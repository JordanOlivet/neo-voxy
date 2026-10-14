# Voxy Server-Driven LOD Streaming — Connection Flood Analysis & Fix Notes

**Server:** `minecraft-gotta-craft-em-all-reforged` (NeoForge 21.1.233 / MC 1.21.1, itzg, Java 21, G1GC, 20G heap)
**Voxy build:** `neo-voxy-1.1.3` (in-house LOD dev build — keep it, do not remove)
**Date of investigation:** 2026-06-28
**Status:** Root cause confirmed. Fix to be developed in Voxy.

---

## TL;DR

Two **independent** problems were producing "teleport lag" on this server:

1. **Server main-thread spawn stalls** (1–6 s `Can't keep up`) — caused by the **Async mod** (`enableAsyncSpawn=true`) injecting a per-RNG-call thread-pool check into the `NaturalSpawner` hot path. **FIXED** by setting `enableAsyncSpawn = false` in `config/async.toml`. Keep this fix.

2. **Voxy LOD streaming floods the single Minecraft TCP connection** (~**16 MB/s** sustained, per client F3 graph) → gameplay packets (keep-alive/ping, movement, block interactions) get delayed behind/alongside the LOD stream on the client's single-threaded packet pipeline → **ping stuck at 1–3 TAB bars (≈300 ms–1 s RTT) and delayed input**, even on a direct LAN connection. **This is the Voxy bug to fix.**

**Confirmation test (definitive):** disabling Voxy client-side → TAB ping went to **5 bars**, throughput dropped to **<200 KB/s**, and the AE2 terminal opened **instantly even at the moment a teleport finished**. Re-enabling reproduces the lag.

This document covers Problem #2 (Voxy). Problem #1 is included only for context.

### Immediate workaround (no Voxy code change)

Run Voxy in **client-only mode** (disable the **server-driven LOD streaming** feature; let the client build LOD locally from the chunk data it already receives). This avoids the separate server→client LOD stream entirely, so the connection is no longer flooded — by construction. **Confirmed empirically 2026-06-28: client-only → no flood, ping back to 5 bars.** The proper fix below is only needed if you want to keep *server-driven* streaming.

---

## Symptom (as originally reported)

- After teleporting, the player can see the world (chunks render fast — world is pre-generated ~10k block radius) but **cannot interact/move for a while**: chest clicks don't register then arrive in a burst; movement rubber-bands (rollback).
- Happens even when alone on the server, for hours.
- Intermittent.

## What was ruled OUT (and how)

| Hypothesis | Verdict | Evidence |
|---|---|---|
| Continuous server overload | ❌ | TPS 20, server tick MSPT median ~8 ms during the lag, CPU ~10–20 % |
| GC pauses | ❌ | Heap 33 % used (6.6/20 GB), G1 pauses ~30 ms |
| Disk I/O | ❌ | World on local NVMe ext4, `sync-chunk-writes=false` |
| Player count / load | ❌ | Reproduces solo |
| Network **path** (LAN client connecting via `cobblemon.lakio.io` → public IP → NAT hairpin) | ❌ | Connecting directly to LAN IP `192.168.1.70:61781` made **no difference** (still 1–3 bars) |
| Client GPU/rendering | ❌ | Client FPS healthy (40–50) during the lag |
| Network **bandwidth path** | ❌ | LAN is gigabit; bandwidth itself isn't the limit |

## Methodology used

- **spark** profiler driven from the container console (RCON returns nothing because spark replies async):
  `docker exec -u 1000 <ctr> mc-send-to-console "spark profiler ..."` then read output from `docker logs`.
- Targeted capture of stalls only: `spark profiler --only-ticks-over 1000`.
- All-thread capture: `spark profiler --thread * --timeout <s>`.
- spark reports decoded offline (protobuf) to get accurate per-method self-time on the Server thread.
  ⚠️ A naive `strings | grep` over a spark report counts the **metadata** (437 mod ids), **not** the call frames — it produced false leads (wrongly implicated Voxy/Cobblemon early on). Always parse the `ThreadNode`/`StackTraceNode` structure (`children_refs` flat-pool format) and weight by `times`.
- Server-side throughput sampled via `/proc/net/dev` (eth0) over a few seconds.
- Client-side: F3 network graph (rx/tx) + TAB ping bars + F3 ping graph.

---

## Problem #1 (context only — already fixed)

Hot path on the Server thread during the multi-second stalls (spark report, `--only-ticks-over 1000`):

```
ServerLevel.tick → ServerChunkCache.tickChunks
  → async$tickChunksSpawn                          (Async mod wraps spawn)
    → NaturalSpawner.spawnForChunk          ~34 s of captured slow-tick time
      → spawnCategoryForPosition
        → BitRandomSource.nextInt           ~13 s
          → async$threadLocalNext            (Async mod mixin on LegacyRandomSource.next)
            → ParallelProcessor.isThreadInPool
              → Stream.anyMatch over a ConcurrentHashMap   ← per-RNG-call overhead
```

