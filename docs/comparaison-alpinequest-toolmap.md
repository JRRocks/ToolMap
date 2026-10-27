# Comparaison AlpineQuest / ToolMap : boutons, navigation et fonctionnement

> Relevé sur émulateur Android (Medium_Phone, Android 17, 1080 × 2400, langue anglaise).
> Versions testées : **ToolMap 0.4.0** (APK debug, `fr.toolmap`) et **AlpineQuest 2.4.0e** (`psyberia.alpinequest.full`).
> Méthode : navigation au doigt simulé (`adb shell input`), lecture de l'arbre d'interface (`uiautomator`) pour ToolMap, et captures d'écran pour AlpineQuest (dont les éléments ne sont pas lisibles par `uiautomator`, donc navigation aux coordonnées).
> Date : 2026-10-15.
> Ce document complète `docs/specs/ui-index.md` (§10, vérification précédente de ToolMap) ; il ne le remplace pas.

## 1. Légende

| Marque | Sens |
|---|---|
| ✅ | Identique ou équivalent à AlpineQuest |
| ⚠️ | Présent mais différent (libellé, emplacement, comportement) |
| 🕳️ | Présent dans AlpineQuest, absent dans ToolMap |
| 🆕 | Présent dans ToolMap, absent dans AlpineQuest |
| ❓ | Non vérifié pendant ce relevé |

## 2. Structure générale

| Élément | AlpineQuest | ToolMap |
|---|---|---|
| Barre du bas | Montagne (menu), carte (calques), drapeau (repères), cible (position), boussole | Logo (menu), Cartes, Drapeau (traces et waypoints), Ma position, boussole, puis +/− et échange de fond |
| Menu principal | Panneau en haut : ? · Hide menu · Lock screen · Print map · Settings · Exit · More… · OPTIONS | Panneau : en-tête « ToolMap 0.4.0 » + ? · Masquer les commandes · Verrouiller · Imprimer à l'échelle… · Réglages… · Plus… · Espace de travail · Information à l'écran |
| Réglages | **Une seule liste défilante** avec sections (Display units, Technical settings, Map canvas, Placemarks, Positioning, Track recorder…) | **Neuf catégories** (Unités, Affichage, Carte, Grille, Repères, Position, Enregistreur, Alertes, Stockage) ouvertes une à une |
| Unités par défaut | Impériales (ft, mi, ft², acre) | Métriques (à confirmer dans l'écran Unités) |
| Panneau des calques | « MAPS & LAYERS » : Available maps · Displayed maps · Select and save an area · favoris · OPTIONS | « Cartes et calques » : Cartes disponibles · Cartes affichées · Sélectionner et enregistrer une zone · favoris |
| Panneau du drapeau | « PLACEMARKS » : Explore placemarks · Displayed placemarks · Create a placemark · Search a location · OPTIONS | « Traces et waypoints » : Explorer · Ajouter un waypoint ici · Dessiner une trace · Dessiner un itinéraire · Rechercher un lieu |
| Panneau de position | « POSITIONING » : Explorer mode · Real-time position (interrupteur) · Track recorder (interrupteur) · Barometer (interrupteur) · OPTIONS | « Ma position » : Mode explorateur · Position en temps réel · Enregistreur de trace · Partage de position… |
| Affichage des panneaux | Panneaux ancrés au bas de l'écran, sans bouton Fermer ; bouton « ? » à droite du titre | Panneaux ancrés au bas, bouton « Fermer » ou « Close sheet » ; « Aide » à droite |
| Typographie des dates | Non concerné | Dates en anglais sur ce téléphone (« Oct 15, 2026 5:25 PM ») |

## 3. Navigation et touche Retour

Ce tableau est le point le plus visible pour un utilisateur qui passe de l'un à l'autre.

| Situation | AlpineQuest | ToolMap |
|---|---|---|
| Retour sur la carte principale | ❓ Non testé proprement : le Retour a servi à fermer des menus. | Quitte l'application (sans confirmation pendant le test ; le réglage « Confirmer la sortie » n'a pas été vérifié). |
| Retour dans un panneau du bas (Cartes, Drapeau, Position) | ❓ Non testé. | Ferme le panneau. |
| Retour dans **À propos** (AlpineQuest) / **Aide** (ToolMap) | Ferme la fenêtre, revient à la carte. | Ferme l'écran, revient à la carte (retour bouton « Fermer »). |
| Retour dans le **détail d'une trace** (Statistiques) | Non testé. | Revient à la **liste** des traces (conforme à `ui-index.md`, N1). |
| Retour dans la **liste** des traces | Non testé. | Revient à la **carte** (conforme à `ui-index.md`). |
| Retour dans une **catégorie de réglages** | Non testé (les réglages sont une seule liste). | ✅ Corrigé le 2026-10-16 (P16.1) : replie la catégorie ouverte, un second Retour ferme les Réglages. (Constat d'origine : fermait tout l'écran.) |
| Bouton « Fermer » de la liste multi-zoom | — | Revient à la **liste des réglages** (conforme). |
| Retour pendant un tracé (dessin) | Non testé. | Demande « Abandonner les modifications ? » (Annuler / Abandonner). Conforme à `ui-index.md`. |
| Retour pendant une mesure | Non testé. | Ferme la mesure **sans** confirmation (bouton « Quitter » aussi). |
| Retour écran verrouillé | ❓ Verrouillage non confirmé (voir §8). | Ne quitte pas l'application, Retour bloqué (conforme). |
| Sortie de l'application | ❓ Bouton « Exit » présent dans le menu, non actionné. « Confirm exit » est décoché, donc a priori sans confirmation. | Retour sur la carte : quitte l'application (sans confirmation pendant le test). |

