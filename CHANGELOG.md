# Changelog

## 2.6.2.6 (unreleased)

### Added

- Restored `.cpmmodel` discovery, import, preview, selection, and CPM lifecycle integration on all five active Minecraft bands.
- Restored optional 3D Skin Layers preview integration, including the deferred 26.2 render path.
- Added packaged-artifact E2E coverage for the 10 release files and their 14 advertised runtime combinations.
- Added build, scheduled E2E, release-gate, and dual-marketplace publishing workflows.

### Changed

- Consolidated active development into one Stonecutter-managed source tree with narrow era overlays.
- Made the release matrix the source of truth for artifact paths, runtime coordinates, metadata ranges, and marketplace versions.
- Corrected loader metadata ranges, project links, and the All Rights Reserved license declaration.
- Froze Minecraft 1.21.4 through 1.21.10; their published binaries remain available but receive no new builds.

### Release blocker

- NeoForge 26.1 and 26.1.1 require a maintained, published Architectury compatibility artifact. Publishing remains blocked until its URL, SHA-256, Modrinth ID, and CurseForge ID are configured and all 14 packaged-runtime smokes pass.

## 2.6.2.5

### Fixed

- Fixed an `IndexOutOfBoundsException` when rendering the skin-list drop zone with one or two skins loaded.
