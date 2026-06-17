package me.cortex.voxy.client.core.model.bakery;

import me.cortex.voxy.client.core.model.ModelFactory;
import net.caffeinemc.mods.sodium.api.util.ColorABGR;
import net.caffeinemc.mods.sodium.api.util.ColorARGB;
import net.caffeinemc.mods.sodium.api.util.ColorMixer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;
import java.util.Random;

public class SoftwareRasterizer {
    private final Vector4f scratch = new Vector4f();

    private final Vector3f scratch1 = new Vector3f();
    private final Vector3f scratch2 = new Vector3f();
    private final Vector3f scratch3 = new Vector3f();
    private final Vector3f scratch4 = new Vector3f();
    //quad meta uv
    private final Vector3f qmuv1 = new Vector3f();
    private final Vector3f qmuv2 = new Vector3f();
    private final Vector3f qmuv3 = new Vector3f();
    private final Vector3f qmuv4 = new Vector3f();


    private final Vector3f scratchR1 = new Vector3f();
    private final Vector3f scratchR2 = new Vector3f();
    private final Vector3f scratchR3 = new Vector3f();
    //Attributes (meta, u, v)
    private final Vector3f a1 = new Vector3f();
    private final Vector3f a2 = new Vector3f();
    private final Vector3f a3 = new Vector3f();

    private static final long DEPTH_MASK = ((1L<<24)-1)<<(64-24);
    private static final long CLEAR_VALUE = DEPTH_MASK;//set the depth to max value and rest of bits to 0

    private final int targetSize;
    private final long[] framebuffer;

    private boolean cullBackFace;
    private boolean doTheBlending;

    private int samplerWidth;
    private int samplerHeight;
    private int[] samplerTexture;

    // Per-quad sampling params (set in rasterQuad). We area-average mip 0 over each
    // output pixel's footprint, clamped to the quad's own sprite texels. This is a
    // clean mipmap-like downscale that does NOT use MC's atlas mips, whose edge
    // texels are bled with neighbouring sprites and produced stray (e.g. sky-blue)
    // pixels on opaque LOD blocks. Footprint 1 = nearest (non-mipped/cutout = sharp).
    private int curFootU, curFootV;
    private int curSu0, curSu1, curSv0, curSv1;

    public SoftwareRasterizer(int targetSize) {
        this.targetSize = targetSize;
        this.framebuffer = new long[targetSize*targetSize];
    }

    public void setFaceCull(boolean isBackFaceCulling) {
        this.cullBackFace = isBackFaceCulling;
    }

    public void setBlending(boolean blending) {
        this.doTheBlending = blending;
    }

    public void setSamplerTexture(int[] texture, int width, int height) {
        if (texture.length != width*height) throw new IllegalArgumentException();
        this.samplerTexture = texture;
        this.samplerWidth = width;
        this.samplerHeight = height;
    }

    // Compute per-quad sampling params: the sprite's texel bounds (so we never read
    // adjacent sprites) and the per-output-pixel footprint for area averaging.
    // forcedMip0 (non-mipped/cutout) -> footprint 1 = sharp nearest.
    private void selectSampleParamsForQuad(boolean forcedMip0) {
        int w = this.samplerWidth, h = this.samplerHeight;
        float minU = min4(this.qmuv1.y, this.qmuv2.y, this.qmuv3.y, this.qmuv4.y);
        float maxU = max4(this.qmuv1.y, this.qmuv2.y, this.qmuv3.y, this.qmuv4.y);
        float minV = min4(this.qmuv1.z, this.qmuv2.z, this.qmuv3.z, this.qmuv4.z);
        float maxV = max4(this.qmuv1.z, this.qmuv2.z, this.qmuv3.z, this.qmuv4.z);
        this.curSu0 = Math.clamp((int) Math.floor(minU * w), 0, w - 1);
        this.curSu1 = Math.clamp((int) Math.ceil(maxU * w) - 1, this.curSu0, w - 1);
        this.curSv0 = Math.clamp((int) Math.floor(minV * h), 0, h - 1);
        this.curSv1 = Math.clamp((int) Math.ceil(maxV * h) - 1, this.curSv0, h - 1);
        if (forcedMip0) {
            this.curFootU = 1;
            this.curFootV = 1;
            return;
        }
        int spanU = this.curSu1 - this.curSu0 + 1;
        int spanV = this.curSv1 - this.curSv0 + 1;
        this.curFootU = Math.max(1, Math.round((float) spanU / this.targetSize));
        this.curFootV = Math.max(1, Math.round((float) spanV / this.targetSize));
    }

