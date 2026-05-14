package me.cortex.voxy.server;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.network.VoxyNetworkHandler;
import me.cortex.voxy.common.network.VoxyPacketPayload;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.service.LodStreamingService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side initialization and management for LOD streaming.
 * <p>
 * Handles:
 * <ul>
 * <li>Server lifecycle events</li>
 * <li>Chunk load events for automatic LOD generation</li>
 * <li>Player sync requests</li>
 * <li>Per-dimension streaming services</li>
 * </ul>
 */
public class VoxyServer {

    // Per-dimension streaming services
    private static final ConcurrentHashMap<ServerLevel, LodStreamingService> streamingServices = new ConcurrentHashMap<>();

    // Current server reference
    private static MinecraftServer currentServer;

    // Is server-side LOD generation enabled
    private static boolean isInitialized = false;

    // Loaded at ServerStartedEvent, accessible by LodStreamingService et al.
    private static volatile VoxyServerConfig serverConfig = new VoxyServerConfig();

    public static VoxyServerConfig getServerConfig() {
        return serverConfig;
    }

    /**
     * Initialize server-side LOD streaming.
     * Called when the server starts.
     */
    /**
     * Initialize Voxy <i>before</i> the server starts loading the world.
     * <p>
     * This is the first server lifecycle event NeoForge fires. The integrated /
     * dedicated server creates the {@link VoxyCommon} instance here, flips the
     * {@code isInitialized} flag and loads the server config so the subsequent
     * {@link ChunkEvent.Load} events that fire while the spawn chunks load are
     * actually ingested. Doing the init at {@link ServerStartedEvent} (as it used
     * to be) loses the spawn chunks because they finish loading before that event.
     */
    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        currentServer = event.getServer();

        try {
            serverConfig = VoxyServerConfig.load(currentServer.getServerDirectory());
            Logger.info("Loaded voxy-server-config.json (maxStreamingRadiusChunks="
                    + serverConfig.maxStreamingRadiusChunks + " [=" +
                    serverConfig.getMaxStreamingRadiusSections() + " voxy sections], serializeThreads="
                    + serverConfig.effectiveSerializeThreads() + ")");
        } catch (Exception e) {
            Logger.error("Failed to load voxy-server-config.json, using defaults", e);
            serverConfig = new VoxyServerConfig();
        }

        // Dedicated server: provision the server-side LOD storage now so the spawn
        // chunks generated during world load can already be ingested. Single-player
        // keeps using the client's instance (the integrated server piggybacks on it).
        if (VoxyCommon.IS_DEDICATED_SERVER && VoxyCommon.getInstance() == null) {
            Logger.info("Creating VoxyServerInstance for server-side LOD storage");
            VoxyCommon.setInstanceFactory(VoxyServerInstance::new);
            VoxyCommon.createInstance();
        }

