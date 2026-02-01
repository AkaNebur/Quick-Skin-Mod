package com.quickskin.mod.client.services;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.common.data.TextureQuality;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Service for managing player skins
 * Handles loading skins from Mojang API and local storage
 */
@Environment(EnvType.CLIENT)
public class SkinService implements ISkinService {
    private static SkinService instance;

    private SkinService() {}

    public static SkinService getInstance() {
        if (instance == null) {
            instance = new SkinService();
        }
        return instance;
    }

    public static void init() {
        getInstance();
        QuickSkin.LOGGER.info("SkinService initialized");
    }

    @Override
    @Nullable
    public ResourceLocation getSkinLocation(UUID playerId, String skinId) {
        if (skinId == null || skinId.isEmpty()) {
            return null;
        }

        // Check if it's a local skin
        if (skinId.startsWith("local_skin:")) {
            String hash = skinId.substring("local_skin:".length());
            return loadLocalSkin(hash);
        }

        // Otherwise, it's a Mojang username
        return loadMojangSkin(skinId);
    }

    @Override
    @Nullable
    public ResourceLocation loadMojangSkin(String username) {
        // Phase 5: Implement Mojang API loading
        QuickSkin.LOGGER.debug("Loading Mojang skin for: {}", username);

        try {
            // Generate a UUID from the username for offline mode
            UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));

            // Return the default skin based on UUID
            // In a full implementation, this would fetch from Mojang's API
            // using the PlayerInfo or SkinManager to get the actual player skin
            ResourceLocation defaultSkin = DefaultPlayerSkin.get(uuid).texture();

            QuickSkin.LOGGER.debug("Using default skin for: {} (UUID: {})", username, uuid);
            return defaultSkin;

        } catch (Exception e) {
            QuickSkin.LOGGER.error("Failed to load Mojang skin for: {}", username, e);
            return null;
        }
    }

    @Override
    @Nullable
    public ResourceLocation loadLocalSkin(String hash) {
        QuickSkin.LOGGER.debug("Loading local skin: {}", hash);

        // Check network cache first (for textures received from server)
        if (com.quickskin.mod.client.storage.NetworkTextureCache.getInstance().hasTexture(hash)) {
            ResourceLocation networkLocation = com.quickskin.mod.client.storage.NetworkTextureCache.getInstance()
                    .getTextureLocation(hash);
            if (networkLocation != null) {
                QuickSkin.LOGGER.debug("Loaded skin from network cache: {}", hash);
                return networkLocation;
            }
        }

        // Try local assets (for user's own skins)
        ResourceLocation localLocation = LocalAssetManager.getInstance().getTextureLocation(hash, TextureQuality.FULL);

        // If not found locally and we're connected to a server, request it
        if (localLocation == null) {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player != null && mc.getConnection() != null) {
                QuickSkin.LOGGER.info("Skin {} not found locally, requesting from server", hash);
                com.quickskin.mod.networking.NetworkSyncService.getInstance()
                    .requestTexture(mc.player.getUUID(), "skin", hash);
            }
        }

        return localLocation;
    }

}
