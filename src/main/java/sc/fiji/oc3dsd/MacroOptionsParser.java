package sc.fiji.oc3dsd;

import sc.fiji.oc3dsd.api.MorphPredicate;
import sc.fiji.oc3dsd.ui.OC3DSDDialogModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parses the options string passed to {@code run("3D Objects Counter+", "...")}
 * into a {@link Parsed} value object. Pure string handling — no ImageJ types
 * so the parser is unit-testable without Fiji.
 *
 * <p>Grammar (whitespace-separated tokens):
 * <ul>
 *   <li>{@code threshold=<int>} — intensity cutoff, default 0.</li>
 *   <li>{@code min=<int>} — minimum object voxel count, default 10.</li>
 *   <li>{@code max=<int|Infinity>} — maximum object voxel count, default Infinity.</li>
 *   <li>{@code exclude_edges} — flag, exclude objects touching image borders.</li>
 *   <li>{@code redirect=[image title]} — optional intensity-measurement source.</li>
 *   <li>{@code sphericity>=0.6}, {@code volume>=100}, ... - direct filter predicates.</li>
 *   <li>{@code hide_labels} - flag, suppress the object label map (default is to show it).</li>
 *   <li>{@code hide_surfaces} - flag, suppress the surface map.</li>
 *   <li>{@code hide_centroids} - flag, suppress the centroid map.</li>
 *   <li>{@code hide_centers_of_mass} - flag, suppress the center-of-mass map.</li>
 *   <li>{@code hide_stats} - flag, suppress the ResultsTable (default is to show it).</li>
 *   <li>{@code hide_summary} - flag, suppress the ImageJ log summary.</li>
 * </ul>
 *
 * <p>Tokens that overlap with the native 3D Objects Counter
 * ({@code threshold}, {@code min}, {@code max}, {@code exclude_edges},
 * {@code redirect}) keep the same names. Plus filters are direct feature
 * predicates, not indexed {@code filter1=} options.
 */
public final class MacroOptionsParser {

    private MacroOptionsParser() {}

    public static final int MAX_FILTERS = 64;
    private static final String[] FILTER_FEATURES = {
            "feret_diameter_max",
            "volume_calibrated",
            "mean_intensity",
            "max_intensity",
            "surface_area",
            "compactness",
            "sphericity",
            "elongation",
            "volume",
            "fractal_dim_xy",
            "fractal_r2_xy",
            "lacunarity_mean_xy",
            "lacunarity_spread_xy",
            "sholl_critical_radius_um",
            "sholl_critical_intersections",
            "sholl_schoenen_index",
            "sholl_primary_branches",
            "skeleton_branches",
            "skeleton_junctions",
            "skeleton_endpoints",
            "skeleton_voxels",
            "ri",
            "sri",
            "pb",
            "mp",
            "vsd"
    };

    /**
     * Applies a macro-options string to a dialog model.
     * <p>
     * Every option 3D Objects Counter+ understands keeps its meaning here, so a
     * user's existing filter and output options transfer unchanged. The
     * detection options replace {@code threshold}, which has nothing to act on
     * in a plugin whose objects come from a detector.
     */
    public static OC3DSDDialogModel parse(String optionsString) {
        String opts = optionsString == null ? "" : optionsString.trim();
        OC3DSDDialogModel model = new OC3DSDDialogModel();

        // Detection.
        model.channel = parseIntOption(getValue(opts, "channel", null), 1, "channel");
        String model_ = getBracketed(opts, "model", null);
        if (model_ == null) model_ = getValue(opts, "model", null);
        if (model_ != null && !model_.trim().isEmpty()) model.modelRef = model_.trim();
        model.probability = parseDoubleOption(getValue(opts, "probability", null), 0.5, "probability");
        model.overlap = parseDoubleOption(getValue(opts, "overlap", null), 0.4, "overlap");
        model.linkingDistance = parseDoubleOption(
                getValue(opts, "linking_distance", null), 5.0, "linking_distance");
        model.gapDistance = parseDoubleOption(
                getValue(opts, "gap_distance", null), 5.0, "gap_distance");
        model.sliceGap = parseIntOption(getValue(opts, "slice_gap", null), 1, "slice_gap");
        model.minSlices = parseIntOption(getValue(opts, "min_slices", null), 1, "min_slices");

        // Everything below is 3D Objects Counter+'s vocabulary, unchanged.
        model.minSize = parseIntOption(getValue(opts, "min", null), 10, "min");
        model.maxSize = parseMaxSize(getValue(opts, "max", null));
        model.excludeOnEdges = hasFlag(opts, "exclude_edges");
        model.showLabels = !hasFlag(opts, "hide_labels");
        model.showSurfaces = !hasFlag(opts, "hide_surfaces");
        model.showCentroids = !hasFlag(opts, "hide_centroids");
        // Both spellings accepted, as in 3D Objects Counter+.
        model.showCentersOfMass = !hasFlag(opts, "hide_centers_of_mass")
                && !hasFlag(opts, "hide_centres_of_mass");
        model.showStats = !hasFlag(opts, "hide_stats");
        model.showSummary = !hasFlag(opts, "hide_summary");
        model.saveLabels = hasFlag(opts, "save_labels");
        String redirect = getBracketed(opts, "redirect", null);
        model.redirectTitle = redirect == null ? "" : redirect;

        for (MorphPredicate predicate : parseDirectPredicates(opts)) {
            model.addFilter(new OC3DSDDialogModel.FilterRow(
                    predicate.featureName, predicate.op.symbol(), predicate.value, true));
        }
        return model;
    }

