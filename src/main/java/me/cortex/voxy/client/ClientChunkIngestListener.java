package me.cortex.voxy.client;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.network.VoxyNetworkHandler;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * Client-side fallback path for multiplayer servers that do <strong>not</strong>
 * run Voxy. Subscribes to {@link ChunkEvent.Load} on {@link ClientLevel} and
 * ingests the freshly loaded chunk into the local {@code WorldEngine} so the
 * client can render its own LODs.
 *
 * <p>Skipped when:
 * <ul>
 * <li>Voxy is disabled,</li>
 * <li>{@code ingestEnabled} is false,</li>
 * <li>we are in single-player (integrated server already runs the server-side
 * ingest path),</li>
 * <li>the effective multiplayer mode is not {@code CLIENT_ONLY} (server with
 * Voxy is streaming for us, no need to double-ingest).</li>
 * </ul>
 */
public class ClientChunkIngestListener {

    private static volatile boolean loggedMode = false;

    @SubscribeEvent
    public static void onClientChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ClientLevel)) {
            return;
        }
        if (!(event.getChunk() instanceof LevelChunk levelChunk)) {
            return;
        }

        var cfg = VoxyConfig.CONFIG;
        if (!cfg.enabled || !cfg.ingestEnabled) {
            return;
        }

        // Single-player runs the integrated server's ChunkEvent.Load on ServerLevel
        // already, which goes through VoxyServer.onChunkLoad → enqueueIngest. No
        // need to duplicate from the client side.
        if (VoxyNetworkHandler.isSinglePlayer()) {
            return;
        }

        if (VoxyNetworkHandler.getEffectiveMode() != VoxyConfig.MultiplayerMode.CLIENT_ONLY) {
            return;
        }

        if (!loggedMode) {
            loggedMode = true;
            Logger.info("ClientChunkIngestListener active — ingesting client chunks locally (mode=CLIENT_ONLY)");
        }

        try {
            VoxelIngestService.tryAutoIngestChunk(levelChunk);
        } catch (Throwable t) {
            Logger.error("Client-side chunk ingest failed for " + levelChunk.getPos(), t);
        }
    }

    public static void resetLogState() {
        loggedMode = false;
    }
}
