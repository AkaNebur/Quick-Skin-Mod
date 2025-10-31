package com.quickskin.mod.client.gui.util;

import com.quickskin.mod.QuickSkin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;

public class GuiScaleManager {
    private static Integer originalGuiScale = null;
    private static boolean scaleChanged = false;

    /**
     * Force set the GUI scale for the QuickSkin menus
     * @param targetScale The desired GUI scale (1-4, where 0 = Auto)
     * @return true if a screen resize was triggered, false otherwise.
     */
    public static boolean setMenuGuiScale(int targetScale) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.options == null) {
                QuickSkin.LOGGER.warn("Cannot change GUI scale: options is null");
                return false;
            }

            OptionInstance<Integer> guiScaleOption = mc.options.guiScale();

            // Store the original scale if we haven't already
            if (originalGuiScale == null) {
                originalGuiScale = guiScaleOption.get();
            }

            // Only change if different from current
            int currentScale = guiScaleOption.get();
            if (currentScale != targetScale) {
                guiScaleOption.set(targetScale);
                scaleChanged = true;

                // Force window to recalculate scaled dimensions
                // This will cause the screen to reinit, which is why the caller should return immediately
                mc.resizeDisplay();

                return true;
            }
        } catch (Exception e) {
            QuickSkin.LOGGER.error("Failed to set menu GUI scale", e);
        }
        return false;
    }

    /**
     * Restore the original GUI scale
     */
    public static void restoreOriginalGuiScale() {
        try {
            if (originalGuiScale == null || !scaleChanged) {
                return; // Nothing to restore
            }

            Minecraft mc = Minecraft.getInstance();
            if (mc.options == null) {
                QuickSkin.LOGGER.warn("Cannot restore GUI scale: options is null");
                return;
            }

            OptionInstance<Integer> guiScaleOption = mc.options.guiScale();
            int currentScale = guiScaleOption.get();

            if (currentScale != originalGuiScale) {
                guiScaleOption.set(originalGuiScale);

                // Force window to recalculate scaled dimensions
                mc.resizeDisplay();
            }

            // Reset tracking variables
            originalGuiScale = null;
            scaleChanged = false;

        } catch (Exception e) {
            QuickSkin.LOGGER.error("Failed to restore original GUI scale", e);
        }
    }

    /**
     * Get the optimal GUI scale for the QuickSkin menu.
     * Returns 3 for consistent layout.
     */
    public static int getOptimalMenuScale() {
        try {
            return 3;
        } catch (Exception e) {
            QuickSkin.LOGGER.error("Failed to calculate optimal menu scale", e);
            return 3; // Safe fallback
        }
    }

    /**
     * Check if we currently have a modified GUI scale
     */
    public static boolean hasModifiedScale() {
        return originalGuiScale != null && scaleChanged;
    }

    /**
     * Get the current original scale (before any changes)
     */
    public static Integer getOriginalScale() {
        return originalGuiScale;
    }
}
