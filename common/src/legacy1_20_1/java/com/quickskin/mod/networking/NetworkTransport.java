package com.quickskin.mod.networking;

import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** Raw-buffer transport seam for Minecraft 1.20.1. */
public interface NetworkTransport {
    NetworkTransport INSTANCE = new ModNetworking();

    void init();
    void initClient();
    boolean canServerReceiveAppearance();
    boolean canPlayerReceiveQuickSkin(ServerPlayer player);

    void sendAppearanceToServer(UUID playerId, String skinId, String capeId, String model);
    void sendTextureChunkToServer(
            String hash, String textureType, int chunkIndex, int totalChunks, byte[] chunkData);
    void sendAnimationMetadataToServer(String hash, String metadataJson);
    void requestTextureFromServer(UUID playerId, String textureType, String hash);
    void sendServerConfigUpdateToServer(String key, boolean value);

    void sendCooldownToPlayer(ServerPlayer player, long cooldownEndTime);
    void sendTextureToPlayer(ServerPlayer player, String textureType, String hash, byte[] imageData);
    void sendAppearanceToPlayer(
            ServerPlayer player, UUID playerId, String skinId, String capeId, String model);
    void sendAnimationMetadataToPlayer(ServerPlayer player, String hash, String metadataJson);
    void sendServerConfigToPlayer(ServerPlayer player, String configJson);
}
