package me.cortex.voxy.common.util;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HierarchicalBitSetTest {

    @Test
    void allocateNextIsSequential() {
        HierarchicalBitSet bs = new HierarchicalBitSet(1 << 12);
        for (int i = 0; i < 1 << 12; i++) {
            assertEquals(i, bs.allocateNext());
            assertTrue(bs.isSet(i));
        }
        assertEquals(1 << 12, bs.getCount());
        assertEquals(-1, bs.allocateNext(), "should signal full");
    }

    @Test
    void endIdTracksContiguousMax() {
        HierarchicalBitSet bs = new HierarchicalBitSet(256);
        for (int i = 0; i < 256; i++) {
            assertEquals(i, bs.allocateNext());
            assertEquals(i, bs.getMaxIndex());
        }
    }

    @Test
    void freeReleasesIndex() {
        HierarchicalBitSet bs = new HierarchicalBitSet(64);
        for (int i = 0; i < 32; i++) bs.allocateNext();
        assertTrue(bs.free(10));
        assertFalse(bs.isSet(10));
        assertFalse(bs.free(10), "double-free should report already-free");
        assertEquals(31, bs.getCount());
    }

    @Test
    void allocateReusesFreedSlotsFromLowest() {
        HierarchicalBitSet bs = new HierarchicalBitSet(64);
        for (int i = 0; i < 10; i++) bs.allocateNext();
        bs.free(3);
        bs.free(7);
        assertEquals(3, bs.allocateNext());
        assertEquals(7, bs.allocateNext());
        assertEquals(10, bs.allocateNext());
    }

    @Test
    void consecutiveAllocationFindsRun() {
        HierarchicalBitSet bs = new HierarchicalBitSet(512);
        for (int i = 0; i < 50; i++) bs.allocateNext();
        int base = bs.allocateNextConsecutiveCounted(5);
        assertEquals(50, base);
        for (int i = 0; i < 5; i++) assertTrue(bs.isSet(base + i));
        assertEquals(55, bs.getCount());
    }

    @Test
    void randomizedAllocateFreeMatchesRefSet() {
        Random r = new Random(42);
        HierarchicalBitSet bs = new HierarchicalBitSet(1 << 16);
        IntSet ref = new IntOpenHashSet();

        for (int j = 0; j < 10_000; j++) {
            int op = r.nextInt(4);
            if (op == 0 || ref.isEmpty()) {
                int v = bs.allocateNext();
                assertNotEquals(-1, v);
                assertTrue(ref.add(v), "double-allocation of " + v);
            } else {
                int[] snap = ref.toIntArray();
                int victim = snap[r.nextInt(snap.length)];
                assertTrue(bs.free(victim));
                assertTrue(ref.remove(victim));
            }
        }
        assertEquals(ref.size(), bs.getCount());
        for (int v : ref) assertTrue(bs.isSet(v));
    }

    @Test
    void respectsLimit() {
        HierarchicalBitSet bs = new HierarchicalBitSet(8);
        for (int i = 0; i < 8; i++) assertTrue(bs.allocateNext() >= 0);
        assertEquals(-1, bs.allocateNext());
    }
}
