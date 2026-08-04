package com.quickskin.mod.client.rendering;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Minecraft 26.1.2 immediate-extraction preview backend. */
@Environment(EnvType.CLIENT)
public final class ExtractorPreviewRenderBackend implements PreviewRenderBackend {
    @Override
    public void renderPlayerModel(
            GuiGraphicsExtractor graphics,
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
                graphics, x, y, scale, yRotation, playerData, mouseX, mouseY, followMouse
        );
    }
}
