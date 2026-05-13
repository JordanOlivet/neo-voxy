package me.cortex.voxy.client.core;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.network.ClientCongestionControl;
import me.cortex.voxy.common.Logger;
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

    /** Sections pending processing because models aren't ready yet */
    private final ConcurrentHashMap<Long, byte[]> pendingSections = new ConcurrentHashMap<>();

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

        this.processingExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "VoxyLodReception");
            t.setDaemon(true);
            return t;
        });

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
            boolean sent = VoxyNetworkHandler.sendToServer(VoxyPacketPayload.syncRequest());
            if (!sent) {
                Logger.warn("Initial sync request not delivered — falling back to client-local ingest");
                localMode = true;
                mapperReady = true;
            }
        }

        // Process pending sections whose models are now available
        if (!pendingSections.isEmpty()) {
            processPendingSections();
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
     */
    private void handleMapperSync(VoxyPacketPayload payload) {
        Logger.info("Received mapper sync from server (" + payload.data().length + " bytes)");
        idRemapper.buildFromServerData(payload.data(), clientMapper);
        mapperReady = true;
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
            if (sectionData.hasData() && !areModelsAvailable(sectionData.voxelData)) {
                // Models not ready yet, queue for later processing
                pendingSections.put(key, data);
                return;
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
     * Checks if all models referenced in the voxel data are available in the model
     * bakery.
     *
     * @param voxelData The voxel data array.
     * @return True if all models are available, false otherwise.
     */
    private boolean areModelsAvailable(long[] voxelData) {
        if (!idRemapper.isReady()) {
            return false; // Cannot check model availability without a remapper
        }
        // Sample voxel data to check if models are ready
        // Only check a small sample to avoid performance issues
        it.unimi.dsi.fastutil.ints.IntOpenHashSet checkedBlocks = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();

        // Sample every 64th voxel to keep it fast
        int step = Math.max(1, voxelData.length / 64);
        for (int i = 0; i < voxelData.length; i += step) {
            long serverVoxel = voxelData[i];
            long clientVoxel = idRemapper.remapVoxelId(serverVoxel);
            int clientBlockId = me.cortex.voxy.common.world.other.Mapper.getBlockId(clientVoxel);
            if (clientBlockId != 0 && checkedBlocks.add(clientBlockId)) {
                if (!modelBakery.factory.hasModelForBlockId(clientBlockId)) {
                    // Request the model to be baked
                    modelBakery.requestBlockBake(clientBlockId);
                    return false;
                }
            }
            // Limit checking to first 16 unique blocks to keep it fast
            if (checkedBlocks.size() >= 16) {
                break;
            }
        }
        return true;
    }

    /**
     * Processes sections that were previously queued because their models were not
     * ready.
     */
    private void processPendingSections() {
        // Create a temporary list to avoid ConcurrentModificationException
        // and to allow processing in batches
        Set<Long> sectionsToProcess = ConcurrentHashMap.newKeySet();
        for (Long key : pendingSections.keySet()) {
            sectionsToProcess.add(key);
        }

        for (Long key : sectionsToProcess) {
            byte[] data = pendingSections.get(key);
            if (data != null) {
                try {
                    SectionSerializer.SectionData sectionData = SectionSerializer.deserialize(data);
                    if (sectionData != null && areModelsAvailable(sectionData.voxelData)) {
                        pendingSections.remove(key);
                        // Submit to processing executor to maintain consistent processing flow
                        processingExecutor.submit(() -> processSection(data));
                    }
                } catch (Exception e) {
                    Logger.error(
                            "Error re-processing pending section " + Long.toHexString(key) + ": " + e.getMessage());
                    Logger.error(e);
                    pendingSections.remove(key); // Remove to avoid infinite retries on error
                }
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
        // Optionally send rate update to server
        // congestionControl.sendRateUpdate();
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
