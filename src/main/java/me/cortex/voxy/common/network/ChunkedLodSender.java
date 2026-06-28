package me.cortex.voxy.common.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import me.cortex.voxy.common.Logger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles bandwidth-limited chunked transfer of LOD section data to players.
 * <p>
 * Based on Distant Horizons' FullDataPayloadSender pattern. Large sections are
 * split into smaller chunks (64KB by default) and sent at a controlled rate
 * based on the player's bandwidth allocation.
 */
public class ChunkedLodSender implements AutoCloseable {

    /** Default chunk size: 64KB (smaller than DH's 1MB for lower latency) */
    public static final int CHUNK_SIZE = 65536;

    /** Tick rate for sending (20 ticks/sec = 50ms per tick) */
    private static final int TICK_RATE = 20;

    /** Timer for tick-based sending */
    private static final Timer SEND_TIMER = new Timer("VoxyChunkedLodSender", true);

    private final ServerPlayer player;
    private final SharedBandwidthLimit sharedBandwidthLimit;
    private final int perPlayerLimitKBps;

    private final ConcurrentLinkedQueue<PendingTransfer> transferQueue = new ConcurrentLinkedQueue<>();
    private final TimerTask tickTask;
    private final AtomicBoolean isActive = new AtomicBoolean(true);

    // Time-based token bucket (guarded by the synchronized tick()). tokenBytes is
    // the accumulated send allowance; lastRefillNanos is the wall-clock anchor we
    // refill against. 0 = not yet initialised.
    private double tokenBytes = 0;
    private long lastRefillNanos = 0L;

    // Stats
    private long totalBytesSent = 0;
    private int sectionsQueued = 0;
    private int sectionsCompleted = 0;

    /**
     * Create a chunked sender for a specific player.
     */
    public ChunkedLodSender(ServerPlayer player, SharedBandwidthLimit sharedBandwidthLimit, int perPlayerLimitKBps) {
        this.player = player;
        this.sharedBandwidthLimit = sharedBandwidthLimit;
        this.perPlayerLimitKBps = perPlayerLimitKBps;

        this.tickTask = new TimerTask() {
            @Override
            public void run() {
                tick();
            }
        };

        SEND_TIMER.scheduleAtFixedRate(tickTask, 0, 1000 / TICK_RATE);
        sharedBandwidthLimit.setSenderActive(this, true);
    }

    /**
     * Queue a section for chunked transfer.
     * 
     * @param sectionData Serialized section data
     * @param sectionId   Unique ID for this section (for reassembly)
     * @param onComplete  Callback when transfer completes
     */
    public void queueSection(byte[] sectionData, int sectionId, Runnable onComplete) {
        if (!isActive.get()) {
            return;
        }

        boolean wasEmpty = transferQueue.isEmpty();
        transferQueue.add(new PendingTransfer(sectionData, sectionId, onComplete));
        sectionsQueued++;

        // Event-driven wake: don't let the first byte of a freshly-streamed
        // section wait up to 50ms for the next scheduled tick. tick() is
        // idempotent and respects the bandwidth budget — calling it directly
        // is the same work the timer would do, just sooner.
        if (wasEmpty) {
            try {
                tick();
            } catch (Throwable t) {
                me.cortex.voxy.common.Logger.error("Eager tick after queueSection failed", t);
            }
        }
    }

    /**
     * Queue a section without completion callback.
     */
    public void queueSection(byte[] sectionData, int sectionId) {
        queueSection(sectionData, sectionId, null);
    }

