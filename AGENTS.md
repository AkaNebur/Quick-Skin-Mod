# Quick Skin Agent Guide

This file is the repository-wide source of truth for coding agents. It applies to every path below
the repository root. Keep detailed human-facing explanations in `README.md` or focused documents,
but keep operational rules and architecture constraints here.

If a tool requires a root `CLAUDE.md`, that file must contain only:

```text
@AGENTS.md
```

Do not duplicate these instructions in `CLAUDE.md`; duplicated guidance drifts.

## Project and release contract

Quick Skin is a client-and-server Minecraft mod built from one Stonecutter-managed source tree. The
central inventory is `release/release-matrix.json`. It is authoritative for supported versions,
loaders, Java versions, remap policy, source-overlay routing, Gradle artifact tasks, runtime
dependencies, E2E lanes, loader ranges, and FML pack formats. Do not create a second lane list in
Gradle, Python, workflows, or documentation.

The active production matrix on this branch contains exactly two artifacts:

| Minecraft | Loaders | Java |
|---|---|---:|
| 1.20.1 | Fabric, Forge | 17 |

Every artifact targets exactly the Minecraft version in its filename and metadata. A support or
loader change starts in the release matrix and must pass its validation and mutation tests.

### Version branch model

- `master` is the shared integration branch. Release branches use the naming form
  `<loader>-and-<loader>-<minecraft>`, for example `forge-and-fabric-1.20.1`.
- A release branch is a normal descendant of `master`, not an orphan patch branch. Unchanged Git
  blobs are shared; the branch-specific commits contain only its matrix, loader/API adapters,
  overlays, metadata, and documentation differences.
- `.github/workflows/sync-version-branches.yml` discovers release branches from their names. It must
  not contain a Minecraft-version list. The matrix checked into each target remains authoritative.
- A trusted push to `master` creates a target-specific synchronization branch and PR. Clean merges
  are mechanical. Claude may resolve a merge conflict while preserving the target matrix and may
  make one bounded repair after a failed gate.
- GITHUB_TOKEN-created PRs do not recursively start ordinary PR workflows, so synchronization
  explicitly dispatches `build-gate.yml` and `on-demand-e2e.yml`. The result handler merges only
  when the latest exact-head run of both workflows succeeds; otherwise the PR remains open.
- Shared behavior changes start on `master`. A version-only fix starts on its release branch and
  must be reflected in canonical `master` sources when the same behavior applies elsewhere.

## Source-set architecture

### Canonical sources

These are the primary implementation trees:

- `common/src/main`: shared client, server, networking, storage, and compatibility code.
- `fabric/src/main`: canonical Fabric entry points and loader integration.
- `forge/src/main`: the active Forge 1.20.1 integration.
- `common/src/e2e` plus each loader's `src/e2e`: the separate packaged-runtime test mod.
- `common/src/test`: loader-independent JUnit regression tests compiled against the common 1.20.1
  node.

Stonecutter preprocesses each canonical `src/main` tree into detached generated sources. Never edit
generated or staged output under `common/versions`, `fabric/versions`, `forge/versions`,
any `build/` directory, `.gradle/`, `.architectury-transformer/`, `e2e-out/`, or
`build/release/`. Fix the tracked canonical source or active overlay instead.

### Active `legacy*` overlays

`legacy` means an active era-specific compatibility overlay, not dead or unsupported code. For an
overlay lane, Gradle performs this operation:

```text
canonical src/main
  -> Stonecutter-generated sources for the selected version
  -> remove generated files whose relative paths exist in the overlay
  -> copy the overlay files
  -> compile generated/consolidated/main/java

canonical src/main/resources
  -> remove resources whose relative paths exist in the overlay
  -> copy the overlay resources
  -> process generated/consolidated/main/resources
```

An overlay file therefore replaces the canonical file at the same relative path. The active
overlays are:

| Module | Minecraft | Active overlay |
|---|---|---|
| common | 1.20.1 | `common/src/legacy1_20_1` |
| fabric | 1.20.1 | none; canonical output |
| forge | 1.20.1 | none; `forge/src/main` |

