package me.cortex.voxy.common;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight first-connect / hot-path diagnostic facility.
 * <p>
 * Disabled by default. Enable via {@link #setEnabled(boolean)} (driven from
 * {@code VoxyConfig.diagFirstConnect} on the client and
 * {@code VoxyServerConfig.isLogDiagFirstConnectEffective()} on the server).
 * When enabled:
 * <ul>
 * <li>A diagnostic <i>window</i> is opened by {@link #startWindow(long)} (e.g.
 * when the client issues a LOD sync request). All in-window logs include the
 * milliseconds elapsed since the window opened so events can be correlated.
 * </li>
 * <li>{@link #shouldSnapshot()} rate-limits periodic state dumps to ~1Hz.</li>
 * <li>{@link #timing(String, long, long)} logs hot-path durations only when
 * they exceed a per-callsite threshold, to keep noise low.</li>
 * </ul>
 * <p>
 * All entry points are no-ops when {@code enabled == false} and very nearly
 * free (one volatile read), so they are safe to leave wired into hot paths.
 */
public final class VoxyDiag {

    private VoxyDiag() {}

    private static volatile boolean enabled = false;

    /** Window state — published via volatiles so any thread can read consistently. */
    private static volatile long windowStartNanos = 0L;
    private static volatile long windowEndNanos = 0L;

    /** Last snapshot emit timestamp (nanos). */
    private static final AtomicLong lastSnapshotNanos = new AtomicLong(0L);

    /** Period between snapshot emissions when {@link #shouldSnapshot()} is polled. */
    private static final long SNAPSHOT_INTERVAL_NS = 1_000_000_000L;

    /**
     * Enable or disable the diagnostic facility. Both client and server may
     * call this from their respective config loaders; in single-player both
     * configs are read by the same JVM. To avoid one side accidentally turning
     * off the other side's flag we never transition {@code true → false}
     * within a JVM lifetime — restart the process to clear. Calls with
     * {@code false} are no-ops once enabled.
     */
    public static void setEnabled(boolean v) {
        if (v) enabled = true;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Open a fresh diagnostic window of {@code durationSeconds}. Re-opening
     * resets the start time so each connect gets a clean window. Becomes a
     * no-op when not enabled.
     */
    public static void startWindow(long durationSeconds) {
        if (!enabled) return;
        long now = System.nanoTime();
        windowStartNanos = now;
        windowEndNanos = now + durationSeconds * 1_000_000_000L;
        lastSnapshotNanos.set(0L);
        Logger.info("[VoxyDiag] window opened (" + durationSeconds + "s)");
    }

    public static boolean inWindow() {
        if (!enabled) return false;
        long start = windowStartNanos;
        if (start == 0L) return false;
        return System.nanoTime() < windowEndNanos;
    }

    public static long sinceStartMs() {
        long start = windowStartNanos;
        if (start == 0L) return 0L;
        return (System.nanoTime() - start) / 1_000_000L;
    }

    /**
     * Returns {@code true} at most once per {@link #SNAPSHOT_INTERVAL_NS} while
     * the window is open. Caller follows up with {@link #log(String)} to emit
     * the snapshot payload. Thread-safe — only one caller per interval wins.
     */
    public static boolean shouldSnapshot() {
        if (!inWindow()) return false;
        long now = System.nanoTime();
        long last = lastSnapshotNanos.get();
        if (now - last < SNAPSHOT_INTERVAL_NS) return false;
        return lastSnapshotNanos.compareAndSet(last, now);
    }

    /**
     * Emit a discrete event with the time elapsed since {@link #startWindow}.
     * Use for first-time events (mapper sync received, sync complete, …).
     */
    public static void event(String label) {
        if (!enabled) return;
        Logger.info("[VoxyDiag] +" + sinceStartMs() + "ms " + label);
    }

    /** Unconditional in-diag log (already gated by caller). */
    public static void log(String label) {
        if (!enabled) return;
        Logger.info("[VoxyDiag] " + label);
    }

    /**
     * Conditional hot-path timing log: only emits when {@code nanos >=
     * thresholdNanos}. Pass thresholds in line with the path's expected cost
     * (e.g. 5_000_000 = 5 ms is a reasonable default for per-frame work).
     */
    public static void timing(String label, long nanos, long thresholdNanos) {
        if (!enabled) return;
        if (nanos < thresholdNanos) return;
        Logger.info("[VoxyDiag] slow " + label + " " + (nanos / 1_000_000L) + "ms ("
                + nanos + "ns)");
    }
}
