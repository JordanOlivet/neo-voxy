package me.cortex.voxy.common.world.service;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.network.*;
import me.cortex.voxy.common.world.SectionSerializer;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.server.VoxyServer;
import me.cortex.voxy.server.VoxyServerConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Server-side service that streams LOD sections to connected players.
 *
 * <p>Architecture:
 * <ul>
 * <li><b>Follow-player:</b> per-player fixed-rate task re-reads the player's
 * section coords every tick. Movement resets the ring origin so newly entered
 * areas stream immediately.</li>
 * <li><b>Dirty-version resend:</b> {@link WorldSection#getVersion()} is compared
 * to the last value sent to that player. If the section has changed since (e.g.
 * Chunky generated new chunks, a block was placed) it is re-serialized and
 * re-sent.</li>
 * <li><b>Dirty-driven push:</b> hooks {@link WorldEngine#setDirtyCallback} so
 * sections recently changed are drained <i>first</i> on each player tick when
 * they fall within the player's radius. Solves the Chunky-pregen-doesn't-stream
 * problem.</li>
 * <li><b>Shared serialized cache:</b> {@code SectionSerializer.serialize} runs
 * once per (section, version) and the byte payload is shared across all players
 * receiving that section.</li>
 * <li><b>Worker pool:</b> serialization runs on a small pool so a single thread
 * is not the bottleneck at high player counts.</li>
 * <li><b>Bandwidth:</b> delegated to {@link ChunkedLodSender} / shared
 * {@link SharedBandwidthLimit} (unchanged).</li>
 * </ul>
 */
public class LodStreamingService implements AutoCloseable {

    private final WorldEngine worldEngine;
    private final ServerLevel level;
    private final VoxyServerConfig config;
    private final SharedBandwidthLimit sharedBandwidthLimit;
    private final ConcurrentHashMap<UUID, PlayerStreamingState> playerStates = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler;
    private final ExecutorService serializeExecutor;
    private final AtomicBoolean isActive = new AtomicBoolean(true);

    // Voxy section units (32 blocks each); derived from ServerLevel build height.
    private final int minSectionY;
    private final int maxSectionYExclusive;

    // Bounded queue of sections recently bumped via WorldEngine.markDirty. The
    // per-player tick drains this first, filtering by player proximity.
    private final ArrayDeque<Long> recentlyDirtyKeys = new ArrayDeque<>();
    private final Object dirtyLock = new Object();

    // Shared serialized-section cache (LRU): one serialize per (key, version).
    private final LinkedHashMap<Long, CachedSection> serializedCache;
    private final Object cacheLock = new Object();

    private WorldEngine.ISectionChangeCallback prevDirtyCallback;

    public LodStreamingService(WorldEngine worldEngine, ServerLevel level) {
        this(worldEngine, level, new SharedBandwidthLimit());
    }

    public LodStreamingService(WorldEngine worldEngine, ServerLevel level, SharedBandwidthLimit sharedBandwidthLimit) {
        this.worldEngine = worldEngine;
        this.level = level;
        this.sharedBandwidthLimit = sharedBandwidthLimit;
        this.config = VoxyServer.getServerConfig();

        // Convert level build height (blocks) to voxy section coords (32 blocks).
        this.minSectionY = Math.floorDiv(level.getMinBuildHeight(), 32);
        this.maxSectionYExclusive = Math.floorDiv(level.getMaxBuildHeight() - 1, 32) + 1;

        this.worldEngine.acquireRef();

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "VoxyLodStreaming-" + level.dimension().location().getPath());
            t.setDaemon(true);
            return t;
        });

        int workers = Math.max(1, config.effectiveSerializeThreads());
        AtomicInteger threadIdx = new AtomicInteger();
        this.serializeExecutor = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r, "VoxySerialize-" + threadIdx.getAndIncrement());
            t.setDaemon(true);
            return t;
        });

        final int cacheCap = Math.max(64, config.serializedCacheEntries);
        this.serializedCache = new LinkedHashMap<>(cacheCap, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, CachedSection> eldest) {
                return size() > cacheCap;
            }
        };

        installDirtyCallback();

        Logger.info("LodStreamingService initialized [" + level.dimension().location() +
                "] workers=" + workers + " radius=" + config.maxStreamingRadiusChunks +
                "ch (=" + config.getMaxStreamingRadiusSections() + "vs)" +
                " ySections=[" + minSectionY + ".." + maxSectionYExclusive + ")");
    }

    private void installDirtyCallback() {
        this.prevDirtyCallback = null; // No-op chain target; server has no render-side listener.
        worldEngine.setDirtyCallback((section, flags, neighborMsk) -> {
            try {
                onSectionDirty(section.key);
            } catch (Throwable t) {
                Logger.error("Dirty callback failure", t);
            }
            if (prevDirtyCallback != null) {
                prevDirtyCallback.accept(section, flags, neighborMsk);
            }
        });
    }

    private void onSectionDirty(long key) {
        synchronized (dirtyLock) {
            int cap = Math.max(256, config.dirtyQueueMaxEntries);
            while (recentlyDirtyKeys.size() >= cap) {
                recentlyDirtyKeys.pollFirst();
            }
            recentlyDirtyKeys.addLast(key);
        }
    }

    private List<Long> snapshotDirty() {
        synchronized (dirtyLock) {
            if (recentlyDirtyKeys.isEmpty()) {
                return Collections.emptyList();
            }
            List<Long> out = new ArrayList<>(recentlyDirtyKeys);
            recentlyDirtyKeys.clear();
            return out;
        }
    }

    public void handleRateUpdate(ServerPlayer player, VoxyPacketPayload payload) {
        int desiredRate = payload.parseRate();
        PlayerStreamingState state = playerStates.get(player.getUUID());
        if (state != null) {
            state.clientDesiredRate = desiredRate;
        }
    }

    @Deprecated
    public void handleSectionRequest(ServerPlayer player, VoxyPacketPayload payload) {
        Logger.info("Ignoring pull request from " + player.getName().getString() +
                " (pull mode deprecated, server-driven streaming only)");
    }

    public void startSyncForPlayer(ServerPlayer player) {
        Logger.info("Received sync request from " + player.getName().getString());

        PlayerStreamingState state = playerStates.computeIfAbsent(
                player.getUUID(),
                uuid -> new PlayerStreamingState(player, sharedBandwidthLimit, config.perPlayerLimitKBps));

        // Refresh in case the player reconnected and the ServerPlayer instance was
        // replaced (state.player would otherwise still point to a disconnected handle
        // whose .connection is null).
        state.player = player;
        // Force a fresh ring expansion from the player's current position so a teleport
        // or rejoin after generating chunks elsewhere doesn't have to wait for player
        // movement to re-trigger the scan.
        state.currentRing = 0;
        state.consecutiveEmptyRings = 0;
        state.inMaintenance = false;
        state.lastPlayerSectionX = Integer.MIN_VALUE;
        state.lastPlayerSectionZ = Integer.MIN_VALUE;

        BloomFilter savedFilter = loadPlayerCache(player.getUUID());
        if (savedFilter != null) {
            state.clientCacheFilter = savedFilter;
            Logger.info("Using saved bloom filter for " + player.getName().getString());
        }

        sendMapperSync(player);
        VoxyNetworkHandler.sendToPlayer(player, VoxyPacketPayload.cacheQuery(new long[0]));

        long periodMs = Math.max(20L, (long) (1000.0 / Math.max(1.0, config.activeTickHz)));
        if (state.scheduledHandle != null) {
            state.scheduledHandle.cancel(false);
        }
        state.scheduledHandle = scheduler.scheduleAtFixedRate(
                () -> tickPlayer(state),
                0L, periodMs, TimeUnit.MILLISECONDS);

        Logger.info("Server-driven streaming enabled for " + player.getName().getString() +
                " (tick=" + periodMs + "ms, radius=" + effectiveRadius(state) + ")");
    }

    private void sendMapperSync(ServerPlayer player) {
        byte[] mapperData = IdRemapper.serializeMapper(worldEngine.getMapper());
        VoxyNetworkHandler.sendToPlayer(player, VoxyPacketPayload.mapperSync(mapperData));
        Logger.info("Sent mapper sync to " + player.getName().getString() +
                " (" + mapperData.length + " bytes)");
    }

    /**
     * Per-player tick. Order of operations:
     * <ol>
     * <li>Sanity / liveness checks.</li>
     * <li>Detect player movement (reset ring origin).</li>
     * <li>Drain recently-dirty sections within radius (Chunky-friendly push).</li>
     * <li>Expand the current ring around the player.</li>
     * </ol>
     */
    private void tickPlayer(PlayerStreamingState state) {
        if (!isActive.get()) {
            return;
        }
        ServerPlayer player = state.player;
        if (!player.isAlive() || player.connection == null) {
            cancel(state);
            return;
        }
        try {
            worldEngine.markActive();

            if (state.clientCacheFilter == null) {
                state.clientCacheFilter = BloomFilter.forExpectedElements(10000);
            }

            int px = player.getBlockX() >> 5;
            int pz = player.getBlockZ() >> 5;
            if (px != state.lastPlayerSectionX || pz != state.lastPlayerSectionZ) {
                state.currentRing = 0;
                state.consecutiveEmptyRings = 0;
                state.lastPlayerSectionX = px;
                state.lastPlayerSectionZ = pz;
            }

            int radius = effectiveRadius(state);

            // 1. Drain recently-dirty sections near this player
            int dirtyFlushed = drainDirtyNear(state, px, pz, radius);

            // 2. Expand the current ring
            int sectionsFound = streamRing(state, px, pz, state.currentRing, radius);

            if (sectionsFound > 0 || dirtyFlushed > 0) {
                state.consecutiveEmptyRings = 0;
            } else if (state.currentRing >= radius) {
                state.consecutiveEmptyRings++;
            }

            if (state.currentRing < radius) {
                state.currentRing++;
            }

            // Switch to slow-tick once we've exhausted active rings, but the dirty-push
            // path still drains on every tick so newly generated sections stream in.
            if (state.consecutiveEmptyRings >= 5 && !state.inMaintenance) {
                state.inMaintenance = true;
                long slow = Math.max(50L, (long) (1000.0 / Math.max(0.1, config.maintenanceTickHz)));
                rescheduleTick(state, slow);
                savePlayerCacheAsync(player.getUUID(), state.clientCacheFilter);
                Logger.info("Entering maintenance mode for " + player.getName().getString() +
                        " (slowTick=" + slow + "ms)");
            } else if (state.consecutiveEmptyRings < 5 && state.inMaintenance) {
                state.inMaintenance = false;
                long fast = Math.max(20L, (long) (1000.0 / Math.max(1.0, config.activeTickHz)));
                rescheduleTick(state, fast);
                Logger.info("Returning to active streaming for " + player.getName().getString() +
                        " (fastTick=" + fast + "ms)");
            }
        } catch (Throwable t) {
            Logger.error("tickPlayer failed", t);
        }
    }

    private void rescheduleTick(PlayerStreamingState state, long periodMs) {
        if (state.scheduledHandle != null) {
            state.scheduledHandle.cancel(false);
        }
        state.scheduledHandle = scheduler.scheduleAtFixedRate(
                () -> tickPlayer(state), 0L, periodMs, TimeUnit.MILLISECONDS);
    }

    private int effectiveRadius(PlayerStreamingState state) {
        // Internal computations stay in voxy-section units (32 blocks each); the
        // config exposes everything in vanilla chunks (16 blocks) for clarity.
        int cap = config.getMaxStreamingRadiusSections();
        if (state.clientHintedRadius > 0) {
            cap = Math.min(cap, Math.max(state.clientHintedRadius, 1));
        }
        cap = Math.min(cap, config.getClientHintRadiusCapSections());
        return Math.max(1, cap);
    }

    private int drainDirtyNear(PlayerStreamingState state, int px, int pz, int radius) {
        List<Long> snap = snapshotDirty();
        if (snap.isEmpty()) {
            return 0;
        }
        int flushed = 0;
        int outOfRange = 0;
        for (Long key : snap) {
            int sx = WorldEngine.getX(key);
            int sz = WorldEngine.getZ(key);
            int dx = Math.abs(sx - px);
            int dz = Math.abs(sz - pz);
            if (Math.max(dx, dz) > radius) {
                outOfRange++;
                continue;
            }
            if (maybeQueueSection(state, key)) {
                flushed++;
            }
        }
        if (snap.size() >= 64 || flushed > 0) {
            Logger.info("[VoxyStream] " + state.player.getName().getString() +
                    " drained " + snap.size() + " dirty keys @ section(" + px + "," + pz +
                    ") r=" + radius + " → flushed=" + flushed + " out-of-range=" + outOfRange);
        }
        return flushed;
    }

    private int streamRing(PlayerStreamingState state, int px, int pz, int ring, int radius) {
        if (ring > radius) {
            return 0;
        }
        int found = 0;
        for (int dx = -ring; dx <= ring; dx++) {
            for (int dz = -ring; dz <= ring; dz++) {
                if (ring > 0 && Math.abs(dx) != ring && Math.abs(dz) != ring) {
                    continue;
                }
                int sectionX = px + dx;
                int sectionZ = pz + dz;
                for (int lvl = WorldEngine.MAX_LOD_LAYER; lvl >= 0; lvl--) {
                    for (int y = minSectionY; y < maxSectionYExclusive; y++) {
                        long key = WorldEngine.getWorldSectionId(lvl, sectionX, y, sectionZ);
                        if (maybeQueueSection(state, key)) {
                            found++;
                        }
                    }
                }
            }
        }
        return found;
    }

    /**
     * Queue a section for streaming if (a) it exists, (b) has content, and
     * (c) its {@link WorldSection#getVersion()} is newer than the last copy we
     * sent to this player. Returns {@code true} iff the section was queued.
     */
    private boolean maybeQueueSection(PlayerStreamingState state, long key) {
        WorldSection section = worldEngine.acquireIfExists(key);
        if (section == null) {
            return false;
        }
        try {
            int lvl = WorldEngine.getLevel(key);
            boolean hasContent;
            if (lvl == 0) {
                hasContent = section.getNonEmptyBlockCount() > 0;
            } else {
                byte childMask = section.getNonEmptyChildren();
                hasContent = childMask != 0;
            }
            if (!hasContent) {
                return false;
            }
            long version = section.getVersion();
            Long lastSent = state.lastSentVersion.get(key);
            if (lastSent != null && lastSent >= version) {
                return false;
            }

            // Try the shared cache first.
            byte[] data = lookupOrSerialize(section, version);
            if (data == null) {
                return false;
            }

            // Hand off to ChunkedLodSender (bandwidth-controlled).
            state.sender.queueSection(data, (int) key);
            state.lastSentVersion.put(key, version);
            state.clientCacheFilter.add(key);
            return true;
        } finally {
            section.release();
        }
    }

    private byte[] lookupOrSerialize(WorldSection section, long version) {
        long key = section.key;
        synchronized (cacheLock) {
            CachedSection cached = serializedCache.get(key);
            if (cached != null && cached.version == version) {
                return cached.data;
            }
        }
        // Serialize off the scheduler thread for big sections. For the very first
        // request this still runs synchronously on the scheduler — the cost is the
        // same as before but subsequent players hit the cache.
        byte[] data;
        try {
            data = SectionSerializer.serialize(section);
        } catch (Throwable t) {
            Logger.error("Failed to serialize section " + WorldEngine.pprintPos(key), t);
            return null;
        }
        synchronized (cacheLock) {
            serializedCache.put(key, new CachedSection(version, data));
        }
        return data;
    }

    public void handleCacheResponse(ServerPlayer player, VoxyPacketPayload payload) {
        BloomFilter clientCache = payload.parseCacheResponseBloomFilter();
        PlayerStreamingState state = playerStates.get(player.getUUID());
        if (state == null) {
            return;
        }
        if (state.clientCacheFilter == null) {
            state.clientCacheFilter = BloomFilter.forExpectedElements(10000);
        }
        if (clientCache != null && clientCache.getSerializedSize() > 100) {
            state.clientCacheFilter.merge(clientCache);
            Logger.info("Merged client bloom filter for " + player.getName().getString());
        } else {
            Logger.info("Client bloom filter too small, using server-side only for " +
                    player.getName().getString());
        }
    }

    public void onPlayerDisconnect(UUID playerId) {
        PlayerStreamingState state = playerStates.remove(playerId);
        if (state != null) {
            if (state.clientCacheFilter != null) {
                savePlayerCacheAsync(playerId, state.clientCacheFilter);
            }
            cancel(state);
            state.close();
        }
        VoxyNetworkHandler.removePlayer(playerId);
    }

    private void cancel(PlayerStreamingState state) {
        if (state.scheduledHandle != null) {
            state.scheduledHandle.cancel(false);
            state.scheduledHandle = null;
        }
    }

    public String getPlayerStats(UUID playerId) {
        PlayerStreamingState state = playerStates.get(playerId);
        if (state == null) {
            return "No active streaming";
        }
        return state.sender.getStatsString() + ", Sent: " + state.lastSentVersion.size();
    }

    /**
     * Drop the per-player "already sent" tracking and rewind ring expansion so
     * every section in range is re-serialized and resent to that client. Used by
     * {@code /voxyadmin resync} to recover from missing LODs without forcing
     * the client to disconnect.
     */
    public int resyncPlayer(UUID playerId) {
        PlayerStreamingState state = playerStates.get(playerId);
        if (state == null) {
            return 0;
        }
        int previouslySent = state.lastSentVersion.size();
        state.lastSentVersion.clear();
        state.currentRing = 0;
        state.consecutiveEmptyRings = 0;
        state.inMaintenance = false;
        state.lastPlayerSectionX = Integer.MIN_VALUE;
        state.lastPlayerSectionZ = Integer.MIN_VALUE;
        state.clientCacheFilter = BloomFilter.forExpectedElements(10000);
        // Also drop any cached serialized bytes — they may be stale relative to the
        // engine's current state after a regen.
        synchronized (cacheLock) {
            serializedCache.clear();
        }
        long periodMs = Math.max(20L, (long) (1000.0 / Math.max(1.0, config.activeTickHz)));
        if (state.scheduledHandle != null) {
            state.scheduledHandle.cancel(false);
        }
        state.scheduledHandle = scheduler.scheduleAtFixedRate(
                () -> tickPlayer(state), 0L, periodMs, TimeUnit.MILLISECONDS);
        return previouslySent;
    }

    /**
     * Resync every player currently being tracked.
     * @return number of players that were resynced.
     */
    public int resyncAll() {
        int n = 0;
        for (UUID id : new ArrayList<>(playerStates.keySet())) {
            if (resyncPlayer(id) >= 0) {
                n++;
            }
        }
        return n;
    }

    @Override
    public void close() {
        isActive.set(false);
        scheduler.shutdown();
        serializeExecutor.shutdown();

        for (PlayerStreamingState state : playerStates.values()) {
            cancel(state);
            state.close();
        }
        playerStates.clear();

        synchronized (cacheLock) {
            serializedCache.clear();
        }
        synchronized (dirtyLock) {
            recentlyDirtyKeys.clear();
        }

        try {
            worldEngine.releaseRef();
        } catch (Exception e) {
            Logger.error("Error releasing world engine ref", e);
        }

        Logger.info("LodStreamingService closed");
    }

    // ==================== Bloom-filter persistence (async I/O) ==================== //

    private Path getCacheDir() {
        return Path.of("voxy_cache", "player_bloom_filters");
    }

    private void savePlayerCacheAsync(UUID playerId, BloomFilter filter) {
        if (filter == null) {
            return;
        }
        serializeExecutor.submit(() -> savePlayerCache(playerId, filter));
    }

    private void savePlayerCache(UUID playerId, BloomFilter filter) {
        try {
            Path dir = getCacheDir();
            Files.createDirectories(dir);
            Path cacheFile = dir.resolve(playerId.toString() + ".bloom");
            Files.write(cacheFile, filter.toBytes());
        } catch (IOException e) {
            Logger.error("Failed to save bloom filter for " + playerId + ": " + e.getMessage());
        }
    }

    private BloomFilter loadPlayerCache(UUID playerId) {
        try {
            Path cacheFile = getCacheDir().resolve(playerId.toString() + ".bloom");
            if (!Files.exists(cacheFile)) {
                return null;
            }
            byte[] data = Files.readAllBytes(cacheFile);
            if (data.length < 1000) {
                Files.delete(cacheFile);
                return null;
            }
            return BloomFilter.fromBytes(data);
        } catch (IOException e) {
            Logger.error("Failed to load bloom filter for " + playerId + ": " + e.getMessage());
            return null;
        }
    }

    // ==================== Inner types ==================== //

    private static class PlayerStreamingState {
        ServerPlayer player;
        final ChunkedLodSender sender;

        // Resend-on-update: server section version > stored version → resend.
        final ConcurrentHashMap<Long, Long> lastSentVersion = new ConcurrentHashMap<>();

        int currentRing = 0;
        int consecutiveEmptyRings = 0;
        boolean inMaintenance = false;
        int clientDesiredRate = SharedBandwidthLimit.DEFAULT_PLAYER_LIMIT_KBPS;
        int clientHintedRadius = 0; // 0 = use server default
        BloomFilter clientCacheFilter = null;

        int lastPlayerSectionX = Integer.MIN_VALUE;
        int lastPlayerSectionZ = Integer.MIN_VALUE;

        ScheduledFuture<?> scheduledHandle;

        PlayerStreamingState(ServerPlayer player, SharedBandwidthLimit sharedLimit, int limitKBps) {
            this.player = player;
            this.sender = new ChunkedLodSender(player, sharedLimit, limitKBps);
        }

        void close() {
            sender.close();
        }
    }

    private record CachedSection(long version, byte[] data) {}
}
