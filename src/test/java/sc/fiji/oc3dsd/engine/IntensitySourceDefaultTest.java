package sc.fiji.oc3dsd.engine;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import org.junit.Test;
import sc.fiji.oc3d.core.measure.LabelFeatureAccumulator;
import sc.fiji.oc3dsd.api.OC3DSD;
import sc.fiji.oc3dsd.api.OC3DSDParameters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Where the intensity columns come from when the user names no redirect image.
 *
 * <p>They come from the analysed image, as they always have in 3D Objects
 * Counter and as "Redirect to: None" means throughout ImageJ. The plugin used to
 * pass {@code null} to the measurement pass instead, and {@code null} is not "no
 * redirect" — it is "no intensities", so {@code IntDen}, {@code Mean},
 * {@code StdDev}, {@code Median}, {@code Min} and {@code Max} came back
 * {@code NaN} in every row of every default run, and {@code XM}/{@code YM}/
 * {@code ZM} silently collapsed onto the geometric centroid. Nothing in the
 * output said so; the columns were simply empty.
 *
 * <p>The geometry of the substituted source is the delicate part. The label
 * image is one channel with frames outermost and Z innermost, so an intensity
 * source that took the input's stack order — channels interleaved — would either
 * be rejected on slice count or, on a single-channel input, quietly measure the
 * right pixels in the wrong frames. The layout assertions below pin it.
 */
public class IntensitySourceDefaultTest {

    private static final int WIDTH = 6;
    private static final int HEIGHT = 6;
    private static final int CHANNELS = 2;
    private static final int SLICES = 4;
    private static final int FRAMES = 2;

    /** Every pixel of (c, z, t) carries {@code c*100 + z*10 + t}, so layout is readable. */
    private static int code(int c, int z, int t) {
        return c * 100 + z * 10 + t;
    }

    private static ImagePlus hyperstack() {
        ImageStack stack = new ImageStack(WIDTH, HEIGHT);
        // Canonical ImageJ order: c fastest, then z, then t.
        for (int t = 1; t <= FRAMES; t++) {
            for (int z = 1; z <= SLICES; z++) {
                for (int c = 1; c <= CHANNELS; c++) {
                    ByteProcessor ip = new ByteProcessor(WIDTH, HEIGHT);
                    ip.setValue(code(c, z, t));
                    ip.fill();
                    stack.addSlice(ip);
                }
            }
        }
        ImagePlus imp = new ImagePlus("input", stack);
        imp.setDimensions(CHANNELS, SLICES, FRAMES);
        Calibration cal = new Calibration();
        cal.pixelWidth = 0.25;
        cal.pixelHeight = 0.25;
        cal.pixelDepth = 1.5;
        cal.setUnit("micron");
        imp.setCalibration(cal);
        return imp;
    }

    /** A label image shaped exactly as the detector's output for {@link #hyperstack()}. */
    private static ImagePlus labelsMatching(int label, int fromZ, int toZ, int frame) {
        ImageStack stack = new ImageStack(WIDTH, HEIGHT);
        for (int t = 1; t <= FRAMES; t++) {
            for (int z = 1; z <= SLICES; z++) {
                FloatProcessor ip = new FloatProcessor(WIDTH, HEIGHT);
                if (t == frame && z >= fromZ && z <= toZ) {
                    for (int y = 1; y <= 2; y++) {
                        for (int x = 1; x <= 2; x++) ip.setf(x, y, label);
                    }
                }
                stack.addSlice(ip);
            }
        }
        ImagePlus imp = new ImagePlus("Label Image", stack);
        imp.setDimensions(1, SLICES, FRAMES);
        return imp;
    }

    private static OC3DSDParameters params(ImagePlus input, int channel, ImagePlus redirect) {
        return OC3DSD.builder(input).channel(channel).intensityImage(redirect).noMaps().build();
    }

    // ------------------------------------------------------------------
    // The substituted source
    // ------------------------------------------------------------------

    @Test
    public void defaultSourceIsTheAnalysedChannelLaidOutLikeTheLabelImage() {
        ImagePlus source = StarDistTrackMateRunner.analysedChannelStack(hyperstack(), 2);

        assertNotNull(source);
        assertEquals(SLICES * FRAMES, source.getStack().getSize());
        assertEquals(1, source.getNChannels());
        assertEquals(SLICES, source.getNSlices());
        assertEquals(FRAMES, source.getNFrames());

        // Frames outermost, Z innermost — the order the label image is assembled in.
        int slice = 1;
        for (int t = 1; t <= FRAMES; t++) {
            for (int z = 1; z <= SLICES; z++) {
                assertEquals("slice " + slice + " should be c=2 z=" + z + " t=" + t,
                        code(2, z, t), source.getStack().getProcessor(slice).get(0, 0));
                slice++;
            }
        }
    }

    @Test
    public void defaultSourceFollowsTheDetectionChannel() {
        ImagePlus input = hyperstack();

        assertEquals(code(1, 1, 1),
                StarDistTrackMateRunner.analysedChannelStack(input, 1).getStack().getProcessor(1).get(0, 0));
        assertEquals(code(2, 1, 1),
                StarDistTrackMateRunner.analysedChannelStack(input, 2).getStack().getProcessor(1).get(0, 0));
    }

