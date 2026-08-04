package com.quickskin.mod.client.gui.util;

//? if <1.21.11 {
import net.minecraft.Util;
//?} else {
import net.minecraft.util.Util;
//?}
//? if <26.1 {
import net.minecraft.client.renderer.PanoramaRenderer;
//?} else {
import net.minecraft.client.renderer.Panorama;
//?}

import java.lang.reflect.Field;

/**
 * Utility class for synchronizing panorama time across different screens.
 * Uses a global time source to ensure consistent panorama position.
 */
public class PanoramaTimeSync {

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
     * Initialize reflection fields for accessing Panorama's time field.
     */
    private static void initFields() {
        if (initialized || initFailed) return;
        initialized = true;

        try {
            //? if <26.1 {
            for (Field field : PanoramaRenderer.class.getDeclaredFields()) {
            //?} else {
            // Find the time field in Panorama (first non-static float)
            for (Field field : Panorama.class.getDeclaredFields()) {
            //?}
                if (field.getType() == float.class && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    panoramaTimeField = field;
                    break;
                }
            }
        } catch (Exception e) {
            initFailed = true;
        }
    }

    /**
     * Sets the time on a Panorama instance to the global time.
     */
    //? if <26.1 {
    public static void syncPanoramaRenderer(PanoramaRenderer renderer) {
    //?} else {
    public static void syncPanoramaRenderer(Panorama renderer) {
    //?}
        initFields();

        if (panoramaTimeField == null || renderer == null) return;

        try {
            panoramaTimeField.setFloat(renderer, getGlobalPanoramaTime());
        } catch (Exception e) {
            // Silently ignore
        }
    }
}
