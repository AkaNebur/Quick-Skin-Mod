package com.quickskin.mod.client.gui;

import com.quickskin.mod.platform.MinecraftCompat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.Panorama;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Narrow compatibility surface for GUI APIs that change between supported Minecraft eras.
 */
@Environment(EnvType.CLIENT)
public final class GuiCompat {
    private GuiCompat() {
    }

    public static void extractParent(Screen parent, GuiGraphicsExtractor graphics, float partialTick) {
        parent.extractRenderState(graphics, -1, -1, partialTick);
    }

    public static void extractPanorama(Panorama panorama, GuiGraphicsExtractor graphics, int width, int height) {
        panorama.startSpin();
        panorama.extractRenderState(graphics, width, height);
    }

    public static double mouseX(MouseButtonEvent event) {
        return event.x();
    }

    public static double mouseY(MouseButtonEvent event) {
        return event.y();
    }

    public static int mouseButton(MouseButtonEvent event) {
        return event.buttonInfo().button();
    }

    public static int keyCode(KeyEvent event) {
        return event.key();
    }

    public static void blit(
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
    ) {
        MinecraftCompat.INSTANCE.blit(
                graphics, texture, x, y, blitOffset, u, v, width, height, textureWidth, textureHeight
        );
    }

    public static void blit(
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
    ) {
        MinecraftCompat.INSTANCE.blit(
                graphics, texture, x, y, width, height, u, v,
                regionWidth, regionHeight, textureWidth, textureHeight
        );
    }

    public static void tooltip(
            GuiGraphicsExtractor graphics,
            Font font,
            Component text,
            int mouseX,
            int mouseY
    ) {
        List<ClientTooltipComponent> components = List.of(
                ClientTooltipComponent.create(text.getVisualOrderText())
        );
        graphics.tooltip(font, components, mouseX, mouseY, DefaultTooltipPositioner.INSTANCE, null);
    }

    public static void tooltip(
            GuiGraphicsExtractor graphics,
            Font font,
            List<Component> lines,
            int mouseX,
            int mouseY
    ) {
        List<ClientTooltipComponent> components = lines.stream()
                .map(line -> ClientTooltipComponent.create(line.getVisualOrderText()))
                .collect(Collectors.toList());
        graphics.tooltip(font, components, mouseX, mouseY, DefaultTooltipPositioner.INSTANCE, null);
    }
}
