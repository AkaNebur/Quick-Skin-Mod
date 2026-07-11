package com.quickskin.mod.platform;

import dev.architectury.injectables.annotations.ExpectPlatform;
//? if >=1.21 {
    //? if <1.21.4 {
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.resources.ResourceLocation;
    //?}
//?}

import java.nio.file.Path;

/**
 * Loader-service abstraction for paths, loader metadata, and environment queries.
 */
public class PlatformHelper {
    @ExpectPlatform
    public static String getPlatformName() {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static Path getGameDirectory() {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static Path getSkinsDirectory() {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static Path getCapesDirectory() {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static Path getConfigDirectory() {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static Path getCacheDirectory() {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static boolean isModLoaded(String modId) {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static String getModVersion() {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static boolean isDevelopmentEnvironment() {
        throw new AssertionError();
    }

    //? if >=1.21 {
        //? if <1.21.4 {
    public static void setPixel(NativeImage image, int x, int y, int color) {
        MinecraftCompat.INSTANCE.setPixel(image, x, y, color);
    }

    public static int getPixel(NativeImage image, int x, int y) {
        return MinecraftCompat.INSTANCE.getPixel(image, x, y);
    }

    public static void setYoung(PlayerModel<?> model, boolean young) {
        MinecraftCompat.INSTANCE.setYoung(model, young);
    }

    public static void setCrouching(PlayerModel<?> model, boolean crouching) {
        MinecraftCompat.INSTANCE.setCrouching(model, crouching);
    }

    public static void setRiding(PlayerModel<?> model, boolean riding) {
        MinecraftCompat.INSTANCE.setRiding(model, riding);
    }

    public static void setAttackTime(PlayerModel<?> model, float attackTime) {
        MinecraftCompat.INSTANCE.setAttackTime(model, attackTime);
    }

    public static void blit(GuiGraphics graphics, ResourceLocation texture, int x, int y, int blitOffset,
                            float u, float v, int width, int height,
                            int textureWidth, int textureHeight) {
        MinecraftCompat.INSTANCE.blit(graphics, texture, x, y, blitOffset,
                u, v, width, height, textureWidth, textureHeight);
    }

    public static void blit(GuiGraphics graphics, ResourceLocation texture, int x, int y,
                            int width, int height, float u, float v,
                            int regionWidth, int regionHeight, int textureWidth, int textureHeight) {
        MinecraftCompat.INSTANCE.blit(graphics, texture, x, y, width, height,
                u, v, regionWidth, regionHeight, textureWidth, textureHeight);
    }

    public static void renderCloak(PlayerModel<?> model, PoseStack poseStack,
                                   VertexConsumer vertexConsumer, int packedLight, int packedOverlay) {
        MinecraftCompat.INSTANCE.renderCloak(
                model, poseStack, vertexConsumer, packedLight, packedOverlay);
    }
        //?}
    //?}
}
