package sc.fiji.oc3dsd.equivalence;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.util.ArrayList;
import java.util.List;

/**
 * The synthetic label corpus, from harness §6 restricted to what applies to a
 * plugin that is <em>handed</em> a label image.
 * <p>
 * There is no threshold sweep here and no connectivity discriminator: this
 * plugin never thresholds and never runs connected components, so both would be
 * testing something that does not exist in this code path. What does apply is
 * geometry, the label-storage and processor-width boundaries, and — specific to
 * this repo — non-contiguous and out-of-order label sets, because StarDist plus
 * TrackMate hands this plugin exactly those and {@code LabelRenumberer} is what
 * fixes them.
 * <p>
 * Fixtures are <strong>generated, never committed as binaries</strong>, and are
 * rebuilt for every single run: {@code measureFilterAndMap} renumbers the label
 * image in place when filtering drops an object, so a shared instance would let
 * one configuration contaminate the next.
 */
final class Fixtures {

    private Fixtures() {
    }

    static final class Fixture {
        /** Up to this many objects, every map-building configuration runs. */
        static final int FULL_MAP_SWEEP_LIMIT = 64;

        /** Beyond this many objects, no map-building configuration runs. */
        static final int ANY_MAP_LIMIT = 512;

        final String name;
        final int approximateObjects;

        Fixture(String name, int approximateObjects) {
            this.name = name;
            this.approximateObjects = approximateObjects;
        }

        boolean allowsAllMapConfigs() {
            return approximateObjects <= FULL_MAP_SWEEP_LIMIT;
        }

        /**
         * Whether a map-building configuration runs for this fixture.
         * <p>
         * Map cost scales with object count — every map carries one text ROI per
         * object — while measurement cost does not, so this is the only axis the
         * sweep bounds. Between the two limits a single representative map
         * configuration still runs, which is what keeps the map builder's
         * processor-width choice covered at that object count rather than
         * assumed.
         */
        boolean allowsMapConfig(String configName) {
            if (allowsAllMapConfigs()) return true;
            if (approximateObjects > ANY_MAP_LIMIT) return false;
            return Harness.REPRESENTATIVE_MAP_CONFIG.equals(configName);
        }
    }

    static List<Fixture> all() {
        List<Fixture> fixtures = new ArrayList<Fixture>();
        // Geometry and edge cases.
        fixtures.add(new Fixture("empty", 0));
        fixtures.add(new Fixture("all_foreground", 1));
        fixtures.add(new Fixture("single_voxel", 1));
        fixtures.add(new Fixture("eight_corners", 8));
        fixtures.add(new Fixture("border_faces", 6));
        fixtures.add(new Fixture("solid_sphere", 1));
        fixtures.add(new Fixture("hollow_shell", 1));
        fixtures.add(new Fixture("u_shape", 1));
        fixtures.add(new Fixture("full_depth_span", 1));
        fixtures.add(new Fixture("clipped_top_bottom", 1));
        fixtures.add(new Fixture("touch_last_slice", 2));
        fixtures.add(new Fixture("mixed_sizes", 5));
        // Non-contiguous labelling — this repo's own failure mode.
        fixtures.add(new Fixture("gapped_labels", 3));
        fixtures.add(new Fixture("out_of_order_labels", 3));
        // ByteProcessor -> ShortProcessor boundary in the map builder.
        fixtures.add(new Fixture("count_254", 254));
        fixtures.add(new Fixture("count_255", 255));
        fixtures.add(new Fixture("count_256", 256));
        // ShortProcessor -> FloatProcessor boundary, cheaply.
        fixtures.add(new Fixture("high_label_65535", 2));
        fixtures.add(new Fixture("high_label_65536", 2));
        // Dense label storage growth in LabelMeasurements, at scale.
        fixtures.add(new Fixture("count_65534", 65534));
        fixtures.add(new Fixture("count_65535", 65535));
        fixtures.add(new Fixture("count_65536", 65536));
        return fixtures;
    }

