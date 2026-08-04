package com.quickskin.mod.neoforge.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.client.rendering.PlayerModelRenderer;
import com.quickskin.mod.client.rendering.PreviewCapeBindings;
import com.quickskin.mod.client.services.CapeAnimationHelper;
import com.quickskin.mod.client.services.CapeService;
import com.quickskin.mod.client.services.PlayerAppearanceService;
import com.quickskin.mod.common.util.TextureAlphaDetector;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/** NeoForge Minecraft 1.21.5 cape adapter: render-state input with an immediate buffer. */
@Mixin(value = CapeLayer.class, priority = 1100)
public class CapeLayerMixin {

    @Shadow @Final private HumanoidModel<?> model;

    @Inject(
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/renderer/entity/state/PlayerRenderState;FF)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1,
            expect = 1,
            allow = 1)
    private void quickskin$renderCustomCape(PoseStack poseStack, MultiBufferSource buffer,
                                            int packedLight, PlayerRenderState renderState,
                                            float yRot, float xRot, CallbackInfo ci) {
        if (CPMCompatIntegration.shouldDeferToCPM()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        AbstractClientPlayer player = null;
        UUID playerId = null;
        if (minecraft.level != null) {
            Entity entity = minecraft.level.getEntity(renderState.id);
            if (entity instanceof AbstractClientPlayer resolvedPlayer) {
                player = resolvedPlayer;
                playerId = resolvedPlayer.getUUID();
            }
        }

        PreviewCapeBindings.Resolution<ResourceLocation> preview =
                PlayerModelRenderer.consumePreviewCape(player);
        if (preview.decision() == PreviewCapeBindings.Decision.HIDDEN) {
            ci.cancel();
            return;
        }
        boolean previewing = preview.decision() == PreviewCapeBindings.Decision.PREVIEW;

        if (playerId == null && minecraft.level == null) {
            playerId = minecraft.getUser().getProfileId();
        }
        if (playerId == null && !previewing) {
            return;
        }
        if (!previewing && renderState.chestEquipment.is(Items.ELYTRA)) {
            ci.cancel();
            return;
        }

        PlayerAppearanceService service = PlayerAppearanceService.getInstance();
        boolean hasServiceCape = !previewing && service.hasActiveCape(playerId);
        boolean isLocalPlayer = playerId != null
                && playerId.equals(minecraft.getUser().getProfileId());
        ClientConfig config = ClientConfig.getInstance();
        boolean hasConfigCape = !previewing && !hasServiceCape && isLocalPlayer
                && !config.activeCapeHash.isEmpty();
        if (!previewing && !hasServiceCape && !hasConfigCape) {
            return;
        }

        String capeId = previewing ? null : service.getCapeId(playerId);
        if (capeId != null) {
            CapeAnimationHelper.markCapeVisible(capeId);
        }

        ResourceLocation capeTexture = previewing ? preview.texture() : null;
        if (!previewing && hasServiceCape) {
            capeTexture = service.getCapeLocation(playerId);
        }
        if (capeTexture == null && !previewing && hasConfigCape) {
            capeTexture = CapeService.getInstance().getCapeLocation(null, config.activeCapeHash);
        }
        if (capeTexture == null) {
            ci.cancel();
            return;
        }

        ResourceLocation finalTexture =
                CapeAnimationHelper.resolveCurrentFrame(capeTexture, capeId);
        if (finalTexture == null) {
            ci.cancel();
            return;
        }

        RenderType renderType = QuickSkin.MOD_ID.equals(finalTexture.getNamespace())
                || TextureAlphaDetector.hasTransparency(finalTexture)
                ? RenderType.entityTranslucent(finalTexture)
                : RenderType.entitySolid(finalTexture);
        VertexConsumer vertices = buffer.getBuffer(renderType);

        @SuppressWarnings("unchecked")
        HumanoidModel<PlayerRenderState> capeModel =
                (HumanoidModel<PlayerRenderState>) (HumanoidModel<?>) model;
        ((CapeLayer) (Object) this).getParentModel().copyPropertiesTo(capeModel);
        capeModel.setupAnim(renderState);
        capeModel.renderToBuffer(poseStack, vertices, packedLight, OverlayTexture.NO_OVERLAY);
        ci.cancel();
    }
}
