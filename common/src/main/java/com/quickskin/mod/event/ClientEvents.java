package com.quickskin.mod.event;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.services.AnimatedTextureManager;
import com.quickskin.mod.client.services.ModelService;
import com.quickskin.mod.common.data.PlayerAppearanceRepository;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.client.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;

/**
 * Client-side event handlers
 * Uses Architectury's event system for cross-platform compatibility
 */
@Environment(EnvType.CLIENT)
public class ClientEvents {

    private static int tickCounter = 0;

    /**
     * Initializes client event listeners
     * Called from QuickSkinClient.init()
     */
    public static void init() {
        QuickSkin.LOGGER.info("Registering client events...");

        // Client tick (fires every game tick, ~20 times per second)
        ClientTickEvent.CLIENT_POST.register(client -> {
            tickCounter++;

            // Every second (20 ticks)
            if (tickCounter >= 20) {
                tickCounter = 0;

                // Phase 7: Tick animation service
                AnimatedTextureManager.getInstance().tick();
            }
        });

        // Player joins world (client-side)
        ClientPlayerEvent.CLIENT_PLAYER_JOIN.register(player -> {
            QuickSkin.LOGGER.info("Local player joined world: {}", player.getName().getString());

            // Phase 5: Rescan assets in case files changed while not in-game
            // LocalAssetManager.getInstance().reload();

            // TODO Phase 5b: Load player's local appearance preferences
            // LocalAppearanceStorage.loadPlayerPreferences(player.getUUID());

            // Clear appearance repository on world join
            PlayerAppearanceRepository.getInstance().clear();
        });

        // Player quits world (client-side)
        ClientPlayerEvent.CLIENT_PLAYER_QUIT.register(player -> {
            if (player != null) {
                QuickSkin.LOGGER.info("Local player quit world: {}", player.getName().getString());
            } else {
                QuickSkin.LOGGER.info("Local player quit world (player was null)");
            }

            // Clear all appearance data
            PlayerAppearanceRepository.getInstance().clear();
            ModelService.getInstance().clearAll();

            // TODO Phase 5: Save local preferences
            // if (player != null) LocalAppearanceStorage.savePlayerPreferences(player.getUUID());
        });

        // Respawn event (player dies and respawns)
        ClientPlayerEvent.CLIENT_PLAYER_RESPAWN.register((oldPlayer, newPlayer) -> {
            QuickSkin.LOGGER.debug("Player respawned");

            // Re-apply appearance after respawn
            // TODO Phase 3: Request appearance from server again
        });

        // Screen init (after screen is initialized, before render)
        ClientGuiEvent.INIT_POST.register((client, screen) -> {
            // Check if this is the title screen or pause screen
            if (screen instanceof TitleScreen || screen instanceof PauseScreen) {
                QuickSkin.LOGGER.debug("Screen initialized: {}", screen.getClass().getSimpleName());

                // TODO Phase 8: Inject QuickSkin button and PlayerWidget
                // GuiInjector.injectWidgets(screen, screen.width, screen.height);
            }
        });

        // Debug screen toggle (F3)
        ClientScreenInputEvent.KEY_PRESSED_PRE.register((client, screen, keyCode, scanCode, modifiers) -> {
            // This event is for screen key presses
            // Keybinds are handled separately in KeybindRegistry
            return EventResult.pass();
        });

        // Raw input (for global keybinds outside of screens)
        ClientRawInputEvent.KEY_PRESSED.register((client, keyCode, scanCode, action, modifiers) -> {
            // Keybinds will be registered separately
            // This is for raw key detection if needed
            return EventResult.pass();
        });

        // HUD render (for potential skin preview overlay)
        ClientGuiEvent.RENDER_HUD.register((guiGraphics, tickDelta) -> {
            // TODO Phase 8: Render skin preview overlay if enabled in config
            // if (ClientConfig.get().showSkinPreviewOverlay) {
            //     SkinPreviewOverlay.render(guiGraphics, tickDelta);
            // }
        });

        // Chat message receive
        // TODO: Determine correct signature for ClientChatEvent.RECEIVED in Architectury
        // May be useful for chat commands or notifications in future phases
        // ClientChatEvent.RECEIVED.register(message -> {
        //     return EventResult.pass();
        // });

        QuickSkin.LOGGER.info("Client events registered");
    }
}
