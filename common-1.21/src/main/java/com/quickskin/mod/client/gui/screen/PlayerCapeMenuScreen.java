package com.quickskin.mod.client.gui.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.gui.util.FileDialogHelper;
import com.quickskin.mod.client.gui.util.GuiScalingUtils;
import com.quickskin.mod.common.util.HashUtil;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.client.gui.widget.PlayerWidget;
import com.quickskin.mod.client.rendering.PlayerModelRenderer;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.platform.PlatformHelper;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.KnownCapes;
import com.quickskin.mod.common.data.TextureQuality;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Cape selection menu for QuickSkin with grid-based layout
 */
@Environment(EnvType.CLIENT)
public class PlayerCapeMenuScreen extends Screen {

    // Background textures
    private static final ResourceLocation STAR_PATTERN_TEXTURE = ResourceLocation.fromNamespaceAndPath(QuickSkin.MOD_ID, "textures/gui/background/star_pattern.png");
    private static final ResourceLocation VIGNETTE_LOCATION = ResourceLocation.withDefaultNamespace("textures/misc/vignette.png");

    // Base dimensions (will be scaled)
    private static final int BASE_CAPE_DISPLAY_SIZE = 64;
    private static final int BASE_CAPE_PADDING = 8;
    private static final int BASE_SCROLL_SPEED = 20;
    private static final int ACTION_BUTTON_SIZE = 11;
    private static final int HEADER_HEIGHT = 20;

    // Responsive grid constraints
    private static final int MIN_GRID_WIDTH = 300;
    private static final int MAX_GRID_WIDTH = 600;
    private static final int MIN_GRID_HEIGHT = 200;
    private static final int MAX_GRID_HEIGHT = 500;

    // Adaptive dimensions
    private int capeDisplaySize;
    private int capePadding;
    private int capesPerRow;
    private int scrollSpeed;

    @Nullable
    private final Screen parent;

    private PlayerWidget playerWidget;
    private SpeedSlider animationSpeedSlider;

    // Model position offsets from grid edge (base offset + config offset when config is 0)
    private static final int MODEL_OFFSET_X = 95; // Was 80, now 80 + 15 = 95
    private static final int MODEL_OFFSET_Y = 121; // Was 85, now 85 + 36 = 121

    private double scrollOffset = 0;
    private double targetScrollOffset = 0;
    private int maxScroll = 0;
    private boolean isDraggingScrollbar = false;
    private double scrollbarClickOffset = 0.0;
    private int totalContentHeight = 0;
    private int gridX, gridY, gridWidth, gridHeight;

    // Player widget positioning
    private int playerWidgetX, playerWidgetY;

    // Player widget rotation state (preserved across resizes)
    private float savedBodyYaw = 20.0f;
    private float savedTargetRotation = 200.0f; // Default after initial toggleRotation()
    private int playerWidgetWidth, playerWidgetHeight;

    @Nullable
    private CapeEntry selectedCape;

    // Sectioned cape lists
    private final List<CapeEntry> localCapes = new ArrayList<>(); // Contains "None" and local capes
    private final List<CapeEntry> knownCapes = new ArrayList<>(); // Contains known/default capes

    // Import feedback
    private String importMessage = "";
    private int importMessageTimer = 0;
    private int importMessageColor = 0xFFFFFF;

    public PlayerCapeMenuScreen(@Nullable Screen parent) {
        super(Component.empty());
        this.parent = parent;
    }

    @Override
    protected void init() {
        // Save rotation state from existing widget before it's destroyed
        if (this.playerWidget != null) {
            this.savedBodyYaw = this.playerWidget.getBodyYaw();
            this.savedTargetRotation = this.playerWidget.getTargetYRotation();
        }

        super.init();

        // Calculate adaptive dimensions based on screen size
        calculateAdaptiveDimensions();

        // Calculate responsive grid dimensions
        int desiredGridWidth = (int)(this.width * 0.55f);
        this.gridWidth = Mth.clamp(
                desiredGridWidth,
                MIN_GRID_WIDTH,
                Math.min(MAX_GRID_WIDTH, this.width - scaleValue(200))
        );

        int gridTopY = scaleValue(40);
        int bottomButtonY = this.height - scaleValue(60);
        int availableHeight = bottomButtonY - gridTopY - scaleValue(10);

        this.gridHeight = Mth.clamp(
                availableHeight,
                MIN_GRID_HEIGHT,
                MAX_GRID_HEIGHT
        );

        // Calculate button positions
        int buttonSpacing = 10;
        int buttonWidth = Math.min(150, (this.width - 60 - buttonSpacing * 2) / 3);
        int totalButtonWidth = buttonWidth * 3 + buttonSpacing * 2;
        int buttonStartX = (this.width - totalButtonWidth) / 2;

        // Calculate grid position (aligned with back button at 25%)
        int backButtonX = buttonStartX + (buttonWidth + buttonSpacing) * 2;
        int gridRightEdge = backButtonX + (int)(buttonWidth * 0.25f);
        this.gridX = gridRightEdge - this.gridWidth;
        this.gridY = gridTopY;

        // Refine capesPerRow for actual grid width
        refineCapesPerRowForGridWidth();

        // Load capes
        refreshCapeList();
        updateGridDimensions();

        // Create buttons
        int bottomY = this.height - scaleValue(60);

        Button importButton = this.addRenderableWidget(com.quickskin.mod.client.gui.util.ButtonFactory.createStyled(
                buttonStartX, bottomY, buttonWidth, scaleValue(20),
                Component.literal("Import Cape"),
                button -> importCape()
        ));

        Button removeButton = this.addRenderableWidget(com.quickskin.mod.client.gui.util.ButtonFactory.createStyled(
                buttonStartX + buttonWidth + buttonSpacing, bottomY, buttonWidth, scaleValue(20),
                Component.literal("Remove Cape"),
                button -> removeCape()
        ));

        Button closeButton = this.addRenderableWidget(com.quickskin.mod.client.gui.util.ButtonFactory.createPrimary(
                buttonStartX + (buttonWidth + buttonSpacing) * 2, bottomY, buttonWidth, scaleValue(20),
                Component.literal("Close"),
                button -> this.onClose()
        ));

        // Create animation speed slider (centered, below buttons)
        int sliderWidth = 200;
        int sliderHeight = 20;
        int sliderX = (this.width - sliderWidth) / 2;
        int sliderY = bottomY + scaleValue(20) + 5; // Position below the buttons
        this.animationSpeedSlider = this.addRenderableWidget(new SpeedSlider(sliderX, sliderY, sliderWidth, sliderHeight));
        // Initially hidden - will be shown when an animated cape is selected
        this.animationSpeedSlider.visible = false;
        this.animationSpeedSlider.active = false;

        // Create player preview widget
        int availableWidthForWidget = this.width - (this.gridX + this.gridWidth) - scaleValue(40);
        int availableHeightForWidget = bottomY - this.gridY - scaleValue(20);

        int widgetSize = Mth.clamp(
                Math.min(availableWidthForWidget, availableHeightForWidget),
                scaleValue(100),
                scaleValue(200)
        );

        this.playerWidgetWidth = widgetSize;
        this.playerWidgetHeight = (int)(widgetSize * 1.8f);

        // Position player widget
        if (this.playerWidgetX == 0 && this.playerWidgetY == 0) {
            int gridRight = this.gridX + this.gridWidth;
            int gridCenter = this.gridY + (this.gridHeight / 2);

            this.playerWidgetX = gridRight + scaleValue(20);
            this.playerWidgetY = gridCenter - (this.playerWidgetHeight / 2);
        }

        LocalPlayer player = Minecraft.getInstance().player;
        ResourceLocation skinLocation = null;
        String modelType = "classic";

        // First priority: Use saved skin from config (works on title screen when player is null)
        ClientConfig config = ClientConfig.getInstance();
        if (!config.activeSkinHash.isEmpty()) {
            LocalAssetManager assetManager = LocalAssetManager.getInstance();
            AssetMetadata metadata = assetManager.getMetadata(config.activeSkinHash);

            if (metadata != null) {
                // Load the saved skin texture
                skinLocation = assetManager.getTextureLocation(config.activeSkinHash, TextureQuality.FULL);

                // Get saved model type preference for this skin
                modelType = assetManager.getSkinModelPreference(config.activeSkinHash);

                // If auto mode, use the detected model type from metadata
                if ("auto".equals(modelType)) {
                    modelType = metadata.skinModel();
                }
            }
        }

        // Second priority: Use current player skin (when in-game)
        if (skinLocation == null && player != null) {
            skinLocation = player.getSkin().texture();

            // Get model type from the active skin if available
            if (!config.activeSkinHash.isEmpty()) {
                LocalAssetManager assetManager = LocalAssetManager.getInstance();
                modelType = assetManager.getSkinModelPreference(config.activeSkinHash);
                AssetMetadata metadata = assetManager.getMetadata(config.activeSkinHash);

                // If auto mode, detect from the active custom skin (if any)
                if ("auto".equals(modelType) && metadata != null) {
                    // Use the detected model type from the custom skin metadata
                    modelType = metadata.skinModel();
                } else {
                    // Fallback: detect from the vanilla player's model
                    modelType = player.getSkin().model().id(); // "default" or "slim"
                    // Convert Minecraft model names to our format
                    if ("default".equals(modelType)) {
                        modelType = "classic";
                    }
                }
            } else if ("auto".equals(modelType)) {
                // No custom skin active, use vanilla player's model
                modelType = player.getSkin().model().id(); // "default" or "slim"
                // Convert Minecraft model names to our format
                if ("default".equals(modelType)) {
                    modelType = "classic";
                }
            }
        }

        // Fallback: Use default Steve skin
        if (skinLocation == null) {
            skinLocation = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/entity/player/wide/steve.png");
            modelType = "classic";
        }

        this.playerWidget = addRenderableWidget(new PlayerWidget(
                this.playerWidgetX, this.playerWidgetY,
                this.playerWidgetWidth, this.playerWidgetHeight,
                skinLocation, null, null, modelType));
        this.playerWidget.setContext(com.quickskin.mod.client.gui.widget.PlayerWidget.WidgetContext.CAPE_MENU);

        // Set custom reference point to right side center of capes grid with fixed offset
        int referenceX = this.gridX + this.gridWidth + MODEL_OFFSET_X;
        int referenceY = this.gridY + (this.gridHeight / 2) + MODEL_OFFSET_Y;
        this.playerWidget.setCustomReferencePoint(referenceX, referenceY);

        // Initialize selected cape based on config/currently equipped cape (AFTER widget is created)
        initializeSelectedCape();

        // Update speed slider visibility based on selected cape
        updateSpeedSliderVisibility();

        // Restore saved rotation state
        this.playerWidget.setRotationState(this.savedBodyYaw, this.savedTargetRotation);
    }

