package com.quickskin.mod.client.services;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.common.data.AnimationMetadata;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.SkinResolution;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.common.util.HashUtil;
import com.quickskin.mod.common.util.HDTextureProcessor;
import com.quickskin.mod.common.util.SkinModelDetector;
import com.quickskin.mod.platform.PlatformHelper;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Manages local skin and cape assets
 * Singleton service that scans filesystem and maintains metadata cache
 */
@Environment(EnvType.CLIENT)
public class LocalAssetManager {

    private static LocalAssetManager instance;

    // Asset discovery
    private final Map<String, AssetMetadata> metadataCache = new ConcurrentHashMap<>();
    private final Map<String, Path> hashToSourcePath = new ConcurrentHashMap<>();

    // Texture registration
    private final Map<String, Map<TextureQuality, ResourceLocation>> textureRegistry = new ConcurrentHashMap<>();

    // Directory paths
    private Path skinsDirectory;
    private Path capesDirectory;
    private Path cacheDirectory;

    private LocalAssetManager() {
        // Private constructor for singleton
    }

    public static LocalAssetManager getInstance() {
        if (instance == null) {
            instance = new LocalAssetManager();
        }
        return instance;
    }

    /**
     * Initialize asset manager and discover assets
     */
    public void init() {
        QuickSkin.LOGGER.info("Initializing LocalAssetManager...");

        // Get directories from platform helper
        skinsDirectory = PlatformHelper.getSkinsDirectory();
        capesDirectory = PlatformHelper.getCapesDirectory();
        cacheDirectory = PlatformHelper.getCacheDirectory();

        // Create directories if they don't exist
        try {
            Files.createDirectories(skinsDirectory);
            Files.createDirectories(capesDirectory);
            Files.createDirectories(cacheDirectory);
        } catch (IOException e) {
            QuickSkin.LOGGER.error("Failed to create asset directories", e);
        }

        // Discover assets
        discoverLocalAssets();

        QuickSkin.LOGGER.info("LocalAssetManager initialized with {} assets", metadataCache.size());
    }

    /**
     * Scan filesystem for skins and capes, build metadata cache
     */
    public void discoverLocalAssets() {
        QuickSkin.LOGGER.info("Discovering local assets...");

        metadataCache.clear();
        hashToSourcePath.clear();

        // Scan skins directory
        int skinsFound = scanDirectory(skinsDirectory, "skin");
        QuickSkin.LOGGER.info("Found {} skins", skinsFound);

        // Scan capes directory
        int capesFound = scanDirectory(capesDirectory, "cape");
        QuickSkin.LOGGER.info("Found {} capes", capesFound);
    }

