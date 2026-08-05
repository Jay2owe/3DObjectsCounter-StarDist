package sc.fiji.oc3dsd.equivalence;

import ij.ImagePlus;
import ij.measure.Calibration;

import java.util.ArrayList;
import java.util.List;

/**
 * The configuration sweep: size bounds, edge exclusion, redirect, calibration,
 * each map on and off, and the morphology filters.
 * <p>
 * <strong>Not a full cross-product, and deliberately so.</strong> Crossing every
 * level of every factor here is roughly two thousand configurations per fixture,
 * which buys almost nothing: these factors act at different points in the
 * pipeline — size bounds and edge exclusion select objects, calibration scales
 * measurements, maps are pure output — so their interactions are shallow. The
 * sweep is therefore one-factor-at-a-time from a baseline, which isolates the
 * cause of any diff, plus two deliberately combined configurations to catch the
 * interactions that do exist (filtering with a calibration in force, and
 * renumbering with maps built afterwards from the renumbered labels).
 * <p>
 * Every configuration is named, and the name is the golden directory, so a
 * failure says which factor moved.
 */
final class Sweep {

    /** Which calibration a configuration puts in force, and on which image. */
    enum Cal {
        /** No calibration; measurements come out in voxels. */
        NONE,
        /** Isotropic 0.5 µm. */
        ISOTROPIC,
        /** z = 5× xy — exercises surface weighting and Feret. */
        ANISOTROPIC,
        /** Calibration on the intensity image only. The label image drives measurement. */
        INTENSITY_ONLY
    }

    static final class Config {
        final String name;
        final int minSize;
        final int maxSize;
        final boolean excludeOnEdges;
        final boolean redirect;
        final Cal cal;
        final boolean objectMap;
        final boolean surfaceMap;
        final boolean centroidMap;
        final boolean comMap;
        final String[] filters;

        private Config(Builder b) {
            this.name = b.name;
            this.minSize = b.minSize;
            this.maxSize = b.maxSize;
            this.excludeOnEdges = b.excludeOnEdges;
            this.redirect = b.redirect;
            this.cal = b.cal;
            this.objectMap = b.objectMap;
            this.surfaceMap = b.surfaceMap;
            this.centroidMap = b.centroidMap;
            this.comMap = b.comMap;
            this.filters = b.filters;
        }

        boolean buildsAnyMap() {
            return objectMap || surfaceMap || centroidMap || comMap;
        }

        /** Applies this configuration's calibration to the images in place. */
        void applyCalibration(ImagePlus labels, ImagePlus intensity) {
            if (cal == Cal.INTENSITY_ONLY) {
                if (intensity != null) intensity.setCalibration(calibration(0.5, 0.5, "micron"));
                return;
            }
            if (cal == Cal.ISOTROPIC) {
                labels.setCalibration(calibration(0.5, 0.5, "micron"));
            } else if (cal == Cal.ANISOTROPIC) {
                labels.setCalibration(calibration(0.2, 1.0, "micron"));
            }
        }

