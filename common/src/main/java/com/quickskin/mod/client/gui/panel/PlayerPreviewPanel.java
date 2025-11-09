package com.quickskin.mod.client.gui.panel;

import com.quickskin.mod.client.gui.widget.PlayerWidget;
import com.quickskin.mod.client.gui.widget.RotateButton;
import com.quickskin.mod.common.data.AssetMetadata;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Panel that manages the player preview widget and model type selection buttons
 */
public class PlayerPreviewPanel extends AbstractWidget {

    @Nullable
    private PlayerWidget playerWidget;

    private Button autoModelButton;
    private Button classicModelButton;
    private Button slimModelButton;
    private Button rotateButton;
    private Button animationToggleButton;
    private final java.util.List<Button> animationButtons = new java.util.ArrayList<>();
    private boolean isAnimationDropdownOpen = false;

    private String currentModelType = "classic";

    @Nullable
    private Consumer<String> modelTypeChangeCallback;

    @Nullable
    private AssetMetadata currentMetadata;

    public PlayerPreviewPanel(int x, int y, int width, int height) {
        super(x, y, width, height, Component.empty());
    }

    /**
     * Set the callback to be called when model type changes
     */
    public void setModelTypeChangeCallback(Consumer<String> callback) {
        this.modelTypeChangeCallback = callback;
    }

    /**
     * Set the current model type (without triggering callback)
     * Used for initialization
     */
    public void setCurrentModelType(String modelType) {
        this.currentModelType = modelType;
        updateModelButtonStates();
    }

    /**
     * Initialize the player widget
     */
    public void initPlayerWidget(com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen screen) {
        // Calculate player widget dimensions
        int widgetSize = Math.min(120, Math.min(height, width));
        int playerWidgetX = getX() + 20;
        int playerWidgetY = getY();

        // Create player widget
        playerWidget = new PlayerWidget(
                playerWidgetX,
                playerWidgetY,
                widgetSize,
                widgetSize,
                null, // Will use default Steve skin
                null, // No cape initially
                null, // No cape ID initially
                "classic" // Default model type
        );
        playerWidget.setContext(com.quickskin.mod.client.gui.widget.PlayerWidget.WidgetContext.SKIN_MENU);

        // Restore shared animation state from title screen
        String savedAnimation = com.quickskin.mod.event.ClientEvents.getSharedAnimation();
        if (savedAnimation != null && !savedAnimation.isEmpty()) {
            playerWidget.setAnimation(savedAnimation);
        }

        screen.registerWidget(playerWidget);
    }