`config/async.toml` had `disabled = true` (parallel entity ticking off → Async provided nothing) **but** `enableAsyncSpawn = true`, which kept wrapping spawn and patching the RNG with a `Stream.anyMatch` thread-pool check executed on **every** `random.nextInt()`. `NaturalSpawner` calls RNG thousands of times per tick (heavy on a Cobblemon pack) → multi-second main-thread stalls, even when standing still.

**Fix applied:** `enableAsyncSpawn = false`. Result: captured slow-tick time on the Server thread dropped from **132 s → ~0**; the only residual >1 s tick was a teleport waiting on chunk load, see below. **Keep this setting** (or remove the Async mod entirely — it is inert with `disabled=true`). If you later re-enable parallel ticking (`disabled=false`) with your deadlock fix, this whole spawn path will parallelize differently — re-measure.

---

## Problem #2 — Voxy LOD streaming floods the gameplay connection (THE Voxy bug)

### Root cause

Voxy runs **server-driven LOD streaming**: it pushes LOD data to the client continuously and re-streams aggressively on movement/teleport. Observed configuration from logs:

```
LodStreamingService initialized [minecraft:the_nether] workers=4 radius=64ch (=32vs) ySections=[0..8) autoRegen=120s/32ch
Server-driven streaming enabled for Lakio21 (tick=40ms, radius=64 chunks)
```

- **radius = 64 chunks** (32 vertical sections) per dimension, streamed every **40 ms** (fastTick).
- On teleport / dimension change it does a **full resync**: it clears the per-client "lastSent" cache and re-streams the whole ring.

Measured impact:

- **Client F3 network graph: ~16 MB/s (≈128 Mbit/s) sustained** with Voxy on. Dropped to **<200 KB/s with Voxy off.**
- The LOD bytes travel over the **same single Minecraft TCP connection** as all gameplay packets. Minecraft processes packets **sequentially on one pipeline**, with the client decoding/ingesting each packet on its netty/main path. 16 MB/s of LOD payloads means gameplay packets (KeepAlive→ping, movement, `UseItemOn`/container open) wait in line / compete for processing time.
- Net effect: **head-of-line blocking + per-packet processing latency** → keep-alive RTT inflates to ~300 ms–1 s (TAB shows 1–3 bars) and player input is delayed — **while the server tick (8 ms) and client FPS (40–50) are both perfectly healthy**. The bottleneck is the *connection/packet pipeline*, not CPU/GPU/bandwidth-of-the-LAN.

### Why it's permanent (not just on teleport)

Because `fastTick=40ms` streaming runs **continuously**, even standing still the connection carries a steady LOD load → ping is **always** 1–3 bars. Teleport makes it worse: a full resync burst.

### Log signatures of the pathology (overworld + nether, during a teleport burst)

```
[VoxyLodStreaming-overworld] [VoxyStream] Lakio21 jumped 93 sections — auto-resync (cleared 27432 lastSent entries)
[VoxyLodStreaming-the_nether] [VoxyStream] Lakio21 jumped 93 sections — auto-resync (cleared 7107 lastSent entries)
[VoxyLodStreaming-overworld/WARN] [VoxyStream] scheduler stalled 1075ms (expected 40ms), skipping auto-resync even though player section delta is 93 sections
... Entering maintenance mode for Lakio21 (slowTick=1000ms) ↔ Returning to active streaming (fastTick=40ms)   (oscillating)
```

- A single teleport clears & re-streams **~27k + 7k LOD sections** in a burst.
- The streaming scheduler itself **stalled 1075 ms** (expected 40 ms) → Voxy's own streaming thread is choking under the resync, on top of flooding the client.
- The `maintenance mode (slowTick=1000ms)` ↔ `active (fastTick=40ms)` oscillation suggests an existing backoff mechanism that is **not preventing the flood**.

### Relevant Voxy entry points (from thread/class names seen)

- `me.cx.vy.cn.wd.se.LodStreamingService` (core), `me.cx.vy.sr.VoxyServer`
- Threads: `VoxyLodStreaming-<dimension>`, `VoxyChunkedLodSender`, `VoxySerialize` (x8), `Dedicated Voxy Worker` (x3)

---

## Fix directions for Voxy (prioritised)

The goal: **never let LOD streaming starve gameplay packets.** Options, from quickest to most robust:

