package sc.fiji.oc3dsd.ui.tuning;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.process.ImageProcessor;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import sc.fiji.oc3dsd.api.OC3DSD;
import sc.fiji.oc3dsd.api.OC3DSDResult;
import sc.fiji.oc3dsd.ui.OC3DSDDialogModel;
import sc.fiji.oc3dsd.runtime.DependencyDoctor;
import sc.fiji.oc3dsd.internal.flash.ui.preview.PreviewDisplaySettings;
import sc.fiji.oc3dsd.internal.flash.ui.variations.*;

/** Adapts the unchanged FLASH comparison grid to the counter's own engine. */
public final class StarDistTuner {
    private StarDistTuner() {}
    public static OC3DSDDialogModel show(ImagePlus input,OC3DSDDialogModel baseline) {
        if(!baseline.is3d() && !DependencyDoctor.verify(true)) return null;
        AtomicReference<OC3DSDDialogModel> chosen=new AtomicReference<>();
        onEdt(()->{
            TuningPlan plan=new TuningPicker(IJ.getInstance(),baseline).showPlan();
            if(plan==null)return;
            try {
                checkMemory(input,plan.combinations.size());
                ImagePlus preview=previewVolume(input,baseline.channel);
                new Session(preview,baseline,plan,chosen).show();
            } catch(RuntimeException error){IJ.error("StarDist parameter tuning",error.getMessage());}
        });
        return chosen.get();
    }
    /** Independent pixels: never moves the source's channel, frame, slice or selection. */
    public static ImagePlus previewVolume(ImagePlus input,int channel) {
        if(input==null || channel<1 || channel>input.getNChannels())throw new IllegalArgumentException("Choose a valid input channel.");
        Rectangle bounds=new Rectangle(0,0,input.getWidth(),input.getHeight());
        Roi roi=input.getRoi();
        if(roi!=null && roi.getType()==Roi.RECTANGLE)bounds=bounds.intersection(roi.getBounds());
        if(bounds.isEmpty())throw new IllegalArgumentException("The selection is outside the image.");
        ImageStack stack=new ImageStack(bounds.width,bounds.height);
        for(int z=1;z<=input.getNSlices();z++){
            ImageProcessor copy=input.getStack().getProcessor(input.getStackIndex(channel,z,input.getT())).duplicate();
            copy.setRoi(bounds);stack.addSlice(copy.crop());
        }
        ImagePlus output=new ImagePlus("Original — tuning preview",stack);
        output.setCalibration(input.getCalibration().copy());
        output.getCalibration().xOrigin-=bounds.x;output.getCalibration().yOrigin-=bounds.y;
        output.setDisplayRange(input.getDisplayRangeMin(),input.getDisplayRangeMax());
        return output;
    }
    static void checkMemory(ImagePlus image,int count){
        // Labels, cached overlays and display copies for every tile, plus one running detector.
        Rectangle area=new Rectangle(0,0,image.getWidth(),image.getHeight());
        if(image.getRoi()!=null && image.getRoi().getType()==Roi.RECTANGLE)area=area.intersection(image.getRoi().getBounds());
        long bytes=(long)area.width*area.height*image.getNSlices()*(32L+20L*count);
        Runtime rt=Runtime.getRuntime();long available=rt.maxMemory()-(rt.totalMemory()-rt.freeMemory());
        if(bytes>available*0.6)throw new IllegalArgumentException("The grid would use too much image memory. Draw a smaller rectangular selection or use fewer values.");
    }
    private static void onEdt(Runnable task){
        if(SwingUtilities.isEventDispatchThread()){task.run();return;}
        try{SwingUtilities.invokeAndWait(task);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Tuning interrupted.",e);}
        catch(java.lang.reflect.InvocationTargetException e){throw new IllegalStateException("Could not open parameter tuning.",e.getCause());}
    }
    private static final class Session {
        final ImagePlus source;
        final OC3DSDDialogModel baseline;
        final TuningPlan plan;
        final AtomicReference<OC3DSDDialogModel> chosen;
        final List<VariationCellPanel> cells=new ArrayList<>();
        final Set<ParameterCombo> ready=new HashSet<>();
        final Map<ParameterCombo,VariationResult> outcomes=new HashMap<>();
        final AtomicBoolean closed=new AtomicBoolean();
        final VariationGridWindow grid;
        ParameterCombo selected;
        PreviewDisplaySettings display;
        boolean grey=false,finished;
        Thread worker;
        Session(ImagePlus source,OC3DSDDialogModel baseline,TuningPlan plan,AtomicReference<OC3DSDDialogModel> chosen){
            this.source=source;this.baseline=baseline.snapshot();this.plan=plan;this.chosen=chosen;
            cells.add(VariationCellPanel.baseline(source));
            for(int i=0;i<plan.combinations.size();i++){
                VariationCellPanel cell=new VariationCellPanel(plan.combinations.get(i),source,this::select,null,i);
                cell.setFooterParameterKeys(plan.keys);cell.setState("queued");cells.add(cell);
            }
            grid=new VariationGridWindow(IJ.getInstance(),"StarDist — tune parameters",cells,true);
            configureCounterToolbar(grid.getContentPane());
            grid.setModalityType(Dialog.ModalityType.APPLICATION_MODAL);
            grid.setObjectOverlaySourceEnabled(false); // Every preview uses this same unfiltered input.
            grid.setPickSelectedEnabled(false);grid.setSliceMax(source.getNSlices());
            grid.setCompletedCount(0,plan.combinations.size(),0);
            display=PreviewDisplaySettings.of(source.getDisplayRangeMin(),source.getDisplayRangeMax(),PreviewDisplaySettings.LutMode.CHANNEL,channelLut(source));
            grid.attachObjectOverlayActionListener(e->refreshDisplay());
            grid.attachLutToggleActionListener(e->{grey=!grey;display=PreviewDisplaySettings.of(display.getDisplayMin(),display.getDisplayMax(),grey?PreviewDisplaySettings.LutMode.GREY:PreviewDisplaySettings.LutMode.CHANNEL,display.getChannelLutName());refreshDisplay();});
            grid.attachBrightnessActionListener(e->brightness());
            grid.attachPickSelectedActionListener(e->commit(selected));
            grid.addWindowListener(new WindowAdapter(){
                @Override public void windowClosing(WindowEvent e){closed.set(true);if(worker!=null)worker.interrupt();}
                @Override public void windowClosed(WindowEvent e){closed.set(true);if(worker!=null)worker.interrupt();CounterGridLifecycle.close(cells);releaseSourceIfFinished();}
            });
            refreshDisplay();
        }
        void show(){
            worker=new Thread(this::compute,"stardist-parameter-tuning");worker.setDaemon(true);
            // ImageJ/SciJava resolve detector services through the plugin caller's loader.
            worker.setContextClassLoader(StarDistTuner.class.getClassLoader());worker.start();
            grid.setVisible(true);
        }
        void select(ParameterCombo combo){
            if(!ready.contains(combo))return;
            selected=combo;
            for(VariationCellPanel cell:cells){
                boolean match=cell.combo().equals(combo);
                cell.setBorderHint(match?VariationCellPanel.BorderHint.KNEE:VariationCellPanel.BorderHint.NONE);
                if(match)cell.setRibbonLabel("Selected");
            }
            grid.setPickSelectedEnabled(true);grid.setActionStatus("Selected: "+describe(combo));
        }
        void commit(ParameterCombo combo){
            if(closed.get() || combo==null || !ready.contains(combo))return;
            chosen.set(TuningPlan.apply(baseline,combo));closed.set(true);grid.dispose();
        }
        void compute(){
            int completed=0,failed=0;
            try{
                for(int i=0;i<plan.combinations.size() && !closed.get();i++){
                    final ParameterCombo combo=plan.combinations.get(i);final VariationCellPanel cell=cells.get(i+1);
                    SwingUtilities.invokeLater(()->{if(!closed.get())cell.setState("running");});
                    VariationResult result;
                    try{
                        OC3DSDDialogModel model=TuningPlan.apply(baseline,combo);
                        model.channel=1;model.redirectTitle="";
                        model.showLabels=model.showSurfaces=model.showCentroids=model.showCentersOfMass=false;
                        long started=System.nanoTime();
                        OC3DSDResult actual=OC3DSD.run(model.toParameters(source,null,message->IJ.log("StarDist tuning: "+message)));
                        result=VariationResult.success(combo,actual.getLabelImage(),actual.getObjectCount(),(System.nanoTime()-started)/1_000_000,actual.getObjects());
                    }catch(Exception | LinkageError error){result=VariationResult.failure(combo,error);failed++;}
                    completed++;
                    final VariationResult outcome=result;final int done=completed,errors=failed;
                    SwingUtilities.invokeLater(()->{
                        // Cells reject and dispose late results after the window has closed.
                        cell.setResult(outcome);
                        if(closed.get())return;
                        outcomes.put(combo,outcome);
                        if(outcome.error()==null){ready.add(combo);cell.setOnPickCommit(this::commit);}
                        // A newly completed tile joins the slice the user is viewing now.
                        grid.setSliceMax(source.getNSlices());
                        refreshDisplay();grid.setCompletedCount(done,plan.combinations.size(),errors);
                        if(done==plan.combinations.size())grid.setActionStatus(errors==done?"No preview succeeded. Open a tile for its error.":"Select a tile, then Pick selected to return its settings to the counter.");
                    });
                }
            }finally{
                SwingUtilities.invokeLater(()->{finished=true;releaseSourceIfFinished();});
            }
        }
        void releaseSourceIfFinished(){if(finished && closed.get())source.flush();}
        void refreshDisplay(){
            grid.setLutToggleText(grey?"Channel LUT":"Grey LUT","Switch the display lookup table");
            for(VariationCellPanel cell:cells){cell.setObjectRawCrop(source);cell.setObjectOverlaySourceRaw(true);cell.setObjectOverlayEnabled(grid.isObjectOverlaySelected());cell.setObjectDisplaySettings(display);}
            for(int i=1;i<cells.size();i++){
                VariationCellPanel cell=cells.get(i);VariationResult result=outcomes.get(cell.combo());
                String tip="<html>"+describe(cell.combo()).replace("; ","<br>");
                if(result!=null)tip+=result.hasError()?"<br>Failed: "+html(result.error().getMessage()):"<br>Objects: "+result.nObjects()+"<br>Click to select; Pick returns these settings to the counter.";
                tip+="</html>";applyTooltip(cell,tip);
            }
        }
        void brightness(){
            JTextField low=new JTextField(Double.toString(display.getDisplayMin()),12),high=new JTextField(Double.toString(display.getDisplayMax()),12);
            JPanel panel=new JPanel(new GridLayout(2,2,8,8));panel.add(new JLabel("Minimum"));panel.add(low);panel.add(new JLabel("Maximum"));panel.add(high);
            if(JOptionPane.showConfirmDialog(grid,panel,"Grid brightness / contrast",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
            double min,max;
            try{min=Double.parseDouble(low.getText());max=Double.parseDouble(high.getText());}
            catch(NumberFormatException e){JOptionPane.showMessageDialog(grid,"Enter numeric display limits.");return;}
            if(!Double.isFinite(min)||!Double.isFinite(max)||max<=min){IJ.error("Maximum must be greater than minimum.");return;}
            display=PreviewDisplaySettings.of(min,max,display.getLutMode(),display.getChannelLutName());refreshDisplay();
        }
        private static String channelLut(ImagePlus source){
            java.awt.image.ColorModel lut=source.getProcessor().getColorModel();
            int rgb=lut.getRGB(255),r=(rgb>>16)&255,g=(rgb>>8)&255,b=rgb&255;
            if(r==g && g==b)return "Grays";
            if(r>128 && g<128 && b<128)return "Red";
            if(g>128 && r<128 && b<128)return "Green";
            if(b>128 && r<128 && g<128)return "Blue";
            if(g>128 && b>128 && r<128)return "Cyan";
            if(r>128 && b>128 && g<128)return "Magenta";
            if(r>128 && g>128 && b<128)return "Yellow";
            return "Grays";
        }
        private static void configureCounterToolbar(Container parent){
            for(Component component:parent.getComponents()){
                if(component instanceof JToolBar){
                    for(Component control:((JToolBar)component).getComponents()){
                        if(control instanceof JComboBox)((JComboBox<?>)control).setSelectedIndex(1);
                        if(control instanceof JButton && "Save variations cache".equals(((JButton)control).getText()))control.setVisible(false);
                    }
                }else if(component instanceof Container)configureCounterToolbar((Container)component);
            }
        }
        private static String describe(ParameterCombo combo){
            StringBuilder out=new StringBuilder();
            for(Map.Entry<ParameterKey,Object> value:combo.values().entrySet()){
                if(out.length()>0)out.append("; ");
                out.append(value.getKey().displayLabel()).append(": ");
                out.append(value.getKey()==TuningParameter.MAX_SIZE && ((Number)value.getValue()).intValue()==Integer.MAX_VALUE?"Infinity":value.getValue());
            }
            return out.toString();
        }
        private static String html(String text){return text==null?"No error details":text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}
        private static void applyTooltip(Container parent,String text){
            if(parent instanceof JComponent)((JComponent)parent).setToolTipText(text);
            for(Component child:parent.getComponents())if(child instanceof Container)applyTooltip((Container)child,text);
        }
    }
}
