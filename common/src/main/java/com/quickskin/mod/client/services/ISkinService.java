package com.quickskin.mod.client.services;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/**
 * Service interface for managing player skins
 */
@Environment(EnvType.CLIENT)
public interface ISkinService {

    /**
     * Gets the ResourceLocation for a player's skin
     * @param playerId The player's UUID
     * @param skinId The skin ID (e.g., "local_skin:hash" or "username")
     * @return The ResourceLocation for the skin, or null if not available
     */
    @Nullable
    ResourceLocation getSkinLocation(UUID playerId, String skinId);

    /**
     * Loads a skin from the Mojang API
     * @param username The player's username
     * @return The ResourceLocation for the skin, or null if failed
     */
    @Nullable
    ResourceLocation loadMojangSkin(String username);

    /**
     * Loads a local skin from storage
     * @param hash The skin's hash
     * @return The ResourceLocation for the skin, or null if not found
     */
    @Nullable
    ResourceLocation loadLocalSkin(String hash);

    /**
     * Checks if a local skin exists
     * @param hash The skin's hash
     * @return true if the skin exists
     */
    boolean hasLocalSkin(String hash);
}
