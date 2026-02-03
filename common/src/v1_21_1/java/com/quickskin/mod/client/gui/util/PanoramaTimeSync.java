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

    // Static panorama renderer that might be used by TitleScreen
    private static PanoramaRenderer staticPanorama = null;
    private static Field staticPanoramaField = null;

    /**
     * Initialize reflection fields for accessing TitleScreen's panorama.
     */
    private static void initFields() {
        if (initialized || initFailed) return;
        initialized = true;

        QuickSkin.LOGGER.info("[PanoramaSync] Initializing panorama sync fields...");

        try {
            // List all TitleScreen fields for debugging
            QuickSkin.LOGGER.info("[PanoramaSync] TitleScreen fields:");
            for (Field field : TitleScreen.class.getDeclaredFields()) {
                boolean isStatic = java.lang.reflect.Modifier.isStatic(field.getModifiers());
                QuickSkin.LOGGER.info("[PanoramaSync]   {} : {} (static: {})",
                    field.getName(), field.getType().getSimpleName(), isStatic);

                // Check for PanoramaRenderer fields (static or instance)
                if (PanoramaRenderer.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    if (isStatic) {
                        staticPanoramaField = field;
                        staticPanorama = (PanoramaRenderer) field.get(null);
                        QuickSkin.LOGGER.info("[PanoramaSync] >>> Found STATIC panorama field: {} value: {}",
                            field.getName(), staticPanorama);
                    } else {
                        titleScreenPanoramaField = field;
                        QuickSkin.LOGGER.info("[PanoramaSync] >>> Found instance panorama field: {}", field.getName());
                    }
                }
            }

            // Also check for RotatingPanoramaRenderer class if it exists
            try {
                Class<?> rotatingClass = Class.forName("net.minecraft.client.renderer.RotatingPanoramaRenderer");
                QuickSkin.LOGGER.info("[PanoramaSync] Found RotatingPanoramaRenderer class!");
                for (Field field : rotatingClass.getDeclaredFields()) {
                    QuickSkin.LOGGER.info("[PanoramaSync]   RotatingPanorama field: {} : {}",
                        field.getName(), field.getType().getSimpleName());
                }
            } catch (ClassNotFoundException e) {
                QuickSkin.LOGGER.info("[PanoramaSync] RotatingPanoramaRenderer class not found (expected in some versions)");
            }

            // List all PanoramaRenderer fields for debugging
            QuickSkin.LOGGER.info("[PanoramaSync] PanoramaRenderer fields:");
            for (Field field : PanoramaRenderer.class.getDeclaredFields()) {
                boolean isStatic = java.lang.reflect.Modifier.isStatic(field.getModifiers());
                QuickSkin.LOGGER.info("[PanoramaSync]   {} : {} (static: {})",
                    field.getName(), field.getType().getSimpleName(), isStatic);

                if (field.getType() == float.class && !isStatic && panoramaTimeField == null) {
                    field.setAccessible(true);
                    panoramaTimeField = field;
                    QuickSkin.LOGGER.info("[PanoramaSync] >>> Using time field: {}", field.getName());
                }
            }

            if (titleScreenPanoramaField == null && staticPanorama == null) {
                QuickSkin.LOGGER.warn("[PanoramaSync] No panorama field found in TitleScreen at all!");
            }

            if (panoramaTimeField == null) {
                QuickSkin.LOGGER.error("[PanoramaSync] No float field found in PanoramaRenderer!");
            }

        } catch (Exception e) {
            initFailed = true;
            QuickSkin.LOGGER.error("[PanoramaSync] Failed to initialize", e);
        }
    }

    private static boolean loggedOnce = false;

    /**
     * Syncs TitleScreen's panorama time to the global time source.
     * Called from TitleScreenMixin before render.
     */
    public static void syncTitleScreenPanorama() {
        initFields();

        if (panoramaTimeField == null) {
            if (!loggedOnce) {
                QuickSkin.LOGGER.warn("[PanoramaSync] panoramaTimeField is null - sync disabled");
                loggedOnce = true;
            }
            return;
        }

        try {
            PanoramaRenderer panorama = null;

            // First try static panorama (cached during init)
            if (staticPanorama != null) {
                panorama = staticPanorama;
            }

            // Then try instance field
            if (panorama == null && titleScreenPanoramaField != null) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.screen instanceof TitleScreen titleScreen) {
                    panorama = (PanoramaRenderer) titleScreenPanoramaField.get(titleScreen);
                }
            }

            if (panorama != null) {
                float time = getGlobalPanoramaTime();
                panoramaTimeField.setFloat(panorama, time);
                if (!loggedOnce) {
                    QuickSkin.LOGGER.info("[PanoramaSync] TitleScreen panorama synced! Time: {}", time);
                    loggedOnce = true;
                }
            } else if (!loggedOnce) {
                QuickSkin.LOGGER.warn("[PanoramaSync] No panorama to sync for TitleScreen");
                loggedOnce = true;
            }
        } catch (Exception e) {
            if (!loggedOnce) {
                QuickSkin.LOGGER.warn("[PanoramaSync] Error syncing TitleScreen panorama", e);
                loggedOnce = true;
            }
        }
    }

    private static boolean loggedQuickSkinOnce = false;

    /**
     * Sets the time on a PanoramaRenderer instance to the global time.
     */
    public static void syncPanoramaRenderer(PanoramaRenderer renderer) {
        initFields();

        if (panoramaTimeField == null || renderer == null) {
            if (!loggedQuickSkinOnce) {
                QuickSkin.LOGGER.warn("[PanoramaSync] Cannot sync QuickSkin panorama - field:{} renderer:{}",
                    panoramaTimeField != null, renderer != null);
                loggedQuickSkinOnce = true;
            }
            return;
        }

        try {
            float time = getGlobalPanoramaTime();
            panoramaTimeField.setFloat(renderer, time);
            if (!loggedQuickSkinOnce) {
                QuickSkin.LOGGER.info("[PanoramaSync] QuickSkin panorama synced! Time: {}", time);
                loggedQuickSkinOnce = true;
            }
        } catch (Exception e) {
            if (!loggedQuickSkinOnce) {
                QuickSkin.LOGGER.warn("[PanoramaSync] Error syncing QuickSkin panorama", e);
                loggedQuickSkinOnce = true;
            }
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
