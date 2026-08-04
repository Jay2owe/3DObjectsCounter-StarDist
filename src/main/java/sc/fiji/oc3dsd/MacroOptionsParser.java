package sc.fiji.oc3dsd;

import sc.fiji.oc3d.core.macro.MacroFilters;
import sc.fiji.oc3d.core.macro.MacroOptions;
import sc.fiji.oc3dsd.api.MorphPredicate;
import sc.fiji.oc3dsd.ui.OC3DSDDialogModel;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Parses the options string passed to
 * {@code run("3D Objects Counter - StarDist", "...")} into an
 * {@link OC3DSDDialogModel}. Pure string handling — no ImageJ types, so the
 * parser is unit-testable without Fiji.
 *
 * <p>Grammar (whitespace-separated tokens):
 * <ul>
 *   <li>{@code channel=<int>}, {@code model=<key|path>},
 *       {@code probability=<double>}, {@code overlap=<double>} — detection.</li>
 *   <li>{@code linking_distance=}, {@code gap_distance=}, {@code slice_gap=},
 *       {@code min_slices=} — linking through Z.</li>
 *   <li>{@code min=<int>} — minimum object voxel count, default 10.</li>
 *   <li>{@code max=<int|Infinity>} — maximum object voxel count, default Infinity.</li>
 *   <li>{@code exclude_edges} — flag, exclude objects touching image borders.</li>
 *   <li>{@code redirect=[image title]} — optional intensity-measurement source.</li>
 *   <li>{@code sphericity>=0.6}, {@code volume>=100}, … — direct filter predicates.</li>
 *   <li>{@code hide_labels}, {@code hide_surfaces}, {@code hide_centroids},
 *       {@code hide_centers_of_mass}, {@code hide_stats}, {@code hide_summary} —
 *       flags suppressing an output that is otherwise shown.</li>
 *   <li>{@code save_labels}, {@code hide_display} — flags.</li>
 * </ul>
 *
 * <p>Tokens that overlap with the native 3D Objects Counter
 * ({@code min}, {@code max}, {@code exclude_edges}, {@code redirect}) keep the
 * same names. {@code threshold} is absent: this plugin's objects come from a
 * detector, so there is nothing for it to act on.
 *
 * <p><strong>The grammar itself lives in {@code oc3d-core}.</strong>
 * {@link MacroOptions} does the tokenising, bracket handling and numeric
 * parsing; {@link MacroFilters} does the direct predicates. What stays here is
 * the part that is genuinely StarDist's: which options exist, what they default
 * to, and how they map onto a dialog model that carries a detection section no
 * other variant has. The forwarding methods below are kept because callers in
 * this plugin use them, and because they are the plugin's own vocabulary even
 * when the implementation is shared.
 */
public final class MacroOptionsParser {

    private MacroOptionsParser() {}

    /** @see MacroFilters#MAX_FILTERS */
    public static final int MAX_FILTERS = MacroFilters.MAX_FILTERS;

    /**
     * Feature names accepted in a direct filter predicate.
     *
     * <p>Wider than what this version measures. The extended names —
     * {@code fractal_*}, {@code sholl_*}, {@code skeleton_*} and the composites
     * — are accepted so a macro written for 3D Objects Counter+, or for a later
     * version of this plugin, parses rather than failing. A predicate on a
     * feature that is not computed evaluates to a pass and the engine warns; see
     * {@link MorphPredicate}.
     */
    private static final List<String> FILTER_FEATURES = Collections.unmodifiableList(Arrays.asList(
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
            "vsd"));

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
        model.channel = MacroOptions.parseIntOption(
                MacroOptions.value(opts, "channel", null), 1, "channel");
        String modelRef = MacroOptions.bracketedOrValue(opts, "model", null);
        if (modelRef != null && !modelRef.trim().isEmpty()) model.modelRef = modelRef.trim();
        model.probability = MacroOptions.parseDoubleOption(
                MacroOptions.value(opts, "probability", null), 0.5, "probability");
        model.overlap = MacroOptions.parseDoubleOption(
                MacroOptions.value(opts, "overlap", null), 0.4, "overlap");
        model.linkingDistance = MacroOptions.parseDoubleOption(
                MacroOptions.value(opts, "linking_distance", null), 5.0, "linking_distance");
        model.gapDistance = MacroOptions.parseDoubleOption(
                MacroOptions.value(opts, "gap_distance", null), 5.0, "gap_distance");
        model.sliceGap = MacroOptions.parseIntOption(
                MacroOptions.value(opts, "slice_gap", null), 1, "slice_gap");
        model.minSlices = MacroOptions.parseIntOption(
                MacroOptions.value(opts, "min_slices", null), 1, "min_slices");

        // Everything below is 3D Objects Counter+'s vocabulary, unchanged.
        model.minSize = MacroOptions.parseIntOption(
                MacroOptions.value(opts, "min", null), 10, "min");
        model.maxSize = MacroOptions.parseMaxSize(MacroOptions.value(opts, "max", null));
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

        for (sc.fiji.oc3d.core.api.MorphPredicate predicate
                : MacroFilters.parse(opts, FILTER_FEATURES)) {
            model.addFilter(new OC3DSDDialogModel.FilterRow(
                    predicate.featureName, predicate.op.symbol(), predicate.value, true));
        }
        return model;
    }

    /** True when the caller asked for no windows (headless and scripted use). */
    public static boolean isHidden(String optionsString) {
        return hasFlag(optionsString == null ? "" : optionsString, "hide_display");
    }

    /** @see MacroOptions#requireSafeBracketedValue */
    public static String requireSafeBracketedValue(String value, String fieldName) {
        return MacroOptions.requireSafeBracketedValue(value, fieldName);
    }

    /** @see MacroOptions#isSafeBracketedValue */
    public static boolean isSafeBracketedValue(String value) {
        return MacroOptions.isSafeBracketedValue(value);
    }

    /** @see MacroOptions#value */
    static String getValue(String options, String key, String defaultValue) {
        return MacroOptions.value(options, key, defaultValue);
    }

    /** @see MacroOptions#bracketed */
    static String getBracketed(String options, String key, String defaultValue) {
        return MacroOptions.bracketed(options, key, defaultValue);
    }

    /** @see MacroOptions#hasFlag */
    static boolean hasFlag(String options, String flag) {
        return MacroOptions.hasFlag(options, flag);
    }
}
