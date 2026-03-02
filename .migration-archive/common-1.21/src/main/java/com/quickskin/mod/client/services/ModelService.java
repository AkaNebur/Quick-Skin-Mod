package com.quickskin.mod.client.services;

import com.quickskin.mod.QuickSkin;
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
    }

    @Override
    public String getModelType(UUID playerId, String skinId, String requestedModel) {
        // If a specific model is explicitly requested (not "auto"), honor that request
        if (requestedModel != null && !"auto".equalsIgnoreCase(requestedModel)) {
            return requestedModel;
        }

        // If auto mode, get the pre-detected model from metadata
        if ("auto".equalsIgnoreCase(requestedModel)) {
            if (skinId != null && skinId.startsWith("local_skin:")) {
                String hash = skinId.substring("local_skin:".length());

                // Get metadata from the asset manager which has the pre-detected model type
                com.quickskin.mod.common.data.AssetMetadata metadata =
                    LocalAssetManager.getInstance().getMetadata(hash);

                if (metadata != null && metadata.skinModel() != null) {
                    // Return the cached model type! This avoids all file I/O.
                    return metadata.skinModel();
                }
            }

            // Default to classic if detection fails or skinId is not local
            return "classic";
        }

        // Fallback: check for override
        if (modelOverrides.containsKey(playerId)) {
            String override = modelOverrides.get(playerId);
            if (!"auto".equalsIgnoreCase(override)) {
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
            return "classic";
        }
    }

    @Override
    public void setModelOverride(UUID playerId, String model) {
        if (playerId == null || model == null) {
            return;
        }
        modelOverrides.put(playerId, model);
    }

    @Override
    @Nullable
    public String getModelOverride(UUID playerId) {
        return modelOverrides.get(playerId);
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
