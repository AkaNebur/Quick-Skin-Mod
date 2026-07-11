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
 * Payload for updating player appearance
 * Format: UUID (player) + String (skinId) + String (capeId) + String (model)
 */
public record UpdateAppearancePayload(UUID playerId, String skinId, String capeId, String model) implements CustomPacketPayload {

    public static final Type<UpdateAppearancePayload> TYPE = new Type<>(
        //? if <1.21.11 {
        ResourceLocation.fromNamespaceAndPath(QuickSkin.MOD_ID, "update_appearance")
        //?} else {
        Identifier.fromNamespaceAndPath(QuickSkin.MOD_ID, "update_appearance")
        //?}
    );

    public static final StreamCodec<ByteBuf, UpdateAppearancePayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            PayloadCodecs.writeUUID(buf, payload.playerId);
            PayloadCodecs.writeString(buf, payload.skinId != null ? payload.skinId : "");
            PayloadCodecs.writeString(buf, payload.capeId != null ? payload.capeId : "");
            PayloadCodecs.writeString(buf, payload.model != null ? payload.model : "classic");
        },
        buf -> new UpdateAppearancePayload(
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
