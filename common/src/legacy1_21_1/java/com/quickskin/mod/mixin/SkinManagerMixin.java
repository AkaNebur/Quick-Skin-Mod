package com.quickskin.mod.mixin;

import com.mojang.authlib.GameProfile;
import com.quickskin.mod.client.compat.CPMCompatIntegration;
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
 * Mixin on SkinManager to intercept skin resolution at the canonical level.
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

    /**
     * Shared helper that applies QuickSkin overrides to a PlayerSkin.
     * Used by both getInsecureSkin and getOrLoad mixin handlers.
     *
     * @param original the original PlayerSkin from Mojang/vanilla
     * @param uuid     the player's UUID
     * @return the modified PlayerSkin, or the original if no overrides apply
     */
    @Unique
    private static PlayerSkin quickskin$applyOverrides(PlayerSkin original, UUID uuid) {
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
                ResourceLocation customSkin;
                if (CPMCompatIntegration.isAvailable()) {
                    // When CPM is installed, register skin as HttpTexture so CPM can
                    // read pixel data and extract embedded 3D model from the PNG file.
                    // CPM's 1.21+ pipeline checks instanceof HttpTexture on the texture.
                    com.quickskin.mod.common.data.PlayerAppearance appearance = service.getAppearance(uuid);
                    String hash = null;
                    if (appearance != null && appearance.getSkinId() != null) {
                        String skinId = appearance.getSkinId();
                        if (skinId.startsWith("local_skin:")) {
                            hash = skinId.substring("local_skin:".length());
                        }
                    }
                    if (hash != null) {
                        customSkin = CPMCompatIntegration.getOrRegisterHttpTexture(hash);
                        if (customSkin == null) {
                            customSkin = service.getSkinLocation(uuid);
                        }
                    } else {
                        customSkin = service.getSkinLocation(uuid);
                    }
                } else {
                    customSkin = service.getSkinLocation(uuid);
                }
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

    /**
     * Intercept getInsecureSkin (synchronous path).
     * Used by vanilla code and any mod that calls SkinManager.getInsecureSkin() directly.
     */
    @Inject(method = "getInsecureSkin", at = @At("RETURN"), cancellable = true)
    private void quickskin$modifyInsecureSkin(GameProfile profile, CallbackInfoReturnable<PlayerSkin> cir) {
        UUID uuid = profile.getId();
        if (uuid == null) return;

        PlayerSkin result = quickskin$applyOverrides(cir.getReturnValue(), uuid);
        if (result != cir.getReturnValue()) {
            cir.setReturnValue(result);
        }
    }

    /**
     * Intercept getOrLoad (async path returning CompletableFuture<PlayerSkin>).
     *
     * Essential for MC >= 1.20.2 uses FallbackPlayer which calls getOrLoad() directly,
     * bypassing getInsecureSkin(). We wrap the returned future with thenApply to apply
     * QuickSkin overrides when the future resolves.
     *
     * Note: thenApply runs synchronously when the source future is already completed
     * (cache hit), so getInsecureSkin (which calls getOrLoad().getNow(null)) will also
     * see the modified skin through this mixin.
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

        CompletableFuture<PlayerSkin> original = cir.getReturnValue();
        CompletableFuture<PlayerSkin> modified = original.thenApply(skin -> {
            return quickskin$applyOverrides(skin, uuid);
        });
        cir.setReturnValue(modified);
    }
}
