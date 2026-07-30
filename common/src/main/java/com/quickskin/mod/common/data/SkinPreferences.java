package com.quickskin.mod.common.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.common.util.BoundedFileReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Stores per-skin user preferences (model type, etc.)
 * Persisted to JSON file in config directory
 */
public class SkinPreferences {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final int MAX_PREFERENCES = 4096;

    // Map of skin hash -> preference data
    private Map<String, SkinPreference> preferences = new HashMap<>();

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
    public synchronized String getModelType(String hash) {
        SkinPreference pref = preferences.get(hash);
        return pref != null && isModel(pref.modelType) ? pref.modelType : "auto";
    }

    /**
     * Set model type preference for a skin
     * @param hash Skin hash
     * @param modelType Model type ("auto", "classic", or "slim")
     */
    public synchronized void setModelType(String hash, String modelType) {
        if (!isHash(hash) || !isModel(modelType)
                || (!preferences.containsKey(hash) && preferences.size() >= MAX_PREFERENCES)) {
            return;
        }
        preferences.computeIfAbsent(hash, k -> new SkinPreference()).modelType = modelType;
    }

    /**
     * Remove preferences for a skin (e.g., when skin is deleted)
     * @param hash Skin hash
     */
    public synchronized void remove(String hash) {
        preferences.remove(hash);
    }

    /**
     * Clear all preferences
     */
    public synchronized void clear() {
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
            String json = BoundedFileReader.readUtf8(path, MAX_FILE_BYTES);
            SkinPreferences prefs = GSON.fromJson(json, SkinPreferences.class);
            if (prefs == null) return new SkinPreferences();
            prefs.normalize();
            return prefs;
        } catch (IOException | RuntimeException e) {
            QuickSkin.LOGGER.warn("Unable to load skin preferences {}", path, e);
            return new SkinPreferences();
        }
    }

    /**
     * Save preferences to file
     * @param path Path to JSON file
     */
    public synchronized void save(Path path) {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            // Ensure parent directory exists
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }

            normalize();
            byte[] encoded = GSON.toJson(this).getBytes(StandardCharsets.UTF_8);
            if (encoded.length > MAX_FILE_BYTES) {
                QuickSkin.LOGGER.error("Refusing to save oversized skin preferences {}", path);
                return;
            }
            Files.write(temporary, encoded);
            atomicReplace(temporary, path);
        } catch (IOException e) {
            QuickSkin.LOGGER.error("Unable to save skin preferences {}", path, e);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupError) {
                QuickSkin.LOGGER.debug("Unable to remove skin preferences temp file {}",
                        temporary, cleanupError);
            }
        }
    }

    /**
     * Get number of stored preferences
     */
    public synchronized int size() {
        return preferences.size();
    }

    private void normalize() {
        if (preferences == null) preferences = new HashMap<>();
        preferences.entrySet().removeIf(entry -> !isHash(entry.getKey())
                || entry.getValue() == null || !isModel(entry.getValue().modelType));
        var iterator = preferences.keySet().iterator();
        while (preferences.size() > MAX_PREFERENCES && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private static boolean isHash(String hash) {
        if (hash == null || hash.length() != 40) return false;
        for (int index = 0; index < hash.length(); index++) {
            char value = hash.charAt(index);
            if (!((value >= '0' && value <= '9') || (value >= 'a' && value <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isModel(String model) {
        return "auto".equals(model) || "classic".equals(model) || "slim".equals(model);
    }

    private static void atomicReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
