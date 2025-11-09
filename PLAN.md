Of course! I can help you implement a server-level cooldown for skin changes. This will involve adding a new server configuration, tracking skin changes on the server, and providing feedback to the user on the client. Here is a step-by-step plan and the corresponding code changes:

### Plan

1.  **Update Server Configuration:** Add a new setting `skinChangeCooldownSeconds` to `ServerConfig.java`.
2.  **Create a Server-side Cooldown Manager:** A new class, `ServerCooldownManager`, will track the last time each player changed their skin.
3.  **Enforce Cooldown on Server:** Modify `ServerNetworkHandler` to check the cooldown before applying a skin change. If the change is successful, it will start a new cooldown and notify the client.
4.  **Create a Client-side Cooldown Service:** A new class, `CooldownService`, will store the player's current cooldown status on the client.
5.  **Update Networking:** Add a new packet to sync cooldown information from the server to the client.
6.  **Modify the Skin Menu GUI:** Update `PlayerSkinMenuScreen` and `ActionButtonsPanel` to lock the "Done" button and display the remaining cooldown time.
7.  **Prevent Spam:** Add a client-side check to prevent sending skin change requests while on cooldown.

Here are the code modifications for each step:

### 1. Update Server Configuration

I'll add the new cooldown setting to `ServerConfig.java`.

```java
// common/src/main/java/com/quickskin/mod/config/ServerConfig.java
package com.quickskin.mod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.platform.PlatformHelper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Server-side configuration for QuickSkin
 * Stored in JSON format in config directory
 */
public class ServerConfig {
    private static ServerConfig instance;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Skin Settings
    public boolean allowCustomSkins = true;
    public boolean allowHDSkins = true;
    public int maxSkinResolution = 2048;
    public boolean disableSkinTransparency = false; // Disable transparency in player skins
    public int skinChangeCooldownSeconds = 60; // Cooldown in seconds for changing skin

    // Cape Settings
    public boolean allowCustomCapes = true;
    public boolean allowAnimatedCapes = true;

    // Performance Settings
    public int maxTextureSize = 2048 * 1024; // 2MB max file size

    // Security Settings
    public boolean requireAuthentication = false;

    private ServerConfig() {
        // Private constructor for singleton
    }

    public static ServerConfig getInstance() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    /**
     * Load configuration from file
     */
    private static ServerConfig load() {
        Path configPath = getConfigPath();

        if (Files.exists(configPath)) {
            try {
                String json = Files.readString(configPath);
                ServerConfig config = GSON.fromJson(json, ServerConfig.class);
                QuickSkin.LOGGER.info("Loaded server configuration");
                return config;
            } catch (Exception e) {
                QuickSkin.LOGGER.error("Failed to load server configuration, using defaults", e);
            }
        }

        // Return default config and save it
        ServerConfig config = new ServerConfig();
        config.save();
        return config;
    }

    /**
     * Save configuration to file
     */
    public void save() {
        Path configPath = getConfigPath();

        try {
            // Ensure config directory exists
            Files.createDirectories(configPath.getParent());

            String json = GSON.toJson(this);
            Files.writeString(configPath, json);
            QuickSkin.LOGGER.debug("Saved server configuration");
        } catch (IOException e) {
            QuickSkin.LOGGER.error("Failed to save server configuration", e);
        }
    }

    /**
     * Get config file path
     */
    private static Path getConfigPath() {
        return PlatformHelper.getConfigDirectory().resolve("quickskin-server.json");
    }

    /**
     * Reload configuration from file
     */
    public static void reload() {
        instance = load();
    }

    /**
     * Convert to JSON for network transmission
     */
    public String toJson() {
        return GSON.toJson(this);
    }

    /**
     * Create from JSON (for network reception)
     */
    public static ServerConfig fromJson(String json) {
        try {
            return GSON.fromJson(json, ServerConfig.class);
        } catch (Exception e) {
            QuickSkin.LOGGER.error("Failed to parse server config from JSON", e);
            return new ServerConfig();
        }
    }
}
```

### 2. Create Server-side Cooldown Manager

I'll create a new class to manage cooldowns on the server.

```java
// common/src/main/java/com/quickskin/mod/server/data/ServerCooldownManager.java
package com.quickskin.mod.server.data;

import com.quickskin.mod.config.ServerConfig;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages skin change cooldowns for players on the server.
 */
public class ServerCooldownManager {
    private static final ServerCooldownManager INSTANCE = new ServerCooldownManager();
    private final Map<UUID, Long> lastSkinChangeTimestamps = new ConcurrentHashMap<>();

    private ServerCooldownManager() {}

    public static ServerCooldownManager getInstance() {
        return INSTANCE;
    }

    /**
     * Checks if a player is currently on cooldown for changing their skin.
     * @param playerId The UUID of the player to check.
     * @return true if the player is on cooldown, false otherwise.
     */
    public boolean isPlayerOnCooldown(UUID playerId) {
        int cooldownSeconds = ServerConfig.getInstance().skinChangeCooldownSeconds;
        if (cooldownSeconds <= 0) {
            return false;
        }

        long lastChangeTime = lastSkinChangeTimestamps.getOrDefault(playerId, 0L);
        long cooldownMillis = cooldownSeconds * 1000L;

        return (System.currentTimeMillis() - lastChangeTime) < cooldownMillis;
    }

    /**
     * Records that a player has just changed their skin, updating their cooldown timestamp.
     * @param playerId The UUID of the player.
     */
    public void recordSkinChange(UUID playerId) {
        lastSkinChangeTimestamps.put(playerId, System.currentTimeMillis());
    }

    /**
     * Calculates the timestamp when a player's cooldown will end.
     * @param playerId The UUID of the player.
     * @return The epoch millisecond timestamp when the cooldown ends, or 0 if not on cooldown.
     */
    public long getCooldownEndTime(UUID playerId) {
        int cooldownSeconds = ServerConfig.getInstance().skinChangeCooldownSeconds;
        if (cooldownSeconds <= 0) {
            return 0L;
        }

        long lastChangeTime = lastSkinChangeTimestamps.getOrDefault(playerId, 0L);
        return lastChangeTime + (cooldownSeconds * 1000L);
    }

    /**
     * Gets the raw timestamp of the last skin change for a player.
     * @param playerId The UUID of the player.
     * @return The epoch millisecond timestamp of the last change.
     */
    public long getLastChangeTime(UUID playerId) {
        return lastSkinChangeTimestamps.getOrDefault(playerId, 0L);
    }

    /**
     * Removes a player's cooldown data, e.g., when they disconnect.
     * @param playerId The UUID of the player.
     */
    public void removePlayer(UUID playerId) {
        lastSkinChangeTimestamps.remove(playerId);
    }
}
```

### 3. Create Client-side Cooldown Service

This service will maintain the cooldown state on the client.

```java
// common/src/main/java/com/quickskin/mod/client/services/CooldownService.java
package com.quickskin.mod.client.services;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Client-side service to manage skin change cooldown status.
 */
@Environment(EnvType.CLIENT)
public class CooldownService {
    private static CooldownService instance;
    private long skinChangeCooldownEnd = 0L;

    private CooldownService() {}

    public static CooldownService getInstance() {
        if (instance == null) {
            instance = new CooldownService();
        }
        return instance;
    }

    /**
     * Sets the timestamp when the cooldown will end.
     * @param time The epoch millisecond timestamp.
     */
    public void setCooldownEndTime(long time) {
        this.skinChangeCooldownEnd = time;
    }

    /**
     * Gets the remaining cooldown time in seconds.
     * @return The number of seconds remaining, or 0 if not on cooldown.
     */
    public long getRemainingCooldownSeconds() {
        if (skinChangeCooldownEnd == 0) return 0;
        long remainingMillis = skinChangeCooldownEnd - System.currentTimeMillis();
        return remainingMillis > 0 ? (remainingMillis / 1000) + 1 : 0;
    }

    /**
     * Checks if the player is currently on cooldown.
     * @return true if the cooldown is active, false otherwise.
     */
    public boolean isCooldownActive() {
        return getRemainingCooldownSeconds() > 0;
    }

    /**
     * Resets the cooldown timer.
     */
    public void clearCooldown() {
        this.skinChangeCooldownEnd = 0L;
    }
}
```

I'll also initialize it in the client entrypoint.

```java
// common/src/main/java/com/quickskin/mod/QuickSkinClient.java
package com.quickskin.mod;

import com.quickskin.mod.client.input.KeybindRegistry;
import com.quickskin.mod.client.services.*;
import com.quickskin.mod.client.storage.LocalAppearanceStorage;
import com.quickskin.mod.event.ClientEvents;
import com.quickskin.mod.networking.ClientNetworking;
import com.quickskin.mod.platform.PlatformHelper;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Client-only entry point for QuickSkin mod
 * This class and all its methods are stripped on dedicated servers
 */
@Environment(EnvType.CLIENT)
public class QuickSkinClient {

    /**
     * Client initialization - only runs on client side
     * Called from platform-specific client entry points
     */
    public static void init() {
        QuickSkin.LOGGER.info("Initializing {} (Client)", QuickSkin.MOD_NAME);

        // Phase 2: Initialize client services
        QuickSkin.LOGGER.info("Initializing client services...");
        ModelService.init();
        SkinService.init();
        CapeService.init();
        PlayerAppearanceService.init();
        MojangApiService.init();
        CooldownService.getInstance();

        // Phase 3: Register client networking (S2C receivers)
        ClientNetworking.init();

        // Phase 4: Register client events and keybinds
        ClientEvents.init();
        KeybindRegistry.init();

        // Phase 5: Initialize asset service and local storage
        LocalAssetManager.getInstance().init();
        LocalAppearanceStorage.getInstance().init(PlatformHelper.getConfigDirectory());

        // Phase 6: Auto-select player's own skin if no skin is currently selected
        ClientEvents.autoSelectPlayerOwnSkin();

        // Phase 7: Animation service (AnimatedTextureManager is lazy-initialized)
        // Ticking is handled in ClientEvents

        // Phase 9: Load client config
        com.quickskin.mod.config.ClientConfig.getInstance();

        QuickSkin.LOGGER.info("{} Client initialization complete", QuickSkin.MOD_NAME);
    }
}
```

### 4. Update Networking

I will define the new packet and register its handlers.

```java
// common/src/main/java/com/quickskin/mod/networking/ModNetworking.java
package com.quickskin.mod.networking;

import com.quickskin.mod.QuickSkin;
import dev.architectury.networking.NetworkManager;
import net.minecraft.resources.ResourceLocation;

/**
 * Central networking registry for QuickSkin
 * Defines all packet IDs used by the mod
 */
public class ModNetworking {

    // Client to Server packets (C2S)
    public static final ResourceLocation UPLOAD_SKIN =
        new ResourceLocation(QuickSkin.MOD_ID, "upload_skin");

    public static final ResourceLocation UPLOAD_CAPE =
        new ResourceLocation(QuickSkin.MOD_ID, "upload_cape");

    public static final ResourceLocation UPDATE_APPEARANCE =
        new ResourceLocation(QuickSkin.MOD_ID, "update_appearance");

    public static final ResourceLocation REQUEST_TEXTURE =
        new ResourceLocation(QuickSkin.MOD_ID, "request_texture");

    public static final ResourceLocation TEXTURE_CHUNK =
        new ResourceLocation(QuickSkin.MOD_ID, "texture_chunk");

    public static final ResourceLocation UPLOAD_ANIMATION_METADATA =
        new ResourceLocation(QuickSkin.MOD_ID, "upload_animation_metadata");

    // Server to Client packets (S2C)
    public static final ResourceLocation SYNC_APPEARANCE =
        new ResourceLocation(QuickSkin.MOD_ID, "sync_appearance");

    public static final ResourceLocation SEND_TEXTURE =
        new ResourceLocation(QuickSkin.MOD_ID, "send_texture");

    public static final ResourceLocation SEND_TEXTURE_CHUNK =
        new ResourceLocation(QuickSkin.MOD_ID, "send_texture_chunk");

    public static final ResourceLocation SEND_ANIMATION_METADATA =
        new ResourceLocation(QuickSkin.MOD_ID, "send_animation_metadata");

    public static final ResourceLocation SYNC_SERVER_CONFIG =
        new ResourceLocation(QuickSkin.MOD_ID, "sync_server_config");

    public static final ResourceLocation COOLDOWN_UPDATE =
        new ResourceLocation(QuickSkin.MOD_ID, "cooldown_update");

    /**
     * Initializes networking (registers server-side receivers)
     * Called from QuickSkin.init() on both client and server
     */
    public static void init() {
        QuickSkin.LOGGER.info("Initializing networking...");

        // Register server-side packet receivers (C2S)
        NetworkManager.registerReceiver(
            NetworkManager.c2s(),
            UPLOAD_SKIN,
            ServerNetworkHandler::handleUploadTexture
        );

        NetworkManager.registerReceiver(
            NetworkManager.c2s(),
            UPLOAD_CAPE,
            ServerNetworkHandler::handleUploadTexture
        );

        NetworkManager.registerReceiver(
            NetworkManager.c2s(),
            UPDATE_APPEARANCE,
            ServerNetworkHandler::handleUpdateAppearance
        );

        NetworkManager.registerReceiver(
            NetworkManager.c2s(),
            REQUEST_TEXTURE,
            ServerNetworkHandler::handleRequestTexture
        );

        NetworkManager.registerReceiver(
            NetworkManager.c2s(),
            UPLOAD_ANIMATION_METADATA,
            ServerNetworkHandler::handleUploadAnimationMetadata
        );

        NetworkManager.registerReceiver(
            NetworkManager.c2s(),
            TEXTURE_CHUNK,
            ServerNetworkHandler::handleTextureChunk
        );

        QuickSkin.LOGGER.info("Networking initialized (server-side receivers ready)");
    }
}
```

