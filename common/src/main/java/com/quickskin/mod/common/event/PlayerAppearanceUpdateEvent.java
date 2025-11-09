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
public record PlayerAppearanceUpdateEvent(UUID playerId, PlayerAppearance appearance) {
    public enum UpdateType {
        SKIN,
        CAPE,
        MODEL,
        FULL
    }

}