## 4. Menus et boutons, écran par écran

### 4.1 Menu principal

| Entrée AlpineQuest | Entrée ToolMap | Comparaison |
|---|---|---|
| Hide menu (cache la barre, ne laisse que le logo) | Masquer les commandes (cache la barre et les zooms) | ✅ Équivalent. |
| Logo quand la barre est masquée : **rétablit la barre directement** | Logo quand la barre est masquée : **rétablit la barre directement** (P16.1) | ✅ Corrigé le 2026-10-16. |
| Lock screen | Verrouiller l'écran | ❓ AlpineQuest : après le tap, la carte restait visible sans pastille de verrouillage ; état non confirmé. ToolMap : confirmé (pastille « Écran verrouillé », appui long pour déverrouiller). |
| Print map | Imprimer à l'échelle… | ✅ Équivalent. |
| Settings | Réglages… | ⚠️ Structure différente (voir §5). |
| Exit | Quitter | ✅ Ajouté le 2026-10-16 (P16.2, ADR-053) : confirmation, et mention de ce qui tourne en arrière-plan. |
| More… (dialogue) : What's new? · Online help · Backup & Restore · Report errors · About | Aide et à propos (?) · Plus… | ⚠️ AlpineQuest regroupe l'aide et la sauvegarde dans une boîte de dialogue. ToolMap a une page « Plus… » (Outils, Cartes hors ligne). |
| OPTIONS (section repliable) | Espace de travail (favoris de carte) · Information à l'écran | ⚠️ ToolMap affiche ici les favoris de carte (voir §4.3). AlpineQuest n'a pas cette section sur le menu. |
| — | « Informations en haut à gauche » (case à cocher) | 🆕 Option d'affichage directe dans le menu. |
| — | « Plus… » → **Outils** : Mesurer distance et surface ; **Cartes hors ligne** : Diagnostic des tuiles (debug) | 🆕 ToolMap groupe des outils dans une page dédiée. |

### 4.2 Plus… (ToolMap) et More… (AlpineQuest)

| AlpineQuest « More… » | ToolMap « Plus… » |
|---|---|
| What's new? | Plus… ▸ Aide ▸ Nouveautés ✅ (P16.3) |
| Online help | — (le ? du menu ouvre « Aide et à propos ») |
| Backup & Restore | Réglages › Stockage et sauvegarde › Sauvegarde et restauration… ⚠️ (chemin plus long) |
| Report errors | Plus… ▸ Aide ▸ Signaler une erreur ✅ (P16.3) |
| About | Aide et à propos (?) |

### 4.3 Cartes et calques

