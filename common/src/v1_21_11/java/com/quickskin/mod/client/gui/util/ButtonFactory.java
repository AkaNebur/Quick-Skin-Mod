package com.quickskin.mod.client.gui.util;

import com.quickskin.mod.client.gui.widget.DangerButton;
import com.quickskin.mod.client.gui.widget.PrimaryButton;
import com.quickskin.mod.client.gui.widget.RotateButton;
import com.quickskin.mod.client.gui.widget.StyledButton;
import com.quickskin.mod.client.gui.widget.TabButton;
import com.quickskin.mod.config.ClientConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.function.Supplier;

/**
 * Factory for creating buttons that respect the styled buttons config option.
 * When styled buttons are disabled, creates standard Minecraft buttons.
 * When enabled, creates custom styled buttons with frosted glass aesthetic.
 */
@Environment(EnvType.CLIENT)
public class ButtonFactory {

    /**
     * Creates a styled button (regular action button).
     */
    public static Button createStyled(int x, int y, int width, int height, Component label, Button.OnPress onPress) {
        if (ClientConfig.getInstance().enableStyledButtons) {
            return new StyledButton(x, y, width, height, label, onPress);
        } else {
            return Button.builder(label, onPress)
                    .bounds(x, y, width, height)
                    .build();
        }
    }

    /**
     * Creates a primary button (main/important action button with green accent).
     */
    public static Button createPrimary(int x, int y, int width, int height, Component label, Button.OnPress onPress) {
        if (ClientConfig.getInstance().enableStyledButtons) {
            return new PrimaryButton(x, y, width, height, label, onPress);
        } else {
            return Button.builder(label, onPress)
                    .bounds(x, y, width, height)
                    .build();
        }
    }

    /**
     * Creates a danger button (destructive action button with red accent).
     * Always uses the styled appearance to emphasize the destructive nature of the action.
     */
    public static Button createDanger(int x, int y, int width, int height, Component label, Button.OnPress onPress) {
        return new DangerButton(x, y, width, height, label, onPress);
    }

    /**
     * Creates a rotate button (rotation preview button).
     */
    public static Button createRotate(int x, int y, int size, Button.OnPress onPress) {
        if (ClientConfig.getInstance().enableStyledButtons) {
            return new RotateButton(x, y, size, onPress);
        } else {
            // Create vanilla button with custom large rotation symbol rendering
            Component buttonText = Component.literal("↺");
            // In 1.21.11: renderString removed, use RotateButton which overrides renderContents
            return new RotateButton(x, y, size, onPress);
        }
    }

    /**
     * Creates a tab button (for tabbed interfaces).
     * Always uses the styled appearance for consistent tabbed interface design.
     */
    public static Button createTab(int x, int y, int width, int height, Component label, boolean selected, Button.OnPress onPress) {
        return new TabButton(x, y, width, height, label, selected, onPress);
    }
}
