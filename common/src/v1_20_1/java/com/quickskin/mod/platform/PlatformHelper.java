package com.quickskin.mod.platform;

import dev.architectury.injectables.annotations.ExpectPlatform;
import java.nio.file.Path;

/**
 * Platform abstraction layer for QuickSkin
 * Methods here are implemented in platform-specific modules (forge/fabric)
 */
public class PlatformHelper {

    /**
     * Gets the platform name (e.g., "Forge", "Fabric")
     */
    @ExpectPlatform
    public static String getPlatformName() {
        throw new AssertionError();
    }

    /**
     * Gets the game directory (where Minecraft is installed)
     */
    @ExpectPlatform
    public static Path getGameDirectory() {
        throw new AssertionError();
    }

    /**
     * Gets the directory where custom skins should be stored
     * Default: <game_dir>/quickskin/uploads/skins
     */
    @ExpectPlatform
    public static Path getSkinsDirectory() {
        throw new AssertionError();
    }

    /**
     * Gets the directory where custom capes should be stored
     * Default: <game_dir>/quickskin/uploads/capes
     */
    @ExpectPlatform
    public static Path getCapesDirectory() {
        throw new AssertionError();
    }

    /**
     * Gets the config directory
     * Forge: <game_dir>/config
     * Fabric: <game_dir>/config
     */
    @ExpectPlatform
    public static Path getConfigDirectory() {
        throw new AssertionError();
    }

    /**
     * Gets the cache directory for processed textures
     * Default: <game_dir>/quickskin_cache
     */
    @ExpectPlatform
    public static Path getCacheDirectory() {
        throw new AssertionError();
    }

    /**
     * Checks if a mod is loaded
     * @param modId The mod ID to check
     * @return true if the mod is loaded
     */
    @ExpectPlatform
    public static boolean isModLoaded(String modId) {
        throw new AssertionError();
    }

    /**
     * Gets the mod version
     */
    @ExpectPlatform
    public static String getModVersion() {
        throw new AssertionError();
    }

    /**
     * Checks if running in a development environment
     */
    @ExpectPlatform
    public static boolean isDevelopmentEnvironment() {
        throw new AssertionError();
    }
}
