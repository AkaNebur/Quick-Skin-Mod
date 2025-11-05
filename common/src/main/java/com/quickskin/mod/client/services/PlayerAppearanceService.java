package com.quickskin.mod.client.services;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.common.data.AnimationMetadata;
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
            // Unregister old animation if cape is changing
            String oldCapeId = appearance.getCapeId();
            if (oldCapeId != null && !oldCapeId.equals(capeId) && (oldCapeId.startsWith("local_cape:") || oldCapeId.startsWith("known:"))) {
                String animationId = null;
                if (oldCapeId.startsWith("local_cape:")) {
                    animationId = "cape_" + oldCapeId.substring("local_cape:".length());
                } else if (oldCapeId.startsWith("known:")) {
                    animationId = "cape_known_" + oldCapeId.substring("known:".length());
                }
                if (animationId != null) {
                    AnimatedTextureManager.getInstance().unregisterAnimation(animationId);
                }
            }

            appearance.setCapeId(capeId);
            appearance.setCapeLocation(null);

            // Load the cape texture (static or atlas)
            ResourceLocation capeLocation = capeService.getCapeLocation(playerId, capeId);
            if (capeLocation != null) {
                appearance.setCapeLocation(capeLocation);

                // If animated, ensure the animation is registered
                if (capeService.isAnimated(capeId)) {
                    String hash = null;
                    String animationId = null;

                    if (capeId.startsWith("local_cape:")) {
                        hash = capeId.substring("local_cape:".length());
                        animationId = "cape_" + hash;
                    } else if (capeId.startsWith("known:")) {
                        // Logic in CapeService already handles registering known capes
                    }

                    if (hash != null && animationId != null) {
                        AnimationMetadata metadata = LocalAssetManager.getInstance().getAnimationMetadata(hash);
                        if (metadata != null) {
                            AnimatedTextureManager.getInstance().registerAnimation(animationId, capeLocation, metadata);
                        }
                    }
                }
            }
        }


        // Refresh player renderer
        refreshPlayerRenderer(playerId);

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
            // Unregister animation when removing cape
            String oldCapeId = appearance.getCapeId();
            if (oldCapeId != null && (oldCapeId.startsWith("local_cape:") || oldCapeId.startsWith("known:"))) {
                String animationId = null;
                if (oldCapeId.startsWith("local_cape:")) {
                    animationId = "cape_" + oldCapeId.substring("local_cape:".length());
                } else if (oldCapeId.startsWith("known:")) {
                    animationId = "cape_known_" + oldCapeId.substring("known:".length());
                }
                if (animationId != null) {
                    AnimatedTextureManager.getInstance().unregisterAnimation(animationId);
                }
            }

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

                if (com.quickskin.mod.config.ClientConfig.getInstance().skinLayers3DCompat) {
                    refreshSkinLayers3D(player);
                }

                QuickSkin.LOGGER.debug("Refreshed renderer for player: {}", playerId);
            }
        }
    }

    private void refreshSkinLayers3D(AbstractClientPlayer player) {
        try {
            Class<?> skinLayersClass = Class.forName("dev.tr7zw.skinlayers.SkinLayersModBase");
            java.lang.reflect.Method refreshMethod = skinLayersClass.getDeclaredMethod("refreshPlayer", net.minecraft.world.entity.player.Player.class);
            refreshMethod.setAccessible(true);
            refreshMethod.invoke(null, player);
            QuickSkin.LOGGER.debug("Refreshed SkinLayers3D for player: {}", player.getUUID());
        } catch (ClassNotFoundException e) {
            // Mod not installed
        } catch (Exception e) {
            QuickSkin.LOGGER.debug("Could not refresh SkinLayers3D (mod may have updated): {}", e.getMessage());
        }
    }

    public boolean hasActiveSkin(UUID playerId) {
        PlayerAppearance appearance = repository.getAppearance(playerId);
        return appearance != null && appearance.getSkinLocation() != null;
    }

    public boolean hasActiveCape(UUID playerId) {
        PlayerAppearance appearance = repository.getAppearance(playerId);
        return appearance != null && appearance.getCapeId() != null && !appearance.getCapeId().isEmpty();
    }

    public boolean hasModelOverride(UUID playerId) {
        return modelService.hasModelOverride(playerId);
    }

    @Nullable
    public ResourceLocation getSkinLocation(UUID playerId) {
        PlayerAppearance appearance = repository.getAppearance(playerId);
        return appearance != null ? appearance.getSkinLocation() : null;
    }

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

        // If not cached, try to resolve it now.
        if (appearance.getCapeId() != null && !appearance.getCapeId().isEmpty()) {
            ResourceLocation location = capeService.getCapeLocation(playerId, appearance.getCapeId());
            if (location != null) {
                appearance.setCapeLocation(location); // Cache it for next time
                return location;
            }
        }

        return null;
    }

    @Nullable
    public String getModelName(UUID playerId) {
        String override = modelService.getModelOverride(playerId);
        if (override != null) {
            if ("auto".equalsIgnoreCase(override)) {
                PlayerAppearance appearance = repository.getAppearance(playerId);
                if (appearance != null) {
                    String skinId = appearance.getSkinId();
                    return modelService.getModelType(playerId, skinId, "auto");
                }
            }
            return override;
        }

        PlayerAppearance appearance = repository.getAppearance(playerId);
        return appearance != null ? appearance.getModel() : null;
    }
}