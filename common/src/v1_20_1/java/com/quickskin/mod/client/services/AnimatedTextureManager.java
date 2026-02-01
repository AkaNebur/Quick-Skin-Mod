package com.quickskin.mod.client.services;

import com.mojang.blaze3d.platform.NativeImage;
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.common.data.AnimationMetadata;
import com.quickskin.mod.config.ClientConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.Optional;
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
        final ResourceLocation atlasTextureLocation;
        final AnimationMetadata metadata;
        final long startTime;

        private final DynamicTexture[] frameTextures;
        private final ResourceLocation[] frameResourceLocations;
        private int currentFrame = 0;
        private float speedMultiplier; // Per-animation speed multiplier

        AnimationState(String animationId, ResourceLocation textureLocation, BufferedImage atlasImage, AnimationMetadata metadata, float speedMultiplier) {
            this.atlasTextureLocation = textureLocation;
            this.metadata = metadata;
            this.startTime = System.currentTimeMillis();
            this.speedMultiplier = speedMultiplier;

            this.frameTextures = new DynamicTexture[metadata.frameCount()];
            this.frameResourceLocations = new ResourceLocation[metadata.frameCount()];
            loadFrames(animationId, atlasImage);
        }

        private void loadFrames(String animationId, BufferedImage atlasImage) {
            try {
                if (atlasImage == null) {
                    QuickSkin.LOGGER.error("Could not read atlas image for animation: {}", atlasTextureLocation);
                    return;
                }

                int frameWidth = atlasImage.getWidth();
                int frameHeight = (metadata.frameCount() > 0) ? atlasImage.getHeight() / metadata.frameCount() : atlasImage.getHeight();

                for (int i = 0; i < metadata.frameCount(); i++) {
                    BufferedImage frameImage = atlasImage.getSubimage(0, i * frameHeight, frameWidth, frameHeight);
                    NativeImage nativeImage = convertToNativeImage(frameImage);

                    frameTextures[i] = new DynamicTexture(nativeImage);
                    // Create a unique name for the frame texture to avoid conflicts
                    String frameId = "quickskin/animated/" + animationId.replaceAll("[^a-zA-Z0-9/._-]", "_") + "_frame_" + i;
                    frameResourceLocations[i] = Minecraft.getInstance().getTextureManager().register(frameId, frameTextures[i]);
                }
            } catch (Exception e) {
                QuickSkin.LOGGER.error("Failed to load and slice animation frames for {}", atlasTextureLocation, e);
            }
        }

        private NativeImage convertToNativeImage(BufferedImage bufferedImage) {
            int width = bufferedImage.getWidth();
            int height = bufferedImage.getHeight();
            NativeImage nativeImage = new NativeImage(width, height, true);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int argb = bufferedImage.getRGB(x, y);
                    int a = (argb >> 24) & 0xFF;
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    int abgr = (a << 24) | (b << 16) | (g << 8) | r;
                    nativeImage.setPixelRGBA(x, y, abgr);
                }
            }
            return nativeImage;
        }

        void tick() {
            if (metadata.frameCount() <= 1) {
                currentFrame = 0;
                return;
            }

            long elapsed = System.currentTimeMillis() - startTime;
            // Apply per-animation speed multiplier
            long adjustedElapsed = (long)(elapsed * speedMultiplier);
            currentFrame = metadata.getFrameAtTime(adjustedElapsed);
        }

        /**
         * Update the speed multiplier for this animation
         */
        void setSpeedMultiplier(float speed) {
            this.speedMultiplier = speed;
        }

        ResourceLocation getCurrentFrameTexture() {
            if (frameResourceLocations == null || currentFrame < 0 || currentFrame >= frameResourceLocations.length || frameResourceLocations[currentFrame] == null) {
                return atlasTextureLocation; // Fallback to full atlas
            }
            return frameResourceLocations[currentFrame];
        }

        void cleanup() {
            for (ResourceLocation rl : frameResourceLocations) {
                if (rl != null) {
                    Minecraft.getInstance().getTextureManager().release(rl);
                }
            }
            for (DynamicTexture tex : frameTextures) {
                if (tex != null) {
                    tex.close();
                }
            }
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
     * @param capeId The cape ID (e.g., "local_cape:hash" or "known:cape_name") for loading speed settings
     * @param textureLocation Location of the atlas texture
     * @param atlasImage The BufferedImage of the atlas
     * @param metadata Animation metadata with frame timing
     */
    public void registerAnimation(String animationId, String capeId, ResourceLocation textureLocation, BufferedImage atlasImage, AnimationMetadata metadata) {
        if (metadata == null || metadata.frameCount() <= 1) {
            QuickSkin.LOGGER.warn("Cannot register animation with invalid metadata (frameCount <= 1): {}", animationId);
            return;
        }

        // If an old animation exists, clean it up first.
        unregisterAnimation(animationId);

        // Load the speed for this specific cape from config
        float speedMultiplier = ClientConfig.getInstance().getCapeAnimationSpeed(capeId);

        AnimationState state = new AnimationState(animationId, textureLocation, atlasImage, metadata, speedMultiplier);
        animations.put(animationId, state);

        QuickSkin.LOGGER.debug("Registered animation: {} with {} frames at speed {}x", animationId, metadata.frameCount(), speedMultiplier);
    }

    /**
     * Clear all animations (for texture cache reload)
     */
    public void clearAnimations() {
        QuickSkin.LOGGER.info("Clearing all animated texture registrations");
        for (AnimationState state : animations.values()) {
            state.cleanup();
        }
        animations.clear();
    }

    /**
     * Unregister an animated texture
     */
    public void unregisterAnimation(String animationId) {
        AnimationState removed = animations.remove(animationId);
        if (removed != null) {
            removed.cleanup();
            QuickSkin.LOGGER.debug("Unregistered and cleaned up animation: {}", animationId);
        }
    }

    /**
     * Update the animation speed for a registered animation
     * @param animationId The animation ID
     * @param speed The new speed multiplier
     */
    public void setAnimationSpeed(String animationId, float speed) {
        AnimationState state = animations.get(animationId);
        if (state != null) {
            state.setSpeedMultiplier(speed);
            QuickSkin.LOGGER.debug("Updated animation speed for {}: {}x", animationId, speed);
        }
    }

    /**
     * Check if an animation is registered
     */
    public boolean isAnimated(String animationId) {
        return animations.containsKey(animationId);
    }

    /**
     * Gets the ResourceLocation for the current frame of an animation.
     * @param animationId The ID of the animation.
     * @return The ResourceLocation for the current frame's texture, or null if the animation is not found.
     */
    @Nullable
    public ResourceLocation getCurrentFrameTexture(String animationId) {
        AnimationState state = animations.get(animationId);
        if (state != null) {
            return state.getCurrentFrameTexture();
        }
        return null;
    }

    /**
     * Get texture location for an animation
     */
    public ResourceLocation getTextureLocation(String animationId) {
        AnimationState state = animations.get(animationId);
        if (state == null) {
            return null;
        }
        return state.atlasTextureLocation;
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
     * Checks if a given texture atlas corresponds to a running animation, and if so,
     * returns the ResourceLocation of the current animation frame.
     *
     * @param atlasLocation The ResourceLocation of the static texture atlas.
     * @return An Optional containing the current frame's ResourceLocation if animated, otherwise an empty Optional.
     */
    public Optional<ResourceLocation> getAnimationFrame(ResourceLocation atlasLocation) {
        if (atlasLocation == null) {
            return Optional.empty();
        }

        // Find which, if any, running animation is using this atlas texture.
        for (AnimationState state : animations.values()) {
            if (atlasLocation.equals(state.atlasTextureLocation)) {
                // We found a match! Return the texture of the current frame.
                return Optional.ofNullable(state.getCurrentFrameTexture());
            }
        }

        // No running animation found for this atlas.
        return Optional.empty();
    }

    /**
     * Tick all animations (called each frame)
     */
    public void tick() {
        for (AnimationState state : animations.values()) {
            state.tick();
        }
    }
}