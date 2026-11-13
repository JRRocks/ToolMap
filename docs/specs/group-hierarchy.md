# Groupes hiérarchiques du partage de position (P39)

> Demande de l'utilisateur (2026-11-13) : `docs/PLAN.md` § P39. Décision d'architecture : ADR-125.
> Ce document décrit le **moteur** (P39.2) : la logique, dans `:core`, paquet `fr.toolmap.core.hierarchy`. Rien n'est branché : ni transport, ni stockage, ni écran. Le système de groupe actuel (`fr.toolmap.core.p2p`, `docs/specs/p2p-sharing.md`) n'est pas modifié.
> Les choix laissés à l'utilisateur sont au § 10 ; tant qu'il n'a pas répondu, le moteur applique la valeur « de départ » indiquée.

## 1. Vocabulaire

| Mot | Sens | Dans le code |
|---|---|---|
| Ensemble | Le tout : un nom, des membres, des groupes | `HierarchyState` |
| Groupe | Un enfant de l'ensemble : un nom, des membres, un leader | `Group` |
| Membre | Un téléphone : identifiant stable sur 64 bits, nom affiché, zéro ou un groupe | `Member` |
| Opération | Un changement demandé par un membre, tel qu'il voyage et se garde | `Op`, `Change` |
| Copie | Ce qu'un téléphone garde d'un ensemble : toutes les opérations reçues et l'état qu'elles donnent | `HierarchyReplica` |

## 2. Modèle

- **Ensemble** : identifiant (64 bits), nom, leader d'ensemble (un, ou poste vacant), administrateurs (au moins un, toujours), règles (§ 4).
- **Groupes** : identifiant (64 bits, tiré au hasard par le téléphone qui le crée, jamais 0), nom, parent, leader (un, ou aucun). Au plus 64 groupes.
- **Membres** : identifiant, nom, groupe (un seul, ou aucun). Au plus 256 membres.
- **Parent-enfant** : chaque groupe porte son parent (`Group.parent`). Aujourd'hui le parent est toujours l'ensemble (`HierarchyState.ROOT`) : un seul niveau. Un niveau de plus (des groupes dans un groupe) ne demande pas un autre format : le champ voyage déjà ; il faudra lever le refus `UNSUPPORTED_DEPTH` et écrire les règles des sous-groupes (suppression d'un parent, leader d'un parent).
- **Noms** (ensemble, groupe, membre) : non vides, sans caractère de contrôle, 64 octets UTF-8 au plus (`Names`).
- L'identifiant d'un groupe supprimé ne revient jamais (`deletedGroups`) : une opération en retard, écrite pour l'ancien groupe, ne peut pas viser un autre groupe par accident.

Un état construit par le moteur est toujours cohérent ; `HierarchyState.inconsistency()` le vérifie (tests, et lecture d'un état venu d'octets).

## 3. Rôles

Les rôles ne sont pas stockés : ils se lisent dans l'état (`HierarchyState.rolesOf`). Un membre en cumule plusieurs.

| Rôle | Qui | Combien |
|---|---|---|
| Administrateur (`ADMIN`) | Dans la liste des administrateurs. Le créateur de l'ensemble est le premier | Un ou plusieurs, jamais zéro |
| Leader d'ensemble (`ENSEMBLE_LEADER`) | Nommé par une opération. Le créateur l'est au départ | Un, ou poste vacant |
| Leader de groupe (`GROUP_LEADER`) | Nommé pour un groupe ; il doit en être membre | Un par groupe, ou aucun |
| Membre (`MEMBER`) | Tout membre admis | Tous |

Un leader qui change de groupe, sort de son groupe ou quitte l'ensemble perd son poste ; le groupe reste, sans leader.

## 4. Droits et visibilité : deux tables de données

Les deux tables sont des **données**, pas du code : changer une réponse du § 10, c'est changer une case. Elles voyagent dans la première opération de l'ensemble (§ 5) : tous les téléphones d'un ensemble appliquent la même table, même si leurs versions de l'application n'ont pas la même table de départ.

### 4.1 Qui peut faire quoi (`RightsTable`)

Une case par opération et par rôle. Une case contient zéro, une ou plusieurs **portées** :

| Portée | Sens |
|---|---|
| `ANYONE` | Quel que soit le membre ou le groupe concerné |
| `OWN_GROUP` | Seulement si le groupe concerné est le groupe de l'auteur (celui dont il est membre, donc celui qu'il mène) |
| `SELF` | Seulement si le membre concerné est l'auteur lui-même (partir, renoncer à un poste, choisir son groupe) |
| (vide) | Jamais |

Un auteur a le droit si **l'un** de ses rôles a une case qui couvre l'opération. Quelqu'un qui n'est pas membre n'a aucun droit.

**Table de départ** (`RightsTable.DEFAULT`), prudente : seuls les administrateurs changent les rôles, les groupes et les affectations.

