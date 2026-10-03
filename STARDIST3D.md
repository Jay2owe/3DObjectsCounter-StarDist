# Optional whole-volume StarDist3D

The existing **StarDist2D + Z linking (current)** mode is the default. Existing
macros and batch exports retain their previous behavior. The new **StarDist3D -
whole volume** choice predicts 3D objects directly, then uses the same object
measurement, size/edge filtering and maps as the current counter. Slice linking
settings do not apply to this mode. It adds no morphological filtering.

The original main and batch counting dialogs keep their established fields and
layout. Open **Analyze > 3D Objects Counter - StarDist Options...** to select the
optional mode or access model training. The selection applies to interactive
counts and batches in this Fiji session; restarting Fiji restores the current
mode. Recorded macros always identify 3D mode explicitly.

Switching in Options shows a warning: boundaries and counts may change, a compatible 3D
model is required, the bundled 2D model cannot be reused, and first use downloads
a separate Python environment. The upstream demonstration model is offered
explicitly for exploration; it is not a general-purpose model validated for
your biological data.

## Python setup

Python and its scientific packages are installed and reused automatically,
using the same Appose/Pixi environment mechanism and shared Java worker launcher
as the Cellpose counter. Installing Cellpose is not required. The StarDist
environment is separate because it uses TensorFlow instead of Cellpose's
PyTorch. No Python executable, terminal command or pip installation is needed
in the normal user workflow. Internet access is needed for first setup and
an optional demo-model download.

The optional mode needs Fiji on Java 11 or newer and the existing counter
family's Java 21 worker runtime. Fiji itself keeps its current Java runtime.
This version runs on the CPU; a graphics card is not required. The pinned stack
is Python 3.11, StarDist 0.9.2, CSBDeep 0.8.2, TensorFlow 2.15.1 and NumPy 1.26.4.
The current mode starts no Python environment.

## Train on your own annotated stacks

1. Open **StarDist Options...**, choose the whole-volume mode and accept the switch warning. Click
   **Train / configure / load 3D model...**.
2. Prepare single-channel TIFF volumes and matching instance-label TIFFs.
   Both must have the same Z,Y,X dimensions. Background is 0; each complete
   object has a unique positive integer label across its Z slices. Annotate
   every object in each training region; incomplete annotations teach the
   model to treat the missing objects as background.
3. Make a CSV with the columns below. Paths can be relative to the CSV.
   Separate specimens, rather than slices from the same specimen, into
   training, validation and test sets. At least one training and one validation
   specimen are required. Reserve test specimens for the final evaluation.
4. Select that CSV, set **Voxel spacing Z,Y,X** and its unit to match your
   stacks, and configure patches, rays, network depth/width, epochs, batches,
   learning rate and augmentation. Hover over a field for its explanation.
   Save the settings and choose an empty/new training-output folder.
5. Click **Validate data**, then **Train**. **Cancel running action** preserves
   completed checkpoints. **Resume** uses the same output folder and settings,
   with a larger total epoch count if needed. Changing data, split, architecture
   or preprocessing requires a new run. **Fine-tune** starts a new run from a
   selected compatible model using your new annotated dataset.
6. Click **Review TIFFs** to open the validation raw, annotated and predicted
   stacks in Fiji. **Evaluate test** writes object-matching statistics at an
   intersection-over-union threshold of 0.5 plus review stacks for the reserved
   test specimens. Inspect missed, merged, split and spurious objects.
7. Click **Use model** to fill the Options dialog's 3D model folder, configuration and
   saved thresholds, then **Apply**. Run the usual counter command. Its Detection
   heading identifies the selected 3D mode, its Model field contains the 3D folder,
   and unused slice-linking fields are disabled. Counting starts with the counter's OK.

```csv
image,labels,specimen_id,split
mouse01/raw.tif,mouse01/instances.tif,mouse01,train
mouse02/raw.tif,mouse02/instances.tif,mouse02,validation
mouse03/raw.tif,mouse03/instances.tif,mouse03,test
```

The model package is the training output's `model` folder: `config.json`,
`weights_best.h5`, `thresholds.json` and `oc3d-model.json`. Copy the whole folder
to reuse/share it. It records preprocessing, voxel spacing, specimen splits,
package versions and checksums. Changed model weights/configuration are rejected
when they disagree with the saved metadata. Training records retain settings,
epoch history, checkpoint state and a replay request.

External single-channel StarDist3D model folders can be selected directly.
Provide the normalization, spacing and units used to train that model via the
3D configuration; external folders may lack this information. Multichannel,
2D and classifying models are rejected. No automatic resampling changes your
image to fit an incompatible training scale. Measurements use the original
image intensities and original calibration, never normalized training pixels.

**Create example** generates six tiny simulated annotated volumes with separate
training/validation/test specimens. It verifies the workflow; it cannot establish
accuracy on biological images.

## Scripted use

The Java counting API returns the usual objects, summary, label image and maps:

```java
OC3DSDResult result = OC3DSD.run(OC3DSD.builder(stack)
    .segmentation("stardist3d")
    .model3d(new File("my-trained-model/model"))
    .pythonConfig(new File("training-config.json"))
    .minSize(10).build());
```

Macro keys are `segmentation=stardist3d`, `model_3d=[full model folder]` and
`python_config=[full JSON configuration]`, alongside the existing channel,
probability, overlap, size, edge and output options. Omitting segmentation
keeps the existing mode. Batch mode uses the selection made in Options.
Its 3D-only `Provenance` receipts record the model checksum, normalization,
calibration, thresholds and count before/after counter filters; unused linking
columns are blank. The label image also carries its inference receipt in the
`stardist3d_provenance` property.

Training and segmentation actions share a JSON action registry packaged in
`python/oc3d_stardist3d/registry.json`. `StarDist3D.callJson(action, parametersJson,
progress)` exposes describe, example, validate, train, predict, evaluate and
demo-model download. `sc.fiji.oc3dsd.cli.StarDist3DCLI` reads one JSON request
file and uses the same managed environment; the predict action produces labels,
while the Java counting API and ImageJ macros add the shared measurements/maps.

## Build locally

Build all modules from one clean checkout with Java 21:

```bash
./mvnw -B -f build/pom.xml clean verify
```

On Windows use `mvnw.cmd` or `scripts/build.ps1`. The `runtime-core` module
contains the shared counter-family launcher source, with its original licence,
so building needs no unpublished Maven artifact or sibling checkout. Its version
is 0.1.1; the existing measurement core remains `oc3d-core:v0.1.1`.
Both cores and the Python worker are bundled privately in the one plugin JAR.
Windows worker processes use `javaw.exe` and `pythonw.exe`; progress and errors
remain available in Fiji without terminal windows.

Upstream method and training references: [StarDist](https://github.com/stardist/stardist),
[official 3D training example](https://github.com/stardist/stardist/blob/main/examples/3D/2_training.ipynb),
[Fiji StarDist plugin](https://imagej.net/plugins/stardist).
