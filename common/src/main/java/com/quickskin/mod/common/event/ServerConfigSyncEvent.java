package com.quickskin.mod.common.event;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Event fired when server config is synced to the client
 * Listeners can use this to update client behavior based on server settings
 */
@Environment(EnvType.CLIENT)
public class ServerConfigSyncEvent {
    private final boolean allowCustomSkins;
    private final boolean allowCustomCapes;
    private final boolean allowTransparentSkins;

    public ServerConfigSyncEvent(boolean allowCustomSkins, boolean allowCustomCapes, boolean allowTransparentSkins) {
        this.allowCustomSkins = allowCustomSkins;
        this.allowCustomCapes = allowCustomCapes;
        this.allowTransparentSkins = allowTransparentSkins;
    }

    public boolean isAllowCustomSkins() {
        return allowCustomSkins;
    }

    public boolean isAllowCustomCapes() {
        return allowCustomCapes;
    }

    public boolean isAllowTransparentSkins() {
        return allowTransparentSkins;
    }
}
