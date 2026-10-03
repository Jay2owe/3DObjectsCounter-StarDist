import csv
import hashlib
import json
import os
from pathlib import Path
import shutil
import time
import numpy as np
import tifffile
from .config import defaults, load_config, digest

def sha256(path):
    result=hashlib.sha256()
    with Path(path).open("rb") as source:
        for block in iter(lambda:source.read(1024*1024),b""):
            result.update(block)
    return result.hexdigest()

def atomic_json(path,value):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    temporary=path.with_name(path.name+".pending")
    temporary.write_text(json.dumps(value,indent=2,allow_nan=False)+"\n",encoding="utf-8")
    for attempt in range(6):
        try:
            os.replace(temporary,path);return
        except PermissionError:
            if attempt==5:raise
            time.sleep(0.05*(attempt+1))

def guard(path, disk=0, memory=0):
    path=Path(path)
    while not path.exists():path=path.parent
    if shutil.disk_usage(path).free < disk + 64*1024**2:
        raise ValueError("Insufficient disk space for this run")
    if memory:
        import psutil
        if psutil.virtual_memory().available < memory + 128*1024**2:
            raise ValueError("Insufficient available RAM for this dataset/model")

def read_volume(path, labels=False, spacing=None, unit=None):
    with tifffile.TiffFile(path) as file:
        series=file.series[0]
        shape,axes=series.shape,series.axes
        if len(shape)!=3 or shape[0]<2 or axes not in ("ZYX","QYX","IYX"):
            raise ValueError(f"{Path(path).name}: requires a single-channel Z,Y,X volume, not time/channel data ({axes}, {shape})")
        guard(Path(path).parent,memory=int(np.prod(shape))*series.dtype.itemsize*3)
        image=series.asarray()
        metadata=file.imagej_metadata or {}
        known=metadata.get("unit")
        canonical={"micron":"um","microns":"um","micrometer":"um","micrometers":"um",
                   "µm":"um","μm":"um",r"\u00B5m":"um",r"\u00b5m":"um",r"\u03BCm":"um",
                   "pixels":"pixel"}.get(known,known)
        if unit and canonical not in (None,"pixel") and canonical!=unit:
            raise ValueError(f"{Path(path).name}: calibrated unit differs from the model/training unit")
        tags=file.pages[0].tags
        if spacing and metadata.get("spacing") and "XResolution" in tags and metadata.get("unit") not in ("pixel","pixels",None):
            resolution=tags["XResolution"].value
            xy=resolution[1]/resolution[0]
            yr=tags["YResolution"].value if "YResolution" in tags else resolution
            actual=[float(metadata["spacing"]),yr[1]/yr[0],xy]
            if not np.allclose(actual,spacing,rtol=1e-4,atol=1e-6):
                raise ValueError(f"{Path(path).name}: voxel spacing differs from the training/model scale")
    if not np.isfinite(image).all():
        raise ValueError(f"{Path(path).name}: nonfinite pixels")
    if labels:
        if np.any(image<0) or np.any(image!=np.floor(image)) or image.max()>16777216:
            raise ValueError(f"{Path(path).name}: labels must be nonnegative exact integers")
        return image.astype(np.uint32)
    return image.astype(np.float32)

def save_volume(path,image,spacing,unit="um"):
    tifffile.imwrite(path,np.asarray(image),imagej=True,photometric="minisblack",
                     resolution=(1/spacing[2],1/spacing[1]),
                     metadata={"axes":"ZYX","spacing":spacing[0],"unit":unit})

def normalize(image,config):
    lower,upper=np.percentile(image,[config["data"]["lower_percentile"],config["data"]["upper_percentile"]])
    return ((image-lower)/max(float(upper-lower),1e-6)).astype(np.float32)

