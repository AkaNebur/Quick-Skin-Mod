package com.quickskin.mod.client.gui.screen;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.gui.util.BackgroundRenderer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

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

    // Source image texture
    private ResourceLocation sourceTextureLocation;
    private DynamicTexture sourceDynTexture;

    // Preview texture (cape back portion)
    private ResourceLocation previewTextureLocation;
    private DynamicTexture previewDynTexture;
    private boolean previewDirty = true;

    public CapeAdjustScreen(Screen parent, BufferedImage sourceImage, Consumer<BufferedImage> onApply) {
        super(Component.translatable("quickskin.cape.adjust_title"));
        this.parent = parent;
        this.sourceImage = sourceImage;
        this.onApply = onApply;
    }

    @Override
    protected void init() {
        // Register source image as texture
        if (sourceTextureLocation != null) {
            Minecraft.getInstance().getTextureManager().release(sourceTextureLocation);
        }
        NativeImage nativeImage = convertToNativeImage(sourceImage);
        sourceDynTexture = new DynamicTexture(nativeImage);
        sourceTextureLocation = Minecraft.getInstance().getTextureManager()
                .register("quickskin/cape_adjust_source", sourceDynTexture);

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

        // Resolution buttons
        int resY = gridY;
        for (int i = 0; i < RESOLUTIONS.length; i++) {
            final int idx = i;
            Button btn = Button.builder(Component.literal(RESOLUTION_LABELS[i]), b -> {
                selectedResolution = idx;
                recalculateGrid();
                previewDirty = true;
            }).bounds(rightPanelX, resY, btnW, 20).build();
            this.addRenderableWidget(btn);
            resY += 24;
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

        resetImagePosition();
    }

    private void resetImagePosition() {
        int capeW = RESOLUTIONS[selectedResolution][0];
        int capeH = RESOLUTIONS[selectedResolution][1];

        // Scale so the source image covers the cape area (cover fit)
        double scaleToFitW = (double) capeW / sourceImage.getWidth();
        double scaleToFitH = (double) capeH / sourceImage.getHeight();
        imgScale = Math.max(scaleToFitW, scaleToFitH);

        // Center
        imgOffsetX = (capeW - sourceImage.getWidth() * imgScale) / 2.0;
        imgOffsetY = (capeH - sourceImage.getHeight() * imgScale) / 2.0;
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

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        BackgroundRenderer.renderBackground(this, graphics, partialTick);

        // Title
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);

        // Instructions
        graphics.drawString(this.font,
                Component.translatable("quickskin.cape.adjust_hint"),
                gridX, gridY + gridH + 8, 0xAAAAAA);

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
        graphics.drawString(this.font,
                Component.translatable("quickskin.cape.adjust_resolution"),
                rightPanelX, gridY - 14, 0xFFFF55);

        // Highlight selected resolution by drawing an outline around its button area
        int resBtnX = gridX + (int) (this.width * 0.6) + 15;
        for (int i = 0; i < RESOLUTIONS.length; i++) {
            if (i == selectedResolution) {
                int btnW2 = Math.min(this.width - resBtnX - 10, 120);
                int by = gridY + i * 24;
                graphics.renderOutline(resBtnX - 1, by - 1, btnW2 + 2, 22, 0xFF55FF55);
            }
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderSourceImage(GuiGraphics graphics) {
        if (sourceTextureLocation == null) return;

        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

        // Convert cape-space offset to display-space
        int drawX = gridX + (int) (imgOffsetX * displayScale);
        int drawY = gridY + (int) (imgOffsetY * displayScale);
        int drawW = (int) (sourceImage.getWidth() * imgScale * displayScale);
        int drawH = (int) (sourceImage.getHeight() * imgScale * displayScale);

        graphics.blit(sourceTextureLocation, drawX, drawY, drawW, drawH,
                0, 0, sourceImage.getWidth(), sourceImage.getHeight(),
                sourceImage.getWidth(), sourceImage.getHeight());
    }

    private void renderCapeGridOverlay(GuiGraphics graphics) {
        // Cape template overlay
        // Outer border
        graphics.renderOutline(gridX, gridY, gridW, gridH, 0xAAFFFFFF);

        // Cape back area (the main visible part in-game)
        // At vanilla 64×32: (1,1) to (11,17) → width=10, height=16
        int backX = gridX + (int) (1.0 / 64.0 * gridW);
        int backY = gridY + (int) (1.0 / 32.0 * gridH);
        int backW = (int) (10.0 / 64.0 * gridW);
        int backH = (int) (16.0 / 32.0 * gridH);

        // Cape front area
        int frontX = gridX + (int) (12.0 / 64.0 * gridW);
        int frontW = (int) (10.0 / 64.0 * gridW);

        // Dim everything outside the back and front areas
        // Top strip (full width)
        graphics.fill(gridX, gridY, gridX + gridW, backY, 0x88000000);
        // Bottom strip (full width)
        graphics.fill(gridX, backY + backH, gridX + gridW, gridY + gridH, 0x88000000);
        // Left of back
        graphics.fill(gridX, backY, backX, backY + backH, 0x88000000);
        // Between back and front
        graphics.fill(backX + backW, backY, frontX, backY + backH, 0x88000000);
        // Right of front
        graphics.fill(frontX + frontW, backY, gridX + gridW, backY + backH, 0x88000000);

        // Green outline around cape back
        graphics.renderOutline(backX, backY, backW, backH, 0xFF55FF55);

        // Blue outline around cape front
        graphics.renderOutline(frontX, backY, frontW, backH, 0xFF5599FF);

        // Label for cape back
        String label = Component.translatable("quickskin.cape.adjust_back").getString();
        int labelW = this.font.width(label);
        graphics.drawString(this.font, label,
                backX + (backW - labelW) / 2, backY + backH / 2 - 4, 0xFF55FF55);

        // Label for cape front
        String frontLabel = Component.translatable("quickskin.cape.adjust_front").getString();
        int frontLabelW = this.font.width(frontLabel);
        graphics.drawString(this.font, frontLabel,
                frontX + (frontW - frontLabelW) / 2, backY + backH / 2 - 4, 0xFF5599FF);
    }

    private void renderPreview(GuiGraphics graphics) {
        int rightPanelX = gridX + (int) (this.width * 0.6) + 15;
        // Account for resolution buttons + reset + snap button spacing
        int previewStartY = gridY + RESOLUTIONS.length * 24 + 80;
        int maxPreviewW = Math.min(100, this.width - rightPanelX - 10);

        // Compose texture if dirty
        if (previewDirty) {
            updatePreviewTexture();
            previewDirty = false;
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

        String backLabel = Component.translatable("quickskin.cape.adjust_back").getString();
        graphics.drawString(this.font, backLabel, rightPanelX, previewStartY - 12, 0xFF55FF55);

        graphics.fill(rightPanelX - 1, previewStartY - 1,
                rightPanelX + backPreviewW + 1, previewStartY + backPreviewH + 1, 0xFF333333);

        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        // Cape back UV: (1*s, 1*s) size (10*s, 16*s)
        graphics.blit(previewTextureLocation,
                rightPanelX, previewStartY, backPreviewW, backPreviewH,
                1 * scale, 1 * scale, 10 * scale, 16 * scale, capeW, capeW / 2);

        // --- Cape Front preview (against the player's body) ---
        int frontX = rightPanelX + backPreviewW + 6;

        String frontLabel = Component.translatable("quickskin.cape.adjust_front").getString();
        graphics.drawString(this.font, frontLabel, frontX, previewStartY - 12, 0xFFAAAAAA);

        graphics.fill(frontX - 1, previewStartY - 1,
                frontX + backPreviewW + 1, previewStartY + backPreviewH + 1, 0xFF333333);

        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        // Cape front UV: (12*s, 1*s) size (10*s, 16*s)
        graphics.blit(previewTextureLocation,
                frontX, previewStartY, backPreviewW, backPreviewH,
                12 * scale, 1 * scale, 10 * scale, 16 * scale, capeW, capeW / 2);
    }

    private void updatePreviewTexture() {
        BufferedImage cape = composeCapeImage();

        if (previewTextureLocation != null) {
            Minecraft.getInstance().getTextureManager().release(previewTextureLocation);
        }

        NativeImage ni = convertToNativeImage(cape);
        previewDynTexture = new DynamicTexture(ni);
        previewTextureLocation = Minecraft.getInstance().getTextureManager()
                .register("quickskin/cape_adjust_preview", previewDynTexture);
    }

    /**
     * Compose the final cape image from source + positioning
     */
    private BufferedImage composeCapeImage() {
        int capeW = RESOLUTIONS[selectedResolution][0];
        int capeH = RESOLUTIONS[selectedResolution][1];

        BufferedImage cape = new BufferedImage(capeW, capeH, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = cape.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // Draw source image at the user's chosen position/scale in cape space
        int drawX = (int) imgOffsetX;
        int drawY = (int) imgOffsetY;
        int drawW = (int) (sourceImage.getWidth() * imgScale);
        int drawH = (int) (sourceImage.getHeight() * imgScale);
        g.drawImage(sourceImage, drawX, drawY, drawW, drawH, null);
        g.dispose();

        return cape;
    }

    // --- Input handling ---

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;

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
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && isDragging) {
            isDragging = false;
            previewDirty = true;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
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
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX >= gridX && mouseX <= gridX + gridW
                && mouseY >= gridY && mouseY <= gridY + gridH) {
            // Zoom centered on mouse position
            double oldScale = imgScale;
            double zoomFactor = delta > 0 ? 1.15 : 1.0 / 1.15;
            imgScale *= zoomFactor;

            // Clamp scale
            int capeW = RESOLUTIONS[selectedResolution][0];
            double minScale = Math.min((double) capeW / sourceImage.getWidth(),
                    (double) (capeW / 2) / sourceImage.getHeight()) * 0.25;
            double maxScale = Math.max((double) capeW / sourceImage.getWidth(),
                    (double) (capeW / 2) / sourceImage.getHeight()) * 8.0;
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
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private void applyAndClose() {
        BufferedImage composedCape = composeCapeImage();
        onApply.accept(composedCape);
        onClose();
    }

    @Override
    public void onClose() {
        BackgroundRenderer.cleanup();

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
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

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
                nativeImage.setPixelRGBA(x, y, abgr);
            }
        }
        return nativeImage;
    }
}
