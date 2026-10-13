package me.cortex.voxy.common.world;

import me.cortex.voxy.common.util.MemoryBuffer;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaveLoadSystem3Test {

    private static long encodeState(int blockId) {
        return ((long) (blockId & ((1 << 20) - 1))) << 27;
    }

    @Test
    void lin2zZ2linAreInverses() {
        for (int i = 0; i < WorldSection.SECTION_VOLUME; i++) {
            assertEquals(i, SaveLoadSystem3.z2lin(SaveLoadSystem3.lin2z(i)));
        }
    }

    @Test
    void lin2zCoversAllPositions() {
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < WorldSection.SECTION_VOLUME; i++) {
            int morton = SaveLoadSystem3.lin2z(i);
            assertTrue(morton >= 0 && morton < WorldSection.SECTION_VOLUME);
            assertTrue(seen.add(morton), "duplicate morton at i=" + i);
        }
        assertEquals(WorldSection.SECTION_VOLUME, seen.size());
    }

    @Test
    void roundTripUniformLvl0Section() {
        WorldSection in = WorldSection._createRawUntrackedUnsafeSection(0, 5, 6, 7);
        long[] raw = in._unsafeGetRawDataArray();
        long value = encodeState(42);
        for (int i = 0; i < raw.length; i++) raw[i] = value;
        in._unsafeSetNonEmptyChildren((byte) 0xCD);

        MemoryBuffer buf = SaveLoadSystem3.serialize(in);
        try {
            WorldSection out = WorldSection._createRawUntrackedUnsafeSection(0, 5, 6, 7);
            assertTrue(SaveLoadSystem3.deserialize(out, buf));
            assertArrayEquals(raw, out._unsafeGetRawDataArray());
            assertEquals((byte) 0xCD, out.getNonEmptyChildren());
            assertEquals(WorldSection.SECTION_VOLUME, out.getNonEmptyBlockCount());
        } finally {
            buf.free();
        }
    }

    @Test
    void roundTripRandomLvl0Section() {
        WorldSection in = WorldSection._createRawUntrackedUnsafeSection(0, -1, 2, -3);
        long[] raw = in._unsafeGetRawDataArray();
        Random rng = new Random(0xCAFEL);
        int expectedNonEmpty = 0;
        for (int i = 0; i < raw.length; i++) {
            int id = rng.nextInt(8);
            raw[i] = encodeState(id);
            if (id != 0) expectedNonEmpty++;
        }

        MemoryBuffer buf = SaveLoadSystem3.serialize(in);
        try {
            WorldSection out = WorldSection._createRawUntrackedUnsafeSection(0, -1, 2, -3);
            assertTrue(SaveLoadSystem3.deserialize(out, buf));
            assertArrayEquals(raw, out._unsafeGetRawDataArray());
            assertEquals(expectedNonEmpty, out.getNonEmptyBlockCount());
        } finally {
            buf.free();
        }
    }

    @Test
    void roundTripHigherLevelSectionSkipsAirCount() {
        WorldSection in = WorldSection._createRawUntrackedUnsafeSection(3, 0, 0, 0);
        long[] raw = in._unsafeGetRawDataArray();
        for (int i = 0; i < raw.length; i++) raw[i] = encodeState(i % 5);

        MemoryBuffer buf = SaveLoadSystem3.serialize(in);
        try {
            WorldSection out = WorldSection._createRawUntrackedUnsafeSection(3, 0, 0, 0);
            assertTrue(SaveLoadSystem3.deserialize(out, buf));
            assertArrayEquals(raw, out._unsafeGetRawDataArray());
            assertEquals(0, out.getNonEmptyBlockCount(), "lvl > 0 skips block counting");
        } finally {
            buf.free();
        }
    }

    @Test
    void deserializeRejectsKeyMismatch() {
        WorldSection in = WorldSection._createRawUntrackedUnsafeSection(1, 0, 0, 0);
        long[] raw = in._unsafeGetRawDataArray();
        for (int i = 0; i < raw.length; i++) raw[i] = encodeState(1);

        MemoryBuffer buf = SaveLoadSystem3.serialize(in);
        try {
            WorldSection wrongKey = WorldSection._createRawUntrackedUnsafeSection(1, 1, 0, 0);
            assertFalse(SaveLoadSystem3.deserialize(wrongKey, buf));
        } finally {
            buf.free();
        }
    }

    @Test
    void roundTripAllAirSection() {
        WorldSection in = WorldSection._createRawUntrackedUnsafeSection(0, 0, 0, 0);
        // data already zeroed = all Mapper.AIR (0)

        MemoryBuffer buf = SaveLoadSystem3.serialize(in);
        try {
            WorldSection out = WorldSection._createRawUntrackedUnsafeSection(0, 0, 0, 0);
            assertTrue(SaveLoadSystem3.deserialize(out, buf));
            assertArrayEquals(in._unsafeGetRawDataArray(), out._unsafeGetRawDataArray());
            assertEquals(0, out.getNonEmptyBlockCount());
        } finally {
            buf.free();
        }
    }
}
