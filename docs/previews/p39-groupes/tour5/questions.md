# P39.13 — Opération : questions du tour 5

**Aperçus validés par l'utilisateur le 2026-11-16** (réponses N0 à N14 plus bas, codées en P39.13, ADR-135 ; N13 précisé, d'où N15, en attente et non codée : images 03-doublon, 03-doublon-B).

Aperçus : `index.html` (à ouvrir dans un navigateur) et `img/` (une image par bande). Noms inventés : Exercice Vallon, Nord, Sud, Est, Ouest, Membre 1 à 22, et les nouveaux noms Éclaireur 11, Guide 7, Relais 5, Plateau, Exercice Vallon 2.

Pour chaque question : la réponse recommandée est la première (A), avec sa raison en une phrase.

## Sans question : les listes et les menus

Dans une liste, un en-tête ou une ligne de menu, le nom du groupe s'affiche **seul** : « Nord » au lieu de « Groupe Nord ». Cela vaut pour « Groupes de départ » à la création, l'écran de l'opération, la page d'un groupe, « Administrer », « Placer », la fiche d'un membre, « Administrateurs » et les messages en bas de l'écran.
Images : 01-creation, 01-operation, 01-page-groupe, 01-administrer, 01-placer, 01-fiche, 01-administrateurs, 01-textes-listes.

Renommer un groupe existe déjà (le créateur et les administrateurs) : rien ne change sur qui peut le faire.

## Le mot « groupe »

**N1. Dans une phrase (fenêtre, bouton, message), faut-il garder le mot « groupe » devant le nom ?**
- **A (recommandé) : oui, en minuscule** : « Supprimer le groupe Ouest ? », « Vous êtes chef du groupe Nord ». La phrase reste correcte quel que soit le nom choisi, alors que sans le mot il faudrait choisir « de » ou « d' » selon le nom (« de Nord », « d'Ouest »), ce que l'application ne sait pas faire d'avance.
- B : jamais le mot : « Supprimer Ouest ? », « Vous êtes chef de Nord ».
Images : 01-phrases, 01-textes-phrases.

## Qui renomme quoi

**N2. Qui peut renommer un autre membre ?**
- **A (recommandé) : le créateur et les administrateurs.** C'est la même règle que pour placer, déplacer ou retirer quelqu'un ; les chefs et leurs adjoints n'ont aucun droit sur l'organisation (votre choix R5).
- B : aussi le chef de groupe et son adjoint, pour les membres de leur groupe.
Images : 02-droits.

**N3. Qui accepte ou refuse la demande d'un membre qui veut changer son nom ?**
- **A (recommandé) : le créateur ou n'importe quel administrateur, et la première réponse décide.** La demande n'attend pas qu'une seule personne soit en ligne.
- B : le créateur seul.
Images : 02-admin, 02-admin-valider.

**N10. Peut-on renommer l'opération elle-même ?**
- **A (recommandé) : oui, le créateur et les administrateurs ; les invitations déjà données restent valables.** Personne n'est à réinviter : la personne qui ouvre une ancienne invitation voit l'ancien nom avant d'entrer, puis le nouveau une fois entrée.
- B : oui, mais les invitations déjà données ne marchent plus : il faut réinviter.
- C : non, le nom de l'opération ne change jamais.
Images : 02-operation, 02-operation-invitation.

**N11. Une personne qui attend encore d'être placée dans un groupe peut-elle demander à changer son nom ?**
- **A (recommandé) : non ; un administrateur peut la renommer.** Elle a déjà choisi son nom en rejoignant, et l'administrateur qui la place la voit dans « En attente ».
- B : oui, comme un membre.
Images : 02-droits.

**N13. Deux personnes, ou deux groupes, peuvent-ils porter le même nom ?**
- **A (recommandé) : non, l'écran le refuse** (« Ce nom est déjà pris »). Deux « Guide 7 » sur la carte prêteraient à confusion. Limite : si deux administrateurs choisissent le même nom au même moment, hors réseau, les deux noms passent ; l'application ne peut pas l'empêcher, car les noms circulent chiffrés.
- B : oui, c'est permis.
Images : aucune.

