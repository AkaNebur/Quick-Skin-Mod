package com.quickskin.mod.client.rendering;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.services.AnimatedTextureManager;
import com.quickskin.mod.platform.MinecraftCompat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.model.player.PlayerCapeModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Utility for rendering player models in GUI using vanilla Minecraft rendering
 * Replaces GeckoLib-based rendering with vanilla PlayerModel
 */
@Environment(EnvType.CLIENT)
public class PlayerModelRenderer {

    private static PlayerModel  classicModel;
    private static PlayerModel slimModel;
    private static PlayerCapeModel capeModel;

    // Match cape data to the exact value-state used by the PiP cache.
    private static final Map<PreviewCapeKey, PreviewCapeState> PENDING_CAPES =
            new LinkedHashMap<>(16, 0.75f, true);
    private static final int MAX_PENDING_CAPES = 128;

    public record PreviewCapeState(
            Identifier texture, PlayerModel bodyModel, PlayerCapeModel capeModel) {
    }

    private record PreviewCapeKey(
            PlayerModel model, Identifier skin, float rotationX, float rotationY, float pivotY,
            int x0, int y0, int x1, int y1, float scale) {
    }

    private static void registerPendingCape(
            PlayerModel bodyModel, Identifier skin, float rotationX, float rotationY, float pivotY,
            int x0, int y0, int x1, int y1, float scale,
            Identifier texture, PlayerCapeModel playerCapeModel) {
        synchronized (PENDING_CAPES) {
            PreviewCapeKey key = new PreviewCapeKey(
                    bodyModel, skin, rotationX, rotationY, pivotY, x0, y0, x1, y1, scale);
            if (!PENDING_CAPES.containsKey(key) && PENDING_CAPES.size() >= MAX_PENDING_CAPES) {
                var oldest = PENDING_CAPES.keySet().iterator();
                if (oldest.hasNext()) PENDING_CAPES.remove(oldest.next());
            }
            PENDING_CAPES.put(key, new PreviewCapeState(texture, bodyModel, playerCapeModel));
        }
    }

    public static PreviewCapeState consumePendingCape(
            PlayerModel bodyModel, Identifier skin, float rotationX, float rotationY, float pivotY,
            int x0, int y0, int x1, int y1, float scale) {
        synchronized (PENDING_CAPES) {
            return PENDING_CAPES.remove(new PreviewCapeKey(
                    bodyModel, skin, rotationX, rotationY, pivotY, x0, y0, x1, y1, scale));
        }
    }

    public static void clearPendingCapes() {
        synchronized (PENDING_CAPES) {
            PENDING_CAPES.clear();
        }
    }

    /**
     * Initialize models (lazy initialization)
     */
    private static void ensureModelsLoaded() {
        if (classicModel == null) {
            Minecraft mc = Minecraft.getInstance();
            ModelPart classicRoot = mc.getEntityModels().bakeLayer(ModelLayers.PLAYER);
            classicModel = new PlayerModel(classicRoot, false);

            ModelPart slimRoot = mc.getEntityModels().bakeLayer(ModelLayers.PLAYER_SLIM);
            slimModel = new PlayerModel(slimRoot, true);

            // In MC 1.21.11+, the cape is a separate model (PlayerCapeModel)
            ModelPart capeRoot = mc.getEntityModels().bakeLayer(ModelLayers.PLAYER_CAPE);
            capeModel = new PlayerCapeModel(capeRoot);
        }
    }

    /**
     * Identifies models owned by QuickSkin's cached preview renderer.
     *
     * @return {@code false} for the classic model, {@code true} for the slim model,
     *         or {@code null} when the model belongs to another renderer
     */
    public static Boolean getQuickSkinPreviewThinArms(PlayerModel model) {
        if (model == classicModel) {
            return Boolean.FALSE;
        }
        if (model == slimModel) {
            return Boolean.TRUE;
        }
        return null;
    }

    // Cached player entity for rendering (persists even after leaving world)
    private static Player cachedPlayer;

    public static java.util.UUID getCachedPlayerUUID() {
        Player player = cachedPlayer;
        return player != null ? player.getUUID() : null;
    }

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

