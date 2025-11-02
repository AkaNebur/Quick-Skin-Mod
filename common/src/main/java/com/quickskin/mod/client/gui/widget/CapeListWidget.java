package com.quickskin.mod.client.gui.widget;

import com.quickskin.mod.client.gui.screen.PlayerCapeMenuScreen;
import com.quickskin.mod.common.data.AssetMetadata;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.network.chat.Component;

/**
 * Scrollable list widget for displaying available capes
 */
@Environment(EnvType.CLIENT)
public class CapeListWidget extends ContainerObjectSelectionList<CapeEntry> {

    private final PlayerCapeMenuScreen parentScreen;
    private final Minecraft mc;

    public CapeListWidget(PlayerCapeMenuScreen parentScreen, Minecraft mc, int width, int height,
                         int y, int entryHeight) {
        super(mc, width, height, y, y + height, entryHeight);
        this.parentScreen = parentScreen;
        this.mc = mc;
    }

    /**
     * Add a cape entry to the list
     */
    public int addCapeEntry(AssetMetadata metadata) {
        return this.addEntry(new CapeEntry(this, metadata));
    }

    /**
     * Clear all entries (public wrapper for protected method)
     */
    public void clearCapeEntries() {
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
    public void makeVisible(CapeEntry entry) {
        this.ensureVisible(entry);
    }

    /**
     * Called when a cape is selected
     */
    public void onCapeSelected(CapeEntry entry) {
        parentScreen.onCapeSelected(entry);
    }

    /**
     * Request deletion of a cape
     */
    public void requestDeletion(CapeEntry entry) {
        parentScreen.showDeleteConfirmation(entry.getMetadata());
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
            int areaHeight = areaBottom - areaTop;

            if (areaHeight > 50) {
                int textY = areaTop + (areaHeight / 2) - 10;

                graphics.drawCenteredString(
                    mc.font,
                    Component.literal("Drop cape files here or click Import"),
                    this.x0 + this.width / 2,
                    textY,
                    0x808080
                );

                graphics.drawCenteredString(
                    mc.font,
                    Component.literal("Supports: PNG, GIF (animated capes)"),
                    this.x0 + this.width / 2,
                    textY + 12,
                    0x606060
                );
            }
        }
    }

    public PlayerCapeMenuScreen getParentScreen() {
        return parentScreen;
    }
}
