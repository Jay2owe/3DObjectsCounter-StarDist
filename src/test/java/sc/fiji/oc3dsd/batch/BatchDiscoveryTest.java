package sc.fiji.oc3dsd.batch;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The batch layer uses both discovery routes at once: recursion decides which
 * files are analysed, the regex decides which results are compared. These tests
 * pin that they stay independent, and that a file matching nothing is still
 * analysed rather than silently dropped.
 */
public class BatchDiscoveryTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File tree() throws IOException {
        File root = folder.newFolder("root");
        touch(root, "WT_animal1_LH.tif");
        touch(root, "WT_animal2_RH.tif");
        touch(root, "KO_animal3_LH.tif");
        touch(root, "notes.txt");
        File nested = new File(root, "day2");
        assertTrue(nested.mkdirs());
        touch(nested, "KO_animal4_RH.tif");
        return root;
    }

    private static void touch(File dir, String name) throws IOException {
        File file = new File(dir, name);
        if (!file.createNewFile()) fail("could not create " + file);
    }

    @Test
    public void recursiveScanFindsNestedImagesAndIgnoresOtherFiles() throws IOException {
        File root = tree();

        List<File> found = BatchDiscovery.discover(root, true, null);

        assertEquals(4, found.size());
        for (File file : found) {
            assertTrue(file.getName().endsWith(".tif"));
        }
    }

    @Test
    public void nonRecursiveScanStopsAtTheTopLevel() throws IOException {
        File root = tree();

        List<File> found = BatchDiscovery.discover(root, false, null);

        assertEquals(3, found.size());
    }

    @Test
    public void extensionFilterIsHonoured() throws IOException {
        File root = tree();

        assertEquals(0, BatchDiscovery.discover(root, true, "czi").size());
        assertEquals(4, BatchDiscovery.discover(root, true, ".TIF").size());
    }

    @Test
    public void captureGroupNamesWhatTheFilesShare() throws IOException {
        File root = tree();
        List<File> files = BatchDiscovery.discover(root, true, null);

        List<BatchDiscovery.Item> items =
                BatchDiscovery.group(files, "^(\\w+?)_.*\\.tif$", 1);
        Map<String, List<File>> grouped = BatchDiscovery.byGroup(items);

        assertEquals(2, grouped.size());
        assertEquals(2, grouped.get("WT").size());
        assertEquals(2, grouped.get("KO").size());
    }

    /**
     * Grouping is orthogonal to recursion: KO files live in two different
     * folders and must still land in one group.
     */
    @Test
    public void groupingIsIndependentOfFolderLayout() throws IOException {
        File root = tree();
        List<BatchDiscovery.Item> items = BatchDiscovery.group(
                BatchDiscovery.discover(root, true, null), "^(\\w+?)_.*\\.tif$", 1);

        List<File> ko = BatchDiscovery.byGroup(items).get("KO");

        assertEquals(2, ko.size());
        assertFalse("the two KO files must come from different folders",
                ko.get(0).getParentFile().equals(ko.get(1).getParentFile()));
    }

    @Test
    public void unmatchedFilesAreGroupedSeparatelyNotDiscarded() throws IOException {
        File root = folder.newFolder("odd");
        touch(root, "WT_a.tif");
        touch(root, "stray.tif");

        List<BatchDiscovery.Item> items = BatchDiscovery.group(
                BatchDiscovery.discover(root, false, null), "^(WT)_.*\\.tif$", 1);
        Map<String, List<File>> grouped = BatchDiscovery.byGroup(items);

        assertEquals("both files must survive discovery", 2, items.size());
        assertEquals(1, grouped.get("WT").size());
        assertEquals(1, grouped.get(BatchDiscovery.UNGROUPED).size());
    }

    @Test
    public void blankPatternPutsEverythingInOneGroup() throws IOException {
        File root = tree();

        List<BatchDiscovery.Item> items = BatchDiscovery.group(
                BatchDiscovery.discover(root, true, null), "", 1);

        assertEquals(1, BatchDiscovery.byGroup(items).size());
    }

    @Test
    public void aGroupIndexBeyondThePatternIsUngroupedNotAnError() throws IOException {
        File root = tree();

        List<BatchDiscovery.Item> items = BatchDiscovery.group(
                BatchDiscovery.discover(root, true, null), "^(\\w+?)_.*\\.tif$", 4);

        assertEquals(1, BatchDiscovery.byGroup(items).size());
        assertTrue(BatchDiscovery.byGroup(items).containsKey(BatchDiscovery.UNGROUPED));
    }

    @Test
    public void anInvalidPatternFailsLoudlyBeforeAnythingRuns() {
        try {
            BatchDiscovery.compile("^(unclosed");
            fail("an invalid regex must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("not a valid regular expression"));
        }
    }

    @Test
    public void previewNamesTheGroupsAndFlagsUngroupedFiles() throws IOException {
        File root = folder.newFolder("preview");
        touch(root, "WT_a.tif");
        touch(root, "stray.tif");

        String preview = BatchDiscovery.previewText(
                BatchDiscovery.group(BatchDiscovery.discover(root, false, null),
                        "^(WT)_.*\\.tif$", 1), 3);

        assertTrue(preview.contains("2 image(s) in 2 group(s)"));
        assertTrue(preview.contains("WT"));
        assertTrue(preview.contains(BatchDiscovery.UNGROUPED));
        assertTrue(preview.contains("will still be analysed"));
    }
}
