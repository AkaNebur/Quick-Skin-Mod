package com.quickskin.mod.client.gui.screen;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.gui.util.BackgroundRenderer;
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
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Cape selection menu for QuickSkin with grid-based layout
 */
@Environment(EnvType.CLIENT)
public class PlayerCapeMenuScreen extends Screen {

    // Background textures
    private static final Identifier STAR_PATTERN_TEXTURE = Identifier.fromNamespaceAndPath(QuickSkin.MOD_ID, "textures/gui/background/star_pattern.png");
    private static final Identifier VIGNETTE_LOCATION = Identifier.withDefaultNamespace("textures/misc/vignette.png");

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
    private int importMessageColor = 0xFFFFFFFF;

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
                Component.translatable("quickskin.button.import_cape"),
                button -> importCape()
        ));

        Button removeButton = this.addRenderableWidget(com.quickskin.mod.client.gui.util.ButtonFactory.createStyled(
                buttonStartX + buttonWidth + buttonSpacing, bottomY, buttonWidth, scaleValue(20),
                Component.translatable("quickskin.button.remove_cape"),
                button -> removeCape()
        ));

        Button closeButton = this.addRenderableWidget(com.quickskin.mod.client.gui.util.ButtonFactory.createPrimary(
                buttonStartX + (buttonWidth + buttonSpacing) * 2, bottomY, buttonWidth, scaleValue(20),
                Component.translatable("quickskin.button.done"),
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
        Identifier skinLocation = null;
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
            skinLocation = player.getSkin().body().texturePath();

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
                    modelType = player.getSkin().model() == net.minecraft.world.entity.player.PlayerModelType.SLIM ? "slim" : "classic";
                }
            } else if ("auto".equals(modelType)) {
                // No custom skin active, use vanilla player's model
                modelType = player.getSkin().model() == net.minecraft.world.entity.player.PlayerModelType.SLIM ? "slim" : "classic";
            }
        }

        // Fallback: Use default Steve skin
        if (skinLocation == null) {
            skinLocation = Identifier.fromNamespaceAndPath("minecraft", "textures/entity/player/wide/steve.png");
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
        // Only populate if user hasn't hidden built-in capes
        ClientConfig config = ClientConfig.getInstance();
        if (!config.hideBuiltInCapes) {
            for (KnownCapes knownCape : KnownCapes.values()) {
                if (!knownCape.isNoCape()) {
                    this.knownCapes.add(CapeEntry.fromKnown(knownCape));
                }
            }
        }

        // Animations are registered lazily when capes become visible in the grid
    }

    /**
     * Ensure an animated cape's animation is registered (lazy loading).
     * Uses async registration for local capes to avoid freezing the render thread.
     * The static first-frame texture is shown until the animation loads.
     */
    private void ensureAnimationRegistered(CapeEntry cape) {
        if (!cape.isAnimated()) return;

        String capeId = cape.getCapeId();
        String animationId = getAnimationIdForCape(capeId);
        if (animationId == null) return;

        com.quickskin.mod.client.services.AnimatedTextureManager animManager =
            com.quickskin.mod.client.services.AnimatedTextureManager.getInstance();

        if (animManager.isAnimated(animationId)) return;

        Identifier texLoc = cape.getTextureLocation();
        if (texLoc == null) return;

        if (capeId.startsWith("local_cape:")) {
            // Async: disk I/O + pixel conversion on background thread
            String hash = capeId.substring("local_cape:".length());
            animManager.registerAnimationAsync(animationId, capeId, texLoc, hash);
        } else if (capeId.startsWith("known:")) {
            // Known capes are in resources (fast), use sync path
            com.quickskin.mod.client.services.CapeService.getInstance().getCapeLocation(null, capeId);
        }
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
        }

        // Second priority: Check PlayerAppearanceService (in-game only)
        if (activeCapeId == null && minecraft != null && minecraft.player != null) {
            java.util.UUID playerId = minecraft.player.getUUID();
            com.quickskin.mod.common.data.PlayerAppearance appearance =
                    PlayerAppearanceService.getInstance().getAppearance(playerId);

            if (appearance != null && appearance.getCapeId() != null && !appearance.getCapeId().isEmpty()) {
                activeCapeId = appearance.getCapeId();
            }
        }

        // No active cape found
        if (activeCapeId == null || activeCapeId.isEmpty()) {
            this.selectedCape = null;
            return;
        }

        // Find the matching cape in both lists and update preview
        for (CapeEntry cape : this.localCapes) {
            if (cape.getCapeId().equals(activeCapeId)) {
                this.selectedCape = cape;
                // Update the preview widget with the saved cape
                Identifier capeLocation = cape.getTextureLocation();
                if (capeLocation != null && playerWidget != null) {
                    playerWidget.setCape(capeLocation, cape.getCapeId());
                }
                return;
            }
        }

        for (CapeEntry cape : this.knownCapes) {
            if (cape.getCapeId().equals(activeCapeId)) {
                this.selectedCape = cape;
                // Update the preview widget with the saved cape
                Identifier capeLocation = cape.getTextureLocation();
                if (capeLocation != null && playerWidget != null) {
                    playerWidget.setCape(capeLocation, cape.getCapeId());
                }
                return;
            }
        }

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

        // Show processing message
        showImportMessage(Component.translatable("quickskin.cape.processing").getString(), 0xFF55AAFF, 60);

        // F5: import on background thread, marshal UI back to render thread.
        if (this.minecraft != null) {
            CompletableFuture.runAsync(() -> {
                Path capesDir = LocalAssetManager.getInstance().getCapesDirectory();
                try {
                    Files.createDirectories(capesDir);
                    boolean ok;
                    try {
                        ok = processDroppedFile(filePath, capesDir);
                    } catch (Exception procEx) {
                        final String msg = procEx.getMessage() != null ? procEx.getMessage() : procEx.getClass().getSimpleName();
                        Minecraft.getInstance().execute(() ->
                            showImportMessage(Component.translatable("quickskin.cape.error", msg).getString(), 0xFFFF5555, 150));
                        return;
                    }
                    final boolean imported = ok;
                    Minecraft.getInstance().execute(() -> {
                        if (imported) {
                            LocalAssetManager.getInstance().reload();
                            refreshCapeList();
                            updateGridDimensions();
                            showImportMessage(Component.translatable("quickskin.cape.imported").getString(), 0xFF55FF55, 100);
                        } else {
                            showImportMessage(Component.translatable("quickskin.cape.invalid_ratio").getString(), 0xFFFF5555, 150);
                        }
                    });
                } catch (IOException e) {
                    Minecraft.getInstance().execute(() ->
                        showImportMessage(Component.translatable("quickskin.cape.error", e.getMessage()).getString(), 0xFFFF5555, 150));
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

        // Remove from PlayerAppearanceService
        // Note: We use applyCape with empty string instead of removeCape
        // to avoid unregistering animations while the menu is open
        if (minecraft != null && minecraft.player != null) {
            // In-game: use the real player's UUID
            PlayerAppearanceService.getInstance()
                    .applyCape(minecraft.player.getUUID(), "");
        } else {
            // Title screen: use cached player UUID if available
            java.util.UUID dummyUUID = getDummyPlayerUUID();
            if (dummyUUID != null) {
                PlayerAppearanceService.getInstance()
                        .applyCape(dummyUUID, "");
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
                Component.translatable("quickskin.screen.delete_cape.title"),
                Component.translatable("quickskin.dialog.confirm_delete_cape", displayName),
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

            showImportMessage(Component.translatable("quickskin.cape.deleted").getString(), 0xFF55FF55, 100);
        } catch (Exception e) {
            showImportMessage(Component.translatable("quickskin.cape.error", e.getMessage()).getString(), 0xFFFF5555, 100);
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
    private void renderBackgroundEffects(GuiGraphicsExtractor graphics, float partialTick) {
        BackgroundRenderer.renderBackground(this, graphics, partialTick);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Render the animated star background
        this.renderBackgroundEffects(graphics, partialTick);

        // graphics.flush() removed in 1.21.6
        // RenderSystem.setShaderColor() removed in 1.21.6

        // Title
        graphics.centeredText(this.font, this.title, this.width / 2, scaleValue(15), 0xFFFFFFFF);

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        // Push pose (1.21.6: Matrix3x2fStack uses pushMatrix/popMatrix, no Z translate in 2D)
        graphics.pose().pushMatrix();

        // Enable scissor for grid content
        graphics.enableScissor(this.gridX, this.gridY,
                this.gridX + this.gridWidth, this.gridY + this.gridHeight);
        this.scrollOffset += (this.targetScrollOffset - this.scrollOffset) * 0.5;
        renderCapeGrid(graphics, mouseX, mouseY);
        graphics.disableScissor();

        this.renderScrollbar(graphics);

        // Pop pose
        graphics.pose().popMatrix();

        // Render import message
        if (importMessageTimer > 0 && !importMessage.isEmpty()) {
            int messageY = this.gridY + this.gridHeight + 10;
            graphics.centeredText(this.font, importMessage, this.width / 2, messageY, importMessageColor);
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
                        // 1.21.6: renderTooltip takes List<ClientTooltipComponent>
                        Component tooltipText = Component.translatable("quickskin.tooltip.delete_cape");
                        List<ClientTooltipComponent> tooltipComponents = List.of(
                            ClientTooltipComponent.create(tooltipText.getVisualOrderText())
                        );
                        graphics.tooltip(this.font, tooltipComponents, mouseX, mouseY, DefaultTooltipPositioner.INSTANCE, null);
                        deleteHovered = true;
                    }
                }
                if (!deleteHovered) {
                    // 1.21.6: renderTooltip takes List<ClientTooltipComponent>
                    List<Component> capeTooltip = getCapeTooltip(hoveredCape);
                    List<ClientTooltipComponent> tooltipComponents = capeTooltip.stream()
                        .map(c -> ClientTooltipComponent.create(c.getVisualOrderText()))
                        .collect(java.util.stream.Collectors.toList());
                    graphics.tooltip(this.font, tooltipComponents, mouseX, mouseY, DefaultTooltipPositioner.INSTANCE, null);
                }
            }
        }
    }

    private void renderCapeGrid(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int currentY = gridY - (int) scrollOffset;

        // --- SECTION 1: MY CAPES ---
        if (!localCapes.isEmpty()) {
            currentY = renderSection(graphics, "quickskin.cape.section.my_capes", localCapes, currentY, mouseX, mouseY, true);
        }

        // --- SECTION 2: DEFAULT CAPES ---
        if (!knownCapes.isEmpty()) {
            renderSection(graphics, "quickskin.cape.section.default_capes", knownCapes, currentY + 20, mouseX, mouseY, false);
        }
    }

    private int renderSection(GuiGraphicsExtractor graphics, String titleKey, List<CapeEntry> capes, int startY, int mouseX, int mouseY, boolean isLocalSection) {
        // Render Header (centered within the grid)
        int headerY = startY + HEADER_HEIGHT / 2 - 4;
        if (headerY > gridY - 8 && headerY < gridY + gridHeight + 8) {
            int gridCenterX = this.gridX + (this.gridWidth / 2);
            graphics.centeredText(this.font, Component.translatable(titleKey), gridCenterX, headerY, 0xFFFFFFFF);
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

    private void renderDropZone(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int mouseX, int mouseY) {
        boolean isHovering = isMouseOver(mouseX, mouseY, x, y, width, height) &&
                mouseY >= gridY && mouseY < gridY + gridHeight;

        int bgColor = isHovering ? 0x2AFFFFFF : 0x1AFFFFFF;
        graphics.fill(x, y, x + width, y + height, bgColor);

        // Draw dashed border
        drawDashedBorder(graphics, x, y, width, height, isHovering);

        // Draw text
        int centerX = x + width / 2;
        int centerY = y + height / 2;
        Component mainMessage = Component.translatable("quickskin.dropzone.capes.main");
        Component subMessage = Component.translatable("quickskin.dropzone.capes.sub");

        int mainColor = isHovering ? 0xFFFFFFFF : 0xFFE0E0E0;
        int subColor = isHovering ? 0xFFB0B0B0 : 0xFF909090;

        if (height > font.lineHeight * 2.5 && width > font.width(subMessage)) {
            graphics.centeredText(this.font, mainMessage, centerX, centerY - font.lineHeight / 2 - 1, mainColor);
            graphics.centeredText(this.font, subMessage, centerX, centerY + font.lineHeight / 2 + 1, subColor);
        } else if (width > font.width(mainMessage)) {
            graphics.centeredText(this.font, mainMessage, centerX, centerY - font.lineHeight / 2, mainColor);
        }
    }

    private void drawDashedBorder(GuiGraphicsExtractor graphics, int x, int y, int width, int height, boolean highlight) {
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

    private void renderCapeEntry(GuiGraphicsExtractor graphics, CapeEntry cape, int x, int y, int mouseX, int mouseY) {
        boolean hovered = isMouseOver(mouseX, mouseY, x, y, capeDisplaySize, capeDisplaySize);

        // Special handling for "None" option
        if (cape.isKnown() && cape.getKnownCape() != null && cape.getKnownCape().isNoCape()) {
            // Render black background
            graphics.fill(x, y, x + capeDisplaySize, y + capeDisplaySize, 0x90000000);

            // Render "None" text centered
            graphics.centeredText(this.font, Component.translatable("quickskin.cape.option.none"), x + capeDisplaySize / 2,
                    y + capeDisplaySize / 2 - 4, 0xFFFFFFFF);

            // Highlight if selected or hovered
            if (isSelected(cape)) {
                drawOutline(graphics,x - 2, y - 2, capeDisplaySize + 4, capeDisplaySize + 4, 0xFFFFFF00);
            } else if (hovered) {
                graphics.fill(x, y, x + capeDisplaySize, y + capeDisplaySize, 0x33FFFFFF);
            }
            return;
        }

        // Regular cape rendering
        Identifier texture = cape.getTextureLocation();

        // If animated, ensure registration (lazy) and get the current frame texture
        if (texture != null && cape.isAnimated()) {
            ensureAnimationRegistered(cape);
            texture = com.quickskin.mod.client.services.AnimatedTextureManager.getInstance()
                .getAnimationFrame(texture)
                .orElse(texture);
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
            drawOutline(graphics,x - 2, y - 2, capeDisplaySize + 4, capeDisplaySize + 4, 0xFFFFFF00);
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
            graphics.text(this.font, "x", deleteButtonX + 3, deleteButtonY + 1, 0xFFFFFFFF);
        }
    }

    private void renderCapeTexture(GuiGraphicsExtractor graphics, Identifier texture, CapeEntry cape, int x, int y) {
        // RenderSystem.setShaderColor() removed in 1.21.6

        int textureWidth = 64;
        int textureHeight = 32;

        // Cape coordinates (show back of cape)
        int u = 1;
        int v = 1;
        int uWidth = 10;
        int vHeight = 16;

        // Check if it's a high resolution cape - scale texture dimensions and UV coordinates
        if (cape.isLocal() && cape.getLocalCape() != null && cape.getLocalCape().resolution() != null && cape.getLocalCape().resolution().isHD()) {
            int scale = cape.getLocalCape().resolution().getScale();
            textureWidth *= scale;
            textureHeight *= scale;
            u *= scale;
            v *= scale;
            uWidth *= scale;
            vHeight *= scale;
        }

        float scaleFactor = capeDisplaySize / 56f;

        // 1.21.6: Matrix3x2fStack uses pushMatrix/popMatrix and 2D translate/scale
        graphics.pose().pushMatrix();
        graphics.pose().translate(x + capeDisplaySize / 2f, y + capeDisplaySize / 2f);
        graphics.pose().scale(scaleFactor * 3.5f, scaleFactor * 3.5f);
        graphics.pose().translate(-5, -8);

        PlatformHelper.blit(graphics, texture, 0, 0, 10, 16, u, v, uWidth, vHeight, textureWidth, textureHeight);

        graphics.pose().popMatrix();
    }

    private void renderLoadingTexture(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.fill(x, y, x + capeDisplaySize, y + capeDisplaySize, 0xFF222222);
        graphics.centeredText(this.font, Component.translatable("quickskin.cape.loading"),
                x + capeDisplaySize / 2, y + capeDisplaySize / 2 - 4, 0xFF888888);
    }

    private void renderCustomIndicator(GuiGraphicsExtractor graphics, int x, int y) {
        int indicatorSize = Math.max(4, capeDisplaySize / 16);
        int rarityColor = 0xFF5555FF; // Purple for custom
        graphics.fill(x + capeDisplaySize - indicatorSize * 2,
                y + capeDisplaySize - indicatorSize * 2,
                x + capeDisplaySize - indicatorSize / 2,
                y + capeDisplaySize - indicatorSize / 2,
                rarityColor);
    }

    private void renderAnimatedIndicator(GuiGraphicsExtractor graphics, int x, int y) {
        String badgeText = Component.translatable("quickskin.cape.animated_badge").getString();
        int textWidth = this.font.width(badgeText);
        int badgeWidth = textWidth + 4;
        int badgeHeight = this.font.lineHeight + 2;
        int margin = 2;

        int badgeX = x + margin;
        int badgeY = y + margin;

        int bgColor = 0xD000CCFF;
        graphics.fill(badgeX, badgeY, badgeX + badgeWidth, badgeY + badgeHeight, bgColor);

        int borderColor = 0xFF00AADD;
        drawOutline(graphics,badgeX, badgeY, badgeWidth, badgeHeight, borderColor);

        graphics.text(this.font, badgeText, badgeX + 2, badgeY + 1, 0xFFFFFFFF);
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
            tooltip.add(Component.translatable("quickskin.tooltip.animated_cape").withStyle(s -> s.withColor(0xFFAA00)));
        } else {
            tooltip.add(Component.translatable("quickskin.tooltip.static_cape").withStyle(s -> s.withColor(0xAAAAAA)));
        }

        if (cape.isLocal() && cape.getLocalCape() != null && cape.getLocalCape().resolution() != null) {
            String resolutionText = cape.getLocalCape().resolution().name();
            tooltip.add(Component.translatable("quickskin.tooltip.resolution", resolutionText).withStyle(s -> s.withColor(0x55FFFF)));
        }

        tooltip.add(Component.empty());
        tooltip.add(Component.translatable("quickskin.tooltip.click_preview").withStyle(s -> s.withColor(0x808080).withItalic(true)));

        return tooltip;
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean focused) {
        if (super.mouseClicked(event, focused)) return true;
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.buttonInfo().button();

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
        Identifier capeLocation = com.quickskin.mod.client.services.CapeService.getInstance()
                .getCapeLocation(null, capeId);

        // Fallback to direct texture if service returns null
        if (capeLocation == null) {
            capeLocation = cape.getTextureLocation();
        }

        // Always update preview widget (works both in-game and on title screen)
        playerWidget.setCape(capeLocation, capeId);

        // Save to config for persistence
        config.activeCapeHash = capeId;
        config.save();

        // Apply to PlayerAppearanceService
        if (minecraft != null && minecraft.player != null) {
            // In-game: use the real player's UUID
            PlayerAppearanceService.getInstance()
                    .applyCape(minecraft.player.getUUID(), capeId);
        } else {
            // Title screen: use a dummy UUID that matches the cached player if it exists
            // This allows entity rendering to work on title screen with cached player
            java.util.UUID dummyUUID = getDummyPlayerUUID();
            if (dummyUUID != null) {
                PlayerAppearanceService.getInstance()
                        .applyCape(dummyUUID, capeId);
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
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dragX, double dragY) {
        double mouseY = event.y();
        if (this.isDraggingScrollbar) {
            updateScrollFromMouse(mouseY);
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        int button = event.buttonInfo().button();
        if (button == 0) {
            this.isDraggingScrollbar = false;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (isMouseOverGrid((int) mouseX, (int) mouseY)) {
            this.targetScrollOffset = Mth.clamp(this.targetScrollOffset - deltaY * scrollSpeed, 0.0D, this.maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    private void renderScrollbar(GuiGraphicsExtractor graphics) {
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
                    String name = p.toString().toLowerCase(Locale.ROOT);
                    return name.endsWith(".png") || name.endsWith(".gif");
                })
                .toList();

        if (validFiles.isEmpty()) {
            showImportMessage(Component.translatable("quickskin.cape.no_files").getString(), 0xFFFFAA00, 100);
            return;
        }

        showImportMessage(Component.translatable("quickskin.cape.processing_count", validFiles.size()).getString(), 0xFF55AAFF, 60);

        CompletableFuture.runAsync(() -> {
            int successCount = 0;
            int invalidCount = 0;

            Path capesDir = LocalAssetManager.getInstance().getCapesDirectory();
            try {
                Files.createDirectories(capesDir);
            } catch (IOException e) {
                Minecraft.getInstance().execute(() ->
                        showImportMessage(Component.translatable("quickskin.cape.error_directory").getString(), 0xFFFF5555, 100));
                return;
            }

            String[] firstError = new String[1];
            for (Path file : validFiles) {
                try {
                    if (processDroppedFile(file, capesDir)) {
                        successCount++;
                    } else {
                        invalidCount++;
                    }
                } catch (IOException e) {
                    invalidCount++;
                    if (firstError[0] == null) firstError[0] = e.getMessage();
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
                    showImportMessage(message, finalSuccessCount > finalInvalidCount ? 0xFF55FF55 : 0xFFFFAA00, 200);
                } else {
                    String msg = firstError[0] != null
                            ? Component.translatable("quickskin.cape.error", firstError[0]).getString()
                            : Component.translatable("quickskin.cape.no_valid").getString();
                    showImportMessage(msg, 0xFFFF5555, 200);
                }
            });
        }).exceptionally(throwable -> {
            Minecraft.getInstance().execute(() ->
                    showImportMessage(Component.translatable("quickskin.cape.error_processing", throwable.getMessage()).getString(), 0xFFFF5555, 200));
            return null;
        });
    }

    private boolean processDroppedFile(Path sourceFile, Path targetDir) throws IOException {
        try {
            String lowerCaseName = sourceFile.toString().toLowerCase(Locale.ROOT);
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
                            int[] argbRow = new int[width];
                            for (int y = 0; y < height; y++) {
                                for (int x = 0; x < width; x++) {
                                    int abgr = PlatformHelper.getPixel(frame, x, y);
                                    int a = (abgr >> 24) & 0xFF;
                                    int b = (abgr >> 16) & 0xFF;
                                    int g = (abgr >> 8) & 0xFF;
                                    int r = abgr & 0xFF;
                                    argbRow[x] = (a << 24) | (r << 16) | (g << 8) | b;
                                }
                                sourceAtlas.setRGB(0, i * height + y, width, 1, argbRow, 0, width);
                            }
                        }

                        animationMetadata = gifResult.metadata();
                        // Same rule as PNG: only the vanilla 64x32 size saves directly.
                        // Any other frame size (including valid HD cape resolutions) goes
                        // through CapeAdjustScreen so the user can preview before saving.
                        isStandardFormat = (width == 64 && height == 32);
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

                // For PNG imports, only save directly when it's the exact vanilla legacy
                // cape size (64x32). Any other dimensions — including valid HD cape sizes
                // like 128x64, 256x128, 512x256, etc. — go through CapeAdjustScreen so the
                // user can preview the result (including elytra compositing) before saving.
                isStandardFormat = (w == 64 && h == 32);
                if (isStandardFormat) {
                    frameCount = 1;
                }
            }

            java.awt.image.BufferedImage finalAtlas;

            // Step 2: Process the source atlas based on its format
            if (isGif && isStandardFormat) {
                // Copy GIF directly to preserve compression — processGifAsset handles caching
                String fileName = sourceFile.getFileName().toString();
                String nameWithoutExt = fileName.substring(0, fileName.lastIndexOf('.'));
                Path targetPath = targetDir.resolve(fileName);
                int counter = 1;
                while (Files.exists(targetPath)) {
                    targetPath = targetDir.resolve(nameWithoutExt + "_" + counter + ".gif");
                    counter++;
                }
                Files.copy(sourceFile, targetPath);
                return true;
            } else if (isStandardFormat) {

                // Keep original HD resolution - no downscaling
                java.awt.image.BufferedImage normalizedAtlas = sourceAtlas;
                int capeWidth = normalizedAtlas.getWidth();
                int capeFrameHeight = capeWidth / 2;

                // Check if the elytra area is transparent
                if (isElytraAreaTransparent(normalizedAtlas)) {
                    java.awt.image.BufferedImage vanillaElytraBase = getVanillaElytraImage();
                    if (vanillaElytraBase == null) { // Fallback if vanilla elytra fails to load
                        finalAtlas = normalizedAtlas;
                    } else {
                        java.awt.image.BufferedImage compositeAtlas = new java.awt.image.BufferedImage(normalizedAtlas.getWidth(), normalizedAtlas.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
                        java.awt.Graphics2D g = compositeAtlas.createGraphics();
                        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

                        g.setComposite(java.awt.AlphaComposite.Clear);
                        g.fillRect(0, 0, normalizedAtlas.getWidth(), normalizedAtlas.getHeight());
                        g.setComposite(java.awt.AlphaComposite.SrcOver);

                        for(int i = 0; i < frameCount; i++) {
                            int yOffset = i * capeFrameHeight;
                            // Scale vanilla elytra to match HD cape dimensions
                            g.drawImage(vanillaElytraBase, 0, yOffset, capeWidth, yOffset + capeFrameHeight,
                                    0, 0, vanillaElytraBase.getWidth(), vanillaElytraBase.getHeight(), null);
                            g.drawImage(normalizedAtlas.getSubimage(0, yOffset, capeWidth, capeFrameHeight), 0, yOffset, null);
                        }
                        g.dispose();
                        finalAtlas = compositeAtlas;
                    }
                } else {
                    finalAtlas = normalizedAtlas;
                }
            } else {
                // Non-standard image: open the cape adjustment screen
                final java.awt.image.BufferedImage srcImage = sourceAtlas;
                final Path srcFile = sourceFile;
                final Path tgtDir = targetDir;
                final int fc = frameCount;
                final com.quickskin.mod.common.data.AnimationMetadata animMeta = animationMetadata;

                if (this.minecraft != null) {
                    this.minecraft.execute(() -> {
                        this.minecraft.setScreen(new CapeAdjustScreen(this, srcImage, fc, composedCape -> {
                            try {
                                int composedW = composedCape.getWidth();
                                int composedFrameH = composedW / 2;
                                int composedFrameCount = (composedFrameH > 0) ? composedCape.getHeight() / composedFrameH : 1;

                                java.awt.image.BufferedImage finalCape = composedCape;
                                if (isElytraAreaTransparent(composedCape)) {
                                    java.awt.image.BufferedImage elytra = getVanillaElytraImage();
                                    if (elytra != null) {
                                        java.awt.image.BufferedImage composite = new java.awt.image.BufferedImage(
                                                composedW, composedCape.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
                                        java.awt.Graphics2D g2 = composite.createGraphics();
                                        g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                                                java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                                        for (int fi = 0; fi < composedFrameCount; fi++) {
                                            int yOff = fi * composedFrameH;
                                            g2.drawImage(elytra, 0, yOff, composedW, yOff + composedFrameH,
                                                    0, 0, elytra.getWidth(), elytra.getHeight(), null);
                                            g2.drawImage(composedCape.getSubimage(0, yOff, composedW, composedFrameH),
                                                    0, yOff, null);
                                        }
                                        g2.dispose();
                                        finalCape = composite;
                                    }
                                }

                                Path savePath = resolveTargetPath(srcFile, tgtDir);
                                saveImageWithAlpha(finalCape, savePath);

                                // Save animation metadata for multi-frame strips
                                if (animMeta != null && composedFrameCount > 1) {
                                    String hash = HashUtil.computeFileHash(savePath);
                                    if (hash != null) {
                                        Path metadataPath = LocalAssetManager.getInstance().getCacheDirectory()
                                                .resolve(hash + ".json");
                                        Files.writeString(metadataPath, animMeta.toJson());
                                    }
                                }

                                LocalAssetManager.getInstance().reload();
                                refreshCapeList();
                                updateGridDimensions();
                                showImportMessage(Component.translatable("quickskin.cape.imported").getString(), 0xFF55FF55, 100);
                            } catch (IOException e) {
                                showImportMessage(Component.translatable("quickskin.cape.error", e.getMessage()).getString(), 0xFFFF5555, 150);
                            }
                        }));
                    });
                }
                return true;
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
                }
            }

            Path targetPath = resolveTargetPath(sourceFile, targetDir);
            saveImageWithAlpha(finalAtlas, targetPath);
            return true;

        } catch (IOException e) {
            throw e;
        }
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
            Identifier VANILLA_ELYTRA_TEXTURE = Identifier.fromNamespaceAndPath("minecraft", "textures/entity/elytra.png");
            var resourceOptional = Minecraft.getInstance().getResourceManager().getResource(VANILLA_ELYTRA_TEXTURE);
            if (resourceOptional.isEmpty()) {
                return null;
            }
            java.io.InputStream stream = resourceOptional.get().open();
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(stream);
            stream.close();
            return image;
        } catch (IOException e) {
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
    protected void extractBlurredBackground(net.minecraft.client.gui.GuiGraphicsExtractor guiGraphics) {
        // Disable the default blur effect - we have our own custom background
    }

    @Override
    public void extractBackground(net.minecraft.client.gui.GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Disable the default dark background overlay - we render our own custom background
    }

    @Override
    public void onClose() {
        BackgroundRenderer.cleanup();

        // Animations are kept alive -- each uses only one small GPU texture (~512KB).
        // They are cleaned up on disconnect (clearAnimations) or resource reload.
        // This avoids a micro-freeze from freeing large atlas NativeImages synchronously.

        if (minecraft != null) {
            minecraft.setScreen(parent);
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

            this.setTooltip(Tooltip.create(Component.translatable("quickskin.cape.animation_speed_tooltip")));
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
            setMessage(Component.translatable("quickskin.cape.animation_speed", percentage));
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
        public void onRelease(net.minecraft.client.input.MouseButtonEvent event) {
            super.onRelease(event);
            // Save config when slider is released
            ClientConfig.getInstance().save();

            if (selectedCape != null) {
                float speed = ClientConfig.getInstance().getCapeAnimationSpeed(selectedCape.getCapeId());
            }
        }

    }

    /**
     * Get the animation ID for a given cape ID.
     * Shared by SpeedSlider, lazy registration, and render logic.
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

    /**
     * Draws an outline immediately using fill calls instead of submitOutline,
     * which defers rendering and can cause z-order issues with modals.
     */
    private static void drawOutline(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y + 1, x + 1, y + height - 1, color);
        graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
    }
}