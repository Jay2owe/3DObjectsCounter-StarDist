package sc.fiji.oc3dsd.ui;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * Tiny self-contained collapsible section so we don't pull in Swing-X
 * just for one widget. A header row with a toggle button on the left and
 * the section title; clicking the header expands/collapses the body.
 */
public final class CollapsiblePane extends JPanel {

    private final JButton toggle;
    private final JPanel body;
    private boolean expanded;

    public CollapsiblePane(String title, boolean startExpanded) {
        super();
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setAlignmentX(Component.LEFT_ALIGNMENT);

        this.expanded = startExpanded;
        this.toggle = new JButton((expanded ? "v " : "> ") + title);
        toggle.setHorizontalAlignment(SwingConstants.LEFT);
        toggle.setBorderPainted(false);
        toggle.setContentAreaFilled(false);
        toggle.setFocusPainted(false);
        toggle.addActionListener(new ActionListener() {
            @Override public void actionPerformed(ActionEvent e) {
                setExpanded(!CollapsiblePane.this.expanded);
            }
        });
        toggle.setAlignmentX(Component.LEFT_ALIGNMENT);

        this.body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.setBorder(BorderFactory.createEmptyBorder(2, 14, 2, 0));
        body.setVisible(expanded);

        add(toggle);
        add(body);
    }

    /** Add components to this to populate the collapsible body. */
    public JComponent body() {
        return body;
    }

    public boolean isExpanded() {
        return expanded;
    }

    public void setExpanded(boolean expanded) {
        if (this.expanded == expanded) return;
        this.expanded = expanded;
        body.setVisible(expanded);
        toggle.setText((expanded ? "v " : "> ") + stripPrefix(toggle.getText()));
        revalidate();
    }

    private static String stripPrefix(String text) {
        if (text == null) return "";
        if (text.startsWith("v ") || text.startsWith("> ")) return text.substring(2);
        return text;
    }

    @Override
    public Dimension getMaximumSize() {
        Dimension preferred = getPreferredSize();
        return new Dimension(Integer.MAX_VALUE, preferred.height);
    }
}

