package me.cortex.voxy.common.config.storage.other;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.config.storage.StorageConfig;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.function.LongConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class BasicPathInsertionConfigTest {

    /** Captures the resolved path observed at build() time. */
    private static final class CapturingConfig extends StorageConfig {
        String capturedPath;
        final StorageBackend backend = new NoopBackend();

        @Override
        public StorageBackend build(ConfigBuildCtx ctx) {
            this.capturedPath = ctx.resolvePath();
            return this.backend;
        }
    }

    private static final class NoopBackend extends StorageBackend {
        @Override public MemoryBuffer getSectionData(long key, MemoryBuffer scratch) { return null; }
        @Override public void setSectionData(long key, MemoryBuffer data) {}
        @Override public void deleteSectionData(long key) {}
        @Override public void iterateStoredSectionPositions(LongConsumer consumer) {}
        @Override public void putIdMapping(int id, ByteBuffer data) {}
        @Override public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() { return new Int2ObjectOpenHashMap<>(); }
        @Override public void flush() {}
        @Override public void close() {}
    }

    @Test
    void buildPushesAndPopsPath() {
        CapturingConfig delegate = new CapturingConfig();
        BasicPathInsertionConfig cfg = new BasicPathInsertionConfig();
        cfg.delegate = delegate;
        cfg.path = "subdir";

        ConfigBuildCtx ctx = new ConfigBuildCtx();
        ctx.pushPath("base");
        StorageBackend backend = cfg.build(ctx);

        assertSame(delegate.backend, backend, "must return delegate's backend");
        assertEquals("base/subdir", delegate.capturedPath, "delegate sees pushed path");
        assertEquals("base", ctx.resolvePath(), "path must be popped after build");
    }

    @Test
    void buildPropagatesAbsoluteOverride() {
        CapturingConfig delegate = new CapturingConfig();
        BasicPathInsertionConfig cfg = new BasicPathInsertionConfig();
        cfg.delegate = delegate;
        cfg.path = "/abs";

        ConfigBuildCtx ctx = new ConfigBuildCtx();
        ctx.pushPath("ignored");
        cfg.build(ctx);
        assertEquals("/abs", delegate.capturedPath, "absolute child resets base");
    }

    @Test
    void defaultEmptyPathAppendsTrailingSlash() {
        // path defaults to "" → resolvePath observes "base/" because the empty segment
        // forces a separator. Document the current behavior.
        CapturingConfig delegate = new CapturingConfig();
        BasicPathInsertionConfig cfg = new BasicPathInsertionConfig();
        cfg.delegate = delegate;

        ConfigBuildCtx ctx = new ConfigBuildCtx();
        ctx.pushPath("base");
        cfg.build(ctx);
        assertEquals("base/", delegate.capturedPath);
        assertEquals("base", ctx.resolvePath(), "stack must be balanced after build");
    }

    @Test
    void getChildStorageConfigsReturnsDelegate() {
        CapturingConfig delegate = new CapturingConfig();
        BasicPathInsertionConfig cfg = new BasicPathInsertionConfig();
        cfg.delegate = delegate;
        var children = cfg.getChildStorageConfigs();
        assertEquals(1, children.size());
        assertSame(delegate, children.get(0));
    }
}
