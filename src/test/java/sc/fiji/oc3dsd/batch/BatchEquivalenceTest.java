package sc.fiji.oc3dsd.batch;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The batch layer's equivalence gate.
 *
 * <p>The measurement harness covers what a run computes. This covers what a
 * batch run <em>writes</em>: which files, in which folders, with which columns,
 * which quoting, which line endings and which numbers. Those are different
 * failure modes — a table can be right while the CSV carrying it is wrong — and
 * for most users the CSV is the only form these numbers ever take.
 *
 * <h2>Golden provenance</h2>
 *
 * These goldens were captured at the start of Stage 04, not at the Stage 01
 * reference commit, and that needs stating rather than glossing.
 *
 * <p>It is equivalent to a pre-migration capture for a checkable reason:
 * {@code src/main/java/sc/fiji/oc3dsd/batch/} is byte-identical to the
 * pre-migration import ({@code git diff d4ef7df..HEAD -- .../batch/} is empty
 * apart from the {@code ResultSource} seam added to record them), and every
 * input the batch layer consumes is already pinned by the Stage 01 goldens as
 * unchanged. Identical code on identical inputs produces identical output.
 *
 * <p>From here they carry the same guarantee as the Stage 01 set: <b>immutable</b>.
 * A wrong golden is a bug report against the shipped plugin and is fixed as its
 * own change, never by recapturing to make a diff disappear.
 */
