package com.quickskin.mod.common.util;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.common.data.SkinResolution;
import com.quickskin.mod.common.data.TextureQuality;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Handles HD skin processing, legacy skin conversion, and texture scaling
 * Supports skins up to 2048x1024 (32x scale factor)
 */
public class HDTextureProcessor {

    /**
     * Process HD skin with transparency handling
     * Converts legacy skins to modern format if needed
     *
     * @param input Input stream of skin image
     * @param allowTransparency Whether to preserve transparency
     * @return Processed image bytes, or null on error
     */
    public static byte[] processHDSkin(InputStream input, boolean allowTransparency) {
        try {
            BufferedImage image = ImageIO.read(input);
            if (image == null) {
                QuickSkin.LOGGER.error("Failed to read image");
                return null;
            }

            int width = image.getWidth();
            int height = image.getHeight();

            QuickSkin.LOGGER.debug("Processing skin: {}x{}", width, height);

            // Check if valid resolution
            SkinResolution resolution = SkinResolution.fromDimensions(width, height);
            if (resolution == null) {
                QuickSkin.LOGGER.warn("Invalid skin dimensions: {}x{}", width, height);
                return null;
            }

            // Convert legacy (64x32) to modern (64x64)
            if (resolution == SkinResolution.LEGACY) {
                QuickSkin.LOGGER.debug("Converting legacy skin to modern format");
                image = convertLegacyToModern(image);
            }

            // Handle transparency
            if (!allowTransparency) {
                image = removeTransparency(image);
            }

            // Convert to PNG bytes
            return imageToPng(image);

        } catch (Exception e) {
            QuickSkin.LOGGER.error("Failed to process HD skin", e);
            return null;
        }
    }

