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
            "classic" // Default model type
        );
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

        // Create rotate button (positioned relative to the model buttons)
        int rotateButtonSize = 20;
        int rotateButtonX = modelButtonsX + modelButtonsTotalWidth - rotateButtonSize;
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
    }

    /**
     * Update the skin displayed in the player preview
     */
    public void updateSkin(AssetMetadata metadata, net.minecraft.resources.ResourceLocation skinLocation) {
        if (playerWidget != null && metadata != null) {
            this.currentMetadata = metadata;
            playerWidget.setSkin(skinLocation);

            // Update model type if auto-detect
            if ("auto".equals(currentModelType)) {
                playerWidget.setModelType(metadata.skinModel());
            }
        }
    }

    /**
     * Set the model type for the player preview
     */
    private void setModelType(String modelType) {
        this.currentModelType = modelType;

        // Update preview widget
        if (playerWidget != null) {
            // If auto mode, use the detected model from metadata
            if ("auto".equals(modelType) && currentMetadata != null) {
                playerWidget.setModelType(currentMetadata.skinModel());
            } else {
                playerWidget.setModelType(modelType);
            }
        }

        // Notify callback to apply to actual player
        if (modelTypeChangeCallback != null) {
            modelTypeChangeCallback.accept(modelType);
        }

        updateModelButtonStates();
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
