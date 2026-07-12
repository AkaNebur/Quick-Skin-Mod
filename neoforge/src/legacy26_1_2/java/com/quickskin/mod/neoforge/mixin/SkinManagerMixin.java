package com.quickskin.mod.neoforge.mixin;

import com.mojang.authlib.GameProfile;
import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.ClientAsset;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
            Identifier skinTexture = original.body().texturePath();
            PlayerModelType skinModel = original.model();
            Identifier capeTexture = original.cape() != null ? original.cape().texturePath() : null;
            boolean anyOverride = false;

            if (hasCustomSkin) {
                // CPM 1.21.11+ bypasses registered player textures. Activate the
                // degraded-capability log, then keep QuickSkin's normal texture.
                CPMCompatIntegration.isAvailable();
                Identifier customSkin = service.getSkinLocation(uuid);
                if (customSkin != null) {
                    skinTexture = customSkin;
                    anyOverride = true;
                }
            }

            if (hasCustomSkin || hasModelOverride) {
                String customModel = service.getModelName(uuid);
                if (customModel != null) {
                    skinModel = "slim".equals(customModel) ? PlayerModelType.SLIM : PlayerModelType.WIDE;
                    anyOverride = true;
                }
            }

            if (hasCustomCape) {
                Identifier customCape = service.getCapeLocation(uuid);
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
                        new ClientAsset.ResourceTexture(skinTexture, skinTexture),
                        capeTexture != null ? new ClientAsset.ResourceTexture(capeTexture, capeTexture) : null,
                        original.elytra(),
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
                Identifier skinTexture = original.body().texturePath();
                PlayerModelType skinModel = original.model();
                Identifier capeTexture = original.cape() != null ? original.cape().texturePath() : null;
                boolean anyOverride = false;

                if (hasSkin) {
                    Identifier loc = LocalAssetManager.getInstance()
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
                        skinModel = "slim".equals(modelType) ? PlayerModelType.SLIM : PlayerModelType.WIDE;
                        anyOverride = true;
                    }
                }

                if (hasCape) {
                    Identifier capeLoc = com.quickskin.mod.client.services.CapeService.getInstance()
                            .getCapeLocation(null, config.activeCapeHash);
                    if (capeLoc != null) {
                        capeTexture = capeLoc;
                        anyOverride = true;
                    }
                }

                if (anyOverride) {
                    return new PlayerSkin(
                            new ClientAsset.ResourceTexture(skinTexture, skinTexture),
                            capeTexture != null ? new ClientAsset.ResourceTexture(capeTexture, capeTexture) : null,
                            original.elytra(),
                            skinModel,
                            original.secure()
                    );
                }
            }
        }

        return original;
    }

    /**
     * Intercept getInsecureSkin (synchronous path).
     * Used by vanilla code and any mod that calls SkinManager.getInsecureSkin() directly.
     */
    @Inject(method = "getInsecureSkin", at = @At("RETURN"), cancellable = true)
    private void quickskin$modifyInsecureSkin(GameProfile profile, CallbackInfoReturnable<PlayerSkin> cir) {
        UUID uuid = profile.id();
        if (uuid == null) return;

        PlayerSkin result = quickskin$applyOverrides(cir.getReturnValue(), uuid, profile.name());
        if (result != cir.getReturnValue()) {
            cir.setReturnValue(result);
        }
    }

    /**
     * Intercept getOrLoad (async path returning CompletableFuture).
     *
     * Essential for MC >= 1.20.2 uses FallbackPlayer which calls getOrLoad() directly,
     * bypassing getInsecureSkin(). We wrap the returned future with thenApply to apply
     * QuickSkin overrides when the future resolves.
     */
    @Inject(method = "getOrLoad", at = @At("RETURN"), cancellable = true)
    private void quickskin$modifyGetOrLoad(GameProfile profile, CallbackInfoReturnable<CompletableFuture<Optional<PlayerSkin>>> cir) {
        UUID uuid = profile.id();
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

        String profileName = profile.name();
        CompletableFuture<Optional<PlayerSkin>> original = cir.getReturnValue();
        CompletableFuture<Optional<PlayerSkin>> modified = original.thenApply(optSkin ->
            optSkin.map(skin -> quickskin$applyOverrides(skin, uuid, profileName))
        );
        cir.setReturnValue(modified);
    }
}
