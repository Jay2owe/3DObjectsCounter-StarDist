package sc.fiji.oc3dsd.runtime;
import com.google.gson.*;
import ij.IJ;
import ij.ImagePlus;
import ij.io.FileSaver;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import sc.fiji.oc3dn.runtime.WorkerRuntime;

/** Managed-Python request API shared by model training and whole-volume inference. */
public final class StarDist3D {
    public static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    public static final String WARNING="StarDist3D predicts whole volumes and can change object boundaries and counts.\n"
        +"It needs a compatible 3D model; the bundled 2D model cannot be reused.\n"
        +"Validate on representative annotated stacks. The upstream 3D demo is not\n"
        +"a general-purpose biological model. First use downloads a separate Python\n"
        +"environment; 3D prediction/training can need more memory and time.";
    private StarDist3D(){}
    /** Plain JSON public API: callers do not need the plugin's private JSON library. */
    public static String callJson(String action,String parameters,Consumer<String> progress)throws Exception{
        return JSON.toJson(call(action,JsonParser.parseString(parameters).getAsJsonObject(),progress));
    }
    public static JsonObject registry(){
        try(InputStream stream=StarDist3D.class.getResourceAsStream("/python/oc3d_stardist3d/registry.json")){
            if(stream==null)throw new IOException("Missing packaged 3D settings registry");
            return JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
        }catch(IOException error){throw new IllegalStateException(error);}
    }
    public static JsonObject defaults(){
        JsonObject config=new JsonObject();config.addProperty("schema_version",1);
        for(JsonElement element:registry().getAsJsonArray("options")){
            JsonObject option=element.getAsJsonObject();String[] key=option.get("key").getAsString().split("\\.");
            if(!config.has(key[0]))config.add(key[0],new JsonObject());
            config.getAsJsonObject(key[0]).add(key[1],option.get("default").deepCopy());
        }return config;
    }
    public static JsonObject call(String action,JsonObject parameters,Consumer<String> progress)throws Exception{
        JsonObject actions=registry().getAsJsonObject("actions");
        if(!actions.has(action))throw new IllegalArgumentException("Unknown 3D action: "+action);
        Set<String> allowed=new HashSet<String>();for(JsonElement key:actions.getAsJsonArray(action))allowed.add(key.getAsString());
        for(String key:parameters.keySet())if(!allowed.contains(key))throw new IllegalArgumentException("Unknown "+action+" parameter: "+key);
        if(action.equals("describe"))return registry();
        if(Integer.parseInt(System.getProperty("java.specification.version").replace("1.",""))<11)throw new IllegalArgumentException("The optional Python mode needs Fiji on Java 11 or newer; the default mode is unchanged");
        Path temporary=Files.createTempDirectory("oc3d-stardist3d-request-");
        try{
            JsonObject request=new JsonObject();request.addProperty("action",action);request.add("parameters",parameters.deepCopy());
            Files.write(temporary.resolve("request.json"),JSON.toJson(request).getBytes(StandardCharsets.UTF_8));
            String output=WorkerRuntime.run(StarDist3D.class,IJ.class,"sc.fiji.oc3dsd.python.StarDistPythonWorker",
                Collections.singletonList(temporary.resolve("request.json").toString()),temporary.resolve("cancel.flag"),progress);
            return JsonParser.parseString(output).getAsJsonObject();
        }finally{try(java.util.stream.Stream<Path> paths=Files.list(temporary)){for(Path path:(Iterable<Path>)paths::iterator)Files.deleteIfExists(path);}Files.deleteIfExists(temporary);}
    }
    public static JsonObject train(File config,File output,boolean resume,File fineTune,Consumer<String> progress)throws Exception{
        JsonObject request=new JsonObject();request.addProperty("config",config.getAbsolutePath());request.addProperty("output",output.getAbsolutePath());request.addProperty("resume",resume);
        if(fineTune!=null)request.addProperty("fine_tune",fineTune.getAbsolutePath());return call("train",request,progress);
    }
    public static String validateModel(File directory){
        try{
            if(directory==null||!new File(directory,"config.json").isFile())return "Choose a StarDist3D model folder containing config.json and weights";
            JsonObject config;
            try(Reader reader=new InputStreamReader(new FileInputStream(new File(directory,"config.json")),StandardCharsets.UTF_8)){config=JsonParser.parseReader(reader).getAsJsonObject();}
            if(!config.has("n_dim")||config.get("n_dim").getAsInt()!=3||config.get("n_channel_in").getAsInt()!=1)return "The model must be a single-channel StarDist3D model; a 2D model cannot be used";
            if(config.has("n_classes")&&!config.get("n_classes").isJsonNull())return "Classifying models are not supported by this counting workflow";
            if(!new File(directory,"weights_best.h5").isFile()&&!new File(directory,"weights_last.h5").isFile())return "Model folder has no weights_best.h5 or weights_last.h5";
            return null;
        }catch(Exception error){return "Cannot read 3D model: "+error.getMessage();}
    }
    public static ImagePlus predict(ImagePlus input,File model,File config,double probability,double overlap,Consumer<String> progress){
        String problem=validateModel(model);if(problem!=null)throw new IllegalArgumentException(problem);
        Path folder;
        try { folder=Files.createTempDirectory("oc3d-stardist3d-image-"); }
        catch(IOException error) { throw new IllegalStateException(error); }
        try{
            long bytes=(long)input.getWidth()*input.getHeight()*input.getStackSize()*4;
            if(Files.getFileStore(folder).getUsableSpace()<bytes*8+64L*1024*1024)throw new IllegalArgumentException("Insufficient space for whole-volume prediction");
            FileSaver saver=new FileSaver(input);if(!saver.saveAsTiffStack(folder.resolve("input.tif").toString()))throw new IOException("Cannot save image for the Python worker");
            JsonObject request=new JsonObject();request.addProperty("model",model.getAbsolutePath());request.addProperty("image",folder.resolve("input.tif").toString());request.addProperty("output",folder.toString());
            request.addProperty("probability",probability);request.addProperty("overlap",overlap);
            JsonArray spacing=new JsonArray();spacing.add(input.getCalibration().pixelDepth);spacing.add(input.getCalibration().pixelHeight);spacing.add(input.getCalibration().pixelWidth);request.add("spacing_zyx",spacing);
            String unit=input.getCalibration().getUnit();
            if(unit.equals("micron")||unit.equals("microns")||unit.equals("µm")||unit.equals("micrometer"))unit="um";
            if(unit.equals("pixels"))unit="pixel";
            request.addProperty("unit",unit);
            if(config!=null)request.addProperty("config",config.getAbsolutePath());
            JsonObject result=call("predict",request,progress);ImagePlus labels=IJ.openImage(folder.resolve("labels.tif").toString());
            if(labels==null)throw new IOException("StarDist3D did not return labels");
            if(labels.getWidth()!=input.getWidth()||labels.getHeight()!=input.getHeight()||labels.getStackSize()!=input.getStackSize())throw new IOException("StarDist3D label dimensions differ from the image");
            labels.setCalibration(input.getCalibration().copy());labels.setProperty("stardist3d_provenance",result.getAsJsonObject("receipt").toString());return labels;
        }catch(Exception error){throw new IllegalStateException(error.getMessage(),error);}
        finally{try{try(java.util.stream.Stream<Path> paths=Files.list(folder)){for(Path path:(Iterable<Path>)paths::iterator)Files.deleteIfExists(path);}Files.deleteIfExists(folder);}catch(IOException cleanup){IJ.log("StarDist3D temporary files: "+folder);}}
    }
}