    // Animation frequency throttling (30 FPS instead of 60+)
    private static long lastAnimationUpdate = 0;
    private static final long ANIMATION_UPDATE_INTERVAL_MS = 33; // ~30 FPS

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

        // Note: Shadow management removed - methods not available in Minecraft 1.21 with Mojang mappings
        // Performance impact is minimal in GUI previews

        try {
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
            // Title screen: use GUI tick counter (more efficient than System.currentTimeMillis())
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
            if ("sit".equals(playerData.getCurrentAnimation() != null ? playerData.getCurrentAnimation().toLowerCase(Locale.ROOT) : null) && mc.level == null) {
                // In 1.21.11, graphics.pose() returns Matrix3x2fStack, use new PoseStack for 3D transforms
                PoseStack poseStack = new PoseStack();
                MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();

                poseStack.pushPose();
                // Match the transformations from InventoryScreen
                poseStack.translate(x, y, 50.0);
                float scaleCasted = (float)(int)scale;
                Matrix4f scaleMatrix = (new Matrix4f()).scaling(scaleCasted, scaleCasted, -scaleCasted);
                poseStack.mulPose(scaleMatrix);
                poseStack.mulPose(quaternionXZ);
                poseStack.mulPose(Axis.YP.rotationDegrees(-targetRotation));
                poseStack.scale(-1.0F, -1.0F, 1.0F);
                poseStack.translate(0.0F, -1.501F, 0.0F);

                renderGrassBlock(poseStack, bufferSource);
                bufferSource.endBatch();
                poseStack.popPose();
            }

            // Set cape data for GuiSkinRendererMixin (PiP bypasses CapeLayer in 1.21.11+)
            ensureModelsLoaded();
            Identifier entityCapeTexture = null;
            if (playerData.getCapeLocation() != null) {
                entityCapeTexture = playerData.getCapeLocation();
                String capeId = playerData.getCapeId();

                String animationId = null;
                if (capeId != null) {
                    if (capeId.startsWith("local_cape:")) {
                        animationId = "cape_" + capeId.substring("local_cape:".length());
                    } else if (capeId.startsWith("known:")) {
                        animationId = "cape_known_" + capeId.substring("known:".length());
                    }
                }

                if (animationId != null) {
                    Identifier currentFrame = AnimatedTextureManager.getInstance().getCurrentFrameTexture(animationId);
                    if (currentFrame != null) {
                        entityCapeTexture = currentFrame;
                    }
                }
            }
            // 1.21.11: renderEntityInInventory removed. We call submitEntityRenderState directly
            // to preserve our own rotation (renderEntityInInventoryFollowsMouse overrides rotation).
            int halfWidth = (int)(scale * 0.6f);
            // Shift box center UP by ~bbHeight/2 in screen space to keep feet at y
            // (submitEntityRenderState offsets by bbHeight/2, centering the entity visually)
            int entityHalfHeight = (int)(scale * 0.9f);
            int yCenter = y - entityHalfHeight;
            int topHalf = (int)(scale * 2.0f);
            int bottomHalf = topHalf;
            int x1 = x - halfWidth, y1 = yCenter - topHalf, x2 = x + halfWidth, y2 = yCenter + bottomHalf;

            // Extract render state (replicating InventoryScreen.extractRenderState)
            var dispatcher = mc.getEntityRenderDispatcher();
            var renderer = dispatcher.getRenderer(playerToRender);
            var renderState = renderer.createRenderState(playerToRender, 1.0f);
            renderState.lightCoords = 15728880; // full bright
            renderState.shadowPieces.clear();
            renderState.outlineColor = 0;

            // createRenderState already copied the entity's rotation (set at lines 157-161),
            // so we only need to normalize bounding box for scale=1
            if (renderState instanceof net.minecraft.client.renderer.entity.state.LivingEntityRenderState livingState) {
                livingState.boundingBoxWidth /= livingState.scale;
                livingState.boundingBoxHeight /= livingState.scale;
                livingState.scale = 1.0f;
            }

            // Compute offset: center entity vertically (bbHeight/2 + small offset)
            org.joml.Vector3f offset = new org.joml.Vector3f(0, renderState.boundingBoxHeight / 2.0f + 0.0625f, 0);

            graphics.submitEntityRenderState(
                    renderState,
                    (float)(int) scale,
                    offset,
                    quaternionXZ,
                    quaternionY,
                    x1, y1, x2, y2
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
        } finally {
            // Previously managed shadow state here, but methods not available in 1.21
        }
    }

