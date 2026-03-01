package com.quickskin.mod.mixin;

import com.mojang.authlib.GameProfile;
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin to intercept PlayerInfo texture and model lookups
 * Allows QuickSkin to override player skins, capes, and models
 */
@Mixin(value = PlayerInfo.class, priority = 1100) // Higher priority to override TLSkinCape and other mods
public abstract class PlayerInfoMixin {

    @Shadow
    @Final
    private GameProfile profile;

    /**
     * Inject into getSkinLocation to override with QuickSkin texture
     */
    @Inject(method = "getSkinLocation", at = @At("HEAD"), cancellable = true)
    private void quickskin$onGetSkinLocation(CallbackInfoReturnable<ResourceLocation> cir) {
        if (CPMCompatIntegration.shouldDeferToCPM()) return;

        PlayerAppearanceService service = PlayerAppearanceService.getInstance();

        // Only override if QuickSkin has an active custom skin for this player
        if (service.hasActiveSkin(this.profile.getId())) {
            ResourceLocation customSkin = service.getSkinLocation(this.profile.getId());
            if (customSkin != null) {
                cir.setReturnValue(customSkin);
                return;
            }
        }

        // Title screen fallback: when no world is loaded, return saved skin from config
        if (Minecraft.getInstance().level == null) {
            ClientConfig config = ClientConfig.getInstance();
            if (!config.activeSkinHash.isEmpty()) {
                ResourceLocation loc = LocalAssetManager.getInstance()
                        .getTextureLocation(config.activeSkinHash, TextureQuality.FULL);
                if (loc != null) {
                    cir.setReturnValue(loc);
                }
            }
        }
    }

    /**
     * Inject into getModelName to override with QuickSkin model type
     */
    @Inject(method = "getModelName", at = @At("HEAD"), cancellable = true)
    private void quickskin$onGetModelName(CallbackInfoReturnable<String> cir) {
        if (CPMCompatIntegration.shouldDeferToCPM()) return;

        PlayerAppearanceService service = PlayerAppearanceService.getInstance();

        // Override if QuickSkin has an active custom model OR a model override for this player
        // Check both to avoid race condition where model is set before skin data is populated
        if (service.hasActiveSkin(this.profile.getId()) || service.hasModelOverride(this.profile.getId())) {
            String customModel = service.getModelName(this.profile.getId());
            if (customModel != null) {
                cir.setReturnValue(customModel);
                return;
            }
        }

        // Title screen fallback: return saved model type from config
        if (Minecraft.getInstance().level == null) {
            ClientConfig config = ClientConfig.getInstance();
            if (!config.activeSkinHash.isEmpty()) {
                LocalAssetManager assetManager = LocalAssetManager.getInstance();
                String modelType = assetManager.getSkinModelPreference(config.activeSkinHash);
                if ("auto".equals(modelType)) {
                    AssetMetadata metadata = assetManager.getMetadata(config.activeSkinHash);
                    if (metadata != null) {
                        modelType = metadata.skinModel();
                    }
                }
                if (modelType != null) {
                    // Convert to Minecraft model names: "classic" -> "default", "slim" stays "slim"
                    String mcModel = "classic".equals(modelType) ? "default" : modelType;
                    cir.setReturnValue(mcModel);
                }
            }
        }
    }

    /**
     * Inject into getCapeLocation to override with QuickSkin cape
     */
    @Inject(method = "getCapeLocation", at = @At("HEAD"), cancellable = true)
    private void quickskin$onGetCapeLocation(CallbackInfoReturnable<ResourceLocation> cir) {
        if (CPMCompatIntegration.shouldDeferToCPM()) return;

        PlayerAppearanceService service = PlayerAppearanceService.getInstance();

        // Only override if QuickSkin has an active custom cape for this player
        if (service.hasActiveCape(this.profile.getId())) {
            com.quickskin.mod.common.data.PlayerAppearance appearance = service.getAppearance(this.profile.getId());

            // Check for the explicit "hide cape" identifier
            if (appearance != null && ("__NONE__".equals(appearance.getCapeId()) || appearance.getCapeId().isEmpty())) {
                cir.setReturnValue(null); // Return null to hide the cape completely
                return;
            }

            ResourceLocation customCape = service.getCapeLocation(this.profile.getId());

            // If a custom cape is found (or still loading but intended), set it.
            cir.setReturnValue(customCape);
            return;
        }

        // Title screen fallback: return saved cape from config
        if (Minecraft.getInstance().level == null) {
            ClientConfig config = ClientConfig.getInstance();
            if (!config.activeCapeHash.isEmpty()) {
                ResourceLocation capeLoc = com.quickskin.mod.client.services.CapeService.getInstance()
                        .getCapeLocation(null, config.activeCapeHash);
                if (capeLoc != null) {
                    cir.setReturnValue(capeLoc);
                }
            }
        }
    }
}
