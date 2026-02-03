package com.quickskin.mod.client.gui.util;

import com.quickskin.mod.QuickSkin;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.PanoramaRenderer;

import java.lang.reflect.Field;

/**
 * Utility class for synchronizing panorama time across different screens.
 * Uses a global time source (System.nanoTime) to ensure consistent panorama position.
 */
public class PanoramaTimeSync {

    private static Field titleScreenPanoramaField = null;
    private static Field panoramaTimeField = null;
    private static boolean initialized = false;
    private static boolean initFailed = false;

    /**
     * Gets the global panorama time based on Minecraft's time utilities.
     * This ensures consistent panorama position regardless of which screen is rendering.
     */
    public static float getGlobalPanoramaTime() {
        // Use Minecraft's Util.getMillis() which is what vanilla uses for timing
        // Convert to seconds and modulo to prevent float overflow
        return (Util.getMillis() / 1000.0f) % 10000.0f;
    }

    /**
     * Initialize reflection fields for accessing TitleScreen's panorama.
     */
    private static void initFields() {
        if (initialized || initFailed) return;
        initialized = true;

        try {
            // Find the PanoramaRenderer field in TitleScreen
            for (Field field : TitleScreen.class.getDeclaredFields()) {
                if (PanoramaRenderer.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    titleScreenPanoramaField = field;
                    QuickSkin.LOGGER.debug("Found TitleScreen panorama field: {}", field.getName());
                    break;
                }
            }

            // Find the time field in PanoramaRenderer (first non-static float)
            for (Field field : PanoramaRenderer.class.getDeclaredFields()) {
                if (field.getType() == float.class && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    panoramaTimeField = field;
                    QuickSkin.LOGGER.debug("Found PanoramaRenderer time field: {}", field.getName());
                    break;
                }
            }

            if (titleScreenPanoramaField == null) {
                QuickSkin.LOGGER.debug("TitleScreen panorama field not found - may be static or different in this version");
            }

        } catch (Exception e) {
            initFailed = true;
            QuickSkin.LOGGER.debug("Failed to initialize panorama sync fields", e);
        }
    }

    /**
     * Syncs TitleScreen's panorama time to the global time source.
     * Called from TitleScreenMixin before render.
     */
    public static void syncTitleScreenPanorama() {
        initFields();

        if (panoramaTimeField == null) return;

        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof TitleScreen titleScreen) {
                PanoramaRenderer panorama = null;

                // Try to get the panorama from instance field
                if (titleScreenPanoramaField != null) {
                    panorama = (PanoramaRenderer) titleScreenPanoramaField.get(titleScreen);
                }

                // If no instance field, try static fields
                if (panorama == null) {
                    for (Field field : TitleScreen.class.getDeclaredFields()) {
                        if (PanoramaRenderer.class.isAssignableFrom(field.getType()) &&
                            java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                            field.setAccessible(true);
                            panorama = (PanoramaRenderer) field.get(null);
                            if (panorama != null) break;
                        }
                    }
                }

                if (panorama != null) {
                    panoramaTimeField.setFloat(panorama, getGlobalPanoramaTime());
                }
            }
        } catch (Exception e) {
            // Silently ignore - this is best-effort sync
        }
    }

    /**
     * Sets the time on a PanoramaRenderer instance to the global time.
     */
    public static void syncPanoramaRenderer(PanoramaRenderer renderer) {
        initFields();

        if (panoramaTimeField == null || renderer == null) return;

        try {
            panoramaTimeField.setFloat(renderer, getGlobalPanoramaTime());
        } catch (Exception e) {
            // Silently ignore
        }
    }

    /**
     * Gets the cached panorama time field for direct use.
     */
    public static Field getPanoramaTimeField() {
        initFields();
        return panoramaTimeField;
    }
}
