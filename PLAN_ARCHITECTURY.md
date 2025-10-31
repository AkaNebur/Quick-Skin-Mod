# QuickSkin Cross-Platform Rewrite Plan (Architectury)

## Preamble: Core Philosophy and High-Level Goals

This plan adapts the original QuickSkin rewrite to work **cross-platform** with both **Fabric and Forge** using **Architectury API 9.2.14**. The primary goal is to deconstruct the monolithic Forge-only structure into a modular, service-oriented architecture that works seamlessly on both platforms.

**Key Objectives:**

1. **Cross-Platform Support:** Use Architectury to maintain a single codebase (90% common code) that works on both Fabric and Forge.
2. **Decoupling:** Break down large classes like `ClientSkinManager` into smaller, single-responsibility services.
3. **Removing GeckoLib:** Replace the 3D player preview with a vanilla-based entity rendering system.
4. **Performance & Scalability:** Optimize animations using texture atlases and UV manipulation instead of hundreds of individual textures.
5. **State Management:** Introduce a centralized repository for player appearance data.
6. **Maintainability:** Adopt modern design patterns and leverage Architectury's abstraction layer.
7. **Preserve GUI:** Keep the existing GUI structure and styling that you like.

---

## Phase 0: Architectury Project Structure Setup

The project already has the basic Architectury structure. We'll organize code following Architectury best practices.

### 0.1. Module Organization

```
quick-skin-1.20.1-fabric-forge/
├── common/                          # 90% of code - platform-agnostic
│   └── src/main/
│       ├── java/com/quickskin/mod/
│       │   ├── QuickSkin.java                    # Main entry point
│       │   ├── QuickSkinClient.java              # Client initialization
│       │   ├── platform/                         # @ExpectPlatform abstractions
│       │   │   └── PlatformHelper.java
│       │   ├── client/                           # Client-only code (@Environment)
│       │   │   ├── gui/                          # All GUI screens
│       │   │   ├── widgets/                      # Custom widgets (PlayerWidget)
│       │   │   ├── rendering/                    # Custom renderers, preview system
│       │   │   ├── event/                        # Client event handlers
│       │   │   └── services/                     # Client-side services
│       │   ├── common/
│       │   │   ├── data/                         # Data objects (SkinData, PlayerAppearance)
│       │   │   ├── event/                        # Common event handlers
│       │   │   └── services/                     # Service interfaces
│       │   ├── core/
│       │   │   ├── animation/                    # New animation engine
│       │   │   ├── assets/                       # Local asset management
│       │   │   └── compat/                       # Compatibility modules
│       │   ├── networking/
│       │   │   ├── ModNetworking.java            # Architectury network registration
│       │   │   ├── ClientNetworking.java         # Client-side network handlers
│       │   │   ├── ServerNetworkHandler.java     # Server-side handlers
│       │   │   └── packets/                      # Packet classes
│       │   ├── server/
│       │   │   ├── data/                         # Server-side caches
│       │   │   └── event/                        # Server event handlers
│       │   ├── mixin/                            # Mixins (work on both platforms)
│       │   │   ├── PlayerInfoMixin.java
│       │   │   └── CapeLayerMixin.java
│       │   └── util/                             # Utilities
│       └── resources/
│           ├── quickskin.mixins.json             # Mixin configuration
│           ├── architectury.common.json          # Architectury metadata
│           └── assets/quickskin/                 # Textures, lang files
│
├── forge/                           # Forge-specific code (thin wrapper)
│   └── src/main/
│       ├── java/com/quickskin/mod/forge/
│       │   ├── QuickSkinForge.java               # @Mod entry point
│       │   ├── QuickSkinForgeClient.java         # Forge client entry
│       │   └── platform/
│       │       └── PlatformHelperImpl.java       # @ExpectPlatform impl
│       └── resources/
│           └── META-INF/mods.toml                # Forge metadata
│
├── fabric/                          # Fabric-specific code (thin wrapper)
│   └── src/main/
│       ├── java/com/quickskin/mod/fabric/
│       │   ├── QuickSkinFabric.java              # ModInitializer
│       │   ├── QuickSkinFabricClient.java        # ClientModInitializer
│       │   └── platform/
│       │       └── PlatformHelperImpl.java       # @ExpectPlatform impl
│       └── resources/
│           ├── fabric.mod.json                   # Fabric metadata
│           └── quickskin.accesswidener           # Access wideners if needed
│
├── build.gradle                     # Root build configuration
├── gradle.properties                # Version properties
└── settings.gradle                  # Module configuration
```

