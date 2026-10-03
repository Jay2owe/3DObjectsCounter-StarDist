package sc.fiji.oc3dsd.ui;
import com.google.gson.*;
import ij.IJ;
import ij.ImagePlus;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.*;
import sc.fiji.oc3dsd.runtime.StarDist3D;

/** Registry-driven, cancellable training, model import and held-out review. */
public final class StarDistTrainingDialog {
    public interface Selection { void select(String model,String configuration,double probability,double overlap); }
    private final JDialog dialog;
    private final Selection selection;
    private final Map<String,JTextField> fields=new LinkedHashMap<String,JTextField>();
    private final JTextField configuration=new JTextField(30), output=new JTextField(30), model=new JTextField(30);
    private final JTextArea log=new JTextArea(7,60);
    private final JPanel buttons=new JPanel(new FlowLayout(FlowLayout.LEFT));
    private Thread running;
    private JsonObject last;
    public StarDistTrainingDialog(Window owner,Selection selection){
        this.selection=selection;dialog=new JDialog(owner,"StarDist3D - train and manage models",Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new java.awt.event.WindowAdapter(){public void windowClosing(java.awt.event.WindowEvent e){if(running==null)dialog.dispose();else cancel();}});
        JPanel rows=new JPanel(new GridBagLayout());int row=0;
        row=pathRow(rows,row,"Configuration JSON",configuration,false);
        row=pathRow(rows,row,"Training / evaluation output",output,true);
        row=pathRow(rows,row,"3D model folder (load / fine-tune)",model,true);
        for(JsonElement element:StarDist3D.registry().getAsJsonArray("options")){
            JsonObject option=element.getAsJsonObject();String key=option.get("key").getAsString();
            JTextField field=new JTextField(option.get("default").isJsonPrimitive()&&option.get("default").getAsJsonPrimitive().isString()
                    ?option.get("default").getAsString():option.get("default").toString(),28);
            field.setToolTipText(option.get("help").getAsString());fields.put(key,field);
            row=pathRow(rows,row,option.get("label").getAsString(),field,key.equals("data.manifest")?false:null);
        }
        JPanel north=new JPanel(new BorderLayout());north.add(new JLabel("Paired 3D TIFFs: image, labels, specimen_id, split (train / validation / test)."),BorderLayout.NORTH);
        JScrollPane scroll=new JScrollPane(rows);scroll.setPreferredSize(new Dimension(740,470));north.add(scroll,BorderLayout.CENTER);
        dialog.add(north,BorderLayout.CENTER);
        add("Load settings",()->load());add("Save settings",()->save());add("Create example",()->execute("example"));
        add("Validate data",()->execute("validate"));add("Train",()->execute("train"));add("Resume",()->execute("resume"));
        add("Fine-tune",()->execute("fine_tune"));add("Evaluate test",()->execute("evaluate"));add("Review TIFFs",()->review());
        add("Use model",()->use());add("Download demo",()->execute("demo_model"));
        JButton cancel=new JButton("Cancel running action");cancel.addActionListener(e->cancel());buttons.add(cancel);
        JPanel south=new JPanel(new BorderLayout());buttons.setPreferredSize(new Dimension(740,108));south.add(buttons,BorderLayout.NORTH);
        log.setEditable(false);log.setLineWrap(true);south.add(new JScrollPane(log),BorderLayout.CENTER);dialog.add(south,BorderLayout.SOUTH);
        dialog.pack();dialog.setLocationRelativeTo(owner);
    }
    private int pathRow(JPanel rows,int row,String label,JTextField field,Boolean directory){
        GridBagConstraints c=new GridBagConstraints();c.gridy=row;c.insets=new Insets(3,5,3,5);c.anchor=GridBagConstraints.WEST;
        c.gridx=0;rows.add(new JLabel(label),c);c.gridx=1;c.weightx=1;c.fill=GridBagConstraints.HORIZONTAL;rows.add(field,c);
        if(directory!=null){c.gridx=2;c.weightx=0;JButton browse=new JButton("Browse");browse.addActionListener(e->{JFileChooser chooser=new JFileChooser();if(directory)chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if(chooser.showOpenDialog(dialog)==JFileChooser.APPROVE_OPTION)field.setText(chooser.getSelectedFile().getAbsolutePath());});rows.add(browse,c);}
        return row+1;
    }
    private void add(String label,Runnable action){JButton button=new JButton(label);button.addActionListener(e->{try{action.run();}catch(Exception error){message(error.getMessage());}});buttons.add(button);}
    private void message(String value){log.append(value+"\n");log.setCaretPosition(log.getDocument().getLength());}
    public void show(){dialog.setVisible(true);}
    private JsonObject settings(){JsonObject config=StarDist3D.defaults();
        for(JsonElement element:StarDist3D.registry().getAsJsonArray("options")){
            JsonObject option=element.getAsJsonObject();String key=option.get("key").getAsString();String[] parts=key.split("\\.");String value=fields.get(key).getText().trim();
            config.getAsJsonObject(parts[0]).add(parts[1],option.get("kind").getAsString().equals("text")?new JsonPrimitive(value):JsonParser.parseString(value));
        }return config;
    }
    private void load(){try{JsonObject config=JsonParser.parseString(new String(Files.readAllBytes(Paths.get(configuration.getText().trim())),StandardCharsets.UTF_8)).getAsJsonObject();
        Path parent=Paths.get(configuration.getText().trim()).toAbsolutePath().getParent();
        for(Map.Entry<String,JTextField> entry:fields.entrySet()){String[] key=entry.getKey().split("\\.");if(!config.has(key[0])||!config.getAsJsonObject(key[0]).has(key[1]))continue;
            JsonElement value=config.getAsJsonObject(key[0]).get(key[1]);String text=value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString()?value.getAsString():value.toString();
            if(entry.getKey().equals("data.manifest")&&!text.isEmpty())text=parent.resolve(text).normalize().toString();entry.getValue().setText(text);
        }message("Loaded settings.");}catch(Exception error){throw new IllegalArgumentException(error.getMessage(),error);}}
    private Path save(){try{if(configuration.getText().trim().isEmpty()){JFileChooser chooser=new JFileChooser();if(chooser.showSaveDialog(dialog)!=JFileChooser.APPROVE_OPTION)throw new IllegalArgumentException("Choose a configuration file.");configuration.setText(chooser.getSelectedFile().getAbsolutePath());}
        Path path=Paths.get(configuration.getText().trim()).toAbsolutePath();Files.write(path,StarDist3D.JSON.toJson(settings()).getBytes(StandardCharsets.UTF_8));return path;
    }catch(IOException error){throw new IllegalArgumentException(error.getMessage(),error);}}
    private void execute(String action){
        if(running!=null)throw new IllegalArgumentException("An action is already running.");
        if(action.equals("demo_model")&&JOptionPane.showConfirmDialog(dialog,"This downloads upstream demonstration weights, not a model validated for your data.","Download demonstration model?",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        JsonObject request=new JsonObject();String actual=action;
        if(!action.equals("example")&&!action.equals("demo_model"))request.addProperty("config",save().toString());
        if(!action.equals("validate")){String folder=output.getText().trim();if(folder.isEmpty())throw new IllegalArgumentException("Choose an output folder.");request.addProperty("output",new File(folder).getAbsolutePath());}
        if(action.equals("resume")||action.equals("fine_tune")){actual="train";request.addProperty("resume",action.equals("resume"));if(action.equals("fine_tune"))request.addProperty("fine_tune",new File(model.getText().trim()).getAbsolutePath());}
        if(action.equals("evaluate")){request.addProperty("model",new File(model.getText().trim()).getAbsolutePath());request.addProperty("split","test");}
        final String runAction=actual;message("Starting "+action+". Python is installed automatically on first use.");
        setBusy(true);running=new Thread(()->{try{JsonObject result=StarDist3D.call(runAction,request,text->SwingUtilities.invokeLater(()->message(text)));
            SwingUtilities.invokeLater(()->{last=result;message(result.has("answer")?result.get("answer").getAsString():result.toString());if(result.has("model"))model.setText(result.get("model").getAsString());
                if(runAction.equals("example")&&result.has("config")){configuration.setText(result.get("config").getAsString());load();}
            });
        }catch(Exception error){IJ.log("StarDist3D: "+error);SwingUtilities.invokeLater(()->message("Action failed: "+error.getMessage()+". See ImageJ Log; checkpoints are retained."));}
        finally{SwingUtilities.invokeLater(()->{running=null;setBusy(false);});}},"StarDist3D training");running.start();
    }
    private void setBusy(boolean busy){for(Component component:buttons.getComponents())if(component instanceof JButton&&!((JButton)component).getText().startsWith("Cancel"))component.setEnabled(!busy);}
    private void cancel(){if(running!=null){message("Cancellation requested; waiting for the current pass and checkpoint.");running.interrupt();}}
    private void review(){
        String root=last!=null&&last.has("output")?last.get("output").getAsString():output.getText().trim();File folder=new File(root);
        File[] files=folder.listFiles((dir,name)->name.endsWith(".tif"));if(files==null||files.length==0)throw new IllegalArgumentException("Train or evaluate first to create review TIFFs.");
        for(File file:files){ImagePlus image=IJ.openImage(file.getAbsolutePath());if(image!=null)image.show();}message("Opened raw, annotated and predicted stacks in this Fiji.");
    }
    private void use(){String folder=model.getText().trim();String problem=StarDist3D.validateModel(new File(folder));if(problem!=null)throw new IllegalArgumentException(problem);
        double probability=0.5,overlap=0.4;
        try{Path thresholds=Paths.get(folder,"thresholds.json");if(Files.exists(thresholds)){JsonObject json=JsonParser.parseString(new String(Files.readAllBytes(thresholds),StandardCharsets.UTF_8)).getAsJsonObject();probability=json.get("prob").getAsDouble();overlap=json.get("nms").getAsDouble();}}catch(Exception error){message("Using editable default thresholds: "+error.getMessage());}
        selection.select(new File(folder).getAbsolutePath(),save().toString(),probability,overlap);dialog.dispose();
    }
}
