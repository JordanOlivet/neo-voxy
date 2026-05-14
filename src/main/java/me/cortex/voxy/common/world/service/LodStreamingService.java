package me.cortex.voxy.common.world.service;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.VoxyDiag;
import me.cortex.voxy.common.network.*;
import me.cortex.voxy.common.world.SectionSerializer;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.server.VoxyServer;
import me.cortex.voxy.server.VoxyServerConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;

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

    // Optional dev-only instrumentation: timestamp at which a section first
    // became dirty after a clean state (i.e. its corresponding chunk's ingest
    // finished). Cleared by maybeQueueSection when the section is handed off
    // to the serializer. Populated only when {@code config.logLatency} is on.
    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap firstDirtyNanos =
            new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
    private final Object firstDirtyNanosLock = new Object();

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
        scheduleAutoRegen();

        Logger.info("LodStreamingService initialized [" + level.dimension().location() +
                "] workers=" + workers + " radius=" + config.maxStreamingRadiusChunks +
                "ch (=" + config.getMaxStreamingRadiusSections() + "vs)" +
                " ySections=[" + minSectionY + ".." + maxSectionYExclusive + ")" +
                " autoRegen=" + config.autoRegenIntervalSeconds + "s/" +
                config.autoRegenRadiusChunks + "ch");
    }

    private void scheduleAutoRegen() {
        if (config.autoRegenIntervalSeconds <= 0) {
            return;
        }
        scheduler.scheduleAtFixedRate(
                this::autoRegenSweep,
                config.autoRegenIntervalSeconds,
                config.autoRegenIntervalSeconds,
                TimeUnit.SECONDS);
    }

    /**
     * Periodic watchdog: walk every chunk currently loaded on the server around
     * each connected player and re-enqueue an ingest for the ones whose
     * corresponding voxy section is empty in the engine.
     * <p>
     * This is the recovery path for {@code ChunkEvent.Load} events Voxy silently
     * dropped (proto-chunk, lighting not propagated, ingest queue overflow, ...)
     * without forcing the player to run {@code /voxyadmin regen} by hand.
     */
    private void autoRegenSweep() {
        if (!isActive.get() || playerStates.isEmpty()) {
            return;
        }
        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            return;
        }
        // Bound the sweep to the server view distance — getChunkNow only returns
        // non-null for chunks the server actively keeps loaded, so going wider is
        // harmless but wastes a few thousand HashMap lookups per tick.
        int viewDistanceChunks;
        try {
            viewDistanceChunks = level.getServer().getPlayerList().getViewDistance();
        } catch (Throwable t) {
            viewDistanceChunks = 16;
        }
        int radiusChunks = Math.max(1, Math.min(config.autoRegenRadiusChunks, viewDistanceChunks + 4));

        int totalRe = 0;
        var chunkSource = level.getChunkSource();
        for (PlayerStreamingState state : playerStates.values()) {
            ServerPlayer p = state.player;
            if (p == null || !p.isAlive() || p.connection == null) {
                continue;
            }
            int pcx = p.getBlockX() >> 4;
            int pcz = p.getBlockZ() >> 4;
            int reForPlayer = 0;
            for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
                for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                    int cx = pcx + dx;
                    int cz = pcz + dz;
                    LevelChunk chunk = chunkSource.getChunkNow(cx, cz);
                    if (chunk == null) {
                        continue;
                    }
                    if (hasAnyContentForChunk(cx, cz)) {
                        continue;
                    }
                    try {
                        if (instance.getIngestService().enqueueIngest(worldEngine, chunk)) {
                            reForPlayer++;
                        }
                    } catch (Exception e) {
                        Logger.error("autoRegen failed at " + chunk.getPos(), e);
                    }
                }
            }
            if (reForPlayer > 0) {
                Logger.info("[VoxyAutoRegen] " + p.getName().getString() + ": re-ingested " +
                        reForPlayer + " loaded chunk(s) missing LOD (radius " + radiusChunks + "ch)");
            }
            totalRe += reForPlayer;
        }
        if (totalRe > 0) {
            Logger.info("[VoxyAutoRegen] sweep total: " + totalRe + " chunks re-ingested across " +
                    playerStates.size() + " player(s)");
        }
    }

    /**
     * Check whether the engine has any non-empty LOD content for the vanilla chunk
     * at {@code (cx, cz)}. A chunk is 16 blocks wide; voxy sections are 32 blocks,
     * so each chunk maps onto a single voxy section column at {@code (cx>>1, cz>>1)}.
     * We probe LOD-0 sections across the dimension Y range and exit early on the
     * first non-empty hit to keep the per-chunk cost down.
     */
    private boolean hasAnyContentForChunk(int cx, int cz) {
        int vsx = cx >> 1;
        int vsz = cz >> 1;
        for (int vy = minSectionY; vy < maxSectionYExclusive; vy++) {
            long key = WorldEngine.getWorldSectionId(0, vsx, vy, vsz);
            WorldSection s = worldEngine.acquireIfExists(key);
            if (s == null) {
                continue;
            }
            try {
                if (s.getNonEmptyBlockCount() > 0) {
                    return true;
                }
            } finally {
                s.release();
            }
        }
        return false;
    }

    private void installDirtyCallback() {
        this.prevDirtyCallback = null; // No-op chain target; server has no render-side listener.
        worldEngine.setDirtyCallback((section, flags, neighborMsk) -> {
            try {
                long key = section.key;
                onSectionDirty(key);
                // Event-driven fast-path: skip the dirty-queue + tick-drain wait for
                // sections that are already within streaming range of a connected
                // player. The slow path (drain) still runs and will simply find the
                // section already marked sent (lastSentVersion bumped here) when it
                // gets to it — no double send.
                if (isActive.get() && !playerStates.isEmpty()) {
                    long version = section.getVersion();
                    for (PlayerStreamingState state : playerStates.values()) {
                        tryFastPush(state, section, key, version);
                    }
                }
            } catch (Throwable t) {
                Logger.error("Dirty callback failure", t);
            }
            if (prevDirtyCallback != null) {
                prevDirtyCallback.accept(section, flags, neighborMsk);
            }
        });
    }

    /**
     * Fast-path companion to {@link #maybeQueueSection}: called inline from the
     * dirty callback when an ingest just wrote a section. Skips the dirty queue
     * + tick drain entirely if the section is already within the player's
     * streaming radius. The section is passed in by reference (the markDirty
     * caller holds a ref); we take our own extra ref for the async worker.
     */
    private void tryFastPush(PlayerStreamingState state, WorldSection section, long key, long version) {
        if (state.player == null
                || state.lastPlayerSectionX == Integer.MIN_VALUE
                || state.clientCacheFilter == null) {
            return;
        }
        int lvl = WorldEngine.getLevel(key);
        int sx = WorldEngine.getX(key);
        int sz = WorldEngine.getZ(key);
        // Same scale fix as drainDirtyNear: lift player coords + radius into the
        // key's LOD-N space before comparing. Without this every LOD-N>0 update
        // (parent mask changes) is silently dropped here too.
        int pxLod = state.lastPlayerSectionX >> lvl;
        int pzLod = state.lastPlayerSectionZ >> lvl;
        int radLod = Math.max(1, effectiveRadius(state) >> lvl);
        int dx = Math.abs(sx - pxLod);
        int dz = Math.abs(sz - pzLod);
        if (Math.max(dx, dz) > radLod) {
            return;
        }
        boolean hasContent = (lvl == 0)
                ? section.getNonEmptyBlockCount() > 0
                : section.getNonEmptyChildren() != 0;
        if (!hasContent) {
            return;
        }
        Long lastSent = state.lastSentVersion.get(key);
        if (lastSent != null && lastSent >= version) {
            return;
        }
        state.lastSentVersion.put(key, version);
        state.clientCacheFilter.add(key);

        if (config.isLogLatencyEffective()) {
            long firstNanos;
            synchronized (firstDirtyNanosLock) {
                firstNanos = firstDirtyNanos.remove(key);
            }
            if (firstNanos != 0L) {
                long elapsedMs = (System.nanoTime() - firstNanos) / 1_000_000L;
                Logger.info("[VoxyLatency] fast-push dirty→queued key=" + WorldEngine.pprintPos(key) +
                        " elapsedMs=" + elapsedMs);
            }
        }

        section.acquire();
        final WorldSection capturedSection = section;
        try {
            serializeExecutor.execute(() -> {
                try {
                    byte[] data = lookupOrSerialize(capturedSection, version);
                    if (data != null) {
                        state.sender.queueSection(data, (int) key);
                        state.sectionsSentCount.incrementAndGet();
                        state.bytesSentCount.addAndGet(data.length);
                    }
                } catch (Throwable t) {
                    Logger.error("fast-push serialize failed for " + WorldEngine.pprintPos(key), t);
                } finally {
                    capturedSection.release();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException rex) {
            capturedSection.release();
        }
    }

    private void onSectionDirty(long key) {
        synchronized (dirtyLock) {
            int cap = Math.max(256, config.dirtyQueueMaxEntries);
            while (recentlyDirtyKeys.size() >= cap) {
                recentlyDirtyKeys.pollFirst();
            }
            recentlyDirtyKeys.addLast(key);
        }
        if (config.isLogLatencyEffective()) {
            long now = System.nanoTime();
            synchronized (firstDirtyNanosLock) {
                if (!firstDirtyNanos.containsKey(key)) {
                    firstDirtyNanos.put(key, now);
                }
            }
        }
    }

    private List<Long> snapshotDirty() {
        synchronized (dirtyLock) {
            if (recentlyDirtyKeys.isEmpty()) {
                return Collections.emptyList();
            }
            int cap = Math.max(1, config.dirtyDrainMaxPerTick);
            int take = Math.min(cap, recentlyDirtyKeys.size());
            if (take >= recentlyDirtyKeys.size()) {
                List<Long> out = new ArrayList<>(recentlyDirtyKeys);
                recentlyDirtyKeys.clear();
                return out;
            }
            List<Long> out = new ArrayList<>(take);
            for (int i = 0; i < take; i++) {
                out.add(recentlyDirtyKeys.pollFirst());
            }
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

    public void handleClientHint(ServerPlayer player, VoxyPacketPayload payload) {
        int radiusChunks = payload.parseClientHintRadiusChunks();
        if (radiusChunks <= 0) {
            return;
        }
        // Convert chunks (16 blocks) to voxy sections (32 blocks). Round up so a
        // partially-covered ring at the boundary still streams.
        int radiusSections = (radiusChunks + 1) / 2;
        PlayerStreamingState state = playerStates.get(player.getUUID());
        if (state == null) {
            return;
        }
        int previous = state.clientHintedRadius;
        state.clientHintedRadius = radiusSections;
        // Cold scan should restart from ring 0 so newly-in-range sections (after a
        // hint increase) get streamed promptly. Setting consecutiveEmptyRings=0
        // lets the regular tickPlayer guard flip out of maintenance and reschedule
        // at the fast cadence on its own — no direct mutation of inMaintenance.
        if (radiusSections > previous) {
            state.currentRing = 0;
            state.consecutiveEmptyRings = 0;
        }
        Logger.info("[VoxyStream] " + player.getName().getString() +
                " client hint = " + radiusChunks + " chunks (" + radiusSections + " sections)");
    }

    @Deprecated
    public void handleSectionRequest(ServerPlayer player, VoxyPacketPayload payload) {
        Logger.info("Ignoring pull request from " + player.getName().getString() +
                " (pull mode deprecated, server-driven streaming only)");
    }

    public void startSyncForPlayer(ServerPlayer player) {
        Logger.info("Received sync request from " + player.getName().getString());
        VoxyDiag.startWindow(60);
        VoxyDiag.event("startSyncForPlayer name=" + player.getName().getString());

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
                " (tick=" + periodMs + "ms, radius=" + (effectiveRadius(state) * 2) + " chunks)");
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
                // Detect teleport / long-distance jump. The client's section data for
                // the new area is almost certainly partial because the server-side
                // load + ingest is still in flight, and we cannot rely on the
                // per-section version-bump path catching every late octant ingest.
                // Wiping {@code lastSentVersion} forces a full re-stream of in-range
                // sections, which is what {@code /voxyadmin resync} does — we just
                // automate it on the obvious "I teleported" trigger.
                //
                // Guard against false positives when the scheduler thread itself was
                // blocked (e.g. processing a huge dirty drain): a stalled tick lets
                // the player accumulate normal movement, which looks like a jump on
                // resume. Only treat the jump as real if the wall-clock interval
                // since the previous tick is close to the configured tick period.
                int jumpThresholdSections = Math.max(1, config.autoResyncOnJumpChunks / 2);
                long now = System.nanoTime();
                long expectedTickPeriodMs = Math.max(20L, (long) (1000.0 / Math.max(1.0,
                        state.inMaintenance ? config.maintenanceTickHz : config.activeTickHz)));
                long sinceLastTickMs = state.lastTickNanos == 0L
                        ? 0L
                        : (now - state.lastTickNanos) / 1_000_000L;
                boolean schedulerStalled = sinceLastTickMs > expectedTickPeriodMs * 4L;
                state.lastTickNanos = now;

                if (config.autoResyncOnJumpChunks > 0
                        && !schedulerStalled
                        && state.lastPlayerSectionX != Integer.MIN_VALUE
                        && (Math.abs(px - state.lastPlayerSectionX) > jumpThresholdSections
                                || Math.abs(pz - state.lastPlayerSectionZ) > jumpThresholdSections)) {
                    int wiped = state.lastSentVersion.size();
                    state.lastSentVersion.clear();
                    state.clientCacheFilter = BloomFilter.forExpectedElements(10000);
                    // Don't clear serializedCache — it is keyed by (section key, version)
                    // and the data behind each entry is still valid for the section's
                    // current version. Re-using the cache here saves us from
                    // re-serializing every section the player is about to receive.
                    Logger.info("[VoxyStream] " + player.getName().getString() +
                            " jumped " + Math.max(Math.abs(px - state.lastPlayerSectionX),
                                    Math.abs(pz - state.lastPlayerSectionZ)) +
                            " sections — auto-resync (cleared " + wiped + " lastSent entries)");
                } else if (schedulerStalled
                        && state.lastPlayerSectionX != Integer.MIN_VALUE
                        && (Math.abs(px - state.lastPlayerSectionX) > jumpThresholdSections
                                || Math.abs(pz - state.lastPlayerSectionZ) > jumpThresholdSections)) {
                    Logger.warn("[VoxyStream] scheduler stalled " + sinceLastTickMs +
                            "ms (expected " + expectedTickPeriodMs + "ms), skipping auto-resync " +
                            "even though player section delta is " +
                            Math.max(Math.abs(px - state.lastPlayerSectionX),
                                    Math.abs(pz - state.lastPlayerSectionZ)) + " sections");
                }
                state.currentRing = 0;
                state.consecutiveEmptyRings = 0;
                state.lastPlayerSectionX = px;
                state.lastPlayerSectionZ = pz;
            } else {
                state.lastTickNanos = System.nanoTime();
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

            if (VoxyDiag.shouldSnapshot()) {
                long now = System.nanoTime();
                long sentDelta = state.sectionsSentCount.get() - state.lastSnapshotSectionsSent;
                long bytesDelta = state.bytesSentCount.get() - state.lastSnapshotBytesSent;
                long elapsedNs = state.lastSnapshotNanos == 0L ? 1L : now - state.lastSnapshotNanos;
                long kbps = (bytesDelta * 1_000_000_000L) / Math.max(1L, elapsedNs) / 1024L;
                state.lastSnapshotSectionsSent = state.sectionsSentCount.get();
                state.lastSnapshotBytesSent = state.bytesSentCount.get();
                state.lastSnapshotNanos = now;
                int dirtyQueue;
                synchronized (dirtyLock) { dirtyQueue = recentlyDirtyKeys.size(); }
                VoxyDiag.log("stream " + player.getName().getString()
                        + " pos=(" + px + "," + pz + ")"
                        + " ring=" + state.currentRing + "/" + radius
                        + " maintenance=" + state.inMaintenance
                        + " sent=" + state.sectionsSentCount.get()
                        + " (+" + sentDelta + "/s)"
                        + " kbps=" + kbps
                        + " lastSentMap=" + state.lastSentVersion.size()
                        + " dirtyQueue=" + dirtyQueue
                        + " dirtyFlushedThisTick=" + dirtyFlushed
                        + " ringFoundThisTick=" + sectionsFound);
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
            // LOD-N section coords are in LOD-N's own scale; lift the player
            // coords + radius into the same scale before doing the chebyshev
            // check. Without this LOD-1+ keys at the player's actual location
            // appear as out-of-range and never get streamed, leaving parent
            // mip mask stale on the client.
            int lvl = WorldEngine.getLevel(key);
            int sx = WorldEngine.getX(key);
            int sz = WorldEngine.getZ(key);
            int pxLod = px >> lvl;
            int pzLod = pz >> lvl;
            int radLod = Math.max(1, radius >> lvl);
            int dx = Math.abs(sx - pxLod);
            int dz = Math.abs(sz - pzLod);
            if (Math.max(dx, dz) > radLod) {
                outOfRange++;
                continue;
            }
            if (maybeQueueSection(state, key)) {
                flushed++;
            }
        }
        if (config.isLogDirtyDrainEffective() && (snap.size() >= 64 || flushed > 0)) {
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
        // Ring iterates at LOD-0 scale around the player. For each LOD-0 coord on
        // this ring, also visit the LOD-N section that physically covers it (key
        // is shared by 4^N LOD-0 coords). Dedupe via a small per-ring Set so the
        // same LOD-N key isn't checked 4^N times.
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        for (int dx = -ring; dx <= ring; dx++) {
            for (int dz = -ring; dz <= ring; dz++) {
                if (ring > 0 && Math.abs(dx) != ring && Math.abs(dz) != ring) {
                    continue;
                }
                int sectionX = px + dx;
                int sectionZ = pz + dz;
                for (int lvl = WorldEngine.MAX_LOD_LAYER; lvl >= 0; lvl--) {
                    int sx = sectionX >> lvl;
                    int sz = sectionZ >> lvl;
                    for (int y = minSectionY; y < maxSectionYExclusive; y++) {
                        long key = WorldEngine.getWorldSectionId(lvl, sx, y, sz);
                        if (!seen.add(key)) {
                            continue;
                        }
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
     * sent to this player. Returns {@code true} iff the section was accepted
     * for streaming (the actual serialize + sender enqueue happens on a worker
     * thread so the scheduler tick does not block on CPU-heavy work).
     */
    private boolean maybeQueueSection(PlayerStreamingState state, long key) {
        // A scheduled tick may still fire briefly after close() flipped the flag.
        // Bail out before we touch the (possibly terminated) executor.
        if (!isActive.get()) {
            return false;
        }
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
                if (config.isLogVersionSkipsEffective()) {
                    Logger.info("[VoxyVersion] skip key=" + WorldEngine.pprintPos(key) +
                            " lastSent=" + lastSent + " current=" + version +
                            " nonEmptyBlocks=" + section.getNonEmptyBlockCount() +
                            " childMask=0x" + Integer.toHexString(section.getNonEmptyChildren() & 0xFF) +
                            " octantMask=0x" + Integer.toHexString(section.getIngestedOctantMask() & 0xFF));
                }
                return false;
            }

            // Commit the "sent" markers eagerly on the scheduler thread so a
            // subsequent drain doesn't enqueue the same section a second time
            // while the worker is still serializing it.
            state.lastSentVersion.put(key, version);
            state.clientCacheFilter.add(key);

            if (config.isLogLatencyEffective()) {
                long firstNanos;
                synchronized (firstDirtyNanosLock) {
                    firstNanos = firstDirtyNanos.remove(key);
                }
                if (firstNanos != 0L) {
                    long elapsedMs = (System.nanoTime() - firstNanos) / 1_000_000L;
                    Logger.info("[VoxyLatency] dirty→queued key=" + WorldEngine.pprintPos(key) +
                            " elapsedMs=" + elapsedMs);
                }
            }

            // Hand off the CPU-heavy work to the worker pool. The section ref
            // count must be kept while the worker reads section data, so we
            // acquire once more and let the worker release.
            section.acquire();
            final WorldSection capturedSection = section;
            try {
                serializeExecutor.execute(() -> {
                    try {
                        byte[] data = lookupOrSerialize(capturedSection, version);
                        if (data != null) {
                            state.sender.queueSection(data, (int) key);
                            state.sectionsSentCount.incrementAndGet();
                            state.bytesSentCount.addAndGet(data.length);
                        }
                    } catch (Throwable t) {
                        Logger.error("async serialize failed for " + WorldEngine.pprintPos(key), t);
                    } finally {
                        capturedSection.release();
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException rex) {
                // Pool already terminated (server shutdown raced the scheduler).
                // Hand the extra ref back ourselves so WorldEngine.free() doesn't
                // strand this section.
                capturedSection.release();
                return false;
            }
            return true;
        } finally {
            // Always release the ref obtained by acquireIfExists. If we forked
            // serialization, the worker holds its own extra ref (taken above)
            // and will release that when it is done.
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
        // Keep serializedCache — its keys carry the section version, so any entry
        // still valid for the live section will skip re-serialization on resend.
        // {@code /voxyadmin regen} bumps the engine versions through markDirty, which
        // invalidates the relevant cache entries naturally.
        long periodMs = Math.max(20L, (long) (1000.0 / Math.max(1.0, config.activeTickHz)));
        if (state.scheduledHandle != null) {
            state.scheduledHandle.cancel(false);
        }
        state.scheduledHandle = scheduler.scheduleAtFixedRate(
                () -> tickPlayer(state), 0L, periodMs, TimeUnit.MILLISECONDS);
        return previouslySent;
    }

    /**
     * Diagnostic snapshot: walk every LOD-0 voxy section within a {@code radius}
     * (in vanilla chunks) around the player and bucket-count the streaming state.
     * Returns a one-liner suitable for sending back as a command reply.
     */
    public String diagPlayer(ServerPlayer player, int radiusChunks) {
        PlayerStreamingState state = playerStates.get(player.getUUID());
        if (state == null) {
            return "no streaming state for " + player.getName().getString();
        }
        int pcx = player.getBlockX() >> 4;
        int pcz = player.getBlockZ() >> 4;
        int rc = Math.max(1, radiusChunks);
        int totalEngineHasContent = 0;
        int totalNeverSent = 0;     // section has content, never sent → drain should pick up
        int totalNeedsResend = 0;   // lastSent < current version → drain should pick up
        int totalUpToDate = 0;      // lastSent >= current version → already sent
        int totalEmpty = 0;         // section exists in engine but blockCount=0
        int totalMissing = 0;       // section not in engine

        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int cx = pcx - rc; cx <= pcx + rc; cx++) {
            for (int cz = pcz - rc; cz <= pcz + rc; cz++) {
                int vsx = cx >> 1;
                int vsz = cz >> 1;
                for (int vy = minSectionY; vy < maxSectionYExclusive; vy++) {
                    long key = WorldEngine.getWorldSectionId(0, vsx, vy, vsz);
                    if (!seen.add(key)) {
                        continue;
                    }
                    WorldSection s = worldEngine.acquireIfExists(key);
                    if (s == null) {
                        totalMissing++;
                        continue;
                    }
                    try {
                        if (s.getNonEmptyBlockCount() == 0) {
                            totalEmpty++;
                            continue;
                        }
                        totalEngineHasContent++;
                        long version = s.getVersion();
                        Long lastSent = state.lastSentVersion.get(key);
                        if (lastSent == null) {
                            totalNeverSent++;
                        } else if (lastSent < version) {
                            totalNeedsResend++;
                        } else {
                            totalUpToDate++;
                        }
                    } finally {
                        s.release();
                    }
                }
            }
        }
        return player.getName().getString() +
                " @ chunk(" + pcx + "," + pcz + ") radius=" + rc + "ch ⇒ " +
                "engineHasContent=" + totalEngineHasContent +
                " neverSent=" + totalNeverSent +
                " needsResend=" + totalNeedsResend +
                " upToDate=" + totalUpToDate +
                " engineEmpty=" + totalEmpty +
                " engineMissing=" + totalMissing +
                " lastSentMapSize=" + state.lastSentVersion.size() +
                " dirtyQueueSize=" + recentlyDirtyKeys.size() +
                " ring=" + state.currentRing + "/" + effectiveRadius(state) +
                " maintenance=" + state.inMaintenance;
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
        // Mark inactive first so any in-flight tickPlayer / dirty-callback fast
        // path bails out before it touches the executors.
        isActive.set(false);

        // Stop scheduling new player ticks. shutdownNow cancels pending tasks
        // and signals running ones to wrap up. The 1-second wait gives any tick
        // currently executing time to return, which would otherwise still call
        // serializeExecutor.execute and trip the RejectedExecutionException
        // path while we shut the pool below.
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                Logger.warn("scheduler did not terminate within 1s on close()");
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        // Only now is it safe to shut the serialize pool down — no more tasks
        // can be submitted to it because the only submitters (tickPlayer + the
        // dirty fast-path) are gated on the active flag we just cleared.
        serializeExecutor.shutdown();
        try {
            if (!serializeExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                Logger.warn("serializeExecutor did not terminate within 2s on close()");
                serializeExecutor.shutdownNow();
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

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
        synchronized (firstDirtyNanosLock) {
            firstDirtyNanos.clear();
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
        long lastTickNanos = 0L;

        // Diagnostics counters: total sections handed to the sender + total payload
        // bytes (sum of serialized section.length). Read by the VoxyDiag snapshot
        // path; never read by streaming logic, so plain longs guarded by volatile
        // would suffice — using java.util.concurrent.atomic.* keeps the increments
        // lock-free when the dirty-callback worker and the scheduled tick both
        // queue sections for the same player.
        final java.util.concurrent.atomic.AtomicLong sectionsSentCount =
                new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong bytesSentCount =
                new java.util.concurrent.atomic.AtomicLong();
        long lastSnapshotSectionsSent = 0L;
        long lastSnapshotBytesSent = 0L;
        long lastSnapshotNanos = 0L;

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
