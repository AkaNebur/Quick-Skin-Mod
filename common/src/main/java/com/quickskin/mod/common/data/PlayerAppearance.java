package com.quickskin.mod.common.data;

import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/**
 * Represents a player's complete appearance (skin, cape, model)
 * This is the core data model used throughout the mod
 */
public class PlayerAppearance {
    private final UUID playerId;
    private String skinId;              // e.g., "local_skin:hash" or "username"
    private String capeId;              // e.g., "local_cape:hash", "known:minecon2016", or "username"
    private String model;               // "classic", "slim", or "auto"

    // Resolved ResourceLocations (cached)
    private ResourceLocation skinLocation;
    private ResourceLocation capeLocation;

    public PlayerAppearance(UUID playerId, String skinId, String capeId, String model) {
        this.playerId = playerId;
        this.skinId = skinId != null ? skinId : "";
        this.capeId = capeId != null ? capeId : "";
        this.model = model != null ? model : "classic";
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getSkinId() {
        return skinId;
    }

    public void setSkinId(String skinId) {
        this.skinId = skinId;
        this.skinLocation = null; // Invalidate cache
    }

    public String getCapeId() {
        return capeId;
    }

    public void setCapeId(String capeId) {
        this.capeId = capeId;
        this.capeLocation = null; // Invalidate cache
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    @Nullable
    public ResourceLocation getSkinLocation() {
        return skinLocation;
    }

    public void setSkinLocation(ResourceLocation skinLocation) {
        this.skinLocation = skinLocation;
    }

    @Nullable
    public ResourceLocation getCapeLocation() {
        return capeLocation;
    }

    public void setCapeLocation(ResourceLocation capeLocation) {
        this.capeLocation = capeLocation;
    }

    public boolean hasCustomSkin() {
        return skinId != null && !skinId.isEmpty();
    }

    public boolean hasCustomCape() {
        return capeId != null && !capeId.isEmpty();
    }

    public PlayerAppearance copy() {
        PlayerAppearance copy = new PlayerAppearance(playerId, skinId, capeId, model);
        copy.skinLocation = this.skinLocation;
        copy.capeLocation = this.capeLocation;
        return copy;
    }

    @Override
    public String toString() {
        return "PlayerAppearance{" +
                "playerId=" + playerId +
                ", skinId='" + skinId + '\'' +
                ", capeId='" + capeId + '\'' +
                ", model='" + model + '\'' +
                '}';
    }
}
