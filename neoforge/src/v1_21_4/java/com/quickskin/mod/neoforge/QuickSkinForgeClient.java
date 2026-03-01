package com.quickskin.mod.neoforge;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.QuickSkinClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * NeoForge client entry point for QuickSkin
 * This class is only loaded on NeoForge clients (not dedicated servers)
 */
@EventBusSubscriber(modid = QuickSkin.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class QuickSkinForgeClient {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // Initialize client code on the main thread
        event.enqueueWork(() -> {
            QuickSkinClient.init();
        });
    }
}
