package com.quickskin.mod.client.gui.screen;

import com.quickskin.mod.client.gui.overlay.SkinPreviewOverlay;
import com.quickskin.mod.config.ClientConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * Settings screen for QuickSkin configuration
 */
@Environment(EnvType.CLIENT)
public class SettingsScreen extends Screen {

    @Nullable
    private final Screen parent;

    public SettingsScreen(@Nullable Screen parent) {
        super(Component.literal("QuickSkin Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        ClientConfig config = ClientConfig.getInstance();
        int centerX = this.width / 2;
        int startY = 40;
        int buttonWidth = 310;
        int buttonHeight = 20;
        int spacing = 25;
        int currentY = startY;

        // HUD Overlay Settings Section
        this.addRenderableWidget(CycleButton.onOffBuilder(config.showSkinPreviewOverlay)
            .create(centerX - buttonWidth / 2, currentY, buttonWidth, buttonHeight,
                Component.literal("Show HUD Overlay"),
                (button, value) -> {
                    config.showSkinPreviewOverlay = value;
                    config.save();
                }));
        currentY += spacing;

        // Overlay Position
        this.addRenderableWidget(CycleButton.<SkinPreviewOverlay.OverlayPosition>builder(
                pos -> Component.literal("Position: " + pos.name()))
            .withValues(SkinPreviewOverlay.OverlayPosition.values())
            .withInitialValue(SkinPreviewOverlay.OverlayPosition.valueOf(config.overlayPosition))
            .create(centerX - buttonWidth / 2, currentY, buttonWidth, buttonHeight,
                Component.literal("Overlay Position"),
                (button, value) -> {
                    config.overlayPosition = value.name();
                    SkinPreviewOverlay.setPosition(value);
                    config.save();
                }));
        currentY += spacing;

        // Animation Settings
        this.addRenderableWidget(CycleButton.onOffBuilder(config.enableAnimations)
            .create(centerX - buttonWidth / 2, currentY, buttonWidth, buttonHeight,
                Component.literal("Enable Animations"),
                (button, value) -> {
                    config.enableAnimations = value;
                    config.save();
                }));
        currentY += spacing;

        this.addRenderableWidget(CycleButton.onOffBuilder(config.enableIdleAnimation)
            .create(centerX - buttonWidth / 2, currentY, buttonWidth, buttonHeight,
                Component.literal("Enable Idle Animation"),
                (button, value) -> {
                    config.enableIdleAnimation = value;
                    config.save();
                }));
        currentY += spacing;

        this.addRenderableWidget(CycleButton.onOffBuilder(config.autoRotatePreview)
            .create(centerX - buttonWidth / 2, currentY, buttonWidth, buttonHeight,
                Component.literal("Auto-Rotate Preview"),
                (button, value) -> {
                    config.autoRotatePreview = value;
                    config.save();
                }));
        currentY += spacing;

        // Performance Settings
        this.addRenderableWidget(CycleButton.onOffBuilder(config.cacheTextures)
            .create(centerX - buttonWidth / 2, currentY, buttonWidth, buttonHeight,
                Component.literal("Cache Textures"),
                (button, value) -> {
                    config.cacheTextures = value;
                    config.save();
                }));
        currentY += spacing;

        this.addRenderableWidget(CycleButton.onOffBuilder(config.enableHDSkins)
            .create(centerX - buttonWidth / 2, currentY, buttonWidth, buttonHeight,
                Component.literal("Enable HD Skins"),
                (button, value) -> {
                    config.enableHDSkins = value;
                    config.save();
                }));
        currentY += spacing;

        // Network Settings
        this.addRenderableWidget(CycleButton.onOffBuilder(config.autoSyncSkins)
            .create(centerX - buttonWidth / 2, currentY, buttonWidth, buttonHeight,
                Component.literal("Auto-Sync Skins"),
                (button, value) -> {
                    config.autoSyncSkins = value;
                    config.save();
                }));
        currentY += spacing;

        // Compatibility Settings
        this.addRenderableWidget(CycleButton.onOffBuilder(config.skinLayers3DCompat)
            .create(centerX - buttonWidth / 2, currentY, buttonWidth, buttonHeight,
                Component.literal("SkinLayers3D Compatibility"),
                (button, value) -> {
                    config.skinLayers3DCompat = value;
                    config.save();
                }));
        currentY += spacing;

        // Keybinds
        this.addRenderableWidget(CycleButton.onOffBuilder(config.enableKeybinds)
            .create(centerX - buttonWidth / 2, currentY, buttonWidth, buttonHeight,
                Component.literal("Enable Keybinds"),
                (button, value) -> {
                    config.enableKeybinds = value;
                    config.save();
                }));

        // Close button at bottom
        this.addRenderableWidget(Button.builder(
            Component.literal("Done"),
            button -> this.onClose()
        ).bounds(this.width / 2 - 100, this.height - 30, 200, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Render background
        this.renderBackground(graphics);

        // Render title
        graphics.drawCenteredString(
            this.font,
            this.title,
            this.width / 2,
            15,
            0xFFFFFF
        );

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }
}
