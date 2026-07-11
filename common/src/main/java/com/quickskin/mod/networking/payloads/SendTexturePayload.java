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
 * Payload for sending texture data to client (S2C)
 * Format: String (textureType) + String (hash) + byte[] (imageData)
 */
public record SendTexturePayload(String textureType, String hash, byte[] imageData) implements CustomPacketPayload {

    public static final Type<SendTexturePayload> TYPE = new Type<>(
        //? if <1.21.11 {
        ResourceLocation.fromNamespaceAndPath(QuickSkin.MOD_ID, "send_texture")
        //?} else {
        Identifier.fromNamespaceAndPath(QuickSkin.MOD_ID, "send_texture")
        //?}
    );

    public static final StreamCodec<ByteBuf, SendTexturePayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            PayloadCodecs.writeString(buf, payload.textureType);
            PayloadCodecs.writeString(buf, payload.hash);
            buf.writeInt(payload.imageData.length);
            buf.writeBytes(payload.imageData);
        },
        buf -> {
            String textureType = PayloadCodecs.readString(buf);
            String hash = PayloadCodecs.readString(buf);
            int length = buf.readInt();
            byte[] imageData = new byte[length];
            buf.readBytes(imageData);
            return new SendTexturePayload(textureType, hash, imageData);
        }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
