package sc.fiji.oc3dsd.equivalence;

import java.util.ArrayList;
import java.util.List;

/**
 * Differences from the goldens that have been looked at and accepted.
 * <p>
 * Goldens are immutable (harness §7): a wrong golden is a bug report against the
 * shipped plugin, fixed as its own change, never regenerated to make a diff go
 * away. But a golden can be a <em>correct</em> record of behaviour that then
 * changes on purpose — a reworded message, a deliberately different ordering.
 * Superseding the golden would put a hole in the immutability rule; leaving the
 * harness red would train everyone to ignore it.
 * <p>
 * So the golden stays exactly as captured and the accepted change is recorded
 * here, next to the reason for it.
 * <p>
 * <strong>This mechanism is deliberately hard to abuse.</strong>
 * <ul>
 *   <li>An entry matches one run, one section, and the exact pair of lines. It
 *       cannot suppress a section, a column or a fixture.</li>
 *   <li>Every entry must actually fire. A registered entry that matches nothing
 *       fails the build — see {@code registerIsNotStale}. Otherwise entries
 *       accumulate and quietly widen into a blanket exemption.</li>
 *   <li>Accepted differences are printed on every run, so they stay visible
 *       rather than becoming invisible once written down.</li>
 *   <li>Nothing here can touch a measurement column. Tier 1, 2 and 3 differences
 *       come out of the numeric table comparison, which never consults this
 *       class. A number that moves is always a failure.</li>
 * </ul>
 */
final class AcceptedDifferences {

    static final class Entry {
        final String run;
        final String section;
        final String golden;
        final String candidate;
        final String reason;

        Entry(String run, String section, String golden, String candidate, String reason) {
            this.run = run;
            this.section = section;
            this.golden = golden;
            this.candidate = candidate;
            this.reason = reason;
        }

        boolean matches(String run, String section, String golden, String candidate) {
            return this.run.equals(run)
                    && this.section.equals(section)
                    && this.golden.equals(golden)
                    && this.candidate.equals(candidate);
        }

        @Override
        public String toString() {
            return run + " [" + section + "] " + reason;
        }
    }

    private static final List<Entry> ENTRIES = new ArrayList<Entry>();

    static {
        // Stage 02c. Adopting core's MacroFilters for the direct-predicate
        // grammar. Every one of the fourteen user-visible message strings is
        // byte-identical between the two implementations; what differs is the
        // order of the feature list interpolated into this one message. This
        // plugin listed them in declaration order, core sorts them
        // alphabetically.
        //
        // Accepted rather than preserved: a user reading "which names may I
        // use?" is better served by an alphabetical list, the parsed result is
        // identical either way, and the alternative is keeping a duplicate
        // parser alive to protect the ordering of an error message. CHANGELOG
        // entry accompanies this.
        String features = "compactness, elongation, feret_diameter_max, fractal_dim_xy, "
                + "fractal_r2_xy, lacunarity_mean_xy, lacunarity_spread_xy, max_intensity, "
                + "mean_intensity, mp, pb, ri, sholl_critical_intersections, "
                + "sholl_critical_radius_um, sholl_primary_branches, sholl_schoenen_index, "
                + "skeleton_branches, skeleton_endpoints, skeleton_junctions, skeleton_voxels, "
                + "sphericity, sri, surface_area, volume, volume_calibrated, vsd";
        String legacy = "feret_diameter_max, volume_calibrated, mean_intensity, max_intensity, "
                + "surface_area, compactness, sphericity, elongation, volume, fractal_dim_xy, "
                + "fractal_r2_xy, lacunarity_mean_xy, lacunarity_spread_xy, "
                + "sholl_critical_radius_um, sholl_critical_intersections, sholl_schoenen_index, "
                + "sholl_primary_branches, skeleton_branches, skeleton_junctions, "
                + "skeleton_endpoints, skeleton_voxels, ri, sri, pb, mp, vsd";
        String prefix = "parse=java.lang.IllegalArgumentException: "
                + "Unknown macro filter feature in 'no_such_feature>=1'. Supported features: ";
        ENTRIES.add(new Entry(
                "macro_roundtrip",
                "options=no_such_feature>=1",
                prefix + legacy + ".",
                prefix + features + ".",
                "Core lists the supported features alphabetically; this plugin listed them "
                        + "in declaration order. Same features, same parse result, same "
                        + "exception type - only the order of the list in the message text."));
    }

    private AcceptedDifferences() {
    }

    static List<Entry> all() {
        return new ArrayList<Entry>(ENTRIES);
    }

    /** The entry accepting this difference, or null when it is a real one. */
    static Entry find(String run, String section, String golden, String candidate) {
        for (int i = 0; i < ENTRIES.size(); i++) {
            Entry entry = ENTRIES.get(i);
            if (entry.matches(run, section, golden, candidate)) return entry;
        }
        return null;
    }
}
