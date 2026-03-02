package com.quickskin.mod.networking;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.config.ServerConfig;
import com.quickskin.mod.networking.payloads.*;
import com.quickskin.mod.server.data.ServerCooldownManager;
import com.quickskin.mod.server.data.ServerPlayerAppearanceRepository;
import com.quickskin.mod.server.storage.ServerAnimationCache;
import com.quickskin.mod.server.storage.ServerTextureCache;
import dev.architectury.networking.NetworkManager;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Server-side network packet handlers (Architectury 13.x for MC 1.21.1)
 * Handles all C2S (Client to Server) packets using CustomPacketPayload
 */
public class ServerNetworkHandler {

    /**
     * Checks if a player's client has QuickSkin installed and can receive our packets.
     * Used to skip sending S2C packets to vanilla clients that don't have the mod.
     */
    private static boolean canReceiveQuickSkin(ServerPlayer player) {
        return NetworkManager.canPlayerReceive(player, SyncAppearancePayload.TYPE);
    }

    /**
     * Handles skin/cape upload from client
     */
    public static void handleUploadTexture(UploadTexturePayload payload, NetworkManager.PacketContext context) {
        context.queue(() -> {
            ServerPlayer player = (ServerPlayer) context.getPlayer();

            if (player == null || !player.getUUID().equals(payload.playerId())) {
                return;
            }

            // Generate hash for this texture
            String hash = payload.playerId().toString() + "_" + payload.textureType();

            // Phase 5: Store texture to server-side storage
            ServerTextureCache.getInstance().storeTexture(hash, payload.imageData());

            // Phase 3: Sync to other players
            broadcastTextureToOtherPlayers(player, payload.textureType(), hash, payload.imageData());
        });
    }

    /**
     * Handles appearance update from client
     */
    public static void handleUpdateAppearance(UpdateAppearancePayload payload, NetworkManager.PacketContext context) {
        context.queue(() -> {
            ServerPlayer player = (ServerPlayer) context.getPlayer();

            if (player == null || !player.getUUID().equals(payload.playerId())) {
                return;
            }

            // Check cooldown settings first to avoid unnecessary work
            int cooldownSeconds = com.quickskin.mod.config.ServerConfig.getInstance().skinChangeCooldownSeconds;

            PlayerAppearance currentAppearance = ServerPlayerAppearanceRepository.getInstance().getAppearance(payload.playerId());
            boolean isSkinChanging = payload.skinId() != null && !payload.skinId().isEmpty() &&
                (currentAppearance == null || !payload.skinId().equals(currentAppearance.getSkinId()));

            // Only check and enforce cooldown if feature is enabled
            if (isSkinChanging && cooldownSeconds > 0) {
                if (ServerCooldownManager.getInstance().isPlayerOnCooldown(payload.playerId())) {
                    return;
                }
            }

            // Update server-side repository
            ServerPlayerAppearanceRepository.getInstance().updateAppearance(
                payload.playerId(), payload.skinId(), payload.capeId(), payload.model()
            );

            // Only record skin change and send updates if cooldown is enabled
            if (isSkinChanging && cooldownSeconds > 0) {
                ServerCooldownManager.getInstance().recordSkinChange(payload.playerId());
                long cooldownEndTime = ServerCooldownManager.getInstance().getCooldownEndTime(payload.playerId());

                CooldownUpdatePayload cooldownPayload = new CooldownUpdatePayload(cooldownEndTime);
                NetworkManager.sendToPlayer(player, cooldownPayload);
            }

            // Phase 3: Broadcast to other players
            broadcastAppearanceToOtherPlayers(player, payload.skinId(), payload.capeId(), payload.model());
        });
    }

    /**
     * Handles texture request from client
     */
    public static void handleRequestTexture(RequestTexturePayload payload, NetworkManager.PacketContext context) {
        context.queue(() -> {
            ServerPlayer player = (ServerPlayer) context.getPlayer();

            if (player == null) {
                return;
            }

            // Phase 5: Load texture from server storage and send to client
            byte[] textureData = ServerTextureCache.getInstance().getTexture(payload.hash());
            if (textureData != null) {
                sendTextureToClient(player, payload.textureType(), payload.hash(), textureData);
            }
        });
    }

