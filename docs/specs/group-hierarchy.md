# Groupes hiérarchiques du partage de position (P39)

> Demande de l'utilisateur (2026-11-13) : `docs/PLAN.md` § P39. Décisions d'architecture : ADR-125 (journal d'opérations), ADR-126 (clés de contenu et signatures), ADR-127 (canaux, noms cachés, objets durables).
> Ce document décrit le **moteur** (P39.2, mis aux règles de l'utilisateur par P39.4, puis à ses choix du second tour et à ses précisions S1 à S7 le 2026-11-14, puis à ses réponses du troisième tour, T1 à T17, le même jour) : la logique, dans `:core`, paquet `fr.toolmap.core.hierarchy`. Rien n'est branché : ni transport, ni stockage, ni écran. Le système de groupe actuel (`fr.toolmap.core.p2p`, `docs/specs/p2p-sharing.md`) n'est pas modifié.
> Les questions du premier tour sont au § 10, les réponses de l'utilisateur aux § 10 bis à 10 quinquies (le moteur les applique), les questions du second tour au § 12, la remontée de la session 103 au § 13, celle de la mise aux choix du second tour (§ 10 quater et 10 quinquies) au § 14, celle de la mise aux réponses du troisième tour (T1 à T17, § 10 sexies) au § 15, celle des clés et du stockage au § 16, celle du transport et du contrôleur au § 17 ; le quatrième passage de maquettes (écarts avant de coder) est au § 18, les choix de l'utilisateur sur ce passage et sur les questions du § 17 au § 10 septies ; la remontée du premier lot d'écrans (P39.8) au § 19.
> Mots : dans les écrans, « ensemble » se dit « opération » et « leader » se dit « chef » (§ 10 ter) ; dans le code et dans ce document, `ensemble` et `leader` restent les noms techniques.
> La trace de dissolution gardée une semaine et les messages gardés sur le téléphone (réponses R1 et R2 du 2026-11-15, P39.9, ADR-131) sont au § 20.
> Le premier essai de bout en bout, sur le vrai serveur de coordination et entre deux émulateurs (P39.12, ADR-134), est au § 23.
> Renommer pendant l'opération (membres, groupes, opération) et les demandes de nom (P39.13, ADR-135, réponses N0 à N14 du 2026-11-16) sont au § 24.

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
- **Noms** (ensemble, groupe, membre) : non vides, sans caractère de contrôle, 64 octets UTF-8 au plus (`Names`). **Les opérations et l'état ne portent pas le nom mais son empreinte** (`HiddenName`, § 8.8) : un téléphone en attente détient les opérations et ne doit y lire aucun nom (Q2). Le seul nom qu'il lit, celui de l'ensemble, lui vient de son invitation (T14).
- L'identifiant d'un groupe supprimé ne revient jamais (`deletedGroups`). Un groupe qui a encore des sous-groupes ne se supprime pas (`HAS_SUBGROUPS`) : on supprime d'abord ce qui est dessous.

Un état construit par le moteur est toujours cohérent ; `HierarchyState.inconsistency()` le vérifie (tests, et lecture d'un état venu d'octets).

### 2.1 En attente

Un arrivant est admis **sans groupe** (V2) : il est « en attente » jusqu'à ce qu'un administrateur l'affecte à un groupe (R1). Tant qu'il attend :

- il n'est lecteur d'**aucun canal des autres** : aucune de leurs clés de contenu n'est enveloppée pour son téléphone, il ne peut donc rien déchiffrer de ce qu'ils partagent (§ 8), et le transport n'a rien à lui adresser ;
- il ne lit **aucun nom de personne ni de groupe** (Q2) : les opérations qu'il détient ne portent que des empreintes ; les noms voyagent scellés avec la clé de tout l'ensemble, qu'il n'a pas (§ 8.8) ;
- il lit **le nom de l'ensemble, et aucun autre** (T14) : son invitation en porte la carte, que son téléphone vérifie contre la première opération (§ 8.8) ;
- **sa position part aux administrateurs seulement** (Q10) : il a une voie à lui (`Lane.WAITING`), lue par lui, les administrateurs et le créateur. Il y envoie sa position et le nom qu'il a choisi, rien d'autre ; personne d'autre n'a le droit d'y écrire : rien ne lui arrive. (2026-11-16, P39.13 : il y envoie aussi le nom qu'il demande, et les administrateurs et le créateur y écrivent les noms qu'il a le droit de connaître, celui de l'ensemble et le sien, rien d'autre ; § 24.) Tant qu'il attend, les administrateurs le voient toujours (T15) : l'écran ne lui offre pas de quoi couper sa position (§ 9) ;
- il ne voit personne sur la carte ; seuls les administrateurs et le créateur l'y voient (`canSee`) ;
- il n'alerte personne et ne reçoit aucune alerte, pas même celle du leader d'ensemble (§ 4.2) ;
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
| `LEADERS` | Entre tous les chefs | Le leader et l'adjoint de chaque unité (ensemble et groupes), les administrateurs, le créateur | Q6, T5 |
| `WAITING` | La voie d'un membre en attente | Ce membre, les administrateurs, le créateur | Q10 |

Une voie où personne n'écrirait n'a pas de lecteurs, donc pas de clé : la voie montante d'un groupe sans leader ni adjoint, la voie descendante d'un groupe sans membre. La voie des chefs a toujours au moins un lecteur, le créateur, qui y alerte (T13) ; comme toute voie, elle n'a besoin de sa clé qu'au premier usage (§ 9).

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

« L'unité que son rôle commande » : l'ensemble pour le créateur, un administrateur, le leader d'ensemble et son adjoint ; son groupe pour un leader de groupe et son adjoint. Trois règles tiennent quelle que soit la table : **on n'écrit que dans un canal qu'on lit** (il en faut la clé) ; **qui attend n'a que les cases du rôle « en attente »** (le rôle « membre », qu'il tient aussi, ne lui donne rien à partager) ; et, pour une sorte **à destination unique** (`HierarchyRules.singleDestination`, une donnée de l'ensemble : l'alerte), **un seul rôle de l'auteur décide** (ci-dessous), là où pour les autres sortes les rôles s'additionnent.

**Table de départ** (`ShareTable.DEFAULT`, une ligne de code par réponse) :

| Sorte | Créateur, administrateur, leader d'ensemble (et son adjoint) | Leader de groupe (et son adjoint) | Membre | En attente |
|---|---|---|---|---|
| Position | (comme membre) | (comme membre) | tout l'ensemble | sa voie d'attente |
| Croquis, waypoint | tout l'ensemble ; un groupe précis | remonter | son groupe | — |
| Message | tout l'ensemble ; répondre dans le fil du milieu d'un groupe | le fil du milieu de son groupe | son groupe | — |
| Alerte (une seule destination) | leader d'ensemble : tout l'ensemble ; créateur, administrateur : tous les chefs | tous les chefs | son groupe | — |
| Noms | (comme membre) | (comme membre) | tout l'ensemble | sa voie d'attente |

Ce que cela donne :

- **Positions** (R2) : à tout l'ensemble, tout le monde voit tout le monde ; celle de qui attend, aux administrateurs seulement (Q10).
- **Dans un groupe** (Q16) : croquis, waypoints, messages et alertes d'un membre restent dans son groupe, fermé à tous les autres.
- **Remontée** (Q3, S1) : le leader d'un groupe et son adjoint font remonter croquis et waypoints ; les reçoivent le leader d'ensemble, son adjoint et les administrateurs (le créateur en est), pas les autres leaders de groupe. L'adjoint remonte comme son leader et lit ce que son leader a remonté. Plus aucun leader de groupe ne diffuse à tout l'ensemble.
- **Diffusion à tout l'ensemble** (S2) : le leader d'ensemble, son adjoint, les administrateurs, le créateur. Personne d'autre.
- **Envoi à un groupe précis** (Q15) : croquis et waypoints, par ceux qui diffusent à tout l'ensemble. Le groupe lit cette voie et n'y écrit pas : ce qu'il partage reste chez lui.
- **Alerte selon le rôle, une seule destination par personne** (Q6, T4, T13) : détail ci-dessous.
- **Messages : trois fils** (S3) : « mon groupe » (le canal du groupe) ; le **fil du milieu** (la voie montante du groupe : son leader, son adjoint et le commandement de l'ensemble, qui y répond) ; « tout l'ensemble » (écrit par le commandement, lu par tous). Un fil du milieu par groupe, parce que les autres leaders de groupe ne doivent pas lire ce qu'un leader remonte (S1). L'autre lecture, un fil commun à tous les chefs, est un changement de deux cases (`LEADERS` à la place de `UP` et de `UP_BELOW`) : un test le montre.
- **Avec des sous-groupes** : les mêmes règles un niveau plus bas. Le leader d'un sous-groupe remonte au leader et à l'adjoint du groupe qui le contient, jamais plus haut.

**L'alerte : une seule destination par personne, sans choix à l'envoi** (Q6, T4, T5, T13, T16). Les rôles se cumulent ; pour l'alerte ils ne s'additionnent pas. Parmi les rôles que tient l'auteur, **le premier dont la case « alerte » n'est pas vide décide seul**, dans cet ordre (`Role.DESTINATION_ORDER`) : leader d'ensemble, son adjoint, créateur, administrateur, leader de groupe, son adjoint, membre. Avec la table de départ :

| L'auteur est | Son alerte part | Qui la lit |
|---|---|---|
| Leader d'ensemble ou son adjoint, quoi qu'il soit d'autre (membre d'un groupe, leader de groupe, administrateur, créateur) | À tout l'ensemble (`Channel.ENSEMBLE`) | Tous les membres placés ; pas ceux qui attendent (T16) |
| Sinon leader de groupe, adjoint de groupe, administrateur ou créateur, avec ou sans groupe | À tous les chefs (`Channel.LEADERS`), **et pas à son propre groupe** (T4, T13) | Le leader d'ensemble, son adjoint, les leaders de groupe, leurs adjoints, les administrateurs, le créateur (T5) |
| Sinon membre d'un groupe | À son groupe | Les membres de ce groupe, son leader et son adjoint compris |
| En attente | Nulle part | — |

`HierarchyState.alertChannel(auteur)` donne ce canal (`null` pour qui attend), `destinationRole(auteur, sorte)` le rôle qui a décidé. Une alerte scellée pour un autre canal est refusée par le téléphone de l'auteur (`mayShare`), et par tous ceux qui la reçoivent si un téléphone modifié la scelle quand même (`ShareSeal.open` : `NOT_ALLOWED`). La destination suit le rôle du moment : un membre nommé leader de son groupe alerte désormais les chefs, plus son groupe (conséquence : § 8.6). Un créateur seul, sans administrateur ni aucun chef, a bien une destination (la voie des chefs) mais personne d'autre ne la lit : l'écran doit compter les lecteurs (`readersOf`) avant de promettre que l'alerte arrive à quelqu'un.

Tout cela est en données : la lecture de T13 tient en une case par rôle (sans les cases « alerte » du créateur et de l'administrateur, ils alertent comme des membres : leur groupe, ou personne) ; l'autre réponse à T4 (« tous les chefs et son groupe ») est une case, les deux portées dans celle du leader de groupe ; un ensemble créé sans l'alerte dans `singleDestination` retrouve l'addition des rôles (l'auteur aurait alors un choix à faire à l'envoi). Un test par cas (`HierarchyAlertTest`).

`HierarchyState.mayShare(auteur, sorte, canal)`, `shareChannels(auteur, sorte)`, `alertChannel(auteur)` et `channelsReadBy(membre)` répondent sans rien écrire.

### 4.3 Qui voit la position de qui (`VisibilityTable`)

Pour chaque rôle de celui qui regarde, les **publics** qu'il voit (`SAME_GROUP`, `GROUP_LEADERS`, `ENSEMBLE_LEADER`, `ADMINS`, `EVERYONE`). `HierarchyState.canSee(viewer, target)` : vrai si l'un des rôles de `viewer` voit l'un des publics dont `target` fait partie. Chacun se voit toujours. Qui est hors de l'ensemble ou en attente ne voit personne. Qui attend n'est vu que de ceux pour qui sa position est scellée (administrateurs, créateur), quelle que soit la table.

**Table de départ** : tout le monde voit tout le monde (R2). Une table plus étroite resterait un filtre d'affichage : les positions sont scellées avec la clé de tout l'ensemble (§ 8).

### 4.4 Comment un nom s'écrit sur la carte (`nameLabel`)

Q17 : le nom est écrit **en entier** sur son propre jeton, sur ceux des membres de son groupe et sur ceux de quiconque tient un poste de leader ou d'adjoint (d'un groupe ou de l'ensemble) ; les autres jetons portent des **initiales** (`Names.initials` : première lettre des deux premiers mots, ou deux premières lettres d'un mot seul, en capitales), le nom entier se lit en touchant le jeton. C'est une **règle d'affichage**, pas une fermeture : tout membre qui a l'accès détient tous les noms (§ 8.8). L'utilisateur l'a confirmé (T11 : c'est pour la lisibilité, la fiche et la liste donnent le nom entier) : rien n'est à fermer par les clés. Ce que cela aurait coûté : § 14.

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
| `RenameMember(member, name)` (2026-11-16, § 24) | Lui donne ce nom aussitôt ; sa demande en cours, s'il en avait une, tombe | Pas membre ; même nom caché (`NO_CHANGE`) |
| `RequestName(member, name)` (2026-11-16, § 24) | Demande ce nom pour soi (qui attend compris) ; remplace la demande en cours ; efface la dernière réponse reçue | Pas membre ; c'est déjà son nom, ou déjà le nom demandé (`NO_CHANGE`) |
| `WithdrawName(member)` (2026-11-16, § 24) | Retire sa demande tant que personne n'y a répondu (N7 A) | Pas membre ; aucune demande en cours (`NOT_PENDING`) |
| `AnswerName(member, name, accept)` (2026-11-16, § 24) | Accepte (le membre prend ce nom) ou refuse la demande ; garde la réponse et son auteur (`NameAnswer`) ; la première réponse dans l'ordre des opérations décide (N3 A, N6 A) | Pas membre ; la demande en cours n'est pas ce nom-là : déjà répondue, retirée ou remplacée (`NOT_PENDING`) |
| `RenameEnsemble(name)` (2026-11-16, § 24) | Renomme l'ensemble ; les invitations déjà données restent valables (N10 A) | Même nom caché (`NO_CHANGE`) |
| `Dissolve` | Fin de l'ensemble pour tout le monde | — |

- Une opération refusée ne change rien et garde son motif (`Rejection`), le même sur tous les téléphones : `HierarchyReplica.history()` permet de dire à l'auteur « votre changement n'a pas été retenu ».
- `HierarchyReplica.issue(change)` vérifie d'abord sur le téléphone de l'auteur : un changement qu'il sait refusé n'est pas écrit du tout. `HierarchyReducer.check(state, author, change)` répond sans rien écrire (pour griser un bouton).
- Un membre retiré peut être admis de nouveau : il revient en attente.
- Le nom d'un membre est celui donné à l'admission ; il n'y a pas d'opération pour le changer, ni pour renommer l'ensemble (à ajouter si l'interface le demande).
  - 2026-11-16 (P39.13, ADR-135, réponses N2, N3, N10 et N11 de l'utilisateur) : l'interface le demande ; cinq changements le font désormais (`RenameMember`, `RequestName`, `WithdrawName`, `AnswerName`, `RenameEnsemble`, ci-dessus et § 24). Seuls les ensembles créés depuis en ont les droits (les règles voyagent dans la première opération).
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

Gros-boutiste, comme `Wire`. Tout bloc commence par `version (1) = 4 | genre (1)`. Clé publique : point P-256 non compressé, 65 octets (`0x04 | X | Y`). Signature : `r (32) | s (32)`. Nom caché : 32 octets. Canal : `voie (1) | portée (8)`. Le format n'est **pas** branché au transport.

**Version 4 (2026-11-14).** Par rapport à la version 3 : les règles portent un octet de plus, les sortes de partage **à destination unique** (l'alerte, § 4.2) ; un ensemble créé avec les règles de la version 3 n'aurait pas été lu de la même façon par ce moteur, d'où le changement de version. Version 3 (le même jour), par rapport à la version 2 : les noms sont des empreintes de 32 octets et non plus du texte ; les lots de clés et les partages nomment un canal (une voie et sa portée) là où la version 2 nommait une unité ; les règles portent deux sortes de partage de plus, le rôle « en attente », les nouvelles portées de partage et la succession de l'adjoint. **Les versions 1 à 3 n'ont jamais été déployées** (branche de conception privée, rien de publié) : elles ne sont pas lues, aucune compatibilité n'est gardée. Un bloc d'une autre version est refusé proprement (`WireException` « unknown version », `kindOf` rend `null`) ; les étiquettes de toutes les signatures et dérivations portent `v4`, de sorte qu'une signature faite pour la version 3 ne vaut rien ici.

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

**Règles** : `n (1) | n × (opération (1), rôle (1), portées (1))` pour les cases non vides des droits, dans l'ordre des codes ; `m (1) | m × (rôle (1), publics (1))` ; `k (1) | k × (sorte de partage (1), rôle (1), portées de partage (1))` ; `sortes signées (1)` ; `sortes à destination unique (1)` ; `drapeaux (1 ; bit 0 : l'adjoint succède au leader qui s'en va)` ; `membres au plus (2) | groupes au plus (2) | niveaux au plus (1)`. Codes : rôles 1 à 8 (`Role`), opérations 1 à 15 (`Operation`), portées en bits 1 / 2 / 4 (`Reach`), publics en bits 1 à 16 (`Audience`), sortes de partage 1 à 6 (`ShareKind`, en bits 1 à 32 pour les sortes signées et pour les sortes à destination unique), portées de partage en bits 1 à 64 (`ShareReach`), voies 1 à 5 (`Lane`).

**État** : `ensemble (8) | nom caché (32) | drapeaux (1 ; bit 0 : dissous) | créateur (8) | leader (8 ; 0 : vacant) | adjoint (8 ; 0 : aucun) | règles | n (2) | n × (membre (8), nom caché (32), clés (130), groupe (8 ; 0 : aucun), administrateur (1))` puis `g (2) | g × (groupe (8), parent (8), nom caché (32), leader (8), adjoint (8))` puis `d (2) | d × groupe supprimé (8)`, tout par identifiant croissant : un même état donne toujours les mêmes octets. Il sert à garder l'état sur le disque pour un démarrage rapide ; l'accord entre téléphones passe par les opérations, jamais par l'état.

**Lot de clés** : `ensemble (8) | canal (9) | nom de la liste de lecteurs (16) | identifiant de la clé (8) | émetteur (8) | clé éphémère (65) | n (2) | n × (lecteur (8), clé enveloppée (48))`, lecteurs par identifiant croissant, puis `signature de l'émetteur (64)`.

**Partage** : `ensemble (8) | canal (9) | identifiant de la clé (8) | sorte (1) | auteur (8) | nonce (12) | partie scellée`, où la partie scellée contient la charge utile, la signature de l'auteur (64) pour une sorte signée, et l'étiquette d'authentification (16).

**Charges utiles définies par le moteur** (à l'intérieur de la partie scellée) : pour un croquis ou un waypoint, un **enregistrement d'objet** (§ 8.7) : `auteur (8) | objet (8) | révision (4) | éditeur (8) | drapeaux (1 ; bit 0 : retiré) | signature de l'éditeur (64) | corps` ; pour des noms, des **cartes** (§ 8.8) : `n (2) | n × (sel (16), nom)`, le nom en longueur sur 1 octet puis UTF-8, 128 cartes au plus par partage. Les autres sortes (position, message, alerte) portent ce que l'application y met.

**Tailles** : une opération fait 158 octets de plus que le corps de son changement (en-tête, longueur, ensemble, auteur, numéro, horloge, code, preuve) : 174 octets pour une affectation, 206 pour une création de groupe, 328 pour une admission (un nom pèse 32 octets quelle que soit sa longueur), 537 pour la création de l'ensemble avec les règles de départ (530 en version 3 : deux cases de plus pour l'alerte du créateur et de l'administrateur, un octet pour les sortes à destination unique). Un lot de clés : 182 octets plus 56 par lecteur (518 pour 6 lecteurs, 1 862 pour 30). Un partage : 64 octets de plus que sa charge utile, 128 quand il est signé ; une position de 32 octets signée : 160 octets (96 sans signature). Un objet durable : 93 octets de plus que son corps, dans un partage signé. Une carte de nom : 17 octets plus le nom.

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

- **Clé de l'invitation** (à brancher, § 9) : portée par le lien ou le QR d'invitation ; elle scelle les trames qui transportent les opérations et les lots de clés. Tout membre admis l'a, **en attente compris** : c'est ce qui lui permet de recevoir le journal des changements, donc de savoir qu'il est admis, à qui envoyer sa position, puis qu'il est placé. Elle n'ouvre **aucun partage** et ne donne **aucun nom** ; l'invitation porte, à côté d'elle, la carte du nom de l'ensemble, et de lui seul (T14, § 8.8).
- **Ce que détient un membre en attente** : sa propre identité (ses deux paires de clés), la clé de l'invitation, le journal des opérations (sans aucun nom : des identifiants, des clés publiques, la forme de la hiérarchie, qui tient quel poste), la carte du nom de l'ensemble venue de son invitation (T14), et la clé de sa propre voie d'attente. Aucune autre clé de contenu : aucune n'a jamais été enveloppée pour lui, et les lots qu'il voit passer ne contiennent aucune copie qu'il puisse ouvrir (testé : chaque copie de chaque lot essayée avec ses clés).
- **Clés de contenu** : une par canal et par liste de lecteurs. Un membre placé détient celles de son groupe, de chaque unité au-dessus, de l'ensemble, et de la voie descendante de son groupe ; un leader de groupe ou son adjoint, en plus, celles de la voie montante de son groupe et de la voie des chefs ; le commandement de l'ensemble (administrateurs et créateur compris, chefs ou non), celles de l'ensemble, de toutes les voies montantes et descendantes, de la voie des chefs ; les administrateurs et le créateur, en plus, celles des voies d'attente. **Aucun d'eux ne détient la clé d'un groupe dont il n'est pas membre.**

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

Le fil « mon groupe » (R7) et tout ce qu'un groupe partage ne doivent être lus que dans ce groupe (Q16) ; ce qu'un chef remonte, que par le commandement (S1) ; l'alerte d'un chef, d'un administrateur ou du créateur, que par les chefs, et pas par le groupe de celui qui alerte (Q6, T4, T13) ; la position de qui attend, que par les administrateurs (Q10). Avec une seule clé pour tout l'ensemble, ou une seule par groupe, ce ne seraient que des filtres d'affichage : tout membre placé pourrait lire le reste avec une application modifiée. La sécurité étant exigée dans ce mode, chaque canal a sa clé. C'est le même code pour toutes les voies ; un lot de voie est petit.

La voie descendante existe pour concilier Q15 et Q16 : le commandement doit pouvoir **écrire à** un groupe sans pouvoir **lire** ce groupe. Avec une clé commune il faut la détenir pour sceller ; lui donner la clé du groupe lui ouvrirait le groupe. La voie descendante a donc ses propres lecteurs (le groupe et le commandement) et sa propre clé, et le groupe n'a pas le droit d'y écrire : rien de ce qu'il partage n'y passe.

### 8.5 Sceller et ouvrir un partage (`ShareSeal`)

- **Sceller** : refusé d'abord si l'auteur n'a pas le droit (`mayShare`), puis s'il n'a pas la clé du moment. AES-256-GCM avec une clé dérivée de la clé de contenu **et de l'auteur** (deux auteurs ne tirent jamais leurs nonces au hasard sous la même clé), nonce de 12 octets au hasard ; l'en-tête en clair (ensemble, canal, clé, sorte, auteur) est authentifié avec le reste.
- **Signature de l'auteur**, à l'intérieur de la partie scellée, pour les sortes que les règles nomment (`HierarchyRules.signedShares`) : **toutes, positions comprises** (S5). Sans elle, n'importe quel lecteur d'un canal pourrait partager au nom d'un autre : une clé commune dit seulement « un des lecteurs ». C'est ce qui rend les filtres du § 4.2 réels : un membre a la clé de l'ensemble (pour les positions), il ne peut pas pour autant y écrire un message au nom du leader.
- **Ouvrir**, dans l'ordre : le téléphone détient la clé nommée (sinon `NO_KEY`) ; il lit encore ce canal (`NOT_A_READER`) ; la clé ouvre (`UNDECIPHERABLE`) ; **l'auteur a le droit, dans l'état du moment**, de partager cette sorte dans ce canal (`NOT_ALLOWED` : qui a été retiré, a changé de groupe ou a perdu son poste ne partage plus, même avec une clé qu'il a gardée) ; puis, pour une sorte signée, la signature est celle de l'auteur avec la clé de son admission (`FORGED`) ; pour une sorte non signée, la clé doit être celle des lecteurs du moment (`STALE_KEY`), puisque seule la clé dit alors qui a scellé.
- Un partage signé, scellé avec la clé précédente par un téléphone qui n'avait pas encore appris un changement, est donc encore lu par ceux qui détiennent cette clé : rien ne se perd pendant qu'un changement se propage.
- **Une alerte n'a qu'une destination** (§ 4.2) : le droit vérifié à l'ouverture est celui-là. L'alerte qu'un téléphone modifié scelle ailleurs (un leader de groupe dans son propre groupe, un administrateur dans le sien, quiconque dans tout l'ensemble sans le mener) est refusée par tous ses lecteurs (`NOT_ALLOWED`), signée ou non (testé avec une trame bien faite, signée de la clé de son auteur).

**Positions signées (S5) : ce que cela coûte.** Format : celui de tout partage signé ; la signature (64 octets) est dans la partie scellée, après la position. Vérification : à l'ouverture, avec la clé de signature inscrite à l'admission de l'auteur ; une position dont la signature n'est pas celle de son auteur est refusée (`FORGED`), sur tous les téléphones, y compris quand elle est scellée avec la bonne clé par un autre lecteur (testé). Taille, pour une position de 32 octets : **96 octets sans signature, 160 avec** (+ 64, soit + 67 %). Temps, **mesuré sur la JVM de la machine de build** (OpenJDK 21, 4 processeurs, `PositionCostTest`, 400 tours après échauffement, écrit dans `core/build/hierarchy-position-cost.txt`), deux passages, le premier seul, le second pendant le build complet : sceller une position, 14 à 30 µs sans signature et 0,2 à 0,4 ms avec (la signature seule : 198 à 373 µs) ; l'ouvrir, 10 à 24 µs sans et 0,7 à 1 ms avec (la vérification seule : 706 à 901 µs). Signer coûte donc une quinzaine de fois plus que sceller, et vérifier une quarantaine de fois plus qu'ouvrir, sur des durées qui restent sous la milliseconde ici. **Rien n'est mesuré sur un téléphone** : un téléphone est plus lent, une clé de signature gardée dans le Keystore plus lente encore (un appel au matériel par signature), et l'effet sur la batterie n'est pas connu. À 30 membres qui envoient chacun une position toutes les quelques secondes, chaque téléphone vérifie une trentaine de signatures par période : à mesurer sur le terrain avant un usage réel. Le choix reste une donnée de l'ensemble (`signedShares`) : un ensemble créé sans les positions dans cette liste a des positions légères et la limite connue (un lecteur peut forger la position d'un autre, testé).

Testé (`ShareSealTest`, `HierarchyKeysTest`, `HierarchyChoicesTest`, `HierarchyAlertTest`, `PositionCostTest`) : allers-retours des six sortes dans les cinq voies, tailles ; partage qu'un téléphone modifié scelle sans en avoir le droit, ou au nom d'un autre : refusé partout ; octets abîmés, coupés, bruit : toujours un refus, jamais autre chose.

### 8.6 Ce que ce schéma ne fait pas

- **Recevoir.** Le moteur dit à qui adresser (`readersOf`) ; empêcher un téléphone en attente de **recevoir** les octets chiffrés dépend du transport : sur une liaison directe on n'envoie qu'aux lecteurs ; par le serveur de coordination, qui relaie à tous ceux qui ont la clé de l'invitation, il verra passer des octets qu'il ne peut pas ouvrir. La fermeture garantie est celle du déchiffrement.
- **Pendant qu'un changement se propage**, un téléphone qui ne l'a pas appris scelle encore pour l'ancienne liste : celui qui vient d'en sortir lit ces partages-là. Cela dure le temps que l'opération arrive.
- **Un membre placé malveillant** peut toujours recopier ce qu'il lit, et remettre un lot ou une clé à qui il veut : aucune cryptographie ne l'empêche.
- **Ce qu'un téléphone en attente apprend sans les noms** : combien il y a de membres et de groupes, leurs identifiants et leurs clés publiques, qui (par identifiant) tient quel poste, quand la hiérarchie change. Aucun nom de personne ni de groupe ; le nom de l'ensemble, par son invitation (T14). Cacher aussi cette forme demanderait de ne pas lui donner le journal, donc de lui faire croire sur parole qui est administrateur : écarté.
- **Quiconque détient l'invitation lit le nom de l'ensemble** (T14, § 8.8) : celui à qui elle est destinée, mais aussi celui à qui on l'a transmise, qui a photographié le QR, ou un membre retiré qui l'a gardée. Rien d'autre : aucun nom de personne ni de groupe, aucun partage.
- **Quiconque détient encore la clé de l'invitation** (un membre retiré, tant que l'invitation n'est pas renouvelée) continue de lire la forme de la hiérarchie, pas les noms nouveaux ni aucun partage.
- **Une alerte partie juste avant un changement de rôle de son auteur.** La destination d'une alerte suit le rôle du moment, et le droit se vérifie à l'ouverture, dans l'état du moment : si un membre alerte son groupe puis est nommé leader (ou l'inverse) avant que l'alerte soit arrivée partout, les téléphones qui apprennent la nomination d'abord refusent l'alerte (`NOT_ALLOWED`) ; ceux qui l'ont déjà ouverte l'ont eue (testé). C'est la règle de tout partage, mais une alerte ne doit pas se perdre : au branchement, une alerte déjà affichée reste affichée, et le téléphone d'un auteur dont la destination change pendant que son alerte court la renvoie dans la nouvelle (§ 9).
- **Un membre remis en attente garde ce qu'il avait reçu** (accepté par l'utilisateur, T16) : les noms appris, les clés et les objets reçus jusque-là, qu'un téléphone honnête oublie (`KeyRing.prune`, `SharedObjects.prune`, `NameBook.prune`). Il n'apprend plus rien de nouveau.
- **Téléphone du créateur perdu** (S7). L'ensemble **continue** avec ses administrateurs : admettre, créer, renommer et supprimer des groupes, placer, changer de groupe, remettre en attente (ce qui coupe un accès), nommer à tous les postes ; les membres partagent, partent d'eux-mêmes. **Plus personne ne peut** : retirer un membre de l'ensemble, nommer ou retirer un administrateur, passer la main, dissoudre. Un administrateur qui part n'est pas remplacé ; quand il n'en reste plus, plus personne n'admet, ne place ni ne nomme : l'ensemble ne fait plus que partager jusqu'à ce qu'on en crée un autre. **Le téléphone perdu reste le créateur** : il garde l'accès sans groupe, personne ne peut le retirer ni le remettre en attente, et chaque clé neuve de l'ensemble, des voies montantes et descendantes, de la voie des chefs et des voies d'attente est encore enveloppée pour lui. Qui trouve ce téléphone déverrouillé lit donc tout cela jusqu'à la fin de l'ensemble (accepté par l'utilisateur, T16). La seule parade est celle que l'utilisateur a choisie : créer une nouvelle opération (testé : `HierarchyChoicesTest`, S7).
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

**Le besoin.** Qui attend ne doit lire aucun nom de personne ni de groupe (Q2 : « sobre ») ; il lit le nom de l'ensemble, et lui seul (T14). Or il détient le journal des opérations, et il en a besoin : c'est ce qui lui dit qui est administrateur, donc à qui sceller sa position, et ce qui lui permet de vérifier les lots de clés. Les noms sortent donc des opérations.

**Ce que porte une opération.** À la place d'un nom, son **empreinte** : SHA-256 d'un sel de 16 octets tiré au hasard et du nom (`HiddenName`, 32 octets). Elle ne dit rien du nom (le sel empêche d'essayer des noms), et elle l'engage : le nom ne peut pas être changé après coup sans une nouvelle opération. L'état ne porte que des empreintes.

**La carte.** Ce qui ouvre une empreinte : le sel et le nom (`NameCard`). Une carte **se prouve elle-même** (son empreinte est dans une opération signée par qui avait le droit de l'écrire) : n'importe quel téléphone qui en détient une peut la transmettre, sans rien avoir à garantir.

**Comment les noms voyagent.** Dans un partage de la sorte « noms » (`ShareKind.ROSTER`), scellé avec la clé de **tout l'ensemble** : tous ceux qui ont l'accès le lisent, qui attend ne peut pas (testé : la trame ne s'ouvre pas chez lui, et ni les opérations ni l'état qu'il détient ne contiennent un nom, pas même le sien ni celui de l'ensemble). Chaque téléphone garde ses cartes dans un carnet (`NameBook`) ; à l'arrivée d'un membre dans l'ensemble, un téléphone présent lui partage les cartes des noms que porte l'état (`NameBook.cardsFor`), comme il lui renvoie les objets durables.

**Le nom de celui qui entre** (Q11). Il choisit son nom ; son téléphone en fait la carte, et l'empreinte va dans l'opération qui l'admet. Tant qu'il attend, il envoie sa carte dans sa voie d'attente : les administrateurs savent qui ils placent, les autres non.

2026-11-16 (P39.13, ADR-135, réponse N11 B) : qui attend peut aussi demander un autre nom ; la carte du nom demandé va dans sa voie d'attente, comme la sienne. Les administrateurs et le créateur y écrivent à leur tour les noms qu'il a le droit de connaître (celui de l'ensemble après un renommage, le sien une fois accepté), et rien d'autre (`ShareReach.WAITING_LANES`, § 24).

**Le nom de l'ensemble, pour qui attend** (T14). L'invitation porte la **carte du nom de l'ensemble** (`NameBook.ensembleCard`, donnée par le téléphone qui invite : il a l'accès, donc la carte). Le téléphone invité s'en sert pour écrire « Rejoindre l'opération “…” ? », puis, dès qu'il détient la première opération, la vérifie : `NameBook.learnEnsembleName` ne garde la carte que si elle ouvre l'empreinte que cette opération a signée (et l'identifiant de l'ensemble, que l'invitation porte aussi, n'admet qu'une première opération, § 5) ; une carte qui ouvrirait un autre nom, de personne ou de groupe, est refusée. Avant cette vérification, le nom affiché n'est que ce que dit l'invitation. `HierarchyState.namesFor(membre)` dit quels noms un membre a le droit de connaître : tous s'il a l'accès ; le nom de l'ensemble et le sien s'il attend ; aucun s'il est dehors ou après la dissolution. Remis en attente, un téléphone honnête oublie les autres (`NameBook.prune(état, soi)`) ; retiré, il oublie tout.

**Ce que cela donne à quiconque détient l'invitation.** Le nom de l'ensemble, en clair, et rien de plus : le sel de la carte ne sert qu'à cette empreinte-là, les autres noms ont chacun le leur. Détiennent l'invitation : celui qu'on invite, toute personne à qui le lien est transmis ou qui voit le QR, et un membre retiré qui l'a gardée. Le nom d'une opération n'est donc pas un secret : il ne faut rien y écrire de confidentiel (à dire sur l'écran de création, § 9). Il ne change pas après coup (pas d'opération pour renommer l'ensemble) : l'invitation ne peut pas devenir fausse.

2026-11-16 (P39.13, ADR-135, réponse N10 A) : l'ensemble se renomme désormais (`RenameEnsemble`). Une invitation donnée avant reste valable : sa carte ouvre l'empreinte d'un nom que l'ensemble a porté, son téléphone l'accepte comme ancien nom et l'affiche jusqu'à connaître le nom d'aujourd'hui, qu'un administrateur ou le créateur lui donne dans sa voie d'attente (§ 24).

**Ce que le moteur garantit pour les noms** : par les clés, aucun nom de personne ni de groupe à qui attend ni à qui est hors de l'ensemble ; le nom de l'ensemble à qui détient l'invitation. Entre membres qui ont l'accès, tous les noms sont connus de tous ; la règle « en entier ou en initiales » (Q17, T11, § 4.4) est une règle d'affichage.

Testé (`NamesTest`, `HierarchyChoicesTest`) : empreintes, cartes, carnet, format, bruit ; le téléphone en attente et ce qu'il détient (les octets de ses opérations, de son état et de son carnet ne contiennent que le nom de l'ensemble ; chacun des neuf autres noms y est cherché) ; la carte d'un autre nom refusée ; ce qu'il oublie remis en attente, puis retiré.

## 9. Ce qu'il faudra pour brancher

**Fait (étape 1, P39.6, session 111, ADR-128)** : les clés du téléphone et le stockage, dans `:data` (paquet `fr.toolmap.data.hierarchy`), appelés par personne ; détail et vérifications au § 16. **Fait (étape 2, P39.7, session 113, ADR-129)** : le transport (ci-dessous) et le contrôleur des écrans, `OperationController` ; ce qui est construit, l'interface que les écrans appelleront, les lectures faites et les questions : § 17. **Reste** : l'interface (ci-dessous).

**Transport** (`Wire.kt`, `MeshNode.kt`, `:p2p`) :

- Un ensemble est un partage **à part** du groupe actuel : sa propre invitation, ses propres trames. L'invitation porte l'identifiant de l'ensemble et la **clé de l'invitation** ; celle-ci scelle (comme `GroupCrypto` aujourd'hui) les trames de quatre genres nouveaux : opérations (`OPS`), résumé (`SUMMARY`), lot de clés (`KEYS`), partage (`SHARE`). Les versions qui ne les connaissent pas les écartent déjà. **L'invitation porte aussi la carte du nom de l'ensemble** (T14 ; `NameBook.ensembleCard` sur le téléphone qui invite) : elle sert à afficher « Rejoindre l'opération “…” ? » (Q11) et reste le seul nom que lit celui qui attend ; elle donne ce nom à quiconque a l'invitation (§ 8.8). Dès que le téléphone invité détient la première opération, il vérifie la carte (`learnEnsembleName`) ; si elle ne correspond pas, il n'affiche plus ce nom et le signale (`Problems.expected`).
- **Arrivée** : le nouveau téléphone montre ses deux clés publiques et l'empreinte du nom qu'il a choisi (une demande d'entrée signée, à écrire ; **pas le nom en clair** : la demande voyage sous la clé de l'invitation, que tout téléphone en attente détient) ; un administrateur écrit `AdmitMember` avec ces clés et cette empreinte. L'arrivant rattrape alors le journal, y lit qui sont les administrateurs, **émet lui-même la clé de sa voie d'attente** (il en est lecteur, et le premier de `keyIssuers`), puis y envoie la carte de son nom et sa position. Il attend ensuite d'être placé.
- À l'ouverture d'une liaison, après `HELLO` : envoyer son résumé, répondre par `missingFor`, puis les lots de `batchesFor`, puis ce qui est encore affiché (ci-dessous). Refaire de temps en temps.
- **Après chaque changement d'état** : `KeyRing.retry` ; `KeyRing.prune`, `SharedObjects.prune` et `NameBook.prune(état, soi)` (oublier ce qu'on ne lit plus ; remis en attente, le carnet ne garde que le nom de l'ensemble et le sien) ; pour chaque canal de `KeyRing.missing`, attendre un délai selon son rang dans `keyIssuers`, puis `KeyRing.issue` si aucun lot n'est arrivé. **Tout de suite pour le canal d'une unité ; pour une voie (montante, descendante, des chefs, d'attente), seulement quand ce téléphone a quelque chose à y partager** : cela évite de refaire treize lots à chaque changement d'administrateur. Les partages durables (messages, croquis, waypoints, alertes) en attente de clé se gardent et se scellent quand elle arrive ; une position se saute.
- **Renvoyer à qui arrive** (S6, § 8.7 et 8.8) : après un changement d'état, `état.arrivals(étatPrécédent)` donne, canal par canal, qui vient d'y entrer. Pour chaque canal où quelqu'un arrive, et une fois sa clé du moment disponible, un téléphone de `resenders(sorte, canal)` attend un délai selon son rang, puis scelle de nouveau chaque enregistrement de `SharedObjects.resend(canal)` ; pour une arrivée dans l'ensemble, un téléphone qui a l'accès partage aussi les cartes de `NameBook.cardsFor`. Pour ne pas le faire plusieurs fois, celui qui voit passer le renvoi d'un autre (mêmes enregistrements : `DUPLICATE`) annule le sien.
- **Renvoyer à qui revient** : à l'ouverture d'une liaison, après les opérations et les clés, les deux téléphones échangent, pour chaque canal qu'ils lisent tous les deux, l'empreinte `SharedObjects.digest(canal)` (elle ne dit rien des objets ; elle peut voyager sous la clé de l'invitation) et le nombre de noms qui leur manquent (`NameBook.missing`). Là où les empreintes diffèrent, celui des deux qui a le droit de partager dans le canal renvoie ce qu'il détient ; l'autre aussi s'il en a le droit. Un téléphone qui n'en a pas le droit attend de croiser quelqu'un qui l'a. À écrire plus tard si les volumes le demandent : échanger l'inventaire (objet, révision) pour ne renvoyer que ce qui manque.
- **Adresser** : un partage ne s'envoie qu'aux lecteurs de son canal là où le transport le permet (§ 8.6).
- **Alerte** : `alertChannel(auteur)` donne **le** canal où part l'alerte de l'auteur, sans choix à l'envoi (§ 4.2) ; `null` : pas d'alerte (qui attend). L'écran écrit à qui elle part d'après `readersOf` de ce canal (l'auteur ôté ; personne, pour un créateur seul). Une alerte ne doit pas se perdre dans un changement de rôle (§ 8.6) : une alerte déjà affichée chez un lecteur le reste, et quand `alertChannel` de l'auteur change pendant que son alerte court, son téléphone la renvoie dans le nouveau canal. Ce qu'une alerte contient, comment on y répond et comment elle finit restent à définir.
- **Celui qui attend ne coupe pas sa position** (T15) : tant qu'il attend, les administrateurs le voient toujours. C'est l'écran qui n'offre pas l'interrupteur, et le service qui envoie sa position dans sa voie d'attente tant que `isWaiting` ; le moteur n'a rien à porter (il ne connaît pas d'interrupteur). Il lui reste « Quitter l'opération ».
- Taille : une trame fait 16 Kio au plus, et une trame de contrôle du serveur de coordination 4 096 octets : `missingFor(résumé, max)` avec `max` ≈ 12 opérations par trame du serveur ; un lot de clés tient dans une trame du serveur jusqu'à 69 lecteurs, dans une trame directe jusqu'à 289 ; au-delà il faudra le couper (à écrire quand les limites monteront). Un objet durable tient dans un partage jusqu'à 12 195 octets de corps.
- Ce que `add`, `offer` et `accept` répondent sert au journal d'événements : `CONFLICT_…`, `UNAUTHENTICATED`, `MALFORMED`, `FULL`, `FORGED`, `WRONG_RECIPIENTS`, `DAMAGED`, `UNKNOWN_EDITOR` sont à signaler (`Problems.expected`) ; `HELD` et `UNKNOWN_READERS` déclenchent un rattrapage.
- **Renouveler l'invitation** après un retrait (nouvelle clé de l'invitation, distribuée comme une clé de contenu à tous les membres admis) : à écrire, pour qu'un membre retiré ne suive plus la forme de la hiérarchie.

**Clés du téléphone** (`:data`) — **fait** (§ 16) : `OperationStorage.create()` fait une identité par opération ; ses `PhoneKeys` ont la clé de signature dans le Keystore (non exportable) et la clé d'accord dans le Keystore à partir de l'API 31, sinon dans un fichier scellé par une clé du Keystore. Les clés ne vont dans aucune sauvegarde (testé). Une signature que le Keystore refuse lève `KeyVaultException` : le transport l'attrape autour de `issue`, `KeyRing.issue`, `ShareSeal.seal` et `SharedRecords.write`. **À mesurer sur un téléphone avant tout usage réel** : une signature par position envoyée et une vérification par position reçue (§ 8.5) ; l'émulateur donne un premier ordre de grandeur (§ 16).

**Stockage** (`:data`) — **fait** (§ 16) : `StoredOperation.load()` / `save(contenu)` / `wipe()`. Gardés, scellés : les opérations (`HierarchySnapshot.encodeOps`, blocs de `HierarchyWire.encodeOpBlocks`), relues par `HierarchySnapshot.restoreReplica` (l'état se recalcule) ; les clés de contenu détenues, les lots vérifiés et en attente ; les cartes de noms ; les enregistrements d'objets. Tout est effacé quand on quitte l'ensemble, quand on en est retiré, ou à sa dissolution (vu à l'enregistrement ou au chargement) ; ce qui tient à un canal (clés, objets, lots qui redonneraient la clé) est oublié à l'enregistrement qui suit l'arrêt de sa lecture. Ce que le transport devra faire avec : ouvrir ou créer le stockage au démarrage du partage en mode « Opération », `save` après chaque changement (opération reçue ou écrite, lot accepté, carte apprise, objet accepté), `wipe` quand l'utilisateur quitte. Depuis P39.9 (§ 20, ADR-131) : à la dissolution, tout est effacé **sauf une trace** gardée une semaine par chaque téléphone qui était membre ; les messages des fils sont gardés (500 par fil) dans un fichier scellé à part, effacé avec l'opération.

**Interface** (après validation des maquettes de P39.5, `CLAUDE.md` § « aperçu validé avant le code ») : tout se lit dans `HierarchyState` (`rolesOf`, `isWaiting`, `waiting`, `groupOf`, `membersOf`, `visibleTo`, `nameLabel`, `shareChannels`, `alertChannel`, `channelsReadBy`, `namesFor`) et dans `NameBook` (un nom peut ne pas être encore connu : prévoir de quoi l'écrire en attendant), se demande par `issue`, se grise par `HierarchyReducer.check`, et les refus arrivés après coup se lisent dans `history()`. Un bouton ne doit jamais supposer qu'un changement écrit est définitif. « Quitter » pour le créateur propose : désigner un successeur parmi les administrateurs (`TransferCreator` puis `RemoveMember` de lui-même), ou dissoudre (`Dissolve`, confirmé par un glissement, Q4) ; si le passage de main a perdu contre un départ simultané du successeur, le départ du créateur est refusé et l'écran le lui dit. L'écran de création dit que le nom de l'opération est lu par quiconque reçoit l'invitation (§ 8.8) : texte visible, à montrer en maquette avant tout code.

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

## 10 sexies. Choix du troisième tour de maquettes (2026-11-14)

Choix faits par l'utilisateur sur les maquettes du troisième passage (`docs/previews/p39-groupes/tour3/`) et sur les questions du moteur (§ 14). Les questions T1 à T12 sont celles de `tour3/REMONTEE.md` ; T4, T6, T11 et T12 reprennent aussi les questions 2, 3, 5 et 4 du § 14 ; T13 et T14 sont les questions 1 et 6 du § 14 ; T15 vient de l'interrupteur dessiné sur l'écran d'attente ; T16 valide les suppositions listées plus bas ; T17 valide tous les autres écrans du troisième passage. Bloc recopié tel quel :

```text
Choix P39 tour 3 (maquettes « Opération »)
T1 A · « Commandement »
T2 1 · Oui : les trois mêmes lignes que le chef de l'opération
T3 C · La ligne « Un groupe », puis une liste déroulante
T4 B · Non : aux chefs seulement
T5 1 · Oui
T6 A · Un fil par groupe
T7 A · « Commandement »
T8 A · Dans la fenêtre aux deux sorties
T9 A · Une tuile à côté d'« Administrer »
T10 B · Un bandeau en tête de l'écran de l'opération
T11 A · Lisibilité : la fiche et la liste donnent le nom entier
T12 1 · Tout reste jusqu'à ce qu'on l'enlève ; l'enlèvent son auteur et ceux qui peuvent partager au même endroit
T13 1 · À tous les chefs
T14 1 · Oui, le nom de l'opération seulement
T15 2 · Non : tant qu'il attend, les administrateurs le voient toujours
T16 1 · D'accord pour tout
T17 1 · D'accord pour tout
```

**Lecture de la session principale** (interprétation ; elle remplace, là où elle les contredit, les lectures des paragraphes précédents et celles des §§ 13 et 14) :

1. **« Commandement »** (T1, T7) désigne le chef de l'opération, son adjoint et les administrateurs (le créateur en est un). C'est le nom de la ligne par laquelle un chef de groupe remonte un croquis ou un waypoint, et celui du fil de messages du milieu.
2. **Envoi à un groupe précis** : les administrateurs le peuvent aussi (T2). Le groupe se choisit par la ligne « Un groupe », puis une liste déroulante (T3 C).
3. **Alerte.** Un membre alerte son groupe. Un chef de groupe et son adjoint alertent tous les chefs, et pas leur propre groupe (T4 B). « Tous les chefs » : le chef de l'opération, son adjoint, les chefs de groupe, leurs adjoints et les administrateurs (T5). Un administrateur qui n'est pas chef alerte tous les chefs (T13) ; lecture : le créateur de même, et pas leur groupe, comme pour un chef de groupe. Le chef de l'opération et son adjoint alertent tous les membres placés ; ceux qui attendent ne reçoivent pas l'alerte (T16).
4. **Fil du milieu** : un fil par groupe, entre le chef du groupe, son adjoint et le commandement (T6 A). Un simple membre n'a que deux fils, « Mon groupe » et « Toute l'opération » (T16).
5. **Dissoudre** : la glissière est dans la fenêtre aux deux sorties ; le glissement remplace la seconde confirmation (T8 A).
6. **« Inviter »** : une tuile à côté d'« Administrer », avec le compte ; grisée, elle écrit « Complet · 30 sur 30 » (T9 A).
7. **Rappel au créateur** de désigner un successeur avant de changer de téléphone : un bandeau en tête de l'écran de l'opération, à chaque ouverture, jusqu'à ce qu'il touche « Compris » (T10 B).
8. **Noms** : la règle « nom entier pour son groupe et pour les chefs, initiales pour les autres » est une règle de lisibilité. La fiche et la liste donnent le nom entier (T11 A) ; les adjoints ont leur nom entier sur la carte comme les chefs (T16). Rien à fermer par les clés.
9. **Ce qu'un membre a partagé** reste jusqu'à ce qu'on l'enlève, même s'il change de groupe ou quitte l'opération ; l'enlèvent son auteur et ceux qui peuvent partager au même endroit (T12). C'est ce que fait le moteur.
10. **Celui qui attend** voit le nom de l'opération, et aucun autre nom (T14) : l'invitation porte ce nom. Il ne peut pas couper l'envoi de sa position aux administrateurs (T15) : l'écran d'attente n'a pas d'interrupteur. Lecture : pour ne plus être vu, il quitte l'opération. Celui qui est remis en attente lit encore le nom du groupe qu'il vient de quitter, qu'il connaissait déjà (T16).
11. **Suppositions acceptées telles quelles (T16)**, en plus de celles reprises aux points 3, 4, 8 et 10 : l'adjoint d'un chef de groupe remonte comme son chef et lit ce que son chef a remonté, les autres membres du groupe ne le voient pas ; l'envoi à un groupe précis vaut pour les croquis et les waypoints, pas pour les messages, tout le groupe visé le voit et n'y répond pas à cet endroit ; les positions restent visibles de toute l'opération ; supprimer un groupe retire de l'affichage ses croquis, ses waypoints et ses messages ; « le chef part » comprend « il change de groupe » et « il est remis en attente », son adjoint devient chef dans les trois cas ; le créateur ne peut pas simplement quitter (successeur, ou dissolution par le glissement) et l'ancien créateur redevient un membre comme un autre ; un membre remis en attente garde ce qu'il avait déjà reçu et n'apprend plus rien de nouveau ; le téléphone perdu du créateur continue de lire ce que lit le commandement jusqu'à la fin de l'opération, personne ne peut l'écarter, et l'écran le dira ; avec un fil par groupe, le chef de l'opération n'a pas de fil pour écrire aux seuls chefs ; quelqu'un qui est à la fois chef de groupe et administrateur aurait quatre lignes dans la fenêtre d'envoi (cas non dessiné).
12. **Maquettes validées (T17, et Q19 et Q20 du § 10 quater)** : les écrans du troisième passage sont validés par l'utilisateur le 2026-11-14 dans les variantes choisies ci-dessus ; les écrans du second tour que le troisième passage n'a pas redessinés restent validés tels quels.

**Écarts à remontrer en aperçu avant de les coder** (ils ne sont pas dessinés tels quels, ou ont été relevés par le contrôle indépendant des 52 images) : l'écran d'attente sans interrupteur (T15) ; le texte de l'alerte d'un administrateur qui n'est pas chef (T13) ; la fenêtre d'envoi à quatre lignes d'un chef de groupe administrateur ; la ligne « Messages » absente de quatre écrans (`09-promotion-groupe`, `09-promotion-operation`, `09-chef-operation-quitte`) ; deux étiquettes de carte collées (`10-attente-admin`, `11-arrivant`) ; des textes ambigus (« Personne pour écrire dans ce fil », « Écrire au Groupe Sud », « Seuls les chefs envoient plus loin que leur groupe ») ; le chemin indiqué par le rappel au créateur, qui passe par « Quitter l'opération » ; le contraste de « jusqu'au bout » sur la glissière ; l'écran de création du second tour, où le groupe du créateur est à mettre en accord.

**Moteur** : seuls T4, T13 et T14 lui demandent un changement (alerte d'un chef de groupe sans son groupe, alerte d'un administrateur vers tous les chefs, nom de l'opération porté par l'invitation) ; il est relancé sur ces trois points le 2026-11-14. Aucun écran n'est codé à ce jour.

## 10 septies. Choix du quatrième passage (2026-11-15)

Choix faits par l'utilisateur sur les maquettes du quatrième passage (`docs/previews/p39-groupes/tour4/`, § 18) et sur les questions du transport (§ 17). U1 à U10 sont les questions du § 2 de `tour4/REMONTEE.md` (toujours la variante A) ; E1 valide les dix écarts sans choix de son § 3 ; R1 à R5 sont les « Questions pour l'utilisateur » 1 à 5 du § 17 ; E2 valide ses « Lectures faites là où la spécification ne tranche pas ». Bloc recopié tel quel :

```text
Choix P39 tour 4 (écarts et échange)
U1 A · La ligne « Ma position » reste, avec un cadenas
U2 A · Oui : « Le Groupe Est ne la reçoit pas, sauf son chef »
U3 A · La ligne d'alerte est grise : « Aucun chef nommé pour la recevoir »
U4 A · Une seule liste, du plus proche au plus large
U5 A · Une ligne grise avec un cadenas : « Fil fermé : ce groupe n'a ni chef ni adjoint »
U6 A · « Au chef et à son adjoint »
U7 A · « Seul le Groupe Nord le verra. »
U8 A · Un bouton « Transmettre » à côté de « Compris »
U9 A · Sous le champ du nom, en petit
U10 A · Pas de durée : il reste affiché jusqu'à ce que quelqu'un le retire
R1 A · Chaque téléphone garde une semaine une trace fermée de l'opération
R2 A · Oui, les 500 derniers de chaque fil
R3 A · Oui : après un départ, on redonne une invitation (ce qui est fait)
R4 A · Celui intégré à l'application (ce qui est fait)
R5 A · Oui, lui et les autres administrateurs seulement (ce qui est fait)
E1 1 · D'accord pour tout
E2 1 · D'accord pour tout
```

Précisions affichées à l'utilisateur sur sa page de choix : R1 A, la trace ne sert qu'à annoncer la dissolution aux retardataires ; elle ne contient ni position, ni nom, ni message. R2 A, les messages sont scellés avec l'opération et effacés avec elle.

**Lecture de la session des écrans** (session 116 ; chaque ligne reprend le sens donné dans `tour4/REMONTEE.md` et au § 17) :

- **U1 A** : l'écran d'attente garde la ligne « Ma position » (« Membre 21 (vous) · envoyée aux administrateurs ») avec un cadenas à la place de l'interrupteur ; sous « Quitter l'opération », « C'est le seul moyen de ne plus envoyer sa position. » ; seul le nom de l'opération y est écrit (T14, T15).
- **U2 A** : la feuille d'alerte d'un administrateur ou du créateur qui n'est pas chef dit, sous « Tous les chefs · 7 personnes », « Le Groupe Est ne la reçoit pas, sauf son chef (Membre 14). ».
- **U3 A** : la règle T13 reste (une seule destination par personne) ; sans aucun chef ni autre administrateur nommé, la ligne « Envoyer une alerte » du créateur est grise (« Aucun chef nommé pour la recevoir ») et, touchée, explique et mène à « Administrer ». Le moteur ne change pas.
- **U4 A** : chef de groupe et administrateur à la fois, une seule liste de quatre lignes, du plus proche au plus large : « Mon groupe », « Commandement », « Un groupe » (liste déroulante), « Toute l'opération » ; « Mon groupe » cochée à l'ouverture.
- **U5 A** : dans « Commandement », le fil d'un groupe sans chef ni adjoint reste à sa place, en ligne grise avec un cadenas : « Fil fermé : ce groupe n'a ni chef ni adjoint ».
- **U6 A** : dans le fil d'un groupe ouvert par le commandement, le champ de saisie dit « Au chef et à son adjoint » ; une ligne en tête du fil nomme ses lecteurs.
- **U7 A** : sous la ligne unique de la fenêtre d'envoi d'un membre : « Seul le Groupe Nord le verra. ».
- **U8 A** : le bandeau du créateur a « Transmettre » à côté de « Compris » ; « Transmettre » ouvre la feuille « Transmettre le rôle de créateur » (administrateurs seulement ; il reste dans l'opération, dans son groupe, comme membre ; ce que garderait l'ancien téléphone). Le menu ⋮ du créateur a aussi « Transmettre le rôle de créateur… » au-dessus de « Quitter l'opération… » (écart 10, validé par E1).
- **U9 A** : sous le champ du nom de l'opération, en petit : « Le nom est lu par toute personne qui reçoit l'invitation, même avant d'être placée dans un groupe. N'y mettez rien de confidentiel. ».
- **U10 A** : un waypoint ou un croquis d'une opération n'a pas de durée : il reste affiché jusqu'à ce que quelqu'un le retire (T12, ce que fait le moteur) ; « Quitter l'opération ? » dit « restent affichés jusqu'à ce que quelqu'un les retire ».
- **R1 A** (question 1 du § 17) : chaque téléphone garde une semaine une trace fermée de l'opération dissoute, qui ne sert qu'à annoncer la dissolution à un téléphone resté absent ; elle ne contient ni position, ni nom, ni message. Changement du transport, codé par la session P39.9 (session 117), pas par les écrans.
- **R2 A** (question 2) : les 500 derniers messages de chaque fil restent après un redémarrage de l'application, scellés avec l'opération et effacés avec elle. Codé par la session P39.9.
- **R3 A** (question 3) : la clé d'invitation est renouvelée après chaque départ ; les invitations données avant ne marchent plus (ce qui est fait). Rien ne change.
- **R4 A** (question 4) : les opérations passent par le serveur intégré à l'application (ce qui est fait).
- **R5 A** (question 5) : le créateur et les administrateurs lisent le nom de qui demande à entrer avant de l'admettre, personne d'autre (ce qui est fait).
- **E1 1** : les dix écarts sans choix du § 3 de `tour4/REMONTEE.md` sont validés tels que dessinés (ligne rouge « à tous les chefs », étiquettes de l'alerte reçue, créateur seul, ligne « Messages » des écrans de promotion, étiquettes de la carte, contraste de « jusqu'au bout », écran de création avec « Est » coché et « Aucun », texte de « Quitter l'opération ? », retrait d'un partage, entrée « Transmettre le rôle de créateur… » du menu ⋮).
- **E2 1** : les neuf lectures du § 17 (nom lu par les administrateurs avant l'admission, salle tirée au hasard, invitation renouvelée après chaque départ, serveur intégré, messages et alertes en mémoire, alerte et sa levée, une opération ouverte à la fois, cartes des noms de qui attend, taille d'un objet durable) sont validées ; la cinquième est remplacée par R2 A.

**Conséquence** : toutes les maquettes sont validées (P39.5 terminé le 2026-11-15). Pour coder, le dessin le plus récent de chaque écran fait foi : `tour4/`, puis `tour3/`, puis `tour2/`. U1 à U10 ne changent que les écrans ; seuls R1 et R2 changent le transport (session P39.9).

## 10 octies. Taille et rythme d'une opération (2026-11-18)

L'utilisateur a demandé le 2026-11-18 ce qu'il faudrait pour qu'une opération accueille plus de 30 personnes, si la limite vient du serveur ou du téléphone, et si une position envoyée toutes les minutes, voire toutes les 10 minutes, allégerait le téléphone. La session principale a répondu, puis posé deux questions (recommandation en premier) :

1. Combien de personnes viser dans une opération ? a) 60 personnes et 12 groupes ; b) 100 ou plus (lots de clés découpés, plan payant de Firebase) ; c) rester à 30 jusqu'aux essais sur le terrain.
2. Quel rythme dans une opération ? a) une position par minute par défaut, réglable par le chef de l'opération de 30 s à 10 min, le signal de présence une fois par minute, le résumé toutes les 5 minutes ; b) une position toutes les 10 minutes, le reste comme aujourd'hui ; c) comme « Entre amis » (une position toutes les 30 s, la présence toutes les 15 s).

Réponses, mot pour mot :

```text
1, aucun pour le moment, la question de capacité était pour de la curiosité.
2, a. Réglages communs pour tout le monde prédéfini par le chef d'opération/ admins.
```

**Lecture** :

- **Taille** : rien ne change ; la limite reste 30 membres et 6 groupes (R19, `HierarchyLimits`). Pour mémoire, dans l'ordre, ce qui bloquerait au-delà : le plan gratuit de Firebase (100 connexions simultanées pour toute la base, 10 Go téléchargés par mois, `docs/specs/firebase-relay.md` § 7 ; le volume croît comme le carré du nombre de membres, puisque chaque téléphone lit toute la salle), puis le lot de clés, qui ne tient dans une trame du serveur que jusqu'à 69 lecteurs (§ 9). Le téléphone n'est pas la limite : à 30 membres, 16 Ko de trames, 58 vérifications et 48 ms de calcul par minute (simulation sur la JVM, § 21 ; pas une mesure sur un téléphone).
- **Rythme (2 a)**, dans une opération seulement (« Entre amis » ne change pas) :
  - un seul rythme de position pour toute l'opération, le même pour tout le monde, réglé à l'avance par le chef de l'opération et les administrateurs ; un membre ne règle pas le sien ;
  - une position par minute par défaut (aujourd'hui toutes les 15 s, sans réglage : `OperationSession.POSITION_EVERY_MS`), réglable de 30 s à 10 min ;
  - le signal de présence passe de 15 s à 1 min, le résumé de 1 min à 5 min ; les délais qui en dépendent suivent (un téléphone disparu se voit au bout de quelques minutes au lieu d'environ une) ;
  - les alertes partent toujours tout de suite ;
  - estimation des échanges par téléphone, extrapolée de la mesure de deux téléphones du § 23 et non mesurée sur un téléphone : environ −65 % avec une position par minute, −85 % toutes les 10 minutes ; à 10 minutes, une position peut avoir environ 800 m de retard à pied.
- **Qui règle** : « chef d'opération/ admins ». Lecture à confirmer par l'utilisateur : le créateur, les administrateurs, le chef de l'opération et son adjoint (un adjoint a les droits de son chef, R8). Ce serait le premier droit du chef de l'opération, qui n'en a aucun sur la hiérarchie (R5) ; le rythme n'en fait pas partie.
- **Avant tout code d'écran** : aperçu à valider, où le rythme se règle et se lit, sous quelle forme, ce que voit un membre (`docs/previews/p39-groupes/tour6/`).
- **Pour la session qui codera** (P39.15) : un réglage de l'opération signé, partagé par tous les téléphones et soumis aux droits (moteur, format et stockage compatibles) ; le service qui envoie la position écran éteint (L4 et M8 du P39.14) envoie à ce rythme ; la présence (`FirebaseRelay.HEARTBEAT_S` et les délais d'ouverture et de fermeture qui en dépendent) et le résumé (`OperationNode.SUMMARY_EVERY_MS`) changent pour le mode « Opération » seulement.

**Choix du tour 6 (2026-11-18)**, sur les aperçus `docs/previews/p39-groupes/tour6/` (questions F1 à F3), mot pour mot :

```text
F1 A
F2 B, 1 à 10min pour le slide et sans limite pour le "2min" touché
F3 A
```

- **F1 A** : une ligne « Fréquence d'envoi » sous « Partager ma position », sur l'écran de l'opération de chacun : une flèche pour qui peut la changer ; pour les autres, un cadenas et « Réglée par le chef de l'opération ». « Nouvelle opération » ne change pas : une minute dès la création.
- **F2 B, modifié par l'utilisateur** : le réglage d'« Entre amis » (feuille « Réglages d'envoi », curseur et valeur à toucher). Le curseur va de 1 à 10 min ; la valeur touchée (« 2 min ») ouvre la fenêtre où taper une valeur sans les bornes du curseur. Lecture : avec les seules bornes de la fenêtre d'« Entre amis », 1 s à 24 h (`ShareCadence.MIN_INTERVAL_S`, `ShareCadence.MAX_INTERVAL_S`). Ce choix remplace « réglable de 30 s à 10 min » de la réponse 2 a ci-dessus. Aperçu de F2 B modifié à valider avant le code (`tour6/img/05-q2-B-modifie.png`).
- **F3 A** : le créateur, les administrateurs, le chef de l'opération et son adjoint changent le rythme ; les chefs de groupe, leurs adjoints et les membres le lisent. La lecture « Qui règle » ci-dessus est confirmée : c'est le premier droit du chef de l'opération, hors de la hiérarchie (R5 inchangé).

**Validation du 2026-11-18**, sur les images `tour6/img/05-q2-B-modifie.png`, `05-q2-B-modifie-2.png` et `05-q2-B-modifie-3.png`, mot pour mot :

```text
1 a, 2 a
```

- **1 a : F2 B modifié est validé tel que dessiné.** La feuille « Réglages d'envoi » de l'opération : un curseur de 1 à 10 min, un cran par minute, les minutes écrites sous les crans ; la valeur à toucher (« 2 min ») ouvre la fenêtre d'« Entre amis », 1 s à 24 h, sans « Déplacement minimal ». Une valeur tapée hors des crans (45 s) reste exacte dans la pastille ; le curseur se met sur le cran le plus proche, comme dans « Entre amis » (à égalité, le plus bas), et seul un curseur déplacé remplace la valeur.
- **2 a : aucune borne propre à l'opération, mais un avertissement de 1 à 29 s.** Dans la fenêtre de l'opération, sous s / min / h : « Moins de 30 s : plus de batterie pour tout le monde, et le serveur gratuit peut atteindre sa limite du mois. » ; « OK » reste actif. Ce seuil et ce texte sont propres à l'opération : « Entre amis » garde les siens (`WARN_BELOW_S`, 5 s, « Moins de 5 s : plus de batterie, et plus d'envois au serveur. »). Lecture : dans l'opération, un seul avertissement, celui-ci, qui couvre aussi 1 à 4 s. Raison donnée à l'utilisateur : trente téléphones à 1 s useraient en une journée environ les 10 Go par mois du forfait gratuit (estimation d'après `firebase-relay.md` § 7), sur le serveur commun à toutes les opérations et à « Entre amis ».
- **L'aperçu de P39.15 est validé** : F1 A (`02-q1-A`), F2 B modifié (`05-q2-B-modifie`, `-2`, `-3`), F3 A (`04-q3`). Le code d'écran peut commencer.

**Réponses du 2026-11-19** sur les états que les images validées ne montraient pas (`tour6/etats/index.html`, `tour6/img/06-f4-changement.png` à `10-minutes-qui-se-touchent.png`, questions F4 à F8), mot pour mot :

```text
F4 b
F5 b (u1 a)
F6 a
F7 a
F8 b pousse plus loin, en divisant le nombre de chiffre montrer, cela gardera une, 2fois moins voir 3fois moins selon l'espace nécessaire pour bien afficher "1min/10min"
```

- **F4 B** : quand le rythme change, la ligne change et un message s'affiche en bas de l'écran de chaque téléphone de l'opération, son auteur et qui attend un groupe compris, comme pour un nom accepté : « Fréquence d'envoi : toutes les 45 s, pour toute l'opération. » (« toutes les minutes » pour une minute). Le code faisait A (la ligne seule) : changé dans 0.4.265.
- **F5 B (U1 A)** : l'écran de qui attend un groupe ne change pas (il envoie déjà sa position aux administrateurs, au rythme de l'opération) ; c'est ce que fait le code.
- **F6 A** : une opération dont les règles ne donnent ce réglage à personne (créée avant 0.4.262) montre à tout le monde, créateur compris, un cadenas et « Fixée à la création de l'opération » ; une minute reste. Le code faisait B (« Réglée par le chef de l'opération ») : changé dans 0.4.265.
- **F7 A** : l'ordre des lignes sous « Partager ma position », avec un nom demandé, reste celui du code ; l'interrupteur et le crayon ne changent pas.
- **F8 B, poussé plus loin par l'utilisateur** : sur un écran étroit, n'écrire sous le curseur qu'une partie des minutes, selon la place, pour que « 1 min » et « 10 min » s'affichent bien. Redessiné par la session principale (`tour6/etroit/b-plus.html`, `tour6/img/11-f8-b-pousse.png`).

**Validation de F8 du 2026-11-19**, mots de l'utilisateur, mot pour mot, après le premier aperçu puis sur l'aperçu redessiné :

```text
Point chichiteux pour le 320dp centralize en affichant 5 aulieu de 4; 7. Cas rare, donc pas important
Oui, code F8
```

- **La règle de F8** : « 1 min » et « 10 min » s'écrivent toujours ; entre les deux, le téléphone prend le premier de ces paliers qui laisse au moins 4 dp entre deux libellés écrits, mesuré avec la largeur réelle du curseur et la taille du texte choisie dans Android : toutes les minutes ; une sur deux (1 min, 3, 5 min, 7, 10 min : une minute à moins d'un pas de « 10 min » ne s'écrit pas) ; « 5 min » seule au milieu ; en dernier recours 1 min et 10 min seules. Chaque libellé écrit reste sous son cran, les bouts peuvent déborder de 4 dp. Si la minute choisie n'est pas écrite, rien n'est en gras : la pastille donne la valeur. En texte normal : une sur deux à 320 et 360 dp, toutes à partir de 384 dp ; à 320 dp avec le texte × 2 : 1 min, 5 min, 10 min. « Entre amis » reste sans libellés. Codé dans 0.4.265 avec F4 B, F6 A et la correction « sous les crans ».

## 11. Fichiers et tests

| Fichier (`core/src/main/kotlin/fr/toolmap/core/hierarchy/`) | Contenu |
|---|---|
| `HierarchyRules.kt` | `Role`, `Operation`, `Reach`, `RightsTable`, `Audience`, `VisibilityTable`, `ShareKind`, `ShareReach`, `ShareTable`, `HierarchyLimits`, `HierarchyRules` |
| `Channel.kt` | `Lane`, `Channel` : les lieux où l'on partage |
| `HierarchyState.kt` | `MemberKeys`, `Member`, `Group`, `HierarchyState` (`rolesOf`, `isWaiting`, `hasAccess`, `commandOf`, `channels`, `readersOf`, `channelsReadBy`, `mayShare`, `shareChannels`, `destinationRole`, `alertChannel`, `resenders`, `arrivals`, `canSee`, `nameLabel`, `hiddenNames`, `namesFor`, `pathToRoot`, `inconsistency`), `NameLabel` |
| `Op.kt` | `Post`, `Change`, `Op` (ordre `Op.ORDER`), `OpProof` |
| `HierarchyReducer.kt` | `HierarchyReducer.reduce` / `check` (dont la succession de l'adjoint), `Rejection` |
| `HierarchyReplica.kt` | `HierarchyReplica` (`add`, `issue`, `summary`, `missingFor`, `history`, `ops`, `readers`, `signingKeyOf`, `create`, `restore`), `AddResult`, `IssueResult` |
| `HierarchyWire.kt` | Format binaire version 4, `Summary` |
| `OpAuth.kt` | `PhoneKeys`, `P256` (clés et signatures sous une seule forme, accord de clés), `OpAuth` (identifiants par empreinte, signature des opérations) |
| `HierarchyKeys.kt` | `Readers`, `ContentKey`, `KeyBatch`, `KeyOffer`, `ContentKeys` (émettre, vérifier, ouvrir un lot), `KeyRing` (les clés d'un téléphone, par canal), `keyIssuers` |
| `ShareSeal.kt` | `Share`, `ShareSeal.seal` / `open`, `ShareRefusal` |
| `SharedObjects.kt` | `SharedRecord`, `ObjectRef`, `SharedRecords` (écrire, retirer, signer, format), `SharedObjects` (ce qui est encore affiché, ce qu'on renvoie), `ObjectVerdict` |
| `Names.kt` | `Names` (noms acceptés, initiales), `HiddenName`, `NameCard`, `NameBook` (dont `ensembleCard`, `learnEnsembleName` : le nom de l'ensemble porté par l'invitation) |

Tests (`core/src/test/kotlin/fr/toolmap/core/hierarchy/`, 140 tests) :

| Fichier | Ce qu'il vérifie |
|---|---|
| `HierarchyReducerTest` (33) | Une règle de l'utilisateur par test, le nom du test commence par la réponse vérifiée (V3, R1 à R19, P2, Q3, Q13) ; refus et leurs motifs ; tables en données ; limites ; sous-groupes à deux niveaux |
| `HierarchyChoicesTest` (17) | Les choix du second et du troisième tour, un test par choix, par les clés quand il s'agit d'une fermeture : Q3 / S1, S2, Q15, Q16, Q6 / T4 / T13, S3, Q2 / T14, Q10, Q12, Q7, Q18 / S4, Q5, Q9, Q14 / P3, P4, Q11 / Q17, S7 |
| `HierarchyAlertTest` (18) | § 4.2 : une seule destination d'alerte par personne, un test par combinaison de rôles (membre ; leader de groupe ; adjoint de groupe ; administrateur avec ou sans groupe ; créateur avec ou sans groupe, seul ; leader d'ensemble et son adjoint ; leader de groupe et leader d'ensemble ; administrateur ou créateur et leader d'ensemble ; administrateur ou créateur et leader de groupe ; en attente ; sous-groupes) ; par les clés (T4, T13, T16) ; alerte scellée ailleurs par un téléphone modifié, refusée partout ; la destination qui suit le rôle ; le même résultat dans les ordres d'arrivée ; les cases et la règle en données |
| `HierarchyReplicaTest` (25) | Cas du § 6.2 dans les deux ordres d'arrivée (attente, adjoints, promotion de l'adjoint, retrait, passage de main, dissolution compris), rattrapage, stockage, deux opérations sous un numéro, convergence sur 50 graines |
| `OpAuthTest` (6) | § 8.1 : signature obligatoire, refus des opérations non ou mal signées, forme unique des clés et des signatures |
| `HierarchyKeysTest` (14) | § 8.3 et 8.4 : membre en attente fermé, arrivée, sortie (quatre façons, téléphone informé ou non), clé par canal, sous-groupes, retour d'un absent, deux émetteurs à la fois, lots refusés, ordre des émetteurs, dissolution |
| `ShareSealTest` (8) | § 8.5 : sceller et ouvrir les six sortes dans les cinq voies, partages forgés ou hors droits refusés, téléphone en retard, octets abîmés |
| `SharedObjectsTest` (6) | § 8.7 (S6) : l'arrivant, le revenant, l'objet d'un membre parti, enregistrements forgés, format, borne |
| `NamesTest` (4) | § 8.8 : empreintes, cartes, carnet, format ; le nom de l'ensemble, seul nom donné à qui attend (T14) |
| `PositionCostTest` (2) | § 8.5 (S5) : taille d'une position signée, refus d'une position abîmée, temps mesurés sur la JVM |
| `HierarchyWireTest` (7) | § 7 : allers-retours, refus (dont les versions 1 à 3), octets abîmés, pour tous les genres de blocs |

Commande : `./gradlew :core:test --tests 'fr.toolmap.core.hierarchy.*'` (≈ 45 s).

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
   Vous avez répondu : un membre alerte son groupe, un chef de groupe alerte tous les chefs, le chef de l'opération alerte tout le monde. Un administrateur qui n'est pas chef a été traité comme un membre : il alerte son groupe. S'il n'a pas de groupe, son alerte ne part aujourd'hui à personne.
   a. À tous les chefs (recommandé) : il lit déjà leurs alertes, et ce sont eux qui peuvent agir.
   b. À toute l'opération.
   c. À personne (aujourd'hui) : un administrateur doit être placé dans un groupe pour pouvoir alerter.

2. **Quand un chef de groupe déclenche une alerte, son propre groupe la reçoit-il aussi ?**
   Vous avez répondu : un chef de groupe alerte tous les chefs. Ses équipiers sont souvent les plus proches de lui.
   a. Oui : tous les chefs et son groupe (recommandé).
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

## 15. Remontée du 2026-11-14 : moteur aux réponses du troisième tour (T1 à T17)

Pour la session principale (session 110, 2026-11-14, branche `claude/laughing-dijkstra-yz3opa`, partie de `conception/groupes-hierarchiques` dbd5829, 0.4.244). Les §§ 1 à 9 et 11 décrivent le moteur tel qu'il est après ce passage ; les §§ 10 à 10 quinquies et 12 à 14 ne sont pas modifiés, et cette session n'a pas écrit le § 10 sexies (réponses de l'utilisateur, session locale). Pas de nouvel ADR : aucune décision d'architecture ne change (l'ADR-127 couvre les canaux et les noms cachés ; le format passe en version 4 pour une donnée de plus dans les règles).

### Ce qui a changé

- **Alerte d'un chef de groupe et de son adjoint** (T4 B) : à tous les chefs, **plus à leur propre groupe**.
- **Alerte d'un administrateur ou du créateur qui n'est pas chef** (T13) : à tous les chefs, qu'il ait un groupe ou non, et pas à son groupe. Deux cases de la table (une par rôle) ; sans elles il alerte comme un membre.
- **Une seule destination d'alerte par personne** (§ 4.2) : l'alerte est une sorte « à destination unique » (`HierarchyRules.singleDestination`, donnée de l'ensemble) ; parmi les rôles cumulés de l'auteur, le premier dont la case n'est pas vide décide seul (`Role.DESTINATION_ORDER` : leader d'ensemble, son adjoint, créateur, administrateur, leader de groupe, son adjoint, membre). `HierarchyState.alertChannel(auteur)` donne le canal, `destinationRole` le rôle qui a décidé ; une alerte scellée ailleurs est refusée par le téléphone de l'auteur et par tous les autres.
- **La voie des chefs a toujours un lecteur** (le créateur, qui y alerte) : elle n'attend plus qu'un groupe ait un chef.
- **Nom de l'opération pour celui qui attend** (T14) : l'invitation porte la carte du nom de l'ensemble ; `NameBook.ensembleCard` la donne à celui qui invite, `learnEnsembleName` ne la garde chez l'invité que si elle ouvre le nom signé par la première opération, `HierarchyState.namesFor` dit quels noms un membre a le droit de connaître, `NameBook.prune(état, soi)` fait oublier les autres à un téléphone remis en attente. Ce que cela donne à quiconque détient l'invitation : § 8.8 et § 8.6.
- **Format binaire version 4** (§ 7) : un octet de plus dans les règles (les sortes à destination unique) ; les versions 1 à 3, jamais déployées, sont refusées.
- **Rien à changer dans le moteur** pour T15 (c'est l'écran qui n'offre pas l'interrupteur : § 9), T11 A (règle d'affichage : § 4.4), T12, T6, T2 et T5 (c'est ce que faisait déjà le moteur : tests ci-dessous).
- Rien hors du paquet `hierarchy`, de ses tests et de `docs/` ; un fichier de tests nouveau, `HierarchyAlertTest.kt`.

### Les 27 lectures du § 14

« Confirmée » : par une réponse de l'utilisateur, nommée. « Toujours à confirmer » : aucune des réponses transmises à cette session ne la cite ; la session principale sait ce que T16 et T17 ont montré.

| Lecture | État |
|---|---|
| 1. L'adjoint d'un chef de groupe remonte comme son chef | Confirmée (T16) |
| 2. Ceux qui diffusent à toute l'opération envoient à un groupe précis | Confirmée (T2) |
| 3. L'alerte d'un chef de groupe est lue par les administrateurs et le créateur | Confirmée (T5) |
| 4. Un administrateur ou le créateur sans poste de chef alerte comme un membre | **Changée** (T13 : à tous les chefs, avec ou sans groupe, pas à son groupe) |
| 5. Le fil du milieu : un fil par groupe | Confirmée (T6) |
| 6. Le successeur devient créateur sans avoir à accepter | Toujours à confirmer |
| 7. Le créateur compte parmi « les administrateurs » | Confirmée pour la voie des chefs et l'alerte (T5, T13) ; pour les remontées, la diffusion et les voies d'attente : toujours à confirmer |
| 8. Les positions vont à toute l'opération | Confirmée (T16) |
| 9. L'envoi à un groupe précis : croquis et waypoints, pas les messages | Confirmée (T16) |
| 10. Le groupe lit ce qui descend vers lui et n'y répond pas là | Toujours à confirmer |
| 11. Dans le fil du milieu, le commandement répond par des messages seulement | Toujours à confirmer |
| 12. Un chef de groupe a deux destinations possibles pour une alerte | **Changée** (T4 B : une seule, tous les chefs) |
| 13. « Le chef part » comprend le changement de groupe et la remise en attente | Confirmée (T16) |
| 14. Le chef de l'opération sorti de son groupe reste chef de l'opération | Toujours à confirmer |
| 15. Sans successeur, c'est l'écran qui écrit « dissoudre » ; le moteur refuse le simple départ du créateur | Toujours à confirmer (T8 A, la glissière dans la fenêtre aux deux sorties, va dans ce sens) |
| 16. L'ancien créateur redevient un membre comme un autre | Confirmée (T16) |
| 17. « Aucun nom » pour celui qui attend, pas même celui de l'opération | **Changée** (T14 : le nom de l'opération, et aucun autre) |
| 18. La position de celui qui attend part aux administrateurs et au créateur ; il leur envoie le nom qu'il a choisi | Confirmée pour la position (T15 : ils le voient toujours) ; l'envoi du nom choisi : toujours à confirmer |
| 19. Q17 est une règle d'affichage | Confirmée (T11 A) |
| 20. Initiales : première lettre des deux premiers mots, ou deux premières lettres | Toujours à confirmer |
| 21. Un croquis ou un waypoint reste affiché tant qu'on ne le retire pas | Confirmée (T12) |
| 22. Le retirent son auteur et ceux qui peuvent partager au même endroit | Confirmée (T12) |
| 23. « Ce que sa place lui laisse voir » : les canaux qu'il lit aujourd'hui | Toujours à confirmer |
| 24. Qui renvoie les objets et les noms à l'arrivant | Toujours à confirmer (choix technique) |
| 25. Toutes les sortes sont signées, alertes et noms compris | Toujours à confirmer (choix technique) |
| 26. Une voie où personne n'écrirait n'a pas de clé | **Changée** pour la voie des chefs (elle a toujours le créateur) ; inchangée pour les voies montantes et descendantes |
| 27. Sous-groupes : ce que remonte le chef d'un sous-groupe va au groupe du dessus | Toujours à confirmer (pas de sous-groupes au départ) |

Acceptés aussi par T16, hors de ces 27 lignes : le membre remis en attente garde ce qu'il avait reçu ; le téléphone perdu du créateur reste lecteur ; l'alerte du chef de l'opération ne va pas à ceux qui attendent (§ 8.6, § 4.2 ; testés).

### Ce qui est vérifié, et comment

- `./gradlew :core:test --tests 'fr.toolmap.core.hierarchy.*'` : **140 tests, 0 échec** (121 avant ce passage ; 18 pour l'alerte, 1 pour le nom de l'opération). Aucun test retiré ni désactivé. Huit tests d'avant échouaient après le changement du moteur, tous pour une règle changée : remis aux nouvelles règles (l'alerte de Q6 et de Q16, les canaux lus par le créateur, trois listes de canaux où la voie des chefs apparaît, la version et la taille du format) ; le test « Q2 » est étendu à T14. Les 19 tests nouveaux sont passés à leur premier passage.
- **Alerte** (`HierarchyAlertTest`) : un test par combinaison de rôles (liste au § 11) ; chaque test vérifie le canal, que tout autre canal de l'ensemble est refusé, le rôle qui décide et la liste des lecteurs. Par les clés : la trame est remise aux neuf téléphones, la liste de ceux qui l'ouvrent est comparée à la liste attendue. Alerte « envoyée ailleurs » : une trame bien faite (bonne clé, signée de la clé de son auteur) scellée par un téléphone modifié dans huit endroits interdits ; personne ne l'ouvre, chaque lecteur répond `NOT_ALLOWED` ; la même trame dans la bonne destination s'ouvre. Deux changements simultanés remis à trois téléphones dans trois ordres : même destination pour chaque membre.
- **Nom de l'opération** : le test cherche chacun des dix noms dans les octets de ce que détient le téléphone en attente (opérations, état, carnet) : seul le nom de l'opération s'y trouve, et seulement dans le carnet ; la carte d'un nom de groupe, de personne, ou d'un autre nom d'opération est refusée.
- **Ce que faisait déjà le moteur**, vérifié par les tests existants, verts : T2 (« Q15 », les quatre auteurs dont l'administrateur), T5 (« Q6 T4 T13 », lecteurs de la voie des chefs, par les clés), T6 (« S3 »), T12 (`SharedObjectsTest` : l'objet d'un membre parti, renvoyé puis retiré par un autre), T15 (« Q10 » : les administrateurs voient celui qui attend).
- Commandes complètes, build et run « CI » : journal, session 110.

### Ce qui n'est pas fait

- **Rien n'est branché**, comme avant (§ 9) : l'invitation elle-même (lien, QR, clé de l'invitation, et maintenant la carte du nom) n'est pas écrite ; le moteur donne la carte et la vérifie.
- **L'alerte** : ce qu'elle contient, comment on y répond, comment elle finit, et son renvoi quand le rôle de l'auteur change pendant qu'elle court (§ 8.6, § 9) ne sont pas écrits.
- **Deux textes d'écran à montrer en maquette** (pour la session principale, pas des questions) : à qui part l'alerte d'un créateur seul (à personne d'autre que lui) ; sur l'écran de création, que le nom de l'opération est lu par quiconque reçoit l'invitation.
- Tout ce que le § 14 listait comme non fait le reste (mesures sur un téléphone, parade aux deux opérations sous un même numéro, revue par un spécialiste, renommer).

### Limites connues

Celles des §§ 6.4, 8.6 et 14, inchangées ; deux de plus, écrites au § 8.6 : le nom de l'opération est lu par quiconque détient l'invitation, et une alerte partie juste avant un changement de rôle de son auteur est refusée par les téléphones qui apprennent le changement d'abord.

### Questions pour l'utilisateur

Aucune question nouvelle.

## 16. Remontée du 2026-11-14 : branchement, étape 1 — clés du téléphone et stockage (P39.6)

Pour la session principale (session 111, branche `claude/optimistic-maxwell-sw423w`, partie de `conception/groupes-hierarchiques` dbd5829). Décision d'architecture : ADR-128. Si une autre session a pris ce numéro de paragraphe ou d'ADR, la session qui fusionne renumérote. Les §§ 10 à 10 sexies et 12 à 14 ne sont pas modifiés ; le § 9 dit ce qui est fait et ce qui reste.

### Ce qui est fait

- **Clés du téléphone** : chaque opération que rejoint ou crée ce téléphone a sa propre identité (deux paires de clés). La clé de signature est faite dans le Keystore et n'en sort jamais. La clé d'accord aussi à partir d'Android 12 (API 31) ; avant, elle est tirée par l'application et gardée dans un fichier scellé par une clé du Keystore.
- **Stockage d'une opération** : un dossier `files/hierarchy/operations/<identifiant>/` ; tout ce que le téléphone tient de l'opération (journal des opérations, clés de contenu, lots de clés, noms, waypoints et croquis) y est scellé, et se relit à l'identique après un redémarrage ; l'état se recalcule à partir des opérations.
- **Effacement** : tout est effacé (clés du Keystore d'abord, puis fichiers) quand le téléphone quitte l'opération, en est retiré, ou qu'elle est dissoute ; ce qui tient à un canal qu'il ne lit plus (clés, objets, lots qui redonneraient la clé) est oublié à l'enregistrement suivant.
- **Données abîmées** : un fichier abîmé, coupé, absent ou venu d'une autre opération est refusé, signalé (`Problems.expected`), lu vide et réécrit au prochain enregistrement ; jamais de plantage. Une identité illisible est refusée proprement (`open` rend `null`).
- **Façade** pour l'étape suivante : `OperationStorage` (`ids`, `create`, `open`, `wipe`, `wipeAll`) et `StoredOperation` (`join`, `load`, `save`, `wipe`), fournis par Hilt (`HierarchyModule`) et **appelés par personne**.
- **Contrôle du vrai Keystore** prêt pour un émulateur : `tools/emulator/hierarchy-keys.sh`, appelé aussi par `smoke.sh` (donc par la CI, sur son émulateur API 34).

Fichiers : `data/src/main/kotlin/fr/toolmap/data/hierarchy/` (`KeyVault`, `AndroidKeyVault`, `OperationStorage`, `StoredOperation`, `SealedFile`, `HierarchyKeysCheck`) ; dans `:core`, un fichier nouveau `hierarchy/HierarchySnapshot.kt` (les octets de ce qu'on garde) et dix membres passés de `private` à `internal` : huit dans `HierarchyKeys.kt` (`ContentKey.secret`, `KeyRing.held`, `checked`, `pending`, `keep`, `hold`, `BatchRef`, `MAX_PENDING`), `Names.kt` (`NameBook.cards`) et `SharedObjects.kt` (`SharedObjects.held`), sans autre changement ni aucune règle changée ; dans `:app`, `di/HierarchyModule.kt`, trois lignes dans `MainActivity.dump` (debug seulement) et une ligne d'exclusion dans chacune des deux règles de sauvegarde (`res/xml/`) ; `tools/emulator/hierarchy-keys.sh` et quatre lignes de `smoke.sh`.

### Les choix (une ligne chacun)

1. Une identité par opération, pas une par téléphone : deux opérations ne se relient pas, quitter l'une n'efface rien des autres.
2. Clé d'accord dans le Keystore dès l'API 31, sinon fichier scellé par le Keystore ; la manière choisie à la création est gardée même après une mise à jour d'Android.
3. Aucune clé ne demande le déverrouillage du téléphone (le partage tourne écran éteint).
4. Fichiers scellés (AES-256-GCM) plutôt que Room : rien à interroger, et c'est la convention du partage actuel (`ShareStore`).
5. Une clé de stockage par opération, scellée par le Keystore : un seul appel au matériel par processus, au lieu d'un par lecture et par écriture.
6. Les opérations sont relues dans l'ordre où tous les téléphones les appliquent (`Op.ORDER`) : même résultat, environ trois fois plus vite qu'en les rendant par auteur ; chaque signature est revérifiée.
7. L'oubli d'un canal se fait à l'enregistrement, et seulement quand le téléphone est membre (avant d'être admis, ou pendant un rattrapage après un fichier abîmé, il ne lit aucun canal et oublierait tout) ; les noms ne sont pas oubliés (ils ne tiennent à aucun canal).
8. L'effacement en fin d'opération est automatique, à l'enregistrement ou au chargement.
9. Une signature refusée par le Keystore lève `KeyVaultException` (signalée) : le moteur n'a pas d'autre moyen de dire « rien n'est signé » ; un accord refusé rend « pas de clé ».
10. `hierarchy/` exclu des deux règles de sauvegarde d'Android : sans cela, le transfert d'appareil à appareil (qui ne dépend pas de `allowBackup="false"` sur Android 12 et plus) l'aurait copié ; inutilisable ailleurs, mais copié.
11. Le contrôle sur émulateur passe par le point d'entrée existant (`dumpsys`, debug seulement) plutôt que par des tests instrumentés que le dépôt n'a pas.

### Ce qui est vérifié, et comment

- `./gradlew :core:test --tests 'fr.toolmap.core.hierarchy.HierarchySnapshotTest' :data:testDebugUnitTest --tests 'fr.toolmap.data.hierarchy.*'` : **25 tests, 0 échec** (`HierarchySnapshotTest` 3, `StoredOperationTest` 16, `HierarchyKeysCheckTest` 4, `HierarchyBackupTest` 2), sur la JVM, avec un coffre logiciel (`SoftwareKeyVault`, sources de test) qui fait ce que fait le Keystore.
  - Aller-retour d'une opération réaliste écrite par le moteur (créateur, un administrateur, quatre membres, deux groupes, chef de groupe, adjoint, chef de l'opération, clés de tous les canaux lus, un lot gardé pour un autre, un lot en attente, dix noms dont un arrivé avant son opération, trois waypoints et croquis dont un retiré) : enregistrée, puis relue par un stockage neuf sur le même dossier et le même coffre (« redémarrage ») : même état (octet pour octet), même résumé, mêmes refus, mêmes opérations, mêmes clés, lots, noms et objets (octet pour octet) ; un partage scellé après le redémarrage s'ouvre chez un autre membre ; ce qu'il écrit ensuite est signé par la même clé. Les deux manières de garder la clé d'accord ; un arrivant avant puis après son admission.
  - Toute signature passe par le coffre ; aucun fichier ne contient un nom ni une clé de contenu en clair.
  - Effacement : départ (`wipe`), retrait par le créateur, dissolution sur deux téléphones ; plus de dossier, plus de clé dans le coffre, et le stockage du créateur intact. Par canal : un chef de groupe remis en attente ne garde, après relecture, ni la clé de son groupe, ni ses objets, ni les lots qui la redonneraient ; il garde les noms.
  - Données abîmées : pour chacune des quatre parties, octet changé, fichier coupé, absent, bruit, vide (20 cas) : partie refusée, signalée, le reste lu ; après rattrapage, l'enregistrement suivant la réécrit et la relecture est propre. Partie d'une autre opération ou d'une autre partie : refusée. Clé de stockage abîmée : tout refusé, rien ne plante, tout repart. En-tête abîmé ou absent, fichier de la clé d'accord abîmé, clé perdue par le coffre : identité refusée proprement, effacée sur demande. Coffre en panne : pas d'identité, rien laissé derrière, et tout revient quand le coffre revient. Tous les signalements sont des échecs prévus, aucun n'est un bogue (vérifié dans chaque test).
  - Deux opérations côte à côte : identités différentes, aucun mélange (enregistrer l'une dans l'autre est refusé), effacer l'une laisse l'autre entière. `wipeAll` n'efface pas les clés d'un autre stockage.
  - Sauvegardes : l'archive de l'application, toutes rubriques cochées, ne contient aucun fichier ni aucun octet du stockage ; `BackupPaths.areaOf` refuse chacun de ses chemins ; les règles de sauvegarde d'Android excluent `hierarchy/` du transfert et de la sauvegarde d'Android 11 et moins, et la sauvegarde en ligne ne prend aucun fichier.
  - Le contrôle de l'émulateur lui-même, joué sur la JVM : les deux phases passent, pour l'API 34 (deux manières) et l'API 28 (fichier scellé seulement) ; un coffre en panne donne une ligne `FAIL` sans plantage ; il n'efface jamais une vraie opération.
- Temps sur la JVM de la machine de build (pas un téléphone ; `data/build/hierarchy-store-cost.txt`) pour une opération de 617 opérations : revérifier les signatures 78 ms ; relire dans l'ordre des horloges 94 ms (contre 316 ms par auteur) ; enregistrer après un changement 2 ms ; relire et tout comparer 124 ms.
- Application inchangée : rien n'appelle `OperationStorage` (seul `HierarchyModule` le nomme, et personne ne le demande) ; le contrôle ne répond qu'au `dumpsys` d'un APK de debug ; ni le manifeste, ni `strings.xml`, ni une ressource visible, ni le code du partage « Entre amis » ne changent (`git diff --stat`, journal). Le build complet est vert (journal, session 111).

### Ce qui n'a pas pu être vérifié en session cloud (à faire par la session locale)

Le vrai Keystore ne tourne pas sur la JVM. Sur l'émulateur du poste, APK de debug de ce commit installé :

```bash
bash tools/emulator/smoke.sh app/build/outputs/apk/debug emulator-check
```

ou, l'application déjà installée et lancée, le contrôle seul :

```bash
bash tools/emulator/hierarchy-keys.sh emulator-check
```

À y lire (`emulator-check/hierarchy-keys.txt`, et la dernière ligne « OK: keys of the « Opération » mode (API …) ») :

- `phase=create api=… agreement-in-keystore=true` (API 31 et plus) puis, pour `agreement=keystore` **et** `agreement=file` : `identity made in … ms`, `sign median=…us verify median=…us agree median=…us`, puis `PASS phase=create` ;
- après le redémarrage du processus : `reloaded in a new process: same member, state, keys, objects` pour les deux, les temps de nouveau, `wiped: no key left in the Keystore, no folder left`, `PASS phase=reload` ;
- aucune ligne `FAIL`, aucun plantage ;
- **branche « API < 31 »** : sur l'émulateur API 34 elle est jouée en forçant le fichier scellé (`agreement=file`) ; sur un émulateur API 26 à 30, s'il y en a un, la même commande doit donner `agreement-in-keystore=false` et la seule manière `agreement=file`.
- Noter au journal les temps d'une signature, d'une vérification et d'un accord, et le numéro d'API.

La CI joue aussi ce contrôle sur son émulateur (API 34) dans le contrôle de l'APK de debug : les lignes sont dans le journal du job « CI » (« Keys of the « Opération » mode on this emulator »).

**Résultat sur l'émulateur de la CI** (run « CI » n° 198, 43634007565, commit 3d7c7c9, Android 14, API 34, émulateur x86_64 à deux cœurs, Keystore logiciel de l'émulateur et non le TEE d'un téléphone) : **« OK: keys of the « Opération » mode (API 34) »**, les deux phases `PASS`, relecture dans un nouveau processus identique pour les deux manières, puis « wiped: no key left in the Keystore, no folder left ». Médianes (30 signatures, 10 accords) :

| Manière | Création de l'identité | Signature (Keystore) | Vérification | Accord |
|---|---|---|---|---|
| clé d'accord dans le Keystore | 25 ms | 630 µs, puis 584 µs après redémarrage | 202 µs, puis 248 µs | 1 073 µs, puis 726 µs |
| clé d'accord en fichier scellé (manière de l'API 26 à 30) | 12 ms | 947 µs, puis 832 µs | 182 µs, puis 183 µs | 157 µs, puis 153 µs |

Une signature par le Keystore coûte donc moins d'une milliseconde ici, une vérification environ 0,2 ms ; l'accord par le Keystore est environ six fois plus lent qu'en mémoire. L'APK de release ne répond pas au contrôle (« not offered by this APK »), comme prévu. Reste pour la session locale : le même contrôle sur l'émulateur du poste (Android 37) et, si possible, sur une API 26 à 30 ; puis un vrai téléphone (TEE ou StrongBox) avant tout usage réel.

### Ce qui reste pour l'étape suivante (transport)

- Ouvrir ou créer le stockage quand le partage démarre en mode « Opération » ; `save` après chaque changement (opération reçue ou écrite, lot accepté ou émis, carte apprise, objet accepté), hors du fil principal ; `wipe` quand l'utilisateur quitte ; réagir à `SaveResult.Ended` et `LoadResult.Ended` (l'écran dira « opération dissoute » ou « vous n'en faites plus partie »).
- Attraper `KeyVaultException` autour de ce qui fait signer (`issue`, `KeyRing.issue`, `ShareSeal.seal`, `SharedRecords.write`).
- Après un chargement : `KeyRing.retry(replica)` pour les lots en attente, puis le rattrapage par le réseau (§ 6.3) pour les parties refusées.
- Mesurer sur un vrai téléphone (signatures par position, relecture d'un long journal).

### Limites connues

- Relire revérifie une signature par opération : à mesurer sur un téléphone pour un journal de plusieurs milliers d'opérations (le moteur n'a pas de relecture « de confiance » ; l'ajouter changerait une règle du moteur).
- Un enregistrement réécrit une partie entière (quelques Mo d'opérations au pire, 20 000 opérations) ; les octets anciens peuvent rester sur la mémoire flash, inutiles une fois les clés du Keystore effacées.
- L'effacement en fin d'opération est sans retour (question 1).
- Un téléphone dont le Keystore perd ses clés (réinitialisation du verrouillage sur certains appareils) perd l'opération : il la rejoint comme un membre neuf.
- Le contrôle sur émulateur ne vaut pas un téléphone (TEE ou StrongBox réels).

### Questions pour l'utilisateur

1. **Quand votre téléphone apprend que vous avez été retiré d'une opération, ou qu'elle est dissoute, faut-il tout effacer aussitôt ?**
   a. Oui, aussitôt (recommandé, ce qui est fait) : plus rien de l'opération ne reste sur le téléphone. Dans un cas rare (deux décisions prises en même temps sur deux téléphones), le retrait peut être annulé ensuite par les autres : le téléphone devra alors rejoindre l'opération comme un nouveau membre.
   b. Garder l'opération fermée (illisible) quelques minutes avant d'effacer, le temps que les autres téléphones confirment.

## 17. Remontée du 2026-11-15 : branchement, étape 2 — transport et contrôleur (P39.7)

Pour la session principale et pour la session qui codera les écrans (session 113, branche `claude/funny-hypatia-cnz6s8`, partie de `conception/groupes-hierarchiques` 56711dc, 0.4.247). Décision d'architecture : ADR-129. Rien de visible ne change : aucun écran, aucun texte, aucune ressource ; le contrôleur est fourni par Hilt et demandé par personne. Le partage « Entre amis » n'est pas touché (aucun fichier de `:p2p`, de `core/…/p2p/` ni de `app/…/share/`).

### Ce qui est construit

- **`:core`, paquet `fr.toolmap.core.hierarchy`** :
  - `OperationWire.kt` : l'invitation (`OperationInvite`, `OperationInviteCodec` : lien `toolmap://operation?d=…` ou texte de QR en Base45, version 4 du code ToolMap, valable 7 jours), l'enveloppe de toutes les trames (`OperationFrames`), le résumé (`OperationSummary`), la demande d'entrée signée (`JoinRequest`, `JoinRequests`), la clé d'invitation par époques (`InviteAnnouncement`, `InviteGrant`, `InviteKeys`), les charges utiles (`OperationPayloads`, `OperationText`), ce qu'on garde à côté du moteur (`OperationLinkState`, `EpochKey`).
  - `OperationNode.kt` : l'automate d'échange (`OperationNode`, `OperationListener`, `ShareOutcome`, `PendingJoin`, `OperationEnding`).
- **`:data`, paquet `fr.toolmap.data.hierarchy`** : `OperationController` (la façade des écrans, ci-dessous), `HierarchyOperationCheck` (contrôle par `dumpsys`) ; `StoredOperation.saveLink` / `loadLink` (un cinquième fichier scellé, `link.sealed`).
- **`:app`** : `di/HierarchyModule.kt` fournit `OperationController` (serveur des nouvelles opérations : le serveur intégré, `BuildConfig.FIREBASE_*`) ; quatre lignes dans `MainActivity.dump` (`… hierarchy-operation`, sous `BuildConfig.DEV_TOOLS`).
- **Outils** : `tools/emulator/hierarchy-operation.sh`, appelé par `smoke.sh` après `hierarchy-keys.sh`.

### Le transport, pas à pas

**Le chemin.** Une opération a sa **salle** sur le serveur de coordination d'« Entre amis » (16 octets tirés au hasard par le créateur, dans l'invitation, la même toute la vie de l'opération). Le serveur n'est pas modifié : il range une trame dont le premier octet vaut `4` (le code d'une position chez « Entre amis ») dans la case de position du membre (300 octets, la dernière seulement), et toute autre trame en trame de contrôle (4 096 octets ici, gardée 120 s, écartée à l'arrivée après 150 s). Le battement ne porte **aucun nom** : le serveur voit l'identifiant du membre (empreinte de sa clé de signature, propre à l'opération) et des octets chiffrés. Pas de liaison directe WebRTC pour l'instant : sans serveur joignable, l'opération ne parle à personne.

**L'enveloppe.** `marqueur (1) | genre (1) | époque (4) | émetteur (8) | nonce (12) | AES-256-GCM(corps)` ; la clé est `HKDF-SHA256(clé d'invitation de l'époque, "toolmap-operation-frame-v1" | opération | époque)`, l'en-tête et l'identifiant de l'opération sont authentifiés. Marqueur `0xC7`, ou `4` pour une position. Genres :

| Genre | Corps |
|---|---|
| 1 opérations | `HierarchyWire.encodeOps`, coupé pour tenir dans une trame |
| 2 résumé | `longueur (2) | résumé du moteur | empreinte des lots de clés (8) | noms manquants (2) | n (2) × (voie (1), portée (8), empreinte des objets (8)) | destinataire (8), seulement s'il n'est pas 0` (destinataire depuis P39.17, § 26) |
| 3 lot de clés | `HierarchyWire.encodeKeyBatch` |
| 4 partage | la trame de `ShareSeal` |
| 5 demande d'entrée | `opération (8) | clés publiques (130) | empreinte du nom (32) | heure (4) | clé éphémère (65) | n (1) × (destinataire (8), longueur (1), carte scellée) | signature (64)` |
| 6 clé d'invitation | `annonce (opération, époque, identifiant de la clé, annonceur, signature) | donneur (8) | clé éphémère (65) | n (2) × (membre (8), clé enveloppée (48)) | signature du donneur (64)` |

**Liaison ouverte** : chacun envoie son résumé ; un téléphone qui n'est pas encore admis envoie aussi sa demande d'entrée. **Réponse à un résumé** : les opérations qui manquent ; si l'autre est en retard, on lui redemande son résumé ; puis, une fois par résumé différent (un même résumé entendu deux fois en 5 s n'a qu'une réponse) : les lots de clés du moment si leurs empreintes diffèrent, les objets de chaque canal que les deux lisent et dont l'empreinte diffère (si l'on a le droit d'y partager), les noms si l'autre en manque ou est en retard sur les opérations (cartes des membres placés et des groupes, jamais celles de qui attend). Un résumé part aussi toutes les 60 s.

**Après chaque changement de l'état** : `KeyRing.retry` ; fin de l'opération pour ce téléphone (dissoute, ou il en a été membre et ne l'est plus) : tout est oublié, l'écouteur le dit, le contrôleur efface le stockage ; sinon oubli de ce qu'on ne lit plus (`prune` des clés, objets et noms) ; vérification du nom de l'opération venu de l'invitation dès la première opération ; clés des **unités** qui manquent : faites par le premier de `keyIssuers` présent, les suivants attendent 1,5 s par rang et ne font rien si la clé est arrivée ; clés des **voies** (montante, descendante, des chefs, d'attente) : faites aussitôt par qui veut y partager ; **arrivées** (`arrivals`) : renvoi des objets du canal par les téléphones de `resenders` (même attente par rang, annulé si un renvoi d'un autre est vu entre-temps), et des noms pour une arrivée dans l'opération ; **départ d'un membre** : le créateur, puis les administrateurs par rang, renouvellent la clé d'invitation ; **alerte en cours** dont la destination a changé : renvoyée dans la nouvelle.

**Entrée.** L'invité crée une identité (`OperationStorage.create`), garde l'invitation (`OperationNode.joining`), rejoint la salle, reçoit le journal (la clé d'invitation l'ouvre), vérifie le nom de l'opération (`learnEnsembleName` ; refusé : signalé, plus affiché), envoie sa demande d'entrée toutes les 20 s, la carte de son nom enveloppée pour le créateur et les administrateurs. L'administrateur la voit dans `joinRequests` (avec le nom) et l'admet (`admit`). L'admis fait la clé de sa voie d'attente, y envoie la carte de son nom et sa position. Placé, il reçoit la clé de l'opération, celle de son groupe, les noms et les objets encore affichés.

**Renouvellement de l'invitation.** Clé neuve, époque + 1, annoncée (signée) par le créateur ou un administrateur, enveloppée pour chaque membre admis (en attente compris), envoyée sous l'ancienne clé. Ensuite, tout s'envoie sous la nouvelle. Un membre resté sur l'ancienne (absent au moment du renouvellement) parle sous elle à son retour : n'importe quel membre qui détient l'annonce lui redonne la nouvelle (enveloppée pour lui seul). Un membre retiré ouvre l'enveloppe, pas sa copie : il ne lit plus rien, ni la forme de la hiérarchie. Une invitation donnée avant ne fonctionne plus (sa demande d'entrée est écartée) : l'écran doit le dire à qui invite. Fait de lui-même après chaque départ, ou sur demande (`renewInvitation`).

**Rejeux et trames falsifiées.** Une enveloppe changée d'un octet ne s'ouvre pas ; une enveloppe d'une autre opération ou d'une autre clé d'invitation non plus. Une opération reçue deux fois est reconnue (`DUPLICATE`), un lot aussi ; une position plus ancienne que la dernière de son auteur est écartée ; un message ou une alerte déjà vus (même auteur, même identifiant) ou de plus de 6 h sont écartés ; une demande d'entrée dont la signature n'est pas celle de la clé qu'elle montre, ou dont l'émetteur n'est pas ce membre, n'est pas montrée. Tout refus d'une trame venue d'un autre téléphone est compté (`OperationNode.dropped`) et, s'il n'est pas normal (une trame d'un canal qu'on ne lit pas l'est), signalé (`Problems.expected`).

### L'interface du contrôleur (pour les écrans)

`fr.toolmap.data.hierarchy.OperationController`, un par processus (`@Inject`), **une opération ouverte à la fois**. Toutes les méthodes `suspend` tournent sur le fil du contrôleur, dans l'ordre ; on peut les appeler depuis un `viewModelScope`. Ce qu'il faut afficher est dans **`view: StateFlow<OperationView>`**, une valeur immuable refaite à chaque changement.

**Démarrer**

| Appel | Rend | Effet |
|---|---|---|
| `ids(): List<String>` | les opérations gardées sur ce téléphone | — |
| `create(operationName: String, myName: String)` | `StartResult` | Crée l'opération, ce téléphone créateur ; elle est gardée, sa salle ouverte |
| `preview(invitation: String): InvitationPreview?` | nom de l'opération, identifiant, `staleness` (`EXPIRED`, `FROM_THE_FUTURE` ou `null`) | Lit une invitation sans rejoindre (« Rejoindre l'opération “…” ? ») |
| `join(invitation: String, myName: String)` | `StartResult` | Demande à entrer ; `view.phase` passe à `ASKING` |
| `open(id: String)` | `StartResult` | Rouvre une opération gardée (au démarrage de l'application) |
| `close()` | — | Quitte la salle, garde l'opération |

`StartResult` : `STARTED`, `BAD_NAME` (vide, caractère de contrôle, plus de 64 octets), `BAD_INVITATION`, `EXPIRED`, `STORAGE_FAILED`, `NOT_FOUND`, `ENDED` (l'opération gardée était finie : effacée à la lecture).

**Inviter, admettre, placer** (le droit se lit dans `check` ou dans `view`)

| Appel | Rend |
|---|---|
| `invitationLink(): String?` / `invitationQr(): String?` | le lien ou le texte du QR ; `null` si ce téléphone n'invite pas (`view.mayInvite`) ou n'a pas de serveur |
| `renewInvitation(): Boolean` | nouvelle clé d'invitation (les invitations données avant ne marchent plus) |
| `admit(member: Long)` / `decline(member: Long)` | `Rejection?` / `Boolean` |
| `createGroup(name: String, parent: Long = HierarchyState.ROOT)` | `Pair<Long?, Rejection?>` : l'identifiant du groupe, ou le refus |
| `renameGroup(group, name)`, `deleteGroup(group)` | `Rejection?` |
| `place(member, group)`, `unassign(member)` | `Rejection?` |
| `setPost(unit, post: Post, member)`, `clearPost(unit, post)` | `Rejection?` (`unit` : un groupe, ou `HierarchyState.ROOT` pour l'opération ; `Post.LEADER` chef, `Post.DEPUTY` adjoint) |
| `addAdmin(member)`, `removeAdmin(member)`, `removeMember(member)` | `Rejection?` |
| `leave()` | `Rejection?` ; refusé au créateur (`IS_CREATOR`) |
| `handOverAndLeave(successor: Long)` | `Rejection?` : passe la main à un administrateur, puis part |
| `dissolve()` | `Rejection?` : fin de l'opération pour tous (créateur seulement) |
| `change(change: Change)` | `Rejection?` : n'importe quel changement du moteur |
| `check(change: Change): Rejection?` | sans `suspend`, sans rien écrire : pour griser un bouton |

`null` veut dire « écrit » : le changement part aux autres. Il peut encore perdre ensuite contre un changement écrit en même temps ailleurs : il apparaît alors dans `view.refused`. Un bouton ne suppose jamais qu'un changement écrit est définitif.

**Partager** (les destinations offertes sont dans `view.targets`, et aucune autre)

| Appel | Rend |
|---|---|
| `sendPosition(fix: PeerFix)` | `ShareOutcome` ; à appeler à chaque position (le service le fera ; une position sans clé ou sans liaison est sautée) |
| `sendMessage(channel: Channel, text: String)` | `ShareOutcome` ; texte de 1 024 octets au plus ; apparaît aussi dans le fil de ce téléphone |
| `sendAlert(text: String, fix: PeerFix?)` | `ShareOutcome` ; part à `view.targets.alert`, sans choix |
| `liftAlert()` | `ShareOutcome` ; lève la dernière alerte de ce téléphone |
| `putObject(kind: ShareKind, channel: Channel, body: ByteArray, objectId: Long? = null)` | `Pair<ShareOutcome, Long>` ; `kind` : `SKETCH` ou `WAYPOINT` ; sans `objectId`, un objet neuf (son identifiant revient) ; corps de 3 700 octets environ au plus (`OperationNode.MAX_OBJECT_BODY`) |
| `removeObject(ref: ObjectRef)` | `ShareOutcome` ; son auteur, ou qui peut partager au même endroit |

`ShareOutcome` : `SENT` (parti vers les téléphones présents), `QUEUED` (gardé jusqu'à la clé du canal ou une liaison, une heure au plus), `SKIPPED` (une position), `NOT_ALLOWED`, `TOO_LARGE`. Les canaux : `Channel.ENSEMBLE` (toute l'opération), `Channel.unit(groupe)` (« Mon groupe »), `Channel.up(groupe)` (le fil du milieu « Commandement » et ce qui remonte), `Channel.down(groupe)` (« Un groupe » précis), `Channel.LEADERS` (tous les chefs), `Channel.waiting(membre)` (voie d'attente).

**`OperationView`** (tout est là ; rien d'autre à lire)

| Champ | Contenu |
|---|---|
| `phase` | `NONE`, `ASKING` (demande envoyée, pas encore admis), `WAITING` (admis, sans groupe), `PLACED`, `ENDED` |
| `ending` | si `ENDED` : `OUT` (parti ou retiré), `DISSOLVED`, `WIPED` ; tout est déjà effacé |
| `storageId`, `ensembleId`, `self` | l'opération gardée, son identifiant, le membre de ce téléphone |
| `operationName` | le nom de l'opération (celui de l'invitation tant que la première opération n'est pas arrivée, puis vérifié) |
| `hierarchy` | l'état du moteur (`HierarchyState`) : `rolesOf`, `isWaiting`, `canSee`, `readersOf`, `leaderOf`… |
| `members` | `MemberView(id, name, roles, group, waiting, online, label)` ; `name` vaut `null` tant que ce téléphone ne le connaît pas (écrire autre chose en attendant) ; `label` : `FULL` ou `INITIALS` sur la carte (Q17) |
| `groups` | `GroupView(id, name, parent, leader, deputy, members)` |
| `joinRequests` | `JoinRequestView(member, name, receivedAtMs)` : pour le créateur et les administrateurs ; `name` peut être `null` si la demande est partie avant que l'arrivant connaisse cet administrateur (elle revient toutes les 20 s) |
| `positions` | dernière position de chaque membre que ce téléphone voit : `ReceivedPosition(fix, channel, receivedAtMs)` |
| `threads` | `Map<Channel, List<ThreadMessage(author, text, sentAtMs, receivedAtMs)>>`, les siens compris, 500 par fil au plus, en mémoire seulement |
| `alerts` | `ReceivedAlert(author, channel, text, fix, sentAtMs, receivedAtMs)` en cours (une alerte levée disparaît) |
| `objects` | les croquis et waypoints encore affichés (`SharedRecord` : `kind`, `channel`, `author`, `objectId`, `revision`, `body`) |
| `targets` | `ShareTargets(position, messages, sketches, waypoints, alert, alertReaders)` ; `alertReaders` vide : l'alerte n'arrive à personne (créateur seul), l'écran le dit |
| `mayInvite`, `inviteEpoch` | ce téléphone invite ; l'époque de la clé d'invitation (elle change à chaque renouvellement) |
| `server`, `online` | `ServerState` de la salle ; les membres présents dans la salle |
| `refused` | `RefusedChange(seq, change, reason)` : ce que ce téléphone a écrit et qui a perdu ensuite |

### Lectures faites là où la spécification ne tranche pas (à confirmer ou corriger)

1. **L'administrateur lit le nom de qui demande à entrer avant de l'admettre** : la carte du nom est enveloppée pour le créateur et chaque administrateur (§ 9 disait seulement « pas le nom en clair »). Les autres membres, et qui attend, ne la lisent pas.
2. **La salle est tirée au hasard par le créateur** et ne change jamais ; le renouvellement change la clé, pas la salle.
3. **La clé d'invitation est renouvelée d'elle-même après chaque départ** (retrait, départ volontaire, et donc aussi passage de main suivi du départ) ; les invitations données avant ne marchent plus.
4. **Serveur des nouvelles opérations** : le serveur intégré à l'application, pas celui que l'utilisateur a réglé pour « Entre amis ».
5. **Messages et alertes en mémoire seulement** : perdus quand l'application redémarre ; jamais renvoyés à qui arrive (S6).
6. **Alerte** : un texte court et, si l'auteur veut, sa position ; levée par son auteur ; renvoyée dans la nouvelle destination quand son rôle change pendant qu'elle court ; une alerte déjà reçue reste affichée jusqu'à sa levée.
7. **Une opération ouverte à la fois** par le contrôleur (plusieurs peuvent être gardées).
8. **Les cartes des noms de qui attend** ne partent jamais à toute l'opération : seuls le créateur et les administrateurs les ont (Q10, Q11), les autres les reçoivent quand le membre est placé.
9. **Un objet durable fait au plus environ 3,7 Kio** (une trame du serveur) ; le moteur en permettait 12 Kio sur une liaison directe.

### Questions pour l'utilisateur

1. **Un téléphone absent quand l'opération est dissoute ne l'apprend jamais : tous les autres ont tout effacé aussitôt. Que faire ?**
   a. Garder sur chaque téléphone, une semaine, une trace fermée de l'opération qui ne sert qu'à annoncer la dissolution aux retardataires (recommandé).
   b. Tout effacer aussitôt (ce qui est fait) : le retardataire reste dans une opération vide jusqu'à ce qu'il la quitte.
2. **Les messages écrits doivent-ils rester sur le téléphone après un redémarrage de l'application ?**
   a. Oui, les 500 derniers de chaque fil, scellés avec l'opération et effacés avec elle (recommandé).
   b. Non, en mémoire seulement (ce qui est fait).
3. **Quand quelqu'un quitte l'opération ou en est retiré, les invitations déjà données cessent de marcher. D'accord ?**
   a. Oui : après un départ, on redonne une invitation (recommandé, ce qui est fait ; sinon le membre retiré pourrait suivre la hiérarchie avec son ancienne invitation).
   b. Non : renouveler seulement quand un administrateur le demande.
4. **Quel serveur pour les opérations ?**
   a. Le serveur intégré à l'application (recommandé, ce qui est fait).
   b. Celui réglé pour « Entre amis », s'il y en a un.
5. **L'administrateur voit-il le nom de qui demande à entrer avant de l'admettre ?**
   a. Oui, lui et les autres administrateurs seulement (recommandé, ce qui est fait).
   b. Non, il ne le voit qu'une fois la personne admise.

### Ce qui est vérifié, et comment

- `./gradlew :core:test --tests 'fr.toolmap.core.hierarchy.*'` : **153 tests, 0 échec** (143 avant ; `OperationNodeTest`, 10, téléphones simulés dans une salle simulée où chaque trame écrite arrive à chaque téléphone présent, horloge du test) : création, invitation par lien et par QR, demande d'entrée avec le nom pour le créateur, attente (position aux administrateurs, aucun nom lu hors celui de l'opération), placement ; retardataire qui rattrape opérations, clés, noms et objets ; départ d'un chef et promotion de l'adjoint, alerte qui suit le nouveau poste ; retrait (le retiré n'ouvre aucune trame suivante ni, avec les clés qu'il a gardées, le partage qu'elle porte ; clé d'invitation renouvelée) ; membre absent pendant un renouvellement qui récupère la clé, passage de main puis départ du créateur ; dissolution sur tous les téléphones présents, puis chez l'absent quand il reçoit les opérations ; envoi dans le groupe, vers le commandement, vers un groupe précis, entre chefs, alerte du chef de l'opération à tous ; revenant après une coupure (objet changé, objet retiré, pas d'ancien message) ; trames rejouées, changées d'un octet, d'une autre opération, demande d'entrée falsifiée ; invitation, demande, état gardé lus à l'identique, invitations coupées refusées, un lien « Entre amis » n'est pas une invitation d'opération.
- `./gradlew :data:testDebugUnitTest --tests 'fr.toolmap.data.hierarchy.*'` : **27 tests, 0 échec** (22 avant ; `OperationControllerTest` 3 : deux contrôleurs, chacun son stockage et un Keystore logiciel, dans une salle en mémoire : création, invitation, admission, placement, chef de groupe, position par la case de position, fil « Commandement », waypoint vers un groupe, dissolution qui vide les deux stockages ; relecture après redémarrage, départ d'un membre (stockage et clés effacés, clé d'invitation renouvelée) ; opération sans serveur ; `HierarchyOperationCheckTest` 2 : les deux phases du contrôle sur émulateur, et un coffre en panne). Chaque test vérifie qu'aucun signalement n'est un bogue.
- Build complet, `SilentFailureTest` compris : journal, session 113. **Run « CI » n° 203** (commit db06aa0), émulateur API 34 de la CI : « OK: « Opération » controller (API 34) » (création et enregistrement 84 ms ; relecture dans un nouveau processus 19 ms, même opération ; dissolution, aucune clé ni aucun dossier laissé) ; publication sautée.

### Ce qui n'a pas pu être vérifié

- **Deux téléphones réels**, ni deux émulateurs : la CI n'a qu'un émulateur ; l'échange n'est vérifié que sur la JVM, salle simulée. Le vrai serveur Firebase n'a pas porté ces trames (même forme JSON, salle de 32 caractères hexadécimaux, nom vide dans le battement : conformes aux règles publiées, à voir en vrai).
- **Batterie et écran éteint** : rien n'est branché au service de partage ; une signature par position envoyée et une vérification par position reçue restent à mesurer sur un téléphone.
- **Le contrôle sur émulateur** (`hierarchy-operation.sh`) est passé sur l'émulateur API 34 de la CI (ci-dessus) ; sur l'émulateur du poste, par la session locale : `bash tools/emulator/smoke.sh app/build/outputs/apk/debug emulator-check`, puis lire « OK: « Opération » controller (API …) » et `emulator-check/hierarchy-operation.txt`.

### Limites connues

- Sur le serveur, chaque téléphone présent répond au résumé d'un autre : des doublons sans effet sur l'état (à réduire par un délai de rang si besoin). *Corrigé le 2026-11-20 (P39.17, § 26)* : le résumé porte son destinataire, seul ce téléphone répond.
- Un téléphone resté sur une ancienne clé d'invitation n'est remis à jour que par un membre qui détient l'annonce de la nouvelle ; un arrivant qui n'a eu que la clé de son invitation ne peut pas la redonner (il ne connaît pas l'annonce).
- Une demande d'entrée faite avec une invitation antérieure au dernier renouvellement est écartée sans réponse : l'écran de l'invité doit dire, au bout d'un moment, qu'il faut une invitation récente.
- Les limites des §§ 6.4, 8.6, 15 et 16 restent.

## 18. Quatrième passage de maquettes : les écarts avant de coder (2026-11-15)

Les écarts listés au § 10 sexies (« Écarts à remontrer en aperçu avant de les coder ») et les deux textes du § 15 (« Deux textes d'écran à montrer en maquette ») sont dessinés dans `docs/previews/p39-groupes/tour4/` (session 114) : chaque image montre le dessin validé au tour 3 à côté de la proposition. Trois écarts de plus, trouvés en relisant ce document : la durée des waypoints et des croquis, que T12 et le moteur n'ont pas ; qui peut retirer un partage (T12) ; une troisième paire d'étiquettes collées sur la carte. Les questions U1 à U10 et la liste des écarts sans choix sont dans `tour4/REMONTEE.md`. **Aucune décision n'est écrite ici** : l'utilisateur n'a pas encore répondu ; ses réponses iront dans un paragraphe à part, comme au § 10 sexies.

## 19. Remontée : écrans, premier lot (P39.8, 2026-11-15)

Session 116, branche `claude/kind-lamport-kw07cf`, partie de `conception/groupes-hierarchiques` (5808ed3, 0.4.250) ; ADR-130. Les écrans suivent les maquettes validées (§ 10 septies) ; pour chaque écran, le dessin le plus récent fait foi (`tour4/`, puis `tour3/`, puis `tour2/`). R1 et R2 (trace de la dissolution, messages gardés) ne sont pas codés ici : ils sont à la session P39.9.

### Ce qui est codé

Le mode n'existe que dans le type de build `debug` (`app/src/debug/`, ADR-130) : l'APK de release et celui de benchmark restent ceux d'avant, sans code, sans texte et sans lien `toolmap://operation` du mode (drapeau `BuildConfig.OPERATION_MODE`, faux hors debug). Textes en français dans `app/src/debug/res/values/strings_operation.xml` ; thèmes de jour et de nuit de l'application.

1. **Entrée** (`tour2/01-entree-A`) : l'écran « Partage de position » hors groupe garde « Entre amis » (mêmes boutons, mêmes trames, même comportement) ; dans l'APK de debug, trois textes changent (« Entre amis : créez un groupe et invitez les autres par un code, ou rejoignez celui d'un autre. », « Créer un groupe entre amis », « Rejoindre un groupe ou une opération ») et la carte « Opération · Nouveau » (« Créer une opération ») vient dessous. Un code scanné ou collé, ou un lien ouvert, qui est une invitation d'opération mène à la question d'entrée ; tout autre code va à « Entre amis » comme avant.
2. **« Nouvelle opération »** (`tour4/10-creation` A, U9 A) : nom et note U9, groupes de départ (Nord, Sud, Est proposés ; ajout, renommage, suppression ; 6 au plus), « Votre groupe » (Est coché, « Aucun » possible), encadré ; « Créer l'opération et inviter » ouvre l'écran de l'opération et la feuille d'invitation.
3. **Écran de l'opération** (`tour3/08-inviter` A, `tour4/08-rappel-A`, `tour4/05-promotion-*`) : titre et compte ; bandeau du créateur avec « Transmettre » et « Compris » (U8 A ; « Compris » le retire pour de bon sur ce téléphone) ; « Partager ma position » ; tuiles « Administrer » (orange avec « n en attente » quand quelqu'un attend) et « Inviter » (« 22 sur 30 » ; grisée « Complet · 30 sur 30 ») ; « Mon groupe » et ses membres (rôle, état en ligne) ; « Toute l'opération » et ses groupes (chef ou « Sans chef », en ligne), chacun ouvrant la liste du groupe avec les noms entiers (`tour3/12-noms-liste` A) ; menu ⋮ : « Transmettre le rôle de créateur… » (créateur), « Quitter l'opération… ».
4. **Invitation** (`tour3/08-inviter-invitation`) : feuille avec le code QR, « Envoyer le lien » et le compte (« 22 sur 30 · 20 placés, 2 en attente »).
5. **Entrée d'un second téléphone** (`tour3/04-entree-nom`, `04-entree-nom-etats`) : « Rejoindre l'opération ? » avec le nom de l'opération et « Votre nom » prérempli (le nom donné dans « Entre amis », sinon celui du téléphone) ; « Rejoindre » grisé tant que le nom est vide ; « Opération complète » pour le 31e (`tour2/04-admin-limites`).
6. **Attente** (`tour4/01-attente` A, U1 A) : le nom de l'opération seul, « Ma position » avec un cadenas (« envoyée aux administrateurs »), « Quitter l'opération » et « C'est le seul moyen de ne plus envoyer sa position. » ; bandeau après la suppression de son groupe (`tour3/06-supprimer-groupe-apres`), fenêtre après une remise en attente (`tour3/07-remise-attente-confirmation`).
7. **Administration** (`tour2/03-attente-admin`, `04-admin-*`, `05-*` ; `tour3/06-supprimer-groupe`, `07-remise-attente-*`) : sommaire (en attente, chef de l'opération et adjoint, groupes, administrateurs, limites) ; liste d'attente, la plus récente arrivée d'abord, et « Placer » ; page d'un groupe (chef, adjoint, membres, « Ajouter des membres », « Modifier le groupe », suppression) ; nominations (chef de l'opération et adjoint, chef et adjoint de groupe, « Personne ») ; fiche d'un membre (changer de groupe, nommer chef ou adjoint, retirer le poste d'adjoint, remettre en attente, nommer administrateur, retirer de l'opération ; chez un administrateur, les gestes du créateur sont grisés avec un cadenas et « Réservé au créateur ») ; administrateurs (nommer, retirer : créateur seul) ; nouveau groupe et renommage.
8. **Départs** (`tour2/08-*`, `tour3/05-createur-A`, `05-successeur`, `09-*`, `tour4/09-glissiere`, `11-quitter-texte`, `05-promotion-*`) : « Quitter l'opération ? » d'un membre, d'un chef de groupe et du chef de l'opération (leur adjoint devient chef) ; départ du créateur (« Désigner un successeur » ou « Glisser pour dissoudre », jusqu'au bout) ; choix du successeur parmi les administrateurs ; transmission du rôle sans quitter (U8 A) ; ce que voient les autres ensuite : messages en bas de l'écran (nouveau créateur, nouveau chef), fenêtres « Vous êtes le créateur », « Vous êtes chef du Groupe … », « Vous êtes chef de l'opération » ; sur « Partage de position », « Opération dissoute » et « Retiré de l'opération ».
9. **Ce que chaque rôle voit et peut faire** est calculé hors de l'affichage (`OperationFacts`, `app/src/debug/kotlin/fr/toolmap/operation/OperationModels.kt`) et couvert par des tests JVM (`OperationModelsTest`), l'affichage par un test Robolectric (`OperationScreensTest`) et la démonstration par `DemoWorldTest`.

### Démonstration et « Voir comme » (APK de debug seulement)

- **Où** : « Partage de position », hors groupe, menu ⋮ ▸ « Démonstration « Opération » ». Une fois prête (quelques secondes ; « Préparation de la démonstration… »), l'écran de l'opération s'ouvre du téléphone de Membre 1. Dans son menu ⋮ : « Voir comme… » (les membres en ligne avec leurs rôles) et « Quitter la démonstration ».
- **Quoi** : « Exercice Vallon », Groupes Nord, Sud, Est et Ouest (sans chef), 20 placés et 2 en attente (Membres 21 et 22) ; Membre 1 créateur et administrateur dans le Groupe Est ; Membre 2 chef de l'opération, Membre 3 adjoint ; Membres 5 et 9 administrateurs ; chefs et adjoints de groupe comme sur les maquettes ; Membres 8, 12 et 20 hors ligne. « Voir comme » : la personne en attente (21), un membre (7), un chef de groupe (4, 9 ou 14), le chef de l'opération (2), un administrateur (5).
- **Comment** : 22 contrôleurs réels (`OperationController`), chacun avec son stockage dans le cache de l'application et un coffre de clés en mémoire, reliés par une salle de serveur simulée en mémoire, comme `OperationControllerTest` : rien ne va au serveur, rien n'entre dans le Keystore, tout s'efface en quittant la démonstration. Les gestes faits dans la démonstration jouent vraiment entre les téléphones simulés (placer, nommer, quitter, dissoudre).
- **Le vrai chemin** marche aussi : la carte « Opération » crée une vraie opération sur le serveur de coordination intégré, et un second téléphone la rejoint par le code QR ou le lien.

### Les captures et leurs maquettes

Les captures sont dans l'artefact `emulator-check` du run « CI », dossier `operation/` : une image par scène, plus neuf de nuit (`…-nuit.png`). Les textes de chaque écran sont dans le journal du job (lignes « == <capture>.png »), lisibles quand les artefacts ne le sont pas. Une capture porte le nom de la maquette à laquelle la comparer ; « M n » : le téléphone de Membre n.

| Capture | Maquette | Écran |
|---|---|---|
| `tour2-01-entree-A` (et `-nuit`) | `tour2/img/01-entree-A.png` (`01-entree-nuit.png`) | « Partage de position » hors groupe, sans opération |
| `tour4-10-creation-A` (et `-nuit`) | `tour4/img/10-creation.png` A (`10-creation-B.png` : A de nuit) | « Nouvelle opération » |
| `tour4-10-creation-A-nouveau-groupe` | `tour2/img/04-admin-groupes.png` (feuille « Nouveau groupe ») | « Ajouter un groupe » touché (écart 4) |
| `tour4-08-rappel-A` | `tour4/img/08-rappel-A.png`, gauche | M1, créateur |
| `tour4-08-rappel-A-feuille` | `tour4/img/08-rappel-A.png`, droite | « Transmettre le rôle de créateur » |
| `tour4-08-rappel-B-menu` | `tour4/img/08-rappel-B.png`, le menu ⋮ (écart validé par E1) | menu ⋮ du créateur |
| `tour3-08-inviter-A` (et `-nuit`) | `tour3/img/08-inviter.png` A (`08-inviter-invitation.png` : écran de nuit) | M5, administrateur |
| `tour3-08-inviter-invitation` | `tour3/img/08-inviter-invitation.png` | feuille d'invitation (code QR) |
| `tour3-08-inviter-B-membre` | `tour3/img/08-inviter-B.png`, écran d'un membre | M7, membre |
| `tour3-12-noms-liste-A` | `tour3/img/12-noms-liste.png` A | M7 : la liste du Groupe Sud |
| `tour3-04-entree-nom` | `tour3/img/04-entree-nom.png` | « Rejoindre l'opération ? », nom prérempli |
| `tour3-04-entree-nom-etats` (et `-nuit`) | `tour3/img/04-entree-nom-etats.png` | le nom effacé, « Rejoindre » grisé |
| `tour2-04-admin-limites-31e` | `tour2/img/04-admin-limites.png`, la 31e personne | « Opération complète » (écart 3) |
| `tour4-01-attente-A` (et `-nuit`) | `tour4/img/01-attente.png` A (`01-attente-B.png` : A de nuit) | M21, en attente |
| `tour2-04-admin-sommaire` (et `-nuit`) | `tour2/img/04-admin-sommaire.png` (`04-admin-nuit.png`) | M5 : « Administrer » |
| `tour2-03-attente-admin` | `tour2/img/03-attente-admin.png`, gauche | M5 : « En attente » |
| `tour2-03-attente-admin-placer` | `tour2/img/03-attente-admin.png`, droite | « Placer Membre 21 » |
| `tour2-04-admin-groupe` (et `-nuit`) | `tour2/img/04-admin-groupe.png` (`04-admin-nuit.png`) | M5 : Groupe Nord |
| `tour2-04-admin-groupe-ajouter` | `tour2/img/04-admin-groupe.png`, « Ajouter au Groupe Nord » | ajout de membres |
| `tour2-04-admin-chef` | `tour2/img/04-admin-chef.png` | M5 : chef de l'opération et adjoint |
| `tour2-04-admin-chef-adjoint` | `tour2/img/04-admin-chef.png`, nomination | adjoint du Groupe Nord (« Déjà chef de ce groupe ») |
| `tour2-04-admin-groupes-nouveau` | `tour2/img/04-admin-groupes.png`, « Nouveau groupe » | sans choix de couleur (écart 1) |
| `tour2-04-admin-groupes-modifier` | `tour2/img/04-admin-groupes.png`, « Modifier le groupe » | idem |
| `tour2-04-admin-sans-chef` | `tour2/img/04-admin-sans-chef.png` | M5 : Groupe Ouest, sans chef |
| `tour2-05-admins-createur` | `tour2/img/05-admins-createur.png` | M1 : « Administrateurs » |
| `tour2-05-admins-createur-nommer` | `tour2/img/05-admins-createur.png`, « Nommer un administrateur » | M1 |
| `tour2-05-admins-retirer` | `tour2/img/05-admins-retirer.png` | M1 : retirer Membre 5 |
| `tour2-05-admins-retirer-administrateur` | `tour2/img/05-admins-retirer.png`, chez un administrateur | M5 : « Seul le créateur… » |
| `tour2-05-retirer-membre` | `tour2/img/05-retirer-membre.png` | M1 : fiche de Membre 11 |
| `tour3-07-remise-attente-fiche` | `tour3/img/07-remise-attente-fiche.png` | M5 : la même fiche, gestes du créateur grisés |
| `tour3-07-remise-attente-adjoint` | `tour3/img/07-remise-attente-adjoint.png`, la fiche | M5 : Membre 10, adjoint |
| `tour3-07-remise-attente-confirmation` | `tour3/img/07-remise-attente-confirmation.png` | M5 : « Remettre Membre 11 en attente ? » |
| `tour3-07-remise-attente-adjoint-confirmation` (et `-nuit`) | `tour3/img/07-remise-attente-adjoint.png`, la confirmation | M5 : Membre 10 |
| `tour2-05-retirer-confirmation` | `tour2/img/05-retirer-confirmation.png` | M1 : « Retirer Membre 11 de l'opération ? » |
| `tour3-06-supprimer-groupe` | `tour3/img/06-supprimer-groupe.png` | M5 : « Supprimer le Groupe Ouest ? » |
| `tour4-11-quitter-texte-membre` | `tour4/img/11-quitter-texte.png` | M7 : « Quitter l'opération ? » |
| `tour3-09-chef-quitte` | `tour3/img/09-chef-quitte.png` | M4, chef du Groupe Nord |
| `tour3-09-chef-operation-quitte` | `tour3/img/09-chef-operation-quitte.png` | M2, chef de l'opération |
| `tour3-05-createur-A` | `tour3/img/05-createur-A.png` | M1 : la fenêtre aux deux sorties |
| `tour4-09-glissiere` (et `-nuit`) | `tour4/img/09-glissiere.png` (`09-glissiere-nuit.png`) | pendant le glissement |
| `tour3-05-createur-A-au-bout` | `tour3/img/05-createur-A-glissement.png`, au bout | « Relâchez pour dissoudre » |
| `tour3-05-successeur` | `tour3/img/05-successeur.png` | « Qui vous succède ? » |
| `tour3-06-supprimer-groupe-apres` | `tour3/img/06-supprimer-groupe-apres.png`, gauche | M5 : « Administrer » après la suppression du Groupe Ouest |
| `tour3-06-supprimer-groupe-apres-membre` | `tour3/img/06-supprimer-groupe-apres.png`, droite | M18, revenu en attente |
| `tour3-07-remise-attente-confirmation-membre` | `tour3/img/07-remise-attente-confirmation.png`, ce que voit le membre | M11, remis en attente |
| `tour3-09-promotion-adjoint` | `tour3/img/09-promotion-adjoint.png` | M5 après le départ de Membre 4 |
| `tour4-05-promotion-groupe` | `tour4/img/05-promotion-groupe.png` (sans « Messages », écart 6) | M7 |
| `tour3-09-promotion-operation` | `tour3/img/09-promotion-operation.png` | M3 après le départ de Membre 2 |
| `tour4-05-promotion-operation` | `tour4/img/05-promotion-operation.png` (sans « Messages ») | M7 |
| `tour2-05-retirer-confirmation-retire` | `tour2/img/05-retirer-confirmation.png`, ce que voit le retiré | M13 retiré : « Partage de position » |
| `tour2-08-successeur` | `tour2/img/08-successeur.png`, gauche | M5, devenu créateur |
| `tour2-08-successeur-autres` | `tour2/img/08-successeur.png`, droite | M7 : le message en bas de l'écran |
| `tour2-08-dissolution-autres` | `tour2/img/08-dissolution.png`, droite | M7 : « Opération dissoute » |

53 scènes, plus 9 de nuit : 62 captures. Les 11 dernières scènes changent la démonstration (suppression d'un groupe, remise en attente, départs, retrait, transmission, dissolution) : elles sont prises après les autres, dans cet ordre.

### Écarts à remontrer en aperçu

Chacun est codé au plus près ; aucun n'a été montré avant d'être codé (consigne de la session principale : décrire ici l'image, la différence et la raison).

1. **Couleur des groupes** (`tour2/img/04-admin-groupes.png`, « Couleur (celles déjà prises sont pâles) » ; groupes de départ de `tour4/img/10-creation.png`) : aucun choix ; chaque groupe prend la première couleur libre d'une liste de huit dans l'ordre de création (bleu, orange, vert, violet, rose, brun, cyan, olive), la même sur tous les téléphones. Pourquoi : le moteur ne porte pas de couleur de groupe ; une couleur choisie sur un téléphone n'irait pas aux autres sans changer le moteur et son format. Question L1.
2. **Entrée sans geste d'acceptation** (`tour2/img/03-attente-admin.png`) : la demande d'entrée signée (nom lisible du créateur et des administrateurs seulement, R5) est admise par le premier téléphone d'administrateur ou du créateur, application ouverte, qui lit le nom ; la personne passe alors « En attente ». Pourquoi : les maquettes ne dessinent pas d'acceptation, et le contrôleur demande qu'un administrateur admette. Tant qu'aucun administrateur n'a l'application ouverte, le demandeur voit déjà l'écran d'attente sans être encore dans l'opération : sa position ne part pas encore, alors que la ligne dit « envoyée aux administrateurs ». Question L3.
3. **« Opération complète » après « Rejoindre »** (`tour2/img/04-admin-limites.png`, la 31e personne « qui scanne ») : le message vient quand la 31e personne a touché « Rejoindre » et que son téléphone a lu l'opération ; sa demande est alors abandonnée et effacée. Pourquoi : l'invitation ne dit pas combien l'opération a de membres (le nombre change, l'invitation non) ; le téléphone ne le sait qu'en lisant le journal, après sa demande.
4. **Feuilles non dessinées, faites par analogie** : « Nouveau groupe » ouvert depuis « Nouvelle opération » (la feuille de `tour2/04-admin-groupes`, sans couleur ; tant que le nom est vide, le bouton grisé dit « Créer le groupe ») ; « Nommer chef ou adjoint » ouvert depuis la fiche d'un membre (`tour3/07-remise-attente-fiche` montre la ligne, pas la feuille) : titre « Nommer chef ou adjoint », « Du Groupe … », deux choix (« Chef de groupe », « Adjoint du chef de groupe »), « Nommer », « Annuler ».
5. **Nom inconnu** : quand un téléphone ne peut pas lire un nom (qui attend, vu par un membre placé ; une personne arrivée avant qu'on soit administrateur, défaut 2 ci-dessous), la ligne et le jeton portent « … ». Les maquettes ne dessinent pas ce cas.
6. **Lignes menant à des écrans pas encore codés, retirées** : sur l'écran de l'opération, « Envoyer une alerte » et « Messages » (`tour3/08-inviter` A, `tour4/08-rappel-A`, `tour4/05-promotion-*`) et l'icône de carte au bout de chaque membre ; dans « Administrer », « Tous les membres » (`tour2/04-admin-sommaire`). Les écrans gardent leur ordre et leurs marges ; rien n'annonce ce qui manque.
7. **Âge d'une arrivée, et depuis quand un membre est hors ligne** (`tour2/img/03-attente-admin.png`, « Arrivé il y a 3 minutes » ; listes des groupes) : comptés par ce téléphone depuis qu'il a vu la demande ou la personne en ligne. Pourquoi : ni le moteur ni le transport ne portent ces heures ; un administrateur qui ouvre l'application après une arrivée la voit arrivée à l'instant.
8. **Écrans étroits** (320 dp, l'émulateur de la CI) : les tuiles « Administrer » et « Inviter » passent l'une sous l'autre quand leurs titres ne tiennent pas côte à côte (sinon « Admini ») ; le sous-titre de la barre d'une page de groupe (« Exercice Vallon · 5 membres », à côté de deux icônes) prend une taille un peu plus petite, 80 % au moins, au lieu de finir par « … ». Les maquettes sont dessinées à 412 dp ; ailleurs, une ligne trop longue passe à la ligne. Vérifié par `OperationNarrowScreensTest`.

*Réponse de l'utilisateur, 2026-11-16* : sans choix (E3). Ses mots : « On y reviendra lors des tests terrains » ; ces écarts restent tels quels pour l'instant.

### Éléments laissés de côté (écrans d'un autre lot)

La couche de la carte (jetons, étiquettes, fiches, `tour2/06-*`, `tour3/10-attente-carte`, `12-noms-carte*`, `tour4/06-etiquettes-*`) ; la fenêtre d'envoi et la liste des partages (`tour2/07-*`, `tour3/01-*`, `tour4/04-*`, `07-envoi-membre*`, `11-duree`, `11-retirer`) ; les messages et les fils (`tour2/07-messages*`, `tour3/03-*`, `tour4/07-fil-ferme*`, `07-ecrire-commandement*`) ; l'alerte (`tour3/02-*`, `tour4/02-*`, `03-*`) ; la fenêtre et la carte de l'arrivant (`tour3/11-arrivant*`) ; la notification Android du partage d'une opération ; « Réglages » et « Aide » du menu ⋮ ; le message propre à une invitation devenue ancienne (après un départ, R3 : la demande reste sans réponse). `tour3/08-inviter-complet` est codé (« Inviter » grisé à 30 sur 30) mais n'a pas de capture : la démonstration a 22 membres.

### Défauts trouvés par la démonstration (contrôleur et transport, P39.7)

1. **Hors ligne non publié** : quand la liaison d'un téléphone se ferme, `OperationController` ne publie pas de nouvelle vue ; les autres le voient « en ligne » jusqu'au changement suivant. La démonstration force une nouvelle vue (en demandant le lien d'invitation) après avoir coupé Membres 8, 12 et 20. À corriger dans le contrôleur.
2. **Administrateur nommé après une arrivée** : la carte du nom d'une personne en attente est scellée pour le créateur et les administrateurs connus au moment de sa demande ; un administrateur nommé ensuite ne lit pas ce nom (« … ») tant qu'elle n'est pas placée, alors que c'est lui qui doit la placer. La démonstration nomme donc ses administrateurs avant l'arrivée de Membres 21 et 22. Question L2.

### Ce qui est vérifié, et comment

- `./gradlew :app:testDebugUnitTest --tests 'fr.toolmap.operation.*'` : 17 tests, 0 échec (`OperationModelsTest` 15 : droits et vues de chaque rôle, gestes grisés, textes des jetons, avis ; `DemoWorldTest` : les faits des maquettes, de chaque place ; `OperationScreensTest` : les écrans de chaque rôle dessinés par Robolectric, ce qui y est et ce qui n'y est pas).
- `OperationNarrowScreensTest` (Robolectric, 320 dp, rendu natif du texte) : aucun texte du mode coupé, tronqué par « … » ni privé de lignes, de la place de chaque rôle, feuilles et fenêtres comprises.
- Compilation des types `release` et `benchmark` sans le mode (aucun fichier de `app/src/debug` n'y entre) ; `SilentFailureTest` et `RegexPortabilityTest` lisent aussi `src/debug` (`core/build.gradle.kts`).
- Build complet, run « CI » et relecture des captures : journal, session 116. Run « CI » n° 212 : 62 captures prises, aucun plantage ni ANR ; leurs textes (journal du job) sont ceux des maquettes. La relecture a trouvé les feuilles hautes ouvertes à mi-hauteur (boutons du bas cachés), corrigé ensuite : les feuilles du mode s'ouvrent entières. Sur l'émulateur de la CI, en anglais, les âges s'écrivent « 14 minutes ago » (« il y a 14 minutes » sur un téléphone en français).

### Ce qui n'est pas vérifié

- Un téléphone réel (le rendu, le code QR scanné par un autre téléphone, le lien ouvert depuis une messagerie) ; deux téléphones réels sur le vrai serveur de coordination (le contrôle sur émulateur n'utilise que la démonstration en mémoire) ; la batterie ; la position envoyée écran éteint (aucun service au premier plan pour une opération : elle ne part que tant que l'application tourne).
- Le passage d'une opération à l'autre et le retour à « Entre amis » après une dissolution sur un vrai téléphone.

### Questions pour l'utilisateur

La recommandation est toujours la première.

**L1. La couleur d'un groupe** (`tour2/04-admin-groupes`, écart 1)
1. Choisie comme sur la maquette : la couleur entre dans le moteur (un champ du groupe, signé, changé par un administrateur) ; à faire par une session du moteur avant que les écrans la proposent. *(recommandé)*
2. Donnée d'office dans l'ordre des groupes, sans choix (ce qui est codé).

*Réponse de l'utilisateur, 2026-11-16* : 1 (L1). La couleur entre dans le moteur et part à tous les téléphones ; elle y est codée avant que l'écran la propose. À faire : P39.14.

**L2. Le nom d'une personne en attente pour un administrateur nommé après son arrivée** (défaut 2)
1. Le créateur ou un administrateur qui lit le nom le scelle aussi pour le nouvel administrateur (changement du transport, comme R1 et R2). *(recommandé)*
2. « … » jusqu'au placement (ce qui est fait) ; un administrateur plus ancien la place.

*Réponse de l'utilisateur, 2026-11-16* : 1 (L2). Les administrateurs qui connaissent le nom de qui attend le redonnent au nouvel administrateur. À faire : P39.14 (la même réponse vaut pour la question 6 du § 21, cas H).

**L3. Qui a l'invitation entre-t-il sans qu'on l'accepte ?** (écart 2)
1. Oui, comme sur les maquettes : il passe « En attente » dès qu'un administrateur a l'application ouverte ; on l'écarte ensuite par « Retirer de l'opération ». *(recommandé)*
2. Non : une fenêtre « Membre 23 demande à entrer », avec « Accepter » et « Refuser », chez le créateur et les administrateurs (à dessiner).

*Réponse de l'utilisateur, 2026-11-16* : 1 (L3). Qui a l'invitation entre sans acceptation, comme sur les maquettes ; on l'écarte par « Retirer de l'opération ». Rien à changer.

**L4. La position d'une opération, écran éteint**
1. Comme « Entre amis » : un service au premier plan et sa notification tant que « Partager ma position » est actif (notification à dessiner). *(recommandé)*
2. Seulement tant que l'application tourne (ce qui est codé).

*Réponse de l'utilisateur, 2026-11-16* : 1 (L4). La position d'une opération part aussi écran éteint, comme « Entre amis » ; la notification se dessine d'abord (aperçu). À faire : P39.14.

**L5. « Scanner un code » coupé en « Scanner un » sur un écran de moins de ~360 dp** (bouton d'« Entre amis », déjà ainsi dans l'APK de release ; ce lot n'y touche pas)
1. Le corriger aussi dans « Entre amis » : le texte du bouton passe sur deux lignes quand il ne tient pas (change l'écran de la release : aperçu avant). *(recommandé)*
2. Laisser tel quel.

*Réponse de l'utilisateur, 2026-11-16* : 1 (L5). Corriger « Scanner un code » coupé dans « Entre amis » : le texte passe sur deux lignes ; aperçu d'abord, c'est un écran de la version publiée. À faire : P39.14.

## 20. Remontée : trace de dissolution et messages gardés (P39.9, 2026-11-15)

Pour la session principale et pour la session qui code les écrans (session 117, branche `claude/clever-edison-pi03p9`, partie de `conception/groupes-hierarchiques` 5808ed3, 0.4.250 ; `.conception-privee` présent). Décision d'architecture : ADR-131. Le § 19 et le § 10 septies sont à la session parallèle des écrans (P39.8) ; ce paragraphe ne les écrit pas. Rien de visible ne change : ni écran, ni texte, ni ressource, ni fichier de `:app` ; le partage « Entre amis » n'est pas touché (aucun fichier de `:p2p`, de `core/…/p2p/` ni de `app/…/share/`).

### Les réponses de l'utilisateur, mot pour mot

```
R1 A · Chaque téléphone garde une semaine une trace fermée de l'opération
R2 A · Oui, les 500 derniers de chaque fil
```

- **R1** répond à la question 1 du § 17 (un téléphone absent quand l'opération est dissoute ne l'apprenait jamais). Précision affichée à l'utilisateur : « Elle ne sert qu'à annoncer la dissolution aux retardataires ; elle ne contient ni position, ni nom, ni message. » Elle tranche aussi la question 1 du § 16 (effacer aussitôt, ou garder l'opération fermée quelques minutes) : tout ce qui n'est pas la trace est effacé aussitôt, comme avant.
- **R2** répond à la question 2 du § 17 (messages en mémoire seulement). Précision affichée : « Scellés avec l'opération et effacés avec elle. »
- Les questions 3 à 5 du § 17 ont reçu la recommandation (ce qui était fait) : rien à coder.

### Ce qui est construit

- **`:core`, paquet `fr.toolmap.core.hierarchy`** :
  - `Dissolution.kt` (nouveau) : la preuve (`DissolutionProof`, `ProofVerdict`), ce qui voyage (`DissolutionNotice`), la trace (`DissolutionTrace`, `TraceKey`), le répondeur du téléphone qui garde une trace (`DissolutionAnnouncer`) ;
  - `MessageLog.kt` (nouveau) : les fils gardés (`MessageLog`, `LoggedMessage`) ; leurs octets dans `HierarchySnapshot` (`encodeMessages`, `restoreMessages` : une partie de plus, même version) ;
  - `OperationWire.kt` : un genre de trame de plus, `FrameGenre.DISSOLUTION` (7) ;
  - `OperationNode.kt` : vérifie une preuve reçue et finit l'opération (`dissolvedByTrace`) ; garde la preuve de sa propre trace (`dissolution`, `dissolvedAtMs`) ; `sendMessage(canal, OperationText)` (l'identifiant du message est tiré par l'appelant, qui le garde).
- **`:data`, paquet `fr.toolmap.data.hierarchy`** : `DissolutionTraces.kt` (nouveau, les traces sur le disque) ; `OperationStorage` (ses traces, une horloge ; `wipeAll` efface aussi les traces) ; `StoredOperation` (`endDissolved`, `saveMessages`, `loadMessages`, la trace laissée par `save` ou `load` quand ils trouvent l'opération dissoute) ; `OperationController` (fils gardés, trace à la fin, moments d'écoute) ; `HierarchyOperationCheck` (trois phases).
- **Outils** : `tools/emulator/hierarchy-operation.sh` (trois phases) ; commentaires de `smoke.sh`.

### R1 — la trace, pas à pas

**Ce qu'elle contient** (`DissolutionTrace`, version 1 de son format, scellée sur le disque) :

| Champ | Octets | Pourquoi |
|---|---|---|
| identifiant de l'opération | 8 | la reconnaître |
| date de la dissolution | 8 | l'effacer une semaine après |
| identifiant pour parler dans la salle | 8 | tiré au hasard à la création de la trace : **pas** l'identifiant de membre de ce téléphone |
| salle et serveur | 16 + adresse et clé publique du serveur | entrer dans la salle |
| clés d'invitation des dernières époques | 4 × (4 + 32) au plus | ouvrir la trame d'un retardataire et lui répondre sous la clé qu'il détient (il a pu manquer un renouvellement) |
| la preuve | 707 à 3 363 avec sa date | prouver la dissolution signée |

La preuve : la première opération (qui ne porte que les **empreintes** du nom de l'opération et du nom du créateur, § 8.8, ses clés publiques et les règles), chaque passage de main appliqué, l'opération de dissolution ; chacune avec l'identifiant de son auteur (l'empreinte de sa clé, propre à cette opération), sa clé publique et sa signature : c'est ce qui prouve que le créateur du moment a dissous. **Rien d'autre** : ni position, ni nom, ni message, ni objet, ni clé de contenu, ni clé privée, ni carte de nom, ni liste des membres (vérifié octet par octet, ci-dessous). La trame qui la porte fait 749 octets sans passage de main, 3 405 avec 16 (le plus que la preuve admet) : une trame de contrôle du serveur (4 096).

**Qui en garde une** : chaque téléphone qui était membre au moment de la dissolution, dans l'état dissous (en attente compris), qu'il l'ait lue dans les opérations ou apprise par une trace. Pas un membre retiré ou parti avant : à la dissolution il n'est plus membre, il a déjà tout effacé (`OUT`), ou, s'il apprend les deux d'un coup, l'état le dit hors de l'opération. Pas un téléphone jamais admis (une demande d'entrée en cours) : il apprend la fin, sa demande s'arrête, il ne garde rien.

**Où** : `files/hierarchy/operations/traces/<identifiant>.trace`, un fichier par opération, scellé (AES-256-GCM) par une clé AES du Keystore propre à la trace (alias `toolmap.hierarchy.op-trace.<identifiant>`, préfixe que la recherche des clés des opérations ne trouve pas). Sous `hierarchy/` : hors de toute sauvegarde (ADR-128, point 8 ; vérifié).

**Quand elle s'efface** : une semaine après la dissolution (`DissolutionTrace.KEPT_MS`), **à compter de la dissolution, pas de la nouvelle** : un retardataire qui l'apprend le jour 6 garde sa trace un jour, et plus aucune trace ne vit au-delà de la semaine. L'effacement se fait de lui-même, la clé du Keystore d'abord puis le fichier, au premier regard après la semaine (démarrage d'une opération, moment d'écoute, `traces()`). Aucun réveil du téléphone n'est programmé pour cela : un téléphone éteint pendant deux semaines garde le fichier, illisible sans la clé, jusqu'à son prochain démarrage de l'application. Une date de plus d'une semaine en avance sur l'horloge (horloge fausse) compte comme dépassée.

**Comment un retardataire l'apprend** :

1. Le retardataire revient dans la salle (son opération est encore ouverte chez lui : il ne la sait pas dissoute). Il envoie son résumé à chaque téléphone qui arrive, et toutes les 60 s.
2. Un téléphone qui garde la trace est dans la salle (moments d'écoute, ci-dessous) : il ouvre la trame du retardataire avec la clé d'invitation de son époque, et répond, sous la même époque, par la preuve et la date (`DissolutionNotice`, trame `DISSOLUTION`). Une fois par minute au plus pour un même émetteur ; jamais à ses propres trames, ni à celles d'une autre trace, ni à une trame qu'il ne peut pas ouvrir (`DissolutionAnnouncer`).
3. Le retardataire vérifie la preuve (ci-dessous). Vraie : il oublie tout, comme pour une dissolution lue dans les opérations, le contrôleur efface le stockage, et, s'il était membre, garde à son tour une trace jusqu'à la même date. Fausse : refusée, comptée, signalée (`Problems.expected`, lieu `operation.dissolution.refused`, la raison) ; l'opération continue.

**La vérification de la preuve** (`DissolutionProof.check`), sans le journal, puis contre ce que le téléphone détient :

| Vérification | Refus |
|---|---|
| chaque opération est de cette opération, et la première est celle que son identifiant désigne | `WRONG_ENSEMBLE` (une preuve rejouée depuis une autre opération) |
| chaque opération est signée par son auteur ; la première par la clé qu'elle inscrit | `FORGED` |
| chaque passage de main, puis la dissolution, est signé par le créateur du moment (le premier, puis chaque successeur), horloges croissantes | `NOT_BY_THE_CREATOR` |
| pour chaque signataire dont ce téléphone a vu l'admission : la même clé | `FORGED` |
| aucune opération de la preuve n'est différente de celle que ce téléphone détient sous ce numéro, ni refusée chez lui ; les passages de main qu'il a appliqués sont les premiers de la preuve | `INCONSISTENT` (un ancien créateur qui signe une dissolution après avoir passé la main, chez qui a vu le passage de main) |

**Quand un téléphone qui garde une trace écoute la salle (le choix de cette session) et ce que cela coûte** :

- **2 minutes à la fois** (`TRACE_WINDOW_MS`) : un retardataire présent parle dans la minute (son résumé à l'arrivée d'un téléphone, puis toutes les 60 s) ;
- **au démarrage d'une opération** sur ce téléphone (création, entrée, réouverture : le partage « Opération » tourne), **puis toutes les 30 minutes** tant qu'elle tourne (`TRACE_EVERY_MS`) ;
- **quand les écrans le demandent** (`announceDissolutions()`, à appeler quand l'application vient au premier plan), une fois toutes les 10 minutes au plus (`ANNOUNCE_EVERY_MS`) ;
- **jamais autrement** : ni en tâche de fond sans opération, ni par un réveil programmé ; sans trace, aucun réseau du tout.

Coût (d'après les réglages du serveur, `FirebaseRelay` ; **non mesuré sur un téléphone**) : un moment, c'est une connexion au flux de la salle et environ 8 battements de présence (un toutes les 15 s) ; tant qu'une opération tourne, 2 minutes sur 30, soit environ un quinzième d'une présence permanente (16 battements par heure au lieu de 240) ; une réponse de 749 à 3 405 octets par retardataire entendu, une fois par minute au plus. Ce que cela laisse : un retardataire n'apprend la dissolution que si un téléphone qui garde la trace passe dans la salle pendant qu'il y est, dans la semaine. Chaque membre présent à la dissolution est un gardien ; le retardataire reste dans la salle tant que son opération est ouverte chez lui.

### R2 — les messages gardés

- Les **500 derniers messages de chaque fil**, les siens compris (`MessageLog.MAX_PER_THREAD`), dans un fichier scellé à part de l'opération (`messages.sealed`, même clé de stockage que les parties, lié à l'opération et à l'ensemble), réécrit seul après chaque message (pas tout l'enregistrement de l'opération) ; relus à l'ouverture (`open`) : mêmes auteurs, textes, heures d'envoi et de réception.
- Un message rejoué (même auteur, même identifiant), par exemple une trame entendue de nouveau après un redémarrage, quand l'automate ne se souvient plus de ce qu'il a vu, n'est pas gardé deux fois.
- Le texte est gardé coupé comme il voyage (1 024 octets d'UTF-8, sur une frontière de caractère) : ce qu'affiche le fil avant et après un redémarrage est le même.
- Le fil d'un canal que ce téléphone ne lit plus (changé de groupe, perdu un poste, remis en attente) est oublié, comme ses clés et ses objets (ADR-128, point 6), y compris dans le fichier.
- **Effacés avec l'opération** : dissolution, retrait, départ (le dossier entier part, la clé de stockage d'abord). **Jamais dans la trace** (vérifié octet par octet).
- **Les alertes restent en mémoire**, comme avant : après un redémarrage, plus aucune.
- **Une opération enregistrée par la 0.4.250** (sans fichier de messages, sans dossier de traces) se relit telle quelle : un fichier de messages absent se lit vide, sans rien signaler ; aucun autre fichier ne change de forme ; rien n'est effacé ; les messages sont gardés dès le premier écrit après la mise à jour (vérifié).

### Les ajouts à l'interface du contrôleur (pour les écrans)

Rien n'est retiré ni renommé ; `OperationView.ending` vaut toujours `Ending.DISSOLVED` pour une dissolution, lue dans les opérations ou apprise par une trace.

| Ajout | Contenu |
|---|---|
| `OperationView.endedByTrace: Boolean` | l'opération finie (`ENDED`, `DISSOLVED`) a été annoncée par un téléphone qui garde la trace : ce téléphone était absent à la dissolution. L'écran peut dire « Opération dissoute pendant votre absence ». |
| `OperationView.traceKeptUntilMs: Long?` | dissoute pendant que ce téléphone en était membre : la trace qu'il garde s'efface à cette date ; `null` : aucune trace (parti, retiré, jamais admis, ou opération sans serveur… voir lecture 5). |
| `OperationController.traces(): List<DissolutionTraceView>` | les traces gardées sur ce téléphone, `DissolutionTraceView(dissolvedAtMs, keptUntilMs)` ; aucun nom (une trace n'en a pas). |
| `OperationController.announceDissolutions(): Int` | **à appeler quand l'application vient au premier plan** (écran de l'opération ou démarrage de l'application) : 2 minutes dans la salle de chaque opération dissoute de la semaine ; rend le nombre de traces annoncées (0 : aucune, aucun réseau). |
| `OperationView.threads` | même type qu'avant ; les messages sont désormais **gardés** (500 par fil) et relus à l'ouverture, et non plus en mémoire seulement (le tableau du § 17 disait « en mémoire seulement ») |
| `open(id)` rendant `StartResult.ENDED` | `view.traceKeptUntilMs` dit si une trace a été gardée (opération trouvée dissoute à la lecture) |
| `LoadResult.Ended.traceKeptUntilMs`, `SaveResult.Ended.traceKeptUntilMs` | le même renseignement au niveau du stockage |
| `OperationNode.sendMessage(channel, OperationText)`, `OperationNode.dissolution`, `.dissolvedByTrace`, `.dissolvedAtMs`, `FrameGenre.DISSOLUTION` | pour le contrôleur et les tests ; les écrans n'en ont pas besoin |

### Lectures faites là où les réponses ne tranchent pas (à confirmer ou corriger)

1. **La semaine compte à partir de la dissolution**, pas du moment où chaque téléphone l'apprend : sinon une trace pourrait passer de téléphone en téléphone et vivre bien plus d'une semaine.
2. **Un retardataire qui apprend la dissolution par une trace en garde une à son tour**, jusqu'à la même date : il était membre au moment de la dissolution, et chaque gardien de plus prévient mieux les autres absents.
3. **Un membre en attente au moment de la dissolution garde une trace** : il est membre (admis).
4. **Un téléphone qui demandait à entrer** (jamais admis) avec une invitation donnée avant la dissolution apprend la fin (sa demande s'arrête au lieu d'attendre sept jours), mais ne garde pas de trace.
5. **Une opération sans serveur laisse aussi une trace** (elle ne s'annoncera jamais) : la règle dit « chaque téléphone », et c'est ce que vérifie le contrôle sur émulateur, qui tourne sans serveur.
6. **L'identifiant sous lequel la trace parle dans la salle est tiré au hasard**, pas celui du membre : la trace ne nomme aucun membre, et le transport de l'opération qui se ferme n'efface pas sur le serveur la présence du moment d'écoute.
7. **Un texte trop long est gardé coupé comme il voyage** (1 024 octets) ; il l'était déjà chez les autres.
8. **Le fil d'un canal que ce téléphone ne lit plus est oublié**, sur le disque aussi, comme ses clés et ses objets.
9. **Les alertes restent en mémoire** (R2 ne parle que des messages ; la consigne de la session principale le redit).

### Questions pour l'utilisateur

1. **Un membre retiré pendant qu'il était hors réseau, qui revient dans la semaine de la dissolution, l'apprend par la trace (il ne savait pas qu'il avait été retiré) et en garde une à son tour. Que faire ?**
   a. L'accepter (recommandé) : il n'apprend que la fin de l'opération, rien d'autre ; la trace reste sans aucune liste de membres.
   b. Mettre dans la trace la liste des numéros des membres au moment de la dissolution (des numéros, sans nom), pour ne répondre qu'à eux.
   *Réponse de l'utilisateur, 2026-11-16* : a (D1). L'accepter : rien à changer.
2. **La dissolution n'arrive à un retardataire que si un téléphone qui garde la trace passe dans la salle pendant qu'il y est. Faut-il aussi déposer la preuve, chiffrée, sur le serveur, où il la lirait dès son arrivée ?**
   a. Non, rien sur le serveur (recommandé, ce qui est fait) : le serveur ne supprime rien de lui-même, la copie y resterait après la semaine si aucun gardien ne repassait l'enlever.
   b. Oui, la déposer, et la faire enlever par le premier téléphone qui garde la trace et passe dans la salle après la semaine.
   *Réponse de l'utilisateur, 2026-11-16* : a (D2). Rien sur le serveur (ce qui est fait).

### Ce qui est vérifié, et comment

- `./gradlew :core:test --tests 'fr.toolmap.core.hierarchy.*'` : **166 tests, 0 échec** (153 avant ; `DissolutionTraceTest` 9, `MessageLogTest` 4), téléphones simulés dans une salle simulée (comme `OperationNodeTest`), et des téléphones qui gardent une trace dans la même salle :
  - le retardataire apprend la dissolution d'un téléphone qui garde la trace, un jour après, et garde la sienne (même date) ; celui qui a manqué un passage de main l'accepte (le passage de main est dans la preuve) ; celui qui demandait à entrer avec une invitation d'avant apprend la fin et ne garde rien ;
  - un membre retiré ne garde aucune trace, qu'il l'apprenne aussitôt ou en même temps que la dissolution ;
  - le contenu de la trace et de sa trame, octet par octet : aucun nom (opération, membres, groupe), aucun message, aucune position (les 8 octets de latitude et longitude), aucun objet, aucune clé de contenu, aucun sel de carte de nom ; rien que la première opération, les passages de main et la dissolution ; l'identifiant sous lequel elle parle n'est celui d'aucun membre ;
  - preuves refusées et signalées : celle d'une autre opération rejouée sous la clé d'invitation de celle-ci (`WRONG_ENSEMBLE`), une dissolution signée par un administrateur (`NOT_BY_THE_CREATOR`), la vraie avec un octet de signature changé (`FORGED`), celle d'un ancien créateur après son passage de main (`INCONSISTENT`) ; une trame changée en route ne s'ouvre même pas ; aucune ne finit l'opération ;
  - le répondeur : une fois par minute, jamais à lui-même, ni à une autre trace, ni à une autre salle ; une preuve de 16 passages de main tient dans une trame ; formats et octets abîmés ; une semaine à compter de la dissolution ;
  - les fils : 500 par fil, un message une fois, les fils non lus oubliés, le texte coupé comme il voyage, les octets relus à l'identique, le refus entier d'octets abîmés.
- `./gradlew :data:testDebugUnitTest --tests 'fr.toolmap.data.hierarchy.*'` : **39 tests, 0 échec** (27 avant ; `OperationTraceTest` 7, `DissolutionTracesTest` 5 ; `HierarchyOperationCheckTest` passe aux trois phases), avec des contrôleurs entiers, chacun son stockage et un Keystore logiciel, dans une salle en mémoire, et une horloge du test :
  - **le retardataire apprend la dissolution le jour 6, pas le jour 8** : jour 6, la trace du créateur est entendue (`endedByTrace`, tout effacé, une trace gardée jusqu'à la même date), puis le moment d'écoute finit seul au bout de deux minutes ; jour 8, la trace s'est effacée (fichier et clé), personne ne le lui dit, rien n'est resté sur le téléphone du créateur ;
  - après la dissolution, il ne reste sur chaque téléphone qu'**un fichier**, la trace, et sa clé : ni dossier, ni clé de l'opération ; la trace, en clair comme scellée, ne contient ni nom, ni message, ni position, ni objet ;
  - un membre retiré ne garde rien : ni dossier, ni messages, ni clé, ni trace, et la dissolution qui suit n'y change rien ;
  - **les messages relus après un redémarrage** (mêmes auteurs, textes et heures), aucune alerte relue, aucun message gardé deux fois quand toutes les trames d'un autre téléphone sont rejouées ; **520 messages : 500 gardés** (« Point 21 » à « Point 520 ») sur les deux téléphones ; **effacés à la dissolution** (plus de `messages.sealed`, rien dans la trace) **comme au retrait** ;
  - **une opération enregistrée par la 0.4.250** (fichier de messages retiré, pas de dossier de traces) se relit sans rien effacer ni rien signaler, puis garde les messages ;
  - le stockage des traces : scellée, sa clé à elle, hors des sauvegardes, une par opération, effacée au jour 7 la clé d'abord, abîmée ou sans clé refusée et effacée, clé sans fichier effacée, Keystore qui refuse sans rien laisser, `wipeAll` sans toucher un autre stockage, rien pour qui avait été retiré.
- Mutation de contrôle : le répondeur rendu muet, 4 tests de `:core` et le test du jour 6 passent au rouge.
- Build complet (`./gradlew check assembleDebug`, journal, session 117) : 2 200 tests, 0 échec ; 0.4.251. **Run « CI » n° 208** (commit 08827e3), émulateur API 34 de la CI : « OK: « Opération » controller (API 34) », trois phases `PASS` : création et enregistrement en 95 ms ; relecture dans un nouveau processus en 26 ms, « 3 messages in 2 threads », puis dissolution : « no folder and no key of the operation left; one trace of 782 bytes, kept 7 days, names nothing, holds no message » ; trace relue dans un autre processus (sa clé dans le Keystore), puis effacée sans rien laisser. Publication sautée, aucune release. Le run n° 209 a échoué par une course dans la relance de l'application des scripts de contrôle (l'ancien processus encore listé après l'arrêt), corrigée dans `hierarchy-keys.sh` et `hierarchy-operation.sh` (journal, session 117) ; **run n° 211** (commit 87de500) : vert, mêmes résultats.
- Chaque test vérifie qu'aucun signalement n'est un bogue (`Problems`), et `SilentFailureTest` est dans `./gradlew check`.

### Ce qui n'a pas pu être vérifié

- **Deux téléphones réels**, ni le vrai serveur de coordination avec le genre 7 (même forme JSON que les autres trames de contrôle, sous la même taille) ; la CI n'a qu'un émulateur.
- **Batterie et réseau des moments d'écoute**, écran éteint : rien n'est branché au service de partage, et rien n'est mesuré sur un téléphone ; les chiffres ci-dessus sont tirés des réglages du serveur.
- **Le contrôle sur émulateur** (`hierarchy-operation.sh`, trois phases) est passé sur l'émulateur API 34 de la CI (run n° 208, ci-dessus) ; sur l'émulateur du poste, par la session locale : `bash tools/emulator/smoke.sh app/build/outputs/apk/debug emulator-check`, puis lire « OK: « Opération » controller (API …) » et `emulator-check/hierarchy-operation.txt` (phases create, reload et trace `PASS`, « dissolved: no folder and no key of the operation left; one trace … », « trace read back in a new process », « trace wiped »).

### Limites connues

- **Un ancien créateur qui a passé la main** peut faire croire à la dissolution un retardataire qui n'a pas vu ce passage de main : sans le journal, l'absence d'une opération ne se prouve pas (même famille que les limites des §§ 6.4 et 15) ; un retardataire qui a vu le passage de main refuse (`INCONSISTENT`, vérifié).
- **Un passage de main qui a perdu contre un départ simultané** de son successeur peut faire refuser une vraie preuve par un retardataire qui n'a vu que le passage de main : il n'est pas prévenu (sans dommage, il reste dans une opération vide comme avant).
- **Un retardataire sur une époque plus ancienne que les 4 que garde la trace** n'est pas entendu par elle.
- **La date de la dissolution n'est pas signée** : elle ne fait que borner la durée des traces ; un téléphone ne prend jamais une date plus tardive que la sienne.
- **Un fil long** se réécrit entier à chaque message (au pire 500 messages de 1 024 octets par fil).
- **Une trace dépassée reste sur le disque, illisible sans sa clé**, jusqu'au prochain démarrage de l'application.
- Les limites des §§ 6.4, 8.6, 15, 16 et 17 restent.

## 21. Tests : simulation à grande échelle (P39.10, 2026-11-15)

Pour la session principale et pour les sessions parallèles (session 118, branche `claude/clever-babbage-9g4a0d`, partie de `claude/clever-edison-pi03p9`, 0.4.251 ; `.conception-privee` présent). Décision : ADR-132. Demande de l'utilisateur du 2026-11-15 : « une batterie de test sur le partage en opération », qui trouve ce que les scénarios écrits à la main ne trouvent pas, et corrige ce qu'elle trouve. Rien de visible ne change : ni écran, ni texte, ni ressource ; le partage « Entre amis » n'est pas touché. Les §§ 19, 20, 22 et 23 sont à d'autres sessions.

### Ce qui est simulé

- **Le serveur de coordination**, avec les règles du vrai (`FirebaseRelay`, `firebase/database.rules.json`, § 17) : une salle ; un battement toutes les 15 s ; la liaison vers un téléphone s'ouvre quand son dernier battement a 40 s au plus, se ferme après 75 s sans battement (évalué toutes les 5 s et à chaque battement) ; une trame dont le premier octet vaut 4 va dans la case de position de son auteur (300 octets, la dernière seulement, une écriture à la fois, la plus récente en attente part, une position attend pendant une coupure) ; toute autre trame est une trame de contrôle (4 500 octets au plus sur le serveur, 4 096 promis par l'opération), gardée jusqu'à ce que son auteur l'efface 120 s après (nettoyage toutes les 60 s tant qu'il est connecté), écartée à l'arrivée après 150 s ; une trame d'un téléphone dont la liaison n'est pas ouverte est écartée ; un téléphone qui se reconnecte reçoit la salle telle qu'elle est (battements, puis trames gardées et cases de position) ; un téléphone qui ferme son transport en ligne efface son nœud, les autres le voient partir.
- **Le réseau** : chaque envoi du serveur vers un téléphone a son délai (30 ms à 1,5 s, et 2 % de pointes jusqu'à 25 s), peut se perdre (4 %), arriver deux fois (3 %), et deux trames d'un même auteur peuvent arriver dans l'autre ordre (mode « rude ») ou non (mode « rude, ordre gardé », comme le vrai serveur) ; une écriture peut échouer (2 %).
- **Les téléphones** : les `OperationNode` réels, avec leurs vraies parties (copie, clés, noms, objets) ; leur stockage est écrit comme `StoredOperation` l'écrit (mêmes octets, par `HierarchySnapshot`, `forgetUnread` compris), 20 ms après chaque changement comme le fil du contrôleur ; coupures de 5 s à 2 h ; téléphone éteint de 1 h à 2 jours puis relu depuis son stockage ; application redémarrée proprement ou tuée (ce qui n'était pas encore écrit est perdu) ; application tuée juste après avoir écrit un changement ; téléphone perdu ; horloges décalées (70 % à ±90 s, 25 % à ±20 min, 5 % de 1 à 2 h). Un téléphone qui garde une trace de dissolution écoute la salle par moments de 2 minutes. Clés et hasard tirés de la graine : un même scénario rejoue au bit près.
- **Une série plus petite avec des `OperationController` entiers** (tests de `:data`, `OperationControllerSimulationTest`, `ControllerWorld`) : chacun son dossier sur le disque et son Keystore logiciel tiré de la graine, tué ou arrêté et redémarré sur le même dossier, coupé du réseau, sur un serveur simulé aux mêmes règles et une seule horloge virtuelle, les fils des contrôleurs passant à leur tour (exécuteur déterministe) ; deux graines (5 et 8 téléphones) : opérations, stockage relu après chaque redémarrage, fils gardés (R2), tout effacé à la fin pour qui sort, trace de dissolution et son annonce à un absent revenu (R1) ; plus la présence (un téléphone coupé passe hors ligne chez les autres en 100 s au plus, puis en ligne à son retour).

### Les scénarios

31 sortes d'étapes, tirées d'une graine (`Scenarios.generate`) : entrée avec une invitation (parfois une ancienne), admission ou refus, création, renommage et suppression de groupe, placement, remise en attente, chefs et adjoints (opération et groupes), administrateurs, retrait, départ, passage de main puis départ, dissolution (dans le dernier quart), renouvellement de l'invitation, positions, messages, rafales de 40 à 160 messages dans un fil, alertes et levées, croquis et waypoints posés, changés, retirés, coupures, absences, redémarrages, appli tuée juste après un changement, téléphone perdu, moments d'écoute des traces, attentes. Chaque étape a sa propre graine (les choix qu'elle fait et le bruit du réseau pendant elle) : retirer une étape ne change pas les autres, ce qui permet de **réduire un échec** (ddmin, `Scenarios.shrink`) au plus court scénario qui échoue encore de la même façon ; le compte rendu donne la graine, les étapes restantes et l'état de chaque téléphone.

### Ce qui est vérifié, une fois le réseau calmé

Calme : plus de pertes, délais courts, chaque téléphone encore dans l'opération rallumé et reconnecté, les traces à l'écoute ; jusqu'à quatre fois six minutes simulées.

1. **Convergence** : les téléphones qui sont membres de l'opération (dans l'état de celui qui détient le plus d'opérations) ont le même résumé, le même état (mêmes octets), les mêmes refus, la même époque et la même clé d'invitation ; un téléphone admis le sait.
2. **Lecture selon le § 4** : un oracle écrit depuis le texte de la spécification (`SimOracle` : qui attend, qui lit chaque canal, où chacun partage chaque sorte, où part son alerte, qui voit la position de qui, quels noms il peut connaître, comment un nom s'écrit) est comparé au moteur sur l'état final et toutes les dix étapes sur l'état d'un téléphone tiré au hasard.
3. **Tour de sondes** : chaque téléphone envoie sa position, un message dans chaque fil où il peut écrire, son alerte, un waypoint dans chaque canal où il peut en poser ; qui l'entend doit être exactement les lecteurs de l'oracle (rien qui manque, rien de trop) ; un téléphone retiré pendant son absence n'entend rien.
4. **Clés gardées** : chaque téléphone a un « jumeau modifié » qui garde toutes les clés de contenu et d'invitation qu'il a pu ouvrir, de toutes les trames que sa connexion a vues, sans jamais rien oublier (ce que garderait un téléphone modifié, même sorti, qui écoute encore la salle) ; aucun téléphone qui ne lit pas un canal ne détient sa clé du moment ; aucun téléphone parti ou retiré ne détient la clé d'invitation du moment. Côté téléphone honnête : clés seulement des canaux lus, une clé pour chaque canal d'unité lu, rien après la fin.
5. **Noms** : un téléphone qui a l'accès connaît tous les noms (ceux de qui attend : seulement le créateur et les administrateurs) ; qui attend ne connaît que le nom de l'opération et le sien.
6. **Objets** : tous les lecteurs d'un canal détiennent les mêmes enregistrements ; aucun objet d'un canal non lu.
7. **Trace R1** : chaque trace gardée ne contient aucun nom, aucun texte, aucune position, aucun corps d'objet, aucun sel de carte, aucune clé de contenu (cherchés octet par octet) ; un retardataire revenu pendant la semaine apprend la dissolution.
8. **R2** : au plus 500 messages par fil, jamais deux fois le même, aucun fil d'un canal non lu.
9. **Rien de signalé** : aucun signalement de bogue (`Problems`) ; parmi les signalements prévus, seuls ceux qu'un réseau honnête peut produire (un partage refusé parce qu'un changement de droits est arrivé avant lui, une remise de clé pour un membre pas encore vu, un objet d'un auteur pas encore vu, deux opérations sous un même numéro après une appli tuée) ; aucune trame lue alors que le téléphone ne lisait pas le canal ; aucune trame au-delà des tailles du serveur.
10. **Bornes** : files et tables de l'automate sous leurs plafonds, 16 clés par canal, 256 lots, 64 lots en attente, 1 024 objets par canal ; aucun appel de l'automate de plus de 4 s.

### Défauts trouvés et corrigés

Chacun dans un commit à lui (`[P39.10] Correction : …`), avec un scénario écrit à la main sur le même simulateur qui le montre (`OperationSimulationFindingsTest`), rouge avant la correction, vert après.

1. **Un arrivant admis après un renouvellement de l'invitation restait dehors pour toujours.** Trouvé par les graines 0x1, 0xb et 0xc (douze téléphones, réseau rude) ; dans la graine 0xb : P6 demande à entrer avec une invitation de l'époque 2 ; P3 part et P4 est retiré, la clé d'invitation passe à l'époque 3 ; P1 admet P6 (sa demande était gardée). L'opération qui l'admet voyage sous l'époque 3, que P6 n'a pas ; les membres lui redonnent bien la clé quand il parle sous l'ancienne (`grantTo`), mais `InviteKeys.open` refusait toute remise dont un destinataire n'est pas membre dans l'état du téléphone qui la reçoit, et P6 ne se savait pas encore membre : refus toutes les 10 s, pour toujours (216 en 27 minutes simulées), demande d'entrée répétée toutes les 20 s, nom jamais connu des autres. Arrive sans aucune panne de réseau : il suffit qu'un départ ait lieu entre la demande et l'admission. **Cause** : la vérification des destinataires comprenait le téléphone lui-même. **Correction** (`OperationWire.kt`, `InviteKeys.open`) : le téléphone qui reçoit n'a pas à être membre dans son propre état (les autres destinataires, si) ; rien n'est ouvert de plus : un membre peut déjà lui donner la clé autrement (§ 8.6). Test : « a phone let in after the invitation key was renewed learns it is in ».
2. **Deux renouvellements simultanés coupaient l'opération en deux pour toujours.** Graines 0xd et 0x15 : après un départ, le créateur renouvelle la clé d'invitation aussitôt, chaque administrateur 1,5 s par rang plus tard s'il n'a rien vu arriver ; sur un réseau plus lent que cela, ou quand le créateur n'est pas relié à cet administrateur à ce moment, deux clés différentes de la même époque partent, et chaque téléphone gardait la première reçue. Chaque moitié n'ouvrait plus les trames de l'autre (graine 0xd : 45 139 refus d'ouverture, deux journaux qui ne se rejoignent plus). **Cause** : `OperationNode.onGrant` ignorait toute remise pour une époque déjà détenue, quelle que soit sa clé. **Correction** (`OperationNode.kt`, ADR-132) : à époque égale, la clé au plus petit identifiant l'emporte partout ; un téléphone qui voit l'autre clé, ou une trame d'une époque détenue que sa clé n'ouvre pas, redonne la sienne à tous les membres sous l'époque d'avant (une fois par 10 s au plus et par époque). Test : « two renewals of the invitation key at once still leave one operation » (réseau à 2 s par trame).
3. **Un administrateur nommé pendant qu'un membre attend n'apprenait jamais son nom.** Celui qui attend envoie sa carte dans sa voie d'attente une seule fois, à son admission ; un administrateur nommé après (ou un nouveau créateur) devient lecteur de cette voie mais ne la recevait jamais, et les autres téléphones ne partagent jamais le nom de qui attend (§ 17, lecture 8) : il ne savait pas qui il plaçait (Q11 : « les administrateurs savent qui ils placent », § 8.8). De même pour un administrateur dont le téléphone était éteint au moment où la carte est partie (graine 0x4). **Correction** (`OperationNode.kt`) : quand la voie d'attente d'un téléphone gagne un lecteur, il y renvoie sa carte ; et quand le résumé d'un lecteur de sa voie dit qu'il lui manque des noms. Tests : « an administrator named while someone waits learns the name of who waits », « an administrator away when someone was let in learns the name of who waits when it is back ».
4. **Le renouvellement qui suit un départ se perdait si l'application redémarrait aussitôt.** Graine 0x8 : P0 passe la main à P4 et part ; l'application de P4 redémarre deux secondes plus tard. Le renouvellement était une tâche en mémoire, prévue pour la seconde suivante ; après le redémarrage, rien ne la relançait : P0, parti, gardait la clé d'invitation du moment (il suivait la forme de la hiérarchie, ce que le renouvellement doit empêcher, § 17). **Correction** (`OperationNode.kt`) : celui qui doit renouveler le premier (rang 0) le fait tout de suite, dans l'appel même qui lui apprend le départ (comme la clé d'une voie, déjà faite aussitôt). Reste : un administrateur de rang suivant qui redémarre pendant son délai, quand le premier n'a pas renouvelé (limite ci-dessous). Test : « the invitation key is renewed after a departure even when the creator's app restarts at once ». Un test de P39.9 (`DissolutionTraceTest`, « a member taken out keeps no trace… ») remettait au retiré les opérations scellées sous la clé du moment : elle est maintenant renouvelée sans lui, comme voulu ; le test les lui remet sous la clé qu'il détient encore (son intention ne change pas).
5. **Un téléphone coupé restait affiché en ligne jusqu'au changement suivant de l'opération** (relevé par la session des écrans, P39.8, et vérifié par la série des contrôleurs). Le contrôleur passait l'ouverture et la fermeture d'une liaison à l'automate sans publier sa vue : `OperationView.online` et `members[].online` ne bougeaient qu'au changement suivant. **Correction** (`OperationController.kt`) : la vue est publiée après chaque ouverture ou fermeture de liaison. Test : « a phone cut off shows offline on the others within a bounded time, then online again », rouge avant (« C cut off for 100 s: offline on A »), vert après.
6. **Un téléphone qui cessait un moment de lire une voie n'en retrouvait jamais les clés.** Graine 0x50ac004b (longue série, six téléphones, réseau calme) : P1, devenu créateur, rattrape des opérations une à une ; l'une place P5, qui attendait, la suivante le remet en attente. Dans l'état intermédiaire, P1 ne lit plus la voie d'attente de P5 et en oublie les clés (`KeyRing.prune`), mais en garde les lots vérifiés ; quand il la relit, avec les mêmes lecteurs, un lot déjà vérifié n'est jamais repris (doublon), et la clé avec laquelle les autres scellent dans cette voie (la plus petite des deux que P5 y avait faites) lui reste inconnue : il ne voyait plus la position de qui attend, ni rien de ce qui s'y partage. **Correction** (`HierarchyKeys.kt`, `KeyRing.restore`, appelée par `OperationNode` après chaque oubli) : les lots vérifiés faits pour les lecteurs du moment d'un canal qu'il lit, et qui lui sont destinés, sont rouverts quand leur clé manque ; rien ne s'ouvre de plus que ce qu'il pouvait déjà ouvrir. Test : « a phone that stops reading a lane for a moment holds its keys again when it reads it again » (la graine elle-même, 3,6 s), rouge avant (« position of P5: not on [P1] »), vert après.

Et un défaut de l'outillage des tests, pas du moteur : le run « CI » n° 223 a vu `OperationTraceTest` (P39.9, « … learns it from a trace on day 6 ») échouer une fois (jamais en local, 88 passages). Cause : le serveur en mémoire de ces tests (`MemoryRelay`) prévenait le téléphone déjà présent d'une nouvelle liaison avant le nouvel arrivant ; le premier pouvait écrire aussitôt, depuis son fil, et sa trame atteignait l'autre avant sa liaison ; le moment d'écoute de la trace répondait alors vers aucune liaison, et ne répondait plus à ce téléphone avant une minute (le vrai serveur ouvre toujours une liaison avant d'en livrer les trames). Corrigé dans `MemoryRelay` (les deux côtés prévenus sous un verrou, une trame qui arrive trop tôt attend la liaison) ; test : `MemoryRelayTest`, rouge avant, vert après.

### Cas non corrigés, et questions pour l'utilisateur

Ces cas changeraient une règle ou demanderaient un changement plus gros que la batterie : ils ne sont pas corrigés ; la simulation les reconnaît et les compte (« cas connus ») au lieu d'échouer.

**Cas A — un membre retiré pendant son absence continue d'inviter et d'admettre dans une copie morte.** Graine 0xc (douze téléphones, réseau rude), étapes 5 à 91 : P1, administrateur, perd le réseau 83 s ; P0 le retire pendant ce temps ; la clé d'invitation est renouvelée sans lui. P1 ne l'apprend jamais (limite connue, § 20 question 1) ; il continue d'admettre, avec ses invitations de l'époque 1, P6, P8 et P10, qui se croient membres d'une opération où P1 est administrateur ; l'opération réelle refuse leurs opérations (`NOT_A_MEMBER`) et ils ne lisent rien d'elle (vérifié : aucune clé du moment chez eux, rien entendu du tour de sondes). **Question 1. Faut-il prévenir un membre retiré pendant son absence ?** a. Oui : quand un téléphone l'entend parler sous l'ancienne clé, il lui renvoie, sous cette clé, l'opération qui l'a retiré (et rien d'autre) ; son application finit l'opération et le dit, ses invités l'apprennent à leur tour (recommandé ; ce qui passe sous l'ancienne clé ne dit que ce retrait). b. Non (ce qui est fait) : il reste dans une opération vide, et ceux qu'il invite aussi, jusqu'à ce qu'ils la quittent.

*Réponse de l'utilisateur, 2026-11-16* : a (M1). On renvoie au membre retiré hors réseau seulement l'avis de son retrait. À faire : P39.14.

**Cas B — un membre absent pendant plus de trois renouvellements ne revient jamais.** Graine 0x9 : P1 et P2 sont éteints des heures pendant que l'invitation est renouvelée quatre fois (un départ à chaque fois) ; chaque téléphone ne garde que les 4 dernières clés d'invitation (`OperationLinkState.KEPT_EPOCHS`, § 20) : plus personne n'ouvre leurs trames, et eux n'ouvrent plus rien. Ils restent dans l'opération sans rien recevoir, sans le savoir. De même pour qui demande à entrer : graine 0x1f (trente téléphones), P7 demande à entrer avec une invitation de l'époque 2 et n'est admis qu'une heure plus tard, à l'époque 6 ; il ne l'apprend jamais (il demande toujours), et P5, nommé administrateur ensuite, ne connaît pas son nom (seul celui qui attend le dit, dans sa voie d'attente ; P0, qui a gardé sa demande, le connaît).

**Cas C — un membre en retard refuse la clé du moment quand celui qui l'a annoncée a été nommé pendant une époque qu'il a manquée.** Graine 0x29 : P0 passe la main à P2, administrateur depuis peu, et part ; P2 renouvelle l'invitation. P1 et P7, restés à l'époque 2, reçoivent bien la clé du moment (`grantTo`), mais ne savent pas que P2 est créateur (le passage de main voyage sous l'époque 3, qu'ils n'ont pas) : ils la refusent (`NOT_ALLOWED`), toutes les 10 s, pour toujours.

**Question 2. Comment un membre absent longtemps revient-il ?** a. Garder les clés des 32 dernières époques (au lieu de 4 ; quelques centaines d'octets), et lui renvoyer sous son ancienne clé les opérations qu'il a manquées, quand il est membre : il revient tant qu'il n'y a pas eu plus de 31 renouvellements pendant son absence (recommandé ; un membre parti qui a gardé cette ancienne clé pourrait lire, pendant ce temps, la forme de la hiérarchie : ni nom, ni partage). b. Garder les clés de 32 époques seulement (cas B réglé, cas C non). c. Rien (ce qui est fait) : il faut lui redonner une invitation, il revient comme un membre neuf ; l'écran devra lui dire, quand il n'entend plus personne, de demander une invitation récente.

*Réponse de l'utilisateur, 2026-11-16* : a (M2). Un membre absent revient de lui-même tant qu'il a manqué moins de 32 renouvellements. À faire : P39.14.

**Cas D — deux opérations sous un même numéro après une application tuée.** Une opération part vers les autres avant que le stockage l'écrive (le contrôleur enregistre juste après) ; si l'application est tuée entre les deux, elle redémarre sans cette opération et peut écrire la suivante sous le même numéro. Les deux téléphones gardent alors la même des deux, partout (§ 6.1, signalé `CONFLICT_REPLACED`) : l'un des deux changements est perdu, sans refus à montrer. Vu une fois (graine 0xb). **Question 3. Faut-il enregistrer un changement avant de l'envoyer ?** a. Oui : l'automate attend que le contrôleur l'ait écrit (une écriture de plus avant chaque envoi d'un changement, quelques millisecondes) (recommandé). b. Non (ce qui est fait) : rare, et sans effet sur l'accord entre téléphones.

*Réponse de l'utilisateur, 2026-11-16* : a (M3). Enregistrer chaque changement avant de l'envoyer. À faire : P39.14.

**Cas E — nom de qui demande à entrer, pour un administrateur nommé après la demande** (relevé par la session des écrans, P39.8, § 19 de sa branche ; question L2 posée à l'utilisateur par la session principale) : la carte du nom d'une demande d'entrée n'est scellée que pour le créateur et les administrateurs que le demandeur connaît au moment où il l'envoie ; le demandeur la renvoie toutes les 20 s, et la refait dès qu'il apprend un nouvel administrateur (`sendJoin` quand les administrateurs changent), mais un administrateur nommé pendant que le demandeur ne reçoit plus le journal (renouvellement de l'invitation après sa demande) ne la lit pas. Non corrigé ici (en attente de la réponse à L2). À distinguer du défaut 3 ci-dessus, corrigé : il concerne un membre déjà admis, qui attend un groupe, et la carte qu'il envoie dans sa voie d'attente.

**Cas F — un départ perdu : le partant a tout effacé, les autres ne l'apprennent jamais.** Graine 0xd (douze téléphones, réseau rude) : P0, créateur, passe la main à P2 puis part (étape 78) ; ses deux opérations ne parviennent à personne (écriture perdue sur le serveur, ou livrées après que son nœud de la salle a été effacé, 5 s plus tard) ; son téléphone a déjà tout effacé. Pour tous les autres, P0 reste le créateur, pour toujours : plus personne ne peut retirer un membre, nommer ou retirer un administrateur, passer la main, dissoudre (comme un téléphone de créateur perdu, S7), et P2 ne devient jamais créateur. Le même sort attend tout départ fait sans réseau : l'opération part vers les liaisons ouvertes, il n'y en a aucune, le téléphone efface tout. **Question 4. Un départ doit-il attendre d'avoir été reçu ?** a. Oui : le téléphone qui part garde l'opération, sans rien montrer d'autre qu'« en train de partir », jusqu'à ce qu'un autre téléphone ait reçu son départ (au plus une semaine), puis efface tout (recommandé). b. Refuser « Quitter » et « Passer la main » sans réseau. c. Rien (ce qui est fait).

*Réponse de l'utilisateur, 2026-11-16* : a (M4). Le téléphone garde son départ jusqu'à ce qu'un autre l'ait reçu (« En train de partir », une semaine au plus). À faire : P39.14 (l'écran « En train de partir » passe d'abord par un aperçu).

**Cas G — la clé d'invitation neuve redonnée à un partant par un téléphone en retard.** Un téléphone qui n'a pas encore appris un départ peut, pendant qu'il se propage, redonner la clé du moment à celui qui part (en la redonnant à un membre resté sur une ancienne clé, ou à tous quand deux clés d'une même époque se croisent, ADR-132) ; s'il n'y a pas d'autre départ ensuite, le partant la garde, et suit la forme de la hiérarchie. Vu une fois dans le jeu fixe (graine 0xd, avant la correction complète du défaut 3 ; la même graine ne le montre plus), puis deux fois dans les 50 premiers scénarios de la longue série (graine 0x50ac0004, dix-huit téléphones, réseau qui réordonne : P15 est retiré à 1:11:44, l'invitation est renouvelée aussitôt sans lui ; un téléphone qui reçoit la clé neuve avant le retrait la lui redonne). C'est la limite connue « pendant qu'un changement se propage » (§ 8.6), appliquée à la clé d'invitation. **Question 5. Faut-il renouveler encore l'invitation quand on voit la clé du moment remise à quelqu'un qui est parti ?** a. Oui : un créateur ou un administrateur qui voit une remise de la clé du moment faite à un membre parti renouvelle l'invitation, à une époque au-dessus (recommandé ; un renouvellement de plus, rare). b. Non (ce qui est fait).

*Réponse de l'utilisateur, 2026-11-16* : a (M5). Un administrateur qui voit la nouvelle invitation arriver chez qui vient de partir renouvelle une fois de plus. À faire : P39.14.

**Cas H — le nom de qui attend, pour un administrateur nommé après la perte de son téléphone.** Graine 0x50ac000a (longue série, dix-huit téléphones) : P15, admis, attend un groupe ; son téléphone est perdu pour de bon (étape 118) ; P16 est nommé administrateur ensuite (étape 143) : il ne connaîtra jamais le nom de P15. Seul celui qui attend dit son nom, dans sa voie d'attente (§ 4.2) ; le créateur, qui le connaît, ne peut pas le redonner. Même chose pour un téléphone qui attend et ne peut plus parler (cas B, graine 0x1f). **Question 6. Le créateur et les administrateurs peuvent-ils redonner, dans la voie d'attente, la carte du nom de qui attend ?** a. Oui : un lecteur de cette voie qui a la carte la redonne quand un autre lecteur dit qu'il lui manque des noms (recommandé ; même voie, mêmes lecteurs : personne ne lit rien de plus ; la table du § 4.2 change pour cette seule case). b. Non (ce qui est fait) : l'administrateur voit un membre en attente sans nom.

*Réponse de l'utilisateur, 2026-11-16* : a, par la réponse à L2 (§ 19). À faire : P39.14.

**Cas I — qui est entré avec la clé d'invitation qui perd ne rejoint jamais les autres.** Vu deux fois dans les 100 premiers scénarios de la longue série. Graine 0x50ac0022 (longue série, dix-huit téléphones) : P0, coupé des autres, renouvelle l'invitation (époque 4, à 0:18) ; P1 la renouvelle aussi pour les autres (à 0:22) ; P13 demande à entrer avec une invitation de P8 qui porte la clé de P1, et P3 l'admet (0:23) avant d'apprendre, au retour de P0, que la clé de P0 l'emporte (ADR-132). La clé gagnante est redonnée sous l'époque d'avant, que P13 n'a jamais eue : il reste seul à l'époque 4, sans le savoir ; l'opération est dissoute à l'époque 9 et aucune trace ne lui ouvre ses trames (celles qui gardent une époque 4 ont l'autre clé). **Question 7. Faut-il garder, un temps, la clé qui perd d'une époque, pour prévenir qui est entré avec elle ?** a. Oui : chaque téléphone garde en mémoire la clé perdante d'une époque (jusqu'à l'époque suivante), ouvre avec elle les trames qui ne s'ouvrent pas avec la gagnante, et redonne la gagnante sous la perdante (recommandé ; une clé de plus par époque, rien de lisible en plus pour qui n'a ni l'une ni l'autre). b. Non (ce qui est fait) : il lui faut une nouvelle invitation.

*Réponse de l'utilisateur, 2026-11-16* : sans choix (M6), en attente. Ses mots : « Il devrait y avoir qu'une seul clés d'invitation. Et si un intru la trouve, il sera trié dans la fils d'attente. » La session principale lui pose une question de suivi (une clé changée par le créateur seul, ou une clé jamais changée, ce qui reviendrait sur R3) ; rien n'est fait d'ici là.

*Réponse de l'utilisateur, 2026-11-16 (tour 6, M6 1)* : « Une seule clé à la fois : seul le créateur la change, après chaque départ ». R3 A reste (la clé d'invitation change après chaque départ) ; seul le créateur la change, les administrateurs ne la changent plus en relais ; le créateur hors ligne, elle change à son retour. La règle de convergence (époque égale, plus petit identifiant gagnant, ADR-132) reste en filet de sécurité. En marche normale, il n'y a plus qu'une clé par époque. À faire : P39.14, avec son ADR.

### Coûts (5, 15 et 30 membres)

Mesurés par `OperationCostTest` (`-Ptoolmap.hierarchy.soak=true`, `core/build/hierarchy-cost.txt`) : l'opération construite (groupes de cinq avec un chef et un adjoint, un administrateur, le créateur chef de l'opération), puis 10 minutes simulées de positions toutes les 30 s, réseau calme ; trames et octets comptés à la sortie et à l'entrée de chaque téléphone, signatures, vérifications et accords de clé comptés par un fournisseur de cryptographie de test, temps de calcul du fil passé dans l'automate (JVM de la machine de session, pas un téléphone).

| Membres | Par téléphone, reçu | Par téléphone, envoyé | Vérifications | Calcul dans l'automate | Serveur, pour la salle (par minute) |
|---|---|---|---|---|---|
| 5 | 12,2 trames/min, ≈ 2,2 Ko/min | 3,0 trames/min, 520 à 570 o/min | 8/min | ≈ 7,5 ms/min | 10 positions, 5,3 trames de contrôle, 20 battements, 5 nettoyages |
| 15 | 42,7 trames/min, ≈ 7,8 Ko/min | 3,0 trames/min, 520 à 650 o/min | 28/min | ≈ 21 ms/min | 30 positions, 15,8 trames de contrôle, 60 battements, 15 nettoyages |
| 30 | 88,4 trames/min, ≈ 16 Ko/min | 3,0 trames/min, 520 à 725 o/min | 58/min | ≈ 48 ms/min | 60 positions, 31,4 trames de contrôle, 120 battements, 30 nettoyages |

- **Par position** : 1,00 signature, 1,00 vérification par téléphone qui l'entend (jamais deux), aucun accord de clé en régime établi ; temps passé dans les automates par position envoyée et entendue de tous : 3,8 ms à 5 membres, 10,6 ms à 15, 23,9 ms à 30 (≈ 0,8 ms par téléphone qui l'entend).
- **Par rôle** : en régime établi, avec des positions seulement, tous les rôles coûtent la même chose, à quelques octets près (le créateur et l'administrateur envoient ≈ 650 à 725 o/min au lieu de 520 à 570). Ce que reçoit chaque téléphone croît comme le nombre de membres : il entend la position de chacun.

### La longue série

Lancée dans la session : `./gradlew :core:test --tests 'fr.toolmap.core.hierarchy.sim.OperationSimulationTest' -Ptoolmap.hierarchy.soak=true`, trois scénarios à la fois, six tailles en alternance (5 téléphones et 80 étapes ; 8 et 120, ordre gardé ; 12 et 160 ; 6 et 100, réseau calme ; 18 et 160 sans positions ; 30 et 200 sans positions, ordre gardé). Arrêtée par la limite de temps de la session à **100 scénarios** (13 580 étapes, 103 h simulées, 1 571 s de calcul) : aucun signalement de bogue, aucune différence avec l'oracle ; cas connus reconnus : « epoch too old » 14, « announcer unknown » 16, nom de qui attend et ne peut plus parler 1. Échecs par catégorie (scénarios), presque tous dans les tailles de 18 et 30 téléphones :
- examinés : `names:missing` 8 (cas H ; le premier, graine 0x50ac000a), `closing:invite-key` 2 (cas G, 0x50ac0004), `dissolution:late-not-told` 1 (cas I, 0x50ac0022), `join:stuck` 1 (cas I encore, 0x50ac0035, trente téléphones : P10 et P0 renouvellent l'époque 5 à deux secondes d'écart, P22 entre avec l'invitation de P0, qui porte la clé qui perd, puis est admis et même nommé administrateur sans jamais l'apprendre), `time` 7 (scénarios de 30 téléphones au-delà de 180 s de temps réel, la machine faisant tourner trois scénarios et un build à la fois : borne du banc d'essai, pas de l'automate, dont aucun appel n'a dépassé 4 s) ;
- pas encore classé, graine donnée pour le rejouer : `converge:ops`, `converge:epoch` et `objects:diverge:UNIT` (un même scénario, 0x50ac003a, dix-huit téléphones) ; P2, créateur par un passage de main fait pendant qu'il était éteint 282 minutes, revient alors que les autres ont renouvelé l'invitation trois fois ; il ne rejoint que P1, retiré pendant sa propre absence (cas A), dont il reçoit l'époque 4 et les 37 opérations, et ne rattrape jamais l'opération réelle (62 opérations, époque 7) ; proche des cas A, B et C, mais le banc d'essai ne le reconnaît pas : à classer.
- examiné et corrigé : `deliver:missing:POSITION:WAITING` 1 (0x50ac004b, six téléphones, réseau calme) : défaut 6 ci-dessus.

Relancée après la correction du défaut 6, sur les mêmes 100 premières graines (13 580 étapes, 104 h simulées, 1 589 s) : `deliver:missing:POSITION:WAITING` a disparu ; `names:missing` 7, `closing:invite-key` 3, `time` 6, et les mêmes `dissolution:late-not-told`, `join:stuck` et divergence de 0x50ac003a ; aucun signalement de bogue.

Un scénario prend ≈ 13 s de calcul (plusieurs minutes pour 30 téléphones) : les 2 000 prévus demandent plus de sept heures sur cette machine.

### Relancer

- Le jeu fixe : `./gradlew :core:test --tests 'fr.toolmap.core.hierarchy.sim.*'` et `./gradlew :data:testDebugUnitTest --tests 'fr.toolmap.data.hierarchy.OperationControllerSimulationTest'` (dans `./gradlew check`).
- La longue série : `./gradlew :core:test --tests 'fr.toolmap.core.hierarchy.sim.*' -Ptoolmap.hierarchy.soak=true` (2 000 scénarios ; un nombre à la place de `true` en fixe le nombre) ; compte rendu dans `core/build/hierarchy-soak.txt` (échecs par catégorie, cas connus, le premier scénario de chaque catégorie réduit), coûts dans `core/build/hierarchy-cost.txt`. Dans cette session, elle a tourné avec 2 Go pour la JVM des tests, par prudence (trois scénarios à la fois ; un script d'initialisation Gradle hors du dépôt : `allprojects { tasks.withType(Test).configureEach { maxHeapSize = "2g" } }`, passé par `--init-script`).
- Un scénario précis : `./gradlew :core:test --tests '*OperationSimulationTest.explore*' -Ptoolmap.hierarchy.explore=<graine>:<téléphones>:<étapes>[:calm|:ordered][:nopos][:profile]` (plusieurs séparés par des virgules) ; compte rendu complet (étapes, état de chaque téléphone, signalements, échantillons de pile avec `profile`) dans `core/build/hierarchy-explore.txt`.

### Ce qui n'a pas pu être vérifié

- **Le vrai serveur et de vrais téléphones** : le serveur est simulé d'après le code du relais et les règles publiées (P39.12 fait l'essai de bout en bout) ; la batterie, le réseau mobile et l'écran éteint ne se simulent pas : les chiffres de coût ci-dessous sont des comptes (trames, octets, signatures), pas des mesures d'énergie.
- **Les écrans** (P39.8) : la simulation appelle l'automate et le contrôleur comme les écrans le feront, sans eux.
- **Un téléphone malveillant actif** (qui écrit ce qu'il ne devrait pas) : c'est P39.11 ; ici, le « jumeau modifié » ne fait qu'écouter et garder.
- **Plus de 30 membres, plusieurs niveaux de groupes** : les limites de départ seulement (30 membres, 6 groupes, un niveau), celles avec lesquelles toute opération est créée.

## 22. Tests : attaques et formats (P39.11, 2026-11-15)

Session 119 (session cloud, demande de l'utilisateur du 2026-11-15 : « une batterie de test sur le partage en opération »). Branche partie de `claude/clever-edison-pi03p9` (conception + R1/R2, 0.4.251). **Des tests seulement** : aucun écran, aucun texte visible, aucune ressource, aucun fichier du mode « Entre amis » (`:p2p`, `core/…/p2p/`, `app/…/share/`). Les §§ 19, 21 et 23 sont à d'autres sessions (P39.10, P39.12) ; ce paragraphe ne les écrit pas.

Chaque attaque est un test qui vérifie le résultat attendu par cette spécification : refus, signalement par `Problems.expected` (jamais un bogue, `SilentFailureTest` le garde), état inchangé, aucun plantage. Les tests partagent une salle simulée du serveur de coordination où chaque trame écrite arrive à chaque téléphone présent, une horloge du test, des clés de la plateforme, et des choix tirés d'un générateur à graines fixes (`AttackRoom`, `core/src/test/.../hierarchy/`).

### Ce qui est essayé, et le résultat

**Un membre malveillant à l'identité valide** (`MaliciousMemberTest`, `:core`) — son téléphone signe et envoie ce que l'application ne lui laisserait pas faire :

| Attaque | Résultat vérifié |
|---|---|
| Signer une opération que son rôle ne permet pas (un membre qui se nomme administrateur ou chef, crée ou supprime un groupe, retire un membre, dissout… ; seize changements) | Gardée partout (pour que tous aient les mêmes opérations), **refusée partout** (`FORBIDDEN`), état inchangé, opération toujours en marche |
| Un administrateur signe ce que seul le créateur peut ; un chef de groupe agit sur la hiérarchie | Refusé (`FORBIDDEN`), un seul administrateur, le créateur inchangé |
| Se remettre dans l'opération après un retrait (s'auto-admettre, rejouer sa propre admission, redemander l'entrée sous l'ancienne clé) | Rien ne le laisse entrer ; sa demande sous l'ancienne clé d'invitation est écartée (`join.old_key`) ; il ne lit plus rien de la suite |
| Écrire un message ou une alerte au nom d'un autre membre (scellé avec la vraie clé du canal, signé de sa propre clé) | Refusé par chaque lecteur (`FORGED` / `share.refused`), rien n'est lu |
| Écrire dans un canal qu'il ne lit pas (le groupe d'un autre, la voie des chefs, la voie montante) | `NOT_ALLOWED` à l'envoi ; une trame bien scellée n'atteint aucun lecteur du canal visé |
| Donner les lots de clés d'un canal à un téléphone qui ne doit pas l'avoir | Le téléphone ne lit toujours rien : aucune copie n'est enveloppée pour lui (vérifié même en capturant tous les lots et toutes les clés d'invitation) |
| Envoyer des trames géantes (taille maximale du format, chaque genre) et en rafale (500 copies d'un partage) | Aucun plantage, état inchangé, un message rejoué compté une fois, l'opération marche encore après |
| Lire les noms ou les positions qu'il ne doit pas voir (membre en attente) | Aucune position, aucun message, aucun nom sauf celui de l'opération ; vérifié sur les octets qu'il détient (opérations, état) |

**Un ancien invité et un téléphone volé** (`MaliciousMemberTest`) : une invitation renouvelée avant d'être utilisée ne laisse plus entrer (la demande voyage sous l'ancienne époque, écartée) ; un téléphone retiré qui a gardé toutes ses clés de contenu et d'invitation n'ouvre aucun partage écrit après son retrait (la clé d'invitation a changé, les clés de contenu ont été refaites sans lui).

**Un serveur de coordination menteur** (`LyingServerTest`, `:core`) — la salle livre ce qu'un serveur hostile ferait :

| Mensonge | Résultat vérifié |
|---|---|
| Perdre, retarder, réordonner, doubler des trames (au hasard, graines fixes) | Tous les téléphones convergent (même état) une fois le rattrapage fait, et travaillent encore |
| Rejouer et tronquer chaque trame d'opérations à de nombreux points | Rien ne change, aucun plantage |
| Injecter des milliers de trames au hasard, et des enveloppes bien formées d'une autre clé d'invitation | Refusées (ne s'ouvrent pas), comptées, aucun bogue, état inchangé |
| Mélanger les trames de deux opérations | Chaque opération garde son état ; aucune n'apprend un membre de l'autre |
| Mélanger deux salles d'une même opération, ou d'opérations étrangères | Rien n'est lu de travers, aucun membre étranger n'entre |
| Relier les identifiants d'un téléphone entre deux opérations | Impossible : une identité par opération, le même téléphone écrit sous deux identifiants sans rapport |

**Ce que le serveur apprend des métadonnées, dit honnêtement** (`LyingServerTest`) : il voit, pour chaque trame, l'identifiant de membre qui l'écrit (propre à l'opération) et si elle va dans la case de position. Il ne voit **aucun nom** ni **aucune coordonnée en clair** (vérifié sur tous les octets de la salle, le nom de l'opération et une latitude très précise cherchés octet par octet). Un téléphone qui garde une trace parle sous un identifiant tiré au hasard, pas son identifiant de membre (§ 20). Il ne peut pas relier un téléphone d'une opération à l'autre.

**Fuzzing des décodeurs** (`HierarchyFuzzTest`, `Fuzzing.kt`, `:core`) : octets au hasard et mutations (octet changé, inséré, tronqué, run poussé au maximum, tout au hasard) d'un corpus de trames valides, pour chaque décodeur du mode — `HierarchyWire` (opération, bloc, résumé, état, lot de clés), `OperationWire` (enveloppe, résumé, demande d'entrée, clé d'invitation, état de liaison, charges utiles, invitation en lien et en QR), cartes de noms, `HierarchySnapshot` (les cinq parties scellées), `DissolutionProof`/`Notice`/`Trace`. Vérifié : **aucune exception non attrapée** (seules `WireException`, et `IllegalArgumentException` pour les décodeurs de texte Base45/Base64, sont tolérées), **temps borné** (aucun décodage au-delà de 2 s), **aucune grosse allocation tirée d'une longueur annoncée** (une entrée courte qui déclare un compte au plafond n'agrandit pas le tas de plus de 2 Mo).

**Stockage abîmé, tronqué, échangé** (`StorageAttackTest`, `:data`, sur un coffre logiciel) : les fichiers scellés ajoutés par P39.7 et P39.9 (`link.sealed`, `messages.sealed`) abîmés, tronqués, absents ou échangés entre deux opérations — refus propre (lu vide ou `null`, signalé, jamais de travers), l'opération se charge quand même ; un fichier de messages absent (opération de la 0.4.250) se lit vide **sans rien signaler** ; une trace échangée entre deux opérations est refusée (son sceau est lié à l'identifiant du fichier) et effacée ; un fuzzing seedé corrompt au hasard tous les fichiers d'une opération (les quatre parties, l'identité, la clé de stockage, le lien, les messages) : `open`/`load`/`loadLink`/`loadMessages` ne lèvent jamais rien, ne lisent rien d'une autre opération, aucun bogue. Les quatre parties, l'identité, la clé de stockage et les traces étaient déjà attaquées par `StoredOperationTest` et `DissolutionTracesTest` (P39.6, P39.9).

### Défaut trouvé et corrigé

- **Une grosse allocation tirée d'une longueur annoncée, dans `HierarchySnapshot`** (ADR-133). Cause : les parties relues construisaient leurs listes par `List(count) { … }`, qui **pré-dimensionne** le tableau au compte lu avant de lire le premier élément. Plafond `MAX_COUNT = 2²⁰`, donc une entrée de 6 octets déclarant 2²⁰ faisait allouer ≈ 5 Mo (mesuré) avant l'échec « tronqué ». Correction (commit à lui, `[P39.11] Correction : …`) : un `readList(r) { … }` qui agrandit un `ArrayList` au fil des éléments réellement lus, borné par la taille de l'entrée ; même résultat pour toute entrée valide. Fichier : `core/src/main/kotlin/fr/toolmap/core/hierarchy/HierarchySnapshot.kt`. Test qui le montre : `HierarchyFuzzTest`, « a short input with a huge announced length is refused without a big allocation » (il échoue si un décodeur pré-dimensionne de nouveau, vérifié en réintroduisant la faute). **Gravité réelle faible** : ces fichiers sont scellés par le téléphone, un tiers ne peut pas y mettre d'octets arbitraires (l'authentification AES-GCM échoue d'abord) ; le cas n'est atteint que par une vraie corruption du disque, déjà refusée et rattrapée par le réseau.

Aucun autre défaut n'a été trouvé : les décodeurs des trames (adversariales) bornent leurs comptes au plus juste ou lisent par `ByteReader.bytes(n)`, qui vérifie la place avant d'allouer.

### Risques qui restent (dits honnêtement)

- **Antidatage d'un administrateur retiré** (§ 6.4) : un administrateur retiré qui a gardé la clé d'invitation de l'époque d'avant peut signer, après coup, une admission et un placement datés **avant** son propre retrait ; ils s'appliquent sur tous les téléphones (leur auteur avait le droit à ce point de l'ordre), et un second téléphone à lui entre ainsi dans l'opération. Un test le **fixe** (`MaliciousMemberTest`, « known limit — a removed administrator antedates… ») : il montre ce que l'attaque obtient aujourd'hui, pour que le jour où un point de reprise (§ 6.4) ferme le cas, le test le dise. Non corrigé ici : la parade (point de reprise, état signé qui remplace le passé) est un gros chantier qui changerait le moteur.
- **Un membre placé malveillant recopie ce qu'il lit** : aucune cryptographie ne l'empêche (§ 8.6). Hors de portée d'un test de format.
- **Rejeu d'un partage encore valable** : une trame captée et renvoyée reste valable tant que son auteur a le droit ; le branchement doit contrôler la date ou le numéro dans la charge utile (§ 8.6), ce que les tests du serveur menteur vérifient pour les positions (écartées si plus anciennes), les messages et les alertes (écartés si déjà vus ou de plus de 6 h).
- **La longueur `MAX_COUNT` de `HierarchySnapshot`** reste un garde-fou large ; `readList` la rend sans effet sur l'allocation, mais un fichier scellé légitime de plusieurs Mo se relit toujours (ce qui est voulu).

### Cas non corrigés et questions pour l'utilisateur

Aucun cas trouvé par cette batterie n'exige une décision de l'utilisateur : le seul défaut (allocation) est corrigé, et la seule limite (antidatage) est déjà connue (§ 6.4) et déjà posée en question ailleurs. Rien de nouveau à trancher.

### Comment relancer le long fuzzing

Le fuzzing seedé rapide tourne dans `./gradlew check` (graines fixes, environ 7 s pour `:core` et 3 s pour `:data`, bien sous la limite de 90 s). La longue série passe derrière une propriété :

```bash
./gradlew :core:test :data:testDebugUnitTest --tests '*HierarchyFuzzTest' --tests '*StorageAttackTest' -Ptoolmap.hierarchy.fuzz=true
```

Elle a été **lancée dans la session 119** : son résultat est consigné au journal (session 119).

### Ce qui n'a pas pu être vérifié

- Deux téléphones réels et le vrai serveur de coordination : la salle est simulée (JVM), sans réseau. Le comportement d'un serveur Firebase réel face à ces trames (déjà conforme aux règles publiées : même forme JSON, salle de 32 caractères hexadécimaux, nom vide dans le battement) reste à voir en vrai (P39.12).
- La batterie est en JVM pure : rien sur émulateur (aucun écran, aucun code d'application n'est touché). Le contrôle sur émulateur de la CI reste celui des §§ 16, 17 et 20.
- Le temps et la mémoire sont mesurés sur la JVM de la machine de build, pas sur un téléphone.

### Blocage CI connu : un test instable de P39.9 (à trancher par l'utilisateur)

La batterie elle-même est verte (en local et dans `./gradlew check`). Mais le run « CI » de la branche de P39.11 est **rouge**, et toujours sur **un seul test, qui n'est pas de P39.11** : `OperationTraceTest` (P39.9, `data/src/test/.../hierarchy/`, commit 08827e3), le test « a phone away at the dissolution learns it from a trace on day 6 ».

- **Ce qui se passe** : sur l'émulateur partagé de la CI (2 cœurs, chargé), une assertion **sans message** de ce test échoue (`java.lang.AssertionError at OperationTraceTest.kt:148`). Le test fait revenir un téléphone absent, attend qu'il apprenne la dissolution par la trace d'un autre dans la salle, puis vérifie, entre autres sans message : qu'aucun `operation.dissolution.refused` n'a été signalé, et que le téléphone qui garde la trace est présent dans la salle.
- **Ce qui devrait se passer** : le test passe, comme en local.
- **Reproductibilité** : **jamais reproduit en local** — 18 exécutions (8 de la classe seule, 6 de tout `:data`, 4 sous forte charge CPU avec huit processus `yes`), toutes vertes. Il est passé deux fois sur la CI de P39.9 (runs n° 208 et 211). Il échoue sur la CI de P39.11 (runs n° 215 et 217). C'est donc une **instabilité de synchronisation propre à la contention de l'émulateur de la CI**, sensible au tour de rôle des tâches postées entre deux contrôleurs sur leurs exécuteurs à un seul fil.
- **Lien avec P39.11** : aucun connu. P39.11 ne modifie pas `OperationTraceTest` ni `OperationController` ; le seul changement du moteur de cette session (`HierarchySnapshot.readList`, ADR-133) donne le même résultat au bit près et ne touche pas le chemin de la trace. Une tentative de durcir le `settle` du test (boucle de drainage de plus) n'a **pas** corrigé le défaut (run n° 217 encore rouge) ; elle a été **annulée**, le test de P39.9 laissé intact.
- **Gravité** : le code du mode « Opération » n'est pas en cause (le défaut est dans l'ordonnancement du test, ou au plus un `operation.dissolution.refused` transitoire et sans effet pendant l'échange). Mais la condition « CI verte » de la tâche P39.11 n'est pas remplie tant qu'il échoue.

**Question pour l'utilisateur** (ce test est à P39.9 / la session principale ; cette session ne le réécrit pas de force) :

1. **La session principale (ou P39.9) rend `OperationTraceTest` « jour 6 » déterministe** (recommandé) : attendre l'échange de la salle par une condition (par ex. sonder jusqu'à `endedByTrace`, borné), au lieu d'un nombre fixe de tours de `settle` ; et, si un `operation.dissolution.refused` transitoire est réellement émis pendant l'échange, décider s'il faut l'éviter côté transport ou le tolérer dans l'assertion. Garde la CI exigeante.
2. **Cette session tente un correctif plus poussé du test**, sans pouvoir le vérifier en local (il n'y échoue jamais) : risque de ne pas corriger le vrai défaut, et de toucher un fichier d'une autre tâche en cours.
3. **Accepter l'instabilité pour l'instant** et fusionner P39.11 quand un run « CI » passe (le défaut est à ~50 % ; non recommandé : contraire à la règle « un test instable se corrige, il ne se relance pas »).

## 23. Tests : bout en bout sur le vrai serveur (P39.12, 2026-11-15)

Pour la session principale (session 120, branche `claude/wizardly-noether-wcttgl`, partie de `claude/clever-edison-pi03p9` a4d1b8d, 0.4.251 : la branche de conception plus P39.9 ; `.conception-privee` présent). Décision d'architecture : ADR-134. Les §§ 19 à 22 sont aux sessions parallèles (écrans, simulation, attaques) ; ce paragraphe ne les écrit pas. Rien de visible ne change ; le partage « Entre amis » n'est pas touché (aucun fichier de `:p2p`, de `core/…/p2p/` ni de `app/…/share/`).

### Ce qui est construit

- **`:data`, paquet `fr.toolmap.data.hierarchy`** : `OperationEndToEnd` (le point d'entrée `dumpsys … operation-e2e`, outils de debug seulement, et `EndToEndSession`, les commandes au-dessus d'un `OperationController`) ; `TrafficMeter` (compteurs : appels au serveur par sorte, octets des corps, trames par genre). Test JVM : `OperationEndToEndTest` (le scénario du script sur deux téléphones d'une salle en mémoire).
- **`:app`** : une ligne d'aiguillage dans `MainActivity.dump` (`BuildConfig.DEV_TOOLS`), qui donne à l'essai le relais Firebase d'« Entre amis » tel qu'il est (`FirebaseRelayFactory`, son client OkHttp avec le compteur, une identité anonyme à l'essai) et le serveur intégré à l'APK.
- **Outils** : `tools/emulator/operation-e2e.sh` (le scénario, deux appareils), `tools/emulator/operation-e2e-measures.py` (le tableau des mesures) ; `.github/workflows/operation-e2e.yml` (deux émulateurs API 34, vrai serveur).

### Le scénario

A et B sont deux émulateurs de la même machine ; « Exercice », « Alpha », « Bravo », « Nord » sont des noms fictifs ; les positions sont la place fictive des tests (45,2° N, 5,7° E, un pas de 0,0001° par position). Chaque ligne est une étape du script, avec ce qu'elle doit rendre ; le script s'arrête à la première qui ne le rend pas.

| Étape | Qui | Ce qui se passe | Résultat attendu |
|---|---|---|---|
| 1.create | A | crée l'opération, sa salle sur le vrai serveur | `STARTED`, serveur `ONLINE`, A peut inviter |
| 1.join, 1.request-seen | B, A | B lit l'invitation (venue de A par adb) et demande à entrer | B lit le nom de l'opération, serveur `ONLINE` ; A voit la demande **avec le nom de B** (R5) |
| 1.admit, 1.waiting | A, B | A admet B | B en attente (`WAITING`) |
| 1.organize, 1.placed | A, B | A crée le groupe « Nord », y place B, le nomme chef du groupe, se nomme chef de l'opération | B placé, lit le nom du groupe et celui de A ; son alerte part aux chefs (`LEADERS`) |
| 2.positions | B, A | B envoie 5 positions, une toutes les 2 s | A reçoit la dernière (et les autres sauf celles que le serveur a remplacées) |
| 3.messages | A, B | un message dans chaque fil où chacun écrit | l'autre reçoit ceux des fils qu'il lit, **et aucun autre** (3 s de plus pour s'en assurer) |
| 3.alert | A, B | une alerte chacun, puis levée | l'autre la reçoit (selon le rang : A, chef de l'opération, à tous ; B, chef de groupe, aux chefs), puis ne la voit plus |
| 3.waypoint | A, B | un waypoint dans un lieu que B lit, puis retiré | B le voit, puis ne le voit plus |
| (une minute) | A, B | rien n'est partagé | ce que coûtent deux téléphones placés, seuls |
| 4.reopen, 4.caught-up | A | l'application de A est tuée ; B met un waypoint ; A redémarre et rouvre l'opération | A en ligne, avec le waypoint de B |
| 4.cut … 4.waypoint-in | B, A | B en mode avion 40 s ; A renomme le groupe et met un waypoint ; B revient | B voit le serveur injoignable, puis de nouveau en ligne, le nouveau nom et le waypoint |
| 5.leave, 5.departure-seen | B, A | B quitte l'opération | B : fin (`OUT`), plus rien de gardé ; A : B parti, **clé d'invitation renouvelée** (R3) |
| 5.join-old, 5.old-invitation | B, A | B (nouvelle identité) redemande avec l'ancienne invitation | A ne voit aucune demande ; ses demandes arrivent et sont écartées (`join.old_key`) |
| 6.join … 6.waiting | B, A | B revient avec une nouvelle invitation ; A l'admet | B en attente |
| 6.cut, 6.dissolve | B, A | B en mode avion ; A dissout | A : fin (`DISSOLVED`), une trace gardée 7 jours |
| 6.back-online | B | B revient, seul dans la salle (A l'a quittée, ses trames avec lui) | serveur `ONLINE` |
| 6.announce, 6.ended-by-trace | A, B | l'application de A redémarre et vient au premier plan (`announceDissolutions`) | B apprend la dissolution **par la trace** (R1) et garde à son tour une trace |
| 6.window-over | A | le moment d'écoute de la trace (2 min) | fini de lui-même ; A quitte la salle |

À la fin, quoi qu'il arrive, les deux applications quittent la salle (`reset`) et le mode avion est coupé. Le script vérifie aussi qu'aucune des deux n'a planté (Java, natif) ni bloqué (ANR).

### Ce qui est mesuré, et comment

- **Latence de chaque étape** : temps rendu par l'application (`…Ms` : durée sur l'appareil ; `…AtMs` : heure de l'appareil) et temps d'attente du script. Entre les deux appareils, la latence est l'heure de réception chez l'un moins l'heure d'envoi chez l'autre, corrigée de l'écart de leurs horloges (lu par adb, au mieux de cinq essais, avec sa marge).
- **Octets** : les corps HTTP des appels au serveur, en montée et en descente, comptés sur le fil (avant décompression, sans en-têtes ni TLS) par un intercepteur réseau du client OkHttp du relais ; et tout le trafic de l'application (`TrafficStats`, TLS compris, les tuiles de la carte aussi).
- **Écritures sur le serveur** : chaque appel qui n'est pas une lecture (présence, position, trame de contrôle, nettoyage, départ), par appareil, par tronçon et par minute ; les trames de l'opération par genre (opérations, résumé, lot de clés, partage, demande d'entrée, clé d'invitation, dissolution, position).
- Les relevés sont dans l'artefact du run (`operation-e2e/` : `steps.txt`, `counters.txt`, `measures.txt`, et les lignes logcat de l'application, filtrées et nettoyées) et dans le journal du job.

### Résultats

**Sur la JVM** (salle en mémoire, `OperationEndToEndTest`, 2 tests) : le scénario du script passe en entier, sauf la coupure de réseau (étape 4, sans réseau à couper) et l'attente des deux minutes du moment d'écoute. Deux lectures en sont sorties, sans défaut :

- **Deux positions dans la même seconde** : la seconde est écartée comme un rejeu (`position.old` : l'heure d'une position est en secondes, et une position qui n'est pas plus récente que la dernière est refusée). Un GPS donne au plus une position par seconde ; le script les espace de 2 s.
- **À la dissolution, la vue dit « fini » un instant avant de dire la trace** (`phase = ENDED` publiée par l'appel, puis `traceKeptUntilMs` à la fin de l'effacement) : un écran qui lit la trace doit suivre la vue (`StateFlow`), pas la première valeur `ENDED`. L'essai attend donc la trace quelques secondes après la fin.

**Sur le vrai serveur, entre deux émulateurs : toutes les étapes passées**, au premier run (run n° 1 du workflow « Opération, bout en bout », 43763114261, commit b03b57f, 0.4.252, 2026-11-15 ; deux émulateurs Android 14, API 34, sur la même machine de la CI, 4 cœurs et 16 Go, démarrés en 72 s ; scénario de 5 min 47 s du premier `reset` au dernier contrôle, dont 2 min de moment d'écoute de la trace). Écart des horloges A − B : −56 ms, lu à 104 ms près : les latences entre les deux appareils sont corrigées de cet écart, à ± 52 ms. « En ligne » : l'état `ONLINE` du relais (identité anonyme, heure du serveur, flux de la salle ouvert).

| Étape | Résultat | Temps |
|---|---|---|
| 1.create (A) | `STARTED`, en ligne, peut inviter | 159 ms pour créer et garder ; 1,48 s jusqu'à « en ligne » |
| 1.join (B) | invitation lue, nom de l'opération lu, `ASKING`, en ligne | 1,98 s jusqu'à « en ligne » |
| 1.request-seen (A) | la demande, **avec le nom de B** (R5) | 3,6 s après le début de l'attente de A, soit environ 1,5 s après que B est en ligne |
| 1.admit → 1.waiting | B en attente, sa position vers sa voie d'attente | 190 ms |
| 1.organize → 1.placed | B placé, lit le nom du groupe et celui de A ; son alerte part aux chefs | 1,2 s (groupe, placement, deux postes, noms, clés) |
| 2.positions | 5 sur 5 reçues, dans le canal du groupe | 166, 170, 180, 167, 218 ms |
| 3.messages A → B | A écrit dans 2 fils (l'opération, le commandement du groupe), B lit les deux : 2 reçus | 141 ms (l'opération), 406 ms (le commandement : clé de la voie faite à la demande) |
| 3.messages B → A | B écrit dans 2 fils (son groupe, le commandement), A ne lit que le commandement : 1 reçu, **aucun autre** pendant 3 s de plus | 294 ms |
| 3.alert A → B | dans l'opération (A, chef de l'opération : tous les membres placés) | 131 ms ; levée vue en 132 ms |
| 3.alert B → A | dans la voie des chefs (B, chef de groupe) | 314 ms ; levée vue en 197 ms |
| 3.waypoint A → B | dans l'opération | 148 ms ; retiré, vu en 163 ms |
| 4.reopen, 4.caught-up (A) | l'application de A tuée ; B met un waypoint ; A relancée : opération rouverte, en ligne, le waypoint de B déjà là | 200 ms pour rouvrir, 1,7 s jusqu'à « en ligne », 5,0 s depuis le lancement de l'application |
| 4.cut (B) | mode avion : serveur injoignable | 560 ms |
| 4.back-online … 4.waypoint-in (B) | après 40 s sans réseau : de nouveau en ligne, le nouveau nom du groupe et le waypoint de A rattrapés aussitôt | **8,0 s** après le retour du réseau pour être en ligne (défaut 2), puis 1 ms et 0 ms |
| 5.leave → 5.departure-seen | B : fin `OUT` ; A : B parti, clé d'invitation renouvelée | **3,4 s** (défaut 1) |
| 5.join-old → 5.old-invitation | B (identité neuve) avec l'ancienne invitation : en ligne, `ASKING` ; A : aucune demande montrée en 45 s, **5 demandes écartées** (`join.old_key`) | 45 s |
| 6.join → 6.request-seen | nouvelle invitation : la demande avec le nom | 2,0 s |
| 6.admit → 6.waiting | B en attente | 201 ms |
| 6.cut, 6.dissolve | B injoignable en 155 ms ; A : `DISSOLVED`, une trace gardée 7 jours, plus aucune opération gardée | 55 ms |
| 6.back-online (B) | seul dans la salle (A l'a quittée, ses trames avec lui) | 3,5 s |
| 6.announce → 6.ended-by-trace | A (application relancée, au premier plan) annonce 1 trace ; B : `DISSOLVED` **par la trace** (R1), et garde la sienne | 1,2 s |
| 6.window-over (A) | moment d'écoute fini de lui-même, A quitte la salle | 120 s |

**Run n° 2** (43766232396, commit 30e21bc, 0.4.253, le même soir) : **toutes les étapes passées de nouveau**, aux mêmes temps à quelques dizaines de millisecondes près (positions 157 à 179 ms ; messages 181 et 481 ms de A vers B, 304 ms de B vers A ; alertes 168 et 323 ms, levées 218 et 185 ms ; waypoint 227 ms, retiré 211 ms ; admission 294 ms, placement 1,4 s ; de nouveau en ligne 8,0 s après la coupure ; départ vu en 3,4 s ; dissolution par la trace 1,2 s). Ce qu'il montre en plus : **renouvellement vérifié** (époque 1 à l'entrée de B, 2 après son départ, 0,6 s après que A l'a vu partir ; la nouvelle invitation porte l'époque 2) ; le départ de B efface son stockage en 54 ms ; **aucune réponse du serveur autre qu'un succès**, sur les deux appareils ; l'attente d'une trame avant son écriture (défaut 1).

**Run n° 3** (43769156127, commit f3e652c, 0.4.253) : **toutes les étapes passées une troisième fois** ; les compteurs relevés juste avant chaque relance donnent enfin le total de l'essai (5 min 38 s de scénario, deux téléphones) : **A 97 écritures** (24 battements, 71 trames de contrôle ; 46 Ko de corps envoyés, 128 Ko reçus), **B 108** (16 battements, 5 positions, 84 trames de contrôle ; 44 Ko envoyés, 235 Ko reçus) ; pour toute l'application, 203 et 222 Ko envoyés, 257 et 555 Ko reçus (dont environ 290 Ko de tuiles de la carte au démarrage de B). Sur les 155 trames de contrôle écrites, **52 sont des lots de clés** (19 de A, 33 de B), 32 des opérations, 30 des partages, 27 des résumés, 12 des demandes d'entrée ; la rafale du défaut 1 se retrouve (B après sa reconnexion : attente moyenne 1,35 s, 36 écritures plus d'une seconde ; A relancé : 1,30 s, 13).

Le renouvellement de la clé d'invitation (R3) est prouvé au run n° 1 par les demandes écartées de l'étape 5 (une demande n'est écartée ainsi que sous une clé plus ancienne que celle du moment), pas par l'étape 5.departure-seen : l'application de A ayant été relancée à l'étape 4, l'époque qu'elle comparait était perdue, et la vérification passait d'elle-même (défaut de l'essai, corrigé : l'époque est lue au début de l'attente, et le script compare l'époque de A au moment de l'entrée de B et à celui de la nouvelle invitation).

**Mesures du run n° 1** (corps HTTP : sans en-têtes ni TLS ; « appli » : tout le trafic de l'application d'après `TrafficStats`, TLS, en-têtes et tuiles de la carte compris) :

| Tronçon | Appareil | Durée | Écritures | dont présence / position / contrôle | Corps ↑ / ↓ | Appli ↑ / ↓ |
|---|---|---|---|---|---|---|
| création, entrée, placement | A | 10 s | 19 | 1 / 0 / 18 | 8,2 / 15,6 Ko | 40,8 / 38,4 Ko |
| création, entrée, placement | B | 10 s | 12 | 1 / 0 / 11 | 3,7 / 15,3 Ko | 34,3 / 295,8 Ko (tuiles de la carte) |
| 5 positions | B | 9 s | 6 | 1 / 5 / 0 | 1,5 / 1,9 Ko | 9,3 / 6,1 Ko |
| fils, alertes, waypoint | A | 12 s | 9 | 1 / 0 / 8 | 2,7 / 5,6 Ko | 14,7 / 10,2 Ko |
| fils, alertes, waypoint | B | 12 s | 5 | 0 / 0 / 5 | 1,6 / 5,5 Ko | 8,8 / 8,8 Ko |
| **une minute sans partage** | A | 60 s | **5** | 4 / 0 / 1 | 392 / 1 396 o | 7,0 / 4,4 Ko |
| **une minute sans partage** | B | 60 s | **5** | 4 / 0 / 1 | 416 / 1 396 o | 7,1 / 4,1 Ko |
| moment d'écoute de la trace (R1) | A | 123 s | 11 | 9 / 0 / 1 | 1,6 / 6,2 Ko | — (application relancée juste avant) |

- **Deux téléphones placés qui ne partagent rien : 5 écritures par minute et par appareil** (un battement de présence toutes les 15 s, un résumé par minute), soit 300 par heure ; 0,4 Ko de corps envoyés et 1,4 Ko reçus par minute (le flux de la salle porte les écritures des deux), mais **7 Ko envoyés par minute pour toute l'application** : chaque écriture porte dans son adresse le jeton du compte anonyme (`?auth=…`, environ 1 Ko), comme pour « Entre amis » (`FirebaseRest`), ce qui pèse plus que les trames.
- **Une position : une écriture** (case de position du membre), environ 300 octets de corps.
- **Le moment d'écoute d'une trace** (R1) : 2 minutes, un flux, 11 écritures (9 battements, la réponse qui porte la preuve, l'effacement de son nœud en partant), environ 1,6 Ko de corps envoyés et 6 Ko reçus : ce que le § 20 estimait (« environ 8 battements »).
- Ce qui reçoit grandit avec le nombre de téléphones : chaque appareil lit toutes les écritures de la salle. Sans partage, à 30 téléphones, environ 30 × 5 écritures par minute à lire par appareil (≈ 21 Ko de corps par minute et par appareil d'après les 1,4 Ko mesurés pour 2 ; extrapolé, non mesuré).
- Les tronçons où une application a été relancée (étapes 4 et 6 pour A) sont faussés au run n° 1 (les compteurs repartent de zéro avec le processus) : le tableau du script en tient compte depuis (« redémarrée »), et compte aussi les écritures par genre de trame, l'attente d'une trame avant son écriture et les réponses du serveur autres qu'un succès (run suivant).

### Défauts trouvés

1. **Une rafale de réponses inutiles après chaque reconnexion, qui retarde de quelques secondes ce qui suit** (non corrigé ici : question 1). Ce qui le montre (run n° 1) : pendant la coupure de B et son retour, le nœud de B confie 22 trames à son relais et reçoit 52 trames (à chaque ouverture de son flux, le serveur redonne les trames de contrôle de A des 120 dernières secondes) ; au tronçon suivant, 38 écritures de contrôle pour 17 trames confiées : une vingtaine d'écritures en retard, et l'opération de départ de B écrite derrière elles, vue par A **3,4 s** après (0,13 à 0,4 s pour tout le reste). Même chose chez A après sa relance (17 écritures de contrôle pour 4 trames confiées dans le tronçon). La cause : `OperationNode.onSummary` répond à chaque résumé qu'il entend, y compris les vieux résumés que le serveur redonne, par les opérations qui manquaient alors à l'autre, par **tous** ses lots de clés dès que les empreintes diffèrent (`KeyRing.batchesFor`), et par les objets de chaque canal dont l'empreinte diffère ; seul un résumé identique entendu deux fois en 5 s n'a qu'une réponse ; et le relais écrit une trame à la fois. Sur tout l'essai, B a ainsi envoyé 21 lots de clés et en a reçu 45, pour deux téléphones. **Le run n° 2 le mesure** : à sa reconnexion, B reçoit 53 trames que le serveur lui redonne (14 opérations, 8 résumés, 17 lots de clés, 14 partages) et confie à son relais 21 trames en réponse (12 lots de clés, 4 partages, 3 opérations, 2 résumés) ; elles attendent ensuite leur écriture **1,46 s en moyenne, 22 écritures plus d'une seconde**, et l'opération de départ de B passe derrière (vue par A en 3,39 s). Chez A relancé : 22 trames confiées (12 lots de clés), attente moyenne **2,05 s**, 19 écritures plus d'une seconde. En dehors de ces rafales, une trame attend de 0 à 76 ms avant son écriture (408 ms en moyenne pendant la création, 18 trames en 10 s). Sans effet sur l'état (les doublons sont reconnus), mais une vingtaine d'écritures et quelques secondes de retard à chaque retour d'un téléphone, et davantage avec plus de téléphones présents (chacun redonne ses 120 s). La correction toucherait la règle d'échange du moteur (`:core`, sur lequel travaillent les sessions de simulation et d'attaques) : elle n'est pas faite ici. Le run suivant donne le détail par genre et l'attente des trames.
2. **Après une coupure, 8 s avant d'être de nouveau en ligne** (non corrigé ici : question 2). Ce qui le montre (run n° 1) : B en ligne 8,0 s après la fin du mode avion, puis tout rattrapé aussitôt (1 ms). La cause : personne ne dit au relais de l'opération que le réseau est revenu (`RelayTransport.onNetwork`, `reconnectNow`, qu'« Entre amis » appelle depuis son suivi du réseau dans `ShareController`) ; il attend son prochain essai (1, 2, 4 puis 8 s au plus, `FirebaseRelay`). La correction a sa place dans le service qui fera tourner le mode « Opération » (pas encore écrit) : petite, mais hors de ce qui existe aujourd'hui.

Défauts de l'essai lui-même, corrigés pendant la session : le résultat de la dissolution et du départ lu avant que la trace soit écrite et le stockage effacé (la vue dit « fini » une cinquantaine de millisecondes avant ; l'essai attend désormais la trace et l'effacement : ce n'est pas un défaut de l'application, mais un écran qui lit la trace doit suivre la vue, pas sa première valeur `ENDED`) ; l'époque comparée après une relance (ci-dessus) ; les durées du script et de l'application mesurées sur l'horloge murale, qui a reculé deux fois d'environ une demi-seconde sur la machine de la CI pendant le run n° 1 (deux temps d'attente négatifs) : horloge monotone désormais, l'horloge murale ne servant qu'à comparer les deux appareils.

### Ce que le serveur a refusé

Rien, aux deux runs : aucune étape n'a vu l'état `REFUSED`, chaque écriture attendue est arrivée de l'autre côté, y compris les battements de présence **au nom vide** (`n: ""`, accepté par les règles publiées : l'ADR-129 le disait « à voir en vrai »), les salles de 32 caractères hexadécimaux, les trames de contrôle des sept genres (jusqu'au lot de clés et à la preuve de dissolution) et les positions dans la case de position. Au run n° 2, le compteur des réponses du serveur le dit en nombres : aucune réponse autre qu'un succès (2xx), ni chez A ni chez B, sur tout l'essai.

### Questions pour l'utilisateur

1. **Après chaque reconnexion d'un téléphone, le serveur lui redonne les deux dernières minutes des autres, et il répond à chaque vieux résumé : une vingtaine d'envois inutiles et environ 3 s de retard pour ce qui suit. Que faire ?**
   a. Répondre au premier résumé d'un téléphone aussitôt, puis à un seul toutes les 2 s, le plus récent (recommandé : même résultat, au plus deux réponses par rafale ; à faire dans le moteur, en accord avec les sessions de simulation et d'attaques).
   b. Ne pas répondre à un résumé qui montre son auteur en retard sur ses propres opérations (il est vieux : un téléphone n'est jamais en retard sur ce qu'il a écrit) ; plus simple, mais ne supprime qu'une partie des réponses.
   c. Laisser ainsi.
   *Réponse de l'utilisateur, 2026-11-16* : a (M7). Ne répondre qu'au plus récent, une fois toutes les 2 secondes. À faire : P39.14.
2. **Après une coupure du réseau, le mode « Opération » met jusqu'à 8 s pour se reconnecter, là où « Entre amis » le fait dès le retour du réseau. Faut-il qu'il suive le réseau comme « Entre amis » ?**
   a. Oui, dans le service qui fera tourner le mode « Opération », quand il sera écrit (recommandé).
   b. Non, 8 s suffisent.
   *Réponse de l'utilisateur, 2026-11-16* : a (M8). Suivre le réseau comme « Entre amis », dans le service du mode « Opération » quand il sera écrit. À faire : P39.14, avec L4.

### Ce qui n'a pas pu être vérifié

- **Deux vrais téléphones**, le réseau mobile, le passage du Wi-Fi au réseau mobile, l'écran éteint, la batterie : l'essai tourne sur deux émulateurs d'une même machine, sur le réseau de la machine de la CI, application au premier plan et écran allumé.
- Les téléphones à Keystore matériel (TEE, StrongBox) : celui de l'émulateur est logiciel.
- **Plus de deux téléphones** : ni la rafale du défaut 1 avec plusieurs téléphones présents, ni les volumes à 30 membres (extrapolés ci-dessus), ni les rôles qu'un troisième téléphone ferait voir (adjoint, administrateur, deux groupes).
- **Une coupure de plus de deux minutes** : au-delà, le serveur n'a plus les trames de l'autre et le rattrapage ne passe plus que par les résumés ; la coupure de l'essai (40 s) se rattrape par les trames que le serveur garde.
- Le service qui fera tourner le mode « Opération » en tâche de fond n'existe pas encore : l'essai tient l'application au premier plan.

## 24. Renommer pendant l'opération (P39.13, 2026-11-16)

> Réponses de l'utilisateur du 2026-11-16 au cinquième passage de maquettes (`docs/previews/p39-groupes/tour5/`, questions N0 à N14, aperçus validés le même jour). Décision : ADR-135. Le moteur et les écrans de debug (mode « Opération », ADR-130) ; rien dans le type de build `release`.

### Les réponses de l'utilisateur

| Question | Réponse | Ce qui est fait |
|---|---|---|
| N0 | A | Le nom du groupe seul dans les listes et les menus (« Nord », plus « Groupe Nord ») ; à la création, « Nord » s'écrit seul |
| N1 | A | « groupe » en minuscule dans les phrases |
| N2 | A | Le créateur et les administrateurs renomment les autres (et eux-mêmes) |
| N3 | A | Le créateur ou n'importe quel administrateur répond à une demande ; la première réponse décide |
| N4 | A | Les demandes dans « Administrer » : la tuile orange compte aussi les noms à valider, une ligne « Noms à valider » sous « En attente » |
| N5 | B | Un crayon sur sa propre ligne (écran de l'opération et écran d'attente) ; rien dans le menu ⋮ |
| N6 | A | La première réponse compte ; l'autre administrateur voit « Déjà répondu » |
| N7 | A | Le membre retire sa demande tant que personne n'a répondu (« Annuler » sous sa ligne) |
| N8 | A | Refus : « Un administrateur n'a pas accepté », sans dire qui |
| N9 | B | Les autres ne sont prévenus de rien ; le renommé l'est, par un message en bas de l'écran |
| N10 | A | L'opération se renomme (menu ⋮, « Renommer l'opération… ») ; les invitations déjà données restent valables |
| N11 | B | Qui attend peut demander un nom. Mot pour mot : « Même à solliciter pour qu'un admin sache de qui en parle plutôt que d'avoir "SM-S913U" son savoir nom/prenom ou autre information connu ». L'écran d'entrée n'est pas touché |
| N12 | A | « Renommer le groupe » et « Supprimer le groupe » : deux lignes écrites en bas de la page du groupe, à la place des icônes du haut |
| N13 | A | L'écran refuse un nom déjà pris (« Ce nom est déjà pris »), pour un membre comme pour un groupe. Précision : « Si on applique le même nom, au moins 1 info dois les différencier exemple leur groupe » : question N15, en attente, non codée |
| N14 | A | « Renommer » en haut de la fiche d'un membre (administrateurs, créateur) |
| N15 | A | (tour 6, 2026-11-16, aperçus 03-doublon et 03-doublon-B) Deux membres au même nom : le groupe entre parenthèses, en gris, après le nom, partout ; dans un même groupe, l'ordre d'arrivée s'ajoute (« Guide 7 (Nord, 1) ») |
| N16 | 1 | (tour 6, 2026-11-16) Le champ « Votre nom » de l'écran d'entrée reste vide quand aucun nom n'est réglé dans l'application ; il faut l'écrire pour entrer. Aperçus 05-entree-sans-nom et 05-entree-sans-nom-ecrit validés en variante A le même jour (« A, texte gris ») : aide en gris, rouge seulement quand on efface un nom écrit |

Sans choix (`tour5/questions.md`) : le nom du groupe seul dans les listes et les menus ; pendant l'attente d'une réponse, tout le monde voit l'ancien nom ; une demande faite hors réseau part au retour du réseau ; le créateur et les administrateurs changent leur propre nom sans demande (et ne reçoivent pas « Un administrateur vous a renommé »). Dans chaque feuille, le champ porte le nom actuel et une croix pour l'effacer ; « Enregistrer » reste grisé tant que rien n'a changé, que le nom est vide, trop long ou déjà pris.

### Le nom proposé à l'entrée

L'écran d'entrée (« Rejoindre l'opération ? ») propose le nom réglé dans l'application ; s'il est vide, le **modèle du téléphone** (`Build.MODEL`, par exemple « SM-S913U » : `ShareController.displayName`, `ShareModule.deviceName`). C'est ce que N11 corrige après coup : la personne demande un vrai nom, un administrateur l'accepte. L'écran d'entrée n'est pas changé (N11 B).

N16 1 (tour 6, 2026-11-16) : le champ reste vide quand aucun nom n'est réglé, et l'on ne peut pas entrer tant qu'il est vide. Aperçus `tour5/img/05-entree-sans-nom*.png` ; rien n'est codé avant le « oui » sur l'aperçu.

*Aperçu validé le 2026-11-16, variante A (« A, texte gris »)* : l'écran d'entrée reçoit le nom réglé dans l'application (`ShareSettings.name`), plus le modèle du téléphone ; vide, le champ a le curseur, l'aide dit en gris « Aucun nom n'est réglé dans l'application. Écrivez celui que les administrateurs verront : il en faut un pour entrer. » et « Rejoindre » reste grisé ; un nom écrit puis effacé met le champ en rouge (« Il faut un nom pour entrer. »), comme avant ; un nom réglé remplit le champ, comme avant. La création d'une opération garde le nom réglé, ou le modèle du téléphone.

*Le clavier (k1, tour 7, P39.16)* : réponse « A, fenêtre au-dessus » du 2026-11-20, aperçu validé le même jour (« Oui, code A », `tour7/img/02-A-clavier-ouvert.png` et `04-etroit-texte-max.png`). La fenêtre reste au-dessus du clavier ; quand la place manque, l'icône et le titre restent en haut, « Annuler » et « Rejoindre » juste au-dessus du clavier, et le milieu (nom de l'opération, champ, aide, texte) défile, le champ remontant en entier quand il prend le curseur. Là où la fenêtre tient déjà (360 et 411 dp, texte normal), rien ne change ; clavier fermé, c'est la fenêtre de `tour5/img/05-entree-sans-nom`. Au plus grand texte d'Android sur 320 dp, le texte « Vous arrivez « en attente »… » se lit en faisant glisser, clavier ouvert comme fermé. La fenêtre de la boîte de dialogue passe en `SOFT_INPUT_ADJUST_RESIZE` (`OperationEntry.kt`, `ShrinkAboveKeyboard`), et la boîte est tenue à la hauteur qui reste entre la barre d'état et le clavier, lue sur l'écran d'où elle s'ouvre (`roomOnScreen`, `WindowInsets.safeDrawing`) : Compose mesure une boîte de dialogue comme si le clavier n'était pas là, et sa propre fenêtre apprend tard, ou pas du tout, qu'il a pris le bas (vu sur l'émulateur, 2026-11-20). Clavier refermé, le milieu reste où il était. Test `OperationNarrowScreensTest` (320 dp, texte × 2).

### Le moteur

- Cinq changements (§ 5) : `RenameMember`, `RequestName`, `WithdrawName`, `AnswerName`, `RenameEnsemble` ; codes 15 à 19 du format (`HierarchyWire`, `VERSION` 4 inchangée).
- Droits (`RightsTable.DEFAULT`, `Operation` 16 à 19) :

| Opération | Créateur | Administrateur | Autres membres (chefs, adjoints, qui attend) |
|---|---|---|---|
| `RENAME_MEMBER` | tout membre | tout membre | — |
| `REQUEST_NAME` (et `WithdrawName`) | — | — | soi-même |
| `ANSWER_NAME` | tout membre | tout membre | — |
| `RENAME_ENSEMBLE` | oui | oui | — |

- **Les opérations créées avant n'ont pas ces droits** : les règles voyagent, creuses, dans la première opération (§ 4, ADR-125) ; une case absente est un refus. Leurs écrans n'affichent ni crayon, ni « Renommer », ni « Renommer l'opération… » (l'écran le demande au moteur, `HierarchyReducer.check`). Pour essayer, créer une nouvelle opération.
- **L'état** : `nameRequests` (membre → nom demandé, une demande à la fois) et `nameAnswers` (membre → `NameAnswer(by, name, accepted)`, la dernière réponse, gardée jusqu'à la demande suivante) ; écrits en fin de format, en option (un état gardé avant se relit). Un membre retiré emporte sa demande et sa réponse ; un membre renommé directement perd sa demande en cours.
- **La première réponse décide** : `AnswerName` ne vaut que si la demande en cours porte ce nom ; l'ordre des opérations étant le même partout (§ 6), deux administrateurs qui répondent en même temps, même hors ligne, aboutissent au même résultat sur tous les téléphones ; la réponse arrivée seconde est refusée (`NOT_PENDING`) et son auteur le voit (« Déjà répondu », avec le nom de celui qui a répondu et sa réponse).
- **Les noms** : comme au § 8.8, les opérations ne portent que des empreintes. Les cartes des noms d'un membre qui attend (le sien, celui qu'il demande) vont dans sa voie d'attente ; les administrateurs et le créateur y écrivent les noms qu'il a le droit de connaître (`HierarchyState.namesFor` : celui de l'ensemble, les siens), en partage « noms » seulement (`ShareReach.WAITING_LANES`) ; un seul le fait, les autres voient qu'il l'a fait. Ils le font à l'arrivée d'une voie d'attente dans un ensemble renommé, quand le résumé de qui attend dit qu'il lui manque un nom, et quand il revient en retard sur un renommage qui le concerne (l'ensemble, ou lui-même) : sans cette dernière règle, trouvée par `OperationNamesTest`, qui attendait hors réseau pendant un renommage gardait l'ancien nom. Un téléphone renommé garde la carte de son nouveau nom.
- **Une invitation donnée avant un renommage** : sa carte ouvre un nom que l'ensemble a porté ; le téléphone invité l'affiche jusqu'à recevoir le nom d'aujourd'hui (N10 A).

### Le contrôleur (`OperationController`)

`renameMember(member, name)`, `requestName(name)`, `withdrawNameRequest()`, `answerName(member, accept)` (répond à la demande en cours de ce membre), `renameOperation(name)` ; chacun rend le motif d'un refus, ou rien. Une réponse refusée plus tard, quand les opérations se rejoignent, apparaît dans `OperationView.refused` comme tout changement non retenu. La vue donne aussi `nameRequests` (`NameRequestView` : membre, nom demandé, depuis quand ce téléphone la voit) et `nameAnswers` (`NameAnswerView` : membre, auteur, nom, accepté) ; le créateur et les administrateurs voient tout, chaque autre membre ne voit que les siennes (la carte d'une demande d'un membre placé voyage avec les autres noms : c'est la vue qui la cache, pas les clés).

### Les écrans (mode « Opération », type `debug`)

- **Sa propre ligne** (écran de l'opération, écran d'attente) : un crayon quand on peut changer son nom (N5 B). Administrateur ou créateur : « Votre nom », enregistré aussitôt. Sinon : « Demander un nom », envoyé aux administrateurs ; en attente, l'encadré dit que seuls les administrateurs le voient. Une demande en cours s'affiche sous la ligne (« Nom demandé », « Annuler »).
- **Administrer** : la tuile orange compte les noms à valider ; la ligne « Noms à valider » ouvre la page des demandes (ancien nom → nouveau ; groupe ou « En attente », rôle, depuis quand ; « Refuser », « Accepter »).
- **Fiche d'un membre** : « Renommer » en haut (N14 A) ; **page d'un groupe** : « Renommer le groupe », « Supprimer le groupe » en bas (N12 A) ; **menu ⋮** : « Renommer l'opération… » (N10 A).
- **Ce que voit le renommé** : « Un administrateur vous a renommé » ; « Accepté : vous vous appelez maintenant … » ; une fenêtre au refus (N8 A). Les autres : rien (N9 B).
- **Nom pris** (N13 A) : la feuille refuse un nom porté par un autre membre (ou par un autre groupe), sans tenir compte des majuscules ni des espaces autour ; c'est l'écran qui vérifie, pas le moteur (ADR-135, point 6).
- **Deux membres au même nom** (N15 A) : quand plusieurs membres portent le même nom (comparé sans majuscules ni espaces autour, comme le refus de N13), chacun est suivi, partout où son nom s'écrit, de son groupe entre parenthèses (« Relais 5 (Nord) », « Relais 5 (Sud) »), en gris dans les listes et les titres ; « En attente » pour qui attend un groupe ; dans un même groupe (ou en attente tous les deux), l'ordre d'arrivée dans l'opération s'ajoute (« Guide 7 (Nord, 1) », « Guide 7 (Nord, 2) »), le même sur tous les téléphones (l'ordre des membres de l'état). Un nom inconnu de ce téléphone n'est comparé à rien. C'est l'écran qui le calcule (`OperationFacts.nameMark`), le moteur n'en sait rien.
- Captures de la CI (`OperationScenes`) : `tour5-02-*`, `tour5-03-doublon*` et `tour5-04-*`, à mettre à côté des aperçus validés.

### Limites connues

- Deux renommages simultanés vers le même nom, sur deux téléphones hors ligne, passent tous les deux : le moteur ne compare pas les noms (empreintes salées).
- Un administrateur hors ligne ne voit pas qu'un autre a déjà répondu ; sa réponse est refusée au retour, il le voit alors (« Déjà répondu »).
- « Depuis quand » une demande attend (`seenAtMs`) n'est gardé qu'en mémoire : après un redémarrage, c'est le moment où ce téléphone la revoit.
- La longue simulation (§ 21) ne tire pas ces changements (ses suites à graine fixe restent les mêmes) ; ils sont couverts par l'accord aléatoire des copies (`HierarchyReplicaTest`), les attaques (`MaliciousMemberTest`), le fuzzing, `HierarchyNamesTest` (13) et `OperationNamesTest` (5).
- N15 (deux membres de même nom) : réponse A du 2026-11-16 (tour 6), codée (« Les écrans », deux membres au même nom).

## 25. Rythme d'envoi des positions (P39.15, 2026-11-18)

> Choix de l'utilisateur du 2026-11-18 (§ 10 octies : 2 a, F1 A, F2 B modifié, F3 A, validation « 1 a, 2 a »). Décision : ADR-136. Le moteur et les écrans de debug (mode « Opération », ADR-130) ; rien dans le type de build `release` ; « Entre amis » ne change pas.

### Le moteur

- **Un changement** : `SetPositionInterval(seconds)`, code 20 du format (`HierarchyWire`, `VERSION` 4 inchangée), 4 octets après le code. **Un droit** : `SET_POSITION_INTERVAL` (`Operation` 20), dans les règles par défaut pour le créateur, les administrateurs et le chef de l'opération (`ENSEMBLE_LEADER`), tous pour l'ensemble (`Reach.ANYONE`) ; l'adjoint du chef de l'opération l'a par son chef (R8). Les chefs de groupe, leurs adjoints, les membres et qui attend ne l'ont pas. R5 reste vrai pour toutes les autres opérations : le chef de l'opération n'a aucun droit sur la hiérarchie.
- **L'état** : `HierarchyState.positionIntervalS`, 60 par défaut. Écrit seulement s'il diffère de 60 : drapeau 2 de l'état, puis la valeur sur 4 octets après les drapeaux. Au décodage : drapeau inconnu, valeur par défaut écrite, valeur hors bornes : refusés (une seule forme par état). Le stockage garde les opérations, pas l'état : un téléphone qui redémarre rejoue le changement.
- **Bornes** : 1 s à 24 h (`MIN_POSITION_INTERVAL_S`, `MAX_POSITION_INTERVAL_S`), celles de la fenêtre d'« Entre amis » (F2 B modifié), écrites dans `:core`. Hors bornes : malformé (`Change.isMalformed`), refusé à l'écriture (`AddResult.MALFORMED`), au décodage et dans le journal reçu d'un autre téléphone. La même valeur qu'aujourd'hui : `NO_CHANGE`.
- **Accord** : comme tout changement, dans l'ordre commun des opérations ; deux changements simultanés (le chef de l'opération à 30 s, son adjoint à 5 min, hors ligne tous les deux) donnent la même valeur partout, la dernière dans cet ordre.
- **Les rythmes du mode « Opération »** (`RelayPresence.OPERATION`, `OperationNode.SUMMARY_EVERY_MS`) :

| | Avant (0.4.261) | Opération, P39.15 | « Entre amis » (inchangé) |
|---|---|---|---|
| Position | 15 s, sans réglage | le réglage, une minute par défaut, appliqué dès qu'il change | le réglage de chacun |
| Signal de présence | 15 s | 1 min | 15 s |
| Lien ouvert si le dernier signal a moins de | 40 s | 160 s | 40 s |
| Lien fermé après, sans signal | 75 s | 300 s | 75 s |
| Résumé de l'opération | 1 min | 5 min | — |
| Alertes | tout de suite | tout de suite | tout de suite |

- **Un défaut trouvé en chemin** (`OperationSimulationFindingsTest`, « un administrateur absent… apprend le nom de qui attend ») : avec le résumé à 5 min, le nom n'arrivait qu'au résumé suivant. Une demande (`pull`) refusée parce qu'une autre venait de partir (une toutes les 2 s, `PULL_EVERY_MS`) était perdue ; elle est maintenant gardée et repart au tour suivant (`pullLater`), et un téléphone à qui il manque le nom d'un membre qui attend le demande à ce membre, s'il le voit.

### Les écrans (mode « Opération », type `debug`)

- **Ligne « Fréquence d'envoi »** (F1 A, `tour6/img/02-q1-A`), sous « Partager ma position » : « Toutes les minutes », ou « Toutes les » et la durée (« Toutes les 45 s ») ; une flèche pour qui peut la changer (le téléphone le demande au moteur, `OperationFacts.mayChangeRhythm`), qui ouvre la feuille ; pour les autres, un cadenas et « Réglée par le chef de l'opération », et rien ne s'ouvre.
- **Feuille « Réglages d'envoi »** (F2 B modifié, `05-q2-B-modifie`, `-2`, `-3`) : le curseur d'« Entre amis » (`PresetSlider`) de 1 à 10 min, un cran par minute, les minutes sous les crans (« 1 min », « 2 », … « 5 min », … « 10 min »), celle de la valeur en gras ; la pastille ouvre la fenêtre d'« Entre amis » (`ValueDialog`, 1 s à 24 h, sans « Déplacement minimal ») ; de 1 à 29 s, l'avertissement de l'opération (« Moins de 30 s : plus de batterie pour tout le monde, et le serveur gratuit peut atteindre sa limite du mois. »), « OK » actif. Une valeur hors des crans reste exacte dans la pastille, le curseur va au cran le plus proche (à égalité, le plus bas) ; seul un curseur déplacé remplace la valeur. « Appliquer » applique (rien si la valeur n'a pas changé), « Annuler » ferme.
- **« Entre amis »** : son curseur n'a pas d'étiquettes (paramètre facultatif, absent), sa fenêtre garde son avertissement sous 5 s.
- **États que les images validées ne montraient pas** (questions F4 à F7, `tour6/etats/index.html`, `tour6/img/06-…` à `09-…`, envoyées le 2026-11-18, réponse attendue) : ce que voient les autres quand le rythme change, l'écran de qui attend, une opération créée avant, l'ordre des lignes avec un nom demandé. Le code fait pour l'instant le plus simple : seule la ligne change (F4 A), rien sur l'écran d'attente (F5 B), dans une opération créée avant le texte des membres et un cadenas pour tous (F6 B), « Nom demandé » puis « Fréquence d'envoi » (F7 A).
- Captures de la CI (`OperationScenes`) : `tour6-02-q1-A`, `tour6-02-q1-A-membre`, `tour6-05-q2-B-modifie`, `-fenetre`, `-2`, `-3`, et `tour6-06-f4-B-45-s-membre` (après « Appliquer » à 45 s, parmi les scènes qui changent la démonstration : la ligne et le message de F4 B ; jusqu'à 0.4.263, `tour6-02-q1-A-45-s-membre`, la ligne seule).
- **Depuis 0.4.265 (réponses du 2026-11-19, § 10 octies)** : F4 B, le message en bas de l'écran de chaque téléphone quand le rythme change (`Notice.RhythmChanged`, tiré de deux vues comme les autres messages, `Notices.between`) ; F6 A, le cadenas et « Fixée à la création de l'opération » pour tout le monde quand les règles ne donnent le droit 20 à aucun rôle (`OperationFacts.rhythmFixed`) ; les minutes placées sous leurs crans. F8 (validé le 2026-11-19, `tour6/img/11-f8-b-pousse`) : sous le curseur, « 1 min » et « 10 min » toujours, et entre les deux toutes les minutes, sinon une sur deux, sinon « 5 min » seule, selon la place (`StepLabelChoice`, au moins 4 dp entre deux libellés).

### Versions mélangées

- **Un téléphone en 0.4.261** ne connaît ni le droit 20 ni le code 20 : il refuse la première opération d'une opération créée après (« unknown operation in the rights ») et ne peut pas la rejoindre ; il ne lit donc jamais le changement.
- **Une opération créée en 0.4.261** n'a pas la case du droit (les règles voyagent dans la première opération) : personne n'y change le rythme, qui se lit à une minute. Un téléphone à jour y envoie sa position toutes les minutes et son signal de présence toutes les minutes ; un téléphone en 0.4.261 y garde 15 s pour les deux. Le 0.4.261 ouvre un lien sur un signal de moins de 40 s et le ferme après 75 s sans signal : un téléphone à jour, qui bat toutes les 60 s, y reste présent la plupart du temps, mais un signal en retard de plus de 15 s ferme le lien, qui se rouvre au signal suivant.

### Coût remesuré

Mesuré par `OperationCostTest` (`./gradlew :core:test --tests '*OperationCostTest*' -Ptoolmap.hierarchy.soak=true`, `core/build/hierarchy-cost.txt`) : la même opération que le § 21 (groupes de cinq avec un chef et un adjoint, un administrateur, le créateur chef de l'opération), 10 minutes simulées, réseau calme, une fois au rythme d'avant (positions toutes les 15 s, présence 15 s, résumé 1 min) et une fois à celui de P39.15 (positions toutes les minutes, présence 1 min, résumé 5 min). « Échanges » : trames écrites et reçues par un téléphone ; « signaux » : signaux de présence écrits et lus. JVM du poste, pas un téléphone.

| Membres | Échanges par téléphone (trames/min, octets/min), avant → après | Signaux par téléphone, avant → après | Calcul dans l'automate, par téléphone | Serveur, écritures de la salle par minute, avant → après |
|---|---|---|---|---|
| 5 | 25,3 → 6,0 ; 4 703 → 1 121 o | 20 → 5 | 10,9 → 2,5 ms/min | 50,3 → 12,0 |
| 15 | 75,8 → 18,1 ; 14 095 → 3 378 o | 60 → 15 | 30,9 → 8,0 ms/min | 150,8 → 36,1 |
| 30 | 151,4 → 36,2 ; 28 121 → 6 755 o | 120 → 30 | 61,7 → 15,4 ms/min | 301,4 → 72,2 |

- **Après / avant, à toutes les tailles** : échanges, trames et octets **24 %** (−76 %), signaux 25 %, écritures du serveur 24 %. L'estimation du § 10 octies (−65 %, extrapolée de la mesure de deux téléphones du § 23) était prudente : elle gardait une part fixe que la simulation ne montre pas en régime établi (ni accord de clé, ni changement de l'opération pendant la mesure).
- **Par position**, rien ne change : une signature, une vérification par téléphone qui l'entend ; 2,5 ms (5 membres) à 15,4 ms (30 membres) de calcul dans les automates de toute la salle par position envoyée et entendue.
- Le test court (`five members for ten minutes, the rhythm of P39_15 against the one of before`, dans `./gradlew check`) vérifie que les échanges par téléphone baissent d'au moins la moitié ; la longue série écrit le tableau ci-dessus.
- **Ce qui n'est pas mesuré** : un vrai téléphone (batterie, radio, écran éteint), le vrai serveur (P39.12 le fait pour deux téléphones), le réseau mobile.

## 26. Seul le destinataire d'un résumé y répond, et personne ne réclame ce qu'il ne peut pas lire (P39.17, 2026-11-20)

Session 125 (poste Windows), branche `conception/groupes-hierarchiques` ; ADR-138. Trouvé en cherchant pourquoi le run « CI » n° 243 était rouge pendant P39.16 : la démonstration de l'APK de debug (22 téléphones simulés dans un seul processus) remplissait le tas de l'émulateur de la CI (192 Mo), comme au run n° 232. Rien de visible ne change ; le partage « Entre amis » n'est pas touché (aucun fichier de `:p2p`, de `core/…/p2p/` ni de `app/…/share/`).

**Ce qui se passait.** Sur le serveur, tout ce qu'un téléphone écrit arrive à toute la salle. Un résumé envoyé à un seul téléphone (à l'ouverture d'un lien, ou pour lui demander ce qui manque, `pull`) était donc entendu par tous, et chacun y répondait, lui aussi à toute la salle : les opérations qui manquaient, tous ses lots de clés si les empreintes différaient, les objets, les noms. Pour une seule arrivée, chaque téléphone présent envoyait son résumé au nouveau, chacun était repris par les 21 autres, et chaque réponse faisait naître d'autres demandes.

**La règle.** Le résumé porte son destinataire (§ 17, format « 2 résumé » : 8 octets de plus, écrits seulement s'il n'est pas 0) :

| Résumé | Destinataire |
|---|---|
| à l'ouverture d'un lien (`linkOpened`) | le téléphone de l'autre bout |
| demande de ce qui manque (`pull`) | le téléphone à qui l'on demande |
| toutes les 5 minutes (`SUMMARY_EVERY_MS`) | toute la salle (0) |
| demande d'une clé d'invitation plus récente | toute la salle (0) |

Un téléphone qui entend un résumé destiné à un autre n'y répond pas du tout (ni opérations, ni lots de clés, ni objets, ni noms, ni demande en retour ; compté dans `OperationNode.heardForOthers`, ce n'est pas un refus). **Ce que porte la réponse reste pris par tous** : les opérations, lots de clés et partages qui passent dans la salle sont lus par chaque téléphone comme avant. C'est sans risque (chaque trame est vérifiée comme si elle lui était destinée, un doublon est reconnu) et utile (un téléphone en retard sur les mêmes opérations les a sans les demander). Les autres genres ne changent pas : la demande d'entrée est scellée pour le créateur et les administrateurs, la clé d'invitation pour chaque destinataire.

**Deux autres causes, trouvées en mesurant.** Elles vont dans le même sens et ne touchent ni à ce qu'on voit ni à qui lit quoi :

- **Ne pas réclamer une clé qu'on ne doit pas avoir.** Un partage qu'un téléphone ne peut pas ouvrir faute de clé (`share.no_key`) ne lui fait demander ce qui manque à son auteur (`pull`) que s'il lit le canal de ce partage dans son état du moment (`OpenResult.Refused` donne le canal). Un membre en attente de sa place ne lit pas les canaux des groupes : il demandait, et la réponse lui renvoyait ce qu'il ne pouvait toujours pas ouvrir, toutes les 25 s environ. L'ordre des refus du § 8 (« le téléphone détient la clé nommée (sinon `NO_KEY`) ; il lit encore ce canal (`NOT_A_READER`) ») ne change pas : seule la réaction du téléphone change.
- **Ne pas compter comme manquants les noms qu'on ne peut pas lire.** Le nombre de noms manquants du résumé ne compte plus, pour un membre qui a l'accès, les noms de ceux qui attendent dans une voie qu'il ne lit pas (Q10, Q11) : ils ne lui viendront jamais, et chaque téléphone de la salle répondait à chaque résumé de la salle par sa liste de noms. Le créateur et les administrateurs, qui lisent ces voies, les comptent comme avant.

**Mesure** (`OperationArrivalCostTest`, `:core`, dans `./gradlew check` ; JVM du poste, salle simulée calme, graine 22 000) : une opération de 21 membres en quatre groupes (chefs, adjoints, deux administrateurs, une position envoyée par chacun), puis un 22e téléphone demande à entrer et est admis ; compté de la demande à une minute après l'admission. Puis cinq minutes de salle calme, le nouveau attendant sa place.

| Trames reçues par téléphone déjà présent | Avant P39.17 | Règle du destinataire seule | P39.17 |
|---|---|---|---|
| en tout (moyenne ; plus petit et plus grand) | 7 360 (7 311 à 7 403) | 1 037 (989 à 1 042) | 872 (827 à 878) |
| opérations | 3 401 | 162 | 241 |
| lots de clés | 2 769 | 438 | 480 |
| partages | 879 | 140 | 5 |
| résumés | 287 | 273 | 122 |
| demandes d'entrée | 24 | 24 | 24 |
| reçues par le nouveau | 7 347 | 756 | 720 |
| écritures de contrôle sur le serveur, pour la salle | 7 708 | 1 071 | 904 |
| ensuite, par minute, un membre en attente (au plus) | 240 | 240 | 4,2 |

Une vraie opération de 22 membres reçoit donc le même flot que la démonstration, téléphone par téléphone : avant P39.17, plus de 7 000 trames par téléphone pour une arrivée, puis 240 par minute sans fin tant qu'un membre attend sa place. La démonstration le subissait 22 fois dans un seul processus. Le test garde l'arrivée sous 1 200 trames et la salle calme sous 20 par minute.

Mesure ponctuelle, même salle juste après l'arrivée (le nouveau en attente), une minute après chaque changement, par téléphone (test jetable, non gardé) : placer un membre 3 420 → 185 ; donner un poste 722 → 37 ; nommer un administrateur 4 142 → 373 ; créer un groupe 1 230 → 69 ; rien 238 → 6.

Ce qui reste arrive surtout quand le nouveau ouvre ses liens, avant même son admission (environ 620 des 872 trames dans les trois premières secondes) : d'après le code, il envoie un résumé à chacun, et chacun lui répond par ce qui lui manque (tout le journal, ses lots de clés), que toute la salle entend. À revoir si la mesure sur téléphone le demande.

**La démonstration de l'APK de debug** (`DemoWorld`) fait entrer ses téléphones, donne les postes et nomme les administrateurs un à un, chacun vu de tous avant le suivant (comme les groupes depuis le run n° 232). Pic du tas sur un émulateur du poste réglé comme celui de la CI (2 Go, `dalvik.vm.heapgrowthlimit` 192m), le plus haut des « X Mo / Y Mo » des ramasse-miettes, et durée de la construction :

| APK | Pics du tas (Mo) | Durée (s) |
|---|---|---|
| 0.4.271, avant P39.17 | 192, 192, 192 | 273, 109, 72 |
| règle du destinataire seule | 165, 103, 145 | 71, 39, 38 |
| et les postes un à un | 181, 154, 80 | 16, 14, 16 |
| et les arrivées une à une | 190, 93, 66 | 38, 47, 44 |
| P39.17 entier | 146, 95, 130 ; puis 124, 113, 111, 112 | 19 à 22 |

**Un téléphone admis avec une invitation plus ancienne que la clé du moment** : s'il reçoit la clé du moment avant les opérations qui permettent de la vérifier (son annonceur, son donneur), il la refuse, comme avant. Il la reçoit de nouveau au résumé suivant de la salle (5 minutes au plus) : avant P39.17, toute la salle répondant à tout, on lui parlait sous l'ancienne clé assez longtemps pour qu'elle lui soit redonnée en quelques secondes. Garder la clé refusée pour la vérifier à l'arrivée des opérations a été essayé et écarté : dans `MaliciousMemberTest` (« known limit… »), le second téléphone du membre retiré avait alors la clé du moment avant d'apprendre le retrait, et la donnait au membre retiré. Le test attend maintenant ce résumé, et vérifie en plus que le membre retiré n'a pas la clé du moment.

**Versions mélangées.** Un téléphone d'avant P39.17 lit les résumés pour toute la salle. Il refuse en revanche un résumé destiné à un seul (« trailing data », signalé comme attendu), donc il ne répond plus aux demandes d'un téléphone à jour. Ce dernier se rattrape alors par les autres téléphones à jour, par ce qui passe dans la salle et par les résumés de toutes les 5 minutes. Un téléphone à jour lit tout résumé d'avant comme destiné à toute la salle et y répond comme avant. Tous les téléphones d'un essai prennent la même version.

**Ce qui n'est pas mesuré** : un vrai téléphone, le vrai serveur avec plus de deux téléphones, le réseau mobile.

## 27. Réponses du 2026-11-16 : ce que le moteur en fait (P39.14, 2026-11-22)

Session 126 (poste Windows), branche `conception/groupes-hierarchiques` ; livraisons P39.14.1 à P39.14.5 (`docs/PLAN.md`). Les réponses sont aux §§ 19 (L1 à L5), 20 (D1, D2), 21 (M1 à M6) et 23 (M7, M8). Sans travail : L3, D1, D2, E3. Les points visibles (L1 à l'écran, L4 et M8, L5, M4) attendent leurs aperçus (`docs/previews/p39-groupes/tour8/`).

### Livraison 1 (P39.14.1) : L1 dans le moteur, M3, M7 — ADR-139

- **L1, la couleur d'un groupe** : changement signé `SetGroupColor(group, rgb)`, code 21 du format, couleur `0xRRGGBB` sur 4 octets (24 bits ; au-delà : malformé, refusé partout). Il suit la ligne « renommer un groupe » de la table des droits (le créateur et les administrateurs) : une opération créée par la 0.4.276 la permet donc aussi. `Group.rgb` reste `null` tant que personne n'a choisi : l'écran garde la couleur de la place du groupe. L'état écrit ne porte les couleurs que si un groupe en a une (drapeau 4, puis la liste des couleurs après les groupes) ; sans couleur, il s'écrit comme avant. Le contrôleur : `setGroupColor`, et `GroupView.rgb`. L'écran ne la propose pas encore (aperçu L1).
- **M3, écrire avant d'envoyer** : le nœud appelle `beforeSend()` juste avant d'envoyer un changement qu'il vient d'écrire ou la remise d'une clé d'invitation qu'il vient de tirer ; le contrôleur enregistre tout sur son fil avant de rendre la main. Pas pour la dissolution ni pour son propre départ (rien n'est gardé). Le cas D du § 21 (deux changements sous un même numéro après une application tuée) ne peut plus naître d'un changement envoyé puis perdu au redémarrage.
- **M7, une réponse toutes les 2 s, au plus récent** : P39.17 (§ 26) a déjà fait que seul le destinataire d'un résumé y répond. Il restait la rafale de vieux résumés d'un même téléphone que le serveur redonne à la reconnexion (deux minutes) : le premier a sa réponse aussitôt, les suivants des 2 s d'après sont mis de côté, seul le plus récent est gardé et répondu à la fin des 2 s. Le reste du § 26 ne change pas. Mesuré sur la JVM (`OperationNodeTest`, « a burst of summaries… ») : dix résumés d'un coup, deux réponses au lieu de dix. Ce qu'il reste de M7 : rien dans le moteur ; la mesure sur le vrai serveur (essai de bout en bout, § 23) le dira au prochain run « Opération, bout en bout ».

### Livraison 2 (P39.14.2) : M6, M5, M2, M1 — ADR-140

- **M6, une seule clé d'invitation à la fois** : après chaque départ, le créateur la change aussitôt, dans l'appel même qui lui apprend le départ (la clé neuve est écrite avec lui, M3) ; les administrateurs ne la changent plus (`renewInvitation` ne fait rien hors du créateur, la tâche `RENEW` des administrateurs est retirée). Le créateur absent : elle change à son retour. Les cas I (§ 21) et 2 (§ 21, défauts corrigés) ne naissent plus d'une marche normale ; la règle d'ADR-132 reste pour un téléphone d'une version d'avant. Limite : le créateur perdu pour de bon (S7), la clé ne change plus.
- **M5, réconcilié avec M6** : c'est le créateur qui, voyant passer une remise de la clé du moment faite à un téléphone parti (cas G), la change une fois de plus ; une fois par minute au plus.
- **M2, un membre absent revient de lui-même** : chaque téléphone garde les 32 dernières clés d'invitation (4 avant). Le résumé d'un membre sous une ancienne clé a sa réponse sous cette clé : les opérations qu'il a manquées, puis la clé du moment (il sait alors qui l'a annoncée : cas C réglé) ; la clé reçue pour lui seul, il demande aussitôt le reste sous la clé du moment. Qui voit une trame d'une clé plus récente que les siennes adresse son résumé à celui qui l'a écrite (lui seul répond, § 26), plus à toute la salle. Le cas B (§ 21) ne reste que pour un membre absent pendant plus de 31 renouvellements.
- **M1, un retiré hors réseau apprend son retrait** : un téléphone qui n'est plus membre et parle sous une ancienne clé reçoit de chaque membre qui l'entend, sous sa clé, l'opération qui l'a retiré, et rien d'autre (une fois par 10 s au plus) ; son application finit l'opération (« retiré »), même si les opérations d'entre-temps lui manquent, quand l'auteur du retrait pouvait le faire. Ses invités de la copie morte (admis par lui après son retrait, dans l'ordre des opérations) reçoivent l'opération qui l'a retiré ; un membre qui n'a pas leur admission la demande sous la même clé au téléphone qui l'a. Ils finissent l'opération aussi.
- **Compatibilité** : une opération créée par la 0.4.276 se lit comme avant (formats inchangés, 32 tient dans l'octet du nombre de clés) ; ses époques annoncées par un administrateur restent acceptées.
- **Tests** (`OperationNodeTest`, salle simulée, et la salle coupée en deux `split` / `mend`) : « only the creator renews the invitation key… (M6) », « two keys of one epoch, one from a phone of an older version… (ADR-132) », « a member away for many renewals comes back by itself (M2, case B) » (six renouvellements), « …takes the key of the new creator (M2, case C) », « a member taken out while it was away is told so… (M1, case A) », « who was let in by an administrator taken out before learns it too (M1, case A) », « the key of now handed to a phone gone makes the creator renew it once more (M5, case G) ». Deux tests existants suivent M1 (le retiré l'apprend maintenant) : `MaliciousMemberTest` « …signs its way back in… » (le retiré finit l'opération, rien ne le fait entrer) et « known limit… antedates… » (l'invitation du second téléphone prise avant le retour du retiré). `DissolutionTraceTest` (« a member taken out keeps no trace… ») passe sans changement : quand le même lot porte le retrait et la dissolution, la fin reste « dissoute », sans trace. Simulateur : la vérification `closing:invite-key` ne vaut que quand le créateur est là (M6).

### Livraison 3 (P39.14.3) : L2, cas E et H — ADR-141

- **Cas E, la demande d'entrée** : quand le créateur ou les administrateurs changent, chaque téléphone qui tient la carte du nom d'une demande en attente la scelle de nouveau pour ceux qui peuvent faire entrer et que la demande n'a pas comptés (trame `JOIN_CARD`, genre 8 : la demande signée, une clé éphémère, une enveloppe par destinataire ; `JoinRequests.relay`, `decodeRelayed`, `openRelayed`). Le destinataire vérifie la signature de la demande, son âge, que l'expéditeur est membre, et que la carte ouverte est celle du nom signé ; il garde alors la demande comme s'il l'avait reçue de son téléphone, et peut la faire entrer. Un simple membre ne lit rien.
- **Cas H, qui attend un groupe** : la voie d'attente d'un téléphone qui ne parle plus gagne un lecteur (administrateur nommé, nouveau créateur) : les lecteurs qui tiennent son nom l'y redisent, le premier en ligne d'abord. Un lecteur de cette voie qui dit dans son résumé qu'il lui manque des noms se le fait redire, une fois par 10 min au plus par voie (`NAMES_AGAIN_EVERY_MS`).
- **Compatibilité** : une opération créée par la 0.4.276 se lit comme avant ; un téléphone d'avant ignore le genre 8.
- **Tests** (`OperationNodeTest`) : « an administrator named after the phone of who waits is lost learns its name from the others (L2, case H) », « an administrator named after a request, its phone no longer hearing, learns it and its name (L2, case E) », « a card wrapped again opens only for its recipient, and only as the name the request signed (L2) » ; le décodeur `JoinRequests.decodeRelayed` est ajouté au fuzzing (`Fuzzing.kt`, avec une trame valide dans le corpus).
- **Deux défauts de la livraison 2, trouvés par la longue série (ADR-142)** : sur les 100 premières graines, trois catégories nouvelles par rapport à la livraison 1 (rejouées une à une sur les deux versions) : `converge:epoch-key` (0x50ac001c : un administrateur éteint pendant que le créateur renouvelle, lui passe la main et part ; revenu, il apprenait son rôle et les départs avant la clé du moment et renouvelait depuis l'ancienne, deux clés pour une époque, et un membre entré à cette époque restait seul) et `objects:diverge` (0x50ac0023, 0x50ac0029 : un membre revenu ne redemandait le reste qu'au premier téléphone qui lui avait remis la clé, un membre en attente qui ne lit pas ses canaux). Corrigé : la clé du moment d'abord, seule, puis le reste demandé sous elle à tous les téléphones reliés ; les opérations sous l'ancienne clé seulement pour qui n'a pas pu la prendre (cas C) ; un créateur qui sait qu'une époque plus récente existe attend sa clé pour renouveler (30 s au plus). Les trois graines passent ; 0x50ac001c entre dans le jeu fixe (`OperationSimulationTest`, « eighteen phones, a creator back after a renewal it missed »), avec deux tests de `OperationNodeTest` (« back as the creator after a renewal it missed… », « back after a renewal, a member gets the objects… »).
- **Longue série rejouée après la correction** (100 premières graines, 13 580 étapes, 107 h simulées, 666 s ; le bilan lu à 100 scénarios, la réduction des cas arrêtée) : plus de `converge:epoch-key`, plus de divergence d'objets en 0x50ac0023, 0x50ac0029 ni 0x50ac003a, plus de `converge:epoch`, aucun cas « announcer unknown » (2 en livraison 2) ; `names:missing` 2 (0x50ac003e : le nom d'un groupe que personne n'a ; rouge aussi en livraison 1, verte en livraison 2 par le seul hasard des trames). **Une divergence nouvelle dans la série, `objects:diverge:DOWN` (0x50ac002c, douze téléphones), est une limite qui existait déjà** : le créateur, hors réseau, retire son rôle à l'administrateur P9 ; P9, qui ne le sait pas encore, pose un croquis vers un groupe ; seul P5, simple membre, le reçoit. Revenu, aucun de ceux qui le tiennent n'a plus le droit de partager vers le groupe : les chefs et le créateur ne l'ont jamais. Rejouée sur les trois versions, la graine est verte en livraisons 1 et 2 (P5 n'avait pas encore la clé du canal quand le croquis est passé) et rouge en livraison 3 (il l'a plus tôt) ; ramenée à trois téléphones, le cas est rouge sur les trois versions. Un test le **fixe** (`OperationNodeTest`, « known limit - a sketch put down by an administrator demoted meanwhile stays with who got it ») ; ce qu'il faudrait en faire (le montrer partout, ou nulle part) est à décider par l'utilisateur.
