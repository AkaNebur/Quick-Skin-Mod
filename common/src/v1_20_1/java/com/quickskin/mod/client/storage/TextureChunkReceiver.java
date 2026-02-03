package com.quickskin.mod.client.storage;

import com.quickskin.mod.QuickSkin;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side chunk receiver for assembling large textures sent from the server
 * This handles multi-part texture transfers that exceed single packet size limits
 */
@Environment(EnvType.CLIENT)
public class TextureChunkReceiver {
    private static TextureChunkReceiver instance;

    private final Map<String, ChunkAssembly> incompleteTextures = new ConcurrentHashMap<>();

    private TextureChunkReceiver() {
    }

    public static TextureChunkReceiver getInstance() {
        if (instance == null) {
            instance = new TextureChunkReceiver();
        }
        return instance;
    }

    /**
     * Receive a chunk of texture data from the server
     * @param hash Texture hash identifier
     * @param chunkIndex Index of this chunk (0-based)
     * @param totalChunks Total number of chunks expected
     * @param chunkData Chunk data bytes
     */
    public void receiveChunk(String hash, int chunkIndex, int totalChunks, byte[] chunkData) {
        ChunkAssembly assembly = incompleteTextures.computeIfAbsent(hash,
            k -> new ChunkAssembly(totalChunks));

        if (assembly.addChunk(chunkIndex, chunkData)) {
            // All chunks received, assemble the complete texture
            byte[] completeData = assembly.assembleTexture();
            if (completeData != null) {
                QuickSkin.LOGGER.debug("Assembled complete texture from {} chunks: {} ({} bytes)",
                    totalChunks, hash, completeData.length);

                // Store in network texture cache (must be on main thread)
                // Note: textureType is null because S2C chunking isn't currently used
                Minecraft.getInstance().execute(() -> {
                    NetworkTextureCache.getInstance().storeTexture(hash, null, completeData);
                    QuickSkin.LOGGER.debug("Stored reassembled texture in network cache: {}", hash);
                });
            }
            incompleteTextures.remove(hash);
        }
    }

    /**
     * Clear all incomplete texture assemblies (e.g., when disconnecting)
     */
    public void clear() {
        incompleteTextures.clear();
        QuickSkin.LOGGER.debug("Cleared all incomplete texture assemblies");
    }

    /**
     * Internal class for assembling chunks into a complete texture
     */
    private static class ChunkAssembly {
        private final byte[][] chunks;
        private final boolean[] received;
        private int receivedCount = 0;

        public ChunkAssembly(int totalChunks) {
            this.chunks = new byte[totalChunks][];
            this.received = new boolean[totalChunks];
        }

        /**
         * Add a chunk to the assembly
         * @return true if all chunks have been received
         */
        public synchronized boolean addChunk(int index, byte[] data) {
            // Validate index
            if (index < 0 || index >= chunks.length) {
                QuickSkin.LOGGER.warn("Invalid chunk index: {} (expected 0-{})", index, chunks.length - 1);
                return false;
            }

            // Ignore duplicate chunks
            if (received[index]) {
                QuickSkin.LOGGER.debug("Ignoring duplicate chunk: {}", index);
                return false;
            }

            chunks[index] = data;
            received[index] = true;
            receivedCount++;

            QuickSkin.LOGGER.debug("Received chunk {}/{}", receivedCount, chunks.length);

            return receivedCount == chunks.length;
        }

        /**
         * Assemble all chunks into a single byte array
         * @return Complete texture data, or null if not all chunks received
         */
        public byte @Nullable [] assembleTexture() {
            if (receivedCount != chunks.length) {
                QuickSkin.LOGGER.warn("Cannot assemble texture: only {}/{} chunks received",
                    receivedCount, chunks.length);
                return null;
            }

            // Calculate total size
            int totalSize = 0;
            for (byte[] chunk : chunks) {
                if (chunk == null) {
                    QuickSkin.LOGGER.error("Null chunk detected during assembly");
                    return null;
                }
                totalSize += chunk.length;
            }

            // Concatenate all chunks
            byte[] result = new byte[totalSize];
            int offset = 0;
            for (byte[] chunk : chunks) {
                System.arraycopy(chunk, 0, result, offset, chunk.length);
                offset += chunk.length;
            }

            return result;
        }
    }
}