    private void calculateAdaptiveDimensions() {
        float scale = GuiScalingUtils.getScaleMultiplier(this.width, this.height);

        this.capeDisplaySize = Math.max(48, Math.min(96, Math.round(BASE_CAPE_DISPLAY_SIZE * scale)));
        this.capePadding = Math.max(4, Math.round(BASE_CAPE_PADDING * scale));
        this.scrollSpeed = Math.max(10, Math.round(BASE_SCROLL_SPEED * scale));

        int estimatedGridWidth = (int)(this.width * 0.55f);
        int capeWithPadding = capeDisplaySize + capePadding;
        this.capesPerRow = Mth.clamp(
                (estimatedGridWidth - capePadding * 2) / capeWithPadding,
                2,
                8
        );

        if (GuiScalingUtils.isSmallScreen(this.width, this.height)) {
            this.capesPerRow = Math.max(2, this.capesPerRow - 1);
            this.capeDisplaySize = Math.round(capeDisplaySize * 0.85f);
        }

        if (GuiScalingUtils.isLargeScreen(this.width, this.height)) {
            this.capesPerRow = Math.min(10, this.capesPerRow + 1);
        }
    }

    private void refineCapesPerRowForGridWidth() {
        int capeWithPadding = capeDisplaySize + capePadding;
        int availableWidth = this.gridWidth - (capePadding * 2);
        int maxPerRow = Math.max(2, availableWidth / capeWithPadding);
        this.capesPerRow = Mth.clamp(maxPerRow, 2, 10);
    }

    private int scaleValue(int baseValue) {
        return GuiScalingUtils.scaleValue(baseValue, this.width, this.height);
    }

    /**
     * Refresh the cape list UI
     * Public so it can be called when textures are reloaded
     */
    public void refreshCapeList() {
        this.localCapes.clear();
        this.knownCapes.clear();

        // --- Section 1: My Capes ---
        // Add "None" option first
        this.localCapes.add(CapeEntry.fromKnown(KnownCapes.NONE));

        // Then add local capes
        List<AssetMetadata> localCapeAssets = LocalAssetManager.getInstance()
                .getAssetsByType("cape");
        for (AssetMetadata localCape : localCapeAssets) {
            this.localCapes.add(CapeEntry.fromLocal(localCape));
        }

        // --- Section 2: Default Capes ---
        // Add all known capes except NONE (that's in My Capes section)
        for (KnownCapes knownCape : KnownCapes.values()) {
            if (!knownCape.isNoCape()) {
                this.knownCapes.add(CapeEntry.fromKnown(knownCape));
            }
        }

        QuickSkin.LOGGER.debug("Loaded {} local capes (including None) + {} default capes",
                localCapes.size(), knownCapes.size());

        // Pre-register animations for all animated capes (for thumbnail rendering)
        registerAllAnimations();
    }

    /**
     * Pre-register animations for all animated capes so thumbnails can display them
     */
    private void registerAllAnimations() {
        com.quickskin.mod.client.services.CapeService capeService =
            com.quickskin.mod.client.services.CapeService.getInstance();

        // Register animations for local capes
        for (CapeEntry cape : localCapes) {
            if (cape.isAnimated()) {
                String capeId = cape.getCapeId();
                // Call getCapeLocation to trigger animation registration
                capeService.getCapeLocation(null, capeId);
            }
        }

        // Register animations for known capes
        for (CapeEntry cape : knownCapes) {
            if (cape.isAnimated()) {
                String capeId = cape.getCapeId();
                // Call getCapeLocation to trigger animation registration
                capeService.getCapeLocation(null, capeId);
            }
        }

        QuickSkin.LOGGER.info("Pre-registered animations for all animated capes in the menu");
    }

    /**
     * Initialize the selected cape based on the saved config or player's currently equipped cape
     */
    private void initializeSelectedCape() {
        String activeCapeId = null;

        // First priority: Check config (works on title screen)
        ClientConfig config = ClientConfig.getInstance();
        if (!config.activeCapeHash.isEmpty()) {
            activeCapeId = config.activeCapeHash;
            QuickSkin.LOGGER.debug("Found active cape in config: {}", activeCapeId);
        }

        // Second priority: Check PlayerAppearanceService (in-game only)
        if (activeCapeId == null && minecraft != null && minecraft.player != null) {
            java.util.UUID playerId = minecraft.player.getUUID();
            com.quickskin.mod.common.data.PlayerAppearance appearance =
                    PlayerAppearanceService.getInstance().getAppearance(playerId);

            if (appearance != null && appearance.getCapeId() != null && !appearance.getCapeId().isEmpty()) {
                activeCapeId = appearance.getCapeId();
                QuickSkin.LOGGER.debug("Found active cape in PlayerAppearanceService: {}", activeCapeId);
            }
        }

        // No active cape found
        if (activeCapeId == null || activeCapeId.isEmpty()) {
            QuickSkin.LOGGER.debug("No active cape found");
            this.selectedCape = null;
            return;
        }

        // Find the matching cape in both lists and update preview
        for (CapeEntry cape : this.localCapes) {
            if (cape.getCapeId().equals(activeCapeId)) {
                this.selectedCape = cape;
                // Update the preview widget with the saved cape
                ResourceLocation capeLocation = cape.getTextureLocation();
                if (capeLocation != null && playerWidget != null) {
                    playerWidget.setCape(capeLocation, cape.getCapeId());
                }
                QuickSkin.LOGGER.info("Initialized selected cape: {}", cape.getFriendlyName());
                return;
            }
        }

        for (CapeEntry cape : this.knownCapes) {
            if (cape.getCapeId().equals(activeCapeId)) {
                this.selectedCape = cape;
                // Update the preview widget with the saved cape
                ResourceLocation capeLocation = cape.getTextureLocation();
                if (capeLocation != null && playerWidget != null) {
                    playerWidget.setCape(capeLocation, cape.getCapeId());
                }
                QuickSkin.LOGGER.info("Initialized selected cape: {}", cape.getFriendlyName());
                return;
            }
        }

        QuickSkin.LOGGER.warn("Could not find cape with ID '{}' in capes lists", activeCapeId);
        this.selectedCape = null;
    }

