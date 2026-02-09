package com.quickskin.mod.event;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.gui.overlay.SkinPreviewOverlay;
import com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen;
import com.quickskin.mod.client.gui.util.DebugOffsetManager;
import com.quickskin.mod.client.gui.widget.PlayerWidget;
import com.quickskin.mod.client.services.AnimatedTextureManager;
import com.quickskin.mod.client.services.CooldownService;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.ModelService;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.PlayerAppearanceRepository;
import com.quickskin.mod.event.CapeTransparencyEvents;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.client.ClientGuiEvent;
import dev.architectury.event.events.client.ClientPlayerEvent;
import dev.architectury.event.events.client.ClientRawInputEvent;
import dev.architectury.event.events.client.ClientScreenInputEvent;
import dev.architectury.event.events.client.ClientTickEvent;
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
import org.lwjgl.glfw.GLFW;

import java.awt.image.BufferedImage;
import java.nio.file.Files;

/**
 * Client-side event handlers
 * Uses Architectury's event system for cross-platform compatibility
 */
@Environment(EnvType.CLIENT)
public class ClientEvents {

    private static int tickCounter = 0;
    private static PlayerWidget playerWidget;

    // Title screen rotation state (preserved across screen rebuilds)
    private static float titleScreenBodyYaw = 20.0f;
    private static float titleScreenTargetRotation = 20.0f;

    // Shared animation state (preserved across all screens)
    private static String sharedAnimation = "idle";

    /**
     * Get the current shared animation state
     */
    public static String getSharedAnimation() {
        return sharedAnimation;
    }

    /**
     * Set the shared animation state
     */
    public static void setSharedAnimation(String animation) {
        if (animation != null && !animation.isEmpty()) {
            sharedAnimation = animation;
        }
    }

    // Animation buttons (for dropdown menu)
    private static Button animationToggleButton;
    private static final java.util.List<Button> animationButtons = new java.util.ArrayList<>();
    private static boolean isAnimationDropdownOpen = false;
    private static boolean isLeftDraggingOverlay = false;
    private static boolean isRightDraggingOverlay = false;