| Opération | Administrateur | Leader d'ensemble | Leader de groupe | Membre |
|---|---|---|---|---|
| Admettre un membre (`ADMIT_MEMBER`) | tous | — | — | — |
| Retirer un membre (`REMOVE_MEMBER`) | tous | — | — | soi-même (quitter) |
| Nommer un administrateur (`ADD_ADMIN`) | tous | — | — | — |
| Retirer un administrateur (`REMOVE_ADMIN`) | tous (jamais le dernier) | — | — | — |
| Créer un groupe (`CREATE_GROUP`) | tous | — | — | — |
| Renommer un groupe (`RENAME_GROUP`) | tous | — | — | — |
| Supprimer un groupe (`DELETE_GROUP`) | tous | — | — | — |
| Affecter un membre à un groupe (`ASSIGN_MEMBER`) | tous | — | — | — |
| Sortir un membre de son groupe (`UNASSIGN_MEMBER`) | tous | — | — | — |
| Nommer un leader de groupe (`SET_GROUP_LEADER`) | tous | — | — | — |
| Retirer un leader de groupe (`CLEAR_GROUP_LEADER`) | tous | — | — | — |
| Nommer le leader d'ensemble (`SET_ENSEMBLE_LEADER`) | tous | — | — | — |

Au départ, « leader » est donc un titre, sans pouvoir : c'est à l'utilisateur de dire ce qu'il ouvre (§ 10, questions 1 à 3). Exemple d'une autre table, vérifiée par les tests : `ASSIGN_MEMBER` à `OWN_GROUP` pour le leader de groupe (il fait entrer des membres dans son groupe, pas dans un autre), `UNASSIGN_MEMBER` à `SELF` pour le membre (il sort seul de son groupe).

Pour `OWN_GROUP`, le groupe concerné est : le groupe nommé (renommer, supprimer, affecter **vers** ce groupe, nommer ou retirer son leader), ou le groupe d'où sort le membre (sortir, retirer).

### 4.2 Qui voit la position de qui (`VisibilityTable`)

Pour chaque rôle de celui qui regarde, les **publics** qu'il voit :

| Public | Qui |
|---|---|
| `SAME_GROUP` | Les membres de son propre groupe |
| `GROUP_LEADERS` | Les leaders de groupe |
| `ENSEMBLE_LEADER` | Le leader d'ensemble |
| `ADMINS` | Les administrateurs |
| `EVERYONE` | Tous les membres |

`HierarchyState.canSee(viewer, target)` est une fonction pure : vrai si l'un des rôles de `viewer` voit l'un des publics dont `target` fait partie. Chacun se voit toujours. Quelqu'un hors de l'ensemble ne voit personne et n'est vu de personne. Deux membres sans groupe ne sont pas « du même groupe ».

**Table de départ** (`VisibilityTable.DEFAULT`) : tout le monde voit tout le monde (`EVERYONE` pour chaque rôle).

Limite à connaître : cette table dit ce que l'application **montre**. Tant que tous les membres partagent la même clé (§ 9), un membre qui modifierait son application pourrait lire les positions qu'on ne lui montre pas. Une vraie confidentialité entre groupes demande une clé par groupe (§ 8.3).

## 5. Opérations

Chaque opération (`Op`) porte : l'ensemble, son **auteur**, son **numéro** chez cet auteur (`seq` : 1, 2, 3…, sans trou), l'**horloge logique** de l'auteur (`clock` : un de plus que toutes les opérations qu'il connaissait), et le changement.

| Changement (`Change`) | Effet | Refusé quand (en plus des droits) |
|---|---|---|
| `CreateEnsemble(nonce, name, creatorName, rules)` | Crée l'ensemble ; l'auteur devient membre, premier administrateur et leader d'ensemble ; fixe les règles | Ensemble déjà créé ; pas la première opération de l'auteur, ou identifiant d'ensemble qui ne correspond pas (`BAD_GENESIS`) |
| `AdmitMember(member, name)` | Ajoute un membre, sans groupe | Déjà membre ; 256 membres |
| `RemoveMember(member)` | Le sort de l'ensemble, de son groupe, de ses postes, des administrateurs | Inconnu ; dernier administrateur |
| `AddAdmin(member)` | Le nomme administrateur | Pas membre ; déjà administrateur |
| `RemoveAdmin(member)` | Lui retire ce rôle | Pas administrateur ; **dernier administrateur** |
| `CreateGroup(group, name, parent)` | Crée un groupe sous l'ensemble | Identifiant pris ou supprimé ; 64 groupes ; parent autre que l'ensemble |
| `RenameGroup(group, name)` | Renomme | Groupe inconnu ; même nom |
| `DeleteGroup(group)` | Supprime ; **ses membres restent dans l'ensemble, sans groupe** | Groupe inconnu |
| `AssignMember(member, group)` | Le met dans ce groupe, donc hors de l'ancien (il cesse de le mener) | Membre ou groupe inconnu ; déjà dedans |
| `UnassignMember(member)` | Le sort de son groupe | Inconnu ; sans groupe |
| `SetGroupLeader(group, member)` | Le nomme leader, à la place du précédent | Groupe ou membre inconnu ; **pas membre de ce groupe** |
| `ClearGroupLeader(group)` | Le groupe n'a plus de leader | Groupe inconnu ; pas de leader |
| `SetEnsembleLeader(member)` | Le nomme leader d'ensemble, à la place du précédent | Pas membre ; déjà leader |

