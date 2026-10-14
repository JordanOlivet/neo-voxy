package me.cortex.voxy.common.mixin.chunky;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.popcraft.chunky.mixin.ServerChunkCacheMixin;
import org.popcraft.chunky.platform.FabricWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.CompletableFuture;

@Mixin(FabricWorld.class)
public class MixinFabricWorld {
    // Wrap the chunk future Chunky 1.4.23 (Fabric) requests through its own invoker
    // mixin inside getChunkAtAsync, so each generated chunk is funnelled into Voxy's
    // ingest pipeline (same behaviour as MixinNeoForgeWorld).
    @WrapOperation(
            method = "getChunkAtAsync",
            at = @At(value = "INVOKE", target = "Lorg/popcraft/chunky/mixin/ServerChunkCacheMixin;invokeGetChunkFutureMainThread(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<ChunkResult<ChunkAccess>> voxy$captureGeneratedChunk(ServerChunkCacheMixin instance, int i, int j, ChunkStatus chunkStatus, boolean b, Operation<CompletableFuture<ChunkResult<ChunkAccess>>> original) {
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
