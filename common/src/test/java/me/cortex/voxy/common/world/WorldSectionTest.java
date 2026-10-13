package me.cortex.voxy.common.world;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldSectionTest {

    @Test
    void getIndexPacksYZXOrder() {
        // Index formula: ((y&M)<<10) | ((z&M)<<5) | (x&M), M = 31
        assertEquals(0, WorldSection.getIndex(0, 0, 0));
        assertEquals(1, WorldSection.getIndex(1, 0, 0));
        assertEquals(1 << 5, WorldSection.getIndex(0, 0, 1));
        assertEquals(1 << 10, WorldSection.getIndex(0, 1, 0));
        assertEquals((31 << 10) | (31 << 5) | 31, WorldSection.getIndex(31, 31, 31));
    }

    @Test
    void getIndexMasksToFiveBits() {
        assertEquals(WorldSection.getIndex(1, 2, 3), WorldSection.getIndex(33, 34, 35));
    }

    @Test
    void getIndexIsUniqueAcrossAllCoordinates() {
        Set<Integer> seen = new HashSet<>();
        for (int x = 0; x < 32; x++) {
            for (int y = 0; y < 32; y++) {
                for (int z = 0; z < 32; z++) {
                    int idx = WorldSection.getIndex(x, y, z);
                    assertTrue(idx >= 0 && idx < WorldSection.SECTION_VOLUME);
                    assertTrue(seen.add(idx), "collision at " + x + "," + y + "," + z);
                }
            }
        }
        assertEquals(WorldSection.SECTION_VOLUME, seen.size());
    }

    @Test
    void getChildIndexEncodesXYZLsb() {
        // Formula: (x&1) | ((y&1)<<2) | ((z&1)<<1)
        assertEquals(0b000, WorldSection.getChildIndex(0, 0, 0));
        assertEquals(0b001, WorldSection.getChildIndex(1, 0, 0));
        assertEquals(0b010, WorldSection.getChildIndex(0, 0, 1));
        assertEquals(0b100, WorldSection.getChildIndex(0, 1, 0));
        assertEquals(0b111, WorldSection.getChildIndex(1, 1, 1));
    }

    @Test
    void setReturnsPreviousValue() {
        WorldSection s = WorldSection._createRawUntrackedUnsafeSection(0, 0, 0, 0);
        long prev = s.set(5, 6, 7, 0xCAFEBABEL);
        assertEquals(0L, prev);
        long prev2 = s.set(5, 6, 7, 0xDEADBEEFL);
        assertEquals(0xCAFEBABEL, prev2);
    }

    @Test
    void setStoresAtCorrectIndex() {
        WorldSection s = WorldSection._createRawUntrackedUnsafeSection(0, 0, 0, 0);
        s.set(3, 7, 11, 0xABCDL);
        long[] raw = s._unsafeGetRawDataArray();
        assertEquals(0xABCDL, raw[WorldSection.getIndex(3, 7, 11)]);
    }

    @Test
    void addNonEmptyBlockCountAccumulates() {
        WorldSection s = WorldSection._createRawUntrackedUnsafeSection(0, 0, 0, 0);
        assertEquals(0, s.getNonEmptyBlockCount());
        assertEquals(5, s.addNonEmptyBlockCount(5));
        assertEquals(12, s.addNonEmptyBlockCount(7));
        assertEquals(2, s.addNonEmptyBlockCount(-10));
    }

    @Test
    void updateLvl0StateReflectsBlockCount() {
        WorldSection s = WorldSection._createRawUntrackedUnsafeSection(0, 0, 0, 0);
        s.updateLvl0State();
        assertEquals((byte) 0, s.getNonEmptyChildren());

        s.addNonEmptyBlockCount(1);
        s.updateLvl0State();
        assertEquals((byte) 0xFF, s.getNonEmptyChildren());

        s.addNonEmptyBlockCount(-1);
        s.updateLvl0State();
        assertEquals((byte) 0, s.getNonEmptyChildren());
    }

    @Test
    void copyDataReturnsIndependentArray() {
        WorldSection s = WorldSection._createRawUntrackedUnsafeSection(0, 0, 0, 0);
        s.set(1, 2, 3, 0xFEEDL);
        long[] copy = s.copyData();
        assertEquals(0xFEEDL, copy[WorldSection.getIndex(1, 2, 3)]);
        copy[0] = 0xFFFFL;
        assertEquals(0L, s._unsafeGetRawDataArray()[0], "modifying copy must not touch section");
    }

    @Test
    void copyDataToWritesAtOffset() {
        WorldSection s = WorldSection._createRawUntrackedUnsafeSection(0, 0, 0, 0);
        s.set(0, 0, 0, 0x1111L);
        long[] dst = new long[WorldSection.SECTION_VOLUME * 2];
        s.copyDataTo(dst, WorldSection.SECTION_VOLUME);
        assertEquals(0x1111L, dst[WorldSection.SECTION_VOLUME]);
        assertEquals(0L, dst[0]);
    }

    @Test
    void hashCodeDependsOnAllCoords() {
        WorldSection a = WorldSection._createRawUntrackedUnsafeSection(1, 2, 3, 4);
        WorldSection b = WorldSection._createRawUntrackedUnsafeSection(1, 2, 3, 4);
        WorldSection c = WorldSection._createRawUntrackedUnsafeSection(1, 2, 3, 5);
        assertEquals(a.hashCode(), b.hashCode());
        assertTrue(a.hashCode() != c.hashCode());
    }

    @Test
    void freshSectionIsLoadedWithZeroRefs() {
        WorldSection s = WorldSection._createRawUntrackedUnsafeSection(0, 0, 0, 0);
        assertEquals(0, s.getRefCount());
        assertTrue(!s.isFreed());
    }

    @Test
    void acquireAndReleaseBalance() {
        WorldSection s = WorldSection._createRawUntrackedUnsafeSection(0, 0, 0, 0);
        assertEquals(1, s.acquire());
        assertEquals(2, s.acquire());
        assertEquals(4, s.acquire(2));
        // release without unload to avoid the untracked-section self-free path.
        // Final release to 0 triggers freeing — test that separately.
        for (int i = 0; i < 3; i++) {
            s.release(false);
        }
        assertEquals(1, s.getRefCount());
    }

    @Test
    void releaseToZeroOnUntrackedSectionFrees() {
        WorldSection s = WorldSection._createRawUntrackedUnsafeSection(0, 0, 0, 0);
        s.acquire();
        s.release(); // drops to 0 → untracked path frees
        assertTrue(s.isFreed());
    }

    @Test
    void tryAcquireFailsOnFreedSection() {
        WorldSection s = WorldSection._createRawUntrackedUnsafeSection(0, 0, 0, 0);
        s.acquire();
        s.release(); // now freed (untracked path)
        assertTrue(!s.tryAcquire());
    }

    @Test
    void tryAcquireIncrementsRefsWhenLoaded() {
        WorldSection s = WorldSection._createRawUntrackedUnsafeSection(0, 0, 0, 0);
        assertTrue(s.tryAcquire());
        assertEquals(1, s.getRefCount());
        s.release(false);
    }

    @Test
    void dirtyFlagToggles() {
        WorldSection s = WorldSection._createRawUntrackedUnsafeSection(0, 0, 0, 0);
        assertTrue(!s.setNotDirty(), "fresh section should not be dirty");
        s.markDirty();
        assertTrue(s.setNotDirty(), "markDirty should be observed");
        assertTrue(!s.setNotDirty(), "second setNotDirty clears the flag");
    }

    @Test
    void inSaveQueueFlagExchange() {
        WorldSection s = WorldSection._createRawUntrackedUnsafeSection(0, 0, 0, 0);
        // false → true must succeed on fresh section.
        assertTrue(s.exchangeIsInSaveQueue(true));
        // true → true must fail (already true, comparand is !state = false).
        assertTrue(!s.exchangeIsInSaveQueue(true));
        // true → false succeeds.
        assertTrue(s.exchangeIsInSaveQueue(false));
    }

    @Test
    void updateEmptyChildStateBitPerChild() {
        WorldSection parent = WorldSection._createRawUntrackedUnsafeSection(1, 0, 0, 0);
        // Child at (1,1,1) → bit index getChildIndex(1,1,1) = 0b111 = 7.
        WorldSection child = WorldSection._createRawUntrackedUnsafeSection(0, 1, 1, 1);
        child.addNonEmptyBlockCount(1);
        child.updateLvl0State(); // child.nonEmptyChildren = 0xFF
        int result = parent.updateEmptyChildState(child);
        assertEquals(2, result, "nothing → something is a major state change");
        assertEquals((byte) (1 << 7), parent.getNonEmptyChildren());

        // Second update with same non-empty state = no change.
        result = parent.updateEmptyChildState(child);
        assertEquals(0, result);

        // Child becomes empty → something → remove bit, but parent still non-zero? no, bit 7 was the only one.
        child.addNonEmptyBlockCount(-1);
        child.updateLvl0State();
        result = parent.updateEmptyChildState(child);
        assertEquals(2, result, "something → nothing is also a major state change");
        assertEquals((byte) 0, parent.getNonEmptyChildren());
    }

    @Test
    void updateEmptyChildStateMinorChange() {
        // Two children non-empty, then one becomes empty → parent stays non-zero → minor change (return 1).
        WorldSection parent = WorldSection._createRawUntrackedUnsafeSection(1, 0, 0, 0);
        WorldSection a = WorldSection._createRawUntrackedUnsafeSection(0, 0, 0, 0);
        WorldSection b = WorldSection._createRawUntrackedUnsafeSection(0, 1, 0, 0);
        a.addNonEmptyBlockCount(1); a.updateLvl0State();
        b.addNonEmptyBlockCount(1); b.updateLvl0State();
        parent.updateEmptyChildState(a);
        parent.updateEmptyChildState(b);
        // Now both bits set. Clear one.
        a.addNonEmptyBlockCount(-1);
        a.updateLvl0State();
        int result = parent.updateEmptyChildState(a);
        assertEquals(1, result, "one bit cleared while another remains = minor change");
        assertTrue(parent.getNonEmptyChildren() != 0);
    }
}
