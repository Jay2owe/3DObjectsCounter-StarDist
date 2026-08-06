package sc.fiji.oc3dsd.engine;

import fiji.plugin.trackmate.Model;
import fiji.plugin.trackmate.Settings;
import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.SpotCollection;
import fiji.plugin.trackmate.TrackMate;
import fiji.plugin.trackmate.action.LabelImgExporter;
import fiji.plugin.trackmate.detection.DetectorKeys;
import fiji.plugin.trackmate.stardist.StarDistCustomDetectorFactory;
import fiji.plugin.trackmate.tracking.jaqaman.SparseLAPTrackerFactory;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.process.ImageProcessor;

import sc.fiji.oc3d.core.label.LabelRenumberer;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detects objects with StarDist on every Z-slice and links the per-slice
 * detections through Z with TrackMate's LAP tracker, producing a 3D label image.
 * <p>
 * <strong>What this is and is not.</strong> StarDist is a 2D detector. There is
 * no 3D StarDist model for Fiji. Objects here are assembled by detecting in 2D
 * slice by slice and linking across Z — the approach documented on TrackMate's
 * StarDist detector page — by presenting the Z axis to TrackMate as if it were
 * time. It works well when an object overlaps itself between consecutive slices,
 * and less well for objects that are strongly concave in Z or sampled with a
 * large Z-step. The result records how many slices each object spans so those
 * cases are visible.
 * <p>
 * <strong>This class produces objects; it does not measure them.</strong> The
 * per-object values it returns are the detector's own diagnostics — mean
 * confidence, mean 2D detection area, mean detection intensity — and they are
 * explicitly not morphometry. {@code Detector_Area_Mean} is the mean of
 * {@code pi * r^2} over the slices of an object: it is not a volume, not a
 * cross-section at any real position, and not comparable between objects of
 * different heights. Real volume, surface, sphericity and the rest are measured
 * from the label image afterwards, by the measurement layer.
 * <p>
 * Adapted from FLASH's {@code flash.pipeline.stardist.StarDist3DRunner}, with
 * five corrections that a pipeline running one image under a human's eye could
 * tolerate and an unattended batch counter cannot. Each is marked in the code
 * with its ledger ID.
 */
public final class StarDistTrackMateRunner {

    /** Column names in the detector diagnostics table. */
    public static final String COL_LABEL = "Label";
    public static final String COL_FRAME = "Frame";
    public static final String COL_SLICES = "Slices";
    public static final String COL_TRACK_ID = "Detector_Track_ID";
    public static final String COL_QUALITY_MEAN = "Detector_Quality_Mean";
    public static final String COL_AREA_MEAN = "Detector_Area_Mean";
    public static final String COL_INTENSITY_MEAN = "Detector_Intensity_Mean";

    /** Spot feature name for mean intensity in the (single) analysed channel. */
    private static final String SPOT_MEAN_INTENSITY = "MEAN_INTENSITY_CH1";

    /** Track IDs above this cannot be stored exactly in a 32-bit float pixel. */
    private static final int MAX_EXACT_FLOAT_LABEL = 16_777_216;

    private StarDistTrackMateRunner() {
    }

    /** Outcome of a run. */
    public static final class Result {

        private final ImagePlus labelImage;
        private final ResultsTable detectorStats;
        private final int objectCount;
        private final int singleSliceObjects;
        private final int droppedShortObjects;
        private final int droppedByDetectorFilters;
        private final long elapsedMs;

        Result(ImagePlus labelImage, ResultsTable detectorStats, int objectCount,
               int singleSliceObjects, int droppedShortObjects,
               int droppedByDetectorFilters, long elapsedMs) {
            this.labelImage = labelImage;
            this.detectorStats = detectorStats;
            this.objectCount = objectCount;
            this.singleSliceObjects = singleSliceObjects;
            this.droppedShortObjects = droppedShortObjects;
            this.droppedByDetectorFilters = droppedByDetectorFilters;
            this.elapsedMs = elapsedMs;
        }

        /** 3D label image, objects numbered 1..N per timepoint. Never {@code null}. */
        public ImagePlus getLabelImage() {
            return labelImage;
        }

