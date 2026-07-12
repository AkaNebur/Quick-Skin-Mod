package com.quickskin.mod.client.gui.screen;

import com.mojang.blaze3d.platform.NativeImage;
//? if <1.21.11 {
import com.mojang.blaze3d.systems.RenderSystem;
//?}
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.gui.GuiCompat;
import com.quickskin.mod.client.gui.util.BackgroundRenderer;
import com.quickskin.mod.client.gui.widget.PlayerWidget;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.platform.MinecraftCompat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
//? if <26.1.2 {
import net.minecraft.client.gui.GuiGraphics;
//?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}
import net.minecraft.util.Mth;

import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * Screen for adjusting cape image position and resolution when the imported
 * image doesn't match a standard cape format.
 *
 * The user sees their source image behind a cape template grid.
 * They can drag to reposition, scroll to zoom, and select a target resolution.
 * The "cape back" area (the main visible part) is highlighted.
 */
@Environment(EnvType.CLIENT)
public class CapeAdjustScreen extends Screen {

    private final Screen parent;
    private final BufferedImage sourceImage;
    private final Consumer<BufferedImage> onApply; // Callback with composed cape
    private final Runnable onCancel;
    private boolean completed;
    private final int frameCount;
    private final int srcFrameHeight; // Height of one frame in the source strip

    // Target cape resolution
    private static final int[][] RESOLUTIONS = {
            {64, 32}, {128, 64}, {256, 128}, {512, 256}, {1024, 512}
    };
    private static final String[] RESOLUTION_LABELS = {
            "64x32", "128x64 (2x)", "256x128 (4x)", "512x256 (8x)", "1024x512 (16x)"
    };
    private int selectedResolution = 0;

    // Image positioning in cape-space (target resolution coordinates)
    private double imgOffsetX = 0;
    private double imgOffsetY = 0;
    private double imgScale = 1.0;

    // Display grid (cape template on screen)
    private int gridX, gridY, gridW, gridH;
    private double displayScale; // cape-space pixels -> screen pixels

    // Snap-to-grid
    private static final int[] SNAP_SIZES = {0, 1, 2, 4, 8};
    private static final String[] SNAP_LABELS = {"Off", "1px", "2px", "4px", "8px"};
    private int snapIndex = 1; // Default: 1px snap

    // Dragging
    private boolean isDragging = false;
    private double dragStartX, dragStartY;
    private double dragStartOffsetX, dragStartOffsetY;

    // Animation playback (for multi-frame GIFs)
    private int currentAnimFrame = 0;
    private long animStartTime = 0;

    // Mirror: copy cape back region to front
    private boolean mirrorFrontBack = false;

    // Source image texture
    //? if <1.21.11 {
    private ResourceLocation sourceTextureLocation;
    //?} else {
    private Identifier sourceTextureLocation;
    //?}
    private DynamicTexture sourceDynTexture;

    // Preview texture (cape back portion)
    //? if <1.21.11 {
    private ResourceLocation previewTextureLocation;
    //?} else {
    private Identifier previewTextureLocation;
    //?}
    private DynamicTexture previewDynTexture;
    private boolean previewDirty = true;

    // 3D player model preview (mirrors the main cape menu)
    private PlayerWidget playerWidget;
    //? if <1.21.11 {
    private ResourceLocation lastPlayerWidgetCape;
    //?} else {
    private Identifier lastPlayerWidgetCape;
    //?}

    public CapeAdjustScreen(Screen parent, BufferedImage sourceImage, Consumer<BufferedImage> onApply) {
        this(parent, sourceImage, 1, onApply, () -> {});
    }

    public CapeAdjustScreen(Screen parent, BufferedImage sourceImage, int frameCount, Consumer<BufferedImage> onApply) {
        this(parent, sourceImage, frameCount, onApply, () -> {});
    }

    public CapeAdjustScreen(
            Screen parent,
            BufferedImage sourceImage,
            int frameCount,
            Consumer<BufferedImage> onApply,
            Runnable onCancel
    ) {
        super(Component.translatable("quickskin.cape.adjust_title"));
        this.parent = parent;
        this.sourceImage = sourceImage;
        this.frameCount = Math.max(1, frameCount);
        this.srcFrameHeight = sourceImage.getHeight() / this.frameCount;
        this.onApply = onApply;
        this.onCancel = onCancel != null ? onCancel : () -> {};
    }

