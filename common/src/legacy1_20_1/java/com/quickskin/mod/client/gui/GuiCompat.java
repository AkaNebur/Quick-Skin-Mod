package com.quickskin.mod.client.gui;

import com.quickskin.mod.platform.MinecraftCompat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.PanoramaRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;

/** Narrow compatibility surface for the legacy GUI APIs. */
@Environment(EnvType.CLIENT)
public final class GuiCompat {
    private GuiCompat() {
    }

    public static void renderParent(Screen parent, GuiGraphics graphics, float partialTick) {
        parent.render(graphics, -1, -1, partialTick);
    }

    public static void renderPanorama(PanoramaRenderer panorama, float partialTick) {
        panorama.render(partialTick, 1.0F);
    }

    public static double mouseX(double mouseX) {
        return mouseX;
    }

    public static double mouseY(double mouseY) {
        return mouseY;
    }

    public static int mouseButton(int button) {
        return button;
    }

    public static int keyCode(int keyCode) {
        return keyCode;
    }

    public static void blit(GuiGraphics graphics, ResourceLocation texture, int x, int y, int blitOffset,
                            float u, float v, int width, int height, int textureWidth, int textureHeight) {
        MinecraftCompat.INSTANCE.blit(
                graphics, texture, x, y, blitOffset, u, v, width, height, textureWidth, textureHeight);
    }

    public static void blit(GuiGraphics graphics, ResourceLocation texture, int x, int y, int width, int height,
                            float u, float v, int regionWidth, int regionHeight,
                            int textureWidth, int textureHeight) {
        MinecraftCompat.INSTANCE.blit(
                graphics, texture, x, y, width, height, u, v,
                regionWidth, regionHeight, textureWidth, textureHeight);
    }

    public static void tooltip(GuiGraphics graphics, Font font, Component text, int mouseX, int mouseY) {
        graphics.renderTooltip(font, text, mouseX, mouseY);
    }

    public static void tooltip(GuiGraphics graphics, Font font, List<Component> lines, int mouseX, int mouseY) {
        graphics.renderTooltip(font, lines, Optional.empty(), mouseX, mouseY);
    }
}
