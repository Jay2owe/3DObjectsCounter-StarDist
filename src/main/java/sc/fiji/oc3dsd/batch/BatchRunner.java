package sc.fiji.oc3dsd.batch;

import ij.IJ;
import ij.ImagePlus;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import sc.fiji.oc3dsd.api.OC3DSD;
import sc.fiji.oc3dsd.api.OC3DSDParameters;
import sc.fiji.oc3dsd.api.OC3DSDResult;
import sc.fiji.oc3dsd.engine.SummaryStatistics;
import sc.fiji.oc3dsd.ui.OC3DSDDialogModel;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a whole folder, aggregating on both axes at once.
 * <p>
 * Images are processed strictly one at a time. Detection holds roughly twice the
 * stack in memory while the model is also resident, and TensorFlow inference is
 * process-global anyway, so parallelising here buys nothing and risks running
 * the JVM out of heap halfway through an overnight run.
 */
public final class BatchRunner {

    /** Everything the caller has to decide before a run starts. */
    public static final class Settings {
        public File inputRoot;
        public File outputRoot;
        public boolean recursive = true;
        public String extensions = BatchDiscovery.DEFAULT_EXTENSIONS;
        public String pattern = "";
        public int groupIndex = 1;
        public boolean skipUnmatched = false;
        public boolean saveLabels = true;
        public boolean saveMaps = false;
    }

    /** What a run produced. */
    public static final class Outcome {
        public final int imagesProcessed;
        public final int imagesFailed;
        public final int totalObjects;
        public final File outputRoot;

        Outcome(int processed, int failed, int objects, File outputRoot) {
            this.imagesProcessed = processed;
            this.imagesFailed = failed;
            this.totalObjects = objects;
            this.outputRoot = outputRoot;
        }
    }

    private static final String[] MANIFEST_HEADINGS = {
            "file", "folder", "group", "unit", "pixel_width", "pixel_depth",
            "model", "probability", "overlap",
            "linking_distance", "linking_distance_px", "gap_distance", "slice_gap", "min_slices",
            "min_size", "max_size", "exclude_edges",
            "objects", "single_slice_objects", "dropped_short",
            "dropped_detector_filters", "dropped_morphology_filters", "elapsed_ms", "status",
    };

    /**
     * How one image becomes a result. Production detects; the equivalence harness
     * substitutes a prepared result so a whole batch run can be recorded without
     * StarDist inference.
     * <p>
     * This is the only seam in this class, and it is deliberately the narrowest
     * one that works: everything a user sees from a batch run — discovery,
     * grouping, folder keys, aggregation on both axes, every CSV and the manifest
     * — is downstream of it and therefore exercised for real, not reimplemented
     * by a test that would then be certifying itself.
     */
    interface ResultSource {
        OC3DSDResult resultFor(ImagePlus image, OC3DSDParameters params);
    }

    private static final ResultSource DETECT = new ResultSource() {
        @Override
        public OC3DSDResult resultFor(ImagePlus image, OC3DSDParameters params) {
            return OC3DSD.run(params);
        }
    };

    private BatchRunner() {
    }

    public static Outcome run(Settings settings, OC3DSDDialogModel model) {
        return run(settings, model, DETECT);
    }

