# Groupes hiérarchiques du partage de position (P39)

> Demande de l'utilisateur (2026-11-13) : `docs/PLAN.md` § P39. Décisions d'architecture : ADR-125 (journal d'opérations), ADR-126 (clés de contenu et signatures).
> Ce document décrit le **moteur** (P39.2, mis aux règles de l'utilisateur par P39.4) : la logique, dans `:core`, paquet `fr.toolmap.core.hierarchy`. Rien n'est branché : ni transport, ni stockage, ni écran. Le système de groupe actuel (`fr.toolmap.core.p2p`, `docs/specs/p2p-sharing.md`) n'est pas modifié.
> Les questions du premier tour sont au § 10, les réponses de l'utilisateur aux § 10 bis et 10 ter (le moteur les applique), les questions du second tour au § 12, la remontée de P39.4 au § 13.
> Mots : dans les écrans, « ensemble » se dira « opération » et « leader » se dira « chef » (§ 10 ter) ; dans le code et dans ce document, `ensemble` et `leader` restent les noms techniques.

## 1. Vocabulaire

| Mot | Sens | Dans le code |
|---|---|---|
| Ensemble | Le tout : un nom, des membres, des groupes, des règles | `HierarchyState` |
| Groupe | Un enfant de l'ensemble (ou d'un autre groupe quand les limites le permettent) : un nom, des membres, un leader, un adjoint | `Group` |
| Unité | L'ensemble ou un groupe : un lieu où l'on partage, avec un leader et un adjoint. L'ensemble est l'unité `ROOT` | `HierarchyState.ROOT`, `isUnit` |
| Membre | Un téléphone : identifiant (empreinte de sa clé de signature), nom affiché, ses deux clés publiques, zéro ou un groupe | `Member`, `MemberKeys` |
| En attente | Membre admis qui n'a pas de groupe et aucun poste qui donne l'accès sans groupe | `HierarchyState.isWaiting` |
| Opération | Un changement demandé par un membre, signé, tel qu'il voyage et se garde | `Op`, `Change` |
| Copie | Ce qu'un téléphone garde d'un ensemble : toutes les opérations reçues et l'état qu'elles donnent | `HierarchyReplica` |
| Lecteurs | Les membres qui lisent ce qui est partagé dans une unité, à un moment donné | `Readers`, `HierarchyState.readersOf` |
| Clé de contenu | La clé qui scelle ce qui est partagé dans une unité, pour une liste de lecteurs exacte | `ContentKey`, `KeyBatch`, `KeyRing` |
| Partage | Une position, un croquis, un waypoint ou un message, scellé pour les lecteurs d'une unité | `Share`, `ShareSeal` |

## 2. Modèle

- **Deux modes durables** (§ 10 ter, point 1) : le groupe simple, inchangé, et l'ensemble décrit ici. Un téléphone est dans l'un ou dans l'autre ; ce choix est fait par l'application, pas par le moteur.
- **Ensemble** : identifiant (64 bits), nom, **créateur** (un, toujours, transférable), administrateurs (zéro ou plusieurs), leader d'ensemble et son adjoint (postes qui peuvent rester vacants), règles (§ 4), et s'il est **dissous** (état final).
- **Groupes** : identifiant (64 bits, tiré au hasard par le téléphone qui le crée, jamais 0), nom, parent, leader, adjoint (deux membres différents du groupe, ou personne).
- **Membres** : identifiant, nom, clés publiques, groupe (**un seul, ou aucun** : une nouvelle affectation remplace l'ancienne, V3).
- **Limites, en données** (`HierarchyLimits`, dans les règles de l'ensemble) : 30 membres, 6 groupes, 1 niveau de groupes au départ (R17, R19). Un ensemble créé avec d'autres limites (jusqu'à 4 096 membres, 1 024 groupes, 8 niveaux : ce que le format porte) suit les mêmes règles, sans autre moteur. Les limites d'un ensemble sont fixées à sa création ; pour les monter, on crée les ensembles suivants avec la nouvelle valeur (une opération qui les change en cours de route n'est pas écrite, § 13).
- **Parent-enfant, écrit pour N niveaux** : chaque groupe porte son parent (`Group.parent`), l'ensemble ou un autre groupe. Les règles ci-dessous (postes, partage, lecteurs, suppression) sont écrites pour une profondeur quelconque et testées à deux niveaux de groupes ; avec la limite de départ (1 niveau), créer un groupe dans un groupe est refusé (`TOO_DEEP`). Un membre appartient à un groupe précis, feuille ou non ; le leader d'un groupe est un membre de ce groupe même, pas d'un de ses sous-groupes.
- **Noms** (ensemble, groupe, membre) : non vides, sans caractère de contrôle, 64 octets UTF-8 au plus (`Names`).
- L'identifiant d'un groupe supprimé ne revient jamais (`deletedGroups`). Un groupe qui a encore des sous-groupes ne se supprime pas (`HAS_SUBGROUPS`) : on supprime d'abord ce qui est dessous.

Un état construit par le moteur est toujours cohérent ; `HierarchyState.inconsistency()` le vérifie (tests, et lecture d'un état venu d'octets).

### 2.1 En attente

Un arrivant est admis **sans groupe** (V2) : il est « en attente » jusqu'à ce qu'un administrateur l'affecte à un groupe (R1). Tant qu'il attend :

- il n'est dans **aucune liste de lecteurs** (`readersOf`) : aucune clé de contenu n'est enveloppée pour son téléphone, il ne peut donc rien déchiffrer (§ 8), et le transport n'a rien à lui adresser ;
- il ne partage rien (`mayShare` : faux, quelle que soit la table), ne voit personne sur la carte et n'y est vu de personne (`canSee`) ;
- il peut quitter l'ensemble, rien d'autre.

Gardent l'accès **sans groupe** : le créateur, les administrateurs, le leader d'ensemble et son adjoint (§ 10 ter, point 5 ; l'adjoint parce qu'il a les droits de son leader). L'accès suit le poste : un administrateur sans groupe à qui le créateur retire son rôle attend un groupe.

Redeviennent en attente : un membre sorti de son groupe (`UnassignMember`), les membres d'un groupe supprimé, l'ancien créateur qui a passé la main et n'a ni groupe ni poste.

## 3. Rôles

Les rôles ne sont pas stockés : ils se lisent dans l'état (`HierarchyState.rolesOf`). **Ils se cumulent** (R8).

| Rôle | Qui | Combien |
|---|---|---|
| Créateur (`CREATOR`) | Celui qui a créé l'ensemble, ou celui à qui il l'a passé | Un, toujours |
| Administrateur (`ADMIN`) | Nommé par le créateur | Zéro ou plusieurs |
| Leader d'ensemble (`ENSEMBLE_LEADER`) | Nommé par un administrateur ; membre avec ou sans groupe | Un, ou poste vacant |
| Adjoint du leader d'ensemble (`ENSEMBLE_DEPUTY`) | Nommé par un administrateur | Un, ou personne |
| Leader de groupe (`GROUP_LEADER`) | Nommé pour un groupe ; il doit en être membre | Un par groupe, ou aucun |
| Adjoint du leader de groupe (`GROUP_DEPUTY`) | Nommé pour un groupe ; il doit en être membre | Un par groupe, ou personne |
| Membre (`MEMBER`) | Tout membre admis, en attente ou non | Tous |

- **Un adjoint a les droits de son leader** (R8, § 10 ter point 4) : c'est une règle du moteur, pas une case. Les tables n'ont rien d'écrit pour les adjoints ; ce que la table donne au leader, son adjoint l'a (`effectiveMask`), que le poste de leader soit pourvu ou vacant.
- **Un poste au plus par unité** : l'adjoint nommé leader quitte le poste d'adjoint, et inversement. Nommer quelqu'un à un poste remplace celui qui le tenait.
- **Postes vacants** : à la création, tous les postes sont vacants. Un leader qui quitte l'ensemble, change de groupe ou sort de son groupe laisse son poste vacant ; personne n'est promu à sa place (R11). Un groupe sans leader ne bloque rien (R12).
- **Créateur** : il y en a toujours exactement un. Il ne peut ni quitter l'ensemble ni en être retiré tant qu'il est le créateur (`IS_CREATOR`) : il passe d'abord la main (`TransferCreator`, son successeur devient le créateur, lui redevient un membre comme un autre), ou il dissout l'ensemble (`Dissolve`). L'application propose ces deux choix quand il veut partir (R10, § 10 ter point 6).
- **Dissolution** : état final. Après elle, aucune opération ne s'applique (`DISSOLVED`), plus personne n'a accès, plus aucune unité n'a de lecteurs.

## 4. Droits, partage et visibilité : trois tables de données

Les tables sont des **données**, pas du code : changer une réponse, c'est changer une case. Elles voyagent dans la première opération de l'ensemble (§ 5) et font partie de son identifiant : tous les téléphones d'un ensemble appliquent les mêmes, même si leurs versions de l'application n'ont pas les mêmes tables de départ.

### 4.1 Qui peut faire quoi (`RightsTable`)

Une case par opération et par rôle. Une case contient zéro, une ou plusieurs **portées** :

| Portée | Sens |
|---|---|
| `ANYONE` | Quel que soit le membre ou le groupe concerné |
| `OWN_GROUP` | Seulement si le groupe concerné est le groupe de l'auteur |
| `SELF` | Seulement si le membre concerné est l'auteur lui-même (partir, renoncer à un poste) |
| (vide) | Jamais |

Un auteur a le droit si **l'un** de ses rôles a une case qui couvre l'opération. Quelqu'un qui n'est pas membre n'a aucun droit.

**Table de départ** (`RightsTable.DEFAULT`), les réponses de l'utilisateur :

| Opération | Créateur | Administrateur | Leaders, adjoints | Membre | Réponse |
|---|---|---|---|---|---|
| Admettre un membre (`ADMIT_MEMBER`) | tous | tous | — | — | R1 |
| Retirer un membre (`REMOVE_MEMBER`) | tous (sauf lui-même) | — | — | soi-même (quitter) | R9, R13 |
| Nommer un administrateur (`ADD_ADMIN`) | tous | — | — | — | R14 |
| Retirer un administrateur (`REMOVE_ADMIN`) | tous | — | — | — | R9, R14 |
| Créer, renommer, supprimer un groupe (`CREATE_GROUP`, `RENAME_GROUP`, `DELETE_GROUP`) | tous | tous | — | — | R14 |
| Affecter un membre à un groupe (`ASSIGN_MEMBER`) | tous | tous | — | — | R3 |
| Sortir un membre de son groupe (`UNASSIGN_MEMBER`) | tous | tous | — | — | R3, R13 |
| Nommer ou laisser vacant : leader de groupe, adjoint de groupe, leader d'ensemble, adjoint d'ensemble (`NAME_GROUP_LEADER`, `NAME_GROUP_DEPUTY`, `NAME_ENSEMBLE_LEADER`, `NAME_ENSEMBLE_DEPUTY`) | tous | tous | — | — | R14 |
| Passer la main (`TRANSFER_CREATOR`) | tous | — | — | — | R10 |
| Dissoudre (`DISSOLVE`) | oui | — | — | — | R10 |

Les leaders et leurs adjoints n'ont **aucun droit sur la hiérarchie** (R5) : ce qu'ils ont de plus qu'un membre, c'est ce qu'ils peuvent partager (§ 4.2). Un membre ne change pas de groupe seul (R13). Un administrateur ne retire personne de l'ensemble, pas même lui-même de son rôle. La colonne du créateur contient celle des administrateurs : un ensemble peut n'avoir aucun administrateur (lecture de cette session, § 13).

Pour `OWN_GROUP` (aucune case de la table de départ ne l'utilise ; les tests montrent une autre table), le groupe concerné est le groupe nommé, ou celui d'où sort le membre.

### 4.2 Qui partage quoi, et jusqu'où (`ShareTable`)

Quatre sortes de partage (`ShareKind`) : position, croquis, waypoint, message. Une case par sorte et par rôle contient les **unités où ce rôle peut partager** :

| Portée | Sens |
|---|---|
| `OWN_UNIT` | Dans son propre groupe |
| `PARENT_UNIT` | Dans l'unité juste au-dessus de son groupe, et pas plus haut |
| `ENSEMBLE` | Dans tout l'ensemble, où que soit l'auteur |

**Table de départ** (`ShareTable.DEFAULT`) :

| Sorte | Créateur, administrateur, leader d'ensemble (et son adjoint) | Leader de groupe (et son adjoint) | Membre |
|---|---|---|---|
| Position | tout l'ensemble | — | tout l'ensemble |
| Croquis, waypoint | tout l'ensemble | un niveau au-dessus | son groupe |
| Message | tout l'ensemble | — | son groupe |

Ce que cela donne (R2, R4, R6, R7, § 10 ter point 2) :

- les positions vont à tout l'ensemble : tout le monde voit tout le monde ;
- un membre partage croquis, waypoints et messages **dans son groupe** ;
- le leader d'un groupe et son adjoint partagent **croquis et waypoints un niveau au-dessus, jamais plus haut** : le leader d'un groupe, à tout l'ensemble ; le leader d'un sous-groupe, au groupe qui le contient et pas à l'ensemble. Leurs messages ne montent pas ;
- les messages à tout l'ensemble sont réservés au leader d'ensemble, à son adjoint, aux administrateurs (et au créateur) ;
- deux fils de messages pour un membre : son groupe, tout l'ensemble (avec des sous-groupes : un fil par unité au-dessus de lui).

**Qui lit** (`readersOf(unité)`, règle du moteur, pas une case) : ce qui est partagé dans une unité est lu par ses membres **et par ceux des unités qui sont dessous** ; ce qui est partagé dans l'ensemble, par tous ceux qui ont l'accès. Personne ne partage vers le bas dans un groupe qui n'est pas le sien, ni de côté. Conséquence : un administrateur ou un leader d'ensemble qui n'est dans aucun groupe ne lit le fil d'aucun groupe ; l'information monte quand le leader du groupe la fait monter.

`HierarchyState.mayShare(auteur, sorte, unité)` et `shareUnits(auteur, sorte)` répondent sans rien écrire. Qui attend un groupe ne partage rien, quelle que soit la table.

### 4.3 Qui voit la position de qui (`VisibilityTable`)

Pour chaque rôle de celui qui regarde, les **publics** qu'il voit (`SAME_GROUP`, `GROUP_LEADERS`, `ENSEMBLE_LEADER`, `ADMINS`, `EVERYONE`). `HierarchyState.canSee(viewer, target)` : vrai si l'un des rôles de `viewer` voit l'un des publics dont `target` fait partie. Chacun se voit toujours. Qui est hors de l'ensemble ou en attente ne voit personne et n'est vu de personne, quelle que soit la table.

**Table de départ** : tout le monde voit tout le monde (R2). Une table plus étroite resterait un filtre d'affichage : les positions sont scellées avec la clé de tout l'ensemble (§ 8).

## 5. Opérations

Chaque opération (`Op`) porte : l'ensemble, son **auteur**, son **numéro** chez cet auteur (`seq` : 1, 2, 3…, sans trou), l'**horloge logique** de l'auteur (`clock` : un de plus que toutes les opérations qu'il connaissait), le changement, et la **preuve** de son auteur (§ 8.1).

| Changement (`Change`) | Effet | Refusé quand (en plus des droits) |
|---|---|---|
| `CreateEnsemble(nonce, name, creatorName, creatorKeys, rules)` | Crée l'ensemble ; l'auteur en est le seul membre et le créateur ; tous les postes sont vacants ; fixe les règles | Ensemble déjà créé ; pas la première opération de l'auteur, ou identifiant d'ensemble qui ne correspond pas (`BAD_GENESIS`) |
| `AdmitMember(member, name, keys)` | Ajoute un membre, en attente | Déjà membre ; limite de membres atteinte ; clés qui ne sont pas celles de `member` (`MALFORMED`) |
| `RemoveMember(member)` | Le sort de l'ensemble, de son groupe, de ses postes, des administrateurs | Inconnu ; **c'est le créateur** (`IS_CREATOR`) |
| `AddAdmin(member)` / `RemoveAdmin(member)` | Le nomme administrateur / lui retire ce rôle | Pas membre ; déjà administrateur / pas administrateur |
| `CreateGroup(group, name, parent)` | Crée un groupe sous l'ensemble ou sous un groupe | Identifiant pris ou supprimé ; parent inconnu ; trop profond (`TOO_DEEP`) ; limite de groupes |
| `RenameGroup(group, name)` | Renomme | Groupe inconnu ; même nom |
| `DeleteGroup(group)` | Supprime ; **ses membres restent dans l'ensemble, en attente** | Groupe inconnu ; il a des sous-groupes (`HAS_SUBGROUPS`) |
| `AssignMember(member, group)` | Le met dans ce groupe, donc hors de l'ancien, dont il quitte le poste | Membre ou groupe inconnu ; déjà dedans |
| `UnassignMember(member)` | Le sort de son groupe : il attend | Inconnu ; sans groupe |
| `SetPost(unit, post, member)` | Le nomme leader ou adjoint de l'unité (`ROOT` : l'ensemble), à la place du précédent | Unité ou membre inconnu ; **pas membre de ce groupe** ; déjà à ce poste |
| `ClearPost(unit, post)` | Laisse le poste vacant | Unité inconnue ; poste déjà vacant |
| `TransferCreator(member)` | `member` devient le créateur ; l'auteur redevient un membre comme un autre | Pas membre ; déjà le créateur |
| `Dissolve` | Fin de l'ensemble pour tout le monde | — |

- Une opération refusée ne change rien et garde son motif (`Rejection`), le même sur tous les téléphones : `HierarchyReplica.history()` permet de dire à l'auteur « votre changement n'a pas été retenu ».
- `HierarchyReplica.issue(change)` vérifie d'abord sur le téléphone de l'auteur : un changement qu'il sait refusé n'est pas écrit du tout. `HierarchyReducer.check(state, author, change)` répond sans rien écrire (pour griser un bouton).
- Un membre retiré peut être admis de nouveau : il revient en attente.
- Le nom d'un membre est celui donné à l'admission ; il n'y a pas d'opération pour le changer, ni pour renommer l'ensemble (à ajouter si l'interface le demande).
- **Identifiant de l'ensemble** : 8 octets de SHA-256 de (auteur, contenu de `CreateEnsemble`, clés du créateur et règles comprises). Un ensemble n'a donc qu'une première opération possible, et qui reçoit l'identifiant (invitation) sait que les règles sont celles prévues.

## 6. Accord entre téléphones, sans autorité centrale

### 6.1 La règle

1. Chaque téléphone **garde toutes les opérations signées** reçues, de tous les auteurs. Une opération sans preuve valable n'est pas gardée (`AddResult.UNAUTHENTICATED`).
2. Il les applique dans un **ordre unique**, le même partout : par horloge croissante, puis par identifiant d'auteur (non signé), puis par numéro (`Op.ORDER`).
3. Chaque opération est vérifiée contre les droits de son auteur **dans l'état où elle s'applique** (pas dans celui que voyait l'auteur) ; refusée, elle ne change rien.
4. L'état est donc une fonction de **l'ensemble** des opérations détenues, pas de leur ordre d'arrivée : deux téléphones qui détiennent les mêmes opérations ont le même état. Quand une opération arrive « dans le passé », le téléphone rejoue (`HierarchyReplica.replay`) ; quand elle arrive à la fin, il l'applique seule.

Compléments :

- Seules les opérations d'un auteur **sans trou** sont appliquées (1, 2, 3…) ; une opération en avance attend (`HELD`) que le rattrapage apporte les précédentes.
- **L'horloge d'un auteur ne recule pas** (règle changée par P39.4). Les opérations d'un auteur comptent dans l'ordre où il les a numérotées ; celle dont l'horloge ne dépasse pas celle de la précédente est refusée (`OUT_OF_ORDER`), où que l'ordre la place, et l'auteur continue. Personne n'écrit donc « avant » ce qu'il a déjà écrit : l'ancien créateur ne peut pas défaire son passage de main, ni le créateur sa dissolution. (Avant P39.4 c'était l'opération la plus ancienne qui perdait : un auteur pouvait effacer la sienne après coup.)
- **Deux opérations différentes sous le même numéro d'un même auteur** (téléphone restauré d'une sauvegarde qui réécrit par-dessus, ou téléphone modifié ; les deux sont signées par lui) : une seule est gardée, la même partout : la vraie première opération de l'ensemble d'abord, puis celle dont les octets viennent en premier. Limite : § 6.4.

### 6.2 Cas limites tranchés (chacun a son test, dans les deux ordres d'arrivée)

| Cas | Ce qui se passe, sur tous les téléphones |
|---|---|
| Deux affectations du même membre en même temps | Les deux s'appliquent, dans l'ordre ; la dernière dans l'ordre reste : un seul groupe. Avant d'avoir échangé, chacun voit son propre choix |
| Un membre est affecté pendant qu'un autre administrateur le remet en attente | Selon l'ordre : en attente, ou dans le groupe de la dernière affectation. Le même résultat partout |
| Un administrateur est retiré par le créateur pendant qu'il agit | Ce que l'ordre place **avant** son retrait reste ; ce qu'il place **après** est refusé (`FORBIDDEN`), y compris sur son propre téléphone, qui l'avait d'abord montré |
| Un membre est retiré par le créateur pendant qu'un administrateur l'affecte ou le nomme | Retrait d'abord : l'affectation est refusée (`NO_SUCH_MEMBER`). Affectation d'abord : elle s'applique, puis le retrait le sort du groupe et du poste |
| Un groupe est supprimé pendant qu'on y affecte quelqu'un | Dans les deux ordres : plus de groupe, membre en attente |
| Un membre est déplacé pendant qu'on le nomme leader du groupe qu'il quitte | Nommé puis déplacé (il perd le poste), ou déplacé puis nomination refusée (`NOT_IN_GROUP`). Dans les deux cas le groupe n'a pas de leader |
| Deux adjoints nommés en même temps pour le même leader | Les deux s'appliquent ; le dernier dans l'ordre reste : un seul adjoint |
| L'adjoint est nommé leader pendant qu'un autre adjoint est nommé | Dans les deux ordres : l'un est leader, l'autre adjoint |
| Le créateur passe la main, puis écrit encore | Tout ce qu'il écrit après son passage de main est refusé (`FORBIDDEN`) ; ce qu'il daterait d'avant est refusé (`OUT_OF_ORDER`) |
| Le créateur passe la main à quelqu'un qui part au même moment | Départ d'abord : le passage de main ne trouve personne (`NO_SUCH_MEMBER`), le créateur reste le créateur et son propre départ est refusé (`IS_CREATOR`). Passage de main d'abord : le successeur est le créateur et son départ est refusé. Il y a toujours exactement un créateur |
| Le créateur dissout pendant que d'autres agissent | Ce que l'ordre place avant la dissolution reste ; tout le reste est refusé (`DISSOLVED`), y compris ce qu'écrit plus tard un téléphone qui ne savait pas |
| Opération rejouée (reçue deux fois) | Reconnue (`DUPLICATE`), rien ne change, la signature n'est pas revérifiée |
| Opération d'un auteur sans droit, non membre ou retiré | Gardée (pour que tous aient les mêmes opérations), refusée (`FORBIDDEN`, `NOT_A_MEMBER`), ne change rien |
| Fausse création de l'ensemble | Refusée (`BAD_GENESIS`) : l'identifiant de l'ensemble ne correspond qu'à la vraie |
| Opération non signée, signée par un autre, ou signature abîmée | Pas gardée, jamais appliquée (`UNAUTHENTICATED`) |

Test de convergence (`HierarchyReplicaTest`, 50 graines fixes, limites et tables variées, un ou deux niveaux de groupes) : cinq téléphones et trois auteurs sans téléphone écrivent au hasard toutes les sortes de changements (attente, adjoints, passage de main, dissolution, retrait compris), avec ou sans droit, parfois deux opérations sous un même numéro, horloges parfois en arrière, échanges partiels ; à la fin toutes les opérations sont remises à huit téléphones neufs dans huit ordres différents : un seul état, les mêmes refus, la même empreinte, et les cinq téléphones d'origine y arrivent aussi après rattrapage. À chaque fois : exactement un créateur, et personne d'en attente dans une liste de lecteurs.

### 6.3 Retour d'un téléphone hors réseau

Échange d'état en deux messages (`Summary`, puis opérations) :

1. Le téléphone envoie son **résumé** (`HierarchyReplica.summary()`) : pour chaque auteur, combien d'opérations il détient sans trou, et une empreinte (8 octets de SHA-256) de ces opérations.
2. L'autre répond par **ce qui manque** (`missingFor(résumé)`) : les opérations au-delà de ces nombres, par blocs (`HierarchyWire.MAX_BATCH`, ou moins selon la trame).
3. Et inversement. On recommence tant que l'un a quelque chose à envoyer.
4. Puis les **clés** : n'importe quel téléphone lui donne les lots de clés du moment (`KeyRing.batchesFor`), qu'il soit lecteur ou non (§ 8.3).

Si les nombres sont égaux et les empreintes différentes, un auteur a écrit deux opérations sous un même numéro : tout est renvoyé, et la règle du § 6.1 garde la même des deux côtés.

Le téléphone revenu peut avoir écrit des opérations hors réseau : elles prennent leur place dans l'ordre comme les autres, et peuvent être refusées si, entre-temps, leur auteur a perdu le droit.

### 6.4 Limites connues

- **Journal qui grossit** : toutes les opérations sont gardées (au plus 20 000 par ensemble, `MAX_OPS` ; au-delà le téléphone n'en prend plus). Un « point de reprise » (état signé qui remplace le passé) sera à écrire si l'usage le demande.
- **Deux opérations sous un même numéro, exprès** : un auteur qui signe une seconde opération sous le numéro d'une ancienne, avec des octets qui passent devant, **remplace la sienne sur tous les téléphones**. Un ancien créateur au téléphone modifié peut ainsi remplacer son passage de main par autre chose et rester le créateur ; un administrateur, effacer une de ses décisions. La signature dit qui l'a fait (les deux opérations portent sa signature, `CONFLICT_REPLACED` le signale), elle ne l'empêche pas. Parade à écrire avant un usage réel (§ 13) : un auteur pris avec deux opérations sous un numéro est figé à partir de ce numéro (ses deux opérations gardées comme preuve), ce qui lui retire tout intérêt à le faire.
- **Antidatage par quelqu'un qui a perdu un droit** : un administrateur retiré et malveillant peut écrire, après coup, une opération datée entre sa dernière opération et son retrait. La règle du § 6.1 ferme le cas de ses propres opérations (il n'écrit pas avant ce qu'il a écrit), pas celui-ci. Même parade (point de reprise).
- **Horloge poussée au maximum** : un membre malveillant peut écrire une opération d'horloge maximale ; plus personne ne peut alors écrire dans cet ensemble (`LOG_FULL`). **Journal rempli exprès** : quiconque a la clé de l'invitation peut signer des milliers d'opérations sans effet jusqu'à la borne. La signature dit qui ; parade : refaire un ensemble, ou le point de reprise.
- **Deux téléphones avec la même identité** (clés restaurées sur un second appareil) : ils écrivent sous les mêmes numéros ; une seule opération de chaque paire reste. Une identité ne se copie pas : un téléphone neuf est un membre neuf (§ 12, question 6).
- **Opération d'un type inconnu** (version plus récente) : refusée au décodage, donc non gardée ; le téléphone doit être mis à jour pour suivre. Ajouter un type d'opération demande de monter la version du format.

## 7. Format binaire (`HierarchyWire`)

Gros-boutiste, comme `Wire`. Tout bloc commence par `version (1) = 2 | genre (1)`. Chaînes : longueur sur 1 octet puis UTF-8. Clé publique : point P-256 non compressé, 65 octets (`0x04 | X | Y`). Signature : `r (32) | s (32)`. Le format n'est **pas** branché au transport.

**Version 2 (P39.4).** La version 1 (P39.2) n'a jamais été déployée : elle n'est pas lue, aucune compatibilité n'est gardée.

| Genre | Contenu |
|---|---|
| 1 `OP` | une opération |
| 2 `OPS` | `n (2)` puis `n × (longueur (2), opération)` ; `n ≤ 512` |
| 3 `SUMMARY` | `ensemble (8) | empreinte (8) | n (2) | n × (auteur (8), nombre (4))`, auteurs par identifiant croissant ; `n ≤ 4096` |
| 4 `STATE` | un état complet (ci-dessous) |
| 5 `KEYS` | un lot de clés : une clé de contenu enveloppée pour chacun de ses lecteurs (§ 8.3) |
| 6 `SHARE` | un partage scellé (§ 8.5) |

**Opération** : `longueur du contenu (2) | contenu | clé de signature de l'auteur (65) | signature (64)`. Une opération ne voyage jamais sans sa preuve.

**Contenu** (ce qui est signé et comparé) : `ensemble (8) | auteur (8) | numéro (4) | horloge (4) | code (1) | corps`.

| Code | Changement | Corps |
|---|---|---|
| 1 | `CreateEnsemble` | `nonce (8) | nom | nom du créateur | clés du créateur (130) | règles` |
| 2 | `AdmitMember` | `membre (8) | nom | clés (130)` |
| 3 / 4 / 5 | `RemoveMember` / `AddAdmin` / `RemoveAdmin` | `membre (8)` |
| 6 | `CreateGroup` | `groupe (8) | parent (8) | nom` |
| 7 | `RenameGroup` | `groupe (8) | nom` |
| 8 | `DeleteGroup` | `groupe (8)` |
| 9 | `AssignMember` | `membre (8) | groupe (8)` |
| 10 | `UnassignMember` | `membre (8)` |
| 11 | `SetPost` | `unité (8 ; 0 : l'ensemble) | poste (1 : leader, 2 : adjoint) | membre (8)` |
| 12 | `ClearPost` | `unité (8) | poste (1)` |
| 13 | `TransferCreator` | `membre (8)` |
| 14 | `Dissolve` | (rien) |

**Clés d'un membre** : `clé de signature (65) | clé d'accord (65)`.

**Règles** : `n (1) | n × (opération (1), rôle (1), portées (1))` pour les cases non vides des droits, dans l'ordre des codes ; `m (1) | m × (rôle (1), publics (1))` ; `k (1) | k × (sorte de partage (1), rôle (1), portées de partage (1))` ; `sortes signées (1)` ; `membres au plus (2) | groupes au plus (2) | niveaux au plus (1)`. Codes : rôles 1 à 7 (`Role`), opérations 1 à 15 (`Operation`), portées en bits 1 / 2 / 4 (`Reach`), publics en bits 1 à 16 (`Audience`), sortes de partage 1 à 4 (`ShareKind`, en bits 1 / 2 / 4 / 8 pour les sortes signées), portées de partage en bits 1 / 2 / 4 (`ShareReach`).

**État** : `ensemble (8) | nom | drapeaux (1 ; bit 0 : dissous) | créateur (8) | leader (8 ; 0 : vacant) | adjoint (8 ; 0 : aucun) | règles | n (2) | n × (membre (8), nom, clés (130), groupe (8 ; 0 : aucun), administrateur (1))` puis `g (2) | g × (groupe (8), parent (8), nom, leader (8), adjoint (8))` puis `d (2) | d × groupe supprimé (8)`, tout par identifiant croissant : un même état donne toujours les mêmes octets. Il sert à garder l'état sur le disque pour un démarrage rapide ; l'accord entre téléphones passe par les opérations, jamais par l'état.

**Lot de clés** : `ensemble (8) | unité (8) | nom de la liste de lecteurs (16) | identifiant de la clé (8) | émetteur (8) | clé éphémère (65) | n (2) | n × (lecteur (8), clé enveloppée (48))`, lecteurs par identifiant croissant, puis `signature de l'émetteur (64)`.

**Partage** : `ensemble (8) | unité (8) | identifiant de la clé (8) | sorte (1) | auteur (8) | nonce (12) | partie scellée`, où la partie scellée contient la charge utile, la signature de l'auteur (64) pour une sorte signée, et l'étiquette d'authentification (16).

**Tailles** : une opération fait 158 octets de plus que le corps de son changement (en-tête, longueur, ensemble, auteur, numéro, horloge, code, preuve) : 174 octets pour une affectation, 239 au plus pour une création de groupe, 361 au plus pour une admission, environ 470 pour la création de l'ensemble avec les règles de départ. Un lot de clés : 181 octets plus 56 par lecteur (517 pour 6 lecteurs, 1 861 pour 30). Un partage : 63 octets de plus que sa charge utile, 127 quand il est signé.

**Entrées invalides** : le décodage ne lève qu'une `WireException`, quels que soient les octets (testé : coupures à chaque longueur, octets modifiés, bruit, bloc lu comme un autre genre). Sont refusés : version ou genre inconnu, données tronquées ou en trop, code inconnu, identifiant 0, nom refusé par `Names`, clé qui n'est pas un point de la courbe ou qui n'est pas celle du membre, règle ou limite inconnue, état incohérent, lot de clés sans lecteur ou mal ordonné, et toute opération qui n'est pas écrite de **la seule façon** dont cette version l'écrit (le décodeur réécrit l'opération et compare : la signature porte sur ces octets exacts).

## 8. Sécurité : signatures et clés de contenu (ADR-126)

Le mode ensemble est le mode où la sécurité est exigée (R18, § 10 ter points 5 et 7). Tout ce qui suit n'utilise que ce que la JVM et Android fournissent déjà (ECDSA et ECDH sur P-256, HKDF-SHA256, AES-256-GCM) : aucune dépendance nouvelle. Le groupe simple n'est pas touché.

### 8.1 Chaque téléphone signe ses changements (activé)

- Chaque téléphone a **deux paires de clés** P-256 (`PhoneKeys`, `MemberKeys`) : une clé de **signature** et une clé d'**accord** (celle pour laquelle on enveloppe les clés de contenu). Deux et non une : la clé de signature peut rester dans le Keystore d'Android sur toutes les versions prises en charge (API 26 et suivantes), alors que l'accord de clés dans le Keystore n'existe qu'à partir de l'API 31.
- **L'identifiant d'un membre est l'empreinte de sa clé de signature** (8 octets de SHA-256). Les deux clés publiques sont inscrites dans l'opération qui admet le membre (ou qui crée l'ensemble, pour le créateur) ; le moteur refuse une admission dont les clés ne sont pas celles de l'identifiant.
- **Toute opération est signée par son auteur.** Ce n'est plus une règle qu'un ensemble peut désactiver (le drapeau `signedOps` de P39.2 a disparu) : une opération sans preuve valable n'est pas gardée par `HierarchyReplica.add` et n'est jamais appliquée par `HierarchyReducer.reduce` (`UNAUTHENTICATED`). La clé qui signe doit être **celle avec laquelle le membre a été admis**, pas seulement une clé de même empreinte.
- **Une seule forme pour les clés et les signatures**, vérifiée par le moteur avant de s'en remettre à la plateforme (`P256`) : point non compressé de 65 octets vérifié sur la courbe, signature `r | s` de 64 octets avec `r` et `s` dans les bornes. Tous les téléphones acceptent ou refusent donc les mêmes octets, quelle que soit leur version d'Android : c'est nécessaire à l'accord du § 6 (une opération acceptée par un téléphone et refusée par un autre les ferait diverger). `P256.derToRaw` convertit ce que rend un Keystore.
- La même clé signe, sous trois étiquettes différentes, les opérations, les lots de clés et les partages : une signature faite pour l'un ne vaut rien pour les deux autres.

Testé (`OpAuthTest`) : opération signée reconnue ; refusées et non gardées : sans preuve, preuve d'une autre opération, signée par un autre au nom de l'auteur, signature abîmée, clé qui n'en est pas une ; clé de même identifiant mais différente de celle de l'admission refusée ; copie sans son téléphone (sans clés) qui ne peut rien écrire.

### 8.2 « Vraiment fermé » : quatre schémas comparés

Besoin (R1, § 10 ter point 5) : le téléphone d'un membre en attente ne doit pouvoir déchiffrer aucun partage ; un membre retiré, parti ou remis en attente ne doit plus rien lire de ce qui suit ; le tout sans autorité centrale, avec des téléphones souvent hors réseau, et des changements de rôles qui arrivent dans le désordre (§ 6).

| Schéma | Principe | Pour | Contre |
|---|---|---|---|
| **A. Une clé par unité et par liste de lecteurs, émise par un lecteur** (retenu) | Chaque unité (l'ensemble, chaque groupe) a une clé de contenu faite pour **exactement** sa liste de lecteurs du moment, enveloppée pour chacun d'eux. Toute modification de la liste demande une clé neuve. N'importe quel lecteur peut l'émettre | Fermeture réelle pour qui attend et pour qui sort ; pas d'autorité ; insensible au désordre des opérations (une clé est liée au contenu de la liste, pas à un numéro) ; n'importe quel téléphone peut relayer les lots à un absent | Un lot à refaire à chaque changement de liste : 181 + 56 × n octets et n accords de clés ; un court moment sans clé après un changement |
| B. Clés émises par les seuls administrateurs | Comme A, mais seuls le créateur et les administrateurs font les clés | Un seul émetteur possible, plus simple à raisonner | Aucune clé neuve tant qu'aucun administrateur n'est joignable (un retrait ne ferme rien sur le terrain) ; les administrateurs détiendraient les clés de tous les groupes, donc liraient tous les fils |
| C. Une clé d'émission par membre (« sender keys ») | Chacun chiffre avec sa propre clé, qu'il distribue à chaque lecteur | Chaque message prouve son auteur sans signature | À chaque changement de liste, **chaque** membre redistribue sa clé à tous : n × n enveloppes au lieu de n ; rattrapage d'un absent auprès de chaque émetteur |
| D. Arbre de clés (MLS / TreeKEM) | Un arbre de clés mis à jour par des « commits » ordonnés | Coût en log n ; secret persistant et réparation après compromission | Exige un ordre total des commits, donc un service de livraison ou un consensus que le partage n'a pas ; très gros à écrire sans bibliothèque ; rien de tel dans la plateforme |

**Choix : A.** Coût assumé : pour un ensemble de 30 membres, un lot de l'ensemble pèse 1 861 octets et demande 30 accords de clés (quelques dizaines de millisecondes sur un téléphone) ; affecter un arrivant change deux listes (son groupe et l'ensemble), soit environ 2,4 Kio ; répartir 30 arrivants un par un dans 6 groupes coûte environ 42 Kio et 555 accords en tout. C'est rare (les rôles changent peu) et petit devant les positions. Une économie possible plus tard, sans changer le principe : quand la liste ne fait que grandir, dériver la clé suivante de la précédente et n'envelopper que pour l'arrivant.

Un simple filtre d'affichage sur la clé unique du groupe actuel, ou un filtre du transport seul, ont été écartés d'emblée : l'utilisateur demande une fermeture cryptographique.

### 8.3 Le schéma retenu, pas à pas

**Qui détient quoi.**

- **Clé de l'invitation** (à brancher, § 9) : portée par le lien ou le QR d'invitation ; elle scelle les trames qui transportent les opérations et les lots de clés. Tout membre admis l'a, **en attente compris** : c'est ce qui lui permet de recevoir le journal des changements, donc de savoir qu'il est admis, puis affecté. Elle n'ouvre **aucun partage**.
- **Ce que détient un membre en attente** : sa propre identité (ses deux paires de clés), la clé de l'invitation, le journal des opérations (les noms de l'ensemble, des groupes et des membres, et qui tient quel poste : § 12, question 2). Aucune clé de contenu : aucune n'a jamais été enveloppée pour lui, et les lots qu'il voit passer ne contiennent aucune copie qu'il puisse ouvrir (testé : chaque copie de chaque lot essayée avec ses clés).
- **Clés de contenu** : une par unité et par liste de lecteurs. Un membre affecté détient celles de son groupe, de chaque unité au-dessus, et de l'ensemble ; un créateur, administrateur ou leader d'ensemble sans groupe, celle de l'ensemble seulement.

**Comment une clé est faite** (`ContentKeys.issue`, `KeyRing.issue`).

1. La **liste de lecteurs** d'une unité (`readersOf`) a un **nom** (`Readers.tag`, 16 octets de SHA-256 de l'ensemble, de l'unité, et de chaque lecteur avec sa clé d'accord). Le moindre changement de la liste change le nom.
2. Un lecteur de l'unité tire une clé de 32 octets au hasard. Son **identifiant** (8 octets) est une empreinte de la clé et de ce pour quoi elle est faite.
3. Il fait une paire de clés éphémère pour ce lot, et pour chaque lecteur : accord ECDH entre la clé éphémère et la clé d'accord du lecteur, HKDF-SHA256 (lié à l'ensemble, l'unité, la liste, la clé, le lecteur), puis AES-256-GCM de la clé de contenu. Chaque copie ne s'ouvre que pour son lecteur.
4. Il signe le tout. Le **lot** (`KeyBatch`) voyage en clair sous la clé de l'invitation.

**Comment un lot est accepté** (`ContentKeys.check`, `KeyRing.offer`). Un téléphone ne garde une clé que si : la liste de lecteurs nommée **a existé** à un moment des opérations qu'il détient (`HierarchyReplica.readers(unité, nom)`) ; l'émetteur est **un lecteur de cette liste** (personne n'émet la clé d'une unité qu'il ne lit pas : ni un administrateur hors du groupe, ni quelqu'un en attente) ; la signature est celle de l'émetteur ; le lot est enveloppé pour **exactement** cette liste (personne d'oublié, personne d'ajouté) ; sa propre copie s'ouvre et donne bien la clé annoncée. Sinon : `FORGED`, `ISSUER_NOT_A_READER`, `WRONG_RECIPIENTS`, `DAMAGED`. Un lot pour une liste qu'il ne connaît pas encore attend (`UNKNOWN_READERS`) et est réessayé après le rattrapage des opérations (`KeyRing.retry`). Un téléphone qui n'est pas lecteur vérifie tout de même le lot et le garde pour le transmettre (`NOT_FOR_ME`).

**Avec quelle clé on scelle** (`KeyRing.sealingKey`). Uniquement avec une clé faite pour **exactement les lecteurs du moment**. Si la liste vient de changer et qu'aucune clé n'existe encore, on ne scelle pas (`NO_KEY`) : on attend le lot, ou on l'émet soi-même. Pour éviter que tous l'émettent en même temps, `keyIssuers(unité)` donne un ordre (leader de l'unité, adjoint, créateur et administrateurs pour l'ensemble, puis les autres par identifiant) : le branchement fera attendre chacun un peu plus selon son rang. Si deux lots sortent quand même en même temps, tout le monde garde les deux, ouvre avec l'un ou l'autre, et scelle avec celui dont l'identifiant est le plus petit : tous convergent sur la même clé.

**Quand quelqu'un entre** (affectation d'un membre en attente, nomination d'un leader d'ensemble sans groupe). La liste de l'ensemble et celle de son groupe changent : deux clés neuves, enveloppées pour lui aussi. Il ne reçoit jamais les clés d'avant : ce qui a été partagé pendant qu'il attendait lui reste fermé, même s'il en a gardé les octets.

**Quand quelqu'un sort** (retiré par le créateur, parti de lui-même, remis en attente, groupe supprimé, poste d'accès retiré). Les listes dont il sort changent, donc leurs clés. Les clés neuves ne sont pas enveloppées pour lui. Son téléphone garde les anciennes : il peut encore lire ce qui a été scellé avec elles, y compris par un téléphone qui n'a pas encore appris le changement (limite inévitable sans autorité centrale : § 8.6). Ce qu'il scellerait lui-même avec une ancienne clé est refusé par tous ceux qui ont appris le changement.

