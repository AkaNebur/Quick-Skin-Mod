package com.quickskin.mod.client.gui.util;

import com.quickskin.mod.client.gui.widget.TabButton;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Factory for creating specialized button widgets
 */
@Environment(EnvType.CLIENT)
public class ButtonFactory {

    /**
     * Creates a tab button (for tabbed interfaces).
     * Uses custom styling for consistent tabbed interface design.
     */
    public static Button createTab(int x, int y, int width, int height, Component label, boolean selected, Button.OnPress onPress) {
        return new TabButton(x, y, width, height, label, selected, onPress);
    }
}
