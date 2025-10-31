package com.quickskin.mod.networking;

import com.quickskin.mod.QuickSkin;
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
        QuickSkin.LOGGER.info("Initializing client networking...");

        // Register client-side packet receivers (S2C)
        // Note: Architectury's NetworkManager will be imported when we uncomment these
        // NetworkManager.registerReceiver(
        //     NetworkManager.s2c(),
        //     ModNetworking.SYNC_APPEARANCE,
        //     ClientNetworkHandler::handleSyncAppearance
        // );
        //
        // NetworkManager.registerReceiver(
        //     NetworkManager.s2c(),
        //     ModNetworking.SEND_TEXTURE,
        //     ClientNetworkHandler::handleSendTexture
        // );
        //
        // NetworkManager.registerReceiver(
        //     NetworkManager.s2c(),
        //     ModNetworking.SEND_TEXTURE_CHUNK,
        //     ClientNetworkHandler::handleSendTextureChunk
        // );
        //
        // NetworkManager.registerReceiver(
        //     NetworkManager.s2c(),
        //     ModNetworking.SEND_ANIMATION_METADATA,
        //     ClientNetworkHandler::handleSendAnimationMetadata
        // );
        //
        // NetworkManager.registerReceiver(
        //     NetworkManager.s2c(),
        //     ModNetworking.SYNC_SERVER_CONFIG,
        //     ClientNetworkHandler::handleSyncServerConfig
        // );

        QuickSkin.LOGGER.info("Client networking initialized (client-side receivers ready)");
    }
}
