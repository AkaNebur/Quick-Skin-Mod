package com.quickskin.mod.server.storage;

import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Assembles chunked texture uploads on the server
 * Handles multi-part texture uploads from clients
 */
public class TextureChunkAssembler {
    private static TextureChunkAssembler instance;

    // Map: hash -> ChunkAssembly
    private final Map<String, ChunkAssembly> assemblies = new ConcurrentHashMap<>();

    private TextureChunkAssembler() {}

    public static TextureChunkAssembler getInstance() {
        if (instance == null) {
            instance = new TextureChunkAssembler();
        }
        return instance;
    }

    /**
     * Add a chunk to the assembly
     * @param hash Texture hash
     * @param chunkIndex Current chunk index
     * @param totalChunks Total number of chunks
     * @param chunkData The chunk data
     * @return Complete texture data if all chunks received, null otherwise
     */
    public byte @Nullable [] addChunk(String hash, int chunkIndex, int totalChunks, byte[] chunkData) {
        // Get or create assembly
        ChunkAssembly assembly = assemblies.computeIfAbsent(hash, k -> new ChunkAssembly(totalChunks));

        // Add chunk
        assembly.addChunk(chunkIndex, chunkData);

        // Check if complete
        if (assembly.isComplete()) {
            byte[] completeData = assembly.assemble();
            assemblies.remove(hash); // Clean up
            return completeData;
        }

        return null;
    }

    /**
     * Internal class to track chunk assembly progress
     */
    private static class ChunkAssembly {
        private final byte[][] chunks;
        private int receivedChunks = 0;

        ChunkAssembly(int totalChunks) {
            this.chunks = new byte[totalChunks][];
        }

        void addChunk(int index, byte[] data) {
            if (index >= 0 && index < chunks.length && chunks[index] == null) {
                chunks[index] = data;
                receivedChunks++;
            }
        }

        boolean isComplete() {
            return receivedChunks == chunks.length;
        }

        byte[] assemble() {
            // Calculate total size
            int totalSize = 0;
            for (byte[] chunk : chunks) {
                totalSize += chunk.length;
            }

            // Assemble chunks
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
