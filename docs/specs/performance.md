# Performance, batterie et restructuration légère du code (P15)

> Demande de l'utilisateur du 2026-10-12 : nouveaux documents « pour la restructuration / performance », sans refactoring complet.
> Remplace `docs/specs/battery-and-performance.md` (audit P10.1) : son inventaire des consommateurs (§1) et ses coûts connus sont repris ici ; ses deux défauts corrigés sont dans le journal (2026-10-02, suite 16).
> **Mise à jour du 2026-10-16 (session 33)**, après un entretien avec l'utilisateur (« prépare un plan d'amélioration des performances ») : constat refait sur le code actuel, nouvelle partie sur les **tuiles** (§3, symptôme ressenti par l'utilisateur : les tuiles arrivent lentement quand on déplace la carte), mesures faites **sur l'émulateur seulement** (§7), build « benchmark » et Baseline Profile (§6, ADR-057), ordre des tâches (§8).
> **Aucun chiffre n'est encore mesuré** : chaque changement est mesuré avant et après sur l'émulateur (§7).

## 1. Qui consomme quoi (repris de P10.1, inchangé)

| Poste | Quand | Fréquence / coût | Arrêt |
|---|---|---|---|
| GPS — carte (`LocationComponent`) | carte visible, position active | 1 s, haute précision | `onStop` de MapLibre ; « Position en temps réel » |
| GPS — boîte d'altitude (`AltitudeController`, dans `AltitudeAwareLocationSource.kt`) | carte visible, écran allumé | 2 s | quitter la carte, écran éteint |
| GPS — cible | une cible est posée | 1 s | cible effacée |
| GPS — enregistrement | enregistrement | 1 s (précis), 2 s (marche, vélo), 10 s (économie) | arrêt |
| GPS — alertes | suivi ou proximité actif | 2 s | plus rien à surveiller |
| GPS — partage | partage actif | intervalle du groupe (30 s par défaut, 5 s minimum) | arrêt du partage |
| Baromètre | un consommateur d'altitude écoute | 1 Hz, compté par référence | dernier consommateur parti |
| Services de premier plan | enregistrement, partage, alertes | notification, verrou de réveil partiel renouvelé | fin de la fonction |
| Téléchargement de zone | WorkManager `dataSync` | §3.2 | fin, annulation |
| Réseau — tuiles | carte visible | tuiles manquantes seulement (§3.1) | mode hors ligne |
| P2P | partage actif | PING 15 s par lien | arrêt |
| Écran | réglage « écran allumé » | carte seulement | quitter la carte |

Android fusionne les demandes de position au plus petit intervalle.

## 2. Coûts de l'interface constatés dans le code

Constat refait le 2026-10-16. Depuis la première version (commit `0461bb7`) : **RS1 est fait** (pile de retour `rememberSaveable` + `BackHandler` dans `MainActivity`, `NavigationBackTest`) ; `MapScreen.kt` est passé de 1 303 à **1 527 lignes** (30 `collectAsStateWithLifecycle`, 30 `LaunchedEffect`).

| ID | Constat | Où | Conséquence probable | Piste (sans refonte) | Tâche |
|---|---|---|---|---|---|
| **PF1** | **La carte est détruite et recréée à chaque écran plein.** `MainActivity` affiche un seul écran à la fois (`when (screen)`) ; ouvrir Traces, Réglages, Recherche… retire `MapScreen` de la composition, donc `MapView` (`onDestroy`). Au retour : nouveau contexte OpenGL, style reconstruit, sources GeoJSON (traces, partage, grille) rechargées, tuiles relues | `MainActivity.kt`, `map/MapViewLifecycle.kt` | retour à la carte lent, écran noir bref, travail GPU et disque répété | garder `MapScreen` composé **sous** les écrans pleins (un `Box` : carte en dessous, écran secondaire par-dessus avec fond opaque), carte en pause (`onPause`) tant qu'elle est couverte | P15.4 |
| PF2 | **Plus de 15 modèles de vue à la portée de l'activité** (`by viewModels()`, `MainActivity.kt` l. 136 et suivantes), tous créés au démarrage ; 9 flux `SharingStarted.Eagerly` dans `:app` | `MainActivity.kt` | démarrage plus long, flux des traces et du multi-zoom actifs sans leur écran | créer à la demande les modèles des écrans secondaires (Réglages, Sauvegarde, Assistant, Multi-zoom, Recherche, Stockage, Téléchargements) ; `WhileSubscribed(5_000)` pour les flux d'un seul écran | P15.6 |
| PF3 | **`MapScreen` : 1 527 lignes, ~30 états lus** dans une seule fonction composable, plus 30 `LaunchedEffect` | `map/MapScreen.kt` | un changement d'un seul état (caméra, GPS, enregistrement) recompose tout l'écran carte, panneaux fermés compris | sous-composables qui lisent leur propre état (boîtes d'information, barre, panneaux, couches) ; caméra en lambda ou `derivedStateOf`. **Pas de changement de comportement** | P15.7 |
| PF4 | Enregistrement long : à chaque lot de points, bibliothèque relue, trace simplifiée en entier, GeoJSON de toutes les traces visibles reconstruit | `data/tracks/TrackDisplay` | coût croissant avec la durée | n'ajouter que le dernier tronçon à la ligne affichée | P15.8 |
| PF5 | Boîte d'altitude : GPS à 2 s tant que la carte est visible | `altitude/AltitudeAwareLocationSource.kt` | batterie écran allumé | suivre l'intervalle du GPS de la carte | P15.10 💤 (non mesurable sur émulateur) |
| PF6 | Alerte de proximité loin du lieu : GPS à 2 s | `AlertController` | batterie en veille | intervalle adaptatif | P15.10 💤 (non mesurable sur émulateur) |
| PF7 | APK de debug de 86,6 Mo (natifs MapLibre + WebRTC pour 3 architectures) | `app/build.gradle.kts` | téléchargement long pour chaque essai | APK de debug `arm64-v8a` seul (option de build) | P15.10 💤 |
| PF8 | `SettingsScreen.kt` : toutes les catégories dans un fichier | `settings/` | maintenance (pas la vitesse) | un fichier par catégorie, même rendu | P15.10 💤 |
| **PF9** | **L'APK installé est un APK de debug** (debuggable, sans R8) ; la release n'a pas R8 non plus (`toolmap.minify` désactivé par défaut, ADR-027) ; aucun Baseline Profile | `app/build.gradle.kts` | Compose nettement plus lent que dans une application optimisée ; toute mesure sur l'APK de debug est faussée | build « benchmark » (§6) puis Baseline Profile | P15.1, P15.5 |

## 3. Tuiles : affichage de la carte et téléchargement de zone (nouveau, 2026-10-16)

### 3.1 Affichage de la carte (symptôme de l'utilisateur)

Chemin : MapLibre → client OkHttp de `TileModule` (`HttpRequestUtil.setOkHttpClient`) → `CoverageInterceptor` → `TileInterceptor` (`serve` : MBTiles, sinon serveur sous le sémaphore `<max-threads>` de la source, puis `store.put`). Le cache ambiant de MapLibre est désactivé (`ToolMapApp.disableAmbientCache`).

| ID | Constat | Où | Piste | Tâche |
|---|---|---|---|---|
| M1 | **40 à 60 tuiles par écran** au lieu de 10 à 15 : la netteté « comme AlpineQuest » (ADR-032) déclare des tuiles plus petites à MapLibre | `ZoomMapping.declaredTileSize`, `StyleApplier.tileSize` | **aucune** : choix voulu, gardé par l'utilisateur | — |
| M2 | **2 ou 3 téléchargements simultanés par source** : les sources IGN fournies ne déclarent pas `<max-threads>` (défaut 3, `TileSource.DEFAULT_MAX_THREADS`) ; OSM, OpenTopoMap et les 25 cartes du catalogue en ligne déclarent 2. Un écran de 50 tuiles se charge en ≈ 17 allers-retours successifs | `builtin-sources.aqx`, `online-catalog.aqx`, `TileInterceptor.download` | `<max-threads>6</max-threads>` sur les 5 sources IGN fournies (§4) | P15.2 |
| M3 | **Les tuiles stockées attendent derrière le réseau** : `maxRequestsPerHost = 20` pour tout l'hôte factice ; 20 appels bloqués dans le sémaphore (jusqu'à 30 s) empêchent de servir une tuile déjà en MBTiles (autre calque, zone téléchargée) | `TileModule` (`MAX_PARALLEL_CALLS`) | monter à 64 : les sémaphores par source protègent déjà les serveurs | P15.2 |
| M4 | **File d'attente dans l'ordre d'arrivée** (`Semaphore(n, true)`) : les tuiles de la vue quittée passent avant celles de la vue affichée ; une tuile annulée par MapLibre attend son tour avant d'abandonner | `TileInterceptor.download` | file servie de la plus récente à la plus ancienne ; un appel annulé sort de la file sans requête réseau | P15.2 |
| M5 | **Tuiles multi-zoom recomposées à chaque affichage** (décodage + PNG réencodé, sans mémoire) ; les tuiles sources d'une tuile composée sont lues l'une après l'autre | `TileInterceptor.compose`, `BitmapTileComposer` | mémoire LRU des tuiles composées (≈ 32 Mo) et lecture parallèle des sources, **seulement si la mesure le justifie** | P15.9 💤 |

### 3.2 Téléchargement de zone

Chemin : `ZoneDownloadWorker` → `ZoneDownloader.download` (N coroutines sur une file) → même client OkHttp, donc `TileInterceptor` (une tuile par appel).

| ID | Constat | Où | Piste | Tâche |
|---|---|---|---|---|
| D1 | **Plafond de 3 fils pour toute source** (`BulkPolicy.DEFAULT_THREADS`), même si le `<max-threads>` de la source en autorise plus ; seul le niveau du plus petit zoom est consulté | `BulkPolicy`, `ZoneDownloader.download` | le `<max-threads>` de la source décide ; OSM, OpenStreetMap France et OpenTopoMap gardent 2 fils et 300 ms de pause | P15.3 |
| D2 | **Une transaction SQLite par tuile** (`store.put` → `putAll` d'une seule entrée, deux insertions) | `MbTilesFile.putAll`, `TileInterceptor.download` | écritures groupées (64 tuiles ou 1 s par transaction) dans le chemin du téléchargement de zone ; l'affichage de la carte n'est pas touché | P15.3 |
| D3 | **Lectures en double** : `store.contains` puis `store.get` (avec jointure) dans l'intercepteur ; l'image est copiée deux fois en mémoire | `ZoneDownloader.download`, `TileInterceptor.serve` | une seule lecture par tuile | P15.3 |
| D4 | **État réécrit toutes les 250 ms** : fichier `ZoneJobStore` (écriture d'un `.tmp` puis renommage) et notification | `ZoneDownloadWorker`, `ZoneDownloader.REPORT_INTERVAL_NANOS` | toutes les secondes | P15.3 |
| D5 | Affichage et téléchargement se partagent le même sémaphore par source | `TileInterceptor.gates` | gardé : c'est ce qui fait respecter `<max-threads>` au total | — |

Arrêt de l'application en plein téléchargement avec les écritures groupées : au plus un paquet perdu, retéléchargé à la reprise (les tuiles stockées sont sautées).

## 4. Règles d'usage des serveurs (vérifiées le 2026-10-16)

| Serveur | Règle | Conséquence pour ToolMap |
|---|---|---|
| **IGN — Géoplateforme** (`data.geopf.fr`, WMTS) | CGU de cartes.gouv.fr, art. 3.2 « Limite d'usage » : le WMTS **n'est pas soumis** à une limite de requêtes par seconde (le WMS-Raster est à 40/s, le TMS vecteur à 400/s ; au-delà : HTTP 429 et blocage de 5 s de l'API concernée). Art. 2.5 : l'IGN peut bloquer un « usage abusif » | `<max-threads>6` sur les sources IGN fournies ; pas plus sans mesure |
| **OpenStreetMap** (`tile.openstreetmap.org`) | Politique de l'OSMF : pas de limite chiffrée de connexions ni de débit ; User-Agent propre à l'application obligatoire ; en-têtes de cache à respecter (sinon 7 jours) ; HTTP/2 recommandé ; **usage hors ligne et préchargement interdits** ; blocage sans préavis en cas d'abus | 2 fils gardés. User-Agent : `ToolMap/0.1 (+https://github.com/JRRocks/ToolMap)` (conforme) |

**Écart connu, gardé par décision de l'utilisateur (2026-10-16, Q14)** : « Enregistrer la zone » reste permis sur `tile.openstreetmap.org`, plafonné à 1 500 tuiles, 2 fils et 300 ms de pause (`BulkPolicy`), alors que la politique d'OSM interdit l'usage hors ligne. Risque : blocage par OSM. À revoir si OSM bloque ToolMap ou si l'application est publiée.

**Plafond des 1 500 tuiles (précisé le 2026-10-20, session 48)** : il compte les tuiles **à télécharger**, pas celles déjà stockées. Avant, une zone de plus de 1 500 tuiles déjà en grande partie stockée (reprise après une pause, zone élargie, zone téléchargée une seconde fois) était refusée alors qu'elle ne demandait que quelques tuiles au serveur. `ZonePlan.storedPerMember` donne, par carte, les tuiles déjà présentes ; elles ne sont comptées (une lecture du MBTiles par carte limitée) que si la zone dépasse le plafond, pour ne pas ralentir le cas courant. Le serveur ne reçoit jamais plus de 1 500 demandes par zone, ce qui garde l'intention d'ADR-015 point 3.

Sources : [politique des tuiles de l'OSMF](https://operations.osmfoundation.org/policies/tiles/), [CGU de cartes.gouv.fr](https://cartes.gouv.fr/cgu/).

## 5. Restructuration légère du code (ce qui est permis)

Le but est de rendre les corrections sûres, **pas** de changer d'architecture (confirmé par l'utilisateur le 2026-10-16).

| ID | Changement | Ce qui ne change pas | État |
|---|---|---|---|
| RS1 | Pile de retour dans `MainActivity` (`rememberSaveable` d'une liste de `Screen`, un seul `BackHandler` à la racine) | l'`enum Screen` ; pas de Navigation Compose | ✅ fait |
| RS2 | `MapScreen` reste dans la composition sous les écrans pleins (PF1) | `MapController`, `MapViewModel` | P15.4 |
| RS3 | `MapScreen` découpé en fichiers par panneau (PF3) | le comportement, les tests existants | P15.7 |
| RS4 | Modèles de vue créés à la demande (PF2) | Hilt, les modèles eux-mêmes | P15.6 |
| RS5 | Chaque bouton ajouté ou déplacé : une ligne dans `docs/specs/ui-index.md` | — | permanent |

Chaque étape est un commit séparé, testé par `./gradlew check assembleDebug`.

## 6. Build « benchmark » et Baseline Profile (ADR-057)

- **Type de build `benchmark`** (dérivé de `release`) : non debuggable, `profileable`, R8 activé (`isMinifyEnabled`, `isShrinkResources`), signé avec la clé de debug fixe (`app/debug.keystore`), `matchingFallbacks += "release"`, mêmes architectures (dont `x86_64` pour l'émulateur). Il sert aux mesures ; l'utilisateur peut aussi l'installer au quotidien. C'est le **premier passage de R8** sur l'application (ADR-027) : les règles `proguard-rules.pro` sont corrigées jusqu'à ce que tous les scénarios du §7 passent. La release n'est pas modifiée.
- **Relevé de temps** (debug et benchmark seulement, jamais en release) : temps jusqu'à la carte entièrement dessinée (`addOnDidFinishRenderingMapListener(fully = true)` de MapLibre) et compte des tuiles par origine (stockée, réseau, composée) depuis `TileStats`, écrits dans logcat avec une étiquette fixe pour les scripts.
  **Fait en P15.1** (`fr.toolmap.perf.MapPerfLog`, champ `BuildConfig.PERF_LOG`, vrai en debug et benchmark, faux en release) : étiquette `ToolMapPerf`, une ligne `rendered step=<n> trigger=<open|gesture|camera> ms=<…> idle_ms=<…> store=<n> network=<n> composed=<n> failed=<n>` par étape (ouverture de la carte, geste, mouvement de caméra de l'application). Le seul `onDidFinishRenderingMap(true)` ne revient pas à chaque étape (constaté sur l'émulateur) : l'étape se termine au premier des trois signaux « tout est chargé » de MapLibre (carte, image entièrement dessinée, passage au repos), pas pendant un geste ni une animation, et l'ouverture attend ses premières tuiles (le style de base vide est dessiné avant elles). `idle_ms` part de l'arrêt de la caméra : l'attente des tuiles seule, sans la durée du geste. Le téléchargement de zone écrit `zone completed ms=<…> downloaded=<n> …` à la fin.
  **Point de départ des scripts** (`fr.toolmap.perf.PerfIntent`, debug et benchmark seulement) : extras de l'intent de lancement `perf.source`, `perf.lat`, `perf.lon`, `perf.zoom` (carte seule et caméra fixes) et `perf.zone`, `perf.zmin`, `perf.zmax` (téléchargement de zone) ; `perf.overlay`, `perf.altmin`, `perf.altmax`, `perf.logic` (carte au-dessus et sa bande d'altitude, P20, `elevation-maps.md` §5). Un APK benchmark n'étant pas debuggable, `run-as` ne permet pas d'écrire l'état de l'application : c'est le moyen de placer la vue.
- **Baseline Profile** : module `:baselineprofile` (Macrobenchmark, `androidx.baselineprofile`) et `androidx.profileinstaller` dans `:app` ; le profil couvre le démarrage, l'ouverture de la carte et un déplacement ; il est généré sur l’émulateur et versionné (`app/src/main/baseline-prof.txt`). Il n'agit que sur un APK non debuggable, donc se mesure sur le build benchmark.

## 7. Protocole de mesure (émulateur seulement)

Décision de l'utilisateur (2026-10-16) : **toutes les mesures se font sur l'émulateur** (« Medium_Phone », Android 37), avant et après **chaque** changement, sur l'APK **benchmark**. Les chiffres de l'émulateur servent à comparer avant et après, pas comme valeurs absolues. Mesures réseau : l'avant et l'après dans la même séance, médiane de 3 passages, pour limiter l'effet des variations du réseau. **Dérive de l’émulateur (P15.5)** : redémarrer l’émulateur avant une série de mesures, entrelacer l’avant et l’après (`cmd package compile`, ou deux APK), ne comparer que des mesures rapprochées (§9).

```
# Démarrage à froid (5 fois, médiane de TotalTime)
adb shell am force-stop fr.toolmap
adb shell am start -W -n fr.toolmap/.MainActivity

# Fluidité (images lentes, 90e / 95e percentile)
adb shell dumpsys gfxinfo fr.toolmap reset
adb shell dumpsys gfxinfo fr.toolmap > gfxinfo.txt

# Relevé de temps de la carte (§6)
adb logcat -s <étiquette du relevé>

# Mémoire, à la fin
adb shell dumpsys meminfo fr.toolmap > meminfo.txt
```

| Scénario | Comment | À relever | Sert à |
|---|---|---|---|
| 0. Démarrage à froid | `am start -W`, 5 fois | `TotalTime` (médiane) | PF2, Baseline Profile |
| R. Retour à la carte | carte → Traces → carte, 10 fois (`adb shell input`) | temps jusqu'à la carte entièrement dessinée (relevé), images lentes | PF1 |
| A. Déplacement de la carte | script `adb shell input swipe` fixe, 1 min | images lentes, 90e / 95e percentile | PF3 |
| T1. Tuiles stockées | vue fixe sur Chambéry, carte IGN, 5 glissements et 2 zooms, toutes les tuiles déjà en MBTiles | temps jusqu'à la carte entièrement dessinée à chaque étape, tuiles par origine | M3, M4, M5 |
| T2. Tuiles en ligne | même parcours après avoir vidé le stockage de la carte | idem | M2, M3, M4 |
| Z. Zone IGN fixe | « Enregistrer la zone » sur un carré fixe de 5 km × 5 km autour de Chambéry, zooms 12 à 16, stockage vidé avant | durée totale, tuiles par seconde (médiane de 3) | D1 à D4 |
| G. Trace longue | enregistrement « marche » avec une trace GPX rejouée (`adb emu geo fix`, plusieurs heures accélérées) | temps de calcul par lot (relevé), images lentes | PF4 |

**Scripts** (P15.1) : `tools/perf/` (Git Bash ; mode d'emploi dans `tools/perf/README.md`), un par scénario (`0-startup.sh`, `R-return.sh`, `A-pan.sh`, `T-tiles.sh online|stored`, `Z-zone.sh`) et `run-all.sh [apk]`. Vue fixe : « IGN Cartes » (`ign-maps`) sur Chambéry (45,5646 ; 5,9178), zoom de caméra 14 (tuiles de zoom 16). Parcours T : ouverture, 5 glissements de 500 px en 600 ms, 2 appuis sur « Zoomer », chaque étape attendant sa ligne de relevé. « Stockage vidé » (T2, Z, préparation de T1) = `pm clear` : toutes les données de l'application. Carré Z : ouest 5,8857, sud 45,5421, est 5,9499, nord 45,5871 (234 tuiles de zoom 12 à 16). G n'a pas de script (P15.8).

Seuils de départ, à confirmer par les premiers chiffres : démarrage à froid < 1,5 s, retour à la carte < 300 ms (après RS2), moins de 5 % d'images lentes en déplaçant la carte.

**Plus tard, sur téléphone** (non mesurable sur l'émulateur, aucune tâche n'en dépend aujourd'hui) : batterie carte affichée 1 h (PF5) ; enregistrement « marche » et « économie » écran éteint 1 h (% batterie, trous) ; partage à 30 s avec un second téléphone écran éteint ; suivi de trace avec alerte d'écart écran éteint (PF6) ; enregistrement de plus de 24 h (aucun trou après l'expiration du verrou de réveil). Commandes : `dumpsys batterystats --reset` puis `dumpsys batterystats --charged fr.toolmap`.

## 8. Ordre des tâches et tests

Ordre validé par l'utilisateur (2026-10-16) : mesures d'abord, puis ce qu'il ressent (tuiles de la carte), le téléchargement de zone, ensuite l'interface.

| Tâche | Changement | Tests à ajouter |
|---|---|---|
| P15.1 | build benchmark, relevé de temps, scripts, mesures de référence | l'APK benchmark ouvre la carte et passe les scénarios du §7 sur l'émulateur |
| P15.2 | M2, M3, M4 | `TileInterceptorTest` : une tuile stockée est servie pendant que les places réseau de sa source sont prises ; la tuile demandée en dernier part en premier ; aucune requête réseau pour un appel annulé ; au plus N téléchargements simultanés par source (OSM 2, IGN 6) |
| P15.3 | D1 à D4 | `ZoneDownloaderTest` : fils simultanés selon `<max-threads>` (OSM 2 avec pause, IGN 6) ; écritures groupées ; reprise après arrêt sans tuile manquante |
| P15.4 | PF1 / RS2 | `AppStartupTest` : une seule instance de `MapView` après carte → Traces → carte |
| P15.5 | Baseline Profile | génération reproductible sur l'émulateur |
| P15.6 | PF2 / RS4 | tests existants verts |
| P15.7 | PF3 / RS3 | aucun nouveau test : les tests existants restent verts sans modification |
| P15.8 | PF4 | `TrackDisplay` : un lot ajouté ne resimplifie pas toute la trace |
| P15.9 💤 | M5 | si T1 / T2 montrent un coût des tuiles composées |
| P15.10 💤 | PF5 à PF8 | — |

## 9. Résultats mesurés (émulateur « Medium_Phone », APK benchmark)

Mesures de la même journée (2026-10-16), médianes de 3 passages ; le réseau du poste varie, les chiffres servent à comparer.

| Scénario | Avant (P15.1) | Après P15.2 / P15.3 | Changement |
|---|---|---|---|
| T2 tuiles en ligne, total | 9 606 ms | **7 408 ms** | −23 % |
| T2, attente après l'arrêt de la caméra | 6 182 ms | **3 992 ms** | −35 % |
| T1 tuiles stockées, total | 4 402 ms | 4 449 ms | inchangé (attendu : rien à télécharger) |
| Z zone IGN 5 km × 5 km, zooms 12 à 16 | 12,2 s, 16,5 tuiles/s | **7,1 s, 28,6 tuiles/s** | −41 % de durée, +73 % de débit |

Réalisé en P15.2 : `RecentFirstGate` (file de la source servie de la plus récente à la plus ancienne, appel annulé retiré sans requête, vérifié aussi à l'entrée de l'intercepteur pour les appels annulés en attente dans le répartiteur d'OkHttp), `<max-threads>6</max-threads>` sur les 5 sources IGN fournies, `maxRequests` et `maxRequestsPerHost` à 64. Le lecteur AQX bornait `<max-threads>` à 3 : la borne est celle de l'assistant (16, `AqxParser.MAX_THREADS`).

Réalisé en P15.3 : `BulkPolicy` ne plafonne plus à 3 fils (le `<max-threads>` de la source décide, 2 fils et 300 ms gardés pour OSM, OSM France et OpenTopoMap), `TileBatch` (64 tuiles ou 1 s par transaction, vidé aussi à l'arrêt ou à la pause), étiquette de requête `BulkFetch` (l'intercepteur ne relit ni n'écrit la tuile d'un téléchargement de zone), état et notification toutes les secondes.

### Réalisé en P15.4 (PF1, RS2) : la carte reste en place sous les écrans pleins

`MapScreen` n'est plus retirée de la composition quand un écran plein s'ouvre (Traces, Réglages, Cartes disponibles…) : elle reste composée, masquée (`graphicsLayer` à alpha 0, sémantique effacée), et son `MapView` est **arrêté sans être détruit** (`rememberMapViewWithLifecycle(covered)` : phases create / start / resume suivies une par une). Les feuilles et boîtes de dialogue (fenêtres à part) ne sont pas composées pendant ce temps. Le Retour de l'écran plein est pris par le `BackHandler` enregistré à son ouverture. `NavigationBackTest` vérifie que la `MapView` est la même après un aller-retour.

| Scénario | Avant | Après (3 passages) | Changement |
|---|---|---|---|
| R. Retour → carte dessinée | 514 ms | 178 / 216 / 240 ms | −55 à −65 % |
| R. images lentes | 15,1 % | 21 à 23 % | plus de lentes (voir ci-dessous) |
| R. 90e / 95e percentile | 40 / 61 ms | 38 à 40 / 48 à 53 ms | percentiles en baisse |

Le taux d'images lentes monte ; **hypothèse non vérifiée** : le redémarrage de la `MapView` au retour produit quelques images lentes, et il y en a moins en tout qu'avant. Au repos, `gfxinfo` ne montre aucune image, carte affichée comme carte couverte (aucun redessin permanent). Le scénario R (`tools/perf/R-return.sh`) attend maintenant la ligne `trigger=return` du relevé (`MapPerfLog.returned()`), puisque la carte persistante n'a plus d'étape d'ouverture à chaque retour.

### Réalisé en P15.5 (PF9) : Baseline Profile

Module `:baselineprofile` (`com.android.test` + `androidx.baselineprofile` 1.5.0, Macrobenchmark 1.5.0, UI Automator 2.4.0) et `androidx.profileinstaller` 1.4.1 dans `:app`. Le générateur (`BaselineProfileGenerator`) accorde les permissions, démarre l'application à froid, laisse la carte se dessiner, enchaîne 8 glissements et un pincement, puis ouvre les boutons du bas (zoom, Cartes, Traces et waypoints) et le menu jusqu'aux Réglages. Le profil, de **2,6 Mo**, est écrit par le plugin dans `app/src/main/baseline-prof.txt` (réglage `baselineProfileOutputDir = "."` : le plugin ne branche son propre dossier `generated/` que sur les variantes « release », pas sur le type `benchmark`). Régénération, émulateur branché (≈ 6 min) :

```
./gradlew :app:generateBaselineProfile
```

La CI ne le régénère pas (pas d'émulateur) ; elle empaquette le fichier versionné (`assets/dexopt/baseline.prof` dans l'APK benchmark, 13,7 Ko après R8).

**Résultat de mesure : aucun gain visible sur l'émulateur.** Scénario 0, médianes de 9 passages, modes alternés dans la même séance (`cmd package compile --reset`, puis `INSTALL_PROFILE` et `compile -m speed-profile`). Deux séries, dans l'ordre où elles ont été faites :

| Mode de compilation | Série 1 : TotalTime / carte dessinée (ms) | Série 2 : après un redémarrage de l'émulateur (ms) |
|---|---|---|
| `verify` (sans profil) | 1 647 / 2 411 puis 1 774 / 2 529 | **646 / 804**, puis 938 / 1 176 |
| `speed-profile` (avec profil) | 1 737 / 2 403 puis 1 661 / 2 322 | **682 / 914**, puis 1 093 / 1 573 |
| `speed` (compilation complète, plafond) | 1 785 / 2 507 | 1 525 / 1 973 |

**Piège de mesure découvert** : l'émulateur *dérive* — après des heures d'usage (série 1) tout y est lent, le lanceur et SystemUI sautent des images, le démarrage mesure 3 à 5 s au lieu de 0,65 s ; et même fraîchement redémarré, les mesures s'allongent de passage en série (646 → 938 → 1 093 → 1 525 ms pour des modes qui devraient aller de plus en plus vite). Règle ajoutée au protocole : **redémarrer l'émulateur avant une série de mesures, entrelacer l'avant et l'après, ne comparer que des mesures rapprochées**.

Même la compilation complète ne l'améliore pas : sur l'émulateur x86_64 (processeur du poste), l'effet du profil est plus petit que la dérive de l'émulateur. Hypothèse (non vérifiée) : le démarrage n'y est pas limité par le code interprété mais par l'initialisation native de MapLibre et l'OpenGL de l'émulateur. Le profil est donc gardé (sans coût de fonctionnement, pratique recommandée, effet attendu sur un téléphone ARM plus lent) mais son effet **ne peut pas être démontré ici** : à confirmer par l'utilisateur sur son téléphone (scénario 0 à la main : `adb shell am start -W`, ou simple ressenti du démarrage).

### Réalisé en P15.6 (PF2, RS4) : modèles de vue et flux à la demande

Mesure d'abord : sur les 15 modèles de vue de `MainActivity`, ceux des écrans secondaires sont déjà des délégués `by viewModels()` paresseux ; seul `MapsViewModel` (catalogue des cartes) se construisait au démarrage, en lançant `stateIn(Eagerly)` sur trois flux. Il passe en construction **à la première lecture** : `groups` et `catalogAdded` sont des `by lazy`, avec une valeur initiale calculée tout de suite (pas de catalogue vide un instant), `alreadyAdded()` partagé. Création du modèle : **25 à 70 ms avant, moins de 8 ms après** (relevé temporaire sur l'émulateur, retiré). Test ajouté : `MapsViewModelTest` « the catalogue is built on first read and is right at once ».

`WhileSubscribed(5_000)` **n'a pas été appliqué** aux autres flux : les flux `Eagerly` restants alimentent l'enregistrement, le partage et les alertes, qui doivent continuer écran fermé ; en changer un serait un changement de comportement sans gain mesuré. Le démarrage à froid (scénario 0) ne montre pas de différence au-delà du bruit de l'émulateur (voir P15.5).

### Réalisé en P15.7 (PF3, RS3) : la caméra ne recompose plus tout `MapScreen`

Mesure d'abord : pendant un glissement, `MapScreen` se recomposait à chaque image de caméra (**692 et 676 recompositions** sur le scénario A, deux passages) parce que la lecture de `controller.liveCamera` (un état Compose) se faisait dans la portée racine (`Box` et `Column` sont `inline`). Correction ciblée, sans changement de comportement : le décalage du nord et le texte du cap sont des `derivedStateOf` (la recomposition n'a lieu que si la valeur affichée change), les valeurs lues sont tenues par `rememberUpdatedState`, et les calques qui dépendent de la caméra (`PrintFrameLayer`, `SaveAreaLayer`) sont des composables privés qui lisent la caméra eux-mêmes. Le découpage complet de RS3 (un fichier par panneau) **n'a pas été fait** : il n'était pas nécessaire pour ce gain et grossit inutilement la modification.

| Scénario A (64 glissements) | Avant | Après |
|---|---|---|
| Recompositions de `MapScreen` | 692 / 676 | **68 / 68** (−90 %) |
| 90e percentile des images | 42 ms | 38 ms |
| Images lentes | ≈ 89 % | ≈ 89 % (inchangé) |

Le taux d'images lentes ne bouge pas : sur l'émulateur, le rendu de la carte (OpenGL logiciel) borne les images, pas l'interface Compose. Le gain est réel côté travail d'interface (−90 % de recompositions) mais n'est pas visible dans le taux brut ici ; à ressentir sur le téléphone. Le compteur de mesure temporaire a été retiré.

### Réalisé en P15.8 (PF4) : la ligne de la trace en cours est prolongée, pas recalculée

`TrackRepository.appendPoints` garde un **journal des derniers lots** ajoutés par l'enregistreur (`AppendedPoints` : points, début de segment, nombre de points total après le lot ; bornes : `APPEND_LOG_POINTS`) et `appendedSince(trackId, revision)` rend les lots qui mènent d'une révision à l'actuelle, ou `null` si les points ont changé autrement (inversion, édition, simplification, fin d'enregistrement) ou si le journal ne remonte pas jusque-là. `TrackDisplay` garde pour chaque trace les segments finis, la **partie gelée** du dernier segment (déjà simplifiée) et une **queue** de points bruts (moins de 256) ; un lot ne simplifie que la queue, ancrée sur le dernier point gelé (Douglas-Peucker : l'écart reste dans la tolérance). Si le journal ne suffit pas, la trace est relue et simplifiée en entier, comme avant (`fullLoads` compte ces relectures, pour les tests). Un lot déjà compris dans la lecture (la révision est augmentée après l'écriture) est ignoré grâce à `endCount`.

| Scénario G (substitut JVM, voir plus bas) | Avant | Après |
|---|---|---|
| Un lot de 10 points sur une trace de 20 000 points : construction des données d'affichage, médiane de 30 lots | **13 ms** | **5 ms** |

Le reste (5 ms) est la fabrication du GeoJSON de toute la ligne, qui n'est pas incrémentale (le tronçon gelé est une liste déjà simplifiée ; MapLibre reçoit la ligne entière). **Le scénario G sur l'émulateur n'a pas été fait** : rejouer plusieurs heures d'enregistrement GPS par `adb emu geo fix` n'est pas praticable ici ; la mesure est un test JVM (Robolectric) qui ajoute 30 lots à une trace de 20 000 points et chronomètre `display.data.first()`. Tests ajoutés (`TrackDisplayTest`, 5) : aucun rechargement complet sur 40 lots ajoutés ; ligne prolongée dans la tolérance de 1 m (écart max. 2,5 m admis, la queue étant simplifiée par morceaux) ; nouveau segment ; inversion → relecture ; `appendedSince` contigu, puis `null` après une réécriture.
