package me.cortex.voxy.client.core.rendering;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlVertexArray;
import me.cortex.voxy.client.core.gl.shader.AutoBindingShader;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.ARBDirectStateAccess.glCopyNamedBufferSubData;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL31.glDrawElementsInstanced;
import static org.lwjgl.opengl.GL42.glDrawElementsInstancedBaseInstance;

//This is a render subsystem, its very simple in what it does
// it renders an AABB around loaded chunks, thats it
//
// === Phantom occlusion bug — diagnosed 2026-04-25, fix = option B ===
// Symptom: specific LOD chunks become invisible (showing sky behind) at the
// vanilla render-distance ring. Hyper-sensitive to camera *position* (±1-2
// blocks toggles), independent of camera *rotation*, only with Voxy enabled.
//
// Root cause: this renderer rasterizes a full 16×16×16 AABB for every Sodium
// section into the depth-bounding buffer. The LOD `quads.frag` discards pixels
// where `gl_FragCoord.z < depthTex.r`. Edge sections at the vanilla RD ring are
// typically only partially populated (terrain at bottom, air at top — surface
// chunks at the world horizon). The full-section AABB over-covers the empty-air
// portion. LOD pixels meant to render behind that empty portion get discarded
// → hole showing sky.
//
// Fix applied (option B, surgical): skip AABB rasterization for sections in the
// outermost vanilla RD ring (chebyshev XZ ≥ vanillaRD - 1 from camera). See
// `outline.vsh`. We retain depth-bound benefit everywhere else; the edge ring
// pays a small fillrate cost (LOD overdraws into vanilla zone, final z-test
// resolves correctly).
//
// Future option A (proper architectural fix) — pursue if the bug is ever
// reported away from the edge ring (interior partial sections: floating
// islands, large caves with open tops, deep ravines exposed to sky):
//   1. Compute tight AABB bounds (min/max XYZ in section-local 0..16) by
//      iterating LevelChunkSection.getStates() in the mixin at the moment of
//      `voxy$updateOnUpload`. Cost ≈ 4096 ops/section update, ~few µs.
//   2. Pack bounds into the upload format (currently 8 bytes ivec2 → would
//      become 12 bytes: ivec2 pos + 1 packed int with 6×4-bit min/max).
//   3. Modify `outline.vsh` to use these bounds when generating cube vertices
//      instead of the fixed 0..16 corners.
// Sodium's `BuiltSectionInfo` only exposes `flags` + `visibilityData`, no
// bounds — so we'd compute them ourselves. Verified against Sodium 0.6.13.
//
// Diagnosis tooling (KEEP IN PLACE for future bug recurrence):
//   - `DEBUG_DISABLE_DEPTH_BOUNDS` toggle below + `/voxy debugBounds` command:
//     If enabled, AABB rasterization is skipped entirely — depth buffer stays
//     at 0, LOD shader's depth test never discards. If toggling makes the
//     missing chunks reappear → confirmed phantom occlusion → consider option A.
//   - `_debugGetTrackedPositions()` (if reintroduced from stash): snapshot of
//     currently tracked sections, for cross-checking against Sodium state.
public class ChunkBoundRenderer {
    // Debug toggle: when true, skip AABB rasterization. Wired to `/voxy debugBounds`.
    // Used to confirm the phantom-occlusion bug; should normally stay false.
    public static volatile boolean DEBUG_DISABLE_DEPTH_BOUNDS = false;

    private static final int INIT_MAX_CHUNK_COUNT = 1 << 12;
    private GlBuffer chunkPosBuffer = new GlBuffer(INIT_MAX_CHUNK_COUNT * 8);// Stored as ivec2
    private final GlBuffer uniformBuffer = new GlBuffer(128);
    private final Long2IntOpenHashMap chunk2idx = new Long2IntOpenHashMap(INIT_MAX_CHUNK_COUNT);
    private long[] idx2chunk = new long[INIT_MAX_CHUNK_COUNT];
    private final Shader rasterShader;

    private final LongOpenHashSet addQueue = new LongOpenHashSet();
    private final LongOpenHashSet remQueue = new LongOpenHashSet();

