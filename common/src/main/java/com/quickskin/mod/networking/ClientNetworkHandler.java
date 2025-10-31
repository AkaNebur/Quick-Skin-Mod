package com.quickskin.mod.networking;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.services.PlayerAppearanceService;
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

            // TODO Phase 5: Store texture to client-side storage
            // LocalAssetManager.storeTexture(hash, imageData, textureType);

            // TODO Phase 5: Update player appearance if needed
            // Minecraft mc = Minecraft.getInstance();
            // if (mc.player != null) {
            //     PlayerAppearanceService.getInstance().refreshPlayerRenderer(mc.player.getUUID());
            // }
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

            // TODO Phase 7: Store animation metadata
            // AnimationService.storeMetadata(hash, metadataJson);
        });
    }

    /**
     * Handles server config sync
     * Packet format: boolean (allowSkins) + boolean (allowCapes) + boolean (allowTransparent)
     */
    public static void handleSyncServerConfig(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        boolean allowSkins = PacketHelper.readBoolean(buf);
        boolean allowCapes = PacketHelper.readBoolean(buf);
        boolean allowTransparent = PacketHelper.readBoolean(buf);

        context.queue(() -> {
            QuickSkin.LOGGER.info("Received server config: allowSkins={}, allowCapes={}, allowTransparent={}",
                    allowSkins, allowCapes, allowTransparent);

            // Fire event for other systems to react
            InternalEventBus.getInstance().post(
                new ServerConfigSyncEvent(allowSkins, allowCapes, allowTransparent)
            );

            // TODO Phase 9: Update client config override
            // ClientConfig.setServerOverride(allowSkins, allowCapes, allowTransparent);
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

            // TODO Phase 5: Implement chunked texture receiver
            // TextureChunkReceiver.receiveChunk(hash, chunkIndex, totalChunks, chunkData);
        });
    }
}
