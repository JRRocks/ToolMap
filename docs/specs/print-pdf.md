# Spécification — Export PDF à l'échelle (N4)

> Tâches P7.x de `docs/PLAN.md`. Inspiration : mise en page d'impression (Print Layout) et Atlas de QGIS.
> **Mise à jour 2026-10-02 (ADR-020)** : le compositeur n'utilise pas `android.graphics.pdf.PdfDocument` mais un écrivain PDF maison en JPEG (`:core`), et la grille n'utilise pas proj4j mais des projections maison ; voir §7 pour ce qui est réalisé et ce qui reste à vérifier sur téléphone.
> Objectif : imprimer une zone comme une vraie carte papier, à une échelle exacte (1:25 000, 1:50 000…).
> **Mise à jour 2026-10-17 (P17, ADR-061)** : nouveau parcours en trois étapes (deux questions, cadre posé sur la carte, confirmation), une page par impression, PDF seulement, feuille aux conventions des cartes papier en deux styles (IGN TOP 25, OTAN). La §6 est remplacée ; voir aussi §8.

## 1. Paramètres utilisateur
| Paramètre | Valeurs |
|---|---|
| Format | A5, A4, A3, Letter, Autre… (largeur × hauteur en mm, 50 mm au moins, **sans maximum** depuis le 2026-10-29, ADR-096) ; A2 et Legal se font par « Autre… ». Plus aucune page n'est refusée pour sa taille (ADR-097) : au-delà de 150 Mpx, alerte « PDF très lourd » (P17.9, ADR-098). |
| Orientation | portrait, paysage ; bouton ⟲ pour tourner la feuille |
| Marges | mm (défaut 8), dans « Qualité et marges » |
| Échelle | 1:10 000, 1:20 000, 1:25 000 (défaut), 1:50 000, 1:100 000, 1:250 000, autre (1:500 à 1:10 000 000) |
| Style | IGN TOP 25 seul (style OTAN retiré le 2026-10-28, ADR-088) |
| Résolution | 150, 200, 300 dpi ou « Autre… » : 72 dpi au moins, **sans maximum** (P17.9, 2026-10-29) ; au-delà de 300 dpi ou de 150 Mpx par page, alerte « PDF très lourd » / « Résolution élevée » (poids estimé, création longue, échec possible ; ADR-098) |
| Calques | ceux de la pile courante, avec leurs plages de zoom (N3) |
| Habillage | titre (prérempli « carte · date »), sous-titre, échelle chiffrée, barre d'échelle, nords et déclinaison, légende, date et source, coordonnées des coins, traces, waypoints et leurs noms (un seul interrupteur depuis P17.8) |
| Grille | Aucune, UTM (chaque zone de son côté de la limite, P24.4), MGRS, Lambert 93, WGS84 DMS, WGS84 décimal ; la première fois celle du format de coordonnées des réglages ; pas automatique selon l'échelle et le style, ou manuel |
| Atlas | ~~dans l'écran d'impression~~ : une page par impression (ADR-061) ; l'atlas revient **le long d'une trace**, depuis le détail de la trace (P14.6 `[F9]`, §5, ADR-067) |

## 2. Mathématiques (implémentées dans `:core`, testées en JVM)

Notations : `S` = dénominateur d'échelle, `d` = dpi, `(fw, fh)` = taille du cadre carte en mm, `φ0` = latitude du centre.