    @Override
    protected void init() {
        // Register source image as texture (first frame only for animation strips)
        if (sourceTextureLocation != null) {
            Minecraft.getInstance().getTextureManager().release(sourceTextureLocation);
        }
        BufferedImage displayFrame = (frameCount > 1)
                ? sourceImage.getSubimage(0, 0, sourceImage.getWidth(), srcFrameHeight)
                : sourceImage;
        NativeImage nativeImage = convertToNativeImage(displayFrame);
        //? if <1.21.11 {
        sourceDynTexture = new DynamicTexture(nativeImage);
        sourceTextureLocation = Minecraft.getInstance().getTextureManager()
                .register("quickskin/cape_adjust_source", sourceDynTexture);
        //?} else {
        sourceDynTexture = new DynamicTexture(() -> "quickskin_cape_adjust_source", nativeImage);
        sourceTextureLocation = Identifier.fromNamespaceAndPath(QuickSkin.MOD_ID, "cape_adjust_source");
        Minecraft.getInstance().getTextureManager().register(sourceTextureLocation, sourceDynTexture);
        //?}

        // Calculate grid display area (left 65% of screen, vertically centered)
        int availW = (int) (this.width * 0.6);
        int availH = (int) (this.height * 0.65);
        int capeW = RESOLUTIONS[selectedResolution][0];
        int capeH = RESOLUTIONS[selectedResolution][1];

        // Scale to fit while maintaining 2:1 aspect
        double scaleX = (double) availW / capeW;
        double scaleY = (double) availH / capeH;
        displayScale = Math.min(scaleX, scaleY);

        gridW = (int) (capeW * displayScale);
        gridH = (int) (capeH * displayScale);
        gridX = 20 + (availW - gridW) / 2;
        gridY = 30 + (availH - gridH) / 2;

        // Center image initially to cover the cape area
        resetImagePosition();

        // Buttons
        this.clearWidgets();

        int rightPanelX = gridX + availW + 15;
        int rightPanelW = this.width - rightPanelX - 10;
        int btnW = Math.min(rightPanelW, 120);

        // Resolution buttons — disable resolutions larger than the source image
        // For animated GIFs, cap at 4x (256x128) to avoid lag on servers
        int resY = gridY;
        int srcW = sourceImage.getWidth();
        int srcH = srcFrameHeight;
        // Max resolution index: 2 (256x128 / 4x) for GIFs, all for static
        int maxResIndex = (frameCount > 1) ? 2 : RESOLUTIONS.length - 1;
        for (int i = 0; i < RESOLUTIONS.length; i++) {
            final int idx = i;
            Button btn = Button.builder(Component.literal(RESOLUTION_LABELS[i]), b -> {
                int oldRes = selectedResolution;
                selectedResolution = idx;
                // Scale position/zoom proportionally to new resolution
                double ratio = (double) RESOLUTIONS[idx][0] / RESOLUTIONS[oldRes][0];
                imgOffsetX *= ratio;
                imgOffsetY *= ratio;
                imgScale *= ratio;
                applySnap();
                recalculateGrid();
                previewDirty = true;
            }).bounds(rightPanelX, resY, btnW, 20).build();
            // Disable if target resolution exceeds source dimensions or GIF cap
            if (RESOLUTIONS[i][0] > srcW || RESOLUTIONS[i][1] > srcH || i > maxResIndex) {
                btn.active = false;
            }
            this.addRenderableWidget(btn);
            resY += 24;
        }
        // If the currently selected resolution got disabled, fall back to the largest enabled one
        boolean currentDisabled = RESOLUTIONS[selectedResolution][0] > srcW
                || RESOLUTIONS[selectedResolution][1] > srcH
                || selectedResolution > maxResIndex;
        if (currentDisabled) {
            for (int i = Math.min(maxResIndex, RESOLUTIONS.length - 1); i >= 0; i--) {
                if (RESOLUTIONS[i][0] <= srcW && RESOLUTIONS[i][1] <= srcH) {
                    selectedResolution = i;
                    recalculateGrid();
                    break;
                }
            }
        }

        // Reset position button
        this.addRenderableWidget(Button.builder(
                Component.translatable("quickskin.cape.adjust_reset"),
                b -> { resetImagePosition(); previewDirty = true; }
        ).bounds(rightPanelX, resY + 10, btnW, 20).build());

        // Snap-to-grid toggle button (cycles through snap sizes)
        this.addRenderableWidget(Button.builder(
                Component.literal("Snap: " + SNAP_LABELS[snapIndex]),
                b -> {
                    snapIndex = (snapIndex + 1) % SNAP_SIZES.length;
                    b.setMessage(Component.literal("Snap: " + SNAP_LABELS[snapIndex]));
                    applySnap();
                    previewDirty = true;
                }
        ).bounds(rightPanelX, resY + 34, btnW, 20).build());

        // Mirror front=back toggle button
        this.addRenderableWidget(Button.builder(
                Component.literal("Mirror: " + (mirrorFrontBack ? "ON" : "OFF")),
                b -> {
                    mirrorFrontBack = !mirrorFrontBack;
                    b.setMessage(Component.literal("Mirror: " + (mirrorFrontBack ? "ON" : "OFF")));
                    previewDirty = true;
                }
        ).bounds(rightPanelX, resY + 58, btnW, 20).build());

        // Apply / Cancel
        int bottomY = this.height - 30;
        int totalBtnWidth = 200 + 10 + 80;
        int btnStartX = (this.width - totalBtnWidth) / 2;

        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.cancel"),
                b -> onClose()
        ).bounds(btnStartX, bottomY, 80, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.translatable("quickskin.cape.adjust_apply"),
                b -> applyAndClose()
        ).bounds(btnStartX + 90, bottomY, 120, 20).build());

        // 3D player model preview (same widget used by PlayerCapeMenuScreen).
        // Placed below the 2D cape/elytra previews, aligned with the right-panel
        // column, above the Apply/Cancel bar.
        int maxPreviewW = Math.min(100, rightPanelW);
        int previewStartY = gridY + RESOLUTIONS.length * 24 + 100;
        int estimatedPreviewsBottom = previewStartY
                + (int) ((maxPreviewW / 2 - 2) * 1.6)   // cape back / front thumbnails
                + 18
                + (int) ((maxPreviewW / 2 - 2) * 2.0);  // elytra thumbnail
        int widgetAreaLeft = rightPanelX;
        int widgetAreaRight = this.width - 10;
        int widgetAreaTop = estimatedPreviewsBottom + 15;
        int widgetAreaBottom = bottomY - 20;
        int widgetAreaW = widgetAreaRight - widgetAreaLeft;
        int widgetAreaH = widgetAreaBottom - widgetAreaTop;
        if (widgetAreaW >= 80 && widgetAreaH >= 120) {
            int widgetHeight = Mth.clamp(widgetAreaH, 120, 220);
            int widgetWidth = Mth.clamp((int) (widgetHeight / 1.8f), 70, widgetAreaW);
            int widgetX = widgetAreaLeft + (widgetAreaW - widgetWidth) / 2;
            int widgetY = widgetAreaTop;

            //? if <1.21.11 {
            ResourceLocation skinLocation = null;
            //?} else {
            Identifier skinLocation = null;
            //?}
            String modelType = "classic";
            LocalPlayer player = Minecraft.getInstance().player;
            ClientConfig config = ClientConfig.getInstance();

            if (!config.activeSkinHash.isEmpty()) {
                LocalAssetManager assetManager = LocalAssetManager.getInstance();
                AssetMetadata metadata = assetManager.getMetadata(config.activeSkinHash);
                if (metadata != null) {
                    skinLocation = assetManager.getTextureLocation(config.activeSkinHash, TextureQuality.FULL);
                    modelType = assetManager.getSkinModelPreference(config.activeSkinHash);
                    if ("auto".equals(modelType)) {
                        modelType = metadata.skinModel();
                    }
                }
            }
            if (skinLocation == null && player != null) {
                //? if <1.21.11 {
                    //? if <1.21 {
                skinLocation = player.getSkinTextureLocation();
                    //?} else {
                skinLocation = player.getSkin().texture();
                    //?}
                //?} else {
                skinLocation = player.getSkin().body().texturePath();
                //?}
                if ("auto".equals(modelType)) {
                    //? if <1.21.11 {
                        //? if <1.21 {
                    String vanillaModel = player.getModelName(); // "default" or "slim"
                    modelType = "slim".equals(vanillaModel) ? "slim" : "classic";
                        //?} else {
                    modelType = "slim".equals(player.getSkin().model().id()) ? "slim" : "classic";
                        //?}
                    //?} else {
                    modelType = player.getSkin().model()
                            == net.minecraft.world.entity.player.PlayerModelType.SLIM ? "slim" : "classic";
                    //?}
                }
            }
            if (skinLocation == null) {
                //? if <1.21.11 {
                    //? if <1.21 {
                skinLocation = new ResourceLocation("minecraft", "textures/entity/player/wide/steve.png");
                    //?} else {
                skinLocation = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/entity/player/wide/steve.png");
                    //?}
                //?} else {
                skinLocation = Identifier.fromNamespaceAndPath("minecraft", "textures/entity/player/wide/steve.png");
                //?}
                modelType = "classic";
            }

            playerWidget = addRenderableWidget(new PlayerWidget(
                    widgetX, widgetY, widgetWidth, widgetHeight,
                    skinLocation, null, null, modelType));
            playerWidget.setContext(PlayerWidget.WidgetContext.CAPE_MENU);
            int referenceX = widgetX + widgetWidth / 2;
            int referenceY = widgetY + widgetHeight / 2;
            playerWidget.setCustomReferencePoint(referenceX, referenceY);
            // Face the player's back to the camera so the cape is visible (same default as cape menu).
            playerWidget.setRotationState(20.0f, 200.0f);
        }
    }

    private void recalculateGrid() {
        int availW = (int) (this.width * 0.6);
        int availH = (int) (this.height * 0.65);
        int capeW = RESOLUTIONS[selectedResolution][0];
        int capeH = RESOLUTIONS[selectedResolution][1];

        double scaleX = (double) availW / capeW;
        double scaleY = (double) availH / capeH;
        displayScale = Math.min(scaleX, scaleY);

        gridW = (int) (capeW * displayScale);
        gridH = (int) (capeH * displayScale);
        gridX = 20 + (availW - gridW) / 2;
        gridY = 30 + (availH - gridH) / 2;
    }

    private void resetImagePosition() {
        int capeW = RESOLUTIONS[selectedResolution][0];
        int capeH = RESOLUTIONS[selectedResolution][1];

        // Scale so the source image covers the cape area (cover fit, using first frame dimensions)
        double scaleToFitW = (double) capeW / sourceImage.getWidth();
        double scaleToFitH = (double) capeH / srcFrameHeight;
        imgScale = Math.max(scaleToFitW, scaleToFitH);

        // Center
        imgOffsetX = (capeW - sourceImage.getWidth() * imgScale) / 2.0;
        imgOffsetY = (capeH - srcFrameHeight * imgScale) / 2.0;
        applySnap();
        previewDirty = true;
    }

    /**
     * Snap offset to the nearest grid position in cape-space pixels.
     */
    private void applySnap() {
        int snap = SNAP_SIZES[snapIndex];
        if (snap > 0) {
            imgOffsetX = Math.round(imgOffsetX / snap) * snap;
            imgOffsetY = Math.round(imgOffsetY / snap) * snap;
        }
    }

    /**
     * Advance the animation frame and update the source texture in-place.
     */
    private void tickAnimation() {
        long now = System.currentTimeMillis();
        if (animStartTime == 0) animStartTime = now;

        // ~100ms per frame (10 FPS preview)
        int newFrame = (int) ((now - animStartTime) / 100) % frameCount;
        if (newFrame != currentAnimFrame) {
            currentAnimFrame = newFrame;
            updateSourceFrame();
            previewDirty = true;
        }
    }

    /**
     * Copy the current animation frame's pixels into the source DynamicTexture and upload to GPU.
     */
    private void updateSourceFrame() {
        if (sourceDynTexture == null) return;
        NativeImage pixels = sourceDynTexture.getPixels();
        if (pixels == null) return;

        int srcW = sourceImage.getWidth();
        int srcYOffset = currentAnimFrame * srcFrameHeight;

        for (int y = 0; y < srcFrameHeight; y++) {
            for (int x = 0; x < srcW; x++) {
                int argb = sourceImage.getRGB(x, srcYOffset + y);
                int a = (argb >> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                int abgr = (a << 24) | (b << 16) | (g << 8) | r;
                MinecraftCompat.INSTANCE.setPixel(pixels, x, y, abgr);
            }
        }
        sourceDynTexture.upload();
    }

    /**
     * Compose a single frame at the current transform for the preview.
     */
    private BufferedImage composeFrame(int frameIndex) {
        int capeW = RESOLUTIONS[selectedResolution][0];
        int capeH = RESOLUTIONS[selectedResolution][1];
        int srcW = sourceImage.getWidth();

        int drawX = (int) imgOffsetX;
        int drawY = (int) imgOffsetY;
        int drawW = (int) (srcW * imgScale);
        int drawH = (int) (srcFrameHeight * imgScale);

        BufferedImage cape = new BufferedImage(capeW, capeH, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = cape.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        int srcY = frameIndex * srcFrameHeight;
        g.drawImage(sourceImage,
                drawX, drawY, drawX + drawW, drawY + drawH,
                0, srcY, srcW, srcY + srcFrameHeight,
                null);
        g.dispose();

        if (mirrorFrontBack) {
            mirrorBackToFront(cape, 0);
        }
        clearUnusedCapePixels(cape, 0, capeH);

        return cape;
    }

    /**
     * Clear pixels outside the cape body and elytra UV face regions.
     * At 1x (64x32):
     *   Cape body:                (0,0)-(22,17)
     *   Elytra top/bottom faces:  (24,0)-(44,2)
     *   Elytra side/front/back:   (22,2)-(46,22)
     * Everything else (corners, right margin, bottom margin) is cleared.
     */
    private void clearUnusedCapePixels(BufferedImage image, int yOffset, int frameH) {
        int capeW = image.getWidth();
        int scale = capeW / 64;

        int capeBodyMaxX = 22 * scale;
        int capeBodyMaxY = yOffset + 17 * scale;

        int elytraTopMinX = 24 * scale;
        int elytraTopMaxX = 44 * scale;
        int elytraTopMaxY = yOffset + 2 * scale;

        int elytraSideMinX = 22 * scale;
        int elytraSideMaxX = 46 * scale;
        int elytraSideMaxY = yOffset + 22 * scale;

        for (int y = yOffset; y < yOffset + frameH; y++) {
            for (int x = 0; x < capeW; x++) {
                boolean inCapeBody = (x < capeBodyMaxX && y < capeBodyMaxY);
                boolean inElytraTop = (x >= elytraTopMinX && x < elytraTopMaxX && y < elytraTopMaxY);
                boolean inElytraSide = (x >= elytraSideMinX && x < elytraSideMaxX
                        && y >= elytraTopMaxY && y < elytraSideMaxY);
                if (!inCapeBody && !inElytraTop && !inElytraSide) {
                    image.setRGB(x, y, 0x00000000);
                }
            }
        }
    }

    /**
     * Copy the front face onto the back face within a single frame so both sides match.
     * UI "Front" face: UV (1, 1) size (10, 16) at 1x scale
     * UI "Cape Back" face: UV (12, 1) size (10, 16) at 1x scale
     * @param image The cape image
     * @param yOffset Y offset for the frame within an animation strip
     */
    private void mirrorBackToFront(BufferedImage image, int yOffset) {
        int scale = image.getWidth() / 64;
        int srcX = 1 * scale;
        int dstX = 12 * scale;
        int y0 = yOffset + 1 * scale;
        int regionW = 10 * scale;
        int regionH = 16 * scale;

        for (int y = 0; y < regionH; y++) {
            for (int x = 0; x < regionW; x++) {
                int pixel = image.getRGB(srcX + x, y0 + y);
                image.setRGB(dstX + x, y0 + y, pixel);
            }
        }
    }

    @Override
    //? if <26.1.2 {
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    //?} else {
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    //?}
        // Advance animation frame for multi-frame GIFs
        if (frameCount > 1) {
            tickAnimation();
        }

        BackgroundRenderer.renderBackground(this, graphics, partialTick);

        // Title
        //? if <26.1.2 {
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);
        //?} else {
        graphics.centeredText(this.font, this.title, this.width / 2, 10, 0xFFFFFF);
        //?}

        // Instructions (centered under grid, underline only the action phrases)
        Component hintText = Component.literal("Drag to move").withStyle(style -> style.withUnderlined(true))
                .append(Component.literal(" · "))
                .append(Component.literal("Scroll to zoom").withStyle(style -> style.withUnderlined(true)));
        int hintX = gridX + (gridW - this.font.width(hintText)) / 2;
        //? if <26.1.2 {
        graphics.drawString(this.font, hintText, hintX, gridY + gridH + 8, 0xFFFFFF);
        //?} else {
        graphics.text(this.font, hintText, hintX, gridY + gridH + 8, 0xFFFFFF);
        //?}

        // Draw dark background for the grid area
        graphics.fill(gridX - 2, gridY - 2, gridX + gridW + 2, gridY + gridH + 2, 0xFF111111);

        // Enable scissor to clip the source image to the grid area
        graphics.enableScissor(gridX, gridY, gridX + gridW, gridY + gridH);

        // Render the source image behind the grid
        renderSourceImage(graphics);

        graphics.disableScissor();

        // Render cape grid overlay
        renderCapeGridOverlay(graphics);

        // Render preview panel
        renderPreview(graphics);

        // Resolution label
        int rightPanelX = gridX + (int) (this.width * 0.6) + 15;
        //? if <26.1.2 {
        graphics.drawString(this.font,
        //?} else {
        graphics.text(this.font,
        //?}
                Component.translatable("quickskin.cape.adjust_resolution"),
                rightPanelX, gridY - 14, 0xFFFF55);

        // Highlight selected resolution by drawing an outline around its button area
        int resBtnX = gridX + (int) (this.width * 0.6) + 15;
        for (int i = 0; i < RESOLUTIONS.length; i++) {
            if (i == selectedResolution) {
                int btnW2 = Math.min(this.width - resBtnX - 10, 120);
                int by = gridY + i * 24;
                //? if <1.21.11 {
                graphics.renderOutline(resBtnX - 1, by - 1, btnW2 + 2, 22, 0xFF55FF55);
                //?} else {
                drawOutline(graphics, resBtnX - 1, by - 1, btnW2 + 2, 22, 0xFF55FF55);
                //?}
            }
        }

        // Note about GIF resolution cap (to the right of the resolution buttons)
        if (frameCount > 1) {
            int btnW3 = Math.min(this.width - resBtnX - 10, 120);
            int noteX = resBtnX + btnW3 + 8;
            int noteY = gridY;
            int noteMaxW = Math.max(60, this.width - noteX - 5);
            //? if <26.1.2 {
            graphics.drawWordWrap(this.font,
            //?} else {
            graphics.textWithWordWrap(this.font,
            //?}
                    Component.literal("Max 4x for animated capes to optimize performance and avoid server lag."),
                    noteX, noteY, noteMaxW, 0xFFAA00);
        }

        //? if <26.1.2 {
        super.render(graphics, mouseX, mouseY, partialTick);
        //?} else {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        //?}
    }

    //? if <26.1.2 {
    private void renderSourceImage(GuiGraphics graphics) {
    //?} else {
    private void renderSourceImage(GuiGraphicsExtractor graphics) {
    //?}
        if (sourceTextureLocation == null) return;

        //? if <1.21.11 {
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        //?}

        // Convert cape-space offset to display-space (uses first frame dimensions)
        int drawX = gridX + (int) (imgOffsetX * displayScale);
        int drawY = gridY + (int) (imgOffsetY * displayScale);
        int drawW = (int) (sourceImage.getWidth() * imgScale * displayScale);
        int drawH = (int) (srcFrameHeight * imgScale * displayScale);

        GuiCompat.blit(graphics, sourceTextureLocation, drawX, drawY, drawW, drawH,
                0, 0, sourceImage.getWidth(), srcFrameHeight,
                sourceImage.getWidth(), srcFrameHeight);
    }

    //? if <26.1.2 {
    private void renderCapeGridOverlay(GuiGraphics graphics) {
    //?} else {
    private void renderCapeGridOverlay(GuiGraphicsExtractor graphics) {
    //?}
        // Cape template overlay — Outer border
        //? if <1.21.11 {
        graphics.renderOutline(gridX, gridY, gridW, gridH, 0xAAFFFFFF);
        //?} else {
        drawOutline(graphics, gridX, gridY, gridW, gridH, 0xAAFFFFFF);
        //?}

        // --- Cape body faces ---
        // Cape back: (1,1) size 10x16 at 1x
        int backX = gridX + (int) (1.0 / 64.0 * gridW);
        int backY = gridY + (int) (1.0 / 32.0 * gridH);
        int backW = (int) (10.0 / 64.0 * gridW);
        int backH = (int) (16.0 / 32.0 * gridH);
        // Cape front: (12,1) size 10x16 at 1x
        int frontX = gridX + (int) (12.0 / 64.0 * gridW);
        int frontW = (int) (10.0 / 64.0 * gridW);

        // --- Elytra wing (from ElytraModel: texOffs(22,0), box 10x20x2) ---
        // Elytra UV occupies (22,0)->(46,22) on the 64x32 texture
        int eTopX = gridX + (int) (24.0 / 64.0 * gridW);   // top face X
        int eTopW = (int) (10.0 / 64.0 * gridW);
        int eBotX = gridX + (int) (34.0 / 64.0 * gridW);   // bottom face X
        int eBotW = (int) (10.0 / 64.0 * gridW);
        int eLX = gridX + (int) (22.0 / 64.0 * gridW);     // left side X
        int eLY = gridY + (int) (2.0 / 32.0 * gridH);      // body strip Y
        int eLH = (int) (20.0 / 32.0 * gridH);             // body strip H
        int eBackX = gridX + (int) (36.0 / 64.0 * gridW);  // back/outer face X
        int eBackW = (int) (10.0 / 64.0 * gridW);

        // Key boundary positions for dimming
        int capeBodyRightX = gridX + (int) (22.0 / 64.0 * gridW);
        int capeBodyBottomY = gridY + (int) (17.0 / 32.0 * gridH);
        int elytraTopStripBottom = gridY + (int) (2.0 / 32.0 * gridH);
        int elytraBottomY = gridY + (int) (22.0 / 32.0 * gridH);
        int elytraRightX = gridX + (int) (46.0 / 64.0 * gridW);

        // Dim unused areas (5 rectangles covering everything outside cape body + elytra)
        // 1. Gap between cape body top-right and elytra top: (22,0)->(24,2)
        graphics.fill(capeBodyRightX, gridY, eTopX, elytraTopStripBottom, 0x88000000);
        // 2. Right of elytra top strip: (44,0)->(64,2)
        graphics.fill(eBotX + eBotW, gridY, gridX + gridW, elytraTopStripBottom, 0x88000000);
        // 3. Right of elytra body: (46,2)->(64,22)
        graphics.fill(elytraRightX, elytraTopStripBottom, gridX + gridW, elytraBottomY, 0x88000000);
        // 4. Below cape body, left of elytra: (0,17)->(22,22)
        graphics.fill(gridX, capeBodyBottomY, capeBodyRightX, elytraBottomY, 0x88000000);
        // 5. Full bottom strip: (0,22)->(64,32)
        graphics.fill(gridX, elytraBottomY, gridX + gridW, gridY + gridH, 0x88000000);

        // --- Cape outlines ---
        //? if <1.21.11 {
        graphics.renderOutline(backX, backY, backW, backH, 0xFF5599FF);
        //?} else {
        drawOutline(graphics, backX, backY, backW, backH, 0xFF5599FF);
        //?}
        if (!mirrorFrontBack) {
            //? if <1.21.11 {
            graphics.renderOutline(frontX, backY, frontW, backH, 0xFF55FF55);
            //?} else {
            drawOutline(graphics, frontX, backY, frontW, backH, 0xFF55FF55);
            //?}
        } else {
            graphics.fill(frontX, backY, frontX + frontW, backY + backH, 0x88000000);
        }

        // Dim all elytra faces except the back/outer wing (barely visible in-game)
        // Top + bottom faces: (24,0)->(44,2)
        graphics.fill(eTopX, gridY, eBotX + eBotW, elytraTopStripBottom, 0x88000000);
        // Left side + front/inner + right side: (22,2)->(36,22)
        graphics.fill(eLX, eLY, eBackX, eLY + eLH, 0x88000000);

        // --- Back/outer wing: wing silhouette outline + corner dimming ---
        // Wing shape from elytra default texture (MinecraftCapes convention)
        // Each row: {startCol, endColExclusive} in 10-wide face space
        // Top-right = shoulder cutoff, bottom-left = wing tip taper
        int[][] wingRows = {
                {0, 6}, {0, 7}, {0, 8}, {0, 8}, {0, 8},     // rows 0-4
                {0, 9}, {0, 9}, {0, 9}, {0, 9},              // rows 5-8
                {0, 10}, {0, 10}, {0, 10}, {0, 10}, {0, 10}, // rows 9-13
                {1, 10}, {1, 10}, {1, 10},                    // rows 14-16
                {2, 10}, {2, 10},                              // rows 17-18
                {3, 10}                                        // row 19
        };
        int eBackFaceY = eLY; // same Y as other elytra body faces
        double colW = eBackW / 10.0;
        double rowH = eLH / 20.0;
        int wingColor = 0xFFFFAA00;

        for (int row = 0; row < 20; row++) {
            int left = wingRows[row][0];
            int right = wingRows[row][1];
            int sLeft = eBackX + (int) (left * colW);
            int sRight = eBackX + (int) (right * colW);
            int sTop = eBackFaceY + (int) (row * rowH);
            int sBot = eBackFaceY + (int) ((row + 1) * rowH);

            // Dim transparent corner pixels
            if (left > 0) {
                graphics.fill(eBackX, sTop, sLeft, sBot, 0x88000000);
            }
            if (right < 10) {
                graphics.fill(sRight, sTop, eBackX + eBackW, sBot, 0x88000000);
            }

            // Left & right border lines (1px)
            graphics.fill(sLeft, sTop, sLeft + 1, sBot, wingColor);
            graphics.fill(sRight - 1, sTop, sRight, sBot, wingColor);

            // Top edge (first row)
            if (row == 0) {
                graphics.fill(sLeft, sTop, sRight, sTop + 1, wingColor);
            }
            // Bottom edge (last row)
            if (row == 19) {
                graphics.fill(sLeft, sBot - 1, sRight, sBot, wingColor);
            }

            // Horizontal staircase connectors where boundary changes
            if (row > 0) {
                int prevLeft = wingRows[row - 1][0];
                int prevRight = wingRows[row - 1][1];
                if (left != prevLeft) {
                    int sPrevLeft = eBackX + (int) (Math.min(left, prevLeft) * colW);
                    int sMaxLeft = eBackX + (int) (Math.max(left, prevLeft) * colW);
                    graphics.fill(sPrevLeft, sTop, sMaxLeft, sTop + 1, wingColor);
                }
                if (right != prevRight) {
                    int sMinRight = eBackX + (int) (Math.min(right, prevRight) * colW);
                    int sMaxRight = eBackX + (int) (Math.max(right, prevRight) * colW);
                    graphics.fill(sMinRight, sTop, sMaxRight, sTop + 1, wingColor);
                }
            }
        }

        // --- Labels ---
        // Cape front (inner side, against player's body)
        String label = Component.translatable("quickskin.cape.adjust_front").getString();
        int labelW = this.font.width(label);
        //? if <26.1.2 {
        graphics.drawString(this.font, label,
        //?} else {
        graphics.text(this.font, label,
        //?}
                backX + (backW - labelW) / 2, backY + backH / 2 - 4, 0xFF5599FF);
        // Cape back (outer side, visible from behind) — hidden when mirroring from front
        if (!mirrorFrontBack) {
            String frontLabel = Component.translatable("quickskin.cape.adjust_back").getString();
            int frontLabelW = this.font.width(frontLabel);
            //? if <26.1.2 {
            graphics.drawString(this.font, frontLabel,
            //?} else {
            graphics.text(this.font, frontLabel,
            //?}
                    frontX + (frontW - frontLabelW) / 2, backY + backH / 2 - 4, 0xFF55FF55);
        }
        // Elytra back/outer (centered in wing shape)
        String eOuterLabel = Component.translatable("quickskin.cape.adjust_elytra").getString();
        int eOuterLabelW = this.font.width(eOuterLabel);
        if (eBackW > eOuterLabelW + 4) {
            //? if <26.1.2 {
            graphics.drawString(this.font, eOuterLabel,
            //?} else {
            graphics.text(this.font, eOuterLabel,
            //?}
                    eBackX + (eBackW - eOuterLabelW) / 2, eLY + eLH / 2 - 4, 0xFFFFAA00);
        }
    }

    //? if <26.1.2 {
    private void renderPreview(GuiGraphics graphics) {
    //?} else {
    private void renderPreview(GuiGraphicsExtractor graphics) {
    //?}
        int rightPanelX = gridX + (int) (this.width * 0.6) + 15;
        // Account for resolution buttons + reset + snap button spacing
        int previewStartY = gridY + RESOLUTIONS.length * 24 + 100;
        int maxPreviewW = Math.min(100, this.width - rightPanelX - 10);

        // Compose texture if dirty
        if (previewDirty) {
            updatePreviewTexture();
            previewDirty = false;
        }

        // Keep the 3D player widget in sync with the latest composed preview.
        if (playerWidget != null && previewTextureLocation != null
                && previewTextureLocation != lastPlayerWidgetCape) {
            playerWidget.setCape(previewTextureLocation, null);
            lastPlayerWidgetCape = previewTextureLocation;
        }

        if (previewTextureLocation == null) return;

        int capeW = RESOLUTIONS[selectedResolution][0];
        int scale = capeW / 64;

        // --- Cape Back preview (the visible part in-game) ---
        int backPreviewW = maxPreviewW / 2 - 2;
        int backPreviewH = (int) (backPreviewW * 1.6); // 10:16 aspect

        if (backPreviewH + previewStartY > this.height - 40) {
            backPreviewH = this.height - 40 - previewStartY;
            backPreviewW = (int) (backPreviewH / 1.6);
        }

        String backLabel = Component.translatable("quickskin.cape.adjust_front").getString();
        //? if <26.1.2 {
        graphics.drawString(this.font, backLabel, rightPanelX, previewStartY - 12, 0xFF5599FF);
        //?} else {
        graphics.text(this.font, backLabel, rightPanelX, previewStartY - 12, 0xFF5599FF);
        //?}

        graphics.fill(rightPanelX - 1, previewStartY - 1,
                rightPanelX + backPreviewW + 1, previewStartY + backPreviewH + 1, 0xFF333333);

        //? if <1.21.11 {
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        //?}
        // Cape back UV: (1*s, 1*s) size (10*s, 16*s)
        GuiCompat.blit(graphics, previewTextureLocation,
                rightPanelX, previewStartY, backPreviewW, backPreviewH,
                1 * scale, 1 * scale, 10 * scale, 16 * scale, capeW, capeW / 2);

        // --- Cape Front preview (against the player's body) ---
        int frontX = rightPanelX + backPreviewW + 6;

        String frontLabel = Component.translatable("quickskin.cape.adjust_back").getString();
        //? if <26.1.2 {
        graphics.drawString(this.font, frontLabel, frontX, previewStartY - 12, 0xFF55FF55);
        //?} else {
        graphics.text(this.font, frontLabel, frontX, previewStartY - 12, 0xFF55FF55);
        //?}

        graphics.fill(frontX - 1, previewStartY - 1,
                frontX + backPreviewW + 1, previewStartY + backPreviewH + 1, 0xFF333333);

        //? if <1.21.11 {
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        //?}
        // Cape front UV: (12*s, 1*s) size (10*s, 16*s)
        GuiCompat.blit(graphics, previewTextureLocation,
                frontX, previewStartY, backPreviewW, backPreviewH,
                12 * scale, 1 * scale, 10 * scale, 16 * scale, capeW, capeW / 2);

        // --- Elytra preview (outer/back wing — the visible part in-game) ---
        // ElytraModel: texOffs(22,0), box 10x20x2, texture 64x32
        // Back/outer face UV: (36, 2) size (10, 20)
        int elytraPreviewW = backPreviewW;
        int elytraPreviewH = (int) (elytraPreviewW * 2.0); // 10:20 aspect

        int elytraY = previewStartY + backPreviewH + 18;
        if (elytraPreviewH + elytraY > this.height - 40) {
            elytraPreviewH = this.height - 40 - elytraY;
            elytraPreviewW = (int) (elytraPreviewH / 2.0);
        }

        if (elytraPreviewH > 4) {
            // Elytra outer (back face — what you see from behind)
            String eOuterLabel = Component.translatable("quickskin.cape.adjust_elytra").getString();
            //? if <26.1.2 {
            graphics.drawString(this.font, eOuterLabel, rightPanelX, elytraY - 12, 0xFFFFAA00);
            //?} else {
            graphics.text(this.font, eOuterLabel, rightPanelX, elytraY - 12, 0xFFFFAA00);
            //?}

            graphics.fill(rightPanelX - 1, elytraY - 1,
                    rightPanelX + elytraPreviewW + 1, elytraY + elytraPreviewH + 1, 0xFF333333);

            //? if <1.21.11 {
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            //?}
            // Back/outer wing UV: (36*s, 2*s) size (10*s, 20*s)
            GuiCompat.blit(graphics, previewTextureLocation,
                    rightPanelX, elytraY, elytraPreviewW, elytraPreviewH,
                    36 * scale, 2 * scale, 10 * scale, 20 * scale, capeW, capeW / 2);

            // Mask the preview corners to match the wing silhouette
            int[][] wingRows = {
                    {0, 6}, {0, 7}, {0, 8}, {0, 8}, {0, 8},
                    {0, 9}, {0, 9}, {0, 9}, {0, 9},
                    {0, 10}, {0, 10}, {0, 10}, {0, 10}, {0, 10},
                    {1, 10}, {1, 10}, {1, 10},
                    {2, 10}, {2, 10},
                    {3, 10}
            };
            double pColW = elytraPreviewW / 10.0;
            double pRowH = elytraPreviewH / 20.0;
            for (int row = 0; row < 20; row++) {
                int left = wingRows[row][0];
                int right = wingRows[row][1];
                int rTop = elytraY + (int) (row * pRowH);
                int rBot = elytraY + (int) ((row + 1) * pRowH);
                if (left > 0) {
                    graphics.fill(rightPanelX, rTop,
                            rightPanelX + (int) (left * pColW), rBot, 0xFF333333);
                }
                if (right < 10) {
                    graphics.fill(rightPanelX + (int) (right * pColW), rTop,
                            rightPanelX + elytraPreviewW, rBot, 0xFF333333);
                }
            }
        }
    }

    private void updatePreviewTexture() {
        // Preview shows the current animation frame (or the only frame for static images)
        BufferedImage cape = composeFrame(currentAnimFrame);

        if (previewTextureLocation != null) {
            Minecraft.getInstance().getTextureManager().release(previewTextureLocation);
        }

        NativeImage ni = convertToNativeImage(cape);
        //? if <1.21.11 {
        previewDynTexture = new DynamicTexture(ni);
        previewTextureLocation = Minecraft.getInstance().getTextureManager()
                .register("quickskin/cape_adjust_preview", previewDynTexture);
        //?} else {
        previewDynTexture = new DynamicTexture(() -> "quickskin_cape_adjust_preview", ni);
        previewTextureLocation = Identifier.fromNamespaceAndPath(QuickSkin.MOD_ID, "cape_adjust_preview");
        Minecraft.getInstance().getTextureManager().register(previewTextureLocation, previewDynTexture);
        //?}
    }

    /**
     * Compose the final cape image from source + positioning.
     * For animation strips, applies the same transform to each frame.
     */
    private BufferedImage composeCapeImage() {
        int capeW = RESOLUTIONS[selectedResolution][0];
        int capeH = RESOLUTIONS[selectedResolution][1];
        int srcW = sourceImage.getWidth();

        int drawX = (int) imgOffsetX;
        int drawY = (int) imgOffsetY;
        int drawW = (int) (srcW * imgScale);
        int drawH = (int) (srcFrameHeight * imgScale);

        BufferedImage cape = new BufferedImage(capeW, capeH * frameCount, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = cape.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        for (int i = 0; i < frameCount; i++) {
            int srcY = i * srcFrameHeight;
            int dstYOffset = i * capeH;
            // Draw this frame at the user's chosen position/scale in cape space
            g.drawImage(sourceImage,
                    drawX, dstYOffset + drawY, drawX + drawW, dstYOffset + drawY + drawH,
                    0, srcY, srcW, srcY + srcFrameHeight,
                    null);
        }
        g.dispose();

        if (mirrorFrontBack) {
            for (int i = 0; i < frameCount; i++) {
                mirrorBackToFront(cape, i * capeH);
            }
        }
        for (int i = 0; i < frameCount; i++) {
            clearUnusedCapePixels(cape, i * capeH, capeH);
        }

        return cape;
    }

    // --- Input handling ---

    @Override
    //? if <1.21.11 {
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
    //?} else {
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean focused) {
        if (super.mouseClicked(event, focused)) return true;
        double mouseX = GuiCompat.mouseX(event);
        double mouseY = GuiCompat.mouseY(event);
        int button = GuiCompat.mouseButton(event);
    //?}

        // Start dragging if clicked inside the grid area
        if (button == 0 && mouseX >= gridX && mouseX <= gridX + gridW
                && mouseY >= gridY && mouseY <= gridY + gridH) {
            isDragging = true;
            dragStartX = mouseX;
            dragStartY = mouseY;
            dragStartOffsetX = imgOffsetX;
            dragStartOffsetY = imgOffsetY;
            return true;
        }
        return false;
    }

    @Override
    //? if <1.21.11 {
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
    //?} else {
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        int button = GuiCompat.mouseButton(event);
    //?}
        if (button == 0 && isDragging) {
            isDragging = false;
            previewDirty = true;
            return true;
        }
        //? if <1.21.11 {
        return super.mouseReleased(mouseX, mouseY, button);
        //?} else {
        return super.mouseReleased(event);
        //?}
    }

    @Override
    //? if <1.21.11 {
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
    //?} else {
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dragX, double dragY) {
        double mouseX = GuiCompat.mouseX(event);
        double mouseY = GuiCompat.mouseY(event);
        int button = GuiCompat.mouseButton(event);
    //?}
        if (isDragging && button == 0) {
            // Convert display-space drag to cape-space
            double deltaX = (mouseX - dragStartX) / displayScale;
            double deltaY = (mouseY - dragStartY) / displayScale;
            imgOffsetX = dragStartOffsetX + deltaX;
            imgOffsetY = dragStartOffsetY + deltaY;
            applySnap();
            previewDirty = true;
            return true;
        }
        //? if <1.21.11 {
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        //?} else {
        return super.mouseDragged(event, dragX, dragY);
        //?}
    }

    @Override
    //? if <1.21 {
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
    //?} else {
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
    //?}
        if (mouseX >= gridX && mouseX <= gridX + gridW
                && mouseY >= gridY && mouseY <= gridY + gridH) {
            // Zoom centered on mouse position
            double oldScale = imgScale;
            //? if <1.21 {
            double zoomFactor = delta > 0 ? 1.15 : 1.0 / 1.15;
            //?} else {
            double zoomFactor = deltaY > 0 ? 1.15 : 1.0 / 1.15;
            //?}
            imgScale *= zoomFactor;

            // Clamp scale (using first frame dimensions)
            int capeW = RESOLUTIONS[selectedResolution][0];
            double minScale = Math.min((double) capeW / sourceImage.getWidth(),
                    (double) (capeW / 2) / srcFrameHeight) * 0.25;
            double maxScale = Math.max((double) capeW / sourceImage.getWidth(),
                    (double) (capeW / 2) / srcFrameHeight) * 8.0;
            imgScale = Math.max(minScale, Math.min(maxScale, imgScale));

            // Adjust offset to zoom toward mouse position
            double mouseInCapeX = (mouseX - gridX) / displayScale;
            double mouseInCapeY = (mouseY - gridY) / displayScale;
            imgOffsetX = mouseInCapeX - (mouseInCapeX - imgOffsetX) * (imgScale / oldScale);
            imgOffsetY = mouseInCapeY - (mouseInCapeY - imgOffsetY) * (imgScale / oldScale);
            applySnap();

            previewDirty = true;
            return true;
        }
        //? if <1.21 {
        return super.mouseScrolled(mouseX, mouseY, delta);
        //?} else {
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
        //?}
    }

    private void applyAndClose() {
        BufferedImage composedCape = composeCapeImage();
        completed = true;
        onApply.accept(composedCape);
        onClose();
    }

    @Override
    public void onClose() {
        BackgroundRenderer.cleanup();

        if (!completed) {
            completed = true;
            onCancel.run();
        }

        // Clean up textures
        if (sourceTextureLocation != null) {
            Minecraft.getInstance().getTextureManager().release(sourceTextureLocation);
            sourceTextureLocation = null;
        }
        if (previewTextureLocation != null) {
            Minecraft.getInstance().getTextureManager().release(previewTextureLocation);
            previewTextureLocation = null;
        }

        if (this.minecraft != null) {
            //? if <26.2 {
            this.minecraft.setScreen(parent);
            //?} else {
            this.minecraft.gui.setScreen(parent);
            //?}
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    //? if >=1.21.11 {
        //? if <26.1.2 {
    @Override
    protected void renderBlurredBackground(GuiGraphics guiGraphics) {
        // Disable the default blur effect - we have our own custom background
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Disable the default dark background overlay - we render our own custom background
    }
        //?} else {
    @Override
    protected void extractBlurredBackground(GuiGraphicsExtractor guiGraphics) {
        // Disable the default blur effect - we have our own custom background
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Disable the default dark background overlay - we render our own custom background
    }
        //?}
    //?}

    //? if >=1.21.11 {
    /**
     * Draws an outline immediately using fill calls.
     */
    private static void drawOutline(
            //? if <26.1.2 {
            GuiGraphics graphics,
            //?} else {
            GuiGraphicsExtractor graphics,
            //?}
            int x, int y, int width, int height, int color) {
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y + 1, x + 1, y + height - 1, color);
        graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }
    //?}

    /**
     * Convert BufferedImage to NativeImage for texture registration
     */
    private static NativeImage convertToNativeImage(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        NativeImage nativeImage = new NativeImage(width, height, false);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = image.getRGB(x, y);
                int a = (argb >> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                int abgr = (a << 24) | (b << 16) | (g << 8) | r;
                MinecraftCompat.INSTANCE.setPixel(nativeImage, x, y, abgr);
            }
        }
        return nativeImage;
    }
}