    /**
     * Initialize the model buttons at the specified position (above cape button)
     */
    public void initModelButtons(
            com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen screen,
            int modelButtonsX,
            int modelButtonsY,
            int modelButtonsTotalWidth,
            int componentHeight,
            int spacing
    ) {
        // Auto button is smaller (square button for the emoji), Classic/Slim share the rest equally
        int autoButtonWidth = componentHeight; // Square button
        int remainingWidth = modelButtonsTotalWidth - autoButtonWidth - spacing;
        int normalModelButtonWidth = (remainingWidth - spacing) / 2;

        // Create model type buttons
        autoModelButton = Button.builder(Component.literal("✨"), button -> {
            setModelType("auto");
        }).bounds(modelButtonsX, modelButtonsY, autoButtonWidth, componentHeight).build();
        screen.registerWidget(autoModelButton);

        classicModelButton = Button.builder(Component.literal("Wide"), button -> {
            setModelType("classic");
        }).bounds(modelButtonsX + autoButtonWidth + spacing, modelButtonsY, normalModelButtonWidth, componentHeight).build();
        screen.registerWidget(classicModelButton);

        slimModelButton = Button.builder(Component.literal("Slim"), button -> {
            setModelType("slim");
        }).bounds(modelButtonsX + autoButtonWidth + spacing + normalModelButtonWidth + spacing, modelButtonsY, normalModelButtonWidth, componentHeight).build();
        screen.registerWidget(slimModelButton);

        // Set button references for player widget positioning
        if (playerWidget != null) {
            playerWidget.setModelButtons(autoModelButton, classicModelButton, slimModelButton);
        }

        // Update button states to lock the currently selected model button
        updateModelButtonStates();

        // Create rotate button (positioned at left edge above model buttons)
        int rotateButtonSize = 20;
        int rotateButtonX = modelButtonsX;
        int rotateButtonY = modelButtonsY - rotateButtonSize - spacing;

        rotateButton = new RotateButton(
                rotateButtonX,
                rotateButtonY,
                rotateButtonSize,
                b -> {
                    if (playerWidget != null) {
                        playerWidget.toggleRotation();
                    }
                }
        );
        rotateButton.setTooltip(Tooltip.create(Component.literal("Rotate Preview")));
        screen.registerWidget(rotateButton);

        // Clear animation buttons from previous init
        animationButtons.clear();
        isAnimationDropdownOpen = false;

        // Only add animation buttons when NOT in-game (title screen only)
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        boolean isInGame = mc.player != null;

        if (!isInGame) {
            // Create animation toggle button (right edge above model buttons)
            int animToggleWidth = 20;
            int animToggleX = modelButtonsX + modelButtonsTotalWidth - animToggleWidth;
            int animToggleY = rotateButtonY;

            animationToggleButton = Button.builder(
                    Component.literal(">"),
                    button -> toggleAnimationDropdown()
            ).bounds(animToggleX, animToggleY, animToggleWidth, rotateButtonSize).build();
            screen.registerWidget(animationToggleButton);

            // Create numbered animation buttons (dropdown)
            java.util.List<String> availableAnimations = getAvailableAnimations();
            for (int i = 0; i < availableAnimations.size(); i++) {
                final String animName = availableAnimations.get(i);
                final int index = i;

                Button animButton = Button.builder(
                        Component.literal(String.valueOf(index + 1)),
                        button -> {
                            // Set the animation on the player widget
                            if (playerWidget != null) {
                                playerWidget.setAnimation(animName);
                                // Save animation state for persistence across all screens
                                com.quickskin.mod.event.ClientEvents.setSharedAnimation(animName);
                                com.quickskin.mod.QuickSkin.LOGGER.info("Animation {} activated: {}", index + 1, animName);
                            }
                            toggleAnimationDropdown();
                        }
                ).bounds(animToggleX, animToggleY - (i + 1) * 22, animToggleWidth, rotateButtonSize).build();

                animButton.visible = false;
                animButton.active = false;
                animationButtons.add(animButton);
                screen.registerWidget(animButton);
            }
        }
    }

    /**
     * Toggle the animation dropdown open/closed
     */
    private void toggleAnimationDropdown() {
        isAnimationDropdownOpen = !isAnimationDropdownOpen;
        updateAnimationDropdownState();
    }

    /**
     * Update animation dropdown button visibility and toggle button text
     */
    private void updateAnimationDropdownState() {
        if (animationToggleButton != null) {
            animationToggleButton.setMessage(Component.literal(isAnimationDropdownOpen ? "×" : ">"));
        }
        for (Button button : animationButtons) {
            button.visible = isAnimationDropdownOpen;
            button.active = isAnimationDropdownOpen;
        }
    }

    /**
     * Get list of available animations
     * Returns vanilla Minecraft animation states
     */
    private java.util.List<String> getAvailableAnimations() {
        java.util.List<String> animations = new java.util.ArrayList<>();
        animations.add("idle");   // Button 1: Idle pose
        animations.add("walk");   // Button 2: Walking pose
        animations.add("sit");    // Button 3: Sitting pose
        return animations;
    }