### 0.2. Key Architectury Principles

1. **Common Module:** Contains 90% of the code - all business logic, GUI, rendering, networking, events, mixins
2. **Platform Modules:** Only entry points and `@ExpectPlatform` implementations
3. **@Environment Annotations:** Use for client-only code in common module
4. **Architectury APIs:** Use for networking, events, platform utilities
5. **Mixins in Common:** They work cross-platform when placed in common module

---

## Phase 1: Core Architecture Redesign (Common Module)

### 1.1. Service-Oriented Refactor

Replace the monolithic `ClientSkinManager` with distinct services in the common module:

**Client Services** (`com.quickskin.mod.client.services`):
- `PlayerAppearanceService`: Central coordinator, delegates to other services
- `SkinService`: Manages player skins (Mojang API, local assets, ResourceLocation)
- `CapeService`: Manages capes (local, known, Mojang)
- `AnimationService`: New animation engine (timing, texture atlas, UV coords)
- `ModelService`: Manages player model types (classic/slim/auto-detection)
- `AssetService`: File I/O, hashing, caching for local skins/capes

**Repository** (`com.quickskin.mod.common.data`):
- `PlayerAppearanceRepository`: Single source of truth - `Map<UUID, PlayerAppearance>`
- `PlayerAppearance`: Record holding `skinId`, `capeId`, `model`, resolved `ResourceLocation`s

### 1.2. Internal Event Bus

Simple custom event bus for service communication:
- `PlayerAppearanceUpdateEvent`: Fired when player look changes
- `LocalAssetReloadEvent`: Fired when assets are imported/deleted
- `ServerConfigSyncEvent`: Fired on server config sync

### 1.3. Platform Abstraction

**Common Module Interface** (`com.quickskin.mod.platform.PlatformHelper`):
```java
@ExpectPlatform
public static Path getSkinsDirectory() { throw new AssertionError(); }

@ExpectPlatform
public static Path getCapesDirectory() { throw new AssertionError(); }

@ExpectPlatform
public static Path getConfigDirectory() { throw new AssertionError(); }

@ExpectPlatform
public static boolean isModLoaded(String modId) { throw new AssertionError(); }
```

**Forge Implementation** (`com.quickskin.mod.forge.platform.PlatformHelperImpl`):
```java
public static Path getSkinsDirectory() {
    return FMLPaths.GAMEDIR.get().resolve("quickskin/skins");
}
// ... etc using FMLPaths, ModList
```

**Fabric Implementation** (`com.quickskin.mod.fabric.platform.PlatformHelperImpl`):
```java
public static Path getSkinsDirectory() {
    return FabricLoader.getInstance().getGameDir().resolve("quickskin/skins");
}
// ... etc using FabricLoader
```

---

## Phase 2: GeckoLib Replacement (3D Preview) - Common Module

### 2.1. Create `PreviewPlayerEntity`

Location: `com.quickskin.mod.client.rendering.PreviewPlayerEntity`

- Extends a simple entity (or custom base class, NOT a real Player)
- Exists only on client, not added to world
- Holds appearance data: skin, cape, model type, animation state
- Annotate with `@Environment(EnvType.CLIENT)`

### 2.2. Implement Custom Renderer

Location: `com.quickskin.mod.client.rendering.PreviewPlayerRenderer`

- Extends `PlayerRenderer` to reuse vanilla logic
- Fetches skin/cape/model from `PreviewPlayerEntity` instance
- Annotate with `@Environment(EnvType.CLIENT)`

