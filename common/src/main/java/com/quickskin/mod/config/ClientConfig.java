package com.quickskin.mod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.platform.PlatformHelper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Client-side configuration for QuickSkin
 * Stored in JSON format in config directory
 */
public class ClientConfig {
    private static ClientConfig instance;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // GUI Settings
    public boolean showSkinPreviewOverlay = false;
    public String overlayPosition = "BOTTOM_RIGHT"; // TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
    public int previewScale = 30;
    public int guiScale = 1; // GUI scaling factor (1-4)

    // Animation Settings
    public float animationSpeed = 1.0f;
    public boolean enableSmoothRotation = true;

    // Performance Settings
    public int maxCachedTextures = 100;
    public int maxSkinResolution = 2048;

    // Network Settings
    public int networkTimeout = 5000; // milliseconds

    // Compatibility Settings
    public boolean skinLayers3DCompat = true;

    // Active Skin Settings (persisted state)
    public String activeSkinHash = "";
    public String activeModelType = "auto"; // "auto", "classic", "slim"
    public String activeCapeHash = ""; // Active cape hash

    // Server Config Override (set by server, not saved to file)
    public transient ServerConfig serverOverride = null;

    private ClientConfig() {
        // Private constructor for singleton
    }

    public static ClientConfig getInstance() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    /**
     * Load configuration from file
     */
    private static ClientConfig load() {
        Path configPath = getConfigPath();

        if (Files.exists(configPath)) {
            try {
                String json = Files.readString(configPath);
                ClientConfig config = GSON.fromJson(json, ClientConfig.class);
                QuickSkin.LOGGER.info("Loaded client configuration");
                return config;
            } catch (Exception e) {
                QuickSkin.LOGGER.error("Failed to load client configuration, using defaults", e);
            }
        }

        // Return default config and save it
        ClientConfig config = new ClientConfig();
        config.save();
        return config;
    }

    /**
     * Save configuration to file
     */
    public void save() {
        Path configPath = getConfigPath();

        try {
            // Ensure config directory exists
            Files.createDirectories(configPath.getParent());

            String json = GSON.toJson(this);
            Files.writeString(configPath, json);
            QuickSkin.LOGGER.debug("Saved client configuration");
        } catch (IOException e) {
            QuickSkin.LOGGER.error("Failed to save client configuration", e);
        }
    }

    /**
     * Get config file path
     */
    private static Path getConfigPath() {
        return PlatformHelper.getConfigDirectory().resolve("quickskin-client.json");
    }

    /**
     * Reload configuration from file
     */
    public static void reload() {
        instance = load();
    }

    /**
     * Apply server config override
     * Server can restrict certain settings
     */
    public void applyServerOverride(ServerConfig serverConfig) {
        this.serverOverride = serverConfig;
        QuickSkin.LOGGER.info("Applied server configuration override");
    }

    /**
     * Get effective value considering server override
     */
    public boolean isHDSkinsAllowed() {
        if (serverOverride != null && !serverOverride.allowHDSkins) {
            return false;
        }
        return true;
    }

    /**
     * Get effective max skin resolution considering server override
     */
    public int getEffectiveMaxSkinResolution() {
        if (serverOverride != null) {
            return Math.min(maxSkinResolution, serverOverride.maxSkinResolution);
        }
        return maxSkinResolution;
    }

    /**
     * Check if custom skins are allowed
     */
    public boolean isCustomSkinsAllowed() {
        if (serverOverride != null && !serverOverride.allowCustomSkins) {
            return false;
        }
        return true;
    }

    /**
     * Check if animated capes are allowed
     */
    public boolean isAnimatedCapesAllowed() {
        if (serverOverride != null && !serverOverride.allowAnimatedCapes) {
            return false;
        }
        return true;
    }
}