**Retour d'un téléphone hors réseau.** Il rattrape d'abord les opérations (§ 6.3) : il sait alors quelles sont les listes de lecteurs. Puis n'importe quel téléphone, lecteur ou non, lui remet les lots du moment (`KeyRing.batchesFor`) : il y trouve sa copie. Dans l'autre ordre (lots avant opérations), les lots attendent et sont réessayés. Il n'a besoin ni de l'émetteur ni d'un administrateur en ligne. Les anciennes clés (16 par unité au plus) restent un temps pour ouvrir ce qui a été scellé juste avant un changement.

**Départ, retrait, dissolution sur le téléphone concerné.** `KeyRing.clear()` oublie tout. Un ensemble dissous n'a plus de lecteurs : rien ne se scelle, rien ne s'émet.

### 8.4 Pourquoi une clé par groupe

Le fil « mon groupe » (R7) et les croquis et waypoints d'un groupe ne doivent être lus que dans ce groupe (et ses sous-groupes). Avec une seule clé pour tout l'ensemble, ce ne serait qu'un filtre d'affichage : tout membre réparti, d'un autre groupe, pourrait les lire avec une application modifiée, et un administrateur hors du groupe aussi. La sécurité étant exigée dans ce mode, chaque unité a sa clé. Cela ne coûte presque rien de plus : c'est le même code que pour l'ensemble, et un lot de groupe est petit (517 octets pour 6 lecteurs). Le filtre hiérarchique devient réel : ce qu'un leader fait monter d'un niveau est scellé de nouveau, pour les lecteurs de l'unité du dessus.

