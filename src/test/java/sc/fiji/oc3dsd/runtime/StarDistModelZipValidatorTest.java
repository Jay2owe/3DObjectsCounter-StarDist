package sc.fiji.oc3dsd.runtime;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The gate between a user's {@code .zip} and TensorFlow.
 *
 * <p>Stage 04 keeps every {@code runtime/} class on the grounds that they are
 * hardening earned from real failures. That argument only holds while the
 * hardening is checked: this class rejects zip-slip entry names, absolute paths,
 * drive-letter paths, zip bombs and archives that are not StarDist models at
 * all, and none of it had a test. Untested hardening is the kind that quietly
 * stops working.
 *
 * <p>The archives here are built in the test, so what each case actually
 * contains is on the page rather than in a binary fixture.
 */
public class StarDistModelZipValidatorTest {

    private static final String MARKER_MESSAGE = "Not a StarDist model.";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    // ------------------------------------------------------------------
    // Accepted layouts
    // ------------------------------------------------------------------

    @Test
    public void acceptsATopLevelSavedModel() throws IOException {
        File zip = zip("model.zip", "saved_model.pb", "variables/variables.index");

        StarDistModelZipValidator.Scan scan =
                StarDistModelZipValidator.validate(zip.toPath(), MARKER_MESSAGE);

        assertTrue(scan.hasFileEntry);
        assertEquals("top-level saved_model.pb", scan.marker);
    }

    @Test
    public void acceptsAModelTfLayout() throws IOException {
        File zip = zip("model.zip", "model.tf/saved_model.pb");

        assertEquals("model.tf SavedModel layout",
                StarDistModelZipValidator.validate(zip.toPath(), MARKER_MESSAGE).marker);
    }

    @Test
    public void acceptsASingleWrappingDirectory() throws IOException {
        // What you get from zipping the model folder rather than its contents -
        // the single commonest way a user packages one of these.
        File zip = zip("model.zip", "my_model/saved_model.pb");

        assertEquals("single-directory saved_model.pb",
                StarDistModelZipValidator.validate(zip.toPath(), MARKER_MESSAGE).marker);
    }

    @Test
    public void acceptsCsbDeepMetadataInTheSameDirectory() throws IOException {
        File zip = zip("model.zip", "net/config.json", "net/thresholds.json");

        assertEquals("CSBDeep config.json + thresholds.json",
                StarDistModelZipValidator.validate(zip.toPath(), MARKER_MESSAGE).marker);
    }

    /**
     * The metadata pair only counts when both files sit together. Two unrelated
     * models zipped side by side each contribute one of the pair, and treating
     * that as a valid model hands TensorFlow something it cannot load.
     */
    @Test
    public void rejectsCsbDeepMetadataSplitAcrossDirectories() throws IOException {
        File zip = zip("model.zip", "a/config.json", "b/thresholds.json");

        assertRejected(zip, MARKER_MESSAGE);
    }

    // ------------------------------------------------------------------
    // Safety
    // ------------------------------------------------------------------

    @Test
    public void rejectsAZipSlipEntryName() throws IOException {
        File zip = zip("evil.zip", "saved_model.pb", "../../etc/passwd");

        assertRejected(zip, "unsafe entry path");
    }

    @Test
    public void rejectsAZipSlipHiddenByBackslashesAndDotSegments() throws IOException {
        // Normalisation runs before the check, so the traversal has to be caught
        // after backslashes become slashes and "./" prefixes are stripped.
        File zip = zip("evil.zip", "saved_model.pb", ".\\..\\..\\windows\\system32\\x");

        assertRejected(zip, "unsafe entry path");
    }

    @Test
    public void rejectsAnAbsoluteEntryPath() throws IOException {
        File zip = zip("evil.zip", "saved_model.pb", "/etc/passwd");

        assertRejected(zip, "unsafe entry path");
    }

    @Test
    public void rejectsADriveLetterEntryPath() throws IOException {
        File zip = zip("evil.zip", "saved_model.pb", "C:/windows/system32/x");

        assertRejected(zip, "unsafe entry path");
    }

    // ------------------------------------------------------------------
    // Rejected inputs
    // ------------------------------------------------------------------

    @Test
    public void rejectsAFileThatIsNotAZipByName() throws IOException {
        File notAZip = temp.newFile("model.tif");

        assertRejected(notAZip, "must be .zip files");
    }

    @Test
    public void rejectsAMissingFile() {
        assertRejected(new File(temp.getRoot(), "absent.zip"), "does not exist");
    }

    @Test
    public void rejectsAnEmptyArchive() throws IOException {
        File zip = zip("empty.zip");

        assertRejected(zip, "empty");
    }

    @Test
    public void rejectsADirectoryOnlyArchive() throws IOException {
        // Directory entries are not file entries: an archive of empty folders is
        // as empty as one with nothing in it.
        File zip = temp.newFile("dirs.zip");
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip));
        try {
            out.putNextEntry(new ZipEntry("model/"));
            out.closeEntry();
        } finally {
            out.close();
        }

        assertRejected(zip, "empty");
    }

    @Test
    public void rejectsAZipThatIsNotAStarDistModel() throws IOException {
        File zip = zip("other.zip", "readme.txt", "data/notes.csv");

        assertRejected(zip, MARKER_MESSAGE);
    }

    @Test
    public void rejectsCorruptArchiveContentWithThePathInTheMessage() throws IOException {
        File zip = temp.newFile("corrupt.zip");
        OutputStream out = new FileOutputStream(zip);
        try {
            out.write("this is not a zip file at all".getBytes(UTF8));
        } finally {
            out.close();
        }

        assertRejected(zip, "could not be read");
    }

    /**
     * A rejection has to name the file. These arrive as "I pointed it at my model
     * and it said no", often second-hand, and a message without the path cannot
     * be acted on.
     */
    @Test
    public void everyRejectionNamesTheOffendingFile() throws IOException {
        File zip = zip("other.zip", "readme.txt");
        try {
            StarDistModelZipValidator.validate(zip.toPath(), MARKER_MESSAGE);
            fail("expected a rejection");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
            assertTrue("the message should name the file, was: " + expected.getMessage(),
                    expected.getMessage().contains("other.zip"));
        }
    }

    // ------------------------------------------------------------------

    private void assertRejected(File file, String expectedFragment) {
        try {
            StarDistModelZipValidator.Scan scan =
                    StarDistModelZipValidator.validate(file.toPath(), MARKER_MESSAGE);
            fail("expected rejection containing '" + expectedFragment
                    + "' but the archive was accepted with marker " + scan.marker);
        } catch (IOException expected) {
            assertTrue("expected a message containing '" + expectedFragment
                            + "', was: " + expected.getMessage(),
                    expected.getMessage() != null
                            && expected.getMessage().contains(expectedFragment));
        }
    }

    /** Builds a zip whose entries are exactly the names given, each one byte. */
    private File zip(String name, String... entries) throws IOException {
        File file = temp.newFile(name);
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file));
        try {
            for (String entry : entries) {
                out.putNextEntry(new ZipEntry(entry));
                out.write('x');
                out.closeEntry();
            }
        } finally {
            out.close();
        }
        return file;
    }
}
