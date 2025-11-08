package com.quickskin.mod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.platform.PlatformHelper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

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
    public float animationSpeed = 1.0f; // Default animation speed (deprecated, use per-cape speeds)
    public boolean enableSmoothRotation = true;
    public Map<String, Float> capeAnimationSpeeds = new HashMap<>(); // Per-cape animation speeds (capeId -> speed)

    // Performance Settings
    public int maxCachedTextures = 100;
    public int maxSkinResolution = 2048;

    // Network Settings
    public int networkTimeout = 5000; // milliseconds

    // Compatibility Settings
    public boolean skinLayers3DCompat = true;

    // Transparency Settings
    public boolean disableSkinTransparency = false; // Disable transparency in player skins

    // Active Skin Settings (persisted state)
    public String activeSkinHash = "";
    @Deprecated // Now using per-skin model preferences stored in skin-preferences.json
    public String activeModelType = "auto"; // "auto", "classic", "slim" (deprecated - kept for compatibility)
    public String activeCapeHash = ""; // Active cape hash
    public String playerOwnSkinHash = ""; // Hash of the player's own Mojang skin (protected from deletion)

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

    /**
     * Check if skin transparency should be disabled
     * Uses OR logic: if either client OR server disables transparency, it's disabled
     */
    public boolean shouldDisableSkinTransparency() {
        // Client wants to disable transparency
        if (disableSkinTransparency) {
            return true;
        }

        // Server wants to disable transparency
        if (serverOverride != null && serverOverride.disableSkinTransparency) {
            return true;
        }

        return false;
    }

    /**
     * Get animation speed with clamping to prevent invalid values (deprecated)
     * @return Clamped animation speed (0.01 to 10.0)
     * @deprecated Use getCapeAnimationSpeed(String capeId) instead
     */
    @Deprecated
    public float getAnimationSpeed() {
        return Math.max(0.01f, Math.min(animationSpeed, 10.0f));
    }

    /**
     * Get animation speed for a specific cape
     * @param capeId The cape ID (e.g., "local_cape:hash" or "known:cape_name")
     * @return Clamped animation speed (0.01 to 10.0), defaults to 1.0 if not set
     */
    public float getCapeAnimationSpeed(String capeId) {
        if (capeId == null || capeId.isEmpty()) {
            return 1.0f;
        }

        // Ensure the map is initialized (in case it's null after deserialization)
        if (capeAnimationSpeeds == null) {
            capeAnimationSpeeds = new HashMap<>();
        }

        Float speed = capeAnimationSpeeds.get(capeId);
        if (speed == null) {
            return 1.0f; // Default speed
        }

        // Clamp to prevent invalid values
        return Math.max(0.01f, Math.min(speed, 10.0f));
    }

    /**
     * Set animation speed for a specific cape
     * @param capeId The cape ID
     * @param speed The animation speed (will be clamped to 0.01-10.0)
     */
    public void setCapeAnimationSpeed(String capeId, float speed) {
        if (capeId == null || capeId.isEmpty()) {
            return;
        }

        // Ensure the map is initialized
        if (capeAnimationSpeeds == null) {
            capeAnimationSpeeds = new HashMap<>();
        }

        // Clamp and store
        float clampedSpeed = Math.max(0.01f, Math.min(speed, 10.0f));
        capeAnimationSpeeds.put(capeId, clampedSpeed);

        QuickSkin.LOGGER.debug("Set animation speed for cape {}: {}", capeId, clampedSpeed);
    }
}