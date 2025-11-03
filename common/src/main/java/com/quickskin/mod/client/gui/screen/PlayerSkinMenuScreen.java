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
import com.quickskin.mod.client.gui.widget.ConfirmationDialog;
import com.quickskin.mod.client.gui.widget.ErrorToast;
import com.quickskin.mod.client.gui.widget.SkinEntry;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.MojangApiService;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Button;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.TextureQuality;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    // Player preview rotation state (preserved across resizes)
    private float savedBodyYaw = 20.0f;
    private float savedTargetRotation = 20.0f;

    // Constants
    private static final int MIN_PANEL_WIDTH = 340;
    private static final int MAX_PANEL_WIDTH = 600;
    private static final int MIN_PANEL_HEIGHT = 280;

    // --- NEW ---: Constants for the background effect
    private static final ResourceLocation STAR_PATTERN_TEXTURE = new ResourceLocation(QuickSkin.MOD_ID, "textures/gui/background/star_pattern.png");
    private static final ResourceLocation VIGNETTE_LOCATION = new ResourceLocation("textures/misc/vignette.png");

    // Error toasts
    private final List<ErrorToast> errorToasts = new ArrayList<>();

    // Confirmation dialog
    @Nullable
    private ConfirmationDialog confirmationDialog;

    // Mojang search widgets
    private EditBox usernameSearchField;
    private Button searchButton;
    private boolean isSearching = false;

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

        // Save rotation state from existing player preview panel before it's destroyed
        if (playerPreviewPanel != null) {
            com.quickskin.mod.client.gui.widget.PlayerWidget widget = playerPreviewPanel.getPlayerWidget();
            if (widget != null) {
                savedBodyYaw = widget.getBodyYaw();
                savedTargetRotation = widget.getTargetYRotation();
            }
        }

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

        // Create Mojang username search field (below title)
        // Match the width of the skin list panel
        int searchFieldWidth = leftPanelWidth;
        int searchButtonWidth = 60;
        int searchFieldX = componentX;

        usernameSearchField = new EditBox(
                this.font,
                searchFieldX,
                yPos,
                searchFieldWidth - searchButtonWidth - scaledSpacing,
                scaledComponentHeight,
                Component.literal("Search by username")
        );
        usernameSearchField.setSuggestion("Write a username...");
        usernameSearchField.setMaxLength(16);
        usernameSearchField.setResponder(text -> {
            onUsernameFieldChanged(text);
            // Update suggestion visibility
            if (text.isEmpty()) {
                usernameSearchField.setSuggestion("Write a username...");
            } else {
                usernameSearchField.setSuggestion("");
            }
        });
        addRenderableWidget(usernameSearchField);

        searchButton = Button.builder(
                Component.literal("Search"),
                button -> searchMojangSkin()
        )
        .bounds(
                searchFieldX + searchFieldWidth - searchButtonWidth,
                yPos,
                searchButtonWidth,
                scaledComponentHeight
        )
        .build();
        addRenderableWidget(searchButton);
        searchButton.active = false;

        // Adjust the yPos for components below the search field
        yPos += scaledComponentHeight + scaledSpacing;

        // Calculate list height with proper spacing
        // Title + padding + search field + spacing + extra spacing for the list
        int topSectionHeight = scaledPadding + scaledComponentHeight + scaledPadding + scaledComponentHeight + scaledSpacing + scaledSpacing;
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

        // Set up model type change callback to apply model to actual player
        playerPreviewPanel.setModelTypeChangeCallback(this::onModelTypeChanged);

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
                    // Open cape selection screen
                    minecraft.setScreen(new PlayerCapeMenuScreen(this));
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
        int linkPanelX = panelX + panelWidth - linkPanelWidth - scaledPadding;

        linkButtonsPanel = new LinkButtonsPanel(
                linkPanelX,
                linkButtonY,
                linkPanelWidth,
                scaledComponentHeight
        );
        linkButtonsPanel.init(this);

        // Restore saved model type and active skin from config
        restoreSavedState();
    }

    /**
     * Restore the saved model type and active skin from config
     */
    private void restoreSavedState() {
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();

        // Restore model type preference
        if (playerPreviewPanel != null) {
            playerPreviewPanel.setCurrentModelType(config.activeModelType);
        }

        // Restore active skin selection
        if (!config.activeSkinHash.isEmpty() && skinListPanel != null) {
            AssetMetadata metadata = LocalAssetManager.getInstance().getMetadata(config.activeSkinHash);
            if (metadata != null) {
                skinListPanel.setSelected(metadata);
            }
        }

        // Restore active cape selection
        if (!config.activeCapeHash.isEmpty() && playerPreviewPanel != null) {
            ResourceLocation capeLocation = getCapeLocationFromId(config.activeCapeHash);
            if (capeLocation != null) {
                playerPreviewPanel.updateCape(capeLocation);
            }
        }

        // Restore rotation state
        if (playerPreviewPanel != null) {
            com.quickskin.mod.client.gui.widget.PlayerWidget widget = playerPreviewPanel.getPlayerWidget();
            if (widget != null) {
                widget.setRotationState(savedBodyYaw, savedTargetRotation);
            }
        }
    }

    /**
     * Convert cape ID to ResourceLocation
     * Cape ID format: "local_cape:hash" or "known:id"
     */
    @Nullable
    private ResourceLocation getCapeLocationFromId(String capeId) {
        if (capeId.startsWith("local_cape:")) {
            // Local cape - extract hash and get texture location
            String hash = capeId.substring("local_cape:".length());
            return LocalAssetManager.getInstance().getTextureLocation(hash, com.quickskin.mod.common.data.TextureQuality.FULL);
        } else if (capeId.startsWith("known:")) {
            // Known cape - extract ID and get from KnownCapes enum
            String id = capeId.substring("known:".length());
            com.quickskin.mod.common.data.KnownCapes knownCape = com.quickskin.mod.common.data.KnownCapes.getById(id);
            if (knownCape != null) {
                return knownCape.getTextureLocation();
            } else {
                QuickSkin.LOGGER.warn("Unknown cape ID: {}", id);
                return null;
            }
        }
        return null;
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

        // Render confirmation dialog (on top of everything)
        if (confirmationDialog != null) {
            confirmationDialog.render(graphics, mouseX, mouseY, partialTick);
        }

        // Render error toasts (on top of dialog)
        renderErrorToasts(graphics);
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
        // Handle confirmation dialog first
        if (confirmationDialog != null) {
            return confirmationDialog.mouseClicked(mouseX, mouseY, button);
        }

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

            // Get the current model type preference from config
            com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
            String modelType = config.activeModelType;

            // Apply skin to the actual player in-game
            if (this.minecraft != null && this.minecraft.player != null) {
                String skinId = "local_skin:" + metadata.hash();

                // Pass the model type directly - ModelService will handle "auto" detection
                com.quickskin.mod.client.services.PlayerAppearanceService.getInstance()
                        .applySkin(this.minecraft.player.getUUID(), skinId, modelType);
                QuickSkin.LOGGER.info("Applied skin to player: {} with model type: {}",
                        metadata.friendlyName(), modelType);
            }

            // Save the active skin hash to config
            config.activeSkinHash = metadata.hash();
            config.save();
        }
    }

    /**
     * Called when model type is changed via the model buttons
     */
    private void onModelTypeChanged(String newModelType) {
        if (this.minecraft != null && this.minecraft.player != null) {
            // Get the currently selected skin entry
            SkinEntry selectedEntry = skinListPanel != null ? skinListPanel.getSelected() : null;

            if (selectedEntry != null) {
                AssetMetadata metadata = selectedEntry.getMetadata();
                String skinId = "local_skin:" + metadata.hash();

                // Pass the model type directly - ModelService will handle "auto" detection
                com.quickskin.mod.client.services.PlayerAppearanceService.getInstance()
                        .applySkin(this.minecraft.player.getUUID(), skinId, newModelType);

                QuickSkin.LOGGER.info("Changed model type to: {}", newModelType);

                // Save the model type preference to config
                com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
                config.activeModelType = newModelType;
                config.save();
            }
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
                    // Show error message to user
                    showError(Component.literal("Failed to import skin"));
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

    /**
     * Show an error toast message
     */
    public void showError(Component message) {
        errorToasts.add(new ErrorToast(message));
    }

    /**
     * Render error toasts
     */
    private void renderErrorToasts(GuiGraphics graphics) {
        errorToasts.removeIf(toast -> !toast.render(graphics, width, height));
    }

    /**
     * Show deletion confirmation dialog
     */
    public void showDeleteConfirmation(AssetMetadata metadata) {
        confirmationDialog = new ConfirmationDialog(
            Component.literal("Delete Skin?"),
            Component.literal("Are you sure you want to delete \"" + metadata.friendlyName() + "\"?"),
            () -> {
                // Confirm deletion
                deleteSkin(metadata);
                confirmationDialog = null;
            },
            () -> {
                // Cancel
                confirmationDialog = null;
            }
        );
        confirmationDialog.init(width, height);
    }

    /**
     * Delete a skin from local storage
     */
    private void deleteSkin(AssetMetadata metadata) {
        try {
            // Delete the file
            Files.deleteIfExists(metadata.path());

            minecraft.getSoundManager().play(
                net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                    SoundEvents.UI_BUTTON_CLICK.value(), 1.0f
                )
            );

            // Refresh the asset manager and skin list
            LocalAssetManager.getInstance().discoverLocalAssets();
            refreshSkinList();

            QuickSkin.LOGGER.info("Deleted skin: {}", metadata.friendlyName());
        } catch (IOException e) {
            QuickSkin.LOGGER.error("Failed to delete skin: {}", metadata.friendlyName(), e);
            showError(Component.literal("Failed to delete skin: " + e.getMessage()));
        }
    }

    /**
     * Called when the username search field changes
     */
    private void onUsernameFieldChanged(String text) {
        if (searchButton != null) {
            searchButton.active = !text.trim().isEmpty() && !isSearching;
        }
    }

    /**
     * Search for a skin using Mojang API
     */
    private void searchMojangSkin() {
        if (usernameSearchField == null || isSearching) {
            return;
        }

        String username = usernameSearchField.getValue().trim();
        if (username.isEmpty()) {
            return;
        }

        // Disable search while fetching
        isSearching = true;
        searchButton.active = false;
        searchButton.setMessage(Component.literal("Searching..."));

        QuickSkin.LOGGER.info("Searching for Mojang skin: {}", username);

        // Fetch skin asynchronously
        MojangApiService.getInstance().fetchSkinByUsername(username)
            .thenAccept(skinData -> {
                // Execute on main thread
                if (this.minecraft != null) {
                    this.minecraft.execute(() -> {
                        if (skinData != null) {
                            handleMojangSkinFetched(skinData);
                        } else {
                            showError(Component.literal("Player not found: " + username));
                            resetSearchButton();
                        }
                    });
                }
            })
            .exceptionally(throwable -> {
                QuickSkin.LOGGER.error("Error fetching Mojang skin", throwable);
                if (this.minecraft != null) {
                    this.minecraft.execute(() -> {
                        showError(Component.literal("Failed to fetch skin: " + throwable.getMessage()));
                        resetSearchButton();
                    });
                }
                return null;
            });
    }

    /**
     * Handle the fetched Mojang skin data
     */
    private void handleMojangSkinFetched(MojangApiService.MojangSkinData skinData) {
        try {
            // Save the skin image to local storage
            Path skinPath = SkinImporter.saveSkinImage(skinData.image, skinData.username);

            if (skinPath != null) {
                QuickSkin.LOGGER.info("Successfully saved Mojang skin for: {}", skinData.username);

                // Reload the asset manager to pick up the new file
                LocalAssetManager.getInstance().reload();

                // Get the metadata for the saved file
                String hash = com.quickskin.mod.common.util.HashUtil.computeFileHash(skinPath);
                if (hash != null) {
                    AssetMetadata metadata = LocalAssetManager.getInstance().getMetadata(hash);

                    if (metadata != null) {
                        // Refresh the skin list
                        refreshSkinList();

                        // Auto-select the imported skin
                        if (skinListPanel != null) {
                            skinListPanel.setSelected(metadata);
                        }

                        // Clear the search field
                        usernameSearchField.setValue("");

                        // Play success sound
                        minecraft.getSoundManager().play(
                            net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                                SoundEvents.UI_BUTTON_CLICK.value(), 1.0f
                            )
                        );
                    } else {
                        showError(Component.literal("Failed to load skin metadata"));
                    }
                } else {
                    showError(Component.literal("Failed to compute file hash"));
                }
            } else {
                showError(Component.literal("Failed to save skin image"));
            }
        } catch (Exception e) {
            QuickSkin.LOGGER.error("Error handling Mojang skin", e);
            showError(Component.literal("Error: " + e.getMessage()));
        } finally {
            resetSearchButton();
        }
    }

    /**
     * Reset the search button state
     */
    private void resetSearchButton() {
        isSearching = false;
        if (searchButton != null) {
            searchButton.setMessage(Component.literal("Search"));
            searchButton.active = usernameSearchField != null && !usernameSearchField.getValue().trim().isEmpty();
        }
    }
}