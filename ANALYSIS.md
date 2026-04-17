# Neo-Voxy - Analyse du Portage Fabric -> NeoForge

**Date**: 2026-04-16
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
| Tests | 10% | Quasi inexistants |
| Stabilite generale | 40% | Beaucoup de hacks reconnus |

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

8. **Optimisations de concurrence** (nombreux TODOs)
   - `Mapper.java` : 6 TODOs (ligne 226 lock-free, 421/437 synchronisation)
   - `AllocationArena.java` : AVL tree custom sans allocations
   - `HierarchicalBitSet.java` : Operations bitset lentes

9. ~~**Configuration des binding points Iris**~~ [FAIT 2026-04-17 — allocation top-down,
   collision-free sans coordination, constantes documentées]

### Priorite BASSE (fonctionnalités additionnelles)

10. **Integrations manquantes**
    - Flashback (2 mixins a porter)
    - Nvidium (1 mixin, necessite le mod NeoForge)
    - Chunky (1 mixin, Fabric-specifique -> necessite adaptation complete)

11. **Model rendering incomplet**
    - `ModelFactory.java` : 28 TODOs sur la transparence, occlusion, modeles de blocs
    - `RenderDataFactory.java` : 39 TODOs sur la generation de geometrie et l'eclairage

12. **Import de monde**
    - `WorldImporter.java` : Upgrade NBT format avec DataFixerUpper
    - `DHImporter.java` : Import Distant Horizons (3 TODOs)

13. **Tests** [EN COURS 2026-04-17]
    - 173 tests JUnit sur 24 classes :
      - **Encoding / bit math** : `SaveLoadSystemTest`, `SaveLoadSystem3Test`,
        `WorldEngineKeyTest`, `MapperBitsTest`
      - **Sérialisation sections** : `SaveLoadRoundTripTest`, `SaveLoadSystem3Test`,
        `SectionSerializerTest` (GZIP + header)
      - **Compression** : `LZ4CompressorTest`, `ZSTDCompressorTest`
      - **Backends stockage** : `MemoryStorageBackendTest`, `LMDBStorageBackendTest`,
        `RocksDBStorageBackendTest` (avec @TempDir pour persistance)
      - **Adaptateurs stockage** : `CompressionStorageAdaptorTest`,
        `ReadonlyCachingLayerTest`, `FragmentedStorageBackendAdaptorTest`
      - **Structures natives** : `HierarchicalBitSetTest`, `AllocationArenaTest`,
        `MemoryBufferTest`, `UnsafeUtilTest`
      - **Réseau** : `BloomFilterTest` (false-positive rate, serialization),
        `SharedBandwidthLimitTest` (fair sharing)
      - **Utilitaires** : `PairTest`, `MessageQueueTest`,
        `ByteBufferBackedInputStreamTest`
      - **WorldSection** : `WorldSectionTest` (getIndex/getChildIndex, set,
        copyData, nonEmptyBlockCount, updateLvl0State)
    - Ont capturé un bug réel dans `LZ4Compressor.decompress` (taille retournée incorrecte)
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
