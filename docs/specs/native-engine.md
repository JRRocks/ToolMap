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
| Interface | Jetpack Compose, Kotlin | Kotlin est le langage de la plateforme ; tout autre choix serait une réécriture plus lente à démarrer | aucun par le langage. **Le levier est ailleurs** : l'APK distribué était un build debuggable sans R8 (`performance.md` PF9), ce qui ralentit tout le code Kotlin, interface comprise | ✅ non debuggable depuis P36.7 (ADR-120, 2026-11-09) ; reste R8, que le build benchmark permet de mesurer |
| **Calcul par pixel des tuiles dessinées ici** : fusion des calques (16 modes), teinte, matrice de couleur, masque d'altitude | Kotlin (`Blend`, `MaskRaster`), boucles de flottants pixel par pixel | **Rust** | fort : § 4 | ✅ porté |
| **PNG des tuiles dessinées ici** (fusionnées, découpées, complétées) | Skia, mais avec ses réglages les plus lents (tous les filtres essayés, zlib niveau 6), pour une image jamais stockée | **Rust** (`png` + `fdeflate`) | fort : c'était le poste le plus lourd d'une tuile dessinée (§ 4). Environ les trois quarts de ce gain viennent du réglage (compression rapide), le reste du langage | ✅ porté |
| Fusion des calques dans le PDF (`AndroidBandRenderer.mix`, une bande entière) | Kotlin (`Blend`) | Rust | le même que pour les tuiles, sur des millions de pixels par bande | ✅ porté (mêmes fonctions) |
| Simplification des tracés (Douglas-Peucker) | Kotlin (`PathSimplifier`) | Rust | presque nul sur une JVM de poste (× 1,1), × 2,2 sur Android (émulateur, APK non debuggable ; × 20 quand l'APK était debuggable) : § 4 | ✅ porté |
| Lecture des GPX, KML, GeoJSON | Kotlin (`XmlPullParser`) | Rust lirait le texte 3 à 5 fois plus vite | un import de gros fichier plus court, opération rare ; le lecteur garde les extensions XML inconnues, les espaces de noms et un journal des anomalies : le réécrire est risqué | non retenu |
| Statistiques, profil, projections (UTM, MGRS, Lambert 93), grilles | Kotlin | Kotlin | quelques centaines de points par appel : le passage en natif coûterait plus que le calcul | non retenu |
| Glyphes des noms sur la carte (champ de distance, `SignedDistance`) | Kotlin, une fois par plage de caractères puis gardé en mémoire | Rust irait plus vite | une fois par lancement et par plage : invisible | non retenu |
| Écriture du PDF (`PdfWriter`, `PageComposer`) | Kotlin pour le texte, Skia et zlib pour les images | déjà en place pour ce qui pèse | le temps d'un PDF est dans les tuiles et le JPEG | rien à faire |
| Codec et maillage du partage | Kotlin | Kotlin | messages de quelques dizaines d'octets | non retenu |
| Scripts et outils (`tools/`, `scripts/`) | bash, Node, PowerShell | hors de l'application | — | rien à faire |

En résumé : sur une vingtaine de domaines, **trois** gagnent à changer de langage (calcul par pixel, PNG des tuiles
dessinées, simplification des tracés) ; ils forment le « moteur de calcul » de ce document. Tout le reste est déjà
natif ou trop léger pour que le langage compte.

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
  (`engine native=1 state=active abi=1 arch=…`). Mesure à la demande : `adb shell dumpsys activity
  fr.toolmap/.MainActivity engine-bench` (`MainActivity.dump`, builds debug et benchmark).
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

`tools/emulator/smoke.sh` demande la mesure à l'application lancée (`adb shell dumpsys activity
fr.toolmap/.MainActivity engine-bench` → `EngineBench`, tuiles dessinées par l'application) et écrit ses lignes dans
le journal du job. Seuls `adb` et le système peuvent faire parler une activité par `dumpsys` : la commande marche sur
un APK non debuggable, et une release n'écoute pas.

### 4.3 Résultats sur l'émulateur

Run « CI » 42707577075 (2026-11-09, commit 6bb9e2b, **0.4.222, APK encore debuggable**), émulateur
`sdk_gphone64_x86_64`, Android 14, 2 cœurs ; médianes de 9 passages après 4 d'échauffement, une tuile de 256 px.

| Étape | Kotlin (ART, APK debuggable) | Rust | Gain |
|---|---|---|---|
| Fusion Produit, pixels opaques | 6,16 ms | 0,12 ms | × 53 |
| Fusion Normal, pixels opaques, opacité 60 % | 5,39 ms | 0,12 ms | × 45 |
| Fusion Lumière tamisée, pixels opaques | 10,61 ms | 0,12 ms | × 89 |
| Fusion Normal, calque en partie transparent | 1,44 ms | 0,35 ms | × 4,1 |
| Fusion Incrustation, calque en partie transparent, 80 % | 12,33 ms | 0,70 ms | × 18 |
| Fusion Couleur (mode non séparable) | 5,46 ms | 0,93 ms | × 5,9 |
| Teinte | 6,76 ms | 1,24 ms | × 5,4 |
| Matrice de couleur | 0,98 ms | 0,17 ms | × 5,7 |
| Masque d'altitude | 9,68 ms | 0,38 ms | × 26 |
| Douglas-Peucker, 20 000 points | 19,47 ms | 0,99 ms | × 20 |
| PNG d'une tuile de plan : `Bitmap.compress` (77 Ko) | 7,81 ms | 0,33 ms (83 Ko) | × 23 |
| PNG d'une tuile de photo : `Bitmap.compress` (139 Ko) | 18,94 ms | 0,39 ms (183 Ko) | × 48 |
| **Tuile fusionnée entière** (3 calques) | 44,6 ms | 10,6 ms | **× 4,2** |
| **Tuile découpée par une bande d'altitude** | 22,9 ms | 2,9 ms | **× 7,8** |

Ce que ces chiffres ajoutent à ceux de la machine de build : sur Android, le code Kotlin par pixel est 5 à 10 fois
plus lent que sur une JVM de poste, le code Rust garde la même vitesse ; les gains du calcul sont donc bien plus
grands qu'au § 4.1 (× 4 à × 89 au lieu de × 1 à × 11), et le Douglas-Peucker, presque sans gain sur le poste, gagne
× 20 ici. Dans une tuile fusionnée entière il reste surtout le décodage des trois images par Skia, déjà natif.

**Réserve** : cet APK était debuggable, et Android compile alors le Kotlin sans toutes ses optimisations. Depuis
P36.7 (ADR-120) l'APK distribué ne l'est plus : la colonne Kotlin baisse, les gains du moteur aussi. Chiffres de
l'APK non debuggable : § 4.3 bis.

### 4.3 bis Résultats sur l'émulateur, APK non debuggable

Run « CI » 42712917348 (2026-11-09, commit 550b3c3, **0.4.224, APK non debuggable**, P36.7), même émulateur
(`sdk_gphone64_x86_64`, Android 14, 2 cœurs), mesure demandée par `dumpsys`.

| Étape | Kotlin, APK debuggable (§ 4.3) | Kotlin, APK non debuggable | Rust | Gain du moteur (APK non debuggable) |
|---|---|---|---|---|
| Fusion Produit, pixels opaques | 6,16 ms | 1,59 ms | 0,12 ms | × 13 |
| Fusion Normal, pixels opaques, opacité 60 % | 5,39 ms | 1,01 ms | 0,12 ms | × 8,6 |
| Fusion Lumière tamisée, pixels opaques | 10,61 ms | 2,61 ms | 0,13 ms | × 20 |
| Fusion Normal, calque en partie transparent | 1,44 ms | 0,80 ms | 0,33 ms | × 2,4 |
| Fusion Incrustation, calque en partie transparent, 80 % | 12,33 ms | 1,33 ms | 0,44 ms | × 3,0 |
| Fusion Couleur (mode non séparable) | 5,46 ms | 1,86 ms | 1,06 ms | × 1,8 |
| Teinte | 6,76 ms | 1,15 ms | 0,77 ms | × 1,5 |
| Matrice de couleur | 0,98 ms | 0,70 ms | 0,18 ms | × 3,9 |
| Masque d'altitude | 9,68 ms | 0,87 ms | 0,42 ms | × 2,1 |
| Douglas-Peucker, 20 000 points | 19,47 ms | 2,06 ms | 0,94 ms | × 2,2 |
| PNG d'une tuile de plan : `Bitmap.compress` | 7,81 ms | 7,32 ms | 0,32 ms | × 23 |
| PNG d'une tuile de photo : `Bitmap.compress` | 18,94 ms | 16,14 ms | 0,30 ms | × 54 |
| **Tuile fusionnée entière** (3 calques) | 44,6 ms | 25,1 ms | 4,5 ms | **× 5,5** |
| **Tuile découpée par une bande d'altitude** | 22,9 ms | 9,3 ms | 2,0 ms | **× 4,6** |

Lecture :

- **Le passage en non debuggable (P36.7) rend à lui seul le code Kotlin 1,4 à 11 fois plus rapide** sur ces calculs
  (deuxième colonne contre la première). Ce gain vaut pour tout le code Kotlin de l'application, interface
  comprise ; le PNG d'Android, déjà natif, ne bouge pas.
- **Le moteur Rust garde son avantage sur l'APK non debuggable** : × 5,5 et × 4,6 sur une tuile entière, surtout par
  le PNG (× 23 à × 54) et la fusion de pixels opaques (× 9 à × 20). Là où le calcul reste en flottants pixel par
  pixel (calque en partie transparent, mode Couleur, teinte), il gagne × 1,5 à × 3 ; le Douglas-Peucker × 2,2.
- **Les deux ensemble**, par rapport à l'APK d'avant (debuggable, Kotlin seul) : une tuile fusionnée passe de
  44,6 ms à 4,5 ms, une tuile découpée de 22,9 ms à 2,0 ms, soit environ dix fois moins.
- Ces mesures viennent de deux runs de la CI, sur deux machines : à quelques pour cent près d'un run à l'autre.

### 4.3 ter Résultats sur l'émulateur du poste Windows (P38.5, 2026-11-10)

APK **0.4.226 de la CI** (non debuggable, moteur natif), émulateur du poste (`sdk_gphone64_x86_64`, Android 16,
6 cœurs, **rendu logiciel** `-gpu swiftshader_indirect`), `tools/emulator/smoke.sh` puis parcours à la main.
`engine native=1 state=active abi=1 arch=x86_64`, mesure terminée en 2,1 s.

| Étape | Kotlin | Rust | Gain |
|---|---|---|---|
| Fusion Produit, pixels opaques | 2,93 ms | 0,33 ms | × 9,0 |
| Fusion Normal, pixels opaques, opacité 60 % | 2,09 ms | 0,22 ms | × 9,7 |
| Fusion Lumière tamisée, pixels opaques | 3,82 ms | 0,21 ms | × 18 |
| Fusion Normal, calque en partie transparent | 1,17 ms | 0,55 ms | × 2,1 |
| Fusion Incrustation, calque en partie transparent, 80 % | 2,12 ms | 0,79 ms | × 2,7 |
| Fusion Couleur (mode non séparable) | 3,05 ms | 1,63 ms | × 1,9 |
| Teinte | 1,90 ms | 1,04 ms | × 1,8 |
| Matrice de couleur | 1,10 ms | 0,31 ms | × 3,6 |
| Masque d'altitude | 2,18 ms | 1,10 ms | × 2,0 |
| Douglas-Peucker, 20 000 points | 3,34 ms | 1,33 ms | × 2,5 |
| PNG d'une tuile de plan : `Bitmap.compress` | 9,37 ms | 0,57 ms | × 16 |
| PNG d'une tuile de photo : `Bitmap.compress` | 24,20 ms | 0,80 ms | × 30 |
| **Tuile fusionnée entière** (3 calques) | 41,2 ms | 11,0 ms | **× 3,7** |
| **Tuile découpée par une bande d'altitude** | 14,4 ms | 4,1 ms | **× 3,5** |

Application :

- **Démarrage à froid** (`am start -W`, 5 fois) : 1 809 ms la première fois après l'installation, puis 1 085, 1 124,
  1 053 et 1 216 ms ; carte dessinée 1,65 à 1,86 s après l'ouverture (`ToolMapPerf rendered trigger=open`, 37 tuiles
  du stockage), soit 2,1 à 3,0 s après la création du processus.
- **Fusion à l'écran** (favori « Rando été », IGN Pentes en « Produit » sur IGN SCAN 25, zoom 17 donc tuiles
  complétées) : 42 à 44 tuiles composées par vue, vue prête 1,3 à 1,6 s après le geste, réseau compris (21 à 34
  tuiles lues en ligne), aucun échec, aucun appel refusé par le moteur.
- **Bande d'altitude** (favori « Rando Hiver ») : tuiles découpées dessinées (`masked=10`), aucun plantage.
- **PDF avec un calque fusionné** (A4 paysage, 1:25 000, 300 dpi, 106 tuiles lues en ligne) : enregistré en moins de
  10 s, 2,4 Mo, aucune tuile manquante.
- **Images lentes** (`dumpsys gfxinfo`) : 216 images, médiane 34 ms, 90e centile 48 ms. Ce chiffre ne dit rien d'un
  téléphone : l'émulateur du poste dessine la carte sans carte graphique (le rendu matériel le fait planter). À
  juger sur le téléphone.
- Les calculs sont plus lents ici que sur l'émulateur de la CI (§ 4.3 bis) : autre machine, six cœurs partagés avec
  le rendu logiciel. Les gains du moteur restent du même ordre.
- Relevé en passant : ART refuse de compiler `MapScreenKt.MapScreen` (« Method exceeds compiler instruction limit:
  28496 ») ; cette fonction reste interprétée.

### 4.4 Mesurer soi-même

```bash
# Machine de build (indicatif)
./gradlew :data:testDebugUnitTest --tests '*TilePipelineBenchmark*' -Ptoolmap.engine.bench=true
# Émulateur, APK de la CI (ou benchmark) installé : lance l'application et lui demande la mesure
bash tools/perf/E-engine.sh
# La même demande à la main, application ouverte, puis lecture des lignes
adb shell dumpsys activity fr.toolmap/.MainActivity engine-bench
adb logcat -d -s ToolMapEngine
# Un scénario complet avec puis sans le moteur (APK benchmark)
bash tools/perf/B-blend.sh
ENGINE=kotlin bash tools/perf/B-blend.sh
```

## 5. Debug d'abord, release ensuite

Demandes de l'utilisateur du 2026-11-09 : une version debug à essayer sur l'émulateur **avant** la mise en release ;
puis : l'APK de debug non debuggable pour y mesurer les performances pendant le test QGIS, **toujours sans release**,
« la release complète quand le test de géoréférence sera fini ».

| Type de build | Bibliothèque dans l'APK | Moteur aux commandes (`BuildConfig.NATIVE_ENGINE`) | Debuggable |
|---|---|---|---|
| `debug` (APK de la CI, celui de l'essai) | oui | **oui** | **non** depuis P36.7 (ADR-120) ; `-Ptoolmap.debuggable=true` pour un build local avec débogueur et `run-as` |
| `benchmark` (mesures scriptées de `tools/perf`, R8) | oui | oui ; `perf.engine=kotlin` l'éteint pour un lancement | non |
| `release` | oui | **non**, jusqu'à la release complète | non |

La **release complète** (P38.6) se fait quand le test du PDF géoréférencé dans QGIS (P37.3) est fini et que
l'utilisateur le dit : `NATIVE_ENGINE` à `true` dans le type `release` de `app/build.gradle.kts`, puis le workflow
« Release ». D'ici là, rien de ce travail n'atteint `main` (la branche dont les téléphones tirent leurs mises à jour)
sans une fusion demandée par l'utilisateur ; la CI continue de livrer l'APK de debug de la branche, après son
contrôle sur émulateur.

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

À faire dans la session locale du test QGIS (P37.3), avec **l'APK de la CI** : c'est lui qui contient le moteur (un
build du poste sans Rust ne l'a pas) et il n'est plus debuggable (P36.7), donc ses performances sont celles d'un
téléphone.

1. Installer l'APK de la CI : `adb install -r -g ToolMap-debug-<version>.apk` (signé par la clé de publication : une
   installation signée par la clé de debug du poste se désinstalle d'abord).
2. Vérifier que le moteur est aux commandes : `adb logcat -d -s ToolMapEngine` doit montrer
   `engine native=1 state=active`.
3. Parcourir ce que le moteur dessine : Cartes et calques ▸ un calque ▸ Couleurs ▸ **Fusion** (Produit, Lumière
   tamisée, Couleur) et **Teinter** ; ⋮ ▸ **Conditions d'affichage** avec une bande d'altitude (favori « Sortie
   Ski ») ; zoomer au-delà du dernier zoom d'une carte (tuiles complétées) ; **Imprimer** un PDF avec un calque
   fusionné ; ouvrir un long tracé.
4. Mesurer le moteur : `bash tools/perf/E-engine.sh`. Mesurer l'application : `adb shell am start -W -n
   fr.toolmap/.MainActivity` (démarrage), `adb shell dumpsys gfxinfo fr.toolmap` (images lentes), lignes `ToolMapPerf`
   de logcat (temps jusqu'à la carte dessinée). Les scénarios scriptés de `tools/perf` (vue fixe, `ENGINE=kotlin`)
   demandent l'APK benchmark, à compiler sur un poste où Rust et le NDK sont installés (§ 6).
5. Dire le résultat : la release complète (§ 5) attend ce retour et la fin du test QGIS.
