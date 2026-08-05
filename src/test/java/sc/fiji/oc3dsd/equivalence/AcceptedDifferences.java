package sc.fiji.oc3dsd.equivalence;

import java.util.ArrayList;
import java.util.List;

/**
 * Differences from the goldens that have been looked at and accepted.
 * <p>
 * Goldens are immutable: a wrong golden is a bug report against the
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
        // Currently empty, and that is the correct state rather than an omission.
        //
        // The register held one entry from Stage 02c: adopting core's
        // MacroFilters made the "unknown macro filter feature" message list the
        // valid names alphabetically where this plugin had listed them in
        // declaration order. It described a difference between the pre-migration
        // goldens and current behaviour.
        //
        // The golden set moved to core-27col in Stage 04e, captured from current
        // behaviour, so that message is now simply what the goldens record and
        // there is no difference left to accept. The entry was removed when the
        // stale-entry guard below failed the build for it — which is the guard
        // doing its job, not an inconvenience to route around.
        //
        // The change is still user-visible relative to what shipped, and the
        // CHANGELOG remains its record. A register is for differences a run
        // still produces, not for a history of them.
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
