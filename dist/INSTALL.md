# Neo-Voxy - Installation pour test

Tous les mods nécessaires sont dans `dist/mods/`.

## Versions

| Composant | Version |
|-----------|---------|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.82+ (testé jusqu'à 21.1.95) |
| Java | 21 (JDK ou JRE) |

## Mods inclus dans `dist/mods/`

| Mod | Version | Statut |
|-----|---------|--------|
| neo-voxy | 0.2.0 | Le mod à tester |
| sodium (NeoForge) | 0.6.13 | Requis (rendu) |
| iris (NeoForge) | 1.8.0 | Requis pour les shaders |
| lithium (NeoForge) | 0.15.0 | Optionnel (perfs serveur) |

---

## Étape 1 : Installer NeoForge

1. Télécharger l'installer NeoForge 21.1.82+ pour MC 1.21.1 :
   https://projects.neoforged.net/neoforged/neoforge

   Lien direct (21.1.95) :
   https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.95/neoforge-21.1.95-installer.jar

2. Lancer l'installer (`java -jar neoforge-21.1.95-installer.jar`) :
   - Choisir **"Install client"**
   - Pointer vers ton dossier `.minecraft` (le launcher Minecraft officiel par défaut)
   - Cliquer "OK"

3. Ouvrir le **launcher Minecraft officiel** :
   - Aller dans "Installations"
   - Sélectionner le profil "neoforge-21.1.95" (ou similaire)
   - Lancer une fois pour que le dossier soit créé

## Étape 2 : Installer les mods

1. Localiser le dossier `mods/` du profil NeoForge.
   - Par défaut sur Windows : `%APPDATA%\.minecraft\mods\`
   - Si le dossier n'existe pas, le créer

2. Copier **tous les jars** de `dist/mods/` dans ce dossier `mods/`

3. Vérifier le contenu :
   ```
   .minecraft/mods/
     neo-voxy-0.2.0.jar
     sodium-mc1.21.1-0.6.13-neoforge.jar
     iris-1.8.0+1.21.1-neoforge.jar
     lithium-mc1.21.1-0.15.0-neoforge.jar
   ```

## Étape 3 : Lancer Minecraft

1. Dans le launcher, sélectionner le profil NeoForge
2. **Important** : vérifier que le profil utilise Java 21
   - Si le launcher tente d'utiliser Java 17, modifier le profil :
     - Plus d'options → JVM → pointer vers `C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot\bin\javaw.exe`
3. Lancer

## Étape 4 : Tester Neo-Voxy

Une fois en jeu :

1. **Vérifier que le mod est chargé** :
   - Menu principal → "Mods" → vérifier que "Neo-Voxy 0.2.0" est listé

2. **Tester le rendu LOD** :
   - Créer un nouveau monde (ou en charger un)
   - Augmenter la distance de rendu Voxy via les options Sodium :
     - Options → Video Settings → onglet "Performance" ou similaire
   - Voler en mode créatif loin de la zone chargée pour voir les LODs

3. **Tester avec shaders (Iris)** :
   - Options → Shader Packs
   - Installer un shader pack (ex: BSL, Sildur's, Complementary)
   - Activer et observer (note: bug connu - le streaming LOD continu casse le rendu Iris)

4. **Commandes Voxy utiles** (à tester en jeu) :
   - `/voxy` (ou tab pour voir les sous-commandes disponibles)

---

## Logs et debug

Si Minecraft crash ou si Neo-Voxy ne se charge pas :

- **Logs** : `.minecraft/logs/latest.log`
- **Crash reports** : `.minecraft/crash-reports/`

Cherche dans les logs :
- Lignes contenant `voxy` (initialisation du mod)
- Lignes contenant `ERROR` ou `Caused by` (crashes)

---

## Problèmes connus (rappel ANALYSIS.md)

- Le streaming LOD continu casse le rendu Iris (bug #1)
- Pas de tests rigoureux faits par l'auteur original
- Incompatibilité connue : BetterFpsDist
