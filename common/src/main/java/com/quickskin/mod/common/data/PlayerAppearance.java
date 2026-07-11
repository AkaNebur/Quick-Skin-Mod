package com.quickskin.mod.common.data;

//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.resources.Identifier;
//?}

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
    //? if <1.21.11 {
    private ResourceLocation skinLocation;
    private ResourceLocation capeLocation;
    //?} else {
    private Identifier skinLocation;
    private Identifier capeLocation;
    //?}

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
    //? if <1.21.11 {
    public ResourceLocation getSkinLocation() {
    //?} else {
    public Identifier getSkinLocation() {
    //?}
        return skinLocation;
    }

    //? if <1.21.11 {
    public void setSkinLocation(ResourceLocation skinLocation) {
    //?} else {
    public void setSkinLocation(Identifier skinLocation) {
    //?}
        this.skinLocation = skinLocation;
    }

    @Nullable
    //? if <1.21.11 {
    public ResourceLocation getCapeLocation() {
    //?} else {
    public Identifier getCapeLocation() {
    //?}
        return capeLocation;
    }

    //? if <1.21.11 {
    public void setCapeLocation(ResourceLocation capeLocation) {
    //?} else {
    public void setCapeLocation(Identifier capeLocation) {
    //?}
        this.capeLocation = capeLocation;
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
