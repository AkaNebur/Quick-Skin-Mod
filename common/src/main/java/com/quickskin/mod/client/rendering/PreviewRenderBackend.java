package com.quickskin.mod.client.rendering;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
//? if <26.2 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}

/**
 * Version seam for player previews rendered from GUI extraction.
 */
@Environment(EnvType.CLIENT)
public interface PreviewRenderBackend {
    //? if <26.2 {
    PreviewRenderBackend INSTANCE = new ImmediatePreviewRenderBackend();
    //?} else {
    PreviewRenderBackend INSTANCE = new DeferredCollectorPreviewRenderBackend();
    //?}

    void renderPlayerModel(
            //? if <26.2 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
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
