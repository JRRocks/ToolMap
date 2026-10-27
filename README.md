# ToolMap
Un logiciel basé sur AlpineQuest mais avec des fonctionnalités maison.

Application Android native (Kotlin, Jetpack Compose, MapLibre) de cartographie et de randonnée hors ligne. Version actuelle : **0.4.0** (`app/build.gradle.kts`).

## Fonctionnalités

**Parité AlpineQuest**
- N'importe quelle source de cartes (XYZ, TMS, WMTS, WMS, clé API, Referer, en-têtes), import et export des fichiers **AQX**, catalogue de 25 cartes en ligne
- Panneau « Cartes et calques » comme AlpineQuest : superposition (ordre, opacité, couleurs), favoris, cartes disponibles par groupes
- Cartes stockées en local (cache automatique, sélection et téléchargement de zones, stockage interne ou carte SD) pour un usage **hors ligne**
- Traces **GPX / KML / KMZ / GeoJSON** : création, édition, enregistrement GPS, dossiers, statistiques, profil altimétrique ; 313 icônes de waypoint et 33 activités
- Menus, barre du bas, boîtes d'information, panneau Positionnement et réglages rangés comme dans AlpineQuest
- Recherche de lieux, mesure de distance et de surface, formats de coordonnées et grilles (DD, DMS, UTM, MGRS, Lambert 93), alertes de proximité et suivi de trace, altimètre barométrique auto-calibré, sauvegarde et restauration

**Nouveautés**
- **Partage de position en temps réel** toutes les *x* secondes au sein d'un groupe : par un serveur de coordination Firebase (le projet de l'utilisateur) ou en pair-à-pair direct par QR code ; icônes, couleurs et images de profil des membres, SOS
- **Assistant guidé** d'ajout de cartes (source, URL, clé API, zooms, test), avec export AQX
- **Plages de zoom par calque** (ex. IGN Topo de 8 à 16, IGN Plan de 1 à 7, IGN Pentes de 1 à 16)
- **Tuiles partagées et cartes multi-zoom** : une carte décrite par plusieurs `.aqx` n'est téléchargée qu'une fois ; zoom forcé ; jamais de zone vide hors des zooms d'une carte
- **Export PDF à l'échelle** (1:25 000, 1:50 000…) d'une zone, façon mise en page QGIS : grille, échelle, nord, légende, atlas multipage
- **Menu du point central** : cible, waypoint, mesure, coordonnées, partage, ouverture dans une autre application (Waze…)

## Documentation
- [`docs/PLAN.md`](docs/PLAN.md) : architecture et feuille de route (phases P0 à P16)
- [`docs/JOURNAL.md`](docs/JOURNAL.md) : journal de bord du développement
- [`docs/DECISIONS.md`](docs/DECISIONS.md) : décisions d'architecture (ADR)
- [`docs/specs/`](docs/specs/) : spécifications (format AQX, partage P2P et serveur Firebase, export PDF, multi-zoom, sauvegarde, performance, publication, index de l'interface)
- [`docs/comparaison-alpinequest-toolmap.md`](docs/comparaison-alpinequest-toolmap.md) et [`docs/aq-icons-inventaire.md`](docs/aq-icons-inventaire.md) : écarts relevés avec AlpineQuest sur émulateur
- [`CLAUDE.md`](CLAUDE.md) : consignes pour les assistants IA (protocole de suivi)

## État
Les phases P0 à P13 sont codées, à quelques reports près consignés dans le plan ; P14 (cohérence entre l'interface et la logique), P15 (performance) et P16 (écarts avec AlpineQuest) sont en cours. La logique est vérifiée par plus de 1 200 tests JVM et Robolectric. Depuis le 2026-10-15, l'interface est aussi parcourue sur un émulateur Android (navigation, menus, icônes) ; **le GPS réel, les capteurs, la caméra, le partage entre deux téléphones, les services écran éteint et la consommation n'ont pas encore été vérifiés sur téléphone** : ces points sont marqués 🔄 dans [`docs/PLAN.md`](docs/PLAN.md).

L'APK de debug est produit par la CI GitHub Actions (artefact `toolmap-debug-apk` et pré-release `debug-<sha7>`) ; quand la CI ne tourne pas, il se compile en local (`./gradlew assembleDebug` → `app/build/outputs/apk/debug/ToolMap-debug-<version>.apk`). La publication (clé, licence, nom de paquet) est décrite dans [`docs/specs/release.md`](docs/specs/release.md).