        isInitialized = true;
        Logger.info("VoxyServer pre-init complete - spawn chunks will be ingested");
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        // Network handler can only safely register once payload registration has been
        // completed by NeoForge (which happens before ServerStartedEvent). Keep it
        // here so the registration order is deterministic.
        VoxyNetworkHandler.setServerMessageHandler(VoxyServer::handleClientMessage);
        Logger.info("VoxyServer ready - LOD streaming handlers registered");
    }

    /**
     * Handle chunk load events to generate LOD data from chunks.
     */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!isInitialized) {
            if (serverConfig.isLogIngestSkipsEffective()) {
                Logger.info("[VoxyIngest] skip: !isInitialized");
            }
            return;
        }

        LevelAccessor level = event.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        // Only process full LevelChunks, not proto-chunks. A chunk loaded mid
        // generation (PROTO status) is not safely readable here; vanilla will fire
        // ChunkEvent.Load again once it is promoted to FULL.
        if (!(event.getChunk() instanceof LevelChunk levelChunk)) {
            if (serverConfig.isLogIngestSkipsEffective()) {
                Logger.info("[VoxyIngest] skip (proto-chunk) at " + event.getChunk().getPos() +
                        " status=" + event.getChunk().getPersistedStatus());
            }
            return;
        }

        WorldIdentifier worldId = WorldIdentifier.of(serverLevel);
        if (worldId == null) {
            if (serverConfig.isLogIngestSkipsEffective()) {
                Logger.info("[VoxyIngest] skip: WorldIdentifier null for " + serverLevel.dimension().location());
            }
            return;
        }

        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            if (serverConfig.isLogIngestSkipsEffective()) {
                Logger.info("[VoxyIngest] skip: VoxyCommon instance null at " + levelChunk.getPos());
            }
            return;
        }
        if (!instance.isIngestEnabled(worldId)) {
            if (serverConfig.isLogIngestSkipsEffective()) {
                Logger.info("[VoxyIngest] skip: ingest disabled for " + worldId);
            }
            return;
        }

        var engine = instance.getOrCreate(worldId);
        if (engine == null) {
            if (serverConfig.isLogIngestSkipsEffective()) {
                Logger.info("[VoxyIngest] skip: engine null for " + worldId);
            }
            return;
        }

        try {
            boolean queued = instance.getIngestService().enqueueIngest(engine, levelChunk);
            if (queued) {
                ingestedChunks.add(chunkKey(levelChunk.getPos().x, levelChunk.getPos().z));
            }
            if (serverConfig.isLogIngestSkipsEffective()) {
                if (queued) {
                    Logger.info("[VoxyIngest] ok at " + levelChunk.getPos() +
                            " status=" + levelChunk.getPersistedStatus());
                } else {
                    Logger.info("[VoxyIngest] enqueueIngest returned false at " + levelChunk.getPos() +
                            " status=" + levelChunk.getPersistedStatus() +
                            " (no lighting yet or queue rejected) — retry sweep will retry");
                }
            }
        } catch (Exception e) {
            Logger.error("Failed to ingest server chunk at " + levelChunk.getPos(), e);
        }
    }

    /**
     * Forget chunks on unload so a future reload retries ingest fresh.
     */
    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getChunk() instanceof LevelChunk levelChunk) {
            ingestedChunks.remove(chunkKey(levelChunk.getPos().x, levelChunk.getPos().z));
        }
    }

    /**
     * Handle messages from clients.
     */
    private static void handleClientMessage(ServerPlayer player, VoxyPacketPayload payload) {
        switch (payload.messageType()) {
            case VoxyPacketPayload.MSG_SYNC_REQUEST -> handleSyncRequest(player);
            case VoxyPacketPayload.MSG_CACHE_RESPONSE -> handleCacheResponse(player, payload);
            case VoxyPacketPayload.MSG_RATE_UPDATE -> handleRateUpdate(player, payload);
            case VoxyPacketPayload.MSG_REQUEST_SECTIONS -> handleSectionRequest(player, payload);
            case VoxyPacketPayload.MSG_CLIENT_HINT -> handleClientHint(player, payload);
        }
    }

    /**
     * Handle sync request from a player.
     */
    private static void handleSyncRequest(ServerPlayer player) {
        Logger.info("Received sync request from " + player.getName().getString());

        ServerLevel level = player.serverLevel();
        WorldIdentifier worldId = WorldIdentifier.of(level);

        if (worldId == null) {
            Logger.warn("No WorldIdentifier for level " + level.dimension().location());
            VoxyNetworkHandler.sendToPlayer(player,
                    new VoxyPacketPayload(VoxyPacketPayload.MSG_SYNC_COMPLETE, new byte[0]));
            return;
        }

        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            Logger.warn("VoxyCommon instance not available");
            VoxyNetworkHandler.sendToPlayer(player,
                    new VoxyPacketPayload(VoxyPacketPayload.MSG_SYNC_COMPLETE, new byte[0]));
            return;
        }

        WorldEngine engine = instance.getNullable(worldId);
        if (engine == null) {
            Logger.warn("No WorldEngine available for level " + level.dimension().location() +
                    " - no LOD data yet. Chunks must be loaded first.");
            VoxyNetworkHandler.sendToPlayer(player,
                    new VoxyPacketPayload(VoxyPacketPayload.MSG_SYNC_COMPLETE, new byte[0]));
            return;
        }

        // Get or create streaming service for this level
        LodStreamingService service = streamingServices.computeIfAbsent(level,
                l -> {
                    Logger.info("Creating LodStreamingService for " + level.dimension().location());
                    return new LodStreamingService(engine, level);
                });

        // Actually start the sync for this player
        service.startSyncForPlayer(player);
        Logger.info(
                "LOD streaming started for " + player.getName().getString() + " in " + level.dimension().location());
    }

    /**
     * Handle cache response from client.
     */
    private static void handleCacheResponse(ServerPlayer player, VoxyPacketPayload payload) {
        // Forward to streaming service if exists
        var service = streamingServices.get(player.serverLevel());
        if (service != null) {
            service.handleCacheResponse(player, payload);
        }
    }

    /**
     * Handle rate update from client.
     */
    private static void handleRateUpdate(ServerPlayer player, VoxyPacketPayload payload) {
        // Forward to streaming service if exists
        var service = streamingServices.get(player.serverLevel());
        if (service != null) {
            service.handleRateUpdate(player, payload);
        }
    }

    /**
     * Handle client streaming-radius hint.
     */
    private static void handleClientHint(ServerPlayer player, VoxyPacketPayload payload) {
        var service = streamingServices.get(player.serverLevel());
        if (service != null) {
            service.handleClientHint(player, payload);
        }
    }

    /**
     * Handle section request from client (pull mode).
     */
    private static void handleSectionRequest(ServerPlayer player, VoxyPacketPayload payload) {
        ServerLevel level = player.serverLevel();
        WorldIdentifier worldId = WorldIdentifier.of(level);

        if (worldId == null) {
            return;
        }

        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            return;
        }

        WorldEngine engine = instance.getNullable(worldId);
        if (engine == null) {
            return;
        }

        // Get or create streaming service for this level
        LodStreamingService service = streamingServices.computeIfAbsent(level,
                l -> {
                    Logger.info("Creating LodStreamingService for " + level.dimension().location());
                    return new LodStreamingService(engine, level);
                });

        // Forward section request to streaming service
        service.handleSectionRequest(player, payload);
    }

    /**
     * Handle level load events.
     */
    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            Logger.info("Server level loaded: " + serverLevel.dimension().location());
        }
    }

    /**
     * Shutdown all streaming services.
     * Called when the server stops.
     */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        Logger.info("VoxyServer shutting down - closing streaming services");

        isInitialized = false;

        for (var service : streamingServices.values()) {
            try {
                service.close();
            } catch (Exception e) {
                Logger.error("Error closing streaming service: " + e.getMessage());
            }
        }
        streamingServices.clear();
        ingestedChunks.clear();

        // Shutdown VoxyCommon instance (dedicated server only)
        // In singleplayer, the client handles the instance lifecycle
        if (VoxyCommon.IS_DEDICATED_SERVER && VoxyCommon.getInstance() != null) {
            Logger.info("Shutting down VoxyServerInstance");
            VoxyCommon.shutdownInstance();
        }

        currentServer = null;
    }

    /**
     * Handle player disconnect to clean up streaming state.
     */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            var level = player.serverLevel();
            var service = streamingServices.get(level);
            if (service != null) {
                service.onPlayerDisconnect(player.getUUID());
            }
            VoxyNetworkHandler.removePlayer(player.getUUID());
        }
    }

    /**
     * Handle server tick for command processing.
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        // Tick chunk processors for generate command
        VoxyServerCommands.tickProcessors();

        // Periodic retry sweep: chunks may be loaded server-side without ever
        // having triggered a successful voxy ingest (proto-chunk during the
        // event, lighting not ready, event handler not yet registered, etc).
        // Every ~3s, walk currently-loaded chunks around each player and re-fire
        // enqueueIngest on any whose engine section is still absent.
        retryTickCounter++;
        if (retryTickCounter >= 60) { // 60 ticks ~= 3s
            retryTickCounter = 0;
            retryMissingIngest(event.getServer());
        }
    }

    private static int retryTickCounter = 0;

    /** Tracks chunks that have already been enqueued for ingest this session. */
    private static final java.util.Set<Long> ingestedChunks =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static long chunkKey(int cx, int cz) {
        return (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
    }

    private static boolean engineHasContentForChunk(WorldEngine engine, int cx, int cz,
            int minSectionY, int maxSectionYExclusive) {
        int vsx = cx >> 1;
        int vsz = cz >> 1;
        for (int vy = minSectionY; vy < maxSectionYExclusive; vy++) {
            long key = WorldEngine.getWorldSectionId(0, vsx, vy, vsz);
            var s = engine.acquireIfExists(key);
            if (s != null) {
                try {
                    if (s.getNonEmptyBlockCount() > 0) {
                        return true;
                    }
                } finally {
                    s.release();
                }
            }
        }
        return false;
    }

    /**
     * For each connected player, scan every currently-loaded chunk inside the
     * server's view distance and re-enqueue ingest for chunks we've never seen
     * enqueue succeed. Dedupes via {@link #ingestedChunks} so repeat scans don't
     * pound the same chunks. Catches chunks that ChunkEvent.Load missed (already
     * loaded before listener attached, proto state during event, etc).
     */
    private static int retryHeartbeatCounter = 0;

    private static void retryMissingIngest(MinecraftServer server) {
        if (!isInitialized || server == null) return;
        var instance = VoxyCommon.getInstance();
        if (instance == null) return;
        int viewDist = server.getPlayerList().getViewDistance();
        for (ServerLevel level : server.getAllLevels()) {
            WorldIdentifier worldId = WorldIdentifier.of(level);
            if (worldId == null || !instance.isIngestEnabled(worldId)) continue;
            WorldEngine engine = instance.getNullable(worldId);
            if (engine == null) continue;
            int minSectionY = level.getMinBuildHeight() >> 5;
            int maxSectionYExclusive = (level.getMaxBuildHeight() + 31) >> 5;
            var chunkSource = level.getChunkSource();
            int considered = 0;
            int alreadyDone = 0;
            int notLoaded = 0;
            int enqueuedOK = 0;
            int enqueueFalse = 0;
            int staleEvicted = 0;
            for (ServerPlayer player : level.players()) {
                int pcx = player.getBlockX() >> 4;
                int pcz = player.getBlockZ() >> 4;
                for (int dx = -viewDist; dx <= viewDist; dx++) {
                    for (int dz = -viewDist; dz <= viewDist; dz++) {
                        int cx = pcx + dx;
                        int cz = pcz + dz;
                        considered++;
                        long key = chunkKey(cx, cz);
                        if (ingestedChunks.contains(key)) {
                            // Verify engine actually has data for this chunk. If the
                            // "done" mark is stale (worker dropped task, engine reset,
                            // etc), evict the entry so the next retry actually retries.
                            if (engineHasContentForChunk(engine, cx, cz, minSectionY, maxSectionYExclusive)) {
                                alreadyDone++;
                                continue;
                            }
                            ingestedChunks.remove(key);
                            staleEvicted++;
                        }
                        LevelChunk chunk = chunkSource.getChunkNow(cx, cz);
                        if (chunk == null) {
                            notLoaded++;
                            continue;
                        }
                        try {
                            if (instance.getIngestService().enqueueIngest(engine, chunk)) {
                                ingestedChunks.add(key);
                                enqueuedOK++;
                                if (serverConfig.isLogIngestSkipsEffective()) {
                                    Logger.info("[VoxyIngest] retry ok at " + chunk.getPos());
                                }
                            } else {
                                enqueueFalse++;
                                if (serverConfig.isLogIngestSkipsEffective()) {
                                    Logger.info("[VoxyIngest] retry enqueue returned false at " +
                                            chunk.getPos() + " (lighting/etc not ready)");
                                }
                            }
                        } catch (Exception e) {
                            Logger.error("retry ingest failed at " + chunk.getPos(), e);
                        }
                    }
                }
            }
            // Heartbeat: log sweep stats every ~30s so it is obvious the
            // retry path is actually running even when nothing changed.
            retryHeartbeatCounter++;
            if (retryHeartbeatCounter >= 10) {
                retryHeartbeatCounter = 0;
                if (serverConfig.isLogIngestSkipsEffective()) {
                    Logger.info("[VoxyIngest] retry sweep " + level.dimension().location() +
                            ": considered=" + considered + " alreadyDone=" + alreadyDone +
                            " staleEvicted=" + staleEvicted +
                            " notLoaded=" + notLoaded + " enqueuedOK=" + enqueuedOK +
                            " enqueueFalse=" + enqueueFalse +
                            " setSize=" + ingestedChunks.size());
                }
            }
        }
    }

    /**
     * Check if the server is available.
     */
    public static boolean isServerAvailable() {
        return currentServer != null;
    }

    /**
     * Get the current server.
     */
    public static MinecraftServer getServer() {
        return currentServer;
    }

    /**
     * Get streaming service for a level.
     */
    public static LodStreamingService getStreamingService(ServerLevel level) {
        return streamingServices.get(level);
    }

    /**
     * Get all active streaming services.
     */
    public static java.util.Collection<LodStreamingService> getAllStreamingServices() {
        return streamingServices.values();
    }

    /**
     * Broadcast sync request to all players in a level.
     */
    public static void broadcastSync(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            handleSyncRequest(player);
        }
    }
}
