# P39.5 — Remontée : maquettes de l'opération, tour 2

Pour la session principale, qui montre les images à l'utilisateur. Ce ne sont que des dessins : aucun fichier Kotlin, `strings.xml`, ressource ou icône touché ; ni `docs/JOURNAL.md`, ni `docs/PLAN.md`, ni `docs/specs/`, ni `version.properties`.

- Page : `docs/previews/p39-groupes/tour2/index.html` (autonome, 660 px de large, se met à la largeur d'un téléphone).
- Images : `docs/previews/p39-groupes/tour2/img/*.png` (42 images, 1320 px de large, 9,7 Mo en tout). Chacune montre deux téléphones côte à côte, avec son titre, ses légendes et son nom de fichier en bas à droite.
- Toutes les images ont été ouvertes et relues après le dernier rendu : pas de texte coupé, accents justes, rien de privé.
- Mots employés : « Entre amis » (le mode simple d'aujourd'hui), « Opération », « Toute l'opération », « Chef de l'opération », « Chef de groupe », « Adjoint », « Administrateur », « Créateur », « Membre », « Groupe », « Mon groupe », « En attente », « Sans groupe » n'apparaît pas (remplacé partout par « En attente »), « Sans chef ».
- Données inventées : opération « Exercice Vallon », groupes Nord (bleu), Sud (orange), Est (vert), Ouest (violet), Centre et Réserve pour l'image des limites ; « Membre 1 » à « Membre 22 ». Le code QR est un motif sans lien. Le fond de carte est celui des maquettes existantes (position par défaut de l'émulateur).
- Étiquettes des images : « Actuel » = l'écran d'aujourd'hui ; « Proposé » = le dessin ; « A (recommandée) », « B », « C » quand un choix reste à faire ; « nuit » = thème de nuit.

## 1. Les images, dans l'ordre

