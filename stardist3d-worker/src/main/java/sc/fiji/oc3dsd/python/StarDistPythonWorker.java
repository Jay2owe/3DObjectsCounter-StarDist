package sc.fiji.oc3dsd.python;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apposed.appose.*;
import sc.fiji.oc3dn.runtime.WorkerRuntime;

/** Java 21 bridge; the Fiji interface and current segmentation mode remain in the host. */
public final class StarDistPythonWorker {
    public static void main(String[] args)throws Exception{
        Path request=Path.of(args[0]),flag=Path.of(args[1]);
        Thread cancellation=WorkerRuntime.watchCancellation(flag);
        try{
            JsonObject envelope=JsonParser.parseString(Files.readString(request)).getAsJsonObject();
            Path scripts=scripts();
            System.err.println("Preparing managed StarDist3D Python; first use downloads Python and dependencies");
            var environment=Appose.pixi().content(manifest()).subscribeProgress((t,c,m)->System.err.println(t+(m>0?" "+100*c/m+"%":"")))
                    .subscribeOutput(System.err::println).subscribeError(System.err::println).build();
            String bootstrap="import os, sys, json, faulthandler\nfaulthandler.dump_traceback_later(180, exit=True)\n"
                +"os.environ['CUDA_VISIBLE_DEVICES']='-1'\nos.environ['TF_CPP_MIN_LOG_LEVEL']='2'\nos.environ['TF_ENABLE_ONEDNN_OPTS']='0'\n"
                +"sys.path.insert(0,"+new Gson().toJson(scripts.toString())+")\n"
                +"from oc3d_stardist3d.api import dispatch\nfrom oc3d_stardist3d.config import load_config\nrequest=json.loads("+new Gson().toJson(envelope.toString())+")\nthreads=load_config(request['parameters'].get('config'))['runtime']['threads']\nimport tensorflow as tf\n"
                +"tf.config.threading.set_intra_op_parallelism_threads(threads)\ntf.config.threading.set_inter_op_parallelism_threads(1)\n"
                // Scientific DLLs must be imported on the startup thread before Appose opens stdin on Windows.
                +"import numpy, scipy, tifffile, h5py\nfrom stardist.models import StarDist3D, Config3D\n"
                +"faulthandler.cancel_dump_traceback_later()\n";
            // Python's Windows platform probe shells out to CMD. User AutoRun hooks can inherit
            // its output pipe and keep startup waiting forever. Keep its native win32 fallback.
            String launch="import sys,platform\nif sys.platform=='win32':\n    platform._syscmd_ver=lambda system='',release='',version='',**kwargs:(system,release,version)\nimport appose.python_worker\nappose.python_worker.main()";
            boolean windows=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
            Service service=windows?environment.service(List.of("pythonw.exe"),"-c",launch).syntax("python"):environment.python("-c",launch);
            try(Service python=service.init(bootstrap).debug(message->{if(message.startsWith("[WORKER")||message.contains("<INVALID>"))System.err.println(message);})){
                String script="if 'dispatch' not in globals():\n    raise RuntimeError('Scientific Python startup failed; see worker log')\n"
                    +"import json,os,sys,contextlib\nrequest=json.loads(request_json)\nwire=sys.stdout\n"
                    +"def report(message):\n    with contextlib.redirect_stdout(wire):\n        task.update(message=message)\n"
                    +"with contextlib.redirect_stdout(sys.stderr):\n    result=dispatch(request['action'],request['parameters'],progress=report,cancelled=lambda:os.path.exists(cancel_path))\n"
                    +"task.outputs['result']=result\n";
                var task=python.task(script,Map.of("request_json",envelope.toString(),"cancel_path",flag.toString()));
                task.listen(e->{if(e.message!=null&&!e.message.isBlank())System.err.println(e.message);});task.start();
                try{task.waitFor();}catch(InterruptedException interrupted){
                    Files.writeString(flag,"cancel");System.err.println("Cancellation requested; waiting for the current native pass/checkpoint");
                    try{task.waitFor();}catch(TaskException ignored){}python.close();python.waitFor();throw interrupted;
                }
                if(task.status!=Service.TaskStatus.COMPLETE)throw new java.io.IOException("StarDist3D action failed: "+task.error);
                System.out.println(new Gson().toJson(task.outputs.get("result")));python.close();python.waitFor();
            }
        }catch(Throwable error){error.printStackTrace(System.err);System.exit(1);}
        finally{cancellation.interrupt();}
    }
    static String manifest(){
        String os=System.getProperty("os.name").toLowerCase(),arch=System.getProperty("os.arch");
        String platform=os.contains("win")?"win-64":os.contains("mac")?(arch.equals("aarch64")?"osx-arm64":"osx-64"):"linux-64";
        if(!Set.of("amd64","x86_64","aarch64").contains(arch)||arch.equals("aarch64")&&!platform.equals("osx-arm64"))throw new IllegalArgumentException("Supported CPU environments: Windows/Linux x64 and Apple Silicon");
        return "[workspace]\nname=\"oc3d-stardist3d-cpu\"\nchannels=[\"conda-forge\"]\nplatforms=[\""+platform+"\"]\n"
            +"[dependencies]\npython=\"3.11.*\"\n[pypi-dependencies]\nappose=\"==0.12.0\"\npackaging=\"==26.2\"\n"
            +"stardist=\"==0.9.2\"\ncsbdeep=\"==0.8.2\"\ntensorflow=\"==2.15.1\"\nnumpy=\"==1.26.4\"\n"
            +"tifffile=\"==2025.5.10\"\npsutil=\"==7.0.0\"\n";
    }
    static Path scripts()throws Exception{
        var names=List.of("__init__.py","registry.json","config.py","data.py","engine.py","api.py");
        var content=new LinkedHashMap<String,byte[]>();var combined=new java.io.ByteArrayOutputStream();
        for(String name:names)try(var input=StarDistPythonWorker.class.getResourceAsStream("/python/oc3d_stardist3d/"+name)){
            if(input==null)throw new java.io.IOException("Missing packaged Python workflow: "+name);
            byte[] bytes=input.readAllBytes();content.put(name,bytes);combined.write(bytes);
        }
        String hash=WorkerRuntime.hex(java.security.MessageDigest.getInstance("SHA-256").digest(combined.toByteArray())).substring(0,16);
        Path root=Path.of(System.getProperty("user.home"),".local","share","oc3d-stardist3d","worker-"+hash),folder=root.resolve("oc3d_stardist3d");
        Files.createDirectories(folder);
        for(var entry:content.entrySet())Files.write(folder.resolve(entry.getKey()),entry.getValue());
        return root;
    }
}
