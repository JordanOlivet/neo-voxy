package me.cortex.voxy.client.debug;

import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import org.lwjgl.opengl.GL11;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public final class DebugContextDumper {
    private static final int MAX_DUMP_BYTES = 50 * 1024;
    private static final int TAIL_LOG_LINES = 50;
    private static final int TAIL_LOG_LINES_TRUNCATED = 20;

    private DebugContextDumper() {}

    public static void dumpTo(File pngFile) {
        if (pngFile == null) return;
        try {
            File parent = pngFile.getParentFile();
            if (parent == null) return;
            String pngName = pngFile.getName();
            String txtName = pngName.toLowerCase().endsWith(".png")
                    ? pngName.substring(0, pngName.length() - 4) + ".txt"
                    : pngName + ".txt";
            File txt = new File(parent, txtName);

            String body = buildDump(pngName);
            if (body.length() > MAX_DUMP_BYTES) {
                body = buildDump(pngName, true);
            }
            Files.writeString(txt.toPath(), body, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Logger.warn("Failed to write debug sidecar for " + pngFile + ": " + t);
        }
    }

    private static String buildDump(String pngName) {
        return buildDump(pngName, false);
    }

    private static String buildDump(String pngName, boolean truncated) {
        StringBuilder sb = new StringBuilder(8 * 1024);
        sb.append("=== NEO-VOXY DEBUG DUMP ===\n");
        sb.append("timestamp: ").append(Instant.now()).append('\n');
        sb.append("screenshot: ").append(pngName).append('\n');
        if (truncated) sb.append("note: dump exceeded ").append(MAX_DUMP_BYTES).append(" bytes, log tail truncated\n");
        sb.append('\n');

        section(sb, "PLAYER", DebugContextDumper::playerSection);
        section(sb, "LOOK TARGET", DebugContextDumper::lookTargetSection);
        section(sb, "VANILLA", DebugContextDumper::vanillaSection);
        section(sb, "VOXY INSTANCE", DebugContextDumper::voxyInstanceSection);
        section(sb, "VOXY RENDER", DebugContextDumper::voxyRenderSection);
        section(sb, "VOXY CONFIG", DebugContextDumper::voxyConfigSection);
        section(sb, "STORAGE BACKEND", DebugContextDumper::storageBackendSection);
        section(sb, "IRIS", DebugContextDumper::irisSection);
        section(sb, "SODIUM", DebugContextDumper::sodiumSection);
        section(sb, "GL", DebugContextDumper::glSection);
        section(sb, "VERSIONS", DebugContextDumper::versionsSection);
        sectionTailLog(sb, truncated ? TAIL_LOG_LINES_TRUNCATED : TAIL_LOG_LINES);

        return sb.toString();
    }

    private static void section(StringBuilder sb, String name, Consumer<StringBuilder> body) {
        sb.append("=== ").append(name).append(" ===\n");
        try {
            body.accept(sb);
        } catch (Throwable t) {
            sb.append("(error: ").append(t.getClass().getSimpleName())
                    .append(": ").append(String.valueOf(t.getMessage())).append(")\n");
        }
        sb.append('\n');
    }

    private static void playerSection(StringBuilder sb) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) {
            sb.append("(no local player)\n");
            return;
        }
        Vec3 pos = p.position();
        sb.append(String.format(Locale.ROOT, "pos: (%.3f, %.3f, %.3f)%n", pos.x, pos.y, pos.z));
        sb.append(String.format(Locale.ROOT, "yaw: %.2f  pitch: %.2f%n", p.getYRot(), p.getXRot()));
        Level level = p.level();
        sb.append("dimension: ").append(level.dimension().location()).append('\n');
        BlockPos bp = p.blockPosition();
        sb.append("block_pos: ").append(bp.getX()).append(", ").append(bp.getY()).append(", ").append(bp.getZ()).append('\n');
        ChunkPos cp = new ChunkPos(bp);
        sb.append("chunk: ").append(cp.x).append(", ").append(cp.z).append('\n');
        try {
            sb.append("biome: ").append(level.getBiome(bp).unwrapKey().map(k -> k.location().toString()).orElse("?")).append('\n');
        } catch (Throwable t) {
            sb.append("biome: (error)\n");
        }
        if (mc.gameMode != null) {
            sb.append("gamemode: ").append(mc.gameMode.getPlayerMode()).append('\n');
        }
        sb.append("on_ground: ").append(p.onGround()).append('\n');
    }

    private static void lookTargetSection(StringBuilder sb) {
        Minecraft mc = Minecraft.getInstance();
        HitResult hr = mc.hitResult;
        if (hr == null || hr.getType() == HitResult.Type.MISS) {
            sb.append("none\n");
            return;
        }
        Vec3 loc = hr.getLocation();
        sb.append("type: ").append(hr.getType()).append('\n');
        sb.append(String.format(Locale.ROOT, "location: (%.3f, %.3f, %.3f)%n", loc.x, loc.y, loc.z));
        if (mc.player != null) {
            sb.append(String.format(Locale.ROOT, "distance: %.3f%n", mc.player.position().distanceTo(loc)));
        }
        if (hr instanceof BlockHitResult bhr && mc.level != null) {
            BlockPos bp = bhr.getBlockPos();
            sb.append("block_pos: ").append(bp.getX()).append(", ").append(bp.getY()).append(", ").append(bp.getZ()).append('\n');
            try {
                sb.append("block: ").append(mc.level.getBlockState(bp)).append('\n');
            } catch (Throwable t) {
                sb.append("block: (error)\n");
            }
            sb.append("face: ").append(bhr.getDirection()).append('\n');
        } else if (hr instanceof EntityHitResult ehr) {
            Entity e = ehr.getEntity();
            sb.append("entity: ").append(e.getType()).append(" (id=").append(e.getId()).append(")\n");
        }
    }

    private static void vanillaSection(StringBuilder sb) {
        Minecraft mc = Minecraft.getInstance();
        sb.append("fps: ").append(mc.getFps()).append('\n');
        sb.append("render_distance: ").append(mc.options.renderDistance().get()).append('\n');
        sb.append("simulation_distance: ").append(mc.options.simulationDistance().get()).append('\n');
        if (mc.level != null) {
            sb.append("entity_count: ").append(mc.level.getEntityCount()).append('\n');
        }
    }

    private static void voxyInstanceSection(StringBuilder sb) {
        if (!VoxyCommon.isAvailable()) {
            sb.append("voxy_available: false\n");
            return;
        }
        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            sb.append("voxy_instance: null (not initialized)\n");
            return;
        }
        List<String> lines = new ArrayList<>();
        instance.addDebug(lines);
        for (String line : lines) sb.append(line).append('\n');
    }

    private static void voxyRenderSection(StringBuilder sb) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.levelRenderer instanceof IGetVoxyRenderSystem holder) {
            VoxyRenderSystem vrs = holder.getVoxyRenderSystem();
            if (vrs == null) {
                sb.append("voxy_render_system: null (rendering disabled or pre-init)\n");
                return;
            }
            List<String> lines = new ArrayList<>();
            vrs.addDebugInfo(lines);
            for (String line : lines) sb.append(line).append('\n');
        } else {
            sb.append("level_renderer is not IGetVoxyRenderSystem\n");
        }
    }

    private static void voxyConfigSection(StringBuilder sb) {
        VoxyConfig c = VoxyConfig.CONFIG;
        sb.append("enabled: ").append(c.enabled).append('\n');
        sb.append("enable_rendering: ").append(c.enableRendering).append('\n');
        sb.append("ingest_enabled: ").append(c.ingestEnabled).append('\n');
        sb.append("section_render_distance: ").append(c.sectionRenderDistance).append(" (= ")
                .append(c.sectionRenderDistance * 32).append(" vanilla chunks)\n");
        sb.append("service_threads: ").append(c.serviceThreads).append('\n');
        sb.append("sub_division_size: ").append(c.subDivisionSize).append('\n');
        sb.append("render_vanilla_fog: ").append(c.renderVanillaFog).append('\n');
        sb.append("render_statistics: ").append(c.renderStatistics).append('\n');
        sb.append("dont_use_sodium_builder_threads: ").append(c.dontUseSodiumBuilderThreads).append('\n');
        sb.append("debug_dump_on_screenshot: ").append(c.debugDumpOnScreenshot).append('\n');
    }

    private static void storageBackendSection(StringBuilder sb) {
        if (!VoxyCommon.isAvailable()) {
            sb.append("(voxy unavailable)\n");
            return;
        }
        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            sb.append("(no instance)\n");
            return;
        }
        // Storage backend isn't directly reachable from VoxyInstance — we describe
        // the active backend type via reflection on VoxyConfigStore if loaded.
        // Fall back to a hint that storage details are part of the instance debug above.
        sb.append("(see VOXY INSTANCE section for active world / cache counts)\n");
    }

    private static void irisSection(StringBuilder sb) {
        sb.append("installed: ").append(IrisUtil.IRIS_INSTALLED).append('\n');
        if (!IrisUtil.IRIS_INSTALLED) return;
        try {
            sb.append("shader_pack_enabled: ").append(IrisUtil.irisShaderPackEnabled()).append('\n');
            sb.append("shadow_active: ").append(IrisUtil.irisShadowActive()).append('\n');
            // Try to get the current pack name via reflection so we don't create a hard
            // import on Iris (which is modCompileOnly). The accessor only exists when
            // IRIS_INSTALLED is true.
            try {
                Class<?> irisClass = Class.forName("net.irisshaders.iris.Iris");
                Object packName = irisClass.getMethod("getCurrentPackName").invoke(null);
                sb.append("pack_name: ").append(packName).append('\n');
            } catch (Throwable t) {
                sb.append("pack_name: (unavailable: ").append(t.getClass().getSimpleName()).append(")\n");
            }
        } catch (Throwable t) {
            sb.append("(iris query failed: ").append(t.getMessage()).append(")\n");
        }
    }

    private static void sodiumSection(StringBuilder sb) {
        ModList ml = ModList.get();
        if (ml == null) {
            sb.append("(modlist unavailable)\n");
            return;
        }
        ml.getModContainerById("sodium").ifPresentOrElse(
                c -> sb.append("version: ").append(c.getModInfo().getVersion()).append('\n'),
                () -> sb.append("not loaded\n"));
        ml.getModContainerById("lithium").ifPresent(
                c -> sb.append("lithium: ").append(c.getModInfo().getVersion()).append('\n'));
    }

    private static void glSection(StringBuilder sb) {
        // GL state queries must run on the render thread. ScreenshotEvent fires there,
        // but guard anyway in case this is ever invoked elsewhere.
        if (!RenderSystem.isOnRenderThread()) {
            sb.append("(not on render thread)\n");
            return;
        }
        try {
            sb.append("vendor: ").append(GL11.glGetString(GL11.GL_VENDOR)).append('\n');
            sb.append("renderer: ").append(GL11.glGetString(GL11.GL_RENDERER)).append('\n');
            sb.append("version: ").append(GL11.glGetString(GL11.GL_VERSION)).append('\n');
            sb.append("max_texture_size: ").append(GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE)).append('\n');
        } catch (Throwable t) {
            sb.append("(gl query failed: ").append(t.getMessage()).append(")\n");
        }
    }

    private static void versionsSection(StringBuilder sb) {
        sb.append("minecraft: ").append(SharedConstants.getCurrentVersion().getName()).append('\n');
        sb.append("voxy: ").append(VoxyCommon.MOD_VERSION).append('\n');
        try {
            sb.append("neoforge: ").append(FMLLoader.versionInfo().neoForgeVersion()).append('\n');
        } catch (Throwable t) {
            sb.append("neoforge: (unavailable)\n");
        }
        sb.append("java: ").append(System.getProperty("java.version")).append('\n');
        sb.append("os: ").append(System.getProperty("os.name"))
                .append(' ').append(System.getProperty("os.version")).append('\n');
        sb.append("dist: ").append(FMLLoader.getDist()).append('\n');
    }

    private static void sectionTailLog(StringBuilder sb, int maxLines) {
        sb.append("=== TAIL LOG (last ").append(maxLines).append(" lines) ===\n");
        try {
            Path log = FMLPaths.GAMEDIR.get().resolve("logs").resolve("latest.log");
            if (!Files.exists(log)) {
                sb.append("(latest.log not found at ").append(log).append(")\n\n");
                return;
            }
            List<String> all = Files.readAllLines(log, StandardCharsets.UTF_8);
            int from = Math.max(0, all.size() - maxLines);
            for (int i = from; i < all.size(); i++) {
                sb.append(all.get(i)).append('\n');
            }
        } catch (Throwable t) {
            sb.append("(error reading log: ").append(t.getMessage()).append(")\n");
        }
        sb.append('\n');
    }
}
