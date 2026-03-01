package com.quickskin.mod.mixin.compat;

import com.quickskin.mod.client.compat.EarsCompatIntegration;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin into Ears' EarsLayerRenderer.getEarsFeatures() for NeoForge/Forge.
 * If Ears returns DISABLED (because the texture isn't an EarsFeaturesHolder),
 * check our stored features from QuickSkin's parsed skin data.
 */
@Pseudo
@Mixin(targets = "com.unascribed.ears.EarsLayerRenderer")
public class EarsLayerRendererMixin {

    @Inject(method = "getEarsFeatures", at = @At("RETURN"), cancellable = true, remap = false)
    private static void quickskin$getEarsFeatures(AbstractClientPlayer peer, CallbackInfoReturnable<Object> cir) {
        if (EarsCompatIntegration.isDisabledResult(cir.getReturnValue())) {
            ResourceLocation skin = peer.getSkin().texture();
            Object features = EarsCompatIntegration.getFeatures(skin);
            if (features != null) {
                cir.setReturnValue(features);
            }
        }
    }
}
