import copy
import csv
import json
import sys
from pathlib import Path
import numpy as np
import pytest
sys.path.insert(0,str(Path(__file__).parents[1]))
from oc3d_stardist3d.config import defaults,load_config,signature
from oc3d_stardist3d.data import dataset,example,normalize,read_volume,save_volume
from oc3d_stardist3d.api import dispatch

@pytest.fixture
def sample(tmp_path):
    result=example(tmp_path/'example')
    return load_config(result['config'])

def test_example_has_separate_specimens(sample):
    rows,identity=dataset(sample)
    assert identity['specimens']=={'train':4,'validation':1,'test':1}
    assert all(row['raw'].shape==row['truth'].shape for row in rows)

def test_resume_can_extend_epochs_but_not_change_preprocessing():
    config=defaults();longer=copy.deepcopy(config);longer['training']['epochs']+=10
    assert signature(config)==signature(longer)
    longer['data']['lower_percentile']=2
    assert signature(config)!=signature(longer)

@pytest.mark.parametrize('section,key,value',[
 ('network','grid_zyx',[1,3,2]),('network','patch_zyx',[15,64,64]),
 ('data','spacing_zyx',[0,1,1]),('data','unit','unknown'),
 ('training','batch_size',False),('training','learning_rate',float('nan')),
 ('runtime','threads',33),('inference','tiles_zyx',[1,1])])
def test_bad_settings_rejected(section,key,value):
    with pytest.raises(ValueError):load_config({section:{key:value}})

def test_specimen_leakage_is_rejected(sample):
    path=Path(sample['data']['manifest']);rows=list(csv.DictReader(path.open()))
    rows[-1]['specimen_id']=rows[0]['specimen_id']
    with path.open('w',newline='') as file:
        writer=csv.DictWriter(file,fieldnames=rows[0]);writer.writeheader();writer.writerows(rows)
    with pytest.raises(ValueError,match='crosses data splits'):dataset(sample)

def test_incompatible_spacing_rejected(sample):
    rows,_=dataset(sample)
    with pytest.raises(ValueError,match='voxel spacing'):read_volume(rows[0]['image'],spacing=[1,1,1])

def test_constant_normalization_is_finite():
    assert np.isfinite(normalize(np.ones((2,3,3),np.float32),defaults())).all()

def test_imagej_escaped_micron_unit_is_compatible(tmp_path):
    # ImageJ 1.54p exports the calibration as the literal ASCII escape below.
    path=tmp_path/'imagej.tif'
    raw=np.arange(48,dtype=np.float32).reshape(3,4,4)
    save_volume(path,raw,[2,1,1],unit=r'\u00B5m')
    np.testing.assert_array_equal(read_volume(path,spacing=[2,1,1],unit='um'),raw)
    with pytest.raises(ValueError,match='calibrated unit'):
        read_volume(path,spacing=[2,1,1],unit='nm')

def test_api_rejects_unknown_action_and_arguments():
    with pytest.raises(ValueError):dispatch('invented',{})
    with pytest.raises(ValueError):dispatch('train',{'hidden_option':True})
    assert dispatch('describe',{})['schema_version']==1
