# Plan iOS — ToolMap sur iPhone (P32)

> Décision : ADR-100 (2026-10-29). Application **Swift + SwiftUI native**, **indépendante du code Android**, dans le dossier `ios/` du dépôt. Statuts et légende comme dans `docs/PLAN.md` (⬜ à faire · 🔄 en cours · ✅ terminé · ⛔ bloqué).
>
> Ce plan est relu au début de chaque session iOS, avec `CLAUDE.md`, `docs/PLAN.md`, les trois dernières entrées de `docs/JOURNAL.md` et `docs/DECISIONS.md`. Les idées pour plus tard vont dans `docs/idees-futur.md`, pas ici.
>
> **Consigne du 2026-10-29** (relayée par la session du poste Windows) : P32.2 à P32.12 s'enchaînent sans validation pas à pas, l'utilisateur ne pouvant rien tester ; les aperçus sont publiés dans `docs/previews/` et validés a posteriori ; les décisions ouvertes prennent le choix le plus simple et réversible, « à confirmer par l'utilisateur » (ADR et journal) ; P32.13 attend l'utilisateur.

## 1. Règles

1. **Aucun fichier Android modifié** par le travail iOS : ni `app/`, `core/`, `data/`, `p2p/`, ni les fichiers Gradle, ni `.github/workflows/ci.yml`. Le travail iOS ne touche que `ios/`, `.github/workflows/ios.yml` et les documents communs (`docs/`, `CLAUDE.md`), plus `version.properties` quand une entrée du journal change son empreinte (ADR-059).
2. **Aperçu validé avant le code** (`CLAUDE.md`) pour chaque écran iOS : maquette de l'écran iPhone (état Android actuel et proposition iOS, plusieurs variantes), « oui » explicite de l'utilisateur, code fidèle, puis capture du simulateur posée à côté de l'aperçu.
3. **Compilation et tests uniquement par la CI macOS** (`.github/workflows/ios.yml`) : l'environnement cloud n'a ni Xcode ni compilateur Swift (ADR-100). Un lot de portage se pousse d'un bloc ; la session attend le run et corrige jusqu'au vert, comme pour la CI Android.
4. **Rien qui exige un compte Apple Developer** (signature pour un appareil, TestFlight, App Store) sans l'accord de l'utilisateur.
5. **Portage fidèle** : un fichier Swift reprend un fichier Kotlin de `:core` (mêmes noms de types, mêmes règles, mêmes messages) ; ses tests reprennent les cas et valeurs attendues des tests Kotlin, sur les mêmes fixtures (`fixtures/`, lues en place depuis le dépôt).
6. Fin de session : comme sur Android (`CLAUDE.md`, point 7), lien de l'APK Android du dernier run vert de la branche **et** lien du run iOS vert.

## 2. Structure

| Chemin | Contenu |
|---|---|
| `ios/ToolMapCore/` | Paquet Swift (`Package.swift`) : logique sans interface, portage de `:core`. `Sources/ToolMapCore/<Domaine>/`, tests Swift Testing dans `Tests/ToolMapCoreTests/`. iOS 17 et macOS 14 au minimum. |
| `ios/ToolMap/` | Application SwiftUI (à venir, P32.3) : projet Xcode généré par XcodeGen depuis `project.yml` sur le runner, MapLibre Native iOS par Swift Package Manager, dépend de `ToolMapCore`. |
| `.github/workflows/ios.yml` | CI iOS sur runner macOS : `swift test` (macOS) puis `xcodebuild test` sur un simulateur iPhone ; journaux et résultats en artefact en cas d'échec ; plus tard captures du simulateur et `.app` zippé. |

Correspondance des paquets : `fr.toolmap.core.<paquet>` (Kotlin) → `ios/ToolMapCore/Sources/ToolMapCore/<Paquet>/` (Swift). Les fonctions Kotlin qui lèvent `IllegalArgumentException` (`require`) deviennent des fonctions Swift `throws` (`ToolMapError.invalidArgument`), pour garder les tests d'erreur.

### Règles d'écriture du Swift (le compilateur n'est que sur la CI)

