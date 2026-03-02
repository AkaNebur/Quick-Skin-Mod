package com.quickskin.mod.client.gui.effect;

import com.mojang.blaze3d.systems.RenderSystem;
import com.quickskin.mod.QuickSkin;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;

/**
 * Handles blur rendering - simplified to be called directly from the screen
 */
@Environment(EnvType.CLIENT)
public class BlurHandler {
    private static PostChain blurShader = null;
    private static final ResourceLocation BLUR_SHADER = ResourceLocation.fromNamespaceAndPath("minecraft", "shaders/post/blur.json");

    /**
     * Call this after rendering the background but before rendering UI
     */
    public static void renderBlur() {
        Minecraft mc = Minecraft.getInstance();

        // Initialize blur shader if needed
        if (blurShader == null) {
            try {
                blurShader = new PostChain(mc.getTextureManager(), mc.getResourceManager(),
                    mc.getMainRenderTarget(), BLUR_SHADER);
                blurShader.resize(mc.getWindow().getWidth(), mc.getWindow().getHeight());
            } catch (Exception e) {
                return;
            }
        }

        // Render blur
        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        blurShader.process(0.0f);
        mc.getMainRenderTarget().bindWrite(true);  // Changed to true so we can draw on top
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
    }

    public static void cleanup() {
        if (blurShader != null) {
            blurShader.close();
            blurShader = null;
        }
    }
}
