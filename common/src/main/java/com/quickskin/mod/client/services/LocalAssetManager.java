package com.quickskin.mod.client.services;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.common.data.AnimationMetadata;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.SkinPreferences;
import com.quickskin.mod.common.data.SkinResolution;
import com.quickskin.mod.common.data.SkinSortMode;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.common.util.HashUtil;
import com.quickskin.mod.common.util.BoundedFileReader;
import com.quickskin.mod.common.util.HDTextureProcessor;
import com.quickskin.mod.common.util.SkinModelDetector;
import com.quickskin.mod.common.util.SafeImageReader;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.networking.NetworkSecurity;
import com.quickskin.mod.platform.PlatformHelper;
import com.quickskin.mod.platform.MinecraftCompat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.SoftReference;
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

    private static final int MAX_ASSET_BYTES = (int) SafeImageReader.MAX_ENCODED_BYTES;
    private static final int MAX_ANIMATION_FRAMES = 256;
    private static final int MAX_SCAN_CANDIDATES = 4096;
    private static final int MAX_SCAN_DEPTH = 32;

    private static LocalAssetManager instance;

    // Asset discovery
    private final Map<String, AssetMetadata> metadataCache = new ConcurrentHashMap<>();
    private final Map<String, Path> hashToSourcePath = new ConcurrentHashMap<>();
    private final Map<String, String> legacyCapeHashAliases = new ConcurrentHashMap<>();

    // Texture registration
    private final Map<String, Map<TextureQuality, Identifier>> textureRegistry = new ConcurrentHashMap<>();

    // F8: GC-friendly cache of decoded source BufferedImages
    private final Map<String, SoftReference<BufferedImage>> sourceImageCache = new ConcurrentHashMap<>();

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
    public synchronized void init() {
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
            QuickSkin.LOGGER.error("Unable to create QuickSkin local asset directories", e);
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
    public synchronized void discoverLocalAssets() {
        metadataCache.clear();
        hashToSourcePath.clear();
        legacyCapeHashAliases.clear();

        // Scan skins directory
        scanDirectory(skinsDirectory, "skin");

        // Scan capes directory
        scanDirectory(capesDirectory, "cape");

        // CPM is optional: do not even expose model files when the mod is absent.
        if (com.quickskin.mod.client.compat.CPMCompatIntegration.isAvailable()) {
            com.quickskin.mod.client.compat.CpmModelWorkflow.reconcilePendingSkinModeReset();
            scanCpmModels();
        } else {
            com.quickskin.mod.client.compat.CpmModelWorkflow.sanitizeUnavailableState();
        }
        com.quickskin.mod.client.compat.CpmModelWorkflow.sanitizeMissingActiveModel();
        LocalCapeHashMigration.migrate(legacyCapeHashAliases);
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

        try (Stream<Path> paths = Files.walk(directory, MAX_SCAN_DEPTH)) {
            List<Path> candidates = paths.limit(MAX_SCAN_CANDIDATES)
                    .filter(Files::isRegularFile).toList();
            if (candidates.size() == MAX_SCAN_CANDIDATES) {
                QuickSkin.LOGGER.warn("Local asset scan reached the {} file cap in {}", MAX_SCAN_CANDIDATES, directory);
            }
            for (Path path : candidates) {
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
                        // Point to the cached PNG atlas so loadTexture/getSourceImage get the
                        // full multi-frame image. ImageIO.read of a .gif only returns the first
                        // frame, so the atlas path is what downstream consumers actually need.
                        // Fall back to the .gif path if the atlas wasn't written (shouldn't happen).
                        Path cachedAtlas = cacheDirectory.resolve("animated_capes").resolve(metadata.hash() + ".png");
                        hashToSourcePath.put(metadata.hash(), Files.exists(cachedAtlas) ? cachedAtlas : path);
                        count++;
                    }
                }
            }
        } catch (IOException e) {
            QuickSkin.LOGGER.warn("Unable to scan QuickSkin asset directory {}", directory, e);
        }

        return count;
    }

    /** Scan CPM's recursive player_models tree for standalone model files. */
    private void scanCpmModels() {
        Path modelsDirectory = com.quickskin.mod.client.compat.CPMCompatIntegration
                .getCPMModelsDirectory();
        if (!Files.isDirectory(modelsDirectory)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(modelsDirectory, MAX_SCAN_DEPTH)) {
            List<Path> candidates = paths.limit(MAX_SCAN_CANDIDATES)
                    .filter(Files::isRegularFile).toList();
            if (candidates.size() == MAX_SCAN_CANDIDATES) {
                QuickSkin.LOGGER.warn("CPM model scan reached the {} file cap in {}", MAX_SCAN_CANDIDATES, modelsDirectory);
            }
            for (Path path : candidates) {
                String fileName = path.getFileName().toString();
                if (!fileName.toLowerCase(Locale.ROOT).endsWith(".cpmmodel")) {
                    continue;
                }
                try {
                    scanCpmModel(path, fileName);
                } catch (IOException | RuntimeException ignored) {
                    // Skip only the unreadable candidate and continue the recursive scan.
                    QuickSkin.LOGGER.debug("Skipping invalid CPM model {}", path, ignored);
                }
            }
        } catch (IOException ignored) {
            // An unreadable optional directory must not affect normal skins/capes.
            QuickSkin.LOGGER.warn("Unable to scan CPM model directory {}", modelsDirectory, ignored);
        }
    }

    private void scanCpmModel(Path path, String fileName) throws IOException {
        byte[] modelBytes = BoundedFileReader.readBytes(path, MAX_ASSET_BYTES);
        String hash = HashUtil.computeHash(modelBytes);
        if (hash == null) {
            return;
        }

        com.quickskin.mod.client.compat.CPMCompatIntegration.CpmModelInfo info =
                com.quickskin.mod.client.compat.CPMCompatIntegration.parseCpmModelInfo(path);
        String fallbackName = fileName.substring(0, fileName.length() - 9);
        String friendlyName = info != null && info.name != null && !info.name.isBlank()
                ? info.name
                : fallbackName;
        AssetMetadata metadata = AssetMetadata.forCpmModel(
                hash,
                friendlyName,
                path,
                modelBytes.length,
                Files.getLastModifiedTime(path).toMillis()
        );
        metadataCache.put(hash, metadata);
        hashToSourcePath.put(hash, path);

        if (info != null && info.iconPngBytes != null && info.iconPngBytes.length <= MAX_ASSET_BYTES
                && SafeImageReader.readPng(info.iconPngBytes) != null) {
            Path iconPath = getCpmIconPath(hash);
            Files.createDirectories(iconPath.getParent());
            Files.write(iconPath, info.iconPngBytes);
        }
    }

    /**
     * Process PNG asset and create metadata
     */
    private AssetMetadata processPngAsset(Path path, String type) {
        try {
            byte[] sourceBytes = BoundedFileReader.readBytes(path, MAX_ASSET_BYTES);
            String legacyHash = HashUtil.computeHash(sourceBytes);
            String hash = HashUtil.computeAssetHash(sourceBytes, type);
            if (hash == null || legacyHash == null) {
                return null;
            }
            if ("cape".equals(type)) legacyCapeHashAliases.put(legacyHash, hash);

            // F9: read image once upfront, use for both metadata synthesis and dimension check.
            BufferedImage image = SafeImageReader.readPng(sourceBytes);
            if (image == null) {
                return null;
            }

            AnimationMetadata animMeta = null;
            if ("cape".equals(type)) {
                animMeta = readAnimationMetadataFile(hash);
                if (animMeta == null && !hash.equals(legacyHash)) {
                    animMeta = readAnimationMetadataFile(legacyHash);
                }
                if (animMeta == null) {
                    int width = image.getWidth();
                    int height = image.getHeight();
                    int frameHeight = width / 2; // Cape frames have a 2:1 aspect ratio.

                    if (width > 0 && frameHeight > 0 && height > frameHeight && height % frameHeight == 0) {
                        int fc = height / frameHeight;
                        if (fc > 1 && fc <= MAX_ANIMATION_FRAMES) {
                            List<AnimationMetadata.FrameData> frames = new ArrayList<>();
                            for (int i = 0; i < fc; i++) {
                                // Use 50ms per frame (20 FPS) as a sensible default.
                                frames.add(new AnimationMetadata.FrameData(50, i));
                            }
                            animMeta = new AnimationMetadata(frames, fc);
                        }
                    }
                }
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
                if (frameCount < 1 || frameCount > MAX_ANIMATION_FRAMES
                        || height % frameCount != 0) return null;
                int frameHeight = (frameCount > 0) ? height / frameCount : height;
                resolution = SkinResolution.fromDimensions(width, frameHeight);
                if (resolution == null) {
                    resolution = SkinResolution.findNearest(width, frameHeight);
                    if (resolution == null) {
                        return null;
                    }
                    image = HDTextureProcessor.resizeAnimationStrip(image, resolution.getWidth());
                    if (!ImageIO.write(image, "PNG", path.toFile())) return null;
                    width = image.getWidth();
                    height = image.getHeight();
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
                        if (!ImageIO.write(image, "PNG", path.toFile())) return null;
                        width = image.getWidth();
                        height = image.getHeight();
                    }
                    skinModel = SkinModelDetector.detectSkinModel(image);
                } else { // Cape logic for static capes or PNG strips
                    int frameHeight = width / 2;
                    if (width > 0 && frameHeight > 0 && height % frameHeight == 0) {
                        frameCount = height / frameHeight;
                        if (frameCount < 1 || frameCount > MAX_ANIMATION_FRAMES) return null;
                        isAnimated = frameCount > 1;
                        resolution = SkinResolution.fromDimensions(width, frameHeight);
                        if (resolution == null) {
                            resolution = SkinResolution.findNearest(width, frameHeight);
                            if (resolution == null) {
                                return null;
                            }
                            // Resize the cape to valid dimensions and overwrite the file
                            if (isAnimated) {
                                image = HDTextureProcessor.resizeAnimationStrip(image, resolution.getWidth());
                            } else {
                                image = HDTextureProcessor.resizeToResolution(image, resolution);
                            }
                            if (!ImageIO.write(image, "PNG", path.toFile())) return null;
                            width = image.getWidth();
                            height = image.getHeight();
                        }
                    } else {
                        return null;
                    }
                }
            }

            String originalHash = hash;
            byte[] finalBytes = BoundedFileReader.readBytes(path, MAX_ASSET_BYTES);
            String finalLegacyHash = HashUtil.computeHash(finalBytes);
            hash = HashUtil.computeAssetHash(finalBytes, type);
            if (!NetworkSecurity.isValidContentId(hash)
                    || !NetworkSecurity.isValidContentId(finalLegacyHash)) return null;
            if ("cape".equals(type)) {
                legacyCapeHashAliases.put(legacyHash, hash);
                legacyCapeHashAliases.put(finalLegacyHash, hash);
            }
            if (animMeta != null) {
                writeAnimationMetadataFile(hash, animMeta);
                if (!originalHash.equals(hash)) deleteAnimationMetadataFile(originalHash);
            }

            // Get friendly name (filename without extension)
            String friendlyName = path.getFileName().toString();
            int dotIndex = friendlyName.lastIndexOf('.');
            if (dotIndex > 0) {
                friendlyName = friendlyName.substring(0, dotIndex);
            }

            // Get file size and modification time
            long fileSize = finalBytes.length;
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
            byte[] sourceBytes = BoundedFileReader.readBytes(path, MAX_ASSET_BYTES);
            String legacyHash = HashUtil.computeHash(sourceBytes);
            String hash = HashUtil.computeAssetHash(sourceBytes, "cape");
            if (hash == null || legacyHash == null) {
                return null;
            }
            legacyCapeHashAliases.put(legacyHash, hash);

            // F7: cache-hit fast path. Skip STB decode if cached atlas + metadata
            // exist and are newer than the source GIF.
            Path cachedAtlasFast = NetworkSecurity.resolveContained(
                    cacheDirectory.resolve("animated_capes"), hash, ".png");
            AnimationMetadata cachedMeta = readAnimationMetadataFile(hash);
            if (!hash.equals(legacyHash) && cachedAtlasFast != null) {
                Path legacyAtlas = NetworkSecurity.resolveContained(
                        cacheDirectory.resolve("animated_capes"), legacyHash, ".png");
                AnimationMetadata legacyMeta = readAnimationMetadataFile(legacyHash);
                if (cachedMeta == null && legacyMeta != null) {
                    cachedMeta = legacyMeta;
                    writeAnimationMetadataFile(hash, legacyMeta);
                }
                if (!Files.exists(cachedAtlasFast) && legacyAtlas != null
                        && !Files.isSymbolicLink(legacyAtlas)
                        && Files.isRegularFile(legacyAtlas)) {
                    Files.createDirectories(cachedAtlasFast.getParent());
                    Files.copy(legacyAtlas, cachedAtlasFast, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (cachedAtlasFast != null && !Files.isSymbolicLink(cachedAtlasFast)
                    && Files.exists(cachedAtlasFast) && cachedMeta != null) {
                try {
                    long srcMtime = Files.getLastModifiedTime(path).toMillis();
                    long atlasMtime = Files.getLastModifiedTime(cachedAtlasFast).toMillis();
                    Path cachedMetaFast = NetworkSecurity.resolveContained(cacheDirectory, hash, ".json");
                    long metaMtime = Files.getLastModifiedTime(cachedMetaFast).toMillis();
                    if (atlasMtime >= srcMtime && metaMtime >= srcMtime) {
                        BufferedImage atlasImg = SafeImageReader.readPng(cachedAtlasFast);
                        if (atlasImg != null && cachedMeta.frameCount() > 0
                                && cachedMeta.frameCount() <= MAX_ANIMATION_FRAMES
                                && atlasImg.getHeight() % cachedMeta.frameCount() == 0) {
                            int cw = atlasImg.getWidth();
                            int ch = atlasImg.getHeight() / cachedMeta.frameCount();
                            SkinResolution res = SkinResolution.fromDimensions(cw, ch);
                            if (res == null) res = SkinResolution.STANDARD;
                            String fn = path.getFileName().toString();
                            int di = fn.lastIndexOf('.');
                            if (di > 0) fn = fn.substring(0, di);
                            return AssetMetadata.forAnimatedCape(
                                hash, fn, path, res,
                                sourceBytes.length, cachedMeta.frameCount(), srcMtime);
                        }
                    }
                } catch (IOException ignored) {
                    // Fall through to full decode on any cache read failure.
                }
            }

            // Load GIF using STB Image
            try (var inputStream = new ByteArrayInputStream(sourceBytes)) {
                result = com.quickskin.mod.common.util.StbGifLoader.loadGif(inputStream);
            }

            // Create PNG atlas from frames (stack vertically)
            int width = result.frameWidth();
            int height = result.frameHeight();
            int frameCount = result.frames().length;
            int atlasHeight = height * frameCount;

            NativeImage atlas = new NativeImage(width, atlasHeight, false);
            Path atlasPath;
            try {
                // Copy each frame into the atlas
                for (int i = 0; i < frameCount; i++) {
                    NativeImage frame = result.frames()[i];
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            MinecraftCompat.INSTANCE.setPixel(atlas, x, i * height + y, MinecraftCompat.INSTANCE.getPixel(frame, x, y));
                        }
                    }
                }

                // Save PNG atlas to cache
                Path cacheDir = cacheDirectory.resolve("animated_capes");
                Files.createDirectories(cacheDir);
                atlasPath = NetworkSecurity.resolveContained(cacheDir, hash, ".png");
                if (atlasPath == null || Files.isSymbolicLink(atlasPath)) return null;
                atlas.writeToFile(atlasPath);
            } finally {
                atlas.close();
            }

            // Composite vanilla elytra on cache atlas if elytra area is transparent
            compositeElytraOnAtlasIfNeeded(atlasPath, frameCount);

            // Save animation metadata to cache
            writeAnimationMetadataFile(hash, result.metadata());

            // Get friendly name
            String friendlyName = path.getFileName().toString();
            int dotIndex = friendlyName.lastIndexOf('.');
            if (dotIndex > 0) {
                friendlyName = friendlyName.substring(0, dotIndex);
            }

            // Get file size and modification time
            long fileSize = sourceBytes.length;
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
        if (!NetworkSecurity.isValidContentId(hash)) return null;
        AssetMetadata assetMeta = getMetadata(hash);
        if (assetMeta == null || !assetMeta.isAnimated()) {
            return null;
        }
        return readAnimationMetadataFile(hash);
    }

    @Nullable
    private AnimationMetadata readAnimationMetadataFile(String hash) {
        Path metadataPath = NetworkSecurity.resolveContained(cacheDirectory, hash, ".json");
        if (metadataPath == null || Files.isSymbolicLink(metadataPath) || !Files.exists(metadataPath)) return null;
        try {
            String json = BoundedFileReader.readUtf8(
                    metadataPath, com.quickskin.mod.networking.TextureTransferLimits.MAX_JSON_BYTES);
            return NetworkSecurity.parseAnimationMetadata(json);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void writeAnimationMetadataFile(String hash, AnimationMetadata metadata) throws IOException {
        String json = metadata.toJson();
        if (!NetworkSecurity.isValidAnimationMetadata(json)) throw new IOException("Invalid animation metadata");
        Path metadataPath = NetworkSecurity.resolveContained(cacheDirectory, hash, ".json");
        if (metadataPath == null || Files.isSymbolicLink(metadataPath)) throw new IOException("Unsafe metadata path");
        Files.writeString(metadataPath, json);
    }

    private void deleteAnimationMetadataFile(String hash) throws IOException {
        Path metadataPath = NetworkSecurity.resolveContained(cacheDirectory, hash, ".json");
        if (metadataPath != null && !Files.isSymbolicLink(metadataPath)) Files.deleteIfExists(metadataPath);
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

    /** Get all selectable skin entries, including optional CPM models. */
    public List<AssetMetadata> getAllSkins() {
        String playerOwnSkinHash = ClientConfig.getInstance().playerOwnSkinHash;
        SkinSortMode sortMode = ClientConfig.getInstance().getSkinSortMode();
        return metadataCache.values().stream()
                .filter(metadata -> metadata.isSkin() || metadata.isCpmModel())
                .sorted(getSortComparator(sortMode, playerOwnSkinHash))
                .toList();
    }

    /**
     * Get metadata by hash
     */
    public AssetMetadata getMetadata(String hash) {
        if (!NetworkSecurity.isValidContentId(hash)) return null;
        return metadataCache.get(hash);
    }

    /**
     * Get source file path by hash
     */
    public Path getSourcePath(String hash) {
        if (!NetworkSecurity.isValidContentId(hash)) return null;
        return hashToSourcePath.get(hash);
    }

    /**
     * Load texture data for a specific quality level
     * Returns raw PNG bytes
     */
    public byte[] loadTexture(String hash, TextureQuality quality) {
        if (!NetworkSecurity.isValidContentId(hash) || quality == null) return null;
        Path sourcePath = hashToSourcePath.get(hash);
        if (sourcePath == null || !Files.exists(sourcePath)) {
            return null;
        }

        try {
            byte[] sourceBytes = BoundedFileReader.readBytes(sourcePath, MAX_ASSET_BYTES);
            AssetMetadata metadata = getMetadata(hash);
            boolean isSkin = metadata != null && "skin".equals(metadata.type());
            boolean shouldRemoveTransparency = isSkin &&
                    com.quickskin.mod.config.ClientConfig.getInstance().shouldDisableSkinTransparency();

            // Canonical full-quality bytes need no decode/re-encode when presentation policy is off.
            if (quality == TextureQuality.FULL && !shouldRemoveTransparency
                    && hash.equals(HashUtil.computeAssetHash(
                            sourceBytes, metadata != null ? metadata.type() : null))) {
                return sourceBytes;
            }

            BufferedImage image = SafeImageReader.readPng(sourceBytes);
            if (image == null) {
                return null;
            }

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
                        yield sourceBytes;
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
     * Loads immutable imported bytes for network transfer. Server presentation policy must never
     * destructively alter the content-addressed source that other clients receive.
     */
    public byte @Nullable [] loadCanonicalTexture(String hash, String textureType) {
        if (!NetworkSecurity.isValidContentId(hash)
                || !NetworkSecurity.isValidTextureType(textureType)) return null;
        AssetMetadata metadata = getMetadata(hash);
        if (metadata == null || !textureType.equals(metadata.type())) return null;
        Path sourcePath = hashToSourcePath.get(hash);
        if (sourcePath == null) return null;
        try {
            byte[] sourceBytes = BoundedFileReader.readBytes(sourcePath, MAX_ASSET_BYTES);
            if ((!metadata.isAnimated() && !hash.equals(
                    HashUtil.computeAssetHash(sourceBytes, textureType)))
                    || NetworkSecurity.getTexturePixelCount(sourceBytes, textureType) < 1) return null;
            // One full bounded decode is the authoritative on-disk tamper/integrity check.
            SafeImageReader.readPng(sourceBytes);
            if ("cape".equals(textureType) && metadata.isAnimated()) {
                AnimationMetadata animation = getAnimationMetadata(hash);
                if (animation == null) return null;
                sourceBytes = com.quickskin.mod.common.util.PngAnimationIdentity
                        .attach(sourceBytes, animation.toJson());
            }
            return sourceBytes;
        } catch (IOException | RuntimeException error) {
            QuickSkin.LOGGER.warn("Unable to load canonical {} texture {}", textureType, hash, error);
            return null;
        }
    }

    private Path getCpmIconPath(String hash) {
        return cacheDirectory.resolve("cpm_icons").resolve(hash + ".png");
    }

    private byte[] loadCpmModelIcon(String hash) {
        Path iconPath = getCpmIconPath(hash);
        if (!Files.exists(iconPath)) {
            return null;
        }
        try {
            return BoundedFileReader.readBytes(iconPath, MAX_ASSET_BYTES);
        } catch (IOException ignored) {
            return null;
        }
    }

    /**
     * Delete local asset
     */
    public boolean deleteAsset(String hash) {
        if (!NetworkSecurity.isValidContentId(hash)) return false;
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
            if (metadata.isSkin()) {
                com.quickskin.mod.client.compat.CPMCompatIntegration.evictHttpTextureCache(hash);
            }
            if (metadata.isCpmModel()) {
                com.quickskin.mod.client.compat.CpmModelWorkflow.onModelDeleted(metadata);
            }
            metadataCache.remove(hash);
            hashToSourcePath.remove(hash);
            sourceImageCache.remove(hash);

            Map<TextureQuality, Identifier> registeredTextures = textureRegistry.remove(hash);
            if (registeredTextures != null) {
                for (Identifier location : registeredTextures.values()) {
                    try {
                        Minecraft.getInstance().getTextureManager().release(location);
                    } catch (RuntimeException ignored) {
                        QuickSkin.LOGGER.debug("Unable to release deleted local texture {}", location, ignored);
                    }
                }
            }

            if (metadata.isCpmModel()) {
                try {
                    Files.deleteIfExists(getCpmIconPath(hash));
                } catch (IOException ignored) {
                    QuickSkin.LOGGER.warn("Unable to delete CPM icon for {}", hash, ignored);
                }
            }

            // Also remove preferences for this skin
            if (skinPreferences != null) {
                skinPreferences.remove(hash);
                savePreferences();
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Rename a local asset file
     * @param hash The hash of the asset to rename
     * @param newFriendlyName The new friendly name (without extension)
     * @return RenameResult indicating success or failure reason
     */
    public RenameResult renameLocalAsset(String hash, String newFriendlyName) {
        if (!NetworkSecurity.isValidContentId(hash)) return RenameResult.NOT_FOUND;
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
            if (updatedMetadata.isCpmModel()
                    && hash.equals(ClientConfig.getInstance().activeCpmModelHash)) {
                com.quickskin.mod.client.compat.CpmModelWorkflow.activateModel(updatedMetadata);
            }

            return RenameResult.SUCCESS;

        } catch (IOException e) {
            return RenameResult.IO_ERROR;
        }
    }

    /**
     * Clear all caches and rediscover assets
     */
    public synchronized void reload() {
        discoverLocalAssets();
    }

    /**
     * Clear texture cache to force re-registration with new settings
     * Call this when transparency settings change
     */
    public void clearTextureCache() {
        // Unregister all textures from Minecraft's texture manager
        Minecraft mc = Minecraft.getInstance();
        for (Map<TextureQuality, Identifier> qualityMap : textureRegistry.values()) {
            for (Identifier location : qualityMap.values()) {
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
            Map<TextureQuality, Identifier> qualityMap = textureRegistry.get(hash);
            if (qualityMap != null) {
                for (Identifier location : qualityMap.values()) {
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
     * Get Identifier for a texture
     * Registers texture with Minecraft if not already registered
     */
    public synchronized Identifier getTextureLocation(String hash, TextureQuality quality) {
        if (!NetworkSecurity.isValidContentId(hash) || quality == null) return null;
        // Check if already registered
        Map<TextureQuality, Identifier> qualityMap = textureRegistry.get(hash);
        if (qualityMap != null && qualityMap.containsKey(quality)) {
            return qualityMap.get(quality);
        }

        // CPM files are not images; their separately cached embedded icon is.
        AssetMetadata metadata = getMetadata(hash);
        byte[] textureData = metadata != null && metadata.isCpmModel()
                ? loadCpmModelIcon(hash)
                : loadTexture(hash, quality);
        if (textureData == null) {
            return null;
        }

        NativeImage nativeImage = null;
        DynamicTexture dynamicTexture = null;
        Identifier location = null;
        boolean registered = false;
        boolean committed = false;
        try {
            // Load directly as NativeImage from PNG bytes (handles pixel format automatically)
            nativeImage = NativeImage.read(new ByteArrayInputStream(textureData));

            // For animated capes, only register the FIRST FRAME on GPU instead of the full atlas.
            // The animation system keeps the atlas in RAM and handles frame switching separately.
            // This prevents massive VRAM waste and fixes incorrect UV rendering when
            // the animation texture is used as a fallback.
            AssetMetadata meta = metadata;
            if (meta != null && meta.isAnimated() && meta.frameCount() > 1 && quality == TextureQuality.FULL) {
                int frameHeight = nativeImage.getHeight() / meta.frameCount();
                if (frameHeight > 0 && frameHeight < nativeImage.getHeight()) {
                    NativeImage firstFrame = new NativeImage(nativeImage.getWidth(), frameHeight, false);
                    boolean installed = false;
                    try {
                        for (int y = 0; y < frameHeight; y++) {
                            for (int x = 0; x < nativeImage.getWidth(); x++) {
                                MinecraftCompat.INSTANCE.setPixel(firstFrame, x, y, MinecraftCompat.INSTANCE.getPixel(nativeImage, x, y));
                            }
                        }
                        nativeImage.close();
                        nativeImage = firstFrame;
                        installed = true;
                    } finally {
                        if (!installed) firstFrame.close();
                    }
                }
            }

            // Create dynamic texture
            dynamicTexture = new DynamicTexture(() -> "quickskin_local_" + hash, nativeImage);

            // Register with texture manager
            location = Identifier.fromNamespaceAndPath(
                    QuickSkin.MOD_ID,
                    "local/" + hash + "_" + quality.name().toLowerCase(Locale.ROOT)
            );

            Minecraft.getInstance().getTextureManager().register(location, dynamicTexture);
            registered = true;

            // Cache transparency info for the first-person arm rendering mixin
            // DynamicTextures aren't accessible via resource manager, so we check here
            boolean hasAlpha = false;
            for (int y = 0; y < nativeImage.getHeight() && !hasAlpha; y += Math.max(1, nativeImage.getHeight() / 32)) {
                for (int x = 0; x < nativeImage.getWidth() && !hasAlpha; x += Math.max(1, nativeImage.getWidth() / 32)) {
                    int pixel = MinecraftCompat.INSTANCE.getPixel(nativeImage, x, y);
                    int alpha = (pixel >> 24) & 0xFF;
                    if (alpha < 255) hasAlpha = true;
                }
            }
            // Parse Ears features from the original unprocessed image (preserving alpha for Alfalfa data)
            if (metadata != null && "skin".equals(metadata.type())
                    && com.quickskin.mod.client.compat.EarsCompatIntegration.isAvailable()) {
                BufferedImage originalImage = getSourceImage(hash);
                if (originalImage != null) {
                    com.quickskin.mod.client.compat.EarsCompatIntegration.parseAndStoreFeatures(location, originalImage);
                }
            }

            // Cache in registry
            com.quickskin.mod.common.util.TextureAlphaDetector.cacheTransparencyResult(location, hasAlpha);
            qualityMap = textureRegistry.computeIfAbsent(hash, k -> new ConcurrentHashMap<>());
            qualityMap.put(quality, location);

            committed = true;
            return location;

        } catch (Exception e) {
            return null;
        } finally {
            if (!committed) {
                if (registered && location != null) {
                    try {
                        Minecraft.getInstance().getTextureManager().release(location);
                    } catch (RuntimeException ignored) {
                        QuickSkin.LOGGER.debug("Unable to release failed local texture {}", location, ignored);
                    }
                } else if (dynamicTexture != null) {
                    try {
                        dynamicTexture.close();
                    } catch (RuntimeException ignored) {
                        QuickSkin.LOGGER.debug("Unable to close failed local texture {}", hash, ignored);
                    }
                } else if (nativeImage != null) {
                    nativeImage.close();
                }
            }
        }
    }

    @Nullable
    public BufferedImage getSourceImage(String hash) {
        if (!NetworkSecurity.isValidContentId(hash)) return null;
        // F8: SoftReference cache — GC reclaims under memory pressure.
        SoftReference<BufferedImage> ref = sourceImageCache.get(hash);
        if (ref != null) {
            BufferedImage cached = ref.get();
            if (cached != null) {
                return cached;
            }
            sourceImageCache.remove(hash, ref);
        }
        Path sourcePath = getSourcePath(hash);
        if (sourcePath == null) {
            // Also check cache for animated capes converted from GIFs
            Path cachedAtlas = NetworkSecurity.resolveContained(
                    cacheDirectory.resolve("animated_capes"), hash, ".png");
            if (cachedAtlas != null && !Files.isSymbolicLink(cachedAtlas) && Files.exists(cachedAtlas)) {
                sourcePath = cachedAtlas;
            } else {
                return null;
            }
        }
        try {
            BufferedImage decoded = SafeImageReader.readPng(sourcePath);
            if (decoded != null) {
                sourceImageCache.put(hash, new SoftReference<>(decoded));
            }
            return decoded;
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
                MinecraftCompat.INSTANCE.setPixel(nativeImage, x, y, abgr);
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
        if (!NetworkSecurity.isValidContentId(hash)) return "auto";
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
        if (!NetworkSecurity.isValidContentId(hash)) return;
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

    /**
     * Check if the elytra area of a cape atlas is transparent, and if so,
     * composite the vanilla elytra texture onto the cached atlas.
     * This keeps the source GIF untouched while ensuring elytra renders correctly.
     */
    private void compositeElytraOnAtlasIfNeeded(Path atlasPath, int frameCount) {
        try {
            BufferedImage atlas = SafeImageReader.readPng(atlasPath);
            if (atlas == null) return;

            int capeW = atlas.getWidth();
            int frameH = (frameCount > 0) ? atlas.getHeight() / frameCount : atlas.getHeight();

            double scale = capeW / 64.0;
            int elytraX = (int) (22 * scale);
            int elytraW = (int) (32 * scale);
            int elytraH = (int) (16 * scale);
            boolean allTransparent = true;
            int samplePoints = 5;
            for (int i = 0; i < samplePoints && allTransparent; i++) {
                for (int j = 0; j < samplePoints && allTransparent; j++) {
                    int x = Math.min(elytraX + (i * elytraW / (samplePoints - 1)), capeW - 1);
                    int y = Math.min(j * elytraH / (samplePoints - 1), frameH - 1);
                    int alpha = (atlas.getRGB(x, y) >> 24) & 0xFF;
                    if (alpha > 10) allTransparent = false;
                }
            }

            if (!allTransparent) return;

            var resourceOpt = Minecraft.getInstance().getResourceManager()
                    .getResource(Identifier.fromNamespaceAndPath("minecraft", "textures/entity/elytra.png"));
            if (resourceOpt.isEmpty()) return;
            BufferedImage elytra;
            try (var stream = resourceOpt.get().open()) {
                byte[] encoded = com.quickskin.mod.common.util.BoundedFileReader.readBytes(
                        stream,
                        (int) com.quickskin.mod.common.util.SafeImageReader.MAX_ENCODED_BYTES);
                elytra = com.quickskin.mod.common.util.SafeImageReader.readPng(encoded);
            }
            if (elytra == null) return;

            BufferedImage composited = new BufferedImage(capeW, atlas.getHeight(), BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = composited.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            for (int i = 0; i < frameCount; i++) {
                int yOff = i * frameH;
                g.drawImage(elytra, 0, yOff, capeW, yOff + frameH,
                        0, 0, elytra.getWidth(), elytra.getHeight(), null);
                g.drawImage(atlas.getSubimage(0, yOff, capeW, frameH), 0, yOff, null);
            }
            g.dispose();

            ImageIO.write(composited, "PNG", atlasPath.toFile());
        } catch (Exception e) {
            // Non-critical — elytra just won't have the vanilla fallback
        }
    }
}
