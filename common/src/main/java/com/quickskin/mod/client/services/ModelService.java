package com.quickskin.mod.client.services;

import com.quickskin.mod.QuickSkin;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import org.jetbrains.annotations.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing player model types (classic/slim)
 */
@Environment(EnvType.CLIENT)
public class ModelService implements IModelService {
    private static ModelService instance;

    private final Map<UUID, String> modelOverrides = new ConcurrentHashMap<>();

    private ModelService() {}

    public static ModelService getInstance() {
        if (instance == null) {
            instance = new ModelService();
        }
        return instance;
    }

    public static void init() {
        getInstance();
        QuickSkin.LOGGER.info("ModelService initialized");
    }

    @Override
    public String getModelType(UUID playerId, String skinId, String requestedModel) {
        // Check for manual override first
        if (modelOverrides.containsKey(playerId)) {
            String override = modelOverrides.get(playerId);
            if (!"auto".equalsIgnoreCase(override)) {
                return override;
            }
        }

        // If auto or no override, use requested model
        if ("auto".equalsIgnoreCase(requestedModel)) {
            // TODO Phase 5: Auto-detect from skin texture
            // For now, default to classic
            return "classic";
        }

        return requestedModel != null ? requestedModel : "classic";
    }

    @Override
    public String detectModelType(byte[] skinData) {
        // TODO Phase 5: Implement actual detection using SkinModelDetector
        // This will be migrated from the old code
        return "classic";
    }

    @Override
    public void setModelOverride(UUID playerId, String model) {
        if (playerId == null || model == null) {
            return;
        }
        modelOverrides.put(playerId, model);
        QuickSkin.LOGGER.debug("Model override set for player {}: {}", playerId, model);
    }

    @Override
    @Nullable
    public String getModelOverride(UUID playerId) {
        return modelOverrides.get(playerId);
    }

    @Override
    public void clearModelOverride(UUID playerId) {
        modelOverrides.remove(playerId);
    }

    @Override
    public boolean hasModelOverride(UUID playerId) {
        return modelOverrides.containsKey(playerId);
    }

    /**
     * Clears all model overrides (e.g., when disconnecting)
     */
    public void clearAll() {
        modelOverrides.clear();
    }
}
