package com.quickskin.mod.server.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.common.data.PlayerAppearance;
import com.quickskin.mod.server.data.ServerPlayerAppearanceRepository;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Server-side storage for player appearance persistence
 * Saves and loads player appearances to/from disk
 */
public class ServerAppearanceStorage {
    private static ServerAppearanceStorage instance;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private Path storageDirectory;

    private ServerAppearanceStorage() {}

    public static ServerAppearanceStorage getInstance() {
        if (instance == null) {
            instance = new ServerAppearanceStorage();
        }
        return instance;
    }

    /**
     * Initialize the appearance storage with server instance
     */
    public void init(MinecraftServer server) {
        // Get server world directory
        Path worldPath = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        storageDirectory = worldPath.resolve("quickskin").resolve("appearances");

        try {
            Files.createDirectories(storageDirectory);
        } catch (IOException e) {
            QuickSkin.LOGGER.error("Failed to create appearance storage directory", e);
        }
    }

    /**
     * Load a player's saved appearance from disk
     * @param playerId The player's UUID
     * @return The loaded appearance, or null if not found
     */
    @Nullable
    public PlayerAppearance loadPlayerAppearance(UUID playerId) {
        if (storageDirectory == null) {
            return null;
        }

        Path file = storageDirectory.resolve(playerId.toString() + ".json");

        if (!Files.exists(file)) {
            return null;
        }

        try {
            String json = Files.readString(file);
            PlayerAppearance appearance = GSON.fromJson(json, PlayerAppearance.class);

            // Update the repository with loaded data
            ServerPlayerAppearanceRepository.getInstance().setAppearance(appearance);

            return appearance;
        } catch (IOException e) {
            QuickSkin.LOGGER.error("Failed to load appearance for player: {}", playerId, e);
            return null;
        }
    }

    /**
     * Save a player's appearance to disk
     * @param playerId The player's UUID
     */
    public void savePlayerAppearance(UUID playerId) {
        if (storageDirectory == null) {
            return;
        }

        PlayerAppearance appearance = ServerPlayerAppearanceRepository.getInstance().getAppearance(playerId);

        if (appearance == null) {
            return;
        }

        Path file = storageDirectory.resolve(playerId.toString() + ".json");

        try {
            String json = GSON.toJson(appearance);
            Files.writeString(file, json);
        } catch (IOException e) {
            QuickSkin.LOGGER.error("Failed to save appearance for player: {}", playerId, e);
        }
    }

}
