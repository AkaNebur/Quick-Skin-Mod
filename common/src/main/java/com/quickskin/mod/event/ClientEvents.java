package com.quickskin.mod.event;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen;
import com.quickskin.mod.client.gui.util.DebugOffsetManager;
import com.quickskin.mod.client.gui.widget.PlayerWidget;
import com.quickskin.mod.client.services.AnimatedTextureManager;
import com.quickskin.mod.client.services.ModelService;
import com.quickskin.mod.common.data.PlayerAppearanceRepository;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.client.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-side event handlers
 * Uses Architectury's event system for cross-platform compatibility
 */
@Environment(EnvType.CLIENT)
public class ClientEvents {

    private static int tickCounter = 0;
    private static PlayerWidget playerWidget;

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
        ClientGuiEvent.INIT_POST.register((client, screenAccess) -> {
            Screen screen = screenAccess.getScreen();

            // Determine screen type for all menu screens
            String screenType = determineScreenType(screen);
            if (screenType == null) {
                return; // Not a screen we care about
            }

            QuickSkin.LOGGER.debug("Screen initialized: {} (type: {})", screen.getClass().getSimpleName(), screenType);

            // Inject QuickSkin button
            int buttonX = 0;
            int buttonY = 0;
            int buttonWidth = 98;
            int buttonHeight = 20;
            int spacing = 4;

            if (screen instanceof TitleScreen titleScreen) {
                // Position next to accessibility button on title screen
                // Find the actual accessibility/language button row by looking for the bottom-most row of small buttons
                int vanillaButtonsY = -1;
                int rightmostX = 0;

                // Step 1: Find all 20px height buttons and identify the bottom-most row
                int maxY = -1;
                for (net.minecraft.client.gui.components.events.GuiEventListener listener : screen.children()) {
                    if (listener instanceof Button button && button.getHeight() == buttonHeight) {
                        if (button.getY() > maxY) {
                            maxY = button.getY();
                        }
                    }
                }

                // Step 2: The bottom-most row is likely the language/accessibility row
                vanillaButtonsY = maxY;

                // Step 3: Find the rightmost button in that row
                if (vanillaButtonsY >= 0) {
                    for (net.minecraft.client.gui.components.events.GuiEventListener listener : screen.children()) {
                        if (listener instanceof AbstractWidget widget && widget.getY() == vanillaButtonsY) {
                            rightmostX = Math.max(rightmostX, widget.getX() + widget.getWidth());
                        }
                    }
                }

                // Fallback if we couldn't find any buttons
                if (vanillaButtonsY < 0) {
                    vanillaButtonsY = titleScreen.height / 4 + 48 + 72;
                    rightmostX = titleScreen.width / 2 + 124;
                }

                buttonX = rightmostX + spacing;
                buttonY = vanillaButtonsY;

            } else if (screen instanceof PauseScreen pauseScreen) {
                // Position next to "Save and Quit to Title" button (matching old mod's logic)
                Button saveAndQuitButton = null;
                int maxWidth = 0;

                // Find the widest button (vanilla buttons)
                for (net.minecraft.client.gui.components.events.GuiEventListener listener : screen.children()) {
                    if (listener instanceof Button button && button.getWidth() > maxWidth) {
                        maxWidth = button.getWidth();
                    }
                }

                // Find the bottom-most button with that max width (Save and Quit to Title)
                if (maxWidth > 0) {
                    int maxY = -1;
                    for (net.minecraft.client.gui.components.events.GuiEventListener listener : screen.children()) {
                        if (listener instanceof Button button && button.getWidth() == maxWidth && button.getY() > maxY) {
                            maxY = button.getY();
                            saveAndQuitButton = button;
                        }
                    }
                }

                if (saveAndQuitButton != null) {
                    int targetY = saveAndQuitButton.getY();
                    int rightmostX = saveAndQuitButton.getX() + saveAndQuitButton.getWidth();

                    // Find the true rightmost edge in that row to account for other mods
                    for (net.minecraft.client.gui.components.events.GuiEventListener listener : screen.children()) {
                        if (listener instanceof AbstractWidget widget && widget.getY() == targetY) {
                            rightmostX = Math.max(rightmostX, widget.getX() + widget.getWidth());
                        }
                    }

                    buttonX = rightmostX + spacing;
                    buttonY = targetY;
                } else {
                    // Fallback position if we can't find the button
                    buttonX = pauseScreen.width - buttonWidth - spacing;
                    buttonY = spacing;
                }
            } else {
                // For other screens (world selection, etc.), use similar logic to PauseScreen
                Button referenceButton = findLargestButton(screen);
                if (referenceButton != null) {
                    int targetY = referenceButton.getY();
                    int rightmostX = referenceButton.getX() + referenceButton.getWidth();

                    for (net.minecraft.client.gui.components.events.GuiEventListener listener : screen.children()) {
                        if (listener instanceof AbstractWidget widget && widget.getY() == targetY) {
                            rightmostX = Math.max(rightmostX, widget.getX() + widget.getWidth());
                        }
                    }

                    buttonX = rightmostX + spacing;
                    buttonY = targetY;
                } else {
                    // Fallback
                    buttonX = screen.width - buttonWidth - spacing;
                    buttonY = screen.height - buttonHeight - spacing;
                }
            }

            // Create and add the "Change Skin" button
            Button changeSkinButton = Button.builder(
                Component.literal("Change Skin"),
                button -> Minecraft.getInstance().setScreen(new PlayerSkinMenuScreen(screen))
            ).bounds(buttonX, buttonY, buttonWidth, buttonHeight).build();

            screenAccess.addRenderableWidget(changeSkinButton);

            // Create and add the PlayerWidget above the button using debug offsets
            int widgetSize = 144;
            int offsetX = DebugOffsetManager.getOffsetX(screenType);
            int offsetY = DebugOffsetManager.getOffsetY(screenType);

            int widgetX = buttonX + offsetX;
            int widgetY = buttonY + offsetY;

            // Get player skin or use default Steve skin
            ResourceLocation skinLocation = null;
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                skinLocation = player.getSkinTextureLocation();
            } else {
                // Fallback to Steve skin if no player available
                skinLocation = new ResourceLocation("minecraft", "textures/entity/player/wide/steve.png");
            }

            playerWidget = new PlayerWidget(widgetX, widgetY, widgetSize, widgetSize, skinLocation, null, "classic");
            playerWidget.setScreenType(screenType); // Set screen type for debug dragging
            screenAccess.addRenderableWidget(playerWidget);

            QuickSkin.LOGGER.debug("Added 'Change Skin' button at ({}, {}) and PlayerWidget at ({}, {}) for screen type '{}'",
                buttonX, buttonY, widgetX, widgetY, screenType);
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

    /**
     * Determine screen type for the player widget
     * Returns: "title" or "pause", or null if not a supported screen
     * ONLY adds widgets to Title Screen and Pause Screen
     */
    private static String determineScreenType(Screen screen) {
        if (screen instanceof TitleScreen) {
            return "title";
        } else if (screen instanceof PauseScreen) {
            return "pause";
        }

        // Don't add widgets to any other screens (skin menu, world selection, etc.)
        return null;
    }

    /**
     * Find the largest button on a screen (used for positioning reference)
     */
    private static Button findLargestButton(Screen screen) {
        Button largest = null;
        int maxWidth = 0;
        int maxY = -1;

        for (net.minecraft.client.gui.components.events.GuiEventListener listener : screen.children()) {
            if (listener instanceof Button button) {
                if (button.getWidth() > maxWidth) {
                    maxWidth = button.getWidth();
                }
            }
        }

        if (maxWidth > 0) {
            for (net.minecraft.client.gui.components.events.GuiEventListener listener : screen.children()) {
                if (listener instanceof Button button && button.getWidth() == maxWidth && button.getY() > maxY) {
                    maxY = button.getY();
                    largest = button;
                }
            }
        }

        return largest;
    }
}
