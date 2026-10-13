package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.ICheekyClientChunkCache;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.util.ModLoaderUtil;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientChunkCache.class)
public class MixinClientChunkCache implements ICheekyClientChunkCache {
    // Bobby keeps unloaded chunks alive longer; when present we ingest at "drop"
    // time (here) instead of at chunk removal in MixinRenderSectionManager,
    // because the chunk data is still accessible at this point with Bobby.
    @Unique
    private static final boolean BOBBY_INSTALLED = ModLoaderUtil.isModLoaded("bobby");

    @Shadow
    volatile ClientChunkCache.Storage storage;

    @Override
    public LevelChunk voxy$cheekyGetChunk(int x, int z) {
        // This doesnt do the in range check stuff, it just gets the chunk at all costs
        var chunk = this.storage.getChunk(this.storage.getIndex(x, z));
        if (chunk == null) {
            return null;
        }
        // Verify the chunk is at the requested position: the storage ring buffer can
        // hold a different chunk at the same index (e.g. after a death/respawn far
        // away), which used to get ingested at the wrong place
        if (chunk.getPos().x == x && chunk.getPos().z == z) {
            return chunk;
        }
        return null;
    }

    @Inject(method = "drop", at = @At("HEAD"))
    public void voxy$captureChunkBeforeUnload(ChunkPos pos, CallbackInfo ci) {
        if (VoxyConfig.CONFIG.ingestEnabled && BOBBY_INSTALLED) {
            var chunk = this.voxy$cheekyGetChunk(pos.x, pos.z);
            if (chunk != null) {
                VoxelIngestService.tryAutoIngestChunk(chunk);
            }
        }
    }
}
