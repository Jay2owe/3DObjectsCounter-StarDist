# Changelog

All notable changes to 3D Objects Counter - StarDist are recorded here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and
versioning follows `VERSIONING.md`.

## [Unreleased] — 0.1.0

First implementation. Carved out of FLASH's `flash.pipeline.stardist` package
and dressed in 3D Objects Counter+'s dialog and output surface.

### Added

- **Detection and linking.** StarDist runs on every Z-slice through TrackMate's
  StarDist detector; the per-slice detections are linked through Z by the
  SparseLAP tracker, and each linked chain becomes one 3D object.
- **3D measurement from the label image** — volume, surface area, sphericity,
  compactness, elongation, maximum Feret diameter, intensity statistics,
  centroid, centre of mass and bounding box, using 3D Objects Counter+'s feature
  definitions so the columns mean the same thing in both plugins.
- **Filters** — minimum and maximum bounds on size, volume, sphericity,
  compactness, elongation, surface area, intensity and Feret diameter, plus
  detector-level area, quality and intensity filters that run before the
  measurement pass.
- **Maps** — object, surface, centroid and centre-of-mass maps, and the 3D label
  image itself as a first-class output.
- **Batch over a folder, on both discovery axes at once.** A recursive scan
  decides which files are analysed; a filename regular expression with a capture
  group decides which results are aggregated together. Results are written per
  image, per source folder and per group, with a manifest recording every
  parameter and the calibration in force.
- **Group preview before a batch run**, because each image costs minutes of
  detection time.
- **Dependency doctor** — a first-run check naming exactly which of the four
  required update sites is missing, plus structural validation of a custom model
  `.zip` before TensorFlow can produce an opaque error, and clearing of a stale
  TensorFlow crash marker left by an earlier Fiji session.
- **Macro recording** for both commands, with `hide_display` for headless use,
  and a public Java API (`OC3DSD` / `OC3DSDParameters` / `OC3DSDResult`) that
  opens no dialogs, shows no windows and writes no files.

### Changed

- **Shared internals now come from `oc3d-core`** rather than from this
  repository's own copies. No number a user sees changes: the adoption is gated
  on an equivalence harness that compares the statistics table, the summary, the
  label partition, all four maps and their number overlays, and the macro
  round-trip against goldens captured from the pre-migration build, with zero
  tolerance on every integer and intensity column. The module is bundled into
  the jar, so nothing extra needs installing.
- **The statistics table gains a `Median` column and matches 3D Objects
  Counter+'s column order.** This is a **schema change** and the one place where
  the "nothing a user sees moves" rule was deliberately set aside.

  The table now carries 27 columns instead of 26: `Median` sits between `StdDev`
  and `Min`, and the `Morph_*` block moves from before `BX` to after `Label`.
  That is the order 3D Objects Counter+ has always shipped, verified against
  that plugin's own goldens rather than assumed, and adopting it means the family
  has one column order instead of two.

  **No value changed.** Every column that existed before still exists, and every
  value under it is bit-identical — 163,678 of them across 557 runs, checked
  column-by-column by name against the pre-migration goldens, which are kept in
  the repository for exactly that purpose. The only column added is `Median`.

  What this affects: a script that reads these tables **by column position**
  needs updating. One that reads **by column name** keeps working, and will
  simply see one extra column. `Median` is `NaN` unless an intensity image is
  measured, as the other intensity columns already are.
- **The "unknown macro filter feature" message lists the valid feature names
  alphabetically**, where it previously listed them in an internal declaration
  order. Only the ordering of that one list changed — the same names are
  accepted, a valid macro parses to exactly the same parameters, and the same
  exception is thrown for an invalid one.
- **The five tuning properties are renamed into `oc3d-core`'s namespace.** They
  come with the shared module rather than being declared here, so they now read
  `sc.fiji.oc3d.core.maxDenseLabel`, `.maxOverlayLabels`, `.overlaySkipped`,
  `.overlaySkippedReason` and `.optionalMapMemoryReserveBytes` — previously
  `sc.fiji.oc3dsd.maxDenseLabelMeasurementsLabel` and `sc.fiji.oc3dsd.*` for the
  rest. The first four are internal escape hatches; the last is a system
  property overriding how much free heap an optional map must leave behind, and
  `overlaySkipped` is set on a map image where a macro can read it.

  None of them was ever documented outside the source, so this is noted for
  completeness rather than as a break anyone is likely to feel. The names are
  now the same in every plugin in the family, which is the point of sharing the
  module, and they survive being bundled into the jar unchanged — verified
  against the built artifact, because relocating a bundled module silently
  rewrites exactly this kind of string.
- **Renumbering clears a pixel that is not a valid label**, rather than leaving
  it in place. A pixel that is non-zero but negative, non-finite or fractional
  was never counted as an object; it is now also cleared from the renumbered
  label image, so a saved label image contains nothing but `0` and `1..N`.
  **No image this plugin produces can contain such a pixel** — detection builds
  the label image as unsigned 16-bit — so this is reachable only by calling the
  engine classes directly on a float label image of your own.

