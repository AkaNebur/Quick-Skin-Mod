package com.quickskin.mod.neoforge.mixin;

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
 * NeoForge-specific mixin to enable transparent arm rendering in first-person view.
 *
 * In 26.2 the immediate MultiBufferSource was removed and AvatarRenderer.renderHand submits the arm
 * via SubmitNodeCollector.submitModelPart(...). We redirect that submit to force entityTranslucent
 * when the player's skin has transparent pixels (mirrors the common ItemInHandRendererMixin).
 */
@Mixin(value = AvatarRenderer.class, priority = 1100)
public class PlayerRendererMixin {

    @Redirect(
            method = "renderHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;Lnet/minecraft/client/model/geom/ModelPart;Z)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModelPart(Lnet/minecraft/client/model/geom/ModelPart;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IILnet/minecraft/client/renderer/texture/TextureAtlasSprite;)V"
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

        boolean needsTranslucent = TextureAlphaDetector.hasTransparency(skinTexture);

        if (needsTranslucent) {
            RenderType translucentType = RenderTypes.entityTranslucent(skinTexture);
            collector.submitModelPart(part, poseStack, translucentType, packedLight, overlay, sprite);
        } else {
            collector.submitModelPart(part, poseStack, renderType, packedLight, overlay, sprite);
        }
    }
}
