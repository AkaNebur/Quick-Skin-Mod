package com.quickskin.mod.networking.payloads;

import com.quickskin.mod.QuickSkin;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}

/**
 * Payload for sending texture chunk to client (S2C)
 * Format: String (hash) + String (textureType) + int (chunkIndex) + int (totalChunks) + byte[] (chunkData)
 */
public record SendTextureChunkPayload(String hash, String textureType, int chunkIndex, int totalChunks, byte[] chunkData) implements CustomPacketPayload {

    public static final Type<SendTextureChunkPayload> TYPE = new Type<>(
        //? if <1.21.11 {
        ResourceLocation.fromNamespaceAndPath(QuickSkin.MOD_ID, "send_texture_chunk")
        //?} else {
        Identifier.fromNamespaceAndPath(QuickSkin.MOD_ID, "send_texture_chunk")
        //?}
    );

    public static final StreamCodec<ByteBuf, SendTextureChunkPayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            PayloadCodecs.writeString(buf, payload.hash);
            PayloadCodecs.writeString(buf, payload.textureType);
            buf.writeInt(payload.chunkIndex);
            buf.writeInt(payload.totalChunks);
            buf.writeInt(payload.chunkData.length);
            buf.writeBytes(payload.chunkData);
        },
        buf -> {
            String hash = PayloadCodecs.readString(buf);
            String textureType = PayloadCodecs.readString(buf);
            int chunkIndex = buf.readInt();
            int totalChunks = buf.readInt();
            int length = buf.readInt();
            byte[] chunkData = new byte[length];
            buf.readBytes(chunkData);
            return new SendTextureChunkPayload(hash, textureType, chunkIndex, totalChunks, chunkData);
        }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