    private final AbstractRenderPipeline pipeline;

    public ChunkBoundRenderer(AbstractRenderPipeline pipeline) {
        this.chunk2idx.defaultReturnValue(-1);
        this.pipeline = pipeline;

        String vert = ShaderLoader.parse("voxy:chunkoutline/outline.vsh");
        String taa = pipeline.taaFunction("getTAA");
        if (taa != null) {
            vert = vert + "\n\n\n" + taa;
        }
        this.rasterShader = Shader.makeAuto()
                .addSource(ShaderType.VERTEX, vert)
                .defineIf("TAA", taa != null)
                .defineIf("USE_SODIUM_EXTRA_CULLING", false)
                .add(ShaderType.FRAGMENT, "voxy:chunkoutline/outline.fsh")
                .compile()
                .ubo(0, this.uniformBuffer)
                .ssbo(1, this.chunkPosBuffer);
    }

    public void addSection(long pos) {
        if (!this.remQueue.remove(pos)) {
            this.addQueue.add(pos);
        }
    }

    public void removeSection(long pos) {
        if (!this.addQueue.remove(pos)) {
            this.remQueue.add(pos);
        }
    }

    // Bind and render, changing as little gl state as possible so that the caller
    // may configure how it wants to render
    public void render(Viewport<?> viewport) {
        if (!this.remQueue.isEmpty()) {
            boolean wasEmpty = this.chunk2idx.isEmpty();
            this.remQueue.forEach(this::_remPos);// TODO: REPLACE WITH SCATTER COMPUTE
            this.remQueue.clear();
            if (this.chunk2idx.isEmpty() && !wasEmpty) {// When going from stuff to nothing need to clear the depth
                                                        // buffer
                viewport.depthBoundingBuffer.clear(0);
            }
        }

        if (this.chunk2idx.isEmpty() && this.addQueue.isEmpty())
            return;

        viewport.depthBoundingBuffer.clear(0);

        if (DEBUG_DISABLE_DEPTH_BOUNDS) {
            // Drain the addQueue so tracking stays consistent across toggles, but skip raster.
            if (!this.addQueue.isEmpty()) {
                this.addQueue.forEach(this::_addPos);
                this.addQueue.clear();
                UploadStream.INSTANCE.commit();
            }
            return;
        }

        long ptr = UploadStream.INSTANCE.upload(this.uniformBuffer, 0, 128);
        long matPtr = ptr;
        ptr += 4 * 4 * 4;

        final int vanillaRdChunks = Minecraft.getInstance().options.getEffectiveRenderDistance();
        final float renderDistance = vanillaRdChunks * 16;// In blocks

        {// This is recomputed to be in chunk section space not worldsection
            int sx = (int) (viewport.cameraX);
            int sy = (int) (viewport.cameraY);
            int sz = (int) (viewport.cameraZ);
            // Pack camera section blocks into ivec4.xyz; .w carries vanilla RD in chunks
            // for the option-B chebyshev edge-ring skip in outline.vsh.
            MemoryUtil.memPutInt(ptr, sx);
            MemoryUtil.memPutInt(ptr + 4, sy);
            MemoryUtil.memPutInt(ptr + 8, sz);
            MemoryUtil.memPutInt(ptr + 12, vanillaRdChunks);
            ptr += 4 * 4;

            var negInnerSec = new Vector3f(
                    (float) (viewport.cameraX - sx),
                    (float) (viewport.cameraY - sy),
                    (float) (viewport.cameraZ - sz));

            negInnerSec.getToAddress(ptr);
            ptr += 4 * 3;
            viewport.MVP.translate(negInnerSec.negate(), new Matrix4f()).getToAddress(matPtr);
            MemoryUtil.memPutFloat(ptr, renderDistance);
            ptr += 4;
        }
        UploadStream.INSTANCE.commit();

        {
            // need to reverse the winding order since we want the back faces of the AABB,
            // not the front

            glFrontFace(GL_CW);// Reverse winding order

            // "reverse depth buffer" it goes from 0->1 where 1 is far away
            glEnable(GL_CULL_FACE);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_GREATER);
        }

