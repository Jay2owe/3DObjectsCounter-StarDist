package sc.fiji.oc3dsd.packaging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * What the packaged jar actually contains.
 * <p>
 * Shading is configuration that fails <em>silently</em>. A wrong Maven
 * coordinate in {@code <artifactSet>} matches nothing, the build succeeds, and
 * the jar ships without the core classes — surfacing as a
 * {@code NoClassDefFoundError} the first time a user runs the plugin in Fiji. A
 * relocation that half-applies ships both copies and lets the classpath decide
 * which one wins. Neither is visible to the unit tests, which run against
 * {@code target/classes} and never see a jar at all.
 * <p>
 * So the claims made by the shade configuration are checked here against the
 * artifact, after it is built. This runs under failsafe rather than surefire for
 * the obvious reason: during the test phase the jar does not exist yet.
 * <p>
 * <strong>Both halves of the relocation matter.</strong> Confirming the
 * relocated package is present proves nothing on its own — the check that earns
 * its keep is that the original package is <em>absent</em>, in entry names and
 * in bytecode alike.
 */
public class ShadedJarIT {

    /**
     * The package core is compiled in.
     * <p>
     * Written split so this constant is not itself a literal that a future
     * relocation of the test jar could rewrite, and so a careless grep for the
     * package name does not match the checker that exists to police it.
     */
    private static final String CORE_PACKAGE = "sc.fiji." + "oc3d.core";

    private static final String CORE_PATH = CORE_PACKAGE.replace('.', '/');

    private static final String SHADED_PACKAGE = "sc.fiji.oc3dsd.internal.core";

    private static final String SHADED_PATH = SHADED_PACKAGE.replace('.', '/');

    /** The plugin's own package, which is never relocated. */
    private static final String OWN_PATH = "sc/fiji/oc3dsd/";

    /**
     * The core types this plugin calls by name.
     * <p>
     * A count of relocated entries would pass on a jar containing the wrong
     * seventy classes. These are the ones whose absence breaks a run, so they
     * are named.
     */
    private static final List<String> REQUIRED_CORE_CLASSES = Arrays.asList(
            "measure/LabelFeatureAccumulator",
            "map/ObjectMapBuilder",
            "label/LabelRenumberer",
            "progress/StatusBarProgress",
            "io/CsvWriter",
            "ui/DialogModel",
            "macro/MacroOptions",
            "macro/MacroFilters",
            "api/MorphPredicate");

    /**
     * Property keys core declares, which must read the same in every shaded
     * copy.
     * <p>
     * These are the reason the relocation carries an exclusion. maven-shade
     * rewrites string constants that parse as class names, so without it all
     * five are silently renamed into the relocated package and every one of them
     * stops answering to the name core documents.
     */
    private static final List<String> PROPERTY_KEYS = Arrays.asList(
            CORE_PACKAGE + ".maxDenseLabel",
            CORE_PACKAGE + ".maxOverlayLabels",
            CORE_PACKAGE + ".overlaySkipped",
            CORE_PACKAGE + ".overlaySkippedReason",
            CORE_PACKAGE + ".optionalMapMemoryReserveBytes");

    private static File jarFile;
    private static JarFile jar;
    private static Set<String> entries;
    /** Class entry name -> its bytes decoded byte-for-char, for literal search. */
    private static Map<String, String> classText;

    @BeforeClass
    public static void openTheJar() throws IOException {
        String configured = System.getProperty("oc3dsd.shadedJar");
        assertTrue("oc3dsd.shadedJar is not set; run this through `mvn verify`,"
                + " which hands the test the exact artifact to inspect",
                configured != null && !configured.trim().isEmpty());

        jarFile = new File(configured.trim());
        assertTrue("packaged jar not found at " + jarFile, jarFile.isFile());

        jar = new JarFile(jarFile);
        Set<String> names = new LinkedHashSet<String>();
        Map<String, String> text = new LinkedHashMap<String, String>();
        java.util.Enumeration<java.util.jar.JarEntry> e = jar.entries();
        while (e.hasMoreElements()) {
            ZipEntry entry = e.nextElement();
            names.add(entry.getName());
            if (entry.getName().endsWith(".class")) {
                text.put(entry.getName(), readAsLatin1(entry));
            }
        }
        entries = Collections.unmodifiableSet(names);
        classText = Collections.unmodifiableMap(text);
    }

    @AfterClass
    public static void closeTheJar() throws IOException {
        if (jar != null) jar.close();
    }

