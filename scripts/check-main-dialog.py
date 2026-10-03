"""Guard the familiar default control order and layout against new advanced rows."""
from pathlib import Path
import json,re,sys
root=Path(__file__).resolve().parents[1]
source=(root/'src/main/java/sc/fiji/oc3dsd/ui/OC3DSDDialog.java').read_text(encoding='utf8')
source=source.replace('new StarDistModelSelector(gd, model);','gd.addChoice("Model",')
actual=re.findall(r'gd\.add(NumericField|StringField|Choice|Checkbox)\("([^"\n]+)"',source)
selector=(root/'src/main/java/sc/fiji/oc3dsd/ui/StarDistModelSelector.java').read_text(encoding='utf8')
expected=json.loads((root/'src/test/resources/ui/default-controls.json').read_text())
if [list(row) for row in actual]!=expected or 'addCheckboxGroup(' in source or 'new StarDistModeControls' in source or 'gd.addChoice("Model",' not in selector or 'Import model...' not in selector or 'gd.enableYesNoCancel("OK", "Tune parameters...")' not in source:
    print('FAIL: main control order/layout changed; put optional controls in StarDist Options...',file=sys.stderr)
    sys.exit(1)
print(f'PASS: {len(actual)} established controls in their original order; Model uses a dropdown with import; no advanced rows')
