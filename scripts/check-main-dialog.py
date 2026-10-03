"""Guard the familiar default control order and layout against new advanced rows."""
from pathlib import Path
import json,re,sys
root=Path(__file__).resolve().parents[1]
source=(root/'src/main/java/sc/fiji/oc3dsd/ui/OC3DSDDialog.java').read_text(encoding='utf8')
actual=re.findall(r'gd\.add(NumericField|StringField|Choice|Checkbox)\("([^"\n]+)"',source)
expected=json.loads((root/'src/test/resources/ui/default-controls.json').read_text())
if [list(row) for row in actual]!=expected or 'addCheckboxGroup(' in source or 'new StarDistModeControls' in source:
    print('FAIL: main control order/layout changed; put optional controls in StarDist Options...',file=sys.stderr)
    sys.exit(1)
print(f'PASS: {len(actual)} established controls in their original order; no advanced rows')
