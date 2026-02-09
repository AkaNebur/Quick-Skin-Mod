package com.quickskin.mod.client.compat;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.platform.PlatformHelper;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;

/**
 * Compatibility integration for Essential mod.
 *
 * Essential includes its own player model rendering on the title screen and pause menu.
 * When both QuickSkin and Essential are installed, this integration:
 * 1. Hides QuickSkin's PlayerWidget, rotate button, and animation buttons from vanilla menus
 * 2. Positions the "Change Skin" button below Essential's button cluster instead
 */
@Environment(EnvType.CLIENT)
public class EssentialCompatIntegration {
    private static boolean MOD_AVAILABLE = false;
    private static boolean CHECKED = false;

    /**
     * Checks if Essential mod is installed.
     */
    public static boolean isAvailable() {
        if (!CHECKED) {
            checkAvailability();
        }
        return MOD_AVAILABLE;
    }

    private static void checkAvailability() {
        CHECKED = true;

        if (PlatformHelper.isModLoaded("essential")) {
            MOD_AVAILABLE = true;
            QuickSkin.LOGGER.debug("[Essential Compat] Detected Essential mod, enabling compatibility layer");
            return;
        }

        // Fallback class-based detection
        try {
            Class.forName("gg.essential.Essential");
            MOD_AVAILABLE = true;
            QuickSkin.LOGGER.debug("[Essential Compat] Detected Essential via class loading, enabling compatibility layer");
        } catch (ClassNotFoundException e) {
            QuickSkin.LOGGER.debug("[Essential Compat] Essential not detected");
        }
    }

    /**
     * Finds the bottom-most Essential widget on the given screen.
     * Scans screen.children() for widgets from the gg.essential package
     * and returns the one with the highest (y + height) value.
     *
     * @param screen The screen to scan
     * @return The bottom-most Essential widget, or null if none found
     */
    public static GuiEventListener findBottomEssentialWidget(Screen screen) {
        if (screen == null) {
            return null;
        }

        GuiEventListener bottomWidget = null;
        int maxBottom = -1;

        for (GuiEventListener listener : screen.children()) {
            String className = listener.getClass().getName();
            if (className.startsWith("gg.essential")) {
                if (listener instanceof net.minecraft.client.gui.components.AbstractWidget widget) {
                    int bottom = widget.getY() + widget.getHeight();
                    if (bottom > maxBottom) {
                        maxBottom = bottom;
                        bottomWidget = listener;
                    }
                }
            }
        }

        if (bottomWidget != null) {
            QuickSkin.LOGGER.debug("[Essential Compat] Found bottom Essential widget: {} at bottom y={}",
                    bottomWidget.getClass().getSimpleName(), maxBottom);
        }

        return bottomWidget;
    }
}
