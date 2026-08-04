package sc.fiji.oc3dsd.equivalence;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The Stage 01 exit gate.
 * <p>
 * Two properties, checked in one pass because they are checked against the same
 * output. First, the harness is <strong>deterministic</strong>: recording every
 * run twice in a row produces byte-identical text. A flaky harness certifies
 * nothing, so this is checked before anything is concluded from it. Second,
 * every run matches the <strong>immutable golden</strong> captured from the
 * pre-migration build, under the tier contract in {@link Tiers}.
 * <p>
 * To capture the goldens for the first time:
 * <pre>
 * mvn -o -B test -Doc3dsd.harness.capture=true
 * </pre>
 * Capture refuses to overwrite an existing golden set. Harness §7: a wrong
 * golden is a bug report against the shipped plugin, fixed as its own change —
 * never regenerated to make a diff go away. Deleting the directory to get past
 * that guard is a visible act in version control, which is the point.
 */
public class EquivalenceHarnessTest {

    @Test
    public void harnessIsDeterministicAndMatchesGoldens() throws IOException {
        List<Harness.Run> runs = Harness.runs();
        assertFalse("the sweep must not be empty", runs.isEmpty());

        if (Harness.captureRequested()) {
            capture(runs);
            return;
        }

        File root = Harness.goldenRoot();
        if (!root.isDirectory()) {
            fail("No goldens at " + root.getAbsolutePath()
                    + ". Capture them from the pre-migration build first:"
                    + " mvn -o -B test -D" + Harness.CAPTURE_PROPERTY + "=true");
        }

        Differ.Report report = new Differ.Report();
        List<String> nonDeterministic = new ArrayList<String>();
        List<String> missing = new ArrayList<String>();

        for (int i = 0; i < runs.size(); i++) {
            Harness.Run run = runs.get(i);
            String name = run.fixture.name + "/" + run.config.name;

            String first = Harness.record(run);
            String second = Harness.record(run);
            if (!first.equals(second)) {
                nonDeterministic.add(name + ": " + firstDifference(first, second));
                continue;
            }

            File golden = Harness.goldenFile(run.fixture.name, run.config.name);
            if (!golden.isFile()) {
                missing.add(name);
                continue;
            }
            Differ.compare(name, read(golden), first, report);
        }

        String macroFirst = Harness.recordMacroRoundTrip();
        String macroSecond = Harness.recordMacroRoundTrip();
        if (!macroFirst.equals(macroSecond)) {
            nonDeterministic.add("macro round-trip: " + firstDifference(macroFirst, macroSecond));
        } else {
            File golden = new File(Harness.goldenRoot(), "macro_roundtrip.txt");
            if (!golden.isFile()) {
                missing.add("macro_roundtrip");
            } else {
                Differ.compare("macro_roundtrip", read(golden), macroFirst, report);
            }
        }

        if (!nonDeterministic.isEmpty()) {
            fail("Harness is not deterministic — two consecutive recordings differ:\n"
                    + bullets(nonDeterministic));
        }
        if (!missing.isEmpty()) {
            fail("Goldens missing for " + missing.size() + " run(s) under "
                    + Harness.goldenRoot().getAbsolutePath() + ":\n" + bullets(missing));
        }

        // The delta table is a Stage 03 deliverable, but it is produced on every
        // run so it is never assembled retrospectively to fit a result.
        System.out.println("Tier 2/3 delta table\n" + report.deltaTable());

        // Accepted differences stay visible. Writing one down is not the same as
        // making it disappear.
        for (int i = 0; i < report.accepted.size(); i++) {
            System.out.println("ACCEPTED DIFFERENCE  " + report.accepted.get(i));
        }

        // A registered entry that no longer fires is stale, and stale entries are
        // how a narrow exemption widens into a blanket one. Fail rather than let
        // them accumulate.
        List<AcceptedDifferences.Entry> unused = new ArrayList<AcceptedDifferences.Entry>();
        for (AcceptedDifferences.Entry entry : AcceptedDifferences.all()) {
            if (!report.accepted.contains(entry)) unused.add(entry);
        }
        if (!unused.isEmpty()) {
            StringBuilder sb = new StringBuilder(
                    "Accepted-difference entries that matched nothing. The behaviour they"
                            + " describe has changed again, or they were never needed."
                            + " Remove them or correct them:\n");
            for (int i = 0; i < unused.size(); i++) {
                sb.append("  - ").append(unused.get(i)).append('\n');
            }
            fail(sb.toString());
        }

        List<Differ.Difference> tier1 = report.ofTier(Tiers.Tier.ONE);
        List<Differ.Difference> tier2 = report.ofTier(Tiers.Tier.TWO);
        List<Differ.Difference> tier3 = report.ofTier(Tiers.Tier.THREE);

        StringBuilder failure = new StringBuilder();
        if (!tier1.isEmpty()) {
            failure.append("TIER 1 — zero differences allowed, found ")
                    .append(tier1.size()).append(":\n").append(summarise(tier1));
        }
        if (!tier2.isEmpty()) {
            failure.append("TIER 2 — outside declared tolerance, found ")
                    .append(tier2.size()).append(":\n").append(summarise(tier2));
        }
        if (!tier3.isEmpty()) {
            failure.append("TIER 3 — requires written sign-off before it may move, found ")
                    .append(tier3.size()).append(":\n").append(summarise(tier3));
        }
        if (failure.length() > 0) {
            fail(failure.toString());
        }
    }

