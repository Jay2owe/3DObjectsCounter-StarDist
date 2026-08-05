package sc.fiji.oc3dsd.runtime;

import ij.IJ;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks that the detector chain is actually installed, and says which update
 * site is missing when it is not.
 * <p>
 * This plugin needs four update sites and roughly 166 MB of native TensorFlow.
 * That is its single biggest barrier to adoption, and it cannot be engineered
 * away — the learned detector <em>is</em> the plugin. What can be fixed is the
 * failure mode: without this check the user gets a {@code NoClassDefFoundError}
 * or an unexplained "Could not load TensorFlow" dialog and no idea which of the
 * four sites they forgot. The check turns that into an instruction.
 * <p>
 * Detection is by reflection on class names only, so this class itself has no
 * compile-time dependency on anything it probes and stays loadable even when
 * every one of them is absent.
 */
public final class DependencyDoctor {

    /** One required component and the update site that supplies it. */
    public static final class Requirement {
        public final String probeClass;
        public final String component;
        public final String updateSite;

        Requirement(String probeClass, String component, String updateSite) {
            this.probeClass = probeClass;
            this.component = component;
            this.updateSite = updateSite;
        }
    }

    private static final Requirement[] REQUIREMENTS = {
            new Requirement("fiji.plugin.trackmate.TrackMate",
                    "TrackMate", "part of the Fiji core distribution"),
            new Requirement("fiji.plugin.trackmate.stardist.StarDistCustomDetectorFactory",
                    "TrackMate-StarDist", "TrackMate-StarDist"),
            new Requirement("de.csbdresden.stardist.StarDist2DModel",
                    "StarDist", "StarDist"),
            new Requirement("de.csbdresden.csbdeep.commands.GenericNetwork",
                    "CSBDeep", "CSBDeep"),
            new Requirement("net.imagej.tensorflow.TensorFlowService",
                    "imagej-tensorflow", "TensorFlow"),
            new Requirement("org.tensorflow.TensorFlow",
                    "TensorFlow runtime", "TensorFlow"),
    };

    private DependencyDoctor() {
    }

    /** The components that could not be found, in the order they are needed. */
    public static List<Requirement> missing() {
        List<Requirement> absent = new ArrayList<Requirement>();
        for (Requirement requirement : REQUIREMENTS) {
            if (!isPresent(requirement.probeClass)) absent.add(requirement);
        }
        return absent;
    }

    /** True when every component is present and the TrackMate version is one this plugin supports. */
    public static boolean isReady() {
        return diagnosis() == null;
    }

    /**
     * A message naming exactly what is missing and how to install it, or
     * {@code null} when everything is present.
     */
    public static String diagnosis() {
        return diagnosis(null);
    }

    /**
     * As {@link #diagnosis()}, and additionally names required update sites that
     * are known to the updater but switched off.
     * <p>
     * The class probe above is the stronger check — it asks whether the code is
     * actually loadable, which is what a run needs — but it cannot distinguish
     * "you never enabled this site" from "you enabled it and have not restarted
     * yet". When a SciJava {@code UpdateService} is available the site state
     * answers that, so the message can tell the user which of the two they are
     * looking at.
     *
     * @param updateService a {@code net.imagej.updater.UpdateService}, or
     *                      {@code null} when none is available. Typed as
     *                      {@code Object} and used reflectively so this class
     *                      keeps its property of having no compile-time
     *                      dependency on anything it probes; a future
     *                      {@code Command} can inject the real service with
     *                      {@code @Parameter} and pass it straight in.
     */
    public static String diagnosis(Object updateService) {
        List<Requirement> absent = missing();
        String versionProblem = TrackMateVersion.problem();

        // An incompatible TrackMate is reported on its own. It is not a missing
        // component and the install instructions below do not apply to it.
        if (absent.isEmpty()) return versionProblem;

        StringBuilder sb = new StringBuilder();
        sb.append("3D Objects Counter - StarDist cannot run: the detector is not fully installed.\n\n");
        sb.append("Missing:\n");
        List<String> sites = new ArrayList<String>();
        for (Requirement requirement : absent) {
            sb.append("  - ").append(requirement.component)
              .append("  (update site: ").append(requirement.updateSite).append(")\n");
            if (!sites.contains(requirement.updateSite)
                    && !requirement.updateSite.startsWith("part of")) {
                sites.add(requirement.updateSite);
            }
        }
        if (!sites.isEmpty()) {
            sb.append("\nTo install: Help > Update... > Manage update sites, then enable ");
            for (int i = 0; i < sites.size(); i++) {
                if (i > 0) sb.append(i == sites.size() - 1 ? " and " : ", ");
                sb.append(sites.get(i));
            }
            sb.append(".\nApply changes and restart Fiji. The TensorFlow site is a large ");
            sb.append("download (about 166 MB).");

            List<String> enabledButNotLoaded = new ArrayList<String>();
            for (String site : inactiveUpdateSites(updateService, sites)) {
                enabledButNotLoaded.add(site);
            }
            if (!enabledButNotLoaded.isEmpty()) {
                sb.append("\n\nThe updater reports these sites are switched off: ");
                sb.append(join(enabledButNotLoaded)).append(".");
            } else if (updateService != null) {
                sb.append("\n\nThe updater reports the required sites are already enabled, ");
                sb.append("so the download is probably incomplete or Fiji has not been ");
                sb.append("restarted since. Run Help > Update... once more, then restart.");
            }
        }
        if (versionProblem != null) {
            sb.append("\n\n").append(versionProblem);
        }
        return sb.toString();
    }

