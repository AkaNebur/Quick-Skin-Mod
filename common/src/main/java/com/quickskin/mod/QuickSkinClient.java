package com.quickskin.mod;

import com.quickskin.mod.client.input.KeybindRegistry;
import com.quickskin.mod.client.services.*;
import com.quickskin.mod.client.storage.LocalAppearanceStorage;
import com.quickskin.mod.event.ClientEvents;
import com.quickskin.mod.networking.ClientNetworking;
import com.quickskin.mod.platform.PlatformHelper;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Client-only entry point for QuickSkin mod
 * This class and all its methods are stripped on dedicated servers
 */
@Environment(EnvType.CLIENT)
public class QuickSkinClient {

    /**
     * Client initialization - only runs on client side
     * Called from platform-specific client entry points
     */
    public static void init() {
        QuickSkin.LOGGER.info("Initializing {} (Client)", QuickSkin.MOD_NAME);

        // Phase 2: Initialize client services
        QuickSkin.LOGGER.info("Initializing client services...");
        ModelService.init();
        SkinService.init();
        CapeService.init();
        PlayerAppearanceService.init();
        MojangApiService.init();

        // Phase 3: Register client networking (S2C receivers)
        ClientNetworking.init();

        // Phase 4: Register client events and keybinds
        ClientEvents.init();
        KeybindRegistry.init();

        // Phase 5: Initialize asset service and local storage
        LocalAssetManager.getInstance().init();
        LocalAppearanceStorage.getInstance().init(PlatformHelper.getConfigDirectory());

        // Phase 6: Auto-select player's own skin if no skin is currently selected
        ClientEvents.autoSelectPlayerOwnSkin();

        // Phase 7: Animation service (AnimatedTextureManager is lazy-initialized)
        // Ticking is handled in ClientEvents

        // Phase 9: Load client config
        com.quickskin.mod.config.ClientConfig.getInstance();

        QuickSkin.LOGGER.info("{} Client initialization complete", QuickSkin.MOD_NAME);
    }
}
