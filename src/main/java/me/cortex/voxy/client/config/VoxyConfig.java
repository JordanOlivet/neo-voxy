package me.cortex.voxy.client.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.cortex.voxy.client.core.SSAO;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.common.util.ModLoaderUtil;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

// NOTE: deliberately does NOT implement Sodium's OptionStorage. VoxyConfig is
// loaded very early (e.g. from the Iris StandardMacros mixin during shaderpack
// load); hard-implementing a Sodium client GUI interface here made class linking
// require that GUI class, which under some modpack classloader setups is not
// resolvable yet and crashed startup with NoClassDefFoundError. The OptionStorage
// adapter lives in VoxyOptionStorage, loaded lazily only when the Sodium options
// GUI is opened.
public class VoxyConfig {
    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE)
            .create();

    public static VoxyConfig CONFIG = loadOrCreate();

    /**
     * How the client should behave when joining a multiplayer server.
     * <ul>
     * <li>{@code AUTO} — detect at connect time. Server has Voxy → use server
     * streaming. No Voxy → ingest local chunks.</li>
     * <li>{@code CLIENT_ONLY} — always ingest locally, ignore any server-side
     * Voxy. Useful as an escape hatch when the server streams too slowly.</li>
     * <li>{@code SERVER_STREAM} — require server-side Voxy. If absent, disable
     * Voxy rendering with a chat warning.</li>
     * </ul>
     */
    public enum MultiplayerMode { AUTO, CLIENT_ONLY, SERVER_STREAM }

    public boolean enabled = true;
    public boolean enableRendering = true;
    public boolean ingestEnabled = true;
    /**
     * Bake LOD model textures at 16px per face instead of 8px. Sharper near LODs
     * (the model atlas mips still give 8px and lower farther out automatically), at
     * the cost of a fixed ~512MB model atlas instead of ~128MB. Read once at startup
     * (the atlas + occlusion masks are sized from it) — changing it requires a game
     * restart.
     */
    public boolean highResModelTextures = false;
    public MultiplayerMode multiplayerMode = MultiplayerMode.AUTO;
    public int sectionRenderDistance = 16;
    public int serviceThreads = (int) Math.max(CpuLayout.getCoreCount() / 1.5, 1);
    public float subDivisionSize = 64;
    public boolean renderVanillaFog = false;
    public boolean renderStatistics = false;
    // Default flipped 2026-05-14: subtracting Sodium's builder thread count from
    // serviceThreads left Voxy with as little as 1 worker on machines where
    // Sodium claims most cores (observed: 11 services - 10 sodium = 1 voxy
    // worker), which serialised the mesh-generation "retry path" during the
    // first-connect bake storm and produced multi-second stalls. Letting Voxy
    // use its full serviceThreads pool gives the bake/remap pipeline real
    // parallelism. If a user reports vanilla chunk meshing being starved by
    // Voxy, they can flip this back off in the config UI.
    public boolean dontUseSodiumBuilderThreads = true;
    public boolean debugDumpOnScreenshot = false;
    public boolean logGapDiag = false;
    /**
     * When {@code true}, opens a 60s "first-connect" diagnostic window each time
     * the client sends a LOD sync request. During the window {@link
     * me.cortex.voxy.common.VoxyDiag} emits a 1Hz snapshot of the bake / pending
     * / received / applied counters and timing logs for hot main-thread paths
     * that exceed configured thresholds. Disabled by default — turn on only to
     * investigate first-connect stalls; otherwise leaves the log untouched.
     */
    public boolean diagFirstConnect = false;
    public SSAO.SSAOMode ssaoMode = SSAO.SSAOMode.AUTO;

    private static VoxyConfig loadOrCreate() {
        VoxyConfig result;
        if (VoxyCommon.isAvailable()) {
            var path = getConfigPath();
            if (Files.exists(path)) {
                try (FileReader reader = new FileReader(path.toFile())) {
                    var conf = GSON.fromJson(reader, VoxyConfig.class);
                    if (conf != null) {
                        conf.save();
                        me.cortex.voxy.common.VoxyDiag.setEnabled(conf.diagFirstConnect);
                        return conf;
                    } else {
                        Logger.error("Failed to load voxy config, resetting");
                    }
                } catch (IOException e) {
                    Logger.error("Could not parse config", e);
                }
            }
            result = new VoxyConfig();
            result.save();
        } else {
            result = new VoxyConfig();
            result.enabled = false;
            result.enableRendering = false;
        }
        me.cortex.voxy.common.VoxyDiag.setEnabled(result.diagFirstConnect);
        return result;
    }

    public void save() {
        try {
            Files.writeString(getConfigPath(), GSON.toJson(this));
            me.cortex.voxy.common.VoxyDiag.setEnabled(this.diagFirstConnect);
        } catch (IOException e) {
            Logger.error("Failed to write config file", e);
        }
    }

    private static Path getConfigPath() {
        return ModLoaderUtil.getConfigDir()
                .resolve("voxy-config.json");
    }

    public boolean isRenderingEnabled() {
        return VoxyCommon.isAvailable() && this.enabled && this.enableRendering;
    }
}
