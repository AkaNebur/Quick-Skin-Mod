package com.quickskin.mod.neoforge.mixin;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.services.AnimatedTextureManager;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.util.TextureAlphaDetector;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CapeLayer.class, priority = 1100)
public class CapeLayerMixin {

    private static final java.util.Map<java.util.UUID, Long> lastLogTime = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long LOG_INTERVAL_MS = 2000;

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V",
            at = @At("HEAD"),
            cancellable = true)
    private void quickskin$renderCustomCape(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                                            AbstractClientPlayer player, float limbSwing, float limbSwingAmount,
                                            float partialTicks, float ageInTicks, float netHeadYaw, float headPitch,
                                            CallbackInfo ci) {

        PlayerAppearanceService service = PlayerAppearanceService.getInstance();

        long now = System.currentTimeMillis();
        Long lastLog = lastLogTime.get(player.getUUID());
        boolean shouldLog = (lastLog == null || now - lastLog > LOG_INTERVAL_MS);
        if (shouldLog) {
            lastLogTime.put(player.getUUID(), now);
        }

        if (!service.hasActiveCape(player.getUUID())) {
            if (shouldLog) {
                QuickSkin.LOGGER.info("[CapeLayerMixin] No active cape for player {}, letting vanilla run", player.getName().getString());
            }
            return;
        }

        ResourceLocation capeTexture = player.getSkin().capeTexture();

        if (capeTexture == null) {
            ci.cancel();
            return;
        }

        if (player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
            ci.cancel();
            return;
        }

        ResourceLocation finalTexture = capeTexture;
        String capeId = service.getCapeId(player.getUUID());

        if (capeId != null && !capeId.isEmpty()) {
            String animationId = null;
            if (capeId.startsWith("local_cape:")) {
                animationId = "cape_" + capeId.substring("local_cape:".length());
            } else if (capeId.startsWith("known:")) {
                animationId = "cape_known_" + capeId.substring("known:".length());
            }

            if (animationId != null) {
                ResourceLocation currentFrame = AnimatedTextureManager.getInstance().getCurrentFrameTexture(animationId);
                if (currentFrame != null) {
                    finalTexture = currentFrame;
                    if (shouldLog) {
                        QuickSkin.LOGGER.info("[CapeLayerMixin] Animation frame: capeId={}, animationId={}, frame={}", capeId, animationId, currentFrame);
                    }
                } else {
                    if (shouldLog) {
                        QuickSkin.LOGGER.warn("[CapeLayerMixin] getCurrentFrameTexture returned null for animationId={}", animationId);
                    }
                }
            } else {
                if (shouldLog) {
                    QuickSkin.LOGGER.warn("[CapeLayerMixin] Could not derive animationId from capeId={}", capeId);
                }
            }
        } else {
            if (shouldLog) {
                QuickSkin.LOGGER.warn("[CapeLayerMixin] capeId is null/empty, falling back to atlas lookup for texture={}", capeTexture);
            }
            java.util.Optional<ResourceLocation> animFrame = AnimatedTextureManager.getInstance()
                    .getAnimationFrame(capeTexture);
            finalTexture = animFrame.orElse(capeTexture);
        }

        RenderType renderType;

        if (finalTexture.getNamespace().equals(QuickSkin.MOD_ID)) {
            renderType = RenderType.entityTranslucentCull(finalTexture);
        } else {
            boolean hasTransparency = TextureAlphaDetector.hasTransparency(finalTexture);
            if (hasTransparency) {
                renderType = RenderType.entityTranslucentCull(finalTexture);
            } else {
                renderType = RenderType.entitySolid(finalTexture);
            }
        }

        VertexConsumer vertexconsumer = buffer.getBuffer(renderType);

        poseStack.pushPose();
        poseStack.translate(0.0D, 0.0D, 0.125D);
        double d0 = Mth.lerp(partialTicks, player.xCloakO, player.xCloak) - Mth.lerp(partialTicks, player.xo, player.getX());
        double d1 = Mth.lerp(partialTicks, player.yCloakO, player.yCloak) - Mth.lerp(partialTicks, player.yo, player.getY());
        double d2 = Mth.lerp(partialTicks, player.zCloakO, player.zCloak) - Mth.lerp(partialTicks, player.zo, player.getZ());
        float f = player.yBodyRotO + (player.yBodyRot - player.yBodyRotO);
        double d3 = Mth.sin(f * ((float)Math.PI / 180F));
        double d4 = -Mth.cos(f * ((float)Math.PI / 180F));
        float f1 = (float)d1 * 10.0F;
        f1 = Mth.clamp(f1, -6.0F, 32.0F);
        float f2 = (float)(d0 * d3 + d2 * d4) * 100.0F;
        f2 = Mth.clamp(f2, 0.0F, 150.0F);
        float f3 = (float)(d0 * d4 - d2 * d3) * 100.0F;
        f3 = Mth.clamp(f3, -20.0F, 20.0F);
        if (f2 < 0.0F) {
            f2 = 0.0F;
        }

        float f4 = Mth.lerp(partialTicks, player.oBob, player.bob);
        f1 += Mth.sin(Mth.lerp(partialTicks, player.walkDistO, player.walkDist) * 6.0F) * 32.0F * f4;
        if (player.isCrouching()) {
            f1 += 25.0F;
        }

        poseStack.mulPose(Axis.XP.rotationDegrees(6.0F + f2 / 2.0F + f1));
        poseStack.mulPose(Axis.ZP.rotationDegrees(f3 / 2.0F));
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - f3 / 2.0F));

        ((CapeLayer)(Object)this).getParentModel().renderCloak(poseStack, vertexconsumer, packedLight, OverlayTexture.NO_OVERLAY);

        poseStack.popPose();

        ci.cancel();
    }
}
