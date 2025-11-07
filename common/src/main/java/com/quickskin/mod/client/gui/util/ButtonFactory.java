package com.quickskin.mod.client.gui.util;

import com.quickskin.mod.client.gui.widget.DangerButton;
import com.quickskin.mod.client.gui.widget.StyledButton;
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
     * Creates a styled button (regular action button).
     */
    public static Button createStyled(int x, int y, int width, int height, Component label, Button.OnPress onPress) {
        return new StyledButton(x, y, width, height, label, onPress);
    }

    /**
     * Creates a danger button (destructive action button with red accent).
     * Always uses the styled appearance to emphasize the destructive nature of the action.
     */
    public static Button createDanger(int x, int y, int width, int height, Component label, Button.OnPress onPress) {
        return new DangerButton(x, y, width, height, label, onPress);
    }

    /**
     * Creates a tab button (for tabbed interfaces).
     * Uses custom styling for consistent tabbed interface design.
     */
    public static Button createTab(int x, int y, int width, int height, Component label, boolean selected, Button.OnPress onPress) {
        return new TabButton(x, y, width, height, label, selected, onPress);
    }
}
