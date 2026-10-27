# Index de l'interface — boutons, navigation, fonctions (P14)

> Demande de l'utilisateur du 2026-10-12 : « Nous avons copié/collé l'UI/UX d'AlpineQuest sans toujours assurer la logique métier derrière chaque bouton. Crée un index des boutons, de la navigation et des fonctions. »
> Remplace `docs/specs/maps-layers-ui.md` (P11, terminé côté code) : ce fichier est désormais la **référence unique** de l'arborescence des menus. Toute session qui ajoute, déplace ou retire un bouton met à jour cet index.
> Établi par lecture du code au commit `0461bb7` (version 0.2.0), tenu à jour par chaque session depuis (dernière mise à jour : session 49, 2026-10-21, P14 à P16). Vérifié en exécution sur émulateur à partir du 2026-10-15 (§10) ; rien n'a encore été vérifié sur téléphone.

## 0. Légende

| Statut | Sens |
|---|---|
| ✅ | branché de bout en bout : le bouton appelle une fonction qui fait ce que dit son libellé |
| ⚠️ | branché, mais incomplet ou trompeur (comportement partiel, libellé inexact, retour au mauvais écran) |
| ❌ | factice : l'option s'enregistre ou s'affiche, sans effet réel |
| 🕳️ | manque : AlpineQuest l'a (ou l'utilisateur l'attend) et ToolMap n'a aucun bouton |
| 🐞 | défaut de navigation ou de comportement constaté dans le code |

Les identifiants entre crochets (`[A3]`) sont repris par `docs/specs/ui-reorganization.md` et par les tâches P14 de `docs/PLAN.md`.

## 1. Navigation entre écrans

La navigation n'utilise pas de bibliothèque : un `enum Screen` dans `MainActivity.kt` et un `when (screen)`. Il n'y a **pas de pile de retour** : chaque écran code en dur l'écran où revient son bouton « Fermer ».

```
                            ┌───────────── MAP (carte, écran principal) ─────────────┐
                            │  panneaux posés sur la carte (pas des écrans) :        │
                            │  menu principal · Positionnement · Cartes et calques · │
                            │  Cartes affichées · zone · impression · dessin ·       │
                            │  mesure · fiche d'un élément · fiche d'un membre       │
                            └──┬──────┬──────┬──────┬──────┬──────┬──────┬──────┬────┘
        AVAILABLE ◄────────────┘      │      │      │      │      │      │      │
        (Cartes disponibles)          │      │      │      │      │      │      │
          ├─► WIZARD (assistant) ─► STORAGE   │      │      │      │      │      │
          ├─► MULTI_ZOOM                      │      │      │      │      │      │
          └─► STORAGE                         │      │      │      │      │      │
        TRACKS ◄──────────────────────────────┘      │      │      │      │      │
          └─► TRACK_DETAIL ─► DOWNLOADS (autour de la trace)  │      │      │      │
        SETTINGS ◄───────────────────────────────────┘      │      │      │      │
          ├─► BACKUP                                         │      │      │      │
          └─► MULTI_ZOOM                                     │      │      │      │
        SEARCH ◄─────────────────────────────────────────────┘      │      │      │
        SHARE ◄─────────────────────────────────────────────────────┘      │      │
        DOWNLOADS · STORAGE ◄──────────────────────────────────────────────┘      │
        ABOUT · DIAGNOSTICS ◄─────────────────────────────────────────────────────┘
```

Depuis P29.0 (2026-10-26), le « Fermer » écrit en haut à gauche des 13 écrans plein écran est une flèche Retour (lue « Retour ») sur le bandeau pétrole commun (`ui/TopBar.kt`, `ToolMapTopBar`) ; l'assistant d'ajout et l'éditeur multi-zoom ont un ✕ (lu « Annuler »). La flèche mène au même écran que l'ancien « Fermer » du tableau.

