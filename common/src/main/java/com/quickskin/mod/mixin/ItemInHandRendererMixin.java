package com.quickskin.mod.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
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
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Mixin to enable transparent arm rendering in first-person view.
 * Wraps the MultiBufferSource to return translucent buffers for skins with transparency.
 */
@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {

    /**
     * Wraps the MultiBufferSource in renderPlayerArm to return translucent buffers for transparent skins.
     */
    @ModifyVariable(
        method = "renderPlayerArm",
        at = @At("HEAD"),
        argsOnly = true,
        ordinal = 0
    )
    private MultiBufferSource quickskin$wrapBufferSourceForTransparency(MultiBufferSource original,
                                                                         PoseStack poseStack,
                                                                         MultiBufferSource multiBufferSource,
                                                                         int light,
                                                                         float equipProgress,
                                                                         float swingProgress,
                                                                         HumanoidArm arm) {
        // Check if transparency is enabled
        if (ClientConfig.getInstance().shouldDisableSkinTransparency()) {
            return original;
        }

        // Get the current player
        AbstractClientPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return original;
        }

        ResourceLocation skinTexture = player.getSkinTextureLocation();

        // Check if this skin needs translucent rendering
        boolean needsTranslucent = false;
        if (skinTexture.getNamespace().equals(QuickSkin.MOD_ID)) {
            needsTranslucent = true; // Custom QuickSkin texture
        } else {
            needsTranslucent = TextureAlphaDetector.hasTransparency(skinTexture);
        }

        if (!needsTranslucent) {
            return original; // No transparency needed
        }

        // Wrap the buffer source to return translucent buffers
        return new MultiBufferSource() {
            @Override
            public VertexConsumer getBuffer(RenderType renderType) {
                // Replace entity render types with translucent version
                String renderTypeName = renderType.toString();
                if (renderTypeName.contains("entity")) {
                    return original.getBuffer(RenderType.entityTranslucentCull(skinTexture));
                }
                return original.getBuffer(renderType);
            }
        };
    }
}
