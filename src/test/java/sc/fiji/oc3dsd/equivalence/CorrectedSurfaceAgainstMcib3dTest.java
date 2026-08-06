package sc.fiji.oc3dsd.equivalence;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import org.junit.Test;
import sc.fiji.oc3d.core.measure.LabelFeatureAccumulator;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Proves that this plugin's reimplemented corrected surface agrees with the
 * recorded mcib3d 4.1.7b oracle values.
 *
 * <h2>Why this test exists</h2>
 *
 * 3D Objects Counter+ fills {@code Morph_Sphericity} and {@code Morph_Compactness}
 * from mcib3d's {@link MeasureCompactness} (its {@code SPHER_CORRECTED} and
 * {@code COMP_CORRECTED} values). This plugin has no mcib3d on its runtime
 * classpath and computes those columns itself, from a Lindblad (2005)
 * weighted-configuration corrected surface written in {@code ij} alone.
 *
 * <p>The values below were captured by running mcib3d 4.1.7b's live
 * {@code MeasureCompactness} implementation over these exact voxel fixtures.
 * That Fiji-distributed version has no public Maven artifact: its former test
 * dependency was a local {@code install-file} result and made clean clones fail.
 * Keeping the immutable oracle values here preserves the numerical regression
 * gate without publishing or depending on that binary.
 *
 * <h2>What is compared, and what is not</h2>
 *
 * The comparison is on {@code SPHER_CORRECTED} and {@code COMP_CORRECTED}, the
 * corrected variants — the ones built on the weighted-configuration surface. Not
 * on {@code SPHER_UNIT} or {@code SPHER_PIX}, which are different estimators
 * mcib3d also offers and which neither plugin reports.
 *
 * <p>The tolerance is relative and tight ({@value #TOLERANCE}). It is not zero
 * because the two implementations reach the same formula by different
 * floating-point routes — mcib3d accumulates over its own voxel list, this one
 * over a streaming pass — and the last bits of a sum of hundreds of terms are
 * not required to match for the definitions to be the same. A real disagreement
 * in definition moves these numbers by percent, not by 1e-12.
 *
 */
public class CorrectedSurfaceAgainstMcib3dTest {

    /**
     * Relative agreement required. Wide enough to absorb a different summation
     * order, far too tight to hide a different surface definition — the
     * uncorrected estimators differ from the corrected ones by tens of percent.
     */
    private static final double TOLERANCE = 1.0e-9;

    @Test
    public void sphericityAndCompactnessMatchRecordedMcib3dOracle() {
        List<String> failures = new ArrayList<String>();
        List<String> observed = new ArrayList<String>();

        for (Shape shape : shapes()) {
            ImagePlus labels = shape.image;

            LabelFeatureAccumulator.FeatureValues ours =
                    LabelFeatureAccumulator.scan(labels, null, null).valuesForLabel(1);
            assertTrue("fixture '" + shape.name + "' produced no object", ours != null);

            check(failures, shape.name, "sphericity",
                    ours.sphericity(), shape.mcib3dSphericity);
            check(failures, shape.name, "compactness",
                    ours.compactness(), shape.mcib3dCompactness);

            observed.add(shape.name + " sphericity=" + ours.sphericity()
                    + "/" + shape.mcib3dSphericity + " compactness=" + ours.compactness()
                    + "/" + shape.mcib3dCompactness);
        }

        assertTrue("this plugin and the recorded mcib3d oracle disagree on the corrected "
                        + "shape measures, so "
                        + "Morph_Sphericity and Morph_Compactness do NOT mean the same thing here as "
                        + "in 3D Objects Counter+:\n  " + join(failures),
                failures.isEmpty());

        System.out.println("corrected shape measures vs recorded mcib3d " + MCIB3D_VERSION
                + " (this plugin/mcib3d)\n  " + join(observed));
    }

    /** Recorded in the output so a future divergence can be dated to a version. */
    private static final String MCIB3D_VERSION = "4.1.7b";

    /**
     * The degenerate case both implementations have to agree about: a lone voxel
     * has all six faces exposed, which the Lindblad table weights at zero, so
     * there is no corrected surface to divide by.
     *
     * <p>Checked separately because "both produce a non-finite value" is the
     * agreement here, and a relative comparison of two NaNs says nothing.
     */
    @Test
    public void anIsolatedVoxelMatchesRecordedMcib3dNonFiniteBehavior() {
        ImagePlus labels = blank("single_voxel", 9, 9, 5);
        labels.getStack().getProcessor(3).setf(4, 4, 1f);

        LabelFeatureAccumulator.FeatureValues ours =
                LabelFeatureAccumulator.scan(labels, null, null).valuesForLabel(1);
        assertFalse("a lone voxel must not report a finite sphericity",
                isUsable(ours.sphericity()));

        // mcib3d 4.1.7b also returned no finite corrected sphericity for this
        // exact fixture when the oracle values above were captured.
    }

    private static boolean isUsable(double value) {
        return Double.isFinite(value) && value != 0.0;
    }

    private static void check(List<String> failures,
                              String shape,
                              String measure,
                              double ours,
                              double theirs) {
        boolean ourFinite = Double.isFinite(ours) && ours != 0.0;
        boolean theirFinite = Double.isFinite(theirs) && theirs != 0.0;
        if (!ourFinite || !theirFinite) {
            if (ourFinite != theirFinite) {
                failures.add(shape + "/" + measure + ": this plugin=" + ours + " mcib3d=" + theirs
                        + " (one produced a usable value and the other did not)");
            }
            return;
        }
        double scale = Math.max(Math.abs(ours), Math.abs(theirs));
        double relative = Math.abs(ours - theirs) / scale;
        if (relative > TOLERANCE) {
            failures.add(shape + "/" + measure + ": this plugin=" + ours + " mcib3d=" + theirs
                    + " relative=" + relative);
        }
    }

    private static String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append("\n  ");
            sb.append(values.get(i));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Shapes
    // ------------------------------------------------------------------

    private static final class Shape {
        final String name;
        final ImagePlus image;
        final double mcib3dSphericity;
        final double mcib3dCompactness;

        Shape(String name, ImagePlus image,
              double mcib3dSphericity, double mcib3dCompactness) {
            this.name = name;
            this.image = image;
            this.mcib3dSphericity = mcib3dSphericity;
            this.mcib3dCompactness = mcib3dCompactness;
        }
    }

    /**
     * Shapes chosen to hit every branch of the Lindblad weight table — the
     * six-way switch on exposed-face count, and the opposite-pair split of the
     * three-face case. A test that only measured balls would exercise two of
     * them.
     */
    private static List<Shape> shapes() {
        List<Shape> shapes = new ArrayList<Shape>();
        shapes.add(new Shape("ball_r6", ball(20, 6.0),
                1.066202239864051, 1.2120450762584103));
        shapes.add(new Shape("ball_r3", ball(14, 3.0),
                1.1586687723492044, 1.5555282650014732));
        // Flat faces and hard edges: the 1-, 2- and opposite-pair 3-face cases.
        shapes.add(new Shape("cube_6", box(16, 3, 8, 3, 8, 3, 8),
                1.0687869330161794, 1.2208812006617438));
        // A slab one voxel thick: every voxel has both z faces exposed.
        shapes.add(new Shape("slab_1_thick", box(16, 3, 11, 3, 11, 5, 5),
                0.6839358773364574, 0.3199235119183544));
        // A line one voxel square: four faces exposed along its length.
        shapes.add(new Shape("rod_1x1", box(16, 4, 11, 6, 6, 6, 6),
                0.8534075050675427, 0.621540415080252));
        // A cavity, so interior faces are exposed and must be weighted too.
        shapes.add(new Shape("hollow_shell", hollowShell(),
                0.7294140425180604, 0.38808098150055065));
        // An L, which introduces concave edges the convex shapes never produce.
        shapes.add(new Shape("l_shape", lShape(),
                0.9040368048340401, 0.7388535001714785));
        return shapes;
    }

    private static ImagePlus ball(int size, double radius) {
        ImagePlus imp = blank("ball", size, size, size);
        double c = size / 2.0 - 0.5;
        ImageStack stack = imp.getStack();
        for (int z = 0; z < size; z++) {
            ImageProcessor ip = stack.getProcessor(z + 1);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    double dx = x - c;
                    double dy = y - c;
                    double dz = z - c;
                    if (dx * dx + dy * dy + dz * dz <= radius * radius) ip.setf(x, y, 1f);
                }
            }
        }
        return imp;
    }

    private static ImagePlus box(int size, int x0, int x1, int y0, int y1, int z0, int z1) {
        ImagePlus imp = blank("box", size, size, size);
        fill(imp, 1, x0, x1, y0, y1, z0, z1);
        return imp;
    }

    private static ImagePlus hollowShell() {
        ImagePlus imp = blank("hollow_shell", 14, 14, 14);
        fill(imp, 1, 3, 10, 3, 10, 3, 10);
        fill(imp, 0, 5, 8, 5, 8, 5, 8);
        return imp;
    }

    private static ImagePlus lShape() {
        ImagePlus imp = blank("l_shape", 16, 16, 16);
        fill(imp, 1, 3, 11, 3, 5, 4, 7);
        fill(imp, 1, 3, 5, 6, 11, 4, 7);
        return imp;
    }

    private static ImagePlus blank(String title, int width, int height, int depth) {
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            stack.addSlice(new ShortProcessor(width, height));
        }
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
}
