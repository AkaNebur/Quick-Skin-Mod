package com.quickskin.mod.client.rendering;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.quickskin.mod.QuickSkin;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Utility for rendering player models in GUI using vanilla Minecraft rendering
 * Replaces GeckoLib-based rendering with vanilla PlayerModel
 */
@Environment(EnvType.CLIENT)
public class PlayerModelRenderer {

    private static PlayerModel<?>  classicModel;
    private static PlayerModel<?> slimModel;

    /**
     * Initialize models (lazy initialization)
     */
    private static void ensureModelsLoaded() {
        if (classicModel == null) {
            QuickSkin.LOGGER.info("[PlayerModelRenderer] Initializing player models...");
            Minecraft mc = Minecraft.getInstance();
            ModelPart classicRoot = mc.getEntityModels().bakeLayer(ModelLayers.PLAYER);
            classicModel = new PlayerModel<>(classicRoot, false);

            ModelPart slimRoot = mc.getEntityModels().bakeLayer(ModelLayers.PLAYER_SLIM);
            slimModel = new PlayerModel<>(slimRoot, true);
            QuickSkin.LOGGER.info("[PlayerModelRenderer] Models initialized successfully");
        }
    }

    // Debug mode flag
    private static boolean debugMode = false;

    // Cached player entity for rendering (persists even after leaving world)
    private static Player cachedPlayer;

    // Fixed offset for positioning the model to match InventoryScreen rendering
    // The manual rendering uses additional X/Y rotations that shift the model position
    // These offsets compensate for that shift to match the InventoryScreen position
    public static double debugOffsetX = 2.0;
    public static double debugOffsetY = -129.0; // Move up to match InventoryScreen position

    // Interactive debug mode for positioning
    public static boolean debugPositioningMode = false; // Set to true to enable drag-to-position
    private static boolean isDraggingModel = false;
    private static int dragStartX = 0;
    private static int dragStartY = 0;
    private static double dragStartOffsetX = 0;
    private static double dragStartOffsetY = 0;

    /**
     * Render a player model in GUI using vanilla InventoryScreen method
     *
     * @param graphics The GuiGraphics for rendering (contains PoseStack and buffer)
     * @param x Screen X position (center point)
     * @param y Screen Y position (feet position)
     * @param scale Scale factor (typically 30-50 for GUI)
     * @param yRotation Y-axis rotation in degrees (not used with vanilla method)
     * @param playerData Player data containing skin, model type, etc.
     * @param mouseX Mouse X position (for head tracking)
     * @param mouseY Mouse Y position (for head tracking)
     * @param followMouse Whether the head should follow the mouse
     */
    public static void renderPlayerModel(
            GuiGraphics graphics,
            int x,
            int y,
            float scale,
            float yRotation,
            PreviewPlayerData playerData,
            int mouseX,
            int mouseY,
            boolean followMouse
    ) {
        if (playerData.getSkinLocation() == null) {
            return; // No skin to render
        }

        // Get Minecraft instance
        Minecraft mc = Minecraft.getInstance();

        // Cache the player when available for use on title screen
        if (mc.player != null) {
            cachedPlayer = mc.player;
        }

        // Try to use cached player (works even on title screen after playing once)
        Player playerToRender = cachedPlayer;

        // If no cached player exists (fresh game launch), use manual rendering
        if (playerToRender == null) {
            renderPlayerModelManual(graphics, x, y, scale, yRotation, playerData, mouseX, mouseY, followMouse);
            return;
        }

        // Store original rotation
        float originalYRot = playerToRender.getYRot();
        float originalXRot = playerToRender.getXRot();
        float originalYHeadRot = playerToRender.yHeadRot;
        float originalYBodyRot = playerToRender.yBodyRot;

        // Set fixed rotation for preview (facing slightly sideways at 20 degrees - other direction)
        // 180 + 20 = 200 degrees to face towards camera with slight angle
        playerToRender.setYRot(200.0F);
        playerToRender.setXRot(0.0F);
        playerToRender.yHeadRot = 200.0F;
        playerToRender.yBodyRot = 200.0F;

        // Create quaternions for rotation (no mouse tracking)
        // First quaternion: 180-degree flip to orient the model correctly
        Quaternionf quaternionXZ = new Quaternionf().rotationXYZ(0.0F, 0.0F, (float)Math.PI);
        // Second quaternion: empty (no rotation)
        Quaternionf quaternionY = new Quaternionf();

        // Use vanilla InventoryScreen rendering method
        try {
            InventoryScreen.renderEntityInInventory(
                graphics,
                x,
                y,
                (int)scale,
                quaternionXZ,
                quaternionY,
                playerToRender
            );
        } catch (Exception e) {
            // If rendering fails (e.g., entity no longer valid), fall back to manual rendering
            renderPlayerModelManual(graphics, x, y, scale, yRotation, playerData, mouseX, mouseY, followMouse);
        }

        // Restore original rotation after rendering
        playerToRender.setYRot(originalYRot);
        playerToRender.setXRot(originalXRot);
        playerToRender.yHeadRot = originalYHeadRot;
        playerToRender.yBodyRot = originalYBodyRot;
    }

