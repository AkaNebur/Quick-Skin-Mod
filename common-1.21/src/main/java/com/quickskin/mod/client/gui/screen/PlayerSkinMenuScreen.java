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
import com.quickskin.mod.client.gui.widget.ErrorToast;
import com.quickskin.mod.client.gui.widget.PlayerWidget;
import com.quickskin.mod.client.gui.widget.SkinEntry;
import com.quickskin.mod.client.services.CooldownService;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.MojangApiService;
import com.quickskin.mod.platform.PlatformHelper;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
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
import net.minecraft.Util;

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

    // Current model type (preserved across resizes)
    private String savedModelType = null;

    // Constants
    private static final int MIN_PANEL_WIDTH = 340;
    private static final int MAX_PANEL_WIDTH = 600;
    private static final int MIN_PANEL_HEIGHT = 280;

    // --- NEW ---: Constants for the background effect
    private static final ResourceLocation STAR_PATTERN_TEXTURE = ResourceLocation.fromNamespaceAndPath(QuickSkin.MOD_ID, "textures/gui/background/star_pattern.png");
    private static final ResourceLocation VIGNETTE_LOCATION = ResourceLocation.withDefaultNamespace("textures/misc/vignette.png");

    // Error toasts
    private final List<ErrorToast> errorToasts = new ArrayList<>();


    // Mojang search widgets
    private EditBox usernameSearchField;
    private Button searchButton;
    private boolean isSearching = false;

    public PlayerSkinMenuScreen(@Nullable Screen parent) {
        super(Component.literal("Quick Skin"));
        this.parent = parent;
    }

    @Override
    public void tick() {
        super.tick();
        updateDoneButtonState();
    }

    private void updateDoneButtonState() {
        if (this.actionButtonsPanel == null) return;
        Button doneButton = this.actionButtonsPanel.getDoneButton();
        if (doneButton == null) return;

        // Cooldown does not apply in singleplayer
        if (this.minecraft != null && this.minecraft.isSingleplayer()) {
            if (!doneButton.active) {
                doneButton.active = true;
                doneButton.setMessage(Component.literal("Done"));
                doneButton.setTooltip(null);
            }
            return;
        }

        long remainingSeconds = CooldownService.getInstance().getRemainingCooldownSeconds();
        if (remainingSeconds > 0) {
            doneButton.active = false;
            doneButton.setMessage(Component.literal("On Cooldown (" + remainingSeconds + "s)"));
            doneButton.setTooltip(Tooltip.create(Component.literal("You must wait before changing your skin again.")));
        } else {
            if (!doneButton.active) {
                doneButton.active = true;
                doneButton.setMessage(Component.literal("Done"));
                doneButton.setTooltip(null);
            }
        }
    }

    @Override
    protected void init() {
        // Force GUI scale for consistent appearance
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

        // Save rotation state and model type from existing player preview panel before it's destroyed
        if (playerPreviewPanel != null) {
            com.quickskin.mod.client.gui.widget.PlayerWidget widget = playerPreviewPanel.getPlayerWidget();
            if (widget != null) {
                savedBodyYaw = widget.getBodyYaw();
                savedTargetRotation = widget.getTargetYRotation();
            }
            // Save the current model type to preserve it across resizes
            savedModelType = playerPreviewPanel.getCurrentModelType();
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
        int searchButtonWidth = 60;
        // Align with skin entry highlight containers
        // Entry highlights: left = getRowLeft() (list x + ~4px), highlightLeft = left - 4px
        int searchFieldX = componentX + 4;
        int searchFieldWidth = leftPanelWidth - 4;

        usernameSearchField = new EditBox(
                this.font,
                searchFieldX,
                yPos,
                searchFieldWidth - searchButtonWidth - scaledSpacing,
                scaledComponentHeight,
                Component.literal("Search by username")
        );
        usernameSearchField.setSuggestion("Enter a player's username...");
        usernameSearchField.setMaxLength(16);
        usernameSearchField.setResponder(text -> {
            onUsernameFieldChanged(text);
            // Update suggestion visibility
            if (text.isEmpty()) {
                usernameSearchField.setSuggestion("Enter a player's username...");
            } else {
                usernameSearchField.setSuggestion("");
            }
        });
        addRenderableWidget(usernameSearchField);

        searchButton = com.quickskin.mod.client.gui.util.ButtonFactory.createStyled(
                searchFieldX + searchFieldWidth - searchButtonWidth,
                yPos,
                searchButtonWidth,
                scaledComponentHeight,
                Component.literal("Search"),
                button -> searchMojangSkin()
        );
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
                fourButtonWidth,
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
                        Util.getPlatform().openUri("https://mcskins.top/128x128/");
                    }
                },
                () -> {
                    // Skin Website
                    if (this.minecraft != null) {
                        this.minecraft.options.chatLinksPrompt().set(false);
                        Util.getPlatform().openUri("https://laby.net/skins?order=trending_30d");
                    }
                },
                () -> {
                    // Open cape selection screen
                    if (minecraft != null) {
                        minecraft.setScreen(new PlayerCapeMenuScreen(this));
                    }
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

        LinkButtonsPanel linkButtonsPanel = new LinkButtonsPanel(
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

        // Check if we're restoring from a resize (savedModelType is not null)
        boolean isResizing = savedModelType != null;

        // Restore active skin selection first
        AssetMetadata selectedSkin = null;
        if (!config.activeSkinHash.isEmpty() && skinListPanel != null) {
            AssetMetadata metadata = LocalAssetManager.getInstance().getMetadata(config.activeSkinHash);
            if (metadata != null) {
                // Don't trigger callback during resize - we'll set model type manually
                skinListPanel.setSelected(metadata, !isResizing);
                selectedSkin = metadata;
            }
        } else if (config.activeSkinHash.isEmpty() && !config.playerOwnSkinHash.isEmpty() && skinListPanel != null) {
            // If no active skin is set, auto-select the player's own skin
            AssetMetadata playerOwnSkin = LocalAssetManager.getInstance().getMetadata(config.playerOwnSkinHash);
            if (playerOwnSkin != null) {
                // Don't trigger callback during resize - we'll set model type manually
                skinListPanel.setSelected(playerOwnSkin, !isResizing);
                selectedSkin = playerOwnSkin;
                QuickSkin.LOGGER.info("Auto-selected player's own skin in menu");
            }
        }

        // Restore model type preference for the selected skin
        if (playerPreviewPanel != null && selectedSkin != null && isResizing) {
            // During resize, use the saved model type
            playerPreviewPanel.setCurrentModelType(savedModelType);

            // Update the preview with the correct skin
            playerPreviewPanel.updateSkin(
                    selectedSkin,
                    LocalAssetManager.getInstance().getTextureLocation(selectedSkin.hash(), com.quickskin.mod.common.data.TextureQuality.FULL)
            );

            // Clear saved model type after using it
            savedModelType = null;
        }
        // Note: If not resizing, onSkinSelected callback will handle loading the preference

        // Restore active cape selection
        if (!config.activeCapeHash.isEmpty() && playerPreviewPanel != null) {
            String capeId = config.activeCapeHash;
            ResourceLocation capeLocation = getCapeLocationFromId(capeId);
            if (capeLocation != null) {
                // Register animation if this is an animated cape
                registerCapeAnimationIfNeeded(capeId, capeLocation);

                playerPreviewPanel.updateCape(capeLocation, capeId);
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
     * Register cape animation if the cape is animated
     * @param capeId Cape ID (format: "local_cape:hash" or "known:id")
     * @param capeLocation Texture location (atlas)
     */
    private void registerCapeAnimationIfNeeded(String capeId, ResourceLocation capeLocation) {
        // Determine animation ID from cape ID
        if (capeId.startsWith("local_cape:")) {
            String hash = capeId.substring("local_cape:".length());
            String animationId = "cape_" + hash;

            // Check if this local cape has animation metadata
            com.quickskin.mod.common.data.AnimationMetadata metadata =
                    LocalAssetManager.getInstance().getAnimationMetadata(hash);

            if (metadata != null && metadata.frameCount() > 1) {
                // Load atlas image from cache
                java.awt.image.BufferedImage atlasImage =
                        LocalAssetManager.getInstance().getSourceImage(hash);

                if (atlasImage != null) {
                    // Register animation
                    QuickSkin.LOGGER.info("Registering animation for cape in skin menu: {}", animationId);
                    com.quickskin.mod.client.services.AnimatedTextureManager.getInstance()
                            .registerAnimation(animationId, capeId, capeLocation, atlasImage, metadata);
                }
            }
        }
        // Known capes might also be animated
        // For now, we'll skip this as known capes use a different system
        // but you could add similar logic if needed
    }

    /**
     * Calculate panel dimensions based on screen size
     * Uses FIXED sizes since we're forcing GUI scale
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
        PlatformHelper.blit(graphics, VIGNETTE_LOCATION, 0, 0, 0, 0.0F, 0.0F, this.width, this.height, this.width, this.height);

        // 4. Reset render state to avoid affecting other GUI elements.
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * Renders the animated star pattern (OPTIMIZED - pre-tiled texture cache, 1 draw call)
     */
    private void renderStarPattern(GuiGraphics graphics, float partialTick) {
        double pixelsPerSecond = 5.0;
        int tileSize = com.quickskin.mod.client.gui.StarPatternCache.getTileSize();

        // Calculate smooth scrolling offset
        int tickCount = this.minecraft != null ? this.minecraft.gui.getGuiTicks() : 0;
        double smoothTime = (tickCount + partialTick) / 20.0;
        double offsetX = (smoothTime * pixelsPerSecond) % tileSize;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 0.15F);

        // Use the pre-tiled cached texture
        ResourceLocation cacheTexture = com.quickskin.mod.client.gui.StarPatternCache.getTextureLocation();
        int cacheWidth = com.quickskin.mod.client.gui.StarPatternCache.getTextureWidth();
        int cacheHeight = com.quickskin.mod.client.gui.StarPatternCache.getTextureHeight();

        // Calculate UV coordinates for smooth sub-pixel scrolling
        // The offset creates the scrolling effect via UV manipulation
        float u0 = (float)offsetX / (float)cacheWidth;
        float v0 = 0.0f;
        float u1 = u0 + ((float)this.width / (float)cacheWidth);
        float v1 = (float)this.height / (float)cacheHeight;

        // Render a single quad with the scrolling UV coordinates
        var pose = graphics.pose();
        pose.pushPose();

        RenderSystem.setShaderTexture(0, cacheTexture);
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexShader);

        com.mojang.blaze3d.vertex.Tesselator tesselator = com.mojang.blaze3d.vertex.Tesselator.getInstance();
        com.mojang.blaze3d.vertex.BufferBuilder bufferBuilder = tesselator.begin(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX);

        bufferBuilder.addVertex(pose.last().pose(), 0, this.height, 0).setUv(u0, v1);
        bufferBuilder.addVertex(pose.last().pose(), this.width, this.height, 0).setUv(u1, v1);
        bufferBuilder.addVertex(pose.last().pose(), this.width, 0, 0).setUv(u1, v0);
        bufferBuilder.addVertex(pose.last().pose(), 0, 0, 0).setUv(u0, v0);
        com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(bufferBuilder.buildOrThrow());

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

        // Push pose
        graphics.pose().pushPose();

        // Render widgets (buttons, etc.)
        super.render(graphics, mouseX, mouseY, partialTick);

        // Pop pose
        graphics.pose().popPose();

        // Render error toasts (on top of everything)
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

        // Only restore GUI scale if we're actually closing (not just opening a modal)
        // The isClosing flag is set by onClose() when truly exiting the menu
        if (isClosing) {
            restoreGuiScaleIfNeeded();
        }
    }


    @Override
    public void onClose() {
        // Mark that we're truly closing (not just opening a modal)
        isClosing = true;

        // Restore GUI scale before closing
        restoreGuiScaleIfNeeded();

        // Return to parent screen (or null to return to game)
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    /**
     * Restore the original GUI scale if it was forced by this screen.
     * This method is idempotent and can be called multiple times safely.
     */
    private void restoreGuiScaleIfNeeded() {
        if (guiScaleForced) {
            guiScaleForced = false;
            GuiScaleManager.restoreOriginalGuiScale();
            QuickSkin.LOGGER.info("PlayerSkinMenuScreen - GUI scale restored");
        }
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
        // Give PlayerWidget input priority for its customization feature
        if (playerPreviewPanel != null) {
            PlayerWidget widget = playerPreviewPanel.getPlayerWidget();
            if (widget != null && widget.mouseClicked(mouseX, mouseY, button)) {
                this.setFocused(widget);
                if (button == 0) {
                    this.setDragging(true);
                }
                return true;
            }
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
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        // Give PlayerWidget input priority for its customization feature
        if (playerPreviewPanel != null) {
            PlayerWidget widget = playerPreviewPanel.getPlayerWidget();
            if (widget != null && widget.mouseScrolled(mouseX, mouseY, deltaX, deltaY)) {
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
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

            // Get the model type preference for this specific skin
            String modelType = LocalAssetManager.getInstance().getSkinModelPreference(metadata.hash());
            QuickSkin.LOGGER.info("onSkinSelected: Loading model preference for skin {}: {}", metadata.friendlyName(), modelType);

            // Update the model buttons to reflect this skin's preference
            playerPreviewPanel.setCurrentModelType(modelType);

            // Update player preview with selected skin
            playerPreviewPanel.updateSkin(
                    metadata,
                    LocalAssetManager.getInstance().getTextureLocation(metadata.hash(), TextureQuality.FULL)
            );

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
            com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
            config.activeSkinHash = metadata.hash();
            config.save();
        }
    }

    /**
     * Called when model type is changed via the model buttons
     */
    private void onModelTypeChanged(String newModelType) {
        // Get the currently selected skin entry
        SkinEntry selectedEntry = skinListPanel != null ? skinListPanel.getSelected() : null;

        if (selectedEntry != null) {
            AssetMetadata metadata = selectedEntry.getMetadata();
            String skinId = "local_skin:" + metadata.hash();

            QuickSkin.LOGGER.info("Changed model type to: {} for skin: {}", newModelType, metadata.friendlyName());

            // Save the model type preference for THIS SPECIFIC SKIN
            LocalAssetManager.getInstance().setSkinModelPreference(metadata.hash(), newModelType);

            // Apply to the actual player in-game (if in-game)
            if (this.minecraft != null && this.minecraft.player != null) {
                // Pass the model type directly - ModelService will handle "auto" detection
                com.quickskin.mod.client.services.PlayerAppearanceService.getInstance()
                        .applySkin(this.minecraft.player.getUUID(), skinId, newModelType);
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
    public <T extends net.minecraft.client.gui.components.events.GuiEventListener & net.minecraft.client.gui.components.Renderable & net.minecraft.client.gui.narration.NarratableEntry> void registerWidget(T widget) {
        this.addRenderableWidget(widget);
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
    /**
     * Refresh the skin list UI
     * Public so it can be called when textures are reloaded
     */
    public void refreshSkinList() {
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
        errorToasts.removeIf(toast -> !toast.render(graphics, width));
    }

    /**
     * Show deletion confirmation dialog
     */
    public void showDeleteConfirmation(AssetMetadata metadata) {
        if (minecraft == null) return;

        String displayName = truncateFileName(metadata.friendlyName());
        minecraft.setScreen(new DeletionConfirmScreen(
                this,
                Component.literal("Delete Skin?"),
                Component.literal("Are you sure you want to delete \"" + displayName + "\"?"),
                (confirmed) -> {
                    if (confirmed) {
                        // Confirm deletion
                        deleteSkin(metadata);
                    }
                    // Return to skin menu screen
                    if (minecraft != null) {
                        minecraft.setScreen(this);
                    }
                },
                true
        ));
    }

    /**
     * Show rename dialog for a skin
     */
    public void showRenameDialog(AssetMetadata metadata) {
        if (minecraft == null) return;

        minecraft.setScreen(new RenameScreen(
                this,
                Component.literal("Rename Skin File"),
                Component.empty(),
                metadata.friendlyName(),
                (newName) -> {
                    // Rename the skin
                    renameSkin(metadata, newName);
                    // Return to skin menu screen
                    if (minecraft != null) {
                        minecraft.setScreen(this);
                    }
                }
        ));
    }

    /**
     * Truncate filename to 35 characters, adding ellipsis if needed
     */
    private String truncateFileName(String name) {
        if (name.length() <= 35) {
            return name;
        }
        return name.substring(0, 32) + "...";
    }

    /**
     * Delete a skin from local storage
     */
    private void deleteSkin(AssetMetadata metadata) {
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();

        // Prevent deletion of the player's own skin
        if (metadata.hash().equals(config.playerOwnSkinHash)) {
            QuickSkin.LOGGER.warn("Cannot delete player's own skin: {}", metadata.friendlyName());
            showError(Component.literal("Cannot delete your own skin!"));
            return;
        }

        try {
            // Delete the file
            Files.deleteIfExists(metadata.path());

            if (minecraft != null) {
                minecraft.getSoundManager().play(
                        net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                                SoundEvents.UI_BUTTON_CLICK.value(), 1.0f
                        )
                );
            }

            // Refresh the asset manager and skin list
            LocalAssetManager.getInstance().discoverLocalAssets();
            refreshSkinList();

            // Auto-select the player's own skin after deletion
            if (!config.playerOwnSkinHash.isEmpty() && skinListPanel != null) {
                AssetMetadata playerOwnSkin = LocalAssetManager.getInstance().getMetadata(config.playerOwnSkinHash);
                if (playerOwnSkin != null) {
                    skinListPanel.setSelected(playerOwnSkin, true);
                    QuickSkin.LOGGER.info("Auto-selected player's own skin after deletion");
                }
            }

            QuickSkin.LOGGER.info("Deleted skin: {}", metadata.friendlyName());
        } catch (IOException e) {
            QuickSkin.LOGGER.error("Failed to delete skin: {}", metadata.friendlyName(), e);
            showError(Component.literal("Failed to delete skin: " + e.getMessage()));
        }
    }

    /**
     * Rename a skin file
     */
    private void renameSkin(AssetMetadata metadata, String newName) {
        LocalAssetManager.RenameResult result = LocalAssetManager.getInstance()
                .renameLocalAsset(metadata.hash(), newName);

        switch (result) {
            case SUCCESS:
                QuickSkin.LOGGER.info("Successfully renamed skin to: {}", newName);

                // Play success sound
                if (minecraft != null) {
                    minecraft.getSoundManager().play(
                            net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                                    SoundEvents.UI_BUTTON_CLICK.value(), 1.0f
                            )
                    );
                }

                // Refresh the skin list to show the new name
                refreshSkinList();

                // Re-select the renamed skin
                if (skinListPanel != null) {
                    AssetMetadata updatedMetadata = LocalAssetManager.getInstance().getMetadata(metadata.hash());
                    if (updatedMetadata != null) {
                        skinListPanel.setSelected(updatedMetadata);
                    }
                }
                break;

            case NAME_TAKEN:
                QuickSkin.LOGGER.warn("Rename failed: Name already exists");
                showError(Component.literal("Error: A skin file with that name already exists."));
                break;

            case INVALID_NAME:
                QuickSkin.LOGGER.warn("Rename failed: Invalid name");
                showError(Component.literal("Error: The name contains invalid characters or is empty."));
                break;

            case IO_ERROR:
                QuickSkin.LOGGER.error("Rename failed: IO error");
                showError(Component.literal("Error: Could not rename the file. See logs."));
                break;

            case NOT_FOUND:
                QuickSkin.LOGGER.error("Rename failed: File not found");
                showError(Component.literal("Error: Could not find the original file."));
                break;
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
                        if (minecraft != null) {
                            minecraft.getSoundManager().play(
                                    net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                                            SoundEvents.UI_BUTTON_CLICK.value(), 1.0f
                                    )
                            );
                        }
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