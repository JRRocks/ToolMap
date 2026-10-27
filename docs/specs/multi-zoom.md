# Spécification — Tuiles partagées, cartes multi-zoom et zooms complétés (P12)

> Demande de l'utilisateur (2026-10-03), avec sept fichiers `.aqx` d'AlpineQuest :
> 1. « Malgré que ce soient les mêmes cartes (ex. IGN TOPO), AlpineQuest les traite indépendamment selon l'AQX, résultant en un téléchargement de la même carte plusieurs fois. »
> 2. « `USR.PERSONNAL_MULTIZOOM.AQX` est l'exemple parfait de ce que je cherche à faire dans ToolMap. AlpineQuest ne propose pas d'outil pour faire interagir des cartes selon le zoom. Devoir modifier à la main un fichier .aqx puis l'importer et retélécharger la zone est fastidieux. »
> 3. « Ne jamais afficher du vide. Si les zooms 5 à 1 sont désactivés, afficher le dernier zoom possible (6), ou proposer un paramètre type `map.scales.default_overscaling_levels=3`. »
> 4. « Les cartes IGN Topo choisissent leur produit selon le zoom ; je souhaite souvent rester sur un IGN Topo 25k. Tu trouveras cette logique de zoom forcé dans certains fichiers AQX. »
> 5. « Installe toute cette logique dans les options de ToolMap. »
>
> Décision d'architecture : ADR-031. Code : `core/…/source/{TileSetIdentity,MapRef,MultiZoomDraft,SourceDuplicates}.kt`, `core/…/tiles/TileResolver.kt`, `data/…/tiles/{MbTilesStore,TileInterceptor,TileComposer}.kt`, `data/…/download/ZoneDownloader.kt`, `data/…/storage/StorageManager.kt`, `app/…/multizoom/`.

## 1. Tuiles partagées (défaut 1)

### 1.1 Identité d'un jeu de tuiles
Deux niveaux de cartes servent les **mêmes images** quand leurs gabarits d'URL sont identiques après normalisation (`TileSetIdentity.normalizeServer`) :

| Étape | Exemple |
|---|---|
| `{$param:k}` remplacé par la valeur du paramètre | `{$param:ign-apikey}` → `ign_scan_ws` |
| Paramètres de requête de clé retirés (`apikey`, `api_key`, `api-key`, `key`, `token`, `access_token`, `accesstoken`, `appid`, `app_id`), ainsi que ceux de valeur vide : deux clés donnent les mêmes tuiles | `…&apikey=ign_scan_ws&…` → (retiré) |
| Schéma ignoré, hôte en minuscules | `HTTP://Data.GeoPF.fr` ≡ `https://data.geopf.fr` |
| Premier libellé d'hôte « tournant » remplacé par `*` quand l'hôte a au moins 3 libellés : une lettre (`a`, `b`, `c`), 0 à 4 lettres suivies de 1 ou 2 chiffres (`mt0`, `khm1`), ou un placeholder (`{$s}`) | `b.tile.opentopomap.org` ≡ `a.tile.opentopomap.org` |
| Paramètres de requête triés, **nom et valeur en minuscules** (WMTS ne distingue pas la casse) ; chemin gardé tel quel | `Request=GetTile&Service=WMTS` ≡ `service=wmts&request=gettile` |
| Plusieurs `<server>` d'un niveau : ensemble trié des gabarits normalisés | |

L'identité d'un niveau est « gabarits normalisés # taille des tuiles », plus « # CRS de `{$bbox}` » quand l'URL utilise `{$bbox}` : une même URL lue en 512 px ou dans un autre CRS est un autre jeu de tuiles. Les zooms n'en font pas partie : un fichier qui s'arrête au zoom 14 et un autre qui va au 16 partagent les zooms communs. Un niveau local (`offline://stored-tiles`) ou une référence à une carte (§2) n'a pas d'identité. Les règles penchent du côté prudent : deux niveaux qui ne diffèrent que d'une façon non listée ne sont pas partagés (stockés deux fois, comme avant).

### 1.2 Nom du stockage
Un fichier `.mbtiles` par **jeu de tuiles**, plus par carte :
- jeu servi par une carte fournie : **id de la carte fournie** (`osm`, `ign-maps`, `ign-scan25`…) — les fichiers existants restent valides ;
- autre jeu : `t-<16 premiers caractères hexadécimaux du SHA-1 de l'identité>` ;
- carte locale (paquet `.mbtiles` importé) : son id, comme avant.

