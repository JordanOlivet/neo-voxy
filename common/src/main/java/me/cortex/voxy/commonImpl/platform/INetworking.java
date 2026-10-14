package me.cortex.voxy.commonImpl.platform;

import me.cortex.voxy.common.network.VoxyPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * Loader side of the Voxy payload channel: sending, and whether the current
 * client connection negotiated the channel. Payload registration happens in
 * each loader's entry point, which hands incoming payloads to
 * {@link me.cortex.voxy.common.network.VoxyNetworkHandler}.
 */
public interface INetworking {

    void sendToPlayer(ServerPlayer player, VoxyPacketPayload payload);

    /** Client to server. Only call when {@link #clientHasServerChannel()} is true. */
    void sendToServer(VoxyPacketPayload payload);

    /** Whether the active client connection negotiated the Voxy channel (server runs Voxy). */
    boolean clientHasServerChannel();
}
