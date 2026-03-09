package com.quickskin.mod.networking.payloads;

import com.quickskin.mod.QuickSkin;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Payload for syncing server config to client (S2C)
 * Format: String (configJson)
 */
public record SyncServerConfigPayload(String configJson) implements CustomPacketPayload {

    public static final Type<SyncServerConfigPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(QuickSkin.MOD_ID, "sync_server_config")
    );

    public static final StreamCodec<ByteBuf, SyncServerConfigPayload> CODEC = StreamCodec.of(
        (buf, payload) -> PayloadCodecs.writeString(buf, payload.configJson),
        buf -> new SyncServerConfigPayload(PayloadCodecs.readString(buf))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
