package sc.fiji.oc3dsd.ui;

import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.GenericDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * The dialog, in 3D Objects Counter+'s idiom: Input, then the parameters unique
 * to this plugin, then Filters and Output.
 * <p>
 * Built on {@link GenericDialog} so the plugin is macro-recordable for free and
 * behaves identically headless — the same choice 3D Objects Counter+ makes for
 * its own native-style controls. Every value is read from and written to
 * {@link OC3DSDDialogModel}, so the validation and the macro round-trip are
 * testable without a display.
 */
public final class OC3DSDDialog {

    private final OC3DSDDialogModel model;
    private final ImagePlus image;

    public OC3DSDDialog(OC3DSDDialogModel model, ImagePlus image) {
        this.model = model;
        this.image = image;
    }

    /**
     * Shows the dialog.
     *
     * @return true when the user pressed OK and the settings validate
     */
    public boolean show() {
        model.configureForImage(image);
        while (true) {
            int action=showMain();
            if(action==0)return false;
            if(action==1)return true;
            List<String> errors=model.validate();
            if(!errors.isEmpty()){ij.IJ.error("StarDist parameter tuning",String.join("\n",errors));continue;}
            OC3DSDDialogModel chosen=sc.fiji.oc3dsd.ui.tuning.StarDistTuner.show(image,model.snapshot());
            if(chosen!=null)model.copyFrom(chosen);
        }
    }

    /** 0: cancel, 1: run, 2: tune; keeps every existing control in its original order. */
    private int showMain() {

        String unit = OC3DSDDialogModel.linkingUnit(image);
        boolean calibrated = !"pixel".equals(unit);

        GenericDialog gd = new GenericDialog("3D Objects Counter - StarDist");

        // ---- Input --------------------------------------------------
        gd.addMessage("Input");
        int channels = image == null ? 1 : Math.max(1, image.getNChannels());
        if (channels > 1) {
            gd.addNumericField("Channel", model.channel, 0);
        }
        List<String> redirects = redirectChoices();
        gd.addChoice("Redirect intensities from",
                redirects.toArray(new String[redirects.size()]),
                redirects.contains(model.redirectTitle) ? model.redirectTitle : redirects.get(0));
        gd.addMessage("'None' measures intensities on this image, as in 3D Objects Counter.\n"
                + "Choose another image only to measure a different channel or stack.");

        // ---- Detection: this replaces 3D Objects Counter+'s threshold --
        gd.addMessage(model.is3d() ? "Detection  (StarDist3D - whole volume)" : "Detection  (StarDist runs on each Z-slice)");
        StarDistModelSelector modelSelector = new StarDistModelSelector(gd, model);
        if (!model.is3d()) gd.addMessage("Default: versatile fluorescence nuclei model.\n"
                + "DSB 2018: Data Science Bowl nuclei benchmark.");
        if(model.is3d()) gd.addMessage("Choose a compatible trained 3D model folder.\nPython setup, training and mode selection: StarDist Options...");
        gd.addNumericField("Probability", model.probability, 2);
        gd.addNumericField("Overlap (NMS)", model.overlap, 2);

        gd.addMessage("Linking through Z  (distances in " + unit + ")");
        gd.addNumericField("Linking max distance", model.linkingDistance, 2);
        gd.addNumericField("Gap closing max distance", model.gapDistance, 2);
        gd.addNumericField("Max slice gap", model.sliceGap, 0);
        gd.addNumericField("Min. slices per object", model.minSlices, 0);
        // The linking distance is read by TrackMate in calibrated units, so the
        // same number means different things on differently calibrated stacks.
        // Say what it means here rather than letting the user discover it from
        // a merged object.
        if (calibrated) {
            gd.addMessage("At this image's calibration, "
                    + model.linkingDistance + " " + unit + " is "
                    + round2(model.linkingDistanceInPixels(image)) + " pixels.");
        } else {
            gd.addMessage("This image is not spatially calibrated, so the distances above\n"
                    + "are in pixels. Objects will also have no calibrated volume.");
        }

        // ---- Filters and Output --------------------------------------
        gd.addMessage("Filters");
        gd.addNumericField("Min size (voxels)", model.minSize, 0);
        gd.addStringField("Max size (voxels)",
                model.maxSize == Integer.MAX_VALUE ? "Infinity" : Integer.toString(model.maxSize), 12);
        gd.addCheckbox("Exclude objects on edges", model.excludeOnEdges);

        gd.addMessage("Output");
        gd.addCheckbox("Objects map", model.showLabels);
        gd.addCheckbox("Surfaces map", model.showSurfaces);
        gd.addCheckbox("Centroids map", model.showCentroids);
        gd.addCheckbox("Centres of mass map", model.showCentersOfMass);
        gd.addCheckbox("Statistics table", model.showStats);
        gd.addCheckbox("Summary", model.showSummary);
        gd.addCheckbox("Keep 3D label image", model.saveLabels);

        StarDistOptionsSession.disableUnusedLinking(gd,model);
        gd.enableYesNoCancel("OK", "Tune parameters...");
        gd.showDialog();
        if (gd.wasCanceled()) return 0;

        // ---- Read back ----------------------------------------------
        if (channels > 1) {
            model.channel = (int) gd.getNextNumber();
        }
        String redirect = gd.getNextChoice();
        model.redirectTitle = "None".equals(redirect) ? "" : redirect;

        modelSelector.read(gd, model);
        model.probability = gd.getNextNumber();
        model.overlap = gd.getNextNumber();
        model.linkingDistance = gd.getNextNumber();
        model.gapDistance = gd.getNextNumber();
        model.sliceGap = (int) gd.getNextNumber();
        model.minSlices = (int) gd.getNextNumber();

        model.minSize = (int) gd.getNextNumber();
        model.maxSize = parseMaxSize(gd.getNextString());
        model.excludeOnEdges = gd.getNextBoolean();

        model.showLabels = gd.getNextBoolean();
        model.showSurfaces = gd.getNextBoolean();
        model.showCentroids = gd.getNextBoolean();
        model.showCentersOfMass = gd.getNextBoolean();
        model.showStats = gd.getNextBoolean();
        model.showSummary = gd.getNextBoolean();
        model.saveLabels = gd.getNextBoolean();

        return gd.wasOKed() ? 1 : 2;
    }

    /**
     * Titles of open images that could serve as the intensity source.
     * <p>
     * The analysed image is deliberately absent: "None" already measures it,
     * and offering it by title as well would invite picking it raw — a
     * multi-channel hyperstack has more slices than the label image and would
     * be rejected on dimensions.
     */
    private List<String> redirectChoices() {
        List<String> titles = new ArrayList<String>();
        titles.add("None");
        int[] ids = WindowManager.getIDList();
        if (ids != null) {
            for (int id : ids) {
                ImagePlus candidate = WindowManager.getImage(id);
                if (candidate == null) continue;
                if (image != null && candidate.getID() == image.getID()) continue;
                titles.add(candidate.getTitle());
            }
        }
        return titles;
    }

    static int parseMaxSize(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty() || "infinity".equalsIgnoreCase(value) || "inf".equalsIgnoreCase(value)) {
            return Integer.MAX_VALUE;
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
            return (int) Math.max(0, parsed);
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException(
                    "Max size must be a whole number or Infinity (value='" + text + "').", nfe);
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