    /**
     * Byte-for-char, not UTF-8.
     * <p>
     * A class file is not text and decoding it as UTF-8 would replace every
     * invalid sequence with U+FFFD, which can swallow the very bytes being
     * looked for. ISO-8859-1 maps all 256 byte values to distinct characters, so
     * searching for an ASCII needle in the result is exact.
     */
    private static String readAsLatin1(ZipEntry entry) throws IOException {
        InputStream in = jar.getInputStream(entry);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return new String(out.toByteArray(), "ISO-8859-1");
        } finally {
            in.close();
        }
    }

    // ------------------------------------------------------------------
    // The relocation, both halves
    // ------------------------------------------------------------------

    /** The include is a Maven coordinate; getting it wrong bundles nothing. */
    @Test
    public void coreIsActuallyBundled() {
        List<String> missing = new ArrayList<String>();
        for (String required : REQUIRED_CORE_CLASSES) {
            String expected = SHADED_PATH + "/" + required + ".class";
            if (!entries.contains(expected)) missing.add(expected);
        }
        assertTrue("the jar is missing core classes this plugin calls by name,"
                + " which is what a wrong <artifactSet> coordinate looks like"
                + " (it matches nothing and the build still succeeds): " + missing,
                missing.isEmpty());
    }

    /**
     * The half that earns its keep: the original package is gone.
     * <p>
     * If both copies ship, which one loads is up to the classpath, and two
     * plugins bundling different core versions stop being independent.
     */
    @Test
    public void theOriginalPackageIsAbsentFromTheJarLayout() {
        Set<String> survivors = new TreeSet<String>();
        for (String name : entries) {
            if (name.startsWith(CORE_PATH + "/")) survivors.add(name);
        }
        assertEquals("entries still under the unrelocated core package: " + survivors,
                0, survivors.size());
    }

    /**
     * And gone from the bytecode, not just from the entry names.
     * <p>
     * Internal names and type descriptors carry the slashed form, so a reference
     * the relocation failed to rewrite shows up here even though the class it
     * points at was moved — a jar that unzips correctly and still throws
     * {@code NoClassDefFoundError} on first use.
     */
    @Test
    public void noBytecodeStillReferencesTheOriginalPackage() {
        List<String> offenders = new ArrayList<String>();
        for (Map.Entry<String, String> entry : classText.entrySet()) {
            if (entry.getValue().indexOf(CORE_PATH) >= 0) offenders.add(entry.getKey());
        }
        assertTrue("classes still referencing " + CORE_PATH + " in their constant"
                + " pool, so the relocation only half-applied: " + offenders,
                offenders.isEmpty());
    }

    /**
     * The documented public Java API stays where it is; relocating it would
     * break every caller.
     * <p>
     * Note the jar holds two packages ending in {@code api}: this one, and
     * core's own, relocated to {@code ...internal.core.api}. Core's belongs
     * there — {@code internal} is what distinguishes them, and keeping a
     * relocated type out of this plugin's published signatures is why
     * {@code MorphPredicate} was kept local rather than adopted from core.
     * Checking for "an api package that moved" would flag that correct
     * relocation, so this names the types instead.
     */
    @Test
    public void thePublicApiIsNotRelocated() {
        List<String> missing = new ArrayList<String>();
        for (String type : Arrays.asList(
                "OC3DSD", "OC3DSDParameters", "OC3DSDResult", "MorphPredicate")) {
            String expected = OWN_PATH + "api/" + type + ".class";
            if (!entries.contains(expected)) missing.add(expected);
        }
        assertTrue("public API types missing from the jar, so either they were"
                + " relocated or they were dropped: " + missing, missing.isEmpty());
    }

    // ------------------------------------------------------------------
    // Property keys: the part relocation gets wrong by default
    // ------------------------------------------------------------------

    /**
     * Each key survives relocation spelled exactly as core declares it.
     * <p>
     * Without the exclusion in the shade configuration every one of these is
     * rewritten into the relocated package, and the failure is entirely silent:
     * a user setting the documented system property gets no error and no effect,
     * and a macro reading the overlay property off a map image gets null.
     */
    @Test
    public void corePropertyKeysSurviveRelocationVerbatim() {
        List<String> missing = new ArrayList<String>();
        for (String key : PROPERTY_KEYS) {
            boolean found = false;
            for (String body : classText.values()) {
                if (body.indexOf(key) >= 0) {
                    found = true;
                    break;
                }
            }
            if (!found) missing.add(key);
        }
        assertTrue("property keys not found spelled as core declares them, so"
                + " relocation renamed them: " + missing, missing.isEmpty());
    }

    /**
     * And the general form of the same check, for keys nobody has added yet.
     * <p>
     * The list above goes stale the moment core declares a sixth property. This
     * one does not: any string in the relocated namespace that does not name a
     * class or package actually present in the jar is a rewritten key, whatever
     * it is called.
     */
    @Test
    public void nothingInTheRelocatedNamespaceIsAStringRatherThanAClass() {
        Set<String> suspects = new TreeSet<String>();
        for (Map.Entry<String, String> entry : classText.entrySet()) {
            String body = entry.getValue();
            int at = body.indexOf(SHADED_PACKAGE + ".");
            while (at >= 0) {
                int end = at;
                while (end < body.length() && isNameChar(body.charAt(end))) end++;
                String candidate = body.substring(at, end);
                if (!namesSomethingInTheJar(candidate)) {
                    suspects.add(candidate + "  (in " + entry.getKey() + ")");
                }
                at = body.indexOf(SHADED_PACKAGE + ".", at + 1);
            }
        }
        assertTrue("strings in the relocated namespace that name nothing in the"
                + " jar — these are property keys the relocation rewrote, and"
                + " they need adding to the <excludes> shape in the shade"
                + " configuration: " + suspects, suspects.isEmpty());
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '$';
    }

    private static boolean namesSomethingInTheJar(String dotted) {
        String path = dotted.replace('.', '/');
        if (entries.contains(path + ".class")) return true;
        for (String name : entries) {
            if (name.startsWith(path + "/") || name.startsWith(path + "$")) return true;
        }
        return false;
    }

    /**
     * The same claim, made by the JVM instead of by a byte search.
     * <p>
     * Loading a relocated class through a real class loader puts it past the
     * bytecode verifier, which is the difference between "the names look right"
     * and "this jar runs" — a half-rewritten constant pool passes a text search
     * and fails here. Reading the key back off the loaded class then answers the
     * question a user actually has: does the property named in core's javadoc
     * still work in the shipped plugin?
     */
    @Test
    public void relocatedClassesLoadAndStillAnswerToCoresPropertyNames() throws Exception {
        // Parent is the platform loader, not the app loader: failsafe already puts
        // the shaded jar on the test classpath, so delegating to the app loader
        // would find these classes there and the test would prove nothing about
        // the file it claims to be inspecting.
        //
        // ij goes in alongside it because that is the environment the jar is
        // built for — core compiles against ImageJ 1.x and nothing else, which
        // is exactly what makes it safe to bundle. If this ever needs a third
        // entry, core has grown a dependency and bundling it has become a
        // heavier decision than it is today.
        URL imagej = ij.ImagePlus.class.getProtectionDomain().getCodeSource().getLocation();
        URLClassLoader loader = new URLClassLoader(
                new URL[] { jarFile.toURI().toURL(), imagej },
                ClassLoader.getSystemClassLoader().getParent());
        try {
            Class<?> maps = loader.loadClass(SHADED_PACKAGE + ".map.ObjectMapBuilder");
            assertEquals("the class came from somewhere other than the jar under test,"
                    + " so this proves nothing about it", loader, maps.getClassLoader());
            assertEquals(CORE_PACKAGE + ".optionalMapMemoryReserveBytes",
                    maps.getField("OPTIONAL_MAP_MEMORY_RESERVE_BYTES_PROPERTY").get(null));
            assertEquals(CORE_PACKAGE + ".overlaySkipped",
                    maps.getField("OVERLAY_SKIPPED_PROPERTY").get(null));

            Class<?> measure = loader.loadClass(SHADED_PACKAGE + ".measure.LabelFeatureAccumulator");
            assertEquals(CORE_PACKAGE + ".maxDenseLabel",
                    measure.getField("MAX_DENSE_LABEL_PROPERTY").get(null));
        } finally {
            loader.close();
        }
    }

    // ------------------------------------------------------------------
    // What else ended up in the jar
    // ------------------------------------------------------------------

    /**
     * Only this plugin and core.
     * <p>
     * TrackMate, TrackMate-StarDist, StarDist and CSBDeep come from update sites
     * and are the user's own copies; bundling any of them would collide with
     * what Fiji already loaded. An {@code <artifactSet>} typo in the other
     * direction — too broad — is caught here.
     */
    @Test
    public void nothingElseWasBundled() {
        Set<String> strangers = new TreeSet<String>();
        for (String name : entries) {
            if (name.endsWith("/")) continue;
            if (name.startsWith(OWN_PATH)) continue;
            if (name.startsWith("META-INF/")) continue;
            if ("plugins.config".equals(name)) continue;
            strangers.add(name);
        }
        assertTrue("unexpected entries in the jar: " + strangers, strangers.isEmpty());
    }

    /**
     * Nothing in the jar needs a newer JVM than the plugin targets.
     * <p>
     * The pom compiles this plugin for Java 8 because that is what a good deal
     * of the Fiji installed base still runs. Bundling a module says nothing
     * about what the module was compiled for, and a core built on a newer JDK
     * would ship class files those users cannot load — not at install time, and
     * not at startup, but the first time the code path is reached.
     * <p>
     * Relocation does not rewrite the class file version, so this is a property
     * of how core was built, which is outside this repository. That is exactly
     * why the check belongs on the artifact.
     */
    @Test
    public void nothingInTheJarNeedsANewerJvmThanThePluginTargets() {
        int javaEight = 52;
        Map<String, Integer> tooNew = new LinkedHashMap<String, Integer>();
        for (Map.Entry<String, String> entry : classText.entrySet()) {
            String body = entry.getValue();
            if (body.length() < 8) continue;
            int major = (body.charAt(6) << 8) | body.charAt(7);
            if (major > javaEight) tooNew.put(entry.getKey(), Integer.valueOf(major));
        }
        assertTrue("class files needing a JVM newer than Java 8 (major " + javaEight
                + "), which the pom targets and much of the Fiji installed base"
                + " still runs: " + tooNew, tooNew.isEmpty());
    }

    /**
     * The dependency-reduced pom is build output and stays in {@code target/}.
     * <p>
     * Shade writes it into the project root by default, where it sits next to
     * {@code pom.xml} looking like something a person wrote and gets committed.
     * Failing here rather than hiding it in {@code .gitignore} is deliberate: if
     * it reappears the configuration has regressed, and that is worth being told
     * about instead of quietly absorbing.
     */
    @Test
    public void theDependencyReducedPomIsNotLeftInTheWorkingTree() {
        String basedir = System.getProperty("oc3dsd.basedir");
        assertTrue("oc3dsd.basedir is not set", basedir != null && !basedir.trim().isEmpty());
        File stray = new File(basedir.trim(), "dependency-reduced-pom.xml");
        assertTrue("shade wrote " + stray + " into the project root; it belongs"
                + " under target/, which is what <dependencyReducedPomLocation> sets",
                !stray.exists());
    }

    /** Core's own Maven metadata is filtered out, leaving one pom in the jar. */
    @Test
    public void onlyThisProjectsMavenMetadataIsPresent() {
        Set<String> poms = new TreeSet<String>();
        for (String name : entries) {
            if (name.startsWith("META-INF/maven/") && name.endsWith("pom.xml")) poms.add(name);
        }
        assertEquals("expected exactly this project's pom under META-INF/maven,"
                + " so provenance has one answer rather than two: " + poms,
                1, poms.size());
    }

    /**
     * Fiji finds the commands through {@code plugins.config}, by class name.
     * <p>
     * It is a plain text file, so no relocation and no compiler ever checks it:
     * a renamed or relocated entry class leaves the menu items pointing at
     * nothing, and the only symptom is that the plugin does not appear.
     */
    @Test
    public void everyClassNamedInPluginsConfigIsInTheJar() throws IOException {
        assertTrue("plugins.config missing from the jar; Fiji would show no menu"
                + " entries at all", entries.contains("plugins.config"));

        ZipEntry entry = jar.getEntry("plugins.config");
        String config = readAsLatin1(entry);
        List<String> missing = new ArrayList<String>();
        int declared = 0;
        for (String line : config.split("\r\n|\r|\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int lastComma = trimmed.lastIndexOf(',');
            if (lastComma < 0) continue;
            String className = trimmed.substring(lastComma + 1).trim();
            int parenthesis = className.indexOf('(');
            if (parenthesis >= 0) className = className.substring(0, parenthesis).trim();
            declared++;
            if (!entries.contains(className.replace('.', '/') + ".class")) missing.add(className);
        }
        assertTrue("plugins.config names classes not in the jar: " + missing, missing.isEmpty());
        assertEquals("expected both commands to be declared", 2, declared);
    }

    /**
     * The manifest survives shading and still records what was bundled.
     * <p>
     * Shading rebuilds the jar from several inputs, each with a manifest of its
     * own, so the project's own entries are not guaranteed to come out the other
     * side.
     */
    @Test
    public void theManifestSurvivesShadingAndNamesTheBundledCore() throws IOException {
        Manifest manifest = jar.getManifest();
        if (manifest == null) fail("the shaded jar has no manifest");
        Attributes main = manifest.getMainAttributes();

        assertEquals("io.github.jay2owe.oc3dsd", main.getValue("Automatic-Module-Name"));

        String coreVersion = System.getProperty("oc3dsd.coreVersion");
        assertTrue("oc3dsd.coreVersion is not set", coreVersion != null && !coreVersion.isEmpty());
        assertEquals("the jar should record the core it carries, since the"
                + " dependency-reduced pom no longer does",
                "io.github.jay2owe:oc3d-core:" + coreVersion,
                main.getValue("Bundled-Dependency"));
    }
}