### 2.3. Rewrite `PlayerWidget`

Location: `com.quickskin.mod.client.widgets.PlayerWidget`

**IMPORTANT:** Preserve existing GUI layout and styling that you like!

- Contains instance of `PreviewPlayerEntity`
- Uses `EntityRenderDispatcher.render()` in `render()` method
- Uses `renderEntityInInventoryFollowsMouse()` for rotation/lighting
- `setAnimation()` updates state on `PreviewPlayerEntity`
- Manually call `previewPlayer.tick()` each frame
- **Keep all existing positioning, sizing, and visual styling**
- Annotate with `@Environment(EnvType.CLIENT)`

### 2.4. Preview Animation Controller

Location: `com.quickskin.mod.client.rendering.PreviewAnimationController`

- Manages limb swing, head rotation for preview
- Handles animation states (walking, idle, etc.)
- Called from `PlayerWidget.render()`
- Annotate with `@Environment(EnvType.CLIENT)`

---

## Phase 3: Animation System Rework (Common Module)

### 3.1. Atlas-Based Animation

**Processing** (`com.quickskin.mod.core.assets.AssetService`):
- When animated cape (GIF/PNG strip) imported, process into vertical texture atlas
- Store timing data in `.json` metadata file (preserve existing format)
- One texture per animation instead of hundreds

**Management** (`com.quickskin.mod.core.animation.AnimationService`):
- Track active animations: `Map<String, AnimationState>`
- For each animation: current frame index, time until next frame
- Provide UV coordinates for current frame

### 3.2. Rendering via `CapeLayerMixin`

Location: `com.quickskin.mod.mixin.CapeLayerMixin`

- Mixin into `CapeLayer` to intercept rendering
- Check `CapeService` if player has custom cape
- If animated, ask `AnimationService` for current frame UV coords
- Use custom `RenderType` or `VertexConsumer` with modified UVs
- Only one texture (atlas) bound per cape - huge GPU/memory savings

---

## Phase 4: GUI System (Common Module) - PRESERVE EXISTING!

**CRITICAL:** You like the current GUI setup, so keep it mostly as-is, just refactor for MVP pattern.

### 4.1. MVP Implementation

Location: `com.quickskin.mod.client.gui`

**Model:** `PlayerAppearanceRepository`, `Config` files

**View:** Existing screens with `@Environment(EnvType.CLIENT)`:
- `PlayerSkinMenuScreen`
- `CapeSelectionScreen`
- `SettingsScreen`
- `DeletionConfirmScreen`
- `RenameScreen`

**Presenter:** `SkinMenuLogicHandler` (promote to full Presenter)
- Receives input from View
- Interacts with services (`PlayerAppearanceService`, `AssetService`)
- Tells View how to update

**Keep All Existing:**
- Widget styles (`ButtonFactory`, `StyledButton`, `DangerButton`, `TabButton`)
- Visual effects (`StarfieldBackground`, `BlurHandler`)
- Layout logic (`GuiScaleManager`, `UIScalingHelper`)
- Custom widgets (`LinkButton`, `RotateButton`)

### 4.2. Widget Injection

Instead of fragile logic in `ClientEvents`, use **Architectury events** in common module:

```java
@Environment(EnvType.CLIENT)
public class ClientEvents {
    public static void init() {
        // Use Architectury's client GUI events
        ClientGuiEvent.INIT_POST.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof TitleScreen || screen instanceof PauseScreen) {
                // Inject PlayerWidget and buttons with robust positioning
            }
        });
    }
}
```

---

## Phase 5: Networking (Common Module with Architectury)

### 5.1. Network Registration

Location: `com.quickskin.mod.networking.ModNetworking`

