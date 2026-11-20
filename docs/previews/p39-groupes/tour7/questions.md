# P39.13 — Opération : tour 7, le clavier sur « Rejoindre l'opération ? » (question k1)

Votre réponse du 2026-11-20, mot pour mot : « A, fenêtre au-dessus ».
La question était : « Pour le clavier qui cache « Rejoindre », je dessine laquelle ? » (A : la fenêtre monte au-dessus du clavier, conseillé ; B : on garde le clavier tel quel ; C : pas de curseur à l'ouverture).

Aperçus : `index.html` (à ouvrir dans un navigateur) et `img/` (une image par bande). Les captures de l'émulateur sont dans `captures/` : APK de debug 0.4.265 de la CI (run « CI » n° 241), téléphone sans nom réglé, opération inventée « Exercice Vallon ». Les images « Proposé A » clavier ouvert sont des **captures retouchées** : des morceaux de ces captures assemblés, sans rien redessiner. Les images « clavier fermé » sont des captures telles quelles.

## Ce qu'on voit aujourd'hui (image 01-actuel)

Le champ « Votre nom » est vide, le curseur dedans, et le clavier s'ouvre tout seul. La fenêtre monte déjà au-dessus du clavier :
- à 411 dp et à 360 dp de large, elle y tient : « Annuler » et « Rejoindre » se voient ;
- à 320 dp (la largeur de l'émulateur de la CI), elle est plus haute que la place qui reste : son bas, avec les deux boutons, passe sous le clavier ;
- avec le plus grand texte d'Android (200 %), l'aide passe aussi sous le clavier ; et même clavier fermé, le texte « Vous arrivez « en attente »… » est coupé après « en ».

## Proposé A (images 02 à 04)

Quand la place manque, l'icône et le titre restent en haut, « Annuler » et « Rejoindre » restent en bas, juste au-dessus du clavier, et le milieu de la fenêtre défile (nom de l'opération, champ, aide, texte). Le champ remonte pour rester visible en entier.
- Clavier fermé, la fenêtre reprend sa hauteur et redevient celle de l'aperçu validé `tour5/img/05-entree-sans-nom` (image 03).
- Là où la fenêtre tient déjà (360 et 411 dp, texte normal), rien ne change.
- Au plus grand texte d'Android, sur 320 dp (image 04) : il ne reste qu'une bande entre le titre et les boutons ; le champ y tient, l'aide et le texte se lisent en faisant glisser le milieu. Clavier fermé, le texte coupé d'aujourd'hui se lit en entier en faisant glisser.

Une variante où le titre défile aussi (seuls les boutons restent en place) a été examinée : sur 320 dp au plus grand texte, elle donne la même image à quelques pixels près. Il n'y a donc pas de choix à faire.

## La question

**k1. Ces aperçus vous conviennent-ils ?** Oui : le code suit l'image 02 (et 04 pour le cas étroit). Non : dites ce qui ne va pas.

## Validation (2026-11-20)

Votre réponse du 2026-11-20, mot pour mot : « Oui, code A ».
La question était : « Je fais coder A comme sur les images 2 et 4 ? » (a : oui, code A ; b : non, à revoir).

Aperçus validés : `img/02-A-clavier-ouvert.png` et `img/04-etroit-texte-max.png` (l'image 03, clavier fermé, montre la fenêtre qui ne change pas). Codé en P39.16.
