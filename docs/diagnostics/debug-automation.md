# Debug Automation — Permettre à Claude de "voir" le mod en jeu

## Contexte

Neo-Voxy fait du rendu LOD massif avec un pipeline complexe (compute shaders d'occlusion, MDIC, async node loading, intégration Iris). Les bugs visuels sont la principale source de régressions, mais ils sont **extrêmement coûteux à diagnostiquer par chat** :

- Décrire textuellement un glitch de rendu (z-fighting, frustum culling cassé, LOD pop-in, shadow leaking, fog blending) prend des paragraphes et reste ambigu
- Les screenshots ponctuels ne suffisent souvent pas : il faut souvent voir l'angle, la distance, la transition entre LODs, le comportement avec/sans Iris, etc.
- L'aller-retour utilisateur ↔ assistant fait que beaucoup de fixes proposés "ratent" la cause réelle et cassent autre chose

**Objectif** : raccourcir radicalement la boucle de diagnostic en donnant à Claude un accès direct (ou semi-direct) à ce que le client Minecraft rend, idéalement avec aussi la capacité de piloter la session (téléport, changement de paramètres, reload) et de lire les états internes du mod (stats Voxy, uniforms, états des sections).

Trois options sont retenues :

- **Option 1** : Screenshots à la demande purs — setup quasi nul, démarrage immédiat
- **Option 1.5** : Screenshot F2 hooké par Voxy + sidecar `.txt` de contexte — petit dev (½ jour), gain massif sur le rapport effort/valeur
- **Option 3** : Serveur MCP custom (mod client neo-voxy-debug-mcp) — gros investissement, contrôle total

L'option 2 (mod de capture scripté sans MCP) et l'option 4 (Computer Use API) sont écartées : la première est dominée par l'option 1.5/3, la seconde n'est pas accessible depuis Claude Code.

---

## Option 1 — Screenshots à la demande

### Principe

L'utilisateur joue normalement. Quand un glitch apparaît, il prend un screenshot (F2 natif Minecraft, ou un outil système). Le fichier PNG atterrit dans un dossier connu. Claude lit l'image directement via son outil `Read` (qui supporte PNG/JPG nativement).

### Mise en place

**Aucun code à écrire.** Setup en 3 étapes :

1. **Localiser le dossier de screenshots MC** : par défaut `C:\Users\Lakio\AppData\Roaming\.minecraft\screenshots\`. F2 dans le jeu écrit là.
2. **Ajouter le dossier comme allowlisted** dans `.claude/settings.json` (ou `~/.claude/settings.json`) pour que `Read` puisse y accéder sans prompt à chaque fichier. Exemple :
   ```json
   {
     "permissions": {
       "allow": [
         "Read(//C/Users/Lakio/AppData/Roaming/.minecraft/screenshots/**)"
       ]
     }
   }
   ```
3. **Convention de communication** : "regarde le dernier screenshot" ou nom de fichier précis. Claude liste le dossier avec `Glob`, prend le plus récent, et lit l'image.

### Workflow type

```
[USER] Le glitch est revenu — F2 pris à l'instant
[CLAUDE] (Glob screenshots/*.png trié par mtime, Read du dernier)
[CLAUDE] OK je vois — c'est du z-fighting entre la section LOD2 et le chunk
         vanilla à environ 96 blocs au sud. Je regarde MDICSectionRenderer.
```

### Combinables avec

- **F3 + screenshot** : F3 affiche les coordonnées, le chunk, la direction, le biome → toutes ces infos sont dans le screenshot, Claude les lit
- **Voxy debug overlay** (s'il existe / à ajouter) : un toggle qui dessine au-dessus du jeu le compte de sections actives, la distance LOD, etc., capturé dans le screenshot
- **Logs en parallèle** : `latest.log` est déjà accessible (cf memory `testing_workflow`). Si l'utilisateur reproduit le bug, Claude peut lire le log autour du timestamp du screenshot
- **Comparaisons A/B** : prendre 2 screenshots (avec mod / sans mod, ou avec Iris / sans Iris) et demander à Claude de comparer

### Forces

- **Démarrage immédiat**, zéro code à écrire ni à maintenir
- Marche pour 90% des glitchs visuels (ceux qu'on voit à un instant T)
- Couvre déjà beaucoup mieux que la description textuelle
- Compatible avec n'importe quel setup utilisateur (Iris, shaders, resource packs)
- Pas d'impact sur les performances ou la stabilité du mod

### Limites

- **Manuel** : l'utilisateur doit penser à F2 au bon moment
- **Pas de pilotage** : Claude ne peut pas changer l'angle, le LOD, téléporter, recharger les shaders pour isoler un facteur
- **Pas d'introspection** : ce que voit l'utilisateur sur F3 est limité ; pas d'accès aux états internes Voxy (taille du framebuffer modèle, état AsyncNodeManager, pool de sections, etc.)
- **Pas d'animation** : un glitch qui apparaît seulement pendant un mouvement (tearing, flicker, pop-in) est mal capturé par un screenshot statique

### Améliorations incrémentales possibles (sans aller jusqu'à l'option 3)

Par ordre d'effort croissant, avant de basculer sur l'option 3 :

- **(a) Convention de naming** : F2 + petite note dans le chat ("flicker en bordure NE") → Claude peut corréler
- **(b) Burst de F2** : capturer 3-5 screenshots rapprochés pour figer une animation
- **(c) Capture vidéo** : OBS / Win+G → screencap d'un GIF court ; Claude lit les frames extraites
- **(d) Mod vanilla léger** : un mod qui dump dans le nom de fichier du screenshot les coords, l'angle, le LOD, la section active — ainsi Claude a le contexte sans F3 dans l'image
- **(e) F3+Shift / F3+B** : afficher hitboxes, chunk borders → screenshot riche en info pour le debug spatial

**Reco** : commencer en (a), passer en (c) si glitch animé. Mais surtout, l'évolution naturelle de tout ça est l'**Option 1.5** ci-dessous, qui formalise et industrialise l'idée du dump de contexte sidecar.

---

## Option 1.5 — F2 hooké par Voxy + sidecar `.txt` de contexte

### Principe

C'est l'Option 1, mais Voxy intercepte la touche F2 (ou plus précisément, l'événement de capture screenshot de Minecraft) et écrit en plus du PNG un fichier texte `<même_nom>.txt` à côté, contenant **toutes les infos qu'on aurait voulu lire dans F3 + l'état interne Voxy + les stats de rendu** au moment exact du screenshot.

Résultat : un seul `Read` du `.txt` par Claude lui donne **tout le contexte technique** d'un coup. Le PNG montre le glitch, le sidecar explique ce qui se passait dans la machinerie au moment T.

### Pourquoi c'est le sweet spot

- **Zéro friction utilisateur** : tu fais toujours juste F2, le reste est automatique
- **70 % du gain de l'Option 3 pour ~5 % du coût** : pas de serveur HTTP, pas de threading async, pas de protocole MCP, pas de mod séparé à porter
- **Évolutif** : on commence avec un dump minimal, on enrichit au fil des bugs rencontrés. Chaque nouveau type de glitch peut justifier l'ajout d'un nouveau bloc dans le dump.
- **Transparent pour les utilisateurs finaux** : toggle `debugDumpOnScreenshot` désactivé par défaut dans `VoxyConfig`. Aucun impact en prod.
- **Pas de couplage Voxy ↔ mod externe** : tout vit dans le repo Voxy, pas de coordination de versions

### Implémentation

#### Hook F2

Minecraft 1.21.1 expose `net.minecraft.client.Screenshot` avec une méthode statique `grab(File gameDirectory, ..., Consumer<Component> messageConsumer)` (signature exacte à vérifier dans les sources Mojmap). Cette méthode :
1. Lit le framebuffer principal
2. Génère un nom de fichier `screenshots/YYYY-MM-DD_HH.MM.SS.png`
3. Écrit le PNG sur disque async
4. Notifie le joueur via le `messageConsumer`

**Approche** : un mixin Voxy avec `@Inject(at = @At("RETURN"))` sur `Screenshot.grab` (ou la variante qui écrit sur disque), récupère le `File` PNG résultant, et déclenche la génération du sidecar.

```java
// Pseudo-code
@Mixin(Screenshot.class)
public class MixinScreenshotDump {
    @Inject(method = "grab(...)V", at = @At("RETURN"))
    private static void voxy$dumpContextSidecar(File gameDir, ..., CallbackInfo ci) {
        if (!VoxyConfig.CONFIG.debugDumpOnScreenshot) return;
        File pngFile = /* récupéré via captureLocal ou re-dérivé */;
        File txtFile = new File(pngFile.getParentFile(),
            pngFile.getName().replace(".png", ".txt"));
        DebugContextDumper.dumpTo(txtFile);
    }
}
```

Le `DebugContextDumper.dumpTo(File)` est une classe Voxy qui agrège toutes les sources d'info et écrit le fichier synchroniquement. F2 a déjà une latence perceptible (~30ms pour la capture + encodage PNG), +5ms pour le dump est invisible.

#### Contenu du sidecar (proposition initiale)

Format texte simple, sections délimitées, lisible par humain ET par Claude. Exemple :

```
=== NEO-VOXY DEBUG DUMP ===
timestamp: 2026-05-01T14:32:17.482
screenshot: 2026-05-01_14.32.17.png

