# Couleurs et modes de fusion des calques (P26)

> Demande et décisions de l'utilisateur : `docs/plan-fonctions-2026-10.md` § P26. Décision d'architecture : ADR-111.
> Le curseur « Teinte » (rotation des couleurs par MapLibre, en direct) est P26.6, ADR-095.

## 1. Ce que fait un mode

Un calque en mode autre que Normal se mélange avec **tout ce qui est dessous** (les calques visibles depuis le bas de la pile). Les formules sont celles de W3C Compositing and Blending Level 1, § 10 (`:core`, `Blend`) :

| Mode (`BlendMode.key`) | Nom affiché | Formule `B(cb, cs)` (fond `cb`, calque `cs`, 0..1) |
|---|---|---|
| `normal` | Normal | `cs` |
| `multiply` | Produit (Multiply) | `cb · cs` |
| `screen` | Superposition (Screen) | `cb + cs − cb · cs` |
| `overlay` | Incrustation (Overlay) | Lumière crue avec fond et calque échangés |
| `darken` / `lighten` | Obscurcir / Éclaircir | `min` / `max` |
| `color-dodge` | Densité couleur − (Color dodge) | `min(1, cb / (1 − cs))` |
| `color-burn` | Densité couleur + (Color burn) | `1 − min(1, (1 − cb) / cs)` |
| `hard-light` | Lumière crue (Hard light) | Produit de `2 cs` sous 0,5, Superposition au-dessus |
| `soft-light` | Lumière tamisée (Soft light) | formule W3C (racine carrée au-dessus de 0,25) |
| `difference` / `exclusion` | Différence / Exclusion | `|cb − cs|` / `cb + cs − 2 cb cs` |
| `hue` / `saturation` / `color` / `luminosity` | Teinte / Saturation / Couleur / Luminosité | `SetLum`, `SetSat` (W3C § 10.2), luminosité `0,3 r + 0,59 g + 0,11 b` |

Composition « source over » : là où le fond est en partie transparent, le calque garde en partie sa propre couleur ; l'opacité du calque multiplie son alpha.

**Teinte d'une couleur** (`LayerConfig.tint`, `tintStrength`) : chaque pixel prend la teinte et la saturation de la couleur choisie et garde sa luminosité (mode Couleur), mélangé avec sa couleur d'origine selon l'intensité (0 à 1, 0,5 par défaut).

## 2. Où se fait le calcul

- `StackToStyle` : un calque fusionné (`LayerConfig.isBlended` : mode autre que Normal, ou teinte d'intensité non nulle) a sa source MapLibre `<carte>?blend=…` (`StyleSources.id(carte, recette)`). MapLibre ne garde pour lui que la visibilité, la plage de zoom et l'assombrissement de nuit.
- `TileInterceptor.blended` lit la recette (`BlendRecipe.parse`, requête encodée telle quelle), puis la tuile de chaque partie dessinée à ce zoom (`BlendRecipe.partsAt` : plage de zoom tenue, ou bande jointe par OU), comme son calque la montre : stockage, hors ligne, serveur, bande d'altitude (`masked`).
- `BitmapTileBlender` : à la taille de la tuile du calque (les autres sont mises à l'échelle), de bas en haut : matrice de couleur de la partie (`RasterAdjust.colorMatrix`), teinte, puis `Blend.composite` avec son mode et son opacité ; PNG, jamais stocké.
- Sans la tuile du calque lui-même : 404 (les calques du dessous restent visibles). Une panne du serveur d'une partie est rendue telle quelle si rien n'a pu être dessiné (MapLibre réessaie).

## 3. La recette dans l'URL

`blend=<partie>;<partie>…`, du bas de la pile vers le calque (le dernier). Une partie : 15 champs séparés par `,`, vides pour la valeur par défaut, virgules de fin retirées :

`carte, mode, opacité, contraste, luminosité, saturation, rotation, inversé (1), zoom min, zoom max, altitude min, altitude max, or, teinte (rrggbb), intensité`

La carte est écrite avec `%XX` pour tout sauf lettres ASCII, chiffres, `-`, `_` et `.`. Exemple : `blend=osm;ign-maps,soft-light,0.35,-0.5,0.1,-1,45,1,8,16,-20,1500,or,3366cc,0.8`. Une requête mal formée est ignorée (tuile ordinaire).

## 4. Sauvegarde et favoris

`LayerConfig` (état de la carte, sauvegarde P9.6) et `WorkspaceLayer` (favoris, fichiers `.toolmap`) gardent mode, teinte et intensité ; un ancien fichier se lit en Normal sans teinte. Mode et teinte comptent dans les « couleurs » d'un favori (`Workspace.changedFields`).

## 5. Coût mesuré (émulateur « Medium_Phone », APK benchmark 0.4.169, 2026-11-03)

Script `tools/perf/B-blend.sh` (extras `perf.overlay` à plusieurs cartes et `perf.blend` de `PerfIntent`). Trois calques : OpenTopoMap, IGN Pentes, IGN Plan en Produit ; vue 45,43° N 6,45° E, zoom 13 ; ouvertures à froid, sans `pm clear`.

| Mesure | Normal | Produit |
|---|---|---|
| Écran entier depuis le stockage (médiane de 3) | 802 ms | 1 316 ms |
| Même chose en mode avion | 808 ms | 1 379 ms |
| Écran entier en ligne, premier passage (points voisins) | 4 776 et 6 031 ms | 6 167 ms (13 098 ms une fois, réseau lent) |
| Tuile composée depuis le stockage, médiane (90e centile), 33 tuiles en parallèle | — | 402 ms (624) : lecture 92, décodage 59, fusion 106, PNG 77 |
| Tuile composée en ligne (calcul seul, processeur moins chargé) | — | décodage 5, fusion 14, PNG 21 ms |

Conclusion : le calcul ajoute environ 0,5 s à un écran entier depuis le stockage, l'ordre de grandeur du redessin d'une bande d'altitude (P20 : 0,31 à 0,45 s) ; en ligne, il disparaît derrière le réseau. Retenu : fusion et teinte appliquées au relâcher, comme P20. Pistes si un appareil plus lent le demandait : calcul en entiers pour les modes séparables, PNG moins compressé ou WebP sans perte, demandes réseau partagées entre la tuile composée et celle de MapLibre (quelques tuiles téléchargées deux fois en ligne : 115 requêtes au lieu de 98 à 108).

Rendu vérifié : IGN Plan en Produit sur SCAN 25, relief et courbes du SCAN visibles sous le plan (`docs/previews/p26/emu-produit-plan-scan25.jpg`, Normal à gauche).

## 6. Reste à faire

Panneau « Couleurs et fusion » (P26.1 aperçu, P26.4), impression (P26.5) : le PDF ne montre pas encore les modes.
