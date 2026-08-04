package sc.fiji.oc3dsd.batch;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import sc.fiji.oc3dsd.api.OC3DSDParameters;
import sc.fiji.oc3dsd.api.OC3DSDResult;
import sc.fiji.oc3dsd.engine.OC3DSDRunner;
import sc.fiji.oc3dsd.ui.OC3DSDDialogModel;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Drives whole batch runs and records what they write.
 *
 * <p>Detection is replaced at {@link BatchRunner.ResultSource}; everything below
 * it is the shipped code. Discovery, the extension filter, recursion, the
 * filename-regex grouping, the folder keys, both aggregation axes, the summary
 * and group tables, the manifest and the CSV quoting are all exercised for real.
 *
 * <p>The images are label images: the substituted result source measures each
 * opened file through {@link OC3DSDRunner#measureFilterAndMap}, the same seam the
 * measurement harness uses. That keeps the numbers in these CSVs the numbers the
 * measurement goldens already pin, so a diff here is a batch-layer difference
 * rather than a measurement one leaking through.
 */
final class BatchHarness {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private BatchHarness() {
    }

    // ------------------------------------------------------------------
    // Configurations
    // ------------------------------------------------------------------

    /** One batch run's settings, named so a diff says which run moved. */
    static final class Config {
        final String name;
        final boolean recursive;
        final String pattern;
        final int groupIndex;
        final boolean skipUnmatched;
        final boolean saveLabels;
        final boolean saveMaps;
        final String extensions;
        final int minSize;
        final boolean excludeOnEdges;

        Config(String name, boolean recursive, String pattern, int groupIndex,
               boolean skipUnmatched, boolean saveLabels, boolean saveMaps,
               String extensions, int minSize, boolean excludeOnEdges) {
            this.name = name;
            this.recursive = recursive;
            this.pattern = pattern;
            this.groupIndex = groupIndex;
            this.skipUnmatched = skipUnmatched;
            this.saveLabels = saveLabels;
            this.saveMaps = saveMaps;
            this.extensions = extensions;
            this.minSize = minSize;
            this.excludeOnEdges = excludeOnEdges;
        }
    }

    private static final String GENOTYPE = "_(wt|ko)_";

    static List<Config> configs() {
        List<Config> configs = new ArrayList<Config>();
        //             name                 recurse pattern     grp  skip   labels maps   ext     min  edges
        configs.add(new Config("baseline",   true,  GENOTYPE,    1, false, false, false, "tif",   1, false));
        configs.add(new Config("flat",       false, GENOTYPE,    1, false, false, false, "tif",   1, false));
        configs.add(new Config("skip_unmatched", true, GENOTYPE, 1, true,  false, false, "tif",   1, false));
        configs.add(new Config("no_pattern", true,  "",          1, false, false, false, "tif",   1, false));
        configs.add(new Config("with_labels_and_maps", true, GENOTYPE, 1, false, true, true, "tif", 1, false));
        configs.add(new Config("all_extensions", true, GENOTYPE, 1, false, false, false, "", 1, false));
        // Filtering changes the object set, which renumbers labels and therefore
        // moves every aggregate: the batch layer must carry that through intact.
        configs.add(new Config("min_size_40", true, GENOTYPE,    1, false, false, false, "tif",  40, false));
        configs.add(new Config("exclude_edges", true, GENOTYPE,  1, false, false, false, "tif",   1, true));
        return configs;
    }

    // ------------------------------------------------------------------
    // Running
    // ------------------------------------------------------------------

    /** Runs one configuration over a freshly built corpus and renders the result. */
    static String record(Config config, File workspace) throws IOException {
        File input = new File(workspace, "in");
        File output = new File(workspace, "out");
        buildCorpus(input);

        BatchRunner.Settings settings = new BatchRunner.Settings();
        settings.inputRoot = input;
        settings.outputRoot = output;
        settings.recursive = config.recursive;
        settings.extensions = config.extensions;
        settings.pattern = config.pattern;
        settings.groupIndex = config.groupIndex;
        settings.skipUnmatched = config.skipUnmatched;
        settings.saveLabels = config.saveLabels;
        settings.saveMaps = config.saveMaps;

        OC3DSDDialogModel model = new OC3DSDDialogModel();
        model.minSize = config.minSize;
        model.excludeOnEdges = config.excludeOnEdges;

        BatchRunner.Outcome outcome = BatchRunner.run(settings, model, MEASURE_ONLY);

        StringBuilder sb = new StringBuilder();
        sb.append("# batch ").append(config.name).append('\n');
        sb.append("processed=").append(outcome.imagesProcessed)
                .append(" failed=").append(outcome.imagesFailed)
                .append(" objects=").append(outcome.totalObjects).append('\n');
        sb.append(BatchCanon.tree(outcome.outputRoot, input));
        return sb.toString();
    }

    /**
     * Measures the opened image as a label image, skipping detection.
     *
     * <p>Detector statistics are null, exactly as they would be for a label image
     * that never went through a detector — which is also what the future
     * "- Labels" variant will do, so this path is not purely hypothetical.
     */
    private static final BatchRunner.ResultSource MEASURE_ONLY = new BatchRunner.ResultSource() {
        @Override
        public OC3DSDResult resultFor(ImagePlus image, OC3DSDParameters params) {
            return OC3DSDRunner.measureFilterAndMap(image, null, params);
        }
    };

    // ------------------------------------------------------------------
    // The corpus
    // ------------------------------------------------------------------

    /**
     * A small input tree that reaches every branch of the batch layer.
     *
     * <pre>
     * in/
     *   a_wt_01.tif          three objects, uncalibrated
     *   a_ko_02.tif          two objects, calibrated 0.5 x 0.5 x 2.0 micron
     *   notes.txt            not an image: the extension filter must skip it
     *   broken_wt_09.tif     not a TIFF: the failure row must record it
     *   nested/
     *     b_wt_03.tif        one object touching the stack edge
     *     b_ko_04.tif        objects of very different sizes, for the size filter
     *     odd,name_wt_05.tif a comma in the file name: the CSV must quote it
     *     ungrouped.tif      matches no group: must still be analysed
     * </pre>
     *
     * The comma-in-a-filename case is deliberate. It is the single most common
     * way a results CSV silently gains a column, and a batch tool that writes
     * file names into a CSV will meet it eventually.
     */
    private static void buildCorpus(File input) throws IOException {
        File nested = new File(input, "nested");
        mkdirs(input);
        mkdirs(nested);

        saveTiff(threeObjects(null), new File(input, "a_wt_01.tif"));
        saveTiff(twoObjects(calibration(0.5, 2.0, "micron")), new File(input, "a_ko_02.tif"));
        write(new File(input, "notes.txt"), "not an image\n");
        write(new File(input, "broken_wt_09.tif"), "this is not a TIFF\n");

        saveTiff(edgeTouching(), new File(nested, "b_wt_03.tif"));
        saveTiff(mixedSizes(), new File(nested, "b_ko_04.tif"));
        saveTiff(twoObjects(null), new File(nested, "odd,name_wt_05.tif"));
        saveTiff(threeObjects(null), new File(nested, "ungrouped.tif"));
    }

    private static ImagePlus threeObjects(Calibration cal) {
        ImagePlus imp = blank("three", 24, 24, 6);
        fill(imp, 1, 2, 6, 2, 6, 1, 3);
        fill(imp, 2, 10, 15, 10, 14, 1, 4);
        fill(imp, 3, 17, 21, 16, 20, 2, 4);
        if (cal != null) imp.setCalibration(cal);
        return imp;
    }

    private static ImagePlus twoObjects(Calibration cal) {
        ImagePlus imp = blank("two", 20, 20, 5);
        fill(imp, 1, 3, 8, 3, 8, 1, 3);
        fill(imp, 2, 12, 17, 12, 16, 0, 2);
        if (cal != null) imp.setCalibration(cal);
        return imp;
    }

    private static ImagePlus edgeTouching() {
        ImagePlus imp = blank("edge", 16, 16, 4);
        // Deliberately against x=0 and z=0, so exclude_edges has something to do.
        fill(imp, 1, 0, 4, 2, 7, 0, 2);
        fill(imp, 2, 8, 12, 8, 12, 1, 2);
        return imp;
    }

    private static ImagePlus mixedSizes() {
        ImagePlus imp = blank("mixed", 20, 20, 5);
        fill(imp, 1, 1, 1, 1, 1, 1, 1);        // 1 voxel
        fill(imp, 2, 4, 6, 4, 6, 1, 2);        // 18 voxels
        fill(imp, 3, 10, 17, 10, 17, 0, 4);    // 320 voxels
        return imp;
    }

    private static Calibration calibration(double xy, double z, String unit) {
        Calibration cal = new Calibration();
        cal.pixelWidth = xy;
        cal.pixelHeight = xy;
        cal.pixelDepth = z;
        cal.setUnit(unit);
        return cal;
    }

    private static ImagePlus blank(String title, int width, int height, int depth) {
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) stack.addSlice(new ShortProcessor(width, height));
        ImagePlus imp = new ImagePlus(title, stack);
        imp.setDimensions(1, depth, 1);
        return imp;
    }

    /** Inclusive bounds. */
    private static void fill(ImagePlus imp, int label,
                             int x0, int x1, int y0, int y1, int z0, int z1) {
        ImageStack stack = imp.getStack();
        for (int z = z0; z <= z1; z++) {
            ImageProcessor ip = stack.getProcessor(z + 1);
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) ip.setf(x, y, label);
            }
        }
    }

    private static void saveTiff(ImagePlus imp, File file) {
        mkdirs(file.getParentFile());
        IJ.saveAsTiff(imp, file.getAbsolutePath());
    }

    private static void write(File file, String content) throws IOException {
        mkdirs(file.getParentFile());
        Writer writer = new OutputStreamWriter(new FileOutputStream(file), UTF8);
        try {
            writer.write(content);
        } finally {
            writer.close();
        }
    }

    private static void mkdirs(File dir) {
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("could not create " + dir.getAbsolutePath());
        }
    }
}
