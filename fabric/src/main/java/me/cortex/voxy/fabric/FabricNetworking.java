package me.cortex.voxy.fabric;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.network.VoxyNetworkHandler;
import me.cortex.voxy.common.network.VoxyPacketPayload;
import me.cortex.voxy.commonImpl.platform.INetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

public class FabricNetworking implements INetworking {

    /**
     * Registers the Voxy payload in both play directions and the server-side
     * receiver. Fabric negotiates channels without making them mandatory, so
     * vanilla servers and clients without Voxy can still connect. Receivers run
     * on the game thread, like NeoForge's default.
     */
    public static void registerCommon() {
        PayloadTypeRegistry.playC2S().register(VoxyPacketPayload.TYPE, VoxyPacketPayload.Codec.INSTANCE);
        PayloadTypeRegistry.playS2C().register(VoxyPacketPayload.TYPE, VoxyPacketPayload.Codec.INSTANCE);
        ServerPlayNetworking.registerGlobalReceiver(VoxyPacketPayload.TYPE,
                (payload, context) -> VoxyNetworkHandler.handleServerbound(context.player(), payload));
        Logger.info("Registered Voxy LOD streaming network handler");
    }

    public static void registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(VoxyPacketPayload.TYPE,
                (payload, context) -> VoxyNetworkHandler.handleClientbound(payload));
    }

    @Override
    public void sendToPlayer(ServerPlayer player, VoxyPacketPayload payload) {
        ServerPlayNetworking.send(player, payload);
    }

    @Override
    public void sendToServer(VoxyPacketPayload payload) {
        ClientPlayNetworking.send(payload);
    }

    @Override
    public boolean clientHasServerChannel() {
        return ClientPlayNetworking.canSend(VoxyPacketPayload.TYPE);
    }
}