| Écran | Ouvert depuis | « Fermer » ramène à | Touche Retour Android | Statut |
|---|---|---|---|---|
| MAP | démarrage | — | ferme l'app (avec confirmation si le réglage est actif) | ✅ |
| AVAILABLE | panneau Cartes ▸ Cartes disponibles | écran d'origine (pile de retour) | écran d'origine | ✅ `[N1]` corrigé (P14.1) |
| WIZARD | Cartes disponibles ▸ Ajouter ; « Modifier / Copier » | écran d'origine | géré (`BackHandler` : étape précédente) | ✅ |
| MULTI_ZOOM | Cartes disponibles (éditeur seul : Annuler / Retour reviennent au catalogue) ; Réglages ▸ Carte (liste ; Retour dans l'éditeur revient à la liste) | écran d'origine | écran d'origine | ✅ `[N1]` corrigé (P14.1) |
| STORAGE | panneau Cartes ▸ Options ; jauge ; assistant (fichier MBTiles : remplace l'assistant) | écran d'origine (Cartes disponibles si ouvert de là ; depuis le panneau Cartes : la carte, panneau rouvert, P18.7) | écran d'origine | ✅ `[N1]` `[N2]` corrigés (P14.1) |
| DOWNLOADS | panneau Cartes ▸ Options ; détail d'une trace | écran d'origine (détail de la trace si ouvert de là ; depuis le panneau Cartes : la carte, panneau rouvert, P18.7) | écran d'origine | ✅ `[N1]` `[N9]` corrigés (P14.1) |
| TRACKS | barre du bas (drapeau) ; import d'un fichier | écran d'origine | écran d'origine | ✅ `[N1]` corrigé (P14.1) |
| TRACK_DETAIL | Traces ▸ Statistiques ; fiche d'une trace sur la carte | écran d'origine | écran d'origine | ✅ `[N1]` corrigé (P14.1) |
| SETTINGS | menu ▸ Réglages… | MAP | MAP | ✅ `[N1]` corrigé (P14.1) |
| BACKUP | Réglages ▸ Stockage et sauvegarde | SETTINGS | SETTINGS | ✅ `[N1]` corrigé (P14.1) |
| ABOUT | « ? » du menu principal (haut de la page) ; « ? » de Cartes et calques, Traces et waypoints, Positionnement (page ouverte à la section du panneau, R5) | écran d'origine (la carte) | écran d'origine | ✅ `[N1]` `[N3]` corrigés (P14.1) |
| SEARCH | drapeau ▸ Rechercher un lieu… | MAP | MAP (le premier Retour ferme le clavier) | ✅ `[N1]` corrigé (P14.1) |
| SHARE | Positionnement ▸ Partage de position (déplacé du menu Plus… le 2026-10-14) ; lien `toolmap://` | écran d'origine | écran d'origine | ✅ `[N1]` corrigé (P14.1) |
| DIAGNOSTICS | Plus… ▸ Diagnostic des tuiles (**APK debug seulement**) | MAP | géré | ✅ `[N4]` réservé aux builds debug (`MainMenuState.diagnostics = BuildConfig.DEBUG`, 2026-10-15) |

**Pile de retour (P14.1, 2026-10-15)** : `MainActivity` garde la liste des écrans ouverts (`ui/BackStack.kt`, sauvegardée par `rememberSaveable`) ; « Fermer » et la touche Retour reviennent à l'écran d'origine, un écran déjà ouvert n'est pas empilé deux fois, « Voir sur la carte » et les actions qui mènent à la carte vident la pile. Les lignes ci-dessus sont à jour. Constat d'origine, corrigé : **`[N1]`** : seuls la carte, l'assistant et le diagnostic déclarent un `BackHandler`. Sur les onze autres écrans, la touche ou le geste Retour d'Android **quitte l'application** au lieu de revenir à l'écran précédent. C'est le défaut le plus visible pour un utilisateur d'AlpineQuest.

**Modes posés sur la carte** et touche Retour :

| Mode | Retour Android | Statut |
|---|---|---|
| Menu principal, Positionnement | ferme le panneau | ✅ |
| Impression | ferme le cadre | ✅ |
| Sélectionner et enregistrer une zone | ferme le mode | ✅ |
| Panneaux Cartes et calques / Cartes affichées (feuilles modales) | ferme la feuille (Material) | ✅ |
| **Dessin** (nouvelle trace, itinéraire, édition) | comme « Quitter » : « Abandonner les modifications ? » s'il y a des changements, sinon ferme le dessin | ✅ `[N5]` corrigé (P14.1) |
| **Mesure** | ferme la mesure, comme « Quitter » | ✅ `[N5]` corrigé (P14.1) |
| Commandes masquées | réaffiche les commandes | ✅ `[N7]` (P14.1) |
| Écran verrouillé | ne quitte pas : rappelle « Maintenez l'écran pour le déverrouiller » | ✅ `[N6]` corrigé (P14.1) |

## 2. Écran carte

### 2.1 Barre du bas (`map/MainMenu.kt`, `MapBottomBar`)

Depuis P29.0 (style « Crête », 2026-10-26) : barre pleine largeur bleu pétrole, jusqu'au bord bas de l'écran, avec le nom sous chaque icône (Menu, Cartes, Traces, Position) ; l'élément dont le panneau est ouvert est posé sur une pastille turquoise clair. Les noms lus par l'accessibilité et les scripts restent « Menu », « Cartes », « Traces et waypoints », « Ma position ». La boussole quitte la barre pour la colonne de droite, au-dessus de l'échange de fond et du zoom.

Depuis P29.1 (variante E « Pastilles » de `docs/previews/design-01-carte.html`, 2026-10-27) : boussole et zoom + / − en ronds blancs de 44 dp (60 en mode gants) à ombre légère, signes bleu pétrole (turquoise clair de nuit ; `MapRoundButton`).

| ID | Bouton | Action | Statut |
|---|---|---|---|
| B1 | Menu (logo) | ouvre le menu principal (§3) | ✅ |
| B2 | Cartes (icône : une carte pliée, comme AlpineQuest, P16.7 ; avant : des calques) | panneau « Cartes et calques » au-dessus de la barre (§5) ; un 2e appui le ferme | ✅ (P14.10, P16.7) |
| B3 | Drapeau | panneau « Traces et waypoints » au-dessus de la barre (§6.0) | ✅ (R1, P14.3 ; P14.10) |
| B4 | Position (appui) | panneau Positionnement (§4) | ✅ |
| B4' | Position (appui long) | cycle libre → suivi → suivi avec boussole ; raccourci des segments « La carte suit » du panneau (L5) | ✅ (`[R4]` réglé, P14.10) |
| B5 | Boussole (colonne de droite depuis P29.0) | remet le nord (de référence) en haut ; cachée au nord | ✅ |
| B6 | + / − (bas droite) | zoom d'un pas (réglage « Pas de zoom ») | ✅ |
| B7 | Échange de fond | bascule entre les deux derniers fonds | ✅ |

### 2.2 Gestes et éléments flottants

| ID | Élément | Action | Statut |
|---|---|---|---|
| G1 | Appui long sur la carte | **une seule** action, choisie dans les réglages : waypoint (défaut), mesure, cible, rien | ✅ inchangé ; le menu rapide est au point central (G12) |
| G2 | Appui sur une trace / un waypoint | fiche (§2.4) ; le nom des waypoints est écrit à droite de leur icône (réglage « Noms sur la carte », ADR-062) | ✅ (P18.1) |
| G3 | Appui sur un membre du partage | fiche du membre ; son nom est écrit à droite de son repère (même réglage) | ✅ (P18.1) |
| G4 | Pastille Position (haut gauche ; une pastille blanche par information depuis P29.1, réglage M8) | appui : petit menu sous la pastille, sur les coordonnées affichées (centre de la carte), avec en tête la puce de la grille et la convergence (P29.1) : Copier les coordonnées · Partager cette position · Format suivant « DD ▸ DM » (change le réglage « Format de position » dans l'ordre DD, DM, DMS, UTM, MGRS, Lambert 93 ; le menu reste ouvert pour enchaîner) | ✅ `[F6]` (P14.6, session 49) |
| G5 | Pastille Échelle : rapport, barre, longueur | appui : montre / cache le zoom (« zoom 16/18 ») | ✅ (P29.1) |
| G6 | Pastille GPS (position en temps réel) : « ± 5 m · ↑ 227 m », ou « GPS (recherche des satellites…) » | appui : montre / cache le détail : satellites, distance de la cible, ligne du baromètre | ✅ (P29.1) |
| G7 | Ligne du baromètre (détail de la pastille GPS, quand le baromètre sert) : pression, altitude | appui : dialogue de calage de l'altitude ; était une boîte à part avant P29.1 | ✅ (P29.1) |
| G8 | Bandeau de cible | distance, cap, « Effacer » | ✅ |
| G9 | Bandeau de suivi / alerte | état du suivi | ✅ |
| G10 | Pastille « écran verrouillé » (appui long) | déverrouille | ✅ |
| G11 | Copyright | affichage | ✅ |
| G12 | Point central : petit anneau au milieu de la carte, comme AlpineQuest ; turquoise cerclé de blanc depuis P29.1 (avant : blanc) | appui sur un waypoint, une trace ou un membre dessiné sous le doigt : sa fiche, comme ailleurs sur la carte (P18.8) ; appui long, ou appui sans rien dessous : menu rapide au-dessus du point, avec une pointe vers lui : bloc teinté avec les coordonnées du point (bouton de format DD ▾ comme le dialogue waypoint ; un appui sur le bloc les copie) et l'altitude du relief (« 282 m · relief ») ; Définir comme cible · Créer un waypoint… · Mesurer depuis ici ; rangée d'icônes Copier · Partager · Ouvrir avec | ✅ (F1, P14.5 ; P14.8 ; P14.10) ; grisé pendant le dessin, la mesure, l'impression, la zone et l'écran verrouillé |
| G13 | Ouvrir un point avec une autre application | bouton « Ouvrir avec » du menu rapide (G12) : envoie la position à l'application par défaut (Réglages ▸ Carte ▸ Ouvrir les positions avec ; défaut : demander à chaque fois) ; Waze reçoit son lien de guidage ; sans application : un message | ✅ (F16, P14.8) ; à voir sur téléphone |

### 2.3 Panneaux d'outils sur la carte

| ID | Panneau | Boutons | Statut |
|---|---|---|---|
| T1 | Dessin (`draw/DrawControls.kt`) | Ajouter ici · Déplacer ici · Supprimer le point · Main levée · Annuler · Rétablir · Outils ▸ Inverser · Simplifier… · Rogner avant · Rogner après · Couper · Tout effacer · Enregistrer (nom proposé : « Tracé du … » ou « Itinéraire du … ») · Quitter | ✅ ; Retour `[N5]` corrigé |
| T1b | Itinéraire (`draw/DrawControls.kt`, `draw/RouteControls.kt`, P19) | Interrupteur « Suivre les chemins (BRouter) » (grisé avec « Installer » sans BRouter) · puce Profil (Randonnée, Vélo, Vélo route, VTT, Voiture, Plus court, Autre… ; « Recalculer tout l'itinéraire avec ce profil ») · allumé : bandeau des chiffres en haut de la carte (variante A) · Annuler · Rétablir · Ajouter ici / Insérer ici (réticule sur le tracé) · Depuis ma position (itinéraire vide) · Déplacer ici · Supprimer l'étape · Étapes (N) ▸ volet (appui : centre et sélectionne ; ⋮ : Nommer… / Modifier… · Rendre discrète · Enregistrer comme waypoint · Profil du tronçon suivant · Tronçon suivant en ligne droite / routé · Recalculer · Partir d'ici · Supprimer) · Outils ▸ Ajouter au début · Inverser le sens · Boucler vers le départ · Aller-retour · Profil altimétrique · Tout effacer · Enregistrer · Quitter ; éteint : le panneau T1 sous l'interrupteur ; message BRouter (Tout dessiner à la main · Ouvrir BRouter) ; plus de 30 points à la main : « Recalculer quand même · Simplifier… · Annuler » | 🔄 codé (session 45, maquettes validées le 2026-10-18) ; à vérifier sur l'émulateur avec BRouter |
| T2 | Mesure (`measure/MeasureBar.kt`) | distance / surface · annuler · rétablir · effacer · Garder comme itinéraire · comme trace ; en mode Surface, dès 3 points : « Télécharger cette zone… » → « Nouveau téléchargement » avec le polygone mesuré | ✅ ; Retour `[N5]` corrigé ; `[F10]` (P14.6, session 49) |
| T3 | Impression, P17 (`print/PrintPanel.kt`, `print/PrintOverlay.kt`) | **1.** fenêtre Taille (A5 · A4 · A3 · Letter · Autre…) + orientation, Échelle (1:10 000 à 1:250 000 · Autre échelle…), résumé, Annuler · Choisir la zone ; **2.** cadre posé sur la carte (un doigt dedans le déplace, deux doigts zooment ; nord en haut, zoom réglé à 70 %), bandeau Format ▾ · Orientation ▾ · Échelle ▾ · ⟲ Tourner la feuille, Annuler · Suivant, et rien d'autre sur la carte (ni boîtes, ni crédits, ni barre du bas, ni boutons ; un SOS du groupe reste affiché ; taille au sol et alertes sur la confirmation, P17.5) ; **3.** confirmation : résumé, Grille (Aucune · UTM · MGRS · Lambert 93 · WGS84 DMS · WGS84 décimal), Waypoints, Traces, Titre, tuiles manquantes ▸ Télécharger (≤ 1 500), Plus d'options ▸ style IGN TOP 25 / OTAN, dpi, fichier léger, plages de zoom, marge, pas de grille, noms des waypoints, éléments de la feuille, sous-titre ; Retour à la zone · Créer le PDF ▸ « PDF enregistré · 1 page · … » Ouvrir · Partager · Fermer. Retour : confirmation ▸ cadre ▸ fermé. ~~Atlas, modèles, aperçu~~ retirés (ADR-061) | ✅ P17 (parcours vérifié sur émulateur ; PDF A4 validés par l'utilisateur) |
| T3b | Impression le long d'une trace (`print/PrintOverlay.kt`, `TrackPagesOverlay`) | depuis le détail d'une trace (§6.2) : la trace est affichée, mêmes questions que T3 (taille, échelle), puis les pages enchaînées le long de la trace, numérotées dans l'ordre de la marche, 10 % de recouvrement, nord en haut ; elles ne bougent pas (la carte garde ses gestes) ; bandeau Format ▾ · Orientation ▾ · Échelle ▾ ; « N pages le long de la trace » · Annuler · Suivant (confirmation, puis un PDF d'une page par cadre, sans page d'assemblage) | ✅ `[F9]` (P14.6, session 49, ADR-067) |
| T4 | Enregistrement (`record/RecordingPanel.kt`, `RecordingChip`) : pastille turquoise en haut à droite de la carte, point rouge (⏸ « En pause · » en pause), « durée · distance » ; rouge quand le GPS manque ou que l'enregistrement échoue (P29.1 ; avant : panneau gris sous les boîtes) | appui : menu avec les chiffres en direct (durée, distance, vitesse, dénivelé), les alertes, Pause · Reprendre · Arrêter | ✅ (P29.1) |
| T5 | Zone (`maps/SaveAreaPanel.kt`) | 4 poignées · case par carte · curseur Zoom maximal · Enregistrer la zone · « ? » ; tuiles déjà stockées comptées à part : « À télécharger : 458 tuiles · 10.8 Mo (31 déjà stockées) », même détail par carte, « Déjà enregistrée » et bouton grisé quand il ne manque rien (P18.6) ; ligne « Altitudes (pour …) » quand une carte a une bande d'altitude : zooms jusqu'à 13, décochable (P20) | ✅ |

### 2.4 Fiche d'un élément touché (`tracks/TappedItemSheet.kt`)

| Bouton | Statut |
|---|---|
| Détails (trace) → TRACK_DETAIL | ✅ |
| Masquer de la carte | ✅ |
| Modifier le tracé (trace, itinéraire) → mode dessin | ✅ |
| Modifier le waypoint | ✅ |
| Dialogue « Nouveau waypoint » / « Modifier » (`waypoints/WaypointDialog.kt`, allégé en P14.9) : pastille du symbole (disque de la couleur de catégorie, glyphe blanc, petit crayon) à gauche du **Nom** → sélecteur « Symbole » : champ de recherche, 16 catégories repliables (nombre d'icônes), 313 icônes ; l'icône choisie est celle de la carte (ADR-056). Nouveau waypoint : nom prérempli « Waypoint N » (N = plus grand numéro existant + 1, `WaypointNames`), sélectionné, clavier ouvert. Une ligne de **coordonnées** (lecture) avec le bouton de format (DD, DM, DMS, UTM, MGRS, L93 ▾ : menu des six formats, réécrit la position) et une ligne d'**altitude** : lue sur le relief (Terrarium) pour un nouveau waypoint (« 442 m · relief », « Altitude… » pendant la lecture, « Altitude inconnue » hors ligne). Le crayon ouvre la saisie libre des coordonnées (tous formats, messages d'erreur) et de l'altitude ; en modification seulement, « Déplacer au centre de la carte ». « + Ajouter une description » ouvre le champ (ouvert d'office s'il y a une description). Actions : Annuler · **Y aller** (tonal : enregistre et définit la cible) · **Enregistrer** (plein) | ✅ (P16.6, P14.9) |
| Aller vers | ✅ |
| Suivre / Ne plus suivre (alerte d'écart) | ✅ |
| Alerte de proximité (waypoint) | ✅ |
| Supprimer (confirmation « Supprimer « nom » ? Cette action est définitive. »), Partager (GPX de l'élément seul, sélecteur d'Android), Exporter (le GPX dans un fichier : sélecteur de fichiers d'Android, nom proposé d'après l'élément ; « Fichier GPX écrit ») ; deuxième rangée en 4 tuiles Partager · Exporter · Masquer · Supprimer | ✅ (P18.2, session 41) ; Exporter `[F8]` variante A (P14.6, session 49) |
| Présentation (P18.2) : en tête, l'icône du waypoint (pastille de sa catégorie) ou un disque de trace / d'itinéraire, le nom et une ligne de chiffres (altitude · coordonnées ; longueur, ↑, ↓) ; le commentaire et la description ; bloc teinté « DEPUIS VOTRE POSITION : 3.90 km · cap 146° SE » quand la position en temps réel est active ; commandes en tuiles (style des panneaux, ADR-060) : waypoint = Aller vers ce point · Alerte de proximité · Modifier, puis Partager · Masquer · Supprimer ; trace = Statistiques · Suivre · Modifier le tracé, puis les trois mêmes ; itinéraire = Suivre · Modifier le tracé (rouvre les étapes et les profils) · Convertir en trace, chiffres « km · ↑ · ↓ · durée · profil » (P19, session 45) | ✅ |

## 3. Menu principal (logo)

Depuis P14.10 (session 38), le menu suit le style du dialogue « Nouveau waypoint » : pastille en tête, tuiles tonales pour les commandes fréquentes, bloc teinté pour l'état, lignes avec sous-titre et chevron (`map/PanelParts.kt`, partagé par les quatre panneaux de la barre).

| ID | Élément | Action | Statut |
|---|---|---|---|
| M1 | En-tête : pastille (montagne), « ToolMap », « Version <version> » + « ? » | ouvre ABOUT en haut de la page (les « ? » des panneaux l'ouvrent à leur section, R5) | ✅ retour vers la carte `[N3]` corrigé |
| M2 | Tuile Masquer | barre réduite au logo ; le logo rouvre le menu, dont la tuile devient « Afficher » ; Retour les réaffiche aussi | ✅ `[N7]` corrigé (P14.1) |
| M3 | Tuile Verrouiller | gestes et appui long coupés, commandes cachées ; Retour bloqué | ✅ `[N6]` `[N8]` corrigés (P14.1) |
| M4 | Tuile Imprimer | parcours T3 (deux questions, cadre, confirmation) ; grisée pendant un outil | ✅ |
| M4b | Tuile Mesurer | outil de mesure (T2), venu de la page Plus… ; grisée pendant un outil | ✅ (P14.10) |
| M7 | Bloc « Carte affichée » : nom du favori appliqué ou « Pile personnalisée », cartes de la pile (« Pentes + Plan IGN ») et leurs vignettes | ouvre le panneau Cartes et calques (§5) | ✅ `[R2]` réglé : la section « Espace de travail » est retirée, le nom du favori courant reste ici (P14.10) |
| M8 | Informations à l'écran (« Position, échelle, GPS ») | interrupteur : montre / cache les pastilles d'information | ✅ |
| M9 | Mode nuit (« Carte assombrie à 60 % ») | interrupteur : assombrit les cartes (luminosité des fonds et calques), pas les traces, les waypoints ni les menus ; l'intensité se règle dans Réglages ▸ Affichage (§8) | ✅ `[F14]` variante B (P14.6, session 49, ADR-067) |
| M5 | Réglages… | SETTINGS ; Retour dans une catégorie ouverte la replie d'abord (P16.1) | ✅ |
| M6 | Plus… (« Nouveautés, signaler une erreur ») | page « Plus… » (§3.1) | ✅ |
| M5b | Quitter (en bas, séparé) | ferme l'écran ; « Quitter ToolMap ? » demande confirmation, et précise que l'enregistrement, le partage ou les alertes en cours continuent (ADR-053) | ✅ (P16.2 ; placé en bas en P14.10) |

### 3.1 Page « Plus… »

| ID | Section | Ligne | Statut |
|---|---|---|---|
| P1 à P3 | Traces | section retirée : Dessiner une trace, Dessiner un itinéraire et Ajouter un waypoint ici sont dans le panneau du drapeau (B3) | ✅ (R1) |
| P4 | Outils | Rechercher un lieu… déplacé dans le panneau du drapeau (B3) | ✅ (R1) |
| P5 | Outils | Mesurer distance et surface : tuile du menu principal (M4b) | ✅ (P14.10) |
| P6 | Outils | Partage de position… : ligne du panneau Positionnement (L7) | ✅ (P14.10) |
| P7 | Cartes hors ligne | « Télécharger la zone affichée… » retiré : un seul parcours, « Sélectionner et enregistrer une zone » (§5.3) | ✅ (R3, P14.3) |
| P9 | Aide | Nouveautés : les 5 dernières entrées du journal, embarquées à la compilation (`WhatsNewTask`) | ✅ (P16.3) |
| P10 | Aide | Signaler une erreur : texte de diagnostic (version, appareil, dernier plantage) partagé par le sélecteur Android ; rien n'est envoyé automatiquement | ✅ (P16.3) |
| P8 | — | Diagnostic des tuiles → DIAGNOSTICS ; la ligne n'existe que dans l'APK debug. La page n'a plus de sections : Nouveautés, Signaler une erreur, Diagnostic (P14.10) | ✅ `[N4]` (outil de mise au point, absent en release) |

## 4. Panneau « Positionnement » (bouton position)

| ID | Ligne | Action | Statut |
|---|---|---|---|
| L1 | « ? » | ouvre ABOUT à la section « Positionnement » (nouvelle : position en temps réel, La carte suit, enregistreur), surlignée 1,6 s | ✅ `[R5]` `[F11]` variante B (P14.3, session 49) ; retour vers la carte (`[N3]` corrigé) |
| L3 | Bloc « Position en temps réel » : interrupteur, état « ± 5 m · alt. 282 m · GPS » (ou « GPS + baromètre »), « En attente du GPS… » ou « Désactivée » | interrupteur | ✅ (P14.10) |
| L5 | « La carte suit » : segments Libre · Suivi · Boussole · Explorer | applique le mode ; allume la position si elle est éteinte ; Libre et Suivi remettent la carte à plat, Explorer = suivi + cap + inclinaison 60° (ancienne ligne « Mode explorateur ») ; le segment allumé suit aussi l'appui long sur B4 | ✅ `[R4]` réglé (P14.10) ; 60° à valider sur téléphone |
| L4 | Enregistreur de trace (« Arrêté » ou « En cours · 1,2 km · 0:24 ») ; icône : une trace et une épingle, comme AlpineQuest, rouge pendant l'enregistrement (P16.7) | démarre (dialogue) / arrête | ✅ |
| L6 | Baromètre (« Corrige l'altitude du GPS ») | interrupteur, visible seulement si le téléphone a le capteur ; même réglage que Réglages (ADR-053) ; icône propre (jauge) | ✅ (P16.2) |
| L7 | Partage de position (« Aucun groupe » ou « 3 membres · position envoyée ») | ouvre SHARE (chevron : plus d'interrupteur qui ouvrait un écran) | ✅ (P14.10) |

## 5. Panneau « Cartes et calques » (bouton Cartes)

### 5.1 Racine (`maps/MapsRootPanel.kt`)

Depuis P14.10, un panneau posé au-dessus de la barre comme les trois autres (plus une feuille du bas) ; il reste ouvert au retour de Cartes disponibles ; Retour ou un appui hors du panneau le ferme.

| ID | Ligne | Statut |
|---|---|---|
| C2 | Bloc « Cartes affichées » : noms de la pile, « 2 cartes · ordre, opacité, couleurs », vignettes superposées → feuille §5.2 | ✅ (P14.10) |
| C1 | Cartes disponibles → AVAILABLE | ✅ |
| C3 | Sélectionner et enregistrer une zone → mode T5 | ✅ |
| C4 | « Cartes et ensembles favoris » : rangée de tuiles qui défile ; appui = appliquer ; noms sur deux lignes sans couper les mots | ✅ (P14.10) |
| C5 | Favoris : appui long = supprimer | ✅ supprime aussi un favori fourni (masqué, `hidden-builtins.txt`), rétabli par Options ▸ « Rétablir les favoris fournis (n supprimé(s)) » ; favoris fournis, seuls depuis ADR-069 : « Rando Multi-Zoom » (cinq cartes empilées avec leurs plages de zoom) et « Sortie Ski » (IGN SCAN 25, SnowBase Map au-dessus de 1550 m, puis à partir du zoom 12 les pistes, les traces Yeti et les pentes IGN à 30 %) (ADR-052, ADR-063, ADR-069). Reste : ni renommer, ni réordonner, ni mettre à jour un favori `[F2]` |
| C6 | Tuile « + Enregistrer l'affichage » en tête des favoris (remplace le signet) | ✅ (P14.10) |
| C7 | Mode hors ligne (« Tuiles stockées seulement ») : interrupteur visible, plus dans un menu Options | ✅ (P14.10) |
| C8 | Puce Couverture (filtre) | ✅ (P14.10) |
| C9 | Puce Importer .aqx | ✅ (P14.10) |
| C10 | Puce Téléchargements → DOWNLOADS ; le retour rouvre ce panneau (P18.7) | ✅ (P14.10) |
| C11 | Puce Stockage → STORAGE, le retour rouvre ce panneau (P18.7) ; puce « Rétablir les favoris fournis » quand il y en a de supprimés | ✅ (P14.10) |
| C12 | « ? » | ABOUT à la section « Cartes et calques », surlignée 1,6 s | ✅ `[R5]` (session 49) |

### 5.2 Cartes affichées : menu ⋮ d'un calque (`maps/DisplayedMapsSheet.kt`)

| Entrée | Statut |
|---|---|
| Ligne d'un calque : nom, puis « N % d'opacité » ; pour un calque limité à des zooms : « Visible aux zooms 12 à 17 » (« Visible au zoom 19 » pour un seul), « · inactif à ce zoom » hors de sa plage, l'opacité seulement sous 100 % (ADR-063) ; avec une bande d'altitude : « Au-dessus de 1500 m », « Au-dessus de 1500 m · zooms 8 à 16 » (ET), « Au-dessus de 1500 m ou zooms 8 à 16 » (OU) | ✅ (P20) |
| Œil (afficher / masquer), goutte (opacité) | ✅ |
| Détails | ✅ |
| Forcer la mise à jour (session) | ✅ |
| Monter · Descendre · Ne plus afficher · Ne plus afficher les autres | ✅ (pas de glisser-déposer) |
| Opacité · Modifier les couleurs · Couleurs inversées · Conditions d'affichage (remplace « Plage de zoom », P20) | ✅ |
| **Conditions d'affichage** (`maps/DisplayConditionsPanel.kt`, `MapsPage.CONDITIONS`) : panneau non modal en bas de la carte, les commandes du bas remontent ; en-tête, ✕, nom de la carte ; Zoom : résumé, « · inactif à ce zoom », Réinitialiser, barre 0-20 et repère du zoom affiché ; ET / OU (boutons segmentés) et phrase de ce que la carte montre ; Altitude : résumé, Réinitialiser, barre 0-4800 m par 50 m (bout de barre = sans limite, « 4800 m et + »), repère « centre N m » ; les barres s'appliquent au relâcher, l'ancien dessin reste jusqu'au nouveau ; Retour ou ✕ le ferment (spec `elevation-maps.md` §3, ADR-068) | ✅ (P20, session 50) |
| Mode de fusion, teinte (panneau « Couleurs et fusion ») | ⬜ P26 (`docs/plan-fonctions-2026-10.md`) |

### 5.3 Écran « Cartes disponibles » (`maps/AvailableMapsScreen.kt`)

| Entrée | Statut |
|---|---|
| Jauge de stockage + « Gérer » → STORAGE | ✅ ; le retour de STORAGE revient au catalogue (`[N2]` corrigé) |
| Champ « Rechercher une carte » en tête : nom et titre du groupe, sans casse ni accents ; « Aucune carte ne correspond à « … ». » ; les groupes trouvés restent dépliés | ✅ `[F5]` (P14.6, session 49) |
| Groupes repliables (OSM, IGN, Mes cartes, Locales, Autres) | ✅ |
| ⋮ Afficher comme fond · Ajouter comme calque · Ne plus afficher | ✅ |
| ⋮ Modifier (importée) / Copier (fournie) → WIZARD | ✅ |
| ⋮ Créer une carte multi-zoom à partir de celle-ci | ✅ |
| ⋮ Supprimer (cartes importées) ; ⋮ Masquer de la liste (cartes fournies) : la carte quitte le catalogue, les favoris et l'affichage (« Cartes indisponibles ignorées ») ; puce « N cartes masquées · Afficher » : les montre en grisé, « Masquée · ⋮ Remettre dans la liste » | ✅ `[F3]` (P14.4, session 49, ADR-067) ; la liste suit la sauvegarde (réglages) |
| ⋮ Télécharger une zone… (retour à la carte, « Sélectionner et enregistrer une zone » avec cette seule carte cochée) · Vider les tuiles stockées (avec la taille ; seulement si la carte en a) · Exporter en .aqx… (la carte seule ; les clés d'une carte ajoutée sont retirées, comme dans l'assistant) | ✅ `[F4]` (P14.6, session 49) |
| Ajouter : Nouvelle carte… · Nouvelle carte multi-zoom · Importer `.aqx` · Catalogue en ligne… | ✅ |
| Catalogue en ligne (`maps/OnlineCatalogScreen.kt`, ADR-054) : 25 cartes par fournisseur, « Ajouter » / « Ajoutée », clé demandée pour Ordnance Survey ; Retour revient à Cartes disponibles | ✅ (P16.4) |

## 6. Traces et waypoints

### 6.0 Panneau du drapeau (`map/TracksMenu.kt`, `TracksMenuPanel`)

| Entrée | Statut |
|---|---|
| Champ « Rechercher un lieu, des coordonnées » → recherche | ✅ (P14.10) |
| Tuiles Waypoint ici (grisée pendant le dessin) · Trace · Itinéraire (grisées pendant le dessin et la mesure), chacune avec son icône | ✅ (P14.10) |
| Explorer les traces et waypoints (« 1 trace · aucun itinéraire · 12 waypoints » ; zéro s'écrit « aucun », P18.5) → écran Traces (6.1) | ✅ (P14.10) |
| Importer un fichier… (« GPX, KML, GeoJSON ») → sélecteur de fichiers, comme l'import de l'écran Traces | ✅ (P14.10) |
| Tout afficher sur la carte : interrupteur ; éteint, il masque dossiers, traces, itinéraires et waypoints ; allumé, il les remontre tous | ✅ (P14.10) |
| « ? » de l'en-tête → ABOUT à la section « Traces et waypoints », surlignée 1,6 s | ✅ `[R5]` (session 49) |
| Touche Retour : ferme le panneau | ✅ |

### 6.1 Liste (`tracks/TracksScreen.kt`)

| Entrée | Statut |
|---|---|
| Importer (barre) | ✅ |
| Case de visibilité par ligne et par dossier | ✅ |
| ⋮ dossier : Renommer · Nouveau sous-dossier · Déplacer vers… · Exporter · Partager · Supprimer | ✅ (P16.5) |
| ⋮ trace : Statistiques · Afficher sur la carte · Renommer · Déplacer vers… · Style · Exporter · Partager · Supprimer | ✅ |
| ⋮ itinéraire : Afficher · Renommer · Déplacer vers… · Style · Exporter · Partager · Supprimer | ✅ |
| ⋮ waypoint : Afficher · Renommer · Déplacer vers… · Exporter · Partager · Supprimer | ✅ ; ⚠️ pas « Modifier » (symbole, coordonnées) ici, seulement depuis la carte |
| Nouveau dossier (haut de l'écran) ; Déplacer vers… (haut de la bibliothèque ou un dossier ; un dossier ne peut aller ni dans lui-même ni dans ses sous-dossiers) | ✅ (P16.5, ADR-055) |
| Puces sous la barre : tri « Date ↓ » (menu : Date (récent d'abord) · Nom · Distance) puis filtre Tout · Traces · Itinéraires · Waypoints ; tri et filtre gardés d'une ouverture à l'autre (`AppSettings.library`) ; les dossiers restent en tête | ✅ `[F7]` (P14.7, session 49) |
| Loupe de la barre : « Rechercher par nom » (tous les mots, sans casse ni accents) ; avec un texte, liste à plat de tout ce qui correspond, dossiers compris | ✅ `[F7]` |
| Appui long sur un élément : sélection multiple ; barre « N sélectionnés » avec ✕, Afficher / masquer sur la carte, Déplacer vers…, Exporter en un seul GPX… (« Sélection »), Supprimer (confirmation « Supprimer N éléments ? ») ; Retour ou ✕ quittent la sélection | ✅ `[F7]` |
| Dossiers ouverts et position de la liste gardés au retour d'un détail et quand on ferme puis rouvre l'explorateur, tant que l'application tourne ; tout est replié au lancement suivant (`TracksViewModel.expandedFolders`, `libraryScroll`) | ✅ (P14.11, session 49) |
| Pas de bande vide sous la barre du haut (barre d'état comptée une seule fois : `consumeWindowInsets`) ; de même dans le détail d'une trace et Cartes hors ligne | ✅ (P14.11) |
| Sous-titre d'une ligne : « Trace · 8.64 km · 21 oct. », « Itinéraire · … », « Waypoint · 1520 m · … » ; dossier : « Dossier · 5 éléments » | ✅ (P14.7) |
| Nouvelle trace / nouvel itinéraire / nouveau waypoint depuis la liste | 🕳️ (seulement depuis la carte) |

### 6.2 Détail d'une trace (`tracks/TrackDetailScreen.kt`)

| Entrée | Statut |
|---|---|
| Statistiques, profil, curseur relayé à la carte | ✅ |
| Compléter les altitudes (Terrarium) | ✅ |
| Afficher sur la carte · Partager | ✅ |
| Outils : Suivre cette trace · Modifier le tracé · Inverser · Simplifier · Fusionner · Décrire | ✅ |
| Télécharger autour de cette trace (rayon) → DOWNLOADS | ✅ |
| Imprimer le long de cette trace… (« Un PDF de plusieurs pages à l'échelle, qui suivent la trace ») → T3b | ✅ `[F9]` (P14.6, session 49) |
| Lignes Début et Fin au format « 22 oct. 2026 à 17:02 » (français, quelle que soit la langue du téléphone) ; ligne Points « 358 · 1 segment » (« 3 segments ») | ✅ (P14.11) |
| Exporter, Renommer, Supprimer | ⚠️ seulement depuis la liste |

## 7. Autres écrans

| Écran | Fonctions | Statut |
|---|---|---|
| SEARCH | géocodage IGN / Nominatim, éléments personnels, coordonnées tapées ; Voir · Aller vers · Enregistrer comme waypoint | ✅ |
| SHARE — ordre (P18.3, session 41) | Hors groupe : une phrase d'explication, **Créer un groupe et inviter**, Rejoindre : scanner un code, Rejoindre : coller un lien, puis les réglages. En groupe : Partager ma position (interrupteur), alerte GPS, cartes d'alerte du téléphone (P21.5), Membres, Inviter · Scanner un code, coller un lien, SOS, Messages, puis les réglages, puis Quitter le groupe. Réglages en cartes **repliées** (titre + résumé, chevron) : « Réglages d'envoi » (résumé « Toutes les 30 s · déplacement minimal : 10 m » ; fréquence, déplacement minimal, STUN), « Ce que les autres voient » (résumé : le nom), « Serveur de coordination » (résumé : serveur intégré / personnalisé / aucun) ; « Tracé des membres » reste un interrupteur visible | ✅ hors groupe sur émulateur ; en groupe à vérifier sur téléphone |
| SHARE | « ? » en haut à droite : texte d'explication (masqué par défaut, 2026-10-14) ; « Ce que les autres voient » : nom, profil (15 jetons : initiales + 14 silhouettes game-icons.net teintées de la couleur choisie, ADR-050 ; ou « Votre image » importée, réduite à 64 × 64 px et 6 Ko ; boutons « Importer une image… » / « Retirer ») et bouton « Couleur de l'icône… » (cercle chromatique + luminosité + palette) ; « Tracé des membres » (interrupteur : tracé + position, ou position seule) ; créer / rejoindre un groupe (QR, lien, serveur) ; cartes d'alerte du téléphone (ligne suivante, ADR-071 ; remplacent la carte « Partage téléphone verrouillé » d'ADR-051) ; membres, messages, SOS, intervalle, serveur de coordination | ✅ ; serveur Firebase non configuré (P13.5 ⛔) |
| DOWNLOADS | formulaire (cartes, zooms, plages des calques), file, pause, reprise, retrait ; liste vide : carte « Aucun téléchargement pour l'instant », une phrase d'explication et le bouton **Sélectionner et enregistrer une zone** (retour à la carte, sélection de zone ouverte, P18.4) ; formulaire : « Tuiles : N, dont M déjà stockées — à télécharger : K (taille) » (P18.6) | ✅ ; ⚠️ la forme « polygone » est décodée et affichée mais **aucun bouton ne permet d'en dessiner un** (seuls rectangle visible et couloir de trace) `[F10]` |
| STORAGE | en tête : total (« 18.9 Mo »), nombre de cartes, Importer un fichier .mbtiles ; puis une ligne par carte, de la plus lourde à la plus légère : nom, taille, « 478 tuiles · zooms 12 à 16 » (carte locale, tuiles partagées), barre de sa part du total, menu ⋮ : Exporter, Effacer les tuiles de plus de 30 j, Tout effacer (ou Supprimer cette carte pour une carte locale) (P18.4) | ✅ ; 🕳️ purge par zone (annoncée en P2.5) |
| MULTI_ZOOM | éditeur de cartes multi-zoom | ✅ |
| BACKUP | sauvegarde / restauration zip | ✅ |
| ABOUT | aide, vie privée, licences | ✅ (`[N3]` corrigé ; aide « Prise en main » à jour, `[V3]`) |
| DIAGNOSTICS | compteurs de l'intercepteur, tuiles de test ; APK debug seulement | ✅ `[N4]` |
| Alertes du partage (P21.5 o, c ; `share/ShareAlerts.kt`, variante B de `docs/previews/p21-alertes.html` validée le 2026-10-24, ADR-071) | réglages du téléphone qui coupent le partage, relus à chaque retour sur SHARE et toutes les 5 s dans un groupe. **Cartes** sous l'interrupteur, une par réglage, dans cet ordre : « Localisation coupée » (rouge, « Aucune position ne part tant qu'elle est coupée. », bouton **Activer la localisation**) · « Économiseur de batterie activé » (orange, « Écran éteint, il coupe le GPS : votre position ne part plus. », **Ouvrir l'économiseur**) · « Batterie de ToolMap optimisée » (orange, « Android peut arrêter le partage en arrière-plan. Choisissez « Sans restriction ». », **Mettre sans restriction** : demande d'exemption d'Android, sinon liste des optimisations, sinon Réglages) ; la carte part dès le retour des réglages. **Dialogue** à l'allumage du partage : « Le partage risque de s'arrêter », « Un (Deux, Trois) réglage(s) du téléphone peu(ven)t couper votre position : » + une puce par réglage · **Partager quand même** · **Régler** (ferme le dialogue sur les cartes) ; le partage tourne dans les deux cas. **Pastille** de la carte « Partage actif · … » en orange avec ⚠ tant qu'une alerte reste. **Notification** : 2e ligne pour le réglage le plus grave (« Localisation coupée : aucune position ne part », « Économiseur de batterie : GPS coupé écran éteint », « Batterie de ToolMap optimisée : Android peut arrêter le partage ») | ✅ émulateur (0.4.68, `docs/previews/p21-alertes-emulateur.html`) ; téléphone à vérifier ; 🕳️ plus de fix GPS, économiseur de données, batterie « restreinte » (aperçu complémentaire non validé) |
| Notification « ToolMap est actif en arrière-plan » (P21.7, `service/BackgroundNotification.kt`, variante B de `docs/previews/p21-notification.html` validée le 2026-10-24) | **une seule** notification tant que le partage, l'enregistrement ou les alertes et le suivi tournent ; une ligne par fonction (« Position partagée · 1 membre connecté », « Enregistrement · 3,2 km · 1 h 05 » ou « Enregistrement en pause · … », « Suivi : nom · 1,2 km restants » ou « Alertes · 2 points surveillés ») ; un bouton par fonction : Arrêter le partage · Arrêter l'enregistrement · Arrêter le suivi ou Arrêter les alertes (pas de Pause : trois boutons au plus) ; appui : ouvre ToolMap ; icône des sommets dans la barre d'état ; canal « Activité en arrière-plan », sans son ni vibration ; part avec la dernière fonction arrêtée. Le téléchargement de cartes garde sa notification de progression, le SOS la sienne | ✅ émulateur ; icône sur le téléphone à vérifier |
| Question « Montrer ToolMap dans la barre d'état ? » (P21.7, `ui/NotificationAsk.kt`) | au lancement du partage (SHARE) ou de l'enregistrement (L4), si les notifications de ToolMap sont refusées ou son canal coupé : « Sans les notifications, Android cache ToolMap : rien n'indique que le partage de position (ou l'enregistrement) continue en arrière-plan. » · **Plus tard** (le partage ou l'enregistrement tourne quand même) · **Autoriser** (demande d'Android ; ouvre les réglages des notifications de ToolMap quand Android ne demande plus) | ✅ émulateur |

## 8. Réglages (`settings/SettingsScreen.kt`) — moteur de chaque réglage

Vérification : pour chacun des 50 réglages (au 2026-10-12), recherche d'un lecteur hors de l'écran Réglages, du modèle et du stockage. Depuis P14.2, « Emplacement de stockage » n'est plus un réglage (ADR-042) : 49 réglages. Depuis P18.1, « Noms sur la carte » : 50 réglages. Depuis P14.6 (session 49), « Mode nuit de la carte » : 51 réglages (son interrupteur est dans le menu principal, M9).

| Catégorie | Réglages | Statut |
|---|---|---|
| Unités et coordonnées (8) | format, format secondaire (boîte Position), distance, surface, cap, pente, pression, nord de référence | ✅ tous lus |
| Affichage (10) | thème, mode nuit de la carte (intensité 20 à 90 %, par pas de 10, défaut 60 %), couleurs dynamiques, gants, menus opaques / gaucher, barre d'état, barre de navigation, par-dessus l'écran verrouillé, écran allumé, confirmer la sortie | ✅ tous lus |
| Carte (8) | résolution, zooms manquants, commandes et pas de zoom, appui long, pincement, rotation, perspective, explorateur automatique | ✅ tous lus |
| Grille et informations (3) | grille, densité et style, style des boîtes. Grille UTM / MGRS (P24.2, variante A de `docs/previews/p24-mgrs.html` validée le 2026-10-26) : chaque zone visible trace sa grille, coupée à sa limite ; limites des zones et des bandes en trait sombre épais, à tous les zooms, pleines même en style pointillé ; traits fins au pas, moyens à 10 km, épais à 100 km ; pas de 100 km au plus ; vue trop large : limites seules, sans puce ni « Zone trop large » ; sous 1 km (UTM), une ligne sur cinq plus épaisse comme avant. Noms (P24.3, ADR-073) : « 31T GL » au-dessus du milieu du carré quand la zone n'en montre qu'un, sinon « 31T » au milieu de la zone et lettres des grands carrés (« GL ») ; UTM : zones seules ; ne cachent jamais le nom d'un waypoint. Boîte Position : ligne « Convergence 2°08′ O » sous la puce de la grille (UTM, MGRS, Lambert 93 en France) | ✅ |
| Repères et traces (7) | taille, couleur par défaut, couleur de cible, flèches / liseré, **Sans données BRouter** (tronçon à la main ou tout l'itinéraire à la main, P19), **noms sur la carte** (waypoints et membres du partage, ADR-062, P18.1), bordures, ombres | ✅ ; ⚠️ « couleur par défaut » ne colore pas les waypoints (leur couleur vient du symbole) |
| Position et cap (7) | cap GNSS, recentrage, positions précédentes, arrière-plan, EGM96, altitude, cap actuel | ✅ |
| Enregistreur (4) | perte de signal, activité, statistiques du segment, tableau de bord | ✅ |
| Alertes (4 + 2) | proximité, écart, vibration, son, rayons | ✅ |
| Stockage et sauvegarde (1 + 1) | Emplacement de stockage : le choix est dans l'écran, il déplace les cartes (confirmation, redémarrage) | ✅ (S1, P14.2, ADR-042) ; carte SD grisée tant qu'elle n'est pas montée |
| Assistant ▸ accès | case « certificats non vérifiés » (`insecure`) retirée ; ToolMap accepte les autorités installées par l'utilisateur dans Android (`network_security_config.xml`) ; un certificat refusé est nommé à l'étape de test : « Certificat du serveur refusé », avec la marche à suivre | ✅ `[S2]` variante C (P14.2, session 49, ADR-066) ; `insecure` d'un `.aqx` importé est gardé pour l'export, jamais appliqué |

## 9. Synthèse des écarts

| ID | Type | Résumé | Gravité |
|---|---|---|---|
| N1 | ✅ | Retour Android quittait l'app depuis 11 écrans : pile de retour (P14.1) | — |
| N5 | ✅ | Retour en dessin / mesure : comme « Quitter », avec confirmation (P14.1) | — |
| N3 | ✅ | ABOUT revient à l'écran d'origine (P14.1) | — |
| N2 | ✅ | STORAGE revient à l'écran d'origine (P14.1) | — |
| N6 | ✅ | Écran verrouillé : Retour bloqué (P14.1) | — |
| N4 | ✅ | Écran de mise au point réservé à l'APK debug (2026-10-15) | — |
| S1 | ✅ | Emplacement de stockage codé : carte SD par déplacement (P14.2, ADR-042) | — |
| S2 | ✅ | Interrupteur `insecure` retiré ; autorités installées par l'utilisateur acceptées (P14.2, ADR-066) | — |
| R2 | ✅ | Section « Espace de travail » retirée du menu ; le favori courant est nommé dans le bloc « Carte affichée » (P14.10) | — |
| R4 | ✅ | Mode de suivi visible : « La carte suit » dans Positionnement (P14.10) | — |
| R3 | ✅ | Un seul parcours de téléchargement de zone (P14.3) | — |
| F1 | ✅ | Menu rapide du point central (P14.5) | — |
| F16 | ✅ | Ouvrir un point avec une autre application : bouton « Ouvrir avec » du menu rapide, application par défaut (P14.8, ADR-044) | — |
| C5 / F2 | 🕳️ | Favoris non modifiables (suppression des favoris fournis faite le 2026-10-14) | moyenne (demande explicite) |
| F3 | ✅ | Cartes fournies masquables du catalogue, avec recherche (F5) et menu ⋮ complété (F4) (P14.4, P14.6) | — |
| F10 | ✅ | Polygone de téléchargement tiré de la mesure de surface (P14.6) | — |
| R5 / F11 | ✅ | Les « ? » des panneaux ouvrent l'aide à leur section, surlignée ; section « Positionnement » ajoutée (P14.3) | — |
| F4 F5 F6 F8 F9 F14 | ✅ | Menu ⋮ des cartes, recherche du catalogue, menu de la boîte Position, Exporter sur la fiche, impression le long d'une trace, mode nuit (P14.6) | — |
| F7 | ✅ | Explorateur : tri, filtre, recherche, sélection multiple (P14.7) | — |
| P14.11 | ✅ | Petits défauts visibles : noms par défaut datés en français (« Zone du 22 oct. 2026 à 17:02 », de même Sortie, Tracé, Itinéraire, Mesure), dossiers gardés ouverts, bande vide sous la barre du haut, cases cochées par toute la ligne (`ui/CheckRow.kt`, 6 endroits), libellés des activités sur deux lignes, ligne Points (aperçu `docs/previews/p14-defauts.html` validé le 2026-10-22) | — |
| G1, F12, F15 | — | Gardés tels quels par décision de l'utilisateur (2026-10-20) : libellé « Couleur par défaut », confirmation des suppressions, pas de bloc des services en cours | — |
| N7 | ✅ | « Masquer les commandes » : « Afficher les commandes de la carte » dans le menu, et Retour (P14.1) — §10 | — |
| N8 | ✅ | Écran verrouillé : l'appui long ne fait plus rien (P14.1) — §10 | — |
| N9 | ✅ | Téléchargements ouvert depuis le détail d'une trace : « Fermer » revient au détail (P14.1) — §10 | — |
| V1 | ✅ | Recherche : la catégorie n'est plus répétée (2026-10-15) — §10 | — |
| V2 | ✅ | Chevauchements corrigés (2026-10-15) : les éléments flottants se poussent (`map/MapChromeLayout.kt`) — §10.6 | — |
| V3 | ✅ | Textes corrigés (2026-10-15) : aide « Prise en main », « aériennes », « zoom forcé », itinéraire nommé « Itinéraire du … » ; reste le « ? » du partage, à revoir avec P13.5 — §10 | basse |

Les corrections et ajouts proposés sont dans `docs/specs/ui-reorganization.md` ; leur ordre et leurs critères dans `docs/PLAN.md` (P14).

## 10. Vérification sur émulateur (2026-10-15)

> Première vérification **en exécution** de cet index (jusqu'ici établi par lecture du code). APK debug 0.4.0 compilé en local, installé sur l'émulateur Android Studio « Medium_Phone » (Android 37, 1080 × 2400, langue du système : anglais), position simulée à Chamonix (`adb emu geo fix`). Chaque bouton a été touché par `adb shell input`, le résultat lu par `uiautomator dump` et des captures d'écran, puis comparé au libellé et au code.

### 10.1 Défauts connus confirmés

| ID | Constat sur l'émulateur |
|---|---|
| N1 | Retour Android **quitte l'app** depuis SETTINGS (catégorie ouverte ou non), AVAILABLE, TRACKS, TRACK_DETAIL, SEARCH (après fermeture du clavier), SHARE, ABOUT, DOWNLOADS, STORAGE ; MULTI_ZOOM (liste) aussi. DIAGNOSTICS revient bien à la carte. |
| N2 | STORAGE ouvert par « Gérer le stockage hors ligne » (Cartes disponibles) : « Fermer » ramène à la carte. |
| N3 | « ? » du menu principal ▸ À propos ▸ « Fermer » : ouvre les **Réglages**. |
| N4 | « Plus… ▸ Diagnostic des tuiles » montre l'écran de mise au point (sans bouton Fermer, avec son propre interrupteur hors ligne). |
| N5 | Dessin (3 points) puis Retour : l'app se ferme, tracé perdu sans question ; même chose en mesure. Le bouton « Quitter » du dessin demande, lui, « Abandonner les modifications ? ». |
| N6 | Écran verrouillé puis Retour : l'app se ferme. |

### 10.2 Défauts nouveaux

| ID | Bouton | Attendu (libellé / code) | Constaté | Cause dans le code |
|---|---|---|---|---|
| N7 | Menu ▸ Masquer les commandes | barre réduite au logo, puis moyen de la rendre | barre et zoom disparaissent ; le logo rouvre le menu, où la ligne dit toujours « Masquer les commandes » et remasque. **Aucun moyen de réafficher** sans arrêter l'app (l'état survit à la rotation) | `MapScreen.kt` : `chromeHidden = true` (`MenuAction.HIDE`) et jamais remis à `false` |
| N8 | Menu ▸ Verrouiller l'écran | « gestes coupés » | déplacement et zoom coupés, mais l'**appui long sur la carte ouvre « Nouveau waypoint »** (le verrou reste actif) | `controller.setGesturesEnabled(!locked)` ne coupe pas `addOnMapLongClickListener` ; `controller.onLongPress` ne teste pas `locked` |
| N9 | Détail d'une trace ▸ Télécharger autour de cette trace ▸ Fermer | retour au détail | retour à la carte | `MainActivity` : `DownloadScreen(onBack = { screen = Screen.MAP })` |
| V1 | Recherche « Chamonix » | « administratif · 74400 » | « administratif · administratif · 74400 » (idem « lieu-dit habité ») | la catégorie est dans `subtitle` (`GeocodeParsers.kt`) **et** ajoutée comme `kind` (`SearchScreen.kt`, `listOfNotNull(kind, result.subtitle)`) |
| V2 | Fond OpenTopoMap | — | l'attribution (2 lignes) couvre le bouton « + » (il marche encore, mais caché) ; le panneau d'enregistrement couvre les boîtes Échelle et GPS ; le bandeau de cible couvre la boîte Échelle ; en sélection de zone, la poignée haut-gauche est sous la boîte Échelle | mise en page des éléments flottants de `MapScreen` |
| V3 | Textes | — | aide « Prise en main » (À propos) périmée : « Calques puis Ajouter une carte », « Télécharger la zone affichée » (retiré, R3), « Enregistrer une trace », « Réglages puis Sauvegarde et restauration » (c'est « Stockage et sauvegarde ▸ Sauvegarde et restauration… »), « Les positions voyagent directement de téléphone à téléphone » (désormais un serveur Firebase) ; le « ? » du partage annonce Firebase alors qu'aucun serveur n'est configuré (liaison directe) ; catalogue : « IGN Photographies aeriennes », « IGN Topo 25k (zoom force) » (`builtin-sources.aqx`) ; un itinéraire dessiné s'appelle par défaut « Tracé du … » (`draw_default_name`) | `strings.xml` `about_start_*`, `builtin-sources.aqx` |

### 10.3 Remarques (comportement discutable, pas un défaut franc)

- Cartes disponibles ▸ ⋮ ▸ « Afficher comme fond de carte » : la carte change, mais on reste sur le catalogue.
- Cartes affichées : « Ne plus afficher » est permis sur la dernière carte (carte grise vide) ; « Monter », « Descendre », « Ne plus afficher les autres » restent actifs avec une seule carte.
- Enregistreur : deux dialogues de suite (réglage Marche / Vélo / Précis / Économie, puis activité : grille de 4 colonnes des 32 activités d'AlpineQuest et Autre, chacune avec son icône colorée, P16.6 ; libellés sur deux lignes au plus depuis P14.11).
- Statistiques : le panneau en direct affichait « 1 min », le détail « Durée totale 0 min » pour la même trace (arrondis différents).
- Nouvelle carte multi-zoom (depuis le catalogue) ▸ Annuler : ramène à la liste des cartes multi-zoom, pas au catalogue.
- Les dates des noms par défaut suivent la langue du téléphone (« Sortie du Oct 15, 2026 4:02 PM » sur un téléphone en anglais). Corrigé en P14.11 (2026-10-22) : « Sortie du 22 oct. 2026 à 17:02 » dans toutes les langues.
- « Ouvrir avec » (point central) : sur l'émulateur, une seule application de cartes, ouverte directement sans sélecteur malgré « Demander à chaque fois ».
- Le « ? » de l'écran de partage est un texte « ? » sans description pour TalkBack ; le bouton Ma position garde la même description quel que soit le mode de suivi (`[R4]`).

### 10.4 Vérifiés conformes

Barre du bas (Menu, Cartes, Drapeau, Ma position, Boussole qui remet le nord et disparaît, + / −, échange de fond) ; menu principal (Imprimer : panneau, Réduire / Agrandir, Retour le ferme ; Réglages ; Plus… ; Informations en haut à gauche) ; Plus… ▸ Mesurer ; panneau Cartes et calques (Cartes disponibles, Cartes affichées et son menu ⋮, Sélectionner et enregistrer une zone et son Retour, favoris : appui, signet « Nouveau favori », appui long ▸ Supprimer pour un favori créé et pour un fourni, Options ▸ Rétablir, Mode hors ligne, Téléchargements, Stockage hors ligne, « ? ») ; Cartes disponibles (groupes, ⋮, Ajouter une carte ▸ Nouvelle carte… ▸ assistant dont Retour revient au catalogue, Nouvelle carte multi-zoom) ; panneau du drapeau (Explorer, Ajouter un waypoint ici, Dessiner une trace / un itinéraire avec Annuler, Rétablir, Outils, Enregistrer, Quitter avec confirmation ; Rechercher) ; Positionnement (Position en temps réel, Mode explorateur avec inclinaison et boussole, Enregistreur : démarrage, statistiques, Arrêter, Partage de position… ▸ écran) ; point central (Définir comme cible + bandeau distance / cap + Annuler la cible, Créer un waypoint…, Mesurer depuis ici, Coordonnées ▸ dialogue Copier, Partager ce point, Ouvrir avec) ; fiche d'une trace (Statistiques ▸ détail ▸ Fermer vers la carte, Modifier le tracé) et d'un waypoint (Aller vers ce point, Alerte de proximité) ; Traces et waypoints (⋮ Statistiques, Afficher sur la carte, Partager ▸ fichier .gpx, Supprimer avec confirmation) ; détail d'une trace (Fermer vers la liste, Télécharger autour ▸ formulaire prérempli) ; Réglages (9 catégories, « Rétablir les réglages par défaut », Cartes multi-zoom… ▸ Fermer vers les Réglages, Sauvegarde et restauration… ▸ Fermer vers les Réglages, Fermer vers la carte) ; recherche (résultats IGN, Actions ▸ Voir sur la carte) ; partage (« ? », jetons, Créer un groupe et inviter ▸ QR, Annuler, Fermer vers la carte).

### 10.5 Corrections vérifiées (2026-10-15, session 28)

APK debug 0.4.0 recompilé et réinstallé sur le même émulateur. Chaque parcours a été refait :

| ID | Parcours | Résultat |
|---|---|---|
| N1 | Retour depuis Réglages, Recherche (après le clavier), Partage, Traces, détail d'une trace, Cartes disponibles, Stockage, Téléchargements, À propos, Sauvegarde, liste multi-zoom | revient à l'écran d'origine ; l'app ne se ferme plus |
| N2 | Cartes disponibles ▸ Gérer le stockage hors ligne ▸ Fermer, puis Retour | revient au catalogue les deux fois |
| N3 | « ? » du menu ▸ Fermer, et ▸ Retour (trois fois de suite) | revient à la carte |
| N5 | Dessin avec un point ▸ Retour | « Abandonner les modifications ? » ; Annuler garde le dessin, Abandonner le ferme. Mesure ▸ Retour : ferme la mesure |
| N6 | Verrouiller ▸ Retour | l'app reste ouverte et verrouillée |
| N7 | Masquer ▸ logo ▸ « Afficher les commandes de la carte » ; Masquer ▸ Retour | la barre revient |
| N8 | Verrouiller ▸ appui long sur la carte | rien ; après déverrouillage, l'appui long ouvre bien « Nouveau waypoint » |
| N9 | Détail d'une trace ▸ Télécharger autour (500 m) ▸ Fermer, Retour, Retour | détail de la trace, puis la liste, puis la carte |
| — | Cartes disponibles ▸ Ajouter une carte ▸ Nouvelle carte multi-zoom ▸ Retour ou Annuler ; ▸ Nouvelle carte… ▸ Retour | revient au catalogue |
| V1 | Recherche « Chamonix » | « mairie · 74400 Chamonix-Mont-Blanc », « administratif · 74400 » |
| V3 | Catalogue ; itinéraire dessiné ▸ Enregistrer | « IGN Photographies aériennes » ; nom proposé « Itinéraire du … » |

Restent : N4 (écran de diagnostic exposé), V2 (chevauchements), le « ? » du partage (Firebase non configuré, P13.5) et les remarques de §10.3.

### 10.6 Chevauchements et diagnostic (2026-10-15, session 29)

**Règle de mise en page des éléments flottants de la carte** (`map/MapChromeLayout.kt`, `ChromePlacement`) :

- **En haut** : les boîtes d'information restent dans le coin. Les panneaux (impression, partage, enregistrement, cible, suivi) se placent **à côté** des boîtes quand ils tiennent dans la largeur restante, centrés sur l'écran si possible (paysage, tablette). Sinon ils passent **en dessous** (portrait).
- **Coin haut droit** (P29.1) : la pastille d'enregistrement reste sur la ligne des pastilles d'information quand les deux tiennent, sinon elle passe dessous, à droite. Les panneaux (cible, suivi, partage) se placent alors entre les deux sur un écran large, sinon sous les deux.
- **En bas** : la barre garde le coin gauche. Les boutons de droite (zoom, échange de fond) se placent à côté d'elle, ou **au-dessus** si la ligne est trop étroite. L'attribution passe à la ligne **avant** ces boutons.
- **Sélection de zone** : le rectangle de départ est pris dans la partie de l'écran que les boîtes laissent libre (en dessous, ou à droite si c'est plus grand). Les poignées sont dessinées **au-dessus** des boîtes et des panneaux : un coin amené sous une boîte reste saisissable.

| ID | Parcours (APK debug 0.4.0, émulateur 1080 × 2400) | Résultat |
|---|---|---|
| V2 | Fond OpenTopoMap | avant : l'attribution allait jusqu'à x = 1032, sur « Zoomer » (x = 954). Après : elle s'arrête à x = 885 et tient sur 2 lignes |
| V2 | Enregistreur démarré, cible définie, position en temps réel (boîtes GPS et baromètre) | avant : panneau sur la boîte Échelle (y = 211, boîte jusqu'à 249). Après : le panneau commence sous la dernière boîte, le bandeau de cible sous le panneau |
| V2 | Même état, en paysage (2400 × 1080) | panneaux à côté des boîtes, centrés ; attribution sur une ligne |
| V2 | Cartes ▸ Sélectionner et enregistrer une zone | poignées du haut à y = 455–581, sous les boîtes (jusqu'à 290) ; une poignée glissée sous la boîte Échelle puis ressortie suit le doigt |
| N4 | Menu ▸ Plus… (APK debug) | « Cartes hors ligne ▸ Diagnostic des tuiles » présent ; absent hors debug (`MainMenuPanelTest`) |

Restent : le « ? » du partage (Firebase non configuré, P13.5) et les remarques de §10.3.
