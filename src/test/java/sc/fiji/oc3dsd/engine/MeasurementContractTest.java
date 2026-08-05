package sc.fiji.oc3dsd.engine;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.process.FloatProcessor;
import org.junit.Test;
import sc.fiji.oc3d.core.measure.LabelFeatureAccumulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * What this plugin requires of the shared measurement code.
 *
 * <p>Measurement comes from the label image, never from the detector's spot
 * features (defect D3), and the feature definitions have to mean the same thing
 * they mean in 3D Objects Counter+ or the shared column names are a lie.
 *
 * <p>These assertions were written against this repository's own
 * {@code LabelMeasurements}. That class is gone — the implementation is now
 * {@code oc3d-core}'s {@link LabelFeatureAccumulator} — and the assertions are
 * kept, pointed at core, deliberately. They are this plugin's stake in a shared
 * dependency: if a later core release changes the sphericity definition, drops a
 * column this plugin's users filter on, or stops scaling volume with the
 * calibration, that is a breaking change <em>for this plugin</em> and it should
 * fail here rather than in someone's analysis. The equivalence harness proves
 * core matches what shipped; this proves core still means what this plugin
 * needs it to mean.
 */
public class MeasurementContractTest {

    private static final int SIZE = 24;

    /** A solid ball of the given radius, centred in the stack. */
    private static ImagePlus ball(double radius, Calibration cal) {
        ImageStack stack = new ImageStack(SIZE, SIZE);
        double c = SIZE / 2.0 - 0.5;
        for (int z = 0; z < SIZE; z++) {
            FloatProcessor ip = new FloatProcessor(SIZE, SIZE);
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    double dx = x - c;
                    double dy = y - c;
                    double dz = z - c;
                    if (dx * dx + dy * dy + dz * dz <= radius * radius) {
                        ip.setf(x, y, 1f);
                    }
                }
            }
            stack.addSlice(ip);
        }
        ImagePlus imp = new ImagePlus("ball", stack);
        imp.setDimensions(1, SIZE, 1);
        if (cal != null) imp.setCalibration(cal);
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

    @Test
    public void voxelCountMatchesTheDrawnObject() {
        ImagePlus labels = ball(6.0, null);

        LabelFeatureAccumulator.Result result = LabelFeatureAccumulator.scan(labels, null, null);

        long voxels = result.valuesForLabel(1).voxelCount();
        double expected = 4.0 / 3.0 * Math.PI * Math.pow(6.0, 3);
        assertTrue("voxelised ball should be within 15% of the analytic volume, was " + voxels,
                Math.abs(voxels - expected) / expected < 0.15);
    }

    /**
     * Sphericity is the mcib3d definition — cube root of
     * {@code 36*pi*V^2 / S^3} on the Lindblad-corrected surface, in voxel units.
     * A ball must come out near 1.
     */
    @Test
    public void sphericityOfABallIsNearOne() {
        ImagePlus labels = ball(8.0, null);

        LabelFeatureAccumulator.Result result = LabelFeatureAccumulator.scan(labels, null, null);
        double sphericity = result.valuesForLabel(1).sphericity();

        assertTrue("sphericity of a ball should be near 1, was " + sphericity,
                sphericity > 0.85 && sphericity <= 1.15);
    }

    @Test
    public void sphericityIsTheCubeRootOfCompactness() {
        LabelFeatureAccumulator.Result result =
                LabelFeatureAccumulator.scan(ball(7.0, null), null, null);
        LabelFeatureAccumulator.FeatureValues values = result.valuesForLabel(1);

        assertEquals(Math.cbrt(values.compactness()), values.sphericity(), 1e-9);
    }

    @Test
    public void calibratedVolumeScalesWithVoxelSize() {
        LabelFeatureAccumulator.Result plain = LabelFeatureAccumulator.scan(
                ball(6.0, calibration(1.0, 1.0, "micron")), null, null);
        LabelFeatureAccumulator.Result scaled = LabelFeatureAccumulator.scan(
                ball(6.0, calibration(0.5, 2.0, "micron")), null, null);

        double plainVolume = plain.valuesForLabel(1).calibratedVolume();
        double scaledVolume = scaled.valuesForLabel(1).calibratedVolume();

        // 0.5 * 0.5 * 2.0 = 0.5 of a unit cube per voxel.
        assertEquals(plainVolume * 0.5, scaledVolume, plainVolume * 1e-6);
    }

    @Test
    public void columnsCarryTheSharedNames() {
        ResultsTable table = LabelFeatureAccumulator.scan(
                ball(6.0, calibration(1.0, 1.0, "micron")), null, null).toStatisticsTable(null);

        assertTrue(table.getColumnIndex("Nb of obj. voxels") != ResultsTable.COLUMN_NOT_FOUND);
        assertTrue(table.getColumnIndex("Morph_Sphericity") != ResultsTable.COLUMN_NOT_FOUND);
        assertTrue(table.getColumnIndex("Morph_Compactness") != ResultsTable.COLUMN_NOT_FOUND);
        assertTrue(table.getColumnIndex("Morph_Elongation") != ResultsTable.COLUMN_NOT_FOUND);
        assertTrue(table.getColumnIndex("Morph_Feret3D_um") != ResultsTable.COLUMN_NOT_FOUND);
        assertTrue(table.getColumnIndex("Volume (micron^3)") != ResultsTable.COLUMN_NOT_FOUND);
    }

    /**
     * Sphericity and compactness were NaN in the class this was carried over
     * from, because 3D Objects Counter+ fills them from the native counter it
     * wraps. There is no native counter in this path, so they have to be
     * computed here — and a regression to NaN would be silent.
     */
    @Test
    public void shapeColumnsAreComputedNotLeftBlank() {
        ResultsTable table = LabelFeatureAccumulator.scan(ball(6.0, null), null, null)
                .toStatisticsTable(null);

        double sphericity = table.getValueAsDouble(
                table.getColumnIndex("Morph_Sphericity"), 0);
        double compactness = table.getValueAsDouble(
                table.getColumnIndex("Morph_Compactness"), 0);

        assertTrue("sphericity must not be NaN", !Double.isNaN(sphericity));
        assertTrue("compactness must not be NaN", !Double.isNaN(compactness));
    }

    /**
     * The bounding box this plugin's edge filter reads.
     *
     * <p>{@code OC3DSDRunner.touchesEdge} used to read the {@code minX}/{@code maxX}
     * fields directly; core exposes origin-plus-extent instead, and the filter
     * now reconstructs the maximum as {@code boundingX() + boundingWidth() - 1}.
     * A change in core to either accessor's meaning would silently move which
     * objects the {@code exclude_edges} option discards.
     */
    @Test
    public void boundingBoxIsOriginPlusExtent() {
        ImageStack stack = new ImageStack(10, 10);
        for (int z = 0; z < 5; z++) {
            FloatProcessor ip = new FloatProcessor(10, 10);
            if (z >= 1 && z <= 3) {
                for (int y = 2; y <= 6; y++) {
                    for (int x = 4; x <= 7; x++) ip.setf(x, y, 1f);
                }
            }
            stack.addSlice(ip);
        }
        ImagePlus labels = new ImagePlus("box", stack);
        labels.setDimensions(1, 5, 1);

        LabelFeatureAccumulator.FeatureValues values =
                LabelFeatureAccumulator.scan(labels, null, null).valuesForLabel(1);

        assertEquals(4, values.boundingX());
        assertEquals(2, values.boundingY());
        assertEquals(1, values.boundingZ());
        assertEquals(4, values.boundingWidth());
        assertEquals(5, values.boundingHeight());
        assertEquals(3, values.boundingDepth());
        // The reconstructed maxima the edge filter compares against.
        assertEquals(7, values.boundingX() + values.boundingWidth() - 1);
        assertEquals(6, values.boundingY() + values.boundingHeight() - 1);
        assertEquals(3, values.boundingZ() + values.boundingDepth() - 1);
    }
}
