import copy
import json
from pathlib import Path
import time
import numpy as np
from .config import load_config, signature
from .data import atomic_json, dataset, guard, normalize, read_volume, save_volume, sha256

def versions():
    from importlib.metadata import version
    return {name:version(name) for name in ("stardist","tensorflow","numpy","csbdeep","appose")}

def model_metadata(folder):
    folder=Path(folder).resolve()
    if not (folder/"config.json").is_file() and (folder/"model/config.json").is_file():
        folder=folder/"model"
    config=json.loads((folder/"config.json").read_text())
    if config.get("n_dim")!=3 or config.get("n_channel_in")!=1 or config.get("n_classes") is not None:
        raise ValueError("Choose a single-channel, non-classifying StarDist3D model folder; 2D models cannot be used")
    weights=next((folder/name for name in ("weights_best.h5","weights_last.h5") if (folder/name).is_file()),None)
    if weights is None:raise ValueError("3D model folder has no weights_best.h5 or weights_last.h5")
    metadata=json.loads((folder/"oc3d-model.json").read_text()) if (folder/"oc3d-model.json").is_file() else None
    if metadata and (metadata.get("schema_version")!=1 or metadata.get("weights_sha256")!=sha256(weights) or metadata.get("config_sha256")!=sha256(folder/"config.json")):
        raise ValueError("Model metadata or weights checksum differs; restore the saved package")
    return folder,weights,metadata

def load_model(folder):
    from stardist.models import StarDist3D
    folder,weights,metadata=model_metadata(folder)
    model=StarDist3D(None,name=folder.name,basedir=str(folder.parent))
    model.load_weights(weights.name)
    return model,folder,weights,metadata

def settings_for_model(metadata,options=None):
    supplied=load_config(options)
    if not metadata or not metadata.get("training_config"):return supplied
    frozen=load_config(metadata["training_config"])
    frozen["runtime"]=supplied["runtime"]
    frozen["inference"]=supplied["inference"]
    return frozen

def cancellation_check(cancelled):
    if cancelled():raise InterruptedError("Action cancelled; completed checkpoints/results remain")

def predict(parameters,progress,cancelled):
    started=time.monotonic()
    model,folder,weights,metadata=load_model(parameters["model"])
    config=settings_for_model(metadata,parameters.get("config"))
    spacing=parameters.get("spacing_zyx",config["data"]["spacing_zyx"])
    if metadata and metadata.get("training_config") and not np.allclose(spacing,config["data"]["spacing_zyx"],rtol=1e-4,atol=1e-6):
        raise ValueError("Counting voxel spacing differs from this model's training scale; resample explicitly or use a compatible model")
    unit=parameters.get("unit",config["data"]["unit"])
    if unit!=config["data"]["unit"]:raise ValueError("Input calibration unit differs from the model/configuration; choose a compatible model or resample explicitly")
    image=Path(parameters["image"]).resolve()
    raw=read_volume(image,spacing=spacing,unit=unit)
    guard(parameters["output"],disk=raw.nbytes*6,memory=raw.nbytes*30)
    cancellation_check(cancelled)
    progress("Predicting whole-volume StarDist3D objects")
    options={"n_tiles":tuple(config["inference"]["tiles_zyx"])}
    for request,key in (("probability","prob_thresh"),("overlap","nms_thresh")):
        if request in parameters:
            value=parameters[request]
            if type(value) not in (int,float) or not 0<=value<=1:
                raise ValueError(f"{request} must be between zero and one")
            options[key]=value
    labels,details=model.predict_instances(normalize(raw,config),**options)
    cancellation_check(cancelled)
    if labels.max()>16777216:raise ValueError("Labels exceed exact ImageJ float storage")
    root=Path(parameters["output"]).resolve();root.mkdir(parents=True,exist_ok=True)
    save_volume(root/"labels.tif",labels.astype(np.float32),spacing,config["data"]["unit"])
    receipt={"schema_version":1,"segmentation":"stardist3d","source_sha256":sha256(image),
             "model":str(folder),"weights_sha256":sha256(weights),"versions":versions(),
             "normalization":{k:config["data"][k] for k in ("lower_percentile","upper_percentile")},
             "spacing_zyx":spacing,"unit":unit,"tiles_zyx":config["inference"]["tiles_zyx"],
             "probability":options.get("prob_thresh",model.thresholds.prob),
             "overlap":options.get("nms_thresh",model.thresholds.nms),
             "objects":int(labels.max()),"elapsed_seconds":time.monotonic()-started,
             "imported_model":metadata is None,"labels_sha256":sha256(root/"labels.tif")}
    atomic_json(root/"prediction.json",receipt)
    return {"answer":f"Predicted {int(labels.max())} whole-volume objects.","output":str(root),"receipt":receipt}

