package com.quickskin.mod.server.storage;

import com.quickskin.mod.QuickSkin;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side cache for animation metadata
 * Stores animation data for animated skins and capes
 */
public class ServerAnimationCache {
    private static ServerAnimationCache instance;

    private final Map<String, String> metadataCache = new ConcurrentHashMap<>();
    private Path storageDirectory;

    private ServerAnimationCache() {}

    public static ServerAnimationCache getInstance() {
        if (instance == null) {
            instance = new ServerAnimationCache();
        }
        return instance;
    }

    /**
     * Initialize the animation cache with server instance
     */
    public void init(MinecraftServer server) {
        // Get server world directory
        Path worldPath = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        storageDirectory = worldPath.resolve("quickskin").resolve("animations");

        try {
            Files.createDirectories(storageDirectory);
        } catch (IOException e) {
            QuickSkin.LOGGER.error("Failed to create animation storage directory", e);
        }

        // Load existing metadata from disk
        loadCachedMetadata();
    }

    /**
     * Store animation metadata
     * @param hash The texture hash
     * @param metadataJson The animation metadata in JSON format
     */
    public void storeMetadata(String hash, String metadataJson) {
        metadataCache.put(hash, metadataJson);
        saveMetadataToDisk(hash, metadataJson);
    }

    /**
     * Retrieve animation metadata
     * @param hash The texture hash
     * @return The metadata JSON, or null if not found
     */
    @Nullable
    public String getMetadata(String hash) {
        return metadataCache.get(hash);
    }

    /**
     * Clear all cached metadata from memory
     */
    public void clear() {
        metadataCache.clear();
    }

    /**
     * Save a metadata entry to disk
     */
    private void saveMetadataToDisk(String hash, String metadataJson) {
        if (storageDirectory == null) {
            return;
        }

        try {
            Path file = storageDirectory.resolve(hash + ".json");
            Files.writeString(file, metadataJson);
        } catch (IOException e) {
            QuickSkin.LOGGER.error("Failed to save animation metadata: {}", hash, e);
        }
    }

    /**
     * Load all metadata from disk into memory cache
     */
    private void loadCachedMetadata() {
        if (storageDirectory == null || !Files.exists(storageDirectory)) {
            return;
        }

        try {
            Files.list(storageDirectory)
                .filter(path -> path.toString().endsWith(".json"))
                .forEach(path -> {
                    try {
                        String hash = path.getFileName().toString().replace(".json", "");
                        String metadata = Files.readString(path);
                        metadataCache.put(hash, metadata);
                    } catch (IOException e) {
                        QuickSkin.LOGGER.warn("Failed to load animation metadata: {}", path, e);
                    }
                });
        } catch (IOException e) {
            QuickSkin.LOGGER.error("Failed to load cached animation metadata", e);
        }
    }
}
