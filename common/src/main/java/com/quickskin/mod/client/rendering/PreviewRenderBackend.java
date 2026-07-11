package com.quickskin.mod.client.rendering;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Version seam for player previews rendered from GUI extraction.
 */
@Environment(EnvType.CLIENT)
public interface PreviewRenderBackend {
    PreviewRenderBackend INSTANCE = new DeferredCollectorPreviewRenderBackend();

    void renderPlayerModel(
            GuiGraphicsExtractor graphics,
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
