package com.quickskin.mod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.platform.PlatformHelper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Server-side configuration for QuickSkin
 * Stored in JSON format in config directory
 */
public class ServerConfig {
    private static ServerConfig instance;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Skin Settings
    public boolean disableSkinTransparency = false; // Disable transparency in player skins
    public int skinChangeCooldownSeconds = 0; // Cooldown in seconds for changing skin (0 = disabled)

    private ServerConfig() {
        // Private constructor for singleton
    }

    public static ServerConfig getInstance() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    /**
     * Load configuration from file
     */
    private static ServerConfig load() {
        Path configPath = getConfigPath();

        if (Files.exists(configPath)) {
            try {
                String json = Files.readString(configPath);
                ServerConfig config = GSON.fromJson(json, ServerConfig.class);
                return config;
            } catch (Exception e) {
            }
        }

        // Return default config and save it
        ServerConfig config = new ServerConfig();
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
        } catch (IOException e) {
        }
    }

    /**
     * Get config file path
     */
    private static Path getConfigPath() {
        return PlatformHelper.getConfigDirectory().resolve("quickskin-server.json");
    }

    /**
     * Reload configuration from file
     */
    public static void reload() {
        instance = load();
    }

    /**
     * Convert to JSON for network transmission
     */
    public String toJson() {
        return GSON.toJson(this);
    }

    /**
     * Create from JSON (for network reception)
     */
    public static ServerConfig fromJson(String json) {
        try {
            return GSON.fromJson(json, ServerConfig.class);
        } catch (Exception e) {
            return new ServerConfig();
        }
    }
}
