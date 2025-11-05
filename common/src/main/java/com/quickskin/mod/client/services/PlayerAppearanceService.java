package com.quickskin.mod.client.services;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.common.data.PlayerAppearanceRepository;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/**
 * Main coordinator service for player appearance
 * This delegates work to specialized services (SkinService, CapeService, ModelService)
 */
@Environment(EnvType.CLIENT)
public class PlayerAppearanceService implements IPlayerAppearanceService {
    private static PlayerAppearanceService instance;

    private final PlayerAppearanceRepository repository;
    private final ISkinService skinService;
    private final ICapeService capeService;
    private final IModelService modelService;

    private PlayerAppearanceService() {
        this.repository = PlayerAppearanceRepository.getInstance();
        this.skinService = SkinService.getInstance();
        this.capeService = CapeService.getInstance();
        this.modelService = ModelService.getInstance();
    }

    public static PlayerAppearanceService getInstance() {
        if (instance == null) {
            instance = new PlayerAppearanceService();
        }
        return instance;
    }

    public static void init() {
        getInstance();
        QuickSkin.LOGGER.info("PlayerAppearanceService initialized");
    }

    @Override
    public void applyLook(UUID playerId, @Nullable String skinId, @Nullable String capeId, @Nullable String model) {
        if (playerId == null) {
            QuickSkin.LOGGER.error("Cannot apply look: playerId is null");
            return;
        }

        QuickSkin.LOGGER.info("Applying look to player {}: skin={}, cape={}, model={}",
                playerId, skinId, capeId, model);

        // Get or create appearance
        PlayerAppearance appearance = repository.getAppearance(playerId);
        if (appearance == null) {
            appearance = new PlayerAppearance(playerId, "", "", "classic");
            repository.setAppearance(appearance);
        }

        // Update skin
        if (skinId != null) {
            appearance.setSkinId(skinId);

            // Resolve model type
            String requestedModel = model != null ? model : "auto";
            String resolvedModel = modelService.getModelType(playerId, skinId, requestedModel);
            appearance.setModel(resolvedModel);

            // Store the REQUESTED model (not resolved) as override
            // This allows "auto" to re-detect each time instead of locking to the first detection
            modelService.setModelOverride(playerId, requestedModel);

            // Load skin ResourceLocation
            ResourceLocation skinLocation = skinService.getSkinLocation(playerId, skinId);
            if (skinLocation != null) {
                appearance.setSkinLocation(skinLocation);
            }
        }

        // Update cape
        if (capeId != null) {
            appearance.setCapeId(capeId);

            // Load cape ResourceLocation
            ResourceLocation capeLocation = capeService.getCapeLocation(playerId, capeId);
            if (capeLocation != null) {
                appearance.setCapeLocation(capeLocation);
            }
        }

        // Refresh player renderer
        refreshPlayerRenderer(playerId);

        // Phase 10: Fire PlayerAppearanceUpdateEvent
        com.quickskin.mod.common.event.PlayerAppearanceUpdateEvent.UpdateType updateType;
        if (skinId != null && capeId != null) {
            updateType = com.quickskin.mod.common.event.PlayerAppearanceUpdateEvent.UpdateType.FULL;
        } else if (skinId != null) {
            updateType = com.quickskin.mod.common.event.PlayerAppearanceUpdateEvent.UpdateType.SKIN;
        } else if (capeId != null) {
            updateType = com.quickskin.mod.common.event.PlayerAppearanceUpdateEvent.UpdateType.CAPE;
        } else if (model != null) {
            updateType = com.quickskin.mod.common.event.PlayerAppearanceUpdateEvent.UpdateType.MODEL;
        } else {
            updateType = com.quickskin.mod.common.event.PlayerAppearanceUpdateEvent.UpdateType.FULL;
        }

        com.quickskin.mod.common.event.InternalEventBus.getInstance().post(
            new com.quickskin.mod.common.event.PlayerAppearanceUpdateEvent(playerId, appearance, updateType)
        );
        QuickSkin.LOGGER.debug("Fired PlayerAppearanceUpdateEvent for {} (type: {})", playerId, updateType);
    }

    @Override
    public void applySkin(UUID playerId, String skinId, @Nullable String model) {
        applyLook(playerId, skinId, null, model);
    }

    @Override
    public void applyCape(UUID playerId, String capeId) {
        applyLook(playerId, null, capeId, null);
    }

    @Override
    public void removeSkin(UUID playerId) {
        PlayerAppearance appearance = repository.getAppearance(playerId);
        if (appearance != null) {
            appearance.setSkinId("");
            appearance.setSkinLocation(null);
            refreshPlayerRenderer(playerId);
        }
    }

    @Override
    public void removeCape(UUID playerId) {
        PlayerAppearance appearance = repository.getAppearance(playerId);
        if (appearance != null) {
            appearance.setCapeId("");
            appearance.setCapeLocation(null);
            refreshPlayerRenderer(playerId);
        }
    }

