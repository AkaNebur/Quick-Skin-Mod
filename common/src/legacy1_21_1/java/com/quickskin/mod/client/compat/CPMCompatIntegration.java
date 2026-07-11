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

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
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
     * Returns true when CPM's editor/GUI is open.
     * We must NOT defer during normal rendering -- isCPMRenderingCustomModel() returns
     * true whenever CPM has loaded ANY model (including the Mojang default), which creates
     * a chicken-and-egg problem: we need getSkin to return our texture so CPM reads it,
     * but CPM is "rendering" so we skip our override, so CPM never sees our texture.
     */
    public static boolean shouldDeferToCPM() {
        if (!isAvailable()) {
            return false;
        }
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
        if (loaderInstance == null || clearCacheMethod == null) {
            CPMLOG.warn("invalidatePlayerCache: loader={} clearCache={}", loaderInstance != null, clearCacheMethod != null);
            return;
        }

        try {
            clearCacheMethod.invoke(loaderInstance);
            CPMLOG.info("invalidatePlayerCache: cleared CPM model definition cache");
        } catch (Exception e) {
            CPMLOG.warn("invalidatePlayerCache: failed", e);
        }
    }

    /**
     * Forces re-registration of skin textures for a player.
     * This clears CPM's model cache AND resets CPM to skin mode for the local player,
     * so CPM reads model data from the skin PNG texture instead of using an explicitly
     * selected .cpmmodel file. The SkinManager mixin provides the new skin texture
     * when CPM re-fetches through getOrLoad().
     */
    public static void forceReRegisterSkins(java.util.UUID playerId) {
        CPMLOG.info("forceReRegisterSkins called for {}", playerId);

        // For the local player: reset CPM to skin mode so it reads from the
        // new skin texture instead of keeping an explicitly selected model.
        // Use mc.player.getUUID() (the in-game UUID) as primary check because on
        // offline-mode servers the in-game UUID differs from mc.getUser().getProfileId().
        java.util.UUID localUuid = null;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.player != null) {
                localUuid = mc.player.getUUID();
            }
            if (localUuid == null && mc != null && mc.getUser() != null) {
                localUuid = mc.getUser().getProfileId();
            }
        } catch (Exception e) {
            // ignore
        }
        if (localUuid != null && localUuid.equals(playerId)) {
            resetToSkinMode();
        }

        // Clear CPM's model definition cache so it re-creates the Player object
        // and calls initTextures() again, which goes through getOrLoad().
        invalidatePlayerCache();
    }

    /**
     * Resets CPM to "skin mode" by clearing the explicitly selected model.
     * In skin mode, CPM reads the 3D model from the skin PNG texture instead
     * of using a .cpmmodel file selected via CPM's menu.
     * Also notifies the server if CPM's network protocol is active.
     */
    private static void resetToSkinMode() {
        if (!isAvailable()) return;

        try {
            // ModConfig.getCommonConfig() -> ConfigEntry
            Class<?> modConfigClass = Class.forName("com.tom.cpm.shared.config.ModConfig");
            Method getCommonConfig = modConfigClass.getMethod("getCommonConfig");
            Object config = getCommonConfig.invoke(null);
            if (config == null) return;

            // Check if a model is currently selected
            Method getString = config.getClass().getMethod("getString", String.class, String.class);
            String currentModel = (String) getString.invoke(config, "selectedModel", null);
            if (currentModel == null) {
                CPMLOG.info("resetToSkinMode: already in skin mode");
                return;
            }

            CPMLOG.info("resetToSkinMode: clearing selectedModel={}", currentModel);

            // ConfigEntry.clearValue("selectedModel")
            Method clearValue = config.getClass().getMethod("clearValue", String.class);
            clearValue.invoke(config, "selectedModel");

            // ModConfigFile.save()
            Method save = config.getClass().getMethod("save");
            save.invoke(config);

            // Notify the server if CPM's network is active
            Class<?> mcaClass = Class.forName("com.tom.cpm.shared.MinecraftClientAccess");
            Method mcaGet = mcaClass.getMethod("get");
            Object mca = mcaGet.invoke(null);
            if (mca != null) {
                Method getServerSideStatus = mcaClass.getMethod("getServerSideStatus");
                Object status = getServerSideStatus.invoke(mca);
                if (status != null && "INSTALLED".equals(status.toString())) {
                    Method sendSkinUpdate = mcaClass.getMethod("sendSkinUpdate");
                    sendSkinUpdate.invoke(mca);
                    CPMLOG.info("resetToSkinMode: sent skin update to server");
                }
            }
        } catch (Exception e) {
            CPMLOG.warn("resetToSkinMode: failed", e);
        }
    }

    // Cache of HttpTexture-backed ResourceLocations for CPM compatibility
    private static final Map<String, ResourceLocation> httpTextureCache = new ConcurrentHashMap<>();

    /**
     * Gets or creates an HttpTexture-backed ResourceLocation for a local skin hash.
     * CPM's skin loading pipeline (1.21+) checks {@code instanceof HttpTexture} on the
     * texture registered at the PlayerSkin's ResourceLocation and reads the file/url fields
     * to extract embedded 3D model data from the skin PNG.
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
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(
                QuickSkin.MOD_ID, "cpm_bridge/" + hash);

        HttpTexture httpTexture = new HttpTexture(
                skinFile,
                "file:///" + skinFile.getAbsolutePath().replace('\\', '/'),
                ResourceLocation.withDefaultNamespace("textures/entity/player/wide/steve.png"),
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
     * Returns true if CPM has a bound player during rendering.
     * Used by ItemInHandRendererMixin to avoid overriding CPM's texture in the RenderType
     * during first-person arm rendering. CPM already handles entitySolid→entityTranslucent
     * conversion when bound, and QuickSkin querying the texture independently would produce
     * a different ResourceLocation (quickskin:skins/hash vs cpm:cpm_X), causing texture artifacts.
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

    /**
     * Returns true if the local player currently has a CPM custom model active.
     * Works for both .cpmmodel files and skins with embedded CPM data.
     * Queries CPM's loader cache for the client player's model definition.
     */
    private static long lastWearingLog = 0;

    public static boolean isLocalPlayerWearingCpmModel() {
        if (!isAvailable() || INIT_FAILED || loaderInstance == null) return false;
        try {
            Method getPlayers = loaderInstance.getClass().getMethod("getPlayers");
            java.util.List<?> players = (java.util.List<?>) getPlayers.invoke(loaderInstance);

            boolean shouldLog = System.currentTimeMillis() - lastWearingLog > 3000;

            if (shouldLog && !players.isEmpty()) {
                lastWearingLog = System.currentTimeMillis();
                CPMLOG.info("isLocalPlayerWearingCpmModel: {} player(s) in cache", players.size());
            }

            for (Object player : players) {
                // Use getUUID + compare instead of isClientPlayer (which can throw internally)
                Method getUUID = player.getClass().getMethod("getUUID");
                java.util.UUID playerUuid = (java.util.UUID) getUUID.invoke(player);

                java.util.UUID localUuid = null;
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.player != null) {
                    localUuid = mc.player.getUUID();
                } else if (mc.getUser() != null) {
                    localUuid = mc.getUser().getProfileId();
                }
                if (localUuid == null || !localUuid.equals(playerUuid)) continue;

                // getModelDefinition() returns non-null only when loaded + doRender() == true
                Method getModelDef = player.getClass().getMethod("getModelDefinition");
                Object modelDef = getModelDef.invoke(player);

                if (shouldLog) {
                    CPMLOG.info("isLocalPlayerWearingCpmModel: local player found, modelDef={}", modelDef != null);
                }

                return modelDef != null;
            }
        } catch (Exception e) {
            CPMLOG.warn("isLocalPlayerWearingCpmModel: error", e);
        }
        return false;
    }

    /**
     * Returns the CPM player_models directory path.
     */
    public static Path getCPMModelsDirectory() {
        return PlatformHelper.getGameDirectory().resolve("player_models");
    }

    /**
     * Selects a CPM model by setting the selectedModel config key.
     * This is the inverse of resetToSkinMode().
     * @param modelFileName The model filename relative to player_models/ (e.g. "MyModel.cpmmodel")
     */
    public static void selectModel(String modelFileName) {
        if (!isAvailable()) return;

        try {
            Class<?> modConfigClass = Class.forName("com.tom.cpm.shared.config.ModConfig");
            Method getCommonConfig = modConfigClass.getMethod("getCommonConfig");
            Object config = getCommonConfig.invoke(null);
            if (config == null) return;

            CPMLOG.info("selectModel: setting selectedModel={}", modelFileName);

            Method setString = config.getClass().getMethod("setString", String.class, String.class);
            setString.invoke(config, "selectedModel", modelFileName);

            Method save = config.getClass().getMethod("save");
            save.invoke(config);

            // Notify the server if CPM's network is active
            Class<?> mcaClass = Class.forName("com.tom.cpm.shared.MinecraftClientAccess");
            Method mcaGet = mcaClass.getMethod("get");
            Object mca = mcaGet.invoke(null);
            if (mca != null) {
                Method getServerSideStatus = mcaClass.getMethod("getServerSideStatus");
                Object status = getServerSideStatus.invoke(mca);
                if (status != null && "INSTALLED".equals(status.toString())) {
                    Method sendSkinUpdate = mcaClass.getMethod("sendSkinUpdate");
                    sendSkinUpdate.invoke(mca);
                    CPMLOG.info("selectModel: sent skin update to server");
                }
            }

            // Clear the model cache so CPM reloads
            invalidatePlayerCache();
        } catch (Exception e) {
            CPMLOG.warn("selectModel: failed", e);
        }
    }

    /**
     * Parsed info from a .cpmmodel file.
     */
    public static class CpmModelInfo {
        public final String name;
        public final String description;
        public final byte[] iconPngBytes; // null if no icon

        public CpmModelInfo(String name, String description, byte[] iconPngBytes) {
            this.name = name;
            this.description = description;
            this.iconPngBytes = iconPngBytes;
        }
    }

    /**
     * Parses a .cpmmodel binary file to extract name, description, and icon.
     * Standalone parser -- does not depend on CPM classes.
     * @return parsed info, or null if the file is invalid
     */
    public static CpmModelInfo parseCpmModelInfo(Path path) {
        try (InputStream fis = Files.newInputStream(path)) {
            DataInputStream dis = new DataInputStream(fis);

            // Verify header
            int header = dis.read();
            if (header != 0x53) return null;

            // Read name (varint length + UTF bytes)
            String name = readVarIntUTF(dis);
            // Read description (varint length + UTF bytes)
            String description = readVarIntUTF(dis);

            // Skip dataBlock (varint length + bytes)
            int dataBlockLen = readVarInt(dis);
            skipFully(dis, dataBlockLen);

            // Skip overflow (varint length + bytes)
            int overflowLen = readVarInt(dis);
            if (overflowLen > 0) {
                skipFully(dis, overflowLen);
                // Skip link data: 1 byte length + that many bytes
                int linkLen = dis.read();
                if (linkLen > 0) {
                    skipFully(dis, linkLen);
                }
            }

            // Read icon image block (varint length + PNG data)
            int iconLen = readVarInt(dis);
            byte[] iconPngBytes = null;
            if (iconLen > 0) {
                iconPngBytes = new byte[iconLen];
                dis.readFully(iconPngBytes);
            }

            return new CpmModelInfo(
                    name != null ? name : path.getFileName().toString(),
                    description != null ? description : "",
                    iconPngBytes
            );
        } catch (Exception e) {
            // Return basic info from filename on parse failure
            String fileName = path.getFileName().toString();
            String nameWithoutExt = fileName;
            if (fileName.endsWith(".cpmmodel")) {
                nameWithoutExt = fileName.substring(0, fileName.length() - 9);
            }
            return new CpmModelInfo(nameWithoutExt, "", null);
        }
    }

    private static int readVarInt(DataInputStream dis) throws IOException {
        int result = 0;
        int shift = 0;
        byte b;
        do {
            b = dis.readByte();
            result |= (b & 0x7F) << (shift * 7);
            shift++;
            if (shift > 5) throw new IOException("VarInt too big");
        } while ((b & 0x80) != 0);
        return result;
    }

    private static String readVarIntUTF(DataInputStream dis) throws IOException {
        int len = readVarInt(dis);
        if (len <= 0) return "";
        byte[] bytes = new byte[len];
        dis.readFully(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void skipFully(DataInputStream dis, int n) throws IOException {
        int remaining = n;
        while (remaining > 0) {
            long skipped = dis.skip(remaining);
            if (skipped <= 0) {
                dis.readByte(); // force read to advance
                remaining--;
            } else {
                remaining -= (int) skipped;
            }
        }
    }
}
