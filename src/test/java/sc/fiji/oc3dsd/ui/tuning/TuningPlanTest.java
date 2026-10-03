package sc.fiji.oc3dsd.ui.tuning;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import sc.fiji.oc3dsd.ui.OC3DSDDialogModel;

public class TuningPlanTest {
    @Test public void decimalRangesIncludeEndpointsWithoutStepDrift(){
        assertEquals(Arrays.<Number>asList(0.3,0.4,0.5),TuningPlan.range(TuningParameter.PROBABILITY,".3",".5",".1"));
    }
    @Test public void explicitValuesPreserveOrderAndRemoveDuplicates(){
        assertEquals(Arrays.<Number>asList(0.5,0.3,0.7),TuningPlan.values(TuningParameter.PROBABILITY,".5, .3; .5 .7"));
        assertEquals(Arrays.<Number>asList(Integer.MAX_VALUE,500),TuningPlan.values(TuningParameter.MAX_SIZE,"Infinity, 500"));
    }
    @Test public void everySelectedAxisCombinesAndFixedSettingsSurvive(){
        OC3DSDDialogModel base=new OC3DSDDialogModel();
        base.channel=2;base.modelRef="dsb2018";base.redirectTitle="Other channel";base.minSize=17;
        base.excludeOnEdges=true;base.showSurfaces=true;base.saveLabels=true;base.pythonConfig="config.json";
        Map<TuningParameter,List<Number>> axes=new LinkedHashMap<>();
        axes.put(TuningParameter.PROBABILITY,TuningPlan.values(TuningParameter.PROBABILITY,".2,.7"));
        axes.put(TuningParameter.SLICE_GAP,TuningPlan.range(TuningParameter.SLICE_GAP,"0","2","1"));
        TuningPlan plan=new TuningPlan(base,axes);assertEquals(6,plan.combinations.size());
        OC3DSDDialogModel picked=TuningPlan.apply(base,plan.combinations.get(5));
        assertEquals(.7,picked.probability,0);assertEquals(2,picked.sliceGap);
        assertEquals(2,picked.channel);assertEquals("dsb2018",picked.modelRef);assertEquals("Other channel",picked.redirectTitle);
        assertEquals(17,picked.minSize);assertTrue(picked.excludeOnEdges);assertTrue(picked.showSurfaces);assertTrue(picked.saveLabels);
        assertEquals("config.json",picked.pythonConfig);assertEquals(.5,base.probability,0);assertEquals(1,base.sliceGap);
    }
    @Test public void invalidRangesAndDomainsCannotRun(){
        rejects(()->TuningPlan.range(TuningParameter.PROBABILITY,"0","1","0"));
        rejects(()->TuningPlan.range(TuningParameter.PROBABILITY,".8",".2",".1"));
        rejects(()->TuningPlan.values(TuningParameter.PROBABILITY,"NaN"));
        rejects(()->TuningPlan.values(TuningParameter.OVERLAP,"1.01"));
        rejects(()->TuningPlan.values(TuningParameter.SLICE_GAP,"1.5"));
        rejects(()->TuningPlan.values(TuningParameter.MIN_SLICES,"0"));
        rejects(()->TuningPlan.range(TuningParameter.MIN_SIZE,"0","100","1"));
    }
    @Test public void combinationLimitAndConflictingSizeBoundsAreRejected(){
        Map<TuningParameter,List<Number>> axes=new LinkedHashMap<>();
        axes.put(TuningParameter.PROBABILITY,TuningPlan.range(TuningParameter.PROBABILITY,"0","1",".1"));
        axes.put(TuningParameter.OVERLAP,TuningPlan.range(TuningParameter.OVERLAP,"0","1",".1"));
        rejects(()->new TuningPlan(new OC3DSDDialogModel(),axes));
        axes.clear();axes.put(TuningParameter.MIN_SIZE,Arrays.<Number>asList(10,20));axes.put(TuningParameter.MAX_SIZE,Arrays.<Number>asList(15));
        rejects(()->new TuningPlan(new OC3DSDDialogModel(),axes));
        rejects(()->new TuningPlan(new OC3DSDDialogModel(),Collections.emptyMap()));
    }
    @Test public void wholeVolumeModeDoesNotOfferUnusedLinkingParameters(){
        OC3DSDDialogModel base=new OC3DSDDialogModel();base.segmentation="stardist3d";
        assertFalse(TuningParameter.LINKING.appliesTo(base));assertFalse(TuningParameter.MIN_SLICES.appliesTo(base));
        assertTrue(TuningParameter.PROBABILITY.appliesTo(base));assertTrue(TuningParameter.MIN_SIZE.appliesTo(base));
        rejects(()->new TuningPlan(base,Collections.singletonMap(TuningParameter.LINKING,Arrays.<Number>asList(3))));
    }
    private static void rejects(Runnable run){try{run.run();fail("Expected invalid sweep rejection");}catch(IllegalArgumentException expected){assertFalse(expected.getMessage().isEmpty());}}
}
