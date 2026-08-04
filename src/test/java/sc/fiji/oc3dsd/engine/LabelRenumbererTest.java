package sc.fiji.oc3dsd.engine;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import org.junit.Test;
import sc.fiji.oc3d.core.label.LabelRenumberer;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins defect D4: TrackMate paints {@code trackId + 1}, which is sparse, holed
 * after filtering, and not stable between runs. Objects must come out numbered
 * {@code 1..N} in a documented order, with the detector's own label recoverable.
 */
public class LabelRenumbererTest {

    private static final int W = 8;
    private static final int H = 8;
    private static final int Z = 3;

    /**
     * Three objects with deliberately out-of-order detector labels:
     * <ul>
     *   <li>label 7 — slices 1-2, centroid y = 1</li>
     *   <li>label 12 — slice 1 only, centroid y = 5</li>
     *   <li>label 3 — slices 2-3, centroid y = 6</li>
     * </ul>
     * Ordering is first slice, then centroid Y, then centroid X, so the expected
     * result is 7 to 1, 12 to 2, 3 to 3 — not the numeric order of the input.
     */
    private static ImagePlus threeObjects() {
        ImageStack stack = new ImageStack(W, H);
        for (int z = 0; z < Z; z++) {
            stack.addSlice(new FloatProcessor(W, H));
        }
        set(stack, 1, 1, 1, 7);
        set(stack, 1, 2, 1, 7);
        set(stack, 2, 1, 1, 7);
        set(stack, 2, 2, 1, 7);

        set(stack, 1, 4, 5, 12);
        set(stack, 1, 5, 5, 12);

        set(stack, 2, 6, 6, 3);
        set(stack, 3, 6, 6, 3);

        ImagePlus imp = new ImagePlus("labels", stack);
        imp.setDimensions(1, Z, 1);
        return imp;
    }

    private static void set(ImageStack stack, int slice, int x, int y, float value) {
        stack.getProcessor(slice).setf(x, y, value);
    }

    private static float get(ImagePlus imp, int slice, int x, int y) {
        return imp.getStack().getProcessor(slice).getf(x, y);
    }

    @Test
    public void numbersObjectsContiguouslyInFirstSliceThenCentroidOrder() {
        ImagePlus labels = threeObjects();

        LabelRenumberer.Result result = LabelRenumberer.renumber(labels);

        assertEquals(3, result.objectCount());
        assertEquals(Integer.valueOf(1), result.oldToNew().get(Integer.valueOf(7)));
        assertEquals(Integer.valueOf(2), result.oldToNew().get(Integer.valueOf(12)));
        assertEquals(Integer.valueOf(3), result.oldToNew().get(Integer.valueOf(3)));

        assertEquals(1f, get(labels, 1, 1, 1), 0f);
        assertEquals(2f, get(labels, 1, 4, 5), 0f);
        assertEquals(3f, get(labels, 2, 6, 6), 0f);
    }

    @Test
    public void detectorLabelRemainsRecoverable() {
        ImagePlus labels = threeObjects();

        LabelRenumberer.Result result = LabelRenumberer.renumber(labels);

        assertEquals(Integer.valueOf(7), result.newToOld().get(Integer.valueOf(1)));
        assertEquals(Integer.valueOf(12), result.newToOld().get(Integer.valueOf(2)));
        assertEquals(Integer.valueOf(3), result.newToOld().get(Integer.valueOf(3)));
    }

    /**
     * Filtering out the middle object must leave no hole. This is the specific
     * failure D4 describes: without renumbering the survivors would be 1 and 3.
     */
    @Test
    public void filteringLeavesNoHoles() {
        ImagePlus labels = threeObjects();
        Set<Integer> keep = new HashSet<Integer>();
        keep.add(Integer.valueOf(7));
        keep.add(Integer.valueOf(3));

        LabelRenumberer.Result result = LabelRenumberer.renumber(labels, keep);

        assertEquals(2, result.objectCount());
        assertEquals(Integer.valueOf(1), result.oldToNew().get(Integer.valueOf(7)));
        assertEquals(Integer.valueOf(2), result.oldToNew().get(Integer.valueOf(3)));
        assertFalse(result.oldToNew().containsKey(Integer.valueOf(12)));

        assertEquals(1f, get(labels, 1, 1, 1), 0f);
        assertEquals(2f, get(labels, 2, 6, 6), 0f);
        assertEquals("filtered object must be erased, not merely unnumbered",
                0f, get(labels, 1, 4, 5), 0f);
    }

    @Test
    public void isStableAcrossRepeatedRuns() {
        LabelRenumberer.Result first = LabelRenumberer.renumber(threeObjects());
        LabelRenumberer.Result second = LabelRenumberer.renumber(threeObjects());
        assertEquals(first.oldToNew(), second.oldToNew());
    }

