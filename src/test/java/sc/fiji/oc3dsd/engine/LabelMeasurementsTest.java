package sc.fiji.oc3dsd.engine;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.process.FloatProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Measurement comes from the label image, never from the detector's spot
 * features (defect D3), and the feature definitions have to mean the same thing
 * they mean in 3D Objects Counter+ or the shared column names are a lie.
 */
public class LabelMeasurementsTest {

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

        LabelMeasurements.Result result = LabelMeasurements.scan(labels, null, null);

        long voxels = result.valuesForLabel(1).voxelCount;
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

        LabelMeasurements.Result result = LabelMeasurements.scan(labels, null, null);
        double sphericity = result.valuesForLabel(1).sphericity();

        assertTrue("sphericity of a ball should be near 1, was " + sphericity,
                sphericity > 0.85 && sphericity <= 1.15);
    }

    @Test
    public void sphericityIsTheCubeRootOfCompactness() {
        LabelMeasurements.Result result = LabelMeasurements.scan(ball(7.0, null), null, null);
        LabelMeasurements.FeatureValues values = result.valuesForLabel(1);

        assertEquals(Math.cbrt(values.compactness()), values.sphericity(), 1e-9);
    }

    @Test
    public void calibratedVolumeScalesWithVoxelSize() {
        LabelMeasurements.Result plain = LabelMeasurements.scan(
                ball(6.0, calibration(1.0, 1.0, "micron")), null, null);
        LabelMeasurements.Result scaled = LabelMeasurements.scan(
                ball(6.0, calibration(0.5, 2.0, "micron")), null, null);

        double plainVolume = plain.valuesForLabel(1).calibratedVolume;
        double scaledVolume = scaled.valuesForLabel(1).calibratedVolume;

        // 0.5 * 0.5 * 2.0 = 0.5 of a unit cube per voxel.
        assertEquals(plainVolume * 0.5, scaledVolume, plainVolume * 1e-6);
    }

    @Test
    public void columnsCarryTheSharedNames() {
        ResultsTable table = LabelMeasurements.scan(
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
        ResultsTable table = LabelMeasurements.scan(ball(6.0, null), null, null)
                .toStatisticsTable(null);

        double sphericity = table.getValueAsDouble(
                table.getColumnIndex("Morph_Sphericity"), 0);
        double compactness = table.getValueAsDouble(
                table.getColumnIndex("Morph_Compactness"), 0);

        assertTrue("sphericity must not be NaN", !Double.isNaN(sphericity));
        assertTrue("compactness must not be NaN", !Double.isNaN(compactness));
    }
}
