package com.quickskin.mod.neoforge.mixin;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.services.PlayerAppearanceService;
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
 * NeoForge-specific mixin to intercept AbstractClientPlayer skin lookups.
 * Uses Mojmap names directly since NeoForge uses Mojmap at runtime.
 */
@Mixin(value = AbstractClientPlayer.class, priority = 100)
public abstract class MixinAbstractClientPlayer {

    @Unique
    private static Field quickskin$playerInfoField = null;

    @Unique
    private static boolean quickskin$fieldSearched = false;

    @Unique
    private static final java.util.Map<java.util.UUID, Long> quickskin$lastLogTime = new java.util.concurrent.ConcurrentHashMap<>();
    @Unique
    private static final long LOG_INTERVAL_MS = 5000;

    @Inject(method = "getSkin", at = @At("HEAD"), cancellable = true)
    private void quickskin$overrideSkinAtHead(CallbackInfoReturnable<PlayerSkin> cir) {
        PlayerAppearanceService service = PlayerAppearanceService.getInstance();
        if (service == null) {
            return;
        }

        AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;

        boolean hasCustomSkin = service.hasActiveSkin(self.getUUID());
        boolean hasCustomCape = service.hasActiveCape(self.getUUID());
        boolean hasModelOverride = service.hasModelOverride(self.getUUID());

        long now = System.currentTimeMillis();
        Long lastLog = quickskin$lastLogTime.get(self.getUUID());
        if (lastLog == null || now - lastLog > LOG_INTERVAL_MS) {
            quickskin$lastLogTime.put(self.getUUID(), now);
            QuickSkin.LOGGER.info("[MixinAbstractClientPlayer HEAD] getSkin() called for {} (UUID={}) - hasCustomSkin={}, hasCustomCape={}, hasModelOverride={}",
                self.getName().getString(), self.getUUID(), hasCustomSkin, hasCustomCape, hasModelOverride);
        }

        if (!hasCustomSkin && !hasCustomCape && !hasModelOverride) {
            return;
        }

        PlayerInfo playerInfo = quickskin$getPlayerInfo(self);
        if (playerInfo == null) {
            QuickSkin.LOGGER.warn("[MixinAbstractClientPlayer] PlayerInfo is null for {}", self.getName().getString());
            return;
        }

        PlayerSkin originalSkin = playerInfo.getSkin();
        if (originalSkin == null) {
            return;
        }

        ResourceLocation skinTexture = originalSkin.texture();
        PlayerSkin.Model skinModel = originalSkin.model();
        ResourceLocation capeTexture = originalSkin.capeTexture();

        if (hasCustomSkin) {
            ResourceLocation customSkin = service.getSkinLocation(self.getUUID());
            if (customSkin != null) {
                skinTexture = customSkin;
                QuickSkin.LOGGER.info("[MixinAbstractClientPlayer] OVERRIDING skin for {} - originalTexture={}, newTexture={}",
                    self.getName().getString(), originalSkin.texture(), skinTexture);
            }
        }

        if (hasCustomSkin || hasModelOverride) {
            String customModel = service.getModelName(self.getUUID());
            if (customModel != null) {
                skinModel = "slim".equals(customModel) ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE;
            }
        }

        if (hasCustomCape) {
            ResourceLocation customCape = service.getCapeLocation(self.getUUID());
            if (customCape != null) {
                capeTexture = customCape;
            } else {
                com.quickskin.mod.common.data.PlayerAppearance appearance = service.getAppearance(self.getUUID());
                if (appearance != null && ("__NONE__".equals(appearance.getCapeId()) || appearance.getCapeId().isEmpty())) {
                    capeTexture = null;
                }
            }
        }

        PlayerSkin customSkin = new PlayerSkin(
            skinTexture,
            originalSkin.textureUrl(),
            capeTexture,
            originalSkin.elytraTexture(),
            skinModel,
            originalSkin.secure()
        );

        QuickSkin.LOGGER.info("[MixinAbstractClientPlayer] Returning custom skin for {} - texture={}, cape={}, model={}",
            self.getName().getString(), skinTexture, capeTexture, skinModel);

        cir.setReturnValue(customSkin);
    }

    @Unique
    private static PlayerInfo quickskin$getPlayerInfo(AbstractClientPlayer player) {
        if (!quickskin$fieldSearched) {
            quickskin$fieldSearched = true;
            for (Field field : AbstractClientPlayer.class.getDeclaredFields()) {
                if (PlayerInfo.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    quickskin$playerInfoField = field;
                    QuickSkin.LOGGER.info("[MixinAbstractClientPlayer] Found playerInfo field: {}", field.getName());
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
}
