package com.quickskin.mod.networking;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.networking.packets.PacketHelper;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Central networking registry for QuickSkin
 * Defines all packet IDs used by the mod
 */
public class ModNetworking implements NetworkTransport {

    // Client to Server packets (C2S)
    public static final ResourceLocation UPLOAD_SKIN =
        new ResourceLocation(QuickSkin.MOD_ID, "upload_skin");

    public static final ResourceLocation UPLOAD_CAPE =
        new ResourceLocation(QuickSkin.MOD_ID, "upload_cape");

    public static final ResourceLocation UPDATE_APPEARANCE =
        new ResourceLocation(QuickSkin.MOD_ID, "update_appearance");

    public static final ResourceLocation REQUEST_TEXTURE =
        new ResourceLocation(QuickSkin.MOD_ID, "request_texture");

    public static final ResourceLocation REQUEST_APPEARANCE_SNAPSHOT =
        new ResourceLocation(QuickSkin.MOD_ID, "request_appearance_snapshot");

    public static final ResourceLocation TEXTURE_CHUNK =
        new ResourceLocation(QuickSkin.MOD_ID, "texture_chunk");

    public static final ResourceLocation UPLOAD_ANIMATION_METADATA =
        new ResourceLocation(QuickSkin.MOD_ID, "upload_animation_metadata");

    public static final ResourceLocation UPDATE_SERVER_CONFIG =
        new ResourceLocation(QuickSkin.MOD_ID, "update_server_config");

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

    public static final ResourceLocation APPEARANCE_SNAPSHOT_COMPLETE =
        new ResourceLocation(QuickSkin.MOD_ID, "appearance_snapshot_complete");

    /**
     * Initializes networking (registers server-side receivers)
     * Called from QuickSkin.init() on both client and server
     */
    @Override
    public void init() {
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
            REQUEST_APPEARANCE_SNAPSHOT,
            ServerNetworkHandler::handleRequestAppearanceSnapshot
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

        NetworkManager.registerReceiver(
            NetworkManager.c2s(),
            UPDATE_SERVER_CONFIG,
            ServerNetworkHandler::handleUpdateServerConfig
        );

    }

    @Override
    public void initClient() {
        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            SYNC_APPEARANCE,
            ClientNetworkHandler::handleSyncAppearance
        );
        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            SEND_TEXTURE,
            ClientNetworkHandler::handleSendTexture
        );
        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            SEND_TEXTURE_CHUNK,
            ClientNetworkHandler::handleSendTextureChunk
        );
        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            SEND_ANIMATION_METADATA,
            ClientNetworkHandler::handleSendAnimationMetadata
        );
        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            SYNC_SERVER_CONFIG,
            ClientNetworkHandler::handleSyncServerConfig
        );
        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            COOLDOWN_UPDATE,
            ClientNetworkHandler::handleCooldownUpdate
        );
        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            APPEARANCE_SNAPSHOT_COMPLETE,
            ClientNetworkHandler::handleAppearanceSnapshotComplete
        );
    }

    @Override
    public boolean canServerReceiveAppearance() {
        return NetworkManager.canServerReceive(UPDATE_APPEARANCE);
    }

    @Override
    public boolean canServerReceiveAppearanceSnapshot() {
        return NetworkManager.canServerReceive(REQUEST_APPEARANCE_SNAPSHOT);
    }

    @Override
    public boolean canPlayerReceiveQuickSkin(ServerPlayer player) {
        return NetworkManager.canPlayerReceive(player, SYNC_APPEARANCE);
    }

    @Override
    public void sendAppearanceToServer(
            UUID playerId, String skinId, String capeId, String model) {
        NetworkManager.sendToServer(
                UPDATE_APPEARANCE,
                PacketHelper.createUpdateAppearancePacket(playerId, skinId, capeId, model));
    }

    @Override
    public void sendTextureChunkToServer(
            String hash, String textureType, int chunkIndex, int totalChunks, byte[] chunkData) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUtf(hash);
        buffer.writeUtf(textureType);
        buffer.writeInt(chunkIndex);
        buffer.writeInt(totalChunks);
        buffer.writeByteArray(chunkData);
        NetworkManager.sendToServer(TEXTURE_CHUNK, buffer);
    }

    @Override
    public void sendAnimationMetadataToServer(String hash, String metadataJson) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUtf(hash);
        buffer.writeUtf(metadataJson);
        NetworkManager.sendToServer(UPLOAD_ANIMATION_METADATA, buffer);
    }

    @Override
    public void requestTextureFromServer(UUID playerId, String textureType, String hash) {
        NetworkManager.sendToServer(
                REQUEST_TEXTURE,
                PacketHelper.createRequestTexturePacket(playerId, textureType, hash));
    }

    @Override
    public void requestAppearanceSnapshotFromServer(UUID playerId, long requestId) {
        NetworkManager.sendToServer(
                REQUEST_APPEARANCE_SNAPSHOT,
                PacketHelper.createRequestAppearanceSnapshotPacket(playerId, requestId));
    }

    @Override
    public void sendServerConfigUpdateToServer(String key, boolean value) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUtf(key);
        buffer.writeBoolean(value);
        NetworkManager.sendToServer(UPDATE_SERVER_CONFIG, buffer);
    }

    @Override
    public void sendCooldownToPlayer(ServerPlayer player, long cooldownEndTime) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeLong(cooldownEndTime);
        NetworkManager.sendToPlayer(player, COOLDOWN_UPDATE, buffer);
    }

    @Override
    public void sendTextureToPlayer(
            ServerPlayer player, String textureType, String hash, byte[] imageData) {
        NetworkManager.sendToPlayer(
                player, SEND_TEXTURE,
                PacketHelper.createSendTexturePacket(textureType, hash, imageData));
    }

    @Override
    public void sendTextureChunkToPlayer(
            ServerPlayer player, String hash, String textureType,
            int chunkIndex, int totalChunks, byte[] chunkData) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeUtf(hash);
        buffer.writeUtf(textureType);
        buffer.writeInt(chunkIndex);
        buffer.writeInt(totalChunks);
        buffer.writeByteArray(chunkData);
        NetworkManager.sendToPlayer(player, SEND_TEXTURE_CHUNK, buffer);
    }

    @Override
    public void sendAppearanceToPlayer(
            ServerPlayer player, UUID playerId, String skinId, String capeId, String model) {
        NetworkManager.sendToPlayer(
                player, SYNC_APPEARANCE,
                PacketHelper.createSyncAppearancePacket(playerId, skinId, capeId, model));
    }

    @Override
    public void sendAnimationMetadataToPlayer(
            ServerPlayer player, String hash, String metadataJson) {
        NetworkManager.sendToPlayer(
                player, SEND_ANIMATION_METADATA,
                PacketHelper.createSendAnimationMetadataPacket(hash, metadataJson));
    }

    @Override
    public void sendServerConfigToPlayer(ServerPlayer player, String configJson) {
        NetworkManager.sendToPlayer(
                player, SYNC_SERVER_CONFIG,
                PacketHelper.createSyncServerConfigPacket(configJson));
    }

    @Override
    public void sendAppearanceSnapshotCompleteToPlayer(
            ServerPlayer player, long requestId) {
        NetworkManager.sendToPlayer(
                player, APPEARANCE_SNAPSHOT_COMPLETE,
                PacketHelper.createAppearanceSnapshotCompletePacket(requestId));
    }
}
