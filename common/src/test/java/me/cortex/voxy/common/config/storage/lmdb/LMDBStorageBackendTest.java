package me.cortex.voxy.common.config.storage.lmdb;

import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.UnsafeUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LMDBStorageBackendTest {

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

    private static String dbFile(Path tempDir, String name) {
        return new File(tempDir.toFile(), name).getAbsolutePath();
    }

    @Test
    void setAndGetRoundTrip(@TempDir Path tempDir) {
        LMDBStorageBackend backend = new LMDBStorageBackend(dbFile(tempDir, "set-get.mdb"));
        try {
            byte[] payload = "lmdb round-trip".getBytes();
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
        LMDBStorageBackend backend = new LMDBStorageBackend(dbFile(tempDir, "missing.mdb"));
        try {
            MemoryBuffer scratch = new MemoryBuffer(1024);
            assertNull(backend.getSectionData(999L, scratch));
            scratch.free();
        } finally {
            backend.close();
        }
    }

    @Test
    void overwriteReplacesValue(@TempDir Path tempDir) {
        LMDBStorageBackend backend = new LMDBStorageBackend(dbFile(tempDir, "overwrite.mdb"));
        try {
            MemoryBuffer first = fromBytes("first".getBytes());
            MemoryBuffer second = fromBytes("second-longer-payload".getBytes());
            backend.setSectionData(1L, first);
            backend.setSectionData(1L, second);

            MemoryBuffer scratch = new MemoryBuffer(1024);
            MemoryBuffer got = backend.getSectionData(1L, scratch);
            assertArrayEquals("second-longer-payload".getBytes(), toBytes(got));

            first.free();
            second.free();
            got.free();
        } finally {
            backend.close();
        }
    }

    @Test
    void deleteRemovesEntry(@TempDir Path tempDir) {
        LMDBStorageBackend backend = new LMDBStorageBackend(dbFile(tempDir, "delete.mdb"));
        try {
            MemoryBuffer in = fromBytes(new byte[] { 7, 8, 9 });
            backend.setSectionData(42L, in);
            backend.deleteSectionData(42L);

            MemoryBuffer scratch = new MemoryBuffer(1024);
            assertNull(backend.getSectionData(42L, scratch));

            in.free();
            scratch.free();
        } finally {
            backend.close();
        }
    }

    @Test
    void idMappingRoundTrip(@TempDir Path tempDir) {
        LMDBStorageBackend backend = new LMDBStorageBackend(dbFile(tempDir, "id-map.mdb"));
        try {
            byte[] payloadA = "mapping-A".getBytes();
            byte[] payloadB = new byte[] { 0, 1, 2, 3, 4 };

            ByteBuffer bufA = ByteBuffer.allocateDirect(payloadA.length);
            bufA.put(payloadA).flip();
            ByteBuffer bufB = ByteBuffer.allocateDirect(payloadB.length);
            bufB.put(payloadB).flip();

            backend.putIdMapping(10, bufA);
            backend.putIdMapping(20, bufB);

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
        String file = dbFile(tempDir, "persist.mdb");
        byte[] payload = "persists-across-close".getBytes();

        LMDBStorageBackend first = new LMDBStorageBackend(file);
        try {
            MemoryBuffer in = fromBytes(payload);
            first.setSectionData(7L, in);
            first.flush();
            in.free();
        } finally {
            first.close();
        }

        LMDBStorageBackend second = new LMDBStorageBackend(file);
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
