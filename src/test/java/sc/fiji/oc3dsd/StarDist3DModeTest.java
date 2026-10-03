package sc.fiji.oc3dsd;
import org.junit.Test;
import static org.junit.Assert.*;
import sc.fiji.oc3dsd.ui.OC3DSDDialogModel;
import sc.fiji.oc3dsd.api.OC3DSD;
import ij.ImagePlus;
import ij.process.ByteProcessor;
import java.io.File;

public class StarDist3DModeTest {
    @Test public void defaultsAndOldMacrosStayInCurrentMode(){
        OC3DSDDialogModel defaults=new OC3DSDDialogModel();assertFalse(defaults.is3d());
        assertFalse(defaults.toMacroOptions().contains("segmentation="));
        assertFalse(MacroOptionsParser.parse("probability=0.6 linking_distance=7").is3d());
        assertEquals("stardist2d",OC3DSD.builder(new ImagePlus("x",new ByteProcessor(2,2))).build().segmentation);
    }
    @Test public void newModeRecordsPathsAndSurvivesSnapshots(){
        OC3DSDDialogModel model=new OC3DSDDialogModel();model.segmentation="stardist3d";model.model3d="C:/models/My nuclei";model.pythonConfig="C:/settings/My run.json";
        OC3DSDDialogModel parsed=MacroOptionsParser.parse(model.toMacroOptions());assertTrue(parsed.is3d());
        assertEquals(model.model3d,parsed.model3d);assertEquals(model.pythonConfig,parsed.snapshot().pythonConfig);
        assertTrue(parsed.enabledPredicates().isEmpty());
    }
    @Test public void newModeRequiresItsOwnModel(){
        OC3DSDDialogModel model=new OC3DSDDialogModel();model.segmentation="stardist3d";
        assertFalse(model.validate().isEmpty());
        try{OC3DSD.builder(new ImagePlus("x",new ByteProcessor(2,2))).segmentation("stardist3d").build();fail("Missing model accepted");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("3D"));}
    }
    @Test public void unknownModeIsRejected(){
        try{OC3DSD.builder(new ImagePlus("x",new ByteProcessor(2,2))).segmentation("invented").model3d(new File("x")).build();fail();}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("Segmentation"));}
    }
}
