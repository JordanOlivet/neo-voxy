package me.cortex.voxy.common.util;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryBufferTest {

    @Test
    void allocatedBufferHasRequestedSize() {
        MemoryBuffer buf = new MemoryBuffer(128);
        assertEquals(128, buf.size);
        assertNotEquals(0, buf.address);
        buf.free();
    }

    @Test
    void cpyFromThenCpyToRoundTrip() {
        MemoryBuffer src = new MemoryBuffer(4);
        MemoryBuffer dst = new MemoryBuffer(4);

        byte[] bytes = { 10, 20, 30, 40 };
        UnsafeUtil.memcpy(bytes, src.address);
        src.cpyTo(dst.address);

        byte[] out = new byte[4];
        UnsafeUtil.memcpy(dst.address, out);
        assertArrayEquals(bytes, out);

        src.free();
        dst.free();
    }

    @Test
    void copyProducesIndependentBuffer() {
        MemoryBuffer a = new MemoryBuffer(4);
        UnsafeUtil.memcpy(new byte[] { 1, 2, 3, 4 }, a.address);

        MemoryBuffer b = a.copy();
        assertNotEquals(a.address, b.address);
        assertEquals(a.size, b.size);

        byte[] out = new byte[4];
        UnsafeUtil.memcpy(b.address, out);
        assertArrayEquals(new byte[] { 1, 2, 3, 4 }, out);

        a.free();
        b.free();
    }

    @Test
    void subSizeShrinksAndFreesOriginal() {
        MemoryBuffer a = new MemoryBuffer(100);
        long origAddress = a.address;
        MemoryBuffer sub = a.subSize(20);
        assertEquals(20, sub.size);
        assertEquals(origAddress, sub.address, "subSize keeps same memory address");
        assertTrue(a.isFreed(), "original buffer object must be freed after subSize");
        sub.free();
    }

    @Test
    void subSizeRejectsLargerThanSize() {
        MemoryBuffer a = new MemoryBuffer(10);
        assertThrows(IllegalArgumentException.class, () -> a.subSize(20));
        a.free();
    }

    @Test
    void subSizeRejectsZeroOrNegative() {
        MemoryBuffer a = new MemoryBuffer(10);
        assertThrows(IllegalArgumentException.class, () -> a.subSize(0));
        assertThrows(IllegalArgumentException.class, () -> a.subSize(-1));
        a.free();
    }

    @Test
    void zeroSetsBytesToZero() {
        MemoryBuffer a = new MemoryBuffer(4);
        UnsafeUtil.memcpy(new byte[] { 1, 2, 3, 4 }, a.address);
        a.zero();
        byte[] out = new byte[4];
        UnsafeUtil.memcpy(a.address, out);
        assertArrayEquals(new byte[] { 0, 0, 0, 0 }, out);
        a.free();
    }

    @Test
    void asByteBufferReflectsNativeMemory() {
        MemoryBuffer a = new MemoryBuffer(4);
        UnsafeUtil.memcpy(new byte[] { 9, 8, 7, 6 }, a.address);
        ByteBuffer view = a.asByteBuffer();
        assertEquals(4, view.remaining());
        assertEquals(9, view.get(0));
        assertEquals(8, view.get(1));
        assertEquals(7, view.get(2));
        assertEquals(6, view.get(3));
        a.free();
    }

    @Test
    void doubleFreeThrows() {
        MemoryBuffer a = new MemoryBuffer(8);
        a.free();
        assertThrows(IllegalStateException.class, a::free);
    }

    @Test
    void createUntrackedUnfreeableReferenceDoesNotOwnMemory() {
        MemoryBuffer a = new MemoryBuffer(16);
        MemoryBuffer ref = a.createUntrackedUnfreeableReference();
        assertEquals(a.address, ref.address);
        assertEquals(a.size, ref.size);
        assertThrows(IllegalArgumentException.class, ref::free);
        a.free();
    }
}
