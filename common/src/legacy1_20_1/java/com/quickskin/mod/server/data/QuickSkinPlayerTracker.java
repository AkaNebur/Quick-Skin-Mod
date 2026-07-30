package com.quickskin.mod.server.data;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which players have QuickSkin installed by observing C2S packets they send.
 * Used as a fallback when Architectury's NetworkManager.canPlayerReceive() returns false
 * (which happens on Forge 1.20.1 with Architectury 9.2.14).
 */
public class QuickSkinPlayerTracker {
    private static final QuickSkinPlayerTracker INSTANCE = new QuickSkinPlayerTracker();
    private final Set<UUID> confirmedPlayers = ConcurrentHashMap.newKeySet();

    private QuickSkinPlayerTracker() {}

    public static QuickSkinPlayerTracker getInstance() {
        return INSTANCE;
    }

    /**
     * Marks a player as confirmed to have QuickSkin installed.
     * @param playerId The UUID of the player.
     * @return true if this is the first time the player is confirmed (triggers initial sync).
     */
    public boolean markConfirmed(UUID playerId) {
        return confirmedPlayers.add(playerId);
    }

    /**
     * Checks if a player has been confirmed to have QuickSkin installed.
     * @param playerId The UUID of the player.
     * @return true if the player has been confirmed.
     */
    public boolean isConfirmed(UUID playerId) {
        return confirmedPlayers.contains(playerId);
    }

    /**
     * Removes a player from the tracker, e.g., when they disconnect.
     * @param playerId The UUID of the player.
     */
    public void removePlayer(UUID playerId) {
        confirmedPlayers.remove(playerId);
    }

    /**
     * Clears all tracked players, e.g., when the server stops.
     */
    public void clear() {
        confirmedPlayers.clear();
    }
}