    /** True when the caller asked for no windows (headless and scripted use). */
    public static boolean isHidden(String optionsString) {
        return hasFlag(optionsString == null ? "" : optionsString, "hide_display");
    }

    private static double parseDoubleOption(String token, double fallback, String optionName) {
        if (token == null || token.trim().isEmpty()) return fallback;
        try {
            double parsed = Double.parseDouble(token.trim());
            if (Double.isNaN(parsed)) {
                throw new IllegalArgumentException(
                        "Macro option '" + optionName + "' must not be NaN (" + optionName + "=" + token + ").");
            }
            return parsed;
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException(
                    "Macro option '" + optionName + "' must be a number ("
                            + optionName + "='" + token + "').", nfe);
        }
    }

    public static String requireSafeBracketedValue(String value, String fieldName) {
        String label = fieldName == null || fieldName.trim().isEmpty()
                ? "Macro bracket value" : fieldName;
        if (value == null) {
            throw new IllegalArgumentException(label
                    + " must not be null (" + label + "=null).");
        }
        if (!isSafeBracketedValue(value)) {
            throw new IllegalArgumentException(label
                    + " cannot contain [, ], quotes, backslashes, or line breaks in macro options "
                    + "(" + label + "='" + value + "'). "
                    + "Rename the image and try again.");
        }
        return value;
    }

    public static boolean isSafeBracketedValue(String value) {
        if (value == null) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '[' || c == ']' || c == '"' || c == '\\' || Character.isISOControl(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the value of {@code key=value} in {@code options}, or
     * {@code defaultValue} if absent. Stops at the next space.
     */
    static String getValue(String options, String key, String defaultValue) {
        if (options == null || key == null) return defaultValue;
        String marker = key + "=";
        int at = findToken(options, marker);
        if (at < 0) return defaultValue;
        int start = at + marker.length();
        // Bracketed value is handled by getBracketed; here we stop at whitespace.
        if (start < options.length() && options.charAt(start) == '[') {
            return defaultValue;
        }
        int end = start;
        while (end < options.length() && !Character.isWhitespace(options.charAt(end))) {
            end++;
        }
        return options.substring(start, end);
    }

    /**
     * Returns the value of {@code key=[bracketed content]} in {@code options},
     * preserving spaces inside the brackets; {@code defaultValue} if absent or
     * malformed. Nested bracket pairs are allowed, but there is no escape
     * syntax for a literal unmatched closing bracket.
     */
    static String getBracketed(String options, String key, String defaultValue) {
        if (options == null || key == null) return defaultValue;
        String marker = key + "=[";
        int at = findToken(options, marker);
        if (at < 0) return defaultValue;
        int start = at + marker.length();
        int depth = 1;
        for (int i = start; i < options.length(); i++) {
            char c = options.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return options.substring(start, i);
                }
            }
        }
        return defaultValue;
    }

    /**
     * Returns true if {@code flag} appears as a whitespace-separated token in
     * {@code options} (not as a prefix of another key).
     */
    static boolean hasFlag(String options, String flag) {
        if (options == null || flag == null) return false;
        int at = findToken(options, flag);
        if (at < 0) return false;
        int after = at + flag.length();
        // Reject `flag=value` and `flag1` cases.
        if (after < options.length()) {
            char c = options.charAt(after);
            if (c == '=' || !Character.isWhitespace(c)) return false;
        }
        return true;
    }

    /**
     * Find {@code needle} in {@code options} at a position that is either the
     * start of the string or preceded by whitespace. This prevents
     * {@code min=10} from matching when searching for the key {@code n}.
     */
    private static int findToken(String options, String needle) {
        int depth = 0;
        for (int i = 0; i <= options.length() - needle.length(); i++) {
            char c = options.charAt(i);
            if (depth == 0
                    && options.startsWith(needle, i)
                    && (i == 0 || Character.isWhitespace(options.charAt(i - 1)))) {
                return i;
            }
            if (c == '[') {
                depth++;
            } else if (c == ']' && depth > 0) {
                depth--;
            }
        }
        return -1;
    }