```java
// common/src/main/java/com/quickskin/mod/networking/ClientNetworking.java
package com.quickskin.mod.networking;

import com.quickskin.mod.QuickSkin;
import dev.architectury.networking.NetworkManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Client-side networking initialization
 * Registers client-side packet receivers (S2C)
 */
@Environment(EnvType.CLIENT)
public class ClientNetworking {

    /**
     * Initializes client-side networking (registers S2C receivers)
     * Called from QuickSkinClient.init() only on client
     */
    public static void init() {
        QuickSkin.LOGGER.info("Initializing client networking...");

        // Register client-side packet receivers (S2C)
        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            ModNetworking.SYNC_APPEARANCE,
            ClientNetworkHandler::handleSyncAppearance
        );

        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            ModNetworking.SEND_TEXTURE,
            ClientNetworkHandler::handleSendTexture
        );

        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            ModNetworking.SEND_TEXTURE_CHUNK,
            ClientNetworkHandler::handleSendTextureChunk
        );

        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            ModNetworking.SEND_ANIMATION_METADATA,
            ClientNetworkHandler::handleSendAnimationMetadata
        );

        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            ModNetworking.SYNC_SERVER_CONFIG,
            ClientNetworkHandler::handleSyncServerConfig
        );

        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            ModNetworking.COOLDOWN_UPDATE,
            ClientNetworkHandler::handleCooldownUpdate
        );

        QuickSkin.LOGGER.info("Client networking initialized (client-side receivers ready)");
    }
}
```

```java
// common/src/main/java/com/quickskin/mod/networking/ClientNetworkHandler.java
package com.quickskin.mod.networking;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.services.AnimatedTextureManager;
import com.quickskin.mod.client.services.CooldownService;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.client.storage.ClientAnimationMetadataCache;
import com.quickskin.mod.common.data.AnimationMetadata;
import com.quickskin.mod.common.event.InternalEventBus;
import com.quickskin.mod.common.event.ServerConfigSyncEvent;
import com.quickskin.mod.networking.packets.PacketHelper;
import dev.architectury.networking.NetworkManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * Client-side network packet handlers
 * Handles all S2C (Server to Client) packets
 */
@Environment(EnvType.CLIENT)
public class ClientNetworkHandler {

    /**
     * Handles appearance sync from server
     * Packet format: UUID (player) + String (skinId) + String (capeId) + String (model)
     */
    public static void handleSyncAppearance(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        UUID playerId = PacketHelper.readPlayerId(buf);
        String skinId = PacketHelper.readString(buf);
        String capeId = PacketHelper.readString(buf);
        String model = PacketHelper.readString(buf);

        // Queue work on main thread (CRITICAL for thread safety!)
        context.queue(() -> {
            QuickSkin.LOGGER.info("Received appearance sync for player {}: skin={}, cape={}, model={}",
                    playerId, skinId, capeId, model);

            // Apply appearance through service
            PlayerAppearanceService.getInstance().applyLook(playerId, skinId, capeId, model);
        });
    }

    /**
     * Handles texture data from server
     * Packet format: String (textureType) + String (hash) + byte[] (imageData)
     */
    public static void handleSendTexture(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        String textureType = PacketHelper.readString(buf);
        String hash = PacketHelper.readString(buf);
        byte[] imageData = PacketHelper.readByteArray(buf);

        context.queue(() -> {
            QuickSkin.LOGGER.info("Received {} texture from server: {} (size: {} bytes)",
                    textureType, hash, imageData.length);

            // Store in network texture cache (not local assets, so it won't appear in skin list)
            com.quickskin.mod.client.storage.NetworkTextureCache.getInstance()
                    .storeTexture(hash, imageData);

            QuickSkin.LOGGER.debug("Cached {} texture from server: {}", textureType, hash);
        });
    }

    /**
     * Handles animation metadata from server
     * Packet format: String (hash) + String (metadataJson)
     */
    public static void handleSendAnimationMetadata(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        String hash = PacketHelper.readString(buf);
        String metadataJson = PacketHelper.readString(buf);

        context.queue(() -> {
            QuickSkin.LOGGER.info("Received animation metadata for: {}", hash);

            // Store animation metadata both in memory cache and to disk
            try {
                // Parse the JSON metadata
                AnimationMetadata metadata = AnimationMetadata.fromJson(metadataJson);

                // Store in client-side memory cache
                ClientAnimationMetadataCache.getInstance().storeMetadata(hash, metadata);

                // Also save to disk so LocalAssetManager can find it
                java.nio.file.Path cacheDir = com.quickskin.mod.client.services.LocalAssetManager.getInstance()
                        .getCacheDirectory();
                java.nio.file.Path metadataPath = cacheDir.resolve(hash + ".json");
                java.nio.file.Files.writeString(metadataPath, metadataJson);

                QuickSkin.LOGGER.debug("Saved animation metadata: {} frames, {} ms total duration",
                        metadata.frameCount(), metadata.getTotalDuration());

                // Register animation for this network texture
                registerNetworkCapeAnimation(hash, metadata);

                // Refresh all players using this cape so they see the animation
                refreshPlayersUsingTexture(hash);

            } catch (Exception e) {
                QuickSkin.LOGGER.error("Failed to save animation metadata for: {}", hash, e);
            }
        });
    }

    /**
     * Registers animation for a network-received cape
     * @param hash Texture hash
     * @param metadata Animation metadata
     */
    private static void registerNetworkCapeAnimation(String hash, AnimationMetadata metadata) {
        try {
            // Get the network texture location
            net.minecraft.resources.ResourceLocation textureLocation =
                com.quickskin.mod.client.storage.NetworkTextureCache.getInstance().getTextureLocation(hash);

            if (textureLocation == null) {
                QuickSkin.LOGGER.warn("Cannot register animation for {}: texture not in network cache", hash);
                return;
            }

            // Get the texture image from network cache
            byte[] textureData = com.quickskin.mod.client.storage.NetworkTextureCache.getInstance().getTextureData(hash);
            if (textureData == null) {
                QuickSkin.LOGGER.warn("Cannot register animation for {}: texture data not available", hash);
                return;
            }

            // Convert to BufferedImage
            java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(textureData);
            java.awt.image.BufferedImage atlasImage = javax.imageio.ImageIO.read(bais);

            if (atlasImage == null) {
                QuickSkin.LOGGER.warn("Cannot register animation for {}: failed to read image", hash);
                return;
            }

            // Register the animation
            // Use same animation ID format as local capes for consistency with renderer
            String animationId = "cape_" + hash;
            String capeId = "local_cape:" + hash;

            com.quickskin.mod.client.services.AnimatedTextureManager animManager =
                com.quickskin.mod.client.services.AnimatedTextureManager.getInstance();

            if (!animManager.isAnimated(animationId)) {
                animManager.registerAnimation(animationId, capeId, textureLocation, atlasImage, metadata);
                QuickSkin.LOGGER.info("Registered animation for network cape: {} ({} frames)", hash, metadata.frameCount());
            }

        } catch (Exception e) {
            QuickSkin.LOGGER.error("Failed to register animation for network cape: {}", hash, e);
        }
    }

    /**
     * Refreshes all players using the specified texture
     * This triggers a re-render which will pick up the newly registered animation
     * @param hash Texture hash
     */
    private static void refreshPlayersUsingTexture(String hash) {
        // The animation will be picked up automatically when CapeService loads the cape
        // No need to manually refresh - CapeService.loadLocalCape() now checks for network animations
        QuickSkin.LOGGER.debug("Animation metadata received for {}, will be applied when cape is loaded", hash);
    }

    /**
     * Handles server config sync (server sends full config to client on join)
     * Packet format: String (serverConfigJson)
     */
    public static void handleSyncServerConfig(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        String configJson = PacketHelper.readString(buf);

        context.queue(() -> {
            QuickSkin.LOGGER.info("Received server config sync");

            // Parse server config from JSON
            com.quickskin.mod.config.ServerConfig serverConfig =
                com.quickskin.mod.config.ServerConfig.fromJson(configJson);

            // Phase 9: Apply server config override to client
            com.quickskin.mod.config.ClientConfig.getInstance().applyServerOverride(serverConfig);

            // Fire event for other systems to react
            InternalEventBus.getInstance().post(
                new ServerConfigSyncEvent(
                    serverConfig.allowCustomSkins,
                    serverConfig.allowCustomCapes,
                    !serverConfig.disableSkinTransparency // allowTransparent
                )
            );

            QuickSkin.LOGGER.debug("Server config override applied: allowCustomSkins={}, allowHDSkins={}, maxSkinResolution={}",
                serverConfig.allowCustomSkins, serverConfig.allowHDSkins, serverConfig.maxSkinResolution);

            // CRITICAL FIX: Sync current appearance to server after receiving config
            // This ensures that when a player joins, existing players see their CURRENT appearance
            // rather than the old saved appearance that was loaded from disk
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                UUID playerId = mc.player.getUUID();
                com.quickskin.mod.common.data.PlayerAppearance currentAppearance =
                    com.quickskin.mod.common.data.PlayerAppearanceRepository.getInstance().getAppearance(playerId);

                if (currentAppearance != null) {
                    QuickSkin.LOGGER.info("Syncing current appearance to server after config received");
                    NetworkSyncService.getInstance().syncAppearance(
                        playerId,
                        currentAppearance.getSkinId(),
                        currentAppearance.getCapeId(),
                        currentAppearance.getModel()
                    );
                }
            }
        });
    }

    /**
     * Handles texture chunk from server (for large textures)
     * Packet format: String (hash) + int (chunkIndex) + int (totalChunks) + byte[] (chunkData)
     */
    public static void handleSendTextureChunk(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        String hash = buf.readUtf();
        int chunkIndex = buf.readInt();
        int totalChunks = buf.readInt();
        byte[] chunkData = buf.readByteArray();

        context.queue(() -> {
            QuickSkin.LOGGER.debug("Received texture chunk {}/{} for: {} (size: {} bytes)",
                    chunkIndex + 1, totalChunks, hash, chunkData.length);

            // Validate chunk data
            if (chunkData.length > 32 * 1024) {
                QuickSkin.LOGGER.warn("Received oversized chunk: {} bytes (max: 32KB)", chunkData.length);
                return;
            }

            // Use TextureChunkReceiver to assemble chunks
            com.quickskin.mod.client.storage.TextureChunkReceiver.getInstance()
                .receiveChunk(hash, chunkIndex, totalChunks, chunkData);
        });
    }

    /**
     * Handles cooldown update from server.
     * Packet format: long (cooldownEndTime)
     */
    public static void handleCooldownUpdate(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        long cooldownEndTime = buf.readLong();

        context.queue(() -> {
            QuickSkin.LOGGER.debug("Received cooldown update. Ends at: {}", cooldownEndTime);
            CooldownService.getInstance().setCooldownEndTime(cooldownEndTime);
        });
    }
}
```

### 5. Enforce Cooldown on Server and Sync Status

I will now update the server-side logic to use the `ServerCooldownManager` and notify clients.

