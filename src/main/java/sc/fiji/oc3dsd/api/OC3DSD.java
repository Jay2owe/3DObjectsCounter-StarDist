package sc.fiji.oc3dsd.api;

import ij.ImagePlus;
import sc.fiji.oc3dsd.engine.OC3DSDRunner;
import sc.fiji.oc3dsd.engine.StarDistLinkingParams;
import sc.fiji.oc3dsd.engine.StarDistPostFilters;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Headless facade for 3D Objects Counter - StarDist.
 * <p>
 * {@link #run(OC3DSDParameters)} opens no dialogs, shows no windows, writes no
 * files, and does not require an active ImageJ window. It is safe to call from
 * a script, another plugin, or a headless Fiji.
 * <p>
 * <strong>One caveat that cannot be engineered away.</strong> TensorFlow
 * inference is process-global. Concurrent calls are serialised internally, so
 * parallelising this call across images gains nothing and risks exhausting
 * memory — each in-flight run holds roughly twice its stack while the model is
 * also resident. Process images one at a time.
 *
 * <pre>
 * OC3DSDParameters params = OC3DSD.builder(stack)
 *         .channel(1)
 *         .probability(0.5)
 *         .overlap(0.4)
 *         .linkingDistance(5.0)
 *         .minSlices(1)
 *         .build();
 *
 * OC3DSDResult result = OC3DSD.run(params);
 * </pre>
 */
public final class OC3DSD {

    private OC3DSD() {
    }

    /** Runs detection, linking and measurement. */
    public static OC3DSDResult run(OC3DSDParameters params) {
        return OC3DSDRunner.run(params);
    }

    public static Builder builder(ImagePlus input) {
        return new Builder(input);
    }

    /** Fluent builder. Defaults match the dialog's defaults exactly. */
    public static final class Builder {

        private final ImagePlus input;
        private int channel = 1;
        private File modelFile;
        private double probability = 0.5;
        private double overlap = 0.4;
        private double linkingDistance = 5.0;
        private double gapDistance = 5.0;
        private int sliceGap = 1;
        private int minSlices = 1;
        private double detectorAreaMin = 0;
        private double detectorAreaMax = Double.POSITIVE_INFINITY;
        private double detectorQualityMin = 0;
        private double detectorIntensityMin = 0;
        private int minSize = 10;
        private int maxSize = Integer.MAX_VALUE;
        private boolean excludeOnEdges = false;
        private final List<MorphPredicate> filters = new ArrayList<MorphPredicate>();
        private ImagePlus intensityImage;
        private boolean buildObjectMap = true;
        private boolean buildSurfaceMap = true;
        private boolean buildCentroidMap = true;
        private boolean buildCentreOfMassMap = true;
        private OC3DSDParameters.WarningSink warningSink;

        Builder(ImagePlus input) {
            this.input = input;
        }

        /** 1-based channel to detect on. */
        public Builder channel(int value) {
            this.channel = value;
            return this;
        }

        /**
         * StarDist model {@code .zip}. Leave unset to use the bundled
         * versatile-fluorescence model.
         */
        public Builder modelFile(File value) {
            this.modelFile = value;
            return this;
        }

        public Builder probability(double value) {
            this.probability = value;
            return this;
        }

        /** Non-maximum-suppression overlap threshold. */
        public Builder overlap(double value) {
            this.overlap = value;
            return this;
        }

        /** Maximum centroid movement between consecutive slices, in calibrated units. */
        public Builder linkingDistance(double value) {
            this.linkingDistance = value;
            return this;
        }

        /** Maximum centroid movement across a slice gap, in calibrated units. */
        public Builder gapDistance(double value) {
            this.gapDistance = value;
            return this;
        }

        /** How many consecutive slices an object may be missing from and still link. */
        public Builder sliceGap(int value) {
            this.sliceGap = value;
            return this;
        }

        /**
         * Minimum Z-slices an object must span. The default of 1 keeps
         * single-slice objects; raising it discards them and the count is
         * reported either way.
         */
        public Builder minSlices(int value) {
            this.minSlices = value;
            return this;
        }

        public Builder detectorAreaRange(double min, double max) {
            this.detectorAreaMin = min;
            this.detectorAreaMax = max;
            return this;
        }

        public Builder detectorQualityMin(double value) {
            this.detectorQualityMin = value;
            return this;
        }

        public Builder detectorIntensityMin(double value) {
            this.detectorIntensityMin = value;
            return this;
        }

        public Builder minSize(int value) {
            this.minSize = value;
            return this;
        }

        public Builder maxSize(int value) {
            this.maxSize = value;
            return this;
        }

        public Builder excludeOnEdges(boolean value) {
            this.excludeOnEdges = value;
            return this;
        }

        public Builder addFilter(MorphPredicate predicate) {
            if (predicate != null) this.filters.add(predicate);
            return this;
        }

        /** Optional intensity-measurement source (the "redirect" image). */
        public Builder intensityImage(ImagePlus value) {
            this.intensityImage = value;
            return this;
        }

        public Builder buildObjectMap(boolean value) {
            this.buildObjectMap = value;
            return this;
        }

        public Builder buildSurfaceMap(boolean value) {
            this.buildSurfaceMap = value;
            return this;
        }

        public Builder buildCentroidMap(boolean value) {
            this.buildCentroidMap = value;
            return this;
        }

        public Builder buildCentreOfMassMap(boolean value) {
            this.buildCentreOfMassMap = value;
            return this;
        }

        /** Skips every map. The cheap path for scripted counting. */
        public Builder noMaps() {
            return buildObjectMap(false).buildSurfaceMap(false)
                    .buildCentroidMap(false).buildCentreOfMassMap(false);
        }

        public Builder warningSink(OC3DSDParameters.WarningSink value) {
            this.warningSink = value;
            return this;
        }

        public OC3DSDParameters build() {
            return new OC3DSDParameters(
                    input,
                    channel,
                    modelFile,
                    probability,
                    overlap,
                    new StarDistLinkingParams(linkingDistance, gapDistance, sliceGap, minSlices),
                    new StarDistPostFilters(detectorAreaMin, detectorAreaMax,
                            detectorQualityMin, detectorIntensityMin),
                    minSize,
                    maxSize,
                    excludeOnEdges,
                    filters,
                    intensityImage,
                    buildObjectMap,
                    buildSurfaceMap,
                    buildCentroidMap,
                    buildCentreOfMassMap,
                    warningSink);
        }
    }
}
