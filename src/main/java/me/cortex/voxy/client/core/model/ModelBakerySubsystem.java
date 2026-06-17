package me.cortex.voxy.client.core.model;


import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.VoxyDiag;
import me.cortex.voxy.common.world.other.Mapper;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class ModelBakerySubsystem {
    //Redo to just make it request the block faces with the async texture download stream which
    // basicly solves all the render stutter due to the baking

    private final ModelStore storage = new ModelStore();
    public final ModelFactory factory;
    private final Mapper mapper;
    private final AtomicInteger blockIdCount = new AtomicInteger();
    private final ConcurrentLinkedDeque<Integer> blockIdQueue = new ConcurrentLinkedDeque<>();//TODO: replace with custom DS

    private final Thread processingThread;
    private volatile boolean isRunning = true;
    public ModelBakerySubsystem(Mapper mapper) {
        this.mapper = mapper;
        this.factory = new ModelFactory(mapper, this.storage);
        this.processingThread = new Thread(()->{
            // The bake itself (software CPU rasterization) now runs HERE, off the
            // render thread, instead of synchronously in tick(). The render thread
            // only pre-loads the block atlas (setupTexture) and uploads finished
            // textures. Blocks on the factory's work monitor when idle; the 100 ms
            // timeout is a safety net for missed notifies.
            while (this.isRunning) {
                // Only bake once the atlas has been read on the render thread
                if (this.factory.bakery.isAtlasLoaded()) {
                    this.drainBakeQueue();
                }
                this.factory.processAllThings();
                try {
                    this.factory.waitForWork(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "Model factory processor");
        this.processingThread.start();
    }

    public void tick(long totalBudget) {
        // Pre-load the block atlas into the software bakery once, on the render
        // thread (needs the GL context). The worker thread bakes only after this.
        if (!this.factory.bakery.isAtlasLoaded()) {
            this.factory.bakery.setupTexture();
            this.factory.signalWork();//wake the worker now that it can bake
        }

        // The render thread no longer bakes (that moved to the worker thread). It
        // only uploads finished textures to the atlas. Give uploads roughly half the
        // per-tick budget so they don't stall Sodium / vanilla rendering.
        long uploadBudget = Math.max(50_000L, totalBudget / 2);
        long uploadStart = VoxyDiag.isEnabled() ? System.nanoTime() : 0L;
        this.factory.tickAndProcessUploads(uploadBudget);
        if (VoxyDiag.isEnabled()) {
            VoxyDiag.timing("tickAndProcessUploads", System.nanoTime() - uploadStart, 5_000_000L);
        }
    }

    // Runs on the model worker thread: drain pending bake requests and CPU-bake them.
    private void drainBakeQueue() {
        Integer i = this.blockIdQueue.poll();
        int j = 0;
        while (i != null) {
            this.factory.addEntry(i);
            j++;
            // Yield back periodically so finished bakes get processed into uploads
            // and we don't hold everything until the queue is fully drained
            if (j >= 64) break;
            i = this.blockIdQueue.poll();
        }
        if (j != 0) {
            this.blockIdCount.addAndGet(-j);
        }
    }

    public void shutdown() {
        this.isRunning = false;
        try {
            this.processingThread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        this.factory.free();
        this.storage.free();
    }

    //This is on this side only and done like this as only worker threads call this code
    private final ReentrantLock seenIdsLock = new ReentrantLock();
    private final IntOpenHashSet seenIds = new IntOpenHashSet(6000);//TODO: move to a lock free concurrent hashmap
    // Out-of-range stateIds reported at most once each. Without this dedup the
    // reject path below spammed a full stack trace per bad id per render tick,
    // starving the render thread (see "out of range state id" freeze report).
    private final IntOpenHashSet outOfRangeSeen = new IntOpenHashSet();
    /**
     * Enqueue a bake for {@code blockId}. Returns {@code true} when this is the
     * first time the id has been seen (so callers can gate one-shot log emits on
     * the return value) and {@code false} when the id was already pending or
     * baked. Previously this method returned void, which forced every caller to
     * maintain its own dedup set — and the mesh-generation worker pool ended up
     * keeping <i>per-worker</i> sets, causing the same "retry path" log line to
     * fire once per worker per missing model. Centralising the dedup here gives
     * callers a single source of truth.
     */
    public boolean requestBlockBake(int blockId) {
        // Valid ids are 0..count-1. Guard with >= (was <, which let blockId == count
        // through to an out-of-bounds bake). No new Exception() / ERROR here: this runs
        // on the render hot path and a bad id is re-offered every tick, so capturing a
        // stack trace per call froze the game. Log once per id at WARN instead.
        if (blockId < 0 || blockId >= this.mapper.getBlockStateCount()) {
            this.seenIdsLock.lock();
            boolean firstReport = this.outOfRangeSeen.add(blockId);
            this.seenIdsLock.unlock();
            if (firstReport) {
                Logger.warn("Skipping bake for out-of-range state id " + blockId + " (max " + this.mapper.getBlockStateCount() + ")");
            }
            return false;
        }
        this.seenIdsLock.lock();
        if (!this.seenIds.add(blockId)) {
            this.seenIdsLock.unlock();
            return false;
        }
        this.seenIdsLock.unlock();
        this.blockIdQueue.add(blockId);
        this.blockIdCount.incrementAndGet();
        this.factory.signalWork();//wake the worker so it bakes promptly
        return true;
    }

    public void addBiome(Mapper.BiomeEntry biomeEntry) {
        this.factory.addBiome(biomeEntry);
    }

    public void addDebugData(List<String> debug) {
        debug.add(String.format("MQ/IF/MC: %04d, %03d, %04d", this.blockIdCount.get(), this.factory.getInflightCount(),  this.factory.getBakedCount()));//Model bake queue/in flight/model baked count
    }

    public ModelStore getStore() {
        return this.storage;
    }

    public boolean areQueuesEmpty() {
        return this.blockIdCount.get()==0 && this.factory.getInflightCount() == 0;
    }

    public int getProcessingCount() {
        return this.blockIdCount.get() + this.factory.getInflightCount();
    }

    public int getQueuedBakeCount() {
        return this.blockIdCount.get();
    }

    public int getInflightBakeCount() {
        return this.factory.getInflightCount();
    }

    public int getBakedCount() {
        return this.factory.getBakedCount();
    }

    public int getQueuedUploadCount() {
        return this.factory.getUploadResultsSize();
    }

    public int getRawBakeResultsSize() {
        return this.factory.getRawBakeResultsSize();
    }
}
