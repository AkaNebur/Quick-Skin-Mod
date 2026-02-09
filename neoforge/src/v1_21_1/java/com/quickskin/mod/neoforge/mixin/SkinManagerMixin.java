package com.quickskin.mod.neoforge.mixin;

import com.mojang.authlib.GameProfile;
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * NeoForge-specific mixin on SkinManager to intercept skin resolution at the canonical level.
 * Uses Mojmap names directly since NeoForge uses Mojmap at runtime.
 *
 * This catches ALL skin lookups including those by mods like Essential that bypass
 * AbstractClientPlayer.getSkin() and PlayerRenderer.getTextureLocation() entirely.
 *
 * Two injection points:
 * - getInsecureSkin(GameProfile) — synchronous, used by vanilla code paths
 * - getOrLoad(GameProfile) — async (CompletableFuture), used by Essential's FallbackPlayer on 1.20.2+
 *
 * Essential for MC >= 1.20.2 uses FallbackPlayer which calls getOrLoad() directly,
 * bypassing getInsecureSkin(). The getOrLoad mixin wraps the future with thenApply
 * so the skin override propagates to both paths.
 */
@Mixin(SkinManager.class)
public class SkinManagerMixin {

    @Unique
    private static final java.util.Map<UUID, Long> quickskin$lastLogTime = new java.util.concurrent.ConcurrentHashMap<>();
    @Unique
    private static final long QUICKSKIN$LOG_INTERVAL_MS = 5000;

    /**
     * Shared helper that applies QuickSkin overrides to a PlayerSkin.
     * Used by both getInsecureSkin and getOrLoad mixin handlers.
     */
    @Unique
    private static PlayerSkin quickskin$applyOverrides(PlayerSkin original, UUID uuid, String profileName, boolean shouldLog) {
        if (original == null || uuid == null) return original;

        PlayerAppearanceService service = PlayerAppearanceService.getInstance();
        if (service == null) return original;

        boolean hasCustomSkin = service.hasActiveSkin(uuid);
        boolean hasCustomCape = service.hasActiveCape(uuid);
        boolean hasModelOverride = service.hasModelOverride(uuid);

        // Try service-based overrides
        if (hasCustomSkin || hasCustomCape || hasModelOverride) {
            ResourceLocation skinTexture = original.texture();
            PlayerSkin.Model skinModel = original.model();
            ResourceLocation capeTexture = original.capeTexture();
            boolean anyOverride = false;

            if (hasCustomSkin) {
                ResourceLocation customSkin = service.getSkinLocation(uuid);
                if (customSkin != null) {
                    skinTexture = customSkin;
                    anyOverride = true;
                }
            }

            if (hasCustomSkin || hasModelOverride) {
                String customModel = service.getModelName(uuid);
                if (customModel != null) {
                    skinModel = "slim".equals(customModel) ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE;
                    anyOverride = true;
                }
            }

            if (hasCustomCape) {
                ResourceLocation customCape = service.getCapeLocation(uuid);
                if (customCape != null) {
                    capeTexture = customCape;
                    anyOverride = true;
                } else {
                    com.quickskin.mod.common.data.PlayerAppearance appearance = service.getAppearance(uuid);
                    if (appearance != null && ("__NONE__".equals(appearance.getCapeId()) || appearance.getCapeId().isEmpty())) {
                        capeTexture = null;
                        anyOverride = true;
                    }
                }
            }

            if (anyOverride) {
                if (shouldLog) {
                    QuickSkin.LOGGER.info("[SkinManagerMixin] Overriding skin for {}: skin={}, cape={}, model={}",
                            profileName, skinTexture, capeTexture, skinModel);
                }
                return new PlayerSkin(
                        skinTexture,
                        original.textureUrl(),
                        capeTexture,
                        original.elytraTexture(),
                        skinModel,
                        original.secure()
                );
            }
        }

        // Title screen config fallback
        if (Minecraft.getInstance().level == null) {
            ClientConfig config = ClientConfig.getInstance();
            boolean hasSkin = !config.activeSkinHash.isEmpty();
            boolean hasCape = !config.activeCapeHash.isEmpty();

            if (hasSkin || hasCape) {
                ResourceLocation skinTexture = original.texture();
                PlayerSkin.Model skinModel = original.model();
                ResourceLocation capeTexture = original.capeTexture();
                boolean anyOverride = false;

                if (hasSkin) {
                    ResourceLocation loc = LocalAssetManager.getInstance()
                            .getTextureLocation(config.activeSkinHash, TextureQuality.FULL);
                    if (loc != null) {
                        skinTexture = loc;
                        String modelType = LocalAssetManager.getInstance().getSkinModelPreference(config.activeSkinHash);
                        if ("auto".equals(modelType)) {
                            var metadata = LocalAssetManager.getInstance().getMetadata(config.activeSkinHash);
                            if (metadata != null) {
                                modelType = metadata.skinModel();
                            }
                        }
                        skinModel = "slim".equals(modelType) ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE;
                        anyOverride = true;
                    }
                }

                if (hasCape) {
                    ResourceLocation capeLoc = com.quickskin.mod.client.services.CapeService.getInstance()
                            .getCapeLocation(null, config.activeCapeHash);
                    if (capeLoc != null) {
                        capeTexture = capeLoc;
                        anyOverride = true;
                    }
                }

                if (anyOverride) {
                    if (shouldLog) {
                        QuickSkin.LOGGER.info("[SkinManagerMixin] Title screen fallback for {}: skin={}, cape={}",
                                profileName, skinTexture, capeTexture);
                    }
                    return new PlayerSkin(
                            skinTexture,
                            original.textureUrl(),
                            capeTexture,
                            original.elytraTexture(),
                            skinModel,
                            original.secure()
                    );
                }
            }
        }

        return original;
    }

