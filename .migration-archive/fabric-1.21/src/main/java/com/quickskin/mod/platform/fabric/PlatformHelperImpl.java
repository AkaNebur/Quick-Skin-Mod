package com.quickskin.mod.platform.fabric;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Path;

/**
 * Fabric implementation of PlatformHelper
 * This class provides Fabric-specific implementations for @ExpectPlatform methods
 */
@SuppressWarnings("unused")
public class PlatformHelperImpl {

    public static String getPlatformName() {
        return "Fabric";
    }

    public static Path getGameDirectory() {
        return FabricLoader.getInstance().getGameDir();
    }

    public static Path getSkinsDirectory() {
        return FabricLoader.getInstance().getGameDir().resolve("quickskin").resolve("uploads").resolve("skins");
    }

    public static Path getCapesDirectory() {
        return FabricLoader.getInstance().getGameDir().resolve("quickskin").resolve("uploads").resolve("capes");
    }

    public static Path getConfigDirectory() {
        return FabricLoader.getInstance().getConfigDir();
    }

    public static Path getCacheDirectory() {
        return FabricLoader.getInstance().getGameDir().resolve("quickskin_cache");
    }

    public static boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    public static String getModVersion() {
        return FabricLoader.getInstance()
            .getModContainer("quickskin")
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("UNKNOWN");
    }

    public static boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }


    /**
     * Sets a pixel in a NativeImage
     * For Fabric 1.21.1, directly calls setPixelRGBA with ABGR format
     * @param color Color in ABGR format (our standard format used throughout the codebase)
     */
    public static void setPixel(NativeImage image, int x, int y, int color) {
        // Fabric 1.21.1 uses setPixelRGBA with ABGR format - direct call without reflection
        image.setPixelRGBA(x, y, color);
    }

    /**
     * Gets a pixel from a NativeImage
     * For Fabric 1.21.1, directly calls getPixelRGBA with ABGR format
     * @return Color in ABGR format (our standard format used throughout the codebase)
     */
    public static int getPixel(NativeImage image, int x, int y) {
        // Fabric 1.21.1 uses getPixelRGBA with ABGR format - direct call without reflection
        return image.getPixelRGBA(x, y);
    }

    /**
     * Sets the 'young' field on a PlayerModel
     * For Fabric 1.21.1, directly sets the field
     */
    public static void setYoung(PlayerModel<?> model, boolean young) {
        // Fabric 1.21.1: Direct field access
        model.young = young;
    }

    /**
     * Sets the 'crouching' field on a PlayerModel
     * For Fabric 1.21.1, directly sets the field
     */
    public static void setCrouching(PlayerModel<?> model, boolean crouching) {
        // Fabric 1.21.1: Direct field access
        model.crouching = crouching;
    }

    /**
     * Sets the 'riding' field on a PlayerModel
     * For Fabric 1.21.1, directly sets the field
     */
    public static void setRiding(PlayerModel<?> model, boolean riding) {
        // Fabric 1.21.1: Direct field access
        model.riding = riding;
    }

    /**
     * Sets the 'attackTime' field on a PlayerModel
     * For Fabric 1.21.1, directly sets the field
     */
    public static void setAttackTime(PlayerModel<?> model, float attackTime) {
        // Fabric 1.21.1: Direct field access
        model.attackTime = attackTime;
    }

    /**
     * Blits a texture to the screen
     * For Fabric 1.21.1, directly calls the blit method
     */
    public static void blit(GuiGraphics graphics, ResourceLocation texture, int x, int y, int blitOffset, float u, float v, int width, int height, int textureWidth, int textureHeight) {
        // Fabric 1.21.1: blit(ResourceLocation, x, y, blitOffset, u, v, width, height, textureWidth, textureHeight)
        graphics.blit(texture, x, y, blitOffset, u, v, width, height, textureWidth, textureHeight);
    }

    /**
     * Blits a texture to the screen with width/height before UV coordinates
     * For Fabric 1.21.1, directly calls the blit method
     */
    public static void blit(GuiGraphics graphics, ResourceLocation texture, int x, int y, int width, int height, float u, float v, int regionWidth, int regionHeight, int textureWidth, int textureHeight) {
        // Fabric 1.21.1: blit(ResourceLocation, x, y, width, height, u, v, regionWidth, regionHeight, textureWidth, textureHeight)
        graphics.blit(texture, x, y, width, height, u, v, regionWidth, regionHeight, textureWidth, textureHeight);
    }

    /**
     * Renders a cloak on a PlayerModel
     * For Fabric 1.21.1, directly calls the renderCloak method
     */
    public static void renderCloak(PlayerModel<?> model, PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay) {
        // Fabric 1.21.1: Use the renderCloak method directly
        model.renderCloak(poseStack, vertexConsumer, packedLight, packedOverlay);
    }
}
