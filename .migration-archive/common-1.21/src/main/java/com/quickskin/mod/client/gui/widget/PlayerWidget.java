package com.quickskin.mod.client.gui.widget;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.rendering.PlayerModelRenderer;
import com.quickskin.mod.client.rendering.PreviewPlayerData;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Widget that displays a 3D rotating player model preview
 * Uses vanilla PlayerModel rendering instead of GeckoLib
 */
@Environment(EnvType.CLIENT)
public class PlayerWidget extends AbstractWidget {

    private final PreviewPlayerData previewData;

    // Rotation state
    private float bodyYaw = 20.0f; // 20 degrees for sideways pose (matching original)
    private float targetYRotation = 20.0f; // Target rotation for smooth animation

    // Display settings
    private float scale = 87.2f; // 10% smaller than previous (96.9 * 0.9 = 87.21)
    private static final float DEFAULT_SCALE = 87.2f; // Default scale value
    private static final float MIN_SCALE = 20.0f; // Minimum scale for resize
    private static final float MAX_SCALE = 200.0f; // Maximum scale for resize
    private static final float SCALE_STEP = 3.0f; // Scale change per scroll tick (smaller for smoother resizing)

    // Pivot point for scaling (at the feet position - where the red crosshair is)
    private static final float PIVOT_OFFSET = 0.0f; // No offset - pivot at feet (crosshair position)

    // Dragging state for repositioning
    private boolean isDragging = false;
    private int dragStartX = 0;
    private int dragStartY = 0;
    private int dragStartOffsetX = 0;
    private int dragStartOffsetY = 0;

    // Cached model bounds for mouse interaction (updated each frame in renderWidget)
    private int cachedModelCenterX = 0;
    private int cachedModelCenterY = 0;
    private float cachedScale = DEFAULT_SCALE;

    // Context type for this widget
    public enum WidgetContext {
        TITLE_SCREEN,
        SKIN_MENU,
        CAPE_MENU,
        PAUSE_MENU,
        OTHER
    }
    private WidgetContext context = WidgetContext.OTHER;

    // Button references for positioning (like the original mod)
    private net.minecraft.client.gui.components.Button autoButton = null;
    private net.minecraft.client.gui.components.Button classicButton = null;
    private net.minecraft.client.gui.components.Button slimButton = null;

    // Custom reference point (alternative to button positioning)
    private Integer customCenterX = null;
    private Integer customCenterY = null;

    // Default offset from button center
    private static final double DEFAULT_OFFSET_FROM_BUTTON_Y = -15.0; // Moved up 5px from -15.0

    /**
     * Creates a new player widget
     * @param x X position
     * @param y Y position
     * @param width Widget width
     * @param height Widget height
     * @param skinLocation Initial skin texture (can be null)
     * @param capeLocation Initial cape texture (can be null)
     * @param capeId ID of the cape, for animations (can be null)
     * @param modelType "slim" or "classic"
     */
    public PlayerWidget(int x, int y, int width, int height,
                        @Nullable ResourceLocation skinLocation,
                        @Nullable ResourceLocation capeLocation,
                        @Nullable String capeId,
                        String modelType) {
        super(x, y, width, height, Component.empty());

        this.previewData = new PreviewPlayerData();
        this.previewData.setSkinLocation(
                skinLocation != null ? skinLocation : ResourceLocation.fromNamespaceAndPath("minecraft", "textures/entity/player/wide/steve.png")
        );
        this.previewData.setCapeLocation(capeLocation);
        this.previewData.setCapeId(capeId);
        this.previewData.setModelType(modelType != null ? modelType : "classic");

        // Ensure widget is visible and active
        this.visible = true;
        this.active = true;
    }

    /**
     * Get the X position for model rendering (dynamically calculated)
     * Uses custom reference first, then model buttons if available, otherwise widget center
     * Applies saved position offset from config
     */
    private int getModelCenterX() {
        // Get saved offset from config
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        int offsetX = getPositionOffsetXFromConfig(config);

        // Priority 1: Custom reference point
        if (customCenterX != null) {
            return customCenterX + offsetX;
        }
        // Priority 2: In skin menu: Center of all three model buttons (Auto, Wide, Slim)
        if (autoButton != null && slimButton != null) {
            // Calculate center of the entire button group
            int leftEdge = autoButton.getX();
            int rightEdge = slimButton.getX() + slimButton.getWidth();
            int middleX = (leftEdge + rightEdge) / 2;
            return middleX + offsetX;
        }
        // Priority 3: Fallback: if only classic/slim buttons exist
        else if (classicButton != null && slimButton != null) {
            int classicCenterX = classicButton.getX() + classicButton.getWidth() / 2;
            int slimCenterX = slimButton.getX() + slimButton.getWidth() / 2;
            int middleX = (classicCenterX + slimCenterX) / 2;
            return middleX + offsetX;
        }
        // Priority 4: Fallback to widget center if no reference
        return getX() + getWidth() / 2 + offsetX;
    }

