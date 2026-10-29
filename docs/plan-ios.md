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
| P32.2 | 🔄 | **Aperçu du squelette d'application** : écran carte sur iPhone (état Android actuel, propositions iOS en variantes : barre, boutons, panneaux en feuilles iOS) | Validation explicite de l'utilisateur |
| P32.3 | ✅ | **Squelette `ios/ToolMap`** fidèle à l'aperçu : XcodeGen, SwiftUI, MapLibre Native iOS, carte OpenStreetMap puis sources intégrées (`builtin-sources.aqx` lu par `AqxParser`) ; build simulateur et capture `xcrun simctl io booted screenshot` en artefact | Capture du simulateur montrant la carte, posée à côté de l'aperçu validé |
| P32.4 | 🔄 | **Tuiles servies par l'application** : `URLProtocol` installé dans la configuration réseau de MapLibre iOS (`MLNNetworkConfiguration`), URL construites par `TileUrlBuilder`, Referer / User-Agent / en-têtes / cookie de la `LevelDef`, stockage MBTiles (SQLite), mode hors ligne | Tests sur serveur simulé (en-têtes, hors ligne sans requête) ; capture d'une tuile IGN servie par l'intercepteur |
| P32.5 | 🔄 | **`.app` du simulateur zippé** en artefact, à ouvrir dans le navigateur avec Appetize.io | Artefact téléchargeable ; ouverture dans Appetize faite par l'utilisateur |
| P32.6 | ⬜ | **Suite du cœur**, par lots avec leurs tests : écriture AQX, géo (formats de coordonnées, UTM, MGRS, Lambert 93, grilles), sources (multi-zoom, identité des jeux de tuiles), calques (pile, plages de zoom, couleurs, favoris), traces (GPX, KML, GeoJSON, statistiques, opérations), réglages, recherche, mesures | Un run vert par lot ; tests Kotlin du domaine repris |
| P32.7 | ⬜ | **Cartes et calques** à l'écran (pile de calques, plages de zoom, catalogue, ajout d'une carte), chaque écran par son aperçu | Captures du simulateur à côté des aperçus validés |
| P32.8 | ⬜ | **Traces et waypoints** : stockage SQLite, import et export (document picker, « Ouvrir avec »), enregistreur GPS CoreLocation en arrière-plan | Tests du stockage ; parcours simulé (`simctl location`) |
| P32.9 | ⬜ | **Téléchargement de zones** (`URLSession` en arrière-plan) et stockage hors ligne | Zone téléchargée puis affichée hors ligne sur le simulateur |
| P32.10 | ⬜ | **Partage de position** par le serveur de coordination Firebase (REST + SSE, AES-GCM par CryptoKit), interopérable avec Android | Vecteurs de test Kotlin décodés en Swift et inversement ; un groupe mixte Android + simulateur |
| P32.11 | ⬜ | **Impression PDF** (maths de `print/`, écrivain PDF, rendu CoreGraphics) | Segment de 10 km = 200 mm ± 0,5 % à 1:50 000 sur le PDF |
| P32.12 | ⬜ | **Itinéraires** : décision de l'utilisateur sur le moteur (§ 5), puis portage du plan d'étapes | Selon la décision |
| P32.13 | ⬜ | **Installation sur un iPhone** : décision de l'utilisateur (compte Apple Developer, TestFlight) | Selon la décision |

## 5. Ce qui ne se porte pas tel quel

| Android | iOS | Décision |
|---|---|---|
| BRouter par AIDL (P19, ADR-064) : moteur hors ligne fourni par l'application BRouter | Aucune application BRouter sur iOS, et une application ne peut pas en appeler une autre de cette façon | À décider (P32.12) : serveur BRouter en ligne, moteur embarqué, ou pas d'itinéraire calculé |
| Mise à jour depuis GitHub (P31, ADR-094) | Interdit : une application ne s'installe que par l'App Store ou TestFlight | Pas d'équivalent |
| Services de premier plan : enregistreur, alertes, écoute du groupe (ADR-024, ADR-051, ADR-090) | Localisation en arrière-plan permise (`UIBackgroundModes` `location`), pas de service permanent sans elle | Enregistreur et alertes par la localisation en arrière-plan ; écoute du groupe à décider (pendant la localisation, ou notifications push, qui demandent un serveur) |
| Intercepteur OkHttp des tuiles (ADR-002) | `URLProtocol` dans la configuration réseau de MapLibre iOS | P32.4 |
| WorkManager (téléchargement de zone) | `URLSession` en arrière-plan | P32.9 |
| Room (traces, waypoints) | SQLite (bibliothèque à choisir à P32.8, ADR à ce moment) | P32.8 |
| Hilt | Injection simple en Swift | — |
| WebRTC direct et QR par la caméra (mode direct, P8) | WebRTC iOS (paquet tiers) et AVFoundation | Après le serveur Firebase (P32.10) |
| SAF, « Ouvrir avec », partage Android | `UIDocumentPicker`, types de documents déclarés, `ShareLink` | Avec chaque fonction |
| Baromètre (`SensorManager`) | `CMAltimeter` | Avec l'altimètre |
| Rendu PDF par `Bitmap` / `Canvas` | CoreGraphics ; l'écrivain PDF maison (ADR-020) se porte | P32.11 |
| Rapport de plantage affiché sur le téléphone (ADR-029) | Pas de capture globale simple des plantages Swift | À revoir |
| Textes `strings.xml` | Catalogue de chaînes Xcode (`Localizable.xcstrings`), en français | Avec chaque écran |
| Vérification sur appareil (GPS réel, capteurs, caméra, deux téléphones, écran éteint) | Simulateur seulement : position simulée, pas de capteurs | Tant que l'utilisateur n'a pas d'iPhone |