- Une opération refusée ne change rien et garde son motif (`Rejection`), le même sur tous les téléphones : `HierarchyReplica.history()` permet de dire à l'auteur « votre changement n'a pas été retenu ».
- `HierarchyReplica.issue(change)` vérifie d'abord sur le téléphone de l'auteur : un changement qu'il sait refusé n'est pas écrit du tout. `HierarchyReducer.check(state, author, change)` répond sans rien écrire (pour griser un bouton).
- Un membre retiré peut être admis de nouveau : il revient simple membre.
- Le nom d'un membre est celui donné à l'admission ; il n'y a pas d'opération pour le changer (à ajouter si l'interface le demande : un code de changement de plus).
- **Identifiant de l'ensemble** : 8 octets de SHA-256 de (auteur, contenu de `CreateEnsemble`). Un ensemble n'a donc qu'une première opération possible : personne ne peut en écrire une autre (autre créateur, autre nom, autres règles) pour le même identifiant, et qui reçoit l'identifiant (invitation) sait que les règles sont celles prévues. `nonce` est un nombre au hasard qui rend l'identifiant unique.

## 6. Accord entre téléphones, sans autorité centrale

### 6.1 La règle

1. Chaque téléphone **garde toutes les opérations** reçues, de tous les auteurs.
2. Il les applique dans un **ordre unique**, le même partout : par horloge croissante, puis par identifiant d'auteur (non signé), puis par numéro (`Op.ORDER`).
3. Chaque opération est vérifiée contre les droits de son auteur **dans l'état où elle s'applique** (pas dans celui que voyait l'auteur) ; refusée, elle ne change rien.
4. L'état est donc une fonction de **l'ensemble** des opérations détenues, pas de leur ordre d'arrivée : deux téléphones qui détiennent les mêmes opérations ont le même état. Quand une opération arrive « dans le passé », le téléphone rejoue (`HierarchyReplica.replay`) ; quand elle arrive à la fin, il l'applique seule.

Compléments :

- Seules les opérations d'un auteur **sans trou** sont appliquées (1, 2, 3…) ; une opération en avance attend (`HELD`) que le rattrapage apporte les précédentes.
- L'horloge d'un auteur avance toujours. Si l'ordre place une opération après une opération plus récente du même auteur (horloge qui recule : bogue ou triche), elle est refusée (`OUT_OF_ORDER`) et l'auteur continue.
- **Deux opérations différentes sous le même numéro d'un même auteur** (téléphone restauré d'une sauvegarde qui réécrit par-dessus, ou triche) : une seule est gardée, la même partout : la vraie première opération de l'ensemble d'abord, puis une opération prouvée (§ 8) contre une qui ne l'est pas, puis celle dont les octets viennent en premier.

### 6.2 Cas limites tranchés (chacun a son test, dans les deux ordres d'arrivée)

| Cas | Ce qui se passe, sur tous les téléphones |
|---|---|
| Deux administrateurs affectent le même membre en même temps | Les deux s'appliquent, dans l'ordre ; la dernière dans l'ordre reste (à horloge égale : celle de l'auteur au plus grand identifiant). Avant d'avoir échangé, chacun voit son propre choix |
| Un administrateur est retiré pendant qu'il agit | Ce que l'ordre place **avant** son retrait reste ; ce qu'il place **après** est refusé (`FORBIDDEN`), y compris sur son propre téléphone, qui l'avait d'abord montré |
| Deux administrateurs se retirent l'un l'autre en même temps | Le premier dans l'ordre l'emporte, l'autre retrait est refusé : il reste un administrateur. Il ne peut jamais y en avoir zéro |
| Un groupe est supprimé pendant qu'on y affecte quelqu'un | Suppression d'abord : l'affectation est refusée (`NO_SUCH_GROUP`). Affectation d'abord : elle s'applique, puis la suppression remet le membre sans groupe. Dans les deux cas : plus de groupe, membre sans groupe |
| Un membre est déplacé pendant qu'on le nomme leader du groupe qu'il quitte | Selon l'ordre : nommé puis déplacé (il perd le poste), ou déplacé puis nomination refusée (`NOT_IN_GROUP`). Dans les deux cas le groupe n'a pas de leader |
| Opération rejouée (reçue deux fois) | Reconnue (`DUPLICATE`), rien ne change |
| Opération d'un auteur sans droit, non membre ou retiré | Gardée (pour que tous aient les mêmes opérations), refusée (`FORBIDDEN`, `NOT_A_MEMBER`), ne change rien |
| Fausse création de l'ensemble | Refusée (`BAD_GENESIS`) : l'identifiant de l'ensemble ne correspond qu'à la vraie |

