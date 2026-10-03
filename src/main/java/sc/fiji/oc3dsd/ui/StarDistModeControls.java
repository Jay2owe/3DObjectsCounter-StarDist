package sc.fiji.oc3dsd.ui;
import ij.gui.GenericDialog;
import java.awt.*;
import javax.swing.JOptionPane;
import sc.fiji.oc3dsd.runtime.StarDist3D;

/** Advanced mode controls, used only by the separate Options command. */
public final class StarDistModeControls {
    private final Choice mode;
    private final TextField folder, config;
    private final Button training;
    private final GenericDialog dialog;
    private boolean restoring;
    public StarDistModeControls(GenericDialog gd, OC3DSDDialogModel model) {
        dialog=gd;
        gd.addChoice("Segmentation",new String[]{"StarDist2D + Z linking (current)","StarDist3D - whole volume"},
            model.is3d()?"StarDist3D - whole volume":"StarDist2D + Z linking (current)");
        mode=(Choice)gd.getChoices().lastElement();
        gd.addStringField("3D model folder",model.model3d,30); folder=(TextField)gd.getStringFields().lastElement();
        gd.addStringField("3D configuration",model.pythonConfig,30); config=(TextField)gd.getStringFields().lastElement();
        Panel panel=new Panel(new FlowLayout(FlowLayout.LEFT,0,0));training=new Button("Train / configure / load 3D model...");panel.add(training);gd.addPanel(panel);
        training.addActionListener(event->new StarDistTrainingDialog(gd,(path,configuration,probability,overlap)->{
            folder.setText(path);config.setText(configuration);
            model.probability=probability;model.overlap=overlap;
            // Threshold fields are located by label by the owning dialog below.
            setThreshold(gd,"Probability",probability);setThreshold(gd,"Overlap",overlap);
        }).show());
        mode.addItemListener(event->{
            if(restoring)return;
            if(mode.getSelectedIndex()==1 && JOptionPane.showConfirmDialog(gd,StarDist3D.WARNING,
                    "Switch to StarDist3D?",JOptionPane.OK_CANCEL_OPTION,JOptionPane.WARNING_MESSAGE)!=JOptionPane.OK_OPTION){
                restoring=true;mode.select(0);restoring=false;
            }
            update();
        });
        EventQueue.invokeLater(this::update);
    }
    private static void setThreshold(Container container,String prefix,double value){
        Component[] components=container.getComponents();
        for(int i=0;i<components.length-1;i++)if(components[i] instanceof Label && ((Label)components[i]).getText().startsWith(prefix)
                && components[i+1] instanceof TextField)((TextField)components[i+1]).setText(Double.toString(value));
    }
    private void update(){
        boolean native3d=mode.getSelectedIndex()==1;folder.setEnabled(native3d);config.setEnabled(native3d);training.setEnabled(native3d);
        Component[] components=dialog.getComponents();
        for(int i=0;i<components.length-1;i++)if(components[i] instanceof Label){
            String label=((Label)components[i]).getText();
            if(label.equals("Model")||label.equals("Model:")||label.startsWith("Linking_max")||label.startsWith("Linking max")||label.startsWith("Gap closing")
                    ||label.startsWith("Gap_closing")||label.startsWith("Max slice")||label.startsWith("Max_slice")||label.startsWith("Min. slices")||label.startsWith("Min._slices")){
                components[i].setEnabled(!native3d);components[i+1].setEnabled(!native3d);
            }
        }
    }
    public void read(GenericDialog gd, OC3DSDDialogModel model){
        model.segmentation=gd.getNextChoice().startsWith("StarDist3D")?"stardist3d":"stardist2d";
        model.model3d=gd.getNextString().trim();model.pythonConfig=gd.getNextString().trim();
    }
}
