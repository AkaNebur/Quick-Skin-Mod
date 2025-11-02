package com.quickskin.mod.client.gui.screen;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.gui.widget.CapeEntry;
import com.quickskin.mod.client.gui.widget.CapeListWidget;
import com.quickskin.mod.client.gui.widget.ConfirmationDialog;
import com.quickskin.mod.client.gui.widget.PlayerWidget;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.data.AssetMetadata;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.util.List;

/**
 * Cape selection menu for QuickSkin
 * Similar to PlayerSkinMenuScreen but for capes
 */
@Environment(EnvType.CLIENT)
public class PlayerCapeMenuScreen extends Screen {

    @Nullable
    private final Screen parent;

    private CapeListWidget capeListWidget;
    private PlayerWidget playerWidget;
    private Button importButton;
    private Button applyButton;
    private Button removeButton;

    @Nullable
    private ConfirmationDialog confirmationDialog;

    @Nullable
    private CapeEntry selectedCape;

    public PlayerCapeMenuScreen(@Nullable Screen parent) {
        super(Component.literal("Cape Selection"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        // Cape list widget (left side)
        int listWidth = this.width / 2 - 20;
        int listHeight = this.height - 80;
        capeListWidget = new CapeListWidget(this, minecraft, listWidth, listHeight, 40, 40);
        this.addRenderableWidget(capeListWidget);

        // Load capes from LocalAssetManager
        refreshCapeList();

        // Player preview widget (right side)
        int previewSize = Math.min(200, listHeight - 40);
        int previewX = this.width / 2 + 10 + (this.width / 2 - 20 - previewSize) / 2;
        int previewY = 50;

        LocalPlayer player = Minecraft.getInstance().player;
        ResourceLocation skinLocation = player != null ? player.getSkinTextureLocation()
            : new ResourceLocation("minecraft", "textures/entity/player/wide/steve.png");
        String modelType = player != null ? player.getModelName() : "default";
        if ("default".equals(modelType)) {
            modelType = "classic";
        }

        playerWidget = new PlayerWidget(previewX, previewY, previewSize, previewSize,
            skinLocation, null, modelType);
        this.addRenderableWidget(playerWidget);

        // Buttons
        int buttonY = this.height - 35;
        int buttonWidth = 100;

        // Import button
        importButton = Button.builder(
            Component.literal("Import Cape"),
            button -> importCape()
        ).bounds(10, buttonY, buttonWidth, 20).build();
        this.addRenderableWidget(importButton);

        // Apply button
        applyButton = Button.builder(
            Component.literal("Apply"),
            button -> applyCape()
        ).bounds(this.width / 2 - buttonWidth - 5, buttonY, buttonWidth, 20).build();
        applyButton.active = false;
        this.addRenderableWidget(applyButton);

        // Remove cape button
        removeButton = Button.builder(
            Component.literal("Remove Cape"),
            button -> removeCape()
        ).bounds(this.width / 2 + 5, buttonY, buttonWidth, 20).build();
        this.addRenderableWidget(removeButton);

        // Close button
        Button closeButton = Button.builder(
            Component.literal("Close"),
            button -> this.onClose()
        ).bounds(this.width - buttonWidth - 10, buttonY, buttonWidth, 20).build();
        this.addRenderableWidget(closeButton);
    }

    private void refreshCapeList() {
        capeListWidget.clearCapeEntries();

        List<AssetMetadata> capes = LocalAssetManager.getInstance()
            .getAssetsByType("cape");

        for (AssetMetadata cape : capes) {
            capeListWidget.addCapeEntry(cape);
        }

        QuickSkin.LOGGER.debug("Loaded {} capes", capes.size());
    }

    public void onCapeSelected(CapeEntry entry) {
        this.selectedCape = entry;
        this.applyButton.active = true;

        // Update player preview with selected cape
        if (entry != null) {
            ResourceLocation capeLocation = LocalAssetManager.getInstance()
                .getTextureLocation(entry.getMetadata().hash(),
                    com.quickskin.mod.common.data.TextureQuality.FULL);
            playerWidget.setCape(capeLocation);
        }
    }

    private void importCape() {
        // TODO: Implement file picker for cape import
        QuickSkin.LOGGER.info("Import cape clicked (file picker not implemented yet)");
    }

    private void applyCape() {
        if (selectedCape == null || minecraft == null || minecraft.player == null) {
            return;
        }

        String capeId = "local_cape:" + selectedCape.getMetadata().hash();
        PlayerAppearanceService.getInstance()
            .applyCape(minecraft.player.getUUID(), capeId);

        QuickSkin.LOGGER.info("Applied cape: {}", selectedCape.getMetadata().friendlyName());
    }

    private void removeCape() {
        if (minecraft == null || minecraft.player == null) {
            return;
        }

        PlayerAppearanceService.getInstance()
            .applyCape(minecraft.player.getUUID(), "");

        playerWidget.setCape(null);
        QuickSkin.LOGGER.info("Removed cape");
    }

    public void showDeleteConfirmation(AssetMetadata metadata) {
        confirmationDialog = new ConfirmationDialog(
            Component.literal("Delete Cape?"),
            Component.literal("Are you sure you want to delete '" + metadata.friendlyName() + "'?"),
            () -> deleteCape(metadata),
            () -> confirmationDialog = null
        );
    }

    private void deleteCape(AssetMetadata metadata) {
        try {
            Files.deleteIfExists(metadata.path());
            LocalAssetManager.getInstance().discoverLocalAssets();
            refreshCapeList();
            confirmationDialog = null;
            selectedCape = null;
            applyButton.active = false;
            QuickSkin.LOGGER.info("Deleted cape: {}", metadata.friendlyName());
        } catch (Exception e) {
            QuickSkin.LOGGER.error("Failed to delete cape", e);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Render background
        this.renderBackground(graphics);

        // Render title
        graphics.drawCenteredString(
            this.font,
            this.title,
            this.width / 2,
            15,
            0xFFFFFF
        );

        // Render confirmation dialog if present
        if (confirmationDialog != null) {
            confirmationDialog.render(graphics, mouseX, mouseY, 0);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Handle confirmation dialog clicks first
        if (confirmationDialog != null) {
            return confirmationDialog.mouseClicked(mouseX, mouseY, button);
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }
}
