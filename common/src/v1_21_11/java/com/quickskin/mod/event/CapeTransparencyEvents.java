package com.quickskin.mod.event;

import com.quickskin.mod.common.util.TextureAlphaDetector;
import dev.architectury.registry.ReloadListenerRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Environment(EnvType.CLIENT)
public class CapeTransparencyEvents implements PreparableReloadListener {

    public static final Identifier LISTENER_ID = Identifier.fromNamespaceAndPath("quickskin", "cape_transparency_cache_clearer");

    public static void register() {
        ReloadListenerRegistry.register(PackType.CLIENT_RESOURCES, new CapeTransparencyEvents(), LISTENER_ID);
    }

    @Override
    public final CompletableFuture<Void> reload(PreparableReloadListener.SharedState sharedState, Executor backgroundExecutor, PreparableReloadListener.PreparationBarrier preparationBarrier, Executor gameExecutor) {
        return preparationBarrier.wait(null).thenRunAsync(() -> {
            TextureAlphaDetector.clearCache();
            com.quickskin.mod.client.services.LocalAssetManager.getInstance().reload();
        }, gameExecutor);
    }
}