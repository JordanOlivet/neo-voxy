package me.cortex.voxy.client.core.model;

import me.cortex.voxy.client.core.RenderResourceReuse;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.stb.STBImageWrite;
import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL11C.GL_NEAREST_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_MAX_LOD;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_MIN_LOD;
import static org.lwjgl.opengl.GL30.glBindBufferBase;
import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.opengl.GL33C.glSamplerParameteri;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL45.glBindTextureUnit;
import static org.lwjgl.opengl.GL45C.glGetTextureImage;

public class ModelStore {
    public static final int MODEL_SIZE = 64;
    final GlBuffer modelBuffer;
    final GlBuffer modelColourBuffer;
    final GlTexture textures;
    public final int blockSampler = glGenSamplers();

    public ModelStore() {
        this.modelBuffer = new GlBuffer(MODEL_SIZE * (1<<16)).name("ModelData");
        this.modelColourBuffer = new GlBuffer(4 * (1<<16)).name("ModelColour");
        this.textures = RenderResourceReuse.getOrCreateModelStoreTextureAtlas();


        //Limit the mips of the texture to match that of the terrain atlas
        int mipLvl = ((TextureAtlas) Minecraft.getInstance().getTextureManager()
                .getTexture(ResourceLocation.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png")))
                .mipLevel;

        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MIN_LOD, 0);
        glSamplerParameteri(this.blockSampler, GL_TEXTURE_MAX_LOD, mipLvl);//Integer.numberOfTrailingZeros(ModelFactory.MODEL_TEXTURE_SIZE)
    }


    public void free() {
        this.modelBuffer.free();
        this.modelColourBuffer.free();
        RenderResourceReuse.giveBackModelStoreTextureAtlas(this.textures);
        glDeleteSamplers(this.blockSampler);
    }


    public void bind(int modelBindingIndex, int colourBindingIndex, int textureBindingIndex) {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, modelBindingIndex, this.modelBuffer.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, colourBindingIndex, this.modelColourBuffer.id);
        glBindTextureUnit(textureBindingIndex, this.textures.id);
        glBindSampler(textureBindingIndex, this.blockSampler);
    }

    // Dumps mip 0 of the model atlas to a PNG. Used to verify that translucent
    // blocks (ice, glass) preserve their source-asset alpha through the GPU bake.
    public boolean dumpAtlasToPng(File out) {
        int w = this.textures.getWidth();
        int h = this.textures.getHeight();
        long bytes = (long) w * h * 4L;
        ByteBuffer buf = MemoryUtil.memAlloc((int) bytes);
        try {
            glGetTextureImage(this.textures.id, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
            File parent = out.getParentFile();
            if (parent != null) parent.mkdirs();
            boolean ok = STBImageWrite.stbi_write_png(out.getAbsolutePath(), w, h, 4, buf, w * 4);
            if (!ok) {
                Logger.error("stbi_write_png failed for " + out.getAbsolutePath());
            }
            return ok;
        } finally {
            MemoryUtil.memFree(buf);
        }
    }
}
