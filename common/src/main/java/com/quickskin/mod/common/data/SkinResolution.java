package com.quickskin.mod.common.data;

/**
 * Enum defining valid skin and cape resolutions
 * Supports standard (64x64) and HD skins up to 2048x1024
 */
public enum SkinResolution {
    LEGACY(64, 32, 1),      // Old skin format (pre-1.8)
    STANDARD(64, 64, 1),    // Modern standard skin
    HD_128(128, 64, 2),     // 2x HD
    HD_256(256, 128, 4),    // 4x HD
    HD_512(512, 256, 8),    // 8x HD
    HD_1024(1024, 512, 16), // 16x HD
    HD_2048(2048, 1024, 32); // 32x HD (max)

    private final int width;
    private final int height;
    private final int scale;

    SkinResolution(int width, int height, int scale) {
        this.width = width;
        this.height = height;
        this.scale = scale;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getScale() {
        return scale;
    }

    public boolean isHD() {
        return scale > 1;
    }

    public boolean isLegacy() {
        return this == LEGACY;
    }

    /**
     * Get resolution from dimensions
     * @return Resolution enum, or null if invalid
     */
    public static SkinResolution fromDimensions(int width, int height) {
        for (SkinResolution res : values()) {
            if (res.width == width && res.height == height) {
                return res;
            }
        }
        return null;
    }

    /**
     * Check if dimensions are valid for a skin
     */
    public static boolean isValidSkinDimension(int width, int height) {
        return fromDimensions(width, height) != null;
    }

    /**
     * Check if dimensions are valid for a cape (64xN or HDx scale)
     */
    public static boolean isValidCapeDimension(int width, int height) {
        // Standard cape: 64x32
        if (width == 64 && height == 32) {
            return true;
        }

        // HD capes: must maintain 2:1 aspect ratio
        if (width > 64 && width % 64 == 0) {
            return height == (width / 2);
        }

        return false;
    }

    /**
     * Get the closest valid resolution for given dimensions
     * Used for normalization
     */
    public static SkinResolution getClosestResolution(int width, int height) {
        SkinResolution exact = fromDimensions(width, height);
        if (exact != null) {
            return exact;
        }

        // Find closest by width
        SkinResolution closest = STANDARD;
        int minDiff = Integer.MAX_VALUE;

        for (SkinResolution res : values()) {
            int diff = Math.abs(res.width - width);
            if (diff < minDiff) {
                minDiff = diff;
                closest = res;
            }
        }

        return closest;
    }

    @Override
    public String toString() {
        return width + "x" + height + (isHD() ? " (HD " + scale + "x)" : "");
    }
}
