package me.cortex.voxy.common.config.storage.other;

import me.cortex.voxy.common.config.compressors.StorageCompressor;
import me.cortex.voxy.common.config.storage.inmemory.MemoryStorageBackend;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.UnsafeUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompressionStorageAdaptorTest {

    /** Counting compressor that prefixes a magic byte on compress and strips it on decompress. */
    private static final class CountingCompressor implements StorageCompressor {
        int compressed, decompressed, closed;

        @Override
        public MemoryBuffer compress(MemoryBuffer src) {
            this.compressed++;
            MemoryBuffer out = new MemoryBuffer(src.size + 1);
            UnsafeUtil.memPutByte(out.address, (byte) 0x5A);
            UnsafeUtil.memcpy(src.address, out.address + 1, src.size);
            return out;
        }

        @Override
        public MemoryBuffer decompress(MemoryBuffer src) {
            this.decompressed++;
            byte magic = UnsafeUtil.memGetByte(src.address);
            if (magic != 0x5A) throw new AssertionError("corrupt magic byte");
            MemoryBuffer out = new MemoryBuffer(src.size - 1);
            UnsafeUtil.memcpy(src.address + 1, out.address, src.size - 1);
            return out;
        }

        @Override
        public void close() {
            this.closed++;
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
    void setCompressesGetDecompresses() {
        CountingCompressor c = new CountingCompressor();
        CompressionStorageAdaptor adaptor = new CompressionStorageAdaptor(c, new MemoryStorageBackend());

        byte[] payload = { 1, 2, 3, 4, 5 };
        MemoryBuffer in = fromBytes(payload);
        adaptor.setSectionData(42L, in);
        in.free();
        assertEquals(1, c.compressed);

        MemoryBuffer scratch = new MemoryBuffer(64);
        MemoryBuffer out = adaptor.getSectionData(42L, scratch);
        assertArrayEquals(payload, toBytes(out));
        assertEquals(1, c.decompressed);
        out.free();

        adaptor.close();
        assertEquals(1, c.closed);
    }

    @Test
    void getMissingReturnsNullWithoutDecompressing() {
        CountingCompressor c = new CountingCompressor();
        CompressionStorageAdaptor adaptor = new CompressionStorageAdaptor(c, new MemoryStorageBackend());

        MemoryBuffer scratch = new MemoryBuffer(64);
        assertNull(adaptor.getSectionData(9999L, scratch));
        assertEquals(0, c.decompressed);
        adaptor.close();
    }

    @Test
    void closeCascadesToCompressor() {
        CountingCompressor c = new CountingCompressor();
        CompressionStorageAdaptor adaptor = new CompressionStorageAdaptor(c, new MemoryStorageBackend());
        adaptor.close();
        assertEquals(1, c.closed);
    }

    @Test
    void roundTripPreservesEmptyPayload() {
        CountingCompressor c = new CountingCompressor();
        CompressionStorageAdaptor adaptor = new CompressionStorageAdaptor(c, new MemoryStorageBackend());
        byte[] payload = { 42 };
        MemoryBuffer in = fromBytes(payload);
        adaptor.setSectionData(1L, in);
        in.free();

        MemoryBuffer scratch = new MemoryBuffer(16);
        MemoryBuffer out = adaptor.getSectionData(1L, scratch);
        assertArrayEquals(payload, toBytes(out));
        assertTrue(c.compressed >= 1);
        out.free();
        adaptor.close();
    }
}
