package me.cortex.voxy.fabric;

import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.server.VoxyServer;
import me.cortex.voxy.server.VoxyServerCommands;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Fabric common entry point: the counterpart of the NeoForge {@code @Mod}
 * constructor and {@code NeoForgeServerEvents}. Server hooks run on both
 * physical sides, since the integrated server fires them too.
 */
public class VoxyFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        var loader = FabricLoader.getInstance();
        VoxyCommon.cleanInit(
                loader.getModContainer("voxy").orElseThrow().getMetadata().getVersion().getFriendlyString(),
                loader.getEnvironmentType() == EnvType.SERVER);

        FabricNetworking.registerCommon();

        // SERVER_STARTING fires before the worlds load, like NeoForge's
        // ServerAboutToStartEvent, so spawn chunks get ingested.
        ServerLifecycleEvents.SERVER_STARTING.register(VoxyServer::onServerAboutToStart);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> VoxyServer.onServerStarted());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> VoxyServer.onServerStopping());
        ServerChunkEvents.CHUNK_LOAD.register(VoxyServer::onChunkLoad);
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> VoxyServer.onChunkUnload(chunk));
        ServerWorldEvents.LOAD.register((server, level) -> VoxyServer.onLevelLoad(level));
        ServerTickEvents.END_SERVER_TICK.register(VoxyServer::onServerTickEnd);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> VoxyServer.onPlayerLogout(handler.getPlayer()));
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register(
                (player, origin, destination) -> VoxyServer.onPlayerChangedDimension(player));

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> VoxyServerCommands.register(dispatcher));
    }
}
