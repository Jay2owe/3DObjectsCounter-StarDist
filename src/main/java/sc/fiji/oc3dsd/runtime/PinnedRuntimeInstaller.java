package sc.fiji.oc3dsd.runtime;

import ij.Prefs;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Installs the exact StarDist/TrackMate/TensorFlow runtime tested with this
 * plugin, without requiring the user to configure several overlapping update
 * sites by hand.
 *
 * <p>The manifest deliberately mirrors the runtime used by FLASH. Downloads go
 * to a temporary {@code .download} file, are checked for exact size and SHA-1,
 * and are only then moved into place. Wrong versions are preserved under a
 * dated {@code .disabled-*} name rather than deleted.</p>
 *
 * <p>This class uses only ImageJ 1.x and the JDK. It therefore remains loadable
 * in a normal Fiji installation even when none of the optional detector JARs
 * are present.</p>
 */
public final class PinnedRuntimeInstaller {

    /** Exact payload size of a completely missing runtime (about 159 MiB). */
    public static final long FULL_DOWNLOAD_BYTES = 166582979L;

    private static final Set<String> SCHEDULED_DISABLES =
            Collections.synchronizedSet(new HashSet<String>());

    /** Receives download status. Calls happen on the installing thread. */
    public interface ProgressListener {
        void update(String message, long completedBytes, long totalBytes);
    }

    /** One pinned JAR in the known-working runtime. */
    static final class JarRequirement {
        final String label;
        final String expectedFile;
        final String matchPrefix;
        final String folder;
        final String downloadUrl;
        final String expectedSha1;
        final long expectedBytes;
        final List<String> ignorePrefixes;

        JarRequirement(String label, String expectedFile, String matchPrefix,
                String folder, String downloadUrl, String expectedSha1,
                long expectedBytes, String... ignorePrefixes) {
            this.label = label;
            this.expectedFile = expectedFile;
            this.matchPrefix = matchPrefix;
            this.folder = folder;
            this.downloadUrl = downloadUrl;
            this.expectedSha1 = expectedSha1;
            this.expectedBytes = expectedBytes;
            this.ignorePrefixes = ignorePrefixes == null
                    ? Collections.<String>emptyList()
                    : Arrays.asList(ignorePrefixes);
        }
    }

    /** Current filesystem state of the pinned runtime. */
    public static final class Audit {
        private final List<String> issues;

        Audit(List<String> issues) {
            this.issues = Collections.unmodifiableList(new ArrayList<String>(issues));
        }

        public boolean isSatisfied() {
            return issues.isEmpty();
        }

        public List<String> getIssues() {
            return issues;
        }
    }

    /** Result of one install/repair attempt. */
    public static final class InstallResult {
        private final List<String> actions;
        private final List<String> errors;

        InstallResult(List<String> actions, List<String> errors) {
            this.actions = Collections.unmodifiableList(new ArrayList<String>(actions));
            this.errors = Collections.unmodifiableList(new ArrayList<String>(errors));
        }

        public boolean isSuccessful() {
            return errors.isEmpty();
        }

        public List<String> getActions() {
            return actions;
        }

        public List<String> getErrors() {
            return errors;
        }
    }

