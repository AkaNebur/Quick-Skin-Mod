# Editing and verification workflow

This file is part of the repository-wide instruction set imported by `AGENTS.md`.

## Editing workflow

- Read `CONTRIBUTING.md` when preparing a human-facing branch, commit, or pull request.
- Read `release/release-matrix.json` and the relevant module `build.gradle.kts` before changing
  versions, loaders, source roots, resources, artifact tasks, or E2E coverage.
- Search canonical sources and all active overlays before changing a cross-version class or method.
- Preserve unrelated working-tree changes. Do not rewrite or delete user work to simplify a patch.
- Never switch or repurpose a user's existing checkout merely to inspect or edit a different branch.
  Fetch that remote branch and create a separate ephemeral Git worktree; inside it, reread
  `AGENTS.md`, every imported instruction, and that branch's release matrix before acting.
- Remove an ephemeral worktree only after `git status --short` is empty and every valuable change
  belongs to a named branch and is committed, pushed, or otherwise exported. A detached-HEAD commit
  alone is not preserved. Let `git worktree remove` refuse dirty trees; never use `--force` to erase
  a dirty or user-owned worktree. Discard work only with the user's explicit authorization.
- Do not commit generated JARs, staged release files, Minecraft runtime directories, screenshots,
  caches, or IDE output.
- Keep production and E2E JARs physically separate. The E2E harness may compile against main output
  but must never package Quick Skin production classes.
- Do not run multiple Gradle invocations concurrently. Architectury uses JVM-global transform state,
  and this repository intentionally disables parallel Gradle execution for aggregate builds.
- Keep each commit to one reviewable concern. Use an imperative conventional subject consistent
  with repository history: `feat:`, `fix:`, `refactor:`, `test:`, `build:`, `docs:`, `ci:`, or
  `chore:`.
- Before committing, inspect the staged diff, run `git diff --check` and
  `git diff --cached --check`, and confirm that no generated or unrelated files are staged. Commit,
  amend, rebase, push, force-push, open a PR, or merge only when explicitly requested.
- Never rewrite commits that may belong to the user or another contributor. Updating an unshared
  topic branch may use rebase when requested; updating a shared branch must use a non-destructive
  merge or a fresh topic branch.
- A pull request targets `master` for shared changes and the exact release branch for version-only
  changes. Its title follows the same conventional format, and its body records scope, validation,
  risks, generated-output status, and material AI assistance.

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
  :common:1.21.11:test
```

Full production and packaged-harness gate:

```powershell
.\gradlew.bat --no-daemon --no-parallel clean `
  :common:1.21.11:test `
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
python -m py_compile scripts/release/branch_readme.py scripts/release/matrix.py `
  scripts/release/verify_release.py `
  scripts/release/generate_sbom.py `
  scripts/release/github_governance.py scripts/release/github_release.py `
  scripts/release/reconcile_publication.py `
  scripts/release/release_identity.py scripts/release/status_table.py `
  scripts/release/verify_reproducibility.py `
  scripts/release/version_branches.py scripts/ci/ai_patch_policy.py `
  scripts/ci/gradle_cache_policy.py scripts/ci/prune_actions_caches.py e2e/orchestrator.py `
  scripts/pages/evidence.py scripts/pages/build_site.py scripts/pages/select_artifact.py `
  scripts/pages/rotate_artifacts.py `
  e2e/packaged_runtime.py e2e/visual_evidence.py e2e/visual_review.py `
  e2e/check_visual_review.py
python -m unittest discover -s scripts/release/tests -p "test_*.py" -v
python -m unittest discover -s scripts/ci/tests -p "test_*.py" -v
```

Packaged Minecraft runtime scenarios require a display and the Java 21 toolchain. Use Xvfb on
headless Linux and in CI; on a desktop session, macOS included, run the orchestrator directly.
Follow `e2e/README.md` for what is verified on which platform, and do not substitute Loom
development runs for packaged-JAR E2E evidence. Gradle and Stonecutter must themselves start on
JDK 21 or newer; shared CI installs JDK 17, JDK 21, and JDK 25 so each version branch can select
its matrix-declared toolchain.

Release automation always rebuilds `buildAllLanes buildAllE2EHarnesses` with `--rerun-tasks` and
requires every production and harness SHA-256 to equal the first build. When determinism is in
scope locally, use `scripts/release/verify_reproducibility.py` against the first staged manifest.

## Documentation maintenance

- Keep the active support table and user build instructions in `README.md` synchronized with the
  release matrix.
- Keep oracle preservation and post-retirement resource routing in `ORACLE-RETIREMENT.md`.
- Keep packaged-runtime behavior in `e2e/README.md`.
- Keep screenshot semantics in `e2e/visual-catalog.json` and public-site behavior under
  `scripts/pages/` plus `site/`; never hand-maintain versions in those files.
- Keep the synchronization and thin-branch contract in `VERSION-BRANCHES.md`.
- Keep immutable release identity, retry semantics, provenance, and protected-environment operation
  in `RELEASING.md`.
- Keep the marked README branch profile aligned through `scripts/release/branch_readme.py`. It
  renders `master` as integration-only and derives each release branch's Minecraft version,
  loaders, Java target, runtime pins, and overlay routing from that branch's matrix; do not edit the
  generated block by hand.
- Keep the generated README status block aligned through `scripts/release/status_table.py`; never
  hand-maintain its version rows.
- When user-visible behavior, build commands, source layout, or compatibility facts change, adapt
  the non-generated README text on every affected branch. For shared changes, verify that one
  synchronization PR per discovered release branch passes both exact-head gates, merges, and gains
  its final exact-tree attestations; document any deliberate exclusion and any outstanding port.
- Keep the newcomer and AI-assisted contribution path in `CONTRIBUTING.md`, and keep
  `.github/pull_request_template.md` aligned with it.
- Keep root `AGENTS.md` limited to one `@path.md` import per line and keep root `CLAUDE.md`
  byte-for-byte equivalent to `@AGENTS.md` followed by one newline.
- Update the appropriate imported file whenever source-set routing, overlay ownership, lifecycle
  composition roots, security boundaries, or mandatory verification commands change.
- When a packaged scenario adds, renames, or removes a required screenshot step, update the visual
  catalog, gallery/reviewer tests, and every affected release branch in the same shared delivery.

When matrix-owned profile facts change, regenerate the marked README block instead of editing it:

```powershell
python scripts/release/branch_readme.py `
  --matrix release/release-matrix.json `
  --readme README.md `
  --profile-branch "<master-or-exact-release-branch>" `
  --write
```
