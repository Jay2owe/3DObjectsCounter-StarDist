package sc.fiji.oc3dsd.ui;
import ij.gui.GenericDialog;
import java.awt.*;

/** Explicit advanced selections apply only to interactive runs in this Fiji session. */
public final class StarDistOptionsSession {
    private static OC3DSDDialogModel selected=new OC3DSDDialogModel();
    private StarDistOptionsSession(){}
    public static synchronized void apply(OC3DSDDialogModel model){
        model.segmentation=selected.segmentation;model.model3d=selected.model3d;model.pythonConfig=selected.pythonConfig;
        if(model.is3d()){model.probability=selected.probability;model.overlap=selected.overlap;}
    }
    public static synchronized void save(OC3DSDDialogModel model){selected=model.snapshot();}
    public static void disableUnusedLinking(GenericDialog gd,OC3DSDDialogModel model){
        if(!model.is3d())return;
        Component[] components=gd.getComponents();
        for(int i=0;i<components.length-1;i++)if(components[i] instanceof Label){String text=((Label)components[i]).getText();
            if(text.startsWith("Linking max")||text.startsWith("Gap closing")||text.startsWith("Max slice")||text.startsWith("Min. slices")){
                components[i].setEnabled(false);components[i+1].setEnabled(false);
            }
        }
    }
}