Une carte dont les zooms ont des serveurs différents (IGN « Topo » d'AlpineQuest) a donc plusieurs stockages, un par niveau. La métadonnée `toolmap-source-id` d'un fichier contient l'id du **stockage**.

### 1.3 Conséquences
- Un fichier `.aqx` importé qui décrit IGN Cartes, IGN SCAN 25, OpenStreetMap… lit et écrit **les tuiles déjà stockées** de la carte fournie : rien n'est téléchargé deux fois, en navigation comme en téléchargement de zone.
- **Import** : l'aperçu signale « Déjà présente : mêmes tuiles que « … » » (doublon exact : même nature fond / calque et **tous** les zooms servis à l'identique ; décochée par défaut) ou « Tuiles en commun avec : … » (partage partiel). Importée quand même, la carte en double partage ses tuiles.
- **Stockage** : une ligne par jeu de tuiles, nommée d'après les cartes qui l'affichent, avec « Tuiles partagées par N cartes ». Les chiffres par carte (« Cartes disponibles ») comptent un jeu partagé pour chaque carte.
- **Assistant** : changer l'adresse d'une carte n'efface que les tuiles qu'aucune autre carte n'affiche (`StorageManager.unusedAfterReplacing`).
- **Migration** : au démarrage, `StorageManager.migrateLegacyFiles` verse les fichiers d'avant (un par carte importée, nommés d'après elle) dans le stockage de leurs tuiles, sans écraser une tuile déjà présente, puis supprime l'ancien fichier. Idempotent ; les paquets locaux et les fichiers de cartes disparues ne sont pas touchés.
- Un zoom qu'une carte ne déclare pas (paquet importé avec plus de zooms) est rangé avec son niveau le plus proche.

## 2. Cartes multi-zoom (défaut 2)

### 2.1 Modèle
Une carte multi-zoom est une `TileSource` ordinaire dont **chaque niveau référence une autre carte** du catalogue :

```xml
<source id="mz-rando" type="topo">
  <name>Rando</name>
  <zoom-levels z="1-8"><server><![CDATA[toolmap-map:osm]]></server></zoom-levels>
  <zoom-levels z="9-11"><server><![CDATA[toolmap-map:opentopomap]]></server></zoom-levels>
  <zoom-levels z="12-16"><server><![CDATA[toolmap-map:ign-scan25]]></server></zoom-levels>
  <zoom-levels z="13-14"> … </zoom-levels>
</source>
```

