package me.cortex.voxy.client.core.model;


import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.VoxyDiag;
import me.cortex.voxy.common.world.other.Mapper;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;

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
            // Replaced the old fixed Thread.sleep(10) with a wait/notify loop on the
            // factory's work queues. The sleep capped throughput at ~100 passes/s
            // regardless of load, which was a major contributor to the first-connect
            // freeze (bake thread idling 10 ms between batches while a flood of
            // sections waited on models). Now the thread blocks until a producer
            // signals new work (download callback that pushes a RawBakeResult, or
            // addBiome). The 100 ms timeout is just a safety net for missed
            // notifies.
            while (this.isRunning) {
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
        long start = System.nanoTime();
        // Give the GL upload pass roughly half the per-tick budget. The previous
        // implementation drained the entire upload queue every frame which caused
        // multi-hundred-ms main-thread spikes during the initial bake flood and
        // starved Sodium / vanilla rendering. The frex "finish everything" path
        // (VoxyRenderSystem) passes a 100 ms budget so behavior there is preserved.
        long uploadBudget = Math.max(50_000L, totalBudget / 2);
        long uploadStart = VoxyDiag.isEnabled() ? System.nanoTime() : 0L;
        this.factory.tickAndProcessUploads(uploadBudget);
        if (VoxyDiag.isEnabled()) {
            VoxyDiag.timing("tickAndProcessUploads", System.nanoTime() - uploadStart, 5_000_000L);
        }
        //Always do 1 iteration minimum
        Integer i = this.blockIdQueue.poll();
        long bakeStart = VoxyDiag.isEnabled() ? System.nanoTime() : 0L;
        int bakedThisTick = 0;
        if (i != null) {
            int j = 0;
            if (i != null) {
                int fbBinding = glGetInteger(GL_FRAMEBUFFER_BINDING);

                do {
                    this.factory.addEntry(i);
                    j++;
                    if (4<j&&(totalBudget<(System.nanoTime() - start)+50_000))//20<j||
                        break;
                    i = this.blockIdQueue.poll();
                } while (i != null);

                glBindFramebuffer(GL_FRAMEBUFFER, fbBinding);//This is done here as stops needing to set then unset the fb in the thing 1000x
            }
            this.blockIdCount.addAndGet(-j);
            bakedThisTick = j;
        }

        if (VoxyDiag.isEnabled() && bakedThisTick > 0) {
            VoxyDiag.timing("bake addEntry x" + bakedThisTick,
                    System.nanoTime() - bakeStart, 5_000_000L);
        }
        //TimingStatistics.modelProcess.stop();
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
        if (this.mapper.getBlockStateCount() < blockId) {
            Logger.error("Error, got bakeing request for out of range state id. StateId: " + blockId + " max id: " + this.mapper.getBlockStateCount(), new Exception());
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
