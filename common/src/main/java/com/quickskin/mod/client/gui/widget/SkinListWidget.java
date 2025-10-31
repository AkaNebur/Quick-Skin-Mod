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

    public SkinListWidget(PlayerSkinMenuScreen parentScreen, Minecraft mc, int width, int height,
                         int y, int entryHeight) {
        super(mc, width, height, y, y + height, entryHeight);
        this.parentScreen = parentScreen;
        this.mc = mc;
    }

    /**
     * Add a skin entry to the list
     */
    public int addSkinEntry(AssetMetadata metadata) {
        return this.addEntry(new SkinEntry(this, metadata));
    }

    /**
     * Clear all entries (public wrapper for protected method)
     */
    public void clearSkinEntries() {
        this.clearEntries();
    }

    /**
     * Remove all entries from the list
     */
    public void removeAllEntries() {
        this.children().clear();
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
    protected int getScrollbarPosition() {
        return this.x1 - 6;
    }

    @Override
    protected void renderList(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.renderList(graphics, mouseX, mouseY, partialTicks);

        // Show drop zone if list is mostly empty
        if (this.getItemCount() <= 2) {
            int rowTop = this.getItemCount() > 0 ? this.getRowTop(this.getItemCount()) : this.y0;
            int areaTop = Math.max(rowTop, this.y0);
            int areaBottom = this.y1;

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
                Component mainMessage = Component.literal("Drop skin files here");
                Component subMessage = Component.literal("or click 'Import Skin'");

                int mainColor = isHovering ? 0xFFFFFF : 0xE0E0E0;
                int subColor = isHovering ? 0xB0B0B0 : 0x909090;

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
}
