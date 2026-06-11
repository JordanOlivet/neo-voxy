package me.cortex.voxy.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


/**
 * Exercises the 32/32 bit layout guard added to AllocationArena.
 * The arena is pure bookkeeping (no real memory is touched) so we can
 * freely manipulate multi-GB ranges.
 */
class AllocationArenaLimitsTest {

    @Test
    void allocAtMaxIntStoresSizeWithoutCorruption() {
        // Before widening to SIZE_BITS=32, Integer.MAX_VALUE (≈ 2^31-1) already
        // exceeded the old 2^30 cap and would silently truncate getSize.
        AllocationArena a = new AllocationArena();
        long addr = a.alloc(Integer.MAX_VALUE);
        assertEquals(0, addr);
        assertEquals(Integer.MAX_VALUE, a.getSize(addr));
        assertEquals((long) Integer.MAX_VALUE, a.getSize());
    }

    @Test
    void checkSizeRejectsOversizedBlock() {
        // The merge guard is reachable only at exotic multi-GB layouts that also
        // happen to overflow the address range; drive the helper directly instead.
        assertThrows(IllegalStateException.class, () -> AllocationArena.checkSize(AllocationArena.MAX_BLOCK_SIZE + 1));
        assertThrows(IllegalStateException.class, () -> AllocationArena.checkSize(-1));
        // Boundary: exactly MAX_BLOCK_SIZE must be accepted.
        AllocationArena.checkSize(AllocationArena.MAX_BLOCK_SIZE);
        AllocationArena.checkSize(0);
    }

    @Test
    void smallAllocationsStillRoundTrip() {
        AllocationArena a = new AllocationArena();
        long x = a.alloc(16);
        long y = a.alloc(32);
        long z = a.alloc(64);
        assertEquals(0, x);
        assertEquals(16, y);
        assertEquals(48, z);
        a.free(y);
        long y2 = a.alloc(32);
        assertEquals(16, y2);
    }
}
