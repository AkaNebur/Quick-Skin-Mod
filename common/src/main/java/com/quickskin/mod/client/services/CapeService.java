package com.quickskin.mod.client.services;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.common.data.AnimationMetadata;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.KnownCapes;
import com.quickskin.mod.common.data.TextureQuality;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Service for managing player capes
 * Handles loading capes from Mojang API, local storage, and known capes
 */
@Environment(EnvType.CLIENT)
public class CapeService implements ICapeService {
    private static CapeService instance;

    private CapeService() {}

    public static CapeService getInstance() {
        if (instance == null) {
            instance = new CapeService();
        }
        return instance;
    }

    public static void init() {
        getInstance();
        QuickSkin.LOGGER.info("CapeService initialized");
    }

    @Override
    @Nullable
    public ResourceLocation getCapeLocation(UUID playerId, String capeId) {
        QuickSkin.LOGGER.info("[CapeService] getCapeLocation called: playerId={}, capeId={}", playerId, capeId);

        if (capeId == null || capeId.isEmpty()) {
            QuickSkin.LOGGER.info("[CapeService] capeId is null or empty, returning null");
            return null;
        }

        // Check if it's a local cape
        if (capeId.startsWith("local_cape:")) {
            QuickSkin.LOGGER.info("[CapeService] Loading local cape");
            String hash = capeId.substring("local_cape:".length());
            return loadLocalCape(hash);
        }

        // Check if it's a known cape
        if (capeId.startsWith("known:")) {
            QuickSkin.LOGGER.info("[CapeService] Loading known cape");
            String knownId = capeId.substring("known:".length());
            return loadKnownCape(knownId);
        }

        // Otherwise, it's a Mojang username
        QuickSkin.LOGGER.info("[CapeService] Loading Mojang cape");
        return loadMojangCape(capeId);
    }

    @Override
    @Nullable
    public ResourceLocation loadMojangCape(String username) {
        // Phase 5: Implement Mojang API cape loading
        QuickSkin.LOGGER.debug("Loading Mojang cape for: {}", username);

        // In a full implementation, this would fetch the player's cape from Mojang's API
        // using the PlayerInfo system to get the actual player's cape texture
        // For now, return null as capes are optional and require online fetching
        QuickSkin.LOGGER.debug("Mojang cape loading requires online API access - not implemented yet");
        return null;
    }

    @Override
    @Nullable
    public ResourceLocation loadLocalCape(String hash) {
        // Phase 5: Implement local cape loading
        QuickSkin.LOGGER.debug("Loading local cape: {}", hash);

        // Use LocalAssetManager to load the cape texture
        ResourceLocation capeLocation = LocalAssetManager.getInstance()
                .getTextureLocation(hash, TextureQuality.FULL);

        if (capeLocation != null) {
            // Check if this cape is animated and register it if not already running.
            AssetMetadata assetMeta = LocalAssetManager.getInstance().getMetadata(hash);
            if (assetMeta != null && assetMeta.isAnimated()) {
                String animationId = "cape_" + hash;
                String capeId = "local_cape:" + hash;
                AnimatedTextureManager animManager = AnimatedTextureManager.getInstance();

                if (!animManager.isAnimated(animationId)) {
                    AnimationMetadata animMeta = LocalAssetManager.getInstance().getAnimationMetadata(hash);
                    BufferedImage atlasImage = LocalAssetManager.getInstance().getSourceImage(hash);
                    if (animMeta != null && atlasImage != null) {
                        animManager.registerAnimation(animationId, capeId, capeLocation, atlasImage, animMeta);
                        QuickSkin.LOGGER.info("Registered animation for local cape: {}", hash);
                    } else {
                        QuickSkin.LOGGER.warn("Could not register animation for local cape {}: metadata or image was null.", hash);
                    }
                }
            }
            QuickSkin.LOGGER.debug("Loaded local cape: {}", hash);
        }

        return capeLocation;
    }

