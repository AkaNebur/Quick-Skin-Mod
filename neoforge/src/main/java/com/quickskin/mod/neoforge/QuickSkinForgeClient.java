package com.quickskin.mod.neoforge;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.QuickSkinClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

/**
 * NeoForge client entry point for QuickSkin
 * This class is only loaded on NeoForge clients (not dedicated servers)
 */
@EventBusSubscriber(modid = QuickSkin.MOD_ID, value = Dist.CLIENT)
public class QuickSkinForgeClient {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // Initialize client code on the main thread
        event.enqueueWork(() -> {
            QuickSkinClient.init();
        });
    }

    /** Newer NeoForge versions route each annotated event to its declaring bus automatically. */
    @SubscribeEvent
    public static void onGameShuttingDown(GameShuttingDownEvent event) {
        QuickSkinClient.close();
    }
}
