package com.quickskin.mod.mixin;

import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin on PlayerRenderer to intercept skin texture lookups at the renderer level.
 *
 * This is needed because some mods (e.g. Essential) create AbstractClientPlayer subclasses
 * that override getSkin() without calling super. The mixin on AbstractClientPlayer.getSkin()
 * doesn't fire for those subclasses, but getTextureLocation() on the renderer is always
 * called regardless of the entity's class hierarchy.
 */
@Mixin(PlayerRenderer.class)
public class PlayerRendererMixin {

    @Inject(method = "getTextureLocation(Lnet/minecraft/client/player/AbstractClientPlayer;)Lnet/minecraft/resources/ResourceLocation;",
            at = @At("HEAD"), cancellable = true)
    private void quickskin$overrideTextureLocation(AbstractClientPlayer player, CallbackInfoReturnable<ResourceLocation> cir) {
        PlayerAppearanceService service = PlayerAppearanceService.getInstance();
        if (service == null) return;

        // Try service-based lookup (covers registered data from Essential compat or server sync)
        if (service.hasActiveSkin(player.getUUID())) {
            ResourceLocation customSkin = service.getSkinLocation(player.getUUID());
            if (customSkin != null) {
                cir.setReturnValue(customSkin);
                return;
            }
        }

        // Title screen fallback: load directly from saved config
        if (Minecraft.getInstance().level == null) {
            ClientConfig config = ClientConfig.getInstance();
            if (!config.activeSkinHash.isEmpty()) {
                ResourceLocation loc = LocalAssetManager.getInstance()
                        .getTextureLocation(config.activeSkinHash, TextureQuality.FULL);
                if (loc != null) {
                    cir.setReturnValue(loc);
                    return;
                }
            }
        }
    }
}
