package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.TrackedObject;

import java.util.ArrayList;

import static org.lwjgl.opengl.GL11.GL_RGBA8;

// System to allow reuse/recycling of render buffer/texture allocations,
// specifically the geometry buffer and texture atlas. Recreating the render
// system (resource reload, toggling voxy, dimension change) used to free and
// reallocate the multi-GB geometry buffer and the model atlas, which is slow and
// can spike VRAM. Caching them across recreations avoids that.
public class RenderResourceReuse {
    private static final ArrayList<GlTexture> MODEL_TEXTURE_CACHE = new ArrayList<>();
    private static final ArrayList<GlBuffer> GEOMETRY_BUFFER_CACHE = new ArrayList<>();

    // Clears and frees any cached resources (used when the entire instance is shutdown)
    public static void clearResources() {
        MODEL_TEXTURE_CACHE.forEach(TrackedObject::free);
        GEOMETRY_BUFFER_CACHE.forEach(TrackedObject::free);
        MODEL_TEXTURE_CACHE.clear();
        GEOMETRY_BUFFER_CACHE.clear();
    }

    public static GlTexture getOrCreateModelStoreTextureAtlas() {
        if (!MODEL_TEXTURE_CACHE.isEmpty()) {
            return MODEL_TEXTURE_CACHE.removeFirst().zero();
        }
        return new GlTexture().store(GL_RGBA8,
                        Integer.numberOfTrailingZeros(ModelFactory.MODEL_TEXTURE_SIZE),
                        ModelFactory.MODEL_TEXTURE_SIZE * 3 * 256,
                        ModelFactory.MODEL_TEXTURE_SIZE * 2 * 256)
                .name("ModelTextures");
    }

    public static void giveBackModelStoreTextureAtlas(GlTexture texture) {
        MODEL_TEXTURE_CACHE.add(texture);
    }

    public static GlBuffer getOrCreateGeometryBuffer() {
        if (!GEOMETRY_BUFFER_CACHE.isEmpty()) {
            // Reuse buffer, todo: probably check the geometry size and try upsize if possible
            return GEOMETRY_BUFFER_CACHE.removeFirst();
        }
        return BasicSectionGeometryData.allocateGeometryBuffer(getGeometryBufferSize());
    }

    public static void giveBackGeometryBuffer(GlBuffer geometryBuffer) {
        GEOMETRY_BUFFER_CACHE.add(geometryBuffer);
    }

    private static long getGeometryBufferSize() {
        long geometryCapacity = Math
                .min((1L << (64 - Long.numberOfLeadingZeros(Capabilities.INSTANCE.ssboMaxSize - 1))) << 1, 1L << 32)
                - 1024/* (1L<<32)-1024 */;
        if (Capabilities.INSTANCE.isIntel) {
            geometryCapacity = Math.max(geometryCapacity, 1L << 30);// intel moment, force min 1gb
        }

        // Limit to available dedicated memory if possible
        if (Capabilities.INSTANCE.canQueryGpuMemory) {
            // 1.5gb vram buffer below available
            long limit = Capabilities.INSTANCE.getFreeDedicatedGpuMemory() - (long) (1.5 * 1024 * 1024 * 1024);
            // Give a minimum of 512 mb requirement
            limit = Math.max(512 * 1024 * 1024, limit);

            geometryCapacity = Math.min(geometryCapacity, limit);
        }
        var override = System.getProperty("voxy.geometryBufferSizeOverrideMB", "");
        if (!override.isEmpty()) {
            geometryCapacity = Long.parseLong(override) * 1024L * 1024L;
        }
        Logger.info("Geometry buffer size resolved to " + (geometryCapacity / (1024 * 1024)) + "MB");
        return geometryCapacity;
    }
}
