package me.cortex.voxy.common.world;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoadedPositionTrackerTest {

    @Test
    void mixIsDeterministic() {
        assertEquals(LoadedPositionTracker.mix(0L), LoadedPositionTracker.mix(0L));
        assertEquals(LoadedPositionTracker.mix(123L), LoadedPositionTracker.mix(123L));
        assertEquals(LoadedPositionTracker.mix(-1L), LoadedPositionTracker.mix(-1L));
    }

    @Test
    void mixDistinctInputsProduceDistinctOutputs() {
        Set<Long> seen = new HashSet<>();
        Random r = new Random(0xBEEFL);
        for (int i = 0; i < 1024; i++) {
            seen.add(LoadedPositionTracker.mix(r.nextLong()));
        }
        assertEquals(1024, seen.size(), "1024 random inputs should produce 1024 distinct mixed outputs");
    }

    @Test
    void mixZeroIsTreatedAsSpecialCase() {
        // The class uses mix(0)==0 as a sentinel for the lock-free zero slot.
        assertEquals(0L, LoadedPositionTracker.mix(0L));
    }

    @Test
    void zeroKeyUsesLockFreeSpecialSlot() {
        AtomicInteger calls = new AtomicInteger();
        LoadedPositionTracker tracker = new LoadedPositionTracker(() -> {
            calls.incrementAndGet();
            return new Object();
        });
        Object first = tracker.getSecOrMakeLoader(0L);
        Object second = tracker.getSecOrMakeLoader(0L);
        assertSame(first, second);
        assertEquals(1, calls.get());
    }

    @Test
    void exchangeOnZeroKeySwapsSpecialSlot() {
        LoadedPositionTracker tracker = new LoadedPositionTracker(Object::new);
        Object original = tracker.getSecOrMakeLoader(0L);
        Object replacement = new Object();
        Object returned = tracker.exchange(0L, replacement);
        assertSame(original, returned);
        assertSame(replacement, tracker.getSecOrMakeLoader(0L));
    }

    @Test
    void exchangeRejectsNullReplacement() {
        LoadedPositionTracker tracker = new LoadedPositionTracker(Object::new);
        assertThrows(IllegalArgumentException.class, () -> tracker.exchange(0L, null));
    }

    @Test
    void getOrMakeStoresFactoryResultForNonZeroKey() {
        AtomicInteger calls = new AtomicInteger();
        LoadedPositionTracker tracker = new LoadedPositionTracker(() -> {
            calls.incrementAndGet();
            return "X";
        });
        long key = 0xDEADBEEFL;
        Object first = tracker.getSecOrMakeLoader(key);
        Object second = tracker.getSecOrMakeLoader(key);
        assertSame(first, second, "second call must return the same instance");
        assertEquals(1, calls.get(), "factory must be invoked exactly once for the same key");
    }

    @Test
    void distinctNonZeroKeysGetDistinctValues() {
        AtomicInteger n = new AtomicInteger();
        LoadedPositionTracker tracker = new LoadedPositionTracker(() ->
                "value-" + n.incrementAndGet());
        Object a = tracker.getSecOrMakeLoader(1);
        Object b = tracker.getSecOrMakeLoader(2);
        Object c = tracker.getSecOrMakeLoader(3);
        assertNotEquals(a, b);
        assertNotEquals(b, c);
        assertNotEquals(a, c);
    }

    @Test
    void exchangeReturnsOldValueForNonZeroKey() {
        LoadedPositionTracker tracker = new LoadedPositionTracker(Object::new);
        long key = 0xFEEDL;
        Object first = tracker.getSecOrMakeLoader(key);
        Object replacement = new Object();
        Object returned = tracker.exchange(key, replacement);
        assertSame(first, returned);
        assertSame(replacement, tracker.getSecOrMakeLoader(key));
    }

    @Test
    void manyKeysFitWithinDefaultCapacity() {
        // Default size=12 → 4096 slots. 1000 keys should comfortably fit and round-trip.
        LoadedPositionTracker tracker = new LoadedPositionTracker(Object::new);
        Object[] expected = new Object[1000];
        for (int i = 1; i <= 1000; i++) {
            expected[i - 1] = tracker.getSecOrMakeLoader(i);
        }
        for (int i = 1; i <= 1000; i++) {
            assertSame(expected[i - 1], tracker.getSecOrMakeLoader(i),
                    "key " + i + " must round-trip");
        }
    }
}
