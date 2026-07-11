package com.quickskin.mod.client.rendering;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;

/** Version seam for player previews. */
@Environment(EnvType.CLIENT)
public interface PreviewRenderBackend {
    PreviewRenderBackend INSTANCE = new ImmediatePreviewRenderBackend();

    void renderPlayerModel(
            GuiGraphics graphics,
            int x,
            int y,
            float scale,
            float yRotation,
            PreviewPlayerData playerData,
            int mouseX,
            int mouseY,
            boolean followMouse
    );
}
