package com.quickskin.mod.mixin;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;

/**
 * Mixin to intercept AbstractClientPlayer skin lookups.
 *
 * Uses @Inject at HEAD with lowest priority (100) to run BEFORE other mods like
 * CustomNPCs-Unofficial (which uses priority 1001 at RETURN).
 *
 * By injecting at HEAD with cancellable=true, we can bypass other mods' modifications
 * entirely when we have custom skin data.
 *
 * In MC 1.21.1, getSkinTextureLocation/getModelName/getCloakTextureLocation were replaced
 * with getSkin() which returns a PlayerSkin record containing all skin data.
 */
@Mixin(value = AbstractClientPlayer.class, priority = 100)
public abstract class MixinAbstractClientPlayer {

    // Cache the playerInfo field for reflection access (works on both Fabric and NeoForge)
    @Unique
    private static Field quickskin$playerInfoField = null;

    @Unique
    private static boolean quickskin$fieldSearched = false;

    // Throttle logging to avoid spam
    @Unique
    private static final java.util.Map<java.util.UUID, Long> quickskin$lastLogTime = new java.util.concurrent.ConcurrentHashMap<>();
    @Unique
    private static final long LOG_INTERVAL_MS = 5000;

    /**
     * Inject at HEAD with lowest priority to intercept before other mods.
     * When we have custom skin data, we bypass the normal method entirely.
     */
    @Inject(method = "getSkin()Lnet/minecraft/client/resources/PlayerSkin;", at = @At("HEAD"), cancellable = true, remap = false)
    private void quickskin$overrideSkinAtHead(CallbackInfoReturnable<PlayerSkin> cir) {
        PlayerAppearanceService service = PlayerAppearanceService.getInstance();
        if (service == null) {
            return;
        }

        AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;

        boolean hasCustomSkin = service.hasActiveSkin(self.getUUID());
        boolean hasCustomCape = service.hasActiveCape(self.getUUID());
        boolean hasModelOverride = service.hasModelOverride(self.getUUID());

        // Throttled debug logging to confirm mixin is running
        long now = System.currentTimeMillis();
        Long lastLog = quickskin$lastLogTime.get(self.getUUID());
        boolean shouldLog = lastLog == null || now - lastLog > LOG_INTERVAL_MS;
        if (shouldLog) {
            quickskin$lastLogTime.put(self.getUUID(), now);
            QuickSkin.LOGGER.debug("[MixinAbstractClientPlayer HEAD] getSkin() called for {} (UUID={}) - hasCustomSkin={}, hasCustomCape={}, hasModelOverride={}",
                self.getName().getString(), self.getUUID(), hasCustomSkin, hasCustomCape, hasModelOverride);
        }

        // Only intercept if we have custom data
        if (!hasCustomSkin && !hasCustomCape && !hasModelOverride) {
            // Title screen fallback: when no world is loaded and config has saved appearance,
            // build a PlayerSkin directly regardless of UUID (covers Essential's fake player)
            if (Minecraft.getInstance().level == null) {
                PlayerSkin fallbackSkin = quickskin$buildTitleScreenFallback(self);
                if (fallbackSkin != null) {
                    cir.setReturnValue(fallbackSkin);
                }
            }
            return;
        }

        // Get the base skin from PlayerInfo using reflection (works on both Fabric and NeoForge)
        PlayerInfo playerInfo = quickskin$getPlayerInfo(self);
        if (playerInfo == null) {
            QuickSkin.LOGGER.warn("[MixinAbstractClientPlayer] PlayerInfo is null for {}", self.getName().getString());
            return;
        }

        PlayerSkin originalSkin = playerInfo.getSkin();
        if (originalSkin == null) {
            return;
        }

        // Build our custom skin
        ResourceLocation skinTexture = originalSkin.texture();
        PlayerSkin.Model skinModel = originalSkin.model();
        ResourceLocation capeTexture = originalSkin.capeTexture();

        // Override skin texture
        if (hasCustomSkin) {
            ResourceLocation customSkin = service.getSkinLocation(self.getUUID());
            if (customSkin != null) {
                skinTexture = customSkin;
            }
        }

        // Override model
        if (hasCustomSkin || hasModelOverride) {
            String customModel = service.getModelName(self.getUUID());
            if (customModel != null) {
                skinModel = "slim".equals(customModel) ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE;
            }
        }

        // Override cape
        if (hasCustomCape) {
            ResourceLocation customCape = service.getCapeLocation(self.getUUID());
            if (customCape != null) {
                capeTexture = customCape;
            } else {
                // Check if we're explicitly hiding the cape
                com.quickskin.mod.common.data.PlayerAppearance appearance = service.getAppearance(self.getUUID());
                if (appearance != null && ("__NONE__".equals(appearance.getCapeId()) || appearance.getCapeId().isEmpty())) {
                    capeTexture = null;
                }
            }
        }

        // Create and set the final skin - cancels the method and returns our custom skin
        PlayerSkin customSkin = new PlayerSkin(
            skinTexture,
            originalSkin.textureUrl(),
            capeTexture,
            originalSkin.elytraTexture(),
            skinModel,
            originalSkin.secure()
        );

        cir.setReturnValue(customSkin);
    }

