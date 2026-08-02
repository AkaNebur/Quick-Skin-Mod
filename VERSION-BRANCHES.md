# Version branches

Quick Skin keeps shared development on `master` and one independently buildable release branch for
each Minecraft version. A release branch name describes its active loader pair and exact Minecraft
version, for example `forge-and-fabric-1.20.1`.

These are ordinary Git branches. GitHub displays a complete source tree after checkout because the
branch must build independently, but unchanged files still reference the same content-addressed Git
objects as `master`. Only changed blobs, trees, and commits add repository storage.

## Source and release ownership

- `master` receives shared behavior, security, test-harness, and automation changes.
- `release/release-matrix.json` in each release branch declares only that branch's artifacts and
  runtimes. No workflow or script keeps a second Minecraft-version list.
- Version-specific differences stay in the active `legacy*` overlays, loader module, Gradle
  properties, metadata, and narrow API adapters selected by that matrix.
- Generated Stonecutter output, runtime directories, staged jars, screenshots, and caches never
  belong in a branch.

## Automated propagation

`Sync version branches` runs after a trusted push to `master` and can also be dispatched for one
exact target. It discovers remote release branches from the naming contract, then for each target:

1. creates an isolated `automation/sync/...` branch from the target, or updates its existing open
   synchronization PR in place;
2. merges `master`, invoking Claude only if semantic conflict resolution is required;
3. validates the target matrix and release mutation tests;
4. opens a PR and explicitly dispatches both `Build gate` and `Packaged E2E` for its exact head;
5. receives a trusted `repository_dispatch` when each gate settles;
6. merges and deletes the automation branch only after both exact-head gates pass;
7. dispatches lightweight Build and Packaged E2E attestations on the final release branch.

GitHub deliberately suppresses recursive workflow events produced with `GITHUB_TOKEN`, which is
why the gates are explicitly dispatched instead of relying on the PR-opened event. Each gate emits
a trusted `repository_dispatch` after it settles, avoiding both a suppressed `workflow_run` chain
and an idle polling runner per version branch.

The final attestations do not compile the mod or launch Minecraft again. Each one verifies through
the GitHub API that its original trusted run completed successfully, that the tested automation
commit is an ancestor of the final merge commit, and that both commits have the exact same Git tree.
The branch-specific badges in `README.md` therefore describe the final release tree without paying
for a duplicate build or E2E run.

The marked release-status table in `README.md` is generated from live remote release branches and
the authoritative matrix stored in each one. `Refresh release status table` updates it on branch
creation or deletion and periodically repairs missed events through one reusable automation PR;
it never pushes directly to `master`. Do not maintain a second version list inside that workflow
or edit the generated table by hand.

The marked branch-profile block is also generated. `scripts/release/branch_readme.py` describes
`master` as the integration branch and derives each release branch's exact Minecraft version,
loaders, Java target, locked runtime/API versions, and source overlays from that branch's matrix.
The synchronizer regenerates and validates it after merging, so a release README cannot silently
inherit another branch's badge or compatibility profile.

If either gate fails, the trusted result workflow gives Claude one bounded repair attempt using the
failed logs and evidence. Claude has no Git or GitHub write credentials and can only upload a
path-policy-checked patch. A separate deterministic writer rechecks and commits that patch before
redispatching both gates. A second failure leaves the PR open rather than weakening tests or
looping indefinitely.

External pull requests still run both workflows normally. AI steps that require repository secrets
are skipped for forked pull requests; untrusted code never receives the Claude credential.

## Working locally

The command examples in this section use a POSIX-compatible shell on macOS, Linux, or Git Bash.

Compare only a release branch's compatibility delta:

```bash
release_branch="<release-branch>"
git fetch origin
git diff "origin/master...origin/$release_branch"
```

Do not switch an existing checkout away from the branch its owner is using. For read-only
inspection, create a detached ephemeral worktree from the fetched remote branch:

```bash
qsm_worktree_root="$(mktemp -d "${TMPDIR:-/tmp}/quick-skin-worktree.XXXXXX")"
qsm_worktree_path="$qsm_worktree_root/checkout"
git worktree add --detach "$qsm_worktree_path" "origin/$release_branch"
```

Keep a detached inspection worktree read-only. If the task turns into an edit, remove the clean
inspection worktree and use the named topic-branch form below; do not commit on detached HEAD.

For an edit, create a topic branch in the ephemeral worktree instead:

```bash
topic_branch="fix/short-description"
qsm_worktree_root="$(mktemp -d "${TMPDIR:-/tmp}/quick-skin-worktree.XXXXXX")"
qsm_worktree_path="$qsm_worktree_root/checkout"
git worktree add -b "$topic_branch" "$qsm_worktree_path" "origin/$release_branch"
```

On entry, read that worktree's `AGENTS.md`, all of its imports, and
`release/release-matrix.json`; branch instructions and source overlays may differ. Each worktree has
a full checkout while sharing the repository's Git object database. Never run Gradle concurrently
in multiple worktrees.

Keep the worktree until every valuable change belongs to a named branch and is committed, pushed,
or otherwise exported. A detached-HEAD commit alone is not preservation. Then verify that the
worktree is clean and remove it without force:

```bash
git -C "$qsm_worktree_path" status --short
git worktree remove "$qsm_worktree_path"
rmdir "$qsm_worktree_root"
```

Stop if the status command prints anything or removal refuses. Never force-remove a dirty,
unfamiliar, or user-owned worktree, and never develop directly on `automation/sync/*`.
