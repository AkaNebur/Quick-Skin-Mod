package com.quickskin.mod.client.storage;

import com.mojang.blaze3d.platform.NativeImage;
import com.quickskin.mod.QuickSkin;
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

    // Store raw texture bytes in memory (ORIGINAL unprocessed data)
    private final Map<String, byte[]> originalTextureData = new ConcurrentHashMap<>();

    // Store processed texture bytes (with transparency removed if needed)
    private final Map<String, byte[]> textureDataCache = new ConcurrentHashMap<>();

    // Store registered ResourceLocations
    private final Map<String, ResourceLocation> textureRegistry = new ConcurrentHashMap<>();

    // Track texture types (skin/cape) for selective clearing
    private final Map<String, String> textureTypeMap = new ConcurrentHashMap<>();

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
     * @param textureType The texture type ("skin" or "cape"), or null if unknown
     * @param textureData The raw PNG/image bytes
     */
    public void storeTexture(String hash, @Nullable String textureType, byte[] textureData) {
        if (hash == null || textureData == null) {
            QuickSkin.LOGGER.warn("Cannot store null texture data");
            return;
        }

        // Store original unprocessed data ONLY if it doesn't already exist.
        // This ensures we never overwrite the pristine original with reprocessed data.
        originalTextureData.putIfAbsent(hash, textureData);

        // Track texture type if provided
        if (textureType != null) {
            textureTypeMap.put(hash, textureType);
        }

        // Check if transparency should be removed for skins (only if we know it's a skin)
        boolean isSkin = "skin".equals(textureType);
        boolean shouldRemoveTransparency = isSkin &&
                com.quickskin.mod.config.ClientConfig.getInstance().shouldDisableSkinTransparency();

        byte[] processedData = textureData;
        if (shouldRemoveTransparency) {
            try {
                // Load the image
                ByteArrayInputStream bais = new ByteArrayInputStream(textureData);
                BufferedImage image = ImageIO.read(bais);

                if (image != null) {
                    // Remove transparency
                    image = com.quickskin.mod.common.util.HDTextureProcessor.removeTransparency(image);

                    // Convert back to PNG bytes
                    processedData = com.quickskin.mod.common.util.HDTextureProcessor.imageToPng(image);
                }
            } catch (IOException e) {
                QuickSkin.LOGGER.error("Failed to process transparency for network texture: {}", hash, e);
                // Fall through to store original texture data
            }
        }

        textureDataCache.put(hash, processedData);
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
            // Load directly as NativeImage from PNG bytes (handles pixel format automatically)
            NativeImage nativeImage = NativeImage.read(new ByteArrayInputStream(textureData));

            // Create dynamic texture
            DynamicTexture dynamicTexture = new DynamicTexture(nativeImage);

            // Register with texture manager
            ResourceLocation location = new ResourceLocation(
                    QuickSkin.MOD_ID,
                    "network/" + hash
            );

            Minecraft.getInstance().getTextureManager().register(location, dynamicTexture);

            // Parse Ears features from original unprocessed data (preserving alpha for Alfalfa)
            String textureType = textureTypeMap.get(hash);
            if ("skin".equals(textureType) && com.quickskin.mod.client.compat.EarsCompatIntegration.isAvailable()) {
                byte[] originalData = originalTextureData.get(hash);
                if (originalData != null) {
                    try {
                        java.awt.image.BufferedImage originalImage = ImageIO.read(new ByteArrayInputStream(originalData));
                        if (originalImage != null) {
                            com.quickskin.mod.client.compat.EarsCompatIntegration.parseAndStoreFeatures(location, originalImage);
                        }
                    } catch (IOException e) {
                        // silently skip Ears parsing
                    }
                }
            }

            // Cache the location
            textureRegistry.put(hash, location);
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
            }
        }

        originalTextureData.clear();
        textureDataCache.clear();
        textureRegistry.clear();
        textureTypeMap.clear();
    }

    /**
     * Clear only skin texture registrations (not the raw data or capes)
     * Used when skin transparency setting changes - this forces skins to re-register
     * with the new transparency setting applied
     */
    public void clearSkins() {
        java.util.List<String> hashesToClear = new java.util.ArrayList<>();

        // Find all skin hashes
        for (java.util.Map.Entry<String, String> entry : textureTypeMap.entrySet()) {
            if ("skin".equals(entry.getValue())) {
                hashesToClear.add(entry.getKey());
            }
        }

        // Release and remove ONLY the registered ResourceLocations for skins
        // Keep the raw texture data so we can re-process it with new settings
        for (String hash : hashesToClear) {
            ResourceLocation location = textureRegistry.remove(hash);
            if (location != null) {
                try {
                    Minecraft.getInstance().getTextureManager().release(location);
                } catch (Exception e) {
                }
            }
        }
    }

    /**
     * Reprocess and re-register all skin textures with current transparency settings
     * Called when server transparency setting changes
     */
    public void reprocessSkins() {
        java.util.List<String> skinHashes = new java.util.ArrayList<>();

        // Find all skin hashes
        for (java.util.Map.Entry<String, String> entry : textureTypeMap.entrySet()) {
            if ("skin".equals(entry.getValue())) {
                skinHashes.add(entry.getKey());
            }
        }

        // Re-store each skin with current settings (this will reprocess transparency from original data)
        for (String hash : skinHashes) {
            byte[] originalData = originalTextureData.get(hash);
            if (originalData != null) {
                // Remove old processed data and registration
                textureDataCache.remove(hash);
                ResourceLocation oldLocation = textureRegistry.remove(hash);
                if (oldLocation != null) {
                    try {
                        Minecraft.getInstance().getTextureManager().release(oldLocation);
                    } catch (Exception e) {
                    }
                }

                // Re-store with current transparency settings using ORIGINAL data
                storeTexture(hash, "skin", originalData);
            }
        }
    }

    /**
     * Get the number of cached textures
     * @return The cache size
     */
    public int size() {
        return textureDataCache.size();
    }

}
