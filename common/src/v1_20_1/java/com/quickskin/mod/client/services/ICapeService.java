package com.quickskin.mod.client.services;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;
import java.util.UUID;

/**
 * Service interface for managing player capes
 */
@Environment(EnvType.CLIENT)
public interface ICapeService {

    /**
     * Gets the ResourceLocation for a player's cape
     * @param playerId The player's UUID
     * @param capeId The cape ID (e.g., "local_cape:hash", "known:minecon2016", or "username")
     * @return The ResourceLocation for the cape, or null if not available
     */
    @Nullable
    ResourceLocation getCapeLocation(UUID playerId, String capeId);

    /**
     * Loads a cape from the Mojang API
     * @param username The player's username
     * @return The ResourceLocation for the cape, or null if failed
     */
    @Nullable
    ResourceLocation loadMojangCape(String username);

    /**
     * Loads a local cape from storage
     * @param hash The cape's hash
     * @return The ResourceLocation for the cape, or null if not found
     */
    @Nullable
    ResourceLocation loadLocalCape(String hash);

    /**
     * Loads a known cape (e.g., Minecon capes)
     * @param capeId The known cape ID (e.g., "minecon2016")
     * @return The ResourceLocation for the cape, or null if not found
     */
    @Nullable
    ResourceLocation loadKnownCape(String capeId);

    /**
     * Checks if a cape is animated
     * @param capeId The cape ID
     * @return true if the cape is animated
     */
    boolean isAnimated(String capeId);

}
