package com.quickskin.mod.client.services;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
    }

    @Override
    @Nullable
    public ResourceLocation getCapeLocation(UUID playerId, String capeId) {

        if (capeId == null || capeId.isEmpty()) {
            return null;
        }

        // Check if it's a local cape
        if (capeId.startsWith("local_cape:")) {
            String hash = capeId.substring("local_cape:".length());
            return loadLocalCape(hash);
        }

        // Check if it's a known cape
        if (capeId.startsWith("known:")) {
            String knownId = capeId.substring("known:".length());
            return loadKnownCape(knownId);
        }

        // Otherwise, it's a Mojang username
        return loadMojangCape(capeId);
    }

    @Override
    @Nullable
    public ResourceLocation loadMojangCape(String username) {
        // Mojang cape loading requires online API access - not implemented yet
        return null;
    }

    @Override
    @Nullable
    public ResourceLocation loadLocalCape(String hash) {
        // Check network cache first (for capes received from server)
        ResourceLocation capeLocation;
        if (com.quickskin.mod.client.storage.NetworkTextureCache.getInstance().hasTexture(hash)) {
            capeLocation = com.quickskin.mod.client.storage.NetworkTextureCache.getInstance()
                    .getTextureLocation(hash);
            if (capeLocation != null) {
                // Check if this network cape has animation metadata
                com.quickskin.mod.common.data.AnimationMetadata animMeta =
                    com.quickskin.mod.client.storage.ClientAnimationMetadataCache.getInstance().getMetadata(hash);

                if (animMeta != null) {
                    // Network cape is animated - register animation
                    // Use same animation ID format as local capes for consistency with renderer
                    String animationId = "cape_" + hash;
                    String capeId = "local_cape:" + hash;
                    AnimatedTextureManager animManager = AnimatedTextureManager.getInstance();

                    if (!animManager.isAnimated(animationId)) {
                        try {
                            // Get texture data and convert to BufferedImage
                            byte[] textureData = com.quickskin.mod.client.storage.NetworkTextureCache.getInstance()
                                .getTextureData(hash);
                            if (textureData != null) {
                                java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(textureData);
                                BufferedImage atlasImage = javax.imageio.ImageIO.read(bais);

                                if (atlasImage != null) {
                                    animManager.registerAnimation(animationId, capeId, capeLocation, atlasImage, animMeta);
                                } else {
                                    QuickSkin.LOGGER.warn("Could not read image for network cape animation: {}", hash);
                                }
                            }
                        } catch (Exception e) {
                            QuickSkin.LOGGER.error("Failed to register animation for network cape: {}", hash, e);
                        }
                    }
                }

                return capeLocation;
            }
        }

        // Fall back to local assets (for user's own capes)
        capeLocation = LocalAssetManager.getInstance()
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
                    } else {
                        QuickSkin.LOGGER.warn("Could not register animation for local cape {}: metadata or image was null.", hash);
                    }
                }
            }
        } else {
            // If not found locally and we're connected to a server, request it
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player != null && mc.getConnection() != null) {
                com.quickskin.mod.networking.NetworkSyncService.getInstance()
                    .requestTexture(mc.player.getUUID(), "cape", hash);
            }
        }

        return capeLocation;
    }

    @Override
    @Nullable
    public ResourceLocation loadKnownCape(String capeId) {
        // Look up the cape in the KnownCapes enum
        KnownCapes cape = KnownCapes.getById(capeId);

        if (cape != null && !cape.isNoCape()) {
            if (cape.isAnimated()) {
                String animationId = "cape_known_" + capeId;
                AnimatedTextureManager animManager = AnimatedTextureManager.getInstance();

                // Only register the animation if it's not already running
                if (!animManager.isAnimated(animationId)) {
                    try {
                        ResourceLocation capeTexture = cape.getTextureLocation();

                        InputStream stream = Minecraft.getInstance().getResourceManager()
                                .getResource(capeTexture).get().open();
                        BufferedImage atlasImage = ImageIO.read(stream);
                        stream.close();

                        if (atlasImage != null) {
                            int width = atlasImage.getWidth();
                            int height = atlasImage.getHeight();
                            int frameHeight = width / 2; // Cape frames are 2:1 ratio
                            int frameCount = height / frameHeight;

                            if (frameCount > 1) {
                                // Create default frame metadata (50ms per frame)
                                List<AnimationMetadata.FrameData> frames = new ArrayList<>();
                                for (int i = 0; i < frameCount; i++) {
                                    frames.add(new AnimationMetadata.FrameData(50, i));
                                }
                                AnimationMetadata metadata = new AnimationMetadata(frames, frameCount);

                                String fullCapeId = "known:" + capeId;
                                animManager.registerAnimation(animationId, fullCapeId, capeTexture, atlasImage, metadata);
                            } else {
                                QuickSkin.LOGGER.warn("[CapeService] FAILED: frameCount <= 1, cannot register animation");
                            }
                        } else {
                            QuickSkin.LOGGER.warn("[CapeService] FAILED: atlasImage is null");
                        }
                    } catch (Exception e) {
                        QuickSkin.LOGGER.error("[CapeService] EXCEPTION during animation registration for: {}", capeId, e);
                    }
                }
            }

            ResourceLocation location = cape.getTextureLocation();
            return location;
        }

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

}