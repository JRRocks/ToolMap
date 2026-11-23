# P39.14 — Opération : tour 8, couleur des groupes, notification, « Scanner un code », départ gardé (L1, L4 + M8, L5, M4)

Vos réponses du 2026-11-16 (`docs/specs/group-hierarchy.md`, §§ 19, 21 et 23) :
- L1 : la couleur d'un groupe se choisit et part à tous les téléphones ;
- L4 : la position d'une opération part aussi écran éteint, avec une notification comme « Entre amis », dessinée d'abord ;
- M8 : le mode « Opération » suit le réseau comme « Entre amis » ;
- L5 : « Scanner un code » passe sur deux lignes quand il ne tient pas ; c'est un écran de la version publiée, d'où l'aperçu ;
- M4 : un départ est gardé jusqu'à ce qu'un autre téléphone l'ait reçu (« En train de partir »), une semaine au plus.

Aperçus : `index.html` (à ouvrir dans un navigateur) et `img/` (une image par bande, 01 à 16). Les captures de l'émulateur sont dans `captures/` :
- « Entre amis » : version publiée 0.4.235, à 320, 340, 345, 360 et 411 dp de large, et avec un texte d'Android agrandi ;
- mode « Opération » : APK de debug 0.4.276, avec l'opération inventée « Exercice Vallon » ; certaines viennent du contrôle sur émulateur de la CI.

Les images « Proposé » sont des **captures retouchées** : des morceaux de ces captures assemblés, avec les textes, les pastilles et les cartes nouveaux dessinés par-dessus. Le téléphone d'essai est en anglais : « now », « Silent » et « Clear all » viennent d'Android, pas de ToolMap.

## L1, la couleur d'un groupe (images 01 à 05)

**Ce qu'on voit aujourd'hui** (image 01) : « Nouveau groupe » et « Renommer le groupe » ne demandent que le nom. Chaque groupe prend la première couleur libre, dans l'ordre : bleu, orange, vert, violet, rose, brun, cyan, olive. L'aide de « Renommer » dit : « Ses membres et sa couleur ne changent pas. »

**Proposé A** (images 02 et 03) : huit pastilles sous le nom, dans la feuille.
- La couleur que le groupe aurait aujourd'hui est cochée d'office : sans y toucher, rien ne change.
- Les couleurs des autres groupes sont pâles et ne se choisissent pas : deux groupes n'ont jamais la même couleur.
- La feuille s'appelle « Modifier le groupe ». La ligne de la page du groupe aussi.
- Les mêmes pastilles servent pour les groupes de départ, dans « Nouvelle opération ».
- Sur un écran plus étroit, les pastilles passent sur deux lignes de quatre.

**Proposé B** (image 04) : la feuille du nom ne change pas.
- La page du groupe gagne une ligne « Couleur du groupe », avec la pastille et le nom de la couleur.
- Cette ligne ouvre une petite feuille de huit pastilles. Toucher une pastille change la couleur tout de suite.
- « Nouveau groupe » reste comme aujourd'hui.

**Ce que voient les autres** (image 05) :
- Le créateur et les administrateurs changent la couleur, comme le nom.
- Elle part à tous les téléphones. Le groupe prend sa nouvelle couleur partout : carte, page de l'opération, listes.
- Proposé : un message en bas de l'écran le dit pendant quelques secondes : « Le groupe Ouest a changé de couleur : rose. »

## L4 et M8, la notification d'une opération (images 06 à 09)

**Ce qu'on voit aujourd'hui** (image 06) :
- Avec « Entre amis », une notification reste dans le volet : « ToolMap est actif en arrière-plan », « Position partagée · 0 membres connectés », « Arrêter le partage ».
- En opération, il n'y en a pas : la position ne part que tant que l'application est ouverte.