    /**
     * Update the skin displayed in the player preview
     */
    public void updateSkin(AssetMetadata metadata, net.minecraft.resources.ResourceLocation skinLocation) {
        if (playerWidget != null && metadata != null) {
            this.currentMetadata = metadata;
            playerWidget.setSkin(skinLocation);

            // Update model type based on current mode
            if ("auto".equals(currentModelType)) {
                // Auto-detect from actual texture
                String detectedModel = detectModelFromTexture(metadata);
                playerWidget.setModelType(detectedModel);
            } else {
                // Use explicitly selected model
                playerWidget.setModelType(currentModelType);
            }
        }
    }

    /**
     * Update the cape displayed in the player preview
     */
    public void updateCape(@Nullable net.minecraft.resources.ResourceLocation capeLocation, @Nullable String capeId) {
        if (playerWidget != null) {
            playerWidget.setCape(capeLocation, capeId);
        }
    }

    /**
     * Get the player widget (for accessing rotation state)
     */
    @Nullable
    public com.quickskin.mod.client.gui.widget.PlayerWidget getPlayerWidget() {
        return playerWidget;
    }

    /**
     * Detect model type from texture data
     */
    private String detectModelFromTexture(AssetMetadata metadata) {
        try {
            // Load texture and detect model
            byte[] textureData = com.quickskin.mod.client.services.LocalAssetManager.getInstance()
                    .loadTexture(metadata.hash(), com.quickskin.mod.common.data.TextureQuality.PREVIEW);

            if (textureData != null) {
                String detected = com.quickskin.mod.common.util.SkinModelDetector.detectSkinModel(textureData);
                com.quickskin.mod.QuickSkin.LOGGER.info("Preview: Auto-detected model from texture for {}: {}",
                        metadata.friendlyName(), detected);
                return detected;
            }
        } catch (Exception e) {
            com.quickskin.mod.QuickSkin.LOGGER.error("Failed to detect model from texture", e);
        }

        // Fallback to metadata if detection fails
        com.quickskin.mod.QuickSkin.LOGGER.warn("Preview: Falling back to metadata model: {}", metadata.skinModel());
        return metadata.skinModel() != null ? metadata.skinModel() : "classic";
    }

    /**
     * Set the model type for the player preview
     */
    private void setModelType(String modelType) {
        this.currentModelType = modelType;
        com.quickskin.mod.QuickSkin.LOGGER.info("[PlayerPreviewPanel] setModelType called: {}, callback is null: {}",
            modelType, modelTypeChangeCallback == null);

        // Update preview widget
        if (playerWidget != null) {
            // If auto mode, detect from actual texture
            if ("auto".equals(modelType) && currentMetadata != null) {
                String detectedModel = detectModelFromTexture(currentMetadata);
                playerWidget.setModelType(detectedModel);
            } else {
                playerWidget.setModelType(modelType);
            }
        }

        // Notify callback to apply to actual player
        if (modelTypeChangeCallback != null) {
            com.quickskin.mod.QuickSkin.LOGGER.info("[PlayerPreviewPanel] Calling callback for model type: {}", modelType);
            modelTypeChangeCallback.accept(modelType);
        } else {
            com.quickskin.mod.QuickSkin.LOGGER.warn("[PlayerPreviewPanel] Callback is NULL! Cannot save model preference!");
        }

        updateModelButtonStates();
    }

    /**
     * Get the current model type
     */
    public String getCurrentModelType() {
        return currentModelType;
    }

    /**
     * Update the active state of model buttons based on current selection
     */
    private void updateModelButtonStates() {
        if (autoModelButton != null && classicModelButton != null && slimModelButton != null) {
            boolean isAuto = "auto".equalsIgnoreCase(currentModelType);
            boolean isSlim = "slim".equalsIgnoreCase(currentModelType);
            boolean isClassic = "classic".equalsIgnoreCase(currentModelType);

            // Button is active (clickable) when it's NOT the current model
            autoModelButton.active = !isAuto;
            classicModelButton.active = !isClassic;
            slimModelButton.active = !isSlim;
        }
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // This panel doesn't render anything itself - child widgets handle rendering
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        // No narration needed for container panel
    }
}