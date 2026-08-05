package sc.fiji.oc3dsd.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A single morphology filter: {@code feature OP value}, where OP is one of
 * {@code >=}, {@code <=}, {@code >}, {@code <}.
 *
 * <p>Use the lowercase feature names documented here. Supported feature
 * names: {@code volume}, {@code volume_calibrated}, {@code surface_area},
 * {@code sphericity}, {@code elongation}, {@code compactness},
 * {@code mean_intensity}, {@code max_intensity}, {@code feret_diameter_max}.
 *
 * <p><b>Unknown feature names always match.</b> That is deliberate: a macro
 * written against a build that computes an extra measurement must not silently
 * discard every object when run against one that does not. The engine reports it
 * through {@link OC3DSDParameters.WarningSink} instead.
 *
 * <p><b>Why this class is not adopted from {@code oc3d-core}.</b> Core's
 * equivalent is behaviourally identical for every predicate this plugin builds —
 * the same nine base features, the same four operators, the same
 * {@code matches} — and adds a feature registry this plugin has nothing to
 * register. But this type appears in the documented public API, on
 * {@link OC3DSD.Builder#addFilter} and {@link OC3DSDParameters#morphPredicates},
 * and core's package is relocated when the jar is shaded. Adopting it would put
 * a relocated internal type into a public signature, which is the thing the
 * "do not relocate {@code sc.fiji.oc3dsd.api}" rule exists to prevent. It stays
 * here so callers keep a stable import.
 */
public final class MorphPredicate {

    public enum Operator {
        GE(">="),
        LE("<="),
        GT(">"),
        LT("<");

        private final String symbol;

        Operator(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }
    }

    private static final Set<String> SUPPORTED_FEATURES = new HashSet<String>(Arrays.asList(
            "volume",
            "volume_calibrated",
            "surface_area",
            "sphericity",
            "elongation",
            "compactness",
            "mean_intensity",
            "max_intensity",
            "feret_diameter_max"));

    // 3D Objects Counter+ adds its extended-measurement feature names here.
    // This plugin ships the base feature set only in v0.1.0; extended
    // measurements are a later version and would add their names the same way.

    public final String featureName;
    public final Operator op;
    public final double value;

    public MorphPredicate(String featureName, Operator op, double value) {
        if (featureName == null) {
            throw new IllegalArgumentException("Morph predicate featureName must not be null (featureName=null).");
        }
        if (featureName.trim().isEmpty()) {
            throw new IllegalArgumentException("Morph predicate featureName must not be blank (featureName='"
                    + featureName + "').");
        }
        if (op == null) {
            throw new IllegalArgumentException("Morph predicate op must not be null (op=null).");
        }
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Morph predicate value must be finite (value="
                    + value + ").");
        }
        this.featureName = featureName.trim();
        this.op = op;
        this.value = value;
    }

    public boolean matches(double observed) {
        if (!SUPPORTED_FEATURES.contains(featureName)) return true;
        if (!Double.isFinite(observed)) return false;
        if (op == Operator.GE) return observed >= value;
        if (op == Operator.LE) return observed <= value;
        if (op == Operator.GT) return observed > value;
        if (op == Operator.LT) return observed < value;
        return false;
    }

    public String format() {
        return featureName + op.symbol() + Double.toString(value);
    }

    /**
     * Parses a single predicate string such as {@code "sphericity>=0.6"}.
     * Whitespace around the feature, operator, or value is allowed for Java
     * callers, but direct macro filter tokens such as
     * {@code sphericity>=0.6} must still be one whitespace-free token.
     */
    public static MorphPredicate parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Morph predicate text must not be null (text=null).");
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Morph predicate text must not be blank (text='"
                    + text + "').");
        }
        String[] operators = {">=", "<=", ">", "<"};
        Operator[] values = {Operator.GE, Operator.LE, Operator.GT, Operator.LT};
        for (int i = 0; i < operators.length; i++) {
            int at = trimmed.indexOf(operators[i]);
            if (at > 0) {
                String feature = trimmed.substring(0, at).trim();
                String rawValue = trimmed.substring(at + operators[i].length()).trim();
                try {
                    double parsedValue = Double.parseDouble(rawValue);
                    try {
                        return new MorphPredicate(feature, values[i], parsedValue);
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("Invalid morph predicate (text='"
                                + text + "', featureName='" + feature + "', operator='"
                                + operators[i] + "', value='" + rawValue + "'): "
                                + e.getMessage(), e);
                    }
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid morph predicate value (text='"
                            + text + "', value='" + rawValue + "').", e);
                }
            }
        }
        throw new IllegalArgumentException("Invalid morph predicate (text='" + text
                + "'; expected feature>=value, feature<=value, feature>value, or feature<value).");
    }

    /** Parses a comma-separated predicate list. Blank entries are skipped. */
    public static List<MorphPredicate> parseList(String decoded) {
        List<MorphPredicate> predicates = new ArrayList<MorphPredicate>();
        if (decoded == null || decoded.trim().isEmpty()) return predicates;
        String[] parts = decoded.split(",", -1);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i] == null ? "" : parts[i].trim();
            if (!part.isEmpty()) {
                predicates.add(parse(part));
            }
        }
        return predicates;
    }

    /** Formats a list as a comma-separated string parseable by {@link #parseList}. */
    public static String formatList(List<MorphPredicate> predicates) {
        if (predicates == null || predicates.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < predicates.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(predicates.get(i).format());
        }
        return sb.toString();
    }
}