    /** Builds a fresh label image. Deterministic: same name, same voxels, always. */
    static ImagePlus labels(String name) {
        if ("empty".equals(name)) return blank(16, 16, 16, name);
        if ("all_foreground".equals(name)) return allForeground(16, name);
        if ("single_voxel".equals(name)) return singleVoxel(name);
        if ("eight_corners".equals(name)) return eightCorners(name);
        if ("border_faces".equals(name)) return borderFaces(name);
        if ("solid_sphere".equals(name)) return ball(24, 8.0, 0.0, name);
        if ("hollow_shell".equals(name)) return ball(24, 8.0, 6.5, name);
        if ("u_shape".equals(name)) return uShape(name);
        if ("full_depth_span".equals(name)) return fullDepthSpan(name);
        if ("clipped_top_bottom".equals(name)) return clippedTopAndBottom(name);
        if ("touch_last_slice".equals(name)) return touchingOnLastSlice(name);
        if ("mixed_sizes".equals(name)) return mixedSizes(name);
        if ("gapped_labels".equals(name)) return threeBars(name, new int[] {1, 5, 9});
        if ("out_of_order_labels".equals(name)) return threeBars(name, new int[] {9, 4, 1});
        if ("count_254".equals(name)) return grid(name, 254);
        if ("count_255".equals(name)) return grid(name, 255);
        if ("count_256".equals(name)) return grid(name, 256);
        if ("high_label_65535".equals(name)) return highLabels(name, 65534, 65535);
        if ("high_label_65536".equals(name)) return highLabels(name, 65535, 65536);
        if ("count_65534".equals(name)) return grid(name, 65534);
        if ("count_65535".equals(name)) return grid(name, 65535);
        if ("count_65536".equals(name)) return grid(name, 65536);
        throw new IllegalArgumentException("Unknown fixture: " + name);
    }

