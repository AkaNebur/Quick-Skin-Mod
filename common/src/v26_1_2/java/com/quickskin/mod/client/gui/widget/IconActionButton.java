package com.quickskin.mod.client.gui.widget;

import com.quickskin.mod.platform.PlatformHelper;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class IconActionButton extends Button {

    private final Identifier texture;
    private final int textureWidth;
    private final int textureHeight;

    public IconActionButton(int x, int y, int width, int height, Identifier texture, OnPress onPress, Component tooltip) {
        super(x, y, width, height, Component.empty(), onPress, DEFAULT_NARRATION);
        this.texture = texture;
        this.textureWidth = 256;
        this.textureHeight = 256;
        this.setTooltip(Tooltip.create(tooltip));
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
        // 1.21.11: renderContents is responsible for everything including background
        extractDefaultSprite(graphics);
        // RenderSystem.setShaderColor() removed in 1.21.11

        int padding = 2;
        PlatformHelper.blit(graphics, this.texture,
                this.getX() + padding, this.getY() + padding,
                this.width - (padding * 2), this.height - (padding * 2),
                0.0F, 0.0F,
                this.textureWidth, this.textureHeight,
                this.textureWidth, this.textureHeight
        );
    }
}
