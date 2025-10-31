package com.quickskin.mod.client.gui.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.gui.util.FileDialogHelper;
import com.quickskin.mod.client.gui.util.SkinImporter;
import com.quickskin.mod.client.gui.widget.PlayerWidget;
import com.quickskin.mod.client.gui.widget.SkinEntry;
import com.quickskin.mod.client.gui.widget.SkinListWidget;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.TextureQuality;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
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

    // Player preview widget
    @Nullable
    private PlayerWidget playerWidget;

    // Skin list widget
    @Nullable
    private SkinListWidget skinListWidget;

    // Model type buttons
    private Button autoModelButton;
    private Button slimModelButton;
    private Button classicModelButton;
    private String currentModelType = "classic";

    // Action buttons
    private Button importButton;
    private Button capeButton;
    private Button settingsButton;

    // Panel dimensions
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;

    // Constants
    private static final int MIN_PANEL_WIDTH = 340;
    private static final int MAX_PANEL_WIDTH = 600;
    private static final int PANEL_HEIGHT = 400;

    public PlayerSkinMenuScreen(@Nullable Screen parent) {
        super(Component.literal("QuickSkin"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        // Calculate panel dimensions based on screen size
        calculatePanelDimensions();

        // Create skin list on the left side
        int skinListWidth = 220;
        int skinListHeight = panelHeight - 100;
        int skinListX = panelX + 10;
        int skinListY = panelY + 40;

        skinListWidget = new SkinListWidget(
            this,
            this.minecraft,
            skinListWidth,
            skinListHeight,
            skinListY,
            36 // Entry height
        );
        skinListWidget.setLeftPos(skinListX);
        skinListWidget.setRenderBackground(false);
        skinListWidget.setRenderTopAndBottom(false);
        this.addRenderableWidget(skinListWidget);

        // Load skins from LocalAssetManager
        loadSkins();

        // Create player widget in right section
        int playerWidgetWidth = 180;
        int playerWidgetHeight = 260;
        int playerWidgetX = panelX + panelWidth - playerWidgetWidth - 20;
        int playerWidgetY = panelY + 40;

        playerWidget = new PlayerWidget(
            playerWidgetX,
            playerWidgetY,
            playerWidgetWidth,
            playerWidgetHeight,
            null, // Will use default Steve skin
            null, // No cape initially
            "classic" // Default model type
        );
        this.addRenderableWidget(playerWidget);

        // Add model type buttons below player widget
        int modelButtonWidth = 56;
        int modelButtonHeight = 18;
        int modelButtonY = playerWidgetY + playerWidgetHeight + 5;
        int modelButtonSpacing = 2;
        int totalModelButtonWidth = (modelButtonWidth * 3) + (modelButtonSpacing * 2);
        int modelButtonStartX = playerWidgetX + (playerWidgetWidth - totalModelButtonWidth) / 2;

        autoModelButton = Button.builder(Component.literal("Auto"), button -> {
            setModelType("auto");
        }).bounds(modelButtonStartX, modelButtonY, modelButtonWidth, modelButtonHeight).build();
        this.addRenderableWidget(autoModelButton);

        slimModelButton = Button.builder(Component.literal("Slim"), button -> {
            setModelType("slim");
        }).bounds(modelButtonStartX + modelButtonWidth + modelButtonSpacing, modelButtonY, modelButtonWidth, modelButtonHeight).build();
        this.addRenderableWidget(slimModelButton);

        classicModelButton = Button.builder(Component.literal("Classic"), button -> {
            setModelType("classic");
        }).bounds(modelButtonStartX + (modelButtonWidth + modelButtonSpacing) * 2, modelButtonY, modelButtonWidth, modelButtonHeight).build();
        this.addRenderableWidget(classicModelButton);

        // Add action buttons below the skin list
        int actionButtonWidth = 70;
        int actionButtonHeight = 18;
        int actionButtonY = skinListY + skinListHeight + 5;
        int actionButtonSpacing = 3;

        importButton = Button.builder(Component.literal("Import"), button -> {
            openImportDialog();
        }).bounds(skinListX, actionButtonY, actionButtonWidth, actionButtonHeight).build();
        this.addRenderableWidget(importButton);

        capeButton = Button.builder(Component.literal("Cape"), button -> {
            // TODO: Open cape selection screen
        }).bounds(skinListX + actionButtonWidth + actionButtonSpacing, actionButtonY, actionButtonWidth, actionButtonHeight).build();
        this.addRenderableWidget(capeButton);

        settingsButton = Button.builder(Component.literal("Settings"), button -> {
            // TODO: Open settings screen
        }).bounds(skinListX + (actionButtonWidth + actionButtonSpacing) * 2, actionButtonY, actionButtonWidth, actionButtonHeight).build();
        this.addRenderableWidget(settingsButton);

        // Add close button
        int buttonWidth = 100;
        int buttonHeight = 20;
        int buttonX = panelX + (panelWidth - buttonWidth) / 2;
        int buttonY = panelY + panelHeight - 30;

        this.addRenderableWidget(
                Button.builder(Component.literal("Close"), button -> onClose())
                        .bounds(buttonX, buttonY, buttonWidth, buttonHeight)
                        .build()
        );
    }

    /**
     * Calculate panel dimensions based on screen size
     */
    private void calculatePanelDimensions() {
        // Adaptive width based on screen width
        panelWidth = Math.min(MAX_PANEL_WIDTH, Math.max(MIN_PANEL_WIDTH, this.width - 100));
        panelHeight = PANEL_HEIGHT;

        // Center the panel
        panelX = (this.width - panelWidth) / 2;
        panelY = (this.height - panelHeight) / 2;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Render background (darken screen)
        renderBackground(graphics);

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
        // Left
        graphics.fill(panelX, panelY, panelX + 1, panelY + panelHeight, 0x60FFFFFF);
        // Right
        graphics.fill(panelX + panelWidth - 1, panelY, panelX + panelWidth, panelY + panelHeight, 0x60FFFFFF);
    }

    @Override
    public void onClose() {
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
                    if (skinListWidget != null && !imported.isEmpty()) {
                        AssetMetadata firstImported = imported.get(0);
                        for (int i = 0; i < skinListWidget.children().size(); i++) {
                            SkinEntry entry = (SkinEntry) skinListWidget.children().get(i);
                            if (entry.getMetadata().hash().equals(firstImported.hash())) {
                                skinListWidget.setSelected(entry);
                                onSkinSelected(entry);
                                skinListWidget.makeVisible(entry);
                                break;
                            }
                        }
                    }
                }
            });
        }
    }

    /**
     * Set the model type for the player preview
     */
    private void setModelType(String modelType) {
        this.currentModelType = modelType;
        if (playerWidget != null) {
            playerWidget.setModelType(modelType);
        }
    }

    /**
     * Load skins from LocalAssetManager
     */
    private void loadSkins() {
        if (skinListWidget == null) {
            return;
        }

        LocalAssetManager assetManager = LocalAssetManager.getInstance();
        List<AssetMetadata> skins = assetManager.getAllSkins();

        for (AssetMetadata metadata : skins) {
            skinListWidget.addSkinEntry(metadata);
        }
    }

    /**
     * Called when a skin is selected from the list
     */
    public void onSkinSelected(SkinEntry entry) {
        if (playerWidget != null && entry != null) {
            AssetMetadata metadata = entry.getMetadata();

            // Update player preview with selected skin
            playerWidget.setSkin(LocalAssetManager.getInstance()
                .getTextureLocation(metadata.hash(), TextureQuality.FULL));

            // Update model type if auto-detect
            if ("auto".equals(currentModelType)) {
                playerWidget.setModelType(metadata.skinModel());
            }
        }
    }

    /**
     * Get the skin list widget
     */
    public SkinListWidget getSkinList() {
        return skinListWidget;
    }

    /**
     * Get the font renderer
     */
    public net.minecraft.client.gui.Font getFont() {
        return this.font;
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
                    if (skinListWidget != null) {
                        // Find and select the imported entry
                        for (int i = 0; i < skinListWidget.children().size(); i++) {
                            SkinEntry entry = (SkinEntry) skinListWidget.children().get(i);
                            if (entry.getMetadata().hash().equals(metadata.hash())) {
                                skinListWidget.setSelected(entry);
                                onSkinSelected(entry);
                                skinListWidget.makeVisible(entry);
                                break;
                            }
                        }
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
        if (skinListWidget == null) {
            return;
        }

        // Clear and reload
        skinListWidget.removeAllEntries();
        loadSkins();
    }
}
