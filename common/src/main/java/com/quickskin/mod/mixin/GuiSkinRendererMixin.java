package com.quickskin.mod.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.quickskin.mod.client.rendering.PlayerModelRenderer;
import com.quickskin.mod.client.rendering.SkinLayers3DIntegration;
import net.minecraft.client.gui.render.pip.GuiSkinRenderer;
import net.minecraft.client.renderer.state.gui.pip.GuiSkinRenderState;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to inject cape rendering into the PiP (Picture-in-Picture) skin rendering system.
 *
 * In 26.2, the PiP renderToTexture pipeline was migrated off the immediate MultiBufferSource
 * (which was removed) onto the deferred {@link SubmitNodeCollector}. The method signature gained
 * a trailing SubmitNodeCollector parameter:
 *   renderToTexture(GuiSkinRenderState, PoseStack, SubmitNodeCollector)
 * We inject at TAIL (after the body parts have been submitted) and submit the cape part to the
 * same collector, mirroring how CapeLayerMixin submits the cape model in-world.
 */
@Mixin(GuiSkinRenderer.class)
public class GuiSkinRendererMixin {

    @Inject(
            method = "renderToTexture(Lnet/minecraft/client/renderer/state/gui/pip/GuiSkinRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V",
            at = @At("HEAD")
    )
    private void quickskin$attachSkinLayersMeshes(GuiSkinRenderState state, PoseStack poseStack,
                                                   SubmitNodeCollector collector, CallbackInfo ci) {
        var root = state.playerModel().root();
        Boolean thinArms = PlayerModelRenderer.getQuickSkinPreviewThinArms(root);
        if (thinArms != null) {
            SkinLayers3DIntegration.attachDeferredMeshes(root, state.texture(), thinArms);
        }
    }

    @Inject(
            method = "renderToTexture(Lnet/minecraft/client/renderer/state/gui/pip/GuiSkinRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V",
            at = @At("TAIL")
    )
    private void quickskin$renderCapeInPiP(GuiSkinRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CallbackInfo ci) {
        PlayerModelRenderer.PreviewCapeState cape =
                PlayerModelRenderer.consumePendingCape(state.playerModel());
        if (cape == null) {
            return;
        }

        RenderType capeRenderType = RenderTypes.entityTranslucent(cape.texture());

        poseStack.pushPose();
        cape.bodyModel().body.translateAndRotate(poseStack);
        poseStack.translate(0.0, 0.0, 0.125);
        poseStack.mulPose(Axis.XP.rotationDegrees(6.0F));
        // 26.2: submit the cape part to the deferred collector instead of writing to a VertexConsumer.
        collector.submitModelPart(cape.capeModel().body.getChild("cape"), poseStack, capeRenderType,
                15728880, OverlayTexture.NO_OVERLAY, null);
        poseStack.popPose();
    }
}
