package sc.fiji.oc3dsd.ui.tuning;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;
import static org.junit.Assert.*;

public class TuningPreviewTest {
    @Test public void selectedChannelTimepointAndRectangleAreCopiedWithoutChangingInput(){
        ImageStack stack=new ImageStack(8,6);
        for(int i=1;i<=12;i++){ByteProcessor p=new ByteProcessor(8,6);p.setValue(i);p.fill();stack.addSlice(p);}
        ImagePlus input=new ImagePlus("source",stack);input.setDimensions(2,3,2);input.setOpenAsHyperStack(true);
        input.setPosition(1,2,2);input.setRoi(2,1,3,4);input.getCalibration().pixelWidth=.25;input.getCalibration().xOrigin=10;
        ImagePlus preview=StarDistTuner.previewVolume(input,2);
        assertEquals(3,preview.getWidth());assertEquals(4,preview.getHeight());assertEquals(3,preview.getNSlices());
        assertEquals(8,preview.getStack().getProcessor(1).get(0,0));assertEquals(12,preview.getStack().getProcessor(3).get(0,0));
        assertEquals(.25,preview.getCalibration().pixelWidth,0);assertEquals(8,preview.getCalibration().xOrigin,0);
        assertEquals(1,input.getC());assertEquals(2,input.getZ());assertEquals(2,input.getT());assertEquals(2,input.getRoi().getBounds().x);
        preview.getStack().getProcessor(1).set(0,0,99);assertEquals(8,input.getStack().getProcessor(8).get(2,1));
        preview.flush();assertEquals(12,input.getStackSize());
    }
}