=== PLAYER ===
pos: (1234.56, 78.9, -456.78)
yaw: 142.3  pitch: -12.5
dimension: minecraft:overworld
biome: minecraft:plains
gamemode: creative
look_target_block: minecraft:stone @ (1240, 78, -460), distance 5.2
look_target_entity: none

=== VOXY STATE ===
enabled: true
mode: mixed (chunk + LOD)
render_distance_chunks_vanilla: 12
render_distance_voxy_sections: 64
storage_backend: LMDB (path=...)
sections_loaded_total: 4823
  lod0: 412
  lod1: 1024
  lod2: 1567
  lod3: 1820
sections_meshing: 8
sections_pending_load: 24
sections_pending_unload: 0

=== RENDER STATS (last frame) ===
sections_submitted: 4823
sections_drawn_after_occlusion: 1247
indirect_draws: 1247
triangles: 2_843_991
gpu_time_ms: 4.2 (if available)
cpu_render_ms: 2.1

=== LOOKED-AT SECTION ===
section_at_target: lod1 (5, 1, -8)
state: ready
last_update: 2026-05-01T14:31:45.111 (32s ago)
mesh_size_bytes: 18_432

=== ASYNC NODE MANAGER ===
load_queue: 24
unload_queue: 0
active_threads: 4/4
throughput_last_5s: 12 sections/s