```java
public class ModNetworking {
    public static final ResourceLocation UPLOAD_SKIN =
        new ResourceLocation("quickskin", "upload_skin");
    public static final ResourceLocation SYNC_SKIN =
        new ResourceLocation("quickskin", "sync_skin");
    public static final ResourceLocation UPLOAD_CAPE =
        new ResourceLocation("quickskin", "upload_cape");
    public static final ResourceLocation REQUEST_TEXTURE =
        new ResourceLocation("quickskin", "request_texture");

    public static void init() {
        // Register C2S receivers
        NetworkManager.registerReceiver(NetworkManager.c2s(),
            UPLOAD_SKIN, ServerNetworkHandler::handleUploadSkin);
        NetworkManager.registerReceiver(NetworkManager.c2s(),
            UPLOAD_CAPE, ServerNetworkHandler::handleUploadCape);
        NetworkManager.registerReceiver(NetworkManager.c2s(),
            REQUEST_TEXTURE, ServerNetworkHandler::handleRequestTexture);
    }
}
```

### 5.2. Client Network Registration

Location: `com.quickskin.mod.networking.ClientNetworking`

```java
@Environment(EnvType.CLIENT)
public class ClientNetworking {
    public static void init() {
        // Register S2C receivers
        NetworkManager.registerReceiver(NetworkManager.s2c(),
            ModNetworking.SYNC_SKIN, ClientNetworkHandler::handleSkinSync);
    }
}
```

### 5.3. Packet Handlers

Location: `com.quickskin.mod.networking.ServerNetworkHandler` and `ClientNetworkHandler`

**CRITICAL:** Always use `context.queue()` for game state access!

```java
public static void handleUploadSkin(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
    String skinId = buf.readUtf();
    byte[] imageData = buf.readByteArray();

    context.queue(() -> {
        ServerPlayer player = (ServerPlayer) context.getPlayer();
        // Process skin upload
        // Store to server cache
        // Sync to other players
    });
}
```

### 5.4. Consolidate Packets

Consider consolidating existing packets:
- Single `C2S_UpdateAppearancePacket(PlayerAppearance)` instead of separate skin/cape packets
- Use chunking for large textures (preserve existing `TextureChunker` logic)
- Keep server config sync packets

---

## Phase 6: Event Handling (Common Module with Architectury)

### 6.1. Common Events

Location: `com.quickskin.mod.common.event.CommonEvents`

```java
public class CommonEvents {
    public static void init() {
        PlayerEvent.PLAYER_JOIN.register(player -> {
            // Send saved skin/cape to joining player
        });

        PlayerEvent.PLAYER_QUIT.register(player -> {
            // Cleanup player data from cache
        });

        LifecycleEvent.SERVER_STARTING.register(server -> {
            // Load server-side skin storage
        });
    }
}
```

### 6.2. Client Events

Location: `com.quickskin.mod.client.event.ClientEvents`

```java
@Environment(EnvType.CLIENT)
public class ClientEvents {
    public static void init() {
        ClientTickEvent.CLIENT_POST.register(client -> {
            // Periodic updates (animation ticks, etc.)
        });

        ClientPlayerEvent.CLIENT_PLAYER_JOIN.register(player -> {
            // Initialize client-side skin cache
            // Load local skins
        });

        ClientRawInputEvent.KEY_PRESSED.register((client, key, scan, action, mods) -> {
            // Handle keybinds for skin menu
            return EventResult.pass();
        });

        ClientGuiEvent.INIT_POST.register((client, screen, width, height) -> {
            // Inject widgets into title/pause screens
        });
    }
}
```

---

## Phase 7: Initialization Flow

### 7.1. Main Entry Points

**Common Module** (`com.quickskin.mod.QuickSkin`):
```java
public class QuickSkin {
    public static final String MOD_ID = "quickskin";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static void init() {
        LOGGER.info("Initializing QuickSkin");

        // Initialize storage
        AssetService.init();

        // Register networking
        ModNetworking.init();

        // Register events
        CommonEvents.init();

        // Load server config
        ServerConfig.load();
    }
}
```