    static Outcome run(Settings settings, OC3DSDDialogModel model, ResultSource source) {
        if (settings == null || settings.inputRoot == null || !settings.inputRoot.isDirectory()) {
            throw new IllegalArgumentException("Choose an input folder that exists.");
        }
        File outputRoot = settings.outputRoot == null ? settings.inputRoot : settings.outputRoot;

        // The plugin's own output folder, not outputRoot itself: when the output
        // is left blank it *is* the input root, and excluding that would exclude
        // every input.
        File resultsFolder = new File(outputRoot, BatchWriter.ROOT_FOLDER);
        List<File> files = BatchDiscovery.discover(
                settings.inputRoot, settings.recursive, settings.extensions, resultsFolder);
        List<BatchDiscovery.Item> items = BatchDiscovery.group(
                files, settings.pattern, settings.groupIndex);

        BatchWriter writer = new BatchWriter(outputRoot);
        writer.prepare();

        // Two aggregations on different axes, both written. A folder tree and an
        // experimental design are rarely the same partition, and making the user
        // pick one in advance means re-running to see the other.
        Map<String, ResultsTable> byGroup = new LinkedHashMap<String, ResultsTable>();
        Map<String, ResultsTable> byFolder = new LinkedHashMap<String, ResultsTable>();
        ResultsTable everything = new ResultsTable();
        List<String[]> manifest = new ArrayList<String[]>();

        int processed = 0;
        int failed = 0;
        int totalObjects = 0;

        for (int i = 0; i < items.size(); i++) {
            BatchDiscovery.Item item = items.get(i);
            if (settings.skipUnmatched && BatchDiscovery.UNGROUPED.equals(item.groupKey)) continue;

            IJ.showStatus("3D Objects Counter - StarDist: " + (i + 1) + "/" + items.size()
                    + "  " + item.file.getName());
            IJ.showProgress(i, items.size());

            ImagePlus image = null;
            try {
                image = IJ.openImage(item.file.getAbsolutePath());
                if (image == null) {
                    throw new IllegalStateException("the file could not be opened");
                }
                OC3DSDParameters params = model.toParameters(image, null,
                        new OC3DSDParameters.WarningSink() {
                            @Override public void warn(String message) {
                                IJ.log("WARNING: " + message);
                            }
                        });
                OC3DSDResult result = source.resultFor(image, params);

                String base = baseName(item.file);
                writer.writeObjects(base, result.getObjects());
                if (settings.saveLabels) writer.writeLabels(base, result.getLabelImage());
                if (settings.saveMaps) {
                    writer.writeMap(base, "_objects_map", result.getObjectMap());
                    writer.writeMap(base, "_surface_map", result.getSurfaceMap());
                    writer.writeMap(base, "_centroids_map", result.getCentroidMap());
                    writer.writeMap(base, "_com_map", result.getCentreOfMassMap());
                }

                String folderKey = folderKey(settings.inputRoot, item.file);
                appendTagged(everything, result.getObjects(), item.file.getName(), item.groupKey, folderKey);
                appendTagged(bucket(byGroup, item.groupKey), result.getObjects(),
                        item.file.getName(), item.groupKey, folderKey);
                appendTagged(bucket(byFolder, folderKey), result.getObjects(),
                        item.file.getName(), item.groupKey, folderKey);

                manifest.add(manifestRow(item, folderKey, model, image, result, "ok"));
                totalObjects += result.getObjectCount();
                processed++;
            } catch (RuntimeException failure) {
                failed++;
                String reason = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                IJ.log("WARNING: " + item.file.getName() + " failed: " + reason);
                manifest.add(failureRow(item, settings.inputRoot, model, reason));
            } finally {
                if (image != null) {
                    image.changes = false;
                    image.close();
                    image.flush();
                }
            }
        }
        IJ.showProgress(1.0);

        for (Map.Entry<String, ResultsTable> entry : byGroup.entrySet()) {
            writer.writeGroupObjects(entry.getKey(), entry.getValue());
            writer.writeGroupSummary(entry.getKey(), SummaryStatistics.of(entry.getValue()));
        }
        writer.writeSummary("summary.csv", SummaryStatistics.of(everything));
        writer.writeSummary("groups.csv", countsTable(byGroup, "Group"));
        writer.writeSummary("per_folder_summary.csv", countsTable(byFolder, "Folder"));
        writer.writeManifest(manifest, MANIFEST_HEADINGS);

        IJ.log("3D Objects Counter - StarDist batch: " + processed + " image(s), "
                + totalObjects + " object(s)"
                + (failed > 0 ? ", " + failed + " failed" : "")
                + ". Output: " + writer.root().getAbsolutePath());

        return new Outcome(processed, failed, totalObjects, writer.root());
    }

    // ------------------------------------------------------------------

    private static ResultsTable bucket(Map<String, ResultsTable> map, String key) {
        ResultsTable table = map.get(key);
        if (table == null) {
            table = new ResultsTable();
            map.put(key, table);
        }
        return table;
    }

