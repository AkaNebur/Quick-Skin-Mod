package com.quickskin.mod.mixin;

import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

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

    @Inject(method = "getTextureLocation(Lnet/minecraft/client/renderer/entity/state/PlayerRenderState;)Lnet/minecraft/resources/ResourceLocation;",
            at = @At("HEAD"), cancellable = true)
    private void quickskin$overrideTextureLocation(PlayerRenderState renderState, CallbackInfoReturnable<ResourceLocation> cir) {
        PlayerAppearanceService service = PlayerAppearanceService.getInstance();
        if (service == null) return;

        // Look up the actual player entity from the render state to get UUID
        UUID playerUUID = null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            Entity entity = mc.level.getEntity(renderState.id);
            if (entity instanceof AbstractClientPlayer player) {
                playerUUID = player.getUUID();
            }
        }

        if (playerUUID == null) return;

        // Try service-based lookup (covers registered data from Essential compat or server sync)
        if (service.hasActiveSkin(playerUUID)) {
            ResourceLocation customSkin = service.getSkinLocation(playerUUID);
            if (customSkin != null) {
                cir.setReturnValue(customSkin);
                return;
            }
        }

        // Title screen fallback: load directly from saved config
        if (mc.level == null) {
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
