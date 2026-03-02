package com.quickskin.mod.networking;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.common.data.AnimationMetadata;
import com.quickskin.mod.networking.payloads.*;
import dev.architectury.networking.NetworkManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

import java.util.UUID;

/**
 * Client-side service for syncing appearance changes to the server
 * Uses Architectury 13.x CustomPacketPayload system for MC 1.21.1
 */
@Environment(EnvType.CLIENT)
public class NetworkSyncService {

    private static NetworkSyncService instance;

    // Maximum chunk size for texture uploads (30KB - safe for network transmission)
    // This matches the old mod's chunk size for better compatibility
    private static final int MAX_CHUNK_SIZE = 30 * 1024;

    private NetworkSyncService() {
    }

    public static NetworkSyncService getInstance() {
        if (instance == null) {
            instance = new NetworkSyncService();
        }
        return instance;
    }

    /**
     * Sync appearance change to server
     * @param playerId Player UUID
     * @param skinId Skin ID (e.g., "local_skin:hash" or "username")
     * @param capeId Cape ID (e.g., "local_cape:hash" or "known:id")
     * @param model Model type ("classic", "slim", or "auto")
     */
    public void syncAppearance(UUID playerId, String skinId, String capeId, String model) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) {
            return;
        }

        // Check if server supports QuickSkin packets
        if (!NetworkManager.canServerReceive(UpdateAppearancePayload.TYPE)) {
            return;
        }

        // Upload skin texture if it's a local skin
        if (skinId != null && skinId.startsWith("local_skin:")) {
            uploadLocalTexture(skinId, "skin");
        }

        // Upload cape texture if it's a local cape
        if (capeId != null && capeId.startsWith("local_cape:")) {
            uploadLocalTexture(capeId, "cape");

            // Upload animation metadata if the cape is animated
            String hash = capeId.substring("local_cape:".length());
            uploadAnimationMetadata(hash);
        }

        // Send appearance update packet
        UpdateAppearancePayload payload = new UpdateAppearancePayload(
                playerId,
                skinId != null ? skinId : "",
                capeId != null ? capeId : "",
                model != null ? model : "classic"
        );

        NetworkManager.sendToServer(payload);
    }

    /**
     * Upload a local texture (skin or cape) to the server in chunks
     * @param textureId Full texture ID (e.g., "local_skin:hash" or "local_cape:hash")
     * @param textureType Type ("skin" or "cape")
     */
    private void uploadLocalTexture(String textureId, String textureType) {
        // Check if server supports texture chunks
        if (!NetworkManager.canServerReceive(TextureChunkPayload.TYPE)) {
            return;
        }

        // Extract hash from texture ID
        String prefix = textureType.equals("skin") ? "local_skin:" : "local_cape:";
        if (!textureId.startsWith(prefix)) {
            return;
        }

        String hash = textureId.substring(prefix.length());

        // Get texture bytes from LocalAssetManager (load at FULL quality)
        byte[] textureData = LocalAssetManager.getInstance().loadTexture(hash, com.quickskin.mod.common.data.TextureQuality.FULL);
        if (textureData == null) {
            return;
        }

        // Split into chunks if necessary
        int totalChunks = (int) Math.ceil((double) textureData.length / MAX_CHUNK_SIZE);

        for (int i = 0; i < totalChunks; i++) {
            int offset = i * MAX_CHUNK_SIZE;
            int length = Math.min(MAX_CHUNK_SIZE, textureData.length - offset);
            byte[] chunk = new byte[length];
            System.arraycopy(textureData, offset, chunk, 0, length);

            // Create chunk payload
            TextureChunkPayload payload = new TextureChunkPayload(
                    hash,
                    textureType,
                    i, // chunk index
                    totalChunks, // total chunks
                    chunk
            );

            NetworkManager.sendToServer(payload);
        }
    }

    /**
     * Upload animation metadata for an animated cape
     * @param hash Texture hash
     */
    private void uploadAnimationMetadata(String hash) {
        // Check if server supports animation metadata
        if (!NetworkManager.canServerReceive(UploadAnimationMetadataPayload.TYPE)) {
            return;
        }

        AnimationMetadata metadata = LocalAssetManager.getInstance().getAnimationMetadata(hash);
        if (metadata == null) {
            // Not animated or no metadata
            return;
        }

        // Serialize metadata to JSON
        String metadataJson = serializeMetadata(metadata);

        UploadAnimationMetadataPayload payload = new UploadAnimationMetadataPayload(hash, metadataJson);
        NetworkManager.sendToServer(payload);

    }

    /**
     * Serialize AnimationMetadata to JSON
     */
    private String serializeMetadata(AnimationMetadata metadata) {
        // Use the built-in toJson() method
        return metadata.toJson();
    }

    /**
     * Clear appearance (reset to default)
     */
    public void clearAppearance(UUID playerId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) {
            return;
        }

        // Check if server supports QuickSkin packets
        if (!NetworkManager.canServerReceive(UpdateAppearancePayload.TYPE)) {
            return;
        }

        UpdateAppearancePayload payload = new UpdateAppearancePayload(playerId, "", "", "classic");
        NetworkManager.sendToServer(payload);
    }

    /**
     * Request a texture from the server (fallback mechanism for missed broadcasts)
     * @param playerId Player UUID making the request
     * @param textureType Type of texture ("skin" or "cape")
     * @param hash Texture hash to request
     */
    public void requestTexture(UUID playerId, String textureType, String hash) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) {
            return;
        }

        // Check if server supports QuickSkin packets
        if (!NetworkManager.canServerReceive(RequestTexturePayload.TYPE)) {
            return;
        }

        RequestTexturePayload payload = new RequestTexturePayload(playerId, textureType, hash);
        NetworkManager.sendToServer(payload);
    }
}