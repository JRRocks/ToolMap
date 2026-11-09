# Langage par domaine et moteur de calcul natif en Rust (P38)

> Demande de l'utilisateur du 2026-11-09 : « Analyse mon projet, et dis-moi ce qui peut être amélioré en termes de
> performances si on utilise le langage de code le plus efficient par domaine. Exemple : mettre du Rust pour les
> moteurs de calcul. Fais-moi une version debug à tester sur émulateur avant de mettre en release avec les nouveaux
> langages de code. »
>
> Décision : ADR-119. Code : `engine/` (crate Rust `engine/src/main/rust`, côté Kotlin `fr.toolmap.engine`),
> `core/.../compute/Compute.kt` (la porte), `app/.../perf/EngineBench.kt` (mesure sur l'appareil).

## 1. Quel langage pour quel domaine

Le constat qui commande tout le reste : **l'essentiel du travail lourd de ToolMap tourne déjà en code natif**, dans
les bibliothèques qu'il utilise. Changer de langage n'apporte quelque chose que là où du calcul intensif est encore
écrit en Kotlin, et seulement si le gain dépasse le coût du passage d'un langage à l'autre (JNI : environ une
microseconde par appel, plus la copie des tableaux).

| Domaine | Aujourd'hui | Le plus efficace | Gain à attendre d'un changement | Décision |
|---|---|---|---|---|
| Dessin de la carte | MapLibre Native, C++ et OpenGL ES | déjà en place | aucun | rien à faire |
| Décodage des tuiles (PNG, JPEG, WebP), JPEG des bandes du PDF | Skia, C++ (`BitmapFactory`, `Bitmap.compress`) | déjà en place | aucun | rien à faire |
| Stockage (traces, tuiles MBTiles) | SQLite, C (Room, `MbTilesFile`) | déjà en place | aucun ; les gains possibles sont dans les requêtes, pas dans le langage | rien à faire |
| Compression (sauvegardes, PDF, codes d'appairage) | zlib, C (`java.util.zip`) | déjà en place | aucun | rien à faire |
| Chiffrement du partage (AES-256-GCM) | BoringSSL, C et assembleur (Conscrypt) | déjà en place | aucun | rien à faire |
| Liaison directe du partage | libwebrtc, C++ | déjà en place | aucun | rien à faire |
| Réseau (tuiles, serveur de coordination) | OkHttp, Kotlin et Java | le temps est celui du réseau, pas du processeur | aucun | rien à faire |
| Interface | Jetpack Compose, Kotlin | Kotlin est le langage de la plateforme ; tout autre choix serait une réécriture plus lente à démarrer | aucun par le langage. **Le levier est ailleurs** : l'APK distribué est un build debuggable sans R8 (`performance.md` PF9), ce qui ralentit tout le code Kotlin, interface comprise ; c'est la décision P36.7 | à décider (P36.7) |
| **Calcul par pixel des tuiles dessinées ici** : fusion des calques (16 modes), teinte, matrice de couleur, masque d'altitude | Kotlin (`Blend`, `MaskRaster`), boucles de flottants pixel par pixel | **Rust** | fort : § 4 | ✅ porté |
| **PNG des tuiles dessinées ici** (fusionnées, découpées, complétées) | Skia, mais avec ses réglages les plus lents (tous les filtres essayés, zlib niveau 6), pour une image jamais stockée | **Rust** (`png` + `fdeflate`) | fort : c'était le poste le plus lourd d'une tuile dessinée (§ 4). Environ les trois quarts de ce gain viennent du réglage (compression rapide), le reste du langage | ✅ porté |
| Fusion des calques dans le PDF (`AndroidBandRenderer.mix`, une bande entière) | Kotlin (`Blend`) | Rust | le même que pour les tuiles, sur des millions de pixels par bande | ✅ porté (mêmes fonctions) |
| Simplification des tracés (Douglas-Peucker) | Kotlin (`PathSimplifier`) | Rust | faible : § 4 | ✅ porté, gain à confirmer sur téléphone |
| Lecture des GPX, KML, GeoJSON | Kotlin (`XmlPullParser`) | Rust lirait le texte 3 à 5 fois plus vite | un import de gros fichier plus court, opération rare ; le lecteur garde les extensions XML inconnues, les espaces de noms et un journal des anomalies : le réécrire est risqué | non retenu |
| Statistiques, profil, projections (UTM, MGRS, Lambert 93), grilles | Kotlin | Kotlin | quelques centaines de points par appel : le passage en natif coûterait plus que le calcul | non retenu |
| Glyphes des noms sur la carte (champ de distance, `SignedDistance`) | Kotlin, une fois par plage de caractères puis gardé en mémoire | Rust irait plus vite | une fois par lancement et par plage : invisible | non retenu |
| Écriture du PDF (`PdfWriter`, `PageComposer`) | Kotlin pour le texte, Skia et zlib pour les images | déjà en place pour ce qui pèse | le temps d'un PDF est dans les tuiles et le JPEG | rien à faire |
| Codec et maillage du partage | Kotlin | Kotlin | messages de quelques dizaines d'octets | non retenu |
| Scripts et outils (`tools/`, `scripts/`) | bash, Node, PowerShell | hors de l'application | — | rien à faire |

En résumé : sur une vingtaine de domaines, **trois** gagnent à changer de langage (calcul par pixel, PNG des tuiles
dessinées, et dans une moindre mesure la simplification des tracés) ; ils forment le « moteur de calcul » de ce
document. Tout le reste est déjà natif ou trop léger pour que le langage compte.

## 2. Ce que fait le moteur

Une bibliothèque Rust, `libtoolmap_engine.so` (0,3 à 0,5 Mo par architecture, 1,2 Mo dans l'APK), appelée par JNI :

| Fonction | Remplace | Sert à |
|---|---|---|
| `composite` | `Blend.compositeReference` | tuiles d'un calque fusionné, bandes du PDF, vignettes de la feuille « Fusion » |
| `tint`, `adjust` | `Blend.tintReference`, `Blend.adjustReference` | teinte et réglages de couleur d'un calque fusionné |
| `maskAlphas`, `applyAlphas` | `MaskRaster.alphas(elevations(…))`, boucle de `BitmapElevationMasker` | tuiles d'un calque à bande d'altitude (écran et PDF) |
| `encodePng` | `Bitmap.compress(PNG)` pour les tuiles dessinées | tuiles fusionnées, découpées, complétées (zoom forcé, zoom manquant) |
| `keptIndices` | `PathSimplifier.keptIndicesReference` (à partir de 256 points) | tracés affichés, couloirs de téléchargement, croquis |

**Mêmes résultats, au bit près.** Chaque fonction refait les mêmes opérations en virgule flottante, dans le même
ordre, que le code Kotlin (y compris les règles de Java pour `min`, `max`, la racine carrée et les arrondis) : les
pixels, les opacités et les points gardés sont identiques. Ce qui change est la façon de faire le travail : le mode
choisi une fois par appel et non par canal, aucune allocation par pixel, la division par 255 lue dans une table, et
surtout **une table par mode et par opacité pour un pixel opaque sur un pixel opaque** (le cas de presque toute une
carte), remplie par la formule elle-même. Seul écart, voulu : le PNG. Il contient exactement les pixels calculés,
alors qu'Android les faisait passer par ses couleurs prémultipliées ; un pixel entièrement transparent y est écrit
noir, comme Android le fait. Une fois relu par Android, le résultat est le même.

Le code Kotlin reste en place, inchangé, sous le nom `…Reference` : il sert de référence aux tests et de repli.

## 3. Architecture

```
:core   fr.toolmap.core.compute.Compute.accelerator   ← la porte (null : le code Kotlin fait tout)
           Blend.composite / tint / adjust, MaskRaster.tileAlphas / applyAlphas,
           PathSimplifier.keptIndices, Compute.fastPng   → l'accélérateur s'il existe, sinon Kotlin
:engine fr.toolmap.engine.NativeEngine   charge la bibliothèque, vérifie sa version, la compare au code Kotlin,
                                          puis s'installe à la porte
        fr.toolmap.engine.NativeBridge   les fonctions JNI
        src/main/rust                    blend.rs, mask.rs, tile_png.rs, simplify.rs, lib.rs (la porte JNI)
:app    ToolMapApp.startNativeEngine     installe le moteur au démarrage, hors du fil principal
```

- **Aucun appelant ne change** : `BitmapTileBlender`, `AndroidBandRenderer`, `BlendSheet`, `TrackDisplay`… appellent
  les mêmes fonctions de `:core` qu'avant. `:core` reste un module Kotlin pur, sans dépendance vers `:engine`.
- **Règles de la porte JNI** (`lib.rs`) : les tableaux sont copiés par morceaux de 65 536 pixels
  (`Get…ArrayRegion` / `Set…ArrayRegion`), jamais épinglés (le ramasse-miettes n'est jamais retenu) ; les tailles sont
  vérifiées avant toute lecture ou écriture ; une panique ne traverse jamais la porte (`catch_unwind`).
- **Le moteur ne peut que faire gagner du temps** : s'il refuse un appel (arguments hors limites, erreur JNI, panique),
  rien n'a été écrit et le code Kotlin fait le travail ; le refus est signalé (`Problems.unexpected("engine.declined")`,
  donc ticket GitHub, ADR-116). Un arrêt après écriture partielle (jamais vu, les tailles étant vérifiées avant) lève
  une exception : la tuile échoue comme une tuile illisible, et le problème est signalé.
- **Contrôle au chargement** (`NativeEngine.install`, quelques millisecondes, hors du fil principal) : version de la
  porte (`ABI_VERSION`, contre une bibliothèque périmée), puis une série de calculs faits par les deux moteurs (pixels
  opaques et transparents, table, mode non séparable, bande d'altitude, PNG, tracé). Au moindre écart, le moteur n'est
  pas installé : `engine.selftest`, `engine.version` ou `engine.load` dans le journal et en ticket.
- Journal : événement `engine.state` (`state`, `native`) au démarrage ; logcat, étiquette `ToolMapEngine`
  (`engine native=1 state=active abi=1 arch=…`).
- Le code Rust n'a que deux dépendances : `jni-sys` 0.3.0 (les types de JNI, aucune dépendance) et `png` 0.18
  (avec `fdeflate`, `crc32fast`, `flate2`, `miniz_oxide`), versions figées par `Cargo.lock`. Seul `lib.rs` contient du
  code `unsafe` (les appels JNI).

## 4. Mesures

### 4.1 Sur la machine de build (JVM HotSpot, 2026-11-09)

`./gradlew :data:testDebugUnitTest --tests '*TilePipelineBenchmark*' -Ptoolmap.engine.bench=true`, médianes, tuiles
de 256 px découpées dans `docs/previews/p26/map-ign-scan25.jpg` et `map-ign-plan.jpg`. La JVM d'un poste compile le
Kotlin bien mieux qu'un téléphone : pour les calculs par pixel, ces gains sont un **plancher**.

| Étape (une tuile de 256 px) | Kotlin | Rust | Gain |
|---|---|---|---|
| Fusion Produit, pixels opaques, opacité 100 % | 1,05 ms | 0,13 ms | × 7,9 |
| Fusion Produit, pixels opaques, opacité 60 % | 1,05 ms | 0,13 ms | × 8,1 |
| Fusion Lumière tamisée, pixels opaques | 1,40 ms | 0,13 ms | × 10,7 |
| Fusion Normal, calque en partie transparent | 0,56 ms | 0,54 ms | × 1,0 |
| Fusion Incrustation, calque en partie transparent, 80 % | 1,01 ms | 0,67 ms | × 1,5 |
| Fusion Couleur (mode non séparable) | 1,70 ms | 1,17 ms | × 1,4 |
| Teinte | 2,10 ms | 0,93 ms | × 2,3 |
| Matrice de couleur | 0,62 ms | 0,28 ms | × 2,2 |
| Masque d'altitude (altitudes et opacités) | 1,03 ms | 0,66 ms | × 1,6 |
| PNG : `Bitmap.compress` d'Android (110 Ko) | 11,0 ms | 0,42 ms (137 Ko) | × 26 |
| PNG : pour comparaison, un écrivain Kotlin avec zlib au plus rapide | 3,1 ms (128 Ko) | 0,42 ms | × 7,5 |
| **Tuile fusionnée entière** (3 calques, celui du dessus en Produit) | 28,0 ms | 5,4 ms | **× 5,1** |
| **Tuile découpée par une bande d'altitude** | 9,2 ms | 2,6 ms | **× 3,5** |
| **Tuile complétée** (agrandie d'un zoom plus grossier) | 29,7 ms | 3,2 ms | **× 9,4** |
| Douglas-Peucker, 50 000 points, tolérance 2 m | 2,6 ms | 2,4 ms | × 1,1 |

Ce que disent ces chiffres : le PNG était bien le poste le plus lourd ; une fois le moteur en place, il reste dans
une tuile fusionnée le décodage des images par Skia (déjà natif). Là où le calcul reste en flottants pixel par pixel
(calque en partie transparent, modes non séparables), une bonne JVM fait presque aussi bien que Rust ; l'écart se
creuse sur téléphone, où le compilateur d'Android est moins fort. Le Douglas-Peucker ne gagne presque rien ici.

### 4.2 Sur l'émulateur de la CI (ART, Android 14, x86_64)

`tools/emulator/smoke.sh` relance l'APK de debug avec la mesure demandée (`EngineBench`, tuiles dessinées par
l'application) et écrit ses lignes dans le journal du job. Un APK de debug est debuggable : Android y compile le
Kotlin sans toutes ses optimisations, comme sur le téléphone de l'utilisateur aujourd'hui (l'APK distribué est le
même). Chiffres du premier run : § 4.3.

### 4.3 Résultats sur l'émulateur

À remplir au premier run vert (journal du job « CI », étape « Check the APK on an emulator »).

### 4.4 Mesurer soi-même

```bash
# Machine de build (indicatif)
./gradlew :data:testDebugUnitTest --tests '*TilePipelineBenchmark*' -Ptoolmap.engine.bench=true
# Émulateur, APK de debug ou benchmark installé
bash tools/perf/E-engine.sh
# Un scénario complet avec puis sans le moteur (APK benchmark)
bash tools/perf/B-blend.sh
ENGINE=kotlin bash tools/perf/B-blend.sh
```

## 5. Debug d'abord, release ensuite

Demande de l'utilisateur : une version debug à essayer sur l'émulateur **avant** la mise en release.

| Type de build | Bibliothèque dans l'APK | Moteur aux commandes (`BuildConfig.NATIVE_ENGINE`) |
|---|---|---|
| `debug` (APK de la CI, celui de l'essai) | oui | **oui** |
| `benchmark` (mesures) | oui | oui ; `perf.engine=kotlin` l'éteint pour un lancement, `perf.engine=bench` lance la mesure |
| `release` | oui | **non**, tant que l'utilisateur n'a pas validé l'essai sur l'émulateur (P38.6) |

La mise en release est une ligne (`NATIVE_ENGINE` à `true` dans le type `release` de `app/build.gradle.kts`), à faire
quand l'utilisateur le dit. Rien de ce travail n'atteint `main` (la branche dont les téléphones tirent leurs mises à
jour) sans une fusion demandée par l'utilisateur.

Le contrôle sur émulateur de la CI (`smoke.sh`, ADR-117) échoue si un APK qui contient la bibliothèque ne l'a pas aux
commandes (hors release), et si l'application plante pendant la mesure.

## 6. Compiler

| Outil | Version | Où |
|---|---|---|
| Rust | 1.97.0, cibles `aarch64-linux-android`, `armv7-linux-androideabi`, `x86_64-linux-android` | `engine/src/main/rust/rust-toolchain.toml` : `rustup` installe tout seul la version et les cibles au premier `cargo` |
| NDK Android | 28.2.13676358 (r28c), pour son éditeur de liens | `scripts/install-android-sdk.sh` (`ndk;28.2.13676358`), `pinnedNdk` dans `engine/build.gradle.kts` |
| Crates | `Cargo.lock` | `cargo build --locked` |

- Gradle fait tout : `:engine:cargoBuild<Variante>JniLibs` compile la bibliothèque pour les trois architectures de
  l'APK (pages mémoire de 16 Ko comprises) et la range dans les bibliothèques natives du module ;
  `:engine:cargoBuildHost` la compile pour la machine de build (tests) ; `:engine:cargoTest` lance les tests du crate
  dans `check`. cargo travaille dans `engine/build/rust`, jamais dans les sources.
- **Rust et le NDK sont facultatifs sur un poste de développement.** Sans eux, le build le dit (« moteur natif absent
  de ce build »), l'APK n'a pas la bibliothèque, l'application le sait (`BuildConfig.NATIVE_LIBRARY` de `:engine`) et
  le code Kotlin fait tout, comme avant ; les tests de parité sont sautés. **Ils sont obligatoires en CI** (`CI=true`,
  ou `-Ptoolmap.engine.required=true`) : un APK distribué a toujours la bibliothèque.
- Session cloud : Rust est dans l'image ; le hook `session-start.sh` installe le NDK et prépare les crates (sans
  bloquer la session s'il n'y arrive pas).
- Poste Windows : installer Rust par [rustup](https://rustup.rs) (le reste suit `rust-toolchain.toml`) et le NDK par
  Android Studio (SDK Manager ▸ SDK Tools ▸ « NDK (Side by side) », version 28.2.13676358) ; sans cela, le build local
  marche sans le moteur.
- Les sources Rust comptent dans le numéro de version (`appSourceFingerprint` lit `engine/src`).

## 7. Tests et garde-fous

| Vérification | Où | Quand |
|---|---|---|
| Tests du crate (valeurs du W3C, table des pixels opaques contre la formule, PNG relu, tracés) | `cargo test` (`:engine:cargoTest`) | `check` |
| **Parité avec le code Kotlin**, au bit près : 16 modes × 6 opacités sur 40 000 pixels de toute sorte, toutes les paires de valeurs opaques × 4 opacités, teinte, matrices, bandes d'altitude (5 fenêtres × 5 bandes), tracés de 0 à 60 000 points, arguments refusés | `EngineParityTest` (`:engine`) | `check` |
| Tuiles dessinées avec le moteur : mêmes pixels que le code Kotlin dans le PNG, et même image une fois relue par Android (fusion, bande d'altitude, JPEG découpé, tuile complétée) | `NativeTilePipelineTest` (`:data`, Robolectric) | `check` |
| Aucun appel muet (`engine.load`, `engine.version`, `engine.selftest`, `engine.declined`) | `SilentFailureTest` | `check` |
| Moteur chargé et aux commandes sur un vrai Android, pas de plantage pendant la mesure | `tools/emulator/smoke.sh` | CI, avant toute publication |

Les tests de parité tournent sur la bibliothèque compilée pour la machine de build : le même code Rust que sur
téléphone, compilé pour un autre processeur. Les opérations en virgule flottante utilisées (IEEE 754, sans
contraction ni mode rapide) donnent les mêmes résultats sur x86_64 et ARM ; le contrôle au chargement (§ 3) le
revérifie sur chaque appareil, à chaque lancement.

## 8. Essayer sur l'émulateur (utilisateur)

1. Installer l'APK de debug : `adb install -r -g ToolMap-debug-<version>.apk`.
2. Vérifier que le moteur est aux commandes : `adb logcat -d -s ToolMapEngine` doit montrer
   `engine native=1 state=active`.
3. Parcourir ce que le moteur dessine : Cartes et calques ▸ un calque ▸ Couleurs ▸ **Fusion** (Produit, Lumière
   tamisée, Couleur) et **Teinter** ; ⋮ ▸ **Conditions d'affichage** avec une bande d'altitude (favori « Sortie
   Ski ») ; zoomer au-delà du dernier zoom d'une carte (tuiles complétées) ; **Imprimer** un PDF avec un calque
   fusionné ; ouvrir un long tracé.
4. Mesurer : `bash tools/perf/E-engine.sh` ; pour un scénario complet, `tools/perf/B-blend.sh` avec et sans
   `ENGINE=kotlin` (APK benchmark).
5. Dire le résultat : la mise en release (§ 5) attend ce retour.
