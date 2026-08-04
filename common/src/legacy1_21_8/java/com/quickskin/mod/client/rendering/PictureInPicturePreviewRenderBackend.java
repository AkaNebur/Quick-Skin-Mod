package com.quickskin.mod.client.rendering;

import net.minecraft.client.gui.GuiGraphics;

/** Minecraft 1.21.8 picture-in-picture preview submission backend. */
public final class PictureInPicturePreviewRenderBackend implements PreviewRenderBackend {
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
