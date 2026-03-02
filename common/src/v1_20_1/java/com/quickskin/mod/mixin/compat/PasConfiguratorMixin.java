// PasConfiguratorMixin.java
package com.quickskin.mod.mixin.compat;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.compat.PasCompatService;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

/**
 * Mixin for Player Armor Stands (PAS) mod's PasConfiguratorScreen.
 * Adds a "Quick Skin" button that allows selecting skins from QuickSkin's library.
 */
@Pseudo
@Mixin(targets = "com.danrus.pas.render.gui.PasConfiguratorScreen", remap = false)
public abstract class PasConfiguratorMixin extends Screen {

    @Unique
    private Button quickskin$skinButton;

    @Unique
    private Object quickskin$namerAdapter;

    @Unique
    private Object quickskin$skinTabButton;

    protected PasConfiguratorMixin(Component component) {
        super(component);
    }

    @Inject(method = "method_25426", at = @At("TAIL"), remap = false)
    private void quickskin$onInit(CallbackInfo ci) {
        try {
            // Get the parent (ArmorStandNamerAdapter) via reflection
            Field parentField = this.getClass().getDeclaredField("parent");
            parentField.setAccessible(true);
            quickskin$namerAdapter = parentField.get(this);

            // Get the skinTabButton to check visibility
            Field skinTabField = this.getClass().getDeclaredField("skinTabButton");
            skinTabField.setAccessible(true);
            quickskin$skinTabButton = skinTabField.get(this);

            // Calculate button position
            // Place it below the "Open Folder" button (which is at y + 50)
            // "Open Folder" uses x = width/2 - 8 and width 120. We match that style.
            int buttonWidth = 120;
            int buttonHeight = 20;
            int x = this.width / 2 - 8;
            int y = this.height / 2 + 75;

            // Create the QuickSkin button
            quickskin$skinButton = Button.builder(
                    Component.literal("Quick Skin"),
                    button -> {
                        // Pass the PAS screen directly - it will be updated after skin selection
                        PasCompatService.openSkinSelection(this);
                    }
            )
            .bounds(x, y, buttonWidth, buttonHeight)
            .tooltip(Tooltip.create(Component.literal("Select a skin from QuickSkin library")))
            .build();

            // Initially visible - will be toggled based on active tab in tick
            quickskin$skinButton.visible = true;

            this.addRenderableWidget(quickskin$skinButton);

        } catch (NoSuchFieldException | IllegalAccessException e) {
        } catch (Exception e) {
        }
    }

    @Inject(method = "method_25393", at = @At("TAIL"), remap = false)
    private void quickskin$onTick(CallbackInfo ci) {
        if (quickskin$skinButton != null && quickskin$skinTabButton != null) {
            try {
                // In PAS, the tab button's "active" property is false when the tab is selected
                Field activeField = quickskin$skinTabButton.getClass().getSuperclass().getDeclaredField("field_3851");
                activeField.setAccessible(true);
                boolean tabActive = activeField.getBoolean(quickskin$skinTabButton);

                // Show button only when skin tab is selected (active = false means selected)
                boolean shouldShow = !tabActive;
                quickskin$skinButton.visible = shouldShow;
                quickskin$skinButton.active = shouldShow;

            } catch (Exception e) {
                // Fallback: just keep the button visible
                quickskin$skinButton.visible = true;
                quickskin$skinButton.active = true;
            }
        }
    }
}
