package me.cortex.voxy.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.service.LodStreamingService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Server-side admin commands for Neo-Voxy.
 * Provides commands for LOD generation, status checking, and management.
 * 
 * Commands:
 * - /voxyadmin status - Show WorldEngine status and metrics
 * - /voxyadmin keep <seconds> - Keep WorldEngine alive for specified duration
 * - /voxyadmin generate [radius] - Generate LODs around executor position
 * - /voxyadmin generate <radius> <x> <z> - Generate LODs around coordinates
 * - /voxyadmin cancel - Cancel current generation
 * - /voxyadmin broadcast - Send sync request to all connected players
 */
public class VoxyServerCommands {

    // Track processors per level
    private static final Map<ServerLevel, ChunkFileProcessor> processors = new HashMap<>();

    /**
     * Register server commands with the command dispatcher
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> voxyCommand = Commands.literal("voxyadmin")
                .requires(source -> source.hasPermission(2)) // Require op level 2

                // /voxyadmin status - Show status
                .then(Commands.literal("status")
                        .executes(context -> showStatus(context.getSource())))

                // /voxyadmin keep <seconds> - Keep WorldEngine alive
                .then(Commands.literal("keep")
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 3600))
                                .executes(context -> keepAlive(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "seconds")))))

                // /voxyadmin generate - Generate LODs around position
                .then(Commands.literal("generate")
                        .executes(context -> generateAroundPlayer(context.getSource(), 100))

                        // /voxyadmin generate <radius> - Generate LODs in radius around player
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 1000))
                                .executes(context -> generateAroundPlayer(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "radius")))

                                // /voxyadmin generate <radius> <x> <z> - Generate at specific coords
                                .then(Commands.argument("centerX", IntegerArgumentType.integer(-30000000, 30000000))
                                        .then(Commands
                                                .argument("centerZ", IntegerArgumentType.integer(-30000000, 30000000))
                                                .executes(context -> generateAtCoords(
                                                        context.getSource(),
                                                        IntegerArgumentType.getInteger(context, "radius"),
                                                        IntegerArgumentType.getInteger(context, "centerX"),
                                                        IntegerArgumentType.getInteger(context, "centerZ")))))))

                // /voxyadmin cancel - Cancel processing
                .then(Commands.literal("cancel")
                        .executes(context -> cancelProcessing(context.getSource())))

                // /voxyadmin broadcast - Broadcast sync to all players
                .then(Commands.literal("broadcast")
                        .executes(context -> broadcastSync(context.getSource())))

                // /voxyadmin regen <radiusChunks> - re-ingest currently-loaded chunks
                // around the source position. Useful when LODs are missing because
                // the original ChunkEvent.Load skipped (proto-chunk, no lighting yet).
                .then(Commands.literal("regen")
                        .then(Commands.argument("radiusChunks", IntegerArgumentType.integer(1, 1024))
                                .executes(context -> regenAroundSource(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "radiusChunks")))))

                // /voxyadmin resync - clear per-player lastSentVersion + ring origin
                // so every section currently in engine is resent to every connected
                // player. Does NOT re-ingest chunks; pair with /voxyadmin regen if
                // the engine itself is missing data.
                .then(Commands.literal("resync")
                        .executes(context -> resyncCurrentLevel(context.getSource())))

                // /voxyadmin diag [radiusChunks] - print per-section bucket counts
                // for sections around the caller. Helps diagnose "I see a hole here,
                // resync fixes it" cases by showing whether the streamer thinks the
                // section is already up-to-date when it actually is not.
                .then(Commands.literal("diag")
                        .executes(context -> diagSnapshot(context.getSource(), 16))
                        .then(Commands.argument("radiusChunks", IntegerArgumentType.integer(1, 256))
                                .executes(context -> diagSnapshot(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "radiusChunks")))))

                // /voxyadmin missing [radiusChunks] - list the chunk coordinates whose
                // voxy LOD-0 section is not in the engine at any Y. Tells you which
                // chunks the server never ingested when the diag shows engineMissing.
                .then(Commands.literal("missing")
                        .executes(context -> missingChunks(context.getSource(), 16))
                        .then(Commands.argument("radiusChunks", IntegerArgumentType.integer(1, 256))
                                .executes(context -> missingChunks(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "radiusChunks")))))

                // /voxyadmin checksection <lvl> <vx> <vy> <vz> - dump server-side
                // engine state of a specific voxy section (any LOD level). Reports
                // whether engine has the section, its block count, child-existence
                // mask, ingested-octant mask, and version. Use to confirm whether a
                // missing client-side LOD is caused by missing engine data, broken
                // mask propagation, or streaming dedup.
                .then(Commands.literal("checksection")
                        .then(Commands.argument("lvl", IntegerArgumentType.integer(0, 4))
                                .then(Commands.argument("vx", IntegerArgumentType.integer())
                                        .then(Commands.argument("vy", IntegerArgumentType.integer())
                                                .then(Commands.argument("vz", IntegerArgumentType.integer())
                                                        .executes(context -> checkSection(
                                                                context.getSource(),
                                                                IntegerArgumentType.getInteger(context, "lvl"),
                                                                IntegerArgumentType.getInteger(context, "vx"),
                                                                IntegerArgumentType.getInteger(context, "vy"),
                                                                IntegerArgumentType.getInteger(context, "vz"))))))))

                // /voxyadmin reload-config - re-read voxy-server-config.json from
                // disk and apply hot-reloadable fields (debug log toggles, etc).
                // Avoids needing a server restart for tweaking log verbosity.
                .then(Commands.literal("reload-config")
                        .executes(context -> reloadConfig(context.getSource())));

        dispatcher.register(voxyCommand);
        Logger.info("Registered VoxyAdmin server commands");
    }

    /**
     * Show status of WorldEngine and processing
     */
    private static int showStatus(CommandSourceStack source) {
        if (!(source.getLevel() instanceof ServerLevel serverLevel)) {
            source.sendFailure(Component.literal("This command must be run in a server world"));
            return 0;
        }

        WorldIdentifier worldId = WorldIdentifier.of(serverLevel);
        var instance = VoxyCommon.getInstance();

        source.sendSuccess(() -> Component.literal("=== NeoVoxy Server Status ==="), false);
        source.sendSuccess(() -> Component.literal("Level: " + serverLevel.dimension().location()), false);

        if (instance == null) {
            source.sendSuccess(() -> Component.literal("VoxyCommon: Not initialized"), false);
            return 1;
        }

        WorldEngine engine = instance.getNullable(worldId);
        if (engine == null) {
            source.sendSuccess(() -> Component.literal("WorldEngine: Not created (no LOD data yet)"), false);
        } else {
            source.sendSuccess(() -> Component.literal("WorldEngine: Active"), false);
            source.sendSuccess(() -> Component.literal("  Live: " + engine.isLive()), false);
            source.sendSuccess(() -> Component.literal("  Active sections: " + engine.getActiveSectionCount()), false);
            source.sendSuccess(() -> Component.literal("  World used: " + engine.isWorldUsed()), false);
            source.sendSuccess(() -> Component.literal("  World idle: " + engine.isWorldIdle()), false);
        }

        // Show processing status
        ChunkFileProcessor processor = processors.get(serverLevel);
        if (processor != null && processor.isProcessing()) {
            source.sendSuccess(() -> Component.literal("Processing: " + processor.getStatusString()), false);
        } else {
            source.sendSuccess(() -> Component.literal("Processing: Idle"), false);
        }

        return 1;
    }

