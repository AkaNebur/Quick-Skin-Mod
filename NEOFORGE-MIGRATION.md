# NeoForge 1.21.1 Migration Summary

This project now supports both Minecraft 1.20.1 and 1.21.1 with the following loaders:

## Version Structure

### Minecraft 1.20.1 (Java 17)
- **common** - Shared code for Fabric and Forge
- **fabric** - Fabric-specific implementation
- **forge** - Forge-specific implementation

### Minecraft 1.21.1 (Java 21)
- **common-1.21** - Shared code for Fabric and NeoForge
- **fabric-1.21** - Fabric-specific implementation for 1.21.1
- **neoforge** - NeoForge-specific implementation

## Building

### Build 1.20.1 Version (Fabric + Forge merged)
```bash
./gradlew build
./gradlew mergeJars
```
Output: `build/forgix/quick-skin.jar` (merged Fabric + Forge)

### Build 1.21.1 Versions (Fabric and NeoForge separately)
```bash
./gradlew build121
```
Outputs:
- `fabric-1.21/build/libs/quick-skin-fabric-1.21-1.0.0.jar`
- `neoforge/build/libs/quick-skin-neoforge-1.0.0.jar`

**Note:** For 1.21.1, Fabric and NeoForge are separate JARs since they're different mod loaders. Users choose either Fabric OR NeoForge, not both.

### Build Everything
```bash
./gradlew build
./gradlew mergeJars
./gradlew build121
```

## Running in Development

### 1.20.1 Versions
- Fabric: `./gradlew :fabric:runClient`
- Forge: `./gradlew :forge:runClient`

### 1.21.1 Versions
- Fabric: `./gradlew :fabric-1.21:runClient`
- NeoForge: `./gradlew :neoforge:runClient`

## Key Changes Made

1. **gradle.properties** - Added 1.21.1 version properties
2. **settings.gradle** - Included new 1.21 modules and NeoForge maven repository
3. **build.gradle** - Updated to:
   - Conditionally use Java 17 for 1.20.1 modules
   - Use Java 21 for 1.21.1 modules
   - Configure separate Minecraft versions per module
   - Added `forgix121` task for 1.21.1 merged JAR
4. **Module Structure** - Duplicated common and fabric to common-1.21 and fabric-1.21
5. **neoforge module** - Created new NeoForge module with:
   - NeoForge-specific build.gradle
   - neoforge.mods.toml metadata file
   - Source files copied from forge module

## Dependencies

### 1.20.1
- Minecraft: 1.20.1
- Fabric Loader: 0.17.3
- Fabric API: 0.92.6+1.20.1
- Forge: 47.4.9
- Architectury: 9.2.14

### 1.21.1
- Minecraft: 1.21.1
- Fabric Loader: 0.16.9
- Fabric API: 0.110.0+1.21.1
- NeoForge: 21.1.77
- Architectury: 13.0.6

## Migration Notes

- The 1.20.1 and 1.21.1 codebases are completely separate
- Bug fixes need to be applied to both versions manually
- You can gradually migrate features from 1.20.1 to 1.21.1
- NeoForge is the successor to Forge for Minecraft 1.20.5+

## Next Steps

1. Test the build with `./gradlew build`
2. Update any API calls that changed between 1.20.1 and 1.21.1
3. Test both versions in-game
4. Update README.md with new version information
