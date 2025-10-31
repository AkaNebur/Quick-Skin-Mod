package com.quickskin.mod.common.event;

import com.quickskin.mod.common.data.PlayerAppearance;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.UUID;

/**
 * Event fired when a player's appearance is updated
 * Listeners can use this to refresh UI, renderers, etc.
 */
@Environment(EnvType.CLIENT)
public class PlayerAppearanceUpdateEvent {
    private final UUID playerId;
    private final PlayerAppearance appearance;
    private final UpdateType updateType;

    public enum UpdateType {
        SKIN,
        CAPE,
        MODEL,
        FULL
    }

    public PlayerAppearanceUpdateEvent(UUID playerId, PlayerAppearance appearance, UpdateType updateType) {
        this.playerId = playerId;
        this.appearance = appearance;
        this.updateType = updateType;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public PlayerAppearance getAppearance() {
        return appearance;
    }

    public UpdateType getUpdateType() {
        return updateType;
    }
}
