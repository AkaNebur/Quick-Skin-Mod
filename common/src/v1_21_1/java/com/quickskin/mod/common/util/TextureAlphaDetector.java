package com.quickskin.mod.common.util;

import com.quickskin.mod.QuickSkin;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility class for detecting if textures contain transparency (alpha channel)
 */
@Environment(EnvType.CLIENT)
public class TextureAlphaDetector {

    // Cache to avoid repeatedly checking the same textures
    private static final Map<ResourceLocation, Boolean> transparencyCache = new ConcurrentHashMap<>();

    // Track textures currently being analyzed to avoid duplicate work
    private static final Set<ResourceLocation> pendingAnalysis = ConcurrentHashMap.newKeySet();

    /**
     * Check if a texture contains any transparent pixels
     * This method returns immediately and does NOT perform I/O on the calling thread.
     *
     * @param textureLocation The resource location of the texture
     * @return true if the texture has any pixels with alpha < 255, OR if the analysis is not yet complete (defaults to TRANSLUCENT for safety)
     */
    public static boolean hasTransparency(ResourceLocation textureLocation) {
        if (textureLocation == null) {
            return false;
        }

        // Check cache first
        Boolean cached = transparencyCache.get(textureLocation);
        if (cached != null) {
            return cached;
        }

        // Default to TRANSLUCENT (true) if not yet analyzed
        // This is safer - it may be slightly less performant but won't cause visual glitches
        return true;
    }

    /**
     * Asynchronously analyze a texture for transparency.
     * This should be called when a texture is first loaded/applied.
     * The analysis happens on a background thread, and the result is cached for future use.
     *
     * @param textureLocation The resource location of the texture to analyze
     */
    public static void analyzeTextureAsync(ResourceLocation textureLocation) {
        if (textureLocation == null) {
            return;
        }

        // Skip if already cached
        if (transparencyCache.containsKey(textureLocation)) {
            return;
        }

        // Skip if already being analyzed
        if (!pendingAnalysis.add(textureLocation)) {
            return;
        }

        QuickSkin.LOGGER.debug("Starting async transparency analysis for: {}", textureLocation);

        // Perform the analysis on a background thread
        CompletableFuture.runAsync(() -> {
            try {
                boolean hasAlpha = detectTransparency(textureLocation);

                // Update cache on the main thread to ensure thread safety with rendering
                Minecraft mc = Minecraft.getInstance();
                mc.execute(() -> {
                    transparencyCache.put(textureLocation, hasAlpha);
                    QuickSkin.LOGGER.info("Completed transparency analysis for {}: {}", textureLocation, hasAlpha);
                });
            } catch (Exception e) {
                QuickSkin.LOGGER.error("Error during async transparency analysis for {}", textureLocation, e);
                // On error, cache as transparent (safe default)
                Minecraft mc = Minecraft.getInstance();
                mc.execute(() -> transparencyCache.put(textureLocation, true));
            } finally {
                // Remove from pending set
                pendingAnalysis.remove(textureLocation);
            }
        });
    }

    /**
     * Actually detect if the texture has transparency by loading and examining it
     */
    private static boolean detectTransparency(ResourceLocation textureLocation) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getResourceManager() == null) {
                return false;
            }

            // Try to get the resource
            Resource resource = mc.getResourceManager().getResource(textureLocation).orElse(null);
            if (resource == null) {
                QuickSkin.LOGGER.debug("Could not find texture resource: {}", textureLocation);
                return false;
            }

            // Load the image
            try (InputStream inputStream = resource.open()) {
                BufferedImage image = ImageIO.read(inputStream);
                if (image == null) {
                    QuickSkin.LOGGER.debug("Could not read image from resource: {}", textureLocation);
                    return false;
                }

                return checkImageForTransparency(image);

            }
        } catch (IOException e) {
            QuickSkin.LOGGER.debug("Failed to check transparency for texture {}: {}", textureLocation, e.getMessage());
            return false;
        } catch (Exception e) {
            QuickSkin.LOGGER.error("Unexpected error checking transparency for texture {}", textureLocation, e);
            return false;
        }
    }

    /**
     * Check if a BufferedImage contains any transparent pixels
     */
    private static boolean checkImageForTransparency(BufferedImage image) {
        // If the image doesn't have an alpha channel, it's not transparent
        if (!image.getColorModel().hasAlpha()) {
            return false;
        }

        int width = image.getWidth();
        int height = image.getHeight();

        // Sample pixels to check for transparency for performance
        int sampleRate = Math.max(1, Math.min(width, height) / 32); // Sample every Nth pixel

        for (int y = 0; y < height; y += sampleRate) {
            for (int x = 0; x < width; x += sampleRate) {
                int pixel = image.getRGB(x, y);
                int alpha = (pixel >> 24) & 0xFF;

                if (alpha < 255) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Clear the transparency cache (useful for resource pack reloads)
     */
    public static void clearCache() {
        transparencyCache.clear();
        pendingAnalysis.clear();
        QuickSkin.LOGGER.debug("Cleared texture transparency cache");
    }
}
