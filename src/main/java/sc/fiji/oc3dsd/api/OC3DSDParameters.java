package sc.fiji.oc3dsd.api;

import ij.ImagePlus;
import sc.fiji.oc3dsd.engine.StarDistLinkingParams;
import sc.fiji.oc3dsd.engine.StarDistPostFilters;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable parameter bundle for one run.
 * <p>
 * Where 3D Objects Counter+ takes a {@code threshold}, this takes a model and
 * the detection and linking settings. Everything after the objects exist — size
 * bounds, morphology predicates, the redirect image — is deliberately identical,
 * so the two plugins' filters mean the same thing.
 *
 * <p>Build one with {@link OC3DSD#builder(ImagePlus)}.
 */
public final class OC3DSDParameters {

    /** Sink for non-fatal warnings (an unsupported feature name, a missing calibration). */
    public interface WarningSink {
        void warn(String message);
    }

    public static final WarningSink NO_OP_WARNING_SINK = new WarningSink() {
        @Override public void warn(String message) {
        }
    };

    /** The stack to detect on. Never null. */
    public final ImagePlus input;
    /** 1-based channel to detect on. */
    public final int channel;
    /** StarDist model {@code .zip}. Never null by the time the engine sees it. */
    public final File modelFile;
    /** Detection probability threshold, 0..1. */
    public final double probability;
    /** Non-maximum-suppression overlap threshold, 0..1. */
    public final double overlap;
    /** Linking distances, slice gap and the minimum slice span. Never null. */
    public final StarDistLinkingParams linking;
    /** Detector-level filters applied before measurement. Never null. */
    public final StarDistPostFilters detectorFilters;

    /** Minimum object size in voxels. */
    public final int minSize;
    /** Maximum object size in voxels. */
    public final int maxSize;
    /** Whether to exclude objects touching the image edges. */
    public final boolean excludeOnEdges;
    /** Morphology predicates; an object must pass <em>all</em> of them to survive. */
    public final List<MorphPredicate> morphPredicates;
    /** Optional intensity-measurement source (the "redirect" image). May be null. */
    public final ImagePlus intensityImage;

    /** Which maps to build. Building none is the cheap path for scripted use. */
    public final boolean buildObjectMap;
    public final boolean buildSurfaceMap;
    public final boolean buildCentroidMap;
    public final boolean buildCentreOfMassMap;

    /** Non-fatal warning sink. Never null. */
    public final WarningSink warningSink;

    OC3DSDParameters(ImagePlus input,
                     int channel,
                     File modelFile,
                     double probability,
                     double overlap,
                     StarDistLinkingParams linking,
                     StarDistPostFilters detectorFilters,
                     int minSize,
                     int maxSize,
                     boolean excludeOnEdges,
                     List<MorphPredicate> morphPredicates,
                     ImagePlus intensityImage,
                     boolean buildObjectMap,
                     boolean buildSurfaceMap,
                     boolean buildCentroidMap,
                     boolean buildCentreOfMassMap,
                     WarningSink warningSink) {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        this.input = input;
        this.channel = Math.max(1, channel);
        this.modelFile = modelFile;
        this.probability = probability;
        this.overlap = overlap;
        this.linking = linking == null ? StarDistLinkingParams.defaults() : linking;
        this.detectorFilters = detectorFilters == null ? StarDistPostFilters.none() : detectorFilters;
        this.minSize = Math.max(0, minSize);
        this.maxSize = Math.max(this.minSize, maxSize);
        this.excludeOnEdges = excludeOnEdges;
        this.morphPredicates = immutableCopy(morphPredicates);
        this.intensityImage = intensityImage;
        this.buildObjectMap = buildObjectMap;
        this.buildSurfaceMap = buildSurfaceMap;
        this.buildCentroidMap = buildCentroidMap;
        this.buildCentreOfMassMap = buildCentreOfMassMap;
        this.warningSink = warningSink == null ? NO_OP_WARNING_SINK : warningSink;
    }

    private static List<MorphPredicate> immutableCopy(List<MorphPredicate> source) {
        if (source == null || source.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<MorphPredicate>(source));
    }
}
