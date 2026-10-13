package me.cortex.voxy.client.core.gl.shader;

import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class ShaderLoader {
    /**
     * Shader sources resolved via {@link Class#getResourceAsStream} sometimes
     * become unreachable after a disconnect/reconnect cycle on NeoForge — the
     * modular classloader appears to invalidate resource URLs across module
     * relifecycle. Caching successful loads keeps the second session alive even
     * when {@code getResourceAsStream} starts returning {@code null}.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, String> SOURCE_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Load shader source from Voxy's classpath.
     * This is necessary on NeoForge because Sodium's ShaderLoader uses its own
     * classloader which cannot access Voxy's resources due to module isolation.
     */
    private static String loadShaderSource(String id) {
        String cached = SOURCE_CACHE.get(id);
        if (cached != null) {
            return cached;
        }

        String ns = id.split(":")[0];
        String path = id.split(":")[1];
        String resourcePath = "/assets/" + ns + "/shaders/" + path;

        try (InputStream stream = ShaderLoader.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new RuntimeException("Shader not found: " + resourcePath);
            }
            String src = IOUtils.toString(stream, StandardCharsets.UTF_8);
            SOURCE_CACHE.put(id, src);
            return src;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load shader: " + resourcePath, e);
        }
    }

    public static String parse(String id) {
        // Load the shader source ourselves since Sodium's classloader can't see our
        // resources
        String src = loadShaderSource(id);

        // Parse imports recursively
        String parsed = parseWithVoxyImports(src);
        parsed = parsed.replaceAll("\r\n", "\n").replaceFirst("(?m)^#version .+\n", "");

        return "#version 460 core\n" + parsed;
    }

    /**
     * Parse shader and resolve imports from Voxy's classpath
     */
    private static String parseWithVoxyImports(String src) {
        StringBuilder result = new StringBuilder();
        String[] lines = src.split("\n");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#import <") && trimmed.endsWith(">")) {
                // Extract import path: #import <voxy:path/to/file.glsl>
                String importPath = trimmed.substring(9, trimmed.length() - 1);
                String importedSrc = loadShaderSource(importPath);
                // Recursively parse imports
                result.append(parseWithVoxyImports(importedSrc));
            } else {
                result.append(line).append("\n");
            }
        }

        return result.toString();
    }
}
