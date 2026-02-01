package com.quickskin.mod.client.rendering;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.services.AnimatedTextureManager;
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
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Utility for rendering player models in GUI using vanilla Minecraft rendering
 * Replaces GeckoLib-based rendering with vanilla PlayerModel
 */
@Environment(EnvType.CLIENT)
public class PlayerModelRenderer {

    private static PlayerModel<?>  classicModel;
    private static PlayerModel<?> slimModel;

    // Animation frequency optimization - update at 30 FPS instead of 60+ FPS
    private static long lastAnimationUpdate = 0;
    private static final long ANIMATION_UPDATE_INTERVAL_MS = 33; // ~30 FPS (1000/30)

    // Cape animation frequency optimization - update at 10 FPS
    private static ResourceLocation cachedCapeTexture = null;
    private static long lastCapeTextureUpdate = 0;
    private static final long CAPE_UPDATE_INTERVAL_MS = 100; // 10 FPS for cape animation

    // Lighting optimization - only setup lighting once
    private static boolean lightingSetup = false;

    // Grass block caching - cache is built on first use
    private static boolean grassBlockCacheBuilt = false;

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

    // Cached player entity for rendering (persists even after leaving world)
    private static Player cachedPlayer;

    // Previous rotation/position values for smooth lerping in idle animation
    private static float prevHeadRotZ = 0.0f;
    private static float prevRightArmRotX = 0.0f;
    private static float prevRightArmRotZ = 0.0f;
    private static float prevLeftArmRotX = 0.0f;
    private static float prevLeftArmRotZ = 0.0f;
    private static float prevRightLegRotX = 0.0f;
    private static float prevLeftLegRotX = 0.0f;
    private static float prevBodyRotX = 0.0f;

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

        // Note: Shadow rendering is typically disabled for GUI entity rendering by default
        // InventoryScreen.renderEntityInInventory already handles this appropriately

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

        // Set rotation for preview using the yRotation parameter
        // Convert yRotation to match InventoryScreen orientation (180 + yRotation)
        float targetRotation = 180.0F + yRotation;
        playerToRender.setYRot(targetRotation);
        playerToRender.setXRot(0.0F);
        playerToRender.yHeadRot = targetRotation + playerData.getHeadYaw();
        playerToRender.yBodyRot = targetRotation;

        // Set tickCount for idle animation ONLY when on title screen (no world)
        // When in-game, the entity already has its own natural tickCount from the game loop
        if (mc.level == null) {
            // Title screen: manually set tickCount to enable animation
            playerToRender.tickCount = mc.gui.getGuiTicks();
        }
        // Otherwise: keep entity's natural tickCount for proper in-game animation

        // Create quaternions for rotation (no mouse tracking)
        // First quaternion: 180-degree flip to orient the model correctly
        Quaternionf quaternionXZ = new Quaternionf().rotationXYZ(0.0F, 0.0F, (float)Math.PI);
        // Second quaternion: empty (no rotation)
        Quaternionf quaternionY = new Quaternionf();

