# P39.5 — Remontée : maquettes de l'opération, tour 3

Pour la session principale, qui montre les images à l'utilisateur. Ce ne sont que des dessins : aucun fichier Kotlin, `strings.xml`, ressource ou icône touché, ni la spec. Fichiers modifiés hors de ce dossier : `docs/JOURNAL.md` (une entrée), `docs/PLAN.md` (ligne P39.5), `version.properties` (écrit par le build).

- Page : `docs/previews/p39-groupes/tour3/index.html` (autonome, 660 px de large, se met à la largeur d'un téléphone). Comme au tour 2, elle est sa propre source : les écrans sont des fonctions JavaScript, dessinées à l'ouverture.
- Images : `docs/previews/p39-groupes/tour3/img/*.png` (52 images, 1320 px de large, 5,6 Mo en tout). Chacune montre deux téléphones côte à côte, avec son titre, ses légendes et son nom de fichier en bas à droite.
- Seuls les écrans que changent les choix Q1 à Q20 et les réponses S1 à S7 sont redessinés. Le reste du tour 2 (création, administration, limites, fiche aux rôles cumulés…) tient toujours.
- Étiquettes : « Avant · tour 2 » = le dessin du tour 2, redessiné ici avec le même fond de carte et les mêmes nombres que le reste ; « Avant · aujourd'hui » = l'écran de l'application (entre amis) ; « Proposé » = le nouveau dessin ; « A (recommandée) », « B », « C » = un choix à faire ; « nuit » = thème de nuit (au moins un téléphone par partie).
- Toutes les images ont été ouvertes et relues après rendu, puis relues une seconde fois par une relecture indépendante (un défaut et une vingtaine de détails repris : un téléphone qui ne montrait pas ce que disait son titre, heures et distances mises en accord avec la carte, légendes ajoutées aux téléphones de nuit). Un contrôle automatique passe sur chaque téléphone (texte coupé par un bord, par une fenêtre ou par l'écran, étiquette de carte hors de l'écran ou sur un jeton) : rien au dernier rendu.

## Branche, commits, CI

- Branche : `claude/loving-cori-40xg11`, posée sur `conception/groupes-hierarchiques` a88f111 (0.4.242) ; `.conception-privee` présent. Version : **0.4.243**.
- Commits : 65ae3c7 (maquettes, journal, plan, version), 48f1321 (reprises après la relecture indépendante : maquettes et journal seulement), puis le commit qui ajoute cette section (ce fichier seulement).
- Run « CI » **n° 192** (43576741849, commit 48f1321) : **vert**. « Style, lint, unit tests, debug APK, release APK » réussi, « Check the APK on an emulator » réussi, **« Publish the Android release » sautée** (skipped). Le run n° 191 (commit 65ae3c7) a été annulé par la CI à l'arrivée du commit suivant (un seul run à la fois par branche) : ce n'est pas un échec.
- Releases et étiquettes après ce run : aucune nouvelle. La dernière release reste `android-0.4.235` (2026-11-10) ; étiquettes : `android-0.4.235`, `android-0.4.234`, `android-0.4.231`, `debug-f335585`, `ios-0.2.1`, `ios-0.2.0`.
- Le run du commit qui ajoute cette section est à lire sur GitHub (onglet Actions de la branche) : il ne peut pas se citer lui-même.
- Rien n'a été poussé sur `main` ni sur `conception/groupes-hierarchiques` ; pas de pull request.

## Les mêmes faits sur toutes les images

- Opération « Exercice Vallon », 4 groupes : Nord (bleu, 5 membres), Sud (orange, 6), Est (vert, 5), Ouest (violet, 4, sans chef).
- **20 membres placés**, dont 17 en ligne ; la pastille de la carte dit « 16 connectés » (les autres que soi, comme aujourd'hui). **2 personnes en attente** (Membre 21, Membre 22). Le compte contre la limite est donc **22 sur 30**, toujours écrit avec son détail (« 20 placés, 2 en attente »). La barre du haut dit « 20 membres » (au tour 2 : « 22 membres » à un endroit, « 20 » à un autre).
- Le créateur, Membre 1, est dans le **Groupe Est** partout. (Sur l'écran de création du tour 2, non redessiné, la case cochée était « Groupe Nord » : à corriger au moment de coder cet écran.)
- Membre 2 chef de l'opération (Nord), Membre 3 son adjoint (Sud), Membre 4 chef du Nord, Membre 5 adjoint du Nord et administrateur, Membre 9 chef du Sud et administrateur, Membre 10 adjoint du Sud, Membre 14 chef de l'Est.
- Le dernier message de chaque fil de groupe a toujours le même auteur (Nord : « Membre 4 : On attend au pont. » ; « Vous : … » sur le téléphone de Membre 4).
- Les mesures des fiches (altitude, vitesse, batterie, précision, coordonnées) diffèrent d'un membre à l'autre.
- Fond de carte inventé (aucun lieu réel, aucun nom), coordonnées inventées (« 31T IO … » : les lettres I et O n'existent pas dans une grille MGRS), noms inventés, code QR sans lien.

## 1. Les images, dans l'ordre

| Fichier | Ce qu'on y voit |
|---|---|
| **1 · Envoyer un croquis ou un waypoint : la destination** | |
| `01-envoi-membre.png` | Avant (tour 2), puis un membre : la liste « Pour » avec une seule ligne, déjà cochée |
| `01-envoi-chef-groupe.png` | Avant (tour 2, variante B : « Toute l'opération »), puis chef de groupe, **A (recommandée)** : « Mon groupe » et « Commandement » |
| `01-envoi-chef-groupe-noms.png` | Le nom de la ligne de remontée : B « Chef de l'opération », C « Remontée » |
| `01-envoi-chef-operation.png` | Chef de l'opération : trois lignes ; « Un groupe » cochée, les groupes en pastilles (**A, recommandée**) |
| `01-envoi-groupe-precis-BC.png` | Choisir le groupe précis : B une ligne par groupe, C une liste déroulante |
| `01-envoi-administrateur.png` | Un administrateur (les trois mêmes lignes) ; la fenêtre du chef de groupe, de nuit |
| **2 · L'alerte selon le rôle** | |
| `02-alerte-membre.png` | Avant (aujourd'hui), puis un membre : « à mon groupe » |
| `02-alerte-chef-groupe.png` | Chef de groupe : **A (recommandée)** son groupe et tous les chefs ; B les chefs seulement |
| `02-alerte-chef-operation.png` | Chef de l'opération : toute l'opération ; son alerte reçue par un membre |
| `02-alerte-recue.png` | L'alerte reçue : avant (aujourd'hui), puis l'alerte d'un membre reçue dans son groupe |
| `02-alerte-nuit.png` | L'alerte d'un chef de groupe reçue par un autre chef ; la feuille d'envoi, de nuit |
| **3 · Messages : trois fils** | |
| `03-messages-membre.png` | Un membre : « Mon groupe » (il écrit), « Toute l'opération » (lecture seule) |
| `03-messages-chef-groupe.png` | « Toute l'opération » chez un chef de groupe : avant (tour 2, avec le bouton de partage), puis trois onglets, lecture seule |
| `03-fil-milieu-A.png` | Fil du milieu, **lecture A (recommandée)** : un fil par groupe ; vu par un chef de groupe, puis la liste des fils chez le chef de l'opération |
| `03-fil-milieu-A-suite.png` | Lecture A : un fil ouvert par le chef de l'opération ; il écrit à toute l'opération |
| `03-fil-milieu-B.png` | Lecture B : un seul fil « Chefs », vu par un chef de groupe et par le chef de l'opération |
| `03-fil-milieu-noms.png` | Le nom du fil du milieu : B « Liaison », C « Remontée » |
| `03-messages-nuit.png` | Fil du milieu (A) et « Toute l'opération » en lecture seule, de nuit |
| **4 · Entrer** | |
| `04-entree-nom.png` | « Rejoindre l'opération ? » : avant (tour 2), puis avec le champ « Votre nom » déjà rempli |
| `04-entree-nom-etats.png` | Le nom effacé (« Rejoindre » grisé) ; la fenêtre de nuit |
| **5 · Le créateur quitte** | |
| `05-createur-A.png` | Avant (tour 2), puis **A (recommandée)** : la glissière « Glisser pour dissoudre » dans la fenêtre aux deux sorties, au repos |
| `05-createur-A-glissement.png` | A : pendant le glissement, puis au bout (« Relâchez pour dissoudre ») |
| `05-createur-B.png` | B : la glissière dans une seconde fenêtre de confirmation, au repos et en cours |
| `05-successeur.png` | Désigner un successeur : avant (tour 2, « Autres membres »), puis les administrateurs seulement |
| `05-successeur-aucun.png` | Aucun autre administrateur ; la fenêtre A de nuit |
| **6 · Supprimer un groupe** | |
| `06-supprimer-groupe.png` | Avant (tour 2, variante B), puis la confirmation : « repassent en attente », position visible des administrateurs, partages du groupe |
| `06-supprimer-groupe-apres.png` | Après : « Administrer » (6 en attente, 3 groupes) ; l'écran d'un membre du groupe supprimé, de nuit |
| **7 · Remettre un membre en attente** | |
| `07-remise-attente-fiche.png` | La fiche du membre dans l'administration : avant (tour 2), puis avec « Remettre en attente » |
| `07-remise-attente-confirmation.png` | La confirmation ; ce que voit ensuite le membre |
| `07-remise-attente-adjoint.png` | La fiche d'un membre qui est déjà adjoint ; la confirmation, de nuit |
| **8 · « Inviter »** | |
| `08-inviter.png` | Avant (aujourd'hui : bouton flottant), puis **A (recommandée)** : une tuile « Inviter » à côté d'« Administrer » |
| `08-inviter-B.png` | B : le bouton flottant ; l'écran d'un membre, sans « Inviter » |
| `08-inviter-complet.png` | 30 sur 30 : « Inviter » grisé avec la raison, variantes A et B |
| `08-inviter-invitation.png` | La feuille d'invitation et son compte ; l'écran complet (A), de nuit |
| **9 · Un chef part** | |
| `09-chef-quitte.png` | Un chef de groupe quitte : avant (tour 2), puis le texte qui dit ce que deviennent ses messages et que son adjoint devient chef |
| `09-promotion-groupe.png` | Les autres membres du groupe : avant (tour 2, « Sans chef »), puis l'adjoint devenu chef |
| `09-promotion-adjoint.png` | L'adjoint devenu chef ; la page du groupe chez un administrateur |
| `09-promotion-operation.png` | De même pour le chef de l'opération : l'adjoint devenu chef, et les autres |
| `09-chef-operation-quitte.png` | Le chef de l'opération quitte ; ce que voient les autres ensuite, de nuit |
| **10 · En attente** | |
| `10-attente.png` | L'écran d'attente : avant (tour 2), puis « Votre position n'est visible que des administrateurs », aucun nom |
| `10-attente-carte.png` | Sa carte : avant (tour 2), puis avec la pastille « Position visible des administrateurs » |
| `10-attente-admin.png` | La carte d'un administrateur, avec ceux qui attendent ; la fiche d'une personne en attente |
| `10-attente-nuit.png` | L'écran d'attente et la carte de l'administrateur, de nuit |
| **11 · Arrivant placé ; rappel au créateur** | |
| `11-arrivant.png` | Un membre vient d'être placé : la fenêtre, puis sa carte avec les croquis et waypoints déjà affichés |
| `11-arrivant-suite.png` | Son fil de messages (qui commence à son arrivée) ; la liste des partages (« avant votre arrivée ») |
| `11-createur-rappel-A.png` | Rappel au créateur, **A (recommandée)** : une fenêtre à la création, puis la page « Créateur » |
| `11-createur-rappel-B.png` | B : un bandeau sur l'écran de l'opération ; la fenêtre A de nuit |
| **12 · Les noms** | |
| `12-noms-carte.png` | La carte : avant (tour 2), puis la même règle, sans étiquette coupée |
| `12-noms-carte-vues.png` | La carte vue du Groupe Sud ; vue du Groupe Nord, de nuit |
| `12-noms-fiche.png` | La fiche d'un membre d'un autre groupe : **A (recommandée)** nom entier, B initiales |
| `12-noms-fiches.png` | La fiche d'un membre de son groupe ; la fiche d'un chef d'un autre groupe |
| `12-noms-liste.png` | La liste d'un autre groupe : **A (recommandée)** noms entiers, B initiales |

## 2. Les questions pour l'utilisateur

La recommandation est toujours le choix 1 (ou A). Sans réponse, c'est ce choix qui vaut.

**T1. Comment s'appelle la ligne par laquelle un chef de groupe remonte un croquis ou un waypoint ?** (`01-envoi-chef-groupe`, `01-envoi-chef-groupe-noms`)
Ce qu'il envoie par cette ligne arrive au chef de l'opération, à son adjoint et aux administrateurs ; les autres groupes ne le voient pas.
1. A : « Commandement ». *(recommandé : un seul mot pour les trois rôles qui reçoivent, et il sert aussi au fil de messages, T7)*
2. B : « Chef de l'opération ».
3. C : « Remontée ».

**T2. Un administrateur peut-il, lui aussi, envoyer à un groupe précis ?** (`01-envoi-administrateur`)
1. Oui : il a les trois mêmes lignes que le chef de l'opération (« Mon groupe », « Un groupe », « Toute l'opération »). *(recommandé, c'est ce qui est dessiné)*
2. Non : seulement « Mon groupe » et « Toute l'opération » ; le groupe précis est réservé au chef de l'opération et à son adjoint.

**T3. Comment choisit-on le groupe précis ?** (`01-envoi-chef-operation`, `01-envoi-groupe-precis-BC`)
1. A : la ligne « Un groupe », puis les groupes en pastilles juste dessous. *(recommandé)*
2. B : une ligne par groupe (la fenêtre s'allonge : huit lignes avec six groupes).
3. C : la ligne « Un groupe », puis une liste déroulante.

**T4. L'alerte d'un chef de groupe part-elle aussi à son propre groupe ?** (`02-alerte-chef-groupe`)
Votre réponse Q6 dit : « un chef de groupe à tous les chefs ».
1. A : oui, à son groupe **et** à tous les chefs. *(recommandé : ses équipiers sont souvent les plus proches pour l'aider)*
2. B : non, aux chefs seulement.

**T5. Les administrateurs reçoivent-ils l'alerte d'un chef de groupe ?** (`02-alerte-chef-groupe`, `02-alerte-nuit`)
1. Oui : « tous les chefs » = le chef de l'opération, les chefs de groupe, leurs adjoints et les administrateurs. *(recommandé, c'est ce qui est dessiné)*
2. Non : seulement les chefs et leurs adjoints.

**T6. Le fil de messages du milieu : qui y lit quoi ?** (`03-fil-milieu-A`, `03-fil-milieu-A-suite`, `03-fil-milieu-B`)
1. A : un fil par groupe, entre le chef du groupe, son adjoint et le commandement (chef de l'opération, son adjoint, administrateurs). Un chef de groupe ne lit pas ce que remonte un autre groupe ; le chef de l'opération a un fil par groupe, et écrit dans « Toute l'opération » pour parler à tous. *(recommandé : c'est la même règle que pour les croquis et les waypoints, réponse S1)*
2. B : un seul fil commun à tous les chefs. Plus simple, mais chaque chef de groupe lit alors ce que les autres écrivent.

**T7. Comment s'appelle le fil du milieu ?** (`03-fil-milieu-A`, `03-fil-milieu-noms`)
1. A : « Commandement ». *(recommandé, le même mot qu'en T1)*
2. B : « Liaison ».
3. C : « Remontée ».
Si vous choisissez la lecture B en T6, le fil s'appelle « Chefs ».

**T8. Où se trouve la glissière qui dissout l'opération ?** (`05-createur-A`, `05-createur-A-glissement`, `05-createur-B`)
1. A : dans la fenêtre aux deux sorties ; le choix « dissoudre » est la glissière elle-même, il n'y a pas d'autre question. *(recommandé : le glissement remplace la seconde confirmation)*
2. B : la fenêtre garde son bouton « Dissoudre l'opération », qui ouvre une seconde fenêtre où l'on glisse.

**T9. Où se trouve « Inviter » pour un administrateur ?** (`08-inviter`, `08-inviter-B`, `08-inviter-complet`)
1. A : une tuile à côté d'« Administrer », avec le compte « 22 sur 30 » ; grisée, elle écrit « Complet · 30 sur 30 ». *(recommandé : rien ne recouvre la liste, et la raison a sa place)*
2. B : le bouton flottant d'aujourd'hui, en bas à droite ; il recouvre le bas de la liste.

**T10. Où et quand rappeler au créateur de désigner un successeur avant de changer de téléphone ?** (`11-createur-rappel-A`, `11-createur-rappel-B`)
1. A : une fenêtre, une seule fois, juste après la création ; puis le rappel reste écrit sur la page « Créateur » d'« Administrer ». *(recommandé)*
2. B : un bandeau en tête de l'écran de l'opération, à chaque ouverture, jusqu'à ce qu'il touche « Compris ».

**T11. Le nom d'un membre d'un autre groupe, quand on touche son jeton ou qu'on ouvre la liste de son groupe** (`12-noms-fiche`, `12-noms-liste`)
Sur la carte, la règle est acquise (Q17) : nom entier pour son groupe et pour les chefs, initiales pour les autres.
1. A : la fiche et la liste donnent le nom entier. *(recommandé : c'est le texte du choix Q17, « le nom se lit en touchant le jeton »)*
2. B : les initiales seulement, partout : hors de son groupe, on ne connaît par leur nom que les chefs et leurs adjoints.

**T12. Que devient ce qu'a envoyé un membre qui quitte l'opération ?** (`09-chef-quitte`)
1. Ses messages, ses croquis et ses waypoints restent chez ceux qui les ont reçus (les waypoints jusqu'à leur échéance) ; lui ne reçoit plus rien. *(recommandé, c'est ce qu'écrit la fenêtre « Quitter l'opération ? »)*
2. Tout ce qu'il a envoyé disparaît chez les autres à son départ.

## 3. Ce que le dessin a fait apparaître

- **Un membre a la même fenêtre d'envoi que les chefs**, avec une seule ligne déjà cochée (au tour 2 : un titre différent et une ligne fixe). Une seule forme pour tous les rôles.
- **Qui lit ce qu'un chef de groupe remonte.** Dessiné : le chef de l'opération, son adjoint, les administrateurs, et aussi l'adjoint du même groupe (il a les droits de son chef, et il est dans le fil du groupe de la lecture A). Les membres du groupe ne le voient pas. À confirmer en même temps que T6.
- **Rôles cumulés.** Un chef de groupe qui est aussi administrateur (Membre 9) est des deux côtés de la remontée : il aurait quatre lignes (« Mon groupe », « Commandement », « Un groupe », « Toute l'opération »). Non dessiné. De même, le chef de l'opération n'a pas de ligne pour n'envoyer qu'au commandement.
- **L'alerte d'un adjoint et d'un administrateur.** Dessiné selon la lecture du § 10 quater : un adjoint fait comme son chef ; un administrateur sans poste de chef alerte son groupe, comme un membre.
- **L'alerte reçue** gagne deux choses : les étiquettes du groupe et du rôle de celui qui alerte, et une ligne « Envoyée à… ».
- **Messages du chef de l'opération aux seuls chefs.** Dans la lecture A, il n'a pas de fil pour écrire à tous les chefs sans que tous les membres lisent : il écrit groupe par groupe, ou à toute l'opération.
- **Un groupe sans chef ni adjoint n'a personne dans son fil du milieu** : la liste du chef de l'opération le dit (« Personne pour écrire dans ce fil »).
- **En attente : l'interrupteur « Partager ma position »** est sur l'écran d'attente (la position part aux administrateurs, Q10). La personne peut donc la couper : à confirmer, ou à retirer si l'on veut qu'un administrateur voie toujours ceux qui attendent.
- **La fenêtre « Rejoindre l'opération ? » dit que les administrateurs verront le nom et la position** : c'est le moment où la personne l'accepte. Sans nom, « Rejoindre » est grisé.
- **Supprimer un groupe** : la confirmation dit aussi que les croquis, waypoints et messages du groupe ne sont plus affichés (lecture : ils disparaissent avec le groupe).
- **Remettre en attente un chef ou un adjoint** : son poste devient vacant ; pour un chef, son adjoint devient chef (même règle que Q18).
- **La fiche d'administration suit les postes du membre** : à un adjoint, on propose « Nommer chef du groupe » et « Retirer le poste d'adjoint », et non « Nommer chef ou adjoint ».
- **L'encadré orange d'un groupe sans chef** est corrigé : sans chef ni adjoint, personne du groupe ne remonte de croquis ni de waypoint ; s'il reste un adjoint, il garde les droits du chef.
- **Sans adjoint, rien ne change** : le groupe d'un chef qui part reste « sans chef » (R11), et le poste de chef de l'opération reste vacant.
- **Écran de l'administrateur** : « Administrer » et « Inviter » sont deux tuiles côte à côte (variante A de T9) ; ses lignes sont un peu plus serrées que chez un membre pour que tout tienne, « Messages » compris (au tour 2, la ligne « Messages » manquait chez les administrateurs). Quand son groupe a plus de cinq membres, « Messages » passe sous le bas de l'écran : il faut faire défiler.
- **Fenêtres** : elles sont à 16 px des bords, pour qu'aucun texte de l'écran de dessous ne dépasse sur les côtés.
- **Un mot nouveau, « Commandement »**, apparaît dans les textes (encadré d'un groupe sans chef, fil du milieu) : il suit la réponse à T1 et T7.

## 4. Ce qui n'a pas été dessiné ou vérifié

- **Positions signées (S5)** : rien à dessiner.
- **Écrans « Avant · aujourd'hui »** : redessinés d'après le code de l'application (`GroupAlert.kt`, textes de `strings.xml`) et les dessins du tour 2, pas d'après une capture de l'émulateur (pas d'émulateur en session cloud).
- **Notifications Android** (alerte reçue hors de l'application, promotion, remise en attente, arrivée d'une personne en attente) : non dessinées ; seules les fenêtres dans l'application le sont.
- **Alerte en cours et alerte levée** (carte « Alerte en cours », « Alerte levée par… ») : inchangées, non redessinées.
- **Rappel au créateur au moment d'une sauvegarde** (« Sauvegarder » sert souvent avant un changement de téléphone) : idée non dessinée, faute de pouvoir montrer cet écran fidèlement.
- **La liste d'attente d'« Administrer »** (tour 2) : non redessinée ; son encadré devra dire que les administrateurs voient la position de ceux qui attendent.
- **L'écran de création** (tour 2) : non redessiné (groupe du créateur à mettre en accord, voir plus haut).
- **Rôles cumulés dans la fenêtre d'envoi**, **envoi d'un tracé ou d'un favori**, **page « Tous les membres »**, **réglages du partage** : non dessinés.
- **Thème de nuit** : un téléphone au moins par partie (14 en tout), pas chaque écran.
- **Mode gants, TalkBack, grandes polices, tablette, mode direct sans serveur** : non traités. Le glissement devra avoir son équivalent pour TalkBack (aujourd'hui, le bouton « Maintenez pour envoyer » de l'alerte s'active par un double toucher).
- **Rendu** : Chromium (Playwright), police Roboto chargée au rendu (Google Fonts, comme les autres pages de `docs/previews/`). Images réduites à 256 couleurs ; relues après réduction.
- **Numéro de session du journal** : « 107 » a été pris sans connaître les numéros des deux sessions qui travaillent en parallèle ; à renuméroter à la fusion s'il y a doublon.
