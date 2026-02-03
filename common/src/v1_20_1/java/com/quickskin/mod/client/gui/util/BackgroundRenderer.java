package com.quickskin.mod.client.gui.util;

import com.mojang.blaze3d.systems.RenderSystem;
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.gui.StarPatternCache;
import com.quickskin.mod.client.gui.effect.BlurHandler;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.common.data.BackgroundStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.CubeMap;
import net.minecraft.client.renderer.PanoramaRenderer;
import net.minecraft.resources.ResourceLocation;

public class BackgroundRenderer {
    private static final ResourceLocation VIGNETTE_LOCATION =
        new ResourceLocation("textures/misc/vignette.png");

    // Panorama renderer instance (reused across renders)
    private static PanoramaRenderer panoramaRenderer = null;
    private static final ResourceLocation PANORAMA_OVERLAY = new ResourceLocation("textures/gui/title/background/panorama_overlay.png");
    private static final CubeMap PANORAMA_CUBE_MAP = new CubeMap(new ResourceLocation("textures/gui/title/background/panorama"));

    /**
     * Renders menu background based on config setting
     * @param screen The screen rendering the background
     * @param graphics Graphics context
     * @param partialTick Partial tick for animations
     */
    public static void renderBackground(Screen screen, GuiGraphics graphics, float partialTick) {
        BackgroundStyle style = ClientConfig.getInstance().getMenuBackgroundStyle();

        switch (style) {
            case OPAQUE_STARS:
                renderOpaqueStarsBackground(screen, graphics, partialTick);
                break;
            case VANILLA_BLUR:
                renderBlurredBackground(screen, graphics, partialTick);
                break;
        }
    }

    /**
     * Renders opaque black background with star pattern and vignette (current default)
     */
    private static void renderOpaqueStarsBackground(Screen screen, GuiGraphics graphics, float partialTick) {
        // 1. Base black fill
        graphics.fill(0, 0, screen.width, screen.height, 0xFF000000);

        // 2. Star pattern
        renderStarPattern(screen, graphics, partialTick);

        // 3. Vignette overlay
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(0.0F, 0.0F, 0.0F, 0.75F);
        graphics.blit(VIGNETTE_LOCATION, 0, 0, 0, 0.0f, 0.0f, screen.width, screen.height, screen.width, screen.height);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
    }

    /**
     * Renders scrolling star pattern overlay
     */
    private static void renderStarPattern(Screen screen, GuiGraphics graphics, float partialTick) {
        double pixelsPerSecond = 5.0;
        int tileSize = StarPatternCache.getTileSize();

        ResourceLocation starTexture = StarPatternCache.getTextureLocation();
        int texWidth = StarPatternCache.getTextureWidth();
        int texHeight = StarPatternCache.getTextureHeight();

        // Calculate smooth scrolling offset
        Minecraft minecraft = Minecraft.getInstance();
        int tickCount = minecraft != null && minecraft.gui != null ? minecraft.gui.getGuiTicks() : 0;
        double smoothTime = (tickCount + partialTick) / 20.0;
        double offsetX = (smoothTime * pixelsPerSecond) % tileSize;

        // Render star pattern with scrolling UV offset
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 0.15f);

        // Calculate UV coordinates for smooth sub-pixel scrolling
        float u0 = (float)offsetX / (float)texWidth;
        float v0 = 0.0f;
        float u1 = u0 + ((float)screen.width / (float)texWidth);
        float v1 = (float)screen.height / (float)texHeight;

        // Render a single quad with the scrolling UV coordinates
        var pose = graphics.pose();
        pose.pushPose();

        RenderSystem.setShaderTexture(0, starTexture);
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexShader);

        com.mojang.blaze3d.vertex.Tesselator tesselator = com.mojang.blaze3d.vertex.Tesselator.getInstance();
        com.mojang.blaze3d.vertex.BufferBuilder bufferBuilder = tesselator.getBuilder();

        bufferBuilder.begin(
            com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
            com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX
        );
        bufferBuilder.vertex(pose.last().pose(), 0, screen.height, 0).uv(u0, v1).endVertex();
        bufferBuilder.vertex(pose.last().pose(), screen.width, screen.height, 0).uv(u1, v1).endVertex();
        bufferBuilder.vertex(pose.last().pose(), screen.width, 0, 0).uv(u1, v0).endVertex();
        bufferBuilder.vertex(pose.last().pose(), 0, 0, 0).uv(u0, v0).endVertex();
        tesselator.end();

        pose.popPose();

        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    /**
     * Renders vanilla-style background - shows panorama without UI buttons
     */
    private static void renderBlurredBackground(Screen screen, GuiGraphics graphics, float partialTick) {
        // Initialize panorama renderer if needed
        if (panoramaRenderer == null) {
            panoramaRenderer = new PanoramaRenderer(PANORAMA_CUBE_MAP);
        }

        // Render the panorama background
        panoramaRenderer.render(partialTick, 1.0F);

        // Render panorama overlay (darkens the panorama like in title screen)
        RenderSystem.enableBlend();
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        graphics.blit(PANORAMA_OVERLAY, 0, 0, 0, 0.0F, 0.0F, screen.width, screen.height, 16, 128);

        // Add additional dark overlay for better UI readability
        graphics.fill(0, 0, screen.width, screen.height, 0x60000000);
    }


    /**
     * Cleans up blur resources when screen is closed
     * MUST be called from screen's removed() or onClose() method
     */
    public static void cleanup() {
        BlurHandler.cleanup();
    }
}
