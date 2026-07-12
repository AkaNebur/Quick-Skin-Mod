package com.quickskin.mod.client.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.common.util.BoundedFileReader;
import com.quickskin.mod.networking.NetworkSecurity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Client-side storage for player appearance preferences
 * Saves local preferences like favorite skins, last used appearance, etc.
 */
@Environment(EnvType.CLIENT)
public class LocalAppearanceStorage {
    private static final int MAX_PREFERENCES_BYTES = 4 * 1024 * 1024;
    private static final int MAX_PLAYERS = 1024;
    private static final int MAX_FAVORITES_PER_PLAYER = 4096;
    private static volatile LocalAppearanceStorage instance;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private Path storageFile;

    private LocalAppearanceStorage() {}

    public static synchronized LocalAppearanceStorage getInstance() {
        if (instance == null) {
            instance = new LocalAppearanceStorage();
        }
        return instance;
    }

    /**
     * Initialize storage with config directory
     */
    public synchronized void init(Path configDirectory) {
        storageFile = Objects.requireNonNull(configDirectory, "configDirectory")
                .resolve("quickskin_preferences.json").toAbsolutePath().normalize();
    }

    /**
     * Player preferences data structure
     */
    public static class PlayerPreferences {
        public String lastSkinId;
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
    public synchronized void savePlayerPreferences(UUID playerId) {
        if (storageFile == null || playerId == null) {
            return;
        }

        // Load existing preferences
        PreferencesData data = loadPreferencesData();

        // Get or create preferences for this player
        PlayerPreferences prefs = data.players.computeIfAbsent(
            playerId.toString(),
            k -> new PlayerPreferences()
        );

        // Update from config (the active skin is stored in ClientConfig)
        com.quickskin.mod.config.ClientConfig config = com.quickskin.mod.config.ClientConfig.getInstance();
        prefs.lastSkinId = config.activeSkinHash;

        // Get model type from the skin's preference
        if (!config.activeSkinHash.isEmpty()) {
            com.quickskin.mod.client.services.LocalAssetManager assetManager =
                com.quickskin.mod.client.services.LocalAssetManager.getInstance();
            prefs.lastModelType = assetManager.getSkinModelPreference(config.activeSkinHash);
        } else {
            prefs.lastModelType = "auto"; // Default if no skin is active
        }

        // Save to disk
        savePreferencesData(data);
    }

    /**
     * Load all preferences from disk
     */
    private PreferencesData loadPreferencesData() {
        if (storageFile == null || !Files.isRegularFile(storageFile)) {
            return new PreferencesData();
        }

        try {
            long size = Files.size(storageFile);
            if (size <= 0 || size > MAX_PREFERENCES_BYTES) {
                QuickSkin.LOGGER.warn("Ignoring oversized local appearance preferences {}", storageFile);
                return new PreferencesData();
            }
            String json = BoundedFileReader.readUtf8(storageFile, MAX_PREFERENCES_BYTES);
            PreferencesData data = GSON.fromJson(json, PreferencesData.class);
            if (data == null) {
                return new PreferencesData();
            }
            normalize(data);
            return data;
        } catch (IOException | RuntimeException e) {
            QuickSkin.LOGGER.warn("Unable to load local appearance preferences {}", storageFile, e);
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

        Path temporary = storageFile.resolveSibling(storageFile.getFileName() + ".tmp");
        try {
            normalize(data);
            Files.createDirectories(storageFile.getParent());
            String json = GSON.toJson(data);
            byte[] encoded = json.getBytes(StandardCharsets.UTF_8);
            if (encoded.length > MAX_PREFERENCES_BYTES) {
                QuickSkin.LOGGER.error("Refusing to save oversized local appearance preferences {}",
                        storageFile);
                return;
            }
            Files.write(temporary, encoded);
            atomicReplace(temporary, storageFile);
        } catch (IOException e) {
            QuickSkin.LOGGER.error("Unable to save local appearance preferences {}", storageFile, e);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupError) {
                QuickSkin.LOGGER.debug("Unable to remove local appearance temp file {}",
                        temporary, cleanupError);
            }
        }
    }

    private static void normalize(PreferencesData data) {
        if (data.players == null) {
            data.players = new HashMap<>();
            return;
        }
        data.players.entrySet().removeIf(entry -> entry.getKey() == null
                || entry.getValue() == null || !isUuid(entry.getKey()));
        trimToSize(data.players, MAX_PLAYERS);
        for (PlayerPreferences preferences : data.players.values()) {
            if (preferences.favorites == null) preferences.favorites = new HashMap<>();
            preferences.favorites.entrySet().removeIf(entry -> entry.getKey() == null
                    || entry.getValue() == null || entry.getKey().length() > 256
                    || entry.getValue().length() > 256);
            trimToSize(preferences.favorites, MAX_FAVORITES_PER_PLAYER);
            if (preferences.lastSkinId == null
                    || (!preferences.lastSkinId.isEmpty()
                    && !NetworkSecurity.isValidContentId(preferences.lastSkinId))) {
                preferences.lastSkinId = "";
            }
            if (!NetworkSecurity.isValidModel(preferences.lastModelType)) {
                preferences.lastModelType = "auto";
            }
        }
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static void trimToSize(Map<?, ?> map, int maximumSize) {
        var iterator = map.keySet().iterator();
        while (map.size() > maximumSize && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
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
