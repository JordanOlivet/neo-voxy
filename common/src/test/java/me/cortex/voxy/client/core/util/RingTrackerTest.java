package me.cortex.voxy.client.core.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RingTrackerTest {

    @Test
    void freshTrackerWithFillFalseHasNoOps() {
        RingTracker tracker = new RingTracker(4, 0, 0, false);
        AtomicInteger adds = new AtomicInteger();
        AtomicInteger rems = new AtomicInteger();
        // process() touches Logger only when there are entries, so 0-entry path is safe.
        int processed = tracker.process(64, (x, z) -> adds.incrementAndGet(),
                (x, z) -> rems.incrementAndGet());
        assertEquals(0, processed);
        assertEquals(0, adds.get());
        assertEquals(0, rems.get());
    }

    @Test
    void filledTrackerEnqueuesLoadsCoveringDisk() {
        int radius = 5;
        RingTracker tracker = new RingTracker(radius, 0, 0, true);
        AtomicInteger adds = new AtomicInteger();
        AtomicInteger rems = new AtomicInteger();
        Set<Long> loaded = new HashSet<>();
        // Drain everything; large N.
        int processed = tracker.process(100_000, (x, z) -> {
            adds.incrementAndGet();
            loaded.add(((long) x << 32) | (z & 0xFFFFFFFFL));
        }, (x, z) -> rems.incrementAndGet());

        assertTrue(adds.get() > 0, "fill ring must enqueue loads");
        assertEquals(0, rems.get(), "no removes expected from a fresh fill");
        // Sanity: every loaded point must lie within the disk.
        for (long packed : loaded) {
            int x = (int) (packed >>> 32);
            int z = (int) (packed & 0xFFFFFFFFL);
            assertTrue(x * x + z * z <= radius * radius + radius,
                    "loaded (" + x + "," + z + ") outside disk");
        }
        assertEquals(loaded.size(), adds.get(), "no duplicate loads");
    }

    @Test
    void unloadCancelsPendingLoads() {
        // fill (load=+1), then unload (load=-1) yields net zero per cell → process drops them all.
        RingTracker tracker = new RingTracker(3, 0, 0, true);
        tracker.unload();
        AtomicInteger adds = new AtomicInteger();
        AtomicInteger rems = new AtomicInteger();
        int processed = tracker.process(10_000, (x, z) -> adds.incrementAndGet(),
                (x, z) -> rems.incrementAndGet());
        assertEquals(0, processed);
        assertEquals(0, adds.get());
        assertEquals(0, rems.get());
    }

    @Test
    void moveCenterByOnePreservesInvariant() {
        // Doesn't crash, doesn't throw IllegalStateException via internal addTo asserts.
        RingTracker tracker = new RingTracker(4, 0, 0, true);
        tracker.moveCenter(1, 0);
        tracker.moveCenter(1, 1);
        tracker.moveCenter(0, 1);
        tracker.moveCenter(0, 0);
    }

    @Test
    void moveCenterLargeJumpResetsRing() {
        // Jump > radius+1 → tracker takes the "unload all + load all at new center" branch.
        RingTracker tracker = new RingTracker(3, 0, 0, true);
        tracker.moveCenter(100, 100);
    }

    @Test
    void smallRandomWalkDoesNotThrow() {
        // Lightweight version of the embedded main(): small radii, modest iterations.
        // Logger is only touched once we call process(), and we don't here.
        Random r = new Random(0xCAFEL);
        for (int seed = 0; seed < 5; seed++) {
            RingTracker tracker = new RingTracker(r.nextInt(8) + 1, 0, 0, true);
            int range = r.nextInt(20) + 1;
            for (int i = 0; i < 500; i++) {
                tracker.moveCenter(r.nextInt(range * 2 + 1) - range,
                        r.nextInt(range * 2 + 1) - range);
            }
        }
    }

    @Test
    void stealingConstructorTransfersPendingOps() {
        RingTracker source = new RingTracker(2, 0, 0, true);
        RingTracker target = new RingTracker(source, 2, 0, 0, false);

        // Source must be drained.
        AtomicInteger sourceCount = new AtomicInteger();
        source.process(10_000, (x, z) -> sourceCount.incrementAndGet(),
                (x, z) -> sourceCount.incrementAndGet());
        assertEquals(0, sourceCount.get());

        // Target must inherit the loads.
        AtomicInteger targetCount = new AtomicInteger();
        target.process(10_000, (x, z) -> targetCount.incrementAndGet(),
                (x, z) -> {});
        assertTrue(targetCount.get() > 0, "stolen ops must surface in target");
    }
}