- `ToolMapCore` est en **Swift 6** (vérification stricte de la concurrence) ; l'application en Swift 5. Types de valeur `Sendable` ; une classe partagée entre fils : `final class … : @unchecked Sendable` avec un `NSLock`. Une constante statique d'un type non `Sendable` : `nonisolated(unsafe) private static let` (`NSRegularExpression` est `Sendable` : `static let` suffit).
- Tests en **Swift Testing** (`import Testing`, `@Test("phrase du test Kotlin")`, `#expect`, `try #require`). Dans `#expect`, mettre `try` **au début** de l'expression (`#expect(try f() == x)`) ; calculer avant une valeur qui demanderait un `try` à droite d'un opérateur. Fichiers de test : `fixtures/` lus en place par `Fixtures.text("aqx/…")`.
- Jamais un `#require` dans un autre `#require` (ni dans un `#expect`) : « recursive expansion of macro » ; passer par une variable.
- Pas de `guard let x` ni `let x = x` dans la **même portée** qu'un `let x` déjà déclaré (« definition conflicts with previous value ») : nommer la valeur optionnelle autrement.
- `try` jamais à droite d'un opérateur binaire (`a + try f()` est refusé) ; dans un argument d'appel, c'est permis mais on préfère une variable.
- Les comportements de Kotlin ont leurs fonctions dans `Support/KotlinText.swift` : `kotlinTrim`, `kotlinInt` (`toIntOrNull`, 32 bits), `kotlinDouble` (`toDoubleOrNull`), `kotlinSplit` (garde les morceaux vides), `kotlinToInt` / `kotlinToLong` (saturés, NaN → 0), `kotlinRound` (`Math.round`), `kotlinRem` (`%` des `Double`), `formatFixed` (`%.nf` en `Locale.ROOT`), `Angle.radians` / `Angle.degrees` (même dernier bit que `Math.toRadians` du JVM).
- `require(…)` de Kotlin → fonction `throws` (`try require(cond, "message")`, erreur `ToolMapError.invalidArgument`) ; une exception propre (`AqxParseException`) → une erreur Swift du même nom en `…Error`.
- Une sous-classe d'une classe Apple ne redéfinit pas une propriété existante (`URLProtocol.task`) : nommer ses propriétés sans ambiguïté.
- Dans `#expect` / `#require`, un appel de fonction au premier niveau ne prend ni chemin de clé ni référence de fonction en argument (`#expect(a.allSatisfy(\.isEdge))` est refusé : « call can throw ») : fermeture en fin d'appel (`a.allSatisfy { $0.isEdge }`).
- Une longue expression mêlant littéraux et `map` (`(0 ..< 50).map { … } + (0 ..< 50).map { … }`) dépasse le temps de vérification des types : variables intermédiaires typées. De même, une valeur attendue calculée (`1_800_000_000_000 + 70_000 - …`) comparée à un `Int64?` se type à part (`let expected: Int64 = …`).
- La valeur par défaut d'un paramètre d'une fonction `public` ne cite que des déclarations `public`. Un octet calculé dans un littéral de `[UInt8]` (`1 | 0x80`) s'écrit `UInt8(1 | 0x80)`.
- Pour vérifier un nom d'API de MapLibre, lire les en-têtes sur `raw.githubusercontent.com/maplibre/maplibre-native/main/platform/{ios,darwin}/src/` (joignable depuis le cloud).

## 3. Méthode de synchronisation avec Android

Les deux applications n'ont aucun code commun : une fonction Android se **traduit** en Swift. Pour ne rien perdre :

1. **Android de référence** : le commit et la version Android jusqu'auxquels le portage est à jour sont notés en tête de la table de parité (§ 6).
2. **En début de session iOS**, après la mise à jour depuis la copie la plus récente (`CLAUDE.md`) : `git log --oneline <commit de référence>..HEAD -- core app data p2p` et les entrées du journal depuis cette date. Chaque changement Android qui concerne une ligne déjà portée ou planifiée de la table devient une ligne « à reporter » ; les nouvelles fonctions entrent dans la table (⬜). Puis le commit de référence avance.
3. **Mêmes tests** : chaque lot porté reprend les tests Kotlin du même domaine (cas, valeurs attendues, fixtures). Un écart de comportement se voit donc comme un test rouge.
4. **Partage de position** : l'interopérabilité Android ↔ iPhone (trames chiffrées, appairage, relais Firebase) se vérifie par des vecteurs de test produits par le code Kotlin de `:core` (programme lancé à part, hors du build Android) et rangés sous `ios/ToolMapCore/Tests/`.
5. **CI iOS** à chaque push qui touche `ios/**`, `fixtures/**` ou le workflow : elle échoue si le code Swift ne compile plus ou si un test casse.

