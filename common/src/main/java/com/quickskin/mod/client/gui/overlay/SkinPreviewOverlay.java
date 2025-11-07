package com.quickskin.mod.client.gui.overlay;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.quickskin.mod.client.rendering.PlayerModelRenderer;
import com.quickskin.mod.client.rendering.PreviewPlayerData;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;

/**
 * HUD overlay showing a preview of your current skin
 * Displays in the corner of the screen (configurable position)
 */
@Environment(EnvType.CLIENT)
public class SkinPreviewOverlay {

    private static final int PREVIEW_SIZE = 50;
    private static final int PADDING = 10;

    // Overlay position (default: bottom-right)
    private static OverlayPosition position = OverlayPosition.BOTTOM_RIGHT;

    // Rotation animation
    private static float rotationAngle = 0f;
    private static final float ROTATION_SPEED = 0.5f; // degrees per frame

    public enum OverlayPosition {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    /**
     * Render the skin preview overlay
     */
    public static void render(GuiGraphics guiGraphics, float tickDelta) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        if (player == null) {
            return;
        }

        // Get current skin and model type
        ResourceLocation skinLocation = player.getSkinTextureLocation();
        String modelType = "classic";

        // Check if there's an active custom skin
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        if (!config.activeSkinHash.isEmpty()) {
            // Use custom skin
            com.quickskin.mod.client.services.LocalAssetManager assetManager =
                com.quickskin.mod.client.services.LocalAssetManager.getInstance();
            com.quickskin.mod.common.data.AssetMetadata metadata = assetManager.getMetadata(config.activeSkinHash);

            if (metadata != null) {
                skinLocation = assetManager.getTextureLocation(config.activeSkinHash,
                    com.quickskin.mod.common.data.TextureQuality.FULL);

                // Get model type preference for this skin (respecting auto mode)
                String skinModelType = assetManager.getSkinModelPreference(config.activeSkinHash);
                if ("auto".equals(skinModelType)) {
                    modelType = metadata.skinModel();
                } else {
                    modelType = skinModelType;
                }
            }
        } else {
            // Use vanilla skin
            modelType = player.getModelName(); // "default" or "slim"
            // Convert to our model type format
            if ("default".equals(modelType)) {
                modelType = "classic";
            }
        }

        // Calculate position based on overlay position setting
        int x = calculateX(mc.getWindow().getGuiScaledWidth());
        int y = calculateY(mc.getWindow().getGuiScaledHeight());

        // Draw background
        int bgColor = 0x80000000; // Semi-transparent black
        guiGraphics.fill(x, y, x + PREVIEW_SIZE, y + PREVIEW_SIZE, bgColor);

        // Draw border
        int borderColor = 0xFF5A5A5A;
        guiGraphics.fill(x, y, x + PREVIEW_SIZE, y + 1, borderColor); // Top
        guiGraphics.fill(x, y + PREVIEW_SIZE - 1, x + PREVIEW_SIZE, y + PREVIEW_SIZE, borderColor); // Bottom
        guiGraphics.fill(x, y, x + 1, y + PREVIEW_SIZE, borderColor); // Left
        guiGraphics.fill(x + PREVIEW_SIZE - 1, y, x + PREVIEW_SIZE, y + PREVIEW_SIZE, borderColor); // Right

        // Update rotation
        rotationAngle += ROTATION_SPEED;
        if (rotationAngle >= 360f) {
            rotationAngle -= 360f;
        }

        // Render player preview
        renderPlayerPreview(
            guiGraphics,
            x + PREVIEW_SIZE / 2,
            y + (int)(PREVIEW_SIZE * 0.85f),
            PREVIEW_SIZE / 3,
            skinLocation,
            null, // No cape for HUD preview
            modelType,
            rotationAngle
        );
    }

    /**
     * Calculate X position based on overlay position
     */
    private static int calculateX(int screenWidth) {
        return switch (position) {
            case TOP_LEFT, BOTTOM_LEFT -> PADDING;
            case TOP_RIGHT, BOTTOM_RIGHT -> screenWidth - PREVIEW_SIZE - PADDING;
        };
    }

    /**
     * Calculate Y position based on overlay position
     */
    private static int calculateY(int screenHeight) {
        return switch (position) {
            case TOP_LEFT, TOP_RIGHT -> PADDING;
            case BOTTOM_LEFT, BOTTOM_RIGHT -> screenHeight - PREVIEW_SIZE - PADDING;
        };
    }

    /**
     * Render the player model preview
     */
    private static void renderPlayerPreview(
        GuiGraphics graphics,
        int x,
        int y,
        int scale,
        ResourceLocation skinTexture,
        ResourceLocation capeTexture,
        String modelType,
        float rotation
    ) {
        // Create preview data
        PreviewPlayerData previewData = new PreviewPlayerData();
        previewData.setSkinLocation(skinTexture);
        previewData.setCapeLocation(capeTexture);
        previewData.setModelType(modelType);

        // Save graphics state
        PoseStack poseStack = graphics.pose();
        poseStack.pushPose();

        try {
            // Render the player
            PlayerModelRenderer.renderPlayerModel(
                graphics,
                x,
                y,
                scale,
                rotation,
                previewData,
                0, // mouseX (no mouse tracking in HUD)
                0, // mouseY
                false // followMouse
            );
        } finally {
            poseStack.popPose();
        }
    }

    /**
     * Set the overlay position
     */
    public static void setPosition(OverlayPosition newPosition) {
        position = newPosition;
    }

    /**
     * Get the current overlay position
     */
    public static OverlayPosition getPosition() {
        return position;
    }
}
