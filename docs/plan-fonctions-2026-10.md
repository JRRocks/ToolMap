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
| P22.0 | ✅ | Aperçu : bouton et dialogue Alerte, bandeau, « Annuler l'alerte », « Alerte levée », ligne « à l'écoute » de la notification, « À l'écoute » dans la liste des membres. Montré le 2026-10-28 (session 75) : `docs/previews/p22-alerte.html` (état actuel, variantes A Balise, B Feuille, C Compte à rebours, D Bandeau de carte, E Plein écran). **Validé le 2026-10-28** : « B pour la page "Envoi", C pour le reste » ; nom du groupe sans réponse, texte de l'aperçu gardé (« Groupe · 4 membres : à l'écoute ») | Validation explicite, date au journal |
| P22.1 | ✅ | Textes : SOS → Alerte, retrait du 112 et de « Arrêter le SOS ». Fait le 2026-10-28 (session 75) avec les écrans de l'aperçu validé (`share/GroupAlert.kt` : feuille d'envoi à maintenir, « Alerte en cours » et « Annuler l'alerte » confirmé, « Alerte levée ») ; capture de l'émulateur à faire sur le poste Windows | Plus aucun « SOS » visible (test de rendu) : `GroupAlertTest` |
| P22.2 | ✅ | Répétition de 2 min et levée de l'alerte (codec, `MeshNode`, `ShareController`). Fait le 2026-10-28 (session 75, ADR-089) : type `SOS_END` (14), 9 envois sur 2 min pour l'alerte et pour sa levée, le plus grand numéro l'emporte, alerte non vue gardée, `ShareUi.sosLifted` 1 min ; l'écran garde ses textes jusqu'à P22.1 | `WireTest`, `MeshNodeTest` : levée reçue, alerte retirée, ancienne version sans erreur |
| P22.3 | 🔄 | Écoute permanente : service (ADR), réception hors partage, ligne de notification, état « À l'écoute ». Codé le 2026-10-28 (session 75, ADR-090) : `ShareService` tant qu'on est dans un groupe (`specialUse`, plus `location` pendant le partage), `ShareBootReceiver`, ligne « Groupe · 4 membres : à l'écoute » sans bouton, message `STATE`, « À l'écoute » dans la liste et pas de repère ; tests JVM et Robolectric. Émulateur le 2026-10-29 (session 78, `docs/previews/p22-alerte-emulateur.html`) : partage coupé et appli en arrière-plan, le service reste avec la ligne « Groupe · 1 membre : à l'écoute » ; en-tête de Partage et pastille de la carte alignés sur la maquette (« Groupe · 1 membre », tout le groupe compté, 0.4.125). **Reste** : alerte et message reçus, avec un deuxième appareil (deux émulateurs ou le test en direct) | `ShareControllerTest` ; émulateur : partage coupé, appli en arrière-plan, alerte et message reçus |
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
| P23.0 | ✅ | Aperçu : valeur touchable, fenêtre de saisie, barre en butée. `docs/previews/p23-saisie.html` (état actuel, 5 variantes : A Pastille et dialogue, B Soulignée et feuille à raccourcis, C Bulle collée à la valeur, D Dialogue avec − et +, E Un crayon pour tout le panneau), **validé le 2026-11-04 : « A · Pastille et dialogue avec l'onglet "fréquence d'envoi" de E qui propose la distance en plus »** ; rendu de l'émulateur ajouté à l'aperçu | Validation explicite, date au journal |
| P23.1 | ✅ | `:core` : bornes, `setZoomRange` 0–24, intervalle et distance libres (`ShareCadence`, réglages du partage). `ValueEntry` (nombre tapé, durée en s / min / h), `ZoomBar.keptBounds` | Tests JVM des bornes et des valeurs hors liste |
| P23.2 | ✅ | Composant (`ui/ValueWindow.kt` : `ValuePill`, `ValueDialog`) + Conditions d'affichage (zoom, altitude, De / À côte à côte) ; correction de la perte de valeur ; flèche et ligne « au-delà de la barre » | Test : valeur hors barre gardée après un toucher de la barre (`DisplayConditionsTypingTest`, et sur l'émulateur 0.4.189) |
| P23.3 | ✅ | Partage : une fenêtre « Réglages d'envoi » pour l'intervalle (s / min / h) et la distance, avertissement sous 5 s ; la pastille garde une valeur hors des crans | `ShareControllerTest` (45 s, 7 m) ; émulateur : 3 s avec avertissement, 2 min, 7 m |
| P23.4 | ✅ | Opacité et couleurs (contraste, luminosité, saturation, rotation) en pastilles avec leur fenêtre ; `percentOf` arrondi | `MapsRootPanelTest` (87 %, −45°) ; émulateur : 87 % |

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
**Décisions du 2026-11-02** (questions de la session « ToolMap », aperçu `docs/previews/p25-partage-sauvegarde.html`,
**variante « C · Poids » validée le 2026-11-02** : « Partage des favoris et sauvegarde : je choisis la variante C · Poids. »)
- Un fichier `.toolmap` contient **plusieurs** favoris et l'AQX de leurs cartes ajoutées, jamais les tuiles ; il part
  par le partage d'Android. Entrée : icône « Partager » dans le bandeau du panneau Cartes et calques ; écran plein
  « Partager des favoris » (✕, « 3 cochés sur 5 »), carte « fichier » en tête (nom, contenu, poids), favoris à cocher,
  choix de la clé, « Partager… » dans la barre du bas.
