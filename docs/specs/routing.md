# Spécification — Itinéraires calculés avec BRouter (P19)

> Demande de l'utilisateur (2026-10-18) : « Dans le même menu que pour les waypoints, mets en place la possibilité de créer des tracés. Le tracé doit pouvoir avoir plusieurs étapes : A à B, A vers B à C, A à B avec des points d'intérêt, etc. Utilise BRouter, application Android qui fait de l'autorouting selon OSM. Propose des paramètres d'édition du routing : à partir d'un point en plein milieu, depuis le début ou la fin, une déviation, etc. »
>
> Décisions prises avec l'utilisateur le 2026-10-18 (session 44, questions Q1 à Q21). Décision d'architecture : ADR-064. Remplace P6.5.
>
> **Interface** : rien de ce document n'est codé avant la validation des maquettes (règle UI/UX de `CLAUDE.md`). Maquettes : `docs/previews/p19-routing.html` (P19.0).

## 1. Principe

L'itinéraire calculé est un **itinéraire** (GPX `<rte>`) qui garde, en plus de sa géométrie, **le plan qui l'a produit** : les étapes placées par l'utilisateur et, entre deux étapes, un **tronçon** avec son profil et son mode. On peut donc le rouvrir, déplacer une étape et ne recalculer que les tronçons voisins.

| Terme | Sens |
|---|---|
| **Étape** | Point placé par l'utilisateur. Deux sortes : **passage** (discret, sans nom, force le chemin) et **étape nommée** (nom + icône de waypoint). |
| **Tronçon** | Chemin entre deux étapes consécutives. Mode **routé** (BRouter) ou **à la main** (ligne droite). |
| **Plan** | Liste des étapes + liste des tronçons (n étapes → n − 1 tronçons). |
| **Géométrie** | Concaténation des points des tronçons : c'est elle qui est dessinée, suivie, imprimée, exportée. |

## 2. Moteur : l'application BRouter (AIDL)

- **Hors ligne uniquement** en V1 : ToolMap appelle le service de l'application BRouter installée (`btools.routingapp`). Aucun repli en ligne (V2 : `brouter.de`, P19.12).
- Liaison : `bindService` sur le service `btools.routingapp.BRouterService` ; `<queries><package android:name="btools.routingapp" /></queries>` dans le manifeste (Android 11+). Interface `btools.routingapp.IBRouterService`, méthode `String getTrackFromParams(in Bundle params)` ; le fichier `.aidl` est recopié tel quel dans `:data` (même paquet). **À confirmer en P19.1** sur la version publiée de BRouter (nom du service, paramètres, forme du retour).
- Paramètres envoyés, **un appel par tronçon** (deux points) :

| Clé | Valeur |
|---|---|
| `lonlats` | `lon,lat\|lon,lat` (départ et arrivée du tronçon) |
| `profile` | nom du profil BRouter (§4) |
| `trackFormat` | `json` (GeoJSON : coordonnées `[lon, lat, ele]`, propriétés `track-length`, `filtered ascend`, `total-time`) |
| `maxRunningTime` | `30` (secondes) |
| `nogos` | `lon,lat,rayon|…` (rayon en mètres entiers), seulement s'il y a des zones à éviter (§5.8, P19.8) |

- Retour : un texte qui commence par `{` est le résultat ; tout autre texte est un message d'erreur de BRouter, classé par `BRouterErrors` (`:core`, testé) en : **données absentes** (fichier `.rd5` manquant pour la zone), **profil inconnu**, **pas de chemin**, **délai dépassé**, **autre**.
- **BRouter absent** (paquet non installé) : l'interrupteur « Suivre les chemins » est grisé, avec « BRouter n'est pas installé · Installer » (F-Droid `https://f-droid.org/packages/btools.routingapp/`, sinon Play Store `market://details?id=btools.routingapp`).
- **Données de routage** (`.rd5`, cases de 5° × 5°) : téléchargées **dans BRouter**, jamais par ToolMap. « Ouvrir BRouter » lance l'application (son écran d'accueil mène au gestionnaire de téléchargement).

## 3. Sans données BRouter : deux comportements au choix

Réglage **Réglages ▸ Repères et traces ▸ « Sans données BRouter »** (`placemarks.routingNoData`) :