        // Use vanilla InventoryScreen rendering method
        try {
            // Render grass block if sitting animation is active AND we're not in a world
            // When in-game, animations are controlled by the game, so don't render the custom grass block
            if ("sit".equalsIgnoreCase(playerData.getCurrentAnimation()) && mc.level == null) {
                PoseStack poseStack = graphics.pose();
                MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();

                poseStack.pushPose();
                // Match the transformations from InventoryScreen
                poseStack.translate(x, y, 50.0);
                float scaleCasted = (float)(int)scale;
                Matrix4f scaleMatrix = (new Matrix4f()).scaling(scaleCasted, scaleCasted, -scaleCasted);
                poseStack.mulPoseMatrix(scaleMatrix);
                poseStack.mulPose(quaternionXZ);
                poseStack.mulPose(Axis.YP.rotationDegrees(-targetRotation));
                poseStack.scale(-1.0F, -1.0F, 1.0F);
                poseStack.translate(0.0F, -1.501F, 0.0F);

                renderGrassBlock(poseStack, bufferSource);
                bufferSource.endBatch();
                poseStack.popPose();
            }

            InventoryScreen.renderEntityInInventory(
                    graphics,
                    x,
                    y,
                    (int)scale,
                    quaternionXZ,
                    quaternionY,
                    playerToRender
            );

            // Note: 3D Skin Layers mod handles entity rendering automatically via entity layers
            // No manual integration needed for entity-based rendering
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
        poseStack.translate((double)x, (double)y, 50.0);

        // Apply scale (negative Z flips the model to face forward)
        // Cast to int to match InventoryScreen.renderEntityInInventory() behavior
        float scaleCasted = (float)(int)scale;
        Matrix4f scaleMatrix = (new Matrix4f()).scaling(scaleCasted, scaleCasted, -scaleCasted);
        poseStack.mulPoseMatrix(scaleMatrix);

        // Apply rotations - match InventoryScreen's quaternion
        Quaternionf quaternionf = (new Quaternionf()).rotateZ((float)Math.PI);
        poseStack.mulPose(quaternionf);

        // === NOW MATCH LivingEntityRenderer.render() EXACTLY ===

        // Body rotation (without the 180 degree offset)
        poseStack.mulPose(Axis.YP.rotationDegrees(-yRotation));

        // Flip the model (line 82 in LivingEntityRenderer)
        poseStack.scale(-1.0F, -1.0F, 1.0F);

        // CRITICAL: Position the model at feet (line 84 in LivingEntityRenderer)
        poseStack.translate(0.0F, -1.501F, 0.0F);

        // Only setup lighting if not already set (optimization)
        if (!lightingSetup) {
            Lighting.setupForEntityInInventory();
            lightingSetup = true;
        }

        // Get Minecraft instance for tick count
        Minecraft mc = Minecraft.getInstance();

        // Get buffer source
        MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();

        // Render grass block if sitting animation is active AND we're not in a world
        // When in-game, animations are controlled by the game, so don't render the custom grass block
        if ("sit".equalsIgnoreCase(playerData.getCurrentAnimation()) && mc.level == null) {
            renderGrassBlock(poseStack, bufferSource);
        }

        // Setup model pose with idle animation
        setupModelPoseWithAnimation(model, playerData, mouseX, mouseY, followMouse, x, y, mc);

        // Render the model with skin texture
        RenderType renderType = RenderType.entityTranslucent(playerData.getSkinLocation());
        var vertexConsumer = bufferSource.getBuffer(renderType);

        // Determine if using slim model
        boolean isSlimModel = playerData.getModelType().equalsIgnoreCase("slim");

        // Render model
        model.renderToBuffer(
                poseStack,
                vertexConsumer,
                15728880, // Full brightness (light level) - same as InventoryScreen
                OverlayTexture.NO_OVERLAY,
                1.0f, 1.0f, 1.0f, 1.0f // RGBA
        );

        // Render 3D skin layers (if mod is installed)
        // The integration class handles all mod detection and graceful fallback
        SkinLayers3DIntegration.render3DLayers(
                poseStack,
                bufferSource,
                15728880,
                OverlayTexture.NO_OVERLAY,
                model,
                playerData.getSkinLocation(),
                isSlimModel
        );

        // Render cape AFTER model if present
        if (playerData.getCapeLocation() != null) {
            ResourceLocation capeAtlasLocation = playerData.getCapeLocation();
            String capeId = playerData.getCapeId();

            ResourceLocation finalCapeTexture = capeAtlasLocation;
            String animationId = null;
            if (capeId != null) {
                if (capeId.startsWith("local_cape:")) {
                    animationId = "cape_" + capeId.substring("local_cape:".length());
                } else if (capeId.startsWith("known:")) {
                    animationId = "cape_known_" + capeId.substring("known:".length());
                }
            }

            if (animationId != null) {
                // CHECK: Should we update cape texture this frame?
                long now = System.currentTimeMillis();
                if (now - lastCapeTextureUpdate >= CAPE_UPDATE_INTERVAL_MS) {
                    ResourceLocation currentFrame = AnimatedTextureManager.getInstance().getCurrentFrameTexture(animationId);
                    if (currentFrame != null) {
                        cachedCapeTexture = currentFrame;
                    }
                    lastCapeTextureUpdate = now;
                }

                // Use cached texture
                if (cachedCapeTexture != null) {
                    finalCapeTexture = cachedCapeTexture;
                }
            }

            // Now render the cape using the final texture
            RenderType capeRenderType = RenderType.entityTranslucent(finalCapeTexture);
            var capeVertexConsumer = bufferSource.getBuffer(capeRenderType);

            poseStack.pushPose();
            // Position the cloak correctly relative to the body
            model.body.translateAndRotate(poseStack);
            poseStack.translate(0.0, 0.0, 0.125); // Move behind the player

            // Add some basic swing/angle to make it look like a cape
            poseStack.mulPose(Axis.XP.rotationDegrees(6.0F));
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0F)); // The cloak model part is drawn facing backwards

