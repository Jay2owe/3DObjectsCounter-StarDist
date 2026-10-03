package sc.fiji.oc3dsd.ui.tuning;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import java.lang.reflect.Field;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.*;
import sc.fiji.oc3dsd.internal.flash.ui.variations.*;

public class TuningGridTest {
    @Test public void originalAndQueuedAndCompletedTilesShareSlices() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            ImagePlus original=volume();
            VariationCellPanel baseline=VariationCellPanel.baseline(original);
            ParameterCombo combo=ParameterCombo.builder().put(TuningParameter.PROBABILITY,.5).build();
            VariationCellPanel preview=new VariationCellPanel(combo,original,null,null);
            SyncedSliceController scroll=new SyncedSliceController();scroll.register(baseline);scroll.register(preview);
            scroll.setSlice(3);assertEquals(3,scroll.currentSlice());assertEquals(3,currentZ(baseline));
            preview.setResult(VariationResult.success(combo,volume(),1,10,null));
            scroll.setSlice(2);assertEquals(2,currentZ(baseline));assertEquals(2,currentZ(preview));
            scroll.setSlice(100);assertEquals(3,scroll.currentSlice());
            CounterGridLifecycle.close(java.util.Arrays.asList(baseline,preview));
            assertEquals("Original image is borrowed by grid cells",3,original.getStackSize());original.flush();
        });
    }
    @Test public void closingTileReleasesResultsAndRejectsLateCompletions() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            TrackingImage original=volume(),labels=volume(),late=volume();
            ParameterCombo combo=ParameterCombo.builder().put(TuningParameter.PROBABILITY,.5).build();
            VariationCellPanel tile=new VariationCellPanel(combo,original,null,null);
            tile.setResult(VariationResult.success(combo,labels,1,10,null));CounterGridLifecycle.close(java.util.Arrays.asList(tile));
            assertEquals(1,labels.flushes);
            tile.setResult(VariationResult.success(combo,late,1,10,null));assertEquals(1,late.flushes);
            assertEquals(0,original.flushes);
            assertEquals(3,original.getStackSize());original.flush();
        });
    }
    private static TrackingImage volume(){ImageStack s=new ImageStack(4,4);for(int i=0;i<3;i++)s.addSlice(new ByteProcessor(4,4));return new TrackingImage(s);}
    private static final class TrackingImage extends ImagePlus{int flushes;TrackingImage(ImageStack stack){super("test",stack);}@Override public void flush(){flushes++;super.flush();}}
    private static int currentZ(VariationCellPanel cell){try{Field f=cell.preview().getClass().getDeclaredField("currentZ");f.setAccessible(true);return f.getInt(cell.preview());}catch(Exception e){throw new AssertionError(e);}}
}