    /**
     * Scan directory for PNG and GIF files and process them
     * @return Number of assets found
     */
    private int scanDirectory(Path directory, String type) {
        if (!Files.exists(directory)) {
            return 0;
        }

        int count = 0;

        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String fileName = path.getFileName().toString().toLowerCase();

                // Process PNG files
                if (fileName.endsWith(".png")) {
                    AssetMetadata metadata = processPngAsset(path, type);
                    if (metadata != null) {
                        metadataCache.put(metadata.hash(), metadata);
                        hashToSourcePath.put(metadata.hash(), path);
                        count++;
                    }
                }
                // Process GIF files (animated capes only)
                else if (fileName.endsWith(".gif") && "cape".equals(type)) {
                    AssetMetadata metadata = processGifAsset(path, type);
                    if (metadata != null) {
                        metadataCache.put(metadata.hash(), metadata);
                        hashToSourcePath.put(metadata.hash(), path);
                        count++;
                    }
                }
            }
        } catch (IOException e) {
            QuickSkin.LOGGER.error("Failed to scan directory: {}", directory, e);
        }

        return count;
    }

    /**
     * Process PNG asset and create metadata
     */
    private AssetMetadata processPngAsset(Path path, String type) {
        try {
            // Compute hash
            String hash = HashUtil.computeFileHash(path);
            if (hash == null) {
                return null;
            }

            // Check for animation metadata first
            Path metadataPath = cacheDirectory.resolve(hash + ".json");
            AnimationMetadata animMeta = null;
            if (Files.exists(metadataPath)) {
                try {
                    String json = Files.readString(metadataPath);
                    animMeta = AnimationMetadata.fromJson(json);
                } catch (IOException e) {
                    QuickSkin.LOGGER.warn("Found metadata file for {} but failed to read it.", hash, e);
                }
            }

            // Read image to get dimensions
            BufferedImage image = ImageIO.read(path.toFile());
            if (image == null) {
                QuickSkin.LOGGER.warn("Failed to read image: {}", path);
                return null;
            }

            int width = image.getWidth();
            int height = image.getHeight();

            SkinResolution resolution;
            String skinModel = null;
            boolean isAnimated = false;
            int frameCount = 1;

            if (animMeta != null) {
                // This is an animated asset (cape) identified by its metadata file.
                isAnimated = true;
                frameCount = animMeta.frameCount();
                int frameHeight = (frameCount > 0) ? height / frameCount : height;
                resolution = SkinResolution.fromDimensions(width, frameHeight);
                if (resolution == null) {
                    QuickSkin.LOGGER.warn("Invalid frame dimensions {}x{} for animated asset: {}", width, frameHeight, path);
                    return null;
                }
            } else {
                // This is a static asset or a PNG animation strip without metadata.
                if ("skin".equals(type)) {
                    resolution = SkinResolution.fromDimensions(width, height);
                    if (resolution == null) {
                        QuickSkin.LOGGER.warn("Invalid skin dimensions {}x{}: {}", width, height, path);
                        return null;
                    }
                    skinModel = SkinModelDetector.detectSkinModel(image);
                } else { // Cape logic for static capes or PNG strips
                    int frameHeight = width / 2;
                    if (width > 0 && frameHeight > 0 && height % frameHeight == 0) {
                        frameCount = height / frameHeight;
                        isAnimated = frameCount > 1;
                        resolution = SkinResolution.fromDimensions(width, frameHeight);
                        if (resolution == null) {
                            QuickSkin.LOGGER.warn("Invalid cape frame dimensions {}x{}: {}", width, frameHeight, path);
                            return null;
                        }
                    } else {
                        QuickSkin.LOGGER.warn("Invalid cape dimensions {}x{}: {}", width, height, path);
                        return null;
                    }
                }
            }


            // Get friendly name (filename without extension)
            String friendlyName = path.getFileName().toString();
            int dotIndex = friendlyName.lastIndexOf('.');
            if (dotIndex > 0) {
                friendlyName = friendlyName.substring(0, dotIndex);
            }

            // Get file size
            long fileSize = Files.size(path);

            // Create metadata
            if ("skin".equals(type)) {
                return AssetMetadata.forSkin(hash, friendlyName, path, resolution, fileSize, skinModel);
            } else {
                if (isAnimated) {
                    return AssetMetadata.forAnimatedCape(hash, friendlyName, path, resolution, fileSize, frameCount);
                } else {
                    return AssetMetadata.forCape(hash, friendlyName, path, resolution, fileSize);
                }
            }

        } catch (Exception e) {
            QuickSkin.LOGGER.error("Failed to process PNG asset: {}", path, e);
            return null;
        }
    }

    /**
     * Process GIF asset (animated cape) and create metadata
     * Converts GIF to PNG atlas and stores animation data
     */
    private AssetMetadata processGifAsset(Path path, String type) {
        try {
            QuickSkin.LOGGER.info("Processing GIF cape: {}", path);

            // Process GIF to extract frames
            com.quickskin.mod.common.util.GifUtil.GifProcessResult result;
            try (var inputStream = Files.newInputStream(path)) {
                result = com.quickskin.mod.common.util.GifUtil.processGif(inputStream);
            }

            // Compute hash of original GIF
            String hash = HashUtil.computeFileHash(path);
            if (hash == null) {
                return null;
            }

            // Save PNG atlas to cache
            Path cacheDir = cacheDirectory.resolve("animated_capes");
            Files.createDirectories(cacheDir);
            Path atlasPath = cacheDir.resolve(hash + ".png");
            Files.write(atlasPath, result.atlasImageData());

            // Save animation metadata to cache
            Path metadataPath = cacheDirectory.resolve(hash + ".json");
            Files.writeString(metadataPath, result.metadata().toJson());

            // Get friendly name
            String friendlyName = path.getFileName().toString();
            int dotIndex = friendlyName.lastIndexOf('.');
            if (dotIndex > 0) {
                friendlyName = friendlyName.substring(0, dotIndex);
            }

            // Get file size
            long fileSize = Files.size(path);

            // Get resolution from first frame
            SkinResolution resolution = SkinResolution.fromDimensions(
                    result.frameWidth(),
                    result.frameHeight()
            );
            if (resolution == null) {
                resolution = SkinResolution.STANDARD;
            }

            QuickSkin.LOGGER.info("GIF cape processed: {} frames, hash: {}", result.metadata().frameCount(), hash);

            // Create metadata for animated cape
            return AssetMetadata.forAnimatedCape(
                    hash,
                    friendlyName,
                    path,
                    resolution,
                    fileSize,
                    result.metadata().frameCount()
            );

        } catch (Exception e) {
            QuickSkin.LOGGER.error("Failed to process GIF asset: {}", path, e);
            return null;
        }
    }

    /**
     * Get animation metadata for a texture hash
     * @param hash The texture hash
     * @return The metadata, or null if not found or not animated
     */
    public AnimationMetadata getAnimationMetadata(String hash) {
        AssetMetadata assetMeta = getMetadata(hash);
        if (assetMeta == null || !assetMeta.isAnimated()) {
            return null;
        }

        Path metadataPath = cacheDirectory.resolve(hash + ".json");
        if (Files.exists(metadataPath)) {
            try {
                String json = Files.readString(metadataPath);
                return AnimationMetadata.fromJson(json);
            } catch (IOException e) {
                QuickSkin.LOGGER.error("Failed to read animation metadata for {}", hash, e);
            }
        }
        return null;
    }

    /**
     * Get all assets of a specific type
     */
    public List<AssetMetadata> getAssetsByType(String type) {
        return metadataCache.values().stream()
                .filter(meta -> type.equals(meta.type()))
                .sorted(Comparator.comparing(AssetMetadata::friendlyName))
                .toList();
    }

    /**
     * Get all skins
     */
    public List<AssetMetadata> getAllSkins() {
        return getAssetsByType("skin");
    }

    /**
     * Get all capes
     */
    public List<AssetMetadata> getAllCapes() {
        return getAssetsByType("cape");
    }

    /**
     * Get metadata by hash
     */
    public AssetMetadata getMetadata(String hash) {
        return metadataCache.get(hash);
    }

    /**
     * Get source file path by hash
     */
    public Path getSourcePath(String hash) {
        return hashToSourcePath.get(hash);
    }

    /**
     * Load texture data for a specific quality level
     * Returns raw PNG bytes
     */
    public byte[] loadTexture(String hash, TextureQuality quality) {
        Path sourcePath = hashToSourcePath.get(hash);
        if (sourcePath == null || !Files.exists(sourcePath)) {
            QuickSkin.LOGGER.warn("Asset not found for hash: {}", hash);
            return null;
        }

        try {
            BufferedImage image = ImageIO.read(sourcePath.toFile());
            if (image == null) {
                return null;
            }

            // Process based on quality
            return switch (quality) {
                case FULL -> Files.readAllBytes(sourcePath); // Original
                case PREVIEW -> HDTextureProcessor.createPreview(image);
                case THUMBNAIL -> HDTextureProcessor.createThumbnail(image);
                case NORMALIZED -> HDTextureProcessor.normalizeForVanilla(image);
            };

        } catch (IOException e) {
            QuickSkin.LOGGER.error("Failed to load texture: {}", hash, e);
            return null;
        }
    }

    /**
     * Save uploaded texture to assets directory
     * @param type "skin" or "cape"
     * @param name Friendly name for file
     * @param data PNG bytes
     * @return Hash of saved file, or null on error
     */
    public String saveTexture(String type, String name, byte[] data) {
        try {
            // Compute hash first
            String hash = HashUtil.computeHash(data);
            if (hash == null) {
                return null;
            }

            // Check if already exists
            if (metadataCache.containsKey(hash)) {
                QuickSkin.LOGGER.debug("Texture already exists: {}", hash);
                return hash;
            }

            // Determine target directory
            Path targetDir = "skin".equals(type) ? skinsDirectory : capesDirectory;

            // Create unique filename
            String fileName = name + ".png";
            Path targetPath = targetDir.resolve(fileName);

            // If file exists, append number
            int counter = 1;
            while (Files.exists(targetPath)) {
                fileName = name + "_" + counter + ".png";
                targetPath = targetDir.resolve(fileName);
                counter++;
            }

            // Write file
            Files.write(targetPath, data);
            QuickSkin.LOGGER.info("Saved texture: {}", targetPath);

            // Process and add to cache
            AssetMetadata metadata = processPngAsset(targetPath, type);
            if (metadata != null) {
                metadataCache.put(metadata.hash(), metadata);
                hashToSourcePath.put(metadata.hash(), targetPath);
            }

            return hash;

        } catch (Exception e) {
            QuickSkin.LOGGER.error("Failed to save texture", e);
            return null;
        }
    }

    /**
     * Delete local asset
     */
    public boolean deleteAsset(String hash) {
        AssetMetadata metadata = metadataCache.get(hash);
        if (metadata == null) {
            return false;
        }

        Path path = hashToSourcePath.get(hash);
        if (path == null || !Files.exists(path)) {
            return false;
        }

        try {
            Files.delete(path);
            metadataCache.remove(hash);
            hashToSourcePath.remove(hash);
            QuickSkin.LOGGER.info("Deleted asset: {}", path);
            return true;
        } catch (IOException e) {
            QuickSkin.LOGGER.error("Failed to delete asset: {}", path, e);
            return false;
        }
    }

    /**
     * Clear all caches and rediscover assets
     */
    public void reload() {
        QuickSkin.LOGGER.info("Reloading local assets...");
        discoverLocalAssets();
    }

    /**
     * Get total asset count
     */
    public int getAssetCount() {
        return metadataCache.size();
    }

    /**
     * Check if hash exists
     */
    public boolean hasAsset(String hash) {
        return metadataCache.containsKey(hash);
    }

    /**
     * Get ResourceLocation for a texture
     * Registers texture with Minecraft if not already registered
     */
    public ResourceLocation getTextureLocation(String hash, TextureQuality quality) {
        // Check if already registered
        Map<TextureQuality, ResourceLocation> qualityMap = textureRegistry.get(hash);
        if (qualityMap != null && qualityMap.containsKey(quality)) {
            return qualityMap.get(quality);
        }

        // Load and register texture
        byte[] textureData = loadTexture(hash, quality);
        if (textureData == null) {
            return null;
        }

        try {
            // Create BufferedImage from bytes
            BufferedImage bufferedImage = ImageIO.read(new ByteArrayInputStream(textureData));
            if (bufferedImage == null) {
                return null;
            }

            // Convert BufferedImage to NativeImage
            NativeImage nativeImage = convertToNativeImage(bufferedImage);
            if (nativeImage == null) {
                return null;
            }

            // Create dynamic texture
            DynamicTexture dynamicTexture = new DynamicTexture(nativeImage);

            // Register with texture manager
            ResourceLocation location = new ResourceLocation(
                    QuickSkin.MOD_ID,
                    "local/" + hash + "_" + quality.name().toLowerCase()
            );

            Minecraft.getInstance().getTextureManager().register(location, dynamicTexture);

            // Cache in registry
            qualityMap = textureRegistry.computeIfAbsent(hash, k -> new ConcurrentHashMap<>());
            qualityMap.put(quality, location);

            QuickSkin.LOGGER.debug("Registered texture: {} ({})", hash, quality);
            return location;

        } catch (IOException e) {
            QuickSkin.LOGGER.error("Failed to register texture: {}", hash, e);
            return null;
        }
    }

    @Nullable
    public BufferedImage getSourceImage(String hash) {
        Path sourcePath = getSourcePath(hash);
        if (sourcePath == null) {
            // Also check cache for animated capes converted from GIFs
            Path cachedAtlas = cacheDirectory.resolve("animated_capes").resolve(hash + ".png");
            if (Files.exists(cachedAtlas)) {
                sourcePath = cachedAtlas;
            } else {
                return null;
            }
        }
        try {
            return ImageIO.read(sourcePath.toFile());
        } catch (IOException e) {
            QuickSkin.LOGGER.error("Failed to read source image for hash {}: {}", hash, e.getMessage());
            return null;
        }
    }

    /**
     * Unregister texture from Minecraft's texture manager
     */
    public void unregisterTexture(String hash) {
        Map<TextureQuality, ResourceLocation> qualityMap = textureRegistry.remove(hash);
        if (qualityMap != null) {
            for (ResourceLocation location : qualityMap.values()) {
                Minecraft.getInstance().getTextureManager().release(location);
            }
        }
    }

    /**
     * Clear all registered textures
     */
    public void unregisterAllTextures() {
        for (String hash : textureRegistry.keySet()) {
            unregisterTexture(hash);
        }
        textureRegistry.clear();
    }

    /**
     * Get the skins directory path
     */
    public Path getSkinsDirectory() {
        return skinsDirectory;
    }

    /**
     * Get the capes directory path
     */
    public Path getCapesDirectory() {
        return capesDirectory;
    }

    /**
     * Gets the cache directory for processed assets.
     */
    public Path getCacheDirectory() {
        return cacheDirectory;
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
                nativeImage.setPixelRGBA(x, y, abgr);
            }
        }

        return nativeImage;
    }
}