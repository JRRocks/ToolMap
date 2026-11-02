# Spécification — Format de sauvegarde ToolMap (P9.6, P33)

> Décisions : ADR-026 ; ADR-106 (sauvegarde à la carte et cartes hors ligne fusionnées, P33, 2026-11-02). Code : `fr.toolmap.core.backup` (`BackupModel.kt`), `fr.toolmap.data.backup` (`BackupService`, `PendingRestore`, `BackupLocations`).

## 1. Fichier

Une archive **zip** (`toolmap-AAAA-MM-JJ.zip`). Le **premier** entrée est `manifest.json` ; viennent ensuite les fichiers, chacun sous son chemin relatif. Les noms sont en UTF-8, séparés par `/`.

Le manifeste est en premier pour qu'une lecture séquentielle (le fichier vient d'un sélecteur de documents Android, on ne peut pas toujours s'y déplacer) puisse afficher ce que contient la sauvegarde, puis tout vérifier au fil de la lecture, sans copie intermédiaire. Conséquence : l'écriture lit chaque fichier deux fois (somme de contrôle, puis contenu) ; un fichier qui change entre les deux fait échouer la sauvegarde plutôt que d'en écrire une incohérente.

## 2. Manifeste (`manifest.json`)

```json
{
  "format": 1,
  "app": "ToolMap",
  "appVersion": "0.4.0",
  "createdAtMillis": 1790000000000,
  "areas": ["database", "settings", "alerts", "state", "sources", "workspaces", "print"],
  "entries": [{ "path": "tracks.db", "size": 12288, "sha256": "…64 hexadécimaux minuscules…" }]
}
```