    /**
     * Called every tick to send pending data.
     */
    private synchronized void tick() {
        if (!isActive.get() || !player.isAlive()) {
            return;
        }

        // Refill the token bucket from elapsed wall-clock time. getEffectiveLimitKBps
        // is a *rate* (KB/s), not a per-call budget: an eager tick fired 1ms after
        // the previous one only earns ~1ms of tokens, so it cannot be used to flood
        // the link. (The old getBytesPerTick handed out a full fresh budget on every
        // call — combined with the eager tick in queueSection that effectively
        // disabled the cap, which is what produced the multi-MB/s LOD flood.)
        int limitKBps = sharedBandwidthLimit.getEffectiveLimitKBps(perPlayerLimitKBps);
        boolean unlimited = (limitKBps <= 0 || limitKBps == Integer.MAX_VALUE);

        long bytesRemaining;
        if (unlimited) {
            bytesRemaining = Long.MAX_VALUE;
        } else {
            long now = System.nanoTime();
            if (lastRefillNanos == 0L) {
                lastRefillNanos = now;
            }
            long elapsedNanos = now - lastRefillNanos;
            if (elapsedNanos < 0L) {
                elapsedNanos = 0L;
            }
            lastRefillNanos = now;

            double bytesPerSec = (double) limitKBps * 1000.0;
            tokenBytes += bytesPerSec * (elapsedNanos / 1_000_000_000.0);

            // Cap accumulated tokens so an idle stream can't bank a giant burst that
            // re-floods the connection the instant work resumes. ~2 ticks (100ms) of
            // allowance keeps first-byte latency low without bursting.
            double burstCap = bytesPerSec * 2.0 / TICK_RATE;
            if (tokenBytes > burstCap) {
                tokenBytes = burstCap;
            }
            bytesRemaining = (long) tokenBytes;
        }

        long consumed = 0L;
        while (bytesRemaining > 0) {
            PendingTransfer transfer = transferQueue.peek();
            if (transfer == null) {
                break;
            }

            int dataRemaining = transfer.buffer.readableBytes();

            // Fast path: section is small enough to fit in a single MC custom
            // payload and we have the bandwidth budget for it. Skip the chunked
            // protocol entirely — no 9-byte header per chunk, no reassembly
            // buffer round-trip on the client.
            if (transfer.bytesSent == 0
                    && dataRemaining <= CHUNK_SIZE
                    && dataRemaining <= bytesRemaining) {
                byte[] sectionData = new byte[dataRemaining];
                transfer.buffer.readBytes(sectionData);
                VoxyNetworkHandler.sendToPlayer(player, VoxyPacketPayload.section(sectionData));
                bytesRemaining -= dataRemaining;
                consumed += dataRemaining;
                totalBytesSent += dataRemaining;
                transferQueue.poll();
                sectionsCompleted++;
                if (transfer.onComplete != null) {
                    try {
                        transfer.onComplete.run();
                    } catch (Exception e) {
                        Logger.error("Error in transfer completion callback: " + e.getMessage());
                    }
                }
                continue;
            }

            // Calculate chunk size (min of remaining bytes, CHUNK_SIZE, and remaining data)
            int chunkSize = (int) Math.min(Math.min(bytesRemaining, (long) CHUNK_SIZE), (long) dataRemaining);

            if (chunkSize <= 0) {
                break;
            }

            // Read chunk from buffer
            byte[] chunkData = new byte[chunkSize + 9]; // 1 byte type + 4 byte sectionId + 4 byte offset
            int offset = transfer.bytesSent;

            // Build chunk packet: [sectionId:4][offset:4][isLast:1][data:N]
            chunkData[0] = (byte) (transfer.sectionId >> 24);
            chunkData[1] = (byte) (transfer.sectionId >> 16);
            chunkData[2] = (byte) (transfer.sectionId >> 8);
            chunkData[3] = (byte) transfer.sectionId;
            chunkData[4] = (byte) (offset >> 24);
            chunkData[5] = (byte) (offset >> 16);
            chunkData[6] = (byte) (offset >> 8);
            chunkData[7] = (byte) offset;

            boolean isLast = (dataRemaining - chunkSize) <= 0;
            chunkData[8] = (byte) (isLast ? 1 : 0);

            // Copy actual data
            transfer.buffer.readBytes(chunkData, 9, chunkSize);
            transfer.bytesSent += chunkSize;

            // Send the chunk
            VoxyNetworkHandler.sendToPlayer(player, VoxyPacketPayload.chunk(chunkData));

            bytesRemaining -= chunkSize;
            consumed += chunkSize;
            totalBytesSent += chunkSize;

            // Check if transfer is complete
            if (transfer.buffer.readableBytes() == 0) {
                transferQueue.poll();
                sectionsCompleted++;

                if (transfer.onComplete != null) {
                    try {
                        transfer.onComplete.run();
                    } catch (Exception e) {
                        Logger.error("Error in transfer completion callback: " + e.getMessage());
                    }
                }
            }
        }

        // Deduct what we actually sent from the bucket so unused allowance carries
        // to the next tick (up to the burst cap) instead of being lost or doubled.
        if (!unlimited) {
            tokenBytes -= consumed;
            if (tokenBytes < 0) {
                tokenBytes = 0;
            }
        }

        // Update active status based on queue
        sharedBandwidthLimit.setSenderActive(this, !transferQueue.isEmpty());
    }

    /**
     * Get the number of pending transfers.
     */
    public int getPendingCount() {
        return transferQueue.size();
    }

    /**
     * Get total bytes sent.
     */
    public long getTotalBytesSent() {
        return totalBytesSent;
    }

    /**
     * Get stats string for debugging.
     */
    public String getStatsString() {
        return String.format("Queued: %d, Completed: %d, Pending: %d, Sent: %.2f MB",
                sectionsQueued, sectionsCompleted, transferQueue.size(),
                totalBytesSent / (1024.0 * 1024.0));
    }

    @Override
    public void close() {
        isActive.set(false);
        tickTask.cancel();
        sharedBandwidthLimit.setSenderActive(this, false);
        transferQueue.clear();
    }

    /**
     * Represents a pending section transfer.
     */
    private static class PendingTransfer {
        final ByteBuf buffer;
        final int sectionId;
        final Runnable onComplete;
        int bytesSent = 0;

        PendingTransfer(byte[] data, int sectionId, Runnable onComplete) {
            this.buffer = Unpooled.wrappedBuffer(data);
            this.sectionId = sectionId;
            this.onComplete = onComplete;
        }
    }
}
