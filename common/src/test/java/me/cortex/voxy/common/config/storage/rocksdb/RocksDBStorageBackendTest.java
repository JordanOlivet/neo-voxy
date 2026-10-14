package me.cortex.voxy.common.config.storage.rocksdb;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.UnsafeUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RocksDBStorageBackendTest {

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
    void setAndGetRoundTrip(@TempDir Path tempDir) {
        RocksDBStorageBackend backend = new RocksDBStorageBackend(tempDir.toString());
        try {
            byte[] payload = "rocksdb round-trip".getBytes();
            MemoryBuffer in = fromBytes(payload);
            backend.setSectionData(123L, in);

            MemoryBuffer scratch = new MemoryBuffer(1024);
            MemoryBuffer got = backend.getSectionData(123L, scratch);
            assertEquals(payload.length, got.size);
            assertArrayEquals(payload, toBytes(got));

            in.free();
            got.free();
        } finally {
            backend.close();
        }
    }

    @Test
    void getMissingReturnsNull(@TempDir Path tempDir) {
        RocksDBStorageBackend backend = new RocksDBStorageBackend(tempDir.toString());
        try {
            MemoryBuffer scratch = new MemoryBuffer(1024);
            assertNull(backend.getSectionData(999L, scratch));
            scratch.free();
        } finally {
            backend.close();
        }
    }

    @Test
    void deleteRemovesEntry(@TempDir Path tempDir) {
        RocksDBStorageBackend backend = new RocksDBStorageBackend(tempDir.toString());
        try {
            MemoryBuffer in = fromBytes(new byte[] { 1, 2, 3 });
            backend.setSectionData(7L, in);
            backend.deleteSectionData(7L);

            MemoryBuffer scratch = new MemoryBuffer(1024);
            assertNull(backend.getSectionData(7L, scratch));

            in.free();
            scratch.free();
        } finally {
            backend.close();
        }
    }

    @Test
    void iterateVisitsAllKeys(@TempDir Path tempDir) {
        RocksDBStorageBackend backend = new RocksDBStorageBackend(tempDir.toString());
        try {
            long[] keys = { 1L, 42L, -1L, Long.MAX_VALUE, Long.MIN_VALUE, 0L, 1234567890L };
            MemoryBuffer in = fromBytes(new byte[] { 0xD, 0xE, 0xA, 0xD });
            for (long k : keys) {
                backend.setSectionData(k, in);
            }
            backend.flush();

            LongOpenHashSet seen = new LongOpenHashSet();
            backend.iterateStoredSectionPositions(seen::add);
            assertEquals(keys.length, seen.size());
            for (long k : keys) {
                assertTrue(seen.contains(k), "missing key " + k);
            }

            in.free();
        } finally {
            backend.close();
        }
    }

    @Test
    void idMappingRoundTrip(@TempDir Path tempDir) {
        RocksDBStorageBackend backend = new RocksDBStorageBackend(tempDir.toString());
        try {
            byte[] payloadA = "mapping-A".getBytes();
            byte[] payloadB = new byte[] { 0, 1, 2, 3, 4, 5 };
            backend.putIdMapping(10, ByteBuffer.wrap(payloadA));
            backend.putIdMapping(20, ByteBuffer.wrap(payloadB));

            var out = backend.getIdMappingsData();
            assertEquals(2, out.size());
            assertArrayEquals(payloadA, out.get(10));
            assertArrayEquals(payloadB, out.get(20));
        } finally {
            backend.close();
        }
    }

    @Test
    void persistsAcrossReopen(@TempDir Path tempDir) {
        String path = tempDir.toString();
        byte[] payload = "persists".getBytes();

        RocksDBStorageBackend first = new RocksDBStorageBackend(path);
        try {
            MemoryBuffer in = fromBytes(payload);
            first.setSectionData(7L, in);
            first.flush();
            in.free();
        } finally {
            first.close();
        }

        RocksDBStorageBackend second = new RocksDBStorageBackend(path);
        try {
            MemoryBuffer scratch = new MemoryBuffer(1024);
            MemoryBuffer got = second.getSectionData(7L, scratch);
            assertArrayEquals(payload, toBytes(got));
            got.free();
        } finally {
            second.close();
        }
    }
}
