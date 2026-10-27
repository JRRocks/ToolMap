# Fixtures KML

| Fichier | Origine | Particularités |
|---|---|---|
| `point.kml`, `linestring.kml`, `multigeometry.kml`, `polygon.kml`, `style.kml` | `mapbox/togeojson`, `test/data/` (BSD-2-Clause) ; exemples de la documentation KML de Google | **Réels**, non modifiés. `style.kml` n'a pas d'espace de noms ; `polygon.kml` a un anneau intérieur |
| `folders-styles-gxtrack.kml` | écrit à la main pour ToolMap | **Synthétique** : dossiers imbriqués, `Style` / `StyleMap`, `TimeStamp`, `gx:Track`, CDATA |
