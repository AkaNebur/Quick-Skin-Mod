package com.quickskin.mod.networking.packets;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * Helper class for creating and reading packet data
 */
public class PacketHelper {

    /**
     * Creates a packet for uploading skin/cape texture
     * Format: UUID (player) + String (textureType) + byte[] (imageData)
     */
    public static FriendlyByteBuf createUploadTexturePacket(UUID playerId, String textureType, byte[] imageData) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeUUID(playerId);
        buf.writeUtf(textureType); // "skin" or "cape"
        buf.writeByteArray(imageData);
        return buf;
    }

    /**
     * Creates a packet for updating player appearance
     * Format: UUID (player) + String (skinId) + String (capeId) + String (model)
     */
    public static FriendlyByteBuf createUpdateAppearancePacket(UUID playerId, String skinId, String capeId, String model) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeUUID(playerId);
        buf.writeUtf(skinId != null ? skinId : "");
        buf.writeUtf(capeId != null ? capeId : "");
        buf.writeUtf(model != null ? model : "classic");
        return buf;
    }

    /**
     * Creates a packet for syncing appearance to clients
     * Format: UUID (player) + String (skinId) + String (capeId) + String (model)
     */
    public static FriendlyByteBuf createSyncAppearancePacket(UUID playerId, String skinId, String capeId, String model) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeUUID(playerId);
        buf.writeUtf(skinId != null ? skinId : "");
        buf.writeUtf(capeId != null ? capeId : "");
        buf.writeUtf(model != null ? model : "classic");
        return buf;
    }

    /**
     * Creates a packet for requesting a texture from server
     * Format: UUID (player) + String (textureType) + String (hash)
     */
    public static FriendlyByteBuf createRequestTexturePacket(UUID playerId, String textureType, String hash) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeUUID(playerId);
        buf.writeUtf(textureType); // "skin" or "cape"
        buf.writeUtf(hash);
        return buf;
    }

    /**
     * Creates a packet for sending texture data to client
     * Format: String (textureType) + String (hash) + byte[] (imageData)
     */
    public static FriendlyByteBuf createSendTexturePacket(String textureType, String hash, byte[] imageData) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeUtf(textureType);
        buf.writeUtf(hash);
        buf.writeByteArray(imageData);
        return buf;
    }

    /**
     * Creates a packet for syncing server config to client
     * Format: boolean (allowSkins) + boolean (allowCapes) + boolean (allowTransparent)
     */
    public static FriendlyByteBuf createSyncServerConfigPacket(boolean allowSkins, boolean allowCapes, boolean allowTransparent) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeBoolean(allowSkins);
        buf.writeBoolean(allowCapes);
        buf.writeBoolean(allowTransparent);
        return buf;
    }

    /**
     * Reads a UUID from buffer
     */
    public static UUID readPlayerId(FriendlyByteBuf buf) {
        return buf.readUUID();
    }

    /**
     * Reads a string from buffer (null-safe)
     */
    public static String readString(FriendlyByteBuf buf) {
        String str = buf.readUtf();
        return str.isEmpty() ? null : str;
    }

    /**
     * Reads a byte array from buffer
     */
    public static byte[] readByteArray(FriendlyByteBuf buf) {
        return buf.readByteArray();
    }

    /**
     * Reads a boolean from buffer
     */
    public static boolean readBoolean(FriendlyByteBuf buf) {
        return buf.readBoolean();
    }
}
