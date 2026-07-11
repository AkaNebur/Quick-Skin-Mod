package com.quickskin.mod.client.services;

import com.mojang.blaze3d.platform.NativeImage;
import com.quickskin.mod.common.data.AnimationMetadata;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.platform.MinecraftCompat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages animated textures (capes, future skin animations).
 * Tracks animation state and provides current frame for rendering.
 *
 * Optimized architecture: uses a SINGLE DynamicTexture per animation instead of
 * one per frame. The atlas pixel data is kept in RAM (NativeImage) and only the
 * current frame is uploaded to the GPU when it changes. This reduces VRAM usage
 * from O(frames) to O(1) per animation.
 */
@Environment(EnvType.CLIENT)
public class AnimatedTextureManager {

    private static AnimatedTextureManager instance;

    // Limits to prevent VRAM/RAM exhaustion from HD animated capes
    private static final int MAX_ANIM_FRAME_WIDTH = 512;
    private static final int MAX_ANIM_FRAME_HEIGHT = 256;
    private static final int MAX_ANIM_FRAMES = 128;

    /**
     * Animation state for a single animated texture.
     * Uses ONE GPU texture that is updated in-place when the frame changes.
     */
    private static class AnimationState {
        //? if <1.21.11 {
        final ResourceLocation originalAtlasLocation; // Atlas location from LocalAssetManager (for reverse lookup)
        final ResourceLocation frameTextureLocation;   // Single GPU texture location for this animation
        //?} else {
        final Identifier originalAtlasLocation; // Atlas location from LocalAssetManager (for reverse lookup)
        final Identifier frameTextureLocation;   // Single GPU texture location for this animation
        //?}
        final AnimationMetadata metadata;
        final long startTime;

        private final NativeImage atlasPixels;      // Full atlas in RAM (native memory, NOT on GPU)
        private final DynamicTexture frameTexture;  // Single GPU texture (wraps framePixels)
        private final int frameWidth;
        private final int frameHeight;
        private int currentFrame = 0;
        private float speedMultiplier;