    /**
     * Manually render player model without requiring a player entity
     * Used on title screen where no world/player exists
     * In 1.21.11+, all GUI 3D rendering must go through the PiP system.
     * Cape rendering is handled by GuiSkinRendererMixin which renders the cape
     * inside renderToTexture(), using the shared buffer source.
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
        PlayerModel model = "slim".equals(playerData.getModelType() != null ? playerData.getModelType().toLowerCase(Locale.ROOT) : null) ? slimModel : classicModel;

        Minecraft mc = Minecraft.getInstance();

        // Setup model pose with idle animation
        setupModelPoseWithAnimation(model, playerData, mouseX, mouseY, followMouse, x, y, mc);

        // Set cape data for the GuiSkinRendererMixin to pick up during renderToTexture
        Identifier capeTexture = null;
        if (playerData.getCapeLocation() != null) {
            capeTexture = playerData.getCapeLocation();
            String capeId = playerData.getCapeId();

            String animationId = null;
            if (capeId != null) {
                if (capeId.startsWith("local_cape:")) {
                    animationId = "cape_" + capeId.substring("local_cape:".length());
                } else if (capeId.startsWith("known:")) {
                    animationId = "cape_known_" + capeId.substring("known:".length());
                }
            }

            if (animationId != null) {
                Identifier currentFrame = AnimatedTextureManager.getInstance().getCurrentFrameTexture(animationId);
                if (currentFrame != null) {
                    capeTexture = currentFrame;
                }
            }
        }
        // Force PiP cache invalidation when cape changes by adding imperceptible scale nudge
        int capeHash = capeTexture != null ? capeTexture.hashCode() : 0;
        float scaleNudge = 0.000004f * (capeHash & 0x3FFF);
        float rotationXNudge = 0.0000001f * ((capeHash >>> 14) & 0x3FFFF);

        // Submit to PiP system - cape rendering injected by GuiSkinRendererMixin
        int boxHeight = (int)(scale * 2.3f);
        int boxHalfWidth = (int)(scale * 1.0f);

        float submittedScale = (float)(int) scale + scaleNudge;
        float submittedRotationY = -45.0f + yRotation;
        graphics.submitSkinRenderState(
                model,
                playerData.getSkinLocation(),
                submittedScale,
                rotationXNudge,
                submittedRotationY,
                -1.0625f,
                x - boxHalfWidth,
                y - boxHeight,
                x + boxHalfWidth,
                y
        );
        registerPendingCape(model, playerData.getSkinLocation(), rotationXNudge,
                submittedRotationY, -1.0625f, x - boxHalfWidth, y - boxHeight,
                x + boxHalfWidth, y, submittedScale, capeTexture, capeModel);
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
        // Create GuiGraphics wrapper - 1.21.11: GuiGraphics(Minecraft, GuiRenderState, int width, int height)
        Minecraft mc = Minecraft.getInstance();
        var window = mc.getWindow();
        GuiGraphics graphics = new GuiGraphics(mc, new net.minecraft.client.gui.render.state.GuiRenderState(), window.getGuiScaledWidth(), window.getGuiScaledHeight());

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
            Identifier capeTexture,
            PlayerModel model
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
        RenderType capeRenderType = RenderTypes.entitySolid(capeTexture);
        var capeConsumer = bufferSource.getBuffer(capeRenderType);

        PoseStack.Pose pose = poseStack.last();
        Matrix4f matrix = pose.pose();

        // UV coordinates
        float u0 = 0.0f;
        float v0 = 0.0f;
        float u1 = 10.0f / 64.0f;
        float v1 = 16.0f / 32.0f;

        // Render quad
        capeConsumer.addVertex(matrix, xOffset, 0.0f, 0.0f)
                .setColor(255, 255, 255, 255)
                .setUv(u0, v0)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(0.0f, 0.0f, 1.0f);
                

        capeConsumer.addVertex(matrix, xOffset, capeHeight, 0.0f)
                .setColor(255, 255, 255, 255)
                .setUv(u0, v1)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(0.0f, 0.0f, 1.0f);
                

        capeConsumer.addVertex(matrix, xOffset + capeWidth, capeHeight, 0.0f)
                .setColor(255, 255, 255, 255)
                .setUv(u1, v1)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(0.0f, 0.0f, 1.0f);
                

        capeConsumer.addVertex(matrix, xOffset + capeWidth, 0.0f, 0.0f)
                .setColor(255, 255, 255, 255)
                .setUv(u1, v0)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(0.0f, 0.0f, 1.0f);
                

        poseStack.popPose();
    }

    /**
     * OLD Render cape in manual mode with correct transformations
     * The cape needs special handling because we're in a transformed coordinate space
     */
    private static void renderCapeManualOLD(
            PoseStack poseStack,
            MultiBufferSource.BufferSource bufferSource,
            Identifier capeLocation,
            float yRotation
    ) {
        poseStack.pushPose();

        // DEBUG: Render a GIANT bright magenta rectangle that's impossible to miss
        // This will help us see exactly where the cape is being rendered
        // RenderType.gui() removed in 1.21.11 - using debugFilledBox() instead
        RenderType debugRenderType = RenderTypes.debugFilledBox();
        var debugConsumer = bufferSource.getBuffer(debugRenderType);
        PoseStack.Pose debugPose = poseStack.last();
        Matrix4f debugMatrix = debugPose.pose();

        // Debug rendering commented out for 1.21.1 API compatibility
        // TODO: Update debug rendering to use new VertexConsumer API
        /*
        // Render a huge bright rectangle (5x5 units) centered at origin
        float debugSize = 2.5f;
        // Top-left
        debugConsumer.addVertex(debugMatrix, -debugSize, -debugSize, 0.0f)
                .setColor(255, 0, 255, 255) // Bright magenta
                .setUv(0, 0)
                
        // Bottom-left
        debugConsumer.addVertex(debugMatrix, -debugSize, debugSize, 0.0f)
                .setColor(255, 0, 255, 255)
                .setUv(0, 1)
                
        // Bottom-right
        debugConsumer.addVertex(debugMatrix, debugSize, debugSize, 0.0f)
                .setColor(255, 0, 255, 255)
                .setUv(1, 1)
                
        // Top-right
        debugConsumer.addVertex(debugMatrix, debugSize, -debugSize, 0.0f)
                .setColor(255, 0, 255, 255)
                .setUv(1, 0)
                
        */

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
        RenderType renderType = RenderTypes.entityTranslucent(capeLocation);
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
        vertexConsumer.addVertex(positionMatrix, xOffset, 0.0f, 0.0f)
                .setColor(255, 255, 255, 255)
                .setUv(u0, v0)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240) // Full bright
                .setNormal(0.0f, 0.0f, 1.0f);
                

