# Fabric support — action plan

Living checklist for shipping Neo-Voxy on **Fabric** alongside NeoForge, from a
single codebase. Tick items as they land and reference the progress log that
covers them (see [`progress-logs/`](progress-logs/README.md)).

- **Target**: Minecraft **1.21.1** only, on both loaders. Porting to newer MC
  versions (1.21.5+ / 26.x) is explicitly out of scope.
- **Principle**: one repo, one `common` module carrying ~95% of the code, thin
  `neoforge` and `fabric` modules. No separate Fabric branch or fork.
- **Non-goal**: cross-loader multiplayer (Fabric client on NeoForge server or the
  reverse). Each server runs the jar matching its loader.

Status legend: `[ ]` todo · `[~]` in progress · `[x]` done · `[-]` dropped (say why)

---

## Decisions

Settled decisions live in [`DECISIONS.md`](DECISIONS.md) as ADRs.

| ID | Question | Outcome |
|----|----------|---------|
| D1 | Repository layout | ADR-006, accepted |
| D2 | Multi-project tooling | ADR-007, accepted: MultiLoader Template (ModDevGradle + Fabric Loom), as in krios |
| D3 | Platform abstraction mechanism | ADR-008, accepted: `ServiceLoader` |
| D4 | Jar naming | ADR-009, proposed |
| D5 | Mod id / display name on Fabric | ADR-009, proposed |
| D6 | Minimum Fabric Loader / Fabric API versions | ADR-010, proposed: Loader 0.16+, any Fabric API (dev: 0.109.0) |

---

## Phase 0 — Repository cleanup and reorganization

No behaviour change. Separate PR, merged before Phase 1 so the multi-project
move starts from a clean tree (ADR-006).

- [x] P0.1 Untrack `bin/` (406 files of IDE build output committed in v0.1) and add it to `.gitignore`
- [x] P0.2 Move documentation into `docs/`:
  - `ANALYSIS.md` → `docs/analysis/port-analysis.md` (stale since 2026-04; add a "historical" banner)
  - `UPSTREAM_BACKPORTS.md` → `docs/upstream-backports.md`
  - `diagnostic-docs/*` → `docs/diagnostics/`
  - Fix every reference (CLAUDE.md, README, memory notes)
- [x] P0.3 Dev helper scripts into `scripts/`:
  - Delete `build.bat` (one-line `gradlew build` + `pause`)
  - `_build-and-deploy.bat` → `scripts/deploy-local.bat`, versioned in git, jar name read from the build instead of hardcoded `neo-voxy-0.2.0.jar` (stale), target loader as argument once Fabric exists
  - Drop `_run-test-server.bat.lnk`
- [x] P0.4 Local runtime state under one gitignored `dev/` folder:
  - `server_test/` → `dev/server-neoforge/` (keep its README, versioned via a `dev/README.md` template or `!`-exceptions)
  - `dist/` (stale 0.2.0 jars + `INSTALL.md`) → delete; merge useful parts of `INSTALL.md` into `README.md`
  - Root `logs/` (empty, gitignored) → delete
- [x] P0.5 `.gitignore` cleanup: add `bin/`, `dev/`, `.orca/`; drop entries for removed files
- [x] P0.6 CI: `check-does-build.yml` called `./gradlew -I init.gradle build` but `init.gradle` does not exist — removed. Switch to `JordanOlivet/ci-workflows` deferred to P1.7 (its artifact glob expects subprojects)
- [x] P0.7 Refresh `README.md` (still says "unfinished port", "no Chunky support"; both false now)
- [x] P0.8 Verify: `./gradlew build` green, jar identical in content to pre-cleanup build

---

## Phase 1 — Multi-project Gradle, NeoForge only

Goal: same NeoForge mod as today, produced from `common` + `neoforge`
subprojects with the MultiLoader Template layout (ADR-007). Fabric does not
exist yet. This is the riskiest phase.

