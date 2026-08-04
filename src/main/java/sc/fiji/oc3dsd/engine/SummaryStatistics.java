package sc.fiji.oc3dsd.engine;

import ij.measure.ResultsTable;

/**
 * Reduces the per-object table to one mean, standard deviation, minimum and
 * maximum row per measured column — the native-style summary a 3D Objects
 * Counter user expects underneath the object list.
 * <p>
 * Detector diagnostic columns are summarised too: the mean detection confidence
 * across a run is genuinely useful for judging whether the probability
 * threshold was sensible.
 */
public final class SummaryStatistics {

    private static final String STATISTIC = "Statistic";

    private SummaryStatistics() {
    }

    public static ResultsTable of(ResultsTable objects) {
        ResultsTable summary = new ResultsTable();
        if (objects == null || objects.size() == 0) return summary;

        String[] headings = objects.getHeadings();
        String[] names = new String[]{"Mean", "StdDev", "Min", "Max"};
        for (int i = 0; i < names.length; i++) {
            summary.incrementCounter();
            summary.addValue(STATISTIC, names[i]);
        }

        for (String heading : headings) {
            if (heading == null || heading.isEmpty() || STATISTIC.equals(heading)) continue;
            int column = objects.getColumnIndex(heading);
            if (column == ResultsTable.COLUMN_NOT_FOUND) continue;

            double sum = 0;
            double sumSquares = 0;
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            int count = 0;
            for (int row = 0; row < objects.size(); row++) {
                double value = objects.getValueAsDouble(column, row);
                if (Double.isNaN(value) || !Double.isFinite(value)) continue;
                sum += value;
                sumSquares += value * value;
                if (value < min) min = value;
                if (value > max) max = value;
                count++;
            }
            if (count == 0) {
                for (int row = 0; row < names.length; row++) {
                    summary.setValue(heading, row, Double.NaN);
                }
                continue;
            }
            double mean = sum / count;
            double variance = (sumSquares / count) - (mean * mean);
            double stdDev = variance > 0 ? Math.sqrt(variance) : 0.0;

            summary.setValue(heading, 0, mean);
            summary.setValue(heading, 1, stdDev);
            summary.setValue(heading, 2, min);
            summary.setValue(heading, 3, max);
        }
        return summary;
    }
}
