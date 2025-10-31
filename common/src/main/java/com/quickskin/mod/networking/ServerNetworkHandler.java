package com.quickskin.mod.networking;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.networking.packets.PacketHelper;
import dev.architectury.networking.NetworkManager;
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

            // TODO Phase 5: Store texture to server-side storage
            // ServerTextureCache.storeTexture(playerId, textureType, imageData);

            // TODO Phase 3: Sync to other players
            // broadcastTextureToOtherPlayers(player, textureType, imageData);
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

            QuickSkin.LOGGER.info("Player {} updated appearance: skin={}, cape={}, model={}",
                    player.getName().getString(), skinId, capeId, model);

            // TODO Phase 3: Broadcast to other players
            // broadcastAppearanceToOtherPlayers(player, skinId, capeId, model);
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

            // TODO Phase 5: Load texture from server storage and send to client
            // byte[] textureData = ServerTextureCache.getTexture(hash);
            // if (textureData != null) {
            //     sendTextureToClient(player, textureType, hash, textureData);
            // }
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

            // TODO Phase 7: Store animation metadata
            // ServerAnimationCache.storeMetadata(hash, metadataJson);
        });
    }
}
