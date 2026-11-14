# Groupes hiérarchiques du partage de position (P39)

> Demande de l'utilisateur (2026-11-13) : `docs/PLAN.md` § P39. Décisions d'architecture : ADR-125 (journal d'opérations), ADR-126 (clés de contenu et signatures), ADR-127 (canaux, noms cachés, objets durables).
> Ce document décrit le **moteur** (P39.2, mis aux règles de l'utilisateur par P39.4, puis à ses choix du second tour et à ses précisions S1 à S7 le 2026-11-14) : la logique, dans `:core`, paquet `fr.toolmap.core.hierarchy`. Rien n'est branché : ni transport, ni stockage, ni écran. Le système de groupe actuel (`fr.toolmap.core.p2p`, `docs/specs/p2p-sharing.md`) n'est pas modifié.
> Les questions du premier tour sont au § 10, les réponses de l'utilisateur aux § 10 bis à 10 quinquies (le moteur les applique), les questions du second tour au § 12, la remontée de la session 103 au § 13, celle de la mise aux choix du second tour (§ 10 quater et 10 quinquies) au § 14.
> Mots : dans les écrans, « ensemble » se dit « opération » et « leader » se dit « chef » (§ 10 ter) ; dans le code et dans ce document, `ensemble` et `leader` restent les noms techniques.

## 1. Vocabulaire

| Mot | Sens | Dans le code |
|---|---|---|
| Ensemble | Le tout : un nom, des membres, des groupes, des règles | `HierarchyState` |
| Groupe | Un enfant de l'ensemble (ou d'un autre groupe quand les limites le permettent) : un nom, des membres, un leader, un adjoint | `Group` |
| Unité | L'ensemble ou un groupe : ce qui a un leader et un adjoint. L'ensemble est l'unité `ROOT` | `HierarchyState.ROOT`, `isUnit` |
| Membre | Un téléphone : identifiant (empreinte de sa clé de signature), nom, ses deux clés publiques, zéro ou un groupe | `Member`, `MemberKeys` |
| En attente | Membre admis qui n'a pas de groupe et aucun poste qui donne l'accès sans groupe | `HierarchyState.isWaiting`, `Role.WAITING` |
| Commandement | Ceux qui commandent une unité : son leader et son adjoint ; pour l'ensemble, en plus, les administrateurs et le créateur | `HierarchyState.commandOf` |
| Opération | Un changement demandé par un membre, signé, tel qu'il voyage et se garde | `Op`, `Change` |
| Copie | Ce qu'un téléphone garde d'un ensemble : toutes les opérations reçues et l'état qu'elles donnent | `HierarchyReplica` |
| Canal | Un lieu où l'on partage : une unité elle-même, ou l'une des voies qui en partent (§ 4.2) | `Channel`, `Lane` |
| Lecteurs | Les membres qui lisent ce qui est partagé dans un canal, à un moment donné | `Readers`, `HierarchyState.readersOf` |
| Clé de contenu | La clé qui scelle ce qui est partagé dans un canal, pour une liste de lecteurs exacte | `ContentKey`, `KeyBatch`, `KeyRing` |
| Partage | Une position, un croquis, un waypoint, un message, une alerte ou des noms, scellé pour les lecteurs d'un canal | `Share`, `ShareSeal` |
| Objet durable | Un croquis ou un waypoint : il reste affiché chez les autres jusqu'à ce qu'on le retire | `SharedRecord`, `SharedObjects` |
| Nom caché | Un nom tel que les opérations le portent : une empreinte qui n'en dit rien ; la carte qui l'ouvre voyage à part | `HiddenName`, `NameCard`, `NameBook` |

## 2. Modèle

- **Deux modes durables** (§ 10 ter, point 1) : le groupe simple, inchangé, et l'ensemble décrit ici. Un téléphone est dans l'un ou dans l'autre ; ce choix est fait par l'application, pas par le moteur.
- **Ensemble** : identifiant (64 bits), nom, **créateur** (un, toujours, transférable à un administrateur), administrateurs (zéro ou plusieurs), leader d'ensemble et son adjoint (postes qui peuvent rester vacants), règles (§ 4), et s'il est **dissous** (état final).
- **Groupes** : identifiant (64 bits, tiré au hasard par le téléphone qui le crée, jamais 0), nom, parent, leader, adjoint (deux membres différents du groupe, ou personne).
- **Membres** : identifiant, nom, clés publiques, groupe (**un seul, ou aucun** : une nouvelle affectation remplace l'ancienne, V3).
- **Limites, en données** (`HierarchyLimits`, dans les règles de l'ensemble) : 30 membres, 6 groupes, 1 niveau de groupes au départ (R17, R19). Un ensemble créé avec d'autres limites (jusqu'à 4 096 membres, 1 024 groupes, 8 niveaux : ce que le format porte) suit les mêmes règles, sans autre moteur. Les limites d'un ensemble sont fixées à sa création.
- **Parent-enfant, écrit pour N niveaux** : chaque groupe porte son parent (`Group.parent`), l'ensemble ou un autre groupe. Les règles ci-dessous (postes, canaux, lecteurs, suppression) sont écrites pour une profondeur quelconque et testées à deux niveaux de groupes ; avec la limite de départ (1 niveau), créer un groupe dans un groupe est refusé (`TOO_DEEP`). Un membre appartient à un groupe précis, feuille ou non ; le leader d'un groupe est un membre de ce groupe même, pas d'un de ses sous-groupes.
- **Noms** (ensemble, groupe, membre) : non vides, sans caractère de contrôle, 64 octets UTF-8 au plus (`Names`). **Les opérations et l'état ne portent pas le nom mais son empreinte** (`HiddenName`, § 8.8) : un téléphone en attente détient les opérations et ne doit lire aucun nom (Q2).
- L'identifiant d'un groupe supprimé ne revient jamais (`deletedGroups`). Un groupe qui a encore des sous-groupes ne se supprime pas (`HAS_SUBGROUPS`) : on supprime d'abord ce qui est dessous.

Un état construit par le moteur est toujours cohérent ; `HierarchyState.inconsistency()` le vérifie (tests, et lecture d'un état venu d'octets).

### 2.1 En attente

Un arrivant est admis **sans groupe** (V2) : il est « en attente » jusqu'à ce qu'un administrateur l'affecte à un groupe (R1). Tant qu'il attend :

- il n'est lecteur d'**aucun canal des autres** : aucune de leurs clés de contenu n'est enveloppée pour son téléphone, il ne peut donc rien déchiffrer de ce qu'ils partagent (§ 8), et le transport n'a rien à lui adresser ;
- il ne lit **aucun nom** (Q2) : les opérations qu'il détient ne portent que des empreintes ; les noms voyagent scellés avec la clé de tout l'ensemble, qu'il n'a pas (§ 8.8) ;
- **sa position part aux administrateurs seulement** (Q10) : il a une voie à lui (`Lane.WAITING`), lue par lui, les administrateurs et le créateur. Il y envoie sa position et le nom qu'il a choisi, rien d'autre ; personne d'autre n'a le droit d'y écrire : rien ne lui arrive ;
- il ne voit personne sur la carte ; seuls les administrateurs et le créateur l'y voient (`canSee`) ;
- il peut quitter l'ensemble, rien d'autre.

Gardent l'accès **sans groupe** : le créateur, les administrateurs, le leader d'ensemble et son adjoint (§ 10 ter, point 5 ; l'adjoint parce qu'il a les droits de son leader). L'accès suit le poste : un administrateur sans groupe à qui le créateur retire son rôle attend un groupe.

Redeviennent en attente : un membre sorti de son groupe par un administrateur (`UnassignMember`, Q12), les membres d'un groupe supprimé (Q7), l'ancien créateur qui a passé la main et n'a ni groupe ni poste. Dans tous ces cas, les clés de tous les canaux qu'ils lisaient sont à refaire sans eux (§ 8.3) : ils ne lisent plus la suite.

## 3. Rôles

Les rôles ne sont pas stockés : ils se lisent dans l'état (`HierarchyState.rolesOf`). **Ils se cumulent** (R8).

| Rôle | Qui | Combien |
|---|---|---|
| Créateur (`CREATOR`) | Celui qui a créé l'ensemble, ou l'administrateur à qui il l'a passé | Un, toujours |
| Administrateur (`ADMIN`) | Nommé par le créateur | Zéro ou plusieurs |
| Leader d'ensemble (`ENSEMBLE_LEADER`) | Nommé par un administrateur ; membre avec ou sans groupe | Un, ou poste vacant |
| Adjoint du leader d'ensemble (`ENSEMBLE_DEPUTY`) | Nommé par un administrateur | Un, ou personne |
| Leader de groupe (`GROUP_LEADER`) | Nommé pour un groupe ; il doit en être membre | Un par groupe, ou aucun |
| Adjoint du leader de groupe (`GROUP_DEPUTY`) | Nommé pour un groupe ; il doit en être membre | Un par groupe, ou personne |
| Membre (`MEMBER`) | Tout membre admis, en attente ou non | Tous |
| En attente (`WAITING`) | Membre admis sans groupe ni poste d'accès ; tenu en plus de `MEMBER` | Tous ceux qui attendent |

- **Un adjoint a les droits de son leader** (R8, § 10 ter point 4) : c'est une règle du moteur, pas une case. Les tables n'ont rien d'écrit pour les adjoints ; ce que la table donne au leader, son adjoint l'a (`effectiveMask`), que le poste de leader soit pourvu ou vacant.
- **Un poste au plus par unité** : l'adjoint nommé leader quitte le poste d'adjoint, et inversement. Nommer quelqu'un à un poste remplace celui qui le tenait : un seul adjoint par leader (P4).
- **Quand un leader s'en va, son adjoint devient leader** (Q18, S4 ; `HierarchyRules.deputySucceeds`, une donnée de l'ensemble). « S'en va » : il quitte l'ensemble, en est retiré, est remis en attente, ou change de groupe (pour un leader de groupe) ; il quitte l'ensemble ou en est retiré (pour le leader d'ensemble, que le fait de sortir de son groupe ne touche pas). Le poste d'adjoint devient vacant ; sans adjoint, le poste de leader reste vacant. La promotion fait partie de l'opération qui fait partir le leader : elle donne le même résultat sur tous les téléphones (§ 6.2). Un administrateur qui laisse un poste vacant (`ClearPost`) ou y nomme quelqu'un d'autre ne promeut personne.
- **Postes vacants** : à la création, tous les postes sont vacants. Un groupe sans leader ne bloque rien (R12).
- **Créateur** : il y en a toujours exactement un. Il ne peut ni quitter l'ensemble ni en être retiré tant qu'il est le créateur (`IS_CREATOR`) : il passe d'abord la main à un administrateur (`TransferCreator` : son successeur devient le créateur aussitôt, sans avoir à accepter ; lui redevient un membre comme un autre), ou il dissout l'ensemble (`Dissolve`). L'application propose ces deux sorties quand il veut partir (R10, P6, Q4, Q13) : « partir sans successeur » est l'opération `Dissolve`.
- **Dissolution** : état final. Après elle, aucune opération ne s'applique (`DISSOLVED`), plus personne n'a accès, plus aucun canal n'a de lecteurs.
- **Si le téléphone du créateur est perdu** (S7) : rien n'est prévu pour le remplacer, aucune porte dérobée. Ce que l'ensemble peut encore faire et ne peut plus faire : § 8.6.

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
| Admettre un membre (`ADMIT_MEMBER`) | tous | tous | — | — | R1, Q5 |
| Retirer un membre (`REMOVE_MEMBER`) | tous (sauf lui-même) | — | — | soi-même (quitter) | R9, R13 |
| Nommer un administrateur (`ADD_ADMIN`) | tous | — | — | — | R14, P3 |
| Retirer un administrateur (`REMOVE_ADMIN`) | tous | — | — | — | R9, R14, Q14 |
| Créer, renommer, supprimer un groupe (`CREATE_GROUP`, `RENAME_GROUP`, `DELETE_GROUP`) | tous | tous | — | — | R14, Q7 |
| Affecter un membre à un groupe (`ASSIGN_MEMBER`) | tous | tous | — | — | R3, Q9 |
| Sortir un membre de son groupe (`UNASSIGN_MEMBER`) | tous | tous | — | — | R3, R13, Q12 |
| Nommer ou laisser vacant : leader de groupe, adjoint de groupe, leader d'ensemble, adjoint d'ensemble (`NAME_GROUP_LEADER`, `NAME_GROUP_DEPUTY`, `NAME_ENSEMBLE_LEADER`, `NAME_ENSEMBLE_DEPUTY`) | tous | tous | — | — | R14, P3, P4 |
| Passer la main (`TRANSFER_CREATOR`) | à un administrateur | — | — | — | R10, Q13 |
| Dissoudre (`DISSOLVE`) | oui | — | — | — | R10, P6 |

Ce que la table donne, point par point (un test chacun, `HierarchyChoicesTest`) : seuls le créateur et les administrateurs font entrer quelqu'un (Q5) ; le créateur crée son groupe et s'y place lui-même en créant l'ensemble, s'il le veut (Q9 : rien ne l'y oblige, il garde l'accès sans groupe) ; un administrateur remet un membre en attente (Q12) ; seul le créateur retire le rôle d'administrateur, un administrateur n'y renonce pas seul (Q14) ; le créateur nomme les administrateurs, les administrateurs nomment à tous les autres postes (P3).

Les leaders et leurs adjoints n'ont **aucun droit sur la hiérarchie** (R5) : ce qu'ils ont de plus qu'un membre, c'est ce qu'ils peuvent partager (§ 4.2). Un membre ne change pas de groupe seul (R13). Un administrateur ne retire personne de l'ensemble. La colonne du créateur contient celle des administrateurs : un ensemble peut n'avoir aucun administrateur. Le rôle « en attente » n'a aucune case : qui attend a les droits d'un membre (partir).

Pour `OWN_GROUP` (aucune case de la table de départ ne l'utilise ; les tests montrent une autre table), le groupe concerné est le groupe nommé, ou celui d'où sort le membre.

### 4.2 Où l'on partage : les canaux, et qui les lit

Six sortes de partage (`ShareKind`) : position, croquis, waypoint, message, **alerte** (Q6) et **noms** (`ROSTER`, § 8.8). On ne partage plus « dans une unité » mais dans un **canal** (`Channel`) : une voie (`Lane`) et ce dont elle est la voie. Chaque canal a ses lecteurs, donc ses clés (§ 8) : qui n'est pas lecteur d'un canal ne peut pas ouvrir ce qui s'y partage, quoi qu'affiche son application.

**Qui lit un canal** (`HierarchyState.readersOf`, une ligne par voie : ce sont les lectures des réponses de l'utilisateur, à changer là) :

| Voie | Canal | Lecteurs | Réponse |
|---|---|---|---|
| `UNIT` | L'ensemble (`Channel.ENSEMBLE`) | Tous ceux qui ont l'accès | R2, S2 |
| `UNIT` | Un groupe | Ses membres et ceux des groupes qui sont dessous ; **personne d'autre**, administrateurs et leader d'ensemble compris s'ils n'en sont pas membres | Q16 |
| `UP` | Ce qui remonte d'un groupe | Son leader, son adjoint, et le commandement de l'unité du dessus (pour un groupe sous l'ensemble : leader d'ensemble, son adjoint, administrateurs, créateur). Pas les autres leaders de groupe, pas les membres du groupe | Q3, S1 |
| `DOWN` | Ce qui descend vers un groupe précis | Ses membres (et dessous), et le commandement de l'unité du dessus, qui y écrit | Q15 |
| `LEADERS` | Entre tous les chefs | Le leader et l'adjoint de chaque unité (ensemble et groupes), les administrateurs, le créateur | Q6 |
| `WAITING` | La voie d'un membre en attente | Ce membre, les administrateurs, le créateur | Q10 |

Une voie où personne n'écrirait n'a pas de lecteurs, donc pas de clé : la voie montante d'un groupe sans leader ni adjoint, la voie descendante d'un groupe sans membre, la voie des chefs tant qu'aucun groupe n'a de leader ni d'adjoint.

**Qui écrit où** (`ShareTable`) : une case par sorte et par rôle contient des **portées**, chacune désignant un canal vu de l'auteur :

| Portée | Canal |
|---|---|
| `OWN_UNIT` | Son propre groupe |
| `UP` | La voie montante de son propre groupe |
| `ENSEMBLE` | Tout l'ensemble, où que soit l'auteur |
| `GROUP_BELOW` | La voie descendante d'un groupe juste sous l'unité que son rôle commande : un groupe précis |
| `UP_BELOW` | La voie montante d'un tel groupe : pour répondre à ce qu'il fait remonter |
| `LEADERS` | La voie des chefs |
| `WAITING` | Sa propre voie d'attente |

« L'unité que son rôle commande » : l'ensemble pour le créateur, un administrateur, le leader d'ensemble et son adjoint ; son groupe pour un leader de groupe et son adjoint. Deux règles tiennent quelle que soit la table : **on n'écrit que dans un canal qu'on lit** (il en faut la clé), et **qui attend n'a que les cases du rôle « en attente »** (le rôle « membre », qu'il tient aussi, ne lui donne rien à partager).

**Table de départ** (`ShareTable.DEFAULT`, une ligne de code par réponse) :

| Sorte | Créateur, administrateur, leader d'ensemble (et son adjoint) | Leader de groupe (et son adjoint) | Membre | En attente |
|---|---|---|---|---|
| Position | (comme membre) | (comme membre) | tout l'ensemble | sa voie d'attente |
| Croquis, waypoint | tout l'ensemble ; un groupe précis | remonter | son groupe | — |
| Message | tout l'ensemble ; répondre dans le fil du milieu d'un groupe | le fil du milieu de son groupe | son groupe | — |
| Alerte | leader d'ensemble seulement : tout l'ensemble | tous les chefs | son groupe | — |
| Noms | (comme membre) | (comme membre) | tout l'ensemble | sa voie d'attente |

Ce que cela donne :

- **Positions** (R2) : à tout l'ensemble, tout le monde voit tout le monde ; celle de qui attend, aux administrateurs seulement (Q10).
- **Dans un groupe** (Q16) : croquis, waypoints, messages et alertes d'un membre restent dans son groupe, fermé à tous les autres.
- **Remontée** (Q3, S1) : le leader d'un groupe et son adjoint font remonter croquis et waypoints ; les reçoivent le leader d'ensemble, son adjoint et les administrateurs (le créateur en est), pas les autres leaders de groupe. L'adjoint remonte comme son leader et lit ce que son leader a remonté. Plus aucun leader de groupe ne diffuse à tout l'ensemble.
- **Diffusion à tout l'ensemble** (S2) : le leader d'ensemble, son adjoint, les administrateurs, le créateur. Personne d'autre.
- **Envoi à un groupe précis** (Q15) : croquis et waypoints, par ceux qui diffusent à tout l'ensemble. Le groupe lit cette voie et n'y écrit pas : ce qu'il partage reste chez lui.
- **Alerte selon le rôle** (Q6) : un membre alerte son groupe ; un leader de groupe ou son adjoint alerte tous les chefs (et peut alerter son groupe, dont il est membre) ; le leader d'ensemble ou son adjoint alerte tout le monde ; un administrateur sans poste de chef alerte comme un membre (donc personne, s'il n'a pas de groupe : § 14, question 1).
- **Messages : trois fils** (S3) : « mon groupe » (le canal du groupe) ; le **fil du milieu** (la voie montante du groupe : son leader, son adjoint et le commandement de l'ensemble, qui y répond) ; « tout l'ensemble » (écrit par le commandement, lu par tous). Un fil du milieu par groupe, parce que les autres leaders de groupe ne doivent pas lire ce qu'un leader remonte (S1). L'autre lecture, un fil commun à tous les chefs, est un changement de deux cases (`LEADERS` à la place de `UP` et de `UP_BELOW`) : un test le montre.
- **Avec des sous-groupes** : les mêmes règles un niveau plus bas. Le leader d'un sous-groupe remonte au leader et à l'adjoint du groupe qui le contient, jamais plus haut.

`HierarchyState.mayShare(auteur, sorte, canal)`, `shareChannels(auteur, sorte)` et `channelsReadBy(membre)` répondent sans rien écrire.

### 4.3 Qui voit la position de qui (`VisibilityTable`)

Pour chaque rôle de celui qui regarde, les **publics** qu'il voit (`SAME_GROUP`, `GROUP_LEADERS`, `ENSEMBLE_LEADER`, `ADMINS`, `EVERYONE`). `HierarchyState.canSee(viewer, target)` : vrai si l'un des rôles de `viewer` voit l'un des publics dont `target` fait partie. Chacun se voit toujours. Qui est hors de l'ensemble ou en attente ne voit personne. Qui attend n'est vu que de ceux pour qui sa position est scellée (administrateurs, créateur), quelle que soit la table.

**Table de départ** : tout le monde voit tout le monde (R2). Une table plus étroite resterait un filtre d'affichage : les positions sont scellées avec la clé de tout l'ensemble (§ 8).

### 4.4 Comment un nom s'écrit sur la carte (`nameLabel`)

Q17 : le nom est écrit **en entier** sur son propre jeton, sur ceux des membres de son groupe et sur ceux de quiconque tient un poste de leader ou d'adjoint (d'un groupe ou de l'ensemble) ; les autres jetons portent des **initiales** (`Names.initials` : première lettre des deux premiers mots, ou deux premières lettres d'un mot seul, en capitales), le nom entier se lit en touchant le jeton. C'est une **règle d'affichage**, pas une fermeture : tout membre qui a l'accès détient tous les noms (§ 8.8). Ce que coûterait de le garantir par les clés : § 14.

## 5. Opérations

Chaque opération (`Op`) porte : l'ensemble, son **auteur**, son **numéro** chez cet auteur (`seq` : 1, 2, 3…, sans trou), l'**horloge logique** de l'auteur (`clock` : un de plus que toutes les opérations qu'il connaissait), le changement, et la **preuve** de son auteur (§ 8.1). Là où un changement porte un nom, c'est un nom caché (§ 8.8).

| Changement (`Change`) | Effet | Refusé quand (en plus des droits) |
|---|---|---|
| `CreateEnsemble(nonce, name, creatorName, creatorKeys, rules)` | Crée l'ensemble ; l'auteur en est le seul membre et le créateur ; tous les postes sont vacants ; fixe les règles | Ensemble déjà créé ; pas la première opération de l'auteur, ou identifiant d'ensemble qui ne correspond pas (`BAD_GENESIS`) |
| `AdmitMember(member, name, keys)` | Ajoute un membre, en attente, sous le nom qu'il a choisi (Q11) | Déjà membre ; limite de membres atteinte ; clés qui ne sont pas celles de `member`, nom qui n'est pas caché (`MALFORMED`) |
| `RemoveMember(member)` | Le sort de l'ensemble, de son groupe, de ses postes, des administrateurs ; s'il était leader, son adjoint le devient | Inconnu ; **c'est le créateur** (`IS_CREATOR`) |
| `AddAdmin(member)` / `RemoveAdmin(member)` | Le nomme administrateur / lui retire ce rôle | Pas membre ; déjà administrateur / pas administrateur |
| `CreateGroup(group, name, parent)` | Crée un groupe sous l'ensemble ou sous un groupe | Identifiant pris ou supprimé ; parent inconnu ; trop profond (`TOO_DEEP`) ; limite de groupes |
| `RenameGroup(group, name)` | Renomme | Groupe inconnu ; même nom caché |
| `DeleteGroup(group)` | Supprime ; **ses membres restent dans l'ensemble, en attente** (Q7) | Groupe inconnu ; il a des sous-groupes (`HAS_SUBGROUPS`) |
| `AssignMember(member, group)` | Le met dans ce groupe, donc hors de l'ancien, dont il quitte le poste (son adjoint lui succède s'il en était le leader) | Membre ou groupe inconnu ; déjà dedans |
| `UnassignMember(member)` | Le sort de son groupe : il attend (Q12) ; son adjoint lui succède s'il en était le leader | Inconnu ; sans groupe |
| `SetPost(unit, post, member)` | Le nomme leader ou adjoint de l'unité (`ROOT` : l'ensemble), à la place du précédent | Unité ou membre inconnu ; **pas membre de ce groupe** ; déjà à ce poste |
| `ClearPost(unit, post)` | Laisse le poste vacant | Unité inconnue ; poste déjà vacant |
| `TransferCreator(member)` | `member`, un administrateur, devient le créateur aussitôt ; l'auteur redevient un membre comme un autre | Pas membre ; déjà le créateur ; **pas administrateur** (`NOT_AN_ADMIN`, Q13) |
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
- **L'horloge d'un auteur ne recule pas.** Les opérations d'un auteur comptent dans l'ordre où il les a numérotées ; celle dont l'horloge ne dépasse pas celle de la précédente est refusée (`OUT_OF_ORDER`), où que l'ordre la place, et l'auteur continue. Personne n'écrit donc « avant » ce qu'il a déjà écrit : l'ancien créateur ne peut pas défaire son passage de main, ni le créateur sa dissolution.
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
| **Un leader de groupe est retiré pendant qu'un autre adjoint est nommé** (Q18) | Retrait d'abord : l'adjoint du moment devient leader, puis le nouveau est nommé adjoint. Nomination d'abord : le nouvel adjoint devient leader, plus d'adjoint. Le même leader partout |
| **Un leader de groupe est remis en attente pendant qu'on le change de groupe** (Q18) | Dans les deux ordres son adjoint devient leader ; lui finit dans l'autre groupe, ou en attente |
| **Le leader d'ensemble part pendant que le créateur le retire** (S4) | L'une des deux opérations s'applique, l'autre ne trouve plus personne ; dans les deux ordres son adjoint devient leader |
| **Le leader d'ensemble part pendant que son adjoint est retiré** (S4) | Dans les deux ordres les deux postes finissent vacants |
| Le créateur passe la main, puis écrit encore | Tout ce qu'il écrit après son passage de main est refusé (`FORBIDDEN`) ; ce qu'il daterait d'avant est refusé (`OUT_OF_ORDER`) |
| Le créateur passe la main à quelqu'un qui part au même moment | Départ d'abord : le passage de main ne trouve personne (`NO_SUCH_MEMBER`), le créateur reste le créateur et son propre départ est refusé (`IS_CREATOR`) : **rien n'est dissous par surprise**. Passage de main d'abord : le successeur est le créateur et son départ est refusé. Il y a toujours exactement un créateur |
| Le créateur passe la main à quelqu'un à qui le rôle d'administrateur est retiré (Q13) | Rôle retiré d'abord : le passage de main est refusé (`NOT_AN_ADMIN`), le créateur reste |
| Le créateur dissout pendant que d'autres agissent | Ce que l'ordre place avant la dissolution reste ; tout le reste est refusé (`DISSOLVED`), y compris ce qu'écrit plus tard un téléphone qui ne savait pas |
| Opération rejouée (reçue deux fois) | Reconnue (`DUPLICATE`), rien ne change, la signature n'est pas revérifiée |
| Opération d'un auteur sans droit, non membre ou retiré | Gardée (pour que tous aient les mêmes opérations), refusée (`FORBIDDEN`, `NOT_A_MEMBER`), ne change rien |
| Fausse création de l'ensemble | Refusée (`BAD_GENESIS`) : l'identifiant de l'ensemble ne correspond qu'à la vraie |
| Opération non signée, signée par un autre, ou signature abîmée | Pas gardée, jamais appliquée (`UNAUTHENTICATED`) |

Test de convergence (`HierarchyReplicaTest`, 50 graines fixes, limites et tables variées, un ou deux niveaux de groupes, promotion de l'adjoint activée ou non) : cinq téléphones et trois auteurs sans téléphone écrivent au hasard toutes les sortes de changements (attente, adjoints, passage de main, dissolution, retrait compris), avec ou sans droit, parfois deux opérations sous un même numéro, horloges parfois en arrière, échanges partiels ; à la fin toutes les opérations sont remises à huit téléphones neufs dans huit ordres différents : un seul état, les mêmes refus, la même empreinte, et les cinq téléphones d'origine y arrivent aussi après rattrapage. À chaque fois : exactement un créateur ; un leader et son adjoint sont deux membres différents ; qui attend n'est lecteur que de sa propre voie d'attente, et de rien d'autre.

### 6.3 Retour d'un téléphone hors réseau

Échange d'état en deux messages (`Summary`, puis opérations) :

1. Le téléphone envoie son **résumé** (`HierarchyReplica.summary()`) : pour chaque auteur, combien d'opérations il détient sans trou, et une empreinte (8 octets de SHA-256) de ces opérations.
2. L'autre répond par **ce qui manque** (`missingFor(résumé)`) : les opérations au-delà de ces nombres, par blocs (`HierarchyWire.MAX_BATCH`, ou moins selon la trame).
3. Et inversement. On recommence tant que l'un a quelque chose à envoyer.
4. Puis les **clés** : n'importe quel téléphone lui donne les lots de clés du moment (`KeyRing.batchesFor`), qu'il soit lecteur ou non (§ 8.3).
5. Puis **ce qui est encore affiché** : les objets durables de chaque canal qu'il lit, renvoyés par un téléphone qui a le droit d'y partager (§ 8.7), et les noms qu'il n'a pas (§ 8.8). Ni anciens messages, ni anciennes positions.

Si les nombres sont égaux et les empreintes différentes, un auteur a écrit deux opérations sous un même numéro : tout est renvoyé, et la règle du § 6.1 garde la même des deux côtés.

Le téléphone revenu peut avoir écrit des opérations hors réseau : elles prennent leur place dans l'ordre comme les autres, et peuvent être refusées si, entre-temps, leur auteur a perdu le droit.

### 6.4 Limites connues

- **Journal qui grossit** : toutes les opérations sont gardées (au plus 20 000 par ensemble, `MAX_OPS` ; au-delà le téléphone n'en prend plus). Un « point de reprise » (état signé qui remplace le passé) sera à écrire si l'usage le demande.
- **Deux opérations sous un même numéro, exprès** : un auteur qui signe une seconde opération sous le numéro d'une ancienne, avec des octets qui passent devant, **remplace la sienne sur tous les téléphones**. Un ancien créateur au téléphone modifié peut ainsi remplacer son passage de main par autre chose et rester le créateur ; un administrateur, effacer une de ses décisions. La signature dit qui l'a fait (les deux opérations portent sa signature, `CONFLICT_REPLACED` le signale), elle ne l'empêche pas. Parade à écrire avant un usage réel : un auteur pris avec deux opérations sous un numéro est figé à partir de ce numéro (ses deux opérations gardées comme preuve), ce qui lui retire tout intérêt à le faire.
- **Antidatage par quelqu'un qui a perdu un droit** : un administrateur retiré et malveillant peut écrire, après coup, une opération datée entre sa dernière opération et son retrait. La règle du § 6.1 ferme le cas de ses propres opérations (il n'écrit pas avant ce qu'il a écrit), pas celui-ci. Même parade (point de reprise).
- **Horloge poussée au maximum** : un membre malveillant peut écrire une opération d'horloge maximale ; plus personne ne peut alors écrire dans cet ensemble (`LOG_FULL`). **Journal rempli exprès** : quiconque a la clé de l'invitation peut signer des milliers d'opérations sans effet jusqu'à la borne. La signature dit qui ; parade : refaire un ensemble, ou le point de reprise.
- **Deux téléphones avec la même identité** (clés restaurées sur un second appareil) : ils écrivent sous les mêmes numéros ; une seule opération de chaque paire reste. Une identité ne se copie pas : un téléphone neuf est un membre neuf.
- **Opération d'un type inconnu** (version plus récente) : refusée au décodage, donc non gardée ; le téléphone doit être mis à jour pour suivre. Ajouter un type d'opération demande de monter la version du format.

## 7. Format binaire (`HierarchyWire`)

Gros-boutiste, comme `Wire`. Tout bloc commence par `version (1) = 3 | genre (1)`. Clé publique : point P-256 non compressé, 65 octets (`0x04 | X | Y`). Signature : `r (32) | s (32)`. Nom caché : 32 octets. Canal : `voie (1) | portée (8)`. Le format n'est **pas** branché au transport.

**Version 3 (2026-11-14).** Par rapport à la version 2 : les noms sont des empreintes de 32 octets et non plus du texte ; les lots de clés et les partages nomment un canal (une voie et sa portée) là où la version 2 nommait une unité ; les règles portent deux sortes de partage de plus, le rôle « en attente », les nouvelles portées de partage et la succession de l'adjoint. **Les versions 1 et 2 n'ont jamais été déployées** (branche de conception privée, rien de publié) : elles ne sont pas lues, aucune compatibilité n'est gardée. Un bloc d'une autre version est refusé proprement (`WireException` « unknown version », `kindOf` rend `null`) ; les étiquettes de toutes les signatures et dérivations portent `v3`, de sorte qu'une signature faite pour la version 2 ne vaut rien ici.

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
| 1 | `CreateEnsemble` | `nonce (8) | nom caché (32) | nom caché du créateur (32) | clés du créateur (130) | règles` |
| 2 | `AdmitMember` | `membre (8) | nom caché (32) | clés (130)` |
| 3 / 4 / 5 | `RemoveMember` / `AddAdmin` / `RemoveAdmin` | `membre (8)` |
| 6 | `CreateGroup` | `groupe (8) | parent (8) | nom caché (32)` |
| 7 | `RenameGroup` | `groupe (8) | nom caché (32)` |
| 8 | `DeleteGroup` | `groupe (8)` |
| 9 | `AssignMember` | `membre (8) | groupe (8)` |
| 10 | `UnassignMember` | `membre (8)` |
| 11 | `SetPost` | `unité (8 ; 0 : l'ensemble) | poste (1 : leader, 2 : adjoint) | membre (8)` |
| 12 | `ClearPost` | `unité (8) | poste (1)` |
| 13 | `TransferCreator` | `membre (8)` |
| 14 | `Dissolve` | (rien) |

**Clés d'un membre** : `clé de signature (65) | clé d'accord (65)`.

**Règles** : `n (1) | n × (opération (1), rôle (1), portées (1))` pour les cases non vides des droits, dans l'ordre des codes ; `m (1) | m × (rôle (1), publics (1))` ; `k (1) | k × (sorte de partage (1), rôle (1), portées de partage (1))` ; `sortes signées (1)` ; `drapeaux (1 ; bit 0 : l'adjoint succède au leader qui s'en va)` ; `membres au plus (2) | groupes au plus (2) | niveaux au plus (1)`. Codes : rôles 1 à 8 (`Role`), opérations 1 à 15 (`Operation`), portées en bits 1 / 2 / 4 (`Reach`), publics en bits 1 à 16 (`Audience`), sortes de partage 1 à 6 (`ShareKind`, en bits 1 à 32 pour les sortes signées), portées de partage en bits 1 à 64 (`ShareReach`), voies 1 à 5 (`Lane`).

**État** : `ensemble (8) | nom caché (32) | drapeaux (1 ; bit 0 : dissous) | créateur (8) | leader (8 ; 0 : vacant) | adjoint (8 ; 0 : aucun) | règles | n (2) | n × (membre (8), nom caché (32), clés (130), groupe (8 ; 0 : aucun), administrateur (1))` puis `g (2) | g × (groupe (8), parent (8), nom caché (32), leader (8), adjoint (8))` puis `d (2) | d × groupe supprimé (8)`, tout par identifiant croissant : un même état donne toujours les mêmes octets. Il sert à garder l'état sur le disque pour un démarrage rapide ; l'accord entre téléphones passe par les opérations, jamais par l'état.

**Lot de clés** : `ensemble (8) | canal (9) | nom de la liste de lecteurs (16) | identifiant de la clé (8) | émetteur (8) | clé éphémère (65) | n (2) | n × (lecteur (8), clé enveloppée (48))`, lecteurs par identifiant croissant, puis `signature de l'émetteur (64)`.

**Partage** : `ensemble (8) | canal (9) | identifiant de la clé (8) | sorte (1) | auteur (8) | nonce (12) | partie scellée`, où la partie scellée contient la charge utile, la signature de l'auteur (64) pour une sorte signée, et l'étiquette d'authentification (16).

**Charges utiles définies par le moteur** (à l'intérieur de la partie scellée) : pour un croquis ou un waypoint, un **enregistrement d'objet** (§ 8.7) : `auteur (8) | objet (8) | révision (4) | éditeur (8) | drapeaux (1 ; bit 0 : retiré) | signature de l'éditeur (64) | corps` ; pour des noms, des **cartes** (§ 8.8) : `n (2) | n × (sel (16), nom)`, le nom en longueur sur 1 octet puis UTF-8, 128 cartes au plus par partage. Les autres sortes (position, message, alerte) portent ce que l'application y met.

**Tailles** : une opération fait 158 octets de plus que le corps de son changement (en-tête, longueur, ensemble, auteur, numéro, horloge, code, preuve) : 174 octets pour une affectation, 206 pour une création de groupe, 328 pour une admission (un nom pèse 32 octets quelle que soit sa longueur), 530 pour la création de l'ensemble avec les règles de départ. Un lot de clés : 182 octets plus 56 par lecteur (518 pour 6 lecteurs, 1 862 pour 30). Un partage : 64 octets de plus que sa charge utile, 128 quand il est signé ; une position de 32 octets signée : 160 octets (96 sans signature). Un objet durable : 93 octets de plus que son corps, dans un partage signé. Une carte de nom : 17 octets plus le nom.

**Entrées invalides** : le décodage ne lève qu'une `WireException`, quels que soient les octets (testé : coupures à chaque longueur, octets modifiés, bruit, bloc lu comme un autre genre). Sont refusés : version ou genre inconnu, données tronquées ou en trop, code inconnu, identifiant 0, clé qui n'est pas un point de la courbe ou qui n'est pas celle du membre, règle, voie ou limite inconnue, voie qui ne peut pas exister (voie des chefs d'un groupe, voie montante de l'ensemble), état incohérent, lot de clés sans lecteur ou mal ordonné, carte dont le nom est refusé par `Names`, et toute opération qui n'est pas écrite de **la seule façon** dont cette version l'écrit (le décodeur réécrit l'opération et compare : la signature porte sur ces octets exacts).

## 8. Sécurité : signatures et clés de contenu (ADR-126, ADR-127)

Le mode ensemble est le mode où la sécurité est exigée (R18, § 10 ter points 5 et 7). Tout ce qui suit n'utilise que ce que la JVM et Android fournissent déjà (ECDSA et ECDH sur P-256, SHA-256, HKDF-SHA256, AES-256-GCM) : aucune dépendance nouvelle. Le groupe simple n'est pas touché.

### 8.1 Chaque téléphone signe ses changements

- Chaque téléphone a **deux paires de clés** P-256 (`PhoneKeys`, `MemberKeys`) : une clé de **signature** et une clé d'**accord** (celle pour laquelle on enveloppe les clés de contenu). Deux et non une : la clé de signature peut rester dans le Keystore d'Android sur toutes les versions prises en charge (API 26 et suivantes), alors que l'accord de clés dans le Keystore n'existe qu'à partir de l'API 31.
- **L'identifiant d'un membre est l'empreinte de sa clé de signature** (8 octets de SHA-256). Les deux clés publiques sont inscrites dans l'opération qui admet le membre (ou qui crée l'ensemble, pour le créateur) ; le moteur refuse une admission dont les clés ne sont pas celles de l'identifiant.
- **Toute opération est signée par son auteur**, sans option : une opération sans preuve valable n'est pas gardée par `HierarchyReplica.add` et n'est jamais appliquée par `HierarchyReducer.reduce` (`UNAUTHENTICATED`). La clé qui signe doit être **celle avec laquelle le membre a été admis**, pas seulement une clé de même empreinte.
- **Une seule forme pour les clés et les signatures**, vérifiée par le moteur avant de s'en remettre à la plateforme (`P256`) : point non compressé de 65 octets vérifié sur la courbe, signature `r | s` de 64 octets avec `r` et `s` dans les bornes. Tous les téléphones acceptent ou refusent donc les mêmes octets, quelle que soit leur version d'Android : c'est nécessaire à l'accord du § 6. `P256.derToRaw` convertit ce que rend un Keystore.
- La même clé signe, sous quatre étiquettes différentes, les opérations, les lots de clés, les partages et les objets durables : une signature faite pour l'un ne vaut rien pour les autres.
- La copie retient la clé de signature de **tout membre que les opérations ont un jour admis**, parti depuis ou non (`HierarchyReplica.signingKeyOf`) : c'est avec elle qu'on vérifie la signature d'un objet resté affiché après le départ de son auteur (§ 8.7).

Testé (`OpAuthTest`) : opération signée reconnue ; refusées et non gardées : sans preuve, preuve d'une autre opération, signée par un autre au nom de l'auteur, signature abîmée, clé qui n'en est pas une ; clé de même identifiant mais différente de celle de l'admission refusée ; copie sans son téléphone (sans clés) qui ne peut rien écrire.

### 8.2 « Vraiment fermé » : quatre schémas comparés

Besoin (R1, § 10 ter point 5, puis Q3, Q15, Q16, Q6, Q10, S1 à S3) : le téléphone d'un membre en attente ne doit pouvoir déchiffrer aucun partage des autres ; un membre retiré, parti ou remis en attente ne doit plus rien lire de ce qui suit ; ce qu'un groupe partage, ce qu'un chef remonte, ce qui descend vers un groupe et ce qui se dit entre chefs ne doivent être lus que par ceux à qui c'est destiné ; le tout sans autorité centrale, avec des téléphones souvent hors réseau, et des changements de rôles qui arrivent dans le désordre (§ 6).

| Schéma | Principe | Pour | Contre |
|---|---|---|---|
| **A. Une clé par canal et par liste de lecteurs, émise par un lecteur** (retenu) | Chaque canal a une clé de contenu faite pour **exactement** sa liste de lecteurs du moment, enveloppée pour chacun d'eux. Toute modification de la liste demande une clé neuve. N'importe quel lecteur peut l'émettre | Fermeture réelle pour qui attend et pour qui sort ; pas d'autorité ; insensible au désordre des opérations (une clé est liée au contenu de la liste, pas à un numéro) ; n'importe quel téléphone peut relayer les lots à un absent ; une voie de plus ne demande aucun code de plus | Un lot à refaire à chaque changement de liste : 182 + 56 × n octets et n accords de clés ; plus de canaux, donc plus de lots (ci-dessous) ; un court moment sans clé après un changement |
| B. Clés émises par les seuls administrateurs | Comme A, mais seuls le créateur et les administrateurs font les clés | Un seul émetteur possible, plus simple à raisonner | Aucune clé neuve tant qu'aucun administrateur n'est joignable ; les administrateurs détiendraient les clés de tous les groupes, donc liraient tous les fils (contraire à Q16) |
| C. Une clé d'émission par membre (« sender keys ») | Chacun chiffre avec sa propre clé, qu'il distribue à chaque lecteur | Chaque message prouve son auteur sans signature | À chaque changement de liste, **chaque** membre redistribue sa clé à tous : n × n enveloppes au lieu de n ; rattrapage d'un absent auprès de chaque émetteur |
| D. Arbre de clés (MLS / TreeKEM) | Un arbre de clés mis à jour par des « commits » ordonnés | Coût en log n ; secret persistant et réparation après compromission | Exige un ordre total des commits, donc un service de livraison ou un consensus que le partage n'a pas ; très gros à écrire sans bibliothèque ; rien de tel dans la plateforme |

**Choix : A, étendu des unités aux canaux** (ADR-127). Le besoin nouveau (un chef qui remonte sans que les autres chefs lisent ; un envoi vers un groupe que ce groupe lit sans que le commandement lise le groupe) se ramène à « une liste de lecteurs de plus » : pour chaque groupe, une voie montante et une voie descendante ; une voie des chefs ; une voie par membre en attente. Sceller pour chaque destinataire, sans clé commune, a été écarté : un accord de clés par destinataire à chaque envoi, positions comprises.

**Coût.** Pour un ensemble de 30 membres en 6 groupes de 5, chaque groupe ayant un leader et un adjoint, avec 5 membres au commandement de l'ensemble : jusqu'à 1 + 6 × 3 + 1 = 20 canaux, plus un par membre en attente. Un lot pèse 1 862 octets pour l'ensemble, 462 pour un groupe, environ 574 pour une voie montante (7 lecteurs), 742 pour une voie descendante (10), 1 Kio pour la voie des chefs, 406 pour une voie d'attente (4). Placer un arrivant change trois listes (l'ensemble, son groupe, la voie descendante de son groupe ; sa voie d'attente disparaît) : environ 3,2 Kio et près de cinquante accords de clés. Nommer ou retirer un administrateur, ou changer le leader d'ensemble, change jusqu'à 13 listes plus les voies d'attente : environ 10 Kio. C'est rare (les rôles changent peu) et petit devant les positions. **Les voies n'ont pas besoin de leur clé à l'avance** : seuls les canaux des unités valent une clé tout de suite ; une voie peut attendre que quelqu'un ait quelque chose à y partager (§ 9). Une économie possible plus tard, sans changer le principe : quand la liste ne fait que grandir, dériver la clé suivante de la précédente et n'envelopper que pour l'arrivant.

### 8.3 Le schéma retenu, pas à pas

**Qui détient quoi.**

- **Clé de l'invitation** (à brancher, § 9) : portée par le lien ou le QR d'invitation ; elle scelle les trames qui transportent les opérations et les lots de clés. Tout membre admis l'a, **en attente compris** : c'est ce qui lui permet de recevoir le journal des changements, donc de savoir qu'il est admis, à qui envoyer sa position, puis qu'il est placé. Elle n'ouvre **aucun partage** et ne donne **aucun nom**.
- **Ce que détient un membre en attente** : sa propre identité (ses deux paires de clés), la clé de l'invitation, le journal des opérations (sans aucun nom : des identifiants, des clés publiques, la forme de la hiérarchie, qui tient quel poste), et la clé de sa propre voie d'attente. Aucune autre clé de contenu : aucune n'a jamais été enveloppée pour lui, et les lots qu'il voit passer ne contiennent aucune copie qu'il puisse ouvrir (testé : chaque copie de chaque lot essayée avec ses clés).
- **Clés de contenu** : une par canal et par liste de lecteurs. Un membre placé détient celles de son groupe, de chaque unité au-dessus, de l'ensemble, et de la voie descendante de son groupe ; un leader de groupe ou son adjoint, en plus, celles de la voie montante de son groupe et de la voie des chefs ; le commandement de l'ensemble, celles de l'ensemble, de toutes les voies montantes et descendantes, de la voie des chefs ; les administrateurs et le créateur, en plus, celles des voies d'attente. **Aucun d'eux ne détient la clé d'un groupe dont il n'est pas membre.**

**Comment une clé est faite** (`ContentKeys.issue`, `KeyRing.issue`).

1. La **liste de lecteurs** d'un canal (`readersOf`) a un **nom** (`Readers.tag`, 16 octets de SHA-256 de l'ensemble, du canal, et de chaque lecteur avec sa clé d'accord). Le moindre changement de la liste change le nom ; deux canaux qui ont les mêmes lecteurs ont deux noms, donc deux clés.
2. Un lecteur du canal tire une clé de 32 octets au hasard. Son **identifiant** (8 octets) est une empreinte de la clé et de ce pour quoi elle est faite.
3. Il fait une paire de clés éphémère pour ce lot, et pour chaque lecteur : accord ECDH entre la clé éphémère et la clé d'accord du lecteur, HKDF-SHA256 (lié à l'ensemble, au canal, à la liste, à la clé, au lecteur), puis AES-256-GCM de la clé de contenu. Chaque copie ne s'ouvre que pour son lecteur.
4. Il signe le tout. Le **lot** (`KeyBatch`) voyage en clair sous la clé de l'invitation.

**Comment un lot est accepté** (`ContentKeys.check`, `KeyRing.offer`). Un téléphone ne garde une clé que si : la liste de lecteurs nommée **a existé** à un moment des opérations qu'il détient (`HierarchyReplica.readers(canal, nom)`) ; l'émetteur est **un lecteur de cette liste** (personne n'émet la clé d'un canal qu'il ne lit pas : ni un administrateur hors du groupe, ni quelqu'un en attente pour autre chose que sa voie) ; la signature est celle de l'émetteur ; le lot est enveloppé pour **exactement** cette liste (personne d'oublié, personne d'ajouté) ; sa propre copie s'ouvre et donne bien la clé annoncée. Sinon : `FORGED`, `ISSUER_NOT_A_READER`, `WRONG_RECIPIENTS`, `DAMAGED`. Un lot pour une liste qu'il ne connaît pas encore attend (`UNKNOWN_READERS`) et est réessayé après le rattrapage des opérations (`KeyRing.retry`). Un téléphone qui n'est pas lecteur vérifie tout de même le lot et le garde pour le transmettre (`NOT_FOR_ME`).

**Avec quelle clé on scelle** (`KeyRing.sealingKey`). Uniquement avec une clé faite pour **exactement les lecteurs du moment**. Si la liste vient de changer et qu'aucune clé n'existe encore, on ne scelle pas (`NO_KEY`) : on attend le lot, ou on l'émet soi-même. Pour éviter que tous l'émettent en même temps, `keyIssuers(canal)` donne un ordre : d'abord ceux qui y écrivent (le membre en attente pour sa voie ; le leader et l'adjoint du groupe pour son canal et sa voie montante ; le commandement du dessus pour une voie descendante), puis le leader d'ensemble, son adjoint, le créateur et les administrateurs pour ce qui touche l'ensemble, puis les autres par identifiant ; le branchement fera attendre chacun un peu plus selon son rang. Si deux lots sortent quand même en même temps, tout le monde garde les deux, ouvre avec l'un ou l'autre, et scelle avec celui dont l'identifiant est le plus petit : tous convergent sur la même clé.

**Quand quelqu'un entre** (placement d'un membre en attente, nomination à un poste). Les listes où il entre changent : des clés neuves, enveloppées pour lui aussi. Il ne reçoit jamais les clés d'avant : ce qui a été partagé avant lui reste fermé, même s'il en a gardé les octets. Ce qui est **encore affiché** chez les autres lui est renvoyé, scellé de nouveau avec la clé du moment (§ 8.7).

**Quand quelqu'un sort** (retiré par le créateur, parti de lui-même, remis en attente par un administrateur, groupe supprimé, poste retiré). Toutes les listes dont il sort changent, donc leurs clés : pour un adjoint de groupe remis en attente, cinq canaux (l'ensemble, la voie des chefs, son groupe, ses voies montante et descendante). Les clés neuves ne sont pas enveloppées pour lui. Son téléphone garde les anciennes tant qu'il ne les oublie pas (`KeyRing.prune`, que fait un téléphone honnête) : il peut encore lire ce qui a été scellé avec elles, y compris par un téléphone qui n'a pas encore appris le changement (limite inévitable sans autorité centrale : § 8.6). Ce qu'il scellerait lui-même avec une ancienne clé est refusé par tous ceux qui ont appris le changement.

**Retour d'un téléphone hors réseau.** Il rattrape d'abord les opérations (§ 6.3) : il sait alors quelles sont les listes de lecteurs. Puis n'importe quel téléphone, lecteur ou non, lui remet les lots du moment (`KeyRing.batchesFor`) : il y trouve sa copie. Dans l'autre ordre (lots avant opérations), les lots attendent et sont réessayés. Il n'a besoin ni de l'émetteur ni d'un administrateur en ligne. Les anciennes clés (16 par canal au plus) restent un temps pour ouvrir ce qui a été scellé juste avant un changement.

**Départ, retrait, dissolution sur le téléphone concerné.** `KeyRing.clear()`, `NameBook.clear()` et `SharedObjects.clear()` oublient tout. Un ensemble dissous n'a plus de lecteurs : rien ne se scelle, rien ne s'émet.

### 8.4 Pourquoi une clé par canal

Le fil « mon groupe » (R7) et tout ce qu'un groupe partage ne doivent être lus que dans ce groupe (Q16) ; ce qu'un chef remonte, que par le commandement (S1) ; l'alerte d'un chef, que par les chefs (Q6) ; la position de qui attend, que par les administrateurs (Q10). Avec une seule clé pour tout l'ensemble, ou une seule par groupe, ce ne seraient que des filtres d'affichage : tout membre placé pourrait lire le reste avec une application modifiée. La sécurité étant exigée dans ce mode, chaque canal a sa clé. C'est le même code pour toutes les voies ; un lot de voie est petit.

La voie descendante existe pour concilier Q15 et Q16 : le commandement doit pouvoir **écrire à** un groupe sans pouvoir **lire** ce groupe. Avec une clé commune il faut la détenir pour sceller ; lui donner la clé du groupe lui ouvrirait le groupe. La voie descendante a donc ses propres lecteurs (le groupe et le commandement) et sa propre clé, et le groupe n'a pas le droit d'y écrire : rien de ce qu'il partage n'y passe.

### 8.5 Sceller et ouvrir un partage (`ShareSeal`)

- **Sceller** : refusé d'abord si l'auteur n'a pas le droit (`mayShare`), puis s'il n'a pas la clé du moment. AES-256-GCM avec une clé dérivée de la clé de contenu **et de l'auteur** (deux auteurs ne tirent jamais leurs nonces au hasard sous la même clé), nonce de 12 octets au hasard ; l'en-tête en clair (ensemble, canal, clé, sorte, auteur) est authentifié avec le reste.
- **Signature de l'auteur**, à l'intérieur de la partie scellée, pour les sortes que les règles nomment (`HierarchyRules.signedShares`) : **toutes, positions comprises** (S5). Sans elle, n'importe quel lecteur d'un canal pourrait partager au nom d'un autre : une clé commune dit seulement « un des lecteurs ». C'est ce qui rend les filtres du § 4.2 réels : un membre a la clé de l'ensemble (pour les positions), il ne peut pas pour autant y écrire un message au nom du leader.
- **Ouvrir**, dans l'ordre : le téléphone détient la clé nommée (sinon `NO_KEY`) ; il lit encore ce canal (`NOT_A_READER`) ; la clé ouvre (`UNDECIPHERABLE`) ; **l'auteur a le droit, dans l'état du moment**, de partager cette sorte dans ce canal (`NOT_ALLOWED` : qui a été retiré, a changé de groupe ou a perdu son poste ne partage plus, même avec une clé qu'il a gardée) ; puis, pour une sorte signée, la signature est celle de l'auteur avec la clé de son admission (`FORGED`) ; pour une sorte non signée, la clé doit être celle des lecteurs du moment (`STALE_KEY`), puisque seule la clé dit alors qui a scellé.
- Un partage signé, scellé avec la clé précédente par un téléphone qui n'avait pas encore appris un changement, est donc encore lu par ceux qui détiennent cette clé : rien ne se perd pendant qu'un changement se propage.

