package com.quickskin.mod.client.gui.util;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.util.HashUtil;
import com.quickskin.mod.common.util.HDTextureProcessor;
import com.quickskin.mod.config.ClientConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility for importing skin files into the QuickSkin directory
 */
@Environment(EnvType.CLIENT)
public class SkinImporter {

    /**
     * Import a skin file
     * @param sourcePath Source file path
     * @return The imported asset metadata, or null on failure
     */
    public static AssetMetadata importSkin(Path sourcePath) {
        if (sourcePath == null || !Files.exists(sourcePath)) {
            QuickSkin.LOGGER.error("Source file does not exist: {}", sourcePath);
            return null;
        }

        // Validate it's a PNG file
        String fileName = sourcePath.getFileName().toString();
        if (!fileName.toLowerCase().endsWith(".png")) {
            QuickSkin.LOGGER.error("File is not a PNG: {}", fileName);
            return null;
        }

        try (InputStream inputStream = Files.newInputStream(sourcePath)) {
            // Process the skin using the new HDTextureProcessor
            // Allow transparency unless disabled in config (client or server)
            boolean allowTransparency = !ClientConfig.getInstance().shouldDisableSkinTransparency();
            byte[] processedImageBytes = HDTextureProcessor.processHDSkin(inputStream, allowTransparency);

            if (processedImageBytes == null) {
                QuickSkin.LOGGER.error("Failed to process skin file: {} (path: {})", fileName, sourcePath.toAbsolutePath());
                return null;
            }

            QuickSkin.LOGGER.info("Importing skin: {}", fileName);

            // Copy file to skins directory
            LocalAssetManager assetManager = LocalAssetManager.getInstance();
            Path targetPath = assetManager.getSkinsDirectory().resolve(fileName);

            // If file already exists, add a number
            int counter = 1;
            String nameWithoutExt = fileName.substring(0, fileName.lastIndexOf('.'));
            while (Files.exists(targetPath)) {
                targetPath = assetManager.getSkinsDirectory().resolve(nameWithoutExt + "_" + counter + ".png");
                counter++;
            }

            // Save the processed image
            Files.write(targetPath, processedImageBytes);
            QuickSkin.LOGGER.info("Saved processed skin to: {}", targetPath);

            // Reload assets to pick up the new file
            assetManager.reload();

            // Get the metadata for the imported file
            String hash = HashUtil.computeFileHash(targetPath);
            if (hash != null) {
                return assetManager.getMetadata(hash);
            }

        } catch (IOException e) {
            QuickSkin.LOGGER.error("Failed to process skin file", e);
        }

        return null;
    }

    /**
     * Import multiple skin files
     * @param sourcePaths Array of source file paths
     * @return List of successfully imported assets
     */
    public static List<AssetMetadata> importSkins(Path[] sourcePaths) {
        List<AssetMetadata> imported = new ArrayList<>();

        for (Path path : sourcePaths) {
            AssetMetadata metadata = importSkin(path);
            if (metadata != null) {
                imported.add(metadata);
            }
        }

        return imported;
    }

    /**
     * Check if dimensions are valid for a skin
     */
    private static boolean isValidSkinDimension(int width, int height) {
        // Legacy format: 64x32
        if (width == 64 && height == 32) {
            return true;
        }

        // Standard and HD formats
        // Valid if width is 64 * (2^n) and height is width
        if (width >= 64 && width <= 2048 && height >= 32 && height <= 1024) {
            // Check if width is a power of 2 multiple of 64
            if (width % 64 == 0) {
                int scale = width / 64;
                // scale should be a power of 2 (1, 2, 4, 8, 16, 32)
                if ((scale & (scale - 1)) == 0) {
                    // Height should be width or width/2 (for legacy)
                    return height == width || height == width / 2;
                }
            }
        }

        return false;
    }

    /**
     * Save a BufferedImage as a skin file
     * @param image The image to save
     * @param username The username (used for filename)
     * @return The path to the saved file, or null on failure
     */
    public static Path saveSkinImage(BufferedImage image, String username) {
        if (image == null || username == null) {
            QuickSkin.LOGGER.error("Invalid parameters for saveSkinImage");
            return null;
        }

        try {
            // Validate dimensions
            int width = image.getWidth();
            int height = image.getHeight();
            if (!isValidSkinDimension(width, height)) {
                QuickSkin.LOGGER.error("Invalid skin dimensions {}x{} for {}", width, height, username);
                return null;
            }

            // Convert legacy 64x32 skins to modern 64x64 format
            if (height == width / 2) {
                QuickSkin.LOGGER.info("Converting legacy 64x32 skin to modern format for: {}", username);
                image = HDTextureProcessor.convertLegacyToModern(image);
            }

            // Apply transparency settings if needed
            boolean disableTransparency = ClientConfig.getInstance().shouldDisableSkinTransparency();
            if (disableTransparency) {
                image = HDTextureProcessor.removeTransparency(image);
            }

            // Create filename from username
            String fileName = username + ".png";
            LocalAssetManager assetManager = LocalAssetManager.getInstance();
            Path targetPath = assetManager.getSkinsDirectory().resolve(fileName);

            // If file already exists, add a number
            int counter = 1;
            while (Files.exists(targetPath)) {
                targetPath = assetManager.getSkinsDirectory().resolve(username + "_" + counter + ".png");
                counter++;
            }

            // Save the image
            ImageIO.write(image, "PNG", targetPath.toFile());
            QuickSkin.LOGGER.info("Saved skin image to: {}", targetPath);

            return targetPath;

        } catch (IOException e) {
            QuickSkin.LOGGER.error("Failed to save skin image for: {}", username, e);
            return null;
        }
    }

}
