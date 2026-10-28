# Plan des fonctions demandées le 2026-10-24 (P22 à P28)

> Décisions prises avec l'utilisateur le 2026-10-24 (session 55, questions Q1 à Q63, récapitulatif validé : « Oui »).
> Règles : aperçu validé avant tout code d'interface (`CLAUDE.md`) ; **petits incréments, un build par tâche**
> (demande du 2026-10-24). Les idées pour plus tard sont dans `docs/idees-futur.md`, lu seulement sur demande.
>
> **Contexte** : les fonctions de groupe se conçoivent et se testent d'abord pour une station de ski (réseau mobile
> présent, favori « Sortie Ski ») ; la montagne sans réseau viendra plus tard.

## Ordre

| Phase | Sujet | Dépend de |
|---|---|---|
| P22 | Alerte et écoute du groupe | — (P21 clos le 2026-10-24) |
| P23 | Saisie précise des valeurs | — |
| P24 | Grilles MGRS et UTM | images de l'utilisateur (non reçues en session 55) |
| P25 | Partage des favoris | P27 pour l'envoi au groupe (P25.4) |
| P26 | Couleurs et modes de fusion | — |
| P27 | Waypoints du groupe | P22 (écoute), P23 (durée saisie) |
| P28 | Dessin | P23 (épaisseur), P26 (roue chromatique) ; P27 pour l'envoi au groupe (P28.9) |

Chaque phase commence par son aperçu (`.0`, actuel et proposé), validé par l'utilisateur avant le code, et finit par
les captures de l'émulateur à côté de l'aperçu.

---

## P22 — Alerte et écoute du groupe

**Existant** : SOS (`Wire` type 8) répété toutes les 15 s (`MeshNode.tick`) jusqu'à « Arrêter le SOS », qui ne fait
qu'arrêter la répétition locale (`ShareController.stopSos` → `MeshNode.stopSos`, rien n'est envoyé) ; chez les autres,
l'alerte expire 120 s après la dernière répétition (`SOS_STALE_MS`). Notification `sharing-sos` (importance haute, son
du canal) postée seulement pendant `ShareService`. Hors partage, Android bloque le réseau de l'appli en arrière-plan.

**Décisions**
- « SOS » → « Alerte » dans toute l'interface ; titres complets « au groupe » (« Envoyer une alerte au groupe »,
  « Alerte de %1$s », canal « Alertes du groupe ») pour ne pas confondre avec les alertes de proximité et les alertes du
  partage (ADR-071). Phrase « Cela ne remplace pas les secours (112) » retirée. Type `SOS = 8` gardé dans le protocole.
- Message libre facultatif, sans motifs tout prêts.
- Répétition automatique pendant 2 min ; chez les autres, l'alerte reste jusqu'à « Vu ».
- « Arrêter le SOS » retiré. « Annuler l'alerte » (auteur seul) envoie une levée : chez les autres, l'alerte et sa
  notification sont remplacées par « Alerte levée par %1$s », qui s'efface seule. Compatible avec les versions
  précédentes (elles ignorent la levée).
- Rien de forcé : dialogue, bandeau, notification prioritaire avec le son du canal ; silencieux et Ne pas déranger
  respectés.
- **Écoute permanente** : tant que l'appareil est dans un groupe, ToolMap reste connecté (écran éteint compris) et
  reçoit positions, messages, alertes, points et favoris, que « Partager ma position » soit allumé ou non (exemple :
  la permanence d'un refuge). Pas d'interrupteur : pour ne plus recevoir, quitter le groupe. Ligne « Groupe « %1$s » :
  à l'écoute » dans la notification unique (P21.7), **sans bouton d'arrêt** ; la toucher ouvre Partage. Les autres
  voient ce membre « À l'écoute » (icône « En ligne », pas de repère sur la carte).
- ADR : type du service de premier plan de l'écoute (Android 15 limite `dataSync` à 6 h par 24 h ; candidats
  `remoteMessaging`, `specialUse`) et coût batterie mesuré.