- [x] P1.1 Root build: `settings.gradle` includes `common`, `neoforge`; `buildSrc` conventions (`multiloader-common`, `multiloader-loader`) adapted from `krios/templates/multiloader`; all versions in root `gradle.properties`
- [x] P1.2 `common`: ModDevGradle in NeoForm mode (vanilla 1.21.1, Mojang mappings), `compileOnly` Sodium/Iris/Lithium NeoForge jars (already Mojang-mapped, no remap), Mixin + MixinExtras compile-only, bundled libraries (LWJGL lmdb/zstd, RocksDB, jedis, xz, sqlite-jdbc) as `implementation`
- [x] P1.3 Move `src/main/java` (except loader code), resources (assets, shaders, mixin JSONs, access widener), `src/test` into `common/`; `voxy.accesswidener` mirrored as `META-INF/accesstransformer.cfg`
- [x] P1.4 `neoforge`: ModDevGradle, `@Mod` entrypoint and NeoForge listeners, `neoforge.mods.toml`, Chunky NeoForge via Modrinth Maven version id (drops `libs/chunky-nf`)
- [x] P1.5 Bundling: the libraries currently shaded via the `bundle` configuration keep shipping inside the jar (shadow or `jarJar`), natives for Windows + Linux only (ADR-004)
- [x] P1.6 `sodium8` source set + `extractSodium8Api` moved to `neoforge` (the `@ConfigEntryPointForge` class is NeoForge-bound until P2.7)
- [x] P1.7 CI on `JordanOlivet/ci-workflows` `build-gradle-mod.yml`; `scripts/setup-dev.*` and `scripts/deploy-local.bat` updated for the new layout
- [~] P1.8 Verify (in-game smoke test and Sodium 0.8.12 left to the user; see the 2026-10-14 log): unit tests pass; jar content compared with the pre-migration jar (classes, resources, bundled libs, `neoforge.mods.toml`); dev server and dev client boot with all mixins applied; in-game smoke test (solo + `dev/server-neoforge`), Sodium 0.6.13 **and** 0.8.12, with and without Iris

---

## Phase 2 — Platform abstraction (still NeoForge only)

Goal: `common` has zero `net.neoforged` imports. Today 11 files reference it.
Done together with Phase 1 on `build/multiloader`: `common` compiled against
vanilla cannot build while any of them remains.

- [x] P2.1 `Platform` service (`common`): config dir, game dir, `isModLoaded`, `isDedicatedServer`, mod version. Replaces `ModLoaderUtil`, and the direct `FMLLoader`/`ModList` calls in `VoxyCommon`, `Serialization`, `DebugContextDumper`
- [x] P2.2 `Serialization` class scan: uses `ModList.getModFileById("voxy")` to find the jar — abstract the "own mod root path" lookup
- [x] P2.3 Server lifecycle: turn `VoxyServer`'s 9 `@SubscribeEvent` handlers into plain static methods; NeoForge listener class in `neoforge/` forwards to them
- [x] P2.4 Networking: split `VoxyNetworkHandler` into payload definitions + handlers (`common`) and registration/sending (`PacketSender` service per loader)
- [x] P2.5 Client hooks: `ClientChunkIngestListener`, `DebugSidecarListener`, `VoxyDebugScreenEntry` — logic in `common`, event subscription in `neoforge/`
- [x] P2.6 Commands: make `VoxyCommands` generic over the source type `<S extends SharedSuggestionProvider>` so Fabric's `FabricClientCommandSource` fits
- [x] P2.7 Sodium 0.8 entrypoint: keep option-building logic shared, isolate the `@ConfigEntryPointForge` annotation in `neoforge/`
- [x] P2.8 Chunky: `MixinNeoForgeWorld` + `voxy-chunky.mixins.json` move to `neoforge/`; shared ingest logic stays in `common`
- [x] P2.9 Verify: `grep -r net.neoforged common/` empty; same in-game matrix as P1.8

---

## Phase 3 — Fabric module skeleton

- [x] P3.1 `fabric` subproject on Fabric Loom with the `multiloader-loader` convention (compiles the common sources, refmap for common mixins)
- [x] P3.2 Dependencies (1.21.1, Fabric variants): Fabric Loader, Fabric API, Sodium `mc1.21.1-0.6.13-fabric` (required), Iris 1.8.x fabric, Lithium 0.15.x fabric, Chunky fabric (all optional, `modCompileOnly`)
- [x] P3.3 `fabric.mod.json`: entrypoints, mixin configs, access widener, `depends`/`recommends`/`breaks`
- [x] P3.4 Empty `ModInitializer` / `ClientModInitializer`; `./gradlew :fabric:build` and `:fabric:runClient` boot to title screen

---

## Phase 4 — Fabric implementation

