# Quick Skin

[![Build gate](https://github.com/AkaNebur/Quick-Skin-Mod/actions/workflows/build-gate.yml/badge.svg?branch=master)](https://github.com/AkaNebur/Quick-Skin-Mod/actions/workflows/build-gate.yml?query=branch%3Amaster)

Quick Skin is a client-and-server Minecraft 1.20.1 mod for changing skins and capes in-game. It supports local and network-synchronized appearances, HD textures, animated capes, and optional integrations without requiring players to leave the game.

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

This branch builds two Minecraft 1.20.1 loader artifacts from one Stonecutter-managed source tree.

| Minecraft | Fabric | Forge | Java | Status |
|---|:---:|:---:|---:|---|
| 1.20.1 | Yes | Yes | 17 | Active |

Every artifact targets exactly the Minecraft version printed in its filename and metadata. No active
artifact advertises a wider compatibility range.

## Installation

Choose the jar whose Minecraft version and loader match your instance. Quick Skin also requires:

- Architectury API for the selected Minecraft version and loader.
- Fabric API on Fabric.
- Forge when using the Forge artifact.

Install Quick Skin on the client for local appearance management. Install it on the server as well when you want Quick Skin appearance synchronization, shared texture transfer, or server-enforced cooldowns.

## Using Quick Skin

1. Open the Quick Skin menu from the title or pause screen, or bind its configurable key.
2. Import a skin or place it under `.minecraft/quickskin/skins/`.
3. Import a cape or place it under `.minecraft/quickskin/capes/`.
4. Select an appearance, preview it, and choose automatic, classic, or slim arms.

When CPM is installed, standalone models live under `.minecraft/player_models/` and can be imported as `.cpmmodel` files.

3D Skin Layers preview support follows the availability of the upstream mod for each loader. Entity previews are owned by the third-party renderer; Quick Skin supplies only the missing manual/title-screen preview path.

## Building

Build both production artifacts in one serial Gradle invocation:

```bash
./gradlew --no-parallel buildAllLanes
```

On Windows:

```powershell
.\gradlew.bat --no-parallel buildAllLanes
```

Launch Gradle with JDK 21 or newer because the Stonecutter build plugin requires it. The produced
Minecraft 1.20.1 jars still target Java 17 through Gradle's Java toolchain.

Production jars are written under the selected module and version node, for example:

```text
fabric/versions/1.20.1/build/libs/
forge/versions/1.20.1/build/libs/
```

Development launches should use configuration-on-demand so unrelated version nodes are not configured:

```bash
./gradlew :fabric:1.20.1:runClient --configure-on-demand
./gradlew :forge:1.20.1:runServer --configure-on-demand
```

The aggregate build intentionally runs with `org.gradle.parallel=false`; Architectury's transformers use JVM-global properties and concurrent transforms are not safe.

Release automation also builds two separate, loader-remapped test harness jars and installs them beside the exact staged production files. See [Packaged-runtime E2E](e2e/README.md) for the two-row gate and local commands.

## Source layout

- `common/src/main`, `fabric/src/main`, and `forge/src/main` contain canonical sources.
- `common/src/legacy1_20_1` isolates the Minecraft 1.20.1 API boundary.
- `PreviewRenderBackend`, `GuiCompat`, `NetworkTransport`, `MinecraftCompat`, and `PlatformHelper` define the cross-version seams.
- Copy-based `src/v*` snapshots are retired; matrix validation rejects their reintroduction. Their preserved reference and resource-routing plan are documented in [Migration oracle retirement](ORACLE-RETIREMENT.md).

Shared changes land on `master` and are ported through tested pull requests to one release branch
per Minecraft version. Git stores those branches as shared history plus their small compatibility
deltas; it does not duplicate unchanged source blobs. See [Version branches](VERSION-BRANCHES.md)
for the naming, synchronization, AI-repair, and merge contract.

## Historical documentation

- [Migration oracle retirement](ORACLE-RETIREMENT.md) records the preservation tag and post-retirement source policy.

## Contributing

Contributions are welcome, including from people using a coding assistant and encountering the
project for the first time. Start with [CONTRIBUTING.md](CONTRIBUTING.md) for branch selection,
safe AI prompting, source ownership, tests, commits, updates, and the pull-request checklist.

Bug reports and feature ideas are just as useful, so open an issue if something is broken. Coding
agents use [AGENTS.md](AGENTS.md) as the repository-wide operational contract.

## License

**All rights reserved** — the source is public, but this is not open source.

You are welcome to read the code, build it for your own use, and fork it to
send pull requests back here. You may not reupload, mirror or redistribute the
mod, publish modified versions of it, or reuse the code in another project.

The only official downloads are [Modrinth](https://modrinth.com/mod/quick-skin),
[CurseForge](https://www.curseforge.com/minecraft/mc-mods/quick-skin) and this
repository. Anything else is a reupload.

Need permission for something not covered here? Ask — reasonable requests are
usually granted. Full terms in [LICENSE](LICENSE).