def train(parameters,progress,cancelled):
    import tensorflow as tf
    from stardist import Rays_GoldenSpiral
    from stardist.models import Config3D,StarDist3D
    config=load_config(parameters["config"])
    rows,identity=dataset(config,progress,cancelled)
    output=Path(parameters["output"]).resolve();resume=bool(parameters.get("resume",False))
    if resume and parameters.get("fine_tune"):raise ValueError("Use resume or fine-tune, not both")
    if not resume and output.exists() and any(output.iterdir()):
        raise ValueError("Choose an empty/new training folder, or resume explicitly")
    network=config["network"];training=config["training"]
    estimate=int(np.prod(network["patch_zyx"]))*training["batch_size"]*network["features"]*2**network["depth"]*128
    guard(output,disk=max(estimate*4,64*1024**2),memory=max(estimate,256*1024**2))
    output.mkdir(parents=True,exist_ok=True);seed=training["seed"];tf.keras.utils.set_random_seed(seed)
    spacing=config["data"]["spacing_zyx"];anisotropy=tuple(v/min(spacing) for v in spacing)
    kwargs=dict(axes="ZYX",rays=Rays_GoldenSpiral(network["rays"],anisotropy=anisotropy),
                grid=tuple(network["grid_zyx"]),anisotropy=anisotropy,n_channel_in=1,
                backbone="unet",unet_n_depth=network["depth"],unet_n_filter_base=network["features"],
                train_patch_size=tuple(network["patch_zyx"]),train_batch_size=training["batch_size"],
                train_learning_rate=training["learning_rate"],train_epochs=training["epochs"],
                train_steps_per_epoch=training["steps"],train_n_val_patches=1,train_tensorboard=False,
                train_reduce_lr=None,use_gpu=False)
    model=StarDist3D(None if resume else Config3D(**kwargs),name="model",basedir=str(output))
    model.prepare_for_training()
    checkpoint=tf.train.Checkpoint(model=model.keras_model,optimizer=model.keras_model.optimizer)
    state_path=output/"training-state.json";completed=0;best=float("inf");history=[]
    if resume:
        state=json.loads(state_path.read_text())
        if state["configuration_signature"]!=signature(config) or state["dataset_sha256"]!=identity["dataset_sha256"]:
            raise ValueError("Resume requires matching data, splits, preprocessing, architecture and training settings")
        completed=state["completed_epochs"];history=state["history"];best=state["best_validation_loss"] if state["best_validation_loss"] is not None else float("inf")
        checkpoint.restore(str(output/state["checkpoint"])).expect_partial()
        for callback in model.callbacks:
            if hasattr(callback,"best"):callback.best=best
    elif parameters.get("fine_tune"):
        original,folder,weights,metadata=load_model(parameters["fine_tune"])
        previous=original.config
        for key in ("n_rays","grid","unet_n_depth","unet_n_filter_base","anisotropy"):
            if getattr(previous,key,None)!=getattr(model.config,key,None):
                raise ValueError(f"Fine-tuning requires matching {key}")
        if metadata and metadata.get("training_config"):
            previous_config=metadata["training_config"]
            if previous_config["data"]!=config["data"]:
                for key in ("spacing_zyx","unit","lower_percentile","upper_percentile"):
                    if previous_config["data"][key]!=config["data"][key]:
                        raise ValueError("Fine-tuning requires matching scale and normalization")
        model.load_weights(str(weights))
    if completed>=training["epochs"]:raise ValueError("Extend total epochs to resume this completed run")
    atomic_json(output/"training-config.json",config);atomic_json(output/"split-manifest.json",identity)
    request={"action":"train","parameters":{**parameters,"config":config}}
    atomic_json(output/"request.json",request)
    (output/"replay.py").write_text("from oc3d_stardist3d.api import dispatch\nimport json\nfrom pathlib import Path\nrequest=json.loads(Path(__file__).with_name('request.json').read_text())\n# Choose a new output folder when replaying a fresh training run.\nprint(dispatch(request['action'],request['parameters']))\n")
    train_rows=[r for r in rows if r["split"]=="train"];val_rows=[r for r in rows if r["split"]=="validation"]
    x=[normalize(r["raw"],config) for r in train_rows];y=[r["truth"] for r in train_rows]
    xv=[normalize(r["raw"],config) for r in val_rows];yv=[r["truth"] for r in val_rows]
    def persist(epoch,logs):
        nonlocal best
        value=float(logs.get("val_loss",float("inf")));best=min(best,value)
        prefix=output/"resume"/f"epoch-{epoch}"/"state";prefix.parent.mkdir(parents=True,exist_ok=True)
        checkpoint.write(str(prefix))
        atomic_json(state_path,{"schema_version":1,"completed_epochs":epoch,
                    "configuration_signature":signature(config),"dataset_sha256":identity["dataset_sha256"],
                    "checkpoint":str(prefix.relative_to(output)),"best_validation_loss":best if np.isfinite(best) else None,"history":history})
        # Keep the latest and previous completed checkpoint after updating the durable pointer.
        import shutil
        for old in prefix.parent.parent.glob("epoch-*"):
            if old.is_dir() and old.name[6:].isdigit() and int(old.name[6:])<epoch-1:
                shutil.rmtree(old)
    if not resume:
        persist(0,{})
        best=float("inf")
    class Progress(tf.keras.callbacks.Callback):
        def on_train_batch_begin(self,batch,logs=None):
            cancellation_check(cancelled)
        def on_train_batch_end(self,batch,logs=None):
            progress(f"Training batch {batch+1}/{training['steps']}")
            cancellation_check(cancelled)
        def on_epoch_end(self,epoch,logs=None):
            logs={key:float(value) for key,value in (logs or {}).items()}
            absolute=completed+epoch+1;history.append({"epoch":absolute,**logs});persist(absolute,logs)
            atomic_json(output/"history.json",history)
            progress(f"Completed epoch {absolute}/{training['epochs']}; validation loss {logs.get('val_loss',0):.5f}")
    model.callbacks.append(Progress())
    rng=np.random.default_rng(seed+completed)
    def augment(raw,truth):
        raw,truth=raw.copy(),truth.copy()
        for axis in range(3):
            if rng.random()<0.5:raw=np.flip(raw,axis);truth=np.flip(truth,axis)
        return np.asarray(raw*rng.uniform(0.9,1.1),np.float32),truth
    try:
        progress("Training StarDist3D on annotated training specimens")
        model.train(x,y,validation_data=(xv,yv),augmenter=augment if training["augment"] else None,
                    seed=seed+completed,epochs=training["epochs"]-completed,steps_per_epoch=training["steps"])
        cancellation_check(cancelled);model.load_weights("weights_best.h5")
        if training["optimize_thresholds"]:
            progress("Fitting thresholds on validation specimens")
            model.optimize_thresholds(xv,yv)
        cancellation_check(cancelled)
        folder=output/"model";weights=folder/"weights_best.h5"
        metadata={"schema_version":1,"library":"stardist","versions":versions(),"training_config":config,
                  "split_manifest":identity,"weights_sha256":sha256(weights),"config_sha256":sha256(folder/"config.json"),
                  "thresholds":{"probability":float(model.thresholds.prob),"overlap":float(model.thresholds.nms)}}
        atomic_json(folder/"oc3d-model.json",metadata)
        labels,_=model.predict_instances(xv[0],n_tiles=tuple(config["inference"]["tiles_zyx"]))
        save_volume(output/"validation-raw.tif",val_rows[0]["raw"],spacing,config["data"]["unit"])
        save_volume(output/"validation-truth.tif",yv[0].astype(np.float32),spacing,config["data"]["unit"])
        save_volume(output/"validation-prediction.tif",labels.astype(np.float32),spacing,config["data"]["unit"])
        return {"answer":f"Trained {training['epochs']} total epochs; review validation before use.","output":str(output),
                "model":str(folder),"thresholds":metadata["thresholds"]}
    except Exception as error:
        (output/"FAILED.txt").write_text(str(error)+"\n");raise

