package com.quickskin.mod.client.services;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.compat.CustomNPCsIntegration;
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
    }

    @Override
    public void applyLook(UUID playerId, @Nullable String skinId, @Nullable String capeId, @Nullable String model) {
        if (playerId == null) {
            return;
        }

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

                // Trigger async transparency analysis for the skin texture
                com.quickskin.mod.common.util.TextureAlphaDetector.analyzeTextureAsync(skinLocation);

                // Notify CustomNPCs integration (if available) to handle any skin cache invalidation
                CustomNPCsIntegration.onSkinApplied(playerId, skinLocation);
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

                // Trigger async transparency analysis for the cape texture
                com.quickskin.mod.common.util.TextureAlphaDetector.analyzeTextureAsync(capeLocation);

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
                            AnimatedTextureManager.getInstance().registerAnimation(animationId, capeId, capeLocation, atlasImage, metadata);
                        }
                    } else if (capeId.startsWith("known:")) {
                        // Register known cape animation
                        String knownId = capeId.substring("known:".length());
                        capeService.loadKnownCape(knownId);
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
                new com.quickskin.mod.common.event.PlayerAppearanceUpdateEvent(playerId, appearance)
        );

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

                // Always refresh SkinLayers3D compatibility
                refreshSkinLayers3D(player);

            }
        }
    }

    private void refreshSkinLayers3D(AbstractClientPlayer player) {
        try {
            Class<?> skinLayersClass = Class.forName("dev.tr7zw.skinlayers.SkinLayersModBase");
            java.lang.reflect.Method refreshMethod = skinLayersClass.getDeclaredMethod("refreshPlayer", net.minecraft.world.entity.player.Player.class);
            refreshMethod.setAccessible(true);
            refreshMethod.invoke(null, player);
        } catch (ClassNotFoundException e) {
            // Mod not installed
        } catch (Exception e) {
        }
    }

    public boolean hasActiveSkin(UUID playerId) {
        PlayerAppearance appearance = repository.getAppearance(playerId);
        return appearance != null && appearance.getSkinId() != null && !appearance.getSkinId().isEmpty();
    }

    public boolean hasActiveCape(UUID playerId) {
        PlayerAppearance appearance = repository.getAppearance(playerId);
        return appearance != null && appearance.getCapeId() != null && !appearance.getCapeId().isEmpty();
    }

    /**
     * Get the cape ID for a player (e.g., "local_cape:hash" or "known:rickroll")
     * @param playerId The player's UUID
     * @return The cape ID string, or null if no cape is set
     */
    @Nullable
    public String getCapeId(UUID playerId) {
        PlayerAppearance appearance = repository.getAppearance(playerId);
        if (appearance == null) {
            return null;
        }
        String capeId = appearance.getCapeId();
        return (capeId != null && !capeId.isEmpty()) ? capeId : null;
    }

    public boolean hasModelOverride(UUID playerId) {
        return modelService.hasModelOverride(playerId);
    }

    @Nullable
    public ResourceLocation getSkinLocation(UUID playerId) {
        PlayerAppearance appearance = repository.getAppearance(playerId);
        if (appearance == null) {
            return null;
        }

        // If the location is already cached, return it.
        if (appearance.getSkinLocation() != null) {
            return appearance.getSkinLocation();
        }

        // SLOW PATH - LOG THIS!

        // If not cached, try to resolve it now.
        // This handles the race condition where SYNC_APPEARANCE arrives before SEND_TEXTURE
        if (appearance.getSkinId() != null && !appearance.getSkinId().isEmpty()) {
            ResourceLocation location = skinService.getSkinLocation(playerId, appearance.getSkinId());
            if (location != null) {
                appearance.setSkinLocation(location); // Cache it for next time

                // Trigger async transparency analysis for the skin texture
                com.quickskin.mod.common.util.TextureAlphaDetector.analyzeTextureAsync(location);

                return location;
            }
        }

        return null;
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

                // Trigger async transparency analysis for the cape texture
                com.quickskin.mod.common.util.TextureAlphaDetector.analyzeTextureAsync(location);

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

    /**
     * Reloads all player skin textures to apply transparency setting changes.
     * This method is granular and only affects skins, leaving capes untouched.
     */
    public void reloadSkinsForTransparencyChange() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;

        // Clear texture alpha detection cache since transparency settings changed
        com.quickskin.mod.common.util.TextureAlphaDetector.clearCache();

        // Clear ONLY skin textures from local cache (not capes!)
        LocalAssetManager.getInstance().clearSkinTextureCache();

        // Reprocess network skin textures with new transparency setting (reprocesses from original data)
        com.quickskin.mod.client.storage.NetworkTextureCache.getInstance().reprocessSkins();

        // Refresh the skin list UI if we're in the skin menu
        if (mc.screen instanceof com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen skinMenu) {
            skinMenu.refreshSkinList();
        }

        // Re-apply ALL players' appearances to force them to fetch new ResourceLocations
        if (mc.level != null && mc.level.players() != null) {
            java.util.List<net.minecraft.world.entity.player.Player> players = new java.util.ArrayList<>(mc.level.players());

            for (net.minecraft.world.entity.player.Player player : players) {
                if (player != null) {
                    com.quickskin.mod.common.data.PlayerAppearance appearance = getAppearance(player.getUUID());
                    if (appearance != null) {
                        // Invalidate cached locations to force re-fetch
                        appearance.setSkinLocation(null);
                        // Re-apply the look to trigger re-resolution and refresh the renderer
                        applyLook(player.getUUID(), appearance.getSkinId(), appearance.getCapeId(), appearance.getModel());
                    }
                }
            }
        }
    }
}