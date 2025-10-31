package com.quickskin.mod.client.services;

import com.quickskin.mod.QuickSkin;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/**
 * Service for managing player capes
 * Handles loading capes from Mojang API, local storage, and known capes
 */
@Environment(EnvType.CLIENT)
public class CapeService implements ICapeService {
    private static CapeService instance;

    private CapeService() {}

    public static CapeService getInstance() {
        if (instance == null) {
            instance = new CapeService();
        }
        return instance;
    }

    public static void init() {
        getInstance();
        QuickSkin.LOGGER.info("CapeService initialized");
    }

    @Override
    @Nullable
    public ResourceLocation getCapeLocation(UUID playerId, String capeId) {
        if (capeId == null || capeId.isEmpty()) {
            return null;
        }

        // Check if it's a local cape
        if (capeId.startsWith("local_cape:")) {
            String hash = capeId.substring("local_cape:".length());
            return loadLocalCape(hash);
        }

        // Check if it's a known cape
        if (capeId.startsWith("known:")) {
            String knownId = capeId.substring("known:".length());
            return loadKnownCape(knownId);
        }

        // Otherwise, it's a Mojang username
        return loadMojangCape(capeId);
    }

    @Override
    @Nullable
    public ResourceLocation loadMojangCape(String username) {
        // TODO Phase 5: Implement Mojang API cape loading
        QuickSkin.LOGGER.debug("Loading Mojang cape for: {}", username);
        return null;
    }

    @Override
    @Nullable
    public ResourceLocation loadLocalCape(String hash) {
        // TODO Phase 5: Implement local cape loading
        QuickSkin.LOGGER.debug("Loading local cape: {}", hash);
        return null;
    }

    @Override
    @Nullable
    public ResourceLocation loadKnownCape(String capeId) {
        // TODO Phase 5: Implement known cape loading (e.g., Minecon capes)
        QuickSkin.LOGGER.debug("Loading known cape: {}", capeId);
        return null;
    }

    @Override
    public boolean isAnimated(String capeId) {
        // TODO Phase 7: Implement animation detection
        // This will check if the cape has animation metadata
        return false;
    }

    @Override
    public boolean hasLocalCape(String hash) {
        // TODO Phase 5: Implement check using AssetService
        return false;
    }
}
