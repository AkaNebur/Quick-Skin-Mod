package com.quickskin.mod.client.gui.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.gui.util.GuiScalingUtils;
import com.quickskin.mod.client.gui.widget.ConfirmationDialog;
import com.quickskin.mod.client.gui.widget.PlayerWidget;
import com.quickskin.mod.client.rendering.PlayerModelRenderer;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.KnownCapes;
import com.quickskin.mod.common.data.TextureQuality;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Cape selection menu for QuickSkin with grid-based layout
 */
@Environment(EnvType.CLIENT)
public class PlayerCapeMenuScreen extends Screen {

    // Background textures
    private static final ResourceLocation STAR_PATTERN_TEXTURE = new ResourceLocation(QuickSkin.MOD_ID, "textures/gui/background/star_pattern.png");
    private static final ResourceLocation VIGNETTE_LOCATION = new ResourceLocation("textures/misc/vignette.png");

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
    private Button importButton;
    private Button removeButton;
    private Button closeButton;

    // Model position offsets from grid edge
    private static final int MODEL_OFFSET_X = 80;
    private static final int MODEL_OFFSET_Y = 85;

    private double scrollOffset = 0;
    private double targetScrollOffset = 0;
    private int maxScroll = 0;
    private boolean isDraggingScrollbar = false;
    private double scrollbarClickOffset = 0.0;
    private int totalContentHeight = 0;
    private int gridX, gridY, gridWidth, gridHeight;

    // Player widget positioning
    private int playerWidgetX, playerWidgetY;
    private int playerWidgetWidth, playerWidgetHeight;

    @Nullable
    private ConfirmationDialog confirmationDialog;

    @Nullable
    private CapeEntry selectedCape;

    // Cape list (both known and local capes)
    private final List<CapeEntry> capes = new ArrayList<>();

    // Import feedback
    private String importMessage = "";
    private int importMessageTimer = 0;
    private int importMessageColor = 0xFFFFFF;

    public PlayerCapeMenuScreen(@Nullable Screen parent) {
        super(Component.literal("Cape Selection"));
        this.parent = parent;
    }

