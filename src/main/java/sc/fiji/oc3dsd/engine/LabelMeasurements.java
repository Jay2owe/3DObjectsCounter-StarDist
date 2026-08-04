package sc.fiji.oc3dsd.engine;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.process.ImageProcessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class LabelMeasurements {

    static final String MAX_DENSE_LABEL_PROPERTY =
            "sc.fiji.oc3dsd.maxDenseLabelMeasurementsLabel";
    private static final int DEFAULT_MAX_DENSE_LABEL = 8 * 1024 * 1024;
    private static final int INITIAL_DENSE_LABELS = 1024;
    private static final double INV_SQRT_2 = 1.0 / Math.sqrt(2.0);
    private static final double INV_SQRT_3 = 1.0 / Math.sqrt(3.0);
    private static final double EIGENVALUE_ZERO_TOLERANCE = 1.0e-12;
    // Bounded Feret estimate: fixed directional extrema, not exact pairwise boundary distance.
    private static final double[][] FERET_DIRECTIONS = {
            {1.0, 0.0, 0.0},
            {0.0, 1.0, 0.0},
            {0.0, 0.0, 1.0},
            {INV_SQRT_2, INV_SQRT_2, 0.0},
            {INV_SQRT_2, -INV_SQRT_2, 0.0},
            {INV_SQRT_2, 0.0, INV_SQRT_2},
            {INV_SQRT_2, 0.0, -INV_SQRT_2},
            {0.0, INV_SQRT_2, INV_SQRT_2},
            {0.0, INV_SQRT_2, -INV_SQRT_2},
            {INV_SQRT_3, INV_SQRT_3, INV_SQRT_3},
            {INV_SQRT_3, INV_SQRT_3, -INV_SQRT_3},
            {INV_SQRT_3, -INV_SQRT_3, INV_SQRT_3},
            {-INV_SQRT_3, INV_SQRT_3, INV_SQRT_3}
    };

    private LabelMeasurements() {
        // Utility class.
    }

    static Result scan(ImagePlus labelImage,
                       ImagePlus intensityImage,
                       Calibration calibration) {
        validateImages(labelImage, intensityImage);

        ImageStack labelStack = labelImage.getStack();
        ImageStack intensityStack = intensityImage == null ? null : intensityImage.getStack();
        int width = labelImage.getWidth();
        int height = labelImage.getHeight();
        int depth = labelStack.getSize();
        Calibration effectiveCalibration = calibration == null ? labelImage.getCalibration() : calibration;
        CalibrationScales scales = CalibrationScales.from(effectiveCalibration);
        FeatureValuesByLabel valuesByLabel = new FeatureValuesByLabel();

        for (int z = 0; z < depth; z++) {
            ImageProcessor labelProcessor = labelStack.getProcessor(z + 1);
            ImageProcessor intensityProcessor = intensityStack == null ? null
                    : intensityStack.getProcessor(z + 1);
            validateProcessor(labelProcessor, width, height, "label");
            if (intensityProcessor != null) {
                validateProcessor(intensityProcessor, width, height, "intensity");
            }
            for (int y = 0; y < height; y++) {
                int offset = y * width;
                for (int x = 0; x < width; x++) {
                    int index = offset + x;
                    int label = labelFromPixel(labelProcessor.getf(index));
                    if (label <= 0) continue;
                    FeatureValues values = valuesByLabel.getOrCreate(label);
                    values.addVoxel(x, y, z, scales);
                    if (intensityProcessor != null) {
                        float intensity = intensityProcessor.getf(index);
                        if (Float.isFinite(intensity)) {
                            values.addIntensity(intensity, x, y, z);
                        }
                    }
                }
            }
        }

        accumulateSurfaceValues(labelStack, width, height, depth, valuesByLabel, scales);
        valuesByLabel.finish(scales);
        return new Result(valuesByLabel, scales.unit);
    }

    private static void validateImages(ImagePlus labelImage, ImagePlus intensityImage) {
        if (labelImage == null) {
            throw new IllegalArgumentException("labelImage must not be null");
        }
        ImageStack labelStack = labelImage.getStack();
        if (labelStack == null || labelStack.getSize() <= 0
                || labelImage.getWidth() <= 0 || labelImage.getHeight() <= 0) {
            throw new IllegalArgumentException("labelImage must have a non-empty stack");
        }
        if (intensityImage == null) return;
        ImageStack intensityStack = intensityImage.getStack();
        if (intensityStack == null || intensityStack.getSize() <= 0) {
            throw new IllegalArgumentException("intensityImage must have a non-empty stack");
        }
        if (intensityImage.getWidth() != labelImage.getWidth()
                || intensityImage.getHeight() != labelImage.getHeight()
                || intensityStack.getSize() != labelStack.getSize()) {
            throw new IllegalArgumentException("intensityImage dimensions must match labelImage");
        }
    }

    private static void validateProcessor(ImageProcessor processor,
                                          int width,
                                          int height,
                                          String imageName) {
        if (processor == null || processor.getPixelCount() < width * height) {
            throw new IllegalArgumentException(imageName + " stack has an invalid slice");
        }
    }

    private static void accumulateSurfaceValues(ImageStack labelStack,
                                                int width,
                                                int height,
                                                int depth,
                                                FeatureValuesByLabel valuesByLabel,
                                                CalibrationScales scales) {
        for (int z = 0; z < depth; z++) {
            ImageProcessor previous = z == 0 ? null : labelStack.getProcessor(z);
            ImageProcessor current = labelStack.getProcessor(z + 1);
            ImageProcessor next = z == depth - 1 ? null : labelStack.getProcessor(z + 2);
            validateProcessor(current, width, height, "label");
            for (int y = 0; y < height; y++) {
                int offset = y * width;
                for (int x = 0; x < width; x++) {
                    int index = offset + x;
                    int label = labelFromPixel(current.getf(index));
                    if (label <= 0) continue;

                    boolean xMinus = x == 0 || labelAt(current, index - 1) != label;
                    boolean xPlus = x == width - 1 || labelAt(current, index + 1) != label;
                    boolean yMinus = y == 0 || labelAt(current, index - width) != label;
                    boolean yPlus = y == height - 1 || labelAt(current, index + width) != label;
                    boolean zMinus = z == 0 || labelAt(previous, index) != label;
                    boolean zPlus = z == depth - 1 || labelAt(next, index) != label;

                    double exposedArea = 0.0;
                    if (xMinus) exposedArea += scales.yzFaceArea;
                    if (xPlus) exposedArea += scales.yzFaceArea;
                    if (yMinus) exposedArea += scales.xzFaceArea;
                    if (yPlus) exposedArea += scales.xzFaceArea;
                    if (zMinus) exposedArea += scales.xyFaceArea;
                    if (zPlus) exposedArea += scales.xyFaceArea;

                    if (exposedArea > 0.0) {
                        FeatureValues values = valuesByLabel.get(label);
                        if (values != null) {
                            values.surfaceVoxelCount++;
                            values.surfaceArea += exposedArea;
                            values.correctedSurfacePixels += correctedSurfaceWeight(
                                    xMinus, xPlus, yMinus, yPlus, zMinus, zPlus);
                        }
                    }
                }
            }
        }
    }

    /**
     * Per-surface-voxel area weight from Lindblad (2005), "Surface area estimation
     * of digitized 3D objects using weighted local configurations". Reproduces
     * mcib3d-core {@code Object3DVoxels.computeContours()} corrected surface exactly:
     * voxels are classified by exposed-face count (1-6), with the 3-face case split
     * by whether an opposite pair of faces is exposed. The weights are in cubic-voxel
     * (pixel) units and deliberately ignore anisotropic calibration, matching mcib3d's
     * corrected sphericity/compactness. A voxel with all 6 faces exposed (an isolated
     * single voxel) contributes nothing, exactly as mcib3d does.
     */
    private static double correctedSurfaceWeight(boolean xMinus, boolean xPlus,
                                                 boolean yMinus, boolean yPlus,
                                                 boolean zMinus, boolean zPlus) {
        int face = 0;
        if (xMinus) face++;
        if (xPlus) face++;
        if (yMinus) face++;
        if (yPlus) face++;
        if (zMinus) face++;
        if (zPlus) face++;
        boolean oppositePair = (xMinus && xPlus) || (yMinus && yPlus) || (zMinus && zPlus);
        switch (face) {
            case 1: return 0.894;
            case 2: return 1.3409;
            case 3: return oppositePair ? 2.0 : 1.5879;
            case 4: return 8.0 / 3.0;
            case 5: return 10.0 / 3.0;
            default: return 0.0; // face == 6 isolated voxel contributes nothing, as in mcib3d
        }
    }

    private static int labelAt(ImageProcessor processor, int index) {
        return processor == null ? 0 : labelFromPixel(processor.getf(index));
    }

    private static int labelFromPixel(float value) {
        if (!Float.isFinite(value) || value <= 0f) return 0;
        if (value > Integer.MAX_VALUE) return 0;
        return Math.round(value);
    }

    private static int configuredMaxDenseLabel() {
        String configured = System.getProperty(MAX_DENSE_LABEL_PROPERTY);
        if (configured == null || configured.trim().isEmpty()) {
            return DEFAULT_MAX_DENSE_LABEL;
        }
        try {
            int parsed = Integer.parseInt(configured.trim());
            return parsed < 0 ? 0 : parsed;
        } catch (NumberFormatException invalidValue) {
            return DEFAULT_MAX_DENSE_LABEL;
        }
    }

    private static String unitOf(Calibration calibration) {
        if (calibration == null) return "pixel";
        String unit = calibration.getUnit();
        if (unit == null || unit.trim().isEmpty()) return "pixel";
        return unit;
    }

    static ResultsTable emptyStatisticsTable(Calibration calibration) {
        ResultsTable table = new ResultsTable();
        String unit = unitOf(calibration);
        Result.initialiseStatisticsHeadings(
                table,
                "Volume (" + unit + "^3)",
                "Surface (" + unit + "^2)");
        return table;
    }

    private static double positiveOrOne(double value) {
        return Double.isFinite(value) && value > 0.0 ? value : 1.0;
    }

    static final class Result {
        private final FeatureValuesByLabel valuesByLabel;
        private final String unit;

        private Result(FeatureValuesByLabel valuesByLabel, String unit) {
            this.valuesByLabel = valuesByLabel;
            this.unit = unit == null || unit.trim().isEmpty() ? "pixel" : unit;
        }

        FeatureValues valuesForLabel(int label) {
            return valuesByLabel.get(label);
        }

        List<Integer> labelsSorted() {
            return valuesByLabel.labelsSorted();
        }

        boolean usesSparseStorage() {
            return valuesByLabel.usesSparseStorage();
        }

        ResultsTable toStatisticsTable(ResultsTable templateOrNull) {
            ResultsTable table = templateOrNull == null ? new ResultsTable() : copyOf(templateOrNull);
            String volumeColumn = "Volume (" + unit + "^3)";
            String surfaceColumn = "Surface (" + unit + "^2)";

            if (table.size() == 0) {
                initialiseStatisticsHeadings(table, volumeColumn, surfaceColumn);
                List<Integer> labels = labelsSorted();
                for (int i = 0; i < labels.size(); i++) {
                    table.incrementCounter();
                    FeatureValues values = valuesForLabel(labels.get(i).intValue());
                    if (values != null) {
                        writeStatisticsRow(table, i, values, volumeColumn, surfaceColumn);
                    }
                }
                return table;
            }

            for (int row = 0; row < table.size(); row++) {
                FeatureValues values = valuesForLabel(labelForRow(table, row));
                if (values != null) {
                    writeStatisticsRow(table, row, values, volumeColumn, surfaceColumn);
                }
            }
            return table;
        }

        private static void initialiseStatisticsHeadings(ResultsTable table,
                                                         String volumeColumn,
                                                         String surfaceColumn) {
            table.setHeading(0, volumeColumn);
            table.setHeading(1, surfaceColumn);
            table.setHeading(2, "Nb of obj. voxels");
            table.setHeading(3, "Nb of surf. voxels");
            table.setHeading(4, "IntDen");
            table.setHeading(5, "Mean");
            table.setHeading(6, "StdDev");
            table.setHeading(7, "Min");
            table.setHeading(8, "Max");
            table.setHeading(9, "X");
            table.setHeading(10, "Y");
            table.setHeading(11, "Z");
            table.setHeading(12, "XM");
            table.setHeading(13, "YM");
            table.setHeading(14, "ZM");
            table.setHeading(15, "Morph_Sphericity");
            table.setHeading(16, "Morph_Compactness");
            table.setHeading(17, "Morph_Elongation");
            table.setHeading(18, "Morph_Feret3D_um");
            table.setHeading(19, "BX");
            table.setHeading(20, "BY");
            table.setHeading(21, "BZ");
            table.setHeading(22, "B-width");
            table.setHeading(23, "B-height");
            table.setHeading(24, "B-depth");
            table.setHeading(25, "Label");
        }

        private static void writeStatisticsRow(ResultsTable table,
                                               int row,
                                               FeatureValues values,
                                               String volumeColumn,
                                               String surfaceColumn) {
            table.setValue(volumeColumn, row, values.calibratedVolume);
            table.setValue(surfaceColumn, row, values.surfaceArea);
            table.setValue("Nb of obj. voxels", row, values.voxelCount);
            table.setValue("Nb of surf. voxels", row, values.surfaceVoxelCount);
            setFiniteOrNaN(table, "IntDen", row, values.intensitySum());
            setFiniteOrNaN(table, "Mean", row, values.intensityMean());
            setFiniteOrNaN(table, "StdDev", row, values.intensityStdDev());
            setFiniteOrNaN(table, "Min", row, values.intensityMin());
            setFiniteOrNaN(table, "Max", row, values.intensityMax());
            table.setValue("X", row, values.centroidX());
            table.setValue("Y", row, values.centroidY());
            table.setValue("Z", row, values.centroidZ());
            table.setValue("XM", row, values.centerOfMassX());
            table.setValue("YM", row, values.centerOfMassY());
            table.setValue("ZM", row, values.centerOfMassZ());
            setFiniteOrNaN(table, "Morph_Sphericity", row, values.sphericity());
            setFiniteOrNaN(table, "Morph_Compactness", row, values.compactness());
            setFiniteOrNaN(table, "Morph_Elongation", row, values.elongation());
            setFiniteOrNaN(table, "Morph_Feret3D_um", row, values.feretDiameterMax());
            table.setValue("BX", row, values.minX);
            table.setValue("BY", row, values.minY);
            table.setValue("BZ", row, values.minZ);
            table.setValue("B-width", row, values.boundingWidth());
            table.setValue("B-height", row, values.boundingHeight());
            table.setValue("B-depth", row, values.boundingDepth());
            table.setValue("Label", row, values.label);
        }

        private static void setFiniteOrNaN(ResultsTable table,
                                           String column,
                                           int row,
                                           double value) {
            table.setValue(column, row, Double.isFinite(value) ? value : Double.NaN);
        }

        private static ResultsTable copyOf(ResultsTable source) {
            ResultsTable copy = new ResultsTable();
            if (source == null || source.size() == 0) return copy;
            String[] headings = source.getHeadings();
            for (int row = 0; row < source.size(); row++) {
                copy.incrementCounter();
                if (headings == null) continue;
                for (int h = 0; h < headings.length; h++) {
                    String heading = headings[h];
                    if (heading == null || heading.trim().isEmpty()) continue;
                    try {
                        copy.setValue(heading, row, source.getValue(heading, row));
                    } catch (RuntimeException unreadableCell) {
                        // ResultsTable columns can be sparse; leave unreadable cells empty.
                    }
                }
            }
            return copy;
        }

        private static int labelForRow(ResultsTable table, int row) {
            if (table == null || table.getColumnIndex("Label") < 0) return row + 1;
            try {
                double label = table.getValue("Label", row);
                return Double.isFinite(label) && label > 0.0 ? (int) Math.round(label) : row + 1;
            } catch (RuntimeException unreadableCell) {
                return row + 1;
            }
        }
    }

    static final class FeatureValues {
        final int label;
        long voxelCount;
        long surfaceVoxelCount;
        double calibratedVolume;
        double surfaceArea;
        // Lindblad (2005) weighted-configuration surface in cubic-voxel (pixel) units.
        // Used for mcib3d-aligned corrected sphericity/compactness, NOT the reported
        // Surface column (which stays the calibrated contact surface above).
        double correctedSurfacePixels;
        double intensitySum;
        double intensitySumSquares;
        double intensityMin = Double.POSITIVE_INFINITY;
        double intensityMax = Double.NEGATIVE_INFINITY;
        double xSum;
        double ySum;
        double zSum;
        double intensityWeightedX;
        double intensityWeightedY;
        double intensityWeightedZ;
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        double xxSum;
        double yySum;
        double zzSum;
        double xySum;
        double xzSum;
        double yzSum;
        private double shapeXSum;
        private double shapeYSum;
        private double shapeZSum;
        private double shapeXXSum;
        private double shapeYYSum;
        private double shapeZZSum;
        private double shapeXYSum;
        private double shapeXZSum;
        private double shapeYZSum;
        private double elongation = Double.NaN;
        private double feretDiameterMax = Double.NaN;
        private double[] feretMin;
        private double[] feretMax;
        private long intensityCount;

        private FeatureValues(int label) {
            this.label = label;
        }

        private void addVoxel(double x, double y, double z, CalibrationScales scales) {
            voxelCount++;
            xSum += x;
            ySum += y;
            zSum += z;
            xxSum += x * x;
            yySum += y * y;
            zzSum += z * z;
            xySum += x * y;
            xzSum += x * z;
            yzSum += y * z;
            double px = x * scales.pixelWidth;
            double py = y * scales.pixelHeight;
            double pz = z * scales.pixelDepth;
            shapeXSum += px;
            shapeYSum += py;
            shapeZSum += pz;
            shapeXXSum += px * px;
            shapeYYSum += py * py;
            shapeZZSum += pz * pz;
            shapeXYSum += px * py;
            shapeXZSum += px * pz;
            shapeYZSum += py * pz;
            addFeretPoint(px, py, pz);
            int ix = (int) x;
            int iy = (int) y;
            int iz = (int) z;
            if (ix < minX) minX = ix;
            if (iy < minY) minY = iy;
            if (iz < minZ) minZ = iz;
            if (ix > maxX) maxX = ix;
            if (iy > maxY) maxY = iy;
            if (iz > maxZ) maxZ = iz;
        }

        private void addIntensity(double intensity, double x, double y, double z) {
            intensityCount++;
            intensitySum += intensity;
            intensitySumSquares += intensity * intensity;
            if (intensity < intensityMin) intensityMin = intensity;
            if (intensity > intensityMax) intensityMax = intensity;
            intensityWeightedX += intensity * x;
            intensityWeightedY += intensity * y;
            intensityWeightedZ += intensity * z;
        }

        private void finish(CalibrationScales scales) {
            calibratedVolume = voxelCount * scales.voxelVolume;
            elongation = computeElongation();
            feretDiameterMax = computeFeretDiameterMax();
        }

        boolean hasIntensityValues() {
            return intensityCount > 0;
        }

        double intensitySum() {
            return hasIntensityValues() ? intensitySum : Double.NaN;
        }

        double intensityMean() {
            return hasIntensityValues() ? intensitySum / (double) intensityCount : Double.NaN;
        }

        double meanIntensity() {
            return intensityMean();
        }

        double intensityMin() {
            return hasIntensityValues() ? intensityMin : Double.NaN;
        }

        double intensityMax() {
            return hasIntensityValues() ? intensityMax : Double.NaN;
        }

        double maxIntensity() {
            return intensityMax();
        }

        double elongation() {
            return elongation;
        }

        double feretDiameterMax() {
            return feretDiameterMax;
        }

        /**
         * Compactness, {@code 36*pi*V^2 / S^3}, using the voxel count as volume
         * and the Lindblad-weighted corrected surface. Both are in cubic-voxel
         * (pixel) units and deliberately ignore anisotropic calibration, which
         * is what mcib3d's {@code Object3DVoxels.getCompactness()} does — so the
         * number here means the same thing as the one 3D Objects Counter+
         * reports for the same object.
         * <p>
         * 3D Objects Counter+ leaves this column to the native counter and
         * writes NaN from its own accumulator. This plugin has no native counter
         * in the path — it holds a label image from the start — so the value is
         * computed here from the corrected surface that was already being
         * accumulated for exactly this purpose.
         */
        double compactness() {
            if (voxelCount <= 0 || !(correctedSurfacePixels > 0.0)) return Double.NaN;
            double v = (double) voxelCount;
            double s = correctedSurfacePixels;
            return (36.0 * Math.PI * v * v) / (s * s * s);
        }

        /** Sphericity, the cube root of {@link #compactness()}; 1 for a perfect sphere. */
        double sphericity() {
            double c = compactness();
            if (Double.isNaN(c) || c < 0.0) return Double.NaN;
            return Math.cbrt(c);
        }

        double intensityStdDev() {
            if (!hasIntensityValues()) return Double.NaN;
            double mean = intensityMean();
            double variance = (intensitySumSquares / (double) intensityCount) - (mean * mean);
            if (variance < 0.0 && variance > -1.0e-9) variance = 0.0;
            return variance < 0.0 ? Double.NaN : Math.sqrt(variance);
        }

        double centroidX() {
            return voxelCount <= 0 ? Double.NaN : xSum / (double) voxelCount;
        }

        double centroidY() {
            return voxelCount <= 0 ? Double.NaN : ySum / (double) voxelCount;
        }

        double centroidZ() {
            return voxelCount <= 0 ? Double.NaN : zSum / (double) voxelCount;
        }

        double centerOfMassX() {
            return hasWeightedCenter() ? intensityWeightedX / intensitySum : centroidX();
        }

        double centerOfMassY() {
            return hasWeightedCenter() ? intensityWeightedY / intensitySum : centroidY();
        }

        double centerOfMassZ() {
            return hasWeightedCenter() ? intensityWeightedZ / intensitySum : centroidZ();
        }

        int boundingWidth() {
            return voxelCount <= 0 ? 0 : maxX - minX + 1;
        }

        int boundingHeight() {
            return voxelCount <= 0 ? 0 : maxY - minY + 1;
        }

        int boundingDepth() {
            return voxelCount <= 0 ? 0 : maxZ - minZ + 1;
        }

        private boolean hasWeightedCenter() {
            return hasIntensityValues() && Double.isFinite(intensitySum) && intensitySum != 0.0;
        }

        private void addFeretPoint(double x, double y, double z) {
            if (feretMin == null) {
                feretMin = new double[FERET_DIRECTIONS.length];
                feretMax = new double[FERET_DIRECTIONS.length];
                Arrays.fill(feretMin, Double.POSITIVE_INFINITY);
                Arrays.fill(feretMax, Double.NEGATIVE_INFINITY);
            }
            for (int i = 0; i < FERET_DIRECTIONS.length; i++) {
                double[] direction = FERET_DIRECTIONS[i];
                double projection = x * direction[0] + y * direction[1] + z * direction[2];
                if (projection < feretMin[i]) feretMin[i] = projection;
                if (projection > feretMax[i]) feretMax[i] = projection;
            }
        }

        private double computeFeretDiameterMax() {
            if (feretMin == null || feretMax == null) return Double.NaN;
            double maxSpan = 0.0;
            for (int i = 0; i < feretMin.length; i++) {
                if (!Double.isFinite(feretMin[i]) || !Double.isFinite(feretMax[i])) continue;
                double span = feretMax[i] - feretMin[i];
                if (span > maxSpan) {
                    maxSpan = span;
                }
            }
            return maxSpan;
        }

        private double computeElongation() {
            if (voxelCount <= 1) return Double.NaN;
            double invCount = 1.0 / (double) voxelCount;
            double cx = shapeXSum * invCount;
            double cy = shapeYSum * invCount;
            double cz = shapeZSum * invCount;
            double cxx = shapeXXSum * invCount - cx * cx;
            double cyy = shapeYYSum * invCount - cy * cy;
            double czz = shapeZZSum * invCount - cz * cz;
            double cxy = shapeXYSum * invCount - cx * cy;
            double cxz = shapeXZSum * invCount - cx * cz;
            double cyz = shapeYZSum * invCount - cy * cz;
            double[] eigenvalues = symmetricEigenvalues3x3(cxx, cxy, cxz, cyy, cyz, czz);
            Arrays.sort(eigenvalues);
            double smallest = zeroIfTiny(eigenvalues[0]);
            double largest = zeroIfTiny(eigenvalues[2]);
            if (largest <= 0.0 || smallest <= 0.0) return Double.NaN;
            return Math.sqrt(largest / smallest);
        }
    }

    private static double[] symmetricEigenvalues3x3(double cxx,
                                                    double cxy,
                                                    double cxz,
                                                    double cyy,
                                                    double cyz,
                                                    double czz) {
        double p1 = cxy * cxy + cxz * cxz + cyz * cyz;
        if (p1 == 0.0) {
            return new double[] {cxx, cyy, czz};
        }

        double q = (cxx + cyy + czz) / 3.0;
        double axx = cxx - q;
        double ayy = cyy - q;
        double azz = czz - q;
        double p2 = axx * axx + ayy * ayy + azz * azz + 2.0 * p1;
        double p = Math.sqrt(p2 / 6.0);
        if (!Double.isFinite(p) || p <= 0.0) {
            return new double[] {cxx, cyy, czz};
        }

        double bxx = axx / p;
        double byy = ayy / p;
        double bzz = azz / p;
        double bxy = cxy / p;
        double bxz = cxz / p;
        double byz = cyz / p;
        double determinant = bxx * (byy * bzz - byz * byz)
                - bxy * (bxy * bzz - byz * bxz)
                + bxz * (bxy * byz - byy * bxz);
        double r = determinant / 2.0;

        double phi;
        if (r <= -1.0) {
            phi = Math.PI / 3.0;
        } else if (r >= 1.0) {
            phi = 0.0;
        } else {
            phi = Math.acos(r) / 3.0;
        }

        double largest = q + 2.0 * p * Math.cos(phi);
        double smallest = q + 2.0 * p * Math.cos(phi + (2.0 * Math.PI / 3.0));
        double middle = 3.0 * q - largest - smallest;
        return new double[] {largest, middle, smallest};
    }

    private static double zeroIfTiny(double value) {
        if (!Double.isFinite(value)) return Double.NaN;
        return Math.abs(value) <= EIGENVALUE_ZERO_TOLERANCE ? 0.0 : value;
    }

    private static final class FeatureValuesByLabel {
        private final int maxDenseLabel;
        private FeatureValues[] dense;
        private Map<Integer, FeatureValues> sparse;

        FeatureValuesByLabel() {
            maxDenseLabel = configuredMaxDenseLabel();
            if (maxDenseLabel > 0) {
                int initialLength = (int) Math.min((long) INITIAL_DENSE_LABELS,
                        (long) maxDenseLabel + 1L);
                dense = new FeatureValues[Math.max(1, initialLength)];
            } else {
                sparse = new HashMap<Integer, FeatureValues>();
            }
        }

        FeatureValues get(int label) {
            if (label <= 0) return null;
            if (dense != null) {
                return label < dense.length ? dense[label] : null;
            }
            return sparse == null ? null : sparse.get(Integer.valueOf(label));
        }

        FeatureValues getOrCreate(int label) {
            if (label <= 0) return null;
            if (dense != null && label <= maxDenseLabel) {
                if (ensureDenseCapacity(label)) {
                    FeatureValues values = dense[label];
                    if (values == null) {
                        values = new FeatureValues(label);
                        dense[label] = values;
                    }
                    return values;
                }
            }
            switchToSparse();
            Integer key = Integer.valueOf(label);
            FeatureValues values = sparse.get(key);
            if (values == null) {
                values = new FeatureValues(label);
                sparse.put(key, values);
            }
            return values;
        }

        void finish(CalibrationScales scales) {
            List<FeatureValues> values = values();
            for (int i = 0; i < values.size(); i++) {
                values.get(i).finish(scales);
            }
        }

        List<Integer> labelsSorted() {
            List<Integer> labels = new ArrayList<Integer>();
            if (dense != null) {
                for (int label = 1; label < dense.length; label++) {
                    if (dense[label] != null) {
                        labels.add(Integer.valueOf(label));
                    }
                }
            } else if (sparse != null) {
                labels.addAll(sparse.keySet());
            }
            Collections.sort(labels);
            return labels;
        }

        boolean usesSparseStorage() {
            return sparse != null;
        }

        private List<FeatureValues> values() {
            List<FeatureValues> values = new ArrayList<FeatureValues>();
            if (dense != null) {
                for (int label = 1; label < dense.length; label++) {
                    if (dense[label] != null) {
                        values.add(dense[label]);
                    }
                }
            } else if (sparse != null) {
                values.addAll(sparse.values());
            }
            return values;
        }

        private boolean ensureDenseCapacity(int label) {
            if (label < dense.length) return true;
            long targetLength = Math.min((long) maxDenseLabel + 1L,
                    Math.max((long) label + 1L, (long) dense.length * 2L));
            while (targetLength <= label && targetLength <= maxDenseLabel) {
                targetLength *= 2L;
            }
            if (targetLength > Integer.MAX_VALUE) return false;
            try {
                dense = Arrays.copyOf(dense, (int) targetLength);
                return true;
            } catch (OutOfMemoryError oom) {
                switchToSparse();
                System.gc();
                return false;
            }
        }

        private void switchToSparse() {
            if (sparse != null) return;
            Map<Integer, FeatureValues> replacement = new HashMap<Integer, FeatureValues>();
            if (dense != null) {
                for (int label = 1; label < dense.length; label++) {
                    FeatureValues values = dense[label];
                    if (values != null) {
                        replacement.put(Integer.valueOf(label), values);
                    }
                }
            }
            sparse = replacement;
            dense = null;
        }
    }

    private static final class CalibrationScales {
        final String unit;
        final double pixelWidth;
        final double pixelHeight;
        final double pixelDepth;
        final double voxelVolume;
        final double yzFaceArea;
        final double xzFaceArea;
        final double xyFaceArea;

        private CalibrationScales(String unit,
                                  double pixelWidth,
                                  double pixelHeight,
                                  double pixelDepth) {
            this.unit = unit;
            this.pixelWidth = pixelWidth;
            this.pixelHeight = pixelHeight;
            this.pixelDepth = pixelDepth;
            voxelVolume = pixelWidth * pixelHeight * pixelDepth;
            yzFaceArea = pixelHeight * pixelDepth;
            xzFaceArea = pixelWidth * pixelDepth;
            xyFaceArea = pixelWidth * pixelHeight;
        }

        static CalibrationScales from(Calibration calibration) {
            return new CalibrationScales(unitOf(calibration),
                    calibration == null ? 1.0 : positiveOrOne(calibration.pixelWidth),
                    calibration == null ? 1.0 : positiveOrOne(calibration.pixelHeight),
                    calibration == null ? 1.0 : positiveOrOne(calibration.pixelDepth));
        }
    }
}