        // Bottom-left
        vertexConsumer.addVertex(positionMatrix, xOffset, capeHeight, 0.0f)
                .setColor(255, 255, 255, 255)
                .setUv(u0, v1)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(0.0f, 0.0f, 1.0f);
                

        // Bottom-right
        vertexConsumer.addVertex(positionMatrix, xOffset + capeWidth, capeHeight, 0.0f)
                .setColor(255, 255, 255, 255)
                .setUv(u1, v1)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(0.0f, 0.0f, 1.0f);
                

        // Top-right
        vertexConsumer.addVertex(positionMatrix, xOffset + capeWidth, 0.0f, 0.0f)
                .setColor(255, 255, 255, 255)
                .setUv(u1, v0)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(0.0f, 0.0f, 1.0f);
                

        poseStack.popPose();
    }

    /**
     * Setup model pose with animation based on current animation state
     * Supports idle, walk, run, sneak, sit, jump animations
     */
    private static void setupModelPoseWithAnimation(
            PlayerModel model,
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

        // CHECK: Should we update animation this frame? (30 FPS instead of 60+)
        long now = System.currentTimeMillis();
        boolean shouldUpdate = (now - lastAnimationUpdate) >= ANIMATION_UPDATE_INTERVAL_MS;

        if (!shouldUpdate) {
            // In MC 1.21.11+, outer layers (sleeves, pants, jacket) are children of their
            // corresponding body parts, so they inherit transforms automatically.
            // Just reset their local rotations to zero to avoid doubling.
            resetOuterLayerRotations(model);
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
        switch (animation.toLowerCase(Locale.ROOT)) {
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

        // In MC 1.21.11+, outer layers are children of their body parts and inherit
        // transforms automatically. Reset their local rotations to zero.
        resetOuterLayerRotations(model);
    }

    /**
     * Reset outer layer rotations to zero.
     * In MC 1.21.11+, outer layers (hat, sleeves, pants, jacket) are children of their
     * corresponding body parts in the model hierarchy, so they inherit parent transforms.
     * Setting their local rotations to zero ensures they stay aligned with the body.
     */
    private static void resetOuterLayerRotations(PlayerModel model) {
        model.hat.xRot = 0;
        model.hat.yRot = 0;
        model.hat.zRot = 0;
        model.leftSleeve.xRot = 0;
        model.leftSleeve.yRot = 0;
        model.leftSleeve.zRot = 0;
        model.rightSleeve.xRot = 0;
        model.rightSleeve.yRot = 0;
        model.rightSleeve.zRot = 0;
        model.leftPants.xRot = 0;
        model.leftPants.yRot = 0;
        model.leftPants.zRot = 0;
        model.rightPants.xRot = 0;
        model.rightPants.yRot = 0;
        model.rightPants.zRot = 0;
        model.jacket.xRot = 0;
        model.jacket.yRot = 0;
        model.jacket.zRot = 0;
    }

    /**
     * Setup idle pose with subtle bounce animation
     */
    private static void setupIdlePose(PlayerModel model, float t, float lerpFactor) {
        // Set model state flags
        MinecraftCompat.INSTANCE.setYoung(model, false);
        MinecraftCompat.INSTANCE.setCrouching(model, false);
        MinecraftCompat.INSTANCE.setRiding(model, false);
        MinecraftCompat.INSTANCE.setAttackTime(model, 0.0f);

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
    private static void setupWalkingPose(PlayerModel model, float t, float lerpFactor) {
        MinecraftCompat.INSTANCE.setYoung(model, false);
        MinecraftCompat.INSTANCE.setCrouching(model, false);
        MinecraftCompat.INSTANCE.setRiding(model, false);
        MinecraftCompat.INSTANCE.setAttackTime(model, 0.0f);

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
    private static void setupSittingPose(PlayerModel model, float t, float lerpFactor) {
        MinecraftCompat.INSTANCE.setYoung(model, false);
        MinecraftCompat.INSTANCE.setCrouching(model, false);
        MinecraftCompat.INSTANCE.setRiding(model, true); // Enable riding flag for sitting pose
        MinecraftCompat.INSTANCE.setAttackTime(model, 0.0f);

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

        // Use Minecraft's BlockRenderer to render a grass block properly
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
                                       Identifier texture,
                                       float x1, float y1, float z1,
                                       float x2, float y2, float z2,
                                       float nx, float ny, float nz) {
        RenderType renderType = RenderTypes.entityCutout(texture);
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
            vertexConsumer.addVertex(matrix, minX, y, minZ)
                .setColor(255, 255, 255, 255)
                .setUv(0, 0)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(nx, ny, nz);
                
            // Top-left
            vertexConsumer.addVertex(matrix, minX, y, maxZ)
                .setColor(255, 255, 255, 255)
                .setUv(0, 1)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(nx, ny, nz);
                
            // Top-right
            vertexConsumer.addVertex(matrix, maxX, y, maxZ)
                .setColor(255, 255, 255, 255)
                .setUv(1, 1)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(nx, ny, nz);
                
            // Bottom-right
            vertexConsumer.addVertex(matrix, maxX, y, minZ)
                .setColor(255, 255, 255, 255)
                .setUv(1, 0)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(nx, ny, nz);
                
        } else if (nx != 0) { // Left or right face (X constant)
            float x = x1;
            vertexConsumer.addVertex(matrix, x, minY, minZ)
                .setColor(255, 255, 255, 255)
                .setUv(0, 0)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(nx, ny, nz);
                
            vertexConsumer.addVertex(matrix, x, maxY, minZ)
                .setColor(255, 255, 255, 255)
                .setUv(0, 1)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(nx, ny, nz);
                
            vertexConsumer.addVertex(matrix, x, maxY, maxZ)
                .setColor(255, 255, 255, 255)
                .setUv(1, 1)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(nx, ny, nz);
                
            vertexConsumer.addVertex(matrix, x, minY, maxZ)
                .setColor(255, 255, 255, 255)
                .setUv(1, 0)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(nx, ny, nz);
                
        } else { // Front or back face (Z constant)
            float z = z1;
            vertexConsumer.addVertex(matrix, minX, minY, z)
                .setColor(255, 255, 255, 255)
                .setUv(0, 0)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(nx, ny, nz);
                
            vertexConsumer.addVertex(matrix, minX, maxY, z)
                .setColor(255, 255, 255, 255)
                .setUv(0, 1)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(nx, ny, nz);
                
            vertexConsumer.addVertex(matrix, maxX, maxY, z)
                .setColor(255, 255, 255, 255)
                .setUv(1, 1)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(nx, ny, nz);
                
            vertexConsumer.addVertex(matrix, maxX, minY, z)
                .setColor(255, 255, 255, 255)
                .setUv(1, 0)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setUv2(240, 240)
                .setNormal(nx, ny, nz);
                
        }
    }

    /**
     * Render a debug cube at the chest position to show rotation center
     */
    private static void renderDebugCube(PoseStack poseStack, MultiBufferSource buffer, PlayerModel model) {
        poseStack.pushPose();

        // Get chest position from the model's body part
        // The body part is positioned at the chest area
        ModelPart body = model.body;

        // Translate to chest center (body origin is at chest)
        poseStack.translate(0.0, -0.7, 0.0); // Move to chest height

        // Scale the cube to be visible
        poseStack.scale(0.2f, 0.2f, 0.2f);

        // Render a bright colored cube
        RenderType renderType = RenderTypes.lines();
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
     * TODO: Update for 1.21.1 VertexConsumer API
     */
    private static void addLine(com.mojang.blaze3d.vertex.VertexConsumer consumer, Matrix4f matrix,
                                float x1, float y1, float z1, float x2, float y2, float z2,
                                int r, int g, int b) {
        // Commented out for 1.21.1 API compatibility
        // consumer.addVertex(matrix, x1, y1, z1).setColor(r, g, b, 255).setNormal(1, 0, 0);
        // consumer.addVertex(matrix, x2, y2, z2).setColor(r, g, b, 255).setNormal(1, 0, 0);
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
        // Front (0Â°): 1.425f
        // Side (90Â°/270Â°): 1.2f
        // Back (180Â°): 1.3f
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

        // 1.21.11: RenderSystem.setShaderLights now takes GpuBufferSlice instead of Vector3f.
        // Use Lighting.Entry-based API instead. This method is currently unused.
        Minecraft.getInstance().gameRenderer.getLighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
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
        defaultData.setSkinLocation(Identifier.withDefaultNamespace("textures/entity/steve.png"));
        defaultData.setModelType("classic");

        renderPlayerModel(poseStack, buffer, x, y, scale, yRotation, defaultData, 0, 0, false);
    }

    /**
     * Clear the cached player entity
     * Call this when leaving a world to reset the player rendering state
     */
    public static void clearCachedPlayer() {
        cachedPlayer = null;
        clearPendingCapes();
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

        return true;
    }
}