## 6. Table de parité

**Android de référence : 0.4.144**, `origin/claude/vibrant-galileo-q3s99n` au commit `1637cb9` (2026-10-29). Depuis 0.4.140 : menus de l'impression (P17.8, P17.9 ; à reporter avec P32.11), releases `android-<version>` (ADR-099, reprises par la CI iOS en `ios-<version>`), aucun changement des parties déjà portées.

| Domaine | Android (`core/src/main/kotlin/fr/toolmap/core/`) | iOS (`ios/ToolMapCore/Sources/ToolMapCore/`) | Statut |
|---|---|---|---|
| Géo : positions, emprises | `geo/LonLat.kt` (`LonLat`, `LonLatZoom`, `Bounds`) | `Geo/LonLat.swift` | ✅ P32.1 |
| Tuiles : maths, URL | `tiles/TileMath.kt`, `tiles/TileUrlBuilder.kt` | `Tiles/TileMath.swift`, `Tiles/TileUrlBuilder.swift`, `Tiles/DecimalText.swift` | ✅ P32.1 |
| Sources : niveaux, zooms, références, délais | `source/TileSource.kt`, `source/ZoomSet.kt`, `source/MapRef.kt`, `source/UpdateDelay.kt` | `Source/*.swift` | ✅ P32.1 |
| AQX : lecture | `aqx/AqxModel.kt`, `aqx/AqxParser.kt` | `Aqx/AqxModel.swift`, `Aqx/AqxParser.swift`, `Aqx/XmlTree.swift` | ✅ P32.1 |
| AQX : écriture, sources intégrées, catalogue | `aqx/AqxWriter.kt`, `source/BuiltinSources.kt`, `maps/OnlineCatalog.kt` | — | ⬜ P32.6 |
| Tuiles : planification, recettes, PNG unis | `tiles/TilePlanner.kt`, `tiles/TileResolver.kt`, `tiles/SolidPng.kt` | — | ⬜ P32.4, P32.9 |
| Sources : multi-zoom, identité, doublons, crédits | `source/MultiZoomDraft.kt`, `source/TileSetIdentity.kt`, `source/SourceDuplicates.kt`, `source/Credits.kt` | — | ⬜ P32.6 |
| Géo : formats, projections, grilles, échelle | `geo/*` (14 fichiers) | — | ⬜ P32.6 |
| Calques et favoris | `layers/*` (8 fichiers) | — | ⬜ P32.6, P32.7 |
| Cartes : catalogue, zone, bande | `maps/*` (4 fichiers) | — | ⬜ P32.7, P32.9 |
| Traces et waypoints | `track/*` (26 fichiers) | — | ⬜ P32.6, P32.8 |
| Enregistreur | `record/*` (6 fichiers) | — | ⬜ P32.8 |
| Alertes, suivi de trace | `alert/*` (4 fichiers) | — | ⬜ |
| Altitude, baromètre, terrain | `altitude/*`, `elevation/*` | — | ⬜ |
| Mesure | `measure/*` | — | ⬜ P32.6 |
| Recherche | `search/*` | — | ⬜ P32.6 |
| Réglages | `settings/*` | — | ⬜ P32.6 |
| Assistant d'ajout de carte | `wizard/*` (6 fichiers) | — | ⬜ |
| Téléchargement de zone | `download/ZoneRequest.kt` | — | ⬜ P32.9 |
| Partage de position | `p2p/*` (13 fichiers) | — | ⬜ P32.10 |
| Impression et PDF | `print/*` (15 fichiers), `pdf/*` | — | ⬜ P32.11 |
| Itinéraires | `route/*` (12 fichiers) | — | ⬜ P32.12 |
| Sauvegarde, journal d'événements, plantages, état | `backup/*`, `eventlog/*`, `crash/*`, `state/*` | — | ⬜ |
| Textes sur la carte | `text/*` | — | ⬜ |
| XML, JSON | `xml/*` | `Aqx/XmlTree.swift` (lecture) | ✅ P32.1 |
| Mise à jour depuis GitHub | `update/AppUpdate.kt` | — | Pas d'équivalent (§ 5) |
