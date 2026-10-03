import copy
import hashlib
import json
import math
from pathlib import Path

REGISTRY = json.loads(Path(__file__).with_name("registry.json").read_text())

def defaults():
    config = {"schema_version": 1}
    for option in REGISTRY["options"]:
        section, name = option["key"].split(".")
        config.setdefault(section, {})[name] = copy.deepcopy(option["default"])
    return config

def load_config(source=None):
    base = Path.cwd()
    if isinstance(source, (str, Path)):
        path = Path(source).resolve()
        source = json.loads(path.read_text(encoding="utf-8-sig"))
        base = path.parent
    source = source or {}
    if not isinstance(source, dict):
        raise ValueError("Configuration must be a JSON object")
    config = defaults()
    for section, values in source.items():
        if section == "schema_version":
            if values != 1:
                raise ValueError("Unsupported configuration schema")
            continue
        if section not in config or not isinstance(values, dict):
            raise ValueError(f"Unknown or invalid configuration section: {section}")
        for key, value in values.items():
            if key not in config[section]:
                raise ValueError(f"Unknown configuration option: {section}.{key}")
            config[section][key] = copy.deepcopy(value)
    for option in REGISTRY["options"]:
        section, key = option["key"].split(".")
        value, kind = config[section][key], option["kind"]
        valid = (type(value) is bool if kind == "boolean" else
                 type(value) is int if kind == "integer" else
                 type(value) in (int, float) and math.isfinite(value) if kind == "number" else
                 isinstance(value, str) if kind == "text" else isinstance(value, list))
        if not valid:
            raise ValueError(f"Invalid {option['key']}: expected {kind}")
    for section, key, integer in (("data","spacing_zyx",False),("network","patch_zyx",True),
                                 ("network","grid_zyx",True),("inference","tiles_zyx",True)):
        values = config[section][key]
        if len(values) != 3 or any(type(v) not in ((int,) if integer else (int,float)) or not math.isfinite(v) or v <= 0 for v in values):
            raise ValueError(f"{section}.{key} requires three positive Z,Y,X values")
    network, training, data = (config[s] for s in ("network","training","data"))
    if not 1 <= network["depth"] <= 5 or not 2 <= network["features"] <= 128 or not 16 <= network["rays"] <= 256:
        raise ValueError("Depth must be 1–5, features 2–128 and rays 16–256")
    if any(v & (v-1) for v in network["grid_zyx"]):
        raise ValueError("Prediction grid entries must be powers of two")
    if any(p % (g*2**network["depth"]) for p,g in zip(network["patch_zyx"],network["grid_zyx"])):
        raise ValueError("Training patches must be divisible by grid times 2^depth")
    if any(training[key] < 1 for key in ("epochs","steps","batch_size")) or training["learning_rate"] <= 0 or training["seed"] < 0:
        raise ValueError("Epochs, steps, batch size and learning rate must be positive; seed nonnegative")
    if not 0 <= data["lower_percentile"] < data["upper_percentile"] <= 100 or not 1 <= config["runtime"]["threads"] <= 32:
        raise ValueError("Check normalization percentiles and CPU threads (1–32)")
    if data["unit"] not in ("um","nm","mm","pixel"):
        raise ValueError("Voxel spacing unit must be um, nm, mm or pixel")
    if data["manifest"]:
        data["manifest"] = str((base / data["manifest"]).resolve())
    return config

def digest(value):
    return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(",",":"),allow_nan=False).encode()).hexdigest()

def signature(config):
    config = copy.deepcopy(config)
    config["training"].pop("epochs")
    config.pop("runtime")
    config["data"].pop("manifest")
    return digest(config)
