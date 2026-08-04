package sc.fiji.oc3dsd.engine;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import org.junit.Test;

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

        assertEquals(1, result.objectCount());
        assertEquals(1f, get(imp, 1, 1, 1), 0f);
        assertEquals(2.5f, get(imp, 1, 3, 3), 0f);
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
}
