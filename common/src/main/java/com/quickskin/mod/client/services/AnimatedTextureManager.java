package com.quickskin.mod.client.services;

import com.quickskin.mod.client.concurrent.ClientIoExecutor;
import com.mojang.blaze3d.platform.NativeImage;
import com.quickskin.mod.QuickSkin;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

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
    private static final int MAX_ANIM_FRAMES = 256;
    private static final int MAX_ANIMATIONS = 32;
    private static final long MAX_RETAINED_ATLAS_BYTES = 128L * 1024L * 1024L;
    private static final long MAX_FRAME_PIXELS_PER_TICK =
            4L * MAX_ANIM_FRAME_WIDTH * MAX_ANIM_FRAME_HEIGHT;

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

            // Build the native/GPU resource transactionally so constructor failure cannot leak it.
            NativeImage framePixels = new NativeImage(frameWidth, frameHeight, false);
            DynamicTexture createdTexture = null;
            //? if <1.21.11 {
            ResourceLocation createdLocation = null;
            //?} else {
            Identifier createdLocation = null;
            //?}
            boolean registered = false;
            try {
                copyFrameTo(framePixels, 0);
                //? if <1.21.11 {
                createdTexture = new DynamicTexture(framePixels);
                //?} else {
                createdTexture = new DynamicTexture(
                        () -> "quickskin_anim_" + animationId, framePixels);
                //?}

                String texId = "quickskin/animated/"
                        + animationId.replaceAll("[^a-zA-Z0-9/._-]", "_");
                //? if <1.21.11 {
                createdLocation = Minecraft.getInstance().getTextureManager()
                        .register(texId, createdTexture);
                //?} else {
                createdLocation = Identifier.parse(texId);
                Minecraft.getInstance().getTextureManager().register(
                        createdLocation, createdTexture);
                //?}
                registered = true;
            } catch (RuntimeException | LinkageError error) {
                if (registered && createdLocation != null) {
                    try {
                        Minecraft.getInstance().getTextureManager().release(createdLocation);
                    } catch (RuntimeException ignored) {
                        if (createdTexture != null) createdTexture.close();
                    }
                } else if (createdTexture != null) {
                    createdTexture.close();
                } else {
                    framePixels.close();
                }
                throw error;
            }
            this.frameTexture = createdTexture;
            this.frameTextureLocation = createdLocation;
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
        long tick(long availablePixels) {
            if (metadata.frameCount() <= 1) {
                return 0L;
            }

            long elapsed = System.currentTimeMillis() - startTime;
            long adjustedElapsed = (long) (elapsed * speedMultiplier);
            int newFrame = metadata.getFrameAtTime(adjustedElapsed);

            if (newFrame != currentFrame) {
                long updatePixels = (long) frameWidth * frameHeight;
                if (updatePixels > availablePixels) return 0L;
                currentFrame = newFrame;
                // Update the DynamicTexture's backing NativeImage and re-upload to GPU
                NativeImage pixels = frameTexture.getPixels();
                if (pixels != null) {
                    copyFrameTo(pixels, currentFrame);
                    frameTexture.upload();
                    return updatePixels;
                }
            }
            return 0L;
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
            try {
                // release() closes the DynamicTexture, freeing framePixels + its GL texture.
                try {
                    Minecraft.getInstance().getTextureManager().release(frameTextureLocation);
                } catch (RuntimeException | LinkageError releaseError) {
                    try {
                        frameTexture.close();
                    } catch (RuntimeException | LinkageError closeError) {
                        releaseError.addSuppressed(closeError);
                    }
                    throw releaseError;
                }
            } finally {
                // Always free the atlas even when the texture manager rejects the release.
                atlasPixels.close();
            }
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
    // Token each async load so cleanup/re-registration cannot let an old session commit later.
    private final Map<String, Long> pendingRegistrations = new ConcurrentHashMap<>();
    private final AtomicLong registrationSequence = new AtomicLong();
    private final AtomicLong retainedPendingSourceBytes = new AtomicLong();
    private long retainedAtlasBytes;
    private int tickCursor;

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
     * Compatibility entry point. Conversion is always delegated to the bounded worker.
     */
    //? if <1.21.11 {
    public void registerAnimation(String animationId, String capeId, ResourceLocation textureLocation,
    //?} else {
    public void registerAnimation(String animationId, String capeId, Identifier textureLocation,
    //?}
                                  BufferedImage atlasImage, AnimationMetadata metadata) {
        registerAnimationAsync(
                animationId, capeId, textureLocation, atlasImage, metadata);
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
        registerAnimationAsyncInternal(animationId, capeId, textureLocation, () ->
                new AnimationSource(
                        LocalAssetManager.getInstance().getSourceImage(hash),
                        LocalAssetManager.getInstance().getAnimationMetadata(hash)), 0L);
    }

    //? if <1.21.11 {
    public void registerAnimationAsync(String animationId, String capeId,
                                       ResourceLocation textureLocation, BufferedImage atlasImage,
                                       AnimationMetadata metadata) {
    //?} else {
    public void registerAnimationAsync(String animationId, String capeId,
                                       Identifier textureLocation, BufferedImage atlasImage,
                                       AnimationMetadata metadata) {
    //?}
        if (!isValidAnimationAtlas(atlasImage, metadata)) return;
        AnimationSource source = new AnimationSource(atlasImage, copyMetadata(metadata));
        registerAnimationAsyncInternal(
                animationId, capeId, textureLocation, () -> source,
                decodedBytes(atlasImage));
    }

    private record AnimationSource(BufferedImage atlasImage, AnimationMetadata metadata) {
    }

    //? if <1.21.11 {
    private void registerAnimationAsyncInternal(
            String animationId, String capeId, ResourceLocation textureLocation,
            Supplier<AnimationSource> sourceSupplier, long retainedSourceBytes) {
    //?} else {
    private void registerAnimationAsyncInternal(
            String animationId, String capeId, Identifier textureLocation,
            Supplier<AnimationSource> sourceSupplier, long retainedSourceBytes) {
    //?}
        if (retainedSourceBytes < 0 || retainedSourceBytes > 64L * 1024L * 1024L
                || (retainedSourceBytes > 0 && !reservePendingSourceBytes(retainedSourceBytes))) {
            return;
        }
        AtomicLong reservedBytes = new AtomicLong(retainedSourceBytes);
        AtomicBoolean reservationReleased = new AtomicBoolean();
        AtomicBoolean mainThreadHandoff = new AtomicBoolean();
        Runnable releaseReservation = () -> {
            if (reservationReleased.compareAndSet(false, true)) {
                retainedPendingSourceBytes.addAndGet(-reservedBytes.get());
            }
        };
        long registrationToken;
        synchronized (this) {
            if (animations.containsKey(animationId)
                    || pendingRegistrations.containsKey(animationId)
                    || animations.size() + pendingRegistrations.size() >= MAX_ANIMATIONS) {
                releaseReservation.run();
                return;
            }
            registrationToken = registrationSequence.incrementAndGet();
            pendingRegistrations.put(animationId, registrationToken);
        }

        ClientIoExecutor.runAsync(() -> {
            NativeImage atlasPixels = null;
            try {
                if (!Long.valueOf(registrationToken).equals(
                        pendingRegistrations.get(animationId))) return;
                // Background thread: disk I/O + pixel conversion
                AnimationSource source = sourceSupplier.get();
                AnimationMetadata metadata = source != null ? source.metadata() : null;
                BufferedImage atlasImage = source != null ? source.atlasImage() : null;

                if (!isValidAnimationAtlas(atlasImage, metadata)) {
                    pendingRegistrations.remove(animationId, registrationToken);
                    return;
                }
                if (!Long.valueOf(registrationToken).equals(
                        pendingRegistrations.get(animationId))) return;
                if (reservedBytes.get() == 0L) {
                    long loadedSourceBytes = decodedBytes(atlasImage);
                    if (!reservePendingSourceBytes(loadedSourceBytes)) {
                        pendingRegistrations.remove(animationId, registrationToken);
                        return;
                    }
                    reservedBytes.set(loadedSourceBytes);
                }
                metadata = copyMetadata(metadata);

                if (!Long.valueOf(registrationToken).equals(
                        pendingRegistrations.get(animationId))) return;
                atlasPixels = processAtlas(atlasImage, metadata);
                if (atlasPixels == null) {
                    pendingRegistrations.remove(animationId, registrationToken);
                    return;
                }

                if (!Long.valueOf(registrationToken).equals(pendingRegistrations.get(animationId))) {
                    atlasPixels.close();
                    atlasPixels = null;
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
                if (!Float.isFinite(speedMultiplier)) speedMultiplier = 1.0f;

                // Capture for lambda
                final NativeImage finalAtlas = atlasPixels;
                final int fw = targetWidth, fh = targetHeight;
                final AnimationMetadata fm = effectiveMeta;
                final float sm = speedMultiplier;

                // Main thread: GL operations (DynamicTexture creation + register)
                Minecraft.getInstance().execute(() -> {
                    try {
                        // Skip if a sync registration happened while we were loading
                        if (!Long.valueOf(registrationToken).equals(
                                pendingRegistrations.get(animationId))
                                || animations.containsKey(animationId)) {
                            finalAtlas.close();
                            return;
                        }
                        commitAnimation(animationId, textureLocation, finalAtlas, fw, fh, fm, sm);
                    } catch (RuntimeException | LinkageError e) {
                        finalAtlas.close();
                    } finally {
                        pendingRegistrations.remove(animationId, registrationToken);
                        releaseReservation.run();
                    }
                });
                atlasPixels = null; // Ownership transferred to execute() callback
                mainThreadHandoff.set(true);

            } catch (RuntimeException | LinkageError e) {
                if (atlasPixels != null) {
                    atlasPixels.close();
                    atlasPixels = null;
                }
                pendingRegistrations.remove(animationId, registrationToken);
            } finally {
                if (!mainThreadHandoff.get() && atlasPixels != null) {
                    atlasPixels.close();
                }
                if (!mainThreadHandoff.get()) releaseReservation.run();
            }
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                releaseReservation.run();
                pendingRegistrations.remove(animationId, registrationToken);
                QuickSkin.LOGGER.warn("Unable to schedule animated texture {}", animationId, error);
            }
        });
    }

    /**
     * Process atlas image: apply resolution/frame limits and convert to NativeImage.
     * Safe to call from any thread.
     */
    private static boolean isValidAnimationAtlas(
            BufferedImage atlasImage, AnimationMetadata metadata) {
        if (atlasImage == null || metadata == null || metadata.frames() == null) return false;
        int frameCount = metadata.frameCount();
        if (frameCount <= 1 || frameCount > MAX_ANIM_FRAMES
                || metadata.frames().size() != frameCount
                || atlasImage.getWidth() < 1 || atlasImage.getHeight() < frameCount
                || atlasImage.getHeight() % frameCount != 0
                || (long) atlasImage.getWidth() * atlasImage.getHeight() * 4L
                        > 64L * 1024L * 1024L) {
            return false;
        }
        boolean[] indexes = new boolean[frameCount];
        for (AnimationMetadata.FrameData frame : metadata.frames()) {
            if (frame == null || frame.delay() < 20 || frame.delay() > 60_000
                    || frame.index() < 0 || frame.index() >= frameCount
                    || indexes[frame.index()]) {
                return false;
            }
            indexes[frame.index()] = true;
        }
        return true;
    }

    private static long decodedBytes(BufferedImage atlasImage) {
        return atlasImage == null ? 0L
                : (long) atlasImage.getWidth() * atlasImage.getHeight() * 4L;
    }

    private boolean reservePendingSourceBytes(long bytes) {
        if (bytes <= 0L || bytes > 64L * 1024L * 1024L) return false;
        long current;
        do {
            current = retainedPendingSourceBytes.get();
            if (current > MAX_RETAINED_ATLAS_BYTES - bytes) return false;
        } while (!retainedPendingSourceBytes.compareAndSet(current, current + bytes));
        return true;
    }

    private static AnimationMetadata copyMetadata(AnimationMetadata metadata) {
        return new AnimationMetadata(List.copyOf(metadata.frames()), metadata.frameCount());
    }

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
    private synchronized void commitAnimation(String animationId, ResourceLocation textureLocation,
    //?} else {
    private synchronized void commitAnimation(String animationId, Identifier textureLocation,
    //?}
                                 NativeImage atlasPixels, int frameWidth, int frameHeight,
                                 AnimationMetadata metadata, float speedMultiplier) {
        long atlasBytes = (long) atlasPixels.getWidth() * atlasPixels.getHeight() * 4L;
        if (animations.containsKey(animationId) || atlasToAnimId.containsKey(textureLocation)
                || animations.size() >= MAX_ANIMATIONS
                || atlasBytes <= 0 || retainedAtlasBytes + atlasBytes > MAX_RETAINED_ATLAS_BYTES) {
            atlasPixels.close();
            return;
        }
        AnimationState state = new AnimationState(
                animationId, textureLocation, atlasPixels,
                frameWidth, frameHeight, metadata, speedMultiplier);
        boolean committed = false;
        try {
            animations.put(animationId, state);
            atlasToAnimId.put(textureLocation, animationId);
            retainedAtlasBytes += atlasBytes;
            committed = true;
        } finally {
            if (!committed) {
                animations.remove(animationId, state);
                atlasToAnimId.remove(textureLocation, animationId);
                try {
                    state.cleanup();
                } catch (RuntimeException | LinkageError cleanupError) {
                    QuickSkin.LOGGER.warn("Unable to roll back animated texture {}", animationId,
                            cleanupError);
                }
            }
        }
    }

    /**
     * Clear all animations (for texture cache reload)
     */
    public synchronized void clearAnimations() {
        pendingRegistrations.clear();
        for (AnimationState state : animations.values()) {
            try {
                state.cleanup();
            } catch (RuntimeException | LinkageError error) {
                QuickSkin.LOGGER.warn("Unable to release an animated texture", error);
            }
        }
        animations.clear();
        atlasToAnimId.clear();
        retainedAtlasBytes = 0;
    }

    /**
     * Unregister an animated texture
     */
    public synchronized void unregisterAnimation(String animationId) {
        pendingRegistrations.remove(animationId);
        AnimationState removed = animations.remove(animationId);
        if (removed != null) {
            atlasToAnimId.remove(removed.originalAtlasLocation);
            retainedAtlasBytes = Math.max(0L, retainedAtlasBytes
                    - (long) removed.atlasPixels.getWidth() * removed.atlasPixels.getHeight() * 4L);
            try {
                removed.cleanup();
            } catch (RuntimeException | LinkageError error) {
                QuickSkin.LOGGER.warn("Unable to release animated texture {}", animationId, error);
            }
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
        return animations.containsKey(animationId) || pendingRegistrations.containsKey(animationId);
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
        List<AnimationState> snapshot = List.copyOf(animations.values());
        if (snapshot.isEmpty()) {
            tickCursor = 0;
            return;
        }
        int start = Math.floorMod(tickCursor, snapshot.size());
        int visited = 0;
        long remainingPixels = MAX_FRAME_PIXELS_PER_TICK;
        while (visited < snapshot.size() && remainingPixels > 0L) {
            AnimationState state = snapshot.get((start + visited) % snapshot.size());
            remainingPixels -= state.tick(remainingPixels);
            visited++;
        }
        tickCursor = (start + Math.max(1, visited)) % snapshot.size();
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
        try {
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
        } catch (RuntimeException | LinkageError error) {
            nativeImage.close();
            throw error;
        }
    }
}
