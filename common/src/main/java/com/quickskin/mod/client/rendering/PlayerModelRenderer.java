package com.quickskin.mod.client.rendering;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.HumanoidArm;
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

    /**
     * Initialize models (lazy initialization)
     */
    private static void ensureModelsLoaded() {
        if (classicModel == null) {
            Minecraft mc = Minecraft.getInstance();
            ModelPart classicRoot = mc.getEntityModels().bakeLayer(ModelLayers.PLAYER);
            classicModel = new PlayerModel<>(classicRoot, false);

            ModelPart slimRoot = mc.getEntityModels().bakeLayer(ModelLayers.PLAYER_SLIM);
            slimModel = new PlayerModel<>(slimRoot, true);
        }
    }

    /**
     * Render a player model in GUI
     *
     * @param poseStack The pose stack for transformations
     * @param buffer The buffer source for rendering
     * @param x Screen X position (center of model)
     * @param y Screen Y position (center of model)
     * @param scale Scale factor (typically 75 for full-body view)
     * @param yRotation Y-axis rotation in degrees
     * @param playerData Player data containing skin, model type, etc.
     * @param mouseX Mouse X position (for head tracking, optional)
     * @param mouseY Mouse Y position (for head tracking, optional)
     * @param followMouse Whether the head should follow the mouse
     */
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
        ensureModelsLoaded();

        if (playerData.getSkinLocation() == null) {
            return; // No skin to render
        }

        poseStack.pushPose();

        // Translate to render position
        poseStack.translate(x, y, 100.0);

        // Apply scale
        poseStack.scale(scale, scale, scale);

        // Flip Z-axis for proper depth rendering
        poseStack.mulPoseMatrix(new Matrix4f().scaling(1.0f, 1.0f, -1.0f));

        // 180-degree rotation to flip model upright
        Quaternionf quaternion = new Quaternionf().rotateZ((float) Math.PI);
        poseStack.mulPose(quaternion);

        // Apply Y-axis rotation (model turning)
        poseStack.mulPose(Axis.YN.rotationDegrees(yRotation));

        // Pivot point adjustment to center the model
        // This positions the model so feet are at bottom and body is centered
        poseStack.translate(-0.5f, -1.0f, -0.5f);

        // Select appropriate model
        PlayerModel<?> model = playerData.isSlim() ? slimModel : classicModel;

        // Setup model pose
        setupModelPose(model, playerData, mouseX, mouseY, followMouse, x, y);

        // Setup lighting
        setupLighting(yRotation);

        // Get render type
        ResourceLocation skinLocation = playerData.getSkinLocation();
        RenderType renderType = RenderType.entityTranslucentCull(skinLocation);

        // Render the model
        model.renderToBuffer(
                poseStack,
                buffer.getBuffer(renderType),
                15728880, // Full bright lighting (LightTexture.FULL_BRIGHT)
                OverlayTexture.NO_OVERLAY,
                1.0f, 1.0f, 1.0f, 1.0f // White color (no tinting)
        );

        poseStack.popPose();

        // Reset render state
        RenderSystem.applyModelViewMatrix();
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
}
