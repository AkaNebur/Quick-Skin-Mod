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

        // Initialize client code
        QuickSkinClient.init();
    }
}