| Fichier | Ce qu'on y voit |
|---|---|
| `00-roles-et-droits.png` | Les huit rôles et leurs signes, puis le tableau « Qui peut faire quoi » (les cases « Q5 » et « Q6 » renvoient aux questions du même numéro) |
| **1 · Entrée** | |
| `01-entree-A.png` | Actuel, puis **A (recommandée)** : l'écran garde sa forme, le mode simple s'appelle « Entre amis », une carte « Opération » avec son bouton dessous |
| `01-entree-BC.png` | B : la carte « Opération » ouvre une page d'explication avant de créer ; C : un seul bouton « Créer et inviter » qui demande le mode |
| `01-entree-nuit.png` | Entrée A et écran « Nouvelle opération », thème de nuit |
| **2 · Créer, inviter** | |
| `02-creation.png` | Actuel (l'invitation s'ouvre aussitôt), puis « Nouvelle opération » : nom, groupes de départ, groupe du créateur |
| `02-invitation.png` | L'invitation vue par un administrateur ; la question posée à la personne qui scanne le code |
| **3 · En attente** | |
| `03-attente-A.png` | Écran d'attente **A (recommandée)**, sobre ; la carte de la personne qui attend (rien du partage n'y figure) |
| `03-attente-BC.png` | B : avec un bouton « Rappeler aux administrateurs » ; C : avec les noms des administrateurs |
| `03-attente-admin.png` | Côté administrateur : la liste d'attente, puis « Placer Membre 21 » dans un groupe |
| `03-attente-nuit.png` | Écran d'attente A et sa carte, thème de nuit |
| **4 · Administrer** | |
| `04-admin-sommaire.png` | Actuel (une liste « Membres », pas de rôle), puis le sommaire : en attente, rôles, groupes, membres |
| `04-admin-groupe.png` | La page d'un groupe ; « Ajouter au Groupe Nord », avec l'encadré « Membre 11 quittera le Groupe Sud » |
| `04-admin-chef.png` | La page du rôle « Chef de l'opération » (chef, adjoint) ; le choix de l'adjoint d'un chef de groupe |
| `04-admin-groupes.png` | « Nouveau groupe » (nom, couleur) ; « Modifier le groupe » (renommer, couleur, supprimer) |
| `04-admin-supprimer.png` | Supprimer un groupe qui a des membres : **A (recommandée)** on choisit le groupe qui les accueille ; B ils repassent en attente |
| `04-admin-sans-chef.png` | Un groupe « sans chef », vu par un administrateur et par un membre de ce groupe |
| `04-admin-limites.png` | 30 membres sur 30 et 6 groupes sur 6 ; la 31e personne qui scanne : « Opération complète » |
| `04-admin-nuit.png` | Sommaire et page d'un groupe, thème de nuit |
| **5 · Réservé au créateur** | |
| `05-admins-createur.png` | Page « Administrateurs » du créateur ; « Nommer un administrateur » |
| `05-admins-retirer.png` | Confirmation du retrait d'un administrateur ; la même page vue par un administrateur (sans « Retirer » ni « Nommer ») |
| `05-retirer-membre.png` | Les gestes sur un membre : le créateur a « Retirer de l'opération » ; pour l'administrateur ce geste est grisé, avec un cadenas |
| `05-retirer-confirmation.png` | Confirmation du retrait d'un membre ; ce que voit la personne retirée |
| **6 · Carte et fiche** | |
| `06-carte.png` | Actuel, puis la carte avec les jetons à la couleur des groupes, couronne, étoile, signes en creux des adjoints |
| `06-carte-vues.png` | La vue « Mon groupe » (les autres en petits points) ; la pastille ouverte, avec la légende des couleurs |
| `06-fiche.png` | Fiche d'un membre : actuel, puis deux étiquettes (groupe, rôle) |
| `06-fiche-cumul.png` | Rôles cumulés (chef de groupe et administrateur) : **A (recommandée)** une étiquette par rôle ; B le premier rôle et « +1 » |
| `06-fiche-adjoints.png` | Fiche de l'adjoint du chef de l'opération ; fiche d'un adjoint de groupe vue par un administrateur (ligne « Rôle et groupe… ») |
| `06-carte-nuit.png` | Carte et fiche, thème de nuit |
| **7 · Partager, écrire** | |
| `07-envoi-membre.png` | Actuel (« Envoyer au groupe »), puis un membre : pas de choix, « Envoyer à mon groupe » |
| `07-envoi-chef-A.png` | **A (recommandée)** : deux boutons côte à côte « Mon groupe / Toute l'opération », vus par un chef de groupe et par le chef de l'opération |
| `07-envoi-chef-BC.png` | B : deux lignes à cocher ; C : deux boutons d'envoi |
| `07-partages-liste.png` | Bas de l'écran du partage : actuel « Dans le groupe », puis « Partagés · Mon groupe » et « Partagés · Toute l'opération » |
| `07-messages.png` | Messages : actuel (un fil), puis deux onglets, fil « Mon groupe » |
| `07-messages-operation.png` | Fil « Toute l'opération » : un chef de groupe (lit, partage un croquis, n'écrit pas) ; le chef de l'opération (écrit) |
| `07-nuit.png` | Envoi (A) et fil « Toute l'opération », thème de nuit |
| **8 · Quitter** | |
| `08-quitter-membre.png` | Actuel (« Quitter le groupe ? »), puis « Quitter l'opération ? » pour un membre |
| `08-quitter-chef.png` | Un chef de groupe quitte ; son groupe ensuite (« Sans chef ») |
| `08-createur-A.png` | Le créateur quitte, **A (recommandée)** : une fenêtre, « Désigner un successeur » ou « Dissoudre » ; le choix du successeur |
| `08-createur-BC.png` | B : une page où l'on coche le successeur ou « Personne » ; C : deux entrées séparées dans le menu ⋮ |
| `08-dissolution.png` | Confirmation de la dissolution ; ce que voient les autres (« Opération dissoute ») |
| `08-successeur.png` | Ce que voit le successeur (« Vous êtes le créateur ») ; ce que voient les autres |
| `08-nuit.png` | Départ du créateur (A) et « Opération dissoute », thème de nuit |

## 2. Les choix à faire

La recommandation est toujours le choix 1. Sans réponse à une question Q9 à Q18, c'est ce qui est dessiné (le choix 1) qui vaut.

### Choix qui se lisent sur les images

**Q1. Comment arrive-t-on à la création d'une opération ?** (`01-entree-A`, `01-entree-BC`)
1. A : la carte « Opération » a son propre bouton « Créer une opération ». *(recommandé : un toucher, et rien ne change pour qui crée un groupe entre amis)*
2. B : on touche la carte, une page explique le mode, puis on crée.
3. C : un seul bouton « Créer et inviter », qui demande « Entre amis » ou « Opération ».

À confirmer dans le même temps : dans A, deux textes de l'écran actuel changent (« Créer un groupe et inviter » devient « Créer un groupe entre amis » ; « Rejoindre un groupe » devient « Rejoindre un groupe ou une opération »), et le texte d'accueil commence par « Entre amis : ».

**Q2. L'écran de celui qui attend un groupe** (`03-attente-A`, `03-attente-BC`)
1. A : sobre. Ce qui se passe, ce qui est fermé, « Quitter l'opération ». Aucun nom de membre. *(recommandé : « ne voit rien » s'applique aussi aux noms)*
2. B : comme A, avec « Rappeler aux administrateurs » (une notification, une fois toutes les 10 minutes au plus).
3. C : comme A, avec les noms des administrateurs à prévenir.