=== IRIS ===
loaded: true
pack: ComplementaryReimagined_r5.4
shadow_enabled: true
voxy_json_present: true (limits: colortex0)

=== SODIUM ===
version: 0.6.13
chunk_renderer: default
backend: VertexArrayObject

=== VANILLA ===
fps: 87
render_distance: 12
simulation_distance: 8

=== GL ===
vendor: NVIDIA Corporation
renderer: NVIDIA GeForce RTX 4070/PCIe/SSE2
version: 4.6.0 NVIDIA 555.85
max_texture_size: 32768

=== VERSIONS ===
minecraft: 1.21.1
neoforge: 21.1.77
voxy: 0.x.y-dev
java: 21.0.2
os: Windows 11

=== VOXY CONFIG (selected) ===
modelTextureSize: 8
maxDetailDistance: 256
useAsyncMeshing: true
... (tronqué si > 30 lignes)

=== TAIL LOG (last 50 lines, or last 50 lines before most recent error) ===
[14:32:14] [Render thread/INFO]: ...
[14:32:15] [Voxy-Mesher-3/WARN]: ...
...
```

#### Garde-fous

- **Taille max** : si le dump dépasse 50 KB, tronquer le tail log et la liste des sections en gardant un résumé statistique. Sinon Claude charge un fichier inutilement énorme à chaque `Read`.
- **Toggle config** : `VoxyConfig.debugDumpOnScreenshot: bool = false` par défaut. Activable via UI Mod Menu.
- **Niveau de verbosité** : éventuellement `debugDumpLevel: enum {minimal, standard, full}` — `minimal` = juste pos+stats Voxy, `full` = tout y compris config dump et tail log.
- **Erreur silencieuse** : si le dump plante (exception inattendue), capter et logger en WARN, ne **jamais** faire échouer le screenshot lui-même.
- **Encodage** : UTF-8, retours à la ligne LF (cohérent avec ce que `Read` traite bien).

#### Bonus quasi-gratuits

- **Touche dédiée "screenshot debug sans HUD"** (ex: F6) : keybinding optionnel qui prend un screenshot **avec HUD masqué** + dump. Utile quand l'inventaire/hotbar masque la zone du glitch.
- **Burst manuel** : maintenir Ctrl+F2 pour 3 captures sur 1s. Trois PNG + trois `.txt` correspondants. Utile pour les glitchs animés.
- **Pré-marqueur** : commande `/voxy mark <label>` qui ajoute une ligne dans le prochain sidecar. L'utilisateur peut ainsi taguer un screenshot avec une note ("juste avant le crash").

### Effort

| Tâche | Effort |
|-------|--------|
| Mixin `Screenshot` + hook F2 | 1-2h |
| Classe `DebugContextDumper` (assemblage des infos) | 2-3h |
| Branchement aux internals Voxy (stats render, sections, AsyncNodeManager) | 2-3h |
| Toggle config + UI Mod Menu | 1h |
| Tests / itération sur le contenu du dump | 1-2h |
| **Total** | **~½ - 1 jour** |

### Forces

- ROI immédiat dès le premier bug diagnostiqué
- Tu gardes 100% du contrôle (c'est toi qui prends F2 quand tu vois le glitch)
- Pas de processus serveur additionnel à lancer / monitorer
- Pas de surface d'attaque (vs Option 3 qui ouvre un port HTTP)
- Le mod reste utilisable normalement par n'importe qui — feature opt-in

### Limites (qui motiveraient quand même un passage en Option 3)

- **Pas de pilotage par Claude** : Claude ne peut pas téléporter, changer un paramètre, recharger les shaders, faire un A/B Iris on/off automatiquement. Tout ça reste manuel.
- **Pas de capture proactive** : si Claude veut "tester l'hypothèse X, peux-tu aller à ces coords et prendre un screenshot ?", c'est toi qui fais le travail. Avec MCP, Claude le fait directement.
- **Pas d'introspection à la demande** : Claude ne peut récupérer un état Voxy que via les sidecars existants — pas de "donne-moi les uniforms du shader Y maintenant".

Pour 80% des cas, ces limites sont acceptables. Pour les bugs récurrents qui demandent beaucoup d'A/B (ex: tout ce qui touche Iris ou les transitions LOD), l'Option 3 redevient pertinente.

---

## Option 3 — Serveur MCP custom (mod client `neo-voxy-debug-mcp`)

### Principe

On écrit (en forkant un mod existant) un **mod client Minecraft** qui :

1. Tourne **dans le process MC** (donc voit le rendu réel, a accès à `Minecraft.getInstance()`, au framebuffer, aux instances Voxy)
2. Expose un **serveur HTTP localhost** qui parle le protocole **MCP** (Model Context Protocol)
3. Fournit des **outils** que Claude peut appeler : `take_screenshot`, `teleport`, `get_voxy_stats`, `set_render_distance`, `reload_shaders`, etc.
4. Est listé dans la config MCP de Claude Code → Claude le voit comme n'importe quel autre serveur d'outils

### Base de fork : `cuspymd/mcp-server-mod`

[GitHub](https://github.com/cuspymd/mcp-server-mod) · [Modrinth](https://modrinth.com/mod/mcp-server-mod) · Licence **CC0-1.0** (domaine public, fork libre sans aucune contrainte d'attribution)

**Ce qu'il fournit déjà** :
- Serveur HTTP MCP (streamable HTTP) sur port 8080, dans le process MC
- Outil `take_screenshot` : capture le framebuffer courant, retourne du PNG base64 — directement consommable par Claude (qui supporte les images en input)
- Outil `take_screenshot` avec téléport optionnel + orientation caméra avant capture
- Outil `execute_commands` : pipe vers la console serveur intégrée (tp, gamemode, time, weather, fill, setblock, give…)
- Outil `get_player_info` : coords, orientation, santé, faim, dim, inventaire
- Outil `get_blocks_in_area` : scan d'une zone rectangulaire (limite configurable, 50³ par défaut)

**Ce qui manque pour notre cas** :
- Compatibilité loader : c'est **Fabric**, on est **NeoForge**
- Compatibilité version : c'est **MC 1.21.4–1.21.11**, on est **MC 1.21.1**
- Outils Voxy-specific : aucun (logique, c'est un mod générique)

### Plan de portage / extension

#### Phase 1 — Portage Fabric → NeoForge 1.21.1 (½ - 1 jour)

Le projet upstream est petit (quelques milliers de lignes Java). Les tâches :

1. **Créer un sous-projet** ou un **repo séparé** `neo-voxy-debug-mcp`. Recommandation : repo séparé, pour ne pas polluer Voxy avec une dépendance MCP en prod.
2. **Setup gradle** : Architectury Loom (cohérent avec Voxy) + dépendance NeoForge 21.1.x
3. **Remplacer les hooks Fabric** :
   - `ClientLifecycleEvents.CLIENT_STARTED` → `ClientStartedEvent` (NeoForge bus)
   - `ClientLifecycleEvents.CLIENT_STOPPING` → `ClientStoppingEvent`
   - Tick events → `ClientTickEvent.Pre/Post`
   - Mod entrypoint : `@Mod("neovoxy_debug_mcp")` au lieu du `ModInitializer` Fabric
4. **Backport 1.21.4 → 1.21.1** : la plupart des APIs client (`Minecraft.getInstance()`, `Framebuffer`, `RenderTarget`, commandes serveur intégrées) sont stables sur cette plage. Risque faible. Si une API a bougé, le compilateur le pointera tout de suite.
5. **Vérifier la dépendance MCP côté Java** : si le projet upstream utilise une lib MCP Java (à vérifier), s'assurer qu'elle est compat Java 21 et qu'elle peut être shadow-jarrée. Sinon, réécrire le serveur HTTP en plain Java + JSON-RPC manuel (MCP par-dessus HTTP, c'est juste du JSON-RPC ; environ 200 lignes).
6. **Test smoke** : lancer le client, vérifier que le serveur écoute sur 8080 et répond à `tools/list`.

#### Phase 2 — Outils Voxy-specific (1-2 jours)

Liste exhaustive des outils à exposer, classés par valeur pour le diagnostic :

**Capture / observation**
- `take_screenshot(width?, height?, hide_hud?)` — déjà fourni, à étendre avec option pour cacher le HUD ou pas
- `take_screenshot_at(x, y, z, yaw, pitch, before_render_ticks?)` — téléport + attendre N ticks que les sections se chargent + capture. Critique pour la repro déterministe.
- `take_screenshot_burst(count, interval_ms)` — N captures rapprochées, retournées sous forme de liste base64. Utile pour les glitchs en mouvement.
- `take_split_screenshot(modes)` — capture multiple en activant/désactivant Voxy ou Iris entre chaque, pour A/B comparison

**État Voxy**
- `get_voxy_status()` — résumé : `enabled`, render distance, mode (chunk seul / mixte / LOD seul), nombre de sections chargées par niveau LOD, mémoire pool, stats `RenderDataFactory`
- `get_voxy_render_stats()` — compteurs frame courante : sections soumises, sections drawn (post-occlusion), draws indirects émis, triangles, ms GPU si disponible
- `get_voxy_section_at(x, y, z)` — détails d'une section précise : niveau LOD, état (loaded / meshing / ready / unloaded), timestamp dernière mise à jour, taille du buffer
- `get_voxy_world_engine_state()` — backend stockage actif (LMDB/RocksDB/Redis/Memory), file de save, métriques compression
- `get_async_node_manager_state()` — file de chargement, file de déchargement, threads actifs, throughput

**Configuration / pilotage**
- `set_render_distance(chunks)` — change la distance vanilla
- `set_voxy_render_distance(sections)` — change la distance Voxy
- `set_voxy_enabled(bool)` — toggle complet du mod
- `set_voxy_mode(mode)` — bascule chunk-only / mixte / LOD-only
- `reload_shaders()` — rechargement à chaud (équivalent F3+T mais ciblé)
- `reload_voxy_meshing()` — force un re-meshing complet d'une zone
- `set_iris_pack(name | null)` — change/désactive Iris pour A/B
- `set_voxy_config(key, value)` — édition générique d'une clé `VoxyConfig` (avec validation côté mod)

**Pilotage joueur**
- `teleport(x, y, z, yaw?, pitch?, dimension?)` — déjà couvert via `execute_commands` mais wrapper plus pratique
- `set_camera(yaw, pitch)` — orientation seule
- `move_camera(dx_yaw, dx_pitch, ticks)` — pan progressif pendant N ticks (utile pour repro un glitch d'animation)
- `wait_ticks(n)` — pause synchrone pour laisser le streaming se stabiliser
- `wait_until_loaded(radius)` — attendre activement que toutes les sections d'un rayon soient `ready`

**Introspection bas niveau (avancé)**
- `dump_uniforms(shader_name)` — pour debug shaders Voxy + Iris : valeurs courantes des uniforms
- `dump_buffer(buffer_id, max_bytes?)` — dump hexa d'un SSBO/UBO Voxy
- `capture_render_doc_frame()` — déclenche une capture RenderDoc (si l'API est attachée) ; énorme pour les bugs GPU
- `get_gl_state()` — état OpenGL pertinent (bindings, viewport, FBO actif)

**Logs**
- `tail_log(lines)` — dernières lignes de `latest.log` (Claude peut déjà le lire mais ce wrapper évite le cache de fichier)
- `subscribe_log(pattern, duration_s)` — capture les lignes matchant un regex pendant N secondes

#### Phase 3 — Intégration Claude Code (½ jour)

1. Documenter dans le README du mod la config MCP à ajouter dans `.claude.json` :
   ```json
   {
     "mcpServers": {
       "neo-voxy-debug": {
         "transport": "http",
         "url": "http://localhost:8080/mcp"
       }
     }
   }
   ```
2. Vérifier que tous les outils apparaissent dans `mcp` côté Claude
3. Ajouter quelques **prompts d'exemple** dans le README pour montrer les workflows fréquents (ex: "diagnostique le glitch de bord LOD à mes coords actuelles")
4. Mettre à jour `CLAUDE.md` du repo Voxy avec un lien vers ce mod et la procédure de lancement

### Architecture suggérée

```
neo-voxy-debug-mcp/                       # repo séparé
├── build.gradle                          # NeoForge 21.1.x, Java 21, Architectury Loom
├── src/main/java/me/cortex/voxy/debugmcp/
│   ├── DebugMcpMod.java                  # @Mod entrypoint
│   ├── server/
│   │   ├── McpHttpServer.java            # Netty ou jetty embed, JSON-RPC over HTTP
│   │   ├── ToolRegistry.java             # enregistrement des outils
│   │   └── tools/
│   │       ├── ScreenshotTool.java
│   │       ├── VoxyStatsTool.java
│   │       ├── TeleportTool.java
│   │       └── ...
│   ├── voxy/
│   │   └── VoxyAccessor.java             # façade qui parle aux internals Voxy
│   │                                     # (réflexion ou mixin accessors)
│   └── render/
│       └── FramebufferCapture.java       # capture du RenderTarget courant
└── src/main/resources/
    └── META-INF/neoforge.mods.toml