- `toolmap-map:<id>` : à ces zooms, afficher la carte `<id>` (ses propres tuiles, à chaque zoom). L'id est encodé en pourcentage si besoin (`MapRef`).
- `toolmap-map:<id>?z=<n>` : **zoom forcé** — sur toute la plage, afficher les tuiles du zoom `n` de la carte `<id>` : **réduites** (2^k × 2^k tuiles par tuile) si `n` est plus fin, **agrandies** (partie d'une tuile) s'il est plus grossier. `n` peut être au plus 4 niveaux au-dessus du premier zoom de la plage (`FORCED_TOO_FAR` sinon : 256 tuiles pour une).
- Une référence ne stocke **rien** : les tuiles sont celles de la carte affichée, dans son stockage. Une tuile composée (zoom forcé, zoom complété) est redessinée à chaque demande à partir des tuiles stockées, jamais enregistrée.
- Chaîne de références suivie sur 4 niveaux au plus (une boucle s'arrête là ; l'éditeur la refuse : `LOOP`).
- Le reste de la source (nom, crédit, type, aperçu) est déduit des cartes affichées (`MultiZoomDraft.toSource`).
- Extension ToolMap : AlpineQuest ne connaît pas `toolmap-map:`. Une carte multi-zoom n'est donc pas destinée à être exportée vers AlpineQuest (aucun écran ne le propose).

### 2.2 Éditeur (Réglages › Cartes › « Cartes multi-zoom… », ou « Cartes disponibles »)
- **Liste** : les cartes multi-zoom, avec une barre des zooms 0–22 (une couleur par tranche, un point pour un zoom forcé) et le résumé « 1-12 IGN Cartes · 13-14 IGN Cartes (zoom 15) · … » ; Afficher, Modifier (Copier pour une carte fournie), Supprimer (en signalant les cartes qui l'affichent) ; « Nouvelle carte » ; « Partir d'une carte existante… ».
- **Édition** : nom ; barre des zooms ; une carte par tranche avec curseur double (zooms 0–22), choix de la carte (avec ses zooms), interrupteur « Zoom forcé » et curseur du zoom (borné à 4 niveaux au-dessus de la tranche ; il suit la tranche quand on la déplace) ; « Ajouter une tranche » (juste au-dessus de la dernière, même carte) ; problèmes affichés sous la tranche concernée.
- **Problèmes** (`DraftIssue`) — bloquants : nom vide, aucune tranche, plage invalide, chevauchement, carte inconnue, boucle, zoom forcé trop fin ; informatifs : la carte n'a pas le zoom forcé (son plus proche est pris), la carte n'a aucun zoom de la tranche (agrandi ou réduit), zooms sans tranche (remplis par la tranche voisine).
- **Depuis une carte** (`MultiZoomDraft.derivedFrom`) : chaque niveau devient une tranche montrant la carte du catalogue qui sert les mêmes tuiles (la carte fournie d'abord), sinon la carte elle-même ; un zoom manquant **à l'intérieur** d'une liste `<zoom-values>` devient une tranche forcée sur le zoom listé le plus proche (le plus grossier à égalité), ce qu'affiche AlpineQuest. Exemples vérifiés par les tests :
  - `USR.PERSONNAL_MULTIZOOM.AQX` → OSM 1-8, OpenTopoMap 9-11, IGN SCAN 25 12-16, OSM 18, IGN Ortho 19 (zoom 17 sans tranche) ;
  - `IGN_TOP25` du fichier « Pentes Yeti » (zooms 3,5,7-16 d'IGN Cartes) → 3, 4 forcé 3, 5, 6 forcé 5, 7-16.
- Une carte enregistrée est une carte importée (fichier `.aqx` dans `files/sources/`), groupe « Cartes multi-zoom » des « Cartes disponibles » ; elle s'affiche, se télécharge par zone, s'imprime et se sauvegarde comme les autres.

### 2.3 Carte fournie « IGN Topo 25k (zoom forcé) » (`ign-topo25k`)
Le flux `GEOGRAPHICALGRIDSYSTEMS.MAPS` (IGN Cartes) change de produit selon le zoom : SCAN 25 seulement aux zooms 15-16. Cette carte garde l'aspect du 1:25 000 plus loin :

| Zooms affichés | Tuiles |
|---|---|
| 1-12 | IGN Cartes, à chaque zoom |
| 13-14 | IGN Cartes zoom 15 (SCAN 25), réduites |
| 15-16 | IGN Cartes (SCAN 25) |
| 17-18 | IGN Cartes zoom 16 (SCAN 25), agrandies |

Constats sur le serveur (2026-10-03) : `SCAN25TOUR` sert les zooms 6 à 16 (17-18 : 404) ; les tuiles d'IGN Cartes aux zooms 15-16 sont identiques octet pour octet à celles du SCAN 25. `ign-scan25` couvre donc 6-16 (au lieu de 6-14).

## 3. Zooms complétés (« ne jamais afficher du vide »)

`ScalingPolicy(levels)`, réglage **Réglages › Cartes › Zooms manquants** (0 à 4, défaut 3 ; équivalent de `map.scales.default_overscaling_levels` d'AlpineQuest), `AppSettings.maps.overscalingLevels` :

| Cas | Ce qui est affiché | Borne |
|---|---|---|
| Zoom au-dessous du premier zoom de la carte | 4^k tuiles du premier zoom, réduites | `levels` niveaux (0-4) |
| Zoom au-delà du dernier zoom | partie de la tuile du dernier zoom, agrandie | 8 niveaux (0 si `levels` = 0) |
| Trou dans une liste de zooms (`<zoom-values>` 3,5,7-16) | le zoom le plus proche ; à égalité, l'agrandissement (zoom plus grossier) | idem |
| Tranche de carte multi-zoom avec zoom forcé | §2.1 | 4 niveaux au-dessus de la tranche |

Mise en œuvre :
- `TileResolver.resolve(source, z, x, y, policy)` donne une recette (`Direct`, `Enlarge`, `Shrink`, `None`) ; 256 tuiles réelles au plus pour une tuile affichée, réductions cumulées bornées à 4 niveaux.
- L'intercepteur (`TileInterceptor`) sert `Direct` comme avant ; pour une recette composée, il obtient chaque tuile réelle par le même chemin (stockage, réseau, mode hors ligne, en-têtes) puis `BitmapTileComposer` dessine un PNG (parties manquantes transparentes ; aucune tuile trouvée : 404). Statistique `COMPOSED`.
- MapLibre : la plage de zoom de la source raster est élargie vers le bas de `shrinkLevels` (sinon MapLibre ne demanderait pas ces tuiles) ; le style est reconstruit quand la plage ou les niveaux d'une carte changent (`StyleApplier.signature`).
- **Téléchargement de zone** : chaque zoom affiché est traduit en tuiles réelles (`TileResolver.tileZooms`) : la zone hors ligne montre ce qu'on voit en ligne. Une tuile réelle n'est comptée qu'une fois (même stockage, même zoom), d'abord pour le zoom affiché qui la sert telle quelle ; un zoom tiré de ses voisins compte donc 0 tuile si ce voisin est aussi téléchargé. Les limites des serveurs communautaires (OSM…) s'appliquent aux cartes réellement interrogées (celles d'une carte multi-zoom).
- **Impression** : `HttpPrintTileFetcher` compose de la même façon ; « télécharger les tuiles manquantes » demande les tuiles réelles.
- **Test d'une source dans l'assistant** : jamais composé (on teste le serveur lui-même).

## 4. Ce qui n'est pas fait
- Export d'une carte multi-zoom en `.aqx` lisible par AlpineQuest (aplatir les références) : non demandé ; un zoom forcé n'a d'ailleurs pas d'équivalent exact dans AlpineQuest.
- Les tuiles composées sont dessinées en série (une tuile réelle après l'autre) : 64 tuiles pour 3 niveaux de réduction, lent au premier affichage en ligne. À mesurer sur téléphone.
- Aucune vérification sur téléphone (pas d'émulateur) : rendu des tuiles réduites et agrandies, durée, consommation mémoire.
