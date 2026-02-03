package com.quickskin.mod.mixin;

import com.quickskin.mod.client.gui.util.PanoramaTimeSync;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to sync TitleScreen's panorama time with a global time source.
 * This ensures seamless panorama transition between TitleScreen and QuickSkin screens.
 */
@Mixin(TitleScreen.class)
public class TitleScreenMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void quickskin$syncPanoramaTime(CallbackInfo ci) {
        // Sync panorama time before rendering to ensure consistent position
        PanoramaTimeSync.syncTitleScreenPanorama();
    }
}
