package com.quickskin.mod.platform;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
//? if <26.2 {
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.resources.Identifier;
//?}

/**
 * Minecraft-version seam for rendering and image APIs. Loader services remain in PlatformHelper.
 */
public interface MinecraftCompat {
    //? if <26.2 {
    MinecraftCompat INSTANCE = new MinecraftCompat1_20_1();
    //?} else {
    MinecraftCompat INSTANCE = new MinecraftCompat26_2();
    //?}

    void setPixel(NativeImage image, int x, int y, int color);

    int getPixel(NativeImage image, int x, int y);
    //? if <26.2 {
    void setYoung(PlayerModel<?> model, boolean young);
    void setCrouching(PlayerModel<?> model, boolean crouching);
    void setRiding(PlayerModel<?> model, boolean riding);
    void setAttackTime(PlayerModel<?> model, float attackTime);
    //?}

    //? if <26.2 {
    void blit(GuiGraphics graphics, ResourceLocation texture, int x, int y, int blitOffset,
              float u, float v, int width, int height, int textureWidth, int textureHeight);
    //?} else {
    void setYoung(PlayerModel model, boolean young);
    //?}

    //? if <26.2 {
    void blit(GuiGraphics graphics, ResourceLocation texture, int x, int y, int width, int height,
              float u, float v, int regionWidth, int regionHeight, int textureWidth, int textureHeight);
    //?} else {
    void setCrouching(PlayerModel model, boolean crouching);
    //?}

    //? if <26.2 {
    void renderCloak(PlayerModel<?> model, PoseStack poseStack, VertexConsumer vertexConsumer,
                     int packedLight, int packedOverlay);
    //?} else {
    void setRiding(PlayerModel model, boolean riding);

    void setAttackTime(PlayerModel model, float attackTime);

    void blit(
            GuiGraphicsExtractor graphics,
            Identifier texture,
            int x,
            int y,
            int blitOffset,
            float u,
            float v,
            int width,
            int height,
            int textureWidth,
            int textureHeight
    );

    void blit(
            GuiGraphicsExtractor graphics,
            Identifier texture,
            int x,
            int y,
            int width,
            int height,
            float u,
            float v,
            int regionWidth,
            int regionHeight,
            int textureWidth,
            int textureHeight
    );

    void renderCloak(
            PlayerModel model,
            PoseStack poseStack,
            VertexConsumer vertexConsumer,
            int packedLight,
            int packedOverlay
    );
    //?}
}