        /** Detector diagnostics, one row per object, joined to the label image on {@code Label}. */
        public ResultsTable getDetectorStats() {
            return detectorStats;
        }

        public int getObjectCount() {
            return objectCount;
        }

        /**
         * Objects spanning exactly one Z-slice. Reported rather than hidden: a
         * high count usually means the Z-step is too coarse for the linking
         * distance, or that the detector is firing on noise.
         */
        public int getSingleSliceObjects() {
            return singleSliceObjects;
        }

        /** Objects discarded for spanning fewer slices than {@code minSlices}. */
        public int getDroppedShortObjects() {
            return droppedShortObjects;
        }

        /** Objects discarded by the detector-level area/quality/intensity filters. */
        public int getDroppedByDetectorFilters() {
            return droppedByDetectorFilters;
        }

        public long getElapsedMs() {
            return elapsedMs;
        }
    }

    /**
     * Runs detection and linking over a stack.
     *
     * @param input      the image. May be a hyperstack; only {@code channel} is detected on
     * @param channel    1-based channel to detect on
     * @param modelFile  StarDist model {@code .zip}
     * @param probThresh detection probability threshold, 0..1
     * @param nmsThresh  non-maximum-suppression overlap threshold, 0..1
     * @param linking    linking distances and the minimum slice span
     * @param filters    detector-level filters applied before measurement
     */
    public static Result run(ImagePlus input,
                             int channel,
                             File modelFile,
                             double probThresh,
                             double nmsThresh,
                             StarDistLinkingParams linking,
                             StarDistPostFilters filters) {

        long start = System.currentTimeMillis();

        // Before anything expensive: refuse a TrackMate whose detector API this
        // plugin was not built against. The catch of LinkageError further down
        // stays as the backstop, but by the time it fires the model has been
        // loaded and slices have been detected. This costs one reflective field
        // read and turns a mid-run failure into a sentence.
        requireSupportedTrackMate();

        if (input == null) {
            throw new DetectionRunFailureException("No input image.");
        }
        if (input.getStackSize() == 0) {
            throw new DetectionRunFailureException("Input image has no slices.");
        }
        if (modelFile == null || !modelFile.isFile()) {
            throw new DetectionRunFailureException(
                    "StarDist model file not found: " + (modelFile == null ? "<null>" : modelFile.getPath()));
        }
        requireUnitThreshold("Probability", probThresh);
        requireUnitThreshold("Overlap (NMS)", nmsThresh);

        StarDistLinkingParams link = linking == null ? StarDistLinkingParams.defaults() : linking;
        StarDistPostFilters filt = filters == null ? StarDistPostFilters.none() : filters;

        int nChannels = Math.max(1, input.getNChannels());
        int nSlices = Math.max(1, input.getNSlices());
        int nFrames = Math.max(1, input.getNFrames());
        int c = Math.min(Math.max(1, channel), nChannels);

        Calibration cal = input.getCalibration() == null ? null : input.getCalibration().copy();
        logCalibration(cal, link);

        // ---- D2: Z and T are separate axes -------------------------------
        // FLASH collapsed them with setDimensions(c, 1, z * t), which lets the
        // tracker link the last Z of frame k to the first Z of frame k+1 and
        // silently merges timepoints into single objects. Each timepoint is
        // detected and linked on its own, then the frames are reassembled.
        List<ImagePlus> perFrameLabels = new ArrayList<ImagePlus>();
        ResultsTable stats = new ResultsTable();
        int totalObjects = 0;
        int totalSingleSlice = 0;
        int totalDroppedShort = 0;
        int totalDroppedFilters = 0;

        for (int t = 1; t <= nFrames; t++) {
            ImagePlus zStack = extractChannelStack(input, c, t, nSlices);
            FrameResult fr = runSingleTimepoint(zStack, modelFile, probThresh, nmsThresh, link, filt, cal);
            perFrameLabels.add(fr.labels);
            totalObjects += fr.objectCount;
            totalSingleSlice += fr.singleSliceObjects;
            totalDroppedShort += fr.droppedShort;
            totalDroppedFilters += fr.droppedByFilters;
            appendStats(stats, fr, t, nFrames);
        }

        ImagePlus labelImage = assemble(perFrameLabels, nSlices, nFrames, cal);
        narrowLabelDepth(labelImage, totalObjects);
        labelImage.setTitle("Label Image");

        long elapsed = System.currentTimeMillis() - start;
        IJ.log("    3D Objects Counter - StarDist: " + totalObjects + " object(s) in "
                + elapsed + " ms"
                + (totalSingleSlice > 0 ? " [" + totalSingleSlice + " span a single slice]" : "")
                + (totalDroppedShort > 0 ? " [" + totalDroppedShort + " dropped below min slices]" : "")
                + (totalDroppedFilters > 0 ? " [" + totalDroppedFilters + " dropped by detector filters]" : ""));

        return new Result(labelImage, stats, totalObjects, totalSingleSlice,
                totalDroppedShort, totalDroppedFilters, elapsed);
    }