    private static final List<JarRequirement> DEFAULT_REQUIREMENTS =
            Collections.unmodifiableList(Arrays.asList(
                    jar("TrackMate", "TrackMate-7.14.0.jar", "TrackMate-", "jars",
                            "https://maven.scijava.org/content/groups/public/sc/fiji/TrackMate/7.14.0/TrackMate-7.14.0.jar",
                            "e39448a98dca2a0d635c2ecf11fb382b59f63c54", 1642752L),
                    jar("TrackMate-StarDist", "TrackMate-StarDist-1.2.1.jar",
                            "TrackMate-StarDist-", "jars",
                            "https://maven.scijava.org/content/groups/public/sc/fiji/TrackMate-StarDist/1.2.1/TrackMate-StarDist-1.2.1.jar",
                            "1d7b62d05f5a22b9de7d5c41b674429237ff42d1", 447824L),
                    jar("StarDist", "StarDist_-0.3.0.jar", "StarDist_-", "plugins",
                            "https://maven.scijava.org/content/groups/public/de/csbdresden/StarDist_/0.3.0-scijava/StarDist_-0.3.0-scijava.jar",
                            "b02ded277ee6c97b9ca5603620645b6658088fdc", 16182299L),
                    jar("CSBDeep", "csbdeep-0.6.0.jar", "csbdeep-", "jars",
                            "https://maven.scijava.org/content/groups/public/de/csbdresden/csbdeep/0.6.0/csbdeep-0.6.0.jar",
                            "5ec7a74917814e7136da6164ff4433ee98cd49f9", 142482L),
                    jar("imagej-tensorflow", "imagej-tensorflow-1.1.5.jar",
                            "imagej-tensorflow-", "jars",
                            "https://maven.scijava.org/content/groups/public/net/imagej/imagej-tensorflow/1.1.5/imagej-tensorflow-1.1.5.jar",
                            "1b9c6163ef064e9d952ac1231fc4cad79f87ab8f", 54134L),
                    jar("TensorFlow proto", "proto-1.15.0.jar", "proto-", "jars",
                            "https://repo1.maven.org/maven2/org/tensorflow/proto/1.15.0/proto-1.15.0.jar",
                            "fec2566e2a9b552885579eb7b48855d37c1a942b", 2882802L,
                            "proto-google-"),
                    jar("protobuf-java", "protobuf-java-3.5.1.jar", "protobuf-java-", "jars",
                            "https://repo1.maven.org/maven2/com/google/protobuf/protobuf-java/3.5.1/protobuf-java-3.5.1.jar",
                            "8c3492f7662fa1cbf8ca76a0f5eb1146f7725acd", 1411071L,
                            "protobuf-java-util-"),
                    jar("TensorFlow core", "tensorflow-1.15.0.jar", "tensorflow-", "jars",
                            "https://repo1.maven.org/maven2/org/tensorflow/tensorflow/1.15.0/tensorflow-1.15.0.jar",
                            "e6a186dca82681e1e28615167e38859b99f82235", 1825L),
                    jar("TensorFlow native library", "libtensorflow-1.15.0.jar",
                            "libtensorflow-", "jars",
                            "https://repo1.maven.org/maven2/org/tensorflow/libtensorflow/1.15.0/libtensorflow-1.15.0.jar",
                            "578c89321585d40dbcf7d038dd6c09f2ec744002", 2103016L),
                    jar("TensorFlow JNI bridge", "libtensorflow_jni-1.15.0.jar",
                            "libtensorflow_jni-", "jars",
                            "https://repo1.maven.org/maven2/org/tensorflow/libtensorflow_jni/1.15.0/libtensorflow_jni-1.15.0.jar",
                            "e749c7ce289ad236914657a11b3c198f35ae5f41", 141714774L)
            ));

    private final List<JarRequirement> requirements;

    public PinnedRuntimeInstaller() {
        this(DEFAULT_REQUIREMENTS);
    }

    PinnedRuntimeInstaller(List<JarRequirement> requirements) {
        this.requirements = Collections.unmodifiableList(
                new ArrayList<JarRequirement>(requirements));
    }

    /** Locates the running Fiji installation without depending on SciJava. */
    public static File resolveFijiDir() {
        String[] candidates = {
                System.getProperty("imagej.dir"),
                System.getProperty("ij.dir"),
                Prefs.getImageJDir(),
                Prefs.getHomeDir()
        };
        for (String candidate : candidates) {
            if (candidate == null || candidate.trim().isEmpty()) continue;
            File dir = new File(candidate.trim());
            if (dir.isDirectory()) return dir;
        }
        return null;
    }

    /** Checks exact versions and duplicate/conflicting JARs in both Fiji folders. */
    public Audit audit(File fijiDir) {
        List<String> issues = new ArrayList<String>();
        if (fijiDir == null || !fijiDir.isDirectory()) {
            issues.add("Could not determine the Fiji.app directory.");
            return new Audit(issues);
        }
        for (JarRequirement requirement : requirements) {
            File requiredDir = new File(fijiDir, requirement.folder);
            File expected = new File(requiredDir, requirement.expectedFile);
            if (!expected.isFile()) {
                issues.add(requirement.label + ": missing " + requirement.folder + "/"
                        + requirement.expectedFile);
            }
            addConflictIssues(issues, fijiDir, requirement, requiredDir);
        }
        return new Audit(issues);
    }