```

### Risques et gotchas

- **Couplage Voxy ↔ debug-mcp** : pour exposer les stats Voxy, il faut soit
  (a) ajouter dans Voxy lui-même quelques getters publics / un MBean, soit
  (b) utiliser de la réflexion + mixin accessors. (a) est plus propre et a peu de coût (méthodes `getRenderStats()` etc. sur les classes existantes). (b) évite de toucher Voxy mais est fragile aux refactos.
- **Threading** : Minecraft impose que la plupart des opérations (téléport, capture framebuffer, accès au monde) se fassent sur le **render thread**. Le serveur HTTP MCP tourne sur un thread séparé → il faut un mécanisme de "dispatch sur main thread" (file de tâches `Minecraft.getInstance().execute(...)`) avec un `CompletableFuture` qui débloque la requête HTTP quand le résultat est prêt. Le mod upstream a déjà ce pattern.
- **Capture framebuffer** : `Minecraft.getInstance().getMainRenderTarget()` donne le framebuffer principal après tous les passes. Mais si on veut capturer **avant** le HUD ou **avant** Iris post-process, il faut hooker plus tôt. L'upstream capture après tout (incluant HUD), ce qui est parfait pour notre cas (on veut voir ce que voit l'utilisateur).
- **Performance** : un screenshot full-frame, c'est ~10-30ms de stall. À utiliser ponctuellement, pas à 60fps. Une option `lite_screenshot` (downscale 1/4) peut aider pour les bursts.
- **Sécurité** : un serveur HTTP localhost ouvert qui peut exécuter `execute_commands` côté MC, c'est puissant. À **bind sur 127.0.0.1 uniquement**, jamais 0.0.0.0. Et idéalement avec un token partagé via la config Claude Code.
- **Iris compat** : si Iris est actif, son post-process peut interférer avec la capture. À tester. Probablement OK car on capture le framebuffer final.
- **Sodium compat** : Voxy a déjà 7 mixins Sodium. Le mod debug ne touche pas au pipeline rendu Sodium, donc neutre. Mais à valider.
- **Mineflayer ≠ in-process** : ne pas confondre avec les MCPs basés Mineflayer (yuniko, arjunkmrm). Eux se connectent **comme un client séparé** en parlant le protocole réseau MC ; ils ne voient **pas** le rendu. Pour Voxy c'est inutile, et c'est pour ça qu'on fork cuspymd qui est un vrai mod client.

### Estimation d'effort consolidée

| Phase | Tâche | Effort |
|-------|-------|--------|
| 1 | Fork + portage Fabric → NeoForge | ½ - 1 jour |
| 1 | Backport 1.21.4 → 1.21.1 | ¼ jour |
| 2 | Outils Voxy-specific (sous-ensemble critique : ~10 outils) | 1 jour |
| 2 | Outils avancés (uniforms, RenderDoc, etc.) | 1 jour optionnel |
| 3 | Intégration MCP Claude Code + doc | ¼ jour |
| **Total minimum viable** | | **~2 jours** |
| **Total exhaustif** | | **~3 jours** |

### Références

- [cuspymd/mcp-server-mod (base de fork, CC0)](https://github.com/cuspymd/mcp-server-mod)
- [MCP Server Mod sur Modrinth](https://modrinth.com/mod/mcp-server-mod)
- [Spec Model Context Protocol](https://modelcontextprotocol.io)
- [yuniko-software/minecraft-mcp-server (Mineflayer-based, NON applicable ici)](https://github.com/yuniko-software/minecraft-mcp-server)
- [arjunkmrm/mcp-minecraft (Mineflayer-based, NON applicable ici)](https://github.com/arjunkmrm/mcp-minecraft)
- [FundamentalLabs/minecraft-mcp (Mineflayer-based, NON applicable ici)](https://github.com/FundamentalLabs/minecraft-mcp)

---

## Recommandation de séquencement

1. **Option 1 dès maintenant** (zéro coût) — pour les bugs urgents en attendant l'Option 1.5
2. **Implémenter l'Option 1.5** (½ - 1 jour) — c'est le vrai sweet spot et probablement la cible long terme. Couvre 80% des cas avec un effort marginal.
3. **Option 3 seulement si nécessaire** : si après quelques mois d'usage de l'Option 1.5 on identifie un besoin récurrent de pilotage par Claude (A/B Iris répétés, exploration spatiale automatisée, introspection à la demande), alors investir les 2-3 jours de l'Option 3.

Les options sont **cumulatives**, pas exclusives :
- L'Option 1 reste utile pour les utilisateurs externes (rapports de bugs sans le toggle debug activé)
- L'Option 1.5 reste utile **même après** l'Option 3 : capture instantanée déclenchée par l'humain quand il voit quelque chose, sans avoir à formuler une requête
- L'Option 3 ajoute le pilotage proactif côté Claude, mais ne remplace pas le réflexe humain "ah tiens, F2"

**ROI estimé** :
- Option 1.5 : payback dès le 2-3e bug diagnostiqué (vs description textuelle qui prend 30+ min)
- Option 3 : payback après ~5 bugs nécessitant de l'A/B ou de l'exploration spatiale automatisée
