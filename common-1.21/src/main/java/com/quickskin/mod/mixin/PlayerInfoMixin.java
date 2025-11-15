package com.quickskin.mod.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.authlib.GameProfile;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Mixin to intercept PlayerInfo skin lookups
 * Allows QuickSkin to override player skins, capes, and models
 *
 * In MC 1.21.1, getSkinLocation/getModelName/getCapeLocation were replaced with getSkin()
 * which returns a PlayerSkin record containing all skin data
 *
 * PERFORMANCE CRITICAL: This method is called thousands of times per frame.
 * We cache the result to avoid expensive service lookups on every call.
 */
@Mixin(value = PlayerInfo.class, priority = 1100) // Higher priority to override TLSkinCape and other mods
public abstract class PlayerInfoMixin {

    @Shadow
    @Final
    private GameProfile profile;

    // Cache for the custom PlayerSkin to avoid rebuilding it every frame
    private PlayerSkin quickskin$cachedSkin = null;

    // Cache the original skin we based our custom skin on
    private PlayerSkin quickskin$cachedOriginalSkin = null;

    // Cache key components to detect when we need to rebuild
    private ResourceLocation quickskin$cachedSkinLocation = null;
    private ResourceLocation quickskin$cachedCapeLocation = null;
    private String quickskin$cachedModelName = null;

    /**
     * Modify the return value of getSkin() to override with QuickSkin data
     * This replaces the old getSkinLocation/getModelName/getCapeLocation injections
     */
    @ModifyReturnValue(method = "getSkin", at = @At("RETURN"))
    private PlayerSkin quickskin$onGetSkin(PlayerSkin originalSkin) {
        PlayerAppearanceService service = PlayerAppearanceService.getInstance();

        boolean hasCustomSkin = service.hasActiveSkin(this.profile.getId());
        boolean hasCustomCape = service.hasActiveCape(this.profile.getId());
        boolean hasModelOverride = service.hasModelOverride(this.profile.getId());

        // Only modify if we have custom data
        if (!hasCustomSkin && !hasCustomCape && !hasModelOverride) {
            // Clear cache if we no longer have custom data
            quickskin$cachedSkin = null;
            quickskin$cachedOriginalSkin = null;
            return originalSkin;
        }

        // FAST PATH: Check if we can use cached result
        // Get the current appearance data
        ResourceLocation currentSkinLocation = hasCustomSkin ? service.getSkinLocation(this.profile.getId()) : null;
        ResourceLocation currentCapeLocation = hasCustomCape ? service.getCapeLocation(this.profile.getId()) : null;
        String currentModelName = (hasCustomSkin || hasModelOverride) ? service.getModelName(this.profile.getId()) : null;

        // Check if cache is valid (original skin unchanged and component data unchanged)
        if (quickskin$cachedSkin != null &&
            quickskin$cachedOriginalSkin == originalSkin &&
            java.util.Objects.equals(quickskin$cachedSkinLocation, currentSkinLocation) &&
            java.util.Objects.equals(quickskin$cachedCapeLocation, currentCapeLocation) &&
            java.util.Objects.equals(quickskin$cachedModelName, currentModelName)) {
            // Cache hit! Return cached result without rebuilding
            return quickskin$cachedSkin;
        }

        // SLOW PATH: Cache miss, need to rebuild
        com.quickskin.mod.QuickSkin.LOGGER.debug("PlayerInfoMixin: Rebuilding skin for player {} (hasCustomSkin={}, hasCustomCape={}, hasModelOverride={})",
            this.profile.getName(), hasCustomSkin, hasCustomCape, hasModelOverride);

        // Get custom values or fall back to original (reuse the values we already fetched)
        ResourceLocation skinTexture = originalSkin.texture();
        PlayerSkin.Model skinModel = originalSkin.model();
        ResourceLocation capeTexture = originalSkin.capeTexture();

        // Override skin texture (use cached value we already retrieved)
        if (hasCustomSkin && currentSkinLocation != null) {
            skinTexture = currentSkinLocation;
            com.quickskin.mod.QuickSkin.LOGGER.debug("PlayerInfoMixin: Set custom skin texture to {}", currentSkinLocation);
        }

        // Override model type (use cached value we already retrieved)
        if ((hasCustomSkin || hasModelOverride) && currentModelName != null) {
            // Convert string model name to PlayerSkin.Model enum
            skinModel = "slim".equals(currentModelName) ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE;
            com.quickskin.mod.QuickSkin.LOGGER.debug("PlayerInfoMixin: Set custom model to {} ({})", currentModelName, skinModel);
        }

        // Override cape texture (use cached value we already retrieved)
        if (hasCustomCape) {
            if (currentCapeLocation != null) {
                capeTexture = currentCapeLocation;
                com.quickskin.mod.QuickSkin.LOGGER.debug("PlayerInfoMixin: Set custom cape texture to {}", currentCapeLocation);
            } else {
                // Check if we're explicitly hiding the cape
                com.quickskin.mod.common.data.PlayerAppearance appearance = service.getAppearance(this.profile.getId());
                if (appearance != null && ("__NONE__".equals(appearance.getCapeId()) || appearance.getCapeId().isEmpty())) {
                    capeTexture = null; // Hide cape
                    com.quickskin.mod.QuickSkin.LOGGER.debug("PlayerInfoMixin: Hiding cape");
                }
            }
        }

        // Create new PlayerSkin with our custom values
        PlayerSkin customSkin = new PlayerSkin(
            skinTexture,
            originalSkin.textureUrl(),
            capeTexture,
            originalSkin.elytraTexture(),
            skinModel,
            originalSkin.secure()
        );

        // Cache the result for subsequent calls
        quickskin$cachedSkin = customSkin;
        quickskin$cachedOriginalSkin = originalSkin;
        quickskin$cachedSkinLocation = currentSkinLocation;
        quickskin$cachedCapeLocation = currentCapeLocation;
        quickskin$cachedModelName = currentModelName;

        return customSkin;
    }
}
