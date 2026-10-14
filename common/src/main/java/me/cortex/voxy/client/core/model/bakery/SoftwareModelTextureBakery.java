package me.cortex.voxy.client.core.model.bakery;

import com.mojang.blaze3d.vertex.PoseStack;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.common.util.UnsafeUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import static org.lwjgl.opengl.ARBDirectStateAccess.glGetTextureImage;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL11C.GL_RGBA;
import static org.lwjgl.opengl.GL12.GL_PACK_IMAGE_HEIGHT;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;

// CPU software-rasterized model texture bakery. Drop-in replacement for the
// GL-based ModelTextureBakery: produces the exact same per-pixel output layout
// ([colour ABGR int][depth int] per pixel, 6 faces appended) so ModelFactory's
// downstream processing is unchanged. Reimplemented against the 1.21.1 model API
// (upstream's version targets MC 26.1); the underlying SoftwareRasterizer is the
// upstream CPU rasterizer.
public class SoftwareModelTextureBakery {
    private static final Matrix4f[] VIEWS = new Matrix4f[6];

    // Block models are extracted off-thread safely (Sodium meshes chunks the same
    // way), but the shared vanilla fluid renderer may not be thread-safe, so fluid
    // baking (rare) is serialised across bake threads.
    private static final Object FLUID_BAKE_LOCK = new Object();

    private final ReuseVertexConsumer vc = new ReuseVertexConsumer();
    private final SoftwareRasterizer rasterizer = new SoftwareRasterizer(ModelFactory.MODEL_TEXTURE_SIZE);

    private final int size = ModelFactory.MODEL_TEXTURE_SIZE;
    private volatile boolean textureLoaded;

    public SoftwareModelTextureBakery() {
    }

    public boolean isAtlasLoaded() {
        return this.textureLoaded;
    }

    // Reads the block atlas pixels into the rasterizer's sampler. Must run on the
    // render thread (needs the GL context). Lazily done on first bake / can be
    // forced after a resource reload via reloadAtlas().
    // Shared, immutable atlas pixel data. Read once on the render thread, then shared
    // (read-only) by every per-thread bakery so baking can run on multiple workers.
    public record Atlas(int[] pixels, int width, int height) {}