    /**
     * Handles chunked texture upload from client
     */
    public static void handleTextureChunk(TextureChunkPayload payload, NetworkManager.PacketContext context) {
        context.queue(() -> {
            ServerPlayer player = (ServerPlayer) context.getPlayer();

            if (player == null) {
                return;
            }

            // Validate chunk size (32KB safety limit to prevent oversized packets)
            if (payload.chunkData().length > 32 * 1024) {
                return;
            }

            // Validate chunk index
            if (payload.chunkIndex() < 0 || payload.chunkIndex() >= payload.totalChunks()) {
                return;
            }

            // Validate total chunks (prevent DoS with excessive chunk counts)
            if (payload.totalChunks() < 1 || payload.totalChunks() > 1000) {
                return;
            }

            // Add chunk to assembler
            byte[] completeTexture = com.quickskin.mod.server.storage.TextureChunkAssembler.getInstance()
                .addChunk(payload.hash(), payload.chunkIndex(), payload.totalChunks(), payload.chunkData());

            // If all chunks received, store and broadcast
            if (completeTexture != null) {
                // Store texture in server cache
                ServerTextureCache.getInstance().storeTexture(payload.hash(), completeTexture);

                // Broadcast texture to other players
                broadcastTextureToOtherPlayers(player, payload.textureType(), payload.hash(), completeTexture);
            }
        });
    }

    /**
     * Handles animation metadata upload from client
     */
    public static void handleUploadAnimationMetadata(UploadAnimationMetadataPayload payload, NetworkManager.PacketContext context) {
        context.queue(() -> {
            ServerPlayer player = (ServerPlayer) context.getPlayer();

            if (player == null) {
                return;
            }

            // Phase 7: Store animation metadata
            ServerAnimationCache.getInstance().storeMetadata(payload.hash(), payload.metadataJson());

            // Broadcast animation metadata to other players
            broadcastAnimationMetadataToOtherPlayers(player, payload.hash(), payload.metadataJson());
        });
    }

