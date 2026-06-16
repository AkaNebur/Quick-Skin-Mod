package com.quickskin.mod.networking.payloads;

import com.quickskin.mod.QuickSkin;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * Payload for syncing appearance to clients (S2C)
 * Format: UUID (player) + String (skinId) + String (capeId) + String (model)
 */
public record SyncAppearancePayload(UUID playerId, String skinId, String capeId, String model) implements CustomPacketPayload {

    public static final Type<SyncAppearancePayload> TYPE = new Type<>(
        Identifier.fromNamespaceAndPath(QuickSkin.MOD_ID, "sync_appearance")
    );

    public static final StreamCodec<ByteBuf, SyncAppearancePayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            PayloadCodecs.writeUUID(buf, payload.playerId);
            PayloadCodecs.writeString(buf, payload.skinId != null ? payload.skinId : "");
            PayloadCodecs.writeString(buf, payload.capeId != null ? payload.capeId : "");
            PayloadCodecs.writeString(buf, payload.model != null ? payload.model : "classic");
        },
        buf -> new SyncAppearancePayload(
            PayloadCodecs.readUUID(buf),
            PayloadCodecs.readString(buf),
            PayloadCodecs.readString(buf),
            PayloadCodecs.readString(buf)
        )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
