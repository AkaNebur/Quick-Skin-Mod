package com.quickskin.mod.neoforge.mixin;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.services.AnimatedTextureManager;
import com.quickskin.mod.client.services.CapeAnimationHelper;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.util.TextureAlphaDetector;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(value = CapeLayer.class, priority = 1100)
public class CapeLayerMixin {

    // In MC 1.21.4+, CapeLayer has its own cape model (PlayerCapeModel) separate from PlayerModel
    @Shadow @Final private HumanoidModel<?> model;

    private static final java.util.Map<java.util.UUID, Long> lastLogTime = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long LOG_INTERVAL_MS = 2000;

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/renderer/entity/state/PlayerRenderState;FF)V",
            at = @At("HEAD"),
            cancellable = true)
    private void quickskin$renderCustomCape(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                                            PlayerRenderState renderState, float yRot, float xRot,
                                            CallbackInfo ci) {
        // Look up the actual player entity from the render state to get UUID
        UUID playerUUID = null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            Entity entity = mc.level.getEntity(renderState.id);
            if (entity instanceof AbstractClientPlayer player) {
                playerUUID = player.getUUID();
            }
        }

        if (playerUUID == null) {
            return; // Can't identify player, let vanilla logic run
        }

        PlayerAppearanceService service = PlayerAppearanceService.getInstance();

        long now = System.currentTimeMillis();
        Long lastLog = lastLogTime.get(playerUUID);
        boolean shouldLog = (lastLog == null || now - lastLog > LOG_INTERVAL_MS);
        if (shouldLog) {
            lastLogTime.put(playerUUID, now);
        }

        // Check service-based cape
        boolean hasServiceCape = service.hasActiveCape(playerUUID);

        // Check config-based cape for local player (works both title screen and in-world)
        boolean hasConfigCape = false;
        boolean isLocalPlayer = mc.player != null && playerUUID.equals(mc.player.getUUID());
        if (!hasServiceCape && isLocalPlayer) {
            ClientConfig config = ClientConfig.getInstance();
            hasConfigCape = !config.activeCapeHash.isEmpty();
        }

        if (!hasServiceCape && !hasConfigCape) {
            return; // No cape from either source, let vanilla handle
        }

        // Get cape texture from our service or config fallback
        ResourceLocation capeTexture = null;
        if (hasServiceCape) {
            capeTexture = service.getCapeLocation(playerUUID);
        }
        if (capeTexture == null && isLocalPlayer) {
            ClientConfig config = ClientConfig.getInstance();
            if (!config.activeCapeHash.isEmpty()) {
                capeTexture = com.quickskin.mod.client.services.CapeService.getInstance()
                        .getCapeLocation(null, config.activeCapeHash);
            }
        }
        if (capeTexture == null) {
            // Fall back to render state's skin cape
            capeTexture = renderState.skin.capeTexture();
        }

        if (capeTexture == null) {
            ci.cancel();
            return;
        }

        ResourceLocation finalTexture = capeTexture;
        String capeId = service.getCapeId(playerUUID);

        if (capeId != null && !capeId.isEmpty()) {
            String animationId = CapeAnimationHelper.deriveAnimationId(capeId);

            if (animationId != null) {
                ResourceLocation currentFrame = AnimatedTextureManager.getInstance().getCurrentFrameTexture(animationId);
                if (currentFrame != null) {
                    finalTexture = currentFrame;
                } else {
                    if (shouldLog && ClientConfig.getInstance().enableVerboseLogging) {
                        QuickSkin.LOGGER.warn("[CapeLayerMixin] getCurrentFrameTexture returned null for animationId={}", animationId);
                    }
                }
            } else {
                if (shouldLog && ClientConfig.getInstance().enableVerboseLogging) {
                    QuickSkin.LOGGER.warn("[CapeLayerMixin] Could not derive animationId from capeId={}", capeId);
                }
            }
        } else {
            if (shouldLog && ClientConfig.getInstance().enableVerboseLogging) {
                QuickSkin.LOGGER.warn("[CapeLayerMixin] capeId is null/empty, falling back to atlas lookup for texture={}", capeTexture);
            }
            java.util.Optional<ResourceLocation> animFrame = AnimatedTextureManager.getInstance()
                    .getAnimationFrame(capeTexture);
            finalTexture = animFrame.orElse(capeTexture);
        }

        RenderType renderType;

        if (finalTexture.getNamespace().equals(QuickSkin.MOD_ID)) {
            renderType = RenderType.entityTranslucent(finalTexture);
        } else {
            boolean hasTransparency = TextureAlphaDetector.hasTransparency(finalTexture);
            if (hasTransparency) {
                renderType = RenderType.entityTranslucent(finalTexture);
            } else {
                renderType = RenderType.entitySolid(finalTexture);
            }
        }

        VertexConsumer vertexconsumer = buffer.getBuffer(renderType);

        // Use CapeLayer's own cape model (PlayerCapeModel) - the proper MC 1.21.4+ approach
        // Copy body transforms from parent player model, then setup animation and render
        @SuppressWarnings("unchecked")
        HumanoidModel<PlayerRenderState> capeModel = (HumanoidModel<PlayerRenderState>) (HumanoidModel<?>) this.model;
        ((CapeLayer)(Object)this).getParentModel().copyPropertiesTo(capeModel);
        capeModel.setupAnim(renderState);
        capeModel.renderToBuffer(poseStack, vertexconsumer, packedLight, OverlayTexture.NO_OVERLAY);

        ci.cancel();
    }
}
