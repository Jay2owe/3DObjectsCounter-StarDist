# Licensing

Short version: **the plugin you download and run is GPL-3.0-or-later.** The
source Jamie Malcolm wrote is BSD-3-Clause, and stays that way.

## The two licences and why there are two

| What | Licence | File |
| --- | --- | --- |
| Original source in this repository | BSD-3-Clause | [LICENSE.BSD-3-Clause](LICENSE.BSD-3-Clause) |
| The combined, distributed plugin | GPL-3.0-or-later | [LICENSE](LICENSE) |

3D Objects Counter - StarDist is not a standalone program. It is a Fiji/ImageJ
plugin that calls directly into GPLv3+ libraries and cannot run without them:

- **[TrackMate](https://github.com/trackmate-sc/TrackMate)** (`sc.fiji:TrackMate`)
  — Jean-Yves Tinevez and contributors. GPL v3 or later.
- **[TrackMate-StarDist](https://github.com/trackmate-sc/TrackMate-StarDist)**
  (`sc.fiji:TrackMate-StarDist`) — GPL v3 or later.

Note that `org.framagit.mcib3d:mcib3d-core` and `sc.fiji:3D_Objects_Counter`
are deliberately *not* dependencies of this plugin — measurement is implemented
directly against `ij` — so they are not part of the linked set here. TrackMate
alone is enough to make the combined work GPL.

Those dependencies are declared with Maven `provided` scope, so they are not
bundled into this plugin's jar. On first use the runtime installer can download
the tested versions from their public Maven repositories into Fiji. That is a
packaging and installation detail. It does not change the legal position: the
compiled plugin calls their APIs directly, is useless without them, and is
distributed to be combined with them. Under the GPL the resulting combined work
must be offered under GPL-3.0-or-later, so that is what the project declares.

## Why the source stays BSD-3-Clause

BSD-3-Clause is GPL-compatible in the inbound direction: BSD-licensed code can
be combined into a GPL work without either licence being violated. Keeping the
original source BSD-3-Clause therefore costs nothing legally, and it means the
code remains reusable by anyone who wants it under permissive terms — including
in projects that could not accept GPL code.

Concretely:

- If you receive the **plugin** (the jar, the update site, a release), your
  rights to the combined work are the GPL-3.0-or-later rights in
  [LICENSE](LICENSE).
- If you take **only the original source files** from this repository and use
  them without the GPL dependencies, you may do so under the BSD-3-Clause terms
  in [LICENSE.BSD-3-Clause](LICENSE.BSD-3-Clause).

`pom.xml` reflects both facts: the `<licenses>` element declares
GPL-3.0-or-later because that governs the distributed artifact, while the
`license.licenseName` property stays `bsd_3` because it controls the per-file
headers stamped on this repository's own source.

## Other components

The separately installed StarDist, CSBDeep, TensorFlow and protobuf components
carry their own licences. They are not bundled in this repository's release JAR;
the first-run installer retrieves the pinned artifacts from their public Maven
repositories and installs them into the user's Fiji.

## If you would prefer a single licence

Relicensing the original source to GPL-3.0-or-later as well is a one-line
change and would make the whole thing uniform. That is a deliberate choice
rather than a default, so it has not been made here. Nothing above prevents it
later.
