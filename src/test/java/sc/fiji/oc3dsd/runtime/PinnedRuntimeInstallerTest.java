package sc.fiji.oc3dsd.runtime;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Collections;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Filesystem-level coverage for the first-run installer, using a local URL. */
public class PinnedRuntimeInstallerTest {

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void installsIntoAnEmptyFijiAndVerifiesTheDownload() throws Exception {
        File fiji = temporary.newFolder("Fiji.app");
        File source = sourceJar("source.jar", 4096);
        PinnedRuntimeInstaller installer = installerFor(source, sha1(source));

        PinnedRuntimeInstaller.InstallResult result = installer.install(fiji, null);

        assertTrue(result.getErrors().toString(), result.isSuccessful());
        File installed = new File(new File(fiji, "jars"), "Example-1.0.0.jar");
        assertTrue(installed.isFile());
        assertArrayEquals(read(source), read(installed));
        assertTrue(installer.audit(fiji).getIssues().toString(),
                installer.audit(fiji).isSatisfied());
    }

    @Test
    public void conflictingVersionsInBothFijiFoldersArePreservedButDisabled()
            throws Exception {
        File fiji = temporary.newFolder("Fiji-conflicts.app");
        File jars = new File(fiji, "jars");
        File plugins = new File(fiji, "plugins");
        assertTrue(jars.mkdir());
        assertTrue(plugins.mkdir());
        File wrongInJars = new File(jars, "Example-2.0.0.jar");
        File wrongInPlugins = new File(plugins, "Example-3.0.0.jar");
        writeBytes(wrongInJars, 1234);
        writeBytes(wrongInPlugins, 1234);
        File source = sourceJar("replacement.jar", 4096);

        PinnedRuntimeInstaller.InstallResult result =
                installerFor(source, sha1(source)).install(fiji, null);

        assertTrue(result.getErrors().toString(), result.isSuccessful());
        assertFalse(wrongInJars.exists());
        assertFalse(wrongInPlugins.exists());
        assertTrue(hasDisabledCopy(jars, "Example-2.0.0.jar.disabled-"));
        assertTrue(hasDisabledCopy(plugins, "Example-3.0.0.jar.disabled-"));
    }

    @Test
    public void corruptDownloadIsNotMovedIntoFijiAndCanBeRetried() throws Exception {
        File fiji = temporary.newFolder("Fiji-corrupt.app");
        File source = sourceJar("corrupt-source.jar", 4096);
        PinnedRuntimeInstaller installer = installerFor(source,
                "0000000000000000000000000000000000000000");

        PinnedRuntimeInstaller.InstallResult result = installer.install(fiji, null);

        assertFalse(result.isSuccessful());
        File destination = new File(new File(fiji, "jars"), "Example-1.0.0.jar");
        assertFalse(destination.exists());
        assertFalse(new File(destination.getParentFile(),
                destination.getName() + ".download").exists());
        assertTrue(result.getErrors().get(0).contains("checksum mismatch"));
    }

    @Test
    public void versionPrefixDoesNotConfuseTrackMateWithTrackMateStarDist() {
        assertTrue(PinnedRuntimeInstaller.matchesVersionedJarPrefix(
                "TrackMate-7.14.0.jar", "TrackMate-"));
        assertFalse(PinnedRuntimeInstaller.matchesVersionedJarPrefix(
                "TrackMate-StarDist-1.2.1.jar", "TrackMate-"));
    }

    private PinnedRuntimeInstaller installerFor(File source, String sha1) throws Exception {
        PinnedRuntimeInstaller.JarRequirement requirement =
                new PinnedRuntimeInstaller.JarRequirement(
                        "Example", "Example-1.0.0.jar", "Example-", "jars",
                        source.toURI().toURL().toString(), sha1, source.length());
        return new PinnedRuntimeInstaller(Collections.singletonList(requirement));
    }

    private File sourceJar(String name, int size) throws Exception {
        File file = temporary.newFile(name);
        writeBytes(file, size);
        return file;
    }

    private static void writeBytes(File file, int size) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            for (int i = 0; i < size; i++) out.write((i * 31 + 7) & 0xff);
        }
    }

    private static boolean hasDisabledCopy(File dir, String prefix) {
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File file : files) if (file.getName().startsWith(prefix)) return true;
        return false;
    }

    private static byte[] read(File file) throws Exception {
        byte[] bytes = new byte[(int) file.length()];
        try (InputStream in = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int count = in.read(bytes, offset, bytes.length - offset);
                if (count < 0) break;
                offset += count;
            }
        }
        return bytes;
    }

    private static String sha1(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(read(file));
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) {
            String part = Integer.toHexString(value & 0xff);
            if (part.length() == 1) hex.append('0');
            hex.append(part);
        }
        return hex.toString();
    }
}