    /**
     * Convert legacy 64x32 skin to modern 64x64 format
     * Properly copies and mirrors limbs for HD compatibility
     */
    public static BufferedImage convertLegacyToModern(BufferedImage legacy) {
        int width = legacy.getWidth();
        int height = legacy.getHeight();
        int scale = width / 64;

        // Create new 64x64 (scaled) image
        BufferedImage modern = new BufferedImage(width, width, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = modern.createGraphics();

        // Copy entire top half (64x32 scaled)
        g.drawImage(legacy, 0, 0, null);

        // Copy and mirror right leg to left leg (modern format)
        copyAreaRGBA(legacy, modern, 0, 16 * scale, 16 * scale, 16 * scale, 16 * scale, 48 * scale, true, false);

        // Copy and mirror right arm to left arm
        copyAreaRGBA(legacy, modern, 40 * scale, 16 * scale, 16 * scale, 16 * scale, 32 * scale, 48 * scale, true, false);

        g.dispose();
        return modern;
    }

    /**
     * Copy and optionally mirror an area of the image
     * Supports HD scale factors
     */
    private static void copyAreaRGBA(
            BufferedImage src,
            BufferedImage dst,
            int srcX, int srcY,
            int width, int height,
            int dstX, int dstY,
            boolean mirrorX,
            boolean mirrorY
    ) {
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (srcX + x < src.getWidth() && srcY + y < src.getHeight()) {
                    int pixel = src.getRGB(srcX + x, srcY + y);

                    int targetX = mirrorX ? dstX + (width - 1 - x) : dstX + x;
                    int targetY = mirrorY ? dstY + (height - 1 - y) : dstY + y;

                    if (targetX < dst.getWidth() && targetY < dst.getHeight()) {
                        dst.setRGB(targetX, targetY, pixel);
                    }
                }
            }
        }
    }

    /**
     * Remove transparency from image, replace with white
     * Preserves 3D overlay layers (they should always be transparent)
     */
    public static BufferedImage removeTransparency(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int scale = width / 64;

        BufferedImage opaque = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = image.getRGB(x, y);
                int alpha = (argb >> 24) & 0xFF;

                // Check if this pixel is part of a 3D overlay layer
                // Overlay layers should always preserve transparency
                if (isOverlayLayerPixel(x / scale, y / scale)) {
                    opaque.setRGB(x, y, argb); // Keep original (with transparency)
                } else if (alpha == 0) {
                    // Make fully transparent pixels white
                    opaque.setRGB(x, y, 0xFFFFFFFF);
                } else {
                    // Make semi-transparent pixels fully opaque
                    opaque.setRGB(x, y, argb | 0xFF000000);
                }
            }
        }

        return opaque;
    }

    /**
     * Check if a pixel coordinate (at 1x scale) is part of a 3D overlay layer
     * These areas should always preserve transparency
     */
    private static boolean isOverlayLayerPixel(int x, int y) {
        // Head overlay (32-63, 0-15)
        if (x >= 32 && x < 64 && y >= 0 && y < 16) {
            return true;
        }

        // Body overlay (16-39, 32-47)
        if (x >= 16 && x < 40 && y >= 32 && y < 48) {
            return true;
        }

        // Right arm overlay (40-55, 32-47)
        if (x >= 40 && x < 56 && y >= 32 && y < 48) {
            return true;
        }

        // Left arm overlay (48-63, 48-63)
        if (x >= 48 && x < 64 && y >= 48 && y < 64) {
            return true;
        }

        // Right leg overlay (0-15, 32-47)
        if (x >= 0 && x < 16 && y >= 32 && y < 48) {
            return true;
        }

        // Left leg overlay (0-15, 48-63)
        if (x >= 0 && x < 16 && y >= 48 && y < 64) {
            return true;
        }

        return false;
    }

    /**
     * Downsample texture to target size with high quality
     */
    public static BufferedImage downsample(BufferedImage source, int targetSize) {
        if (source.getWidth() <= targetSize && source.getHeight() <= targetSize) {
            return source; // Already small enough
        }

        // Calculate target dimensions maintaining aspect ratio
        int targetWidth, targetHeight;
        if (source.getWidth() > source.getHeight()) {
            targetWidth = targetSize;
            targetHeight = (targetSize * source.getHeight()) / source.getWidth();
        } else {
            targetHeight = targetSize;
            targetWidth = (targetSize * source.getWidth()) / source.getHeight();
        }

        BufferedImage downsampled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = downsampled.createGraphics();

        // High quality downsampling
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        g.dispose();

        return downsampled;
    }

    /**
     * Create thumbnail (64x64) for GUI lists
     */
    public static byte[] createThumbnail(BufferedImage source) {
        BufferedImage thumbnail = downsample(source, TextureQuality.THUMBNAIL.getTargetSize());
        return imageToPng(thumbnail);
    }

    /**
     * Create preview (256x256) for GUI selection screen
     */
    public static byte[] createPreview(BufferedImage source) {
        BufferedImage preview = downsample(source, TextureQuality.PREVIEW.getTargetSize());
        return imageToPng(preview);
    }

    /**
     * Normalize texture to 64x64 for vanilla rendering
     * (Phase 6 will remove this when we drop GeckoLib)
     */
    public static byte[] normalizeForVanilla(BufferedImage source) {
        BufferedImage normalized = downsample(source, TextureQuality.NORMALIZED.getTargetSize());
        return imageToPng(normalized);
    }

    /**
     * Convert BufferedImage to PNG byte array
     */
    public static byte[] imageToPng(BufferedImage image) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            QuickSkin.LOGGER.error("Failed to convert image to PNG", e);
            return null;
        }
    }

    /**
     * Convert byte array back to BufferedImage
     */
    public static BufferedImage pngToImage(byte[] data) {
        try {
            return ImageIO.read(new ByteArrayInputStream(data));
        } catch (Exception e) {
            QuickSkin.LOGGER.error("Failed to convert PNG to image", e);
            return null;
        }
    }

    /**
     * Normalize cape to standard 64x32 if HD
     */
    public static BufferedImage normalizeCape(BufferedImage cape) {
        if (cape.getWidth() == 64 && cape.getHeight() == 32) {
            return cape; // Already normalized
        }

        // Downsample to 64x32
        BufferedImage normalized = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = normalized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(cape, 0, 0, 64, 32, null);
        g.dispose();

        return normalized;
    }
}
