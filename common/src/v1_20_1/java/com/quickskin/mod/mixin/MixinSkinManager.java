package com.quickskin.mod.mixin;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mixin to intercept SkinManager's skin loading so that Essential's title screen
 * player model (which loads skins via SkinManager.registerSkins, bypassing
 * AbstractClientPlayer.getSkinTextureLocation) uses QuickSkin's texture.
 */
@Mixin(SkinManager.class)
public class MixinSkinManager {

    @Unique
    private static final ThreadLocal<Boolean> quickskin$processing = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Inject(method = "registerSkins", at = @At("HEAD"), cancellable = true)
    private void quickskin$wrapRegisterSkins(
            GameProfile profile,
            SkinManager.SkinTextureCallback callback,
            boolean requireSecure,
            CallbackInfo ci) {

        // Prevent recursion when we re-invoke registerSkins with the wrapped callback
        if (quickskin$processing.get()) return;

        UUID localUuid = null;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.getUser() != null) {
                localUuid = mc.getUser().getProfileId();
            }
        } catch (Exception e) {
            // Safety: if anything goes wrong getting UUID, just pass through
        }

        ClientConfig config = ClientConfig.getInstance();

        if (localUuid != null && localUuid.equals(profile.getId()) && !config.activeSkinHash.isEmpty()) {
            LocalAssetManager assetManager = LocalAssetManager.getInstance();
            AssetMetadata metadata = assetManager.getMetadata(config.activeSkinHash);

            if (metadata != null) {
                // Wrap the callback to replace the SKIN texture with QuickSkin's
                SkinManager.SkinTextureCallback wrappedCallback = (type, location, texture) -> {
                    if (type == MinecraftProfileTexture.Type.SKIN) {
                        ResourceLocation ourSkin = assetManager.getTextureLocation(
                                config.activeSkinHash, TextureQuality.FULL);
                        if (ourSkin != null) {
                            // Build metadata with correct model type
                            String modelType = assetManager.getSkinModelPreference(config.activeSkinHash);
                            Map<String, String> meta = new HashMap<>();
                            if ("slim".equals(modelType)) {
                                meta.put("model", "slim");
                            } else if ("auto".equals(modelType)) {
                                AssetMetadata skinMeta = assetManager.getMetadata(config.activeSkinHash);
                                if (skinMeta != null && "slim".equals(skinMeta.skinModel())) {
                                    meta.put("model", "slim");
                                }
                            }

                            MinecraftProfileTexture modifiedTexture = new MinecraftProfileTexture(
                                    "quickskin://local/" + config.activeSkinHash, meta);

                            QuickSkin.LOGGER.info("[SkinManager Mixin] Replaced skin texture for {} with QuickSkin skin: {}",
                                    profile.getName(), metadata.friendlyName());
                            callback.onSkinTextureAvailable(type, ourSkin, modifiedTexture);
                            return;
                        }
                    }
                    // For CAPE and other types, or if our skin failed to load, pass through
                    callback.onSkinTextureAvailable(type, location, texture);
                };

                // Cancel original call and re-invoke with the wrapped callback
                ci.cancel();
                quickskin$processing.set(true);
                try {
                    ((SkinManager) (Object) this).registerSkins(profile, wrappedCallback, requireSecure);
                } finally {
                    quickskin$processing.set(false);
                }
            }
        }
    }
}
