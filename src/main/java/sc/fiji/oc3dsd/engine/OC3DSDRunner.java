package sc.fiji.oc3dsd.engine;

import ij.ImagePlus;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import sc.fiji.oc3dsd.api.OC3DSDParameters;
import sc.fiji.oc3dsd.api.OC3DSDResult;
import sc.fiji.oc3d.core.label.LabelRenumberer;
import sc.fiji.oc3d.core.map.ObjectMapBuilder;
import sc.fiji.oc3d.core.measure.LabelFeatureAccumulator;
import sc.fiji.oc3d.core.progress.StatusBarProgress;
import sc.fiji.oc3dsd.runtime.DependencyDoctor;
import sc.fiji.oc3dsd.runtime.ModelResolver;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Orchestrates one run: detect, link, measure, filter, map.
 * <p>
 * The order matters and is not arbitrary. Detector-level filters run inside the
 * detection stage, on the detector's own features, so obvious noise never
 * reaches the expensive measurement pass. The size bounds and the edge rule run
 * afterwards, on real measured voxel counts and bounding boxes, because they are
 * meaningless before anything has been measured. Objects are renumbered after
 * each filtering stage so the label image never carries holes.
 */
public final class OC3DSDRunner {

    /** Columns the detector contributes, appended after the measured columns. */
    private static final String[] DETECTOR_COLUMNS = {
            StarDistTrackMateRunner.COL_SLICES,
            StarDistTrackMateRunner.COL_TRACK_ID,
            StarDistTrackMateRunner.COL_QUALITY_MEAN,
            StarDistTrackMateRunner.COL_AREA_MEAN,
            StarDistTrackMateRunner.COL_INTENSITY_MEAN,
    };

    private OC3DSDRunner() {
    }

    /**
     * The image the intensity columns are measured from.
     * <p>
     * A named redirect wins. With none — the default, and what most runs use —
     * the source is the analysed channel of the input itself, which is what
     * "no redirect" has always meant in 3D Objects Counter and in ImageJ's own
     * {@code Set Measurements}. Returning {@code null} here instead would leave
     * {@code IntDen}, {@code Mean}, {@code StdDev}, {@code Median}, {@code Min}
     * and {@code Max} as {@code NaN} in every row, and silently collapse the
     * centre of mass onto the centroid, for the majority of runs.
     */
    static ImagePlus resolveIntensitySource(OC3DSDParameters params) {
        if (params.intensityImage != null) return params.intensityImage;
        return StarDistTrackMateRunner.analysedChannelStack(params.input, params.channel);
    }

    public static OC3DSDResult run(OC3DSDParameters params) {
        if (params == null) {
            throw new IllegalArgumentException("params must not be null");
        }
        long start = System.currentTimeMillis();

        StatusBarProgress progress = StatusBarProgress.steps(5);

        try {
            File modelFile = params.modelFile == null
                    ? ModelResolver.bundledModel()
                    : params.modelFile;

            // 1. Detect and link.
            progress.step("Detecting objects");
            StarDistTrackMateRunner.Result detection = StarDistTrackMateRunner.run(
                    params.input,
                    params.channel,
                    modelFile,
                    params.probability,
                    params.overlap,
                    params.linking,
                    params.detectorFilters);

            OC3DSDResult result = measureFilterAndMap(
                    detection.getLabelImage(),
                    detection.getDetectorStats(),
                    params,
                    resolveIntensitySource(params),
                    progress,
                    start,
                    detection.getSingleSliceObjects(),
                    detection.getDroppedShortObjects(),
                    detection.getDroppedByDetectorFilters());

            DependencyDoctor.noteRanSuccessfully();

            return result;
        } finally {
            progress.finish("Done");
        }
    }

