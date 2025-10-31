package com.quickskin.mod.common.event;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Event fired when local assets (skins/capes) are reloaded
 * Listeners can use this to refresh UI lists
 */
@Environment(EnvType.CLIENT)
public class LocalAssetReloadEvent {
    private final AssetType assetType;

    public enum AssetType {
        SKINS,
        CAPES,
        ALL
    }

    public LocalAssetReloadEvent(AssetType assetType) {
        this.assetType = assetType;
    }

    public AssetType getAssetType() {
        return assetType;
    }
}
