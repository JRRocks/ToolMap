# Décisions d'architecture (ADR)

> Une décision = un ADR numéroté. Ne jamais modifier le fond d'un ADR accepté : le remplacer par un nouvel ADR (statut « Remplacé par ADR-XXX »).
>
> Gabarit :
> ```
> ## ADR-XXX — Titre
> - Date / statut : AAAA-MM-JJ — Proposé | Accepté | Remplacé par ADR-YYY
> - Contexte :
> - Décision :
> - Conséquences :
> - Alternatives rejetées :
> ```

## ADR-001 — Android natif en Kotlin + Jetpack Compose
- **Date / statut** : 2026-10-01 — Accepté (choix de l'utilisateur)
- **Contexte** : AlpineQuest est une application Android. L'usage cible est la randonnée : GPS en arrière-plan, stockage volumineux, batterie, et requêtes de tuiles avec des en-têtes arbitraires (Referer, User-Agent) exigées par certaines sources AQX.
- **Décision** : application Android native en Kotlin, UI Jetpack Compose (Material 3), Hilt, Room, WorkManager, coroutines/Flow. minSdk 26.
- **Conséquences** : pas de version PC ni iOS. Le contrôle du réseau, des services de premier plan et des capteurs est complet. La logique pure est isolée dans `:core` (JVM) pour rester testable sans émulateur dans l'environnement cloud.
- **Alternatives rejetées** : Capacitor + PWA (Referer et User-Agent non modifiables côté web, GPS en arrière-plan limité) ; Flutter (non retenu par l'utilisateur).

## ADR-002 — MapLibre Native pour l'affichage, intercepteur OkHttp pour les tuiles
- **Date / statut** : 2026-10-01 — Accepté (à confirmer par le spike P1.1)
- **Contexte** : il faut afficher n'importe quelle source raster (XYZ, TMS, WMTS, WMS, quadkey) avec des en-têtes personnalisés, la servir depuis notre propre stockage hors ligne, appliquer des plages de zoom par calque (N3), régler l'opacité et les couleurs, et garder un rendu fluide (rotation, zoom continu).
- **Décision** : MapLibre Native Android pour le rendu GPU. Chaque source est déclarée en raster `https://tiles.toolmap.local/{sourceId}/{z}/{x}/{y}`. Un `TileInterceptor` OkHttp, installé via `HttpRequestUtil.setOkHttpClient`, construit l'URL réelle depuis la définition AQX, ajoute les en-têtes, et lit et écrit dans le MBTiles. Le cache ambiant MapLibre est désactivé. Les plages de zoom des calques passent par `minzoom`/`maxzoom` des layers ; les réglages par `raster-opacity`, `raster-contrast`, `raster-saturation` et `raster-brightness-*`. L'ombrage utilise `raster-dem` + `hillshade`.
- **Conséquences** : beaucoup moins de code de rendu à écrire. Toute la logique des sources reste chez nous, testable et indépendante de MapLibre. Limité à Web Mercator (voir ADR-005). Le PDF (P7) n'utilise pas MapLibre : un compositeur dédié lit directement les MBTiles, pour une échelle exacte et un habillage vectoriel.
- **Alternatives rejetées** : osmdroid (projet peu actif, rendu CPU) ; moteur raster maison d'emblée (coûteux, mais c'est le **plan de repli** si le spike P1.1 échoue).

## ADR-003 — Stockage des tuiles en MBTiles, un fichier par source
- **Date / statut** : 2026-10-01 — Accepté
- **Contexte** : usage hors ligne en randonnée, téléchargement de zones, partage de paquets de cartes entre appareils, gestion de la place disque.
- **Décision** : un fichier SQLite au schéma MBTiles standard par source (`files/tiles/{sourceId}.mbtiles`). Une table annexe stocke la date de téléchargement de chaque tuile (expiration selon `update-delay`).
- **Conséquences** : format ouvert, lisible par QGIS et d'autres applications, exportable et importable tel quel. Purge et statistiques par source triviales.
- **Alternatives rejetées** : fichiers individuels sur disque (des millions d'inodes, copie lente) ; cache ambiant MapLibre (opaque, non exportable).

## ADR-004 — Partage de position P2P strict : WebRTC + signalisation par QR code
- **Date / statut** : 2026-10-01 — Accepté (choix de l'utilisateur) ; **remplacé en partie par ADR-033** (serveur Firebase pour les nouveaux groupes ; le mode direct reste)
- **Contexte** : l'utilisateur n'a pas de serveur et veut un fonctionnement réellement pair-à-pair, sans relais tiers.
- **Décision** : WebRTC DataChannel (`stream-webrtc-android`). L'offre et la réponse SDP sont échangées par QR code (ou lien texte), avec ICE non progressif. Maillage de groupe : un nouvel arrivant s'appaire avec un seul membre, puis la signalisation vers les autres est relayée par les canaux existants. Chiffrement applicatif AES-GCM avec une clé de groupe transmise dans le QR, en plus du DTLS. Pas de TURN. Un STUN public sert seulement à découvrir l'adresse publique ; il est désactivable. Spécification : `docs/specs/p2p-sharing.md`.
- **Conséquences** : aucune donnée ne passe par un tiers. En contrepartie : les connexions entre deux téléphones derrière un CGNAT symétrique (fréquent en 4G) échouent ; si tout le groupe perd le réseau assez longtemps pour que les IP changent, il faut re-scanner un QR. Une interface `PeerTransport` permettra d'ajouter Nearby (Bluetooth/Wi-Fi) ou Meshtastic (LoRa).
- **Alternatives rejetées** : Trystero/Nostr (signalisation publique) ; relais MQTT/Nostr chiffré (pas du vrai P2P) — non retenus par l'utilisateur.

## ADR-005 — Web Mercator (EPSG:3857) uniquement en v1
- **Date / statut** : 2026-10-01 — Accepté (clause « proj4j » **remplacée par ADR-020** : grilles UTM / Lambert 93 / MGRS par projections maison)
- **Contexte** : MapLibre ne gère que Web Mercator. La grande majorité des sources (IGN `tilematrixset=PM`, OSM, OpenTopoMap) sont en Web Mercator.
- **Décision** : les sources AQX dans une autre projection (`projection-code`, `import-crs-txt-def`…) sont importées mais marquées « non supportées » et désactivées. Les grilles UTM/Lambert 93 dessinées par-dessus (écran et PDF) passent par proj4j.
- **Conséquences** : la reprojection de tuiles est reportée en bonus. En impression, l'échelle est exacte au centre de la page (correction par le facteur cos φ) et la variation vers les bords reste faible : ±tan φ · Δφ, soit ≈ 0,08 % sur une A4 à 1:50 000 vers 45° N (voir `docs/specs/print-pdf.md`).
- **Alternatives rejetées** : reprojection dès la v1 (complexe, peu utile au départ).

## ADR-006 — Langues du projet
- **Date / statut** : 2026-10-01 — Accepté
- **Décision** : documentation, journal, textes de l'interface et messages de commit en **français** ; identifiants, noms de fichiers source et commentaires de code en **anglais**.

## ADR-007 — Chaîne de build : AGP 9.4, Gradle 9.6, Kotlin 2.4, API 37
- **Date / statut** : 2026-10-01 — Accepté
- **Contexte** : le projet démarre en octobre 2026. Versions stables les plus récentes vérifiées sur Google Maven / Maven Central : AGP 9.4.1 (exige **Gradle 9.6.0** et JDK 17+, API max 37), Kotlin 2.4.20, KSP 2.3.12, Hilt 2.60.1, Compose BOM 2026.09.00.
- **Décision** : AGP 9.4.1 avec **Kotlin intégré** (le plugin `org.jetbrains.kotlin.android` n'est pas appliqué ; seul `kotlin.plugin.compose` l'est). Gradle 9.6.0 via le wrapper (somme SHA-256 verrouillée). `compileSdk` 37, `targetSdk` 36 (Android 17 pas encore éprouvé pour les services de localisation en arrière-plan : à réévaluer en P10), `minSdk` 26, Java/Kotlin cible 17 (compilé avec JDK 21). Toutes les versions dans `gradle/libs.versions.toml`. Les niveaux d'API sont aussi dans `scripts/install-android-sdk.sh` : les deux fichiers doivent rester synchronisés.
- **Conséquences** : build vérifié de bout en bout (tests, lint, APK). Les bibliothèques ajoutées plus tard doivent être compatibles AGP 9 (surveiller KSP/Room/Hilt). `:core` (module Kotlin JVM pur) n'a **pas** de tâche `testDebugUnitTest` : utiliser `:core:test` ou `check`.
- **Alternatives rejetées** : AGP 8.13 + Gradle 8.14 (déjà installé dans l'image, mais fin de vie plus proche).

## ADR-008 — Style de code : Spotless + ktlint ; Detekt reporté
- **Date / statut** : 2026-10-01 — Accepté
- **Contexte** : le plan prévoyait ktlint et detekt. Detekt 1.23.8 (dernière stable) est compilé contre Kotlin 2.0 et ne suit pas Kotlin 2.4 ; la 2.0 n'existe qu'en alpha (`dev.detekt` 2.0.0-alpha.6).
- **Décision** : **Spotless 8.10.3 + ktlint 1.8.0** (style `ktlint_official`, règles dans `.editorconfig`, 120 colonnes). `./gradlew spotlessApply` corrige, `./gradlew spotlessCheck` vérifie et fait partie de `check`. La règle `chain-method-continuation` est désactivée (elle éclatait `libs.versions.x.get().toInt()` sur 4 lignes) ; elle l'est à la fois dans `.editorconfig` (pour l'IDE) et par `editorConfigOverride` dans `build.gradle.kts` (Spotless n'a pas appliqué le réglage de `.editorconfig`). Android Lint complète l'analyse.
- **Conséquences** : pas d'analyse statique de type detekt pour l'instant. À rouvrir quand detekt 2.0 sera stable (tâche de suivi dans le journal).
- **Alternatives rejetées** : detekt 1.23.8 (incompatible) ; detekt 2.0 alpha (instable) ; plugin ktlint-gradle (moins bien suivi avec le Kotlin intégré d'AGP 9).

## ADR-009 — Environnement cloud : SDK par hook, miroir Maven Central, keystore debug fixe
- **Date / statut** : 2026-10-01 — Accepté
- **Contexte** : (1) le SDK Android n'est pas préinstallé dans le bac à sable ; (2) `repo.maven.apache.org` répond **429 Too Many Requests** sur les rafales (IP d'egress partagée) et Gradle désactive alors le dépôt, ce qui fait échouer le build ; (3) une clé de debug générée à chaque exécution de CI donnerait à chaque APK une signature différente, donc impossible de mettre à jour l'application sur le téléphone sans la désinstaller.
- **Décision** : (1) `scripts/install-android-sdk.sh`, appelé par le hook SessionStart et par la CI, installe cmdline-tools, platform 37 et build-tools 36 dans `$HOME/android-sdk`. (2) Le hook installe `scripts/gradle-init/cloud-mirror.init.gradle` dans `~/.gradle/init.d/` : il redirige uniquement Maven Central vers le miroir Google `maven-central.storage-download.googleapis.com`. Les fichiers de build du projet et la CI n'en dépendent pas. (3) `app/debug.keystore` (mot de passe `android`) est **versionné volontairement** : c'est une clé de debug, non secrète. Les builds de release auront leur propre clé, jamais committée (P10.3).
- **Conséquences** : un build à froid prend ≈ 2 min (téléchargement du wrapper et des dépendances). Le miroir peut avoir quelques heures de retard sur une toute nouvelle version : en cas d'artefact introuvable, attendre ou retirer temporairement le script d'init.
- **Alternatives rejetées** : réglages de retry/backoff Gradle (les attentes dépassent plusieurs minutes par artefact) ; keystore généré par la CI.

## ADR-010 — OkHttp 4.12.0 (et non 5.x) pour le client des tuiles
- **Date / statut** : 2026-10-02 — Accepté. **Remplace** la recommandation « OkHttp 5.5.0 » de l'entrée de journal de la session 2.
- **Contexte** : `HttpRequestUtil.setOkHttpClient(Call.Factory)` de MapLibre Native 13.6.1 reçoit un `okhttp3.OkHttpClient` : MapLibre dépend d'OkHttp **4.12.0**. OkHttp 5 change des signatures binaires (Kotlin, modules scindés) : un client 5.x passé à du code compilé contre la 4.x risque une `NoSuchMethodError` à l'exécution, impossible à détecter sans appareil dans cet environnement.
- **Décision** : `com.squareup.okhttp3:okhttp:4.12.0` et `mockwebserver:4.12.0` (tests) dans `:data`, exposés en `api` pour que `:app` construise le client. Les deux jars sont présents sur le miroir Maven (ADR-009).
- **Conséquences** : pas de HTTP/3 ni des nouveautés de la 5.x. La montée de version devient une tâche à part, à faire quand MapLibre aura migré, ou après vérification sur appareil.
- **Alternatives rejetées** : OkHttp 5.x (risque d'incompatibilité binaire non vérifiable ici) ; client HTTP maison (aucun avantage).

## ADR-011 — `MapView` MapLibre dans `AndroidView` ; correspondance zoom caméra / zoom de tuile mesurée par le spike
- **Date / statut** : 2026-10-02 — Accepté pour le choix de `MapView` ; la correspondance de zoom est **remplacée par ADR-032** (décalage selon la densité de l'écran ; MapLibre arrondit au zoom le plus proche pour une source raster).
- **Contexte** : il existe `maplibre-compose` 0.18.0, mais il est jeune, son DSL masque `HttpRequestUtil`, `TileSet` et les propriétés `raster-*` dont dépendent N3 et P1.6, et les dépendances de rendu ne sont pas vérifiables sans appareil.
- **Décision** : `org.maplibre.android.maps.MapView` hébergé dans un `AndroidView` Compose, avec son cycle de vie lié au `LifecycleOwner` (fonction `rememberMapViewWithLifecycle`). À partir de P1.5, une classe fine `MapController` isolera MapLibre du reste de l'UI pour que le plan de repli d'ADR-002 reste réaliste. Hypothèse de zoom : la caméra MapLibre raisonne en tuiles de **512 px** ; pour une source de 256 px la tuile `z` est demandée quand `floor(caméra) + 1 = z`, et le « zoom AlpineQuest » vaut donc caméra + 1. Les `minzoom`/`maxzoom` d'une source MapLibre désignent le `z` des tuiles (celui de l'URL). L'écran de diagnostic affiche et exporte les couples `cam<n>->z<m>` pour vérifier cette hypothèse.
- **Conséquences** : l'hypothèse conditionne la traduction des plages de zoom par calque de **P3.1** ; la note correspondante de PLAN.md reste « non vérifiée » tant que le rapport n'est pas revenu.
- **Alternatives rejetées** : `maplibre-compose` (voir contexte) ; GLSurfaceView + moteur maison (c'est le plan de repli d'ADR-002).

## ADR-012 — Persistance de la phase P1 sans Room
- **Date / statut** : 2026-10-02 — Accepté
- **Contexte** : en P1 il faut mémoriser des sources de cartes, la pile de calques et la caméra. Ce sont de petits documents hiérarchiques ; Room (schéma, migrations, KSP sous AGP 9) n'apporte rien tant qu'il n'y a pas de requêtes (placemarks, P5).
- **Décision** : les sources **intégrées** vivent dans une ressource `builtin-sources.aqx` du classpath de `:core`, lue par notre propre `AqxParser` (`BuiltinSources.load()`) ; les sources **importées** seront conservées telles quelles en fichiers `.aqx` dans `filesDir/sources/` (P1.7) ; la pile de calques et la caméra seront sérialisées en JSON (P1.5/P1.6). Room est réservé aux placemarks (P5). Le magasin de tuiles du spike est en mémoire (`InMemoryTileStore`) ; MBTiles (ADR-003) arrive avec P2.1.
- **Conséquences** : l'export d'une source (P4.6) est trivial (le fichier existe déjà). Pas de migration de schéma en P1. Les sources intégrées partagent le chemin de code des sources importées, donc sont testées par les mêmes tests.
- **Alternatives rejetées** : Room dès P1 (complexité sans bénéfice) ; DataStore Proto (schéma à maintenir en plus du format AQX).

## ADR-013 — Tests du stockage MBTiles avec Robolectric (SQLite d'Android)
- **Date / statut** : 2026-10-02 — Accepté
- **Contexte** : `MbTilesFile` s'appuie sur `android.database.sqlite` (WAL, `WITHOUT ROWID`, transactions) : c'est le moteur qui tournera sur le téléphone. Il n'y a ni émulateur ni appareil dans le bac à sable, et un SQLite JDBC différent ne prouverait rien sur le comportement Android.
- **Décision** : Robolectric 4.17 en test unitaire dans `:data` (`@Config(sdk = [35])`, `includeAndroidResources = true`) ; il fournit le vrai SQLite natif d'Android en JVM. Les tests écrivent dans des répertoires temporaires. Les tests qui ouvrent aussi un `MockWebServer` (flux hors ligne complet) tournent dans le même module.
- **Conséquences** : pas de test instrumenté pour le stockage. La première exécution de Robolectric télécharge ses artefacts (le miroir ADR-009 les fournit). Si un test se bloque, le lancer seul avec `--tests` et `timeout`. Ce que Robolectric ne couvre pas (permissions, WorkManager réel, SAF) reste à vérifier sur téléphone.
- **Alternatives rejetées** : `sqlite-jdbc` (autre moteur, autres comportements WAL) ; tests instrumentés (aucun appareil).

## ADR-014 — Carte « locale » : le serveur `offline://stored-tiles`
- **Date / statut** : 2026-10-02 — Accepté
- **Contexte** : importer un `.mbtiles` d'une autre origine (ou d'un autre outil) crée une carte qui n'a **aucun serveur**. Il faut qu'elle reste une `TileSource` ordinaire (pile de calques, plages de zoom, export AQX) sans qu'aucun code ne tente de la télécharger.
- **Décision** : un `LevelDef` dont le seul `<server>` commence par `offline:` (valeur écrite : `offline://stored-tiles`, `LevelDef.LOCAL_ONLY_SERVER`) est « local » (`isLocalOnly`). `TileInterceptor` répond 404 pour toute tuile absente, même en ligne, sans requête réseau ; `ZoneDownloader` ignore ces niveaux ; les formulaires de téléchargement et la fusion d'import ne les proposent pas. La valeur survit à l'aller-retour AQX (testé). Les tuiles importées ont une date inconnue (`StoredTile.UNKNOWN_DATE_MILLIS`) : elles ne périment jamais et `purgeOlderThan` ne les efface pas.
- **Conséquences** : extension propre à ToolMap, documentée dans `docs/specs/aqx-format.md` ; le comportement d'AlpineQuest face à cette valeur n'est pas vérifié. Une carte locale ne peut être supprimée que par « Supprimer cette carte » (elle ne peut pas être retéléchargée).
- **Alternatives rejetées** : champ dédié dans le modèle (casse la compatibilité AQX pour rien) ; lecture directe du fichier sans passer par l'intercepteur (deux chemins de rendu).

## ADR-015 — Téléchargement de zone : même client HTTP, WorkManager sans `hilt-work`, politique « gros volumes »
- **Date / statut** : 2026-10-02 — Accepté
- **Contexte** : le téléchargement de zone doit respecter exactement les mêmes en-têtes, cookies, rotation de serveurs et `max-threads` que l'affichage, survivre à la fermeture de l'application, et ne pas faire de tort aux serveurs communautaires.
- **Décision** : (1) `ZoneDownloader` émet ses requêtes sur l'URL factice de l'hôte `tiles.toolmap.local` **par le même `OkHttpClient`** que la carte : l'intercepteur construit l'URL réelle, applique les en-têtes et **stocke** la tuile ; le downloader ne fait que compter. La reprise après pause ou erreur = relancer la zone en sautant les tuiles déjà stockées. (2) Exécution par WorkManager (`CoroutineWorker`, service de premier plan `dataSync`, notification de progression) ; le worker obtient ses dépendances par un `EntryPoint` Hilt, sans ajouter `hilt-work` (un artefact de moins à valider avec AGP 9). Les travaux sont décrits par un fichier JSON par job dans `filesDir/downloads/`. (3) `BulkPolicy` : les hôtes de `openstreetmap.org`, `openstreetmap.fr` et `opentopomap.org` (politiques d'usage interdisant le téléchargement massif) sont plafonnés à 1 500 tuiles, 2 fils et 300 ms entre deux téléchargements ; les autres sources restent plafonnées par le garde-fou général du planificateur et par `max-threads`.
- **Conséquences** : un seul chemin de téléchargement à tester. La notification et le service de premier plan ne sont pas vérifiables ici (téléphone). Android 14+ impose la permission `FOREGROUND_SERVICE_DATA_SYNC`, déclarée.
- **Alternatives rejetées** : second client HTTP pour le téléchargement (risque de divergence d'en-têtes) ; `hilt-work` ; téléchargement par `DownloadManager` (pas de contrôle des en-têtes ni du stockage MBTiles).

## ADR-016 — Plage de zoom d'un calque : bornes incluses en zoom affiché, traduction MapLibre `min − 1` / `max`
- **Date / statut** : 2026-10-02 — Accepté pour la sémantique ; la **traduction numérique est remplacée par ADR-032** (`min − décalage` / `max + 1 − décalage`, le décalage dépendant de l'écran et du réglage « Netteté des cartes »).
- **Contexte** : N3 demande des plages par calque (« IGN Topo 8–16, Plan 1–7 ») sans trou ni recouvrement visible. Le `minzoom` / `maxzoom` d'un *layer* MapLibre porte sur le zoom de la caméra (tuiles de 512 px) : calque caché sous `minzoom`, et **à partir de** `maxzoom` (borne exclusive). L'ancienne note de P3.1 (`minzoom = min`, `maxzoom = max + 1`) oubliait le décalage de 1 entre zoom caméra et zoom affiché.
- **Décision** : `LayerConfig.displayMinZoom` / `displayMaxZoom` sont des **entiers inclusifs** exprimés dans le zoom affiché à l'utilisateur. « 8 à 16 » signifie visible de 8,0 inclus à 17,0 exclu, si bien que « 1–7 » et « 8–16 » se touchent à 8,0 sans trou à 7,5. Traduction (`LayerZoom.cameraRange`, via `ZoomMapping`) : `minzoom = min − 1` (non posé si ≤ 0), `maxzoom = (max + 1) − 1 = max`. Une borne absente = pas de limite (0 / 24 côté MapLibre). Distinct de la plage **native** de la source (`RasterSource.minzoom/maxzoom`, zoom des tuiles) : au-delà du max natif MapLibre étire la dernière tuile (sur-zoom), ce qui permet « SCAN 25 affiché de 8 à 16 » alors que ses tuiles s'arrêtent à 14. Le téléchargement et, plus tard, le PDF travaillent en zoom de tuile entier, bornes incluses (`ZoomLimit`).
- **Conséquences** : si le rapport du spike corrige l'offset de zoom, **un seul changement** (`ZoomMapping.CAMERA_TO_TILE_OFFSET`) corrige la carte, les barres de zoom et le préréglage. Les tests couvrent l'accord entre la règle « zoom affiché » et la règle MapLibre sur la traduction, pas la valeur de l'offset.
- **Alternatives rejetées** : plages de zoom des tuiles posées sur la source (une source partagée par plusieurs calques aurait la même plage) ; bornes exclusives (contre-intuitif pour l'utilisateur).

## ADR-017 — Assistant d'ajout de carte : lecture SAX des capabilities, brouillon textuel, sonde par l'intercepteur, trafic en clair autorisé
- **Date / statut** : 2026-10-02 — Accepté. L'affichage de l'assistant n'a pas pu être exécuté (pas d'émulateur) ; la logique est couverte en JVM.
- **Contexte** : P4 doit lire les documents `GetCapabilities` des serveurs réels (celui de l'IGN pèse 3 Mo et décrit 720 couches), ne rien récupérer d'externe à la demande d'un document hostile, construire une URL de tuiles, la **tester comme la carte la demandera**, et ne jamais laisser fuiter une clé d'API dans un message ou un export par inadvertance.
- **Décision** :
  1. **Lecture SAX** (`OgcXml` / `PathHandler`, `:core`) au lieu d'un DOM : mémoire bornée, aucun résolveur d'entités, `DOCTYPE` simple (WMS 1.1.1) neutralisé, déclaration `<!ENTITY` ou sous-ensemble interne refusés. Racine reconnue par `CapabilitiesReader` ; `ExceptionReport` / `ServiceExceptionReport` deviennent le message d'erreur. Plafond de 25 Mo à la lecture réseau.
  2. **WMTS** : seules les grilles Web Mercator (`EPSG:3857`, `900913`, `3785`, `102100`, `102113`) sont proposées ; les zooms viennent des `TileMatrixLimits` de la couche croisées avec les identifiants numériques de la grille. L'URL est un modèle **KVP** (`SERVICE=WMTS&REQUEST=GetTile…&TILEMATRIX={$z}&TILEROW={$y}&TILECOL={$x}`), ou le `ResourceURL` REST du document quand il existe pour le format choisi ; les paramètres déjà présents dans l'adresse de base (`apikey=`, `map=`), insensibles à la casse, ne sont jamais répétés. La grille retenue est **celle que la couche déclare** (`PM_0_19`, `PM_0_17`… et non `PM`) : le serveur IGN a répondu 200 avec la même tuile pour les deux (vérifié par `curl`).
  3. **WMS** : requêtes `GetMap` en EPSG:3857 avec `{$bbox}` et `bbox-crs-code` ; `CRS=` en 1.3.0, `SRS=` avant ; `TRANSPARENT=TRUE` pour le PNG. Les CRS d'une couche sont ceux de ses parents ajoutés aux siens.
  4. **Brouillon** : `SourceDraft` garde des champs textuels (valeurs à moitié saisies), se valide par **codes** (`DraftIssue`, erreurs bloquantes et avertissements) sans exception, produit un `TileSource` à un seul niveau et peut être reconstruit depuis une source (`fromSource`). Les sources à plusieurs niveaux, locales, non supportées ou à `clear-color` ne sont pas modifiables dans l'assistant (l'export AQX reste possible) ; une source intégrée est **copiée** (nouvel id), jamais modifiée.
  5. **Clés d'API** : `ApiKeyExtractor` repère `apikey`, `api_key`, `key`, `token`, `access_token`, `appid`… dans la chaîne de requête, les range en `<param>` (`{$param:nom}`) et l'assistant le fait **automatiquement en quittant l'étape « adresse »** ; l'export AQX les vide par défaut (case décochable). Seuls les paramètres que les URL utilisent sont écrits dans le fichier.
  6. **Sonde de tuile** (`TileProbe`, `:data`) : la requête passe par un vrai `TileInterceptor` (mêmes Referer, en-têtes, cookie, règles de zoom, gabarit) avec un magasin, des compteurs et un mode hors ligne **jetables** : rien n'est stocké, la carte ne voit rien. Le verdict est déduit des compteurs de l'intercepteur et du code HTTP du serveur (`httpCode` = réponse réelle du serveur, `null` si aucune) ; le contenu est vérifié par ses premiers octets (`ImageSniffer`), pas seulement par le type MIME ; les valeurs des paramètres (clés) sont remplacées par `***` dans le message du serveur, et l'URL affichée est déjà expurgée par `TileStats`.
  7. **Trafic en clair** : `android:usesCleartextTraffic="true"`. Beaucoup de serveurs d'anciens fichiers AQX ne parlent que `http://` ; la parité « n'importe quelle source » l'exige. L'assistant affiche un avertissement pour toute adresse `http://`, et la sonde distingue le refus de la politique Android (`CLEARTEXT_BLOCKED`).
  8. **`insecure="true"`** (certificat non vérifié) est enregistré et exporté fidèlement mais **n'est pas appliqué** : l'interrupteur de l'assistant le dit explicitement.
  9. **Modifier l'adresse d'une carte efface ses tuiles stockées** (même `id`, donc même fichier MBTiles : elles décriraient une autre carte) ; l'étape finale le signale avant l'enregistrement.
- **Conséquences** : 71 tests JVM ajoutés couvrent parseurs, brouillon, lecture réseau, sonde et `SourceWizardViewModel` (Robolectric ajouté aux tests de `:app`). Restent à vérifier sur téléphone : l'enchaînement des écrans, la saisie, l'aperçu de l'image, le refus effectif de `http://` hors manifeste.
- **Alternatives rejetées** : DOM (3 Mo × 720 couches en mémoire, XXE à désactiver à la main) ; StAX (absent d'Android) ; tester la tuile avec un client à part (risque de diverger de la carte) ; refuser `http://` (casse l'import de fichiers existants) ; appliquer `insecure` sans test faute de serveur HTTPS auto-signé dans le bac à sable.

## ADR-018 — Traces : modèle neutre, Room avec points en blocs compacts, XML non modélisé conservé, affichage GeoJSON simplifié
- **Date / statut** : 2026-10-02 — Accepté. Les écrans (bibliothèque, statistiques, profil, carte) n'ont pas pu être exécutés (pas d'émulateur) ; modèle, formats, base, statistiques et dépôt sont couverts en JVM / Robolectric sur des fichiers réels.
- **Contexte** : P5 doit lire et écrire GPX 1.0/1.1, KML/KMZ et GeoJSON sans perdre ce que les fichiers contiennent (extensions Garmin, fréquence cardiaque par point), afficher des traces de 50 000 points et garder une bibliothèque modifiable. P6 enregistrera des traces point par point.
- **Décision** :
  1. **Un modèle unique** (`TrackDocument`, `:core`) indépendant du format : waypoints, itinéraires, traces à segments, dossiers (chemin KML), style, `format` d'origine. Lecture SAX bornée (mêmes protections que l'ADR-017 : DOCTYPE neutralisé, ENTITY refusée). Les anomalies (point invalide, `<trk>` vide, polygone KML pris pour un itinéraire…) deviennent des `TrackIssue` typés, jamais une exception : un fichier réel imparfait s'importe.
  2. **XML non modélisé conservé** (`RawXml`) : tout élément qu'on n'interprète pas (extensions `gpxtpx`, `magvar`, `hdop`, `copyright`…) est gardé **tel que lu**, avec les préfixes d'espace de noms du document d'origine (`namespaces` posés sur la racine à l'écriture). `RawXml.declaring` redéclare les préfixes d'un fragment quand deux imports utilisent le même préfixe pour deux espaces de noms différents. L'écriture produit du GPX 1.1 dans l'ordre du schéma, avec les décimales exactes lues.
  3. **Room** (`:data`, version 2.8.5, schéma exporté dans `data/schemas/`) : `folders` (arbre, visibilité), `tracks` (métadonnées + **statistiques en cache** : distance, D+/D−, emprise, dates, nombre de points), `track_chunks`, `routes` (points en JSON), `waypoints`. Suppression en cascade (clés étrangères).
  4. **Points en blocs compacts** (`PointCodec`) : au plus **2 000 points par bloc** (`track_chunks`, clé `trackId/segment/chunk`), delta + varint zigzag, altitude au millimètre, temps en delta. Un bloc reste très en dessous de la fenêtre de 2 Mo d'un curseur SQLite Android, la liste de la bibliothèque ne décode jamais un point (elle lit `tracks`), et l'enregistreur de P6 **ajoutera des blocs** sans réécrire la trace. Le codec arrondit aux unités qu'il stocke (1e-7° ≈ 1 cm, 1 mm, 1 ms) : sur des fichiers Garmin dont les flottants 32 bits ont été élargis en doubles, l'aller-retour est exact à la tolérance près, pas au bit près (test dédié).
  5. **Un dossier racine par fichier importé**, nommé d'après le document ou le fichier ; les dossiers KML sont des sous-dossiers ; les espaces de noms du fichier sont gardés sur le dossier racine pour l'export. La visibilité d'un dossier s'applique à tout ce qu'il contient, **sans modifier** l'état propre de chaque élément (un élément masqué à la main reste masqué quand le dossier revient). L'export réécrit les éléments dans l'ordre d'importation.
  6. **Affichage** : deux `GeoJsonSource` (lignes, points) et des couches au-dessus des rasters ; couleur, épaisseur et opacité viennent des propriétés de chaque entité (changer un style = mettre à jour la donnée). Le GeoJSON est construit **hors du fil principal**, après une simplification de Douglas-Peucker à 1 m (invisible à tout zoom qui montre la trace entière) et des coordonnées à 6 décimales ; la géométrie simplifiée est gardée en mémoire (budget de 400 000 points), si bien que masquer, montrer ou recolorer ne redécode rien. `line-dasharray` n'étant pas pilotable par les données, les pointillés sont une seconde couche filtrée. Les flèches de sens sont une couche de symboles le long de la ligne (image dessinée au démarrage). **Pas d'étiquettes de texte** : le style de base n'a pas de polices (`glyphs`) ; le nom s'affiche à l'appui, dans une feuille.
  7. **Statistiques** (`TrackStatsCalculator`) : médiane glissante sur les altitudes, puis hystérésis de 5 m pour D+/D−, temps de marche DIN 33466, vitesse maximale mesurée sur au moins 10 s. **Altitudes manquantes** : tuiles Terrarium (z13, interpolation bilinéaire, décodeur PNG maison sans dépendance) lues **par le même client que la carte** (source `terrarium` : tuiles déjà stockées d'abord, mode hors ligne respecté) ; une altitude présente n'est jamais remplacée.
  8. **Import par le contenu** : `TrackImporter.detect` regarde les premiers octets (ZIP, `{`, élément racine) ; l'extension ne sert qu'à nommer le dossier. « Ouvrir avec » et « Partager » passent par la même route que les cartes `.aqx` : le contenu décide. L'export passe par un fichier de cache et un `FileProvider` pour le partage.
- **Conséquences** : le style d'une trace (couleur) **n'est pas écrit** dans le GPX exporté et les métadonnées du document (auteur, mots-clés) ne sont pas gardées par la bibliothèque (dette notée au journal). Les champs GPX 1.0 `speed`, `course`, `email` et l'e-mail / le lien de l'auteur 1.1 sont ignorés avec un `TrackIssue`. Plus de 50 000 points s'affichent d'après un test de performance JVM de construction (≈ 50 ms) ; la fluidité réelle du rendu reste à vérifier sur téléphone.
- **Alternatives rejetées** : une ligne SQLite par point (millions de lignes, listes lentes) ; un BLOB unique par trace (dépasse la fenêtre de curseur, impose de tout réécrire pour ajouter un point) ; DOM pour le GPX (mémoire) ; une bibliothèque tierce de GPX (perd les extensions inconnues) ; GeoJSON complet sans simplification (50 000 points × propriétés à chaque changement de style).


## ADR-019 — Création, édition et enregistrement : cœur pur testé, enregistreur à lots avec fichier de session, GPS seul, édition au réticule
- **Date / statut** : 2026-10-02 — Accepté. Les gestes, le service de premier plan, les permissions, le GPS réel et les dialogues n'ont pas pu être exécutés (pas d'émulateur) ; la logique est couverte en JVM / Robolectric avec un GPS simulé.
- **Contexte** : P6 demande de dessiner et corriger des tracés au doigt, d'enregistrer une trace GPS pendant des heures écran éteint sans jamais la perdre, de poser des waypoints et de marcher vers l'un d'eux. Tout ce qui n'est pas de l'interface doit pouvoir se tester sans téléphone.
- **Décision** :
  1. **Enregistreur en trois couches.** `RecorderCore` (`:core`, pur) décide quels points garder : intervalle et distance minimaux, précision maximale, temps qui recule, saut impossible (vitesse), silence long ou série de mauvais points → **nouveau segment** ; altitude lissée par médiane de 5 puis hystérésis de 5 m (celle de `TrackStatsCalculator`) pour le dénivelé en direct. `RecordingController` (`:app`, singleton) relie un `LocationSource` au cœur et écrit **par lots** (tous les 10 points ou 15 s, et à chaque pause / arrêt) avec `TrackRepository.appendPoints`, qui ne ré-encode que le dernier bloc de la trace (ADR-018 : blocs de 2 000 points) ; un lot qui échoue reste en mémoire et est retenté. `RecordingService` n'est que l'hôte : service de premier plan de type `location`, verrou de réveil partiel, notification avec Pause / Reprendre / Arrêter, `START_STICKY` pour repartir si le système le tue.
  2. **Reprise après plantage sans migration de base.** Un petit fichier JSON (`filesDir/recording/session.json`, écrit de façon atomique, `RecordingSessionStore`) dit quelle trace est en cours d'enregistrement, depuis quand et combien de temps actif. Au démarrage, `checkForRecovery` le lit : l'utilisateur choisit de **continuer** (le cœur rejoue les points stockés, `replay`, et le prochain point ouvre un nouveau segment : le trou n'est pas relié) ou de **garder** ce qui existe. Une session qui pointe vers une trace supprimée est ignorée. Perte maximale : le dernier lot.
  3. **GPS seul** : `LocationManager.GPS_PROVIDER`, sans Google Play Services ni position réseau (cohérent avec l'ambition « hors ligne, sans compte »). Un GPS absent ou une permission refusée est affiché dans le panneau (`gpsUnavailable`) sans fermer l'enregistrement. Permission de position précise demandée avant de démarrer ; la permission de notification (API 33+) ne conditionne que la notification.
  4. **Édition : un modèle pur** (`PolylineEditor`, `:core`) avec historique d'instantanés borné **par le nombre total de points** (400 000), pour que l'annulation reste possible sur une grande trace sans exploser la mémoire. Un appui est interprété par `EditorGeoJson.interpret` : sommet à portée → le sélectionner (second appui : désélectionner) ; poignée de milieu → l'insérer comme sommet ; sinon ajouter un point (après le sommet sélectionné, sinon à la fin). **Déplacer = « Déplacer ici » au réticule** plutôt que glisser : le glisser entre en conflit avec le déplacement de la carte de MapLibre et n'est pas fiable sans appareil pour l'ajuster. **Main levée** : une couche Compose transparente (la carte ne bouge plus) enregistre le trait, ses points sont convertis en positions puis éclaircis par Douglas-Peucker.
  5. **Outils** (`TrackOps` : inverser, couper, fusionner, rogner, simplifier ; boutons de l'éditeur et de l'écran de statistiques). Inverser **efface les heures** (elles n'auraient plus de sens) ; couper **crée tout de suite** deux traces (la première garde nom et style, la seconde se nomme « … (2) », le point de coupure est partagé) ; fusionner **crée une nouvelle trace** et conserve les sources ; une trace à plusieurs segments demande lequel éditer ; une édition d'itinéraire ne garde le nom d'un point que s'il a gardé exactement ses coordonnées.
  6. **Waypoints** : symboles = 12 noms standard Garmin (`WaypointSymbols`, écrits tels quels dans le GPX), couleur du marqueur déduite du symbole ; saisie des coordonnées en DD, DDM ou DMS avec virgule ou point décimal (`CoordinateParser`, erreurs typées), réécriture dans un autre format à la demande. Les traces, itinéraires et waypoints créés à la main sont à la **racine** de la bibliothèque.
  7. **Cible** : `NavTarget` + `Navigation.guide` (distance, cap) et arrivée à 15 m ; la flèche tourne de `cap − orientation de la carte`, ce qui évite un capteur de boussole (le téléphone n'a qu'à avoir la carte orientée). Le GPS n'est écouté que **tant qu'une cible est posée**.
- **Conséquences** : dette notée — pas de glisser-déposer des sommets, pas de capteur de boussole (la flèche suppose la carte orientée « cap en haut » pour être juste en marchant), pas d'enregistrement sans permission de position précise, emprise de téléchargement dessinée toujours reportée. À vérifier sur téléphone : tous les gestes, la durée de vie du service écran éteint (critère de P6.3), la notification, la reprise après « forcer l'arrêt ».
- **Alternatives rejetées** : écrire chaque point en base (écritures et réveils inutiles, usure) ; ne garder les points qu'en mémoire jusqu'à l'arrêt (perte totale en cas de plantage) ; colonne « session » dans Room (migration pour une donnée qui vit quelques heures) ; `FusedLocationProvider` (dépendance Google Play Services) ; glisser les sommets (conflit de gestes) ; capteur de boussole dès P6 (dérive, calibration, à étudier en P9).

## ADR-020 — Export PDF : écrivain PDF maison en JPEG, maths de cadre dans `:core`, projections de grille maison, bandes de 512 lignes, pas de proj4j
- **Date / statut** : 2026-10-02 — Accepté. Remplace en partie ADR-005 (la clause « les grilles UTM / Lambert 93 passent par proj4j »). Mise en page à l'écran, sélecteur de fichier, `PdfRenderer` et impression papier n'ont pas pu être exécutés (pas d'émulateur) ; la géométrie, le PDF produit et la composition d'image sont vérifiés en JVM (Robolectric en graphismes natifs) et par lecture du PDF avec poppler.
- **Contexte** : P7 doit sortir une carte **à l'échelle exacte** (un segment de 10 km = 200 mm à 1:50 000), avec un habillage vectoriel, une grille UTM / Lambert 93 / MGRS / WGS84, un atlas multipage et une mémoire bornée, y compris en A3 à 300 dpi. La spec envisageait `android.graphics.pdf.PdfDocument` (images Flate sans perte, donc des fichiers de plusieurs dizaines de Mo) et proj4j.
- **Décision** :
  1. **Écrivain PDF 1.4 maison dans `:core`** (`fr.toolmap.core.pdf.PdfWriter`) : polices de base 14 (Helvetica, codage WinAnsi, métriques dans `HelveticaMetrics`, aucune police embarquée), `MediaBox` à valeurs **réelles** (A4 = 841,89 × 595,276 pt, pas d'arrondi à l'entier comme `PdfDocument`) et images **JPEG** (`DCTDecode`) en bandes. Raisons : l'échelle exacte exige une `MediaBox` non arrondie ; JPEG garde les fichiers petits (une A4 à 1:50 000 de 150 dpi pèse ≈ 0,2 Mo avec des tuiles de synthèse ; la taille réelle avec de vraies tuiles IGN reste à mesurer) ; l'écrivain est pur donc testé en JVM (relecture par `PdfProbe`, `pdftoppm`/`pdfinfo` sur les échantillons). Pas de bibliothèque tierce (PdfBox-Android) : ≈ 600 lignes d'écrivain (+ la table de largeurs Helvetica) suffisent, sans dépendance de plus.
  2. **Géométrie du cadre** (`FrameProjection`, `PrintMath`) : le cadre est une fenêtre Web Mercator à l'**échelle exacte au centre** (facteur `1/cos φ` appliqué au sol) ; zoom de tuiles `z* = ceil(zoom équivalent)` (léger : `z* − 1`), borné par les zooms natifs de chaque source avec **avertissement de sur-échantillonnage** ; les plages d'affichage des calques (N3) se comparent au zoom équivalent **fractionnaire**, pas à `z*`. L'écart d'échelle aux bords (≈ 0,075 % en A4 à 1:50 000 vers 45° N) est affiché au résumé. Mesuré contre un calcul Mercator indépendant : ≤ 0,09 mm sur le papier.
  3. **Rendu par bandes de 512 lignes** (`PrintRaster`) avec 2 lignes de recouvrement et placements de tuiles en pixels **entiers** (aucun joint entre bandes) ; la mémoire d'une bande est bornée quelle que soit la page. `BandRenderer` est une interface de `:core` ; `AndroidBandRenderer` (`:data`) l'implémente avec `Bitmap` / `Canvas` (opacité et `ColorMatrixColorFilter` par calque, la matrice étant l'équivalent exact des réglages du shader MapLibre : `RasterAdjust.colorMatrix`), un pool de 6 fils et un `Semaphore` selon `BulkPolicy`.
  4. **Lecture des tuiles pour l'impression** (`HttpPrintTileFetcher`) : **stockage local d'abord** (une tuile stockée n'expire jamais) ; le réseau n'est utilisé que pour les sources **sans** politique restrictive (`BulkPolicy`) et hors mode hors ligne ; les hôtes de type OSM / OpenTopoMap restent « stockage ou téléchargement de zone seulement ». Avant l'export, `ZoneDownloader.missing` compte les tuiles absentes et `runTiles` les télécharge (≤ 1 500). Une tuile 404 est vide (hors couverture), pas manquante ; une tuile **indisponible** est **hachurée** (calque de fond) et comptée dans la page de résultat et la légende.
  5. **Projections de grille maison** (`Utm`, `Lambert93`, `Mgrs`, `:core`) : série de Krüger à l'ordre 4 (UTM), Lambert conique conforme à 2 parallèles (Lambert 93), MGRS par-dessus l'UTM. `ProjectionsTest` compare UTM et Lambert 93 à **PROJ au millimètre** (aller-retour compris) et MGRS à la bibliothèque de référence (exceptions de zones comprises). Pas de proj4j : ≈ 280 lignes, aucun chargement de fichier de définitions, testable en JVM. **Remplace la clause proj4j de l'ADR-005** ; la restriction Web Mercator pour les *tuiles* de l'ADR-005 reste.
  6. **Atlas** (`Atlas.kt`) : grille de pages avec recouvrement, ou glouton **le long d'une trace** ; page d'assemblage en tête (contours numérotés) ; mini-carte de situation rendue une fois pour toutes les pages.
  7. **Mode d'impression de l'application** : le cadre est dessiné à la **taille réelle au sol** (`ScaleBar.metersPerDp` / `PrintViewport.frameDp`) ; l'impression est **toujours nord en haut** (la carte est remise au nord à l'ouverture, le cadre tourne de `−cap` si on la tourne) ; « Ajuster le zoom » règle la caméra (`PrintViewport.zoomToFit`). L'export tourne dans la portée du `ViewModel`, écrit par SAF (`CreateDocument`) et **supprime le fichier à moitié écrit** en cas d'annulation ou d'échec. Modèles de mise en page enregistrés dans `filesDir/print/templates.json` (écriture atomique, fichier abîmé → valeurs par défaut).
- **Conséquences** : 89 tests `:core`, 12 `:data` et 9 `:app` ajoutés (dont deux PDF produits avec de vraies images JPEG et relus avec poppler). Dette : l'export n'est **pas** un service de premier plan (un export long peut être interrompu si Android tue le processus) ; les pages d'atlas n'ont pas de renvoi de numéro de page sur les bords ; l'échelle de la page d'assemblage n'est pas arrondie ; les textes de la grille WGS84 sont français dans `:core` ; JPEG seulement (qualité ≈ 88) ; les tuiles manquantes d'un calque **superposé** sont comptées mais pas hachurées ; une tuile qui n'existe pas côté serveur est comptée « manquante » avant l'export. Restent à vérifier sur téléphone : superposition du cadre sur la vraie caméra MapLibre, geste d'ajustement, enregistrement / ouverture / partage, aperçu `PdfRenderer`, mémoire et durée d'une A3 à 300 dpi, impression « taille réelle » mesurée à la règle.
- **Alternatives rejetées** : `android.graphics.pdf.PdfDocument` (pages arrondies au point entier, images Flate lourdes, impossible à tester en JVM) ; PdfBox-Android (dépendance lourde pour ce besoin) ; proj4j (taille, chargement dynamique, pour trois projections connues) ; rendu de la carte MapLibre en bitmap (échelle non exacte, résolution limitée par l'écran, sources sous restriction non lisibles hors ligne) ; export en service de premier plan dès P7 (reporté, nécessite un appareil pour régler notification et cycle de vie).

## ADR-021 — Partage de position : protocole pur dans `:core` sur une interface de transport, WebRTC DataChannel dans `:p2p`, maillage par signalisation relayée, chiffrement de groupe, contrôleur testé avec un réseau simulé
- **Date / statut** : 2026-10-02 — Accepté (précise ADR-004). **Aucune liaison WebRTC réelle, aucune lecture de QR par la caméra, aucun service de premier plan ni aucun écran n'ont pu être exécutés** (pas d'émulateur, pas de second téléphone) : le protocole, le maillage, le chiffrement, le codec d'appairage et le contrôleur sont vérifiés en JVM ; le reste est à vérifier sur deux, puis trois téléphones.
- **Contexte** : P8 demande un partage de position sans serveur, entre jusqu'à ~10 téléphones, où un nouvel arrivant ne s'appaire qu'une fois (QR ou lien) et où les autres liaisons se négocient d'elles-mêmes. Le risque principal est que presque tout dépend de WebRTC, qu'on ne peut pas lancer ici : il faut donc que la plus grande partie de la logique n'en dépende pas.
- **Décision** :
  1. **Tout le protocole est du Kotlin pur dans `:core`** (`fr.toolmap.core.p2p`) : `Base45`, codec d'appairage binaire (`PairingCodec` : magic `0x544D`, version avec drapeau deflate, genres OFFER = 1 / ANSWER = 2 / **MESH_OFFER = 3**), `Sdp` (reconstruction d'une SDP complète à partir de la charge réduite), `GroupCrypto`, `Wire` (messages), `MeshNode` (état du groupe), `ShareCadence`, `ShareModels` (réglages et groupe sauvegardés), `ShareGeoJson` (couches de la carte). Le réseau n'y entre que par deux interfaces : `MeshLink` (une liaison ouverte) et `PairingTransport` (`MeshTransport` + première invitation `invite` / `join`). `WebRtcLinks` (`:p2p`) est **une** implémentation ; les tests utilisent un réseau en mémoire (`MeshNodeTest`) et un faux transport (`ShareControllerTest`). Cela garde la porte ouverte aux transports de P8.9.
  2. **WebRTC** : `io.getstream:stream-webrtc-android` 1.3.10, DataChannel seulement. Deux canaux **prénégociés** (`negotiated = true`, id 0 `ctrl` fiable et ordonné, id 1 `pos` non ordonné, `maxRetransmits = 0`) pour qu'aucun côté n'attende l'annonce de l'autre ; `UNIFIED_PLAN`, `MAXBUNDLE`, `GATHER_ONCE`, TCP désactivé. **ICE non progressif** : on attend la fin de la collecte (5 s au plus) puis on lit la SDP. STUN public (`stun.l.google.com`) activable, révélant seulement l'adresse réseau du téléphone.
  3. **Charge d'appairage réduite, SDP reconstruite** : on ne transmet que `ufrag`, `pwd`, empreinte DTLS SHA-256, rôle DTLS, ≤ 8 candidats UDP (IPv4 avant IPv6, `host` avant `srflx`, 5 gardés au plus : `PairingCodec.prune`) et l'identité (id de membre, nom ≤ 24 octets, couleur) ; l'autre côté reconstruit la SDP à partir d'un gabarit fixe. Texte du QR : binaire → deflate si plus court → **Base45** en mode alphanumérique, correction d'erreur M ; vérifié par test avec l'encodeur réel : ≤ version 15. **Le lien est en base64 URL-safe** (`toolmap://pair?d=…`), pas en Base45 comme le disait la spec : Base45 contient des espaces et des `%` qu'une messagerie abîme. Les candidats mDNS (`….local`) sont ignorés (non encodables).
  4. **Maillage** (`MeshNode`) : un nouveau membre ne s'appaire qu'avec un membre ; `HELLO` + `PEERS` à l'ouverture d'une liaison ; pour chaque autre membre connu, **le plus petit `memberId` fait l'offre**, transmise en `SIGNAL` (genre MESH_OFFER / ANSWER) par les liaisons qui existent. Positions, textes et SOS sont **inondés** avec TTL 4 et déduplication `(membre, n° de séquence)` ; un membre est « perdu » après 45 s de silence (pings toutes les 15 s) ; liaisons manquantes réessayées toutes les 20 s avec délai exponentiel après un échec de négociation (30 s). **La reconnexion est une nouvelle offre relayée**, pas un redémarrage ICE (écart à la spec §7).
  5. **Chiffrement** : clé AES-256-GCM dérivée par `HKDF-SHA256(groupKey, "toolmap-p2p-v1")`, en plus du DTLS ; chaque trame porte un nonce de 12 octets. Retrait d'un membre : `REKEY` (nouvelle clé, chiffré avec l'ancienne, envoyé à tous sauf à l'exclu) **et** `KICK` (type 11, ajouté pendant P8) envoyé sur la liaison de l'exclu, chiffré avec l'**ancienne** clé : sans lui, l'exclu ne saurait jamais qu'il est dehors. Les trames de l'exclu encore en vol sont ignorées. **N'importe quel membre peut retirer n'importe qui** (pas d'administrateur) ; l'exclu n'est prévenu que si celui qui le retire ou un de ses voisins a une liaison directe avec lui.
  6. **Application** : `ShareController` (`:app`, singleton) possède le `MeshNode`, le transport, les réglages (`filesDir/p2p/share.json`, écriture atomique, `ShareStore`), les codes affichés (invitation, réponse), l'envoi du GPS selon `ShareCadence` (intervalle, déplacement minimal, envoi forcé après 5 intervalles) et les alertes SOS (restent jusqu'à « Vu », disparaissent 2 min après le dernier envoi). `ShareService` n'est que l'hôte (service de premier plan `location`, verrou de réveil partiel, **non relancé par le système** : après une mort du processus, le groupe revient avec sa clé mais sans liaison et sans partage tant que l'utilisateur ne le rallume pas). Il n'est démarré que **pendant que la position est partagée** : un membre qui ne fait que regarder n'est servi qu'application ouverte (le type `location` exige la permission de position). Un SOS reçu déclenche une notification à haute priorité tant que le service tourne (donc pendant le partage).
  7. **Carte** : un disque par membre (couleur du membre, estompé si la position a plus de 10 min ou si le membre est perdu, anneau rouge en SOS) et sa traînée en ligne ; l'identifiant de membre (64 bits) voyage en **chaîne** dans le GeoJSON (un double perd des chiffres) ; **pas d'étiquette de texte** (pas de polices dans le style de base, comme ADR-018) : un appui ouvre la fiche du membre (voir sur la carte, aller vers lui via `NavTarget`, enregistrer sa traînée comme trace de la bibliothèque, le retirer).
  8. **Caméra** : CameraX 1.6.2 (aperçu + analyse), ZXing en décodage de la plaque de luminance (`QrCodes.decode`, y compris image inversée), sans Google Play Services ; refus de la permission ou caméra absente → coller le lien reçu par message.
- **Conséquences** : tests ajoutés : `:core` p2p (codecs, messages, maillage à 3+ membres, rekey / kick, reconnexion, rejeu de traînée, modèles, GeoJSON), `:p2p` (QR, `ShareStore`), `:app` (`ShareControllerTest`, 17 cas dont cadence, SOS, exclusion, redémarrage). **Risques non levés, à vérifier sur appareil** : l'acceptation par libwebrtc d'une SDP reconstruite depuis le gabarit ; le comportement du module audio par défaut de `PeerConnectionFactory` sans audio utilisé (permission micro non demandée) ; IPv6 limité par `prune` ; hôtes mDNS ignorés (deux téléphones sur un Wi-Fi qui force l'obfuscation mDNS pourraient ne trouver que `srflx`) ; CGNAT symétrique (limite connue d'ADR-004) ; **taille de l'APK** (bibliothèques natives de WebRTC pour toutes les ABI : filtrage des ABI prévu en P10) ; consommation de batterie écran éteint ; fiabilité de la lecture des QR de version 12-15 sur de vrais écrans.
- **Alternatives rejetées** : tout dans `WebRtcLinks` (rien ne serait testable ici) ; trickle ICE (plusieurs QR ou allers-retours) ; SDP complète dans le QR (≈ 1 Ko+, hors de portée d'un QR lisible) ; ICE restart pour la reconnexion (plus de cas à gérer sans appareil pour les observer) ; administrateur de groupe (contraire à « aucun serveur, aucune autorité » et sans bénéfice pour 2 à 10 amis) ; service de premier plan permanent même sans partage (exige la permission de position pour rien) ; bibliothèque tierce de QR Compose.

## ADR-022 — Réglages, formats de coordonnées, grille d'écran et mesure : un fichier JSON de réglages, des maths pures dans `:core`, aire exacte sur l'ellipsoïde
- **Date / statut** : 2026-10-02 — Accepté. Complète ADR-020 (projections maison) sans le contredire. Les écrans (réglages, barre de mesure, grille, pastille), le thème et le mode gants n'ont pas pu être exécutés (pas d'émulateur) ; tout le calcul est vérifié en JVM.
- **Contexte** : P9 ajoute des réglages qui changent l'affichage partout (unités, format de coordonnées, thème), une grille sur la carte et un outil de mesure. Il faut que ces choix soient lus par l'interface, mais aussi par du code qui n'est pas de l'interface (la notification d'enregistrement), et que la logique reste testable sans téléphone.
- **Décision** :
  1. **Réglages = un modèle sérialisable et un fichier.** `AppSettings` (`:core`, tous les champs ont une valeur par défaut, `AppSettingsCodec.decodeOrDefault` ne lève jamais : un fichier abîmé ou d'une version plus récente redonne des valeurs utilisables) et `SettingsStore` (`:data`, `filesDir/settings.json`, écriture atomique par fichier temporaire puis renommage, `StateFlow`, mises à jour sérialisées). Pas de DataStore : même motif que `WorkStateStore` et `WorkspaceStore`, aucune dépendance de plus, testable en JVM. Les réglages sont inclus dans la sauvegarde de P9.6.
  2. **Un seul point d'entrée pour écrire un nombre à l'écran** : `Formats` (`:app`), construit à la racine depuis les réglages et fourni par `LocalFormats`. Il enveloppe `TrackFormat` (qui sait maintenant écrire en unités impériales : ft, mi, mph, ac) et `CoordinateFormat.format`. Les 35 appels directs à `TrackFormat.*` de l'interface passent par lui ; seule la notification du service d'enregistrement, qui n'est pas composable, lit `SettingsStore` directement.
  3. **Coordonnées** : `CoordinateSystem` = DD, DDM, DMS, UTM, MGRS, Lambert 93. `CoordinateFormat.format` écrit dans le système choisi ; au-delà de 80° S / 84° N, UTM et MGRS retombent sur les degrés décimaux (le texte le dit) ; Lambert 93 hors de la France (`Lambert93.covers`) est écrit mais signalé « hors du domaine de validité ». `GridReference` lit UTM (`31T 452482 E 5011717 N`, avec ou sans espaces, avec ou sans `mE`), MGRS (`31T DQ 52482 11717`, cellule : on prend le centre) et Lambert 93 (`L93`, `Lambert 93`, `EPSG:2154`) ; ce qui ressemble à une référence mais n'en est pas une (zone 61, carré inexistant, E hors de la France) donne une erreur typée `GRID`, pas un échec muet. Pour MGRS la lettre de ligne se répète tous les 2 000 km : on la lève par la latitude de la bande (tolérance ±0,5° au bord).
  4. **Grille d'écran** (`ScreenGrid.plan`) : calcul pur à partir de l'emprise visible. Les lignes sont **droites dans leur système et courbes sur la carte** : chacune est échantillonnée en 24 points puis reprojetée en longitude / latitude (écrits à 7 décimales, 1 cm). Pas choisi dans 1-2-5 × 10ⁿ pour ≈ 6 lignes, 40 au plus (`TOO_WIDE` au-delà), pas minimal de 1 km pour MGRS avec trait épais tous les 100 km, trait épais tous les 5 pas ailleurs. Pas de **texte** sur la carte (pas de polices dans le style de base, ADR-018) : la pastille du centre dit « Grille UTM 31T · 1 km » et les coordonnées du centre. Recalculée hors du fil principal à chaque fin de mouvement.
  5. **Mesure** : les distances restent celles de la sphère de rayon moyen (même formule que partout, erreur < 0,5 %) ; la **surface est exacte sur l'ellipsoïde WGS84** par projection cylindrique équivalente (latitude authalique, formule de Snyder), indépendante de la zone UTM et sans coupure à l'antimeridien. Vérifié contre une intégrale de surface indépendante (1e-9 en relatif) et contre un carré UTM corrigé du facteur d'échelle. Les arêtes du polygone sont des lignes droites de la projection, qui s'écartent des géodésiques d'une quantité négligeable aux tailles d'une randonnée. L'outil place des points (appui ou réticule, comme le dessin de ligne, avec `PolylineEditor`), est **exclusif avec le dessin et l'impression**, et peut garder la ligne comme itinéraire ou comme trace (puis se ferme, comme le dessin).
  6. **Thème** : clair / sombre / système, couleurs dynamiques (Android 12+) désactivables, barres système réalignées sur le thème choisi (`enableEdgeToEdge` rappelé). **Mode gants** : cibles tactiles minimales de 64 dp (48 dp sinon) et texte agrandi de 20 % (`LocalDensity`, `LocalMinimumInteractiveComponentSize`).
- **Conséquences** : tests ajoutés : `:core` (`GridReferenceTest`, `ScreenGridTest`, `AppSettingsTest` dont unités impériales, `MeasureTest`, `MeasureGeoJsonTest`), `:data` (`SettingsStoreTest`), `:app` (`MeasureViewModelTest`). Dette : les réglages d'altitude et d'alertes sont enregistrés dès maintenant mais n'ont d'effet qu'avec P9.4 et P9.5 ; la langue n'est pas réglable (interface en français seulement) ; la grille se recalcule à la fin du mouvement, pas pendant ; les sommets de la mesure ne se déplacent pas (annuler / rétablir seulement).
- **Alternatives rejetées** : DataStore Preferences (dépendance, pas de gain pour un objet unique) ; formater dans chaque écran (35 oublis possibles à chaque nouvelle unité) ; proj4j pour la grille (rejeté par ADR-020) ; aire par la formule du polygone sphérique (erreur jusqu'à 0,5 % contre 1e-9 ici) ; grille avec étiquettes de texte (pas de polices, ADR-018) ; mesure intégrée à l'éditeur de lignes (deux outils aux gestes identiques mais à vocations différentes, l'un enregistre, l'autre calcule).

## ADR-023 — Recherche : deux géocodeurs interrogés à la validation seulement, lecture tolérante des réponses, recherche locale instantanée, aucune requête hors ligne
- **Date / statut** : 2026-10-02 — Accepté. L'écran de recherche n'a pas pu être exécuté (pas d'émulateur) ; les parseurs sont vérifiés sur des réponses réelles des services, le client avec `MockWebServer`, le ViewModel avec un faux géocodeur.
- **Contexte** : P9.1 demande de retrouver un lieu par son nom, en France comme dans le monde, et de retrouver ses propres éléments. Les deux services publics ont des conditions : Nominatim impose un `User-Agent` identifiant, au plus une requête par seconde et interdit l'auto-complétion ; l'IGN Géoplateforme n'a pas de clé mais deux index (`poi` pour les toponymes, `address` pour les adresses) dont les réponses n'ont pas la même forme (champs texte ou tableaux).
- **Décision** :
  1. **Appel à la validation uniquement** (touche « Rechercher » du clavier) : jamais pendant la frappe, jamais en **mode hors ligne** (le ViewModel n'envoie rien et le dit). Ce qui est local (position tapée, traces, itinéraires, waypoints) se met à jour à chaque lettre, sans réseau.
  2. **Deux services, trois requêtes, indépendantes** : IGN `poi`, IGN `address` et Nominatim partent ensemble ; la panne ou la lenteur de l'un n'empêche pas d'afficher la réponse des autres, et chaque panne est rapportée par service (réseau, statut HTTP dont 429, réponse illisible). Ordre d'affichage : lieux IGN, Nominatim, adresses IGN ; une recherche qui commence par un chiffre (« 12 rue… ») met les adresses d'abord. Un même lieu trouvé deux fois (même nom à l'article près, à moins de 300 m) n'est montré qu'une fois, le premier service l'emporte. Le centre de la carte oriente les deux services (`lat`/`lon` pour l'IGN, `viewbox` sans `bounded` pour Nominatim).
  3. **Politique Nominatim respectée par construction** : `GeocoderClient` espace ses appels Nominatim d'au moins 1,1 s (verrou, horloge et pause injectables, testé), envoie le `User-Agent` `ToolMap/<version> (+https://github.com/JRRocks/ToolMap)`, et l'écran affiche la mention « © contributeurs OpenStreetMap » (et « © IGN » pour l'autre). Client HTTP **à part** (pas celui des tuiles), délais de 10 s par lecture et 20 s par appel, réponse limitée à 1 Mo, annulation de l'appel quand la recherche est annulée.
  4. **Lecture tolérante** (`GeocodeParsers`, `:core`) : une réponse qui n'est pas ce que le service documente donne `null` (« illisible »), une entrée sans position, avec une position impossible ou sans nom est ignorée sans faire échouer les autres, un champ qui est tantôt texte tantôt tableau est lu des deux façons. Le rectangle `boundingbox` de Nominatim n'est gardé que s'il dépasse 0,001° (≈ 100 m) : le petit carré d'un sommet ou d'un bâtiment n'est pas un cadrage.
  5. **Recherche locale** (`LocalSearch`, `:core`) : comparaison sans casse ni accents (`Normalizer` NFD), tous les mots doivent figurer dans le nom ou dans la note, classement nom exact, nom commençant par la saisie, mot du nom (l'élision compte : « écrin » trouve « l'Écrin »), n'importe où dans le nom, note. Une trace ou un itinéraire est montré au milieu de son emprise et cadré sur elle. Une position tapée (`CoordinateParser`, toutes les écritures de P9.3) devient un résultat en tête.
  6. **Actions sur un résultat** : voir sur la carte (point ou emprise), aller vers (cible de navigation), enregistrer comme waypoint (nom et lieu en description). La distance au centre de la carte est écrite dans les unités des réglages.
- **Conséquences** : tests ajoutés : `GeocodeParsersTest`, `LocalSearchTest` (avec `SearchMergeTest`) dans `:core` ; `GeocoderClientTest` (10 cas : paramètres, ordre et doublons, pannes isolées, réponses illisibles, absence de réseau, requête vide, espacement Nominatim, annulation) dans `:data` ; `SearchViewModelTest` (7 cas) dans `:app`. Dette : pas de marqueur du résultat sur la carte (le viseur indique le centre), pas d'historique de recherches, pas de recherche par catégorie (« refuges autour de moi »), les catégories OSM sont affichées telles quelles (en anglais), les réponses ne sont pas mises en cache, pas d'index de recherche local pour de très grandes bibliothèques (parcours linéaire).
- **Alternatives rejetées** : auto-complétion pendant la frappe (interdite par la politique de Nominatim, et coûteuse en données) ; un seul service (l'IGN ne couvre que la France, Nominatim est moins précis sur les toponymes français) ; bibliothèque de géocodage embarquée (taille) ; Photon ou autre instance publique (autre politique d'usage, aucun gain démontré) ; cache disque des réponses (ajoute de l'état pour peu de gain).

## ADR-024 — Alertes : règles pures dans `:core`, état dans un fichier JSON, service de premier plan tant que quelque chose est surveillé
- **Date / statut** : 2026-10-02 — Accepté. Les règles sont vérifiées en JVM avec un GPS simulé ; la notification, la vibration, le son, le service écran éteint et le rendu sur la carte n'ont pas pu être exécutés (pas d'émulateur).
- **Contexte** : P9.4 demande deux alertes : l'arrivée près d'un waypoint surveillé, et l'écart par rapport à une trace ou un itinéraire que l'on suit. Un GPS bruité produit des franchissements de seuil en rafale ; une trace qui repasse près d'elle-même (aller-retour, boucle) rend « le point le plus proche » ambigu ; l'alerte n'a de valeur que si elle arrive écran éteint.
- **Décision** :
  1. **`AlertEngine` (`:core`)** est une machine à états sans plateforme : positions en entrée, événements en sortie (`Proximity`, `OffTrack`, `BackOnTrack`, `Arrived`) et un état de suivi (écart, distance parcourue, distance restante). Règles : une position moins précise que 150 m est ignorée ; **proximité** : un seul événement à l'entrée du rayon, réarmé seulement au-delà de 1,25 × le rayon (hystérésis), un point déjà « dedans » à la première lecture est marqué sans bruit (activer une alerte à côté du point n'est pas une surprise) ; **écart** : la limite doit être dépassée, et dépasser aussi la précision de la position, par **3 positions de suite** avant l'alerte, répétée toutes les 60 s, avec « de retour sur la trace » en dessous de 0,8 × la limite ; **arrivée** : fin de la ligne à moins de 30 m après plus de la moitié parcourue (une boucle n'« arrive » pas à son propre départ).
  2. **`TrackFollower` (`:core`)** projette une position sur la ligne (projection plane centrée sur la position, distances le long de la ligne géodésiques). La **progression connue sert d'indice** : parmi les candidats presque aussi proches que le meilleur *et situés sur un autre tronçon* (pas le segment voisin, qui fausserait la progression en marche avant : bug trouvé par les tests), celui dont la distance parcourue est la plus proche de l'indice l'emporte. Sans progression, l'indice est le départ (une boucle commence à son début). « Sens inverse » inverse la ligne.
  3. **État persistant** (`AlertState` : ligne suivie + waypoints surveillés avec rayon optionnel) dans `filesDir/alerts.json`, écriture atomique comme les réglages, **pas dans Room** : pas de migration de la base (version 1, schéma exporté) pour un attribut de waypoint, et le rayon propre à un waypoint ne sort pas dans les exports GPX. Conséquence : à sauvegarder avec le reste (P9.6). Les éléments supprimés de la bibliothèque sont oubliés, mais **uniquement ceux qu'on a vus disparaître** (et, à la première lecture, ceux que l'état sauvegardé avant le démarrage mentionnait) : un instantané de la bibliothèque qui ne contient pas encore un waypoint tout juste créé ne doit pas effacer l'alerte posée dessus (bug trouvé par les tests).
  4. **`AlertController`** (un par processus) écoute le GPS (une position toutes les 2 s : il n'en faut pas à 1 Hz) et démarre le **service de premier plan `AlertService`** (type `location`, verrou de réveil partiel, bouton « Tout arrêter ») uniquement tant que quelque chose est surveillé. Le service n'est pas relancé par le système (`START_NOT_STICKY`) : au prochain lancement de l'application l'état est relu et le service redémarre. Si le système refuse de le démarrer depuis l'arrière-plan, l'alerte ne fonctionne que l'application affichée (l'échec est absorbé).
  5. **Son et vibration joués par l'application**, pas par le canal de notification (créé muet) : désactiver « Son » ou « Vibration » dans les réglages de ToolMap les coupe réellement, sans passer par les réglages système du canal. Gabarits de vibration différents (proximité, écart, arrivée).
  6. **Carte** : disque translucide au rayon de chaque waypoint surveillé ; bandeau de suivi (progression, restant, écart, sens inverse, arrêter) et dernière alerte conservée jusqu'à acquittement. Pas de surbrillance de la ligne suivie (elle reste dessinée par la bibliothèque si elle est visible).
- **Conséquences** : tests ajoutés : `TrackFollowerTest` (+ cas d'indice), `AlertEngineTest` (22 cas : hystérésis, amorçage silencieux, répétition, retour, arrivée, boucle, sens inverse…), `AlertStateTest`, `AlertGeoJsonTest` dans `:core` ; `AlertStateStoreTest` dans `:data` ; `AlertControllerTest` (14 cas avec GPS, notificateur et service simulés) et `AlertTextsTest` dans `:app`. Dette : pas d'alerte sans position GPS (pas de position réseau, comme partout), consommation à mesurer en P10.1, pas de réglage du délai de répétition ni du nombre de confirmations, pas de zone d'alerte de forme libre, notification de proximité identique pour tous les waypoints (pas de texte personnalisé).
- **Alternatives rejetées** : API de géorepérage de Google Play Services (dépendance exclue par le projet, ADR-002) ; rayon stocké dans la table des waypoints (migration, export) ; alerte déclenchée à la première position hors limite (les sauts du GPS en milieu urbain ou en forêt la rendraient inutilisable) ; son et vibration du canal de notification (non coupables depuis ToolMap) ; projection sur la seule distance au sommet le plus proche (fausse entre deux sommets éloignés d'une ligne droite).

## ADR-025 — Altimètre barométrique : pression au niveau de la mer estimée par un filtre de Kalman, calage sur le relief et le GPS, altitude fournie par la source de positions
- **Date / statut** : 2026-10-02 — Accepté. Le capteur, la pastille et le calage manuel n'ont pas pu être exécutés (pas d'émulateur, pas de téléphone) : toute la logique est vérifiée en JVM avec des pressions, des positions et un relief simulés ; la qualité réelle du calage reste à constater sur le terrain.
- **Contexte** : P9.5 demande un altimètre barométrique qui se cale tout seul. Une pression ne donne une altitude que face à la pression au niveau de la mer *du moment*, qui varie de quelques hPa par jour avec la météo (1 hPa ≈ 8 m) : un baromètre non calé dérive de plusieurs dizaines de mètres en une demi-journée, alors qu'il est exact à 1 ou 2 m près sur une montée. Le GPS donne une altitude absolue mais bruitée (10 à 30 m), et sur Android, `Location.getAltitude()` est par contrat la hauteur au-dessus de l'**ellipsoïde** WGS84, qui s'écarte du niveau de la mer de −100 à +85 m (≈ +48 m en France) ; le niveau de la mer n'est donné (`getMslAltitudeMeters()`) qu'à partir d'Android 14, et le dépôt n'embarque pas de modèle de géoïde.
- **Décision** :
  1. **Estimer la pression au niveau de la mer, pas l'altitude.** `BarometricAltimeter` (`:core`) tient un filtre de Kalman à une dimension sur cette pression : chaque altitude de référence en déduit une mesure de pression au niveau de la mer (atmosphère standard, `Atmosphere`) pondérée par l'incertitude de la référence convertie en hPa ; l'incertitude de l'estimation croît avec le temps au rythme de la météo (1 hPa/h, réglable) ; un écart de plus de 4 écarts-types est rejeté, et **5 rejets de suite** font repartir l'estimation de la référence (c'est l'estimation qui avait tort). Les variations courtes (montée, descente) sont exactes quel que soit le calage ; le calage ne fixe que la valeur absolue. La pression est lissée (constante de temps 4 s) et périmée au bout de 30 s sans mesure (plus d'altitude plutôt qu'une altitude figée).
  2. **Trois sources de référence, d'incertitudes différentes** : le relief au point (`TERRAIN`, tuiles Terrarium, σ = 20 m : niveau de la mer garanti, quelques mètres en terrain ouvert, des dizaines sur une falaise), le GPS **au-dessus du niveau de la mer** (`GPS_MSL`, Android 14+, σ = max(précision verticale, 8 m)), la saisie de l'utilisateur (`MANUAL`, σ = 2 m, repart de zéro, jamais limitée). Le GPS d'un téléphone plus ancien est de **datum inconnu** (`GPS_UNKNOWN_DATUM`, σ = 60 m) : simple indice, et **jamais pris tant que le relief a calé depuis moins de 10 min**. Chaque source est limitée à une référence toutes les 30 s (des références rapprochées d'une même source ne sont pas indépendantes : elles ramèneraient l'incertitude à zéro et le filtre ne suivrait plus la météo — vérifié par le test « la météo change pendant 6 h »).
  3. **Relief demandé peu et avec parcimonie** (`AltitudeService`) : à la première position précise (≤ 50 m), puis toutes les 5 min ou tous les 500 m ; échec ou hors ligne : nouvel essai après 60 s ; pression pas encore reçue : après 10 s. `TerrariumElevation` garde 4 tuiles décodées (une marche reste dans la même tuile longtemps) et passe par le même client que la carte, donc par le stockage local et le mode hors ligne.
  4. **Choix de l'altitude** (`AltitudeSelector`) : GPS seul → celle du GPS ; baromètre seul → celle du baromètre (aucune tant qu'il n'est pas calé) ; **automatique** → le baromètre quand son incertitude ≤ 30 m, le GPS sinon. Un téléphone sans baromètre se comporte comme « GPS seulement » quel que soit le réglage.
  5. **L'altitude est donnée par la source de positions**, pas par chaque consommateur : `AltitudeAwareLocationSource` enveloppe `LocationSource` (patron décorateur) et remplace `Fix.ele` ; l'enregistrement, la navigation, le partage et les alertes voient donc la même altitude sans changer. Le capteur est **compté par référence** (`attach` / `detach` à chaque flux collecté) : éteint si personne n'écoute ou en mode « GPS seulement », et le changement de mode le démarre ou l'arrête en cours de route. `AltitudeService` ne dépend pas de `LocationSource` (qui dépend de lui : pas de cycle). La pastille de la carte écoute les positions (`AltitudeController.watch`) seulement écran allumé (`repeatOnLifecycle(STARTED)`).
  6. **`Fix`** gagne `verticalAccuracyMeters` et `eleIsMsl` ; `AndroidLocationSource` prend l'altitude au-dessus du niveau de la mer sur Android 14+ (aussi pour le mode « GPS seulement » : c'est la convention du format GPX) et ne la déclare niveau de la mer que dans ce cas.
  7. **Pastille et calage manuel** : « Alt. 1 234 m ± 8 m · baromètre » sous les coordonnées ; un appui ouvre « Caler l'altimètre » (saisie en m ou en ft selon les réglages, refusée avec un message tant que le baromètre n'a pas de mesure).
- **Conséquences** : tests ajoutés : `AtmosphereTest` (4), `BarometricAltimeterTest` (14 : calage, variations, lissage, pondération, valeurs aberrantes, limitation par source, météo sur 6 h avec et sans suivi, incertitude, périmé, absurdités, calage manuel, remise à zéro) et `AltitudeSelectorTest` (3) dans `:core` ; `TerrariumElevationTest` (3) et `AltitudeServiceTest` (12 : modes, sans baromètre, calage par le GPS puis par le baromètre, relief, datum inconnu, cadence des demandes de relief, échec puis nouvel essai, pression pas encore reçue, calage manuel, capteur compté par référence) dans `:data` ; `AltitudeAwareLocationSourceTest` (4), `AndroidPressureSourceTest` (2, avec le `SensorManager` de Robolectric) et `LocationToFixTest` (4) dans `:app`. Dette : pas de modèle de géoïde (le GPS d'avant Android 14 ne sert qu'avec une grande incertitude : en mode automatique, c'est alors le GPS qui s'affiche, tel quel) ; l'état du filtre n'est pas enregistré (un redémarrage recale : une position, une demande de relief) ; le relief Terrarium est à 13–19 m par pixel et peut s'écarter de dizaines de mètres sur une falaise ou une vallée étroite (d'où σ = 20 m et la pondération) ; consommation du capteur et du GPS de la pastille à mesurer en P10.1 ; pas de calage sur un waypoint coté ni sur un sommet connu (la saisie manuelle y supplée) ; l'altitude des points déjà enregistrés n'est pas corrigée après coup.
- **Alternatives rejetées** : estimer directement l'altitude (impossible de séparer météo et mouvement) ; moyenne glissante du GPS (le GPS dérive de façon corrélée, la moyenne ne la supprime pas) ; embarquer un modèle de géoïde EGM96 (≈ 2 Mo pour corriger une imprécision que le relief corrige déjà quand il est disponible) ; compensation par une station météo (réseau, aucun gain hors ligne) ; interroger le relief à chaque position (un appel réseau toutes les 2 s) ; lire le capteur dans chaque consommateur (quatre altitudes différentes à l'écran, dans la trace et dans le partage).

## ADR-026 — Sauvegarde et restauration : un zip vérifié, restauration préparée à côté puis appliquée au démarrage suivant
- **Date / statut** : 2026-10-02 — Accepté. Le sélecteur de documents, le redémarrage du processus et la reprise sur une vraie base n'ont pas pu être exécutés (pas d'émulateur) ; le format, la vérification, l'application et le retour en arrière sont vérifiés en JVM sur des dossiers temporaires (`BackupServiceTest`) et l'écran avec `BackupService` réel (`BackupViewModelTest`).
- **Contexte** : P9.6 demande de sauvegarder les données de l'utilisateur et de les restaurer (changement de téléphone, réinstallation). Les données sont éparpillées : une base Room, des fichiers JSON écrits atomiquement, des cartes importées, des cartes hors ligne (de quelques Mo à plusieurs Go en SQLite WAL), et deux choses qui ne doivent surtout pas voyager (la clé secrète du partage de position, la reprise après plantage de l'enregistrement). Restaurer à chaud dans un processus qui a déjà ouvert la base et lu ses fichiers (`SettingsStore`, `AlertStateStore`, Room, MBTiles ouverts) est fragile ; une archive fournie par l'utilisateur est une entrée non fiable (`zip-slip`, bombe de décompression, archive tronquée ou fabriquée).
- **Décision** :
  1. **Un zip dont le premier entrée est le manifeste** (version du format, application, rubriques couvertes, chaque fichier avec taille et SHA-256), puis les fichiers : on peut lire l'archive en flux (un `Uri` du sélecteur de documents ne se parcourt pas toujours), l'aperçu coûte la lecture d'un seul entrée, et la vérification se fait au fil de la lecture, sans copie temporaire de l'archive (qui doublerait la place utilisée pour des cartes hors ligne). Prix : l'écriture lit chaque fichier deux fois, et un fichier modifié entre les deux passes fait échouer la sauvegarde (le document de destination est supprimé) plutôt que d'en écrire une incohérente. Détail dans `docs/specs/backup-format.md`.
  2. **Liste blanche de chemins** (`BackupPaths`) : huit rubriques, un niveau de dossier, extensions imposées, aucun chemin avec `.`, `..`, antislash, segment vide ou commençant par un point. Ce qui n'y figure pas est refusé dans le manifeste comme dans l'archive : `p2p/share.json` (deux téléphones ne doivent pas partager une identité de partage), `recording/`, `downloads/`, les journaux SQLite. Le chemin canonique de chaque entrée est en outre contrôlé à l'extraction.
  3. **Une restauration remplace chaque rubrique couverte en entier** (même vide) et ne touche pas aux autres : restaurer une sauvegarde sans cartes hors ligne laisse celles du téléphone ; restaurer une sauvegarde sans carte importée efface celles du téléphone. Le manifeste liste donc les rubriques couvertes, pas seulement leurs fichiers.
  4. **Base copiée de façon cohérente** : journal replié (`wal_checkpoint(TRUNCATE)`), puis transaction d'écriture gardée ouverte le temps de la copie (les lectures continuent, les écritures attendent quelques centaines de millisecondes) ; la copie va dans un fichier temporaire d'où le zip est écrit. Cartes hors ligne : journal de chaque fichier replié par une seconde connexion, entrées non compressées (les tuiles sont déjà des images), **option désactivée par défaut** avec la taille affichée.
  5. **Restauration en deux temps** : (a) *préparation* à l'écran : lecture en flux, toutes les vérifications (manifeste valide en premier, place disponible, entrée listée, non dupliquée, pas plus grande que déclarée, SHA-256, chemin canonique, aucune entrée manquante) vers `restore-pending/`, marqueur `restore.json` écrit en dernier ; échec ou annulation : tout est supprimé, les données vivantes ne sont jamais touchées. (b) *application* **au démarrage suivant**, dans `Application.attachBaseContext` (avant l'injection Hilt, donc avant que quoi que ce soit ne lise un fichier ou n'ouvre la base) : chaque rubrique couverte est déplacée de côté puis remplacée ; une étape qui échoue défait tout ce qui a été fait. Le résultat est écrit dans `restore-report.json` et montré une fois. Aucun rechargement à chaud (`SettingsStore.reload()` et `AlertStateStore.reload()`, prévus par ADR-022 et ADR-024, ne sont donc pas appelés ici : ils restent disponibles). Un bouton « Redémarrer maintenant » lance l'activité dans un nouveau processus puis termine l'actuel.
  6. **Règles d'extraction Android** (`data_extraction_rules.xml`) : sauvegarde automatique du cloud exclue (déjà `allowBackup="false"`), transfert d'appareil à appareil sans `p2p/` ni `recording/` ; `backup_rules.xml` (`fullBackupContent`) dit la même chose pour Android 11 et avant (le lint `DataExtractionRules` exige les deux tant que `minSdk` < 31).
- **Conséquences** : tests ajoutés : `BackupModelTest` (8 : chemins permis et refusés, manifeste, versions, rapport) dans `:core` ; `BackupServiceTest` (17 : contenu de l'archive, clé de partage exclue, cartes hors ligne sur demande, base figée, aller-retour exact, rubrique vide qui remplace, aperçu sans extraction, progression, fichier qui n'est pas une sauvegarde, somme de contrôle fausse, entrée trop grande / non listée / absente, `../` et chemins interdits, version trop récente, fichier modifié pendant l'écriture, échec à mi-chemin avec retour exact, préparation inachevée jetée, restauration remplacée) dans `:data` ; `BackupViewModelTest` (10) et `RoomDatabaseFreezerTest` (2) dans `:app`. Dette : pas de chiffrement de l'archive (le fichier contient des traces : à garder en lieu sûr, l'écran le dit) ; pas de sauvegarde automatique ni planifiée ; pas de fusion (le choix est remplacer) ; restaurer vers un téléphone dont Android est plus ancien n'a pas été pensé (le fichier de base garde sa version de schéma, Room migre ou refuse) ; l'enregistrement en cours est arrêté par le redémarrage ; sur certains appareils le lancement d'une activité juste avant la fin du processus peut être retardé (le message dit que la restauration s'applique de toute façon au démarrage suivant).
- **Alternatives rejetées** : restauration à chaud (fermer Room, rouvrir les magasins, `reload()` : beaucoup d'états à resynchroniser, un échec laisse l'application dans un état mixte) ; exporter les traces en GPX et le reste en JSON (perd les dossiers, les rubriques non GPX, et ré-implémente la base) ; manifeste en dernier avec `ZipFile` (impose de copier l'archive entière avant de pouvoir l'inspecter) ; sauvegarde automatique Android (envoie des données de localisation hors du téléphone sans que l'utilisateur l'ait demandé, et ne couvre pas un changement de plateforme) ; chiffrer avec un mot de passe (gestion de mot de passe perdu, pas demandé).

## ADR-027 — Publication : clé fournie par l'environnement, trois architectures, R8 désactivé par défaut, licence laissée à l'auteur
- **Date / statut** : 2026-10-02 — Accepté. Les builds `assembleRelease`, `bundleRelease` et `assembleRelease -Ptoolmap.minify=true` aboutissent (vérifié) ; **aucun n'a été lancé sur un téléphone** et le workflow `release.yml` n'a pas été exécuté avec une vraie clé.
- **Contexte** : P10.3 demande une signature, des APK et un AAB de release, un choix de licence et une page d'aide. L'APK de debug de la CI faisait 114,9 MB, surtout à cause des bibliothèques natives de MapLibre et de WebRTC en quatre architectures. Une clé de publication ne doit pas être dans le dépôt, et une clé perdue interdit toute mise à jour. R8 peut casser, au démarrage ou à l'usage, du code qui passe par le JNI ou la réflexion, d'une façon que les tests JVM ne voient pas.
- **Décision** :
  1. **Clé par variables d'environnement** (`TOOLMAP_KEYSTORE_FILE`, `…_PASSWORD`, `TOOLMAP_KEY_ALIAS`, `TOOLMAP_KEY_PASSWORD`) : la configuration `release` n'existe que si elle est fournie ; sinon les builds de release réussissent et sortent non signés. Le workflow `release.yml` (manuel ou sur étiquette `v*`) la reconstruit depuis des secrets GitHub s'ils existent. La clé de debug fixe d'ADR-016 est inchangée, donc la CI continue de livrer un APK réinstallable par-dessus.
  2. **Architectures `arm64-v8a`, `armeabi-v7a`, `x86_64`** pour tous les types de build (le x86 32 bits a disparu des téléphones) ; pas de `splits.abi` (inutile avec l'AAB, qui livre l'architecture du téléphone, et il multiplierait les fichiers de la CI). Mesuré : APK de release 82,3 MB, AAB 42,5 MB.
  3. **R8 désactivé par défaut**, activable par `-Ptoolmap.minify=true` (et par l'entrée du workflow) : APK de 70,7 MB, compilation sans erreur, classes JNI de MapLibre et de WebRTC conservées (vérifié dans `mapping.txt`), mais **jamais exécuté**. Quand l'utilisateur l'aura essayé sur son téléphone, changer la valeur par défaut suffira ; le fichier de correspondance `mapping.txt` est publié avec le build réduit pour lire les traces de plantage.
  4. **Numéro de version** : `versionCode` = numéro d'exécution du workflow (1 en local), `versionName` dans `app/build.gradle.kts`.
  5. **Licence du code non choisie** : aucun fichier `LICENSE` n'est ajouté ; le choix revient à l'auteur (`docs/specs/release.md` §5). Les licences des bibliothèques embarquées sont listées dans l'écran « Aide et à propos », et un test (`ThirdPartyTest`) échoue si une bibliothèque entre dans le catalogue de versions sans y être déclarée.
  6. **Écran « Aide et à propos »** (Réglages) : prise en main, vie privée (ce qui quitte le téléphone, dont l'adresse IP publique révélée au STUN de Google tant que l'option est active), limites connues (dont celles du partage, `p2p-sharing.md` §8), crédits des données, licences.
- **Conséquences** : la clé de publication, ses mots de passe, la licence et le nom de paquet définitif sont des décisions et des manipulations de l'utilisateur (`release.md` §6). L'artefact de debug de la CI pèse moins (plus de x86 32 bits). Une traduction anglaise n'est pas faite : l'interface n'existe qu'en français.
- **Alternatives rejetées** : `splits.abi` avec APK universel (quatre à cinq fichiers par exécution de la CI, sans bénéfice avec l'AAB) ; R8 par défaut (pari sans téléphone) ; mot de passe de clé dans `gradle.properties` du dépôt (secret dans l'historique) ; choisir une licence à la place de l'auteur.

## ADR-028 — Carte : MapLibre en OpenGL ES (`android-sdk-opengl`) plutôt que la version Vulkan
- **Date / statut** : 2026-10-03 — Accepté. Remplace le choix implicite de `org.maplibre.gl:android-sdk` (ADR-002 / ADR-010 ne fixaient que la bibliothèque et sa version). **Pas encore vérifié sur téléphone.**
- **Contexte** : l'APK corrigé du plantage de `ToolMapApp.onCreate` plante toujours au lancement chez l'utilisateur, sans message. L'écran carte est le premier écran et `org.maplibre.gl:android-sdk` 13.x est la version **Vulkan seulement** : le manifeste de l'AAR demande Vulkan 1.0 (`android.hardware.vulkan.version` 0x400003), `RenderingEngine` refuse tout autre moteur, et un pilote Vulkan absent ou défaillant (fréquent sur les téléphones d'entrée et de milieu de gamme, et sur les versions d'Android plus anciennes) se termine par un plantage natif, qu'aucun code Java ne voit. Le test de démarrage (Robolectric, natifs neutralisés) passe de l'API 26 à l'API 35 : le côté Java/Kotlin du lancement n'est pas en cause.
- **Décision** : utiliser `org.maplibre.gl:android-sdk-opengl` à la même version (13.6.1). Même API Java (`MapView`, `MapLibre`, `HttpRequestUtil`…) : seule la ligne du catalogue de versions change. Le manifeste de cette AAR ne demande aucune fonctionnalité graphique particulière ; OpenGL ES est présent sur tous les téléphones Android.
- **Conséquences** : rendu identique attendu (mêmes styles, mêmes tuiles) ; pas de gain éventuel de Vulkan sur les téléphones récents. `ThirdParty` (préfixe `org.maplibre`) reste valable. Si le plantage persiste, le rapport de plantage de l'ADR-029 dira où.
- **Alternatives rejetées** : rester en Vulkan et attendre un journal de l'utilisateur (il n'en a pas) ; choisir le moteur au lancement selon le téléphone (deux bibliothèques natives de 8 à 11 Mo par architecture dans l'APK, et MapLibre ne permet pas de basculer dans une même version de l'AAR) ; revenir à une version 11.x de MapLibre (perte de corrections, sans garantie).

## ADR-029 — Rapport de plantage affiché sur le téléphone
- **Date / statut** : 2026-10-03 — Accepté.
- **Contexte** : l'utilisateur n'a pas accès à `logcat` ; un plantage au lancement ne laisse rien voir (« aucun message, rien ne s'affiche »), et deux allers-retours n'ont pas suffi à en trouver la cause. Pas de service de rapport en ligne : rien ne doit quitter le téléphone sans l'utilisateur (vie privée, ADR-027 §6).
- **Décision** :
  1. **Exception Java/Kotlin** : `CrashReporter` installe le gestionnaire d'exceptions non rattrapées **en premier** dans `ToolMapApp.attachBaseContext`. Il écrit le rapport (`files/crash/pending.txt` : version, commit `BuildConfig.GIT_SHA`, téléphone, Android/API, ABI, versions OpenGL ES et Vulkan, cause première, trace complète, fin du journal de l'application lu par `logcat -d`) puis ouvre `CrashActivity` et termine le processus.
  2. **Plantage natif** (signal dans MapLibre, WebRTC…) : aucun code ne tourne ; au démarrage suivant, `ActivityManager.getHistoricalProcessExitReasons` (Android 11+) donne la sortie `REASON_CRASH_NATIVE` et son *tombstone* (protobuf, dont on garde les chaînes lisibles : message d'abandon, bibliothèques, fonctions). Seules les sorties postérieures à l'installation de l'APK et à la vérification précédente sont retenues (pas de rapport d'une ancienne version, pas de double rapport).
  3. **`CrashActivity`** tourne dans son **propre processus** (`:crash`) et n'utilise ni Hilt, ni Compose, ni la carte (vues Android simples) : elle s'ouvre même si c'est cela qui a planté. Boutons Copier, Partager (texte), Relancer.
  4. Tant qu'un rapport n'a pas été vu, le démarrage suivant **saute l'initialisation de MapLibre** et `MainActivity` ouvre le rapport avant de construire la carte : un plantage au lancement ne peut pas cacher son propre rapport. Le rapport vu est gardé (`last.txt`) et le démarrage d'après est normal.
- **Conséquences** : le processus `:crash` crée aussi une `ToolMapApp` ; elle n'y fait rien (pas de gestionnaire, pas de restauration, pas de MapLibre ; le client HTTP injecté est `Lazy`). Avant Android 11, un plantage natif n'est pas rapporté (seules les exceptions Java le sont). La fin du journal peut contenir ce que l'application a écrit dans `logcat` : l'écran invite à relire le rapport avant de l'envoyer.
- **Alternatives rejetées** : bibliothèque ou service de rapport (ACRA, Crashlytics : envoi réseau, dépendance) ; rapport dans une activité du processus principal (meurt avec lui) ; demander à l'utilisateur d'installer `adb`.

## ADR-030 — Interface « Cartes et calques » : regroupement par fournisseur, vignettes par le circuit des tuiles, mise à jour forcée par session, inversion par la luminosité
- **Date / statut** : 2026-10-03 — Accepté. **Apparence pas encore vue sur téléphone.**
- **Contexte** : l'utilisateur veut l'arborescence et, si possible, le visuel du panneau « Maps & layers » d'AlpineQuest (P11, `docs/specs/maps-layers-ui.md`). Plusieurs de ses fonctions n'ont pas d'équivalent direct dans l'architecture existante.
- **Décision** :
  1. **Catalogue** : la logique (groupes, ordre, crédit) est dans `:core` (`MapCatalog`) et se teste sans Android. Le groupe d'une carte se déduit de son copyright et de sa source de données (« geopf.fr », « IGN », « OpenStreetMap »…), de son origine (importée, locale), pas d'un champ ajouté au format AQX : le format reste celui d'AlpineQuest (`docs/specs/aqx-format.md`).
  2. **Favoris = `Workspace`** : pas de nouveau modèle ni de nouveau fichier. Une carte favorite est un ensemble d'un seul calque. Cinq favoris sont fournis (`Workspaces.builtin`) ; `Workspace.matches` repère celui qui est à l'écran.
  3. **Vignettes par le circuit des tuiles** : `MapThumbnails` demande une tuile `https://tiles.toolmap.local/<id>/z/x/y` au client OkHttp de la carte. Le stockage, le mode hors ligne et la mise à jour forcée s'appliquent donc sans second chemin. Une tuile manquante est téléchargée et stockée comme une tuile affichée. Pas de cache disque propre (la tuile est déjà dans `MbTilesStore`) ; cache mémoire + nouvel essai après 60 s en cas d'échec.
  4. **Mise à jour forcée par session** : `ForcedRefresh` (dans `:data`) vit en mémoire, comme `OfflineMode`. `TileInterceptor` redemande une tuile stockée une fois par session et par tuile, et sert la tuile stockée si le serveur échoue. Non persisté : un réglage qui coûte des données ne doit pas survivre à un redémarrage sans que l'utilisateur le voie.
  5. **Couleurs inversées** : `LayerConfig.inverted` échange `brightness-min` et `brightness-max` du calque raster MapLibre (`RasterAdjust.brightnessRange`), seul moyen sans shader ; la matrice de couleurs de l'export PDF utilise la même fonction, donc la page imprimée a les couleurs de l'écran. L'ancien JSON sans ce champ se lit (valeur par défaut).
  6. **Estimation partagée** : `ZoneEstimator` sort du modèle de vue du formulaire de téléchargement pour servir aussi le mode « zone ». `ZoneQueue` (interface, implémentée par `DownloadScheduler`) permet de tester le modèle de vue sans WorkManager ; `DownloadScheduler` reçoit `WorkManager` en `dagger.Lazy`, de sorte que l'écran carte ne dépende pas de son initialisation.
  7. **Écrans** : « Cartes disponibles » est un écran de plus dans l'énumération `Screen` de `MainActivity` (pas de graphe de navigation, comme les autres) ; la page ouverte du panneau (`MapsPage`) est dans `MapsViewModel`, qui vit avec l'activité : revenir de l'écran des cartes redonne le panneau.
- **Conséquences** : l'ancienne feuille « Calques » (`LayersSheet`) et ses textes sont supprimés. Mode de fusion, gomme de couleur et comparaison de cartes restent à faire (P11.5, sans équivalent MapLibre). Les vignettes peuvent provoquer jusqu'à une vingtaine de requêtes de tuiles à l'ouverture du catalogue (hors ligne : aucune).
- **Alternatives rejetées** : champ « groupe » dans l'AQX (écart avec AlpineQuest) ; vignettes embarquées dans l'APK (poids, et les cartes ajoutées n'en auraient pas) ; mise à jour forcée persistante (surprise de consommation de données) ; inversion par un second calque de fusion (MapLibre n'en a pas) ; navigation Compose (non utilisée ailleurs).

## ADR-031 — Tuiles stockées par jeu de tuiles, cartes multi-zoom par références, zooms manquants composés par l'intercepteur
- **Date / statut** : 2026-10-03 — Accepté. **Remplace en partie ADR-003** : le stockage reste un fichier MBTiles, mais un fichier par **jeu de tuiles** et non plus par source. **Pas encore vérifié sur téléphone.**
- **Contexte** : l'utilisateur a fourni sept fichiers `.aqx` et décrit deux défauts d'AlpineQuest (P12, `docs/specs/multi-zoom.md`). (1) Une même carte (IGN Cartes, SCAN 25…) décrite par plusieurs fichiers est stockée et téléchargée une fois par fichier ; avec un fichier par source, ToolMap faisait de même. (2) Faire passer une carte à une autre selon le zoom (`USR.PERSONNAL_MULTIZOOM.AQX`) oblige à écrire l'AQX à la main, puis à tout retélécharger. Il demande aussi de ne jamais afficher une carte vide hors des zooms d'une source (paramètre à la `map.scales.default_overscaling_levels=3`) et de pouvoir rester au SCAN 25 quand IGN Cartes change de produit (« zoom forcé »), le tout réglable dans l'application.
- **Décision** :
  1. **Identité d'un jeu de tuiles** (`TileSetIdentity`, `:core`) : gabarits d'URL normalisés (paramètres substitués, clés retirées, schéma ignoré, hôte en minuscules, sous-domaine tournant `a`/`b`/`mt0`/`{$s}` remplacé par `*`, requête triée en minuscules) + taille des tuiles + CRS de `{$bbox}` quand il sert. Règles prudentes : un écart non prévu laisse deux jeux distincts (stockés deux fois, comme avant), jamais l'inverse.
  2. **Nom du stockage** (`StorageKeys`) : l'id de la carte fournie qui sert ce jeu (les fichiers existants restent valides), sinon `t-` + 16 caractères du SHA-1 de l'identité ; un paquet local garde son id. Une source dont les niveaux ont des serveurs différents a un stockage par niveau. `MbTilesStore` traduit `(source, zoom)` en stockage : le reste du code (intercepteur, téléchargement, impression, statistiques) passe par lui.
  3. **Migration** au démarrage (`StorageManager.migrateLegacyFiles`, fil de basse priorité) : un ancien fichier par carte importée est versé dans le stockage partagé sans écraser une tuile présente, puis supprimé. Idempotente.
  4. **Carte multi-zoom = source dont les niveaux référencent d'autres cartes** : serveur `toolmap-map:<id>` ou `toolmap-map:<id>?z=<n>` (zoom forcé) dans un `<zoom-levels>` ordinaire (`MapRef`). Pas de nouveau modèle ni de nouveau fichier : c'est un `.aqx` importé, qui s'affiche, se télécharge, s'imprime et se sauvegarde par les chemins existants. Une référence ne stocke rien ; références suivies sur 4 niveaux au plus.
  5. **Résolution** (`TileResolver`, `:core`) : une tuile affichée donne une recette — `Direct` (une tuile réelle), `Enlarge` (partie d'une tuile plus grossière), `Shrink` (4^k tuiles plus fines), `None`. Elle suit les références, les zooms forcés, les trous des listes `<zoom-values>` et la règle des zooms manquants `ScalingPolicy(levels)` : `levels` (0 à 4, défaut 3, réglage `AppSettings.maps.overscalingLevels`) niveaux de réduction au-dessous du premier zoom, 8 d'agrandissement au-delà du dernier (0 quand le réglage est à 0), 256 tuiles réelles au plus pour une tuile affichée.
  6. **Composition dans l'intercepteur** : `TileInterceptor` obtient chaque tuile réelle par le chemin habituel (stockage, réseau, mode hors ligne) et `BitmapTileComposer` (`:data`) dessine un PNG, **jamais stocké** (il se recompose à partir des tuiles stockées). MapLibre reçoit une plage de zoom élargie vers le bas (`StyleApplier.zoomRange`) pour demander ces tuiles ; au-delà du maximum, il agrandit lui-même (ADR-016). Le test d'une source dans l'assistant ne compose jamais (`ScalingPolicy.OFF`).
  7. **Téléchargement et impression** : un zoom affiché est traduit en zooms de tuiles réelles (`TileResolver.tileZooms`) ; le plan compte une tuile une fois par (stockage, zoom), d'abord pour le zoom qui la montre telle quelle.
  8. **Éditeur dans l'application** (Réglages › Cartes, et « Cartes disponibles ») : tranches de zooms, carte affichée par tranche, zoom forcé, problèmes bloquants ou informatifs calculés dans `:core` (`MultiZoomDraft`), création depuis un `.aqx` existant (`derivedFrom`). Carte fournie `ign-topo25k` (« IGN Topo 25k (zoom forcé) ») construite ainsi.
- **Conséquences** : importer un `.aqx` qui décrit une carte déjà présente ne retélécharge rien ; l'aperçu d'import signale les doublons (`SourceDuplicates`). L'écran Stockage montre une ligne par jeu de tuiles (« partagé par N cartes ») ; les chiffres par carte des « Cartes disponibles » comptent un jeu partagé pour chaque carte. Changer l'adresse d'une carte n'efface que les tuiles qu'aucune autre n'affiche. `toolmap-map:` est une extension ToolMap : une carte multi-zoom n'est pas lisible par AlpineQuest. Les tuiles composées sont demandées une à une (jusqu'à 64 pour 3 niveaux de réduction) : plus lentes au premier affichage en ligne.
- **Alternatives rejetées** : dédoublonner les fichiers MBTiles a posteriori (le téléchargement double a déjà eu lieu) ; une table de correspondance entre sources réglée à la main (l'utilisateur veut que ce soit automatique) ; empiler des calques avec des plages de zoom (ADR-016) pour faire une carte multi-zoom (pas de zoom forcé, ordre et téléchargement par calque, rien à partager ni à sauvegarder comme une carte) ; laisser MapLibre gérer seul les zooms manquants (il ne réduit pas au-dessous du minimum d'une source, et ne connaît ni les trous des `<zoom-values>` ni le zoom forcé) ; stocker les tuiles composées (doublon des tuiles réelles et invalidation à chaque changement de réglage).

## ADR-032 — Netteté des cartes : taille des tuiles déclarée à MapLibre selon la densité de l'écran, comme AlpineQuest
- **Date / statut** : 2026-10-03 — Accepté. **Remplace** la correspondance de zoom d'ADR-011 (« zoom affiché = caméra + 1 ») et la traduction numérique d'ADR-016 (`min − 1` / `max`), dont la sémantique est gardée (bornes incluses, en zoom affiché). **Pas encore vérifié sur téléphone.**
- **Contexte** : l'utilisateur compare ToolMap et AlpineQuest avec le même fichier (`USR.PERSONNAL_MULTIZOOM.AQX`) sur la même vue (Chambéry, ≈ 7 km de large) : ToolMap est flou à certains zooms. Captures : ToolMap « z 12.6 » (caméra 11,6) ; AlpineQuest « 1:109,600 14/18 ». MapLibre prend les tuiles raster du zoom `round(caméra + log2(512 / tileSize))` et dessine une tuile sur `tileSize` dp. Déclarées à leurs 256 px, il prenait le zoom 13, et un pixel de tuile couvrait 2,8125 × 2^−0,4 ≈ 2,1 pixels de l'écran (densité 2,8125 du samsung SM-S931U). AlpineQuest affiche le zoom 14 à ≈ 1 pixel d'écran par pixel de tuile (barre de 2 km ≈ 301 px sur 1 080 : 6,65 m par pixel ; tuile z14 à 45,6° N : 6,69 m par pixel). L'écart (log2 de la densité, 1,5 niveau ici) ne dépend pas du `.aqx`.
- **Décision** :
  1. `ZoomMapping` (`:core`) devient une valeur propre à l'écran : `tileScale = max(1, densité / grossissement)`. La taille déclarée à MapLibre pour des tuiles de 256 px est `round(256 / tileScale)` (91 sur le téléphone de l'utilisateur ; `round(taille / tileScale)` pour une autre taille, 512 → 182). Le décalage zoom affiché − zoom caméra vaut `log2(512 / taille déclarée)` (2,49 sur ce téléphone, 1 avant). Tout découle de l'entier déclaré : le zoom affiché est exactement celui des tuiles que MapLibre demande.
  2. Le zoom affiché (pastille « z », curseur des barres de zoom, zooms proposés pour enregistrer une zone, vignettes, assistant, plages des calques traduites en zoom caméra) passe par ce `ZoomMapping`. Il reste « le `z` des tuiles de 256 px à l'écran », comme le « 14/18 » d'AlpineQuest ; une plage « 8–16 » garde son sens.
  3. Réglage « Netteté des cartes » (Réglages › Cartes, `AppSettings.maps.tileMagnification`, pixels d'écran par pixel de tuile) : 1 « Nette, comme AlpineQuest » (défaut, y compris pour un fichier de réglages plus ancien), 1,5 et 2 (proposés s'ils sont inférieurs à la densité), 0 « Taille Android » (taille par défaut de MapLibre, rendu des versions précédentes). Valeur inutilisable → taille Android.
  4. La taille des tuiles d'une source MapLibre est fixée à sa création : elle entre dans `StyleApplier.signature`, et changer de netteté reconstruit les sources du style. La couverture stockée est déclarée de la même façon, pour montrer le zoom qui est à l'écran.
  5. Correction d'ADR-011 : pour une source raster, MapLibre arrondit au zoom le plus proche (`coveringZoomLevel`) ; il ne prend pas la partie entière.
- **Conséquences** : 4 à 8 fois plus de tuiles à l'écran sur un téléphone récent (≈ 40 à 60 au lieu de 10 à 15), donc plus de données en ligne et, pour une même vue, des zones proposées plus profondes : c'est le comportement d'AlpineQuest. Les textes dessinés dans les tuiles gardent leur taille en pixels, donc sont petits ; le réglage permet de les grossir. Pour une même vue, le zoom affiché augmente (z 12,6 → z 14,1) ; les plages de zoom enregistrées restent valables. Les zooms affichés au-dessous du décalage (0 à 2) ne sont plus atteignables (caméra ≥ 0) : sans effet pratique. La composition des zooms manquants (ADR-031) porte sur 4 à 8 fois plus de tuiles affichées. Rien ne change pour le PDF (P7), qui calcule son propre zoom.
- **Alternatives rejetées** : `pixelRatio` de la carte à 1 (tout ce que dessine MapLibre deviendrait minuscule : traces, position, symboles) ; tuiles « @2x » demandées aux serveurs (la plupart des `.aqx` n'en ont pas) ; un décalage fixe de +1 (juste pour un écran de densité 2 seulement) ; garder 256 px (c'est le flou constaté).

## ADR-033 — Partage de position par un serveur de coordination Firebase (relais chiffré)
- **Date / statut** : 2026-10-05 — Accepté (demande de l'utilisateur : « le P2P échoue ; un serveur Firebase fait la signalisation / le routage, avec mon compte Google, plan gratuit, tous les utilisateurs de l'app y sont invités »). **Remplace en partie ADR-004** : le mode direct WebRTC reste dans le code pour les groupes existants (`GroupRecord.server == null`) ; tout nouveau groupe passe par le serveur dès qu'un serveur est configuré.
- **Contexte** : la liaison WebRTC directe n'a jamais abouti sur le téléphone de l'utilisateur (cause non établie : CGNAT, SDP reconstruite ou déroulé des QR). Un serveur rend la connectivité indépendante des NAT.
- **Décision** : Firebase Realtime Database en **REST + SSE** (OkHttp, ni SDK ni Play Services), authentification **anonyme**. Le serveur est un **bus de diffusion de trames déjà scellées** (AES-GCM, clé de groupe inchangée) : il ne voit que du chiffré. Le serveur se transmet par l'invitation (`toolmap://join?d=`, version 2, 7 jours, sans code de réponse) ; il est réglable dans Partage › Serveur de coordination ou intégré à l'APK par `BuildConfig` (secrets CI). Les états des pairs (`PeerState`) et du serveur (`ServerState`) s'affichent par une icône et un mot. Spécification : `docs/specs/firebase-relay.md`.
- **Conséquences** : dépendance à un service tiers (disponibilité, quotas Spark : 100 connexions, 1 Go, 10 Go/mois). Les noms affichés sont en clair dans la présence. Les règles de sécurité ne connaissent pas l'appartenance au groupe ; le REKEY circule sur le bus partagé (un client modifié et retiré peut le relire, scellé avec l'ancienne clé). Quiconque possède une invitation non expirée peut entrer.
- **Alternatives rejetées** : SDK Firebase (Play Services, taille) ; Firestore (facturation par lecture) ; TURN auto-hébergé (serveur à gérer) ; signalisation seule avec liaison WebRTC ensuite (laissée en suite possible, P13.6).

## ADR-034 — Barre du bas et menu principal à la AlpineQuest
- **Date / statut** : 2026-10-10 — Accepté (demande de l'utilisateur : « reprends la même disposition des menus de AlpineQuest pour ToolMap », avec trois captures). **Remplace** le menu ⋮ déroulant en haut à droite, que `maps-layers-ui.md` §1 gardait « inchangé ». Les écrans ouverts par le menu ne changent pas. **Pas encore vérifié sur téléphone.**
- **Contexte** : AlpineQuest regroupe le pilotage de la carte en bas à gauche : une barre (menu, cartes, repères, position, boussole) et un panneau qui s'ouvre au-dessus, avec des sections repliables. ToolMap avait un ⋮ plat de dix-sept entrées en haut à droite, une boussole et un bouton « Cartes » épars en bas à droite.
- **Décision** :
  1. **Barre du bas** (`MapBottomBar`, `map/MainMenu.kt`) : logo → menu, cartes, traces et waypoints, position (couleur selon le mode), boussole. **Boutons + et −** (`MapController.zoomBy`), en bas à droite.
  2. **Menu principal** (`MainMenuPanel`) : panneau au-dessus de la barre, dans l'ordre d'AlpineQuest ; les commandes propres à ToolMap sont sous « Plus… », sur une page à part. Les options sont des cases qui ne ferment pas le menu : grille, mode nuit et coordonnées écrivent dans `SettingsStore` (`MapViewModel.updateSettings`) ; hors ligne et couverture gardent leur état de la carte.
  3. **Masquer et verrouiller** : deux états de `MapScreen` conservés par `rememberSaveable`. Le verrou coupe les gestes MapLibre (`MapController.setGesturesEnabled`) et se lève par un **appui long** sur la pastille (un simple appui se fait dans une poche).
  4. **Réglage** `AppSettings.showCoordinates` (vrai par défaut, lit un ancien fichier) : la boîte des coordonnées s'affiche ou non.
  5. **Pas de « Quitter »** : l'enregistrement, le partage et les alertes sont des services de premier plan dont l'arrêt a déjà ses commandes ; une sortie forcée couperait un enregistrement sans prévenir. **Pas de personnalisation** de la liste, de « Mon compte », d'« Élément personnalisé » (`spec §8`).
  6. Les icônes absentes de `material-icons-core` sont dessinées dans `MenuIcons`, sur le même principe que `MapsIcons`.
- **Conséquences** : une seule entrée pour les commandes, mais une partie est à deux niveaux (« Plus… ») : un utilisateur de l'ancien ⋮ retrouve sa commande une touche plus loin. Le verrou n'existe que dans l'écran carte (pas de verrou système). `MapViewModel` écrit désormais dans les réglages, comme l'écran Réglages.
- **Alternatives rejetées** : garder le ⋮ et ajouter seulement la barre (ce n'est plus « la même disposition ») ; une feuille modale (`ModalBottomSheet`) à la place du panneau (AlpineQuest affiche le panneau sur la carte, qui reste visible au-dessus) ; une liste personnalisable (crayons) : hors périmètre.

## ADR-035 — Boîtes d'information du haut à gauche à la AlpineQuest

- **Date / statut** : 2026-10-10 — Accepté (demande de l'utilisateur : « Met en place les mêmes détails en haut à gauche », avec la capture d'AlpineQuest). **Remplace** le point 4 de ADR-034 : le réglage `AppSettings.showCoordinates` ne cache plus seulement la boîte des coordonnées, mais toute la pile de boîtes du haut à gauche.
- **Contexte** : AlpineQuest empile en haut à gauche la position (MGRS et altitude du centre), l'échelle (ratio, zoom, barre), le GPS (satellites, précision, altitude, distance à la cible) et le baromètre (pression, altitude). ToolMap avait la ligne des coordonnées, une ligne d'altitude et une barre d'échelle en bas à gauche ; la précision GPS, la pression et les satellites utilisés n'étaient pas exposés.
- **Décision** :
  1. **Une pile, quatre boîtes** (`MapInfoBoxes`, `map/MapInfoBoxes.kt`) : position, échelle, GPS (si la localisation est active), baromètre (si le téléphone en a un et que la localisation est active). Spec §9.
  2. **Un seul réglage** pour la pile entière (`showCoordinates`, vrai par défaut). Pas de réglage séparé par boîte, pour garder le menu court.
  3. **L'échelle quitte le bas de l'écran** et vit dans la boîte du haut. Le ratio suit la convention d'AlpineQuest (dp ÷ 160 pouces × densité), et la barre prend la même échelle, pour que son étiquette et sa longueur soient cohérentes.
  4. **Les données manquantes sont ajoutées à la source existante** : `AltitudeStatus` gagne la précision et la pression ; les satellites utilisés viennent d'un nouveau `GnssSatellites` (`GnssStatus`, minSdk 26), écouté seulement pendant la localisation.
- **Conséquences** : le réglage « Information à l'écran » cache plus de choses qu'avant ; « Masquer les commandes » ne cache plus l'échelle. Le baromètre n'a de boîte que sur un téléphone qui en a un, et seulement quand la localisation écoute.
- **Alternatives rejetées** : garder l'échelle en bas et seulement ajouter la boîte du haut (deux échelles à l'écran) ; un réglage par boîte (plus de choses à régler, sans équivalent dans AlpineQuest) ; afficher l'altitude du centre de la carte (ToolMap n'a pas de relief sous le centre).

## ADR-036 — Réglages à la AlpineQuest : écran à sections, une icône par option, un moteur par réglage

- **Date / statut** : 2026-10-10 — Accepté (demande de l'utilisateur : « Crée tous les paramètres comme dans AlpineQuest. Rajoute une icône pour chaque option. Une fois le visuel terminé, code tous les moteurs associés aux boutons. »). Précise la partie « réglages » de ADR-034 et ADR-035 sans les remplacer.
- **Contexte** : AlpineQuest range ses réglages en sections, avec une icône devant chaque option. ToolMap n'avait qu'un écran court (quelques interrupteurs) ; plusieurs réglages affichés n'avaient aucun effet.
- **Décision** :
  1. **Un seul fichier de réglages** : `AppSettings` (JSON, `AppSettingsCodec`), découpé en groupes (`MapCanvasSettings`, `PlacemarkSettings`, `PositionSettings`, `RecorderSettings`, `AlertSettings`, `MapDisplaySettings`). Chaque choix est une `enum` ; chaque champ a une valeur par défaut. Un fichier plus ancien, plus récent ou abîmé donne des valeurs utilisables (`ignoreUnknownKeys`, `coerceInputValues`, `decodeOrDefault` ne lève jamais d'exception).
  2. **Les formats d'unités sont des fonctions pures** dans `:core` (`UnitFormats.kt` : cap en degrés, mils ou grades ; pente en pourcentage ou en degrés ; pression en hPa, mmHg ou inHg ; référence du nord, convergence UTM). L'interface lit `Formats` (`LocalFormats`) à chaque affichage : un réglage change l'affichage sans redémarrer.
  3. **Une icône par option** : `SettingsIcons.kt`, 59 icônes Material Design (licence Apache 2.0), rangées par section dans `SettingsScreen`.
  4. **Géoïde EGM96** : `EgmGeoid` lit le fichier `WW15MGH.DAC` (grille du NGA, domaine public) fourni dans les ressources. `AltitudeService` retire l'ondulation du géoïde à l'altitude GNSS (au-dessus de l'ellipsoïde) seulement si `positioning.egm96` est vrai et si le récepteur ne donne pas déjà l'altitude au-dessus du niveau de la mer.
  5. **Un moteur par réglage**, ou un écart écrit noir sur blanc : les écarts de P11.8 sont dans `PLAN.md` et dans le journal (stockage des cartes non déplacé ; couleur des waypoints donnée par leur symbole ; activité demandée au début d'un enregistrement, pas à chaque segment).
- **Conséquences** : `AppSettings` grossit. Ajouter un réglage = un champ avec sa valeur par défaut, son libellé et son moteur, plus un cas dans `AppSettingsTest` (aller-retour). Les anciennes versions ignorent les nouveaux champs.
- **Alternatives rejetées** : une préférence par clé (un fichier par réglage, pas de lecture atomique du groupe) ; un écran par onglet (AlpineQuest n'en a pas) ; corriger le géoïde par un service réseau (pas de réseau requis pour une correction d'altitude).

## ADR-037 — Panneau « Positionnement » à la AlpineQuest : le bouton position ouvre un menu, l'appui long garde le cycle

- **Date / statut** : 2026-10-11 — Accepté (demande de l'utilisateur : « Rajoute les même sous menus et les fonctionnalités », capture du menu POSITIONING d'AlpineQuest). Précise la ligne « Position » de ADR-034 (barre du bas) sans la remplacer.
- **Contexte** : le menu Positionnement d'AlpineQuest regroupe le mode explorateur, la position en temps réel, l'enregistreur de trace et le baromètre, puis des options repliables. ToolMap n'avait qu'un bouton qui cyclait entre trois modes, sans menu : la position ne pouvait pas être coupée (`locationOn` ne revenait jamais à faux), le mode explorateur n'existait que par le geste à deux doigts, et l'enregistreur comme le baromètre n'avaient pas de commande sur la carte.
- **Décision** :
  1. **Le bouton position ouvre le panneau** (un appui). Ses lignes et leur ordre sont ceux de la capture ; chaque ligne appelle le moteur existant (`MapController`, le dialogue de démarrage d'enregistrement, `AppSettings`), sans logique nouvelle de suivi, d'enregistrement ou d'altitude.
  2. **L'appui long garde l'ancien cycle** (libre → suivi → suivi avec boussole), pour qu'aucun mode ne soit perdu. Le cycle n'a pas d'autre accès.
  3. **La position peut être coupée** : `MapController.disableLocation` retire le repère et le suivi, et sort du mode explorateur. `applyLocationMode` remet `locationOn` à vrai, ce qui rend la coupure réversible.
  4. **Le baromètre est une coche sur `AltitudeMode`** : coché = mode différent de GPS (`barometerOn()`) ; coché depuis GPS, le mode passe à automatique ; décoché, il passe à GPS (`withBarometer`). La boîte baromètre (ADR-035) ne s'affiche que si la coche est mise.
  5. **Mode explorateur** : `enterExplorer` = suivi avec boussole et inclinaison de 60° ; `leaveExplorer` = suivi, à plat, nord en haut. La définition de ToolMap est gardée : l'aide AlpineQuest consultée ne définit pas le terme. Le geste à deux doigts garde ses 20°.
  6. **Options** : les cases reprennent les réglages existants de ADR-036 ; aucun réglage n'est ajouté.
  7. **Le « ? » du panneau ouvre « À propos »**, comme celui du menu principal : ToolMap n'a pas encore d'aide dédiée au positionnement.
- **Conséquences** : `MapBottomBar` reçoit `positionOpen`, `onPosition` et `onPositionLongPress` (au lieu de `onLocate`). `MenuRow` est partagé entre les deux panneaux. Le panneau suit les règles du menu principal : il se ferme par un appui hors de lui ou par Retour, et la confirmation de sortie ne s'ouvre pas tant qu'il est ouvert. Ajouter une ligne au panneau = un libellé et un appel à son moteur.
- **Alternatives rejetées** : garder le cycle au tap et ajouter un bouton « menu » à côté (barre plus large, deux commandes de position) ; démarrer l'enregistrement sans son dialogue (le nom et l'activité seraient perdus, ou demandés plus tard) ; ajouter un réglage « inclinaison de l'explorateur » (la valeur de 60° est une décision de ToolMap, à ajuster sur appareil si elle paraît trop forte).

## ADR-038 — Menus et réglages rangés par catégories repliables

- **Date / statut** : 2026-10-11 — Accepté (demande de l'utilisateur : « Le bouton de tout les settings est à côté le MGRS, pas du tout évident pour un utilisateur de faire la distinction » ; puis : faire l'inventaire, ranger les réglages par catégories, replier les lignes du menu complet). **Précise** ADR-034 (point 2 : « Plus… » reste une page, ses commandes sont rangées en trois sections repliables) et ADR-036 (les sections de Réglages deviennent des catégories repliables). **Retire** l'engrenage de la ligne « Grille » du menu principal. Aucun ADR n'est remplacé.
- **Contexte** : sur la ligne « Grille : MGRS », l'engrenage (ouvrir les réglages) et la case (afficher la grille) se touchaient : un utilisateur ne voyait pas la différence. L'écran Réglages montrait ses 50 réglages et ses liens d'un seul tenant, sous dix en-têtes : un écran très dense.
- **Décision** :
  1. **Une seule entrée vers les réglages** : « Réglages… » du menu principal. La ligne « Grille » ne porte plus qu'une case (`map/MainMenu.kt`).
  2. **Réglages en dix catégories repliables** (`settings/SettingsScreen.kt`, `SettingsCategory`) : un appui sur le titre ouvre ou referme la catégorie ; toutes sont fermées au départ ; l'état est gardé par `rememberSaveable`. Les 50 réglages, les 2 curseurs conditionnels, les 3 liens et la note sont repris à l'identique (mêmes moteurs, mêmes valeurs par défaut) ; seuls les regroupements changent (spec `maps-layers-ui.md` §11.2).
  3. **« Plus… » reste une page**, avec trois sections repliables ouvertes au départ : Traces, Outils, Cartes hors ligne (`MenuSection`, comme les sections du menu principal). Aucune commande n'est ajoutée, retirée ni déplacée vers une autre page.
  4. **Les doublons ne sont pas supprimés** : leur liste (spec §11.3) est donnée à l'utilisateur, qui décide.
- **Conséquences** : une ligne de moins dans le menu principal ; le réglage de la grille se trouve sous « Grille et informations » ; un réglage replié n'est visible qu'après un appui sur sa catégorie. Les tests d'interface (`SettingsCategoriesTest`, `MainMenuPanelTest`) vérifient les titres, le repliage et l'absence d'engrenage ; la lisibilité reste à vérifier sur téléphone.
- **Alternatives rejetées** : garder l'engrenage en le déplaçant (deux cibles toujours voisines) ; des catégories ouvertes au départ (l'écran reste long, ce que l'utilisateur veut éviter) ; un écran à onglets (ADR-036 l'a déjà écarté) ; supprimer les doublons sans demande.

## ADR-039 — Retrait des doublons : un réglage, une seule commande

- **Date / statut** : 2026-10-11 — Accepté (demande de l'utilisateur : « Retire les doublons », après l'inventaire de P11.10). **Remplace** en partie :
  - le point 2 de ADR-034 : le menu principal n'a plus de section « Options » (hors ligne, couverture, grille et mode nuit en sont retirés) ; la case « Informations en haut à gauche » reste ;
  - le point 4 de ADR-037 : le baromètre n'est plus une coche du panneau Positionnement ;
  - le point 6 de ADR-037 : le panneau Positionnement n'a plus les dix cases de réglages ;
  - le point 4 de ADR-038 : les retraits sont décidés ici ; seuls les deux parcours de zone restent à trancher (point 5).
  ADR-038 point 2 change aussi : Réglages a neuf catégories, « Aide » étant retirée. Le reste de ADR-034, ADR-037 et ADR-038 tient.
- **Contexte** : un même réglage pouvait être changé à deux endroits, avec le même effet (menu principal et Réglages, panneau Positionnement et Réglages, panneau Cartes et menu principal). Certaines commandes ouvraient un écran déjà accessible par la barre du bas ou par le panneau Cartes. Le menu en était plus long, et une valeur avait deux écrans.
- **Décision** :
  1. **Retirés du menu principal** : Mode hors ligne et Couverture (le panneau Cartes les garde, sur le même `vm`) ; Grille (Réglages, « Grille sur la carte », propose aussi « aucune ») ; Mode nuit (Réglages, Thème) ; Netteté des cartes… (Réglages, Résolution d'affichage de la carte). La section « Options » disparaît.
  2. **Retirés de « Plus… »** : Traces et waypoints (bouton de la barre du bas) ; Enregistrer une trace (case « Enregistreur de trace » du panneau Positionnement, même dialogue) ; Importer une carte, Téléchargements et Stockage hors ligne (panneau Cartes). Restent : Traces (dessiner une trace, dessiner un itinéraire, ajouter un waypoint ici), Outils (rechercher un lieu, mesurer, partage de position), Cartes hors ligne (télécharger la zone affichée, diagnostic des tuiles).
  3. **Retirés du panneau Positionnement** : la ligne Baromètre, qui écrit le même champ `AppSettings.altitude` que le réglage Altitude mais ne sait pas choisir le baromètre seul (elle passe du GPS à « automatique ») ; la section Options et ses dix cases, qui sont dans Réglages (Position et cap, Carte, Enregistreur de trace).
  4. **Retiré de Réglages** : la catégorie « Aide », dont le seul lien ouvrait « Aide et à propos… ». « À propos » reste ouvert par le « ? » du menu principal et par celui du panneau Positionnement.
  5. **Gardés, parce qu'ils n'ont pas de réglage équivalent ou ne désignent pas la même chose** : « Masquer les commandes » et « Verrouiller l'écran » (actions de la carte) ; « Informations en haut à gauche » (aucun réglage ne montre ou ne cache la pile) ; « Mode explorateur » du panneau (une commande) et « Mode explorateur automatique » de Réglages (un réglage) ; « Emplacement de stockage » de Réglages (où sont les fichiers). **À trancher par l'utilisateur** : « Télécharger la zone affichée… » (Plus…) et « Sélectionner et enregistrer une zone » (panneau Cartes) sont deux parcours différents, gardés tous les deux.
  6. **Retirés du code** : `SharpnessDialog` ; les icônes `MenuIcons.Smartphone`, `Record`, `Unfold`, `Import` et `Storage` ; l'icône « Aide » de `SettingsIcons` ; le type `MenuToggle`, remplacé par un rappel `onCoordinates` ; les paramètres `settings`, `onUpdate` et `onBarometer` du panneau Positionnement ; treize chaînes sans usage.
- **Conséquences** : le menu principal a deux sections (Espace de travail, Information à l'écran) ; « Plus… » en a trois, avec huit commandes ; le panneau Positionnement a trois lignes et le « ? » ; Réglages a neuf catégories. Un réglage n'a plus qu'une commande : pour la grille, le thème, la netteté, l'altitude ou les options de position, il faut aller dans « Réglages… ». Les tests `MainMenuPanelTest` et `SettingsCategoriesTest` vérifient les absences et le nombre de catégories ; la lisibilité reste à voir sur téléphone.
- **Alternatives rejetées** : garder les doublons, comme ADR-038 le prévoyait (c'est la demande qui change ce point) ; garder les cases et retirer seulement les réglages (deux écritures possibles pour une même valeur, sans gain) ; retirer aussi les commandes de la barre du bas (la barre est l'accès principal aux traces et à la position) ; retirer « Télécharger la zone affichée » ou « Sélectionner et enregistrer une zone » sans trancher (deux flux différents).

## ADR-040 — Numéro de version : `0.MINEUR.CORRECTIF`, une incrémentation par livraison

- **Date / statut** : 2026-10-11 — Accepté (demande de l'utilisateur : « Le nom de la release est toujours en 0.1.0, fait évoluer les versions lorsqu'il y a de l'évolution »). **Précise** le point 4 de ADR-027 (le `versionName` est dans `app/build.gradle.kts`) sans le remplacer : le `versionCode` n'est pas touché. Aucun ADR n'est remplacé.
- **Contexte** : le `versionName` valait `0.1.0` depuis le premier APK. Pendant ce temps, le menu et les réglages ont changé (P11.6 à P11.11) : l'APK s'appelait toujours `ToolMap-debug-0.1.0.apk`, et la pré-version `debug-<sha7>` ne portait aucune version. Sur le téléphone, rien ne disait quelle version était testée, et aucune règle ne disait quand le numéro devait changer.
- **Décision** :
  1. **Forme** : tant que l'application n'est pas publiée, `0.MINEUR.CORRECTIF`. Une livraison qui ajoute une fonction ou change l'interface augmente le MINEUR et remet le CORRECTIF à 0 ; une livraison qui ne corrige que des défauts augmente le CORRECTIF.
  2. **Une incrémentation par livraison** : une livraison est un ensemble de changements de l'application que l'utilisateur va essayer, en général une session de travail. Le numéro est augmenté une fois, dans le commit qui livre le changement. Les commits intermédiaires d'une même livraison, les tests, la documentation et la CI ne le changent pas.
  3. **Où il apparaît** : dans le nom de l'APK (`ToolMap-<phase>-<version>.apk`), dans le titre de la pré-version de debug (`ToolMap debug <version> (<sha7>)`), dans « Aide et à propos » et dans l'en-tête du menu. Ces deux écrans le lisent à l'exécution (`PackageInfo`, `BuildConfig.VERSION_NAME`) : rien d'autre à changer. Le journal indique le numéro livré dans chaque entrée.
  4. **Premier passage** : `0.1.0` → `0.2.0`, pour les livraisons de P11.10 et P11.11 (catégories de réglages, retraits de doublons : un changement visible, donc un MINEUR).
  5. **`1.0.0`** : décision de l'utilisateur, quand l'application aura été essayée sur un téléphone et qu'une publication est prévue (clé de publication, licence : ADR-027).
  6. **`versionCode` inchangé** : numéro d'exécution du workflow de release (ADR-027, point 4), 1 en local et dans la CI de debug. Android accepte une mise à jour à `versionCode` égal : les APK de debug successifs s'installent l'un par-dessus l'autre, quel que soit leur nom.
- **Conséquences** : les anciens noms (`0.1.0`) restent dans le journal et sur les pré-versions déjà publiées. Les `0.1.0` des tests (`appVersion = "0.1.0"`, `CrashReportTest`) sont des données d'exemple : ils ne suivent pas la version. Aucun test ne lit la version de l'application.
- **Alternatives rejetées** : `versionCode` dérivé du nom (`majeur × 10000 + mineur × 100 + correctif`) : cela changerait le schéma de ADR-027 sans besoin, tant que les APK de debug s'installent par-dessus ; un numéro de build seul (`0.1.0-build52`) : le nom visible ne dit toujours pas quelle version est testée ; une date (`2026.10.11`) : elle ne dit pas l'importance du changement ; une incrémentation à chaque push : les corrections de CI et de tests produiraient des numéros sans sens.

## ADR-041 — Index de l'interface comme référence unique des menus ; specs remplacées retirées

- **Date / statut** : 2026-10-12 — Accepté pour la documentation (demande de l'utilisateur : « Crée un index des boutons, de la navigation et des fonctions […] Supprime tous les markdown obsolètes parce que finis. Remplace-les par de nouveaux markdown pour la restructuration / performance »). Les changements d'interface et de code qu'il prépare (P14, P15) restent **proposés** : chacun aura son ADR à sa réalisation. Aucun ADR n'est remplacé ; ADR-030 et ADR-034 à ADR-039 renvoient à `maps-layers-ui.md`, désormais lisible dans l'historique git.
- **Contexte** : l'interface reprend l'organisation d'AlpineQuest (P11) ; la lecture du code montre des boutons sans logique complète : la touche Retour quitte l'application depuis onze écrans, un réglage enregistré sans effet, des favoris fournis impossibles à supprimer, deux parcours pour une même action. L'arborescence vivait dans une spec de chantier (`maps-layers-ui.md`, 11 sections ajoutées session après session), sans statut par bouton.
- **Décision** :
  1. `docs/specs/ui-index.md` est la **référence unique** de l'arborescence : chaque écran, bouton, geste et réglage y a une ligne avec son moteur et un statut (✅ ⚠️ ❌ 🕳️ 🐞). Toute session qui ajoute, déplace ou retire un bouton met l'index à jour dans le même commit.
  2. `docs/specs/ui-reorganization.md` porte les propositions (lots 1 à 4) et les décisions attendues de l'utilisateur ; `docs/specs/performance.md` porte la performance, la batterie et la restructuration légère.
  3. Retirés : `maps-layers-ui.md` (remplacé par l'index) et `battery-and-performance.md` (repris dans `performance.md`). **Gardés**, parce qu'ils décrivent un format ou un protocole encore lu par le code ou par l'utilisateur : `aqx-format.md`, `backup-format.md`, `p2p-sharing.md` et `print-pdf.md` (cités dans des commentaires du code), `firebase-relay.md` (guide de la console, P13.5 ⛔), `multi-zoom.md` (P12.3 en cours), `release.md` (protocole de CLAUDE.md). Le journal n'est jamais réduit (CLAUDE.md, point 5).
  4. Règle de la réorganisation : réparer, puis retirer le factice, puis déplacer, puis ajouter ; la barre du bas et l'ordre du menu principal d'AlpineQuest ne changent pas.
- **Conséquences** : une seule lecture suffit pour savoir si un bouton fonctionne. Les anciennes entrées du journal et les ADR citent des sections de `maps-layers-ui.md` qui n'existent plus dans l'arbre de travail : elles restent exactes pour le commit où elles ont été écrites.
- **Alternatives rejetées** : garder `maps-layers-ui.md` et y ajouter une section 12 (le fichier mélange déjà six chantiers) ; supprimer toutes les specs des phases terminées (le code cite `print-pdf.md` et `p2p-sharing.md`, et les formats AQX et de sauvegarde restent des contrats) ; résumer ou couper le journal (interdit par le protocole).

## ADR-042 — Emplacement des cartes hors ligne : un déplacement copié et vérifié, pas un réglage

- **Date / statut** : 2026-10-12 — Accepté (demande de l'utilisateur : « On le code » pour S1, `ui-reorganization.md` §6, question 3). **Remplace** le réglage `AppSettings.storage` (défaut `INTERNAL`), enregistré mais jamais appliqué (`ui-index.md`, S1). Aucun ADR n'est remplacé.
- **Contexte** : les cartes hors ligne sont des fichiers `.mbtiles` (un par jeu de tuiles, phase P2) dans le stockage interne du téléphone. Elles peuvent peser plusieurs gigaoctets, et une carte SD amovible est l'endroit naturel pour les garder. Le réglage « Emplacement de stockage » existait sans effet, ce qui contredit la règle 3 de `ui-reorganization.md`. Une carte SD retirée est cachée par Android : son contenu disparaît sans erreur évidente.
- **Décision** :
  1. **Pas de réglage, un déplacement** : le choix de Réglages ▸ Données déplace les cartes, après confirmation (taille des cartes, destination). Le champ `storage` disparaît d'`AppSettings` ; le codec ignore les champs inconnus, donc les anciens fichiers se lisent. La seule source de vérité est le fichier pointeur `tiles-location.txt`, qui indique le dossier en service (`TileLocation`).
  2. **Copie, vérification, puis bascule** : chaque fichier `.mbtiles` est copié avec ses `-wal` et `-shm` dans le nouveau dossier, et la taille de chaque copie est contrôlée. Une copie qui échoue supprime ses propres fichiers et laisse l'ancien dossier en service. Une fois la copie vérifiée, le pointeur est écrit (fichier temporaire renommé), puis les fichiers d'origine sont supprimés.
  3. **Téléchargements arrêtés avant** : WorkManager annule les travaux de téléchargement et attend qu'il n'en reste aucun en file ou en cours (60 × 500 ms, sinon échec du déplacement).
  4. **Déplacement au démarrage, dans un processus à part** : la demande est écrite (`tiles-move.txt`), le processus est arrêté, et `AlarmManager` relance `MainActivity`. Celle-ci ouvre `StorageMoveActivity`, qui tourne dans le processus `:storage` et n'ouvre aucun fichier de tuiles. `ToolMapApp` ne fait rien d'autre tant que la demande est en attente : aucune base ni aucun fichier de tuiles n'est ouvert pendant la copie.
  5. **Carte absente = carte indisponible** : un dossier sur une carte amovible n'est utilisé que si la carte est montée (`Environment.getExternalStorageState` = `MEDIA_MOUNTED`) ou si le dossier existe déjà. Sinon, l'intercepteur de tuiles ne lit ni n'écrit (`MbTilesStore`), un téléchargement est refusé, une sauvegarde qui inclut les cartes est refusée (`BackupFailure.TILES_UNAVAILABLE`), et une restauration des cartes est refusée avant toute écriture. Aucun dossier n'est créé sous le point de montage.
  6. **Dossier propre à l'application** : les cartes sur la carte SD vont dans le dossier `tiles` du dossier externe de l'application (`ContextCompat.getExternalFilesDirs`), qui ne demande aucune permission de stockage.
- **Conséquences** :
  - Le chemin des cartes est lu à un seul endroit (`TileLocation`) par l'intercepteur, les sauvegardes, les téléchargements et l'écran Réglages.
  - Désinstaller l'application efface aussi les cartes stockées sur la carte SD. La confirmation du déplacement ne le dit pas encore : à ajouter.
  - Limites connues : une carte FAT32 refuse un fichier de plus de 4 Go, la copie échoue alors et les originaux restent ; l'écran Réglages ne se rafraîchit pas si la carte est montée pendant qu'il est ouvert ; le mode WAL sur stockage externe n'est pas encore testé sur téléphone ; une restauration des cartes qui sont sur la carte SD copie l'ancien dossier dans le stockage interne (place libre nécessaire, non corrigé) ; si Android bloque le lancement par `AlarmManager`, rouvrir l'application lance le déplacement.
- **Alternatives rejetées** :
  - **Cacher le réglage** (proposition de `ui-reorganization.md`, S1) : écartée à la demande de l'utilisateur, qui veut la carte SD.
  - **Changer seulement le chemin, sans copier** : les fichiers ne suivraient pas, et l'application ne trouverait plus ses cartes.
  - **Accès par `ACTION_OPEN_DOCUMENT_TREE` (SAF)** : SQLite ouvre des chemins de fichiers, un document SAF n'en a pas ; la base devrait être recopiée à chaque ouverture.
  - **Copie pendant l'utilisation** : les fichiers sont ouverts par le processus ; une bascule à chaud peut laisser une base ou un journal WAL à moitié déplacé. Le redémarrage est plus simple et plus sûr.
  - **Chemin conservé dans `AppSettings`** : deux sources (le réglage et les fichiers) peuvent se contredire après un échec.

## ADR-043 — Panneau du drapeau et menu du point central, comme AlpineQuest

- **Date / statut** : 2026-10-12 — Accepté (demande de l'utilisateur : rendu du panneau du drapeau comparé à sa capture AlpineQuest, puis « un petit cercle blanc se trouve au milieu de l'écran, ce point est cliquable avec un menu rapide » ; réponses 1 et 4 de `ui-reorganization.md` §6). **Remplace** la proposition F1 de `ui-reorganization.md` (menu par appui long, réglage « Appui long » à la valeur « Menu »). Le réglage « Appui long » ne change pas. Aucun ADR n'est remplacé.
- **Contexte** : le drapeau ouvrait l'écran Traces, et les commandes de création (Dessiner une trace, Ajouter un waypoint ici) étaient dans « Plus… » (R1). AlpineQuest ouvre depuis son bouton des repères un panneau au-dessus de la barre, et affiche au centre de la carte un petit cercle blanc qui ouvre un menu rapide du point.
- **Décision** :
  1. **Le drapeau ouvre un panneau** : `TracksMenuPanel` (`map/TracksMenu.kt`), au-dessus de la barre, sur le modèle du panneau Positionnement. Lignes : Explorer les traces et waypoints (l'écran Traces, inchangé), Ajouter un waypoint ici, Dessiner une trace, Dessiner un itinéraire, Rechercher un lieu…. Chaque ligne passe par `MenuAction` et `runMenuAction` : aucun nouveau moteur.
  2. **Conditions des commandes** : « Dessiner » et « Ajouter un waypoint ici » sont grisés pendant un tracé, comme dans l'ancien menu. Les panneaux s'excluent entre eux (le voile recouvre la barre), et Retour ferme le panneau.
  3. **Le point central** : un cercle blanc de 20 dp, dans une cible de 48 dp, au centre de la carte (`CenterPoint`). Un appui ouvre au-dessus du point le menu rapide (`CenterQuickMenu`) : Définir comme cible · Créer un waypoint… · Mesurer depuis ici · Coordonnées (copier, avec une boîte « Coordonnées du centre ») · Partager ce point. Les commandes appellent les moteurs existants (cible, waypoint, mesure, presse-papiers, partage).
  4. **Désactivé pendant un outil** : le point ne réagit pas pendant le dessin, la mesure, l'impression, la définition d'une zone et quand l'écran est verrouillé (`pointMenuAllowed` dans `MapScreen`). Il reste visible, avec le libellé « Point central de la carte : menu rapide » pour les lecteurs d'écran.
  5. **L'appui long ne bouge pas** : il garde son réglage (G1). Le menu du point ne dépend d'aucun geste caché.
  6. **Pas de bouton de plus** dans la barre du bas (règle 4 de `ui-reorganization.md`).
- **Conséquences** :
  - « Plus… » ne contient plus de commandes de tracé ni de recherche (`MainMenuPanelTest`).
  - Le drapeau n'ouvre plus directement l'écran Traces : la première ligne du panneau y mène.
  - Écarts volontaires avec AlpineQuest : « Télécharger autour » et « Aller vers » ne sont pas repris (« Définir comme cible » tient la place d'« Aller vers »). « Importer un fichier… » et « Afficher tout / Masquer tout » ne sont pas encore dans le panneau.
  - Le point occupe une cible de 48 dp au centre de la carte : à voir sur téléphone (gêne possible pour viser un repère précis).
  - Tests : `TracksMenuPanelTest`, `CenterPointTest`, `MainMenuPanelTest`, et un rendu PNG (`TracksMenuRenderTest`, gardé par `-Ptoolmap.renders`) pour la comparaison visuelle.
- **Alternatives rejetées** :
  - **Appui long par défaut** (proposition F1 initiale) : le geste ne se découvre pas, et il est déjà réglé pour une autre action (G1). L'utilisateur a demandé un point visible.
  - **Un bouton de plus dans la barre** : la barre garde ses cinq boutons (règle 1 de `ui-reorganization.md`).
  - **Menu ancré au doigt** : le centre de la carte est toujours le point visé ; un menu ancré au doigt déplacerait la cible.
  - **Commandes ajoutées à l'écran Traces** : elles seraient loin du point visé sur la carte.

## ADR-044 — Ouvrir un point avec une autre application : une application par défaut, Waze par son propre lien

- **Date / statut** : 2026-10-12 — Accepté (demande de l'utilisateur : une icône « Ouvrir avec » dans le menu rapide du point central, une application par défaut paramétrable, et l'envoi d'une position à Waze pour obtenir l'itinéraire jusqu'à ce point, comme dans AlpineQuest). **Précise** ADR-043 (le menu rapide) sans le remplacer. Aucun ADR n'est remplacé.
- **Contexte** : AlpineQuest envoie une position à Waze, qui calcule le chemin jusqu'à ce point. Le sélecteur d'applications d'Android ne propose que les applications qui ouvrent les liens `geo:` ; sur le téléphone de l'utilisateur, Waze n'en fait pas partie, et il n'accepte que son propre lien. Depuis Android 11, une application installée n'est visible que si elle est déclarée dans le manifeste.
- **Décision** :
  1. **Liens** (`PointLinks`, dans `:core`, testé) : pour Waze, son lien public `https://waze.com/ul?ll=<lat>,<lon>&navigate=yes`, qui démarre le guidage, avec le paquet `com.waze` fixé dans l'intention. Pour les autres applications, et pour le choix du système, le lien générique `geo:<lat>,<lon>?q=<lat>,<lon>`. Les coordonnées s'écrivent avec un point décimal, quelle que soit la langue du téléphone.
  2. **Application par défaut** : le réglage `pointApp` (nom de paquet ; `null` = demander à chaque fois) est dans les Réglages, section Carte, ligne « Ouvrir les positions avec ». La liste propose « Demander à chaque fois », puis les applications qui ouvrent `geo:`, et Waze s'il est installé.
  3. **Bouton** : icône « Ouvrir avec » (une flèche qui sort d'un cadre), troisième icône de la rangée du menu rapide. Avec un défaut installé et capable d'ouvrir la position, l'application s'ouvre directement. Sinon, le sélecteur du système, avec Waze en tête s'il est installé. Aucune application : un message.
  4. **Choix non mémorisé** : le sélecteur n'enregistre rien. Le défaut ne se règle que dans les Réglages, et l'appui n'ouvre jamais une application que l'utilisateur n'a pas choisie.
  5. **Visibilité des applications** : le manifeste déclare dans `<queries>` le schéma `geo` et le paquet `com.waze`.
- **Conséquences** :
  - Le lien Waze suit la forme publique de Waze, celle que Google documente pour Waze. Il ne se vérifie que sur un téléphone où Waze est installé.
  - Le point n'a pas de nom dans le lien : il est désigné par ses coordonnées. La navigation Google Maps (`google.navigation:`) n'est pas ajoutée ; elle s'ajouterait comme Waze.
  - Les trois icônes de la rangée se partagent la largeur du menu ; un libellé long passe sur deux lignes.
- **Alternatives rejetées** :
  - **Le seul sélecteur du système** : Waze n'y figure pas sur ce téléphone, et le choix ne se répète pas.
  - **Mémoriser le choix du sélecteur** : le défaut serait caché ; il faudrait le défaire dans les réglages d'Android.
  - **Lien `waze://`** : il figure dans l'ancienne documentation de Waze, sans vérification sur les versions actuelles ; le lien `https` est celui de la documentation de Google.

## ADR-045 — Téléversement des artefacts CI non bloquant : la pré-version est la livraison de l'APK

- **Date / statut** : 2026-10-12 — Accepté (demande de l'utilisateur : rendre le téléversement des artefacts non bloquant). **Complète** ADR-027 (publication) sans le remplacer. Aucun ADR n'est remplacé.
- **Contexte** : le compte n'a que 0,5 Go de stockage d'artefacts par mois, partagé entre tous ses dépôts (`docs/specs/release.md` §1). Le téléversement de `toolmap-debug-apk` a été refusé (« Artifact storage quota has been hit ») pour les runs 38229042755, 38211724975 et 38251268017, alors que la compilation, les tests et le lint de ces runs réussissaient : le run passait au rouge pour une cause hors du code. La pré-version `debug-<sha7>`, qui ne compte pas dans le quota, était publiée à chaque fois.
- **Décision** :
  1. Les étapes « Upload debug APK » et « Upload reports » de `ci.yml` passent en `continue-on-error: true`. Un refus du quota ne fait plus échouer le run.
  2. La livraison de l'APK est la pré-version `debug-<sha7>` ; l'artefact n'est qu'un complément, quand le quota le permet.
  3. La compilation, les tests, le lint et `assembleDebug` restent bloquants. La publication de la pré-version et la suppression des anciens artefacts étaient déjà non bloquantes.
- **Conséquences** :
  - Un run peut être vert sans artefact. Son journal donne la cause, et le lien à donner à l'utilisateur est celui de la pré-version.
  - Une panne qui touche à la fois le téléversement et la pré-version laisse un run vert sans APK. C'est la limite acceptée ; le journal de l'étape le montre. Si cela arrive, une étape de garde qui échoue dans ce seul cas est à ajouter.
  - Le numéro de version ne change pas : une modification de CI ne livre pas d'APK (ADR-040, `release.md` §1).
  - Une seule pré-version est gardée : le lien d'un commit disparaît au push suivant.
- **Alternatives rejetées** :
  - **Garder le run rouge** : il signale un échec qui n'est pas dans le code, pousse à relancer sans effet et fait échouer le run pour rien.
  - **Supprimer le téléversement** : il perdrait l'artefact quand le quota le permet.
  - **Étape de garde dès maintenant** : un re-run du même commit ne peut pas recréer la pré-version (son étiquette existe déjà) ; avec le quota épuisé, la garde échouerait à tort. À reconsidérer si la limite gêne.

## ADR-046 — Garde de livraison : un push doit laisser un APK, artefact ou pré-version

- **Date / statut** : 2026-10-12 — Accepté (demande de l'utilisateur : « Ajoute une étape de garde »). **Complète** ADR-045 et reprend son alternative « étape de garde dès maintenant », en changeant la condition de réussite. Aucun ADR n'est remplacé.
- **Contexte** : ADR-045 accepte qu'une panne touchant à la fois le téléversement de l'artefact et la pré-version laisse un run vert sans APK, et prévoit une garde pour ce seul cas. ADR-045 écartait la garde pour une raison précise : sur un re-run du même commit, `gh release create` échoue (l'étiquette `debug-<sha7>` existe déjà) ; une garde fondée sur l'issue de l'étape « Publish the debug APK » échouerait à tort quand le quota refuse aussi l'artefact.
- **Décision** :
  1. Une étape finale, « Check that the APK was delivered », tourne sur un push et seulement si les étapes bloquantes précédentes ont réussi (`if: success() && github.event_name == 'push'`). Les étapes « Publish the debug APK » et « Upload debug APK » reçoivent un identifiant (`publish-apk`, `upload-apk`).
  2. Elle **passe** si l'artefact `toolmap-debug-apk` a été téléversé (issue `success` de l'étape d'envoi), ou si la pré-version `debug-<sha7>` porte un fichier `ToolMap-debug-*.apk`, vérifié par `gh release view` et non par l'issue de l'étape « Publish ». Le second cas couvre le re-run, où la pré-version existe déjà.
  3. Elle **échoue** si aucun des deux n'existe, et son message (`::error::`) donne l'issue de chacune des deux étapes.
  4. Les pull requests sont exclues : elles n'ont pas de pré-version.
- **Conséquences** :
  - Un push qui ne livre aucun APK passe au rouge, et le message dit où regarder.
  - Avec le quota épuisé (état actuel), la garde s'appuie sur la pré-version : le run reste vert tant qu'elle est publiée. Il ne passe au rouge que si la pré-version échoue aussi.
  - La garde vérifie la présence d'un fichier `.apk` dans la pré-version, pas son intégrité : une pré-version tronquée passerait. Une comparaison au SHA-256 de l'APK compilé reste possible si cela arrive.
  - Aucune nouvelle permission : `gh` utilise le jeton du run, déjà en `contents: write`.
- **Alternatives rejetées** :
  - **Garde fondée sur l'issue de « Publish »** : échoue à tort au re-run (voir Contexte).
  - **Garde fondée sur l'artefact seul** : échouerait à chaque push tant que le quota est épuisé, alors que l'APK est livré par la pré-version.
  - **Job séparé « livraison »** : une étape suffit, et un job de plus ajoute de la configuration sans changer le résultat.
  - **Retirer `continue-on-error` de l'envoi** : retour au rouge pour une cause hors du code (ADR-045).

## ADR-047 — Signature des APK distribués : la clé de publication, et non la clé de debug publique

- **Date / statut** : 2026-10-12 — Accepté (demande de l'utilisateur : nettoyer les anciennes APK sans clé de signature, et ranger la clé de signature dans les secrets). **Remplace** le point 3 d'ADR-009 pour les APK distribués et **précise** ADR-027 : la clé de debug fixe reste le repli, la CI signe désormais avec la clé de publication dès que ses secrets existent.
- **Contexte** : la clé de debug (ADR-009) est versionnée et son mot de passe est `android` : une fois le dépôt public, n'importe qui peut signer un APK du paquet `fr.toolmap` avec elle. Un tel APK s'installe par-dessus une installation de ToolMap signée par cette clé (même signature = mise à jour), donc sans désinstallation et avec accès aux données de l'application. Jusqu'ici, la CI livrait ses APK signés par cette clé publique ; la clé de publication (ADR-027) ne servait qu'au workflow `release.yml`.
- **Décision** :
  1. La clé de publication (secrets `TOOLMAP_KEYSTORE_BASE64`, `TOOLMAP_KEYSTORE_PASSWORD`, `TOOLMAP_KEY_ALIAS`, `TOOLMAP_KEY_PASSWORD`) signe aussi l'APK de debug de la CI : `app/build.gradle.kts` donne à la configuration `debug` la configuration `release` quand elle existe, et `ci.yml` décode la clé dans un fichier temporaire, comme `release.yml`.
  2. Sans ce secret, la CI signe avec la clé de debug publique et le dit : avertissement dans le journal de l'étape, phrase dans les notes de la pré-version. C'est un repli transitoire, pas un état visé.
  3. L'étape « Show the signer of the debug APK » affiche l'empreinte SHA-256 du certificat, qui permet de voir quelle clé a signé l'APK (publique, pas un secret).
  4. L'étape « Delete old artifacts » supprime tous les `toolmap-debug-apk` avant de téléverser celui du run : il n'en reste qu'un. L'ancien code en gardait deux (celui du run précédent et celui du run en cours), en contradiction avec `release.md` §1.
- **Conséquences** :
  - Le jour où les secrets existent, la première APK de la CI signée par la clé de publication ne s'installe pas par-dessus une installation précédente : il faut désinstaller ToolMap une fois (données locales perdues, sauf sauvegarde P9.6). Les téléphones de test sont concernés.
  - Un secret mal formé (base64 invalide, mot de passe faux) fait échouer le build de la CI : rouge, plutôt qu'une signature avec la mauvaise clé ou une signature publique en silence.
  - La clé de publication n'est créée ni dans une session cloud ni dans un chat : elle l'est sur le poste de l'utilisateur (`release.md` §2). Une clé affichée dans un chat ne doit plus servir à publier.
  - La clé de debug publique reste dans l'historique git (commit `0ae8c79`) : l'en retirer demande une réécriture de l'historique, décision séparée qui exige un accord explicite.
- **Alternatives rejetées** :
  - **Une clé de CI distincte de la clé de publication** : une clé de plus à créer, sauvegarder et tenir à jour, pour la même désinstallation unique. À reconsidérer si une publication sur un magasin demande une clé dédiée.
  - **Supprimer `app/debug.keystore` tout de suite** : la CI ne pourrait plus signer tant que le secret n'existe pas.
  - **Garder la clé publique pour les APK de test** : laisse la faille ouverte dès que le dépôt est public.

## ADR-048 — Apparence d'un membre : icône et couleur libre sur 4 octets (protocole 2)

- **Date / statut** : 2026-10-14 — Accepté (demande de l'utilisateur : choisir une icône et sa couleur sur un cercle chromatique, c'est ce que les autres voient sur leur carte).
- **Contexte** : la couleur d'un membre voyageait comme un octet (index de palette) dans le code d'appairage, `HELLO` et `PEERS`, alors que `ShareController` y mettait déjà la couleur ARGB entière : `u8` n'en gardait que l'octet bleu, et les autres voyaient une couleur presque noire après le premier `HELLO` (bogue latent de 0.4.0, masqué en mode serveur jusqu'au `HELLO` par la balise de présence qui porte l'entier).
- **Décision** :
  1. Un seul entier de 32 bits, `MemberLook` (`:core`) : l'identifiant d'icône dans l'octet de poids fort, la couleur RGB en dessous. Il remplace l'octet de couleur dans `HELLO`, `PEERS` et le code d'appairage (`i32`), et passe tel quel dans la balise de présence Firebase (`k`, déjà un nombre).
  2. Les icônes sont une liste ordonnée (`MemberLook.ICONS`) ; l'identifiant est l'index et voyage : on n'ajoute qu'à la fin. 0 = initiales (l'apparence d'avant). Un octet de poids fort inconnu (dont `0xFF` des couleurs ARGB de 0.4.0) donne les initiales.
  3. `Msg.PROTOCOL` passe à 2 et la version du code d'appairage à 3 (la version 2 est déjà celle des invitations au serveur, `RoomInviteCodec`) : un code de 0.4.0 est refusé comme « code non valide » au lieu d'être mal lu.
  4. Les dessins sont des icônes Material (Apache 2.0) copiées en vecteurs `res/drawable/ic_member_*`, blancs et teintés : noir ou blanc selon la clarté du disque.
  5. Sur la carte, chaque membre est une image (disque de sa couleur, liseré blanc, icône ou initiales) créée à la demande, une par icône et couleur (`MemberLook.imageKey`), dans une couche de symboles.
  6. Le tracé des autres membres est un réglage local (`ShareSettings.showTrails`, actif par défaut) : désactivé, seule la position actuelle s'affiche. Rien ne change sur le réseau.
- **Conséquences** : un téléphone en 0.4.0 et un téléphone à jour ne se comprennent plus (trames `HELLO`/`PEERS` refusées, codes refusés) : mettre à jour tous les téléphones du groupe. Les réglages enregistrés gardent leur couleur de palette (`colorIndex`) tant qu'aucune couleur n'est choisie sur le cercle.
- **Alternatives rejetées** : ajouter un champ en fin de `HELLO` (0.4.0 refuse aussi les octets en trop, sans gain) ; des emoji (multicolores, la couleur choisie ne se verrait que sur le disque) ; la bibliothèque `material-icons-extended` (lourde, figée par Google, pour 12 icônes).

## ADR-049 — Icônes de profil en emoji et image de profil partagée (message AVATAR)

- **Date / statut** : 2026-10-14 — Accepté (demande de l'utilisateur : des icônes rigolotes, animaux, monstres, personnages, 15 au plus ; importer sa propre image, en taille réduite obligatoire, vue par tout le groupe). **Remplace** les points 2 et 4 d'ADR-048 (icônes Material).
- **Décision** :
  1. Les icônes sont des **emoji** dessinés par la police du système (Compose et `Canvas.drawText` pour la carte) : initiales + 14 (renard, panda, grenouille, pieuvre, T-rex, licorne, manchot, monstre, fantôme, extraterrestre, robot, magicien, vampire, zombie). Tous datent d'Emoji 5.0 (Android 8.0, le minSdk). Aucun fichier d'image, aucune licence à citer, aucun personnage sous droits (pas de Pikachu ni de Mario : marques déposées). Les identifiants 1 à 12 d'ADR-048 changent de sens : cette version n'a été diffusée que par l'APK de test du 2026-10-14.
  2. **Image de profil** : l'identifiant d'icône `0x7F` (`MemberLook.PICTURE`) dit « mon image ». L'image choisie (sélecteur de photos du système) est recadrée au carré central, redressée selon l'EXIF, réduite à **64 × 64 px** et enregistrée en JPEG dont la qualité baisse jusqu'à tenir dans **6 Ko** (`MemberLook.MAX_PICTURE_BYTES`) ; sinon elle est refusée. Elle est gardée en Base64 dans les réglages de partage.
  3. Elle voyage dans un nouveau message **`AVATAR` (12)** : `memberId u64 | seq u32 | taille u16 | JPEG`, propagé comme un texte (numéro de séquence, relais, une seule prise en compte) ; envoyé à tout le groupe quand il change, et renvoyé tel quel (même numéro) à chaque nouveau lien. Vide = image retirée. Au-delà de 6 Ko, le message est refusé à la lecture et rien n'est envoyé à l'écriture.
  4. Tant que l'image n'est pas arrivée, les autres voient les initiales sur le disque de couleur. Sur la carte, l'image remplit le disque (liseré blanc) ; une image de carte par contenu (`tm-p-<hachage>-<taille>`).
- **Conséquences** : un téléphone d'une version antérieure ignore `AVATAR` (type inconnu : trame refusée en silence) et voit les initiales. La trame la plus lourde passe de quelques centaines d'octets à environ 6 Ko, très en dessous de la limite de 16 Ko des trames et des messages Firebase.
- **Alternatives rejetées** : images d'emoji sous licence (Twemoji, OpenMoji : fichiers et attribution, alors que la police du système suffit) ; images plus grandes (coût sur réseau mobile, chaque membre la reçoit) ; image dans la balise de présence du serveur (n'existe pas en mode direct, et serait lisible par le serveur : elle doit rester chiffrée par la clé du groupe).

## ADR-050 — Jetons de membre : silhouettes de game-icons.net teintées de la couleur choisie

- **Date / statut** : 2026-10-14 — Accepté (demande de l'utilisateur : des icônes qui changent de couleur avec la couleur choisie, dans le style d'un cavalier d'échecs plein, « comme des jetons de jeu sur une minicarte », 15 variées ; il proposait Flaticon). **Remplace** le point 1 d'ADR-049 (emoji) ; l'image de profil (points 2 à 4 d'ADR-049) ne change pas.
- **Décision** :
  1. Les icônes sont 14 silhouettes de **game-icons.net** (CC BY 3.0) : cavalier, loup, renard, aigle, pieuvre, T-rex, licorne, dragon, fantôme, extraterrestre, robot, magicien, ninja, viking ; avec les initiales, 15 choix. Copiées depuis le paquet npm `@iconify-json/game-icons` en vecteurs `res/drawable/ic_token_*` (un seul tracé, repère 512), blancs et teintés à l'affichage.
  2. Dessin d'un jeton : silhouette (ou initiales) **de la couleur choisie** sur un disque contrasté (blanc sous une couleur sombre, presque noir sous une couleur claire : `MemberLook.backdrop`), cerclé de la couleur choisie. L'image de profil se place dans le cercle.
  3. L'attribution (CC BY) est dans « Aide et à propos » (`ThirdParty`) et dans l'en-tête de chaque fichier.
- **Alternatives rejetées** : **Flaticon** : le téléchargement demande un compte, la licence gratuite exige une attribution par icône et par auteur, et les conditions interdisent la récupération automatique ; game-icons.net offre le même style (silhouettes pleines de jeu) sous une licence simple. Les **emoji** (ADR-049) : multicolores, ils ne prennent pas la couleur choisie.
- **Conséquences** : les identifiants 1 à 14 changent de sens une nouvelle fois (seuls des APK de test du 2026-10-14 sont concernés). Rien ne change sur le réseau.

## ADR-051 — Partage écran verrouillé : service relancé par le système, reconnexion agressive au serveur

- **Date / statut** : 2026-10-14 — Accepté (test terrain de l'utilisateur : le partage marchait, puis « Serveur injoignable » sans retour ; un autre membre en « Synchronisation perdue » depuis 19 min ; l'application semblait se fermer téléphone verrouillé). Complète ADR-033.
- **Décision** :
  1. `ShareService` est **`START_STICKY`** : si le système tue le processus pendant un partage, il le relance et le partage reprend. Un drapeau `share-service/active` (SharedPreferences) distingue l'arrêt voulu (bouton, interrupteur, GPS perdu) d'une mort : une relance sans ce drapeau s'arrête aussitôt. Un démarrage au premier plan refusé (Android 12+, arrière-plan) arrête le service sans planter.
  2. **Reconnexion** : l'attente entre deux essais plafonne à **8 s** (au lieu de 30 s) ; `RelayTransport.reconnectNow()` réveille la boucle tout de suite et annule (`Call.cancel`) un flux ouvert depuis plus de 5 s, qui peut rester accroché au réseau disparu. `ShareService` l'appelle sur changement du réseau par défaut (nouveau réseau, ou réseau qui redevient « validé »). Délai de lecture du flux : 45 s (Firebase envoie un keep-alive toutes les 30 s).
  3. **Écritures fusionnées** : hors réseau, chaque écriture échoue lentement (20 s) ; positions et présences ne s'empilent plus dans la file : une seule écriture de position en attente (toujours la plus récente), une seule présence. Avant, la file grossissait d'une écriture par position et la présence, coincée derrière, n'arrivait plus aux autres après le retour du réseau.
  4. Carte « Partage téléphone verrouillé » sur l'écran de partage tant que l'application est soumise à l'optimisation de la batterie : demande d'exemption (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) et conseil Samsung (« Applications jamais en veille »).
- **Alternatives rejetées** : relancer par `AlarmManager` / WorkManager (un service de premier plan collant suffit, et un travail périodique ne tourne pas plus souvent que toutes les 15 min) ; garder 30 s de plafond (trop lent en montagne où le réseau va et vient).
- **Conséquences** : quelques requêtes de plus sans réseau (un essai toutes les 8 s, qui échoue vite) ; pour une publication Google Play, la permission d'exemption de batterie devra être justifiée (partage de position en continu).

## ADR-052 — Favoris fournis supprimables ; « Rando Multi-Zoom » de l'utilisateur en carte intégrée
- **Date** : 2026-10-14 · **Statut** : accepté
- **Contexte** : demande de l'utilisateur : « Permet de supprimer les cartes par défaut. Et mets ma carte AQX parmi les défauts à la place du IGN », avec son fichier `USR.PERSONNAL_MULTIZOOM.AQX` (source `RANDO_ZOOM_GROUP` : OSM 1–8, OpenTopoMap 9–11, IGN SCAN 25 12–16, OSM 18, IGN Ortho 19).
- **Décision** :
  - Les favoris fournis restent dans le code (`Workspaces.builtin`) ; leur suppression les **masque** : identifiants listés dans `workspaces/hidden-builtins.txt` (fichier ignoré par la lecture des `.json`), `WorkspaceStore.favorites` = fournis non masqués + enregistrés. « Rétablir les favoris fournis » vide la liste. Une mise à jour de l'app qui change un favori fourni le propage donc toujours.
  - La source de l'utilisateur devient une carte intégrée **multi-zoom** (`builtin-sources.aqx`, même id `RANDO_ZOOM_GROUP`) écrite en références `toolmap-map:` vers les cartes intégrées (OSM 1–8, OpenTopoMap 9–11, IGN SCAN 25 12–16, OSM 18, IGN Ortho 19), comme `ign-topo25k`. Une copie brute des URL du fichier faisait de cette carte la propriétaire des tuiles d'OSM, de SCAN 25… (la première carte intégrée qui déclare un jeu de tuiles le possède, ADR-031 : 7 tests rouges) ; en références, elle ne stocke rien et partage les tuiles déjà téléchargées. Une copie importée du fichier est remplacée par la version intégrée (règle existante de `SourceRepository`).
  - Le favori « IGN topo » (`ign-maps` seule) est remplacé, en première position, par « Rando Multi-Zoom » (`builtin-rando-multizoom`). La carte « IGN Cartes » reste au catalogue.
- **Conséquences** : un favori « IGN topo » n'existe plus ; un utilisateur qui le veut l'enregistre lui-même (signet). Le zoom 17 de la carte multi-zoom n'a pas de niveau propre (comme dans le fichier) : il est complété par la règle des zooms manquants (ADR-031).

## ADR-053 — « Quitter » et interrupteur Baromètre rétablis
- **Date** : 2026-10-16 · **Statut** : accepté (demande de l'utilisateur : corriger les écarts avec AlpineQuest, P16.2). **Remplace** le point 5 de ADR-034 (« Pas de Quitter ») et le point 3 de ADR-039 (le baromètre n'est plus une coche du panneau Position).
- **Contexte** : AlpineQuest a un bouton « Quitter » dans son menu principal et une case Baromètre dans le panneau de position ; ToolMap les avait retirés (sortie forcée qui coupe un enregistrement ; doublon avec Réglages).
- **Décision** :
  - Le menu principal a une ligne « Quitter » après « Réglages… ». Si un enregistrement de trace, un partage de position ou des alertes tournent, le dialogue « Quitter ToolMap ? » (Quitter / Annuler) précise que ce qui tourne continue en arrière-plan et s'arrête avec ses propres commandes ; sinon l'application se ferme directement. Quitter ne coupe donc rien : il ferme l'écran.
  - Le panneau Position reprend l'interrupteur « Baromètre », visible seulement si le téléphone a le capteur ; il écrit le même réglage que Réglages (un seul réglage, deux commandes, comme pour la boîte de coordonnées).
- **Conséquences** : une sortie ne coupe jamais un service, et prévient quand il en reste un. Le doublon Baromètre revient, voulu : c'est l'écart que l'utilisateur voulait voir fermé.

## ADR-054 — Catalogue de cartes en ligne embarqué
- **Date** : 2026-10-16 · **Statut** : accepté (P16.4).
- **Contexte** : AlpineQuest propose un catalogue de cartes distantes dans « Ajouter une carte » ; ToolMap n'avait que l'assistant, l'import AQX et les cartes fournies.
- **Décision** : un fichier AQX `core/src/main/resources/online-catalog.aqx` (25 cartes : OpenStreetMap, IGN, swisstopo, BKG, USGS, Ordnance Survey) lu par `OnlineCatalog`, présenté dans « Ajouter une carte ▸ Catalogue en ligne… » (`OnlineCatalogScreen`). Seules les sources dont les conditions permettent l'accès direct aux tuiles y figurent : ni Bing ni Google. Les cartes qui exigent une clé (Ordnance Survey, paramètre `os-key` laissé vide) la demandent avant l'ajout ; elle reste sur le téléphone. Une carte déjà possédée (même identifiant, ou mêmes tuiles par `TileSetIdentity`) montre « Ajoutée » et n'est pas dupliquée (`SourceRepository.alreadyHave` / `addFromCatalog`).
- **Conséquences** : le catalogue est figé dans l'APK (une mise à jour de la liste passe par une version) ; une adresse qui change chez un fournisseur se corrige dans le fichier. Aucun serveur de ToolMap n'est interrogé.

## ADR-055 — Dossiers créés et rangés par l'utilisateur
- **Date** : 2026-10-16 · **Statut** : accepté (P16.5 ; couvre la part « dossier créé, déplacement » de P14.7).
- **Contexte** : le modèle (`FolderEntity`, `folderId` sur traces, itinéraires et waypoints) existait pour l'import KML ; l'écran n'offrait ni création ni déplacement.
- **Décision** : `TrackRepository.createFolder`, `moveItem` et `moveFolder` (refus d'un dossier dans lui-même ou dans ses descendants), exposés par « Nouveau dossier » (haut de l'écran), « Nouveau sous-dossier » et « Déplacer vers… » (menus ⋮). Aucune migration Room : les colonnes étaient déjà là. Les fragments XML bruts d'un élément s'appuient sur les préfixes de l'import au sommet de son dossier : quand l'élément change de dossier racine, la racine d'arrivée apprend les préfixes de l'ancienne (les siens l'emportent en cas de conflit) ; au sommet de la bibliothèque, `NamespaceMerger` offre les préfixes de toutes les racines et chaque fragment déclare ceux qu'il utilise.
- **Conséquences** : si deux imports utilisent un même préfixe pour deux espaces de noms différents, un élément déplacé garde celui de la racine d'arrivée (cas très rare). Tri, filtre et sélection multiple restent à faire (P14.7).

## ADR-056 — Icônes de waypoint et d'activité : sources libres, générées depuis un catalogue
- **Date** : 2026-10-16 · **Statut** : accepté (P16.6).
- **Contexte** : AlpineQuest propose environ 300 icônes de waypoint en 16 catégories (une couleur par catégorie) et 32 activités. Ses dessins sont propriétaires et les icônes de Geocaching.com sont des marques : ToolMap ne peut pas les copier.
- **Décision** : ToolMap reconstruit des icônes **équivalentes** (même sujet, même nom, même couleur de catégorie) à partir de jeux libres : Maki et Temaki (CC0), Material Design Icons (Apache 2.0, mention dans « Aide et à propos », `ThirdParty.kt`). `tools/icons/catalog.txt` est la source unique (catégories, couleurs, clé GPX, libellé français, glyphe source, activités) ; `tools/icons/generate.mjs` en tire les VectorDrawables (`ic_wp_*`, `ic_act_*`), les libellés, `WaypointCatalog`, `WaypointIconRes`, `TrackActivity` et `ActivityRes` (ne pas les modifier à la main). La clé stockée et écrite en GPX `sym` est la clé de l'icône ; les anciens noms Garmin (Summit, Campground…) sont des alias. Défaut : « Waypoint » en rouge #E53935 (couleur historique de ToolMap). Sur la carte : disque de la couleur de la catégorie avec le glyphe blanc (`SymbolLayer`, image `tm-wp-<id>` ajoutée à la demande). Géocaching : glyphes génériques aux couleurs approchantes.
- **Conséquences** : l'enum `TrackActivity` passe de 5 à 33 valeurs (les anciennes HIKING, RUNNING, CYCLING, SKI, OTHER deviennent HIKE, RUN, BICYCLE, SKI, OTHER ; l'activité n'est pas stockée, seulement mise dans le nom de la trace). Ajouter une icône = une ligne du catalogue puis relancer le générateur (`npm pack @mapbox/maki @rapideditor/temaki @mdi/svg`, extraits chacun dans un dossier).

## ADR-057 — Mesures sur l'émulateur, build « benchmark » et Baseline Profile
- **Date** : 2026-10-16 · **Statut** : accepté et réalisé le 2026-10-16 (P15.1 session 34, P15.5 session 35 ; le profil ne change pas le démarrage mesuré sur l’émulateur, voir `performance.md` §9).
- **Contexte** : la spec P15 attendait des mesures faites par l'utilisateur sur son téléphone avant tout changement ; elles n'ont jamais été faites, et l'émulateur est disponible depuis le 2026-10-15. L'utilisateur n'installe que l'APK de debug (debuggable, sans R8) : Compose y est nettement plus lent qu'en application optimisée, et une mesure faite dessus ne dit rien de l'application réelle. R8 n'a jamais tourné sur l'application (ADR-027), et il n'y a pas de Baseline Profile.
- **Décision** :
  1. Toutes les mesures de P15 se font **sur l'émulateur**, avant et après chaque changement (décision de l'utilisateur) ; elles servent à comparer, pas comme valeurs absolues. La batterie, que l'émulateur ne mesure pas, est renvoyée à plus tard, sur téléphone.
  2. Nouveau type de build **`benchmark`** dérivé de `release` : non debuggable, `profileable`, R8 et réduction des ressources activés, signé avec la clé de debug fixe, `matchingFallbacks += "release"`. Les mesures se font sur lui ; l'utilisateur peut aussi l'installer au quotidien. La release reste telle qu'ADR-027 la décrit tant que le build benchmark n'a pas fait ses preuves.
  3. Relevé de temps (carte entièrement dessinée, tuiles par origine) compilé en debug et en benchmark seulement.
  4. **Baseline Profile** : module `:baselineprofile` (Macrobenchmark, plugin `androidx.baselineprofile`) et `androidx.profileinstaller` dans `:app`, profil généré sur l'émulateur et versionné.
- **Conséquences** : un module Gradle et deux dépendances de plus (versions figées dans `libs.versions.toml`) ; le premier passage de R8 peut demander des règles `proguard-rules.pro` (Hilt, Room, MapLibre, WebRTC, Firebase, sérialisation) : c'est aussi le chemin vers une release optimisée. La CI garde `check assembleDebug` ; la génération du profil n'y tourne pas (pas d'émulateur).
- **Alternatives rejetées** : mesurer l'APK de debug (faussé) ; activer R8 directement en release (risque sur l'APK distribué sans essai préalable) ; attendre les mesures sur téléphone (bloquait P15 depuis le 2026-10-12).

## ADR-058 — Tuiles : connexions par serveur et ordre de service
- **Date** : 2026-10-16 · **Statut** : accepté et réalisé le 2026-10-16 (P15.2 et P15.3, session 35 ; chiffres dans `performance.md` §9).
- **Contexte** : l'utilisateur constate que les tuiles apparaissent lentement quand il déplace la carte. Le code montre 40 à 60 tuiles par écran (netteté d'ADR-032, gardée), 2 ou 3 téléchargements simultanés par source, `maxRequestsPerHost = 20` partagé par tout l'hôte factice (les tuiles stockées attendent derrière le réseau), une file d'attente dans l'ordre d'arrivée, et un téléchargement de zone plafonné à 3 fils avec une transaction SQLite par tuile. Règles d'usage vérifiées le 2026-10-16 : le WMTS de la Géoplateforme n'a pas de limite de requêtes par seconde (CGU de cartes.gouv.fr, art. 3.2 ; blocage possible en cas d'usage abusif, art. 2.5) ; la politique d'OSM ne chiffre pas les connexions mais interdit l'usage hors ligne et le préchargement.
- **Décision** :
  1. Le `<max-threads>` de la source décide du nombre de téléchargements simultanés, pour l'affichage comme pour le téléchargement de zone (plus de plafond général de 3). Les 5 sources IGN fournies déclarent `<max-threads>6</max-threads>`. `BulkPolicy` garde 2 fils et 300 ms de pause pour OSM, OpenStreetMap France et OpenTopoMap.
  2. `maxRequestsPerHost` passe à 64 : les sémaphores par source protègent les serveurs, la limite de l'hôte factice ne doit plus retenir les tuiles lues en MBTiles.
  3. La file d'attente d'une source sert la demande la plus récente en premier ; un appel annulé par MapLibre la quitte sans requête réseau.
  4. Le téléchargement de zone écrit ses tuiles par paquets (64 tuiles ou 1 s par transaction), lit chaque tuile une seule fois et rafraîchit son état et sa notification toutes les secondes. L'affichage de la carte garde l'écriture tuile par tuile.
  5. « Enregistrer la zone » reste permis sur `tile.openstreetmap.org` dans les limites de `BulkPolicy` (1 500 tuiles), malgré la politique d'OSM : choix de l'utilisateur, risque consigné dans `performance.md` §4.
- **Conséquences** : jusqu'à 6 connexions simultanées vers `data.geopf.fr` ; un arrêt brutal pendant un téléchargement de zone perd au plus un paquet, retéléchargé à la reprise. Le partage du sémaphore entre affichage et téléchargement de zone est gardé, pour que le total respecte `<max-threads>`.
- **Alternatives rejetées** : changer la netteté par défaut (ADR-032, fidélité à AlpineQuest) ; plus de 6 connexions vers l'IGN sans mesure (clause d'usage abusif) ; refuser l'enregistrement de zone OSM (écarté par l'utilisateur).

## ADR-059 — Numéro de version : CORRECTIF automatique à chaque changement des sources

- **Date / statut** : 2026-10-17 — Accepté (demande de l'utilisateur : « Toolmap version been stuck at 0.4.0 since multiple update […] make sure there is never 2 file with the same number. No matter how small change is made to the app »). **Remplace le point 2 d'ADR-040** (« une incrémentation par livraison », faite à la main) ; la forme `0.MINEUR.CORRECTIF`, le MINEUR décidé par la session et `1.0.0` décidé par l'utilisateur restent.
- **Contexte** : le numéro était écrit à la main dans `appVersionName` (`app/build.gradle.kts`). Les sessions 34 et 35 ont livré des changements (P15.1 à P15.8) en laissant `0.4.0` : plusieurs APK différents portaient le même nom, y compris sur le miroir Drive (`ToolMap.0.4.0.apk` écrasé à chaque fois).
- **Décision** :
  1. Le numéro est dans `version.properties` (racine) : `version=` et `sourceHash=`, l'empreinte des sources dont ce numéro a été compilé.
  2. L'empreinte est le SHA-256 (16 premiers caractères hexadécimaux) des fichiers Gradle (`build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`), des fichiers à la racine des modules `:app`, `:core`, `:data`, `:p2p` et de leurs jeux de sources hors tests (`src/*`, sauf `test*` et `androidTest`), et des lignes de `docs/JOURNAL.md` que « Nouveautés » copie dans l'APK (titres `## ` et « Objectif »). Une nouvelle entrée du journal change donc le numéro : **recompiler après l'avoir écrite**, avant de pousser. Les `\r` sont retirés : une copie Windows et une extraction Linux des mêmes fichiers donnent la même valeur.
  3. Un build **local** qui produit un APK ou un bundle (tâche `assemble*`, `bundle*`, `install*`, `package*`) et dont l'empreinte diffère passe le CORRECTIF à +1 et réécrit le fichier. Empreinte vide = numéro posé à la main (nouveau MINEUR) : gardé tel quel.
  4. La **CI** (`CI=true`) n'écrit jamais : une empreinte qui ne correspond pas fait échouer le build (sources poussées sans build local).
  5. `scripts/drive-mirror.ps1` lit le numéro dans `version.properties`.
- **Conséquences** : deux APK différents n'ont jamais le même numéro ; un APK recompilé à l'identique garde le sien. Le numéro avance à chaque build d'APK qui suit une modification du code (pas des tests ni de la documentation), et peut avoir des trous (build échoué après l'incrémentation). Deux copies de travail partant du même numéro peuvent produire le même suivant : travailler dans une seule à la fois (`release.md` §1).
- **Alternatives rejetées** : nombre de commits git (le dossier du poste Windows n'est pas suivi par git) ; numéro de run de la CI (les builds locaux et le miroir Drive n'en ont pas) ; date et heure (deux builds identiques auraient deux numéros, et le numéro ne dirait plus l'importance du changement) ; garder la règle manuelle avec un rappel (déjà écrite, et oubliée deux sessions de suite).

## ADR-060 — Panneaux de la barre : un seul style, et le panneau Cartes posé au-dessus de la barre

- **Date / statut** : 2026-10-17 — Accepté (l'utilisateur a validé les maquettes de la session 38 : « Je valide les recommandations, construit les nouveaux UI/IX »).
- **Contexte** : les quatre boutons de la barre ouvraient trois panneaux posés au-dessus de la barre (menu principal, Traces et waypoints, Positionnement) et une feuille du bas (Cartes et calques, `MapsRootSheet`) d'un autre style. Les panneaux mélangeaient des lignes sans hiérarchie : commandes fréquentes, état, réglages rares et sortie au même niveau. Le dialogue « Nouveau waypoint » (P14.9) a donné le style retenu.
- **Décision** :
  1. Les pièces communes sont dans `map/PanelParts.kt` : `BarPanel` (cadre), `PanelHeader`, `StateCard` (bloc teinté qui dit l'état et s'ouvre), `ActionTiles` / `ActionTile` (tuiles tonales des commandes fréquentes), `MenuRow` (ligne avec sous-titre, chevron s'il ouvre un écran, interrupteur s'il bascule), `PanelCaption`. Un nouveau panneau de la barre les réutilise.
  2. « Cartes et calques » devient un panneau au-dessus de la barre (`maps/MapsRootPanel.kt`), comme les trois autres ; un 2e appui sur le bouton Cartes, Retour ou un appui hors du panneau le ferme. Il reste ouvert au retour de « Cartes disponibles ». Les feuilles de §5.2 (cartes affichées) gardent leur forme.
  3. Le menu principal ne liste plus les favoris (« Espace de travail », `[R2]`) : un bloc « Carte affichée » nomme le favori appliqué (ou « Pile personnalisée ») et ouvre le panneau Cartes, seul endroit où l'on applique, enregistre et supprime un favori.
  4. Le mode de suivi est visible (`[R4]`) : segments « La carte suit » Libre · Suivi · Boussole · Explorer dans Positionnement. Explorer = suivi avec boussole + inclinaison ; la carte inclinée (`MapController.tilted`, ≥ 1°) distingue Explorer de Boussole. L'appui long sur le bouton position reste un raccourci.
- **Conséquences** : un seul style pour les quatre panneaux ; la hiérarchie se lit à la forme (tuile, bloc, ligne). Le panneau Cartes est plus court qu'une feuille plein écran mais défile au-delà de la hauteur des panneaux. Réglages (lot F) n'est pas encore repris.
- **Alternatives rejetées** : garder la feuille du bas pour Cartes (deux styles pour une même barre) ; un 5e bouton pour le mode de suivi (la barre est pleine) ; garder les favoris dans le menu en plus du panneau (deux noms, « espace de travail » et « favori », pour une seule chose).

## ADR-061 — Impression : deux questions, un cadre posé sur la carte, une feuille aux normes des cartes papier (TOP 25 ou OTAN)

- **Date / statut** : 2026-10-17 — Accepté (séance de questions de la session 39, Q1 à Q23, réponses validées par l'utilisateur ; PDF de contrôle à valider avant tout commit). **Remplace** la §6 de `docs/specs/print-pdf.md` (panneau unique, cadre fixe au centre de l'écran, atlas et modèles dans le panneau). Complète ADR-020 sans le contredire (écrivain PDF maison, projections maison).
- **Contexte** : l'impression (P7) ouvrait un panneau de quinze réglages, avec un cadre fixe au centre de l'écran sous lequel on déplaçait la carte. L'utilisateur veut le parcours de « Sélectionner et enregistrer une zone » : d'abord la taille de la feuille et l'échelle, puis un cadre de taille fixe à placer, puis une confirmation courte (grille, waypoints), et une feuille utilisable sur le terrain comme une carte papier.
- **Décision** :
  1. **Parcours en trois étapes** (`PrintStage`) : une fenêtre pose les deux questions (taille : A5, A4, A3, Letter, Autre… en mm, et l'orientation ; échelle : 1:10 000, 1:20 000, 1:25 000, 1:50 000, 1:100 000, 1:250 000, autre), réglages de la dernière impression présélectionnés, bouton « Choisir la zone ».
  2. **Cadre posé sur le sol** : centré sur la carte au départ, carte remise au nord, zoom réglé pour que le cadre occupe environ 70 % de la place libre. Un doigt dans le cadre le déplace, deux doigts zooment la carte ; hors du cadre la carte garde ses gestes. Pas de poignées : la taille découle du format et de l'échelle. Un bandeau en haut garde `Format ▾`, `Orientation ▾`, `Échelle ▾` et ⟲ : un changement garde le centre et règle le zoom à nouveau. En bas : Annuler / Suivant.
  3. **Confirmation** : grille (Aucune · UTM · MGRS · Lambert 93 · WGS84 DMS · WGS84 décimal ; la première fois, celle du format de coordonnées des réglages), waypoints, traces, titre prérempli « carte · date », une ligne de résumé, les tuiles manquantes (téléchargeables jusqu'à 1 500, hachurées au-delà) et les cartes agrandies en orange (non bloquant). « Plus d'options » replié : style, dpi, marge, pas de grille, noms des waypoints, éléments de la feuille, sous-titre. Boutons Retour à la zone / Créer le PDF.
  4. **PDF seulement** (pas d'impression directe) ; après l'enregistrement : « PDF enregistré · 1 page · 2,4 Mo », rappel « imprimer à 100 % », Ouvrir et Partager. L'aperçu interne est retiré.
  5. **Une page par impression**. L'atlas reste dans `:core` (maths testées) mais quitte l'écran ; il reviendra depuis la fiche d'une trace. Les modèles enregistrés sont retirés : seule la dernière impression est mémorisée (`PrintTemplateStore.last`).
  6. **Feuille** : marge, bande d'étiquettes et cadre noir double gradué dans l'autre système (minutes de latitude et longitude pour une grille métrique, kilomètres UTM pour une grille en degrés) ; étiquettes sur les quatre côtés à chiffres principaux (gros chiffres = kilomètres), coordonnées complètes aux coins ; un bandeau du bas en cases : titre et légende, échelle (chiffrée, barre, « 1 cm = … », écart aux bords), diagramme des trois nords hors échelle avec les valeurs exactes (déclinaison, convergence, angle quadrillage–magnétique, année, variation annuelle), quadrillage et système, ©.
  7. **Deux styles** au choix (« Plus d'options », TOP 25 par défaut, mémorisé) : **IGN TOP 25** (quadrillage bleu, angles en degrés) et **OTAN** (quadrillage noir, angles aussi en millièmes 6 400, carré de 100 km, encadré « Donner une référence » avec l'exemple du centre de la feuille).
  8. **Grille** : pas automatique selon l'échelle et le style (`GridSpacing`, modifiable) ; la grille de la zone UTM du centre est prolongée au-delà de la limite de zone, avec une note ; en MGRS, les lettres du carré de 100 km de part et d'autre d'une limite.
  9. Waypoints : disque de 4 mm à la couleur de l'icône avec son glyphe blanc, nom noir détouré de blanc (masquable) ; traces de 0,6 mm environ.
- **Conséquences** : le cadre ne bouge plus avec la carte, il faut le faire glisser (comme la zone à enregistrer). La place de la carte sur la feuille diminue (bandeau et cadre gradué) : une A4 paysage montre 263,8 × 148,2 mm de carte (6,6 × 3,7 km au 1:25 000). Les anciens fichiers `templates.json` sont ignorés ; `last.json` d'avant P17 est relu (champs inconnus ignorés, mini-carte coupée).
- **Alternatives rejetées** : garder le panneau unique (trop de réglages avant de voir la zone) ; impression directe par le service d'impression d'Android (l'échelle dépend alors du pilote ; le PDF « 100 % » est plus sûr) ; poignées de redimensionnement (la taille ne serait plus celle de la feuille) ; un seul style de feuille (l'utilisateur veut les deux) ; garder l'atlas et les modèles dans l'écran (rarement utiles, ils alourdissent le parcours).

## ADR-062 — Noms sur la carte : glyphes dessinés sur le téléphone, servis à MapLibre par un intercepteur

- **Date / statut** : 2026-10-18 — Accepté (session 41, revue UX des waypoints, du partage et du stockage hors ligne). **Remplace** la phrase « Pas d'étiquettes de texte » d'ADR-018 point 6 et le « pas d'étiquette de texte » d'ADR-021 point 7 (membres du partage). Ne change rien d'autre à ces ADR.
- **Contexte** : sur la carte, un waypoint n'était qu'une icône et un membre du groupe un disque : pour savoir lequel est lequel, il fallait toucher chaque repère. AlpineQuest écrit le nom à côté. MapLibre ne dessine du texte qu'à partir de fichiers de glyphes (`glyphs`, PBF de champs de distance signée par plage de 256 caractères), que le style de base n'avait pas ; son dessin local de glyphes (`LocalGlyphRasterizer`) ne couvre que les idéogrammes CJK. Une police hébergée demande le réseau ; une police embarquée pèse plusieurs Mo par plage d'alphabet.
- **Décision** :
  1. Le style de base déclare `"glyphs": "https://glyphs.toolmap.local/{fontstack}/{range}.pbf"`. Un intercepteur OkHttp (`GlyphInterceptor`, `:data`, placé avec les autres intercepteurs de `TileModule`) répond à cet hôte fictif : il **dessine chaque caractère de la plage avec la police sans empattement du téléphone** (Roboto en général ; « Medium » si la pile de polices contient `bold` ou `medium`) à 24 px, calcule le champ de distance signée et écrit le fichier PBF. Rien ne sort sur le réseau ; fonctionne hors ligne ; aucune police dans l'APK. Chaque fichier fait quelques Ko et reste en mémoire une fois fait.
  2. Les maths sont dans `:core`, testées en JVM : `SignedDistance` (transformée de distance euclidienne exacte de Felzenszwalb, comme TinySDF : marge 3 px, rayon 8, bord à 191) et `GlyphRange` (lecture de la plage, encodage du protocole `glyphs > stacks > glyphs` à la main, `top` ramené à la ligne de base de MapLibre par `ASCENT` = 24, calé sur l'émulateur : texte centré sur le repère).
  3. Couches `tm-t-points-label` (waypoints) et `tm-s-labels-layer` (membres) : nom à droite du repère, 13 dp × taille des repères, texte gris foncé détouré de blanc, un nom qui en couvrirait un autre n'est pas écrit (le repère reste). Réglage **Repères ▸ « Noms sur la carte »** (`PlacemarkSettings.labels`, actif par défaut).
- **Conséquences** : la première apparition d'une plage coûte quelques dizaines de ms de dessin (hors fil principal, dans le client OkHttp de MapLibre). Les caractères absents de la police du téléphone ne sont pas écrits. Le rendu dépend de la police du téléphone (Roboto, One UI…). L'impression PDF n'est pas concernée (elle écrit ses noms elle-même, ADR-061).
- **Alternatives rejetées** : police PBF embarquée (plusieurs Mo pour le latin étendu seul, et une licence à suivre) ; serveur de glyphes en ligne (pas hors ligne, et contraire à ADR-018) ; noms dessinés dans l'image de chaque repère (pas d'évitement des collisions, une image par nom et par taille) ; vues Android posées sur la carte (coûteuses au déplacement, désynchronisées).

## ADR-063 — Favori « Rando Multi-Zoom » : cinq cartes empilées au lieu d'une carte multi-zoom intégrée

- **Date / statut** : 2026-10-18 — Accepté (session 43, demande de l'utilisateur : « montrer ce que permettent les options au lieu d'imposer le montage »). **Remplace** le 2e point de la décision d'ADR-052 (source intégrée multi-zoom `RANDO_ZOOM_GROUP`) et précise le 3e (contenu du favori). Le reste d'ADR-052 (favoris fournis supprimables) est inchangé.
- **Contexte** : le favori « Rando Multi-Zoom » affichait une seule carte, la source intégrée `RANDO_ZOOM_GROUP`, qui changeait de fond selon le zoom en interne. Dans « Cartes affichées », l'utilisateur ne voyait qu'une ligne : ni les cartes utilisées, ni leurs plages de zoom, rien à régler. Preview validée par l'utilisateur le 2026-10-18 (cinq lignes dans « Cartes affichées », chacune avec « Visible aux zooms … »).
- **Décision** :
  1. Le favori `builtin-rando-multizoom` devient une pile de cinq calques ordinaires des cartes intégrées, chacun avec sa plage d'affichage (réglage de calque existant, comme « IGN Topo 8–16, Plan 1–7, Pentes ») : OpenStreetMap 1–8, OpenTopoMap 9–11, IGN SCAN 25 Touristique **12–17**, OpenStreetMap 18, IGN Photographies aériennes 19. Le zoom 17, absent du fichier de l'utilisateur, est confié au SCAN 25 (tuiles du zoom 16 agrandies), comme le dit le commentaire du fichier (« Zoom 12 à 17 ») : choix de l'utilisateur.
  2. La source intégrée `RANDO_ZOOM_GROUP` est **retirée** de `builtin-sources.aqx` (choix de l'utilisateur). Les cartes multi-zoom restent possibles par l'éditeur, et « IGN Topo 25k (zoom forcé) » reste l'exemple fourni.
  3. Au démarrage, un état enregistré qui affichait `RANDO_ZOOM_GROUP` reçoit les cinq calques à sa place (`Workspaces.replaceRetired`, appelé par `MapViewModel`), et la carte retirée sort de la bascule rapide : pas de carte vide après la mise à jour. Si le téléphone connaît une carte de cet identifiant (copie du fichier importée à la main, qui redevient visible puisqu'elle n'est plus masquée par une carte intégrée), rien n'est remplacé.
- **Conséquences** : les tuiles déjà téléchargées servent toujours (elles appartenaient déjà à OSM, OpenTopoMap, SCAN 25 et aux photos aériennes). L'utilisateur peut changer une plage, l'opacité ou retirer une carte du montage, puis l'enregistrer comme son propre favori. Une zone enregistrée depuis ce favori se télécharge carte par carte (comportement des piles de calques).

## ADR-064 — Itinéraires calculés : BRouter par AIDL, plan d'étapes et de tronçons gardé avec la géométrie

- **Date / statut** : 2026-10-18 — Accepté (session 44, décisions Q1 à Q21 prises avec l'utilisateur ; spec `docs/specs/routing.md`). **Remplace** P6.5 (« BRouter hors ligne via son service AIDL, sinon OSRM/openrouteservice en ligne ») : pas de repli en ligne en V1. **Précise** ADR-019 (édition au réticule) sans le remplacer : le réticule reste le seul geste pour poser et déplacer une étape.
- **Contexte** : l'utilisateur veut créer depuis le panneau du drapeau des itinéraires à plusieurs étapes qui suivent les chemins OSM, et les retoucher (insérer une étape au milieu, prolonger par le début ou la fin, dévier). Un itinéraire ToolMap n'était qu'une liste de points (`Route.points`) : une fois calculé, on ne pourrait plus le recalculer en déplaçant une étape. BRouter est une application Android libre, hors ligne, qui expose un service AIDL ; ses données (`.rd5`) se téléchargent dans l'application elle-même.
- **Décision** :
  1. **Moteur** : l'application BRouter installée, par son service AIDL (`IBRouterService.getTrackFromParams`), **un appel par tronçon**, résultat en GeoJSON (`trackFormat=json`). Aucun moteur en ligne en V1 (repli `brouter.de` en V2, P19.12). ToolMap ne télécharge pas les données de BRouter : il ouvre BRouter.
  2. **Plan gardé avec la géométrie** : `RoutePlan` (`:core`) = étapes (passage ou étape nommée) + tronçons (profil, mode routé ou à la main, état, points). `routes.points` reste la **géométrie** concaténée, si bien que l'affichage, le suivi, l'impression, la recherche et les statistiques ne changent pas ; le plan est dans une nouvelle colonne `routes.plan_json` (migration Room, nullable : un itinéraire sans plan est un itinéraire à la main).
  3. **GPX** : `<rte>` avec toute la géométrie (lisible partout) et le plan en extension `tm:plan` (espace de noms `https://toolmap.app/gpx/1`), que ToolMap relit pour rendre l'itinéraire modifiable ; les étapes nommées portent `<name>` et `<sym>` sur leur `<rtept>`.
  4. **Calcul automatique et local** : chaque opération d'édition (`RoutePlanEditor`, pur, testé) dit quels tronçons recalculer ; une file (`RoutingQueue`) les calcule un par un, annule ce qu'un changement rend périmé, et garde un cache LRU (départ, arrivée, profil) pour qu'Annuler / Rétablir ne rappellent pas BRouter.
  5. **Sans données BRouter** : deux comportements au choix de l'utilisateur (réglage `routingNoDataMode`) : tronçon tracé à la main et routage qui reprend ensuite (défaut), ou tout l'itinéraire passe en dessin à la main. Le message propose l'autre comportement en un appui.
- **Conséquences** : un itinéraire calculé pèse plus lourd (géométrie dense en `<rtept>`) ; les applications tierces le lisent comme un itinéraire ordinaire. Sans BRouter installé, le mode Itinéraire reste le dessin à la main actuel. Les profils proposés dépendent de ceux de BRouter (un profil absent est signalé, pas deviné). Tout ce qui ne demande pas l'appli BRouter se teste en JVM avec un faux moteur ; l'appel réel se vérifie sur l'émulateur avec BRouter installé depuis F-Droid.


## ADR-065 — Le plan d'un itinéraire est rangé dans ses extensions GPX, sans colonne `plan_json`

- **Date / statut** : 2026-10-18 — Accepté (session 45, P19.2). **Remplace le point 2 d'ADR-064** pour le stockage seulement (le plan reste gardé avec la géométrie) ; le reste d'ADR-064 tient.
- **Contexte** : ADR-064 prévoyait une colonne Room `routes.plan_json` (migration). Or `RouteEntity` garde déjà les extensions GPX de l'itinéraire (`extrasJson`, rempli dès l'import GPX) et le plan a de toute façon une forme GPX (`tm:plan`, §7.3 de la spec) pour l'export et le réimport.
- **Décision** : le plan est **un élément `tm:plan` parmi les extensions** de l'itinéraire (`RoutePlanGpx.attach` / `planOf`), dans `extrasJson` en base comme dans le fichier GPX exporté. Pas de migration Room. `planOf` ne rend le plan que s'il colle à la géométrie enregistrée (mêmes points) : un itinéraire retouché par une autre application est relu comme un itinéraire à la main. Un itinéraire entièrement à la main est enregistré **sans** plan (ses points sont ses étapes, comme avant P19).
- **Conséquences** : une seule forme du plan (base, export, import) ; aucune migration à tester ; les sauvegardes (P9.6) et les exports gardent le plan sans code en plus. Le plan est relu (décodage JSON + XML) à l'ouverture de l'éditeur, à l'affichage des repères et sur la fiche, ce qui reste léger pour des itinéraires de quelques centaines d'étapes. Au passage : le réglage qu'ADR-064 appelle `routingNoDataMode` s'appelle `placemarks.routingNoData` dans le code.


## ADR-066 — Certificats : autorités installées par l'utilisateur au lieu de l'option `insecure`

- **Date / statut** : 2026-10-21 — Accepté (session 49, variante C de la décision 2 de `docs/previews/p14-fonctions.html`, validée par l'utilisateur le 2026-10-20). **Remplace** la ligne `@insecure` « ✅ (avec avertissement) » de `aqx-format.md` et la dette de P4.3 ; complète ADR-017 (trafic en clair).
- **Contexte** : l'assistant d'ajout de carte avait un interrupteur « Accepter un certificat HTTPS invalide » (`insecure`, repris d'AlpineQuest), enregistré et exporté mais jamais appliqué à OkHttp (`[S2]`). L'appliquer demandait un client qui accepte tout certificat pour cette source : Google Play refuse les applications qui le font, et une erreur de réglage aurait exposé les clés d'API. Le besoin réel est celui des serveurs WMS d'entreprise ou d'administration, signés par une autorité que le téléphone ne connaît pas.
- **Décision** :
  1. `app/src/main/res/xml/network_security_config.xml` (référencé par le manifeste) : `base-config` avec `cleartextTrafficPermitted="true"` (ADR-017 inchangé) et deux `trust-anchors` : `system` et `user`. Une autorité que l'utilisateur installe dans Android (Réglages ▸ Sécurité ▸ Chiffrement et identifiants) est donc acceptée par la carte, l'assistant et les téléchargements.
  2. L'interrupteur est retiré de l'assistant et `DraftIssue.INSECURE_TLS` disparaît. Le champ `insecure` d'un `.aqx` importé est **gardé tel quel** (modification et export, pour qu'un aller-retour vers AlpineQuest ne le perde pas), jamais appliqué.
  3. L'étape de test nomme l'erreur : `TileProbe` rend `ProbeVerdict.CERTIFICATE_REJECTED` pour une chaîne de certificats refusée (`CertPathValidatorException`, « Trust anchor for certification path », `SSLPeerUnverifiedException`, certificat expiré ou pas encore valide, échec de poignée de main lié au certificat), avec le message « Certificat du serveur refusé » et la marche à suivre.
- **Conséquences** : la vérification TLS n'est jamais désactivée. Un serveur à certificat auto-signé marche une fois son certificat installé comme autorité. Une autorité installée par l'utilisateur vaut pour toute l'application (c'est l'avertissement de lint `AcceptsUserCertificates`, accepté ici). Un `.aqx` qui comptait sur `insecure` pour un certificat expiré ne marche plus : c'est voulu.
- **Alternatives rejetées** : appliquer `insecure` par un client dédié (A, refus de Google Play, risque pour les clés) ; retirer l'interrupteur sans rien d'autre (B, laisse sans solution les serveurs privés).

## ADR-067 — Fonctions P14 : cartes fournies masquées dans les réglages, mode nuit par la luminosité, atlas le long d'une trace sans page d'assemblage

- **Date / statut** : 2026-10-21 — Accepté (session 49, décisions 3, 8, 10 et 11 de `docs/previews/p14-fonctions.html`, validées par l'utilisateur le 2026-10-20). **Précise** ADR-061 point 5 (l'atlas « reviendra depuis la fiche d'une trace ») et ADR-052 (favoris fournis) sans les contredire.
- **Contexte** : les aperçus validés fixent ce que l'on voit ; restaient des choix de mise en œuvre : où garder les cartes fournies masquées, comment assombrir la carte, ce que devient la page d'assemblage d'un atlas le long d'une trace, où garder le tri et le filtre de l'explorateur.
- **Décision** :
  1. **Cartes fournies masquées** (`[F3]`) : identifiants dans `AppSettings.hiddenMaps`, donc dans les réglages et la sauvegarde (P9.6) sans fichier à part (contrairement aux favoris fournis d'ADR-052, rangés avec les espaces de travail). `MapCatalog.groups` les écarte, sauf « Afficher » (grisées) ; une carte de l'utilisateur n'est jamais masquée (elle se supprime). Sur la carte, une carte masquée encore dans la pile est ignorée comme une carte manquante (« Cartes indisponibles ignorées »).
  2. **Mode nuit** (`[F14]`, variante B) : `AppSettings.nightMode` et `nightPercent` (20 à 90 %, pas de 10, défaut 60) ; `StackToStyle` multiplie `raster-brightness-min` et `-max` de chaque carte raster par `1 − intensité`, en plus de son réglage propre (inversion comprise). Les traces, waypoints, menus et cartes vectorielles ne changent pas ; le thème de l'application non plus.
  3. **Atlas le long d'une trace** (`[F9]`) : les maths existantes (`Atlas`, `:core`) placent les pages ; `PrintPlan` n'ajoute **pas** de page d'assemblage hors du mode grille (`AtlasMode.GRID`) : la trace et les numéros suffisent, et le PDF reste « une page par cadre ». Les cadres ne se déplacent pas à la main ; la carte cadre l'ensemble (`PrintViewport.fit`).
  4. **Explorateur** (`[F7]`) : tri et filtre gardés dans `AppSettings.library` (`LibraryViewSettings`) d'une ouverture à l'autre ; la recherche ne l'est pas. L'ordre, le filtre et la recherche (tous les mots, sans casse ni accents) sont des fonctions pures (`LibraryOrder`, `:core`, testées en JVM) ; les dossiers restent en tête.
- **Conséquences** : la liste des cartes masquées voyage avec la sauvegarde des réglages ; une sauvegarde plus ancienne les fait revenir. Le mode nuit ne coûte rien au rendu (deux propriétés de style). Un atlas le long d'une boucle se recouvre beaucoup (pages dans l'ordre de la marche) : connu, accepté pour cette version.

## ADR-068 — Cartes selon l'altitude : bande par calque, masque calculé dans l'intercepteur, condition ET / OU avec la plage de zoom

- **Date / statut** : 2026-10-23 — Accepté (session 50, aperçu `docs/previews/p20-elevation.html` validé par l'utilisateur le 2026-10-23, variante A). **Étend** ADR-063 (plages de zoom des calques) sans le contredire. Spec : `docs/specs/elevation-maps.md`.
- **Contexte** : l'utilisateur veut changer de carte selon l'altitude (OpenTopoMap sous 1500 m, OpenSnowMap au-dessus), réglable en direct et hors ligne si le calcul le permet, et réglé au même endroit que la plage de zoom, les deux conditions se combinant par ET / OU. MapLibre n'a pas d'expression qui découpe une couche raster selon une autre (altitudes) ; un rendu par carte « fusionnée » obligerait à stocker une carte par seuil.
- **Décision** :
  1. **Une bande d'altitude par calque** (`LayerConfig.displayMinElevation` / `displayMaxElevation`, `rangeLogic` ET / OU), pas de carte nouvelle : la pile existante fait le reste (le calque masqué laisse voir celui du dessous).
  2. **Masque calculé dans l'intercepteur de tuiles** (`TileInterceptor.masked`, `BitmapElevationMasker`, maths dans `:core` `MaskRaster`), sur les tuiles d'altitudes Terrarium au zoom `min(z, 13)` (`DemWindow.MAX_DEM_ZOOM`, déjà celui des altitudes des traces, ADR-018 point 7) : une tuile hors de la bande n'est pas lue (404), une tuile toute dedans passe sans recodage, seule une tuile à cheval est découpée (PNG, jamais stockée).
  3. **La bande voyage dans l'URL des tuiles** : id de source du style `carte?alt=min,max[&whole=a,b]` (`StyleSources`). Une nouvelle bande est une nouvelle source MapLibre, qui redemande ses tuiles sans vider le reste du cache ; aucune autre voie (état partagé avec l'intercepteur) n'est nécessaire.
  4. **ET / OU** : ET garde la plage de zoom en `minzoom` / `maxzoom` de la couche ; OU les retire et fait dessiner les tuiles entières aux zooms de la plage (`whole`), masquées ailleurs (au zoom de tuile entier).
  5. **Anti-flash** : à un changement de bande, l'ancien calque reste dessous en « fantôme » (`tm-g-<id>`, `BandSwap`) jusqu'à ce que la carte redevienne immobile après une image dessinée.
  6. **Réglage au relâcher** des curseurs (pas pendant le glissement), dans un panneau non modal en bas de la carte (« Conditions d'affichage », qui remplace « Plage de zoom »).
  7. **Hors ligne** : la sélection de zone ajoute une ligne « Altitudes » (tuiles `terrarium` des zooms des cartes à bande jusqu'à 13, `ZoneRequest.zoomLimits`) ; `MapLibre.setConnected(true)` au démarrage, sinon MapLibre ne demande plus de tuiles en mode avion alors que l'intercepteur sait servir le stockage.
- **Conséquences** :
  - Coût mesuré sur l'émulateur (spec §5) : 3 à 6 ms de masque et 37 à 100 ms de recodage PNG par tuile découpée ; un seuil changé redessine l'écran en 0,3 à 0,45 s depuis le stockage, 2,6 à 3,1 s par le réseau. Le recodage PNG est le poste à réduire si un téléphone est trop lent (WebP sans perte, cache des tuiles découpées).
  - Sans altitudes stockées, hors ligne, un calque à bande n'affiche rien (la carte du dessous reste).
  - Limite juste à un demi-pixel d'altitudes près (≈ 7 m au zoom 13) ; OU change de dessin au zoom de tuile entier.
  - La bande suit l'état de la carte, la sauvegarde et les favoris (`WorkspaceLayer`) ; les anciens fichiers se lisent sans bande.

## ADR-069 — Cartes de ski intégrées sous les ids des fichiers AQX de l'utilisateur ; favori « Sortie Ski » ; favoris fournis réduits à deux

- **Date / statut** : 2026-10-23 — Accepté (session 51). **Modifie** la liste des favoris fournis d'ADR-052 et d'ADR-063 ; leurs décisions de fond restent (favoris fournis supprimables et rétablissables, Rando Multi-Zoom en cinq cartes empilées). Aperçu : la capture « Cartes affichées » de l'utilisateur du 2026-10-23 (sa configuration réelle, reproduite sur l'émulateur).
- **Contexte** : l'utilisateur a monté sa configuration de ski avec les bandes d'altitude (ADR-068) à partir de deux fichiers AQX : `COM.109.AQX` (OpenSnowMap : fond « SnowBase Map » et calque « Pistes Layer ») et `USR.IGN+PENTES+YETI_TRANSITOIRE 2024_SANS GEOKEY.AQX` (traces de ski de randonnée hivernales Yeti, WMS de l'ENSG). Il veut la retrouver en favori fourni, et ne garder que « Rando Multi-Zoom » et « Sortie Ski » comme favoris par défaut.
- **Décision** :
  1. Trois cartes ajoutées au catalogue intégré (`builtin-sources.aqx`) **sous les ids de ses fichiers** : `OSMSNOWM` (SnowBase Map), `OSMSKIM` (Pistes Layer, calque), `TRACESHIVERNALES` (Traces_ski_yeti_api, calque). Une copie importée du même id est remplacée par la carte intégrée (`SourceRepository`), et les piles, sauvegardes et favoris qui la nomment la retrouvent sans conversion (même voie qu'ADR-052 pour Rando Multi-Zoom).
  2. Serveurs en https (les fichiers disent http), 2 connexions et agent utilisateur ToolMap (serveurs bénévoles) ; OpenSnowMap reçoit son referer `http://www.opensnowmap.org/`, sans lequel le fond répond « access denied ». Zooms des fichiers gardés : `3,5,7-17` (OpenSnowMap), `3,5,7-16` (Yeti, WMS demandé en mètres Web Mercator).
  3. Favori fourni **« Sortie Ski »** (`builtin-sortie-ski`), du bas vers le haut : IGN SCAN 25 Touristique (100 %), SnowBase Map au-dessus de 1550 m, Pistes Layer à partir du zoom 12, Traces_ski_yeti_api à partir du zoom 12, IGN Pentes (montagne) à 30 % à partir du zoom 12 ; condition ET partout.
  4. `Workspaces.builtin` = « Rando Multi-Zoom », « Sortie Ski ». Les favoris fournis « IGN Topo 8–16, Plan 1–7, Pentes », « OpenStreetMap », « OpenTopoMap » et « Photos aériennes » sont retirés ; le premier reste comme exemple dans les tests (`TestWorkspaces.kt`).
  5. Tuiles déjà téléchargées : le partage des tuiles (ADR-031) range une carte importée dans `t-<hash>`, une carte intégrée sous son id. `StorageKeys.takenOverByBuiltins` et `StorageManager.mergeTakenOverStorages()` (au démarrage, avec les autres migrations du stockage) versent ces tuiles dans le stockage de la carte intégrée, sans écraser celles qui y sont, puis suppriment l'ancien fichier. Toute carte ajoutée plus tard au catalogue en profite.
- **Conséquences** :
  - Les trois cartes quittent « Mes cartes » : OpenSnowMap va dans « Cartes OpenStreetMap », les traces Yeti dans « Autres cartes ». Les autres cartes des fichiers de l'utilisateur (IGN, pentes) restent importées.
  - Les favoris fournis retirés disparaissent de la rangée, même pour qui s'en servait ; les favoris personnels ne sont pas touchés, et un id retiré resté dans `hidden-builtins.txt` est sans effet.
  - Les serveurs OpenSnowMap et ENSG sont tenus par des bénévoles et des écoles : un changement d'adresse casse le favori jusqu'à une mise à jour du catalogue.

## ADR-070 — Journal d'événements dans tous les builds ; outils de test en direct dans un module `:debugkit` réservé au debug, journal envoyé par le groupe chiffré

- **Date / statut** : 2026-10-23 — Accepté (session 52). Spec : `docs/specs/event-log.md`, protocole : `docs/specs/live-test.md`. N'ajoute aucune dépendance (pas de Firebase SDK, ADR-033 inchangé) ; ajoute le message `DIAG` au protocole du maillage (P8.5).
- **Contexte** : l'utilisateur veut tester le partage de position en direct (lui sur son téléphone en marchant, Claude sur l'émulateur), voir en temps réel tout ce qui se passe sur le téléphone, et disposer d'un journal de tout ce que l'application fait et subit. Les outils de test, lourds et gourmands, ne doivent pas apparaître dans une release.
- **Décision** :
  1. **Journal d'événements dans `:core`** (`EventLog`, `EventStore`, `EventLine`) : une ligne texte par événement, numérotée, dans `files/eventlog` (deux fichiers tournants, époque tirée au hasard), écrite par un fil à part ; deux niveaux (`event` toujours, `detail` seulement en mode test) ; jauges lues à la demande. Présent dans tous les builds, léger par défaut ; coordonnées seulement en mode test.
  2. **Sources** : l'application (`SystemEvents` : processus, raisons d'arrêt, activités, mémoire, écran, alimentation, doze, économie, localisation, réseau, permissions), le partage (`ShareController`, `ShareService`, `LocationSource`), le maillage (`MeshNode`), le serveur (`FirebaseRelay`, `FirebaseRest`), WebRTC, les plantages (`CrashReporter` vide le journal avant de mourir).
  3. **Module `:debugkit`** (bibliothèque Android, `debugImplementation`) : rôle collecteur, mode test, instantanés, suivi GNSS, commandes `adb` (récepteur protégé par `DUMP`). Branché par un `Set<AppExtension>` Hilt (`@Multibinds` dans l'app, `@IntoSet` dans le module) : vide en release, sans code de test dans l'APK.
  4. **Le journal voyage par le groupe** : message `DIAG` (type 13) chiffré avec la clé du groupe, sur le canal fiable, relayé par le maillage, ≤ 4096 octets (trame de 5,5 kio en Base64, sous la limite de 6000 des règles Firebase : **aucune règle à changer**). Pas de canal de débogage à part sur le serveur, rien en clair.
  5. **Fiabilité de bout en bout par accusés** : le collecteur annonce toutes les 5 s ce qu'il a de chaque membre ; il refuse une trame qui ne suit pas ; l'émetteur renvoie depuis le dernier accusé après 15 s sans progrès ; fenêtre de 4000 lignes ; marques `# gap` / `# log` pour les lignes perdues sur le téléphone et les journaux nouveaux.
  6. **Les positions d'abord** : une trame de journal au plus par seconde, et aucune tant qu'une écriture au serveur attend (`RelayTransport.pendingWrites`, `DiagChannel.busy`).
  7. **Mode test déclenché par le collecteur** (30 min après la dernière annonce, gardé au redémarrage) : un APK de debug sans collecteur reste léger.
- **Conséquences** :
  - Un test en direct coûte au téléphone ≈ 1 trame de 5,5 kio par seconde vers le serveur, un réveil par seconde, des instantanés toutes les 5 s et le suivi GNSS : les mesures de batterie faites pendant un test sont celles du build de debug en test.
  - Les membres d'un groupe de test reçoivent le journal des autres (chiffré, groupe de test dédié) ; un membre en release le relaie sans le lire.
  - Les défauts soupçonnés (une trame PING qui remplace une position pas encore écrite, `relay.p.replaced`) sont d'abord mesurés pendant les tests, puis corrigés dans des tâches P21 suivantes.

## ADR-071 — Alertes du partage : les réglages du téléphone qui le coupent sont dits à l'utilisateur

- **Date / statut** : 2026-10-24 — Accepté (session 54 ; demande de l'utilisateur après le second test en direct :
  « ajoute des alertes pour les points qui ne peuvent pas être corrigés via l'app », exemple de l'économiseur de
  batterie ; variante B de `docs/previews/p21-alertes.html` validée le 2026-10-24). **Remplace** le point 4 d'ADR-051
  (carte « Partage téléphone verrouillé » et conseil Samsung).
- **Contexte** : au second test, l'économiseur de batterie de One UI coupait le GPS à chaque extinction de l'écran,
  service de premier plan compris (défaut o) ; la localisation coupée n'était pas signalée non plus (c). L'appli ne peut
  changer aucun de ces réglages.
- **Décision** :
  1. `PhoneChecks` (`share/ShareAlerts.kt`) lit, dans l'ordre de gravité : localisation coupée (rouge : rien ne part),
     économiseur de batterie, batterie de l'appli optimisée (orange : le partage peut s'arrêter). `ShareController`
     les relit toutes les 5 s dans un groupe (son `tick`) et à chaque retour sur l'écran Partage ; événement
     `share.alerts` à chaque changement.
  2. Quatre endroits : une carte par réglage sous l'interrupteur, avec un bouton vers l'écran des réglages qui la règle
     (demande d'exemption d'Android pour la batterie) ; un dialogue à l'allumage du partage, qui ne l'empêche pas ; la
     pastille de la carte en orange avec ⚠ ; une 2e ligne dans la notification pour le réglage le plus grave.
  3. Couleurs : rouge du thème (`errorContainer`) ; orange fixe (clair et sombre), pas de couleur du thème qui convienne.
- **Alternatives rejetées** : un simple message à l'allumage (oublié ensuite, et l'économiseur s'allume souvent après) ;
  la notification seule (cachée si les notifications sont refusées) ; garder le conseil Samsung (le nom des réglages
  change selon la version de One UI ; la demande d'exemption d'Android suffit pour « Sans restriction »).
- **Conséquences** : une lecture de trois réglages toutes les 5 s dans un groupe (coût négligeable). Restent à
  valider sur un aperçu : plus de fix GPS (c), économiseur de données, batterie « restreinte » ; et le dire aux autres
  membres (o, point 3).

## ADR-072 — P21 clos : `:debugkit` sorti du build, plus dans aucun APK

- **Date / statut** : 2026-10-24 — Accepté (session 56 ; demande de l'utilisateur : « Retire :debugkit de
  l'application et republie-le sans le kit de debug », puis « Tu peux clore tout le P21 »). **Remplace** le point 3
  d'ADR-070 (module `:debugkit` dans l'APK de debug) ; les points 1, 2 et 4 à 7 restent : le journal d'événements est
  dans tous les builds, le message `DIAG` reste dans le protocole (un membre le relaie sans le lire).
- **Contexte** : les deux tests en direct sont faits, la phase P21 est close. L'APK de debug que l'utilisateur
  installe ne doit plus porter les outils de test (collecteur, mode test, commandes `adb`, écran gardé allumé).
- **Décision** :
  1. `settings.gradle.kts` n'inclut plus `:debugkit` et `app/build.gradle.kts` ne le prend plus
     (`debugImplementation` retiré, module retiré de l'empreinte des sources de `version.properties`). L'APK de debug
     se comporte comme une release pour les extensions : le `Set<AppExtension>` (`@Multibinds`) reste vide.
  2. Les sources `debugkit/` et les scripts `tools/livetest/` restent dans le projet, débranchés : sur le poste
     Windows, le dossier n'est pas suivi par git et le miroir Drive efface ce qui est effacé en local.
  3. Pour un nouveau test en direct : `include(":debugkit")` dans `settings.gradle.kts` et
     `debugImplementation(project(":debugkit"))` dans `app/build.gradle.kts` (plus `"debugkit"` dans la liste des
     modules de `appSourceFingerprint`), puis `live-test.md`.
- **Alternatives rejetées** : supprimer le dossier (perte sans retour sur le poste local) ; garder le module dans le
  build sans le donner à l'app (compilé et testé à chaque `check` pour rien).
- **Conséquences** : l'APK de debug ne répond plus aux commandes de `tools/livetest/` (`cmd.sh`, `setup.sh`) et ne
  passe jamais en mode test ; les sources de `debugkit/` ne sont plus compilées : elles peuvent prendre du retard sur
  `:core` et demander une remise à niveau quand on les rebranche.

## ADR-073 — Grille UTM / MGRS à l'écran : une grille par zone, noms des zones et des carrés, convergence dans la boîte

- **Date / statut** : 2026-10-26 — Accepté (sessions 59 et 60, P24.2 et P24.3 ; aperçu `docs/previews/p24-mgrs.html`
  validé par l'utilisateur le 2026-10-26, variante A au dézoom). **Remplace** au point 4 d'ADR-022 « Pas de **texte**
  sur la carte » (pour la grille) et « grille de la zone du centre », ainsi que le « trait épais tous les 100 km » de
  MGRS et la note `TOO_WIDE` pour UTM et MGRS. Le reste d'ADR-022 est inchangé (pas, 40 lignes au plus, calcul pur
  hors du fil principal, WGS84 et Lambert 93 sans texte).
- **Contexte** : images de l'utilisateur (Pontcharra, 2026-10-25) : toute la grille tournait d'environ 4° quand le
  centre passait 6° E (31T → 32T), et la ligne proche du centre disparaissait au dézoom de 5 à 10 km. Sans nom à
  l'écran, rien ne disait dans quelle zone ni dans quel carré de 100 km on lisait la grille. Les glyphes servis sur le
  téléphone (ADR-062) permettent désormais d'écrire sur la carte.
- **Décision** :
  1. **Une grille par zone** (`ScreenGrid`, `:core`) : chaque zone de grille visible (bandes d'une même zone jointes,
     exceptions de Norvège et du Svalbard comprises, `GridZones`) trace sa grille, coupée à sa limite ; les limites des
     zones et des bandes sont des lignes à part (`GridLineLevel.ZONE_EDGE`), dessinées à tous les zooms dans un calque
     plein (#1c2429, 3,2 dp). La grille garde l'hémisphère du centre (continue à l'équateur) ; un écran qui passe
     l'antiméridien dessine la zone 1 au-delà de 180°.
  2. **Pas et niveaux (variante A)** : le pas reste choisi comme avant sur la zone du centre (≈ 6 lignes), plafonné à
     100 km ; les lignes de 10 et de 100 km sont plus marquées à tous les pas (0,8 / 1,5 / 2,3 dp), si bien qu'au
     dézoom seules les lignes fines partent. Vue trop large pour des lignes : limites et noms seuls, sans puce ni note.
     L'UTM sous 1 km (hors aperçu) garde une ligne sur cinq plus épaisse.
  3. **Noms** (`GridLabelKind`, source `tm-g-grid-labels`) : l'écran est échantillonné (13 points sur le petit côté) ;
     une zone qui ne montre qu'un carré est nommée avec lui (« 31T GL ») au-dessus du milieu du carré ; sinon, la zone
     (« 31T ») au milieu de sa partie de l'écran et chaque carré qui en couvre assez (6 échantillons sur 325) par ses
     lettres sous son milieu. UTM : les zones seules. Texte gris foncé détouré de blanc, 12 dp (14 pour une zone),
     police du téléphone d'ADR-062 ; un nom de la grille ne cache jamais le nom d'un waypoint ou d'un membre
     (`text-ignore-placement`).
  4. **Convergence** : angle du nord du quadrillage au centre (`GridPlanner.convergenceDegrees(kind, lat, lon)`, le même
     calcul que le cartouche imprimé), écrit sous la puce de la grille dans la boîte Position, « Convergence 2°08′ O »
     (UTM, MGRS, Lambert 93 en France), dans le style de la puce : l'aperçu la montrait en jaune sur une boîte sombre,
     la boîte de l'appli est claire.
- **Conséquences** : tests `ScreenGridTest` (deux zones à l'écran, limite à tous les pas, dézoom, vue large, Norvège,
  équateur, antiméridien, noms, convergence). Les extrémités coupées à une limite sont interpolées entre deux
  échantillons (quelques mètres sur une vue de plusieurs centaines de km, un centième de pixel). Deux noms de la grille
  peuvent se chevaucher sur une vue très large ; ils peuvent passer sur le nom d'un waypoint sans le cacher.
- **Alternatives rejetées** : grille de la zone du centre prolongée (l'existant, qui saute à la jonction) ; pas de 1,
  10, 100 km seulement (variante B, grille trop serrée au dézoom) ; noms dans la boîte au lieu de la carte (ne dit pas
  où passe la jonction) ; noms de carré en UTM (les références UTM n'ont pas de lettres).

## ADR-074 — Style « Crête » : palette ToolMap fixe par défaut, trois arrondis, une barre du haut et une barre du bas communes

- **Date / statut** : 2026-10-26 — Accepté (session 63, P29.0 ; aperçu `docs/previews/design-00-style.html`, variante
  E · Crête, choisie par l'utilisateur le 2026-10-26). **Remplace** au point 6 d'ADR-022 les « couleurs dynamiques »
  comme valeur par défaut (elles restent un réglage, éteint par défaut) ; complète ADR-060 (parties communes des
  panneaux) sans le contredire.
- **Contexte** : audit de P29 (`docs/plan-design-2026-10.md` §2) : sur Android 12 et plus, la palette venait du fond
  d'écran (lavande sur l'émulateur), donc l'appli changeait de couleur d'un téléphone à l'autre ; neuf arrondis, des
  « Fermer » en texte et des ✕ selon l'écran, des surtitres en capitales que les guides d'impeccable refusent.
- **Décision** :
  1. **Palette fixe** (`ui/theme/Theme.kt`) : bleu pétrole #163A4A (primaire, coque), turquoise #14918C (ce qui est
     allumé : interrupteurs, pastille « Affichée »), fonds #F2F5F6, panneaux et listes blancs, tuiles #E2ECEF, texte
     #122027 / #506169. Thème de nuit dessiné à part : pétrole profond (#0C1A20), turquoise clair (#7FD6D1). Les couleurs
     hors du schéma Material (coque, pastille de la barre, tuiles, boîtes d'information) sont dans `ToolMapColors`
     (`MaterialTheme.toolMap`).
  2. **Réglage renommé** : `AppSettings.dynamicColor = true` devient `wallpaperColors = false`. Les réglages sont
     écrits avec leurs valeurs par défaut (`encodeDefaults`) : changer la seule valeur par défaut aurait laissé
     les téléphones existants sur le fond d'écran. L'ancienne clé est ignorée à la lecture (`ignoreUnknownKeys`) :
     chaque téléphone passe une fois à la palette ToolMap ; l'utilisateur peut rallumer « Couleurs du fond d'écran ».
  3. **Trois arrondis** (`Shapes`) : 12 dp (boutons, champs, menus, puces), 14 dp (cartes, tuiles, bouton flottant),
     18 dp (panneaux, feuilles, dialogues). Les boutons de Material restant des pilules quel que soit le thème, des
     enveloppes de même signature (`ui/theme/Controls.kt` : `Button`, `OutlinedButton` à contour pétrole de 1,5 dp,
     `FilledTonalButton`, `TextButton`, `Switch` turquoise) remplacent ceux de Material par les seuls imports.
  4. **Barre du haut commune** (`ui/ToolMapTopBar`) sur les 13 écrans plein écran : bandeau pétrole jusque sous la
     barre d'état (icônes de la barre d'état claires tant qu'il est affiché), flèche Retour, titre et sous-titre, actions
     en icônes (« Enregistrer » de l'éditeur multi-zoom reste en mots ; ✕ pour annuler un éditeur).
  5. **Barre du bas** pleine largeur, pétrole, jusqu'au bord bas de l'écran (`Modifier.bleed` à travers la marge de
     l'habillage), nom sous chaque icône, pastille turquoise clair sur l'élément dont le panneau est ouvert. La
     boussole passe dans la colonne de droite. **Panneaux** : bandeau pétrole fixe en tête (`PanelBand`), plus de
     surtitre en capitales (`StateCard` met le sujet dans la ligne grise : « Carte affichée · IGN Cartes »).
- **Conséquences** : une seule identité dans les deux thèmes ; la barre nommée prend environ 76 dp de carte en bas.
  Les étapes P29.1 à P29.10 réorganisent chaque onglet dans ce style. Restent hors de cette étape (repris par leur
  étape) : couleurs écrites en dur dans 8 fichiers, en-têtes de la feuille « Cartes affichées » et du panneau de zone,
  favoris en lettres.
- **Alternatives rejetées** : variantes A Forêt, B TOP 25, C Balise, D Altimètre de l'aperçu ; garder la valeur par
  défaut seule (sans effet sur les téléphones existants) ; `NavigationBar` de Material (pas d'appui long pour la
  position, hauteur et marges imposées).

## ADR-075 — Menu principal « Portée » : les tuiles en pied fixe, « Quitter » en haut, « Plus » déplié sur place
- **Date / statut** : 2026-10-27 — Accepté (P29.2 ; aperçu `docs/previews/design-02-menu.html`, variante B · Portée,
  choisie par l'utilisateur le 2026-10-27). **Remplace** le premier point d'ADR-053 sur la place de « Quitter »
  (« une ligne Quitter après Réglages… », placée en bas en P14.10) ; le reste d'ADR-053 (Quitter ne coupe rien, le
  dialogue prévient de ce qui continue) est gardé et précisé.
- **Contexte** : audit de P29.2 : le panneau montait à environ 500 dp avec 11 cibles ; les quatre tuiles fréquentes
  étaient tout en haut, loin du pouce, et « Quitter », la commande la plus rare et la seule qui ferme l'appli, était
  la plus proche de la barre ; la page « Plus… » ajoutait un niveau pour deux lignes, et son Retour système fermait
  tout le menu.
- **Décision** :
  1. **Pied fixe** : les tuiles Masquer, Verrouiller, Imprimer, Mesurer forment le bas du panneau, posé sur la barre ;
     elles ne défilent pas (`BarPanel` reçoit un `footer`). Au-dessus, de bas en haut : Mode nuit, Informations à
     l'écran, le bloc des cartes affichées, « Réglages… ».
  2. **Haut du panneau, loin du doigt** : la ligne « Plus » (repliée : « Nouveautés, signaler une erreur ») puis
     « Quitter », qui passe donc avant « Réglages… ».
  3. **« Plus » se déplie sur place** (Nouveautés, Signaler une erreur, Diagnostic des tuiles dans l'APK de debug) ;
     la page « Plus » est supprimée. Retour replie la ligne avant de fermer le menu.
  4. **Dialogue de sortie** : une ligne de liste par service qui tourne (enregistrement, partage, suivi ou alertes),
     avec ses chiffres, puis le bouton de la notification de ToolMap qui l'arrête (`map/ExitDialog.kt`).
  5. Corrections communes de l'aperçu : interrupteurs lus comme tels par TalkBack (`MenuRow(switchOn = …)`, rôle
     Switch), sous-titre du mode nuit selon l'état, bloc des cartes sans « Pile personnalisée » (nom du favori, sinon
     les cartes et « sans favori »), icônes Nouveautés (porte-voix) et Signaler une erreur (insecte), logo du mode
     masqué en rond blanc comme le zoom, pastille « Écran verrouillé » à la forme des pastilles d'information, tuile
     « Afficher » (jamais visible) retirée.
- **Conséquences** : le fréquent est à portée de pouce et la tuile visée tombe toujours au même endroit, même en mode
  gants ; l'ordre de lecture est inversé (le rare se lit en premier sous le bandeau) et s'éloigne d'AlpineQuest, ce que
  l'utilisateur a accepté en choisissant B. En mode gants, la partie qui défile a peu de place.
- **Alternatives rejetées** : variantes A Cairn, C Damier, D Soufflet, E Versants de l'aperçu.

## ADR-076 — « Cartes et calques » en « Strates » : la pile dans le panneau, lignes dépliables, glisser pour réordonner
- **Date / statut** : 2026-10-27 — Accepté (P29.3 ; aperçu `docs/previews/design-03-cartes.html`, variante C · Strates,
  choisie par l'utilisateur le 2026-10-27). **Revient** sur deux points écartés en P14
  (`docs/specs/ui-reorganization.md` : pas de glisser-déposer des calques) et remplace la feuille « Cartes affichées »
  (P11.2, P14.10) ; complète ADR-060 (le panneau Cartes reste le seul endroit où l'on applique, enregistre et supprime
  un favori) et ADR-068 (le panneau « Conditions d'affichage » garde sa fonction, en version compacte).
- **Contexte** : audit de P29.3 : une même tâche passait par trois contenants (panneau, feuille modale, panneau des
  conditions), trois en-têtes et deux « ? » ; la feuille modale voilait la carte pendant un réglage visuel ; le menu ⋮
  d'un calque alignait 10 commandes ; fermer les conditions ramenait à la carte nue ; monter un calque de trois rangs
  demandait 6 touchers ; les favoris sans vignette montraient des lettres sur un pastel.
- **Décision** :
  1. La feuille `DisplayedMapsSheet` et `MapsPage.DISPLAYED` disparaissent : la pile est le contenu du panneau, du
     dessus vers le dessous, une ligne par calque (poignée, vignette, nom, état, flèche, œil).
  2. Toucher une ligne la déplie sur place (une à la fois) : opacité, Monter · Descendre · Conditions · Couleurs ·
     Plus ; Couleurs se déplie en dessous (contraste, luminosité, saturation, couleurs inversées, réinitialiser) ; Plus
     garde Détails, Forcer la mise à jour (cette session), Ne plus afficher, Ne plus afficher les autres. « Opacité de
     la carte » et « Couleurs inversées » du ⋮, doublons, sont retirés.
  3. La poignée se fait glisser pour réordonner (le calque change de place à chaque demi-ligne) ; TalkBack garde
     Monter et Descendre en actions de la poignée.
  4. Bandeau : favori appliqué (ou la seule carte), nombre de cartes, et combien sont dessinées au zoom de la carte
     (`LayerElevation.isDrawnAt`) ; la vignette d'un calque dessiné est cerclée de turquoise, celle d'un calque
     inactif atténuée. Sans favori et avec plusieurs cartes, le bandeau dit seulement leur nombre : pas de « Pile
     personnalisée », comme le menu principal depuis P29.2 (ADR-075).
  5. Favoris en puces (vignette ronde de la carte de fond du dessus) ; appliquer laisse le panneau ouvert ; appui long :
     Appliquer · Supprimer ; la suppression se fait aussitôt, avec « Annuler » dans la snackbar
     (`WorkspaceStore.unhide`, `MapViewModel.restoreWorkspace`) ; « Rétablir (n) » en dernière puce.
  6. Pied : « Hors ligne » et « Couverture », deux interrupteurs de même forme ; quatre tuiles Cartes disponibles,
     Enregistrer une zone, Téléchargements, Stockage. « Importer .aqx » quitte le panneau (il reste dans Cartes
     disponibles ▸ Ajouter une carte, ADR-039).
  7. Conditions compactes : bandeau pétrole, barres en ligne avec leur libellé, Réinitialiser en icône, ET / OU et sa
     phrase seulement si zoom et altitude sont posés ; ✕ et Retour ramènent au panneau, la ligne du calque toujours
     dépliée (`MapsViewModel.expandedLayer`). La phrase « La carte se redessine… » passe dans l'aide.
  8. Les vignettes sans image montrent l'icône de carte sur la couleur des tuiles du thème, plus d'initiales.
- **Conséquences** : un toucher de moins pour tout réglage de calque ; la carte reste nette pendant les réglages ;
  l'organisation s'éloigne d'AlpineQuest (plus de « Cartes affichées » à part, appliquer un favori ne ferme plus le
  panneau), ce que l'utilisateur a accepté en choisissant C ; le glisser-déposer est du code maison (pas de liste
  paresseuse : la pile est courte).
- **Alternatives rejetées** : variantes A Rangement, B Planche, D Classeur, E Étagement de l'aperçu.

## ADR-077 — « Traces et waypoints » : liste et détail en « Topo », fiche en « Repère », « Waypoint ici » en un appui
- **Date / statut** : 2026-10-27 — Accepté (P29.4 ; aperçu `docs/previews/design-04-traces.html`, choix de
  l'utilisateur le 2026-10-27 : **A · Topo** pour le panneau du drapeau, l'écran Traces et le détail d'une trace,
  **B · Repère** pour la fiche d'un élément touché et le waypoint en un appui). **Revient** sur l'habitude
  d'AlpineQuest reprise jusqu'ici (« Waypoint ici » ouvrait toujours le dialogue) ; complète ADR-043 et ADR-060 (le
  panneau du drapeau garde sa place et ses fonctions) et ADR-056 (la marque de carte d'un waypoint est sa pastille de
  catégorie, partout).
- **Contexte** : audit de P29.4 : l'écran Traces vide n'enseignait rien ; un même appui ouvrait le détail d'une trace
  mais ne faisait que cadrer la carte sur un itinéraire ou un waypoint ; une case anonyme disait la visibilité ; un point
  rouge fixe marquait tous les waypoints ; le détail empilait 14 lignes de même poids et 10 boutons pleine largeur, le
  profil sans axe ni légende ; la fiche mettait sept commandes au même rang ; poser un waypoint demandait toujours un
  dialogue.
- **Décision** :
  1. Panneau (A) : libellés en verbe (« Dessiner une trace », « Dessiner un itinéraire », « Liste des traces et
     waypoints »), champ « Lieu, trace ou waypoint », KMZ cité ; mêmes commandes.
  2. « Waypoint ici » (B) enregistre aussitôt « Waypoint N » au centre, avec l'altitude du relief (3 s d'attente au
     plus) et le symbole du dernier waypoint créé (le drapeau rouge sinon) (`TracksViewModel.quickWaypoint`) ; la
     snackbar « « Waypoint N » enregistré · 442 m » propose « Modifier », qui ouvre le dialogue. L'appui long sur la carte
     et « Ajouter un waypoint » du point central gardent le dialogue : ils visent un point précis, souvent à nommer.
  3. Liste (A) : un bloc blanc groupé ; chaque ligne s'ouvre sur la marque de l'élément sur la carte ; un œil de 48 dp
     (rôle interrupteur) remplace la case et se grise quand un dossier parent masque l'élément ; le mot du type quitte
     la ligne (TalkBack le lit encore) ; un itinéraire ou un waypoint ouvre la carte **avec sa fiche** ; le ⋮ d'un
     waypoint gagne « Modifier… » ; la bibliothèque vide enseigne (import, dessin).
  4. Détail (A) : quatre chiffres clés et le profil (axe, légende des pentes, description TalkBack) d'emblée ; un seul
     bouton plein (Afficher sur la carte) ; « Tous les chiffres » replié ; outils en lignes groupées Préparer · Sur le
     terrain · Modifier ; Renommer, Style, Décrire, Déplacer, Exporter, Supprimer dans le ⋮ de la barre, Partager en
     icône.
  5. Fiche (B) : une action mise en avant (Aller vers ce point, sous une lecture en grand de la distance et du cap ;
     Suivre cette trace, sous son petit profil ; Suivre cet itinéraire), deux tuiles, Partager · Exporter · Masquer ·
     Supprimer dans son ⋮. Les tuiles d'une trace (Statistiques, Modifier le tracé) et d'un itinéraire (Modifier le
     tracé, Convertir en trace), que l'aperçu ne dessinait pas, reprennent les commandes restantes de la fiche actuelle.
  6. Dialogue (A, que « Modifier » de B ouvre) : bloc position sur la couleur des tuiles, icône Material « terrain » ;
     le sélecteur de symbole s'ouvre sur la catégorie du symbole actuel, rangées de 48 dp, libellés en 12 sp.
- **Conséquences** : poser un waypoint, aller vers un point, suivre une trace : un appui chacun ; un appui malheureux
  sur « Waypoint ici » pose un waypoint (il se supprime depuis le ⋮ de sa fiche) ; « Y aller » à la création passe par
  la fiche ou par « Définir comme cible » du point central ; les composants de liste groupée (`ui/Grouped.kt`) servent
  aux étapes suivantes.
- **Alternatives rejetées** : A seule (le dialogue à chaque waypoint, sept commandes au même rang dans la fiche), B
  seule (bloc du centre, éléments proches et tri par proximité : non retenus pour le panneau et la liste), C Registre,
  D Volet, E Carnet.

## ADR-078 — « Positionnement » en « Sentier » : un seul dialogue de départ, « Reprendre » après un arrêt, calage sous le baromètre
- **Date / statut** : 2026-10-27 — Accepté (P29.5 ; aperçu `docs/previews/design-05-position.html`, variante A · Sentier,
  choisie par l'utilisateur le 2026-10-27). Garde ADR-037 (panneau dans l'ordre d'AlpineQuest, mêmes lignes et mêmes
  interrupteurs) et ADR-053 (Baromètre = même réglage que Réglages ▸ Altitude) ; remplace le dialogue « Quelle
  activité ? » de P11.8 par une partie du dialogue de départ.
- **Contexte** : audit de P29.5 : partir demandait deux dialogues sans montrer le nom final ; un toucher arrêtait
  l'enregistrement sans retour, et le message de fin restait caché sous le panneau ; le panneau marquait
  l'enregistrement du rouge « erreur » de Material et écrivait la distance avant la durée, à l'inverse de la pastille ;
  le calage de l'altitude n'était qu'une ligne de 32 dp du détail de la pastille GPS ; « En attente du GPS… » ne disait
  rien de la recherche ; les refus d'autorisation s'affichaient comme un « Import impossible ».
- **Décision** :
  1. Panneau : une ligne d'aide sous « La carte suit » (ce que fait le mode choisi, ou que choisir un mode allume la
     position) ; « alt. » devant l'altitude ; « En attente du GPS… · N satellites vus » (`GnssSatellites.counts` : vus et
     utilisés) ; l'enregistreur en turquoise avec le point rouge de sa pastille, la durée avant la distance ; « · calée »
     après le baromètre calé ; « Caler l'altitude… » sous le baromètre allumé, grisée tant que la position est coupée.
  2. Départ : un seul dialogue (`RecordStartDialog`) : activité en puces (les 4 dernières, la plus récente choisie
     d'avance, mémorisées par `RecordingViewModel`), « Toutes… » qui déplie les 33, le nom final dans le champ, la
     précision des points renommée (Précise · Normale · Espacée · Économie, mêmes réglages que les profils WALK, BIKE,
     PRECISE, SAVER, dont les noms internes restent) ; « Démarrer » démarre.
  3. Arrêt : toujours un toucher (interrupteur ou pastille) ; le message de fin, désormais composé au-dessus des
     panneaux et placé au-dessus du panneau ouvert (sa hauteur mesurée, pour ne pas couvrir ses dernières lignes),
     propose « Reprendre » : `RecordingController.reopen` rouvre la trace qui vient de finir (chiffres repris,
     nouveau segment), par l'action `ACTION_REOPEN` du service.
  4. Un seul nom, « Caler l'altitude » ; pression au niveau de la mer dans l'unité des réglages ; refus d'autorisation
     sans « Import impossible : » ; durée avant distance aussi dans la notification.
- **Conséquences** : un dialogue de moins au départ ; un arrêt par mégarde se rattrape sans confirmation ; le panneau
  grandit d'une ligne d'aide et d'une sous-ligne ; seul l'enregistrement qui vient de finir peut être repris (un nouveau
  départ l'oublie, comme la fin de l'application).
- **Alternatives rejetées** : variantes B à E de l'aperçu.

## ADR-079 — « Cartes disponibles » en « Bascule » : fond à la radio, calques à l'interrupteur, ajout par une feuille, assistant en 5 étapes
- **Date / statut** : 2026-10-27 — Accepté (P29.6 ; aperçu `docs/previews/design-06-cartes-disponibles.html`, variante
  B · Bascule, choisie par l'utilisateur le 2026-10-27). Garde ADR-017 (assistant : capabilities, brouillon, sonde),
  ADR-031 (cartes multi-zoom par références) et ADR-054 (catalogue en ligne embarqué) ; retire l'étape « Type » de
  l'assistant (N2).
- **Contexte** : audit de P29.6 : l'appui sur une ligne changeait la carte affichée sans « Annuler » ; la jauge
  mettait en avant la place du téléphone ; un menu ⋮ de 9 commandes refaisait l'appui sur la ligne ; « Ajouter une
  carte » ouvrait un menu muet, avec les imports en triple ; le catalogue était fait de Cards, sans icône de type, et
  son message d'ajout sans suite ; l'assistant ne faisait qu'avancer (4 × « Suivant » pour renommer une carte) ;
  l'éditeur multi-zoom perdait la barre des zooms dès la 2e tranche et parlait le langage du fichier `.aqx`.
- **Décision** :
  1. Deux segments « Fonds de carte | Calques ». Une radio désigne le fond le plus bas de la pile (celui que
     `replaceBase` remplace) ; dans « Calques », un interrupteur par calque (et par fond affiché au-dessus du fond) dit
     s'il est dans la pile (`addLayer` / `hideSource`). La ligne entière porte l'état (`selectable` / `toggleable`).
     Afficher une carte d'un appui propose « Annuler » (`MapViewModel.restoreStack`, la pile d'avant).
  2. La barre dit ce que montre la carte (« Fond : IGN Cartes · 1 calque ») ; sa loupe met « Rechercher une carte » à
     la place du titre et cherche aussi dans le catalogue en ligne (`MapCatalog.matches`), avec « Ajouter » ; son ⋮
     porte « Stockage hors ligne… » (poids des tuiles, place libre) et « Afficher les cartes masquées (n) ». La jauge,
     la puce des cartes masquées, la pastille « Affichée » et « Afficher comme fond de carte » disparaissent ; le
     nombre de tuiles passe à droite de « Vider les tuiles stockées ».
  3. « Ajouter une carte » ouvre une feuille : Catalogue en ligne, Adresse de tuiles (XYZ), Service WMTS, Service WMS,
     Nouvelle carte multi-zoom, « Importer un fichier (.aqx, .mbtiles) », chacun avec sa description. Un seul
     sélecteur de fichier : un paquet MBTiles (extension, sinon en-tête SQLite : `UriReader.isMbTiles`) va à l'écran
     Stockage, qui demande où le ranger ; le reste suit l'import `.aqx`.
  4. Catalogue : mêmes segments avec leurs nombres, loupe, fournisseurs en en-têtes, lignes blanches avec l'icône du
     type, « Afficher » dans le message d'ajout, conditions d'usage en pied de liste.
  5. Assistant en 5 étapes (`WizardStep` sans `TYPE`) : il commence à l'Adresse du type choisi, qui est son titre ;
     5 arrêts nommés, un arrêt fait prend une coche et se touche pour y revenir (tous en modification ;
     `WizardState.reached`) ; pas de « Retour » à la 1re étape ; « Suivant : Accès »… puis « Enregistrer ». Quitter
     l'Adresse par un arrêt range aussi les clés d'API (`goTo`).
  6. Éditeur multi-zoom : barre des zooms fixe en haut, « Ce qui s'affiche » en lignes (une par tranche, une par trou
     avec « Combler », `MultiZoomViewModel.fillGap`), réglages d'une tranche dans une feuille du bas. Ouvert depuis
     Cartes disponibles, Enregistrer y revient avec « Carte affichée : X ».
  7. Corrections communes : icônes warning et check_circle au lieu de « ⚠ » et « ✓ », interrupteur « Calque
     superposable… » au bout de sa ligne, certificat refusé en ligne avec l'icône error, vignette sans tuile à l'icône
     du type (carte, calque, image aérienne, relief), un seul bouton plein par écran (« Lire les couches » et « Tester
     une tuile » en contour).
- **Conséquences** : on sait avant de toucher ce que fera l'appui ; le ⋮ d'une carte tombe à 8 lignes au plus ; il
  faut choisir un segment avant de voir les cartes ; la radio ne montre que le fond le plus bas ; l'interrupteur
  d'ici retire de la pile, l'œil de « Cartes et calques » masque sans retirer.
- **Alternatives rejetées** : variantes A, C, D et E de l'aperçu.

## ADR-080 — Téléchargements et stockage en « Poids » : la zone pesée d'abord, la tâche en cours en grand, la place du téléphone en tête
- **Date / statut** : 2026-10-27 — Accepté (P29.7 ; aperçu `docs/previews/design-07-telechargements.html`, variante
  B · Poids, choisie par l'utilisateur le 2026-10-27). Garde ADR-042 (stockage, carte SD) et la file WorkManager
  des téléchargements ; la sélection de zone suit le cadrage de l'impression (P17) pour la barre du bas.
- **Contexte** : audit de P29.7 : l'en-tête du panneau de zone, coupé (« Sélectionner et enreg… »), n'était pas le
  bandeau des panneaux ; la barre du bas restait active pendant la sélection et « Cartes » effaçait le rectangle ;
  chaque carte s'écrivait « 6-16/18 » en 11 sp ; l'aide parlait de cases « en vert » et la confirmation d'un chemin
  qui n'existe pas ; l'écran s'appelait « Cartes hors ligne » ; une corbeille oubliait une tâche sans retour possible ;
  « erreurs : 0 » toujours écrit, nombres sans espace des milliers ; le Stockage ne disait pas la place du téléphone.
- **Décision** :
  1. Zone : la barre du bas est masquée pendant la sélection (✕ ou Retour la ramènent), les boutons de zoom restent ;
     la feuille met le poids en grand avec la place libre (`refreshStorage` à l'ouverture du mode), puis le zoom
     maximal, la ligne repliée « Cartes · … · 2 sur 3 » qui déplie les cases des cartes (zooms en mots, 12 sp, barres
     avec leurs bouts) et « Enregistrer la zone » en pleine largeur ; les états du total remplacent le grand chiffre.
     Aide titrée « Enregistrer une zone », couleurs justes ; confirmation « Suivi dans Cartes › Téléchargements. ».
  2. Téléchargements : l'écran prend le nom de la tuile ; la tâche en cours vient d'abord, en grand (vignette, pour
     cent, Pause, Retirer) ; les tâches en attente ou interrompues juste dessous ; « En pause » avec les échecs ;
     « Terminés » ; nombres à la française, erreurs seulement s'il y en a ; bouton « Nouvelle zone ».
  3. « Retirer » propose « Annuler » (`DownloadScheduler.restore` : la tâche revient telle quelle, en attente si
     elle tournait). Pour que le retrait tienne, le travail d'une tâche ne réécrit plus une tâche retirée
     (`ZoneJobStore.update`) : avant, l'annulation la réenregistrait « En pause » et elle réapparaissait.
  4. Stockage : jauge du téléphone en tête (place du dossier des tuiles, `StatFs`), part de ToolMap en turquoise ;
     liste groupée de la plus lourde à la plus légère ; import en icône dans la barre ; l'état vide mène à la
     sélection d'une zone.
- **Conséquences** : la décision « combien pèse cette zone, reste-t-il de la place ? » se lit d'un coup d'œil ; les
  cartes cochées ne se voient qu'en résumé tant que la ligne « Cartes » est repliée ; pendant la sélection, le menu,
  les traces et la position ne s'ouvrent pas (il faut sortir du mode).
- **Alternatives rejetées** : variantes A, C, D et E de l'aperçu.

## ADR-081 — Partage en « Cordée » : votre ligne en tête, Inviter flottant, SOS dans la barre, fiche et invitation en feuilles
- **Date / statut** : 2026-10-27 — Accepté (P29.8 ; aperçu `docs/previews/design-08-partage.html`, variante B ·
  Cordée, choisie par l'utilisateur le 2026-10-27). Garde ADR-071 (cartes d'alerte du partage, leur bouton plein
  étant l'exception validée), ADR-050 (jetons) et le protocole du partage (ADR-004, ADR-021, ADR-033).
- **Contexte** : audit de P29.8 : une dizaine de blocs de même poids en groupe, quatre boutons pleins, un SOS sans
  place fixe ni forme propre, la même action sous des libellés différents selon l'état, des lignes de membres de
  36 dp, une fiche de membre en dialogue qui couvre la carte et retire sans confirmation, une invitation qui dit
  « Annuler » alors que le code reste valable 7 jours, des résumés de réglages qui ne disent pas l'état réel.
- **Décision** :
  1. Votre ligne en tête : hors groupe, votre jeton et votre nom (celui que les autres voient, `ShareUi.myName`)
     ouvrent la page « Ce que les autres voient » ; en groupe, le texte ouvre votre fiche, l'interrupteur à part
     envoie ou non. Hors groupe : état vide « Aucun groupe », « Créer un groupe et inviter », « Rejoindre un
     groupe » en deux boutons côte à côte.
  2. En groupe : alertes sous votre ligne ; membres en lignes de 64 dp avec un bouton carte chacun ; ligne
     « Messages » avec le dernier message et le nombre de non-lus (`ShareController.markMessagesRead`, compteur des
     messages des autres reçus depuis la dernière ouverture), qui ouvre la page des messages (50 derniers, heure) ;
     « Inviter » en bouton flottant, seule action pleine.
  3. Le SOS passe en icône dans la barre (« Envoyer un SOS », même dialogue) ; pendant le vôtre, une ligne rouge en
     tête porte « Arrêter le SOS ». Scanner, coller, réglages du partage (une page), aide et quitter vont dans le ⋮ ;
     la phrase sur l'écran éteint passe dans l'aide.
  4. Fiche d'un membre en feuille (`MemberSheet`, depuis la liste et la carte) : quatre chiffres, position au format
     choisi, tuiles Voir sur la carte · Aller vers ce membre · Enregistrer sa trace, « Retirer du groupe » dans le ⋮
     avec confirmation ; invitation en feuille (`CodeSheet`) avec « Fermer ».
- **Conséquences** : ce qu'on vient vérifier tient en haut sans défiler ; le SOS est loin du pouce (le dialogue évite
  l'envoi par erreur) ; scanner, coller et les réglages demandent un toucher de plus ; Messages s'ouvre à part.
- **Alternatives rejetées** : variantes A, C, D et E de l'aperçu.

## ADR-082 — Réglages en « Loupe » : une recherche sur les vrais réglages, des pages à onglets, la Sauvegarde en deux segments
- **Date / statut** : 2026-10-27 — Accepté (P29.9 ; aperçu `docs/previews/design-09-reglages.html`, variante C ·
  Loupe, choisie par l'utilisateur le 2026-10-27). **Remplace** le point 2 de ADR-038 (catégories repliables dans
  une seule colonne) ; les onglets écartés par ADR-036 et ADR-038 sont repris à la demande de l'utilisateur. Garde
  ADR-038 §1 (une seule entrée, Menu ▸ « Réglages… »), ADR-039 (une commande par réglage : la recherche est une vue
  filtrée des mêmes lignes) et ADR-036 §3 (une icône Material par réglage).
- **Contexte** : audit de P29.9 : neuf catégories muettes et pas de recherche ; une catégorie ouverte qui ressemble
  à ses lignes ; une fenêtre pour chaque choix, même entre deux options, et cinq dialogues doubles ; des aides qui
  redisent le libellé ; des cases à cocher alors que le style Crête a des interrupteurs ; « Rétablir » sans retour ;
  dans la Sauvegarde, deux boutons pleins et la restauration en attente en bas.
- **Décision** :
  1. Liste : un champ « Rechercher un réglage » au-dessus des neuf catégories. La recherche (`SettingsSearch`,
     `:core`) lit les titres, les explications et les noms des options, sans casse ni accents, chaque mot tapé
     devant y être, un « s » ou un « x » final de mot (4 lettres et plus) n'étant pas exigé. Les résultats : les
     catégories dont le nom contient les mots (2e ligne « Catégorie »), puis les réglages eux-mêmes, utilisables sur
     place, la catégorie en tête de leur 2e ligne et les mots trouvés en gras. Sans résultat : « Aucun réglage ne
     contient ce mot. » et « Effacer la recherche ».
  2. Chaque réglage est une `SettingEntry` (mots cherchés + ligne), construite par catégorie (`SettingsEntries.kt`) :
     la même ligne sert sur la page et dans les résultats.
  3. Catégorie : une page, avec des onglets Material à défilement dans le bandeau pétrole (noms courts : Unités,
     Affichage, Carte, Grille, Repères, Position, Enregistreur, Alertes, Stockage), sans glissement latéral (les
     curseurs) ; la loupe de la barre revient à la recherche ; le ⋮ garde « Rétablir les réglages par défaut ».
     Retour : la page, puis la recherche, puis l'écran. La page ouverte et la recherche sont gardées dans
     `SettingsViewModel` : Retour depuis la Sauvegarde ou le Multi-zoom revient sur la page d'où on vient ;
     « Réglages… » du menu rouvre toujours la liste (`openList`).
  4. Socle commun : interrupteurs Crête ; « Apparence des menus » éclatée (« Fond opaque des menus » dans Affichage,
     « Boutons + et − à gauche » dans Carte sous « Afficher les boutons + et − ») ; dialogues doubles remplacés par des
     lignes (boutons et pas du zoom, densité et traits des grilles, taille et fond des boîtes, flèches et liseré des
     traces) ; choix de 2 ou 3 options en boutons segmentés pleine largeur, sans coche, le nom complet du choix en
     2e ligne quand il dit plus ; curseurs dans la ligne (Rayon par défaut, Écart toléré, Zooms manquants, comme Mode
     nuit) ; aides qui redisaient le libellé retirées ; note « Tuiles partagées » en légende sous « Cartes
     multi-zoom… » ; icônes des lignes en gris, celles des catégories en couleur primaire ; « Alerte d'écart de
     trace » prend `wrong_location`. Gardent leur dialogue : formats de position, résolution, grille, appui long,
     ouvrir les positions avec, altitude, cap actuel, sans données BRouter, couleurs, emplacement de stockage.
  5. « Rétablir » s'applique, puis une barre « Réglages par défaut rétablis » propose « Annuler », qui remet les
     réglages d'avant.
  6. Sauvegarde (`BackupPage`) : un segmenté « Sauvegarder » | « Restaurer », une action principale par segment ;
     le compte rendu (« Masquer ») et la progression au-dessus, le segmenté grisé pendant un travail. Sauvegarder :
     une phrase, un groupe Contenu + « Inclure les cartes hors ligne », ce qui n'est pas sauvegardé, puis « Créer une
     sauvegarde… » (tonal si une restauration attend). Restaurer : la carte « Restauration prête » en tête
     (« Redémarrer maintenant », seul bouton plein, et « Annuler »), le texte, « Choisir une sauvegarde… » (en contour
     si une restauration attend, plein sinon). Une restauration en attente ouvre l'écran sur « Restaurer ».
- **Conséquences** : un réglage se retrouve sans savoir où il est rangé ; une catégorie voisine est à un toucher ;
  9 onglets défilent, la moitié hors de vue ; les noms courts sont nouveaux ; chaque nouvelle ligne de réglage doit
  être une `SettingEntry` pour être trouvée. Tests : `SettingsSearchTest` (`:core`), `SettingsScreenTest`,
  `BackupPageTest`, `NavigationBackTest` (Retour de la Sauvegarde sur la page « Stockage ») ; rendus
  `SettingsRenderTest`.
- **Alternatives rejetées** : variantes A, B, D et E de l'aperçu.

## ADR-083 — Outils en « Équerre » : chaque outil devient un panneau de la barre, menus et dialogues blancs
- **Date / statut** : 2026-10-28 — Accepté (P29.10 ; aperçu `docs/previews/design-10-outils.html`, variante A ·
  Équerre, choisie par l'utilisateur le 2026-10-27). Applique ADR-060 (panneaux de la barre) aux outils ; garde les
  aperçus validés du cadre d'impression (2026-10-26) et de P19 (2026-10-18), ADR-074 (style Crête) et ADR-075.
- **Contexte** : audit de P29.10 : cinq panneaux d'outil écrits à la main en deux recettes (coins de 16 dp, gris
  teinté par l'élévation, titres variables), collés en bas sous la barre alors que Cartes, Traces et Position flottent
  au-dessus ; neuf commandes en mots et deux boutons pleins dans le dessin ; « Annuler » qui veut dire défaire ou
  renoncer ; bandeaux de cible et de suivi en cartes Material ; menus et dialogues gris-bleu ; orange des alertes
  d'impression sous le contraste AA ; suppression dont le titre répète le bouton, le bouton destructif semblable à
  « Annuler ».
- **Décision** :
  1. `ToolPanel` (`map/PanelParts.kt`, sur `BarPanel` et `PanelBand`) : dessin, itinéraire, mesure, confirmation
     d'impression et panneau du cadre sont des panneaux de la barre, au-dessus de la barre du bas restée en bas
     (12 dp d'écart) ; bandeau pétrole au nom de l'outil (« Nouvelle trace », « Itinéraire » ou le nom de la ligne,
     « Mesure », « Imprimer à l'échelle ») avec ses chiffres en sous-titre et ✕ pour quitter (« Abandonner les
     modifications ? » s'il y a des changements) ; pas de ✕ pendant le placement du cadre, où « Annuler » renonce et
     où la barre reste masquée.
  2. Actions au réticule en tuiles (`ActionTile`, la principale et les bascules allumées sur la couleur secondaire) ;
     pied `ToolFooter` : icônes Annuler la dernière action / Rétablir, un menu en texte (`TextMenu` : « Outils »,
     « Garder… ») et un seul bouton plein (« Enregistrer » ; la mesure n'en a pas) ; Distance | Surface en segmenté.
  3. MapScreen : `toolLift` remonte les boutons de droite et les crédits au-dessus du panneau d'outil
     (`MapBottomChrome.lift`) et le message en barre au-dessus de lui ; un panneau de la barre ouvert pendant un outil
     le remplace, il revient à sa fermeture.
  4. Ce qui reste posé sur la carte prend l'habillage des pastilles de P29.1 (`ui/MapPill.kt`) : chiffres
     d'itinéraire, bande du cadre (puces compactes de 32 dp), bandeaux de cible et de suivi (l'alerte garde sa
     couleur) ; la puce de profil passe à 32 dp vus, 48 dp au toucher.
  5. Menus et dialogues : enveloppes `AlertDialog` et `DropdownMenu` (`ui/theme/Controls.kt`), blanches de jour
     (`ToolMapColors.popup`, #1A2F38 de nuit), coins de 18 et 12 dp, sans teinte d'élévation, adoptées par leurs
     imports dans tout le code. `ConfirmDialog(destructive = true)` met le bouton qui confirme en rouge ; une
     suppression nomme l'objet dans le titre (« Supprimer « Fort Barraux » ? », « Supprimer 3 éléments ? »,
     « Supprimer le dossier « … » ? ») et dit « Cette action est définitive. ».
  6. Alertes de l'impression en couleur tertiaire du thème au lieu de l'orange écrit en dur.
  7. Recherche (second incrément) : champ « Lieu, coordonnées ou élément » avec loupe et « Effacer » ; l'état de la
     recherche en cartes sous le champ (aide, hors ligne, aucun résultat, service en panne avec « Réessayer ») ;
     résultats en groupes blancs (« Sur mon téléphone », « En ligne ») avec une pastille d'icône, la distance en
     sous-titre et un ⋮ (Voir sur la carte, Aller vers, Enregistrer comme waypoint) ; crédits sur une ligne en pied ;
     types d'OpenStreetMap en français (`OsmTypes`, `:core`, « Château fort », « Village »).
  8. Aide et à propos : en-tête ToolMap (tuile, nom, version), « Prise en main » en blocs à icône dans un groupe blanc
     (le bloc ouvert par le « ? » d'un panneau reste éclairé un instant), vie privée et limites en blocs de texte,
     données et licences en lignes de liste.
- **Conséquences** : les outils ont la même coque et la même place que les panneaux de la barre ; le panneau
  d'itinéraire reste haut (environ 250 dp) ; un outil laisse la barre du bas atteignable ; les actions d'un résultat
  restent derrière le ⋮ ; l'aide reste longue. Tests : `ToolPanelsTest` (6), `SearchScreenTest` (5), `OsmTypesTest`
  (2, `:core`) ; rendus `ToolsRenderTest`, `SearchRenderTest`.
- **Alternatives rejetées** : variantes B, C, D et E de l'aperçu.

## ADR-084 — Point central au milieu de la carte visible pendant un outil, crédits passagers sur une ligne

- **Date / statut** : 2026-10-28 — Accepté (demande de l'utilisateur : « le point pour visée “Ajouter ici” est invisible », puis « le réticule est actuellement caché derrière les crédits de carte (à retirer car occupant trop d'espace) » ; variante E de `docs/previews/p19-reticule.html` validée le 2026-10-28, rendu `docs/previews/p19-reticule-emulateur.html`). **Complète** le point 3 d'ADR-043 (place du point central) et la phrase d'ADR-083 sur les crédits montés au-dessus du panneau. Aucun ADR n'est remplacé.
- **Contexte** : le point central (le réticule de « Ajouter ici », « Déplacer ici », de la mesure) était fixé au centre de l'écran. Le panneau d'un outil (environ 250 dp, plus haut sans étape) et le bloc des crédits posé juste au-dessus (trois lignes avec OpenStreetMap, OpenTopoMap et IGN) le couvraient sur un téléphone à la taille d'affichage « Grand », et sur l'émulateur à 500 dpi.
- **Décision** :
  1. **Pendant le dessin (trace, itinéraire) et la mesure**, quand leur panneau est affiché, le centre de la carte devient le milieu de la partie visible entre le bas des boîtes du haut et le haut du panneau : `MapController.setCentreInsets` règle la marge de la caméra MapLibre (`CameraUpdateFactory.paddingTo`, 300 ms), et `CenterPoint` se décale d'autant (même durée). La cible de la caméra reste le point visé : la carte glisse avec le réticule, `liveCamera`, les coordonnées, « Ajouter ici », « Insérer ici » et l'appui sur le point visent ce point sans autre changement. Panneau fermé (ou caché par un panneau de la barre), marges à 0 : retour au centre de l'écran. Moins de 96 dp de carte libre : pas de décalage. L'impression garde le centre de l'écran (son cadre en dépend).
  2. **Crédits passagers** : une ligne (`Credits.short`, `:core`) qui coupe chaque copyright à ses séparateurs, retire « (c) », « Style : », « contributors » et les licences entre parenthèses, écrit OpenStreetMap « OSM » et garde chaque nom une fois (« © OSM · SRTM · OpenTopoMap · IGN »). Elle s'affiche 4 s à chaque changement des cartes affichées (et à l'ouverture), puis s'efface en 0,6 s ; tronquée par « … » si elle dépasse. Les textes complets restent dans « Aide et à propos ».
- **Conséquences** : le réticule n'est plus couvert par le panneau ni par les crédits ; la carte se décale de la moitié de la hauteur du panneau à l'ouverture d'un outil, et le réticule bouge un peu quand le panneau change de hauteur (premier point posé, bandeau des chiffres qui remplace les boîtes), le point visé restant dessous. Les crédits ne sont visibles en permanence nulle part sur la carte : choix de l'utilisateur, leur rappel à chaque changement de carte restant la trace demandée par les fournisseurs. Tests : `CreditsTest` (3, `:core`).
- **Alternatives rejetées** : variantes A (bouton « i » dans la colonne), B (petit ⓘ dans un coin), C (crédits seulement dans les fiches et l'aide) et D (ligne courte permanente sous le panneau) ; panneaux compacts ou repliés au lieu du réticule déplacé (premier aperçu).

## ADR-085 — Reprendre une étape d'itinéraire au réticule

- **Date / statut** : 2026-10-28 — Accepté (demande de l'utilisateur : « pouvoir récupérer la main sur un point en le survolant, même mécanique que “Ajouter ici” qui se transforme en “Insérer ici” en survolant l'itinéraire » ; variante A de `docs/previews/p19-reprendre-etape.html` validée le 2026-10-28, rendu `docs/previews/p19-reprendre-etape-emulateur.html`). **Complète** l'opération 4 de `docs/specs/routing.md` §5.3 (« Déplacer ici » après sélection, qui reste). Aucun ADR n'est remplacé.
- **Contexte** : le réticule posé sur une étape laissait « Ajouter ici » (une nouvelle étape par-dessus) ; pour déplacer une étape, il fallait la toucher du doigt, déplacer la carte, puis toucher « Déplacer ici », sans voir le tracé à venir.
- **Décision** :
  1. **Survol** : à moins de 24 dp d'une étape (même portée que « Insérer ici », `RoutePlan.nearestStop`), la tuile principale du panneau « Itinéraire » devient « Prendre l'étape N » (lettre de la carte aux extrémités, sinon numéro). Une étape passe avant le tracé.
  2. **Prise** : `DrawViewModel.take` sélectionne l'étape ; tant qu'elle est prise, elle est dessinée sous le réticule (`RoutePlanGeoJson.handles(moved = …)`), ses deux tronçons sont pâles (`st` = `g`) et une ligne pointillée la relie à ses voisines (`RoutePlanGeoJson.elastic`, source `tm-e-elastic`). `MapScreen` suit `liveCamera` et n'envoie à la carte que les poignées et la ligne élastique : la ligne de l'itinéraire, même chaîne qu'avant, n'est pas renvoyée (`MapController.showEdit`). BRouter n'est pas appelé pendant le glissement.
  3. **Panneau « Étape N en main »** : « Poser ici » (tuile double, `RoutePlanEditor.move`, une seule entrée d'annulation, les deux tronçons voisins recalculés), « Annuler » (aussi ✕ et Retour : l'étape reste où elle était), « Supprimer l'étape » ; l'interrupteur, le profil et les autres tuiles sont cachés. Toute autre modification (Annuler la dernière action, outils) lâche l'étape ; un appui sur la carte est ignoré pendant la prise.
- **Conséquences** : déplacer une étape se fait au réticule, comme l'ajout et l'insertion, avec un aperçu du nouveau passage ; « Déplacer ici » reste pour l'étape choisie dans la liste. Tests : `RoutePlanEditorTest` (pose en une annulation), `RoutePlanGeoJsonTest` (tronçons pâles, poignée déplacée, lignes élastiques).
- **Alternatives rejetées** : variante B (sans lignes élastiques : on ne voit pas le passage avant de poser), variante C (le survol sélectionne, puis « Déplacer ici » : l'étape ne suit pas le réticule, et « Ajouter ici » reste à côté).

## ADR-086 — Menu sans le bloc des cartes ; grille UTM chiffrée au bord de l'écran

- **Date / statut** : 2026-10-28 — Accepté (demandes de l'utilisateur : « Retire “Cartes affichées” du “Menu” », aperçu `docs/previews/menu-sans-cartes.html` validé le 2026-10-28 ; « seule la grille MGRS s'affiche, même si j'utilise d'autres modèles comme UTM », puis « A · Chiffres au bord de l'écran et retire la pastille. Comme ça le chiffre disparaît naturellement lorsque très zoomé 18/19 », variante A de `docs/previews/p24-utm-reperes.html` choisie le 2026-10-28 ; rendus `docs/previews/menu-sans-cartes-emulateur.html` et `docs/previews/p24-utm-reperes-emulateur.html`). **Remplace** le point 1 d'ADR-075 pour le bloc des cartes affichées (le reste du menu « Portée » ne change pas) et, pour UTM seulement, la règle des noms d'ADR-073 (« UTM : zones seules ») ; MGRS ne change pas.
- **Contexte** : le bloc du menu répétait l'onglet « Cartes » de la barre, qui ouvre le même panneau. Pour la grille, le test de tous les réglages de « Grille et informations » sur l'émulateur a montré que les grilles changent bien, mais qu'UTM et MGRS tracent les mêmes lignes à partir de 1 km (MGRS est construit sur UTM) : seul le nom au centre différait (« 31T » ou « 31T GL »), d'où l'impression de toujours voir MGRS.
- **Décision** :
  1. **Menu** : le bloc des cartes affichées (vignettes, nom des cartes ou du favori, « sans favori ») est retiré avec son code (`MainMenuState.favorite`, `displayLayers`, `MenuAction.MAPS`, `StackThumbnails`, 4 textes).
  2. **Chiffres UTM** : `ScreenGrid.plan` donne les lignes du kilomètre de chaque zone (`ScreenGridPlan.figureLines`, `GridFigureLine` : abscisse ou ordonnée, valeur, points) ; `GridFigures` (Compose, au-dessus de la carte et sous les boîtes et boutons) les projette à chaque mouvement de caméra et écrit leur valeur en km là où elles sortent de l'écran : ordonnées à gauche, 5 dp du bord, juste au-dessus de leur ligne ; abscisses 8 dp au-dessus de la barre, juste à droite de leur ligne ; chiffres avant les deux derniers en 13 sp, deux derniers en 18 sp gras, gris foncé avec un halo blanc. Un chiffre est sauté s'il sort du cadre (sous les boîtes du haut, au-dessus de la barre) ou s'il toucherait un chiffre déjà placé (les ordonnées d'abord), les boutons du bas à droite ou le zoom à gauche.
  3. **Lignes du kilomètre seules** : sous 1 km, les lignes à 100 ou 500 m ne sont pas chiffrées ; aux zooms 18 et 19, aucune ligne du kilomètre n'est à l'écran et aucun chiffre ne s'affiche.
  4. **Nom « 31T » retiré en UTM** quand les lignes sont tracées ; il reste sur une vue trop large pour les lignes, où aucun chiffre ne le remplace. La puce de la boîte Position garde « UTM 31T · 1 km ».
  5. Les chiffres sont cachés pendant le placement du cadre d'impression et de la zone à enregistrer, comme les boîtes.
- **Conséquences** : la grille UTM se lit comme celle d'une carte papier et ne ressemble plus à MGRS. Les lignes étant recalculées à l'arrêt de la carte, un glissement peut faire sortir une ligne de sa partie calculée : son chiffre disparaît jusqu'à l'arrêt. Une carte tournée de 90° n'a plus d'abscisse en bas (les lignes nord-sud deviennent horizontales). Tests : `ScreenGridTest` (lignes du kilomètre, coupe « 50 » / « 45 », rien de près, UTM sans noms sauf vue large), `MainMenuPanelTest`.
- **Alternatives rejetées** : variante B (chiffres sur les lignes près du centre : ils chargent la vue où l'on regarde), variante C (pastille du système et de l'écart : déjà dans la puce de la boîte Position, et toujours affichée, même de très près).

## ADR-087 — Feuille imprimée : la grille choisie seule, MGRS sans les centaines de km

- **Date / statut** : 2026-10-28 — Accepté (demande de l'utilisateur, avec ses PDF « UTM », « MGRS », « MGRS OTAN » et « Degré » : « le PDF est généré avec des valeurs WGS84 et un combo UTM / MGRS écrit en bordure de carte. Restreins au modèle choisi uniquement. Si je prends MGRS, je ne dois pas voir de 7 devant le 33 »). **Remplace** la ligne « Cadre gradué » de `docs/specs/print-pdf.md` § 8 (graduations dans « l'autre système », ADR-061) et les coordonnées des coins en degrés sur une grille métrique.
- **Contexte** : une feuille UTM ou MGRS portait trois systèmes : la grille choisie, les coins en degrés-minutes-secondes, le cadre gradué en minutes de latitude et de longitude (avec sa note dans la case QUADRILLAGE). En MGRS, les étiquettes reprenaient celles d'UTM (« ⁷**33**⁰⁰⁰ m E ») : le « 7 » des centaines de km et les « 000 m E » n'existent pas dans une référence MGRS, qui se lit dans le carré de 100 km. L'encadré OTAN donnait un exemple MGRS quelle que soit la grille.
- **Décision** :
  1. **Cadre gradué** : graduations noir / blanc au pas de la grille choisie (`GridPlanner.graduation(frame, grid, step)`), aucune sans grille ; la note « Cadre gradué toutes les … » est retirée.
  2. **Coins** : écrits seulement pour une grille en degrés ou sans grille (DMS ; en degrés décimaux pour « WGS84 décimal »). Sur une grille UTM, MGRS ou Lambert 93, la première étiquette de chaque côté donne déjà la valeur complète.
  3. **MGRS** : deux chiffres par ligne (les km dans le carré de 100 km), sans centaines de km ni « 000 m » ; la légende du gros chiffre dit « = 33 km dans le carré de 100 km ». UTM et Lambert 93 gardent ⁷**33**⁰⁰⁰ m E.
  4. **Encadré OTAN « Donner une référence »** : il suit la grille : MGRS (lettres du carré, 6 chiffres), UTM (zone, puis km et centaines de mètres : « 31T 732 600 E 5 033 400 N »), Lambert 93 (même lecture sans zone), degrés (le centre de la feuille en DMS ou en décimal).
  5. « WGS84 » reste dans « UTM 31N · WGS84 » : c'est le système géodésique de la grille, pas une coordonnée.
- **Conséquences** : une feuille ne montre plus qu'un système de coordonnées ; la position d'un point en degrés n'est plus lisible sur une feuille UTM ou MGRS (choisir « WGS84 » pour cela). Les options UTM et MGRS restent distinctes : étiquettes et lecture différentes. Tests : `GridAtlasTest` (cadre gradué selon la grille, MGRS sans centaines de km), `PrintExportTest` (aucun coin en degrés sur une grille métrique, encadré OTAN selon la grille).
- **Alternatives rejetées** : coins dans la grille choisie (répètent la première étiquette de chaque côté) ; retirer l'option MGRS (elle se lit autrement qu'UTM et donne les lettres du carré).
