package sc.fiji.oc3dsd.equivalence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Applies the tier contract to a golden/candidate pair and produces the delta
 * table.
 * <p>
 * Two comparison modes, chosen by what the record contains rather than by what
 * the caller wants:
 * <ul>
 *   <li><strong>Numeric</strong>, for statistics and summary tables recorded in
 *       full. Each cell is compared under its column's declared tolerance, so a
 *       Tier 2 column may move within tolerance while a Tier 1 column may not
 *       move at all.</li>
 *   <li><strong>Exact</strong>, for everything else — counts, warnings, label
 *       partitions, map pixel digests, and any table large enough to have been
 *       recorded as a digest. Exact is the stricter reading, so falling back to
 *       it can only over-report, never under-report.</li>
 * </ul>
 */
final class Differ {

    static final class Difference {
        final String run;
        final String section;
        final Tiers.Tier tier;
        final String detail;

        Difference(String run, String section, Tiers.Tier tier, String detail) {
            this.run = run;
            this.section = section;
            this.tier = tier;
            this.detail = detail;
        }

        @Override
        public String toString() {
            return "Tier " + tierNumber() + "  " + run + " [" + section + "] " + detail;
        }

        private String tierNumber() {
            if (tier == Tiers.Tier.ONE) return "1";
            if (tier == Tiers.Tier.TWO) return "2";
            return "3";
        }
    }

    /** Per-column relative differences, for the Tier 2/3 delta table. */
    static final class Deltas {
        final List<Double> values = new ArrayList<Double>();
        int outsideTolerance;

        void add(double relative, boolean withinTolerance) {
            values.add(Double.valueOf(relative));
            if (!withinTolerance) outsideTolerance++;
        }
    }

    static final class Report {
        final List<Difference> differences = new ArrayList<Difference>();
        final Map<String, Deltas> deltasByColumn = new TreeMap<String, Deltas>();
        /** Differences matched by {@link AcceptedDifferences}, kept so they stay visible. */
        final List<AcceptedDifferences.Entry> accepted = new ArrayList<AcceptedDifferences.Entry>();

        List<Difference> ofTier(Tiers.Tier tier) {
            List<Difference> out = new ArrayList<Difference>();
            for (int i = 0; i < differences.size(); i++) {
                if (differences.get(i).tier == tier) out.add(differences.get(i));
            }
            return out;
        }

        /** Per column: min / median / p95 / max relative difference, and the count outside tolerance. */
        String deltaTable() {
            StringBuilder sb = new StringBuilder();
            sb.append("column\ttier\ttolerance\tn\tmin\tmedian\tp95\tmax\toutside\n");
            for (Map.Entry<String, Deltas> entry : deltasByColumn.entrySet()) {
                String column = entry.getKey();
                Deltas deltas = entry.getValue();
                List<Double> sorted = new ArrayList<Double>(deltas.values);
                Collections.sort(sorted);
                sb.append(column).append('\t')
                        .append(Tiers.tierOf(column)).append('\t')
                        .append(Canon.num(Tiers.relativeToleranceFor(column))).append('\t')
                        .append(sorted.size()).append('\t')
                        .append(Canon.num(quantile(sorted, 0.0))).append('\t')
                        .append(Canon.num(quantile(sorted, 0.5))).append('\t')
                        .append(Canon.num(quantile(sorted, 0.95))).append('\t')
                        .append(Canon.num(quantile(sorted, 1.0))).append('\t')
                        .append(deltas.outsideTolerance).append('\n');
            }
            return sb.toString();
        }