- Ouverture : feuille « Ajouter N favoris ? », une case par favori et son état (nom déjà pris, carte ajoutée, clé à
  saisir) ; rien n'est appliqué.
- Les tuiles passent par la sauvegarde (P33), jamais en direct entre téléphones. Format : ADR-105.

| ID | Statut | Tâche | Critères d'acceptation |
|---|---|---|---|
| P25.0 | ✅ | Aperçu : commandes d'un favori (Partager, Renommer), choix des clés, import, message du groupe. **Validé le 2026-11-02 : variante « C · Poids »** de `docs/previews/p25-partage-sauvegarde.html` (partage, ouverture ; « Renommer » et le message du groupe n'y sont pas) | Validation explicite, date au journal |
| P25.1 | ✅ | `:core` : format `.toolmap` (versions, AQX inclus, clés), plan d'import (cartes réutilisées ou ajoutées, nom « (2) », clé à saisir, carte masquée remise) ; ADR-105. Fait en session 87 | Tests JVM d'aller-retour, clés retirées et gardées : `FavoritesFileTest` (11) ✅ |
| P25.2 | ✅ | Export : icône « Partager » du bandeau, écran « Partager des favoris » (variante C), partage d'Android | Émulateur : fichier produit ; capture à côté de l'aperçu |
| P25.3 | ✅ | Import (« Ouvrir avec », feuille « Ajouter N favoris ? », cartes, clés, cartes masquées) | Tests ; émulateur : import d'un favori avec une carte ajoutée absente |
| P25.4 | ⬜ | Envoi au groupe (après P27) : trame chiffrée (au-delà de 4096 octets : compression ou découpage), « Ajouter » | `MeshNodeTest` ; deux émulateurs |
| P25.5 | ⬜ | « Renommer » un favori (décision du 2026-10-24) : absent de l'aperçu validé, aperçu à faire avant le code | Aperçu validé ; émulateur : favori renommé |
| P25.6 | ✅ | Favoris fournis mis aux réglages de l'utilisateur, noms compris (demande du 2026-11-02, son export `.toolmap`) : « Rando été » et « Rando Hiver », ids gardés (ADR-107) | `WorkspaceTest`, `BuiltinSourcesTest` ; émulateur : les deux puces renommées dans Cartes et calques |
| P25.7 | ✅ | Aperçu « Enregistrer sur un favori » (demande du 2026-11-02 : changer un favori appliqué et l'écraser). **Validé le 2026-11-02 : variante B · Deux boutons**, dialogue de confirmation raccourci (« Plus court, retire "L'original fourni reste disponible" ») : `docs/previews/p25-enregistrer-favori.html` | Validation explicite, date au journal |
| P25.8 | ✅ | `:core` et `:data` : favori d'origine dans `WorkState` (oublié quand la pile est vidée), écarts favori / pile (`FavoriteChanges`), favori appliqué et modifié (`Favorites`), mise à jour sur place, version de l'utilisateur d'un favori fourni et « Rétablir l'original » (`WorkspaceStore`) ; ADR-108 | `FavoriteChangesTest` (8), `WorkStateTest`, `WorkspaceStoreTest`, `FavoritesFileTest` (favori fourni mis à jour partagé sous son nom), `BackupModelTest` |
| P25.9 | ✅ | Panneau Cartes et calques, variante B : puce tiretée avec point, « (modifié) » dans le bandeau, ligne d'état d'un calque changé en couleur d'accent, boutons « Mettre à jour » et « + Nouveau… », dialogue « Mettre à jour « … » ? » (une ligne par carte changée), snackbar avec « Annuler », « Rétablir l'original » à l'appui long | `MapsRootPanelTest` ; émulateur : panneau modifié, dialogue, après, captures à côté de l'aperçu |

---

## P26 — Couleurs et modes de fusion

**Existant** : MapLibre 13.6.1 : opacité, contraste, luminosité, saturation (`StyleApplier`) ; `raster-hue-rotate`
utilisé depuis P26.6 (curseur « Teinte », ADR-095) ; aucun mode de fusion ; dialogue « Modifier les couleurs » (`ColorsDialog`) ; impression par
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
| P26.0 | ✅ | Faisabilité et coût (APK benchmark, émulateur) : 2 à 3 calques composés dans l'intercepteur, ms par tuile, redessin en ligne et en mode avion ; ADR. Fait le 2026-11-03 (session 89, `tools/perf/B-blend.sh`, ADR-111) : 3 calques, écran entier depuis le stockage 0,80 → 1,32 s, mode avion 0,81 → 1,38 s, en ligne noyé dans le réseau ; tuile composée 402 ms en médiane sous charge ; coût accepté, réglage au relâcher (`docs/specs/blend-modes.md` § 5) | Mesures au journal et dans une spec |
| P26.1 | ✅ | Aperçu du panneau « Couleurs et fusion ». 2026-11-03 : `docs/previews/p26-fusion.html` (état actuel, 5 variantes : A Volet, B Dans la ligne, C Vignettes, D Onglets, E Feuille expliquée ; cartes réelles fusionnées par le navigateur), publié https://claude.ai/artifact/9zaipiJeM11fzmDX6KXCCU ; **validé le 2026-11-03 : « E »** (Feuille expliquée). « Teinter › » n'y est pas dessiné : aperçu complémentaire `docs/previews/p26-teinter.html` (T1 feuille, T2 dans la ligne, roue = cercle chromatique du Partage), publié https://claude.ai/artifact/MUrpK6PzEcPxxwf8c22GPM ; **validé le 2026-11-03 : « T1 »** (feuille) | Validation explicite, date au journal |
| P26.2 | ✅ | `:core` : formules des 16 modes, teinte. Fait le 2026-11-03 (session 89) : `Blend`, `BlendMode` (16 modes W3C, teinte = mode Couleur avec intensité, matrice de couleur), `BlendTest` | Tests JVM sur valeurs de référence |
| P26.3 | ✅ | Modèle (`LayerConfig`, `WorkspaceLayer`), `StackToStyle`, intercepteur. Fait le 2026-11-03 (session 89) : `LayerConfig` et `WorkspaceLayer` (mode, teinte, intensité), `BlendRecipe` (recette dans l'URL), `StackToStyle.blended`, `TileInterceptor.blended`, `BitmapTileBlender` ; rendu vérifié sur l'émulateur (IGN Plan en Produit sur SCAN 25) | `StackToStyleTest`, test de l'intercepteur ; fait : `BlendRecipeTest`, `BlendStyleTest`, `BlendInterceptorTest`, `WorkspaceTest` |
| P26.4 | ✅ | Panneau et roue chromatique. 2026-11-03 (session 89) : « Fusion › » et sa feuille des 16 modes expliqués, avec vignettes de la vraie carte (`BlendSheet.kt`), ligne « · Produit », « Rotation des couleurs », réinitialisation complète ; émulateur à côté de l'aperçu : `docs/previews/p26-fusion-emulateur.html` (0.4.175) ; « Teinter › » (piste T1 choisie le 2026-11-03) : feuille, pastilles, intensité, roue (cercle chromatique du Partage), en 0.4.176, captures ajoutées à la même page | Tests de rendu ; captures à côté de l'aperçu |
| P26.5 | ✅ | Impression. Fait le 2026-11-03 (session 89) : `AndroidBandRenderer` mêle un calque fusionné ou teinté avec les formules de `Blend` ; `AndroidBandRendererTest` (Produit sur SCAN 25) ; PDF d'exemple `android-produit.pdf` (`PrintExportAndroidTest`) relu | PDF relu (un mode Produit visible) |
| P26.6 | ✅ | Demande de l'utilisateur (2026-10-29) : « Rajoute le slide pour Hue, dans la correction de couleur d'un calque / carte. Rends le menu transparent pendant l'utilisation du slider afin de voir les effets rendus sur la carte. » Curseur « Teinte » (rotation des couleurs de MapLibre, `raster-hue-rotate`, en direct, sans l'intercepteur ni la mesure de P26.0) et panneau transparent pendant un réglage. Aperçu `docs/previews/p26-teinte.html` (état actuel, pistes T1 / T2, variantes A à D) **validé le 2026-10-29 : « T2 - A »** (piste en roue des couleurs ; panneau effacé, curseur tenu seul sur une pastille ; les couleurs restent dans la ligne dépliée, D non retenu) ; codé en 0.4.136 (session 81, ADR-095) ; vérifié sur l'émulateur le 2026-10-29 (session 82) : captures à côté de l'aperçu dans `docs/previews/p26-teinte-emulateur.html`, avec les rendus `MapsPanelRenderTest` (`colors-day`, `hue-held-day`) ; favori et PDF avec la teinte vus sur l'émulateur, teinte du style testée dans `RasterAdjustTest` | Validation explicite, date au journal ; `RasterAdjustTest` et `StackToStyleTest` (teinte), favori et PDF avec la teinte ; capture à côté de l'aperçu |

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
- **2026-11-03, demande de l'utilisateur** : le partage au groupe couvre aussi les **tracés** et les **croquis** (P28.9 en
  fait partie). Un partage ne vit que dans le groupe. On crée depuis l'écran du groupe, **sous « Messages »**, pour
  distinguer le perso du partagé ; chaque partage ajoute un **message automatique** (« Marie a partagé un croquis
  « Accès parking » »), qui montre l'élément sur la carte. Aperçu `docs/previews/p27-groupe-partages.html`,
  **variante A validée le 2026-11-03** : bloc « Dans le groupe » (type, nom, auteur, temps restant) et bouton
  « Partager dans le groupe » ouvrant une feuille (Nouveau waypoint · Nouveau tracé · Nouveau croquis · Partager un
  des miens…) ; outils habituels avec « Pour le groupe » à la couleur de l'auteur dans leur bandeau.