    /**
     * Get the base Y position for model rendering (without scale adjustments)
     * Uses custom reference first, then Classic/Slim buttons if available, otherwise widget center
     * Applies saved position offset from config
     */
    private int getBaseModelCenterY() {
        // Get saved offset from config
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        int offsetY = getPositionOffsetYFromConfig(config);

        // Priority 1: Custom reference point
        if (customCenterY != null) {
            return customCenterY + offsetY;
        }
        // Priority 2: In skin menu: Classic button Y coordinate (Classic and Slim are on same Y)
        if (classicButton != null) {
            int buttonCenterY = classicButton.getY() + classicButton.getHeight() / 2;
            return (int)(buttonCenterY + DEFAULT_OFFSET_FROM_BUTTON_Y) + offsetY;
        }
        // Priority 3: Fallback to widget center if no reference
        return getY() + getHeight() / 2 + 10 + offsetY; // Offset down slightly
    }

    /**
     * Get the Y position for model rendering (adjusted for current scale with pivot point)
     * The pivot point is below the model's feet, so scaling keeps that point fixed
     */
    private int getModelCenterY() {
        int baseY = getBaseModelCenterY();

        // Calculate the pivot point Y (at default scale, it's PIVOT_OFFSET below the model center)
        // This point remains fixed regardless of scale
        float pivotY = baseY + PIVOT_OFFSET;

        // Calculate where the model center should be based on current scale
        // As scale increases, model moves up (away from pivot)
        // As scale decreases, model moves down (toward pivot)
        float scaleRatio = scale / DEFAULT_SCALE;
        float offsetFromPivot = PIVOT_OFFSET * scaleRatio;

        return (int)(pivotY - offsetFromPivot);
    }

    /**
     * Set button references for positioning
     */
    public void setModelButtons(net.minecraft.client.gui.components.Button auto,
                                net.minecraft.client.gui.components.Button classic,
                                net.minecraft.client.gui.components.Button slim) {
        this.autoButton = auto;
        this.classicButton = classic;
        this.slimButton = slim;
    }

    /**
     * Set custom reference point for positioning (alternative to button references)
     * @param centerX X coordinate of the reference point
     * @param centerY Y coordinate of the reference point
     */
    public void setCustomReferencePoint(int centerX, int centerY) {
        this.customCenterX = centerX;
        this.customCenterY = centerY;
    }

    /**
     * Set the context for this widget (determines slider positioning)
     * @param context The context (TITLE_SCREEN, SKIN_MENU, or OTHER)
     */
    public void setContext(WidgetContext context) {
        this.context = context;
    }

    /**
     * Get the slider percentage from config based on current context
     * When config is 0, returns built-in default for that context
     */
    private int getSliderPercentageFromConfig(com.quickskin.mod.config.ClientConfig config) {
        int configValue;
        int defaultValue;

        switch (context) {
            case TITLE_SCREEN:
                configValue = config.sizeModelPreviewPercentageTitleScreen;
                defaultValue = 30;
                break;
            case SKIN_MENU:
                configValue = config.sizeModelPreviewPercentageSkinMenu;
                defaultValue = 51;
                break;
            case CAPE_MENU:
                configValue = config.sizeModelPreviewPercentageCapeMenu;
                defaultValue = 51;
                break;
            case PAUSE_MENU:
                configValue = config.sizeModelPreviewPercentagePauseMenu;
                defaultValue = 32;
                break;
            default:
                return 50;
        }

        return configValue != 0 ? configValue : defaultValue;
    }

