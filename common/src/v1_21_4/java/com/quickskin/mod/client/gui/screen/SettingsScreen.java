package com.quickskin.mod.client.gui.screen;

import com.mojang.blaze3d.platform.InputConstants;
import com.quickskin.mod.client.gui.effect.BlurHandler;
import com.quickskin.mod.client.gui.util.ButtonFactory;
import com.quickskin.mod.client.gui.widget.TabButton;
import com.quickskin.mod.client.input.KeybindRegistry;
import com.quickskin.mod.common.data.BackgroundStyle;
import com.quickskin.mod.config.ClientConfig;
import com.quickskin.mod.config.ServerConfig;
import com.quickskin.mod.networking.payloads.UpdateServerConfigPayload;
import dev.architectury.networking.NetworkManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
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
        GUI_EDIT("quickskin.settings.tab.gui_edit"),
        CLIENT("quickskin.settings.tab.client"),
        SERVER("quickskin.settings.tab.server"),
        MODPACK("quickskin.settings.tab.modpack");

        private final String translationKey;

        Tab(String translationKey) {
            this.translationKey = translationKey;
        }

        public String getTranslationKey() {
            return translationKey;
        }
    }

    private Tab activeTab = Tab.CLIENT;
    private TabButton guiEditTabButton;
    private TabButton clientTabButton;
    private TabButton serverTabButton;
    private TabButton modpackTabButton;
    private final List<AbstractWidget> guiEditSettingWidgets = new ArrayList<>();
    private final List<AbstractWidget> clientSettingWidgets = new ArrayList<>();
    private final List<AbstractWidget> serverSettingWidgets = new ArrayList<>();
    private final List<AbstractWidget> modpackSettingWidgets = new ArrayList<>();


    // Client setting widgets
    private Checkbox showOverlayCheckbox;
    private Checkbox disableSkinTransparencyCheckbox;
    private Checkbox enableStyledButtonsCheckbox;
    private Checkbox enablePlayerPreviewCustomizationCheckbox;
    private Checkbox hideBuiltInCapesCheckbox;
    private Checkbox menuBackgroundCheckbox;
    private Button keybindButton;

    // State for keybind editing
    @Nullable
    private KeyMapping selectedKey;

    // Server setting checkboxes
    private Checkbox serverDisableSkinTransparencyCheckbox;
    private EditBox skinChangeCooldownEditBox;

    // --- NEW --- Modpack setting widgets
    private Checkbox enablePlayerOwnSkinSystemCheckbox;

    // Track if texture reload is needed
    private boolean needsTextureReload = false;

    public SettingsScreen(@Nullable Screen parent) {
        super(Component.translatable("quickskin.screen.settings.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        // Clear widget lists to prevent duplication on resize
        guiEditSettingWidgets.clear();
        clientSettingWidgets.clear();
        serverSettingWidgets.clear();
        modpackSettingWidgets.clear();

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
                Component.translatable(Tab.CLIENT.getTranslationKey()),
                activeTab == Tab.CLIENT,
                btn -> switchTab(Tab.CLIENT)
        );
        this.addRenderableWidget(clientTabButton);

        guiEditTabButton = (TabButton) ButtonFactory.createTab(
                tabStartX + TAB_WIDTH + TAB_SPACING, tabY,
                TAB_WIDTH, TAB_HEIGHT,
                Component.translatable(Tab.GUI_EDIT.getTranslationKey()),
                activeTab == Tab.GUI_EDIT,
                btn -> switchTab(Tab.GUI_EDIT)
        );
        this.addRenderableWidget(guiEditTabButton);

        modpackTabButton = (TabButton) ButtonFactory.createTab(
                tabStartX + (TAB_WIDTH + TAB_SPACING) * 2, tabY,
                TAB_WIDTH, TAB_HEIGHT,
                Component.translatable(Tab.MODPACK.getTranslationKey()),
                activeTab == Tab.MODPACK,
                btn -> switchTab(Tab.MODPACK)
        );
        this.addRenderableWidget(modpackTabButton);

        serverTabButton = (TabButton) ButtonFactory.createTab(
                tabStartX + (TAB_WIDTH + TAB_SPACING) * 3, tabY,
                TAB_WIDTH, TAB_HEIGHT,
                Component.translatable(Tab.SERVER.getTranslationKey()),
                activeTab == Tab.SERVER,
                btn -> switchTab(Tab.SERVER)
        );
        this.addRenderableWidget(serverTabButton);

        // Create settings for all tabs
        createGuiEditSettings();
        createClientSettings();
        createServerSettings();
        createModpackSettings();

        // Create Done button
        Button doneButton = ButtonFactory.createPrimary(
                dialogX + dialogWidth / 2 - 50, dialogY + dialogHeight - 30, 100, 20,
                Component.translatable("quickskin.button.done"),
                btn -> this.onClose()
        );
        this.addRenderableWidget(doneButton);

        // Show initial tab
        switchTab(activeTab);
    }

    private void createGuiEditSettings() {
        ClientConfig config = ClientConfig.getInstance();
        int checkboxSize = 20;
        int spacing = 30;
        // Settings content area starts below tabs
        int startY = dialogY + TAB_HEIGHT + 20;
        int leftColumnX = dialogX + 20;
        int currentY = startY;

        // Show HUD Overlay
        showOverlayCheckbox = Checkbox.builder(
                Component.translatable("quickskin.settings.enable_preview"),
                this.font)
                .pos(leftColumnX, currentY)
                .selected(config.showSkinPreviewOverlay)
                .build();
        guiEditSettingWidgets.add(showOverlayCheckbox);
        currentY += spacing;

        // Enable Styled Buttons
        enableStyledButtonsCheckbox = Checkbox.builder(
                Component.translatable("quickskin.settings.enable_styled_buttons"),
                this.font)
                .pos(leftColumnX, currentY)
                .selected(config.enableStyledButtons)
                .build();
        guiEditSettingWidgets.add(enableStyledButtonsCheckbox);
        currentY += spacing;

        // Enable Preview Customization
        enablePlayerPreviewCustomizationCheckbox = Checkbox.builder(
                Component.translatable("quickskin.settings.enable_preview_custom"),
                this.font)
                .pos(leftColumnX, currentY)
                .selected(config.enablePlayerPreviewCustomization)
                .build();
        guiEditSettingWidgets.add(enablePlayerPreviewCustomizationCheckbox);
        currentY += spacing;

        // Hide Built-in Capes
        hideBuiltInCapesCheckbox = Checkbox.builder(
                Component.translatable("quickskin.settings.hide_builtin_capes"),
                this.font)
                .pos(leftColumnX, currentY)
                .selected(config.hideBuiltInCapes)
                .tooltip(Tooltip.create(Component.translatable("quickskin.settings.hide_builtin_capes.tooltip")))
                .build();
        guiEditSettingWidgets.add(hideBuiltInCapesCheckbox);
        currentY += spacing;

        // Menu Background Style
        menuBackgroundCheckbox = Checkbox.builder(
                Component.translatable("quickskin.settings.menu_background"),
                this.font)
                .pos(leftColumnX, currentY)
                .selected(config.getMenuBackgroundStyle() == BackgroundStyle.VANILLA_BLUR)
                .tooltip(Tooltip.create(Component.translatable("quickskin.settings.menu_background.tooltip")))
                .build();
        guiEditSettingWidgets.add(menuBackgroundCheckbox);
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
        // Keybind button and label
        int keybindButtonWidth = 75;
        int keybindButtonSpacing = 5;

        keybindButton = ButtonFactory.createStyled(
                leftColumnX, currentLeftY, keybindButtonWidth, 20,
                KeybindRegistry.OPEN_SKIN_MENU.getTranslatedKeyMessage(),
                button -> this.selectedKey = KeybindRegistry.OPEN_SKIN_MENU
        );
        clientSettingWidgets.add(keybindButton);

        clientSettingWidgets.add(new AbstractWidget(leftColumnX + keybindButtonWidth + keybindButtonSpacing, currentLeftY, 100, 20, Component.translatable("quickskin.settings.keybind_label")) {
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
        // Skin Transparency Settings
        disableSkinTransparencyCheckbox = Checkbox.builder(
                Component.translatable("quickskin.settings.disable_transparency"),
                this.font)
                .pos(rightColumnX, currentRightY)
                .selected(config.disableSkinTransparency)
                .build();
        clientSettingWidgets.add(disableSkinTransparencyCheckbox);
    }

    private void createServerSettings() {
        ServerConfig config = ServerConfig.getInstance();
        int checkboxSize = 20;
        int spacing = 30;
        // Settings content area starts below tabs
        int startY = dialogY + TAB_HEIGHT + 20;
        int leftColumnX = dialogX + 20;
        int currentY = startY;

        // Check if player has admin permissions
        boolean isAdmin = minecraft != null && minecraft.player != null && minecraft.player.hasPermissions(2);

        // Get current server transparency setting from server override
        // This is synced from the server on join (both singleplayer and multiplayer)
        ServerConfig serverOverride = ClientConfig.getInstance().getServerOverride();
        boolean currentTransparencySetting = serverOverride != null ? serverOverride.disableSkinTransparency : false;

        // Transparency Settings
        serverDisableSkinTransparencyCheckbox = Checkbox.builder(
                Component.translatable("quickskin.settings.disable_transparency_server"),
                this.font)
                .pos(leftColumnX, currentY)
                .selected(currentTransparencySetting)
                .build();
        // Only allow admins to change this setting
        serverDisableSkinTransparencyCheckbox.active = isAdmin;
        serverSettingWidgets.add(serverDisableSkinTransparencyCheckbox);
        currentY += spacing;

        // Skin Change Cooldown Setting
        int editBoxWidth = 60;
        int editBoxSpacing = 5;

        skinChangeCooldownEditBox = new EditBox(
                this.font,
                leftColumnX, currentY,
                editBoxWidth, 20,
                Component.translatable("quickskin.settings.cooldown_label")
        );
        skinChangeCooldownEditBox.setValue(String.valueOf(config.skinChangeCooldownSeconds));
        skinChangeCooldownEditBox.setMaxLength(5);
        skinChangeCooldownEditBox.setFilter(text -> text.isEmpty() || text.matches("\\d+"));
        skinChangeCooldownEditBox.active = isAdmin;
        serverSettingWidgets.add(skinChangeCooldownEditBox);

        // Label for cooldown EditBox
        serverSettingWidgets.add(new AbstractWidget(leftColumnX + editBoxWidth + editBoxSpacing, currentY, 200, 20, Component.translatable("quickskin.settings.cooldown_seconds")) {
            @Override
            public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
                guiGraphics.drawString(
                        Minecraft.getInstance().font,
                        this.getMessage(),
                        this.getX(),
                        this.getY() + (this.height - 8) / 2,
                        0xE0E0E0
                );
            }

            @Override
            public void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput narrationElementOutput) {
                narrationElementOutput.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE, this.getMessage());
            }

            @Override
            public boolean mouseClicked(double pMouseX, double pMouseY, int pButton) {
                return false;
            }
        });
    }

    private void createModpackSettings() {
        ClientConfig config = ClientConfig.getInstance();
        // Settings content area starts below tabs
        int startY = dialogY + TAB_HEIGHT + 20;
        int leftColumnX = dialogX + 20;

        enablePlayerOwnSkinSystemCheckbox = Checkbox.builder(
                Component.translatable("quickskin.settings.enable_own_skin"),
                this.font)
                .pos(leftColumnX, startY)
                .selected(config.enablePlayerOwnSkinSystem)
                .tooltip(Tooltip.create(
                        Component.translatable("quickskin.settings.own_skin_tooltip")
                ))
                .build();
        modpackSettingWidgets.add(enablePlayerOwnSkinSystemCheckbox);
    }

    private void switchTab(Tab tab) {
        activeTab = tab;

        // Remove all setting widgets
        for (AbstractWidget widget : guiEditSettingWidgets) {
            this.removeWidget(widget);
        }
        for (AbstractWidget widget : clientSettingWidgets) {
            this.removeWidget(widget);
        }
        for (AbstractWidget widget : serverSettingWidgets) {
            this.removeWidget(widget);
        }
        for (AbstractWidget widget : modpackSettingWidgets) {
            this.removeWidget(widget);
        }

        // Add widgets for active tab
        List<AbstractWidget> activeWidgets = switch (tab) {
            case GUI_EDIT -> guiEditSettingWidgets;
            case CLIENT -> clientSettingWidgets;
            case SERVER -> serverSettingWidgets;
            case MODPACK -> modpackSettingWidgets;
        };
        for (AbstractWidget widget : activeWidgets) {
            this.addRenderableWidget(widget);
        }

        // Update tab button selected states (visual feedback)
        guiEditTabButton.setSelected(tab == Tab.GUI_EDIT);
        clientTabButton.setSelected(tab == Tab.CLIENT);
        serverTabButton.setSelected(tab == Tab.SERVER);
        modpackTabButton.setSelected(tab == Tab.MODPACK);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Render parent screen in background (with mouse coords outside screen to prevent interaction)
        if (this.parent != null) {
            this.parent.render(graphics, -1, -1, partialTick);
        }

        // Flush 3D content (including PlayerWidget) to framebuffer, then clear depth buffer
        // so the blur/overlay/modal panels render on top of everything
        graphics.flush();
        com.mojang.blaze3d.systems.RenderSystem.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT);
        BlurHandler.renderBlur();

        // Update keybind button text before rendering
        if (this.selectedKey == KeybindRegistry.OPEN_SKIN_MENU) {
            this.keybindButton.setMessage(Component.literal("> ").append(Component.literal("???").withStyle(ChatFormatting.YELLOW)).append(" <"));
        } else {
            this.keybindButton.setMessage(KeybindRegistry.OPEN_SKIN_MENU.getTranslatedKeyMessage());
        }

        // Draw darker overlay over entire screen
        graphics.fill(0, 0, this.width, this.height, 0x60000000);

        // Calculate content panel area (below tabs)
        int contentPanelY = dialogY + TAB_HEIGHT;
        int contentPanelHeight = dialogHeight - TAB_HEIGHT;

        // Draw main content panel background with frosted glass styling
        graphics.fill(dialogX, contentPanelY,
                dialogX + dialogWidth,
                contentPanelY + contentPanelHeight,
                PANEL_BG);

        // Draw outline around content panel
        drawPanelOutline(graphics, dialogX, contentPanelY, dialogWidth, contentPanelHeight, PANEL_OUTLINE);

        // Render widgets (buttons, tabs, etc.) - this ensures they render AFTER everything above
        super.render(graphics, mouseX, mouseY, partialTick);

        // Render "Admin-Only" notice for Server tab if not admin (render last to ensure it's on top)
        if (activeTab == Tab.SERVER && minecraft != null) {
            boolean isAdmin = minecraft.player != null && minecraft.player.hasPermissions(2);

            if (!isAdmin) {
                int noticeY = dialogY + dialogHeight - 55;
                Component notice = Component.translatable("quickskin.settings.server_notice");
                int noticeWidth = this.font.width(notice);
                graphics.drawString(this.font, notice, dialogX + (dialogWidth - noticeWidth) / 2, noticeY, 0xFFCC00, false);
            }
        }
    }

    /**
     * Draws outline around the specified rectangular area
     */
    private void drawPanelOutline(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        // Top
        graphics.fill(x, y, x + width, y + 1, color);
        // Bottom
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        // Left
        graphics.fill(x, y, x + 1, y + height, color);
        // Right
        graphics.fill(x + width - 1, y, x + width, y + height, color);
    }

    /**
     * Recreates a screen to apply new styling settings
     */
    private Screen recreateScreen(Screen screen) {
        if (screen instanceof PlayerSkinMenuScreen) {
            return new PlayerSkinMenuScreen(null);
        } else if (screen instanceof PlayerCapeMenuScreen) {
            return new PlayerCapeMenuScreen(null);
        }
        // For other screens, return the original
        return screen;
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
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        // Allow scrolling only within modal
        if (mouseX >= dialogX && mouseX <= dialogX + dialogWidth &&
                mouseY >= dialogY && mouseY <= dialogY + dialogHeight) {
            return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
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

    /**
     * Temporarily hide/show PlayerWidget instances in the parent screen
     * to prevent the 3D player model from rendering on top of the modal.
     */
    private void hidePlayerWidgets(boolean hide) {
        if (this.parent == null) return;
        for (var child : this.parent.children()) {
            if (child instanceof com.quickskin.mod.client.gui.widget.PlayerWidget pw) {
                pw.visible = !hide;
            }
        }
    }

    @Override
    public void renderBackground(net.minecraft.client.gui.GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Disable the default background (panorama) - we render the parent screen's background manually
    }

    @Override
    protected void renderBlurredBackground() {
        // Disable the default Minecraft blur effect - we handle blur manually
    }

    @Override
    public void removed() {
        super.removed();
        // Cleanup blur resources
        BlurHandler.cleanup();

        // If a reload was flagged, execute it now that the screen is closed.
        if (this.needsTextureReload) {
            com.quickskin.mod.client.services.PlayerAppearanceService.getInstance().reloadSkinsForTransparencyChange();
        }
    }

    @Override
    public void onClose() {
        // If we were editing a keybind, cancel it and save changes
        if(this.selectedKey != null) {
            KeyMapping.resetMapping();
            this.selectedKey = null;
        }

        // Execute any pending transparency reload from server config changes
        com.quickskin.mod.networking.ClientNetworkHandler.executePendingTransparencyReload();

        // Save client settings
        if (showOverlayCheckbox != null) {
            ClientConfig config = ClientConfig.getInstance();

            // Check if transparency setting changed
            boolean oldTransparencySetting = config.disableSkinTransparency;
            boolean oldStyledButtonsSetting = config.enableStyledButtons;

            config.showSkinPreviewOverlay = showOverlayCheckbox.selected();
            config.disableSkinTransparency = disableSkinTransparencyCheckbox.selected();
            config.enableStyledButtons = enableStyledButtonsCheckbox.selected();
            config.enablePlayerPreviewCustomization = enablePlayerPreviewCustomizationCheckbox.selected();
            config.enablePlayerOwnSkinSystem = enablePlayerOwnSkinSystemCheckbox.selected();
            config.hideBuiltInCapes = hideBuiltInCapesCheckbox.selected();

            // Save menu background style
            if (menuBackgroundCheckbox != null) {
                BackgroundStyle newStyle = menuBackgroundCheckbox.selected() ?
                    BackgroundStyle.VANILLA_BLUR : BackgroundStyle.OPAQUE_STARS;
                config.setMenuBackgroundStyle(newStyle);
            }

            // If transparency setting changed, flag for a reload
            if (oldTransparencySetting != config.disableSkinTransparency) {
                this.needsTextureReload = true;
            }

            config.save();

            // If styled buttons setting changed, refresh parent screen
            if (oldStyledButtonsSetting != config.enableStyledButtons && parent != null && minecraft != null) {
                // Recreate parent screen to apply new button style
                Screen newParent = recreateScreen(parent);
                minecraft.setScreen(newParent);
                return; // Don't set screen to parent again below
            }
        }

        // Save server settings (only if player is admin)
        if (serverDisableSkinTransparencyCheckbox != null) {
            boolean isAdmin = minecraft != null && minecraft.player != null && minecraft.player.hasPermissions(2);

            if (isAdmin) {
                boolean newValue = serverDisableSkinTransparencyCheckbox.selected();

                // Get old value from server override (synced from server)
                ServerConfig serverOverride = ClientConfig.getInstance().getServerOverride();
                boolean oldValue = serverOverride != null ? serverOverride.disableSkinTransparency : false;

                if (newValue != oldValue) {
                    // Send packet to server to update the server-side config
                    UpdateServerConfigPayload payload = new UpdateServerConfigPayload("disableSkinTransparency", newValue);
                    NetworkManager.sendToServer(payload);

                    // The server will broadcast the change to all clients, including this one
                    // No need to save locally or reload textures here - it will happen when we receive the broadcast
                }
            }

            // Save cooldown setting (if admin)
            if (isAdmin && skinChangeCooldownEditBox != null && !skinChangeCooldownEditBox.getValue().isEmpty()) {
                try {
                    int cooldownSeconds = Integer.parseInt(skinChangeCooldownEditBox.getValue());
                    if (cooldownSeconds >= 0 && cooldownSeconds <= 86400) { // Max 24 hours
                        ServerConfig config = ServerConfig.getInstance();
                        config.skinChangeCooldownSeconds = cooldownSeconds;
                        config.save();
                    }
                } catch (NumberFormatException e) {
                    // Invalid input, keep existing value
                }
            }
        }

        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }
}