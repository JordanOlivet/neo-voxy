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
}
