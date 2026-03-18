package com.quickskin.mod.mixin;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.client.services.AnimatedTextureManager;
import com.quickskin.mod.client.services.CapeAnimationHelper;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.util.TextureAlphaDetector;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import net.minecraft.client.model.HumanoidModel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(value = CapeLayer.class, priority = 1100) // Higher priority to override TLSkinCape and other mods
public class CapeLayerMixin {

    // In MC 1.21.4+, CapeLayer has its own cape model (PlayerCapeModel) separate from PlayerModel
    @Shadow @Final private HumanoidModel<?> model;

    @Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V",
            at = @At("HEAD"),
            cancellable = true)
    private void quickskin$renderCustomCape(PoseStack poseStack, SubmitNodeCollector buffer, int packedLight,
                                            AvatarRenderState renderState, float yRot, float xRot,
                                            CallbackInfo ci) {
        if (CPMCompatIntegration.shouldDeferToCPM()) return;

        // Look up the actual player entity from the render state to get UUID
        UUID playerUUID = null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            Entity entity = mc.level.getEntity(renderState.id);
            if (entity instanceof AbstractClientPlayer player) {
                playerUUID = player.getUUID();
                // Don't render cape when elytra is equipped
                if (player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
                    ci.cancel();
                    return;
                }
            }
        }

        if (playerUUID == null) {
            return; // Can't identify player, let vanilla logic run
        }

        PlayerAppearanceService service = PlayerAppearanceService.getInstance();

        if (!service.hasActiveCape(playerUUID)) {
            return; // No custom cape, let vanilla logic run
        }

        // Get cape texture from our service instead of renderState.skin cape,
        // because some mods (e.g. Essential) override getSkin() in a subclass,
        // bypassing our MixinAbstractClientPlayer that sets the correct cape texture.
        Identifier capeTexture = service.getCapeLocation(playerUUID);
        if (capeTexture == null) {
            // Fallback to config-based lookup for title screen
            if (mc.level == null) {
                ClientConfig config = ClientConfig.getInstance();
                if (!config.activeCapeHash.isEmpty()) {
                    capeTexture = com.quickskin.mod.client.services.CapeService.getInstance()
                            .getCapeLocation(null, config.activeCapeHash);
                }
            }
        }
        if (capeTexture == null) {
            // Fall back to render state's skin cape
            if (renderState.skin != null && renderState.skin.cape() != null) {
                capeTexture = renderState.skin.cape().texturePath();
            }
        }

        if (capeTexture == null) {
            ci.cancel(); // Don't render anything if QuickSkin wants to hide the cape
            return;
        }

        // Check if this cape is animated. If so, get the current frame texture.
        // Use animation ID lookup (more reliable) instead of atlas location lookup
        Identifier finalTexture = capeTexture;
        String capeId = service.getCapeId(playerUUID);

        if (capeId != null && !capeId.isEmpty()) {
            String animationId = CapeAnimationHelper.deriveAnimationId(capeId);

            if (animationId != null) {
                Identifier currentFrame = AnimatedTextureManager.getInstance().getCurrentFrameTexture(animationId);
                if (currentFrame != null) {
                    finalTexture = currentFrame;
                }
            }
        } else {
            // Fallback to atlas location lookup (for non-QuickSkin capes that might be animated)
            java.util.Optional<Identifier> animFrame = AnimatedTextureManager.getInstance()
                    .getAnimationFrame(capeTexture);
            finalTexture = animFrame.orElse(capeTexture);
        }

        RenderType renderType;

        // If the texture is from our mod (local, network, animated, or known),
        // always use the translucent render type to correctly handle transparency.
        if (finalTexture.getNamespace().equals(QuickSkin.MOD_ID)) {
            renderType = RenderTypes.entityTranslucent(finalTexture);
        } else {
            // For vanilla capes or capes from other mods, use the alpha detector.
            boolean hasTransparency = TextureAlphaDetector.hasTransparency(finalTexture);
            if (hasTransparency) {
                renderType = RenderTypes.entityTranslucent(finalTexture);
            } else {
                renderType = RenderTypes.entitySolid(finalTexture);
            }
        }

        // Replicate the vanilla cape rendering logic with our custom render type
        // In MC 1.21.9, CapeLayer uses SubmitNodeCollector.submitModel() instead of renderToBuffer()
        @SuppressWarnings("unchecked")
        HumanoidModel<AvatarRenderState> capeModel = (HumanoidModel<AvatarRenderState>) (HumanoidModel<?>) this.model;
        capeModel.setupAnim(renderState);

        // Submit the cape model with our custom render type
        buffer.submitModel(capeModel, renderState, poseStack, renderType, packedLight,
                OverlayTexture.NO_OVERLAY, renderState.outlineColor, null);

        // Cancel the original vanilla method to prevent it from rendering a second time
        ci.cancel();
    }
}
