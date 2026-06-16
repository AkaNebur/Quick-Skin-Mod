package com.quickskin.mod.networking.payloads;

import com.quickskin.mod.QuickSkin;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * Payload for uploading skin/cape texture
 * Format: UUID (player) + String (textureType) + byte[] (imageData)
 */
public record UploadTexturePayload(UUID playerId, String textureType, byte[] imageData) implements CustomPacketPayload {

    public static final Type<UploadTexturePayload> TYPE = new Type<>(
        Identifier.fromNamespaceAndPath(QuickSkin.MOD_ID, "upload_texture")
    );

    public static final StreamCodec<ByteBuf, UploadTexturePayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            PayloadCodecs.writeUUID(buf, payload.playerId);
            PayloadCodecs.writeString(buf, payload.textureType);
            buf.writeInt(payload.imageData.length);
            buf.writeBytes(payload.imageData);
        },
        buf -> {
            UUID playerId = PayloadCodecs.readUUID(buf);
            String textureType = PayloadCodecs.readString(buf);
            int length = buf.readInt();
            byte[] imageData = new byte[length];
            buf.readBytes(imageData);
            return new UploadTexturePayload(playerId, textureType, imageData);
        }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
