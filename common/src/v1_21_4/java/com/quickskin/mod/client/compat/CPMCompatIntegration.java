package com.quickskin.mod.client.compat;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.platform.PlatformHelper;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Compatibility integration for CustomPlayerModels (CPM) mod.
 *
 * When CPM is actively processing a player (rendering a custom model, or its
 * editor/GUI is open), QuickSkin defers entirely (skin, model, cape) to avoid
 * breaking CPM's model binding and texture reading pipeline.
 * When CPM has no active involvement, QuickSkin works normally.
 *
 * Uses reflection to avoid compile-time dependency on CPM.
 */
@Environment(EnvType.CLIENT)
public class CPMCompatIntegration {
    private static boolean MOD_AVAILABLE = false;
    private static boolean CHECKED = false;
    private static boolean INIT_FAILED = false;

    // Reflection cached references
    private static Object managerInstance;          // RenderManager
    private static Method getBoundPlayerMethod;     // RenderManager.getBoundPlayer()
    private static Method getModelDefinitionMethod; // Player.getModelDefinition()
    private static Method doRenderMethod;           // ModelDefinition.doRender()
    private static Object loaderInstance;            // ModelDefinitionLoader
    private static Method clearCacheMethod;          // ModelDefinitionLoader.clearCache()

    /**
     * Checks if CPM is installed.
     */
    public static boolean isAvailable() {
        if (!CHECKED) {
            checkAvailability();
        }
        return MOD_AVAILABLE;
    }

    private static void checkAvailability() {
        CHECKED = true;

        boolean modLoaded = PlatformHelper.isModLoaded("cpm");

        if (modLoaded) {
            MOD_AVAILABLE = true;
            initializeReflection();
            return;
        }

        // Fallback class-based detection
        try {
            Class.forName("com.tom.cpm.client.CustomPlayerModelsClient");
            MOD_AVAILABLE = true;
            initializeReflection();
        } catch (ClassNotFoundException e) {
        }
    }

    private static void initializeReflection() {
        try {
            // 1. Get CustomPlayerModelsClient.INSTANCE
            Class<?> clientClass = Class.forName("com.tom.cpm.client.CustomPlayerModelsClient");
            Field instanceField = clientClass.getField("INSTANCE");
            Object clientInstance = instanceField.get(null);
            if (clientInstance == null) {
                INIT_FAILED = true;
                return;
            }

            // 2. Get 'manager' field from superclass (ClientBase)
            Field managerField = null;
            Class<?> currentClass = clientInstance.getClass();
            while (currentClass != null) {
                try {
                    managerField = currentClass.getDeclaredField("manager");
                    break;
                } catch (NoSuchFieldException e) {
                    currentClass = currentClass.getSuperclass();
                }
            }
            if (managerField == null) {
                INIT_FAILED = true;
                return;
            }
            managerField.setAccessible(true);
            managerInstance = managerField.get(clientInstance);
            if (managerInstance == null) {
                INIT_FAILED = true;
                return;
            }

            // 3. Cache getBoundPlayer() method on RenderManager
            getBoundPlayerMethod = managerInstance.getClass().getMethod("getBoundPlayer");

            // 4. Get the 'loader' field (ModelDefinitionLoader) from RenderManager
            try {
                Field loaderField = managerInstance.getClass().getDeclaredField("loader");
                loaderField.setAccessible(true);
                loaderInstance = loaderField.get(managerInstance);
                if (loaderInstance != null) {
                    clearCacheMethod = loaderInstance.getClass().getMethod("clearCache");
                }
            } catch (Exception e) {
            }

        } catch (Exception e) {
            INIT_FAILED = true;
        }
    }

    /**
     * Primary check: should QuickSkin defer to CPM right now?
     * Returns true when CPM's GUI is open or CPM is actively rendering a custom model.
     */
    public static boolean shouldDeferToCPM() {
        if (!isAvailable()) {
            return false;
        }
        return isCPMScreenOpen() || isCPMRenderingCustomModel();
    }

    /**
     * Returns true if a CPM screen/GUI is currently open.
     * CPM's editor and other screens need full control over skin rendering.
     */
    private static boolean isCPMScreenOpen() {
        try {
            net.minecraft.client.gui.screens.Screen screen = Minecraft.getInstance().screen;
            if (screen == null) {
                return false;
            }
            String className = screen.getClass().getName();
            return className.startsWith("com.tom.cpm");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Invalidates CPM's model cache so it re-reads skin data on next render.
     * Called when QuickSkin changes the active skin for a player.
     */
    public static void invalidatePlayerCache() {
        if (!isAvailable() || INIT_FAILED) return;
        if (loaderInstance == null || clearCacheMethod == null) return;

        try {
            clearCacheMethod.invoke(loaderInstance);
        } catch (Exception e) {
        }
    }

    /**
     * Returns true if CPM has a bound player during rendering.
     * Used by ItemInHandRendererMixin to avoid overriding CPM's texture in the RenderType
     * during first-person arm rendering.
     */
    public static boolean isCPMActivelyRendering() {
        if (!isAvailable() || INIT_FAILED || managerInstance == null || getBoundPlayerMethod == null) {
            return false;
        }
        try {
            Object boundPlayer = getBoundPlayerMethod.invoke(managerInstance);
            return boundPlayer != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns true if CPM is currently rendering a custom model for a player.
     */
    private static boolean isCPMRenderingCustomModel() {
        if (INIT_FAILED || managerInstance == null || getBoundPlayerMethod == null) {
            return false;
        }

        try {
            // 1. Call getBoundPlayer() on the RenderManager
            Object boundPlayer = getBoundPlayerMethod.invoke(managerInstance);
            if (boundPlayer == null) {
                return false;
            }

            // 2. Call getModelDefinition() on the Player
            if (getModelDefinitionMethod == null) {
                getModelDefinitionMethod = boundPlayer.getClass().getMethod("getModelDefinition");
            }
            Object modelDefinition = getModelDefinitionMethod.invoke(boundPlayer);
            if (modelDefinition == null) {
                return false;
            }

            // 3. Call doRender() on the ModelDefinition
            if (doRenderMethod == null) {
                doRenderMethod = modelDefinition.getClass().getMethod("doRender");
            }
            return (boolean) doRenderMethod.invoke(modelDefinition);
        } catch (Exception e) {
            // Silently fail - don't spam logs during rendering
            return false;
        }
    }
}
