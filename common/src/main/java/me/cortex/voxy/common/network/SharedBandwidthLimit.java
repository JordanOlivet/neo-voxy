package me.cortex.voxy.common.network;

import me.cortex.voxy.common.Logger;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

/**
 * Manages fair bandwidth sharing across all active LOD streaming connections.
 * <p>
 * Based on Distant Horizons' SharedBandwidthLimit pattern. When multiple
 * players
 * are receiving LOD data simultaneously, each gets an equal share of the global
 * bandwidth limit to prevent any single player from monopolizing the
 * connection.
 */
public class SharedBandwidthLimit {

    /** Default global bandwidth limit: 10 MB/s = 10240 KB/s */
    public static final int DEFAULT_GLOBAL_LIMIT_KBPS = 10240;

    /** Default per-player bandwidth limit: 1 MB/s = 1024 KB/s */
    public static final int DEFAULT_PLAYER_LIMIT_KBPS = 1024;

    private final Set<Object> activeSenders = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final IntSupplier globalLimitSupplier;

    /**
     * Create with default global limit.
     */
    public SharedBandwidthLimit() {
        this(() -> DEFAULT_GLOBAL_LIMIT_KBPS);
    }

    /**
     * Create with custom global limit supplier (e.g., from config).
     */
    public SharedBandwidthLimit(IntSupplier globalLimitSupplier) {
        this.globalLimitSupplier = globalLimitSupplier;
    }

    /**
     * Mark a sender as active or inactive.
     * Active senders share the global bandwidth pool.
     */
    public void setSenderActive(Object sender, boolean active) {
        if (active) {
            activeSenders.add(sender);
        } else {
            activeSenders.remove(sender);
        }
    }

    /**
     * Get the bandwidth share for each active sender.
     * This divides the global limit equally among all active senders.
     * 
     * @return Bandwidth share in KB/s per sender
     */
    public int getBandwidthShareKBps() {
        int globalLimit = globalLimitSupplier.getAsInt();
        if (globalLimit <= 0) {
            // 0 or negative means unlimited
            return Integer.MAX_VALUE;
        }

        int numSenders = Math.max(activeSenders.size(), 1);
        return globalLimit / numSenders;
    }

    /**
     * Calculate bytes allowed to send per tick (50ms).
     *
     * @param perPlayerLimitKBps Per-player limit in KB/s
     * @return Bytes allowed this tick
     */
    public int getBytesPerTick(int perPlayerLimitKBps) {
        int share = getBandwidthShareKBps();
        int effectiveLimit = Math.min(perPlayerLimitKBps, share);

        if (effectiveLimit <= 0 || effectiveLimit == Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }

        // Convert KB/s to bytes per tick (20 ticks/sec)
        // KB/s * 1000 bytes/KB / 20 ticks/sec = bytes/tick
        // Add 1 to account for rounding
        return (effectiveLimit * 1000) / 20 + 1;
    }

    /**
     * Effective per-second send allowance for a sender, in KB/s: the smaller of
     * its per-player limit and its fair share of the (current) global pool.
     * <p>
     * Unlike {@link #getBytesPerTick(int)}, this returns a <em>rate</em>, not a
     * fixed per-call budget. Callers are expected to convert it into send tokens
     * based on actual elapsed wall-clock time (a token bucket), which is what
     * keeps the limit honest when the sender is ticked at an irregular cadence
     * (e.g. an eager flush the instant a section is queued). Handing out a full
     * per-call budget the way {@code getBytesPerTick} does lets such eager calls
     * bypass the cap entirely.
     *
     * @return KB/s allowance, or {@link Integer#MAX_VALUE} when unlimited.
     */
    public int getEffectiveLimitKBps(int perPlayerLimitKBps) {
        int share = getBandwidthShareKBps();
        int effectiveLimit = Math.min(perPlayerLimitKBps, share);
        if (effectiveLimit <= 0) {
            return Integer.MAX_VALUE;
        }
        return effectiveLimit;
    }

    /**
     * Get the number of currently active senders.
     */
    public int getActiveSenderCount() {
        return activeSenders.size();
    }
}
