package me.cortex.voxy.common.world;

import me.cortex.voxy.common.util.MemoryBuffer;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaveLoadRoundTripTest {

    private static long encodeState(int blockId) {
        return ((long) (blockId & ((1 << 20) - 1))) << 27;
    }

    private static void fillUniform(WorldSection section, long value) {
        for (int x = 0; x < 32; x++) {
            for (int y = 0; y < 32; y++) {
                for (int z = 0; z < 32; z++) {
                    section.set(x, y, z, value);
                }
            }
        }
    }

    @Test
    void roundTripUniformSection() {
        WorldSection in = WorldSection._createRawUntrackedUnsafeSection(2, 3, 4, 5);
        fillUniform(in, encodeState(42));
        in._unsafeSetNonEmptyChildren((byte) 0xAB);

        MemoryBuffer buf = SaveLoadSystem.serialize(in);
        try {
            WorldSection out = WorldSection._createRawUntrackedUnsafeSection(2, 3, 4, 5);
            assertTrue(SaveLoadSystem.deserialize(out, buf));
            assertArrayEquals(in._unsafeGetRawDataArray(), out._unsafeGetRawDataArray());
            assertEquals((byte) 0xAB, out.getNonEmptyChildren());
            assertEquals(32 * 32 * 32, out.getNonEmptyBlockCount());
        } finally {
            buf.free();
        }
    }

    @Test
    void roundTripRandomSection() {
        WorldSection in = WorldSection._createRawUntrackedUnsafeSection(0, 10, -3, 7);
        Random rng = new Random(0xDEADBEEFL);
        long[] data = in._unsafeGetRawDataArray();
        int expectedNonEmpty = 0;
        for (int i = 0; i < data.length; i++) {
            int id = rng.nextInt(16);
            data[i] = encodeState(id);
            if (id != 0) expectedNonEmpty++;
        }

        MemoryBuffer buf = SaveLoadSystem.serialize(in);
        try {
            WorldSection out = WorldSection._createRawUntrackedUnsafeSection(0, 10, -3, 7);
            assertTrue(SaveLoadSystem.deserialize(out, buf));
            assertArrayEquals(data, out._unsafeGetRawDataArray());
            assertEquals(expectedNonEmpty, out.getNonEmptyBlockCount());
        } finally {
            buf.free();
        }
    }

    @Test
    void deserializeRejectsKeyMismatch() {
        WorldSection in = WorldSection._createRawUntrackedUnsafeSection(1, 0, 0, 0);
        fillUniform(in, encodeState(7));

        MemoryBuffer buf = SaveLoadSystem.serialize(in);
        try {
            WorldSection wrongKey = WorldSection._createRawUntrackedUnsafeSection(1, 1, 0, 0);
            assertEquals(false, SaveLoadSystem.deserialize(wrongKey, buf));
        } finally {
            buf.free();
        }
    }

    @Test
    void roundTripAllAirSection() {
        WorldSection in = WorldSection._createRawUntrackedUnsafeSection(3, 0, 0, 0);
        // data already zero-initialized = all air

        MemoryBuffer buf = SaveLoadSystem.serialize(in);
        try {
            WorldSection out = WorldSection._createRawUntrackedUnsafeSection(3, 0, 0, 0);
            assertTrue(SaveLoadSystem.deserialize(out, buf));
            assertArrayEquals(in._unsafeGetRawDataArray(), out._unsafeGetRawDataArray());
            assertEquals(0, out.getNonEmptyBlockCount());
        } finally {
            buf.free();
        }
    }
}
