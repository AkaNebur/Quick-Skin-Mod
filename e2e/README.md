# Packaged-runtime E2E

The release gate tests the same jars that publishing receives. It does not use Loom run tasks or
compiled `main` output.

The checked-in [release matrix](../release/release-matrix.json) is the lane inventory consumed by
Stonecutter settings, aggregate Gradle tasks, publication, and E2E. This branch defines two
Minecraft 1.21.1 release files and two matching runtime rows: Fabric and NeoForge. Every artifact
advertises and launches on exactly Minecraft 1.21.1; matrix validation fails if any consumer-facing
row or task identity disagrees.

## Local setup

Build and stage the production jars plus separate remapped automation mods:

```bash
./gradlew --no-parallel clean buildAllLanes buildAllE2EHarnesses
python scripts/release/verify_release.py --stage build/release
python -m pip install --only-binary=:all: --requirement e2e/requirements.txt
```

`buildAllLanes` also runs the conventional loader-independent JUnit suite on the stable common
version selected by `unit_test_version` in the release matrix. Run it directly with
`./gradlew testStableLane` while developing boundary logic. Gradle itself must start on JDK 21 or
newer for Stonecutter; the packaged Minecraft runtime below uses Java 21.

List the resolved matrix without launching Minecraft:

```bash
python e2e/orchestrator.py --list --artifacts-manifest build/release/artifacts.json
```

Run one packaged smoke. The runtime needs a display, not a specific operating system: on headless
Linux (including CI) wrap the command in `xvfb-run`, and on a machine with a real display — macOS
included — run it directly.

```bash
# Headless Linux / CI
xvfb-run -a python e2e/orchestrator.py \
  --packaged \
  --artifact-node fabric-1.21.1 \
  --runtime-version 1.21.1 \
  --scenarios phase0-smoke

# macOS or any desktop session
python e2e/orchestrator.py \
  --packaged \
  --artifact-node fabric-1.21.1 \
  --runtime-version 1.21.1 \
  --scenarios phase0-smoke
```

The two-client `propagation` and `propagation-live` scenarios and the NeoForge lane are exercised on
Linux CI. Treat local macOS runs as development evidence only; release evidence comes from the CI
Linux run.

Java 21 is selected from `QUICKSKIN_JAVA_21` or `JAVA_HOME_21_X64`. Gradle's own toolchain
downloads under `~/.gradle/jdks/` satisfy this, so a machine that has built the lanes usually
already has it. Each execution creates an isolated server and client game directory below
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

Pull requests run smoke, live propagation, and full behavior on both loader lanes. A release runs
all four scenarios for both lanes against the manifest-bound bytes from the exact release commit.
The same packaged workflow can also be dispatched manually, and master-to-version synchronization
dispatches it explicitly before an automated port is allowed to merge.
