package me.cortex.voxy.common.config.compressors;

import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.UnsafeUtil;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZSTDCompressorTest {

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
    void roundTripSmallPayload() {
        ZSTDCompressor c = new ZSTDCompressor(3);
        byte[] payload = "voxy zstd round-trip test payload".getBytes();
        MemoryBuffer in = fromBytes(payload);

        MemoryBuffer compressed = c.compress(in);
        assertNotNull(compressed);

        MemoryBuffer decompressed = c.decompress(compressed);
        assertNotNull(decompressed);
        assertEquals(payload.length, decompressed.size);
        assertArrayEquals(payload, toBytes(decompressed));

        in.free();
        compressed.free();
    }

    @Test
    void roundTripHighlyCompressible() {
        ZSTDCompressor c = new ZSTDCompressor(3);
        byte[] payload = new byte[8192];
        MemoryBuffer in = fromBytes(payload);

        MemoryBuffer compressed = c.compress(in);
        assertTrue(compressed.size < payload.length,
                "all-zero payload should compress smaller than original (got " + compressed.size + ")");

        MemoryBuffer decompressed = c.decompress(compressed);
        assertNotNull(decompressed);
        assertArrayEquals(payload, toBytes(decompressed));

        in.free();
        compressed.free();
    }

    @Test
    void roundTripRandomPayload() {
        ZSTDCompressor c = new ZSTDCompressor(3);
        byte[] payload = new byte[16384];
        new Random(0xBADCAFEL).nextBytes(payload);
        MemoryBuffer in = fromBytes(payload);

        MemoryBuffer compressed = c.compress(in);
        MemoryBuffer decompressed = c.decompress(compressed);

        assertNotNull(decompressed);
        assertArrayEquals(payload, toBytes(decompressed));

        in.free();
        compressed.free();
    }

    @Test
    void roundTripAtHighCompressionLevel() {
        ZSTDCompressor c = new ZSTDCompressor(19);
        byte[] payload = new byte[4096];
        new Random(42).nextBytes(payload);
        MemoryBuffer in = fromBytes(payload);

        MemoryBuffer compressed = c.compress(in);
        MemoryBuffer decompressed = c.decompress(compressed);

        assertNotNull(decompressed);
        assertArrayEquals(payload, toBytes(decompressed));

        in.free();
        compressed.free();
    }
}
