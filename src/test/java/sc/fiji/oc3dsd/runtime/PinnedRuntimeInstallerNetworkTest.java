package sc.fiji.oc3dsd.runtime;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Opt-in end-to-end check of every pinned URL, byte count and checksum. */
public class PinnedRuntimeInstallerNetworkTest {

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void installsTheCompleteManifestIntoAnEmptyFiji() throws Exception {
        Assume.assumeTrue("enable with -Doc3dsd.runtimeNetworkTest=true",
                Boolean.getBoolean("oc3dsd.runtimeNetworkTest"));
        File fiji = temporary.newFolder("empty-Fiji.app");
        PinnedRuntimeInstaller installer = new PinnedRuntimeInstaller();

        PinnedRuntimeInstaller.InstallResult result = installer.install(fiji, null);

        assertTrue(result.getErrors().toString(), result.isSuccessful());
        assertTrue(installer.audit(fiji).getIssues().toString(),
                installer.audit(fiji).isSatisfied());
        assertEquals(PinnedRuntimeInstaller.FULL_DOWNLOAD_BYTES,
                totalJarBytes(new File(fiji, "jars"))
                        + totalJarBytes(new File(fiji, "plugins")));
    }

    private static long totalJarBytes(File dir) {
        long total = 0L;
        File[] files = dir.listFiles();
        if (files == null) return total;
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".jar")) total += file.length();
        }
        return total;
    }
}
