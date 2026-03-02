package com.quickskin.mod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.common.data.BackgroundStyle;
import com.quickskin.mod.common.data.SkinSortMode;
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
    public boolean enablePlayerPreviewCustomization = false; // Enable customization (resize, reposition) of player previews
    public float hudOverlayRotation = 20.0f;

    // Player Preview Slider Percentages (1-100%) for different contexts
    // 0 = use built-in default, non-zero = use that percentage
    public int sizeModelPreviewPercentageTitleScreen = 0;
    public int sizeModelPreviewPercentageSkinMenu = 0;
    public int sizeModelPreviewPercentageCapeMenu = 0;
    public int sizeModelPreviewPercentagePauseMenu = 0;
    public int sizeModelPreviewPercentageHudOverlay = 30;

    // Player Preview Position Offsets (X, Y) for different contexts
    // These are added to the base offsets (which already include the intended defaults)
    public int positionOffsetXTitleScreen = 0;
    public int positionOffsetYTitleScreen = 0;
    public int positionOffsetXSkinMenu = 0;
    public int positionOffsetYSkinMenu = 0;
    public int positionOffsetXCapeMenu = 0;
    public int positionOffsetYCapeMenu = 0;
    public int positionOffsetXPauseMenu = 0;
    public int positionOffsetYPauseMenu = 0;
    public int positionOffsetXHudOverlay = 0;
    public int positionOffsetYHudOverlay = 0;

    // Animation Settings
    public float animationSpeed = 1.0f; // Default animation speed (deprecated, use per-cape speeds)
    public boolean enableSmoothRotation = true;
    public Map<String, Float> capeAnimationSpeeds = new HashMap<>(); // Per-cape animation speeds (capeId -> speed)

    // Performance Settings
    public int maxCachedTextures = 100;

    // Network Settings
    public int networkTimeout = 5000; // milliseconds

    // Transparency Settings
    public boolean disableSkinTransparency = false; // Disable transparency in player skins

    // GUI Style Settings
    public boolean enableStyledButtons = false; // Enable custom styled buttons with frosted glass aesthetic

    // Cape Settings
    public boolean hideBuiltInCapes = false; // Hide built-in Minecraft capes from cape menu

    // Sorting Settings
    public String skinSortMode = "LATEST_LAST"; // Skin list sorting mode

    // Menu Background Style
    public String menuBackgroundStyle = "opaque_stars"; // "opaque_stars" or "vanilla_blur"

    // --- NEW --- Modpack Settings
    public boolean enablePlayerOwnSkinSystem = true; // When enabled, automatically downloads and protects the player's own skin.

    // Logging Settings

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
                return config;
            } catch (Exception e) {
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
    }

    /**
     * Get current server config override
     * @return Server config override, or null if not set
     */
    public ServerConfig getServerOverride() {
        return serverOverride;
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
    }

    /**
     * Get skin sort mode with fallback to default
     * @return Skin sort mode
     */
    public SkinSortMode getSkinSortMode() {
        try {
            return SkinSortMode.valueOf(skinSortMode);
        } catch (IllegalArgumentException e) {
            return SkinSortMode.LATEST_LAST;
        }
    }

    /**
     * Set skin sort mode and save configuration
     * @param mode The sort mode to set
     */
    public void setSkinSortMode(SkinSortMode mode) {
        this.skinSortMode = mode.name();
        save();
    }

    /**
     * Get menu background style with fallback to default
     * @return Menu background style enum
     */
    public BackgroundStyle getMenuBackgroundStyle() {
        return BackgroundStyle.fromId(menuBackgroundStyle);
    }

    /**
     * Set menu background style
     * @param style The background style to set
     */
    public void setMenuBackgroundStyle(BackgroundStyle style) {
        this.menuBackgroundStyle = style.getId();
        save();
    }
}