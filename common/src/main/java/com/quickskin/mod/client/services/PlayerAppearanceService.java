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

import java.awt.image.BufferedImage;
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
            // NOTE: We do NOT unregister the old animation when switching capes.
            // Animations are needed for thumbnail rendering in the capes menu, and unregistering
            // them would cause thumbnails to fall back to the full atlas texture, displaying
            // all frames at once. Animations will be cleaned up when appropriate (e.g., when
            // the player disconnects or leaves the world).

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

                        AnimationMetadata metadata = LocalAssetManager.getInstance().getAnimationMetadata(hash);
                        BufferedImage atlasImage = LocalAssetManager.getInstance().getSourceImage(hash);
                        if (metadata != null && atlasImage != null) {
                            QuickSkin.LOGGER.info("[PlayerAppearanceService] Registering local cape animation {} for player {}",
                                animationId, playerId);
                            AnimatedTextureManager.getInstance().registerAnimation(animationId, capeId, capeLocation, atlasImage, metadata);
                        } else {
                            QuickSkin.LOGGER.warn("[PlayerAppearanceService] Failed to register local cape animation {} - metadata={}, atlasImage={}",
                                animationId, metadata != null, atlasImage != null);
                        }
                    } else if (capeId.startsWith("known:")) {
                        // Register known cape animation
                        String knownId = capeId.substring("known:".length());
                        capeService.loadKnownCape(knownId);
                        QuickSkin.LOGGER.info("[PlayerAppearanceService] Registered known cape animation for player {}: {}", playerId, knownId);
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

        // Sync to server if this is the local player
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && playerId.equals(mc.player.getUUID())) {
            com.quickskin.mod.networking.NetworkSyncService.getInstance().syncAppearance(
                playerId,
                appearance.getSkinId(),
                appearance.getCapeId(),
                appearance.getModel()
            );
        }
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

            // Sync to server if this is the local player
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && playerId.equals(mc.player.getUUID())) {
                com.quickskin.mod.networking.NetworkSyncService.getInstance().syncAppearance(
                    playerId,
                    "",
                    appearance.getCapeId(),
                    appearance.getModel()
                );
            }
        }
    }

    @Override
    public void removeCape(UUID playerId) {
        PlayerAppearance appearance = repository.getAppearance(playerId);
        if (appearance != null) {
            // NOTE: We do NOT unregister animations when removing a cape.
            // Animations are needed for thumbnail rendering in the capes menu.
            // They will be cleaned up when appropriate (e.g., menu closes, player disconnects).

            appearance.setCapeId("");
            appearance.setCapeLocation(null);
            refreshPlayerRenderer(playerId);

            // Sync to server if this is the local player
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && playerId.equals(mc.player.getUUID())) {
                com.quickskin.mod.networking.NetworkSyncService.getInstance().syncAppearance(
                    playerId,
                    appearance.getSkinId(),
                    "",
                    appearance.getModel()
                );
            }
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