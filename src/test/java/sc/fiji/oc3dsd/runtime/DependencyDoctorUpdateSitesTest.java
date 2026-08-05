package sc.fiji.oc3dsd.runtime;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the update-site half of the pre-flight check.
 * <p>
 * The class probe in {@link DependencyDoctor} answers "is the code loadable",
 * which is what a run actually needs. It cannot tell a user who never enabled
 * StarDist apart from one who enabled it and has not restarted Fiji yet. The
 * SciJava {@code UpdateService} answers that, and is consulted reflectively so
 * the plugin still builds and loads without the updater on the classpath.
 * <p>
 * The service is stubbed here with the same method shape
 * ({@code getUpdateSite(String)} returning something with {@code isActive()}),
 * which is exactly what the reflection binds to.
 */
public class DependencyDoctorUpdateSitesTest {

    /** Stands in for {@code net.imagej.updater.UpdateSite}. */
    public static final class FakeSite {
        private final boolean active;

        FakeSite(boolean active) {
            this.active = active;
        }

        public boolean isActive() {
            return active;
        }
    }

    /** Stands in for {@code net.imagej.updater.UpdateService}. */
    public static final class FakeUpdateService {
        private final Map<String, FakeSite> sites = new HashMap<String, FakeSite>();

        FakeUpdateService with(String name, boolean active) {
            sites.put(name, new FakeSite(active));
            return this;
        }

        public FakeSite getUpdateSite(String name) {
            return sites.get(name);
        }
    }

    private static final List<String> REQUIRED =
            Arrays.asList("StarDist", "CSBDeep", "TrackMate-StarDist", "TensorFlow");

    @Test
    public void namesTheSitesTheUpdaterHasSwitchedOff() {
        FakeUpdateService service = new FakeUpdateService()
                .with("StarDist", false)
                .with("CSBDeep", true)
                .with("TrackMate-StarDist", false)
                .with("TensorFlow", true);

        List<String> inactive = DependencyDoctor.inactiveUpdateSites(service, REQUIRED);

        assertEquals(Arrays.asList("StarDist", "TrackMate-StarDist"), inactive);
    }

    @Test
    public void saysNothingWhenEverySiteIsEnabled() {
        FakeUpdateService service = new FakeUpdateService()
                .with("StarDist", true)
                .with("CSBDeep", true)
                .with("TrackMate-StarDist", true)
                .with("TensorFlow", true);

        assertTrue(DependencyDoctor.inactiveUpdateSites(service, REQUIRED).isEmpty());
    }

    @Test
    public void aSiteTheUpdaterHasNeverHeardOfIsNotReportedAsSwitchedOff() {
        // getUpdateSite returns null for a site absent from the user's list.
        // That is a different condition from "present and disabled", and the
        // class probe already covers it, so it must not be double-reported.
        FakeUpdateService service = new FakeUpdateService().with("CSBDeep", true);

        assertTrue(DependencyDoctor.inactiveUpdateSites(service, REQUIRED).isEmpty());
    }

    @Test
    public void notKnowingIsSafe() {
        // No service, no updater, or an object with a different API shape: the
        // check must degrade to silence rather than to a false accusation.
        assertTrue(DependencyDoctor.inactiveUpdateSites(null, REQUIRED).isEmpty());
        assertTrue(DependencyDoctor.inactiveUpdateSites("not a service", REQUIRED).isEmpty());
        assertTrue(DependencyDoctor.inactiveUpdateSites(new Object(), REQUIRED).isEmpty());
    }

    @Test
    public void toleratesMissingOrEmptySiteLists() {
        FakeUpdateService service = new FakeUpdateService().with("StarDist", false);

        assertTrue(DependencyDoctor.inactiveUpdateSites(service, null).isEmpty());
        assertTrue(DependencyDoctor.inactiveUpdateSites(service, new ArrayList<String>()).isEmpty());
    }

    @Test
    public void thePluginKnowsWhichUpdateSitesItNeeds() {
        List<String> required = DependencyDoctor.requiredUpdateSites();

        assertTrue("StarDist", required.contains("StarDist"));
        assertTrue("CSBDeep", required.contains("CSBDeep"));
        assertTrue("TrackMate-StarDist", required.contains("TrackMate-StarDist"));
        assertTrue("TensorFlow", required.contains("TensorFlow"));
        assertFalse("TrackMate ships with Fiji and has no site of its own",
                required.contains("part of the Fiji core distribution"));
    }
}
