package com.quickskin.mod.networking;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.services.AnimatedTextureManager;
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
            com.quickskin.mod.client.services.CooldownService.getInstance().setCooldownEndTime(cooldownEndTime);
        });
    }
}