    /**
     * Initializes client event listeners
     * Called from QuickSkinClient.init()
     */
    public static void init() {
        CapeTransparencyEvents.register();

        // Client tick (fires every game tick, ~20 times per second)
        ClientTickEvent.CLIENT_POST.register(client -> {
            // This also ensures the singleton instance is created.
            AnimatedTextureManager.getInstance().tick();

            // Handle HUD overlay dragging only when a GUI is open (cursor is visible)
            if (!client.mouseHandler.isMouseGrabbed()) {
                boolean leftMouseDown = GLFW.glfwGetMouseButton(client.getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
                boolean rightMouseDown = GLFW.glfwGetMouseButton(client.getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
                double mouseX = client.mouseHandler.xpos() * (double)client.getWindow().getGuiScaledWidth() / (double)client.getWindow().getScreenWidth();
                double mouseY = client.mouseHandler.ypos() * (double)client.getWindow().getGuiScaledHeight() / (double)client.getWindow().getScreenHeight();

                // Handle Left Click for moving
                if (leftMouseDown) {
                    if (!isLeftDraggingOverlay) {
                        if (SkinPreviewOverlay.onMouseClicked(mouseX, mouseY, 0).interruptsFurtherEvaluation()) {
                            isLeftDraggingOverlay = true;
                        }
                    } else {
                        SkinPreviewOverlay.onMouseDragged(mouseX, mouseY, 0, 0, 0);
                    }
                } else if (isLeftDraggingOverlay) {
                    SkinPreviewOverlay.onMouseReleased(mouseX, mouseY, 0);
                    isLeftDraggingOverlay = false;
                }

                // Handle Right Click for rotating
                if (rightMouseDown) {
                    if (!isRightDraggingOverlay) {
                        if (SkinPreviewOverlay.onRightMouseClicked(mouseX, mouseY, 1).interruptsFurtherEvaluation()) {
                            isRightDraggingOverlay = true;
                        }
                    } else {
                        SkinPreviewOverlay.onMouseDragged(mouseX, mouseY, 1, 0, 0);
                    }
                } else if (isRightDraggingOverlay) {
                    SkinPreviewOverlay.onMouseReleased(mouseX, mouseY, 1);
                    isRightDraggingOverlay = false;
                }
            } else {
                // If no screen is open or mouse is grabbed, ensure dragging is stopped
                if (isLeftDraggingOverlay) isLeftDraggingOverlay = false;
                if (isRightDraggingOverlay) isRightDraggingOverlay = false;
            }
        });

        // Download player's own skin on startup (async, won't block)
        ensurePlayerOwnSkinExists();

        // Player joins world (client-side)
        ClientPlayerEvent.CLIENT_PLAYER_JOIN.register(player -> {
            // Reset animation to idle when entering world
            setSharedAnimation("idle");

            // Phase 5: Rescan assets in case files changed while not in-game
            // LocalAssetManager.getInstance().reload();

            // Clear appearance repository on world join
            PlayerAppearanceRepository.getInstance().clear();
            CooldownService.getInstance().clearCooldown();

            // If in singleplayer, apply server config override immediately
            // (In multiplayer, this happens via network packet from server)
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && minecraft.hasSingleplayerServer()) {
                com.quickskin.mod.config.ServerConfig serverConfig = com.quickskin.mod.config.ServerConfig.getInstance();
                com.quickskin.mod.config.ClientConfig.getInstance().applyServerOverride(serverConfig);
            }

            // Restore saved skin and model type from config
            restoreSavedAppearance(player);
        });

        // Player quits world (client-side)
        ClientPlayerEvent.CLIENT_PLAYER_QUIT.register(player -> {
            // *** THE FIX: Clear all active animations to stop background ticking and render lookups ***
            AnimatedTextureManager.getInstance().clearAnimations();

            // Clear all appearance data
            PlayerAppearanceRepository.getInstance().clear();
            ModelService.getInstance().clearAll();
            CooldownService.getInstance().clearCooldown();

            // Clear incomplete texture chunks
            com.quickskin.mod.client.storage.TextureChunkReceiver.getInstance().clear();

            // Clear network texture cache (skins from other players)
            com.quickskin.mod.client.storage.NetworkTextureCache.getInstance().clear();

            // Clear server config override so client uses their own transparency settings in menu
            com.quickskin.mod.config.ClientConfig.getInstance().applyServerOverride(null);

            // Clear texture alpha detection cache since we're leaving the world
            com.quickskin.mod.common.util.TextureAlphaDetector.clearCache();

            // Clear local texture cache to force reload with client's own transparency settings
            com.quickskin.mod.client.services.LocalAssetManager.getInstance().clearTextureCache();

            // Clear cached player to reset rendering state (fixes invisible buttons)
            com.quickskin.mod.client.rendering.PlayerModelRenderer.clearCachedPlayer();

            // Phase 5: Save local preferences
            if (player != null) {
                com.quickskin.mod.client.storage.LocalAppearanceStorage.getInstance()
                        .savePlayerPreferences(player.getUUID());
            }

            // Re-register appearance for Essential's title screen player model
            com.quickskin.mod.client.compat.EssentialCompatIntegration.registerMenuAppearance();
        });

        // Respawn event (player dies and respawns)
        ClientPlayerEvent.CLIENT_PLAYER_RESPAWN.register((oldPlayer, newPlayer) -> {
            // Re-apply appearance after respawn
            restoreSavedAppearance(newPlayer);
        });

        // Screen init (after screen is initialized, before render)
        ClientGuiEvent.INIT_POST.register((client, screenAccess) -> {
            Screen screen = screenAccess.getScreen();

            // Determine screen type for all menu screens
            String screenType = determineScreenType(screen);
            if (screenType == null) {
                return; // Not a screen we care about
            }

            // Check for Essential mod compatibility
            boolean essentialPresent = com.quickskin.mod.client.compat.EssentialCompatIntegration.isAvailable();

            // Ensure Essential's player model uses QuickSkin's skin/cape
            if (essentialPresent) {
                com.quickskin.mod.client.compat.EssentialCompatIntegration.registerMenuAppearance();
            }

            // Inject QuickSkin button
            int buttonX = 0;
            int buttonY = 0;
            int buttonWidth = 98;
            int buttonHeight = 20;
            int spacing = 4;

            if (screen instanceof TitleScreen titleScreen) {
                boolean positioned = false;

                // If Essential is present, position to the left of Essential's bottom-most widget
                if (essentialPresent) {
                    net.minecraft.client.gui.components.events.GuiEventListener bottomWidget =
                            com.quickskin.mod.client.compat.EssentialCompatIntegration.findBottomEssentialWidget(screen);
                    if (bottomWidget instanceof net.minecraft.client.gui.components.AbstractWidget essentialWidget) {
                        buttonWidth = 20;
                        buttonHeight = 20;
                        buttonX = essentialWidget.getX() - buttonWidth - spacing;
                        buttonY = essentialWidget.getY();
                        positioned = true;
                    }
                }

                if (!positioned) {
                    // Position next to accessibility button on title screen
                    // The Y coordinate for the row with the vanilla language and accessibility buttons
                    final int vanillaButtonsY = titleScreen.height / 4 + 48 + 72;

                    net.minecraft.client.gui.components.ImageButton accessibilityButton = null;

                    // Find the right-most ImageButton on the right half of the screen in that specific row
                    // This specifically targets vanilla buttons and avoids other mods' buttons
                    for (net.minecraft.client.gui.components.events.GuiEventListener listener : screen.children()) {
                        if (listener instanceof net.minecraft.client.gui.components.ImageButton imgButton) {
                            if (imgButton.getY() == vanillaButtonsY &&
                                    imgButton.getX() > titleScreen.width / 2 &&
                                    imgButton.getWidth() == 20 &&
                                    imgButton.getHeight() == 20) {
                                if (accessibilityButton == null || imgButton.getX() > accessibilityButton.getX()) {
                                    accessibilityButton = imgButton;
                                }
                            }
                        }
                    }

                    // Position next to the found accessibility button
                    if (accessibilityButton != null) {
                        buttonX = accessibilityButton.getX() + accessibilityButton.getWidth() + spacing;
                        buttonY = accessibilityButton.getY();
                    } else {
                        // Fallback if we couldn't find the accessibility button
                        buttonX = titleScreen.width / 2 + 128;
                        buttonY = titleScreen.height / 4 + 48 + 84;
                    }
                }

            } else if (screen instanceof PauseScreen pauseScreen) {
                boolean positioned = false;

                // If Essential is present, position to the left of Essential's bottom-most widget
                if (essentialPresent) {
                    net.minecraft.client.gui.components.events.GuiEventListener bottomWidget =
                            com.quickskin.mod.client.compat.EssentialCompatIntegration.findBottomEssentialWidget(screen);
                    if (bottomWidget instanceof net.minecraft.client.gui.components.AbstractWidget essentialWidget) {
                        buttonWidth = 20;
                        buttonHeight = 20;
                        buttonX = essentialWidget.getX() - buttonWidth - spacing;
                        buttonY = essentialWidget.getY();
                        positioned = true;
                    }
                }

                if (!positioned) {
                    // Position next to "Save and Quit to Title" button
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
                        // Position directly next to the vanilla quit button
                        buttonX = saveAndQuitButton.getX() + saveAndQuitButton.getWidth() + spacing;
                        buttonY = saveAndQuitButton.getY();
                    } else {
                        // Fallback position if we can't find the button
                        buttonX = pauseScreen.width - buttonWidth - spacing;
                        buttonY = spacing;
                    }
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
            final Button changeSkinButton;
            if (essentialPresent) {
                // Use icon button when Essential is present
                changeSkinButton = new com.quickskin.mod.client.gui.widget.IconActionButton(
                        buttonX, buttonY, buttonWidth, buttonHeight,
                        new ResourceLocation("quickskin", "textures/gui/quickskin_icon.png"),
                        button -> Minecraft.getInstance().setScreen(new PlayerSkinMenuScreen(screen)),
                        Component.translatable("quickskin.button.change_skin")
                );
            } else {
                changeSkinButton = Button.builder(
                        Component.translatable("quickskin.button.change_skin"),
                        button -> Minecraft.getInstance().setScreen(new PlayerSkinMenuScreen(screen))
                ).bounds(buttonX, buttonY, buttonWidth, buttonHeight).build();
            }

            screenAccess.addRenderableWidget(changeSkinButton);

            // Skip PlayerWidget, rotate button, and animation buttons when Essential is present
            // (Essential has its own player model rendering with cosmetics)
            if (!essentialPresent) {
                // Create and add the PlayerWidget above the button using debug offsets
                int widgetSize = 144;
                int offsetX = DebugOffsetManager.getOffsetX(screenType);
                int offsetY = DebugOffsetManager.getOffsetY(screenType);

                int widgetX = buttonX + offsetX;
                int widgetY = buttonY + offsetY;

                // Get player skin and model type from saved config or player
                ResourceLocation skinLocation = null;
                String modelType = "classic";
                LocalPlayer player = Minecraft.getInstance().player;

                com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();

                // First priority: Use saved skin from config (works on title screen when player is null)
                if (!config.activeSkinHash.isEmpty()) {
                    com.quickskin.mod.client.services.LocalAssetManager assetManager =
                            com.quickskin.mod.client.services.LocalAssetManager.getInstance();
                    com.quickskin.mod.common.data.AssetMetadata metadata = assetManager.getMetadata(config.activeSkinHash);

                    if (metadata != null) {
                        // Load the saved skin texture
                        skinLocation = assetManager.getTextureLocation(config.activeSkinHash, com.quickskin.mod.common.data.TextureQuality.FULL);

                        // Get saved model type preference for this skin
                        modelType = assetManager.getSkinModelPreference(config.activeSkinHash);

                        // If auto mode, use the detected model type from metadata
                        if ("auto".equals(modelType)) {
                            modelType = metadata.skinModel();
                        }

                    }
                }

                // Second priority: Use current player skin (when in-game)
                if (skinLocation == null && player != null) {
                    skinLocation = player.getSkinTextureLocation();

                    // Get model type from the active skin if available
                    if (!config.activeSkinHash.isEmpty()) {
                        LocalAssetManager assetManager = LocalAssetManager.getInstance();
                        modelType = assetManager.getSkinModelPreference(config.activeSkinHash);
                        AssetMetadata metadata = assetManager.getMetadata(config.activeSkinHash);

                        // If auto mode, detect from the active custom skin (if any)
                        if ("auto".equals(modelType) && metadata != null) {
                            // Use the detected model type from the custom skin metadata
                            modelType = metadata.skinModel();
                        } else {
                            // Fallback: detect from the vanilla player's model
                            modelType = player.getModelName(); // "default" or "slim"
                            // Convert Minecraft model names to our format
                            if ("default".equals(modelType)) {
                                modelType = "classic";
                            }
                        }
                    } else if ("auto".equals(modelType)) {
                        // No custom skin active, use vanilla player's model
                        modelType = player.getModelName(); // "default" or "slim"
                        // Convert Minecraft model names to our format
                        if ("default".equals(modelType)) {
                            modelType = "classic";
                        }
                    }
                }

                // Fallback: Use default Steve skin
                if (skinLocation == null) {
                    skinLocation = new ResourceLocation("minecraft", "textures/entity/player/wide/steve.png");
                    modelType = "classic";
                }

                // Load saved cape from config
                String capeId = config.activeCapeHash;
                ResourceLocation capeLocation = null;
                if (capeId != null && !capeId.isEmpty()) {
                    // Use the service to resolve the location. This will also trigger animation registration.
                    // The UUID is not used for local/known capes, so we can pass null.
                    capeLocation = com.quickskin.mod.client.services.CapeService.getInstance().getCapeLocation(null, capeId);
                }

                // Save rotation and animation state from existing widget before creating new one
                if (playerWidget != null) {
                    titleScreenBodyYaw = playerWidget.getBodyYaw();
                    titleScreenTargetRotation = playerWidget.getTargetYRotation();
                    String currentAnimation = playerWidget.getAnimation();
                    if (currentAnimation != null && !currentAnimation.isEmpty()) {
                        setSharedAnimation(currentAnimation);
                    }
                }

                playerWidget = new PlayerWidget(widgetX, widgetY, widgetSize, widgetSize, skinLocation, capeLocation, capeId, modelType);
                // Set context based on screen type
                if ("title".equals(screenType)) {
                    playerWidget.setContext(com.quickskin.mod.client.gui.widget.PlayerWidget.WidgetContext.TITLE_SCREEN);
                } else if ("pause".equals(screenType)) {
                    playerWidget.setContext(com.quickskin.mod.client.gui.widget.PlayerWidget.WidgetContext.PAUSE_MENU);
                }
                screenAccess.addRenderableWidget(playerWidget);

                // Restore saved rotation and animation state
                playerWidget.setRotationState(titleScreenBodyYaw, titleScreenTargetRotation);
                String savedAnimation = getSharedAnimation();
                if (savedAnimation != null && !savedAnimation.isEmpty()) {
                    playerWidget.setAnimation(savedAnimation);
                }

                // Create and add rotate button (above Change Skin button, aligned to the left edge)
                int rotateButtonSize = 20;
                int rotateButtonX = buttonX;
                int rotateButtonY = buttonY - rotateButtonSize - spacing;

                com.quickskin.mod.client.gui.widget.RotateButton rotateButton =
                        new com.quickskin.mod.client.gui.widget.RotateButton(
                                rotateButtonX,
                                rotateButtonY,
                                rotateButtonSize,
                                button -> playerWidget.toggleRotation()
                        );
                screenAccess.addRenderableWidget(rotateButton);

                // Register priority widgets (take precedence over model interaction)
                playerWidget.clearPriorityWidgets(); // Clear old priorities
                playerWidget.addPriorityWidget(changeSkinButton); // Change Skin button
                playerWidget.addPriorityWidget(rotateButton); // Rotate button

                // Clear animation buttons from previous screen
                animationButtons.clear();
                isAnimationDropdownOpen = false;

                // Only add animation buttons on title screen, not in-game (pause menu)
                if ("title".equals(screenType)) {
                    // Create animation toggle button (right of rotate button)
                    int animToggleWidth = 20;
                    int animToggleX = buttonX + buttonWidth - animToggleWidth;
                    int animToggleY = rotateButtonY;

                    animationToggleButton = Button.builder(
                            Component.literal(">"),
                            button -> toggleAnimationDropdown()
                    ).bounds(animToggleX, animToggleY, animToggleWidth, rotateButtonSize).build();
                    screenAccess.addRenderableWidget(animationToggleButton);

                    // Register animation toggle button as priority widget
                    playerWidget.addPriorityWidget(animationToggleButton);

                    // Create numbered animation buttons (dropdown)
                    java.util.List<String> availableAnimations = getAvailableAnimations();
                    for (int i = 0; i < availableAnimations.size(); i++) {
                        final String animName = availableAnimations.get(i);
                        final int index = i;

                        Button animButton = Button.builder(
                                Component.literal(String.valueOf(index + 1)),
                                button -> {
                                    // Set the animation on the player widget
                                    if (playerWidget != null) {
                                        playerWidget.setAnimation(animName);
                                        // Save animation state for persistence across all screens
                                        setSharedAnimation(animName);
                                    }
                                    toggleAnimationDropdown();
                                }
                        ).bounds(animToggleX, animToggleY - (i + 1) * 22, animToggleWidth, rotateButtonSize).build();

                        animButton.visible = false;
                        animButton.active = false;
                        animationButtons.add(animButton);
                        screenAccess.addRenderableWidget(animButton);

                        // Register animation button as priority widget
                        playerWidget.addPriorityWidget(animButton);
                    }
                }
            } else {
                // Essential is present - hide our player widget and controls
                playerWidget = null;
                animationButtons.clear();
                isAnimationDropdownOpen = false;
            }

        });

        // Use PRE event for scrolling so we can interrupt it
        ClientScreenInputEvent.MOUSE_SCROLLED_PRE.register((client, screen, mouseX, mouseY, amount) -> {
            // Forward scroll events to the HUD overlay if the cursor is visible
            if (!client.mouseHandler.isMouseGrabbed()) {
                return SkinPreviewOverlay.onMouseScrolled(mouseX, mouseY, amount);
            }
            return EventResult.pass();
        });

        // Global mouse release handler to catch releases over other widgets
        // This ensures PlayerWidget receives mouseReleased even when released over skin list, etc.
        ClientScreenInputEvent.MOUSE_RELEASED_PRE.register((client, screen, mouseX, mouseY, button) -> {
            // Check if any PlayerWidget is currently being interacted with
            com.quickskin.mod.client.gui.widget.PlayerWidget activeWidget =
                com.quickskin.mod.client.gui.widget.PlayerWidget.getActiveInteractionWidget();

            if (activeWidget != null && activeWidget.isInteracting()) {
                // Forward the release event to the active PlayerWidget
                boolean handled = activeWidget.mouseReleased(mouseX, mouseY, button);
                if (handled) {
                    return EventResult.interruptTrue(); // Consume the event
                }
            }
            return EventResult.pass();
        });

        // HUD render (for potential skin preview overlay)
        ClientGuiEvent.RENDER_HUD.register((guiGraphics, tickDelta) -> {
            // Get the setting from the client configuration
            boolean showOverlay = com.quickskin.mod.config.ClientConfig.getInstance().showSkinPreviewOverlay;
            if (showOverlay) {
                com.quickskin.mod.client.gui.overlay.SkinPreviewOverlay.render(guiGraphics, tickDelta);
            }
        });

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

    /**
     * Ensure player's own skin exists in the list
     * Downloads it from Mojang if not present
     * Can be called at any time (even before joining a world)
     */
    private static void ensurePlayerOwnSkinExists() {
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        if (!config.enablePlayerOwnSkinSystem) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getUser() == null) {
            QuickSkin.LOGGER.warn("Cannot download player skin: Minecraft user not available");
            return;
        }

        String playerName = minecraft.getUser().getName();

        // Check if we already have the player's skin hash and it exists
        if (!config.playerOwnSkinHash.isEmpty()) {
            AssetMetadata existingMetadata = LocalAssetManager.getInstance().getMetadata(config.playerOwnSkinHash);
            if (existingMetadata != null) {
                // Player's skin already exists
                return;
            }
        }

        // Download player's own skin (async, won't block startup)
        com.quickskin.mod.client.services.MojangApiService.getInstance().fetchSkinByUsername(playerName)
                .thenAccept(skinData -> {
                    if (minecraft != null) {
                        minecraft.execute(() -> {
                            if (skinData != null) {
                                handlePlayerOwnSkinFetched(skinData);
                            } else {
                                QuickSkin.LOGGER.warn("Failed to fetch player's own skin");
                            }
                        });
                    }
                })
                .exceptionally(throwable -> {
                    QuickSkin.LOGGER.error("Error fetching player's own skin", throwable);
                    return null;
                });
    }

    /**
     * Handle the fetched player's own skin data
     * Smart mode: checks if skin already exists before saving a duplicate
     */
    private static void handlePlayerOwnSkinFetched(com.quickskin.mod.client.services.MojangApiService.MojangSkinData skinData) {
        try {
            // Process the image to get its final form before hashing and saving.
            // This ensures the hash we check against is the same as the one that will be generated from the saved file.
            BufferedImage image = skinData.image;

            // Convert legacy 64x32 skins to modern 64x64 format
            if (image.getHeight() == image.getWidth() / 2) {
                image = com.quickskin.mod.common.util.HDTextureProcessor.convertLegacyToModern(image);
            }

            // Apply transparency settings if needed
            if (com.quickskin.mod.config.ClientConfig.getInstance().shouldDisableSkinTransparency()) {
                image = com.quickskin.mod.common.util.HDTextureProcessor.removeTransparency(image);
            }

            // Convert the (potentially modified) image to a byte array to compute its definitive hash.
            byte[] processedImageBytes = com.quickskin.mod.common.util.HDTextureProcessor.imageToPng(image);
            if (processedImageBytes == null) {
                QuickSkin.LOGGER.error("Failed to convert processed player skin to byte array.");
                return;
            }

            String finalHash = com.quickskin.mod.common.util.HashUtil.computeHash(processedImageBytes);
            if (finalHash == null) {
                QuickSkin.LOGGER.error("Failed to compute hash for processed player skin.");
                return;
            }

            LocalAssetManager assetManager = LocalAssetManager.getInstance();
            AssetMetadata existingMetadata = assetManager.getMetadata(finalHash);

            if (existingMetadata == null) {
                // The skin does not exist in the local library, so we save it.
                String fileName = skinData.username + ".png";
                java.nio.file.Path targetPath = assetManager.getSkinsDirectory().resolve(fileName);

                // Handle filename collisions
                int counter = 1;
                while (Files.exists(targetPath)) {
                    targetPath = assetManager.getSkinsDirectory().resolve(skinData.username + "_" + counter + ".png");
                    counter++;
                }

                Files.write(targetPath, processedImageBytes);

                // Reload assets to recognize the new file.
                assetManager.reload();
            } else {
            }

            // Now that the skin is guaranteed to be in the asset manager, set its hash in the config.
            com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
            config.playerOwnSkinHash = finalHash;

            // If no active skin is set, auto-select the player's own skin.
            if (config.activeSkinHash.isEmpty()) {
                config.activeSkinHash = finalHash;

                // Apply it to the player if they're in a world.
                net.minecraft.client.player.LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
                if (player != null) {
                    AssetMetadata metadata = assetManager.getMetadata(finalHash);
                    if (metadata != null) {
                        String skinId = "local_skin:" + finalHash;
                        String modelType = assetManager.getSkinModelPreference(finalHash);

                        com.quickskin.mod.client.services.PlayerAppearanceService.getInstance()
                                .applySkin(player.getUUID(), skinId, modelType);

                    }
                } else {
                }
            } else {
            }

            config.save();

        } catch (Exception e) {
            QuickSkin.LOGGER.error("Error handling player's own skin", e);
        }
    }

    /**
     * Restore saved skin and cape from config when player joins world
     */
    private static void restoreSavedAppearance(LocalPlayer player) {
        // Check if we're in replay mode
        boolean isReplay = com.quickskin.mod.client.compat.ReplayModHelper.isInReplay();

        if (isReplay) {
            // In replay mode - start a background watcher that will apply skins when player entities load
            com.quickskin.mod.client.compat.ReplayModHelper.startReplayPlayerWatcher();
            return;
        }

        // Normal gameplay - apply to the joining player
        restoreSavedAppearanceToPlayer(player.getUUID());
    }

    /**
     * Helper to restore saved appearance to a specific player UUID
     */
    private static void restoreSavedAppearanceToPlayer(java.util.UUID targetPlayerId) {
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        com.quickskin.mod.client.services.LocalAssetManager assetManager =
                com.quickskin.mod.client.services.LocalAssetManager.getInstance();

        // Check if there's a saved skin
        if (!config.activeSkinHash.isEmpty()) {
            com.quickskin.mod.common.data.AssetMetadata metadata = assetManager.getMetadata(config.activeSkinHash);

            if (metadata != null) {
                // Apply the saved skin with the saved model type preference for this skin
                String skinId = "local_skin:" + metadata.hash();
                String modelType = assetManager.getSkinModelPreference(config.activeSkinHash);

                com.quickskin.mod.client.services.PlayerAppearanceService.getInstance()
                        .applySkin(targetPlayerId, skinId, modelType);

            } else {
                QuickSkin.LOGGER.warn("Saved skin hash not found in assets: {}", config.activeSkinHash);
            }
        } else if (!config.playerOwnSkinHash.isEmpty()) {
            // No skin selected, but player's own skin exists - auto-select it
            com.quickskin.mod.common.data.AssetMetadata metadata = assetManager.getMetadata(config.playerOwnSkinHash);

            if (metadata != null) {
                // Auto-select and apply the player's own skin
                config.activeSkinHash = config.playerOwnSkinHash;
                config.save();

                String skinId = "local_skin:" + metadata.hash();
                String modelType = assetManager.getSkinModelPreference(config.playerOwnSkinHash);

                // If auto mode, use the detected model from the skin
                if ("auto".equals(modelType)) {
                    modelType = metadata.skinModel();
                }

                com.quickskin.mod.client.services.PlayerAppearanceService.getInstance()
                        .applySkin(targetPlayerId, skinId, modelType);

            }
        }

        // Check if there's a saved cape
        if (!config.activeCapeHash.isEmpty()) {
            String capeId = config.activeCapeHash;

            com.quickskin.mod.client.services.PlayerAppearanceService.getInstance()
                    .applyCape(targetPlayerId, capeId);

        }
    }

    /**
     * Auto-select player's own skin if no skin is currently selected
     * Called during initialization to ensure base skin is always selected
     */
    public static void autoSelectPlayerOwnSkin() {
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();

        // Check if no skin is selected but player's own skin exists
        if (config.activeSkinHash.isEmpty() && !config.playerOwnSkinHash.isEmpty()) {
            LocalAssetManager assetManager = LocalAssetManager.getInstance();
            AssetMetadata metadata = assetManager.getMetadata(config.playerOwnSkinHash);

            if (metadata != null) {
                // Auto-select the player's own skin
                config.activeSkinHash = config.playerOwnSkinHash;
                config.save();

            } else {
            }
        } else if (!config.activeSkinHash.isEmpty()) {
        }
    }

    /**
     * Toggle the animation dropdown open/closed
     */
    private static void toggleAnimationDropdown() {
        isAnimationDropdownOpen = !isAnimationDropdownOpen;
        updateAnimationDropdownState();
    }

    /**
     * Update animation dropdown button visibility and toggle button text
     */
    private static void updateAnimationDropdownState() {
        if (animationToggleButton != null) {
            animationToggleButton.setMessage(Component.literal(isAnimationDropdownOpen ? "×" : ">"));
        }
        for (Button button : animationButtons) {
            button.visible = isAnimationDropdownOpen;
            button.active = isAnimationDropdownOpen;
        }
    }

    /**
     * Get list of available animations
     * Returns vanilla Minecraft animation states
     */
    private static java.util.List<String> getAvailableAnimations() {
        java.util.List<String> animations = new java.util.ArrayList<>();
        animations.add("idle");   // Button 1: Idle pose
        animations.add("walk");   // Button 2: Walking pose
        animations.add("sit");    // Button 3: Sitting pose
        return animations;
    }
}