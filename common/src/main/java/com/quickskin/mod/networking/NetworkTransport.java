package com.quickskin.mod.networking;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * Transport seam for Quick Skin packet registration, capability checks, and delivery.
 */
public interface NetworkTransport {
    NetworkTransport INSTANCE = new ModNetworking();

    void init();

    void initClient();

    boolean canServerReceive(CustomPacketPayload.Type<?> type);

    boolean canPlayerReceive(ServerPlayer player, CustomPacketPayload.Type<?> type);

    void sendToServer(CustomPacketPayload payload);

    void sendToPlayer(ServerPlayer player, CustomPacketPayload payload);
}
