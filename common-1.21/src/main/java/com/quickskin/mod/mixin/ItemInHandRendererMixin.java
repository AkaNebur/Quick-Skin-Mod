package com.quickskin.mod.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.common.util.TextureAlphaDetector;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Mixin to enable transparent arm rendering in first-person view.
 * Redirects getBuffer calls to return translucent buffers for skins with transparency.
 *
 * Uses @Redirect which is more reliable cross-platform than @ModifyVariable or @ModifyArg.
 */
@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {

    /**
     * Redirects all getBuffer calls within renderPlayerArm to potentially return
     * translucent render types for transparent skins.
     */
    @Redirect(
        method = "renderPlayerArm",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/MultiBufferSource;getBuffer(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"
        )
    )
    private VertexConsumer quickskin$redirectGetBuffer(MultiBufferSource multiBufferSource, RenderType renderType) {
        // Check if transparency is enabled
        if (ClientConfig.getInstance().shouldDisableSkinTransparency()) {
            return multiBufferSource.getBuffer(renderType);
        }

        // Get the current player
        AbstractClientPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return multiBufferSource.getBuffer(renderType);
        }

        ResourceLocation skinTexture = player.getSkin().texture();

        // Check if this skin needs translucent rendering
        boolean needsTranslucent = false;
        if (skinTexture.getNamespace().equals(QuickSkin.MOD_ID)) {
            needsTranslucent = true; // Custom QuickSkin texture
        } else {
            needsTranslucent = TextureAlphaDetector.hasTransparency(skinTexture);
        }

        if (!needsTranslucent) {
            return multiBufferSource.getBuffer(renderType);
        }

        // Replace entity render types with translucent version
        String renderTypeName = renderType.toString();
        if (renderTypeName.contains("entity")) {
            return multiBufferSource.getBuffer(RenderType.entityTranslucentCull(skinTexture));
        }

        return multiBufferSource.getBuffer(renderType);
    }
}
