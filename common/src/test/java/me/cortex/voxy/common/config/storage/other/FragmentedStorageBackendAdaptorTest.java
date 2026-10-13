package me.cortex.voxy.common.config.storage.other;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.config.storage.inmemory.MemoryStorageBackend;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.UnsafeUtil;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FragmentedStorageBackendAdaptorTest {

    private static MemoryBuffer fromBytes(byte[] in) {
        MemoryBuffer b = new MemoryBuffer(in.length);
        UnsafeUtil.memcpy(in, b.address);
        return b;
    }

    private static byte[] toBytes(MemoryBuffer b) {
        byte[] out = new byte[(int) b.size];
        UnsafeUtil.memcpy(b.address, out);
        return out;
    }

    private static StorageBackend[] backends(int n) {
        StorageBackend[] arr = new StorageBackend[n];
        for (int i = 0; i < n; i++) arr[i] = new MemoryStorageBackend();
        return arr;
    }

    @Test
    void rejectsNonPowerOfTwoFragmentCount() {
        assertThrows(IllegalArgumentException.class,
                () -> new FragmentedStorageBackendAdaptor(backends(3)));
        assertThrows(IllegalArgumentException.class,
                () -> new FragmentedStorageBackendAdaptor(backends(5)));
    }

    @Test
    void acceptsPowersOfTwo() {
        new FragmentedStorageBackendAdaptor(backends(1)).close();
        new FragmentedStorageBackendAdaptor(backends(2)).close();
        new FragmentedStorageBackendAdaptor(backends(4)).close();
        new FragmentedStorageBackendAdaptor(backends(8)).close();
    }

    @Test
    void setThenGetRoundTrip() {
        FragmentedStorageBackendAdaptor frag = new FragmentedStorageBackendAdaptor(backends(4));
        byte[] payload = { 11, 22, 33 };
        MemoryBuffer in = fromBytes(payload);
        frag.setSectionData(12345L, in);
        in.free();

        MemoryBuffer scratch = new MemoryBuffer(32);
        MemoryBuffer out = frag.getSectionData(12345L, scratch);
        assertNotNull(out);
        assertArrayEquals(payload, toBytes(out));
        out.free();
        frag.close();
    }

    @Test
    void getMissingReturnsNull() {
        FragmentedStorageBackendAdaptor frag = new FragmentedStorageBackendAdaptor(backends(4));
        MemoryBuffer scratch = new MemoryBuffer(32);
        assertNull(frag.getSectionData(999L, scratch));
        frag.close();
    }

    @Test
    void deleteRemovesSection() {
        FragmentedStorageBackendAdaptor frag = new FragmentedStorageBackendAdaptor(backends(2));
        byte[] payload = { 1, 2, 3 };
        MemoryBuffer in = fromBytes(payload);
        frag.setSectionData(7L, in);
        in.free();
        frag.deleteSectionData(7L);
        MemoryBuffer scratch = new MemoryBuffer(16);
        assertNull(frag.getSectionData(7L, scratch));
        frag.close();
    }

    @Test
    void iterateYieldsAllStoredKeysAcrossFragments() {
        FragmentedStorageBackendAdaptor frag = new FragmentedStorageBackendAdaptor(backends(4));
        long[] keys = new long[200];
        for (int i = 0; i < 200; i++) {
            keys[i] = 1000L + i;
            MemoryBuffer in = fromBytes(new byte[] { (byte) i });
            frag.setSectionData(keys[i], in);
            in.free();
        }

        LongOpenHashSet collected = new LongOpenHashSet();
        frag.iterateStoredSectionPositions(collected::add);
        assertEquals(200, collected.size());
        for (long k : keys) assertTrue(collected.contains(k));
        frag.close();
    }

    @Test
    void keysDistributeAcrossFragments() {
        StorageBackend[] raw = backends(4);
        FragmentedStorageBackendAdaptor frag = new FragmentedStorageBackendAdaptor(raw);
        Random rng = new Random(42);
        for (int i = 0; i < 500; i++) {
            MemoryBuffer in = fromBytes(new byte[] { 1 });
            frag.setSectionData(rng.nextLong(), in);
            in.free();
        }
        int nonEmpty = 0;
        for (StorageBackend b : raw) {
            LongOpenHashSet keys = new LongOpenHashSet();
            b.iterateStoredSectionPositions(keys::add);
            if (!keys.isEmpty()) nonEmpty++;
        }
        assertTrue(nonEmpty >= 3, "expected wide distribution, got " + nonEmpty + " non-empty fragments");
        frag.close();
    }

    @Test
    void idMappingsReplicatedAcrossAllFragments() {
        StorageBackend[] raw = backends(4);
        FragmentedStorageBackendAdaptor frag = new FragmentedStorageBackendAdaptor(raw);

        byte[] mapping = { 10, 20, 30 };
        frag.putIdMapping(1, ByteBuffer.wrap(mapping));

        for (StorageBackend b : raw) {
            var m = b.getIdMappingsData();
            assertArrayEquals(mapping, m.get(1));
        }

        var merged = frag.getIdMappingsData();
        assertArrayEquals(mapping, merged.get(1));
        frag.close();
    }
}
