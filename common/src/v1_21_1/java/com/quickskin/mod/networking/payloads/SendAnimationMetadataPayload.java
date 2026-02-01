package com.quickskin.mod.networking.payloads;

import com.quickskin.mod.QuickSkin;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Payload for sending animation metadata to client (S2C)
 * Format: String (hash) + String (metadataJson)
 */
public record SendAnimationMetadataPayload(String hash, String metadataJson) implements CustomPacketPayload {

    public static final Type<SendAnimationMetadataPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(QuickSkin.MOD_ID, "send_animation_metadata")
    );

    public static final StreamCodec<ByteBuf, SendAnimationMetadataPayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            PayloadCodecs.writeString(buf, payload.hash);
            PayloadCodecs.writeString(buf, payload.metadataJson);
        },
        buf -> new SendAnimationMetadataPayload(
            PayloadCodecs.readString(buf),
            PayloadCodecs.readString(buf)
        )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