**Common Client Module** (`com.quickskin.mod.QuickSkinClient`):
```java
@Environment(EnvType.CLIENT)
public class QuickSkinClient {
    public static void init() {
        LOGGER.info("Initializing QuickSkin Client");

        // Register client networking
        ClientNetworking.init();

        // Register client events
        ClientEvents.init();

        // Load client config
        ClientConfig.load();

        // Initialize services
        PlayerAppearanceService.init();
        SkinService.init();
        CapeService.init();
        AnimationService.init();
    }
}
```

### 7.2. Forge Entry Points

**Forge Module** (`com.quickskin.mod.forge.QuickSkinForge`):
```java
@Mod(QuickSkin.MOD_ID)
public class QuickSkinForge {
    public QuickSkinForge() {
        // CRITICAL: Register event bus with Architectury
        EventBuses.registerModEventBus(
            QuickSkin.MOD_ID,
            FMLJavaModLoadingContext.get().getModEventBus()
        );

        QuickSkin.init();
    }
}
```

**Forge Client** (`com.quickskin.mod.forge.QuickSkinForgeClient`):
```java
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class QuickSkinForgeClient {
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(QuickSkinClient::init);
    }
}
```

### 7.3. Fabric Entry Points

**Fabric Module** (`com.quickskin.mod.fabric.QuickSkinFabric`):
```java
public class QuickSkinFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        QuickSkin.init();
    }
}
```

**Fabric Client** (`com.quickskin.mod.fabric.QuickSkinFabricClient`):
```java
public class QuickSkinFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        QuickSkinClient.init();
    }
}
```

### 7.4. Metadata Files

**Forge** (`META-INF/mods.toml`):
```toml
modLoader="javafml"
loaderVersion="[47,)"
license="MIT"

[[mods]]
modId="quickskin"
version="${file.jarVersion}"
displayName="QuickSkin"

[[dependencies.quickskin]]
modId="forge"
mandatory=true

[[dependencies.quickskin]]
modId="architectury"
mandatory=true

[[mixins]]
config="quickskin.mixins.json"
```

**Fabric** (`fabric.mod.json`):
```json
{
  "schemaVersion": 1,
  "id": "quickskin",
  "version": "${version}",
  "name": "QuickSkin",
  "environment": "*",
  "entrypoints": {
    "main": ["com.quickskin.mod.fabric.QuickSkinFabric"],
    "client": ["com.quickskin.mod.fabric.QuickSkinFabricClient"]
  },
  "mixins": ["quickskin.mixins.json"],
  "depends": {
    "fabric-api": "*",
    "architectury": "*"
  }
}
```

---

## Phase 8: Compatibility & Cleanup

### 8.1. Compatibility

**SkinLayers3D** (`com.quickskin.mod.core.compat.SkinLayers3DCompatibility`):
- Preserve existing reflection-based approach
- Check if mod loaded: `PlatformHelper.isModLoaded("skinlayers3d")`
- Works identically on both platforms

### 8.2. Configuration

**Common Module** (`com.quickskin.mod.config`):
- `ClientConfig.java` (with `@Environment(EnvType.CLIENT)`)
- `ServerConfig.java`
- Use simple JSON files with Gson
- Paths via `PlatformHelper.getConfigDirectory()`

### 8.3. Web Server

**Common Module** (`com.quickskin.mod.features.webserver`):
- Entire web server feature remains in common
- Works identically on both platforms
- Keep all existing handlers, rate limiting, JSON utilities

---

## Implementation Order (Phase-by-Phase)

### Phase 1: Foundation Setup (Week 1)
1. ✅ Architectury project structure already exists
2. Create `QuickSkin.java` and `QuickSkinClient.java` in common
3. Create Forge/Fabric entry points
4. Set up `PlatformHelper` with `@ExpectPlatform` methods
5. Verify project builds and runs on both platforms (empty mod)

### Phase 2: Service Layer (Week 1-2)
1. Implement `PlayerAppearanceRepository` in common
2. Create service interfaces in `com.quickskin.mod.common.services`
3. Implement client services in `com.quickskin.mod.client.services`
4. Migrate logic from `ClientSkinManager` to new services
5. Test service initialization on both platforms

