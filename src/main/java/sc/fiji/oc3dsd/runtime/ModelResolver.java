package sc.fiji.oc3dsd.runtime;

import ij.IJ;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Finds the StarDist model file to run with.
 * <p>
 * Uses the fluorescence models shipped inside the StarDist jar or an imported
 * model archive. Model references remain portable macro keys or file paths.
 * <p>
 * A user-supplied zip is validated structurally before it is handed to
 * TensorFlow, because an invalid one otherwise surfaces as an opaque native
 * error that has been recurring on the image.sc forum since 2020.
 */
public final class ModelResolver {

    /** Key naming the bundled model in macro options. */
    public static final String BUNDLED_MODEL_KEY = "versatile_fluo";
    public static final String DSB2018_MODEL_KEY = "dsb2018";

    /** Where the model lives inside the StarDist jar. */
    private static final String BUNDLED_MODEL_RESOURCE = "models/2D/dsb2018_heavy_augment.zip";
    private static final Map<String, File> cachedModels = new HashMap<String, File>();

    private ModelResolver() {
    }

    /**
     * Resolves a model reference to a file on disk.
     *
     * @param modelRef {@link #BUNDLED_MODEL_KEY}, blank, or a path to a {@code .zip}
     * @throws IllegalArgumentException if a user-supplied path is missing or not a valid model
     */
    public static File resolve(String modelRef) {
        String ref = modelRef == null ? "" : modelRef.trim();
        if (isBuiltin(ref)) {
            return bundledModel(ref);
        }
        File file = new File(ref);
        if (!file.isFile()) {
            throw new IllegalArgumentException(
                    "StarDist model file not found: " + file.getAbsolutePath());
        }
        String problem = validate(file);
        if (problem != null) {
            throw new IllegalArgumentException(
                    "'" + file.getName() + "' is not a usable StarDist model: " + problem);
        }
        return file;
    }

    /**
     * Structural check on a model zip. Returns {@code null} when it looks
     * usable, otherwise a human-readable reason.
     */
    public static String validate(File modelZip) {
        if (modelZip == null) return "no file given";
        if (!modelZip.isFile()) return "file does not exist";
        try {
            StarDistModelZipValidator.validate(modelZip.toPath(),
                    "it is not a StarDist model archive");
            return null;
        } catch (IllegalArgumentException invalid) {
            return invalid.getMessage();
        } catch (IOException unreadable) {
            return "the file could not be read (" + unreadable.getMessage() + ")";
        } catch (RuntimeException unexpected) {
            return String.valueOf(unexpected.getMessage());
        }
    }

    /**
     * Extracts the model that ships inside the StarDist jar into a temporary
     * file, once per session. The detector takes a file path, not a resource.
     */
    public static synchronized File bundledModel() {
        return bundledModel(BUNDLED_MODEL_KEY);
    }

    public static boolean isBuiltin(String ref) {
        return ref == null || ref.trim().isEmpty()
                || BUNDLED_MODEL_KEY.equalsIgnoreCase(ref.trim())
                || DSB2018_MODEL_KEY.equalsIgnoreCase(ref.trim());
    }

    private static synchronized File bundledModel(String modelRef) {
        String key = modelRef == null || modelRef.trim().isEmpty()
                ? BUNDLED_MODEL_KEY : modelRef.trim().toLowerCase(Locale.ROOT);
        File cached = cachedModels.get(key);
        if (cached != null && cached.isFile()) return cached;
        String resource = DSB2018_MODEL_KEY.equals(key)
                ? "models/2D/dsb2018_paper.zip" : BUNDLED_MODEL_RESOURCE;
        ClassLoader loader = ModelResolver.class.getClassLoader();
        InputStream in = loader.getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException(
                    "The bundled StarDist model could not be found inside the StarDist jar ("
                            + resource + "). Run Install Runtime from the plugin's "
                            + "dependency prompt, or choose your own model .zip.");
        }
        try {
            Path temp = Files.createTempFile("oc3dsd_model_", ".zip");
            temp.toFile().deleteOnExit();
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            File file = temp.toFile();
            cachedModels.put(key, file);
            IJ.log(BUNDLED_MODEL_KEY.equals(key)
                    ? "    Using the bundled StarDist versatile-fluorescence model."
                    : "    Using the bundled StarDist DSB 2018 fluorescence model.");
            return file;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not extract the bundled StarDist model: " + e.getMessage(), e);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // Nothing useful to do; the copy either succeeded or already threw.
            }
        }
    }

    /** Display name for a model reference, for logs and the summary. */
    public static String displayName(String modelRef) {
        String ref = modelRef == null ? "" : modelRef.trim();
        if (ref.isEmpty() || BUNDLED_MODEL_KEY.equalsIgnoreCase(ref)) {
            return "versatile fluorescence (bundled)";
        }
        if (DSB2018_MODEL_KEY.equalsIgnoreCase(ref)) return "DSB 2018 fluorescence (bundled)";
        return new File(ref).getName();
    }
}