### 8.5 Sceller et ouvrir un partage (`ShareSeal`)

- **Sceller** : refusé d'abord si l'auteur n'a pas le droit (`mayShare`), puis s'il n'a pas la clé du moment. AES-256-GCM avec une clé dérivée de la clé de contenu **et de l'auteur** (deux auteurs ne tirent jamais leurs nonces au hasard sous la même clé), nonce de 12 octets au hasard ; l'en-tête en clair (ensemble, unité, clé, sorte, auteur) est authentifié avec le reste.
- **Signature de l'auteur**, à l'intérieur de la partie scellée, pour les sortes que les règles nomment (`HierarchyRules.signedShares`) : **toutes au départ**, positions comprises. Sans elle, n'importe quel lecteur d'une unité pourrait partager au nom d'un autre : une clé commune dit seulement « un des lecteurs ». C'est ce qui rend le filtre du § 4.2 réel : un membre a la clé de l'ensemble (pour les positions), il ne peut pas pour autant y écrire un message au nom du leader. Coût : 64 octets et une signature par partage (§ 12, question 1, pour les positions).
- **Ouvrir**, dans l'ordre : le téléphone détient la clé nommée (sinon `NO_KEY`) ; il lit encore cette unité (`NOT_A_READER`) ; la clé ouvre (`UNDECIPHERABLE`) ; **l'auteur a le droit, dans l'état du moment**, de partager cette sorte dans cette unité (`NOT_ALLOWED` : qui a été retiré, a changé de groupe ou a perdu son poste ne partage plus, même avec une clé qu'il a gardée) ; puis, pour une sorte signée, la signature est celle de l'auteur avec la clé de son admission (`FORGED`) ; pour une sorte non signée, la clé doit être celle des lecteurs du moment (`STALE_KEY`), puisque seule la clé dit alors qui a scellé.
- Un partage signé, scellé avec la clé précédente par un téléphone qui n'avait pas encore appris un changement, est donc encore lu par ceux qui détiennent cette clé : rien ne se perd pendant qu'un changement se propage.

