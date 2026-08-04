package sc.fiji.oc3dsd.batch;

import ij.IJ;
import ij.ImagePlus;
import ij.measure.ResultsTable;
import sc.fiji.oc3d.core.io.CsvWriter;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

/**
 * Writes the batch output tree.
 * <pre>
 * &lt;output root&gt;/3D Objects Counter - StarDist/
 *     Labels/    &lt;image&gt;_labels.tif
 *     Objects/   &lt;image&gt;_objects.csv
 *     Maps/      &lt;image&gt;_objects_map.tif, _surface_map.tif, ...
 *     Groups/    &lt;group&gt;_objects.csv, &lt;group&gt;_summary.csv
 *     Summary/   summary.csv, per_folder_summary.csv, groups.csv, manifest.csv
 *     README.txt
 * </pre>
 * The manifest is the point of the whole thing: it records every parameter, the
 * calibration in force and the object counts for each file, so a result can be
 * reproduced or challenged months later.
 */
public final class BatchWriter {

    public static final String ROOT_FOLDER = "3D Objects Counter - StarDist";

    private final File root;

    public BatchWriter(File outputRoot) {
        this.root = new File(outputRoot, ROOT_FOLDER);
    }

    public File root() {
        return root;
    }

    public void prepare() {
        mkdirs(new File(root, "Labels"));
        mkdirs(new File(root, "Objects"));
        mkdirs(new File(root, "Maps"));
        mkdirs(new File(root, "Groups"));
        mkdirs(new File(root, "Summary"));
        writeReadme();
    }

    public void writeObjects(String baseName, ResultsTable objects) {
        writeTable(new File(new File(root, "Objects"), baseName + "_objects.csv"), objects);
    }

    public void writeGroupObjects(String groupKey, ResultsTable objects) {
        writeTable(new File(new File(root, "Groups"), safe(groupKey) + "_objects.csv"), objects);
    }

    public void writeGroupSummary(String groupKey, ResultsTable summary) {
        writeTable(new File(new File(root, "Groups"), safe(groupKey) + "_summary.csv"), summary);
    }

    public void writeSummary(String fileName, ResultsTable table) {
        writeTable(new File(new File(root, "Summary"), fileName), table);
    }

    public void writeLabels(String baseName, ImagePlus labels) {
        if (labels == null) return;
        IJ.saveAsTiff(labels, new File(new File(root, "Labels"), baseName + "_labels.tif").getAbsolutePath());
    }

    public void writeMap(String baseName, String suffix, ImagePlus map) {
        if (map == null) return;
        IJ.saveAsTiff(map, new File(new File(root, "Maps"), baseName + suffix + ".tif").getAbsolutePath());
    }

    /**
     * Writes a result table through {@link ResultsTable#saveAs}, deliberately,
     * where the manifest goes through {@code oc3d-core}'s {@code CsvWriter}.
     * <p>
     * Core's writer formats every number with {@code Double.toString}. ImageJ's
     * formats to the table's decimal places with per-column widths, which is what
     * has always been written here: {@code 5.500}, {@code 1.056}, and integral
     * columns bare. Routing these tables through core would therefore rewrite
     * every number in every batch CSV — not the values, but how they read, and
     * downstream scripts parse these files.
     * <p>
     * That is a user-visible change and it is not one this migration is entitled
     * to make in passing. Unifying the two is a decision about what the batch CSV
     * format should be, and it belongs to whoever makes that decision for the
     * whole family, with a CHANGELOG entry and a version bump behind it.
     */
    private void writeTable(File target, ResultsTable table) {
        if (table == null) return;
        mkdirs(target.getParentFile());
        try {
            table.saveAs(target.getAbsolutePath());
        } catch (IOException e) {
            IJ.log("WARNING: could not write " + target.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    private void writeReadme() {
        File readme = new File(root, "README.txt");
        PrintWriter writer = null;
        try {
            mkdirs(root);
            writer = new PrintWriter(readme, "UTF-8");
            writer.println("3D Objects Counter - StarDist — batch output");
            writer.println();
            writer.println("Labels/   one 3D label image per input, objects numbered 1..N.");
            writer.println("Objects/  one row per object per input.");
            writer.println("Maps/     object, surface, centroid and centre-of-mass maps.");
            writer.println("Groups/   objects and summary aggregated by the filename regex group.");
            writer.println("Summary/  summary.csv over everything, per_folder_summary.csv by source");
            writer.println("          folder, groups.csv by regex group, and manifest.csv.");
            writer.println();
            writer.println("manifest.csv records the parameters, calibration and counts for every");
            writer.println("image. Keep it: it is what makes these numbers reproducible.");
            writer.println();
            writer.println("Note on method: StarDist runs on each Z-slice and the detections are");
            writer.println("linked through Z by TrackMate. This is not a 3D StarDist model. The");
            writer.println("Slices column reports how many slices each object spans; objects");
            writer.println("spanning a single slice deserve a look before they are trusted.");
        } catch (IOException e) {
            IJ.log("WARNING: could not write " + readme.getAbsolutePath() + ": " + e.getMessage());
        } catch (RuntimeException e) {
            IJ.log("WARNING: could not write " + readme.getAbsolutePath() + ": " + e.getMessage());
        } finally {
            if (writer != null) writer.close();
        }
    }

    /**
     * Appends the manifest, one row per image.
     * <p>
     * Quoting and encoding come from {@code oc3d-core}'s {@link CsvWriter}, which
     * applies exactly the rules this class used to apply itself: quote on comma,
     * double quote, CR or LF; double an embedded quote; write null as an empty
     * field; UTF-8; platform line separator. The two produce byte-identical
     * files, which the batch goldens check rather than assume.
     */
    public void writeManifest(List<String[]> rows, String[] headings) {
        File target = new File(new File(root, "Summary"), "manifest.csv");
        mkdirs(target.getParentFile());
        CsvWriter writer = null;
        try {
            writer = new CsvWriter(target);
            writer.row(headings);
            for (String[] row : rows) {
                writer.row(row);
            }
        } catch (IOException e) {
            IJ.log("WARNING: could not write the manifest: " + e.getMessage());
        } catch (RuntimeException e) {
            IJ.log("WARNING: could not write the manifest: " + e.getMessage());
        } finally {
            close(writer);
        }
    }

    private static void close(CsvWriter writer) {
        if (writer == null) return;
        try {
            writer.close();
        } catch (IOException e) {
            IJ.log("WARNING: could not close the manifest: " + e.getMessage());
        }
    }

    /** Makes a group key safe to use as a file name. */
    static String safe(String value) {
        String text = value == null || value.trim().isEmpty() ? "unnamed" : value.trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sb.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' ? c : '_');
        }
        return sb.toString();
    }

    private static void mkdirs(File dir) {
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            IJ.log("WARNING: could not create " + dir.getAbsolutePath());
        }
    }
}
