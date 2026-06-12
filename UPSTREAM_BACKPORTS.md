# Suivi des backports upstream (MCRcortex/voxy)

Source de vérité du rapatriement des commits upstream vers neo-voxy.

- **Point de fork** : `67a5a23d` (dernier commit upstream avant le portage Neo-Voxy v0.1, 2026-01-19)
- **Branche upstream suivie** : `upstream/dev` (`git remote add upstream https://github.com/MCRcortex/voxy.git`)
- **233 commits** analysés entre le fork et upstream `dev` au 2026-06-11
- ⚠️ Upstream est passé sur **MC 26.1 le 2026-03-26** (`38540eac`) : les commits postérieurs nécessitent adaptation manuelle, voire réimplémentation (lire le diff comme spec, pas comme patch). On reste sur **MC 1.21.1**.

Statuts : ✅ porté · 🟰 déjà couvert par un fix local équivalent · ⏭ non applicable (code divergent) · ⏳ à faire · 🚫 skippé (décision)

## Lot 1 — Fixes critiques (PR `backport-critical-fixes`)

| SHA | Quoi | Statut | Notes |
|-----|------|--------|-------|
| `d0e879b6` | fix lightmap texture Iris | ✅ | Adapté API 1.21.1 (`lightTexture.getId()`), sampler 0 forcé pour samplers externes |
| `11f4fdfc` | fix RD config ×16 | ⏭ | Notre UI réécrite (`VoxyConfigScreenPages`) passe déjà `sectionRenderDistance` directement |
| `3eda8590` | fix désérialisation biomes | 🟰/✅ | Fallback biome manquant déjà présent ; portés : return-null sur duplicate + garde null ColorResolver |
| `f453d555` | GlTexture formats (R32UI→RED_INTEGER, RG16F) | ⏭ | Notre `GlTexture` antérieur, sans `zero()`/`getFormat()`. À reconsidérer si on porte le bakery (Lot 4.8) |
| `437e2e0b` | block light packing mip LoD | ✅ | Masque `& 0xF0` après /8 (résidu corrompait skyLight) ; le `<<4` était déjà fixé chez nous |
| `81459153` | fix occlusion face X | 🟰 | Notre `isFaceCoveredByNeighbor` (`selfFace ^ 1`) a déjà les bonnes orientations |
| `a5c7f564` + `91d4f7c8` | fix neighbor check cull-same | ✅ | Raccourci réduit à `cullsSame(meta)` ; remplace notre garde `faceCanBeOccluded` |
| `5d407397` | AABB `Math.max` (bug 1+ an) | ✅ | Clamp des extents dégénérés dans le packing AABB |

## Lot 2 — Stabilité / concurrence (PR `backport-stability`)

| SHA | Quoi | Statut | Notes |
|-----|------|--------|-------|
| `352da265` + `ebea10c8` | **CRITICAL** fuite mémoire RocksDB + double close | ✅ | Iterator id-mappings fermé, handles fermés après options, `db.closeE()` en dernier. Version native 8.10.0 conservée (le fix upstream est purement code, leur bump 10.9.1 a été reverté) |
| `136381a7` | fix deadlock save-queue à l'unload | ✅ | Chaîne `nonBlocking` saveSection→enqueueSave, sleep→yield, throw BAD POS retiré (NodeManager) |
| `192721a7` | race condition rare à l'unload | ✅ | `shouldSave()`, saveSection renvoie l'enqueue, retry sous lock si refs/dirty regagnés, assert freed-while-dirty |
| `3205a336` | toujours markDirty même si en save queue | ✅ | Sinon changement perdu ; le clear est fait par le saving service |
| `c7166d3f` | checks + unmarkDirty dans saving service | 🟰 | Entièrement couvert par le port de `192721a7` |
| `d2f87345` | unlock on error (VoxyInstance.getOrCreate) | ✅ | Même fuite de write lock chez nous |
| `5f216fa1` | enqueue bake atomique avec in-flight | ✅ | Deps fluides avant le gate, enqueue sous le lock |
| `c2ba3c22` | while-loop processAllThings | 🟰 | Notre boucle wait/notify (timeout 100 ms) couvre le cas |
| `6189ee38` | fix ingestion chunks au respawn/téléport | ✅ | Vérif position dans cheekyGetChunk + getChunk(FULL, false) dans les 2 chemins d'ingest |
| `36964ee4` + `2a979ac0` + `c6b30e51` | lock file exclusif | 🚫 | Différé : désactivé par défaut upstream même aujourd'hui, faible valeur pour notre usage |

