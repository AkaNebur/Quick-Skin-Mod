package com.quickskin.mod.platform;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/** Minecraft 1.21.5 implementation of rendering and image compatibility operations. */
public final class MinecraftCompat1_21_5 implements MinecraftCompat {
    private static int abgrToArgb(int abgr) {
        int a = (abgr >>> 24) & 0xFF;
        int b = (abgr >>> 16) & 0xFF;
        int g = (abgr >>> 8) & 0xFF;
        int r = abgr & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    @Override
    public void setPixel(NativeImage image, int x, int y, int color) {
        image.setPixel(x, y, abgrToArgb(color));
    }

    @Override
    public int getPixel(NativeImage image, int x, int y) {
        return abgrToArgb(image.getPixel(x, y));
    }

    @Override
    public void setYoung(PlayerModel model, boolean young) {
    }

    @Override
    public void setCrouching(PlayerModel model, boolean crouching) {
    }

    @Override
    public void setRiding(PlayerModel model, boolean riding) {
    }

    @Override
    public void setAttackTime(PlayerModel model, float attackTime) {
    }

    @Override
    public void blit(GuiGraphics graphics, ResourceLocation texture, int x, int y, int blitOffset,
                     float u, float v, int width, int height, int textureWidth, int textureHeight) {
        graphics.blit(RenderType::guiTextured, texture, x, y, u, v,
                width, height, textureWidth, textureHeight);
    }

    @Override
    public void blit(GuiGraphics graphics, ResourceLocation texture, int x, int y, int width, int height,
                     float u, float v, int regionWidth, int regionHeight,
                     int textureWidth, int textureHeight) {
        graphics.blit(RenderType::guiTextured, texture, x, y, u, v,
                width, height, regionWidth, regionHeight, textureWidth, textureHeight);
    }

    @Override
    public void renderCloak(PlayerModel model, PoseStack poseStack, VertexConsumer vertexConsumer,
                            int packedLight, int packedOverlay) {
        // The cape became a separate PlayerCapeModel in 1.21.4. Preview rendering owns it directly.
    }
}