        private static double quantile(List<Double> sorted, double q) {
            if (sorted.isEmpty()) return Double.NaN;
            int index = (int) Math.round(q * (sorted.size() - 1));
            return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index))).doubleValue();
        }
    }

    private Differ() {
    }

    static void compare(String run, String golden, String candidate, Report report) {
        Map<String, List<String>> goldenSections = sections(golden);
        Map<String, List<String>> candidateSections = sections(candidate);

        for (String name : goldenSections.keySet()) {
            if (!candidateSections.containsKey(name)) {
                report.differences.add(new Difference(run, name, Tiers.Tier.ONE,
                        "section present in golden but missing from candidate"));
            }
        }
        for (String name : candidateSections.keySet()) {
            if (!goldenSections.containsKey(name)) {
                report.differences.add(new Difference(run, name, Tiers.Tier.ONE,
                        "section present in candidate but absent from golden"));
            }
        }
        for (Map.Entry<String, List<String>> entry : goldenSections.entrySet()) {
            List<String> other = candidateSections.get(entry.getKey());
            if (other == null) continue;
            compareSection(run, entry.getKey(), entry.getValue(), other, report);
        }
    }

    private static void compareSection(String run,
                                       String section,
                                       List<String> golden,
                                       List<String> candidate,
                                       Report report) {
        if (isFullTable(golden) && isFullTable(candidate)) {
            compareTable(run, section, golden, candidate, report);
            return;
        }
        compareExactly(run, section, golden, candidate, report);
    }

    private static boolean isFullTable(List<String> lines) {
        boolean headings = false;
        boolean full = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("headings\t")) headings = true;
            if ("detail=full".equals(line)) full = true;
        }
        return headings && full;
    }

    private static void compareExactly(String run,
                                       String section,
                                       List<String> golden,
                                       List<String> candidate,
                                       Report report) {
        int max = Math.max(golden.size(), candidate.size());
        for (int i = 0; i < max; i++) {
            String a = i < golden.size() ? golden.get(i) : "<absent>";
            String b = i < candidate.size() ? candidate.get(i) : "<absent>";
            if (a.equals(b)) continue;
            // Only ever consulted here, on exact text. The numeric table
            // comparison never reaches this, so no measurement column can be
            // accepted away.
            AcceptedDifferences.Entry accepted = AcceptedDifferences.find(run, section, a, b);
            if (accepted != null) {
                report.accepted.add(accepted);
                continue;
            }
            report.differences.add(new Difference(run, section, Tiers.Tier.ONE,
                    "line " + i + ": golden '" + a + "' candidate '" + b + "'"));
        }
    }

    private static void compareTable(String run,
                                     String section,
                                     List<String> golden,
                                     List<String> candidate,
                                     Report report) {
        String[] goldenHeadings = headings(golden);
        String[] candidateHeadings = headings(candidate);
        if (!java.util.Arrays.equals(goldenHeadings, candidateHeadings)) {
            report.differences.add(new Difference(run, section, Tiers.Tier.ONE,
                    "column set changed: golden " + java.util.Arrays.toString(goldenHeadings)
                            + " candidate " + java.util.Arrays.toString(candidateHeadings)));
            return;
        }
        List<String[]> goldenRows = rows(golden);
        List<String[]> candidateRows = rows(candidate);
        if (goldenRows.size() != candidateRows.size()) {
            report.differences.add(new Difference(run, section, Tiers.Tier.ONE,
                    "row count changed: golden " + goldenRows.size()
                            + " candidate " + candidateRows.size()));
            return;
        }
        for (int row = 0; row < goldenRows.size(); row++) {
            String[] a = goldenRows.get(row);
            String[] b = candidateRows.get(row);
            for (int column = 0; column < goldenHeadings.length; column++) {
                String heading = goldenHeadings[column];
                // +1 for the leading row-index cell.
                String cellA = column + 1 < a.length ? a[column + 1] : "-";
                String cellB = column + 1 < b.length ? b[column + 1] : "-";
                if (cellA.equals(cellB)) {
                    recordDelta(report, heading, 0.0, true);
                    continue;
                }
                Double valueA = numeric(cellA);
                Double valueB = numeric(cellB);
                if (valueA == null || valueB == null) {
                    report.differences.add(new Difference(run, section, Tiers.tierOf(heading),
                            "row " + row + " column '" + heading + "': golden '"
                                    + cellA + "' candidate '" + cellB + "'"));
                    continue;
                }
                boolean agree = Tiers.agree(heading, valueA.doubleValue(), valueB.doubleValue());
                double relative = Tiers.relativeDifference(
                        valueA.doubleValue(), valueB.doubleValue());
                recordDelta(report, heading, relative, agree);
                if (!agree) {
                    report.differences.add(new Difference(run, section, Tiers.tierOf(heading),
                            "row " + row + " column '" + heading + "': golden " + cellA
                                    + " candidate " + cellB + " (relative "
                                    + Canon.num(relative) + ", tolerance "
                                    + Canon.num(Tiers.relativeToleranceFor(heading)) + ")"));
                }
            }
        }
    }

    /** Only Tier 2 and Tier 3 columns enter the delta table; Tier 1 has no deltas to tabulate. */
    private static void recordDelta(Report report, String column, double relative, boolean within) {
        if (Tiers.tierOf(column) == Tiers.Tier.ONE) return;
        Deltas deltas = report.deltasByColumn.get(column);
        if (deltas == null) {
            deltas = new Deltas();
            report.deltasByColumn.put(column, deltas);
        }
        deltas.add(relative, within);
    }

    private static Double numeric(String cell) {
        if (cell == null || cell.isEmpty() || cell.startsWith("s:") || "-".equals(cell)) return null;
        try {
            return Double.valueOf(Double.parseDouble(cell));
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static String[] headings(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("headings\t")) {
                return lines.get(i).substring("headings\t".length()).split("\t", -1);
            }
        }
        return new String[0];
    }

    private static List<String[]> rows(List<String> lines) {
        List<String[]> rows = new ArrayList<String[]>();
        boolean started = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if ("detail=full".equals(line)) {
                started = true;
                continue;
            }
            if (!started) continue;
            rows.add(line.split("\t", -1));
        }
        return rows;
    }

    /** Splits a canonical record into its {@code ## name} sections, order preserved. */
    private static Map<String, List<String>> sections(String text) {
        Map<String, List<String>> sections = new LinkedHashMap<String, List<String>>();
        String current = "header";
        sections.put(current, new ArrayList<String>());
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("## ")) {
                current = line.substring(3).trim();
                if (!sections.containsKey(current)) {
                    sections.put(current, new ArrayList<String>());
                }
                continue;
            }
            if (line.isEmpty() && i == lines.length - 1) continue;
            sections.get(current).add(line);
        }
        return sections;
    }
}