- **Emprise au sol** du cadre : `Gw = fw · S / 1000` m, `Gh = fh · S / 1000` m.
- **Emprise en Web Mercator** : facteur d'échelle de Mercator `k0 = 1 / cos φ0`, d'où l'emprise projetée `(Gw · k0) × (Gh · k0)` centrée sur le centre projeté.
- **Pixels de sortie** : `Pw = fw / 25,4 · d`, `Ph = fh / 25,4 · d`.
- **Résolution au sol cible** : `r = S · 0,0254 / d` (m/pixel).
- **Résolution d'une tuile** (256 px) au zoom z : `R(z) = 156 543,03392 · cos φ0 / 2^z` (diviser par 2 pour des tuiles de 512 px).
- **Zoom de tuiles choisi** : `z* = ceil(log2(156 543,03392 · cos φ0 / r))`, de sorte que `R(z*) ≤ r` (léger sous-échantillonnage, image nette). On le borne à la plage de zoom du calque et au zoom natif maximal de la source. Si on est bridé par le zoom natif, afficher un avertissement « suréchantillonnage ×(R/r) ». Option « fichier léger » : z* − 1.
- **Erreur d'échelle** : exacte au centre ; aux bords nord et sud, l'erreur relative vaut ≈ `tan φ0 · Δφ` (Δφ = demi-hauteur au sol / 6 371 km, en radians).

### Valeurs de référence (tests unitaires), φ0 = 45° N, tuiles de 256 px
| Échelle | dpi | r (m/px) | z* | R(z*) (m/px) |
|---|---|---|---|---|
| 1:25 000 | 300 | 2,117 | 16 | 1,689 |
| 1:25 000 | 150 | 4,233 | 15 | 3,378 |
| 1:50 000 | 300 | 4,233 | 15 | 3,378 |
| 1:100 000 | 300 | 8,467 | 14 | 6,756 |

- A4 paysage, marges 10 mm, cadre 277 × 190 mm : à 1:50 000 l'emprise est de **13,85 × 9,5 km** ; à 1:25 000, de 6,925 × 4,75 km.
- Erreur au bord, A4 paysage à 1:50 000, 45° N : Δφ = 4,75 / 6 371 = 7,46·10⁻⁴ rad, soit ≈ **0,075 %**.
- Nombre de tuiles, A4 à 1:50 000, z15, 45° N : une tuile couvre 3,378 × 256 ≈ 865 m, soit ≈ 17 × 12 = **~200 tuiles par calque**.
- Exemple de la fixture : IGN SCAN 25 Touristique est limité à z14 dans `IGN_Topo_zoom_test.aqx`. Une impression à 1:25 000 / 300 dpi (z16 requis) doit donc déclencher l'avertissement de suréchantillonnage : R(14) / r = 6,756 / 2,117 ≈ ×3,2.

**Critère global** : un segment de 10 km mesure 200 mm ± 0,5 % sur la page imprimée à 1:50 000 (« taille réelle », sans ajustement à la page).

## 3. Composition
- ~~`android.graphics.pdf.PdfDocument`~~ → **`PdfWriter` maison** (`:core`, ADR-020) : PDF 1.4, `MediaBox` réelle (A4 = 841,89 × 595,276 pt, pas 595 × 842), polices de base Helvetica, images JPEG. Unités de page en points PostScript (1 pt = 1/72 po).
- **Cadre carte** : rendu **par bandes** de 512 px de haut, coupées en **carreaux** de 8 192 px de large au plus (16 Mo de bitmap au plus, aucune image plus large que le JPEG ne le permet ; `PrintRaster.pieces`, ADR-097) ; une page de 8 192 px de large au plus a un carreau par bande. Pour chaque bande : bitmap de `Pw × hauteur`, composition des calques dans l'ordre (opacité, réglages couleur dont la teinte (même matrice que le shader de la carte, ADR-095), plages de zoom au zoom z*), tuiles lues dans `MbTilesStore`, puis `drawBitmap` dans le rectangle de la bande en points.
- **Tuiles manquantes** : avant l'export, calcul de la liste des tuiles nécessaires puis proposition de les télécharger (réutilise P2.4). Sinon, les zones manquantes sont hachurées et signalées.
- **Vectoriel** (net à toute résolution) : traces, waypoints, grille, textes et habillage sont dessinés directement sur le `Canvas` du PDF, jamais rastérisés.
- **Taille de fichier** : `PdfDocument` encodait sans perte (Flate) ; une A3 à 300 dpi fait ≈ 17 Mpx, donc un fichier de plusieurs dizaines de Mo. Réglé par ADR-020 : bandes **JPEG** (qualité ≈ 88). Poids réel d'une A3 / 300 dpi avec de vraies tuiles : à mesurer sur téléphone.

