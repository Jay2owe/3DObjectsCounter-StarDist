package sc.fiji.oc3dsd.ui.tuning;

import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import sc.fiji.oc3dsd.ui.OC3DSDDialogModel;

/** Only selected parameters expose their range/value editors. */
final class TuningPicker extends JDialog {
    private final OC3DSDDialogModel baseline;
    private final List<Row> rows=new ArrayList<>();
    private final JLabel count=new JLabel(" ");
    private final JButton run=new JButton("Build comparison grid");
    private TuningPlan selected;
    TuningPicker(Window owner,OC3DSDDialogModel baseline) {
        super(owner,"Tune StarDist parameters",ModalityType.APPLICATION_MODAL);
        this.baseline=baseline;
        JPanel body=new JPanel();body.setLayout(new BoxLayout(body,BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(16,18,12,18));
        body.add(new JLabel("Select parameters, then enter a range or individual values."));
        body.add(Box.createVerticalStrut(8));
        body.add(new JLabel("Preview: chosen channel, current timepoint, all Z slices."));
        body.add(new JLabel("A rectangular selection limits the preview area; OK later runs the full image."));
        body.add(Box.createVerticalStrut(12));
        for(TuningParameter parameter:TuningParameter.values()) if(parameter.appliesTo(baseline)) {
            Row row=new Row(parameter); rows.add(row);body.add(row.panel);
        }
        JPanel footer=new JPanel(new BorderLayout(12,8));footer.setBorder(BorderFactory.createEmptyBorder(10,18,14,18));
        footer.add(count,BorderLayout.NORTH);
        JPanel buttons=new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancel=new JButton("Back"); cancel.addActionListener(e->dispose());
        run.addActionListener(e->{try{selected=plan();dispose();}catch(IllegalArgumentException error){count.setText(error.getMessage());}});
        buttons.add(cancel);buttons.add(run);footer.add(buttons,BorderLayout.SOUTH);
        add(body,BorderLayout.CENTER);add(footer,BorderLayout.SOUTH);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);getRootPane().setDefaultButton(run);
        updateCount();pack();setMinimumSize(new Dimension(710,getHeight()));setLocationRelativeTo(owner);
    }
    TuningPlan showPlan(){setVisible(true);return selected;}
    private TuningPlan plan(){
        Map<TuningParameter,List<Number>> axes=new LinkedHashMap<>();
        for(Row row:rows) if(row.check.isSelected()) axes.put(row.parameter,row.mode.getSelectedIndex()==0?
                TuningPlan.range(row.parameter,row.start.getText(),row.end.getText(),row.step.getText()):
                TuningPlan.values(row.parameter,row.values.getText()));
        return new TuningPlan(baseline,axes);
    }
    private void updateCount(){try{count.setText(plan().combinations.size()+" previews + original image");run.setEnabled(true);}catch(IllegalArgumentException e){count.setText(e.getMessage());run.setEnabled(false);}}
    private final class Row {
        final TuningParameter parameter;
        final JPanel panel=new JPanel(new BorderLayout(12,4));
        final JCheckBox check;
        final JComboBox<String> mode=new JComboBox<>(new String[]{"Range","Individual values"});
        final JTextField start=new JTextField(5),end=new JTextField(5),step=new JTextField(5),values=new JTextField(26);
        Row(TuningParameter parameter){
            this.parameter=parameter;
            check=new JCheckBox(parameter.displayLabel(),parameter==TuningParameter.PROBABILITY || parameter==TuningParameter.OVERLAP);
            check.setPreferredSize(new Dimension(215,28));panel.add(check,BorderLayout.WEST);
            JPanel editor=new JPanel(new BorderLayout(8,0));editor.add(mode,BorderLayout.WEST);
            CardLayout cards=new CardLayout();JPanel entries=new JPanel(cards);
            JPanel range=new JPanel(new FlowLayout(FlowLayout.LEFT,5,0));
            range.add(new JLabel("From"));range.add(start);range.add(new JLabel("to"));range.add(end);range.add(new JLabel("step"));range.add(step);
            entries.add(range,"Range");entries.add(values,"Individual values");editor.add(entries,BorderLayout.CENTER);
            panel.add(editor,BorderLayout.CENTER);panel.setBorder(BorderFactory.createEmptyBorder(4,0,4,0));
            double current=parameter.read(baseline);
            String value=parameter.integer?Integer.toString((int)current):Double.toString(current);
            start.setText(value);end.setText(value);step.setText(parameter.integer?"1":"0.1");values.setText(parameter==TuningParameter.MAX_SIZE && current==Integer.MAX_VALUE?"Infinity":value);
            if(parameter==TuningParameter.PROBABILITY || parameter==TuningParameter.OVERLAP){
                start.setText(Double.toString(Math.max(0,Math.round((current-0.1)*100.0)/100.0)));
                end.setText(Double.toString(Math.min(1,Math.round((current+0.1)*100.0)/100.0)));
            }
            editor.setVisible(check.isSelected());
            check.addActionListener(e->{editor.setVisible(check.isSelected());updateCount();});
            mode.addActionListener(e->{cards.show(entries,(String)mode.getSelectedItem());updateCount();});
            DocumentListener listener=new DocumentListener(){public void insertUpdate(DocumentEvent e){updateCount();} public void removeUpdate(DocumentEvent e){updateCount();}public void changedUpdate(DocumentEvent e){updateCount();}};
            for(JTextField field:new JTextField[]{start,end,step,values})field.getDocument().addDocumentListener(listener);
        }
    }
}
