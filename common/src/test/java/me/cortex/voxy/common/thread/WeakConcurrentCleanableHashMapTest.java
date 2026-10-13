package me.cortex.voxy.common.thread;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeakConcurrentCleanableHashMapTest {

    /** Minimal LongSupplier-keyed identity holder. */
    private static final class Key implements LongSupplier {
        private final long id;
        Key(long id) { this.id = id; }
        @Override public long getAsLong() { return this.id; }
    }

    @Test
    void computeIfAbsentStoresAndRetrieves() {
        WeakConcurrentCleanableHashMap<Key, String> map = new WeakConcurrentCleanableHashMap<>(v -> {});
        Key k = new Key(42);
        AtomicInteger calls = new AtomicInteger();
        String v1 = map.computeIfAbsent(k, () -> { calls.incrementAndGet(); return "first"; });
        assertEquals("first", v1);
        assertEquals(1, calls.get());
        assertEquals(1, map.size());
    }

    @Test
    void computeIfAbsentDoesNotRecomputeForSameId() {
        WeakConcurrentCleanableHashMap<Key, String> map = new WeakConcurrentCleanableHashMap<>(v -> {});
        Key k = new Key(7);
        AtomicInteger calls = new AtomicInteger();
        String first = map.computeIfAbsent(k, () -> { calls.incrementAndGet(); return "X"; });
        String second = map.computeIfAbsent(k, () -> { calls.incrementAndGet(); return "Y"; });
        assertSame(first, second);
        assertEquals(1, calls.get(), "second call must not invoke supplier");
    }

    @Test
    void distinctIdsCoexist() {
        WeakConcurrentCleanableHashMap<Key, Integer> map = new WeakConcurrentCleanableHashMap<>(v -> {});
        for (int i = 0; i < 64; i++) {
            int id = i;
            map.computeIfAbsent(new Key(id), () -> id * 10);
        }
        assertEquals(64, map.size());
    }

    @Test
    void clearReturnsAllValuesAndResetsSize() {
        WeakConcurrentCleanableHashMap<Key, String> map = new WeakConcurrentCleanableHashMap<>(v -> {});
        for (int i = 0; i < 5; i++) {
            int id = i;
            map.computeIfAbsent(new Key(id), () -> "v" + id);
        }
        assertEquals(5, map.size());
        List<String> drained = map.clear();
        assertEquals(5, drained.size(), "all values must be returned");
        assertEquals(0, map.size());
    }

    @Test
    void clearOnEmptyMapReturnsEmptyList() {
        WeakConcurrentCleanableHashMap<Key, Object> map = new WeakConcurrentCleanableHashMap<>(v -> {});
        List<Object> drained = map.clear();
        assertNotNull(drained);
        assertEquals(0, drained.size());
    }

    @Test
    void cleanupTriggersValueCleanerWhenKeyGced() throws InterruptedException {
        AtomicInteger cleaned = new AtomicInteger();
        WeakConcurrentCleanableHashMap<Key, String> map =
                new WeakConcurrentCleanableHashMap<>(v -> cleaned.incrementAndGet());

        // Insert and drop the strong reference.
        map.computeIfAbsent(new Key(99), () -> "ephemeral");
        assertEquals(1, map.size());

        // Force GC; spin a few times because GC is best-effort.
        for (int i = 0; i < 10 && cleaned.get() == 0; i++) {
            System.gc();
            Thread.sleep(20);
            map.cleanup();
        }
        assertTrue(cleaned.get() >= 1, "value cleaner must run at least once after key GC");
        assertEquals(0, map.size(), "size must drop after cleanup");
    }
}
