package com.quickskin.mod.client.gui.overlay;

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

    // Cached fields for performance
    private static ResourceLocation cachedSkinLocation = null;
    private static String cachedModelType = "classic";
    private static String lastCheckedSkinHash = null; // Use null to force initial update

    public enum OverlayPosition {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    /**
     * Render the skin preview overlay
     */
    @SuppressWarnings("unused")
    public static void render(GuiGraphics guiGraphics, float tickDelta) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;

        if (player == null) {
            return;
        }

        // Update-on-change logic for huge performance gain
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        String activeSkinHash = config.activeSkinHash;

        // Use .equals() for string comparison. lastCheckedSkinHash can be null initially.
        boolean needsUpdate = (lastCheckedSkinHash == null) || !lastCheckedSkinHash.equals(activeSkinHash);

        if (needsUpdate) {
            lastCheckedSkinHash = activeSkinHash; // Update the hash we're tracking

            if (!activeSkinHash.isEmpty()) {
                // Use custom skin
                com.quickskin.mod.client.services.LocalAssetManager assetManager =
                    com.quickskin.mod.client.services.LocalAssetManager.getInstance();
                com.quickskin.mod.common.data.AssetMetadata metadata = assetManager.getMetadata(activeSkinHash);

                if (metadata != null) {
                    cachedSkinLocation = assetManager.getTextureLocation(activeSkinHash,
                        com.quickskin.mod.common.data.TextureQuality.FULL);

                    // Get model type preference for this skin, using cached data from metadata
                    String skinModelPref = assetManager.getSkinModelPreference(activeSkinHash);
                    if ("auto".equals(skinModelPref)) {
                        cachedModelType = metadata.skinModel(); // Use cached model
                    } else {
                        cachedModelType = skinModelPref;
                    }
                }
            } else {
                // Use vanilla skin
                cachedSkinLocation = player.getSkinTextureLocation();
                cachedModelType = player.getModelName(); // "default" or "slim"
                if ("default".equals(cachedModelType)) {
                    cachedModelType = "classic";
                }
            }
        }

        // Fallback if cached skin is somehow still null
        if (cachedSkinLocation == null) {
            cachedSkinLocation = player.getSkinTextureLocation();
            cachedModelType = "classic";
        }

        // Calculate position based on overlay position setting
        int x = calculateX(mc.getWindow().getGuiScaledWidth());
        int y = calculateY(mc.getWindow().getGuiScaledHeight());

        // Update rotation only if enabled in config
        if (config.enableRotatingPreviewInOverlay) {
            rotationAngle += ROTATION_SPEED;
            if (rotationAngle >= 360f) {
                rotationAngle -= 360f;
            }
        } else {
            // Set a fixed, nice-looking angle when rotation is disabled
            rotationAngle = 20f;
        }

        // Render player preview using cached data
        renderPlayerPreview(
            guiGraphics,
            x + PREVIEW_SIZE / 2,
            y + (int)(PREVIEW_SIZE * 0.85f),
            cachedSkinLocation,  // Use cached value
            cachedModelType,     // Use cached value
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
        ResourceLocation skinTexture,
        String modelType,
        float rotation
    ) {
        int scale = PREVIEW_SIZE / 3;
        // Create preview data
        PreviewPlayerData previewData = new PreviewPlayerData();
        previewData.setSkinLocation(skinTexture);
        previewData.setCapeLocation(null); // No cape for HUD preview
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
