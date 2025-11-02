package com.quickskin.mod.client.services;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.common.util.SkinModelDetector;
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
        QuickSkin.LOGGER.info("ModelService.getModelType called: playerId={}, skinId={}, requestedModel={}",
                playerId, skinId, requestedModel);

        // If a specific model is explicitly requested (not "auto"), honor that request
        if (requestedModel != null && !"auto".equalsIgnoreCase(requestedModel)) {
            QuickSkin.LOGGER.info("Using explicitly requested model: {}", requestedModel);
            return requestedModel;
        }

        // If auto mode, detect from skin texture (ignore overrides)
        if ("auto".equalsIgnoreCase(requestedModel)) {
            // Auto-detect from skin texture
            if (skinId != null && skinId.startsWith("local_skin:")) {
                String hash = skinId.substring("local_skin:".length());
                QuickSkin.LOGGER.info("Auto-detecting model type for skin hash: {}", hash);

                // Detect from texture
                byte[] skinData = LocalAssetManager.getInstance().loadTexture(hash, TextureQuality.PREVIEW);
                if (skinData != null) {
                    String detected = detectModelType(skinData);
                    QuickSkin.LOGGER.info("Auto-detected model type: {}", detected);
                    return detected;
                }
            }

            // Default to classic if detection fails
            QuickSkin.LOGGER.warn("Failed to auto-detect model type, defaulting to classic");
            return "classic";
        }

        // Fallback: check for override
        if (modelOverrides.containsKey(playerId)) {
            String override = modelOverrides.get(playerId);
            if (!"auto".equalsIgnoreCase(override)) {
                QuickSkin.LOGGER.info("Using model override: {}", override);
                return override;
            }
        }

        return "classic";
    }

    @Override
    public String detectModelType(byte[] skinData) {
        if (skinData == null) {
            return "classic";
        }

        try {
            return SkinModelDetector.detectSkinModel(skinData);
        } catch (Exception e) {
            QuickSkin.LOGGER.error("Failed to detect model type from skin data", e);
            return "classic";
        }
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