        glBindVertexArray(GlVertexArray.STATIC_VAO);
        viewport.depthBoundingBuffer.bind();
        this.rasterShader.bind();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, SharedIndexBuffer.INSTANCE_BB_BYTE.id());
        this.pipeline.bindUniforms();

        // Batch the draws into groups of size 32
        int count = this.chunk2idx.size();
        if (count > 32) {
            glDrawElementsInstanced(GL_TRIANGLES, 6 * 2 * 3 * 32, GL_UNSIGNED_BYTE, 0, count / 32);
        }
        if (count % 32 != 0) {
            glDrawElementsInstancedBaseInstance(GL_TRIANGLES, 6 * 2 * 3 * (count % 32), GL_UNSIGNED_BYTE, 0, 1,
                    (count / 32) * 32);
        }

        {
            glFrontFace(GL_CCW);// Restore winding order

            glDepthFunc(GL_LEQUAL);

            // TODO: check this is correct
            glEnable(GL_CULL_FACE);
            glEnable(GL_DEPTH_TEST);
        }

        if (!this.addQueue.isEmpty()) {
            this.addQueue.forEach(this::_addPos);// TODO: REPLACE WITH SCATTER COMPUTE
            this.addQueue.clear();
            UploadStream.INSTANCE.commit();
        }
    }

    private void _remPos(long pos) {
        int idx = this.chunk2idx.remove(pos);
        if (idx == -1) {
            Logger.warn("Chunk not in map: " + pos);
            return;
        }
        if (idx == this.chunk2idx.size()) {
            // Dont need to do anything as heap is already compact
            return;
        }
        if (this.idx2chunk[idx] != pos) {
            throw new IllegalStateException();
        }

        // Move last entry on heap to this index
        long ePos = this.idx2chunk[this.chunk2idx.size()];// since is already removed size is correct end idx
        if (this.chunk2idx.put(ePos, idx) == -1) {
            throw new IllegalStateException();
        }
        this.idx2chunk[idx] = ePos;

        // Put the end pos into the new idx
        this.put(idx, ePos);
    }

    private void _addPos(long pos) {
        if (this.chunk2idx.containsKey(pos)) {
            Logger.warn("Chunk already in map: " + pos);
            return;
        }
        this.ensureSize1();// Resize if needed

        int idx = this.chunk2idx.size();
        this.chunk2idx.put(pos, idx);
        this.idx2chunk[idx] = pos;

        this.put(idx, pos);
    }

    private void ensureSize1() {
        if (this.chunk2idx.size() < this.idx2chunk.length)
            return;
        // Commit any copies, ensures is synced to new buffer
        UploadStream.INSTANCE.commit();

        int size = (int) (this.idx2chunk.length * 1.5);
        Logger.info("Resizing chunk position buffer to: " + size);
        // Need to resize
        var old = this.chunkPosBuffer;
        this.chunkPosBuffer = new GlBuffer(size * 8L);
        glCopyNamedBufferSubData(old.id, this.chunkPosBuffer.id, 0, 0, old.size());
        old.free();
        var old2 = this.idx2chunk;
        this.idx2chunk = new long[size];
        System.arraycopy(old2, 0, this.idx2chunk, 0, old2.length);
        // Replace the old buffer with the new one
        ((AutoBindingShader) this.rasterShader).ssbo(1, this.chunkPosBuffer);
    }

    private void put(int idx, long pos) {
        long ptr2 = UploadStream.INSTANCE.upload(this.chunkPosBuffer, 8L * idx, 8);
        // Need to do it in 2 parts because ivec2 is 2 parts
        MemoryUtil.memPutInt(ptr2, (int) (pos & 0xFFFFFFFFL));
        ptr2 += 4;
        MemoryUtil.memPutInt(ptr2, (int) ((pos >>> 32) & 0xFFFFFFFFL));
    }

    public void reset() {
        this.chunk2idx.clear();
    }

    public void free() {
        this.rasterShader.free();
        this.uniformBuffer.free();
        this.chunkPosBuffer.free();
    }
}
