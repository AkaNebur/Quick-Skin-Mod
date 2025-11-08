package com.quickskin.mod.server.storage;

import com.quickskin.mod.QuickSkin;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side cache for player textures (skins and capes)
 * Stores textures in memory and persists to disk
 */
public class ServerTextureCache {
    private static ServerTextureCache instance;

    private final Map<String, byte[]> textureCache = new ConcurrentHashMap<>();
    private Path storageDirectory;

    private ServerTextureCache() {}

    public static ServerTextureCache getInstance() {
        if (instance == null) {
            instance = new ServerTextureCache();
        }
        return instance;
    }

    /**
     * Initialize the texture cache with server instance
     */
    public void init(MinecraftServer server) {
        // Get server world directory
        Path worldPath = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        storageDirectory = worldPath.resolve("quickskin").resolve("textures");

        try {
            Files.createDirectories(storageDirectory);
            QuickSkin.LOGGER.info("ServerTextureCache initialized at: {}", storageDirectory);
        } catch (IOException e) {
            QuickSkin.LOGGER.error("Failed to create texture storage directory", e);
        }

        // Load existing textures from disk
        loadCachedTextures();
    }

    /**
     * Store a texture in cache and persist to disk
     * @param hash The texture hash (unique identifier)
     * @param textureData The texture image data
     */
    public void storeTexture(String hash, byte[] textureData) {
        // Store in memory cache
        textureCache.put(hash, textureData);

        // Persist to disk asynchronously
        saveTextureToDisk(hash, textureData);

        QuickSkin.LOGGER.debug("Stored texture: {} ({} bytes)", hash, textureData.length);
    }

    /**
     * Store a player's texture with UUID and type
     * @param playerId The player's UUID
     * @param textureType "skin" or "cape"
     * @param textureData The texture image data
     */
    public void storeTexture(UUID playerId, String textureType, byte[] textureData) {
        String hash = generateHash(playerId, textureType);
        storeTexture(hash, textureData);
    }

    /**
     * Retrieve a texture from cache
     * @param hash The texture hash
     * @return The texture data, or null if not found
     */
    @Nullable
    public byte[] getTexture(String hash) {
        return textureCache.get(hash);
    }

    /**
     * Save all cached textures to disk
     */
    public void saveAll() {
        QuickSkin.LOGGER.info("Saving {} textures to disk...", textureCache.size());
        int saved = 0;

        for (Map.Entry<String, byte[]> entry : textureCache.entrySet()) {
            if (saveTextureToDisk(entry.getKey(), entry.getValue())) {
                saved++;
            }
        }

        QuickSkin.LOGGER.info("Saved {} textures", saved);
    }

    /**
     * Clear all cached textures from memory
     */
    public void clear() {
        textureCache.clear();
        QuickSkin.LOGGER.info("Cleared texture cache");
    }

    /**
     * Generate a hash for a player's texture
     */
    private String generateHash(UUID playerId, String textureType) {
        return playerId.toString() + "_" + textureType;
    }

    /**
     * Save a texture to disk
     */
    private boolean saveTextureToDisk(String hash, byte[] data) {
        if (storageDirectory == null) {
            return false;
        }

        try {
            Path file = storageDirectory.resolve(hash + ".png");
            Files.write(file, data);
            return true;
        } catch (IOException e) {
            QuickSkin.LOGGER.error("Failed to save texture: {}", hash, e);
            return false;
        }
    }

    /**
     * Load all textures from disk into memory cache
     */
    private void loadCachedTextures() {
        if (storageDirectory == null || !Files.exists(storageDirectory)) {
            return;
        }

        try {
            Files.list(storageDirectory)
                .filter(path -> path.toString().endsWith(".png"))
                .forEach(path -> {
                    try {
                        String hash = path.getFileName().toString().replace(".png", "");
                        byte[] data = Files.readAllBytes(path);
                        textureCache.put(hash, data);
                    } catch (IOException e) {
                        QuickSkin.LOGGER.warn("Failed to load texture: {}", path, e);
                    }
                });

            QuickSkin.LOGGER.info("Loaded {} textures from disk", textureCache.size());
        } catch (IOException e) {
            QuickSkin.LOGGER.error("Failed to load cached textures", e);
        }
    }
}