    /**
     * Manually render player model without requiring a player entity
     * Used on title screen where no world/player exists
     * Replicates InventoryScreen.renderEntityInInventory() behavior EXACTLY
     */
    private static void renderPlayerModelManual(
            GuiGraphics graphics,
            int x,
            int y,
            float scale,
            float yRotation,
            PreviewPlayerData playerData,
            int mouseX,
            int mouseY,
            boolean followMouse
    ) {
        ensureModelsLoaded();

        // Select model based on type
        PlayerModel<?> model = playerData.getModelType().equalsIgnoreCase("slim") ? slimModel : classicModel;

        PoseStack poseStack = graphics.pose();
        poseStack.pushPose();

        // Match InventoryScreen.renderEntityInInventory() transformations
        double finalX = (double)x + debugOffsetX;
        double finalY = (double)y + debugOffsetY;
        poseStack.translate(finalX, finalY, 50.0);

        // Apply scale (negative Z flips the model to face forward)
        // Cast to int to match InventoryScreen.renderEntityInInventory() behavior
        float scaleCasted = (float)(int)scale;
        Matrix4f scaleMatrix = (new Matrix4f()).scaling(scaleCasted, scaleCasted, -scaleCasted);
        poseStack.mulPoseMatrix(scaleMatrix);

        // Apply rotations
        Quaternionf quaternionf = (new Quaternionf()).rotateZ((float)Math.PI);
        poseStack.mulPose(quaternionf);

        // Additional rotations needed for manual rendering (not needed when using InventoryScreen)
        poseStack.mulPose(Axis.XP.rotationDegrees(180.0f));
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0f));

        // Apply body rotation (yRotation parameter from PlayerWidget)
        poseStack.mulPose(Axis.YP.rotationDegrees(yRotation));

        // Lighting.setupForEntityInInventory();
        Lighting.setupForEntityInInventory();

        // Setup model pose (arms, legs, head rotation)
        setupModelPose(model, playerData, mouseX, mouseY, followMouse, x, y);

        // Get buffer source
        MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();

        // Render the model with skin texture
        RenderType renderType = RenderType.entityTranslucent(playerData.getSkinLocation());
        var vertexConsumer = bufferSource.getBuffer(renderType);

        // Render model
        model.renderToBuffer(
            poseStack,
            vertexConsumer,
            15728880, // Full brightness (light level) - same as InventoryScreen
            OverlayTexture.NO_OVERLAY,
            1.0f, 1.0f, 1.0f, 1.0f // RGBA
        );

        // Flush buffers - matches guiGraphics.flush() in InventoryScreen
        bufferSource.endBatch();

        poseStack.popPose();

        // Lighting.setupFor3DItems();
        Lighting.setupFor3DItems();
    }

    /**
     * Legacy method for backwards compatibility - forwards to new GuiGraphics version
     */
    @Deprecated
    public static void renderPlayerModel(
            PoseStack poseStack,
            MultiBufferSource buffer,
            int x,
            int y,
            float scale,
            float yRotation,
            PreviewPlayerData playerData,
            int mouseX,
            int mouseY,
            boolean followMouse
    ) {
        // Create GuiGraphics wrapper
        Minecraft mc = Minecraft.getInstance();
        GuiGraphics graphics = new GuiGraphics(mc, buffer instanceof MultiBufferSource.BufferSource ?
            (MultiBufferSource.BufferSource)buffer : mc.renderBuffers().bufferSource());

        // Forward to new method
        renderPlayerModel(graphics, x, y, scale, yRotation, playerData, mouseX, mouseY, followMouse);
    }

    /**
     * Setup model pose (arm positions, head rotation, etc.)
     */
    private static void setupModelPose(
            PlayerModel<?> model,
            PreviewPlayerData playerData,
            int mouseX,
            int mouseY,
            boolean followMouse,
            int modelCenterX,
            int modelCenterY
    ) {
        // Reset all rotations
        model.young = false;
        model.crouching = false;
        model.riding = false;

        // Set default idle pose
        model.attackTime = 0.0f;

        // Arms at sides (idle pose)
        model.leftArm.xRot = 0.0f;
        model.leftArm.yRot = 0.0f;
        model.leftArm.zRot = 0.0f;

        model.rightArm.xRot = 0.0f;
        model.rightArm.yRot = 0.0f;
        model.rightArm.zRot = 0.0f;

        // Legs at default position
        model.leftLeg.xRot = 0.0f;
        model.leftLeg.yRot = 0.0f;
        model.leftLeg.zRot = 0.0f;

        model.rightLeg.xRot = 0.0f;
        model.rightLeg.yRot = 0.0f;
        model.rightLeg.zRot = 0.0f;

        // Head rotation
        if (followMouse) {
            // Calculate head rotation based on mouse position
            float deltaX = mouseX - modelCenterX;
            float deltaY = mouseY - modelCenterY;

            // Convert to angles (limited range for natural look)
            float headYaw = Math.max(-45.0f, Math.min(45.0f, deltaX * 0.1f));
            float headPitch = Math.max(-30.0f, Math.min(30.0f, deltaY * 0.05f));

            model.head.yRot = (float) Math.toRadians(headYaw);
            model.head.xRot = (float) Math.toRadians(headPitch);
        } else {
            // Use stored head rotation
            model.head.yRot = (float) Math.toRadians(playerData.getHeadYaw());
            model.head.xRot = (float) Math.toRadians(playerData.getHeadPitch());
        }

        // Hat layer (outer layer of head) follows head rotation
        model.hat.copyFrom(model.head);

        // Setup arm rendering
        model.leftSleeve.copyFrom(model.leftArm);
        model.rightSleeve.copyFrom(model.rightArm);
        model.leftPants.copyFrom(model.leftLeg);
        model.rightPants.copyFrom(model.rightLeg);
        model.jacket.copyFrom(model.body);
    }

    /**
     * Render a debug cube at the chest position to show rotation center
     */
    private static void renderDebugCube(PoseStack poseStack, MultiBufferSource buffer, PlayerModel<?> model) {
        poseStack.pushPose();

        // Get chest position from the model's body part
        // The body part is positioned at the chest area
        ModelPart body = model.body;

        // Translate to chest center (body origin is at chest)
        poseStack.translate(0.0, -0.7, 0.0); // Move to chest height

        // Scale the cube to be visible
        poseStack.scale(0.2f, 0.2f, 0.2f);

        // Render a bright colored cube
        RenderType renderType = RenderType.lines();
        var vertexConsumer = buffer.getBuffer(renderType);

        // Draw cube wireframe (bright cyan/magenta for visibility)
        float size = 1.0f;
        Matrix4f matrix = poseStack.last().pose();

        // Draw all 12 edges of a cube
        // Bottom face
        addLine(vertexConsumer, matrix, -size, -size, -size, size, -size, -size, 0, 255, 255); // Cyan
        addLine(vertexConsumer, matrix, size, -size, -size, size, -size, size, 0, 255, 255);
        addLine(vertexConsumer, matrix, size, -size, size, -size, -size, size, 0, 255, 255);
        addLine(vertexConsumer, matrix, -size, -size, size, -size, -size, -size, 0, 255, 255);

        // Top face
        addLine(vertexConsumer, matrix, -size, size, -size, size, size, -size, 255, 0, 255); // Magenta
        addLine(vertexConsumer, matrix, size, size, -size, size, size, size, 255, 0, 255);
        addLine(vertexConsumer, matrix, size, size, size, -size, size, size, 255, 0, 255);
        addLine(vertexConsumer, matrix, -size, size, size, -size, size, -size, 255, 0, 255);

        // Vertical edges
        addLine(vertexConsumer, matrix, -size, -size, -size, -size, size, -size, 255, 255, 0); // Yellow
        addLine(vertexConsumer, matrix, size, -size, -size, size, size, -size, 255, 255, 0);
        addLine(vertexConsumer, matrix, size, -size, size, size, size, size, 255, 255, 0);
        addLine(vertexConsumer, matrix, -size, -size, size, -size, size, size, 255, 255, 0);

        poseStack.popPose();
    }

    /**
     * Helper method to add a colored line to the vertex consumer
     */
    private static void addLine(com.mojang.blaze3d.vertex.VertexConsumer consumer, Matrix4f matrix,
                                float x1, float y1, float z1, float x2, float y2, float z2,
                                int r, int g, int b) {
        consumer.vertex(matrix, x1, y1, z1).color(r, g, b, 255).normal(1, 0, 0).endVertex();
        consumer.vertex(matrix, x2, y2, z2).color(r, g, b, 255).normal(1, 0, 0).endVertex();
    }

    /**
     * Setup lighting for the model based on rotation
     * Adds dynamic brightness that changes with rotation angle
     */
    private static void setupLighting(float yRotation) {
        // Normalize rotation to 0-360
        float normalizedRotation = yRotation % 360.0f;
        if (normalizedRotation < 0) {
            normalizedRotation += 360.0f;
        }

        // Calculate brightness based on rotation (front is brightest)
        // Front (0°): 1.425f
        // Side (90°/270°): 1.2f
        // Back (180°): 1.3f
        float brightness;
        if (normalizedRotation < 90.0f) {
            // Front to side
            brightness = 1.425f - (normalizedRotation / 90.0f) * 0.225f;
        } else if (normalizedRotation < 180.0f) {
            // Side to back
            brightness = 1.2f + ((normalizedRotation - 90.0f) / 90.0f) * 0.1f;
        } else if (normalizedRotation < 270.0f) {
            // Back to side
            brightness = 1.3f - ((normalizedRotation - 180.0f) / 90.0f) * 0.1f;
        } else {
            // Side to front
            brightness = 1.2f + ((normalizedRotation - 270.0f) / 90.0f) * 0.225f;
        }

        // Setup shader lights with custom light vector
        // Standard light direction (from top-left-front)
        org.joml.Vector3f lightDirection = new org.joml.Vector3f(0.2f, 1.0f, -0.7f).normalize();

        RenderSystem.setShaderLights(
                new org.joml.Vector3f(lightDirection).mul(brightness),
                new org.joml.Vector3f(lightDirection).mul(brightness * 0.5f)
        );
    }

    /**
     * Render a simplified player model (just for testing)
     * Uses default Steve skin
     */
    public static void renderDefaultPlayer(
            PoseStack poseStack,
            MultiBufferSource buffer,
            int x,
            int y,
            float scale,
            float yRotation
    ) {
        PreviewPlayerData defaultData = new PreviewPlayerData();
        defaultData.setSkinLocation(new ResourceLocation("textures/entity/steve.png"));
        defaultData.setModelType("classic");

        renderPlayerModel(poseStack, buffer, x, y, scale, yRotation, defaultData, 0, 0, false);
    }

    /**
     * Handle mouse press for debug positioning mode
     * Call this from your screen's mouseClicked method
     */
    public static boolean handleDebugMousePressed(int mouseX, int mouseY, int button) {
        if (!debugPositioningMode || button != 0) {
            return false;
        }

        // Start dragging
        isDraggingModel = true;
        dragStartX = mouseX;
        dragStartY = mouseY;
        dragStartOffsetX = debugOffsetX;
        dragStartOffsetY = debugOffsetY;
        return true;
    }

    /**
     * Handle mouse drag for debug positioning mode
     * Call this from your screen's mouseDragged method
     */
    public static boolean handleDebugMouseDragged(int mouseX, int mouseY, int button) {
        if (!debugPositioningMode || !isDraggingModel || button != 0) {
            return false;
        }

        // Update offsets based on drag distance
        int deltaX = mouseX - dragStartX;
        int deltaY = mouseY - dragStartY;

        debugOffsetX = dragStartOffsetX + deltaX;
        debugOffsetY = dragStartOffsetY + deltaY;

        return true;
    }

    /**
     * Handle mouse release for debug positioning mode
     * Call this from your screen's mouseReleased method
     */
    public static boolean handleDebugMouseReleased(int mouseX, int mouseY, int button) {
        if (!debugPositioningMode || !isDraggingModel || button != 0) {
            return false;
        }

        isDraggingModel = false;

        // Log the final offsets
        QuickSkin.LOGGER.info("========================================");
        QuickSkin.LOGGER.info("[Debug Positioning] Model positioned!");
        QuickSkin.LOGGER.info("[Debug Positioning] Set these values in PlayerModelRenderer:");
        QuickSkin.LOGGER.info("[Debug Positioning]   debugOffsetX = {};", debugOffsetX);
        QuickSkin.LOGGER.info("[Debug Positioning]   debugOffsetY = {};", debugOffsetY);
        QuickSkin.LOGGER.info("========================================");

        return true;
    }
}
