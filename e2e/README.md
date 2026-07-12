# Packaged-runtime E2E

The release gate tests the same jars that publishing receives. It does not use Loom run tasks or
compiled `main` output.

The checked-in [release matrix](../release/release-matrix.json) is the lane inventory consumed by
Stonecutter settings, aggregate Gradle tasks, publication, and E2E. It currently defines 10 release
files and 10 matching runtime rows. Every artifact advertises and launches on exactly one Minecraft
version; matrix validation fails if any consumer-facing row or task identity disagrees.

## Local setup

Build and stage the production jars plus separate remapped automation mods:

```bash
./gradlew --no-parallel clean buildAllLanes buildAllE2EHarnesses
python scripts/release/verify_release.py --stage build/release
python -m pip install --only-binary=:all: --requirement e2e/requirements.txt
```

`buildAllLanes` also runs the conventional loader-independent JUnit suite on the stable common
version selected by `unit_test_version` in the release matrix. Run it directly with
`./gradlew testStableLane` while developing boundary logic.

List the resolved matrix without launching Minecraft:

```bash
python e2e/orchestrator.py --list --artifacts-manifest build/release/artifacts.json
```

Run one packaged smoke under Linux/Xvfb:

```bash
xvfb-run -a python e2e/orchestrator.py \
  --packaged \
  --artifact-node fabric-1.21.11 \
  --runtime-version 1.21.11 \
  --scenarios phase0-smoke
```

Java 17, 21, and 25 are selected from `QUICKSKIN_JAVA_<major>` or
`JAVA_HOME_<major>_X64`. Each execution creates an isolated server and client game directory below
`e2e-out/profiles/`, installs the manifest-bound Quick Skin jar by SHA-256, and adds only the
loader-specific dependencies and separate E2E harness.

## Fail-closed contract

A row fails for a missing or changed package, missing/invalid report, unexpected step, failed or
corrupt/undersized/effectively blank screenshot, a visually unchanged apply/animation pair,
compatibility/error screen, crash report, or fatal mixin/access-widener/linkage/`@ExpectPlatform`
log evidence. Pixel checks use broad entropy/color and pairwise-change invariants rather than golden
images, so GPU and Minecraft-version rendering differences are allowed. Every result records the literal fields
`artifact_node`, `runtime_version`, `loader`, `scenario`, `jar_sha256`, and `port`. All loader and
Architectury dependencies are locked directly in the matrix for that exact runtime.

Pull requests run smoke, live propagation, and full behavior on the matrix's three cross-era loader
anchors. A release runs all four scenarios for every lane against the manifest-bound bytes from the
exact release commit; the weekly workflow remains an independent recurring soak of the same contract.
