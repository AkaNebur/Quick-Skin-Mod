# Migration Oracle Retirement

The copy-based migration oracles were retired on 2026-07-17. Their final checked-in state is
preserved by the Git tag `pre-scalability-oracle-retirement`, which resolves to commit
`a0576aae6b2c5a0d6f951175fb32bfe24621a50f`. That tag is the parity reference if an old
implementation must be consulted; oracle sources must not be restored to active source roots.

## Retirement gate

The deletion becomes the repository baseline only after the retirement change passes all five
common test lanes, all ten production builds, all ten packaged-harness builds, staged-artifact
verification, and the packaged runtime scenarios described in `e2e/README.md`. The preservation
tag must remain available through at least the first clean release produced from that baseline.
If a parity issue is found before that release, compare the generated lane source with the tag and
fix the canonical source or an active `legacy*` overlay; do not copy an oracle tree back.

## Live resource routing after retirement

No `src/v*` content remains. Canonical resources live in each module's `src/main/resources` tree.
The active `common/src/legacy1_21_9` overlay is Java-only, so this branch has no era-specific
resource overrides.

Gradle consumes the canonical resources for every active module. Fabric
metadata is normalized from one canonical `fabric.mod.json`; NeoForge metadata is normalized from
its canonical TOML using release-matrix loader and dependency ranges. FML
`pack.mcmeta` values are also normalized from explicit per-artifact matrix fields, preserving the
previous lane-specific values without retaining resource snapshots. A new `src/v*` tree is
rejected by matrix validation.
