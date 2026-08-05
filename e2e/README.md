# Packaged-runtime E2E

The release gate tests the same jars that publishing receives. It does not use Loom run tasks or
compiled `main` output.

The checked-in [release matrix](../release/release-matrix.json) is the lane inventory consumed by
Stonecutter settings, aggregate Gradle tasks, publication, and E2E. This branch defines two
Minecraft 1.20.1 release files and two matching runtime rows: Fabric and Forge. Every artifact
advertises and launches on exactly Minecraft 1.20.1; matrix validation fails if any consumer-facing
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
newer for Stonecutter; the packaged Minecraft runtime below remains Java 17.

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
  --artifact-node fabric-1.20.1 \
  --runtime-version 1.20.1 \
  --scenarios phase0-smoke

# macOS or any desktop session
python e2e/orchestrator.py \
  --packaged \
  --artifact-node fabric-1.20.1 \
  --runtime-version 1.20.1 \
  --scenarios phase0-smoke
```

The two-client `propagation` and `propagation-live` scenarios and the Forge lane are exercised on
Linux CI. Treat local macOS runs as development evidence only; release evidence comes from the CI
Linux run.

Java 17 is selected from `QUICKSKIN_JAVA_17` or `JAVA_HOME_17_X64`. Gradle's own toolchain
downloads under `~/.gradle/jdks/` satisfy this, so a machine that has built the lanes usually
already has it. Each execution creates an isolated server and client game directory below
`e2e-out/profiles/`, installs the manifest-bound Quick Skin jar by SHA-256, and adds only the
loader-specific dependencies and separate E2E harness.

## Fail-closed contract

A row fails for a missing or changed package, missing/invalid report, unexpected step, failed or
corrupt/undersized/effectively blank screenshot, a washed-out OPAQUE_STARS skin-menu background, a
missing required skin-menu, cape-menu, cape-editor, or settings label, a visually unchanged apply/animation
pair, compatibility/error screen, crash report, or fatal mixin/access-widener/linkage/
`@ExpectPlatform` log evidence. The background check measures luminance in a normalized outer
region free of the menu and toast UI. Required-copy probes normalize to the gallery's 1600x900
reference size and require bright glyph pixels only in narrow, stable text regions. Other pixel
checks use broad entropy/color and pairwise-change invariants rather than golden images, so GPU and
Minecraft-version rendering differences are allowed. Every result records the literal fields
`artifact_node`, `runtime_version`, `loader`, `scenario`, `jar_sha256`, and `port`. All loader and
Architectury dependencies are locked directly in the matrix for that exact runtime.

Pull requests run smoke, live propagation, and full behavior on both loader lanes. A release runs
all four scenarios for both lanes against the manifest-bound bytes from the exact release commit.
The same packaged workflow can also be dispatched manually, and master-to-version synchronization
dispatches it explicitly before an automated port is allowed to merge.

After that tested port merges, the controller dispatches a lightweight run of the same workflow on
the final release branch. That run does not launch Minecraft: it verifies the successful source
run and requires the final merge commit to have exactly the tested Git tree. This is the evidence
shown by each branch-specific Packaged E2E badge in the root README.

## Public visual evidence

The architectural rationale, evaluated alternatives, and external precedents are recorded in
[ADR 0002](../docs/architecture/decisions/0002-publish-curated-e2e-evidence-with-github-pages.md).

The required runtime does not treat a filename as screenshot identity. Each validated capture has
the semantic key `<artifact>/<scenario>/<client-role>/<step>`, and
[`visual-catalog.json`](visual-catalog.json) supplies its stable title, expectation, and advisory
review tier. `result.json` remains authoritative for the screenshot path, status, SHA-256,
dimensions, and before/after pixel metrics. The catalog and the runtime screenshot-step contract
must have exact test-enforced coverage.

`visual_review_workflow.js` is an optional manual Workflow adapter, not the GitHub Actions entry
point. It consumes the generated manifest and emits the same exact verdict-array contract as
`check_visual_review.py`; keep that adapter and the CI prompt/checker schema aligned.

After a successful full run on a release branch—or after its exact-tree attestation—the advisory
`prepare-pages-evidence` job downloads the original packaged artifacts and creates
`pages-e2e-<branch>`. That one-day handoff contains only catalogued source PNGs plus a validated
manifest; logs, caches, crash reports, Minecraft directories, and AI-authored HTML are never
copied.

The `Project site` workflow executes only the protected generator from `master`. It discovers
release branches from GitHub, accepts the newest public artifact whose workflow branch and SHA
equal each current branch head, authenticates both recorded Actions runs, validates the exact
curated tree and every path/hash/dimension/catalog identity, rechecks all heads, converts every
accepted raw bundle to an exact-schema WebP derivative bundle before the `collected-pages-*`
fan-in, and publishes the complete site as one atomic GitHub Pages artifact. A missing, stale, or
invalid version aborts the new deployment so the previous site remains available. Pages and the AI
vision pass stay advisory; neither is added to the protected Build or Packaged E2E checks. After a
successful deployment, the workflow rolls each compact bundle into a protected 90-day
`pages-cache-*` artifact. Original PNG bytes never enter that durable cache. A monthly Pages run
validates and refreshes those compact caches, so an unchanged release branch does not need to
relaunch Minecraft merely to keep its public proof available.

Run the focused contracts in the project Python environment (CI installs the Linux renderer from
the hash-locked `scripts/pages/requirements.txt`):

```bash
python -m unittest \
  scripts.release.tests.test_visual_evidence \
  scripts.release.tests.test_pages_site -v
```

The Pages pipeline hash-locks the same Pillow version as packaged E2E. Protected `master` code
decodes every source PNG, recalculates its pixel metrics and required comparisons, and only then
creates bounded WebP images in a temporary bundle. It records and validates source identity,
hashes, dimensions and pixel metrics separately from the derivative identity, hashes, dimensions,
pixel metrics and comparisons before atomically admitting that bundle to fan-in. Later cache and
site reads revalidate the protected source record and the derivative bytes; the site copies the
content-addressed WebP without re-encoding it. For a local dependency-free fixture output, call
`scripts/pages/build_site.py --copy-images` with one or more already prepared branch bundles, then
serve the resulting directory over HTTP; the static JavaScript deliberately fetches its JSON
inventories rather than embedding untrusted data in HTML.