| Fonction | AlpineQuest | ToolMap | Comparaison |
|---|---|---|---|
| Catalogue des cartes | « On-demand maps » : groupes OpenStreetMap (Open Roads Map, Open Topo Map, Standard Carto, Hiking Trails, Cycling Paths, Cycle/Hike, Transport, Humanitarian, OSM France…) ; storage bar 5.44 / 10.41 GB ; réglages (engrenage) ; ≡ tiroir | « Cartes disponibles » : groupes repliables (OpenStreetMap, IGN, Mes cartes, Locales, Autres) ; jauge « Tuiles de ToolMap : 12.4 Mo » ; « Gérer le stockage hors ligne » | ⚠️ Noms et groupes différents. ToolMap affiche la taille des tuiles par carte. |
| Ajouter une carte | **Bouton « + » flottant** → catalogue distant : OSM Maps (9), Bing (4), Google (10), IGN France (21), BKG Allemagne (3), Swisstopo (15), Ordnance Survey UK (6), USGS (7), cartes partenaires | « Ajouter une carte » → « Nouvelle carte… » (assistant : adresse du serveur de tuiles, clé d'API, Referer) ; « Importer une carte (.aqx)… » | ✅ Ajouté le 2026-10-16 (P16.4, ADR-054) : « Catalogue en ligne… » (25 cartes ; ni Bing ni Google, dont les conditions interdisent l'accès direct aux tuiles). |
| Actions sur une carte | ⋮ : affichée / ajoutée comme calque (voir ci-dessous) | ⋮ : Afficher comme fond de carte · Ajouter comme calque · Copier pour modifier · Créer une carte multi-zoom à partir de celle-ci | ✅ ToolMap a un menu complet. |
| Cartes affichées | « Displayed maps » : liste des calques, opacité, ordre | « Cartes affichées » : liste des calques, bouton par calque : Masquer, Opacité, Réglages de la carte (Détails, Forcer la mise à jour, Monter, Descendre, Ne plus afficher, Modifier les couleurs, Couleurs inversées, Plage de zoom) | ✅ Fonctions proches. |
| Favoris de cartes | « FAVORITE MAPS & SETS » : **vide** sur ce téléphone | Cinq favoris fournis (Rando Multi-Zoom, IGN Topo 8–16, OpenStreetMap, OpenTopoMap, Photos aériennes) + enregistrement de l'affichage actuel | 🆕 ToolMap fournit des favoris par défaut. |
| Sélectionner une zone à enregistrer | « Select and save an area » | « Sélectionner et enregistrer une zone » | ✅ Équivalent (non testé en détail). |
| Changer le fond de carte | Non relevé | Revenir à la carte de fond précédente (bouton bas de carte) | 🆕 ToolMap a un bouton d'échange rapide. |
| Application d'un favori depuis le menu | Non relevé (favoris vides) | Espace de travail : appliquer Rando Multi-Zoom ferme le menu et change l'attribution (trois fournisseurs) | ✅ Fonctionnement vérifié côté ToolMap. |

### 4.4 Traces, waypoints et placemarks

| Fonction | AlpineQuest « Explore placemarks » | ToolMap « Explorer les traces et waypoints » |
|---|---|---|
| Écran | « Local placemarks » : dossier **« Recorded tracks »** (0 folder · 0 file), ≡ tiroir, X, ⋮, bouton « + » | « Traces et waypoints » : liste plate avec ⋮ par élément ; bouton « Importer » en haut à droite |
| Organisation | Dossiers | ✅ Ajouté le 2026-10-16 (P16.5, ADR-055) : Nouveau dossier, Nouveau sous-dossier, Déplacer vers… |
| Actions d'un élément | (non ouvert pendant ce relevé) | ⋮ trace : Statistiques · Afficher sur la carte · Renommer · Style de la ligne · Exporter en GPX… · Partager · Supprimer |
| Créer un point | « Create a placemark » (panneau) et « + » (liste) | « Ajouter un waypoint ici » (panneau) ; appui long sur la carte (action configurable) |
| Dessiner une trace | Non présent dans le panneau du drapeau | « Dessiner une trace » et « Dessiner un itinéraire » dans le panneau du drapeau |
| Rechercher un lieu | « Search a location » | « Rechercher un lieu… » ; résultats en ligne après validation (IGN, OpenStreetMap) |
| Affichage des éléments | « Displayed placemarks » : liste de ce qui est affiché | Case de visibilité par élément (non relevée pendant le test) ⚠️ |

