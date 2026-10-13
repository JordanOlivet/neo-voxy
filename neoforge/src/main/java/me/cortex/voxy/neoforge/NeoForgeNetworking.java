package me.cortex.voxy.neoforge;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.network.VoxyNetworkHandler;
import me.cortex.voxy.common.network.VoxyPacketPayload;
import me.cortex.voxy.commonImpl.platform.INetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class NeoForgeNetworking implements INetworking {

    /**
     * Registers the Voxy payload as an optional bidirectional play payload, so
     * vanilla servers and clients without Voxy can still connect.
     */
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VoxyNetworkHandler.PROTOCOL_VERSION).optional();

        registrar.playBidirectional(
                VoxyPacketPayload.TYPE,
                VoxyPacketPayload.Codec.INSTANCE,
                NeoForgeNetworking::handlePayload);

        Logger.info("Registered Voxy LOD streaming network handler");
    }

    private static void handlePayload(VoxyPacketPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer serverPlayer) {
            VoxyNetworkHandler.handleServerbound(serverPlayer, payload);
        } else {
            VoxyNetworkHandler.handleClientbound(payload);
        }
    }

    @Override
    public void sendToPlayer(ServerPlayer player, VoxyPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    @Override
    public void sendToServer(VoxyPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    @Override
    public boolean clientHasServerChannel() {
        var connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.hasChannel(VoxyPacketPayload.TYPE);
    }
}
