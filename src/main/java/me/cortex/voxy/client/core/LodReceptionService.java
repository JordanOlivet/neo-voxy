package me.cortex.voxy.client.core;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.network.ClientCongestionControl;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.VoxyDiag;
import me.cortex.voxy.common.network.BloomFilter;
import me.cortex.voxy.common.network.IdRemapper;
import me.cortex.voxy.common.network.VoxyNetworkHandler;
import me.cortex.voxy.common.network.VoxyPacketPayload;
import me.cortex.voxy.common.world.SectionSerializer;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client-side service for receiving and processing streamed LOD data.
 * <p>
 * Implements server-driven streaming architecture with client hints:
 * <ul>
 * <li>Receives LOD sections pushed by the server</li>
 * <li>Responds to cache queries with bloom filter (to skip sections client
 * has)</li>
 * <li>Deserializes section data and remaps server IDs to client IDs</li>
 * <li>Injects received sections into {@link WorldEngine}</li>
 * <li>Triggers render updates via {@code markDirty()}</li>
 * </ul>
 */
public class LodReceptionService implements AutoCloseable {

    // ==================== Core Fields ==================== //

    private final WorldEngine worldEngine;
    private final Mapper clientMapper;
    private final IdRemapper idRemapper = new IdRemapper();
    private final ClientCongestionControl congestionControl;
    private final me.cortex.voxy.client.core.model.ModelBakerySubsystem modelBakery;

    // Chunk reassembly buffers (sectionId -> partial data)
    private final ConcurrentHashMap<Integer, ChunkReassemblyBuffer> reassemblyBuffers = new ConcurrentHashMap<>();

    // Processing thread
    private final ExecutorService processingExecutor;
    private final AtomicBoolean isActive = new AtomicBoolean(true);

    // Stats
    private final AtomicInteger sectionsReceived = new AtomicInteger(0);
    private final AtomicInteger sectionsApplied = new AtomicInteger(0);

    // ==================== Tracking State ==================== //

    /** Sections that have been received from the server */
    private final Set<Long> receivedSections = ConcurrentHashMap.newKeySet();

    /**
     * Sections pending processing because models aren't ready yet.
     * <p>
     * Previously this stored just the raw compressed byte[], which forced
     * {@link #processPendingSections()} to LZ4-decompress every parked
     * section on every main-thread tick to re-check model availability. On
     * cold first-connect the queue grew to ~2000 entries while models were
     * still baking, and the recurring decompress-storm stalled the render
     * thread for >1.5 s per frame. {@link PendingEntry} caches the sampled
     * required-blockId set when the section is first parked so the recheck
     * is O(set.size) hash lookups with no LZ4 work at all.
     */
    private final ConcurrentHashMap<Long, PendingEntry> pendingSections = new ConcurrentHashMap<>();

    private static final class PendingEntry {
        final byte[] data;
        /** Unique sampled blockIds that must be baked before this section can apply. */
        final int[] requiredBlockIds;

        PendingEntry(byte[] data, int[] requiredBlockIds) {
            this.data = data;
            this.requiredBlockIds = requiredBlockIds;
        }
    }

    /** Whether the mapper has been synced (required for processing) */
    private volatile boolean mapperReady = false;

    /** Whether we've already requested sync */
    private volatile boolean syncRequested = false;

    /**
     * True when the active connection lacks the Voxy channel (vanilla server) or
     * the user has forced {@code CLIENT_ONLY}. In that case we never send sync
     * requests and the client falls back to local chunk ingest (handled by
     * {@code ClientChunkIngestListener}).
     */
    private volatile boolean localMode = false;

    /** Last MC dimension key observed — drives auto-resync on dimension change. */
    private volatile net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> lastDimension = null;

    public LodReceptionService(WorldEngine worldEngine, Mapper clientMapper,
            me.cortex.voxy.client.core.model.ModelBakerySubsystem modelBakery) {
        this.worldEngine = worldEngine;
        this.clientMapper = clientMapper;
        this.modelBakery = modelBakery;
        this.congestionControl = new ClientCongestionControl(this::onRateUpdate);

        // Small pool so deserialize + applyVoxelData on incoming sections can run
        // in parallel. Concurrent writes only happen if the same section key gets
        // streamed twice in quick succession; the server-side dedup
        // ({@code lastSentVersion}) keeps that rare, and when it does happen both
        // packets carry the same bytes so last-write-wins is benign.
        int workers = Math.max(2, Runtime.getRuntime().availableProcessors() / 4);
        java.util.concurrent.atomic.AtomicInteger idx = new java.util.concurrent.atomic.AtomicInteger();
        this.processingExecutor = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r, "VoxyLodReception-" + idx.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
        Logger.info("LodReceptionService processing pool: " + workers + " workers");

        // Register client message handler
        VoxyNetworkHandler.setClientMessageHandler(this::handleServerMessage);

        Logger.info("LodReceptionService initialized for server-driven streaming");
    }

