package com.quickskin.mod.client.gui.widget;

import com.mojang.blaze3d.systems.RenderSystem;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.TextureQuality;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Individual skin entry in the skin list
 */
@Environment(EnvType.CLIENT)
public class SkinEntry extends ContainerObjectSelectionList.Entry<SkinEntry> {

    private final Minecraft mc;
    private final AssetMetadata metadata;
    private final ResourceLocation textureLocation;
    private final SkinListWidget parentList;

    // Action button state
    private final int actionButtonSize = 11;
    private int deleteButtonX, deleteButtonY;
    private boolean isDeleteHovered;

    public SkinEntry(SkinListWidget parentList, AssetMetadata metadata) {
        this.parentList = parentList;
        this.metadata = metadata;
        this.mc = Minecraft.getInstance();

        // Get texture location from LocalAssetManager
        this.textureLocation = LocalAssetManager.getInstance()
            .getTextureLocation(metadata.hash(), TextureQuality.PREVIEW);
    }

    public AssetMetadata getMetadata() {
        return metadata;
    }

    public String getSortName() {
        return metadata.friendlyName();
    }

    @Override
    public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                      int mouseX, int mouseY, boolean isHovered, float partialTicks) {

        // Selection and hover highlight
        int highlightPaddingH = 4;
        int highlightPaddingV = 2;
        int highlightLeft = left - highlightPaddingH;
        int highlightRight = left + width - 10;
        int highlightTop = top - highlightPaddingV;
        int highlightBottom = top + height + highlightPaddingV;

        if (parentList.getSelected() == this) {
            // Selected state - blue highlight with border
            graphics.fill(highlightLeft, highlightTop, highlightRight, highlightBottom, 0x80308CC0);
            graphics.renderOutline(highlightLeft, highlightTop, highlightRight - highlightLeft,
                highlightBottom - highlightTop, 0xFF4080FF);
        } else if (isHovered) {
            // Hover state - subtle white highlight
            graphics.fill(highlightLeft, highlightTop, highlightRight, highlightBottom, 0x30FFFFFF);
        }

        // Render skin face preview
        int faceSize = height - 8;
        int faceX = left + 4;
        int faceY = top + 4;

        if (textureLocation != null) {
            RenderSystem.enableBlend();

            // Get texture dimensions for proper UV mapping
            int textureWidth = metadata.resolution().getWidth();
            int textureHeight = metadata.resolution().getHeight();

            // Scale UV coordinates proportionally for HD textures
            float scaleX = textureWidth / 64.0f;
            float scaleY = textureHeight / 64.0f;

            // Render face (front + overlay)
            graphics.blit(textureLocation, faceX, faceY, faceSize, faceSize,
                8.0f * scaleX, 8.0f * scaleY, (int)(8 * scaleX), (int)(8 * scaleY),
                textureWidth, textureHeight);
            graphics.blit(textureLocation, faceX, faceY, faceSize, faceSize,
                40.0f * scaleX, 8.0f * scaleY, (int)(8 * scaleX), (int)(8 * scaleY),
                textureWidth, textureHeight);

            RenderSystem.disableBlend();
        } else {
            // Fallback if texture not loaded
            graphics.fill(faceX, faceY, faceX + faceSize, faceY + faceSize, 0xFF333333);
            graphics.drawCenteredString(mc.font, "?", faceX + faceSize / 2,
                faceY + faceSize / 2 - 4, 0xFFFFFF);
        }

        // Render text info
        int textX = left + faceSize + 12;

        // Calculate max width for text to avoid overlapping buttons
        int buttonAreaWidth = actionButtonSize + 8;
        int textMaxWidth = (highlightRight - buttonAreaWidth) - textX;

        // Display name (truncated if needed)
        String displayName = getSortName();
        if (mc.font.width(displayName) > textMaxWidth) {
            displayName = mc.font.plainSubstrByWidth(displayName, textMaxWidth - mc.font.width("...")) + "...";
        }
        graphics.drawString(mc.font, displayName, textX, top + 6, 0xFFFFFF);

        // Model type and resolution
        String modelText = "slim".equalsIgnoreCase(metadata.skinModel()) ? "Slim" : "Classic";
        if (metadata.resolution().isHD()) {
            modelText += " • " + metadata.resolution().name();
        }
        graphics.drawString(mc.font, modelText, textX, top + 6 + mc.font.lineHeight + 2,
            metadata.resolution().isHD() ? 0x55FF55 : 0xAAAAAA);

        // Render action buttons on hover
        this.isDeleteHovered = false;
        if (isHovered) {
            int margin = 4;
            this.deleteButtonX = highlightRight - actionButtonSize - margin;
            this.deleteButtonY = highlightTop + margin;

            boolean deleteHovered = mouseX >= deleteButtonX && mouseX < deleteButtonX + actionButtonSize &&
                                   mouseY >= deleteButtonY && mouseY < deleteButtonY + actionButtonSize;

            graphics.fill(deleteButtonX, deleteButtonY,
                deleteButtonX + actionButtonSize, deleteButtonY + actionButtonSize,
                deleteHovered ? 0xA0E04040 : 0x80C00000);
            graphics.drawString(mc.font, "x", deleteButtonX + 3, deleteButtonY + 1, 0xFFFFFF);

            this.isDeleteHovered = deleteHovered;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (this.isDeleteHovered) {
                // TODO: Implement deletion confirmation dialog
                // For now, just play sound
                mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                    net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 0.5f
                ));
                return true;
            }

            // Select this skin
            mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 0.25f
            ));
            parentList.setSelected(this);
            parentList.onSkinSelected(this);
            return true;
        }
        return false;
    }

    @Override
    public List<? extends GuiEventListener> children() {
        return List.of();
    }

    @Override
    public List<? extends NarratableEntry> narratables() {
        return List.of();
    }
}
