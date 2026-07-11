package com.quickskin.mod.client.gui.util;

//? if <1.21.11 {
import com.mojang.blaze3d.systems.RenderSystem;
import com.quickskin.mod.QuickSkin;
//?}
import com.quickskin.mod.client.gui.StarPatternCache;
import com.quickskin.mod.client.gui.GuiCompat;
import com.quickskin.mod.client.gui.effect.BlurHandler;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.common.data.BackgroundStyle;
import net.minecraft.client.Minecraft;
//? if <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.CubeMap;
//? if <26.1 {
import net.minecraft.client.renderer.PanoramaRenderer;
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.client.renderer.Panorama;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
//?}

public class BackgroundRenderer {
    //? if <1.21.11 {
    private static final ResourceLocation VIGNETTE_LOCATION =
        new ResourceLocation("textures/misc/vignette.png");
    //?} else {
    private static final Identifier VIGNETTE_LOCATION =
        Identifier.withDefaultNamespace("textures/misc/vignette.png");
    //?}

    // Panorama renderer instance (reused across renders)
    //? if <26.1 {
    private static PanoramaRenderer panoramaRenderer = null;
    private static final ResourceLocation PANORAMA_OVERLAY = new ResourceLocation("textures/gui/title/background/panorama_overlay.png");
    private static final CubeMap PANORAMA_CUBE_MAP = new CubeMap(new ResourceLocation("textures/gui/title/background/panorama"));
    //?} else {
    private static Panorama panoramaRenderer = null;
    private static final Identifier PANORAMA_OVERLAY = Identifier.withDefaultNamespace("textures/gui/title/background/panorama_overlay.png");
    private static final CubeMap PANORAMA_CUBE_MAP = new CubeMap(Identifier.withDefaultNamespace("textures/gui/title/background/panorama"));
    //?}