    @Override
    protected void init() {
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

        // Initialize selected cape based on currently equipped cape
        initializeSelectedCape();

        // Create buttons
        int bottomY = this.height - scaleValue(60);

        this.importButton = this.addRenderableWidget(Button.builder(
                Component.literal("Import Cape"),
                button -> importCape()
        ).bounds(buttonStartX, bottomY, buttonWidth, scaleValue(20)).build());

        this.removeButton = this.addRenderableWidget(Button.builder(
                Component.literal("Remove Cape"),
                button -> removeCape()
        ).bounds(buttonStartX + buttonWidth + buttonSpacing, bottomY, buttonWidth, scaleValue(20)).build());

        this.closeButton = this.addRenderableWidget(Button.builder(
                Component.literal("Close"),
                button -> this.onClose()
        ).bounds(buttonStartX + (buttonWidth + buttonSpacing) * 2, bottomY, buttonWidth, scaleValue(20)).build());

        // Create player preview widget
        int availableWidthForWidget = this.width - (this.gridX + this.gridWidth) - scaleValue(40);
        int availableHeightForWidget = this.closeButton.getY() - this.gridY - scaleValue(20);

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
        ResourceLocation skinLocation = player != null ? player.getSkinTextureLocation()
            : new ResourceLocation("minecraft", "textures/entity/player/wide/steve.png");
        String modelType = player != null ? player.getModelName() : "default";
        if ("default".equals(modelType)) {
            modelType = "classic";
        }

        this.playerWidget = addRenderableWidget(new PlayerWidget(
                this.playerWidgetX, this.playerWidgetY,
                this.playerWidgetWidth, this.playerWidgetHeight,
                skinLocation, null, modelType));

        // Set custom reference point to right side center of capes grid with fixed offset
        int referenceX = this.gridX + this.gridWidth + MODEL_OFFSET_X;
        int referenceY = this.gridY + (this.gridHeight / 2) + MODEL_OFFSET_Y;
        this.playerWidget.setCustomReferencePoint(referenceX, referenceY);

        // Trigger initial rotation animation on menu open
        this.playerWidget.toggleRotation();
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

    private void refreshCapeList() {
        this.capes.clear();

        // Add known capes first (exclude NONE)
        for (KnownCapes knownCape : KnownCapes.values()) {
            if (!knownCape.isNoCape()) {
                this.capes.add(CapeEntry.fromKnown(knownCape));
            }
        }

        // Then add local capes
        List<AssetMetadata> localCapes = LocalAssetManager.getInstance()
            .getAssetsByType("cape");
        for (AssetMetadata localCape : localCapes) {
            this.capes.add(CapeEntry.fromLocal(localCape));
        }

        QuickSkin.LOGGER.debug("Loaded {} total capes ({} known + {} local)",
            capes.size(), KnownCapes.values().length - 1, localCapes.size());
    }

    /**
     * Initialize the selected cape based on the player's currently equipped cape
     */
    private void initializeSelectedCape() {
        if (minecraft == null || minecraft.player == null) {
            QuickSkin.LOGGER.debug("No player available, cannot initialize selected cape");
            return;
        }

        // Get the player's current appearance
        java.util.UUID playerId = minecraft.player.getUUID();
        com.quickskin.mod.common.data.PlayerAppearance appearance =
            PlayerAppearanceService.getInstance().getAppearance(playerId);

        if (appearance == null || appearance.getCapeId() == null || appearance.getCapeId().isEmpty()) {
            QuickSkin.LOGGER.debug("No active cape for player");
            this.selectedCape = null;
            return;
        }

        // Find the matching cape in the list
        String activeCapeId = appearance.getCapeId();
        for (CapeEntry cape : this.capes) {
            if (cape.getCapeId().equals(activeCapeId)) {
                this.selectedCape = cape;
                QuickSkin.LOGGER.info("Initialized selected cape: {}", cape.getFriendlyName());
                return;
            }
        }

        QuickSkin.LOGGER.warn("Could not find cape with ID '{}' in capes list", activeCapeId);
        this.selectedCape = null;
    }

    private void updateGridDimensions() {
        int totalHeight = 0;

        if (!this.capes.isEmpty()) {
            totalHeight += HEADER_HEIGHT;
            // Add 1 for "None" option
            int totalItems = this.capes.size() + 1;
            int rows = (int) Math.ceil((double) totalItems / capesPerRow);
            totalHeight += rows * (capeDisplaySize + capePadding);
        }

        this.totalContentHeight = totalHeight + capePadding;
        this.maxScroll = Math.max(0, this.totalContentHeight - this.gridHeight);
    }

    private void importCape() {
        // TODO: Implement file picker for cape import
        QuickSkin.LOGGER.info("Import cape clicked (file picker not implemented yet)");
    }

    private void removeCape() {
        // Always update preview widget (works both in-game and on title screen)
        playerWidget.setCape(null);
        this.selectedCape = null;

        // Remove from PlayerAppearanceService
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

        confirmationDialog = new ConfirmationDialog(
            Component.literal("Delete Cape?"),
            Component.literal("Are you sure you want to delete '" + capeEntry.getFriendlyName() + "'?"),
            () -> deleteCape(capeEntry),
            () -> confirmationDialog = null
        );
    }

    private void deleteCape(CapeEntry capeEntry) {
        if (!capeEntry.isLocal() || capeEntry.getLocalCape() == null) {
            return;
        }

        try {
            Files.deleteIfExists(capeEntry.getPath());
            LocalAssetManager.getInstance().discoverLocalAssets();
            refreshCapeList();
            updateGridDimensions();
            confirmationDialog = null;
            selectedCape = null;
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
        // Render the animated star background
        this.renderBackgroundEffects(graphics, partialTick);

        // Title
        graphics.drawCenteredString(this.font, this.title, this.width / 2, scaleValue(15), 0xFFFFFF);

        // Grid background (darker semi-transparent for better visibility)
        graphics.fill(this.gridX - 5, this.gridY - 5,
                this.gridX + this.gridWidth + 5, this.gridY + this.gridHeight + 5,
                0xB0000000);

        // Enable scissor for grid content
        graphics.enableScissor(this.gridX, this.gridY,
                this.gridX + this.gridWidth, this.gridY + this.gridHeight);
        this.scrollOffset += (this.targetScrollOffset - this.scrollOffset) * 0.5;
        renderCapeGrid(graphics, mouseX, mouseY);
        graphics.disableScissor();

        this.renderScrollbar(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        // Render import message
        if (importMessageTimer > 0 && !importMessage.isEmpty()) {
            int messageY = this.gridY + this.gridHeight + 10;
            graphics.drawCenteredString(this.font, importMessage, this.width / 2, messageY, importMessageColor);
        }

        // Render confirmation dialog if present
        if (confirmationDialog != null) {
            confirmationDialog.render(graphics, mouseX, mouseY, 0);
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
            } else {
                // Check if hovering over "None" option
                if (isHoveringNoneOption(mouseX, mouseY)) {
                    List<Component> tooltip = new ArrayList<>();
                    tooltip.add(Component.literal("No Cape").withStyle(s -> s.withBold(true).withColor(0xFFFFFF)));
                    tooltip.add(Component.literal("Remove your cape").withStyle(s -> s.withColor(0xAAAAAA)));
                    graphics.renderTooltip(this.font, tooltip, Optional.empty(), mouseX, mouseY);
                }
            }
        }
    }

    private void renderCapeGrid(GuiGraphics graphics, int mouseX, int mouseY) {
        int currentY = gridY - (int) scrollOffset;

        // Render "My Capes" header
        if (!capes.isEmpty() || true) { // Always show header
            int headerY = currentY + HEADER_HEIGHT / 2 - 4;
            if (headerY > gridY - 8 && headerY < gridY + gridHeight + 8) {
                int gridCenterX = this.gridX + (this.gridWidth / 2);
                graphics.drawCenteredString(this.font, "My Capes", gridCenterX, headerY, 0xFFFFFF);
            }
            currentY += HEADER_HEIGHT;

            // Render "None" option first
            renderNoneOption(graphics, currentY, mouseX, mouseY);

            // Render cape grid items
            int totalItems = capes.size() + 1; // +1 for "None"
            for (int i = 0; i < capes.size(); i++) {
                CapeEntry cape = capes.get(i);
                int itemIndex = i + 1; // +1 because "None" is at index 0
                int row = itemIndex / capesPerRow;
                int col = itemIndex % capesPerRow;

                int x = gridX + capePadding + col * (capeDisplaySize + capePadding);
                int y = currentY + capePadding + row * (capeDisplaySize + capePadding);

                if (y + capeDisplaySize < gridY || y > gridY + gridHeight) {
                    continue; // Cull capes outside visible area
                }

                renderCapeEntry(graphics, cape, x, y, mouseX, mouseY);
            }

            // Render drop zone if first row isn't full
            if (totalItems < capesPerRow) {
                int col = totalItems % capesPerRow;
                int dropZoneX = gridX + capePadding + col * (capeDisplaySize + capePadding);
                int dropZoneY = currentY + capePadding;
                int dropZoneWidth = (gridX + gridWidth) - dropZoneX - capePadding;
                int dropZoneHeight = capeDisplaySize;

                if (dropZoneWidth > capePadding) {
                    renderDropZone(graphics, dropZoneX, dropZoneY, dropZoneWidth, dropZoneHeight, mouseX, mouseY);
                }
            }
        }
    }

    private void renderNoneOption(GuiGraphics graphics, int baseY, int mouseX, int mouseY) {
        int x = gridX + capePadding;
        int y = baseY + capePadding;

        if (y + capeDisplaySize < gridY || y > gridY + gridHeight) {
            return; // Cull if outside visible area
        }

        boolean hovered = isMouseOver(mouseX, mouseY, x, y, capeDisplaySize, capeDisplaySize);

        // Draw background
        graphics.fill(x, y, x + capeDisplaySize, y + capeDisplaySize, 0x90000000);

        // Draw "None" text
        graphics.drawCenteredString(this.font, "None", x + capeDisplaySize / 2,
                y + capeDisplaySize / 2 - 4, 0xFFFFFF);

        // Highlight if selected or hovered
        if (selectedCape == null) {
            graphics.renderOutline(x - 2, y - 2, capeDisplaySize + 4, capeDisplaySize + 4, 0xFFFFFF00);
        } else if (hovered) {
            graphics.fill(x, y, x + capeDisplaySize, y + capeDisplaySize, 0x33FFFFFF);
        }
    }

    private boolean isHoveringNoneOption(int mouseX, int mouseY) {
        int currentY = gridY - (int) scrollOffset + HEADER_HEIGHT;
        int x = gridX + capePadding;
        int y = currentY + capePadding;
        return isMouseOver(mouseX, mouseY, x, y, capeDisplaySize, capeDisplaySize);
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
        ResourceLocation texture = cape.getTextureLocation();

        boolean hovered = isMouseOver(mouseX, mouseY, x, y, capeDisplaySize, capeDisplaySize);

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

        // Render delete button on hover (only for local capes)
        if (hovered && cape.isLocal()) {
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
        if (cape.isLocal() && cape.getLocalCape() != null && cape.getLocalCape().resolution().isHD()) {
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

        graphics.blit(texture, 0, 0, 10, 16, u, v, uWidth, vHeight, textureWidth, textureHeight);

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
        if (selectedCape == null || cape == null) return false;
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
        // Handle confirmation dialog clicks first
        if (confirmationDialog != null) {
            return confirmationDialog.mouseClicked(mouseX, mouseY, button);
        }

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
            // Check if clicking "None" option
            if (isHoveringNoneOption((int) mouseX, (int) mouseY)) {
                this.selectedCape = null;
                removeCape();
                return true;
            }

            CapeEntry clickedCape = getCapeAt((int) mouseX, (int) mouseY);
            if (clickedCape != null) {
                // Check for delete button click (only for local capes)
                if (clickedCape.isLocal()) {
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

                // Select cape
                this.selectedCape = clickedCape;
                applyCape(clickedCape);
                return true;
            }
        }

        return false;
    }

    private void applyCape(CapeEntry cape) {
        ResourceLocation capeLocation = cape.getTextureLocation();
        String capeId = cape.getCapeId();

        // Always update preview widget (works both in-game and on title screen)
        QuickSkin.LOGGER.info("[PlayerCapeMenuScreen] Setting cape in preview widget: {}", capeLocation);
        playerWidget.setCape(capeLocation);

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
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (isMouseOverGrid((int) mouseX, (int) mouseY)) {
            this.targetScrollOffset = Mth.clamp(this.targetScrollOffset - delta * scrollSpeed, 0.0D, this.maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
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
        int currentY = gridY + HEADER_HEIGHT;

        int relX = mouseX - this.gridX - capePadding;
        int relY = absoluteMouseY - currentY - capePadding;

        int col = relX / (capeDisplaySize + capePadding);
        int row = relY / (capeDisplaySize + capePadding);

        if (col < 0 || col >= capesPerRow) return null;

        int index = row * capesPerRow + col;
        // -1 because first slot is "None"
        index = index - 1;

        if (index >= 0 && index < capes.size()) {
            int capeX = this.gridX + capePadding + (col) * (capeDisplaySize + capePadding);
            int capeY = currentY + capePadding + row * (capeDisplaySize + capePadding) - (int) this.scrollOffset;
            if (isMouseOver(mouseX, mouseY, capeX, capeY, capeDisplaySize, capeDisplaySize)) {
                return capes.get(index);
            }
        }
        return null;
    }

    @Nullable
    private int[] getCapePosition(CapeEntry cape) {
        int currentY = gridY - (int) scrollOffset + HEADER_HEIGHT;
        int index = -1;

        // Find the index of this cape
        for (int i = 0; i < capes.size(); i++) {
            if (capes.get(i).getCapeId().equals(cape.getCapeId())) {
                index = i;
                break;
            }
        }

        if (index == -1) return null;

        // +1 because "None" is at index 0
        index = index + 1;

        int row = index / capesPerRow;
        int col = index % capesPerRow;
        int x = gridX + capePadding + col * (capeDisplaySize + capePadding);
        int y = currentY + capePadding + row * (capeDisplaySize + capePadding);
        return new int[]{x, y};
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
                .collect(Collectors.toList());

        if (validFiles.isEmpty()) {
            showImportMessage("⚠ No PNG or GIF files found", 0xFFAA00, 100);
            return;
        }

        showImportMessage("Processing " + validFiles.size() + " file(s)...", 0x55AAFF, 60);

        // TODO: Implement file processing
        QuickSkin.LOGGER.info("Received {} files for import", validFiles.size());
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }
}
