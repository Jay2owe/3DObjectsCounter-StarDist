package sc.fiji.oc3dsd.packaging;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.Test;

/**
 * Keeps the Stage 05 relocation audit alive instead of leaving it a date in a
 * document.
 * <p>
 * Shading relocates {@code oc3d-core} into a private package, and a relocator
 * rewrites what it can see: type references, and any string constant that parses
 * as a class name or a resource path. What it cannot see is a name assembled at
 * runtime — {@code Class.forName(prefix + suffix)} keeps looking for the old
 * package and finds nothing.
 * <p>
 * The audit found every name lookup in {@code src/main} pointed at an external
 * package (TrackMate, StarDist, CSBDeep, TensorFlow, the Fiji updater), so
 * relocation is safe here. That conclusion is only true of the code as it was
 * audited, which is what this test pins: it fails when a lookup is added,
 * removed or repointed, and says so.
 * <p>
 * <strong>What it does and does not prove.</strong> It proves no <em>literal</em>
 * in the main source names the relocated package, and that the set of name
 * lookups is the audited one. It cannot evaluate a lookup whose argument is
 * computed — that is precisely why the inventory exists, so a new one has to be
 * looked at by a person. The complementary check on the built artifact is
 * {@link ShadedJarIT}.
 */
public class RelocationAuditTest {

    /** Split so this checker does not match its own search. */
    private static final String CORE_PACKAGE = "sc.fiji." + "oc3d.core";

    private static final String CORE_PATH = CORE_PACKAGE.replace('.', '/');

    /**
     * Every call in {@code src/main} that resolves something by name, and why
     * relocation leaves it alone.
     * <p>
     * Keyed by source file, call and first argument rather than by line number,
     * so an edit elsewhere in the file does not make it fail for no reason.
     */
    private static final Map<String, String> AUDITED = new LinkedHashMap<String, String>();

    static {
        AUDITED.put("DependencyDoctor.java :: Class.forName :: className",
                "the parameter of isPresent(String); every caller passes a"
                        + " Requirement.probeClass, all six of which name TrackMate,"
                        + " StarDist, CSBDeep, imagej-tensorflow or TensorFlow classes");
        AUDITED.put("DependencyDoctor.java :: getMethod :: \"getUpdateSite\"",
                "a method on the Fiji updater's UpdateService, reflected on"
                        + " because imagej-updater is not a dependency");
        AUDITED.put("DependencyDoctor.java :: getMethod :: \"isActive\"",
                "a method on the Fiji updater's UpdateSite, same reason");
        AUDITED.put("ModelResolver.java :: getResourceAsStream :: BUNDLED_MODEL_RESOURCE",
                "models/2D/dsb2018_heavy_augment.zip, a resource inside the"
                        + " StarDist jar, which is never shaded into this one");
        AUDITED.put("TrackMateVersion.java :: Class.forName :: TRACKMATE_CLASS",
                "fiji.plugin.trackmate.TrackMate, external");
        AUDITED.put("TrackMateVersion.java :: getField :: \"PLUGIN_NAME_VERSION\"",
                "a field on TrackMate's own class, not a package name");
        AUDITED.put("TrackMateVersion.java :: getResourceAsStream ::"
                        + " \"/META-INF/maven/sc.fiji/TrackMate/pom.properties\"",
                "TrackMate's Maven descriptor. The only literal in the main source"
                        + " containing \"sc.fiji\", and it survives: it holds slashes,"
                        + " so it is never read as a class name, and as a path it"
                        + " begins META-INF rather than the relocated package");
    }

    /** Calls that resolve something by a name given as text. */
    private static final List<String> LOOKUPS = Arrays.asList(
            "Class.forName(",
            ".loadClass(",
            ".getMethod(",
            ".getDeclaredMethod(",
            ".getField(",
            ".getDeclaredField(",
            ".getConstructor(",
            ".getDeclaredConstructor(",
            ".getResourceAsStream(",
            ".getResource(");

    // ------------------------------------------------------------------

    /**
     * No literal in the main source names the package that gets relocated.
     * <p>
     * A literal there is a coin flip: shade rewrites it when it parses as a
     * class name, which happens to be right, and rewrites it when it is a
     * property key, which is wrong. This plugin reaches core through types, and
     * that is what keeps the question from arising at all.
     */
    @Test
    public void noLiteralInTheMainSourceNamesTheRelocatedPackage() throws IOException {
        Map<String, String> offenders = new TreeMap<String, String>();
        for (File source : mainSources()) {
            String code = stripComments(read(source));
            for (String literal : stringLiterals(code)) {
                if (literal.startsWith(CORE_PACKAGE) || literal.startsWith(CORE_PATH)) {
                    offenders.put(source.getName(), literal);
                }
            }
        }
        assertTrue("string literals naming the relocated package: " + offenders
                + " — shading rewrites these, and whether that is correct depends"
                + " on whether each one is a class name. Reach core through types"
                + " instead.", offenders.isEmpty());
    }

