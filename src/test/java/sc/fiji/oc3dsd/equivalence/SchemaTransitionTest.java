package sc.fiji.oc3dsd.equivalence;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Proves that adopting {@code oc3d-core}'s statistics schema changed the schema
 * and <b>nothing else</b>.
 *
 * <h2>Why this test exists rather than a recapture</h2>
 *
 * The pre-migration goldens in {@code golden/d4ef7df} record 26 columns with the
 * {@code Morph_*} block before {@code BX}. Core emits 27, with {@code Median}
 * between {@code StdDev} and {@code Min} and the {@code Morph_*} block after
 * {@code Label} — the order 3D Objects Counter+ has always shipped, verified
 * against that plugin's own goldens.
 *
 * <p>Compared line by line, that is 1708 differences. Almost all of them are one
 * reordering restated once per row, and a plain recapture would replace the
 * goldens and assert nothing about whether a <em>value</em> moved underneath the
 * reshuffle. That is exactly the failure a golden set exists to prevent.
 *
 * <p>So the old goldens are read as data and compared to current output
 * <b>by column name</b>:
 *
 * <ul>
 *   <li>every column present before is still present;</li>
 *   <li>every value under every one of those columns is byte-identical, in the
 *       same row order;</li>
 *   <li>the only column that appears is {@code Median};</li>
 *   <li>every section that is not a table — the configuration echo, the counts,
 *       the label partition, all four maps and their overlays — is byte-identical.</li>
 * </ul>
 *
 * With those four established, "the schema changed and no measurement moved" is
 * a checked statement rather than a hope. This test reads the <em>old</em>
 * goldens and is what licenses capturing the new ones; it stays in the tree as
 * the record of the transition.
 */
