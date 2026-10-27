# Plan de la refonte de l'interface (P29) — style uniforme, onglet par onglet

> Demande de l'utilisateur (2026-10-26) : « Revois tout l'UI / UX, utilise les ressources sur https://github.com/pbakaus/impeccable pour retravailler le design de chaque onglet. Maintenant un style uniforme. Propose-moi 5 variantes à chaque changement, et utilise la notification pour me demander de choisir. »
>
> Ce plan est relu à chaque session de la refonte. Il complète la règle de `CLAUDE.md` (aperçu validé avant le code).

## 1. Méthode

- **Ressource** : le skill de design `impeccable` (pbakaus/impeccable, commit `ade3783` du 2026-10-26, licence Apache 2.0), lu comme référence : `SKILL.md`, `reference/android.md`, `mode-operate.md`, `operate.md`, `craft-floor.md`, `audit.native.md`, `colorize.md`, `typeset.md`, `layout.md`, `new-work.md`. Son exécutable (`scripts/impeccable`) n'est pas lancé : seuls les guides servent.
- **Mode « Operate »** : l'utilisateur fait une tâche (carte, randonnée) ; Material 3 garde la structure, la navigation et les contrôles ; le style passe par le thème (rôles de couleur, échelle typographique, formes) et **une** signature visuelle.
- **Cinq variantes par changement**, rendues dans la conversation (état actuel + A à E), copie autonome envoyée en fichier, puis **notification** (`PushNotification`) pour demander le choix. Aucun code avant un choix explicite (`CLAUDE.md`).
- **Un build par étape** ; après le code, capture de l'émulateur à côté de l'aperçu validé (`docs/previews/design-NN-…-emulateur.html`).
- L'étape 0 fixe le **style** (même organisation des écrans, seul l'habillage change) ; les étapes suivantes peuvent changer l'**organisation** d'un onglet, dans le style choisi.

## 2. Constats de l'audit (2026-10-26, version 0.4.76, émulateur)

1. **Couleurs prises au fond d'écran** : `ToolMapTheme(dynamicColor = true)` par défaut (`AppSettings.dynamicColor`) ; sur Android 12 et plus, la palette vient du fond d'écran (lavande sur l'émulateur) : l'appli n'a pas de couleur à elle et change d'un téléphone à l'autre. Les couleurs de marque (`#2E6B3A`, `#8A5A2B`, `ui/theme/Theme.kt`) ne servent que sous Android 11.
2. **Barres du haut différentes** sur les 13 écrans plein écran : « Fermer » en texte à gauche (Cartes disponibles, Partage, Traces…), ✕ en icône (Réglages), titres de taille variable, sous-titre sur certains.
3. **Neuf arrondis** différents (2, 8, 10, 12, 14, 16, 24 dp, 50 %, haut seulement 16) ; 23 couleurs écrites en dur dans 8 fichiers (`draw/DrawControls.kt`, `draw/RouteControls.kt`, `map/MapOverlays.kt`, `multizoom/MultiZoomScreen.kt`, `print/PrintPanel.kt`, `share/PeerStatusIcon.kt`, `tracks/ElevationProfileView.kt`, `ui/theme/Theme.kt`).
4. **Contenants mélangés** : une carte teintée par ligne (Cartes disponibles, Partage) à côté de listes à filets (Réglages) ; boutons pilule pleins, contours pilule, puces carrées et cadre pointillé dans un même panneau (Cartes et calques).
5. **Surtitres en capitales** au-dessus des titres (« CARTE AFFICHÉE », « CARTES AFFICHÉES ») : refusés par les guides (« kicker » au-dessus d'un titre).
6. **Favoris en lettres** (« O », « IS ») sur des pastels pris au hasard, au lieu d'une vignette de la carte.
7. **États vides** réduits à une phrase (Traces : « Aucune trace. Importez… »), sans action mise en avant.
8. Points forts à garder : rôles typographiques Material presque partout (`bodySmall`, `titleMedium`…), cibles de 48 dp et mode gants (64 dp), panneaux de la barre déjà unifiés (`map/PanelParts.kt`, ADR-060).

## 3. Règles communes, quelle que soit la variante

- Une palette ToolMap fixe et son thème de nuit dessiné à part ; « Couleurs du fond d'écran » n'est plus la valeur par défaut (le réglage peut rester).
- Une seule barre du haut : flèche Retour, titre, actions en icônes.
- Trois arrondis (petit, moyen, grand) ; une seule famille de boutons : plein (une action principale par écran), contour, texte.
- Pas de surtitre au-dessus d'un titre : l'information passe dans la ligne sous le titre.
- Listes groupées pour des éléments de même type ; cibles de 48 dp (64 en mode gants) ; texte en sp ; contraste AA vérifié en clair et en sombre.
- Icônes Material d'un seul style ; jamais d'emoji ni de lettres à la place d'une icône.

## 4. Étapes

| ID | Statut | Étape | Aperçu | Choix de l'utilisateur |
|---|---|---|---|---|
| P29.0 | ✅ | **Style uniforme** : palette, typographie, formes, barre du haut, boutons, listes, panneaux (A Forêt · B TOP 25 · C Balise · D Altimètre · E Crête) | `docs/previews/design-00-style.html` (montré le 2026-10-26) ; émulateur : `docs/previews/design-00-style-emulateur.html` | **E · Crête** (2026-10-26) ; codée, ADR-074 |
| P29.1 | ✅ | Carte : boîtes d'information, barre du bas, zoom, point central, bandeaux (A Cartouche · B Coque pétrole · C Bandeau · D Tableau de bord · E Pastilles) | `docs/previews/design-01-carte.html` (montré le 2026-10-27) ; comparaison `docs/previews/design-01-carte-emulateur.html` | **E · Pastilles** (2026-10-27), codée en 0.4.79 |
| P29.2 | ⬜ | Menu principal (logo) et page « Plus… » | — | — |
| P29.3 | ⬜ | Panneau « Cartes et calques », Cartes affichées, favoris | — | — |
| P29.4 | ⬜ | Panneau « Traces et waypoints », écran Traces, détail d'une trace, fiche d'un élément, dialogue waypoint | — | — |
| P29.5 | ⬜ | Panneau « Positionnement », enregistreur | — | — |
| P29.6 | ⬜ | Cartes disponibles, assistant d'ajout, multi-zoom, catalogue en ligne | — | — |
| P29.7 | ⬜ | Téléchargements, Stockage, sélection de zone | — | — |
| P29.8 | ⬜ | Partage de position (hors groupe, en groupe, membres, alertes) | — | — |
| P29.9 | ⬜ | Réglages et Sauvegarde | — | — |
| P29.10 | ⬜ | Recherche, À propos, outils sur la carte (impression, mesure, dessin, itinéraire), dialogues | — | — |

Chaque étape : 5 variantes rendues, notification, choix, code, build, capture de l'émulateur à côté de l'aperçu, entrée du journal, statut ici et dans `docs/PLAN.md`.