| ID | Statut | Tâche | Critères d'acceptation |
|---|---|---|---|
| P22.0 | ⬜ | Aperçu : bouton et dialogue Alerte, bandeau, « Annuler l'alerte », « Alerte levée », ligne « à l'écoute » de la notification, « À l'écoute » dans la liste des membres | Validation explicite, date au journal |
| P22.1 | ⬜ | Textes : SOS → Alerte, retrait du 112 et de « Arrêter le SOS » | Plus aucun « SOS » visible (test de rendu) |
| P22.2 | ⬜ | Répétition de 2 min et levée de l'alerte (codec, `MeshNode`, `ShareController`) | `WireTest`, `MeshNodeTest` : levée reçue, alerte retirée, ancienne version sans erreur |
| P22.3 | ⬜ | Écoute permanente : service (ADR), réception hors partage, ligne de notification, état « À l'écoute » | `ShareControllerTest` ; émulateur : partage coupé, appli en arrière-plan, alerte et message reçus |
| P22.4 | ⬜ | Vérification sur téléphone | Alerte reçue écran éteint sans partager ; batterie de l'écoute sur 1 h consignée |

---

## P23 — Saisie précise des valeurs

**Existant** : `RangeBar` ; zoom 0–20 (`ZoomBar.MAX`), altitude 0–4800 m par 50 m (`ElevationBar`), bouts de barre
= sans limite ; `LayerStack.setZoomRange` borne à 0–20, `setElevationRange` ne borne pas. **Défaut** : une valeur hors
barre devient « sans limite » dès qu'on touche la barre (`toBounds`). Partage : `PresetSlider` sur
`ShareCadence.PRESETS_S` (5 s → 30 min) et `MOVE_PRESETS_M` (0 → 100 m). Opacité et couleurs : `LabeledSlider`. Aucun
composant de saisie commun (`NumberField` privé de `PrintPanel`).

**Décisions**
- Composant commun « valeur touchable » : on touche le chiffre, une petite fenêtre s'ouvre avec le clavier numérique.
  Pour le zoom et l'altitude (Conditions d'affichage), l'intervalle et la distance (partage), l'opacité et les couleurs.
- Bornes : zoom 0 à 24, entier ; altitude −500 à 9000 m, au mètre ; intervalle 1 s à 24 h, avertissement sous 5 s
  (batterie, serveur) ; distance 0 à 10 000 m, au mètre.
- Valeur hors barre : barre en butée, valeur saisie affichée ; bouger la barre la remplace.

| ID | Statut | Tâche | Critères d'acceptation |
|---|---|---|---|
| P23.0 | ⬜ | Aperçu : valeur touchable, fenêtre de saisie, barre en butée | Validation explicite, date au journal |
| P23.1 | ⬜ | `:core` : bornes, `setZoomRange` 0–24, intervalle et distance libres (`ShareCadence`, réglages du partage) | Tests JVM des bornes et des valeurs hors liste |
| P23.2 | ⬜ | Composant + Conditions d'affichage (zoom, altitude) ; correction de la perte de valeur | Test : valeur hors barre gardée après un toucher de la barre |
| P23.3 | ⬜ | Partage : intervalle et distance, avertissement sous 5 s | `ShareControllerTest` (45 s, 7 m) |
| P23.4 | ⬜ | Opacité et couleurs | Tests de rendu |

---

## P24 — Grilles MGRS et UTM

**Existant** : à l'écran (`ScreenGrid.plan`, `ScreenGrid.kt:111`) comme à l'impression (`GridPlanner`), grille de la
**zone du centre prolongée** sur tout l'écran ou la feuille : aucune limite de zone tracée, zone voisine jamais
dessinée, saut quand le centre change de zone. Lignes de 100 km absentes quand le pas passe à 200 ou 500 km
(`niceStep`), plus de grille au-delà de 40 lignes (`TOO_WIDE`), ni de part et d'autre de l'équateur (fausse ordonnée
de `Utm.toUtm`). Exceptions Norvège et Svalbard non appliquées (`Utm.kt:69`). Pas de texte à l'écran (ADR-022).
Impression : lettres de carré fausses au-delà de la limite (`Mgrs.kt:62`), note « Quadrillage de la zone prolongé ».
Convergence calculée pour le cartouche (`GridPlanner.convergenceDegrees`), jamais affichée à l'écran.

**Référence** : ~~mgrs-data.org~~ (fichiers à 100 m de 165 à 456 Mo par zone, inutilisables) remplacé en session 57
par GeoConvert (GeographicLib) : `fixtures/mgrs/geoconvert-junctions.txt`. **Images de l'utilisateur** reçues le
2026-10-25 (`docs/previews/p24-images/`) : à Pontcharra, la grille entière tourne d'environ 4° quand le centre passe
6° E (31T → 32T) ; au dézoom de 5 à 10 km, la ligne proche du centre disparaît.

