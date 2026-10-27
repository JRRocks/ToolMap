# Cartes selon l'altitude (P20)

> Demande de l'utilisateur (2026-10-23) : « un système de changement de carte selon la courbe de niveau. Exemple de 0 m à 1500 m tu affiches OpenTopoMap, mais de 1500 m à l'infini tu affiches OpenSnowMap. Teste la faisabilité et le coût en performance. Mets en place la sauvegarde des tuiles pour le hors ligne. Si le coût en calcul est léger, permets la modification de la hauteur de la courbe de niveau en direct (même hors ligne). » Puis : « les options de la courbe de niveau vont au même endroit que l'affichage selon le zoom ; les deux options fusionnent en condition ET / OU ».
>
> Aperçu validé le **2026-10-23** : variante A (panneau en bas de la carte, réglage appliqué au relâcher, ET / OU), ligne du calque et ligne « Altitudes » du téléchargement : `docs/previews/p20-elevation.html`. Décision : ADR-068.

## 1. Principe

Chaque calque de la pile peut avoir une **bande d'altitude** (`LayerConfig.displayMinElevation` / `displayMaxElevation`, en mètres, bornes comprises, `null` = sans limite) en plus de sa plage de zoom. Hors de la bande, ses pixels sont transparents : la carte du dessous se voit. L'exemple de l'utilisateur s'écrit avec deux calques :

| Calque (bas → haut) | Plage de zoom | Bande d'altitude |
|---|---|---|
| OpenTopoMap | — | — |
| OpenSnowMap | — | au-dessus de 1500 m |

Aucune carte nouvelle n'est fabriquée : c'est la pile ordinaire, avec un masque par calque. Une bande ne vise donc pas que deux cartes : trois calques (fond, carte de moyenne montagne 1000-2000 m, carte de haute montagne au-dessus de 2000 m) marchent de même.

### 1.1 Zoom ET / OU altitude

`LayerConfig.rangeLogic` dit comment la plage de zoom et la bande se combinent quand les deux sont posées :

