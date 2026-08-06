package sc.fiji.oc3dsd.batch;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Byte-stable rendering of a whole batch output tree.
 *
 * <p>The equivalence harness's {@code Canon} records tables and images that never
 * reach the disk. This records what a batch run actually leaves behind: which
 * files exist, where, and what is in them. They are separate because they answer
 * separate questions — a measurement can be right while the CSV that carries it
 * is quoted wrongly, and the batch CSVs are the only form in which most users
 * ever see these numbers.
 *
 * <h2>What is normalised, and why each one has to be</h2>
 *
 * <ul>
 *   <li><b>The input and output roots</b> become {@code <IN>} and {@code <OUT>}.
 *       They are temporary directories with a random component, so recording
 *       them would make the golden unreproducible. Path <em>structure</em>
 *       below the root is kept exactly, including separators, because that is
 *       what the folder-key logic produces.</li>
 *   <li><b>{@code elapsed_ms}</b> becomes {@code <ELAPSED>}. It is a duration.</li>
 * </ul>
 *
 * Nothing else is normalised. A file that consistently uses the host platform's
 * line separator is recorded as {@code NATIVE}; mixed or non-native endings are
 * called out explicitly. This preserves the contract without making Windows
 * CRLF goldens fail on Linux, where the same writer correctly emits LF.
 *
 * <p>TIFF outputs are recorded as dimensions plus a digest over pixel bits
 * rather than as file bytes, because TIFF headers carry writer metadata that is
 * not part of what the plugin promises.
 */
final class BatchCanon {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private BatchCanon() {
    }

    static String tree(File outputRoot, File inputRoot) {
        StringBuilder sb = new StringBuilder();
        List<File> files = new ArrayList<File>();
        collect(outputRoot, files);
        List<String> relative = new ArrayList<String>();
        for (File file : files) {
            relative.add(relativise(outputRoot, file));
        }
        Collections.sort(relative);

        sb.append("files=").append(relative.size()).append('\n');
        for (String path : relative) {
            sb.append("  ").append(path).append('\n');
        }
        for (String path : relative) {
            File file = new File(outputRoot, path.replace('/', File.separatorChar));
            sb.append("\n## ").append(path).append('\n');
            if (path.endsWith(".csv") || path.endsWith(".txt")) {
                sb.append(text(file, inputRoot, outputRoot));
            } else if (path.endsWith(".tif")) {
                sb.append(tiff(file));
            } else {
                sb.append("opaque bytes=").append(file.length()).append('\n');
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------

    private static String text(File file, File inputRoot, File outputRoot) {
        String raw;
        try {
            raw = new String(readAll(file), UTF8);
        } catch (IOException unreadable) {
            return "unreadable: " + unreadable.getMessage() + '\n';
        }
        StringBuilder sb = new StringBuilder();
        sb.append("lineEndings=").append(lineEndings(raw)).append('\n');

        String normalised = raw.replace("\r\n", "\n").replace('\r', '\n');
        normalised = replacePath(normalised, inputRoot, "<IN>");
        normalised = replacePath(normalised, outputRoot, "<OUT>");
        normalised = maskElapsed(normalised);

        String[] lines = normalised.split("\n", -1);
        // A trailing newline yields one empty final element; record the count
        // without it but keep the fact that the file ended with a newline.
        int count = lines.length;
        boolean trailingNewline = count > 0 && lines[count - 1].isEmpty();
        if (trailingNewline) count--;
        sb.append("lines=").append(count)
                .append(" trailingNewline=").append(trailingNewline).append('\n');
        for (int i = 0; i < count; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    /** Reports whether line endings are native and consistent on this host. */
    private static String lineEndings(String raw) {
        int crlf = 0;
        int bareLf = 0;
        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) != '\n') continue;
            if (i > 0 && raw.charAt(i - 1) == '\r') crlf++;
            else bareLf++;
        }
        if (crlf > 0 && bareLf > 0) return "MIXED crlf=" + crlf + " lf=" + bareLf;
        if (crlf > 0) return "\r\n".equals(System.lineSeparator())
                ? "NATIVE" : "NON_NATIVE_CRLF";
        if (bareLf > 0) return "\n".equals(System.lineSeparator())
                ? "NATIVE" : "NON_NATIVE_LF";
        return "none";
    }

    private static String replacePath(String text, File root, String token) {
        if (root == null) return text;
        String absolute = root.getAbsolutePath();
        String out = text.replace(absolute, token);
        // The manifest holds native separators; a CSV consumer may also see the
        // forward-slash form on some platforms, so both are covered.
        return out.replace(absolute.replace('\\', '/'), token);
    }

    /**
     * The manifest's {@code elapsed_ms} column, masked by position rather than
     * by value: masking "any integer that looks like a duration" would also
     * swallow object counts, which are exactly what must not be masked.
     *
     * <p>Splitting must respect quoting. The corpus deliberately contains a file
     * name with a comma in it, and a naive {@code split(",")} shifts every cell
     * after it — which masks the wrong column and produces a golden that is
     * wrong in a way nobody would spot by reading it. The determinism check
     * caught exactly that; hence the real parser.
     */
    private static String maskElapsed(String text) {
        String[] lines = text.split("\n", -1);
        List<String> header = splitCsv(lines.length == 0 ? "" : lines[0]);
        int column = header.indexOf("elapsed_ms");
        if (column < 0) return text;

        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) continue;
            List<String> cells = splitCsv(lines[i]);
            if (column >= cells.size() || cells.get(column).isEmpty()) continue;
            cells.set(column, "<ELAPSED>");
            lines[i] = joinCsv(cells);
        }
        return join(lines, "\n");
    }

