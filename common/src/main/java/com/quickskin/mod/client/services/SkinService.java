package com.quickskin.mod.client.services;

import com.google.common.hash.Hashing;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.common.data.TextureQuality;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
            ResourceLocation defaultSkin = DefaultPlayerSkin.getDefaultSkin(uuid);

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

        // Fall back to local assets (for user's own skins)
        return LocalAssetManager.getInstance().getTextureLocation(hash, TextureQuality.FULL);
    }

    @Override
    public boolean hasLocalSkin(String hash) {
        // Check if the metadata cache contains this hash
        return LocalAssetManager.getInstance().getMetadata(hash) != null;
    }
}