| Choix | Comportement | Message |
|---|---|---|
| **Tracer le tronçon à la main** (défaut) | Le tronçon en échec passe en ligne droite (mode à la main, marqué « sans données »). L'interrupteur reste allumé : les tronçons suivants sont de nouveau routés là où il y a des données. Quand les données arrivent, « Recalculer » (⋮ de l'étape) le reroute. | « Pas de données BRouter ici : tronçon tracé à la main » · **Tout dessiner à la main** · **Ouvrir BRouter** |
| **Passer tout l'itinéraire en dessin à la main** | Le tronçon en échec passe en ligne droite et l'interrupteur s'éteint : la suite se dessine à la main, comme sur une feuille blanche, jusqu'à ce que l'utilisateur le rallume. | « Dessin à la main activé : pas de données BRouter ici » · **Ouvrir BRouter** |

Le raccourci du message n'agit que sur l'itinéraire en cours ; il ne change pas le réglage.

Les autres erreurs : **profil inconnu** → « Profil « mtb » absent de BRouter » et retour au profil précédent ; **pas de chemin** et **délai dépassé** → tronçon à la main, comme « données absentes » (mêmes deux comportements), avec leur propre texte.

## 4. Profils

Puce **Profil** du mode Itinéraire. Le dernier profil utilisé est retenu (préférence `routingProfile`).

| Libellé | Profil BRouter |
|---|---|
| Randonnée (défaut) | `hiking-mountain` |
| Vélo | `trekking` |
| Vélo route | `fastbike` |
| VTT | `mtb` (s'il est absent de BRouter : message « profil inconnu », §3) |
| Voiture | `car-fast` (livré avec l'application BRouter 1.7.9 : `car-fast`, `car-eco`, `car-vario-nocost` ; `car-vario`, présent dans le dépôt de BRouter, n'est pas installé par l'application. Vérifié sur l'émulateur le 2026-10-18) |
| Plus court | `shortest` |
| Autre… | nom tapé (profil personnel de BRouter), gardé dans la liste |

Changer de profil dans la puce : la feuille propose « Recalculer tout l'itinéraire avec ce profil » (coché) ; décoché, seuls les nouveaux tronçons le prennent. Un tronçon peut avoir son propre profil (⋮ de l'étape ▸ « Profil du tronçon suivant »).

## 5. Édition

### 5.1 Entrée
Panneau du drapeau ▸ tuile **Itinéraire** (inchangée) → mode dessin d'itinéraire avec l'interrupteur **« Suivre les chemins (BRouter) »**, allumé par défaut quand BRouter est installé. Éteint : le dessin à la main actuel (réticule, main levée), dont chaque point est une étape reliée en ligne droite. La tuile **Trace** reste le dessin libre.