    @Override
    @Nullable
    public PlayerAppearance getAppearance(UUID playerId) {
        return repository.getAppearance(playerId);
    }

    @Override
    public void refreshPlayerRenderer(UUID playerId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.levelRenderer != null) {
            AbstractClientPlayer player = (AbstractClientPlayer) mc.level.getPlayerByUUID(playerId);
            if (player != null) {
                // Force block update to refresh renderer
                BlockPos pos = player.blockPosition();
                BlockState state = mc.level.getBlockState(pos);
                mc.levelRenderer.setBlockDirty(pos, state, state);

                // Phase 10: SkinLayers3D compatibility refresh
                if (com.quickskin.mod.config.ClientConfig.getInstance().skinLayers3DCompat) {
                    refreshSkinLayers3D(player);
                }

                QuickSkin.LOGGER.debug("Refreshed renderer for player: {}", playerId);
            }
        }
    }

    /**
     * Refresh SkinLayers3D rendering for a player (if mod is present)
     * SkinLayers3D adds 3D layers to player skins
     */
    private void refreshSkinLayers3D(AbstractClientPlayer player) {
        try {
            // Check if SkinLayers3D is loaded
            Class<?> skinLayersClass = Class.forName("dev.tr7zw.skinlayers.SkinLayersModBase");

            // Try to call the refresh method if it exists
            // This is a safe approach - if the mod structure changes, it just won't refresh
            java.lang.reflect.Method refreshMethod = skinLayersClass.getDeclaredMethod("refreshPlayer", net.minecraft.world.entity.player.Player.class);
            refreshMethod.setAccessible(true);
            refreshMethod.invoke(null, player);

            QuickSkin.LOGGER.debug("Refreshed SkinLayers3D for player: {}", player.getUUID());
        } catch (ClassNotFoundException e) {
            // SkinLayers3D not installed - this is fine
        } catch (Exception e) {
            // Method signature changed or other issue - log but don't crash
            QuickSkin.LOGGER.debug("Could not refresh SkinLayers3D (mod may have updated): {}", e.getMessage());
        }
    }

    /**
     * Check if player has an active custom skin
     * Used by mixins to determine if QuickSkin should override
     */
    public boolean hasActiveSkin(UUID playerId) {
        PlayerAppearance appearance = repository.getAppearance(playerId);
        return appearance != null && appearance.getSkinLocation() != null;
    }

    /**
     * Check if player has an active custom cape
     * Used by mixins to determine if QuickSkin should override
     */
    public boolean hasActiveCape(UUID playerId) {
        PlayerAppearance appearance = repository.getAppearance(playerId);
        return appearance != null && appearance.getCapeId() != null && !appearance.getCapeId().isEmpty();
    }

    /**
     * Check if player has a model override
     * Used by mixins to determine if QuickSkin should override
     */
    public boolean hasModelOverride(UUID playerId) {
        return modelService.hasModelOverride(playerId);
    }

    /**
     * Get skin ResourceLocation for a player
     * Used by mixins
     */
    @Nullable
    public ResourceLocation getSkinLocation(UUID playerId) {
        PlayerAppearance appearance = repository.getAppearance(playerId);
        return appearance != null ? appearance.getSkinLocation() : null;
    }

    /**
     * Get cape ResourceLocation for a player
     * Used by mixins
     */
    @Nullable
    public ResourceLocation getCapeLocation(UUID playerId) {
        PlayerAppearance appearance = repository.getAppearance(playerId);
        if (appearance == null) {
            return null;
        }

        // If the location is already cached, return it.
        if (appearance.getCapeLocation() != null) {
            return appearance.getCapeLocation();
        }

        // If not cached, try to resolve it now. This ensures the mixin gets the texture as soon as it's ready.
        if (appearance.getCapeId() != null && !appearance.getCapeId().isEmpty()) {
            ResourceLocation location = capeService.getCapeLocation(playerId, appearance.getCapeId());
            if (location != null) {
                appearance.setCapeLocation(location); // Cache it for next time
                return location;
            }
        }

        return null;
    }

    /**
     * Get model name for a player
     * Used by mixins
     */
    @Nullable
    public String getModelName(UUID playerId) {
        // Check model override first (priority)
        String override = modelService.getModelOverride(playerId);
        if (override != null) {
            // If override is "auto", resolve it to the actual model type
            if ("auto".equalsIgnoreCase(override)) {
                PlayerAppearance appearance = repository.getAppearance(playerId);
                if (appearance != null) {
                    String skinId = appearance.getSkinId();
                    // Resolve "auto" to actual model type
                    String resolvedModel = modelService.getModelType(playerId, skinId, "auto");
                    return resolvedModel;
                }
            }
            return override;
        }

        // Fall back to appearance model
        PlayerAppearance appearance = repository.getAppearance(playerId);
        return appearance != null ? appearance.getModel() : null;
    }
}
