# Spécification — Serveur de coordination Firebase pour le partage de position (P13)

> Décision : ADR-033 (remplace en partie ADR-004). Code : `core/.../p2p/Relay.kt`, `p2p/.../FirebaseRelay.kt`, `FirebaseRest.kt`, `RoomState.kt`, `ServerSentEvents.kt` ; règles : `firebase/database.rules.json`.

## 1. Pourquoi
Le mode direct (WebRTC + QR, `p2p-sharing.md`) échoue souvent sur téléphone : CGNAT 4G, pare-feu, SDP reconstruite refusée, double scan de QR. Un serveur Firebase (gratuit, plan Spark) sert de **bus de diffusion** : chaque téléphone y dépose ses trames et lit celles des autres. Plus de NAT à traverser, plus de QR de réponse.

## 2. Ce que le serveur voit
Les trames sont **déjà chiffrées** (AES-256-GCM, clé `HKDF-SHA256(clé de groupe)`, `Wire`) avant d'être envoyées. Le serveur ne stocke que : le nom de pièce (hex de l'identifiant du groupe), des identifiants de membres, des horodatages, les noms affichés (64 car. max, en clair dans la présence) et du chiffré. La clé du groupe ne quitte jamais les téléphones, sauf dans l'invitation (§5).

## 3. Architecture
- **REST + SSE** par OkHttp, sans SDK ni Google Play Services.
- **Authentification anonyme** : `accounts:signUp` (Identity Toolkit) puis renouvellement par `securetoken` ; l'accès à la base se fait par `?auth=<idToken>`. Le jeton est conservé dans `files/p2p/firebase-auth.token`.
- **Arbre** : `rooms/<pièce>/b/m<idMembre hex>/{h,p,c}`
  - `h` présence : `{t: horodatage serveur (.sv), n: nom, k: couleur}`, réécrit toutes les 15 s d'heure murale
    (vérifié chaque seconde : le délai d'un minuteur ne compte pas la veille du processeur, P21.5 q) ;
  - `p` dernière position (trame scellée `f` + `t`), remplacée à chaque envoi ; **seulement des `POS`** : un membre qui arrive y lit la dernière position (P21.5 b) ;
  - `c/<id>` messages de contrôle (texte, SOS, REKEY, BYE…) et les `PING` de vivacité (toutes les 15 s) : trame `f` + `t`, nettoyés au bout de 120 s, ignorés à la réception au-delà de 150 s. Au démarrage, le relais efface aussi celles qu'un
    processus précédent a laissées (liste `shallow`, heure lue dans la clé, `PATCH` de `null`, P21.5 r).
- Un flux SSE sur `rooms/<pièce>/b` alimente `RoomState` (put/patch → arbre local). Les écarts d'horloge sont absorbés par un décalage mesuré sur `.sv` ; une mesure dont l'aller-retour est négatif
  ou de plus de 10 s est refaite (3 essais), et l'écart est remesuré quand l'heure du téléphone change
  (`ACTION_TIME_CHANGED`, P21.5 n).
- `RelayTransport` est un `MeshTransport` : pas d'offre ni de réponse ; une liaison `MeshLink` apparaît pour chaque membre dont la présence est récente (< 40 s pour ouvrir, > 75 s pour fermer). `MeshLink.isBroadcastMedium` empêche `MeshNode` de réinonder des trames que tout le monde lit déjà.
- **Reconnexion** (ADR-051, P21.5) : essais espacés de 1, 2, 4 puis 8 s au plus ; flux muet 45 s = flux mort. Hors réseau, une seule écriture de position (la plus récente) et une seule présence attendent dans la file ; la position retenue est écrite dès la réouverture du flux.
  - **Réseau** : le contrôleur suit le réseau par défaut tant qu'un groupe sur serveur existe (`NetworkWatch`, pas seulement pendant le partage) et le dit au relais (`onNetwork`). À chaque nouveau réseau, `reconnectNow()` réessaie aussitôt et annule le flux s'il a plus de 5 s **ou** s'il a été ouvert sur un autre réseau. Le relais a son propre pool de connexions OkHttp, remplacé à chaque nouveau réseau : sans cela, le flux rouvert reprenait une connexion de l'ancien réseau et tombait quand Android le coupait (30 s après le retour du Wi-Fi).
  - **Réseau bloqué par Android** (appli en arrière-plan sans service, économiseur de données, Doze ; `onBlocked`) : aucune tentative ni présence tant que c'est bloqué, tentative immédiate au déblocage.
  - **Aucun réseau** (mode avion ; `onOffline`, P21.5 p) : la boucle attend le retour d'un réseau, 5 min au plus au cas où l'appel serait manqué, puis tente aussitôt.
  - **Jeton** : renouvelé 2 min avant l'expiration par l'écriture qui le demande ; le flux, ouvert avec l'ancien, est alors rouvert aussitôt avec le nouveau (≈ 0,3 s, l'état reste « en ligne »). Si le serveur révoque quand même le jeton du flux (`auth_revoked`), seul ce jeton est oublié et la reconnexion se fait sans pause.
