package com.quickskin.mod.mixin;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.client.services.LocalAssetManager;
import com.quickskin.mod.common.data.AssetMetadata;
import com.quickskin.mod.common.data.TextureQuality;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
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

    @Inject(method = "registerSkins", at = @At("HEAD"), cancellable = true)
    private void quickskin$wrapRegisterSkins(
            GameProfile profile,
            SkinManager.SkinTextureCallback callback,
            boolean requireSecure,
            CallbackInfo ci) {

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
                ResourceLocation ourSkin;
                if (CPMCompatIntegration.isAvailable()) {
                    // When CPM is installed, register skin as HttpTexture so CPM can
                    // read pixel data and extract embedded 3D model from the PNG file
                    ourSkin = CPMCompatIntegration.getOrRegisterHttpTexture(config.activeSkinHash);
                    if (ourSkin == null) {
                        ourSkin = assetManager.getTextureLocation(
                                config.activeSkinHash, TextureQuality.FULL);
                    }
                } else {
                    ourSkin = assetManager.getTextureLocation(
                            config.activeSkinHash, TextureQuality.FULL);
                }

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

                    // Use file URL so CPM reads from our local file, not Mojang.
                    // getHash() extracts the last path segment as cache key,
                    // so CPM won't confuse this with the Mojang premium skin.
                    String textureUrl = "file:///quickskin/" + config.activeSkinHash;
                    java.nio.file.Path sourcePath = assetManager.getSourcePath(config.activeSkinHash);
                    if (sourcePath != null && sourcePath.toFile().exists()) {
                        textureUrl = "file:///" + sourcePath.toFile().getAbsolutePath().replace('\\', '/');
                    }

                    MinecraftProfileTexture modifiedTexture = new MinecraftProfileTexture(
                            textureUrl, meta);

                    // Cancel the original registerSkins and directly fire our SKIN callback.
                    // We do NOT re-invoke registerSkins because that would cause CPM's mixin
                    // to wrap the callback again, letting CPM see the raw Mojang skin data
                    // before our modifications. By firing the callback directly, CPM only
                    // processes our modified skin data.
                    ci.cancel();
                    callback.onSkinTextureAvailable(
                            MinecraftProfileTexture.Type.SKIN, ourSkin, modifiedTexture);
                }
            }
        }
    }

}
