package me.cortex.voxy.neoforge;

import me.cortex.voxy.server.VoxyServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Forwards NeoForge server-side events to {@link VoxyServer}. Registered on both
 * physical sides: the integrated server fires them too.
 */
public class NeoForgeServerEvents {

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        VoxyServer.onServerAboutToStart(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        VoxyServer.onServerStarted();
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        VoxyServer.onChunkLoad(event.getLevel(), event.getChunk());
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        VoxyServer.onChunkUnload(event.getChunk());
    }

    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        VoxyServer.onLevelLoad(event.getLevel());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        VoxyServer.onServerStopping();
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        VoxyServer.onPlayerLogout(event.getEntity());
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        VoxyServer.onPlayerChangedDimension(event.getEntity());
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        VoxyServer.onServerTickEnd(event.getServer());
    }
}
