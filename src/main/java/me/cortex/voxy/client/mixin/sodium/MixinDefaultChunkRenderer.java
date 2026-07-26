package me.cortex.voxy.client.mixin.sodium;

import com.llamalad7.mixinextras.sugar.Local;

import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;

import net.caffeinemc.mods.sodium.client.gl.device.RenderDevice;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class MixinDefaultChunkRenderer extends ShaderChunkRenderer {

    public MixinDefaultChunkRenderer(RenderDevice device, ChunkVertexType vertexType) {
        super(device, vertexType);
    }

    // The target arguments are pulled in with @Local(argsOnly) rather than declared
    // on the handler: Sodium 0.8.x appended a trailing boolean to render(), so a
    // literal argument list would only ever match a single Sodium branch. Matching
    // by type keeps both 0.6.x and 0.8.x working.
    @Inject(method = "render", at = @At(value = "HEAD"), cancellable = true)
    private void cancelThingie(CallbackInfo ci,
            @Local(argsOnly = true) ChunkRenderMatrices matrices,
            @Local(argsOnly = true) TerrainRenderPass renderPass,
            @Local(argsOnly = true) CameraTransform camera) {
        if (VoxyClient.disableSodiumChunkRender()) {
            super.begin(renderPass);
            this.doRender(matrices, renderPass, camera);
            super.end(renderPass);
            ci.cancel();
        }
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer;end(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V", shift = At.Shift.BEFORE))
    private void injectRender(CallbackInfo ci,
            @Local(argsOnly = true) ChunkRenderMatrices matrices,
            @Local(argsOnly = true) TerrainRenderPass renderPass,
            @Local(argsOnly = true) CameraTransform camera) {
        this.doRender(matrices, renderPass, camera);
    }

    @Unique
    private void doRender(ChunkRenderMatrices matrices, TerrainRenderPass renderPass, CameraTransform camera) {
        // Match origine voxy 12111 branch: only CUTOUT pass triggers Voxy LOD render.
        // TRANSLUCENT pass NOT intercepted — origine renders LOD translucent inline
        // during runPipeline, then vanilla translucent draws normally on top via Sodium.
        // Our previous TRANSLUCENT branch (calling blitOverTranslucent) caused vanilla
        // water/ice to be overwritten by GL_ALWAYS re-blit of Voxy framebuffer.
        if (renderPass == DefaultTerrainRenderPasses.CUTOUT) {
            var renderer = ((IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer).getVoxyRenderSystem();
            if (renderer != null) {
                me.cortex.voxy.client.core.rendering.Viewport<?> viewport = renderer.setupViewport(matrices, camera.x,
                        camera.y, camera.z);
                renderer.renderOpaque(viewport);
            }
        }
    }
}
