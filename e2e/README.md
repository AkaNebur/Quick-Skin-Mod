# Packaged-runtime E2E

The release gate tests the same jars that publishing receives. It does not use Loom run tasks or
compiled `main` output.

The checked-in [release matrix](../release/release-matrix.json) defines 10 release files and 10
matching smoke rows. Every artifact advertises and launches on exactly one Minecraft version.

## Local setup

Build and stage the production jars plus separate remapped automation mods:

```bash
./gradlew --no-parallel clean buildAllLanes buildAllE2EHarnesses
python scripts/release/verify_release.py --stage build/release
python -m pip install --requirement e2e/requirements.txt
```

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
undersized screenshot, compatibility/error screen, crash report, or fatal mixin/access-widener/
linkage/`@ExpectPlatform` log evidence. Every result records the literal fields
`artifact_node`, `runtime_version`, `loader`, `scenario`, `jar_sha256`, and `port`. All loader and
Architectury dependencies are locked directly in the matrix for that exact runtime.