    /**
     * Save the slider percentage to config based on current context
     */
    private void saveSliderPercentageToConfig(int percentage) {
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        switch (context) {
            case TITLE_SCREEN:
                config.sizeModelPreviewPercentageTitleScreen = percentage;
                break;
            case SKIN_MENU:
                config.sizeModelPreviewPercentageSkinMenu = percentage;
                break;
            case CAPE_MENU:
                config.sizeModelPreviewPercentageCapeMenu = percentage;
                break;
            case PAUSE_MENU:
                config.sizeModelPreviewPercentagePauseMenu = percentage;
                break;
            case OTHER:
                // Don't save for OTHER context
                break;
        }
        config.save();
    }

    /**
     * Get the position X offset from config based on current context
     * Returns the raw config value (defaults are baked into base positions)
     */
    private int getPositionOffsetXFromConfig(com.quickskin.mod.config.ClientConfig config) {
        return switch (context) {
            case TITLE_SCREEN -> config.positionOffsetXTitleScreen;
            case SKIN_MENU -> config.positionOffsetXSkinMenu;
            case CAPE_MENU -> config.positionOffsetXCapeMenu;
            case PAUSE_MENU -> config.positionOffsetXPauseMenu;
            default -> 0;
        };
    }

    /**
     * Get the position Y offset from config based on current context
     * Returns the raw config value (defaults are baked into base positions)
     */
    private int getPositionOffsetYFromConfig(com.quickskin.mod.config.ClientConfig config) {
        return switch (context) {
            case TITLE_SCREEN -> config.positionOffsetYTitleScreen;
            case SKIN_MENU -> config.positionOffsetYSkinMenu;
            case CAPE_MENU -> config.positionOffsetYCapeMenu;
            case PAUSE_MENU -> config.positionOffsetYPauseMenu;
            default -> 0;
        };
    }

    /**
     * Save position offsets to config based on current context
     */
    private void savePositionOffsetsToConfig(int offsetX, int offsetY) {
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        switch (context) {
            case TITLE_SCREEN:
                config.positionOffsetXTitleScreen = offsetX;
                config.positionOffsetYTitleScreen = offsetY;
                break;
            case SKIN_MENU:
                config.positionOffsetXSkinMenu = offsetX;
                config.positionOffsetYSkinMenu = offsetY;
                break;
            case CAPE_MENU:
                config.positionOffsetXCapeMenu = offsetX;
                config.positionOffsetYCapeMenu = offsetY;
                break;
            case PAUSE_MENU:
                config.positionOffsetXPauseMenu = offsetX;
                config.positionOffsetYPauseMenu = offsetY;
                break;
            case OTHER:
                // Don't save for OTHER context
                return;
        }
        config.save();
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Load and apply scale from config
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        int savedPercentage = getSliderPercentageFromConfig(config);

        // Convert percentage (1-100%) to scale value
        float percentageAsFloat = (savedPercentage - 1) / 99.0f; // Convert 1-100 to 0.0-1.0
        scale = MIN_SCALE + percentageAsFloat * (MAX_SCALE - MIN_SCALE);

        // Ensure cape animation is registered before rendering
        if (previewData.getCapeId() != null && previewData.getCapeLocation() != null) {
            String capeId = previewData.getCapeId();
            String animationId = null;

            if (capeId.startsWith("local_cape:")) {
                animationId = "cape_" + capeId.substring("local_cape:".length());
            } else if (capeId.startsWith("known:")) {
                animationId = "cape_known_" + capeId.substring("known:".length());
            }

            // Check if animation should exist but isn't registered yet
            if (animationId != null) {
                com.quickskin.mod.client.services.AnimatedTextureManager animManager =
                    com.quickskin.mod.client.services.AnimatedTextureManager.getInstance();

                if (!animManager.isAnimated(animationId)) {
                    // Animation not registered yet - try to register it now
                    if (capeId.startsWith("local_cape:")) {
                        String hash = capeId.substring("local_cape:".length());
                        com.quickskin.mod.common.data.AnimationMetadata metadata =
                            com.quickskin.mod.client.services.LocalAssetManager.getInstance().getAnimationMetadata(hash);
                        java.awt.image.BufferedImage atlasImage =
                            com.quickskin.mod.client.services.LocalAssetManager.getInstance().getSourceImage(hash);

                        if (metadata != null && atlasImage != null) {
                            animManager.registerAnimation(animationId, capeId, previewData.getCapeLocation(), atlasImage, metadata);
                        }
                    } else if (capeId.startsWith("known:")) {
                        String knownId = capeId.substring("known:".length());
                        com.quickskin.mod.client.services.CapeService.getInstance().loadKnownCape(knownId);
                    }
                }
            }
        }

        // Tick animations (updates frame indices for animated capes)
        // This is necessary because ClientTickEvent.CLIENT_POST doesn't fire in menus
        com.quickskin.mod.client.services.AnimatedTextureManager animManager =
            com.quickskin.mod.client.services.AnimatedTextureManager.getInstance();
        animManager.tick();

        // Smoothly animate rotation towards target
        if (Math.abs(targetYRotation - bodyYaw) > 0.1f) {
            float diff = targetYRotation - bodyYaw;
            // Smooth interpolation (lerp with factor 0.15)
            bodyYaw += diff * 0.15f;
        } else {
            bodyYaw = targetYRotation;
        }

        // Get current model center (recalculated each frame for correct rotation pivot)
        int modelCenterX = getModelCenterX();
        int modelCenterY = getModelCenterY();

        // Cache these values for mouse interaction
        cachedModelCenterX = modelCenterX;
        cachedModelCenterY = modelCenterY;
        cachedScale = scale;

        // Update preview data
        previewData.setYRotation(bodyYaw);
        previewData.setHeadYaw(0.0f);
        previewData.setHeadPitch(0.0f);

        // Render the player model
        // Use GuiGraphics directly for vanilla rendering method
        PlayerModelRenderer.renderPlayerModel(
                graphics,
                modelCenterX,
                modelCenterY,
                scale,
                bodyYaw,
                previewData,
                mouseX,
                mouseY,
                false
        );

        // Render border around actual player model rendering area (only if customization is enabled)
        if (config.enablePlayerPreviewCustomization) {
            renderModelBorder(graphics, modelCenterX, modelCenterY, scale);
        }
    }

