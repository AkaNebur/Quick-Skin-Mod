package com.quickskin.mod.e2e.forge;

import com.quickskin.mod.e2e.E2EHarness;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Dev-only Forge entry point for the E2E harness, registered as a separate mod ({@code quick_skin_e2e})
 * backed by the {@code e2e} source set. Added only to E2E run configs; never in the published jar.
 * The harness drives the cross-loader Architectury {@code ClientTickEvent}, so no Forge-specific logic
 * beyond bootstrapping on client setup.
 */
@Mod(E2EForge.MODID)
public class E2EForge {

    public static final String MODID = "quick_skin_e2e";

    public E2EForge() {
        FMLJavaModLoadingContext.get().getModEventBus()
                .addListener((FMLClientSetupEvent event) -> event.enqueueWork(E2EHarness::start));
    }
}