**Test de dessin ToolMap** : « Dessiner une trace » → deux points avec « Ajouter ici » (la carte déplacée entre les deux), « Enregistrer » → nom proposé « Tracé du Oct 15, 2026 7:07 PM » → toast « Enregistré. ». Le nom par défaut suit la langue du téléphone.

**Test de recherche ToolMap** : saisie de « Chamonix » puis Entrée → résultats en ligne, chacun avec **Actions** : Voir sur la carte · Aller vers · Enregistrer comme waypoint. « Voir sur la carte » recentre la carte sur Chamonix. ✅ Conforme à `ui-index.md` V1 (catégorie non répétée).

### 4.5 Position

| Fonction | AlpineQuest « POSITIONING » | ToolMap « Ma position » |
|---|---|---|
| Mode explorateur | Explorer mode (action) | Mode explorateur (interrupteur) ✅ — testé : l'interrupteur change d'état et le panneau reste ouvert (effet visuel sur la carte non vérifié) |
| Position en temps réel | Interrupteur | Interrupteur ✅ |
| Enregistreur de trace | Track recorder (interrupteur) | Enregistreur de trace (dialogue : nom, réglage Marche / Vélo / Précis / Économie, Annuler / Démarrer) ⚠️ Une seule étape ; AlpineQuest demande aussi une activité |
| Baromètre | Interrupteur dans le panneau | ✅ Rétabli le 2026-10-16 (P16.2, ADR-053), visible si le capteur existe. |
| Partage de position | — (non présent dans le panneau) | « Partage de position… » 🆕 (groupes, QR, lien, serveur Firebase non configuré) |
| Aide | ? en haut à droite | « Aide et à propos » en haut à droite |

### 4.6 Bouton boussole et bouton de zoom

| Élément | AlpineQuest | ToolMap |
|---|---|---|
| Boussole (bas droite) | Bouton boussole dans la barre du bas | Boussole qui remet le nord en haut, disparaît quand le nord est déjà en haut |
| Zoom | Boutons + / − sur le côté droit | Boutons + / − (bas droite) ; test : 17 → 18 ✅ |
| Commandes de zoom | Réglage « Zoom controls » (détail non relevé) | Réglage « Commandes de zoom » : Boutons affichés · Un niveau |

### 4.7 Impression

| Élément | AlpineQuest « Print map » | ToolMap « Imprimer à l'échelle… » |
|---|---|---|
| Accès | Menu principal | Menu principal |
| Contenu | Non ouvert pendant ce relevé | Panneau en bas : format (A4 paysage par défaut), Ajuster le zoom, marge 10 mm, échelle 1:25 000, pages, image 3272 × 2244 px à 300 dpi, tuiles lues / à télécharger (204), taille estimée du PDF (1,1 à 14,7 Mo), **Télécharger les tuiles manquantes (204)**, **Exporter en PDF** |
| Sortie | Non relevée | PDF (non exporté pendant le test, pour ne pas écrire de fichier) |

## 5. Réglages