Test de convergence (`HierarchyReplicaTest`, 60 graines fixes) : cinq téléphones et trois auteurs sans téléphone écrivent au hasard, avec ou sans droit, parfois deux opérations sous un même numéro, horloges parfois en arrière, échanges partiels ; à la fin toutes les opérations sont remises à huit téléphones neufs dans huit ordres différents : un seul état, les mêmes refus, la même empreinte, et les cinq téléphones d'origine y arrivent aussi après rattrapage.

### 6.3 Retour d'un téléphone hors réseau

Échange d'état en deux messages (`Summary`, puis opérations) :

1. Le téléphone envoie son **résumé** (`HierarchyReplica.summary()`) : pour chaque auteur, combien d'opérations il détient sans trou, et une empreinte (8 octets de SHA-256) de ces opérations.
2. L'autre répond par **ce qui manque** (`missingFor(résumé)`) : les opérations au-delà de ces nombres, par blocs (`HierarchyWire.MAX_BATCH`, ou moins selon la trame).
3. Et inversement. On recommence tant que l'un a quelque chose à envoyer.

Si les nombres sont égaux et les empreintes différentes, un auteur a écrit deux opérations sous un même numéro : tout est renvoyé, et la règle du § 6.1 garde la même des deux côtés.

Le téléphone revenu peut avoir écrit des opérations hors réseau : elles prennent leur place dans l'ordre comme les autres, et peuvent être refusées si, entre-temps, leur auteur a perdu le droit.

### 6.4 Limites connues

- **Journal qui grossit** : toutes les opérations sont gardées (au plus 20 000 par ensemble, `MAX_OPS` ; au-delà le téléphone n'en prend plus). Les changements de rôles sont rares, la limite est loin ; un « point de reprise » (état signé qui remplace le passé) sera à écrire si l'usage le demande.
- **Antidatage par un administrateur retiré** : un administrateur retiré et malveillant peut écrire, après coup, une opération datée d'avant son retrait (par exemple nommer un complice). La signature (§ 8) prouve l'auteur, pas la date. Parade tant que rien d'autre n'existe : refaire un ensemble. Parade à écrire si le besoin est réel : § 8.3.
- **Horloge poussée au maximum** : un membre malveillant peut écrire une opération d'horloge maximale ; plus personne ne peut alors écrire dans cet ensemble (`LOG_FULL`). Même parade.
- **Journal rempli exprès** : un membre malveillant peut écrire des milliers d'opérations sans effet jusqu'à la borne ; l'ensemble ne prend alors plus de changement. Même parade ; la signature dit au moins qui l'a fait.
- **Deux téléphones avec le même identifiant** (sauvegarde restaurée sur un second appareil) : ils écrivent sous les mêmes numéros ; une seule opération de chaque paire reste. Un téléphone restauré doit se rattraper avant d'écrire.
- **Opération d'un type inconnu** (version plus récente) : refusée au décodage, donc non gardée ; le téléphone doit être mis à jour pour suivre. Ajouter un type d'opération demande donc de monter la version du format.

## 7. Format binaire (`HierarchyWire`)

Gros-boutiste, comme `Wire`. Tout bloc commence par `version (1) = 1 | genre (1)`. Chaînes : longueur sur 1 octet puis UTF-8. Le format n'est **pas** branché au transport : ces octets sont la charge utile d'un futur type de message (§ 9).

| Genre | Contenu |
|---|---|
| 1 `OP` | une opération |
| 2 `OPS` | `n (2)` puis `n × (longueur (2), opération)` ; `n ≤ 512` |
| 3 `SUMMARY` | `ensemble (8) | empreinte (8) | n (2) | n × (auteur (8), nombre (4))`, auteurs par identifiant croissant ; `n ≤ 4096` |
| 4 `STATE` | un état complet (ci-dessous) |

**Opération** : `longueur du contenu (2) | contenu | longueur de la clé (1) | clé publique | longueur de la signature (1) | signature` (0 et 0 sans preuve).

**Contenu** (ce qui est signé et comparé) : `ensemble (8) | auteur (8) | numéro (4) | horloge (4) | code (1) | corps`.

| Code | Changement | Corps |
|---|---|---|
| 1 | `CreateEnsemble` | `nonce (8) | nom | nom du créateur | règles` |
| 2 | `AdmitMember` | `membre (8) | nom` |
| 3 / 4 / 5 | `RemoveMember` / `AddAdmin` / `RemoveAdmin` | `membre (8)` |
| 6 | `CreateGroup` | `groupe (8) | parent (8) | nom` |
| 7 | `RenameGroup` | `groupe (8) | nom` |
| 8 | `DeleteGroup` | `groupe (8)` |
| 9 | `AssignMember` | `membre (8) | groupe (8)` |
| 10 | `UnassignMember` | `membre (8)` |
| 11 | `SetGroupLeader` | `groupe (8) | membre (8)` |
| 12 | `ClearGroupLeader` | `groupe (8)` |
| 13 | `SetEnsembleLeader` | `membre (8)` |

**Règles** : `drapeaux (1 ; bit 0 : opérations signées) | n (1) | n × (opération (1), rôle (1), portées (1))` pour les cases non vides, dans l'ordre des codes, puis `m (1) | m × (rôle (1), publics (1))`. Codes : rôles 1 à 4 (`Role`), opérations 1 à 12 (`Operation`), portées en bits 1 / 2 / 4 (`Reach`), publics en bits 1 / 2 / 4 / 8 / 16 (`Audience`).

**État** : `ensemble (8) | nom | leader (8 ; 0 : vacant) | règles | n (2) | n × (membre (8), nom, groupe (8 ; 0 : aucun), administrateur (1))` puis `g (2) | g × (groupe (8), parent (8), nom, leader (8 ; 0 : aucun))` puis `d (2) | d × groupe supprimé (8)`, tout par identifiant croissant : un même état donne toujours les mêmes octets. Il sert à garder l'état sur le disque pour un démarrage rapide ; l'accord entre téléphones passe par les opérations, jamais par l'état.

**Tailles** : un bloc `OP` sans preuve fait au plus 112 octets hors création (moins de 50 en général) ; avec preuve, environ 165 de plus (clé 91, signature 72 au plus).

**Entrées invalides** : le décodage ne lève qu'une `WireException`, quels que soient les octets (testé : coupures à chaque longueur, chaque octet modifié, bruit, bloc lu comme un autre genre). Sont refusés : version ou genre inconnu, données tronquées ou en trop, code inconnu, identifiant 0, nom refusé par `Names`, règle inconnue, demi-preuve, état incohérent, et toute opération qui n'est pas écrite de **la seule façon** dont cette version l'écrit (le décodeur réécrit l'opération et compare : la signature porte sur ces octets exacts).

