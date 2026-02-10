package com.quickskin.mod.networking;

import com.quickskin.mod.QuickSkin;
import dev.architectury.networking.NetworkManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Client-side networking initialization
 * Registers client-side packet receivers (S2C)
 */
@Environment(EnvType.CLIENT)
public class ClientNetworking {

    /**
     * Initializes client-side networking (registers S2C receivers)
     * Called from QuickSkinClient.init() only on client
     */
    public static void init() {
        // Register client-side packet receivers (S2C)
        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            ModNetworking.SYNC_APPEARANCE,
            ClientNetworkHandler::handleSyncAppearance
        );

        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            ModNetworking.SEND_TEXTURE,
            ClientNetworkHandler::handleSendTexture
        );

        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            ModNetworking.SEND_TEXTURE_CHUNK,
            ClientNetworkHandler::handleSendTextureChunk
        );

        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            ModNetworking.SEND_ANIMATION_METADATA,
            ClientNetworkHandler::handleSendAnimationMetadata
        );

        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            ModNetworking.SYNC_SERVER_CONFIG,
            ClientNetworkHandler::handleSyncServerConfig
        );

        NetworkManager.registerReceiver(
            NetworkManager.s2c(),
            ModNetworking.COOLDOWN_UPDATE,
            ClientNetworkHandler::handleCooldownUpdate
        );

    }
}
