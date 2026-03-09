package com.quickskin.mod.networking;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.networking.payloads.*;
import dev.architectury.networking.NetworkManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Client-side networking initialization
 * Registers client-side packet receivers (S2C) - Architectury 13.x for MC 1.21.1
 */
@Environment(EnvType.CLIENT)
public class ClientNetworking {

    /**
     * Initializes client-side networking (registers S2C receivers and C2S payload types)
     * Called from QuickSkinClient.init() only on client
     */
    public static void init() {
        // Register S2C (Server to Client) payload receivers
        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            SyncAppearancePayload.TYPE,
            SyncAppearancePayload.CODEC,
            ClientNetworkHandler::handleSyncAppearance
        );

        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            SendTexturePayload.TYPE,
            SendTexturePayload.CODEC,
            ClientNetworkHandler::handleSendTexture
        );

        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            SendTextureChunkPayload.TYPE,
            SendTextureChunkPayload.CODEC,
            ClientNetworkHandler::handleSendTextureChunk
        );

        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            SendAnimationMetadataPayload.TYPE,
            SendAnimationMetadataPayload.CODEC,
            ClientNetworkHandler::handleSendAnimationMetadata
        );

        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            SyncServerConfigPayload.TYPE,
            SyncServerConfigPayload.CODEC,
            ClientNetworkHandler::handleSyncServerConfig
        );

        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            CooldownUpdatePayload.TYPE,
            CooldownUpdatePayload.CODEC,
            ClientNetworkHandler::handleCooldownUpdate
        );

    }
}