## 8. Authentification : qui prouve qu'une opération vient bien d'un administrateur ?

### 8.1 Ce qui existe

`GroupCrypto` chiffre et authentifie chaque trame avec **la clé du groupe** (AES-GCM). Cela prouve « écrit par quelqu'un qui a la clé », donc par un membre : tous les membres ont la même clé. Rien ne distingue un membre d'un autre : l'identifiant de membre est un champ que l'émetteur remplit. Dans le système actuel c'est sans conséquence (tout membre peut déjà retirer n'importe qui) ; avec des rôles, un membre qui modifierait son application pourrait écrire une opération au nom d'un administrateur.

### 8.2 Ce qui est proposé, et déjà écrit (`OpAuth`, sans aucune dépendance nouvelle)

- Chaque téléphone a **sa paire de clés** ECDSA P-256 (fournie par la JVM et par Android depuis toujours ; pas de bibliothèque).
- **Son identifiant de membre est l'empreinte de sa clé publique** (8 octets de SHA-256). Admettre un membre par son identifiant, c'est donc admettre sa clé, sans annuaire.
- Chaque opération porte la clé publique de son auteur et sa **signature** du contenu. Vérifier (`OpAuth.verify`) : la clé a bien l'empreinte de l'auteur, et la signature est bonne.
- Un ensemble dont les règles disent `signedOps` **refuse sur tous les téléphones** (`UNAUTHENTICATED`) toute opération sans preuve valable ; c'est dans les règles de l'ensemble, donc dans son identifiant : on ne peut pas le désactiver après coup.
- Une copie d'une opération dont on a retiré la preuve ne masque pas la vraie (la copie prouvée la remplace) ; une fausse opération sous le numéro d'une vraie perd toujours contre la vraie.
- La clé du groupe continue de chiffrer le tout : la signature s'ajoute, elle ne remplace rien.

Testé (`OpAuthTest`) : opération signée reconnue ; refusées : sans preuve, preuve d'une autre opération, signée par un autre au nom de l'auteur, signature abîmée, clé qui n'en est pas une (signalé par `Problems.expected("hierarchy.auth.verify")`, sans plantage) ; ensemble signé qui ignore une opération forgée au nom de l'administrateur.

`signedOps` vaut **faux au départ** : rien n'appelle `OpAuth` tant que le § 8.3 n'est pas décidé.

### 8.3 Ce qui reste à décider

