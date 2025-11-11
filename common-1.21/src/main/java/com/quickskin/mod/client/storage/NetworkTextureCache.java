package com.quickskin.mod.client.storage;

import com.mojang.blaze3d.platform.NativeImage;
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.platform.PlatformHelper;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache for textures received from the server over the network
 * Stores textures in memory only - they won't appear in the local skin list
 */
@Environment(EnvType.CLIENT)
public class NetworkTextureCache {
    private static NetworkTextureCache instance;

    // Store raw texture bytes in memory
    private final Map<String, byte[]> textureDataCache = new ConcurrentHashMap<>();

    // Store registered ResourceLocations
    private final Map<String, ResourceLocation> textureRegistry = new ConcurrentHashMap<>();

    private NetworkTextureCache() {}

    public static NetworkTextureCache getInstance() {
        if (instance == null) {
            instance = new NetworkTextureCache();
        }
        return instance;
    }

    /**
     * Store texture data received from the network
     * @param hash The texture hash
     * @param textureData The raw PNG/image bytes
     */
    public void storeTexture(String hash, byte[] textureData) {
        if (hash == null || textureData == null) {
            QuickSkin.LOGGER.warn("Cannot store null texture data");
            return;
        }

        textureDataCache.put(hash, textureData);
        QuickSkin.LOGGER.debug("Cached network texture: {} ({} bytes)", hash, textureData.length);
    }

    /**
     * Get texture data for a hash
     * @param hash The texture hash
     * @return The texture bytes, or null if not found
     */
    public byte @Nullable [] getTextureData(String hash) {
        return textureDataCache.get(hash);
    }

    /**
     * Get or create a ResourceLocation for a network texture
     * Registers the texture with Minecraft's texture manager if not already registered
     * @param hash The texture hash
     * @return The ResourceLocation, or null if texture not found
     */
    @Nullable
    public ResourceLocation getTextureLocation(String hash) {
        // Check if already registered
        if (textureRegistry.containsKey(hash)) {
            return textureRegistry.get(hash);
        }

        // Get texture data
        byte[] textureData = textureDataCache.get(hash);
        if (textureData == null) {
            QuickSkin.LOGGER.warn("Network texture not found in cache: {}", hash);
            return null;
        }

        // Load and register texture
        try {
            BufferedImage bufferedImage = ImageIO.read(new ByteArrayInputStream(textureData));
            if (bufferedImage == null) {
                QuickSkin.LOGGER.error("Failed to decode network texture: {}", hash);
                return null;
            }

            // Convert to NativeImage
            NativeImage nativeImage = convertToNativeImage(bufferedImage);

            // Create dynamic texture
            DynamicTexture dynamicTexture = new DynamicTexture(nativeImage);

            // Register with texture manager
            ResourceLocation location = ResourceLocation.fromNamespaceAndPath(
                    QuickSkin.MOD_ID,
                    "network/" + hash
            );

            Minecraft.getInstance().getTextureManager().register(location, dynamicTexture);

            // Cache the location
            textureRegistry.put(hash, location);

            QuickSkin.LOGGER.info("Registered network texture: {}", hash);
            return location;

        } catch (IOException e) {
            QuickSkin.LOGGER.error("Failed to register network texture: {}", hash, e);
            return null;
        }
    }

    /**
     * Check if a texture is cached
     * @param hash The texture hash
     * @return true if the texture is in the cache
     */
    public boolean hasTexture(String hash) {
        return textureDataCache.containsKey(hash);
    }

    /**
     * Clear all cached network textures
     */
    public void clear() {
        // Release all registered textures
        for (ResourceLocation location : textureRegistry.values()) {
            try {
                Minecraft.getInstance().getTextureManager().release(location);
            } catch (Exception e) {
                QuickSkin.LOGGER.debug("Failed to release texture {}: {}", location, e.getMessage());
            }
        }

        textureDataCache.clear();
        textureRegistry.clear();
        QuickSkin.LOGGER.info("Cleared network texture cache");
    }

    /**
     * Get the number of cached textures
     * @return The cache size
     */
    public int size() {
        return textureDataCache.size();
    }

    /**
     * Convert BufferedImage to NativeImage for texture registration
     */
    private NativeImage convertToNativeImage(BufferedImage bufferedImage) {
        int width = bufferedImage.getWidth();
        int height = bufferedImage.getHeight();

        NativeImage nativeImage = new NativeImage(width, height, true);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = bufferedImage.getRGB(x, y);
                // NativeImage expects ABGR format
                int a = (argb >> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                int abgr = (a << 24) | (b << 16) | (g << 8) | r;
                PlatformHelper.setPixel(nativeImage, x, y, abgr);
            }
        }

        return nativeImage;
    }
}
