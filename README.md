# QuickSkin - Minecraft 1.20.1 Fabric/Forge Mod

A cross-platform skin customization mod built with Architectury API for both Fabric and Forge.

## Features

✅ **Cross-Platform Support** - Works on both Fabric and Forge using Architectury API 9.2.14
✅ **HD Skins** - Support for skins up to 2048x1024 resolution
✅ **Animated Capes** - GIF support for animated capes
✅ **Service-Oriented Architecture** - Clean, maintainable codebase with focused services
✅ **Vanilla Rendering** - No external rendering libraries needed
✅ **Local Asset Management** - Automatic scanning and caching of local textures
✅ **Interactive GUI** - Modern skin selection menu with 3D preview
✅ **Mixin-Based Texture Swapping** - Seamless texture override system

## Architecture

### Module Structure
```
quick-skin-1.20.1-fabric-forge/
├── common/          # 90% of code - platform-agnostic
│   ├── src/main/java/com/quickskin/mod/
│   │   ├── client/
│   │   │   ├── gui/         # Phase 8: UI components
│   │   │   ├── input/       # Phase 4: Keybinds
│   │   │   ├── rendering/   # Phase 6: Vanilla rendering
│   │   │   └── services/    # Phase 2: Service layer
│   │   ├── common/
│   │   │   ├── data/        # Phase 2: Data models
│   │   │   └── util/        # Phase 5: Utilities
│   │   ├── config/          # Phase 10: Configuration
│   │   ├── event/           # Phase 4: Event handlers
│   │   ├── mixin/           # Phase 9: Mixins
│   │   ├── networking/      # Phase 3: Networking
│   │   └── platform/        # Phase 1: Platform abstraction
│   └── src/main/resources/
│       └── quickskin.mixins.json
├── forge/           # Forge-specific wrapper
│   └── src/main/java/com/quickskin/mod/
│       ├── forge/
│       └── platform/forge/  # Forge PlatformHelper implementation
├── fabric/          # Fabric-specific wrapper
│   └── src/main/java/com/quickskin/mod/
│       ├── fabric/
│       └── platform/fabric/ # Fabric PlatformHelper implementation
└── build.gradle     # Multi-module Gradle configuration
```

### Key Systems

#### Phase 1: Foundation
- Multi-module Gradle project with Architectury
- Platform abstraction layer (@ExpectPlatform)
- Entry points for Fabric and Forge

#### Phase 2: Service Layer
- **SkinService** - Skin texture management
- **CapeService** - Cape texture management
- **ModelService** - Model type detection (slim/classic)
- **PlayerAppearanceService** - Main coordinator service
- **PlayerAppearanceRepository** - Single source of truth

#### Phase 3: Networking
- Cross-platform networking with Architectury's NetworkManager
- 11 packet types (5 C2S, 6 S2C)
- Thread-safe packet handling with context.queue()

#### Phase 4: Event Handling
- Common events (server-side)
- Client events (player join/quit, GUI, tick)
- Keybind registry (K key for menu)

#### Phase 5: Asset Management
- **LocalAssetManager** - Filesystem scanning and caching
- **SkinModelDetector** - Auto-detection of slim vs classic
- **HDTextureProcessor** - HD skin processing
- **GifUtil** - Animated cape processing
- Hash-based asset identification (SHA1)
- Multi-quality texture system (FULL, PREVIEW, THUMBNAIL, NORMALIZED)

#### Phase 6: Vanilla Rendering
- **PreviewPlayerData** - State holder for 3D previews
- **PlayerModelRenderer** - Vanilla PlayerModel rendering
- **CapeRenderer** - Static and animated cape rendering
- No GeckoLib dependency

#### Phase 7: Animation System
- **AnimatedTextureManager** - Frame management
- **AnimationMetadata** - Time-based frame data
- GIF to PNG atlas conversion

#### Phase 8: GUI System
- **PlayerSkinMenuScreen** - Main menu (K key)
- **PlayerWidget** - 3D rotating player preview with mouse interaction
- **SkinListWidget** - Scrollable skin list
- **SkinEntry** - Individual skin entries with thumbnails
- Auto-rotation, drag-to-rotate, scroll-to-zoom
- Model type buttons (Auto/Slim/Classic)
- Action buttons (Import/Cape/Settings)

#### Phase 9: Mixins
- **PlayerInfoMixin** - Intercepts getSkinLocation, getModelName, getCapeLocation
- Texture registration with Minecraft's TextureManager
- BufferedImage → NativeImage conversion
- Dynamic texture loading

#### Phase 10: Configuration
- **ClientConfig** - Client-side settings (JSON)
- **ServerConfig** - Server-side settings (JSON)
- Auto-save/load from config directory

## Usage

### For Players

1. **Open the skin menu**: Press `K` (configurable)
2. **Browse skins**: Scroll through your local skins in the left panel
3. **Preview**: Click a skin to see it on the 3D model
4. **Interact with preview**:
   - Drag to rotate
   - Scroll to zoom
   - Hover for head tracking
5. **Select model type**: Choose Auto/Slim/Classic
6. **Apply**: Click to apply the skin

### Adding Custom Skins

Place `.png` skin files in:
```
.minecraft/quickskin/skins/
```

Place `.png` or `.gif` cape files in:
```
.minecraft/quickskin/capes/
```

The mod will automatically detect and cache them on startup.

### HD Skin Support

QuickSkin supports HD skins up to 2048x1024:
- Standard: 64x64
- HD 2x: 128x64
- HD 4x: 256x128
- HD 8x: 512x256
- HD 16x: 1024x512
- HD 32x: 2048x1024

## Development

### Building

```bash
./gradlew build
```

Output JARs:
- `fabric/build/libs/quickskin-<version>-fabric.jar`
- `forge/build/libs/quickskin-<version>-forge.jar`

### Project Structure

- **Common Module** (90% of code): Platform-agnostic logic
- **Forge Module** (5%): Forge-specific entry points
- **Fabric Module** (5%): Fabric-specific entry points

### Key Design Patterns

- **Singleton**: Services use singleton pattern
- **Repository**: PlayerAppearanceRepository as data store
- **Observer**: Event system for decoupled communication
- **Strategy**: Platform-specific implementations via @ExpectPlatform
- **Facade**: PlayerAppearanceService coordinates other services

## Dependencies

- **Minecraft**: 1.20.1
- **Forge**: 47.4.0
- **Fabric Loader**: 0.15.11
- **Architectury API**: 9.2.14
- **Yarn Mappings**: 1.20.1+build.1
- **Java**: 17

## Compatibility

- Compatible with Optifine/Oculus
- SkinLayers3D compatibility (optional)
- Lower priority mixins to allow other mods to run first

## License

All rights reserved.

## Credits

Built with Architectury API for cross-platform compatibility.
