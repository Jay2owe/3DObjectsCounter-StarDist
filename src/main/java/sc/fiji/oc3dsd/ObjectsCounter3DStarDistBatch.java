package sc.fiji.oc3dsd;

import ij.IJ;
import ij.Macro;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import ij.plugin.frame.Recorder;
import sc.fiji.oc3dsd.batch.BatchDiscovery;
import sc.fiji.oc3dsd.batch.BatchRunner;
import sc.fiji.oc3dsd.runtime.DependencyDoctor;
import sc.fiji.oc3dsd.ui.OC3DSDDialogModel;

import java.io.File;
import java.util.List;

/**
 * {@code Analyze > 3D Objects Counter - StarDist Batch...}
 * <p>
 * Recursive discovery and regex grouping, together. The group preview is shown
 * and confirmed before anything runs, because each image costs minutes of
 * detection time and a pattern that accidentally puts eighty files into eighty
 * groups is an expensive mistake to discover afterwards.
 */
public class ObjectsCounter3DStarDistBatch implements PlugIn {

    @Override
    public void run(String arg) {
        String options = Macro.getOptions();
        boolean interactive = options == null;

        if (!DependencyDoctor.verify(interactive)) return;

        BatchRunner.Settings settings = new BatchRunner.Settings();
        OC3DSDDialogModel model;

        if (interactive) {
            model = new OC3DSDDialogModel();
            if (!askSettings(settings, model)) return;
            if (!confirmGroups(settings)) return;
        } else {
            model = MacroOptionsParser.parse(options);
            settings.inputRoot = fileOption(options, "input");
            settings.outputRoot = fileOption(options, "output");
            settings.recursive = !MacroOptionsParser.hasFlag(options, "no_recursive");
            String extensions = MacroOptionsParser.getValue(options, "extensions", null);
            if (extensions != null && !extensions.isEmpty()) settings.extensions = extensions;
            String pattern = MacroOptionsParser.getBracketed(options, "pattern", null);
            if (pattern != null) settings.pattern = pattern;
            String group = MacroOptionsParser.getValue(options, "group", null);
            if (group != null && !group.isEmpty()) {
                try {
                    settings.groupIndex = Integer.parseInt(group.trim());
                } catch (NumberFormatException nfe) {
                    IJ.error("3D Objects Counter - StarDist Batch",
                            "group must be a whole number (group='" + group + "').");
                    return;
                }
            }
            settings.skipUnmatched = MacroOptionsParser.hasFlag(options, "skip_unmatched");
            settings.saveLabels = !MacroOptionsParser.hasFlag(options, "no_labels");
            settings.saveMaps = MacroOptionsParser.hasFlag(options, "save_maps");
        }

        List<String> errors = model.validate();
        if (!errors.isEmpty()) {
            report(interactive, join(errors));
            return;
        }

        try {
            BatchRunner.Outcome outcome = BatchRunner.run(settings, model);
            if (interactive && Recorder.record) {
                Recorder.recordString("run(\"3D Objects Counter - StarDist Batch...\", \""
                        + macroOptions(settings, model) + "\");\n");
            }
            if (interactive) {
                IJ.showMessage("3D Objects Counter - StarDist Batch",
                        outcome.imagesProcessed + " image(s) processed, "
                                + outcome.totalObjects + " object(s)."
                                + (outcome.imagesFailed > 0
                                ? "\n" + outcome.imagesFailed + " image(s) failed — see the Log."
                                : "")
                                + "\n\nOutput: " + outcome.outputRoot.getAbsolutePath());
            }
        } catch (IllegalArgumentException badInput) {
            report(interactive, badInput.getMessage());
        } catch (RuntimeException failure) {
            report(interactive, failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage());
        }
    }

