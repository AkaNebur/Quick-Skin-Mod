# Source-set architecture

This file is part of the repository-wide instruction set imported by `AGENTS.md`.

## Canonical sources

These are the primary implementation trees:

- `common/src/main`: shared client, server, networking, storage, and compatibility code.
- `fabric/src/main`: canonical Fabric entry points and loader integration.
- `forge/src/main`: the active Forge 1.20.1 integration.
- `common/src/e2e` plus each loader's `src/e2e`: the separate packaged-runtime test mod.
- `common/src/test`: loader-independent JUnit regression tests compiled against the common 1.20.1
  node.

Stonecutter preprocesses each canonical `src/main` tree into detached generated sources. Never edit
generated or staged output under `common/versions`, `fabric/versions`, `forge/versions`, any
`build/` directory, `.gradle/`, `.architectury-transformer/`, `e2e-out/`, or `build/release/`. Fix
the tracked canonical source or active overlay instead.

## Active `legacy*` overlays

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

## Retired `src/v*` snapshots

The copy-based `src/v*` migration snapshots are retired and must not be restored. Their final state
is preserved by the `pre-scalability-oracle-retirement` Git tag. Consult that tag only as a parity
reference, then make the effective change in canonical sources or an active overlay. Matrix
validation rejects reintroduced `src/v*` content and live Java classes with more than two copies.
See `ORACLE-RETIREMENT.md` for the retirement gate and resource-routing details.
