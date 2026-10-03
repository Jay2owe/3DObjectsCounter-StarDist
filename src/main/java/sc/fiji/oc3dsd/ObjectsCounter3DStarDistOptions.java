package sc.fiji.oc3dsd;
import ij.IJ;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import sc.fiji.oc3dsd.ui.*;

/** Advanced settings kept outside the established counting dialogs. */
public final class ObjectsCounter3DStarDistOptions implements PlugIn {
    @Override public void run(String argument){
        OC3DSDDialogModel model=new OC3DSDDialogModel();StarDistOptionsSession.apply(model);
        GenericDialog gd=new GenericDialog("3D Objects Counter - StarDist Options");
        gd.addMessage("Optional whole-volume segmentation and model training.\nThe standard counting dialog keeps its familiar layout.");
        StarDistModeControls controls=new StarDistModeControls(gd,model);
        gd.addMessage("Selections apply to interactive counts and batches in this Fiji session.\nRecorded macros name the mode and model explicitly.\nRestarting Fiji restores the current StarDist2D mode.");
        gd.setOKLabel("Apply");gd.showDialog();if(gd.wasCanceled())return;
        controls.read(gd,model);
        if(model.is3d()){
            String problem=sc.fiji.oc3dsd.runtime.StarDist3D.validateModel(new java.io.File(model.model3d));
            if(problem!=null){IJ.error("StarDist Options",problem);return;}
        }
        StarDistOptionsSession.save(model);IJ.showStatus(model.is3d()?"StarDist3D selected for this Fiji session":"Current StarDist2D mode restored");
    }
}
