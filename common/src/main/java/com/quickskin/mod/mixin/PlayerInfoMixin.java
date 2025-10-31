package com.quickskin.mod.mixin;

import com.mojang.authlib.GameProfile;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin to intercept PlayerInfo texture and model lookups
 * Allows QuickSkin to override player skins, capes, and models
 */
@Mixin(value = PlayerInfo.class, priority = 900) // Lower priority to let other mods run first
public abstract class PlayerInfoMixin {

    @Shadow
    @Final
    private GameProfile profile;

    /**
     * Inject into getSkinLocation to override with QuickSkin texture
     */
    @Inject(method = "getSkinLocation", at = @At("HEAD"), cancellable = true)
    private void quickskin$onGetSkinLocation(CallbackInfoReturnable<ResourceLocation> cir) {
        PlayerAppearanceService service = PlayerAppearanceService.getInstance();

        // Only override if QuickSkin has an active custom skin for this player
        if (service.hasActiveSkin(this.profile.getId())) {
            ResourceLocation customSkin = service.getSkinLocation(this.profile.getId());
            if (customSkin != null) {
                cir.setReturnValue(customSkin);
            }
        }
        // If no active QuickSkin, let vanilla or other mods handle it
    }

    /**
     * Inject into getModelName to override with QuickSkin model type
     */
    @Inject(method = "getModelName", at = @At("HEAD"), cancellable = true)
    private void quickskin$onGetModelName(CallbackInfoReturnable<String> cir) {
        PlayerAppearanceService service = PlayerAppearanceService.getInstance();

        // Override if QuickSkin has an active custom model OR a model override for this player
        // Check both to avoid race condition where model is set before skin data is populated
        if (service.hasActiveSkin(this.profile.getId()) || service.hasModelOverride(this.profile.getId())) {
            String customModel = service.getModelName(this.profile.getId());
            if (customModel != null) {
                cir.setReturnValue(customModel);
            }
        }
    }

    /**
     * Inject into getCapeLocation to override with QuickSkin cape
     */
    @Inject(method = "getCapeLocation", at = @At("HEAD"), cancellable = true)
    private void quickskin$onGetCapeLocation(CallbackInfoReturnable<ResourceLocation> cir) {
        PlayerAppearanceService service = PlayerAppearanceService.getInstance();

        // Only override if QuickSkin has an active custom cape for this player
        if (service.hasActiveCape(this.profile.getId())) {
            ResourceLocation customCape = service.getCapeLocation(this.profile.getId());

            if (customCape != null) {
                // Use the custom cape texture
                cir.setReturnValue(customCape);
            } else {
                // Null means hide the cape
                cir.setReturnValue(null);
            }
        }
        // If no active QuickSkin cape, let vanilla or other mods handle it
    }
}