    /** Copies a per-image table into an aggregate, tagging the provenance columns. */
    private static void appendTagged(ResultsTable target, ResultsTable source,
                                     String fileName, String groupKey, String folderKey) {
        if (target == null || source == null) return;
        String[] headings = source.getHeadings();
        for (int row = 0; row < source.size(); row++) {
            target.incrementCounter();
            target.addValue("File", fileName);
            target.addValue("Group", groupKey);
            target.addValue("Folder", folderKey);
            for (String heading : headings) {
                int column = source.getColumnIndex(heading);
                if (column == ResultsTable.COLUMN_NOT_FOUND) continue;
                String text = source.getStringValue(heading, row);
                double value = source.getValueAsDouble(column, row);
                if (Double.isNaN(value) && text != null && !text.isEmpty()) {
                    target.addValue(heading, text);
                } else {
                    target.addValue(heading, value);
                }
            }
        }
    }

    private static ResultsTable countsTable(Map<String, ResultsTable> buckets, String keyHeading) {
        ResultsTable table = new ResultsTable();
        for (Map.Entry<String, ResultsTable> entry : buckets.entrySet()) {
            table.incrementCounter();
            table.addValue(keyHeading, entry.getKey());
            table.addValue("Objects", entry.getValue().size());
        }
        return table;
    }

    private static String[] manifestRow(BatchDiscovery.Item item,
                                        String folderKey,
                                        OC3DSDDialogModel model,
                                        ImagePlus image,
                                        OC3DSDResult result,
                                        String status) {
        Calibration cal = image.getCalibration();
        boolean calibrated = cal != null && cal.scaled();
        String unit = calibrated ? cal.getUnit() : "pixel";
        double pw = calibrated ? cal.pixelWidth : 1.0;
        double pd = calibrated ? cal.pixelDepth : 1.0;

        return new String[]{
                item.file.getAbsolutePath(),
                folderKey,
                item.groupKey,
                unit,
                Double.toString(pw),
                Double.toString(pd),
                sc.fiji.oc3dsd.runtime.ModelResolver.displayName(model.modelRef),
                Double.toString(model.probability),
                Double.toString(model.overlap),
                Double.toString(model.linkingDistance),
                Double.toString(model.linkingDistanceInPixels(image)),
                Double.toString(model.gapDistance),
                Integer.toString(model.sliceGap),
                Integer.toString(model.minSlices),
                Integer.toString(model.minSize),
                model.maxSize == Integer.MAX_VALUE ? "Infinity" : Integer.toString(model.maxSize),
                Boolean.toString(model.excludeOnEdges),
                Integer.toString(result.getObjectCount()),
                Integer.toString(result.getSingleSliceObjects()),
                Integer.toString(result.getDroppedShortObjects()),
                Integer.toString(result.getDroppedByDetectorFilters()),
                Integer.toString(result.getDroppedByMorphologyFilters()),
                Long.toString(result.getElapsedMs()),
                status,
        };
    }

    private static String[] failureRow(BatchDiscovery.Item item, File inputRoot,
                                       OC3DSDDialogModel model, String reason) {
        String[] row = new String[MANIFEST_HEADINGS.length];
        for (int i = 0; i < row.length; i++) row[i] = "";
        row[0] = item.file.getAbsolutePath();
        row[1] = folderKey(inputRoot, item.file);
        row[2] = item.groupKey;
        row[6] = sc.fiji.oc3dsd.runtime.ModelResolver.displayName(model.modelRef);
        row[row.length - 1] = "failed: " + reason;
        return row;
    }

    private static String folderKey(File root, File file) {
        File parent = file.getParentFile();
        if (parent == null) return ".";
        String rootPath = root.getAbsolutePath();
        String parentPath = parent.getAbsolutePath();
        if (parentPath.equals(rootPath)) return ".";
        if (parentPath.startsWith(rootPath)) {
            String relative = parentPath.substring(rootPath.length());
            while (relative.startsWith(File.separator)) relative = relative.substring(1);
            return relative.isEmpty() ? "." : relative;
        }
        return parentPath;
    }

    static String baseName(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
