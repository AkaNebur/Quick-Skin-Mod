package com.quickskin.mod.client.gui.panel;

import com.quickskin.mod.client.gui.widget.SkinEntry;
import com.quickskin.mod.client.gui.widget.SkinListWidget;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.common.data.AssetMetadata;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/**
 * Panel that manages the skin list widget on the left side of the screen
 */
public class SkinListPanel extends AbstractWidget {

    @Nullable
    private SkinListWidget skinListWidget;

    private final Minecraft minecraft;
    private final Consumer<SkinEntry> onSkinSelectedCallback;

    public SkinListPanel(int x, int y, int width, int height, Minecraft minecraft, Consumer<SkinEntry> onSkinSelectedCallback) {
        super(x, y, width, height, Component.empty());
        this.minecraft = minecraft;
        this.onSkinSelectedCallback = onSkinSelectedCallback;
    }

    /**
     * Initialize the panel and create the skin list widget
     */
    public void init(com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen screen) {
        // Create skin list widget
        skinListWidget = new SkinListWidget(
            screen,
            minecraft,
            width,
            height,
            getY(),
            40 // Entry height - matches original
        );
        skinListWidget.setLeftPos(getX());
        skinListWidget.setRenderBackground(false);
        skinListWidget.setRenderTopAndBottom(false);
        screen.registerWidget(skinListWidget);

        // Load skins from LocalAssetManager
        loadSkins();
    }

    /**
     * Load skins from LocalAssetManager
     */
    private void loadSkins() {
        if (skinListWidget == null) {
            return;
        }

        LocalAssetManager assetManager = LocalAssetManager.getInstance();
        List<AssetMetadata> skins = assetManager.getAllSkins();

        for (AssetMetadata metadata : skins) {
            skinListWidget.addSkinEntry(metadata);
        }
    }

    /**
     * Refresh the skin list after importing
     */
    public void refresh() {
        if (skinListWidget == null) {
            return;
        }

        // Clear and reload
        skinListWidget.removeAllEntries();
        loadSkins();
    }

    /**
     * Set the selected skin programmatically
     */
    public void setSelected(AssetMetadata metadata) {
        if (skinListWidget == null || metadata == null) {
            return;
        }

        // Find and select the entry
        for (int i = 0; i < skinListWidget.children().size(); i++) {
            SkinEntry entry = (SkinEntry) skinListWidget.children().get(i);
            if (entry.getMetadata().hash().equals(metadata.hash())) {
                skinListWidget.setSelected(entry);
                onSkinSelectedCallback.accept(entry);
                skinListWidget.makeVisible(entry);
                break;
            }
        }
    }

    /**
     * Get the skin list widget
     */
    @Nullable
    public SkinListWidget getSkinListWidget() {
        return skinListWidget;
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // This panel doesn't render anything itself - child widgets handle rendering
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
        // No narration needed for container panel
    }
}
