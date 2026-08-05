package sc.fiji.oc3dsd.ui;

import ij.ImagePlus;
import sc.fiji.oc3d.core.ui.DialogModel;
import sc.fiji.oc3dsd.MacroOptionsParser;
import sc.fiji.oc3dsd.api.OC3DSD;
import sc.fiji.oc3dsd.api.OC3DSDParameters;
import sc.fiji.oc3dsd.engine.StarDistLinkingParams;
import sc.fiji.oc3dsd.runtime.ModelResolver;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mutable, Swing-free data model for the dialog. Holds the user's selections,
 * validates them, and produces an {@link OC3DSDParameters} or the equivalent
 * macro-options string. {@link OC3DSDDialog} always reads and writes through
 * this model, so the logic stays unit-testable without a display.
 * <p>
 * The shape is 3D Objects Counter+'s, deliberately: the filter rows, the size
 * bounds, the output toggles and every shared macro key mean exactly what they
 * mean there — and now they <em>are</em> the same code, inherited from
 * {@code oc3d-core}'s {@link DialogModel}. The one section that differs is
 * detection, where a threshold is replaced by a model plus the detection and
 * linking settings, and that is what this subclass adds.
 * <p>
 * The split is the one core was built for: {@link #appendEngineMacroOptions}
 * puts the detection options at the front of a recorded macro,
 * {@link #validateAdditional} adds the checks for them, and
 * {@link #copyAdditionalFrom} carries them through a snapshot. Nothing about the
 * detection section had to be pushed into core to make it fit.
 */
public final class OC3DSDDialogModel extends DialogModel {

    // ---- Detection: the section that replaces the threshold -------------
    /** 1-based channel to detect on. */
    public int channel = 1;
    /** {@link ModelResolver#BUNDLED_MODEL_KEY} or a path to a {@code .zip}. */
    public String modelRef = ModelResolver.BUNDLED_MODEL_KEY;
    public double probability = 0.5;
    public double overlap = 0.4;
    /** Calibrated units — see defect D5; the unit is what makes this dangerous. */
    public double linkingDistance = 5.0;
    public double gapDistance = 5.0;
    public int sliceGap = 1;
    /** 1 keeps single-slice objects. Raising it discards them; they are counted either way. */
    public int minSlices = 1;

    /** Keep the 3D label image as an output. Not a core concept. */
    public boolean saveLabels = false;

    // ---- Extension points ------------------------------------------------

    @Override
    protected void appendEngineMacroOptions(StringBuilder options) {
        append(options, "channel=" + channel);
        if (modelRef == null || modelRef.isEmpty()
                || ModelResolver.BUNDLED_MODEL_KEY.equalsIgnoreCase(modelRef)) {
            append(options, "model=" + ModelResolver.BUNDLED_MODEL_KEY);
        } else {
            append(options, "model=["
                    + MacroOptionsParser.requireSafeBracketedValue(modelRef, "Model path") + "]");
        }
        append(options, "probability=" + probability);
        append(options, "overlap=" + overlap);
        append(options, "linking_distance=" + linkingDistance);
        append(options, "gap_distance=" + gapDistance);
        append(options, "slice_gap=" + sliceGap);
        append(options, "min_slices=" + minSlices);
    }

    /**
     * Adds {@code save_labels} at the very end.
     * <p>
     * Core's {@code appendExtraMacroFlags} hook writes variant flags just after
     * the size options, which would move this token in every recorded macro.
     * The order is not functionally significant — the parser is
     * order-independent — but a recorded macro is something users paste into
     * scripts and diff, so it is left exactly where it was.
     */
    @Override
    public String toMacroOptions() {
        String options = super.toMacroOptions();
        return saveLabels ? options + " save_labels" : options;
    }

    @Override
    protected List<String> validateAdditional() {
        List<String> errors = new ArrayList<String>();
        if (channel < 1) errors.add("Channel must be >= 1 (channel=" + channel + ").");
        if (!(probability >= 0.0 && probability <= 1.0)) {
            errors.add("Probability must be between 0 and 1 (probability=" + probability + ").");
        }
        if (!(overlap >= 0.0 && overlap <= 1.0)) {
            errors.add("Overlap must be between 0 and 1 (overlap=" + overlap + ").");
        }
        if (!(linkingDistance >= 0.0) || !Double.isFinite(linkingDistance)) {
            errors.add("Linking max distance must be a finite number >= 0 "
                    + "(linking_distance=" + linkingDistance + ").");
        }
        if (!(gapDistance >= 0.0) || !Double.isFinite(gapDistance)) {
            errors.add("Gap closing max distance must be a finite number >= 0 "
                    + "(gap_distance=" + gapDistance + ").");
        }
        if (sliceGap < 0) errors.add("Max slice gap must be >= 0 (slice_gap=" + sliceGap + ").");
        if (minSlices < 1) {
            errors.add("Min. slices per object must be >= 1 (min_slices=" + minSlices + ").");
        }
        if (modelRef != null && !modelRef.isEmpty()
                && !ModelResolver.BUNDLED_MODEL_KEY.equalsIgnoreCase(modelRef)) {
            String problem = ModelResolver.validate(new File(modelRef));
            if (problem != null) {
                errors.add("Model '" + modelRef + "' cannot be used: " + problem);
            }
        }
        return errors;
    }

    @Override
    protected void copyAdditionalFrom(DialogModel other) {
        if (!(other instanceof OC3DSDDialogModel)) return;
        OC3DSDDialogModel source = (OC3DSDDialogModel) other;
        this.channel = source.channel;
        this.modelRef = source.modelRef;
        this.probability = source.probability;
        this.overlap = source.overlap;
        this.linkingDistance = source.linkingDistance;
        this.gapDistance = source.gapDistance;
        this.sliceGap = source.sliceGap;
        this.minSlices = source.minSlices;
        this.saveLabels = source.saveLabels;
    }

    @Override
    protected DialogModel newInstance() {
        return new OC3DSDDialogModel();
    }

    /** Covariant so callers keep a StarDist model rather than a base one. */
    @Override
    public OC3DSDDialogModel snapshot() {
        return (OC3DSDDialogModel) super.snapshot();
    }

    // ---- StarDist-specific behaviour --------------------------------------

    /** Produces the parameter bundle the engine consumes. */
    public OC3DSDParameters toParameters(ImagePlus input,
                                         ImagePlus intensityImage,
                                         OC3DSDParameters.WarningSink warningSink) {
        OC3DSD.Builder builder = OC3DSD.builder(input)
                .channel(channel)
                .modelFile(ModelResolver.resolve(modelRef))
                .probability(probability)
                .overlap(overlap)
                .linkingDistance(linkingDistance)
                .gapDistance(gapDistance)
                .sliceGap(sliceGap)
                .minSlices(minSlices)
                .minSize(minSize)
                .maxSize(maxSize)
                .excludeOnEdges(excludeOnEdges)
                .intensityImage(intensityImage)
                .buildObjectMap(showLabels)
                .buildSurfaceMap(showSurfaces)
                .buildCentroidMap(showCentroids)
                .buildCentreOfMassMap(showCentersOfMass)
                .warningSink(warningSink);
        return builder.build();
    }

    /**
     * The linking distance restated in pixels for this image, so the dialog can
     * show what the number actually means on the data in front of the user.
     */
    public double linkingDistanceInPixels(ImagePlus image) {
        double pixelWidth = 1.0;
        if (image != null && image.getCalibration() != null) {
            pixelWidth = image.getCalibration().pixelWidth;
        }
        return StarDistLinkingParams.pixelEquivalent(linkingDistance, pixelWidth);
    }

    /** The calibrated unit in force, or "pixel" when the image is uncalibrated. */
    public static String linkingUnit(ImagePlus image) {
        String unit = calibratedVolumeUnit(image);
        return unit == null ? "pixel" : unit;
    }

    /**
     * No morphology predicates, ever. This plugin does not filter on shape.
     * <p>
     * The shared dialog model still carries the machinery, because 3D Objects
     * Counter+ uses it, and it derives predicates from per-feature ranges whose
     * text this plugin's dialog no longer offers. Left inherited, that is a
     * filter nobody can see: the fields are gone from the dialog, so a predicate
     * arriving from a default, a macro option or a later change to the shared
     * module would silently drop objects with nothing on screen to explain it.
     * <p>
     * Overridden rather than trusted, because the alternative is trusting that
     * the ranges stay at their defaults in a module this repository does not own.
     * Returning empty makes the absence a property of this plugin instead of a
     * coincidence about that one.
     */
    @Override
    public List<sc.fiji.oc3d.core.api.MorphPredicate> enabledPredicates() {
        return Collections.emptyList();
    }
}
