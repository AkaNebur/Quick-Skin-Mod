package com.quickskin.mod.mixin;

import com.quickskin.mod.client.services.PlayerAppearanceService;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin to intercept AbstractClientPlayer skin lookups at the deepest level
 * This operates at the same depth as TLSkinCape, with higher priority (2000) to win
 */
@Mixin(AbstractClientPlayer.class)
public class MixinAbstractClientPlayer {

    /**
     * Intercept skin texture lookups and return QuickSkin's texture if active.
     * We inject at HEAD with cancellable=true to short-circuit TLSkinCape and vanilla.
     *
     * With global mixin priority 2000 (higher than TLSkinCape's default 1000),
     * this ensures QuickSkin gets the final say on player skins.
     */
    @Inject(method = "getSkinTextureLocation", at = @At("HEAD"), cancellable = true)
    private void quickskin$getSkinTextureLocation(CallbackInfoReturnable<ResourceLocation> cir) {
        AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;

        // Get QuickSkin's texture for this player
        PlayerAppearanceService service = PlayerAppearanceService.getInstance();

        // Only override if QuickSkin has an active custom skin for this player
        if (service.hasActiveSkin(self.getUUID())) {
            ResourceLocation customSkin = service.getSkinLocation(self.getUUID());
            if (customSkin != null) {
                cir.setReturnValue(customSkin); // QuickSkin wins here
            }
        }
        // If no active QuickSkin, let vanilla or other mods handle it
    }

    /**
     * Intercept model name lookups to return QuickSkin's model type.
     * Works in tandem with skin texture override to ensure correct model rendering.
     */
    @Inject(method = "getModelName", at = @At("HEAD"), cancellable = true)
    private void quickskin$getModelName(CallbackInfoReturnable<String> cir) {
        AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;

        PlayerAppearanceService service = PlayerAppearanceService.getInstance();

        // Override if QuickSkin has an active custom model OR a model override for this player
        if (service.hasActiveSkin(self.getUUID()) || service.hasModelOverride(self.getUUID())) {
            String customModel = service.getModelName(self.getUUID());
            if (customModel != null) {
                cir.setReturnValue(customModel);
            }
        }
    }

    /**
     * Intercept cape texture lookups to return QuickSkin's cape if active.
     */
    @Inject(method = "getCloakTextureLocation", at = @At("HEAD"), cancellable = true)
    private void quickskin$getCloakTextureLocation(CallbackInfoReturnable<ResourceLocation> cir) {
        AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;

        PlayerAppearanceService service = PlayerAppearanceService.getInstance();

        // Only override if QuickSkin has an active custom cape for this player
        if (service.hasActiveCape(self.getUUID())) {
            com.quickskin.mod.common.data.PlayerAppearance appearance = service.getAppearance(self.getUUID());

            // Check for the explicit "hide cape" identifier
            if (appearance != null && ("__NONE__".equals(appearance.getCapeId()) || appearance.getCapeId().isEmpty())) {
                cir.setReturnValue(null); // Return null to hide the cape completely
                return;
            }

            ResourceLocation customCape = service.getCapeLocation(self.getUUID());

            // If a custom cape is found (or still loading but intended), set it.
            cir.setReturnValue(customCape);
        }
        // If no active QuickSkin cape, let vanilla or other mods handle it
    }
}