| NeoForge today | Fabric replacement |
|----------------|--------------------|
| `ServerAboutToStartEvent`, `ServerStartedEvent`, `ServerStoppingEvent` | `ServerLifecycleEvents.SERVER_STARTING/STARTED/STOPPING` |
| `ChunkEvent.Load/Unload` (server) | `ServerChunkEvents.CHUNK_LOAD/UNLOAD` |
| `ChunkEvent.Load` (client) | `ClientChunkEvents.CHUNK_LOAD` |
| `LevelEvent.Load` | `ServerWorldEvents.LOAD` |
| `ServerTickEvent.Post` | `ServerTickEvents.END_SERVER_TICK` |
| `PlayerLoggedOutEvent` | `ServerPlayConnectionEvents.DISCONNECT` |
| `PlayerChangedDimensionEvent` | `ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD` |
| `RegisterCommandsEvent` | `CommandRegistrationCallback` |
| `RegisterClientCommandsEvent` | `ClientCommandRegistrationCallback` |
| `RegisterPayloadHandlersEvent` | `PayloadTypeRegistry` + `ServerPlayNetworking` / `ClientPlayNetworking` |
| `ScreenshotEvent` | **No Fabric API event** — mixin on `Screenshot` |
| `CustomizeGuiOverlayEvent.DebugText` | **No Fabric API event** — mixin on `DebugScreenOverlay` |
| `FMLClientSetupEvent` | `ClientModInitializer` (check GL context availability for `VoxyClient.initVoxyClient`) |

- [x] P4.1 `Platform` + `PacketSender` Fabric implementations
- [x] P4.2 Entrypoints wiring `VoxyCommon.cleanInit`, server and client hooks
- [x] P4.3 Server lifecycle, chunk, level, tick, player events
- [x] P4.4 Networking (payload types registered for both directions)
- [x] P4.5 Client chunk ingest
- [x] P4.6 Server and client commands
- [x] P4.7 Screenshot mixin (F2 debug sidecar)
- [x] P4.8 F3 debug text mixin
- [x] P4.9 Chunky: mixin on Chunky's Fabric world adapter
- [~] P4.10 Sodium 0.8 on Fabric (entrypoint `sodium:config_api_user` in place, compiled against the Mojang-mapped 0.8 API; not runnable in dev, to test in a real install): find the Fabric entrypoint mechanism for the config API (entrypoint key in `fabric.mod.json`, to confirm in the 0.8 Fabric jar) and how its classes are nested (`META-INF/jars/` vs NeoForge JarJar) for the compile classpath
- [x] P4.11 Check every mixin applies on Fabric (12 Minecraft targets may differ where NeoForge patches vanilla); run with `-Dmixin.debug.verify=true`

---

## Phase 5 — Validation matrix

Both loaders, manual in-game tests (real MC install, see testing workflow).

| Scenario | NeoForge | Fabric |
|----------|:--------:|:------:|
| Unit tests (shared `common`) | ✅ | ✅ |
| Dev server boots, Voxy server instance, clean stop | ✅ | ✅ |
| Real dedicated server with the shipped jar | ✅ | ✅ |
| Chunky pregen ingest (real server) | ☐ | ✅ |
| Client mixin audit, Sodium 0.6.13 | ✅ | ✅ |
| Client mixin audit, Sodium 0.6.13 + Iris | ✅ (1.8.12) | ✅ (1.8.8) |
| Solo world, LOD build + render | ✅ | ✅ |
| Sodium 0.6.13 options page | ✅ | ✅ |
| Sodium 0.8.12 (options page, mixins) | ☐ | ☐ |
| Iris + shader pack in game | ✅ | ✅ |
| Client on dedicated server, LOD streaming | ✅ | ✅ |
| Dimension change / disconnect / reconnect | ☐ | ✅ |
| Lithium present | ☐ (server only ✅) | ☐ |
| F2 sidecar + F3 overlay | ✅ | ✅ |
| Client-only mode on vanilla server | ☐ | ✅ |

✅ verified on 2026-10-14, by dev runs and scripted dev clients (see the
progress log); ☐ needs an in-game test.

- [x] P5.1 `dev/server-fabric/` local test server (mirrors `dev/server-neoforge/`)
- [~] P5.2 Run the matrix, file issues for every failure

---

## Phase 6 — Release

- [~] P6.1 CI builds and uploads both jars (workflow in place; no Actions run seen on the repo yet)
- [x] P6.2 README: per-loader requirements and install steps
- [x] P6.3 CLAUDE.md: new layout, build commands per subproject (`:neoforge:runClient`, `:fabric:runClient`), Fabric specifics
- [ ] P6.4 Version bump (minor) and release notes