    // ------------------------------------------------------------------
    // One timepoint
    // ------------------------------------------------------------------

    private static final class FrameResult {
        ImagePlus labels;
        int objectCount;
        int singleSliceObjects;
        int droppedShort;
        int droppedByFilters;
        /** Per new label: slice span, original detector label, and the three detector means. */
        Map<Integer, ObjectStats> byLabel = new HashMap<Integer, ObjectStats>();
    }

    private static final class ObjectStats {
        int detectorLabel;
        int slices;
        double qualityMean = Double.NaN;
        double areaMean = Double.NaN;
        double intensityMean = Double.NaN;
    }

    private static FrameResult runSingleTimepoint(ImagePlus zStack,
                                                  File modelFile,
                                                  double probThresh,
                                                  double nmsThresh,
                                                  StarDistLinkingParams link,
                                                  StarDistPostFilters filt,
                                                  Calibration cal) {

        int z = zStack.getStackSize();

        // Pad the channel axis from 1 to 2 so TrackMate's isHyperStack() check
        // passes via getNDimensions() > 3 rather than via a visible window. The
        // detector is pinned to channel 1, so the padding channel is ignored.
        // Peak memory roughly doubles for the duration of the call.
        ImagePlus padded = padChannel(zStack);
        padded.setDimensions(2, 1, z);
        padded.setOpenAsHyperStack(true);
        if (cal != null) padded.setCalibration(cal.copy());

        FrameResult out = new FrameResult();

        try {
            Model model = new Model();
            model.setLogger(fiji.plugin.trackmate.Logger.VOID_LOGGER);

            Settings settings = new Settings(padded);
            settings.addAllAnalyzers();
            configureDetector(settings, probThresh, nmsThresh, modelFile);

            settings.trackerFactory = new SparseLAPTrackerFactory();
            settings.trackerSettings = settings.trackerFactory.getDefaultSettings();
            settings.trackerSettings.put("LINKING_MAX_DISTANCE", Double.valueOf(link.linkingMaxDistance));
            settings.trackerSettings.put("GAP_CLOSING_MAX_DISTANCE", Double.valueOf(link.gapClosingMaxDistance));
            settings.trackerSettings.put("MAX_FRAME_GAP", Integer.valueOf(link.maxSliceGap));

            TrackMate trackmate = new TrackMate(model, settings);

            if (!trackmate.checkInput()) {
                throw new DetectionRunFailureException(
                        "TrackMate input check failed: " + safeMessage(trackmate.getErrorMessage()));
            }
            if (!trackmate.execDetection()) {
                throw new DetectionRunFailureException(
                        "StarDist detection failed: " + safeMessage(trackmate.getErrorMessage()));
            }
            if (!trackmate.computeSpotFeatures(false)) {
                throw new DetectionRunFailureException(
                        "Spot feature computation failed: " + safeMessage(trackmate.getErrorMessage()));
            }
            trackmate.execInitialSpotFiltering();
            trackmate.execSpotFiltering(false);

            if (model.getSpots().getNSpots(true) == 0) {
                out.labels = emptyLabelStack(zStack.getWidth(), zStack.getHeight(), z, cal);
                return out;
            }

            if (!trackmate.execTracking()) {
                throw new DetectionRunFailureException(
                        "Linking failed: " + safeMessage(trackmate.getErrorMessage()));
            }

            // Partition: spots that belong to a track, and spots that do not.
            Map<Integer, List<Spot>> spotsByTrack = new HashMap<Integer, List<Spot>>();
            Set<Spot> tracked = new HashSet<Spot>();
            for (Integer trackId : model.getTrackModel().trackIDs(true)) {
                Set<Spot> spots = model.getTrackModel().trackSpots(trackId);
                if (spots == null || spots.isEmpty()) continue;
                spotsByTrack.put(trackId, new ArrayList<Spot>(spots));
                tracked.addAll(spots);
            }
            List<Spot> singletons = new ArrayList<Spot>();
            for (Spot spot : model.getSpots().iterable(true)) {
                if (!tracked.contains(spot)) singletons.add(spot);
            }

            // ---- D1: single-slice objects are objects -------------------
            // FLASH deleted every spot the tracker could not link and exported
            // tracks only, so an object present on exactly one Z-slice vanished
            // with no warning. Objects thinner than two Z-steps and objects
            // clipped by the ends of the stack were dropped systematically, and
            // the count silently tracked the acquisition's Z-step rather than
            // the sample. Singletons are now real objects when minSlices allows,
            // and are counted either way.
            Map<Integer, ObjectStats> candidates = new HashMap<Integer, ObjectStats>();
            int droppedShort = 0;

            for (Map.Entry<Integer, List<Spot>> e : spotsByTrack.entrySet()) {
                List<Spot> spots = e.getValue();
                if (spots.size() < link.minSlices) {
                    droppedShort++;
                    continue;
                }
                int label = trackLabel(e.getKey());
                candidates.put(Integer.valueOf(label), summarise(spots, label));
            }

            ImagePlus trackedLabels = spotsByTrack.isEmpty()
                    ? emptyLabelStack(zStack.getWidth(), zStack.getHeight(), z, cal)
                    : LabelImgExporter.createLabelImagePlus(
                            model, padded, false, true,
                            LabelImgExporter.LabelIdPainting.LABEL_IS_TRACK_ID);
            detach(trackedLabels);

            int singleSlice = 0;
            if (link.minSlices <= 1 && !singletons.isEmpty()) {
                int offset = maxLabel(trackedLabels);
                // Export the unlinked spots on their own, labelled by spot ID so
                // each painted region can be mapped back to the spot that made
                // it, then shifted clear of the track labels before merging.
                SpotCollection loose = new SpotCollection();
                for (Spot spot : singletons) {
                    Double frame = spot.getFeature(Spot.FRAME);
                    if (frame == null) continue;
                    loose.add(spot, Integer.valueOf(frame.intValue()));
                }
                loose.setVisible(true);
                Model looseModel = new Model();
                looseModel.setLogger(fiji.plugin.trackmate.Logger.VOID_LOGGER);
                looseModel.setSpots(loose, false);

                ImagePlus looseLabels = LabelImgExporter.createLabelImagePlus(
                        looseModel, padded, false, false,
                        LabelImgExporter.LabelIdPainting.LABEL_IS_SPOT_ID);
                detach(looseLabels);

                mergeWithOffset(trackedLabels, looseLabels, offset);
                for (Spot spot : singletons) {
                    int label = spot.ID() + offset;
                    ObjectStats st = summarise(java.util.Collections.singletonList(spot), label);
                    candidates.put(Integer.valueOf(label), st);
                    singleSlice++;
                }
            } else if (!singletons.isEmpty()) {
                droppedShort += singletons.size();
            }

            // ---- D5: linking distance is in calibrated units -------------
            // Handled at entry by logCalibration; nothing to do here beyond
            // having passed the calibrated value straight through to TrackMate,
            // which is what it expects.

            // Detector-level filters, then a single renumbering pass that both
            // erases the rejects and makes the surviving labels contiguous.
            Set<Integer> keep = new HashSet<Integer>();
            int droppedByFilters = 0;
            for (Map.Entry<Integer, ObjectStats> e : candidates.entrySet()) {
                if (passes(e.getValue(), filt)) {
                    keep.add(e.getKey());
                } else {
                    droppedByFilters++;
                }
            }

            // ---- D4: contiguous, deterministic object numbering ----------
            LabelRenumberer.Result renumbered = LabelRenumberer.renumber(trackedLabels, keep);

            Map<Integer, ObjectStats> finalStats = new HashMap<Integer, ObjectStats>();
            int finalSingleSlice = 0;
            for (Map.Entry<Integer, Integer> e : renumbered.oldToNew().entrySet()) {
                ObjectStats st = candidates.get(e.getKey());
                if (st == null) continue;
                finalStats.put(e.getValue(), st);
                if (st.slices <= 1) finalSingleSlice++;
            }

            trackedLabels.setDimensions(1, z, 1);
            if (cal != null) trackedLabels.setCalibration(cal.copy());

            out.labels = trackedLabels;
            out.objectCount = renumbered.objectCount();
            out.singleSliceObjects = finalSingleSlice;
            out.droppedShort = droppedShort;
            out.droppedByFilters = droppedByFilters;
            out.byLabel = finalStats;
            return out;

        } catch (DetectionRunFailureException e) {
            throw e;
        } catch (Exception e) {
            throw new DetectionRunFailureException("StarDist detection failed: " + summarise(e), e);
        } catch (LinkageError e) {
            throw new DetectionRunFailureException(
                    "StarDist failed due to an incompatible runtime. Run the plugin's Install "
                            + "Runtime repair, restart Fiji, and try again: " + summarise(e), e);
        } finally {
            padded.changes = false;
            padded.flush();
        }
    }

