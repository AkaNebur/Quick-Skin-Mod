package com.quickskin.mod.networking;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.networking.payloads.*;
import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import net.fabricmc.api.EnvType;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * Central networking registry for QuickSkin
 * Registers all packet payloads used by the mod (Architectury 13.x for MC 1.21.1)
 */
//? if <26.1 {
public class ModNetworking {
//?} else {
public class ModNetworking implements NetworkTransport {
//?}

    /**
     * Initializes networking (registers payload types and server-side receivers)
     * Called from QuickSkin.init() on both client and server
     */
    //? if >=26.1 {
    @Override
    //?}
    //? if <26.1 {
    public static void init() {
    //?} else {
    public void init() {
    //?}
        // Register C2S (Client to Server) payload receivers
        NetworkManager.registerReceiver(
                NetworkManager.c2s(),
                UploadTexturePayload.TYPE,
                UploadTexturePayload.CODEC,
                ServerNetworkHandler::handleUploadTexture
        );

        NetworkManager.registerReceiver(
                NetworkManager.c2s(),
                UpdateAppearancePayload.TYPE,
                UpdateAppearancePayload.CODEC,
                ServerNetworkHandler::handleUpdateAppearance
        );

        NetworkManager.registerReceiver(
                NetworkManager.c2s(),
                RequestTexturePayload.TYPE,
                RequestTexturePayload.CODEC,
                ServerNetworkHandler::handleRequestTexture
        );

        NetworkManager.registerReceiver(
                NetworkManager.c2s(),
                TextureChunkPayload.TYPE,
                TextureChunkPayload.CODEC,
                ServerNetworkHandler::handleTextureChunk
        );

        NetworkManager.registerReceiver(
                NetworkManager.c2s(),
                UploadAnimationMetadataPayload.TYPE,
                UploadAnimationMetadataPayload.CODEC,
                ServerNetworkHandler::handleUploadAnimationMetadata
        );

        NetworkManager.registerReceiver(
                NetworkManager.c2s(),
                UpdateServerConfigPayload.TYPE,
                UpdateServerConfigPayload.CODEC,
                ServerNetworkHandler::handleUpdateServerConfig
        );

        // Register S2C (Server to Client) payload types ONLY on servers (not clients)
        // On clients, ClientNetworking.registerReceiver() handles both type and handler registration
        // This prevents duplicate registration errors on clients
        // Use Architectury's cross-platform environment detection
        if (Platform.getEnv() == EnvType.SERVER) {
            // We're on a dedicated server
            NetworkManager.registerS2CPayloadType(SyncAppearancePayload.TYPE, SyncAppearancePayload.CODEC);
            NetworkManager.registerS2CPayloadType(SendTexturePayload.TYPE, SendTexturePayload.CODEC);
            NetworkManager.registerS2CPayloadType(SendTextureChunkPayload.TYPE, SendTextureChunkPayload.CODEC);
            NetworkManager.registerS2CPayloadType(SendAnimationMetadataPayload.TYPE, SendAnimationMetadataPayload.CODEC);
            NetworkManager.registerS2CPayloadType(SyncServerConfigPayload.TYPE, SyncServerConfigPayload.CODEC);
            NetworkManager.registerS2CPayloadType(CooldownUpdatePayload.TYPE, CooldownUpdatePayload.CODEC);
        }
    }

    //? if >=26.1 {
    @Override
    public void initClient() {
        ClientNetworking.init();
    }

    @Override
    public boolean canServerReceive(CustomPacketPayload.Type<?> type) {
        return NetworkManager.canServerReceive(type);
    }

    @Override
    public boolean canPlayerReceive(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return NetworkManager.canPlayerReceive(player, type);
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        NetworkManager.sendToServer(payload);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        NetworkManager.sendToPlayer(player, payload);
    }
    //?}
}