## 4. Étapes

| ID | Statut | Tâche | Critères d'acceptation |
|---|---|---|---|
| P32.0 | ✅ | **Architecture** validée par l'utilisateur le 2026-10-29 : Swift + SwiftUI natif, code indépendant d'Android (ADR-100), ce plan, phase P32 dans `docs/PLAN.md` | Validation explicite, consignée au journal |
| P32.1 | ✅ | **Paquet `ToolMapCore`, premier lot** et **CI iOS** : tuiles (`TileMath`, `TileUrlBuilder` avec `{$bbox}` arrondi comme `BigDecimal` HALF_UP), sources (`ZoomSet`, `LevelDef`, `TileSource`, `ClearColor`, `MapRef`, `UpdateDelay`), géo (`LonLat`, `LonLatZoom`, `Bounds`), lecture AQX (`AqxParser` sur `XMLParser`, DOCTYPE et entités refusés) ; workflow `ios.yml` | Vérifié le 2026-10-29 : run 40944407448 vert, 53 tests sur macOS et sur le simulateur iPhone 17 Pro (iOS 26.2). Run vert de `ios.yml` : `swift test` et `xcodebuild test` sur simulateur iPhone ; tests repris de `TileMathTest`, `TileUrlBuilderTest`, `AqxParserTest`, `ZoomSetTest`, `MapRefTest` (sauf l'aller-retour par `AqxWriter`), `UpdateDelayTest`, sur `fixtures/aqx/IGN_Topo_zoom_test.aqx` et `fixtures/aqx/user/` |
| P32.2 | 🔄 | **Aperçu du squelette d'application** : écran carte sur iPhone (état Android actuel, propositions iOS en variantes : barre, boutons, panneaux en feuilles iOS) | Validation explicite de l'utilisateur — aperçu `docs/previews/p32-ios-carte.html` et captures de tous les écrans `docs/previews/p32-ios-ecrans-simulateur.html`, **à valider a posteriori** (consigne du 2026-10-29) |
| P32.3 | ✅ | **Squelette `ios/ToolMap`** fidèle à l'aperçu : XcodeGen, SwiftUI, MapLibre Native iOS, carte OpenStreetMap puis sources intégrées (`builtin-sources.aqx` lu par `AqxParser`) ; build simulateur et capture `xcrun simctl io booted screenshot` en artefact | Capture du simulateur montrant la carte, posée à côté de l'aperçu validé |
| P32.4 | ✅ | **Tuiles servies par l'application** : `URLProtocol` installé dans la configuration réseau de MapLibre iOS (`MLNNetworkConfiguration`), URL construites par `TileUrlBuilder`, Referer / User-Agent / en-têtes / cookie de la `LevelDef`, stockage MBTiles (SQLite), mode hors ligne | Vérifié le 2026-10-29 : `TileServiceTests` (en-têtes de la `LevelDef`, tuile stockée puis resservie sans réseau, aucune requête hors ligne, erreur du serveur, zoom forcé composé) verts sur macOS et le simulateur (run 40959653747) ; carte IGN servie par l'intercepteur sur les captures de `ios-0.1.0` et `ios-0.1.1`. Tests sur serveur simulé (en-têtes, hors ligne sans requête) ; capture d'une tuile IGN servie par l'intercepteur |
| P32.5 | 🔄 | **`.app` du simulateur zippé** en artefact, à ouvrir dans le navigateur avec Appetize.io | Artefact téléchargeable ; ouverture dans Appetize faite par l'utilisateur |
| P32.6 | ✅ | **Suite du cœur**, par lots avec leurs tests : écriture AQX, géo (formats de coordonnées, UTM, MGRS, Lambert 93, grilles), sources (multi-zoom, identité des jeux de tuiles), calques (pile, plages de zoom, couleurs, favoris), traces (GPX, KML, GeoJSON, statistiques, opérations), réglages, recherche, mesures ; puis bibliothèque, enregistreur, alertes, itinéraires, impression, partage, sauvegarde | Vérifié le 2026-10-30 : run iOS 40979054267 vert, **1 052 tests** sur macOS et sur le simulateur iPhone, repris des tests Kotlin (table § 6.1). Un run vert par lot ; tests Kotlin du domaine repris |
| P32.7 | 🔄 | **Cartes et calques** à l'écran (pile de calques, plages de zoom, catalogue, ajout d'une carte), chaque écran par son aperçu | Écrits, compilés, captures du simulateur (release `ios-0.2.x`) ; comparaison aux aperçus Android validés à faire par l'utilisateur (a posteriori) |
| P32.8 | 🔄 | **Traces et waypoints** : stockage SQLite, import et export (document picker, « Ouvrir avec »), enregistreur GPS CoreLocation en arrière-plan | Tests du stockage verts (`LibraryRepositoryTests`, `LibraryCreationTests`, `LibraryDisplayTests`, `LibraryFoldersTests`) ; écrans et enregistreur compilés, captures avec la bibliothèque de démonstration ; **reste** : parcours simulé (`simctl location`) |
| P32.9 | 🔄 | **Téléchargement de zones** et stockage hors ligne (une file dans l'app, `beginBackgroundTask` en arrière-plan : une session `URLSession` d'arrière-plan espacerait les milliers de petites requêtes d'une zone) | Écrans compilés, captures ; **reste** : zone téléchargée puis affichée hors ligne sur le simulateur |
| P32.10 | 🔄 | **Partage de position** par le serveur de coordination Firebase (REST + SSE, AES-GCM par CryptoKit), interopérable avec Android | Vecteurs de test Kotlin relus et réécrits au bit près en Swift (verts) ; écrans compilés ; **reste** : un groupe mixte Android + iPhone (appareil réel) |
| P32.11 | 🔄 | **Impression PDF** (maths de `print/`, écrivain PDF, rendu CoreGraphics) | PDF produit sur le simulateur (capture 29, « PDF enregistré · 1 page ») ; maths vérifiées par les tests du cœur ; **reste** : mesurer 10 km = 200 mm ± 0,5 % à 1:50 000 sur le PDF imprimé |
| P32.12 | 🔄 | **Itinéraires** : moteur BRouter en ligne (ADR-102, **à confirmer par l'utilisateur**), plan d'étapes, dessin, « Aller vers », suivi de trace et alertes | Écrans compilés, itinéraire de démonstration sur la capture 14 ; **reste** : confirmation de l'ADR-102 et essai avec réseau |
| P32.13 | ⬜ | **Installation sur un iPhone** : décision de l'utilisateur (compte Apple Developer, TestFlight) ; préparé : `.github/workflows/ios-testflight.yml`, inactif sans les secrets (§ 5 bis) | Selon la décision |

## 5. Ce qui ne se porte pas tel quel

| Android | iOS | Décision |
|---|---|---|
| BRouter par AIDL (P19, ADR-064) : moteur hors ligne fourni par l'application BRouter | Aucune application BRouter sur iOS, et une application ne peut pas en appeler une autre de cette façon | Serveur BRouter en ligne (`brouter.de`), ADR-102, **à confirmer par l'utilisateur** |
| Mise à jour depuis GitHub (P31, ADR-094) | Interdit : une application ne s'installe que par l'App Store ou TestFlight | Pas d'équivalent |
| Services de premier plan : enregistreur, alertes, écoute du groupe (ADR-024, ADR-051, ADR-090) | Localisation en arrière-plan permise (`UIBackgroundModes` `location`), pas de service permanent sans elle | Enregistreur et alertes par la localisation en arrière-plan ; écoute du groupe à décider (pendant la localisation, ou notifications push, qui demandent un serveur) |
| Intercepteur OkHttp des tuiles (ADR-002) | `URLProtocol` dans la configuration réseau de MapLibre iOS | P32.4 |
| WorkManager (téléchargement de zone) | File de téléchargement dans l'app, poursuivie un moment en arrière-plan (`beginBackgroundTask`), reprise au lancement | ADR-103 (une session `URLSession` d'arrière-plan espacerait les milliers de petites requêtes d'une zone) |
| Room (traces, waypoints) | SQLite du système par le petit enveloppeur de `ToolMapCore`, **même schéma** que la base Room (`tracks.db`, version 1) | ADR-103 |
| Hilt | Injection simple en Swift | — |
| WebRTC direct et QR par la caméra (mode direct, P8) | Serveur Firebase seul ; QR d'invitation lu par AVFoundation | ADR-103 : pas de mode direct (paquet tiers WebRTC) |
| SAF, « Ouvrir avec », partage Android | `UIDocumentPicker`, types de documents déclarés, `ShareLink` | Avec chaque fonction |
| Baromètre (`SensorManager`) | `CMAltimeter` | Avec l'altimètre |
| Rendu PDF par `Bitmap` / `Canvas` | CoreGraphics ; l'écrivain PDF maison (ADR-020) se porte | P32.11 |
| Rapport de plantage affiché sur le téléphone (ADR-029) | Pas de capture globale simple des plantages Swift | À revoir |
| Textes `strings.xml` | Catalogue de chaînes Xcode (`Localizable.xcstrings`), en français | Avec chaque écran |
| Serveurs de cartes en `http://` (acceptés par Android) | Refusés par iOS (App Transport Security) : l'assistant le dit | À décider par l'utilisateur : lever la protection pour tous les serveurs est une baisse de sécurité (ADR-103) |
| Vérification sur appareil (GPS réel, capteurs, caméra, deux téléphones, écran éteint) | Simulateur seulement : position simulée, pas de capteurs | Tant que l'utilisateur n'a pas d'iPhone |

## 5 bis. Installer sur un iPhone (P32.13, préparé)

Rien n'est fait sans l'accord de l'utilisateur (ADR-100, point 6). Ce qui est prêt : le workflow manuel `.github/workflows/ios-testflight.yml` (Actions ▸ « iOS TestFlight » ▸ Run workflow), qui archive l'application signée et l'envoie à TestFlight, et qui ne fait rien tant que les secrets ci-dessous manquent.

Ce que l'utilisateur aurait à faire, une fois décidé :
1. Créer un compte Apple Developer (developer.apple.com, 99 € par an, identité vérifiée) ;
2. Dans App Store Connect : créer l'app « ToolMap » avec l'identifiant `fr.toolmap.ios`, puis une clé d'API (Utilisateurs et accès ▸ Intégrations ▸ API App Store Connect, rôle « App Manager ») ;
3. Mettre dans les secrets du dépôt GitHub : `APPLE_TEAM_ID` (10 caractères), `APPLE_ASC_KEY_ID`, `APPLE_ASC_ISSUER_ID`, `APPLE_ASC_KEY_P8` (contenu du fichier `.p8`) ;
4. Lancer « iOS TestFlight » ; après le traitement d'Apple, installer TestFlight sur l'iPhone et accepter l'invitation (test interne, sans revue d'Apple).

Sans compte : le `.app` du simulateur (release `ios-<version>`) se lance dans un navigateur par Appetize.io (téléverser le zip sur appetize.io, plateforme iOS).

## 6. Table de parité

**Android de référence : 0.4.144**, `origin/claude/vibrant-galileo-q3s99n` au commit `1637cb9` (2026-10-29) ; relevé le 2026-10-30 : cette branche est passée en 0.4.145 (`b4852ff`) sans changement de code (journal, plan et aperçu de l'impression vérifiée sur l'émulateur). Depuis 0.4.140 : menus de l'impression (P17.8, P17.9 ; à reporter avec P32.11), releases `android-<version>` (ADR-099, reprises par la CI iOS en `ios-<version>`), aucun changement des parties déjà portées.

### 6.1 Cœur (`ToolMapCore`, portage de `:core` et des parties testables de `:data` et `:p2p`)

Tests Swift Testing repris des tests Kotlin, verts sur macOS (`swift test`) et sur le simulateur iPhone (`xcodebuild test`) : run iOS 40972000611 du 2026-10-29, **1 011 tests**.

| Domaine | Android | iOS (`ios/ToolMapCore/Sources/ToolMapCore/`) | Statut |
|---|---|---|---|
| Géo : positions, formats, UTM, MGRS, Lambert 93, grilles, échelle, saisie | `geo/*` | `Geo/` | ✅ |
| Tuiles : maths, URL, recettes, composition, planification, PNG unis, service | `tiles/*`, intercepteur `:data` | `Tiles/` (dont `TileService`) | ✅ |
| Sources, AQX (lecture, écriture), sources intégrées, catalogue en ligne | `source/*`, `aqx/*`, `maps/OnlineCatalog.kt` | `Source/`, `Aqx/`, `Maps/` | ✅ |
| Stockage MBTiles | `:data` tiles | `Storage/` | ✅ |
| Calques, favoris, conditions d'affichage, couleurs | `layers/*` | `Layers/` | ✅ |
| Relief, altitude, baromètre, géoïde | `elevation/*`, `altitude/*` | `Elevation/`, `Altitude/` | ✅ |
| Traces et waypoints : GPX, KML, KMZ, GeoJSON, statistiques, opérations, icônes | `track/*` | `Track/`, `Glyphs/` (icônes reprises des drawables Android, `ios/tools/glyphs.py`) | ✅ |
| Bibliothèque (base Room `tracks.db`, même schéma) | `:data` tracks | `Library/` | ✅ |
| Enregistreur, alertes, suivi de trace | `record/*`, `alert/*` | `Record/`, `Alert/` | ✅ |
| Mesure, recherche, assistant d'ajout de carte | `measure/*`, `search/*`, `wizard/*` | `Measure/`, `Search/`, `Wizard/` | ✅ |
| Téléchargement de zone | `download/*` | `Download/` | ✅ |
| Réglages (même JSON), recherche des réglages | `settings/*` | `Settings/` | ✅ |
| Partage de position : trames, chiffrement, maillage, relais Firebase | `p2p/*`, `:p2p` | `P2P/` (vecteurs Kotlin relus au bit près, `ios/tools/p2p-vectors`) | ✅ sauf WebRTC direct |
| Impression et PDF | `print/*`, `pdf/*` | `Print/`, `Pdf/` | ✅ |
| Itinéraires | `route/*` | `Route/` (moteur `BRouterWebEngine`, ADR-102) | ✅ |
| Sauvegarde, journal d'événements, plantages, état, textes | `backup/*`, `eventlog/*`, `crash/*`, `state/*`, `text/*` | `Backup/`, `EventLog/`, `Crash/`, `State/`, `Text/` | ✅ |
| Mise à jour depuis GitHub | `update/AppUpdate.kt` | — | Pas d'équivalent (§ 5) |

### 6.2 Application (`ios/ToolMap/Sources/`)

| Écran ou fonction (`docs/specs/ui-index.md`) | iOS | Statut |
|---|---|---|
| Écran carte, pastilles, point central et son menu, boutons, barre (§2) | `UI/MapScreen.swift`, `UI/InfoPills.swift`, `UI/Map/` | écrit |
| Menu principal, Masquer, Verrouiller (§3) | `UI/Panels/MenuPanel.swift`, `LockViews.swift` | écrit |
| Positionnement, enregistreur (§4, T4) | `UI/Panels/PositionPanel.swift`, `UI/Record/`, `Controllers/RecorderController.swift` | écrit |
| Cartes et calques, cartes disponibles, catalogue, assistant, multi-zoom (§5) | `UI/Panels/MapsPanel.swift`, `UI/Maps/`, `Controllers/MapsController.swift` | écrit |
| Traces et waypoints : panneau, liste, détail, fiche, dialogue waypoint (§6, §2.4) | `UI/Panels/TracksPanel.swift`, `UI/Library/`, `Controllers/LibraryController.swift` | écrit |
| Outils : mesure (T2), dessin et itinéraire (T1, T1b), zone (T5), impression (T3, T3b) | `UI/Tools/`, `UI/Print/`, `Controllers/` | écrit |
| Aller vers, suivi de trace (G8, G9) | `UI/Route/`, `Controllers/RoutingController.swift` | écrit |
| Recherche, partage, téléchargements, stockage, réglages, sauvegarde, aide, nouveautés (§7, §8) | `UI/Search/`, `UI/Share/`, `UI/Downloads/`, `UI/Settings/`, `UI/About/` | écrit |
| Grille UTM / MGRS / Lambert 93 sur la carte | `Map/Layers/GridLayers.swift`, `UI/Map/GridFiguresOverlay.swift` | écrit |
| Mode direct du partage (WebRTC, QR de réponse) | — | non porté (§ 5) |
| Mise à jour depuis GitHub, Quitter, barre de navigation | — | sans objet sur iPhone |
