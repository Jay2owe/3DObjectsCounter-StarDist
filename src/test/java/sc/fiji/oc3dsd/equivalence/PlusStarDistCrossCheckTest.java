package sc.fiji.oc3dsd.equivalence;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;
import org.junit.Ignore;
import org.junit.Test;
import sc.fiji.oc3d.core.measure.LabelFeatureAccumulator;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The cross-plugin check: <b>3D Objects Counter+ and 3D Objects Counter -
 * StarDist must produce an identical statistics table for an identical label
 * image</b>, because after the migration both call the same
 * {@link LabelFeatureAccumulator}. Any disagreement is a bug in the extraction,
 * not a tolerance question.
 *
 * <h2>Status: the check itself is PARKED and has NOT run</h2>
 *
 * {@link #plusAndStarDistAgreeOnTheSameLabelImage()} is {@code @Ignore}d because
 * the 3D Objects Counter+ repository does not currently compile — Dropbox has
 * dehydrated 51 of its 72 source files and 256 of its git objects. That is
 * blocker <b>P0</b> in the program overview. Until it is cleared, no reference
 * table can be produced from the Plus side and there is nothing to compare
 * against.
 *
 * <p>The gate item is therefore <b>OPEN</b>, not passed. It closes in PLUS/05
 * and must be green before <em>either</em> plugin is published.
 *
 * <h2>Why the comparison goes through a file rather than two live plugins</h2>
 *
 * Running both plugins in one JVM would work today and stop working at Stage 05,
 * when each jar shades core under its own relocated package: the two
 * {@code LabelFeatureAccumulator} classes would then be unrelated types with
 * unrelated {@code FeatureValues}, and a direct object comparison could not be
 * written at all. Comparing a canonical rendering instead survives relocation,
 * because text does not care what package produced it.
 *
 * <p>So each repository renders its own side with {@link Canon#table} and the
 * two renderings are compared byte for byte. This file is written to be copied
 * <em>verbatim</em> into the Plus repository; only the package declaration needs
 * to change. {@link #buildFixtures()} deliberately depends on nothing but
 * {@code ij}, so the two sides cannot drift through a shared helper that only
 * one of them updates.
 *
 * <h2>What this check does <em>not</em> cover</h2>
 *
 * After PLUS/05 both sides call the same accumulator, so this check proves the
 * extraction is faithful — it cannot prove that the extracted code agrees with
 * the library 3D Objects Counter+ used to get {@code Morph_Sphericity} and
 * {@code Morph_Compactness} from. That is a different question, and it is
 * answered by {@link CorrectedSurfaceAgainstMcib3dTest}, which runs today: the
 * corrected surface reimplemented in {@code ij} matches mcib3d's own
 * {@code MeasureCompactness} to within 1e-9 relative across seven shapes.
 *
 * <p>Those two together are what "the columns mean the same thing in both
 * plugins" actually requires. Neither is sufficient alone.
 *
 * <h2>How to close this gate</h2>
 *
 * <ol>
 *   <li>Rehydrate the Plus repository and complete PLUS/05.</li>
 *   <li>Run {@link #starDistSideIsDeterministicAndReadyForTheCrossCheck()} in
 *       both repositories. Each writes its side to
 *       {@code target/crosscheck/}.</li>
 *   <li>Copy the Plus rendering to
 *       {@code src/test/resources/crosscheck/plus-measurement-reference.txt}
 *       in this repository, and this repository's rendering to the equivalent
 *       path in Plus.</li>
 *   <li>Remove the {@code @Ignore} in both. Both must pass.</li>
 * </ol>
 *
 * A difference found at that point is diagnosed, never tolerated: these are the
 * same code path on the same input, so the two renderings are either identical
 * or something is wrong with the extraction.
 */
public class PlusStarDistCrossCheckTest {

    /** Supplied by the Plus migration; absent by design until PLUS/05 lands. */
    private static final String PLUS_REFERENCE =
            "/crosscheck/plus-measurement-reference.txt";

    private static final Charset UTF8 = Charset.forName("UTF-8");

    // ------------------------------------------------------------------
    // The half that can run now
    // ------------------------------------------------------------------

    /**
     * Renders this plugin's side of the cross-check and proves it reproducible,
     * then writes it to {@code target/crosscheck/} for the Plus side to consume.
     *
     * <p>This runs today and is not part of the parked gate. It is what makes
     * the gate closeable the moment P0 clears: the artefact Plus needs already
     * exists, and its determinism is established here rather than being assumed
     * later, when a mismatch would be ambiguous between "the plugins disagree"
     * and "the rendering is not stable".
     */
    @Test
    public void starDistSideIsDeterministicAndReadyForTheCrossCheck() throws IOException {
        String once = render();
        String twice = render();
        assertEquals("the cross-check rendering must be reproducible within one JVM",
                once, twice);

        assertTrue("rendering must carry every fixture", once.contains("## cube"));
        assertTrue(once.contains("## single_voxel"));
        assertTrue(once.contains("## hollow_shell"));
        assertTrue(once.contains("## two_objects_anisotropic"));
        assertTrue(once.contains("## intensity_ramp"));

        File out = new File("target/crosscheck");
        assertTrue("could not create " + out.getAbsolutePath(), out.isDirectory() || out.mkdirs());
        File file = new File(out, "stardist-measurement-reference.txt");
        Writer writer = new OutputStreamWriter(new FileOutputStream(file), UTF8);
        try {
            writer.write(once);
        } finally {
            writer.close();
        }
    }

    // ------------------------------------------------------------------
    // The gate itself — parked
    // ------------------------------------------------------------------

    @Test
    @Ignore("BLOCKED by P0: the 3D Objects Counter+ repository does not compile "
            + "(Dropbox has dehydrated 51 of 72 source files and 256 git objects), so no "
            + "Plus-side reference table can be produced. Gate item is OPEN, not passed. "
            + "Closes in PLUS/05; must be green before either plugin is published. "
            + "To enable: drop the Plus rendering at "
            + "src/test/resources/crosscheck/plus-measurement-reference.txt and remove this annotation.")
    public void plusAndStarDistAgreeOnTheSameLabelImage() throws IOException {
        String plus = readResource(PLUS_REFERENCE);
        assertNotNull("the Plus reference rendering is missing from the test classpath at "
                + PLUS_REFERENCE + "; see this class's javadoc for how it is produced", plus);

        // Byte-for-byte. Both sides run the same accumulator on the same input,
        // so there is no legitimate source of difference to absorb.
        assertEquals("3D Objects Counter+ and 3D Objects Counter - StarDist disagree on the "
                + "same label image. This is a bug in the core extraction, not a tolerance "
                + "question - trace it to the causing object and column.",
                normaliseLineEndings(plus), normaliseLineEndings(render()));
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /**
     * The canonical rendering of every shared fixture's statistics table.
     *
     * <p>Uses {@link Canon#table}, the same serialisation the equivalence
     * harness records its goldens with: shortest round-tripping decimals, a
     * SHA-256 over raw {@code doubleToLongBits} rather than over formatted text,
     * and full per-row detail below 512 rows. Nothing here is lossy at the
     * precision that matters.
     */
    private static String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("# oc3d cross-check: measurement\n");
        sb.append("# Plus and StarDist must render this file identically.\n");
        for (Fixture fixture : buildFixtures()) {
            LabelFeatureAccumulator.Result measured = LabelFeatureAccumulator.scan(
                    fixture.labels, fixture.intensity, fixture.labels.getCalibration());
            ResultsTable table = measured.toStatisticsTable(null);
            sb.append(Canon.table(fixture.name, table));
        }
        return sb.toString();
    }

    private static String normaliseLineEndings(String text) {
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }

    private static String readResource(String path) throws IOException {
        InputStream in = PlusStarDistCrossCheckTest.class.getResourceAsStream(path);
        if (in == null) return null;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), UTF8);
        } finally {
            in.close();
        }
    }

    // ------------------------------------------------------------------
    // The shared fixtures
    // ------------------------------------------------------------------

    private static final class Fixture {
        final String name;
        final ImagePlus labels;
        final ImagePlus intensity;

        Fixture(String name, ImagePlus labels, ImagePlus intensity) {
            this.name = name;
            this.labels = labels;
            this.intensity = intensity;
        }
    }

    /**
     * Five label images, specified precisely enough that both repositories build
     * byte-identical inputs from this code alone.
     *
     * <ul>
     *   <li><b>cube</b> — 16x16x8 short stack, uncalibrated. Label 1 fills
     *       x,y,z in [2,7]x[2,7]x[1,6]. A plain solid body: volume, centroid,
     *       bounding box, exposed-face surface.</li>
     *   <li><b>single_voxel</b> — 8x8x4, label 1 at (3,3,1) only. All six faces
     *       exposed, so the Lindblad corrected surface is 0 and sphericity and
     *       compactness are NaN. Pins the degenerate branch, which is exactly
     *       the kind of case two implementations disagree about.</li>
     *   <li><b>hollow_shell</b> — 12x12x12, label 1 fills [2,8]^3 with [3,7]^3
     *       removed. Interior cavity faces are exposed and must be counted;
     *       an implementation that only walks the outer boundary gets this
     *       wrong and gets the solid cube right.</li>
     *   <li><b>two_objects_anisotropic</b> — 20x20x6, calibration
     *       0.2 x 0.2 x 1.0 micron. Label 1 fills [1,5]x[1,5]x[0,2], label 2
     *       fills [10,16]x[10,14]x[2,5]. Two labels at once, with strongly
     *       anisotropic voxels: the calibrated Surface column must respond to
     *       the anisotropy while sphericity and compactness must not.</li>
     *   <li><b>intensity_ramp</b> — the cube fixture with a float intensity
     *       image, value {@code x + 10*y + 100*z}. Drives Mean, StdDev, Min,
     *       Max, IntDen and the centre-of-mass columns with values that differ
     *       along all three axes, so a transposed index shows up.</li>
     * </ul>
     */
    private static Fixture[] buildFixtures() {
        return new Fixture[]{
                new Fixture("cube", cube(), null),
                new Fixture("single_voxel", singleVoxel(), null),
                new Fixture("hollow_shell", hollowShell(), null),
                new Fixture("two_objects_anisotropic", twoObjectsAnisotropic(), null),
                new Fixture("intensity_ramp", cube(), intensityRamp()),
        };
    }

    private static ImagePlus cube() {
        ImagePlus imp = shortStack("cube", 16, 16, 8);
        fill(imp, 1, 2, 7, 2, 7, 1, 6);
        return imp;
    }

    private static ImagePlus singleVoxel() {
        ImagePlus imp = shortStack("single_voxel", 8, 8, 4);
        fill(imp, 1, 3, 3, 3, 3, 1, 1);
        return imp;
    }

    private static ImagePlus hollowShell() {
        ImagePlus imp = shortStack("hollow_shell", 12, 12, 12);
        fill(imp, 1, 2, 8, 2, 8, 2, 8);
        fill(imp, 0, 3, 7, 3, 7, 3, 7);
        return imp;
    }

    private static ImagePlus twoObjectsAnisotropic() {
        ImagePlus imp = shortStack("two_objects_anisotropic", 20, 20, 6);
        fill(imp, 1, 1, 5, 1, 5, 0, 2);
        fill(imp, 2, 10, 16, 10, 14, 2, 5);
        Calibration cal = new Calibration();
        cal.pixelWidth = 0.2;
        cal.pixelHeight = 0.2;
        cal.pixelDepth = 1.0;
        cal.setUnit("micron");
        imp.setCalibration(cal);
        return imp;
    }

    private static ImagePlus intensityRamp() {
        ImageStack stack = new ImageStack(16, 16);
        for (int z = 0; z < 8; z++) {
            FloatProcessor ip = new FloatProcessor(16, 16);
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    ip.setf(x, y, (float) (x + 10 * y + 100 * z));
                }
            }
            stack.addSlice(ip);
        }
        ImagePlus imp = new ImagePlus("intensity_ramp", stack);
        imp.setDimensions(1, 8, 1);
        return imp;
    }

    private static ImagePlus shortStack(String title, int width, int height, int depth) {
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            stack.addSlice(new ShortProcessor(width, height));
        }
        ImagePlus imp = new ImagePlus(title, stack);
        imp.setDimensions(1, depth, 1);
        return imp;
    }

    /** Inclusive bounds, so the spec in the javadoc reads the way the code runs. */
    private static void fill(ImagePlus imp, int label,
                             int x0, int x1, int y0, int y1, int z0, int z1) {
        ImageStack stack = imp.getStack();
        for (int z = z0; z <= z1; z++) {
            ij.process.ImageProcessor ip = stack.getProcessor(z + 1);
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    ip.setf(x, y, label);
                }
            }
        }
    }
}
