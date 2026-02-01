package com.quickskin.mod.common.data;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import org.jetbrains.annotations.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single source of truth for player appearance data (client-side only)
 * This is the central repository that all services and mixins query
 */
@Environment(EnvType.CLIENT)
public class PlayerAppearanceRepository {
    private static final PlayerAppearanceRepository INSTANCE = new PlayerAppearanceRepository();

    private final Map<UUID, PlayerAppearance> appearances = new ConcurrentHashMap<>();

    private PlayerAppearanceRepository() {}

    public static PlayerAppearanceRepository getInstance() {
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
     * Clears all appearance data
     */
    public void clear() {
        appearances.clear();
    }

}