## Lot 3 — Qualité rendu / features (PR `backport-render-quality`)

| SHA | Quoi | Statut | Notes |
|-----|------|--------|-------|
| `0781dd47` | traversal = cercle lisse RD, plus de pop-in | ✅ | Uniform `renderDistance` + cull XZ circulaire dans `traversal_dev.comp`, tracker +1 |
| `a19d5d0f` | pas d'auto-enqueue render en bord de RD | ✅ | `furthestPointToCamera` + gate sur enqueueSelfForRender |
| `9694968d` | fix fade-in des chunks | ✅ | Strip des markers `_cfi_ignoreMarker` du patch json Iris |
| `b33ad015` + `62099a74` | support émissif | 🟰 | Déjà implémenté chez nous (`9f2c4853` « Fix light not emit from LODs ») |
| `ff3a84cf` | clamp émission 0-15 | ✅ | Mods peuvent déclarer hors plage → overflow du champ 4 bits |
| `260bcdbc` | détection dynamique layer modèle | ✅ | Adapté à notre flux RenderType ; translucent sans pixel translucide → solid/cutout |
| `26949ee1` + `7d785cda` | stairs bakées comme bloc de base | ✅ | `withPropertiesOf` + accesswidener `StairBlock.baseState` |
| `ad5f6ee0` | fix warning driver AMD | ✅ | Skip `glDispatchCompute(0)` |
| `7446e9ec` | RD float à incréments fins | 🚫 | Notre UI config réécrite a sa propre granularité ; ripple config/réseau pour gain UX mineur |
| `0033da2a` | valeur config vs effective | ⏭ | Le check warning visé n'existe pas chez nous |

## Lot 4 — Performance (différé, au cas par cas, réimplémentation si port impossible)

| # | SHAs | Quoi | Statut |
|---|------|------|--------|
| 1 | `1511bf36` + `5dcaa23b` + `7d511421` | pipeline sérialisation sans memcopy/realloc | ⏳ |
| 2 | `80d217d8` + `23b095b0` + `6a691211` | ExpandingObjectAllocationList borné + request ids 19 bits | ⏳ |
| 3 | `0ba739f9` | pas de meshing pendant baking intensif | ⏳ |
| 4 | `1f993f8e` | opto mesh factory | ⏳ |
| 5 | `e5af2c91` + `eaf107e4` | alignement upload stream (capacités OpenGL) | ⏳ |
| 6 | `4333864c` | centralisation unpacks de position | ⏳ |
| 7 | `d7782df2` + `40a62448` + `a5afb2fb` + `672ee7c7` | réutilisation geometry buffer + texture atlas | ⏳ |
| 8 | `5ca0fa73` + `e62beff1` + `0637d1ad` + `fd81fd18` | bakery software raster + baking off-thread + meshing sans limite (**le plus gros**) | ⏳ |
| 9 | `60858794` + `a5bb6a73` | fix gros texture packs / atlas (dépend possiblement de 8) | ⏳ |

## Skippés (décision, pas de port)

- Port MC 26.1 et dépendances strictes (Java 25, loader, FAPI, bumps Sodium 0.8.x)
- **rev-z** (`d6231c7f`…`727fddba`) — gros chantier lié à la nouvelle base, risque Iris
- **SSAO v2** (avril 2026) — notre port SSAO (`e56af799`) en place ; réévaluer si artefacts
- TAA (`68f782d6`, `08a17128`, `5779f52d`)
- `8eb5afc5` stats rendu vers F3 — préférence UI, optionnel
- Build/CI, bumps de version
