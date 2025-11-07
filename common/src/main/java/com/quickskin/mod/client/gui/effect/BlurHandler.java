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
    private static long fadeStartTime = -1;
    private static final int FADE_TIME_MS = 200;
    private static final ResourceLocation BLUR_SHADER = new ResourceLocation("minecraft", "shaders/post/blur.json");

    // Blur intensity: Higher values = more blur (1.0 = subtle, 10.0 = intense, default is 5.0)
    private static float blurRadius = 5.0f;

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
                fadeStartTime = System.currentTimeMillis();
            } catch (Exception e) {
                QuickSkin.LOGGER.error("Failed to load blur shader", e);
                return;
            }
        }

        // Render blur
        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        blurShader.process(0.0f);
        mc.getMainRenderTarget().bindWrite(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
    }

    private static float getProgress() {
        if (fadeStartTime < 0) return 1.0f;

        long elapsed = System.currentTimeMillis() - fadeStartTime;
        float progress = Math.min(elapsed / (float) FADE_TIME_MS, 1.0f);

        // Ease-in-out
        return progress * (2.0f - progress);
    }

    public static void cleanup() {
        if (blurShader != null) {
            blurShader.close();
            blurShader = null;
            fadeStartTime = -1;
        }
    }

    public static void resize(int width, int height) {
        if (blurShader != null) {
            blurShader.resize(width, height);
        }
    }

    /**
     * Set the blur intensity
     * @param radius Blur radius (1.0 = subtle, 5.0 = default, 10.0 = intense, 20.0 = very intense)
     */
    public static void setBlurRadius(float radius) {
        blurRadius = radius;
        QuickSkin.LOGGER.info("Blur radius set to: {}", radius);
    }

    /**
     * Get the current blur radius
     */
    public static float getBlurRadius() {
        return blurRadius;
    }
}