    /**
     * Handles server config update from admin client
     */
    public static void handleUpdateServerConfig(UpdateServerConfigPayload payload, NetworkManager.PacketContext context) {
        context.queue(() -> {
            ServerPlayer player = (ServerPlayer) context.getPlayer();

            if (player == null) {
                return;
            }

            // Check if player has admin permissions (operator level 2+)
            if (!player.hasPermissions(2)) {
                return;
            }

            // Update server config based on key
            com.quickskin.mod.config.ServerConfig serverConfig =
                com.quickskin.mod.config.ServerConfig.getInstance();

            switch (payload.key()) {
                case "disableSkinTransparency":
                    serverConfig.disableSkinTransparency = payload.value();
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
     */
    private static void broadcastTextureToOtherPlayers(ServerPlayer player, String textureType, String hash, byte[] imageData) {
        SendTexturePayload payload = new SendTexturePayload(textureType, hash, imageData);

        // Send to all players except the sender (only if they have QuickSkin)
        for (ServerPlayer otherPlayer : player.server.getPlayerList().getPlayers()) {
            if (!otherPlayer.getUUID().equals(player.getUUID()) && canReceiveQuickSkin(otherPlayer)) {
                NetworkManager.sendToPlayer(otherPlayer, payload);
            }
        }

    }

    /**
     * Broadcasts a player's appearance to all other players on the server
     */
    private static void broadcastAppearanceToOtherPlayers(ServerPlayer player, String skinId, String capeId, String model) {
        SyncAppearancePayload payload = new SyncAppearancePayload(player.getUUID(), skinId, capeId, model);

        // Send to all players except the sender (only if they have QuickSkin)
        for (ServerPlayer otherPlayer : player.server.getPlayerList().getPlayers()) {
            if (!otherPlayer.getUUID().equals(player.getUUID()) && canReceiveQuickSkin(otherPlayer)) {
                NetworkManager.sendToPlayer(otherPlayer, payload);
            }
        }

    }

    /**
     * Sends a player's appearance to a specific client
     * Used when players join or respawn
     */
    public static void sendAppearanceToPlayer(ServerPlayer recipient, UUID targetPlayerId) {
        if (!canReceiveQuickSkin(recipient)) {
            return;
        }

        PlayerAppearance appearance = ServerPlayerAppearanceRepository.getInstance().getAppearance(targetPlayerId);

        if (appearance != null) {
            // Send the appearance metadata
            SyncAppearancePayload payload = new SyncAppearancePayload(
                    targetPlayerId,
                    appearance.getSkinId(),
                    appearance.getCapeId(),
                    appearance.getModel()
            );

            NetworkManager.sendToPlayer(recipient, payload);

            // Also send the texture data if it's a custom skin/cape
            String skinId = appearance.getSkinId();
            String capeId = appearance.getCapeId();

            // Send skin texture if it's a local skin
            if (skinId != null && skinId.startsWith("local_skin:")) {
                String hash = skinId.substring("local_skin:".length());
                byte[] skinData = ServerTextureCache.getInstance().getTexture(hash);
                if (skinData != null) {
                    SendTexturePayload skinPayload = new SendTexturePayload("skin", hash, skinData);
                    NetworkManager.sendToPlayer(recipient, skinPayload);
                }
            }

            // Send cape texture if it's a local cape
            if (capeId != null && capeId.startsWith("local_cape:")) {
                String hash = capeId.substring("local_cape:".length());
                byte[] capeData = ServerTextureCache.getInstance().getTexture(hash);
                if (capeData != null) {
                    SendTexturePayload capePayload = new SendTexturePayload("cape", hash, capeData);
                    NetworkManager.sendToPlayer(recipient, capePayload);

                    // Also send animation metadata if available
                    String metadata = ServerAnimationCache.getInstance().getMetadata(hash);
                    if (metadata != null) {
                        SendAnimationMetadataPayload animPayload = new SendAnimationMetadataPayload(hash, metadata);
                        NetworkManager.sendToPlayer(recipient, animPayload);
                    }
                }
            }

        }
    }

    /**
     * Sends all player appearances to a newly joined player
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
     */
    private static void sendTextureToClient(ServerPlayer player, String textureType, String hash, byte[] textureData) {
        if (!canReceiveQuickSkin(player)) {
            return;
        }
        SendTexturePayload payload = new SendTexturePayload(textureType, hash, textureData);
        NetworkManager.sendToPlayer(player, payload);
    }

    /**
     * Broadcasts animation metadata to all other players on the server
     */
    private static void broadcastAnimationMetadataToOtherPlayers(ServerPlayer player, String hash, String metadataJson) {
        SendAnimationMetadataPayload payload = new SendAnimationMetadataPayload(hash, metadataJson);

        // Send to all players except the sender (only if they have QuickSkin)
        for (ServerPlayer otherPlayer : player.server.getPlayerList().getPlayers()) {
            if (!otherPlayer.getUUID().equals(player.getUUID()) && canReceiveQuickSkin(otherPlayer)) {
                NetworkManager.sendToPlayer(otherPlayer, payload);
            }
        }

    }

    /**
     * Sends server config to a specific player (called on player join)
     */
    public static void sendServerConfigToPlayer(ServerPlayer player) {
        if (!canReceiveQuickSkin(player)) {
            return;
        }
        com.quickskin.mod.config.ServerConfig serverConfig = com.quickskin.mod.config.ServerConfig.getInstance();
        String configJson = serverConfig.toJson();

        SyncServerConfigPayload payload = new SyncServerConfigPayload(configJson);
        NetworkManager.sendToPlayer(player, payload);

    }

    /**
     * Broadcasts server config to ALL players on the server
     * Called when an admin changes a server setting
     */
    private static void broadcastServerConfigToAllPlayers(net.minecraft.server.MinecraftServer server) {
        com.quickskin.mod.config.ServerConfig serverConfig = com.quickskin.mod.config.ServerConfig.getInstance();
        String configJson = serverConfig.toJson();

        SyncServerConfigPayload payload = new SyncServerConfigPayload(configJson);

        // Send to all players that have QuickSkin (including the admin who made the change)
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (canReceiveQuickSkin(player)) {
                NetworkManager.sendToPlayer(player, payload);
            }
        }

    }
}