def dataset(config,progress=lambda message:None,cancelled=lambda:False, require_train=True):
    manifest=Path(config["data"]["manifest"])
    if not manifest.is_file():
        raise ValueError("Choose an annotated pairs CSV")
    with manifest.open(encoding="utf-8-sig",newline="") as file:
        reader=csv.DictReader(file)
        if not {"image","labels","specimen_id","split"}.issubset(reader.fieldnames or []):
            raise ValueError("CSV needs image, labels, specimen_id and split columns")
        rows=list(reader)
    if not rows:raise ValueError("Dataset is empty")
    resolved=[];groups={};seen_files=set();content_groups={}
    for row in rows:
        if cancelled():raise InterruptedError("Dataset checking cancelled")
        specimen=row["specimen_id"].strip();split=row["split"].strip()
        if not specimen or split not in ("train","validation","test"):
            raise ValueError("Every row needs a specimen_id and train/validation/test split")
        if specimen in groups and groups[specimen]!=split:
            raise ValueError(f"Specimen {specimen} crosses data splits")
        groups[specimen]=split
        image=(manifest.parent/row["image"]).resolve();labels=(manifest.parent/row["labels"]).resolve()
        if image in seen_files:raise ValueError("Duplicate image in annotated pairs")
        seen_files.add(image)
        if image==labels or not image.is_file() or not labels.is_file():
            raise ValueError("Raw/label paths must be distinct existing files")
        raw_hash=sha256(image);label_hash=sha256(labels)
        if raw_hash in content_groups and content_groups[raw_hash]!=split:
            raise ValueError("Identical image content crosses data splits")
        content_groups[raw_hash]=split
        resolved.append({"image":str(image),"labels":str(labels),"specimen_id":specimen,
                         "split":split,"image_sha256":raw_hash,"labels_sha256":label_hash})
    if require_train and not {"train","validation"}.issubset(set(groups.values())):
        raise ValueError("Separate training and validation specimens are required")
    estimate=sum(Path(row[k]).stat().st_size for row in resolved for k in ("image","labels"))*12
    guard(manifest.parent,memory=estimate)
    for index,row in enumerate(resolved):
        progress(f"Checking annotated volume {index+1}/{len(resolved)}")
        row["raw"]=read_volume(row["image"],spacing=config["data"]["spacing_zyx"],unit=config["data"]["unit"])
        row["truth"]=read_volume(row["labels"],labels=True)
        if row["raw"].shape!=row["truth"].shape:
            raise ValueError("Raw and label volume dimensions differ")
        if row["split"] in ("train","validation"):
            if not row["truth"].any():raise ValueError("Training/validation volume has no annotated objects")
            if any(p>s for p,s in zip(config["network"]["patch_zyx"],row["raw"].shape)):
                raise ValueError("Training patches must fit every training and validation volume")
    identities=[{k:v for k,v in row.items() if k not in ("raw","truth")} for row in resolved]
    content=[{k:v for k,v in row.items() if k not in ("image","labels")} for row in identities]
    return resolved,{"schema_version":1,"rows":identities,"dataset_sha256":digest(content),
                     "specimens":{split:sum(v==split for v in groups.values()) for split in ("train","validation","test")}}

def example(output):
    root=Path(output).resolve()
    if root.exists() and any(root.iterdir()):raise ValueError("Choose a new or empty example folder")
    guard(root,disk=2*1024**2);root.mkdir(parents=True,exist_ok=True)
    config=defaults();config["network"].update(patch_zyx=[16,32,32],depth=2,features=4,rays=32)
    config["training"].update(epochs=20,steps=5,optimize_thresholds=False)
    config["data"]["manifest"]="pairs.csv"
    z,y,x=np.mgrid[:16,:32,:32];rng=np.random.default_rng(20261002);rows=[]
    for index in range(6):
        truth=np.zeros(z.shape,np.uint16)
        truth[((z-8)/3)**2+((y-11)/5)**2+((x-10)/5)**2<1]=1
        truth[((z-8)/3)**2+((y-21)/5)**2+((x-22)/5)**2<1]=2
        raw=np.clip(rng.normal(15,2,z.shape)+120*(truth>0),0,255).astype(np.uint8)
        stem=f"specimen-{index+1}"
        save_volume(root/(stem+"-image.tif"),raw,config["data"]["spacing_zyx"])
        save_volume(root/(stem+"-labels.tif"),truth,config["data"]["spacing_zyx"])
        rows.append({"image":stem+"-image.tif","labels":stem+"-labels.tif","specimen_id":stem,
                     "split":"train" if index<4 else "validation" if index==4 else "test"})
    with (root/"pairs.csv").open("w",newline="",encoding="utf-8") as file:
        writer=csv.DictWriter(file,fieldnames=rows[0]);writer.writeheader();writer.writerows(rows)
    atomic_json(root/"config.json",config)
    return {"answer":"Six simulated annotated volumes; no biological accuracy claim.","config":str(root/"config.json"),"output":str(root)}