    /**
     * Called every client tick to ensure sync is requested.
     * Should be called from the client tick event.
     */
    public void tick() {
        if (!isActive.get()) {
            return;
        }

        // Auto-resync on dimension change. Comparing ResourceKey is cheap and
        // catches both respawn-via-portal and the rare manual /execute-in cases.
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null && mc.level != null) {
            var dim = mc.level.dimension();
            if (dim != null && dim != lastDimension) {
                if (lastDimension != null) {
                    Logger.info("Dimension changed (" + lastDimension.location() + " → " + dim.location() +
                            "), re-issuing LOD sync");
                    resetForReconnect();
                }
                lastDimension = dim;
            }
        }

        if (!VoxyNetworkHandler.shouldEnableStreaming()) {
            return;
        }

        if (localMode) {
            // Process pending sections in case the player ingested locally and models
            // just became available.
            if (!pendingSections.isEmpty()) {
                processPendingSections();
            }
            return;
        }

        // Resolve the effective mode on first tick after connect.
        VoxyConfig.MultiplayerMode mode = VoxyNetworkHandler.getEffectiveMode();
        if (mode == VoxyConfig.MultiplayerMode.CLIENT_ONLY) {
            if (!syncRequested) {
                Logger.info("LodReceptionService: server lacks Voxy channel (or CLIENT_ONLY forced), switching to client-local ingest");
            }
            syncRequested = true;
            localMode = true;
            mapperReady = true; // we will use the client mapper directly; no remap needed
            return;
        }

        // Request sync from server if not done yet (to get mapper)
        if (!syncRequested) {
            syncRequested = true;
            Logger.info("Requesting LOD sync for server-driven streaming");
            VoxyDiag.startWindow(60);
            VoxyDiag.event("syncRequested");
            boolean sent = VoxyNetworkHandler.sendToServer(VoxyPacketPayload.syncRequest());
            if (!sent) {
                Logger.warn("Initial sync request not delivered — falling back to client-local ingest");
                localMode = true;
                mapperReady = true;
            } else {
                // Push our render distance so the server clamps its streaming radius
                // to what we'll actually display.
                VoxyNetworkHandler.sendClientHint();
            }
        }

        // Process pending sections whose models are now available
        if (!pendingSections.isEmpty()) {
            long t0 = VoxyDiag.isEnabled() ? System.nanoTime() : 0L;
            processPendingSections();
            if (VoxyDiag.isEnabled()) {
                VoxyDiag.timing("processPendingSections", System.nanoTime() - t0, 5_000_000L);
            }
        }

