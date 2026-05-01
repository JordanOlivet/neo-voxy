# Neo-Voxy - Analyse du Portage Fabric -> NeoForge

**Date**: 2026-04-25 (dernière mise à jour)
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
| Rendu (pipeline core) | 85% | Phantom occlusion + AABB extent + ingestion gate fixés (2026-04-25) |
| Streaming LOD | 60% | Partiellement fonctionnel, edge cases cassent |
| Integration Iris | 75% | Fonctionne mais binding points hardcodes |
| Integration Sodium | 88% | Mixins en place, fonctionnel + accessor RenderSectionManager pour diag |
| Stockage (backends) | 70% | LMDB/RocksDB fonctionnels, TODOs sur le locking |
| Tests | 45% | 329 tests sur 43 classes (encoding, stockage, structures natives, réseau, util, config build, section lifecycle, section storage, paths config, voxelization, thread, voxel mipper, IdRemapper, ScanMesher2D, ring utils, allocation list, position tracker) |
| UX / config | 80% | Lang strings complètes, F3 overlay actif (toggle render statistics fonctionnel) |
| Stabilite generale | 72% | Concurrence correctness fixée + holes LOD résolus (phantom occlusion / ingest partial) |

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

### 3.3 Binding points hardcodes (Iris) [RÉSOLU 2026-04-17]

Voir item 9 section 4. `IrisVoxyRenderPipeline.java:30-73` alloue désormais
top-down depuis les maxima du contexte GL (`GL_MAX_UNIFORM_BUFFER_BINDINGS`,
`GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS`, `GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS`).
Iris/Sodium/shader packs allouent bottom-up depuis 0 → conflict-free sans coordination.

### 3.4 ZSTD unsafe [RÉSOLU 2026-04-16]

`ZSTDCompressor.java` utilise maintenant `ZSTD_isError` pour détecter les échecs :
- `compress()` : throw `RuntimeException` avec le nom de l'erreur ZSTD + taille/niveau (échec = bug, pas corruption silencieuse)
- `decompress()` : log warn avec le nom de l'erreur ZSTD + dump hex des premiers octets, return `null` (pattern cohérent avec `LZ4Compressor`)
- Les appelants (`CompressionStorageAdaptor`, `SectionSerializationStorage`) gèrent déjà `null` = "section absente, à régénérer"

### 3.5 RocksDB configuration [RÉSOLU 2026-04-17]

`RocksDBStorageBackend.java` : options column family désormais séparées par CF
(DEFAULT / world_sections / id_mappings), fermeture propre, code mort
(`swizzlePos`) supprimé.

### 3.6 ChunkBoundRenderer phantom occlusion [RÉSOLU 2026-04-25 — option B]

Bug historique reproduit indépendamment du portage : certains chunks LOD
deviennent invisibles à proximité du bord de la render distance vanilla.
Le bug est position-dépendant en monde, indépendant de la rotation caméra,
hyper-sensible (±1-2 blocs de déplacement le toggle), déterministe par RD vanilla.

- **Cause** : `ChunkBoundRenderer.render()` rasterise une AABB pleine 16³ par section
  Sodium dans le depth-bounding buffer. Le shader `quads.frag` discard les pixels où
  `gl_FragCoord.z < depthTex.r`. Les sections au bord de la RD vanilla sont typiquement
  partiellement peuplées (terrain en bas + air en haut) → l'AABB pleine over-cover
  la portion vide → les pixels LOD censés rendre derrière sont discardés.
- **Fix appliqué (option B, surgical)** : skip de la rastérisation AABB pour les sections
  dans l'anneau extérieur de la RD vanilla (`chebXZ ≥ vanillaRD - 1`).
  - `outline.vsh` : test chebyshev XZ vs camera chunk, emission d'un vertex dégénéré.
  - `ChunkBoundRenderer.java` : RD vanilla packée dans `section.w` de l'uniform.
  - `VoxyCommands.java` : commande `/voxy debugBounds` pour toggler `DEBUG_DISABLE_DEPTH_BOUNDS`
    au runtime → si l'invisible réapparaît quand off → confirmé phantom-occlusion.
- **Tradeoff** : les sections de l'anneau perdent le bénéfice depth-bound ; le LOD
  overdraw dans la zone vanilla mais le z-test final résout correctement. Coût perf
  imperceptible (bande étroite).