Testé (`ShareSealTest`, `HierarchyKeysTest`) : allers-retours des quatre sortes, tailles ; partage qu'un téléphone modifié scelle sans en avoir le droit, ou au nom d'un autre : refusé partout ; octets abîmés, coupés, bruit : toujours un refus, jamais autre chose.

### 8.6 Ce que ce schéma ne fait pas

- **Recevoir.** Le moteur dit à qui adresser (`readersOf`) ; empêcher un téléphone en attente de **recevoir** les octets chiffrés dépend du transport : sur une liaison directe on n'envoie qu'aux lecteurs ; par le serveur de coordination, qui relaie à tous ceux qui ont la clé de l'invitation, il verra passer des octets qu'il ne peut pas ouvrir. La fermeture garantie est celle du déchiffrement.
- **Pendant qu'un changement se propage**, un téléphone qui ne l'a pas appris scelle encore pour l'ancienne liste : celui qui vient d'en sortir lit ces partages-là. Cela dure le temps que l'opération arrive.
- **Un membre réparti malveillant** peut toujours recopier ce qu'il lit, et remettre un lot ou une clé à qui il veut : aucune cryptographie ne l'empêche.
- **La liste des membres** (noms, groupes, postes) est lisible par tout membre admis, en attente compris, et par quiconque détient encore la clé de l'invitation (un membre retiré, tant que l'invitation n'est pas renouvelée) : § 9 et § 12, question 2.
- **Rejeu.** Une trame captée et renvoyée plus tard par quelqu'un qui n'a pas la clé reste valable tant que son auteur a le droit : la date ou le numéro du partage est dans la charge utile, à contrôler au branchement (comme le maillage le fait déjà pour les messages).
- **Ce qui a été partagé avant une arrivée ou pendant une longue absence** n'est pas renvoyé : § 12, question 4.
- **Téléphone perdu, changé ou restauré** : nouvelles clés, donc nouveau membre (§ 12, question 6).
- **Empreintes sur 64 bits** (identifiants de membre et d'ensemble) : assez contre l'erreur et le bricolage ; la clé de signature inscrite à l'admission ferme la collision pour les membres. Passer à 128 bits changerait le format.

## 9. Ce qu'il faudra pour brancher

Rien de ceci n'est fait.

**Transport** (`Wire.kt`, `MeshNode.kt`, `:p2p`) :

- Un ensemble est un partage **à part** du groupe actuel : sa propre invitation, ses propres trames. L'invitation porte l'identifiant de l'ensemble et la **clé de l'invitation** ; celle-ci scelle (comme `GroupCrypto` aujourd'hui) les trames de quatre genres nouveaux : opérations (`OPS`), résumé (`SUMMARY`), lot de clés (`KEYS`), partage (`SHARE`). Les versions qui ne les connaissent pas les écartent déjà.
- **Arrivée** : le nouveau téléphone montre son nom et ses deux clés publiques (une demande d'entrée signée, à écrire) ; un administrateur écrit `AdmitMember` avec ces clés. Il attend ensuite d'être affecté.
- À l'ouverture d'une liaison, après `HELLO` : envoyer son résumé, répondre par `missingFor`, puis les lots de `batchesFor`. Refaire de temps en temps.
- **Après chaque changement d'état** : `KeyRing.retry` ; pour chaque unité de `KeyRing.missing`, attendre un délai selon son rang dans `keyIssuers`, puis `KeyRing.issue` si aucun lot n'est arrivé. Les partages durables (messages, croquis, waypoints) en attente de clé se gardent et se scellent quand elle arrive ; une position se saute.
- **Adresser** : un partage ne s'envoie qu'aux lecteurs de son unité là où le transport le permet (§ 8.6).
- Taille : une trame fait 16 Kio au plus, et une trame de contrôle du serveur de coordination 4 096 octets : `missingFor(résumé, max)` avec `max` ≈ 12 opérations par trame du serveur ; un lot de clés tient dans une trame du serveur jusqu'à 69 lecteurs, dans une trame directe jusqu'à 289 ; au-delà il faudra le couper (à écrire quand les limites monteront).
- Ce que `add` et `offer` répondent sert au journal d'événements : `CONFLICT_…`, `UNAUTHENTICATED`, `MALFORMED`, `FULL`, `FORGED`, `WRONG_RECIPIENTS`, `DAMAGED` sont à signaler (`Problems.expected`) ; `HELD` et `UNKNOWN_READERS` déclenchent un rattrapage.
- **Renouveler l'invitation** après un retrait (nouvelle clé de l'invitation, distribuée comme une clé de contenu à tous les membres admis) : à écrire, pour qu'un membre retiré ne lise plus la liste des membres.

**Clés du téléphone** (`:data` ou `:p2p`) : une implémentation de `PhoneKeys` avec la clé de signature dans le Keystore (non exportable) et la clé d'accord dans le Keystore à partir de l'API 31, sinon dans un fichier scellé par une clé du Keystore. Les clés ne vont pas dans la sauvegarde (§ 12, question 6).

**Stockage** (`:data`) : garder les opérations (`HierarchyReplica.ops()` → `HierarchyWire.encodeOpBlocks`), relire par `HierarchyReplica.restore` ; l'état se recalcule. Garder les clés de contenu détenues (à protéger par le Keystore) et les lots vérifiés. Tout effacer en quittant l'ensemble, quand on en est retiré, ou à sa dissolution.

**Interface** (après validation des maquettes de P39.5, `CLAUDE.md` § « aperçu validé avant le code ») : tout se lit dans `HierarchyState` (`rolesOf`, `isWaiting`, `waiting`, `groupOf`, `membersOf`, `visibleTo`, `shareUnits`, `unitsReadBy`), se demande par `issue`, se grise par `HierarchyReducer.check`, et les refus arrivés après coup se lisent dans `history()`. Un bouton ne doit jamais supposer qu'un changement écrit est définitif. « Quitter » pour le créateur propose : désigner un successeur (`TransferCreator` puis `RemoveMember` de lui-même), ou dissoudre (`Dissolve`).

**Fil d'exécution** : `HierarchyReplica` et `KeyRing` sont synchronisés ; `HierarchyState` est immuable, on peut la donner telle quelle à l'interface.

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

## 10 ter. Précisions de l'utilisateur (2026-11-13)

Réponses aux six précisions demandées au § 10 bis (P1 à P6, dans l'ordre de la liste ci-dessus). Bloc recopié tel quel :

```text
P1 1 · Les deux restent pour de bon | précision : groupe simple sera le partage entre copain, l'ensemble sera le partage professionnel avec du monde et des chefs
P2 2 · Croquis et waypoints, pas les messages | précision : Mettre en place un system qui pourra entreprendre les sous-groupes, un leader de sous-groupe peut partager dans les groupes, mais pas l'ensemble, un leader de groupe peut partager dans l'ensemble mais pas au dessus (si il y avait un au-dessus) toujours un ce système de filtre hiérachique
P3 1 · Le créateur nomme les administrateurs ; les administrateurs nomment le reste
P4 1 · Un adjoint par leader de groupe et un adjoint du leader d'ensemble
P5 1 · Vraiment fermé | précision : le partage d'ensemble à pour but un utilisation professionnel, la sécurité est de mise pour se mode de partage
P6 1 · Il désigne un successeur avant de partir | précision : Si il ne désigne personne, l'ensemble est dissous. Choix proposer lorsqu'il souhaite partir
Remarques : Nous allons renommer les mode de partage pour plus de clareté.
"Group d'ami" pour le mode simple
"Communautaire" pour le grand nombre
c'est une idée, fais moi 3 propositions par noms, tu trouvera peut être mieux
```

**Lecture de la session principale** (interprétation, à confirmer sur les maquettes du second tour ; le moteur est mis à ces règles par P39.4) :

1. **Deux modes durables.** Le groupe simple (partage entre amis, sans exigence de sécurité nouvelle, code inchangé) et l'ensemble (usage professionnel, avec des chefs, sécurité exigée). Un téléphone est dans l'un ou dans l'autre (R15). Les deux modes seront renommés : noms proposés à l'utilisateur, non choisis à cette date.
2. **Filtre hiérarchique du partage, écrit pour N niveaux** (deux aujourd'hui, R17) : un membre partage dans sa propre unité ; le leader d'une unité (et son adjoint) partage croquis et waypoints **un niveau au-dessus**, jamais plus haut. À deux niveaux : le leader de groupe partage croquis et waypoints à tout l'ensemble, pas de message ; les messages à tout l'ensemble restent au leader d'ensemble, à son adjoint et aux administrateurs (R6).
3. **Nominations** : le créateur nomme et retire les administrateurs ; les administrateurs nomment le reste (leader d'ensemble, leaders de groupe, adjoints, répartition dans les groupes). Seul le créateur retire un membre (R9).
4. **Adjoints** : un par leader de groupe, un pour le leader d'ensemble, avec les droits de leur leader (R8) ; les rôles se cumulent.
5. **Membre en attente : fermé pour de bon.** Tant qu'il n'est pas réparti dans un groupe, son téléphone ne reçoit ni ne peut déchiffrer aucun partage (positions, croquis, waypoints, messages) : la séparation est cryptographique, pas un filtre d'affichage. Le créateur, les administrateurs et le leader d'ensemble gardent l'accès même sans groupe (déduction de la session principale, annoncée à l'utilisateur).
6. **Départ du créateur** : au moment de quitter, l'application lui propose de désigner un successeur (qui devient le créateur) ; s'il ne désigne personne, l'ensemble est dissous pour tout le monde (R10).
7. **Sécurité du mode ensemble** (R18, P5) : chaque téléphone signe ses changements (`OpAuth` activé dans ce mode seulement).
8. **À prévoir sans le construire** : sous-groupes à plusieurs échelons (R17), plus de 30 membres et de 6 groupes (R19), mots de l'interface remplaçables (R20).

**Noms des deux modes (réponses de l'utilisateur, 2026-11-13)**, à trois questions de la session principale :

```text
1. Nom du mode simple : Entre amis
2. Nom du mode professionnel : Opération
3. Le mot « Ensemble » suit-il le nom du mode professionnel ? Oui mais change pour "Chef de l'organisation" aulieu de leader, de même pour les groupes avec "chef de groupe"
```

Lecture de la session principale (la réponse 3 reprend le mot « organisation » de l'exemple donné dans la question, alors que le nom choisi en 2 est « Opération » : à confirmer sur les maquettes de P39.5) : mode simple « **Entre amis** » ; mode professionnel « **Opération** » ; dans les écrans, « Ensemble » devient « Opération » (« Toute l'opération »), « Leader d'ensemble » devient « **Chef de l'opération** », « Leader de groupe » devient « **Chef de groupe** » ; « Groupe », « Administrateur », « Membre » et « Adjoint » restent. Ces mots remplacent ceux de R20 dans l'interface ; dans le code et dans ce document, « ensemble » et « leader » restent les noms techniques.

**Confirmé par l'utilisateur le 2026-11-13** (« Oui, opération ») : le chef de l'ensemble s'appelle « Chef de l'opération ».

## 11. Fichiers et tests

| Fichier (`core/src/main/kotlin/fr/toolmap/core/hierarchy/`) | Contenu |
|---|---|
| `HierarchyRules.kt` | `Role`, `Operation`, `Reach`, `RightsTable`, `Audience`, `VisibilityTable`, `ShareKind`, `ShareReach`, `ShareTable`, `HierarchyLimits`, `HierarchyRules` |
| `HierarchyState.kt` | `MemberKeys`, `Member`, `Group`, `HierarchyState` (`rolesOf`, `isWaiting`, `hasAccess`, `readersOf`, `unitsReadBy`, `mayShare`, `shareUnits`, `canSee`, `pathToRoot`, `inconsistency`), `Names` |
| `Op.kt` | `Post`, `Change`, `Op` (ordre `Op.ORDER`), `OpProof` |
| `HierarchyReducer.kt` | `HierarchyReducer.reduce` / `check`, `Rejection` |
| `HierarchyReplica.kt` | `HierarchyReplica` (`add`, `issue`, `summary`, `missingFor`, `history`, `ops`, `readers`, `create`, `restore`), `AddResult`, `IssueResult` |
| `HierarchyWire.kt` | Format binaire version 2, `Summary` |
| `OpAuth.kt` | `PhoneKeys`, `P256` (clés et signatures sous une seule forme, accord de clés), `OpAuth` (identifiants par empreinte, signature des opérations) |
| `HierarchyKeys.kt` | `Readers`, `ContentKey`, `KeyBatch`, `KeyOffer`, `ContentKeys` (émettre, vérifier, ouvrir un lot), `KeyRing` (les clés d'un téléphone), `keyIssuers` |
| `ShareSeal.kt` | `Share`, `ShareSeal.seal` / `open`, `ShareRefusal` |

Tests (`core/src/test/kotlin/fr/toolmap/core/hierarchy/`, 89 tests) :

| Fichier | Ce qu'il vérifie |
|---|---|
| `HierarchyReducerTest` (32) | Une règle de l'utilisateur par test, le nom du test commence par la réponse vérifiée (V3, R1 à R19) ; refus et leurs motifs ; tables en données ; limites ; sous-groupes à deux niveaux |
| `HierarchyReplicaTest` (22) | Cas du § 6.2 dans les deux ordres d'arrivée (attente, adjoints, retrait, passage de main, dissolution compris), rattrapage, stockage, deux opérations sous un numéro, convergence sur 50 graines |
| `OpAuthTest` (6) | § 8.1 : signature obligatoire, refus des opérations non ou mal signées, forme unique des clés et des signatures |
| `HierarchyKeysTest` (14) | § 8.3 et 8.4 : membre en attente fermé, arrivée, sortie (quatre façons, téléphone informé ou non), clé par groupe, sous-groupes, retour d'un absent, deux émetteurs à la fois, lots refusés, dissolution |
| `ShareSealTest` (8) | § 8.5 : sceller et ouvrir, partages forgés ou hors droits refusés, téléphone en retard, octets abîmés |
| `HierarchyWireTest` (7) | § 7 : allers-retours, refus, octets abîmés, pour tous les genres de blocs |

Commande : `./gradlew :core:test --tests 'fr.toolmap.core.hierarchy.*'` (≈ 30 s).

Utilise de l'existant, sans le modifier : `ByteWriter` / `ByteReader` / `WireException` (`p2p/Bytes.kt`), `GroupCrypto.hkdfSha256` (`p2p/GroupCrypto.kt`), `Problems` / `catchExpected` (`problem/`). Aucune dépendance nouvelle.

## 12. Questions du second tour

Pour l'utilisateur, à poser par la session principale. Le premier choix est le choix recommandé ; « départ » marque ce que le moteur fait aujourd'hui. Les mots sont ceux des écrans (« opération », « chef »).

1. **Faut-il rendre impossible qu'un membre déjà réparti envoie une fausse position au nom d'un autre ?**
   Chaque téléphone signe déjà ses messages, ses croquis et ses waypoints : personne ne peut les envoyer au nom d'un autre. Pour les positions, envoyées toutes les quelques secondes, la même protection alourdit chaque envoi.
   a. Oui : les positions sont signées aussi (recommandé, départ). Chaque position envoyée pèse environ le double et demande un petit calcul de plus ; l'effet sur la batterie sera mesuré sur le terrain avant le premier usage réel.
   b. Non : les positions restent légères. Un membre réparti qui modifie son application pourrait alors afficher une fausse position sous le nom d'un autre membre ; messages, croquis et waypoints restent protégés.
   Ce choix se fait à la création d'une opération : le changer ensuite demande d'en créer une nouvelle.

2. **Que voit un membre « en attente » ?**
   Il ne voit jamais ni position, ni message, ni croquis, ni waypoint : c'est acquis. La question porte sur la liste des personnes.
   a. Il voit le nom de l'opération, les noms des membres et des groupes, et qu'il est « en attente » (recommandé, départ). C'est le plus simple, et son téléphone peut vérifier lui-même qui le répartit.
   b. Il ne voit que « en attente d'être réparti » ; la liste lui arrive au moment où il est réparti. Demande un travail de plus (cacher aussi la liste des changements).

3. **Un administrateur peut-il remettre un membre « en attente » ?**
   Seul le créateur retire quelqu'un de l'opération (votre réponse R9). Remettre en attente, c'est sortir le membre de son groupe sans le retirer : il reste inscrit, mais il ne reçoit plus rien jusqu'à ce qu'on le répartisse de nouveau.
   a. Oui (recommandé, départ) : c'est le moyen rapide, pour un administrateur, de couper l'accès d'un téléphone perdu ou d'une personne partie sans prévenir, quand le créateur n'est pas joignable.
   b. Non : seul le créateur peut couper l'accès de quelqu'un, en le retirant. Un administrateur peut seulement le changer de groupe.

4. **Un membre qui vient d'être réparti, ou qui revient après une longue absence, retrouve-t-il ce qui a été partagé avant ?**
   Aujourd'hui le moteur ne lui donne que ce qui est partagé à partir de son arrivée ou de son retour.
   a. Il retrouve les waypoints et les croquis encore affichés chez les autres membres de son groupe et de l'opération (un point de rendez-vous posé avant son arrivée, par exemple), mais pas les anciens messages ni les anciennes positions (recommandé ; travail à faire au branchement : les téléphones présents lui renvoient ces objets).
   b. Rien d'avant : il ne voit que ce qui est partagé à partir de maintenant (départ).
   c. Tout, anciens messages compris.

5. **L'alerte (détresse) d'un membre, dans une opération, part à qui ?**
   Le filtre hiérarchique limite ce qu'un membre partage à son groupe ; l'alerte n'est pas encore écrite dans le moteur.
   a. À toute l'opération, quel que soit le membre réparti qui la déclenche (recommandé : la sécurité des personnes passe avant le filtre).
   b. À son groupe, plus les chefs et les administrateurs.
   c. À son groupe seulement.

6. **Si le créateur perd son téléphone (ou en change) ?**
   L'identité d'un membre est attachée à son téléphone, pour que personne ne puisse se faire passer pour lui : un téléphone neuf est un nouveau membre, à faire entrer et à répartir de nouveau. Pour un membre ordinaire, c'est sans gravité. Pour le créateur, plus personne ne peut alors retirer un membre, nommer un administrateur ni dissoudre.
   a. L'opération continue avec ses administrateurs jusqu'à sa fin, puis on en crée une nouvelle (recommandé pour commencer : rien de plus à construire, aucune porte dérobée). L'application rappelle au créateur de désigner un successeur avant de changer de téléphone.
   b. Le créateur désigne à l'avance un remplaçant de secours, qui peut prendre sa place si le créateur ne donne plus signe de vie (travail en plus, et il faut choisir après combien de temps).
   c. L'identité du créateur peut être copiée dans une sauvegarde chiffrée et restaurée sur un autre téléphone (plus simple pour lui, moins sûr : qui a la sauvegarde est le créateur).

## 13. Remontée P39.4

Pour la session principale (session 103, 2026-11-13, branche `claude/affectionate-dirac-mv4a6s`, partie de `conception/groupes-hierarchiques`).

### Ce qui a changé

- **Modèle** : créateur unique et transférable, administrateurs (zéro ou plusieurs), adjoint pour le leader d'ensemble et pour chaque leader de groupe, membre « en attente », dissolution (état final), clés publiques de chaque membre dans l'état, limites en données (30 / 6 / 1 niveau au départ), groupes sur N niveaux (règles écrites et testées à deux niveaux, fermées à un par la limite de départ).
- **Table des droits** aux réponses R1, R3, R5, R9, R13, R14 et aux précisions P3, P4, P6 ; **table des partages** nouvelle (R2, R4, R6, R7, P2) ; table de visibilité gardée (R2), avec la règle que qui attend ne voit ni n'est vu.
- **Opérations** : `SetPost` / `ClearPost` (leader ou adjoint, d'un groupe ou de l'ensemble) remplacent les trois opérations de P39.2 ; `TransferCreator`, `Dissolve` ; `AdmitMember` et `CreateEnsemble` portent les clés du membre. Le créateur n'est plus leader d'ensemble d'office : tous les postes sont vacants à la création.
- **Signatures activées** : toute opération est signée, sans option ; non signée ou mal signée, elle n'est ni gardée ni appliquée. Clés et signatures sous une seule forme, vérifiée par le moteur.
- **Schéma de clés** (ADR-126, § 8) : une clé de contenu par unité et par liste de lecteurs, enveloppée pour chaque lecteur ; logique pure écrite (`ContentKeys`, `KeyRing`), scellement et signature des partages (`ShareSeal`). Tous les partages sont signés au départ.
- **Règle d'ordre changée** (§ 6.1) : l'horloge d'un auteur ne recule pas. Trouvé en écrivant les tests du passage de main : avec la règle de P39.2, un ancien créateur pouvait effacer son propre passage de main, et un créateur sa dissolution, en écrivant ensuite une opération d'horloge plus petite.
- **Format binaire** version 2 ; la version 1, jamais déployée, n'est pas lue.

### Lectures faites par cette session (à confirmer ou à corriger ; chacune est une case ou une ligne)

- **Le créateur fait aussi tout ce que font les administrateurs** (admettre, répartir, nommer, gérer les groupes, écrire à tout l'ensemble). Sinon un ensemble neuf, sans administrateur, serait bloqué. Autre lecture possible : il doit d'abord se nommer administrateur.
- **L'adjoint du leader d'ensemble garde l'accès sans groupe**, comme son leader (« les adjoints ont les droits de leur leader »).
- **Un administrateur ne peut pas renoncer seul à son rôle** : « le créateur nomme et retire les administrateurs ». Il peut quitter l'ensemble.
- **Aucun leader ne peut renoncer seul à son poste** : ce sont les administrateurs qui nomment et laissent vacant.
- **L'ancien créateur redevient un membre comme un autre** : s'il n'a ni groupe ni poste, il attend un groupe (il perd donc l'accès aux partages en passant la main, sauf si on le nomme administrateur ou qu'on le répartit).
- **Supprimer un groupe remet ses membres en attente** (ils perdent l'accès jusqu'à être répartis ailleurs) ; un groupe qui a des sous-groupes ne se supprime pas.
- **Les leaders de groupe n'envoient pas de message un niveau au-dessus**, seulement croquis et waypoints (P2), à tous les niveaux ; un administrateur ou un leader d'ensemble hors d'un groupe ne lit pas le fil de ce groupe.
- **Départ du créateur** : le moteur refuse qu'il parte tant qu'il est le créateur ; les deux issues (successeur, dissolution) sont deux opérations que l'écran propose. « S'il ne désigne personne, l'ensemble est dissous » est donc un choix de l'écran, pas un effet automatique d'un départ.

### Ce qui est vérifié, et comment

- `./gradlew :core:test --tests 'fr.toolmap.core.hierarchy.*'` : 89 tests, 0 échec (liste au § 11). Un test par règle ; les opérations refusées ont leurs tests (motifs compris) ; convergence dans les deux ordres pour chaque cas du § 6.2 et sur 50 tirages au hasard.
- « Vraiment fermé » : `HierarchyKeysTest` essaie chaque copie de chaque lot de clés avec les clés d'un téléphone en attente (aucune ne s'ouvre), et vérifie qu'aucune trame partagée ne s'ouvre chez lui, avant comme après son affectation pour ce qui a été partagé avant.
- Commandes et résultats complets : journal, session 103.
- Aucun fichier du groupe simple n'est modifié (`git diff --stat origin/conception/groupes-hierarchiques` : paquet `hierarchy`, ses tests, `docs/`, `version.properties`).

### Ce qui n'est pas fait

- **Rien n'est branché** : ni transport, ni stockage, ni clés dans le Keystore, ni écran (§ 9). La clé de l'invitation, la demande d'entrée d'un arrivant et le renouvellement de l'invitation après un retrait sont décrits, pas écrits.
- **Deux opérations sous un même numéro, exprès** (§ 6.4) : un ancien créateur au téléphone modifié peut encore remplacer son passage de main. C'est la limite la plus sérieuse du moteur pour un usage où la sécurité compte ; la parade (figer l'auteur pris sur le fait) est à écrire avant tout usage réel. Avec elle : le point de reprise contre l'antidatage et le journal rempli exprès.
- **Rien n'est mesuré sur un téléphone** : coût des signatures (surtout avec une clé dans le Keystore) et des accords de clés, batterie. Les tailles sont calculées et testées, pas les durées.
- **Pas de revue par un spécialiste** : le schéma n'utilise que des briques standard de la plateforme, mais leur assemblage est celui de cette session. Avant un usage professionnel réel, le faire relire.
- **Limites d'un ensemble fixées à sa création** : pas d'opération pour les monter en cours de route ; au-delà de 69 lecteurs un lot de clés ne tient plus dans une trame du serveur (à couper).
- **Alerte** : pas de sorte de partage « alerte » (question 5). **Historique** : rien n'est renvoyé à un arrivant ni à un revenant (question 4).
- **Renommer** l'ensemble ou un membre, déplacer un groupe sous un autre parent : pas d'opération.
- **Table de visibilité plus étroite que « tout le monde »** : resterait un filtre d'affichage (les positions sont scellées pour tout l'ensemble).
