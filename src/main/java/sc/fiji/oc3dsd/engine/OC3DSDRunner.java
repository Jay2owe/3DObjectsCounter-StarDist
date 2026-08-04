package sc.fiji.oc3dsd.engine;

import ij.ImagePlus;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import sc.fiji.oc3dsd.api.MorphPredicate;
import sc.fiji.oc3dsd.api.OC3DSDParameters;
import sc.fiji.oc3dsd.api.OC3DSDResult;
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
 * reaches the expensive measurement pass. Morphology filters run afterwards, on
 * real measured volume and shape, because they are meaningless before anything
 * has been measured. Objects are renumbered after each filtering stage so the
 * label image never carries holes.
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

    public static OC3DSDResult run(OC3DSDParameters params) {
        if (params == null) {
            throw new IllegalArgumentException("params must not be null");
        }
        long start = System.currentTimeMillis();

        ProgressReporter progress = ProgressReporter.steps(5);

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

            ImagePlus labels = detection.getLabelImage();
            Calibration cal = params.input.getCalibration() == null
                    ? null : params.input.getCalibration().copy();

            // 2. Measure from the labels. Not from the detector's spot features:
            //    those are diagnostics, and presenting them as morphometry would
            //    be wrong (see StarDistTrackMateRunner's class documentation).
            progress.step("Measuring objects");
            LabelMeasurements.Result measured = LabelMeasurements.scan(
                    labels, params.intensityImage, cal);

            // 3. Filter on what was measured, then renumber so the survivors are
            //    contiguous and the maps and tables still join on the label.
            progress.step("Filtering");
            ResultsTable objects = measured.toStatisticsTable(null);
            Set<Integer> keep = survivors(objects, measured, params, labels);
            int droppedByMorphology = measured.labelsSorted().size() - keep.size();

            if (droppedByMorphology > 0) {
                LabelRenumberer.Result renumbered = LabelRenumberer.renumber(labels, keep);
                measured = LabelMeasurements.scan(labels, params.intensityImage, cal);
                objects = measured.toStatisticsTable(null);
                remapDetectorStats(detection.getDetectorStats(), renumbered);
            }
            joinDetectorColumns(objects, detection.getDetectorStats());

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

            DependencyDoctor.noteRanSuccessfully();

            return new OC3DSDResult(
                    objects,
                    summary,
                    labels,
                    objectMap,
                    surfaceMap,
                    centroidMap,
                    comMap,
                    objectCount,
                    detection.getSingleSliceObjects(),
                    detection.getDroppedShortObjects(),
                    detection.getDroppedByDetectorFilters(),
                    droppedByMorphology,
                    System.currentTimeMillis() - start);
        } finally {
            progress.finish("Done");
        }
    }

    // ------------------------------------------------------------------
    // Filtering
    // ------------------------------------------------------------------

    /** Labels that pass the size bounds, the edge rule and every morphology predicate. */
    private static Set<Integer> survivors(ResultsTable objects,
                                          LabelMeasurements.Result measured,
                                          OC3DSDParameters params,
                                          ImagePlus labels) {
        Set<Integer> keep = new HashSet<Integer>();
        List<Integer> all = measured.labelsSorted();
        int width = labels.getWidth();
        int height = labels.getHeight();
        int depth = labels.getStackSize();

        for (int row = 0; row < objects.size() && row < all.size(); row++) {
            int label = all.get(row).intValue();
            LabelMeasurements.FeatureValues values = measured.valuesForLabel(label);
            if (values == null) continue;

            long voxels = values.voxelCount;
            if (voxels < params.minSize) continue;
            if (params.maxSize != Integer.MAX_VALUE && voxels > params.maxSize) continue;

            if (params.excludeOnEdges && touchesEdge(values, width, height, depth)) continue;

            if (!passesAll(params.morphPredicates, values, objects, row, params)) continue;

            keep.add(Integer.valueOf(label));
        }
        return keep;
    }

    private static boolean touchesEdge(LabelMeasurements.FeatureValues values,
                                       int width, int height, int depth) {
        return values.minX <= 0 || values.minY <= 0 || values.minZ <= 0
                || values.maxX >= width - 1
                || values.maxY >= height - 1
                || values.maxZ >= depth - 1;
    }

    private static boolean passesAll(List<MorphPredicate> predicates,
                                     LabelMeasurements.FeatureValues values,
                                     ResultsTable objects,
                                     int row,
                                     OC3DSDParameters params) {
        if (predicates == null || predicates.isEmpty()) return true;
        for (MorphPredicate predicate : predicates) {
            Double observed = featureValue(predicate.featureName, values, objects, row);
            if (observed == null) {
                params.warningSink.warn("Unknown filter feature '" + predicate.featureName
                        + "'; the filter was ignored.");
                continue;
            }
            if (Double.isNaN(observed.doubleValue())) {
                // A feature that could not be computed for this object cannot
                // exclude it. Silently dropping such objects would make the
                // count depend on a measurement failure.
                continue;
            }
            if (!predicate.matches(observed.doubleValue())) return false;
        }
        return true;
    }

    /** Maps a macro feature name to the measured value, or null when unknown. */
    private static Double featureValue(String feature,
                                       LabelMeasurements.FeatureValues values,
                                       ResultsTable objects,
                                       int row) {
        if (feature == null) return null;
        String name = feature.trim().toLowerCase(java.util.Locale.ROOT);
        if ("sphericity".equals(name)) return Double.valueOf(values.sphericity());
        if ("compactness".equals(name)) return Double.valueOf(values.compactness());
        if ("elongation".equals(name)) return Double.valueOf(values.elongation());
        if ("volume".equals(name)) return Double.valueOf(values.voxelCount);
        if ("volume_calibrated".equals(name)) return Double.valueOf(values.calibratedVolume);
        if ("surface_area".equals(name)) return Double.valueOf(values.surfaceArea);
        if ("mean_intensity".equals(name)) return Double.valueOf(values.meanIntensity());
        if ("max_intensity".equals(name)) return Double.valueOf(values.maxIntensity());
        if ("feret_diameter_max".equals(name)) return Double.valueOf(values.feretDiameterMax());
        // Anything else may still be a column the table carries.
        int column = objects.getColumnIndex(feature);
        if (column != ResultsTable.COLUMN_NOT_FOUND) {
            return Double.valueOf(objects.getValueAsDouble(column, row));
        }
        return null;
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
