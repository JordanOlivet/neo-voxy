package me.cortex.voxy.server;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.cortex.voxy.common.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Dedicated-server tunables for LOD streaming.
 * <p>
 * Loaded from {@code <server-root>/voxy-server-config.json}. Auto-created with
 * defaults on first start. Hot-reload supported via {@link #reload()}.
 */
public class VoxyServerConfig {

    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .create();

    /**
     * Per-player hard cap on streaming radius, expressed in <b>vanilla chunks</b>
     * (16 blocks each — same unit as the server's {@code view-distance}).
     * <p>
     * Default 64 chunks = 1024 blocks. Internally Voxy works on 32-block voxy
     * sections (= 2 chunks per axis); the conversion is done via
     * {@link #getMaxStreamingRadiusSections()}.
     */
    public int maxStreamingRadiusChunks = 64;

    /**
     * Max radius the server will honor from a client {@code MSG_CLIENT_HINT},
     * also in vanilla chunks. Default 64 chunks.
     */
    public int clientHintRadiusCapChunks = 64;

    /** Per-player bandwidth cap (KB/s). */
    public int perPlayerLimitKBps = 1024;

    /** Global bandwidth cap shared across all players (KB/s). 0 = unlimited. */
    public int globalLimitKBps = 10240;

    /** Section serialization worker threads. 0 = auto (min(4, cpus/2)). */
    public int serializeThreads = 0;

    /** Bounded size of the recently-dirty section queue per streaming service. */
    public int dirtyQueueMaxEntries = 8192;

    /** Tick rate when streaming is actively expanding rings. */
    public double activeTickHz = 5.0;

    /** Tick rate when the service is in maintenance / steady-state mode. */
    public double maintenanceTickHz = 1.0;

    /** LRU size of the shared serialized-section cache. */
    public int serializedCacheEntries = 4096;

    /**
     * When {@code true}, log every {@code ChunkEvent.Load} that gets skipped by
     * Voxy (proto-chunk, no lighting, ingest disabled, ...). Verbose — use only
     * to diagnose missing LODs.
     */
    public boolean logIngestSkips = false;

    /**
     * Interval (seconds) for the auto-regen watchdog. Periodically iterates the
     * chunks currently loaded server-side around each connected player and
     * re-ingests any whose corresponding voxy section has no content in the
     * engine. Recovers from {@code ChunkEvent.Load} skips (proto-chunk, missing
     * lighting). Set to {@code 0} to disable. Default 30s.
     */
    public int autoRegenIntervalSeconds = 30;

    /**
     * Horizontal cap (vanilla chunks) on the auto-regen sweep around each player.
     * Lower = cheaper but slower coverage; higher = catches missing chunks
     * further away. Default 32 chunks. Capped to the server view-distance at
     * runtime so it never tries to re-ingest unloaded chunks.
     */
    public int autoRegenRadiusChunks = 32;

    /**
     * When a player teleports or moves more than this many vanilla chunks in a
     * single streaming tick, the per-player {@code lastSentVersion} cache is
     * cleared so the new area gets fully re-streamed from scratch. Recovers from
     * race conditions where the previous partial-section data on the client
     * stayed stuck because the relevant server-side version bumps were dropped.
     * Set to {@code 0} to disable. Default 8 chunks.
     */
    public int autoResyncOnJumpChunks = 8;

    /**
     * When {@code true}, log every section that the streamer skips because
     * {@code lastSentVersion[key] >= section.version}. Useful to confirm whether
     * a "missing LOD" symptom is caused by the version-bump path being missed
     * server-side. Verbose — leave off in normal operation.
     */
    public boolean logVersionSkips = false;

    /**
     * Maximum number of dirty-key entries drained per player tick. Prevents the
     * scheduler thread from blocking for hundreds of milliseconds when the
     * queue spikes (e.g. Chunky pregen of thousands of sections), which would
     * stall position tracking and false-trigger the auto-resync jump detector.
     * Excess entries stay in the queue for the next tick. Default 4096.
     */
    public int dirtyDrainMaxPerTick = 4096;

    private transient Path configPath;

    public static VoxyServerConfig load(Path serverRoot) {
        Path path = serverRoot.resolve("voxy-server-config.json");
        VoxyServerConfig config = null;
        if (Files.exists(path)) {
            try {
                config = GSON.fromJson(Files.readString(path), VoxyServerConfig.class);
                if (config == null) {
                    Logger.error("voxy-server-config.json deserialized to null, reverting to defaults");
                }
            } catch (Exception e) {
                Logger.error("Failed to parse voxy-server-config.json, reverting to defaults", e);
            }
        }
        if (config == null) {
            config = new VoxyServerConfig();
        }
        config.configPath = path;
        try {
            Files.writeString(path, GSON.toJson(config));
        } catch (IOException e) {
            Logger.error("Failed to write voxy-server-config.json", e);
        }
        return config;
    }

    public boolean reload() {
        if (configPath == null || !Files.exists(configPath)) {
            return false;
        }
        try {
            VoxyServerConfig fresh = GSON.fromJson(Files.readString(configPath), VoxyServerConfig.class);
            if (fresh == null) {
                return false;
            }
            this.maxStreamingRadiusChunks = fresh.maxStreamingRadiusChunks;
            this.clientHintRadiusCapChunks = fresh.clientHintRadiusCapChunks;
            this.logIngestSkips = fresh.logIngestSkips;
            this.autoRegenIntervalSeconds = fresh.autoRegenIntervalSeconds;
            this.autoRegenRadiusChunks = fresh.autoRegenRadiusChunks;
            this.autoResyncOnJumpChunks = fresh.autoResyncOnJumpChunks;
            this.logVersionSkips = fresh.logVersionSkips;
            this.dirtyDrainMaxPerTick = fresh.dirtyDrainMaxPerTick;
            this.perPlayerLimitKBps = fresh.perPlayerLimitKBps;
            this.globalLimitKBps = fresh.globalLimitKBps;
            this.serializeThreads = fresh.serializeThreads;
            this.dirtyQueueMaxEntries = fresh.dirtyQueueMaxEntries;
            this.activeTickHz = fresh.activeTickHz;
            this.maintenanceTickHz = fresh.maintenanceTickHz;
            this.serializedCacheEntries = fresh.serializedCacheEntries;
            return true;
        } catch (Exception e) {
            Logger.error("Failed to reload voxy-server-config.json", e);
            return false;
        }
    }

    public int effectiveSerializeThreads() {
        if (serializeThreads > 0) {
            return serializeThreads;
        }
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(4, cores / 2));
    }

    /**
     * Convert {@link #maxStreamingRadiusChunks} (vanilla 16-block chunks) into the
     * 32-block voxy-section units the streaming loop iterates with.
     * Rounds up so a configured chunk-radius always covers <em>at least</em> that
     * many chunks.
     */
    public int getMaxStreamingRadiusSections() {
        return Math.max(1, (maxStreamingRadiusChunks + 1) / 2);
    }

    /**
     * Convert {@link #clientHintRadiusCapChunks} (vanilla 16-block chunks) into
     * voxy-section units. Rounds up.
     */
    public int getClientHintRadiusCapSections() {
        return Math.max(1, (clientHintRadiusCapChunks + 1) / 2);
    }
}
