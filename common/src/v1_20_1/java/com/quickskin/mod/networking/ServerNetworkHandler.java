package com.quickskin.mod.networking;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.config.ServerConfig;
import com.quickskin.mod.networking.packets.PacketHelper;
import com.quickskin.mod.server.data.QuickSkinPlayerTracker;
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
     * Checks if a player's client has QuickSkin installed and can receive our packets.
     * Used to skip sending S2C packets to vanilla clients that don't have the mod.
     */
    private static boolean canReceiveQuickSkin(ServerPlayer player) {
        return NetworkManager.canPlayerReceive(player, ModNetworking.SYNC_APPEARANCE)
                || QuickSkinPlayerTracker.getInstance().isConfirmed(player.getUUID());
    }

    /**
     * Tracks that a player has QuickSkin installed (they sent us a C2S packet).
     * On first confirmation, triggers full sync for the player.
     */
    private static void trackModPresence(ServerPlayer player) {
        if (QuickSkinPlayerTracker.getInstance().markConfirmed(player.getUUID())) {
            // Send all existing appearances to this newly confirmed player
            sendAllAppearancesToPlayer(player);

            // Send server config
            sendServerConfigToPlayer(player);

            // Send cooldown status if applicable
            int cooldownSeconds = com.quickskin.mod.config.ServerConfig.getInstance().skinChangeCooldownSeconds;
            if (cooldownSeconds > 0 && ServerCooldownManager.getInstance().isPlayerOnCooldown(player.getUUID())) {
                long cooldownEndTime = ServerCooldownManager.getInstance().getCooldownEndTime(player.getUUID());
                FriendlyByteBuf cooldownBuf = new FriendlyByteBuf(Unpooled.buffer());
                cooldownBuf.writeLong(cooldownEndTime);
                NetworkManager.sendToPlayer(player, ModNetworking.COOLDOWN_UPDATE, cooldownBuf);
            }

            // Broadcast this player's appearance to all other players
            sendAppearanceToAllPlayers(player);
        }
    }

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
                return;
            }

            trackModPresence(player);

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
                return;
            }

            trackModPresence(player);

            // Check cooldown settings first to avoid unnecessary work
            int cooldownSeconds = com.quickskin.mod.config.ServerConfig.getInstance().skinChangeCooldownSeconds;

            PlayerAppearance currentAppearance = ServerPlayerAppearanceRepository.getInstance().getAppearance(playerId);
            boolean isSkinChanging = skinId != null && !skinId.isEmpty() && (currentAppearance == null || !skinId.equals(currentAppearance.getSkinId()));

            // Only check and enforce cooldown if feature is enabled
            if (isSkinChanging && cooldownSeconds > 0) {
                if (ServerCooldownManager.getInstance().isPlayerOnCooldown(playerId)) {
                    return;
                }
            }

            // Update server-side repository
            ServerPlayerAppearanceRepository.getInstance().updateAppearance(playerId, skinId, capeId, model);

            // Only record skin change and send updates if cooldown is enabled
            if (isSkinChanging && cooldownSeconds > 0) {
                ServerCooldownManager.getInstance().recordSkinChange(playerId);
                long cooldownEndTime = ServerCooldownManager.getInstance().getCooldownEndTime(playerId);
                FriendlyByteBuf cooldownBuf = new FriendlyByteBuf(Unpooled.buffer());
                cooldownBuf.writeLong(cooldownEndTime);
                NetworkManager.sendToPlayer(player, ModNetworking.COOLDOWN_UPDATE, cooldownBuf);
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

            trackModPresence(player);

            // Phase 5: Load texture from server storage and send to client
            byte[] textureData = ServerTextureCache.getInstance().getTexture(hash);
            if (textureData != null) {
                sendTextureToClient(player, textureType, hash, textureData);
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

            trackModPresence(player);

            // Validate chunk size (32KB safety limit to prevent oversized packets)
            if (chunkData.length > 32 * 1024) {
                return;
            }

            // Validate chunk index
            if (chunkIndex < 0 || chunkIndex >= totalChunks) {
                return;
            }

            // Validate total chunks (prevent DoS with excessive chunk counts)
            if (totalChunks < 1 || totalChunks > 1000) {
                return;
            }

            // Add chunk to assembler
            byte[] completeTexture = com.quickskin.mod.server.storage.TextureChunkAssembler.getInstance()
                .addChunk(hash, chunkIndex, totalChunks, chunkData);

            // If all chunks received, store and broadcast
            if (completeTexture != null) {
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

            trackModPresence(player);

            // Phase 7: Store animation metadata
            ServerAnimationCache.getInstance().storeMetadata(hash, metadataJson);

            // Broadcast animation metadata to other players
            broadcastAnimationMetadataToOtherPlayers(player, hash, metadataJson);
        });
    }

    /**
     * Handles server config update from admin client
     * Packet format: String (key) + boolean (value)
     */
    public static void handleUpdateServerConfig(FriendlyByteBuf buf, NetworkManager.PacketContext context) {
        String key = PacketHelper.readString(buf);
        boolean value = buf.readBoolean();

        context.queue(() -> {
            ServerPlayer player = (ServerPlayer) context.getPlayer();

            if (player == null) {
                return;
            }

            trackModPresence(player);

            // Check if player has admin permissions (operator level 2+)
            if (!player.hasPermissions(2)) {
                return;
            }

            // Update server config based on key
            com.quickskin.mod.config.ServerConfig serverConfig =
                com.quickskin.mod.config.ServerConfig.getInstance();

            switch (key) {
                case "disableSkinTransparency":
                    serverConfig.disableSkinTransparency = value;
                    break;
                default:
                    return;
            }

            // Save config to disk
            serverConfig.save();

            // Broadcast config change to ALL clients (including the admin who made the change)
            broadcastServerConfigToAllPlayers(player.server);
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
        // Send to all players except the sender (only if they have QuickSkin)
        for (ServerPlayer otherPlayer : player.server.getPlayerList().getPlayers()) {
            if (!otherPlayer.getUUID().equals(player.getUUID()) && canReceiveQuickSkin(otherPlayer)) {
                // Create a fresh packet for each player to avoid buffer reuse issues
                FriendlyByteBuf packet = PacketHelper.createSendTexturePacket(textureType, hash, imageData);
                NetworkManager.sendToPlayer(otherPlayer, ModNetworking.SEND_TEXTURE, packet);
            }
        }

    }

    /**
     * Broadcasts a player's appearance to all other players on the server
     * @param player The player whose appearance changed
     * @param skinId The skin ID
     * @param capeId The cape ID
     * @param model The model type
     */
    private static void broadcastAppearanceToOtherPlayers(ServerPlayer player, String skinId, String capeId, String model) {
        // Send to all players except the sender (only if they have QuickSkin)
        for (ServerPlayer otherPlayer : player.server.getPlayerList().getPlayers()) {
            if (!otherPlayer.getUUID().equals(player.getUUID()) && canReceiveQuickSkin(otherPlayer)) {
                // Create a fresh packet for each player to avoid buffer reuse issues
                FriendlyByteBuf packet = PacketHelper.createSyncAppearancePacket(
                        player.getUUID(), skinId, capeId, model
                );
                NetworkManager.sendToPlayer(otherPlayer, ModNetworking.SYNC_APPEARANCE, packet);
            }
        }

    }

    /**
     * Sends a player's appearance to a specific client
     * Used when players join or respawn
     * @param recipient The player to send the appearance to
     * @param targetPlayerId The player whose appearance to send
     */
    public static void sendAppearanceToPlayer(ServerPlayer recipient, UUID targetPlayerId) {
        if (!canReceiveQuickSkin(recipient)) {
            return;
        }

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
                }
            }

            // Send cape texture if it's a local cape
            if (capeId != null && capeId.startsWith("local_cape:")) {
                String hash = capeId.substring("local_cape:".length());
                byte[] capeData = ServerTextureCache.getInstance().getTexture(hash);
                if (capeData != null) {
                    FriendlyByteBuf capePacket = PacketHelper.createSendTexturePacket("cape", hash, capeData);
                    NetworkManager.sendToPlayer(recipient, ModNetworking.SEND_TEXTURE, capePacket);

                    // Also send animation metadata if available
                    String metadata = ServerAnimationCache.getInstance().getMetadata(hash);
                    if (metadata != null) {
                        FriendlyByteBuf animPacket = PacketHelper.createSendAnimationMetadataPacket(hash, metadata);
                        NetworkManager.sendToPlayer(recipient, ModNetworking.SEND_ANIMATION_METADATA, animPacket);
                    }
                }
            }

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
        if (!canReceiveQuickSkin(player)) {
            return;
        }
        FriendlyByteBuf packet = PacketHelper.createSendTexturePacket(textureType, hash, textureData);
        NetworkManager.sendToPlayer(player, ModNetworking.SEND_TEXTURE, packet);
    }

    /**
     * Broadcasts animation metadata to all other players on the server
     * @param player The player who uploaded the metadata
     * @param hash The texture hash
     * @param metadataJson The animation metadata JSON
     */
    private static void broadcastAnimationMetadataToOtherPlayers(ServerPlayer player, String hash, String metadataJson) {
        // Send to all players except the sender (only if they have QuickSkin)
        for (ServerPlayer otherPlayer : player.server.getPlayerList().getPlayers()) {
            if (!otherPlayer.getUUID().equals(player.getUUID()) && canReceiveQuickSkin(otherPlayer)) {
                // Create a fresh packet for each player to avoid buffer reuse issues
                FriendlyByteBuf packet = PacketHelper.createSendAnimationMetadataPacket(hash, metadataJson);
                NetworkManager.sendToPlayer(otherPlayer, ModNetworking.SEND_ANIMATION_METADATA, packet);
            }
        }

    }

    /**
     * Sends server config to a specific player (called on player join)
     * @param player The player to send config to
     */
    public static void sendServerConfigToPlayer(ServerPlayer player) {
        if (!canReceiveQuickSkin(player)) {
            return;
        }
        com.quickskin.mod.config.ServerConfig serverConfig = com.quickskin.mod.config.ServerConfig.getInstance();
        String configJson = serverConfig.toJson();

        FriendlyByteBuf packet = PacketHelper.createSyncServerConfigPacket(configJson);

        NetworkManager.sendToPlayer(player, ModNetworking.SYNC_SERVER_CONFIG, packet);
    }

    /**
     * Broadcasts server config to ALL players on the server
     * Called when an admin changes a server setting
     * @param server The server instance
     */
    private static void broadcastServerConfigToAllPlayers(net.minecraft.server.MinecraftServer server) {
        com.quickskin.mod.config.ServerConfig serverConfig = com.quickskin.mod.config.ServerConfig.getInstance();
        String configJson = serverConfig.toJson();

        // Send to all players that have QuickSkin (including the admin who made the change)
        // IMPORTANT: Create a fresh packet buffer for each player to avoid buffer exhaustion
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (canReceiveQuickSkin(player)) {
                FriendlyByteBuf packet = PacketHelper.createSyncServerConfigPacket(configJson);
                NetworkManager.sendToPlayer(player, ModNetworking.SYNC_SERVER_CONFIG, packet);
            }
        }

    }
}
