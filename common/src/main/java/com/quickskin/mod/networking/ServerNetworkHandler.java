package com.quickskin.mod.networking;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.networking.packets.PacketHelper;
import com.quickskin.mod.server.data.ServerPlayerAppearanceRepository;
import com.quickskin.mod.server.storage.ServerAnimationCache;
import com.quickskin.mod.server.storage.ServerTextureCache;
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

            QuickSkin.LOGGER.info("Player {} updated appearance: skin={}, cape={}, model={}",
                    player.getName().getString(), skinId, capeId, model);

            // Update server-side repository
            ServerPlayerAppearanceRepository.getInstance().updateAppearance(playerId, skinId, capeId, model);

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
        FriendlyByteBuf packet = PacketHelper.createSendTexturePacket(textureType, hash, imageData);

        // Send to all players except the sender
        for (ServerPlayer otherPlayer : player.server.getPlayerList().getPlayers()) {
            if (!otherPlayer.getUUID().equals(player.getUUID())) {
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
        FriendlyByteBuf packet = PacketHelper.createSyncAppearancePacket(
                player.getUUID(), skinId, capeId, model
        );

        // Send to all players except the sender
        for (ServerPlayer otherPlayer : player.server.getPlayerList().getPlayers()) {
            if (!otherPlayer.getUUID().equals(player.getUUID())) {
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
            FriendlyByteBuf packet = PacketHelper.createSyncAppearancePacket(
                    targetPlayerId,
                    appearance.getSkinId(),
                    appearance.getCapeId(),
                    appearance.getModel()
            );

            NetworkManager.sendToPlayer(recipient, ModNetworking.SYNC_APPEARANCE, packet);

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
        FriendlyByteBuf packet = PacketHelper.createSendAnimationMetadataPacket(hash, metadataJson);

        // Send to all players except the sender
        for (ServerPlayer otherPlayer : player.server.getPlayerList().getPlayers()) {
            if (!otherPlayer.getUUID().equals(player.getUUID())) {
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