    /**
     * Update the animation speed slider visibility and value based on selected cape
     */
    private void updateSpeedSliderVisibility() {
        if (this.animationSpeedSlider == null) return;

        boolean show = false;
        if (this.selectedCape != null && this.selectedCape.isAnimated()) {
            show = true;
            // Load the speed for the newly selected cape
            this.animationSpeedSlider.loadSpeedForCurrentCape();
        }

        this.animationSpeedSlider.visible = show;
        this.animationSpeedSlider.active = show;
    }

    private void updateGridDimensions() {
        int totalHeight = 0;

        // "My Capes" section height
        if (!this.localCapes.isEmpty()) {
            totalHeight += HEADER_HEIGHT;
            int localRows = (int) Math.ceil((double) this.localCapes.size() / capesPerRow);
            totalHeight += localRows * (capeDisplaySize + capePadding);
        }

        // "Default Capes" section height
        if (!this.knownCapes.isEmpty()) {
            totalHeight += HEADER_HEIGHT + 20; // Extra spacing between sections
            int knownRows = (int) Math.ceil((double) this.knownCapes.size() / capesPerRow);
            totalHeight += knownRows * (capeDisplaySize + capePadding);
        }

        this.totalContentHeight = totalHeight + capePadding;
        this.maxScroll = Math.max(0, this.totalContentHeight - this.gridHeight);
    }

    private void importCape() {
        FileDialogHelper.openCapeFileDialog("Select Cape File", this::handleCapeImport);
    }

    /**
     * Handle imported cape file
     */
    private void handleCapeImport(Path filePath) {
        if (filePath == null) {
            return;
        }

        QuickSkin.LOGGER.info("Importing cape: {}", filePath);

        // Show processing message
        showImportMessage("Processing cape...", 0x55AAFF, 60);

        // Import on main thread
        if (this.minecraft != null) {
            this.minecraft.execute(() -> {
                Path capesDir = LocalAssetManager.getInstance().getCapesDirectory();
                try {
                    Files.createDirectories(capesDir);

                    if (processDroppedFile(filePath, capesDir)) {
                        QuickSkin.LOGGER.info("Successfully imported cape: {}", filePath.getFileName());

                        // Reload assets
                        LocalAssetManager.getInstance().reload();

                        // Refresh the cape list
                        refreshCapeList();
                        updateGridDimensions();

                        showImportMessage("✓ Imported cape", 0x55FF55, 100);
                    } else {
                        QuickSkin.LOGGER.error("Failed to import cape: {}", filePath);
                        showImportMessage("⚠ Invalid cape file (must be 2:1 ratio or animation strip)", 0xFF5555, 150);
                    }
                } catch (IOException e) {
                    QuickSkin.LOGGER.error("Failed to import cape", e);
                    showImportMessage("⚠ Error: " + e.getMessage(), 0xFF5555, 150);
                }
            });
        }
    }

    private void removeCape() {
        // Always update preview widget (works both in-game and on title screen)
        playerWidget.setCape(null, null);
        this.selectedCape = null;

        // Update speed slider visibility (hide it since no cape is selected)
        updateSpeedSliderVisibility();

        // Clear from config for persistence
        ClientConfig config = ClientConfig.getInstance();
        config.activeCapeHash = "";
        config.save();
        QuickSkin.LOGGER.info("Cleared cape from config");

        // Remove from PlayerAppearanceService
        // Note: We use applyCape with empty string instead of removeCape
        // to avoid unregistering animations while the menu is open
        if (minecraft != null && minecraft.player != null) {
            // In-game: use the real player's UUID
            PlayerAppearanceService.getInstance()
                    .applyCape(minecraft.player.getUUID(), "");
            QuickSkin.LOGGER.info("Removed cape from in-game player");
        } else {
            // Title screen: use cached player UUID if available
            java.util.UUID dummyUUID = getDummyPlayerUUID();
            if (dummyUUID != null) {
                PlayerAppearanceService.getInstance()
                        .applyCape(dummyUUID, "");
                QuickSkin.LOGGER.info("Removed cape from cached player");
            } else {
                QuickSkin.LOGGER.info("Removed cape from preview only");
            }
        }
    }

    public void showDeleteConfirmation(CapeEntry capeEntry) {
        // Only allow deletion of local capes
        if (!capeEntry.isLocal()) {
            return;
        }

        if (minecraft == null) {
            return;
        }

        String displayName = truncateFileName(capeEntry.getFriendlyName());
        minecraft.setScreen(new DeletionConfirmScreen(
                this,
                Component.literal("Delete Cape?"),
                Component.literal("Are you sure you want to delete '" + displayName + "'?"),
                (confirmed) -> {
                    if (confirmed) {
                        deleteCape(capeEntry);
                    }
                    // Return to cape menu screen
                    minecraft.setScreen(this);
                },
                true
        ));
    }

    /**
     * Truncate filename to 35 characters, adding ellipsis if needed
     */
    private String truncateFileName(String name) {
        int maxLength = 35;
        if (name.length() <= maxLength) {
            return name;
        }
        return name.substring(0, maxLength - 3) + "...";
    }

    private void deleteCape(CapeEntry capeEntry) {
        if (!capeEntry.isLocal() || capeEntry.getLocalCape() == null) {
            return;
        }

        Path capePath = capeEntry.getPath();
        if (capePath == null) {
            QuickSkin.LOGGER.error("Cannot delete cape: path is null");
            return;
        }

        // Check if the cape being deleted is the one currently selected for preview.
        final boolean wasSelected = this.selectedCape != null && this.selectedCape.getCapeId().equals(capeEntry.getCapeId());

        try {
            Files.deleteIfExists(capePath);
            LocalAssetManager.getInstance().discoverLocalAssets();
            refreshCapeList();
            updateGridDimensions();

            // If the deleted cape was the one being previewed, call removeCape()
            // to update the preview widget and clear the active cape from the config.
            if (wasSelected) {
                removeCape();
            }

            QuickSkin.LOGGER.info("Deleted cape: {}", capeEntry.getFriendlyName());
            showImportMessage("✓ Deleted cape", 0x55FF55, 100);
        } catch (Exception e) {
            QuickSkin.LOGGER.error("Failed to delete cape", e);
            showImportMessage("⚠ Failed to delete cape", 0xFF5555, 100);
        }
    }

    private void showImportMessage(String message, int color, int duration) {
        this.importMessage = message;
        this.importMessageColor = color;
        this.importMessageTimer = duration;
    }

    @Override
    public void tick() {
        super.tick();
        if (importMessageTimer > 0) {
            importMessageTimer--;
        }
    }

