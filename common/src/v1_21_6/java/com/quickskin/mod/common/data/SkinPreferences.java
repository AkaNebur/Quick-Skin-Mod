package com.quickskin.mod.common.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.quickskin.mod.QuickSkin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Stores per-skin user preferences (model type, etc.)
 * Persisted to JSON file in config directory
 */
public class SkinPreferences {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Map of skin hash -> preference data
    private final Map<String, SkinPreference> preferences = new HashMap<>();

    /**
     * Individual skin preference
     */
    public static class SkinPreference {
        public String modelType = "auto"; // "auto", "classic", or "slim"

        public SkinPreference() {
            // Default constructor for Gson
        }

    }

    /**
     * Get model type preference for a skin
     * @param hash Skin hash
     * @return Model type ("auto", "classic", or "slim"), defaults to "auto"
     */
    public String getModelType(String hash) {
        SkinPreference pref = preferences.get(hash);
        return pref != null ? pref.modelType : "auto";
    }

    /**
     * Set model type preference for a skin
     * @param hash Skin hash
     * @param modelType Model type ("auto", "classic", or "slim")
     */
    public void setModelType(String hash, String modelType) {
        preferences.computeIfAbsent(hash, k -> new SkinPreference()).modelType = modelType;
    }

    /**
     * Remove preferences for a skin (e.g., when skin is deleted)
     * @param hash Skin hash
     */
    public void remove(String hash) {
        preferences.remove(hash);
    }

    /**
     * Clear all preferences
     */
    public void clear() {
        preferences.clear();
    }

    /**
     * Load preferences from file
     * @param path Path to JSON file
     * @return Loaded preferences, or new instance if file doesn't exist
     */
    public static SkinPreferences load(Path path) {
        if (!Files.exists(path)) {
            return new SkinPreferences();
        }

        try {
            String json = Files.readString(path);
            SkinPreferences prefs = GSON.fromJson(json, SkinPreferences.class);
            return prefs != null ? prefs : new SkinPreferences();
        } catch (IOException e) {
            return new SkinPreferences();
        }
    }

    /**
     * Save preferences to file
     * @param path Path to JSON file
     */
    public void save(Path path) {
        try {
            // Ensure parent directory exists
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }

            String json = GSON.toJson(this);
            Files.writeString(path, json);
        } catch (IOException e) {
            // Log error but don't crash
            QuickSkin.LOGGER.error("Failed to save skin preferences to {}: {}", path, e.getMessage());
        }
    }

    /**
     * Get number of stored preferences
     */
    public int size() {
        return preferences.size();
    }
}