    // ------------------------------------------------------------------
    // Detector configuration
    // ------------------------------------------------------------------

    @SuppressWarnings({"rawtypes", "unchecked"})
    static void configureDetector(Settings settings, double probThresh, double nmsThresh, File modelFile) {
        settings.detectorFactory = new StarDistCustomDetectorFactory();
        settings.detectorSettings = settings.detectorFactory.getDefaultSettings();
        settings.detectorSettings.put(DetectorKeys.KEY_TARGET_CHANNEL, Integer.valueOf(1));
        settings.detectorSettings.put(StarDistCustomDetectorFactory.KEY_MODEL_FILEPATH,
                modelFile.getAbsolutePath());
        settings.detectorSettings.put(StarDistCustomDetectorFactory.KEY_SCORE_THRESHOLD,
                Double.valueOf(probThresh));
        settings.detectorSettings.put(StarDistCustomDetectorFactory.KEY_OVERLAP_THRESHOLD,
                Double.valueOf(nmsThresh));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * TrackMate's {@code LABEL_IS_TRACK_ID} contract: zero is background, so a
     * track paints {@code trackId + 1}.
     */
    static int trackLabel(Integer trackId) {
        if (trackId == null || trackId.intValue() < 0 || trackId.intValue() >= MAX_EXACT_FLOAT_LABEL) {
            throw new DetectionRunFailureException(
                    "TrackMate track ID outside the exactly representable range: " + trackId);
        }
        return trackId.intValue() + 1;
    }

    private static ObjectStats summarise(List<Spot> spots, int detectorLabel) {
        ObjectStats st = new ObjectStats();
        st.detectorLabel = detectorLabel;
        Set<Integer> frames = new HashSet<Integer>();
        Mean quality = new Mean();
        Mean area = new Mean();
        Mean intensity = new Mean();
        for (Spot spot : spots) {
            Double frame = spot.getFeature(Spot.FRAME);
            if (frame != null) frames.add(Integer.valueOf(frame.intValue()));
            quality.add(spot.getFeature(Spot.QUALITY));
            intensity.add(spot.getFeature(SPOT_MEAN_INTENSITY));
            Double radius = spot.getFeature(Spot.RADIUS);
            if (radius != null && Double.isFinite(radius.doubleValue())) {
                area.add(Double.valueOf(Math.PI * radius.doubleValue() * radius.doubleValue()));
            }
        }
        st.slices = Math.max(1, frames.size());
        st.qualityMean = quality.mean();
        st.areaMean = area.mean();
        st.intensityMean = intensity.mean();
        return st;
    }

    private static boolean passes(ObjectStats st, StarDistPostFilters filt) {
        if (!filt.isActive()) return true;
        if (isReal(st.areaMean)) {
            if (st.areaMean < filt.areaMin) return false;
            if (st.areaMean > filt.areaMax) return false;
        }
        if (filt.qualityMin > 0 && isReal(st.qualityMean) && st.qualityMean < filt.qualityMin) return false;
        if (filt.intensityMin > 0 && isReal(st.intensityMean) && st.intensityMean < filt.intensityMin) return false;
        return true;
    }

    private static boolean isReal(double v) {
        return !Double.isNaN(v) && Double.isFinite(v);
    }

    /** Extracts one channel of one timepoint as a plain single-channel Z-stack. */
    static ImagePlus extractChannelStack(ImagePlus input, int channel, int frame, int nSlices) {
        ImageStack src = input.getStack();
        ImageStack out = new ImageStack(input.getWidth(), input.getHeight());
        for (int z = 1; z <= nSlices; z++) {
            int index = input.getStackIndex(channel, z, frame);
            ImageProcessor ip = src.getProcessor(index);
            // Per-slice duplicate rather than ij.plugin.Duplicator, which is not
            // thread-safe and would make batch parallelisation unsafe later.
            out.addSlice(src.getSliceLabel(index), ip.duplicate());
        }
        ImagePlus imp = new ImagePlus("stardist_input", out);
        if (input.getCalibration() != null) imp.setCalibration(input.getCalibration().copy());
        return imp;
    }

    /**
     * The analysed channel of the input, laid out to match the label image
     * {@link #run} returns: one channel, frames outermost, Z innermost — the
     * same order {@link #assemble} writes.
     * <p>
     * This is the intensity source a run measures from when the user names no
     * redirect image, which is what 3D Objects Counter has always done: with no
     * redirect, intensities come from the image being analysed. The channel is
     * the one the detector ran on, so the numbers describe the signal the
     * objects were found in.
     * <p>
     * The slices are <strong>shared, not duplicated</strong>, unlike
     * {@link #extractChannelStack}: measurement only ever reads pixels, so a
     * copy of an entire channel would be paid for nothing. The dimensions are
     * therefore guaranteed to match the label image by construction rather than
     * by a check — {@code nSlices * nFrames} slices, built from the same three
     * counts the detection loop uses.
     *
     * @return the intensity source, or {@code null} when {@code input} has no
     *         stack to read
     */
    static ImagePlus analysedChannelStack(ImagePlus input, int channel) {
        if (input == null) return null;
        ImageStack src = input.getStack();
        if (src == null || src.getSize() == 0) return null;

        int nChannels = Math.max(1, input.getNChannels());
        int nSlices = Math.max(1, input.getNSlices());
        int nFrames = Math.max(1, input.getNFrames());
        int c = Math.min(Math.max(1, channel), nChannels);

        ImageStack out = new ImageStack(input.getWidth(), input.getHeight());
        for (int t = 1; t <= nFrames; t++) {
            for (int z = 1; z <= nSlices; z++) {
                int index = input.getStackIndex(c, z, t);
                out.addSlice(src.getSliceLabel(index), src.getProcessor(index));
            }
        }
        ImagePlus imp = new ImagePlus("intensity_source", out);
        imp.setDimensions(1, nSlices, nFrames);
        if (input.getCalibration() != null) imp.setCalibration(input.getCalibration().copy());
        return imp;
    }

    /** Duplicates each slice so the stack has two identical channels. */
    private static ImagePlus padChannel(ImagePlus zStack) {
        ImageStack src = zStack.getStack();
        ImageStack out = new ImageStack(zStack.getWidth(), zStack.getHeight());
        for (int i = 1; i <= src.getSize(); i++) {
            ImageProcessor ip = src.getProcessor(i);
            out.addSlice(ip);
            out.addSlice(ip.duplicate());
        }
        ImagePlus padded = new ImagePlus(zStack.getTitle(), out);
        if (zStack.getCalibration() != null) padded.setCalibration(zStack.getCalibration().copy());
        return padded;
    }

    private static ImagePlus emptyLabelStack(int width, int height, int z, Calibration cal) {
        ImagePlus imp = IJ.createImage("Label Image", "32-bit black", width, height, z);
        imp.setDimensions(1, z, 1);
        if (cal != null) imp.setCalibration(cal.copy());
        return imp;
    }

    /**
     * {@code LabelImgExporter} does not normally show its output, but detach it
     * defensively so later dimension and bit-depth changes never touch the
     * shared WindowManager from a worker thread.
     */
    private static void detach(ImagePlus imp) {
        if (imp != null && imp.getWindow() != null) {
            imp.changes = false;
            imp.hide();
        }
    }

    private static int maxLabel(ImagePlus labelImage) {
        int max = 0;
        ImageStack stack = labelImage.getStack();
        for (int s = 1; s <= stack.getSize(); s++) {
            ImageProcessor ip = stack.getProcessor(s);
            if (ip == null) continue;
            for (int i = 0; i < ip.getPixelCount(); i++) {
                int v = (int) ip.getf(i);
                if (v > max) max = v;
            }
        }
        return max;
    }

    /** Paints every positive label of {@code source} into {@code target}, shifted by {@code offset}. */
    private static void mergeWithOffset(ImagePlus target, ImagePlus source, int offset) {
        ImageStack ts = target.getStack();
        ImageStack ss = source.getStack();
        int n = Math.min(ts.getSize(), ss.getSize());
        for (int s = 1; s <= n; s++) {
            ImageProcessor tp = ts.getProcessor(s);
            ImageProcessor sp = ss.getProcessor(s);
            if (tp == null || sp == null) continue;
            int pixels = Math.min(tp.getPixelCount(), sp.getPixelCount());
            for (int i = 0; i < pixels; i++) {
                float v = sp.getf(i);
                if (v > 0f && tp.getf(i) <= 0f) {
                    tp.setf(i, v + offset);
                }
            }
        }
    }

    /** Reassembles the per-timepoint label stacks into one image with Z and T intact. */
    private static ImagePlus assemble(List<ImagePlus> frames, int nSlices, int nFrames, Calibration cal) {
        if (frames.size() == 1) {
            ImagePlus single = frames.get(0);
            single.setDimensions(1, nSlices, 1);
            if (cal != null) single.setCalibration(cal.copy());
            return single;
        }
        ImagePlus first = frames.get(0);
        ImageStack out = new ImageStack(first.getWidth(), first.getHeight());
        for (ImagePlus frame : frames) {
            ImageStack fs = frame.getStack();
            for (int s = 1; s <= fs.getSize(); s++) {
                out.addSlice(fs.getProcessor(s));
            }
        }
        ImagePlus imp = new ImagePlus("Label Image", out);
        imp.setDimensions(1, nSlices, nFrames);
        imp.setOpenAsHyperStack(true);
        if (cal != null) imp.setCalibration(cal.copy());
        return imp;
    }

    /**
     * TrackMate exports 32-bit float labels. Narrow to 16-bit when every label
     * fits exactly; an unchecked conversion would clip distinct objects onto the
     * same value.
     */
    private static void narrowLabelDepth(ImagePlus labelImage, int objectCount) {
        if (objectCount > 65_535 || labelImage.getBitDepth() == 16) return;
        ImageStack src = labelImage.getStack();
        ImageStack out = new ImageStack(src.getWidth(), src.getHeight());
        for (int s = 1; s <= src.getSize(); s++) {
            ImageProcessor sp = src.getProcessor(s);
            ij.process.ShortProcessor target =
                    new ij.process.ShortProcessor(sp.getWidth(), sp.getHeight());
            for (int i = 0; i < sp.getPixelCount(); i++) {
                target.set(i, (int) sp.getf(i));
            }
            out.addSlice(src.getSliceLabel(s), target);
        }
        int c = labelImage.getNChannels();
        int z = labelImage.getNSlices();
        int t = labelImage.getNFrames();
        labelImage.setStack(out);
        labelImage.setDimensions(c, z, t);
        labelImage.setDisplayRange(0, Math.max(1, objectCount));
    }

    private static void appendStats(ResultsTable table, FrameResult fr, int frame, int nFrames) {
        List<Integer> labels = new ArrayList<Integer>(fr.byLabel.keySet());
        java.util.Collections.sort(labels);
        for (Integer label : labels) {
            ObjectStats st = fr.byLabel.get(label);
            table.incrementCounter();
            table.addValue(COL_LABEL, label.intValue());
            if (nFrames > 1) table.addValue(COL_FRAME, frame);
            table.addValue(COL_SLICES, st.slices);
            table.addValue(COL_TRACK_ID, st.detectorLabel);
            if (isReal(st.qualityMean)) table.addValue(COL_QUALITY_MEAN, st.qualityMean);
            if (isReal(st.areaMean)) table.addValue(COL_AREA_MEAN, st.areaMean);
            if (isReal(st.intensityMean)) table.addValue(COL_INTENSITY_MEAN, st.intensityMean);
        }
    }

    /**
     * D5. TrackMate reads the linking distances in calibrated units, so the same
     * number means five pixels on an uncalibrated stack and fifty at 0.1 um/px.
     * Over-linking merges neighbouring objects, which is the exact failure this
     * plugin exists to avoid, so both readings go into the log.
     */
    private static void logCalibration(Calibration cal, StarDistLinkingParams link) {
        boolean calibrated = cal != null && cal.scaled();
        String unit = calibrated ? cal.getUnit() : "pixel";
        double pw = calibrated ? cal.pixelWidth : 1.0;
        double linkPx = StarDistLinkingParams.pixelEquivalent(link.linkingMaxDistance, pw);
        double gapPx = StarDistLinkingParams.pixelEquivalent(link.gapClosingMaxDistance, pw);
        if (!calibrated) {
            IJ.log("    Image is not spatially calibrated; linking distances are in pixels.");
        }
        IJ.log("    Linking: max " + link.linkingMaxDistance + " " + unit
                + " (" + round2(linkPx) + " px), gap closing " + link.gapClosingMaxDistance + " " + unit
                + " (" + round2(gapPx) + " px), slice gap " + link.maxSliceGap
                + ", min slices " + link.minSlices);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * Fails fast on a TrackMate major version this plugin does not support.
     * <p>
     * Deliberately at engine level rather than only in the plugin classes: the
     * public API ({@code OC3DSD.run}) does not go through them, and a scripted
     * batch is exactly where a mid-run failure costs the most.
     */
    private static void requireSupportedTrackMate() {
        String problem = sc.fiji.oc3dsd.runtime.TrackMateVersion.problem();
        if (problem != null) throw new DetectionRunFailureException(problem);
    }

    private static void requireUnitThreshold(String label, double value) {
        if (!(value >= 0.0 && value <= 1.0) || Double.isNaN(value)) {
            throw new DetectionRunFailureException(label + " must be between 0 and 1, but was " + value + ".");
        }
    }

    private static String safeMessage(String message) {
        return (message == null || message.trim().isEmpty()) ? "no detail reported" : message.trim();
    }

    private static String summarise(Throwable t) {
        if (t == null) return "unknown error";
        String message = t.getMessage();
        return t.getClass().getSimpleName()
                + (message == null || message.trim().isEmpty() ? "" : ": " + message.trim());
    }

    /** Mean of the finite values offered to it; NaN when there were none. */
    private static final class Mean {
        private double sum;
        private int count;

        void add(Double value) {
            if (value == null) return;
            double v = value.doubleValue();
            if (!Double.isNaN(v) && Double.isFinite(v)) {
                sum += v;
                count++;
            }
        }

        double mean() {
            return count == 0 ? Double.NaN : sum / count;
        }
    }
}
