# Quick Skin

Quick Skin is a client-and-server Minecraft mod for changing skins and capes in-game. It supports local and network-synchronized appearances, HD textures, animated capes, and optional integrations without requiring players to leave the game.

- [Modrinth](https://modrinth.com/mod/quick-skin) (`zAIE84Ch`)
- [CurseForge](https://www.curseforge.com/minecraft/mc-mods/quick-skin) (`1323980`)

## Features

- Change skins and capes from the title screen or pause menu.
- Import PNG, WebP, and JPEG skins, including HD skins up to 2048x1024.
- Import static and animated capes, with server-configurable change cooldowns.
- Choose automatic, classic, or slim player models.
- Preview appearances in an interactive 3D player widget.
- Synchronize Quick Skin appearances when the mod is installed on the server.
- Use optional Customizable Player Models (`.cpmmodel`) and 3D Skin Layers integrations when their matching third-party mod is installed.

Quick Skin does not declare CPM or 3D Skin Layers as required dependencies. If an optional mod or API is unavailable, the corresponding integration disables itself and normal skin/cape behavior remains available.

## Active build matrix

The repository builds ten loader artifacts from one Stonecutter-managed source tree.

| Minecraft band | Fabric | Forge | NeoForge | Java | Status |
|---|:---:|:---:|:---:|---:|---|
| 1.20.1 | Yes | Yes | - | 17 | Active |
| 1.21.1 | Yes | - | Yes | 21 | Active |
| 1.21.11 | Yes | - | Yes | 21 | Active |
| 26.1-26.1.2 | Yes | - | Built; pooled claim blocked | 25 | Release-gated |
| 26.2 | Yes | - | Yes | 25 | Active |

The pooled NeoForge 26.1-26.1.2 support claim is release-gated and currently blocked, not passed. NeoForge 26.1 and 26.1.1 require a maintained Architectury compatibility build. Because that build is the pooled file's required marketplace dependency, the gate installs its exact bytes on 26.1, 26.1.1, and 26.1.2. Unless that dependency is available and passes all three runtimes, NeoForge support begins at 26.1.2 with stock Architectury.

Minecraft 1.21.4 through 1.21.10 are frozen. Their existing downloads remain available, but they receive no new builds, fixes, dependency updates, or compatibility testing. See the exact files and checksums in [Frozen versions](docs/FROZEN-VERSIONS.md).

## Installation

Choose the jar whose Minecraft version and loader match your instance. Quick Skin also requires:

- Architectury API for the selected Minecraft version and loader.
- Fabric API on Fabric.
- Forge or NeoForge on its corresponding artifact.

Install Quick Skin on the client for local appearance management. Install it on the server as well when you want Quick Skin appearance synchronization, shared texture transfer, or server-enforced cooldowns.

## Using Quick Skin

1. Open the Quick Skin menu from the title or pause screen, or bind its configurable key.
2. Import a skin or place it under `.minecraft/quickskin/skins/`.
3. Import a cape or place it under `.minecraft/quickskin/capes/`.
4. Select an appearance, preview it, and choose automatic, classic, or slim arms.

When CPM is installed, standalone models live under `.minecraft/player_models/` and can be imported as `.cpmmodel` files. Explicit model-file selection is supported across active bands. Embedded CPM data inside an arbitrary Quick Skin PNG is a degraded compatibility case on 1.21.11 and later because current CPM no longer reads the registered player texture through its legacy bridge.

3D Skin Layers preview support follows the availability of the upstream mod for each loader. Entity previews are owned by the third-party renderer; Quick Skin supplies only the missing manual/title-screen preview path.

## Building

Build all ten production artifacts in one serial Gradle invocation:

```bash
./gradlew --no-parallel buildAllLanes
```

On Windows:

```powershell
.\gradlew.bat --no-parallel buildAllLanes
```

Production jars are written under the selected module and version node, for example:

```text
fabric/versions/1.21.11/build/libs/
neoforge/versions/26.2/build/libs/
forge/versions/1.20.1/build/libs/
```

Development launches should use configuration-on-demand so unrelated version nodes are not configured:

```bash
./gradlew :fabric:26.2:runClient --configure-on-demand
./gradlew :neoforge:1.21.11:runServer --configure-on-demand
```

The aggregate build intentionally runs with `org.gradle.parallel=false`; Architectury's transformers use JVM-global properties and mixed-version transforms are not safe in parallel.

Release automation also builds 10 separate, loader-remapped test harness jars and installs them beside the exact staged production files. See [Packaged-runtime E2E](e2e/README.md) for the 14-row gate and local commands.

## Source layout

- `common/src/main`, `fabric/src/main`, `forge/src/main`, and `neoforge/src/main` contain canonical sources.
- Small `src/legacy*` overlays isolate genuine era-level API boundaries.
- `PreviewRenderBackend`, `GuiCompat`, `NetworkTransport`, `MinecraftCompat`, and `PlatformHelper` define the cross-version seams.
- Historical `src/v*` trees are migration oracles, not supported release branches. Do not modify them during normal feature work.

The historical version trees remain until two successfully published, release-gate-green releases have completed and all remaining active resource references have been migrated away from them. See [Frozen versions](docs/FROZEN-VERSIONS.md) for the retention policy.

## Historical documentation

- [NeoForge migration history](NEOFORGE-MIGRATION.md) describes the retired pre-Stonecutter layout only.
- [Frozen versions](docs/FROZEN-VERSIONS.md) records archived artifact identities and support policy.

## License

All rights reserved.
