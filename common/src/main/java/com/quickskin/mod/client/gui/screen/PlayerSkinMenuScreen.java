package com.quickskin.mod.client.gui.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.gui.util.FileDialogHelper;
import com.quickskin.mod.client.gui.util.GuiScaleManager;
import com.quickskin.mod.client.gui.util.SkinImporter;
import com.quickskin.mod.client.gui.widget.LinkButton;
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

    // Rotate button
    private Button rotateButton;

    // Action buttons
    private Button importButton;
    private Button hdSkinWebsiteButton;
    private Button skinWebsiteButton;
    private Button capeButton;
    private Button doneButton;

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

    // Icon textures
    private static final ResourceLocation DISCORD_ICON = new ResourceLocation("quickskin", "textures/gui/discord_icon.png");
    private static final ResourceLocation CURSEFORGE_ICON = new ResourceLocation("quickskin", "textures/gui/curseforge_icon.png");
    private static final ResourceLocation MODRINTH_ICON = new ResourceLocation("quickskin", "textures/gui/modrinth_icon.png");
    private static final ResourceLocation SETTINGS_ICON = new ResourceLocation("quickskin", "textures/gui/settings_icon.png");

    // URLs
    private static final String DISCORD_URL = "https://discord.gg/yGxdvA7qej";
    private static final String CURSEFORGE_URL = "https://www.curseforge.com/minecraft/mc-mods/quick-skin";
    private static final String MODRINTH_URL = "https://modrinth.com/mod/quick-skin";

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

        // Use consistent sizing values like the original mod
        int scaledPadding = 6;
        int scaledSpacing = 4;
        int scaledComponentHeight = 20;

        // Calculate left panel width (60% of total for balanced layout)
        int leftPanelWidth = (int) (panelWidth * 0.6f);
        int rightPanelWidth = (int) (panelWidth * 0.35f);

        int componentX = panelX + scaledPadding;
        int yPos = panelY + scaledPadding + scaledComponentHeight + scaledPadding;

        // Calculate list height with proper spacing
        int topSectionHeight = scaledPadding + scaledComponentHeight + scaledPadding + scaledComponentHeight + scaledSpacing;
        int bottomSectionHeight = (scaledComponentHeight * 3) + (scaledSpacing * 2) + scaledPadding;
        int listHeight = panelHeight - topSectionHeight - bottomSectionHeight;

        // Create skin list on the left side
        skinListWidget = new SkinListWidget(
            this,
            this.minecraft,
            leftPanelWidth,
            listHeight,
            yPos,
            40 // Entry height - matches original
        );
        skinListWidget.setLeftPos(componentX);
        skinListWidget.setRenderBackground(false);
        skinListWidget.setRenderTopAndBottom(false);
        this.addRenderableWidget(skinListWidget);

        // Load skins from LocalAssetManager
        loadSkins();

        // Create player widget in right section
        int playerWidgetX = panelX + panelWidth - rightPanelWidth - scaledPadding;
        int playerWidgetY = yPos;
        int availableHeightForWidget = panelHeight - topSectionHeight - bottomSectionHeight;
        int widgetSize = Math.min(144, Math.min(availableHeightForWidget, rightPanelWidth));

        playerWidget = new PlayerWidget(
            playerWidgetX + 20,
            playerWidgetY,
            widgetSize,
            widgetSize,
            null, // Will use default Steve skin
            null, // No cape initially
            "classic" // Default model type
        );
        this.addRenderableWidget(playerWidget);

        // --- Bottom Buttons (anchored to bottom of panel, matching original layout) ---
        int bottomY = panelY + panelHeight - scaledPadding;
        int fullWidthX = panelX + scaledPadding;
        int fullComponentWidth = panelWidth - (scaledPadding * 2);

        // Row 1 (Bottom-most): Done Button (full width)
        bottomY -= scaledComponentHeight;
        doneButton = Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(fullWidthX, bottomY, fullComponentWidth, scaledComponentHeight)
                .build();
        this.addRenderableWidget(doneButton);

        // Row 2: Import, HD Skin, Skin, Cape Buttons (4 equal width buttons)
        bottomY -= (scaledComponentHeight + scaledSpacing);
        int fourButtonWidth = (fullComponentWidth - (scaledSpacing * 3)) / 4;

        importButton = Button.builder(Component.literal("Import Skin"), button -> {
            openImportDialog();
        }).bounds(fullWidthX, bottomY, fourButtonWidth, scaledComponentHeight).build();
        this.addRenderableWidget(importButton);

        hdSkinWebsiteButton = Button.builder(Component.literal("HD Skin Website"), button -> {
            // TODO: Open HD skin website
            if (this.minecraft != null) {
                this.minecraft.options.chatLinksPrompt().set(false);
            }
        }).bounds(fullWidthX + fourButtonWidth + scaledSpacing, bottomY, fourButtonWidth, scaledComponentHeight).build();
        this.addRenderableWidget(hdSkinWebsiteButton);

        skinWebsiteButton = Button.builder(Component.literal("Skin Website"), button -> {
            // TODO: Open skin website
            if (this.minecraft != null) {
                this.minecraft.options.chatLinksPrompt().set(false);
            }
        }).bounds(fullWidthX + (fourButtonWidth + scaledSpacing) * 2, bottomY, fourButtonWidth, scaledComponentHeight).build();
        this.addRenderableWidget(skinWebsiteButton);

        capeButton = Button.builder(Component.literal("Cape"), button -> {
            // TODO: Open cape selection screen
        }).bounds(fullWidthX + (fourButtonWidth + scaledSpacing) * 3, bottomY, fourButtonWidth, scaledComponentHeight).build();
        this.addRenderableWidget(capeButton);

        // Row 3: Model buttons (Auto, Wide/Classic, Slim) positioned in the rightmost fourth
        bottomY -= (scaledComponentHeight + scaledSpacing);
        int modelButtonsX = fullWidthX + (fourButtonWidth + scaledSpacing) * 3;
        int modelButtonsTotalWidth = fourButtonWidth;

        // Auto button is smaller (square button for the emoji), Classic/Slim share the rest equally
        int autoButtonWidth = scaledComponentHeight; // Square button
        int remainingWidth = modelButtonsTotalWidth - autoButtonWidth - scaledSpacing;
        int normalModelButtonWidth = (remainingWidth - scaledSpacing) / 2;

        autoModelButton = Button.builder(Component.literal("✨"), button -> {
            setModelType("auto");
        }).bounds(modelButtonsX, bottomY, autoButtonWidth, scaledComponentHeight).build();
        this.addRenderableWidget(autoModelButton);

        classicModelButton = Button.builder(Component.literal("Wide"), button -> {
            setModelType("classic");
        }).bounds(modelButtonsX + autoButtonWidth + scaledSpacing, bottomY, normalModelButtonWidth, scaledComponentHeight).build();
        this.addRenderableWidget(classicModelButton);

        slimModelButton = Button.builder(Component.literal("Slim"), button -> {
            setModelType("slim");
        }).bounds(modelButtonsX + autoButtonWidth + scaledSpacing + normalModelButtonWidth + scaledSpacing, bottomY, normalModelButtonWidth, scaledComponentHeight).build();
        this.addRenderableWidget(slimModelButton);

        // Set button references for player widget positioning
        if (playerWidget != null) {
            playerWidget.setModelButtons(autoModelButton, classicModelButton, slimModelButton);
        }

        // Update button states to lock the currently selected model button
        updateModelButtonStates();

        // --- Rotate Button ---
        int rotateButtonSize = 20;
        int rightPanelEdgeX = panelX + panelWidth - scaledPadding;
        int rotateButtonX = rightPanelEdgeX - rotateButtonSize;
        int rotateButtonY = classicModelButton.getY() - rotateButtonSize - scaledSpacing;

        rotateButton = this.addRenderableWidget(
            new com.quickskin.mod.client.gui.widget.RotateButton(
                rotateButtonX,
                rotateButtonY,
                rotateButtonSize,
                b -> {
                    if (playerWidget != null) {
                        playerWidget.toggleRotation();
                    }
                }
            )
        );
        rotateButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Rotate Preview")));

        // --- Top-Right Link Buttons (Modrinth, CurseForge, Discord, Settings) ---
        int buttonSize = scaledComponentHeight;
        int linkButtonY = panelY + scaledPadding;

        // Settings button (far right)
        int settingsButtonX = panelX + panelWidth - buttonSize - scaledPadding;
        this.addRenderableWidget(new LinkButton(
                settingsButtonX,
                linkButtonY,
                buttonSize,
                buttonSize,
                SETTINGS_ICON,
                null, // No URL, will be handled differently
                Component.literal("Settings")
        ) {
            @Override
            public void onPress() {
                // TODO: Open settings screen
                QuickSkin.LOGGER.info("Settings button pressed");
            }
        });

        // Discord button (left of settings)
        int discordButtonX = settingsButtonX - buttonSize - scaledSpacing;
        this.addRenderableWidget(new LinkButton(
                discordButtonX,
                linkButtonY,
                buttonSize,
                buttonSize,
                DISCORD_ICON,
                DISCORD_URL,
                Component.literal("Join our Discord!")
        ));

        // CurseForge button (left of Discord)
        int curseforgeButtonX = discordButtonX - buttonSize - scaledSpacing;
        this.addRenderableWidget(new LinkButton(
                curseforgeButtonX,
                linkButtonY,
                buttonSize,
                buttonSize,
                CURSEFORGE_ICON,
                CURSEFORGE_URL,
                Component.literal("Visit our CurseForge page")
        ));

        // Modrinth button (left of CurseForge)
        int modrinthButtonX = curseforgeButtonX - buttonSize - scaledSpacing;
        this.addRenderableWidget(new LinkButton(
                modrinthButtonX,
                linkButtonY,
                buttonSize,
                buttonSize,
                MODRINTH_ICON,
                MODRINTH_URL,
                Component.literal("Visit our Modrinth page")
        ));
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
        updateModelButtonStates();
    }

    /**
     * Update the active state of model buttons based on current selection
     * Buttons are locked (inactive) when they are the currently selected model
     */
    private void updateModelButtonStates() {
        if (autoModelButton != null && classicModelButton != null && slimModelButton != null) {
            boolean isAuto = "auto".equalsIgnoreCase(currentModelType);
            boolean isSlim = "slim".equalsIgnoreCase(currentModelType);
            boolean isClassic = "classic".equalsIgnoreCase(currentModelType);

            // Button is active (clickable) when it's NOT the current model
            // Button is inactive (locked/grayed out) when it IS the current model
            autoModelButton.active = !isAuto;
            classicModelButton.active = !isClassic;
            slimModelButton.active = !isSlim;
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
