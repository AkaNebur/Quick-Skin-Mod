package com.quickskin.mod.networking;

import com.quickskin.mod.QuickSkin;
import dev.architectury.networking.NetworkManager;
import net.minecraft.resources.ResourceLocation;

/**
 * Central networking registry for QuickSkin
 * Defines all packet IDs used by the mod
 */
public class ModNetworking {

    // Client to Server packets (C2S)
    public static final ResourceLocation UPLOAD_SKIN =
        new ResourceLocation(QuickSkin.MOD_ID, "upload_skin");

    public static final ResourceLocation UPLOAD_CAPE =
        new ResourceLocation(QuickSkin.MOD_ID, "upload_cape");

    public static final ResourceLocation UPDATE_APPEARANCE =
        new ResourceLocation(QuickSkin.MOD_ID, "update_appearance");

    public static final ResourceLocation REQUEST_TEXTURE =
        new ResourceLocation(QuickSkin.MOD_ID, "request_texture");

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

    /**
     * Initializes networking (registers server-side receivers)
     * Called from QuickSkin.init() on both client and server
     */
    public static void init() {
        QuickSkin.LOGGER.info("Initializing networking...");

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

        QuickSkin.LOGGER.info("Networking initialized (server-side receivers ready)");
    }
}
