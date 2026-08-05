package sc.fiji.oc3dsd.runtime;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The clear-once-then-believe-it policy for TensorFlow's crash flag.
 *
 * <p>The policy is a judgement call between two bad outcomes. Never clearing the
 * flag leaves a user permanently unable to run StarDist because of a crash that
 * happened once, in a previous session, possibly for an unrelated reason.
 * Always clearing it makes Fiji crash on every single run when the TensorFlow
 * install is genuinely broken. So: clear it once, and if it comes back, believe
 * it.
 *
 * <p>{@code TensorFlowCrashSentinel} was written with a package-private core
 * taking the directory as an argument, annotated "exercised directly by tests
 * with a temp directory". That test did not exist. This is it — a state machine
 * whose whole purpose is to behave differently on the second occurrence is not
 * something to leave unchecked.
 */
public class TensorFlowCrashSentinelTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void doesNothingWhenThereIsNoSentinel() throws IOException {
        File dir = temp.newFolder("native");

        assertEquals(TensorFlowCrashSentinel.Outcome.NONE,
                TensorFlowCrashSentinel.evaluate(dir));
    }

    @Test
    public void doesNothingWhenTheDirectoryIsAbsentOrNull() {
        assertEquals(TensorFlowCrashSentinel.Outcome.NONE,
                TensorFlowCrashSentinel.evaluate(null));
        assertEquals("a machine without a Fiji install must not be an error",
                TensorFlowCrashSentinel.Outcome.NONE,
                TensorFlowCrashSentinel.evaluate(new File(temp.getRoot(), "absent")));
    }

    @Test
    public void clearsAnOrphanedSentinelAndRecordsThatItDidSo() throws IOException {
        File dir = temp.newFolder("native");
        File sentinel = touch(dir, TensorFlowCrashSentinel.SENTINEL_NAME);

        assertEquals(TensorFlowCrashSentinel.Outcome.CLEARED,
                TensorFlowCrashSentinel.evaluate(dir));

        assertFalse("the stale flag should be gone", sentinel.exists());
        assertTrue("a marker must record that the one automatic clear has been used",
                new File(dir, TensorFlowCrashSentinel.MARKER_NAME).isFile());
    }

    /**
     * The case the whole class exists for: TensorFlow crashed again after the
     * automatic clear, so the crash is real and the flag stays. Clearing it a
     * second time would crash Fiji on every run from then on.
     */
    @Test
    public void leavesASentinelThatReappearsAfterAClear() throws IOException {
        File dir = temp.newFolder("native");
        touch(dir, TensorFlowCrashSentinel.SENTINEL_NAME);
        assertEquals(TensorFlowCrashSentinel.Outcome.CLEARED,
                TensorFlowCrashSentinel.evaluate(dir));

        File reappeared = touch(dir, TensorFlowCrashSentinel.SENTINEL_NAME);

        assertEquals(TensorFlowCrashSentinel.Outcome.REPEATED,
                TensorFlowCrashSentinel.evaluate(dir));
        assertTrue("a repeatable crash keeps its flag", reappeared.isFile());
    }

    /**
     * A successful run re-arms the automatic clear. Without this the policy is
     * clear-once-ever rather than clear-once-per-genuine-orphan, and a user who
     * hit one stale flag in 2024 would never get the automatic repair again.
     */
    @Test
    public void aSuccessfulRunReArmsTheAutomaticClear() throws IOException {
        File dir = temp.newFolder("native");
        touch(dir, TensorFlowCrashSentinel.SENTINEL_NAME);
        assertEquals(TensorFlowCrashSentinel.Outcome.CLEARED,
                TensorFlowCrashSentinel.evaluate(dir));

        TensorFlowCrashSentinel.noteLoadedOk(dir);
        assertFalse("the marker should be gone after a successful load",
                new File(dir, TensorFlowCrashSentinel.MARKER_NAME).isFile());

        // A later, unrelated stale flag is therefore cleared again rather than
        // being mistaken for a repeat of the first one.
        touch(dir, TensorFlowCrashSentinel.SENTINEL_NAME);
        assertEquals(TensorFlowCrashSentinel.Outcome.CLEARED,
                TensorFlowCrashSentinel.evaluate(dir));
    }

    @Test
    public void notingASuccessfulLoadIsSafeWithNothingToRemove() throws IOException {
        TensorFlowCrashSentinel.noteLoadedOk(null);
        TensorFlowCrashSentinel.noteLoadedOk(new File(temp.getRoot(), "absent"));
        TensorFlowCrashSentinel.noteLoadedOk(temp.newFolder("empty"));
        // Reaching here without throwing is the assertion: this runs on the
        // success path of every StarDist run and must never be what breaks it.
    }

    private static File touch(File dir, String name) throws IOException {
        File file = new File(dir, name);
        if (!file.isFile() && !file.createNewFile()) {
            throw new IOException("could not create " + file.getAbsolutePath());
        }
        return file;
    }
}
