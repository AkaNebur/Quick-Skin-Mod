# Changelog

## 2.6.2.6 (unreleased)

### Added

- Restored `.cpmmodel` discovery, import, preview, selection, and CPM lifecycle integration on all five active Minecraft bands.
- Restored optional 3D Skin Layers preview integration, including the deferred 26.2 render path.
- Added packaged-artifact E2E coverage for the 10 release files and their 10 exact runtime combinations.
- Added build, scheduled E2E, release-gate, and dual-marketplace publishing workflows.

### Changed

- Consolidated active development into one Stonecutter-managed source tree with narrow era overlays.
- Made the release matrix the source of truth for artifact paths, runtime coordinates, metadata ranges, and marketplace versions.
- Corrected loader metadata ranges, project links, and the All Rights Reserved license declaration.
- Reduced the active build to five exact Minecraft targets and removed retired source trees, configuration, metadata, and release automation.

## 2.6.2.5

### Fixed

- Fixed an `IndexOutOfBoundsException` when rendering the skin-list drop zone with one or two skins loaded.
