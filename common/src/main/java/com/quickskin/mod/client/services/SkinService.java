package com.quickskin.mod.client.services;

import com.quickskin.mod.QuickSkin;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;
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
        // TODO Phase 5: Implement Mojang API loading
        // This will use Minecraft's built-in skin loading via SkinAPI
        QuickSkin.LOGGER.debug("Loading Mojang skin for: {}", username);
        return null;
    }

    @Override
    @Nullable
    public ResourceLocation loadLocalSkin(String hash) {
        // TODO Phase 5: Implement local skin loading
        // This will use AssetService to load from local storage
        QuickSkin.LOGGER.debug("Loading local skin: {}", hash);
        return null;
    }

    @Override
    public boolean hasLocalSkin(String hash) {
        // TODO Phase 5: Implement check using AssetService
        return false;
    }
}