    private static int parseIntOption(String token, int fallback, String optionName) {
        if (token == null) return fallback;
        String trimmed = token.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Macro option '" + optionName
                    + "' must not be blank (" + optionName + "='" + token + "').");
        }
        try {
            double parsed = Double.parseDouble(trimmed);
            if (!Double.isFinite(parsed)) {
                throw new IllegalArgumentException("Macro option '" + optionName
                        + "' must be finite (" + optionName + "='" + token + "').");
            }
            if (parsed <= 0) return 0;
            if (parsed >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
            return (int) Math.round(parsed);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Macro option '" + optionName
                    + "' has invalid numeric value (" + optionName + "='" + token + "').", e);
        }
    }

    private static int parseMaxSize(String token) {
        if (token == null) return Integer.MAX_VALUE;
        String t = token.trim();
        if (t.isEmpty()) {
            throw new IllegalArgumentException("Macro option 'max' must not be blank (max='" + token + "').");
        }
        if ("infinity".equalsIgnoreCase(t)
                || "inf".equalsIgnoreCase(t)) {
            return Integer.MAX_VALUE;
        }
        return parseIntOption(t, Integer.MAX_VALUE, "max");
    }

    private static List<MorphPredicate> parseDirectPredicates(String options) {
        List<MorphPredicate> predicates = new ArrayList<MorphPredicate>();
        List<String> tokens = tokensOutsideBrackets(options);
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            rejectIndexedFilterToken(token);
            MorphPredicate predicate = directPredicateFromToken(token);
            if (predicate == null) continue;
            if (predicates.size() >= MAX_FILTERS) {
                throw new IllegalArgumentException("Too many direct filter predicates in macro options "
                        + "(maximum " + MAX_FILTERS + ").");
            }
            predicates.add(predicate);
        }
        return predicates;
    }

    private static MorphPredicate directPredicateFromToken(String token) {
        if (token == null || token.isEmpty()) return null;
        if (token.startsWith("redirect=[")) return null;
        for (int i = 0; i < FILTER_FEATURES.length; i++) {
            String feature = FILTER_FEATURES[i];
            if (!token.startsWith(feature)) continue;
            String suffix = token.substring(feature.length());
            if (suffix.startsWith(">=") || suffix.startsWith("<=")
                    || suffix.startsWith(">") || suffix.startsWith("<")) {
                return parsePredicate(token, token);
            }
            if (suffix.startsWith("=")) {
                throw new IllegalArgumentException("Macro filter '" + token
                        + "' is invalid; use feature>=value, feature<=value, "
                        + "feature>value, or feature<value.");
            }
        }
        if (looksLikePredicate(token)) {
            throw new IllegalArgumentException("Unknown macro filter feature in '" + token
                    + "'. Supported features: " + supportedFeatureList() + ".");
        }
        return null;
    }

    private static boolean looksLikePredicate(String token) {
        if (token == null) return false;
        return token.contains(">=") || token.contains("<=")
                || token.indexOf('>') >= 0 || token.indexOf('<') >= 0;
    }

    private static String supportedFeatureList() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < FILTER_FEATURES.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(FILTER_FEATURES[i]);
        }
        return sb.toString();
    }

    private static void rejectIndexedFilterToken(String token) {
        if (token == null || !token.startsWith("filter")) return;
        int i = "filter".length();
        while (i < token.length() && Character.isDigit(token.charAt(i))) {
            i++;
        }
        if (i > "filter".length() && i < token.length() && token.charAt(i) == '=') {
            throw new IllegalArgumentException("Macro option '" + token.substring(0, i)
                    + "' is no longer supported; use direct filter syntax such as "
                    + "'sphericity>=0.6'.");
        }
    }

    private static List<String> tokensOutsideBrackets(String options) {
        List<String> tokens = new ArrayList<String>();
        if (options == null || options.isEmpty()) return tokens;
        StringBuilder token = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < options.length(); i++) {
            char c = options.charAt(i);
            if (Character.isWhitespace(c) && depth == 0) {
                addToken(tokens, token);
                continue;
            }
            token.append(c);
            if (c == '[') {
                depth++;
            } else if (c == ']' && depth > 0) {
                depth--;
            }
        }
        addToken(tokens, token);
        return tokens;
    }

    private static void addToken(List<String> tokens, StringBuilder token) {
        if (token == null || token.length() == 0) return;
        tokens.add(token.toString());
        token.setLength(0);
    }

    private static MorphPredicate parsePredicate(String key, String value) {
        try {
            return MorphPredicate.parse(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Macro option '" + key
                    + "' has invalid morph predicate (" + key + "='" + value + "'): "
                    + e.getMessage(), e);
        }
    }
}