    /**
     * The differ has to be able to fail. A comparison that cannot detect a
     * seeded change certifies nothing, and this is cheap insurance against the
     * whole harness quietly turning into a no-op during a later stage.
     */
    @Test
    public void differDetectsSeededChangesAndHonoursTheContract() {
        String golden = ""
                + "# fixture=x config=y\n"
                + "## counts\n"
                + "objects=3\n"
                + "## statistics\n"
                + "rows=1 columns=3\n"
                + "headings\tNb of obj. voxels\tMorph_Elongation\tMorph_Sphericity\n"
                + "body_sha256=irrelevant\n"
                + "detail=full\n"
                + "0\t10\t2.0\t0.5\n";

        // Tier 1: an integer voxel count moving by one is a failure.
        Differ.Report tier1 = new Differ.Report();
        Differ.compare("r", golden, golden.replace("0\t10\t", "0\t11\t"), tier1);
        assertEquals(1, tier1.ofTier(Tiers.Tier.ONE).size());

        // Tier 1: a count line outside any table is compared exactly.
        Differ.Report counts = new Differ.Report();
        Differ.compare("r", golden, golden.replace("objects=3", "objects=4"), counts);
        assertEquals(1, counts.ofTier(Tiers.Tier.ONE).size());

        // Tier 2: elongation may move within 1e-9 relative, and may not beyond it.
        Differ.Report within = new Differ.Report();
        Differ.compare("r", golden, golden.replace("\t2.0\t", "\t2.0000000001\t"), within);
        assertTrue("1e-10 relative is inside the declared elongation tolerance",
                within.differences.isEmpty());

        Differ.Report beyond = new Differ.Report();
        Differ.compare("r", golden, golden.replace("\t2.0\t", "\t2.001\t"), beyond);
        assertEquals(1, beyond.ofTier(Tiers.Tier.TWO).size());

        // Tier 2 with no declared tolerance: sphericity may not move at all.
        Differ.Report sphericity = new Differ.Report();
        Differ.compare("r", golden, golden.replace("\t0.5\n", "\t0.5000000001\n"), sphericity);
        assertEquals(1, sphericity.ofTier(Tiers.Tier.TWO).size());

        // Identical input must be silent, or every other assertion above is meaningless.
        Differ.Report same = new Differ.Report();
        Differ.compare("r", golden, golden, same);
        assertTrue(same.differences.isEmpty());
    }

    /** Tier 1 means no tolerance. Not a small tolerance — none. */
    @Test
    public void tierOneColumnsCarryNoTolerance() {
        String[] tierOne = {
                "Volume (micron^3)", "Nb of obj. voxels", "Nb of surf. voxels", "IntDen",
                "Mean", "StdDev", "Min", "Max", "X", "Y", "Z", "XM", "YM", "ZM",
                "BX", "BY", "BZ", "B-width", "B-height", "B-depth", "Label",
                "Slices", "Detector_Track_ID", "Detector_Quality_Mean",
                "Detector_Area_Mean", "Detector_Intensity_Mean",
        };
        for (int i = 0; i < tierOne.length; i++) {
            assertEquals(tierOne[i] + " must be Tier 1",
                    Tiers.Tier.ONE, Tiers.tierOf(tierOne[i]));
            assertEquals(tierOne[i] + " must carry no tolerance",
                    0.0, Tiers.relativeToleranceFor(tierOne[i]), 0.0);
        }
        assertEquals(Tiers.Tier.TWO, Tiers.tierOf("Surface (micron^2)"));
        assertEquals(Tiers.Tier.TWO, Tiers.tierOf("Morph_Sphericity"));
        assertEquals(Tiers.Tier.TWO, Tiers.tierOf("Morph_Compactness"));
        assertEquals(Tiers.Tier.TWO, Tiers.tierOf("Morph_Elongation"));
        assertEquals(Tiers.Tier.THREE, Tiers.tierOf("Morph_Feret3D_um"));

        // A column nobody has classified is Tier 1, so a renamed or new column
        // stops the harness instead of inheriting a convenient default.
        assertEquals(Tiers.Tier.ONE, Tiers.tierOf("Something_New"));
    }

