package sc.fiji.oc3dsd.runtime;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * How a model reference becomes a file, and what happens when it cannot.
 *
 * <p>{@code bundledModel()} is not exercised here: it extracts a resource from
 * the StarDist jar, which is a {@code provided}-scope dependency and is not on
 * the test classpath. Everything that does not need that resource is, including
 * every path a user-supplied model takes.
 */
public class ModelResolverTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    // ------------------------------------------------------------------
    // displayName - reaches the log, the summary and the batch manifest
    // ------------------------------------------------------------------

    @Test
    public void displayNameDescribesTheBundledModelForEveryFormOfItsReference() {
        String bundled = "versatile fluorescence (bundled)";
        assertEquals(bundled, ModelResolver.displayName(null));
        assertEquals(bundled, ModelResolver.displayName(""));
        assertEquals(bundled, ModelResolver.displayName("   "));
        assertEquals(bundled, ModelResolver.displayName(ModelResolver.BUNDLED_MODEL_KEY));
        assertEquals("the key is matched case-insensitively",
                bundled, ModelResolver.displayName("VERSATILE_FLUO"));
    }

    /**
     * A custom model shows its file name, not its path. The manifest already
     * carries the full path in its own column, and a directory prefix in a log
     * line is noise that varies between machines.
     */
    @Test
    public void displayNameOfACustomModelIsItsFileNameAlone() {
        assertEquals("my_model.zip",
                ModelResolver.displayName(new File(temp.getRoot(), "my_model.zip").getAbsolutePath()));
    }

    // ------------------------------------------------------------------
    // validate - null means usable, anything else is the reason
    // ------------------------------------------------------------------

    @Test
    public void validateAcceptsAWellFormedModel() throws IOException {
        assertNull(ModelResolver.validate(modelZip("good.zip", "saved_model.pb")));
    }

    @Test
    public void validateReportsWhyRatherThanJustFailing() throws IOException {
        assertEquals("no file given", ModelResolver.validate(null));
        assertEquals("file does not exist",
                ModelResolver.validate(new File(temp.getRoot(), "absent.zip")));

        String notAModel = ModelResolver.validate(modelZip("other.zip", "readme.txt"));
        assertNotNull("a zip that is not a model must be reported", notAModel);
        assertTrue("the reason should say what is wrong, was: " + notAModel,
                notAModel.contains("not a StarDist model archive"));
    }

    /**
     * {@code validate} is called from the dialog on every keystroke-driven
     * revalidation, so it must return a reason rather than throw — a thrown
     * exception there closes the dialog.
     */
    @Test
    public void validateNeverThrowsForUnreadableInput() throws IOException {
        File corrupt = temp.newFile("corrupt.zip");
        FileOutputStream out = new FileOutputStream(corrupt);
        try {
            out.write("not a zip".getBytes("UTF-8"));
        } finally {
            out.close();
        }

        String reason = ModelResolver.validate(corrupt);

        assertNotNull("an unreadable file is a reason, not an exception", reason);
        assertTrue("the reason should say it could not be read, was: " + reason,
                reason.contains("could not be read"));
    }

    // ------------------------------------------------------------------
    // resolve
    // ------------------------------------------------------------------

    @Test
    public void resolveReturnsAUserSuppliedModelUnchanged() throws IOException {
        File model = modelZip("mine.zip", "model.tf/saved_model.pb");

        assertEquals(model.getAbsolutePath(),
                ModelResolver.resolve(model.getAbsolutePath()).getAbsolutePath());
    }

    @Test
    public void resolveNamesTheMissingPathRatherThanFailingVaguely() {
        File absent = new File(temp.getRoot(), "absent.zip");
        try {
            ModelResolver.resolve(absent.getAbsolutePath());
            fail("expected a rejection for a missing model");
        } catch (IllegalArgumentException expected) {
            assertTrue("the message must carry the path the user typed, was: "
                            + expected.getMessage(),
                    expected.getMessage().contains(absent.getAbsolutePath()));
        }
    }

    @Test
    public void resolveExplainsWhyAnExistingFileIsNotUsable() throws IOException {
        File notAModel = modelZip("other.zip", "readme.txt");
        try {
            ModelResolver.resolve(notAModel.getAbsolutePath());
            fail("expected a rejection for a zip that is not a model");
        } catch (IllegalArgumentException expected) {
            assertTrue("the message should name the file, was: " + expected.getMessage(),
                    expected.getMessage().contains("other.zip"));
            assertTrue("the message should carry the underlying reason, was: "
                            + expected.getMessage(),
                    expected.getMessage().contains("not a usable StarDist model"));
        }
    }

    // ------------------------------------------------------------------

    private File modelZip(String name, String... entries) throws IOException {
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