public class BatchEquivalenceTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final File GOLDEN_DIR = new File("golden/batch");
    private static final String CAPTURE_PROPERTY = "oc3dsd.batch.capture";

    @Rule
    public TemporaryFolder workspace = new TemporaryFolder();

    @Test
    public void batchOutputIsDeterministicAndMatchesGoldens() throws IOException {
        List<BatchHarness.Config> configs = BatchHarness.configs();
        assertTrue("no batch configurations", configs.size() >= 8);

        List<String> first = new ArrayList<String>();
        for (BatchHarness.Config config : configs) {
            first.add(BatchHarness.record(config, workspace.newFolder(config.name + "-1")));
        }

        // A batch run walks the filesystem and aggregates into hash maps. Both are
        // places where an accidental dependence on iteration order hides, and it
        // would show up as a golden that passes today and fails on another machine.
        for (int i = 0; i < configs.size(); i++) {
            String again = BatchHarness.record(
                    configs.get(i), workspace.newFolder(configs.get(i).name + "-2"));
            assertEquals("batch run '" + configs.get(i).name + "' is not reproducible",
                    first.get(i), again);
        }

        if (Boolean.getBoolean(CAPTURE_PROPERTY)) {
            capture(configs, first);
            return;
        }

        List<String> differences = new ArrayList<String>();
        for (int i = 0; i < configs.size(); i++) {
            String name = configs.get(i).name;
            File golden = new File(GOLDEN_DIR, name + ".txt");
            if (!golden.isFile()) {
                differences.add(name + ": no golden at " + golden.getPath()
                        + " (capture with -D" + CAPTURE_PROPERTY + "=true)");
                continue;
            }
            String expected = normalise(read(golden));
            String actual = normalise(first.get(i));
            if (!expected.equals(actual)) {
                differences.add(name + ":\n" + firstDifference(expected, actual));
            }
        }

        if (!differences.isEmpty()) {
            fail("batch output differs from the goldens. Every one of these is "
                    + "user-visible: they are the files a batch run leaves on disk.\n\n"
                    + join(differences));
        }
    }

    /**
     * A file name containing a comma must be quoted in every CSV that carries it.
     *
     * <p>This is the failure the goldens exist to prevent but would not announce
     * clearly: an unquoted comma shifts every column to its right by one, and the
     * file still opens in Excel — with the wrong numbers under the right headings.
     * The goldens would catch a regression as a diff; this says what the diff
     * <em>means</em>.
     *
     * <p>Two writers reach disk by different routes — {@code ResultsTable.saveAs}
     * for the tables and {@code BatchWriter.csvCell} for the manifest — so both
     * are checked, not just the one that happened to be written most recently.
     */
    @Test
    public void everyCsvIsRectangularDespiteACommaInAFileName() throws IOException {
        String rendered = BatchHarness.record(
                BatchHarness.configs().get(0), workspace.newFolder("quoting"));
        assertTrue("the corpus should have produced a manifest",
                rendered.contains("Summary/manifest.csv"));

        List<String> problems = new ArrayList<String>();
        String section = null;
        int headerCells = -1;
        int checkedRows = 0;
        int sawTheCommaName = 0;

        for (String line : rendered.split("\n", -1)) {
            if (line.startsWith("## ")) {
                section = line.substring(3);
                headerCells = -1;
                continue;
            }
            if (section == null || !section.endsWith(".csv")) continue;
            // Skip the per-file preamble the canon writes before the content.
            if (line.startsWith("lineEndings=") || line.startsWith("lines=")) continue;
            if (line.isEmpty()) continue;

            List<String> cells = BatchCanon.splitCsv(line);
            if (headerCells < 0) {
                headerCells = cells.size();
                continue;
            }
            checkedRows++;
            if (line.contains("odd,name_wt_05.tif")) sawTheCommaName++;
            if (cells.size() != headerCells) {
                problems.add(section + ": a row has " + cells.size()
                        + " cells where the header has " + headerCells + "\n    " + line);
            }
        }

        assertTrue("no CSV rows were checked - the rendering shape must have changed",
                checkedRows > 20);
        assertTrue("the comma-containing file name never reached a CSV row, so this "
                + "test verified nothing; check the corpus still contains it",
                sawTheCommaName > 0);
        assertTrue("a CSV row does not line up with its header. Every column to the "
                + "right of the offending cell is shifted, and the file still opens "
                + "in a spreadsheet - with the wrong numbers under the right "
                + "headings.\n  " + join(problems), problems.isEmpty());
    }

    // ------------------------------------------------------------------

    private void capture(List<BatchHarness.Config> configs, List<String> rendered)
            throws IOException {
        if (!GOLDEN_DIR.isDirectory() && !GOLDEN_DIR.mkdirs()) {
            throw new IOException("could not create " + GOLDEN_DIR.getAbsolutePath());
        }
        for (int i = 0; i < configs.size(); i++) {
            File target = new File(GOLDEN_DIR, configs.get(i).name + ".txt");
            if (target.isFile()) {
                throw new IllegalStateException("refusing to overwrite an existing golden: "
                        + target.getPath() + ". Goldens are immutable; delete it deliberately "
                        + "and record why, or fix the code instead.");
            }
            write(target, rendered.get(i));
        }
        write(new File(GOLDEN_DIR, "MANIFEST.txt"),
                "Batch output goldens.\n\n"
                        + "configurations: " + configs.size() + "\n"
                        + "captured at: Stage 04, before any batch class was reconciled\n\n"
                        + "Provenance: src/main/java/sc/fiji/oc3dsd/batch/ is byte-identical to\n"
                        + "the pre-migration import, and the measurement results it consumes are\n"
                        + "pinned unchanged by the Stage 01 goldens. Identical code on identical\n"
                        + "inputs, so this is equivalent to a pre-migration capture.\n\n"
                        + "Normalised: the input and output roots (temporary directories) and the\n"
                        + "manifest's elapsed_ms column. Nothing else - line endings in particular\n"
                        + "are recorded, not normalised away.\n\n"
                        + "Immutable from this point. Do not recapture to silence a diff.\n");
    }

    /** Line endings of the golden file itself are not the subject under test. */
    private static String normalise(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String firstDifference(String expected, String actual) {
        String[] want = expected.split("\n", -1);
        String[] got = actual.split("\n", -1);
        int limit = Math.min(want.length, got.length);
        for (int i = 0; i < limit; i++) {
            if (!want[i].equals(got[i])) {
                return "  line " + (i + 1) + "\n    golden:    " + want[i]
                        + "\n    candidate: " + got[i];
            }
        }
        return "  identical for " + limit + " lines, then golden has " + want.length
                + " lines and the candidate has " + got.length;
    }

    private static String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append("\n\n");
            sb.append(values.get(i));
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

    private static void write(File file, String content) throws IOException {
        Writer writer = new OutputStreamWriter(new FileOutputStream(file), UTF8);
        try {
            writer.write(content);
        } finally {
            writer.close();
        }
    }
}
