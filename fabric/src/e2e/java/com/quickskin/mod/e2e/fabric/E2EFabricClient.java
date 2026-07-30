package com.quickskin.mod.e2e.fabric;

import com.quickskin.mod.e2e.E2EHarness;
import net.fabricmc.api.ClientModInitializer;

/**
 * Dev-only Fabric client entry point for the E2E harness. This class lives in the {@code e2e} source
 * set, which is added only to E2E Loom run configs and never to the published jar.
 */
public class E2EFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        E2EHarness.start();
    }
}
