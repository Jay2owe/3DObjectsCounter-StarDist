package sc.fiji.oc3dsd.batch;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Finds the images a batch run will process, and works out which of them belong
 * together.
 * <p>
 * <strong>Both discovery routes, together.</strong> They answer different halves
 * of the same problem and neither substitutes for the other: the recursive scan
 * decides <em>which files are analysed</em>, and the filename regex decides
 * <em>which results are compared</em>. A folder tree organised by acquisition
 * date and an experiment organised by genotype are different partitions of the
 * same images, and a user should not have to reorganise their disk to get the
 * grouping they want.
 * <p>
 * Files that match nothing are analysed anyway and reported under
 * {@link #UNGROUPED}. Silently skipping an input is how a batch run comes back
 * with a number nobody can reconcile.
 */
public final class BatchDiscovery {

    /** Group key for files the pattern does not match. */
    public static final String UNGROUPED = "<ungrouped>";

    /** Default extensions considered to be images. */
    public static final String DEFAULT_EXTENSIONS = "tif,tiff,lif,nd2,czi,lsm,oib,oif";

    /** One discovered image and where it belongs. */
    public static final class Item {
        public final File file;
        public final String groupKey;

        Item(File file, String groupKey) {
            this.file = file;
            this.groupKey = groupKey;
        }
    }

    private BatchDiscovery() {
    }

    /**
     * Walks {@code root} and returns the images to analyse, in a stable order.
     *
     * @param root       directory to scan
     * @param recursive  whether to descend into subfolders
     * @param extensions comma-separated extension list; blank means {@link #DEFAULT_EXTENSIONS}
     */
    public static List<File> discover(File root, boolean recursive, String extensions) {
        List<File> found = new ArrayList<File>();
        if (root == null || !root.isDirectory()) return found;
        List<String> allowed = parseExtensions(extensions);
        collect(root, recursive, allowed, found);
        Collections.sort(found, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getAbsolutePath().compareToIgnoreCase(b.getAbsolutePath());
            }
        });
        return found;
    }

    /**
     * Assigns a group key to each file from capture group {@code groupIndex} of
     * {@code pattern}, matched against the filename.
     *
     * @param pattern    regular expression, or blank for a single group
     * @param groupIndex 1-based capture group to use as the key
     */
    public static List<Item> group(List<File> files, String pattern, int groupIndex) {
        List<Item> items = new ArrayList<Item>();
        if (files == null) return items;

        Pattern compiled = compile(pattern);
        for (File file : files) {
            items.add(new Item(file, keyFor(file, compiled, groupIndex)));
        }
        return items;
    }

    /** Compiles the grouping pattern, or null when there is nothing to match on. */
    public static Pattern compile(String pattern) {
        if (pattern == null || pattern.trim().isEmpty()) return null;
        try {
            return Pattern.compile(pattern.trim());
        } catch (PatternSyntaxException bad) {
            throw new IllegalArgumentException(
                    "The grouping pattern is not a valid regular expression: " + bad.getDescription()
                            + " (pattern='" + pattern + "').", bad);
        }
    }

    private static String keyFor(File file, Pattern pattern, int groupIndex) {
        if (pattern == null) return "all";
        Matcher matcher = pattern.matcher(file.getName());
        if (!matcher.matches()) {
            // Try a find() as well: users routinely write a fragment rather than
            // a whole-filename pattern, and failing them for that is unhelpful.
            matcher.reset();
            if (!matcher.find()) return UNGROUPED;
        }
        if (groupIndex < 1 || groupIndex > matcher.groupCount()) return UNGROUPED;
        String key = matcher.group(groupIndex);
        return key == null || key.isEmpty() ? UNGROUPED : key;
    }

    /**
     * Group key to file count, in encounter order — what the preview shows
     * before a run that may take hours starts.
     */
    public static Map<String, List<File>> byGroup(List<Item> items) {
        Map<String, List<File>> grouped = new LinkedHashMap<String, List<File>>();
        if (items == null) return grouped;
        for (Item item : items) {
            List<File> bucket = grouped.get(item.groupKey);
            if (bucket == null) {
                bucket = new ArrayList<File>();
                grouped.put(item.groupKey, bucket);
            }
            bucket.add(item.file);
        }
        return grouped;
    }

    /** A human-readable preview of the grouping, for confirmation before running. */
    public static String previewText(List<Item> items, int examplesPerGroup) {
        Map<String, List<File>> grouped = byGroup(items);
        if (grouped.isEmpty()) return "No images found.";

        StringBuilder sb = new StringBuilder();
        sb.append(items.size()).append(" image(s) in ").append(grouped.size()).append(" group(s):\n\n");
        for (Map.Entry<String, List<File>> entry : grouped.entrySet()) {
            List<File> files = entry.getValue();
            sb.append("  ").append(entry.getKey()).append("  —  ")
              .append(files.size()).append(" file(s)\n");
            for (int i = 0; i < Math.min(examplesPerGroup, files.size()); i++) {
                sb.append("      ").append(files.get(i).getName()).append('\n');
            }
            if (files.size() > examplesPerGroup) {
                sb.append("      ... and ").append(files.size() - examplesPerGroup).append(" more\n");
            }
        }
        if (grouped.containsKey(UNGROUPED)) {
            sb.append("\nFiles under ").append(UNGROUPED)
              .append(" did not match the pattern. They will still be analysed.");
        }
        return sb.toString();
    }

    private static void collect(File dir, boolean recursive, List<String> allowed, List<File> out) {
        File[] entries = dir.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            if (entry.isDirectory()) {
                if (recursive) collect(entry, true, allowed, out);
            } else if (hasAllowedExtension(entry, allowed)) {
                out.add(entry);
            }
        }
    }

    private static boolean hasAllowedExtension(File file, List<String> allowed) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return allowed.contains(extension);
    }

    static List<String> parseExtensions(String extensions) {
        String value = extensions == null || extensions.trim().isEmpty()
                ? DEFAULT_EXTENSIONS : extensions.trim();
        List<String> out = new ArrayList<String>();
        for (String token : Arrays.asList(value.split(","))) {
            String cleaned = token.trim().toLowerCase(Locale.ROOT);
            if (cleaned.startsWith(".")) cleaned = cleaned.substring(1);
            if (!cleaned.isEmpty()) out.add(cleaned);
        }
        return out;
    }
}
