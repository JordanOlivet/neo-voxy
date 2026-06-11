package me.cortex.voxy.common.util;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnsafeUtilTest {

    @Test
    void allocateAndFreeRoundTrip() {
        long addr = UnsafeUtil.allocateMemory(32);
        assertNotEquals(0, addr);
        UnsafeUtil.freeMemory(addr);
    }

    @Test
    void memsetFillsPattern() {
        long addr = UnsafeUtil.allocateMemory(8);
        UnsafeUtil.memset(addr, 0xAB, 8);
        byte[] out = new byte[8];
        UnsafeUtil.memcpy(addr, out);
        for (byte b : out) {
            assertEquals((byte) 0xAB, b);
        }
        UnsafeUtil.freeMemory(addr);
    }

    @Test
    void longReadWriteRoundTrip() {
        long addr = UnsafeUtil.allocateMemory(8);
        UnsafeUtil.memPutLong(addr, 0x0102030405060708L);
        assertEquals(0x0102030405060708L, UnsafeUtil.memGetLong(addr));
        UnsafeUtil.freeMemory(addr);
    }

    @Test
    void intReadWriteRoundTrip() {
        long addr = UnsafeUtil.allocateMemory(4);
        UnsafeUtil.memPutInt(addr, 0x12345678);
        assertEquals(0x12345678, UnsafeUtil.memGetInt(addr));
        UnsafeUtil.freeMemory(addr);
    }

    @Test
    void shortReadWriteRoundTrip() {
        long addr = UnsafeUtil.allocateMemory(2);
        UnsafeUtil.memPutShort(addr, (short) 0x1234);
        assertEquals((short) 0x1234, UnsafeUtil.memGetShort(addr));
        UnsafeUtil.freeMemory(addr);
    }

    @Test
    void byteReadWriteRoundTrip() {
        long addr = UnsafeUtil.allocateMemory(1);
        UnsafeUtil.memPutByte(addr, (byte) 0x5A);
        assertEquals((byte) 0x5A, UnsafeUtil.memGetByte(addr));
        UnsafeUtil.freeMemory(addr);
    }

    @Test
    void memcpyByteArrayToNativeAndBack() {
        long addr = UnsafeUtil.allocateMemory(4);
        byte[] in = { 1, 2, 3, 4 };
        UnsafeUtil.memcpy(in, addr);
        byte[] out = new byte[4];
        UnsafeUtil.memcpy(addr, out);
        assertArrayEquals(in, out);
        UnsafeUtil.freeMemory(addr);
    }

    @Test
    void memcpyWithLengthLimitsCopy() {
        long addr = UnsafeUtil.allocateMemory(8);
        UnsafeUtil.memset(addr, 0, 8);
        byte[] in = { 1, 2, 3, 4, 5, 6, 7, 8 };
        UnsafeUtil.memcpy(in, 3, addr);

        byte[] out = new byte[8];
        UnsafeUtil.memcpy(addr, out);
        assertEquals(1, out[0]);
        assertEquals(2, out[1]);
        assertEquals(3, out[2]);
        assertEquals(0, out[3]);
        UnsafeUtil.freeMemory(addr);
    }

    @Test
    void memcpyWithOffsetWritesIntoDestOffset() {
        long addr = UnsafeUtil.allocateMemory(4);
        byte[] in = { 9, 8, 7, 6 };
        UnsafeUtil.memcpy(in, addr);

        byte[] out = new byte[8];
        UnsafeUtil.memcpy(addr, 4, out, 2);
        assertEquals(0, out[0]);
        assertEquals(0, out[1]);
        assertEquals(9, out[2]);
        assertEquals(8, out[3]);
        assertEquals(7, out[4]);
        assertEquals(6, out[5]);
        UnsafeUtil.freeMemory(addr);
    }

    @Test
    void memcpyNativeToNative() {
        long src = UnsafeUtil.allocateMemory(4);
        long dst = UnsafeUtil.allocateMemory(4);
        UnsafeUtil.memcpy(new byte[] { 10, 20, 30, 40 }, src);
        UnsafeUtil.memcpy(src, dst, 4);
        byte[] out = new byte[4];
        UnsafeUtil.memcpy(dst, out);
        assertArrayEquals(new byte[] { 10, 20, 30, 40 }, out);
        UnsafeUtil.freeMemory(src);
        UnsafeUtil.freeMemory(dst);
    }

    @Test
    void memcpyShortArrayToNative() {
        long addr = UnsafeUtil.allocateMemory(6);
        short[] in = { 0x0102, 0x0304, 0x0506 };
        UnsafeUtil.memcpy(in, addr);

        assertEquals((short) 0x0102, UnsafeUtil.memGetShort(addr));
        assertEquals((short) 0x0304, UnsafeUtil.memGetShort(addr + 2));
        assertEquals((short) 0x0506, UnsafeUtil.memGetShort(addr + 4));
        UnsafeUtil.freeMemory(addr);
    }

    @Test
    void memAddressReturnsDirectBufferAddress() {
        ByteBuffer direct = ByteBuffer.allocateDirect(16);
        long addr = UnsafeUtil.memAddress(direct);
        assertNotEquals(0, addr);
        UnsafeUtil.memPutInt(addr, 0xCAFEBABE);
        assertEquals(0xCAFEBABE, direct.order(ByteOrder.nativeOrder()).getInt(0));
    }

    @Test
    void memAddressRejectsHeapBuffer() {
        ByteBuffer heap = ByteBuffer.allocate(16);
        assertThrows(IllegalArgumentException.class, () -> UnsafeUtil.memAddress(heap));
    }

    @Test
    void createByteBufferWrapsNativeMemory() {
        long addr = UnsafeUtil.allocateMemory(4);
        UnsafeUtil.memcpy(new byte[] { 1, 2, 3, 4 }, addr);
        ByteBuffer view = UnsafeUtil.createByteBuffer(addr, 4);
        assertEquals(4, view.capacity());
        assertEquals(1, view.get(0));
        assertEquals(4, view.get(3));
        UnsafeUtil.freeMemory(addr);
    }

    @Test
    void memAllocReturnsDirectBuffer() {
        ByteBuffer b = UnsafeUtil.memAlloc(32);
        assertTrue(b.isDirect());
        assertEquals(32, b.capacity());
        UnsafeUtil.memFree(b);
    }

    @Test
    void memCopyBetweenByteBuffers() {
        ByteBuffer src = ByteBuffer.allocateDirect(4).put(new byte[] { 5, 6, 7, 8 });
        src.position(0);
        ByteBuffer dst = ByteBuffer.allocateDirect(4);
        UnsafeUtil.memCopy(src, dst);
        assertEquals(5, dst.get(0));
        assertEquals(6, dst.get(1));
        assertEquals(7, dst.get(2));
        assertEquals(8, dst.get(3));
        assertEquals(0, src.position(), "src position must not advance");
        assertEquals(0, dst.position(), "dst position must not advance");
    }
}