```java
// common/src/main/java/com/quickskin/mod/networking/ServerNetworkHandler.java
package com.quickskin.mod.networking;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.networking.packets.PacketHelper;
import com.quickskin.mod.server.data.ServerCooldownManager;
import com.quickskin.mod.server.data.ServerPlayerAppearanceRepository;
import com.quickskin.mod.server.storage.ServerAnimationCache;
import com.quickskin.mod.server.storage.ServerTextureCache;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Server-side network packet handlers
 * Handles all C2S (Client to Server) packets
 */
public class ServerNetworkHandler {

    /**
     * Handles skin/cape upload from client
     * Packet format: UUID (player) + String (textureType) + byte[] (imageData)
     */
    public static void handleUploadTexture(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        // Read data from buffer
        UUID playerId = PacketHelper.readPlayerId(buf);
        String textureType = PacketHelper.readString(buf);
        byte[] imageData = PacketHelper.readByteArray(buf);

        // Queue work on main thread (CRITICAL for thread safety!)
        context.queue(() -> {
            ServerPlayer player = (ServerPlayer) context.getPlayer();

            if (player == null || !player.getUUID().equals(playerId)) {
                QuickSkin.LOGGER.warn("Player UUID mismatch in upload texture packet");
                return;
            }

            QuickSkin.LOGGER.info("Received {} upload from player: {} (size: {} bytes)",
                    textureType, player.getName().getString(), imageData.length);

            // Generate hash for this texture
            String hash = playerId.toString() + "_" + textureType;

            // Phase 5: Store texture to server-side storage
            ServerTextureCache.getInstance().storeTexture(hash, imageData);

            // Phase 3: Sync to other players
            broadcastTextureToOtherPlayers(player, textureType, hash, imageData);
        });
    }

    /**
     * Handles appearance update from client
     * Packet format: UUID (player) + String (skinId) + String (capeId) + String (model)
     */
    public static void handleUpdateAppearance(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        UUID playerId = PacketHelper.readPlayerId(buf);
        String skinId = PacketHelper.readString(buf);
        String capeId = PacketHelper.readString(buf);
        String model = PacketHelper.readString(buf);

        context.queue(() -> {
            ServerPlayer player = (ServerPlayer) context.getPlayer();

            if (player == null || !player.getUUID().equals(playerId)) {
                QuickSkin.LOGGER.warn("Player UUID mismatch in update appearance packet");
                return;
            }

            PlayerAppearance currentAppearance = ServerPlayerAppearanceRepository.getInstance().getAppearance(playerId);
            boolean isSkinChanging = skinId != null && !skinId.isEmpty() && (currentAppearance == null || !skinId.equals(currentAppearance.getSkinId()));

            if (isSkinChanging) {
                if (ServerCooldownManager.getInstance().isPlayerOnCooldown(playerId)) {
                    QuickSkin.LOGGER.warn("Player {} tried to change skin during cooldown. Change rejected.", player.getName().getString());
                    return;
                }
            }

            QuickSkin.LOGGER.info("Player {} updated appearance: skin={}, cape={}, model={}",
                    player.getName().getString(), skinId, capeId, model);

            // Update server-side repository
            ServerPlayerAppearanceRepository.getInstance().updateAppearance(playerId, skinId, capeId, model);

            if (isSkinChanging) {
                ServerCooldownManager.getInstance().recordSkinChange(playerId);

                int cooldownSeconds = com.quickskin.mod.config.ServerConfig.getInstance().skinChangeCooldownSeconds;
                if (cooldownSeconds > 0) {
                    long cooldownEndTime = ServerCooldownManager.getInstance().getCooldownEndTime(playerId);
                    FriendlyByteBuf cooldownBuf = new FriendlyByteBuf(Unpooled.buffer());
                    cooldownBuf.writeLong(cooldownEndTime);
                    NetworkManager.sendToPlayer(player, ModNetworking.COOLDOWN_UPDATE, cooldownBuf);
                    QuickSkin.LOGGER.debug("Sent cooldown update to player {}", player.getName().getString());
                }
            }

            // Phase 3: Broadcast to other players
            broadcastAppearanceToOtherPlayers(player, skinId, capeId, model);
        });
    }

    /**
     * Handles texture request from client
     * Packet format: UUID (player) + String (textureType) + String (hash)
     */
    public static void handleRequestTexture(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        UUID playerId = PacketHelper.readPlayerId(buf);
        String textureType = PacketHelper.readString(buf);
        String hash = PacketHelper.readString(buf);

        context.queue(() -> {
            ServerPlayer player = (ServerPlayer) context.getPlayer();

            if (player == null) {
                return;
            }

            QuickSkin.LOGGER.info("Player {} requested {} texture: {}",
                    player.getName().getString(), textureType, hash);

            // Phase 5: Load texture from server storage and send to client
            byte[] textureData = ServerTextureCache.getInstance().getTexture(hash);
            if (textureData != null) {
                sendTextureToClient(player, textureType, hash, textureData);
            } else {
                QuickSkin.LOGGER.warn("Requested texture not found: {}", hash);
            }
        });
    }

    /**
     * Handles chunked texture upload from client
     * Packet format: String (hash) + String (textureType) + int (chunkIndex) + int (totalChunks) + byte[] (chunkData)
     */
    public static void handleTextureChunk(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        String hash = PacketHelper.readString(buf);
        String textureType = PacketHelper.readString(buf);
        int chunkIndex = buf.readInt();
        int totalChunks = buf.readInt();
        byte[] chunkData = PacketHelper.readByteArray(buf);

        context.queue(() -> {
            ServerPlayer player = (ServerPlayer) context.getPlayer();

            if (player == null) {
                return;
            }

            // Validate chunk size (32KB safety limit to prevent oversized packets)
            if (chunkData.length > 32 * 1024) {
                QuickSkin.LOGGER.warn("Rejecting oversized chunk from {}: {} bytes (max: 32KB)",
                    player.getName().getString(), chunkData.length);
                return;
            }

            // Validate chunk index
            if (chunkIndex < 0 || chunkIndex >= totalChunks) {
                QuickSkin.LOGGER.warn("Invalid chunk index from {}: {}/{}",
                    player.getName().getString(), chunkIndex, totalChunks);
                return;
            }

            // Validate total chunks (prevent DoS with excessive chunk counts)
            if (totalChunks < 1 || totalChunks > 1000) {
                QuickSkin.LOGGER.warn("Invalid total chunks from {}: {}",
                    player.getName().getString(), totalChunks);
                return;
            }

            QuickSkin.LOGGER.debug("Received texture chunk {}/{} from {} (type: {}, hash: {})",
                chunkIndex + 1, totalChunks, player.getName().getString(), textureType, hash);

            // Add chunk to assembler
            byte[] completeTexture = com.quickskin.mod.server.storage.TextureChunkAssembler.getInstance()
                .addChunk(hash, chunkIndex, totalChunks, chunkData);

            // If all chunks received, store and broadcast
            if (completeTexture != null) {
                QuickSkin.LOGGER.info("Received complete {} texture from player: {} (size: {} bytes)",
                    textureType, player.getName().getString(), completeTexture.length);

                // Store texture in server cache
                ServerTextureCache.getInstance().storeTexture(hash, completeTexture);

                // Broadcast texture to other players
                broadcastTextureToOtherPlayers(player, textureType, hash, completeTexture);
            }
        });
    }

    /**
     * Handles animation metadata upload from client
     * Packet format: String (hash) + String (metadataJson)
     */
    public static void handleUploadAnimationMetadata(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        String hash = PacketHelper.readString(buf);
        String metadataJson = PacketHelper.readString(buf);

        context.queue(() -> {
            ServerPlayer player = (ServerPlayer) context.getPlayer();

            if (player == null) {
                return;
            }

            QuickSkin.LOGGER.info("Player {} uploaded animation metadata for: {}",
                    player.getName().getString(), hash);

            // Phase 7: Store animation metadata
            ServerAnimationCache.getInstance().storeMetadata(hash, metadataJson);

            // Broadcast animation metadata to other players
            broadcastAnimationMetadataToOtherPlayers(player, hash, metadataJson);
        });
    }

    /**
     * Broadcasts a player's texture to all other players on the server
     * @param player The player whose texture changed
     * @param textureType The type of texture ("skin" or "cape")
     * @param hash The texture hash
     * @param imageData The texture image data
     */
    private static void broadcastTextureToOtherPlayers(ServerPlayer player, String textureType, String hash, byte[] imageData) {
        // Send to all players except the sender
        for (ServerPlayer otherPlayer : player.server.getPlayerList().getPlayers()) {
            if (!otherPlayer.getUUID().equals(player.getUUID())) {
                // Create a fresh packet for each player to avoid buffer reuse issues
                FriendlyByteBuf packet = PacketHelper.createSendTexturePacket(textureType, hash, imageData);
                NetworkManager.sendToPlayer(otherPlayer, ModNetworking.SEND_TEXTURE, packet);
            }
        }

        QuickSkin.LOGGER.debug("Broadcasted {} texture from {} to {} players",
                textureType, player.getName().getString(),
                player.server.getPlayerList().getPlayerCount() - 1);
    }

    /**
     * Broadcasts a player's appearance to all other players on the server
     * @param player The player whose appearance changed
     * @param skinId The skin ID
     * @param capeId The cape ID
     * @param model The model type
     */
    private static void broadcastAppearanceToOtherPlayers(ServerPlayer player, String skinId, String capeId, String model) {
        // Send to all players except the sender
        for (ServerPlayer otherPlayer : player.server.getPlayerList().getPlayers()) {
            if (!otherPlayer.getUUID().equals(player.getUUID())) {
                // Create a fresh packet for each player to avoid buffer reuse issues
                FriendlyByteBuf packet = PacketHelper.createSyncAppearancePacket(
                        player.getUUID(), skinId, capeId, model
                );
                NetworkManager.sendToPlayer(otherPlayer, ModNetworking.SYNC_APPEARANCE, packet);
            }
        }

        QuickSkin.LOGGER.debug("Broadcasted appearance from {} to {} players",
                player.getName().getString(),
                player.server.getPlayerList().getPlayerCount() - 1);
    }

    /**
     * Sends a player's appearance to a specific client
     * Used when players join or respawn
     * @param recipient The player to send the appearance to
     * @param targetPlayerId The player whose appearance to send
     */
    public static void sendAppearanceToPlayer(ServerPlayer recipient, UUID targetPlayerId) {
        PlayerAppearance appearance = ServerPlayerAppearanceRepository.getInstance().getAppearance(targetPlayerId);

        if (appearance != null) {
            // Send the appearance metadata
            FriendlyByteBuf packet = PacketHelper.createSyncAppearancePacket(
                    targetPlayerId,
                    appearance.getSkinId(),
                    appearance.getCapeId(),
                    appearance.getModel()
            );

            NetworkManager.sendToPlayer(recipient, ModNetworking.SYNC_APPEARANCE, packet);

            // Also send the texture data if it's a custom skin/cape
            String skinId = appearance.getSkinId();
            String capeId = appearance.getCapeId();

            // Send skin texture if it's a local skin
            if (skinId != null && skinId.startsWith("local_skin:")) {
                String hash = skinId.substring("local_skin:".length());
                byte[] skinData = ServerTextureCache.getInstance().getTexture(hash);
                if (skinData != null) {
                    FriendlyByteBuf skinPacket = PacketHelper.createSendTexturePacket("skin", hash, skinData);
                    NetworkManager.sendToPlayer(recipient, ModNetworking.SEND_TEXTURE, skinPacket);
                    QuickSkin.LOGGER.debug("Sent skin texture {} to {}", hash, recipient.getName().getString());
                }
            }

            // Send cape texture if it's a local cape
            if (capeId != null && capeId.startsWith("local_cape:")) {
                String hash = capeId.substring("local_cape:".length());
                byte[] capeData = ServerTextureCache.getInstance().getTexture(hash);
                if (capeData != null) {
                    FriendlyByteBuf capePacket = PacketHelper.createSendTexturePacket("cape", hash, capeData);
                    NetworkManager.sendToPlayer(recipient, ModNetworking.SEND_TEXTURE, capePacket);
                    QuickSkin.LOGGER.debug("Sent cape texture {} to {}", hash, recipient.getName().getString());

                    // Also send animation metadata if available
                    String metadata = ServerAnimationCache.getInstance().getMetadata(hash);
                    if (metadata != null) {
                        FriendlyByteBuf animPacket = PacketHelper.createSendAnimationMetadataPacket(hash, metadata);
                        NetworkManager.sendToPlayer(recipient, ModNetworking.SEND_ANIMATION_METADATA, animPacket);
                        QuickSkin.LOGGER.debug("Sent animation metadata for {} to {}", hash, recipient.getName().getString());
                    }
                }
            }

            QuickSkin.LOGGER.debug("Sent appearance of {} to {}",
                    targetPlayerId, recipient.getName().getString());
        }
    }

    /**
     * Sends all player appearances to a newly joined player
     * @param player The player who just joined
     */
    public static void sendAllAppearancesToPlayer(ServerPlayer player) {
        // Send appearance of every other player to the joining player
        for (ServerPlayer otherPlayer : player.server.getPlayerList().getPlayers()) {
            if (!otherPlayer.getUUID().equals(player.getUUID())) {
                sendAppearanceToPlayer(player, otherPlayer.getUUID());
            }
        }

        QuickSkin.LOGGER.debug("Sent all player appearances to {}", player.getName().getString());
    }

    /**
     * Sends a specific player's appearance to all OTHER players on the server
     * Used when a player joins to notify existing players of the new player's appearance
     * @param player The player whose appearance to send
     */
    public static void sendAppearanceToAllPlayers(ServerPlayer player) {
        PlayerAppearance appearance = ServerPlayerAppearanceRepository.getInstance().getAppearance(player.getUUID());

        if (appearance != null) {
            // Send to all players except the player themselves
            for (ServerPlayer otherPlayer : player.server.getPlayerList().getPlayers()) {
                if (!otherPlayer.getUUID().equals(player.getUUID())) {
                    sendAppearanceToPlayer(otherPlayer, player.getUUID());
                }
            }

            QuickSkin.LOGGER.debug("Sent appearance of {} to all other players", player.getName().getString());
        } else {
            QuickSkin.LOGGER.debug("No appearance found for {}, skipping broadcast to other players", player.getName().getString());
        }
    }

    /**
     * Send a texture to a client
     * @param player The player to send to
     * @param textureType The texture type
     * @param hash The texture hash
     * @param textureData The texture data
     */
    private static void sendTextureToClient(ServerPlayer player, String textureType, String hash, byte[] textureData) {
        FriendlyByteBuf packet = PacketHelper.createSendTexturePacket(textureType, hash, textureData);
        NetworkManager.sendToPlayer(player, ModNetworking.SEND_TEXTURE, packet);
        QuickSkin.LOGGER.debug("Sent {} texture {} to {}", textureType, hash, player.getName().getString());
    }

    /**
     * Broadcasts animation metadata to all other players on the server
     * @param player The player who uploaded the metadata
     * @param hash The texture hash
     * @param metadataJson The animation metadata JSON
     */
    private static void broadcastAnimationMetadataToOtherPlayers(ServerPlayer player, String hash, String metadataJson) {
        // Send to all players except the sender
        for (ServerPlayer otherPlayer : player.server.getPlayerList().getPlayers()) {
            if (!otherPlayer.getUUID().equals(player.getUUID())) {
                // Create a fresh packet for each player to avoid buffer reuse issues
                FriendlyByteBuf packet = PacketHelper.createSendAnimationMetadataPacket(hash, metadataJson);
                NetworkManager.sendToPlayer(otherPlayer, ModNetworking.SEND_ANIMATION_METADATA, packet);
            }
        }

        QuickSkin.LOGGER.debug("Broadcasted animation metadata for {} to {} players",
                hash, player.server.getPlayerList().getPlayerCount() - 1);
    }

    /**
     * Sends server config to a specific player (called on player join)
     * @param player The player to send config to
     */
    public static void sendServerConfigToPlayer(ServerPlayer player) {
        com.quickskin.mod.config.ServerConfig serverConfig = com.quickskin.mod.config.ServerConfig.getInstance();
        String configJson = serverConfig.toJson();

        FriendlyByteBuf packet = PacketHelper.createSyncServerConfigPacket(configJson);

        NetworkManager.sendToPlayer(player, ModNetworking.SYNC_SERVER_CONFIG, packet);
        QuickSkin.LOGGER.debug("Sent server config to {}", player.getName().getString());
    }
}
```

