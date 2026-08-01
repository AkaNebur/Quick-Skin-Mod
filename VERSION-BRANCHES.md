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
creation or deletion and periodically repairs missed events. Do not maintain a second version list
inside that workflow or edit the generated table by hand.

If either gate fails, the trusted result workflow gives Claude one bounded repair attempt using the
failed logs and evidence. It redispatches both gates for the new commit. A second failure leaves the
PR open rather than weakening tests or looping indefinitely.

External pull requests still run both workflows normally. AI steps that require repository secrets
are skipped for forked pull requests; untrusted code never receives the Claude credential.

## Working locally

Compare only a release branch's compatibility delta:

```bash
git diff master...forge-and-fabric-1.20.1
```

Work on multiple versions without duplicating the Git object database:

```bash
git worktree add ../quick-skin-1.20.1 forge-and-fabric-1.20.1
```

Each working tree contains a full checkout, but all worktrees share the repository's `.git` object
store. Run only the matrix-declared Gradle and packaged-runtime lanes for that worktree.
