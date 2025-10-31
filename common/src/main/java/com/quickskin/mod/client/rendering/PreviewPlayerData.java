package com.quickskin.mod.client.rendering;

import com.quickskin.mod.common.data.SkinResolution;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.ResourceLocation;

/**
 * Data holder for player preview rendering
 * Replaces GeoPlayerEntity - stores all info needed to render a player model in GUI
 */
@Environment(EnvType.CLIENT)
public class PreviewPlayerData {

    private ResourceLocation skinLocation;
    private ResourceLocation capeLocation;
    private String modelType; // "classic" or "slim"
    private SkinResolution resolution;
    private float yRotation; // Y-axis rotation in degrees
    private float headYaw; // Head yaw for looking around
    private float headPitch; // Head pitch for looking up/down

    // Animation state
    private String currentAnimation; // For future animation support
    private float animationProgress;
    private long lastTickTime;

    public PreviewPlayerData() {
        this.modelType = "classic";
        this.resolution = SkinResolution.STANDARD;
        this.yRotation = 0.0f;
        this.headYaw = 0.0f;
        this.headPitch = 0.0f;
        this.currentAnimation = "idle";
        this.animationProgress = 0.0f;
        this.lastTickTime = System.currentTimeMillis();
    }

    /**
     * Update animation state
     * Should be called each frame
     */
    public void tick() {
        long currentTime = System.currentTimeMillis();
        float deltaTime = (currentTime - lastTickTime) / 1000.0f;
        lastTickTime = currentTime;

        // Update animation progress (for future use)
        animationProgress += deltaTime;
        if (animationProgress > 360.0f) {
            animationProgress -= 360.0f;
        }
    }

    /**
     * Check if this is a slim model
     */
    public boolean isSlim() {
        return "slim".equals(modelType);
    }

    /**
     * Check if HD skin
     */
    public boolean isHD() {
        return resolution != null && resolution.isHD();
    }

    /**
     * Get scale factor for HD skins
     */
    public int getScaleFactor() {
        return resolution != null ? resolution.getScale() : 1;
    }

    // Getters and setters

    public ResourceLocation getSkinLocation() {
        return skinLocation;
    }

    public void setSkinLocation(ResourceLocation skinLocation) {
        this.skinLocation = skinLocation;
    }

    public ResourceLocation getCapeLocation() {
        return capeLocation;
    }

    public void setCapeLocation(ResourceLocation capeLocation) {
        this.capeLocation = capeLocation;
    }

    public String getModelType() {
        return modelType;
    }

    public void setModelType(String modelType) {
        this.modelType = modelType;
    }

    public SkinResolution getResolution() {
        return resolution;
    }

    public void setResolution(SkinResolution resolution) {
        this.resolution = resolution;
    }

    public float getYRotation() {
        return yRotation;
    }

    public void setYRotation(float yRotation) {
        this.yRotation = yRotation;
    }

    public float getHeadYaw() {
        return headYaw;
    }

    public void setHeadYaw(float headYaw) {
        this.headYaw = headYaw;
    }

    public float getHeadPitch() {
        return headPitch;
    }

    public void setHeadPitch(float headPitch) {
        this.headPitch = headPitch;
    }

    public String getCurrentAnimation() {
        return currentAnimation;
    }

    public void setCurrentAnimation(String currentAnimation) {
        this.currentAnimation = currentAnimation;
    }

    public float getAnimationProgress() {
        return animationProgress;
    }
}
