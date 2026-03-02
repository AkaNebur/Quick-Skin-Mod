package com.quickskin.mod.neoforge.mixin;

import com.mojang.authlib.GameProfile;
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NeoForge-specific mixin on SkinManager to intercept skin resolution at the canonical level.
 * Uses Mojmap names directly since NeoForge uses Mojmap at runtime.
 *
 * This catches ALL skin lookups including those by mods like Essential that bypass
 * AbstractClientPlayer.getSkin() and PlayerRenderer.getTextureLocation() entirely.
 *
 * Two injection points:
 * - getInsecureSkin(GameProfile) — synchronous, used by vanilla code paths
 * - getOrLoad(GameProfile) — async (CompletableFuture), used by Essential's FallbackPlayer on 1.20.2+
 *
 * Essential for MC >= 1.20.2 uses FallbackPlayer which calls getOrLoad() directly,
 * bypassing getInsecureSkin(). The getOrLoad mixin wraps the future with thenApply
 * so the skin override propagates to both paths.
 */
@Mixin(SkinManager.class)
public class SkinManagerMixin {

    // Cache for HttpTexture-backed ResourceLocations (for CPM compat)
    @Unique
    private static final Map<String, ResourceLocation> quickskin$httpTextureCache = new ConcurrentHashMap<>();

    /**
     * Shared helper that applies QuickSkin overrides to a PlayerSkin.
     * Used by both getInsecureSkin and getOrLoad mixin handlers.
     */
    @Unique
    private static PlayerSkin quickskin$applyOverrides(PlayerSkin original, UUID uuid, String profileName) {
        if (original == null || uuid == null) return original;

        PlayerAppearanceService service = PlayerAppearanceService.getInstance();
        if (service == null) return original;

        boolean hasCustomSkin = service.hasActiveSkin(uuid);
        boolean hasCustomCape = service.hasActiveCape(uuid);
        boolean hasModelOverride = service.hasModelOverride(uuid);

        // Try service-based overrides
        if (hasCustomSkin || hasCustomCape || hasModelOverride) {
            ResourceLocation skinTexture = original.texture();
            PlayerSkin.Model skinModel = original.model();
            ResourceLocation capeTexture = original.capeTexture();
            boolean anyOverride = false;

            if (hasCustomSkin) {
                ResourceLocation customSkin;
                if (CPMCompatIntegration.isAvailable()) {
                    // When CPM is installed, register skin as HttpTexture so CPM can read pixel data
                    customSkin = quickskin$getOrRegisterHttpTexture(uuid, service);
                } else {
                    customSkin = service.getSkinLocation(uuid);
                }
                if (customSkin != null) {
                    skinTexture = customSkin;
                    anyOverride = true;
                }
            }

            if (hasCustomSkin || hasModelOverride) {
                String customModel = service.getModelName(uuid);
                if (customModel != null) {
                    skinModel = "slim".equals(customModel) ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE;
                    anyOverride = true;
                }
            }

            if (hasCustomCape) {
                ResourceLocation customCape = service.getCapeLocation(uuid);
                if (customCape != null) {
                    capeTexture = customCape;
                    anyOverride = true;
                } else {
                    com.quickskin.mod.common.data.PlayerAppearance appearance = service.getAppearance(uuid);
                    if (appearance != null && ("__NONE__".equals(appearance.getCapeId()) || appearance.getCapeId().isEmpty())) {
                        capeTexture = null;
                        anyOverride = true;
                    }
                }
            }

            if (anyOverride) {
                return new PlayerSkin(
                        skinTexture,
                        original.textureUrl(),
                        capeTexture,
                        original.elytraTexture(),
                        skinModel,
                        original.secure()
                );
            }
        }

        // Config-based fallback for local player (title screen and in-world)
        boolean isLocalPlayer = uuid.equals(Minecraft.getInstance().getUser().getProfileId());
        if (isLocalPlayer) {
            ClientConfig config = ClientConfig.getInstance();
            boolean hasSkin = !config.activeSkinHash.isEmpty();
            boolean hasCape = !config.activeCapeHash.isEmpty();

            if (hasSkin || hasCape) {
                ResourceLocation skinTexture = original.texture();
                PlayerSkin.Model skinModel = original.model();
                ResourceLocation capeTexture = original.capeTexture();
                boolean anyOverride = false;

                if (hasSkin) {
                    ResourceLocation loc = LocalAssetManager.getInstance()
                            .getTextureLocation(config.activeSkinHash, TextureQuality.FULL);
                    if (loc != null) {
                        skinTexture = loc;
                        String modelType = LocalAssetManager.getInstance().getSkinModelPreference(config.activeSkinHash);
                        if ("auto".equals(modelType)) {
                            var metadata = LocalAssetManager.getInstance().getMetadata(config.activeSkinHash);
                            if (metadata != null) {
                                modelType = metadata.skinModel();
                            }
                        }
                        skinModel = "slim".equals(modelType) ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE;
                        anyOverride = true;
                    }
                }

                if (hasCape) {
                    ResourceLocation capeLoc = com.quickskin.mod.client.services.CapeService.getInstance()
                            .getCapeLocation(null, config.activeCapeHash);
                    if (capeLoc != null) {
                        capeTexture = capeLoc;
                        anyOverride = true;
                    }
                }

                if (anyOverride) {
                    return new PlayerSkin(
                            skinTexture,
                            original.textureUrl(),
                            capeTexture,
                            original.elytraTexture(),
                            skinModel,
                            original.secure()
                    );
                }
            }
        }

        return original;
    }

