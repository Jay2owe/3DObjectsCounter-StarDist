package sc.fiji.oc3dsd.api;

import ij.ImagePlus;
import ij.measure.ResultsTable;

/**
 * The result of one run: the objects, what was measured about them, and the
 * label image they came from.
 * <p>
 * The label image is deliberately part of the public result rather than an
 * internal detail. This plugin is a producer — its output is meant to be fed to
 * whatever consumes label images next, so a caller should never have to
 * re-derive it.
 */
public final class OC3DSDResult {

    private final ResultsTable objects;
    private final ResultsTable summary;
    private final ImagePlus labelImage;
    private final ImagePlus objectMap;
    private final ImagePlus surfaceMap;
    private final ImagePlus centroidMap;
    private final ImagePlus centreOfMassMap;
    private final int objectCount;
    private final int singleSliceObjects;
    private final int droppedShortObjects;
    private final int droppedByDetectorFilters;
    private final int droppedByMorphologyFilters;
    private final long elapsedMs;

    public OC3DSDResult(ResultsTable objects,
                        ResultsTable summary,
                        ImagePlus labelImage,
                        ImagePlus objectMap,
                        ImagePlus surfaceMap,
                        ImagePlus centroidMap,
                        ImagePlus centreOfMassMap,
                        int objectCount,
                        int singleSliceObjects,
                        int droppedShortObjects,
                        int droppedByDetectorFilters,
                        int droppedByMorphologyFilters,
                        long elapsedMs) {
        this.objects = objects;
        this.summary = summary;
        this.labelImage = labelImage;
        this.objectMap = objectMap;
        this.surfaceMap = surfaceMap;
        this.centroidMap = centroidMap;
        this.centreOfMassMap = centreOfMassMap;
        this.objectCount = objectCount;
        this.singleSliceObjects = singleSliceObjects;
        this.droppedShortObjects = droppedShortObjects;
        this.droppedByDetectorFilters = droppedByDetectorFilters;
        this.droppedByMorphologyFilters = droppedByMorphologyFilters;
        this.elapsedMs = elapsedMs;
    }

    /** One row per object, numbered 1..N, in 3D Objects Counter+'s column order. */
    public ResultsTable getObjects() {
        return objects;
    }

    /** Mean, standard deviation, minimum and maximum of every measured column. */
    public ResultsTable getSummary() {
        return summary;
    }

    /** The 3D label image. Objects are numbered 1..N per timepoint. */
    public ImagePlus getLabelImage() {
        return labelImage;
    }

    public ImagePlus getObjectMap() {
        return objectMap;
    }

    public ImagePlus getSurfaceMap() {
        return surfaceMap;
    }

    public ImagePlus getCentroidMap() {
        return centroidMap;
    }

    public ImagePlus getCentreOfMassMap() {
        return centreOfMassMap;
    }

    public int getObjectCount() {
        return objectCount;
    }

    /**
     * Objects spanning exactly one Z-slice. Worth looking at: a high proportion
     * usually means the Z-step is too coarse for the linking distance, or that
     * the detector is firing on noise.
     */
    public int getSingleSliceObjects() {
        return singleSliceObjects;
    }

    /** Objects discarded for spanning fewer slices than the minimum. */
    public int getDroppedShortObjects() {
        return droppedShortObjects;
    }

    /** Objects discarded by the detector-level area, quality or intensity filters. */
    public int getDroppedByDetectorFilters() {
        return droppedByDetectorFilters;
    }

    /** Objects discarded by the size bounds or the morphology predicates. */
    public int getDroppedByMorphologyFilters() {
        return droppedByMorphologyFilters;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }
}
