package me.cortex.voxy.common.config.compressors;

import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.UnsafeUtil;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LZ4CompressorTest {

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
        LZ4Compressor c = new LZ4Compressor();
        byte[] payload = "voxy lz4 round-trip test payload".getBytes();
        MemoryBuffer in = fromBytes(payload);

        MemoryBuffer compressed = c.compress(in);
        assertNotNull(compressed);
        assertTrue(compressed.size > 4, "compressed output should include 4-byte size header");

        MemoryBuffer decompressed = c.decompress(compressed);
        assertNotNull(decompressed);
        assertEquals(payload.length, decompressed.size);
        assertArrayEquals(payload, toBytes(decompressed));

        in.free();
        compressed.free();
    }

    @Test
    void roundTripHighlyCompressible() {
        LZ4Compressor c = new LZ4Compressor();
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
        LZ4Compressor c = new LZ4Compressor();
        byte[] payload = new byte[16384];
        new Random(0xC0FFEEL).nextBytes(payload);
        MemoryBuffer in = fromBytes(payload);

        MemoryBuffer compressed = c.compress(in);
        MemoryBuffer decompressed = c.decompress(compressed);

        assertNotNull(decompressed);
        assertArrayEquals(payload, toBytes(decompressed));

        in.free();
        compressed.free();
    }

    @Test
    void decompressRejectsTruncatedHeader() {
        LZ4Compressor c = new LZ4Compressor();
        MemoryBuffer tooSmall = new MemoryBuffer(2);
        try {
            assertEquals(null, c.decompress(tooSmall));
        } finally {
            tooSmall.free();
        }
    }

    @Test
    void decompressRejectsNegativeDeclaredSize() {
        LZ4Compressor c = new LZ4Compressor();
        MemoryBuffer bad = new MemoryBuffer(8);
        try {
            UnsafeUtil.memPutInt(bad.address, -1);
            UnsafeUtil.memPutInt(bad.address + 4, 0);
            assertEquals(null, c.decompress(bad));
        } finally {
            bad.free();
        }
    }

    @Test
    void decompressRejectsAbsurdlyLargeDeclaredSize() {
        LZ4Compressor c = new LZ4Compressor();
        MemoryBuffer bad = new MemoryBuffer(8);
        try {
            UnsafeUtil.memPutInt(bad.address, Integer.MAX_VALUE);
            UnsafeUtil.memPutInt(bad.address + 4, 0);
            assertEquals(null, c.decompress(bad));
        } finally {
            bad.free();
        }
    }

    @Test
    void decompressRejectsCorruptBodyAfterValidHeader() {
        LZ4Compressor c = new LZ4Compressor();
        // Valid declared size (small) but body is garbage that won't decode.
        MemoryBuffer bad = new MemoryBuffer(16);
        try {
            UnsafeUtil.memPutInt(bad.address, 64);
            for (int i = 4; i < 16; i++) {
                UnsafeUtil.memPutByte(bad.address + i, (byte) 0xFF);
            }
            assertEquals(null, c.decompress(bad), "corrupt body must be caught and return null");
        } finally {
            bad.free();
        }
    }
}