1. **Activer la signature** (§ 10, question 9). Recommandé : oui, dès la première version publiée ; l'activer plus tard obligerait à refaire les ensembles.
2. **Où vit la clé privée** : Android Keystore (clé non exportable, liée au téléphone ; `OpSigner` est fait pour cela) ou fichier de l'application. Keystore recommandé ; à écrire dans `:data` ou `:p2p` (P39.3 et suite), avec ce que devient l'identité à la restauration d'une sauvegarde (nouvelle clé, donc nouveau membre à admettre).
3. **Identifiant de membre** : le nouveau système doit utiliser l'empreinte de la clé, pas l'identifiant au hasard du système actuel. Largeur : 64 bits suffisent contre l'erreur et le bricolage, pas contre un adversaire qui calcule longtemps ; passer à 128 bits changerait le format (version 2).
4. **Antidatage et horloge au maximum** (§ 6.4) : la signature ne les empêche pas. Piste : une opération « point de reprise » signée par un administrateur, qui fige l'état et refuse tout ce qui est daté d'avant ; à écrire seulement si le besoin est réel.
5. **Confidentialité entre groupes** (§ 4.2) : une clé par groupe, distribuée par les administrateurs aux seuls membres du groupe (chiffrée pour la clé publique de chacun : P-256 le permet par ECDH, toujours sans dépendance), et renouvelée quand un membre sort. Seulement si l'utilisateur veut que les groupes ne se voient pas (§ 10, question 4).
6. **Retrait d'un membre** : comme aujourd'hui (`Msg.Rekey`), retirer quelqu'un de l'ensemble doit changer la clé du groupe, sinon il lit encore les positions.

## 9. Ce qu'il faudra pour brancher (P39.3 et suite)

Rien de ceci n'est fait.

**Transport** (`Wire.kt`, `MeshNode.kt`, `:p2p`) :

- Deux types de message nouveaux (les versions qui ne les connaissent pas les écartent déjà, comme `POINT`) : « opérations » (un bloc `OPS`) et « résumé » (un bloc `SUMMARY`), sur le canal fiable, inondés et dédupliqués comme un texte.
- À l'ouverture d'une liaison, après `HELLO` : envoyer son résumé, répondre par `missingFor`. Refaire de temps en temps (les opérations perdues se rattrapent ainsi).
- Taille : une trame fait 16 Kio au plus, et une trame de contrôle du serveur de coordination 4096 octets (`Msg.Diag.MAX_BYTES`) : appeler `missingFor(résumé, max)` avec `max` ≈ 12 opérations signées par trame du serveur, ≈ 50 par trame directe.
- Ce que `add` répond sert au journal d'événements : `CONFLICT_…`, `MALFORMED`, `FULL` sont à signaler (`Problems.expected`), `HELD` déclenche un rattrapage.
- `WireException` au décodage : à traiter comme pour les autres messages (trame écartée, signalée).
- Un ensemble est un partage **à part** du groupe actuel (« indépendant du système actuel ») : sa propre clé de groupe, sa propre invitation (qui porte l'identifiant de l'ensemble), ses propres trames. À décider : nouveau lien `toolmap://…` ou champ de plus dans l'actuel (§ 10, question 11).
- Admission : un administrateur écrit `AdmitMember` quand un nouveau téléphone se présente (automatique ou après accord : § 10, question 10). Tant qu'aucun administrateur n'est joignable, le nouveau venu n'est pas membre.
- Positions : `canSee` décide ce qui s'affiche ; sans clé par groupe, les positions voyagent comme aujourd'hui.

**Stockage** (`:data`, à côté de `share.points.json`) : garder les opérations (`HierarchyReplica.ops()` → `HierarchyWire.encodeOpBlocks`), relire par `HierarchyReplica.restore` ; l'état se recalcule. Effacer en quittant l'ensemble. À ajouter au format de sauvegarde (`backup-format.md`) si l'ensemble doit survivre à une restauration.

**Interface** (après validation des maquettes de P39.1, `CLAUDE.md` § « aperçu validé avant le code ») : tout se lit dans `HierarchyState` (`rolesOf`, `groupOf`, `membersOf`, `visibleTo`), se demande par `issue`, se grise par `HierarchyReducer.check`, et les refus arrivés après coup se lisent dans `history()`. Un bouton ne doit jamais supposer qu'un changement écrit est définitif : il peut perdre contre un changement simultané (§ 6.2).

**Fil d'exécution** : `HierarchyReplica` est synchronisée ; `HierarchyState` est immuable, on peut la donner telle quelle à l'interface (un `StateFlow` dans `:p2p`).

## 10. Questions pour l'utilisateur

À poser par la session principale. Le premier choix est le choix recommandé ; « départ » marque ce que le moteur fait aujourd'hui. Chaque réponse ne change qu'une case d'une table, sauf mention.

1. **Qui répartit les membres dans les groupes ?**
   a. Seulement les administrateurs (recommandé, départ).
   b. Les administrateurs, et chaque leader de groupe pour son propre groupe (il fait entrer et sortir des membres de son groupe).
   c. Les administrateurs et le leader d'ensemble.
   d. Chacun choisit son groupe lui-même.