```java
// common/src/main/java/com/quickskin/mod/event/CommonEvents.java
package com.quickskin.mod.event;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.networking.ModNetworking;
import com.quickskin.mod.networking.ServerNetworkHandler;
import com.quickskin.mod.server.data.ServerCooldownManager;
import com.quickskin.mod.server.storage.ServerAnimationCache;
import com.quickskin.mod.server.storage.ServerAppearanceStorage;
import com.quickskin.mod.server.storage.ServerTextureCache;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

/**
 * Common event handlers (runs on both client and server)
 * Uses Architectury's event system for cross-platform compatibility
 */
public class CommonEvents {

    /**
     * Initializes common event listeners
     * Called from QuickSkin.init()
     */
    public static void init() {
        QuickSkin.LOGGER.info("Registering common events...");

        // Player joins server
        PlayerEvent.PLAYER_JOIN.register(player -> {
            QuickSkin.LOGGER.info("Player joined: {}", player.getName().getString());

            // Phase 5: Load player's saved appearance from server storage
            com.quickskin.mod.common.data.PlayerAppearance savedAppearance =
                ServerAppearanceStorage.getInstance().loadPlayerAppearance(player.getUUID());

            // If no saved appearance exists, create a default entry in the repository
            // This ensures all connected players have an entry that can be synced to joining players
            if (savedAppearance == null) {
                QuickSkin.LOGGER.debug("No saved appearance for {}, creating default entry", player.getName().getString());
                com.quickskin.mod.server.data.ServerPlayerAppearanceRepository.getInstance()
                    .updateAppearance(player.getUUID(), "", "", "classic");
            }

            // Phase 3: Send all other players' appearances to the joining player
            ServerNetworkHandler.sendAllAppearancesToPlayer((ServerPlayer) player);

            // CRITICAL FIX: Also send THIS player's appearance to all OTHER players
            // This ensures that existing players (like the host) see the joining player's appearance
            // AND when the host first starts the server, future joining players will see the host
            ServerNetworkHandler.sendAppearanceToAllPlayers((ServerPlayer) player);

            // Phase 9: Sync server config to client
            ServerNetworkHandler.sendServerConfigToPlayer((ServerPlayer) player);

            // Send current cooldown status to joining player
            int cooldownSeconds = com.quickskin.mod.config.ServerConfig.getInstance().skinChangeCooldownSeconds;
            if (cooldownSeconds > 0 && ServerCooldownManager.getInstance().isPlayerOnCooldown(player.getUUID())) {
                long cooldownEndTime = ServerCooldownManager.getInstance().getCooldownEndTime(player.getUUID());
                FriendlyByteBuf cooldownBuf = new FriendlyByteBuf(Unpooled.buffer());
                cooldownBuf.writeLong(cooldownEndTime);
                NetworkManager.sendToPlayer((ServerPlayer) player, ModNetworking.COOLDOWN_UPDATE, cooldownBuf);
                QuickSkin.LOGGER.debug("Sent initial cooldown status to joining player {}", player.getName().getString());
            }
        });

        // Player quits server
        PlayerEvent.PLAYER_QUIT.register(player -> {
            QuickSkin.LOGGER.info("Player quit: {}", player.getName().getString());

            // Phase 5: Save player's appearance to server storage
            ServerAppearanceStorage.getInstance().savePlayerAppearance(player.getUUID());

            // Cleanup cooldown data
            ServerCooldownManager.getInstance().removePlayer(player.getUUID());
        });

        // Player respawns (after death)
        PlayerEvent.PLAYER_RESPAWN.register((player, conqueredEnd) -> {
            if (player instanceof ServerPlayer) {
                ServerPlayer serverPlayer = (ServerPlayer) player;

                // Phase 3: Re-send all appearances to the respawned player
                ServerNetworkHandler.sendAllAppearancesToPlayer(serverPlayer);
            }
        });

        // Player changes dimension
        PlayerEvent.CHANGE_DIMENSION.register((player, oldLevel, newLevel) -> {

            // Re-sync appearance if needed (sometimes skins don't transfer across dimensions)
            if (player instanceof ServerPlayer) {
                ServerPlayer serverPlayer = (ServerPlayer) player;
                // Phase 3: Re-send all appearances to this player
                ServerNetworkHandler.sendAllAppearancesToPlayer(serverPlayer);
            }
        });

        // Server starting
        LifecycleEvent.SERVER_STARTING.register(server -> {
            QuickSkin.LOGGER.info("Server starting, initializing QuickSkin server components...");

            // Phase 5: Initialize server-side storage
            ServerTextureCache.getInstance().init(server);
            ServerAnimationCache.getInstance().init(server);
            ServerAppearanceStorage.getInstance().init(server);

            // Phase 9: Reload server config (in case it was modified)
            com.quickskin.mod.config.ServerConfig.reload();
        });

        // Server started (ready to accept players)
        LifecycleEvent.SERVER_STARTED.register(server -> {
            QuickSkin.LOGGER.info("Server started, QuickSkin ready");
        });

        // Server stopping
        LifecycleEvent.SERVER_STOPPING.register(server -> {
            QuickSkin.LOGGER.info("Server stopping, saving QuickSkin data...");

            // Phase 5: Save all pending texture data
            ServerTextureCache.getInstance().saveAll();

            // Phase 9: Save server config
            com.quickskin.mod.config.ServerConfig.getInstance().save();
        });

        // Server stopped
        LifecycleEvent.SERVER_STOPPED.register(server -> {
            QuickSkin.LOGGER.info("Server stopped, QuickSkin cleanup complete");

            // Phase 5: Clear caches
            ServerTextureCache.getInstance().clear();
            ServerAnimationCache.getInstance().clear();
        });

        QuickSkin.LOGGER.info("Common events registered");
    }
}
```