        //? if <1.21.11 {
        AnimationState(String animationId, ResourceLocation atlasLocation,
        //?} else {
        AnimationState(String animationId, Identifier atlasLocation,
        //?}
                       NativeImage atlasPixels, int frameWidth, int frameHeight,
                       AnimationMetadata metadata, float speedMultiplier) {
            this.originalAtlasLocation = atlasLocation;
            this.metadata = metadata;
            this.startTime = System.currentTimeMillis();
            this.speedMultiplier = speedMultiplier;
            this.atlasPixels = atlasPixels;
            this.frameWidth = frameWidth;
            this.frameHeight = frameHeight;

            // Create frame-sized NativeImage for the GPU texture
            NativeImage framePixels = new NativeImage(frameWidth, frameHeight, false);
            copyFrameTo(framePixels, 0);
            //? if <1.21.11 {
            this.frameTexture = new DynamicTexture(framePixels);
            //?} else {
            this.frameTexture = new DynamicTexture(() -> "quickskin_anim_" + animationId, framePixels);
            //?}

            // Register single texture with a unique name
            String texId = "quickskin/animated/" + animationId.replaceAll("[^a-zA-Z0-9/._-]", "_");
            //? if <1.21.11 {
            this.frameTextureLocation = Minecraft.getInstance().getTextureManager()
                    .register(texId, frameTexture);
            //?} else {
            this.frameTextureLocation = Identifier.parse(texId);
            Minecraft.getInstance().getTextureManager().register(frameTextureLocation, frameTexture);
            //?}
        }

        /**
         * Copy a specific frame's pixels from the atlas to the target NativeImage.
         */
        private void copyFrameTo(NativeImage target, int frameIndex) {
            int srcY = frameIndex * frameHeight;
            for (int y = 0; y < frameHeight; y++) {
                for (int x = 0; x < frameWidth; x++) {
                    //? if <26.2 {
                    MinecraftCompat.INSTANCE.setPixel(
                            target, x, y, MinecraftCompat.INSTANCE.getPixel(atlasPixels, x, srcY + y));
                    //?} else {
                    MinecraftCompat.INSTANCE.setPixel(target, x, y, MinecraftCompat.INSTANCE.getPixel(atlasPixels, x, srcY + y));
                    //?}
                }
            }
        }

        /**
         * Tick the animation. If the frame changed, copies new frame pixels
         * to the GPU texture and uploads.
         */
        void tick() {
            if (metadata.frameCount() <= 1) {
                return;
            }

            long elapsed = System.currentTimeMillis() - startTime;
            long adjustedElapsed = (long) (elapsed * speedMultiplier);
            int newFrame = metadata.getFrameAtTime(adjustedElapsed);

            if (newFrame != currentFrame) {
                currentFrame = newFrame;
                // Update the DynamicTexture's backing NativeImage and re-upload to GPU
                NativeImage pixels = frameTexture.getPixels();
                if (pixels != null) {
                    copyFrameTo(pixels, currentFrame);
                    frameTexture.upload();
                }
            }
        }

        void setSpeedMultiplier(float speed) {
            this.speedMultiplier = speed;
        }

        //? if <1.21.11 {
        ResourceLocation getCurrentFrameTexture() {
        //?} else {
        Identifier getCurrentFrameTexture() {
        //?}
            return frameTextureLocation;
        }

        void cleanup() {
            // release() calls close() on the DynamicTexture, which frees framePixels + GL texture
            Minecraft.getInstance().getTextureManager().release(frameTextureLocation);
            // Free the atlas RAM
            atlasPixels.close();
        }
    }

    // Map of animation ID -> animation state
    private final Map<String, AnimationState> animations = new ConcurrentHashMap<>();
    // Reverse lookup: atlas texture location -> animation ID for O(1) getAnimationFrame()
    //? if <1.21.11 {
    private final Map<ResourceLocation, String> atlasToAnimId = new ConcurrentHashMap<>();
    //?} else {
    private final Map<Identifier, String> atlasToAnimId = new ConcurrentHashMap<>();
    //?}
    // Track animations currently being loaded asynchronously
    private final Set<String> pendingRegistrations = ConcurrentHashMap.newKeySet();

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
     * Register an animated texture synchronously.
     * Used by CapeService, PlayerAppearanceService, etc. when data is already loaded.
     */
    //? if <1.21.11 {
    public void registerAnimation(String animationId, String capeId, ResourceLocation textureLocation,
    //?} else {
    public void registerAnimation(String animationId, String capeId, Identifier textureLocation,
    //?}
                                  BufferedImage atlasImage, AnimationMetadata metadata) {
        if (metadata == null || metadata.frameCount() <= 1) {
            return;
        }

        // Defensive guard for legacy large capes imported before F1 caps existed
        {
            int fc = metadata.frameCount();
            int fw = atlasImage.getWidth();
            int fh = fc > 0 ? atlasImage.getHeight() / fc : atlasImage.getHeight();
            long decodedBytes = (long) fc * fw * fh * 4;
            if (decodedBytes > 64L * 1024 * 1024) {
                long mb = decodedBytes / (1024 * 1024);
                org.slf4j.LoggerFactory.getLogger(AnimatedTextureManager.class).warn(
                    "[QuickSkin] Animated cape '{}' would require {} MB decoded — skipping frame pre-upload. Re-import to apply size limits. Cape will display as static first frame.", animationId, mb);
                return;
            }
        }

        // If an old animation exists, clean it up first
        unregisterAnimation(animationId);
        pendingRegistrations.remove(animationId);

        // Process and commit on the current thread
        NativeImage atlasPixels = processAtlas(atlasImage, metadata);
        if (atlasPixels == null) return;

        int effectiveFrameCount = Math.min(metadata.frameCount(), MAX_ANIM_FRAMES);
        int targetWidth = Math.min(atlasImage.getWidth(), MAX_ANIM_FRAME_WIDTH);
        int srcFrameHeight = atlasImage.getHeight() / metadata.frameCount();
        int targetHeight = Math.min(srcFrameHeight, MAX_ANIM_FRAME_HEIGHT);

        AnimationMetadata effectiveMeta = metadata;
        if (effectiveFrameCount < metadata.frameCount()) {
            effectiveMeta = new AnimationMetadata(
                    metadata.frames().subList(0, effectiveFrameCount), effectiveFrameCount);
        }

        float speedMultiplier = ClientConfig.getInstance().getCapeAnimationSpeed(capeId);
        commitAnimation(animationId, textureLocation, atlasPixels, targetWidth, targetHeight, effectiveMeta, speedMultiplier);
    }

    /**
     * Register an animated texture asynchronously.
     * Performs disk I/O and pixel conversion on a background thread,
     * then commits the GL resources on the main thread.
     * The static first-frame texture is shown until the animation is ready.
     *
     * @param animationId     Unique animation ID
     * @param capeId          Cape ID for speed settings
     * @param textureLocation Atlas texture location (reverse lookup key)
     * @param hash            Asset hash for loading from LocalAssetManager
     */
    public void registerAnimationAsync(String animationId, String capeId,
                                       //? if <1.21.11 {
                                       ResourceLocation textureLocation, String hash) {
                                       //?} else {
                                       Identifier textureLocation, String hash) {
                                       //?}
        if (animations.containsKey(animationId) || pendingRegistrations.contains(animationId)) {
            return;
        }
        pendingRegistrations.add(animationId);

        CompletableFuture.runAsync(() -> {
            NativeImage atlasPixels = null;
            try {
                // Background thread: disk I/O + pixel conversion
                AnimationMetadata metadata = LocalAssetManager.getInstance().getAnimationMetadata(hash);
                BufferedImage atlasImage = LocalAssetManager.getInstance().getSourceImage(hash);

                if (metadata == null || atlasImage == null || metadata.frameCount() <= 1) {
                    pendingRegistrations.remove(animationId);
                    return;
                }

                atlasPixels = processAtlas(atlasImage, metadata);
                if (atlasPixels == null) {
                    pendingRegistrations.remove(animationId);
                    return;
                }

                int effectiveFrameCount = Math.min(metadata.frameCount(), MAX_ANIM_FRAMES);
                int targetWidth = Math.min(atlasImage.getWidth(), MAX_ANIM_FRAME_WIDTH);
                int srcFrameHeight = atlasImage.getHeight() / metadata.frameCount();
                int targetHeight = Math.min(srcFrameHeight, MAX_ANIM_FRAME_HEIGHT);

                AnimationMetadata effectiveMeta = metadata;
                if (effectiveFrameCount < metadata.frameCount()) {
                    effectiveMeta = new AnimationMetadata(
                            metadata.frames().subList(0, effectiveFrameCount), effectiveFrameCount);
                }

                float speedMultiplier = ClientConfig.getInstance().getCapeAnimationSpeed(capeId);

                // Capture for lambda
                final NativeImage finalAtlas = atlasPixels;
                final int fw = targetWidth, fh = targetHeight;
                final AnimationMetadata fm = effectiveMeta;
                final float sm = speedMultiplier;

                // Main thread: GL operations (DynamicTexture creation + register)
                Minecraft.getInstance().execute(() -> {
                    try {
                        // Skip if a sync registration happened while we were loading
                        if (animations.containsKey(animationId)) {
                            finalAtlas.close();
                            return;
                        }
                        commitAnimation(animationId, textureLocation, finalAtlas, fw, fh, fm, sm);
                    } catch (Exception e) {
                        finalAtlas.close();
                    } finally {
                        pendingRegistrations.remove(animationId);
                    }
                });
                atlasPixels = null; // Ownership transferred to execute() callback

            } catch (Exception e) {
                if (atlasPixels != null) {
                    atlasPixels.close();
                }
                pendingRegistrations.remove(animationId);
            }
        });
    }

    /**
     * Process atlas image: apply resolution/frame limits and convert to NativeImage.
     * Safe to call from any thread.
     */
    private static NativeImage processAtlas(BufferedImage atlasImage, AnimationMetadata metadata) {
        int effectiveFrameCount = Math.min(metadata.frameCount(), MAX_ANIM_FRAMES);
        int srcFrameWidth = atlasImage.getWidth();
        int srcFrameHeight = atlasImage.getHeight() / metadata.frameCount();

        boolean needsDownscale = srcFrameWidth > MAX_ANIM_FRAME_WIDTH || srcFrameHeight > MAX_ANIM_FRAME_HEIGHT;
        boolean needsTruncate = effectiveFrameCount < metadata.frameCount();

        int targetWidth = Math.min(srcFrameWidth, MAX_ANIM_FRAME_WIDTH);
        int targetHeight = Math.min(srcFrameHeight, MAX_ANIM_FRAME_HEIGHT);

        if (needsDownscale || needsTruncate) {
            BufferedImage processedAtlas = new BufferedImage(
                    targetWidth, targetHeight * effectiveFrameCount, BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = processedAtlas.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            for (int i = 0; i < effectiveFrameCount; i++) {
                g.drawImage(atlasImage,
                        0, i * targetHeight, targetWidth, (i + 1) * targetHeight,
                        0, i * srcFrameHeight, srcFrameWidth, (i + 1) * srcFrameHeight,
                        null);
            }
            g.dispose();
            return convertToNativeImage(processedAtlas);
        } else {
            return convertToNativeImage(atlasImage);
        }
    }

    /**
     * Commit a prepared animation to the maps and create GL resources.
     * Must be called on the main/render thread.
     */
    //? if <1.21.11 {
    private void commitAnimation(String animationId, ResourceLocation textureLocation,
    //?} else {
    private void commitAnimation(String animationId, Identifier textureLocation,
    //?}
                                 NativeImage atlasPixels, int frameWidth, int frameHeight,
                                 AnimationMetadata metadata, float speedMultiplier) {
        AnimationState state = new AnimationState(
                animationId, textureLocation, atlasPixels,
                frameWidth, frameHeight, metadata, speedMultiplier);
        animations.put(animationId, state);
        atlasToAnimId.put(textureLocation, animationId);
    }

    /**
     * Clear all animations (for texture cache reload)
     */
    public void clearAnimations() {
        pendingRegistrations.clear();
        for (AnimationState state : animations.values()) {
            state.cleanup();
        }
        animations.clear();
        atlasToAnimId.clear();
    }

    /**
     * Unregister an animated texture
     */
    public void unregisterAnimation(String animationId) {
        pendingRegistrations.remove(animationId);
        AnimationState removed = animations.remove(animationId);
        if (removed != null) {
            atlasToAnimId.remove(removed.originalAtlasLocation);
            removed.cleanup();
        }
    }

    /**
     * Update the animation speed for a registered animation
     */
    public void setAnimationSpeed(String animationId, float speed) {
        AnimationState state = animations.get(animationId);
        if (state != null) {
            state.setSpeedMultiplier(speed);
        }
    }

    /**
     * Check if an animation is registered or currently being loaded asynchronously.
     * Callers use this to avoid redundant registration attempts.
     */
    public boolean isAnimated(String animationId) {
        return animations.containsKey(animationId) || pendingRegistrations.contains(animationId);
    }

    /**
     * Gets the Identifier for the current frame of an animation.
     * With the optimized architecture, this always returns the same Identifier
     * (the texture is updated in-place via upload()).
     */
    @Nullable
    //? if <1.21.11 {
    public ResourceLocation getCurrentFrameTexture(String animationId) {
    //?} else {
    public Identifier getCurrentFrameTexture(String animationId) {
    //?}
        AnimationState state = animations.get(animationId);
        if (state != null) {
            return state.getCurrentFrameTexture();
        }
        return null;
    }

    /**
     * Get the original atlas texture location for an animation
     */
    //? if <1.21.11 {
    public ResourceLocation getTextureLocation(String animationId) {
    //?} else {
    public Identifier getTextureLocation(String animationId) {
    //?}
        AnimationState state = animations.get(animationId);
        if (state == null) {
            return null;
        }
        return state.originalAtlasLocation;
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
     * returns the Identifier of the current animation frame.
     * Uses O(1) reverse lookup instead of iterating all animations.
     */
    //? if <1.21.11 {
    public Optional<ResourceLocation> getAnimationFrame(ResourceLocation atlasLocation) {
    //?} else {
    public Optional<Identifier> getAnimationFrame(Identifier atlasLocation) {
    //?}
        if (atlasLocation == null) {
            return Optional.empty();
        }

        // O(1) reverse lookup
        String animId = atlasToAnimId.get(atlasLocation);
        if (animId == null) {
            return Optional.empty();
        }

        AnimationState state = animations.get(animId);
        if (state == null) {
            return Optional.empty();
        }

        return Optional.of(state.getCurrentFrameTexture());
    }

    /**
     * Tick all animations (called each game tick).
     * Only uploads to GPU when the frame actually changes.
     */
    public void tick() {
        for (AnimationState state : animations.values()) {
            state.tick();
        }
    }

    /**
     * Convert a BufferedImage to NativeImage using direct pixel copy.
     * Avoids the expensive PNG encode/decode round-trip.
     * Safe to call from any thread.
     */
    private static NativeImage convertToNativeImage(BufferedImage bufferedImage) {
        int width = bufferedImage.getWidth();
        int height = bufferedImage.getHeight();
        NativeImage nativeImage = new NativeImage(width, height, false);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = bufferedImage.getRGB(x, y);
                // Convert ARGB to ABGR (NativeImage pixel format)
                int a = (argb >> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                int abgr = (a << 24) | (b << 16) | (g << 8) | r;
                MinecraftCompat.INSTANCE.setPixel(nativeImage, x, y, abgr);
            }
        }

        return nativeImage;
    }
}
