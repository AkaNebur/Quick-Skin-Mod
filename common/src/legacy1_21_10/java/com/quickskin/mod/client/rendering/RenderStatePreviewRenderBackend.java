package com.quickskin.mod.client.rendering;

import net.minecraft.client.gui.GuiGraphics;

/** Direct 1.21.10 GUI render-state preview submission backend. */
public final class RenderStatePreviewRenderBackend implements PreviewRenderBackend {
    @Override
    public void renderPlayerModel(
            GuiGraphics graphics,
            int x,
            int y,
            float scale,
            float yRotation,
            PreviewPlayerData playerData,
            int mouseX,
            int mouseY,
            boolean followMouse
    ) {
        PlayerModelRenderer.renderPlayerModel(
                graphics, x, y, scale, yRotation, playerData, mouseX, mouseY, followMouse);
    }
}
