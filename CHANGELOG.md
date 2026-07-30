# Changelog

## 3.0.0 (unreleased)

### Added

- Restored `.cpmmodel` discovery, import, preview, selection, and CPM lifecycle integration on both
  Minecraft 1.20.1 loader lanes.
- Restored optional 3D Skin Layers preview integration for the supported 1.20.1 render path.
- Added packaged-artifact E2E coverage for the two release files and their two exact runtime
  combinations.
- Skin and cape menus now pick up files copied into `quickskin/uploads/` from outside the game, without a client restart.
- Added build, scheduled E2E, release-gate, and dual-marketplace publishing workflows.

### Changed

- Consolidated active development into one Stonecutter-managed source tree with narrow era overlays.
- Made the release matrix the source of truth for artifact paths, runtime coordinates, metadata ranges, and marketplace versions.
- Corrected loader metadata ranges, project links, and the All Rights Reserved license declaration.
- Reduced the active build to Minecraft 1.20.1 on Fabric and Forge, and removed the later-version
  and NeoForge source trees, configuration, metadata, and release automation.

## 2.6.2.5

### Fixed

- Fixed an `IndexOutOfBoundsException` when rendering the skin-list drop zone with one or two skins loaded.
