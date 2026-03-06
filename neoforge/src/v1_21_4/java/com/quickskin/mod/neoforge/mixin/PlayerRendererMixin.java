package com.quickskin.mod.neoforge.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.common.util.TextureAlphaDetector;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * NeoForge-specific mixin to enable transparent arm rendering in first-person view.
 * This mixin targets PlayerRenderer, which is responsible for rendering the arm model.
 * It redirects getBuffer calls to return translucent buffers for skins with transparency.
 */
@Mixin(value = PlayerRenderer.class, priority = 1100)
public class PlayerRendererMixin {

    /**
     * Redirects the getBuffer call within PlayerRenderer's renderHand method.
     * This allows us to switch from RenderType.entitySolid to RenderType.entityTranslucent
     * when the player's skin has transparent pixels.
     */
    @Redirect(
            method = "renderHand",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/MultiBufferSource;getBuffer(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"
            )
    )
    private VertexConsumer quickskin$redirectRenderHandBuffer(MultiBufferSource instance, RenderType renderType,
                                                              PoseStack poseStack, MultiBufferSource buffer, int packedLight, ResourceLocation skinTexture, ModelPart arm, boolean isSleeve) {
        if (CPMCompatIntegration.shouldDeferToCPM()) return instance.getBuffer(renderType);
        if (CPMCompatIntegration.isCPMActivelyRendering()) return instance.getBuffer(renderType);

        // Check if transparency is disabled globally by config
        if (ClientConfig.getInstance().shouldDisableSkinTransparency()) {
            return instance.getBuffer(renderType);
        }

        if (skinTexture == null) {
            return instance.getBuffer(renderType);
        }

        // Determine if the skin needs a translucent render type
        boolean needsTranslucent = TextureAlphaDetector.hasTransparency(skinTexture);

        if (needsTranslucent) {
            // The vanilla method calls getBuffer for both the solid arm and the translucent sleeve.
            // By forcing entityTranslucent here, we correctly render the arm with transparency.
            return instance.getBuffer(RenderType.entityTranslucent(skinTexture));
        }

        // If no transparency is needed, use the original render type provided by the vanilla method.
        return instance.getBuffer(renderType);
    }
}
