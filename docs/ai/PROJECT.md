# Project and release contract

This file is part of the repository-wide instruction set imported by `AGENTS.md`. Together, the
documents listed there are the source of truth for coding agents working anywhere below the
repository root.

## Documentation map

- `AGENTS.md` is the import-only manifest for the complete coding-agent instruction set.
- `CLAUDE.md` is only a compatibility redirect to that manifest.
- `CONTRIBUTING.md` is the human-facing path from an unfamiliar checkout to a reviewed pull
  request, including an AI-assisted workflow.
- `README.md` is for users and builders; focused architecture documents own their subjects.

Do not put operational rules directly in `AGENTS.md` or `CLAUDE.md`, and do not create another root
instruction file that restates this contract. Add a nested `AGENTS.md` only when a directory
genuinely needs narrower rules, and keep it limited to imports for those local deltas.

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

## Version branch model

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
- GITHUB_TOKEN-created PRs and child runs do not recursively start ordinary PR or completion
  workflows, so synchronization explicitly dispatches `build-gate.yml` and `on-demand-e2e.yml`.
  Each gate reports completion through a trusted `repository_dispatch`; the result handler merges
  only when the latest exact-head run of both gates succeeds. An open synchronization PR is updated
  in place when newer shared commits arrive.
- After merging, the controller publishes lightweight Build and Packaged E2E attestations on the
  final release branch. They must verify the original trusted run IDs, exact tested commit, ancestry,
  and identical Git trees; never rerun Minecraft merely to populate a badge or attest a changed tree.
- The marked README release-status table is generated from discovered release branches and each
  branch's matrix. Do not edit its rows manually or add a branch/version list to its workflow.
- Shared behavior changes start on `master`. A version-only fix starts on its release branch and
  must be reflected in canonical `master` sources when the same behavior applies elsewhere.

## Task routing

Choose the target before editing:

| Change scope | Start from | Expected destination |
|---|---|---|
| Shared behavior, security, tests, automation, or general documentation | `master` | Workflow-owned port PRs to release branches |
| One exact Minecraft version or loader pair | That release branch | Only that release branch |
| Version/loader support inventory | `master`, matrix first | New or updated release branch after matrix validation |
| Generated output or staged artifacts | Nowhere | Fix the tracked input instead |

At the start of every task:

1. Inspect `git status --short --branch` and preserve existing work.
2. Read the active `release/release-matrix.json`; never infer support from directory names alone.
3. Read the relevant focused document and module build file.
4. Search canonical sources and every active overlay for the affected path or symbol.
5. State the intended scope and run the smallest check that can disprove the change while
   iterating.

Never develop directly on `automation/sync/*`; those branches are disposable workflow-owned PR
heads. Human contributors start with `CONTRIBUTING.md` and use a separate topic branch.

The current synchronizer attempts every release branch for a new `master` change. If intended
scope excludes a version, make that exception explicit before editing. Do not silently spread broad
Stonecutter conditionals or create a second branch inventory; choose a narrow adapter/overlay,
version-branch change, or explicit synchronization-policy change and document the decision.