    /**
     * Keep the WorldEngine alive for specified seconds
     */
    private static int keepAlive(CommandSourceStack source, int seconds) {
        if (!(source.getLevel() instanceof ServerLevel serverLevel)) {
            source.sendFailure(Component.literal("This command must be run in a server world"));
            return 0;
        }

        WorldIdentifier worldId = WorldIdentifier.of(serverLevel);
        var instance = VoxyCommon.getInstance();

        if (instance == null) {
            source.sendFailure(Component.literal("VoxyCommon not initialized"));
            return 0;
        }

        // Get or create the engine
        WorldEngine engine = instance.getOrCreate(worldId);
        if (engine == null) {
            source.sendFailure(Component.literal("Failed to create WorldEngine"));
            return 0;
        }

        // Keep the engine alive
        engine.keepAlive(seconds * 1000L);
        source.sendSuccess(() -> Component.literal("WorldEngine will be kept alive for " + seconds + " seconds"), true);

        return 1;
    }

    /**
     * Generate LODs around the command sender's position
     */
    private static int generateAroundPlayer(CommandSourceStack source, int radius) {
        BlockPos center = BlockPos.containing(source.getPosition());
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;

        return generateInRadius(source, radius, centerChunkX, centerChunkZ);
    }

    /**
     * Generate LODs at specific block coordinates
     */
    private static int generateAtCoords(CommandSourceStack source, int radius, int blockX, int blockZ) {
        int centerChunkX = blockX >> 4;
        int centerChunkZ = blockZ >> 4;

        return generateInRadius(source, radius, centerChunkX, centerChunkZ);
    }

