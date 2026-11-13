# P39.1 — Remontée de la session « démonstration visuelle »

Pour la session principale. Maquettes seulement : aucun fichier Kotlin, `strings.xml`, ressource ou icône touché ; ni `docs/JOURNAL.md`, ni `docs/PLAN.md`, ni `docs/DECISIONS.md`, ni `version.properties`.

- Page : `docs/previews/p39-groupes/index.html` (autonome, 660 px de large, jour seulement).
- Images : `docs/previews/p39-groupes/img/*.png` (660 px, facteur 2, soit 1320 px de large), **toutes regardées une à une** après le dernier rendu.
- Vocabulaire proposé : **ensemble** ▸ **groupes** ▸ membres ; rôles **administrateur**, **leader d'ensemble**, **leader de groupe**, **membre**.
- Données inventées : ensemble « Raid des Trois Combes », groupes Aigle, Lynx, Chamois, onze prénoms inventés ; QR code = motif neutre (aucun lien) ; fond de carte = celui des maquettes existantes (`design-08-partage.html`, position par défaut de l'émulateur).

## (a) Images, dans l'ordre

| # | Fichier | Contenu |
|---|---|---|
| 1 | `01-hierarchie.png` | Actuel (groupe plat) ; proposé : arbre ensemble ▸ groupes ▸ membres, les quatre rôles, tableau « Qui peut faire quoi » (les cases orange renvoient aux questions ci-dessous) |
| 2 | `02-entree-A.png` | Point d'entrée, **A (recommandée)** : actuel, puis une carte « Ensemble » sous l'écran actuel |
| 3 | `02-entree-B.png` | Point d'entrée, B : deux onglets « Groupe simple / Ensemble » |
| 4 | `02-entree-C.png` | Point d'entrée, C : le choix au moment de créer (feuille « Que voulez-vous créer ? ») |
| 5 | `03-creation.png` | Actuel (l'invitation s'ouvre aussitôt), puis l'écran « Nouvel ensemble » (nom, groupes de départ) |
| 6 | `03-invitation-A.png` | Invitation A : un seul code, l'arrivant est « sans groupe » (qui invite, qui arrive) |
| 7 | `03-invitation-B.png` | Invitation **B (recommandée)** : on choisit le groupe d'arrivée avant de montrer le code |
| 8 | `03-invitation-C.png` | Invitation C : l'arrivant choisit son groupe |
| 9 | `04-admin-actuel.png` | Administration : écran actuel et ce qui est proposé, en texte |
| 10 | `04-admin-A.png` | Administration **A (recommandée)** : liste par groupe, fiche « Rôle et groupe » d'un membre |
| 11 | `04-admin-B.png` | Administration B : liste par membre, groupe changé sur la ligne |
| 12 | `04-admin-C.png` | Administration C : par rôle et par groupe (façon Discord), page d'un groupe |
| 13 | `05-liste-membre.png` | Liste : actuel, puis vue d'un membre simple |
| 14 | `05-liste-leaders.png` | Liste : vue d'un leader de groupe, vue du leader d'ensemble |
| 15 | `05-carte-actuel.png` | Carte : actuel, et ce qui distingue les trois variantes |
| 16 | `05-carte-A.png` | Carte **A (recommandée)** : le jeton prend la couleur du groupe (membre, leader d'ensemble) |
| 17 | `05-carte-B.png` | Carte B : couleur personnelle + pastille à la lettre du groupe |
| 18 | `05-carte-C.png` | Carte C : couleur personnelle, groupe écrit sur l'étiquette |
| 19 | `06-fiche-A.png` | Fiche d'un membre **A (recommandée)** : actuel, puis deux étiquettes (groupe, rôle) |
| 20 | `06-fiche-B.png` | Fiche B : une ligne de texte colorée, action « Voir son groupe » |
| 21 | `06-fiche-C.png` | Fiche C : bandeau à la couleur du groupe |
| 22 | `07-propagation.png` | Trajet d'un changement de rôle, téléphone hors réseau compris, et quatre « et si… » |

Dans toutes les maquettes proposées hors bande 5, les jetons sont dessinés avec la variante A de la carte (couleur du groupe, étoile, couronne). Si l'utilisateur choisit B ou C pour la carte, les listes suivent.

## (b) Questions à poser à l'utilisateur

La recommandation est toujours le choix 1. Les numéros Q2, Q3, Q5, Q6 et Q8 sont ceux des cases orange du tableau de `01-hierarchie.png`.

**Q1. Un membre peut-il rester sans groupe ?**
1. Oui : « Sans groupe » est un état normal (arrivant pas encore placé, personne de la coordination) ; il partage sa position et voit la carte. *(recommandé)*
2. Oui, mais seulement en attente : rappel aux administrateurs tant qu'il n'est pas placé.
3. Non : on ne peut entrer que dans un groupe (impose la variante B ou C de l'invitation).

**Q2. Qui voit la position de qui ?**
1. Tout le monde voit tout le monde ; la carte met son propre groupe en avant, les autres en petits points (sélecteur « Mon groupe / Tout l'ensemble »). *(recommandé : le plus sûr sur le terrain, et le seul qui ne demande pas une clé de chiffrement par groupe)*
2. Un membre voit son groupe et tous les leaders ; les leaders et les administrateurs voient tout.
3. Un membre ne voit que son groupe ; seul le leader d'ensemble voit tout.

À dire à l'utilisateur : les choix 2 et 3 ne sont une vraie confidentialité que si chaque groupe a sa propre clé (travail du moteur, P39.2) ; avec une seule clé, ce n'est qu'un filtre d'affichage.

**Q3. Un membre peut-il changer seul de groupe ?**
1. Non : seul un administrateur place les membres. *(recommandé)*
2. Il peut demander ; un administrateur (ou le leader du groupe visé) accepte.
3. Oui, librement.

**Q4. Combien d'ensembles à la fois, et avec un groupe simple en même temps ?**
1. Un seul partage actif à la fois : soit un groupe simple, soit un ensemble. *(recommandé pour une première version : une seule notification, un seul envoi de position)*
2. Un ensemble et un groupe simple en même temps.
3. Plusieurs ensembles en même temps.

**Q5. Qui peut écrire à tout l'ensemble ?**
1. Les administrateurs et les leaders (ensemble et groupes) ; un membre écrit à son groupe. L'alerte, elle, part toujours à tout l'ensemble, pour tous. *(recommandé)*
2. Tout le monde écrit à tout l'ensemble.
3. Le leader d'ensemble et les administrateurs seulement.

**Q6. Un leader de groupe peut-il inviter ou prendre quelqu'un dans son groupe ?**
1. Non : inviter et placer restent aux administrateurs. *(recommandé : un seul endroit où la répartition se décide)*
2. Il peut prendre dans son groupe un membre « sans groupe », et l'en retirer.
3. Il peut aussi inviter directement dans son groupe.

**Q7. Cumul et place des rôles**
1. Les rôles se cumulent : un administrateur est aussi membre ou leader ; le leader d'ensemble peut n'être dans aucun groupe (section « Coordination ») ; un seul leader par groupe, un seul leader d'ensemble. *(recommandé, c'est ce que montrent les maquettes)*
2. Le leader d'ensemble doit appartenir à un groupe.
3. Plusieurs leaders possibles par groupe (un leader et un adjoint).

**Q8. Que devient l'ensemble si le dernier administrateur part ?**
1. Il ne peut pas partir sans nommer un autre administrateur ; s'il disparaît sans prévenir (téléphone perdu), le leader d'ensemble devient administrateur après un délai à fixer. *(recommandé)*
2. L'administration passe d'office au leader d'ensemble, sinon au plus ancien membre.
3. L'ensemble est dissous pour tout le monde.

**Q9. Choix des variantes** (une réponse par ligne)
- Point d'entrée : **A** (carte sous l'écran actuel) · B (onglets) · C (choix au moment de créer).
- Invitation : **B** (groupe d'arrivée choisi par qui invite, « Sans groupe » possible) · A (toujours sans groupe) · C (l'arrivant choisit).
- Administration : **A** (par groupe) · B (par membre) · C (par rôle, façon Discord).
- Carte : **A** (couleur du groupe) · B (pastille) · C (étiquette).
- Fiche d'un membre : **A** (deux étiquettes) · B (ligne de texte et « Voir son groupe ») · C (bandeau coloré).

**Q10. Les mots**
1. « Ensemble », « Groupe », « Leader d'ensemble », « Leader de groupe », « Administrateur », « Membre » (mots de la demande). *(recommandé)*
2. « Chef » à la place de « Leader ».
3. D'autres mots, à donner par l'utilisateur (par exemple « Équipe » pour les groupes).

**Q11. Les messages**
1. Deux fils : « Mon groupe » et « Tout l'ensemble ». *(recommandé)*
2. Un seul fil pour tout l'ensemble, comme aujourd'hui.
3. Un fil par groupe, lisible par tous.

**Q12. Un groupe sans leader, ou dont le leader est hors ligne**
1. Permis : le groupe s'affiche « sans leader » chez les administrateurs, rien n'est bloqué. *(recommandé)*
2. Un leader est obligatoire : on ne peut pas retirer le leader sans en nommer un autre.
3. Si le leader est hors ligne depuis un délai à fixer, le membre le plus ancien du groupe porte l'étoile en attendant.

**Q13. Taille visée** (le groupe actuel vise 10 membres)
1. Jusqu'à 30 membres et 6 groupes pour la première version. *(recommandé, à confirmer par le moteur)*
2. 10 membres comme aujourd'hui.
3. Plus de 30 (à chiffrer : cadence d'envoi, lisibilité de la carte).

## (c) Ce qui n'a pas pu être vérifié

- **Accord avec le moteur (P39.2)** : la bande 7 (décisions numérotées, gardées par le serveur, rejouées au retour, la dernière gagne, auteur vérifié) est une description en langage courant, calquée sur les points du groupe (`p2p-sharing.md` § 12). Je n'ai pas lu la branche du moteur : à accorder avec `docs/specs/group-hierarchy.md` avant de montrer l'image, surtout la règle de départage entre deux administrateurs et le cas de l'administrateur révoqué pendant qu'il est hors réseau.
- **Écran « Actuel »** : redessiné d'après `design-08-partage.html` (variante B « Cordée ») et `ui-index.md`, pas d'après une capture de l'émulateur (pas d'émulateur en session cloud). Les lignes « Dans le groupe » (P27) et « Partager dans le groupe » ne sont pas dessinées, pour garder l'image lisible.
- **Thème de nuit** : non dessiné.
- **Mode gants, TalkBack, grandes polices** : non traités dans les maquettes.
- **Mode direct (WebRTC, sans serveur)** : les maquettes supposent le serveur de coordination ; rien n'est dit d'un ensemble en mode direct.
- **Ce que les maquettes changent dans l'écran actuel** : en variante A du point d'entrée, la légende « Rejoindre un groupe » devient « Rejoindre un groupe ou un ensemble » ; c'est le seul texte actuel touché.
- **Rendu** : Chromium headless (Playwright), police Roboto embarquée au rendu ; `index.html` charge Roboto par Google Fonts comme les autres pages de `docs/previews/` et retombe sur la police du système sans réseau.
- **`CI=true ./gradlew help` n'a pas pu tourner** dans ce conteneur : le SDK Android n'est pas installé et son téléchargement est refusé par le réseau de la session (403), le plugin Android ne se résout pas. Vérifié à la place par lecture de `appSourceFingerprint` (`app/build.gradle.kts`) : l'empreinte ne couvre que les fichiers Gradle de la racine, les modules `app`, `core`, `data`, `p2p`, `engine` et deux sortes de lignes de `docs/JOURNAL.md` ; `docs/previews/` n'y entre pas, `version.properties` reste à 0.4.236. Le run CI le confirme (voir (d)).
- **Poids des images** : PNG réduits à une palette de 256 couleurs (5,4 Mo en tout au lieu de 14) ; relus après réduction.
- **Icônes** : icônes Material « filled » ; la couronne est un tracé fait pour la maquette (pas une icône de l'application).

## (d) Branche et commit

- Branche : `claude/practical-ptolemy-kvydc9`, partie de `origin/conception/groupes-hierarchiques` (`2806dc8`, 0.4.236) ; `.conception-privee` présent à la racine. Aucun push sur `main` ni sur `conception/groupes-hierarchiques`, pas de PR, pas de tag, pas de release.
- Commit des maquettes : `41ce597` (« [P39.1] Groupes hiérarchiques : maquettes de l'ensemble, des groupes et des rôles… »).
- Run « CI » de ce commit : **n° 179, identifiant 43438861141**, vert (2026-11-13, 14:01 à 14:16 UTC) : style, lint, tests, APK de debug et de release, contrôle sur émulateur réussis ; étape **« Publish the Android release » sautée** (`skipped`) : aucune release publiée. L'empreinte des sources correspond donc à `version.properties` (0.4.236), ce que `./gradlew help` n'avait pas pu vérifier ici.
- Le commit qui ajoute ces lignes ne touche que ce fichier ; son run CI n'est pas consigné ici.
