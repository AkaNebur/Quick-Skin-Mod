package com.quickskin.mod.mixin;

import com.mojang.authlib.GameProfile;
import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin to intercept PlayerInfo skin lookups and apply custom skins/capes.
 */
@Mixin(value = PlayerInfo.class, priority = 500)
public abstract class PlayerInfoMixin {

    @Shadow
    @Final
    private GameProfile profile;

    // Cache for the custom PlayerSkin to avoid rebuilding it every frame
    @Unique
    private PlayerSkin quickskin$cachedSkin = null;

    // Cache the original skin's texture to detect when underlying skin changed
    @Unique
    private ResourceLocation quickskin$cachedOriginalTexture = null;

    // Cache key components to detect when we need to rebuild
    @Unique
    private ResourceLocation quickskin$cachedSkinLocation = null;
    @Unique
    private ResourceLocation quickskin$cachedCapeLocation = null;
    @Unique
    private String quickskin$cachedModelName = null;

    /**
     * Inject at TAIL to override skin data when we have custom skin/cape/model.
     */
    @Inject(method = "getSkin", at = @At("TAIL"), cancellable = true)
    private void quickskin$overrideSkinTail(CallbackInfoReturnable<PlayerSkin> cir) {
        if (CPMCompatIntegration.shouldDeferToCPM()) return;

        PlayerAppearanceService service = PlayerAppearanceService.getInstance();
        if (service == null) {
            return;
        }

        boolean hasCustomSkin = service.hasActiveSkin(this.profile.getId());
        boolean hasCustomCape = service.hasActiveCape(this.profile.getId());
        boolean hasModelOverride = service.hasModelOverride(this.profile.getId());

        // Only modify if we have custom data
        if (!hasCustomSkin && !hasCustomCape && !hasModelOverride) {
            // Title screen fallback: when no world is loaded, build skin from config
            if (Minecraft.getInstance().level == null) {
                PlayerSkin fallback = quickskin$buildTitleScreenFallback(cir.getReturnValue());
                if (fallback != null) {
                    cir.setReturnValue(fallback);
                    return;
                }
            }
            quickskin$cachedSkin = null;
            quickskin$cachedOriginalTexture = null;
            return;
        }

        PlayerSkin original = cir.getReturnValue();
        if (original == null) {
            return;
        }

        // Get current Quick-Skin data
        ResourceLocation currentSkinLocation = hasCustomSkin ? service.getSkinLocation(this.profile.getId()) : null;
        ResourceLocation currentCapeLocation = hasCustomCape ? service.getCapeLocation(this.profile.getId()) : null;
        String currentModelName = (hasCustomSkin || hasModelOverride) ? service.getModelName(this.profile.getId()) : null;

        // FAST PATH: Check if we can use cached result
        if (quickskin$cachedSkin != null &&
            java.util.Objects.equals(quickskin$cachedOriginalTexture, original.texture()) &&
            java.util.Objects.equals(quickskin$cachedSkinLocation, currentSkinLocation) &&
            java.util.Objects.equals(quickskin$cachedCapeLocation, currentCapeLocation) &&
            java.util.Objects.equals(quickskin$cachedModelName, currentModelName)) {
            cir.setReturnValue(quickskin$cachedSkin);
            return;
        }

        // SLOW PATH: Cache miss, need to rebuild
        ResourceLocation skinTexture = original.texture();
        PlayerSkin.Model skinModel = original.model();
        ResourceLocation capeTexture = original.capeTexture();

        // Override skin texture
        if (hasCustomSkin && currentSkinLocation != null) {
            skinTexture = currentSkinLocation;
        }

        // Override model
        if ((hasCustomSkin || hasModelOverride) && currentModelName != null) {
            skinModel = "slim".equals(currentModelName) ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE;
        }

        // Override cape
        if (hasCustomCape) {
            if (currentCapeLocation != null) {
                capeTexture = currentCapeLocation;
            } else {
                com.quickskin.mod.common.data.PlayerAppearance appearance = service.getAppearance(this.profile.getId());
                if (appearance != null && ("__NONE__".equals(appearance.getCapeId()) || appearance.getCapeId().isEmpty())) {
                    capeTexture = null;
                }
            }
        }

        // Create new PlayerSkin with our custom values
        PlayerSkin customSkin = new PlayerSkin(
            skinTexture,
            original.textureUrl(),
            capeTexture,
            original.elytraTexture(),
            skinModel,
            original.secure()
        );

        // Cache the result
        quickskin$cachedSkin = customSkin;
        quickskin$cachedOriginalTexture = original.texture();
        quickskin$cachedSkinLocation = currentSkinLocation;
        quickskin$cachedCapeLocation = currentCapeLocation;
        quickskin$cachedModelName = currentModelName;

        cir.setReturnValue(customSkin);
    }

    /**
     * Builds a PlayerSkin from saved config for title screen fallback.
     * Returns null if no saved skin/cape is configured.
     */
    @Unique
    private PlayerSkin quickskin$buildTitleScreenFallback(PlayerSkin original) {
        ClientConfig config = ClientConfig.getInstance();
        boolean hasSkin = !config.activeSkinHash.isEmpty();
        boolean hasCape = !config.activeCapeHash.isEmpty();

        if (!hasSkin && !hasCape) {
            return null;
        }

        LocalAssetManager assetManager = LocalAssetManager.getInstance();
        ResourceLocation skinTexture = null;
        PlayerSkin.Model skinModel = (original != null) ? original.model() : PlayerSkin.Model.WIDE;
        ResourceLocation capeTexture = null;

        if (hasSkin) {
            skinTexture = assetManager.getTextureLocation(config.activeSkinHash, TextureQuality.FULL);
            String modelType = assetManager.getSkinModelPreference(config.activeSkinHash);
            if ("auto".equals(modelType)) {
                AssetMetadata metadata = assetManager.getMetadata(config.activeSkinHash);
                if (metadata != null) {
                    modelType = metadata.skinModel();
                }
            }
            skinModel = "slim".equals(modelType) ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE;
        }

        if (hasCape) {
            capeTexture = com.quickskin.mod.client.services.CapeService.getInstance()
                    .getCapeLocation(null, config.activeCapeHash);
        }

        if (skinTexture == null && capeTexture == null) {
            return null;
        }

        if (original != null) {
            return new PlayerSkin(
                skinTexture != null ? skinTexture : original.texture(),
                original.textureUrl(),
                capeTexture != null ? capeTexture : original.capeTexture(),
                original.elytraTexture(),
                skinModel,
                original.secure()
            );
        }

        // Last resort: build with just our textures
        return new PlayerSkin(
            skinTexture, null, capeTexture, null, skinModel, false
        );
    }
}
