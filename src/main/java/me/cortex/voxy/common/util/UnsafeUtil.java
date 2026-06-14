package me.cortex.voxy.common.util;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.ByteBuffer;

/**
 * Utility class that provides memory operations using sun.misc.Unsafe.
 * This replaces LWJGL MemoryUtil to work on both client and dedicated server.
 */
public class UnsafeUtil {
    private static final Unsafe UNSAFE;
    private static final long BUFFER_ADDRESS_OFFSET;
    private static final long BUFFER_CAPACITY_OFFSET;
    private static final long BUFFER_LIMIT_OFFSET;
    private static final long BUFFER_POSITION_OFFSET;
    private static final long BUFFER_MARK_OFFSET;
    private static final long BB_BIG_ENDIAN_OFFSET;
    private static final Class<?> DIRECT_BYTE_BUFFER_CLASS;
    static {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            UNSAFE = (Unsafe) field.get(null);

            // Cache Buffer/ByteBuffer field offsets so that createByteBuffer can
            // allocate a DirectByteBuffer via Unsafe.allocateInstance and populate
            // its fields directly. This avoids ByteBuffer.allocateDirect, which
            // counts against -XX:MaxDirectMemorySize and was the source of a
            // direct-memory OOM under bulk ingest (Chunky pregen).
            BUFFER_ADDRESS_OFFSET = UNSAFE.objectFieldOffset(Buffer.class.getDeclaredField("address"));
            BUFFER_CAPACITY_OFFSET = UNSAFE.objectFieldOffset(Buffer.class.getDeclaredField("capacity"));
            BUFFER_LIMIT_OFFSET = UNSAFE.objectFieldOffset(Buffer.class.getDeclaredField("limit"));
            BUFFER_POSITION_OFFSET = UNSAFE.objectFieldOffset(Buffer.class.getDeclaredField("position"));
            BUFFER_MARK_OFFSET = UNSAFE.objectFieldOffset(Buffer.class.getDeclaredField("mark"));
            BB_BIG_ENDIAN_OFFSET = UNSAFE.objectFieldOffset(ByteBuffer.class.getDeclaredField("bigEndian"));
            DIRECT_BYTE_BUFFER_CLASS = Class.forName("java.nio.DirectByteBuffer");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final long BYTE_ARRAY_BASE_OFFSET = UNSAFE.arrayBaseOffset(byte[].class);
    private static final long SHORT_ARRAY_BASE_OFFSET = UNSAFE.arrayBaseOffset(short[].class);
    private static final long LONG_ARRAY_BASE_OFFSET = UNSAFE.arrayBaseOffset(long[].class);

    // Memory allocation operations (replaces LWJGL MemoryUtil)
    public static long allocateMemory(long size) {
        return UNSAFE.allocateMemory(size);
    }

    public static void freeMemory(long address) {
        UNSAFE.freeMemory(address);
    }

    public static void memset(long address, int value, long size) {
        UNSAFE.setMemory(address, size, (byte) value);
    }

    // Memory read/write primitives (replaces LWJGL MemoryUtil.memPut*/memGet*)
    public static void memPutLong(long address, long value) {
        UNSAFE.putLong(address, value);
    }

    public static long memGetLong(long address) {
        return UNSAFE.getLong(address);
    }

    public static void memPutInt(long address, int value) {
        UNSAFE.putInt(address, value);
    }

    public static int memGetInt(long address) {
        return UNSAFE.getInt(address);
    }

    public static void memPutShort(long address, short value) {
        UNSAFE.putShort(address, value);
    }

    public static short memGetShort(long address) {
        return UNSAFE.getShort(address);
    }

    public static void memPutByte(long address, byte value) {
        UNSAFE.putByte(address, value);
    }

    public static byte memGetByte(long address) {
        return UNSAFE.getByte(address);
    }

    /**
     * Gets the native memory address of a direct ByteBuffer.
     * Replaces LWJGL MemoryUtil.memAddress()
     */
    public static long memAddress(ByteBuffer buffer) {
        if (!buffer.isDirect()) {
            throw new IllegalArgumentException("Buffer must be direct");
        }
        return UNSAFE.getLong(buffer, BUFFER_ADDRESS_OFFSET);
    }

    public static void memcpy(long src, long dst, long length) {
        UNSAFE.copyMemory(src, dst, length);
    }

    // Copy the entire length of src to the dst memory where dst is a byte array
    // (source length from dst)
    public static void memcpy(long src, byte[] dst) {
        UNSAFE.copyMemory(null, src, dst, BYTE_ARRAY_BASE_OFFSET, dst.length);
    }

    public static void memcpy(long src, int length, byte[] dst) {
        UNSAFE.copyMemory(null, src, dst, BYTE_ARRAY_BASE_OFFSET, length);
    }

    public static void memcpy(long src, int length, byte[] dst, int offset) {
        UNSAFE.copyMemory(null, src, dst, BYTE_ARRAY_BASE_OFFSET + offset, length);
    }

    // Copy the entire length of src to the dst memory where src is a byte array
    // (source length from src)
    public static void memcpy(byte[] src, long dst) {
        UNSAFE.copyMemory(src, BYTE_ARRAY_BASE_OFFSET, null, dst, src.length);
    }

    public static void memcpy(byte[] src, int len, long dst) {
        UNSAFE.copyMemory(src, BYTE_ARRAY_BASE_OFFSET, null, dst, len);
    }

    public static void memcpy(short[] src, long dst) {
        UNSAFE.copyMemory(src, SHORT_ARRAY_BASE_OFFSET, null, dst, (long) src.length << 1);
    }

    public static void memcpy(long[] src, long dst) {
        UNSAFE.copyMemory(src, LONG_ARRAY_BASE_OFFSET, null, dst, (long) src.length << 3);
    }

    /**
     * Creates a ByteBuffer view of native memory at the given address.
     * Allocates a DirectByteBuffer instance via Unsafe.allocateInstance, bypassing
     * its constructor entirely. This avoids ByteBuffer.allocateDirect (which
     * reserves -XX:MaxDirectMemorySize-tracked memory and was leaking under bulk
     * ingest when the reflective DirectByteBuffer(long,int) constructor path
     * failed under JDK 21 strong encapsulation) and also avoids the per-call
     * reflection cost of the previous implementation.
     *
     * Byte order is set to BIG_ENDIAN to match the default ByteBuffer order
     * produced by the JNI DirectByteBuffer(long,int) constructor.
     */
    public static ByteBuffer createByteBuffer(long address, int size) {
        try {
            ByteBuffer buffer = (ByteBuffer) UNSAFE.allocateInstance(DIRECT_BYTE_BUFFER_CLASS);
            UNSAFE.putLong(buffer, BUFFER_ADDRESS_OFFSET, address);
            UNSAFE.putInt(buffer, BUFFER_MARK_OFFSET, -1);
            UNSAFE.putInt(buffer, BUFFER_POSITION_OFFSET, 0);
            UNSAFE.putInt(buffer, BUFFER_LIMIT_OFFSET, size);
            UNSAFE.putInt(buffer, BUFFER_CAPACITY_OFFSET, size);
            UNSAFE.putBoolean(buffer, BB_BIG_ENDIAN_OFFSET, true);
            return buffer;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create ByteBuffer wrapping native memory", e);
        }
    }

    /**
     * Allocates a direct ByteBuffer. Replaces LWJGL MemoryUtil.memAlloc()
     */
    public static ByteBuffer memAlloc(int size) {
        return ByteBuffer.allocateDirect(size);
    }

    /**
     * Frees a direct ByteBuffer. Replaces LWJGL MemoryUtil.memFree()
     * Since we utilize ByteBuffer.allocateDirect, we rely on the GC/Cleaner,
     * but we provide this no-op to satisfy the API.
     */
    public static void memFree(Buffer buffer) {
        // No-op for standard Java direct buffers, relying on GC.
        // If we really wanted to freeing immediately, we'd use Unsafe to invoke the
        // cleaner,
        // but that's version dependent.
    }

    /**
     * Copies memory from one ByteBuffer to another. Replaces LWJGL
     * MemoryUtil.memCopy()
     */
    public static void memCopy(ByteBuffer src, ByteBuffer dst) {
        // Duplicate src to not affect its position
        ByteBuffer srcSlice = src.duplicate();
        ByteBuffer dstSlice = dst.duplicate();
        dstSlice.put(srcSlice);
    }
}