### Phase 3: Networking (Week 2)
1. Set up `ModNetworking` with Architectury's `NetworkManager`
2. Create packet classes
3. Implement `ServerNetworkHandler` and `ClientNetworkHandler`
4. Test packet sending/receiving on both Fabric and Forge
5. Verify chunking for large textures works

### Phase 4: Events (Week 2-3)
1. Implement `CommonEvents` with Architectury events
2. Implement `ClientEvents` with Architectury client events
3. Test player join/quit, lifecycle events on both platforms
4. Verify client events (tick, GUI, input) work

### Phase 5: Asset Management (Week 3)
1. Create `AssetService` in common
2. Implement skin/cape storage with `PlatformHelper` paths
3. Port HD texture processing
4. Test local file I/O on both platforms

### Phase 6: GeckoLib Replacement (Week 3-4)
1. Create `PreviewPlayerEntity` (vanilla-based)
2. Implement `PreviewPlayerRenderer`
3. Rewrite `PlayerWidget` to use new preview system
4. **Preserve all existing styling and layout!**
5. Create `PreviewAnimationController`
6. Test preview rendering on both platforms
7. Remove all GeckoLib dependencies from build.gradle

### Phase 7: Animation System (Week 4)
1. Implement new `AnimationService` with atlas support
2. Create `CapeLayerMixin` with UV manipulation
3. Migrate animation metadata processing
4. Test animated capes on both platforms
5. Deprecate `AnimatedTextureManager`

### Phase 8: GUI Migration (Week 4-5)
1. Port all screen classes to common module with `@Environment(EnvType.CLIENT)`
2. **Keep all existing screens as-is!** Just add annotations
3. Port widget classes (preserve styling)
4. Refactor `SkinMenuLogicHandler` as Presenter (MVP pattern)
5. Implement GUI event-based widget injection
6. Test all screens on both Fabric and Forge

### Phase 9: Mixins (Week 5)
1. Move mixins to common module
2. Configure `quickskin.mixins.json`
3. Test `PlayerInfoMixin` on both platforms
4. Test `CapeLayerMixin` on both platforms
5. Reference mixin config in Forge/Fabric metadata

### Phase 10: Configuration & Compat (Week 5-6)
1. Implement `ClientConfig` and `ServerConfig`
2. Port `SkinLayers3DCompatibility` to common
3. Port web server to common
4. Test config loading on both platforms

### Phase 11: Testing & Polish (Week 6)
1. Full integration testing on Fabric
2. Full integration testing on Forge
3. Test cross-play (Fabric client + Forge server, vice versa)
4. Performance profiling (animation system, networking)
5. Bug fixes and optimizations

### Phase 12: Documentation & Release
1. Update README with Fabric/Forge support
2. Create CHANGELOG
3. Build unified JAR with Forgix
4. Test final JAR on both platforms
5. Release!

---

## Critical Architectury Considerations

### Client/Server Separation
- **Use `@Environment(EnvType.CLIENT)` for ALL client-only classes** in common module
- Never reference client classes from common code without environment checks
- Separate initialization: `QuickSkin.init()` vs `QuickSkinClient.init()`

### Networking Best Practices
- Always use `context.queue()` in packet handlers
- Check `NetworkManager.canPlayerReceive()` before sending S2C
- Create new buffers for each packet
- Validate client data on server

### Event Handling
- Return `EventResult.pass()` if not interrupting
- Register during initialization, not lazily
- Keep handlers lightweight (especially tick events)

### Platform Abstraction
- Use Architectury APIs first before `@ExpectPlatform`
- Keep `@ExpectPlatform` methods simple - thin wrappers only
- Match signatures exactly in implementations

### Mixin Compatibility
- Mixins in common module work on both platforms
- Use `client` array for client-only mixins
- Reference in both Forge and Fabric metadata

---

## Migration from Existing Code

### What to Preserve Exactly As-Is
- GUI screens (just add `@Environment(EnvType.CLIENT)`)
- Widget styling and layout
- Visual effects (starfield, blur)
- Button factories and custom buttons
- Local storage hashing and file I/O logic
- Web server implementation
- SkinLayers3D compatibility
- Animation metadata format
- Texture processing algorithms

