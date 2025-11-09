package com.quickskin.mod.client.storage;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.common.data.AnimationMetadata;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side cache for animation metadata received from server
 * Stores animation data in memory for playback
 */
@Environment(EnvType.CLIENT)
public class ClientAnimationMetadataCache {
    private static ClientAnimationMetadataCache instance;

    private final Map<String, AnimationMetadata> metadataCache = new ConcurrentHashMap<>();

    private ClientAnimationMetadataCache() {}

    public static ClientAnimationMetadataCache getInstance() {
        if (instance == null) {
            instance = new ClientAnimationMetadataCache();
        }
        return instance;
    }

    /**
     * Store animation metadata for a texture
     * @param hash The texture hash
     * @param metadata The animation metadata
     */
    public void storeMetadata(String hash, AnimationMetadata metadata) {
        if (hash == null || metadata == null) {
            return;
        }

        metadataCache.put(hash, metadata);
        QuickSkin.LOGGER.debug("Cached animation metadata for: {} ({} frames)",
                hash, metadata.frameCount());
    }

    /**
     * Get animation metadata for a texture
     * @param hash The texture hash
     * @return The metadata, or null if not found
     */
    @Nullable
    public AnimationMetadata getMetadata(String hash) {
        return metadataCache.get(hash);
    }

    /**
     * Clear all cached metadata
     */
    public void clear() {
        metadataCache.clear();
        QuickSkin.LOGGER.debug("Cleared client animation metadata cache");
    }

    /**
     * Remove metadata for a specific texture
     * @param hash The texture hash
     */
    public void remove(String hash) {
        metadataCache.remove(hash);
    }
}
