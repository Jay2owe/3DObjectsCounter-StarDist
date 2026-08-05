package sc.fiji.oc3dsd.engine;

/**
 * Filters applied to detections immediately after linking, using the detector's
 * own per-spot features rather than 3D morphometry.
 * <p>
 * These are deliberately separate from the morphology filters in the dialog.
 * They act on what StarDist reports about each 2D detection — its area, its
 * confidence, its mean intensity — averaged over the slices of an object, and
 * exist to discard obvious detector noise before the expensive 3D measurement
 * pass runs. The min/max morphology filters act afterwards, on real measured
 * volume, sphericity and the rest.
 */
public final class StarDistPostFilters {

    public final double areaMin;
    public final double areaMax;
    public final double qualityMin;
    public final double intensityMin;

    public StarDistPostFilters(double areaMin,
                               double areaMax,
                               double qualityMin,
                               double intensityMin) {
        this.areaMin = Math.max(0, areaMin);
        this.areaMax = areaMax <= 0 ? Double.POSITIVE_INFINITY : areaMax;
        this.qualityMin = Math.max(0, qualityMin);
        this.intensityMin = Math.max(0, intensityMin);
    }

    /** No detector-level filtering; everything reaches the measurement pass. */
    public static StarDistPostFilters none() {
        return new StarDistPostFilters(0, Double.POSITIVE_INFINITY, 0, 0);
    }

    public boolean isActive() {
        return areaMin > 0
                || Double.isFinite(areaMax)
                || qualityMin > 0
                || intensityMin > 0;
    }

    @Override
    public String toString() {
        if (!isActive()) return "none";
        StringBuilder sb = new StringBuilder();
        if (areaMin > 0 || Double.isFinite(areaMax)) {
            sb.append("area=").append(areaMin).append('-')
              .append(Double.isFinite(areaMax) ? String.valueOf(areaMax) : "Inf");
        }
        if (qualityMin > 0) sb.append(" quality>=").append(qualityMin);
        if (intensityMin > 0) sb.append(" intensity>=").append(intensityMin);
        return sb.toString().trim();
    }
}
