package me.cortex.voxy.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.LodReceptionService;
import me.cortex.voxy.client.core.rendering.ChunkBoundRenderer;
import me.cortex.voxy.client.mixin.sodium.AccessorRenderSectionManager;
import me.cortex.voxy.client.mixin.sodium.AccessorSodiumWorldRenderer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.network.VoxyNetworkHandler;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.commonImpl.importers.DHImporter;
import me.cortex.voxy.commonImpl.importers.WorldImporter;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

public class VoxyCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        var imports = Commands.literal("import")
                .then(Commands.literal("world")
                        .then(Commands.argument("world_name", StringArgumentType.string())
                                .suggests(VoxyCommands::importWorldSuggester)
                                .executes(VoxyCommands::importWorld)))
                .then(Commands.literal("bobby")
                        .then(Commands.argument("world_name", StringArgumentType.string())
                                .suggests(VoxyCommands::importBobbySuggester)
                                .executes(VoxyCommands::importBobby)))
                .then(Commands.literal("raw")
                        .then(Commands.argument("path", StringArgumentType.string())
                                .executes(VoxyCommands::importRaw)))
                .then(Commands.literal("zip")
                        .then(Commands.argument("zipPath", StringArgumentType.string())
                                .executes(VoxyCommands::importZip)
                                .then(Commands.argument("innerPath", StringArgumentType.string())
                                        .executes(VoxyCommands::importZip))))
                .then(Commands.literal("cancel")
                        .executes(VoxyCommands::cancelImport));

        if (DHImporter.HasRequiredLibraries) {
            imports = imports
                    .then(Commands.literal("distant_horizons")
                            .then(Commands.argument("sqlDbPath", StringArgumentType.string())
                                    .executes(VoxyCommands::importDistantHorizons)));
        }

        return Commands.literal("voxy")
                .then(Commands.literal("reload")
                        .executes(VoxyCommands::reloadInstance))
                .then(Commands.literal("sync")
                        .executes(VoxyCommands::syncLod))
                .then(Commands.literal("debugBounds")
                        .executes(VoxyCommands::toggleDebugBounds))
                .then(Commands.literal("dumpbounds")
                        .executes(ctx -> dumpBounds(ctx, -1))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(0, 64))
                                .executes(ctx -> dumpBounds(ctx, IntegerArgumentType.getInteger(ctx, "radius")))))
                .then(imports);
    }

    private static int dumpBounds(CommandContext<CommandSourceStack> ctx, int radiusArg) {
        var mc = Minecraft.getInstance();
        var wr = mc.levelRenderer;
        if (!(wr instanceof IGetVoxyRenderSystem vrs)) {
            ctx.getSource().sendFailure(Component.literal("Voxy render system not available"));
            return 1;
        }
        var renderSystem = vrs.getVoxyRenderSystem();
        if (renderSystem == null) {
            ctx.getSource().sendFailure(Component.literal("Voxy render system not initialized"));
            return 1;
        }

        int rd = mc.options.getEffectiveRenderDistance();
        int radius = radiusArg < 0 ? rd + 3 : radiusArg;

        var player = mc.player;
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("No player"));
            return 1;
        }
        int pcx = SectionPos.blockToSectionCoord(player.getBlockX());
        int pcy = SectionPos.blockToSectionCoord(player.getBlockY());
        int pcz = SectionPos.blockToSectionCoord(player.getBlockZ());

        long[] tracked = renderSystem.chunkBoundRenderer._debugGetTrackedPositions();
        Arrays.sort(tracked);

        SodiumWorldRenderer sodium = SodiumWorldRenderer.instanceNullable();
        RenderSectionManager mgr = null;
        Long2ReferenceMap<RenderSection> sectionMap = null;
        if (sodium != null) {
            mgr = ((AccessorSodiumWorldRenderer) sodium).getRenderSectionManager();
            if (mgr != null) {
                sectionMap = ((AccessorRenderSectionManager) mgr).getSectionByPosition();
            }
        }

        int totalTracked = tracked.length;
        int withinRadius = 0;
        int orphanCount = 0;
        int phantomCount = 0;
        int invisibleCount = 0;
        int ringMin = Math.max(0, rd - 2);
        int ringMax = rd + 3;

        StringBuilder sb = new StringBuilder();
        sb.append("=== /voxy dumpbounds ===\n");
        sb.append("playerSection=(").append(pcx).append(',').append(pcy).append(',').append(pcz).append(')')
                .append(" effectiveRD=").append(rd)
                .append(" radius=").append(radius)
                .append(" totalTracked=").append(totalTracked)
                .append(" sodiumAvail=").append(sodium != null)
                .append(" sectionMapAvail=").append(sectionMap != null).append('\n');
        sb.append("Sodium flags: bit0=BLOCK_GEOMETRY, bit1=BLOCK_ENTITIES, bit2=ANIMATED_SPRITES.\n");
        sb.append("ORPHAN = tracked but Sodium has no built section. PHANTOM = built but flags=0 or NO BLOCK_GEOMETRY -> AABB writes depth without vanilla pixels behind it (likely cause of invisible LOD).\n");

        int shown = 0;
        for (long pos : tracked) {
            int sx = SectionPos.x(pos);
            int sy = SectionPos.y(pos);
            int sz = SectionPos.z(pos);
            int dx = sx - pcx;
            int dz = sz - pcz;
            int dy = sy - pcy;
            int chebXZ = Math.max(Math.abs(dx), Math.abs(dz));
            if (chebXZ > radius) continue;
            withinRadius++;
            boolean sodiumBuilt = sodium == null || sodium.isSectionReady(sx, sy, sz);
            boolean sodiumVisible = mgr == null || mgr.isSectionVisible(sx, sy, sz);
            int flags = -1;
            if (sectionMap != null) {
                RenderSection rs = sectionMap.get(pos);
                if (rs != null) flags = rs.getFlags();
            }
            boolean hasBlockGeo = flags > 0 && (flags & 1) != 0;
            boolean inRing = chebXZ >= ringMin && chebXZ <= ringMax;
            String tag;
            if (sodium != null && !sodiumBuilt) {
                tag = "ORPHAN";
                orphanCount++;
            } else if (sectionMap != null && sodiumBuilt && !hasBlockGeo) {
                tag = "PHANTOM";
                phantomCount++;
            } else if (mgr != null && !sodiumVisible) {
                tag = "INVISIBLE";
                invisibleCount++;
            } else if (inRing) {
                tag = "EDGE";
            } else {
                tag = "";
            }
            sb.append("  (").append(sx).append(',').append(sy).append(',').append(sz).append(')')
                    .append(" d=(").append(dx).append(',').append(dy).append(',').append(dz).append(')')
                    .append(" chebXZ=").append(chebXZ)
                    .append(" built=").append(sodiumBuilt)
                    .append(" visible=").append(sodiumVisible)
                    .append(" flags=").append(flags);
            if (!tag.isEmpty()) sb.append(' ').append(tag);
            sb.append('\n');
            shown++;
        }
        sb.append("withinRadius=").append(withinRadius)
                .append(" shown=").append(shown)
                .append(" orphans=").append(orphanCount)
                .append(" phantoms=").append(phantomCount)
                .append(" invisible=").append(invisibleCount);

        String dump = sb.toString();
        Logger.info(dump);
        ctx.getSource().sendSystemMessage(Component.literal(
                "dumpbounds: tracked=" + totalTracked + " withinRadius=" + withinRadius
                        + " orphans=" + orphanCount + " phantoms=" + phantomCount
                        + " invisible=" + invisibleCount + " (full output in log)"));
        return 0;
    }

    // Diagnosis tool for the phantom-occlusion bug (see ChunkBoundRenderer.java).
    // Toggles AABB rasterization on/off at runtime. If toggling makes invisible
    // LOD chunks reappear → confirmed phantom-occlusion → consider whether the
    // option-B chebyshev edge-ring skip in outline.vsh still covers the case, or
    // whether option A (tight per-section bounds) is now warranted.
    private static int toggleDebugBounds(CommandContext<CommandSourceStack> ctx) {
        boolean now = !ChunkBoundRenderer.DEBUG_DISABLE_DEPTH_BOUNDS;
        ChunkBoundRenderer.DEBUG_DISABLE_DEPTH_BOUNDS = now;
        ctx.getSource().sendSystemMessage(Component.literal(
                "ChunkBoundRenderer depth-bounds rasterization: " + (now ? "DISABLED" : "ENABLED")));
        return 0;
    }

    private static int reloadInstance(CommandContext<CommandSourceStack> ctx) {
        var instance = (VoxyClientInstance) VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        var wr = Minecraft.getInstance().levelRenderer;
        if (wr != null) {
            ((IGetVoxyRenderSystem) wr).shutdownRenderer();
        }

        VoxyCommon.shutdownInstance();
        System.gc();
        VoxyCommon.createInstance();

        var r = Minecraft.getInstance().levelRenderer;
        if (r != null)
            r.allChanged();
        return 0;
    }

    private static int syncLod(CommandContext<CommandSourceStack> ctx) {
        // Check if we're in single-player (no network needed)
        if (VoxyNetworkHandler.isSinglePlayer()) {
            ctx.getSource()
                    .sendSystemMessage(Component.literal("LOD sync not needed in single-player - data is local"));
            return 0;
        }

        // Get the render system to access LodReceptionService
        var wr = Minecraft.getInstance().levelRenderer;
        if (wr instanceof IGetVoxyRenderSystem vrs) {
            var renderSystem = vrs.getVoxyRenderSystem();
            if (renderSystem != null) {
                var receptionService = renderSystem.getLodReceptionService();
                if (receptionService != null) {
                    receptionService.requestSync();
                    ctx.getSource().sendSystemMessage(Component.literal("Requesting LOD sync from server..."));
                    return 0;
                }
            }
        }

        ctx.getSource().sendFailure(Component.literal("Voxy render system not available"));
        return 1;
    }

    private static int importDistantHorizons(CommandContext<CommandSourceStack> ctx) {
        var instance = (VoxyClientInstance) VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        var dbFile = new File(ctx.getArgument("sqlDbPath", String.class));
        if (!dbFile.exists()) {
            return 1;
        }
        if (dbFile.isDirectory()) {
            dbFile = dbFile.toPath().resolve("DistantHorizons.sqlite").toFile();
            if (!dbFile.exists()) {
                return 1;
            }
        }

        File dbFile_ = dbFile;
        var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine == null)
            return 1;
        return instance.getImportManager().makeAndRunIfNone(engine, () -> new DHImporter(dbFile_, engine,
                Minecraft.getInstance().level, instance.getServiceManager(), instance.savingServiceRateLimiter)) ? 0
                        : 1;
    }

    private static boolean fileBasedImporter(File directory) {
        var instance = (VoxyClientInstance) VoxyCommon.getInstance();
        if (instance == null) {
            return false;
        }

        var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine == null)
            return false;
        return instance.getImportManager().makeAndRunIfNone(engine, () -> {
            var importer = new WorldImporter(engine, Minecraft.getInstance().level, instance.getServiceManager(),
                    instance.savingServiceRateLimiter);
            importer.importRegionDirectoryAsync(directory);
            return importer;
        });
    }

    private static int importRaw(CommandContext<CommandSourceStack> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        return fileBasedImporter(new File(ctx.getArgument("path", String.class))) ? 0 : 1;
    }

    private static int importBobby(CommandContext<CommandSourceStack> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        var file = new File(".bobby").toPath().resolve(ctx.getArgument("world_name", String.class)).toFile();
        return fileBasedImporter(file) ? 0 : 1;
    }

    private static CompletableFuture<Suggestions> importWorldSuggester(CommandContext<CommandSourceStack> ctx,
            SuggestionsBuilder sb) {
        return fileDirectorySuggester(Minecraft.getInstance().gameDirectory.toPath().resolve("saves"), sb);
    }

    private static CompletableFuture<Suggestions> importBobbySuggester(CommandContext<CommandSourceStack> ctx,
            SuggestionsBuilder sb) {
        return fileDirectorySuggester(Minecraft.getInstance().gameDirectory.toPath().resolve(".bobby"), sb);
    }

    private static CompletableFuture<Suggestions> fileDirectorySuggester(Path dir, SuggestionsBuilder sb) {
        var str = sb.getRemaining().replace("\\\\", "\\").replace("\\", "/");
        if (str.startsWith("\"")) {
            str = str.substring(1);
        }
        if (str.endsWith("\"")) {
            str = str.substring(0, str.length() - 1);
        }
        var remaining = str;
        if (str.contains("/")) {
            int idx = str.lastIndexOf('/');
            remaining = str.substring(idx + 1);
            try {
                dir = dir.resolve(str.substring(0, idx));
            } catch (Exception e) {
                return Suggestions.empty();
            }
            str = str.substring(0, idx + 1);
        } else {
            str = "";
        }

        try {
            var worlds = Files.list(dir).toList();
            for (var world : worlds) {
                if (!world.toFile().isDirectory()) {
                    continue;
                }
                var wn = world.getFileName().toString();
                if (wn.equals(remaining)) {
                    continue;
                }
                if (SharedSuggestionProvider.matchesSubStr(remaining, wn)
                        || SharedSuggestionProvider.matchesSubStr(remaining, '"' + wn)) {
                    wn = str + wn + "/";
                    sb.suggest(StringArgumentType.escapeIfRequired(wn));
                }
            }
        } catch (IOException e) {
        }

        return sb.buildFuture();
    }

    private static int importWorld(CommandContext<CommandSourceStack> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        var name = ctx.getArgument("world_name", String.class);
        var file = new File("saves").toPath().resolve(name);
        name = name.toLowerCase();
        if (name.endsWith("/")) {
            name = name.substring(0, name.length() - 1);
        }
        if (!(name.endsWith("region"))) {
            file = file.resolve("region");
        }
        return fileBasedImporter(file.toFile()) ? 0 : 1;
    }

    private static int importZip(CommandContext<CommandSourceStack> ctx) {
        var zip = new File(ctx.getArgument("zipPath", String.class));
        var innerDir = "region/";
        try {
            innerDir = ctx.getArgument("innerPath", String.class);
        } catch (Exception e) {
        }

        var instance = (VoxyClientInstance) VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        String finalInnerDir = innerDir;

        var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine != null) {
            return instance.getImportManager().makeAndRunIfNone(engine, () -> {
                var importer = new WorldImporter(engine, Minecraft.getInstance().level, instance.getServiceManager(),
                        instance.savingServiceRateLimiter);
                importer.importZippedRegionDirectoryAsync(zip, finalInnerDir);
                return importer;
            }) ? 0 : 1;
        }
        return 1;
    }

    private static int cancelImport(CommandContext<CommandSourceStack> ctx) {
        var instance = (VoxyClientInstance) VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        var world = WorldIdentifier.ofEngineNullable(Minecraft.getInstance().level);
        if (world != null) {
            return instance.getImportManager().cancelImport(world) ? 0 : 1;
        }
        return 1;
    }
}