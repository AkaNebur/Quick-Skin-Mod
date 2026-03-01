package com.quickskin.mod.client.gui.widget;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

@Environment(EnvType.CLIENT)
public class RotateButton extends Button {

    public RotateButton(int x, int y, int size, OnPress onPress) {
        super(x, y, size, size, Component.literal("↺"), onPress, DEFAULT_NARRATION);
    }

    @Override
    public void renderString(GuiGraphics pGuiGraphics, Font pFont, int pColor) {
        Component message = this.getMessage();
        // In 1.21.6, graphics.pose() returns Matrix3x2fStack, use new PoseStack for 3D transforms
        PoseStack poseStack = new PoseStack();
        poseStack.pushPose();

        float scale = 2.8F;
        float textWidth = pFont.width(message);

        // Translate to the center of the button to scale from that point
        poseStack.translate(this.getX() + this.getWidth() / 1.8F, this.getY() + this.getHeight() / 4F, 0);
        poseStack.scale(scale, scale, 1.0F);

        // Draw the string centered on the new (0, 0) origin
        pGuiGraphics.drawString(pFont, message, (int)(-textWidth / 2), (int)(-pFont.lineHeight / 2.0F + 1), pColor);

        poseStack.popPose();
    }

    @Override
    protected boolean isValidClickButton(int button) {
        // Only allow left-click
        return button == 0;
    }
}
