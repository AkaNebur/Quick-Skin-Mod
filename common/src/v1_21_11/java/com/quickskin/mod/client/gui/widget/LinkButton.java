package com.quickskin.mod.client.gui.widget;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.platform.PlatformHelper;
import net.minecraft.util.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class LinkButton extends Button {

    private final Identifier texture;
    private final int textureWidth;
    private final int textureHeight;

    public LinkButton(int x, int y, int width, int height, Identifier texture, String url, Component tooltip) {
        super(x, y, width, height, Component.empty(), button -> {
            if (url != null) {
                openLink(url);
            }
        }, DEFAULT_NARRATION);

        this.texture = texture;

        // Assuming square textures for logos
        this.textureWidth = 256;
        this.textureHeight = 256;

        this.setTooltip(Tooltip.create(tooltip));
    }

    @Override
    protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        // 1.21.11: renderContents is responsible for everything including background
        renderDefaultSprite(graphics);
        // RenderSystem.setShaderColor() removed in 1.21.6

        // Draw the logo texture on top, inset slightly to fit within the rounded border.
        int padding = 2;
        PlatformHelper.blit(graphics, this.texture,
                this.getX() + padding, this.getY() + padding,           // Screen position (x, y) with padding
                this.width - (padding * 2), this.height - (padding * 2), // Size on screen (width, height) reduced by padding
                0.0F, 0.0F,                                              // Texture UV start
                this.textureWidth, this.textureHeight,                   // Region in texture to draw (the whole image)
                this.textureWidth, this.textureHeight                    // Total texture size
        );
    }

    private static void openLink(String url) {
        // Open the link in the default browser
        Util.getPlatform().openUri(url);
    }

    @Override
    protected boolean isValidClickButton(net.minecraft.client.input.MouseButtonInfo buttonInfo) {
        // Only allow left-click
        return buttonInfo.button() == 0;
    }
}