    /** RFC 4180 field splitting: quoted fields may contain commas and "" escapes. */
    static List<String> splitCsv(String line) {
        List<String> cells = new ArrayList<String>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c != '"') {
                    cell.append(c);
                } else if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    quoted = false;
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString());
        return cells;
    }

    /** Re-quotes with the same rules, so a masked row still round-trips. */
    private static String joinCsv(List<String> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(',');
            String value = cells.get(i);
            boolean quote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                    || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
            sb.append(quote ? "\"" + value.replace("\"", "\"\"") + "\"" : value);
        }
        return sb.toString();
    }

    private static String join(String[] parts, String separator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(separator);
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    private static String tiff(File file) {
        ImagePlus imp = IJ.openImage(file.getAbsolutePath());
        if (imp == null) return "unreadable tiff\n";
        StringBuilder sb = new StringBuilder();
        sb.append("tiff ").append(imp.getWidth()).append('x').append(imp.getHeight())
                .append('x').append(imp.getStackSize())
                .append(" bitDepth=").append(imp.getBitDepth()).append('\n');
        ImageStack stack = imp.getStack();
        MessageDigest digest = sha256();
        for (int slice = 1; slice <= stack.getSize(); slice++) {
            ImageProcessor ip = stack.getProcessor(slice);
            int pixels = ip.getPixelCount();
            for (int i = 0; i < pixels; i++) {
                int bits = Float.floatToIntBits(ip.getf(i));
                digest.update((byte) (bits >>> 24));
                digest.update((byte) (bits >>> 16));
                digest.update((byte) (bits >>> 8));
                digest.update((byte) bits);
            }
        }
        sb.append("pixels_sha256=").append(hex(digest.digest())).append('\n');
        imp.close();
        return sb.toString();
    }

    private static void collect(File directory, List<File> into) {
        File[] children = directory == null ? null : directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) collect(child, into);
            else into.add(child);
        }
    }

    private static String relativise(File root, File file) {
        String rootPath = root.getAbsolutePath();
        String filePath = file.getAbsolutePath();
        String relative = filePath.startsWith(rootPath)
                ? filePath.substring(rootPath.length()) : filePath;
        while (relative.startsWith(File.separator)) relative = relative.substring(1);
        return relative.replace('\\', '/');
    }

    private static byte[] readAll(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JVM spec", impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