- **Option A documentée** (à appliquer si récurrence loin du bord — îles flottantes,
  grandes grottes ouvertes au ciel) : calculer des AABB tight par section côté Java
  (itérer `LevelChunkSection.getStates()` dans le mixin `voxy$updateOnUpload`),
  packer les bounds dans le format d'upload (12 bytes au lieu de 8), modifier
  `outline.vsh` pour utiliser ces bounds. Note : `BuiltSectionInfo` Sodium n'expose
  PAS de bounds, à computer nous-mêmes.
- **Diag tooling** : `/voxy dumpbounds [radius]` dump dans le log la liste des
  sections trackées + leur statut Sodium (built / visible / flags), tags ORPHAN /
  PHANTOM / INVISIBLE / EDGE pour repérer les anomalies. Mixin
  `AccessorRenderSectionManager` pour accès au `Long2ReferenceMap<RenderSection>`,
  `ChunkBoundRenderer._debugGetTrackedPositions()` pour le snapshot.
- Trace mémoire : `bug_chunkbound_phantom_occlusion.md` dans le memory store.

### 3.7 AABB extent=0 underflow + ingestion gate [RÉSOLU 2026-04-25]

Deux bugs latents identifiés lors de l'analyse du phantom occlusion :

- **AABB extent=0 underflow** dans `RenderDataFactory.java` (~L.149-176) : l'encoding
  `(max-min-1)<<shift` corrompt silencieusement les axes où la géométrie a une
  épaisseur de 1 (ex : un seul plan de quads). Fix : `auxPos + 1` au lieu de
  `auxPos` sur les 3 axes normaux (X/Y/Z) avec commentaire explicatif.
- **Ingestion gate** : les sections LOD étaient meshées même partiellement
  ingérées, produisant de la géométrie sparse rendue comme chunks visuellement
  transparents à distance LOD. Fix :
  - `WorldSection.java` : ajout de `volatile byte ingestedOctantMask` + VarHandle,
    méthodes `markOctantIngested(int)`, `isFullyIngested()`, `getIngestedOctantMask()`,
    `_unsafeSetFullyIngested()`. Bitmask des 8 octants 2×2×2.
  - `WorldUpdater.java` : marque l'octant correspondant à chaque ingest VS,
    flag `justBecameFullyIngested` propagé jusqu'au `markDirty` pour forcer
    un re-mesh quand le dernier ingest n'écrit que des données identiques
    (`didStateChange=false`). Suppression du `break` early-exit pour que chaque
    niveau LOD reçoive son `markOctantIngested`.
  - `SaveLoadSystem.java` / `SaveLoadSystem2.java` / `SaveLoadSystem3.java` :
    `_unsafeSetFullyIngested()` post-deserialize (les 32768 voxel slots sont
    écrits, la section est complète, bypass de la gate).
  - `RenderDataFactory.generateMesh()` : early return `BuiltSection.emptyWithChildren`
    si `!section.isFullyIngested()`.

### 3.8 F3 debug overlay vide + lang strings manquantes [RÉSOLU 2026-04-25]

- **F3 overlay** : `VoxyDebugScreenEntry.java` était 100% commenté (l'API Fabric
  `DebugScreenEntry` n'a pas d'équivalent direct en NeoForge 1.21.1). Réactivé
  via `CustomizeGuiOverlayEvent.DebugText`, listener enregistré dans `Voxy.java`
  côté client. Affiche : header `voxy-<version>` (couleur selon état),
  `VoxyInstance.addDebug` (MemoryBuffer / I/S/AWSC), `VoxyRenderSystem.addDebugInfo`
  (model bakery, render gen, node manager, pipeline) et **`RenderStatistics.addDebug`
  (HTC/HRS/VS/QC) quand le toggle est activé** — fix du toggle "Show Render Statistics"
  qui était mort.
- **Lang strings** : 6 entrées manquantes dans `en_us.json` (`serviceThreads`,
  `useSodiumBuilder`, `rendering`, `subDivisionSize`, `vanilla_fog`,
  `render_statistics`) — les options de l'écran config affichaient les clés
  brutes (`voxy.config.general.serviceThreads`). Ajoutées avec libellés et
  tooltips appropriés.

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

