package com.quickskin.mod.client.gui.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.gui.panel.ActionButtonsPanel;
import com.quickskin.mod.client.gui.panel.LinkButtonsPanel;
import com.quickskin.mod.client.gui.panel.PlayerPreviewPanel;
import com.quickskin.mod.client.gui.panel.SkinListPanel;
import com.quickskin.mod.client.gui.util.FileDialogHelper;
import com.quickskin.mod.client.gui.util.GuiScaleManager;
import com.quickskin.mod.client.gui.util.SkinImporter;
import com.quickskin.mod.client.gui.widget.SkinEntry;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.TextureQuality;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;

/**
 * Main skin selection menu for QuickSkin
 * Opens when K key is pressed
 */
@Environment(EnvType.CLIENT)
public class PlayerSkinMenuScreen extends Screen {

    @Nullable
    private final Screen parent;

    // Panels
    private SkinListPanel skinListPanel;
    private PlayerPreviewPanel playerPreviewPanel;
    private ActionButtonsPanel actionButtonsPanel;
    private LinkButtonsPanel linkButtonsPanel;

    // Panel dimensions
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;

    // GUI scale management
    private boolean guiScaleForced = false;
    private boolean isClosing = false;

    // Constants
    private static final int MIN_PANEL_WIDTH = 340;
    private static final int MAX_PANEL_WIDTH = 600;
    private static final int MIN_PANEL_HEIGHT = 280;

    // --- NEW ---: Constants for the background effect
    private static final ResourceLocation STAR_PATTERN_TEXTURE = new ResourceLocation(QuickSkin.MOD_ID, "textures/gui/background/star_pattern.png");
    private static final ResourceLocation VIGNETTE_LOCATION = new ResourceLocation("textures/misc/vignette.png");


