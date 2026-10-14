package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.ICheekyClientChunkCache;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.common.util.ModLoaderUtil;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderSectionManager.class, remap = false)
public class MixinRenderSectionManager {
    // When Bobby is present, chunk-removal ingest is handled by MixinClientChunkCache.drop
    // instead (Bobby keeps the chunk data accessible later than this hook), so we
    // skip the standard path here to avoid double-ingest.
    @Unique
    private static final boolean BOBBY_INSTALLED = ModLoaderUtil.isModLoaded("bobby");

    @Shadow
    @Final
    private ClientLevel level;

    @Shadow
    @Final
    private ChunkBuilder builder;

    // There is deliberately no hook on the constructor here. Sodium changed its
    // signature between 0.6.x (ClientLevel, int, CommandList) and 0.8.x
    // (ClientLevel, int, SortBehavior, CommandList), and remapJar bakes the
    // compile-time descriptor into a bare "<init>" selector, which pins the mixin
    // to whichever Sodium it was built against. The chunk tracker is reset from
    // MixinSodiumWorldRenderer#initRenderer instead — that is the method which
    // constructs the RenderSectionManager, and its descriptor is identical across
    // both branches.

    @Inject(method = "onChunkRemoved", at = @At("HEAD"))
    private void injectIngest(int x, int z, CallbackInfo ci) {
        // TODO: Am not quite sure if this is right
        if (VoxyConfig.CONFIG.ingestEnabled && !BOBBY_INSTALLED) {
            var cccm = (ICheekyClientChunkCache) this.level.getChunkSource();
            if (cccm != null) {
                var chunk = cccm.voxy$cheekyGetChunk(x, z);
                if (chunk != null) {
                    VoxelIngestService.tryAutoIngestChunk(chunk);
                }
            }
        }
    }

    @Inject(method = "onChunkAdded", at = @At("HEAD"))
    private void voxy$ingestOnAdd(int x, int z, CallbackInfo ci) {
        if (this.level.levelRenderer != null && VoxyConfig.CONFIG.ingestEnabled) {
            var cccm = this.level.getChunkSource();
            if (cccm != null) {
                var chunk = cccm.getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk != null) {
                    VoxelIngestService.tryAutoIngestChunk(chunk);
                }
            }
        }
    }

    /*
     * @Inject(method = "onChunkRemoved", at = @At("HEAD"))
     * private void voxy$trackChunkRemove(int x, int z, CallbackInfo ci) {
     * if (this.level.worldRenderer != null) {
     * var system =
     * ((IGetVoxyRenderSystem)(this.level.worldRenderer)).getVoxyRenderSystem();
     * if (system != null) {
     * system.chunkBoundRenderer.removeSection(ChunkPos.toLong(x, z));
     * }
     * }
     * }
     */

    @Unique
    private long cachedChunkPos = -1;
    @Unique
    private int cachedChunkStatus;

    @Redirect(method = "updateSectionInfo", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;setInfo(Lnet/caffeinemc/mods/sodium/client/render/chunk/data/BuiltSectionInfo;)Z"))
    private boolean voxy$updateOnUpload(RenderSection instance, BuiltSectionInfo info) {
        boolean wasBuilt = instance.getFlags() != 0;
        int flags = instance.getFlags();
        if (!instance.setInfo(info)) {
            return false;
        }
        if (wasBuilt == (instance.getFlags() != 0)) {// Only want to do stuff on change
            return true;
        }

        flags |= instance.getFlags();
        if (flags == 0) {// Only process things with stuff
            return true;
        }

        VoxyRenderSystem system = ((IGetVoxyRenderSystem) (this.level.levelRenderer)).getVoxyRenderSystem();
        if (system == null) {
            return true;
        }
        int x = instance.getChunkX(), y = instance.getChunkY(), z = instance.getChunkZ();

        if (wasBuilt && VoxyConfig.CONFIG.ingestEnabled) {
            var tracker = ((AccessorChunkTracker) ChunkTrackerHolder.get(this.level)).getChunkStatus();
            // in theory the cache value could be wrong but is so soso unlikely and at worst
            // means we either duplicate ingest a chunk
            // which... could be bad ;-; or we dont ingest atall which is ok!
            long key = ChunkPos.asLong(x, z);
            if (key != this.cachedChunkPos) {
                this.cachedChunkPos = key;
                this.cachedChunkStatus = tracker.getOrDefault(key, 0);
            }
            if (this.cachedChunkStatus == 3) {// If this chunk still has surrounding chunks
                // Only ingest if the chunk exists at FULL status; during respawn or
                // teleport transitions the blind getChunk could hand back wrong data
                var chunk = this.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk != null) {
                    // Cheap enough to derive per upload, and avoids caching level
                    // geometry in a field that only a constructor hook could fill.
                    int bottomSectionY = this.level.getMinBuildHeight() >> 4;
                    var section = chunk.getSection(y - bottomSectionY);
                    var lp = this.level.getLightEngine();

                    var csp = SectionPos.of(x, y, z);
                    var blp = lp.getLayerListener(LightLayer.BLOCK).getDataLayerData(csp);
                    var slp = lp.getLayerListener(LightLayer.SKY).getDataLayerData(csp);

                    // Note: we dont do this check and just blindly ingest, it shouldbe ok :tm:
                    // if (blp != null || slp != null)
                    VoxelIngestService.rawIngest(system.getEngine(), section, x, y, z, blp == null ? null : blp.copy(),
                            slp == null ? null : slp.copy());
                }
            }
        }

        // Do some very cheeky stuff for MiB
        if (VoxyCommon.IS_MINE_IN_ABYSS) {
            int sector = (x + 512) >> 10;
            x -= sector << 10;
            y += 16 + (256 - 32 - sector * 30);
        }
        long pos = SectionPos.asLong(x, y, z);
        if (wasBuilt) {// Remove
            // TODO: on chunk remove do ingest if is surrounded by built chunks (or when the
            // tracker says is ok)

            system.chunkBoundRenderer.removeSection(pos);
        } else {// Add
            system.chunkBoundRenderer.addSection(pos);
        }
        return true;
    }
}