```java
// common/src/main/java/com/quickskin/mod/event/ClientEvents.java
package com.quickskin.mod.event;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen;
import com.quickskin.mod.client.gui.util.DebugOffsetManager;
import com.quickskin.mod.client.gui.widget.PlayerWidget;
import com.quickskin.mod.client.services.AnimatedTextureManager;
import com.quickskin.mod.client.services.CooldownService;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.ModelService;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.PlayerAppearanceRepository;
import com.quickskin.mod.event.CapeTransparencyEvents;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.client.ClientGuiEvent;
import dev.architectury.event.events.client.ClientPlayerEvent;
import dev.architectury.event.events.client.ClientRawInputEvent;
import dev.architectury.event.events.client.ClientScreenInputEvent;
import dev.architectury.event.events.client.ClientTickEvent;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-side event handlers
 * Uses Architectury's event system for cross-platform compatibility
 */
@Environment(EnvType.CLIENT)
public class ClientEvents {

    private static int tickCounter = 0;
    private static PlayerWidget playerWidget;

    // Title screen rotation state (preserved across screen rebuilds)
    private static float titleScreenBodyYaw = 20.0f;
    private static float titleScreenTargetRotation = 20.0f;

    // Shared animation state (preserved across all screens)
    private static String sharedAnimation = "idle";

    /**
     * Get the current shared animation state
     */
    public static String getSharedAnimation() {
        return sharedAnimation;
    }

    /**
     * Set the shared animation state
     */
    public static void setSharedAnimation(String animation) {
        if (animation != null && !animation.isEmpty()) {
            sharedAnimation = animation;
        }
    }

    // Animation buttons (for dropdown menu)
    private static Button animationToggleButton;
    private static final java.util.List<Button> animationButtons = new java.util.ArrayList<>();
    private static boolean isAnimationDropdownOpen = false;

    /**
     * Initializes client event listeners
     * Called from QuickSkinClient.init()
     */
    public static void init() {
        QuickSkin.LOGGER.info("Registering client events...");

        CapeTransparencyEvents.register();

        // Client tick (fires every game tick, ~20 times per second)
        ClientTickEvent.CLIENT_POST.register(client -> {
            // This also ensures the singleton instance is created.
            AnimatedTextureManager.getInstance().tick();
        });

        // Download player's own skin on startup (async, won't block)
        ensurePlayerOwnSkinExists();

        // Player joins world (client-side)
        ClientPlayerEvent.CLIENT_PLAYER_JOIN.register(player -> {
            QuickSkin.LOGGER.info("Local player joined world: {}", player.getName().getString());

            // Phase 5: Rescan assets in case files changed while not in-game
            // LocalAssetManager.getInstance().reload();

            // Clear appearance repository on world join
            PlayerAppearanceRepository.getInstance().clear();
            CooldownService.getInstance().clearCooldown();

            // Restore saved skin and model type from config
            restoreSavedAppearance(player);
        });

        // Player quits world (client-side)
        ClientPlayerEvent.CLIENT_PLAYER_QUIT.register(player -> {
            if (player != null) {
                QuickSkin.LOGGER.info("Local player quit world: {}", player.getName().getString());
            } else {
                QuickSkin.LOGGER.info("Local player quit world (player was null)");
            }

            // Clear all appearance data
            PlayerAppearanceRepository.getInstance().clear();
            ModelService.getInstance().clearAll();
            CooldownService.getInstance().clearCooldown();

            // Clear incomplete texture chunks
            com.quickskin.mod.client.storage.TextureChunkReceiver.getInstance().clear();

            // Clear cached player to reset rendering state (fixes invisible buttons)
            com.quickskin.mod.client.rendering.PlayerModelRenderer.clearCachedPlayer();

            // Phase 5: Save local preferences
            if (player != null) {
                com.quickskin.mod.client.storage.LocalAppearanceStorage.getInstance()
                        .savePlayerPreferences(player.getUUID());
            }
        });

        // Respawn event (player dies and respawns)
        ClientPlayerEvent.CLIENT_PLAYER_RESPAWN.register((oldPlayer, newPlayer) -> {
            QuickSkin.LOGGER.debug("Player respawned");

            // Re-apply appearance after respawn
            restoreSavedAppearance(newPlayer);
        });

        // Screen init (after screen is initialized, before render)
        ClientGuiEvent.INIT_POST.register((client, screenAccess) -> {
            Screen screen = screenAccess.getScreen();

            // Determine screen type for all menu screens
            String screenType = determineScreenType(screen);
            if (screenType == null) {
                return; // Not a screen we care about
            }

            QuickSkin.LOGGER.debug("Screen initialized: {} (type: {})", screen.getClass().getSimpleName(), screenType);

            // Inject QuickSkin button
            int buttonX = 0;
            int buttonY = 0;
            int buttonWidth = 98;
            int buttonHeight = 20;
            int spacing = 4;

            if (screen instanceof TitleScreen titleScreen) {
                // Position next to accessibility button on title screen
                // The Y coordinate for the row with the vanilla language and accessibility buttons
                final int vanillaButtonsY = titleScreen.height / 4 + 48 + 72;

                net.minecraft.client.gui.components.ImageButton accessibilityButton = null;

                // Find the right-most ImageButton on the right half of the screen in that specific row
                // This specifically targets vanilla buttons and avoids other mods' buttons
                for (net.minecraft.client.gui.components.events.GuiEventListener listener : screen.children()) {
                    if (listener instanceof net.minecraft.client.gui.components.ImageButton imgButton) {
                        if (imgButton.getY() == vanillaButtonsY &&
                            imgButton.getX() > titleScreen.width / 2 &&
                            imgButton.getWidth() == 20 &&
                            imgButton.getHeight() == 20) {
                            if (accessibilityButton == null || imgButton.getX() > accessibilityButton.getX()) {
                                accessibilityButton = imgButton;
                            }
                        }
                    }
                }

                // Position next to the found accessibility button
                if (accessibilityButton != null) {
                    buttonX = accessibilityButton.getX() + accessibilityButton.getWidth() + spacing;
                    buttonY = accessibilityButton.getY();
                } else {
                    // Fallback if we couldn't find the accessibility button
                    buttonX = titleScreen.width / 2 + 128;
                    buttonY = titleScreen.height / 4 + 48 + 84;
                }

            } else if (screen instanceof PauseScreen pauseScreen) {
                // Position next to "Save and Quit to Title" button
                Button saveAndQuitButton = null;
                int maxWidth = 0;

                // Find the widest button (vanilla buttons)
                for (net.minecraft.client.gui.components.events.GuiEventListener listener : screen.children()) {
                    if (listener instanceof Button button && button.getWidth() > maxWidth) {
                        maxWidth = button.getWidth();
                    }
                }

                // Find the bottom-most button with that max width (Save and Quit to Title)
                if (maxWidth > 0) {
                    int maxY = -1;
                    for (net.minecraft.client.gui.components.events.GuiEventListener listener : screen.children()) {
                        if (listener instanceof Button button && button.getWidth() == maxWidth && button.getY() > maxY) {
                            maxY = button.getY();
                            saveAndQuitButton = button;
                        }
                    }
                }

                if (saveAndQuitButton != null) {
                    // Position directly next to the vanilla quit button
                    buttonX = saveAndQuitButton.getX() + saveAndQuitButton.getWidth() + spacing;
                    buttonY = saveAndQuitButton.getY();
                } else {
                    // Fallback position if we can't find the button
                    buttonX = pauseScreen.width - buttonWidth - spacing;
                    buttonY = spacing;
                }
            } else {
                // For other screens (world selection, etc.), use similar logic to PauseScreen
                Button referenceButton = findLargestButton(screen);
                if (referenceButton != null) {
                    int targetY = referenceButton.getY();
                    int rightmostX = referenceButton.getX() + referenceButton.getWidth();

                    for (net.minecraft.client.gui.components.events.GuiEventListener listener : screen.children()) {
                        if (listener instanceof AbstractWidget widget && widget.getY() == targetY) {
                            rightmostX = Math.max(rightmostX, widget.getX() + widget.getWidth());
                        }
                    }

                    buttonX = rightmostX + spacing;
                    buttonY = targetY;
                } else {
                    // Fallback
                    buttonX = screen.width - buttonWidth - spacing;
                    buttonY = screen.height - buttonHeight - spacing;
                }
            }

            // Create and add the "Change Skin" button
            Button changeSkinButton = Button.builder(
                    Component.literal("Change Skin"),
                    button -> Minecraft.getInstance().setScreen(new PlayerSkinMenuScreen(screen))
            ).bounds(buttonX, buttonY, buttonWidth, buttonHeight).build();

            screenAccess.addRenderableWidget(changeSkinButton);

            // Create and add the PlayerWidget above the button using debug offsets
            int widgetSize = 144;
            int offsetX = DebugOffsetManager.getOffsetX(screenType);
            int offsetY = DebugOffsetManager.getOffsetY(screenType);

            int widgetX = buttonX + offsetX;
            int widgetY = buttonY + offsetY;

            // Get player skin and model type from saved config or player
            ResourceLocation skinLocation = null;
            String modelType = "classic";
            LocalPlayer player = Minecraft.getInstance().player;

            com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();

            // First priority: Use saved skin from config (works on title screen when player is null)
            if (!config.activeSkinHash.isEmpty()) {
                com.quickskin.mod.client.services.LocalAssetManager assetManager =
                        com.quickskin.mod.client.services.LocalAssetManager.getInstance();
                com.quickskin.mod.common.data.AssetMetadata metadata = assetManager.getMetadata(config.activeSkinHash);

                if (metadata != null) {
                    // Load the saved skin texture
                    skinLocation = assetManager.getTextureLocation(config.activeSkinHash, com.quickskin.mod.common.data.TextureQuality.FULL);

                    // Get saved model type preference for this skin
                    modelType = assetManager.getSkinModelPreference(config.activeSkinHash);

                    // If auto mode, use the detected model type from metadata
                    if ("auto".equals(modelType)) {
                        modelType = metadata.skinModel();
                    }

                    QuickSkin.LOGGER.debug("Using saved skin for title screen widget: {} with model type: {}",
                            metadata.friendlyName(), modelType);
                }
            }

            // Second priority: Use current player skin (when in-game)
            if (skinLocation == null && player != null) {
                skinLocation = player.getSkinTextureLocation();

                // Get model type from the active skin if available
                if (!config.activeSkinHash.isEmpty()) {
                    LocalAssetManager assetManager = LocalAssetManager.getInstance();
                    modelType = assetManager.getSkinModelPreference(config.activeSkinHash);
                    AssetMetadata metadata = assetManager.getMetadata(config.activeSkinHash);

                    // If auto mode, detect from the active custom skin (if any)
                    if ("auto".equals(modelType) && metadata != null) {
                        // Use the detected model type from the custom skin metadata
                        modelType = metadata.skinModel();
                    } else {
                        // Fallback: detect from the vanilla player's model
                        modelType = player.getModelName(); // "default" or "slim"
                        // Convert Minecraft model names to our format
                        if ("default".equals(modelType)) {
                            modelType = "classic";
                        }
                    }
                } else if ("auto".equals(modelType)) {
                    // No custom skin active, use vanilla player's model
                    modelType = player.getModelName(); // "default" or "slim"
                    // Convert Minecraft model names to our format
                    if ("default".equals(modelType)) {
                        modelType = "classic";
                    }
                }
            }

            // Fallback: Use default Steve skin
            if (skinLocation == null) {
                skinLocation = new ResourceLocation("minecraft", "textures/entity/player/wide/steve.png");
                modelType = "classic";
            }

            // Load saved cape from config
            String capeId = config.activeCapeHash;
            ResourceLocation capeLocation = null;
            if (capeId != null && !capeId.isEmpty()) {
                // Use the service to resolve the location. This will also trigger animation registration.
                // The UUID is not used for local/known capes, so we can pass null.
                capeLocation = com.quickskin.mod.client.services.CapeService.getInstance().getCapeLocation(null, capeId);
            }

            // Save rotation and animation state from existing widget before creating new one
            if (playerWidget != null) {
                titleScreenBodyYaw = playerWidget.getBodyYaw();
                titleScreenTargetRotation = playerWidget.getTargetYRotation();
                String currentAnimation = playerWidget.getAnimation();
                if (currentAnimation != null && !currentAnimation.isEmpty()) {
                    setSharedAnimation(currentAnimation);
                }
            }

            playerWidget = new PlayerWidget(widgetX, widgetY, widgetSize, widgetSize, skinLocation, capeLocation, capeId, modelType);
            // Set context based on screen type
            if ("title".equals(screenType)) {
                playerWidget.setContext(com.quickskin.mod.client.gui.widget.PlayerWidget.WidgetContext.TITLE_SCREEN);
            } else if ("pause".equals(screenType)) {
                playerWidget.setContext(com.quickskin.mod.client.gui.widget.PlayerWidget.WidgetContext.PAUSE_MENU);
            }
            screenAccess.addRenderableWidget(playerWidget);

            // Restore saved rotation and animation state
            playerWidget.setRotationState(titleScreenBodyYaw, titleScreenTargetRotation);
            String savedAnimation = getSharedAnimation();
            if (savedAnimation != null && !savedAnimation.isEmpty()) {
                playerWidget.setAnimation(savedAnimation);
            }

            // Create and add rotate button (above Change Skin button, aligned to the left edge)
            int rotateButtonSize = 20;
            int rotateButtonX = buttonX;
            int rotateButtonY = buttonY - rotateButtonSize - spacing;

            com.quickskin.mod.client.gui.widget.RotateButton rotateButton =
                    new com.quickskin.mod.client.gui.widget.RotateButton(
                            rotateButtonX,
                            rotateButtonY,
                            rotateButtonSize,
                            button -> playerWidget.toggleRotation()
                    );
            screenAccess.addRenderableWidget(rotateButton);

            // Clear animation buttons from previous screen
            animationButtons.clear();
            isAnimationDropdownOpen = false;

            // Only add animation buttons on title screen, not in-game (pause menu)
            if ("title".equals(screenType)) {
                // Create animation toggle button (right of rotate button)
                int animToggleWidth = 20;
                int animToggleX = buttonX + buttonWidth - animToggleWidth;
                int animToggleY = rotateButtonY;

                animationToggleButton = Button.builder(
                        Component.literal(">"),
                        button -> toggleAnimationDropdown()
                ).bounds(animToggleX, animToggleY, animToggleWidth, rotateButtonSize).build();
                screenAccess.addRenderableWidget(animationToggleButton);

                // Create numbered animation buttons (dropdown)
                java.util.List<String> availableAnimations = getAvailableAnimations();
                for (int i = 0; i < availableAnimations.size(); i++) {
                    final String animName = availableAnimations.get(i);
                    final int index = i;

                    Button animButton = Button.builder(
                            Component.literal(String.valueOf(index + 1)),
                            button -> {
                                // Set the animation on the player widget
                                if (playerWidget != null) {
                                    playerWidget.setAnimation(animName);
                                    // Save animation state for persistence across all screens
                                    setSharedAnimation(animName);
                                    QuickSkin.LOGGER.info("Animation {} activated: {}", index + 1, animName);
                                }
                                toggleAnimationDropdown();
                            }
                    ).bounds(animToggleX, animToggleY - (i + 1) * 22, animToggleWidth, rotateButtonSize).build();

                    animButton.visible = false;
                    animButton.active = false;
                    animationButtons.add(animButton);
                    screenAccess.addRenderableWidget(animButton);
                }
            }

            QuickSkin.LOGGER.debug("Added 'Change Skin' button at ({}, {}) and PlayerWidget at ({}, {}) for screen type '{}'",
                    buttonX, buttonY, widgetX, widgetY, screenType);
        });

        // Debug screen toggle (F3)
        ClientScreenInputEvent.KEY_PRESSED_PRE.register((client, screen, keyCode, scanCode, modifiers) -> {
            // This event is for screen key presses
            // Keybinds are handled separately in KeybindRegistry
            return EventResult.pass();
        });

        // Raw input (for global keybinds outside of screens)
        ClientRawInputEvent.KEY_PRESSED.register((client, keyCode, scanCode, action, modifiers) -> {
            // Keybinds will be registered separately
            // This is for raw key detection if needed
            return EventResult.pass();
        });

        // HUD render (for potential skin preview overlay)
        ClientGuiEvent.RENDER_HUD.register((guiGraphics, tickDelta) -> {
            // Get the setting from the client configuration
            boolean showOverlay = com.quickskin.mod.config.ClientConfig.getInstance().showSkinPreviewOverlay;
            if (showOverlay) {
                com.quickskin.mod.client.gui.overlay.SkinPreviewOverlay.render(guiGraphics, tickDelta);
            }
        });

        QuickSkin.LOGGER.info("Client events registered");
    }

    /**
     * Determine screen type for the player widget
     * Returns: "title" or "pause", or null if not a supported screen
     * ONLY adds widgets to Title Screen and Pause Screen
     */
    private static String determineScreenType(Screen screen) {
        if (screen instanceof TitleScreen) {
            return "title";
        } else if (screen instanceof PauseScreen) {
            return "pause";
        }

        // Don't add widgets to any other screens (skin menu, world selection, etc.)
        return null;
    }

    /**
     * Find the largest button on a screen (used for positioning reference)
     */
    private static Button findLargestButton(Screen screen) {
        Button largest = null;
        int maxWidth = 0;
        int maxY = -1;

        for (net.minecraft.client.gui.components.events.GuiEventListener listener : screen.children()) {
            if (listener instanceof Button button) {
                if (button.getWidth() > maxWidth) {
                    maxWidth = button.getWidth();
                }
            }
        }

        if (maxWidth > 0) {
            for (net.minecraft.client.gui.components.events.GuiEventListener listener : screen.children()) {
                if (listener instanceof Button button && button.getWidth() == maxWidth && button.getY() > maxY) {
                    maxY = button.getY();
                    largest = button;
                }
            }
        }

        return largest;
    }

    /**
     * Ensure player's own skin exists in the list
     * Downloads it from Mojang if not present
     * Can be called at any time (even before joining a world)
     */
    private static void ensurePlayerOwnSkinExists() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getUser() == null) {
            QuickSkin.LOGGER.warn("Cannot download player skin: Minecraft user not available");
            return;
        }

        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        String playerName = minecraft.getUser().getName();

        // Check if we already have the player's skin hash and it exists
        if (!config.playerOwnSkinHash.isEmpty()) {
            AssetMetadata existingMetadata = LocalAssetManager.getInstance().getMetadata(config.playerOwnSkinHash);
            if (existingMetadata != null) {
                // Player's skin already exists
                QuickSkin.LOGGER.info("Player's own skin already exists: {}", existingMetadata.friendlyName());
                return;
            }
        }

        // Download player's own skin (async, won't block startup)
        QuickSkin.LOGGER.info("Downloading player's own skin: {}", playerName);
        com.quickskin.mod.client.services.MojangApiService.getInstance().fetchSkinByUsername(playerName)
            .thenAccept(skinData -> {
                if (minecraft != null) {
                    minecraft.execute(() -> {
                        if (skinData != null) {
                            handlePlayerOwnSkinFetched(skinData);
                        } else {
                            QuickSkin.LOGGER.warn("Failed to fetch player's own skin");
                        }
                    });
                }
            })
            .exceptionally(throwable -> {
                QuickSkin.LOGGER.error("Error fetching player's own skin", throwable);
                return null;
            });
    }

    /**
     * Handle the fetched player's own skin data
     * Smart mode: checks if skin already exists before saving a duplicate
     */
    private static void handlePlayerOwnSkinFetched(com.quickskin.mod.client.services.MojangApiService.MojangSkinData skinData) {
        try {
            // Convert BufferedImage to byte array for hash computation
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(skinData.image, "PNG", baos);
            byte[] imageBytes = baos.toByteArray();

            // Compute hash of the downloaded skin
            String downloadedHash = com.quickskin.mod.common.util.HashUtil.computeHash(imageBytes);
            if (downloadedHash == null) {
                QuickSkin.LOGGER.error("Failed to compute hash for downloaded skin");
                return;
            }

            com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
            String hash;
            boolean wasAlreadyInList = false;

            // Smart check: see if this skin already exists in the list
            AssetMetadata existingSkin = LocalAssetManager.getInstance().getMetadata(downloadedHash);
            if (existingSkin != null) {
                // Skin already exists! Just mark it as the player's own skin
                hash = downloadedHash;
                wasAlreadyInList = true;
                QuickSkin.LOGGER.info("Player's skin already exists in list as '{}' - marking it as player's own skin",
                    existingSkin.friendlyName());
            } else {
                // If player had a different base skin before, delete it to prevent accumulation
                if (!config.playerOwnSkinHash.isEmpty() && !config.playerOwnSkinHash.equals(downloadedHash)) {
                    AssetMetadata oldBaseSkin = LocalAssetManager.getInstance().getMetadata(config.playerOwnSkinHash);
                    if (oldBaseSkin != null) {
                        QuickSkin.LOGGER.info("Deleting old base skin '{}' before saving new one", oldBaseSkin.friendlyName());
                        LocalAssetManager.getInstance().deleteAsset(config.playerOwnSkinHash);
                    }
                }

                // Skin doesn't exist, save it
                java.nio.file.Path skinPath = com.quickskin.mod.client.gui.util.SkinImporter.saveSkinImage(skinData.image, skinData.username);
                if (skinPath != null) {
                    QuickSkin.LOGGER.info("Successfully saved player's own skin: {}", skinData.username);

                    // Reload the asset manager to pick up the new file
                    LocalAssetManager.getInstance().reload();

                    // Verify the hash matches what we computed
                    hash = com.quickskin.mod.common.util.HashUtil.computeFileHash(skinPath);
                    if (hash == null || !hash.equals(downloadedHash)) {
                        QuickSkin.LOGGER.error("Hash mismatch after saving player's own skin");
                        return;
                    }
                } else {
                    QuickSkin.LOGGER.error("Failed to save player's own skin image");
                    return;
                }
            }

            // Store the hash as the player's own skin
            config.playerOwnSkinHash = hash;

            // If no active skin is set, auto-select the player's own skin
            if (config.activeSkinHash.isEmpty()) {
                config.activeSkinHash = hash;

                // Apply it to the player if they're in a world
                net.minecraft.client.player.LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
                if (player != null) {
                    LocalAssetManager assetManager = LocalAssetManager.getInstance();
                    AssetMetadata metadata = assetManager.getMetadata(hash);
                    if (metadata != null) {
                        String skinId = "local_skin:" + hash;
                        String modelType = assetManager.getSkinModelPreference(hash);

                        // If auto mode, use the detected model from the skin
                        if ("auto".equals(modelType)) {
                            modelType = metadata.skinModel();
                        }

                        com.quickskin.mod.client.services.PlayerAppearanceService.getInstance()
                            .applySkin(player.getUUID(), skinId, modelType);

                        QuickSkin.LOGGER.info("Auto-selected and applied player's own skin: {}", skinData.username);
                    }
                } else {
                    // Player not in world yet, skin will be applied when they join
                    QuickSkin.LOGGER.info("Auto-selected player's own skin (will apply on world join): {}", skinData.username);
                }
            } else {
                QuickSkin.LOGGER.info("Active skin already set, not auto-selecting player's own skin");
            }

            config.save();
            if (wasAlreadyInList) {
                QuickSkin.LOGGER.info("Player's own skin marked (already in list): hash {}", hash);
            } else {
                QuickSkin.LOGGER.info("Player's own skin added to list: hash {}", hash);
            }
        } catch (Exception e) {
            QuickSkin.LOGGER.error("Error handling player's own skin", e);
        }
    }

    /**
     * Restore saved skin and cape from config when player joins world
     */
    private static void restoreSavedAppearance(LocalPlayer player) {
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        com.quickskin.mod.client.services.LocalAssetManager assetManager =
                com.quickskin.mod.client.services.LocalAssetManager.getInstance();

        // Check if there's a saved skin
        if (!config.activeSkinHash.isEmpty()) {
            com.quickskin.mod.common.data.AssetMetadata metadata = assetManager.getMetadata(config.activeSkinHash);

            if (metadata != null) {
                // Apply the saved skin with the saved model type preference for this skin
                String skinId = "local_skin:" + metadata.hash();
                String modelType = assetManager.getSkinModelPreference(config.activeSkinHash);

                com.quickskin.mod.client.services.PlayerAppearanceService.getInstance()
                        .applySkin(player.getUUID(), skinId, modelType);

                QuickSkin.LOGGER.info("Restored saved skin: {} with model type: {}",
                        metadata.friendlyName(), modelType);
            } else {
                QuickSkin.LOGGER.warn("Saved skin hash not found in assets: {}", config.activeSkinHash);
            }
        } else if (!config.playerOwnSkinHash.isEmpty()) {
            // No skin selected, but player's own skin exists - auto-select it
            com.quickskin.mod.common.data.AssetMetadata metadata = assetManager.getMetadata(config.playerOwnSkinHash);

            if (metadata != null) {
                // Auto-select and apply the player's own skin
                config.activeSkinHash = config.playerOwnSkinHash;
                config.save();

                String skinId = "local_skin:" + metadata.hash();
                String modelType = assetManager.getSkinModelPreference(config.playerOwnSkinHash);

                // If auto mode, use the detected model from the skin
                if ("auto".equals(modelType)) {
                    modelType = metadata.skinModel();
                }

                com.quickskin.mod.client.services.PlayerAppearanceService.getInstance()
                        .applySkin(player.getUUID(), skinId, modelType);

                QuickSkin.LOGGER.info("Auto-selected and applied player's own skin: {} with model type: {}",
                        metadata.friendlyName(), modelType);
            }
        }

        // Check if there's a saved cape
        if (!config.activeCapeHash.isEmpty()) {
            String capeId = config.activeCapeHash;

            com.quickskin.mod.client.services.PlayerAppearanceService.getInstance()
                    .applyCape(player.getUUID(), capeId);

            QuickSkin.LOGGER.info("Restored saved cape: {}", capeId);
        }
    }

    /**
     * Auto-select player's own skin if no skin is currently selected
     * Called during initialization to ensure base skin is always selected
     */
    public static void autoSelectPlayerOwnSkin() {
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();

        // Check if no skin is selected but player's own skin exists
        if (config.activeSkinHash.isEmpty() && !config.playerOwnSkinHash.isEmpty()) {
            LocalAssetManager assetManager = LocalAssetManager.getInstance();
            AssetMetadata metadata = assetManager.getMetadata(config.playerOwnSkinHash);

            if (metadata != null) {
                // Auto-select the player's own skin
                config.activeSkinHash = config.playerOwnSkinHash;
                config.save();

                QuickSkin.LOGGER.info("Auto-selected player's own skin on startup: {}", metadata.friendlyName());
            } else {
                QuickSkin.LOGGER.debug("Player's own skin hash exists in config but not in assets (will be downloaded)");
            }
        } else if (!config.activeSkinHash.isEmpty()) {
            QuickSkin.LOGGER.debug("Active skin already set: {}", config.activeSkinHash);
        }
    }

    /**
     * Toggle the animation dropdown open/closed
     */
    private static void toggleAnimationDropdown() {
        isAnimationDropdownOpen = !isAnimationDropdownOpen;
        updateAnimationDropdownState();
    }

    /**
     * Update animation dropdown button visibility and toggle button text
     */
    private static void updateAnimationDropdownState() {
        if (animationToggleButton != null) {
            animationToggleButton.setMessage(Component.literal(isAnimationDropdownOpen ? "×" : ">"));
        }
        for (Button button : animationButtons) {
            button.visible = isAnimationDropdownOpen;
            button.active = isAnimationDropdownOpen;
        }
    }

    /**
     * Get list of available animations
     * Returns vanilla Minecraft animation states
     */
    private static java.util.List<String> getAvailableAnimations() {
        java.util.List<String> animations = new java.util.ArrayList<>();
        animations.add("idle");   // Button 1: Idle pose
        animations.add("walk");   // Button 2: Walking pose
        animations.add("sit");    // Button 3: Sitting pose
        return animations;
    }
}
```

