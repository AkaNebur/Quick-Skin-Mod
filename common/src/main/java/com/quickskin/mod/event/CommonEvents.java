package com.quickskin.mod.event;

import com.quickskin.mod.QuickSkin;
//? if >=1.21 {
import com.quickskin.mod.networking.ServerNetworkHandler;
//?}
import com.quickskin.mod.networking.NetworkTransport;
//? if <1.21 {
import com.quickskin.mod.networking.ServerNetworkHandler;
import com.quickskin.mod.server.data.QuickSkinPlayerTracker;
//?} else {
import com.quickskin.mod.networking.payloads.CooldownUpdatePayload;
//?}
import com.quickskin.mod.server.data.ServerCooldownManager;
import com.quickskin.mod.server.storage.ServerAnimationCache;
import com.quickskin.mod.server.storage.ServerAppearanceStorage;
import com.quickskin.mod.server.storage.ServerTextureCache;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
//? if >=1.21 {
import com.quickskin.mod.networking.payloads.SyncAppearancePayload;
//?}
import net.minecraft.server.level.ServerPlayer;

/**
 * Common event handlers (runs on both client and server)
 * Uses Architectury's event system for cross-platform compatibility
 */
public class CommonEvents {

    /**
     * Initializes common event listeners
     * Called from QuickSkin.init()
     */
    public static void init() {
        // Player joins server
        PlayerEvent.PLAYER_JOIN.register(player -> {
            // Phase 5: Load player's saved appearance from server storage
            com.quickskin.mod.common.data.PlayerAppearance savedAppearance =
                ServerAppearanceStorage.getInstance().loadPlayerAppearance(player.getUUID());

            // If no saved appearance exists, create a default entry in the repository
            // This ensures all connected players have an entry that can be synced to joining players
            if (savedAppearance == null) {
                com.quickskin.mod.server.data.ServerPlayerAppearanceRepository.getInstance()
                    .updateAppearance(player.getUUID(), "", "", "classic");
            }

            // Send QuickSkin data only to players that have the mod installed
            ServerPlayer serverPlayer = (ServerPlayer) player;
            //? if <1.21 {
            boolean hasQuickSkin = NetworkTransport.INSTANCE.canPlayerReceiveQuickSkin(serverPlayer)
                    || QuickSkinPlayerTracker.getInstance().isConfirmed(serverPlayer.getUUID());
            //?} else {
            boolean hasQuickSkin = NetworkTransport.INSTANCE.canPlayerReceive(serverPlayer, SyncAppearancePayload.TYPE);
            //?}

            if (hasQuickSkin) {
                // Phase 3: Send all other players' appearances to the joining player
                ServerNetworkHandler.sendAllAppearancesToPlayer(serverPlayer);

                // Phase 9: Sync server config to client
                ServerNetworkHandler.sendServerConfigToPlayer(serverPlayer);

                // Send current cooldown status to joining player
                int cooldownSeconds = com.quickskin.mod.config.ServerConfig.getInstance().skinChangeCooldownSeconds;
                if (cooldownSeconds > 0 && ServerCooldownManager.getInstance().isPlayerOnCooldown(player.getUUID())) {
                    long cooldownEndTime = ServerCooldownManager.getInstance().getCooldownEndTime(player.getUUID());
                    //? if <1.21 {
                    NetworkTransport.INSTANCE.sendCooldownToPlayer(serverPlayer, cooldownEndTime);
                    //?} else {
                    CooldownUpdatePayload payload = new CooldownUpdatePayload(cooldownEndTime);
                    NetworkTransport.INSTANCE.sendToPlayer(serverPlayer, payload);
                    //?}
                }
            }

            // CRITICAL FIX: Send THIS player's appearance to all OTHER players that have QuickSkin
            // This ensures that existing players (like the host) see the joining player's appearance
            // (sendAppearanceToAllPlayers internally checks canReceiveQuickSkin for each recipient)
            ServerNetworkHandler.sendAppearanceToAllPlayers(serverPlayer);
        });

        // Player quits server
        PlayerEvent.PLAYER_QUIT.register(player -> {
            // Phase 5: Save player's appearance to server storage
            ServerAppearanceStorage.getInstance().savePlayerAppearance(player.getUUID());

            //? if <1.21 {
            QuickSkinPlayerTracker.getInstance().removePlayer(player.getUUID());
            //?}
            // Cleanup cooldown data (only if cooldown feature is enabled)
            int cooldownSeconds = com.quickskin.mod.config.ServerConfig.getInstance().skinChangeCooldownSeconds;
            if (cooldownSeconds > 0) {
                ServerCooldownManager.getInstance().removePlayer(player.getUUID());
            }

            // Cleanup server-side caches (textures stay cached for other players)
            // Note: We don't clear textures as they may be used by other players
        });

        // Player respawns (after death)
        //? if <1.21 {
        PlayerEvent.PLAYER_RESPAWN.register((player, conqueredEnd) -> {
            if (player instanceof ServerPlayer) {
                ServerPlayer serverPlayer = (ServerPlayer) player;
                if (NetworkTransport.INSTANCE.canPlayerReceiveQuickSkin(serverPlayer)
                        || QuickSkinPlayerTracker.getInstance().isConfirmed(serverPlayer.getUUID())) {
                    ServerNetworkHandler.sendAllAppearancesToPlayer(serverPlayer);
                }
            }
        });
        //?}

        // Player changes dimension
        PlayerEvent.CHANGE_DIMENSION.register((player, oldLevel, newLevel) -> {
            // Re-sync appearance if needed (sometimes skins don't transfer across dimensions)
            if (player instanceof ServerPlayer) {
                ServerPlayer serverPlayer = (ServerPlayer) player;
                //? if <1.21 {
                if (NetworkTransport.INSTANCE.canPlayerReceiveQuickSkin(serverPlayer)
                        || QuickSkinPlayerTracker.getInstance().isConfirmed(serverPlayer.getUUID())) {
                //?} else {
                if (NetworkTransport.INSTANCE.canPlayerReceive(serverPlayer, SyncAppearancePayload.TYPE)) {
                //?}
                    // Phase 3: Re-send all appearances to this player
                    ServerNetworkHandler.sendAllAppearancesToPlayer(serverPlayer);
                }
            }
        });

        // Server starting
        LifecycleEvent.SERVER_STARTING.register(server -> {
            // Phase 5: Initialize server-side storage
            ServerTextureCache.getInstance().init(server);
            ServerAnimationCache.getInstance().init(server);
            ServerAppearanceStorage.getInstance().init(server);

            // Phase 9: Reload server config (in case it was modified)
            com.quickskin.mod.config.ServerConfig.reload();
        });

        // Server started (ready to accept players)
        LifecycleEvent.SERVER_STARTED.register(server -> {
        });

        // Server stopping
        LifecycleEvent.SERVER_STOPPING.register(server -> {
            // Phase 5: Save all pending texture data
            ServerTextureCache.getInstance().saveAll();

            // Phase 9: Save server config
            com.quickskin.mod.config.ServerConfig.getInstance().save();
        });

        // Server stopped
        LifecycleEvent.SERVER_STOPPED.register(server -> {
            // Phase 5: Clear caches
            ServerTextureCache.getInstance().clear();
            ServerAnimationCache.getInstance().clear();
            //? if <1.21 {
            QuickSkinPlayerTracker.getInstance().clear();
            //?}
        });

    }
}
