# P39.5 — Remontée : maquettes de l'opération, tour 4

Pour la session principale, qui bâtit la page de choix. Ce ne sont que des dessins : aucun fichier Kotlin, `strings.xml`, ressource ou icône touché. Fichiers modifiés hors de ce dossier : `docs/JOURNAL.md` (une entrée, session 114), `docs/PLAN.md` (ligne P39.5), `docs/specs/group-hierarchy.md` (§ 17, un renvoi ici, sans décision), `version.properties` (écrit par le build).

- **Page** : `docs/previews/p39-groupes/tour4/index.html` (autonome, 660 px de large). Elle reprend tel quel le code du tour 3 (boîte à outils, faits, écrans), qui dessine les téléphones « Actuel », puis ajoute les écrans du tour 4. Mêmes couleurs, mêmes composants, même fond de carte inventé.
- **Images** : `docs/previews/p39-groupes/tour4/img/*.png`, **31 images**, 1320 px de large, 3,1 Mo. Chacune : deux téléphones côte à côte, un titre, une phrase d'en-tête, une légende sous chaque téléphone, le nom du fichier en bas à droite. Elles se lisent seules sur un téléphone.
- **Étiquettes** : « Actuel · tour 3 » = le dessin validé au tour 3 (« Actuel · tour 2 » pour l'écran de création, que le tour 3 n'avait pas redessiné) ; « Proposé » = sans choix à faire ; « Proposé A (recommandée) », « Proposé B » = un choix ; « · nuit » = thème de nuit (au moins un téléphone de nuit dans chaque partie qui a un choix).
- **Mêmes faits qu'au tour 3** : « Exercice Vallon », 4 groupes (Nord, Sud, Est, Ouest sans chef), 20 membres placés, 2 en attente (22 sur 30). Membre 1 créateur et administrateur, dans le Groupe Est ; Membre 2 chef de l'opération (Nord), Membre 3 son adjoint (Sud) ; Membre 4 chef du Nord, Membre 5 son adjoint et administrateur ; Membre 9 chef du Sud et administrateur, Membre 10 son adjoint ; Membre 14 chef de l'Est. Deux images seulement s'en écartent, exprès : l'opération qui vient d'être créée (Membre 1 seul, 3 groupes) et l'opération « à son début » (8 membres dans 3 groupes, aucun poste).
- **Rendu** : Chromium (Playwright), police Roboto (Google Fonts), chaque `section.shot` capturée à l'échelle 2, puis réduite à 256 couleurs. Toutes les images ont été ouvertes et relues après rendu. Deux contrôles automatiques sur chaque téléphone : étiquettes, jetons et position sur la carte qui se touchent (moins de 4 px) ; texte qui sort du téléphone ou coupé sur une ligne. Restent seulement, voulus : les contacts des téléphones « Actuel » (c'est l'écart montré) et les aperçus de messages coupés par des points de suspension (comme au tour 3).

## 1. Les images, dans l'ordre

