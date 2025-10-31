package com.quickskin.mod.client.gui.widget;

import com.quickskin.mod.client.rendering.PlayerModelRenderer;
import com.quickskin.mod.client.rendering.PreviewPlayerData;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
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
    private float bodyYaw = 20.0f;
    private float headYaw = 0.0f;
    private float headPitch = 0.0f;

    // Animation state
    private long lastUpdateTime = System.currentTimeMillis();
    private float autoRotationAngle = 0.0f;
    private boolean autoRotate = true;

    // Display settings
    private float scale = 30.0f;
    private int modelCenterX;
    private int modelCenterY;

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

        this.modelCenterX = x + width / 2;
        this.modelCenterY = y + height / 2 + 10; // Offset down slightly
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Update auto-rotation if enabled
        if (autoRotate && !isDragging) {
            long currentTime = System.currentTimeMillis();
            float deltaTime = (currentTime - lastUpdateTime) / 1000.0f;
            lastUpdateTime = currentTime;

            autoRotationAngle += 30.0f * deltaTime; // 30 degrees per second
            if (autoRotationAngle >= 360.0f) {
                autoRotationAngle -= 360.0f;
            }
            bodyYaw = autoRotationAngle;
        }

        // Update head tracking to follow mouse
        if (!isDragging && isMouseOver(mouseX, mouseY)) {
            float relativeMouseX = mouseX - modelCenterX;
            float relativeMouseY = mouseY - modelCenterY;

            headYaw = Math.max(-45.0f, Math.min(45.0f, relativeMouseX * 0.15f));
            headPitch = Math.max(-30.0f, Math.min(30.0f, -relativeMouseY * 0.1f));
        } else if (!isDragging) {
            // Smoothly return head to neutral
            headYaw *= 0.9f;
            headPitch *= 0.9f;
        }

        // Update preview data
        previewData.setYRotation(bodyYaw);
        previewData.setHeadYaw(headYaw);
        previewData.setHeadPitch(headPitch);

        // Render the player model
        // GuiGraphics provides pose() for PoseStack and bufferSource() for MultiBufferSource
        PlayerModelRenderer.renderPlayerModel(
            graphics.pose(),
            graphics.bufferSource(),
            modelCenterX,
            modelCenterY,
            scale,
            bodyYaw,
            previewData,
            mouseX,
            mouseY,
            false
        );

        // Draw border if hovered
        if (isMouseOver(mouseX, mouseY)) {
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + 1, 0x60FFFFFF); // Top
            graphics.fill(getX(), getY() + getHeight() - 1, getX() + getWidth(), getY() + getHeight(), 0x60FFFFFF); // Bottom
            graphics.fill(getX(), getY(), getX() + 1, getY() + getHeight(), 0x60FFFFFF); // Left
            graphics.fill(getX() + getWidth() - 1, getY(), getX() + getWidth(), getY() + getHeight(), 0x60FFFFFF); // Right
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isMouseOver((int) mouseX, (int) mouseY)) {
            isDragging = true;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            autoRotate = false; // Stop auto-rotation when user interacts
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
     * Enable or disable auto-rotation
     */
    public void setAutoRotate(boolean autoRotate) {
        this.autoRotate = autoRotate;
        if (autoRotate) {
            lastUpdateTime = System.currentTimeMillis();
        }
    }

    /**
     * Reset to default rotation
     */
    public void resetRotation() {
        bodyYaw = 20.0f;
        headYaw = 0.0f;
        headPitch = 0.0f;
        autoRotationAngle = 20.0f;
        autoRotate = true;
        lastUpdateTime = System.currentTimeMillis();
    }

    /**
     * Set the scale of the model
     */
    public void setScale(float scale) {
        this.scale = Math.max(15.0f, Math.min(60.0f, scale));
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        // Add accessibility narration
        narrationElementOutput.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE,
            Component.literal("Player preview"));
    }
}
