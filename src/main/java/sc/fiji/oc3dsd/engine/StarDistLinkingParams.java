package sc.fiji.oc3dsd.engine;

/**
 * Parameters passed to TrackMate's LAP tracker when it links per-slice StarDist
 * detections into 3D objects.
 * <p>
 * Distances are in the image's <em>calibrated</em> units, because that is how
 * TrackMate interprets {@code LINKING_MAX_DISTANCE}. The same numeric value
 * therefore means different things on differently calibrated stacks, which is
 * why {@link #pixelEquivalent(double, double)} exists and why the dialog and the
 * run log both restate the value in pixels.
 */
public final class StarDistLinkingParams {

    /** Maximum centroid movement between consecutive slices, in calibrated units. */
    public final double linkingMaxDistance;

    /** Maximum centroid movement across a slice gap, in calibrated units. */
    public final double gapClosingMaxDistance;

    /** How many consecutive slices an object may be missing from and still link through. */
    public final int maxSliceGap;

    /**
     * Minimum number of Z-slices an object must span to be kept. A value of 1
     * keeps objects that StarDist found on a single slice; TrackMate cannot form
     * a track from one detection, so those are recovered separately rather than
     * being discarded.
     */
    public final int minSlices;

    public StarDistLinkingParams(double linkingMaxDistance,
                                 double gapClosingMaxDistance,
                                 int maxSliceGap,
                                 int minSlices) {
        this.linkingMaxDistance = Math.max(0, linkingMaxDistance);
        this.gapClosingMaxDistance = Math.max(0, gapClosingMaxDistance);
        this.maxSliceGap = Math.max(0, maxSliceGap);
        this.minSlices = Math.max(1, minSlices);
    }

    /** Defaults carried over from the FLASH pipeline, where they were tuned on confocal nuclei. */
    public static StarDistLinkingParams defaults() {
        return new StarDistLinkingParams(5.0, 5.0, 1, 1);
    }

    /**
     * Restates a calibrated distance in pixels for the given pixel size, so the
     * user can see what the number actually means on this image.
     *
     * @param calibratedDistance distance in calibrated units
     * @param pixelWidth         calibrated size of one pixel in x; values that are
     *                           not finite and positive are treated as 1 (uncalibrated)
     */
    public static double pixelEquivalent(double calibratedDistance, double pixelWidth) {
        double scale = (Double.isFinite(pixelWidth) && pixelWidth > 0) ? pixelWidth : 1.0;
        return calibratedDistance / scale;
    }

    @Override
    public String toString() {
        return "linking=" + linkingMaxDistance
                + ", gapClosing=" + gapClosingMaxDistance
                + ", sliceGap=" + maxSliceGap
                + ", minSlices=" + minSlices;
    }
}
