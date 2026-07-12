# Packaged-runtime E2E

The release gate tests the same jars that publishing receives. It does not use Loom run tasks or
compiled `main` output.

The checked-in [release matrix](../release/release-matrix.json) separates the artifact node from the
Minecraft runtime version. Its 10 release files resolve to 14 smoke rows because each 26.1 jar is
launched on 26.1, 26.1.1, and 26.1.2 for its loader.

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
`artifact_node`, `runtime_version`, `loader`, `scenario`, `jar_sha256`, and `port`.

NeoForge 26.1 and 26.1.1 cannot boot with stock Architectury 20.x. The pooled marketplace file must
therefore require one maintained, published compatibility artifact. All three pooled rows, including
26.1.2, install those same exact bytes through `NEOFORGE_26_1_ARCHITECTURY_URL` and
`NEOFORGE_26_1_ARCHITECTURY_SHA256`; publishing additionally requires its Modrinth and CurseForge
project IDs. Missing inputs block scheduled coverage and release instead of substituting the
development byte patch.
