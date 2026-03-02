package com.quickskin.mod.platform.neoforge;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * NeoForge implementation of PlatformHelper for MC 1.21.4+
 * Uses direct API calls (no reflection) since each MC version has its own copy.
 */
@SuppressWarnings("unused")
public class PlatformHelperImpl {

    public static String getPlatformName() {
        return "NeoForge";
    }

    public static Path getGameDirectory() {
        return FMLPaths.GAMEDIR.get();
    }

    public static Path getSkinsDirectory() {
        return FMLPaths.GAMEDIR.get().resolve("quickskin").resolve("uploads").resolve("skins");
    }

    public static Path getCapesDirectory() {
        return FMLPaths.GAMEDIR.get().resolve("quickskin").resolve("uploads").resolve("capes");
    }

    public static Path getConfigDirectory() {
        return FMLPaths.CONFIGDIR.get();
    }

    public static Path getCacheDirectory() {
        return FMLPaths.GAMEDIR.get().resolve("quickskin_cache");
    }

    public static boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    public static String getModVersion() {
        return ModList.get()
            .getModContainerById("quickskin")
            .map(container -> container.getModInfo().getVersion().toString())
            .orElse("UNKNOWN");
    }

    public static boolean isDevelopmentEnvironment() {
        return !FMLLoader.isProduction();
    }

    /**
     * Converts ABGR color to ARGB color by swapping red and blue channels
     */
    private static int abgrToArgb(int abgr) {
        int a = (abgr >> 24) & 0xFF;
        int b = (abgr >> 16) & 0xFF;
        int g = (abgr >> 8) & 0xFF;
        int r = abgr & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Sets a pixel in a NativeImage
     * For MC 1.21.4 (1.21.2+), uses setPixel with ARGB format
     * @param color Color in ABGR format (our standard format used throughout the codebase)
     */
    public static void setPixel(NativeImage image, int x, int y, int color) {
        image.setPixel(x, y, abgrToArgb(color));
    }

    /**
     * Gets a pixel from a NativeImage
     * For MC 1.21.4 (1.21.2+), uses getPixel with ARGB format
     * @return Color in ABGR format (our standard format used throughout the codebase)
     */
    public static int getPixel(NativeImage image, int x, int y) {
        return abgrToArgb(image.getPixel(x, y));
    }

    /**
     * Sets the 'young' field on a PlayerModel
     * In MC 1.21.2+, this field was removed — no-op
     */
    public static void setYoung(PlayerModel model, boolean young) {
        // Field removed in 1.21.2+
    }

    /**
     * Sets the 'crouching' field on a PlayerModel
     * In MC 1.21.2+, this field was removed — no-op
     */
    public static void setCrouching(PlayerModel model, boolean crouching) {
        // Field removed in 1.21.2+
    }

    /**
     * Sets the 'riding' field on a PlayerModel
     * In MC 1.21.2+, this field was removed — no-op
     */
    public static void setRiding(PlayerModel model, boolean riding) {
        // Field removed in 1.21.2+
    }

    /**
     * Sets the 'attackTime' field on a PlayerModel
     * In MC 1.21.2+, this field was removed — no-op
     */
    public static void setAttackTime(PlayerModel model, float attackTime) {
        // Field removed in 1.21.2+
    }

    /**
     * Blits a texture to the screen
     * For MC 1.21.4 (1.21.2+), uses RenderType::guiTextured function parameter
     */
    public static void blit(GuiGraphics graphics, ResourceLocation texture, int x, int y, int blitOffset, float u, float v, int width, int height, int textureWidth, int textureHeight) {
        graphics.blit(RenderType::guiTextured, texture, x, y, u, v, width, height, textureWidth, textureHeight);
    }

    /**
     * Blits a texture to the screen with width/height before UV coordinates
     * For MC 1.21.4 (1.21.2+), uses the 12-param blit overload with separate region sizes
     */
    public static void blit(GuiGraphics graphics, ResourceLocation texture, int x, int y, int width, int height, float u, float v, int regionWidth, int regionHeight, int textureWidth, int textureHeight) {
        graphics.blit(RenderType::guiTextured, texture, x, y, u, v, width, height, regionWidth, regionHeight, textureWidth, textureHeight);
    }

    private static java.lang.reflect.Field cloakField;
    private static boolean cloakFieldChecked = false;

    private static void initializeCloakField() {
        if (cloakFieldChecked) return;
        cloakFieldChecked = true;

        String[] fieldNames = {"cloak", "cape"};
        for (String name : fieldNames) {
            try {
                cloakField = PlayerModel.class.getField(name);
                return;
            } catch (NoSuchFieldException e) {
                // Try next name
            }
        }
        for (String name : fieldNames) {
            try {
                cloakField = PlayerModel.class.getDeclaredField(name);
                cloakField.setAccessible(true);
                return;
            } catch (NoSuchFieldException e) {
                // Try next name
            }
        }
        cloakField = null;
    }

    /**
     * Renders a cloak on a PlayerModel
     * For MC 1.21.4 (1.21.2+), renderCloak() was removed, render cloak ModelPart directly.
     */
    public static void renderCloak(PlayerModel model, PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay) {
        initializeCloakField();
        if (cloakField != null) {
            try {
                Object cloakPart = cloakField.get(model);
                if (cloakPart != null) {
                    java.lang.reflect.Method renderMethod = cloakPart.getClass().getMethod("render",
                        PoseStack.class, VertexConsumer.class, int.class, int.class);
                    renderMethod.invoke(cloakPart, poseStack, vertexConsumer, packedLight, packedOverlay);
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to render cloak ModelPart directly", e);
            }
        }
    }
}