    public PlayerSkinMenuScreen(@Nullable Screen parent) {
        super(Component.literal("QuickSkin"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        // Force GUI scale to 2 for consistent appearance
        if (!guiScaleForced && !isClosing) {
            guiScaleForced = true;
            int optimalScale = GuiScaleManager.getOptimalMenuScale();
            if (GuiScaleManager.setMenuGuiScale(optimalScale)) {
                // Scale was changed and resizeDisplay() was called, which will trigger init() again
                return;
            }
        }

        super.init();
        clearWidgets();

        // Calculate panel dimensions based on screen size
        calculatePanelDimensions();

        // Use consistent sizing values
        int scaledPadding = 6;
        int scaledSpacing = 4;
        int scaledComponentHeight = 20;

        // Calculate panel areas
        int leftPanelWidth = (int) (panelWidth * 0.6f);
        int rightPanelWidth = (int) (panelWidth * 0.35f);

        int componentX = panelX + scaledPadding;
        int yPos = panelY + scaledPadding + scaledComponentHeight + scaledPadding;

        // Calculate list height with proper spacing
        int topSectionHeight = scaledPadding + scaledComponentHeight + scaledPadding + scaledComponentHeight + scaledSpacing;
        int bottomSectionHeight = (scaledComponentHeight * 3) + (scaledSpacing * 2) + scaledPadding;
        int listHeight = panelHeight - topSectionHeight - bottomSectionHeight;

        // Create Skin List Panel (left side)
        skinListPanel = new SkinListPanel(
                componentX,
                yPos,
                leftPanelWidth,
                listHeight,
                this.minecraft,
                this::onSkinSelected
        );
        skinListPanel.init(this);

        // Calculate bottom section dimensions first (needed for player preview panel)
        int fullWidthX = panelX + scaledPadding;
        int fullComponentWidth = panelWidth - (scaledPadding * 2);
        int fourButtonWidth = (fullComponentWidth - (scaledSpacing * 3)) / 4;

        // Calculate where action buttons will be
        int actionButtonsBottomY = panelY + panelHeight - scaledPadding;
        int actionPanelHeight = (scaledComponentHeight * 2) + scaledSpacing;

        // Model buttons row (Row 3: above Import/HD/Skin/Cape buttons)
        int modelButtonsY = actionButtonsBottomY - actionPanelHeight - scaledComponentHeight - scaledSpacing;
        int modelButtonsX = fullWidthX + (fourButtonWidth + scaledSpacing) * 3;
        int modelButtonsTotalWidth = fourButtonWidth;

        // Create Player Preview Panel (right side)
        int playerWidgetX = panelX + panelWidth - rightPanelWidth - scaledPadding;
        int playerWidgetY = yPos;
        int availableHeightForWidget = panelHeight - topSectionHeight - bottomSectionHeight;

        playerPreviewPanel = new PlayerPreviewPanel(
                playerWidgetX,
                playerWidgetY,
                rightPanelWidth,
                availableHeightForWidget
        );
        playerPreviewPanel.initPlayerWidget(this);

        // Create model buttons positioned above the cape button
        playerPreviewPanel.initModelButtons(
                this,
                modelButtonsX,
                modelButtonsY,
                modelButtonsTotalWidth,
                scaledComponentHeight,
                scaledSpacing
        );

        // Create Action Buttons Panel (bottom)
        int bottomY = actionButtonsBottomY - (scaledComponentHeight * 2) - scaledSpacing;

        ActionButtonsPanel.ActionCallbacks callbacks = new ActionButtonsPanel.ActionCallbacks(
                this::openImportDialog,
                () -> {
                    // HD Skin Website
                    if (this.minecraft != null) {
                        this.minecraft.options.chatLinksPrompt().set(false);
                    }
                },
                () -> {
                    // Skin Website
                    if (this.minecraft != null) {
                        this.minecraft.options.chatLinksPrompt().set(false);
                    }
                },
                () -> {
                    // TODO: Open cape selection screen
                },
                this::onClose
        );

        actionButtonsPanel = new ActionButtonsPanel(
                fullWidthX,
                bottomY,
                fullComponentWidth,
                actionPanelHeight,
                callbacks
        );
        actionButtonsPanel.init(this, callbacks);

        // Create Link Buttons Panel (top-right)
        int linkButtonY = panelY + scaledPadding;
        int linkPanelWidth = (scaledComponentHeight + scaledSpacing) * 4;
        int linkPanelX = panelX + panelWidth - linkPanelWidth - scaledPadding + scaledSpacing;

        linkButtonsPanel = new LinkButtonsPanel(
                linkPanelX,
                linkButtonY,
                linkPanelWidth,
                scaledComponentHeight
        );
        linkButtonsPanel.init(this);
    }

    /**
     * Calculate panel dimensions based on screen size
     * Uses FIXED sizes since we're forcing GUI scale to 3
     */
    private void calculatePanelDimensions() {
        // Calculate panel dimensions as percentages of screen for flexible sizing
        int desiredWidth = (int)(this.width * 0.5f);
        int desiredHeight = (int)(this.height * 0.8f);

        panelWidth = Mth.clamp(
                desiredWidth,
                MIN_PANEL_WIDTH,
                Math.min(MAX_PANEL_WIDTH, this.width - 80)
        );

        panelHeight = Mth.clamp(
                desiredHeight,
                MIN_PANEL_HEIGHT,
                this.height - 80
        );

        // Adjust panel size if components don't fit
        int minRequiredHeight = calculateMinRequiredHeight();
        if (panelHeight < minRequiredHeight) {
            panelHeight = Math.min(minRequiredHeight, this.height - 40);
        }

        int minRequiredWidth = calculateMinRequiredWidth();
        if (panelWidth < minRequiredWidth) {
            panelWidth = Math.min(minRequiredWidth, this.width - 40);
        }

        // Center the panel
        panelX = (this.width - panelWidth) / 2;
        panelY = (this.height - panelHeight) / 2;
    }

    /**
     * Calculate minimum required height for all components
     */
    private int calculateMinRequiredHeight() {
        int scaledPadding = 6;
        int scaledComponentHeight = 20;
        int scaledSpacing = 4;
        // Title + username row + list (min 3 entries) + 3 button rows
        return scaledPadding * 4 + scaledComponentHeight * 7 + scaledSpacing * 4 + 120; // 120 for min list height
    }

    /**
     * Calculate minimum required width for all components
     */
    private int calculateMinRequiredWidth() {
        int scaledPadding = 6;
        int scaledSpacing = 4;
        // Need space for left panel + right panel (player widget) + padding
        return 220 + 150 + scaledPadding * 3 + scaledSpacing * 2;
    }

    // --- NEW ---: Method to render the animated background
    /**
     * Renders a moving star pattern background similar to the effect on the example website.
     * This includes a tiled, scrolling texture and a vignette overlay for depth.
     */
    private void renderBackgroundEffects(GuiGraphics graphics, float partialTick) {
        // 1. Fill with solid black as a base layer.
        graphics.fill(0, 0, this.width, this.height, 0xFF000000);

        // 2. Render the moving star pattern.
        renderStarPattern(graphics, partialTick);

        // 3. Render a vignette overlay for a darker, focused feel around the edges.
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(0.0F, 0.0F, 0.0F, 0.75F);
        // Stretch Minecraft's vignette texture to cover the entire screen.
        graphics.blit(VIGNETTE_LOCATION, 0, 0, 0, 0.0F, 0.0F, this.width, this.height, this.width, this.height);

        // 4. Reset render state to avoid affecting other GUI elements.
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * Renders the animated star pattern
     */
    private void renderStarPattern(GuiGraphics graphics, float partialTick) {
        // Actual texture size
        int textureSize = 1024;
        // The size to render each tile (smaller = more stars visible).
        int tileSize = 55;
        // Animation speed: pixels per second
        double pixelsPerSecond = 8.0;

        // Use Minecraft's smooth game time (ticks + partial tick) for perfectly smooth animation
        int tickCount = this.minecraft != null ? this.minecraft.gui.getGuiTicks() : 0;
        double smoothTime = (tickCount + partialTick) / 20.0; // Convert to seconds
        double offset = (smoothTime * pixelsPerSecond) % tileSize;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 0.15F);

        // Calculate how many tiles are needed to cover the screen
        int xTiles = Mth.ceil((float) this.width / tileSize) + 2;
        int yTiles = Mth.ceil((float) this.height / tileSize) + 1;

        var pose = graphics.pose();
        pose.pushPose();

        for (int y = 0; y < yTiles; ++y) {
            for (int x = 0; x < xTiles; ++x) {
                // Draw each tile, applying the horizontal scroll offset.
                double drawX = x * tileSize - offset;
                double drawY = y * tileSize;

                // Draw the full texture scaled down to tileSize x tileSize
                pose.pushPose();
                pose.translate(drawX, drawY, 0);
                pose.scale(tileSize / (float)textureSize, tileSize / (float)textureSize, 1.0f);
                graphics.blit(STAR_PATTERN_TEXTURE, 0, 0, 0, 0.0f, 0.0f, textureSize, textureSize, textureSize, textureSize);
                pose.popPose();
            }
        }

        pose.popPose();

        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Render the animated background
        this.renderBackgroundEffects(graphics, partialTick);

        // Render panel background (frosted glass effect)
        renderPanel(graphics);

        // Render title
        graphics.drawCenteredString(
                this.font,
                this.title,
                this.width / 2,
                panelY + 10,
                0xFFFFFF
        );

        // Render widgets (buttons, etc.)
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * Render the main panel with frosted glass effect
     */
    private void renderPanel(GuiGraphics graphics) {
        // Panel background (dark semi-transparent)
        graphics.fill(
                panelX, panelY,
                panelX + panelWidth, panelY + panelHeight,
                0xB0000000
        );

        // Panel outline (subtle white)
        // Top
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 1, 0x60FFFFFF);
        // Bottom
        graphics.fill(panelX, panelY + panelHeight - 1, panelX + panelWidth, panelY + panelHeight, 0x60FFFFFF);
        // Left (shortened by 1px at top and bottom to avoid corner overlap)
        graphics.fill(panelX, panelY + 1, panelX + 1, panelY + panelHeight - 1, 0x60FFFFFF);
        // Right (shortened by 1px at top and bottom to avoid corner overlap)
        graphics.fill(panelX + panelWidth - 1, panelY + 1, panelX + panelWidth, panelY + panelHeight - 1, 0x60FFFFFF);
    }

    @Override
    public void removed() {
        super.removed();
        // GUI scale restoration is handled in onClose()
    }

    @Override
    public void onClose() {
        // Restore original GUI scale BEFORE switching to parent screen
        if (guiScaleForced) {
            isClosing = true;
            guiScaleForced = false;
            GuiScaleManager.restoreOriginalGuiScale();
            QuickSkin.LOGGER.info("PlayerSkinMenuScreen.onClose() - GUI scale restored");
        }

        // Return to parent screen (or null to return to game)
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        // Don't pause game when this screen is open
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Allow ESC to close
        if (keyCode == 256) { // ESC key
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Handle debug positioning mode
        if (com.quickskin.mod.client.rendering.PlayerModelRenderer.handleDebugMousePressed((int)mouseX, (int)mouseY, button)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        // Handle debug positioning mode
        if (com.quickskin.mod.client.rendering.PlayerModelRenderer.handleDebugMouseDragged((int)mouseX, (int)mouseY, button)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        // Handle debug positioning mode
        if (com.quickskin.mod.client.rendering.PlayerModelRenderer.handleDebugMouseReleased((int)mouseX, (int)mouseY, button)) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        QuickSkin.LOGGER.info("Files dropped: {}", files.size());

        // Filter for PNG files
        List<Path> pngFiles = files.stream()
                .filter(path -> path.toString().toLowerCase().endsWith(".png"))
                .toList();

        if (pngFiles.isEmpty()) {
            QuickSkin.LOGGER.warn("No PNG files in drop");
            return;
        }

        QuickSkin.LOGGER.info("Processing {} PNG files", pngFiles.size());

        // Import all PNG files
        if (this.minecraft != null) {
            this.minecraft.execute(() -> {
                List<AssetMetadata> imported = SkinImporter.importSkins(pngFiles.toArray(new Path[0]));

                if (!imported.isEmpty()) {
                    QuickSkin.LOGGER.info("Successfully imported {} skins", imported.size());

                    // Reload the skin list
                    refreshSkinList();

                    // Auto-select the first imported skin
                    if (skinListPanel != null && !imported.isEmpty()) {
                        AssetMetadata firstImported = imported.get(0);
                        skinListPanel.setSelected(firstImported);
                    }
                }
            });
        }
    }

    /**
     * Called when a skin is selected from the list
     */
    public void onSkinSelected(SkinEntry entry) {
        if (playerPreviewPanel != null && entry != null) {
            AssetMetadata metadata = entry.getMetadata();

            // Update player preview with selected skin
            playerPreviewPanel.updateSkin(
                    metadata,
                    LocalAssetManager.getInstance().getTextureLocation(metadata.hash(), TextureQuality.FULL)
            );
        }
    }

    /**
     * Get the font renderer
     */
    public net.minecraft.client.gui.Font getFont() {
        return this.font;
    }

    /**
     * Public wrapper for addRenderableWidget to allow panels to add widgets
     */
    public <T extends net.minecraft.client.gui.components.events.GuiEventListener & net.minecraft.client.gui.components.Renderable & net.minecraft.client.gui.narration.NarratableEntry> T registerWidget(T widget) {
        return this.addRenderableWidget(widget);
    }

    /**
     * Open file dialog to import a skin
     */
    private void openImportDialog() {
        FileDialogHelper.openSkinFileDialog("Select Skin File", this::handleSkinImport);
    }

    /**
     * Handle imported skin file
     */
    private void handleSkinImport(Path filePath) {
        if (filePath == null) {
            return;
        }

        QuickSkin.LOGGER.info("Importing skin: {}", filePath);

        // Import on main thread (Minecraft.getInstance().execute runs on main thread)
        if (this.minecraft != null) {
            this.minecraft.execute(() -> {
                AssetMetadata metadata = SkinImporter.importSkin(filePath);
                if (metadata != null) {
                    QuickSkin.LOGGER.info("Successfully imported skin: {}", metadata.friendlyName());

                    // Reload the skin list
                    refreshSkinList();

                    // Auto-select the imported skin
                    if (skinListPanel != null) {
                        skinListPanel.setSelected(metadata);
                    }
                } else {
                    QuickSkin.LOGGER.error("Failed to import skin: {}", filePath);
                    // TODO: Show error message to user
                }
            });
        }
    }

    /**
     * Refresh the skin list after importing
     */
    private void refreshSkinList() {
        if (skinListPanel != null) {
            skinListPanel.refresh();
        }
    }
}