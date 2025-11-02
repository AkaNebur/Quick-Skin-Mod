package com.quickskin.mod.client.gui.widget;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/**
 * Confirmation dialog overlay for destructive actions
 */
@Environment(EnvType.CLIENT)
public class ConfirmationDialog {

    private final Minecraft mc;
    private final Component title;
    private final Component message;
    private final Runnable onConfirm;
    private final Runnable onCancel;

    private Button confirmButton;
    private Button cancelButton;

    private int dialogX;
    private int dialogY;
    private int dialogWidth = 280;
    private int dialogHeight = 120;

    public ConfirmationDialog(Component title, Component message, Runnable onConfirm, Runnable onCancel) {
        this.mc = Minecraft.getInstance();
        this.title = title;
        this.message = message;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
    }

    /**
     * Initialize dialog and create buttons
     */
    public void init(int screenWidth, int screenHeight) {
        // Center the dialog
        dialogX = (screenWidth - dialogWidth) / 2;
        dialogY = (screenHeight - dialogHeight) / 2;

        // Create buttons
        int buttonWidth = 100;
        int buttonHeight = 20;
        int buttonSpacing = 10;
        int buttonY = dialogY + dialogHeight - buttonHeight - 15;

        int confirmX = dialogX + (dialogWidth / 2) - buttonWidth - (buttonSpacing / 2);
        int cancelX = dialogX + (dialogWidth / 2) + (buttonSpacing / 2);

        confirmButton = Button.builder(
            Component.literal("Delete"),
            btn -> {
                mc.getSoundManager().play(
                    net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        SoundEvents.UI_BUTTON_CLICK.value(), 1.0f
                    )
                );
                onConfirm.run();
            }
        ).bounds(confirmX, buttonY, buttonWidth, buttonHeight).build();

        cancelButton = Button.builder(
            Component.literal("Cancel"),
            btn -> {
                mc.getSoundManager().play(
                    net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        SoundEvents.UI_BUTTON_CLICK.value(), 1.0f
                    )
                );
                onCancel.run();
            }
        ).bounds(cancelX, buttonY, buttonWidth, buttonHeight).build();
    }

    /**
     * Render the dialog
     */
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Push pose stack to render on top
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0.0, 0.0, 400.0); // High z-level to render on top of everything

        // Darken background
        guiGraphics.fill(0, 0, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight(), 0xC0000000);

        // Draw dialog background with higher opacity
        guiGraphics.fill(dialogX, dialogY, dialogX + dialogWidth, dialogY + dialogHeight, 0xFF2D2D2D);

        // Draw border
        guiGraphics.fill(dialogX, dialogY, dialogX + dialogWidth, dialogY + 1, 0xFF5A5A5A); // Top
        guiGraphics.fill(dialogX, dialogY + dialogHeight - 1, dialogX + dialogWidth, dialogY + dialogHeight, 0xFF5A5A5A); // Bottom
        guiGraphics.fill(dialogX, dialogY, dialogX + 1, dialogY + dialogHeight, 0xFF5A5A5A); // Left
        guiGraphics.fill(dialogX + dialogWidth - 1, dialogY, dialogX + dialogWidth, dialogY + dialogHeight, 0xFF5A5A5A); // Right

        // Draw title
        int titleX = dialogX + (dialogWidth / 2) - (mc.font.width(title) / 2);
        guiGraphics.drawString(mc.font, title, titleX, dialogY + 15, 0xFFFFFF, false);

        // Draw message (word wrap if needed)
        int messageX = dialogX + 15;
        int messageY = dialogY + 40;
        int maxWidth = dialogWidth - 30;

        // Simple word wrap
        String messageText = message.getString();
        java.util.List<String> lines = wrapText(messageText, maxWidth);
        for (int i = 0; i < lines.size(); i++) {
            guiGraphics.drawString(mc.font, lines.get(i), messageX, messageY + (i * 12), 0xAAAAAA, false);
        }

        // Render buttons
        confirmButton.render(guiGraphics, mouseX, mouseY, partialTick);
        cancelButton.render(guiGraphics, mouseX, mouseY, partialTick);

        // Pop pose stack
        guiGraphics.pose().popPose();
    }

    /**
     * Handle mouse click
     */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (confirmButton.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (cancelButton.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

        // Click outside dialog = cancel
        if (mouseX < dialogX || mouseX > dialogX + dialogWidth ||
            mouseY < dialogY || mouseY > dialogY + dialogHeight) {
            onCancel.run();
            return true;
        }

        return false;
    }

    /**
     * Simple word wrap helper
     */
    private java.util.List<String> wrapText(String text, int maxWidth) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder currentLine = new StringBuilder();

        for (String word : words) {
            String testLine = currentLine.length() == 0 ? word : currentLine + " " + word;
            if (mc.font.width(testLine) <= maxWidth) {
                if (currentLine.length() > 0) currentLine.append(" ");
                currentLine.append(word);
            } else {
                if (currentLine.length() > 0) {
                    lines.add(currentLine.toString());
                }
                currentLine = new StringBuilder(word);
            }
        }

        if (currentLine.length() > 0) {
            lines.add(currentLine.toString());
        }

        return lines;
    }
}
