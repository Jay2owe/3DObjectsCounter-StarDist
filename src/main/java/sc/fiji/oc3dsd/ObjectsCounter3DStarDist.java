package sc.fiji.oc3dsd;

import ij.IJ;
import ij.ImagePlus;
import ij.Macro;
import ij.WindowManager;
import ij.plugin.PlugIn;
import ij.plugin.frame.Recorder;
import sc.fiji.oc3dsd.api.OC3DSD;
import sc.fiji.oc3dsd.api.OC3DSDParameters;
import sc.fiji.oc3dsd.api.OC3DSDResult;
import sc.fiji.oc3dsd.runtime.DependencyDoctor;
import sc.fiji.oc3dsd.ui.OC3DSDDialog;
import sc.fiji.oc3dsd.ui.OC3DSDDialogModel;

import java.util.List;

/**
 * {@code Analyze > 3D Objects Counter - StarDist}.
 * <p>
 * Routes between macro and interactive use, checks the detector is installed
 * before anything expensive happens, runs the analysis and shows the results.
 */
public class ObjectsCounter3DStarDist implements PlugIn {

    @Override
    public void run(String arg) {
        String options = Macro.getOptions();
        boolean interactive = options == null;
        boolean hidden = !interactive && MacroOptionsParser.isHidden(options);

        ImagePlus image = WindowManager.getCurrentImage();
        if (image == null) {
            fail(interactive, "Open a Z-stack first.");
            return;
        }
        if (image.getNSlices() < 2) {
            fail(interactive, "This plugin needs a Z-stack. '" + image.getTitle()
                    + "' has a single slice.");
            return;
        }

        // The detector chain is four update sites and a large native library.
        // Check before the user waits, and name what is missing.
        if (!DependencyDoctor.verify(interactive)) return;

        OC3DSDDialogModel model;
        if (interactive) {
            model = new OC3DSDDialogModel();
            if (!new OC3DSDDialog(model, image).show()) return;
        } else {
            model = MacroOptionsParser.parse(options);
        }

        List<String> errors = model.validate();
        if (!errors.isEmpty()) {
            fail(interactive, join(errors));
            return;
        }

        ImagePlus redirect = resolveRedirect(model.redirectTitle, interactive);
        if (redirect == null && model.redirectTitle != null && !model.redirectTitle.isEmpty()) {
            return;
        }

        try {
            OC3DSDParameters params = model.toParameters(image, redirect, new OC3DSDParameters.WarningSink() {
                @Override public void warn(String message) {
                    IJ.log("WARNING: " + message);
                }
            });
            OC3DSDResult result = OC3DSD.run(params);

            if (interactive && Recorder.record) {
                Recorder.recordString("run(\"3D Objects Counter - StarDist\", \""
                        + model.toMacroOptions() + "\");\n");
            }

            report(result, model, image);
            if (!hidden) display(result, model);
        } catch (IllegalArgumentException badInput) {
            fail(interactive, badInput.getMessage());
        } catch (RuntimeException failure) {
            fail(interactive, failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage());
        }
    }

    /**
     * The native-style summary log, plus the four lines this plugin adds because
     * its method has failure modes a threshold does not.
     */
    private void report(OC3DSDResult result, OC3DSDDialogModel model, ImagePlus image) {
        if (!model.showSummary) return;
        IJ.log("3D Objects Counter - StarDist: " + image.getTitle());
        IJ.log("  Objects: " + result.getObjectCount());
        IJ.log("  Model: " + sc.fiji.oc3dsd.runtime.ModelResolver.displayName(model.modelRef)
                + ", probability " + model.probability + ", overlap " + model.overlap);
        String unit = OC3DSDDialogModel.linkingUnit(image);
        IJ.log("  Linking: " + model.linkingDistance + " " + unit
                + " (" + round2(model.linkingDistanceInPixels(image)) + " px)"
                + ", gap " + model.gapDistance + " " + unit
                + ", slice gap " + model.sliceGap
                + ", min slices " + model.minSlices);
        IJ.log("  Single-slice objects: " + result.getSingleSliceObjects()
                + (result.getDroppedShortObjects() > 0
                ? "  (" + result.getDroppedShortObjects() + " dropped below the minimum)" : ""));
        int dropped = result.getDroppedByDetectorFilters() + result.getDroppedByObjectFilters();
        if (dropped > 0) {
            IJ.log("  Filtered out: " + result.getDroppedByDetectorFilters()
                    + " by detector filters, " + result.getDroppedByObjectFilters()
                    + " by size or edge filters");
        }
        IJ.log("  Elapsed: " + result.getElapsedMs() + " ms");
    }

    private void display(OC3DSDResult result, OC3DSDDialogModel model) {
        if (model.showStats && result.getObjects() != null) {
            result.getObjects().show("3D Objects Counter - StarDist Objects");
        }
        if (model.showSummary && result.getSummary() != null) {
            result.getSummary().show("3D Objects Counter - StarDist Summary");
        }
        showIfPresent(result.getObjectMap());
        showIfPresent(result.getSurfaceMap());
        showIfPresent(result.getCentroidMap());
        showIfPresent(result.getCentreOfMassMap());
        if (model.saveLabels) showIfPresent(result.getLabelImage());
    }

    private static void showIfPresent(ImagePlus image) {
        if (image != null) image.show();
    }

    private static ImagePlus resolveRedirect(String title, boolean interactive) {
        if (title == null || title.isEmpty()) return null;
        ImagePlus found = WindowManager.getImage(title);
        if (found == null) {
            String message = "Redirect image '" + title + "' is not open.";
            IJ.log("WARNING: " + message);
            if (interactive) IJ.error("3D Objects Counter - StarDist", message);
        }
        return found;
    }

    private static void fail(boolean interactive, String message) {
        String text = message == null || message.trim().isEmpty()
                ? "3D Objects Counter - StarDist could not run." : message;
        IJ.log("WARNING: " + text);
        if (interactive) IJ.error("3D Objects Counter - StarDist", text);
    }

    private static String join(List<String> messages) {
        StringBuilder sb = new StringBuilder();
        for (String message : messages) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(message);
        }
        return sb.toString();
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