public class SchemaTransitionTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final File OLD_GOLDEN_ROOT = new File("golden/d4ef7df");

    /** The one column the transition is allowed to add. */
    private static final Set<String> ALLOWED_NEW_COLUMNS =
            new LinkedHashSet<String>(Arrays.asList("Median"));

    @Test
    public void onlyTheColumnSetAndOrderChanged() throws IOException {
        assertTrue("the pre-migration goldens must still be present at "
                + OLD_GOLDEN_ROOT.getPath() + "; they are the evidence this test rests on",
                OLD_GOLDEN_ROOT.isDirectory());

        List<String> problems = new ArrayList<String>();
        int runsChecked = 0;
        int columnsChecked = 0;
        int valuesChecked = 0;

        for (Harness.Run run : Harness.runs()) {
            File golden = new File(new File(OLD_GOLDEN_ROOT, run.fixture.name),
                    run.config.name + ".txt");
            if (!golden.isFile()) continue;
            runsChecked++;

            Map<String, List<String>> before = sections(read(golden));
            Map<String, List<String>> after = sections(Harness.record(run));
            String where = run.fixture.name + "/" + run.config.name;

            if (!before.keySet().equals(after.keySet())) {
                problems.add(where + ": the set of recorded sections changed, was "
                        + before.keySet() + " now " + after.keySet());
                continue;
            }

            for (Map.Entry<String, List<String>> entry : before.entrySet()) {
                String section = entry.getKey();
                List<String> oldLines = entry.getValue();
                List<String> newLines = after.get(section);

                Table oldTable = Table.parse(oldLines);
                Table newTable = Table.parse(newLines);

                if (oldTable == null || newTable == null) {
                    // Not a full-detail table: it must not have moved at all.
                    if (!oldLines.equals(newLines)) {
                        problems.add(where + " [" + section + "]: a non-table section "
                                + "changed, and only the statistics schema was supposed to:\n"
                                + firstDifferingLine(oldLines, newLines));
                    }
                    continue;
                }

                int[] counted = oldTable.compareByColumnName(newTable, where, section,
                        ALLOWED_NEW_COLUMNS, problems);
                columnsChecked += counted[0];
                valuesChecked += counted[1];
            }
        }

        assertTrue("no runs were checked - the old goldens or the run list must have moved",
                runsChecked > 100);
        assertTrue("no values were compared, so this test proved nothing",
                valuesChecked > 1000);

        if (!problems.isEmpty()) {
            fail("adopting core's schema moved something other than the schema. "
                    + runsChecked + " runs, " + columnsChecked + " columns, "
                    + valuesChecked + " values compared.\n\n" + join(problems, 40));
        }

        System.out.println("schema transition verified: " + runsChecked + " runs, "
                + columnsChecked + " columns, " + valuesChecked
                + " values identical under their own column names; the only column added is "
                + ALLOWED_NEW_COLUMNS);
    }

    // ------------------------------------------------------------------
    // A parsed full-detail table
    // ------------------------------------------------------------------

    private static final class Table {
        final List<String> headings;
        /** One list of cells per row, in recorded order. */
        final List<List<String>> rows;

        Table(List<String> headings, List<List<String>> rows) {
            this.headings = headings;
            this.rows = rows;
        }

        /**
         * Parses a section rendered by {@code Canon.table}, or returns null when
         * the section is not a table recorded in full detail.
         *
         * <p>Above 512 rows the canon records a body hash plus the first and last
         * 32 rows. The hash covers a column order that has legitimately changed
         * and so cannot be compared either way, but the recorded windows can: row
         * order is unchanged, so they align one-to-one and every value in them is
         * checked by name like any other. Those fixtures are therefore verified
         * at their edges rather than not at all, and this says so rather than
         * letting the reduced coverage pass as full coverage.
         */
        static Table parse(List<String> lines) {
            List<String> headings = null;
            boolean detail = false;
            boolean declaredEmpty = false;
            List<List<String>> rows = new ArrayList<List<String>>();
            for (String line : lines) {
                if (line.startsWith("rows=")) {
                    // An empty table stops after its headings, with no detail line;
                    // its column set still has to be compared by name.
                    declaredEmpty = line.startsWith("rows=0 ");
                } else if (line.startsWith("headings\t")) {
                    headings = Arrays.asList(line.substring("headings\t".length()).split("\t", -1));
                } else if (line.startsWith("detail=")) {
                    detail = true;
                } else if (detail && !line.isEmpty() && Character.isDigit(line.charAt(0))) {
                    List<String> cells =
                            new ArrayList<String>(Arrays.asList(line.split("\t", -1)));
                    cells.remove(0); // the row index
                    rows.add(cells);
                }
            }
            if (headings == null || !(detail || declaredEmpty)) return null;
            return new Table(headings, rows);
        }

        /** @return {columns compared, values compared} */
        int[] compareByColumnName(Table other, String where, String section,
                                  Set<String> allowedNew, List<String> problems) {
            Set<String> added = new LinkedHashSet<String>(other.headings);
            added.removeAll(headings);
            added.removeAll(allowedNew);
            if (!added.isEmpty()) {
                problems.add(where + " [" + section + "]: unexpected new column(s) " + added);
            }
            Set<String> removed = new LinkedHashSet<String>(headings);
            removed.removeAll(other.headings);
            if (!removed.isEmpty()) {
                problems.add(where + " [" + section + "]: column(s) disappeared " + removed);
            }
            if (rows.size() != other.rows.size()) {
                problems.add(where + " [" + section + "]: row count changed, was "
                        + rows.size() + " now " + other.rows.size());
                return new int[]{0, 0};
            }

            int columns = 0;
            int values = 0;
            for (int c = 0; c < headings.size(); c++) {
                String column = headings.get(c);
                int target = other.headings.indexOf(column);
                if (target < 0) continue;
                columns++;
                for (int r = 0; r < rows.size(); r++) {
                    List<String> oldRow = rows.get(r);
                    List<String> newRow = other.rows.get(r);
                    if (c >= oldRow.size() || target >= newRow.size()) continue;
                    values++;
                    String before = oldRow.get(c);
                    String after = newRow.get(target);
                    if (!before.equals(after)) {
                        problems.add(where + " [" + section + "] row " + r + " column '"
                                + column + "': was '" + before + "' now '" + after + "'");
                    }
                }
            }
            return new int[]{columns, values};
        }
    }

    // ------------------------------------------------------------------

    /** Splits a rendering into its {@code ## name} sections, in order. */
    private static Map<String, List<String>> sections(String rendering) {
        Map<String, List<String>> out = new LinkedHashMap<String, List<String>>();
        List<String> current = null;
        for (String line : rendering.replace("\r\n", "\n").split("\n", -1)) {
            if (line.startsWith("## ")) {
                current = new ArrayList<String>();
                out.put(line.substring(3), current);
            } else if (current != null) {
                current.add(line);
            }
        }
        return out;
    }

    private static String firstDifferingLine(List<String> before, List<String> after) {
        int limit = Math.min(before.size(), after.size());
        for (int i = 0; i < limit; i++) {
            if (!before.get(i).equals(after.get(i))) {
                return "    golden:    " + before.get(i) + "\n    candidate: " + after.get(i);
            }
        }
        return "    lengths differ: " + before.size() + " vs " + after.size();
    }

    private static String join(List<String> values, int limit) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size() && i < limit; i++) {
            if (i > 0) sb.append('\n');
            sb.append("  ").append(values.get(i));
        }
        if (values.size() > limit) {
            sb.append("\n  ... and ").append(values.size() - limit).append(" more");
        }
        return sb.toString();
    }

    private static String read(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return new String(out.toByteArray(), UTF8);
        } finally {
            in.close();
        }
    }
}
