package me.cortex.voxy.client.core.model;


import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.VoxyDiag;
import me.cortex.voxy.common.world.other.Mapper;
import java.util.List;
import me.cortex.voxy.common.util.cpu.CpuLayout;

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

    // Several bake threads rasterize models in parallel (each uses its own per-thread
    // bakery, sharing the read-only atlas); a single processor thread turns finished
    // bakes into uploadable textures (that path mutates shared model-id state and must
    // stay single-threaded). Render-thread tick only loads the atlas + uploads.
    private final Thread[] bakeThreads;
    private final Thread processThread;
    // Bake threads wait on this when the bake queue is empty; producers (the atlas
    // load and requestBlockBake) notify it. Using a dedicated monitor avoids the
    // bake threads busy-spinning on the factory's process-work condition.
    private final Object bakeNotifier = new Object();
    private volatile boolean isRunning = true;
    public ModelBakerySubsystem(Mapper mapper) {
        this.mapper = mapper;
        this.factory = new ModelFactory(mapper, this.storage);

        int bakeThreadCount = Math.clamp(CpuLayout.getCoreCount() / 2, 1, 6);
        this.bakeThreads = new Thread[bakeThreadCount];
        for (int t = 0; t < bakeThreadCount; t++) {
            this.bakeThreads[t] = new Thread(() -> {
                while (this.isRunning) {
                    int baked = 0;
                    // Only bake once the atlas has been read on the render thread
                    if (this.factory.isAtlasLoaded()) {
                        baked = this.drainBakeQueue();
                    }
                    if (baked == 0) {
                        // No bake work; wait for new queue entries (or the 100ms net)
                        synchronized (this.bakeNotifier) {
                            try {
                                this.bakeNotifier.wait(100);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                }
            }, "Voxy model bake #" + t);
            this.bakeThreads[t].start();
        }

        this.processThread = new Thread(() -> {
            while (this.isRunning) {
                this.factory.processAllThings();
                try {
                    this.factory.waitForWork(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "Voxy model processor");
        this.processThread.start();
    }

    public void tick(long totalBudget) {
        // Pre-load the block atlas once, on the render thread (needs the GL context).
        // The bake threads only bake after this.
        if (!this.factory.isAtlasLoaded()) {
            this.factory.loadAtlas();
            this.wakeBakeThreads();//atlas ready -> bake threads can start
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

    // Runs on a bake thread: drain pending bake requests and CPU-bake them in
    // parallel (each thread uses its own bakery). Returns how many were baked.
    private int drainBakeQueue() {
        Integer i = this.blockIdQueue.poll();
        int j = 0;
        while (i != null) {
            this.factory.addEntry(i);
            j++;
            // Yield back periodically so other bake threads share the queue and the
            // processor turns finished bakes into uploads
            if (j >= 64) break;
            i = this.blockIdQueue.poll();
        }
        if (j != 0) {
            this.blockIdCount.addAndGet(-j);
        }
        return j;
    }

    private void wakeBakeThreads() {
        synchronized (this.bakeNotifier) {
            this.bakeNotifier.notifyAll();
        }
    }

    public void shutdown() {
        this.isRunning = false;
        this.wakeBakeThreads();// wake any waiting bake threads so they see !isRunning
        this.factory.signalWork();// wake the processor thread too
        try {
            for (var t : this.bakeThreads) {
                t.join();
            }
            this.processThread.join();
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
        this.wakeBakeThreads();//wake a bake thread so it bakes promptly
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