## 4. Éléments d'habillage (façon QGIS)
| Élément | Détail |
|---|---|
| Titre / sous-titre | libres |
| Échelle numérique | « 1:50 000 » |
| Barre d'échelle | segments alternés noirs et blancs (ex. 0–500 m–1–2 km), longueur calculée en mm papier |
| Flèche du nord | nord géographique ; déclinaison magnétique du jour via `android.hardware.GeomagneticField` (« Déclinaison 2026 : 2°15′ E ») ; convergence de la grille si grille UTM ou Lambert |
| Grille | UTM (1 km à 1:25 000, 1 ou 2 km à 1:50 000), Lambert 93, graticule WGS84 en DMS, MGRS. Lignes calculées par nos projections (`Utm`, `Lambert93`, `Mgrs`) et densifiées (elles peuvent être légèrement courbes en Mercator) ; étiquettes en bordure du cadre façon IGN |
| Légende | traces et waypoints visibles (couleur, nom), calques utilisés |
| Cartouche | date d'impression, système de coordonnées, **attribution © de chaque source (obligatoire)**, mention « ToolMap » |
| ~~Mini-carte~~ | retirée avec l'atlas (ADR-061) |
| Coordonnées des coins | option |

## 5. Atlas
- **Grille de pages** : la zone sélectionnée est découpée en cadres de la taille de page, avec recouvrement r % ; pages numérotées par ligne et colonne.
- **Le long d'une trace** : algorithme glouton. On place une page centrée sur le premier point, puis on avance le long de la trace jusqu'à sortir de la zone utile (cadre moins le recouvrement), et on place la page suivante. L'orientation par page (portrait ou paysage) est fixe en v1, automatique plus tard.
- **Page d'assemblage** : vue d'ensemble avec le contour numéroté de chaque page (grille de pages seulement).
- **Depuis le détail d'une trace** (P14.6 `[F9]`, ADR-067) : « Imprimer le long de cette trace… » pose les deux questions de §6 (taille, échelle), puis montre les pages enchaînées le long de la trace, numérotées dans l'ordre de la marche, 10 % de recouvrement, nord en haut, orientation commune. Les cadres ne se déplacent pas (ils découlent de la trace) ; la carte garde ses gestes et cadre l'ensemble des pages (`PrintViewport.fit`). Bandeau `Format ▾`, `Orientation ▾`, `Échelle ▾` et « N pages le long de la trace » ; Suivant ouvre la même confirmation que §6.3, puis un PDF d'une page par cadre, **sans page d'assemblage**. Sur une boucle, les pages se recouvrent beaucoup (l'algorithme suit la trace dans l'ordre).

