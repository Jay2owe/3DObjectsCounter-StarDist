# 3D Objects Counter - StarDist

<!-- badges: GitHub Actions build, JitPack, DOI, licence -->

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
  [3D Objects Counter+](https://github.com/Jay2owe/3DObjectsCounterPlus).
- Minimum and maximum filters on size, volume, sphericity, compactness, elongation, surface area,
  intensity and Feret diameter.
- Object, surface, centroid and centre-of-mass maps with numbered labels, plus the 3D label image.
- Live preview of detection on the displayed slice before running the whole stack.
- Folder batch with recursive search **and** regex grouping: recursion decides which files are
  analysed, the capture group decides which results are aggregated together. Both a per-folder and a
  per-group summary are written, alongside a manifest recording every parameter.
- Macro-recordable, with `hide_display` for headless and scripted use.
- A public Java API that opens no dialogs, shows no windows and writes no files.
- A first-run dependency check that names any missing update site in plain language, and validates a
  custom model `.zip` before TensorFlow sees it.

## Installation

**Update site.** In Fiji, `Help > Update... > Manage update sites`, then enable four sites:

| Site | Why |
|---|---|
| `3DObjectsCounter-StarDist` | this plugin — `https://sites.imagej.net/3DObjectsCounter-StarDist/` |
| `StarDist` | the detector |
| `CSBDeep` | StarDist's runtime |
| `TrackMate-StarDist` | the bridge between them |

TrackMate itself is part of the Fiji core and needs nothing enabled. The StarDist and CSBDeep sites
bring TensorFlow, roughly 166 MB. Apply changes, restart, then run
`Analyze > 3D Objects Counter - StarDist`.

**Manual.** Build or download `3D_Objects_Counter_StarDist-0.1.0.jar`, copy it into Fiji's
`plugins/` folder and restart. The four update sites are still required for the detector chain.

## Use

Open a Z-stack and run `Analyze > 3D Objects Counter - StarDist`.

Set the channel to detect on, choose a model, and set **Probability** and **Overlap**. Press
**Run Preview** to see detections on the current slice. Under **Linking**, set **Linking max
distance** — how far an object may move between consecutive slices and still be the same object, in
calibrated units — plus the gap-closing distance, maximum slice gap and minimum slices per object.
Then set the filters and choose the outputs. **Preview** runs the count and keeps the dialog open;
**OK** runs it and closes.

`Analyze > 3D Objects Counter - StarDist Batch` runs a folder. Choose the root, whether to include
subfolders, and optionally a filename regular expression whose capture group names the group each
file belongs to. The groups are shown for confirmation before the run starts.

## Macro

```
run("3D Objects Counter - StarDist",
    "channel=1 model=versatile_fluo probability=0.5 overlap=0.4 " +
    "linking_distance=5.0 gap_distance=5.0 slice_gap=1 min_slices=1 " +
    "min=10 filter=sphericity min=0.3 max=1.0 " +
    "exclude_edges save_labels hide_summary");
```

The full option table is in the wiki page. Option names follow 3D Objects Counter+ wherever the
option means the same thing.

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

- Schmidt, Weigert, Broaddus & Myers (2018) *Cell Detection with Star-convex Polygons*. MICCAI.
- Tinevez et al. (2017) *TrackMate: An open and extensible platform for single-particle tracking*.
  Methods.
- Ershov et al. (2022) *TrackMate 7: integrating state-of-the-art segmentation algorithms into
  tracking pipelines*. Nature Methods.
- Bolte & Cordelières (2006) *A guided tour into subcellular colocalization analysis in light
  microscopy*. Journal of Microscopy — for the object measurement definitions.

`CITATION.cff` in this repository carries the plugin's own DOI.

## Licence

**The plugin you download and run is GPL-3.0-or-later** (`LICENSE`), because it
calls directly into TrackMate and TrackMate-StarDist and cannot run without them.

**The original source in this repository is BSD-3-Clause**
(`LICENSE.BSD-3-Clause`), and stays that way, so it remains reusable under
permissive terms by anyone who does not want the GPL dependencies.

`LICENSING.md` explains why there are two and which applies to you.