1. **App-level rate limit / per-tick byte budget (quickest, highest value).**
   Cap LOD bytes/sec to a small fraction of the link, e.g. **0.5–2 MB/s**, and send LOD in **small batches per tick** rather than large bursts. 16 MB/s → ~1 MB/s removes the flood while keeping LOD usable. Make the cap configurable.

2. **Separate transport channel for LOD (most robust architecturally).**
   Move LOD off the main Minecraft connection entirely (a side socket / dedicated channel). Then LOD can never head-of-line-block gameplay packets, regardless of volume. This is the clean long-term fix. (If staying on the MC connection, at least split into its own logical stream with strict low priority.)

3. **Prioritise gameplay packets when sharing the connection.**
   Interleave: emit at most N small LOD packets per tick; always let KeepAlive / movement / container-interaction packets jump ahead. Avoid enqueuing a big contiguous block of LOD that delays whatever is queued after it.

4. **Smarter teleport resync (don't clear-and-burst).**
   Instead of clearing the whole `lastSent` cache and re-streaming the full ring at once: spread the resync over many ticks, **nearest sections first**, rate-limited. The current "jumped 93 sections → cleared 27432 entries" all-at-once is the worst case.

5. **Per-dimension LOD cache to avoid full resync on dimension hops.**
   Overworld↔Nether round-trips trigger full resyncs of both dimensions. Persist per-dimension `lastSent` state so returning to a recently-visited dimension re-streams only deltas.

6. **Adaptive streaming driven by measured connection health.**
   Monitor RTT / send-buffer backpressure and **throttle streaming rate when latency rises** (app-level congestion control). The existing maintenance/active oscillation hints this is partially attempted but ineffective — tie backoff to actual RTT, not just section delta.

7. **Reduce / make adaptive the default `radius` (currently 64 chunks).**
   64 is very large to (re)stream. Consider a smaller default and grow it only when the connection is idle and healthy.

8. **Fix the `scheduler stalled 1075ms`.**
   Investigate what blocks `VoxyLodStreaming-<dim>` during resync (serialization on `VoxySerialize`? lock contention?). A stalling scheduler compounds the burst.

9. **Compression / delta encoding of LOD payloads.**
   Ensure LOD packets are well compressed and delta-encoded against `lastSent`. Reduces bytes-on-wire for the same coverage.

**Suggested first iteration:** (1) + (3) + (4) — a configurable per-tick LOD byte budget, gameplay-packet priority, and a spread/nearest-first teleport resync. That alone should bring the flood from 16 MB/s to a controlled trickle and restore 5-bar ping while keeping LOD functional. (2) is the ideal follow-up.

---

## How to measure / validate a fix

Run the same before/after comparison after each Voxy change (server restart not needed if Voxy change is client-side; otherwise restart the container):

**Client side**
- TAB ping bars: target **5** (keep-alive RTT < 150 ms).
- F3 network graph: LOD streaming should stay in the **tens–low-hundreds of KB/s**, not MB/s, including during/after teleport.
- Input feel: chest/terminal open and movement instant immediately after a teleport.

**Server side**
- eth0 throughput during a teleport burst:
  ```
  docker exec <ctr> sh -c 'a=$(grep eth0 /proc/net/dev); sleep 6; b=$(grep eth0 /proc/net/dev); echo "$a"; echo "$b"' \
    | awk '/eth0/{rx[NR]=$2;tx[NR]=$10} END{printf "TX %.3f MB/s\nRX %.4f MB/s\n",(tx[2]-tx[1])/6/1048576,(rx[2]-rx[1])/6/1048576}'
  ```
  Target: TX stays low (≈ tens of KB/s) even during teleport bursts.
- Server tick stays healthy (it already is): `mc-send-to-console "spark tps"`.
- Watch logs for the pathology: absence of `scheduler stalled` and of large `cleared NNNNN lastSent entries` bursts.

**Reference numbers from this investigation**
- Voxy ON: ~16 MB/s sustained (client F3), ping 1–3 bars, input delayed.
- Voxy OFF: <200 KB/s, ping 5 bars, input instant.
- Server tick during the lag: MSPT median ~8 ms (healthy) — proves the issue is the connection, not the server.

---

## Appendix — environment data points

- Host: AMD Ryzen 7 3700X (8c/16t), client: Intel i7-7700 + NVIDIA, gigabit LAN.
- Server published port: `192.168.1.70:61781` → container `25565`.
- DNS: `cobblemon.lakio.io` = grey (DNS-only) A record + Minecraft SRV → public IP (NOT the cause; direct LAN was identical).
- Settings: `view-distance=8`, `simulation-distance=6`, `network-compression-threshold=256`.
- spark reports captured (may expire): nether/empty `cryBGGUvHs`, terminal/spawn-stall `tiyWppW70A`, post-fix-A `QWcFN4hfyS`, all-thread teleport `XF5PoKP0Hc`.
