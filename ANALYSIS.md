# Neo-Voxy - Analyse du Portage Fabric -> NeoForge

**Date**: 2026-04-18 (dernière mise à jour)
**Version analysee**: 0.2.0
**Minecraft**: 1.21.1 | **NeoForge**: 21.1.77+

---

## 1. Vue d'ensemble

Neo-Voxy est un portage du mod Voxy (rendu LOD longue distance) de Fabric vers NeoForge.
Le portage des APIs de la plateforme (entrypoint, events, networking) est **termine**.
Le mod reste **instable** avec des fonctionnalites partiellement fonctionnelles.

### Estimation de l'avancement

| Domaine | Avancement | Notes |
|---------|-----------|-------|
| Portage API NeoForge | 95% | Complet, quelques artefacts Fabric restants |
| Rendu (pipeline core) | 80% | Fonctionnel mais nombreux TODOs d'optimisation |
| Streaming LOD | 60% | Partiellement fonctionnel, edge cases cassent |
| Integration Iris | 75% | Fonctionne mais binding points hardcodes |
| Integration Sodium | 85% | Mixins en place, fonctionnel |
| Stockage (backends) | 70% | LMDB/RocksDB fonctionnels, TODOs sur le locking |
| Tests | 45% | 324 tests sur 43 classes (encoding, stockage, structures natives, réseau, util, config build, section lifecycle, section storage, paths config, voxelization, thread, voxel mipper, IdRemapper, ScanMesher2D, ring utils, allocation list, position tracker) |
| Stabilite generale | 65% | Concurrence correctness fixée, intégrations Chunky/import propres |

---

## 2. Ce qui est FAIT et fonctionnel

### Portage NeoForge (complet)
- **Entrypoint**: `@Mod("voxy")` avec `IEventBus` NeoForge
- **Events**: Tous portes (`ServerStartedEvent`, `ChunkEvent.Load`, `PlayerEvent`, `ServerTickEvent`, etc.)
- **Networking**: Payload system NeoForge (`RegisterPayloadHandlersEvent`, `VoxyPacketPayload`)
- **Commandes**: `RegisterCommandsEvent` + `RegisterClientCommandsEvent`
- **Utilitaires**: `ModLoaderUtil.java` utilise `FMLPaths` et `ModList.get().isLoaded()`
- **Metadata**: `neoforge.mods.toml` correctement configure
- **Access Widener**: `voxy.accesswidener` adapte pour NeoForge/Loom

### Rendering core
- Pipeline hierarchique avec occlusion traversal
- Integration OpenGL (compute shaders, indirect rendering)
- Model baking et texture atlas
- Post-processing (fog, SSAO revert en cours)

### Integrations mods
- **Sodium**: 7 mixins fonctionnels (rendu, options GUI, chunk tracking)
- **Iris**: 10 mixins (pipeline, samplers, uniforms, shader patching)
- **Lithium**: Integration recemment fixee (commit `539fae6d`)

### Infrastructure
- Multiples backends de stockage (LMDB, RocksDB, Redis, in-memory)
- Compression ZSTD/LZ4/XZ
- Systeme de configuration par monde
- Threading avec services asynchrones

---

## 3. Problemes et bugs IDENTIFIES

### 3.1 Bug critique : Incohérence mixin config [RÉSOLU 2026-04-16]

