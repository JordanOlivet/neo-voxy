# Neo-Voxy - CLAUDE.md

## Projet

Neo-Voxy est un portage NeoForge du mod Minecraft **Voxy** (initialement Fabric).
Voxy fournit un rendu LOD (Level of Detail) pour des distances de rendu massives.

- **Minecraft** : 1.21.1
- **NeoForge** : 21.1.77+
- **Java** : 21 (requis)
- **Build system** : MultiLoader Template (ADR-007) : `common` sur ModDevGradle/NeoForm, `neoforge` sur ModDevGradle + Shadow

## Logs de progression (OBLIGATOIRE)

Chaque session de travail sur ce repo DOIT laisser une trace dans
`docs/progress-logs/`. Regles completes et template :
`docs/progress-logs/README.md`.

- **Debut de session**, lire dans l'ordre : le dernier
  `docs/progress-logs/YYYY-MM-DD.md` (point de reprise), `docs/DECISIONS.md`
  (ADRs, contraignants), puis le plan concerne dans `docs/` (ex.
  `docs/fabric-port-plan.md`), avant de toucher au code.
- **Fin de session** : ecrire (ou completer, si une session a deja eu lieu le
  jour meme) `docs/progress-logs/YYYY-MM-DD.md` AVANT le commit final, et le
  commiter avec le travail decrit (meme branche, meme PR). Un fichier par jour.
- Cocher les items du plan avances pendant la session et les citer par ID.
- Une session sans code (investigation, decision) produit aussi un log.
- Toute decision structurelle = une entree ADR dans `docs/DECISIONS.md`
  (revenir dessus = nouvelle ADR qui la remplace, jamais une reecriture).

## Organisation du repo (ADR-006)

| Chemin | Contenu |
|--------|---------|
| `common/`, `neoforge/`, `buildSrc/` | Code (voir Structure du projet) |
| `docs/` | `DECISIONS.md`, plans (`fabric-port-plan.md`), `progress-logs/`, `upstream-backports.md`, `analysis/` (historique), `diagnostics/` |
| `scripts/` | `setup-dev.sh`/`.ps1` (bootstrap), `deploy-local.bat` (build + copie du jar vers `.minecraft/mods` et `dev/server-neoforge/mods`) |
| `dev/` | Etat runtime local (serveurs de test), gitignore sauf `dev/README.md` |
| `*/run/`, `build/`, `bin/` | Gitignores (run dirs dev, sorties de build/IDE) |

## Structure du projet

```
common/                  # ~95% du code, compile contre Minecraft vanilla (NeoForm)
  src/main/java/me/cortex/voxy/
    client/              # Cote client : rendu, config, UI, commandes
      core/              # Pipeline de rendu (GL, modeles, shaders, sections)
      iris/              # Integration Iris (shader patching, samplers)
      mixin/             # Mixins client (minecraft/, sodium/, iris/)
      config/            # Configuration client (VoxyConfig)
    server/              # Cote serveur : LOD streaming, commandes (VoxyServer = handlers appeles par les loaders)
    common/              # Partage : world engine, stockage, compression, voxelization, reseau
    commonImpl/          # Initialisation commune (VoxyCommon, VoxyInstance)
      platform/          # Services loader (IPlatform, INetworking) charges par ServiceLoader (ADR-008)
  src/main/resources/    # assets, shaders, mixin JSONs, voxy.accesswidener + META-INF/accesstransformer.cfg
  src/test/java/         # Tests unitaires JUnit
neoforge/                # Couche NeoForge : VoxyNeoForge (@Mod), listeners d'events, impl des services,
                         # mixin Chunky (MixinNeoForgeWorld), source set sodium8, neoforge.mods.toml
buildSrc/                # Conventions Gradle partagees (multiloader-common, multiloader-loader)
```

Regle : `common` n'importe jamais `net.neoforged` ni `net.fabricmc` (le compiler
contre vanilla le garantit). Tout ce qui depend du loader passe par
`commonImpl.platform.Services` ou par des methodes statiques de `common`
appelees depuis les listeners du loader.

## Dependances requises pour le dev

Toutes les versions sont dans `gradle.properties` (Modrinth Maven, resolues par Gradle) :

- **Sodium** (NeoForge) : `mc1.21.1-0.6.13-neoforge` - requis au runtime client
- **Iris** (NeoForge) : `1.8.0` a la compilation, `1.8.12` pour les runs dev (`-PwithIris`) car 1.8.0 ne s'applique pas sur Sodium 0.6.13
- **Lithium** (NeoForge) : `0.15.0` - optionnel
- **Chunky** (NeoForge) : par id de version Modrinth (`LuFhm4eU`), le numero seul resout le jar Forge

### Double support Sodium 0.6.x / 0.8.x

Sodium 0.8.x a supprime `SodiumOptionsGUI` et l'a remplace par une API
d'enregistrement (`net.caffeinemc.mods.sodium.api.config`) absente de 0.6.x. Les
deux chemins coexistent dans le jar et s'excluent mutuellement au runtime :

- **0.6.x** : `MixinSodiumOptionsGUI` + `VoxyConfigScreenPages` + `VoxyOptionStorage`
  (source set `main`). Sur 0.8.x le mixin est simplement skip avec un WARN, sa
  classe cible n'existant plus.
