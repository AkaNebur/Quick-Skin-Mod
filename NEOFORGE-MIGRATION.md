# Historical NeoForge migration note

> This document describes a retired repository layout and is retained only as migration history. Its old module names, Forgix workflow, merged jars, `build121` task, and per-version copy workflow are no longer valid. Use the [README](README.md) for current build and support information.

## What this migration represented

Quick Skin originally added Minecraft 1.21.1 and NeoForge by duplicating the older common/Fabric modules into separate `common-1.21` and `fabric-1.21` projects. At that time:

- Minecraft 1.20.1 used Fabric and Forge.
- Minecraft 1.21.1 used Fabric and NeoForge.
- Fixes had to be copied manually between version-specific source trees.

That architecture has been replaced by Stonecutter-managed canonical sources, narrow era overlays, and a ten-artifact build matrix. The current aggregate build is:

```bash
./gradlew --no-parallel buildAllLanes
```

Development runs use a version node and configuration-on-demand, for example:

```bash
./gradlew :neoforge:1.21.11:runClient --configure-on-demand
```

## Current NeoForge boundary

The current build matrix emits NeoForge artifacts for 1.21.1, 1.21.11, 26.1, and 26.2. The pooled NeoForge 26.1-26.1.2 support claim is release-gated and currently blocked, not passed: 26.1 and 26.1.1 require a maintained Architectury compatibility build, and the packaged jar must pass each claimed runtime before publication. Unless that compatibility dependency is available, the supported NeoForge range begins at 26.1.2.

The last complete pre-Stonecutter source snapshot is the `pre-stonecutter-oracle` tag (`ae872376b665c6040f395086f1fab416587555e0`). It is an implementation oracle, not proof that a published binary was built from that exact commit.