    /**
     * Generate LODs within a radius of specified chunk coordinates
     */
    private static int generateInRadius(CommandSourceStack source, int radius, int centerChunkX, int centerChunkZ) {
        if (!(source.getLevel() instanceof ServerLevel serverLevel)) {
            source.sendFailure(Component.literal("This command must be run in a server world"));
            return 0;
        }

        ChunkFileProcessor processor = getOrCreateProcessor(serverLevel);

        if (processor.isProcessing()) {
            source.sendFailure(Component.literal(
                    "Processing already in progress. Use '/voxyadmin status' to check progress or '/voxyadmin cancel' to stop."));
            return 0;
        }

        int blockX = centerChunkX << 4;
        int blockZ = centerChunkZ << 4;
        int totalChunks = (2 * radius + 1) * (2 * radius + 1);

        source.sendSuccess(() -> Component.literal(
                "Starting LOD generation for " + totalChunks + " chunks around (" + blockX + ", " + blockZ + ")..."),
                true);

        processor.processChunksInRadius(centerChunkX, centerChunkZ, radius).thenRun(() -> {
            source.sendSuccess(() -> Component.literal("Finished: " + processor.getStatusString()), true);
        });

        return 1;
    }

    /**
     * Cancel current processing
     */
    private static int cancelProcessing(CommandSourceStack source) {
        if (!(source.getLevel() instanceof ServerLevel serverLevel)) {
            source.sendFailure(Component.literal("This command must be run in a server world"));
            return 0;
        }

        ChunkFileProcessor processor = processors.get(serverLevel);

        if (processor == null || !processor.isProcessing()) {
            source.sendFailure(Component.literal("No processing in progress to cancel"));
            return 0;
        }

        processor.cancel();
        source.sendSuccess(() -> Component.literal("Cancelling chunk processing..."), true);

        return 1;
    }

    /**
     * Broadcast sync request to all players in the level
     */
    private static int broadcastSync(CommandSourceStack source) {
        if (!(source.getLevel() instanceof ServerLevel serverLevel)) {
            source.sendFailure(Component.literal("This command must be run in a server world"));
            return 0;
        }

        Collection<ServerPlayer> players = serverLevel.players();
        if (players.isEmpty()) {
            source.sendFailure(Component.literal("No players in this level"));
            return 0;
        }

        VoxyServer.broadcastSync(serverLevel);
        source.sendSuccess(() -> Component.literal("Broadcast sync request to " + players.size() + " players"), true);

        return 1;
    }

    /**
     * Get or create a processor for the given level
     */
    private static ChunkFileProcessor getOrCreateProcessor(ServerLevel level) {
        return processors.computeIfAbsent(level, ChunkFileProcessor::new);
    }

    /**
     * Re-ingest every chunk currently loaded by the server in a radius (in
     * vanilla chunks) around the command source. This is the recovery path when
     * Voxy missed a chunk on its first {@code ChunkEvent.Load} (proto-chunk, no
     * lighting yet). Chunks that aren't loaded server-side are skipped silently
     * — they will be picked up by the normal event when they next load.
     */
    private static int regenAroundSource(CommandSourceStack source, int radiusChunks) {
        if (!(source.getLevel() instanceof ServerLevel serverLevel)) {
            source.sendFailure(Component.literal("This command must be run in a server world"));
            return 0;
        }
        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            source.sendFailure(Component.literal("VoxyCommon not initialized"));
            return 0;
        }
        WorldIdentifier worldId = WorldIdentifier.of(serverLevel);
        if (worldId == null) {
            source.sendFailure(Component.literal("WorldIdentifier null for this level"));
            return 0;
        }
        if (!instance.isIngestEnabled(worldId)) {
            source.sendFailure(Component.literal("Ingest disabled for this world"));
            return 0;
        }
        WorldEngine engine = instance.getOrCreate(worldId);
        if (engine == null) {
            source.sendFailure(Component.literal("Failed to obtain WorldEngine"));
            return 0;
        }

        BlockPos center = BlockPos.containing(source.getPosition());
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;

