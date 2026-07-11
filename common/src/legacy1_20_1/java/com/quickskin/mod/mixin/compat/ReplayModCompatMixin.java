package com.quickskin.mod.mixin.compat;

import com.quickskin.mod.QuickSkin;
import com.quickskin.mod.networking.ClientNetworkHandler;
import com.quickskin.mod.networking.ModNetworking;
import dev.architectury.networking.NetworkManager;
import dev.architectury.utils.Env;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to intercept custom payload packets for Replay Mod compatibility.
 *
 * When Replay Mod plays back a recording, it creates a fake network connection
 * (EmbeddedChannel) that bypasses the standard Architectury/Fabric/Forge packet
 * handlers. This mixin manually intercepts QuickSkin packets directly at the
 * ClientPacketListener level, ensuring they are processed even during replay.
 */
@Mixin(ClientPacketListener.class)
public class ReplayModCompatMixin {

    @Inject(method = "handleCustomPayload(Lnet/minecraft/network/protocol/game/ClientboundCustomPayloadPacket;)V", at = @At("HEAD"), cancellable = true)
    private void quickskin$interceptReplayPackets(ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
        ResourceLocation id = packet.getIdentifier();

        // Check if the packet belongs to QuickSkin
        if (!id.getNamespace().equals(QuickSkin.MOD_ID)) {
            return;
        }

        // Reconstruct data buffer - copy the internal data and reset reader index
        FriendlyByteBuf originalBuf = packet.getData();
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.copiedBuffer(originalBuf.copy()));
        buf.readerIndex(0); // Ensure we read from the beginning

        // Create a dummy context for packet handling
        NetworkManager.PacketContext context = new NetworkManager.PacketContext() {
            @Override
            public net.minecraft.world.entity.player.Player getPlayer() {
                return Minecraft.getInstance().player;
            }

            @Override
            public void queue(Runnable runnable) {
                Minecraft.getInstance().execute(runnable);
            }

            @Override
            public Env getEnvironment() {
                return Env.CLIENT;
            }
        };

        // Manually route to ClientNetworkHandler based on packet ID
        boolean handled = false;
        try {
            if (id.equals(ModNetworking.SYNC_APPEARANCE)) {
                ClientNetworkHandler.handleSyncAppearance(buf, context);
                handled = true;
            } else if (id.equals(ModNetworking.SEND_TEXTURE)) {
                ClientNetworkHandler.handleSendTexture(buf, context);
                handled = true;
            } else if (id.equals(ModNetworking.SEND_TEXTURE_CHUNK)) {
                ClientNetworkHandler.handleSendTextureChunk(buf, context);
                handled = true;
            } else if (id.equals(ModNetworking.SEND_ANIMATION_METADATA)) {
                ClientNetworkHandler.handleSendAnimationMetadata(buf, context);
                handled = true;
            } else if (id.equals(ModNetworking.SYNC_SERVER_CONFIG)) {
                ClientNetworkHandler.handleSyncServerConfig(buf, context);
                handled = true;
            } else if (id.equals(ModNetworking.COOLDOWN_UPDATE)) {
                ClientNetworkHandler.handleCooldownUpdate(buf, context);
                handled = true;
            }
        } catch (Exception e) {
        } finally {
            buf.release();
        }

        // Cancel original handler to prevent double-processing
        if (handled) {
            ci.cancel();
        }
    }
}