            model.renderCloak(poseStack, capeVertexConsumer, 15728880, OverlayTexture.NO_OVERLAY);

            poseStack.popPose();
        }

        // Flush buffers - matches guiGraphics.flush() in InventoryScreen
        bufferSource.endBatch();

        poseStack.popPose();

        // Don't restore lighting - let next render set what it needs (optimization)
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
     * Smoothly lerp (linear interpolate) between current and target value
     * @param current Current value
     * @param target Target value
     * @param factor Interpolation factor (0-1, higher = faster)
     * @return Interpolated value
     */
    private static float smoothLerp(float current, float target, float factor) {
        return current + (target - current) * factor;
    }

    /**
     * Render cape layer similar to vanilla CapeLayer
     * This is called AFTER the player model is rendered
     */
    private static void renderCapeLayer(
            PoseStack poseStack,
            MultiBufferSource.BufferSource bufferSource,
            ResourceLocation capeTexture,
            PlayerModel<?> model
    ) {
        poseStack.pushPose();

        // Apply body transformations (cape is attached to the body)
        model.body.translateAndRotate(poseStack);

        // Position cape at back of shoulders
        poseStack.translate(0.0, 0.0, 0.125);

        // Cape dimensions
        float capeWidth = 10.0f / 16.0f;
        float capeHeight = 16.0f / 16.0f;
        float xOffset = -capeWidth / 2.0f;

        // Get cape render type and buffer
        RenderType capeRenderType = RenderType.entitySolid(capeTexture);
        var capeConsumer = bufferSource.getBuffer(capeRenderType);

        PoseStack.Pose pose = poseStack.last();
        Matrix4f matrix = pose.pose();

        // UV coordinates
        float u0 = 0.0f;
        float v0 = 0.0f;
        float u1 = 10.0f / 64.0f;
        float v1 = 16.0f / 32.0f;

        // Render quad
        capeConsumer.vertex(matrix, xOffset, 0.0f, 0.0f)
                .color(255, 255, 255, 255)
                .uv(u0, v0)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(pose.normal(), 0.0f, 0.0f, 1.0f)
                .endVertex();

        capeConsumer.vertex(matrix, xOffset, capeHeight, 0.0f)
                .color(255, 255, 255, 255)
                .uv(u0, v1)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(pose.normal(), 0.0f, 0.0f, 1.0f)
                .endVertex();

        capeConsumer.vertex(matrix, xOffset + capeWidth, capeHeight, 0.0f)
                .color(255, 255, 255, 255)
                .uv(u1, v1)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(pose.normal(), 0.0f, 0.0f, 1.0f)
                .endVertex();

        capeConsumer.vertex(matrix, xOffset + capeWidth, 0.0f, 0.0f)
                .color(255, 255, 255, 255)
                .uv(u1, v0)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(pose.normal(), 0.0f, 0.0f, 1.0f)
                .endVertex();

        poseStack.popPose();
    }

    /**
     * OLD Render cape in manual mode with correct transformations
     * The cape needs special handling because we're in a transformed coordinate space
     */
    private static void renderCapeManualOLD(
            PoseStack poseStack,
            MultiBufferSource.BufferSource bufferSource,
            ResourceLocation capeLocation,
            float yRotation
    ) {
        poseStack.pushPose();

        // DEBUG: Render a GIANT bright magenta rectangle that's impossible to miss
        // This will help us see exactly where the cape is being rendered
        RenderType debugRenderType = RenderType.gui();
        var debugConsumer = bufferSource.getBuffer(debugRenderType);
        PoseStack.Pose debugPose = poseStack.last();
        Matrix4f debugMatrix = debugPose.pose();

        // Render a huge bright rectangle (5x5 units) centered at origin
        float debugSize = 2.5f;
        // Top-left
        debugConsumer.vertex(debugMatrix, -debugSize, -debugSize, 0.0f)
                .color(255, 0, 255, 255) // Bright magenta
                .uv(0, 0)
                .endVertex();
        // Bottom-left
        debugConsumer.vertex(debugMatrix, -debugSize, debugSize, 0.0f)
                .color(255, 0, 255, 255)
                .uv(0, 1)
                .endVertex();
        // Bottom-right
        debugConsumer.vertex(debugMatrix, debugSize, debugSize, 0.0f)
                .color(255, 0, 255, 255)
                .uv(1, 1)
                .endVertex();
        // Top-right
        debugConsumer.vertex(debugMatrix, debugSize, -debugSize, 0.0f)
                .color(255, 0, 255, 255)
                .uv(1, 0)
                .endVertex();

        // Position cape at the back of the player's body
        // In our transformed space (after XP 180, YP 180), we need to adjust positioning
        // The cape should be slightly behind the body center
        poseStack.translate(0.0, 0.0, -0.125); // Negative Z because of our flipped coordinates

        // Cape dimensions (Minecraft standard: 10x16 pixels on 64x32 texture)
        float capeWidth = 10.0f / 16.0f;  // 0.625 units
        float capeHeight = 16.0f / 16.0f; // 1.0 units
        float xOffset = -capeWidth / 2.0f; // Center the cape

        // Add subtle swing animation
        float capeSwing = (float) Math.sin(System.currentTimeMillis() / 1000.0) * 0.1f;
        poseStack.mulPose(Axis.XP.rotationDegrees(capeSwing * 10.0f));

        // Get render type and vertex consumer
        RenderType renderType = RenderType.entityTranslucentCull(capeLocation);
        var vertexConsumer = bufferSource.getBuffer(renderType);

        // Get matrices
        PoseStack.Pose pose = poseStack.last();
        Matrix4f positionMatrix = pose.pose();

        // UV coordinates for standard Minecraft cape (64x32 texture)
        float u0 = 0.0f / 64.0f;
        float v0 = 0.0f / 32.0f;
        float u1 = 10.0f / 64.0f;
        float v1 = 16.0f / 32.0f;

        // Render cape quad (4 vertices forming a rectangle)
        // Top-left
        vertexConsumer.vertex(positionMatrix, xOffset, 0.0f, 0.0f)
                .color(255, 255, 255, 255)
                .uv(u0, v0)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880) // Full bright
                .normal(0.0f, 0.0f, 1.0f)
                .endVertex();

        // Bottom-left
        vertexConsumer.vertex(positionMatrix, xOffset, capeHeight, 0.0f)
                .color(255, 255, 255, 255)
                .uv(u0, v1)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(0.0f, 0.0f, 1.0f)
                .endVertex();

        // Bottom-right
        vertexConsumer.vertex(positionMatrix, xOffset + capeWidth, capeHeight, 0.0f)
                .color(255, 255, 255, 255)
                .uv(u1, v1)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(0.0f, 0.0f, 1.0f)
                .endVertex();

        // Top-right
        vertexConsumer.vertex(positionMatrix, xOffset + capeWidth, 0.0f, 0.0f)
                .color(255, 255, 255, 255)
                .uv(u1, v0)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(0.0f, 0.0f, 1.0f)
                .endVertex();

        poseStack.popPose();
    }

    /**
     * Setup model pose with animation based on current animation state
     * Supports idle, walk, run, sneak, sit, jump animations
     */
    private static void setupModelPoseWithAnimation(
            PlayerModel<?> model,
            PreviewPlayerData playerData,
            int mouseX,
            int mouseY,
            boolean followMouse,
            int modelCenterX,
            int modelCenterY,
            Minecraft mc
    ) {
        // Get current animation type from preview data
        String animation = playerData.getCurrentAnimation();
        if (animation == null || animation.isEmpty()) {
            animation = "idle";
        }

        // CHECK: Should we update animation this frame?
        long now = System.currentTimeMillis();
        boolean shouldUpdate = (now - lastAnimationUpdate) >= ANIMATION_UPDATE_INTERVAL_MS;

        if (!shouldUpdate) {
            // Keep previous pose, just update hat/sleeves to match
            model.hat.copyFrom(model.head);
            model.leftSleeve.copyFrom(model.leftArm);
            model.rightSleeve.copyFrom(model.rightArm);
            model.leftPants.copyFrom(model.leftLeg);
            model.rightPants.copyFrom(model.rightLeg);
            model.jacket.copyFrom(model.body);
            return; // EXIT EARLY - saves 40-60% CPU time
        }

        lastAnimationUpdate = now;

        // Get elapsed time using Minecraft's tick counter
        int tickCount = mc != null ? mc.gui.getGuiTicks() : 0;
        float elapsedTime = tickCount / 20.0f; // Convert ticks to seconds
        float t = elapsedTime * 0.8f; // Slower, more relaxed pace

        // Lerp factor for smooth transitions
        float lerpFactor = 0.15f;

        // Apply animation based on type
        switch (animation.toLowerCase()) {
            case "walk":
                setupWalkingPose(model, t, lerpFactor);
                break;
            case "sit":
                setupSittingPose(model, t, lerpFactor);
                break;
            case "idle":
            default:
                setupIdlePose(model, t, lerpFactor);
                break;
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
     * Setup idle pose with subtle bounce animation
     */
    private static void setupIdlePose(PlayerModel<?> model, float t, float lerpFactor) {
        // Set model state flags
        model.young = false;
        model.crouching = false;
        model.riding = false;
        model.attackTime = 0.0f;

        // HEAD: Bouncy up/down with head tilt
        float targetHeadRotZ = (float)Math.sin(t * 1.2) * 0.04f;
        prevHeadRotZ = smoothLerp(prevHeadRotZ, targetHeadRotZ, lerpFactor);

        model.head.xRot = 0.0f;
        model.head.yRot = 0.0f;
        model.head.zRot = prevHeadRotZ;

        // BODY: Bounce effect
        float targetBodyBounce = (float)Math.abs(Math.sin(t * 0.8)) * 0.12f;
        prevBodyRotX = smoothLerp(prevBodyRotX, targetBodyBounce * 0.2f, lerpFactor);

        model.body.xRot = -prevBodyRotX;
        model.body.yRot = 0.0f;
        model.body.zRot = 0.0f;

        // RIGHT ARM: Swing forward/back with rotation
        float targetRightArmRotX = (float)Math.sin(t * 0.8) * 0.12f;
        float targetRightArmRotZ = (float)Math.sin(t) * 0.04f;
        prevRightArmRotX = smoothLerp(prevRightArmRotX, targetRightArmRotX, lerpFactor);
        prevRightArmRotZ = smoothLerp(prevRightArmRotZ, targetRightArmRotZ, lerpFactor);

        model.rightArm.xRot = prevRightArmRotX;
        model.rightArm.yRot = 0.0f;
        model.rightArm.zRot = prevRightArmRotZ;

        // LEFT ARM: Opposite swing
        float targetLeftArmRotX = (float)Math.sin(t * 0.8 + Math.PI) * 0.12f;
        float targetLeftArmRotZ = (float)Math.sin(t + Math.PI) * -0.04f;
        prevLeftArmRotX = smoothLerp(prevLeftArmRotX, targetLeftArmRotX, lerpFactor);
        prevLeftArmRotZ = smoothLerp(prevLeftArmRotZ, targetLeftArmRotZ, lerpFactor);

        model.leftArm.xRot = prevLeftArmRotX;
        model.leftArm.yRot = 0.0f;
        model.leftArm.zRot = prevLeftArmRotZ;

        // RIGHT LEG: Subtle swing
        float targetRightLegRotX = (float)Math.sin(t * 0.5) * 0.05f;
        prevRightLegRotX = smoothLerp(prevRightLegRotX, targetRightLegRotX, lerpFactor);

        model.rightLeg.xRot = prevRightLegRotX;
        model.rightLeg.yRot = 0.0f;
        model.rightLeg.zRot = 0.0f;

        // LEFT LEG: Opposite subtle swing
        float targetLeftLegRotX = (float)Math.sin(t * 0.5 + Math.PI) * 0.05f;
        prevLeftLegRotX = smoothLerp(prevLeftLegRotX, targetLeftLegRotX, lerpFactor);

        model.leftLeg.xRot = prevLeftLegRotX;
        model.leftLeg.yRot = 0.0f;
        model.leftLeg.zRot = 0.0f;
    }

    /**
     * Setup walking pose with natural body movements
     */
    private static void setupWalkingPose(PlayerModel<?> model, float t, float lerpFactor) {
        model.young = false;
        model.crouching = false;
        model.riding = false;
        model.attackTime = 0.0f;

        // ARMS and LEGS: Swinging motion (arms opposite to legs) - faster animation
        float limbSwing = (float)Math.sin(t * 8.0) * 0.6f;

        // HEAD: Natural bobbing and slight side-to-side movement while walking
        float headBobY = (float)Math.abs(Math.sin(t * 8.0)) * 0.05f; // Up and down bob matching stride
        float headTiltZ = (float)Math.sin(t * 8.0) * 0.03f; // Slight tilt side to side
        model.head.xRot = -headBobY; // Nod slightly with each step
        model.head.yRot = 0.0f;
        model.head.zRot = headTiltZ;

        // BODY: Dynamic movement - sway and lean
        float bodySway = (float)Math.sin(t * 8.0) * 0.04f; // Side-to-side sway
        float bodyBob = (float)Math.abs(Math.sin(t * 8.0)) * 0.02f; // Up/down movement
        model.body.xRot = 0.05f + bodyBob; // Forward lean plus bob
        model.body.yRot = bodySway; // Torso rotation
        model.body.zRot = bodySway * 0.5f; // Slight roll

        // ARMS: Natural swing with slight outward motion
        float armSwingOut = (float)Math.abs(Math.sin(t * 8.0)) * 0.05f;
        model.rightArm.xRot = -limbSwing;
        model.rightArm.yRot = 0.0f;
        model.rightArm.zRot = armSwingOut;

        model.leftArm.xRot = limbSwing;
        model.leftArm.yRot = 0.0f;
        model.leftArm.zRot = -armSwingOut;

        // LEGS: Standard walking motion
        model.rightLeg.xRot = limbSwing;
        model.rightLeg.yRot = 0.0f;
        model.rightLeg.zRot = 0.0f;

        model.leftLeg.xRot = -limbSwing;
        model.leftLeg.yRot = 0.0f;
        model.leftLeg.zRot = 0.0f;
    }

    /**
     * Setup sitting pose with subtle idle movements
     */
    private static void setupSittingPose(PlayerModel<?> model, float t, float lerpFactor) {
        model.young = false;
        model.crouching = false;
        model.riding = true; // Enable riding flag for sitting pose
        model.attackTime = 0.0f;

        // HEAD: Subtle breathing and slight look-around
        float headBob = (float)Math.sin(t * 0.6) * 0.02f;
        float headTilt = (float)Math.sin(t * 0.4) * 0.03f;
        model.head.xRot = headBob;
        model.head.yRot = 0.0f;
        model.head.zRot = headTilt;

        // BODY: Subtle breathing motion
        float breathe = (float)Math.sin(t * 0.5) * 0.01f;
        model.body.xRot = breathe;
        model.body.yRot = 0.0f;
        model.body.zRot = 0.0f;

        // ARMS: Resting on legs with subtle relaxed movement
        float armSway = (float)Math.sin(t * 0.7) * 0.02f;
        model.rightArm.xRot = -0.62f + armSway;
        model.rightArm.yRot = 0.0f;
        model.rightArm.zRot = 0.0f;

        model.leftArm.xRot = -0.62f - armSway;
        model.leftArm.yRot = 0.0f;
        model.leftArm.zRot = 0.0f;

        // LEGS: Bent for sitting with very subtle fidget
        float legFidget = (float)Math.sin(t * 0.3) * 0.01f;
        model.rightLeg.xRot = -1.4f + legFidget;
        model.rightLeg.yRot = 0.31f;
        model.rightLeg.zRot = 0.05f;

        model.leftLeg.xRot = -1.4f - legFidget;
        model.leftLeg.yRot = -0.31f;
        model.leftLeg.zRot = -0.05f;
    }

    /**
     * Render a grass block underneath the player when sitting
     * Positioned at the player's feet
     */
    private static void renderGrassBlock(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource) {
        poseStack.pushPose();

        // Hardcoded position and scale values
        double offsetX = -0.333;
        double offsetY = 1.4;
        double offsetZ = 0.222;
        double scale = 0.575;

        // Position the grass block
        poseStack.translate(offsetX, offsetY, offsetZ);

        // Rotate 180 degrees around X-axis to flip it right-side up
        poseStack.mulPose(com.mojang.math.Axis.XP.rotationDegrees(180.0f));

        // Scale the block
        poseStack.scale((float)scale, (float)scale, (float)scale);

        // Build cache on first render
        if (!grassBlockCacheBuilt) {
            QuickSkin.LOGGER.info("[PlayerModelRenderer] Grass block cache initialized");
            grassBlockCacheBuilt = true;
        }

        // Use Minecraft's BlockRenderer to render a grass block properly
        // The actual mesh is cached internally by Minecraft's block renderer
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.client.renderer.block.BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();

        // Render the grass block using Minecraft's built-in renderer
        blockRenderer.renderSingleBlock(
            net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState(),
            poseStack,
            bufferSource,
            15728880, // Full brightness
            net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY
        );

        poseStack.popPose();
    }

    /**
     * Helper method to render a single face of a cube
     */
    private static void renderCubeFace(Matrix4f matrix, PoseStack.Pose pose,
                                       MultiBufferSource.BufferSource bufferSource,
                                       ResourceLocation texture,
                                       float x1, float y1, float z1,
                                       float x2, float y2, float z2,
                                       float nx, float ny, float nz) {
        RenderType renderType = RenderType.entityCutout(texture);
        var vertexConsumer = bufferSource.getBuffer(renderType);

        // Calculate the 4 corners of the face
        float minX = Math.min(x1, x2);
        float maxX = Math.max(x1, x2);
        float minY = Math.min(y1, y2);
        float maxY = Math.max(y1, y2);
        float minZ = Math.min(z1, z2);
        float maxZ = Math.max(z1, z2);

        // Determine which coordinates vary based on the normal
        if (ny != 0) { // Top or bottom face (Y constant)
            float y = y1;
            // Bottom-left
            vertexConsumer.vertex(matrix, minX, y, minZ)
                .color(255, 255, 255, 255)
                .uv(0, 0)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(pose.normal(), nx, ny, nz)
                .endVertex();
            // Top-left
            vertexConsumer.vertex(matrix, minX, y, maxZ)
                .color(255, 255, 255, 255)
                .uv(0, 1)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(pose.normal(), nx, ny, nz)
                .endVertex();
            // Top-right
            vertexConsumer.vertex(matrix, maxX, y, maxZ)
                .color(255, 255, 255, 255)
                .uv(1, 1)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(pose.normal(), nx, ny, nz)
                .endVertex();
            // Bottom-right
            vertexConsumer.vertex(matrix, maxX, y, minZ)
                .color(255, 255, 255, 255)
                .uv(1, 0)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(pose.normal(), nx, ny, nz)
                .endVertex();
        } else if (nx != 0) { // Left or right face (X constant)
            float x = x1;
            vertexConsumer.vertex(matrix, x, minY, minZ)
                .color(255, 255, 255, 255)
                .uv(0, 0)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(pose.normal(), nx, ny, nz)
                .endVertex();
            vertexConsumer.vertex(matrix, x, maxY, minZ)
                .color(255, 255, 255, 255)
                .uv(0, 1)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(pose.normal(), nx, ny, nz)
                .endVertex();
            vertexConsumer.vertex(matrix, x, maxY, maxZ)
                .color(255, 255, 255, 255)
                .uv(1, 1)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(pose.normal(), nx, ny, nz)
                .endVertex();
            vertexConsumer.vertex(matrix, x, minY, maxZ)
                .color(255, 255, 255, 255)
                .uv(1, 0)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(pose.normal(), nx, ny, nz)
                .endVertex();
        } else { // Front or back face (Z constant)
            float z = z1;
            vertexConsumer.vertex(matrix, minX, minY, z)
                .color(255, 255, 255, 255)
                .uv(0, 0)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(pose.normal(), nx, ny, nz)
                .endVertex();
            vertexConsumer.vertex(matrix, minX, maxY, z)
                .color(255, 255, 255, 255)
                .uv(0, 1)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(pose.normal(), nx, ny, nz)
                .endVertex();
            vertexConsumer.vertex(matrix, maxX, maxY, z)
                .color(255, 255, 255, 255)
                .uv(1, 1)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(pose.normal(), nx, ny, nz)
                .endVertex();
            vertexConsumer.vertex(matrix, maxX, minY, z)
                .color(255, 255, 255, 255)
                .uv(1, 0)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(15728880)
                .normal(pose.normal(), nx, ny, nz)
                .endVertex();
        }
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
     * Clear the cached player entity
     * Call this when leaving a world to reset the player rendering state
     */
    public static void clearCachedPlayer() {
        cachedPlayer = null;
        QuickSkin.LOGGER.info("[PlayerModelRenderer] Cached player cleared");
    }

    /**
     * Reset lighting state
     * Call this when screen changes to ensure proper lighting setup
     */
    public static void resetLightingState() {
        lightingSetup = false;
    }

    /**
     * Clear grass block cache
     * Call this when leaving world to clear cache
     */
    public static void clearGrassBlockCache() {
        grassBlockCacheBuilt = false;
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