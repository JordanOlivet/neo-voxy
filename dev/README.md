# dev/ — local runtime state

Everything under `dev/` is gitignored except this file. It holds local test
installations that are too large or too machine-specific for git.

## Expected layout

```
dev/
  server-neoforge/   NeoForge 1.21.1 dedicated server for multiplayer LOD streaming tests
  server-fabric/     Fabric 1.21.1 dedicated server, same purpose
```

## Setting up `server-neoforge/`

1. Download the NeoForge 21.1.x installer from https://projects.neoforged.net/neoforged/neoforge
   and run `java -jar neoforge-<version>-installer.jar --installServer` inside `dev/server-neoforge/`.
2. Accept the EULA (`eula.txt`: `eula=true`). Write config files as ASCII/UTF-8
   **without BOM** (a PowerShell `>` redirect adds a BOM and the server then
   ignores the first key).
3. Suggested `server.properties`: `gamemode=creative`, `difficulty=peaceful`,
   `view-distance=8`, `online-mode=false`. Suggested `user_jvm_args.txt`:
   `-Xms2G -Xmx4G`.
4. `mods/`: Sodium is client-only and not needed. Add Chunky (NeoForge) to test
   pregeneration ingest, Lithium optionally.
5. `scripts/deploy-local.bat` builds the mod and copies the jar into
   `dev/server-neoforge/mods/` (and the client `mods/` folder).
6. Start with `run.bat` / `run.sh`. Connect a Voxy client to `localhost:25565`
   (online-mode off, any username works).

## Setting up `server-fabric/`

1. Download the server launcher from
   https://meta.fabricmc.net/v2/versions/loader/1.21.1/0.16.9/1.0.1/server/jar
   as `fabric-server-launch.jar`; `java -Xmx3G -jar fabric-server-launch.jar nogui`
   downloads the vanilla server on first start.
2. Same `eula.txt` and `server.properties` as above.
3. `mods/`: Fabric API for 1.21.1 (required), Chunky Fabric optionally.
4. `scripts\deploy-local.bat fabric` builds and copies the Fabric jar.

## Useful in-game tests

- `/chunky radius 1500` then `/chunky start`: the server pregenerates chunks;
  LODs must stream to the connected client without the player moving.
- Walk 100+ blocks: new LODs around the player must stream within 1-2 s.
- Switch dimension (`/execute in minecraft:the_nether run tp @s ~ ~ ~`): a new
  mapper sync and LOD sync must fire.
- Edit `voxy-server-config.json` (created at first start in the server
  directory) and `/reload` to hot-swap streaming radius / bandwidth.
- `/voxyadmin help` lists the server commands (status, generate, regen, resync...).

## Vanilla server scenario (`CLIENT_ONLY` fallback)

1. Stop the Voxy server.
2. Run a stock 1.21.1 vanilla server in a separate directory (no loader, no mods).
3. Connect with the Voxy client. It must not crash, and the log shows
   `LodReceptionService: server lacks Voxy channel ... switching to client-local ingest`.