### 5.2 Poser une étape
- Au **réticule** : « Ajouter ici » (ADR-019, inchangé). Le réticule est le point central : pendant l'édition, il se place au milieu de la carte laissée entre le bandeau du haut et le panneau « Itinéraire », la carte glissant avec lui (ADR-084).
- **Waypoint existant** sous le réticule : il devient une étape nommée (son nom, son icône).
- **« Depuis ma position »** : pose la première étape à la position GPS (proposé tant que l'itinéraire est vide et que la position est connue).

### 5.3 Opérations (V1)

| # | Opération | Où | Tronçons recalculés |
|---|---|---|---|
| 1 | **Ajouter à la fin** (défaut) | « Ajouter ici » | le nouveau |
| 2 | **Ajouter au début** | Outils ▸ « Ajouter au début » (bascule ; le bandeau dit « Ajout au début ») | le nouveau |
| 3 | **Insérer une étape au milieu** (déviation, « passer par ici ») | réticule à moins de 24 dp de la géométrie : le bouton devient « Insérer ici » | le tronçon coupé, devenu deux |
| 4 | **Déplacer une étape** | étape sélectionnée (réticule dessus ou ligne de la liste) ▸ « Déplacer ici » | les deux voisins |
| 4b | **Reprendre une étape au réticule** (ADR-085) | réticule à moins de 24 dp d'une étape : le bouton devient « Prendre l'étape N » (une étape passe avant le tracé : jamais « Insérer ici » sur elle) ▸ l'étape suit le réticule, reliée à ses voisines par des lignes pointillées, ses deux tronçons pâlis ; panneau « Étape N en main » : « Poser ici » · « Annuler » (aussi ✕ et Retour) · « Supprimer l'étape » | les deux voisins, à la pose seulement |
| 5 | **Supprimer une étape** | étape sélectionnée ▸ « Supprimer » ou ⋮ de l'étape | les deux voisins fusionnés en un |
| 6 | **Tronçon en ligne droite** (hors sentier) | ⋮ ▸ « Tronçon suivant en ligne droite » (et retour « … routé ») | celui-ci |
| 7 | **Profil par tronçon** | ⋮ ▸ « Profil du tronçon suivant » | celui-ci |
| 8 | **Inverser le sens** | Outils ▸ « Inverser » | tous les routés (sens uniques, pentes) |
| 9a | **Boucler vers le départ** | Outils ▸ « Boucler vers le départ » : ajoute une copie de A à la fin | le nouveau |
| 9b | **Aller-retour** | Outils ▸ « Aller-retour » : ajoute les étapes en ordre inverse | les nouveaux |
| 10 | **Partir d'ici** | ⋮ ▸ « Partir d'ici » : sur une boucle, fait tourner les étapes ; sinon supprime les étapes d'avant (confirmation « Supprimer les 2 étapes avant ? ») | aucun (boucle) ou aucun (coupe) |

**Annuler / Rétablir** couvrent tout, y compris les changements de profil et de mode ; ils reprennent les résultats déjà calculés (§6.3) sans rappeler BRouter.

### 5.4 Sortes d'étapes
- **Passage** par défaut ; **étape nommée** quand elle vient d'un waypoint existant.
- ⋮ ▸ « Nommer… » (nom + icône, dialogue du waypoint) / « Rendre discrète ».
- ⋮ ▸ « Enregistrer comme waypoint » : crée un waypoint de la bibliothèque (même nom, même icône).

### 5.5 Liste des étapes
Volet repliable **« Étapes (N) »** : une ligne par étape (pastille A, B, C… ou icône de l'étape nommée ; nom ou « Passage » ; distance depuis le départ ; profil du tronçon suivant s'il diffère). Appui : centre la carte sur l'étape et la sélectionne. ⋮ : Nommer… / Rendre discrète · Enregistrer comme waypoint · Profil du tronçon suivant · Tronçon suivant en ligne droite / routé · Recalculer (tronçon à la main faute de données) · Partir d'ici · Supprimer. Pas de réordonnancement par glisser en V1.

### 5.6 Chiffres et profil altimétrique
- Bandeau, **en haut de la carte à la place de la boîte de position** (variante A des maquettes, validée le 2026-10-18), dès deux étapes et tant que l'interrupteur est allumé : `12,4 km · ↑ 830 m · ↓ 610 m · 4 h 10`. Distance et dénivelés : `TrackStatsCalculator` sur la géométrie (même lissage que les traces). Durée : DIN 33466 (`estimatedWalkMillis`) pour Randonnée ; pour les autres profils, somme des `total-time` de BRouter, les tronçons à la main n'étant pas comptés (la durée s'écrit alors « ≥ 1 h 10 »).
- Bouton **Profil altimétrique** : ouvre le profil existant (P5.5) sur la géométrie en cours, synchronisé avec la carte. Pas affiché en permanence.
- Altitudes : celles de BRouter (SRTM contenu dans ses données) ; un tronçon à la main (ligne droite par choix, ou faute de données BRouter) reçoit les altitudes Terrarium comme une trace (P5.5), par le même client de tuiles que la carte : tuiles enregistrées d'abord, téléchargées si l'appareil est en ligne et hors du mode hors ligne (session 47, `HandLegElevations`) :
  - dans un itinéraire qui suit les chemins, le tronçon est **échantillonné tous les 30 m** le long de sa ligne (droite sur la carte, au plus 500 points), pour qu'une crête ou un vallon entre ses deux étapes compte dans les dénivelés et se voie sur le profil ; ces points font partie de la géométrie enregistrée (le `n` de son `tm:leg`) ;
  - un itinéraire **entièrement à la main** est enregistré par ses seules étapes (ADR-065) : seules ses étapes reçoivent une altitude, pour que les chiffres de l'éditeur restent ceux de la fiche ;
  - un tronçon dont une tuile manque n'a **aucune** altitude (pas une moitié), et n'est pas redemandé avant la prochaine ouverture de l'éditeur ; les altitudes trouvées ne sont ni une modification (pas d'« Annuler », l'itinéraire ouvert ne passe pas « modifié ») ni redemandées après Annuler / Rétablir ou un sens inversé.

### 5.7 Repères sur la carte (pendant l'édition)
Départ : disque vert « A ». Arrivée : disque rouge à la dernière lettre ; une boucle (ou un aller-retour) revient sur son départ et ne montre que « A », sans seconde lettre dessous (session 46). Étapes nommées : icône de waypoint et nom (ADR-062). Passages : petits disques blancs cerclés de la couleur du tracé. Tronçon à la main : pointillé. Tronçon en calcul : pointillé gris. Après l'enregistrement : départ, arrivée et étapes nommées seulement.

### 5.8 Zones à éviter (P19.8, variante A de `docs/previews/p19-nogos.html`, validée le 2026-10-28)
- **Poser** : Outils ▸ **« Éviter une zone »** (sous « Aller-retour », signe ⊖ rouge). Le panneau devient « Zone à éviter » : « Placez le réticule au centre de la zone. », quatre tailles **100 m · 250 m · 500 m · 1 km** (rayon ; la dernière choisie est retenue pour la fois suivante, réglage `routingAvoidRadius`, 250 m par défaut), tuiles **« Éviter cette zone »** (action principale, deux fois plus large) et **« Annuler »** ; ↶ ↷, Outils et « Enregistrer » restent. Un cercle rouge pointillé de la taille choisie suit le réticule ; ✕ et Retour annulent comme « Annuler ».
- **Sur la carte** : disque rouge à 16 %, bord pointillé, panneau de sens interdit au centre, sous le tracé.
- **Retirer** : réticule dans une zone (et sur aucune étape), la tuile principale devient **« Retirer la zone »** (couleurs d'erreur). Priorité sous le réticule : étape, puis zone, puis tracé. Zones superposées : celle dont le centre est le plus proche.
- **Changer la taille** (P30.4, variante C de `docs/previews/p30-itineraires.html`, validée le 2026-10-28) : réticule dans une zone (et sur aucune étape), la zone passe en trait plein (`z` = `hover` du GeoJSON), la consigne « Tirez la poignée pour changer la taille (50 m à 2 km). » prend la place de l'interrupteur, et une **poignée** (disque blanc cerclé de rouge, `ZoneSizeHandle`) se pose sur le cercle, plein est de son centre, reliée à lui par un pointillé fin, sa taille dessous. Tirée au doigt, la taille suit la distance du doigt au centre, arrondie à 50 m, de 50 m à 2 km (`AvoidZone.resized`), le cercle avec elle et tout le tracé pâlit ; au relâcher, la zone garde son centre et prend la taille (`RoutePlanEditor.replaceZone` : un seul « Annuler »), retenue pour la zone suivante (`routingAvoidRadius`). Agrandir recalcule les tronçons routés qui traversent la nouvelle zone ; réduire, tous les tronçons routés (le cache rend aussitôt ceux déjà connus). TalkBack : actions « Agrandir la zone » et « Réduire la zone » (50 m). La pose garde ses quatre puces.
- **Recalcul** : une zone ajoutée ne fait recalculer que les tronçons routés dont la ligne la traverse (un obstacle posé hors du meilleur chemin le laisse le meilleur) ; une zone retirée fait recalculer tous les tronçons routés (le cache rend aussitôt ceux déjà connus sans cette zone). Les tronçons tracés à la main ne bougent pas. Une zone qui contient l'une des deux étapes d'un tronçon n'est pas envoyée pour ce tronçon (`AvoidZone.around`) : BRouter 1.7.10 ne trouve alors aucun chemin (vérifié sur l'émulateur : « pas de chemin », tronçon tracé à la main), et le tronçon doit atteindre son étape.
- **Annuler / Rétablir** couvrent les zones ; « Tout effacer » les retire avec les étapes. Elles sont **enregistrées avec l'itinéraire** (`tm:zone`, §7.3) et reviennent avec « Modifier le tracé » ; un itinéraire à la main qui a des zones s'enregistre avec son plan.
- La ligne sous les chiffres du haut les compte : « 3 étapes · 1 zone évitée ».

### 5.9 Points d'intérêt (P30.1, variante B de `docs/previews/p30-itineraires.html`, validée le 2026-10-28 ; ADR-092)
- **Ce que c'est** : un nom et une icône de waypoint posés sur l'itinéraire ou à côté (une source à 80 m du sentier). Contrairement à une étape nommée, un point d'intérêt **ne change pas le chemin** : BRouter ne le voit jamais.
- **Poser** : sous l'interrupteur, la bascule **« Étape | Point d'intérêt »** (dès la première étape ; gardée tant que l'appli tourne). Sur « Point d'intérêt », la tuile principale devient **« Ajouter le point »** et le réticule une épingle dont la pointe vise le point ; la feuille **« Quel point ? »** propose sept icônes courantes (Source, Refuge, Point de vue, Parking, Danger, Sommet, Photo) et « Autre… » (la grille complète des waypoints) ; toucher une icône propose son nom tant que l'utilisateur n'en a pas tapé un autre ; « Annuler » · « Ajouter ».
- **Modifier** : réticule sur un point d'intérêt (à 24 dp), la tuile principale devient **« Modifier le point »** : la même feuille avec « OK », **« Déplacer »** (panneau « Point en main » : le point suit le réticule, « Poser ici » · « Annuler » · « Supprimer le point ») et **« Supprimer le point »**. Ordre sous le réticule sur « Étape » : étape, point d'intérêt, zone, tracé ; sur « Point d'intérêt » : point d'intérêt, sinon « Ajouter le point ».
- **Liste** : rubrique « Points d'intérêt (N) » sous les étapes du volet, avec la distance depuis le départ et l'écart au tracé (« 2.10 km · à 60 m du tracé », « … · sur le tracé » à moins de 15 m) ; appui : centre la carte ; ⋮ : Modifier… · Enregistrer comme waypoint · Supprimer.
- **Profil altimétrique** : un disque de la couleur de l'icône à la distance du point. **Chiffres du haut** : « 3 étapes · 1 point d'intérêt ».
- **Carte** : icône et nom du waypoint, sous les étapes, pendant la modification et quand l'itinéraire est affiché (un toucher ouvre la fiche de l'itinéraire).
- **Annuler / Rétablir** les couvrent ; « Tout effacer » les retire. Ils sont **enregistrés avec l'itinéraire** (`tm:poi`, §7.3 ; un itinéraire à la main qui en a s'enregistre avec son plan) et l'export GPX les écrit aussi en `<wpt>` ; un import ne les reprend pas une seconde fois en waypoints de la bibliothèque.

## 6. Calcul

### 6.1 Quand
À chaque changement, sans bouton « Calculer » : seuls les tronçons touchés (§5.3) passent à l'état **en calcul**. Un nouveau changement **annule** le calcul des tronçons qu'il touche.

### 6.2 File
`RoutingQueue` (`:app`, coroutines) : un appel BRouter à la fois, dans l'ordre des tronçons ; `withTimeout` de 35 s. Le résultat d'un tronçon est appliqué au plan seulement si le tronçon n'a pas changé entre-temps (clé du §6.3).

### 6.3 Mémoire des résultats
Clé d'un tronçon : (départ, arrivée arrondis à 1e-6°, profil, zones à éviter sous la forme du paramètre `nogos`). Cache LRU de 200 tronçons pour la durée de l'édition : Annuler, Rétablir, Inverser deux fois ou un aller-retour sur un sentier déjà calculé ne rappellent pas BRouter.

## 7. Modèle et stockage

### 7.1 `:core` (pur, testé en JVM)
- `RoutePlan(stops: List<RouteStop>, legs: List<RouteLeg>, zones: List<AvoidZone>, pois: List<RoutePoi>)` ; `RoutePoi(lat, lon, name, sym)` (P30.1 : `nearestPoi`, `placeOf`) ; `AvoidZone(lat, lon, radiusMeters)` (P19.8 : `crosses`, `covers`, `nogos`) ; `RouteStop(lat, lon, kind = PASSAGE | NAMED, name, sym)` ; `RouteLeg(profile, mode = ROUTED | STRAIGHT, state = OK | PENDING | NO_DATA | FAILED, points: List<TrackPoint>, timeMillis?)`.
- `RoutePlanEditor` : les opérations du §5.3 et Annuler / Rétablir ; chaque opération rend le nouveau plan et **la liste des tronçons à recalculer**. Invariant testé : `legs.size == stops.size - 1`, et la géométrie relie les étapes dans l'ordre.
- `RoutingEngine` (interface) : `suspend fun route(from, to, profile, zones): LegResult` ; `BRouterJson` (lecture du GeoJSON) ; `BRouterErrors` (classement des messages).

### 7.2 `:data`
- `BRouterEngine` : implémente `RoutingEngine` par AIDL ; `isInstalled()`.
- Pas de migration Room (ADR-065, remplace la colonne `plan_json` prévue) : le plan est un élément `tm:plan` des extensions de l'itinéraire (`RouteEntity.extrasJson`), sous la même forme que dans le GPX (§7.3). `RouteEntity.points` reste la **géométrie** : affichage, suivi (P9.4), impression, recherche et statistiques n'ont rien à changer. Un itinéraire entièrement à la main s'enregistre sans plan.

### 7.3 GPX
- `<rte>` : un `<rtept>` par point de la géométrie (lisible par toute application) ; le point qui coïncide avec une étape nommée porte son `<name>` et son `<sym>`.
- Le plan dans les extensions de la route, espace de noms ToolMap :
  ```xml
  <extensions><tm:plan xmlns:tm="https://toolmap.app/gpx/1">
    <tm:stop lat="45.9" lon="6.8" kind="named" name="Refuge" sym="Lodge"/>
    <tm:leg profile="hiking-mountain" mode="routed"/>
    <tm:stop lat="45.8" lon="6.9" kind="passage"/>
    <tm:zone lat="45.85" lon="6.85" r="250"/>
    <tm:poi lat="45.86" lon="6.84" name="Source" sym="Spring"/>
  </tm:plan></extensions>
  ```
  `tm:zone` : une zone à éviter (centre, rayon en mètres ; §5.8), après les étapes et les tronçons. `tm:poi` : un point d'intérêt (§5.9), après les zones ; l'export l'écrit aussi en `<wpt>` (ADR-092).
  Réimporté dans ToolMap : itinéraire modifiable avec ses étapes. Ailleurs : extension ignorée, géométrie intacte. Aller-retour GPX testé en JVM.

## 8. Après l'enregistrement
- Fiche de l'itinéraire (P18.2) : « Modifier le tracé » rouvre l'éditeur **avec les étapes et les profils** ; nouvelle commande **« Convertir en trace »** (géométrie copiée dans une nouvelle trace, l'itinéraire reste) ; « Suivre » (alerte d'écart) marche sur la géométrie.
- **Itinéraire sans plan** (dessiné à la main, importé) : s'ouvre en dessin à la main, interrupteur éteint. L'allumer transforme chaque point en passage et recalcule tout ; au-delà de **30 points**, d'abord : « 214 points : simplifier avant de recalculer ? » (Simplifier… · Recalculer quand même · Annuler).

## 8 bis. « Aller vers un point » par les chemins (P30.3, variante C de `docs/previews/p30-itineraires.html`, validée le 2026-10-28 ; ADR-093)
- **Entrées** : « Aller vers ce point » d'un waypoint (fiche sur la carte, bibliothèque) ouvre la **feuille de départ** ; les autres « Aller vers » (résultat de recherche, centre de la carte, favori, membre du groupe, « Enregistrer et y aller ») partent tout de suite avec les choix retenus.
- **Feuille de départ** : icône du waypoint, « Aller vers <nom> », puis « 3,8 km · ↑ 92 m · 1 h 15 · arrivée 15:20 » dès que BRouter a répondu (« Calcul du chemin… » avant) ; le chemin se dessine derrière. « Par les chemins | À vol d'oiseau » ; profils Randonnée, Vélo, VTT, Voiture ; « Recalculer toutes les 1 · 2 · 5 · 10 min » ; « Partir ». Un nouveau choix recalcule le chemin. « Partir » retient les choix (réglages `goToRouted`, `goToProfile`, `goToIntervalMinutes`). Fermée sans « Partir », la navigation qu'elle masquait (s'il y en avait une) reprend.
- **Calcul** (`GoTo`, `:core`) : un tronçon BRouter de la position au point (`GoToWay`), redemandé quand l'intervalle est écoulé (2 min par défaut) et, hors du chemin (plus de 50 m par défaut, réglage `goToOffRouteMeters` 25 · 50 · 100 m), sans attendre l'intervalle mais pas plus d'une fois toutes les 15 s. L'écart se compte au-delà de la distance au chemin au moment du calcul (BRouter part du chemin le plus proche d'un marcheur au milieu d'un champ). Pas de recalcul une fois arrivé (15 m). L'ancien chemin reste affiché jusqu'au nouveau.
- **Chiffres restants** : distance le long du chemin depuis le point le plus proche, montée lissée, durée (DIN 33466 en randonnée, temps de BRouter au prorata pour les autres profils), heure d'arrivée.
- **Sans BRouter, sans données ici** : la ligne droite d'avant (distance et cap), « Pas de données BRouter ici · nouvel essai dans … » (« Ouvrir BRouter » / « Installer » dans la feuille) ; nouvel essai à l'intervalle suivant. **Sans chemin, délai dépassé** : l'ancien chemin reste s'il y en a un.
- **Pastille** (variante A) : flèche vers le chemin à 40 m devant (vers le chemin lui-même quand on s'en écarte), « Vers <nom> », « 3,8 km par les chemins · ↑ 92 m · 1 h 15 », « arrivée 15:20 · recalcul dans 1 min 40 » et l'icône du profil (qui ouvre les profils), « Arrêter ». Hors du chemin : vibration (réglage `goToVibrate`), chemin gris en pointillé, pastille rouge « Hors du chemin (70 m) : calcul d'un nouveau chemin… » puis « … nouveau chemin calculé » 8 s.
- **Carte** : le reste du chemin dans la couleur de la cible avec un bord blanc (`NavLayers`), à la place de la ligne droite pointillée.
- **Écran éteint** : `GoToController` (un seul pour l'application) écoute le GPS chaque seconde ; `GoToService` (premier plan, type `location`, verrou partiel) met sa ligne dans la notification unique : « Vers Refuge du Pré · 3,1 km · arrivée 15:20 » avec « Arrêter ».
- **Réglages** ▸ Repères et traces, bloc « Aller vers un point » : Par les chemins (BRouter), Profil, Recalculer toutes les, Recalculer si je m'écarte de plus de, Vibrer en quittant le chemin.

## 9. Hors V1 (tâches ⬜ de P19)
Itinéraires alternatifs (`alternativeidx`) · instructions de guidage · déviation en faisant glisser le tracé au doigt · repli en ligne sur `brouter.de` · réordonner les étapes par glisser dans la liste.

## 10. Vérification
- JVM : `RoutePlanEditorTest` (les 10 opérations, tronçons à recalculer, Annuler / Rétablir), `AvoidZoneTest` (paramètre `nogos`, traversée, zone sous le réticule), `BRouterJsonTest`, `BRouterErrorsTest`, aller-retour GPX du plan, migration Room (Robolectric).
- « Aller vers un point » : `GoToTest` (ce qui reste, écart, durée, recalcul à l'intervalle, hors du chemin et jamais une fois arrivé, réglages), `GoToControllerTest` (faux GPS et faux BRouter : intervalle, écart et vibration, sans données, à vol d'oiseau, feuille de départ, retour de la navigation masquée, arrivée).
- Émulateur : BRouter installé depuis F-Droid avec une case `.rd5` (par ex. `E5_N45.rd5`, Alpes) ; itinéraire A → B → C en Randonnée, insertion au milieu, déplacement, suppression, boucle, aller-retour, inversion ; tronçon hors de la case téléchargée (deux comportements du §3) ; BRouter désinstallé (interrupteur grisé). Captures à côté des maquettes validées.
