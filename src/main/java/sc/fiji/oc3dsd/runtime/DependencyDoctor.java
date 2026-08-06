package sc.fiji.oc3dsd.runtime;

import ij.IJ;
import ij.gui.GenericDialog;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the plugin launchable when some or all optional detector libraries are
 * absent, then offers the interactive user a one-click pinned-runtime repair.
 *
 * <p>Detection is by class name only. This class therefore has no compile-time
 * link to TrackMate, StarDist, CSBDeep or TensorFlow and can safely perform the
 * first-run check on a normal Fiji installation containing none of them.</p>
 */
public final class DependencyDoctor {

    /** One required runtime component and the class used to probe it. */
    public static final class Requirement {
        public final String probeClass;
        public final String component;

        Requirement(String probeClass, String component) {
            this.probeClass = probeClass;
            this.component = component;
        }
    }

    private static final Requirement[] REQUIREMENTS = {
            new Requirement("fiji.plugin.trackmate.TrackMate", "TrackMate"),
            new Requirement("fiji.plugin.trackmate.stardist.StarDistCustomDetectorFactory",
                    "TrackMate-StarDist"),
            new Requirement("de.csbdresden.stardist.StarDist2DModel", "StarDist"),
            new Requirement("de.csbdresden.csbdeep.commands.GenericNetwork", "CSBDeep"),
            new Requirement("net.imagej.tensorflow.TensorFlowService", "imagej-tensorflow"),
            new Requirement("org.tensorflow.TensorFlow", "TensorFlow runtime"),
    };

    private DependencyDoctor() {
    }

    /** The components that could not be loaded, in the order they are needed. */
    public static List<Requirement> missing() {
        List<Requirement> absent = new ArrayList<Requirement>();
        for (Requirement requirement : REQUIREMENTS) {
            if (!isPresent(requirement.probeClass)) absent.add(requirement);
        }
        return absent;
    }

    /** True when all components load and the installed TrackMate major is supported. */
    public static boolean isReady() {
        return diagnosis() == null;
    }

    /** A plain-language runtime problem, or {@code null} when ready. */
    public static String diagnosis() {
        List<Requirement> absent = missing();
        String versionProblem = TrackMateVersion.problem();
        if (absent.isEmpty()) return versionProblem;

        StringBuilder message = new StringBuilder();
        message.append("3D Objects Counter - StarDist cannot run because its detector runtime ")
                .append("is not fully installed.\n\nMissing or unloadable:\n");
        for (Requirement requirement : absent) {
            message.append("  - ").append(requirement.component).append('\n');
        }
        message.append("\nRun the plugin interactively to install the tested runtime in one step, ")
                .append("then restart Fiji.");
        if (versionProblem != null) message.append("\n\n").append(versionProblem);
        return message.toString();
    }

    /**
     * Compatibility overload retained for callers that used to supply an
     * updater service. Update-site state is no longer needed by the direct
     * pinned installer.
     */
    public static String diagnosis(Object ignoredUpdateService) {
        return diagnosis();
    }

    /** Runs the dependency check and offers one-click repair when interactive. */
    public static boolean verify(boolean interactive) {
        return verify(interactive, null);
    }

    /** Compatibility overload; {@code ignoredUpdateService} is intentionally unused. */
    public static boolean verify(boolean interactive, Object ignoredUpdateService) {
        String problem = diagnosis();
        if (problem == null) {
            TensorFlowCrashSentinel.clearIfStale();
            return true;
        }

        IJ.log(problem);
        if (!interactive || GraphicsEnvironment.isHeadless()) return false;
        offerPinnedInstall(problem);
        return false;
    }

    /** Call after a successful run so a future genuinely stale crash flag can be cleared. */
    public static void noteRanSuccessfully() {
        TensorFlowCrashSentinel.noteTensorFlowLoadedOk();
    }

    private static void offerPinnedInstall(String problem) {
        final File fijiDir = PinnedRuntimeInstaller.resolveFijiDir();
        final PinnedRuntimeInstaller installer = new PinnedRuntimeInstaller();
        final PinnedRuntimeInstaller.Audit audit = installer.audit(fijiDir);

        if (fijiDir == null) {
            IJ.error("3D Objects Counter - StarDist",
                    problem + "\n\nThe plugin could not locate the running Fiji.app folder, "
                            + "so it cannot install the runtime automatically.");
            return;
        }

        // The files may have been installed earlier in this same Fiji session.
        // Class probes cannot see newly added JARs until the user restarts.
        if (audit.isSatisfied()) {
            IJ.showMessage("Restart Fiji",
                    "The tested StarDist runtime is installed, but Fiji has not loaded it yet.\n\n"
                            + "Restart Fiji, then run 3D Objects Counter - StarDist again.");
            return;
        }

        GenericDialog confirm = new GenericDialog("Install StarDist runtime");
        confirm.addMessage("The detector runtime is missing or incompatible.\n\n"
                + "Install the exact versions tested with this plugin directly into:\n"
                + fijiDir.getAbsolutePath() + "\n\n"
                + "Download: up to about 159 MB. Existing conflicting JARs are renamed\n"
                + "with a .disabled date suffix, never deleted.");
        confirm.setOKLabel("Install Runtime");
        confirm.showDialog();
        if (confirm.wasCanceled()) return;

        PinnedRuntimeInstaller.InstallResult result = installer.install(fijiDir,
                new PinnedRuntimeInstaller.ProgressListener() {
                    @Override
                    public void update(String message, long completedBytes, long totalBytes) {
                        IJ.showStatus("3D Objects Counter - StarDist: " + message);
                        if (totalBytes > 0L) {
                            IJ.showProgress(Math.min(1.0,
                                    completedBytes / (double) totalBytes));
                        }
                    }
                });
        IJ.showProgress(1.0);
        logLines(result.getActions());

        if (result.isSuccessful()) {
            String sentinelAction = TensorFlowCrashSentinel.clearNow();
            if (!sentinelAction.isEmpty()) IJ.log(sentinelAction);
            IJ.showStatus("StarDist runtime installed - restart Fiji");
            IJ.showMessage("Runtime installed",
                    "The tested StarDist runtime was installed successfully.\n\n"
                            + "Restart Fiji, then run 3D Objects Counter - StarDist again.\n"
                            + "Fiji will not be restarted automatically.");
        } else {
            logLines(result.getErrors());
            IJ.showStatus("StarDist runtime installation incomplete");
            IJ.error("Runtime installation incomplete",
                    join(result.getErrors())
                            + "\n\nSuccessful downloads were kept. Run the plugin again to retry.");
        }
    }

    private static void logLines(List<String> lines) {
        for (String line : lines) IJ.log("StarDist runtime: " + line);
    }

    private static String join(List<String> lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            if (text.length() > 0) text.append('\n');
            text.append("- ").append(line);
        }
        return text.toString();
    }

    private static boolean isPresent(String className) {
        try {
            Class.forName(className, false, DependencyDoctor.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException absent) {
            return false;
        } catch (LinkageError broken) {
            return false;
        } catch (SecurityException denied) {
            return false;
        }
    }
}