| ID | Statut | Tâche | Critères d'acceptation |
|---|---|---|---|
| P27.0 | ✅ | Aperçu : création (nom, symbole, durée, Silencieux / Alerte), repère, fiche, liste « Points du groupe », entrées. 2026-11-03 : entrées et liste validées, variante A de `docs/previews/p27-groupe-partages.html` (waypoints, tracés, croquis) ; création, durée et fiche : `docs/previews/p27-creer-pour-le-groupe.html`, **validé le 2026-11-03 : « B - oui »** (dialogue court « Envoyer au groupe », page « Partager un des miens », fiche d'un partage) | Validation explicite, date au journal |
| P27.1 | 🔄 | Protocole : création, mise à jour, retrait, expiration ; rejeu aux arrivants ; emplacement serveur et règles | `WireTest`, `MeshNodeTest` ; émulateur Firebase (`scripts/firebase-emulator-test.sh`) | 2026-11-03 (session 89) : message `POINT`, révisions et pierres tombales, rejeu, emplacement `b/w`, règles ; `WireTest`, `MeshNodeTest`, `RoomStateTest`, `FirebaseRelayFixesTest` verts ; **reste** l'émulateur Firebase (Node absent du poste Windows), à faire sur un poste qui l'a ou en P27.5 ; 2026-11-03 : tracés et croquis dans le même message (genre et forme compacte, `GroupShape`, ADR-113), `createTrack` et `createSketch`, `b/w` à 17 000 caractères ; `GroupShapeTest`, `ShareControllerTest` |
| P27.2 | ✅ | Contrôleur et stockage local (durées, « tant que le groupe existe ») | `ShareControllerTest` | 2026-11-03 (session 89) : `PointDuration`, `ShareController` (créer, déplacer, renommer, prolonger, retirer, alertes), `share.points.json` ; 4 tests verts |
| P27.3 | 🔄 | Interface : carte, fiche, liste, création, notifications | Captures à côté de l'aperçu | 2026-11-03 (suite 10) : bloc « Dans le groupe », feuille, dialogue B, fiche, outils « Pour le groupe », message automatique, carte ; tests verts ; reste la capture de l'émulateur à côté de l'aperçu et la notification d'un partage reçu |
| P27.4 | 🔄 | Garder dans mes waypoints ; envoyer un waypoint au groupe | Test du dépôt des waypoints | 2026-11-03 (suite 10) : « Garder dans mes waypoints / traces / croquis » et « Partager un des miens » codés ; test du dépôt à écrire |
| P27.5 | ⬜ | Vérification sur téléphone (contexte station de ski), règles republiées | Point reçu par un membre qui écoute sans partager |

---

## P28 — Dessin

**Existant** : main levée du mode dessin (`FreehandLayer`, `Freehand.thin` : Douglas-Peucker à 4 dp) qui produit une
trace ou un itinéraire ordinaire ; la carte ne bouge pas pendant le tracé ; aucun lissage, aucun SVG, aucun objet
« dessin ».

**Décisions du 2026-11-03 (premier lot « Croquis », elles priment sur les décisions du 2026-10-24 ci-dessous)**
- Nom : **« Croquis »**. La tuile « Dessiner une trace » du panneau Traces et waypoints est retirée et remplacée par
  « Croquis » ; une trace à la main se dessine par « Dessiner un itinéraire » avec BRouter éteint (le bouton de la
  liste vide et l'aide « Prise en main » suivent).
- La tuile ouvre l'**espace « Croquis » à part** : liste des croquis et « Nouveau croquis » ; liste vide : directement
  le dessin. Un croquis = une session nommée (« Croquis du 3 nov. »), rouvrable pour la compléter.
- Premier lot = **stylo seul** : formes, sélection et poignées, roue chromatique, surligneur, flèches, impression,
  envoi au groupe et lissage de la main levée de l'itinéraire (P28.7) viennent ensuite.
- Gestes : **bascule Main / Stylo** (remplace « un doigt dessine, deux doigts déplacent ») ; ouverture en **Main**.
- Épaisseur au **curseur seul** (1 à 40 px telle qu'on la voit, enregistrée en mètres) ; **8 couleurs fixes** (rouge,
  orange, jaune, vert, bleu, violet, noir, blanc) jusqu'à la roue de P26.
- Au dézoom : le trait s'affine ; **tout le croquis disparaît** dès que son trait le plus épais passerait sous 1 px.
- **Gomme partielle** sur les points bruts : elle retire les points sous le doigt et coupe le trait en morceaux.
- Lissage faible / moyen / fort choisi avant de dessiner ; pendant le dessin, trait **affiché déjà lissé** (calculé à
  la volée depuis les points bruts) ; **à « Enregistrer »**, chaque morceau est lissé et figé en chemin SVG, les points
  bruts sont jetés. Croquis rouvert : chemins rééchantillonnés en points, la gomme agit partout, tout est reconverti à
  l'enregistrement.
- SVG en **stockage interne** seulement (Room, sauvegarde) : ni fichier .svg partagé, ni GeoJSON dans ce lot.

**Décisions du 2026-11-04 (formes, P28.4)** : elles remplacent, pour les formes, celles du 2026-10-24 ci-dessous.
- Pas de liste de formes toutes faites (trait droit, flèche, cercle, rectangle, triangle) : une forme se pose **point
  par point avec le moteur de Mesure → Surface** (point central, « Ajouter ici » ou appui sur la carte, chiffres en
  direct, annuler et rétablir). Deux sortes : **Ligne** (ouverte) et **Zone** (fermée par « Terminer » ou en touchant
  le premier point).
- L'interrupteur « Remplir » devient des **remplissages** : aperçu `docs/previews/p28-formes.html`, **validé le
  2026-11-04 : « E »** (variante « Motif × couleur ») : 8 motifs (aucun, teinte, hachures, bandes, points, quadrillage,
  croisillons, vagues) dans la couleur choisie ; six combinaisons ont un nom (hachures rouges : Interdit, bandes
  jaunes : Danger, points verts : À préserver, quadrillage bleu : À fouiller, croisillons noirs : Fouillé, vagues
  bleues : Humide).
- **P28.5 fait partie de P28.4** : la sélection, les poignées et la fiche ne concernent que les formes. **Les traits à
  main levée ne se sélectionnent pas et ne se modifient pas** ; seule la gomme agit dessus.

**Décisions du 2026-10-24**
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
| P28.0 | ✅ | Aperçu : bouton d'accès, espace « Dessin », barre d'outils, formes, sélection et poignées, fiche. Premier lot : `docs/previews/p28-croquis.html` (tuile « Croquis », espace et stylo, variantes A Trousse · B Rail · C Pastille · D Carnet · E Équerre), montré le 2026-11-03 ; **validé le 2026-11-03 : « Espace : A · Stylo : D »** (espace = panneau du drapeau avec liste et interrupteurs, A ; outil = bandeau du haut et barre du bas sur deux étages, D) | Validation explicite, date au journal |
| P28.1 | ✅ | `:core` : lissage, modèle (traits, formes, style, épaisseur en mètres), chemin SVG, échantillonnage, mesures. Fait le 2026-11-03 pour le premier lot (traits, style, lissage, SVG, gomme, visibilité ; ADR-110, `core/sketch`) ; formes et mesures avec P28.4 et P28.5 | Tests JVM (lissage, aller-retour SVG, surfaces) |
| P28.2 | ✅ | Stockage (Room, migration), sauvegarde, export GeoJSON et SVG. Fait le 2026-11-03 : tables `sketches` et `sketch_strokes` dans `tracks.db` (version 2, migration automatique), donc dans la sauvegarde ; export GeoJSON et SVG écartés par la décision du 2026-11-03 (SVG en stockage interne seulement) | Test de migration ; aller-retour de sauvegarde |
| P28.3 | ✅ | Mode stylo : gestes, traits lissés, épaisseur en mètres sur MapLibre (au moins 1 px). Fait le 2026-11-03 (variante D, `app/sketch/SketchTool.kt`, `SketchLayers.kt`) ; vérifié sur l'émulateur | Émulateur : trait lissé, carte déplacée à deux doigts |
| P28.4 | 🔄 | Formes et remplissages (décisions du 2026-11-04). Fait le 2026-11-04 (0.4.192 et suivantes) : mode **Forme** (Main · Stylo · Forme · Gomme), Ligne \| Zone, pose point par point (point central, « Ajouter ici », appui sur la carte, premier point touché), chiffres de la mesure, 8 motifs × 8 couleurs avec le nom des six combinaisons connues, « Terminer » ; motifs sur la carte (MapLibre), sous le doigt (outil), à l'impression (PDF, découpés à la zone) et dans l'envoi au groupe ; base `tracks.db` version 3 ; la gomme retire une forme touchée en entier. Reste la sélection, les poignées et la fiche (ex-P28.5), dont l'aperçu attend la validation | Tests du modèle ; captures |
| P28.5 | 🔄 | Rattachée à P28.4 le 2026-11-04 (demande de l'utilisateur) : sélection, poignées et fiche des **formes** seulement, pas des traits à main levée. 2026-11-03 : gomme partielle, annuler et rétablir faits ; panneau du mode Gomme : aperçu `docs/previews/p28-gomme.html` **validé le 2026-11-03 : variante A** (curseur « Taille de la gomme », 8 à 64 px), codé ; fiche de sélection proposée dans `docs/previews/p28-formes.html`, pas encore validée | Tests ; captures |
| P28.6 | ✅ | Espace « Dessin » : liste, afficher et masquer, renommer, supprimer. Fait le 2026-11-03 : espace « Croquis » de la variante A (`SketchPanel.kt`) | Test de rendu |
| P28.7 | ✅ | Lissage de la main levée des traces. Fait le 2026-11-03 (session 89, 0.4.172) : `Freehand.smooth` (lisseur du Croquis) dans `PolylineEditor.addStroke` ; la main levée des itinéraires garde l'amincissement (une étape par point) | `PolylineEditor` : test du lissage |
| P28.8 | ✅ | Impression vectorielle. Fait le 2026-11-03 (session 89, 0.4.173) : croquis visibles imprimés en courbes avec les traces (`PageComposer.drawSketches`), PDF d'exemple `core/build/print-samples/sketch-25000.pdf` relu | PDF relu |
| P28.9 | 🔄 | Envoi au groupe : fait avec P27 (partages du groupe, 2026-11-03 ; stylo « Pour le groupe » codé en suite 10) | `MeshNodeTest` ; deux émulateurs |
| P28.10 | 🔄 | Captures à côté de l'aperçu. 2026-11-03 : captures de l'émulateur (0.4.166) à côté de l'aperçu validé, dans `docs/previews/p28-gomme.html` | Validées par l'utilisateur |
| P28.11 | ✅ | Croquis dans l'impression : interrupteur « Croquis » à part de « Traces », nombre de croquis dans le cadre, croquis gardés visibles sur la carte pendant le cadrage. Demande du 2026-11-03 (« invisible à l'impression ») ; aperçu `docs/previews/print-croquis-variantes.html` (5 variantes), **validé le 2026-11-03 : « A oui »** (variante A, et croquis gardés sur l'aperçu du cadre) ; fait et vérifié sur l'émulateur le 2026-11-03 (0.4.180), captures dans l'aperçu | Tests du PDF et du panneau ; PDF de l'émulateur relu ; capture à côté de l'aperçu |