| Groupe | AlpineQuest (liste unique) | ToolMap (neuf catégories) |
|---|---|---|
| Unités et coordonnées | Location format · Secondary loc. format · Distance unit (Imperial) · Area unit (Imperial) · Bearing unit (Degrees) · Slope unit (%) · Pressure unit (hPa) · Reference north (True) | Unités et coordonnées (8 réglages, lus dans `ui-index.md` §8) |
| Affichage | Storage location · Hide status bar ✓ · Hide navigation bar ✓ · Display over lockscreen ☐ · Screen always on ☐ · Confirm exit ☐ | Affichage : Thème (Système / Clair / Sombre) · Couleurs du fond d'écran · Mode gants · Boutons plus grands · Apparence des menus · Barre d'état · Barre de navigation · Par-dessus l'écran verrouillé · Garder l'écran allumé · Confirmer la sortie |
| Carte | Map display resolution · Menus appearance · Information boxes style · Zoom controls · Grids density and style · Long-press map actions · Pinch to zoom ✓ · Manual map rotation ✓ · Perspective map view ✓ · Automatic explorer mode ✓ | Carte : Résolution d'affichage (Nette) · Zooms manquants · Cartes multi-zoom · Commandes de zoom · Appui long sur la carte · Ouvrir les positions avec · Zoom au pincement · Rotation manuelle · Vue en perspective |
| Appui long | Long-press map actions | Appui long sur la carte : **Aucune action / Créer un repère ici / Mesurer depuis ce point / Définir comme cible** (testé : « Créer un repère ici » ouvre « Nouveau waypoint » avec Enregistrer et y aller / Enregistrer / Annuler) |
| Repères et traces | PLACEMARKS : Display size · Default color · Target color · Style of tracks · Display borders ✓ · Display shadows ✓ | Repères et traces (taille, couleur par défaut, couleur de cible, flèches, bordures, ombres) |
| Position et cap | POSITIONING : Current course display · Automatic GNSS relock ☐ · Display previous locations ✓ · Disable in background ✓ · EGM96 GNSS adjustment ✓ | Position et cap (cap GNSS, recentrage, positions précédentes, arrière-plan, EGM96, altitude, cap actuel) |
| Enregistreur | TRACK RECORDER : Lost signal warning ✓ · Ask for activity ✓ · Reset stats on new segment ✓ · Dashboard mode ☐ | Enregistreur de trace (perte de signal, activité, statistiques du segment, tableau de bord) |
| Alertes | — (non relevé) | Alertes : proximité, écart, vibration, son, rayons |
| Stockage | Storage location (une seule entrée) | Stockage et sauvegarde : Emplacement de stockage (Mémoire du téléphone / Carte SD) · Sauvegarde et restauration… |
| Réinitialisation | — (non relevé) | ⋮ : « Rétablir les réglages par défaut » |

**Test ToolMap** : Réglages › Carte › « Appui long sur la carte » → dialogue de choix, fermé par « Terminé » sans changement.

## 6. Comportement des boutons, par test

| Action testée | Résultat ToolMap | Résultat AlpineQuest |
|---|---|---|
| Ouvrir le menu principal | Panneau bas, ToolMap 0.4.0 en en-tête | Panneau, ALPINEQUEST 2.4.0E en en-tête |
| Masquer les commandes puis restaurer | ✅ Restaure via le menu (« Afficher les commandes de la carte ») | ⚠️ Restaure directement par le logo |
| Appliquer un favori de carte | ✅ Ferme le menu, change l'attribution | — (aucun favori) |
| Basculer le fond de carte précédent | ✅ | Non testé |
| Recherche de lieu « Chamonix » | ✅ Résultats IGN / OSM, actions | Non testé |
| Dessiner une trace (2 points) puis Enregistrer | ✅ Toast « Enregistré. » | Non testé |
| Mesurer 2 points distants | ✅ 165 m ; Quitter sans confirmation | Non testé |
| Appui long sur la carte | ✅ « Nouveau waypoint » | Non testé |
| Ouvrir l'aide (? / About) | ✅ Revient à la carte | ✅ Dialogue About, Back ferme la fenêtre |
| Catalogue de cartes (+) | 🕳️ Pas d'équivalent | ✅ Catalogue en ligne (OSM, Bing, Google, agences) |
| Verrouillage | ✅ Pastille, Retour bloqué | ❓ |

## 7. Défauts et écarts relevés côté ToolMap

Classés par gravité pour l'utilisateur :