- `format` : entier ; un fichier de format supérieur à celui que l'application lit est refusé avec son propre message (« mettez l'application à jour »). Les clés inconnues sont ignorées (ajouts compatibles). **1** : sauvegarde sans cartes hors ligne (lisible par toutes les versions), ou faite avant P33. **2** (depuis P33, ADR-106) : sauvegarde qui contient des cartes hors ligne choisies une par une ; une version d'avant P33 la refuse, car elle remplacerait tout son dossier de tuiles par les seules cartes choisies.
- `areas` : les rubriques que la sauvegarde **couvre** (celles cochées à l'écriture, P33), y compris celles qui n'ont aucun fichier. Une restauration remplace **entièrement** chaque rubrique de données cochée à la restauration (même vide : les cartes importées du téléphone disparaissent si la sauvegarde n'en avait aucune) et ne touche pas aux autres ; les cartes hors ligne sont **fusionnées** (§ 5).
- `entries` : tous les fichiers, avec taille et SHA-256 ; chacun doit appartenir à une rubrique couverte, sans doublon.

## 3. Rubriques et chemins autorisés

| Rubrique (`areas`) | Chemins | Emplacement sur le téléphone |
|---|---|---|
| `database` | `tracks.db` | base Room (copie cohérente : journal replié, écritures suspendues pendant la copie) |
| `settings` | `settings.json` | `filesDir/settings.json` |
| `alerts` | `alerts.json` | `filesDir/alerts.json` |
| `state` | `state/*.json` | `filesDir/state/` (caméra et calques) |
| `sources` | `sources/*.aqx` | `filesDir/sources/` (cartes importées ou créées) |
| `workspaces` | `workspaces/*.json`, `workspaces/hidden-builtins.txt` | `filesDir/workspaces/` (favoris enregistrés ; liste des favoris fournis supprimés, ADR-052) |
| `print` | `print/*.json` | `filesDir/print/` (mises en page) |
| `tiles` (option) | `tiles/*.mbtiles` | dossier des cartes hors ligne (interne ou carte SD, ADR-042) ; un fichier par stockage de tuiles (ADR-031), seuls ceux choisis à l'écriture ; entrées non compressées |

**Tout autre chemin est refusé**, que ce soit dans le manifeste ou dans l'archive : un chemin relatif sans `.`, `..`, segment vide, antislash, caractère de contrôle, segment commençant par `.`, et d'au plus un niveau de dossier. En particulier jamais `p2p/share.json` (la clé secrète du partage de position : deux téléphones ne doivent pas partager une identité), `recording/session.json` (reprise après plantage), `downloads/` (file d'attente liée à des tâches système), ni les journaux SQLite (`-wal`, `-shm`).

## 4. Écriture

1. Seules les rubriques cochées sont écrites (`BackupService.create(out, areas, tiles)`, P33) ; parmi les cartes hors ligne, seuls les fichiers choisis (`tileFiles()` : nom et taille de chaque fichier `.mbtiles`). La rubrique `tiles` n'est couverte qu'avec au moins une carte ; le manifeste est alors de format 2, sinon de format 1. Journaux des cartes hors ligne repliés si elles sont incluses ; carte SD absente : refus (`TILES_UNAVAILABLE`).
2. Base : journal replié (`wal_checkpoint(TRUNCATE)`), puis une transaction d'écriture gardée ouverte le temps de copier le fichier dans un fichier temporaire (les lectures continuent, les écritures attendent) : la copie est cohérente.
3. Passe 1 : taille, SHA-256 et CRC-32 de chaque fichier. Manifeste écrit en premier.
4. Passe 2 : contenu, en revérifiant taille et SHA-256 ; un écart (`CORRUPT`) fait échouer la sauvegarde et le document de destination est supprimé.

## 5. Restauration (en deux temps)

1. **Aperçu** : lecture du seul manifeste (date, version, rubriques, nombre de fichiers, taille) et confirmation de l'utilisateur.
2. **Préparation** (`stage(input, areas)`) : lecture séquentielle de l'archive vers `filesDir/restore-pending/payload/`. Seules les rubriques cochées (P33 ; toutes par défaut) sont déballées : les entrées des autres sont lues sans être écrites (elles doivent quand même être listées), la place demandée et la barre de progression ne comptent que les rubriques cochées, et `restore.json` est le manifeste réduit à ces rubriques (`BackupManifest.only`). Vérifications : manifeste en premier et valide, place disponible (`taille + 16 Mo`), chaque entrée **listée** (une entrée non listée est refusée), **non dupliquée**, qui **ne dépasse pas** sa taille déclarée (protection contre les bombes de décompression), dont le **SHA-256** correspond, dont le chemin canonique reste dans le dossier de préparation (protection contre `zip-slip`) ; **toutes** les entrées du manifeste présentes. Un fichier `restore.json` (copie du manifeste) est écrit en dernier : il marque la préparation comme complète. Au moindre échec ou à l'annulation, `restore-pending/` est supprimé. Les données vivantes ne sont jamais touchées à ce stade.
3. **Application** (`PendingRestore.apply`, au tout début du processus, dans `Application.attachBaseContext`, avant que quoi que ce soit n'ouvre la base ou ne lise un fichier) : pour chaque rubrique de données de `restore.json`, les données vivantes (dont `-wal`, `-shm`, `-journal` de la base) sont **déplacées de côté** (`restore-undo/`), puis les fichiers préparés mis en place. Ensuite les **cartes hors ligne**, quel que soit le format du fichier (ADR-106) : une carte absente du téléphone est mise en place telle quelle ; une carte présente reçoit, dans une transaction SQLite (`MbTilesFile.mergeFrom`, `ATTACH` du fichier préparé), les tuiles qui lui manquent et celles du fichier plus récentes (`tm_tile_info.stored_at` ; une tuile sans date compte pour la plus ancienne, à égalité celle du téléphone reste), puis ses métadonnées de zooms et d'emprise sont recalculées ; les autres cartes du téléphone ne sont pas touchées. Si une étape échoue, les rubriques de données reviennent exactement à l'état d'avant ; une carte déjà fusionnée garde les tuiles reçues (une fusion n'enlève rien). Dans tous les cas `restore-pending/` et `restore-undo/` sont supprimés et le résultat est écrit dans `restore-report.json`, montré une fois sur l'écran de sauvegarde. Une préparation sans `restore.json` (processus tué en cours de route) est jetée.
4. L'écran propose « Redémarrer maintenant » (lance l'activité principale dans un nouveau processus, puis termine l'actuel) ; sinon la restauration s'applique au démarrage suivant, quel qu'il soit.

## 6. Limites connues

- Un enregistrement en cours est arrêté par le redémarrage (la reprise après plantage de P6 le retrouve dans l'état d'avant la restauration… s'il existe encore : `recording/` n'est pas sauvegardé et reste, lui, tel qu'il est).
- La fusion des cartes hors ligne se fait au démarrage qui suit : quelques secondes de plus pour des centaines de Mo. Place nécessaire : celle des tuiles du fichier, pas le double.
- Une sauvegarde faite pendant un téléchargement de zone (cartes hors ligne incluses) peut échouer (« changé pendant la sauvegarde ») : recommencer.
- Les altitudes, traces et waypoints sont restaurés avec la base : si le schéma de la base change dans une version future, la restauration d'une sauvegarde plus ancienne passera par les migrations de Room à l'ouverture (la base restaurée garde sa version).
- L'ensemble n'a pas pu être exécuté sur un téléphone : sélecteur de documents, redémarrage du processus et lecture d'une vraie base sont à vérifier à la main.
- **Favoris fournis supprimés** (ADR-052, depuis le 2026-10-14) : leur liste est dans `workspaces/hidden-builtins.txt`. Constat du 2026-10-16 : ce fichier n'étant pas un `.json`, il n'entrait pas dans la rubrique `workspaces`, et les favoris fournis supprimés revenaient après une restauration. **Corrigé le 2026-10-20** (session 48) : `BackupPaths.areaOf` range aussi `workspaces/hidden-builtins.txt` (constante `BackupPaths.HIDDEN_BUILTINS_FILE`, partagée avec `WorkspaceStore`) dans `workspaces`. Une sauvegarde faite avant cette date ne contient pas ce fichier : sa restauration remplace tout le dossier `workspaces/`, et les favoris fournis supprimés reviennent, comme avant.
- Les réglages et données ajoutés après P9.6 suivent la sauvegarde seulement s'ils sont rangés dans une rubrique ci-dessus (par exemple les dossiers des traces de P16.5 sont dans la base, les cartes du catalogue en ligne de P16.4 dans `sources/`).