2. **Que peut faire un leader de groupe ?**
   a. Rien de plus qu'un membre : c'est un titre, visible de tous (recommandé pour commencer, départ).
   b. Gérer son groupe : le renommer, y faire entrer et en faire sortir des membres.
   c. Comme b, et nommer lui-même son remplaçant.

3. **Que peut faire le leader d'ensemble ?**
   a. Rien de plus qu'un membre : c'est un titre (recommandé pour commencer, départ).
   b. Créer et supprimer des groupes, et nommer leurs leaders ; les administrateurs gardent le reste.
   c. Tout ce que fait un administrateur.

4. **Qui voit la position de qui sur la carte ?**
   a. Tout le monde voit tout le monde (recommandé, départ).
   b. Chacun voit son groupe et les leaders ; les leaders et les administrateurs voient tout le monde.
   c. Chacun ne voit que son groupe ; seuls le leader d'ensemble et les administrateurs voient tout le monde.
   Avec b ou c, ce n'est d'abord qu'un filtre d'affichage ; le rendre inviolable demande un travail de plus (une clé par groupe, § 8.3).

5. **Un administrateur peut-il retirer un autre administrateur ?**
   a. Oui, n'importe lequel, sauf le dernier (recommandé, départ).
   b. Seul le créateur de l'ensemble peut retirer un administrateur (demande une règle de plus dans le moteur).
   c. Non : un administrateur ne peut que renoncer lui-même.

6. **Un membre peut-il partir seul ?**
   a. Il peut quitter l'ensemble, pas changer de groupe (recommandé, départ).
   b. Il peut quitter l'ensemble et sortir de son groupe.
   c. Non : seul un administrateur le retire (il lui reste à quitter le partage sur son téléphone).

7. **Quand le leader d'ensemble s'en va ou est retiré :**
   a. Le poste reste vacant jusqu'à ce qu'un administrateur nomme quelqu'un (recommandé, départ).
   b. Le plus ancien administrateur devient leader tout seul (règle de plus dans le moteur).
   c. Il ne peut pas partir tant qu'un remplaçant n'est pas nommé (règle de plus).

8. **Un membre dans plusieurs groupes à la fois, ou des groupes dans un groupe ?**
   a. Non : un seul groupe par membre, un seul niveau de groupes, comme demandé (recommandé, départ).
   b. Des sous-groupes (le modèle le prévoit, les règles restent à écrire).
   c. Plusieurs groupes par membre (change le modèle et le format).