def evaluate(parameters,progress,cancelled):
    from stardist.matching import matching_dataset
    model,folder,weights,metadata=load_model(parameters["model"])
    supplied=load_config(parameters["config"]);config=settings_for_model(metadata,supplied)
    config["data"]["manifest"]=supplied["data"]["manifest"]
    split=parameters.get("split","test")
    if split not in ("validation","test"):raise ValueError("Evaluate held-out validation or test specimens")
    rows,identity=dataset(config,progress,cancelled,require_train=False)
    selected=[r for r in rows if r["split"]==split]
    if not selected:raise ValueError(f"No {split} specimens")
    if metadata:
        trained={r["image_sha256"] for r in metadata["split_manifest"]["rows"] if r["split"]=="train"}
        trained_specimens={r["specimen_id"] for r in metadata["split_manifest"]["rows"] if r["split"]=="train"}
        if any(r["image_sha256"] in trained or r["specimen_id"] in trained_specimens for r in selected):
            raise ValueError("Evaluation includes a training image or specimen")
    output=Path(parameters["output"]).resolve()
    if output.exists() and any(output.iterdir()):raise ValueError("Choose a new evaluation folder")
    output.mkdir(parents=True,exist_ok=True);predictions=[]
    for i,row in enumerate(selected):
        cancellation_check(cancelled);progress(f"Evaluating {split} volume {i+1}/{len(selected)}")
        labels,_=model.predict_instances(normalize(row["raw"],config),n_tiles=tuple(config["inference"]["tiles_zyx"]))
        predictions.append(labels);spacing=config["data"]["spacing_zyx"]
        for name,pixels in (("raw",row["raw"]),("truth",row["truth"].astype(np.float32)),("prediction",labels.astype(np.float32))):
            save_volume(output/f"{i+1:03d}-{name}.tif",pixels,spacing,config["data"]["unit"])
    metrics=matching_dataset([r["truth"] for r in selected],predictions,thresh=0.5)._asdict()
    metrics={k:float(v) if isinstance(v,(np.floating,float)) else int(v) if isinstance(v,(np.integer,int)) else v for k,v in metrics.items()}
    result={"schema_version":1,"answer":f"Evaluated {len(selected)} held-out {split} volumes.","output":str(output),
            "metrics":metrics,"model":str(folder),"weights_sha256":sha256(weights),"dataset":identity,"versions":versions()}
    atomic_json(output/"evaluation.json",result);return result

def demo_model(output,progress):
    from stardist.models import StarDist3D
    root=Path(output).resolve()
    if root.exists() and any(root.iterdir()):raise ValueError("Choose an empty/new model folder")
    progress("Downloading upstream 3D demonstration weights; validate before biological use")
    model=StarDist3D.from_pretrained("3D_demo")
    import shutil
    shutil.copytree(model.logdir,root)
    atomic_json(root/"DEMONSTRATION.json",{"warning":"Upstream demonstration model; not validated for your microscopy data.","versions":versions()})
    return {"answer":"Downloaded demonstration model; validate on representative annotated stacks.","model":str(root),"output":str(root)}
