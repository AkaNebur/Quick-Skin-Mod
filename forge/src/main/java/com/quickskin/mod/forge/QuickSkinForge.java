package com.quickskin.mod.forge;

import com.quickskin.mod.QuickSkin;
import dev.architectury.platform.forge.EventBuses;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Forge entry point for QuickSkin
 * This class is only loaded on Forge
 */
@Mod(QuickSkin.MOD_ID)
public class QuickSkinForge {

    public QuickSkinForge() {
        // CRITICAL: Register Architectury event bus with Forge
        // This is required for Architectury events to work on Forge
        EventBuses.registerModEventBus(
            QuickSkin.MOD_ID,
            FMLJavaModLoadingContext.get().getModEventBus()
        );

        QuickSkin.LOGGER.info("QuickSkin loading on Forge platform");

        // Initialize common code
        QuickSkin.init();
    }
}
