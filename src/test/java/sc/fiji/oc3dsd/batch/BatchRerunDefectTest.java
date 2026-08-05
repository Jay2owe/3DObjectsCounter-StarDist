package sc.fiji.oc3dsd.batch;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A batch run must not consume its own previous output.
 *
 * <h2>The situation</h2>
 *
 * Leaving the output folder blank in the batch dialog sets the output root to
 * the input root ({@code ObjectsCounter3DStarDistBatch:133}), which is the
 * obvious thing for a user to do. Discovery is recursive by default and
 * {@code saveLabels} defaults to {@code true}, so the first run writes
 * {@code <input>/3D Objects Counter - StarDist/Labels/*_labels.tif}.
 *
 * <p>Those are {@code .tif} files inside the input tree. Without an exclusion,
 * the second run over the same folder discovers them and measures them as though
 * they were new images — inflating the object count, adding rows to every
 * aggregate, and doing it silently, because a label image is a perfectly valid
 * input that produces perfectly plausible numbers.
 *
 * <p>It compounds: the third run consumes the second run's labels as well.
 *
 * <p>This is the property {@code oc3d-core}'s {@code BatchFileDiscovery} was
 * built with — it takes the output directory and excludes it with all of its
 * descendants — and reconciling this plugin's discovery against core is what
 * surfaced it.
 */
public class BatchRerunDefectTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void rerunningIntoTheInputFolderDoesNotConsumeTheFirstRunsOutput()
            throws IOException {
        File root = temp.newFolder("in-place");
        BatchHarness.buildMinimalCorpus(root);
        int inputImages = countTiffs(root);
        assertTrue("the corpus should contain images", inputImages >= 2);

        // Output left blank, as the dialog allows: it lands inside the input tree.
        BatchRunner.Outcome first = BatchHarness.runInPlace(root, true);
        assertEquals("every input should be processed on the first run",
                inputImages, first.imagesProcessed);
        assertTrue("the first run should have written label images into the input tree",
                countTiffs(root) > inputImages);

        BatchRunner.Outcome second = BatchHarness.runInPlace(root, true);

        assertEquals("the second run processed more images than the folder contains, "
                        + "so it consumed the first run's output. Every aggregate, count and "
                        + "summary from that run is wrong, and nothing says so.",
                inputImages, second.imagesProcessed);
        assertEquals("object totals must not grow on a re-run over unchanged inputs",
                first.totalObjects, second.totalObjects);
    }

    /** Counts {@code .tif} files anywhere below {@code root}. */
    private static int countTiffs(File root) {
        File[] entries = root.listFiles();
        if (entries == null) return 0;
        int count = 0;
        for (File entry : entries) {
            if (entry.isDirectory()) count += countTiffs(entry);
            else if (entry.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".tif")) count++;
        }
        return count;
    }
}