**Positions signées (S5) : ce que cela coûte.** Format : celui de tout partage signé ; la signature (64 octets) est dans la partie scellée, après la position. Vérification : à l'ouverture, avec la clé de signature inscrite à l'admission de l'auteur ; une position dont la signature n'est pas celle de son auteur est refusée (`FORGED`), sur tous les téléphones, y compris quand elle est scellée avec la bonne clé par un autre lecteur (testé). Taille, pour une position de 32 octets : **96 octets sans signature, 160 avec** (+ 64, soit + 67 %). Temps, **mesuré sur la JVM de la machine de build** (OpenJDK 21, 4 processeurs, `PositionCostTest`, 400 tours après échauffement, écrit dans `core/build/hierarchy-position-cost.txt`), deux passages, le premier seul, le second pendant le build complet : sceller une position, 14 à 30 µs sans signature et 0,2 à 0,4 ms avec (la signature seule : 198 à 373 µs) ; l'ouvrir, 10 à 24 µs sans et 0,7 à 1 ms avec (la vérification seule : 706 à 901 µs). Signer coûte donc une quinzaine de fois plus que sceller, et vérifier une quarantaine de fois plus qu'ouvrir, sur des durées qui restent sous la milliseconde ici. **Rien n'est mesuré sur un téléphone** : un téléphone est plus lent, une clé de signature gardée dans le Keystore plus lente encore (un appel au matériel par signature), et l'effet sur la batterie n'est pas connu. À 30 membres qui envoient chacun une position toutes les quelques secondes, chaque téléphone vérifie une trentaine de signatures par période : à mesurer sur le terrain avant un usage réel. Le choix reste une donnée de l'ensemble (`signedShares`) : un ensemble créé sans les positions dans cette liste a des positions légères et la limite connue (un lecteur peut forger la position d'un autre, testé).

Testé (`ShareSealTest`, `HierarchyKeysTest`, `HierarchyChoicesTest`, `PositionCostTest`) : allers-retours des six sortes dans les cinq voies, tailles ; partage qu'un téléphone modifié scelle sans en avoir le droit, ou au nom d'un autre : refusé partout ; octets abîmés, coupés, bruit : toujours un refus, jamais autre chose.

### 8.6 Ce que ce schéma ne fait pas

- **Recevoir.** Le moteur dit à qui adresser (`readersOf`) ; empêcher un téléphone en attente de **recevoir** les octets chiffrés dépend du transport : sur une liaison directe on n'envoie qu'aux lecteurs ; par le serveur de coordination, qui relaie à tous ceux qui ont la clé de l'invitation, il verra passer des octets qu'il ne peut pas ouvrir. La fermeture garantie est celle du déchiffrement.
- **Pendant qu'un changement se propage**, un téléphone qui ne l'a pas appris scelle encore pour l'ancienne liste : celui qui vient d'en sortir lit ces partages-là. Cela dure le temps que l'opération arrive.
- **Un membre placé malveillant** peut toujours recopier ce qu'il lit, et remettre un lot ou une clé à qui il veut : aucune cryptographie ne l'empêche.
- **Ce qu'un téléphone en attente apprend sans les noms** : combien il y a de membres et de groupes, leurs identifiants et leurs clés publiques, qui (par identifiant) tient quel poste, quand la hiérarchie change. Aucun nom de personne, de groupe ni de l'ensemble. Cacher aussi cette forme demanderait de ne pas lui donner le journal, donc de lui faire croire sur parole qui est administrateur : écarté.
- **Quiconque détient encore la clé de l'invitation** (un membre retiré, tant que l'invitation n'est pas renouvelée) continue de lire la forme de la hiérarchie, pas les noms nouveaux ni aucun partage.
- **Téléphone du créateur perdu** (S7). L'ensemble **continue** avec ses administrateurs : admettre, créer, renommer et supprimer des groupes, placer, changer de groupe, remettre en attente (ce qui coupe un accès), nommer à tous les postes ; les membres partagent, partent d'eux-mêmes. **Plus personne ne peut** : retirer un membre de l'ensemble, nommer ou retirer un administrateur, passer la main, dissoudre. Un administrateur qui part n'est pas remplacé ; quand il n'en reste plus, plus personne n'admet, ne place ni ne nomme : l'ensemble ne fait plus que partager jusqu'à ce qu'on en crée un autre. **Le téléphone perdu reste le créateur** : il garde l'accès sans groupe, personne ne peut le retirer ni le remettre en attente, et chaque clé neuve de l'ensemble, des voies montantes et descendantes, de la voie des chefs et des voies d'attente est encore enveloppée pour lui. Qui trouve ce téléphone déverrouillé lit donc tout cela jusqu'à la fin de l'ensemble. La seule parade est celle que l'utilisateur a choisie : créer une nouvelle opération (testé : `HierarchyChoicesTest`, S7).
- **Rejeu.** Une trame captée et renvoyée plus tard par quelqu'un qui n'a pas la clé reste valable tant que son auteur a le droit : la date ou le numéro du partage est dans la charge utile, à contrôler au branchement (comme le maillage le fait déjà pour les messages). Les objets durables portent leur révision : un état plus ancien ne remplace jamais un plus récent.
- **Anciens messages et anciennes positions** : jamais renvoyés (S6).
- **Téléphone perdu, changé ou restauré** : nouvelles clés, donc nouveau membre.
- **Empreintes sur 64 bits** (identifiants de membre et d'ensemble) : assez contre l'erreur et le bricolage ; la clé de signature inscrite à l'admission ferme la collision pour les membres. Passer à 128 bits changerait le format.

### 8.7 Ce que retrouve un arrivant : les objets durables (`SharedObjects`, S6)

Un membre qui vient d'être placé, ou qui revient après une longue absence, retrouve **les waypoints et croquis encore affichés chez les autres**, dans la limite de ce que sa place lui laisse voir ; ni anciens messages, ni anciennes positions.

**L'état des objets encore affichés.** Un croquis ou un waypoint (`ShareKind.standing`) n'est pas un message qui passe : c'est un **objet**, nommé par son auteur et l'identifiant que l'auteur lui a donné, dans un canal. Chaque état de l'objet est un **enregistrement** (`SharedRecord`) : révision (1, 2, 3…), corps (ce qu'y met l'application), ou « retiré ». Chaque téléphone garde, pour chaque objet, l'enregistrement le plus récent qu'il a reçu (`SharedObjects`) : la révision la plus haute ; à révision égale un retrait l'emporte, puis l'ordre des octets ; le même partout. **Encore affiché** : tout objet dont le dernier enregistrement n'est pas un retrait, dans un canal que le téléphone lit. Un objet disparaît quand on le retire ; il cesse d'être affiché sur un téléphone qui ne lit plus son canal (`prune`). Il **reste** affiché quand son auteur change de groupe, perd son poste ou quitte l'ensemble : un point de rendez-vous posé par un chef ne disparaît pas avec la relève.

**Qui change, qui retire.** Seul son auteur change un objet. Le retire : son auteur, ou **quiconque a aujourd'hui le droit de partager cette sorte dans ce canal** (sinon un objet dont l'auteur est parti ne pourrait plus être retiré par personne).

**Deux signatures.** L'enregistrement porte la signature de celui qui l'a écrit (son « éditeur »), sur tout ce qu'il dit : ensemble, canal, sorte, auteur, objet, révision, retiré ou non, corps. **Elle ne dépend pas de la clé de contenu** : le même enregistrement peut être scellé de nouveau, plus tard, avec une autre clé. Le partage qui le transporte porte, comme tout partage, la signature de celui qui le scelle.

**Qui renvoie, sous quelle clé.** Renvoyer un objet, c'est sceller de nouveau son enregistrement, tel quel, comme un partage de la même sorte dans le même canal, **avec la clé du moment**, par un téléphone qui a **aujourd'hui le droit de partager cette sorte dans ce canal** (`HierarchyState.resenders`) : n'importe quel membre du groupe pour le canal d'un groupe ; le commandement de l'ensemble pour ce qui est diffusé à tous et pour ce qui descend vers un groupe ; le leader du groupe, son adjoint (et, pour les messages seulement, le commandement) pour ce qui remonte. Celui qui renvoie **répond** de l'enregistrement : `ShareSeal.open` vérifie son droit du moment, comme pour tout partage. La signature intérieure dit qui a fait l'objet et que personne ne l'a changé en route : celui qui renvoie ne peut ni modifier l'objet d'un autre, ni lui en attribuer un. Elle se vérifie avec la clé de l'admission de l'éditeur, même parti (§ 8.1).

**Ce que l'arrivant accepte** (`SharedObjects.accept`) : un enregistrement arrivé dans un partage que `ShareSeal.open` a laissé passer ; dont la signature est celle de son éditeur ; un contenu écrit par l'auteur de l'objet, ou un retrait ; pour le canal et la sorte du partage qui le porte ; plus récent que ce qu'il détient (`STORED`, sinon `DUPLICATE` ou `OLDER`). Refusés : `FORGED`, `UNKNOWN_EDITOR`, `MALFORMED`, `FULL` (1 024 objets par canal ; les retraits les plus anciens sont oubliés d'abord).

**Dans la limite de ce que sa place lui laisse voir** : rien de particulier à écrire. Un renvoi est scellé avec la clé d'un canal ; l'arrivant n'ouvre que les canaux dont il est lecteur. Placé dans un groupe, il reçoit ce qui est affiché dans l'ensemble, dans son groupe et dans la voie descendante de son groupe ; pas ce que son leader a remonté, ni rien d'un autre groupe (testé).

**Le revenant** reçoit de la même façon les objets changés et **les retraits** : c'est pour lui que les retraits se gardent et se renvoient. Deux téléphones qui détiennent les mêmes enregistrements pour un canal ont la même empreinte (`SharedObjects.digest`) : ils n'ont rien à se renvoyer.

**Ni messages ni positions** : aucun téléphone ne les garde pour les renvoyer (`SharedObjects` les refuse), et les trames d'origine restent fermées à l'arrivant, scellées avec des clés qui ne lui ont jamais été données.

**Limites.** Un simple membre ne peut pas renvoyer ce qui a été diffusé à tout l'ensemble (il n'a pas le droit d'y partager) : l'arrivant ne retrouve ces objets-là que lorsqu'un téléphone du commandement est joignable ; l'administrateur qui vient de le placer l'est le plus souvent. Un membre qui a aujourd'hui le droit de partager dans un canal peut y faire accepter par un arrivant un objet ancien que les présents auraient refusé à l'époque ; il aurait pu le partager lui-même aujourd'hui, sous son nom. Ce qui dépend du transport (quand renvoyer, à qui, qui commence) est au § 9.

Testé (`SharedObjectsTest`) : l'arrivant placé dans un groupe, canal par canal ; le revenant (objet changé, objet retiré par son auteur, par le leader du groupe) ; un état ancien qui ne fait pas revenir un objet retiré ; l'objet d'un membre parti, renvoyé puis retiré par un autre ; le membre sorti d'un groupe ; enregistrements forgés, déplacés, d'un inconnu ; format et bruit ; borne par canal.

### 8.8 Noms cachés (`HiddenName`, `NameCard`, `NameBook` ; Q2, Q11)

**Le besoin.** Qui attend ne doit lire aucun nom (Q2 : « sobre »). Or il détient le journal des opérations, et il en a besoin : c'est ce qui lui dit qui est administrateur, donc à qui sceller sa position, et ce qui lui permet de vérifier les lots de clés. Les noms sortent donc des opérations.

**Ce que porte une opération.** À la place d'un nom, son **empreinte** : SHA-256 d'un sel de 16 octets tiré au hasard et du nom (`HiddenName`, 32 octets). Elle ne dit rien du nom (le sel empêche d'essayer des noms), et elle l'engage : le nom ne peut pas être changé après coup sans une nouvelle opération. L'état ne porte que des empreintes.

**La carte.** Ce qui ouvre une empreinte : le sel et le nom (`NameCard`). Une carte **se prouve elle-même** (son empreinte est dans une opération signée par qui avait le droit de l'écrire) : n'importe quel téléphone qui en détient une peut la transmettre, sans rien avoir à garantir.

**Comment les noms voyagent.** Dans un partage de la sorte « noms » (`ShareKind.ROSTER`), scellé avec la clé de **tout l'ensemble** : tous ceux qui ont l'accès le lisent, qui attend ne peut pas (testé : la trame ne s'ouvre pas chez lui, et ni les opérations ni l'état qu'il détient ne contiennent un nom, pas même le sien). Chaque téléphone garde ses cartes dans un carnet (`NameBook`) ; à l'arrivée d'un membre dans l'ensemble, un téléphone présent lui partage les cartes des noms que porte l'état (`NameBook.cardsFor`), comme il lui renvoie les objets durables.

**Le nom de celui qui entre** (Q11). Il choisit son nom ; son téléphone en fait la carte, et l'empreinte va dans l'opération qui l'admet. Tant qu'il attend, il envoie sa carte dans sa voie d'attente : les administrateurs savent qui ils placent, les autres non.

**Ce que le moteur garantit pour les noms** : par les clés, aucun nom à qui attend ni à qui est hors de l'ensemble. Entre membres qui ont l'accès, tous les noms sont connus de tous ; la règle « en entier ou en initiales » (Q17, § 4.4) est une règle d'affichage.

Testé (`NamesTest`, `HierarchyChoicesTest`) : empreintes, cartes, carnet, format, bruit ; le téléphone en attente et ce qu'il détient.

## 9. Ce qu'il faudra pour brancher

Rien de ceci n'est fait.

**Transport** (`Wire.kt`, `MeshNode.kt`, `:p2p`) :

- Un ensemble est un partage **à part** du groupe actuel : sa propre invitation, ses propres trames. L'invitation porte l'identifiant de l'ensemble et la **clé de l'invitation** ; celle-ci scelle (comme `GroupCrypto` aujourd'hui) les trames de quatre genres nouveaux : opérations (`OPS`), résumé (`SUMMARY`), lot de clés (`KEYS`), partage (`SHARE`). Les versions qui ne les connaissent pas les écartent déjà. Pour afficher « Rejoindre l'opération “…” ? » (Q11), l'invitation peut porter la carte du nom de l'ensemble : c'est un choix d'écran, qui donne ce nom à quiconque a l'invitation.
- **Arrivée** : le nouveau téléphone montre ses deux clés publiques et l'empreinte du nom qu'il a choisi (une demande d'entrée signée, à écrire ; **pas le nom en clair** : la demande voyage sous la clé de l'invitation, que tout téléphone en attente détient) ; un administrateur écrit `AdmitMember` avec ces clés et cette empreinte. L'arrivant rattrape alors le journal, y lit qui sont les administrateurs, **émet lui-même la clé de sa voie d'attente** (il en est lecteur, et le premier de `keyIssuers`), puis y envoie la carte de son nom et sa position. Il attend ensuite d'être placé.
- À l'ouverture d'une liaison, après `HELLO` : envoyer son résumé, répondre par `missingFor`, puis les lots de `batchesFor`, puis ce qui est encore affiché (ci-dessous). Refaire de temps en temps.
- **Après chaque changement d'état** : `KeyRing.retry` ; `KeyRing.prune`, `SharedObjects.prune` et `NameBook.prune` (oublier ce qu'on ne lit plus) ; pour chaque canal de `KeyRing.missing`, attendre un délai selon son rang dans `keyIssuers`, puis `KeyRing.issue` si aucun lot n'est arrivé. **Tout de suite pour le canal d'une unité ; pour une voie (montante, descendante, des chefs, d'attente), seulement quand ce téléphone a quelque chose à y partager** : cela évite de refaire treize lots à chaque changement d'administrateur. Les partages durables (messages, croquis, waypoints, alertes) en attente de clé se gardent et se scellent quand elle arrive ; une position se saute.
- **Renvoyer à qui arrive** (S6, § 8.7 et 8.8) : après un changement d'état, `état.arrivals(étatPrécédent)` donne, canal par canal, qui vient d'y entrer. Pour chaque canal où quelqu'un arrive, et une fois sa clé du moment disponible, un téléphone de `resenders(sorte, canal)` attend un délai selon son rang, puis scelle de nouveau chaque enregistrement de `SharedObjects.resend(canal)` ; pour une arrivée dans l'ensemble, un téléphone qui a l'accès partage aussi les cartes de `NameBook.cardsFor`. Pour ne pas le faire plusieurs fois, celui qui voit passer le renvoi d'un autre (mêmes enregistrements : `DUPLICATE`) annule le sien.
- **Renvoyer à qui revient** : à l'ouverture d'une liaison, après les opérations et les clés, les deux téléphones échangent, pour chaque canal qu'ils lisent tous les deux, l'empreinte `SharedObjects.digest(canal)` (elle ne dit rien des objets ; elle peut voyager sous la clé de l'invitation) et le nombre de noms qui leur manquent (`NameBook.missing`). Là où les empreintes diffèrent, celui des deux qui a le droit de partager dans le canal renvoie ce qu'il détient ; l'autre aussi s'il en a le droit. Un téléphone qui n'en a pas le droit attend de croiser quelqu'un qui l'a. À écrire plus tard si les volumes le demandent : échanger l'inventaire (objet, révision) pour ne renvoyer que ce qui manque.
- **Adresser** : un partage ne s'envoie qu'aux lecteurs de son canal là où le transport le permet (§ 8.6).
- **Alerte** : `shareChannels(auteur, ALERT)` donne les canaux où l'auteur a le droit d'alerter ; lequel ou lesquels utiliser au moment de l'envoi est un choix d'écran (§ 14, question 2). Le moteur ne porte que le droit et la fermeture.
- Taille : une trame fait 16 Kio au plus, et une trame de contrôle du serveur de coordination 4 096 octets : `missingFor(résumé, max)` avec `max` ≈ 12 opérations par trame du serveur ; un lot de clés tient dans une trame du serveur jusqu'à 69 lecteurs, dans une trame directe jusqu'à 289 ; au-delà il faudra le couper (à écrire quand les limites monteront). Un objet durable tient dans un partage jusqu'à 12 195 octets de corps.
- Ce que `add`, `offer` et `accept` répondent sert au journal d'événements : `CONFLICT_…`, `UNAUTHENTICATED`, `MALFORMED`, `FULL`, `FORGED`, `WRONG_RECIPIENTS`, `DAMAGED`, `UNKNOWN_EDITOR` sont à signaler (`Problems.expected`) ; `HELD` et `UNKNOWN_READERS` déclenchent un rattrapage.
- **Renouveler l'invitation** après un retrait (nouvelle clé de l'invitation, distribuée comme une clé de contenu à tous les membres admis) : à écrire, pour qu'un membre retiré ne suive plus la forme de la hiérarchie.

**Clés du téléphone** (`:data` ou `:p2p`) : une implémentation de `PhoneKeys` avec la clé de signature dans le Keystore (non exportable) et la clé d'accord dans le Keystore à partir de l'API 31, sinon dans un fichier scellé par une clé du Keystore. Les clés ne vont pas dans la sauvegarde. **À mesurer sur un téléphone avant tout usage réel** : une signature par position envoyée et une vérification par position reçue (§ 8.5).

**Stockage** (`:data`) : garder les opérations (`HierarchyReplica.ops()` → `HierarchyWire.encodeOpBlocks`), relire par `HierarchyReplica.restore` ; l'état se recalcule. Garder les clés de contenu détenues (à protéger par le Keystore), les lots vérifiés, les cartes de noms et les enregistrements d'objets (`SharedRecords.encode`). Tout effacer en quittant l'ensemble, quand on en est retiré, ou à sa dissolution ; effacer ce qui tient à un canal quand on cesse de le lire.

**Interface** (après validation des maquettes de P39.5, `CLAUDE.md` § « aperçu validé avant le code ») : tout se lit dans `HierarchyState` (`rolesOf`, `isWaiting`, `waiting`, `groupOf`, `membersOf`, `visibleTo`, `nameLabel`, `shareChannels`, `channelsReadBy`) et dans `NameBook` (un nom peut ne pas être encore connu : prévoir de quoi l'écrire en attendant), se demande par `issue`, se grise par `HierarchyReducer.check`, et les refus arrivés après coup se lisent dans `history()`. Un bouton ne doit jamais supposer qu'un changement écrit est définitif. « Quitter » pour le créateur propose : désigner un successeur parmi les administrateurs (`TransferCreator` puis `RemoveMember` de lui-même), ou dissoudre (`Dissolve`, confirmé par un glissement, Q4) ; si le passage de main a perdu contre un départ simultané du successeur, le départ du créateur est refusé et l'écran le lui dit.

**Fil d'exécution** : `HierarchyReplica`, `KeyRing`, `NameBook` et `SharedObjects` sont synchronisés ; `HierarchyState` est immuable, on peut la donner telle quelle à l'interface.

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

## 10 quater. Choix du second tour de maquettes (2026-11-13)

Choix faits par l'utilisateur sur les maquettes du second tour (`docs/previews/p39-groupes/tour2/`, questions Q1 à Q18 de son `REMONTEE.md` ; Q19 = l'écran principal d'une opération et la pastille de la carte, Q20 = tous les autres écrans dessinés en une seule version). Bloc recopié tel quel :

```text
Q1 A · La carte « Opération » a son propre bouton
Q2 A · Sobre
Q3 B · Deux lignes à cocher, avec le détail sous chacune | précision : Changement de plan, un chef de groupe peu remonter au chef d'opération, mais ne diffuse pas à tout le monde, seul le chef d'opération peut diffuser chez tout le monde
Q4 A · Une fenêtre avec les deux sorties | précision : comme pour décrocher/raccrocher, un swipe de gauche à droite doit être effectué sur le choix de dissoudre, cela évite les miss clic sur une action avec de lourde conséquence
Q5 1 · Les administrateurs et le créateur seulement
Q6 (sans choix) | précision : Selon le rôle, un membre à son groupe, un chef de groupe à tout les chefs, le chefs d'opération à tout le monde
Q7 B · Ils repassent « en attente »
Q8 A · Une étiquette par rôle
Q9 1 · Oui : il choisit son groupe en créant l'opération
Q10 2 · Oui, aux administrateurs seulement
Q11 1 · Oui : « Rejoindre l'opération ? » | précision : Avec proposition de choisir un nom en entrant "Avec le nom paramétrer par défaut"
Q12 2 · Oui
Q13 2 · Un administrateur seulement
Q14 1 · Non : seul le créateur le lui retire
Q15 2 · Oui : il choisit un groupe précis
Q16 1 · Les membres de ce groupe seulement | précision : limite le trop d'information partager
Q17 1 · Le nom pour son groupe et pour les chefs, des initiales pour les autres
Q18 2 · L'adjoint devient chef automatiquement
Q19 1 · Ça me va
Q20 1 · D'accord pour tout
```

**Lecture de la session principale** (interprétation ; elle remplace, là où elle les contredit, les lectures du § 10 ter et celles du § 13) :

1. **Le partage vers le haut change (Q3, remplace P2 et le point 2 du § 10 ter).** Un chef de groupe ne diffuse plus à toute l'opération : il remonte vers le niveau du dessus. Seul le chef de l'opération diffuse à tout le monde. Le choix de la destination se fait par deux lignes à cocher (variante B).
2. **Alerte selon le rôle (Q6, répond à la question 5 du § 12)** : un membre alerte son groupe, un chef de groupe alerte tous les chefs, le chef de l'opération alerte tout le monde. Lecture : un adjoint fait comme son chef ; un administrateur sans poste de chef fait comme un membre.
3. **Dissoudre se confirme par un glissement de gauche à droite (Q4)**, comme pour décrocher un appel : geste nouveau, à montrer en maquette avant tout code.
4. **Supprimer un groupe remet ses membres en attente (Q7)** : c'est ce que fait déjà le moteur.
5. **Celui qui attend** : il ne voit aucun nom (Q2 A, donc choix b de la question 2 du § 12 : la liste des membres n'arrive qu'au moment où il est placé) ; sa position part aux administrateurs seulement (Q10), lui ne reçoit toujours rien.
6. **Entrée (Q11)** : « Rejoindre l'opération ? », avec un champ pour son nom, déjà rempli avec le nom réglé par défaut.
7. **Un administrateur peut remettre un membre en attente (Q12, question 3 du § 12 : oui).**
8. **Le successeur du créateur est un administrateur (Q13)** ; lecture : il devient créateur sans avoir à accepter, et il en est prévenu.
9. **Le chef de l'opération peut envoyer à un groupe précis (Q15)**, en plus de son groupe et de toute l'opération.
10. **Ce qui est partagé dans un groupe n'est vu que par ce groupe (Q16)**, administrateurs et chef de l'opération compris s'ils n'en font pas partie.
11. **Quand un chef de groupe part, son adjoint devient chef automatiquement (Q18)** ; le poste d'adjoint devient vacant.
12. **Validés comme dessinés** : Q1 A, Q5 (seuls le créateur et les administrateurs invitent), Q8 A, Q9 (le créateur choisit son groupe), Q14, Q17, Q19, Q20.

**Précisions demandées à l'utilisateur (en attente, 2026-11-13)** : S1, qui reçoit ce qu'un chef de groupe remonte (tous les chefs, ou le chef de l'opération et son adjoint) ; S2, qui diffuse à toute l'opération (les administrateurs aussi, ou non) ; S3, les messages écrits suivent-ils le même chemin (un fil « Chefs ») ; S4, l'adjoint du chef de l'opération devient-il chef automatiquement ; S5, S6 et S7, les questions 1, 4 et 6 du § 12 (positions signées, ce que retrouve un arrivant, téléphone du créateur perdu). Le moteur et les maquettes ne sont mis à ces choix qu'après ces réponses ; aucun écran n'est codé avant l'accord de l'utilisateur sur les maquettes des écrans modifiés.

## 10 quinquies. Précisions du troisième tour (2026-11-14)

Réponses de l'utilisateur aux sept précisions S1 à S7 demandées à la fin du § 10 quater. Bloc recopié tel quel :

```text
Précisions P39 tour 3 (opération)
S1 3 · Le chef de l'opération, son adjoint et les administrateurs
S2 2 · Le chef de l'opération, son adjoint et les administrateurs
S3 1 · Oui : trois fils
S4 1 · Oui : même règle que pour un groupe
S5 1 · Oui : les positions sont signées aussi
S6 1 · Les waypoints et croquis encore affichés chez les autres
S7 1 · L'opération continue avec ses administrateurs, puis on en crée une nouvelle
```

Questions posées, pour mémoire : S1, quand un chef de groupe « remonte » un croquis ou un waypoint, qui le reçoit ; S2, qui peut diffuser à toute l'opération ; S3, les messages écrits suivent-ils le même chemin (trois fils : « Mon groupe », « Chefs », « Toute l'opération ») ; S4, quand le chef de l'opération part, son adjoint devient-il chef automatiquement ; S5, faut-il empêcher qu'un membre envoie une fausse position au nom d'un autre ; S6, que retrouve un membre qui vient d'être placé ou qui revient après une longue absence ; S7, que se passe-t-il si le créateur perd son téléphone ou en change.

**Lecture de la session principale** (interprétation ; elle remplace, là où elle les contredit, les lectures des §§ 10 ter, 10 quater et 13) :

1. **Ce qu'un chef de groupe remonte (S1)** est reçu par le chef de l'opération, son adjoint et les administrateurs. Les autres chefs de groupe ne le voient pas. Lecture : l'adjoint d'un chef de groupe remonte comme son chef, et voit ce que son chef a remonté.
2. **Diffusent à toute l'opération (S2)** : le chef de l'opération, son adjoint et les administrateurs (le créateur en est un). Personne d'autre.
3. **Trois fils de messages (S3)** : « Mon groupe » ; un fil du milieu, par lequel un chef de groupe écrit vers le haut ; « Toute l'opération », où seuls ceux du point 2 écrivent et que tout le monde lit. **Point ouvert**, né de S1 : puisque les autres chefs de groupe ne voient pas ce qu'un chef remonte, le fil du milieu ne peut pas être un fil commun à tous les chefs. Lecture retenue en attendant : un fil par groupe, entre le chef du groupe, son adjoint et ceux du point 1, qui y répondent ; l'autre lecture (un seul fil commun à tous les chefs) et le nom de ce fil (« Chefs » ne convient plus tel quel) sont à trancher par l'utilisateur sur les maquettes du troisième passage.
4. **Alerte (Q6 du § 10 quater, inchangée)** : un membre alerte son groupe, un chef de groupe alerte tous les chefs, le chef de l'opération alerte tout le monde. Lecture : les administrateurs reçoivent aussi l'alerte d'un chef de groupe, puisqu'ils reçoivent ce qu'il remonte ; à confirmer sur les maquettes.
5. **Envoi à un groupe précis (Q15)** : lecture, ceux qui diffusent à toute l'opération (point 2) peuvent aussi viser un groupe précis ; à confirmer sur les maquettes pour les administrateurs.
6. **Quand le chef de l'opération part (S4)**, son adjoint devient chef automatiquement et le poste d'adjoint devient vacant ; sans adjoint, le poste de chef reste vacant jusqu'à ce qu'un administrateur nomme quelqu'un. Même règle que pour un groupe (Q18).
7. **Les positions sont signées (S5)**. Chaque position pèse environ le double et demande un calcul de plus : l'effet sur la batterie n'est mesuré sur aucun téléphone à ce jour, il le sera avant tout usage réel.
8. **Ce que retrouve un arrivant (S6)** : un membre qui vient d'être placé, ou qui revient après une longue absence, reçoit les waypoints et les croquis encore affichés chez les autres, dans la limite de ce que sa place lui laisse voir. Ni les anciens messages, ni les anciennes positions. Celui qui attend ne reçoit toujours rien.
9. **Téléphone du créateur perdu ou changé (S7)** : ni sauvegarde de son identité, ni remplaçant de secours, aucune porte dérobée. L'opération continue avec ses administrateurs, puis on en crée une nouvelle. L'application rappelle au créateur de désigner un successeur avant de changer de téléphone (texte visible : à montrer en maquette avant tout code).

Le moteur (P39.4) et le troisième passage de maquettes (P39.5) sont relancés sur ces choix le 2026-11-14, en sessions cloud. Aucun écran n'est codé avant l'accord de l'utilisateur sur les maquettes des écrans modifiés.

## 11. Fichiers et tests

| Fichier (`core/src/main/kotlin/fr/toolmap/core/hierarchy/`) | Contenu |
|---|---|
| `HierarchyRules.kt` | `Role`, `Operation`, `Reach`, `RightsTable`, `Audience`, `VisibilityTable`, `ShareKind`, `ShareReach`, `ShareTable`, `HierarchyLimits`, `HierarchyRules` |
| `Channel.kt` | `Lane`, `Channel` : les lieux où l'on partage |
| `HierarchyState.kt` | `MemberKeys`, `Member`, `Group`, `HierarchyState` (`rolesOf`, `isWaiting`, `hasAccess`, `commandOf`, `channels`, `readersOf`, `channelsReadBy`, `mayShare`, `shareChannels`, `resenders`, `arrivals`, `canSee`, `nameLabel`, `hiddenNames`, `pathToRoot`, `inconsistency`), `NameLabel` |
| `Op.kt` | `Post`, `Change`, `Op` (ordre `Op.ORDER`), `OpProof` |
| `HierarchyReducer.kt` | `HierarchyReducer.reduce` / `check` (dont la succession de l'adjoint), `Rejection` |
| `HierarchyReplica.kt` | `HierarchyReplica` (`add`, `issue`, `summary`, `missingFor`, `history`, `ops`, `readers`, `signingKeyOf`, `create`, `restore`), `AddResult`, `IssueResult` |
| `HierarchyWire.kt` | Format binaire version 3, `Summary` |
| `OpAuth.kt` | `PhoneKeys`, `P256` (clés et signatures sous une seule forme, accord de clés), `OpAuth` (identifiants par empreinte, signature des opérations) |
| `HierarchyKeys.kt` | `Readers`, `ContentKey`, `KeyBatch`, `KeyOffer`, `ContentKeys` (émettre, vérifier, ouvrir un lot), `KeyRing` (les clés d'un téléphone, par canal), `keyIssuers` |
| `ShareSeal.kt` | `Share`, `ShareSeal.seal` / `open`, `ShareRefusal` |
| `SharedObjects.kt` | `SharedRecord`, `ObjectRef`, `SharedRecords` (écrire, retirer, signer, format), `SharedObjects` (ce qui est encore affiché, ce qu'on renvoie), `ObjectVerdict` |
| `Names.kt` | `Names` (noms acceptés, initiales), `HiddenName`, `NameCard`, `NameBook` |

Tests (`core/src/test/kotlin/fr/toolmap/core/hierarchy/`, 121 tests) :

| Fichier | Ce qu'il vérifie |
|---|---|
| `HierarchyReducerTest` (33) | Une règle de l'utilisateur par test, le nom du test commence par la réponse vérifiée (V3, R1 à R19, P2, Q3, Q13) ; refus et leurs motifs ; tables en données ; limites ; sous-groupes à deux niveaux |
| `HierarchyChoicesTest` (17) | Les choix du second tour, un test par choix, par les clés quand il s'agit d'une fermeture : Q3 / S1, S2, Q15, Q16, Q6, S3, Q2, Q10, Q12, Q7, Q18 / S4, Q5, Q9, Q14 / P3, P4, Q11 / Q17, S7 |
| `HierarchyReplicaTest` (25) | Cas du § 6.2 dans les deux ordres d'arrivée (attente, adjoints, promotion de l'adjoint, retrait, passage de main, dissolution compris), rattrapage, stockage, deux opérations sous un numéro, convergence sur 50 graines |
| `OpAuthTest` (6) | § 8.1 : signature obligatoire, refus des opérations non ou mal signées, forme unique des clés et des signatures |
| `HierarchyKeysTest` (14) | § 8.3 et 8.4 : membre en attente fermé, arrivée, sortie (quatre façons, téléphone informé ou non), clé par canal, sous-groupes, retour d'un absent, deux émetteurs à la fois, lots refusés, ordre des émetteurs, dissolution |
| `ShareSealTest` (8) | § 8.5 : sceller et ouvrir les six sortes dans les cinq voies, partages forgés ou hors droits refusés, téléphone en retard, octets abîmés |
| `SharedObjectsTest` (6) | § 8.7 (S6) : l'arrivant, le revenant, l'objet d'un membre parti, enregistrements forgés, format, borne |
| `NamesTest` (3) | § 8.8 : empreintes, cartes, carnet, format |
| `PositionCostTest` (2) | § 8.5 (S5) : taille d'une position signée, refus d'une position abîmée, temps mesurés sur la JVM |
| `HierarchyWireTest` (7) | § 7 : allers-retours, refus (dont les versions 1 et 2), octets abîmés, pour tous les genres de blocs |

Commande : `./gradlew :core:test --tests 'fr.toolmap.core.hierarchy.*'` (≈ 40 s).

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

## 14. Remontée du 2026-11-14 : moteur aux choix du second tour (Q1 à Q20, S1 à S7)

Pour la session principale (session 106, 2026-11-14, branche `claude/jolly-ptolemy-3u4uvx`, partie de `conception/groupes-hierarchiques` d40d7a0 puis remise sur a88f111). Les §§ 1 à 9 et 11 décrivent le moteur tel qu'il est après ce passage ; les §§ 10 à 10 quinquies, 12 et 13 ne sont pas modifiés. Décision d'architecture : ADR-127.

### Ce qui a changé

- **On partage dans des canaux, plus dans des unités** (§ 4.2, § 8.4). Un canal est une voie et ce dont elle est la voie : l'ensemble ou un groupe lui-même ; ce qui **remonte** d'un groupe ; ce qui **descend** vers un groupe précis ; la voie **des chefs** ; la voie d'un membre **en attente**. Chaque canal a sa liste de lecteurs et sa clé : toutes les fermetures demandées tiennent par les clés, pas par l'affichage.
- **Table des partages refaite** aux réponses Q3, S1, S2, Q15, Q16, Q6, S3, Q10 ; deux sortes de partage de plus (**alerte**, **noms**) ; un rôle de plus (**en attente**).
- **Noms cachés** (§ 8.8) : les opérations et l'état portent l'empreinte d'un nom, plus le nom ; les noms voyagent scellés pour qui a l'accès. Qui attend ne reçoit aucun nom, par les clés.
- **Position de qui attend** : scellée pour les administrateurs et le créateur seulement, dans sa voie à lui ; rien ne lui arrive.
- **Promotion de l'adjoint** (Q18, S4) dans le réducteur, pour un groupe comme pour l'ensemble ; une donnée de l'ensemble (`deputySucceeds`).
- **Successeur du créateur** : un administrateur, sinon refus (`NOT_AN_ADMIN`).
- **Objets durables** (§ 8.7, S6) : croquis et waypoints ont un état (révision, retrait), une signature qui survit à un renvoi, et se renvoient à qui arrive ou revient.
- **Format binaire version 3** (§ 7) ; les versions 1 et 2, jamais déployées, sont refusées proprement.
- Nouveaux fichiers : `Channel.kt`, `Names.kt`, `SharedObjects.kt`. Rien hors du paquet `hierarchy`, de ses tests et de `docs/`.

### Les seize points demandés

| Point | État | Où |
|---|---|---|
| 1. Remontée d'un chef de groupe (Q3, S1) | Fait, par les clés | § 4.2, voie `UP` ; test « Q3 S1 » |
| 2. Diffusion à toute l'opération (S2) | Fait | § 4.2 ; test « S2 » |
| 3. Envoi à un groupe précis (Q15) | Fait, par les clés | § 4.2 et 8.4, voie `DOWN` ; test « Q15 » |
| 4. Un groupe n'est lu que par lui (Q16) | Fait (c'était déjà le cas ; vérifié avec les voies) | test « Q16 » |
| 5. Alerte selon le rôle (Q6) | Fait : sorte `ALERT`, voie des chefs | test « Q6 » ; questions 1 et 2 ci-dessous |
| 6. Trois fils (S3) | Fait, lecture « un fil par groupe » ; l'autre est un changement de deux cases, testé | test « S3 » ; question 3 |
| 7. Celui qui attend (Q2, Q10) | Fait, par les clés | § 2.1, 8.8 ; tests « Q2 », « Q10 » |
| 8. Remettre en attente (Q12) | Fait : les cinq canaux d'un adjoint de groupe refaits sans lui, testé téléphone informé ou non | test « Q12 » |
| 9. Supprimer un groupe (Q7) | Vérifié, clés comprises | test « Q7 » |
| 10. Succession du créateur (Q13, P6) | Fait ; « sans successeur » reste l'opération `Dissolve` (lecture 15) | § 3 ; tests « R10 » |
| 11. Promotion de l'adjoint (Q18, S4) | Fait ; quatre cas dans les deux ordres d'arrivée | § 3, 6.2 ; tests « Q18 S4 », « promotion - … » |
| 12. Tables (Q5, Q9, Q14, P3, P4) | Vérifiées, rien à changer | § 4.1 ; tests du même nom |
| 13. Nom à l'entrée (Q11, Q17) | Nom choisi par celui qui entre : fait. « Entier ou initiales » : **règle d'affichage**, pas par les clés | § 4.4, 8.8 ; « Ce que coûterait… » ci-dessous |
| 14. Positions signées (S5) | Fait (déjà la règle de départ) ; tailles testées, temps mesurés sur la JVM | § 8.5 |
| 15. Ce que retrouve un arrivant (S6) | Fait dans `:core` ; ce qui dépend du transport est décrit, sans code | § 8.7, § 9 |
| 16. Téléphone du créateur perdu (S7) | Rien construit ; écrit et testé | § 8.6 ; test « S7 » |

### Lectures faites (une ligne chacune ; à confirmer ou à corriger)

Celles de la session principale, appliquées telles quelles, et où elles se changent :

1. L'adjoint d'un chef de groupe remonte comme son chef et lit ce que son chef a remonté. (Règle « l'adjoint a les droits de son leader » ; il est lecteur de la voie montante : ligne `UP` de `readersOf`.)
2. Tous ceux qui diffusent à toute l'opération peuvent envoyer à un groupe précis. (Case `GROUP_BELOW` des trois rôles, `ShareTable.DEFAULT`.)
3. L'alerte d'un chef de groupe est lue aussi par les administrateurs et le créateur. (Ligne `LEADERS` de `readersOf`.)
4. Un administrateur sans poste de chef alerte comme un membre ; le créateur de même. (Pas de case « alerte » pour ces deux rôles.)
5. Le fil du milieu : un fil par groupe. (Cases `UP` et `UP_BELOW` des messages.)
6. Le successeur devient créateur sans avoir à accepter. (`TransferCreator` s'applique aussitôt.)

Celles de cette session :

7. Le créateur compte parmi « les administrateurs » des réponses S1 et S2 même s'il n'est pas inscrit comme administrateur : il lit les remontées, diffuse à tous, lit la voie des chefs et les voies d'attente. (`commandOf`.)
8. Les positions vont toujours à toute l'opération (R2) : Q16 ne parle que de ce qu'on partage dans un groupe. (Case position du rôle membre.)
9. L'envoi à un groupe précis (Q15) vaut pour les croquis et les waypoints, pas pour les messages : sinon il y aurait un quatrième fil. (Case `GROUP_BELOW` absente des messages.)
10. Le groupe lit ce qui descend vers lui et n'y répond pas là : il répond dans son propre groupe, ou son chef dans le fil du milieu.
11. Dans le fil du milieu, le commandement répond par des messages seulement ; un croquis destiné à un groupe passe par l'envoi à ce groupe, que tout le groupe voit.
12. Un chef de groupe a deux destinations possibles pour une alerte : tous les chefs, et son propre groupe (il en est membre). Laquelle, ou les deux : question 2.
13. « Le chef part » comprend « il change de groupe » : son adjoint devient chef aussi. Un administrateur qui laisse le poste vacant, ou y nomme quelqu'un d'autre, ne promeut personne.
14. Le chef de l'opération sorti de son groupe reste chef de l'opération : il n'est pas parti de l'opération.
15. « Sans successeur désigné, le départ du créateur dissout l'opération » : c'est l'écran qui écrit « dissoudre » (Q4, avec le glissement) ; le moteur refuse un simple départ du créateur. Sinon, un passage de main qui perd contre le départ simultané du successeur, suivi du départ du créateur, dissoudrait l'opération pour tout le monde sans que personne l'ait voulu (cas du § 6.2, testé).
16. L'ancien créateur redevient un membre comme un autre (il reste administrateur s'il l'était) ; le nouveau reste inscrit comme administrateur.
17. « Aucun nom » pour celui qui attend : ni nom de personne, ni nom de groupe, ni nom de l'opération. Il garde la forme de la hiérarchie sans les noms (§ 8.6). Question 6 pour le nom de l'opération.
18. La position de celui qui attend part aux administrateurs **et au créateur** ; il leur envoie aussi le nom qu'il a choisi, pour qu'ils sachent qui ils placent.
19. Q17 est lu comme une règle d'affichage tournée vers celui qu'on regarde : nom entier sur le jeton des membres de mon groupe et sur celui des chefs et adjoints (de groupe et de l'opération), initiales sur les autres, nom entier en touchant le jeton. Un administrateur ou le créateur sans poste de chef : initiales. Question 5.
20. Initiales : première lettre des deux premiers mots, ou deux premières lettres d'un mot seul. (`Names.initials`.)
21. Un croquis ou un waypoint reste affiché tant qu'on ne le retire pas, même quand son auteur change de groupe, perd son poste ou quitte l'opération. Question 4.
22. Seul son auteur modifie un objet ; le retirent son auteur et quiconque a aujourd'hui le droit de partager la même sorte au même endroit. Question 4.
23. « Dans la limite de ce que sa place lui laisse voir » : les canaux qu'il lit aujourd'hui. Un membre sorti d'un groupe ne voit plus les objets de ce groupe.
24. Renvoie les objets à l'arrivant un téléphone qui a aujourd'hui le droit de partager au même endroit (§ 8.7) ; les noms, n'importe quel téléphone qui a l'accès.
25. Toutes les sortes sont signées, alertes et noms compris.
26. Une voie où personne n'écrirait n'a pas de clé (groupe sans chef ni adjoint : rien ne remonte).
27. Avec des sous-groupes : ce que remonte le chef d'un sous-groupe va au chef et à l'adjoint du groupe du dessus, pas aux administrateurs.

### Ce qui est vérifié, et comment

- `./gradlew :core:test --tests 'fr.toolmap.core.hierarchy.*'` : **121 tests, 0 échec** (liste au § 11 ; 89 avant ce passage). Les 89 tests d'avant sont gardés, mis aux nouvelles règles là où elles changent ; aucun n'est retiré ni désactivé.
- Les fermetures sont vérifiées par les clés : chaque trame est remise à tous les téléphones du test, et le test compare la liste de ceux qui l'ouvrent à la liste attendue (`World.openers`).
- La promotion de l'adjoint : quatre cas, chacun dans les deux ordres d'arrivée et remis à un troisième téléphone dans trois ordres ; les 50 tirages de convergence passent par la promotion (activée ou non selon le tirage).
- Qui attend : le test cherche chacun des dix noms de l'ensemble dans les octets de tout ce que détient son téléphone (opérations et état) : aucun.
- Positions signées : `PositionCostTest`, résultats au § 8.5, **sur la JVM de la machine de build ; rien n'est mesuré sur un téléphone**.
- Commandes complètes et résultats : journal, session 106.

### Ce que coûterait de garantir « nom entier ou initiales » par les clés (point 13)

Aujourd'hui : règle d'affichage. Tout membre placé détient tous les noms, et les maquettes prévoient que le nom entier se lise en touchant le jeton. Pour que le téléphone d'un membre d'un autre groupe **ne puisse pas** connaître le nom entier, il faudrait : deux empreintes par membre dans l'admission (nom entier, initiales ; 32 octets de plus) ; la carte des initiales partagée à tout l'ensemble, celle du nom entier dans le canal du groupe du membre et dans la voie des chefs (c'est le chef du groupe qui l'y porte, le membre n'y écrit pas) ; renvoyer ces cartes à chaque changement de groupe ou de chef (le mécanisme du § 8.7 sert tel quel) ; un format version 4. Travail du moteur : de l'ordre de celui des noms cachés. Conséquence pour l'écran : le nom entier ne se lirait plus en touchant le jeton d'un membre d'un autre groupe. Les administrateurs le connaîtraient toujours (ils le reçoivent quand le membre attend).

### Ce qui n'est pas fait

- **Rien n'est branché** : ni transport, ni stockage, ni clés dans le Keystore, ni écran (§ 9). La demande d'entrée d'un arrivant, la clé de l'invitation et son renouvellement sont décrits, pas écrits.
- **Rien n'est mesuré sur un téléphone** : signatures (une par position envoyée, une vérification par position reçue), accords de clés, batterie.
- **Quand renvoyer, à qui, qui commence** (S6) : décrit au § 9, sans code ; le moteur donne `arrivals`, `resenders`, `resend`, `digest`. L'échange d'inventaire (ne renvoyer que ce qui manque) n'est pas écrit.
- **Un simple membre ne renvoie pas** ce qui a été diffusé à tout l'ensemble (§ 8.7, limites) : possible avec une sorte de partage « renvoi », non écrite.
- **L'alerte** : le moteur porte le droit et la fermeture ; ce qu'une alerte contient, comment on y répond et comment elle finit ne sont pas définis.
- **« Nom entier ou initiales » par les clés** : ci-dessus.
- **Deux opérations sous un même numéro, exprès** (§ 6.4) : toujours la limite la plus sérieuse ; parade à écrire avant tout usage réel, avec le point de reprise.
- **Pas de revue par un spécialiste** de l'assemblage des clés.
- **Renommer** un membre ou l'ensemble, déplacer un groupe sous un autre parent, changer les limites d'un ensemble en cours de route : pas d'opération.

### Limites connues

- **Téléphone du créateur perdu** (S7) : il reste lecteur de tout ce que lit le commandement, jusqu'à la fin de l'opération, et personne ne peut l'écarter (§ 8.6). C'est la conséquence directe du choix « aucune porte dérobée » ; l'écran devrait le dire au moment où l'on choisit de continuer.
- **Plus de canaux, donc plus de lots de clés** : jusqu'à trois par groupe au lieu d'un, plus la voie des chefs et une voie par membre en attente (§ 8.2). Changer un administrateur ou le chef de l'opération refait jusqu'à treize lots (environ 10 Kio pour 30 membres).
- **Ce que voit un téléphone en attente sans les noms** : le nombre de membres et de groupes, les identifiants, qui tient quel poste (§ 8.6).
- **Un membre remis en attente garde ce qu'il savait** : les noms appris, les clés et les objets reçus jusque-là (un téléphone honnête les oublie : `prune`). Il n'apprend plus rien de nouveau.
- **Le fil du milieu d'un groupe est lu par tout le commandement** (chef de l'opération, son adjoint, administrateurs, créateur), pas par une seule personne.
- **Objets retirés** : 1 024 objets par canal, retraits compris ; au-delà les retraits les plus anciens sont oubliés, et un téléphone absent depuis très longtemps pourrait alors faire revenir un objet retiré.
- Celles du § 6.4 et du § 8.6, inchangées.

### Questions pour l'utilisateur

À poser par la session principale. Le premier choix est le choix recommandé ; « aujourd'hui » marque ce que fait le moteur.

1. **Un administrateur (ou le créateur) qui n'est dans aucun groupe et n'est pas chef : à qui part son alerte ?**
   Vous avez répondu qu'un administrateur sans poste de chef alerte comme un membre, donc son groupe. S'il n'a pas de groupe, son alerte ne part aujourd'hui à personne.
   a. À tous les chefs (recommandé) : il lit déjà leurs alertes, et ce sont eux qui peuvent agir.
   b. À toute l'opération.
   c. À personne (aujourd'hui) : un administrateur doit être placé dans un groupe pour pouvoir alerter.

2. **Quand un chef de groupe déclenche une alerte, son propre groupe la reçoit-il aussi ?**
   Vous avez répondu : un chef de groupe alerte tous les chefs. Ses équipiers sont souvent les plus proches de lui.
   a. Oui : tous les chefs et son groupe (recommandé). Le moteur le permet déjà.
   b. Non : tous les chefs seulement.
   c. Il choisit au moment de l'envoi.

3. **Le fil de messages « du milieu » : un par groupe, ou un seul pour tous les chefs ?**
   Vous avez choisi trois fils, et que ce qu'un chef de groupe remonte ne soit pas vu des autres chefs de groupe.
   a. Un fil par groupe (recommandé, aujourd'hui) : le chef du groupe et son adjoint y écrivent au chef de l'opération, à son adjoint et aux administrateurs, qui y répondent. Les autres chefs de groupe ne le lisent pas.
   b. Un seul fil commun à tous les chefs : tous les chefs de groupe se lisent entre eux. Plus simple à suivre, mais ce qu'un chef écrit vers le haut est lu par les autres chefs.

4. **Un waypoint ou un croquis partagé : jusqu'à quand reste-t-il sur la carte des autres, et qui peut l'enlever ?**
   a. Il reste jusqu'à ce qu'on l'enlève, même si celui qui l'a posé change de groupe ou quitte l'opération ; peuvent l'enlever celui qui l'a posé et ceux qui ont le droit de partager au même endroit (recommandé, aujourd'hui). Un point de rendez-vous ne disparaît pas quand le chef est relevé.
   b. Il reste jusqu'à ce qu'on l'enlève ; seuls celui qui l'a posé, les chefs et les administrateurs peuvent l'enlever.
   c. Il disparaît de toutes les cartes quand celui qui l'a posé quitte l'opération ou le groupe.

5. **Les noms sur la carte (« le nom pour son groupe et pour les chefs, des initiales pour les autres ») : est-ce pour la lisibilité, ou pour que les autres groupes ne connaissent pas les noms ?**
   a. Pour la lisibilité (recommandé, aujourd'hui) : chacun peut lire le nom entier en touchant le jeton, comme sur la maquette.
   b. Pour la discrétion : le téléphone d'un membre d'un autre groupe ne doit pas pouvoir connaître le nom entier. Demande un travail de plus, et le nom ne se lit plus en touchant le jeton.

6. **Celui qui attend voit-il le nom de l'opération qu'il a rejointe ?**
   Il ne voit aucun nom de personne ni de groupe : c'est acquis.
   a. Oui, le nom de l'opération seulement (recommandé) : l'écran « Rejoindre l'opération ? » peut le montrer ; il suffit que l'invitation le porte.
   b. Non (aujourd'hui) : rien du tout, pas même le nom de l'opération, tant qu'il n'est pas placé.
