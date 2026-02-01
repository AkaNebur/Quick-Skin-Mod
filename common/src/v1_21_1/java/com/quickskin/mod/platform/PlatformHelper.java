package com.quickskin.mod.platform;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.resources.ResourceLocation;
import java.nio.file.Path;

/**
 * Platform abstraction layer for QuickSkin
 * Methods here are implemented in platform-specific modules (forge/fabric)
 */
public class PlatformHelper {

    /**
     * Gets the platform name (e.g., "Forge", "Fabric")
     */
    @ExpectPlatform
    public static String getPlatformName() {
        throw new AssertionError();
    }

    /**
     * Gets the game directory (where Minecraft is installed)
     */
    @ExpectPlatform
    public static Path getGameDirectory() {
        throw new AssertionError();
    }

    /**
     * Gets the directory where custom skins should be stored
     * Default: <game_dir>/quickskin/uploads/skins
     */
    @ExpectPlatform
    public static Path getSkinsDirectory() {
        throw new AssertionError();
    }

    /**
     * Gets the directory where custom capes should be stored
     * Default: <game_dir>/quickskin/uploads/capes
     */
    @ExpectPlatform
    public static Path getCapesDirectory() {
        throw new AssertionError();
    }

    /**
     * Gets the config directory
     * Forge: <game_dir>/config
     * Fabric: <game_dir>/config
     */
    @ExpectPlatform
    public static Path getConfigDirectory() {
        throw new AssertionError();
    }

    /**
     * Gets the cache directory for processed textures
     * Default: <game_dir>/quickskin_cache
     */
    @ExpectPlatform
    public static Path getCacheDirectory() {
        throw new AssertionError();
    }

    /**
     * Checks if a mod is loaded
     * @param modId The mod ID to check
     * @return true if the mod is loaded
     */
    @ExpectPlatform
    public static boolean isModLoaded(String modId) {
        throw new AssertionError();
    }

    /**
     * Gets the mod version
     */
    @ExpectPlatform
    public static String getModVersion() {
        throw new AssertionError();
    }

    /**
     * Checks if running in a development environment
     */
    @ExpectPlatform
    public static boolean isDevelopmentEnvironment() {
        throw new AssertionError();
    }

    /**
     * Sets a pixel in a NativeImage
     * Abstracts the difference between MC 1.21.1 (setPixelRGBA) and 1.21.2+ (setPixel)
     * @param image The NativeImage
     * @param x X coordinate
     * @param y Y coordinate
     * @param color The color in ABGR format
     */
    @ExpectPlatform
    public static void setPixel(NativeImage image, int x, int y, int color) {
        throw new AssertionError();
    }

    /**
     * Gets a pixel from a NativeImage
     * Abstracts the difference between MC 1.21.1 (getPixelRGBA) and 1.21.2+ (getPixel)
     * @param image The NativeImage
     * @param x X coordinate
     * @param y Y coordinate
     * @return The color in ABGR format
     */
    @ExpectPlatform
    public static int getPixel(NativeImage image, int x, int y) {
        throw new AssertionError();
    }

    /**
     * Sets the 'young' field on a PlayerModel
     * Abstracts the difference between MC 1.21.1 (has 'young' field) and 1.21.2+ (field removed)
     * @param model The PlayerModel
     * @param young Whether the model should be young (baby)
     */
    @ExpectPlatform
    public static void setYoung(PlayerModel<?> model, boolean young) {
        throw new AssertionError();
    }

    /**
     * Sets the 'crouching' field on a PlayerModel
     * Abstracts the difference between MC 1.21.1 (has 'crouching' field) and 1.21.2+ (field removed)
     * @param model The PlayerModel
     * @param crouching Whether the model should be crouching
     */
    @ExpectPlatform
    public static void setCrouching(PlayerModel<?> model, boolean crouching) {
        throw new AssertionError();
    }

    /**
     * Sets the 'riding' field on a PlayerModel
     * Abstracts the difference between MC 1.21.1 (has 'riding' field) and 1.21.2+ (field removed)
     * @param model The PlayerModel
     * @param riding Whether the model should be riding
     */
    @ExpectPlatform
    public static void setRiding(PlayerModel<?> model, boolean riding) {
        throw new AssertionError();
    }

    /**
     * Sets the 'attackTime' field on a PlayerModel
     * Abstracts the difference between MC 1.21.1 (has 'attackTime' field) and 1.21.2+ (field removed)
     * @param model The PlayerModel
     * @param attackTime The attack time value
     */
    @ExpectPlatform
    public static void setAttackTime(PlayerModel<?> model, float attackTime) {
        throw new AssertionError();
    }

    /**
     * Blits a texture to the screen
     * Abstracts the difference between MC 1.21.1 (has blitOffset parameter) and 1.21.2+ (removed blitOffset)
     * @param graphics The GuiGraphics
     * @param texture The texture resource location
     * @param x Screen X position
     * @param y Screen Y position
     * @param blitOffset Z-offset (ignored in 1.21.2+)
     * @param u Texture U coordinate
     * @param v Texture V coordinate
     * @param width Width to render
     * @param height Height to render
     * @param textureWidth Total texture width
     * @param textureHeight Total texture height
     */
    @ExpectPlatform
    public static void blit(GuiGraphics graphics, ResourceLocation texture, int x, int y, int blitOffset, float u, float v, int width, int height, int textureWidth, int textureHeight) {
        throw new AssertionError();
    }

    /**
     * Blits a texture to the screen with integer UV coordinates
     * This overload uses integer UV coordinates and renders with specified width/height
     * Compatible with both MC 1.21.1 (no Function parameter) and 1.21.2+ (requires Function parameter)
     * @param graphics The GuiGraphics
     * @param texture The texture resource location
     * @param x Screen X position
     * @param y Screen Y position
     * @param width Width to render on screen
     * @param height Height to render on screen
     * @param u Texture U coordinate (float)
     * @param v Texture V coordinate (float)
     * @param regionWidth Width of texture region to sample
     * @param regionHeight Height of texture region to sample
     * @param textureWidth Total texture width
     * @param textureHeight Total texture height
     */
    @ExpectPlatform
    public static void blit(GuiGraphics graphics, ResourceLocation texture, int x, int y, int width, int height, float u, float v, int regionWidth, int regionHeight, int textureWidth, int textureHeight) {
        throw new AssertionError();
    }

    /**
     * Renders a cloak on a PlayerModel
     * Abstracts the difference between MC 1.21.1 and 1.21.2+ renderCloak method signatures
     * @param model The PlayerModel
     * @param poseStack The PoseStack
     * @param vertexConsumer The VertexConsumer
     * @param packedLight Packed light value
     * @param packedOverlay Packed overlay value
     */
    @ExpectPlatform
    public static void renderCloak(PlayerModel<?> model, PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay) {
        throw new AssertionError();
    }
}
