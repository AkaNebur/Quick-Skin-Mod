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

import java.util.UUID;

/**
 * Payload for requesting a texture from server
 * Format: UUID (player) + String (textureType) + String (hash)
 */
public record RequestTexturePayload(UUID playerId, String textureType, String hash) implements CustomPacketPayload {

    public static final Type<RequestTexturePayload> TYPE = new Type<>(
        //? if <1.21.11 {
        ResourceLocation.fromNamespaceAndPath(QuickSkin.MOD_ID, "request_texture")
        //?} else {
        Identifier.fromNamespaceAndPath(QuickSkin.MOD_ID, "request_texture")
        //?}
    );

    public static final StreamCodec<ByteBuf, RequestTexturePayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            PayloadCodecs.writeUUID(buf, payload.playerId);
            PayloadCodecs.writeString(buf, payload.textureType);
            PayloadCodecs.writeString(buf, payload.hash);
        },
        buf -> new RequestTexturePayload(
            PayloadCodecs.readUUID(buf),
            PayloadCodecs.readString(buf),
            PayloadCodecs.readString(buf)
        )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
