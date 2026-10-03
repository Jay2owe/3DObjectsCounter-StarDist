# 3D Objects Counter - StarDist deploy contract

This contract controls local Fiji deployment for 3D Objects Counter - StarDist.
Bare `deploy` means a local candidate install only. An explicit upload request
publishes the tested artifact to the ImageJ update site and GitHub, with the
GitHub source release archived by the existing Zenodo integration.

<!-- deploy-required-channels: ["local-fiji", "imagej-update-site", "github-release", "zenodo"] -->

## Source and artifact identity

- Read the next version from `pom.xml` and follow `VERSIONING.md`; optional
  whole-volume StarDist3D introduces a new engine and is version `1.0.0`.
- Read the version from `pom.xml` at every deployment and record the exact
  source commit and SHA-256 of the selected jar.
- Select exactly one `target/3D_Objects_Counter_StarDist-*.jar`, excluding
  `*-sources.jar`, `*-tests.jar`, `original-*`, and non-plugin jars.

## Runtime surfaces

Deploy the selected jar to the local Fiji plugins directory:

`C:\Users\Owner\UK Dementia Research Institute Dropbox\Brancaccio Lab\Jamie\Fiji.app\plugins`

Remove only files matching `^3D_Objects_Counter_StarDist-.*\.jar$`. The target
must contain exactly one live matching jar whose SHA-256 equals the artifact.

The public update site is `3DObjectsCounter-StarDist` at
`https://sites.imagej.net/3DObjectsCounter-StarDist/`; its workflow is
`.github/workflows/fiji-update-site-upload.yml` and is publish-only.

No shared lab distribution folder is declared by this repository or the local
deployment rules. Do not invent one; add an explicit destination before using
it.

## Gates

- Check branch, version, source status, and whether Fiji is running.
- Build all release modules with Java 21:
  `.\mvnw.cmd -B -f build/pom.xml clean verify`.
- Run the unchanged-layout guard and the Python workflow checks.
- Verify managed setup and a Python action from a windowless Windows host,
  retaining progress/error pipes and observing no visible console windows.
- Run one representative Fiji check through `Analyze > 3D Objects Counter -
  StarDist`, including the documented `Install Runtime` path when dependencies
  are absent. Confirm the detector completes and the plugin reports its result.
- Keep distinct checks in one harness run rather than repeating the same action.

## Publication order

1. local Fiji candidate deployment;
2. push the tested source commit/tag and attach its JAR/checksums to a draft
   GitHub release;
3. one dry-run update-site workflow using that draft's exact tested artifact;
4. authorised ImageJ updater upload and hosted-JAR hash verification;
5. publish the GitHub draft, verify its asset hash and collect the matching
   Zenodo record.

One version, source commit and artifact hash identify all JAR channels. The
release source includes the shared launcher module so clean CI needs no local
Maven installation or sibling checkout. Keep unrelated local files uncommitted.

## Unresolved requirements

- Restart Fiji after copying; the runtime installer also requires a second Fiji
  restart after it reports success.
- Update-site credentials are workflow secrets and must never be stored here.
- The shared lab distribution destination is unresolved and remains intentionally
  omitted.
