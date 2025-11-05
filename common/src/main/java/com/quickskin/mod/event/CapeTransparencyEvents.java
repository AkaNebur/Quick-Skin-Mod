package com.quickskin.mod.event;

import com.quickskin.mod.common.util.TextureAlphaDetector;
import dev.architectury.registry.ReloadListenerRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Environment(EnvType.CLIENT)
public class CapeTransparencyEvents implements PreparableReloadListener {

    public static final ResourceLocation LISTENER_ID = new ResourceLocation("quickskin", "cape_transparency_cache_clearer");

    public static void register() {
        ReloadListenerRegistry.register(PackType.CLIENT_RESOURCES, new CapeTransparencyEvents(), LISTENER_ID);
    }

    @Override
    public final CompletableFuture<Void> reload(PreparationBarrier preparationBarrier, ResourceManager resourceManager, ProfilerFiller preparationsProfiler, ProfilerFiller reloadProfiler, Executor backgroundExecutor, Executor gameExecutor) {
        return preparationBarrier.wait(null).thenRunAsync(() -> {
            TextureAlphaDetector.clearCache();
            com.quickskin.mod.client.services.LocalAssetManager.getInstance().reload();
        }, gameExecutor);
    }
}