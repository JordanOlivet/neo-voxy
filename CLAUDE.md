# Neo-Voxy - CLAUDE.md

## Projet

Neo-Voxy est un portage NeoForge du mod Minecraft **Voxy** (initialement Fabric).
Voxy fournit un rendu LOD (Level of Detail) pour des distances de rendu massives.

- **Minecraft** : 1.21.1
- **NeoForge** : 21.1.77+
- **Java** : 21 (requis)
- **Build system** : Architectury Loom 1.10-SNAPSHOT + Shadow plugin

## Structure du projet

```
src/main/java/me/cortex/voxy/
  Voxy.java              # Entrypoint (@Mod)
  client/                # Cote client : rendu, config, UI, commandes
    core/                # Pipeline de rendu (GL, modeles, shaders, sections)
    iris/                # Integration Iris (shader patching, samplers)
    mixin/               # Mixins client (minecraft/, sodium/, iris/)
    config/              # Configuration client (VoxyConfig)
  server/                # Cote serveur : events, LOD streaming, commandes
  common/                # Partage : world engine, stockage, compression, voxelization
    config/              # Backends stockage (LMDB, RocksDB, Redis, Memory)
    world/               # WorldEngine, WorldSection, SaveLoadSystem
    network/             # Protocole reseau (VoxyNetworkHandler, VoxyPacketPayload)
  commonImpl/            # Initialisation commune (VoxyCommon, VoxyInstance)
```

## Dependances requises pour le dev

- **Sodium** (NeoForge) : `mc1.21.1-0.6.13-neoforge` - requis au runtime
- **Iris** (NeoForge) : `1.8.0+1.21.1-neoforge` - optionnel (pour les shaders), `modCompileOnly` au build
- **Lithium** (NeoForge) : `0.15.0` - optionnel

## Commandes de build

```bash
# Generer les sources Minecraft (premiere fois / apres changement de mappings)
rtk ./gradlew genSources

# Compiler le mod
rtk ./gradlew build

# Lancer le client de dev
rtk ./gradlew runClient

# Lancer le serveur de dev
rtk ./gradlew runServer
```

## Architecture du rendu

Le pipeline de rendu est hierarchique :
1. `VoxyRenderSystem` orchestre le rendu
2. `HierarchicalOcclusionTraverser` decide quelles sections sont visibles (GPU compute)
3. `AsyncNodeManager` gere le chargement/dechargement async des nodes
4. `RenderDataFactory` genere la geometrie des sections (meshing)
5. `MDICSectionRenderer` effectue le rendu final (Multi-Draw Indirect Count)

## Mixins

- **Config client** : `src/main/resources/voxy.mixins.json` (28 mixins : 10 Minecraft, 7 Sodium, 11 Iris)
- **Config common** : `src/main/resources/voxy-common.mixins.json` (1 mixin)
- Les mixins ciblent : Minecraft vanilla, Sodium, Iris

## Points d'attention

- Le streaming LOD continu casse le rendu Iris (bug connu)
- 290 TODOs dans le code, dont certains critiques (ZSTD, RocksDB)
- Les binding points Iris sont hardcodes (indices 5, 10, 6)
- Pas de tests automatises

## Conventions

- Package racine : `me.cortex.voxy`
- Les mixins utilisent le prefixe `voxy$` pour les methodes injectees
- Mappings : Mojang officiel (`loom.officialMojangMappings()`)
- Natives bundlees : Windows + Linux uniquement (pas de macOS)