## 6. Interface (P17, ADR-061 ; remplace le panneau unique de P7)
1. **La feuille et l'échelle** (P17.8, variante E « Épreuve », validée le 2026-10-29, ADR-098) : « Imprimer » ouvre un panneau en bas de la carte, le cadre dessiné au-dessus (non déplaçable à cette étape, recadré à chaque changement) : feuilles A5, A4, A3, Letter dessinées à la même échelle d'après la vraie mise en page (`SheetThumbnail`, `SheetLayout`, sans tuiles) avec leurs millimètres, « Autre » qui ouvre Largeur / Hauteur dans le panneau (50 mm au moins, pas de maximum) ; orientation en bouton segmenté ; échelles sur une ligne qui défile, « Autre… » ; « Sur le terrain 6,6 × 3,7 km » ; ligne orange d'un PDF lourd. Les réglages de la dernière impression sont présélectionnés. Annuler / « Choisir la zone ».
2. **Cadre posé sur le sol**, comme « Sélectionner et enregistrer une zone » : centré sur la carte au départ, carte remise au nord, zoom réglé pour que le cadre occupe environ 70 % de la place libre. Un doigt dans le cadre le déplace ; deux doigts zooment la carte ; hors du cadre, la carte garde ses gestes. Pas de poignées. Bandeau du haut : puces `Format ▾`, `Orientation ▾`, `Échelle ▾`, bouton ⟲, taille au sol et avertissements ; un changement garde le centre et règle le zoom à nouveau. En bas : Annuler / Suivant.
3. **Confirmation** (P17.8, variante A « Gabarit », validée le 2026-10-29, ADR-098 ; la carte recadre le cadre dans la place libre au-dessus, hors des cases d'information, de la barre et des boutons de zoom, et le recadre quand le panneau change de hauteur ; « Retour à la zone » le remet au centre à 70 %) : encadré d'état en tête (« 1 page A4 paysage · 1:25 000 », taille au sol, dpi, poids estimé, problèmes en rouge, cartes agrandies en orange, tuiles à télécharger avec « Télécharger » jusqu'à 1 500, hachures au-delà) ; encadré orange « PDF très lourd » (forme Q2) qui mène à « Qualité et marges » ; « Sur la carte » : Grille (menu), Waypoints et leurs noms, Traces ; « Feuille » : Titre, puis deux lignes résumées qui ouvrent leur page (← retour, ✕ fermer, mêmes boutons au pied) : **Éléments de la feuille** (bandeau du bas : échelle chiffrée, barre d'échelle, nords et déclinaison, légende, date et source ; cadre : coordonnées des coins ; sous-titre) et **Qualité et marges** (résolution 150 · 200 · 300 · Autre…, encadré « Résolution élevée », fichier plus léger, calques hors de leur plage de zoom, marge, pas de la grille en menu). Boutons « Retour à la zone » / « Créer le PDF ». Retour arrière : d'une page des réglages à la page principale, de la confirmation au cadre, puis fermeture.
4. **Export** : enregistrement par SAF (`ACTION_CREATE_DOCUMENT`), puis « PDF enregistré · 1 page · 2,4 Mo », rappel d'imprimer en **« taille réelle / 100 % »**, Ouvrir et Partager. Pas d'aperçu interne, pas d'impression directe.
5. Seuls les réglages de la dernière impression sont mémorisés (`PrintTemplateStore.last`, `last.json`) ; les modèles enregistrés sont retirés.

## 7. Réalisation (état après P7)
| Sujet | Réalisé | Où |
|---|---|---|
| Maths | zoom de tuiles `z* = ceil(zoom équivalent)` (léger : `z* − 1`), résolution, emprise, avertissement de sur-échantillonnage, erreur d'échelle aux bords | `PrintMath`, `FrameProjection`, `PrintLayers` |
| Plages de zoom des calques | comparées au zoom équivalent **fractionnaire** (pas à `z*`) ; un calque hors plage n'est pas imprimé et le résumé le dit | `PrintLayers` |
| Bandes d'altitude (P20, 2026-10-29) | le calque est découpé à sa bande comme à l'écran, tuiles d'altitude comprises dans les tuiles requises ; « OU » : entier dans sa plage de zoom, réduit à sa bande ailleurs (`specs/elevation-maps.md` §4.1) | `PrintLayers` (`PrintBand`), `PrintPlan.requiredTiles`, `AndroidBandRenderer` |
| Bandes | 512 lignes, 2 lignes de recouvrement, tuiles posées en pixels entiers | `PrintRaster`, `PrintExporter` |
| PDF | `PdfWriter` (Helvetica WinAnsi, JPEG `DCTDecode`, `MediaBox` réelle) | `fr.toolmap.core.pdf` |
| Habillage | titre / sous-titre, échelle numérique, barre graphique, nord + déclinaison (`GeomagneticField`) + convergence, grille (UTM, Lambert 93, WGS84, MGRS) étiquetée, traces, waypoints, légende, cartouche avec attributions, coordonnées des coins, mini-carte | `PageComposer`, `GridPlanner`, `ScaleBarPlan` |
| Atlas | grille de pages avec recouvrement, ou le long d'une trace ; page d'assemblage numérotée | `Atlas` |
| Tuiles | stockage local d'abord ; réseau seulement pour les sources sans politique restrictive ; hachures + compte pour les tuiles indisponibles ; téléchargement des manquantes (≤ 1 500) | `HttpPrintTileFetcher`, `ZoneDownloader.missing/runTiles`, `AndroidBandRenderer` |
| Interface | ~~panneau unique, aperçu `PdfRenderer`, modèles~~ → parcours P17 (§6) : `PrintSetupDialog`, `PrintFrameOverlay` + `PrintFrameBar` + `PrintFrameActions`, `PrintConfirmSheet`, `PrintOutcomeDialog` ; étapes `PrintStage` dans `PrintViewModel` | `fr.toolmap.print` (`:app`) |
| Réglages mémorisés | `PrintTemplateStore.last` / `remember` (`last.json`, écriture atomique) ; ~~`templates.json`~~ | `:data` |

**Écarts à la spec d'origine** : l'orientation est fixe par document (pas automatique par page d'atlas) ; l'impression est toujours **nord en haut** ; les pages d'atlas n'affichent pas de renvoi de numéro de page sur les bords ; l'échelle de la page d'assemblage n'est pas arrondie.

**Vérifié en JVM** (voir journal) : géométrie du cadre contre un calcul Mercator indépendant (≤ 0,09 mm), placement des bandes (bord à bord, sans trou), `MediaBox`, présence du titre, de l'échelle et du © dans le flux, PDF relus avec poppler (1 page A4 ; atlas de 13 pages), composition réelle `Bitmap` / `Canvas` / JPEG avec hachures. **Non vérifié** : tout ce qui touche à l'écran (cadre sur la vraie caméra, gestes), à Android (SAF, `PdfRenderer`, partage) et à la durée / mémoire d'une A3 à 300 dpi sur téléphone ; mesure à la règle d'un segment de 10 km imprimé (critère global).

## 8. Feuille aux conventions des cartes papier (P17, ADR-061)

Une page par impression. De l'extérieur vers la carte :

| Élément | Détail | Où |
|---|---|---|
| Marge | 8 mm par défaut, blanche (zone non imprimable des imprimantes) | `PageSetup.marginMm` |
| Bande d’étiquettes | 7 mm sur les quatre côtés, **dans la grille choisie seule** (ADR-087) : coordonnées de la grille à **chiffres principaux** (gros chiffres = kilomètres, ex. ⁷**28**⁰⁰⁰ m E en UTM et Lambert 93 ; en MGRS, les deux chiffres du kilomètre dans le carré de 100 km seuls, **28**) ; coordonnées complètes aux quatre coins pour une grille en degrés ou sans grille seulement (DMS, ou degrés décimaux pour « WGS84 décimal ») ; feuille à cheval (P24.4) : chiffres de chaque zone de son côté, et ceux de la zone voisine prolongée en bleu italique dans une rangée extérieure (pratique OTAN), qui peut prendre la marge blanche jusqu’à 4 mm du bord du papier en haut et sur les côtés | `PageSetup.LABEL_STRIP_MM`, `PageComposer` |
| Cadre gradué | double cadre noir de 1,6 mm, graduations alternées noir / blanc **au pas de la grille choisie** (ADR-087, remplace « l'autre système ») ; aucune sans grille | `PageSetup.GRADUATION_MM`, `PageComposer` |
| Carte | nord en haut, quadrillage dessiné en vectoriel par-dessus la carte. **Feuille à cheval sur deux zones UTM / MGRS (P24.4, aperçu `docs/previews/p24-mgrs.html` §4 validé le 2026-10-26)** : chaque zone trace sa grille de son côté, coupée à la limite (fini la zone du centre prolongée et sa note) ; limite des zones (méridien) ou des bandes (parallèle) en trait sombre de 0,6 mm, avec « 6° E » en haut ou « 48° N » à gauche ; en MGRS, carrés nommés avec leur zone (« 31T GL », « 32T KR ») de part et d'autre de la limite, lettres justes des deux côtés (zone du lieu, exceptions comprises) | `GridPlanner`, `GridZones.pieces` |
| Bandeau du bas | cases de 22 mm de haut : titre et légende (cartes, traces, waypoints) ; **ÉCHELLE** (chiffrée, barre dont l'unité suit le dernier nombre, « 1 cm sur la carte = … », écart aux bords) ; **NORDS** (schéma hors échelle NG / NM / NQ ; une ligne par angle, nom à gauche et valeur à droite : Déclinaison, Convergence, **Angle quadrillage–magnétique** toujours écrit ; l'année et la variation annuelle, en gris sous la déclinaison, cèdent la place les premières) ; **QUADRILLAGE** (système, pas, gros chiffres ; à cheval : « MGRS 31T et 32T · WGS84 — carreaux de 1 km », « Limite des zones 31 / 32 : méridien 6° E (trait épais) », « Carrés de 100 km : 31T GL (ouest) · 32T KR (est) », « En bleu dans la marge : quadrillage de la zone voisine », en 4,9 pt) ; une ou deux rangées selon la largeur ; ligne des © en bas | `SheetLayout`, `PageComposer` |
| Waypoints | disque de 4 mm à la couleur de l'icône avec son glyphe blanc (image avec masque alpha, écrite une fois par document), nom noir détouré de blanc (masquable) | `PrintWaypoint.iconId`, `PdfImage.rgba` |
| Traces | 0,6 mm environ, à leur couleur | `PageComposer` |

**Style** : celui des cartes IGN TOP 25 seul (quadrillage bleu, angles des nords en degrés). Le style OTAN (quadrillage noir, angles aussi en millièmes, encadré « Donner une référence (100 m) », carreaux de 1 km au 1:100 000) a été retiré le 2026-10-28 à la demande de l'utilisateur, qui n'y voyait pas de différence utile (ADR-088) ; les lettres du carré de 100 km restent dans la case QUADRILLAGE d'une feuille MGRS.

**Pas automatique** (`GridSpacing`, modifiable dans « Plus d'options ») : carreaux de 2 à 5 cm sur le papier.

| Échelle | UTM / MGRS / Lambert | WGS84 DMS | WGS84 décimal |
|---|---|---|---|
| 1:10 000 | 500 m | 15″ | 0,005° |
| 1:20 000 – 1:25 000 | 1 km | 30″ | 0,01° |
| 1:50 000 | 1 km | 1′ | 0,02° |
| 1:100 000 | 2 km | 2′ | 0,05° |
| 1:250 000 | 10 km | 5′ | 0,1° |

**Place de la carte** : une A4 paysage (marge 8 mm) montre 263,8 × 148,2 mm de carte, soit 6,6 × 3,7 km au 1:25 000 et 13,2 × 7,4 km au 1:50 000. La déclinaison est celle du jour (`GeomagneticField`) ; sa variation annuelle est la différence avec la valeur dans un an.

**Vérifié** : tests JVM de `:core` (mise en page, pas de grille, graticule décimal, chiffres principaux, trois angles des nords écrits ; encadré OTAN jusqu'au 2026-10-28), feuilles d'essai relues à l'écran, deux PDF A4 de contrôle faits sur l'émulateur (OTAN / MGRS / OpenTopoMap 1:50 000, TOP 25 / UTM / IGN SCAN 25 Touristique 1:25 000), relus et **validés par l'utilisateur** (2026-10-17). **Reste** : la mesure à la règle sur une feuille imprimée à 100 %.
