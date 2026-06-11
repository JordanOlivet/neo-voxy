package me.cortex.voxy.common.config.storage.other;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.config.storage.inmemory.MemoryStorageBackend;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.UnsafeUtil;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.function.LongConsumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReadonlyCachingLayerTest {

    /** Wraps a MemoryStorageBackend and counts getSectionData calls. */
    private static final class CountingBackend extends StorageBackend {
        final MemoryStorageBackend inner = new MemoryStorageBackend();
        int getCalls, setCalls, deleteCalls;

        @Override
        public MemoryBuffer getSectionData(long key, MemoryBuffer scratch) {
            this.getCalls++;
            return this.inner.getSectionData(key, scratch);
        }

        @Override
        public void setSectionData(long key, MemoryBuffer data) {
            this.setCalls++;
            this.inner.setSectionData(key, data);
        }

        @Override
        public void deleteSectionData(long key) {
            this.deleteCalls++;
            this.inner.deleteSectionData(key);
        }

        @Override
        public void iterateStoredSectionPositions(LongConsumer consumer) {
            this.inner.iterateStoredSectionPositions(consumer);
        }

        @Override
        public void putIdMapping(int id, ByteBuffer data) {
            this.inner.putIdMapping(id, data);
        }

        @Override
        public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
            return this.inner.getIdMappingsData();
        }

        @Override
        public void flush() {
            this.inner.flush();
        }

        @Override
        public void close() {
            this.inner.close();
        }
    }

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

    @Test
    void missFetchesOnMissAndPopulatesCache() {
        CountingBackend cache = new CountingBackend();
        CountingBackend onMiss = new CountingBackend();

        byte[] payload = { 7, 8, 9 };
        MemoryBuffer src = fromBytes(payload);
        onMiss.setSectionData(100L, src);
        src.free();
        onMiss.setCalls = 0;

        ReadonlyCachingLayer layer = new ReadonlyCachingLayer(cache, onMiss);
        MemoryBuffer scratch = new MemoryBuffer(32);
        MemoryBuffer out = layer.getSectionData(100L, scratch);
        assertArrayEquals(payload, toBytes(out));
        assertEquals(1, cache.getCalls);
        assertEquals(1, onMiss.getCalls);
        assertEquals(1, cache.setCalls, "miss must populate cache");
        out.free();
    }

    @Test
    void hitDoesNotQueryOnMiss() {
        CountingBackend cache = new CountingBackend();
        CountingBackend onMiss = new CountingBackend();

        byte[] payload = { 1, 2, 3 };
        MemoryBuffer src = fromBytes(payload);
        cache.setSectionData(5L, src);
        src.free();
        cache.setCalls = 0;

        ReadonlyCachingLayer layer = new ReadonlyCachingLayer(cache, onMiss);
        MemoryBuffer scratch = new MemoryBuffer(16);
        MemoryBuffer out = layer.getSectionData(5L, scratch);
        assertArrayEquals(payload, toBytes(out));
        assertEquals(1, cache.getCalls);
        assertEquals(0, onMiss.getCalls, "hit must not hit onMiss");
        out.free();
    }

    @Test
    void doubleMissReturnsNull() {
        CountingBackend cache = new CountingBackend();
        CountingBackend onMiss = new CountingBackend();

        ReadonlyCachingLayer layer = new ReadonlyCachingLayer(cache, onMiss);
        MemoryBuffer scratch = new MemoryBuffer(16);
        assertNull(layer.getSectionData(404L, scratch));
    }

    @Test
    void setGoesToCacheOnly() {
        CountingBackend cache = new CountingBackend();
        CountingBackend onMiss = new CountingBackend();
        ReadonlyCachingLayer layer = new ReadonlyCachingLayer(cache, onMiss);

        MemoryBuffer src = fromBytes(new byte[] { 1, 2, 3 });
        layer.setSectionData(1L, src);
        src.free();
        assertEquals(1, cache.setCalls);
        assertEquals(0, onMiss.setCalls);
    }

    @Test
    void deleteGoesToCacheOnly() {
        CountingBackend cache = new CountingBackend();
        CountingBackend onMiss = new CountingBackend();
        ReadonlyCachingLayer layer = new ReadonlyCachingLayer(cache, onMiss);

        layer.deleteSectionData(1L);
        assertEquals(1, cache.deleteCalls);
        assertEquals(0, onMiss.deleteCalls);
    }

    @Test
    void iterateNotImplemented() {
        ReadonlyCachingLayer layer = new ReadonlyCachingLayer(new CountingBackend(), new CountingBackend());
        assertThrows(IllegalStateException.class, () -> layer.iterateStoredSectionPositions(k -> {}));
    }
}