    /** Out-of-range channels clamp rather than throw, as the detector's own does. */
    @Test
    public void channelIsClampedToWhatTheInputHas() {
        ImagePlus input = hyperstack();

        assertEquals(code(1, 1, 1),
                StarDistTrackMateRunner.analysedChannelStack(input, 0).getStack().getProcessor(1).get(0, 0));
        assertEquals(code(CHANNELS, 1, 1),
                StarDistTrackMateRunner.analysedChannelStack(input, 99).getStack().getProcessor(1).get(0, 0));
    }

    @Test
    public void defaultSourceCarriesTheInputCalibration() {
        ImagePlus source = StarDistTrackMateRunner.analysedChannelStack(hyperstack(), 1);

        assertEquals(0.25, source.getCalibration().pixelWidth, 1e-12);
        assertEquals(1.5, source.getCalibration().pixelDepth, 1e-12);
        assertEquals("micron", source.getCalibration().getUnit());
    }

    // ------------------------------------------------------------------
    // Which source a run measures from
    // ------------------------------------------------------------------

    @Test
    public void anExplicitRedirectIsUsedUnchanged() {
        ImagePlus redirect = StarDistTrackMateRunner.analysedChannelStack(hyperstack(), 1);

        assertSame(redirect, OC3DSDRunner.resolveIntensitySource(
                params(hyperstack(), 2, redirect)));
    }

    @Test
    public void noRedirectStillYieldsASource() {
        assertNotNull("no redirect must not mean no intensities",
                OC3DSDRunner.resolveIntensitySource(params(hyperstack(), 1, null)));
    }

    // ------------------------------------------------------------------
    // The defect itself
    // ------------------------------------------------------------------

    /**
     * The whole point: measured against the resolved source, the intensity
     * columns hold numbers. Values are exact because the fixture fills each
     * slice with a single code, so the object's four voxels per slice all carry
     * it.
     */
    @Test
    public void intensityColumnsAreMeasuredWhenNoRedirectIsGiven() {
        ImagePlus input = hyperstack();
        ImagePlus labels = labelsMatching(1, 1, 4, 1);
        OC3DSDParameters params = params(input, 1, null);

        ResultsTable table = LabelFeatureAccumulator
                .scan(labels, OC3DSDRunner.resolveIntensitySource(params), input.getCalibration())
                .toStatisticsTable(null);

        assertEquals(1, table.size());
        // 4 voxels per slice, z = 1..4 of frame 1: codes 111, 121, 131, 141.
        double perSlice = code(1, 1, 1) + code(1, 2, 1) + code(1, 3, 1) + code(1, 4, 1);
        assertEquals(4 * perSlice, value(table, "IntDen", 0), 1e-6);
        assertEquals(perSlice / 4.0, value(table, "Mean", 0), 1e-6);
        assertEquals(code(1, 1, 1), value(table, "Min", 0), 1e-6);
        assertEquals(code(1, 4, 1), value(table, "Max", 0), 1e-6);
        assertTrue("Median must be measured", !Double.isNaN(value(table, "Median", 0)));
        assertTrue("StdDev must be measured", !Double.isNaN(value(table, "StdDev", 0)));
    }

    /**
     * The second, quieter symptom. With no intensities the centre of mass falls
     * back to the geometric centroid, so {@code XM}/{@code YM}/{@code ZM} equalled
     * {@code X}/{@code Y}/{@code Z} in every row — which looks like data, not like
     * a missing measurement. This object brightens with Z, so its centre of mass
     * must sit deeper than its centroid.
     */
    @Test
    public void centreOfMassIsIntensityWeightedAgain() {
        ImagePlus input = hyperstack();
        ImagePlus labels = labelsMatching(1, 1, 4, 1);
        OC3DSDParameters params = params(input, 1, null);

        ResultsTable table = LabelFeatureAccumulator
                .scan(labels, OC3DSDRunner.resolveIntensitySource(params), input.getCalibration())
                .toStatisticsTable(null);

        assertTrue("ZM should be pulled towards the brighter deep slices, was "
                        + value(table, "ZM", 0) + " against Z " + value(table, "Z", 0),
                value(table, "ZM", 0) > value(table, "Z", 0));
    }

    /**
     * Frames are not interchangeable. An object in frame 2 must be measured
     * against frame 2's intensities — the failure a channel-interleaved or
     * frame-flattened source would produce, and one that yields entirely
     * plausible numbers.
     */
    @Test
    public void objectsAreMeasuredAgainstTheirOwnTimepoint() {
        ImagePlus input = hyperstack();
        ImagePlus labels = labelsMatching(1, 2, 2, 2);
        OC3DSDParameters params = params(input, 1, null);

        ResultsTable table = LabelFeatureAccumulator
                .scan(labels, OC3DSDRunner.resolveIntensitySource(params), input.getCalibration())
                .toStatisticsTable(null);

        // z=2 of frame 2, not of frame 1.
        assertEquals(code(1, 2, 2), value(table, "Mean", 0), 1e-6);
    }

    private static double value(ResultsTable table, String column, int row) {
        int index = table.getColumnIndex(column);
        assertTrue("missing column " + column, index != ResultsTable.COLUMN_NOT_FOUND);
        return table.getValueAsDouble(index, row);
    }
}