    /**
     * Gets the PlayerInfo from AbstractClientPlayer using reflection.
     * This works on both Fabric (Intermediary) and NeoForge (SRG) mappings.
     */
    @Unique
    private static PlayerInfo quickskin$getPlayerInfo(AbstractClientPlayer player) {
        if (!quickskin$fieldSearched) {
            quickskin$fieldSearched = true;
            // Try to find the playerInfo field - it might have different names in different mappings
            for (Field field : AbstractClientPlayer.class.getDeclaredFields()) {
                if (PlayerInfo.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    quickskin$playerInfoField = field;
                    QuickSkin.LOGGER.debug("[MixinAbstractClientPlayer] Found playerInfo field: {}", field.getName());
                    break;
                }
            }
            if (quickskin$playerInfoField == null) {
                QuickSkin.LOGGER.error("[MixinAbstractClientPlayer] Could not find playerInfo field in AbstractClientPlayer!");
            }
        }

        if (quickskin$playerInfoField != null) {
            try {
                return (PlayerInfo) quickskin$playerInfoField.get(player);
            } catch (IllegalAccessException e) {
                QuickSkin.LOGGER.error("[MixinAbstractClientPlayer] Failed to access playerInfo field", e);
            }
        }
        return null;
    }

    /**
     * Builds a PlayerSkin from saved config for title screen fallback.
     * Returns null if no saved skin/cape is configured.
     */
    @Unique
    private static PlayerSkin quickskin$buildTitleScreenFallback(AbstractClientPlayer player) {
        ClientConfig config = ClientConfig.getInstance();
        boolean hasSkin = !config.activeSkinHash.isEmpty();
        boolean hasCape = !config.activeCapeHash.isEmpty();

        if (!hasSkin && !hasCape) {
            return null;
        }

        LocalAssetManager assetManager = LocalAssetManager.getInstance();
        ResourceLocation skinTexture = null;
        PlayerSkin.Model skinModel = PlayerSkin.Model.WIDE;
        ResourceLocation capeTexture = null;

        if (hasSkin) {
            skinTexture = assetManager.getTextureLocation(config.activeSkinHash, TextureQuality.FULL);
            String modelType = assetManager.getSkinModelPreference(config.activeSkinHash);
            if ("auto".equals(modelType)) {
                AssetMetadata metadata = assetManager.getMetadata(config.activeSkinHash);
                if (metadata != null) {
                    modelType = metadata.skinModel();
                }
            }
            skinModel = "slim".equals(modelType) ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE;
        }

        if (hasCape) {
            capeTexture = com.quickskin.mod.client.services.CapeService.getInstance()
                    .getCapeLocation(null, config.activeCapeHash);
        }

        if (skinTexture == null && capeTexture == null) {
            return null;
        }

        // Get the base skin from PlayerInfo to fill in missing fields
        PlayerInfo playerInfo = quickskin$getPlayerInfo(player);
        if (playerInfo != null) {
            PlayerSkin originalSkin = playerInfo.getSkin();
            if (originalSkin != null) {
                return new PlayerSkin(
                    skinTexture != null ? skinTexture : originalSkin.texture(),
                    originalSkin.textureUrl(),
                    capeTexture != null ? capeTexture : originalSkin.capeTexture(),
                    originalSkin.elytraTexture(),
                    hasSkin ? skinModel : originalSkin.model(),
                    originalSkin.secure()
                );
            }
        }

        // Last resort: build with just our textures
        if (skinTexture != null) {
            return new PlayerSkin(
                skinTexture, null, capeTexture, null, skinModel, false
            );
        }

        return null;
    }
}