**Décisions**
- Écran : chaque zone visible dessine sa grille, coupée à sa limite (méridiens de 6°, parallèles des bandes de 8°,
  exceptions comprises) ; limite de zone en trait épais à tous les zooms dès qu'une grille MGRS ou UTM est active ;
  la jonction en biais montre la convergence.
- Lignes de 100 km dès que le pas est de 100 km ou moins ; vue trop large : limites de zones seules au lieu de
  « grille trop large ».
- Noms de zone (« 31T ») et de carré (« FK ») discrets au milieu des carrés visibles (glyphes d'ADR-062) ; nouvel ADR
  qui remplace ADR-022 sur le texte de la grille.
- Convergence affichée dans la boîte de position, à côté de la puce de grille (UTM, MGRS, Lambert 93) ; cartouche
  imprimé inchangé.
- Impression à cheval sur deux zones : chaque zone de son côté, limite épaisse, chiffres des bords de chaque zone de
  son côté, lettres de carré justes des deux côtés, encadré de référence citant les deux zones, graduations de la zone
  voisine dans la marge (pratique OTAN) ; remplace la note « prolongé ».
- UTM : même traitement que MGRS.
- Exactitude : exceptions 32V et 31X–37X, passage de l'équateur, tests contre un extrait de mgrs-data.org aux
  jonctions de zones et de carrés.

| ID | Statut | Tâche | Critères d'acceptation |
|---|---|---|---|
| P24.0 | ✅ | Comparer aux images de l'utilisateur ; aperçu de l'écran (jonction, noms, convergence) et d'une feuille à cheval. Aperçu montré en session 57 (`docs/previews/p24-mgrs.html`), **validé le 2026-10-26 : variante A** au dézoom (lignes de 10 et 100 km plus marquées) | Validation explicite, date au journal ✅ (session 59) |
| P24.1 | ✅ | `:core` : exceptions Norvège et Svalbard, équateur, polygones de zone ; fixture ~~extraite de mgrs-data.org~~ GeoConvert (`fixtures/mgrs/geoconvert-junctions.txt`). Fait (sessions 57 et 58) : `Utm.zoneOf(lat, lon)`, `toUtm(north =)`, `convergenceDegrees`, `GridZones` ; `GridPlanner` et `ScreenGrid` sur la zone exacte et un seul hémisphère | Tests JVM contre la fixture (identifiants et limites aux jonctions) : `MgrsJunctionsTest`, `GridZonesTest` ✅ |
| P24.2 | ✅ | `ScreenGrid` : grilles par zone, limites, règles de zoom. Fait (session 59) : chaque zone (bandes jointes) trace sa grille coupée à sa limite ; limites des zones et des bandes (`GridLineLevel.ZONE_EDGE`, calque `tm-g-grid-edge-layer`, toujours plein) ; niveaux du trait : pas, 10 km, 100 km ; pas plafonné à 100 km ; vue trop large : limites seules ; UTM sous 1 km inchangé (une ligne sur cinq) ; antiméridien et équateur | `ScreenGridTest` : deux zones à l'écran, limite présente à tous les pas ✅ |
| P24.3 | ✅ | Noms et convergence à l'écran (ADR). Fait (session 60) : `ScreenGridPlan.labelsGeoJson` (« 31T GL », « 31T », « GL » selon la règle de l'aperçu ; UTM : zones seules), calque `tm-g-grid-labels-layer` ; convergence sous la puce de la boîte Position ; ADR-073 | Test de rendu ; émulateur à une jonction (31T / 32T) ✅ |
| P24.4 | 🔄 | Impression à cheval (`GridPlanner`, `PageComposer`). Codé (session 61) : grille de chaque zone de son côté, limite épaisse et « 6° E », carrés « 31T GL » / « 32T KR », chiffres de la zone voisine en bleu dans une rangée extérieure, cartouche des deux zones ; découpage partagé avec l’écran (`GridZones.pieces`). PDF créé par l’application sur l’émulateur conforme (session 62). **Reste** : PDF réel relu et validé par l’utilisateur | `GridAtlasTest` ✅ ; PDF A4 OTAN / MGRS à cheval relu et validé par l’utilisateur |
| P24.5 | ⬜ | Captures et PDF à côté de l'aperçu | Validés par l'utilisateur |
| P24.6 | ✅ | Grille UTM reconnaissable (remarque du 2026-10-28 : « seule la grille MGRS s'affiche, même si j'utilise UTM ») : valeur en km de chaque ligne du kilomètre au bord de l'écran (abscisses en bas, ordonnées à gauche, deux derniers chiffres en gras), nom « 31T » retiré en UTM ; variante A de `docs/previews/p24-utm-reperes.html` validée le 2026-10-28 (ADR-086) | `ScreenGridTest` (lignes du kilomètre, coupe des chiffres, rien de près) ; émulateur au 1:85 000, 1:21 000, 1:10 600 et 1:2 650 : `docs/previews/p24-utm-reperes-emulateur.html` ✅ |
| P24.7 | ✅ | Feuille imprimée dans la grille choisie seule (remarque du 2026-10-28 : « des valeurs WGS84 et un combo UTM / MGRS en bordure de carte ») : cadre gradué au pas de la grille, coins en degrés seulement pour une grille en degrés, MGRS sans les centaines de km ni « 000 m », encadré OTAN dans la grille de la feuille (ADR-087) | `GridAtlasTest`, `PrintExportTest` ✅ ; un PDF par grille créé sur l’émulateur, validés par l’utilisateur le 2026-10-28 (« Oui pour les grilles ») ✅ |