    /**
     * A deterministic intensity image matching a label image's dimensions, for
     * the redirect configurations. Values are spread across the 16-bit range and
     * vary in all three axes so that {@code Mean}, {@code StdDev}, {@code IntDen}
     * and the centres of mass all have something to bite on — a flat image would
     * make every one of them trivially reproducible and prove nothing.
     */
    static ImagePlus intensityFor(ImagePlus labelImage) {
        int width = labelImage.getWidth();
        int height = labelImage.getHeight();
        int depth = labelImage.getStackSize();
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            ShortProcessor ip = new ShortProcessor(width, height);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int value = ((x * 7) + (y * 13) + (z * 29)) % 251 + 1;
                    ip.set(x, y, value);
                }
            }
            stack.addSlice(ip);
        }
        ImagePlus imp = new ImagePlus("intensity", stack);
        imp.setDimensions(1, depth, 1);
        return imp;
    }

    // ------------------------------------------------------------------
    // Builders
    // ------------------------------------------------------------------

    private static ImagePlus blank(int width, int height, int depth, String title) {
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            stack.addSlice(new ShortProcessor(width, height));
        }
        return finish(stack, depth, title);
    }

    private static ImagePlus allForeground(int size, String title) {
        ImagePlus imp = blank(size, size, size, title);
        fill(imp, 1);
        return imp;
    }

    private static ImagePlus singleVoxel(String title) {
        ImagePlus imp = blank(16, 16, 16, title);
        set(planes(imp), 8, 8, 8, 1);
        return imp;
    }

    private static ImagePlus eightCorners(String title) {
        ImagePlus imp = blank(16, 16, 16, title);
        ImageProcessor[] planes = planes(imp);
        int label = 1;
        for (int z = 0; z < 2; z++) {
            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 2; x++) {
                    set(planes, x * 15, y * 15, z * 15, label++);
                }
            }
        }
        return imp;
    }

    private static ImagePlus borderFaces(String title) {
        ImagePlus imp = blank(16, 16, 16, title);
        ImageProcessor[] planes = planes(imp);
        set(planes, 0, 8, 8, 1);
        set(planes, 15, 8, 8, 2);
        set(planes, 8, 0, 8, 3);
        set(planes, 8, 15, 8, 4);
        set(planes, 8, 8, 0, 5);
        set(planes, 8, 8, 15, 6);
        return imp;
    }

    /** A ball, or a shell when {@code innerRadius} is positive. */
    private static ImagePlus ball(int size, double radius, double innerRadius, String title) {
        ImagePlus imp = blank(size, size, size, title);
        ImageProcessor[] planes = planes(imp);
        double c = size / 2.0 - 0.5;
        for (int z = 0; z < size; z++) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    double dx = x - c;
                    double dy = y - c;
                    double dz = z - c;
                    double d2 = dx * dx + dy * dy + dz * dz;
                    if (d2 <= radius * radius && d2 >= innerRadius * innerRadius) {
                        set(planes, x, y, z, 1);
                    }
                }
            }
        }
        return imp;
    }

    /** Two arms, separate on every slice but the last, where they join. */
    private static ImagePlus uShape(String title) {
        ImagePlus imp = blank(16, 16, 16, title);
        ImageProcessor[] planes = planes(imp);
        for (int z = 0; z < 16; z++) {
            for (int y = 4; y < 12; y++) {
                set(planes, 4, y, z, 1);
                set(planes, 11, y, z, 1);
            }
        }
        for (int x = 4; x <= 11; x++) {
            for (int y = 4; y < 12; y++) {
                set(planes, x, y, 15, 1);
            }
        }
        return imp;
    }

    private static ImagePlus fullDepthSpan(String title) {
        ImagePlus imp = blank(16, 16, 16, title);
        ImageProcessor[] planes = planes(imp);
        for (int z = 0; z < 16; z++) {
            for (int y = 7; y <= 8; y++) {
                for (int x = 7; x <= 8; x++) {
                    set(planes, x, y, z, 1);
                }
            }
        }
        return imp;
    }

    /** A ball whose top and bottom are cut off by the ends of the stack. */
    private static ImagePlus clippedTopAndBottom(String title) {
        int size = 16;
        ImagePlus imp = blank(size, size, size, title);
        ImageProcessor[] planes = planes(imp);
        double c = size / 2.0 - 0.5;
        double radius = 10.0;
        for (int z = 0; z < size; z++) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    double dx = x - c;
                    double dy = y - c;
                    double dz = (z - c) * 1.6;
                    if (dx * dx + dy * dy + dz * dz <= radius * radius) {
                        set(planes, x, y, z, 1);
                    }
                }
            }
        }
        return imp;
    }

    private static ImagePlus touchingOnLastSlice(String title) {
        ImagePlus imp = blank(16, 16, 16, title);
        ImageProcessor[] planes = planes(imp);
        for (int z = 0; z < 16; z++) {
            set(planes, 7, 8, z, 1);
            set(planes, 8, 8, z, 2);
        }
        return imp;
    }

    /**
     * Five cubes of deliberately different sizes — 1, 8, 27 and 64 voxels in the
     * interior, plus a 27-voxel cube against the {@code x = 0} face.
     * <p>
     * Every other fixture holds objects that are all the same size, so a size
     * bound or a morphology filter either keeps all of them or drops all of
     * them. Neither case reaches the interesting path: dropping <em>some</em>
     * objects is what triggers the renumber-and-rescan branch, after which the
     * survivors must come out numbered 1..N and the maps, the overlay and the
     * statistics table must all still join on the new numbers. The edge cube
     * does the same job for {@code excludeOnEdges}.
     */
    private static ImagePlus mixedSizes(String title) {
        ImagePlus imp = blank(20, 20, 20, title);
        ImageProcessor[] planes = planes(imp);
        cube(planes, 2, 2, 2, 1, 1);
        cube(planes, 6, 2, 2, 2, 2);
        cube(planes, 10, 2, 2, 3, 3);
        cube(planes, 14, 2, 2, 4, 4);
        cube(planes, 0, 12, 12, 3, 5);
        return imp;
    }

    /** A {@code side}-cubed block with its near corner at (x, y, z). */
    private static void cube(ImageProcessor[] planes, int x, int y, int z, int side, int label) {
        for (int dz = 0; dz < side; dz++) {
            for (int dy = 0; dy < side; dy++) {
                for (int dx = 0; dx < side; dx++) {
                    set(planes, x + dx, y + dy, z + dz, label);
                }
            }
        }
    }

    /** Three separated bars carrying the given labels, in the given order. */
    private static ImagePlus threeBars(String title, int[] labels) {
        ImagePlus imp = blank(16, 16, 16, title);
        ImageProcessor[] planes = planes(imp);
        for (int i = 0; i < labels.length; i++) {
            int x = 2 + i * 5;
            for (int z = i; z < i + 6; z++) {
                for (int y = 4; y < 10; y++) {
                    set(planes, x, y, z, labels[i]);
                }
            }
        }
        return imp;
    }

    /** {@code count} single-voxel objects on a lattice, labelled 1..count. */
    private static ImagePlus grid(String title, int count) {
        int side = (int) Math.ceil(Math.sqrt(count));
        int width = side * 2 + 1;
        ImagePlus imp = count > 65535
                ? blankFloat(width, width, 1, title)
                : blank(width, width, 1, title);
        ImageProcessor[] planes = planes(imp);
        int label = 1;
        for (int j = 0; j < side && label <= count; j++) {
            for (int i = 0; i < side && label <= count; i++) {
                set(planes, i * 2 + 1, j * 2 + 1, 0, label);
                label++;
            }
        }
        return imp;
    }

    /**
     * Two objects carrying labels straddling the {@code ShortProcessor} to
     * {@code FloatProcessor} boundary in the map builder, without paying for
     * sixty-five thousand objects to get there.
     */
    private static ImagePlus highLabels(String title, int first, int second) {
        ImagePlus imp = second > 65535
                ? blankFloat(16, 16, 4, title)
                : blank(16, 16, 4, title);
        ImageProcessor[] planes = planes(imp);
        for (int z = 0; z < 4; z++) {
            for (int y = 4; y < 8; y++) {
                for (int x = 3; x < 7; x++) {
                    set(planes, x, y, z, first);
                }
                for (int x = 9; x < 13; x++) {
                    set(planes, x, y, z, second);
                }
            }
        }
        return imp;
    }

    private static ImagePlus blankFloat(int width, int height, int depth, String title) {
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            stack.addSlice(new FloatProcessor(width, height));
        }
        return finish(stack, depth, title);
    }

    private static ImagePlus finish(ImageStack stack, int depth, String title) {
        ImagePlus imp = new ImagePlus(title, stack);
        imp.setDimensions(1, depth, 1);
        return imp;
    }

    /**
     * The stack's planes, fetched once.
     * <p>
     * {@link ImageStack#getProcessor(int)} builds a fresh {@code ImageProcessor}
     * on every call and a {@code ShortProcessor}'s constructor scans the whole
     * plane for its display range. Calling it per voxel therefore costs
     * O(objects × pixels): building the 65,534-object fixture that way took over
     * five minutes on its own. Fetching the planes once makes it instant.
     */
    private static ImageProcessor[] planes(ImagePlus imp) {
        ImageStack stack = imp.getStack();
        ImageProcessor[] planes = new ImageProcessor[stack.getSize()];
        for (int z = 0; z < planes.length; z++) {
            planes[z] = stack.getProcessor(z + 1);
        }
        return planes;
    }

    private static void set(ImageProcessor[] planes, int x, int y, int z, int label) {
        planes[z].setf(x, y, label);
    }

    private static void fill(ImagePlus imp, int label) {
        ImageProcessor[] planes = planes(imp);
        for (int z = 0; z < planes.length; z++) {
            ImageProcessor ip = planes[z];
            for (int i = 0; i < ip.getPixelCount(); i++) {
                ip.setf(i, label);
            }
        }
    }
}