### 6. Modify the GUI

Finally, I'll update the `PlayerSkinMenuScreen` and `ActionButtonsPanel` to reflect the cooldown status.

```java
// common/src/main/java/com/quickskin/mod/client/gui/panel/ActionButtonsPanel.java
package com.quickskin.mod.client.gui.panel;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * Panel that manages the action buttons at the bottom of the screen
 * (Import, HD Skin Website, Skin Website, Cape, Done)
 */
public class ActionButtonsPanel extends AbstractWidget {

    private static final int SPACING = 4;
    private static final int COMPONENT_HEIGHT = 20;

    private Button doneButton;

    /**
     * Callbacks for button actions
     */
    public record ActionCallbacks(
        Runnable onImport,
        Runnable onHdSkinWebsite,
        Runnable onSkinWebsite,
        Runnable onCape,
        Runnable onDone
    ) {}

    public ActionButtonsPanel(int x, int y, int width, int height, ActionCallbacks callbacks) {
        super(x, y, width, height, Component.empty());
    }

    /**
     * Initialize the panel and create all child widgets
     */
    public void init(com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen screen, ActionCallbacks callbacks) {
        int fullWidthX = getX();
        int fullComponentWidth = width;
        int bottomY = getY() + height;

        // Row 1 (Bottom-most): Done Button (full width)
        bottomY -= COMPONENT_HEIGHT;
        this.doneButton = Button.builder(Component.literal("Done"), button -> callbacks.onDone.run())
            .bounds(fullWidthX, bottomY, fullComponentWidth, COMPONENT_HEIGHT)
            .build();
        screen.registerWidget(this.doneButton);

        // Row 2: Import, HD Skin, Skin, Cape Buttons (4 equal width buttons)
        bottomY -= (COMPONENT_HEIGHT + SPACING);
        int fourButtonWidth = (fullComponentWidth - (SPACING * 3)) / 4;

        Button importButton = Button.builder(Component.literal("Import Skin"), button -> callbacks.onImport.run())
            .bounds(fullWidthX, bottomY, fourButtonWidth, COMPONENT_HEIGHT).build();
        screen.registerWidget(importButton);

        Button hdSkinWebsiteButton = Button.builder(Component.literal("HD Skin Website"), button -> callbacks.onHdSkinWebsite.run())
            .bounds(fullWidthX + fourButtonWidth + SPACING, bottomY, fourButtonWidth, COMPONENT_HEIGHT).build();
        screen.registerWidget(hdSkinWebsiteButton);

        Button skinWebsiteButton = Button.builder(Component.literal("Skin Website"), button -> callbacks.onSkinWebsite.run())
            .bounds(fullWidthX + (fourButtonWidth + SPACING) * 2, bottomY, fourButtonWidth, COMPONENT_HEIGHT).build();
        screen.registerWidget(skinWebsiteButton);

        Button capeButton = Button.builder(Component.literal("Cape"), button -> callbacks.onCape.run())
            .bounds(fullWidthX + (fourButtonWidth + SPACING) * 3, bottomY, fourButtonWidth, COMPONENT_HEIGHT).build();
        screen.registerWidget(capeButton);
    }

    public Button getDoneButton() {
        return this.doneButton;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // This panel doesn't render anything itself - child widgets handle rendering
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        // No narration needed for container panel
    }
}
```

