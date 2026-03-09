package com.quickskin.mod.networking.payloads;

import com.quickskin.mod.QuickSkin;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Payload for server config update from admin client
 * Format: String (key) + boolean (value)
 */
public record UpdateServerConfigPayload(String key, boolean value) implements CustomPacketPayload {

    public static final Type<UpdateServerConfigPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(QuickSkin.MOD_ID, "update_server_config")
    );

    public static final StreamCodec<ByteBuf, UpdateServerConfigPayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            PayloadCodecs.writeString(buf, payload.key);
            buf.writeBoolean(payload.value);
        },
        buf -> new UpdateServerConfigPayload(
            PayloadCodecs.readString(buf),
            buf.readBoolean()
        )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
