package me.cortex.voxy.common.network;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Central network handler for Voxy LOD streaming.
 * <p>
 * Handles registration of custom payloads with NeoForge and dispatches
 * incoming messages to appropriate handlers.
 */
public class VoxyNetworkHandler {

    private static final String PROTOCOL_VERSION = "1";

    // Server-side: handlers for client→server messages
    private static BiConsumer<ServerPlayer, VoxyPacketPayload> serverMessageHandler;

    // Client-side: handler for server→client messages
    private static java.util.function.Consumer<VoxyPacketPayload> clientMessageHandler;

    // Track which players have LOD streaming enabled
    private static final ConcurrentHashMap<UUID, Boolean> playerCapabilities = new ConcurrentHashMap<>();

    /**
     * Register the payload handler with NeoForge.
     * Call this from the mod's RegisterPayloadHandlersEvent.
     */
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION).optional();

        registrar.playBidirectional(
                VoxyPacketPayload.TYPE,
                VoxyPacketPayload.Codec.INSTANCE,
                VoxyNetworkHandler::handlePayload);

        Logger.info("Registered Voxy LOD streaming network handler");
    }

    /**
     * Set the server-side message handler.
     * Called when a client sends a message to the server.
     */
    public static void setServerMessageHandler(BiConsumer<ServerPlayer, VoxyPacketPayload> handler) {
        serverMessageHandler = handler;
    }

    /**
     * Set the client-side message handler.
     * Called when the server sends a message to the client.
     */
    public static void setClientMessageHandler(java.util.function.Consumer<VoxyPacketPayload> handler) {
        clientMessageHandler = handler;
    }

    /**
     * Handle incoming payload from either direction.
     */
    private static void handlePayload(VoxyPacketPayload payload, IPayloadContext context) {
        // Determine if this is server or client side
        if (context.player() instanceof ServerPlayer serverPlayer) {
            // Server receiving from client
            handleServerbound(serverPlayer, payload);
        } else {
            // Client receiving from server
            handleClientbound(payload);
        }
    }

    /**
     * Handle messages received on the server from clients.
     */
    private static void handleServerbound(ServerPlayer player, VoxyPacketPayload payload) {
        if (serverMessageHandler != null) {
            try {
                serverMessageHandler.accept(player, payload);
            } catch (Exception e) {
                Logger.error("Error handling serverbound Voxy packet: " + e.getMessage());
                Logger.error(e);
            }
        }
    }

    /**
     * Handle messages received on the client from server.
     */
    private static void handleClientbound(VoxyPacketPayload payload) {
        if (clientMessageHandler != null) {
            try {
                clientMessageHandler.accept(payload);
            } catch (Exception e) {
                Logger.error("Error handling clientbound Voxy packet: " + e.getMessage());
                Logger.error(e);
            }
        }
    }

    // ==================== //
    // Sending Methods //
    // ==================== //

    /**
     * Send a payload to a specific player (server→client).
     */
    public static void sendToPlayer(ServerPlayer player, VoxyPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    /**
     * Send a payload to the server (client→server).
     * <p>
     * Defensive: returns {@code false} (instead of throwing) when the channel is
     * not negotiated on the active connection (e.g. vanilla server). Callers may
     * use the return value to detect lack of server-side mod support.
     */
    public static boolean sendToServer(VoxyPacketPayload payload) {
        try {
            if (!hasServerSupport()) {
                return false;
            }
            PacketDistributor.sendToServer(payload);
            return true;
        } catch (Throwable t) {
            Logger.warn("sendToServer failed (treating server as having no Voxy channel): " + t.getMessage());
            serverSupportCached = Boolean.FALSE;
            return false;
        }
    }

    // Cached per-connection. Reset by resetConnectionState() on disconnect.
    private static volatile Boolean serverSupportCached = null;

    /**
     * Check whether the active client connection has negotiated the Voxy payload
     * channel (i.e. the server has the mod installed).
     */
    public static boolean hasServerSupport() {
        Boolean cached = serverSupportCached;
        if (cached != null) {
            return cached;
        }
        var mc = Minecraft.getInstance();
        if (mc == null) {
            return false;
        }
        var listener = mc.getConnection();
        if (listener == null) {
            return false;
        }
        boolean result;
        try {
            result = listener.hasChannel(VoxyPacketPayload.TYPE);
        } catch (Throwable t) {
            result = false;
        }
        serverSupportCached = result;
        return result;
    }

    /**
     * Clear cached connection state (call on disconnect / world transition).
     */
    public static void resetConnectionState() {
        serverSupportCached = null;
    }

    /**
     * Resolve the effective multiplayer mode after considering server capability.
     * <ul>
     * <li>{@code AUTO} → {@code SERVER_STREAM} if server has the mod, else {@code CLIENT_ONLY}.</li>
     * <li>Other values returned as-is.</li>
     * </ul>
     */
    public static VoxyConfig.MultiplayerMode getEffectiveMode() {
        if (isSinglePlayer()) {
            // Singleplayer is always handled by the integrated server's chunk-load path.
            return VoxyConfig.MultiplayerMode.SERVER_STREAM;
        }
        VoxyConfig.MultiplayerMode forced = VoxyConfig.CONFIG.multiplayerMode;
        if (forced == VoxyConfig.MultiplayerMode.CLIENT_ONLY) {
            return VoxyConfig.MultiplayerMode.CLIENT_ONLY;
        }
        if (forced == VoxyConfig.MultiplayerMode.SERVER_STREAM) {
            return VoxyConfig.MultiplayerMode.SERVER_STREAM;
        }
        return hasServerSupport()
                ? VoxyConfig.MultiplayerMode.SERVER_STREAM
                : VoxyConfig.MultiplayerMode.CLIENT_ONLY;
    }

    // ==================== //
    // Player Capabilities //
    // ==================== //

    /**
     * Mark a player as having LOD streaming capability.
     */
    public static void setPlayerCapable(UUID playerId, boolean capable) {
        if (capable) {
            playerCapabilities.put(playerId, true);
        } else {
            playerCapabilities.remove(playerId);
        }
    }

    /**
     * Check if a player has LOD streaming capability.
     */
    public static boolean isPlayerCapable(UUID playerId) {
        return playerCapabilities.getOrDefault(playerId, false);
    }

    /**
     * Remove a player from capability tracking (on disconnect).
     */
    public static void removePlayer(UUID playerId) {
        playerCapabilities.remove(playerId);
    }

    // ==================== //
    // Single-player Check //
    // ==================== //

    /**
     * Check if we're running in a single-player/integrated server context.
     * In single-player, we don't need network streaming - data is local.
     */
    public static boolean isSinglePlayer() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null)
            return false;

        // Check if we have an integrated server (single-player or LAN host)
        var integratedServer = mc.getSingleplayerServer();
        return integratedServer != null;
    }

    /**
     * Check if LOD streaming should be enabled.
     * Disabled in single-player since data is already local.
     */
    public static boolean shouldEnableStreaming() {
        return !isSinglePlayer();
    }
}