    // ------------------------------------------------------------------
    // Capture
    // ------------------------------------------------------------------

    private void capture(List<Harness.Run> runs) throws IOException {
        File root = Harness.goldenRoot();
        if (root.exists()) {
            fail("Goldens already exist at " + root.getAbsolutePath()
                    + ". They are immutable (harness §7). A wrong golden is a bug report"
                    + " against the shipped plugin, fixed as its own change — never"
                    + " regenerated to make a diff go away.");
        }
        String currentFixture = null;
        long fixtureStart = 0;
        for (int i = 0; i < runs.size(); i++) {
            Harness.Run run = runs.get(i);
            if (!run.fixture.name.equals(currentFixture)) {
                if (currentFixture != null) {
                    System.out.println("  " + currentFixture + ": "
                            + (System.currentTimeMillis() - fixtureStart) + " ms");
                }
                currentFixture = run.fixture.name;
                fixtureStart = System.currentTimeMillis();
            }
            String first = Harness.record(run);
            String second = Harness.record(run);
            assertEquals("refusing to capture a non-deterministic run: "
                    + run.fixture.name + "/" + run.config.name, first, second);
            write(Harness.goldenFile(run.fixture.name, run.config.name), first);
        }
        if (currentFixture != null) {
            System.out.println("  " + currentFixture + ": "
                    + (System.currentTimeMillis() - fixtureStart) + " ms");
        }
        write(new File(root, "macro_roundtrip.txt"), Harness.recordMacroRoundTrip());
        write(new File(root, "MANIFEST.txt"), manifest(runs));
        System.out.println("Captured " + runs.size() + " runs to " + root.getAbsolutePath());
    }

    private static String manifest(List<Harness.Run> runs) {
        StringBuilder sb = new StringBuilder();
        sb.append("golden set: ").append(Harness.GOLDEN_SET).append('\n');
        sb.append("reference sha: ").append(Harness.REFERENCE_SHA).append('\n');
        sb.append("runs: ").append(runs.size()).append('\n');
        sb.append("fixtures: ").append(Fixtures.all().size()).append('\n');
        sb.append("configurations: ").append(Sweep.all().size()).append('\n');
        sb.append('\n');
        sb.append("The 27-column schema: the column order 3D Objects Counter+ has always\n");
        sb.append("shipped, adopted from oc3d-core so the family has one column order.\n");
        sb.append("Median sits between StdDev and Min; the Morph_* block follows Label.\n");
        sb.append('\n');
        sb.append("Superseded golden/").append(Harness.REFERENCE_SHA);
        sb.append(", which records the 26-column layout this plugin\n");
        sb.append("shipped before. That set is KEPT, not deleted: SchemaTransitionTest reads it\n");
        sb.append("and proves every value under every carried-over column is identical here\n");
        sb.append("— 163678 of them — with Median the only column added. Capturing this set\n");
        sb.append("without that proof would have replaced the evidence with an assumption.\n");
        sb.append('\n');
        sb.append("Immutable from this point: harness §7. Detection is NOT exercised — see\n");
        sb.append("docs/migration/DETERMINISM.md for what this set can and cannot certify.\n");
        sb.append('\n');
        sb.append("Coverage reductions, stated rather than left implicit:\n");
        sb.append(Harness.reductionNotes());
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    private static String firstDifference(String a, String b) {
        String[] left = a.split("\n", -1);
        String[] right = b.split("\n", -1);
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            String x = i < left.length ? left[i] : "<absent>";
            String y = i < right.length ? right[i] : "<absent>";
            if (!x.equals(y)) {
                return "line " + i + ": '" + x + "' vs '" + y + "'";
            }
        }
        return "no line differs (lengths " + a.length() + " and " + b.length() + ")";
    }

    private static String bullets(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size() && i < 40; i++) {
            sb.append("  - ").append(items.get(i)).append('\n');
        }
        if (items.size() > 40) {
            sb.append("  ... and ").append(items.size() - 40).append(" more\n");
        }
        return sb.toString();
    }

    private static String summarise(List<Differ.Difference> differences) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < differences.size() && i < 40; i++) {
            sb.append("  ").append(differences.get(i)).append('\n');
        }
        if (differences.size() > 40) {
            sb.append("  ... and ").append(differences.size() - 40).append(" more\n");
        }
        return sb.toString();
    }

    private static String read(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    private static void write(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("could not create " + parent.getAbsolutePath());
        }
        OutputStream out = new FileOutputStream(file);
        try {
            out.write(bytes(text));
        } finally {
            out.close();
        }
    }

    private static byte[] bytes(String text) {
        try {
            return text.getBytes("UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException("UTF-8 is required by the JLS", impossible);
        }
    }
}