**Q3. Choisir la destination d'un croquis ou d'un waypoint** (`07-envoi-chef-A`, `07-envoi-chef-BC`)
1. A : dans la fenêtre d'envoi, deux boutons côte à côte « Mon groupe / Toute l'opération », « Mon groupe » coché au départ, et une ligne « Les 20 membres des 4 groupes le verront ». *(recommandé)*
2. B : deux lignes à cocher, avec le détail sous chacune.
3. C : deux boutons d'envoi, « Envoyer à mon groupe » et « Envoyer à toute l'opération ».

**Q4. Le départ du créateur** (`08-createur-A`, `08-createur-BC`)
1. A : « Quitter l'opération » ouvre une fenêtre avec les deux sorties, « Désigner un successeur » et « Dissoudre l'opération ». *(recommandé)*
2. B : une page où l'on coche le successeur, ou « Personne », qui dissout.
3. C : pas de « Quitter » pour le créateur ; le menu ⋮ a « Transmettre l'opération… » et « Dissoudre l'opération… ».

**Q5. Qui peut inviter ?** (case « Q5 » du tableau de `00-roles-et-droits`)
1. Les administrateurs et le créateur seulement : le bouton « Inviter » n'apparaît que chez eux. *(recommandé, c'est ce qui est dessiné : ce sont eux qui placent les arrivants)*
2. Tout membre placé dans un groupe, comme aujourd'hui entre amis.
3. Les administrateurs, le chef de l'opération et les chefs de groupe.

**Q6. À qui part l'alerte ?** (case « Q6 » du tableau)
1. À toute l'opération, quel que soit le rôle de celui qui l'envoie. *(recommandé : c'est un appel à l'aide, pas un message ; le bouton s'appelle « Envoyer une alerte »)*
2. À son groupe seulement ; à toute l'opération pour ceux qui peuvent y écrire.
3. Au choix au moment de l'envoi.

**Q7. Supprimer un groupe qui a encore des membres** (`04-admin-supprimer`)
1. A : on choisit le groupe qui les accueille. *(recommandé : personne ne perd la carte)*
2. B : ils repassent « en attente » et ne voient plus rien tant qu'on ne les a pas replacés.
3. Refus : on ne peut supprimer qu'un groupe vide (non dessiné).

**Q8. La fiche d'un membre qui cumule des rôles** (`06-fiche-cumul`)
1. A : une étiquette par rôle, sur deux lignes s'il le faut. *(recommandé)*
2. B : le rôle le plus élevé, puis « +1 » qui s'ouvre au toucher.

### Règles que les dessins ont dû supposer

**Q9. Le créateur est-il dans un groupe ?**
1. Oui : il choisit son groupe en créant l'opération (ligne « Votre groupe » de `02-creation`). Tout le monde a donc un groupe et un fil « Mon groupe ». *(recommandé)*
2. Non : le créateur et les administrateurs peuvent rester hors groupe, tout en voyant tout.

**Q10. La position de celui qui attend est-elle envoyée ?**
1. Non : tant qu'il n'est pas placé, rien ne part de son téléphone et rien n'y arrive (ligne « Votre position n'est pas envoyée » de `03-attente-A`). *(recommandé)*
2. Oui, aux administrateurs seulement, pour savoir où il est avant de le placer.

**Q11. Une question avant d'entrer dans une opération ?** (`02-invitation`, téléphone de droite)
1. Oui : « Rejoindre l'opération ? », avec son nom et ce que veut dire « en attente ». *(recommandé)*
2. Non : on entre dès le code scanné, comme aujourd'hui, et l'écran d'attente explique.

