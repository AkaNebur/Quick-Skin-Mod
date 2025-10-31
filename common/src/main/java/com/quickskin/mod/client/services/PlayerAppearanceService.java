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
            String resolvedModel = modelService.getModelType(playerId, skinId, model != null ? model : "auto");
            appearance.setModel(resolvedModel);
            modelService.setModelOverride(playerId, resolvedModel);

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

        // TODO Phase 10: Fire PlayerAppearanceUpdateEvent
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

                // TODO Phase 10: Add SkinLayers3D compatibility refresh
                QuickSkin.LOGGER.debug("Refreshed renderer for player: {}", playerId);
            }
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
        return appearance != null && appearance.getCapeLocation() != null;
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
        return appearance != null ? appearance.getCapeLocation() : null;
    }

    /**
     * Get model name for a player
     * Used by mixins
     */
    @Nullable
    public String getModelName(UUID playerId) {
        PlayerAppearance appearance = repository.getAppearance(playerId);
        return appearance != null ? appearance.getModel() : null;
    }
}