    /**
     * A non-integral pixel value means the image is not a label image. Rounding
     * it would silently hand those pixels to a neighbouring object's identity,
     * so they are treated as background instead.
     *
     * <p><b>The pixel is now cleared, where this repository's own copy left the
     * {@code 2.5} in place.</b> That is the single behavioural difference between
     * the two implementations, and core's reading is the one that matches the
     * paragraph above: a pixel declared background should be background in the
     * renumbered image too. Leaving it produced a saved label image containing a
     * value that was neither background nor any object — which the next tool to
     * read that file would have to guess about.
     *
     * <p>Not reachable from this plugin: see
     * {@link #detectionCannotProduceAnInvalidNonZeroPixel()}.
     */
    @Test
    public void nonIntegralPixelsAreBackground() {
        ImageStack stack = new ImageStack(W, H);
        for (int z = 0; z < Z; z++) {
            stack.addSlice(new FloatProcessor(W, H));
        }
        set(stack, 1, 1, 1, 4f);
        set(stack, 1, 3, 3, 2.5f);
        ImagePlus imp = new ImagePlus("labels", stack);
        imp.setDimensions(1, Z, 1);

        LabelRenumberer.Result result = LabelRenumberer.renumber(imp);

        assertEquals("a non-integral pixel is not an object", 1, result.objectCount());
        assertEquals(1f, get(imp, 1, 1, 1), 0f);
        assertEquals("a non-integral pixel is cleared, not carried through",
                0f, get(imp, 1, 3, 3), 0f);
    }

    @Test
    public void emptyImageIsNotAnError() {
        ImageStack stack = new ImageStack(W, H);
        for (int z = 0; z < Z; z++) {
            stack.addSlice(new FloatProcessor(W, H));
        }
        ImagePlus imp = new ImagePlus("labels", stack);

        LabelRenumberer.Result result = LabelRenumberer.renumber(imp);

        assertEquals(0, result.objectCount());
        assertTrue(result.oldToNew().isEmpty());
    }

    @Test
    public void preservesSixteenBitLabelImages() {
        ImageStack stack = new ImageStack(W, H);
        for (int z = 0; z < Z; z++) {
            stack.addSlice(new ij.process.ShortProcessor(W, H));
        }
        stack.getProcessor(1).set(1, 1, 9);
        stack.getProcessor(2).set(1, 1, 9);
        stack.getProcessor(1).set(5, 5, 4);
        ImagePlus imp = new ImagePlus("labels", stack);
        imp.setDimensions(1, Z, 1);

        LabelRenumberer.Result result = LabelRenumberer.renumber(imp);

        assertEquals(2, result.objectCount());
        ImageProcessor ip = imp.getStack().getProcessor(1);
        assertEquals(1, ip.get(1, 1));
        assertEquals(2, ip.get(5, 5));
    }

    /**
     * The one behavioural difference between this repository's former copy and
     * {@code oc3d-core}'s, pinned so it is a decision on the record rather than
     * something discovered later.
     *
     * <p>A pixel that is non-zero but is not a valid label — negative, NaN,
     * infinite, or fractional — was previously <em>left as it was</em>. Core
     * zeroes it. Core is right: after renumbering, a label image should contain
     * nothing but {@code 0} and {@code 1..N}, and leaving a NaN in one produces
     * an image whose own measurement pass will disagree with its label map.
     *
     * <p>It is invisible in this plugin, because this plugin cannot produce such
     * a pixel — see {@link #detectionCannotProduceAnInvalidNonZeroPixel()}. It
     * will be visible in the future "- Labels" variant, which takes user-supplied
     * label images and can be handed a float stack containing anything at all.
     */
    @Test
    public void invalidNonZeroPixelsAreClearedRatherThanLeftInPlace() {
        ImageStack stack = new ImageStack(W, H);
        for (int z = 0; z < Z; z++) stack.addSlice(new FloatProcessor(W, H));
        set(stack, 1, 1, 1, 5f);           // a real object
        set(stack, 1, 3, 3, -4f);          // negative
        set(stack, 1, 4, 3, Float.NaN);    // not a number
        set(stack, 1, 5, 3, 2.5f);         // fractional: between two labels
        set(stack, 1, 6, 3, Float.POSITIVE_INFINITY);
        ImagePlus imp = new ImagePlus("mixed", stack);
        imp.setDimensions(1, Z, 1);

        LabelRenumberer.Result result = LabelRenumberer.renumber(imp);

        assertEquals("only the one valid label is an object", 1, result.objectCount());
        assertEquals("the valid object is renumbered to 1", 1f, get(imp, 1, 1, 1), 0f);
        assertEquals("a negative pixel is cleared", 0f, get(imp, 1, 3, 3), 0f);
        assertEquals("a NaN pixel is cleared", 0f, get(imp, 1, 4, 3), 0f);
        assertEquals("a fractional pixel is cleared", 0f, get(imp, 1, 5, 3), 0f);
        assertEquals("an infinite pixel is cleared", 0f, get(imp, 1, 6, 3), 0f);
    }

    /**
     * Why the difference above cannot reach a user of <em>this</em> plugin.
     *
     * <p>The label image reaching {@code LabelRenumberer} in production is built
     * by {@code StarDistTrackMateRunner} as a {@link ShortProcessor} stack.
     * Unsigned 16-bit integers cannot be negative, cannot be NaN or infinite, and
     * cannot be fractional, so the cleared branch is unreachable by construction
     * rather than by convention.
     */
    @Test
    public void detectionCannotProduceAnInvalidNonZeroPixel() {
        ShortProcessor sp = new ShortProcessor(W, H);
        sp.setf(2, 2, -7f);
        sp.setf(3, 3, Float.NaN);
        sp.setf(4, 4, 2.5f);

        for (int i = 0; i < sp.getPixelCount(); i++) {
            float value = sp.getf(i);
            assertTrue("a ShortProcessor cannot hold a negative label, got " + value,
                    value >= 0f);
            assertTrue("a ShortProcessor cannot hold a non-finite label, got " + value,
                    !Float.isNaN(value) && !Float.isInfinite(value));
            assertEquals("a ShortProcessor cannot hold a fractional label, got " + value,
                    value, Math.rint(value), 0f);
        }
    }
}
