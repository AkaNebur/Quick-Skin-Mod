package com.quickskin.mod.networking;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.networking.payloads.*;
import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;
import net.fabricmc.api.EnvType;

/**
 * Central networking registry for QuickSkin
 * Registers all packet payloads used by the mod (Architectury 13.x for MC 1.21.1)
 */
public class ModNetworking {

    /**
     * Initializes networking (registers payload types and server-side receivers)
     * Called from QuickSkin.init() on both client and server
     */
    public static void init() {
        QuickSkin.LOGGER.info("Initializing networking...");

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

        // Register S2C payloads ONLY on dedicated server (not on client)
        // On client, ClientNetworking.init() handles S2C registration with real handlers
        // On server, we need dummy registrations so the server can SEND S2C packets
        // Use Architectury's Platform API for cross-platform environment detection
        if (Platform.getEnv() == EnvType.SERVER) {
            // We're on a dedicated server - register S2C with dummy handlers
            NetworkManager.registerReceiver(NetworkManager.s2c(), SyncAppearancePayload.TYPE, SyncAppearancePayload.CODEC, (payload, context) -> {});
            NetworkManager.registerReceiver(NetworkManager.s2c(), SendTexturePayload.TYPE, SendTexturePayload.CODEC, (payload, context) -> {});
            NetworkManager.registerReceiver(NetworkManager.s2c(), SendTextureChunkPayload.TYPE, SendTextureChunkPayload.CODEC, (payload, context) -> {});
            NetworkManager.registerReceiver(NetworkManager.s2c(), SendAnimationMetadataPayload.TYPE, SendAnimationMetadataPayload.CODEC, (payload, context) -> {});
            NetworkManager.registerReceiver(NetworkManager.s2c(), SyncServerConfigPayload.TYPE, SyncServerConfigPayload.CODEC, (payload, context) -> {});
            NetworkManager.registerReceiver(NetworkManager.s2c(), CooldownUpdatePayload.TYPE, CooldownUpdatePayload.CODEC, (payload, context) -> {});
            QuickSkin.LOGGER.info("Registered S2C payloads for dedicated server");
        }

        QuickSkin.LOGGER.info("Networking initialized (server-side receivers ready)");
    }
}
