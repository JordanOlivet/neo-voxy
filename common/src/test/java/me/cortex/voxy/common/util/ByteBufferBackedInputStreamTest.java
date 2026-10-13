package me.cortex.voxy.common.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ByteBufferBackedInputStreamTest {

    @Test
    void readsSingleBytesUnsigned() throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(new byte[] { 0x01, (byte) 0xFF, 0x7F });
        ByteBufferBackedInputStream in = new ByteBufferBackedInputStream(buf);
        assertEquals(0x01, in.read());
        assertEquals(0xFF, in.read());
        assertEquals(0x7F, in.read());
        assertEquals(-1, in.read(), "EOF returns -1");
    }

    @Test
    void readsChunk() throws IOException {
        byte[] src = { 1, 2, 3, 4, 5 };
        ByteBufferBackedInputStream in = new ByteBufferBackedInputStream(ByteBuffer.wrap(src));
        byte[] dst = new byte[5];
        assertEquals(5, in.read(dst, 0, 5));
        assertArrayEquals(src, dst);
        assertEquals(-1, in.read(dst, 0, 5));
    }

    @Test
    void readsPartial() throws IOException {
        byte[] src = { 1, 2, 3 };
        ByteBufferBackedInputStream in = new ByteBufferBackedInputStream(ByteBuffer.wrap(src));
        byte[] dst = new byte[10];
        assertEquals(3, in.read(dst, 2, 10));
        assertEquals(1, dst[2]);
        assertEquals(2, dst[3]);
        assertEquals(3, dst[4]);
    }
}
