package sc.fiji.oc3dsd.equivalence;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The one systematic non-measurement change made by the visible-mask fix. */
final class MapDisplayRangeChange {

    private static final String PREFIX = "displayRange=0.0,";
    private static final String VISIBLE_MASK_RANGE = "displayRange=0.0,1.0";
    private static final Set<String> MAP_SECTIONS = new HashSet<String>(Arrays.asList(
            "objectMap", "surfaceMap", "centroidMap", "centreOfMassMap"));

    private MapDisplayRangeChange() {
    }

    static boolean accepts(String section, String golden, String candidate) {
        if (!MAP_SECTIONS.contains(section)
                || !VISIBLE_MASK_RANGE.equals(candidate)
                || golden == null
                || !golden.startsWith(PREFIX)) {
            return false;
        }
        try {
            return Double.parseDouble(golden.substring(PREFIX.length())) > 1.0;
        } catch (NumberFormatException notARange) {
            return false;
        }
    }

    static boolean differsOnlyByThisChange(String section,
                                           List<String> golden,
                                           List<String> candidate) {
        if (golden == null || candidate == null || golden.size() != candidate.size()) {
            return false;
        }
        boolean found = false;
        for (int index = 0; index < golden.size(); index++) {
            String before = golden.get(index);
            String after = candidate.get(index);
            if (before.equals(after)) continue;
            if (!accepts(section, before, after)) return false;
            found = true;
        }
        return found;
    }
}
