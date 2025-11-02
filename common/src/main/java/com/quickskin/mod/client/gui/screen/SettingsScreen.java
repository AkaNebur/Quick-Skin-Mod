package com.quickskin.mod.client.gui.screen;

import com.quickskin.mod.client.gui.overlay.SkinPreviewOverlay;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.config.ServerConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Modal settings dialog for QuickSkin configuration
 * Features tabbed interface for Client and Server settings
 */
@Environment(EnvType.CLIENT)
public class SettingsScreen extends Screen {

    @Nullable
    private final Screen parent;

    private int dialogX;
    private int dialogY;
    private int dialogWidth = 400;
    private int dialogHeight = 380;

    private enum Tab {
        CLIENT("Client"),
        SERVER("Server");

        private final String displayName;

        Tab(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    private Tab activeTab = Tab.CLIENT;
    private Button clientTabButton;
    private Button serverTabButton;
    private final List<AbstractWidget> clientSettingWidgets = new ArrayList<>();
    private final List<AbstractWidget> serverSettingWidgets = new ArrayList<>();

    public SettingsScreen(@Nullable Screen parent) {
        super(Component.literal("QuickSkin Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        // Make dialog responsive to screen size
        dialogWidth = Math.min(400, this.width - 40);
        dialogHeight = Math.min(380, this.height - 40);

        // Center the dialog
        dialogX = (this.width - dialogWidth) / 2;
        dialogY = (this.height - dialogHeight) / 2;

        // Create tab buttons
        int tabWidth = 100;
        int tabHeight = 24;
        int tabY = dialogY + 8;
        int tabSpacing = 4;

        clientTabButton = Button.builder(
            Component.literal(Tab.CLIENT.getDisplayName()),
            btn -> switchTab(Tab.CLIENT)
        ).bounds(dialogX + 10, tabY, tabWidth, tabHeight).build();
        this.addRenderableWidget(clientTabButton);

        serverTabButton = Button.builder(
            Component.literal(Tab.SERVER.getDisplayName()),
            btn -> switchTab(Tab.SERVER)
        ).bounds(dialogX + 10 + tabWidth + tabSpacing, tabY, tabWidth, tabHeight).build();
        this.addRenderableWidget(serverTabButton);

        // Create settings for both tabs
        createClientSettings();
        createServerSettings();

        // Create Done button
        Button doneButton = Button.builder(
            Component.literal("Done"),
            btn -> this.onClose()
        ).bounds(dialogX + dialogWidth / 2 - 50, dialogY + dialogHeight - 30, 100, 20).build();
        this.addRenderableWidget(doneButton);

        // Show initial tab
        switchTab(activeTab);
    }

    private void createClientSettings() {
        ClientConfig config = ClientConfig.getInstance();
        int buttonWidth = dialogWidth - 40;
        int buttonHeight = 20;
        int spacing = 4;
        int startY = dialogY + 45;
        int currentY = startY;

        // HUD Overlay Settings
        clientSettingWidgets.add(CycleButton.onOffBuilder(config.showSkinPreviewOverlay)
            .create(dialogX + 20, currentY, buttonWidth, buttonHeight,
                Component.literal("Show HUD Overlay"),
                (button, value) -> {
                    config.showSkinPreviewOverlay = value;
                    config.save();
                }));
        currentY += buttonHeight + spacing;

        // Overlay Position
        clientSettingWidgets.add(CycleButton.<SkinPreviewOverlay.OverlayPosition>builder(
                pos -> Component.literal("Position: " + pos.name()))
            .withValues(SkinPreviewOverlay.OverlayPosition.values())
            .withInitialValue(SkinPreviewOverlay.OverlayPosition.valueOf(config.overlayPosition))
            .create(dialogX + 20, currentY, buttonWidth, buttonHeight,
                Component.literal("Overlay Position"),
                (button, value) -> {
                    config.overlayPosition = value.name();
                    SkinPreviewOverlay.setPosition(value);
                    config.save();
                }));
        currentY += buttonHeight + spacing;

        // Animation Settings
        clientSettingWidgets.add(CycleButton.onOffBuilder(config.enableAnimations)
            .create(dialogX + 20, currentY, buttonWidth, buttonHeight,
                Component.literal("Enable Animations"),
                (button, value) -> {
                    config.enableAnimations = value;
                    config.save();
                }));
        currentY += buttonHeight + spacing;

        clientSettingWidgets.add(CycleButton.onOffBuilder(config.enableIdleAnimation)
            .create(dialogX + 20, currentY, buttonWidth, buttonHeight,
                Component.literal("Enable Idle Animation"),
                (button, value) -> {
                    config.enableIdleAnimation = value;
                    config.save();
                }));
        currentY += buttonHeight + spacing;

        clientSettingWidgets.add(CycleButton.onOffBuilder(config.autoRotatePreview)
            .create(dialogX + 20, currentY, buttonWidth, buttonHeight,
                Component.literal("Auto-Rotate Preview"),
                (button, value) -> {
                    config.autoRotatePreview = value;
                    config.save();
                }));
        currentY += buttonHeight + spacing;

        // Performance Settings
        clientSettingWidgets.add(CycleButton.onOffBuilder(config.cacheTextures)
            .create(dialogX + 20, currentY, buttonWidth, buttonHeight,
                Component.literal("Cache Textures"),
                (button, value) -> {
                    config.cacheTextures = value;
                    config.save();
                }));
        currentY += buttonHeight + spacing;

        clientSettingWidgets.add(CycleButton.onOffBuilder(config.enableHDSkins)
            .create(dialogX + 20, currentY, buttonWidth, buttonHeight,
                Component.literal("Enable HD Skins"),
                (button, value) -> {
                    config.enableHDSkins = value;
                    config.save();
                }));
        currentY += buttonHeight + spacing;

        // Network Settings
        clientSettingWidgets.add(CycleButton.onOffBuilder(config.autoSyncSkins)
            .create(dialogX + 20, currentY, buttonWidth, buttonHeight,
                Component.literal("Auto-Sync Skins"),
                (button, value) -> {
                    config.autoSyncSkins = value;
                    config.save();
                }));
        currentY += buttonHeight + spacing;

        // Compatibility Settings
        clientSettingWidgets.add(CycleButton.onOffBuilder(config.skinLayers3DCompat)
            .create(dialogX + 20, currentY, buttonWidth, buttonHeight,
                Component.literal("SkinLayers3D Compatibility"),
                (button, value) -> {
                    config.skinLayers3DCompat = value;
                    config.save();
                }));
        currentY += buttonHeight + spacing;

        // Keybinds
        clientSettingWidgets.add(CycleButton.onOffBuilder(config.enableKeybinds)
            .create(dialogX + 20, currentY, buttonWidth, buttonHeight,
                Component.literal("Enable Keybinds"),
                (button, value) -> {
                    config.enableKeybinds = value;
                    config.save();
                }));
    }

    private void createServerSettings() {
        ServerConfig config = ServerConfig.getInstance();
        int buttonWidth = dialogWidth - 40;
        int buttonHeight = 20;
        int spacing = 4;
        int startY = dialogY + 45;
        int currentY = startY;

        // Check if player is on a server or in singleplayer
        boolean isServerAdmin = minecraft != null && minecraft.hasSingleplayerServer();

        // Skin Settings
        serverSettingWidgets.add(CycleButton.onOffBuilder(config.allowCustomSkins)
            .create(dialogX + 20, currentY, buttonWidth, buttonHeight,
                Component.literal("Allow Custom Skins"),
                (button, value) -> {
                    if (isServerAdmin) {
                        config.allowCustomSkins = value;
                        config.save();
                    }
                }));
        serverSettingWidgets.get(serverSettingWidgets.size() - 1).active = isServerAdmin;
        currentY += buttonHeight + spacing;

        serverSettingWidgets.add(CycleButton.onOffBuilder(config.allowHDSkins)
            .create(dialogX + 20, currentY, buttonWidth, buttonHeight,
                Component.literal("Allow HD Skins"),
                (button, value) -> {
                    if (isServerAdmin) {
                        config.allowHDSkins = value;
                        config.save();
                    }
                }));
        serverSettingWidgets.get(serverSettingWidgets.size() - 1).active = isServerAdmin;
        currentY += buttonHeight + spacing;

        // Cape Settings
        serverSettingWidgets.add(CycleButton.onOffBuilder(config.allowCustomCapes)
            .create(dialogX + 20, currentY, buttonWidth, buttonHeight,
                Component.literal("Allow Custom Capes"),
                (button, value) -> {
                    if (isServerAdmin) {
                        config.allowCustomCapes = value;
                        config.save();
                    }
                }));
        serverSettingWidgets.get(serverSettingWidgets.size() - 1).active = isServerAdmin;
        currentY += buttonHeight + spacing;

        serverSettingWidgets.add(CycleButton.onOffBuilder(config.allowAnimatedCapes)
            .create(dialogX + 20, currentY, buttonWidth, buttonHeight,
                Component.literal("Allow Animated Capes"),
                (button, value) -> {
                    if (isServerAdmin) {
                        config.allowAnimatedCapes = value;
                        config.save();
                    }
                }));
        serverSettingWidgets.get(serverSettingWidgets.size() - 1).active = isServerAdmin;
        currentY += buttonHeight + spacing;

        // Security Settings
        serverSettingWidgets.add(CycleButton.onOffBuilder(config.requireAuthentication)
            .create(dialogX + 20, currentY, buttonWidth, buttonHeight,
                Component.literal("Require Authentication"),
                (button, value) -> {
                    if (isServerAdmin) {
                        config.requireAuthentication = value;
                        config.save();
                    }
                }));
        serverSettingWidgets.get(serverSettingWidgets.size() - 1).active = isServerAdmin;
    }

    private void switchTab(Tab tab) {
        activeTab = tab;

        // Remove all setting widgets
        for (AbstractWidget widget : clientSettingWidgets) {
            this.removeWidget(widget);
        }
        for (AbstractWidget widget : serverSettingWidgets) {
            this.removeWidget(widget);
        }

        // Add widgets for active tab
        List<AbstractWidget> activeWidgets = tab == Tab.CLIENT ? clientSettingWidgets : serverSettingWidgets;
        for (AbstractWidget widget : activeWidgets) {
            this.addRenderableWidget(widget);
        }

        // Update tab button states (visual feedback)
        clientTabButton.active = (tab != Tab.CLIENT);
        serverTabButton.active = (tab != Tab.SERVER);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Darken background overlay
        graphics.fill(0, 0, this.width, this.height, 0xC0000000);

        // Draw dialog background
        graphics.fill(dialogX, dialogY, dialogX + dialogWidth, dialogY + dialogHeight, 0xFF2D2D2D);

        // Draw border
        graphics.fill(dialogX, dialogY, dialogX + dialogWidth, dialogY + 1, 0xFF5A5A5A); // Top
        graphics.fill(dialogX, dialogY + dialogHeight - 1, dialogX + dialogWidth, dialogY + dialogHeight, 0xFF5A5A5A); // Bottom
        graphics.fill(dialogX, dialogY, dialogX + 1, dialogY + dialogHeight, 0xFF5A5A5A); // Left
        graphics.fill(dialogX + dialogWidth - 1, dialogY, dialogX + dialogWidth, dialogY + dialogHeight, 0xFF5A5A5A); // Right

        // Draw tab separator line
        int tabLineY = dialogY + 38;
        graphics.fill(dialogX + 10, tabLineY, dialogX + dialogWidth - 10, tabLineY + 1, 0xFF5A5A5A);

        // Render widgets (buttons, etc.)
        super.render(graphics, mouseX, mouseY, partialTick);

        // Draw title at top right of dialog
        String titleText = "Settings";
        int titleX = dialogX + dialogWidth - this.font.width(titleText) - 10;
        int titleY = dialogY + 13;
        graphics.drawString(this.font, titleText, titleX, titleY, 0xFFFFFF, false);

        // Render "Read-Only" notice for Server tab if not admin
        if (activeTab == Tab.SERVER && minecraft != null && !minecraft.hasSingleplayerServer()) {
            int noticeY = dialogY + dialogHeight - 55;
            String notice = "Server settings are read-only (not server admin)";
            int noticeWidth = this.font.width(notice);
            graphics.drawString(this.font, notice, dialogX + (dialogWidth - noticeWidth) / 2, noticeY, 0xAAAAAA, false);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Click outside dialog = close
        if (mouseX < dialogX || mouseX > dialogX + dialogWidth ||
            mouseY < dialogY || mouseY > dialogY + dialogHeight) {
            this.onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // ESC key closes dialog
        if (keyCode == 256) { // ESC
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false; // Don't pause game
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }
}
