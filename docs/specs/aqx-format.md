# Spécification — Format AQX (AlpineQuest XML map configuration)

> Référence pour P1.2 (parseur), P1.3 (URL des tuiles) et P4.6 (export AQX).
> Sources : aide officielle https://alpinequest.net/en/help/v2/maps/on-demand-create et fichiers communautaires (ex. dépôt GitHub `nnngrach/AnyGIS_maps`).
> Fixture : `fixtures/aqx/IGN_Topo_zoom_test.aqx` (fournie par l'utilisateur).

## Structure générale

```xml
<?xml version="1.0" encoding="utf-8" ?>
<aqx version="11">
  <name>Titre du fichier</name>
  <description>Texte libre / attribution</description>
  <param name="apikey">valeur</param>          <!-- accessible via {$param:apikey} -->
  <source id="ID_UNIQUE" type="topo" layer="false" opacity="100">
    <name>Nom affiché</name>
    ...
    <level> ... </level>                          <!-- forme 1 (utilisée par la fixture) -->
    <zoom-levels z="1-17"> ... </zoom-levels>     <!-- forme 2 (doc officielle, v8+) -->
  </source>
  <source id="..."> ... </source>
</aqx>
```

- **Versions** : 8 à 13 rencontrées ; la 11 est la plus courante (AlpineQuest 2.3.0+). Le parseur doit tolérer les balises inconnues : il les ignore et les journalise en avertissement.
- Les URL sont généralement en `<![CDATA[...]]>`.

## Éléments de `<source>`

| Élément / attribut | Sens | Prise en charge v1 |
|---|---|---|
| `@id` | Identifiant unique (sert de clé de stockage) | ✅ |
| `@type` ou `<type>` | roads, topo, satellite, hybrid, nautical, aeronautical, historical, hillshade, contours (la fixture écrit `<type>TOPO</type>` en élément, en majuscules) | ✅ (insensible à la casse, attribut ou élément) |
| `@layer` | `true` = calque superposé plutôt que fond | ✅ |
| `@opacity` | Opacité initiale 0–100 | ✅ |
| `<name>` | Nom affiché | ✅ |
| `<data-source>` | URL d'information (fixture) | ✅ (affichée) |
| `<copyright>` | Attribution | ✅ (obligatoire en PDF) |
| `<region>` | Code région (ex. `WRLD`) | ✅ (informatif) |
| `<outline>` | Emprise : paires `lon,lat` séparées par des espaces | ✅ |
| `<preview-location>` | `lon,lat,zoom` pour l'aperçu | ✅ |
| `<default-location>` | `lon,lat` | ✅ |

## Définition des niveaux de zoom

**Forme 1** (fixture) :
```xml
<level>
  <zoom-values>1,2,3,...,18</zoom-values>
  <referer><![CDATA[https://data.geopf.fr/]]></referer>
  <server><![CDATA[https://...TileMatrix={$z}&TileCol={$x}&TileRow={$y}]]></server>
</level>
```
**Forme 2** (doc officielle) : `<zoom-levels z="1-17">` ou `z="1,3,5"` (plages et listes combinables), même contenu. `<zoom-level>` (singulier) est l'ancienne forme, dépréciée.

Plusieurs `<level>` / `<zoom-levels>` peuvent coexister dans une source : chacun couvre ses zooms avec ses propres serveurs et paramètres. Notre modèle : `TileSource.levels: List<LevelDef>`.

Certains fichiers (OpenSnowMap, `COM.109.AQX`) regroupent les serveurs et leurs réglages HTTP dans un élément `<servers>` à l'intérieur du niveau : ses enfants sont lus comme s'ils étaient directement dans le niveau (2026-10-03, P12).

Un zoom absent d'une liste `<zoom-values>` (fichier « Pentes Yeti » : `3,5,7-16`) est affiché par AlpineQuest avec le niveau le plus proche ; ToolMap fait de même (zooms complétés, `docs/specs/multi-zoom.md` §3), et l'éditeur de cartes multi-zoom en fait des « zooms forcés » quand on part d'un tel fichier.

### Enfants d'un niveau

| Élément | Sens | v1 |
|---|---|---|
| `<server>` (répétable) | Gabarit d'URL ; plusieurs = répartition de charge (rotation). **Extension ToolMap** : la valeur `offline://stored-tiles` désigne un niveau **sans serveur** (carte locale issue d'un `.mbtiles` importé) : l'intercepteur n'émet jamais de requête réseau, une tuile absente répond 404, et la zone n'est jamais proposée au téléchargement. Comportement d'AlpineQuest face à cette valeur : non vérifié. **Extension ToolMap (P12, ADR-031)** : `toolmap-map:<id>` affiche à ces zooms la carte `<id>` du catalogue, `toolmap-map:<id>?z=<n>` ses tuiles du zoom `n` (zoom forcé) ; c'est ainsi qu'est écrite une carte multi-zoom (`docs/specs/multi-zoom.md` §2). AlpineQuest ne connaît pas ces valeurs. | ✅ |
| `<referer>` | En-tête HTTP Referer | ✅ |
| `<user-agent>` | En-tête User-Agent | ✅ |
| `<header>` (répétable) | En-tête libre `Nom: valeur` | ✅ |
| `<cookie>` | Chaîne de cookies | ✅ |
| `<update-delay>` | Durée de validité du cache : `<n>D`, `<n>W`, `<n>M`, `<n>Y` (jours, semaines, mois de 30 jours, années de 365 jours). `0`, valeur absente ou illisible = **jamais périmée**. Une tuile périmée est retéléchargée, mais servie telle quelle si le réseau échoue ou en mode hors ligne ; une tuile de date inconnue (importée d'un `.mbtiles` tiers) ne périme jamais | ✅ |
| `<max-threads>` | Téléchargements simultanés (défaut 3, max 16 depuis ADR-058) | ✅ |
| `@insecure` | Accepter tous les certificats HTTPS | lu et réécrit à l'export, **jamais appliqué** (ADR-066) : ToolMap accepte à la place les autorités de certification installées par l'utilisateur |
| `<tiles-size>`, `<tiles-size-x>`, `<tiles-size-y>` | Taille des tuiles (défaut 256) | ✅ carrées seulement |
| `<clear-color r="240-255" g=".." b=".."/>` | Rendre transparente une plage de couleurs | ✅ (dans l'intercepteur) |
| `<key-url>` | Lien vers la légende | ✅ (affiché) |
| `<bbox-crs-code>` | CRS de `{$bbox}` (défaut EPSG:4326 pour AQX). Sans cet élément, une URL WMS qui demande elle-même `CRS=` / `SRS=` `EPSG:3857` (ou 900913, 102100, 102113) reçoit ses bbox en EPSG:3857 (fichier « Pentes Yeti », 2026-10-03) | ✅ EPSG:4326 / 3857 |
| `<projection-code>`, `<projection-name>`, `<projection-geoid>`, `<projection-true-origin-x/y>`, `<projection-false-origin-x/y>`, `<projection-resolution>`, `<projection-factor-x/y>`, `<projection-scale-denominator>`, `<tiles-bounds>` | Projections spécifiques | ❌ source marquée « non supportée » (ADR-005) |
| `<expression set="var" type="int">`, `<variable set=... type="single|multi" values choices default display/>` | Variables calculées / choisies par l'utilisateur | ⚠️ lecture + `variable` simple ; expressions évaluées : à étudier (bonus) |

## Placeholders d'URL

| Placeholder | Valeur |
|---|---|
| `{$x}` | Colonne de tuile (schéma XYZ Google/OSM) |
| `{$y}` | Ligne de tuile (XYZ, origine en haut) |
| `{$!y}` | Ligne inversée (TMS) : `2^z - 1 - y` |
| `{$z}` | Niveau de zoom |
| `{$q}` | Quadkey Bing (`quadtree(x,y,z)`) |
| `{$bbox}` | `minx,miny,maxx,maxy` de la tuile, dans `bbox-crs-code` (EPSG:4326 par défaut) |
| `{$ibbox}` | Bbox entière |
| `{$param:clé}` | Valeur d'un `<param name="clé">` global |
| `{$serverpart}` | (rencontré dans des fichiers communautaires) : sous-domaine tournant, à vérifier sur des exemples réels |

## Notes sur la fixture `IGN_Topo_zoom_test.aqx`
- 2 sources : `IGN_MAPS` (couche `GEOGRAPHICALGRIDSYSTEMS.MAPS`, zooms 1–18) et `IGN_SCAN25TOUR` (couche `GEOGRAPHICALGRIDSYSTEMS.MAPS.SCAN25TOUR`, zooms 6–14).
- Service : WMTS privé IGN Géoplateforme `https://data.geopf.fr/private/wmts?apikey=ign_scan_ws&...&tilematrixset=PM&TileMatrix={$z}&TileCol={$x}&TileRow={$y}`, au format JPEG, avec Referer `https://data.geopf.fr/`.
- Testé le 2026-10-01 : `TileMatrix=14&TileCol=8460&TileRow=5950` sur SCAN25TOUR → HTTP 200 `image/jpeg`, même sans Referer.
- L'assistant (P4.2) doit savoir extraire `apikey=ign_scan_ws` en paramètre `{$param:apikey}`.

## Notes d'implémentation (P1.2 parseur, P1.3 URL) — état au 2026-10-02
Code : `core/src/main/kotlin/fr/toolmap/core/{aqx,source,tiles}/`. Tests : `AqxParserTest` (fixture + cas synthétiques), `TileUrlBuilderTest`, `TileMathTest`, `ZoomSetTest`.

- **Encodage de la fixture** : UTF-8 **avec BOM** et fins de ligne CRLF. Le décodeur UTF-8 de la JVM conserve le BOM : le parseur le retire avant l'analyse XML (sinon « Content is not allowed in prolog »).
- **Sécurité** : toute présence de `<!DOCTYPE` ou `<!ENTITY` fait rejeter le fichier (XXE) ; taille maximale 5 000 000 caractères. Les erreurs de format lèvent `AqxParseException` (message anglais, à habiller côté UI).
- **Tolérance** : élément inconnu, `<variable>`/`<expression>` (lus, non évalués), `<header>` mal formé, `<clear-color>` mal formé → `AqxWarning`, l'import réussit. Source sans `id` ou `id` en double → ignorée avec avertissement. Niveau sans `<server>` ou sans zooms valides → ignoré ; une source sans aucun niveau utilisable est « non supportée ».
- **Non supporté** (`TileSource.unsupportedReason`, source conservée mais désactivée) : `projection-*` / `tiles-bounds` / `import-crs-txt-def`, `projection-code` hors {EPSG:3857, 900913, 3785, 102100, 102113}, `bbox-crs-code` hors {EPSG:4326, EPSG:3857}, tuiles non carrées (`tiles-size-x` ≠ `tiles-size-y`).
- **Plafonds** : `max-threads` borné à 1–16 (défaut 3 ; les sources IGN fournies demandent 6, ADR-058).
- **Paramètres** : les `<param>` de niveau fichier sont copiés dans chaque source (la source reste autonome une fois stockée) ; un `<param>` dans une `<source>` redéfinit la valeur.
- **Placeholders** : `{$ibbox}` est interprété comme la bbox **arrondie à l'entier** dans le CRS de `bbox-crs-code` (**hypothèse**, à confirmer sur des fichiers réels). `{$serverpart}` n'est pas géré : `TileUrlException` explicite. Format des nombres de `{$bbox}` : point décimal quel que soit le locale, 10 décimales (degrés) ou 4 (mètres), zéros finals supprimés.
- **Rotation des serveurs** : compteur atomique par `TileUrlBuilder` (un builder par source) ; `build(level, z, x, y, serverIndex)` est la variante déterministe pour les tests.
- **Tuile de référence IGN** : `14/8460/5950` (curl : HTTP 200) est à ≈ 44,11° N ; la tuile du `preview-location` `5.90,44.80` est `14/8460/5906` (`TileMath.tileAt`, vérifié par un calcul Python indépendant).

## Export AQX (P4.6)
Produire `version="11"`, forme 2 (`<zoom-levels z="a-b">`), les `<param>` en tête et les URL en CDATA. Test d'aller-retour : le modèle obtenu après réimport doit être égal au modèle de départ (`AqxWriterTest`, et `SourceDraftTest` pour une source créée par l'assistant).

Notes d'implémentation (P4, `AqxWriter`, `SourceDraft`) :
- **Clés d'API** : l'assistant range toute clé trouvée dans la chaîne de requête en `<param name="apikey">` et la référence par `{$param:apikey}` ; l'export propose de **vider** les valeurs des paramètres (`redactedParams` : `<param name="apikey"><![CDATA[]]></param>`), coché par défaut. À l'import, un paramètre vide fait échouer la construction de l'URL de façon visible (le test de l'assistant répond 401/403).
- **Une source créée par l'assistant n'a qu'un niveau**, avec les réglages HTTP saisis (`<referer>`, `<user-agent>`, `<header>Nom: valeur</header>`, `<cookie>`, `<update-delay>`, `<max-threads>`, `<tiles-size>`). Plusieurs serveurs = plusieurs `<server>` (rotation).
- **WMTS / WMS** : l'assistant ne produit **aucun élément nouveau** ; une couche WMTS devient une URL KVP ordinaire (`…&TILEMATRIX={$z}&TILEROW={$y}&TILECOL={$x}…`), une couche WMS une URL `GetMap` avec `{$bbox}` et `<bbox-crs-code>EPSG:3857</bbox-crs-code>`. Le fichier exporté est donc lisible par AlpineQuest sans extension.
- **`insecure`** n'est plus proposé par l'assistant (ADR-066) : celui d'un `.aqx` importé est gardé tel quel (modification et export), jamais appliqué. Un serveur signé par une autorité d'entreprise ou d'administration marche une fois cette autorité installée dans Android (Réglages ▸ Sécurité ▸ Chiffrement et identifiants) ; sinon l'étape de test dit « Certificat du serveur refusé ».
