package com.quickskin.mod.platform;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.resources.ResourceLocation;

/** Minecraft-version seam for rendering and image APIs. */
public interface MinecraftCompat {
    MinecraftCompat INSTANCE = new MinecraftCompat1_20_1();

    void setPixel(NativeImage image, int x, int y, int color);
    int getPixel(NativeImage image, int x, int y);
    void setYoung(PlayerModel<?> model, boolean young);
    void setCrouching(PlayerModel<?> model, boolean crouching);
    void setRiding(PlayerModel<?> model, boolean riding);
    void setAttackTime(PlayerModel<?> model, float attackTime);

    void blit(GuiGraphics graphics, ResourceLocation texture, int x, int y, int blitOffset,
              float u, float v, int width, int height, int textureWidth, int textureHeight);

    void blit(GuiGraphics graphics, ResourceLocation texture, int x, int y, int width, int height,
              float u, float v, int regionWidth, int regionHeight, int textureWidth, int textureHeight);

    void renderCloak(PlayerModel<?> model, PoseStack poseStack, VertexConsumer vertexConsumer,
                     int packedLight, int packedOverlay);
}
