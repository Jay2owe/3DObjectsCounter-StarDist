# 3D Objects Counter - StarDist

[![CI](https://github.com/Jay2owe/3DObjectsCounter-StarDist/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Jay2owe/3DObjectsCounter-StarDist/actions/workflows/ci.yml)
[![License: GPL v3+](https://img.shields.io/badge/license-GPL--3.0--or--later-blue.svg)](LICENSE)
[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.21933250.svg)](https://doi.org/10.5281/zenodo.21933250)

A Fiji/ImageJ plugin that counts and measures 3D objects in a Z-stack using StarDist detection
linked through Z with TrackMate.

StarDist runs on each slice of the stack; TrackMate's LAP tracker links the per-slice detections
through Z so that each linked chain becomes one 3D object; every object is then measured — volume,
surface area, sphericity, compactness, elongation, maximum Feret diameter, intensity statistics,
centroid, centre of mass and bounding box. The point is objects that touch: a threshold merges them,
a learned detector separates them.

The plugin is a **producer** of label images. Its 3D label image output can be handed to any plugin
that consumes label images, so segmentation and downstream analysis stay decoupled.

## How the 3D objects are built

StarDist is a 2D detector. This plugin does **not** use a 3D StarDist model — none exists for Fiji,
and the [StarDist plugin documentation](https://imagej.net/plugins/stardist) says so directly — and
it does not perform 3D segmentation. It detects in 2D on every slice and links across Z, the
approach documented on TrackMate's
[StarDist detector page](https://imagej.net/plugins/trackmate/detectors/trackmate-stardist).

That works well when an object overlaps itself between consecutive slices. It works less well for
objects that are strongly concave in Z, for stacks with a large Z-step, and for objects that touch
in Z as well as in XY. The plugin reports the number of slices each object spans, and how many
objects were found on a single slice only, so those cases are visible in the output rather than
hidden inside it.

## Features

- StarDist detection per Z-slice with a bundled model or your own `.zip`.
- TrackMate LAP linking across Z, with linking distance, gap-closing distance and slice gap exposed.
- Per-object 3D measurements, in the same columns and to the same definitions as
  [3D Objects Counter+](https://github.com/Jay2owe/3DObjectsCounterPlus). Intensity statistics are
  read from the analysed channel unless you redirect them to another image.
- Minimum and maximum object size, and exclusion of objects touching the image edges. Shape is
  measured and reported, not filtered on — see [Filtering](#filtering).
- Object maps show complete linked shapes on every occupied Z slice, with contrasting numbered
  labels; raw map pixels retain their numeric object IDs. Surface, centroid and centre-of-mass maps
  and the 3D label image are also available.
- Live preview of detection on the displayed slice before running the whole stack.
- Folder batch with recursive search **and** regex grouping: recursion decides which files are
  analysed, the capture group decides which results are aggregated together. Both a per-folder and a
  per-group summary are written, alongside a manifest recording every parameter.
- Macro-recordable, with `hide_display` for headless and scripted use.
- A public Java API that opens no dialogs, shows no windows and writes no files.
- A one-click first-run installer for the exact StarDist, TrackMate and TensorFlow versions tested
  with the plugin, plus custom model `.zip` validation before TensorFlow sees it.

## Installation

**GitHub release.** Download `3D_Objects_Counter_StarDist-0.1.0.jar` from the
[latest release](https://github.com/Jay2owe/3DObjectsCounter-StarDist/releases/latest), copy it into
Fiji's `plugins/` folder, and restart Fiji. Run `Analyze > 3D Objects Counter - StarDist`. If the
detector runtime is absent, press
**Install Runtime**. The plugin downloads the exact known-working StarDist, TrackMate and
TensorFlow JARs directly (up to about 159 MB), verifies every download, and preserves conflicting
versions under a dated `.disabled-*` name. When it reports success, restart Fiji yourself; the
plugin does not restart Fiji automatically. You do not need to configure the StarDist, CSBDeep,
TrackMate-StarDist or TensorFlow update sites.

**Update site.** In Fiji, choose `Help > Update... > Manage Update Sites`, add
`https://sites.imagej.net/3DObjectsCounter-StarDist/`, enable it, apply changes, and restart Fiji.
The GitHub release JAR above remains available for manual installation.

**From source.** Build the plugin as described below, copy
`target/3D_Objects_Counter_StarDist-0.1.0.jar` into Fiji's `plugins/` folder, and restart Fiji.

## Building

The project requires JDK 8 or newer. A fresh clone includes platform launchers that bootstrap the
pinned Maven version, resolve the released `oc3d-core` module, run the behavioural and packaging
checks, and shade a private copy of core into the plugin JAR:

```bash
./mvnw -B clean verify
```

On Windows use `mvnw.cmd -B clean verify`. The deployable artifact is
`target/3D_Objects_Counter_StarDist-0.1.0.jar`; `-sources`, `-tests` and `original-*` JARs are not
Fiji plugins.

## Use

Open a Z-stack and run `Analyze > 3D Objects Counter - StarDist`.

Set the channel to detect on, choose a model, and set **Probability** and **Overlap**. Leave
**Redirect intensities from** on `None` to measure `IntDen`, `Mean`, `StdDev`, `Median`, `Min` and
`Max` on the channel you are detecting in; choose another open image only when the intensities you
want live somewhere else, in which case it must match the stack in width, height and slice count.
Press
**Run Preview** to see detections on the current slice. Under **Linking**, set **Linking max
distance** — how far an object may move between consecutive slices and still be the same object, in
calibrated units — plus the gap-closing distance, maximum slice gap and minimum slices per object.
Then set the size bounds and choose the outputs. **Preview** runs the count and keeps the dialog
open;
**OK** runs it and closes.

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

`OC3DSD.run` opens no dialogs, shows no windows, writes no files and needs no active ImageJ window.
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

- Malcolm, J. (2026). *3D Objects Counter - StarDist* (Version 0.1.0)
  [Computer software]. Zenodo. https://doi.org/10.5281/zenodo.21933251
- Schmidt, Weigert, Broaddus & Myers (2018) *Cell Detection with Star-convex Polygons*. MICCAI.
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