- Retrait d'un membre (`ban`) : ses trames sont ignorées, la clé du groupe est renouvelée (REKEY) comme en mode direct.

## 4. États affichés
**Pair** (`PeerState`, une icône + un mot) :
| État | Icône | Sens |
|---|---|---|
| En ligne | rond vert, coche | lié et entendu récemment |
| Appairé | rond bleu, deux anneaux | dans le groupe, rien reçu de lui encore |
| Synchronisation perdue | rond ambre, « ! » | il parlait, silence depuis < 10 min ; ou **ce téléphone** ne joint plus le serveur (il ne peut garantir personne) |
| Hors ligne | rond gris, tiret | silence depuis ≥ 10 min (soi-même : serveur injoignable) |
| Non appairé | rond rouge, croix | parti, retiré, ou pas de groupe |

**Serveur** (`ServerState`) : `NONE` (mode direct), `CONNECTING`, `ONLINE`, `UNREACHABLE`, `REFUSED` (adresse, clé ou accès anonyme refusés). Affiché au-dessus de la liste des membres.

## 5. Invitation
`toolmap://join?d=<base64 URL-safe>` ou QR (Base45) : version 2, contient l'identifiant et la clé du groupe, l'identifiant et le nom de l'invitant, l'adresse de la base et la clé API web. **Pas de réponse à scanner** : la personne qui scanne est dans le groupe aussitôt. Valable 7 jours. **Quiconque a le code peut entrer** : à ne pas publier ; retirer un membre renouvelle la clé mais l'ancien code reste utilisable jusqu'à expiration par qui l'a conservé (mais la clé qu'il y lit est l'ancienne : il ne déchiffre pas les trames postérieures au renouvellement).

## 6. Règles de sécurité (`firebase/database.rules.json`)
Lecture et écriture réservées aux utilisateurs authentifiés (anonymes compris), nom de pièce en 32 caractères hexadécimaux, structure et tailles validées nœud par nœud. **Limites assumées** : les règles ne connaissent pas l'appartenance à un groupe (tout porteur d'un jeton anonyme peut écrire dans une pièce dont il devine le nom, mais n'en lit que du chiffré et ne peut produire de trame valide sans la clé) ; un client modifié et retiré du groupe peut relire le REKEY déposé sur le bus partagé pendant ≈ 1 h, mais il est scellé avec l'ancienne clé qu'il possède : le renouvellement protège donc seulement les trames envoyées **après** que les membres restants ont appliqué la nouvelle clé.

## 7. Quotas du plan Spark (gratuit)
100 connexions simultanées, 1 Go stocké, 10 Go/mois de téléchargement. Une position scellée ≈ 150 octets ; à 30 s d'intervalle, un membre consomme environ 15 Mo/mois en émission et autant par pair en lecture : un groupe de 5 tient très largement. Au-delà de 100 téléphones connectés ensemble, les nouvelles connexions sont refusées (`UNREACHABLE`).

## 8. Mise en place (console Firebase)
À faire une fois par le propriétaire du serveur, avec son compte Google :
1. https://console.firebase.google.com → **Ajouter un projet** (Analytics inutile).
2. **Build › Realtime Database › Créer une base** (région au choix, mode verrouillé). Notez l'adresse `https://<projet>-default-rtdb.<région>.firebasedatabase.app`.
3. **Build › Authentication › Commencer › Méthode de connexion › Anonyme › Activer.**
4. Onglet **Règles** de la Realtime Database : collez le contenu de `firebase/database.rules.json` puis **Publier**.
5. **Paramètres du projet › Général › Clé API Web** : copiez-la (elle n'est pas secrète, elle identifie le projet ; les règles protègent la base). Facultatif : restreindre la clé aux API *Identity Toolkit* et *Token Service* dans Google Cloud › Identifiants.
6. Donnez les deux valeurs à l'application :
   - **dans l'application** : Partage › Serveur de coordination › adresse + clé (les groupes créés ensuite l'utilisent, et l'invitation les transmet aux autres, qui n'ont rien à saisir) ;
   - **ou dans l'APK de la CI** : secrets du dépôt GitHub `TOOLMAP_FIREBASE_DB_URL` et `TOOLMAP_FIREBASE_API_KEY` (lus par `app/build.gradle.kts` ; en local : `-Ptoolmap.firebase.url=… -Ptoolmap.firebase.key=…` ou les variables d'environnement du même nom). L'APK embarque alors le serveur : tout le monde l'utilise par défaut.
7. Facultatif, plan Spark : **Alertes de budget** inutiles (pas de facturation), mais surveiller *Utilisation* dans la console.

## 9. Vérification
- JVM : `RelayTest` (adresses, invitations, états), `RoomStateTest` (arbre, flux SSE), `ShareControllerRelayTest`.
- Émulateur Firebase réel (auth + base, règles chargées) : `scripts/firebase-emulator-test.sh` (`FirebaseRelayEmulatorTest`, désactivé sans `FIREBASE_EMULATOR=1`) : deux clients s'échangent position et texte, présence, départ, retrait.
- **Non vérifié** : sur un vrai projet Firebase et sur téléphone (réseau mobile, écran éteint, quotas).