    private boolean askSettings(BatchRunner.Settings settings, OC3DSDDialogModel model) {
        GenericDialog gd = new GenericDialog("3D Objects Counter - StarDist Batch");

        gd.addMessage("Input");
        gd.addDirectoryField("Input folder", "");
        gd.addDirectoryField("Output folder", "");
        gd.addCheckbox("Include subfolders", true);
        gd.addStringField("File extensions", BatchDiscovery.DEFAULT_EXTENSIONS, 30);

        gd.addMessage("Grouping  (optional)");
        gd.addStringField("Filename pattern (regex)", "", 30);
        gd.addNumericField("Group key (capture group)", 1, 0);
        gd.addCheckbox("Skip files that do not match", false);
        gd.addMessage("The capture group names what the files in a group SHARE — the\n"
                + "condition. Example: ^(\\w+?)_.*\\.tif$ puts WT_animal3_LH.tif in\n"
                + "group WT. Leave the pattern blank to treat every file as one group.\n"
                + "Files matching nothing are still analysed, under <ungrouped>.");

        gd.addMessage("Detection");
        gd.addStringField("Model", model.modelRef, 30);
        gd.addNumericField("Probability", model.probability, 2);
        gd.addNumericField("Overlap (NMS)", model.overlap, 2);
        gd.addNumericField("Linking max distance", model.linkingDistance, 2);
        gd.addNumericField("Gap closing max distance", model.gapDistance, 2);
        gd.addNumericField("Max slice gap", model.sliceGap, 0);
        gd.addNumericField("Min. slices per object", model.minSlices, 0);
        gd.addNumericField("Channel", model.channel, 0);
        gd.addNumericField("Min size (voxels)", model.minSize, 0);

        gd.addMessage("Output");
        gd.addCheckbox("Save 3D label images", true);
        gd.addCheckbox("Save maps", false);

        gd.showDialog();
        if (gd.wasCanceled()) return false;

        String input = gd.getNextString();
        String output = gd.getNextString();
        settings.inputRoot = input == null || input.trim().isEmpty() ? null : new File(input.trim());
        settings.outputRoot = output == null || output.trim().isEmpty()
                ? settings.inputRoot : new File(output.trim());
        settings.recursive = gd.getNextBoolean();
        settings.extensions = gd.getNextString();

        settings.pattern = gd.getNextString();
        settings.groupIndex = (int) gd.getNextNumber();
        settings.skipUnmatched = gd.getNextBoolean();

        model.modelRef = gd.getNextString();
        model.probability = gd.getNextNumber();
        model.overlap = gd.getNextNumber();
        model.linkingDistance = gd.getNextNumber();
        model.gapDistance = gd.getNextNumber();
        model.sliceGap = (int) gd.getNextNumber();
        model.minSlices = (int) gd.getNextNumber();
        model.channel = (int) gd.getNextNumber();
        model.minSize = (int) gd.getNextNumber();

        settings.saveLabels = gd.getNextBoolean();
        settings.saveMaps = gd.getNextBoolean();

        if (settings.inputRoot == null || !settings.inputRoot.isDirectory()) {
            IJ.error("3D Objects Counter - StarDist Batch", "Choose an input folder that exists.");
            return false;
        }
        return true;
    }

    /**
     * Shows what will be run, grouped, and waits for confirmation. This is the
     * cheapest possible check on an expensive mistake.
     */
    private boolean confirmGroups(BatchRunner.Settings settings) {
        List<File> files = BatchDiscovery.discover(
                settings.inputRoot, settings.recursive, settings.extensions);
        if (files.isEmpty()) {
            IJ.error("3D Objects Counter - StarDist Batch",
                    "No matching images found in " + settings.inputRoot.getAbsolutePath()
                            + (settings.recursive ? " or its subfolders." : "."));
            return false;
        }
        List<BatchDiscovery.Item> items;
        try {
            items = BatchDiscovery.group(files, settings.pattern, settings.groupIndex);
        } catch (IllegalArgumentException badPattern) {
            IJ.error("3D Objects Counter - StarDist Batch", badPattern.getMessage());
            return false;
        }

        GenericDialog preview = new GenericDialog("Confirm groups");
        preview.addMessage(BatchDiscovery.previewText(items, 3));
        preview.addMessage("Detection takes minutes per image. Check this before starting.");
        preview.setOKLabel("Run");
        preview.showDialog();
        return !preview.wasCanceled();
    }

    private static String macroOptions(BatchRunner.Settings settings, OC3DSDDialogModel model) {
        StringBuilder sb = new StringBuilder();
        sb.append("input=[").append(MacroOptionsParser.requireSafeBracketedValue(
                settings.inputRoot.getAbsolutePath(), "Input folder")).append(']');
        if (settings.outputRoot != null) {
            sb.append(" output=[").append(MacroOptionsParser.requireSafeBracketedValue(
                    settings.outputRoot.getAbsolutePath(), "Output folder")).append(']');
        }
        if (!settings.recursive) sb.append(" no_recursive");
        sb.append(" extensions=").append(settings.extensions.replace(" ", ""));
        if (settings.pattern != null && !settings.pattern.isEmpty()) {
            sb.append(" pattern=[").append(MacroOptionsParser.requireSafeBracketedValue(
                    settings.pattern, "Filename pattern")).append(']');
            sb.append(" group=").append(settings.groupIndex);
        }
        if (settings.skipUnmatched) sb.append(" skip_unmatched");
        if (!settings.saveLabels) sb.append(" no_labels");
        if (settings.saveMaps) sb.append(" save_maps");
        sb.append(' ').append(model.toMacroOptions());
        return sb.toString();
    }

    private static File fileOption(String options, String key) {
        String value = MacroOptionsParser.getBracketed(options, key, null);
        if (value == null) value = MacroOptionsParser.getValue(options, key, null);
        return value == null || value.trim().isEmpty() ? null : new File(value.trim());
    }

    private static void report(boolean interactive, String message) {
        String text = message == null || message.trim().isEmpty()
                ? "3D Objects Counter - StarDist Batch could not run." : message;
        IJ.log("WARNING: " + text);
        if (interactive) IJ.error("3D Objects Counter - StarDist Batch", text);
    }

    private static String join(List<String> messages) {
        StringBuilder sb = new StringBuilder();
        for (String message : messages) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(message);
        }
        return sb.toString();
    }
}
