package me.cortex.voxy.common.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AllocationArenaTest {

    @Test
    void sequentialAllocGrowsLinearly() {
        AllocationArena a = new AllocationArena();
        long p0 = a.alloc(100);
        long p1 = a.alloc(50);
        long p2 = a.alloc(25);
        assertEquals(0, p0);
        assertEquals(100, p1);
        assertEquals(150, p2);
        assertEquals(175, a.getSize());
    }

    @Test
    void allocSizeStoredPerAddress() {
        AllocationArena a = new AllocationArena();
        long addr = a.alloc(42);
        assertEquals(42, a.getSize(addr));
    }

    @Test
    void freeShrinksWhenLast() {
        AllocationArena a = new AllocationArena();
        a.alloc(100);
        long second = a.alloc(50);
        a.free(second);
        assertEquals(100, a.getSize());
    }

    @Test
    void freeMiddleBlockReusedForSameSize() {
        AllocationArena a = new AllocationArena();
        long p0 = a.alloc(100);
        long p1 = a.alloc(50);
        long p2 = a.alloc(25);
        a.free(p1);
        long p3 = a.alloc(50);
        assertEquals(p1, p3, "50-byte free slot should be reused exactly");
        assertEquals(175, a.getSize());
    }

    @Test
    void freeAdjacentBlocksMerge() {
        AllocationArena a = new AllocationArena();
        a.alloc(100);
        long b = a.alloc(50);
        long c = a.alloc(50);
        long d = a.alloc(50);
        a.alloc(100);

        a.free(b);
        a.free(c);
        long big = a.alloc(100);
        assertEquals(b, big, "merged 50+50 free region should satisfy 100 at original address");
    }

    @Test
    void expandIntoFreeSpace() {
        AllocationArena a = new AllocationArena();
        long first = a.alloc(50);
        long middle = a.alloc(50);
        a.alloc(50);
        a.free(middle);
        assertTrue(a.expand(first, 20));
        assertEquals(70, a.getSize(first));
    }

    @Test
    void expandBeyondAvailableFails() {
        AllocationArena a = new AllocationArena();
        long first = a.alloc(50);
        a.alloc(50);
        assertFalse(a.expand(first, 10), "cannot grow into taken neighbor");
    }

    @Test
    void expandTailGrowsArena() {
        AllocationArena a = new AllocationArena();
        long first = a.alloc(50);
        assertTrue(a.expand(first, 100));
        assertEquals(150, a.getSize());
        assertEquals(150, a.getSize(first));
    }

    @Test
    void resetClearsState() {
        AllocationArena a = new AllocationArena();
        a.alloc(123);
        a.reset();
        assertEquals(0, a.getSize());
        assertEquals(0, a.alloc(10));
    }

    @Test
    void resizedFlagSetOnGrowth() {
        AllocationArena a = new AllocationArena();
        a.alloc(10);
        assertTrue(a.getResetResized());
        assertFalse(a.getResetResized(), "flag should clear on read");
        a.alloc(10);
        assertTrue(a.getResetResized(), "growth sets flag again");
    }

    @Test
    void sizeLimitRejectsAlloc() {
        AllocationArena a = new AllocationArena();
        a.setLimit(100);
        assertEquals(0, a.alloc(50));
        assertEquals(50, a.alloc(50));
        assertEquals(AllocationArena.SIZE_LIMIT, a.alloc(1));
    }

    @Test
    void randomizedAllocFreeNeverOverlaps() {
        AllocationArena a = new AllocationArena();
        Random r = new Random(0xC0DEL);
        Set<Long> live = new HashSet<>();

        for (int i = 0; i < 2000; i++) {
            if (live.isEmpty() || r.nextInt(3) != 0) {
                int size = 1 + r.nextInt(256);
                long addr = a.alloc(size);
                assertNotEquals(AllocationArena.SIZE_LIMIT, addr);
                assertTrue(live.add(addr), "duplicate address " + addr);
                assertEquals(size, a.getSize(addr));
            } else {
                long victim = live.iterator().next();
                live.remove(victim);
                a.free(victim);
            }
        }
    }
}
