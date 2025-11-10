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
 */
@Mixin(value = PlayerInfo.class, priority = 900) // Lower priority to let other mods run first
public abstract class PlayerInfoMixin {

    @Shadow
    @Final
    private GameProfile profile;

    /**
     * Modify the return value of getSkin() to override with QuickSkin data
     * This replaces the old getSkinLocation/getModelName/getCapeLocation injections
     */
    @ModifyReturnValue(method = "getSkin", at = @At("RETURN"))
    private PlayerSkin quickskin$onGetSkin(PlayerSkin originalSkin) {
        // ALWAYS log this to confirm mixin is being called
        com.quickskin.mod.QuickSkin.LOGGER.info("PlayerInfoMixin.getSkin() called for player: {}", this.profile.getName());

        PlayerAppearanceService service = PlayerAppearanceService.getInstance();

        boolean hasCustomSkin = service.hasActiveSkin(this.profile.getId());
        boolean hasCustomCape = service.hasActiveCape(this.profile.getId());
        boolean hasModelOverride = service.hasModelOverride(this.profile.getId());

        // Only modify if we have custom data
        if (!hasCustomSkin && !hasCustomCape && !hasModelOverride) {
            return originalSkin;
        }

        // Debug logging
        com.quickskin.mod.QuickSkin.LOGGER.info("PlayerInfoMixin: Overriding skin for player {} (hasCustomSkin={}, hasCustomCape={}, hasModelOverride={})",
            this.profile.getName(), hasCustomSkin, hasCustomCape, hasModelOverride);

        // Get custom values or fall back to original
        ResourceLocation skinTexture = originalSkin.texture();
        PlayerSkin.Model skinModel = originalSkin.model();
        ResourceLocation capeTexture = originalSkin.capeTexture();

        // Override skin texture
        if (hasCustomSkin) {
            ResourceLocation customSkin = service.getSkinLocation(this.profile.getId());
            if (customSkin != null) {
                skinTexture = customSkin;
                com.quickskin.mod.QuickSkin.LOGGER.info("PlayerInfoMixin: Set custom skin texture to {}", customSkin);
            }
        }

        // Override model type
        if (hasCustomSkin || hasModelOverride) {
            String customModelName = service.getModelName(this.profile.getId());
            if (customModelName != null) {
                // Convert string model name to PlayerSkin.Model enum
                skinModel = "slim".equals(customModelName) ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE;
                com.quickskin.mod.QuickSkin.LOGGER.info("PlayerInfoMixin: Set custom model to {} ({})", customModelName, skinModel);
            }
        }

        // Override cape texture
        if (hasCustomCape) {
            com.quickskin.mod.common.data.PlayerAppearance appearance = service.getAppearance(this.profile.getId());

            // Check for the explicit "hide cape" identifier
            if (appearance != null && ("__NONE__".equals(appearance.getCapeId()) || appearance.getCapeId().isEmpty())) {
                capeTexture = null; // Hide cape
                com.quickskin.mod.QuickSkin.LOGGER.info("PlayerInfoMixin: Hiding cape");
            } else {
                ResourceLocation customCape = service.getCapeLocation(this.profile.getId());
                if (customCape != null) {
                    capeTexture = customCape;
                    com.quickskin.mod.QuickSkin.LOGGER.info("PlayerInfoMixin: Set custom cape texture to {}", customCape);
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

        return customSkin;
    }
}
