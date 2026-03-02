package com.quickskin.mod.client.compat;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.platform.PlatformHelper;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.HttpTexture;
import net.minecraft.resources.ResourceLocation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final Logger CPMLOG = LoggerFactory.getLogger("QuickSkin-CPM");
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

        if (PlatformHelper.isModLoaded("cpm")) {
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
            // CPM not detected
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
    private static long lastDeferLog = 0;

    public static boolean shouldDeferToCPM() {
        if (!isAvailable()) {
            return false;
        }
        // Only defer when CPM's editor/GUI is open.
        // We must NOT defer during normal rendering -- isCPMRenderingCustomModel() returns
        // true whenever CPM has loaded ANY model (including the Mojang default), which creates
        // a chicken-and-egg problem: we need getSkinLocation to return our texture so CPM reads
        // it, but CPM is "rendering" so we skip our override, so CPM never sees our texture.
        return isCPMScreenOpen();
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
     * Forces re-registration of skin textures for a player.
     * This clears CPM's model cache AND resets PlayerInfo's pending textures flag,
     * causing registerSkins() to be called again on the next getSkinLocation() call.
     * The re-registration flows through MixinSkinManager, which provides the
     * HttpTexture bridge with the new skin file, so CPM gets the updated skin data.
     */
    public static void forceReRegisterSkins(java.util.UUID playerId) {
        CPMLOG.info("forceReRegisterSkins called for {}", playerId);
        // Always clear CPM model cache
        invalidatePlayerCache();

        if (!isAvailable()) return;

        // Reset PlayerInfo.pendingTextures so registerSkins() fires again
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.connection != null) {
            net.minecraft.client.multiplayer.PlayerInfo playerInfo =
                    mc.player.connection.getPlayerInfo(playerId);
            CPMLOG.info("forceReRegisterSkins playerInfo={}", playerInfo != null);
            if (playerInfo != null) {
                ((QuickSkinPlayerInfoAccess) playerInfo)
                        .quickskin$forceReRegisterSkins();
            }
        } else {
            CPMLOG.info("forceReRegisterSkins SKIPPED: player={} connection={}",
                    mc.player != null, mc.player != null ? mc.player.connection != null : "N/A");
        }
    }

    // Cache of HttpTexture-backed ResourceLocations for CPM compatibility
    private static final Map<String, ResourceLocation> httpTextureCache = new ConcurrentHashMap<>();

    /**
     * Gets or creates an HttpTexture-backed ResourceLocation for a local skin hash.
     * CPM's skin loading pipeline checks {@code instanceof HttpTexture} and reads from
     * the file field to extract embedded 3D model data from the skin PNG.
     *
     * @param hash the local skin content hash
     * @return HttpTexture-backed ResourceLocation, or null if the file is not found
     */
    public static ResourceLocation getOrRegisterHttpTexture(String hash) {
        if (hash == null || hash.isEmpty()) return null;

        // Check cache first
        ResourceLocation cached = httpTextureCache.get(hash);
        if (cached != null) {
            if (Minecraft.getInstance().getTextureManager().getTexture(cached, null) != null) {
                return cached;
            }
            CPMLOG.info("getOrRegisterHttpTexture: cache STALE for {}", hash);
            httpTextureCache.remove(hash);
        }

        // Find the skin file on disk (local skins)
        Path sourcePath = LocalAssetManager.getInstance().getSourcePath(hash);
        CPMLOG.info("getOrRegisterHttpTexture: localPath={} exists={}",
                sourcePath, sourcePath != null && sourcePath.toFile().exists());

        // Fallback: network-received textures stored in memory -- write to a temp file for CPM
        if (sourcePath == null || !sourcePath.toFile().exists()) {
            sourcePath = com.quickskin.mod.client.storage.NetworkTextureCache.getInstance()
                    .getOrCreateTempFile(hash);
            CPMLOG.info("getOrRegisterHttpTexture: networkTempFile={} exists={}",
                    sourcePath, sourcePath != null && sourcePath.toFile().exists());
        }

        if (sourcePath == null || !sourcePath.toFile().exists()) {
            CPMLOG.info("getOrRegisterHttpTexture: FAILED, no file for hash={}", hash);
            return null;
        }

        File skinFile = sourcePath.toFile();
        ResourceLocation location = new ResourceLocation(QuickSkin.MOD_ID, "cpm_bridge/" + hash);

        HttpTexture httpTexture = new HttpTexture(
                skinFile,
                "file:///" + skinFile.getAbsolutePath().replace('\\', '/'),
                new ResourceLocation("textures/entity/player/wide/steve.png"),
                true,
                () -> {}
        );

        Minecraft.getInstance().getTextureManager().register(location, httpTexture);
        httpTextureCache.put(hash, location);

        CPMLOG.info("getOrRegisterHttpTexture: REGISTERED {} file={}", location, skinFile.getAbsolutePath());
        return location;
    }

    /**
     * Evicts a hash from the HttpTexture cache so the next call to
     * {@link #getOrRegisterHttpTexture} creates a fresh HttpTexture.
     */
    public static void evictHttpTextureCache(String hash) {
        if (hash != null) {
            ResourceLocation old = httpTextureCache.remove(hash);
            if (old != null) {
                Minecraft.getInstance().getTextureManager().release(old);
            }
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
