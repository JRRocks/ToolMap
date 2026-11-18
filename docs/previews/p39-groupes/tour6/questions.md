# P39.15 — Opération : questions du tour 6 (rythme d'envoi des positions)

**Aperçus validés le 2026-11-18** (« Validation du 2026-11-18 », en bas) : P39.15 peut être codé.

Votre choix du 2026-11-18, mot pour mot : « 2, a. Réglages communs pour tout le monde prédéfini par le chef d'opération/ admins. » Lecture (`docs/specs/group-hierarchy.md` § 10 octies) : dans une opération, un seul rythme d'envoi des positions, le même pour tout le monde, une minute par défaut, de 30 s à 10 min ; un membre ne règle pas le sien. « Entre amis » ne change pas.

Aperçus : `index.html` (à ouvrir dans un navigateur) et `img/` (une image par bande). Noms inventés : Exercice Vallon, Nord, Sud, Est, Ouest, Membre 1 à 22. Les images « Actuel · APK 0.4.261 » sont des captures de l'APK de debug 0.4.261 prises par la CI sur l'émulateur (run « CI » n° 235) ; le réglage d'« Entre amis » est dessiné d'après le code (il n'en existe pas de capture).

Pour chaque question : la réponse recommandée est la première (A), avec sa raison en une phrase.

## Aujourd'hui (images 01-actuel-…)

Dans une opération, chaque téléphone envoie sa position toutes les 15 s, sans réglage, et rien ne l'écrit à l'écran. Seul « Entre amis » a un réglage : « Réglages du partage » ▸ « Réglages d'envoi » ▸ « Fréquence d'envoi », propre à chacun, 30 s par défaut, de 5 s à 30 min. Le chef de l'opération, s'il n'est pas administrateur, n'a ni « Administrer » ni « Inviter », et son menu ⋮ n'a que « Quitter l'opération… ».

## Sans question

- Sans réglage, une opération envoie une position par minute.
- La présence part une fois par minute (aujourd'hui toutes les 15 s), le résumé toutes les 5 minutes (aujourd'hui toutes les minutes) : rien ne change à l'écran.
- Les alertes partent toujours tout de suite.
- Sous le choix, le même texte dans chaque variante : « Pour tout le monde dans l'opération. Plus espacé : moins de batterie et de données, des positions moins fraîches. Les alertes partent toujours tout de suite. »

## Les questions

**F1. Où le rythme se règle-t-il et se lit-il ?**
- **A (recommandé) : une ligne « Fréquence d'envoi » sous « Partager ma position », sur l'écran de l'opération de chacun.** Une flèche pour qui peut la changer ; pour les autres, un cadenas et « Réglée par le chef de l'opération », comme la ligne de qui attend un groupe (U1 A). Chacun voit à quel rythme part sa position, et le chef de l'opération, qui n'a pas « Administrer », trouve le réglage sur l'écran qu'il a sous les yeux.
- B : comme A, plus « Fréquence d'envoi (modifiable ensuite) » en bas de « Nouvelle opération », pour la régler avant d'inviter.
- C : seulement pour qui règle : une partie « Réglages » en bas d'« Administrer » (créateur, administrateurs) et « Fréquence d'envoi… » dans le menu ⋮ du chef de l'opération et de son adjoint ; les autres membres ne voient rien.
Images : 02-q1-A, 02-q1-B, 02-q1-C.

**F2. Comment choisir la valeur ?** (ce qui s'ouvre en touchant « Fréquence d'envoi »)
- **A (recommandé) : cinq choix** : toutes les 30 s, toutes les minutes (par défaut), toutes les 2 min, toutes les 5 min, toutes les 10 min ; « Appliquer » sur toute la largeur, « Annuler » dessous. Les cinq valeurs se lisent d'un coup et se touchent sans viser ; c'est la forme de « Placer Membre 21 ».
- B : le réglage d'« Entre amis » (« Réglages d'envoi » ▸ « Fréquence d'envoi ») : son curseur, borné de 30 s à 10 min, et la valeur à toucher pour en taper une autre (45 s, 3 min…).
- C : un curseur à cinq crans, la valeur écrite en grand au-dessus.
Images : 03-q2-A-C (A et C), 03-q2-B.

**F3. Qui règle le rythme ?** (votre phrase : « prédéfini par le chef d'opération/ admins »)
- **A (recommandé) : le créateur, les administrateurs, le chef de l'opération et son adjoint.** C'est votre phrase « chef d'opération/ admins » ; l'adjoint suit son chef comme partout (R8). Ce serait le premier droit du chef de l'opération, qui n'en a aucun sur l'organisation (R5) ; le rythme n'en fait pas partie.
- B : le créateur et les administrateurs seulement ; le chef de l'opération lit le rythme comme un membre, sous le cadenas « Réglée par les administrateurs ».
Dans les deux cas, les chefs de groupe, leurs adjoints et les membres lisent le rythme sans le changer.
Images : 04-q3.

## Ce que montre chaque image

| Image | Ce qu'elle montre |
|---|---|
| 01-actuel-operation | Captures : l'écran de l'opération de Membre 1 (créateur, avec le rappel « Transmettre ») et de Membre 7 (membre du groupe Nord). Rien n'indique le rythme. |
| 01-actuel-menu-administrer | Captures : le menu ⋮ du créateur (« Voir comme… » et « Quitter la démonstration » sont des outils de la démonstration) et « Administrer » vu par Membre 5, administrateur. Aucun réglage de l'opération. |
| 01-actuel-creation-entre-amis | Capture du haut de « Nouvelle opération » ; à côté, « Réglages du partage » d'« Entre amis », dessiné d'après le code. |
| 02-q1-A | F1 A : la ligne « Fréquence d'envoi » chez Membre 1, créateur (flèche), et chez Membre 7, membre (cadenas). |
| 02-q1-B | F1 B : « Nouvelle opération » descendu jusqu'en bas, le choix après « Votre groupe ». |
| 02-q1-C | F1 C : « Réglages » en bas d'« Administrer » (Membre 5) ; le menu ⋮ de Membre 2, chef de l'opération. |
| 03-q2-A-C | F2 A et C sur le téléphone de Membre 1, « Toutes les 2 min » choisi. |
| 03-q2-B | F2 B : la feuille « Réglages d'envoi », puis la fenêtre pour taper la valeur. |
| 04-q3 | F3 : qui change le rythme avec A et avec B ; l'écran de Membre 2, chef de l'opération, avec A (flèche) et avec B (cadenas). |
| 05-q2-B-modifie | F2 B modifié : la feuille « Réglages d'envoi », curseur de 1 à 10 min sur 2 min ; puis la fenêtre d'« Entre amis » (1 s à 24 h), « 45 » tapé en secondes, « OK » actif. |
| 05-q2-B-modifie-2 | F2 B modifié après « OK » : la pastille « 45 s », le curseur sur 1 min ; la règle d'une valeur tapée hors des crans. |
| 05-q2-B-modifie-3 | Valeurs courtes, question 2 a : « 20 » tapé en secondes ; sous s / min / h, l'avertissement « Moins de 30 s : plus de batterie pour tout le monde, et le serveur gratuit peut atteindre sa limite du mois. » ; « OK » reste actif. |

Non dessiné : l'écran de qui attend un groupe, et ce que voient les autres au moment où le rythme change.

## Choix du 2026-11-18

Réponse de l'utilisateur relayée par la session principale, mot pour mot :

> F1 A / F2 B, 1 à 10min pour le slide et sans limite pour le "2min" touché / F3 A

- **F1 A** : la ligne « Fréquence d'envoi » sous « Partager ma position », sur l'écran de l'opération de chacun (image 02-q1-A).
- **F2 B modifié, à valider** (images 05-q2-B-modifie et 05-q2-B-modifie-2 ; 03-q2-B reste l'aperçu de départ) :
  - la plage « de 30 s à 10 min » du début devient : de 1 à 10 min au curseur, de 1 s à 24 h en tapant la valeur ;
  - le curseur a un cran par minute ; les chiffres sous les crans sont un ajout (le curseur d'« Entre amis » n'en a pas) ;
  - toucher la valeur ouvre la fenêtre d'« Entre amis », sans borne propre à l'opération et sans son champ « Déplacement minimal » (l'opération n'en a pas) ; l'avertissement « Moins de 5 s : plus de batterie, et plus d'envois au serveur. » apparaît de 1 à 4 s ;
  - une valeur tapée hors des crans (45 s) : la pastille la garde, le curseur se met sur le cran le plus proche (1 min), comme dans « Entre amis » ; déplacer le curseur remplace la valeur par celle du cran.
- **F3 A** : le créateur, les administrateurs, le chef de l'opération et son adjoint changent le rythme ; les autres le lisent sous le cadenas « Réglée par le chef de l'opération ».

## Validation du 2026-11-18

Réponse de l'utilisateur relayée par la session principale, mot pour mot :

> 1 a, 2 a

- **1 a** : F2 B modifié est validé tel que dessiné (images 05-q2-B-modifie et 05-q2-B-modifie-2).
- **2 a** : aucune borne propre à l'opération ; de 1 à 29 s, l'avertissement de l'image 05-q2-B-modifie-3, « OK » actif. Dans l'opération, il remplace celui d'« Entre amis » (« Moins de 5 s… »), qui ne change pas dans « Entre amis ».
- Aperçu validé : F1 A (02-q1-A), F2 B modifié (05-q2-B-modifie, -2, -3), F3 A (04-q3).