        /** Human-readable record of the configuration, written into every golden. */
        String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("minSize=").append(minSize).append('\n');
            sb.append("maxSize=").append(maxSize == Integer.MAX_VALUE
                    ? "Infinity" : Integer.toString(maxSize)).append('\n');
            sb.append("excludeOnEdges=").append(excludeOnEdges).append('\n');
            sb.append("redirect=").append(redirect).append('\n');
            sb.append("calibration=").append(cal).append('\n');
            sb.append("maps=").append(objectMap).append(',').append(surfaceMap)
                    .append(',').append(centroidMap).append(',').append(comMap).append('\n');
            sb.append("filters=");
            for (int i = 0; i < filters.length; i++) {
                if (i > 0) sb.append(' ');
                sb.append(filters[i]);
            }
            sb.append('\n');
            return sb.toString();
        }
    }

    private static Calibration calibration(double xy, double z, String unit) {
        Calibration cal = new Calibration();
        cal.pixelWidth = xy;
        cal.pixelHeight = xy;
        cal.pixelDepth = z;
        cal.setUnit(unit);
        return cal;
    }

    private static final class Builder {
        private final String name;
        private int minSize;
        private int maxSize = Integer.MAX_VALUE;
        private boolean excludeOnEdges;
        private boolean redirect;
        private Cal cal = Cal.NONE;
        private boolean objectMap = true;
        private boolean surfaceMap = true;
        private boolean centroidMap = true;
        private boolean comMap = true;
        private String[] filters = new String[0];

        Builder(String name) {
            this.name = name;
        }

        Builder size(int min, int max) {
            this.minSize = min;
            this.maxSize = max;
            return this;
        }

        Builder edges() {
            this.excludeOnEdges = true;
            return this;
        }

        Builder redirect() {
            this.redirect = true;
            return this;
        }

        Builder cal(Cal value) {
            this.cal = value;
            return this;
        }

        Builder maps(boolean object, boolean surface, boolean centroid, boolean com) {
            this.objectMap = object;
            this.surfaceMap = surface;
            this.centroidMap = centroid;
            this.comMap = com;
            return this;
        }

        Builder filters(String... value) {
            this.filters = value;
            return this;
        }

        Config build() {
            return new Config(this);
        }
    }

    private Sweep() {
    }

    static List<Config> all() {
        List<Config> configs = new ArrayList<Config>();

        // The baseline every other configuration varies one factor from.
        configs.add(new Builder("baseline").build());

        // Size bounds. minSize=10 is the shipped default, so it is not an
        // exotic case — it is what most users actually run.
        configs.add(new Builder("min_10").size(10, Integer.MAX_VALUE).build());
        configs.add(new Builder("max_50").size(0, 50).build());
        configs.add(new Builder("min_5_max_200").size(5, 200).build());

        // Edge exclusion. The object set under this option is a known
        // algorithmic difference in 3D Objects Counter+, because the classic
        // counter mislabels edge contact across a late merge. Not so here —
        // this plugin never
        // merges provisional ids — so it stays Tier 1, and this configuration is
        // what holds that claim up.
        configs.add(new Builder("exclude_edges").edges().build());

        // Redirect.
        configs.add(new Builder("redirect").redirect().build());

        // Calibration.
        configs.add(new Builder("cal_isotropic").cal(Cal.ISOTROPIC).build());
        configs.add(new Builder("cal_anisotropic").cal(Cal.ANISOTROPIC).build());
        configs.add(new Builder("cal_intensity_only")
                .redirect().cal(Cal.INTENSITY_ONLY).build());

        // Maps, each alone and none at all.
        configs.add(new Builder("no_maps").maps(false, false, false, false).build());
        configs.add(new Builder("map_objects").maps(true, false, false, false).build());
        configs.add(new Builder("map_surfaces").maps(false, true, false, false).build());
        configs.add(new Builder("map_centroids").maps(false, false, true, false).build());
        configs.add(new Builder("map_com").maps(false, false, false, true).build());

        // Morphology filters, one feature at a time.
        configs.add(new Builder("filter_volume").filters("volume>=8").build());
        configs.add(new Builder("filter_sphericity").filters("sphericity>=0.3").build());
        configs.add(new Builder("filter_elongation").filters("elongation<=3").build());
        configs.add(new Builder("filter_surface_area").filters("surface_area>=10").build());
        configs.add(new Builder("filter_feret").filters("feret_diameter_max>=2").build());
        configs.add(new Builder("filter_volume_calibrated")
                .cal(Cal.ISOTROPIC).filters("volume_calibrated>=1").build());
        // Intensity features with no redirect image resolve to NaN. A feature
        // that could not be computed must not exclude an object — otherwise the
        // count would depend on a measurement failure — so this must be a no-op.
        configs.add(new Builder("filter_mean_intensity_no_redirect")
                .filters("mean_intensity>=10").build());
        configs.add(new Builder("filter_mean_intensity")
                .redirect().filters("mean_intensity>=10").build());
        configs.add(new Builder("filter_max_intensity")
                .redirect().filters("max_intensity>=200").build());
        // An unknown feature warns and is ignored rather than silently
        // excluding everything. The warning text is user-visible and recorded.
        configs.add(new Builder("filter_unknown_feature")
                .filters("no_such_feature>=1").build());
        configs.add(new Builder("filter_two_features")
                .filters("volume>=8", "sphericity>=0.2").build());

        // Map-free duplicates of the factors that select objects. Fixtures with
        // tens of thousands of objects cannot afford a numbered overlay per
        // object, but they must still exercise size bounds, edge exclusion, the
        // redirect and the renumber-and-rescan path at that scale — these are
        // what let them do it. See Harness#runs.
        configs.add(new Builder("no_maps_min_10")
                .size(10, Integer.MAX_VALUE).maps(false, false, false, false).build());
        configs.add(new Builder("no_maps_max_50")
                .size(0, 50).maps(false, false, false, false).build());
        configs.add(new Builder("no_maps_exclude_edges")
                .edges().maps(false, false, false, false).build());
        configs.add(new Builder("no_maps_redirect")
                .redirect().maps(false, false, false, false).build());
        configs.add(new Builder("no_maps_filter_volume")
                .filters("volume>=8").maps(false, false, false, false).build());

        // The interactions that genuinely exist: filtering drops objects, which
        // triggers the renumber-and-rescan path, after which the maps are built
        // from the renumbered labels and must still join to the table.
        configs.add(new Builder("combined_strict")
                .size(5, 500).edges().redirect().cal(Cal.ANISOTROPIC)
                .filters("sphericity>=0.3").build());
        configs.add(new Builder("combined_loose")
                .redirect().cal(Cal.ISOTROPIC).maps(false, false, false, false)
                .filters("volume>=2").build());

        return configs;
    }
}
