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

            // Phase 5: Store texture to client-side storage (in-memory for now)
            // Note: In a full implementation, this would be saved to disk via LocalAssetManager
            // For now, the texture will be sent when needed and cached by Minecraft's texture manager

            // Phase 5: Update player appearance if needed
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                // The appearance will be updated automatically when the SYNC_APPEARANCE packet arrives
                QuickSkin.LOGGER.debug("Texture received, appearance will be refreshed on next sync");
            }
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

            // Phase 7: Store and register animation metadata
            try {
                // Parse the JSON metadata
                AnimationMetadata metadata = AnimationMetadata.fromJson(metadataJson);

                // Store in client-side cache
                ClientAnimationMetadataCache.getInstance().storeMetadata(hash, metadata);

                QuickSkin.LOGGER.debug("Cached animation metadata: {} frames, {} ms total duration",
                        metadata.frameCount(), metadata.getTotalDuration());

                // The texture location will be registered with AnimatedTextureManager
                // when the actual texture is loaded and associated with this hash

            } catch (Exception e) {
                QuickSkin.LOGGER.error("Failed to parse animation metadata for: {}", hash, e);
            }
        });
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

            // Phase 5: Chunked texture receiver for large textures (HD skins)
            // This would reassemble chunks into a complete texture
            // For now, we use single-packet texture transfer (implemented in handleSendTexture)
            QuickSkin.LOGGER.warn("Chunked texture transfer not yet implemented - use single packet for now");
        });
    }
}
