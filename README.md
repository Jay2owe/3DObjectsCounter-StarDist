# 3D Objects Counter - StarDist

[![CI](https://github.com/Jay2owe/3DObjectsCounter-StarDist/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Jay2owe/3DObjectsCounter-StarDist/actions/workflows/ci.yml)
[![License: GPL v3+](https://img.shields.io/badge/license-GPL--3.0--or--later-blue.svg)](LICENSE)
[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.21933250.svg)](https://doi.org/10.5281/zenodo.21933250)

A Fiji/ImageJ plugin that counts and measures 3D objects in a Z-stack using StarDist detection
linked through Z with TrackMate.

The existing slice-based mode remains the default. An optional **StarDist3D -
whole volume** mode now predicts 3D objects using automatically managed Python,
with controls to train, resume, evaluate and load models for your own annotated
stacks. Switching displays a model-compatibility and validation warning.
See [the StarDist3D guide](STARDIST3D.md) for setup, annotation format and training.

StarDist runs on each slice of the stack; TrackMate's LAP tracker links the per-slice detections
through Z so that each linked chain becomes one 3D object; every object is then measured — volume,
surface area, sphericity, compactness, elongation, maximum Feret diameter, intensity statistics,
centroid, centre of mass and bounding box. The point is objects that touch: a threshold merges them,
a learned detector separates them.

The plugin is a **producer** of label images. Its 3D label image output can be handed to any plugin
that consumes label images, so segmentation and downstream analysis stay decoupled.

## How the 3D objects are built

The default mode uses the standard Fiji StarDist 2D detector. It detects in 2D on every slice and links across Z, the
approach documented on TrackMate's
[StarDist detector page](https://imagej.net/plugins/trackmate/detectors/trackmate-stardist).

That works well when an object overlaps itself between consecutive slices. It works less well for
objects that are strongly concave in Z, for stacks with a large Z-step, and for objects that touch
in Z as well as in XY. The plugin reports the number of slices each object spans, and how many
objects were found on a single slice only, so those cases are visible in the output rather than
hidden inside it.

## Features

- StarDist detection per Z-slice with named fluorescence-model choices and an import option for your own `.zip`.
- TrackMate LAP linking across Z, with linking distance, gap-closing distance and slice gap exposed.
- Per-object 3D measurements, in the same columns and to the same definitions as
  [3D Objects Counter+](https://github.com/Jay2owe/3DObjectsCounterPlus). Intensity statistics are
  read from the analysed channel unless you redirect them to another image.
- Minimum and maximum object size, and exclusion of objects touching the image edges. Shape is
  measured and reported, not filtered on — see [Filtering](#filtering).
- Object maps show complete linked shapes on every occupied Z slice, with contrasting numbered
  labels; raw map pixels retain their numeric object IDs. Surface, centroid and centre-of-mass maps
  and the 3D label image are also available.
- Parameter tuning with ranges or individual values, using FLASH's comparison grid with the original image and linked Z scrolling.
- Folder batch with recursive search **and** regex grouping: recursion decides which files are
  analysed, the capture group decides which results are aggregated together. Both a per-folder and a
  per-group summary are written, alongside a manifest recording every parameter.
- Macro-recordable, with `hide_display` for headless and scripted use.
- A public Java API that opens no dialogs and shows no windows. The default mode writes no files; optional 3D mode uses temporary exchange files and a managed environment cache.
- A one-click first-run installer for the exact StarDist, TrackMate and TensorFlow versions tested
  with the plugin, plus custom model `.zip` validation before TensorFlow sees it.

## Installation

**GitHub release.** Download `3D_Objects_Counter_StarDist-2.0.0.jar` from the
[latest release](https://github.com/Jay2owe/3DObjectsCounter-StarDist/releases/latest), copy it into
Fiji's `plugins/` folder, and restart Fiji. Run `Analyze > 3D Objects Counter - StarDist`. If the
detector runtime is absent, press
**Install Runtime**. The plugin downloads the exact known-working StarDist, TrackMate and
TensorFlow JARs directly (up to about 159 MB), verifies every download, and preserves conflicting
versions under a dated `.disabled-*` name. When it reports success, restart Fiji yourself; the
plugin does not restart Fiji automatically. You do not need to configure the StarDist, CSBDeep,
TrackMate-StarDist or TensorFlow update sites.

**Windows with Java 11.** The older ImageJ TensorFlow loader used by the default
2D mode can fail even when its runtime JARs are installed. A verified workaround
is to extract `org/tensorflow/native/windows-x86_64/tensorflow_jni.dll` from the
pinned `libtensorflow_jni-1.15.0.jar` into Fiji's `lib/win64/`, then start Fiji
with that folder on `PATH` before Java starts. Changing `java.library.path`
after startup does not repair the cached native-library search path. This
workaround is separate from the automatic Python setup for optional 3D mode;
the plugin installer does not currently apply it automatically.

**Update site.** In Fiji, choose `Help > Update... > Manage Update Sites`, add
`https://sites.imagej.net/3DObjectsCounter-StarDist/`, enable it, apply changes, and restart Fiji.
The GitHub release JAR above remains available for manual installation.

**From source.** Build the plugin as described below, copy
`target/3D_Objects_Counter_StarDist-2.0.0.jar` into Fiji's `plugins/` folder, and restart Fiji.

## Building

The build requires JDK 21. The default counting mode retains its Java 8 compatibility; optional 3D mode requires Fiji on Java 11 or newer and a Java 21 background worker. A fresh clone includes platform launchers that bootstrap the
pinned Maven version, resolve the released `oc3d-core` module, run the behavioural and packaging
checks, and shade a private copy of core into the plugin JAR:

```bash
./mvnw -B -f build/pom.xml clean verify
```

On Windows use `mvnw.cmd -B -f build/pom.xml clean verify`. The deployable artifact is
`target/3D_Objects_Counter_StarDist-2.0.0.jar`; `-sources`, `-tests` and `original-*` JARs are not
Fiji plugins.

## Use

Open a Z-stack and run `Analyze > 3D Objects Counter - StarDist`.

Set the channel to detect on, choose a model, and set **Probability** and **Overlap**.
The **Model** dropdown defaults to **Fluorescence nuclei (default)**, the existing
versatile-fluorescence model. **Fluorescence nuclei (DSB 2018)** selects the original
nuclei benchmark model. Choose **Import model...** to select and validate your own
StarDist 2D `.zip`; imported filenames remain available in this dropdown and the
batch dialog. Keep imported files in their original location. Existing macros
still accept `model=versatile_fluo` or `model=[path/to/model.zip]`.

Leave **Redirect intensities from** on `None` to measure `IntDen`, `Mean`, `StdDev`, `Median`, `Min` and
`Max` on the channel you are detecting in; choose another open image only when the intensities you
want live somewhere else, in which case it must match the stack in width, height and slice count.
Under **Linking**, set **Linking max
distance** — how far an object may move between consecutive slices and still be the same object, in
calibrated units — plus the gap-closing distance, maximum slice gap and minimum slices per object.
Then set the size bounds and choose the outputs. **OK** runs the count.

**Tune parameters...**, beside **OK**, opens a parameter picker. Tick the settings
to vary and choose **Range** (start, end, step) or **Individual values** (for example,
`0.3, 0.5, 0.7`). Every selected value is combined with every other selected
parameter, with a limit of 64 previews. Unselected settings keep their current values.

The grid places the original image alongside object previews. Its shared Z slider
scrolls every tile together; overlay, zoom and brightness controls follow FLASH.
Previews use the chosen channel and current timepoint across all Z slices. Draw a
rectangular selection first to compare a smaller area. Only successful tiles can
be picked. Click a tile and **Pick selected**, or its **Pick** pill, to return to the
main dialog with those parameters. Check them and press **OK** to run the full image.
Closing the grid keeps your previous settings. Whole-volume 3D mode offers the
detection and size parameters it uses; slice-linking parameters apply only to 2D mode.

`Analyze > 3D Objects Counter - StarDist Batch` runs a folder. Choose the root, whether to include
subfolders, and optionally a filename regular expression whose capture group names the group each
file belongs to. The groups are shown for confirmation before the run starts.

## Filtering

Objects are selected on **size** and on whether they touch an image edge. There is deliberately no
filtering on shape.

Sphericity, compactness, elongation and maximum Feret diameter are still measured and still appear
in the results table, so you can sort on them, plot them, or filter the exported table however you
like. What the plugin will not do is silently drop objects on your behalf using them, because the
count is the headline number and a shape threshold buried in a dialog is an easy way to change it
without noticing.

If you paste a macro from 3D Objects Counter+ that carries a shape predicate, it parses and is
ignored rather than failing.

## Macro

```
run("3D Objects Counter - StarDist",
    "channel=1 model=versatile_fluo probability=0.5 overlap=0.4 " +
    "linking_distance=5.0 gap_distance=5.0 slice_gap=1 min_slices=1 " +
    "min=10 " +
    "exclude_edges save_labels hide_summary");
```

Option names follow 3D Objects Counter+ wherever the option means the same thing. Omitting
`redirect=[title]`, as above, measures intensities on the analysed channel; it does not switch
intensity measurement off.

## Java API

```java
OC3DSDParameters params = OC3DSDParameters.builder(stack)
        .channel(1)
        .probability(0.5)
        .overlap(0.4)
        .linkingDistance(5.0)
        .minSlices(1)
        .build();

OC3DSDResult result = OC3DSD.run(params);
ResultsTable objects = result.getObjects();
ImagePlus labels = result.getLabelImage();
```

`OC3DSD.run` opens no dialogs, shows no windows and needs no active ImageJ window. The default mode writes no files; optional 3D mode uses temporary exchange files and a managed environment cache.
TensorFlow inference is process-global, so concurrent calls are serialised internally; parallelising
across images gains nothing.

## Related

[3D Objects Counter+](https://github.com/Jay2owe/3DObjectsCounterPlus) counts and measures 3D
objects from a threshold, in the same dialog layout with the same column names and macro options.
It is faster and needs no extra update sites, and it is the better choice when objects are well
separated. The two plugins segment in completely different ways, so their counts on the same image
are not expected to agree — use whichever matches the data, not both as a cross-check.

## Citing

Please cite this plugin and the methods it builds on:

- Malcolm, J. (2026). *3D Objects Counter - StarDist* (Version 2.0.0)
  [Computer software]. [Release](https://github.com/Jay2owe/3DObjectsCounter-StarDist/releases/tag/v2.0.0).
- Schmidt, Weigert, Broaddus & Myers (2018) *Cell Detection with Star-convex Polygons*. MICCAI.
- Weigert, Schmidt, Haase, Sugawara & Myers (2020) *Star-convex Polyhedra for 3D
  Object Detection and Segmentation in Microscopy*. WACV.
  https://doi.org/10.1109/WACV45572.2020.9093435
- Tinevez et al. (2017) *TrackMate: An open and extensible platform for single-particle tracking*.
  Methods.
- Ershov et al. (2022) *TrackMate 7: integrating state-of-the-art segmentation algorithms into
  tracking pipelines*. Nature Methods.
- Bolte & Cordelières (2006) *A guided tour into subcellular colocalization analysis in light
  microscopy*. Journal of Microscopy — for the object measurement definitions.

`CITATION.cff` in this repository carries machine-readable citation metadata.

## Licence

**The plugin you download and run is GPL-3.0-or-later** (`LICENSE`), because it
calls directly into TrackMate and TrackMate-StarDist and cannot run without them.

**The original source in this repository is BSD-3-Clause**
(`LICENSE.BSD-3-Clause`), and stays that way, so it remains reusable under
permissive terms by anyone who does not want the GPL dependencies.

`LICENSING.md` explains why there are two and which applies to you.