    private static float min4(float a, float b, float c, float d) { return Math.min(Math.min(a, b), Math.min(c, d)); }
    private static float max4(float a, float b, float c, float d) { return Math.max(Math.max(a, b), Math.max(c, d)); }

    // Area-average mip 0 over the output pixel's footprint, clamped to the sprite's
    // own texels. Averaging fills cutout/leaf holes; clamping + mip-0-only keeps
    // opaque blocks clean (no neighbour-sprite/mip bleed).
    private int sampleTexture(float u, float v) {
        int w = this.samplerWidth;
        int cu = Math.round(u*w - 0.5f);
        int cv = Math.round(v*this.samplerHeight - 0.5f);
        int fu = this.curFootU, fv = this.curFootV;
        if (fu <= 1 && fv <= 1) {
            int pu = Math.clamp(cu, this.curSu0, this.curSu1);
            int pv = Math.clamp(cv, this.curSv0, this.curSv1);
            return this.samplerTexture[w*pv+pu];
        }
        int startU = cu - fu/2, startV = cv - fv/2;
        // Alpha-weighted (premultiplied) RGB average: transparent texels must NOT
        // drag the colour toward black, otherwise foliage looks dark/over-heavy.
        // Alpha is a straight average so leaves keep their natural see-through gaps
        // (aeration). Matches MC's atlas mip generation.
        long sumR = 0, sumG = 0, sumB = 0, sumA = 0, sumW = 0;
        int n = 0;
        for (int dv = 0; dv < fv; dv++) {
            int ty = Math.clamp(startV + dv, this.curSv0, this.curSv1);
            for (int du = 0; du < fu; du++) {
                int tx = Math.clamp(startU + du, this.curSu0, this.curSu1);
                int c = this.samplerTexture[w*ty + tx];
                int a = (c >>> 24) & 0xFF;
                sumR += (long) (c & 0xFF) * a;
                sumG += (long) ((c >> 8) & 0xFF) * a;
                sumB += (long) ((c >> 16) & 0xFF) * a;
                sumA += a;
                sumW += a;
                n++;
            }
        }
        int a = (int) (sumA / n);
        int r, g, b;
        if (sumW > 0) {
            r = (int) (sumR / sumW);
            g = (int) (sumG / sumW);
            b = (int) (sumB / sumW);
        } else {
            r = 0; g = 0; b = 0;
        }
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    public void clear() {
        Arrays.fill(this.framebuffer, CLEAR_VALUE);
    }

    public void raster(Matrix4f mvp, ReuseVertexConsumer vertices) {
        this.raster(mvp, vertices.getAddress(), vertices.quadCount());
    }
    public void raster(Matrix4f mvp, long verticesAddr, int quadCount) {
        if (quadCount == 0) return;
        for (int i = 0; i < quadCount; i++) {
            this.rasterQuad(mvp, verticesAddr+ReuseVertexConsumer.VERTEX_FORMAT_SIZE*4L*i);
        }
        //Arrays.fill(this.framebuffer, -1);
    }

    private void rasterQuad(Matrix4f transform, long addr) {
        loadTransformPos(transform, addr, 0, this.scratch1, this.qmuv1);
        loadTransformPos(transform, addr, 1, this.scratch2, this.qmuv2);
        loadTransformPos(transform, addr, 2, this.scratch3, this.qmuv3);
        loadTransformPos(transform, addr, 3, this.scratch4, this.qmuv4);

        // Select the atlas mip for this quad. meta bit 1 = "mipped"; a non-mipped
        // face forces mip 0 (sharp), matching the bake shader's -16 LOD bias.
        int meta = Float.floatToRawIntBits(this.qmuv1.x);
        boolean forcedMip0 = ((meta >> 1) & 1) == 0;
        this.selectSampleParamsForQuad(forcedMip0);

        //0,1,2 | 2,3,0
        this.scratchR1.set(this.scratch1);
        this.scratchR2.set(this.scratch2);
        this.scratchR3.set(this.scratch3);
        this.a1.set(this.qmuv1);
        this.a2.set(this.qmuv2);
        this.a3.set(this.qmuv3);
        this.rasterTriangle(false);
        this.scratchR1.set(this.scratch3);
        this.scratchR2.set(this.scratch4);
        this.scratchR3.set(this.scratch1);
        this.a1.set(this.qmuv3);
        this.a2.set(this.qmuv4);
        this.a3.set(this.qmuv1);
        this.rasterTriangle(true);
    }

    private void rasterTriangle(boolean orZero) {
        Vector3f v1 = this.scratchR1;
        Vector3f v2 = this.scratchR2;
        Vector3f v3 = this.scratchR3;


        float area = edge(v1, v2, v3);

        //Pretty sure this is how you check for winding order aswell (if area is negative its counterclockwise)
        if (area<0 == this.cullBackFace) {
            return;
        }

        if (Math.abs(area)<0.001) {
            return;//Degenerate triangle
        }

        //TODO: check this is right?
        /*
        if (area < 0) {
            var t = v1;
            v1 = v2;
            v2 = t;
            area = -area;
        }*/

        int minX = Math.max((int) Math.floor(Math.min(Math.min(v1.x, v2.x), v3.x)), 0);
        int maxX = Math.min((int) Math.ceil(Math.max(Math.max(v1.x, v2.x), v3.x)), this.targetSize-1);
        int minY = Math.max((int) Math.floor(Math.min(Math.min(v1.y, v2.y), v3.y)), 0);
        int maxY = Math.min((int) Math.ceil(Math.max(Math.max(v1.y, v2.y), v3.y)), this.targetSize-1);

        float invArea = 1.0f/area;
        for (int py = minY; py<=maxY; py++) {
            for (int px = minX; px<=maxX; px++) {
                float cx = px+0.5f;
                float cy = py+0.5f;
                float w1 = edge(v2, v3, cx, cy)*invArea;
                float w2 = edge(v3, v1, cx, cy)*invArea;
                float w3 = 1.0f-w1-w2;
                // The second triangle of each quad fills the shared diagonal. Use a
                // small epsilon: at MODEL_TEXTURE_SIZE=8 the diagonal pixels land
                // exactly on the shared edge and float error pushed the barycentric
                // slightly negative, leaving a 1px diagonal of unwritten (transparent)
                // pixels through every face — very visible at 8px (see-through blocks).
                final float EDGE_EPS = 1.0e-4f;
                if ((w1>0.0f&&w2>0.0f&&w3>0.0f)||(orZero&&w1>=-EDGE_EPS&&w2>=-EDGE_EPS&&w3>=-EDGE_EPS)) {
                    //Dont need to worry about perspective correction afak as it should already be all correct

                    //pixel is inside the triangle
                    this.rasterPixel(px+py*this.targetSize, w1, w2, w3);
                }
            }
        }
    }

    private void rasterPixel(int index, float b1, float b2, float b3) {//Barry coords
        float z = Math.fma(b1, this.scratchR1.z, Math.fma(b2, this.scratchR2.z, b3 * this.scratchR3.z));
        z = Math.fma(z,0.5f,0.5f);
        if (z<0.0f && -0.000001f<=z) z = 0;//Clamp to 0 if its really small negative
        if (z<0.0f||z>1.0f)
            return;//TODO: check this



        int meta = Float.floatToRawIntBits(this.a1.x);
        float u = Math.fma(b1, this.a1.y, Math.fma(b2, this.a2.y, b3 * this.a3.y));
        float v = Math.fma(b1, this.a1.z, Math.fma(b2, this.a2.z, b3 * this.a3.z));

        int colour = this.sampleTexture(u,v);//The ABGR colour of this pixel


        final int ALPHA_CUTOFF_THRESHOLD = 0;
        //TODO: meta&1 OR if we are blending
        if ((meta&1)!=0 && (colour>>>24)<=ALPHA_CUTOFF_THRESHOLD) {//Discard on small alpha
            return;
        }

        //Stencil increment first
        this.framebuffer[index] += (1L<<32);

        //Funny jank depth test
        long depthVal = ((long) (((double)z)*((1<<24)-1)))<<(64-24);
        if (depthVal == DEPTH_MASK) depthVal--;//We wanto render _something_ at least
        if (Long.compareUnsigned(this.framebuffer[index],depthVal)<=0) {
            return;//Depth test failed, (using a strictly LESS_THAN comparison)
        }
        //Set the pixels depth value
        this.framebuffer[index] &= ~DEPTH_MASK;
        this.framebuffer[index] |= depthVal;

        //set the metadata bit
        this.framebuffer[index] &= ~(1L<<39);
        this.framebuffer[index] |= ((long)(meta&4))<<37;

        int srcColour = (int) this.framebuffer[index];
        this.framebuffer[index] &= ~Integer.toUnsignedLong(-1);

        if (this.doTheBlending) {//Blending
            //mutate colour var
            colour = doBlending(srcColour, colour);
        }


        //Remember ABGR FORMAT
        this.framebuffer[index] |= Integer.toUnsignedLong(colour);
    }


    // ARBDrawBuffersBlend.glBlendFuncSeparateiARB(0, GL_ONE_MINUS_DST_ALPHA, GL_DST_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    private static int doBlending(int scr, int dst) {
        int srcAlpha = (scr>>>24)&0xFF;
        if (srcAlpha == 0) {
            return dst;
        }
        int dstAlpha = (dst>>>24)&0xFF;
        scr &= ~(0xFF<<24);
        dst &= ~(0xFF<<24);
        int blendAlpha = Math.min(0xFF,srcAlpha+((dstAlpha*(255-srcAlpha))>>8));
        //how much did we actually get

        int blend = ColorMixer.mix(dst, scr, dstAlpha);//addRGB(ColorABGR.mulRGB(scr, 255-dstAlpha),ColorABGR.mulRGB(dst, dstAlpha));
        return blend|(blendAlpha<<24);
    }

