package me.cortex.voxy.common.config.storage.inmemory;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.UnsafeUtil;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryStorageBackendTest {

    private static MemoryBuffer fromBytes(byte[] src) {
        MemoryBuffer buf = new MemoryBuffer(src.length);
        UnsafeUtil.memcpy(src, buf.address);
        return buf;
    }

    private static byte[] toBytes(MemoryBuffer buf) {
        byte[] out = new byte[(int) buf.size];
        UnsafeUtil.memcpy(buf.address, out);
        return out;
    }

    @Test
    void setAndGetSection() {
        MemoryStorageBackend backend = new MemoryStorageBackend();
        byte[] payload = "hello voxy".getBytes();
        MemoryBuffer in = fromBytes(payload);
        backend.setSectionData(42L, in);

        MemoryBuffer scratch = new MemoryBuffer(1024);
        MemoryBuffer got = backend.getSectionData(42L, scratch);
        assertEquals(payload.length, got.size);
        assertArrayEquals(payload, toBytes(got));

        in.free();
        backend.close();
    }

    @Test
    void getMissingReturnsNull() {
        MemoryStorageBackend backend = new MemoryStorageBackend();
        MemoryBuffer scratch = new MemoryBuffer(1024);
        assertNull(backend.getSectionData(999L, scratch));
        scratch.free();
        backend.close();
    }

    @Test
    void overwriteReplacesValue() {
        MemoryStorageBackend backend = new MemoryStorageBackend();
        MemoryBuffer first = fromBytes("first".getBytes());
        MemoryBuffer second = fromBytes("second-longer".getBytes());
        backend.setSectionData(1L, first);
        backend.setSectionData(1L, second);

        MemoryBuffer scratch = new MemoryBuffer(1024);
        MemoryBuffer got = backend.getSectionData(1L, scratch);
        assertArrayEquals("second-longer".getBytes(), toBytes(got));

        first.free();
        second.free();
        backend.close();
    }

    @Test
    void deleteRemovesEntry() {
        MemoryStorageBackend backend = new MemoryStorageBackend();
        MemoryBuffer in = fromBytes(new byte[] { 1, 2, 3 });
        backend.setSectionData(7L, in);
        backend.deleteSectionData(7L);

        MemoryBuffer scratch = new MemoryBuffer(1024);
        assertNull(backend.getSectionData(7L, scratch));

        in.free();
        scratch.free();
        backend.close();
    }

    @Test
    void iterateVisitsAllKeys() {
        MemoryStorageBackend backend = new MemoryStorageBackend();
        long[] keys = { 1L, 42L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 0L, 1234567890L };
        MemoryBuffer in = fromBytes(new byte[] { 0xD, 0xE, 0xA, 0xD });
        for (long k : keys) {
            backend.setSectionData(k, in);
        }

        LongOpenHashSet seen = new LongOpenHashSet();
        backend.iterateStoredSectionPositions(seen::add);
        assertEquals(keys.length, seen.size());
        for (long k : keys) {
            assertTrue(seen.contains(k), "missing key " + k);
        }

        in.free();
        backend.close();
    }

    @Test
    void idMappingRoundTrip() {
        MemoryStorageBackend backend = new MemoryStorageBackend();
        byte[] mappingA = "mapping-A".getBytes();
        byte[] mappingB = new byte[] { 0, 1, 2, 3, 4, 5 };
        backend.putIdMapping(10, ByteBuffer.wrap(mappingA));
        backend.putIdMapping(20, ByteBuffer.wrap(mappingB));

        var out = backend.getIdMappingsData();
        assertEquals(2, out.size());
        assertArrayEquals(mappingA, out.get(10));
        assertArrayEquals(mappingB, out.get(20));

        backend.close();
    }

    @Test
    void idMappingPutReplaces() {
        MemoryStorageBackend backend = new MemoryStorageBackend();
        byte[] first = "first".getBytes();
        byte[] second = "second".getBytes();
        backend.putIdMapping(5, ByteBuffer.wrap(first));
        backend.putIdMapping(5, ByteBuffer.wrap(second));

        var out = backend.getIdMappingsData();
        assertEquals(1, out.size());
        assertArrayEquals(second, out.get(5));

        backend.close();
    }

    @Test
    void sliceDistributionSeparatesKeys() {
        MemoryStorageBackend backend = new MemoryStorageBackend(4);
        byte[] payload = { 1, 2, 3, 4 };
        MemoryBuffer in = fromBytes(payload);
        for (long k = 0; k < 100; k++) {
            backend.setSectionData(k, in);
        }
        for (long k = 0; k < 100; k++) {
            // subSize consumes the scratch buffer, so allocate fresh per call
            MemoryBuffer scratch = new MemoryBuffer(1024);
            MemoryBuffer got = backend.getSectionData(k, scratch);
            assertEquals(payload.length, got.size, "missing key " + k);
            assertArrayEquals(payload, Arrays.copyOf(toBytes(got), (int) got.size));
            got.free();
        }
        in.free();
        backend.close();
    }
}