| Fichier | Ce qu'on y voit | Question |
|---|---|---|
| **1 · L'écran d'attente, sans interrupteur** (T15, T14) | | |
| `01-attente.png` | Actuel (interrupteur « Partager ma position ») ; A : ligne « Ma position » avec un cadenas | U1 |
| `01-attente-B.png` | B : plus de ligne « Ma position » ; A de nuit | U1 |
| **2 · L'alerte d'un administrateur qui n'est pas chef** (T13, T4 B) | | |
| `02-alerte-admin.png` | Actuel (« Mon groupe ») ; A : « Tous les chefs » + « Le Groupe Est ne la reçoit pas, sauf son chef » | U2 |
| `02-alerte-admin-B.png` | B : sans cette ligne ; l'alerte reçue par Membre 14, avec les étiquettes « Créateur », « Administrateur » et « Envoyée à tous les chefs, pas au Groupe Est » | U2 |
| `02-alerte-lignes.png` | La ligne rouge d'un chef de groupe : actuel « à mon groupe et aux chefs », proposé « à tous les chefs » | sans choix |
| **3 · L'alerte d'un créateur seul** (§ 15 de la spec) | | |
| `03-alerte-createur-seul.png` | Opération qui vient d'être créée : actuel (ligne rouge « à mon groupe ») ; proposé (ligne grise « Personne pour la recevoir : vous êtes seul ») | sans choix |
| `03-alerte-sans-chef.png` | 8 membres, aucun poste : A la ligne grise, touchée, explique et mène à « Administrer » ; B l'alerte part à son groupe tant qu'aucun chef n'est nommé | U3 |
| **4 · Chef de groupe et administrateur : quatre lignes** (T1, T2, T3 C, T16) | | |
| `04-envoi-quatre-lignes.png` | Actuel (fenêtre d'un chef de groupe, deux lignes) ; A : une liste de quatre lignes, Membre 9 | U4 |
| `04-envoi-quatre-lignes-suite.png` | A avec « Un groupe » et sa liste déroulante ; B : deux blocs, « Comme chef du Groupe Sud » et « Comme administrateur » | U4 |
| **5 · « Messages » sur les écrans de promotion** | | |
| `05-promotion-groupe.png` | Actuel (sans « Messages ») ; proposé (avec), Membre 7 après le départ du chef du Nord | sans choix |
| `05-promotion-operation.png` | Idem après le départ du chef de l'opération | sans choix |
| `05-promotion-nuit.png` | Le même écran, de nuit, actuel et proposé | sans choix |
| **6 · Les étiquettes de la carte** | | |
| `06-etiquettes-admin.png` | Carte de Membre 5 : « Membre 14 » ne touche plus le jeton 21 (la personne en attente est déplacée) | sans choix |
| `06-etiquettes-arrivant.png` | Carte de Membre 21 placé : sa flèche ne recouvre plus « Secteurs de recherche », « Zone à éviter » ne touche plus le jeton 12 | sans choix |
| `06-etiquettes-sud.png` | Carte de Membre 13 (`12-noms-carte-vues` du tour 3) : « Membre 11 » ne touche plus le jeton 12 | sans choix (écart trouvé en plus) |
| **7 · Trois textes ambigus** | | |
| `07-fil-ferme.png` | « Commandement » chez le chef de l'opération : actuel « Personne pour écrire dans ce fil » ; A ligne grise « Fil fermé : ce groupe n'a ni chef ni adjoint » | U5 |
| `07-fil-ferme-B.png` | B : la ligne retirée, une phrase dessous ; A de nuit | U5 |
| `07-ecrire-commandement.png` | Fil du Groupe Sud ouvert par le chef de l'opération : actuel « Écrire au Groupe Sud » ; A « Au chef et à son adjoint » + une ligne qui nomme les lecteurs | U6 |
| `07-ecrire-commandement-B.png` | B « À Membre 9 et Membre 10 » ; A de nuit | U6 |
| `07-envoi-membre.png` | Fenêtre d'envoi d'un membre : actuel « Seuls les chefs envoient plus loin que leur groupe » ; A « Seul le Groupe Nord le verra. » | U7 |
| `07-envoi-membre-B.png` | B : aucune phrase ; A de nuit | U7 |
| **8 · Le rappel au créateur : le bon chemin** (T10 B, T16) | | |
| `08-rappel-actuel.png` | Actuel : le bandeau choisi (ne dit pas comment faire) et la fenêtre de la variante A (chemin par « Quitter l'opération ») | U8 |
| `08-rappel-A.png` | A : bandeau avec « Transmettre » et « Compris » ; la feuille « Transmettre le rôle de créateur » (il reste membre ; ce que garderait l'ancien téléphone) | U8 |
| `08-rappel-B.png` | B : le bandeau cite « menu ⋮ ▸ Transmettre le rôle de créateur » ; le menu ⋮ avec cette entrée, au-dessus de « Quitter l'opération… » | U8 |
| **9 · La glissière : « jusqu'au bout » lisible** (T8 A) | | |
| `09-glissiere.png` | Pendant le glissement : contraste 3,1:1 → 7,6:1, deux flèches | sans choix |
| `09-glissiere-nuit.png` | De nuit : 3,4:1 → 8,8:1 | sans choix |
| **10 · L'écran de création** (Q9, T14) | | |
| `10-creation.png` | Actuel (tour 2 : « Groupe Nord » coché, 2 groupes) ; A : 3 groupes, « Est » coché, « Aucun » possible, sous le nom : qui le lit | U9 (et sans choix pour le groupe) |
| `10-creation-B.png` | B : le même texte dans un encadré orange au-dessus du bouton ; A de nuit | U9 |
| **11 · Ce qu'on a partagé reste jusqu'à ce qu'on le retire** (T12 ; écart trouvé en plus) | | |
| `11-duree.png` | Actuel = B (« Waypoint · 2 h ») ; A : « Waypoint · Silencieux · Reste affiché jusqu'à ce qu'on le retire » | U10 |
| `11-quitter-texte.png` | « Quitter l'opération ? » : actuel « reste chez ceux qui l'ont reçu » ; proposé « restent affichés jusqu'à ce que quelqu'un les retire » | sans choix (suit U10) |
| `11-retirer.png` | La feuille d'un partage : dans son groupe, « Retirer pour tout le groupe » ; pour toute l'opération, grisé avec qui peut le retirer | sans choix |

## 2. Les questions pour l'utilisateur

La recommandation est toujours le choix A. Sans réponse, c'est A qui vaut.

**U1. L'écran d'attente n'a plus d'interrupteur (votre réponse T15). Que met-on à sa place ?** (`01-attente`, `01-attente-B`)
A. La ligne « Ma position » reste, avec un cadenas : « Membre 21 (vous) · envoyée aux administrateurs ». *(recommandé : la personne voit son jeton, comme partout ailleurs, et voit que sa position part, sans pouvoir la couper)*
B. Plus de ligne « Ma position » : l'écran commence par le sablier ; la liste « D'ici là » dit que les administrateurs voient sa position.
Dans les deux : sous « Quitter l'opération », « C'est le seul moyen de ne plus envoyer sa position. » ; seul le nom de l'opération est écrit (T14).

**U2. L'alerte d'un administrateur qui n'est pas chef part à tous les chefs, pas à son groupe (T13). La feuille d'envoi le dit-elle ?** (`02-alerte-admin`, `02-alerte-admin-B`)
A. Oui : sous « Tous les chefs · 7 personnes », une ligne « Le Groupe Est ne la reçoit pas, sauf son chef (Membre 14). » *(recommandé : il s'attend à ce que ses équipiers la reçoivent ; au moment d'une alerte, il doit savoir qu'ils ne la reçoivent pas)*
B. Non : « Tous les chefs » et le nombre suffisent.
Dans les deux : la ligne rouge dit « à tous les chefs » ; l'alerte reçue porte les étiquettes « Créateur » ou « Administrateur » et « Envoyée à tous les chefs, pas au Groupe Est ».

**U3. Le créateur alerte alors qu'aucun chef ni autre administrateur n'est nommé : personne d'autre que lui ne lirait l'alerte (§ 4.2 et § 15 de la spec). Que fait-on ?** (`03-alerte-sans-chef`)
A. La règle T13 reste : la ligne « Envoyer une alerte » est grise et dit « Aucun chef nommé pour la recevoir » ; touchée, elle explique et mène à « Administrer ». *(recommandé : une seule destination par personne, sans exception ; le cas ne dure que le temps de nommer quelqu'un)*
B. Tant qu'aucun chef n'est nommé, son alerte part à son groupe ; ensuite, à tous les chefs. Change une règle du moteur (une destination qui dépend du moment).
Dans les deux : seul dans l'opération (`03-alerte-createur-seul`), la ligne est grise : « Personne pour la recevoir : vous êtes seul ».

**U4. La fenêtre d'envoi de quelqu'un qui est chef de groupe et administrateur (Membre 9) : comment ranger ses quatre lignes ?** (`04-envoi-quatre-lignes`, `04-envoi-quatre-lignes-suite`)
A. Une seule liste, du plus proche au plus large : « Mon groupe », « Commandement », « Un groupe » (liste déroulante, T3 C), « Toute l'opération ». *(recommandé : la même forme que pour tous les rôles, une ligne de plus seulement)*
B. Les mêmes lignes en deux blocs titrés : « Comme chef du Groupe Sud » (Mon groupe, Commandement) puis « Comme administrateur » (Un groupe, Toute l'opération).
Dans les deux : « Mon groupe » est coché à l'ouverture ; la ligne « Commandement » compte aussi son adjoint (« Chef de l'opération, son adjoint, administrateurs, votre adjoint · 5 personnes »).

**U5. Dans « Commandement », le fil d'un groupe sans chef ni adjoint (le Groupe Ouest) : comment le montrer ?** (`07-fil-ferme`, `07-fil-ferme-B`)
Le texte du tour 3, « Personne pour écrire dans ce fil », laissait croire qu'on pouvait l'ouvrir. Ce fil n'a pas de lecteurs, donc pas de clé : personne n'y écrit, le commandement non plus.
A. Une ligne grise avec un cadenas : « Fil fermé : ce groupe n'a ni chef ni adjoint ». *(recommandé : le groupe reste à sa place dans la liste, la raison est sur la ligne)*
B. La ligne est retirée ; une phrase sous la liste dit pourquoi, et que son fil s'ouvrira quand un chef ou un adjoint sera nommé.

**U6. Le champ de saisie du commandement dans le fil d'un groupe (« Écrire au Groupe Sud » au tour 3) : quel texte ?** (`07-ecrire-commandement`, `07-ecrire-commandement-B`)
Ce fil n'est lu que par le chef du groupe, son adjoint et le commandement, pas par tout le groupe.
A. « Au chef et à son adjoint ». *(recommandé : juste, et ne change pas quand on nomme quelqu'un d'autre)*
B. « À Membre 9 et Membre 10 » (leurs noms).
Dans les deux : une ligne en tête du fil nomme ses lecteurs (« Lu par Membre 9 (chef), Membre 10 (adjoint) et le commandement. Les autres membres du Groupe Sud ne le lisent pas. »).

**U7. Sous la ligne unique de la fenêtre d'envoi d'un membre (« Seuls les chefs envoient plus loin que leur groupe » au tour 3) : quelle phrase ?** (`07-envoi-membre`, `07-envoi-membre-B`)
La phrase du tour 3 était fausse à deux endroits : un chef de groupe n'envoie pas à tous (il remonte au commandement), un administrateur sans poste de chef envoie partout.
A. « Seul le Groupe Nord le verra. » *(recommandé : dit ce qui se passe, sans parler des rôles)*
B. Aucune phrase : la ligne cochée « Mon groupe · Groupe Nord · 5 membres » suffit.

**U8. Le rappel au créateur (bandeau, T10 B) : comment mène-t-il à l'action ?** (`08-rappel-actuel`, `08-rappel-A`, `08-rappel-B`)
Au tour 3, le chemin passait par « Quitter l'opération ». Or pour changer de téléphone il suffit de transmettre le rôle : le créateur reste dans l'opération, comme membre de son groupe.
A. Le bandeau a un bouton « Transmettre », à côté de « Compris » ; il ouvre la feuille « Transmettre le rôle de créateur » (administrateurs seulement ; « Vous restez dans l'opération, dans le Groupe Est, comme membre » ; ce que garderait l'ancien téléphone). *(recommandé : un toucher depuis le rappel)*
B. Le bandeau, sans bouton, cite le chemin : « menu ⋮ ▸ Transmettre le rôle de créateur ».
Dans les deux : le menu ⋮ du créateur a l'entrée « Transmettre le rôle de créateur… » au-dessus de « Quitter l'opération… ».

**U9. L'écran de création dit que le nom de l'opération est lu par toute personne qui reçoit l'invitation (§ 8.8 de la spec). Où ?** (`10-creation`, `10-creation-B`)
A. Sous le champ du nom, en petit : « Le nom est lu par toute personne qui reçoit l'invitation, même avant d'être placée dans un groupe. N'y mettez rien de confidentiel. » *(recommandé : au moment où l'on écrit le nom)*
B. Le même texte dans un encadré orange, juste au-dessus du bouton « Créer l'opération et inviter ».

**U10. Un waypoint ou un croquis partagé dans une opération a-t-il une durée ?** (`11-duree`, `11-quitter-texte`)
Votre réponse T12 : « Tout reste jusqu'à ce qu'on l'enlève ». C'est ce que fait le moteur. Mais les fenêtres d'envoi du tour 3 gardaient « Waypoint · 2 h », comme entre amis.
A. Pas de durée dans une opération : il reste affiché jusqu'à ce que quelqu'un le retire (« Waypoint · Silencieux · Reste affiché jusqu'à ce qu'on le retire »). *(recommandé : c'est votre réponse T12 et le moteur ; un repère d'opération ne disparaît pas tout seul en pleine sortie)*
B. Une durée choisie par son auteur, comme entre amis ; il s'efface à la fin, ou avant si quelqu'un le retire. Demande un changement du moteur (échéance dans les objets durables).
Le texte de « Quitter l'opération ? » suit : A « restent affichés jusqu'à ce que quelqu'un les retire » ; B « jusqu'à leur fin, ou jusqu'à ce que quelqu'un les retire ». Toutes les fenêtres d'envoi du tour 3 et du tour 4 (« 2 h ») suivront ce choix.

## 3. Les écarts sans choix (« D'accord pour tout » ou non)

1. **La ligne rouge d'un chef de groupe** dit « à tous les chefs » (T4 B ; le tour 3 montrait la variante A, « à mon groupe et aux chefs »). Même texte pour son adjoint, un administrateur et le créateur ; « à toute l'opération » pour le chef de l'opération et son adjoint. (`02-alerte-lignes`)
2. **L'alerte reçue** porte aussi les étiquettes « Créateur » et « Administrateur », et dit « Envoyée à tous les chefs, pas au Groupe Est ». (`02-alerte-admin-B`)
3. **Le créateur seul** dans l'opération : la ligne d'alerte est grise, « Personne pour la recevoir : vous êtes seul ». (`03-alerte-createur-seul`)
4. **La ligne « Messages »** reste sur les écrans de promotion, au-dessus du message du bas ; les lignes de membres y sont un peu plus serrées, comme chez un administrateur. (`05-promotion-groupe`, `05-promotion-operation`, `05-promotion-nuit`)
5. **Les étiquettes de la carte** ne touchent plus que leur jeton : la personne en attente un peu plus haut et à gauche, la position de l'arrivant un peu plus au sud, « Zone à éviter » dans le bas de sa zone, « Membre 11 » à gauche de son jeton. (`06-etiquettes-admin`, `06-etiquettes-arrivant`, `06-etiquettes-sud`)
6. **« jusqu'au bout »** sur la glissière : rouge foncé de jour (7,6:1), rose clair de nuit (8,8:1), pleine opacité, deux flèches. (`09-glissiere`, `09-glissiere-nuit`)
7. **L'écran de création** : le créateur est dans le Groupe Est, comme sur toutes les autres images ; trois groupes de départ (Nord, Sud, Est), comme l'écran de l'opération qui vient d'être créée (« 1 membre · 3 groupes ») ; « Aucun » est proposé (Q9 : rien ne l'oblige à prendre un groupe). (`10-creation`)
8. **« Quitter l'opération ? »** dit que les croquis et les waypoints restent affichés jusqu'à ce que quelqu'un les retire (selon U10). (`11-quitter-texte`)
9. **Retirer un partage** (T12, non dessiné jusqu'ici) : la feuille d'un partage a « Retirer pour tout le groupe » quand on partage au même endroit ; sinon la ligne est grisée et dit qui peut le retirer (son auteur, le chef de l'opération, son adjoint et les administrateurs, pour toute l'opération). (`11-retirer`)
10. **Le menu ⋮ du créateur** a l'entrée « Transmettre le rôle de créateur… », à part de « Quitter l'opération… », dans les deux variantes de U8. (`08-rappel-B`)

## 4. Ce que la lecture de la spécification a fait apparaître en plus

- **La durée des waypoints** (U10) : le tour 3 gardait « 2 h » alors que T12 et le moteur disent « jusqu'à ce qu'on l'enlève ».
- **Qui peut retirer un partage** (T12) : jamais dessiné ; dessiné ici (`11-retirer`).
- **Une troisième paire d'étiquettes collées**, sur `12-noms-carte-vues` du tour 3 (« Membre 11 » contre le jeton 12) : corrigée (`06-etiquettes-sud`).
- **La ligne rouge d'un chef de groupe** (T4 B) et **l'étiquette du rôle dans l'alerte reçue** (T13) : le tour 3 montrait la variante qui n'a pas été choisie.
- **Le rappel au créateur** dit aussi ce que garderait son ancien téléphone (T16 : « l'écran le dira ») : dans la feuille de transmission (`08-rappel-A`).
- **Le créateur seul** a deux cas : vraiment seul (rien à choisir, la ligne est grise), et des membres sans aucun chef (U3).

## 5. Ce qui n'a pas été dessiné ou vérifié

- Les notifications Android (alerte, rappel) : non dessinées, comme au tour 3.
- Le glissement et la ligne d'alerte grise pour TalkBack : non traités.
- La feuille d'un partage pour un administrateur ou pour l'auteur lui-même (il peut toujours retirer le sien) : non dessinée.
- L'effet de U10 sur les écrans « Entre amis » : aucun (la durée y reste).
- Le chemin « Transmettre le rôle de créateur » quand il n'y a aucun administrateur : la feuille du tour 3 « Aucun autre administrateur » (`05-successeur-aucun`) vaut aussi ici, non redessinée.

## Branche, commits, CI

- Branche : `claude/exciting-sagan-65pyin`, posée sur `conception/groupes-hierarchiques` 56711dc (0.4.247) ; `.conception-privee` présent au départ et avant chaque push.
- Commits : 28e2311 (maquettes, journal, plan, spec, version **0.4.248**), puis le commit qui ajoute cette ligne (documentation seule).
- Run « CI » **n° 202** (43719967654, commit 28e2311) : **vert**. Compilation, tests, APK de debug et de release réussis ; contrôle sur émulateur réussi ; **« Publish the Android release » sautée** (skipped). Aucune release, aucune pull request ; rien poussé sur `main` ni sur `conception/groupes-hierarchiques`.
- Le run du commit qui ajoute cette ligne est à lire dans l'onglet Actions de la branche.