### What to Rewrite
- `ClientSkinManager` → Multiple services
- GeckoLib preview → Vanilla entity rendering
- Forge events → Architectury events
- Forge networking → Architectury networking
- `AnimatedTextureManager` → Atlas-based `AnimationService`
- Mod entry points → Architectury pattern

### What to Split by Platform
- Config directory paths (`@ExpectPlatform`)
- Mod loading checks (`@ExpectPlatform`)
- Loader-specific entry points (Forge @Mod, Fabric ModInitializer)

---

## Testing Strategy

### Unit Testing
- `SkinModelDetector` (works on both platforms)
- Asset hashing
- Animation metadata parsing
- UV coordinate calculation

### Integration Testing
- **Fabric Client + Fabric Server:** Full feature testing
- **Forge Client + Forge Server:** Full feature testing
- **Cross-platform:** Fabric client → Forge server, Forge client → Fabric server
- Skin upload/download
- Cape application
- Animation playback
- GUI functionality
- Web server integration

### Performance Testing
- Animation system GPU/memory usage
- Network packet size and frequency
- Client tick performance
- Renderer performance

---

## Dependencies (Already Configured)

```gradle
// Root build.gradle
plugins {
    id 'dev.architectury.loom' version '1.11-SNAPSHOT'
    id 'architectury-plugin' version '3.4-SNAPSHOT'
    id 'io.github.pacifistmc.forgix' version '2.0.0-SNAPSHOT.5.1'
}

// Common module
dependencies {
    modImplementation "dev.architectury:architectury:${architectury_api_version}"
}

// Forge module
dependencies {
    forge "net.minecraftforge:forge:${forge_version}"
    modApi "dev.architectury:architectury-forge:${architectury_api_version}"
}

// Fabric module
dependencies {
    modImplementation "net.fabricmc:fabric-loader:${fabric_loader_version}"
    modApi "net.fabricmc.fabric-api:fabric-api:${fabric_api_version}"
    modApi "dev.architectury:architectury-fabric:${architectury_api_version}"
}
```

---

## Summary of Architectural Changes

| Aspect | Old (Forge-Only) | New (Architectury) |
|--------|-----------------|-------------------|
| **Code Organization** | Single module | common/forge/fabric modules (90% common) |
| **Entry Points** | `@Mod` class | `@Mod` + `ModInitializer` via thin wrappers |
| **Networking** | Forge packets | Architectury `NetworkManager` (cross-platform) |
| **Events** | Forge events | Architectury events (cross-platform) |
| **Client Code** | Mixed | `@Environment(EnvType.CLIENT)` in common |
| **Platform-Specific** | N/A | `@ExpectPlatform` for paths, mod checks |
| **Mixins** | Forge mixin config | Shared mixin config (both platforms) |
| **Preview System** | GeckoLib | Vanilla entity rendering |
| **Animations** | Dynamic textures per frame | Texture atlases + UV manipulation |
| **Architecture** | Monolithic `ClientSkinManager` | Service-oriented with repository pattern |
| **GUI** | Forge events for injection | Architectury client GUI events |
| **Build Output** | Forge JAR only | Unified JAR (Forgix) for both platforms |

---

## Success Criteria

✅ Mod runs on both Fabric and Forge 1.20.1
✅ Single unified JAR works on both platforms
✅ All GUI features preserved and functional
✅ Skin/cape upload and sync works cross-platform
✅ Animations perform better (atlas-based)
✅ No GeckoLib dependency
✅ Web server works on both platforms
✅ SkinLayers3D compatibility maintained
✅ Config system works on both loaders
✅ Clean, maintainable codebase with service architecture
✅ Cross-play works (Fabric client + Forge server, vice versa)

---

This plan provides a complete roadmap for rebuilding QuickSkin as a cross-platform mod using Architectury, while preserving the GUI and features you love. The phased approach ensures incremental progress with testable milestones.
