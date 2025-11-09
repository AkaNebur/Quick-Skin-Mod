package com.quickskin.mod.common.data;

/**
 * Enum defining valid skin and cape resolutions
 * Supports standard (64x64) and HD skins up to 2048x2048
 */
public enum SkinResolution {
    LEGACY(64, 32, 1),      // Old skin format (pre-1.8)
    STANDARD(64, 64, 1),    // Modern standard skin
    HD_128(128, 128, 2),    // 2x HD
    HD_256(256, 256, 4),    // 4x HD
    HD_512(512, 512, 8),    // 8x HD
    HD_1024(1024, 1024, 16), // 16x HD
    HD_2048(2048, 2048, 32); // 32x HD (max)

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

    @Override
    public String toString() {
        return width + "x" + height + (isHD() ? " (HD " + scale + "x)" : "");
    }
}