Nettoyage effectué :
- `MixinWorld.java` supprimé (duplicata mort de `MixinLevelCommon`)
- `MixinGlDebug.java` supprimé (fichier 100% commenté, restaurable via git si besoin)
- `MixinBlockableEventLoop.java` supprimé (fichier 100% commenté, restaurable via git si besoin)
- `MixinStandardMacros` conservé (actif et fonctionnel, le README était dans l'erreur)
- README mis à jour pour refléter l'état réel

### 3.2 LOD Streaming + Iris [MITIGE / NON REPRODUIT 2026-04-16]

Le commit `c53b8e24` documentait : "continuous lod streaming = broken iris rendering".
Tests en jeu (BSL + Photon + Complementary Reimagined) avec le voxy.json par defaut integre :
le bug n'est plus reproductible de maniere detectable. Soit il a ete corrige indirectement
par les fixes d'integration Iris, soit il est suffisamment mitige pour ne pas etre genant.

**A ne rouvrir qu'en dernier recours** si des utilisateurs rapportent des symptomes specifiques
(flickers, trous de rendu correles au streaming LOD actif) avec un shader pack particulier.

### 3.3 Binding points hardcodes (Iris)

Dans `IrisVoxyRenderPipeline.java` :
- Binding indices 5, 10, 6 sont hardcodes avec des `// TODO: don't randomly make these`
- Risque de conflit avec d'autres mods/shader packs

### 3.4 ZSTD unsafe [RÉSOLU 2026-04-16]

`ZSTDCompressor.java` utilise maintenant `ZSTD_isError` pour détecter les échecs :
- `compress()` : throw `RuntimeException` avec le nom de l'erreur ZSTD + taille/niveau (échec = bug, pas corruption silencieuse)
- `decompress()` : log warn avec le nom de l'erreur ZSTD + dump hex des premiers octets, return `null` (pattern cohérent avec `LZ4Compressor`)
- Les appelants (`CompressionStorageAdaptor`, `SectionSerializationStorage`) gèrent déjà `null` = "section absente, à régénérer"

### 3.5 RocksDB configuration [RÉSOLU 2026-04-17]

`RocksDBStorageBackend.java` : options column family désormais séparées par CF
(DEFAULT / world_sections / id_mappings), fermeture propre, code mort
(`swizzlePos`) supprimé.

---

## 4. Ce qu'il RESTE A FAIRE

### Priorite HAUTE (bloquant pour un mod fonctionnel)

1. ~~**Fixer le streaming LOD + Iris**~~ [MITIGE / NON REPRODUIT 2026-04-16 — voir section 3.2]

2. ~~**Nettoyer les mixins incohérents**~~ [FAIT 2026-04-16]

3. ~~**Fixer la compression ZSTD**~~ [FAIT 2026-04-16]

4. ~~**Prérequis environnement : Java 21**~~ [FAIT — Java 21 installe, build OK]

5. ~~**Porter les mixins desactives**~~ [FAIT 2026-04-16 — MixinBlockableEventLoop + MixinGlDebug]

6. ~~**voxy.json par defaut integre**~~ [FAIT 2026-04-16 — fallback classpath pour packs sans integration]

### Priorite MOYENNE (amelioration significative)

5. ~~**Natives multi-plateforme (macOS)**~~ [NON PLANIFIE — macOS hors scope, base utilisateurs quasi nulle]

6. ~~**Bobby mod support**~~ [FAIT 2026-04-17 — `ModLoaderUtil.isModLoaded("bobby")`]

7. ~~**Porter les mixins desactives**~~ [FAIT 2026-04-16 — voir priorite HAUTE #5]

8. **Optimisations de concurrence** [PARTIEL 2026-04-17 — correctness-first]
   - FAIT : `Mapper.java` — `ObjectArrayList` remplacés par volatile snapshots copy-on-write,
     lectures désormais lock-free (TODOs 226/421/437 résolus).
   - FAIT : `AllocationArena.java` — layout élargi à 32/32 bits (max bloc 4 GB), `checkSize`
     fail-fast au lieu de corruption silencieuse (FIXME L.5 résolu).
   - FAIT : `ActiveSectionTracker.java` — timeout sur le spin-wait (warn 10s, throw 60s) avec
     seuils réglables pour les tests. Partie "jump back to service" du TODO L.177 conservée.
   - REPORTE : `MemoryStorageBackend` StampedLock (perf), `HierarchicalBitSet` (latence
     intra-thread), `AllocationArena` (AVL custom, alignment, FIXME merge addr=0),
     `ActiveSectionTracker` (VolatileHolder, cache global, thread check). TODOs préservés.
   - Tests ajoutés : `AllocationArenaLimitsTest` (3), `ActiveSectionTrackerTimeoutTest` (1).

9. ~~**Configuration des binding points Iris**~~ [FAIT 2026-04-17 — allocation top-down,
   collision-free sans coordination, constantes documentées]

### Priorite BASSE (fonctionnalités additionnelles)

10. **Integrations manquantes** [PARTIEL 2026-04-18]
    - FAIT — **Chunky** : `MixinNeoForgeWorld` wrap `ServerChunkCache#getChunkFutureMainThread`,
      auto-ingest des chunks générés par Chunky dans Voxy. Config mixin optionnelle
      (`voxy-chunky.mixins.json` `required:false`). Jar NF dans `libs/chunky-nf/` car
      Modrinth Maven résout `chunky:1.4.23` vers la variante Forge.
    - REPORTE — **Flashback** : aucun build NeoForge sur Modrinth pour 1.21.1
      (Fabric-only au 2026-04). Code à réinstaller depuis git si un port apparaît
      (`git show e98bddd4^:src/main/java/me/cortex/voxy/client/mixin/flashback/`).
    - REPORTE — **Nvidium** : aucun build NeoForge officiel sur Modrinth pour 1.21.1.
      Un fork non-officiel existe mais pas sur un repo Maven.

11. **Model rendering incomplet**
    - `ModelFactory.java` : 28 TODOs sur la transparence, occlusion, modeles de blocs
    - `RenderDataFactory.java` : 39 TODOs sur la generation de geometrie et l'eclairage

14. **Artefact grille sur eau LOD** [IDENTIFIE 2026-04-17 — non résolu]
    - Symptôme : quadrillage régulier visible sur toute la surface des lacs en rendu LOD,
      disparaît dès qu'on entre dans la render distance vanilla. Espacement aligné sur
      les frontières de sections meshées (multiples de 32 blocs).
    - Cause : double coupure au niveau du meshing fluide —
      (a) UVs locales par quad (`quads2.vert:108-111`) → la texture d'eau reset à chaque
          jointure de quad/section au lieu de tiler en espace monde ;
      (b) éclairage per-quad (`RenderDataFactory.java:564`) → saut de luminosité entre
          quads adjacents au lieu d'interpolation per-vertex.
    - Fix envisagé : passer en lighting per-vertex pour les fluides (4 ids au lieu de 1,
      impact vertex format) + UVs en world-space côté shader. 2-3× la taille de la passe
      concurrence, inconnue sur les bits restants dans le vertex format, risque de
      régression côté Iris.
    - Note secondaire : `Mipper.mip()` (L.31-54) choisit le voxel enfant avec la plus
      haute opacité → l'eau (opacity 0) perd systématiquement contre tout solide. Crée
      de petits îlots flottants aux rives (moins visible que la grille). Fix : majority-vote
      avec eau préférée en cas de parité, ~30 lignes dans `Mipper.java`.

12. **Import de monde** [FAIT 2026-04-18]
    - FAIT — `WorldImporter.java` : helper `upgradeChunkNbt()` qui passe chaque chunk dans
      `DataFixers.getDataFixer().update(References.CHUNK, ...)` avant `importChunkNBT`.
      Les anciens mondes (DataVersion < courant) sont upgrades avant extraction des sections.
    - FAIT — `DHImporter.java` : `org.xerial:sqlite-jdbc:3.46.1.3` ajouté en bundle/forgeRuntimeLibrary.
      `HasRequiredLibraries` flip à `true` au runtime — l'import DH devient fonctionnel.
    - REPORTE : TODOs perf restants (memory copy par section L.346 WorldImporter ;
      data cache XZ stream + VoxelizedSection 32³ L.276/279 DHImporter).

15. **Render distance LOD étendue** [FAIT 2026-04-18]
    - Cap du slider monté de 64 à 128 (= 4096 chunks vanilla, ~65 km). Permet de profiter
      pleinement d'une pré-génération Chunky. La VRAM/RAM sera le goulot pratique avant le
      cap arena (4 G éléments — voir notes dans `AllocationArena`).

13. **Tests** [EN COURS 2026-04-19]
    - 324 tests JUnit sur 43 classes (242 → 324, +82 ajoutés 2026-04-19) :
      - **Encoding / bit math** : `SaveLoadSystemTest`, `SaveLoadSystem3Test`,
        `WorldEngineKeyTest`, `MapperBitsTest` (incluant `composeMappingId` avec
        drop biome pour air et traitement light unsigned)
      - **Sérialisation sections** : `SaveLoadRoundTripTest`, `SaveLoadSystem3Test`,
        `SectionSerializerTest` (GZIP + header)
      - **Compression** : `LZ4CompressorTest` (round-trips + rejets corruption :
        header tronqué, taille négative, taille absurde, body invalide),
        `ZSTDCompressorTest` (round-trips + rejet payload garbage)
      - **Backends stockage** : `MemoryStorageBackendTest`, `LMDBStorageBackendTest`,
        `RocksDBStorageBackendTest` (avec @TempDir pour persistance)
      - **Adaptateurs stockage** : `CompressionStorageAdaptorTest`,
        `ReadonlyCachingLayerTest`, `FragmentedStorageBackendAdaptorTest`,
        `SectionSerializationStorageTest` (round-trip via SaveLoadSystem3,
        return codes 0/1/-1, suppression auto sur payload corrompu, iterate),
        `BasicPathInsertionConfigTest` (push/pop path, override absolu,
        balance du stack)
      - **Structures natives** : `HierarchicalBitSetTest`, `AllocationArenaTest`,
        `MemoryBufferTest`, `UnsafeUtilTest`
      - **Réseau** : `BloomFilterTest` (false-positive rate, serialization),
        `SharedBandwidthLimitTest` (fair sharing), `VoxyPacketPayloadTest`
        (rateUpdate/requestSections/cacheQuery/cacheResponse round-trips,
        delta-encoding compacité, limites batch size, rejets wrong message type)
      - **Utilitaires** : `PairTest`, `MessageQueueTest`,
        `ByteBufferBackedInputStreamTest`, `MultiGsonTest` (round-trip + erreurs :
        unknown class, duplicate field, mismatched arg count)
      - **Config build** : `ConfigBuildCtxTest` (resolvePath avec stack, paths
        absolus/drive letter, rejet `..`, substituteString, ensurePathExists)
      - **WorldSection** : `WorldSectionTest` (getIndex/getChildIndex, set,
        copyData, nonEmptyBlockCount, updateLvl0State, acquire/release lifecycle,
        tryAcquire sur section freed, dirty/inSaveQueue flags,
        updateEmptyChildState major/minor transitions)
      - **Voxelization** : `VoxelizedSectionTest` (pyramid layout 16³+8³+4³+2³+1,
        getBaseIndexForLevel, get/set par niveau, indexing yzx, zero/reset,
        constructeur avec backing array externe)
      - **Thread / weak maps** : `WeakConcurrentCleanableHashMapTest`
        (computeIfAbsent, distinct ids, clear retourne values, cleanup post-GC
        déclenche cleaner)
      - **Réseau (IDs)** : `VoxyNetworkHandlerCapabilitiesTest` (set/remove
        capabilities, indépendance par UUID), `IdRemapperTest` (mapping
        block/biome avec light préservé, branche air drop biome, defaults
        à zéro pour ids inconnus, reset clears state)
      - **commonImpl statiques** : `WorldIdentifierStaticsTest` (mixStafford13
        determinism + avalanche), `VoxyCommonTest` (verification flags,
        défauts, system properties, only literal "true")
      - **Mipper** : `MipperTest` (all-air branch, sky-light ceil, **pin du bug
        block-light over-shift dans le branch all-air**)
      - **Client utils** : `ExpandingObjectAllocationListTest` (put/release/get
        + reuse + croissance au-delà de 16), `RingUtilTest` (halfSphere,
        halfCircle, corner2D dans le rayon), `RingTrackerTest` (fill/unload
        symétriques, moveCenter petit/grand delta, stealing constructor),
        `ScanMesher2DTest` (round-trip random sparse, MAX_SIZE=16,
        merging row/colonne, reset)
      - **Position tracker** : `LoadedPositionTrackerTest` (mix Stafford,
        zero-key special slot, exchange, **pin du bug check-null inversé**
        dans la branche non-zero)
    - Ont capturé un bug réel dans `LZ4Compressor.decompress` (taille retournée incorrecte)
    - Bugs additionnels documentés (pinned via assertion) :
      - `LoadedPositionTracker.getSecOrMakeLoader` lance `IllegalStateException`
        sur tout slot fraîchement acquis pour `loc != 0` (check `value[pos]==null`
        inversé). Chemin jamais exécuté en prod, mais le test verrouille le
        comportement actuel pour casser quand le bug sera corrigé.
      - `Mipper.mip()` branche all-air over-shift `blockLight` (after `/8` la
        valeur est déjà à la position high-nibble, le `<< 4` la pousse hors
        du byte → composante block-light silencieusement à zéro). Le test
        verrouille le comportement actuel.
      - `AllocationArena.getLargestFreeBlockSize` (`tailSet(-1)` vide sous
        `compareUnsigned`) — non utilisé hors `main()`, laissé intact.
    - Reste à couvrir : Redis backend (nécessite infra), compression XZ (pas de wrapper),
      chemins d'ingestion, pipeline de rendu, classes dépendantes de Mapper
      (`IdRemapper` nécessite `Blocks.AIR.defaultBlockState()` = runtime Minecraft)

---

## 5. Statistiques du code

| Metrique | Valeur |
|----------|--------|
| Fichiers Java | ~219 |
| Taille totale du code | ~2.2 MB |
| TODOs/FIXMEs | **290** dans 75 fichiers |
| Mixins client actifs | 28 (10 Minecraft, 7 Sodium, 11 Iris) |
| Mixins common actifs | 1 (MixinLevelCommon) |
| Mixins morts/commentes | 0 (nettoyés le 2026-04-16) |
| Backends de stockage | 5 (LMDB, RocksDB, Redis, Memory, Fragmented) |
| Compresseurs | 3 (ZSTD, LZ4, LZMA/XZ) |

---

## 6. Dependances et compatibilite

### Requises
| Dependance | Version | Statut |
|------------|---------|--------|
| Minecraft | 1.21.1 | OK |
| NeoForge | 21.1.77+ | OK |
| Sodium (NeoForge) | 0.6.13 | OK |
| Java | 21 | **MANQUANT** (17 installe) |

### Optionnelles
| Dependance | Version | Statut |
|------------|---------|--------|
| Iris (NeoForge) | 1.8.0 | Compile, partiellement fonctionnel |
| Lithium (NeoForge) | 0.15.0 | Recemment fixe |

### Incompatibilites connues
- BetterFpsDist

---

## 7. Incohérences README vs Code [RÉSOLU 2026-04-16]

Toutes les incohérences ci-dessous ont été corrigées :

| Element | Ancien README | Etat actuel |
|---------|---------------|-------------|
| MixinStandardMacros | "Removed" (faux) | Retiré de "Removed", ajouté à la liste Iris active |
| MixinWorld | Liste comme actif | Supprimé (code mort) + retiré du README |
| MixinGlDebug | "Removed" (fichier existait) | Fichier réellement supprimé |
| MixinBlockableEventLoop | "Removed" (fichier existait) | Fichier réellement supprimé |
| Iris | "Required" | Corrigé en "Optional (for shader support)" |

---

## 8. Recommandations pour la suite

### Etape 1 : Setup environnement
1. Installer Java 21 (JDK)
2. `./gradlew genSources` pour generer les sources Minecraft decompilees
3. `./gradlew build` pour verifier la compilation
4. Tester dans un environnement Minecraft avec Sodium + Iris

### Etape 2 : Stabilisation
1. Nettoyer les mixins (supprimer le code mort, fixer les incoherences)
2. Corriger le README
3. Fixer le streaming LOD + Iris
4. Ajouter la gestion d'erreur ZSTD

### Etape 3 : Fonctionnalites
1. Porter les mixins desactives (GlDebug, BlockableEventLoop)
2. Activer le support Bobby si le mod NeoForge existe
3. Optimiser la concurrence (les TODOs critiques)

### Etape 4 : Qualite
1. Ajouter des tests au minimum sur la serialization/deserialization
2. Tester les differents backends de stockage
3. Tester la compatibilite avec differents shader packs