    /**
     * When CPM is installed, registers the QuickSkin skin file as an HttpTexture
     * so that CPM can extract the file path and read embedded pixel data (3D model).
     * CPM's skin loading pipeline checks `instanceof HttpTexture` and reads from the file field.
     *
     * Uses reflection to access HttpTexture since the class may not exist in all MC versions.
     */
    @Unique
    private static ResourceLocation quickskin$getOrRegisterHttpTexture(UUID uuid, PlayerAppearanceService service) {
        com.quickskin.mod.common.data.PlayerAppearance appearance = service.getAppearance(uuid);
        if (appearance == null) return null;

        String skinId = appearance.getSkinId();
        if (skinId == null || skinId.isEmpty()) return null;

        // Extract hash from skinId (format: "local_skin:hash")
        String hash = null;
        if (skinId.startsWith("local_skin:")) {
            hash = skinId.substring("local_skin:".length());
        }

        if (hash == null || hash.isEmpty()) {
            // Not a local skin (could be network skin) - fall back to DynamicTexture
            return service.getSkinLocation(uuid);
        }

        // Check cache first
        ResourceLocation cached = quickskin$httpTextureCache.get(hash);
        if (cached != null) {
            // Verify it's still registered in TextureManager
            // Use reflection-safe check: getTexture(ResourceLocation) without default parameter
            AbstractTexture existing = Minecraft.getInstance().getTextureManager().getTexture(cached);
            if (existing != null) {
                return cached;
            }
            quickskin$httpTextureCache.remove(hash);
        }

        // Find the skin file on disk
        Path sourcePath = LocalAssetManager.getInstance().getSourcePath(hash);
        if (sourcePath == null || !sourcePath.toFile().exists()) {
            // File not found, fall back to DynamicTexture
            return service.getSkinLocation(uuid);
        }

        // Create an HttpTexture pointing to the local file using reflection
        // since HttpTexture may not exist in all MC versions
        File skinFile = sourcePath.toFile();
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(
                QuickSkin.MOD_ID,
                "cpm_bridge/" + hash
        );

        try {
            Class<?> httpTextureClass = Class.forName("net.minecraft.client.renderer.texture.HttpTexture");
            // HttpTexture constructor: (File file, String urlString, ResourceLocation fallback, boolean processLegacySkin, Runnable onDownloaded)
            java.lang.reflect.Constructor<?> constructor = httpTextureClass.getConstructor(
                    File.class, String.class, ResourceLocation.class, boolean.class, Runnable.class
            );
            Object httpTexture = constructor.newInstance(
                    skinFile,
                    "file:///" + skinFile.getAbsolutePath().replace('\\', '/'),
                    ResourceLocation.withDefaultNamespace("textures/entity/player/wide/steve.png"),
                    true,
                    (Runnable) () -> {}
            );

            Minecraft.getInstance().getTextureManager().register(location, (AbstractTexture) httpTexture);
            quickskin$httpTextureCache.put(hash, location);

            QuickSkin.LOGGER.info("[CPM Compat] Registered HttpTexture bridge for skin hash={} file={}", hash, skinFile.getAbsolutePath());

            return location;
        } catch (ClassNotFoundException e) {
            // HttpTexture class doesn't exist in this MC version, fall back to DynamicTexture
            QuickSkin.LOGGER.debug("[CPM Compat] HttpTexture class not found, falling back to DynamicTexture");
            return service.getSkinLocation(uuid);
        } catch (Exception e) {
            QuickSkin.LOGGER.warn("[CPM Compat] Failed to create HttpTexture bridge, falling back to DynamicTexture", e);
            return service.getSkinLocation(uuid);
        }
    }

    /**
     * Intercept getInsecureSkin (synchronous path).
     * Used by vanilla code and any mod that calls SkinManager.getInsecureSkin() directly.
     */
    @Inject(method = "getInsecureSkin", at = @At("RETURN"), cancellable = true)
    private void quickskin$modifyInsecureSkin(GameProfile profile, CallbackInfoReturnable<PlayerSkin> cir) {
        UUID uuid = profile.getId();
        if (uuid == null) return;

        PlayerSkin result = quickskin$applyOverrides(cir.getReturnValue(), uuid, profile.getName());
        if (result != cir.getReturnValue()) {
            cir.setReturnValue(result);
        }
    }

    /**
     * Intercept getOrLoad (async path returning CompletableFuture).
     * In MC 1.21.5+, getOrLoad returns CompletableFuture<Optional<PlayerSkin>>.
     */
    @Inject(method = "getOrLoad", at = @At("RETURN"), cancellable = true)
    private void quickskin$modifyGetOrLoad(GameProfile profile, CallbackInfoReturnable<CompletableFuture<Optional<PlayerSkin>>> cir) {
        UUID uuid = profile.getId();
        if (uuid == null) return;

        PlayerAppearanceService service = PlayerAppearanceService.getInstance();
        boolean hasServiceOverrides = false;
        boolean hasTitleScreenFallback = false;

        if (service != null) {
            hasServiceOverrides = service.hasActiveSkin(uuid)
                    || service.hasActiveCape(uuid)
                    || service.hasModelOverride(uuid);
        }

        boolean isLocalPlayer = uuid.equals(Minecraft.getInstance().getUser().getProfileId());
        if (!hasServiceOverrides && isLocalPlayer) {
            ClientConfig config = ClientConfig.getInstance();
            hasTitleScreenFallback = !config.activeSkinHash.isEmpty() || !config.activeCapeHash.isEmpty();
        }

        // Only wrap the future if we actually have overrides to apply
        if (!hasServiceOverrides && !hasTitleScreenFallback) return;

        String profileName = profile.getName();
        CompletableFuture<Optional<PlayerSkin>> original = cir.getReturnValue();
        CompletableFuture<Optional<PlayerSkin>> modified = original.thenApply(optSkin ->
            optSkin.map(skin -> quickskin$applyOverrides(skin, uuid, profileName))
        );
        cir.setReturnValue(modified);
    }
}
