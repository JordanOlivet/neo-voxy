# Architecture Decision Records

Lightweight ADR log. One entry per structural decision; newest at the bottom.
Decisions here are **binding**: revisit one only with a written superseding
entry (new ADR that names the one it replaces, and the old entry's status set to
`superseded by ADR-NNN`).

Status values: `proposed` (written, awaiting the user's call) · `accepted` ·
`superseded by ADR-NNN`.

## ADR-001 — Independent hard fork, no upstream sync

**Date**: 2026-06-11 · **Status**: accepted

Neo-Voxy versions independently from upstream Voxy (MCRcortex/voxy). Lineage:
upstream `0.2.9-alpha` (Fabric, MC 1.21.11), backported to MC 1.21.1 / NeoForge
(renumbered 0.1.0), then this fork (0.2.0, 1.0.0 on 2026-06-11).

- Upstream fixes are cherry-picked or reimplemented one by one when needed
  (tracked in `docs/upstream-backports.md`); merging or rebasing upstream is not
  a goal.
- Releases: no git tags, no GitHub Releases. Source-only fork (see License): the
  user merges PRs on GitHub.
- Rejected: tracking upstream. It moves to the latest MC versions (26.x as of
  2026-10), so every sync would mean re-porting the loader layer and backporting
  the MC version.

## ADR-002 — Minecraft 1.21.1 only, Java 21

**Date**: 2026-10-14 · **Status**: accepted

Stay on MC 1.21.1 on every loader. Most of the mods played alongside Voxy have
not migrated to newer MC versions; porting to 1.21.5+ / 26.x (Blaze3D/GpuDevice
rewrite, Sodium 0.9) would be a project of its own. Revisit when the
surrounding mod ecosystem has moved.

## ADR-003 — Support Sodium 0.6.x and 0.8.x from one jar

**Date**: 2026-07-27 · **Status**: accepted

Sodium 0.8.x removed `SodiumOptionsGUI` in favour of a registration API
(`net.caffeinemc.mods.sodium.api.config`) that 0.6.x lacks. Both paths ship in
the same jar and exclude each other at runtime: `MixinSodiumOptionsGUI` (skipped
with a WARN on 0.8.x) and `VoxySodiumConfig` in the `sodium8` source set
(compiled against 0.8.12, never loaded on 0.6.x). Mixins whose target signature
differs between the two branches list both descriptors explicitly or avoid the
hook. Rejected: dropping 0.6.x (still the common version in 1.21.1 packs).

## ADR-004 — Native libraries for Windows and Linux only

**Date**: 2026-04-16 · **Status**: accepted

Bundled natives (LMDB, zstd) cover Windows and Linux; `GPUSelectorWindows2` is
Windows-specific. macOS is out of scope: its user base for this mod is
effectively zero. Re-evaluate only if a macOS user actually reports in.

## ADR-005 — Mandatory progress logs and this ADR log

**Date**: 2026-10-14 · **Status**: accepted

Every work session leaves a dated log in `docs/progress-logs/YYYY-MM-DD.md`
(one file per working day, rules in its `README.md`), committed with the work it
describes. Structural decisions go here. Plans live in `docs/` as checklists
whose items are cited by ID in the logs. Goal: any session, on any machine, can
resume from the latest log without the previous conversation. Modeled on the
krios repository.

## ADR-006 — Repository layout

**Date**: 2026-10-14 · **Status**: accepted

- Root holds only the build entry points (Gradle files, wrapper), `README.md`,
  `LICENSE.md`, `CLAUDE.md`.
- `docs/`: plans, this file, progress logs, analyses (`analysis/`),
  diagnostics (`diagnostics/`), upstream backport tracking.
- `scripts/`: dev environment setup and local deploy helpers, versioned.
- `dev/`: local runtime state (test servers), gitignored except its README.
- Code lives in Gradle subprojects `common/`, `neoforge/`, `fabric/` (ADR-007).
- IDE output (`bin/`) and fetched jars (`libs/`) are never versioned.

## ADR-007 — Multi-loader via the MultiLoader Template

**Date**: 2026-10-14 · **Status**: accepted

Ship NeoForge and Fabric from one codebase, following the MultiLoader Template
pattern already used in krios: `common` compiled against vanilla Minecraft
(Mojang mappings, NeoForm via ModDevGradle), `neoforge` on ModDevGradle (the
official NeoForge tooling), `fabric` on Fabric Loom. Each loader project
compiles the common sources against its own classpath.

- Rejected: Architectury Loom + Architectury plugin (the build used until
  then). Smallest migration, but NeoForge goes through a third-party Loom fork
  pinned on a SNAPSHOT, which already forced the `libs/` JarJar workaround and
  baked mixin descriptors at `remapJar` (the Sodium 0.6/0.8 trap). NeoForge 1.21.1
  runs Mojang mappings in production, so ModDevGradle needs no remapping at all.
- Rejected: a separate Fabric branch or fork (every fix applied twice).
- Cost accepted: one-time rewrite of the NeoForge build; the access widener is
  kept for Fabric and mirrored as an access transformer for common/NeoForge.

## ADR-008 — Platform abstraction through ServiceLoader

**Date**: 2026-10-14 · **Status**: accepted

`common` never imports loader APIs. Loader-specific behaviour (paths, mod
presence, environment, networking registration and sending) goes through
interfaces in `common` implemented per loader and discovered with
`java.util.ServiceLoader` (`META-INF/services/<fully qualified interface name>`
in each loader's resources). Loader event subscriptions live in the loader
projects and forward to plain static methods in `common`.
Rejected: `@ExpectPlatform` (Architectury bytecode transformation).

## ADR-009 — Artifact naming and mod identity across loaders

**Date**: 2026-10-14 · **Status**: accepted

- Jars: `neo-voxy-neoforge-<version>.jar` and `neo-voxy-fabric-<version>.jar`.
- Mod id `voxy` on both loaders: Neo-Voxy and upstream Voxy are mutually
  exclusive on purpose (same mixin targets, same config files).
- Display name "Neo-Voxy" on both loaders for now; renaming is cosmetic and can
  happen any time.
- Same version number for both jars, bumped together.

## ADR-010 — Fabric build specifics

**Date**: 2026-10-14 · **Status**: accepted

- Fabric Loom 1.8 with Gradle 8.10, same toolchain as krios. Mods built with a
  newer Loom cannot be remapped by it: Sodium 0.8.12 Fabric (Loom 1.16) is
  therefore not used at compile time; the `sodium8` source set compiles against
  the Mojang-mapped 0.8 API extracted from the NeoForge jar, and `remapJar`
  maps its Minecraft references. Upgrading Loom (and Gradle) is deferred until
  a dependency forces it.
- Fabric API 0.109.0+1.21.1 in dev (newer builds also need a newer Loom);
  `fabric.mod.json` accepts any Fabric API.
- Sodium is a `recommends`, not a `depends`: Fabric has no client-only
  dependencies and Sodium is client-only. A client without Sodium fails on the
  Sodium mixins, as on NeoForge without the required dependency.
- NeoForge events missing from Fabric API are replaced by Fabric-only mixins:
  `MixinScreenshot` (F2 sidecar) and `MixinDebugScreenOverlay` (F3 lines).