    // Reads the block atlas mip 0 on the render thread (needs the GL context). The
    // returned data is immutable and safe to share across baking threads.
    public static Atlas loadAtlas() {
        var tex = Minecraft.getInstance().getTextureManager()
                .getTexture(ResourceLocation.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png"));
        int glId = tex.getId();
        int width = glGetTexLevelWidth(glId);
        int height = glGetTexLevelHeight(glId);

        // Read only mip 0 (full res). The rasterizer area-averages it per output
        // footprint, which gives a clean downscale without MC's atlas mips — those
        // bleed neighbouring sprites into a sprite's edge texels at higher levels and
        // put stray (e.g. sky-blue) pixels on opaque LOD blocks.
        glFlush();
        glFinish();
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        // Pixel-store PACK state is GLOBAL: a non-default value left here corrupts
        // later glReadPixels by other mods (a leftover PACK_ROW_LENGTH crashed the
        // NVIDIA driver via the watut framebuffer read). Read tightly packed
        // (ROW_LENGTH=0 = use image width) and restore defaults afterwards.
        glPixelStorei(GL_PACK_ROW_LENGTH, 0);
        glPixelStorei(GL_PACK_IMAGE_HEIGHT, 0);
        glPixelStorei(GL_PACK_SKIP_ROWS, 0);
        glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
        glPixelStorei(GL_PACK_ALIGNMENT, 4);

        int[] pixels = new int[width * height];
        glGetTextureImage(glId, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);

        // Restore pixel-store PACK state to GL defaults
        glPixelStorei(GL_PACK_ROW_LENGTH, 0);
        glPixelStorei(GL_PACK_IMAGE_HEIGHT, 0);
        glPixelStorei(GL_PACK_SKIP_ROWS, 0);
        glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
        glPixelStorei(GL_PACK_ALIGNMENT, 4);

        return new Atlas(pixels, width, height);
    }

    // Point this bakery's rasterizer at a (shared, read-only) atlas. The rasterizer
    // only reads it; its scratch/framebuffer is per-instance, so several bakeries can
    // share one Atlas and bake in parallel.
    public void setAtlas(Atlas atlas) {
        this.rasterizer.setSamplerTexture(atlas.pixels(), atlas.width(), atlas.height());
        this.textureLoaded = true;
    }

    // Convenience: load + set on the render thread (single-threaded path / tests).
    public void setupTexture() {
        this.setAtlas(loadAtlas());
    }

    public void reloadAtlas() {
        this.textureLoaded = false;
    }

    private static int glGetTexLevelWidth(int id) {
        return org.lwjgl.opengl.GL45C.glGetTextureLevelParameteri(id, 0, GL_TEXTURE_WIDTH);
    }

    private static int glGetTexLevelHeight(int id) {
        return org.lwjgl.opengl.GL45C.glGetTextureLevelParameteri(id, 0, GL_TEXTURE_HEIGHT);
    }

    private void bakeBlockModel(BlockState state, RenderType layer) {
        if (state.getRenderShape() == RenderShape.INVISIBLE) {
            return;//Dont bake if invisible
        }
        var model = Minecraft.getInstance()
                .getModelManager()
                .getBlockModelShaper()
                .getBlockModel(state);

        int meta = ModelTextureBakery.getMetaFromLayer(layer);

        for (Direction direction : new Direction[]{Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null}) {
            var quads = model.getQuads(state, direction, new SingleThreadedRandomSource(42L));
            for (var quad : quads) {
                this.vc.quad(quad, meta | (quad.isTinted() ? 4 : 0));
            }
        }
    }

    private void bakeFluidState(BlockState state, RenderType layer, int face) {
        {
            int metadata = ModelTextureBakery.getMetaFromLayer(layer);
            //Just assume all fluids are tinted, if they arnt it should be implicitly culled in the model baking phase
            // since it wont have the colour provider
            metadata |= 4;//Has tint
            this.vc.setDefaultMeta(metadata);
        }
        synchronized (FLUID_BAKE_LOCK) {
        Minecraft.getInstance().getBlockRenderer().renderLiquid(BlockPos.ZERO, new BlockAndTintGetter() {
            @Override
            public float getShade(Direction direction, boolean shaded) {
                return 0;
            }

            @Override
            public LevelLightEngine getLightEngine() {
                return null;
            }

            @Override
            public int getBrightness(LightLayer type, BlockPos pos) {
                return 0;
            }

            @Override
            public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
                return 0;
            }

            @Nullable
            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face)) {
                    return Blocks.AIR.defaultBlockState();
                }
                return state;
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                if (shouldReturnAirForFluid(pos, face)) {
                    return Blocks.AIR.defaultBlockState().getFluidState();
                }
                return state.getFluidState();
            }

            @Override
            public int getHeight() {
                return 0;
            }

            @Override
            public int getMinBuildHeight() {
                return 0;
            }
        }, this.vc, state, state.getFluidState());
        }
        this.vc.setDefaultMeta(0);
    }

    private static boolean shouldReturnAirForFluid(BlockPos pos, int face) {
        var fv = Direction.from3DDataValue(face).getNormal();
        int dot = fv.getX() * pos.getX() + fv.getY() * pos.getY() + fv.getZ() * pos.getZ();
        return dot >= 1;
    }

    public void free() {
        this.vc.free();
    }

    // Writes 6 faces, each size*size pixels, appended. Per pixel: [colour ABGR int][depth int]
    // matching the GL ModelTextureBakery output so ModelFactory.processModelResult is unchanged.
    // depth int layout: bits 8..31 = 24-bit depth, bit 7 = tint flag, bits 0..6 = written/stencil count.
    public void renderToOutput(BlockState state, long outputBuffer) {
        // The atlas must be pre-loaded on the render thread (setupTexture) before any
        // off-thread bake that actually samples it. Empty/invisible models (e.g. air,
        // seeded at construction before the atlas is read) emit no geometry and need
        // no atlas — those are allowed through.
        boolean isBlock = true;
        RenderType layer;
        if (state.getBlock() instanceof LiquidBlock) {
            layer = ItemBlockRenderTypes.getRenderLayer(state.getFluidState());
            isBlock = false;
        } else {
            if (state.getBlock() instanceof LeavesBlock) {
                layer = RenderType.solid();
            } else {
                layer = ItemBlockRenderTypes.getChunkRenderType(state);
            }
        }
        boolean blending = layer == RenderType.translucent();

        final int FACE_SIZE = this.size * this.size;

        if (isBlock) {
            this.vc.reset();
            this.bakeBlockModel(state, layer);
            boolean empty = this.vc.isEmpty();
            if (!empty && !this.textureLoaded) {
                throw new IllegalStateException("Atlas not loaded; call setupTexture() on the render thread first");
            }
            for (int i = 0; i < 6; i++) {
                this.rasterizer.setFaceCull(i == 1 || i == 2 || i == 4);
                this.rasterizer.clear();
                if (!empty) {
                    this.rasterizer.setBlending(blending);
                    this.rasterizer.raster(VIEWS[i], this.vc);
                }
                writeFace(outputBuffer + (long) FACE_SIZE * 8 * i, this.rasterizer.getRawFramebuffer(), FACE_SIZE);
            }
        } else {
            if (!(state.getBlock() instanceof LiquidBlock)) throw new IllegalStateException();
            if (!this.textureLoaded) {
                throw new IllegalStateException("Atlas not loaded; call setupTexture() on the render thread first");
            }
            for (int i = 0; i < 6; i++) {
                this.rasterizer.setFaceCull(i == 1 || i == 2 || i == 4);
                this.rasterizer.clear();
                this.vc.reset();
                this.bakeFluidState(state, layer, i);
                if (!this.vc.isEmpty()) {
                    this.rasterizer.setBlending(blending);
                    this.rasterizer.raster(VIEWS[i], this.vc);
                }
                writeFace(outputBuffer + (long) FACE_SIZE * 8 * i, this.rasterizer.getRawFramebuffer(), FACE_SIZE);
            }
        }
    }

    // Transcode the rasterizer's packed framebuffer longs into our [colour][depth] pixel format
    private static void writeFace(long out, long[] framebuffer, int faceSize) {
        for (int i = 0; i < faceSize; i++) {
            long v = framebuffer[i];
            int colour = (int) v;//low 32 = ABGR
            int written = (int) ((v >>> 32) & 0x7F);//stencil count (low 7 bits; bit 39 is tint)
            int tint = (int) ((v >>> 39) & 1);
            int depth24 = (int) ((v >>> 40) & 0xFFFFFF);
            int depthInt = (depth24 << 8) | (written) | (tint << 7);
            UnsafeUtil.memPutInt(out + (long) i * 8, colour);
            UnsafeUtil.memPutInt(out + (long) i * 8 + 4, depthInt);
        }
    }

    static {
        //the face/direction is the face (e.g. down is the down face)
        addView(0, -90, 0, 0, 0);//Direction.DOWN
        addView(1, 90, 0, 0, 0b100);//Direction.UP
        addView(2, 0, 180, 0, 0b001);//Direction.NORTH
        addView(3, 0, 0, 0, 0);//Direction.SOUTH
        addView(4, 0, 90, 270, 0b100);//Direction.WEST
        addView(5, 0, 270, 270, 0);//Direction.EAST
    }

    private static void addView(int i, float pitch, float yaw, float rotation, int flip) {
        var stack = new PoseStack();
        stack.translate(0.5f, 0.5f, 0.5f);
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0, 0, 1), rotation));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(1, 0, 0), pitch));
        stack.mulPose(makeQuatFromAxisExact(new Vector3f(0, 1, 0), yaw));
        stack.mulPose(new Matrix4f().scale(1 - 2 * (flip & 1), 1 - (flip & 2), 1 - ((flip >> 1) & 2)));
        stack.translate(-0.5f, -0.5f, -0.5f);
        var mat = new Matrix4f(stack.last().pose());

        // Projection baked into the view. Use the GL ModelTextureBakery projection
        // (m22=-1, no z translation): the rasterizer applies z=fma(z,0.5,0.5), which
        // replicates GL's [0,1] depth-range mapping, so depth values match the old GL
        // path exactly and downstream computeDepth/face-offset stays calibrated.
        // (Upstream's -2/1 projection would double the depth range and shift LOD geometry.)
        mat = new Matrix4f().set(
                        2, 0, 0, 0,
                        0, 2, 0, 0,
                        0, 0, -1, 0,
                        -1, -1, 0, 1)
                .mul(mat);
        VIEWS[i] = mat;
    }

    private static Quaternionf makeQuatFromAxisExact(Vector3f vec, float angle) {
        angle = (float) Math.toRadians(angle);
        float hangle = angle / 2.0f;
        float sinAngle = (float) Math.sin(hangle);
        float invVLength = (float) (1 / Math.sqrt(vec.lengthSquared()));
        return new Quaternionf(vec.x * invVLength * sinAngle,
                vec.y * invVLength * sinAngle,
                vec.z * invVLength * sinAngle,
                Math.cos(hangle));
    }
}