    /**
     * Installs missing pinned JARs and disables incompatible versions.
     * The caller must tell the user to restart Fiji after success.
     */
    public InstallResult install(File fijiDir, ProgressListener listener) {
        List<String> actions = new ArrayList<String>();
        List<String> errors = new ArrayList<String>();
        if (!prepareFijiDir(fijiDir, errors)) {
            return new InstallResult(actions, errors);
        }

        long totalBytes = bytesStillNeeded(fijiDir);
        long completedBytes = 0L;
        String dateSuffix = new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date());

        for (JarRequirement requirement : requirements) {
            File requiredDir = new File(fijiDir, requirement.folder);
            File otherDir = new File(fijiDir,
                    "jars".equals(requirement.folder) ? "plugins" : "jars");

            disableConflicts(requiredDir, requirement, true, dateSuffix, actions, errors);
            disableConflicts(otherDir, requirement, false, dateSuffix, actions, errors);

            File expected = new File(requiredDir, requirement.expectedFile);
            if (expected.isFile()) {
                notifyProgress(listener, "Using " + requirement.expectedFile,
                        completedBytes, totalBytes);
                continue;
            }

            notifyProgress(listener, "Downloading " + requirement.label + "...",
                    completedBytes, totalBytes);
            try {
                downloadWithRetries(requirement, expected, listener,
                        completedBytes, totalBytes);
                actions.add("Installed: " + requirement.folder + "/" + requirement.expectedFile);
                completedBytes += requirement.expectedBytes;
            } catch (Exception failure) {
                errors.add("Could not install " + requirement.expectedFile + ": "
                        + safeMessage(failure));
            }
        }

