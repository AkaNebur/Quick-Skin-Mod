package com.quickskin.mod.event;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.networking.ServerNetworkHandler;
import com.quickskin.mod.server.storage.ServerAnimationCache;
import com.quickskin.mod.server.storage.ServerAppearanceStorage;
import com.quickskin.mod.server.storage.ServerTextureCache;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
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
        QuickSkin.LOGGER.info("Registering common events...");

        // Player joins server
        PlayerEvent.PLAYER_JOIN.register(player -> {
            QuickSkin.LOGGER.info("Player joined: {}", player.getName().getString());

            // Phase 5: Load player's saved appearance from server storage
            com.quickskin.mod.common.data.PlayerAppearance savedAppearance =
                ServerAppearanceStorage.getInstance().loadPlayerAppearance(player.getUUID());

            // If no saved appearance exists, create a default entry in the repository
            // This ensures all connected players have an entry that can be synced to joining players
            if (savedAppearance == null) {
                QuickSkin.LOGGER.debug("No saved appearance for {}, creating default entry", player.getName().getString());
                com.quickskin.mod.server.data.ServerPlayerAppearanceRepository.getInstance()
                    .updateAppearance(player.getUUID(), "", "", "classic");
            }

            // Phase 3: Send all other players' appearances to the joining player
            ServerNetworkHandler.sendAllAppearancesToPlayer((ServerPlayer) player);

            // CRITICAL FIX: Also send THIS player's appearance to all OTHER players
            // This ensures that existing players (like the host) see the joining player's appearance
            // AND when the host first starts the server, future joining players will see the host
            ServerNetworkHandler.sendAppearanceToAllPlayers((ServerPlayer) player);

            // Phase 9: Sync server config to client
            ServerNetworkHandler.sendServerConfigToPlayer((ServerPlayer) player);
        });

        // Player quits server
        PlayerEvent.PLAYER_QUIT.register(player -> {
            QuickSkin.LOGGER.info("Player quit: {}", player.getName().getString());

            // Phase 5: Save player's appearance to server storage
            ServerAppearanceStorage.getInstance().savePlayerAppearance(player.getUUID());

            // Cleanup server-side caches (textures stay cached for other players)
            // Note: We don't clear textures as they may be used by other players
        });

        // Player respawns (after death)
        PlayerEvent.PLAYER_RESPAWN.register((player, conqueredEnd) -> {
            if (player instanceof ServerPlayer) {
                ServerPlayer serverPlayer = (ServerPlayer) player;

                // Phase 3: Re-send all appearances to the respawned player
                ServerNetworkHandler.sendAllAppearancesToPlayer(serverPlayer);
            }
        });

        // Player changes dimension
        PlayerEvent.CHANGE_DIMENSION.register((player, oldLevel, newLevel) -> {

            // Re-sync appearance if needed (sometimes skins don't transfer across dimensions)
            if (player instanceof ServerPlayer) {
                ServerPlayer serverPlayer = (ServerPlayer) player;
                // Phase 3: Re-send all appearances to this player
                ServerNetworkHandler.sendAllAppearancesToPlayer(serverPlayer);
            }
        });

        // Server starting
        LifecycleEvent.SERVER_STARTING.register(server -> {
            QuickSkin.LOGGER.info("Server starting, initializing QuickSkin server components...");

            // Phase 5: Initialize server-side storage
            ServerTextureCache.getInstance().init(server);
            ServerAnimationCache.getInstance().init(server);
            ServerAppearanceStorage.getInstance().init(server);

            // Phase 9: Reload server config (in case it was modified)
            com.quickskin.mod.config.ServerConfig.reload();
        });

        // Server started (ready to accept players)
        LifecycleEvent.SERVER_STARTED.register(server -> {
            QuickSkin.LOGGER.info("Server started, QuickSkin ready");
        });

        // Server stopping
        LifecycleEvent.SERVER_STOPPING.register(server -> {
            QuickSkin.LOGGER.info("Server stopping, saving QuickSkin data...");

            // Phase 5: Save all pending texture data
            ServerTextureCache.getInstance().saveAll();

            // Phase 9: Save server config
            com.quickskin.mod.config.ServerConfig.getInstance().save();
        });

        // Server stopped
        LifecycleEvent.SERVER_STOPPED.register(server -> {
            QuickSkin.LOGGER.info("Server stopped, QuickSkin cleanup complete");

            // Phase 5: Clear caches
            ServerTextureCache.getInstance().clear();
            ServerAnimationCache.getInstance().clear();
        });

        QuickSkin.LOGGER.info("Common events registered");
    }
}
