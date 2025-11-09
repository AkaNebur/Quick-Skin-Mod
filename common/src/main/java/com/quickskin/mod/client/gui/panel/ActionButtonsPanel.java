package com.quickskin.mod.client.gui.panel;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * Panel that manages the action buttons at the bottom of the screen
 * (Import, HD Skin Website, Skin Website, Cape, Done)
 */
public class ActionButtonsPanel extends AbstractWidget {

    private static final int SPACING = 4;
    private static final int COMPONENT_HEIGHT = 20;

    /**
     * Callbacks for button actions
     */
    public record ActionCallbacks(
        Runnable onImport,
        Runnable onHdSkinWebsite,
        Runnable onSkinWebsite,
        Runnable onCape,
        Runnable onDone
    ) {}

    public ActionButtonsPanel(int x, int y, int width, int height, ActionCallbacks callbacks) {
        super(x, y, width, height, Component.empty());
    }

    /**
     * Initialize the panel and create all child widgets
     */
    public void init(com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen screen, ActionCallbacks callbacks) {
        int fullWidthX = getX();
        int fullComponentWidth = width;
        int bottomY = getY() + height;

        // Row 1 (Bottom-most): Done Button (full width)
        bottomY -= COMPONENT_HEIGHT;
        Button doneButton = Button.builder(Component.literal("Done"), button -> callbacks.onDone.run())
            .bounds(fullWidthX, bottomY, fullComponentWidth, COMPONENT_HEIGHT)
            .build();
        screen.registerWidget(doneButton);

        // Row 2: Import, HD Skin, Skin, Cape Buttons (4 equal width buttons)
        bottomY -= (COMPONENT_HEIGHT + SPACING);
        int fourButtonWidth = (fullComponentWidth - (SPACING * 3)) / 4;

        Button importButton = Button.builder(Component.literal("Import Skin"), button -> callbacks.onImport.run())
            .bounds(fullWidthX, bottomY, fourButtonWidth, COMPONENT_HEIGHT).build();
        screen.registerWidget(importButton);

        Button hdSkinWebsiteButton = Button.builder(Component.literal("HD Skin Website"), button -> callbacks.onHdSkinWebsite.run())
            .bounds(fullWidthX + fourButtonWidth + SPACING, bottomY, fourButtonWidth, COMPONENT_HEIGHT).build();
        screen.registerWidget(hdSkinWebsiteButton);

        Button skinWebsiteButton = Button.builder(Component.literal("Skin Website"), button -> callbacks.onSkinWebsite.run())
            .bounds(fullWidthX + (fourButtonWidth + SPACING) * 2, bottomY, fourButtonWidth, COMPONENT_HEIGHT).build();
        screen.registerWidget(skinWebsiteButton);

        Button capeButton = Button.builder(Component.literal("Cape"), button -> callbacks.onCape.run())
            .bounds(fullWidthX + (fourButtonWidth + SPACING) * 3, bottomY, fourButtonWidth, COMPONENT_HEIGHT).build();
        screen.registerWidget(capeButton);
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // This panel doesn't render anything itself - child widgets handle rendering
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        // No narration needed for container panel
    }
}
