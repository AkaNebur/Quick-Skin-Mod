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
    public boolean autoRotatePreview = true;
    public int previewScale = 30;

    // Keybind Settings
    public boolean enableKeybinds = true;

    // Performance Settings
    public boolean cacheTextures = true;
    public int maxCachedTextures = 100;

    // Compatibility Settings
    public boolean skinLayers3DCompat = true;

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
}