    /**
     * Renders a moving star pattern background similar to the skin menu.
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
        PlatformHelper.blit(graphics, VIGNETTE_LOCATION, 0, 0, 0, 0.0F, 0.0F, this.width, this.height, this.width, this.height);

        // 4. Reset shader color but keep blend enabled for GUI elements
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
                PlatformHelper.blit(graphics, STAR_PATTERN_TEXTURE, 0, 0, 0, 0.0f, 0.0f, textureSize, textureSize, textureSize, textureSize);
                pose.popPose();
            }
        }

        pose.popPose();

        // Reset shader color but keep blend enabled
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Tick animations for animated cape thumbnails
        com.quickskin.mod.client.services.AnimatedTextureManager.getInstance().tick();

        // Render the animated star background
        this.renderBackgroundEffects(graphics, partialTick);

        // Flush and ensure clean render state
        graphics.flush();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        // Title
        graphics.drawCenteredString(this.font, this.title, this.width / 2, scaleValue(15), 0xFFFFFF);

        super.render(graphics, mouseX, mouseY, partialTick);

        // Push pose and translate forward in Z to render on top
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 100);

        // Enable scissor for grid content
        graphics.enableScissor(this.gridX, this.gridY,
                this.gridX + this.gridWidth, this.gridY + this.gridHeight);
        this.scrollOffset += (this.targetScrollOffset - this.scrollOffset) * 0.5;
        renderCapeGrid(graphics, mouseX, mouseY);
        graphics.disableScissor();

        this.renderScrollbar(graphics);

        // Pop pose
        graphics.pose().popPose();

        // Render import message
        if (importMessageTimer > 0 && !importMessage.isEmpty()) {
            int messageY = this.gridY + this.gridHeight + 10;
            graphics.drawCenteredString(this.font, importMessage, this.width / 2, messageY, importMessageColor);
        }

        // Tooltip logic
        if (isMouseOverGrid(mouseX, mouseY)) {
            CapeEntry hoveredCape = getCapeAt(mouseX, mouseY);
            if (hoveredCape != null) {
                boolean deleteHovered = false;
                int[] pos = getCapePosition(hoveredCape);
                if (pos != null && hoveredCape.isLocal()) {
                    int x = pos[0];
                    int y = pos[1];
                    int margin = 2;

                    int deleteButtonX = x + capeDisplaySize - ACTION_BUTTON_SIZE - margin;
                    int deleteButtonY = y + margin;
                    if (isMouseOver(mouseX, mouseY, deleteButtonX, deleteButtonY, ACTION_BUTTON_SIZE, ACTION_BUTTON_SIZE)) {
                        graphics.renderTooltip(this.font, Component.literal("Delete"), mouseX, mouseY);
                        deleteHovered = true;
                    }
                }
                if (!deleteHovered) {
                    graphics.renderTooltip(this.font, getCapeTooltip(hoveredCape), Optional.empty(), mouseX, mouseY);
                }
            }
        }
    }

    private void renderCapeGrid(GuiGraphics graphics, int mouseX, int mouseY) {
        int currentY = gridY - (int) scrollOffset;

        // --- SECTION 1: MY CAPES ---
        if (!localCapes.isEmpty()) {
            currentY = renderSection(graphics, "My Capes", localCapes, currentY, mouseX, mouseY, true);
        }

        // --- SECTION 2: DEFAULT CAPES ---
        if (!knownCapes.isEmpty()) {
            renderSection(graphics, "Default Capes", knownCapes, currentY + 20, mouseX, mouseY, false);
        }
    }

    private int renderSection(GuiGraphics graphics, String title, List<CapeEntry> capes, int startY, int mouseX, int mouseY, boolean isLocalSection) {
        // Render Header (centered within the grid)
        int headerY = startY + HEADER_HEIGHT / 2 - 4;
        if (headerY > gridY - 8 && headerY < gridY + gridHeight + 8) {
            int gridCenterX = this.gridX + (this.gridWidth / 2);
            graphics.drawCenteredString(this.font, title, gridCenterX, headerY, 0xFFFFFF);
        }
        int currentY = startY + HEADER_HEIGHT;

        // Render Grid Items
        for (int i = 0; i < capes.size(); i++) {
            CapeEntry cape = capes.get(i);
            int row = i / capesPerRow;
            int col = i % capesPerRow;

            int x = gridX + capePadding + col * (capeDisplaySize + capePadding);
            int y = currentY + capePadding + row * (capeDisplaySize + capePadding);

            if (y + capeDisplaySize < gridY || y > gridY + gridHeight) {
                continue; // Cull capes outside the visible area
            }

            renderCapeEntry(graphics, cape, x, y, mouseX, mouseY);
        }

        // For "My Capes", if the first row isn't full, render a drop zone in the remaining space
        if (isLocalSection && capes.size() < capesPerRow) {
            int col = capes.size() % capesPerRow;
            int dropZoneX = gridX + capePadding + col * (capeDisplaySize + capePadding);
            int dropZoneY = currentY + capePadding; // Y position of the first row
            int dropZoneWidth = (gridX + gridWidth) - dropZoneX - capePadding;
            int dropZoneHeight = capeDisplaySize;

            if (dropZoneWidth > capePadding) {
                renderDropZone(graphics, dropZoneX, dropZoneY, dropZoneWidth, dropZoneHeight, mouseX, mouseY);
            }
        }

        int rows = (int) Math.ceil((double) capes.size() / capesPerRow);
        return currentY + rows * (capeDisplaySize + capePadding);
    }


    private void renderDropZone(GuiGraphics graphics, int x, int y, int width, int height, int mouseX, int mouseY) {
        boolean isHovering = isMouseOver(mouseX, mouseY, x, y, width, height) &&
                mouseY >= gridY && mouseY < gridY + gridHeight;

        int bgColor = isHovering ? 0x2AFFFFFF : 0x1AFFFFFF;
        graphics.fill(x, y, x + width, y + height, bgColor);

        // Draw dashed border
        drawDashedBorder(graphics, x, y, width, height, isHovering);

        // Draw text
        int centerX = x + width / 2;
        int centerY = y + height / 2;
        Component mainMessage = Component.literal("Drop cape files here");
        Component subMessage = Component.literal("or click 'Import Cape'");

        int mainColor = isHovering ? 0xFFFFFF : 0xE0E0E0;
        int subColor = isHovering ? 0xB0B0B0 : 0x909090;

        if (height > font.lineHeight * 2.5 && width > font.width(subMessage)) {
            graphics.drawCenteredString(this.font, mainMessage, centerX, centerY - font.lineHeight / 2 - 1, mainColor);
            graphics.drawCenteredString(this.font, subMessage, centerX, centerY + font.lineHeight / 2 + 1, subColor);
        } else if (width > font.width(mainMessage)) {
            graphics.drawCenteredString(this.font, mainMessage, centerX, centerY - font.lineHeight / 2, mainColor);
        }
    }

    private void drawDashedBorder(GuiGraphics graphics, int x, int y, int width, int height, boolean highlight) {
        int color = highlight ? 0xFFFFFFFF : 0x80FFFFFF;
        int dashLength = 8;
        int gapLength = 4;
        int totalLength = dashLength + gapLength;

        // Top border
        for (int i = 0; i < width; i += totalLength) {
            int segmentLength = Math.min(dashLength, width - i);
            graphics.fill(x + i, y, x + i + segmentLength, y + 1, color);
        }

        // Bottom border
        for (int i = 0; i < width; i += totalLength) {
            int segmentLength = Math.min(dashLength, width - i);
            graphics.fill(x + i, y + height - 1, x + i + segmentLength, y + height, color);
        }

        // Left border
        for (int i = 0; i < height; i += totalLength) {
            int segmentLength = Math.min(dashLength, height - i);
            graphics.fill(x, y + i, x + 1, y + i + segmentLength, color);
        }

        // Right border
        for (int i = 0; i < height; i += totalLength) {
            int segmentLength = Math.min(dashLength, height - i);
            graphics.fill(x + width - 1, y + i, x + width, y + i + segmentLength, color);
        }
    }

    private void renderCapeEntry(GuiGraphics graphics, CapeEntry cape, int x, int y, int mouseX, int mouseY) {
        boolean hovered = isMouseOver(mouseX, mouseY, x, y, capeDisplaySize, capeDisplaySize);

        // Special handling for "None" option
        if (cape.isKnown() && cape.getKnownCape() != null && cape.getKnownCape().isNoCape()) {
            // Render black background
            graphics.fill(x, y, x + capeDisplaySize, y + capeDisplaySize, 0x90000000);

            // Render "None" text centered
            graphics.drawCenteredString(this.font, "None", x + capeDisplaySize / 2,
                    y + capeDisplaySize / 2 - 4, 0xFFFFFF);

            // Highlight if selected or hovered
            if (isSelected(cape)) {
                graphics.renderOutline(x - 2, y - 2, capeDisplaySize + 4, capeDisplaySize + 4, 0xFFFFFF00);
            } else if (hovered) {
                graphics.fill(x, y, x + capeDisplaySize, y + capeDisplaySize, 0x33FFFFFF);
            }
            return;
        }

        // Regular cape rendering
        ResourceLocation texture = cape.getTextureLocation();

        // If animated, get the current frame texture instead of the atlas
        if (texture != null && cape.isAnimated()) {
            String capeId = cape.getCapeId();
            String animationId = null;

            if (capeId.startsWith("local_cape:")) {
                animationId = "cape_" + capeId.substring("local_cape:".length());
            } else if (capeId.startsWith("known:")) {
                animationId = "cape_known_" + capeId.substring("known:".length());
            }

            if (animationId != null) {
                ResourceLocation frameTexture = com.quickskin.mod.client.services.AnimatedTextureManager
                    .getInstance().getCurrentFrameTexture(animationId);
                if (frameTexture != null) {
                    texture = frameTexture;
                }
            }
        }

        // Render cape texture
        if (texture != null) {
            renderCapeTexture(graphics, texture, cape, x, y);
        } else {
            renderLoadingTexture(graphics, x, y);
        }

        // Render custom indicator
        if (cape.isCustom()) {
            renderCustomIndicator(graphics, x, y);
        }

        // Render animated indicator if applicable
        if (cape.isAnimated()) {
            renderAnimatedIndicator(graphics, x, y);
        }

        // Highlight if selected or hovered
        if (isSelected(cape)) {
            graphics.renderOutline(x - 2, y - 2, capeDisplaySize + 4, capeDisplaySize + 4, 0xFFFFFF00);
        } else if (hovered) {
            graphics.fill(x, y, x + capeDisplaySize, y + capeDisplaySize, 0x33FFFFFF);
        }

        // Render delete button on hover (only for local capes, not "None")
        if (hovered && cape.isLocal() && !cape.isKnown()) {
            int margin = 2;
            int deleteButtonX = x + capeDisplaySize - ACTION_BUTTON_SIZE - margin;
            int deleteButtonY = y + margin;
            boolean deleteHovered = isMouseOver(mouseX, mouseY, deleteButtonX, deleteButtonY, ACTION_BUTTON_SIZE, ACTION_BUTTON_SIZE);
            int deleteBgColor = deleteHovered ? 0xA0E04040 : 0x80C00000;
            graphics.fill(deleteButtonX, deleteButtonY, deleteButtonX + ACTION_BUTTON_SIZE, deleteButtonY + ACTION_BUTTON_SIZE, deleteBgColor);
            graphics.drawString(this.font, "x", deleteButtonX + 3, deleteButtonY + 1, 0xFFFFFFFF);
        }
    }

    private void renderCapeTexture(GuiGraphics graphics, ResourceLocation texture, CapeEntry cape, int x, int y) {
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        int textureWidth = 64;
        int textureHeight = 32;

        // Check if it's a high resolution cape
        if (cape.isLocal() && cape.getLocalCape() != null && cape.getLocalCape().resolution() != null && cape.getLocalCape().resolution().isHD()) {
            int scale = cape.getLocalCape().resolution().getScale();
            textureWidth *= scale;
            textureHeight *= scale;
        }

        // Cape coordinates (show back of cape)
        int u = 1;
        int v = 1;
        int uWidth = 10;
        int vHeight = 16;

        float scaleFactor = capeDisplaySize / 56f;

        graphics.pose().pushPose();
        graphics.pose().translate(x + capeDisplaySize / 2f, y + capeDisplaySize / 2f, 0);
        graphics.pose().scale(scaleFactor * 3.5f, scaleFactor * 3.5f, 1.0f);
        graphics.pose().translate(-5, -8, 0);

        PlatformHelper.blit(graphics, texture, 0, 0, 10, 16, u, v, uWidth, vHeight, textureWidth, textureHeight);

        graphics.pose().popPose();
    }

    private void renderLoadingTexture(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + capeDisplaySize, y + capeDisplaySize, 0xFF222222);
        graphics.drawCenteredString(this.font, "Loading",
                x + capeDisplaySize / 2, y + capeDisplaySize / 2 - 4, 0x888888);
    }

    private void renderCustomIndicator(GuiGraphics graphics, int x, int y) {
        int indicatorSize = Math.max(4, capeDisplaySize / 16);
        int rarityColor = 0xFF5555FF; // Purple for custom
        graphics.fill(x + capeDisplaySize - indicatorSize * 2,
                y + capeDisplaySize - indicatorSize * 2,
                x + capeDisplaySize - indicatorSize / 2,
                y + capeDisplaySize - indicatorSize / 2,
                rarityColor);
    }

    private void renderAnimatedIndicator(GuiGraphics graphics, int x, int y) {
        String badgeText = "GIF";
        int textWidth = this.font.width(badgeText);
        int badgeWidth = textWidth + 4;
        int badgeHeight = this.font.lineHeight + 2;
        int margin = 2;

        int badgeX = x + margin;
        int badgeY = y + margin;

        int bgColor = 0xD000CCFF;
        graphics.fill(badgeX, badgeY, badgeX + badgeWidth, badgeY + badgeHeight, bgColor);

        int borderColor = 0xFF00AADD;
        graphics.renderOutline(badgeX, badgeY, badgeWidth, badgeHeight, borderColor);

        graphics.drawString(this.font, badgeText, badgeX + 2, badgeY + 1, 0xFFFFFFFF);
    }

    private boolean isSelected(CapeEntry cape) {
        if (cape == null) return false;

        // If no cape is selected and this is the "None" option, it's selected
        if (selectedCape == null) {
            return cape.isKnown() && cape.getKnownCape() != null && cape.getKnownCape().isNoCape();
        }

        return cape.getCapeId().equals(selectedCape.getCapeId());
    }

    private List<Component> getCapeTooltip(CapeEntry cape) {
        List<Component> tooltip = new ArrayList<>();

        int nameColor = cape.isKnown() ? 0xFFD700 : 0x55FF55; // Gold for known, green for local
        tooltip.add(Component.literal(cape.getFriendlyName()).withStyle(s -> s.withBold(true).withColor(nameColor)));

        tooltip.add(Component.literal(cape.getDescription()).withStyle(s -> s.withColor(0xCCCCCC)));

        if (cape.isAnimated()) {
            tooltip.add(Component.literal("Animated cape").withStyle(s -> s.withColor(0xFFAA00)));
        } else {
            tooltip.add(Component.literal("Static cape").withStyle(s -> s.withColor(0xAAAAAA)));
        }

        if (cape.isLocal() && cape.getLocalCape() != null && cape.getLocalCape().resolution() != null) {
            String resolutionText = cape.getLocalCape().resolution().name();
            tooltip.add(Component.literal("Resolution: " + resolutionText).withStyle(s -> s.withColor(0x55FFFF)));
        }

        tooltip.add(Component.empty());
        tooltip.add(Component.literal("Click to preview").withStyle(s -> s.withColor(0x808080).withItalic(true)));

        return tooltip;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;

        // Handle scrollbar dragging
        if (button == 0 && this.maxScroll > 0) {
            int scrollbarWidth = scaleValue(6);
            int scrollbarX = this.gridX + this.gridWidth + 3;
            int scrollbarTrackHeight = this.gridHeight;

            if (mouseX >= scrollbarX && mouseX < scrollbarX + scrollbarWidth &&
                    mouseY >= this.gridY && mouseY < this.gridY + scrollbarTrackHeight) {

                this.isDraggingScrollbar = true;

                int thumbHeight = Mth.clamp((int) ((float) (scrollbarTrackHeight * scrollbarTrackHeight) / (float) this.totalContentHeight),
                        scaleValue(32), scrollbarTrackHeight - 8);
                int thumbY = this.gridY + (int) (this.scrollOffset * (double) (scrollbarTrackHeight - thumbHeight) / (double) this.maxScroll);

                if (mouseY >= thumbY && mouseY < thumbY + thumbHeight) {
                    this.scrollbarClickOffset = mouseY - thumbY;
                } else {
                    this.scrollbarClickOffset = thumbHeight / 2.0;
                    updateScrollFromMouse(mouseY);
                }
                return true;
            }
        }

        // Handle cape selection
        if (button == 0 && isMouseOverGrid((int) mouseX, (int) mouseY)) {
            CapeEntry clickedCape = getCapeAt((int) mouseX, (int) mouseY);
            if (clickedCape != null) {
                // Check for delete button click (only for local capes, not "None")
                if (clickedCape.isLocal() && !clickedCape.isKnown()) {
                    int[] pos = getCapePosition(clickedCape);
                    if (pos != null) {
                        int x = pos[0];
                        int y = pos[1];
                        int margin = 2;

                        int deleteButtonX = x + capeDisplaySize - ACTION_BUTTON_SIZE - margin;
                        int deleteButtonY = y + margin;
                        if (isMouseOver((int) mouseX, (int) mouseY, deleteButtonX, deleteButtonY, ACTION_BUTTON_SIZE, ACTION_BUTTON_SIZE)) {
                            showDeleteConfirmation(clickedCape);
                            return true;
                        }
                    }
                }

                // Play selection sound
                if (minecraft != null && minecraft.getSoundManager() != null) {
                    minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 0.25f
                    ));
                }

                // Select cape (includes "None" option)
                this.selectedCape = clickedCape;
                if (clickedCape.isKnown() && clickedCape.getKnownCape() != null && clickedCape.getKnownCape().isNoCape()) {
                    removeCape();
                } else {
                    applyCape(clickedCape);
                }
                return true;
            }
        }

        return false;
    }

    private void applyCape(CapeEntry cape) {
        String capeId = cape.getCapeId();

        // Get the config
        ClientConfig config = ClientConfig.getInstance();

        // NOTE: We do NOT unregister the old animation while the menu is open.
        // All animated capes are pre-registered in registerAllAnimations() for thumbnail display.
        // If we unregister an animation when switching capes, the old cape's thumbnail will
        // fall back to using the full atlas texture, displaying all frames at once instead of
        // animating properly. Animations will be cleaned up appropriately when the menu closes
        // or when the game state changes (e.g., leaving the world).

        // IMPORTANT: Call CapeService.getCapeLocation() to trigger animation registration
        // This must be done BEFORE setting the preview widget
        ResourceLocation capeLocation = com.quickskin.mod.client.services.CapeService.getInstance()
                .getCapeLocation(null, capeId);

        // Fallback to direct texture if service returns null
        if (capeLocation == null) {
            capeLocation = cape.getTextureLocation();
        }

        // Always update preview widget (works both in-game and on title screen)
        QuickSkin.LOGGER.info("[PlayerCapeMenuScreen] Setting cape in preview widget: {}", capeLocation);
        playerWidget.setCape(capeLocation, capeId);

        // Save to config for persistence
        config.activeCapeHash = capeId;
        config.save();
        QuickSkin.LOGGER.info("Saved cape to config: {}", capeId);

        // Apply to PlayerAppearanceService
        if (minecraft != null && minecraft.player != null) {
            // In-game: use the real player's UUID
            PlayerAppearanceService.getInstance()
                    .applyCape(minecraft.player.getUUID(), capeId);
            QuickSkin.LOGGER.info("Applied cape to in-game player: {}", cape.getFriendlyName());
        } else {
            // Title screen: use a dummy UUID that matches the cached player if it exists
            // This allows entity rendering to work on title screen with cached player
            java.util.UUID dummyUUID = getDummyPlayerUUID();
            if (dummyUUID != null) {
                PlayerAppearanceService.getInstance()
                        .applyCape(dummyUUID, capeId);
                QuickSkin.LOGGER.info("Applied cape to cached player for preview: {}", cape.getFriendlyName());
            } else {
                QuickSkin.LOGGER.info("Applied cape to preview only (no cached player): {}", cape.getFriendlyName());
            }
        }

        // Update speed slider visibility based on whether the selected cape is animated
        updateSpeedSliderVisibility();
    }

    /**
     * Get the UUID of the cached player entity used for rendering
     */
    private java.util.UUID getDummyPlayerUUID() {
        // Access the cached player from PlayerModelRenderer
        // This is a bit hacky but necessary for title screen rendering
        try {
            var cachedPlayerField = PlayerModelRenderer.class.getDeclaredField("cachedPlayer");
            cachedPlayerField.setAccessible(true);
            var cachedPlayer = (net.minecraft.world.entity.player.Player) cachedPlayerField.get(null);
            return cachedPlayer != null ? cachedPlayer.getUUID() : null;
        } catch (Exception e) {
            QuickSkin.LOGGER.error("Failed to get cached player UUID", e);
            return null;
        }
    }

