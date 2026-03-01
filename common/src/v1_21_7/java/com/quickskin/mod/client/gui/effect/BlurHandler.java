package com.quickskin.mod.client.gui.effect;

import com.quickskin.mod.QuickSkin;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Handles blur rendering - simplified to be called directly from the screen.
 *
 * In MC 1.21.4+, the PostChain API was reworked (constructor changed, resize/close/process
 * methods removed or changed signatures), so custom PostChain-based blur is not supported.
 * This handler gracefully no-ops to allow compilation and runtime without blur effects.
 */
@Environment(EnvType.CLIENT)
public class BlurHandler {

    private static boolean warned = false;

    /**
     * Call this after rendering the background but before rendering UI.
     * No-op on 1.21.4+ due to PostChain API changes.
     */
    public static void renderBlur() {
        if (!warned) {
            QuickSkin.LOGGER.debug("BlurHandler: Blur effect is not supported on this Minecraft version (PostChain API changed in 1.21.4+)");
            warned = true;
        }
        // No-op: PostChain API is incompatible in 1.21.4+
    }

    /**
     * Cleans up blur shader resources.
     * No-op on 1.21.4+ since no shader is loaded.
     */
    public static void cleanup() {
        // No-op: no shader to clean up
    }
}
