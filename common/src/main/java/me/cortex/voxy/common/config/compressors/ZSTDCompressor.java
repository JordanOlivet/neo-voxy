package me.cortex.voxy.common.config.compressors;

import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.ThreadLocalMemoryBuffer;
import me.cortex.voxy.common.util.UnsafeUtil;
import me.cortex.voxy.common.world.SaveLoadSystem;

import static me.cortex.voxy.common.util.GlobalCleaner.CLEANER;
import static org.lwjgl.util.zstd.Zstd.*;

public class ZSTDCompressor implements StorageCompressor {
    private record Ref(long ptr) {}

    private static Ref createCleanableCompressionContext() {
        long ctx = ZSTD_createCCtx();
        var ref = new Ref(ctx);
        CLEANER.register(ref, ()->ZSTD_freeCCtx(ctx));
        return ref;
    }

    private static Ref createCleanableDecompressionContext() {
        long ctx = ZSTD_createDCtx();
        nZSTD_DCtx_setParameter(ctx, ZSTD_d_experimentalParam3, 1);//experimental ZSTD_d_forceIgnoreChecksum
        var ref = new Ref(ctx);
        CLEANER.register(ref, ()->ZSTD_freeDCtx(ctx));
        return ref;
    }

    private static final ThreadLocal<Ref> COMPRESSION_CTX = ThreadLocal.withInitial(ZSTDCompressor::createCleanableCompressionContext);
    private static final ThreadLocal<Ref> DECOMPRESSION_CTX = ThreadLocal.withInitial(ZSTDCompressor::createCleanableDecompressionContext);

    private static final ThreadLocalMemoryBuffer SCRATCH = new ThreadLocalMemoryBuffer(SaveLoadSystem.BIGGEST_SERIALIZED_SECTION_SIZE + 1024);

    private final int level;

    public ZSTDCompressor(int level) {
        this.level = level;
    }

    @Override
    public MemoryBuffer compress(MemoryBuffer saveData) {
        MemoryBuffer compressedData = new MemoryBuffer((int)ZSTD_COMPRESSBOUND(saveData.size));
        long compressedSize = nZSTD_compressCCtx(COMPRESSION_CTX.get().ptr, compressedData.address, compressedData.size, saveData.address, saveData.size, this.level);
        if (ZSTD_isError(compressedSize)) {
            compressedData.free();
            throw new RuntimeException("ZSTD compression failed: " + ZSTD_getErrorName(compressedSize)
                    + " (input size=" + saveData.size + ", level=" + this.level + ")");
        }
        return compressedData.subSize(compressedSize);
    }

    @Override
    public MemoryBuffer decompress(MemoryBuffer saveData) {
        var decompressed = SCRATCH.get().createUntrackedUnfreeableReference();
        long size = nZSTD_decompressDCtx(DECOMPRESSION_CTX.get().ptr, decompressed.address, decompressed.size, saveData.address, saveData.size);
        if (ZSTD_isError(size)) {
            me.cortex.voxy.common.Logger.warn("ZSTD decompression failed: " + ZSTD_getErrorName(size)
                    + " (size=" + saveData.size + ", first bytes: " + formatFirstBytes(saveData) + ")");
            return null;
        }
        return decompressed.subSize(size);
    }

    private static String formatFirstBytes(MemoryBuffer data) {
        StringBuilder sb = new StringBuilder();
        int len = (int) Math.min(32, data.size);
        for (int i = 0; i < len; i++) {
            if (i > 0) sb.append(" ");
            byte b = UnsafeUtil.memGetByte(data.address + i);
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    @Override
    public void close() {

    }

    public static class Config extends CompressorConfig {
        public int compressionLevel;

        @Override
        public StorageCompressor build(ConfigBuildCtx ctx) {
            return new ZSTDCompressor(this.compressionLevel);
        }

        public static String getConfigTypeName() {
            return "ZSTD";
        }
    }
}
