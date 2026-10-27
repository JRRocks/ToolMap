# Fixtures GPX

Fichiers GPX **réels** (non modifiés) utilisés par les tests JVM de `:core` (`fr.toolmap.core.track`).

| Fichier | Origine | Particularités |
|---|---|---|
| `Mojstrovka.gpx` | `tkrajina/gpxpy`, `test_files/` (Apache-2.0) | GPX **1.0** (GPSBabel), une trace de 184 points, dates à fraction de 7 chiffres (années 1901) |
| `cerknicko-jezero.gpx` | `tkrajina/gpxpy`, `test_files/` (Apache-2.0) | GPX **1.0** (GPSBabel), 7 waypoints (avec symboles) + 7 traces numérotées, 296 points |
| `korita-zbevnica.gpx` | `tkrajina/gpxpy`, `test_files/` (Apache-2.0) | GPX **1.0** (GPSBabel), 2 waypoints + 3 traces, 871 points |
| `garmin-connect-run.gpx` | `mapbox/togeojson`, `test/data/run.gpx` (BSD-2-Clause) | GPX **1.1** exporté par Garmin Connect : espaces de noms `gpxtpx` / `gpxx`, extension de fréquence cardiaque sur chaque point, 206 points |

Aucune donnée personnelle de l'utilisateur dans ce dossier.
