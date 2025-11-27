package com.quickskin.mod.networking;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.networking.payloads.*;
import dev.architectury.networking.NetworkManager;

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

        QuickSkin.LOGGER.info("Networking initialized (server-side receivers ready)");
    }
}