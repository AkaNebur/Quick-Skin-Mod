package com.quickskin.mod.networking;

//? if >=1.21 {
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//?}
import net.minecraft.server.level.ServerPlayer;

//? if <1.21 {
import java.util.UUID;
//?}
public interface NetworkTransport {
    //? if <1.21 {
    NetworkTransport INSTANCE = new ModNetworking();
    //?} else {
        //? if <26.1 {
    NetworkTransport INSTANCE = new PayloadNetworkTransport();
        //?} else {
    NetworkTransport INSTANCE = new ModNetworking();
        //?}
    //?}

    void init();

    void initClient();
    //? if <1.21 {
    boolean canServerReceiveAppearance();
    boolean canPlayerReceiveQuickSkin(ServerPlayer player);
    //?}

    //? if <1.21 {
    void sendAppearanceToServer(UUID playerId, String skinId, String capeId, String model);
    void sendTextureChunkToServer(
            String hash, String textureType, int chunkIndex, int totalChunks, byte[] chunkData);
    void sendAnimationMetadataToServer(String hash, String metadataJson);
    void requestTextureFromServer(UUID playerId, String textureType, String hash);
    void sendServerConfigUpdateToServer(String key, boolean value);
    //?} else {
    boolean canServerReceive(CustomPacketPayload.Type<?> type);
    //?}

    //? if <1.21 {
    void sendCooldownToPlayer(ServerPlayer player, long cooldownEndTime);
    void sendTextureToPlayer(ServerPlayer player, String textureType, String hash, byte[] imageData);
    void sendAppearanceToPlayer(
            ServerPlayer player, UUID playerId, String skinId, String capeId, String model);
    void sendAnimationMetadataToPlayer(ServerPlayer player, String hash, String metadataJson);
    void sendServerConfigToPlayer(ServerPlayer player, String configJson);
    //?} else {
    boolean canPlayerReceive(ServerPlayer player, CustomPacketPayload.Type<?> type);

    void sendToServer(CustomPacketPayload payload);

    void sendToPlayer(ServerPlayer player, CustomPacketPayload payload);
    //?}
}