    private static int addRGB(int a, int b) {
        return Math.min(0xFF,(a&0xFF)+(b&0xFF))|
                Math.min((0xFF<<8),(a&(0xFF<<8))+(b&(0xFF<<8)))|
                Math.min((0xFF<<16),(a&(0xFF<<16))+(b&(0xFF<<16)));
    }

    private static float edge(Vector3f a, Vector3f b, Vector3f c) {
        return (c.x-a.x)*(b.y-a.y) - (c.y-a.y) * (b.x-a.x);
    }

    private static float edge(Vector3f a, Vector3f b, float cx, float cy) {
        return (cx-a.x)*(b.y-a.y) - (cy-a.y) * (b.x-a.x);
    }


    private void loadTransformPos(Matrix4f transform, long addr, int vert, Vector3f out, Vector3f otherAttributesOut) {
        this.scratch.setFromAddress(addr+vert*ReuseVertexConsumer.VERTEX_FORMAT_SIZE);
        otherAttributesOut.setFromAddress(addr+vert*ReuseVertexConsumer.VERTEX_FORMAT_SIZE+3*4);
        this.scratch.w = 1.0f;
        var vec = transform.transformProject(this.scratch);
        if (Math.abs(this.scratch.w-1.0f)>0.000001f)
            throw new IllegalStateException();
        out.set(maintainPrecision(Math.fma(vec.x, 0.5f, 0.5f)*this.targetSize), maintainPrecision(Math.fma(vec.y, 0.5f, 0.5f)*this.targetSize), vec.z);//TODO: dont know if z transform is correct
    }


    private static float maintainPrecision(float x) {
        return x;//TODO: value snapping in screenspace if needed
    }


    public long[] getRawFramebuffer() {
        return this.framebuffer;
    }
}
