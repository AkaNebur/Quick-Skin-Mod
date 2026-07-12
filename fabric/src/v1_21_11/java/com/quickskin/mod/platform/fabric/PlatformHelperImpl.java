package com.quickskin.mod.platform.fabric;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.nio.file.Path;

/**
 * Fabric implementation of PlatformHelper for MC 1.21.11+
 * APIs changed in 1.21.2+:
 * - setPixelRGBA/getPixelRGBA (ABGR) → setPixel/getPixel (ARGB)
 * - model.young/crouching/riding/attackTime fields removed
 * - model.renderCloak() → model.cloak.render()
 * - graphics.blit(Identifier, ...) → graphics.blit(RenderType::guiTextured, Identifier, ...)
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
     * For MC 1.21.11 (1.21.2+), uses setPixel with ARGB format
     * @param color Color in ABGR format (our standard format used throughout the codebase)
     */
    public static void setPixel(NativeImage image, int x, int y, int color) {
        // MC 1.21.2+ uses setPixel with ARGB format, convert from our ABGR
        image.setPixel(x, y, abgrToArgb(color));
    }

    /**
     * Gets a pixel from a NativeImage
     * For MC 1.21.11 (1.21.2+), uses getPixel with ARGB format
     * @return Color in ABGR format (our standard format used throughout the codebase)
     */
    public static int getPixel(NativeImage image, int x, int y) {
        // MC 1.21.2+ uses getPixel with ARGB format, convert to our ABGR
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
     * For MC 1.21.11+, uses RenderPipelines.GUI_TEXTURED
     */
    public static void blit(GuiGraphics graphics, Identifier texture, int x, int y, int blitOffset, float u, float v, int width, int height, int textureWidth, int textureHeight) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, u, v, width, height, textureWidth, textureHeight);
    }

    /**
     * Blits a texture to the screen with width/height before UV coordinates
     * For MC 1.21.11+, uses the 12-param blit overload with separate region sizes
     */
    public static void blit(GuiGraphics graphics, Identifier texture, int x, int y, int width, int height, float u, float v, int regionWidth, int regionHeight, int textureWidth, int textureHeight) {
        // 12-param: blit(RenderPipeline, RL, x, y, u, v, width, height, regionWidth, regionHeight, textureWidth, textureHeight)
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, u, v, width, height, regionWidth, regionHeight, textureWidth, textureHeight);
    }

    private static java.lang.reflect.Field cloakField;
    private static boolean cloakFieldChecked = false;

    private static void initializeCloakField() {
        if (cloakFieldChecked) return;
        cloakFieldChecked = true;

        // Try to find the cloak ModelPart field - may be named "cloak" or "cape" depending on mappings
        String[] fieldNames = {"cloak", "cape"};
        for (String name : fieldNames) {
            try {
                cloakField = PlayerModel.class.getField(name);
                return;
            } catch (NoSuchFieldException e) {
                // Try next name
            }
        }
        // If no public field found, try declared fields (private/protected)
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
     * For MC 1.21.11 (1.21.2+), renderCloak() was removed, render cloak ModelPart directly.
     * Uses reflection to find the cloak field since it may not exist or may be renamed.
     */
    public static void renderCloak(PlayerModel model, PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay) {
        initializeCloakField();
        if (cloakField != null) {
            try {
                Object cloakPart = cloakField.get(model);
                if (cloakPart != null) {
                    // The cloak is a ModelPart, call its render method
                    java.lang.reflect.Method renderMethod = cloakPart.getClass().getMethod("render",
                        PoseStack.class, VertexConsumer.class, int.class, int.class);
                    renderMethod.invoke(cloakPart, poseStack, vertexConsumer, packedLight, packedOverlay);
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to render cloak ModelPart directly", e);
            }
        }
        // If cloak field doesn't exist, silently skip rendering
    }
}
