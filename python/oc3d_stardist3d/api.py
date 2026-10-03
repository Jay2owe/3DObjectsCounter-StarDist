from .config import REGISTRY,load_config

def dispatch(action,parameters=None,progress=lambda message:None,cancelled=lambda:False):
    parameters=parameters or {}
    if action not in REGISTRY["actions"]:raise ValueError(f"Unknown action: {action}")
    unknown=set(parameters)-set(REGISTRY["actions"][action])
    if unknown:raise ValueError(f"Unknown {action} parameters: {sorted(unknown)}")
    if action=="describe":return REGISTRY
    from .data import example,dataset
    if action=="example":return example(parameters["output"])
    if action=="validate":
        _,info=dataset(load_config(parameters["config"]),progress,cancelled)
        return {"answer":"Annotated volumes and specimen splits passed validation.","dataset":info}
    from . import engine
    if action=="demo_model":return engine.demo_model(parameters["output"],progress)
    return getattr(engine,action)(parameters,progress,cancelled)
