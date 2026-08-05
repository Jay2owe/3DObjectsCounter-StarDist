package sc.fiji.oc3dsd.engine;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins defect D5: TrackMate reads the linking distances in the image's
 * calibrated units, so the FLASH default of 5.0 means five pixels on an
 * uncalibrated stack and fifty at 0.1 um/pixel. Over-linking merges neighbouring
 * objects, which is the exact failure this plugin exists to prevent, so the
 * pixel equivalent has to be computable and shown.
 */
public class LinkingParamsCalibrationTest {

    @Test
    public void sameDistanceMeansDifferentPixelCountsOnDifferentCalibrations() {
        double distance = 5.0;

        assertEquals("uncalibrated: 5 units is 5 pixels",
                5.0, StarDistLinkingParams.pixelEquivalent(distance, 1.0), 1e-9);
        assertEquals("0.1 um/px: 5 um is 50 pixels",
                50.0, StarDistLinkingParams.pixelEquivalent(distance, 0.1), 1e-9);
        assertEquals("1.5 um/px: 5 um is about 3.33 pixels",
                3.3333333, StarDistLinkingParams.pixelEquivalent(distance, 1.5), 1e-6);
    }

    @Test
    public void treatsMissingOrNonsensicalPixelSizeAsUncalibrated() {
        assertEquals(5.0, StarDistLinkingParams.pixelEquivalent(5.0, 0.0), 1e-9);
        assertEquals(5.0, StarDistLinkingParams.pixelEquivalent(5.0, -2.0), 1e-9);
        assertEquals(5.0, StarDistLinkingParams.pixelEquivalent(5.0, Double.NaN), 1e-9);
        assertEquals(5.0, StarDistLinkingParams.pixelEquivalent(5.0, Double.POSITIVE_INFINITY), 1e-9);
    }

    @Test
    public void clampsNegativeInputs() {
        StarDistLinkingParams p = new StarDistLinkingParams(-1.0, -2.0, -3, -4);

        assertEquals(0.0, p.linkingMaxDistance, 0.0);
        assertEquals(0.0, p.gapClosingMaxDistance, 0.0);
        assertEquals(0, p.maxSliceGap);
        assertEquals("an object must span at least one slice", 1, p.minSlices);
    }

    @Test
    public void defaultsKeepSingleSliceObjects() {
        // D1: the default must not silently discard objects found on one slice.
        assertEquals(1, StarDistLinkingParams.defaults().minSlices);
    }

    @Test
    public void postFiltersTreatNonPositiveMaxAsUnbounded() {
        StarDistPostFilters filters = new StarDistPostFilters(0, 0, 0, 0);

        assertEquals(Double.POSITIVE_INFINITY, filters.areaMax, 0.0);
        assertFalse("all-zero filters must not filter anything", filters.isActive());
        assertEquals("none", filters.toString());
    }

    @Test
    public void postFiltersReportWhenActive() {
        StarDistPostFilters filters = new StarDistPostFilters(10, 500, 0.2, 0);

        assertTrue(filters.isActive());
        assertTrue(filters.toString().contains("area=10.0-500.0"));
        assertTrue(filters.toString().contains("quality>=0.2"));
        assertFalse(filters.toString().contains("intensity"));
    }
}
