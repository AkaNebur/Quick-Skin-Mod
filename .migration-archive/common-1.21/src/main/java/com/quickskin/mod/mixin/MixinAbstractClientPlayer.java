package com.quickskin.mod.mixin;

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

        // Only intercept if we have custom data
        if (!hasCustomSkin && !hasCustomCape && !hasModelOverride) {
            // No custom data - let the normal method (and other mods) run
            return;
        }

        // Get the base skin from PlayerInfo using reflection (works on both Fabric and NeoForge)
        PlayerInfo playerInfo = quickskin$getPlayerInfo(self);
        if (playerInfo == null) {
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
                    break;
                }
            }
        }

        if (quickskin$playerInfoField != null) {
            try {
                return (PlayerInfo) quickskin$playerInfoField.get(player);
            } catch (IllegalAccessException e) {
            }
        }
        return null;
    }
}
