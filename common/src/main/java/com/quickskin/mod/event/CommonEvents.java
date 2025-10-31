package com.quickskin.mod.event;

import com.quickskin.mod.QuickSkin;
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

            // TODO Phase 5: Load player's saved appearance from server storage
            // ServerAppearanceStorage.loadPlayerAppearance(player.getUUID());

            // TODO Phase 3: Send player's appearance to them
            // sendAppearanceToPlayer((ServerPlayer) player);

            // TODO Phase 9: Sync server config to client
            // ServerConfigManager.syncToClient((ServerPlayer) player);
        });

        // Player quits server
        PlayerEvent.PLAYER_QUIT.register(player -> {
            QuickSkin.LOGGER.info("Player quit: {}", player.getName().getString());

            // TODO Phase 5: Save player's appearance to server storage
            // ServerAppearanceStorage.savePlayerAppearance(player.getUUID());

            // Cleanup server-side caches
            // ServerTextureCache.clearPlayerData(player.getUUID());
        });

        // Player changes dimension
        PlayerEvent.CHANGE_DIMENSION.register((player, oldLevel, newLevel) -> {
            QuickSkin.LOGGER.debug("Player {} changed dimension: {} -> {}",
                    player.getName().getString(), oldLevel.location(), newLevel.location());

            // Re-sync appearance if needed (sometimes skins don't transfer across dimensions)
            if (player instanceof ServerPlayer) {
                ServerPlayer serverPlayer = (ServerPlayer) player;
                // TODO Phase 3: Re-send appearance packet
                // sendAppearanceToPlayer(serverPlayer);
            }
        });

        // Server starting
        LifecycleEvent.SERVER_STARTING.register(server -> {
            QuickSkin.LOGGER.info("Server starting, initializing QuickSkin server components...");

            // TODO Phase 5: Initialize server-side storage
            // ServerTextureCache.init(server);
            // ServerAnimationCache.init(server);

            // TODO Phase 9: Load server config
            // ServerConfig.load();
        });

        // Server started (ready to accept players)
        LifecycleEvent.SERVER_STARTED.register(server -> {
            QuickSkin.LOGGER.info("Server started, QuickSkin ready");
        });

        // Server stopping
        LifecycleEvent.SERVER_STOPPING.register(server -> {
            QuickSkin.LOGGER.info("Server stopping, saving QuickSkin data...");

            // TODO Phase 5: Save all pending texture data
            // ServerTextureCache.saveAll();

            // TODO Phase 9: Save server config
            // ServerConfig.save();
        });

        // Server stopped
        LifecycleEvent.SERVER_STOPPED.register(server -> {
            QuickSkin.LOGGER.info("Server stopped, QuickSkin cleanup complete");

            // TODO Phase 5: Clear caches
            // ServerTextureCache.clear();
            // ServerAnimationCache.clear();
        });

        QuickSkin.LOGGER.info("Common events registered");
    }
}