1. **Retour dans une catégorie de Réglages** : quitte tout l'écran Réglages au lieu de revenir à la liste des neuf catégories. Le bouton « Fermer » de la liste multi-zoom, lui, revient bien à la liste. Incohérence entre les deux chemins.
2. **Restauration des commandes masquées** : passer par le menu alors qu'AlpineQuest restaure directement par le logo.
3. **Pas de catalogue de cartes en ligne** : AlpineQuest propose un catalogue (OSM, Bing, Google, agences nationales) ; ToolMap exige une saisie manuelle ou un import `.aqx`.
4. **Pas de baromètre dans le panneau Position** : seule la boîte d'information le montre.
5. **Pas de « Exit » dans le menu** : la sortie passe par la touche Retour ; AlpineQuest propose un bouton Exit dans son menu.
6. **Enregistreur de trace** : une seule étape de dialogue, alors qu'AlpineQuest demande une activité en plus du réglage de précision.
7. **Dates en anglais** sur un téléphone en anglais : cohérent avec la langue du téléphone, mais à vérifier en français (les textes de l'interface sont en français).

## 8. Points non vérifiés

- **Verrouillage AlpineQuest** : après « Lock screen », la carte restait visible sans indicateur. Je n'ai pas pu confirmer si le verrouillage était actif. À refaire.
- **Retour sur la carte AlpineQuest** : non testé proprement (le Retour a servi à fermer des menus).
- **Export PDF** et **téléchargement de tuiles** (ToolMap, impression) : non lancés, pour ne pas créer de fichier ni télécharger.
- **Réglages AlpineQuest détaillés** (Zoom controls, Grids, Information boxes, Menus appearance) : listés, pas ouverts.
- **Partage de position ToolMap** : écran ouvert, bouton « Partager ma position » non actionné (crée un groupe).
- **Diagnostic des tuiles** : présent seulement dans l'APK debug, comme prévu (`ui-index.md`, N4).

## 9. Synthèse

- **Structure** : AlpineQuest regroupe tout dans des panneaux verticaux à icônes, avec des sections en majuscules ; ToolMap reprend les mêmes panneaux mais découpe les réglages en neuf catégories et ajoute une page « Plus… ».
- **Navigation** : la pile de retour de ToolMap est en place (`ui-index.md`, N1), mais deux écarts subsistent : le Retour dans les catégories de Réglages et le Retour sur la carte qui quitte l'application.
- **Fonctions absentes de ToolMap** : catalogue de cartes en ligne, bouton Exit, rapport d'erreurs, « What's new? ».
- **Fonctions relevées dans ToolMap et non vues dans AlpineQuest pendant ce test** : bouton d'échange de fond rapide, partage de position par groupe, alertes de proximité et de suivi, favoris fournis.
- **Vocabulaire** : AlpineQuest dit « placemark » pour un point ou une trace, ToolMap dit « trace » et « waypoint ». Les deux applications utilisent « carte », « calque » et « favori ».

## 10. Écarts corrigés (P16, 2026-10-16)

Corrections faites à la demande de l'utilisateur, sans toucher aux fonctions propres à ToolMap (🆕). Tâches de `docs/PLAN.md` ; vérifiées par tests Robolectric et JVM, **à voir sur téléphone**.

| Écart relevé (§7) | Résolution | Tâche |
|---|---|---|
| 1. Retour dans une catégorie de Réglages quittait tout l'écran | Retour replie la catégorie, puis ferme les Réglages | P16.1 |
| 2. Commandes masquées rétablies par le menu | Un appui sur le logo rétablit la barre | P16.1 |
| 3. Pas de catalogue de cartes en ligne | « Ajouter une carte ▸ Catalogue en ligne… » (ADR-054) | P16.4 |
| 4. Pas de baromètre dans le panneau Position | Interrupteur rétabli (ADR-053) | P16.2 |
| 5. Pas de « Exit » | Ligne « Quitter » du menu principal (ADR-053) | P16.2 |
| 6. Enregistreur : pas d'activité | Faux écart : le dialogue d'activité existait déjà | — |
| 7. Dates dans la langue du téléphone | Laissé tel quel (accord de l'utilisateur) | — |
| §4.2 What's new? · Report errors | Plus… ▸ Aide ▸ Nouveautés · Signaler une erreur | P16.3 |
| §4.4 Pas de dossiers | Nouveau dossier, sous-dossier, Déplacer vers… (ADR-055) | P16.5 |

Laissés comme ils sont, d'accord avec l'utilisateur : neuf catégories de Réglages, unités métriques par défaut, Retour sur la carte qui quitte sans confirmation (le réglage « Confirmer la sortie » existe), Retour en mesure sans confirmation, panneaux fermés par « Fermer », pas de raccourci de sauvegarde dans « Plus… ».