    @Override
    @Nullable
    public ResourceLocation loadKnownCape(String capeId) {
        QuickSkin.LOGGER.debug("Loading known cape: {}", capeId);

        // Look up the cape in the KnownCapes enum
        KnownCapes cape = KnownCapes.getById(capeId);

        if (cape != null && !cape.isNoCape()) {
            QuickSkin.LOGGER.debug("[CapeService] loadKnownCape: capeId={}, isAnimated={}", capeId, cape.isAnimated());

            if (cape.isAnimated()) {
                String animationId = "cape_known_" + capeId;
                AnimatedTextureManager animManager = AnimatedTextureManager.getInstance();

                QuickSkin.LOGGER.info("[CapeService] Animation check: animationId={}, alreadyRegistered={}",
                    animationId, animManager.isAnimated(animationId));

                // Only register the animation if it's not already running
                if (!animManager.isAnimated(animationId)) {
                    QuickSkin.LOGGER.info("[CapeService] Starting animation registration for: {}", capeId);
                    try {
                        ResourceLocation capeTexture = cape.getTextureLocation();
                        QuickSkin.LOGGER.info("[CapeService] Loading texture from: {}", capeTexture);

                        InputStream stream = Minecraft.getInstance().getResourceManager()
                                .getResource(capeTexture).get().open();
                        BufferedImage atlasImage = ImageIO.read(stream);
                        stream.close();

                        QuickSkin.LOGGER.info("[CapeService] Image loaded: {}x{}",
                            atlasImage != null ? atlasImage.getWidth() : "null",
                            atlasImage != null ? atlasImage.getHeight() : "null");

                        if (atlasImage != null) {
                            int width = atlasImage.getWidth();
                            int height = atlasImage.getHeight();
                            int frameHeight = width / 2; // Cape frames are 2:1 ratio
                            int frameCount = height / frameHeight;

                            QuickSkin.LOGGER.info("[CapeService] Calculated: width={}, height={}, frameHeight={}, frameCount={}",
                                width, height, frameHeight, frameCount);

                            if (frameCount > 1) {
                                // Create default frame metadata (50ms per frame)
                                List<AnimationMetadata.FrameData> frames = new ArrayList<>();
                                for (int i = 0; i < frameCount; i++) {
                                    frames.add(new AnimationMetadata.FrameData(50, i));
                                }
                                AnimationMetadata metadata = new AnimationMetadata(frames, frameCount);

                                QuickSkin.LOGGER.info("[CapeService] Calling registerAnimation...");
                                String fullCapeId = "known:" + capeId;
                                animManager.registerAnimation(animationId, fullCapeId, capeTexture, atlasImage, metadata);
                                QuickSkin.LOGGER.info("[CapeService] SUCCESS! Registered animation for KnownCape: {} ({} frames)", capeId, frameCount);
                            } else {
                                QuickSkin.LOGGER.warn("[CapeService] FAILED: frameCount <= 1, cannot register animation");
                            }
                        } else {
                            QuickSkin.LOGGER.warn("[CapeService] FAILED: atlasImage is null");
                        }
                    } catch (Exception e) {
                        QuickSkin.LOGGER.error("[CapeService] EXCEPTION during animation registration for: {}", capeId, e);
                    }
                } else {
                    QuickSkin.LOGGER.info("[CapeService] Animation already registered, skipping");
                }
            }

            ResourceLocation location = cape.getTextureLocation();
            QuickSkin.LOGGER.debug("Found known cape {} at {}", capeId, location);
            return location;
        }

        QuickSkin.LOGGER.debug("Unknown cape ID: {}", capeId);
        return null;
    }

    @Override
    public boolean isAnimated(String capeId) {
        if (capeId == null || capeId.isEmpty()) {
            return false;
        }

        // Check if it's a local cape
        if (capeId.startsWith("local_cape:")) {
            String hash = capeId.substring("local_cape:".length());

            // Check if the asset has animation metadata
            AssetMetadata metadata = LocalAssetManager.getInstance().getMetadata(hash);
            if (metadata != null && metadata.isAnimated()) {
                QuickSkin.LOGGER.debug("Cape {} is animated", hash);
                return true;
            }
        }

        // Check if it's a known cape
        if (capeId.startsWith("known:")) {
            String knownId = capeId.substring("known:".length());
            com.quickskin.mod.common.data.KnownCapes cape = com.quickskin.mod.common.data.KnownCapes.getById(knownId);
            if (cape != null) {
                return cape.isAnimated();
            }
        }

        // Mojang capes are not animated
        return false;
    }

    @Override
    public boolean hasLocalCape(String hash) {
        // Phase 5: Implement check using AssetService
        return LocalAssetManager.getInstance().getMetadata(hash) != null;
    }
}