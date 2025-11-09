package com.quickskin.mod.client.gui.panel;

import com.quickskin.mod.client.gui.widget.LinkButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Panel that manages the link buttons in the top-right corner
 * (Modrinth, CurseForge, Discord, Settings)
 */
public class LinkButtonsPanel extends AbstractWidget {

    private static final int SPACING = 4;
    private static final int BUTTON_SIZE = 20;

    // Icon textures
    private static final ResourceLocation DISCORD_ICON = new ResourceLocation("quickskin", "textures/gui/discord_icon.png");
    private static final ResourceLocation CURSEFORGE_ICON = new ResourceLocation("quickskin", "textures/gui/curseforge_icon.png");
    private static final ResourceLocation MODRINTH_ICON = new ResourceLocation("quickskin", "textures/gui/modrinth_icon.png");
    private static final ResourceLocation SETTINGS_ICON = new ResourceLocation("quickskin", "textures/gui/settings_icon.png");

    // URLs
    private static final String DISCORD_URL = "https://discord.gg/yGxdvA7qej";
    private static final String CURSEFORGE_URL = "https://www.curseforge.com/minecraft/mc-mods/quick-skin";
    private static final String MODRINTH_URL = "https://modrinth.com/mod/quick-skin";

    public LinkButtonsPanel(int x, int y, int width, int height) {
        super(x, y, width, height, Component.empty());
    }

    /**
     * Initialize the panel and create all child widgets
     */
    public void init(com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen screen) {
        int linkButtonY = getY();
        int currentX = getX() + width - BUTTON_SIZE; // Start from right edge

        // Settings button (far right)
        screen.registerWidget(new LinkButton(
            currentX,
            linkButtonY,
            BUTTON_SIZE,
            BUTTON_SIZE,
            SETTINGS_ICON,
            null, // No URL, will be handled differently
            Component.literal("Settings")
        ) {
            @Override
            public void onPress() {
                // Open settings screen
                Minecraft.getInstance().setScreen(
                    new com.quickskin.mod.client.gui.screen.SettingsScreen(screen)
                );
            }
        });

        // Discord button (left of settings)
        currentX -= (BUTTON_SIZE + SPACING);
        screen.registerWidget(new LinkButton(
            currentX,
            linkButtonY,
            BUTTON_SIZE,
            BUTTON_SIZE,
            DISCORD_ICON,
            DISCORD_URL,
            Component.literal("Join our Discord!")
        ));

        // CurseForge button (left of Discord)
        currentX -= (BUTTON_SIZE + SPACING);
        screen.registerWidget(new LinkButton(
            currentX,
            linkButtonY,
            BUTTON_SIZE,
            BUTTON_SIZE,
            CURSEFORGE_ICON,
            CURSEFORGE_URL,
            Component.literal("Visit our CurseForge page")
        ));

        // Modrinth button (left of CurseForge)
        currentX -= (BUTTON_SIZE + SPACING);
        screen.registerWidget(new LinkButton(
            currentX,
            linkButtonY,
            BUTTON_SIZE,
            BUTTON_SIZE,
            MODRINTH_ICON,
            MODRINTH_URL,
            Component.literal("Visit our Modrinth page")
        ));
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
