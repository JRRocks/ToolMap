# Mesures de performance sur l'émulateur (P15)

Scripts `adb` des scénarios de `docs/specs/performance.md` §7, à lancer depuis Git Bash (ou tout shell POSIX) avec
l'émulateur « Medium_Phone » démarré. Ils mesurent l'APK **benchmark** (R8, non debuggable, ADR-057) :

```bash
./gradlew :app:assembleBenchmark
bash tools/perf/run-all.sh app/build/outputs/apk/benchmark/ToolMap-benchmark-0.4.0.apk
```

| Script | Scénario | Résultat |
|---|---|---|
| `0-startup.sh` | 0. Démarrage à froid, 5 fois | médiane de `TotalTime` ; médiane du temps jusqu'à la carte dessinée depuis le début du processus |
| `R-return.sh` | R. Carte → « Explorer les traces et waypoints » → Retour, 10 fois | médiane du temps jusqu'à la carte dessinée (depuis la création de la carte, et depuis la touche Retour) ; images lentes |
| `A-pan.sh` | A. Glissements fixes pendant 1 min | images, % d'images lentes, 90e et 95e percentile (`gfxinfo`, interface seulement : la carte OpenGL n'y figure pas) |
| `T-tiles.sh online` | T2. Parcours fixe (ouverture, 5 glissements, 2 zooms) après `pm clear` | par étape : médiane de `ms` et de `idle_ms`, tuiles par origine ; 3 passages |
| `T-tiles.sh stored` | T1. Même parcours, tuiles déjà en MBTiles (un passage de préparation non compté) | idem |
| `Z-zone.sh` | Z. « IGN Cartes », carré de 5 km × 5 km autour de Chambéry, zooms 12 à 16, après `pm clear` | médiane de la durée et des tuiles par seconde ; 3 passages |

Variables : `RUNS` (nombre de passages), `DURATION` (secondes du scénario A), `OUT_DIR` (dossier des relevés,
`tools/perf/out` par défaut, non versionné).

## Ce que l'application écrit (debug et benchmark seulement)

- Étiquette logcat `ToolMapPerf` (`fr.toolmap.perf.MapPerfLog`) :
  `rendered step=<n> trigger=<open|gesture|camera> ms=<…> idle_ms=<…> store=<n> network=<n> composed=<n> failed=<n>`.
  Une ligne par étape (ouverture de la carte, geste, mouvement de caméra demandé par l'application) quand MapLibre
  annonce la carte entièrement dessinée et que la caméra est arrêtée. `ms` part du début de l'étape, `idle_ms` de
  l'arrêt de la caméra (l'attente des tuiles seule) ; les tuiles sont les requêtes servies depuis le début de l'étape
  (`TileStats`). L'ouverture ajoute `since_process_ms` pour la première carte du processus.
- `zone completed ms=<…> total=<n> downloaded=<n> stored=<n> missing=<n> failed=<n> bytes=<n>` à la fin d'un
  téléchargement de zone.
- Point de départ fixe par les extras de l'intent de lancement (`fr.toolmap.perf.PerfIntent`) : `perf.source`,
  `perf.lat`, `perf.lon`, `perf.zoom`, et `perf.zone` (`ouest,sud,est,nord`) avec `perf.zmin`, `perf.zmax` ; pour les
  cartes selon l'altitude (P20) : `perf.overlay` (carte ajoutée au-dessus), `perf.altmin`, `perf.altmax` (mètres, vide =
  sans limite) et `perf.logic` (`and` / `or`). La caméra n'est appliquée qu'à un démarrage à froid (`am force-stop` avant).

## Précautions

- `pm clear` (T2, Z, préparation de T1) vide **toutes** les données de l'application sur l'émulateur.
- Les mesures réseau (T2, Z) dépendent de la connexion : comparer l'avant et l'après dans la même séance.
- Les chiffres de l'émulateur servent à comparer, pas comme valeurs absolues.
- Git Bash réécrit les chemins `/sdcard/…` : `lib.sh` pose `MSYS_NO_PATHCONV=1`.
