package com.quickskin.mod.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.quickskin.mod.client.rendering.PlayerModelRenderer;
import com.quickskin.mod.client.rendering.SkinLayers3DIntegration;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.GuiSkinRenderer;
import net.minecraft.client.gui.render.state.pip.GuiSkinRenderState;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to inject cape rendering into the PiP (Picture-in-Picture) skin rendering system.
 * In 1.21.11+, all GUI 3D rendering goes through the PiP system. Model.renderToBuffer() is final,
 * so we inject into GuiSkinRenderer.renderToTexture() to render the cape after the body
 * but before bufferSource.endBatch(), within the same off-screen texture context.
 */
@Mixin(GuiSkinRenderer.class)
public class GuiSkinRendererMixin {

    @Inject(
            method = "renderToTexture(Lnet/minecraft/client/gui/render/state/pip/GuiSkinRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch()V"
            )
    )
    private void quickskin$renderCapeInPiP(GuiSkinRenderState state, PoseStack poseStack, CallbackInfo ci) {
        // Use the shared buffer source (same instance used by the PiP system).
        MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
        Boolean thinArms = PlayerModelRenderer.getQuickSkinPreviewThinArms(state.playerModel());
        if (thinArms != null) {
            SkinLayers3DIntegration.render3DLayers(poseStack, bufferSource, 15728880,
                    OverlayTexture.NO_OVERLAY, state.playerModel(), state.texture(), thinArms);
        }

        PlayerModelRenderer.PreviewCapeState cape =
                PlayerModelRenderer.consumePendingCape(
                        state.playerModel(), state.texture(), state.rotationX(), state.rotationY(),
                        state.pivotY(), state.x0(), state.y0(), state.x1(), state.y1(), state.scale());
        if (cape == null || cape.texture() == null
                || cape.bodyModel() == null || cape.capeModel() == null) {
            return;
        }

        RenderType capeRenderType = RenderTypes.entityTranslucent(cape.texture());
        VertexConsumer capeConsumer = bufferSource.getBuffer(capeRenderType);

        poseStack.pushPose();
        cape.bodyModel().body.translateAndRotate(poseStack);
        poseStack.translate(0.0, 0.0, 0.125);
        poseStack.mulPose(Axis.XP.rotationDegrees(6.0F));
        cape.capeModel().body.getChild("cape").render(
                poseStack, capeConsumer, 15728880, OverlayTexture.NO_OVERLAY);
        poseStack.popPose();
    }
}