        Audit remaining = audit(fijiDir);
        if (!remaining.isSatisfied()) {
            for (String issue : remaining.getIssues()) {
                if (!isOnlyScheduledConflict(issue)) errors.add(issue);
            }
        }
        notifyProgress(listener, errors.isEmpty() ? "Runtime installed." : "Installation incomplete.",
                totalBytes, totalBytes);
        return new InstallResult(actions, errors);
    }

    private static JarRequirement jar(String label, String expectedFile,
            String matchPrefix, String folder, String url, String sha1,
            long bytes, String... ignorePrefixes) {
        return new JarRequirement(label, expectedFile, matchPrefix, folder,
                url, sha1, bytes, ignorePrefixes);
    }

    private boolean prepareFijiDir(File fijiDir, List<String> errors) {
        if (fijiDir == null || !fijiDir.isDirectory()) {
            errors.add("Could not determine the Fiji.app directory.");
            return false;
        }
        File jars = new File(fijiDir, "jars");
        File plugins = new File(fijiDir, "plugins");
        if (!prepareDirectory(jars, errors) || !prepareDirectory(plugins, errors)) return false;

        File probe = null;
        try {
            probe = File.createTempFile("oc3dsd-write-test-", ".tmp", fijiDir);
            return true;
        } catch (IOException denied) {
            errors.add("Fiji is not writable: " + fijiDir.getAbsolutePath()
                    + ". Move Fiji to a writable folder or run it with permission to update itself.");
            return false;
        } finally {
            if (probe != null && probe.exists()) probe.delete();
        }
    }

    private static boolean prepareDirectory(File dir, List<String> errors) {
        if (dir.isDirectory()) return true;
        if (dir.exists() || !dir.mkdirs()) {
            errors.add("Could not prepare " + dir.getAbsolutePath());
            return false;
        }
        return true;
    }

    private long bytesStillNeeded(File fijiDir) {
        long bytes = 0L;
        for (JarRequirement requirement : requirements) {
            File expected = new File(new File(fijiDir, requirement.folder),
                    requirement.expectedFile);
            if (!expected.isFile()) bytes += requirement.expectedBytes;
        }
        return bytes;
    }

    private static void download(JarRequirement requirement, File destination,
            ProgressListener listener, long alreadyCompleted, long totalBytes) throws Exception {
        File temporary = new File(destination.getParentFile(),
                destination.getName() + ".download");
        URLConnection connection = new URL(requirement.downloadUrl).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("User-Agent", "3DObjectsCounter-StarDist-runtime-installer");

        long downloaded = 0L;
        try (InputStream in = connection.getInputStream();
             FileOutputStream out = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                downloaded += read;
                notifyProgress(listener, "Downloading " + requirement.label + "...",
                        alreadyCompleted + downloaded, totalBytes);
            }
            out.getFD().sync();
        } catch (Exception failure) {
            if (temporary.exists()) temporary.delete();
            throw failure;
        }

        try {
            if (temporary.length() != requirement.expectedBytes) {
                throw new IOException("download size mismatch (expected "
                        + requirement.expectedBytes + " bytes, got " + temporary.length() + ")");
            }
            String actualSha1 = digest(temporary, "SHA-1");
            if (!requirement.expectedSha1.equalsIgnoreCase(actualSha1)) {
                throw new IOException("checksum mismatch (the download may be corrupt)");
            }
            Files.move(temporary.toPath(), destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unavailable) {
            Files.move(temporary.toPath(), destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (temporary.exists()) temporary.delete();
        }
    }

    private static void downloadWithRetries(JarRequirement requirement, File destination,
            ProgressListener listener, long alreadyCompleted, long totalBytes) throws Exception {
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                download(requirement, destination, listener, alreadyCompleted, totalBytes);
                return;
            } catch (Exception failure) {
                lastFailure = failure;
                if (attempt < 3) {
                    notifyProgress(listener, "Retrying " + requirement.label
                            + " (attempt " + (attempt + 1) + " of 3)...",
                            alreadyCompleted, totalBytes);
                }
            }
        }
        throw lastFailure;
    }

    private static String digest(File file, String algorithm) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) {
            String part = Integer.toHexString(value & 0xff);
            if (part.length() == 1) hex.append('0');
            hex.append(part);
        }
        return hex.toString();
    }

    private static void addConflictIssues(List<String> issues, File fijiDir,
            JarRequirement requirement, File requiredDir) {
        File[] dirs = {new File(fijiDir, "jars"), new File(fijiDir, "plugins")};
        for (File dir : dirs) {
            for (File conflict : matchingJars(dir, requirement)) {
                boolean isExpected = dir.equals(requiredDir)
                        && conflict.getName().equals(requirement.expectedFile);
                if (!isExpected) {
                    issues.add(requirement.label + ": conflicting JAR "
                            + relativePath(fijiDir, conflict));
                }
            }
        }
    }

    private static List<File> matchingJars(File dir, JarRequirement requirement) {
        List<File> matches = new ArrayList<File>();
        File[] files = dir.listFiles();
        if (files == null) return matches;
        for (File file : files) {
            String name = file.getName();
            if (!file.isFile() || !name.endsWith(".jar") || isScheduled(file)) continue;
            if (shouldIgnore(name, requirement.ignorePrefixes)) continue;
            if (matchesVersionedJarPrefix(name, requirement.matchPrefix)) matches.add(file);
        }
        Collections.sort(matches);
        return matches;
    }

    private static void disableConflicts(File dir, JarRequirement requirement,
            boolean requiredFolder, String dateSuffix, List<String> actions,
            List<String> errors) {
        for (File file : matchingJars(dir, requirement)) {
            if (requiredFolder && file.getName().equals(requirement.expectedFile)) continue;
            File disabled = uniqueDisabledFile(dir, file.getName(), dateSuffix);
            if (file.renameTo(disabled)) {
                actions.add("Disabled conflicting JAR: " + file.getAbsolutePath());
            } else if (scheduleWindowsDisableAfterExit(file, disabled)) {
                SCHEDULED_DISABLES.add(fileKey(file));
                actions.add("Scheduled conflicting JAR to be disabled when Fiji closes: "
                        + file.getAbsolutePath());
            } else {
                errors.add("Could not disable conflicting JAR: " + file.getAbsolutePath()
                        + ". Close Fiji, rename it so it no longer ends in .jar, then try again.");
            }
        }
    }

    static boolean matchesVersionedJarPrefix(String fileName, String prefix) {
        if (fileName == null || prefix == null || !fileName.startsWith(prefix)
                || fileName.length() <= prefix.length()) return false;
        return Character.isDigit(fileName.charAt(prefix.length()));
    }

    private static boolean shouldIgnore(String name, List<String> prefixes) {
        for (String prefix : prefixes) if (name.startsWith(prefix)) return true;
        return false;
    }

    private static File uniqueDisabledFile(File dir, String name, String dateSuffix) {
        File candidate = new File(dir, name + ".disabled-" + dateSuffix);
        if (!candidate.exists()) return candidate;
        for (int i = 2; i < 1000; i++) {
            candidate = new File(dir, name + ".disabled-" + dateSuffix + "-" + i);
            if (!candidate.exists()) return candidate;
        }
        return new File(dir, name + ".disabled-" + dateSuffix + "-" + System.currentTimeMillis());
    }

    private static boolean scheduleWindowsDisableAfterExit(File source, File disabled) {
        if (!isWindows()) return false;
        try {
            File script = File.createTempFile("oc3dsd-runtime-disable-", ".ps1");
            File log = new File(source.getParentFile(),
                    "3DObjectsCounter-StarDist-runtime-repair.log");
            writeDeferredDisableScript(script);
            List<String> command = Arrays.asList(
                    "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-WindowStyle", "Hidden", "-File", script.getAbsolutePath(),
                    currentProcessId(), source.getAbsolutePath(), disabled.getAbsolutePath(),
                    log.getAbsolutePath());
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
            builder.start();
            return true;
        } catch (Exception failure) {
            return false;
        }
    }

    private static void writeDeferredDisableScript(File script) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(script), StandardCharsets.UTF_8))) {
            writer.write("param([string]$ParentPid,[string]$SourcePath,[string]$DestPath,[string]$LogPath)\n");
            writer.write("$ErrorActionPreference = 'Stop'\n");
            writer.write("function Log([string]$Message) { Add-Content -LiteralPath $LogPath -Value ((Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + ' ' + $Message) -ErrorAction SilentlyContinue }\n");
            writer.write("try {\n");
            writer.write("  Log \"Waiting to disable locked Fiji JAR: $SourcePath\"\n");
            writer.write("  if ($ParentPid -match '^[0-9]+$') { while (Get-Process -Id ([int]$ParentPid) -ErrorAction SilentlyContinue) { Start-Sleep -Seconds 2 } }\n");
            writer.write("  for ($i = 0; $i -lt 300; $i++) {\n");
            writer.write("    if (-not (Test-Path -LiteralPath $SourcePath)) { exit 0 }\n");
            writer.write("    try { Rename-Item -LiteralPath $SourcePath -NewName (Split-Path -Leaf $DestPath) -Force; Log \"Disabled $SourcePath\"; Remove-Item -LiteralPath $MyInvocation.MyCommand.Path -Force -ErrorAction SilentlyContinue; exit 0 } catch { Start-Sleep -Seconds 1 }\n");
            writer.write("  }\n");
            writer.write("  Log \"FAILED to disable $SourcePath after Fiji closed.\"; exit 1\n");
            writer.write("} catch { Log (\"FAILED: \" + $_.Exception.Message); exit 1 }\n");
        }
    }

    private static String currentProcessId() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        if (name == null) return "";
        int at = name.indexOf('@');
        return (at >= 0 ? name.substring(0, at) : name).trim();
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean isScheduled(File file) {
        return SCHEDULED_DISABLES.contains(fileKey(file));
    }

    private static String fileKey(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException ignored) {
            return file.getAbsolutePath();
        }
    }

    private static boolean isOnlyScheduledConflict(String issue) {
        if (issue == null || !issue.contains("conflicting JAR ")) return false;
        String path = issue.substring(issue.indexOf("conflicting JAR ")
                + "conflicting JAR ".length());
        return SCHEDULED_DISABLES.contains(fileKey(new File(path)));
    }

    private static String relativePath(File root, File child) {
        try {
            return root.toPath().relativize(child.toPath()).toString();
        } catch (RuntimeException unrelatedRoots) {
            return child.getAbsolutePath();
        }
    }

    private static void notifyProgress(ProgressListener listener, String message,
            long completed, long total) {
        if (listener != null) listener.update(message, completed, total);
    }

    private static String safeMessage(Throwable failure) {
        if (failure == null || failure.getMessage() == null
                || failure.getMessage().trim().isEmpty()) {
            return failure == null ? "unknown error" : failure.getClass().getSimpleName();
        }
        return failure.getMessage().trim();
    }
}