    /**
     * Render a border around where the player model is actually rendered (for debugging/positioning)
     */
    private void renderModelBorder(GuiGraphics graphics, int centerX, int centerY, float scale) {
        // Approximate the player model bounds
        // A Minecraft player is ~1.8 blocks tall, and with scale, this gives us the visual height
        // The width is approximately 60% of the height for a standing player
        int modelHeight = (int)(scale * 2.0f); // Approximate rendered height
        int modelWidth = (int)(modelHeight * 0.6f); // Approximate rendered width

        // Calculate bounding box (model is centered horizontally, feet at centerY)
        int left = centerX - modelWidth / 2;
        int right = centerX + modelWidth / 2;
        int top = centerY - modelHeight;

        // Draw instructional text above the border
        net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;
        String instructionText = "Mouse wheel: resize | Left click: move";
        int textWidth = font.width(instructionText);
        int textX = centerX - textWidth / 2; // Center the text horizontally
        int textY = top - 12; // Position text 12 pixels above the border
        int textColor = 0xFFFFFFFF; // White text

        // Draw text with shadow for better readability
        graphics.drawString(font, instructionText, textX, textY, textColor, true);

        // Draw border (2 pixels thick for visibility) in bright green
        int borderColor = 0xFF00FF00; // Bright green

        // Top edge
        graphics.fill(left, top, right, top + 2, borderColor);
        // Bottom edge
        graphics.fill(left, centerY - 2, right, centerY, borderColor);
        // Left edge
        graphics.fill(left, top, left + 2, centerY, borderColor);
        // Right edge
        graphics.fill(right - 2, top, right, centerY, borderColor);

        // Draw center crosshair to show exact model center
        int crosshairSize = 5;
        int crosshairColor = 0xFFFF0000; // Red
        // Horizontal line
        graphics.fill(centerX - crosshairSize, centerY - 1, centerX + crosshairSize, centerY + 1, crosshairColor);
        // Vertical line
        graphics.fill(centerX - 1, centerY - crosshairSize, centerX + 1, centerY + crosshairSize, crosshairColor);
    }

    /**
     * Update the skin texture
     */
    public void setSkin(@Nullable ResourceLocation skinLocation) {
        if (skinLocation != null) {
            previewData.setSkinLocation(skinLocation);
        }
    }

    /**
     * Update the cape texture and ID
     */
    public void setCape(@Nullable ResourceLocation capeLocation, @Nullable String capeId) {
        previewData.setCapeLocation(capeLocation);
        previewData.setCapeId(capeId);
    }

    /**
     * Update the model type
     */
    public void setModelType(String modelType) {
        previewData.setModelType(modelType);
    }