| Condition | Dessin du calque |
|---|---|
| zoom seul | entier dans sa plage de zoom, rien ailleurs (comportement d'avant P20) |
| altitude seule | sa bande, à tous les zooms (ET et OU donnent le même dessin) |
| zoom **ET** altitude | sa bande, seulement aux zooms de la plage |
| zoom **OU** altitude | entier aux zooms de la plage ; aux autres zooms, sa bande |

ET est le choix par défaut. Avec OU, la plage de zoom ne cache plus le calque (`LayerElevation.limitsZoom` = faux : pas de `minzoom` / `maxzoom` MapLibre) ; ce sont les tuiles qui sont entières ou masquées selon leur zoom (`ElevationMask.whole`). La limite se fait donc au **zoom de tuile**, pas au quart de zoom de la caméra comme une plage de zoom seule.

## 2. Masquage des tuiles

Le masque se calcule dans l'intercepteur de tuiles (`TileInterceptor.masked`), pas dans MapLibre (qui n'a pas d'expression raster pour cela) :

1. **La bande voyage dans l'URL** des tuiles du calque : la source du style s'appelle `opensnowmap?alt=1500,` (`StyleSources.id`), ses tuiles `https://tiles.toolmap.local/opensnowmap/12/2130/1462?alt=1500,`. Avec OU : `?alt=1500,&whole=8,16`. Une nouvelle bande est une nouvelle source : MapLibre redemande ses tuiles, sans vider son cache des autres.
2. **Altitudes** : tuile Terrarium (`terrarium`, carte d'altitudes fournie) au zoom `min(z, 13)` (`DemWindow`, `DemWindow.MAX_DEM_ZOOM`), lue comme une tuile ordinaire (stockage, mode hors ligne, serveur). Au-delà du zoom 13, la tuile d'altitudes est découpée en 2^(z−13) × 2^(z−13) cases. Les tuiles d'altitudes décodées restent en mémoire (`BitmapElevationMasker`, 64 tuiles, ≈ 256 Ko chacune) : un nouveau seuil ne relit que les tuiles de la carte.
3. **Tri rapide** : altitudes mini et maxi sous la tuile (un pixel d'altitudes au-delà des bords, `MaskRaster.range`) :
   - hors de la bande → **pas de tuile** (404, rien à lire ni à dessiner) ;
   - toute dans la bande → la tuile ordinaire, **sans recodage** ;
   - à cheval → la tuile est découpée.
4. **Découpe** (`MaskRaster`) : altitude de chaque pixel interpolée entre les quatre centres de pixels d'altitudes voisins ; opacité 255 dans la bande, 0 dehors, et un pixel de fondu le long de la courbe (la pente locale dit combien de mètres couvre un pixel), pour une limite sans escaliers. L'opacité propre de la tuile est multipliée par celle du masque (écriture directe du canal alpha, `getPixels` / `setPixels` ; un `PorterDuff.DST_IN` n'avait aucun effet sur une tuile JPEG). Le résultat est un PNG, **jamais stocké** : seules les tuiles d'origine le sont.
5. **Sans altitudes** (hors ligne et pas de tuile d'altitudes stockée) : le calque n'affiche rien à cet endroit (404 « no elevation here ») ; la carte du dessous reste visible.

### 2.1 Limites connues

- La limite est juste au plus à **un demi-pixel d'altitudes** près (≈ 7 m au sol au zoom 13 à 45° N) ; les données elles-mêmes (SRTM / EU-DEM) sont à ≈ 30 m.
- Au bord d'une tuile d'altitudes, ses pixels de bord sont répétés (pas de lecture des tuiles voisines) : décalage d'au plus un demi-pixel d'altitudes le long des coutures.
- OU change de dessin au zoom de tuile entier (§1.1).

## 3. Panneau « Conditions d'affichage »

Menu ⋮ d'un calque de Cartes affichées ▸ **Conditions d'affichage** (remplace « Plage de zoom »). Panneau posé en bas de la carte, non modal (`MapsPage.CONDITIONS`, `maps/DisplayConditionsPanel.kt`) : la carte reste visible au-dessus et les commandes du bas remontent de sa hauteur. Retour ou ✕ le ferment.

- **En-tête** : « Conditions d'affichage », ✕, nom de la carte.
- **Zoom** : résumé (« Zooms 8 à 16 », « Tous les zooms »…), « · inactif à ce zoom » quand le calque n'est pas dessiné au zoom affiché, « Réinitialiser » ; barre à deux poignées 0-20 avec le repère du zoom affiché.
- **ET / OU** : deux boutons segmentés centrés, appliqués à l'appui, et une phrase qui dit ce que la carte montre (« Au-dessus de 1500 m, aux zooms 8 à 16 seulement. », « Entière aux zooms 8 à 16 ; aux autres zooms, au-dessus de 1500 m seulement. », « Sans plage de zoom, OU revient à ET. »).
- **Altitude** : résumé (« Au-dessus de 1500 m », « Entre 1000 et 2000 m », « Toutes les altitudes »), « Réinitialiser » ; barre à deux poignées de 0 à 4800 m par pas de 50 m (`ElevationBar`) ; une poignée en bout de barre = pas de limite (« 4800 m et + ») ; repère de l'altitude au centre de la carte (« centre 1834 m »), lue sur les tuiles d'altitudes 300 ms après le dernier déplacement.
- Les deux barres s'appliquent **au relâcher** (pas pendant le glissement : chaque bande redemande les tuiles de l'écran).

Ligne du calque dans Cartes affichées : « Au-dessus de 1500 m » ; avec ET : « Au-dessus de 1500 m · zooms 8 à 16 » ; avec OU : « Au-dessus de 1500 m ou zooms 8 à 16 » ; puis « · inactif à ce zoom » et l'opacité sous 100 %, comme avant.

### 3.1 Sans écran blanc au changement de bande (anti-flash)

Une nouvelle bande est une nouvelle source : le calque reconstruit est vide jusqu'à l'arrivée de ses tuiles (≈ 0,5 s mesurée, plusieurs secondes par le réseau). Pendant ce temps, `MapController` garde l'ancien dessin dessous, en « fantôme » (`tm-g-<id>`, même source et mêmes propriétés que l'ancien calque, `BandSwap.newGhosts`). Le fantôme est retiré quand la carte redevient immobile (`OnDidBecomeIdle`) après au moins une image dessinée, ou tout de suite quand le calque disparaît ou revient sur la source du fantôme (`BandSwap.stale`). Un calque re-réglé pendant le chargement garde son premier fantôme.

## 4. Hors ligne

« Sélectionner et enregistrer une zone » ajoute une ligne **« Altitudes (pour OpenSnowMap) »** dès qu'une carte de la zone a une bande d'altitude (`MapsViewModel.elevationRow`) :

- cochée quand une de ces cartes l'est ; décochable ;
- zooms du plus bas des cartes à bande jusqu'à 13 (`ZoneRequest.zoomLimits["terrarium"]`), bornés par le curseur « Zoom maximal » ; elle n'allonge pas ce curseur ;
- détail : zooms, tuiles et taille comme les autres lignes (« 3-13 · … »), « déjà enregistrées » quand il ne manque rien.

Avec la carte et ses altitudes stockées, tout seuil se règle hors ligne : le masque se recalcule sur les tuiles stockées (§5). Les tuiles masquées ne sont jamais stockées : changer de bande ne demande aucun téléchargement.

## 5. Coût mesuré (émulateur « Medium_Phone », APK benchmark, 2026-10-23)

| Mesure | Résultat |
|---|---|
| Masque d'une tuile 256 px (altitudes interpolées + opacité) | 3 à 6 ms |
| Recodage PNG d'une tuile découpée | 37 à 100 ms (le poste le plus lourd) |
| Redessin plein écran après un nouveau seuil, tuiles stockées | 309 à 453 ms |
| Même chose, tuiles par le réseau | 2,6 à 3,1 s |
| Démarrage à froid hors ligne (mode avion), carte masquée | 1 498 ms, 172 tuiles du stockage, 0 réseau |
| Seuil changé hors ligne (1500 → 1300 m) | 343 ms |

Conclusion : le calcul est léger, le réglage en direct est retenu (au relâcher du curseur). Sur le téléphone de l'utilisateur (P20.6, 2026-10-23), le test est jugé satisfaisant : rien à changer pour le moment. Pistes si un appareil plus lent le demandait : WebP sans perte au lieu du PNG, cache mémoire des tuiles découpées.

En mode avion, MapLibre ne demandait plus aucune tuile (il se croit hors réseau et attend) : `ToolMapApp.ensureMapLibre()` appelle `MapLibre.setConnected(true)`, puisque toutes ses tuiles passent par l'intercepteur, qui sait servir le stockage hors ligne.

## 6. Sauvegarde et favoris

La bande et ET / OU font partie de `LayerConfig` (état de la carte, sauvegarde P9.6) et de `WorkspaceLayer` (favoris) ; un ancien fichier sans ces champs se lit sans bande. Un favori n'est « affiché » que si les bandes sont les mêmes ; ET / OU ne compte que lorsque le calque a les deux conditions.

## 7. Hors périmètre

- OpenSnowMap n'est pas ajoutée au catalogue fourni (carte à importer en `.aqx`) : non demandé.
- Le formulaire « Nouveau téléchargement » (écran Téléchargements) n'ajoute pas les altitudes d'office ; seule la sélection de zone le fait.
- Les tuiles des altitudes sous le plus petit zoom d'une carte à bande ne sont pas téléchargées.
