# Réorganisation légère des menus et fonctions utiles (P14)

> Demande de l'utilisateur du 2026-10-12 : « Propose une réorganisation des menus. Propose des fonctionnalités utiles (comme supprimer les cartes en favori de base). […] Ne fais pas un refactoring complet, mon app doit rester sensiblement similaire à AlpineQuest pour des raisons d'habitude. »
> Ce document était une **proposition** ; il sert maintenant de suivi. Statuts : ✅ fait · ◐ fait en partie · ⬜ à faire. Les écarts cités (`[N1]`, `[F2]`…) renvoient à `docs/specs/ui-index.md`. L'ordre de réalisation et les critères d'acceptation sont dans `docs/PLAN.md` (P14). Mis à jour le 2026-10-16 (session 32) : lots 1 et 2, P16 (Quitter, dossiers, aide).

## 0. Ce qui reste (état au 2026-10-21)

| Lot | Reste à faire | Tâche du plan |
|---|---|---|
| 2 | rien à coder : `[S2]` fait en session 49 (variante C, ADR-066), `[G1]` gardé tel quel par décision de l'utilisateur ; le déplacement vers une carte SD (`[S1]`) reste à voir sur téléphone | P14.2 |
| 3 | rien : `[R5]` fait en session 49 (variante B, avec F11) ; `[R2]` et `[R4]` faits en P14.10 | P14.3 |
| 4 | rien : `[F3]`, F4, F5, F6, F7, F8, F9, F10, F11 et F14 faits en session 49 ; F12 et F15 abandonnées (décision de l'utilisateur du 2026-10-20) | P14.4, P14.6, P14.7 |

**Aperçus validés le 2026-10-20** : les 13 décisions de `docs/previews/p14-fonctions.html` (faites en session 48), réponses en §6 ; codées en session 49 et comparées à l'émulateur dans `docs/previews/p14-fonctions-emulateur.html`.
| — | Décisions de l'utilisateur encore ouvertes : §7.4 questions 1 à 3 (la 3 révèle un défaut : les favoris fournis supprimés ne suivent pas la sauvegarde) | — |

## 1. Règles de cette réorganisation

1. **Rien ne change de place sans raison écrite.** La barre du bas, ses cinq boutons et leur ordre restent ceux d'AlpineQuest. Le menu principal garde ses commandes et leur ordre.
2. **D'abord réparer, ensuite déplacer, enfin ajouter.** Un bouton qui ne marche pas (Retour, réglage factice) passe avant toute nouveauté.
3. **Un bouton, une logique.** Pas de réglage enregistré sans effet ; s'il ne peut pas être fait tout de suite, il est caché (pas grisé avec une note).
4. **Une fonction, un chemin principal.** Un raccourci est permis s'il est contextuel (ex. « Télécharger autour de cette trace »), pas s'il double un menu.
5. **Pas de nouvelle bibliothèque de navigation.** La pile de retour se fait avec l'`enum Screen` existant (voir `docs/specs/performance.md` §3).

## 2. Lot 1 — Réparer la navigation ✅ (P14.1, 2026-10-15)

| ID | Réalisation | Effet pour l'utilisateur |
|---|---|---|
| N1 ✅ | Pile de retour : `MainActivity` garde la liste des écrans ouverts (`ui/BackStack.kt`, sauvegardée) ; Retour Android et « Fermer » dépilent. Plus aucun écran ne code en dur sa destination. | Retour revient à l'écran précédent, comme partout sous Android et dans AlpineQuest |
| N2, N3, N9 ✅ | Conséquence de N1 : STORAGE revient à Cartes disponibles s'il en vient ; ABOUT revient à la carte ; détail ▸ liste ▸ carte | — |
| N5 ✅ | Retour en mode dessin : confirmation s'il y a des changements ; en mesure, ferme l'outil | plus de tracé perdu par un geste Retour |
| N6, N8 ✅ | Écran verrouillé : Retour et appui long sans effet ; seul le maintien déverrouille | rien ne se ferme dans la poche |
| N7 ✅ | Commandes masquées : la ligne du menu devient « Afficher les commandes de la carte », Retour les réaffiche ; depuis P16.1, le logo aussi | les commandes ne restent pas perdues |

Vérifié sur émulateur (`ui-index.md` §10.5) et par `NavigationBackTest`.

## 3. Lot 2 — Supprimer les boutons factices ✅ (P14.2)

| ID | Proposition et état |
|---|---|
| S1 ✅ | « Emplacement de stockage » : le choix déplace les cartes vers la carte SD, après confirmation (ADR-042). La proposition était de le cacher ; l'utilisateur a demandé le codage. |
| N4 ✅ | « Diagnostic des tuiles » : **réservé à l'APK debug** (2026-10-15) au lieu d'être déplacé dans « Aide et à propos » comme proposé : c'est un outil de mise au point. |
| S2 ✅ | Case « certificats non vérifiés » de l'assistant : **retirée** (session 49, variante C, ADR-066). ToolMap accepte les autorités de certification installées par l'utilisateur dans Android, ce qui couvre les serveurs WMS privés sans désactiver la vérification ; un certificat refusé est nommé à l'étape de test. |
| G1 — | Le réglage « Couleur par défaut » des repères colore aussi le symbole des waypoints sans couleur propre, ou son libellé devient « Couleur par défaut des traces ». Recommandation : changer le libellé (aucun code de rendu). Depuis P16.6, la couleur d'un waypoint vient de la catégorie de son icône : le libellé est d'autant plus trompeur. |

## 4. Lot 3 — Réorganisation légère ✅ (P14.3)

### 4.1 Arborescence actuelle et visée (les changements sont marqués ★)

```
Carte
├── Barre du bas (inchangée) : Logo · Cartes · Drapeau · Position · Boussole · + / −
├── Menu principal (logo)
│   ├── « ? » (aide)                                   ★ ouvre l'aide ; « À propos » reste en bas de l'aide (R5, à faire)
│   ├── Masquer les commandes · Verrouiller l'écran · Imprimer à l'échelle… · Réglages… · Quitter (P16.2, ADR-053) · Plus…
│   ├── ★ section « Espace de travail » retirée (doublon des Favoris du panneau Cartes, [R2], fait en P14.10 : le favori courant est nommé dans le bloc « Carte affichée »)
│   └── Information à l'écran ▸ Informations en haut à gauche
│       Plus…
│       ├── Outils : Mesurer distance et surface · Partage de position…   (★ Rechercher un lieu… déplacé dans le drapeau : fait)
│       ├── Aide : Nouveautés · Signaler une erreur (P16.3)
│       └── ★ « Traces » et « Télécharger la zone affichée » retirées : fait. « Cartes hors ligne ▸ Diagnostic » seulement dans l'APK debug : fait
├── ★ Drapeau ▸ panneau « Traces et waypoints » (au-dessus de la barre) : fait (ADR-043)
│   ├── Ajouter un waypoint ici · Dessiner un itinéraire · Dessiner une trace · Rechercher un lieu…
│   ├── Explorateur (liste complète, dossiers depuis P16.5)          = écran TRACKS
│   ├── Importer un fichier… (à ajouter)
│   └── Afficher tout / Masquer tout (à ajouter)
├── Position ▸ panneau « Positionnement »
│   ├── Mode explorateur · Position en temps réel · Enregistreur de trace · Baromètre (rétabli, ADR-053)
│   ├── ★ La carte suit : Libre · Suivi · Boussole · Explorer   (l'appui long reste un raccourci, [R4], fait en P14.10)
│   └── ★ Services actifs : enregistrement · partage · alertes, avec « Arrêter » pour chacun ([F15], à faire)
├── ★ Point central (petit cercle blanc au milieu de la carte) ▸ menu rapide : fait (P14.5, P14.8)
│   └── Définir comme cible · Créer un waypoint… · Mesurer depuis ici · Coordonnées (copier) · Partager ce point · Ouvrir avec
└── Cartes ▸ panneau « Cartes et calques »
    ├── Cartes disponibles (Ajouter une carte ▸ assistant, `.aqx`, catalogue en ligne P16.4) · Cartes affichées · Sélectionner et enregistrer une zone
    ├── Favoris (seul endroit des espaces de travail ; supprimer et rétablir les fournis : fait, [F2])
    └── Options : Mode hors ligne · Couverture · Importer · Téléchargements · Stockage · Rétablir les favoris fournis
```

### 4.2 Justification de chaque mouvement

| ID | Mouvement | Pourquoi | Habitude AlpineQuest |
|---|---|---|---|
| R1 ✅ | Le drapeau ouvre un panneau au lieu d'un écran plein | les commandes de création (« Dessiner une trace », « Ajouter un waypoint ici ») étaient cachées dans « Plus… », loin du bouton des traces | dans AlpineQuest, le bouton des repères ouvre un menu (nouveau repère, explorateur, ouvrir un fichier) ; ce mouvement **rapproche** ToolMap d'AlpineQuest |
| R2 ✅ | « Espace de travail » retiré du menu principal | même liste que les Favoris du panneau Cartes, mais sans création ni suppression ; deux noms (« espace de travail », « favori ») pour une seule chose | les ensembles de cartes favoris sont dans le panneau des cartes |
| R3 ✅ | « Télécharger la zone affichée… » retiré de « Plus… » | deux parcours pour la même action ; celui d'AlpineQuest (« Sélectionner et enregistrer une zone ») est plus complet. L'écran DOWNLOADS reste : file d'attente (Options ▸ Téléchargements) et couloir autour d'une trace (détail d'une trace) | « Select and save an area » |
| R4 ✅ | Mode de suivi visible dans Positionnement | l'appui long sur le bouton position est le seul accès au cycle libre / suivi / boussole, et rien ne l'indique | — (ajout discret, l'appui long reste) |
| R5 ✅ | « ? » ouvre l'aide à la section du panneau, surlignée (session 49, variante B) | le « ? » ouvre aujourd'hui la page « À propos » (licences) depuis deux panneaux | aide contextuelle |

Ce qui **ne bouge pas** : la barre du bas, l'ordre du menu principal, le panneau Cartes et ses sous-écrans, l'écran Réglages et ses neuf catégories, les menus ⋮ des calques et des cartes, le panneau d'impression.

## 5. Lot 4 — Fonctions utiles proposées ✅ (P14.4 à P14.8)

Classées par intérêt pour un randonneur habitué à AlpineQuest. Chacune reste dans un menu existant : aucune n'ajoute de bouton à la barre du bas.

| ID | Fonction | Où | Détail | Effort |
|---|---|---|---|---|
| **F2** ✅ | **Gérer les favoris** (P14.4, ADR-052) | panneau Cartes ▸ Favoris, appui long | Supprimer, **y compris les favoris fournis** (identifiants dans `workspaces/hidden-builtins.txt`, qui suit la sauvegarde depuis le 2026-10-20 : §7.4, question 3) ; Options ▸ « Rétablir les favoris fournis ». Le favori « IGN topo » est remplacé par « Rando Multi-Zoom ». Non faits : Renommer, Mettre à jour avec l'affichage actuel, Déplacer | S |
| **F3** ✅ | **Masquer une carte fournie du catalogue** (P14.4) | Cartes disponibles ▸ ⋮ | « Masquer de la liste » pour une carte fournie (« Supprimer » reste pour une carte importée) ; option « Afficher les cartes masquées » en tête d'écran. Proposer aussi de vider ses tuiles | S |
| **F1** ✅ | **Menu du point central** (P14.5, ADR-043) | carte, au centre | Petit cercle blanc au milieu de la carte ; menu rapide : Définir comme cible · Créer un waypoint… · Mesurer depuis ici · Coordonnées (copier) · Partager ce point. Grisé pendant les outils et l'écran verrouillé. Écart connu : « Télécharger autour » et « Aller vers » ne sont pas repris | M |
| **F16** ✅ | **Ouvrir avec une autre application** (P14.8, ADR-044) | menu du point central | lien `geo:` ou Waze ; application par défaut dans Réglages ▸ Carte | S |
| F4 ✅ | Menu ⋮ d'une carte plus complet | Cartes disponibles ▸ ⋮ | Vider les tuiles stockées · Exporter en `.aqx` · Télécharger une zone de cette carte (ouvre le mode zone avec cette seule carte cochée) | S |
| F8 ✅ | Fiche d'un élément plus complète | fiche sur la carte | Partager · Exporter · Supprimer (avec annulation) | S |
| F7 ✅ | Explorateur des traces (P14.7) | écran Traces | Nouveau dossier et Déplacer vers… **faits** (P16.5, ADR-055) ; tri (date, nom, distance), filtre, recherche et sélection multiple (afficher / masquer, déplacer, exporter, supprimer) **faits** en session 49 | M |
| F6 ✅ | Appui sur la boîte Position | carte, haut gauche | Copier les coordonnées · Partager · Format suivant | S |
| F9 ✅ | Imprimer cette trace | détail d'une trace | ouvre l'impression en mode atlas le long de la trace | S |
| F10 ✅ | Télécharger une surface mesurée | barre de mesure, mode surface | « Télécharger cette zone » envoie le polygone au formulaire de téléchargement (la forme polygone existe déjà dans `ZoneRequest`, sans outil pour la dessiner) | S |
| F12 — | Annuler une suppression (P14.7) | traces, favoris, cartes importées | bandeau « Supprimé — Annuler » 5 s avant la suppression réelle | M |
| F14 ✅ | Mode nuit de la carte | menu principal ▸ Information à l'écran, ou Réglages ▸ Affichage | assombrit les calques (luminosité, déjà gérée par `RasterAdjust`) sans changer le thème ; c'est le sens du « Night mode » d'AlpineQuest | S |
| F15 — | Services actifs | panneau Positionnement | liste de ce qui tourne en arrière-plan (enregistrement, partage, alertes, téléchargement) avec « Arrêter ». Depuis P16.2, « Quitter » existe aussi (il ferme l'écran sans couper les services, ADR-053) : F15 reste le seul moyen de tout voir et arrêter au même endroit | S |
| F5 ✅ | Filtre du catalogue | Cartes disponibles | champ de recherche par nom ; utile au-delà de 20 cartes (le catalogue en ligne P16.4 en ajoute 25) | S |
| F11 ✅ | Aide contextuelle | « ? » des panneaux | une page d'aide par panneau (texte court), à la place de « À propos » | S |

Effort : S = une session, M = deux sessions environ. « — » : gardé tel quel par décision de l'utilisateur (2026-10-20, §6 point 6).

**Non proposé**, pour ne pas s'éloigner d'AlpineQuest ou du périmètre : navigation à onglets, refonte Material de la barre, glisser-déposer des calques (le menu Monter / Descendre suffit et existe dans AlpineQuest), écran d'accueil.

## 6. Décisions de l'utilisateur

Réponses reçues le 2026-10-12 :

1. R1 : **fait** (P14.3, ADR-043). Le drapeau ouvre un panneau comme AlpineQuest. À valider sur téléphone.
2. R3 : **accord** (« Oui pour le moment ») : un seul parcours, « Sélectionner et enregistrer une zone ». **Fait** (P14.3).
3. S1 : **codé** (« On le code ») : le choix d'emplacement déplace les cartes vers la carte SD (P14.2, ADR-042).
4. F1 : **autrement** : ni l'appui long par défaut ni « créer un waypoint ». L'utilisateur demande le menu du point central, comme AlpineQuest (P14.5, ADR-043). L'appui long garde son réglage.
5. Ordre des lots 3 et 4 : **toujours ouvert**. F2 a été fait le 2026-10-14 à la demande de l'utilisateur (ADR-052), ce qui suit de fait le début de l'option B (§7.3). Restent R2, R4, R5 et F3 (2026-10-20 : R2 et R4 faits en P14.10 ; R5 et F3 en aperçu, à valider).

6. Réponses du 2026-10-20 aux 13 aperçus de `docs/previews/p14-fonctions.html` : G1 inchangé (les deux propositions prenaient trop de place) ; S2 variante C ; F3, F4, F5 oui ; R5 / F11 variante B (grande aide ouverte à la section) ; F15 inchangé ; F6 oui ; F8 variante A ; F9 oui ; F10 oui ; F14 variante B ; F7 oui ; F12 inchangé (la confirmation reste) ; P16.7 oui. Tout est codé en session 49.

Demandes postérieures qui touchent ces lots : « Quitter » rétabli (P16.2, ADR-053), dossiers des traces (P16.5, ADR-055), Nouveautés et Signaler une erreur (P16.3).

## 7. Étude de l'ordre des lots 3 et 4 (question 5)

> Étude du 2026-10-12, par lecture du code (références de fichiers à la date de l'étude, les numéros de ligne ont pu bouger). Elle ne tranche pas : l'ordre reste à décider par l'utilisateur (§7.4).

### 7.1 Ce que le code montrait

- **Espace de travail (R2) : rien ne se perd à le retirer.** Le menu principal liste les favoris (fournis et enregistrés) et permet de les appliquer. Le panneau Cartes en fait autant (`MapsRootSheet.kt`, `onApply`) et permet en plus d'enregistrer (`SaveFavoriteDialog`) et de supprimer un favori (appui long, `DeleteFavoriteDialog`). Seul le menu affiche le nom du favori courant, ou « Pile personnalisée » : cette information part avec le menu, et il faut décider où la mettre (§7.4, question 2). **R2 n'attend donc rien.**
- **Aide (R5) : plus petit que la spec ne le dit.** Le « ? » du menu principal ouvre `AboutScreen`, qui est déjà un écran d'information : introduction, données, licences. R5 se réduit à réordonner cet écran et à changer le libellé du « ? », sans aide à créer.
- **Mode de suivi (R4) : accès caché confirmé.** Le bouton position n'a qu'un appui long, qui ouvre le cycle libre / suivi / boussole (`onPositionLongPress`). Rien à l'écran ne l'indique.
- **Favoris fournis (F2)** : fait par ADR-052. La liste des masqués est dans `workspaces/hidden-builtins.txt` ; or la sauvegarde ne prend que `workspaces/*.json` (`BackupPaths.areaOf`) : après une restauration, les favoris fournis supprimés reviennent (constat du 2026-10-16, par lecture du code ; corrigé le 2026-10-20, §7.4 question 3).
- **Cartes masquées (F3) : le moteur existe déjà.** Un favori qui cite une carte absente ou indisponible est déjà appliqué sans elle, avec le message « Cartes indisponibles ignorées » (`MapViewModel.applyWorkspace`, `workspace_skipped`). Masquer une carte n'a donc pas de conséquence imprévue sur les favoris.

### 7.2 Dépendances

| Élément | Dépend de | Écrans touchés | Remarque |
|---|---|---|---|
| R2 | rien (vérifié) | menu principal | F2 est fait : le panneau Cartes est complet |
| R4 | F15 | panneau Positionnement | même panneau : une seule passe de mise en page |
| R5 | F11 | écran « À propos » | F11 changerait son rôle (N4 n'y touche plus : diagnostic réservé au debug) |
| F3 | F2 ✅ (vocabulaire « masqué ») | Cartes disponibles ▸ ⋮ | deux listes de masquage (favoris, cartes) : même mot, même logique |
| F4 | F3 | Cartes disponibles ▸ ⋮ | même menu que F3 |
| F5 | F3 | Cartes disponibles | l'option « Afficher les cartes masquées » est dans le même écran |
| F12 | F2 ✅ | traces, favoris, cartes | — |
| F15 | R4 | panneau Positionnement | les trois services doivent pouvoir s'arrêter séparément |
| F11 | R5 | aides des panneaux | la page d'aide par panneau suit le découpage de R5 |
| F6, F7, F8, F9, F10, F14 | rien | fiche, trace, mesure, carte | indépendantes |

### 7.3 Trois ordres possibles

- **A. Ordre du plan** : lot 3 (R2, R4, R5), puis lot 4. Respecte à la lettre la règle « d'abord réparer, ensuite déplacer, enfin ajouter » (§1, point 2). Inconvénient : chaque écran est retouché deux fois (R4 puis F15, R5 puis F11).
- **B. F2, F3, F4 d'abord, puis R2 et le reste** : le panneau Cartes est complet avant de retirer son doublon du menu. Déroge à la règle « déplacer avant d'ajouter » pour F2-F4, ce que l'utilisateur a déjà permis. **F2 est fait.**
- **C. Par écran** : Cartes (F3, F4, F5, R2), puis Positionnement (R4, F15, F6), puis Aide (R5, F11), puis le reste de F. Une passe par écran, chaque lot se teste sur un seul écran. Déroge le plus à l'ordre des lots.

**Recommandation : B**, dans cet ordre : F3, F4, puis R2 ; R4 avec F15 ; R5 avec F11 ; enfin F5, F6, F7, F8, F9, F10, F12, F14. B garde la priorité que l'utilisateur a donnée aux favoris et limite les passes sur les écrans de Positionnement et d'Aide. Si l'utilisateur tient à la règle du plan, A reste correct : il coûte une passe de plus sur deux écrans.

### 7.4 Ce qui revient à l'utilisateur

1. **Ordre** : A, B ou C (recommandé : B, déjà commencé par F2).
2. **R2** : où afficher le favori courant (nom du favori ou « Pile personnalisée ») une fois le menu retiré : en titre du panneau Cartes, dans le bandeau de la carte, ou nulle part.
3. **F2** : la liste des favoris masqués doit-elle suivre la sauvegarde (recommandé : oui) ? Elle ne la suivait pas (`hidden-builtins.txt` n'est pas un `.json`) : il fallait soit l'écrire en `.json`, soit l'inscrire dans `docs/specs/backup-format.md`. **Fait le 2026-10-20 selon la recommandation** (seconde solution, sans effet visible) : le fichier entre dans la rubrique `workspaces` de la sauvegarde. Si l'utilisateur préfère que les favoris fournis reviennent après une restauration, il suffit de retirer cette ligne de `BackupPaths.areaOf`.

### 7.5 Effort

Restent 10 changements de taille S (F3, F4, F5, F6, F8, F9, F10, F11, F14, F15) et 2 de taille M (F7 pour le tri, le filtre et la sélection multiple, F12). Les trois R restants sont petits d'après §7.1 ; R4 dépend de F15.