### Fixed

- **A batch re-run no longer consumes its own previous output.** Leaving the
  output folder blank puts the results inside the input folder — the obvious
  thing to do, and what the dialog offers. Discovery is recursive by default and
  label images are saved by default, so the first run wrote `_labels.tif` files
  into the tree it had just scanned. Running the same folder a second time then
  measured those label images as though they were new inputs: the object count
  grew, every aggregate and summary gained rows, and nothing indicated it,
  because a label image is a valid input that produces entirely plausible
  numbers. It compounded on each further run. The plugin's own output folder is
  now excluded from discovery, in the run and in the group preview alike.

  Found by reconciling this plugin's discovery against `oc3d-core`'s, which
  carries the exclusion. If you have re-run a batch into its own input folder,
  the results from the second and later runs are wrong and should be regenerated.

### Fixed — carried over from FLASH, where the pipeline's usage pattern hid them

These are corrections to the extracted source, not to a previous release of this
plugin. FLASH runs one image at a time under a human's eye; a batch counter does
not, and each of these produced a plausible-looking wrong number.

- **Single-slice objects are no longer silently deleted.** FLASH removed every
  detection the tracker could not link and exported tracks only, so any object
  present on exactly one Z-slice vanished without warning — systematically
  dropping thin objects, objects clipped by the ends of the stack, and most
  objects in a coarsely sampled stack. Single-slice objects are now recovered as
  real objects, controlled by `Min. slices per object` (default 1), and counted
  and reported either way.
- **Z and T are no longer collapsed into one axis.** FLASH set the frame count to
  `z * t`, which let the tracker link the last Z-slice of one timepoint to the
  first Z-slice of the next and merge them into a single object. Each timepoint
  is now detected and linked independently and the frames are reassembled with
  Z and T intact.
- **Object measurements come from the label image, not from the detector.**
  FLASH reported the mean of the detector's per-slice spot features, where
  "area" was the mean of `pi*r^2` over an object's slices — not a volume, not a
  cross-section, and not comparable between objects of different heights. Those
  values are retained as clearly named diagnostics
  (`Detector_Area_Mean`, `Detector_Quality_Mean`, `Detector_Intensity_Mean`)
  alongside real measured morphometry.
- **Objects are numbered 1..N.** FLASH used TrackMate's track ID plus one, which
  is sparse, holed after filtering, and not stable between runs of the same
  image. Numbering is now contiguous and deterministic — first slice of
  appearance, then centroid Y, then centroid X — with the detector's own label
  preserved in `Detector_Track_ID`.
- **The linking distance states its units.** It is read by TrackMate in
  calibrated units, so the same number meant five pixels on an uncalibrated
  stack and fifty at 0.1 µm/pixel — and over-linking merges neighbouring
  objects, the exact failure this plugin exists to prevent. The dialog now shows
  the pixel equivalent, says so when an image is uncalibrated, and the log and
  batch manifest record both readings.

### Changed relative to the build plan

- **`mcib3d-core` is not a dependency after all.** The measurement layer turned
  out to be implementable with `ij` alone — 3D Objects Counter+'s accumulator
  already computes the Lindblad (2005) corrected surface itself rather than
  calling mcib3d. Measurement is therefore `ij`-only, and `3D_Objects_Counter`
  is not a dependency either: this plugin never thresholds and never runs
  connected components, so the native counter has nothing to contribute.
- **Sphericity and compactness are computed here.** 3D Objects Counter+ leaves
  those two columns to the native counter and writes NaN from its own
  accumulator. With no native counter in this path they are computed from the
  corrected surface that was already being accumulated, to mcib3d's definition.

  **Verified against mcib3d itself.** Two plugins in the same family filling the
  same two columns by two different code paths — 3D Objects Counter+ through
  mcib3d's `MeasureCompactness`, this plugin through its own Lindblad (2005)
  weighted-configuration surface in `ij` — is exactly the arrangement in which
  they can quietly drift apart and report different shapes for the same object.
  The two now agree to within 1e-9 relative across seven shapes chosen to hit
  every branch of the weight table (ball, cube, one-voxel slab, one-voxel rod,
  hollow shell with an interior cavity, L-shape), and both decline to report a
  shape for an isolated voxel. mcib3d is a test-scoped dependency for this check
  only; it is not linked into the plugin and the measurement path is still
  `ij`-only.

### Known limitations

- Detection is 2D per slice. This is not a 3D StarDist model, and no such model
  exists for Fiji. Objects that are strongly concave in Z, or sampled with a
  large Z-step, will link poorly. The `Slices` column and the single-slice count
  in the summary exist so those cases are visible.
- Four update sites are required — StarDist, CSBDeep, TensorFlow and
  TrackMate-StarDist — including roughly 166 MB of native TensorFlow. This
  cannot be removed; the learned detector is the plugin.
- Accuracy against manual 3D ground truth has not been measured. That benchmark
  is planned before any preprint.