    @Unique
    private static boolean quickskin$shouldLog(UUID uuid) {
        long now = System.currentTimeMillis();
        Long lastLog = quickskin$lastLogTime.get(uuid);
        boolean shouldLog = lastLog == null || now - lastLog > QUICKSKIN$LOG_INTERVAL_MS;
        if (shouldLog) {
            quickskin$lastLogTime.put(uuid, now);
        }
        return shouldLog;
    }

    /**
     * Intercept getInsecureSkin (synchronous path).
     * Used by vanilla code and any mod that calls SkinManager.getInsecureSkin() directly.
     */
    @Inject(method = "getInsecureSkin", at = @At("RETURN"), cancellable = true)
    private void quickskin$modifyInsecureSkin(GameProfile profile, CallbackInfoReturnable<PlayerSkin> cir) {
        UUID uuid = profile.getId();
        if (uuid == null) return;

        boolean shouldLog = quickskin$shouldLog(uuid);
        PlayerSkin result = quickskin$applyOverrides(cir.getReturnValue(), uuid, profile.getName(), shouldLog);
        if (result != cir.getReturnValue()) {
            cir.setReturnValue(result);
        }
    }

    /**
     * Intercept getOrLoad (async path returning CompletableFuture).
     *
     * Essential for MC >= 1.20.2 uses FallbackPlayer which calls getOrLoad() directly,
     * bypassing getInsecureSkin(). We wrap the returned future with thenApply to apply
     * QuickSkin overrides when the future resolves.
     */
    @Inject(method = "getOrLoad", at = @At("RETURN"), cancellable = true)
    private void quickskin$modifyGetOrLoad(GameProfile profile, CallbackInfoReturnable<CompletableFuture<PlayerSkin>> cir) {
        UUID uuid = profile.getId();
        if (uuid == null) return;

        PlayerAppearanceService service = PlayerAppearanceService.getInstance();
        boolean hasServiceOverrides = false;
        boolean hasTitleScreenFallback = false;

        if (service != null) {
            hasServiceOverrides = service.hasActiveSkin(uuid)
                    || service.hasActiveCape(uuid)
                    || service.hasModelOverride(uuid);
        }

        if (!hasServiceOverrides && Minecraft.getInstance().level == null) {
            ClientConfig config = ClientConfig.getInstance();
            hasTitleScreenFallback = !config.activeSkinHash.isEmpty() || !config.activeCapeHash.isEmpty();
        }

        // Only wrap the future if we actually have overrides to apply
        if (!hasServiceOverrides && !hasTitleScreenFallback) return;

        String profileName = profile.getName();
        CompletableFuture<PlayerSkin> original = cir.getReturnValue();
        CompletableFuture<PlayerSkin> modified = original.thenApply(skin -> {
            boolean shouldLog = quickskin$shouldLog(uuid);
            return quickskin$applyOverrides(skin, uuid, profileName, shouldLog);
        });
        cir.setReturnValue(modified);
    }
}
