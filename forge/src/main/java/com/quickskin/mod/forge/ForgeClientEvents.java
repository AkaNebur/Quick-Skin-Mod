package com.quickskin.mod.forge;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderArmEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge-specific client event handlers
 * Handles platform-specific events like RenderArmEvent
 */
@Mod.EventBusSubscriber(modid = QuickSkin.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class ForgeClientEvents {

    /**
     * Handles first-person arm rendering to enable transparency support.
     * This event allows us to force translucent rendering for player arms.
     */
    @SubscribeEvent
    public static void onRenderArm(RenderArmEvent event) {
        // Only apply translucent rendering if transparency is enabled
        if (ClientConfig.getInstance().shouldDisableSkinTransparency()) {
            return; // Transparency is disabled, use default rendering
        }

        // Cancel the default rendering
        event.setCanceled(true);

        // Re-render the arm with translucent render type
        RenderType renderType = RenderType.entityTranslucentCull(event.getPlayer().getSkinTextureLocation());

        VertexConsumer vertexConsumer = event.getMultiBufferSource().getBuffer(renderType);

        PlayerRenderer playerRenderer = (PlayerRenderer)
            Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(event.getPlayer());

        int packedLight = event.getPackedLight();
        int overlay = OverlayTexture.NO_OVERLAY;

        // Reset arm rotations to neutral position before rendering
        // This prevents first-person arms from inheriting leftover rotation state
        ModelPart leftArm = playerRenderer.getModel().leftArm;
        ModelPart rightArm = playerRenderer.getModel().rightArm;
        ModelPart leftSleeve = playerRenderer.getModel().leftSleeve;
        ModelPart rightSleeve = playerRenderer.getModel().rightSleeve;

        leftArm.xRot = 0.0F;
        leftArm.yRot = 0.0F;
        leftArm.zRot = 0.0F;
        rightArm.xRot = 0.0F;
        rightArm.yRot = 0.0F;
        rightArm.zRot = 0.0F;
        leftSleeve.xRot = 0.0F;
        leftSleeve.yRot = 0.0F;
        leftSleeve.zRot = 0.0F;
        rightSleeve.xRot = 0.0F;
        rightSleeve.yRot = 0.0F;
        rightSleeve.zRot = 0.0F;

        // Render both the base arm and the sleeve (second layer) with proper alpha
        if (event.getArm() == net.minecraft.world.entity.HumanoidArm.LEFT) {
            // Render base arm
            leftArm.render(event.getPoseStack(), vertexConsumer, packedLight, overlay, 1.0F, 1.0F, 1.0F, 1.0F);
            // Render sleeve (second layer)
            leftSleeve.render(event.getPoseStack(), vertexConsumer, packedLight, overlay, 1.0F, 1.0F, 1.0F, 1.0F);
        } else {
            // Render base arm
            rightArm.render(event.getPoseStack(), vertexConsumer, packedLight, overlay, 1.0F, 1.0F, 1.0F, 1.0F);
            // Render sleeve (second layer)
            rightSleeve.render(event.getPoseStack(), vertexConsumer, packedLight, overlay, 1.0F, 1.0F, 1.0F, 1.0F);
        }
    }
}
