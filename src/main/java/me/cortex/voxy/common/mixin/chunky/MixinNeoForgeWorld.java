package me.cortex.voxy.common.mixin.chunky;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.popcraft.chunky.platform.NeoForgeWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.CompletableFuture;

@Mixin(value = NeoForgeWorld.class, remap = false)
public class MixinNeoForgeWorld {
    // Wrap the inner getChunkFutureMainThread call inside Chunky's getChunkAtAsync so we can
    // funnel each generated chunk into Voxy's ingest pipeline. Chunky 1.4.23 (NeoForge) calls
    // ServerChunkCache#getChunkFutureMainThread directly, no longer via its own invoker mixin.
    @WrapOperation(
            method = "getChunkAtAsync",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache;getChunkFutureMainThread(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;", remap = true))
    private CompletableFuture<ChunkResult<ChunkAccess>> voxy$captureGeneratedChunk(ServerChunkCache instance, int i, int j, ChunkStatus chunkStatus, boolean b, Operation<CompletableFuture<ChunkResult<ChunkAccess>>> original) {
        var future = original.call(instance, i, j, chunkStatus, b);
        //TODO: gate behind a server-side ingest config flag instead of always-on
        return future.thenApply(res -> {
            res.ifSuccess(chunk -> {
                if (chunk instanceof LevelChunk worldChunk) {
                    VoxelIngestService.tryAutoIngestChunk(worldChunk);
                }
            });
            return res;
        });
    }
}
