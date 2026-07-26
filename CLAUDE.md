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

### Double support Sodium 0.6.x / 0.8.x

Sodium 0.8.x a supprime `SodiumOptionsGUI` et l'a remplace par une API
d'enregistrement (`net.caffeinemc.mods.sodium.api.config`) absente de 0.6.x. Les
deux chemins coexistent dans le jar et s'excluent mutuellement au runtime :

- **0.6.x** : `MixinSodiumOptionsGUI` + `VoxyConfigScreenPages` + `VoxyOptionStorage`
  (source set `main`). Sur 0.8.x le mixin est simplement skip avec un WARN, sa
  classe cible n'existant plus.
- **0.8.x** : `src/sodium8/java/.../VoxySodiumConfig.java`, source set `sodium8`
  compile contre 0.8.12, decouvert via `@ConfigEntryPointForge("voxy")`. Jamais
  chargee sur 0.6.x.

Les classes 0.8.x etant dans un JarJar imbriqué, la tache Gradle
`extractSodium8Api` deballe le jar interne pour le classpath de compilation du
source set. Toute classe dont le nom contient « config » est chargee par
reflexion depuis `Serialization`, d'ou l'exclusion explicite de
`VoxySodiumConfig` : la lier sur 0.6.x provoquerait un `NoClassDefFoundError`.

Meme piege cote mixins : `remapJar` resout un selecteur nu (`"<init>"`,
`"render"`) contre le Sodium de compilation et grave le descripteur dans
l'annotation. Pour une methode dont la signature differe entre les deux
branches, il faut soit eviter le hook, soit lister les deux descripteurs
explicitement avec `require = 1`.

## Setup initial (nouveau clone)

Le dossier `libs/` est gitignore : un clone neuf n'a ni le jar Chunky NeoForge
(requis pour compiler le mixin Chunky) ni les JarJars Sodium (requis au runtime).
Un script bootstrappe tout (Chunky, JarJars Sodium, et un JDK 21 si absent) :

```bash
# Linux / macOS / Git Bash sous Windows
scripts/setup-dev.sh            # provisionne + ./gradlew genSources
scripts/setup-dev.sh --quick    # provisionne + compileJava seulement
scripts/setup-dev.sh --no-build # provisionne uniquement

# Windows (PowerShell natif)
powershell -ExecutionPolicy Bypass -File scripts\setup-dev.ps1
```

Le toolchain Java 21 est auto-provisionne par Gradle (resolver foojay dans
`settings.gradle`) si aucun JDK 21 n'est detecte.

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