The remaining whole-file canonical replacements are genuine 1.20.1 rewrites:
`ModNetworking`, `ServerNetworkHandler`, `PlayerInfoMixin`, and
`MixinAbstractClientPlayer`. Other overlay Java files are additive compatibility classes or thin
1.20.1 backends.

Keep overlays narrow. Prefer a small adapter or a Stonecutter version branch over copying an entire
service, screen, or handler. When a class exists in an active overlay:

1. Make the intended behavior clear in the canonical implementation first when possible.
2. Find every active overlay of the same relative path.
3. Apply the equivalent behavior using that era's Minecraft API.
4. Compile and test every affected version/loader lane.

Changing only `src/main` does not fix a lane whose overlay replaces that file.

### Retired `src/v*` snapshots

The copy-based `src/v*` migration snapshots are retired and must not be restored. Their final state
is preserved by the `pre-scalability-oracle-retirement` Git tag. Consult that tag only as a parity
reference, then make the effective change in canonical sources or an active overlay. Matrix
validation rejects reintroduced `src/v*` content and live Java classes with more than two copies.
See `ORACLE-RETIREMENT.md` for the retirement gate and resource-routing details.

## Runtime architecture invariants

### Lifecycle and threading

- `ClientRuntime` and `ServerRuntime` are the composition roots for session-scoped state. Loader
  lifecycle hooks must enter and leave through them.
- Teardown must use exact connection/session identity. A delayed disconnect must never clear a
  replacement connection for the same UUID.
- Minecraft client and server state is committed on the appropriate main thread. File reads, image
  decoding, hashing, chunk assembly, and other bulk work belong on the bounded worker executors.
- Executors, queues, prepared handoffs, and pending transfers must remain bounded and must release
  leases on success, failure, cancellation, disconnect, and shutdown.

### Networking and texture identity

- Treat all packet fields, lengths, hashes, image data, metadata, and disk cache contents as
  untrusted. Validate before allocation or state mutation.
- Keep packet codecs, chunk assemblers, rate limiters, request maps, retry state, and caches bounded.
- Large texture bytes are demand-driven: advertise appearances/hashes, and send bytes only after a
  missing client requests them. Preserve the global per-tick response and upload pacing.
- Appearance snapshots and updates use bounded, session-identity-aware pacing plus exact completion
  acknowledgement. A dropped or superseded snapshot must converge through bounded retry.
- Network texture identity is the hash of the exact canonical transmitted PNG. Local cape asset IDs
  use `HashUtil.computeAssetHash(..., "cape")` and are deliberately domain-separated from skin IDs.
- Client caches are keyed by both hash and texture type. The same PNG bytes may validly exist as a
  skin and a cape; never collapse typed keys back to a hash-only cache or resource path.
- Renderer-confirmed skin/cape use receives only a short, bounded working-set preference. Cache
  entry, byte, and pixel caps remain hard even when every resident texture is visible.
- Animated canonical PNGs carry exact `qsMD` metadata through `PngAnimationIdentity`. Changing frame
  timing must change the transmitted identity even when pixels are unchanged.
- Animation slots are visibility/staleness based, not arrival ordered. A visible network animation
  without a slot must use its separately bounded first-frame texture (or render nothing while that
  frame is prepared); never expose the stacked atlas as a static cape fallback.
- Server animation metadata is immutable after the first accepted value for the lifetime of the
  backing cape. Delivery-cache eviction must not delete the persistent identity binding; backing
  texture deletion must remove metadata, authority, and identity together.
- Appearance and animation convergence depends on exact acknowledgements and bounded retry. Do not
  replace it with optimistic send-once synchronization.

### Files, images, and persistence

- Use `BoundedFileReader`, `SafeImageReader`, and the established GIF preflight path. Do not add
  production `ImageIO.read`, unbounded `Files.readAllBytes`/`readString`, or decode-before-dimension
  validation.
- Resolve content-addressed paths through the containment helpers. Reject invalid content IDs,
  symbolic-link targets, and paths outside the configured root.
- Persist mutable state using a temporary file plus atomic replace where supported. Keep the
  fallback contained and clean stale temporary files during initialization.
