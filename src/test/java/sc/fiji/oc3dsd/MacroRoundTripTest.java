package sc.fiji.oc3dsd;

import org.junit.Test;
import sc.fiji.oc3dsd.ui.OC3DSDDialogModel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A user moving between 3D Objects Counter+ and this plugin must not have to
 * relearn the macro vocabulary. Every option the two share keeps its name and
 * meaning; only the detection options are new, and only {@code threshold} is
 * gone, because there is nothing here for it to act on.
 */
public class MacroRoundTripTest {

    @Test
    public void detectionOptionsParse() {
        OC3DSDDialogModel model = MacroOptionsParser.parse(
                "channel=2 model=versatile_fluo probability=0.35 overlap=0.55 "
                        + "linking_distance=3.5 gap_distance=7 slice_gap=2 min_slices=3");

        assertEquals(2, model.channel);
        assertEquals("versatile_fluo", model.modelRef);
        assertEquals(0.35, model.probability, 1e-9);
        assertEquals(0.55, model.overlap, 1e-9);
        assertEquals(3.5, model.linkingDistance, 1e-9);
        assertEquals(7.0, model.gapDistance, 1e-9);
        assertEquals(2, model.sliceGap);
        assertEquals(3, model.minSlices);
    }

    @Test
    public void defaultsMatchTheDialogWhenOptionsAreAbsent() {
        OC3DSDDialogModel model = MacroOptionsParser.parse("");

        assertEquals(1, model.channel);
        assertEquals(0.5, model.probability, 1e-9);
        assertEquals(0.4, model.overlap, 1e-9);
        assertEquals(5.0, model.linkingDistance, 1e-9);
        assertEquals(5.0, model.gapDistance, 1e-9);
        assertEquals(1, model.sliceGap);
        assertEquals("single-slice objects are kept by default", 1, model.minSlices);
        assertEquals(10, model.minSize);
        assertEquals(Integer.MAX_VALUE, model.maxSize);
    }

    @Test
    public void sharedOptionsKeepTheirMeaning() {
        OC3DSDDialogModel model = MacroOptionsParser.parse(
                "min=25 max=5000 exclude_edges hide_surfaces hide_stats");

        assertEquals(25, model.minSize);
        assertEquals(5000, model.maxSize);
        assertTrue(model.excludeOnEdges);
        assertFalse(model.showSurfaces);
        assertFalse(model.showStats);
        assertTrue("options not named must keep their defaults", model.showLabels);
        assertTrue(model.showCentroids);
    }

    /** 3D Objects Counter+ accepts both spellings; so must this. */
    @Test
    public void bothSpellingsOfCentresOfMassAreAccepted() {
        assertFalse(MacroOptionsParser.parse("hide_centers_of_mass").showCentersOfMass);
        assertFalse(MacroOptionsParser.parse("hide_centres_of_mass").showCentersOfMass);
        assertTrue(MacroOptionsParser.parse("").showCentersOfMass);
    }

    @Test
    public void infinityIsAcceptedForMaxSize() {
        assertEquals(Integer.MAX_VALUE, MacroOptionsParser.parse("max=Infinity").maxSize);
        assertEquals(Integer.MAX_VALUE, MacroOptionsParser.parse("max=inf").maxSize);
    }

    @Test
    public void bracketedValuesSurviveSpaces() {
        OC3DSDDialogModel model = MacroOptionsParser.parse(
                "redirect=[My Image 01.tif] model=[C:\\models\\my model.zip]");

        assertEquals("My Image 01.tif", model.redirectTitle);
        assertEquals("C:\\models\\my model.zip", model.modelRef);
    }

    @Test
    public void hideDisplayIsRecognised() {
        assertTrue(MacroOptionsParser.isHidden("min=10 hide_display"));
        assertFalse(MacroOptionsParser.isHidden("min=10"));
    }

    @Test
    public void plusStyleFilterSyntaxParsesAndIsIgnored() {
        // This plugin has no morphology filters. 3D Objects Counter+ does, and
        // someone will paste one of its macros in here — so the predicate syntax
        // has to be tolerated rather than rejected. It is read as an unrecognised
        // option and has no effect, which is the same thing ImageJ does with any
        // option a command does not know.
        OC3DSDDialogModel model = MacroOptionsParser.parse(
                "sphericity>=0.6 min=0 max=Infinity");

        assertTrue(model.validate().isEmpty());
        assertTrue("a predicate in the options must not become a filter",
                model.enabledPredicates().isEmpty());
    }

    @Test
    public void aRecordedStringParsesBackToTheSameState() {
        OC3DSDDialogModel original = new OC3DSDDialogModel();
        original.channel = 3;
        original.probability = 0.7;
        original.overlap = 0.25;
        original.linkingDistance = 2.5;
        original.gapDistance = 4.0;
        original.sliceGap = 0;
        original.minSlices = 2;
        original.minSize = 42;
        original.maxSize = 9999;
        original.excludeOnEdges = true;
        original.showCentroids = false;
        original.saveLabels = true;

        OC3DSDDialogModel replayed = MacroOptionsParser.parse(original.toMacroOptions());

        assertEquals(original.channel, replayed.channel);
        assertEquals(original.probability, replayed.probability, 1e-9);
        assertEquals(original.overlap, replayed.overlap, 1e-9);
        assertEquals(original.linkingDistance, replayed.linkingDistance, 1e-9);
        assertEquals(original.gapDistance, replayed.gapDistance, 1e-9);
        assertEquals(original.sliceGap, replayed.sliceGap);
        assertEquals(original.minSlices, replayed.minSlices);
        assertEquals(original.minSize, replayed.minSize);
        assertEquals(original.maxSize, replayed.maxSize);
        assertEquals(original.excludeOnEdges, replayed.excludeOnEdges);
        assertEquals(original.showCentroids, replayed.showCentroids);
        assertEquals(original.saveLabels, replayed.saveLabels);
    }

    @Test
    public void validationRejectsOutOfRangeDetectionSettings() {
        OC3DSDDialogModel model = MacroOptionsParser.parse("probability=1.5 overlap=-0.2 min_slices=0");

        assertEquals(3, model.validate().size());
    }
}
