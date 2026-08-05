package sc.fiji.oc3dsd.runtime;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins the TrackMate version guard.
 * <p>
 * The failure this prevents is not a missing dependency — it is a present one
 * with a changed API. TrackMate 8 reorganised the custom detector and segmenter
 * modules this plugin drives, so a v8 install fails inside
 * {@code execDetection()} after the model is loaded and slices are already
 * being processed. The parsing is kept separate from the reflective lookup
 * precisely so it can be pinned here without TrackMate being installed.
 */
public class TrackMateVersionTest {

    @Test
    public void readsTheMajorVersionFromTheStringsTrackMateActuallyReports() {
        assertEquals(7, TrackMateVersion.majorOf("7.14.0"));
        assertEquals(7, TrackMateVersion.majorOf("7.14"));
        assertEquals(8, TrackMateVersion.majorOf("8.0.0"));
        assertEquals(8, TrackMateVersion.majorOf("8.0.0-SNAPSHOT"));
        assertEquals(7, TrackMateVersion.majorOf("  7.14.0  "));
        // PLUGIN_NAME_VERSION is a display string, not a bare version.
        assertEquals(7, TrackMateVersion.majorOf("TrackMate v7.14.0"));
    }

    @Test
    public void treatsAnUnreadableVersionAsUnknownRatherThanGuessing() {
        assertEquals(-1, TrackMateVersion.majorOf(null));
        assertEquals(-1, TrackMateVersion.majorOf(""));
        assertEquals(-1, TrackMateVersion.majorOf("   "));
        assertEquals(-1, TrackMateVersion.majorOf("unknown"));
        assertEquals(-1, TrackMateVersion.majorOf("999999999999"));
    }

    @Test
    public void acceptsTheSevenSeriesItIsBuiltAgainst() {
        assertNull(TrackMateVersion.problemFor("7.14.0"));
        assertNull(TrackMateVersion.problemFor("7.0.0"));
        assertNull(TrackMateVersion.problemFor("7.99.9"));
    }

    @Test
    public void rejectsTrackMate8AndSaysWhy() {
        String problem = TrackMateVersion.problemFor("8.0.0");

        assertNotNull("TrackMate 8 must be refused before a run starts", problem);
        assertTrue("names the version found", problem.contains("8.0.0"));
        assertTrue("explains the detector API change", problem.contains("detector"));
        assertTrue("warns about the Java requirement", problem.contains("Java 21"));
        assertTrue("says what was tested", problem.contains(TrackMateVersion.TESTED_AGAINST));
    }

    @Test
    public void rejectsVersionsOlderThanTheDetectorApi() {
        String problem = TrackMateVersion.problemFor("6.0.1");

        assertNotNull(problem);
        assertTrue(problem.contains("6.0.1"));
    }

    @Test
    public void failsOpenWhenTheVersionCannotBeDetermined() {
        // An unreadable version string is not evidence of an incompatible one.
        // Blocking here would make the plugin unusable on any install whose
        // manifest is unusual; the LinkageError catch remains the backstop.
        assertNull(TrackMateVersion.problemFor(null));
        assertNull(TrackMateVersion.problemFor(""));
        assertNull(TrackMateVersion.problemFor("not a version"));
    }

    @Test
    public void theTrackMateTheProjectBuildsAgainstPassesItsOwnGuard() {
        // TrackMate is a provided-scope dependency, so it is on the test
        // classpath. If the pom is ever bumped past the supported major this
        // fails here rather than in front of a user.
        String installed = TrackMateVersion.installed();
        if (installed == null) return; // not resolvable in this environment

        assertNull("the pom's TrackMate must satisfy the guard, but " + installed + " does not",
                TrackMateVersion.problemFor(installed));
    }

    @Test
    public void theDeclaredTestedVersionIsItselfSupported() {
        assertNull(TrackMateVersion.problemFor(TrackMateVersion.TESTED_AGAINST));
        assertEquals(TrackMateVersion.SUPPORTED_MAJOR,
                TrackMateVersion.majorOf(TrackMateVersion.TESTED_AGAINST));
    }
}
