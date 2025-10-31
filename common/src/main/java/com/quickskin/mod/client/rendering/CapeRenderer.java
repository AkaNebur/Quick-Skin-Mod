package com.quickskin.mod.client.rendering;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/**
 * Utility for rendering capes in GUI
 * Handles both static and animated capes
 */
@Environment(EnvType.CLIENT)
public class CapeRenderer {

    /**
     * Render a cape on the player model
     *
     * @param poseStack The pose stack (should be in player model space)
     * @param buffer The buffer source
     * @param capeLocation The cape texture location
     * @param yRotation Player Y rotation (for cape physics)
     */
    public static void renderCape(
            PoseStack poseStack,
            MultiBufferSource buffer,
            ResourceLocation capeLocation,
            float yRotation
    ) {
        if (capeLocation == null) {
            return;
        }

        poseStack.pushPose();

        // Position cape behind player
        // Cape attaches at neck/shoulder area
        poseStack.translate(0.0, 0.0, 0.125); // Slightly back from body center

        // Rotate cape based on player rotation (for wind effect)
        // Capes naturally hang and sway
        float capeSwing = (float) Math.sin(System.currentTimeMillis() / 1000.0) * 2.0f;
        poseStack.mulPose(Axis.XP.rotationDegrees(6.0f + capeSwing));

        // Get render type
        RenderType renderType = RenderType.entityTranslucentCull(capeLocation);
        VertexConsumer vertexConsumer = buffer.getBuffer(renderType);

        // Render cape mesh
        // Standard cape is 10x16 in Minecraft (64x32 texture)
        // UV mapping: (0,0) to (10,16) on a 64x32 texture
        renderCapeQuad(poseStack, vertexConsumer);

        poseStack.popPose();
    }

    /**
     * Render the cape mesh as a quad
     * Cape hangs down from shoulders
     */
    private static void renderCapeQuad(PoseStack poseStack, VertexConsumer consumer) {
        // Cape dimensions (in model units)
        float capeWidth = 10.0f / 16.0f;  // 10 pixels = 0.625 model units
        float capeHeight = 16.0f / 16.0f; // 16 pixels = 1.0 model units

        // Center the cape
        float xOffset = -capeWidth / 2.0f;

        // Get matrices
        PoseStack.Pose pose = poseStack.last();
        Matrix4f positionMatrix = pose.pose();

        // UV coordinates for standard cape (64x32 texture)
        // Cape texture is in the rectangle (0,0) to (10,16) on the texture
        float u0 = 0.0f / 64.0f;
        float v0 = 0.0f / 32.0f;
        float u1 = 10.0f / 64.0f;
        float v1 = 16.0f / 32.0f;

        // Render cape as a simple quad (one-sided for performance)
        // Top-left
        consumer.vertex(positionMatrix, xOffset, 0.0f, 0.0f)
                .color(255, 255, 255, 255)
                .uv(u0, v0)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880) // Full bright
                .normal(0.0f, 0.0f, 1.0f)
                .endVertex();

        // Bottom-left
        consumer.vertex(positionMatrix, xOffset, capeHeight, 0.0f)
                .color(255, 255, 255, 255)
                .uv(u0, v1)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(0.0f, 0.0f, 1.0f)
                .endVertex();

        // Bottom-right
        consumer.vertex(positionMatrix, xOffset + capeWidth, capeHeight, 0.0f)
                .color(255, 255, 255, 255)
                .uv(u1, v1)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(0.0f, 0.0f, 1.0f)
                .endVertex();

        // Top-right
        consumer.vertex(positionMatrix, xOffset + capeWidth, 0.0f, 0.0f)
                .color(255, 255, 255, 255)
                .uv(u1, v0)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(0.0f, 0.0f, 1.0f)
                .endVertex();
    }

    /**
     * Render animated cape (for GIF capes)
     * Uses specific frame from texture atlas
     *
     * @param frameIndex Current animation frame
     * @param frameCount Total number of frames
     */
    public static void renderAnimatedCape(
            PoseStack poseStack,
            MultiBufferSource buffer,
            ResourceLocation capeLocation,
            float yRotation,
            int frameIndex,
            int frameCount
    ) {
        if (capeLocation == null || frameCount <= 0) {
            return;
        }

        poseStack.pushPose();

        // Same positioning as static cape
        poseStack.translate(0.0, 0.0, 0.125);
        float capeSwing = (float) Math.sin(System.currentTimeMillis() / 1000.0) * 2.0f;
        poseStack.mulPose(Axis.XP.rotationDegrees(6.0f + capeSwing));

        RenderType renderType = RenderType.entityTranslucentCull(capeLocation);
        VertexConsumer vertexConsumer = buffer.getBuffer(renderType);

        // Render with frame-specific UVs
        renderAnimatedCapeQuad(poseStack, vertexConsumer, frameIndex, frameCount);

        poseStack.popPose();
    }

    /**
     * Render animated cape quad with frame-specific UV mapping
     * Assumes frames are stacked vertically in texture atlas
     */
    private static void renderAnimatedCapeQuad(
            PoseStack poseStack,
            VertexConsumer consumer,
            int frameIndex,
            int frameCount
    ) {
        float capeWidth = 10.0f / 16.0f;
        float capeHeight = 16.0f / 16.0f;
        float xOffset = -capeWidth / 2.0f;

        PoseStack.Pose pose = poseStack.last();
        Matrix4f positionMatrix = pose.pose();

        // Calculate UV for specific frame
        // Frames are stacked vertically, so texture height = 32 * frameCount
        float u0 = 0.0f / 64.0f;
        float u1 = 10.0f / 64.0f;

        float textureHeight = 32.0f * frameCount;
        float frameStart = 32.0f * frameIndex;
        float v0 = frameStart / textureHeight;
        float v1 = (frameStart + 16.0f) / textureHeight;

        // Render quad
        consumer.vertex(positionMatrix, xOffset, 0.0f, 0.0f)
                .color(255, 255, 255, 255)
                .uv(u0, v0)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(0.0f, 0.0f, 1.0f)
                .endVertex();

        consumer.vertex(positionMatrix, xOffset, capeHeight, 0.0f)
                .color(255, 255, 255, 255)
                .uv(u0, v1)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(0.0f, 0.0f, 1.0f)
                .endVertex();

        consumer.vertex(positionMatrix, xOffset + capeWidth, capeHeight, 0.0f)
                .color(255, 255, 255, 255)
                .uv(u1, v1)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(0.0f, 0.0f, 1.0f)
                .endVertex();

        consumer.vertex(positionMatrix, xOffset + capeWidth, 0.0f, 0.0f)
                .color(255, 255, 255, 255)
                .uv(u1, v0)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(0.0f, 0.0f, 1.0f)
                .endVertex();
    }
}
