package me.cortex.voxy.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigBuildCtxTest {

    @Test
    void substituteStringReplacesRegisteredTokens() {
        var ctx = new ConfigBuildCtx();
        ctx.setProperty("{world_identifier}", "overworld");
        ctx.setProperty("{base_save_path}", "saves");
        assertEquals("saves/overworld/data",
                ctx.substituteString("{base_save_path}/{world_identifier}/data"));
    }

    @Test
    void substituteStringLeavesUnknownTokens() {
        var ctx = new ConfigBuildCtx();
        assertEquals("{unknown}/x", ctx.substituteString("{unknown}/x"));
    }

    @Test
    void setPropertyRejectsPlainKey() {
        var ctx = new ConfigBuildCtx();
        assertThrows(IllegalArgumentException.class, () -> ctx.setProperty("world", "x"));
    }

    @Test
    void setPropertyRejectsMissingClosingBrace() {
        var ctx = new ConfigBuildCtx();
        assertThrows(IllegalArgumentException.class, () -> ctx.setProperty("{world", "x"));
    }

    @Test
    void resolvePathConcatenatesStack() {
        var ctx = new ConfigBuildCtx();
        ctx.pushPath("a");
        ctx.pushPath("b");
        ctx.pushPath("c");
        assertEquals("a/b/c", ctx.resolvePath());
    }

    @Test
    void resolvePathSingleElement() {
        var ctx = new ConfigBuildCtx();
        ctx.pushPath("only");
        assertEquals("only", ctx.resolvePath());
    }

    @Test
    void resolvePathHandlesDotSlashPrefix() {
        var ctx = new ConfigBuildCtx();
        ctx.pushPath("base");
        ctx.pushPath("./child");
        assertEquals("base/child", ctx.resolvePath());
    }

    @Test
    void resolvePathAbsoluteChildResetsBase() {
        var ctx = new ConfigBuildCtx();
        ctx.pushPath("base");
        ctx.pushPath("/absolute");
        assertEquals("/absolute", ctx.resolvePath());
    }

    @Test
    void resolvePathDriveLetterResetsBase() {
        var ctx = new ConfigBuildCtx();
        ctx.pushPath("base");
        ctx.pushPath("C:/drive");
        assertEquals("C:/drive", ctx.resolvePath());
    }

    @Test
    void resolvePathRejectsRelativeDotDot() {
        var ctx = new ConfigBuildCtx();
        ctx.pushPath("base");
        ctx.pushPath("../escape");
        assertThrows(IllegalStateException.class, ctx::resolvePath);
    }

    @Test
    void popPathUndoesPush() {
        var ctx = new ConfigBuildCtx();
        ctx.pushPath("a");
        ctx.pushPath("b");
        ctx.popPath();
        assertEquals("a", ctx.resolvePath());
    }

    @Test
    void resolvePathEmptyStackReturnsEmpty() {
        var ctx = new ConfigBuildCtx();
        assertEquals("", ctx.resolvePath());
    }

    @Test
    void resolvePathAppendsTrailingSlashWhenMissing() {
        var ctx = new ConfigBuildCtx();
        ctx.pushPath("a/");
        ctx.pushPath("b");
        assertEquals("a/b", ctx.resolvePath());
        ctx.popPath();
        ctx.pushPath("c"); // stack now ["a/", "c"], base "a/" already has slash
        assertEquals("a/c", ctx.resolvePath());
    }

    @Test
    void ensurePathExistsCreatesDirectory(@TempDir Path tmp) {
        var ctx = new ConfigBuildCtx();
        Path target = tmp.resolve("nested/inner/leaf");
        String result = ctx.ensurePathExists(target.toString());
        assertEquals(target.toString(), result);
        assertTrue(Files.isDirectory(target));
    }

    @Test
    void ensurePathExistsIdempotent(@TempDir Path tmp) {
        var ctx = new ConfigBuildCtx();
        Path target = tmp.resolve("already-exists");
        ctx.ensurePathExists(target.toString());
        ctx.ensurePathExists(target.toString()); // second call must not throw
        assertTrue(Files.isDirectory(target));
    }

    @Test
    void fluentApiReturnsSameContext() {
        var ctx = new ConfigBuildCtx();
        assertEquals(ctx, ctx.setProperty("{x}", "1"));
        assertEquals(ctx, ctx.pushPath("a"));
        assertEquals(ctx, ctx.popPath());
    }
}