    /**
     * Everything after detection: measure, filter, renumber, map, summarise.
     * <p>
     * <strong>Internal seam, not public API.</strong> {@code api/} is the
     * documented surface; this exists so the equivalence harness can drive the
     * post-detection pipeline over a corpus of label images without paying for
     * StarDist inference on every fixture. It is the same code {@link
     * #run(OC3DSDParameters)} executes — not a reimplementation of it — which is
     * the whole point: a harness that reproduced this logic could not certify it.
     * <p>
     * The detection-derived counts on the returned result are zero here, because
     * no detection ran. {@code params.input} is used only for its title and
     * calibration; the labels come from {@code labels}, not from it.
     * <p>
     * For the same reason this seam does <strong>not</strong> apply {@link
     * #resolveIntensitySource}'s default: {@code params.input} is not the image
     * {@code labels} was detected in and generally does not even share its
     * geometry, so measuring intensities from it would be measuring the wrong
     * picture. Here {@code params.intensityImage} means exactly what it says,
     * and {@code null} leaves the intensity columns {@code NaN}. Callers driving
     * this directly with a label image of their own supply their own source.
     *
     * @param labels         label image, <strong>modified in place</strong> when
     *                       filtering drops objects
     * @param detectorStats  detector diagnostics joined on {@code Label}, or null
     */
    public static OC3DSDResult measureFilterAndMap(ImagePlus labels,
                                                   ResultsTable detectorStats,
                                                   OC3DSDParameters params) {
        if (params == null) {
            throw new IllegalArgumentException("params must not be null");
        }
        StatusBarProgress progress = StatusBarProgress.none();
        return measureFilterAndMap(labels, detectorStats, params, params.intensityImage,
                progress, System.currentTimeMillis(), 0, 0, 0);
    }

    private static OC3DSDResult measureFilterAndMap(ImagePlus labels,
                                                    ResultsTable detectorStats,
                                                    OC3DSDParameters params,
                                                    ImagePlus intensitySource,
                                                    StatusBarProgress progress,
                                                    long start,
                                                    int singleSliceObjects,
                                                    int droppedShortObjects,
                                                    int droppedByDetectorFilters) {
        Calibration cal = params.input.getCalibration() == null
                ? null : params.input.getCalibration().copy();

        // 2. Measure geometry from the labels, intensities from the source
        //    resolved above. Not from the detector's spot features: those are
        //    diagnostics, and presenting them as morphometry would be wrong
        //    (see StarDistTrackMateRunner's class documentation).
        progress.step("Measuring objects");
        LabelFeatureAccumulator.Result measured = LabelFeatureAccumulator.scan(
                labels, intensitySource, cal);

        // 3. Filter on what was measured, then renumber so the survivors are
        //    contiguous and the maps and tables still join on the label.
        progress.step("Filtering");
        ResultsTable objects = measured.toStatisticsTable(null);
        Set<Integer> keep = survivors(objects, measured, params, labels);
        int droppedByObjectFilters = measured.labelsSorted().size() - keep.size();

        if (droppedByObjectFilters > 0) {
            LabelRenumberer.Result renumbered = LabelRenumberer.renumber(labels, keep);
            measured = LabelFeatureAccumulator.scan(labels, intensitySource, cal);
            objects = measured.toStatisticsTable(null);
            remapDetectorStats(detectorStats, renumbered);
        }
        joinDetectorColumns(objects, detectorStats);

        int objectCount = objects.size();

        // 4. Maps.
        progress.step("Building maps");
        String title = params.input.getTitle();
        ImagePlus objectMap = params.buildObjectMap
                ? ObjectMapBuilder.objectMap(labels, objects, title) : null;
        ImagePlus surfaceMap = params.buildSurfaceMap
                ? ObjectMapBuilder.surfaceMap(labels, objects, title) : null;
        ImagePlus centroidMap = params.buildCentroidMap
                ? ObjectMapBuilder.centroidMap(labels, objects, title) : null;
        ImagePlus comMap = params.buildCentreOfMassMap
                ? ObjectMapBuilder.centerOfMassMap(labels, objects, title) : null;

        // 5. Summary.
        progress.step("Summarising");
        ResultsTable summary = SummaryStatistics.of(objects);

        return new OC3DSDResult(
                objects,
                summary,
                labels,
                objectMap,
                surfaceMap,
                centroidMap,
                comMap,
                objectCount,
                singleSliceObjects,
                droppedShortObjects,
                droppedByDetectorFilters,
                droppedByObjectFilters,
                System.currentTimeMillis() - start);
    }

    // ------------------------------------------------------------------
    // Filtering
    // ------------------------------------------------------------------

