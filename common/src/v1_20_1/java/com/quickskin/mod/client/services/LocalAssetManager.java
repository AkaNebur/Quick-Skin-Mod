package com.quickskin.mod.client.services;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.common.data.AnimationMetadata;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.SkinPreferences;
import com.quickskin.mod.common.data.SkinResolution;
import com.quickskin.mod.common.data.SkinSortMode;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.common.util.HashUtil;
import com.quickskin.mod.common.util.HDTextureProcessor;
import com.quickskin.mod.common.util.SkinModelDetector;
import com.quickskin.mod.config.ClientConfig;
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
import java.io.InputStream;
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

    // Per-skin preferences
    private SkinPreferences skinPreferences;
    private Path preferencesFile;

    /**
     * Result enum for rename operations
     */
    public enum RenameResult {
        SUCCESS,
        NAME_TAKEN,
        INVALID_NAME,
        IO_ERROR,
        NOT_FOUND
    }

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
        }

        // Load skin preferences
        preferencesFile = PlatformHelper.getConfigDirectory().resolve("skin-preferences.json");
        skinPreferences = SkinPreferences.load(preferencesFile);

        // Discover assets
        discoverLocalAssets();
    }

    /**
     * Scan filesystem for skins and capes, build metadata cache
     */
    public void discoverLocalAssets() {
        metadataCache.clear();
        hashToSourcePath.clear();

        // Scan skins directory
        scanDirectory(skinsDirectory, "skin");

        // Scan capes directory
        scanDirectory(capesDirectory, "cape");

        // Scan CPM models directory if CPM is available
        if (com.quickskin.mod.client.compat.CPMCompatIntegration.isAvailable()) {
            scanCpmModels();
        }
    }

    /**
     * Scan CPM's player_models directory for .cpmmodel files
     */
    private void scanCpmModels() {
        Path modelsDir = com.quickskin.mod.client.compat.CPMCompatIntegration.getCPMModelsDirectory();
        if (!Files.exists(modelsDir)) return;

        try (Stream<Path> paths = Files.walk(modelsDir)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String fileName = path.getFileName().toString();
                if (!fileName.toLowerCase(Locale.ROOT).endsWith(".cpmmodel")) continue;

                try {
                    String hash = HashUtil.computeFileHash(path);
                    if (hash == null) continue;

                    // Parse the .cpmmodel to get its name
                    var info = com.quickskin.mod.client.compat.CPMCompatIntegration.parseCpmModelInfo(path);
                    String friendlyName = info != null ? info.name : fileName.substring(0, fileName.length() - 9);

                    long fileSize = Files.size(path);
                    long lastModifiedTime = Files.getLastModifiedTime(path).toMillis();

                    AssetMetadata metadata = AssetMetadata.forCpmModel(hash, friendlyName, path, fileSize, lastModifiedTime);
                    metadataCache.put(hash, metadata);
                    hashToSourcePath.put(hash, path);

                    // Cache icon PNG bytes if available
                    if (info != null && info.iconPngBytes != null) {
                        Path iconPath = cacheDirectory.resolve("cpm_icons").resolve(hash + ".png");
                        Files.createDirectories(iconPath.getParent());
                        Files.write(iconPath, info.iconPngBytes);
                    }
                } catch (Exception e) {
                    // Skip invalid files
                }
            }
        } catch (IOException e) {
            // Directory walk failed
        }
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
                String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);

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
                    AssetMetadata metadata = processGifAsset(path);
                    if (metadata != null) {
                        metadataCache.put(metadata.hash(), metadata);
                        hashToSourcePath.put(metadata.hash(), path);
                        count++;
                    }
                }
            }
        } catch (IOException e) {
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

            // For capes, check if it's an old animation strip missing metadata and generate it.
            if ("cape".equals(type)) {
                Path metadataPathForCheck = cacheDirectory.resolve(hash + ".json");
                if (!Files.exists(metadataPathForCheck)) {
                    // No metadata found. Let's see if this PNG is a multi-frame strip.
                    try (InputStream is = Files.newInputStream(path)) {
                        BufferedImage imageForCheck = ImageIO.read(is);
                        if (imageForCheck != null) {
                            int width = imageForCheck.getWidth();
                            int height = imageForCheck.getHeight();
                            // Cape frames have a 2:1 aspect ratio.
                            int frameHeight = width / 2;

                            if (width > 0 && frameHeight > 0 && height > frameHeight && height % frameHeight == 0) {
                                int frameCount = height / frameHeight;
                                if (frameCount > 1) {
                                        List<AnimationMetadata.FrameData> frames = new ArrayList<>();
                                    for (int i = 0; i < frameCount; i++) {
                                        // Use 50ms per frame (20 FPS) as a sensible default.
                                        frames.add(new AnimationMetadata.FrameData(50, i));
                                    }
                                    AnimationMetadata generatedMeta = new AnimationMetadata(frames, frameCount);

                                    Files.writeString(metadataPathForCheck, generatedMeta.toJson());
                                }
                            }
                        }
                    } catch (Exception e) {
                    }
                }
            }

            // Check for animation metadata first
            Path metadataPath = cacheDirectory.resolve(hash + ".json");
            AnimationMetadata animMeta = null;
            if (Files.exists(metadataPath)) {
                try {
                    String json = Files.readString(metadataPath);
                    animMeta = AnimationMetadata.fromJson(json);
                } catch (IOException e) {
                }
            }

            // Read image to get dimensions
            BufferedImage image = ImageIO.read(path.toFile());
            if (image == null) {
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
                    resolution = SkinResolution.findNearest(width, frameHeight);
                    if (resolution == null) {
                        return null;
                    }
                }
            } else {
                // This is a static asset or a PNG animation strip without metadata.
                if ("skin".equals(type)) {
                    resolution = SkinResolution.fromDimensions(width, height);
                    if (resolution == null) {
                        resolution = SkinResolution.findNearest(width, height);
                        if (resolution == null) {
                            return null;
                        }
                        // Resize the image and overwrite the file so loadTexture works correctly
                        image = HDTextureProcessor.resizeToResolution(image, resolution);
                        ImageIO.write(image, "PNG", path.toFile());
                        width = image.getWidth();
                        height = image.getHeight();
                    }
                    skinModel = SkinModelDetector.detectSkinModel(image);
                } else { // Cape logic for static capes or PNG strips
                    int frameHeight = width / 2;
                    if (width > 0 && frameHeight > 0 && height % frameHeight == 0) {
                        frameCount = height / frameHeight;
                        isAnimated = frameCount > 1;
                        resolution = SkinResolution.fromDimensions(width, frameHeight);
                        if (resolution == null) {
                            resolution = SkinResolution.findNearest(width, frameHeight);
                            if (resolution == null) {
                                return null;
                            }
                        }
                    } else {
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

            // Get file size and modification time
            long fileSize = Files.size(path);
            long lastModifiedTime = Files.getLastModifiedTime(path).toMillis();

            // Create metadata
            if ("skin".equals(type)) {
                return AssetMetadata.forSkin(hash, friendlyName, path, resolution, fileSize, skinModel, lastModifiedTime);
            } else {
                if (isAnimated) {
                    return AssetMetadata.forAnimatedCape(hash, friendlyName, path, resolution, fileSize, frameCount, lastModifiedTime);
                } else {
                    return AssetMetadata.forCape(hash, friendlyName, path, resolution, fileSize, lastModifiedTime);
                }
            }

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Process GIF asset (animated cape) and create metadata
     * Loads GIF frames directly using STB Image
     */
    private AssetMetadata processGifAsset(Path path) {
        com.quickskin.mod.common.util.StbGifLoader.GifLoadResult result = null;
        try {
            // Load GIF using STB Image
            try (var inputStream = Files.newInputStream(path)) {
                result = com.quickskin.mod.common.util.StbGifLoader.loadGif(inputStream);
            }

            // Compute hash of original GIF
            String hash = HashUtil.computeFileHash(path);
            if (hash == null) {
                return null;
            }

            // Create PNG atlas from frames (stack vertically)
            int width = result.frameWidth();
            int height = result.frameHeight();
            int frameCount = result.frames().length;
            int atlasHeight = height * frameCount;

            NativeImage atlas = new NativeImage(width, atlasHeight, false);

            // Copy each frame into the atlas
            for (int i = 0; i < frameCount; i++) {
                NativeImage frame = result.frames()[i];
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        atlas.setPixelRGBA(x, i * height + y, frame.getPixelRGBA(x, y));
                    }
                }
            }

            // Save PNG atlas to cache
            Path cacheDir = cacheDirectory.resolve("animated_capes");
            Files.createDirectories(cacheDir);
            Path atlasPath = cacheDir.resolve(hash + ".png");
            atlas.writeToFile(atlasPath);
            atlas.close();

            // Save animation metadata to cache
            Path metadataPath = cacheDirectory.resolve(hash + ".json");
            Files.writeString(metadataPath, result.metadata().toJson());

            // Get friendly name
            String friendlyName = path.getFileName().toString();
            int dotIndex = friendlyName.lastIndexOf('.');
            if (dotIndex > 0) {
                friendlyName = friendlyName.substring(0, dotIndex);
            }

            // Get file size and modification time
            long fileSize = Files.size(path);
            long lastModifiedTime = Files.getLastModifiedTime(path).toMillis();

            // Get resolution from first frame
            SkinResolution resolution = SkinResolution.fromDimensions(width, height);
            if (resolution == null) {
                resolution = SkinResolution.STANDARD;
            }

            // Create metadata for animated cape
            return AssetMetadata.forAnimatedCape(
                    hash,
                    friendlyName,
                    path,
                    resolution,
                    fileSize,
                    frameCount,
                    lastModifiedTime
            );

        } catch (Exception e) {
            return null;
        } finally {
            // Clean up frames
            if (result != null) {
                result.close();
            }
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
            }
        }
        return null;
    }

    /**
     * Get all assets of a specific type
     */
    public List<AssetMetadata> getAssetsByType(String type) {
        String playerOwnSkinHash = ClientConfig.getInstance().playerOwnSkinHash;
        SkinSortMode sortMode = ClientConfig.getInstance().getSkinSortMode();

        return metadataCache.values().stream()
                .filter(meta -> type.equals(meta.type()))
                .sorted(getSortComparator(sortMode, playerOwnSkinHash))
                .toList();
    }

    /**
     * Get comparator for sorting assets based on sort mode
     */
    private Comparator<AssetMetadata> getSortComparator(SkinSortMode mode, String playerSkinHash) {
        return switch (mode) {
            case LATEST_LAST -> Comparator
                    .comparing((AssetMetadata meta) -> !meta.hash().equals(playerSkinHash))
                    .thenComparing(AssetMetadata::friendlyName);

            case LATEST_FIRST -> Comparator
                    .comparing((AssetMetadata meta) -> !meta.hash().equals(playerSkinHash))
                    .thenComparing(Comparator.comparing(AssetMetadata::lastModifiedTime).reversed());

            case ALPHABETICAL -> Comparator
                    .comparing((AssetMetadata meta) -> !meta.hash().equals(playerSkinHash))
                    .thenComparing(AssetMetadata::friendlyName);
        };
    }

    /**
     * Get all skins (including CPM models)
     */
    public List<AssetMetadata> getAllSkins() {
        String playerOwnSkinHash = ClientConfig.getInstance().playerOwnSkinHash;
        SkinSortMode sortMode = ClientConfig.getInstance().getSkinSortMode();

        return metadataCache.values().stream()
                .filter(meta -> "skin".equals(meta.type()) || "cpmmodel".equals(meta.type()))
                .sorted(getSortComparator(sortMode, playerOwnSkinHash))
                .toList();
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
            return null;
        }

        try {
            BufferedImage image = ImageIO.read(sourcePath.toFile());
            if (image == null) {
                return null;
            }

            // Check if this is a skin and transparency should be disabled
            AssetMetadata metadata = getMetadata(hash);
            boolean isSkin = metadata != null && "skin".equals(metadata.type());
            boolean shouldRemoveTransparency = isSkin &&
                    com.quickskin.mod.config.ClientConfig.getInstance().shouldDisableSkinTransparency();

            // Apply transparency removal if needed
            if (shouldRemoveTransparency) {
                image = HDTextureProcessor.removeTransparency(image);
            }

            // Process based on quality
            return switch (quality) {
                case FULL -> {
                    // For FULL quality, we need to convert the image back to bytes
                    // since we may have modified it (transparency removal)
                    if (shouldRemoveTransparency) {
                        yield HDTextureProcessor.imageToPng(image);
                    } else {
                        yield Files.readAllBytes(sourcePath); // Original
                    }
                }
                case PREVIEW -> HDTextureProcessor.createPreview(image);
                case THUMBNAIL -> HDTextureProcessor.createThumbnail(image);
                case NORMALIZED -> HDTextureProcessor.normalizeForVanilla(image);
            };

        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Load cached CPM model icon PNG bytes
     */
    private byte[] loadCpmModelIcon(String hash) {
        Path iconPath = cacheDirectory.resolve("cpm_icons").resolve(hash + ".png");
        if (Files.exists(iconPath)) {
            try {
                return Files.readAllBytes(iconPath);
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Delete local asset
     */
    public void deleteAsset(String hash) {
        AssetMetadata metadata = metadataCache.get(hash);
        if (metadata == null) {
            return;
        }

        Path path = hashToSourcePath.get(hash);
        if (path == null || !Files.exists(path)) {
            return;
        }

        try {
            Files.delete(path);
            metadataCache.remove(hash);
            hashToSourcePath.remove(hash);

            // Also remove preferences for this skin
            if (skinPreferences != null) {
                skinPreferences.remove(hash);
                savePreferences();
            }

        } catch (IOException e) {
        }
    }

    /**
     * Rename a local asset file
     * @param hash The hash of the asset to rename
     * @param newFriendlyName The new friendly name (without extension)
     * @return RenameResult indicating success or failure reason
     */
    public RenameResult renameLocalAsset(String hash, String newFriendlyName) {
        // Validate the new name
        if (newFriendlyName == null || newFriendlyName.trim().isEmpty()) {
            return RenameResult.INVALID_NAME;
        }

        // Check for invalid characters in filename
        String sanitizedName = newFriendlyName.trim();
        if (sanitizedName.matches(".*[<>:\"/\\\\|?*].*")) {
            return RenameResult.INVALID_NAME;
        }

        // Get the metadata for this asset
        AssetMetadata metadata = metadataCache.get(hash);
        if (metadata == null) {
            return RenameResult.NOT_FOUND;
        }

        // Get the current file path
        Path currentPath = hashToSourcePath.get(hash);
        if (currentPath == null || !Files.exists(currentPath)) {
            return RenameResult.NOT_FOUND;
        }

        // Determine the parent directory and file extension
        Path parentDir = currentPath.getParent();
        String extension = currentPath.getFileName().toString();
        int dotIndex = extension.lastIndexOf('.');
        if (dotIndex > 0) {
            extension = extension.substring(dotIndex);
        } else {
            extension = ".png"; // Default to .png if no extension found
        }

        // Create the new file path
        Path newPath = parentDir.resolve(sanitizedName + extension);

        // Check if a file with the new name already exists
        if (Files.exists(newPath) && !newPath.equals(currentPath)) {
            return RenameResult.NAME_TAKEN;
        }

        try {
            // Rename the file
            Files.move(currentPath, newPath, StandardCopyOption.REPLACE_EXISTING);

            // Update the metadata cache with new friendly name and path
            AssetMetadata updatedMetadata;
            if (metadata.isCpmModel()) {
                updatedMetadata = AssetMetadata.forCpmModel(
                        metadata.hash(),
                        sanitizedName,
                        newPath,
                        metadata.fileSize(),
                        metadata.lastModifiedTime()
                );
            } else if ("skin".equals(metadata.type())) {
                updatedMetadata = AssetMetadata.forSkin(
                        metadata.hash(),
                        sanitizedName,
                        newPath,
                        metadata.resolution(),
                        metadata.fileSize(),
                        metadata.skinModel(),
                        metadata.lastModifiedTime()
                );
            } else if (metadata.isAnimated()) {
                updatedMetadata = AssetMetadata.forAnimatedCape(
                        metadata.hash(),
                        sanitizedName,
                        newPath,
                        metadata.resolution(),
                        metadata.fileSize(),
                        metadata.frameCount(),
                        metadata.lastModifiedTime()
                );
            } else {
                updatedMetadata = AssetMetadata.forCape(
                        metadata.hash(),
                        sanitizedName,
                        newPath,
                        metadata.resolution(),
                        metadata.fileSize(),
                        metadata.lastModifiedTime()
                );
            }

            metadataCache.put(hash, updatedMetadata);
            hashToSourcePath.put(hash, newPath);

            return RenameResult.SUCCESS;

        } catch (IOException e) {
            return RenameResult.IO_ERROR;
        }
    }

    /**
     * Clear all caches and rediscover assets
     */
    public void reload() {
        discoverLocalAssets();
    }

    /**
     * Clear texture cache to force re-registration with new settings
     * Call this when transparency settings change
     */
    public void clearTextureCache() {
        // Unregister all textures from Minecraft's texture manager
        Minecraft mc = Minecraft.getInstance();
        for (Map<TextureQuality, ResourceLocation> qualityMap : textureRegistry.values()) {
            for (ResourceLocation location : qualityMap.values()) {
                try {
                    mc.getTextureManager().release(location);
                } catch (Exception e) {
                    // Failed to release texture
                }
            }
        }

        // Clear our cache
        textureRegistry.clear();

        // Clear Ears features cache
        if (com.quickskin.mod.client.compat.EarsCompatIntegration.isAvailable()) {
            com.quickskin.mod.client.compat.EarsCompatIntegration.clearAllFeatures();
        }
    }

    /**
     * Clear only skin textures from cache (not capes)
     * Call this when skin transparency settings change
     */
    public void clearSkinTextureCache() {

        Minecraft mc = Minecraft.getInstance();
        List<String> hashesToClear = new ArrayList<>();

        // Find all skin hashes
        for (String hash : textureRegistry.keySet()) {
            AssetMetadata metadata = getMetadata(hash);
            if (metadata != null && "skin".equals(metadata.type())) {
                hashesToClear.add(hash);
            }
        }

        // Unregister and remove skin textures only
        for (String hash : hashesToClear) {
            Map<TextureQuality, ResourceLocation> qualityMap = textureRegistry.get(hash);
            if (qualityMap != null) {
                for (ResourceLocation location : qualityMap.values()) {
                    try {
                        mc.getTextureManager().release(location);
                    } catch (Exception e) {
                        // Failed to release texture
                    }
                }
                textureRegistry.remove(hash);
            }
        }
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

        // For cpmmodel entries, load the cached icon PNG
        AssetMetadata meta = getMetadata(hash);
        byte[] textureData;
        if (meta != null && meta.isCpmModel()) {
            textureData = loadCpmModelIcon(hash);
        } else {
            textureData = loadTexture(hash, quality);
        }
        if (textureData == null) {
            return null;
        }

        try {
            // Load directly as NativeImage from PNG bytes (handles pixel format automatically)
            NativeImage nativeImage = NativeImage.read(new ByteArrayInputStream(textureData));

            // For animated capes, only register the FIRST FRAME on GPU instead of the full atlas.
            // The animation system keeps the atlas in RAM and handles frame switching separately.
            // This prevents massive VRAM waste and fixes incorrect UV rendering when
            // the animation texture is used as a fallback.
            if (meta != null && meta.isAnimated() && meta.frameCount() > 1 && quality == TextureQuality.FULL) {
                int frameHeight = nativeImage.getHeight() / meta.frameCount();
                if (frameHeight > 0 && frameHeight < nativeImage.getHeight()) {
                    NativeImage firstFrame = new NativeImage(nativeImage.getWidth(), frameHeight, false);
                    for (int y = 0; y < frameHeight; y++) {
                        for (int x = 0; x < nativeImage.getWidth(); x++) {
                            firstFrame.setPixelRGBA(x, y, nativeImage.getPixelRGBA(x, y));
                        }
                    }
                    nativeImage.close();
                    nativeImage = firstFrame;
                }
            }

            // Create dynamic texture
            DynamicTexture dynamicTexture = new DynamicTexture(nativeImage);

            // Register with texture manager
            ResourceLocation location = new ResourceLocation(
                    QuickSkin.MOD_ID,
                    "local/" + hash + "_" + quality.name().toLowerCase(Locale.ROOT)
            );

            Minecraft.getInstance().getTextureManager().register(location, dynamicTexture);

            // Cache transparency info for the first-person arm rendering mixin
            // DynamicTextures aren't accessible via resource manager, so we check here
            boolean hasAlpha = false;
            for (int y = 0; y < nativeImage.getHeight() && !hasAlpha; y += Math.max(1, nativeImage.getHeight() / 32)) {
                for (int x = 0; x < nativeImage.getWidth() && !hasAlpha; x += Math.max(1, nativeImage.getWidth() / 32)) {
                    int pixel = nativeImage.getPixelRGBA(x, y);
                    int alpha = (pixel >> 24) & 0xFF;
                    if (alpha < 255) hasAlpha = true;
                }
            }
            com.quickskin.mod.common.util.TextureAlphaDetector.cacheTransparencyResult(location, hasAlpha);

            // Parse Ears features from the original unprocessed image (preserving alpha for Alfalfa data)
            AssetMetadata metadata = getMetadata(hash);
            if (metadata != null && "skin".equals(metadata.type())
                    && com.quickskin.mod.client.compat.EarsCompatIntegration.isAvailable()) {
                BufferedImage originalImage = getSourceImage(hash);
                if (originalImage != null) {
                    com.quickskin.mod.client.compat.EarsCompatIntegration.parseAndStoreFeatures(location, originalImage);
                }
            }

            // Cache in registry
            qualityMap = textureRegistry.computeIfAbsent(hash, k -> new ConcurrentHashMap<>());
            qualityMap.put(quality, location);

            return location;

        } catch (IOException e) {
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
            return null;
        }
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

    /**
     * Get model type preference for a skin
     * @param hash Skin hash
     * @return Model type preference ("auto", "classic", or "slim")
     */
    public String getSkinModelPreference(String hash) {
        if (skinPreferences == null) {
            return "auto";
        }
        return skinPreferences.getModelType(hash);
    }

    /**
     * Set model type preference for a skin
     * @param hash Skin hash
     * @param modelType Model type ("auto", "classic", or "slim")
     */
    public void setSkinModelPreference(String hash, String modelType) {
        if (skinPreferences != null) {
            skinPreferences.setModelType(hash, modelType);
            savePreferences();
        }
    }

    /**
     * Save skin preferences to disk
     */
    private void savePreferences() {
        if (skinPreferences != null && preferencesFile != null) {
            skinPreferences.save(preferencesFile);
        }
    }
}