        if (VoxyDiag.shouldSnapshot()) {
            VoxyDiag.log("rx pend=" + pendingSections.size()
                    + " cached=" + receivedSections.size()
                    + " rcv=" + sectionsReceived.get()
                    + " app=" + sectionsApplied.get()
                    + " reassembly=" + reassemblyBuffers.size()
                    + " mapperReady=" + mapperReady
                    + " | bake queued=" + modelBakery.getQueuedBakeCount()
                    + " inflight=" + modelBakery.getInflightBakeCount()
                    + " baked=" + modelBakery.getBakedCount()
                    + " upQ=" + modelBakery.getQueuedUploadCount()
                    + " rawQ=" + modelBakery.getRawBakeResultsSize()
                    + " fps=" + net.minecraft.client.Minecraft.getInstance().getFps());
        }
    }

    public boolean isLocalMode() {
        return localMode;
    }

    /**
     * Reset the sync-request state on world / dimension transition so a fresh
     * connection retries the handshake from scratch.
     */
    public void resetForReconnect() {
        syncRequested = false;
        mapperReady = false;
        localMode = false;
        receivedSections.clear();
        pendingSections.clear();
        reassemblyBuffers.clear();
        idRemapper.reset();
        VoxyNetworkHandler.resetConnectionState();
    }

    /**
     * Handle messages from the server.
     */
    private void handleServerMessage(VoxyPacketPayload payload) {
        switch (payload.messageType()) {
            case VoxyPacketPayload.MSG_MAPPER_SYNC -> handleMapperSync(payload);
            case VoxyPacketPayload.MSG_LOD_SECTION -> handleSection(payload);
            case VoxyPacketPayload.MSG_LOD_CHUNK -> handleChunk(payload);
            case VoxyPacketPayload.MSG_SYNC_COMPLETE -> handleSyncComplete(payload);
            case VoxyPacketPayload.MSG_CACHE_QUERY -> handleCacheQuery(payload);
        }

        // Update congestion control
        congestionControl.onChunkReceived(payload);
    }

    /**
     * Handle mapper sync from server.
     * <p>
     * Building the remap tables walks ~1200 block-state strings, parsing each
     * via the vanilla block registry. Even with the O(1) lookup fix in
     * {@link me.cortex.voxy.common.world.other.Mapper#getOrRegisterBlockStateFromString}
     * the parse loop still allocates and registers new entries; doing it on
     * the network/render thread costs noticeable frames. Hand the work off to
     * {@link #processingExecutor} — everything else gates on
     * {@link IdRemapper#isReady()}, so any LOD sections that arrive while the
     * build is in flight simply sit in {@code pendingSections} and replay
     * once {@code mapperReady} flips.
     */
    private void handleMapperSync(VoxyPacketPayload payload) {
        final byte[] data = payload.data();
        Logger.info("Received mapper sync from server (" + data.length + " bytes)");
        VoxyDiag.event("mapperSyncReceived bytes=" + data.length);
        final long t0 = System.nanoTime();
        processingExecutor.submit(() -> {
            try {
                idRemapper.buildFromServerData(data, clientMapper);
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                Logger.info("ID remapper built off-thread in " + ms + "ms");
                VoxyDiag.event("remapperBuilt " + ms + "ms (off-thread)");
                mapperReady = true;
                prebakeFromMapper();
            } catch (Throwable t) {
                Logger.error("Failed to build ID remapper", t);
            }
        });
    }

    /**
     * Request a bake for every block state the server's mapper sync just
     * registered in the client mapper. The curated pre-bake at world load is
     * a best-effort warm-up (it runs before any sync arrives and only covers a
     * vanilla-shaped subset of blocks); this pass guarantees exact coverage
     * for whatever the server actually has — including blocks from mods only
     * the server has loaded — and dedup'ing happens for free because
     * {@link me.cortex.voxy.client.core.model.ModelBakerySubsystem#requestBlockBake(int)}
     * returns {@code false} the second time it sees an id, so the curated
     * pre-bake's warm-up bakes are not redone.
     */
    private void prebakeFromMapper() {
        var entries = clientMapper.getStateEntries();
        int requested = 0;
        for (var entry : entries) {
            if (entry == null) continue;
            if (modelBakery.requestBlockBake(entry.id)) {
                requested++;
            }
        }
        Logger.info("Mapper-driven pre-bake: requested " + requested + " additional block states (of "
                + entries.length + " in mapper)");
        VoxyDiag.event("mapperPrebake requested=" + requested + " total=" + entries.length);
    }

    /**
     * Handle complete section data.
     */
    private void handleSection(VoxyPacketPayload payload) {
        sectionsReceived.incrementAndGet();
        processingExecutor.submit(() -> processSection(payload.data()));
    }

    /**
     * Handle chunk of a large section.
     */
    private void handleChunk(VoxyPacketPayload payload) {
        byte[] data = payload.data();
        if (data.length < 9) {
            Logger.warn("Received chunk with insufficient header");
            return;
        }

        // Parse chunk header: [sectionId:4][offset:4][isLast:1][data:N]
        int sectionId = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16) |
                ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        int offset = ((data[4] & 0xFF) << 24) | ((data[5] & 0xFF) << 16) |
                ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
        boolean isLast = data[8] != 0;

        // Get or create reassembly buffer
        ChunkReassemblyBuffer buffer = reassemblyBuffers.computeIfAbsent(
                sectionId,
                id -> new ChunkReassemblyBuffer());

        // Add chunk data
        buffer.addChunk(offset, data, 9, data.length - 9);

        if (isLast) {
            // Complete! Process the section
            reassemblyBuffers.remove(sectionId);
            sectionsReceived.incrementAndGet();

            byte[] completeData = buffer.assemble();
            if (completeData != null) {
                processingExecutor.submit(() -> processSection(completeData));
            }
        }
    }

    /**
     * Handle sync complete signal.
     */
    private void handleSyncComplete(VoxyPacketPayload payload) {
        Logger.info("LOD sync complete! Received: " + sectionsReceived.get() +
                ", Applied: " + sectionsApplied.get());
        VoxyDiag.event("syncComplete rcv=" + sectionsReceived.get()
                + " app=" + sectionsApplied.get());
    }

    /**
     * Handle cache query from server.
     */
    private void handleCacheQuery(VoxyPacketPayload payload) {
        // Build bloom filter of sections we have
        BloomFilter filter = BloomFilter.forExpectedElements(
                Math.max(100, receivedSections.size()));

        for (Long key : receivedSections) {
            filter.add(key);
        }

        // Send response
        VoxyNetworkHandler.sendToServer(VoxyPacketPayload.cacheResponse(filter));
    }

    /**
     * Process received section data.
     */
    private void processSection(byte[] data) {
        if (!isActive.get())
            return;

        try {
            SectionSerializer.SectionData sectionData = SectionSerializer.deserialize(data);
            if (sectionData == null) {
                Logger.warn("Failed to deserialize section data");
                return;
            }

            // Get or create section in world engine
            long key = sectionData.getKey();

            // Check if all required models for this section are available
            if (sectionData.hasData()) {
                int[] requiredIds = sampleRequiredBlockIds(sectionData.voxelData);
                if (!allModelsReady(requiredIds)) {
                    // Models not ready yet, queue for later processing. Cache the
                    // sampled blockIds so the tick recheck doesn't need to LZ4
                    // decompress the byte[] again.
                    pendingSections.put(key, new PendingEntry(data, requiredIds));
                    return;
                }
            }

            // Mark as received
            receivedSections.add(key);

            WorldSection section = worldEngine.acquire(key);

            if (section == null) {
                Logger.warn("Failed to acquire section for key: " + key);
                return;
            }

            try {
                // Apply data to section
                if (sectionData.hasData() && idRemapper.isReady()) {
                    applyVoxelData(section, sectionData.voxelData);
                }

                // Update non-empty children
                section._unsafeSetNonEmptyChildren(sectionData.nonEmptyChildren);

                // Render gates mesh generation behind {@code isFullyIngested()} (octant
                // mask == 0xFF). On the server side, sections at the boundary of vanilla
                // view-distance only have some of their 8 octants ingested because the
                // missing chunks aren't currently loaded, so their octant mask stays
                // partial (0xaa / 0xcc / 0x33 / ...) — that triggered visible holes in
                // the rendered LOD anywhere the player's view-distance "circle" cut
                // across a 32-block voxy section column. Treat any section we receive
                // over the network as authoritative: the server already had whatever
                // data it had, no point waiting on octants that may never come.
                section._unsafeSetFullyIngested();

                // Server-streamed sections never went through WorldUpdater.insertUpdate
                // on the client, so the render system has no idea that the new section's
                // 6 neighbors need their boundary meshes re-built. Without this the
                // section boundaries stay rendered against stale neighbor data and you
                // get a visible grid of seams (the "quadrillage") on dimension reload
                // or after Chunky generation. Force a full neighbor remesh.
                worldEngine.markDirty(section, WorldEngine.DEFAULT_UPDATE_FLAGS, 0b111111);

                sectionsApplied.incrementAndGet();

            } finally {
                section.release();
            }

        } catch (Exception e) {
            Logger.error("Error processing section: " + e.getMessage());
            Logger.error(e);
        }
    }

    /**
     * Sample {@code voxelData} for the unique client-side blockIds that need to
     * be baked before the section can apply. Done once when a section is first
     * deserialized so the tick recheck doesn't need to LZ4-decompress the
     * payload again. Mirrors the legacy {@code areModelsAvailable} sampling
     * cadence (every 64th voxel, cap at 16 unique blocks) — we only need a
     * representative subset, not a true union, because any one missing model
     * already forces the section to wait.
     */
    private int[] sampleRequiredBlockIds(long[] voxelData) {
        boolean remapReady = idRemapper.isReady();
        it.unimi.dsi.fastutil.ints.IntOpenHashSet seen = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
        int step = Math.max(1, voxelData.length / 64);
        for (int i = 0; i < voxelData.length; i += step) {
            long voxel = voxelData[i];
            long client = remapReady ? idRemapper.remapVoxelId(voxel) : voxel;
            int blockId = me.cortex.voxy.common.world.other.Mapper.getBlockId(client);
            if (blockId != 0) {
                seen.add(blockId);
                if (seen.size() >= 16) break;
            }
        }
        return seen.toIntArray();
    }

    /**
     * Returns {@code true} iff every blockId in {@code requiredBlockIds} has a
     * baked model. Missing models trigger a {@code requestBlockBake} (which is
     * itself dedup'd internally) so the bake pipeline keeps making progress as
     * the tick re-polls. Cheap — no LZ4, no allocations beyond the per-call
     * iteration. Pre-condition for applying a pending section to the world
     * engine.
     */
    private boolean allModelsReady(int[] requiredBlockIds) {
        if (!idRemapper.isReady()) {
            return false;
        }
        for (int id : requiredBlockIds) {
            if (!modelBakery.factory.hasModelForBlockId(id)) {
                modelBakery.requestBlockBake(id);
                return false;
            }
        }
        return true;
    }

    /**
     * Processes sections that were previously queued because their models were
     * not ready. Walks the pending map without re-deserializing the payload —
     * each entry's cached {@code requiredBlockIds} array is matched against
     * {@code ModelFactory.hasModelForBlockId}, and ready entries are atomically
     * removed (via {@code remove(key, value)}) and shipped to the processing
     * executor. The previous implementation ran a fresh
     * {@link SectionSerializer#deserialize} per pending entry per tick, which
     * stalled the render thread by >1.5 s during the initial bake storm.
     */
    private void processPendingSections() {
        var it = pendingSections.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            PendingEntry pending = entry.getValue();
            if (pending == null) continue;
            if (!allModelsReady(pending.requiredBlockIds)) continue;
            if (pendingSections.remove(entry.getKey(), pending)) {
                final byte[] data = pending.data;
                processingExecutor.submit(() -> processSection(data));
            }
        }
    }

    /**
     * Apply voxel data to a section, remapping IDs.
     */
    private void applyVoxelData(WorldSection section, long[] voxelData) {
        long[] dataArray = section._unsafeGetRawDataArray();
        if (dataArray == null) {
            Logger.warn("Section has no data array");
            return;
        }

        int count = Math.min(voxelData.length, dataArray.length);
        for (int i = 0; i < count; i++) {
            long serverVoxel = voxelData[i];
            long clientVoxel = idRemapper.remapVoxelId(serverVoxel);
            dataArray[i] = clientVoxel;
        }
    }

    /**
     * Called when congestion control adjusts rate.
     */
    private void onRateUpdate() {
        // Forward our AIMD-derived desired rate to the server so it can ease off
        // when our link is congested. The server treats it as a downward cap only
        // (clamped to its own per-player limit), so this can never make the server
        // push faster than its configured cap — it only lets a congested client
        // ask for less.
        congestionControl.sendRateUpdate();
    }

    /**
     * Request LOD sync from server.
     */
    public void requestSync() {
        if (!VoxyNetworkHandler.shouldEnableStreaming()) {
            Logger.info("LOD streaming disabled in single-player");
            return;
        }

        Logger.info("Requesting LOD sync from server...");
        VoxyNetworkHandler.sendToServer(VoxyPacketPayload.syncRequest());
    }

    /**
     * Get reception stats.
     */
    public String getStats() {
        return String.format("Received: %d, Applied: %d, Cached: %d",
                sectionsReceived.get(), sectionsApplied.get(),
                receivedSections.size());
    }

    public int getPendingSize() {
        return this.pendingSections.size();
    }

    public int getReceivedCacheSize() {
        return this.receivedSections.size();
    }

    public int getSectionsReceived() {
        return this.sectionsReceived.get();
    }

    public int getSectionsApplied() {
        return this.sectionsApplied.get();
    }

    @Override
    public void close() {
        isActive.set(false);
        processingExecutor.shutdown();
        reassemblyBuffers.clear();
        receivedSections.clear();
        idRemapper.reset();
        Logger.info("LodReceptionService closed");
    }

    /**
     * Buffer for reassembling chunked section data.
     */
    private static class ChunkReassemblyBuffer {
        private final ConcurrentHashMap<Integer, byte[]> chunks = new ConcurrentHashMap<>();
        private int totalSize = 0;

        void addChunk(int offset, byte[] data, int srcOffset, int length) {
            byte[] chunk = new byte[length];
            System.arraycopy(data, srcOffset, chunk, 0, length);
            chunks.put(offset, chunk);
            totalSize = Math.max(totalSize, offset + length);
        }

        byte[] assemble() {
            if (chunks.isEmpty())
                return null;

            byte[] result = new byte[totalSize];
            for (var entry : chunks.entrySet()) {
                System.arraycopy(entry.getValue(), 0, result, entry.getKey(), entry.getValue().length);
            }
            return result;
        }
    }
}
