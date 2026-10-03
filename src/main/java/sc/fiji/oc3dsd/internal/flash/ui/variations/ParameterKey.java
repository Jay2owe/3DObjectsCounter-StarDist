package sc.fiji.oc3dsd.internal.flash.ui.variations;

public interface ParameterKey {

    enum ValueKind {
        NUMBER,
        STRING
    }

    String stableKey();

    String displayLabel();

    ValueKind valueKind();
}