    private void updateScrollFromMouse(double mouseY) {
        int scrollbarTrackHeight = this.gridHeight;
        int thumbHeight = Mth.clamp((int) ((float) (scrollbarTrackHeight * scrollbarTrackHeight) / (float) this.totalContentHeight),
                scaleValue(32), scrollbarTrackHeight - 8);

        double scrollableTrackHeight = scrollbarTrackHeight - thumbHeight;
        if (scrollableTrackHeight > 0) {
            double scrollRatio = (mouseY - this.gridY - this.scrollbarClickOffset) / scrollableTrackHeight;
            this.targetScrollOffset = Mth.clamp(scrollRatio * this.maxScroll, 0, this.maxScroll);
        }
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.isDraggingScrollbar) {
            updateScrollFromMouse(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            this.isDraggingScrollbar = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (isMouseOverGrid((int) mouseX, (int) mouseY)) {
            this.targetScrollOffset = Mth.clamp(this.targetScrollOffset - deltaY * scrollSpeed, 0.0D, this.maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    private void renderScrollbar(GuiGraphics graphics) {
        if (this.maxScroll <= 0) return;

        int scrollbarWidth = scaleValue(6);
        int scrollbarX = this.gridX + this.gridWidth + 3;
        int scrollbarTrackEnd = this.gridY + this.gridHeight;

        graphics.fill(scrollbarX, this.gridY, scrollbarX + scrollbarWidth, scrollbarTrackEnd, 0x80000000);

        int thumbHeight = Mth.clamp((int) ((float) (this.gridHeight * this.gridHeight) / (float) this.totalContentHeight),
                scaleValue(32), this.gridHeight - 8);
        int thumbY = this.gridY + (int) (this.scrollOffset * (double) (this.gridHeight - thumbHeight) / (double) this.maxScroll);

        graphics.fill(scrollbarX, thumbY, scrollbarX + scrollbarWidth, thumbY + thumbHeight, -8355712);
        graphics.fill(scrollbarX + 1, thumbY + 1, scrollbarX + scrollbarWidth - 1, thumbY + thumbHeight - 1, -4144960);
    }

    @Nullable
    private CapeEntry getCapeAt(int mouseX, int mouseY) {
        if (!isMouseOverGrid(mouseX, mouseY)) return null;

        int absoluteMouseY = mouseY + (int) scrollOffset;
        int currentY = gridY;

        // --- Check "My Capes" section ---
        if (!localCapes.isEmpty()) {
            currentY += HEADER_HEIGHT;
            int rows = (int) Math.ceil((double) localCapes.size() / capesPerRow);
            int sectionHeight = rows * (capeDisplaySize + capePadding) + capePadding;

            if (absoluteMouseY >= currentY && absoluteMouseY < currentY + sectionHeight) {
                CapeEntry cape = findCapeInGrid(mouseX, mouseY, absoluteMouseY, currentY, localCapes);
                if (cape != null) return cape;
            }
            currentY += sectionHeight;
        }

        // --- Check "Default Capes" section ---
        if (!knownCapes.isEmpty()) {
            // The rendering logic in renderCapeGridOptimized adds a 20px gap before this section.
            // We must add it here as well to keep the click detection synchronized with the visuals.
            currentY += 20;

            currentY += HEADER_HEIGHT;
            int rows = (int) Math.ceil((double) knownCapes.size() / capesPerRow);
            int sectionHeight = rows * (capeDisplaySize + capePadding) + capePadding;

            if (absoluteMouseY >= currentY && absoluteMouseY < currentY + sectionHeight) {
                return findCapeInGrid(mouseX, mouseY, absoluteMouseY, currentY, knownCapes);
            }
        }

        return null;
    }

    private CapeEntry findCapeInGrid(int mouseX, int mouseY, int absoluteMouseY, int sectionTopY, List<CapeEntry> capes) {
        int relX = mouseX - this.gridX - capePadding;
        int relY = absoluteMouseY - sectionTopY - capePadding;

        int col = relX / (capeDisplaySize + capePadding);
        int row = relY / (capeDisplaySize + capePadding);

        if (col < 0 || col >= capesPerRow) return null;

        int index = row * capesPerRow + col;

        if (index >= 0 && index < capes.size()) {
            int capeX = this.gridX + capePadding + col * (capeDisplaySize + capePadding);
            // We need the on-screen Y to check bounds, not the absolute Y
            int capeY = sectionTopY + capePadding + row * (capeDisplaySize + capePadding) - (int) this.scrollOffset;
            if (isMouseOver(mouseX, mouseY, capeX, capeY, capeDisplaySize, capeDisplaySize)) {
                return capes.get(index);
            }
        }
        return null;
    }

    @Nullable
    private int[] getCapePosition(CapeEntry cape) {
        int currentY = gridY - (int) scrollOffset;

        // Check "My Capes" section
        if (!localCapes.isEmpty()) {
            currentY += HEADER_HEIGHT;
            int index = localCapes.indexOf(cape);
            if (index != -1) {
                int row = index / capesPerRow;
                int col = index % capesPerRow;
                int x = gridX + capePadding + col * (capeDisplaySize + capePadding);
                int y = currentY + capePadding + row * (capeDisplaySize + capePadding);
                return new int[]{x, y};
            }
            int rows = (int) Math.ceil((double) localCapes.size() / capesPerRow);
            currentY += rows * (capeDisplaySize + capePadding);
        }

        // Check "Default Capes" section
        if (!knownCapes.isEmpty()) {
            currentY += 20; // Extra spacing
            currentY += HEADER_HEIGHT;
            int index = knownCapes.indexOf(cape);
            if (index != -1) {
                int row = index / capesPerRow;
                int col = index % capesPerRow;
                int x = gridX + capePadding + col * (capeDisplaySize + capePadding);
                int y = currentY + capePadding + row * (capeDisplaySize + capePadding);
                return new int[]{x, y};
            }
        }
        return null;
    }

    private boolean isMouseOverGrid(int mouseX, int mouseY) {
        return mouseX >= this.gridX && mouseX < this.gridX + this.gridWidth &&
                mouseY >= this.gridY && mouseY < this.gridY + this.gridHeight;
    }

    private boolean isMouseOver(int mouseX, int mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    @Override
    public void onFilesDrop(List<Path> paths) {
        List<Path> validFiles = paths.stream()
                .filter(p -> {
                    String name = p.toString().toLowerCase();
                    return name.endsWith(".png") || name.endsWith(".gif");
                })
                .toList();

        if (validFiles.isEmpty()) {
            showImportMessage("No PNG or GIF files found", 0xFFAA00, 100);
            return;
        }

        showImportMessage("Processing " + validFiles.size() + " file(s)...", 0x55AAFF, 60);

        CompletableFuture.runAsync(() -> {
            int successCount = 0;
            int invalidCount = 0;

            Path capesDir = LocalAssetManager.getInstance().getCapesDirectory();
            try {
                Files.createDirectories(capesDir);
            } catch (IOException e) {
                Minecraft.getInstance().execute(() ->
                        showImportMessage("Error: Could not create capes directory", 0xFF5555, 100));
                return;
            }

            for (Path file : validFiles) {
                if (processDroppedFile(file, capesDir)) {
                    successCount++;
                } else {
                    invalidCount++;
                }
            }

            int finalSuccessCount = successCount;
            int finalInvalidCount = invalidCount;

            Minecraft.getInstance().execute(() -> {
                if (finalSuccessCount > 0) {
                    // THIS IS THE FIX: Reload assets BEFORE refreshing the list
                    LocalAssetManager.getInstance().reload();

                    refreshCapeList();
                    updateGridDimensions();

                    String message = String.format("✓ Imported %d cape%s", finalSuccessCount,
                            finalSuccessCount == 1 ? "" : "s");
                    if (finalInvalidCount > 0) {
                        message += String.format(" (%d invalid)", finalInvalidCount);
                    }
                    showImportMessage(message, finalSuccessCount > finalInvalidCount ? 0x55FF55 : 0xFFAA00, 200);
                } else {
                    showImportMessage("⚠ No valid capes found (must be 2:1 ratio or animation strip)", 0xFF5555, 200);
                }
            });
        }).exceptionally(throwable -> {
            Minecraft.getInstance().execute(() ->
                    showImportMessage("Error processing files: " + throwable.getMessage(), 0xFF5555, 200));
            return null;
        });
    }

    private boolean processDroppedFile(Path sourceFile, Path targetDir) {
        try {
            String lowerCaseName = sourceFile.toString().toLowerCase();
            boolean isGif = lowerCaseName.endsWith(".gif");

            java.awt.image.BufferedImage sourceAtlas;
            int frameCount = 1;
            boolean isStandardFormat;
            com.quickskin.mod.common.data.AnimationMetadata animationMetadata = null;
            byte[] finalAtlasBytes;

            // Step 1: Load image into a source atlas and determine its format
            if (isGif) {
                try (java.io.InputStream is = Files.newInputStream(sourceFile)) {
                    com.quickskin.mod.common.util.StbGifLoader.GifLoadResult gifResult = com.quickskin.mod.common.util.StbGifLoader.loadGif(is);
                    if (gifResult == null || gifResult.frames() == null) return false;

                    try {
                        // Convert NativeImage frames to BufferedImage atlas
                        int width = gifResult.frameWidth();
                        int height = gifResult.frameHeight();
                        frameCount = gifResult.frames().length;
                        int atlasHeight = height * frameCount;

                        sourceAtlas = new java.awt.image.BufferedImage(width, atlasHeight, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                        for (int i = 0; i < frameCount; i++) {
                            com.mojang.blaze3d.platform.NativeImage frame = gifResult.frames()[i];
                            for (int y = 0; y < height; y++) {
                                for (int x = 0; x < width; x++) {
                                    int abgr = PlatformHelper.getPixel(frame, x, y);
                                    // Convert ABGR to ARGB for BufferedImage
                                    int a = (abgr >> 24) & 0xFF;
                                    int b = (abgr >> 16) & 0xFF;
                                    int g = (abgr >> 8) & 0xFF;
                                    int r = abgr & 0xFF;
                                    int argb = (a << 24) | (r << 16) | (g << 8) | b;
                                    sourceAtlas.setRGB(x, i * height + y, argb);
                                }
                            }
                        }

                        animationMetadata = gifResult.metadata();
                        isStandardFormat = true;
                    } finally {
                        // Clean up NativeImage frames
                        gifResult.close();
                    }
                }
            } else { // Is PNG
                java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(sourceFile.toFile());
                if (image == null) return false;
                sourceAtlas = image;

                int w = image.getWidth();
                int h = image.getHeight();
                int frameHeightIfCape = w / 2;

                isStandardFormat = false;
                if (w > 0 && h > 0 && w % 2 == 0 && h % frameHeightIfCape == 0) {
                    com.quickskin.mod.common.data.SkinResolution frameRes = com.quickskin.mod.common.data.SkinResolution.fromDimensions(w, frameHeightIfCape);
                    if (frameRes != null) {
                        isStandardFormat = true;
                        frameCount = h / frameHeightIfCape;
                    }
                }
            }

            java.awt.image.BufferedImage finalAtlas;

            // Step 2: Process the source atlas based on its format
            if (isStandardFormat) {
                QuickSkin.LOGGER.info("Processing as a standard cape format ({} frames): {}", frameCount, sourceFile.getFileName());

                // ### START FIX: Use resizeAnimationStrip for multi-frame images ###
                java.awt.image.BufferedImage normalizedAtlas;
                if (frameCount > 1) {
                    // This is an animation strip, resize it while preserving the vertical frames.
                    normalizedAtlas = com.quickskin.mod.common.util.HDTextureProcessor.resizeAnimationStrip(sourceAtlas, 64);
                } else {
                    // This is a single static image, use the standard downsampler.
                    normalizedAtlas = com.quickskin.mod.common.util.HDTextureProcessor.downsample(sourceAtlas, 64);
                }
                // ### END FIX ###

                // Check if the elytra area is transparent
                if (isElytraAreaTransparent(normalizedAtlas)) {
                    QuickSkin.LOGGER.info("Detected transparent elytra. Compositing with vanilla elytra.");
                    java.awt.image.BufferedImage vanillaElytraBase = getVanillaElytraImage();
                    if (vanillaElytraBase == null) { // Fallback if vanilla elytra fails to load
                        finalAtlas = normalizedAtlas;
                    } else {
                        java.awt.image.BufferedImage compositeAtlas = new java.awt.image.BufferedImage(normalizedAtlas.getWidth(), normalizedAtlas.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
                        java.awt.Graphics2D g = compositeAtlas.createGraphics();

                        g.setComposite(java.awt.AlphaComposite.Clear);
                        g.fillRect(0, 0, normalizedAtlas.getWidth(), normalizedAtlas.getHeight());
                        g.setComposite(java.awt.AlphaComposite.SrcOver);

                        for(int i = 0; i < frameCount; i++) {
                            int yOffset = i * 32;
                            g.drawImage(vanillaElytraBase, 0, yOffset, null);
                            g.drawImage(normalizedAtlas.getSubimage(0, yOffset, 64, 32), 0, yOffset, null);
                        }
                        g.dispose();
                        finalAtlas = compositeAtlas;
                    }
                } else {
                    finalAtlas = normalizedAtlas;
                }
            } else {
                QuickSkin.LOGGER.info("Processing non-standard image as a custom static cape: {}", sourceFile.getFileName());
                java.awt.image.BufferedImage vanillaElytraBase = getVanillaElytraImage();
                if (vanillaElytraBase == null) {
                    vanillaElytraBase = new java.awt.image.BufferedImage(64, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                }

                java.awt.image.BufferedImage newCapeTexture = new java.awt.image.BufferedImage(64, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g = newCapeTexture.createGraphics();

                g.setComposite(java.awt.AlphaComposite.Clear);
                g.fillRect(0, 0, 64, 32);
                g.setComposite(java.awt.AlphaComposite.SrcOver);

                g.drawImage(vanillaElytraBase, 0, 0, null);
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(java.awt.RenderingHints.KEY_ALPHA_INTERPOLATION, java.awt.RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
                g.drawImage(sourceAtlas, 0, 0, 22, 17, null);
                g.dispose();

                finalAtlas = newCapeTexture;
            }

            try (var baos = new java.io.ByteArrayOutputStream()) {
                javax.imageio.ImageIO.write(finalAtlas, "png", baos);
                finalAtlasBytes = baos.toByteArray();
            }

            if (animationMetadata != null) {
                String hash = HashUtil.computeHash(finalAtlasBytes);
                if (hash != null) {
                    Path metadataPath = LocalAssetManager.getInstance().getCacheDirectory().resolve(hash + ".json");
                    Files.writeString(metadataPath, animationMetadata.toJson());
                    QuickSkin.LOGGER.info("Saved animation metadata for imported GIF: {}", metadataPath);
                }
            }

            Path targetPath = resolveTargetPath(sourceFile, targetDir);
            saveImageWithAlpha(finalAtlas, targetPath);
            return true;

        } catch (IOException e) {
            QuickSkin.LOGGER.error("Error processing dropped file {}: {}", sourceFile.getFileName(), e.getMessage());
        }
        return false;
    }

    private boolean isElytraAreaTransparent(java.awt.image.BufferedImage image) {
        double scale = image.getWidth() / 64.0;
        int elytraX = (int) (22 * scale);
        int elytraY = 0;
        int elytraWidth = (int) (32 * scale);
        int elytraHeight = (int) (16 * scale);

        int samplePoints = 5;
        for (int i = 0; i < samplePoints; i++) {
            for (int j = 0; j < samplePoints; j++) {
                int x = elytraX + (i * elytraWidth / (samplePoints - 1));
                int y = elytraY + (j * elytraHeight / (samplePoints - 1));
                x = Math.min(x, image.getWidth() - 1);
                y = Math.min(y, image.getHeight() - 1);
                int pixel = image.getRGB(x, y);
                int alpha = (pixel >> 24) & 0xFF;
                if (alpha > 10) {
                    return false;
                }
            }
        }
        return true;
    }

    @Nullable
    private java.awt.image.BufferedImage getVanillaElytraImage() {
        try {
            ResourceLocation VANILLA_ELYTRA_TEXTURE = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/entity/elytra.png");
            var resourceOptional = Minecraft.getInstance().getResourceManager().getResource(VANILLA_ELYTRA_TEXTURE);
            if (resourceOptional.isEmpty()) {
                QuickSkin.LOGGER.error("Vanilla elytra texture resource not found");
                return null;
            }
            java.io.InputStream stream = resourceOptional.get().open();
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(stream);
            stream.close();
            return image;
        } catch (IOException e) {
            QuickSkin.LOGGER.error("Failed to load vanilla elytra texture for custom cape creation", e);
            return null;
        }
    }

    private Path resolveTargetPath(Path sourceFile, Path targetDir) {
        String fileName = sourceFile.getFileName().toString();
        String nameWithoutExt = fileName.substring(0, fileName.lastIndexOf('.'));
        String ext = ".png";

        Path targetPath = targetDir.resolve(nameWithoutExt + ext);
        int counter = 1;
        while (Files.exists(targetPath)) {
            targetPath = targetDir.resolve(nameWithoutExt + "_" + counter + ext);
            counter++;
        }
        return targetPath;
    }

    private void saveImageWithAlpha(java.awt.image.BufferedImage image, Path outputPath) throws IOException {
        java.awt.image.BufferedImage argbImage;
        if (image.getType() != java.awt.image.BufferedImage.TYPE_INT_ARGB) {
            argbImage = new java.awt.image.BufferedImage(image.getWidth(), image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = argbImage.createGraphics();
            g.setComposite(java.awt.AlphaComposite.Src);
            g.drawImage(image, 0, 0, null);
            g.dispose();
        } else {
            argbImage = image;
        }

        javax.imageio.ImageIO.write(argbImage, "png", outputPath.toFile());
    }

    @Override
    public boolean isPauseScreen() {
        // Don't pause game when this screen is open
        return false;
    }

    @Override
    public void renderBlurredBackground(float partialTick) {
        // Disable the default blur effect - we have our own custom background
    }

    @Override
    public void renderBackground(net.minecraft.client.gui.GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Disable the default dark background overlay - we render our own custom background
    }

    @Override
    public void onClose() {
        // Unregister all animations when closing the menu
        unregisterAllAnimations();

        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    /**
     * Unregister all animations when the menu is closed to clean up resources
     */
    private void unregisterAllAnimations() {
        com.quickskin.mod.client.services.AnimatedTextureManager animManager =
            com.quickskin.mod.client.services.AnimatedTextureManager.getInstance();

        // Unregister animations for all local capes
        for (CapeEntry cape : localCapes) {
            if (cape.isAnimated()) {
                String capeId = cape.getCapeId();
                String animationId = null;

                if (capeId.startsWith("local_cape:")) {
                    animationId = "cape_" + capeId.substring("local_cape:".length());
                }

                if (animationId != null) {
                    animManager.unregisterAnimation(animationId);
                    QuickSkin.LOGGER.debug("[PlayerCapeMenuScreen] Unregistered animation on close: {}", animationId);
                }
            }
        }

        // Unregister animations for all known capes
        for (CapeEntry cape : knownCapes) {
            if (cape.isAnimated()) {
                String capeId = cape.getCapeId();
                String animationId = null;

                if (capeId.startsWith("known:")) {
                    animationId = "cape_known_" + capeId.substring("known:".length());
                }

                if (animationId != null) {
                    animManager.unregisterAnimation(animationId);
                    QuickSkin.LOGGER.debug("[PlayerCapeMenuScreen] Unregistered animation on close: {}", animationId);
                }
            }
        }
    }

    /**
     * Speed slider for controlling per-cape animation speed
     * Uses quadratic mapping for finer control at lower speeds
     */
    private class SpeedSlider extends AbstractSliderButton {
        // Speed range: 0.1 (10%) to 3.0 (300%)
        final double minSpeed = 0.1;
        final double maxSpeed = 3.0;

        public SpeedSlider(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty(), 0);

            this.setTooltip(Tooltip.create(Component.literal("Controls animation speed for this cape (10% - 300%)")));
            loadSpeedForCurrentCape();
        }

        /**
         * Load the speed for the currently selected cape
         */
        public void loadSpeedForCurrentCape() {
            if (selectedCape == null || !selectedCape.isAnimated()) {
                this.value = 0.5; // Default to middle (100%)
                updateMessage();
                return;
            }

            // Get speed for this specific cape
            String capeId = selectedCape.getCapeId();
            double currentSpeed = ClientConfig.getInstance().getCapeAnimationSpeed(capeId);
            double clampedSpeed = Mth.clamp(currentSpeed, minSpeed, maxSpeed);

            // Reverse the quadratic mapping to find the slider position
            // speed = minSpeed + (v^2) * (maxSpeed - minSpeed)
            // v = sqrt((speed - minSpeed) / (maxSpeed - minSpeed))
            this.value = Math.sqrt((clampedSpeed - minSpeed) / (maxSpeed - minSpeed));

            updateMessage();
        }

        @Override
        protected void updateMessage() {
            // Display percentage (10% to 300%)
            int percentage = (int) Math.round(10 + this.value * this.value * 290);
            setMessage(Component.literal(String.format("Animation Speed: %d%%", percentage)));
        }

        @Override
        protected void applyValue() {
            if (selectedCape == null || !selectedCape.isAnimated()) {
                return;
            }

            // Apply quadratic mapping for finer control at lower speeds
            double v = this.value;
            double speed = minSpeed + (v * v) * (maxSpeed - minSpeed);
            // Clamp to prevent invalid values
            speed = Math.max(0.01, Math.min(speed, 10.0));

            // Save to config for this specific cape
            String capeId = selectedCape.getCapeId();
            ClientConfig.getInstance().setCapeAnimationSpeed(capeId, (float) speed);

            // Update the active animation's speed in real-time
            String animationId = getAnimationIdForCape(capeId);
            if (animationId != null) {
                com.quickskin.mod.client.services.AnimatedTextureManager.getInstance()
                    .setAnimationSpeed(animationId, (float) speed);
            }
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            // Save config when slider is released
            ClientConfig.getInstance().save();

            if (selectedCape != null) {
                float speed = ClientConfig.getInstance().getCapeAnimationSpeed(selectedCape.getCapeId());
                QuickSkin.LOGGER.info("Saved animation speed for {}: {}x", selectedCape.getCapeId(), speed);
            }
        }

        /**
         * Get the animation ID for a given cape ID
         */
        private String getAnimationIdForCape(String capeId) {
            if (capeId == null) return null;

            if (capeId.startsWith("local_cape:")) {
                String hash = capeId.substring("local_cape:".length());
                return "cape_" + hash;
            } else if (capeId.startsWith("known:")) {
                String knownId = capeId.substring("known:".length());
                return "cape_known_" + knownId;
            }

            return null;
        }
    }
}