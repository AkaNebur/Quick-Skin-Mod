package com.quickskin.mod.client.gui.util;

import com.quickskin.mod.client.gui.StarPatternCache;
import com.quickskin.mod.client.gui.effect.BlurHandler;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.common.data.BackgroundStyle;
import com.quickskin.mod.platform.PlatformHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.CubeMap;
import net.minecraft.client.renderer.PanoramaRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

public class BackgroundRenderer {
    private static final ResourceLocation VIGNETTE_LOCATION =
        ResourceLocation.withDefaultNamespace("textures/misc/vignette.png");

    // Panorama renderer instance (reused across renders)
    private static PanoramaRenderer panoramaRenderer = null;
    private static final ResourceLocation PANORAMA_OVERLAY = ResourceLocation.withDefaultNamespace("textures/gui/title/background/panorama_overlay.png");
    private static final CubeMap PANORAMA_CUBE_MAP = new CubeMap(ResourceLocation.withDefaultNamespace("textures/gui/title/background/panorama"));

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

        // 3. Vignette overlay — pass ARGB color (black at 75% opacity)
        int vignetteColor = (191 << 24) | 0; // 0xBF000000
        graphics.blit(RenderType::guiTextured, VIGNETTE_LOCATION, 0, 0, 0.0f, 0.0f,
            screen.width, screen.height, screen.width, screen.height, vignetteColor);
    }

    /**
     * Renders scrolling star pattern overlay
     */
    private static void renderStarPattern(Screen screen, GuiGraphics graphics, float partialTick) {
        double pixelsPerSecond = 5.0;
        int tileSize = StarPatternCache.getTileSize();

        ResourceLocation starTexture = StarPatternCache.getTextureLocation();
        int cacheWidth = StarPatternCache.getTextureWidth();
        int cacheHeight = StarPatternCache.getTextureHeight();

        // Calculate smooth scrolling offset
        Minecraft minecraft = Minecraft.getInstance();
        int tickCount = minecraft != null && minecraft.gui != null ? minecraft.gui.getGuiTicks() : 0;
        double smoothTime = (tickCount + partialTick) / 20.0;
        double offsetX = (smoothTime * pixelsPerSecond) % tileSize;

        // Star opacity as ARGB color (white with 15% opacity)
        int argbColor = (38 << 24) | (255 << 16) | (255 << 8) | 255; // 0x26FFFFFF

        // Ensure linear filtering for smooth sub-pixel scrolling
        StarPatternCache.ensureLinearFiltering();

        // Render with blit passing ARGB color for opacity
        // Use guiTextured instead of guiTexturedOverlay for Sodium compatibility
        graphics.blit(RenderType::guiTextured, starTexture, 0, 0, (float) offsetX, 0.0f,
            screen.width, screen.height, cacheWidth, cacheHeight, argbColor);
    }

    /**
     * Renders vanilla-style background - shows panorama on title screen,
     * or transparent overlay when in-game to show the world behind
     */
    private static void renderBlurredBackground(Screen screen, GuiGraphics graphics, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();

        // Check if player is in a world
        if (minecraft != null && minecraft.player != null && minecraft.level != null) {
            // In-game: render semi-transparent overlay to show the world behind
            graphics.fill(0, 0, screen.width, screen.height, 0x90000000);
        } else {
            // Title screen: render the panorama background
            if (panoramaRenderer == null) {
                panoramaRenderer = new PanoramaRenderer(PANORAMA_CUBE_MAP);
            }

            // Sync panorama time with global time source (same as TitleScreen via mixin)
            PanoramaTimeSync.syncPanoramaRenderer(panoramaRenderer);

            // Render the panorama background
            panoramaRenderer.render(graphics, screen.width, screen.height, 1.0F, partialTick);

            // Render panorama overlay (darkens the panorama like in title screen)
            // graphics.setColor() removed in 1.21.4+; use PlatformHelper.blit for cross-version compat
            PlatformHelper.blit(graphics, PANORAMA_OVERLAY, 0, 0, 0, 0.0F, 0.0F, screen.width, screen.height, 16, 128);

            // Add additional dark overlay for better UI readability
            graphics.fill(0, 0, screen.width, screen.height, 0x60000000);
        }
    }

    /**
     * Cleans up resources when screen is closed.
     * MUST be called from screen's removed() or onClose() method.
     */
    public static void cleanup() {
        BlurHandler.cleanup();
        // Don't reset panoramaRenderer - keep it for consistent time accumulation
    }
}
