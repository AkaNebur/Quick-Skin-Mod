package com.quickskin.mod.client.gui.screen;

import com.quickskin.mod.client.gui.overlay.SkinPreviewOverlay;
import com.quickskin.mod.client.gui.util.ButtonFactory;
import com.quickskin.mod.client.gui.widget.TabButton;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.config.ServerConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
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

    // Panel styling - frosted glass effect
    private static final int PANEL_BG = 0xB0000000;           // Darker semi-transparent background
    private static final int PANEL_OUTLINE = 0x60FFFFFF;      // Subtle white outline

    // Tab dimensions
    private static final int TAB_HEIGHT = 30;
    private static final int TAB_WIDTH = 100;
    private static final int TAB_SPACING = 2;

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
    private TabButton clientTabButton;
    private TabButton serverTabButton;
    private final List<AbstractWidget> clientSettingWidgets = new ArrayList<>();
    private final List<AbstractWidget> serverSettingWidgets = new ArrayList<>();

    // Client setting checkboxes
    private Checkbox showOverlayCheckbox;
    private Checkbox enableAnimationsCheckbox;
    private Checkbox enableIdleAnimationCheckbox;
    private Checkbox autoRotatePreviewCheckbox;
    private Checkbox cacheTexturesCheckbox;
    private Checkbox enableHDSkinsCheckbox;
    private Checkbox autoSyncSkinsCheckbox;
    private Checkbox skinLayers3DCompatCheckbox;
    private Checkbox enableKeybindsCheckbox;

    // Server setting checkboxes
    private Checkbox allowCustomSkinsCheckbox;
    private Checkbox allowHDSkinsCheckbox;
    private Checkbox allowCustomCapesCheckbox;
    private Checkbox allowAnimatedCapesCheckbox;
    private Checkbox requireAuthenticationCheckbox;

    public SettingsScreen(@Nullable Screen parent) {
        super(Component.literal("QuickSkin Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        // Clear widget lists to prevent duplication on resize
        clientSettingWidgets.clear();
        serverSettingWidgets.clear();

        // Make dialog responsive to screen size
        dialogWidth = Math.min(400, this.width - 40);
        dialogHeight = Math.min(380, this.height - 40);

        // Center the dialog
        dialogX = (this.width - dialogWidth) / 2;
        dialogY = (this.height - dialogHeight) / 2;

        // Create tab buttons at the top of the panel
        int tabY = dialogY;
        int tabStartX = dialogX;

        clientTabButton = (TabButton) ButtonFactory.createTab(
            tabStartX, tabY,
            TAB_WIDTH, TAB_HEIGHT,
            Component.literal(Tab.CLIENT.getDisplayName()),
            activeTab == Tab.CLIENT,
            btn -> switchTab(Tab.CLIENT)
        );
        this.addRenderableWidget(clientTabButton);

        serverTabButton = (TabButton) ButtonFactory.createTab(
            tabStartX + TAB_WIDTH + TAB_SPACING, tabY,
            TAB_WIDTH, TAB_HEIGHT,
            Component.literal(Tab.SERVER.getDisplayName()),
            activeTab == Tab.SERVER,
            btn -> switchTab(Tab.SERVER)
        );
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
        int checkboxSize = 20;
        int spacing = 30;
        // Settings content area starts below tabs
        int startY = dialogY + TAB_HEIGHT + 20;
        int currentY = startY;
        int contentX = dialogX + 20;

        // HUD Overlay Settings
        showOverlayCheckbox = new Checkbox(
            contentX, currentY,
            checkboxSize, checkboxSize,
            Component.literal("Show HUD Overlay"),
            config.showSkinPreviewOverlay
        );
        clientSettingWidgets.add(showOverlayCheckbox);
        currentY += spacing;

        // Animation Settings
        enableAnimationsCheckbox = new Checkbox(
            contentX, currentY,
            checkboxSize, checkboxSize,
            Component.literal("Enable Animations"),
            config.enableAnimations
        );
        clientSettingWidgets.add(enableAnimationsCheckbox);
        currentY += spacing;

        enableIdleAnimationCheckbox = new Checkbox(
            contentX, currentY,
            checkboxSize, checkboxSize,
            Component.literal("Enable Idle Animation"),
            config.enableIdleAnimation
        );
        clientSettingWidgets.add(enableIdleAnimationCheckbox);
        currentY += spacing;

        autoRotatePreviewCheckbox = new Checkbox(
            contentX, currentY,
            checkboxSize, checkboxSize,
            Component.literal("Auto-Rotate Preview"),
            config.autoRotatePreview
        );
        clientSettingWidgets.add(autoRotatePreviewCheckbox);
        currentY += spacing;

        // Performance Settings
        cacheTexturesCheckbox = new Checkbox(
            contentX, currentY,
            checkboxSize, checkboxSize,
            Component.literal("Cache Textures"),
            config.cacheTextures
        );
        clientSettingWidgets.add(cacheTexturesCheckbox);
        currentY += spacing;

        enableHDSkinsCheckbox = new Checkbox(
            contentX, currentY,
            checkboxSize, checkboxSize,
            Component.literal("Enable HD Skins"),
            config.enableHDSkins
        );
        clientSettingWidgets.add(enableHDSkinsCheckbox);
        currentY += spacing;

        // Network Settings
        autoSyncSkinsCheckbox = new Checkbox(
            contentX, currentY,
            checkboxSize, checkboxSize,
            Component.literal("Auto-Sync Skins"),
            config.autoSyncSkins
        );
        clientSettingWidgets.add(autoSyncSkinsCheckbox);
        currentY += spacing;

        // Compatibility Settings
        skinLayers3DCompatCheckbox = new Checkbox(
            contentX, currentY,
            checkboxSize, checkboxSize,
            Component.literal("SkinLayers3D Compatibility"),
            config.skinLayers3DCompat
        );
        clientSettingWidgets.add(skinLayers3DCompatCheckbox);
        currentY += spacing;

        // Keybinds
        enableKeybindsCheckbox = new Checkbox(
            contentX, currentY,
            checkboxSize, checkboxSize,
            Component.literal("Enable Keybinds"),
            config.enableKeybinds
        );
        clientSettingWidgets.add(enableKeybindsCheckbox);
    }

    private void createServerSettings() {
        ServerConfig config = ServerConfig.getInstance();
        int checkboxSize = 20;
        int spacing = 30;
        // Settings content area starts below tabs
        int startY = dialogY + TAB_HEIGHT + 20;
        int currentY = startY;
        int contentX = dialogX + 20;

        // Check if player is on a server or in singleplayer
        boolean isServerAdmin = minecraft != null && minecraft.hasSingleplayerServer();

        // Skin Settings
        allowCustomSkinsCheckbox = new Checkbox(
            contentX, currentY,
            checkboxSize, checkboxSize,
            Component.literal("Allow Custom Skins"),
            config.allowCustomSkins
        );
        allowCustomSkinsCheckbox.active = isServerAdmin;
        serverSettingWidgets.add(allowCustomSkinsCheckbox);
        currentY += spacing;

        allowHDSkinsCheckbox = new Checkbox(
            contentX, currentY,
            checkboxSize, checkboxSize,
            Component.literal("Allow HD Skins"),
            config.allowHDSkins
        );
        allowHDSkinsCheckbox.active = isServerAdmin;
        serverSettingWidgets.add(allowHDSkinsCheckbox);
        currentY += spacing;

        // Cape Settings
        allowCustomCapesCheckbox = new Checkbox(
            contentX, currentY,
            checkboxSize, checkboxSize,
            Component.literal("Allow Custom Capes"),
            config.allowCustomCapes
        );
        allowCustomCapesCheckbox.active = isServerAdmin;
        serverSettingWidgets.add(allowCustomCapesCheckbox);
        currentY += spacing;

        allowAnimatedCapesCheckbox = new Checkbox(
            contentX, currentY,
            checkboxSize, checkboxSize,
            Component.literal("Allow Animated Capes"),
            config.allowAnimatedCapes
        );
        allowAnimatedCapesCheckbox.active = isServerAdmin;
        serverSettingWidgets.add(allowAnimatedCapesCheckbox);
        currentY += spacing;

        // Security Settings
        requireAuthenticationCheckbox = new Checkbox(
            contentX, currentY,
            checkboxSize, checkboxSize,
            Component.literal("Require Authentication"),
            config.requireAuthentication
        );
        requireAuthenticationCheckbox.active = isServerAdmin;
        serverSettingWidgets.add(requireAuthenticationCheckbox);
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

        // Update tab button selected states (visual feedback)
        clientTabButton.setSelected(tab == Tab.CLIENT);
        serverTabButton.setSelected(tab == Tab.SERVER);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Render parent screen in background (with mouse coords outside screen to prevent interaction)
        if (this.parent != null) {
            this.parent.render(graphics, -1, -1, partialTick);
        }

        // Push pose to ensure modal renders on a higher layer
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 100); // Move modal forward in Z

        // Draw darker overlay over entire screen
        graphics.fill(0, 0, this.width, this.height, 0xC0000000);

        // Calculate content panel area (below tabs)
        int contentPanelY = dialogY + TAB_HEIGHT;
        int contentPanelHeight = dialogHeight - TAB_HEIGHT;

        // Draw main content panel background with frosted glass styling
        graphics.fill(dialogX, contentPanelY,
                     dialogX + dialogWidth,
                     contentPanelY + contentPanelHeight,
                     PANEL_BG);

        // Draw outline around content panel
        // Top line (connects tabs to content)
        graphics.fill(dialogX, contentPanelY,
                     dialogX + dialogWidth, contentPanelY + 1,
                     PANEL_OUTLINE);
        // Bottom
        graphics.fill(dialogX, contentPanelY + contentPanelHeight - 1,
                     dialogX + dialogWidth, contentPanelY + contentPanelHeight,
                     PANEL_OUTLINE);
        // Left
        graphics.fill(dialogX, contentPanelY,
                     dialogX + 1, contentPanelY + contentPanelHeight,
                     PANEL_OUTLINE);
        // Right
        graphics.fill(dialogX + dialogWidth - 1, contentPanelY,
                     dialogX + dialogWidth, contentPanelY + contentPanelHeight,
                     PANEL_OUTLINE);

        // Render widgets (buttons, tabs, etc.) - this ensures they render AFTER everything above
        super.render(graphics, mouseX, mouseY, partialTick);

        // Render "Read-Only" notice for Server tab if not admin (render last to ensure it's on top)
        if (activeTab == Tab.SERVER && minecraft != null && !minecraft.hasSingleplayerServer()) {
            int noticeY = dialogY + dialogHeight - 55;
            String notice = "Server settings are read-only (not server admin)";
            int noticeWidth = this.font.width(notice);
            graphics.drawString(this.font, notice, dialogX + (dialogWidth - noticeWidth) / 2, noticeY, 0xAAAAAA, false);
        }

        graphics.pose().popPose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Click outside dialog (including tabs) = close
        if (mouseX < dialogX || mouseX > dialogX + dialogWidth ||
            mouseY < dialogY || mouseY > dialogY + dialogHeight) {
            this.onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        // Prevent parent screen interactions
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        // Allow scrolling only within modal
        if (mouseX >= dialogX && mouseX <= dialogX + dialogWidth &&
            mouseY >= dialogY && mouseY <= dialogY + dialogHeight) {
            return super.mouseScrolled(mouseX, mouseY, delta);
        }
        return false;
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
        // Save client settings
        if (showOverlayCheckbox != null) {
            ClientConfig config = ClientConfig.getInstance();

            config.showSkinPreviewOverlay = showOverlayCheckbox.selected();
            config.enableAnimations = enableAnimationsCheckbox.selected();
            config.enableIdleAnimation = enableIdleAnimationCheckbox.selected();
            config.autoRotatePreview = autoRotatePreviewCheckbox.selected();
            config.cacheTextures = cacheTexturesCheckbox.selected();
            config.enableHDSkins = enableHDSkinsCheckbox.selected();
            config.autoSyncSkins = autoSyncSkinsCheckbox.selected();
            config.skinLayers3DCompat = skinLayers3DCompatCheckbox.selected();
            config.enableKeybinds = enableKeybindsCheckbox.selected();

            config.save();
        }

        // Save server settings
        if (allowCustomSkinsCheckbox != null) {
            ServerConfig config = ServerConfig.getInstance();

            config.allowCustomSkins = allowCustomSkinsCheckbox.selected();
            config.allowHDSkins = allowHDSkinsCheckbox.selected();
            config.allowCustomCapes = allowCustomCapesCheckbox.selected();
            config.allowAnimatedCapes = allowAnimatedCapesCheckbox.selected();
            config.requireAuthentication = requireAuthenticationCheckbox.selected();

            config.save();
        }

        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }
}
