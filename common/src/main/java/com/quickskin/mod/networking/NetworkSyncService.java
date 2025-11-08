package com.quickskin.mod.networking;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.common.data.AnimationMetadata;
import com.quickskin.mod.networking.packets.PacketHelper;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * Client-side service for syncing appearance changes to the server
 * Similar to the old SkinSwapper class
 */
@Environment(EnvType.CLIENT)
public class NetworkSyncService {

    private static NetworkSyncService instance;

    // Maximum chunk size for texture uploads (2MB)
    private static final int MAX_CHUNK_SIZE = 2 * 1024 * 1024;

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
            QuickSkin.LOGGER.warn("Cannot sync appearance to server (not connected)");
            return;
        }

        QuickSkin.LOGGER.info("Syncing appearance to server: skin={}, cape={}, model={}", skinId, capeId, model);

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
        FriendlyByteBuf buf = PacketHelper.createUpdateAppearancePacket(
            playerId,
            skinId != null ? skinId : "",
            capeId != null ? capeId : "",
            model != null ? model : "classic"
        );

        NetworkManager.sendToServer(ModNetworking.UPDATE_APPEARANCE, buf);
        QuickSkin.LOGGER.debug("Sent UPDATE_APPEARANCE packet to server");
    }

    /**
     * Upload a local texture (skin or cape) to the server in chunks
     * @param textureId Full texture ID (e.g., "local_skin:hash" or "local_cape:hash")
     * @param textureType Type ("skin" or "cape")
     */
    private void uploadLocalTexture(String textureId, String textureType) {
        // Extract hash from texture ID
        String prefix = textureType.equals("skin") ? "local_skin:" : "local_cape:";
        if (!textureId.startsWith(prefix)) {
            QuickSkin.LOGGER.warn("Invalid texture ID format: {}", textureId);
            return;
        }

        String hash = textureId.substring(prefix.length());

        // Get texture bytes from LocalAssetManager (load at FULL quality)
        byte[] textureData = LocalAssetManager.getInstance().loadTexture(hash, com.quickskin.mod.common.data.TextureQuality.FULL);
        if (textureData == null) {
            QuickSkin.LOGGER.warn("Could not find texture data for hash: {}", hash);
            return;
        }

        QuickSkin.LOGGER.info("Uploading {} texture to server: {} ({} bytes)", textureType, hash, textureData.length);

        // Split into chunks if necessary
        int totalChunks = (int) Math.ceil((double) textureData.length / MAX_CHUNK_SIZE);

        for (int i = 0; i < totalChunks; i++) {
            int offset = i * MAX_CHUNK_SIZE;
            int length = Math.min(MAX_CHUNK_SIZE, textureData.length - offset);
            byte[] chunk = new byte[length];
            System.arraycopy(textureData, offset, chunk, 0, length);

            // Create chunk packet
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            buf.writeUtf(hash);
            buf.writeUtf(textureType);
            buf.writeInt(i); // chunk index
            buf.writeInt(totalChunks); // total chunks
            buf.writeByteArray(chunk);

            NetworkManager.sendToServer(ModNetworking.TEXTURE_CHUNK, buf);
            QuickSkin.LOGGER.debug("Sent texture chunk {}/{} for {}", i + 1, totalChunks, hash);
        }
    }

    /**
     * Upload animation metadata for an animated cape
     * @param hash Texture hash
     */
    private void uploadAnimationMetadata(String hash) {
        AnimationMetadata metadata = LocalAssetManager.getInstance().getAnimationMetadata(hash);
        if (metadata == null) {
            // Not animated or no metadata
            return;
        }

        QuickSkin.LOGGER.info("Uploading animation metadata for: {}", hash);

        // Serialize metadata to JSON
        String metadataJson = serializeMetadata(metadata);

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeUtf(hash);
        buf.writeUtf(metadataJson);

        NetworkManager.sendToServer(ModNetworking.UPLOAD_ANIMATION_METADATA, buf);
        QuickSkin.LOGGER.debug("Sent animation metadata for {}", hash);
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
            QuickSkin.LOGGER.warn("Cannot clear appearance on server (not connected)");
            return;
        }

        QuickSkin.LOGGER.info("Clearing appearance on server");

        FriendlyByteBuf buf = PacketHelper.createUpdateAppearancePacket(playerId, "", "", "classic");
        NetworkManager.sendToServer(ModNetworking.UPDATE_APPEARANCE, buf);
    }
}
