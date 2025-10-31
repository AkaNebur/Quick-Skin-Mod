package com.quickskin.mod.client.services;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.common.data.AnimationMetadata;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages animated textures (capes, future skin animations)
 * Tracks animation state and provides current frame for rendering
 */
@Environment(EnvType.CLIENT)
public class AnimatedTextureManager {

    private static AnimatedTextureManager instance;

    /**
     * Animation state for a single animated texture
     */
    private static class AnimationState {
        final ResourceLocation textureLocation;
        final AnimationMetadata metadata;
        final long startTime;

        AnimationState(ResourceLocation textureLocation, AnimationMetadata metadata) {
            this.textureLocation = textureLocation;
            this.metadata = metadata;
            this.startTime = System.currentTimeMillis();
        }

        /**
         * Get current frame index based on elapsed time
         */
        int getCurrentFrame() {
            long elapsed = System.currentTimeMillis() - startTime;
            return metadata.getFrameAtTime(elapsed);
        }

        /**
         * Get total frame count
         */
        int getFrameCount() {
            return metadata.frameCount();
        }
    }

    // Map of animation ID -> animation state
    private final Map<String, AnimationState> animations = new ConcurrentHashMap<>();

    private AnimatedTextureManager() {
        // Private constructor for singleton
    }

    public static AnimatedTextureManager getInstance() {
        if (instance == null) {
            instance = new AnimatedTextureManager();
        }
        return instance;
    }

    /**
     * Register an animated texture
     * @param animationId Unique ID for this animation (e.g., "cape_<hash>")
     * @param textureLocation Location of the atlas texture
     * @param metadata Animation metadata with frame timing
     */
    public void registerAnimation(String animationId, ResourceLocation textureLocation, AnimationMetadata metadata) {
        if (metadata == null || metadata.frameCount() == 0) {
            QuickSkin.LOGGER.warn("Cannot register animation with null or empty metadata: {}", animationId);
            return;
        }

        AnimationState state = new AnimationState(textureLocation, metadata);
        animations.put(animationId, state);

        QuickSkin.LOGGER.debug("Registered animation: {} with {} frames", animationId, metadata.frameCount());
    }

    /**
     * Unregister an animated texture
     */
    public void unregisterAnimation(String animationId) {
        AnimationState removed = animations.remove(animationId);
        if (removed != null) {
            QuickSkin.LOGGER.debug("Unregistered animation: {}", animationId);
        }
    }

    /**
     * Check if an animation is registered
     */
    public boolean isAnimated(String animationId) {
        return animations.containsKey(animationId);
    }

    /**
     * Get current frame index for an animation
     * @return Frame index, or 0 if animation not found
     */
    public int getCurrentFrame(String animationId) {
        AnimationState state = animations.get(animationId);
        if (state == null) {
            return 0;
        }
        return state.getCurrentFrame();
    }

    /**
     * Get frame count for an animation
     * @return Frame count, or 1 if animation not found
     */
    public int getFrameCount(String animationId) {
        AnimationState state = animations.get(animationId);
        if (state == null) {
            return 1;
        }
        return state.getFrameCount();
    }

    /**
     * Get texture location for an animation
     */
    public ResourceLocation getTextureLocation(String animationId) {
        AnimationState state = animations.get(animationId);
        if (state == null) {
            return null;
        }
        return state.textureLocation;
    }

    /**
     * Get animation metadata
     */
    public AnimationMetadata getMetadata(String animationId) {
        AnimationState state = animations.get(animationId);
        if (state == null) {
            return null;
        }
        return state.metadata;
    }

    /**
     * Clear all animations
     */
    public void clearAll() {
        QuickSkin.LOGGER.info("Clearing all animations ({})", animations.size());
        animations.clear();
    }

    /**
     * Get total number of registered animations
     */
    public int getAnimationCount() {
        return animations.size();
    }

    /**
     * Tick all animations (called each frame)
     * Currently animations are time-based, so this is a no-op
     * But kept for future manual frame advancement if needed
     */
    public void tick() {
        // Currently time-based, no per-frame work needed
        // Could add manual frame advancement here if needed
    }
}
