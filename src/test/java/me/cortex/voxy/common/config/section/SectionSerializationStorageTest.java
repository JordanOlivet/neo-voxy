package me.cortex.voxy.common.config.section;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.common.config.storage.inmemory.MemoryStorageBackend;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.UnsafeUtil;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SectionSerializationStorageTest {

    private static long encodeState(int blockId) {
        return ((long) (blockId & ((1 << 20) - 1))) << 27;
    }

    @Test
    void saveThenLoadRoundTripsData() {
        MemoryStorageBackend backend = new MemoryStorageBackend();
        SectionSerializationStorage storage = new SectionSerializationStorage(backend);

        WorldSection in = WorldSection._createRawUntrackedUnsafeSection(0, 1, 2, 3);
        in.set(5, 6, 7, encodeState(123));
        in.set(8, 9, 10, encodeState(456));
        in._unsafeSetNonEmptyChildren((byte) 0xCD);

        storage.saveSection(in);

        WorldSection out = WorldSection._createRawUntrackedUnsafeSection(0, 1, 2, 3);
        int rc = storage.loadSection(out);
        assertEquals(0, rc, "successful load returns 0");
        assertArrayEquals(in._unsafeGetRawDataArray(), out._unsafeGetRawDataArray());
        assertEquals((byte) 0xCD, out.getNonEmptyChildren());

        backend.close();
    }

    @Test
    void loadMissingSectionReturnsOne() {
        MemoryStorageBackend backend = new MemoryStorageBackend();
        SectionSerializationStorage storage = new SectionSerializationStorage(backend);

        WorldSection out = WorldSection._createRawUntrackedUnsafeSection(0, 0, 0, 0);
        int rc = storage.loadSection(out);
        assertEquals(1, rc, "missing key returns 1");

        backend.close();
    }

    @Test
    void loadCorruptDataReturnsMinusOneAndDeletes() {
        MemoryStorageBackend backend = new MemoryStorageBackend();
        SectionSerializationStorage storage = new SectionSerializationStorage(backend);

        // Inject garbage at the section's key.
        WorldSection victim = WorldSection._createRawUntrackedUnsafeSection(0, 0, 0, 0);
        MemoryBuffer junk = new MemoryBuffer(64);
        for (int i = 0; i < 64; i++) UnsafeUtil.memPutByte(junk.address + i, (byte) 0xAB);
        backend.setSectionData(victim.key, junk);
        junk.free();

        // Pre-fill destination so we can verify it gets reset to AIR.
        victim.set(0, 0, 0, encodeState(999));
        int rc = storage.loadSection(victim);
        assertEquals(-1, rc, "corrupt payload returns -1");
        for (long v : victim._unsafeGetRawDataArray()) {
            assertEquals(Mapper.AIR, v, "section must be wiped to AIR after failed load");
        }

        // Backend must have removed the corrupt blob.
        MemoryBuffer scratch = new MemoryBuffer(1024);
        assertNull(backend.getSectionData(victim.key, scratch),
                "corrupt entry must be deleted from backend");
        scratch.free();

        backend.close();
    }

    @Test
    void iterateStoredSectionPositionsDelegates() {
        MemoryStorageBackend backend = new MemoryStorageBackend();
        SectionSerializationStorage storage = new SectionSerializationStorage(backend);

        WorldSection a = WorldSection._createRawUntrackedUnsafeSection(0, 1, 0, 0);
        WorldSection b = WorldSection._createRawUntrackedUnsafeSection(0, 0, 0, 1);
        storage.saveSection(a);
        storage.saveSection(b);

        LongOpenHashSet seen = new LongOpenHashSet();
        storage.iterateStoredSectionPositions(seen::add);
        assertEquals(2, seen.size());
        assertEquals(true, seen.contains(a.key));
        assertEquals(true, seen.contains(b.key));

        backend.close();
    }
}
