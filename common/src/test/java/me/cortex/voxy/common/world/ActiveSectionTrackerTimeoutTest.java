package me.cortex.voxy.common.world;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies that a non-loader thread waiting on a stuck loader throws
 * IllegalStateException after the configured spin-wait budget instead of
 * hanging forever.
 */
class ActiveSectionTrackerTimeoutTest {

    private long originalWarn;
    private long originalThrow;

    @AfterEach
    void restore() {
        ActiveSectionTracker.spinWarnNanos = originalWarn == 0 ? TimeUnit.SECONDS.toNanos(10) : originalWarn;
        ActiveSectionTracker.spinThrowNanos = originalThrow == 0 ? TimeUnit.SECONDS.toNanos(60) : originalThrow;
    }

    @Test
    void waiterThrowsWhenLoaderStalls() throws Exception {
        originalWarn = ActiveSectionTracker.spinWarnNanos;
        originalThrow = ActiveSectionTracker.spinThrowNanos;
        ActiveSectionTracker.spinWarnNanos = TimeUnit.MILLISECONDS.toNanos(50);
        ActiveSectionTracker.spinThrowNanos = TimeUnit.MILLISECONDS.toNanos(500);

        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);

        ActiveSectionTracker tracker = new ActiveSectionTracker(1, section -> {
            loaderStarted.countDown();
            try {
                // Block until we explicitly release — simulates stuck I/O.
                releaseLoader.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 0;
        }, 16);

        Thread loader = new Thread(() -> tracker.acquire(0, 0, 0, 0, false), "stuck-loader");
        loader.setDaemon(true);
        loader.start();

        assertNotNull(loaderStarted.await(5, TimeUnit.SECONDS) ? Boolean.TRUE : null,
                "loader thread failed to enter load()");

        AtomicReference<Throwable> caught = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                tracker.acquire(0, 0, 0, 0, false);
            } catch (Throwable t) {
                caught.set(t);
            }
        }, "waiter");
        waiter.setDaemon(true);
        waiter.start();
        waiter.join(TimeUnit.SECONDS.toMillis(5));

        try {
            Throwable t = caught.get();
            assertInstanceOf(IllegalStateException.class, t,
                    "waiter should fail-fast with IllegalStateException, got: " + t);
        } finally {
            releaseLoader.countDown();
            loader.join(TimeUnit.SECONDS.toMillis(5));
        }
    }
}