11. **Model rendering incomplet** [EN COURS 2026-04-26]
    - `ModelFactory.java` : 28 TODOs sur la transparence, occlusion, modeles de blocs
    - `RenderDataFactory.java` : 39 TODOs sur la generation de geometrie et l'eclairage
    - FAIT 2026-04-25 — AABB extent=0 underflow corrigé (`auxPos+1` sur 3 axes
      normaux, voir 3.7).

    ### Familles de TODOs identifiées

    **(a) Transparence / opaque-mask** (FIXME `ModelFactory:103-126`) [FAIT 2026-04-27]
    - Feuilles, verre teinté translucides (alpha utilisé pour la couleur) mais
      pixels soit opaques soit transparents — pipeline ne sait pas distinguer.
    - Pas de **occlusion mask par face** (16×16 bits = 4 longs) → occlusion
      full-block grossière, blocs partiels (comparateur, dalles) mal occluded.
    - Conséquence : face culling cassé sur frontières de section pour
      translucides (`RenderDataFactory:21,477,548`), `DISABLE_CULL_SAME_OCCLUDES`
      figé à `false` faute de fix.
    - **FAIT 2026-04-27 (infrastructure)** : `ModelFactory` calcule et stocke
      maintenant un masque d'occlusion par face (4 longs = 256 bits par face,
      24 longs par blockstate) dans `faceOcclusionMaskCache[]`, alimenté
      pendant le bake via `TextureUtils.wasPixelWritten`. Constantes
      `OCCLUSION_MASK_LONGS_PER_FACE = 4`, getter
      `getFaceOcclusionMaskLong(clientId, face, longIdx)` et helper
      `isFaceFullyOccludedBy(selfClientId, selfFace, neighborClientId,
      neighborFace)` exposés. `TextureUtils.wasPixelWritten` rendu
      package-visible. TODO ModelFactory L.109-112 résolu en note.
    - **FAIT 2026-04-27 (wiring)** : helper `isFaceCoveredByNeighbor(selfId,
      selfFace, neighborId, neighborMeta)` ajouté dans `RenderDataFactory` —
      OR de l'ancien `faceOccludes(neighbor, opp)` (couvre self transparent
      derrière mur opaque) ET du nouveau `isFaceFullyOccludedBy` (couvre
      partial-vs-partial subset). 8 callsites wirés sur 14 :
      `shouldMeshNonOpaqueBlockFace` (L.~360), opaque inner YZ (L.~419),
      opaque outer YZ (L.~485), non-opaque outer YZ branches fail/failB
      (L.~795/~810), opaque inner X (L.~975), opaque outer X branches -x/+x
      (L.~1040/~1066). Les 5 sites fluide (YZ inner L.~552, YZ outer L.~660,
      X inner L.~1175, X outer -x L.~1281, X outer +x L.~1342) ne sont
      **pas** wirés : self fluid → `occludesFace==false` → mask vide → check
      mask retourne toujours false → OR collapse au boolean legacy →
      wiring sans gain sémantique. Documenté in-line.
    - **Effet attendu** : améliore le culling dans deux cas qui passaient à
      travers avant : (i) deux blocs partiels même-orientation se faisant
      face en frontière de section (escaliers same-orientation, dalles), où
      maintenant la cover par-pixel détecte le subset ; (ii) tous les sites
      où le voisin a un mask full mais que `faceOccludes` était stricte (rare
      mais existe). Les régressions visuelles sur self-transparent-vs-mur
      sont évitées par le OR avec le legacy.
    - **FAIT 2026-04-29 (suivi cullsSame asymétrique)** : ajout du gate
      `ModelQueries.faceCanBeOccluded(selfMeta, selfFace)` aux 6 callsites
      `cullsSame` de `RenderDataFactory` :
      `shouldMeshNonOpaqueBlockFace` (L.~370), opaque inner YZ (L.~432),
      opaque outer YZ (L.~507), fluid YZ outer (L.~681),
      X fluid outer -x (L.~1351), X fluid outer +x (L.~1415). Aux deux
      premiers sites opaque-YZ, `selfMeta` est désormais chargé depuis
      `sectionData[idx*2 + 1]`. Effet : un bloc partiel (face à offset ≥ 0.3)
      ne se fait plus culler par le shortcut cull-same même si le voisin a
      le même model id — seul le mask wiring (étape 4) peut le culler à ce
      stade, et uniquement si le voisin couvre vraiment la face. Le flag
      `DISABLE_CULL_SAME_OCCLUDES` est laissé à `false` avec une note
      détaillée (L.21-29) expliquant que sa sémantique est inversée par
      rapport à son nom (flip = cull plus agressif, pas l'inverse) : le bug
      stained-glass-aux-bordures (item 11b) n'est pas lié à ce flag et
      reste ouvert pending repro runtime.

    **(b) Self-occlusion / culling fin** (`RenderDataFactory:774,789,954,1021,1043,1151`) [VERIFIE 2026-04-25]
    - Audit des 6 sites : tous suivent un pattern asymétrique (cull si neighbor
      face occlude OU si neighbor a même model id, sans gate sur "ce côté
      peut-il être occlus"). C'est conservateur et correct pour les blocs
      pleins ; sur-cull les blocs partiels (escaliers/dalles same-orientation
      en frontière de section), coût visuel faible.
    - Cause profonde : `ModelQueries.faceCanBeOccluded` (bit 0b100) n'est
      **jamais** mis dans `ModelFactory.java` → la donnée per-face occlusion
      n'existe pas. Toute correction symétrique nécessite d'abord d'introduire
      ce bit, ce qui est précisément le travail de l'étape 4 (opaque mask par
      face, famille (a)).
    - Action : 6 TODOs `// TODO check self occlusion` remplacés par des notes
      explicatives référençant la dépendance à l'étape 4 (règle trace
      d'intention). Aucun changement de logique.
    - **Reste ouvert** : TODO L.21 + FIXME L.350 sur les translucents
      (`stained glass breaking on chunk boarders`) — symptôme documenté mais
      cause incertaine, reproduction runtime nécessaire pour aller plus loin.
      Peut être lié au bug ci-dessus, peut être une autre divergence
      inner/border. **Laissé strictement intact**.

    **(c) Lighting** (`RenderDataFactory:803,1285,1346` + L.571,1182 — `// TODO: LIGHTING`) [VERIFIE 2026-04-25]
    - Audit pipeline complet : l'éclairage est **flat per-quad de bout en bout**
      par construction.
      • `ScanMesher2D.putNext` (L.44, L.66) merge deux voxels uniquement si leur
        `data` long est strictement égal — les 8 bits de lighting font partie
        du long → deux voxels d'éclairage différent ne fusionnent pas. Chaque
        quad émis a déjà un éclairage uniforme.
      • Côté shader (`quads2.vert:138`), le calcul de `tinting` se fait
        uniquement à `cornerIdx == 1` (provoking vertex), output `flat uvec4`
        (L.17). Aucun chemin d'interpolation par-vertex.
    - **Per-vertex lighting = refactor pipeline**, pas un nettoyage de TODOs :
      change le format géométrique (4 corners light = 32 bits/quad au lieu de
      8), mesher doit échantillonner aux 4 coins, shader doit passer en
      interpolation `smooth`. Substantiel.
    - **Correction (2026-04-27)** : claim initial erroné — `faceUsesSelfLighting`
      (bit 0b1000 par face) **est** bien mis dans `ModelFactory.java` (~L.590)
      pour les faces avec `offset > 0.01` ou `RenderType.translucent()`. Donc
      lanternes/cake-shaped/translucents s'auto-éclairent déjà. Le gap réel
      (TODO `ModelFactory:391`) concerne uniquement les blocs émissifs
      pleine-cube (glowstone, magma, sea lantern) : offset ≈ 0 et non
      translucides → fall-through les deux gates → tirent leur éclairage d'un
      voxel d'air voisin en LOD au lieu d'eux-mêmes.
    - Action : 5 sites `// TODO: LIGHTING` + dead code commenté résolus en
      notes explicatives (règle trace d'intention). Code commenté inutile
      retiré (le fallback `lighter = sectionData[bi]` était identique à la
      ligne active). YZ non-opaque outer (RDF:803) légèrement nettoyé : le
      ternaire toujours-faux `(false ? 0L : 1L)` remplacé par sa valeur
      directe.
    - **Lien au point 14** : oui mais le fix est l'étape per-vertex, pas un
      réarrangement des branches actuelles.

    **(d) Blocs spéciaux** (`ModelFactory:460-493`)
    - Couleurs constantes / tinting biome non géré pleinement (`829`).
    - Cas spéciaux : vines, glow lichen (`475`), faces orientées par
      alignment-depth (`531`), feuilles avec AO (`653`).

    **(e) Atlas / mémoire** (`ModelFactory:155-158,996-1029`) [FAIT 2026-04-28]
    - **FAIT 2026-04-28** : `MODEL_TEXTURE_SIZE` 16 → 8. Gain VRAM atlas :
      512 MiB → 128 MiB (-384 MiB, l'allocation GPU dominante de Voxy). Heap
      `faceOcclusionMaskCache` : 12 MiB → 3 MiB. Bake CPU ~4× plus rapide
      (4× moins de pixels par face). `OCCLUSION_MASK_LONGS_PER_FACE` 4 → 1
      (8×8=64 bits tient dans un long), `isFaceFullyOccludedBy` simplifié à
      un seul AND-NOT. Build clean.
    - **Risque visuel** : à LOD0 (premier ring après RD vanilla, le plus
      proche), le mip-0 perdu peut être visible. Aux LOD1+, invisible (le
      sampler choisit déjà mip 1+ à distance). À tester en jeu.
    - **Reste ouvert** : blits de baking non batchés (6 blits séparés) →
      CPU/GPU sync overhead. Hors scope (perf marginale, complexité élevée).

    **(f) Géométrie inter-section** (`RenderDataFactory:679,873,1075`) [VERIFIE 2026-04-25]
    - Vérification du code : les TODOs sont des notes de vérification stale, pas
      une fonctionnalité manquante. L'architecture Inner/Outer couvre déjà toutes
      les faces correctement :
      • `generateYZNonOpaqueInnerGeometry` parcourt layers 1..30 ; les layers 0 et 31
        (frontières de section) sont émis par `generateYZNonOpaqueOuterGeometry` via
        `neighboringFaces[]`.
      • Pour X axis : `msk &= -1>>>1` retire le bit 31 spurieux (transition
        "voxel 31 vs rien") dans Inner ; la face +x boundary est émise par
        `generateXOuterOpaqueGeometry` / `generateXOuterFluidGeometry` via
        `neighboringFaces[i+32*32]`.
    - Action : 3 TODOs remplacés par des notes `// Verified (2026-04-25): ...`
      expliquant pourquoi chaque ligne est correcte (règle trace d'intention).
    - Si trous fins persistent en jeu, la cause est ailleurs (probablement
      family (b) self-occlusion ou (c) lighting), pas le meshing inter-section.

    ### Plan d'attaque (5 étapes, ordre impact/effort)

    | # | Étape | Famille | Statut |
    |---|-------|---------|--------|
    | 1 | Faces inter-section (RDF:679,873,1075) | (f) | FAIT 2026-04-25 (vérif : architecture déjà correcte, 3 TODOs résolus en notes) |
    | 2 | Self-occlusion correcte (regrouper TODOs) | (b) | PARTIEL 2026-04-25 (6 TODOs résolus en notes ; fix réel bloqué sur étape 4 ; bug stained glass L.21/L.350 laissé intact, demande reproduction runtime) |
    | 3 | Lighting per-vertex pour fluides + opaques non-cull → résout aussi item 14 (grille eau) | (c) | REPORTÉ 2026-04-25 (vrai fix = refactor pipeline géométrie+shader, pas TODO sweep ; 5 sites résolus en notes ; cause racine du item 14 confirmée mais pas adressée) |
    | 4 | Opaque mask par face (16×16 bits) → débloque transparence propre + occlusion fine | (a) | FAIT 2026-04-27 (infrastructure + wiring : 8/14 callsites — opaque/non-opaque ; 5 sites fluide intentionnellement skippés car self.mask vide → no-op ; documenté in-line) |
    | 5 | Atlas 8×8 (perf/mémoire pure) | (e) | FAIT 2026-04-28 (-384 MiB VRAM atlas, -9 MiB heap mask cache, bake ~4× plus rapide ; vérif visuelle in-game à faire à LOD0) |

    Note : famille (d) blocs spéciaux est traitée opportunistiquement au fil
    des étapes 2-4 selon les cas qu'elles débloquent.

16. **Holes / chunks invisibles en rendu LOD** [FAIT 2026-04-25]
    - Phantom occlusion en bord de RD vanilla — option B appliquée (3.6).
    - Ingestion partielle visible comme transparence — gate ajoutée (3.7).
    - Diag tooling en place : `/voxy debugBounds`, `/voxy dumpbounds [radius]`.
    - Reste possible : option A (tight AABBs per-section) si récurrence loin du
      bord. Path documenté en 3.6 + memory store.

17. **UX écran config + F3** [FAIT 2026-04-25]
    - Toutes les options de l'écran config Voxy ont désormais un libellé et une
      tooltip (en_us.json complété : 6 entrées manquantes).
    - F3 debug overlay réactivé via `CustomizeGuiOverlayEvent.DebugText`.
      Le toggle "Show Render Statistics" affiche maintenant réellement les
      compteurs HTC/HRS/VS/QC.

14. **Artefact grille sur eau LOD** [IDENTIFIE 2026-04-17 — non résolu — diagnostic complété 2026-04-25]
    - Symptôme : quadrillage régulier visible sur toute la surface des lacs en rendu LOD,
      disparaît dès qu'on entre dans la render distance vanilla. Espacement aligné sur
      les frontières de sections meshées (multiples de 32 blocs).
    - Cause : double coupure au niveau du meshing fluide —
      (a) UVs locales par quad (`quads2.vert:108-111`) → la texture d'eau reset à chaque
          jointure de quad/section au lieu de tiler en espace monde ;
      (b) éclairage per-quad **structurel** (pas un bug à fixer ponctuellement) :
          `ScanMesher2D` fusionne sur égalité stricte du `data` long (qui inclut les 8 bits
          de lighting) → chaque quad a déjà un éclairage uniforme. Côté shader,
          `quads2.vert:138` calcule `tinting` uniquement sur le provoking vertex
          (`cornerIdx == 1`) avec output `flat uvec4` (L.17) — aucun chemin
          d'interpolation par-vertex n'existe. (Voir ANALYSIS.md item 11(c) [VERIFIE].)
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
    - 329 tests JUnit sur 43 classes (242 → 329, +87 ajoutés 2026-04-19) :
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
      - **Mipper** : `MipperTest` (all-air branch, sky-light ceil, block-light
        averaging — couvre le fix du bug d'over-shift)
      - **Client utils** : `ExpandingObjectAllocationListTest` (put/release/get
        + reuse + croissance au-delà de 16), `RingUtilTest` (halfSphere,
        halfCircle, corner2D dans le rayon), `RingTrackerTest` (fill/unload
        symétriques, moveCenter petit/grand delta, stealing constructor),
        `ScanMesher2DTest` (round-trip random sparse, MAX_SIZE=16,
        merging row/colonne, reset)
      - **Position tracker** : `LoadedPositionTrackerTest` (mix Stafford,
        zero-key special slot, exchange, get/store non-zero keys, distinct
        keys, capacité défaut — couvre le fix du bug check-null inversé)
    - Bugs capturés et **corrigés** par les tests :
      - `LZ4Compressor.decompress` retournait une taille incorrecte (corrigé
        2026-04-18).
      - `LoadedPositionTracker.getSecOrMakeLoader` (corrigé 2026-04-19) :
        l'assertion `value[pos] == null` était inversée — un slot fraîchement
        CAS-acquis doit être null, donc le throw doit être `!= null`. Le
        chemin non-zero key est désormais utilisable.
      - `Mipper.mip()` branche all-air (corrigé 2026-04-19) : `blockLight`
        après `/8` est déjà aligné high-nibble, le `<< 4` final le poussait
        hors du byte → composante block-light silencieusement à zéro. Fix :
        retirer le `<< 4`.
    - Bug restant non corrigé (laissé intact, code mort) :
      - `AllocationArena.getLargestFreeBlockSize` (`tailSet(-1)` vide sous
        `compareUnsigned`) — non utilisé hors `main()`.
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
| Mixins client actifs | 29 (10 Minecraft, 8 Sodium, 11 Iris) |
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
