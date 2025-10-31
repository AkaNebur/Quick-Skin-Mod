package com.quickskin.mod.fabric;

import com.quickskin.mod.QuickSkin;
import net.fabricmc.api.ModInitializer;

/**
 * Fabric entry point for QuickSkin
 * This class is only loaded on Fabric
 */
public class QuickSkinFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        QuickSkin.LOGGER.info("QuickSkin loading on Fabric platform");

        // Initialize common code
        QuickSkin.init();
    }
}
