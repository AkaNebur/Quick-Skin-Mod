package com.quickskin.mod.client.compat;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.gui.screen.PlayerSkinMenuScreen;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.mixin.compat.PasConfiguratorAccessor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Compatibility service for Player Armor Stands (PAS) mod integration.
 * Allows users to select skins from QuickSkin and apply them to armor stands.
 */
@Environment(EnvType.CLIENT)
public class PasCompatService {

    private static final String PAS_MOD_CLASS = "com.danrus.pas.PlayerArmorStands";
    private static final String PAS_CONFIGURATOR_CLASS = "com.danrus.pas.render.gui.PasConfiguratorScreen";

    // PAS limits filenames to 16 characters for local files
    private static final int PAS_MAX_FILENAME_LENGTH = 16;

    /**
     * Check if PAS mod is loaded
     */
    public static boolean isPasLoaded() {
        try {
            Class.forName(PAS_MOD_CLASS);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Get the PAS skins directory path.
     * PAS stores local skins in .minecraft/pas/skins/
     */
    public static Path getPasSkinDirectory() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("pas")
                .resolve("skins");
    }

    /**
     * Opens the QuickSkin skin selection screen in selection mode.
     * When a skin is selected, it will be copied to PAS's directory and
     * the PAS configurator screen will be updated directly.
     *
     * @param pasScreen The PAS configurator screen (passed as Screen for soft dependency)
     */
    public static void openSkinSelection(Screen pasScreen) {
        PlayerSkinMenuScreen skinScreen = new PlayerSkinMenuScreen(pasScreen);

        skinScreen.setSelectionCallback((metadata) -> {
            applySkinToPasScreen(pasScreen, metadata);
        });

        Minecraft.getInstance().setScreen(skinScreen);
    }

    /**
     * Applies the selected QuickSkin skin to the PAS configurator screen.
     * This copies the skin file to PAS's directory and updates the screen state directly.
     *
     * @param pasScreen The PAS configurator screen
     * @param metadata The selected skin metadata from QuickSkin
     */
    private static void applySkinToPasScreen(Screen pasScreen, AssetMetadata metadata) {
        try {
            // 1. Ensure PAS skins directory exists
            Path pasSkinDir = getPasSkinDirectory();
            if (!Files.exists(pasSkinDir)) {
                Files.createDirectories(pasSkinDir);
                QuickSkin.LOGGER.info("[PAS Compat] Created PAS skins directory: {}", pasSkinDir);
            }

            // 2. Generate a valid PAS filename from the friendly name
            // PAS only allows A-Z, a-z, 0-9, and underscores, max 16 chars.
            String cleanName = metadata.friendlyName().replaceAll("[^a-zA-Z0-9_]", "");

            // If the name is empty after cleaning (e.g. skin was named "---"), fall back to hash
            if (cleanName.isEmpty()) {
                cleanName = metadata.hash();
            }

            // Truncate to 16 characters (PAS limit)
            String shortName = cleanName.length() > PAS_MAX_FILENAME_LENGTH
                    ? cleanName.substring(0, PAS_MAX_FILENAME_LENGTH)
                    : cleanName;

            String targetFileName = shortName + ".png";
            Path targetPath = pasSkinDir.resolve(targetFileName);

            // 3. Copy the skin from QuickSkin to PAS
            byte[] skinData = LocalAssetManager.getInstance().loadTexture(metadata.hash(), TextureQuality.FULL);

            if (skinData != null) {
                // Write the file (overwriting if it exists is usually fine here as the user explicitly selected it)
                Files.write(targetPath, skinData);
                QuickSkin.LOGGER.info("[PAS Compat] Copied skin '{}' to PAS directory as {}",
                        metadata.friendlyName(), targetFileName);

                // 4. Update the PAS screen state directly using accessor mixin
                updatePasScreenState(pasScreen, metadata, shortName);

            } else {
                QuickSkin.LOGGER.error("[PAS Compat] Failed to load skin data for hash: {}", metadata.hash());
            }

        } catch (IOException e) {
            QuickSkin.LOGGER.error("[PAS Compat] Failed to copy skin to PAS directory", e);
        }
    }

    /**
     * Updates the PAS configurator screen state directly.
     * Uses the PasConfiguratorAccessor mixin to set the internal fields.
     *
     * @param pasScreen The PAS configurator screen
     * @param metadata The skin metadata
     * @param shortName The truncated filename (max 16 chars)
     */
    private static void updatePasScreenState(Screen pasScreen, AssetMetadata metadata, String shortName) {
        try {
            // Check if this is a PasConfiguratorScreen
            if (!pasScreen.getClass().getName().equals(PAS_CONFIGURATOR_CLASS)) {
                QuickSkin.LOGGER.warn("[PAS Compat] Screen is not PasConfiguratorScreen: {}",
                        pasScreen.getClass().getName());
                return;
            }

            // Cast to accessor interface
            PasConfiguratorAccessor accessor = (PasConfiguratorAccessor) pasScreen;

            // Set the entity name to the truncated name (max 16 chars)
            accessor.quickskin$setEntityName(shortName);
            QuickSkin.LOGGER.info("[PAS Compat] Set entityName to: {}", shortName);

            // Set the skin provider to "F" (File)
            accessor.quickskin$setSkinProvider("F");
            QuickSkin.LOGGER.info("[PAS Compat] Set skinProvider to: F");

            // Determine and set the slim/wide model
            String fullHash = metadata.hash();
            String modelPreference = LocalAssetManager.getInstance().getSkinModelPreference(fullHash);
            boolean isSlim;
            if ("slim".equalsIgnoreCase(modelPreference)) {
                isSlim = true;
            } else if ("classic".equalsIgnoreCase(modelPreference)) {
                isSlim = false;
            } else {
                // Auto - use the skin's detected model
                isSlim = "slim".equalsIgnoreCase(metadata.skinModel());
            }
            accessor.quickskin$setIsSlim(isSlim);
            QuickSkin.LOGGER.info("[PAS Compat] Set isSlim to: {}", isSlim);

            // Force the screen to refresh by setting the screen to itself
            // This triggers the standard initialization chain properly
            Minecraft.getInstance().setScreen(pasScreen);
            QuickSkin.LOGGER.info("[PAS Compat] Refreshed PAS screen");

            QuickSkin.LOGGER.info("[PAS Compat] Successfully applied skin to armor stand: {}", shortName);

        } catch (Exception e) {
            QuickSkin.LOGGER.error("[PAS Compat] Failed to update PAS screen state", e);
        }
    }

    /**
     * Checks if a skin with the given name already exists in PAS's skin directory.
     * Uses the same sanitization logic as applySkinToPasScreen to check existence correctly.
     *
     * @param metadata The skin metadata
     * @return true if the skin file exists in PAS directory
     */
    public static boolean skinExistsInPas(AssetMetadata metadata) {
        // Must perform same sanitization logic to check existence
        String cleanName = metadata.friendlyName().replaceAll("[^a-zA-Z0-9_]", "");
        if (cleanName.isEmpty()) {
            cleanName = metadata.hash();
        }

        String shortName = cleanName.length() > PAS_MAX_FILENAME_LENGTH
                ? cleanName.substring(0, PAS_MAX_FILENAME_LENGTH)
                : cleanName;

        Path pasSkinDir = getPasSkinDirectory();
        Path skinPath = pasSkinDir.resolve(shortName + ".png");
        return Files.exists(skinPath);
    }
}
