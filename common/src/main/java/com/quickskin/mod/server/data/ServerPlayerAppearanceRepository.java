package com.quickskin.mod.server.data;

import com.quickskin.mod.common.data.PlayerAppearance;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side repository for tracking player appearances
 * This stores appearance data for all connected players on the server
 */
public class ServerPlayerAppearanceRepository {
    private static final ServerPlayerAppearanceRepository INSTANCE = new ServerPlayerAppearanceRepository();

    private final Map<UUID, PlayerAppearance> appearances = new ConcurrentHashMap<>();

    private ServerPlayerAppearanceRepository() {}

    public static ServerPlayerAppearanceRepository getInstance() {
        return INSTANCE;
    }

    /**
     * Gets a player's appearance data
     * @param playerId The player's UUID
     * @return The appearance data, or null if not set
     */
    @Nullable
    public PlayerAppearance getAppearance(UUID playerId) {
        return appearances.get(playerId);
    }

    /**
     * Sets a player's appearance data
     * @param appearance The appearance data to set
     */
    public void setAppearance(PlayerAppearance appearance) {
        if (appearance == null || appearance.getPlayerId() == null) {
            return;
        }
        appearances.put(appearance.getPlayerId(), appearance);
    }

    /**
     * Updates an existing player's appearance, or creates new if doesn't exist
     * @param playerId The player's UUID
     * @param skinId The skin ID (can be null to keep existing)
     * @param capeId The cape ID (can be null to keep existing)
     * @param model The model type (can be null to keep existing)
     */
    public void updateAppearance(UUID playerId, @Nullable String skinId, @Nullable String capeId, @Nullable String model) {
        PlayerAppearance appearance = appearances.computeIfAbsent(
            playerId,
            id -> new PlayerAppearance(id, "", "", "classic")
        );

        if (skinId != null) {
            appearance.setSkinId(skinId);
        }
        if (capeId != null) {
            appearance.setCapeId(capeId);
        }
        if (model != null) {
            appearance.setModel(model);
        }
    }

    /**
     * Removes a player's appearance data (e.g., when they disconnect)
     * @param playerId The player's UUID
     */
    public void removeAppearance(UUID playerId) {
        appearances.remove(playerId);
    }

    /**
     * Clears all appearance data
     */
    public void clear() {
        appearances.clear();
    }

    /**
     * Checks if a player has custom appearance data
     * @param playerId The player's UUID
     * @return true if the player has custom data
     */
    public boolean hasAppearance(UUID playerId) {
        return appearances.containsKey(playerId);
    }

    /** Prevents eviction of a blob referenced by a currently connected appearance. */
    public boolean isTextureReferenced(String hash) {
        if (hash == null) return false;
        String skinId = "local_skin:" + hash;
        String capeId = "local_cape:" + hash;
        for (PlayerAppearance appearance : appearances.values()) {
            if (skinId.equals(appearance.getSkinId())
                    || capeId.equals(appearance.getCapeId())) return true;
        }
        return false;
    }
}