    /**
     * Which of {@code sites} the updater knows about but has switched off.
     * <p>
     * Returns an empty list when no {@code UpdateService} was supplied, when it
     * does not behave as expected, or when nothing is switched off — "cannot
     * tell" and "nothing wrong" are deliberately the same answer here, because
     * this check only ever adds detail to a diagnosis the class probe has
     * already made.
     *
     * @param updateService a {@code net.imagej.updater.UpdateService}, or {@code null}
     * @param siteNames     update-site names to ask about
     */
    public static List<String> inactiveUpdateSites(Object updateService, List<String> siteNames) {
        List<String> inactive = new ArrayList<String>();
        if (updateService == null || siteNames == null || siteNames.isEmpty()) return inactive;
        for (String name : siteNames) {
            Boolean active = isSiteActive(updateService, name);
            if (active != null && !active.booleanValue()) inactive.add(name);
        }
        return inactive;
    }

    /** Every update site this plugin needs, whether or not it is currently missing. */
    public static List<String> requiredUpdateSites() {
        List<String> sites = new ArrayList<String>();
        for (Requirement requirement : REQUIREMENTS) {
            if (requirement.updateSite.startsWith("part of")) continue;
            if (!sites.contains(requirement.updateSite)) sites.add(requirement.updateSite);
        }
        return sites;
    }

    /**
     * {@code updateService.getUpdateSite(name).isActive()}, reflectively.
     *
     * @return {@code TRUE} or {@code FALSE}, or {@code null} when the question
     *         could not be asked or the site is not in the user's list at all
     */
    private static Boolean isSiteActive(Object updateService, String name) {
        try {
            Object site = updateService.getClass()
                    .getMethod("getUpdateSite", String.class)
                    .invoke(updateService, name);
            if (site == null) return null;
            Object active = site.getClass().getMethod("isActive").invoke(site);
            return active instanceof Boolean ? (Boolean) active : null;
        } catch (Exception unavailable) {
            // No updater on the classpath, a different API shape, or a headless
            // context with no configuration to read. Not knowing is fine.
            return null;
        } catch (LinkageError broken) {
            return null;
        }
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(i == items.size() - 1 ? " and " : ", ");
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    /**
     * Runs the check and, if anything is missing, reports it and returns false.
     *
     * @param interactive whether to show a dialog as well as logging
     */
    public static boolean verify(boolean interactive) {
        return verify(interactive, null);
    }

    /**
     * As {@link #verify(boolean)}, with update-site state included in the
     * message when a {@code net.imagej.updater.UpdateService} is available.
     */
    public static boolean verify(boolean interactive, Object updateService) {
        String diagnosis = diagnosis(updateService);
        if (diagnosis == null) {
            // TensorFlow is about to be loaded natively. A crash flag left over
            // from an earlier Fiji session makes Fiji refuse to load it even
            // though nothing is wrong, so clear a stale one first.
            TensorFlowCrashSentinel.clearIfStale();
            return true;
        }
        IJ.log(diagnosis);
        if (interactive) IJ.error("3D Objects Counter - StarDist", diagnosis);
        return false;
    }

    /** Call after a successful run so a genuinely orphaned crash flag can be cleared again later. */
    public static void noteRanSuccessfully() {
        TensorFlowCrashSentinel.noteTensorFlowLoadedOk();
    }

    private static boolean isPresent(String className) {
        try {
            Class.forName(className, false, DependencyDoctor.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException absent) {
            return false;
        } catch (LinkageError broken) {
            // Present but unloadable — a version conflict. Treat as missing; the
            // user has to fix the install either way.
            return false;
        }
    }
}