    /**
     * Renders menu background based on config setting
     * @param screen The screen rendering the background
     * @param graphics Graphics context
     * @param partialTick Partial tick for animations
     */
    //? if <26.1 {
    public static void renderBackground(Screen screen, GuiGraphics graphics, float partialTick) {
    //?} else {
    public static void renderBackground(Screen screen, GuiGraphicsExtractor graphics, float partialTick) {
    //?}
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
    //? if <26.1 {
    private static void renderOpaqueStarsBackground(Screen screen, GuiGraphics graphics, float partialTick) {
    //?} else {
    private static void renderOpaqueStarsBackground(Screen screen, GuiGraphicsExtractor graphics, float partialTick) {
    //?}
        // 1. Base black fill
        graphics.fill(0, 0, screen.width, screen.height, 0xFF000000);

        // 2. Star pattern
        renderStarPattern(screen, graphics, partialTick);

        //? if <1.21.11 {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(0.0F, 0.0F, 0.0F, 0.75F);
        GuiCompat.blit(graphics, VIGNETTE_LOCATION, 0, 0, 0, 0.0f, 0.0f, screen.width, screen.height, screen.width, screen.height);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
        //?} else {
        // 3. Vignette overlay — pass ARGB color (black at 75% opacity)
        int vignetteColor = (191 << 24) | 0; // 0xBF000000
        graphics.blit(RenderPipelines.GUI_TEXTURED, VIGNETTE_LOCATION, 0, 0, 0.0f, 0.0f,
            screen.width, screen.height, screen.width, screen.height, vignetteColor);
        //?}
    }

    /**
     * Renders scrolling star pattern overlay
     */
    //? if <26.1 {
    private static void renderStarPattern(Screen screen, GuiGraphics graphics, float partialTick) {
    //?} else {
    private static void renderStarPattern(Screen screen, GuiGraphicsExtractor graphics, float partialTick) {
    //?}
        double pixelsPerSecond = 5.0;
        int tileSize = StarPatternCache.getTileSize();

        //? if <1.21.11 {
        ResourceLocation starTexture = StarPatternCache.getTextureLocation();
        int texWidth = StarPatternCache.getTextureWidth();
        int texHeight = StarPatternCache.getTextureHeight();
        //?} else {
        Identifier starTexture = StarPatternCache.getTextureLocation();
        int cacheWidth = StarPatternCache.getTextureWidth();
        int cacheHeight = StarPatternCache.getTextureHeight();
        //?}

        // Calculate smooth scrolling offset
        Minecraft minecraft = Minecraft.getInstance();
        //? if <26.2 {
        int tickCount = minecraft != null && minecraft.gui != null ? minecraft.gui.getGuiTicks() : 0;
        //?} else {
        int tickCount = minecraft != null && minecraft.gui != null ? minecraft.gui.hud.getGuiTicks() : 0;
        //?}
        double smoothTime = (tickCount + partialTick) / 20.0;
        double offsetX = (smoothTime * pixelsPerSecond) % tileSize;

        //? if <1.21.11 {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 0.15f);
        //?} else {
        // Star opacity as ARGB color (white with 15% opacity)
        int argbColor = (38 << 24) | (255 << 16) | (255 << 8) | 255; // 0x26FFFFFF
        //?}

        //? if <1.21.11 {
        float u0 = (float)offsetX / (float)texWidth;
        float v0 = 0.0f;
        float u1 = u0 + ((float)screen.width / (float)texWidth);
        float v1 = (float)screen.height / (float)texHeight;
        //?} else {
        // Ensure linear filtering for smooth sub-pixel scrolling
        StarPatternCache.ensureLinearFiltering();
        //?}

        //? if <1.21.11 {
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
        //?} else {
        // Render with blit passing ARGB color for opacity
        // Use GUI_TEXTURED instead of guiTexturedOverlay for Sodium compatibility
        graphics.blit(RenderPipelines.GUI_TEXTURED, starTexture, 0, 0, (float) offsetX, 0.0f,
            screen.width, screen.height, cacheWidth, cacheHeight, argbColor);
        //?}
    }

    /**
     * Renders vanilla-style background - shows panorama on title screen,
     * or transparent overlay when in-game to show the world behind
     */
    //? if <26.1 {
    private static void renderBlurredBackground(Screen screen, GuiGraphics graphics, float partialTick) {
    //?} else {
    private static void renderBlurredBackground(Screen screen, GuiGraphicsExtractor graphics, float partialTick) {
    //?}
        Minecraft minecraft = Minecraft.getInstance();

        // Check if player is in a world
        if (minecraft != null && minecraft.player != null && minecraft.level != null) {
            // In-game: render semi-transparent overlay to show the world behind
            graphics.fill(0, 0, screen.width, screen.height, 0x90000000);
        } else {
            // Title screen: render the panorama background
            if (panoramaRenderer == null) {
                //? if <26.1 {
                panoramaRenderer = new PanoramaRenderer(PANORAMA_CUBE_MAP);
                //?} else {
                panoramaRenderer = new Panorama();
                //?}
            }

            // Sync panorama time with global time source (same as TitleScreen via mixin)
            PanoramaTimeSync.syncPanoramaRenderer(panoramaRenderer);

            //? if <26.2 {
            GuiCompat.renderPanorama(panoramaRenderer, partialTick);
            //?} else {
            // 26.2: Panorama.extractRenderState dropped its trailing "should spin" boolean; spinning is
            // now toggled via startSpin()/holdSpin(). Keep the panorama spinning like the title screen.
            GuiCompat.extractPanorama(panoramaRenderer, graphics, screen.width, screen.height);
            //?}

            // Render panorama overlay (darkens the panorama like in title screen)
            //? if <1.21.11 {
            RenderSystem.enableBlend();
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
            //?}
            GuiCompat.blit(graphics, PANORAMA_OVERLAY, 0, 0, 0, 0.0F, 0.0F, screen.width, screen.height, 16, 128);

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
