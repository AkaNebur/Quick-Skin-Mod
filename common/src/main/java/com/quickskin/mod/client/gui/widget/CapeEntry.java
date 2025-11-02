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
 * Individual cape entry in the cape list
 */
@Environment(EnvType.CLIENT)
public class CapeEntry extends ContainerObjectSelectionList.Entry<CapeEntry> {

    private final Minecraft mc;
    private final AssetMetadata metadata;
    private final ResourceLocation textureLocation;
    private final CapeListWidget parentList;

    // Action button state
    private final int actionButtonSize = 11;
    private int deleteButtonX, deleteButtonY;
    private boolean isDeleteHovered;

    public CapeEntry(CapeListWidget parentList, AssetMetadata metadata) {
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

        // Render cape preview (smaller than face preview)
        int capeWidth = (height - 8) * 3 / 4; // Aspect ratio for cape
        int capeHeight = height - 8;
        int capeX = left + 4;
        int capeY = top + 4;

        if (textureLocation != null) {
            RenderSystem.enableBlend();

            // Render cape texture preview
            // Cape texture dimensions: 64x32 (or HD multiples)
            int textureWidth = 64;
            int textureHeight = 32;

            if (metadata.resolution().isHD()) {
                int scale = metadata.resolution().getScale();
                textureWidth *= scale;
                textureHeight *= scale;
            }

            // Draw cape preview (use middle section of cape)
            graphics.blit(textureLocation, capeX, capeY, capeWidth, capeHeight,
                1.0f, 1.0f, 10, 16,
                textureWidth, textureHeight);

            RenderSystem.disableBlend();
        } else {
            // Fallback if texture not loaded
            graphics.fill(capeX, capeY, capeX + capeWidth, capeY + capeHeight, 0xFF333333);
            graphics.drawCenteredString(mc.font, "?", capeX + capeWidth / 2,
                capeY + capeHeight / 2 - 4, 0xFFFFFF);
        }

        // Render text info
        int textX = left + capeWidth + 12;

        // Calculate max width for text to avoid overlapping buttons
        int buttonAreaWidth = actionButtonSize + 8;
        int textMaxWidth = (highlightRight - buttonAreaWidth) - textX;

        // Display name (truncated if needed)
        String displayName = getSortName();
        if (mc.font.width(displayName) > textMaxWidth) {
            displayName = mc.font.plainSubstrByWidth(displayName, textMaxWidth - mc.font.width("...")) + "...";
        }
        graphics.drawString(mc.font, displayName, textX, top + 6, 0xFFFFFF);

        // Cape type and resolution
        String typeText = metadata.isAnimated() ? "Animated" : "Static";
        if (metadata.resolution().isHD()) {
            typeText += " • " + metadata.resolution().name();
        }
        graphics.drawString(mc.font, typeText, textX, top + 6 + mc.font.lineHeight + 2,
            metadata.isAnimated() ? 0xFFAA00 : 0xAAAAAA);

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
            // Check if delete button was clicked
            if (isDeleteHovered) {
                parentList.requestDeletion(this);
                return true;
            }

            // Otherwise, select this cape
            parentList.setSelected(this);
            parentList.onCapeSelected(this);
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