- **0.8.x** : `neoforge/src/sodium8/java/.../VoxySodiumConfig.java`, source set `sodium8`
  compile contre 0.8.12, decouvert via `@ConfigEntryPointForge("voxy")`. Jamais
  chargee sur 0.6.x.

Les classes 0.8.x etant dans un JarJar imbriqué, la tache Gradle
`extractSodium8Api` deballe le jar interne pour le classpath de compilation du
source set. Toute classe dont le nom contient « config » est chargee par
reflexion depuis `Serialization`, d'ou l'exclusion explicite de
`VoxySodiumConfig` : la lier sur 0.6.x provoquerait un `NoClassDefFoundError`.

Cote mixins : sur NeoForge (ModDevGradle, pas de remapping) les selecteurs nus
(`"<init>"`, `"render"`) restent nus et sont resolus au runtime contre le Sodium
charge. Sur Fabric, Loom genere une refmap qui grave le descripteur resolu
contre le Sodium de compilation : pour une methode dont la signature differe
entre 0.6 et 0.8, il faut soit eviter le hook, soit lister les deux descripteurs
explicitement avec `require = 1`.

Sodium 0.8.x ne peut pas tourner en run dev (jar de bootstrap + jar mod imbrique
sur deux couches differentes) : ce chemin se valide en jeu reel uniquement.

## Setup initial (nouveau clone)

Gradle resout tout (Minecraft via ModDevGradle, Sodium/Iris/Lithium/Chunky via
Modrinth Maven, JDK 21 via foojay). Le script ne fait que garantir une JVM de
lancement puis builder :

```bash
scripts/setup-dev.sh            # JVM si absente + ./gradlew build
scripts/setup-dev.sh --quick    # compileJava seulement
powershell -ExecutionPolicy Bypass -File scripts\setup-dev.ps1   # Windows natif
```

## Commandes de build

```bash
rtk ./gradlew build                      # common (tests inclus) + jar neoforge/build/libs/neo-voxy-neoforge-<ver>.jar
rtk ./gradlew :common:test               # tests unitaires seuls
rtk ./gradlew :neoforge:runClient        # client dev (run dir : neoforge/run)
rtk ./gradlew :neoforge:runServer        # serveur dev (neoforge/run/eula.txt requis)
scripts\deploy-local.bat [neoforge]      # build + copie vers .minecraft/mods et dev/server-neoforge/mods
```

Options des runs dev NeoForge :
- `-PwithIris` : ajoute Iris 1.8.12 au runtime.
- `-PquickPlay=<save>` : charge directement un monde de `neoforge/run/saves/`.
- `-PauditMixins` : force l'application de tous les mixins au setup client
  (`MixinEnvironment.audit()`), pour voir un mixin casse sans entrer en jeu.

Pieges :
- Shadow 8.1.1 ne considere pas ses `exclude` comme inputs : apres modification,
  `./gradlew :neoforge:shadowJar --rerun`.
- Un run client lance pendant que la session Windows est verrouillee reste bloque
  sur l'ecran de chargement (rien n'est rendu) ; utiliser `-PauditMixins`.
- Tuer les JVM de run dev orphelines :
  `Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" | Where-Object { $_.CommandLine -match 'neo-voxy' }`.

## Architecture du rendu

Le pipeline de rendu est hierarchique :
1. `VoxyRenderSystem` orchestre le rendu
2. `HierarchicalOcclusionTraverser` decide quelles sections sont visibles (GPU compute)
3. `AsyncNodeManager` gere le chargement/dechargement async des nodes
4. `RenderDataFactory` genere la geometrie des sections (meshing)
5. `MDICSectionRenderer` effectue le rendu final (Multi-Draw Indirect Count)

## Mixins

- **Config client** : `common/src/main/resources/voxy.mixins.json` (Minecraft, Sodium, Iris)
- **Config common** : `common/src/main/resources/voxy-common.mixins.json` (1 mixin)
- **Chunky** : `neoforge/src/main/resources/voxy-chunky.mixins.json` (cible propre au loader)
- Acces : `voxy.accesswidener` (Fabric) et `META-INF/accesstransformer.cfg` (common/NeoForge)
  doivent rester synchronises. L'AT doit aussi ouvrir la classe proprietaire d'un membre
  elargi et les overrides d'une methode elargie (NeoForm recompile Minecraft) ; les champs
  de record ne s'elargissent pas, utiliser les accesseurs du record.

## Points d'attention

- Streaming LOD continu + Iris : bug historique, plus reproduit (a reverifier seulement si signale)
- 290 TODOs dans le code, dont certains critiques (ZSTD, RocksDB)
- Les binding points Iris sont hardcodes (indices 5, 10, 6)
- Tests unitaires JUnit dans `common/src/test` (pas de tests en jeu automatises) ; ils tournent dans `common/build/test-run/`

## Conventions

- Package racine : `me.cortex.voxy`
- Les mixins utilisent le prefixe `voxy$` pour les methodes injectees
- Mappings : Mojang officiel partout (NeoForm pour `common`, NeoForge 1.21.1 tourne en Mojang en production)
- Natives bundlees : Windows + Linux uniquement (pas de macOS)
