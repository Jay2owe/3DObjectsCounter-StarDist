package sc.fiji.oc3dsd.ui.tuning;

import sc.fiji.oc3dsd.ui.OC3DSDDialogModel;
import sc.fiji.oc3dsd.internal.flash.ui.variations.ParameterKey;

/** Numeric controls already exposed by the counter; no new analysis semantics. */
public enum TuningParameter implements ParameterKey {
    PROBABILITY("Probability", false, 0, 1),
    OVERLAP("Overlap (NMS)", false, 0, 1),
    LINKING("Linking max distance", false, 0, Double.MAX_VALUE),
    GAP_DISTANCE("Gap closing max distance", false, 0, Double.MAX_VALUE),
    SLICE_GAP("Max slice gap", true, 0, Integer.MAX_VALUE),
    MIN_SLICES("Min. slices per object", true, 1, Integer.MAX_VALUE),
    MIN_SIZE("Min size (voxels)", true, 0, Integer.MAX_VALUE),
    MAX_SIZE("Max size (voxels)", true, 0, Integer.MAX_VALUE);

    public final boolean integer;
    private final String label;
    private final double min, max;
    TuningParameter(String label, boolean integer, double min, double max) {
        this.label=label; this.integer=integer; this.min=min; this.max=max;
    }
    public String stableKey() { return name().toLowerCase(java.util.Locale.ROOT); }
    public String displayLabel() { return label; }
    public ValueKind valueKind() { return ValueKind.NUMBER; }
    public boolean appliesTo(OC3DSDDialogModel model) {
        return !model.is3d() || !(this==LINKING || this==GAP_DISTANCE || this==SLICE_GAP || this==MIN_SLICES);
    }
    public Number checked(double value) {
        if (!Double.isFinite(value) || value<min || value>max || (integer && value!=Math.rint(value)))
            throw new IllegalArgumentException(label+" needs "+(integer?"whole numbers":"finite values")+" between "+min+" and "+max+".");
        if (integer) return Integer.valueOf((int)value);
        return Double.valueOf(value);
    }
    public double read(OC3DSDDialogModel m) {
        switch(this) {
            case PROBABILITY:return m.probability; case OVERLAP:return m.overlap;
            case LINKING:return m.linkingDistance; case GAP_DISTANCE:return m.gapDistance;
            case SLICE_GAP:return m.sliceGap; case MIN_SLICES:return m.minSlices;
            case MIN_SIZE:return m.minSize; default:return m.maxSize;
        }
    }
    public void apply(OC3DSDDialogModel m, Number value) {
        Number v=checked(value.doubleValue());
        switch(this) {
            case PROBABILITY:m.probability=v.doubleValue();break;
            case OVERLAP:m.overlap=v.doubleValue();break;
            case LINKING:m.linkingDistance=v.doubleValue();break;
            case GAP_DISTANCE:m.gapDistance=v.doubleValue();break;
            case SLICE_GAP:m.sliceGap=v.intValue();break;
            case MIN_SLICES:m.minSlices=v.intValue();break;
            case MIN_SIZE:m.minSize=v.intValue();break;
            case MAX_SIZE:m.maxSize=v.intValue();break;
        }
    }
}
