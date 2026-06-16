package com.quickskin.mod.mixin;

import com.quickskin.mod.client.gui.util.PanoramaTimeSync;
import net.minecraft.client.renderer.Panorama;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to sync ALL panorama renderers to use the same global time.
 * This ensures seamless panorama transition between any screens.
 */
@Mixin(Panorama.class)
public class PanoramaRendererMixin {

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void quickskin$syncPanoramaTime(CallbackInfo ci) {
        // Sync this panorama's time to the global time before rendering
        PanoramaTimeSync.syncPanoramaRenderer((Panorama)(Object)this);
    }
}
