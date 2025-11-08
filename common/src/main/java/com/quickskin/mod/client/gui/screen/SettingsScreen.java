package com.quickskin.mod.client.gui.screen;

import com.mojang.blaze3d.platform.InputConstants;
import com.quickskin.mod.client.gui.util.ButtonFactory;
import com.quickskin.mod.client.gui.widget.TabButton;
import com.quickskin.mod.client.input.KeybindRegistry;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.config.ServerConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
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
    private int dialogWidth = 480;
    private int dialogHeight = 280;

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

    // Client setting widgets
    private Checkbox showOverlayCheckbox;
    private Checkbox skinLayers3DCompatCheckbox;
    private Checkbox disableSkinTransparencyCheckbox;
    private Checkbox enablePlayerPreviewCustomizationCheckbox;
    private Button keybindButton;

    // State for keybind editing
    @Nullable
    private KeyMapping selectedKey;

    // Server setting checkboxes
    private Checkbox serverDisableSkinTransparencyCheckbox;

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
        dialogWidth = Math.min(480, this.width - 40);
        dialogHeight = Math.min(280, this.height - 40);

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
        int leftColumnX = dialogX + 20;
        int rightColumnX = dialogX + dialogWidth / 2 + 10;
        int currentLeftY = startY;
        int currentRightY = startY;

        // Left Column
        // HUD Overlay Settings
        showOverlayCheckbox = new Checkbox(
                leftColumnX, currentLeftY,
                checkboxSize, checkboxSize,
                Component.literal("Show HUD Overlay"),
                config.showSkinPreviewOverlay
        );
        clientSettingWidgets.add(showOverlayCheckbox);
        currentLeftY += spacing;

        // Player Preview Customization Settings
        enablePlayerPreviewCustomizationCheckbox = new Checkbox(
                leftColumnX, currentLeftY,
                checkboxSize, checkboxSize,
                Component.literal("Enable Preview Customization"),
                config.enablePlayerPreviewCustomization
        );
        clientSettingWidgets.add(enablePlayerPreviewCustomizationCheckbox);
        currentLeftY += spacing;

        // Keybind button and label
        int keybindButtonWidth = 75;
        int keybindButtonSpacing = 5;

        keybindButton = Button.builder(
                KeybindRegistry.OPEN_SKIN_MENU.getTranslatedKeyMessage(),
                button -> this.selectedKey = KeybindRegistry.OPEN_SKIN_MENU
        ).bounds(leftColumnX, currentLeftY, keybindButtonWidth, 20).build();
        clientSettingWidgets.add(keybindButton);

        clientSettingWidgets.add(new AbstractWidget(leftColumnX + keybindButtonWidth + keybindButtonSpacing, currentLeftY, 100, 20, Component.literal("Open Skin Menu")) {
            @Override
            public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
                // Draw the string vertically centered with the standard UI text color.
                guiGraphics.drawString(
                        Minecraft.getInstance().font,
                        this.getMessage(),
                        this.getX(),
                        this.getY() + (this.height - 8) / 2,
                        0xE0E0E0 // Standard light gray text color
                );
            }

            @Override
            public void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput narrationElementOutput) {
                narrationElementOutput.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE, this.getMessage());
            }

            @Override
            public boolean mouseClicked(double pMouseX, double pMouseY, int pButton) {
                return false; // Make the label non-interactive
            }
        });


        // Right Column
        // Compatibility Settings
        skinLayers3DCompatCheckbox = new Checkbox(
                rightColumnX, currentRightY,
                checkboxSize, checkboxSize,
                Component.literal("SkinLayers3D Compatibility"),
                config.skinLayers3DCompat
        );
        clientSettingWidgets.add(skinLayers3DCompatCheckbox);
        currentRightY += spacing;

        // Skin Transparency Settings
        disableSkinTransparencyCheckbox = new Checkbox(
                rightColumnX, currentRightY,
                checkboxSize, checkboxSize,
                Component.literal("Disable Skin Transparency"),
                config.disableSkinTransparency
        );
        clientSettingWidgets.add(disableSkinTransparencyCheckbox);
    }

    private void createServerSettings() {
        ServerConfig config = ServerConfig.getInstance();
        int checkboxSize = 20;
        // Settings content area starts below tabs
        int startY = dialogY + TAB_HEIGHT + 20;
        int leftColumnX = dialogX + 20;

        // Check if player is on a server or in singleplayer
        boolean isServerAdmin = minecraft != null && minecraft.hasSingleplayerServer();

        // Transparency Settings
        serverDisableSkinTransparencyCheckbox = new Checkbox(
                leftColumnX, startY,
                checkboxSize, checkboxSize,
                Component.literal("Disable Skin Transparency"),
                config.disableSkinTransparency
        );
        serverDisableSkinTransparencyCheckbox.active = isServerAdmin;
        serverSettingWidgets.add(serverDisableSkinTransparencyCheckbox);
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

        // Update keybind button text before rendering
        if (this.selectedKey == KeybindRegistry.OPEN_SKIN_MENU) {
            this.keybindButton.setMessage(Component.literal("> ").append(Component.literal("???").withStyle(ChatFormatting.YELLOW)).append(" <"));
        } else {
            this.keybindButton.setMessage(KeybindRegistry.OPEN_SKIN_MENU.getTranslatedKeyMessage());
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
        // Handle setting a keybind with a mouse click
        if (this.selectedKey != null) {
            this.selectedKey.setKey(InputConstants.Type.MOUSE.getOrCreate(button));
            KeyMapping.resetMapping();
            this.selectedKey = null;
            return true;
        }

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
        // Handle setting a keybind with a keyboard press
        if (this.selectedKey != null) {
            if (keyCode == InputConstants.KEY_ESCAPE) {
                this.selectedKey.setKey(InputConstants.UNKNOWN);
            } else {
                this.selectedKey.setKey(InputConstants.getKey(keyCode, scanCode));
            }
            KeyMapping.resetMapping();
            this.selectedKey = null;
            return true;
        }

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
        // If we were editing a keybind, cancel it and save changes
        if(this.selectedKey != null) {
            KeyMapping.resetMapping();
            this.selectedKey = null;
        }

        // Save client settings
        if (showOverlayCheckbox != null) {
            ClientConfig config = ClientConfig.getInstance();

            // Check if transparency setting changed
            boolean oldTransparencySetting = config.disableSkinTransparency;

            config.showSkinPreviewOverlay = showOverlayCheckbox.selected();
            config.skinLayers3DCompat = skinLayers3DCompatCheckbox.selected();
            config.disableSkinTransparency = disableSkinTransparencyCheckbox.selected();
            config.enablePlayerPreviewCustomization = enablePlayerPreviewCustomizationCheckbox.selected();

            // If transparency setting changed, clear texture cache and refresh player
            if (oldTransparencySetting != config.disableSkinTransparency) {
                com.quickskin.mod.client.services.LocalAssetManager.getInstance().clearTextureCache();

                // Refresh the player's appearance to apply the new transparency setting
                if (minecraft != null && minecraft.player != null) {
                    com.quickskin.mod.client.services.PlayerAppearanceService.getInstance()
                        .refreshPlayerRenderer(minecraft.player.getUUID());
                }
            }

            config.save();
        }

        // Save server settings
        if (serverDisableSkinTransparencyCheckbox != null) {
            ServerConfig config = ServerConfig.getInstance();

            // Check if transparency setting changed
            boolean oldServerTransparencySetting = config.disableSkinTransparency;

            config.disableSkinTransparency = serverDisableSkinTransparencyCheckbox.selected();

            // If transparency setting changed, clear texture cache and refresh player
            if (oldServerTransparencySetting != config.disableSkinTransparency) {
                com.quickskin.mod.client.services.LocalAssetManager.getInstance().clearTextureCache();

                // Refresh the player's appearance to apply the new transparency setting
                if (minecraft != null && minecraft.player != null) {
                    com.quickskin.mod.client.services.PlayerAppearanceService.getInstance()
                        .refreshPlayerRenderer(minecraft.player.getUUID());
                }
            }

            config.save();
        }

        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }
}