    /** Labels that pass the size bounds and the edge rule. */
    private static Set<Integer> survivors(ResultsTable objects,
                                          LabelFeatureAccumulator.Result measured,
                                          OC3DSDParameters params,
                                          ImagePlus labels) {
        Set<Integer> keep = new HashSet<Integer>();
        List<Integer> all = measured.labelsSorted();
        int width = labels.getWidth();
        int height = labels.getHeight();
        int depth = labels.getStackSize();

        for (int row = 0; row < objects.size() && row < all.size(); row++) {
            int label = all.get(row).intValue();
            LabelFeatureAccumulator.FeatureValues values = measured.valuesForLabel(label);
            if (values == null) continue;

            long voxels = values.voxelCount();
            if (voxels < params.minSize) continue;
            if (params.maxSize != Integer.MAX_VALUE && voxels > params.maxSize) continue;

            if (params.excludeOnEdges && touchesEdge(values, width, height, depth)) continue;


            keep.add(Integer.valueOf(label));
        }
        return keep;
    }

    /**
     * Core exposes the bounding box as origin-plus-extent rather than as the
     * min/max pair this read against directly. {@code boundingX() + boundingWidth()
     * - 1} is the old {@code maxX} exactly — integer arithmetic, no rounding.
     * <p>
     * The accessors substitute {@code 0} for an object with no voxels, where the
     * raw fields would still hold their {@code Integer.MAX_VALUE} /
     * {@code MIN_VALUE} sentinels. That case cannot arise here: a
     * {@code FeatureValues} only exists because {@code scan} found a voxel
     * carrying its label, and {@code addVoxel} increments the count first.
     */
    private static boolean touchesEdge(LabelFeatureAccumulator.FeatureValues values,
                                       int width, int height, int depth) {
        return values.boundingX() <= 0
                || values.boundingY() <= 0
                || values.boundingZ() <= 0
                || values.boundingX() + values.boundingWidth() - 1 >= width - 1
                || values.boundingY() + values.boundingHeight() - 1 >= height - 1
                || values.boundingZ() + values.boundingDepth() - 1 >= depth - 1;
    }

    // ------------------------------------------------------------------
    // Detector diagnostics
    // ------------------------------------------------------------------

    /** Rewrites the detector table's labels after a renumbering pass. */
    private static void remapDetectorStats(ResultsTable stats, LabelRenumberer.Result renumbered) {
        if (stats == null || stats.size() == 0) return;
        int labelColumn = stats.getColumnIndex(StarDistTrackMateRunner.COL_LABEL);
        if (labelColumn == ResultsTable.COLUMN_NOT_FOUND) return;

        List<Integer> doomed = new ArrayList<Integer>();
        for (int row = 0; row < stats.size(); row++) {
            int old = (int) stats.getValueAsDouble(labelColumn, row);
            Integer mapped = renumbered.oldToNew().get(Integer.valueOf(old));
            if (mapped == null) {
                doomed.add(Integer.valueOf(row));
            } else {
                stats.setValue(StarDistTrackMateRunner.COL_LABEL, row, mapped.intValue());
            }
        }
        for (int i = doomed.size() - 1; i >= 0; i--) {
            stats.deleteRow(doomed.get(i).intValue());
        }
    }

    /**
     * Appends the detector's diagnostic columns to the measured table, joined on
     * the object label. Kept separate and clearly named so nobody mistakes
     * {@code Detector_Area_Mean} for a real cross-sectional area.
     */
    private static void joinDetectorColumns(ResultsTable objects, ResultsTable stats) {
        if (objects == null || stats == null || stats.size() == 0) return;
        int labelColumn = stats.getColumnIndex(StarDistTrackMateRunner.COL_LABEL);
        if (labelColumn == ResultsTable.COLUMN_NOT_FOUND) return;

        Map<Integer, Integer> rowByLabel = new HashMap<Integer, Integer>();
        for (int row = 0; row < stats.size(); row++) {
            rowByLabel.put(Integer.valueOf((int) stats.getValueAsDouble(labelColumn, row)),
                    Integer.valueOf(row));
        }
        for (int row = 0; row < objects.size(); row++) {
            // Measured rows are written in ascending label order starting at 1.
            Integer source = rowByLabel.get(Integer.valueOf(row + 1));
            if (source == null) continue;
            for (String column : DETECTOR_COLUMNS) {
                int index = stats.getColumnIndex(column);
                if (index == ResultsTable.COLUMN_NOT_FOUND) continue;
                objects.setValue(column, row, stats.getValueAsDouble(index, source.intValue()));
            }
        }
    }
}