    /**
     * The set of name lookups is still the audited one.
     * <p>
     * Failing here does not mean something is broken. It means the audit no
     * longer describes the code, and the new lookup needs a verdict — is its
     * target external, or does it point somewhere shading will move?
     */
    @Test
    public void everyNameLookupIsOneTheAuditHasSeen() throws IOException {
        Set<String> found = new TreeSet<String>();
        for (File source : mainSources()) {
            found.addAll(lookupsIn(source));
        }

        Set<String> added = new TreeSet<String>(found);
        added.removeAll(AUDITED.keySet());
        Set<String> gone = new TreeSet<String>(AUDITED.keySet());
        gone.removeAll(found);

        StringBuilder message = new StringBuilder();
        if (!added.isEmpty()) {
            message.append("\nname lookups the relocation audit has not seen:");
            for (String entry : added) message.append("\n    ").append(entry);
            message.append("\n  Decide whether the target is external — TrackMate,")
                    .append(" StarDist, CSBDeep, TensorFlow, the Fiji updater — or")
                    .append(" whether shading moves it, then record it in AUDITED")
                    .append(" with the reason.");
        }
        if (!gone.isEmpty()) {
            message.append("\naudited lookups no longer in the source:");
            for (String entry : gone) {
                message.append("\n    ").append(entry)
                        .append("\n        was: ").append(AUDITED.get(entry));
            }
            message.append("\n  Remove them, so the inventory keeps meaning what it says.");
        }
        assertTrue(message.toString(), added.isEmpty() && gone.isEmpty());
    }

    // ------------------------------------------------------------------
    // Reading the source
    // ------------------------------------------------------------------

    private static List<File> mainSources() {
        File root = new File(System.getProperty("user.dir"), "src/main/java");
        assertTrue("main sources not found at " + root, root.isDirectory());
        List<File> found = new ArrayList<File>();
        collect(root, found);
        assertTrue("no .java files under " + root, !found.isEmpty());
        return found;
    }

    private static void collect(File directory, List<File> into) {
        File[] children = directory.listFiles();
        if (children == null) return;
        Arrays.sort(children);
        for (File child : children) {
            if (child.isDirectory()) collect(child, into);
            else if (child.getName().endsWith(".java")) into.add(child);
        }
    }

    private static String read(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    /**
     * Blanks comments, keeps everything else at its original offset.
     * <p>
     * Javadoc in this repository discusses the core package by name constantly,
     * and every one of those mentions would otherwise be reported as a hazard.
     */
    static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                while (i < source.length()
                        && !(source.charAt(i) == '*' && i + 1 < source.length()
                                && source.charAt(i + 1) == '/')) {
                    out.append(source.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < source.length()) {
                    out.append("  ");
                    i += 2;
                }
            } else if (c == '"' || c == '\'') {
                int end = endOfQuoted(source, i);
                out.append(source, i, end);
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Index just past the closing quote of the literal starting at {@code start}. */
    private static int endOfQuoted(String source, int start) {
        char quote = source.charAt(start);
        int i = start + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            i++;
            if (c == quote) break;
            if (c == '\n') break;
        }
        return Math.min(i, source.length());
    }

    static List<String> stringLiterals(String code) {
        List<String> found = new ArrayList<String>();
        int i = 0;
        while (i < code.length()) {
            if (code.charAt(i) == '"') {
                int end = endOfQuoted(code, i);
                String raw = code.substring(i, end);
                if (raw.length() >= 2 && raw.endsWith("\"")) {
                    found.add(raw.substring(1, raw.length() - 1));
                }
                i = end;
            } else {
                i++;
            }
        }
        return found;
    }

    // ------------------------------------------------------------------
    // Finding the lookups
    // ------------------------------------------------------------------

    private static Set<String> lookupsIn(File source) throws IOException {
        String code = stripComments(read(source));
        Set<String> found = new TreeSet<String>();
        for (String lookup : LOOKUPS) {
            int at = code.indexOf(lookup);
            while (at >= 0) {
                int open = at + lookup.length();
                String call = lookup.startsWith(".")
                        ? lookup.substring(1, lookup.length() - 1)
                        : lookup.substring(0, lookup.length() - 1);
                found.add(source.getName() + " :: " + call + " :: "
                        + firstArgument(code, open));
                at = code.indexOf(lookup, at + 1);
            }
        }
        return found;
    }

    /**
     * The first argument of a call whose {@code (} has just been consumed.
     * <p>
     * Stops at a comma outside any nested brackets or quotes, so
     * {@code forName(name, false, loader)} yields {@code name} and a nested call
     * in the first position is not cut in half.
     */
    private static String firstArgument(String code, int afterOpenParenthesis) {
        int depth = 0;
        int i = afterOpenParenthesis;
        StringBuilder argument = new StringBuilder();
        while (i < code.length()) {
            char c = code.charAt(i);
            if (c == '"' || c == '\'') {
                int end = endOfQuoted(code, i);
                argument.append(code, i, end);
                i = end;
                continue;
            }
            if (c == '(' || c == '[' || c == '{') depth++;
            if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) break;
                depth--;
            }
            if (c == ',' && depth == 0) break;
            argument.append(c);
            i++;
        }
        return argument.toString().replaceAll("\\s+", " ").trim();
    }
}
