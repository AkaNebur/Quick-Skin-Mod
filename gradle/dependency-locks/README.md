# Selective Gradle dependency locks

The lockfiles in this directory cover only each loader's `shadowBundle` configuration: external
libraries that are physically embedded in a Quick Skin release JAR. The file name includes the
loader and Minecraft version because Stonecutter's buildable projects live in generated version
directories that must not own tracked lock state.

Minecraft, mappings, loader development configurations, and Architectury/Loom transform
configurations are deliberately not version-locked. Project-owned dependency declarations use
exact versions, while Loom also creates internal configurations and may inject dynamic selectors.
The resolved external coordinates, artifacts, and metadata are pinned by
`gradle/verification-metadata.xml`, so an unreviewed resolution change still fails strict
verification. Locking the generated configurations produced high-churn, tool-version-specific
state without adding protection beyond that coordinate-specific SHA-256 allowlist.

Regenerate a loader lock after intentionally changing a shaded dependency:

```bash
./gradlew --no-daemon --no-parallel \
  :fabric:1.21.5:dependencies :neoforge:1.21.5:dependencies --write-locks
```

Review the resulting diff. A lockfile must contain only the expected `shadowBundle` entries and
must remain in this directory; never commit lock state generated below a `versions/` directory.
See [DEPENDENCY-SECURITY.md](../../DEPENDENCY-SECURITY.md) for the complete verification boundary
and upgrade procedure.
