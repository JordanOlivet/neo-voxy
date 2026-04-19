package me.cortex.voxy.common.world;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        // The "zero key" path triggers when mix(loc) == 0; mix(0) == 0, so passing 0 hits it.
        // This is the only path in getSecOrMakeLoader that currently works (see bug below).
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
    void getOrMakeForNonZeroKeyThrowsDueToInvertedNullCheck() {
        // Documents a bug in LoadedPositionTracker.getSecOrMakeLoader (non-zero-key branch):
        //
        //     if (pos < 0) {
        //         pos = -pos-1;
        //         // No entry found but we have acquired a write location
        //         if (this.value[pos] == null) {
        //             throw new IllegalStateException();   // ← inverted: throws on the
        //                                                   //   normal first-insert path
        //         }
        //         Object val = this.value[pos] = this.factory.get();
        //         ...
        //
        // The check fires every time a fresh slot is claimed, so any first insert of
        // a key whose mix() != 0 explodes. The class is therefore only usable today
        // through its zero-key fast path.
        //
        // Pin the current behavior so the test breaks (and forces re-evaluation) once
        // the bug is fixed.
        LoadedPositionTracker tracker = new LoadedPositionTracker(Object::new);
        assertThrows(IllegalStateException.class, () -> tracker.getSecOrMakeLoader(0xDEADBEEFL));
    }
}
