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
 * Payload for animation metadata upload from client
 * Format: String (hash) + String (metadataJson)
 */
public record UploadAnimationMetadataPayload(String hash, String metadataJson) implements CustomPacketPayload {

    public static final Type<UploadAnimationMetadataPayload> TYPE = new Type<>(
        //? if <1.21.11 {
        ResourceLocation.fromNamespaceAndPath(QuickSkin.MOD_ID, "upload_animation_metadata")
        //?} else {
        Identifier.fromNamespaceAndPath(QuickSkin.MOD_ID, "upload_animation_metadata")
        //?}
    );

    public static final StreamCodec<ByteBuf, UploadAnimationMetadataPayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            PayloadCodecs.writeString(buf, payload.hash);
            PayloadCodecs.writeString(buf, payload.metadataJson);
        },
        buf -> new UploadAnimationMetadataPayload(
            PayloadCodecs.readString(buf),
            PayloadCodecs.readString(buf)
        )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
