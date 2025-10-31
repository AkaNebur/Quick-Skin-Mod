package com.quickskin.mod.fabric;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.QuickSkinClient;
import net.fabricmc.api.ClientModInitializer;

/**
 * Fabric client entry point for QuickSkin
 * This class is only loaded on Fabric clients (not dedicated servers)
 */
public class QuickSkinFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        QuickSkin.LOGGER.info("QuickSkin client initialization on Fabric");

        // Initialize client code
        QuickSkinClient.init();
    }
}
