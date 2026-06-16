package com.quickskin.mod.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.common.util.TextureAlphaDetector;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Mixin to enable transparent arm rendering in first-person view.
 * This mixin targets AvatarRenderer, which is responsible for rendering the arm model.
 * It redirects submitModelPart calls to use translucent render types for skins with transparency.
 *
 * In MC 1.21.9, the vanilla renderHand already uses entityTranslucent by default,
 * so this mixin is mostly a safety net and ensures correct behavior when other mods
 * might change the render type.
 */
@Mixin(value = AvatarRenderer.class, priority = 1100)
public class ItemInHandRendererMixin {

    /**
     * Redirects the submitModelPart call within AvatarRenderer's private renderHand method.
     * This allows us to ensure entityTranslucent is used when the player's skin has
     * transparent pixels.
     */
    @Redirect(
            method = "renderHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;Lnet/minecraft/client/model/geom/ModelPart;Z)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModelPart(Lnet/minecraft/client/model/geom/ModelPart;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/RenderType;IILnet/minecraft/client/renderer/texture/TextureAtlasSprite;)V"
            )
    )
    private void quickskin$redirectSubmitModelPart(SubmitNodeCollector collector, ModelPart part,
                                                    PoseStack poseStack, RenderType renderType,
                                                    int packedLight, int overlay, TextureAtlasSprite sprite,
                                                    // Injected arguments from renderHand:
                                                    PoseStack poseStackOuter, SubmitNodeCollector bufferOuter,
                                                    int packedLightOuter, Identifier skinTexture,
                                                    ModelPart arm, boolean slim) {
        if (CPMCompatIntegration.shouldDeferToCPM()) {
            collector.submitModelPart(part, poseStack, renderType, packedLight, overlay, sprite);
            return;
        }

        if (CPMCompatIntegration.isCPMActivelyRendering()) {
            collector.submitModelPart(part, poseStack, renderType, packedLight, overlay, sprite);
            return;
        }

        if (ClientConfig.getInstance().shouldDisableSkinTransparency()) {
            collector.submitModelPart(part, poseStack, renderType, packedLight, overlay, sprite);
            return;
        }

        if (skinTexture == null) {
            collector.submitModelPart(part, poseStack, renderType, packedLight, overlay, sprite);
            return;
        }

        // Determine if the skin needs a translucent render type
        boolean needsTranslucent = TextureAlphaDetector.hasTransparency(skinTexture);

        if (needsTranslucent) {
            RenderType translucentType = RenderTypes.entityTranslucent(skinTexture);
            collector.submitModelPart(part, poseStack, translucentType, packedLight, overlay, sprite);
        } else {
            collector.submitModelPart(part, poseStack, renderType, packedLight, overlay, sprite);
        }
    }
}
