package com.quickskin.mod;

import com.quickskin.mod.event.CommonEvents;
import com.quickskin.mod.networking.ModNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for QuickSkin mod (common initialization)
 * This runs on both Fabric and Forge
 */
public class QuickSkin {
    public static final String MOD_ID = "quickskin";
    public static final String MOD_NAME = "QuickSkin";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    /**
     * Common initialization - runs on both client and server
     * Called from platform-specific entry points (Forge @Mod, Fabric ModInitializer)
     */
    public static void init() {
        LOGGER.info("Initializing {} (Common)", MOD_NAME);

        // Phase 3: Register networking (server-side receivers)
        ModNetworking.init();

        // Phase 4: Register common events
        CommonEvents.init();

        // TODO Phase 5: Initialize storage
        // AssetService.init();

        // TODO Phase 9: Load server config
        // ServerConfig.load();

        LOGGER.info("{} Common initialization complete", MOD_NAME);
    }
}