## Où se trouvent les commandes

**N14. Où l'administrateur renomme-t-il un membre ?**
- **A (recommandé) : une ligne « Renommer » en tête de la fiche du membre.** Elle se lit, comme les autres actions de la fiche, et s'ouvre du même geste.
- B : un crayon à côté du nom, en haut de la fiche.
Images : 02-membre, 02-membre-B.

**N12. Comment rendre « Renommer le groupe » plus facile à trouver ?** (Aujourd'hui : un crayon sans texte dans la barre du haut.)
- **A (recommandé) : deux lignes écrites en bas de la page du groupe, « Renommer le groupe » et « Supprimer le groupe ».** Les mots se lisent, et la barre du haut garde la place pour le nom du groupe.
- B : le mot « Renommer » à côté du crayon, dans la barre ; elle devient pleine et le sous-titre est coupé.
Images : 02-groupe, 02-groupe-B.

**N5. Où un membre demande-t-il à changer son nom ?**
- **A (recommandé) : dans le menu ⋮ de l'écran de l'opération, « Changer mon nom… ».** C'est rare : une ligne de menu suffit et l'écran reste dégagé.
- B : un crayon sur sa propre ligne, à côté de l'interrupteur.
Images : 02-demande, 02-demande-B.

**N4. Où le créateur et les administrateurs voient-ils une demande ?**
- **A (recommandé) : dans « Administrer », comme une arrivée : la tuile devient orange (« 1 nom à valider ») et une ligne « Noms à valider » s'ajoute.** Tout ce qui attend une décision est au même endroit.
- B : un bandeau en tête de l'écran de l'opération, avec « Refuser » et « Accepter ».
Images : 02-admin, 02-admin-B.

## La demande d'un membre

**N6. Deux administrateurs répondent en même temps, ou l'un répond hors réseau : que se passe-t-il ?**
- **A (recommandé) : la première réponse compte, l'autre administrateur est prévenu (« Déjà répondu »).** Tous les téléphones rangent les réponses dans le même ordre, donc ils retiennent tous la même.
- B : un accord passe toujours avant un refus, même s'il arrive après.
Images : 02-admin-valider.

**N7. Le membre peut-il annuler sa demande ?**
- **A (recommandé) : oui, tant que personne n'a répondu ; il peut ensuite en faire une autre.** Une faute de frappe se corrige sans déranger un administrateur.
- B : non.
Images : 02-attente.

**N8. Après un refus, le membre apprend-il qui a refusé ?**
- **A (recommandé) : non, « Un administrateur n'a pas accepté ».** Le refus reste une décision de l'organisation, pas d'une personne.
- B : oui, « Membre 5 n'a pas accepté ».
Images : 02-refus.

**N9. Que voit le reste de l'opération quand un nom change (membre, groupe ou opération) ?**
- **A (recommandé) : un message en bas de l'écran, une fois : « Membre 11 s'appelle maintenant « Éclaireur 11 » ».** Personne ne cherche qui est ce nouveau nom sur la carte.
- B : rien ; le nouveau nom remplace l'ancien partout, sans message.
La personne renommée est toujours prévenue, quel que soit le choix.
Images : 02-autres, 02-membre-B.

Ce qui ne demande pas de choix : pendant l'attente, tout le monde voit l'ancien nom ; une demande faite hors réseau part au retour du réseau ; le créateur et les administrateurs changent leur propre nom sans demande.

## Réponses de l'utilisateur (2026-11-16)

N0 A (le nom seul dans les listes et les menus) · N1 A · N2 A · N3 A · N4 A · N5 **B** (le crayon sur sa propre ligne) · N6 A · N7 A · N8 A · N9 **B** (rien pour le reste de l'opération ; le renommé est prévenu) · N10 A · N11 **B** (qui attend demande aussi : « Même à solliciter pour qu'un admin sache de qui en parle plutôt que d'avoir "SM-S913U" son savoir nom/prenom ou autre information connu ») · N12 A · N13 A (précision : « Si on applique le même nom, au moins 1 info dois les différencier exemple leur groupe ») · N14 A ; et les quatre points sans choix ci-dessus. Validés le 2026-11-16 ; codés dans P39.13.

Le menu ⋮ de l'opération (image 02-operation) perd « Changer mon nom… », remplacé par le crayon (N5 B).

## Ajoutés après les réponses (images 03-doublon, 03-doublon-B, 04-attente-nom, 04-nom-pris)

**N15. Comment distinguer deux personnes au même nom ?** (cela n'arrive que si deux noms identiques sont pris au même moment hors réseau, ou par deux arrivants)
- **A (recommandé) : le groupe entre parenthèses, en gris, après le nom, partout** ; à l'intérieur d'un même groupe, leur ordre d'arrivée s'ajoute (« Guide 7 (Nord, 1) »). Une seule règle, le même texte partout.
- B : le groupe seulement là où il n'est pas déjà écrit.
Images : 03-doublon, 03-doublon-B. En attente de la réponse : rien n'est codé.

Sans choix : qui attend un groupe demande un nouveau nom par le même crayon que les membres (04-attente-nom) ; l'écran refuse un nom déjà pris, en rouge, bouton grisé (04-nom-pris), pour un membre, un groupe ou soi-même.

**04-attente-nom et 04-nom-pris validés par l'utilisateur le 2026-11-16** (« Oui, les deux », réponse relayée par la session principale) ; codés dans P39.13, captures de la CI `tour5-04-attente-nom`, `tour5-04-attente-nom-demande`, `tour5-04-nom-pris` et `tour5-04-nom-pris-createur` à mettre à côté. Le prénom d'exemple de 04-attente-nom est devenu « Prénom N. » (dépôt public : aucun prénom dans les images, même inventé).

Le nom qu'un arrivant reçoit d'office est le modèle de son téléphone (« SM-S913U ») : c'est le nom réglé dans l'application, prérempli sur « Rejoindre l'opération ? » ; l'écran d'entrée n'est pas changé (aperçu d'abord).

## Réponses du tour 6 (2026-11-16)

Réponses de l'utilisateur relayées par la session principale, toutes comme recommandé :

> N15 A · Le groupe après le nom, en gris, partout
> M6 1 · Une seule clé à la fois : seul le créateur la change, après chaque départ
> N16 1 · Le champ reste vide : il faut écrire son nom pour entrer

- **N15 A** : validé sur les images 03-doublon et 03-doublon-B (côté A, ordre d'arrivée dans un même groupe compris) ; codé dans P39.13.
- **M6 1** : seul le créateur change la clé d'invitation, après chaque départ (R3 A reste) ; les administrateurs ne la changent plus en relais ; le créateur hors ligne, la clé change à son retour ; la règle de convergence (époque égale, plus petit identifiant gagnant) reste en filet de sécurité. Consigné dans la spécification et le plan (P39.14), non codé ici ; l'ADR revient à la session P39.14.
- **N16 1** : le champ « Votre nom » de « Rejoindre l'opération ? » reste vide quand aucun nom n'est réglé dans l'application ; on ne peut pas entrer tant qu'il est vide. Aperçu : images 05-entree-sans-nom, 05-entree-sans-nom-ecrit et 05-entree-sans-nom-BC (A recommandé : aide en gris, rien en rouge ; B : rouge dès l'ouverture ; C : « Il faut un nom pour entrer. » en gris). En attente du « oui » sur l'aperçu : rien n'est codé.

**05-entree-sans-nom et 05-entree-sans-nom-ecrit validés en variante A par l'utilisateur le 2026-11-16** (« A, texte gris », réponse relayée par la session principale) ; B et C écartées. Codé dans P39.13 : champ vide quand aucun nom n'est réglé dans l'application, curseur dedans, « Rejoindre » grisé tant qu'il est vide, aide en gris (« Aucun nom n'est réglé dans l'application. Écrivez celui que les administrateurs verront : il en faut un pour entrer. »), rouge seulement quand on efface un nom écrit, inchangé quand un nom est réglé. Captures de la CI `tour3-04-entree-nom`, `tour3-04-entree-nom-etats` et `tour5-05-entree-sans-nom-ecrit` à mettre à côté.
