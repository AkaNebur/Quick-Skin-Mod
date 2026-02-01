package com.quickskin.mod.networking.payloads;

import com.quickskin.mod.QuickSkin;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Payload for cooldown update to client (S2C)
 * Format: long (cooldownEndTime)
 */
public record CooldownUpdatePayload(long cooldownEndTime) implements CustomPacketPayload {

    public static final Type<CooldownUpdatePayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(QuickSkin.MOD_ID, "cooldown_update")
    );

    public static final StreamCodec<ByteBuf, CooldownUpdatePayload> CODEC = StreamCodec.of(
        (buf, payload) -> buf.writeLong(payload.cooldownEndTime),
        buf -> new CooldownUpdatePayload(buf.readLong())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