---

## P25 — Partage des favoris

**Existant** : un JSON par favori (`WorkspaceStore`, `WorkspaceLayer` : cartes, ordre, opacité, couleurs, zooms,
altitudes, ET / OU) ; aucun export ; cartes absentes ignorées à l'application (« Cartes indisponibles ignorées ») ;
export AQX d'une carte avec clés retirées (`SourceRepository.export`) ; appui long sur un favori = supprimer.

**Décisions**
- Fichier `Nom du favori.toolmap` (JSON versionné : le favori et l'AQX des cartes non fournies), envoyé par le
  partage Android, ouvert par « Ouvrir avec » ou par l'import des favoris. Pas de QR ni de lien.
- À chaque partage, choix de garder ou de retirer les clés API (avertissement si elles sont gardées).
- Import : cartes déjà présentes réutilisées (même jeu de tuiles, ADR-031), manquantes ajoutées, clé demandée si
  besoin ; nom en double « (2) » ; « Appliquer maintenant » ; carte fournie masquée remise dans la liste, avec un
  message.
- « Renommer » un favori.
- Envoi au groupe (après P27) : « %1$s partage le favori « %2$s » » avec « Ajouter » ; rien d'ajouté ni d'appliqué
  sans le destinataire.

| ID | Statut | Tâche | Critères d'acceptation |
|---|---|---|---|
| P25.0 | ⬜ | Aperçu : commandes d'un favori (Partager, Renommer), choix des clés, import, message du groupe | Validation explicite, date au journal |
| P25.1 | ⬜ | `:core` : format `.toolmap` (versions, AQX inclus, clés) | Tests JVM d'aller-retour, clés retirées et gardées |
| P25.2 | ⬜ | Export (partage Android) et Renommer | Émulateur : fichier produit, favori renommé |
| P25.3 | ⬜ | Import (« Ouvrir avec », cartes, clés, cartes masquées) | Tests ; émulateur : import d'un favori avec une carte ajoutée absente |
| P25.4 | ⬜ | Envoi au groupe (après P27) : trame chiffrée (au-delà de 4096 octets : compression ou découpage), « Ajouter » | `MeshNodeTest` ; deux émulateurs |

---

## P26 — Couleurs et modes de fusion

**Existant** : MapLibre 13.6.1 : opacité, contraste, luminosité, saturation (`StyleApplier`) ; `raster-hue-rotate`
non utilisé ; aucun mode de fusion ; dialogue « Modifier les couleurs » (`ColorsDialog`) ; impression par
`RasterAdjust.colorMatrix` ; l'intercepteur sait déjà traiter les pixels d'une tuile (masque d'altitude, P20).

**Décisions**
- Fusion calculée dans l'intercepteur : une tuile composée des calques concernés, formules standard (W3C
  Compositing) écrites dans `:core`, les mêmes pour l'impression. Un mode mélange la carte avec **tout ce qui est
  dessous**.
- 16 modes, nom français puis anglais en gris : Normal · Produit (Multiply) · Superposition (Screen) · Incrustation
  (Overlay) · Obscurcir (Darken) · Éclaircir (Lighten) · Densité couleur − (Color dodge) · Densité couleur + (Color
  burn) · Lumière crue (Hard light) · Lumière tamisée (Soft light) · Différence · Exclusion · Teinte (Hue) ·
  Saturation · Couleur (Color) · Luminosité (Luminosity).
- Teinte d'une couleur choisie avec son intensité (intercepteur) et rotation des couleurs (MapLibre, en direct) ; roue
  chromatique commune avec le dessin (P28).
- Panneau « Couleurs et fusion » en bas de la carte, comme « Conditions d'affichage », qui remplace `ColorsDialog` :
  mode, teinte, rotation, contraste, luminosité, saturation, inversion. Fusion et teinte au relâcher (redessin depuis
  le stockage, comme P20), le reste en direct.
- Fusion et teinte dans le PDF et dans les favoris (`WorkspaceLayer`, format de P25).
- La mesure du coût vient d'abord : si elle est trop lourde, arrêt et retour vers l'utilisateur avant l'aperçu.

| ID | Statut | Tâche | Critères d'acceptation |
|---|---|---|---|
| P26.0 | ⬜ | Faisabilité et coût (APK benchmark, émulateur) : 2 à 3 calques composés dans l'intercepteur, ms par tuile, redessin en ligne et en mode avion ; ADR | Mesures au journal et dans une spec |
| P26.1 | ⬜ | Aperçu du panneau « Couleurs et fusion » | Validation explicite, date au journal |
| P26.2 | ⬜ | `:core` : formules des 16 modes, teinte | Tests JVM sur valeurs de référence |
| P26.3 | ⬜ | Modèle (`LayerConfig`, `WorkspaceLayer`), `StackToStyle`, intercepteur | `StackToStyleTest`, test de l'intercepteur |
| P26.4 | ⬜ | Panneau et roue chromatique | Tests de rendu ; captures à côté de l'aperçu |
| P26.5 | ⬜ | Impression | PDF relu (un mode Produit visible) |

---

## P27 — Waypoints du groupe

**Existant** : aucun partage de point au groupe (`MSG` : texte de 140 caractères) ; « Partager ce point » ouvre la
feuille de partage d'Android ; pas de dissolution (« Quitter le groupe » seulement). Serveur :
`rooms/<pièce>/b/m<id>/{h,p,c}`, `c` effacé au bout de 120 s ; règles `firebase/database.rules.json` validées nœud par
nœud.

**Décisions**
- Un point du groupe n'existe que dans le groupe (pas dans « Traces et waypoints ») ; chaque membre le voit sur la
  carte et dans le groupe, peut le prendre comme cible (« Aller vers ») et le garder dans ses waypoints ; on peut
  aussi **envoyer un de ses waypoints au groupe**.
- Durées : 30 min, 1 h, 2 h, 4 h, 12 h, 24 h, saisie libre (P23) ou « Tant que le groupe existe » (jusqu'à son retrait
  ou jusqu'à ce que le groupe se vide ; pas de dissolution ajoutée).
- Tout membre crée et retire (le retrait dit qui l'a fait) ; l'auteur déplace, renomme et prolonge ; mises à jour
  silencieuses.
- Notification choisie à la création : « Silencieux » (repère et ligne dans le groupe) ou « Alerte » (traitement de
  l'Alerte, P22) ; un membre qui arrive plus tard reçoit les points sans sonnerie.
- Entrées : menu du point central (« Point au groupe ici »), fiche d'un waypoint (« Envoyer au groupe », dans un groupe
  seulement), liste « Points du groupe » de l'écran Partage (créer, voir, retirer, garder, prolonger).
- Symbole choisi dans le catalogue des 313 icônes, marque « groupe » à la couleur de l'auteur, durée restante
  (« encore 42 min »).
- Mode direct aussi : les points passent par le maillage et chaque membre les redonne aux arrivants. Serveur : nouvel
  emplacement persistant ; **l'utilisateur republie les règles Firebase** dans sa console.

| ID | Statut | Tâche | Critères d'acceptation |
|---|---|---|---|
| P27.0 | ⬜ | Aperçu : création (nom, symbole, durée, Silencieux / Alerte), repère, fiche, liste « Points du groupe », entrées | Validation explicite, date au journal |
| P27.1 | ⬜ | Protocole : création, mise à jour, retrait, expiration ; rejeu aux arrivants ; emplacement serveur et règles | `WireTest`, `MeshNodeTest` ; émulateur Firebase (`scripts/firebase-emulator-test.sh`) |
| P27.2 | ⬜ | Contrôleur et stockage local (durées, « tant que le groupe existe ») | `ShareControllerTest` |
| P27.3 | ⬜ | Interface : carte, fiche, liste, création, notifications | Captures à côté de l'aperçu |
| P27.4 | ⬜ | Garder dans mes waypoints ; envoyer un waypoint au groupe | Test du dépôt des waypoints |
| P27.5 | ⬜ | Vérification sur téléphone (contexte station de ski), règles republiées | Point reçu par un membre qui écoute sans partager |

---

## P28 — Dessin

**Existant** : main levée du mode dessin (`FreehandLayer`, `Freehand.thin` : Douglas-Peucker à 4 dp) qui produit une
trace ou un itinéraire ordinaire ; la carte ne bouge pas pendant le tracé ; aucun lissage, aucun SVG, aucun objet
« dessin ».

**Décisions**
- **Espace « Dessin » à part**, avec son propre bouton et sa propre liste (emplacement du bouton choisi sur l'aperçu) ;
  un dessin = un ensemble de traits et de formes avec un nom (afficher ou masquer, renommer, supprimer).
- Stylo : un doigt dessine, deux doigts déplacent et zooment la carte.
- Lissage : Douglas-Peucker puis courbes de Bézier (méthode de Schneider), angles nets gardés ; « Lissage : faible /
  moyen / fort », moyen par défaut. Le même lissage s'applique à la main levée des traces.
- Outils : roue chromatique, épaisseur au curseur (1 à 40 px telle qu'on la voit au moment de dessiner, enregistrée
  en mètres sur le terrain : le trait s'affine en dézoomant comme une carte papier, jamais sous 1 px, sans limite en
  zoomant), surligneur, flèche au bout d'un trait, gomme par élément, annuler et rétablir. Pas de texte.
- Formes : trait droit et flèche (glisser du début à la fin), cercle (glisser depuis le centre), rectangle et triangle
  (glisser d'un coin à l'autre, puis poignées pour ajuster et tourner), zone point par point (« Terminer » ou toucher
  le premier point) ; interrupteur « Remplir » (couleur à 25 %), éteint par défaut.
- Sélection d'un élément : supprimer, changer couleur, épaisseur, remplissage, déplacer, redimensionner une forme
  (poignées) ; un trait libre ne se redessine pas.
- Fiche d'un élément : mesures réelles (longueur, rayon, surface).
- Enregistré en chemin SVG en coordonnées de carte ; export GeoJSON (courbes en lignes, couleur, épaisseur) et SVG ;
  compris dans la sauvegarde (`backup-format.md`) ; imprimé en vectoriel dans le PDF. Pas de GPX.
- Envoi au groupe en seconde étape, après P27 : même mécanisme que les points du groupe, sans modification par les
  autres.

| ID | Statut | Tâche | Critères d'acceptation |
|---|---|---|---|
| P28.0 | ⬜ | Aperçu : bouton d'accès, espace « Dessin », barre d'outils, formes, sélection et poignées, fiche | Validation explicite, date au journal |
| P28.1 | ⬜ | `:core` : lissage, modèle (traits, formes, style, épaisseur en mètres), chemin SVG, échantillonnage, mesures | Tests JVM (lissage, aller-retour SVG, surfaces) |
| P28.2 | ⬜ | Stockage (Room, migration), sauvegarde, export GeoJSON et SVG | Test de migration ; aller-retour de sauvegarde |
| P28.3 | ⬜ | Mode stylo : gestes, traits lissés, épaisseur en mètres sur MapLibre (au moins 1 px) | Émulateur : trait lissé, carte déplacée à deux doigts |
| P28.4 | ⬜ | Formes et « Remplir » | Tests du modèle ; captures |
| P28.5 | ⬜ | Sélection, modification, poignées, gomme, annuler et rétablir, fiche et mesures | Tests ; captures |
| P28.6 | ⬜ | Espace « Dessin » : liste, afficher et masquer, renommer, supprimer | Test de rendu |
| P28.7 | ⬜ | Lissage de la main levée des traces | `PolylineEditor` : test du lissage |
| P28.8 | ⬜ | Impression vectorielle | PDF relu |
| P28.9 | ⬜ | Envoi au groupe (après P27) | `MeshNodeTest` ; deux émulateurs |
| P28.10 | ⬜ | Captures à côté de l'aperçu | Validées par l'utilisateur |
