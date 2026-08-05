package sc.fiji.oc3dsd.equivalence;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Overlay;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.process.ImageProcessor;

import java.awt.Rectangle;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical, byte-stable text for everything one run produces.
 * <p>
 * Three properties matter and all three are deliberate.
 * <p>
 * <strong>Exact.</strong> Numbers are written with {@link Double#toString(double)},
 * which is the shortest decimal that round-trips to the same {@code double}, and
 * digests are taken over raw {@code doubleToLongBits}. A change in the last bit
 * of a {@code Mean} changes the record. Formatting to a fixed number of decimal
 * places would silently swallow exactly the Tier 1 differences this harness
 * exists to catch.
 * <p>
 * <strong>Diagnosable.</strong> Where a run is small enough, every object gets
 * its own line, so a diff names the object that moved rather than reporting that
 * something, somewhere, changed. Above {@link #FULL_DETAIL_LIMIT} entries the
 * per-object lines would dominate the repository, so the record keeps the digest
 * and the head and tail of the table. That cap is written into the output as an
 * explicit {@code detail=digest} marker, so a reduced record can never be
 * mistaken for a full one.
 * <p>
 * <strong>Cheap.</strong> The sweep is a few thousand runs and has to fit inside
 * {@code mvn test}, so per-voxel and per-cell work stays allocation-free. Voxel
 * sets are hashed with a 128-bit FNV-style mix rather than SHA-256 per label:
 * sixty-five thousand {@code MessageDigest} instances per run is minutes of
 * provider lookup for no extra certainty, since each label's line also carries
 * its exact voxel count and exact bounding box.
 */
final class Canon {

    /** Above this many rows or labels, per-entry lines give way to a digest. */
    static final int FULL_DETAIL_LIMIT = 512;

    /** How many leading and trailing entries survive the digest fallback. */
    static final int DETAIL_WINDOW = 32;

    private Canon() {
    }

    // ------------------------------------------------------------------
    // Primitives
    // ------------------------------------------------------------------

    /**
     * Shortest round-tripping decimal for a double. Never locale-dependent,
     * unlike {@code String.format}, and never lossy, unlike {@code %.6f}.
     */
    static String num(double value) {
        return Double.toString(value);
    }

    static String sha256(String text) {
        Digest digest = new Digest();
        digest.put(text);
        return digest.hex();
    }

    /** Incremental SHA-256 over primitives, so nothing large is ever materialised. */
    private static final class Digest {
        private final MessageDigest digest;
        private final byte[] scratch = new byte[8];

        Digest() {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is required by the JLS", impossible);
            }
        }

        void put(String text) {
            try {
                digest.update(text.getBytes("UTF-8"));
            } catch (UnsupportedEncodingException impossible) {
                throw new IllegalStateException("UTF-8 is required by the JLS", impossible);
            }
        }

        void put(long value) {
            for (int i = 0; i < 8; i++) {
                scratch[i] = (byte) (value >>> (8 * i));
            }
            digest.update(scratch, 0, 8);
        }

        void put(double value) {
            put(Double.doubleToLongBits(value));
        }

        String hex() {
            return toHex(digest.digest());
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xff;
            if (b < 16) sb.append('0');
            sb.append(Integer.toHexString(b));
        }
        return sb.toString();
    }

    private static String hex16(long value) {
        String text = Long.toHexString(value);
        StringBuilder sb = new StringBuilder(16);
        for (int i = text.length(); i < 16; i++) {
            sb.append('0');
        }
        return sb.append(text).toString();
    }

    // ------------------------------------------------------------------
    // Tables
    // ------------------------------------------------------------------

    /**
     * A {@link ResultsTable} as canonical TSV: a heading line, a digest over
     * every cell, then one line per row while the table is small enough for
     * that to be useful. String cells are prefixed {@code s:} so the summary
     * table's {@code Statistic} labels survive without being read as numbers.
     */
    static String table(String name, ResultsTable table) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(name).append('\n');
        if (table == null) {
            sb.append("absent\n");
            return sb.toString();
        }
        String[] headings = usableHeadings(table);
        sb.append("rows=").append(table.size())
                .append(" columns=").append(headings.length).append('\n');
        sb.append("headings\t").append(join(headings)).append('\n');
        if (headings.length == 0 || table.size() == 0) {
            return sb.toString();
        }

        int[] columns = columnIndices(table, headings);
        sb.append("body_sha256=").append(bodyDigest(table, headings, columns)).append('\n');
        if (table.size() <= FULL_DETAIL_LIMIT) {
            sb.append("detail=full\n");
            appendRows(sb, table, headings, columns, 0, table.size());
        } else {
            sb.append("detail=digest window=").append(DETAIL_WINDOW).append('\n');
            appendRows(sb, table, headings, columns, 0, DETAIL_WINDOW);
            sb.append("...\n");
            appendRows(sb, table, headings, columns, table.size() - DETAIL_WINDOW, table.size());
        }
        return sb.toString();
    }

    /**
     * Digest over every cell of the table, taken on the raw bits rather than on
     * formatted text. Identical in strictness and far cheaper — a 65,536-row
     * table would otherwise spend two million {@code Double.toString} calls per
     * run just to be hashed.
     */
    private static String bodyDigest(ResultsTable table, String[] headings, int[] columns) {
        Digest digest = new Digest();
        for (int i = 0; i < headings.length; i++) {
            digest.put(headings[i]);
        }
        for (int row = 0; row < table.size(); row++) {
            for (int c = 0; c < columns.length; c++) {
                if (columns[c] == ResultsTable.COLUMN_NOT_FOUND) {
                    digest.put("-");
                    continue;
                }
                double value = table.getValueAsDouble(columns[c], row);
                if (Double.isNaN(value)) {
                    // Either a genuine NaN or a string cell; the text decides.
                    String text = table.getStringValue(columns[c], row);
                    digest.put(text == null ? "" : text);
                } else {
                    digest.put(value);
                }
            }
        }
        return digest.hex();
    }

    private static void appendRows(StringBuilder sb,
                                   ResultsTable table,
                                   String[] headings,
                                   int[] columns,
                                   int from,
                                   int to) {
        for (int row = Math.max(0, from); row < Math.min(table.size(), to); row++) {
            sb.append(row);
            for (int c = 0; c < columns.length; c++) {
                sb.append('\t').append(cell(table, columns[c], row));
            }
            sb.append('\n');
        }
    }

    private static String cell(ResultsTable table, int column, int row) {
        if (column == ResultsTable.COLUMN_NOT_FOUND) return "-";
        double value = table.getValueAsDouble(column, row);
        if (!Double.isNaN(value)) return num(value);
        String text = table.getStringValue(column, row);
        return "s:" + (text == null ? "" : text);
    }

    private static int[] columnIndices(ResultsTable table, String[] headings) {
        int[] columns = new int[headings.length];
        for (int i = 0; i < headings.length; i++) {
            columns[i] = table.getColumnIndex(headings[i]);
        }
        return columns;
    }

    private static String[] usableHeadings(ResultsTable table) {
        String[] headings = table.getHeadings();
        if (headings == null) return new String[0];
        List<String> usable = new ArrayList<String>();
        for (int i = 0; i < headings.length; i++) {
            String heading = headings[i];
            if (heading == null || heading.trim().isEmpty()) continue;
            usable.add(heading);
        }
        return usable.toArray(new String[usable.size()]);
    }

    private static String join(String[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append('\t');
            sb.append(values[i]);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Images
    // ------------------------------------------------------------------

    /**
     * A label image as a <em>partition</em>: per label, its voxel count,
     * bounding box and a hash of its voxel positions.
     * <p>
     * Object maps are compared as partitions rather than
     * pixel-by-pixel, so that a pure renumbering is not read as thousands of
     * failures. Recording the per-label hash gives both readings at once: the
     * set of hashes answers "is it the same partition?", and the label each hash
     * sits against answers "did the numbering move?".
     */
    static String partition(String name, ImagePlus image) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(name).append('\n');
        if (image == null || image.getStack() == null) {
            sb.append("absent\n");
            return sb.toString();
        }
        sb.append(geometry(image));

        List<LabelExtent> extents = extents(image);
        sb.append("labels=").append(extents.size()).append('\n');
        Digest digest = new Digest();
        for (int i = 0; i < extents.size(); i++) {
            digest.put(extents.get(i).line());
        }
        sb.append("partition_sha256=").append(digest.hex()).append('\n');
        if (extents.size() <= FULL_DETAIL_LIMIT) {
            sb.append("detail=full\n");
            for (int i = 0; i < extents.size(); i++) {
                sb.append(extents.get(i).line()).append('\n');
            }
        } else {
            sb.append("detail=digest window=").append(DETAIL_WINDOW).append('\n');
            for (int i = 0; i < DETAIL_WINDOW && i < extents.size(); i++) {
                sb.append(extents.get(i).line()).append('\n');
            }
            sb.append("...\n");
            for (int i = Math.max(0, extents.size() - DETAIL_WINDOW); i < extents.size(); i++) {
                sb.append(extents.get(i).line()).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * A map image as geometry plus a digest of its pixels, and the full number
     * overlay. The overlay is user-visible — it is the numbering a person reads
     * off the map — so it is compared, not skipped.
     */
    static String map(String name, ImagePlus image) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(name).append('\n');
        if (image == null) {
            sb.append("absent\n");
            return sb.toString();
        }
        sb.append("title=").append(image.getTitle()).append('\n');
        sb.append(geometry(image));
        sb.append("pixels_sha256=").append(pixelDigest(image)).append('\n');
        sb.append(overlay(image));
        return sb.toString();
    }

    private static String geometry(ImagePlus image) {
        StringBuilder sb = new StringBuilder();
        ImageStack stack = image.getStack();
        sb.append("dims=").append(image.getWidth()).append('x').append(image.getHeight())
                .append('x').append(stack == null ? 0 : stack.getSize()).append('\n');
        sb.append("czt=").append(image.getNChannels()).append(',')
                .append(image.getNSlices()).append(',').append(image.getNFrames()).append('\n');
        sb.append("bitDepth=").append(image.getBitDepth()).append('\n');
        sb.append("displayRange=").append(num(image.getDisplayRangeMin()))
                .append(',').append(num(image.getDisplayRangeMax())).append('\n');
        Calibration cal = image.getCalibration();
        if (cal == null) {
            sb.append("calibration=absent\n");
        } else {
            sb.append("calibration=").append(num(cal.pixelWidth)).append(',')
                    .append(num(cal.pixelHeight)).append(',')
                    .append(num(cal.pixelDepth)).append(',')
                    .append(cal.getUnit()).append('\n');
        }
        return sb.toString();
    }

    private static String pixelDigest(ImagePlus image) {
        ImageStack stack = image.getStack();
        Digest digest = new Digest();
        if (stack == null) return digest.hex();
        for (int slice = 1; slice <= stack.getSize(); slice++) {
            ImageProcessor ip = stack.getProcessor(slice);
            if (ip == null) {
                digest.put("null");
                continue;
            }
            int pixels = ip.getPixelCount();
            digest.put(pixels);
            for (int i = 0; i < pixels; i++) {
                digest.put(Float.floatToIntBits(ip.getf(i)));
            }
        }
        return digest.hex();
    }

    private static String overlay(ImagePlus image) {
        StringBuilder sb = new StringBuilder();
        String skipped = sc.fiji.oc3d.core.map.ObjectMapBuilder.overlaySkippedReason(image);
        sb.append("overlaySkipped=").append(skipped == null ? "no" : skipped).append('\n');
        Overlay ov = image.getOverlay();
        if (ov == null) {
            sb.append("overlay=absent\n");
            return sb.toString();
        }
        List<String> rois = new ArrayList<String>();
        for (int i = 0; i < ov.size(); i++) {
            Roi roi = ov.get(i);
            if (roi == null) continue;
            Rectangle bounds = roi.getBounds();
            rois.add(roi.getName() + "\t" + roi.getPosition()
                    + "\t" + bounds.x + "\t" + bounds.y);
        }
        // The overlay is built in table-row order; sorting makes the record
        // independent of that, so a diff here means a ROI genuinely changed.
        Collections.sort(rois);
        Digest digest = new Digest();
        for (int i = 0; i < rois.size(); i++) {
            digest.put(rois.get(i));
        }
        sb.append("overlay=").append(rois.size()).append('\n');
        sb.append("overlay_sha256=").append(digest.hex()).append('\n');
        if (rois.size() <= FULL_DETAIL_LIMIT) {
            sb.append("detail=full\n");
            for (int i = 0; i < rois.size(); i++) {
                sb.append(rois.get(i)).append('\n');
            }
        } else {
            sb.append("detail=digest\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Label extents
    // ------------------------------------------------------------------

    private static List<LabelExtent> extents(ImagePlus image) {
        ImageStack stack = image.getStack();
        int width = image.getWidth();
        int height = image.getHeight();
        Map<Integer, LabelExtent> byLabel = new HashMap<Integer, LabelExtent>();
        for (int z = 0; z < stack.getSize(); z++) {
            ImageProcessor ip = stack.getProcessor(z + 1);
            if (ip == null) continue;
            for (int y = 0; y < height; y++) {
                int offset = y * width;
                for (int x = 0; x < width; x++) {
                    int label = labelOf(ip.getf(offset + x));
                    if (label <= 0) continue;
                    Integer key = Integer.valueOf(label);
                    LabelExtent extent = byLabel.get(key);
                    if (extent == null) {
                        extent = new LabelExtent(label);
                        byLabel.put(key, extent);
                    }
                    extent.add(x, y, z);
                }
            }
        }
        List<LabelExtent> extents = new ArrayList<LabelExtent>(byLabel.values());
        Collections.sort(extents, new Comparator<LabelExtent>() {
            @Override
            public int compare(LabelExtent a, LabelExtent b) {
                return Integer.compare(a.label, b.label);
            }
        });
        return extents;
    }

    private static int labelOf(float value) {
        if (!Float.isFinite(value) || value <= 0f) return 0;
        if (value > Integer.MAX_VALUE) return 0;
        return Math.round(value);
    }

    /**
     * One label's extent. Voxels arrive in z → y → x order, which is already
     * canonical, so the hash is order-dependent by design: a partition that
     * kept the same voxels but reached them in a different order would be a
     * different traversal, and traversal order is a hard constraint here:
     * floating-point addition is not associative, so a different order perturbs
     * {@code Mean} and {@code StdDev} in their last bits.
     */
    private static final class LabelExtent {
        private final int label;
        private long count;
        private long h1 = 0xcbf29ce484222325L;
        private long h2 = 0x9e3779b97f4a7c15L;
        private int minX = Integer.MAX_VALUE;
        private int minY = Integer.MAX_VALUE;
        private int minZ = Integer.MAX_VALUE;
        private int maxX = Integer.MIN_VALUE;
        private int maxY = Integer.MIN_VALUE;
        private int maxZ = Integer.MIN_VALUE;

        LabelExtent(int label) {
            this.label = label;
        }

        void add(int x, int y, int z) {
            count++;
            long packed = (((long) z) << 42) ^ (((long) y) << 21) ^ x;
            h1 = (h1 ^ packed) * 0x100000001b3L;
            h2 = Long.rotateLeft(h2 + packed, 27) * 0xff51afd7ed558ccdL;
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (z < minZ) minZ = z;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
            if (z > maxZ) maxZ = z;
        }

        String line() {
            return label + "\t" + count
                    + "\t" + minX + "," + minY + "," + minZ
                    + "\t" + (maxX - minX + 1) + "," + (maxY - minY + 1) + "," + (maxZ - minZ + 1)
                    + "\t" + hex16(h1) + hex16(h2);
        }
    }
}
