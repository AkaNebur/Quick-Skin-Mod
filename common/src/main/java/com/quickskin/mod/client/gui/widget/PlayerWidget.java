package com.quickskin.mod.client.gui.widget;

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

    // Mouse interaction
    private boolean isDragging = false;
    private double lastMouseX = 0;
    private double lastMouseY = 0;

    // Rotation state
    private float bodyYaw = 20.0f; // 20 degrees for sideways pose (matching original)
    private float headYaw = 0.0f;
    private float headPitch = 0.0f;
    private float targetYRotation = 20.0f; // Target rotation for smooth animation

    // Animation state (disabled for static pose)
    private boolean autoRotate = false; // Disabled - keep static pose

    // Display settings
    private float scale = 87.2f; // 10% smaller than previous (96.9 * 0.9 = 87.21)


    // Button references for positioning (like the original mod)
    private net.minecraft.client.gui.components.Button autoButton = null;
    private net.minecraft.client.gui.components.Button classicButton = null;
    private net.minecraft.client.gui.components.Button slimButton = null;

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
     * @param modelType "slim" or "classic"
     */
    public PlayerWidget(int x, int y, int width, int height,
                       @Nullable ResourceLocation skinLocation,
                       @Nullable ResourceLocation capeLocation,
                       String modelType) {
        super(x, y, width, height, Component.empty());

        this.previewData = new PreviewPlayerData();
        this.previewData.setSkinLocation(
            skinLocation != null ? skinLocation : new ResourceLocation("minecraft", "textures/entity/player/wide/steve.png")
        );
        this.previewData.setCapeLocation(capeLocation);
        this.previewData.setModelType(modelType != null ? modelType : "classic");

        // Ensure widget is visible and active
        this.visible = true;
        this.active = true;
    }

    /**
     * Get the X position for model rendering (dynamically calculated)
     * Uses model buttons if available, otherwise widget center
     */
    private int getModelCenterX() {
        // In skin menu: Center of all three model buttons (Auto, Wide, Slim)
        if (autoButton != null && slimButton != null) {
            // Calculate center of the entire button group
            int leftEdge = autoButton.getX();
            int rightEdge = slimButton.getX() + slimButton.getWidth();
            int middleX = (leftEdge + rightEdge) / 2;
            return middleX;
        }
        // Fallback: if only classic/slim buttons exist
        else if (classicButton != null && slimButton != null) {
            int classicCenterX = classicButton.getX() + classicButton.getWidth() / 2;
            int slimCenterX = slimButton.getX() + slimButton.getWidth() / 2;
            int middleX = (classicCenterX + slimCenterX) / 2;
            return middleX;
        }
        // Fallback to widget center if no button reference
        return getX() + getWidth() / 2;
    }

    /**
     * Get the Y position for model rendering (dynamically calculated)
     * Uses Classic/Slim buttons if available, otherwise widget center
     */
    private int getModelCenterY() {
        // In skin menu: Classic button Y coordinate (Classic and Slim are on same Y)
        if (classicButton != null) {
            int buttonCenterY = classicButton.getY() + classicButton.getHeight() / 2;
            return (int)(buttonCenterY + DEFAULT_OFFSET_FROM_BUTTON_Y);
        }
        // Fallback to widget center if no button reference
        return getY() + getHeight() / 2 + 10; // Offset down slightly
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

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
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

        // Keep head at neutral position (mouse tracking disabled)
        if (!isDragging) {
            // Smoothly return head to neutral
            headYaw *= 0.9f;
            headPitch *= 0.9f;
        }

        // Update preview data
        previewData.setYRotation(bodyYaw);
        previewData.setHeadYaw(headYaw);
        previewData.setHeadPitch(headPitch);

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
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isMouseOver((int) mouseX, (int) mouseY)) {
            isDragging = true;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            isDragging = false;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (isDragging && button == 0) {
            float deltaX = (float) (mouseX - lastMouseX);
            float deltaY = (float) (mouseY - lastMouseY);

            // Update body rotation based on horizontal drag
            bodyYaw += deltaX * 0.5f;
            if (bodyYaw >= 360.0f) bodyYaw -= 360.0f;
            if (bodyYaw < 0.0f) bodyYaw += 360.0f;

            // Update head rotation based on drag
            headYaw += deltaX * 0.3f;
            headPitch -= deltaY * 0.3f;

            // Clamp head rotation
            headYaw = Math.max(-45.0f, Math.min(45.0f, headYaw));
            headPitch = Math.max(-30.0f, Math.min(30.0f, headPitch));

            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return true;
        }
        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (isMouseOver((int) mouseX, (int) mouseY)) {
            // Zoom in/out with scroll wheel
            scale += (float) deltaY * 2.0f;
            scale = Math.max(15.0f, Math.min(60.0f, scale)); // Clamp scale
            return true;
        }
        return false;
    }

    /**
     * Check if mouse is over the widget
     */
    private boolean isMouseOver(int mouseX, int mouseY) {
        return mouseX >= getX() && mouseX < getX() + getWidth() &&
               mouseY >= getY() && mouseY < getY() + getHeight();
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
     * Update the cape texture
     */
    public void setCape(@Nullable ResourceLocation capeLocation) {
        previewData.setCapeLocation(capeLocation);
    }

    /**
     * Update the model type
     */
    public void setModelType(String modelType) {
        previewData.setModelType(modelType);
    }

    /**
     * Reset to default rotation
     */
    public void resetRotation() {
        bodyYaw = 20.0f;
        headYaw = 0.0f;
        headPitch = 0.0f;
    }

    /**
     * Set the scale of the model
     */
    public void setScale(float scale) {
        this.scale = Math.max(15.0f, Math.min(60.0f, scale));
    }

    /**
     * Toggle rotation - adds 180 degrees to target rotation
     * Allows spamming for continuous spin
     */
    public void toggleRotation() {
        targetYRotation += 180.0f;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        // Add accessibility narration
        narrationElementOutput.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE,
            Component.literal("Player preview"));
    }
}
