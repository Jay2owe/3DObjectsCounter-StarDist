package sc.fiji.oc3dsd.engine;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import org.junit.Test;
import sc.fiji.oc3dsd.api.OC3DSD;
import sc.fiji.oc3dsd.api.OC3DSDParameters;
import sc.fiji.oc3dsd.api.OC3DSDResult;

import java.awt.Color;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ObjectMapDisplayTest {

    @Test
    public void lowNumberedObjectRendersAcrossEveryOccupiedZSlice() {
        ImagePlus input = blankInput();
        ImagePlus labels = lowAndHighLabelStack();
        OC3DSDParameters params = OC3DSD.builder(input)
                .minSize(1)
                .buildObjectMap(true)
                .buildSurfaceMap(false)
                .buildCentroidMap(false)
                .buildCentreOfMassMap(false)
                .build();

        OC3DSDResult result = OC3DSDRunner.measureFilterAndMap(labels, null, params);
        ImagePlus objects = result.getObjectMap();

        assertNotNull(objects);
        assertEquals(2, result.getObjectCount());
        for (int slice = 1; slice <= 3; slice++) {
            objects.setSlice(slice);
            int renderedRgb = objects.getBufferedImage().getRGB(2, 2) & 0x00ffffff;
            assertTrue("label 1 must be visible on occupied slice " + slice,
                    renderedRgb != 0);
            assertEquals("the raw numeric object ID must remain available",
                    1.0, objects.getProcessor().getf(2, 2), 0.0);
        }
        assertEquals(1000.0, objects.getStack().getProcessor(1).getf(0, 0), 0.0);
        assertNotNull(objects.getOverlay());
        assertEquals(Color.RED, objects.getOverlay().get(0).getStrokeColor());
    }

    private static ImagePlus blankInput() {
        ImageStack stack = new ImageStack(5, 5);
        for (int slice = 0; slice < 3; slice++) {
            stack.addSlice(new ByteProcessor(5, 5));
        }
        return new ImagePlus("source", stack);
    }

    private static ImagePlus lowAndHighLabelStack() {
        ImageStack stack = new ImageStack(5, 5);
        for (int slice = 0; slice < 3; slice++) {
            FloatProcessor processor = new FloatProcessor(5, 5);
            processor.setf(2, 2, 1);
            if (slice == 0) processor.setf(0, 0, 1000);
            stack.addSlice(processor);
        }
        return new ImagePlus("labels", stack);
    }
}