```java
// common/src/main/java/com/quickskin/mod/client/gui/screen/PlayerSkinMenuScreen.java
package com.quickskin.mod.client.gui.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.gui.panel.ActionButtonsPanel;
import com.quickskin.mod.client.gui.panel.LinkButtonsPanel;
import com.quickskin.mod.client.gui.panel.PlayerPreviewPanel;
import com.quickskin.mod.client.gui.panel.SkinListPanel;
import com.quickskin.mod.client.gui.util.FileDialogHelper;
import com.quickskin.mod.client.gui.util.GuiScaleManager;
import com.quickskin.mod.client.gui.util.SkinImporter;
import com.quickskin.mod.client.gui.widget.ErrorToast;
import com.quickskin.mod.client.gui.widget.SkinEntry;
import com.quickskin.mod.client.services.CooldownService;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.MojangApiService;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Button;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.TextureQuality;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import net.minecraft.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Main skin selection menu for QuickSkin
 * Opens when K key is pressed
 */
@Environment(EnvType.CLIENT)
public class PlayerSkinMenuScreen extends Screen {

    @Nullable
    private final Screen parent;

    // Panels
    private SkinListPanel skinListPanel;
    private PlayerPreviewPanel playerPreviewPanel;
    private ActionButtonsPanel actionButtonsPanel;

    // Panel dimensions
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;

    // GUI scale management
    private boolean guiScaleForced = false;
    private boolean isClosing = false;

    // Player preview rotation state (preserved across resizes)
    private float savedBodyYaw = 20.0f;
    private float savedTargetRotation = 20.0f;

    // Current model type (preserved across resizes)
    private String savedModelType = null;

    // Constants
    private static final int MIN_PANEL_WIDTH = 340;
    private static final int MAX_PANEL_WIDTH = 600;
    private static final int MIN_PANEL_HEIGHT = 280;

    // --- NEW ---: Constants for the background effect
    private static final ResourceLocation STAR_PATTERN_TEXTURE = new ResourceLocation(QuickSkin.MOD_ID, "textures/gui/background/star_pattern.png");
    private static final ResourceLocation VIGNETTE_LOCATION = new ResourceLocation("textures/misc/vignette.png");

    // Error toasts
    private final List<ErrorToast> errorToasts = new ArrayList<>();


    // Mojang search widgets
    private EditBox usernameSearchField;
    private Button searchButton;
    private boolean isSearching = false;

    public PlayerSkinMenuScreen(@Nullable Screen parent) {
        super(Component.literal("Quick Skin"));
        this.parent = parent;
    }

    @Override
    public void tick() {
        super.tick();
        updateDoneButtonState();
    }

    private void updateDoneButtonState() {
        if (this.actionButtonsPanel == null) return;
        Button doneButton = this.actionButtonsPanel.getDoneButton();
        if (doneButton == null) return;

        // Cooldown does not apply in singleplayer
        if (this.minecraft != null && this.minecraft.isSingleplayer()) {
            if (!doneButton.active) {
                doneButton.active = true;
                doneButton.setMessage(Component.literal("Done"));
                doneButton.setTooltip(null);
            }
            return;
        }

        long remainingSeconds = CooldownService.getInstance().getRemainingCooldownSeconds();
        if (remainingSeconds > 0) {
            doneButton.active = false;
            doneButton.setMessage(Component.literal("On Cooldown (" + remainingSeconds + "s)"));
            doneButton.setTooltip(Tooltip.create(Component.literal("You must wait before changing your skin again.")));
        } else {
            if (!doneButton.active) {
                doneButton.active = true;
                doneButton.setMessage(Component.literal("Done"));
                doneButton.setTooltip(null);
            }
        }
    }

    @Override
    protected void init() {
        // Force GUI scale for consistent appearance
        if (!guiScaleForced && !isClosing) {
            guiScaleForced = true;
            int optimalScale = GuiScaleManager.getOptimalMenuScale();
            if (GuiScaleManager.setMenuGuiScale(optimalScale)) {
                // Scale was changed and resizeDisplay() was called, which will trigger init() again
                return;
            }
        }

        super.init();
        clearWidgets();

        // Save rotation state and model type from existing player preview panel before it's destroyed
        if (playerPreviewPanel != null) {
            com.quickskin.mod.client.gui.widget.PlayerWidget widget = playerPreviewPanel.getPlayerWidget();
            if (widget != null) {
                savedBodyYaw = widget.getBodyYaw();
                savedTargetRotation = widget.getTargetYRotation();
            }
            // Save the current model type to preserve it across resizes
            savedModelType = playerPreviewPanel.getCurrentModelType();
        }

        // Calculate panel dimensions based on screen size
        calculatePanelDimensions();

        // Use consistent sizing values
        int scaledPadding = 6;
        int scaledSpacing = 4;
        int scaledComponentHeight = 20;

        // Calculate panel areas
        int leftPanelWidth = (int) (panelWidth * 0.6f);
        int rightPanelWidth = (int) (panelWidth * 0.35f);

        int componentX = panelX + scaledPadding;
        int yPos = panelY + scaledPadding + scaledComponentHeight + scaledPadding;

        // Create Mojang username search field (below title)
        // Match the width of the skin list panel
        int searchButtonWidth = 60;
        // Align with skin entry highlight containers
        // Entry highlights: left = getRowLeft() (list x + ~4px), highlightLeft = left - 4px
        int searchFieldX = componentX + 4;
        int searchFieldWidth = leftPanelWidth - 4;

        usernameSearchField = new EditBox(
                this.font,
                searchFieldX,
                yPos,
                searchFieldWidth - searchButtonWidth - scaledSpacing,
                scaledComponentHeight,
                Component.literal("Search by username")
        );
        usernameSearchField.setSuggestion("Enter a player's username...");
        usernameSearchField.setMaxLength(16);
        usernameSearchField.setResponder(text -> {
            onUsernameFieldChanged(text);
            // Update suggestion visibility
            if (text.isEmpty()) {
                usernameSearchField.setSuggestion("Enter a player's username...");
            } else {
                usernameSearchField.setSuggestion("");
            }
        });
        addRenderableWidget(usernameSearchField);

        searchButton = Button.builder(
                        Component.literal("Search"),
                        button -> searchMojangSkin()
                )
                .bounds(
                        searchFieldX + searchFieldWidth - searchButtonWidth,
                        yPos,
                        searchButtonWidth,
                        scaledComponentHeight
                )
                .build();
        addRenderableWidget(searchButton);
        searchButton.active = false;

        // Adjust the yPos for components below the search field
        yPos += scaledComponentHeight + scaledSpacing;

        // Calculate list height with proper spacing
        // Title + padding + search field + spacing + extra spacing for the list
        int topSectionHeight = scaledPadding + scaledComponentHeight + scaledPadding + scaledComponentHeight + scaledSpacing + scaledSpacing;
        int bottomSectionHeight = (scaledComponentHeight * 3) + (scaledSpacing * 2) + scaledPadding;
        int listHeight = panelHeight - topSectionHeight - bottomSectionHeight;

        // Create Skin List Panel (left side)
        skinListPanel = new SkinListPanel(
                componentX,
                yPos,
                leftPanelWidth,
                listHeight,
                this.minecraft,
                this::onSkinSelected
        );
        skinListPanel.init(this);

        // Calculate bottom section dimensions first (needed for player preview panel)
        int fullWidthX = panelX + scaledPadding;
        int fullComponentWidth = panelWidth - (scaledPadding * 2);
        int fourButtonWidth = (fullComponentWidth - (scaledSpacing * 3)) / 4;

        // Calculate where action buttons will be
        int actionButtonsBottomY = panelY + panelHeight - scaledPadding;
        int actionPanelHeight = (scaledComponentHeight * 2) + scaledSpacing;

        // Model buttons row (Row 3: above Import/HD/Skin/Cape buttons)
        int modelButtonsY = actionButtonsBottomY - actionPanelHeight - scaledComponentHeight - scaledSpacing;
        int modelButtonsX = fullWidthX + (fourButtonWidth + scaledSpacing) * 3;

        // Create Player Preview Panel (right side)
        int playerWidgetX = panelX + panelWidth - rightPanelWidth - scaledPadding;
        int playerWidgetY = yPos;
        int availableHeightForWidget = panelHeight - topSectionHeight - bottomSectionHeight;

        playerPreviewPanel = new PlayerPreviewPanel(
                playerWidgetX,
                playerWidgetY,
                rightPanelWidth,
                availableHeightForWidget
        );
        playerPreviewPanel.initPlayerWidget(this);

        // Set up model type change callback to apply model to actual player
        playerPreviewPanel.setModelTypeChangeCallback(this::onModelTypeChanged);

        // Create model buttons positioned above the cape button
        playerPreviewPanel.initModelButtons(
                this,
                modelButtonsX,
                modelButtonsY,
                fourButtonWidth,
                scaledComponentHeight,
                scaledSpacing
        );

        // Create Action Buttons Panel (bottom)
        int bottomY = actionButtonsBottomY - (scaledComponentHeight * 2) - scaledSpacing;

        ActionButtonsPanel.ActionCallbacks callbacks = new ActionButtonsPanel.ActionCallbacks(
                this::openImportDialog,
                () -> {
                    // HD Skin Website
                    if (this.minecraft != null) {
                        this.minecraft.options.chatLinksPrompt().set(false);
                        Util.getPlatform().openUri("https://mcskins.top/128x128/");
                    }
                },
                () -> {
                    // Skin Website
                    if (this.minecraft != null) {
                        this.minecraft.options.chatLinksPrompt().set(false);
                        Util.getPlatform().openUri("https://laby.net/skins?order=trending_30d");
                    }
                },
                () -> {
                    // Open cape selection screen
                    if (minecraft != null) {
                        minecraft.setScreen(new PlayerCapeMenuScreen(this));
                    }
                },
                this::onClose
        );

        actionButtonsPanel = new ActionButtonsPanel(
                fullWidthX,
                bottomY,
                fullComponentWidth,
                actionPanelHeight,
                callbacks
        );
        actionButtonsPanel.init(this, callbacks);

        // Create Link Buttons Panel (top-right)
        int linkButtonY = panelY + scaledPadding;
        int linkPanelWidth = (scaledComponentHeight + scaledSpacing) * 4;
        int linkPanelX = panelX + panelWidth - linkPanelWidth - scaledPadding;

        LinkButtonsPanel linkButtonsPanel = new LinkButtonsPanel(
                linkPanelX,
                linkButtonY,
                linkPanelWidth,
                scaledComponentHeight
        );
        linkButtonsPanel.init(this);

        // Restore saved model type and active skin from config
        restoreSavedState();

        updateDoneButtonState();
    }

    /**
     * Restore the saved model type and active skin from config
     */
    private void restoreSavedState() {
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();

        // Check if we're restoring from a resize (savedModelType is not null)
        boolean isResizing = savedModelType != null;

        // Restore active skin selection first
        AssetMetadata selectedSkin = null;
        if (!config.activeSkinHash.isEmpty() && skinListPanel != null) {
            AssetMetadata metadata = LocalAssetManager.getInstance().getMetadata(config.activeSkinHash);
            if (metadata != null) {
                // Don't trigger callback during resize - we'll set model type manually
                skinListPanel.setSelected(metadata, !isResizing);
                selectedSkin = metadata;
            }
        } else if (config.activeSkinHash.isEmpty() && !config.playerOwnSkinHash.isEmpty() && skinListPanel != null) {
            // If no active skin is set, auto-select the player's own skin
            AssetMetadata playerOwnSkin = LocalAssetManager.getInstance().getMetadata(config.playerOwnSkinHash);
            if (playerOwnSkin != null) {
                // Don't trigger callback during resize - we'll set model type manually
                skinListPanel.setSelected(playerOwnSkin, !isResizing);
                selectedSkin = playerOwnSkin;
                QuickSkin.LOGGER.info("Auto-selected player's own skin in menu");
            }
        }

        // Restore model type preference for the selected skin
        if (playerPreviewPanel != null && selectedSkin != null && isResizing) {
            // During resize, use the saved model type
            playerPreviewPanel.setCurrentModelType(savedModelType);

            // Update the preview with the correct skin
            playerPreviewPanel.updateSkin(
                    selectedSkin,
                    LocalAssetManager.getInstance().getTextureLocation(selectedSkin.hash(), com.quickskin.mod.common.data.TextureQuality.FULL)
            );

            // Clear saved model type after using it
            savedModelType = null;
        }
        // Note: If not resizing, onSkinSelected callback will handle loading the preference

        // Restore active cape selection
        if (!config.activeCapeHash.isEmpty() && playerPreviewPanel != null) {
            String capeId = config.activeCapeHash;
            ResourceLocation capeLocation = getCapeLocationFromId(capeId);
            if (capeLocation != null) {
                // Register animation if this is an animated cape
                registerCapeAnimationIfNeeded(capeId, capeLocation);

                playerPreviewPanel.updateCape(capeLocation, capeId);
            }
        }

        // Restore rotation state
        if (playerPreviewPanel != null) {
            com.quickskin.mod.client.gui.widget.PlayerWidget widget = playerPreviewPanel.getPlayerWidget();
            if (widget != null) {
                widget.setRotationState(savedBodyYaw, savedTargetRotation);
            }
        }
    }

    /**
     * Convert cape ID to ResourceLocation
     * Cape ID format: "local_cape:hash" or "known:id"
     */
    @Nullable
    private ResourceLocation getCapeLocationFromId(String capeId) {
        if (capeId.startsWith("local_cape:")) {
            // Local cape - extract hash and get texture location
            String hash = capeId.substring("local_cape:".length());
            return LocalAssetManager.getInstance().getTextureLocation(hash, com.quickskin.mod.common.data.TextureQuality.FULL);
        } else if (capeId.startsWith("known:")) {
            // Known cape - extract ID and get from KnownCapes enum
            String id = capeId.substring("known:".length());
            com.quickskin.mod.common.data.KnownCapes knownCape = com.quickskin.mod.common.data.KnownCapes.getById(id);
            if (knownCape != null) {
                return knownCape.getTextureLocation();
            } else {
                QuickSkin.LOGGER.warn("Unknown cape ID: {}", id);
                return null;
            }
        }
        return null;
    }

    /**
     * Register cape animation if the cape is animated
     * @param capeId Cape ID (format: "local_cape:hash" or "known:id")
     * @param capeLocation Texture location (atlas)
     */
    private void registerCapeAnimationIfNeeded(String capeId, ResourceLocation capeLocation) {
        // Determine animation ID from cape ID
        if (capeId.startsWith("local_cape:")) {
            String hash = capeId.substring("local_cape:".length());
            String animationId = "cape_" + hash;

            // Check if this local cape has animation metadata
            com.quickskin.mod.common.data.AnimationMetadata metadata =
                LocalAssetManager.getInstance().getAnimationMetadata(hash);

            if (metadata != null && metadata.frameCount() > 1) {
                // Load atlas image from cache
                java.awt.image.BufferedImage atlasImage =
                    LocalAssetManager.getInstance().getSourceImage(hash);

                if (atlasImage != null) {
                    // Register animation
                    QuickSkin.LOGGER.info("Registering animation for cape in skin menu: {}", animationId);
                    com.quickskin.mod.client.services.AnimatedTextureManager.getInstance()
                        .registerAnimation(animationId, capeId, capeLocation, atlasImage, metadata);
                }
            }
        }
        // Known capes might also be animated
        // For now, we'll skip this as known capes use a different system
        // but you could add similar logic if needed
    }

    /**
     * Calculate panel dimensions based on screen size
     * Uses FIXED sizes since we're forcing GUI scale
     */
    private void calculatePanelDimensions() {
        // Calculate panel dimensions as percentages of screen for flexible sizing
        int desiredWidth = (int)(this.width * 0.5f);
        int desiredHeight = (int)(this.height * 0.8f);

        panelWidth = Mth.clamp(
                desiredWidth,
                MIN_PANEL_WIDTH,
                Math.min(MAX_PANEL_WIDTH, this.width - 80)
        );

        panelHeight = Mth.clamp(
                desiredHeight,
                MIN_PANEL_HEIGHT,
                this.height - 80
        );

        // Adjust panel size if components don't fit
        int minRequiredHeight = calculateMinRequiredHeight();
        if (panelHeight < minRequiredHeight) {
            panelHeight = Math.min(minRequiredHeight, this.height - 40);
        }

        int minRequiredWidth = calculateMinRequiredWidth();
        if (panelWidth < minRequiredWidth) {
            panelWidth = Math.min(minRequiredWidth, this.width - 40);
        }

        // Center the panel
        panelX = (this.width - panelWidth) / 2;
        panelY = (this.height - panelHeight) / 2;
    }

    /**
     * Calculate minimum required height for all components
     */
    private int calculateMinRequiredHeight() {
        int scaledPadding = 6;
        int scaledComponentHeight = 20;
        int scaledSpacing = 4;
        // Title + username row + list (min 3 entries) + 3 button rows
        return scaledPadding * 4 + scaledComponentHeight * 7 + scaledSpacing * 4 + 120; // 120 for min list height
    }

    /**
     * Calculate minimum required width for all components
     */
    private int calculateMinRequiredWidth() {
        int scaledPadding = 6;
        int scaledSpacing = 4;
        // Need space for left panel + right panel (player widget) + padding
        return 220 + 150 + scaledPadding * 3 + scaledSpacing * 2;
    }

    // --- NEW ---: Method to render the animated background
    /**
     * Renders a moving star pattern background similar to the effect on the example website.
     * This includes a tiled, scrolling texture and a vignette overlay for depth.
     */
    private void renderBackgroundEffects(GuiGraphics graphics, float partialTick) {
        // 1. Fill with solid black as a base layer.
        graphics.fill(0, 0, this.width, this.height, 0xFF000000);

        // 2. Render the moving star pattern.
        renderStarPattern(graphics, partialTick);

        // 3. Render a vignette overlay for a darker, focused feel around the edges.
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(0.0F, 0.0F, 0.0F, 0.75F);
        // Stretch Minecraft's vignette texture to cover the entire screen.
        graphics.blit(VIGNETTE_LOCATION, 0, 0, 0, 0.0F, 0.0F, this.width, this.height, this.width, this.height);

        // 4. Reset render state to avoid affecting other GUI elements.
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * Renders the animated star pattern
     */
    private void renderStarPattern(GuiGraphics graphics, float partialTick) {
        // Actual texture size
        int textureSize = 1024;
        // The size to render each tile (smaller = more stars visible).
        int tileSize = 55;
        // Animation speed: pixels per second
        double pixelsPerSecond = 8.0;

        // Use Minecraft's smooth game time (ticks + partial tick) for perfectly smooth animation
        int tickCount = this.minecraft != null ? this.minecraft.gui.getGuiTicks() : 0;
        double smoothTime = (tickCount + partialTick) / 20.0; // Convert to seconds
        double offset = (smoothTime * pixelsPerSecond) % tileSize;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 0.15F);

        // Calculate how many tiles are needed to cover the screen
        int xTiles = Mth.ceil((float) this.width / tileSize) + 2;
        int yTiles = Mth.ceil((float) this.height / tileSize) + 1;

        var pose = graphics.pose();
        pose.pushPose();

        for (int y = 0; y < yTiles; ++y) {
            for (int x = 0; x < xTiles; ++x) {
                // Draw each tile, applying the horizontal scroll offset.
                double drawX = x * tileSize - offset;
                double drawY = y * tileSize;

                // Draw the full texture scaled down to tileSize x tileSize
                pose.pushPose();
                pose.translate(drawX, drawY, 0);
                pose.scale(tileSize / (float)textureSize, tileSize / (float)textureSize, 1.0f);
                graphics.blit(STAR_PATTERN_TEXTURE, 0, 0, 0, 0.0f, 0.0f, textureSize, textureSize, textureSize, textureSize);
                pose.popPose();
            }
        }

        pose.popPose();

        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Render the animated background
        this.renderBackgroundEffects(graphics, partialTick);

        // Render panel background (frosted glass effect)
        renderPanel(graphics);

        // Render title
        graphics.drawCenteredString(
                this.font,
                this.title,
                this.width / 2,
                panelY + 10,
                0xFFFFFF
        );

        // Render widgets (buttons, etc.)
        super.render(graphics, mouseX, mouseY, partialTick);

        // Render error toasts (on top of everything)
        renderErrorToasts(graphics);
    }

    /**
     * Render the main panel with frosted glass effect
     */
    private void renderPanel(GuiGraphics graphics) {
        // Panel background (dark semi-transparent)
        graphics.fill(
                panelX, panelY,
                panelX + panelWidth, panelY + panelHeight,
                0xB0000000
        );

        // Panel outline (subtle white)
        // Top
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 1, 0x60FFFFFF);
        // Bottom
        graphics.fill(panelX, panelY + panelHeight - 1, panelX + panelWidth, panelY + panelHeight, 0x60FFFFFF);
        // Left (shortened by 1px at top and bottom to avoid corner overlap)
        graphics.fill(panelX, panelY + 1, panelX + 1, panelY + panelHeight - 1, 0x60FFFFFF);
        // Right (shortened by 1px at top and bottom to avoid corner overlap)
        graphics.fill(panelX + panelWidth - 1, panelY + 1, panelX + panelWidth, panelY + panelHeight - 1, 0x60FFFFFF);
    }

    @Override
    public void removed() {
        super.removed();

        // Only restore GUI scale if we're actually closing (not just opening a modal)
        // The isClosing flag is set by onClose() when truly exiting the menu
        if (isClosing) {
            restoreGuiScaleIfNeeded();
        }
    }

    @Override
    public void onClose() {
        // Mark that we're truly closing (not just opening a modal)
        isClosing = true;

        // Restore GUI scale before closing
        restoreGuiScaleIfNeeded();

        // Return to parent screen (or null to return to game)
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    /**
     * Restore the original GUI scale if it was forced by this screen.
     * This method is idempotent and can be called multiple times safely.
     */
    private void restoreGuiScaleIfNeeded() {
        if (guiScaleForced) {
            guiScaleForced = false;
            GuiScaleManager.restoreOriginalGuiScale();
            QuickSkin.LOGGER.info("PlayerSkinMenuScreen - GUI scale restored");
        }
    }

    @Override
    public boolean isPauseScreen() {
        // Don't pause game when this screen is open
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Allow ESC to close
        if (keyCode == 256) { // ESC key
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Handle debug positioning mode
        if (com.quickskin.mod.client.rendering.PlayerModelRenderer.handleDebugMousePressed((int)mouseX, (int)mouseY, button)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        // Handle debug positioning mode
        if (com.quickskin.mod.client.rendering.PlayerModelRenderer.handleDebugMouseDragged((int)mouseX, (int)mouseY, button)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        // Handle debug positioning mode
        if (com.quickskin.mod.client.rendering.PlayerModelRenderer.handleDebugMouseReleased((int)mouseX, (int)mouseY, button)) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        QuickSkin.LOGGER.info("Files dropped: {}", files.size());

        // Filter for PNG files
        List<Path> pngFiles = files.stream()
                .filter(path -> path.toString().toLowerCase().endsWith(".png"))
                .toList();

        if (pngFiles.isEmpty()) {
            QuickSkin.LOGGER.warn("No PNG files in drop");
            return;
        }

        QuickSkin.LOGGER.info("Processing {} PNG files", pngFiles.size());

        // Import all PNG files
        if (this.minecraft != null) {
            this.minecraft.execute(() -> {
                List<AssetMetadata> imported = SkinImporter.importSkins(pngFiles.toArray(new Path[0]));

                if (!imported.isEmpty()) {
                    QuickSkin.LOGGER.info("Successfully imported {} skins", imported.size());

                    // Reload the skin list
                    refreshSkinList();

                    // Auto-select the first imported skin
                    if (skinListPanel != null && !imported.isEmpty()) {
                        AssetMetadata firstImported = imported.get(0);
                        skinListPanel.setSelected(firstImported);
                    }
                }
            });
        }
    }

    /**
     * Called when a skin is selected from the list
     */
    public void onSkinSelected(SkinEntry entry) {
        if (this.minecraft != null && !this.minecraft.isSingleplayer()) {
            if (CooldownService.getInstance().isCooldownActive()) {
                showError(Component.literal("You must wait before changing your skin again."));
                if (this.minecraft != null) {
                    this.minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.VILLAGER_NO, 1.0F, 1.0F));
                }
                return;
            }
        }

        if (playerPreviewPanel != null && entry != null) {
            AssetMetadata metadata = entry.getMetadata();

            // Get the model type preference for this specific skin
            String modelType = LocalAssetManager.getInstance().getSkinModelPreference(metadata.hash());
            QuickSkin.LOGGER.info("onSkinSelected: Loading model preference for skin {}: {}", metadata.friendlyName(), modelType);

            // Update the model buttons to reflect this skin's preference
            playerPreviewPanel.setCurrentModelType(modelType);

            // Update player preview with selected skin
            playerPreviewPanel.updateSkin(
                    metadata,
                    LocalAssetManager.getInstance().getTextureLocation(metadata.hash(), TextureQuality.FULL)
            );

            // Apply skin to the actual player in-game
            if (this.minecraft != null && this.minecraft.player != null) {
                String skinId = "local_skin:" + metadata.hash();

                // Pass the model type directly - ModelService will handle "auto" detection
                com.quickskin.mod.client.services.PlayerAppearanceService.getInstance()
                        .applySkin(this.minecraft.player.getUUID(), skinId, modelType);
                QuickSkin.LOGGER.info("Applied skin to player: {} with model type: {}",
                        metadata.friendlyName(), modelType);
            }

            // Save the active skin hash to config
            com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
            config.activeSkinHash = metadata.hash();
            config.save();
        }
    }

    /**
     * Called when model type is changed via the model buttons
     */
    private void onModelTypeChanged(String newModelType) {
        // Get the currently selected skin entry
        SkinEntry selectedEntry = skinListPanel != null ? skinListPanel.getSelected() : null;

        if (selectedEntry != null) {
            AssetMetadata metadata = selectedEntry.getMetadata();
            String skinId = "local_skin:" + metadata.hash();

            QuickSkin.LOGGER.info("Changed model type to: {} for skin: {}", newModelType, metadata.friendlyName());

            // Save the model type preference for THIS SPECIFIC SKIN
            LocalAssetManager.getInstance().setSkinModelPreference(metadata.hash(), newModelType);

            // Apply to the actual player in-game (if in-game)
            if (this.minecraft != null && this.minecraft.player != null) {
                // Pass the model type directly - ModelService will handle "auto" detection
                com.quickskin.mod.client.services.PlayerAppearanceService.getInstance()
                        .applySkin(this.minecraft.player.getUUID(), skinId, newModelType);
            }
        }
    }

    /**
     * Get the font renderer
     */
    public net.minecraft.client.gui.Font getFont() {
        return this.font;
    }

    /**
     * Public wrapper for addRenderableWidget to allow panels to add widgets
     */
    public <T extends net.minecraft.client.gui.components.events.GuiEventListener & net.minecraft.client.gui.components.Renderable & net.minecraft.client.gui.narration.NarratableEntry> void registerWidget(T widget) {
        this.addRenderableWidget(widget);
    }

    /**
     * Open file dialog to import a skin
     */
    private void openImportDialog() {
        FileDialogHelper.openSkinFileDialog("Select Skin File", this::handleSkinImport);
    }

    /**
     * Handle imported skin file
     */
    private void handleSkinImport(Path filePath) {
        if (filePath == null) {
            return;
        }

        QuickSkin.LOGGER.info("Importing skin: {}", filePath);

        // Import on main thread (Minecraft.getInstance().execute runs on main thread)
        if (this.minecraft != null) {
            this.minecraft.execute(() -> {
                AssetMetadata metadata = SkinImporter.importSkin(filePath);
                if (metadata != null) {
                    QuickSkin.LOGGER.info("Successfully imported skin: {}", metadata.friendlyName());

                    // Reload the skin list
                    refreshSkinList();

                    // Auto-select the imported skin
                    if (skinListPanel != null) {
                        skinListPanel.setSelected(metadata);
                    }
                } else {
                    QuickSkin.LOGGER.error("Failed to import skin: {}", filePath);
                    // Show error message to user
                    showError(Component.literal("Failed to import skin"));
                }
            });
        }
    }

    /**
     * Refresh the skin list after importing
     */
    private void refreshSkinList() {
        if (skinListPanel != null) {
            skinListPanel.refresh();
        }
    }

    /**
     * Show an error toast message
     */
    public void showError(Component message) {
        errorToasts.add(new ErrorToast(message));
    }

    /**
     * Render error toasts
     */
    private void renderErrorToasts(GuiGraphics graphics) {
        errorToasts.removeIf(toast -> !toast.render(graphics, width));
    }

    /**
     * Show deletion confirmation dialog
     */
    public void showDeleteConfirmation(AssetMetadata metadata) {
        if (minecraft == null) return;

        String displayName = truncateFileName(metadata.friendlyName());
        minecraft.setScreen(new DeletionConfirmScreen(
                this,
                Component.literal("Delete Skin?"),
                Component.literal("Are you sure you want to delete \"" + displayName + "\"?"),
                (confirmed) -> {
                    if (confirmed) {
                        // Confirm deletion
                        deleteSkin(metadata);
                    }
                    // Return to skin menu screen
                    if (minecraft != null) {
                        minecraft.setScreen(this);
                    }
                },
                true
        ));
    }

    /**
     * Show rename dialog for a skin
     */
    public void showRenameDialog(AssetMetadata metadata) {
        if (minecraft == null) return;

        minecraft.setScreen(new RenameScreen(
                this,
                Component.literal("Rename Skin File"),
                Component.empty(),
                metadata.friendlyName(),
                (newName) -> {
                    // Rename the skin
                    renameSkin(metadata, newName);
                    // Return to skin menu screen
                    if (minecraft != null) {
                        minecraft.setScreen(this);
                    }
                }
        ));
    }

    /**
     * Truncate filename to 35 characters, adding ellipsis if needed
     */
    private String truncateFileName(String name) {
        if (name.length() <= 35) {
            return name;
        }
        return name.substring(0, 32) + "...";
    }

    /**
     * Delete a skin from local storage
     */
    private void deleteSkin(AssetMetadata metadata) {
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();

        // Prevent deletion of the player's own skin
        if (metadata.hash().equals(config.playerOwnSkinHash)) {
            QuickSkin.LOGGER.warn("Cannot delete player's own skin: {}", metadata.friendlyName());
            showError(Component.literal("Cannot delete your own skin!"));
            return;
        }

        try {
            // Delete the file
            Files.deleteIfExists(metadata.path());

            if (minecraft != null) {
                minecraft.getSoundManager().play(
                        net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                                SoundEvents.UI_BUTTON_CLICK.value(), 1.0f
                        )
                );
            }

            // Refresh the asset manager and skin list
            LocalAssetManager.getInstance().discoverLocalAssets();
            refreshSkinList();

            // Auto-select the player's own skin after deletion
            if (!config.playerOwnSkinHash.isEmpty() && skinListPanel != null) {
                AssetMetadata playerOwnSkin = LocalAssetManager.getInstance().getMetadata(config.playerOwnSkinHash);
                if (playerOwnSkin != null) {
                    skinListPanel.setSelected(playerOwnSkin, true);
                    QuickSkin.LOGGER.info("Auto-selected player's own skin after deletion");
                }
            }

            QuickSkin.LOGGER.info("Deleted skin: {}", metadata.friendlyName());
        } catch (IOException e) {
            QuickSkin.LOGGER.error("Failed to delete skin: {}", metadata.friendlyName(), e);
            showError(Component.literal("Failed to delete skin: " + e.getMessage()));
        }
    }

    /**
     * Rename a skin file
     */
    private void renameSkin(AssetMetadata metadata, String newName) {
        LocalAssetManager.RenameResult result = LocalAssetManager.getInstance()
                .renameLocalAsset(metadata.hash(), newName);

        switch (result) {
            case SUCCESS:
                QuickSkin.LOGGER.info("Successfully renamed skin to: {}", newName);

                // Play success sound
                if (minecraft != null) {
                    minecraft.getSoundManager().play(
                            net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                                    SoundEvents.UI_BUTTON_CLICK.value(), 1.0f
                            )
                    );
                }

                // Refresh the skin list to show the new name
                refreshSkinList();

                // Re-select the renamed skin
                if (skinListPanel != null) {
                    AssetMetadata updatedMetadata = LocalAssetManager.getInstance().getMetadata(metadata.hash());
                    if (updatedMetadata != null) {
                        skinListPanel.setSelected(updatedMetadata);
                    }
                }
                break;

            case NAME_TAKEN:
                QuickSkin.LOGGER.warn("Rename failed: Name already exists");
                showError(Component.literal("Error: A skin file with that name already exists."));
                break;

            case INVALID_NAME:
                QuickSkin.LOGGER.warn("Rename failed: Invalid name");
                showError(Component.literal("Error: The name contains invalid characters or is empty."));
                break;

            case IO_ERROR:
                QuickSkin.LOGGER.error("Rename failed: IO error");
                showError(Component.literal("Error: Could not rename the file. See logs."));
                break;

            case NOT_FOUND:
                QuickSkin.LOGGER.error("Rename failed: File not found");
                showError(Component.literal("Error: Could not find the original file."));
                break;
        }
    }

    /**
     * Called when the username search field changes
     */
    private void onUsernameFieldChanged(String text) {
        if (searchButton != null) {
            searchButton.active = !text.trim().isEmpty() && !isSearching;
        }
    }

    /**
     * Search for a skin using Mojang API
     */
    private void searchMojangSkin() {
        if (usernameSearchField == null || isSearching) {
            return;
        }

        String username = usernameSearchField.getValue().trim();
        if (username.isEmpty()) {
            return;
        }

        // Disable search while fetching
        isSearching = true;
        searchButton.active = false;
        searchButton.setMessage(Component.literal("Searching..."));

        QuickSkin.LOGGER.info("Searching for Mojang skin: {}", username);

        // Fetch skin asynchronously
        MojangApiService.getInstance().fetchSkinByUsername(username)
                .thenAccept(skinData -> {
                    // Execute on main thread
                    if (this.minecraft != null) {
                        this.minecraft.execute(() -> {
                            if (skinData != null) {
                                handleMojangSkinFetched(skinData);
                            } else {
                                showError(Component.literal("Player not found: " + username));
                                resetSearchButton();
                            }
                        });
                    }
                })
                .exceptionally(throwable -> {
                    QuickSkin.LOGGER.error("Error fetching Mojang skin", throwable);
                    if (this.minecraft != null) {
                        this.minecraft.execute(() -> {
                            showError(Component.literal("Failed to fetch skin: " + throwable.getMessage()));
                            resetSearchButton();
                        });
                    }
                    return null;
                });
    }

    /**
     * Handle the fetched Mojang skin data
     */
    private void handleMojangSkinFetched(MojangApiService.MojangSkinData skinData) {
        try {
            // Save the skin image to local storage
            Path skinPath = SkinImporter.saveSkinImage(skinData.image, skinData.username);

            if (skinPath != null) {
                QuickSkin.LOGGER.info("Successfully saved Mojang skin for: {}", skinData.username);

                // Reload the asset manager to pick up the new file
                LocalAssetManager.getInstance().reload();

                // Get the metadata for the saved file
                String hash = com.quickskin.mod.common.util.HashUtil.computeFileHash(skinPath);
                if (hash != null) {
                    AssetMetadata metadata = LocalAssetManager.getInstance().getMetadata(hash);

                    if (metadata != null) {
                        // Refresh the skin list
                        refreshSkinList();

                        // Auto-select the imported skin
                        if (skinListPanel != null) {
                            skinListPanel.setSelected(metadata);
                        }

                        // Clear the search field
                        usernameSearchField.setValue("");

                        // Play success sound
                        if (minecraft != null) {
                            minecraft.getSoundManager().play(
                                    net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                                            SoundEvents.UI_BUTTON_CLICK.value(), 1.0f
                                    )
                            );
                        }
                    } else {
                        showError(Component.literal("Failed to load skin metadata"));
                    }
                } else {
                    showError(Component.literal("Failed to compute file hash"));
                }
            } else {
                showError(Component.literal("Failed to save skin image"));
            }
        } catch (Exception e) {
            QuickSkin.LOGGER.error("Error handling Mojang skin", e);
            showError(Component.literal("Error: " + e.getMessage()));
        } finally {
            resetSearchButton();
        }
    }

    /**
     * Reset the search button state
     */
    private void resetSearchButton() {
        isSearching = false;
        if (searchButton != null) {
            searchButton.setMessage(Component.literal("Search"));
            searchButton.active = usernameSearchField != null && !usernameSearchField.getValue().trim().isEmpty();
        }
    }
}
```

This completes the implementation. The new cooldown feature is now integrated, respecting the server's configuration and providing clear feedback to the player on the client.