package com.quickskin.mod.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
//? if <1.21.11 {
import com.mojang.blaze3d.vertex.VertexConsumer;
//?} else {
//?}
import com.quickskin.mod.client.compat.CPMCompatIntegration;
import com.quickskin.mod.common.util.TextureAlphaDetector;
import com.quickskin.mod.config.ClientConfig;
import net.minecraft.client.model.geom.ModelPart;
//? if <1.21.11 {
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.resources.ResourceLocation;
//?} else {
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Mixin to enable transparent arm rendering in first-person view.
 * This mixin targets AvatarRenderer, which is responsible for rendering the arm model.
 * It redirects submitModelPart calls to use translucent render types for skins with transparency.
 *
 * In MC 1.21.11, the vanilla renderHand already uses entityTranslucent by default,
 * so this mixin is mostly a safety net and ensures correct behavior when other mods
 * might change the render type.
 */
//? if <1.21.11 {
@Mixin(value = PlayerRenderer.class, priority = 1100) // Higher priority to override TLSkinCape and other mods
//?} else {
@Mixin(value = AvatarRenderer.class, priority = 1100)
//?}
public class ItemInHandRendererMixin {

    /**
     * Redirects the submitModelPart call within AvatarRenderer's private renderHand method.
     * This allows us to ensure entityTranslucent is used when the player's skin has
     * transparent pixels.
     */
    @Redirect(
//? if <1.21.11 {
            method = "renderHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/client/model/geom/ModelPart;Lnet/minecraft/client/model/geom/ModelPart;)V",
//?} else {
            method = "renderHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;Lnet/minecraft/client/model/geom/ModelPart;Z)V",
//?}
            at = @At(
                    value = "INVOKE",
//? if <1.21.11 {
                    target = "Lnet/minecraft/client/renderer/MultiBufferSource;getBuffer(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"
//?} else {
                    target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModelPart(Lnet/minecraft/client/model/geom/ModelPart;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IILnet/minecraft/client/renderer/texture/TextureAtlasSprite;)V"
//?}
            ),
            require = 0,
//? if <1.21.11 {
            // Vanilla requests one buffer for the arm and one for the sleeve.
            expect = 2,
            allow = 2
//?} else {
            expect = 1,
            allow = 1
//?}
    )
//? if <1.21.11 {
    private VertexConsumer quickskin$redirectRenderHandBuffer(MultiBufferSource instance, RenderType renderType,
                                                              // Injected arguments from renderHand:
                                                              PoseStack poseStack, MultiBufferSource buffer, int packedLight, AbstractClientPlayer player, ModelPart arm, ModelPart sleeve) {
        if (CPMCompatIntegration.shouldDeferToCPM()) return instance.getBuffer(renderType);

        // When CPM has a bound player, it manages the texture pipeline and already converts
        // entitySolid→entityTranslucent when needed. Overriding the RenderType here would
        // use a different ResourceLocation (quickskin:skins/hash vs CPM's cpm:cpm_X),
        // causing first-person arm texture artifacts.
        if (CPMCompatIntegration.isCPMActivelyRendering()) return instance.getBuffer(renderType);

        // Check if transparency is disabled globally by config
        if (ClientConfig.getInstance().shouldDisableSkinTransparency()) {
            return instance.getBuffer(renderType);
//?} else {
    private void quickskin$redirectSubmitModelPart(SubmitNodeCollector collector, ModelPart part,
                                                    PoseStack poseStack, RenderType renderType,
                                                    int packedLight, int overlay, TextureAtlasSprite sprite,
                                                    // Injected arguments from renderHand:
                                                    PoseStack poseStackOuter, SubmitNodeCollector bufferOuter,
                                                    int packedLightOuter, Identifier skinTexture,
                                                    ModelPart arm, boolean slim) {
        if (CPMCompatIntegration.shouldDeferToCPM()) {
            collector.submitModelPart(part, poseStack, renderType, packedLight, overlay, sprite);
            return;
//?}
        }

//? if <1.21 {
        ResourceLocation skinTexture = player.getSkinTextureLocation();
//?} else if <1.21.11 {
        ResourceLocation skinTexture = player.getSkin().texture();
//?} else {
        if (CPMCompatIntegration.isCPMActivelyRendering()) {
            collector.submitModelPart(part, poseStack, renderType, packedLight, overlay, sprite);
            return;
        }

        if (ClientConfig.getInstance().shouldDisableSkinTransparency()) {
            collector.submitModelPart(part, poseStack, renderType, packedLight, overlay, sprite);
            return;
        }

//?}
        if (skinTexture == null) {
//? if <1.21.11 {
            return instance.getBuffer(renderType);
//?} else {
            collector.submitModelPart(part, poseStack, renderType, packedLight, overlay, sprite);
            return;
//?}
        }

        // Determine if the skin needs a translucent render type
        boolean needsTranslucent = TextureAlphaDetector.hasTransparency(skinTexture);

        if (needsTranslucent) {
//? if <1.21.11 {
            // The vanilla method calls getBuffer for both the solid arm and the translucent sleeve.
            // By forcing entityTranslucent here, we correctly render the arm with transparency.
            // It's harmless to also request a translucent buffer for the sleeve, which already uses it.
            // We use entityTranslucent instead of entityTranslucentCull to avoid z-fighting on complex layers.
            return instance.getBuffer(RenderType.entityTranslucent(skinTexture));
//?} else {
            RenderType translucentType = RenderTypes.entityTranslucent(skinTexture);
            collector.submitModelPart(part, poseStack, translucentType, packedLight, overlay, sprite);
        } else {
            collector.submitModelPart(part, poseStack, renderType, packedLight, overlay, sprite);
//?}
        }
//? if <1.21.11 {

        // If no transparency is needed, use the original render type provided by the vanilla method.
        return instance.getBuffer(renderType);
//?} else {
//?}
    }
}