**Q12. Un administrateur peut-il remettre un membre « en attente » (le sortir de son groupe sans le retirer) ?**
1. Non : il change seulement le groupe d'un membre. Couper quelqu'un du partage reste un geste du créateur. *(recommandé, c'est ce qui est dessiné)*
2. Oui.

**Q13. Qui peut succéder au créateur, et doit-il accepter ?**
1. Tout membre placé dans un groupe, les administrateurs proposés en tête ; il devient créateur sans avoir à accepter et en est prévenu (`08-successeur`). *(recommandé)*
2. Un administrateur seulement.
3. Tout membre, mais il doit accepter ; le créateur ne peut partir qu'après.

**Q14. Un administrateur peut-il renoncer seul à son rôle ?**
1. Non : seul le créateur le lui retire. *(recommandé, rien n'est dessiné pour renoncer)*
2. Oui, par un bouton « Renoncer » sur la page « Administrateurs ».

**Q15. Le chef de l'opération peut-il envoyer un croquis à un autre groupe que le sien ?**
1. Non : deux destinations pour tous, « Mon groupe » ou « Toute l'opération ». *(recommandé, c'est ce qui est dessiné)*
2. Oui : il choisit un groupe précis.

**Q16. Qui voit ce qui est partagé à l'intérieur d'un groupe ?**
1. Les membres de ce groupe seulement, administrateurs et chef de l'opération compris s'ils n'en font pas partie (`07-partages-liste`). *(recommandé : même règle pour tous)*
2. Les administrateurs et le chef de l'opération voient les partages de tous les groupes.

**Q17. Les noms sur la carte**
1. Le nom est écrit pour les membres de son groupe et pour les chefs ; les autres jetons ne portent que leurs initiales, le nom se lit en touchant le jeton (`06-carte`). *(recommandé : à 30, tous les noms se recouvriraient)*
2. Le nom de tout le monde, comme aujourd'hui.

**Q18. Un groupe « sans chef » garde-t-il son adjoint ?**
1. Oui : quand le chef part, l'adjoint garde ses droits et ne devient pas chef tout seul ; un administrateur nomme le nouveau chef (`08-quitter-chef`). *(recommandé)*
2. L'adjoint devient chef automatiquement.
3. L'adjoint perd son poste avec le chef.

## 3. Ce que les dessins ont fait apparaître

- **L'écran principal d'une opération** (visible derrière plusieurs fenêtres, et en entier dans `04-admin-sans-chef`, `08-quitter-chef`, `08-successeur`) n'était pas dans la liste demandée. Il reprend le dessin du tour 1 : « Mon groupe » en entier, une ligne par autre groupe sous « Toute l'opération », puis « Messages » et « Partagés ». Les administrateurs y ont en plus la ligne « Administrer », orange quand quelqu'un attend. À valider avec le reste.
- **La pastille « Toute l'opération / Mon groupe » de la carte** vient aussi du tour 1. Elle ne cache rien (tout le monde voit tout le monde) : « Mon groupe » réduit les autres groupes à de petits points. Elle sert de légende des couleurs.
- **Le menu ⋮ de la fiche d'un membre** (« Retirer du groupe » aujourd'hui) disparaît dans une opération : retirer est réservé au créateur et se fait dans « Administrer ».
- **Un seul partage à la fois** (réponse R15 du tour 1) : rien n'est dessiné pour quelqu'un qui est déjà dans un groupe entre amis et veut créer ou rejoindre une opération. Le plus simple : il doit d'abord quitter son groupe ; le message d'aujourd'hui (« Ce code vient d'un autre groupe que le vôtre… ») sert tel quel.
- **Les couleurs des groupes** sont choisies parmi huit ; une couleur prise n'est plus proposée. La couleur personnelle du jeton ne sert plus dans une opération.
- **« Tant que le groupe existe »** (durée d'un partage) devient « tant que l'opération existe » dans une opération.
- **Le premier chef de l'opération** : à la création, il n'y en a pas ; le créateur le nomme ensuite (écrit dans l'encadré de `02-creation`).
- **La limite de 30** compte les personnes en attente (22 membres sur 30 = 20 placés et 2 en attente).

## 4. Ce qui n'a pas pu être dessiné ou vérifié

- **Écrans « Actuel »** : redessinés d'après le code de la version 0.4.238 (`app/src/main/kotlin/fr/toolmap/share/`, textes de `strings.xml`) et le style du tour 1, pas d'après une capture de l'émulateur (pas d'émulateur en session cloud). Les jetons actuels sont des silhouettes de remplacement.
- **§ 10 ter de la spec** : il n'était pas encore sur `conception/groupes-hierarchiques` au moment du travail ; les règles suivies sont celles redonnées dans la consigne.
- **Non dessinés** : les notifications Android (arrivée d'une personne en attente, rappel, nomination), la page « Tous les membres », la recherche d'un membre, le partage d'un tracé ou d'un favori (seuls le waypoint et le croquis sont montrés), l'alerte en cours, les réglages du partage, le rattrapage d'un téléphone resté hors réseau.
- **Thème de nuit** : dessiné pour les écrans principaux de chaque partie (six images), pas pour chaque écran.
- **Mode gants, TalkBack, grandes polices, tablette** : non traités.
- **Mode direct (sans serveur)** : les dessins supposent le serveur de coordination.
- **Rendu** : Chromium (Playwright), police Roboto chargée au rendu ; `index.html` la charge par Google Fonts comme les autres pages de `docs/previews/` et retombe sur la police du système sans réseau. Images réduites à 256 couleurs pour le poids, les couleurs de l'interface et des groupes gardées exactes ; relues après réduction.
- **Icônes** : icônes Material ; la couronne est un tracé fait pour la maquette (pas une icône de l'application).