**Proposé** : la même notification, à la même place, avec le même titre et le même bouton.
- A (image 07) : « Position partagée · Exercice Vallon ».
- B (image 07) : la même ligne, plus « Groupe Nord · toutes les minutes ».
- C (image 08) : « Position partagée » seulement. Sur l'écran verrouillé, rien ne dit à qui voit le téléphone que vous êtes en opération. Android impose une notification tant que la position part écran éteint : on peut seulement choisir ce qu'elle dit.
- **Quand elle paraît** : dès que « Partager ma position » est actif dans l'opération, application ouverte ou non.
- **Quand elle part** :
  - quand on coupe le partage (l'interrupteur, ou « Arrêter le partage ») ;
  - à la dissolution de l'opération ;
  - quand on en est retiré ;
  - quand on la quitte, une fois le départ reçu.
- **Toucher la notification** ouvre la page de l'opération.
- **« Arrêter le partage »** coupe l'envoi de votre position ; vous restez dans l'opération.

**Sans réseau** (image 09) :
- Le mode « Opération » suivra le réseau comme « Entre amis » : la position repart dès le retour du réseau.
- Aujourd'hui, la notification d'« Entre amis » ne dit rien de la coupure ; seul l'écran du partage l'indique (« Serveur injoignable (réseau coupé ?) »).
- Proposé : une ligne « Hors réseau : envoi dès son retour » tant que le réseau manque.

## L5, « Scanner un code » (images 10 à 12)

**Ce qu'on voit aujourd'hui** (image 10), dans « Entre amis » de la version publiée :
- le bouton affiche « Scanner un », la fin manque ;
- avec le texte normal, il est coupé jusqu'à 340 dp de large et entier dès 345 dp ;
- avec le texte d'Android à 115 %, il est coupé aussi à 360 dp ;
- à 411 dp, il est entier même à 130 %.

**Proposé** : le texte passe sur deux lignes, seulement quand il ne tient pas. Partout où il tient, rien ne change (image 12, 345 dp).
- A (image 11) : « Scanner / un code ». Les deux boutons ont la même hauteur.
- B (image 11) : « Scanner un / code », la coupure qu'Android ferait tout seul. Les deux boutons ont la même hauteur.
- C (image 12) : la coupure de A, mais seul « Scanner un code » grandit ; « Coller un lien » garde sa hauteur.

## M4, « En train de partir » (images 13 à 16)

**Ce qu'on voit aujourd'hui** (image 13) :
- On confirme « Quitter l'opération », et le téléphone efface tout aussitôt : l'écran du partage revient à « Aucun groupe », sans message.
- Si aucun autre téléphone n'a reçu le départ (pas de réseau à ce moment-là), les autres ne l'apprennent jamais.
- Si c'était le créateur, plus personne ne peut retirer un membre, nommer un administrateur ni dissoudre.

**Proposé, commun à A, B et C** :
- Le téléphone garde le départ et le renvoie tout seul, même application fermée, jusqu'à ce qu'un autre téléphone l'ait reçu.
- Pendant l'attente, votre position ne part plus et vous ne recevez plus rien.
- Une notification sans bouton le dit (image 14) : « Départ de « Exercice Vallon » en cours », « En attente d'un autre téléphone », « Au plus tard le dimanche 29 novembre ».
- **Reçu** (image 16) : le téléphone efface tout, la notification part, et un message le dit si l'application est ouverte : « Votre départ de « Exercice Vallon » est reçu : tout est effacé de ce téléphone. »
- **7 jours sans réponse** (image 16) : le téléphone efface tout quand même. À la prochaine ouverture, une fenêtre « Départ non reçu » le dit et prévient que les autres vous voient peut-être encore.
- **Le créateur** qui passe la main (« Transmettre à Membre 5 et quitter ») attend de la même façon. Membre 5 ne devient créateur qu'une fois le départ reçu.

**Ce que montre l'application pendant l'attente** :
- A (image 14) : l'écran de l'opération laisse place à une seule carte, « En train de partir… ». Le titre garde le nom de l'opération, sous lequel est écrit « En train de partir ».
- B (image 15) : l'écran du partage revient à « Aucun groupe » dès la confirmation. Une bande sur la carte montre l'attente.
- C (image 15) : l'application est comme aujourd'hui ; seule la notification montre l'attente.

## Les questions

**L1. Pour choisir la couleur d'un groupe, je fais coder laquelle ?**
- A : les pastilles dans la feuille « Modifier le groupe », et dans « Nouveau groupe » (conseillé)
- B : une ligne « Couleur du groupe » sur la page du groupe, qui ouvre une petite feuille

**L1 bis. Quand la couleur d'un groupe change, les autres voient-ils un message ?**
- a : oui, « Le groupe Ouest a changé de couleur : rose. » (conseillé)
- b : non, la couleur change sans message

**L4. Que dit la notification d'une opération ?**
- A : « Position partagée · Exercice Vallon » (conseillé)
- B : la même ligne, plus le groupe et le rythme d'envoi
- C : « Position partagée », sans le nom de l'opération

**L4 bis (M8). Sans réseau, la notification le dit-elle ?**
- a : oui, « Hors réseau : envoi dès son retour » (conseillé)
- b : non, comme « Entre amis »

**L5. Comment « Scanner un code » passe-t-il sur deux lignes ?**
- A : « Scanner / un code », les deux boutons à la même hauteur (conseillé)
- B : « Scanner un / code », les deux boutons à la même hauteur
- C : « Scanner / un code », seul le bouton de gauche grandit

**M4. Pendant qu'un départ attend, que montre l'application ?**
- A : l'écran de l'opération réduit à une carte « En train de partir… » (conseillé)
- B : l'opération fermée, une bande sur la carte
- C : rien, seulement la notification
