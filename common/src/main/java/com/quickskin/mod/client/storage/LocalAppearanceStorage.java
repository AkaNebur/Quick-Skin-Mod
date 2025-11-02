package com.quickskin.mod.client.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.quickskin.mod.QuickSkin;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Client-side storage for player appearance preferences
 * Saves local preferences like favorite skins, last used appearance, etc.
 */
@Environment(EnvType.CLIENT)
public class LocalAppearanceStorage {
    private static LocalAppearanceStorage instance;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private Path storageFile;

    private LocalAppearanceStorage() {}

    public static LocalAppearanceStorage getInstance() {
        if (instance == null) {
            instance = new LocalAppearanceStorage();
        }
        return instance;
    }

    /**
     * Initialize storage with config directory
     */
    public void init(Path configDirectory) {
        storageFile = configDirectory.resolve("quickskin_preferences.json");
        QuickSkin.LOGGER.info("LocalAppearanceStorage initialized at: {}", storageFile);
    }

    /**
     * Player preferences data structure
     */
    public static class PlayerPreferences {
        public String lastSkinId;
        public String lastCapeId;
        public String lastModelType;
        public Map<String, String> favorites;

        public PlayerPreferences() {
            this.favorites = new HashMap<>();
        }
    }

    /**
     * All preferences (keyed by player UUID)
     */
    public static class PreferencesData {
        public Map<String, PlayerPreferences> players;

        public PreferencesData() {
            this.players = new HashMap<>();
        }
    }

    /**
     * Save player preferences
     * @param playerId The player's UUID
     */
    public void savePlayerPreferences(UUID playerId) {
        if (storageFile == null) {
            return;
        }

        // Load existing preferences
        PreferencesData data = loadPreferencesData();

        // Get or create preferences for this player
        PlayerPreferences prefs = data.players.computeIfAbsent(
            playerId.toString(),
            k -> new PlayerPreferences()
        );

        // Update from config (the active skin/model are stored in ClientConfig)
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        prefs.lastSkinId = config.activeSkinHash;
        prefs.lastModelType = config.activeModelType;

        // Save to disk
        savePreferencesData(data);

        QuickSkin.LOGGER.debug("Saved preferences for player: {}", playerId);
    }

    /**
     * Load player preferences
     * @param playerId The player's UUID
     * @return The preferences, or null if not found
     */
    @Nullable
    public PlayerPreferences loadPlayerPreferences(UUID playerId) {
        PreferencesData data = loadPreferencesData();
        return data.players.get(playerId.toString());
    }

    /**
     * Load all preferences from disk
     */
    private PreferencesData loadPreferencesData() {
        if (storageFile == null || !Files.exists(storageFile)) {
            return new PreferencesData();
        }

        try {
            String json = Files.readString(storageFile);
            PreferencesData data = GSON.fromJson(json, PreferencesData.class);
            return data != null ? data : new PreferencesData();
        } catch (IOException e) {
            QuickSkin.LOGGER.error("Failed to load appearance preferences", e);
            return new PreferencesData();
        }
    }

    /**
     * Save all preferences to disk
     */
    private void savePreferencesData(PreferencesData data) {
        if (storageFile == null) {
            return;
        }

        try {
            String json = GSON.toJson(data);
            Files.writeString(storageFile, json);
        } catch (IOException e) {
            QuickSkin.LOGGER.error("Failed to save appearance preferences", e);
        }
    }
}
