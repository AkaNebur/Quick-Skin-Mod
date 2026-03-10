package com.quickskin.mod.client.gui.widget;

import com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen;
import com.quickskin.mod.common.data.AssetMetadata;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.network.chat.Component;

/**
 * Scrollable list widget for displaying available skins
 */
@Environment(EnvType.CLIENT)
public class SkinListWidget extends ContainerObjectSelectionList<SkinEntry> {

    private final PlayerSkinMenuScreen parentScreen;
    private final Minecraft mc;
    private int xPosition;
    private final int itemHeight;

    public SkinListWidget(PlayerSkinMenuScreen parentScreen, Minecraft mc, int width, int height,
                         int y, int x, int entryHeight) {
        super(mc, width, height, y, entryHeight);
        this.parentScreen = parentScreen;
        this.mc = mc;
        this.xPosition = x;
        this.itemHeight = entryHeight;
        // Set the widget position
        this.setX(x);
    }

    /**
     * Add a skin entry to the list
     */
    public void addSkinEntry(AssetMetadata metadata) {
        this.addEntry(new SkinEntry(this, metadata));
    }

    /**
     * Clear all entries (public wrapper for protected method)
     */
    public void clearSkinEntries() {
        this.clearEntries();
    }

    /**
     * Make an entry visible by scrolling to it
     */
    public void makeVisible(SkinEntry entry) {
        this.ensureVisible(entry);
    }

    /**
     * Called when a skin is selected
     */
    public void onSkinSelected(SkinEntry entry) {
        parentScreen.onSkinSelected(entry);
    }

    @Override
    public int getRowWidth() {
        return this.width - 8;
    }

    @Override
    protected int scrollBarX() {
        return this.getRight() - 6;
    }

    @Override
    public int getX() {
        return xPosition;
    }

    @Override
    public int getRowLeft() {
        return xPosition + 4;
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        // Call parent to render the list entries
        super.renderWidget(graphics, mouseX, mouseY, partialTicks);

        // Show drop zone if list is mostly empty
        if (this.getItemCount() <= 2) {
            int rowTop = this.getItemCount() > 0 ? this.getRowTop(this.getItemCount() - 1) + this.itemHeight : this.getY();
            int areaTop = Math.max(rowTop, this.getY());
            int areaBottom = this.getBottom();

            // Only draw if there's enough empty space
            if (areaBottom - areaTop > 60) {
                int areaLeft = this.getRowLeft();
                int areaWidth = this.getRowWidth();
                int centerX = areaLeft + areaWidth / 2;
                int centerY = areaTop + (areaBottom - areaTop) / 2;

                // Calculate drop zone bounds with padding
                int zoneWidth = Math.min(areaWidth - 40, 300);
                int zoneHeight = Math.min(areaBottom - areaTop - 40, 140);
                int zoneX = centerX - zoneWidth / 2;
                int zoneY = centerY - zoneHeight / 2;

                // Check if mouse is hovering over the drop zone
                boolean isHovering = mouseX >= zoneX && mouseX <= zoneX + zoneWidth &&
                                    mouseY >= zoneY && mouseY <= zoneY + zoneHeight;

                // Draw drop zone background with hover effect
                int bgColor = isHovering ? 0x1AFFFFFF : 0x0FFFFFFF;
                graphics.fill(zoneX, zoneY, zoneX + zoneWidth, zoneY + zoneHeight, bgColor);

                // Draw dashed border
                drawDashedBorder(graphics, zoneX, zoneY, zoneWidth, zoneHeight, isHovering);

                // Draw text centered in the zone
                Component mainMessage = Component.translatable("quickskin.dropzone.skins.main");
                Component subMessage = Component.translatable("quickskin.dropzone.skins.sub");

                int mainColor = isHovering ? 0xFFFFFFFF : 0xFFE0E0E0;
                int subColor = isHovering ? 0xFFB0B0B0 : 0xFF909090;

                graphics.drawCenteredString(mc.font, mainMessage,
                    centerX, centerY - mc.font.lineHeight - 2, mainColor);
                graphics.drawCenteredString(mc.font, subMessage,
                    centerX, centerY + 2, subColor);
            }
        }
    }

    /**
     * Draws a dashed border around the drop zone
     */
    private void drawDashedBorder(GuiGraphics graphics, int x, int y, int width, int height, boolean highlight) {
        int color = highlight ? 0xFFFFFFFF : 0x80FFFFFF;
        int dashLength = 8;
        int gapLength = 4;
        int totalLength = dashLength + gapLength;

        // Top border
        for (int i = 0; i < width; i += totalLength) {
            int segmentLength = Math.min(dashLength, width - i);
            graphics.fill(x + i, y, x + i + segmentLength, y + 1, color);
        }

        // Bottom border
        for (int i = 0; i < width; i += totalLength) {
            int segmentLength = Math.min(dashLength, width - i);
            graphics.fill(x + i, y + height - 1, x + i + segmentLength, y + height, color);
        }

        // Left border
        for (int i = 0; i < height; i += totalLength) {
            int segmentLength = Math.min(dashLength, height - i);
            graphics.fill(x, y + i, x + 1, y + i + segmentLength, color);
        }

        // Right border
        for (int i = 0; i < height; i += totalLength) {
            int segmentLength = Math.min(dashLength, height - i);
            graphics.fill(x + width - 1, y + i, x + width, y + i + segmentLength, color);
        }
    }

    /**
     * Request deletion confirmation for a skin
     */
    public void requestDeletion(SkinEntry entry) {
        parentScreen.showDeleteConfirmation(entry.getMetadata());
    }

    /**
     * Request rename dialog for a skin
     */
    public void requestRename(SkinEntry entry) {
        parentScreen.showRenameDialog(entry.getMetadata());
    }

    /**
     * Request upload to Mojang for a skin
     */
    public void requestUploadToMojang(SkinEntry entry) {
        parentScreen.showUploadToMojangDialog(entry.getMetadata());
    }
}
