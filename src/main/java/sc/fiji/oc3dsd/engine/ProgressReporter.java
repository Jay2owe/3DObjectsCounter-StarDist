package sc.fiji.oc3dsd.engine;

import ij.IJ;

import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;

/**
 * ImageJ status-bar progress helper using native 3D Objects Counter style
 * step labels.
 */
final class ProgressReporter {

    private static final ProgressReporter NONE = new ProgressReporter(1, false);

    private final int totalSteps;
    private final boolean enabled;
    private int currentStep;

    private ProgressReporter(int totalSteps, boolean enabled) {
        this.totalSteps = Math.max(1, totalSteps);
        this.enabled = enabled;
    }

    static ProgressReporter steps(int totalSteps) {
        return new ProgressReporter(totalSteps, true);
    }

    static ProgressReporter none() {
        return NONE;
    }

    void step(String description) {
        if (!enabled) return;
        currentStep = Math.min(totalSteps, currentStep + 1);
        showStatus(formatStep(currentStep, totalSteps, description));
        showProgress(progressAtStepStart(currentStep, totalSteps));
    }

    void detail(String description) {
        if (!enabled) return;
        int step = currentStep <= 0 ? 1 : currentStep;
        showStatus(formatStep(step, totalSteps, description));
    }

    void finishStep() {
        if (!enabled || currentStep <= 0) return;
        showProgress(progressAtStepEnd(currentStep, totalSteps));
    }

    void finish(String description) {
        if (!enabled) return;
        currentStep = totalSteps;
        showStatus(formatStep(totalSteps, totalSteps, description));
        showProgress(1.0);
    }

    void error(String description) {
        if (!enabled) return;
        showStatus("3D Objects Counter+: " + safeDescription(description));
        showProgress(1.0);
    }

    static String formatStepForTests(int step, int totalSteps, String description) {
        return formatStep(step, totalSteps, description);
    }

    static double progressAtStepStartForTests(int step, int totalSteps) {
        return progressAtStepStart(step, totalSteps);
    }

    static double progressAtStepEndForTests(int step, int totalSteps) {
        return progressAtStepEnd(step, totalSteps);
    }

    private static String formatStep(int step, int totalSteps, String description) {
        int safeTotal = Math.max(1, totalSteps);
        int safeStep = Math.min(safeTotal, Math.max(1, step));
        return "Step " + safeStep + "/" + safeTotal + ": " + safeDescription(description);
    }

    private static String safeDescription(String description) {
        return description == null || description.trim().isEmpty()
                ? "Working" : description.trim();
    }

    private static double progressAtStepStart(int step, int totalSteps) {
        int safeTotal = Math.max(1, totalSteps);
        int safeStep = Math.min(safeTotal, Math.max(1, step));
        return (double) (safeStep - 1) / (double) safeTotal;
    }

    private static double progressAtStepEnd(int step, int totalSteps) {
        int safeTotal = Math.max(1, totalSteps);
        int safeStep = Math.min(safeTotal, Math.max(1, step));
        return (double) safeStep / (double) safeTotal;
    }

    private static void showStatus(String text) {
        runImageJUiUpdate(new Runnable() {
            @Override public void run() {
                try {
                    IJ.showStatus(text);
                } catch (RuntimeException ignored) {
                    // Headless and test environments may not have a live ImageJ UI.
                }
            }
        });
    }

    private static void showProgress(double progress) {
        runImageJUiUpdate(new Runnable() {
            @Override public void run() {
                try {
                    IJ.showProgress(progress);
                } catch (RuntimeException ignored) {
                    // Headless and test environments may not have a live ImageJ UI.
                }
            }
        });
    }

    private static void runImageJUiUpdate(Runnable update) {
        if (update == null) return;
        if (GraphicsEnvironment.isHeadless() || EventQueue.isDispatchThread()) {
            update.run();
            return;
        }
        EventQueue.invokeLater(update);
    }
}

