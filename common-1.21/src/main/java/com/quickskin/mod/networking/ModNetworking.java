package com.quickskin.mod.networking;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.networking.payloads.*;
import dev.architectury.networking.NetworkManager;
import dev.architectury.platform.Platform;

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

        // Register S2C (Server to Client) payload types ONLY on servers (not clients)
        // On clients, ClientNetworking.registerReceiver() handles both type and handler registration
        // This prevents duplicate registration errors on clients
        // Use Architectury's cross-platform environment detection
        try {
            // Check if we're on a dedicated server (no client classes loaded)
            Class.forName("net.minecraft.client.Minecraft");
            // If we reach here, client classes are present - skip S2C type registration
            QuickSkin.LOGGER.debug("Client environment detected, skipping S2C payload type registration");
        } catch (ClassNotFoundException e) {
            // Client classes not found - we're on a dedicated server
            NetworkManager.registerS2CPayloadType(SyncAppearancePayload.TYPE, SyncAppearancePayload.CODEC);
            NetworkManager.registerS2CPayloadType(SendTexturePayload.TYPE, SendTexturePayload.CODEC);
            NetworkManager.registerS2CPayloadType(SendTextureChunkPayload.TYPE, SendTextureChunkPayload.CODEC);
            NetworkManager.registerS2CPayloadType(SendAnimationMetadataPayload.TYPE, SendAnimationMetadataPayload.CODEC);
            NetworkManager.registerS2CPayloadType(SyncServerConfigPayload.TYPE, SyncServerConfigPayload.CODEC);
            NetworkManager.registerS2CPayloadType(CooldownUpdatePayload.TYPE, CooldownUpdatePayload.CODEC);

            QuickSkin.LOGGER.info("Registered S2C payload types for dedicated server");
        }

        QuickSkin.LOGGER.info("Networking initialized (C2S receivers registered)");
    }
}