9. **Faut-il empêcher qu'un membre qui bricole son application se fasse passer pour un administrateur ?**
   a. Oui : chaque téléphone signe ses changements avec une clé qui lui est propre (recommandé ; écrit, à activer ; rien à faire pour l'utilisateur).
   b. Non : confiance entre membres, comme dans le groupe actuel.
   À décider avant la première version publiée : l'activer plus tard obligerait à recréer les ensembles.

10. **Comment entre-t-on dans un ensemble ?**
    a. Avec le lien ou le QR d'invitation, comme aujourd'hui ; l'arrivant est admis tout seul comme membre sans groupe dès que le téléphone d'un administrateur le voit (recommandé).
    b. Avec le lien ou le QR, puis un administrateur accepte chaque arrivée d'un geste.
    c. Un administrateur inscrit d'abord la personne, qui reçoit une invitation à son nom.

11. **Le groupe actuel et le nouvel ensemble :**
    a. Les deux existent côte à côte ; on choisit « groupe simple » ou « ensemble avec groupes » en créant un partage (recommandé : « indépendant du système actuel »).
    b. Le nouvel ensemble remplace le groupe actuel une fois au point (un ensemble sans groupe se comporte alors comme le groupe d'aujourd'hui).
    c. Les deux côte à côte, et un groupe actuel peut être transformé en ensemble.

12. **Combien de personnes au plus dans un ensemble ?**
    a. Une trentaine, comme le groupe actuel (recommandé : le transport actuel est fait pour cela).
    b. Une centaine (le moteur l'accepte ; le transport est à revoir : listes de membres, nombre de liaisons).
    c. Davantage (jusqu'à 256 pour le moteur ; transport à refaire).

## 10 bis. Réponses de l'utilisateur (2026-11-13)

Choix faits par l'utilisateur sur les maquettes de P39.1 (`docs/previews/p39-groupes/`) et sur les questions. **V1 à V5** : variantes des maquettes ; **R1 à R20** : fusion des questions de `docs/previews/p39-groupes/REMONTEE.md` (b) et de celles du § 10 ci-dessus (la numérotation R n'est donc pas celle du § 10 ; les intitulés complets sont dans ces deux documents). Bloc recopié tel quel :

```text
V1 A · Une carte « Ensemble » sous l'écran actuel
V2 A · Toujours « sans groupe »
V3 C · Par rôle et par groupe, façon Discord | précision : toujours avec 1 seul groupe final, donc une nouvelle attribution écrase l'ancienne
V4 A · Le jeton prend la couleur du groupe
V5 A · Deux étiquettes : groupe et rôle
R1 2 · Oui, mais seulement en attente | précision : Et il n'a pas accès aux partages avant d'avoir été répartie dans un groupe
R2 1 · Tout le monde voit tout le monde
R3 1 · Seulement les administrateurs
R4 (sans choix) | précision : Partager un croquis/waypoint/message à l'ensemble
R5 1 · Rien de plus : c'est un titre
R6 3 · Le leader d'ensemble et les administrateurs seulement
R7 1 · Deux fils : « Mon groupe » et « Tout l'ensemble »
R8 1 · Les rôles se cumulent | précision : En plus des adjoints de leader, avec les mêmes droits que le leader
R9 2 · Seul le créateur de l'ensemble le peut
R10 3 · L'ensemble est dissous pour tout le monde
R11 1 · Le poste reste vacant jusqu'à ce qu'un administrateur nomme quelqu'un
R12 1 · Permis : le groupe s'affiche « sans leader », rien n'est bloqué
R13 1 · Il peut quitter l'ensemble, pas changer de groupe
R14 (sans choix) | précision : le créateur de l'ensemble désigne les rôles
R15 1 · Un seul : soit un groupe simple, soit un ensemble
R16 (sans choix) | précision : Pas compris la question, développe
R17 1 · Non : un seul groupe par membre, un seul niveau | précision : un modèle de sous-groupes avec plusieurs échellon reste à prévoir, mais on se tient à 2 échellons pour le moment
R18 1 · Oui : chaque téléphone signe ses changements | précision : sécurité uniquement nécessaire en mode Ensemble, le partage de groupe classique reste un mode sans nécessité de sécurité
R19 1 · Jusqu'à 30 membres et 6 groupes | précision : pour commencer, et si le modèle fonctionne bien durant les essaies terrain, on va monter en nombre, donc reste à prevoir.
R20 1 · Ensemble, Groupe, Leader d'ensemble, Leader de groupe, Administrateur, Membre | précision : choix du moment, risque d'évoluer
```

**Précisions demandées (en attente)** : posées à l'utilisateur par la session principale ; le moteur n'est **pas** modifié pour ces réponses tant qu'elles ne sont pas reçues (tâche à part, avec de nouvelles maquettes avant tout code d'écran) :

- le groupe actuel et l'ensemble (R16 : question à développer) ;
- ce que le leader de groupe partage à tout l'ensemble, en regard de R6 (R4) ;
- qui désigne les rôles (R14 : le créateur de l'ensemble, les administrateurs, ou les deux) ;
- les adjoints (R8 : adjoints de leader avec les mêmes droits que le leader) ;
- le membre en attente : vraiment fermé (aucun accès aux partages avant d'être réparti) ou simple filtre d'affichage (R1) ;
- le départ du créateur (R9, R10).

## 11. Fichiers et tests

| Fichier (`core/src/main/kotlin/fr/toolmap/core/hierarchy/`) | Contenu |
|---|---|
| `HierarchyRules.kt` | `Role`, `Operation`, `Reach`, `RightsTable`, `Audience`, `VisibilityTable`, `HierarchyRules` |
| `HierarchyState.kt` | `Member`, `Group`, `HierarchyState` (`rolesOf`, `canSee`, `inconsistency`), `Names` |
| `Op.kt` | `Change`, `Op` (ordre `Op.ORDER`), `OpProof` |
| `HierarchyReducer.kt` | `HierarchyReducer.reduce` / `check`, `Rejection` |
| `HierarchyReplica.kt` | `HierarchyReplica` (`add`, `issue`, `summary`, `missingFor`, `history`, `ops`, `create`, `restore`), `AddResult`, `IssueResult` |
| `HierarchyWire.kt` | Format binaire, `Summary` |
| `OpAuth.kt` | Identifiants par empreinte, signature ECDSA P-256, `OpSigner` |

Tests (`core/src/test/kotlin/fr/toolmap/core/hierarchy/`, 39 tests) : `HierarchyReducerTest` (cas nominaux, refus, limites, tables de droits et de visibilité), `HierarchyReplicaTest` (cas du § 6.2, rattrapage, stockage, convergence sur 60 graines), `HierarchyWireTest` (allers-retours, refus, octets abîmés), `OpAuthTest` (§ 8.2). Commande : `./gradlew :core:test --tests 'fr.toolmap.core.hierarchy.*'`.

Utilise de l'existant, sans le modifier : `ByteWriter` / `ByteReader` / `WireException` (`p2p/Bytes.kt`), `Problems` / `catchExpected` (`problem/`). Aucune dépendance nouvelle.