    /**
     * Toggle rotation - adds 180 degrees to target rotation
     * Allows spamming for continuous spin
     */
    public void toggleRotation() {
        targetYRotation += 180.0f;
    }

    /**
     * Get current body yaw (current rotation)
     */
    public float getBodyYaw() {
        return bodyYaw;
    }

    /**
     * Get target rotation (where it's animating towards)
     */
    public float getTargetYRotation() {
        return targetYRotation;
    }

    /**
     * Set rotation state (for restoring after widget recreation)
     */
    public void setRotationState(float bodyYaw, float targetYRotation) {
        this.bodyYaw = bodyYaw;
        this.targetYRotation = targetYRotation;
    }

    /**
     * Set the animation state for the player model
     * @param animation Animation name (idle, walk, run, sneak, sit, jump)
     */
    public void setAnimation(String animation) {
        if (animation != null && !animation.isEmpty()) {
            previewData.setCurrentAnimation(animation);
        }
    }

    /**
     * Get the current animation
     */
    public String getAnimation() {
        return previewData.getCurrentAnimation();
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        // Do nothing - make widget non-clickable
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Check if customization feature is enabled and left click
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        if (!config.enablePlayerPreviewCustomization || button != 0) {
            return false;
        }

        // Only handle clicks within the model's interactive area (the green box)
        if (!isMouseOver(mouseX, mouseY)) {
            return false;
        }

        // Start dragging
        isDragging = true;
        dragStartX = (int)mouseX;
        dragStartY = (int)mouseY;
        dragStartOffsetX = getPositionOffsetXFromConfig(config);
        dragStartOffsetY = getPositionOffsetYFromConfig(config);

        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (!isDragging || button != 0) {
            return false;
        }

        // Stop dragging
        isDragging = false;
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!isDragging || button != 0) {
            return false;
        }

        // Calculate new offsets based on drag distance
        int deltaX = (int)mouseX - dragStartX;
        int deltaY = (int)mouseY - dragStartY;

        int newOffsetX = dragStartOffsetX + deltaX;
        int newOffsetY = dragStartOffsetY + deltaY;

        // Save the new offsets to config
        savePositionOffsetsToConfig(newOffsetX, newOffsetY);

        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        // Check if customization feature is enabled
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        if (!config.enablePlayerPreviewCustomization) {
            return false;
        }

        // Only handle scroll events within the model's interactive area (the green box)
        if (!isMouseOver(mouseX, mouseY)) {
            return false;
        }

        // Adjust scale based on scroll direction
        float oldScale = scale;
        scale += (float)deltaY * SCALE_STEP;

        // Clamp to min/max
        scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));

        // Only save if scale actually changed
        if (scale != oldScale) {
            // Calculate and save percentage
            float scaleRange = MAX_SCALE - MIN_SCALE;
            float currentScaleOffset = scale - MIN_SCALE;
            int percentage = Math.round((currentScaleOffset / scaleRange) * 99.0f) + 1; // 1-100%

            // Save to config
            saveSliderPercentageToConfig(percentage);
        }

        return true; // Consume the scroll event
    }

    /**
     * Check if mouse is over this widget
     * When customization is enabled, use the model area (green border).
     * Otherwise, use the full widget bounds.
     */
    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();

        // When customization is enabled, check if mouse is inside the model area (green border)
        if (config.enablePlayerPreviewCustomization) {
            return !isMouseOutsideModelArea(mouseX, mouseY, cachedModelCenterX, cachedModelCenterY, cachedScale);
        }

        // Otherwise, use the full widget bounds
        return mouseX >= getX() && mouseX < getX() + getWidth() &&
               mouseY >= getY() && mouseY < getY() + getHeight();
    }

    /**
     * Check if mouse is outside the player model rendering area (the green debug border area)
     */
    private boolean isMouseOutsideModelArea(double mouseX, double mouseY, int centerX, int centerY, float scale) {
        // Calculate the same bounds as renderModelBorder()
        int modelHeight = (int)(scale * 2.0f);
        int modelWidth = (int)(modelHeight * 0.6f);

        int left = centerX - modelWidth / 2;
        int right = centerX + modelWidth / 2;
        int top = centerY - modelHeight;

        return mouseX < left || mouseX > right ||
               mouseY < top || mouseY > centerY;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        // Add accessibility narration
        narrationElementOutput.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE,
                Component.literal("Player preview"));
    }
}