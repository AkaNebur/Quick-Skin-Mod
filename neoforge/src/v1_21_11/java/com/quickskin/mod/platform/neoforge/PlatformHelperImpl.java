package com.quickskin.mod.platform.neoforge;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * NeoForge implementation of PlatformHelper for MC 1.21.6+
 * Uses RenderPipelines.GUI_TEXTURED instead of RenderType::guiTextured
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
        return !FMLEnvironment.isProduction();
    }

    private static int abgrToArgb(int abgr) {
        int a = (abgr >> 24) & 0xFF;
        int b = (abgr >> 16) & 0xFF;
        int g = (abgr >> 8) & 0xFF;
        int r = abgr & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static void setPixel(NativeImage image, int x, int y, int color) {
        image.setPixel(x, y, abgrToArgb(color));
    }

    public static int getPixel(NativeImage image, int x, int y) {
        return abgrToArgb(image.getPixel(x, y));
    }

    public static void setYoung(PlayerModel model, boolean young) {
    }

    public static void setCrouching(PlayerModel model, boolean crouching) {
    }

    public static void setRiding(PlayerModel model, boolean riding) {
    }

    public static void setAttackTime(PlayerModel model, float attackTime) {
    }

    public static void blit(GuiGraphics graphics, Identifier texture, int x, int y, int blitOffset, float u, float v, int width, int height, int textureWidth, int textureHeight) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, u, v, width, height, textureWidth, textureHeight);
    }

    public static void blit(GuiGraphics graphics, Identifier texture, int x, int y, int width, int height, float u, float v, int regionWidth, int regionHeight, int textureWidth, int textureHeight) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, u, v, width, height, regionWidth, regionHeight, textureWidth, textureHeight);
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
            } catch (NoSuchFieldException e) { }
        }
        for (String name : fieldNames) {
            try {
                cloakField = PlayerModel.class.getDeclaredField(name);
                cloakField.setAccessible(true);
                return;
            } catch (NoSuchFieldException e) { }
        }
        cloakField = null;
    }

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