        int queued = 0;
        int notLoaded = 0;
        int enqueueRejected = 0;
        var chunkSource = serverLevel.getChunkSource();
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                int cx = centerChunkX + dx;
                int cz = centerChunkZ + dz;
                LevelChunk chunk = chunkSource.getChunkNow(cx, cz);
                if (chunk == null) {
                    notLoaded++;
                    continue;
                }
                try {
                    if (instance.getIngestService().enqueueIngest(engine, chunk)) {
                        queued++;
                    } else {
                        enqueueRejected++;
                    }
                } catch (Exception e) {
                    Logger.error("regen failed at " + chunk.getPos(), e);
                }
            }
        }
        final int qF = queued;
        final int nF = notLoaded;
        final int rF = enqueueRejected;
        source.sendSuccess(() -> Component.literal(
                "Regen: " + qF + " ingested, " + rF + " rejected (no lighting/proto/etc), " + nF +
                        " not currently loaded — radius=" + radiusChunks + "ch"), true);
        return qF;
    }

    /**
     * Print a streaming-state snapshot for the caller's surrounding sections.
     * Helps confirm whether the {@code lastSentVersion >= version} skip path is
     * the cause of "this LOD is missing on my client but the server has it".
     */
    private static int diagSnapshot(CommandSourceStack source, int radiusChunks) {
        if (!(source.getLevel() instanceof ServerLevel serverLevel)) {
            source.sendFailure(Component.literal("This command must be run in a server world"));
            return 0;
        }
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command must be run by a player"));
            return 0;
        }
        LodStreamingService service = VoxyServer.getStreamingService(serverLevel);
        if (service == null) {
            source.sendFailure(Component.literal("No streaming service for this level (no player has synced yet)"));
            return 0;
        }
        String report = service.diagPlayer(player, radiusChunks);
        source.sendSuccess(() -> Component.literal(report), true);
        return 1;
    }

    /**
     * List chunk (X,Z) coords whose voxy LOD-0 section is absent from the engine
     * at every Y. Use to identify which chunks the server never ingested.
     */
    private static int missingChunks(CommandSourceStack source, int radiusChunks) {
        if (!(source.getLevel() instanceof ServerLevel serverLevel)) {
            source.sendFailure(Component.literal("This command must be run in a server world"));
            return 0;
        }
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command must be run by a player"));
            return 0;
        }
        WorldIdentifier worldId = WorldIdentifier.of(serverLevel);
        if (worldId == null) {
            source.sendFailure(Component.literal("No WorldIdentifier"));
            return 0;
        }
        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            source.sendFailure(Component.literal("No VoxyCommon instance"));
            return 0;
        }
        var engine = instance.getNullable(worldId);
        if (engine == null) {
            source.sendFailure(Component.literal("No engine for this level"));
            return 0;
        }
        int pcx = player.getBlockX() >> 4;
        int pcz = player.getBlockZ() >> 4;
        int rc = Math.max(1, radiusChunks);
        int minSectionY = serverLevel.getMinBuildHeight() >> 5;
        int maxSectionYExclusive = (serverLevel.getMaxBuildHeight() + 31) >> 5;
        StringBuilder out = new StringBuilder();
        int missingSections = 0;
        int listed = 0;
        final int maxList = 64;
        // Dedupe by voxy (vsx, vsz) — two adjacent chunks share one section, and
        // acquireIfExists has an LRU side effect that turns a second probe of the
        // same key into a non-null air shell.
        java.util.HashSet<Long> seenXZ = new java.util.HashSet<>();
        int vsxMin = (pcx - rc) >> 1;
        int vsxMax = (pcx + rc) >> 1;
        int vszMin = (pcz - rc) >> 1;
        int vszMax = (pcz + rc) >> 1;
        for (int vsx = vsxMin; vsx <= vsxMax; vsx++) {
            for (int vsz = vszMin; vsz <= vszMax; vsz++) {
                long xz = (((long) vsx) << 32) | (vsz & 0xFFFFFFFFL);
                if (!seenXZ.add(xz)) continue;
                boolean hasContent = false;
                for (int vy = minSectionY; vy < maxSectionYExclusive; vy++) {
                    long key = WorldEngine.getWorldSectionId(0, vsx, vy, vsz);
                    var s = engine.acquireIfExists(key);
                    if (s != null) {
                        try {
                            if (s.getNonEmptyBlockCount() > 0) {
                                hasContent = true;
                                break;
                            }
                        } finally {
                            s.release();
                        }
                    }
                }
                if (!hasContent) {
                    missingSections++;
                    if (listed < maxList) {
                        if (out.length() > 0) out.append(", ");
                        // Report as the lower-left chunk of the section so users can
                        // jump there with /tp.
                        out.append("(").append(vsx * 2).append(",").append(vsz * 2).append(")");
                        listed++;
                    }
                }
            }
        }
        final int totalMissing = missingSections;
        final int finalListed = listed;
        final String preview = out.toString();
        source.sendSuccess(() -> Component.literal(
                "missing sections within " + rc + "ch of chunk(" + pcx + "," + pcz + "): " + totalMissing +
                        (finalListed < totalMissing
                                ? " (first " + finalListed + " chunk coords: " + preview + ", ...)"
                                : ": " + preview)),
                true);
        return 1;
    }

    private static int reloadConfig(CommandSourceStack source) {
        var cfg = VoxyServer.getServerConfig();
        if (cfg == null) {
            source.sendFailure(Component.literal("Server config not initialized"));
            return 0;
        }
        boolean ok = cfg.reload();
        if (ok) {
            source.sendSuccess(() -> Component.literal(
                    "voxy-server-config.json reloaded (debug_log_activated=" + cfg.debugLogActivated +
                            ", log_ingest_skips=" + cfg.logIngestSkips +
                            ", log_dirty_drain=" + cfg.logDirtyDrain +
                            ", log_version_skips=" + cfg.logVersionSkips +
                            ", log_latency=" + cfg.logLatency + ")"),
                    true);
            return 1;
        }
        source.sendFailure(Component.literal("Failed to reload voxy-server-config.json (see server log)"));
        return 0;
    }

    private static int checkSection(CommandSourceStack source, int lvl, int vx, int vy, int vz) {
        if (!(source.getLevel() instanceof ServerLevel serverLevel)) {
            source.sendFailure(Component.literal("This command must be run in a server world"));
            return 0;
        }
        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            source.sendFailure(Component.literal("VoxyCommon not initialized"));
            return 0;
        }
        WorldIdentifier worldId = WorldIdentifier.of(serverLevel);
        if (worldId == null) {
            source.sendFailure(Component.literal("No WorldIdentifier"));
            return 0;
        }
        WorldEngine engine = instance.getNullable(worldId);
        if (engine == null) {
            source.sendFailure(Component.literal("No engine"));
            return 0;
        }
        long key = WorldEngine.getWorldSectionId(lvl, vx, vy, vz);
        var s = engine.acquireIfExists(key);
        final String report;
        if (s == null) {
            report = "section " + lvl + "@[" + vx + "," + vy + "," + vz + "] NOT IN ENGINE";
        } else {
            try {
                report = "section " + lvl + "@[" + vx + "," + vy + "," + vz + "]"
                        + " blockCount=" + s.getNonEmptyBlockCount()
                        + " childMask=0x" + Integer.toHexString(s.getNonEmptyChildren() & 0xFF)
                        + " octantMask=0x" + Integer.toHexString(s.getIngestedOctantMask() & 0xFF)
                        + " version=" + s.getVersion();
            } finally {
                s.release();
            }
        }
        source.sendSuccess(() -> Component.literal(report), true);
        return 1;
    }

    /**
     * Reset every connected player's "already sent" tracking on the current
     * level's {@link LodStreamingService}. The next streaming tick will resend
     * all in-range sections from scratch.
     */
    private static int resyncCurrentLevel(CommandSourceStack source) {
        if (!(source.getLevel() instanceof ServerLevel serverLevel)) {
            source.sendFailure(Component.literal("This command must be run in a server world"));
            return 0;
        }
        LodStreamingService service = VoxyServer.getStreamingService(serverLevel);
        if (service == null) {
            source.sendFailure(Component.literal("No streaming service for this level (no player has synced yet)"));
            return 0;
        }
        int n = service.resyncAll();
        source.sendSuccess(() -> Component.literal("Resynced " + n + " player(s) on " +
                serverLevel.dimension().location()), true);
        return n;
    }

    /**
     * Tick all processors (called from server tick event)
     */
    public static void tickProcessors() {
        for (ChunkFileProcessor processor : processors.values()) {
            if (processor.isProcessing()) {
                processor.tick();
            }
        }
    }

    /**
     * Cleanup processors when server stops
     */
    public static void cleanup() {
        for (ChunkFileProcessor processor : processors.values()) {
            processor.shutdown();
        }
        processors.clear();
    }
}
