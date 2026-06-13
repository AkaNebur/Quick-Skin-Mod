package com.quickskin.mod.common.util;

import com.mojang.blaze3d.platform.NativeImage;
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.common.data.AnimationMetadata;
import com.quickskin.mod.platform.PlatformHelper;
import org.lwjgl.PointerBuffer;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * GIF loader using LWJGL's STB Image library
 * Provides native, high-performance GIF decoding
 */
public class StbGifLoader {

    /**
     * Result of GIF loading
     */
    public record GifLoadResult(
        NativeImage[] frames,         // Array of NativeImage frames
        AnimationMetadata metadata,   // Animation frame timing
        int frameWidth,               // Width of a single frame
        int frameHeight               // Height of a single frame
    ) {
        /**
         * Clean up all frames when done
         */
        public void close() {
            if (frames != null) {
                for (NativeImage frame : frames) {
                    if (frame != null) {
                        frame.close();
                    }
                }
            }
        }
    }

    /**
     * Load GIF file using STB Image
     * @param input GIF file input stream
     * @return Loading result with frames and metadata
     */
    public static GifLoadResult loadGif(InputStream input) throws IOException {
        ByteBuffer gifData = null;
        ByteBuffer imageData = null;
        PointerBuffer delaysBuffer;

        try {
            // Read entire GIF into byte buffer
            byte[] gifBytes = input.readAllBytes();
            gifData = MemoryUtil.memAlloc(gifBytes.length);
            gifData.put(gifBytes);
            gifData.flip();

            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer widthBuffer = stack.mallocInt(1);
                IntBuffer heightBuffer = stack.mallocInt(1);
                IntBuffer framesBuffer = stack.mallocInt(1);
                IntBuffer channelsBuffer = stack.mallocInt(1);

                // Allocate buffer for frame delays pointer (will be filled by STB)
                delaysBuffer = stack.mallocPointer(1);

                // Load GIF with STB Image
                // Request RGBA format (4 channels)
                imageData = STBImage.stbi_load_gif_from_memory(
                    gifData,
                    delaysBuffer,
                    widthBuffer,
                    heightBuffer,
                    framesBuffer,
                    channelsBuffer,
                    4  // Request RGBA
                );

                if (imageData == null) {
                    String error = STBImage.stbi_failure_reason();
                    throw new IOException("Failed to load GIF: " + error);
                }

                int width = widthBuffer.get(0);
                int height = heightBuffer.get(0);
                int frameCount = framesBuffer.get(0);

                if (frameCount == 0) {
                    throw new IOException("GIF has no frames");
                }

                // Hard input caps — reject oversized GIFs before any pixel work
                if (width > 2048) {
                    throw new IOException("Animated cape too large: width " + width + " exceeds maximum 2048 pixels.");
                }
                if (frameCount > 256) {
                    throw new IOException("Animated cape too large: " + frameCount + " frames exceeds maximum 256.");
                }
                long decodedBytes = (long) frameCount * width * height * 4;
                long maxBytes = 64L * 1024 * 1024;
                if (decodedBytes > maxBytes) {
                    long decodedMb = decodedBytes / (1024 * 1024);
                    throw new IOException("Animated cape too large. Maximum 64 MB when decoded (frames x width x height x 4). Yours is " + decodedMb + " MB. Try reducing resolution or frame count.");
                }


                // Get the delays IntBuffer from the pointer
                long delaysPtr = delaysBuffer.get(0);
                IntBuffer delays = MemoryUtil.memIntBuffer(delaysPtr, frameCount);

                // Extract frame delays and create metadata
                List<AnimationMetadata.FrameData> frameDataList = new ArrayList<>();
                for (int i = 0; i < frameCount; i++) {
                    int delay = delays.get(i);
                    // STB Image returns delays in milliseconds
                    // Ensure minimum delay to prevent too-fast animations
                    if (delay < 20) {
                        delay = 100;
                    }
                    frameDataList.add(new AnimationMetadata.FrameData(delay, i));
                }

                AnimationMetadata metadata = new AnimationMetadata(frameDataList, frameCount);

                // Convert frames to NativeImage array
                NativeImage[] frames = new NativeImage[frameCount];
                int frameSize = width * height * 4; // RGBA = 4 bytes per pixel

                for (int i = 0; i < frameCount; i++) {
                    try {
                        // Create NativeImage for this frame
                        NativeImage frame = new NativeImage(width, height, false);

                        // Copy pixel data from STB buffer to NativeImage
                        // STB Image returns frames stacked vertically in the buffer
                        int frameOffset = i * frameSize;

                        for (int y = 0; y < height; y++) {
                            for (int x = 0; x < width; x++) {
                                int pixelOffset = frameOffset + (y * width + x) * 4;

                                // Read RGBA from STB buffer
                                int r = imageData.get(pixelOffset) & 0xFF;
                                int g = imageData.get(pixelOffset + 1) & 0xFF;
                                int b = imageData.get(pixelOffset + 2) & 0xFF;
                                int a = imageData.get(pixelOffset + 3) & 0xFF;

                                // NativeImage uses ABGR format
                                int abgr = (a << 24) | (b << 16) | (g << 8) | r;
                                PlatformHelper.setPixel(frame, x, y, abgr);
                            }
                        }

                        frames[i] = frame;
                    } catch (Exception e) {
                        // Clean up already created frames on error
                        for (int j = 0; j < i; j++) {
                            if (frames[j] != null) {
                                frames[j].close();
                            }
                        }
                        throw new IOException("Failed to create frame " + i, e);
                    }
                }

                return new GifLoadResult(frames, metadata, width, height);

            } finally {
                // Free STB Image buffer
                if (imageData != null) {
                    STBImage.stbi_image_free(imageData);
                }
            }

        } finally {
            // Free allocated buffer (delaysBuffer is stack-allocated and auto-freed)
            if (gifData != null) {
                MemoryUtil.memFree(gifData);
            }
        }
    }
}