- Keep cache accounting weighted by bytes/pixels where relevant, not only entry count. Active server
  appearance blobs remain pinned only within the hard global pinned-byte budget; reject an
  over-budget appearance gracefully instead of weakening the cap.
- Server cache deletion belongs on the bounded cache-I/O executor. Remove a cache entry from the
  live namespace before scheduling deletion so a concurrent replacement cannot be deleted.

### Optional integrations

- CPM, Ears, CustomNPCs, and 3D Skin Layers are optional. Guard their entry points and preserve the
  normal skin/cape path when an optional mod or API is absent.
- Compatibility failures must degrade locally; they must not break base mod initialization or
  dedicated-server startup.

## Editing workflow

- Read `release/release-matrix.json` and the relevant module `build.gradle.kts` before changing
  versions, loaders, source roots, resources, artifact tasks, or E2E coverage.
- Search canonical sources and all active overlays before changing a cross-version class or method.
- Preserve unrelated working-tree changes. Do not rewrite or delete user work to simplify a patch.
- Do not commit generated JARs, staged release files, Minecraft runtime directories, screenshots,
  caches, or IDE output.
- Keep production and E2E JARs physically separate. The E2E harness may compile against main output
  but must never package Quick Skin production classes.
- Do not run multiple Gradle invocations concurrently. Architectury uses JVM-global transform state,
  and this repository intentionally disables parallel Gradle execution for aggregate builds.
- Use conventional commit subjects consistent with repository history, for example `feat:`,
  `fix:`, `refactor:`, `test:`, `build:`, or `chore:`. Commit only when explicitly requested.

## Verification

Use the smallest relevant check while iterating, then run the proportional aggregate gate before
handoff. On Windows, use `gradlew.bat`; on Unix-like systems, use `./gradlew`.

Fast stable unit lane:

```powershell
.\gradlew.bat --no-parallel testStableLane
```

The active common test lane:

```powershell
.\gradlew.bat --no-daemon --no-parallel `
  :common:1.20.1:test
```

Full production and packaged-harness gate:

```powershell
.\gradlew.bat --no-daemon --no-parallel clean `
  :common:1.20.1:test `
  buildAllLanes buildAllE2EHarnesses
```

Stage and verify the exact release outputs:

```powershell
python scripts/release/verify_release.py `
  --matrix release/release-matrix.json `
  --manifest build/release/artifacts.json `
  --stage build/release

python scripts/release/verify_release.py `
  --matrix release/release-matrix.json `
  --manifest build/release/artifacts.json `
  --stage build/release `
  --verify-staged
```

Also run:

```powershell
git diff --check
python -m py_compile scripts/release/matrix.py scripts/release/verify_release.py `
  scripts/release/version_branches.py e2e/orchestrator.py `
  e2e/packaged_runtime.py e2e/visual_review.py
python -m unittest discover -s scripts/release/tests -p "test_*.py" -v
```

Packaged Minecraft runtime scenarios require a display and the Java 17 toolchain. Use Xvfb on
headless Linux and in CI; on a desktop session, macOS included, run the orchestrator directly.
Follow `e2e/README.md` for what is verified on which platform, and do not substitute Loom
development runs for packaged-JAR E2E evidence. Gradle and Stonecutter must themselves start on
JDK 21 or newer; shared CI installs JDK 17, JDK 21, and JDK 25 so each version branch can select
its matrix-declared toolchain.

When release determinism is in scope, rebuild `buildAllLanes buildAllE2EHarnesses` with
`--rerun-tasks` and compare both production and both harness SHA-256 values with the first build.

## Documentation maintenance

- Keep the active support table and user build instructions in `README.md` synchronized with the
  release matrix.
- Keep oracle preservation and post-retirement resource routing in `ORACLE-RETIREMENT.md`.
- Keep packaged-runtime behavior in `e2e/README.md`.
- Keep the synchronization and thin-branch contract in `VERSION-BRANCHES.md`.
- Update this file whenever source-set routing, overlay ownership, lifecycle composition roots,
  security boundaries, or mandatory verification commands change.
