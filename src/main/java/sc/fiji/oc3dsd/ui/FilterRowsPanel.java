package sc.fiji.oc3dsd.ui;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * Fixed native-style min/max morphology filter table backed by
 * {@link OC3DSDDialogModel}. Default ranges are non-excluding; the engine
 * only emits predicates for values the user tightens away from those defaults.
 */
public final class FilterRowsPanel extends JPanel {

    private static final int INPUT_FIELD_COLUMNS = 4;

    /** Callback notifying the parent dialog that filters changed. */
    public interface ChangeCallback {
        void filtersChanged();
    }

    private final OC3DSDDialogModel model;
    private final ChangeCallback callback;
    private final Component minSizeControl;
    private final Component maxSizeControl;
    private final JPanel rowsContainer;
    private boolean refreshing;

    public FilterRowsPanel(OC3DSDDialogModel model, ChangeCallback callback) {
        this(model, callback, null, null);
    }

    public FilterRowsPanel(OC3DSDDialogModel model,
                           ChangeCallback callback,
                           Component minSizeControl,
                           Component maxSizeControl) {
        super();
        if (model == null) {
            throw new IllegalArgumentException("model must not be null (model=null).");
        }
        this.model = model;
        this.callback = callback == null ? new ChangeCallback() {
            @Override public void filtersChanged() {}
        } : callback;
        this.minSizeControl = minSizeControl;
        this.maxSizeControl = maxSizeControl;

        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setAlignmentX(Component.LEFT_ALIGNMENT);

        this.rowsContainer = new JPanel(new GridBagLayout());
        rowsContainer.setOpaque(false);
        rowsContainer.setAlignmentX(Component.LEFT_ALIGNMENT);

        add(rowsContainer);
        refresh();
    }

    public void refresh() {
        refreshing = true;
        try {
            rowsContainer.removeAll();
            addHeader();
            int rowOffset = 1;
            if (minSizeControl != null && maxSizeControl != null) {
                addSizeRow(rowOffset);
                rowOffset++;
            }
            for (int i = 0; i < model.featureRanges().size(); i++) {
                addRangeRow(i + rowOffset, model.featureRanges().get(i));
            }
        } finally {
            refreshing = false;
        }
        rowsContainer.revalidate();
        rowsContainer.repaint();
    }

    private void addHeader() {
        GridBagConstraints gbc = baseConstraints(0);
        gbc.gridx = 0;
        rowsContainer.add(header(""), gbc);
        gbc.gridx = 1;
        rowsContainer.add(header("Min"), gbc);
        gbc.gridx = 2;
        rowsContainer.add(header("Max"), gbc);
    }

    private void addSizeRow(int row) {
        GridBagConstraints gbc = baseConstraints(row);
        gbc.gridx = 0;
        rowsContainer.add(new JLabel("Size (Voxels)"), gbc);

        gbc.gridx = 1;
        rowsContainer.add(minSizeControl, gbc);

        gbc.gridx = 2;
        rowsContainer.add(maxSizeControl, gbc);
    }

    private void addRangeRow(int row, OC3DSDDialogModel.FeatureRange range) {
        GridBagConstraints gbc = baseConstraints(row);
        gbc.gridx = 0;
        rowsContainer.add(new JLabel(range.label), gbc);

        JTextField min = inputField(range.minText);
        min.setToolTipText(range.feature + " minimum");
        bind(min, range, true);
        gbc.gridx = 1;
        rowsContainer.add(min, gbc);

        JTextField max = inputField(range.maxText);
        max.setToolTipText(range.feature + " maximum; Infinity is allowed");
        bind(max, range, false);
        gbc.gridx = 2;
        rowsContainer.add(max, gbc);
    }

    private static JTextField inputField(String text) {
        JTextField field = new JTextField(text, INPUT_FIELD_COLUMNS);
        java.awt.Dimension preferred = field.getPreferredSize();
        field.setPreferredSize(preferred);
        field.setMinimumSize(preferred);
        field.setMaximumSize(preferred);
        return field;
    }

    private void bind(final JTextField field,
                      final OC3DSDDialogModel.FeatureRange range,
                      final boolean minField) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) {
                update();
            }

            @Override public void removeUpdate(DocumentEvent e) {
                update();
            }

            @Override public void changedUpdate(DocumentEvent e) {
                update();
            }

            private void update() {
                if (refreshing) return;
                if (minField) {
                    range.minText = field.getText();
                } else {
                    range.maxText = field.getText();
                }
                callback.filtersChanged();
            }
        });
    }

    private static JLabel header(String text) {
        JLabel label = new JLabel(text);
        label.setBorder(BorderFactory.createEmptyBorder(0, 0, 2, 0));
        return label;
    }

    private static GridBagConstraints baseConstraints(int row) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridy = row;
        gbc.insets = new Insets(1, 0, 3, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.NONE;
        return gbc;
    }

    @Override
    public boolean isOptimizedDrawingEnabled() {
        return false;
    }
}

