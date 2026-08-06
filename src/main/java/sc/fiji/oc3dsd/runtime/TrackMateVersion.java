package sc.fiji.oc3dsd.runtime;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Properties;

/**
 * Reads the installed TrackMate version and refuses to run against one this
 * plugin was never built for.
 * <p>
 * This plugin drives TrackMate through its custom-detector API: it installs a
 * {@code StarDistCustomDetectorFactory}, fills a settings map with detector
 * keys, and calls {@code execDetection}. TrackMate 8 reorganised exactly that
 * surface — detectors and segmenters became different module types — so a v8
 * install does not fail at load time with a clean {@code NoClassDefFoundError};
 * it fails part-way through {@code execDetection()}, after the model has been
 * loaded and the first slices have been detected, with an error that points at
 * TrackMate internals rather than at the real cause. TrackMate 8 also requires
 * Java 21 and Fiji-Latest, so an install that has it is a different Fiji from
 * the one this plugin targets.
 * <p>
 * A {@code TrackMate-StarDist-2.0.0.jar.disabled-…} in a Fiji installation is
 * the fingerprint of someone having hit this and backed it out by hand.
 * <p>
 * Version reading is reflective and defensive, so this class stays loadable
 * when TrackMate is absent entirely and the parsing can be tested without it.
 * When the version cannot be determined the check passes: an unreadable version
 * string is not evidence of an incompatible one, and the {@code LinkageError}
 * handling downstream remains as the backstop.
 */
public final class TrackMateVersion {

    /** The version the project compiles and is tested against. */
    public static final String TESTED_AGAINST = "7.14.0";

    /** Major versions known to work. TrackMate 8 breaks the detector API. */
    public static final int SUPPORTED_MAJOR = 7;

    private static final String TRACKMATE_CLASS = "fiji.plugin.trackmate.TrackMate";

    private TrackMateVersion() {
    }

    /**
     * The installed TrackMate version, or {@code null} when TrackMate is absent
     * or does not say.
     * <p>
     * Three sources are tried in decreasing order of reliability. The jar
     * manifest is authoritative and is what Fiji's own jars carry. The
     * {@code PLUGIN_NAME_VERSION} field is TrackMate's own runtime answer —
     * note the field is {@code PLUGIN_NAME_VERSION}, not {@code VERSION}, which
     * does not exist. The Maven descriptor inside the jar is the last resort.
     */
    public static String installed() {
        Class<?> trackMate;
        try {
            trackMate = Class.forName(TRACKMATE_CLASS, false,
                    TrackMateVersion.class.getClassLoader());
        } catch (ClassNotFoundException absent) {
            return null;
        } catch (LinkageError broken) {
            return null;
        }

        String fromManifest = fromPackage(trackMate);
        if (fromManifest != null) return fromManifest;

        String fromField = fromPluginNameVersion(trackMate);
        if (fromField != null) return fromField;

        return fromMavenDescriptor(trackMate);
    }

    /**
     * A message explaining why the installed TrackMate cannot be used, or
     * {@code null} when it can.
     */
    public static String problem() {
        return problemFor(installed());
    }

    /**
     * The version check itself, separated from how the version was obtained so
     * it can be exercised without TrackMate on the classpath.
     *
     * @param version a raw version string, possibly {@code null} or malformed
     * @return the message to show the user, or {@code null} when the version is
     *         acceptable or could not be understood
     */
    static String problemFor(String version) {
        int major = majorOf(version);
        if (major < 0) return null;
        if (major == SUPPORTED_MAJOR) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("3D Objects Counter - StarDist cannot run with TrackMate ")
          .append(version.trim()).append(".\n\n");
        if (major > SUPPORTED_MAJOR) {
            sb.append("TrackMate ").append(major)
              .append(" changed the custom detector and segmenter module API that this\n")
              .append("plugin depends on, and requires Java 21 and Fiji-Latest. Detection\n")
              .append("would fail part-way through a run rather than cleanly at startup.\n\n");
        } else {
            sb.append("This plugin needs the TrackMate ").append(SUPPORTED_MAJOR)
              .append(".x detector API, which TrackMate ").append(major)
              .append(" predates.\n\n");
        }
        sb.append("Tested against TrackMate ").append(TESTED_AGAINST)
          .append(" with TrackMate-StarDist 1.2.1.\n")
          .append("Run this plugin interactively and choose Install Runtime to repair ")
          .append("the pinned versions, then restart Fiji.");
        return sb.toString();
    }

    /**
     * The leading integer of a version string, or {@code -1} when there is
     * none. Tolerates a {@code TrackMate v7.14.0} style prefix, and
     * {@code -SNAPSHOT} and similar suffixes.
     */
    static int majorOf(String version) {
        if (version == null) return -1;
        String text = version.trim();
        if (text.isEmpty()) return -1;

        int i = 0;
        while (i < text.length() && !isDigit(text.charAt(i))) i++;
        if (i == text.length()) return -1;

        int start = i;
        while (i < text.length() && isDigit(text.charAt(i))) i++;

        // Only the first number in the string counts, and only when it really
        // is the version's major component: "TrackMate v7.14" is 7, but a bare
        // word followed by digits that are not a version is not worth guessing
        // at, so require the run to be short enough to be a major version.
        String digits = text.substring(start, i);
        if (digits.length() > 4) return -1;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException tooLarge) {
            return -1;
        }
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static String fromPackage(Class<?> trackMate) {
        Package pkg = trackMate.getPackage();
        if (pkg == null) return null;
        return blankToNull(pkg.getImplementationVersion());
    }

    private static String fromPluginNameVersion(Class<?> trackMate) {
        try {
            Field field = trackMate.getField("PLUGIN_NAME_VERSION");
            Object value = field.get(null);
            return value == null ? null : blankToNull(value.toString());
        } catch (NoSuchFieldException noSuchField) {
            return null;
        } catch (IllegalAccessException denied) {
            return null;
        } catch (RuntimeException unexpected) {
            return null;
        } catch (LinkageError broken) {
            return null;
        }
    }

    private static String fromMavenDescriptor(Class<?> trackMate) {
        InputStream in = trackMate.getResourceAsStream(
                "/META-INF/maven/sc.fiji/TrackMate/pom.properties");
        if (in == null) return null;
        try {
            Properties properties = new Properties();
            properties.load(in);
            return blankToNull(properties.getProperty("version"));
        } catch (Exception unreadable) {
            return null;
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
                // Nothing useful to do; the version simply stays unknown.
            }
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
