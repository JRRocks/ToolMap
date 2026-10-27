# Spécification — Partage de position en groupe : protocole et mode direct (N1)

> Tâches P8.x (mode direct) et P13.x (serveur) de `docs/PLAN.md`. Décisions : ADR-004 (mode direct : appairage par QR, pas de TURN), ADR-021 (réalisation et écarts), ADR-033 (serveur de coordination), ADR-048 à 051 (apparence des membres, partage écran verrouillé).
> Ce fichier décrit le **protocole commun** (messages chiffrés, maillage, cadence, reconnexion) et le **mode direct** WebRTC. Le transport par serveur est décrit dans `docs/specs/firebase-relay.md`. Révisé le 2026-10-16 (session 32) pour intégrer les deux modes au texte ; l'historique des écarts reste au §11.

## 1. Objectifs
- Chaque membre d'un groupe envoie sa position toutes les **x secondes** (réglable de 5 s à 30 min) et voit celle des autres sur la carte.
- Deux modes de transport, avec les **mêmes trames chiffrées** (§5) :
  - **Serveur de coordination** (mode par défaut des nouveaux groupes depuis P13, ADR-033) : une base Firebase Realtime Database **appartenant à l'utilisateur** relaie les trames. Le serveur ne voit que des trames chiffrées de bout en bout avec la clé du groupe, plus la présence, les identifiants et les noms affichés (`firebase-relay.md` §2). Il passe là où la liaison directe échoue (CGNAT des réseaux mobiles). Invitation par lien ou QR `toolmap://join`, sans réponse à scanner.
  - **Mode direct** (P8, conservé pour les groupes existants) : **aucun serveur ni relais tiers**. Les seuls services externes possibles sont des serveurs STUN publics, qui renvoient à un téléphone son adresse IP publique ; aucune donnée applicative n'y transite et ils sont désactivables. Appairage par QR ou lien `toolmap://pair` (§3).
- Doit marcher écran éteint et téléphone verrouillé (service de premier plan relancé par le système, ADR-051) et rester économe en batterie.

## 2. Pile technique
- WebRTC natif Android : `io.getstream:stream-webrtc-android` (build maintenue de libwebrtc), **DataChannel uniquement**, sans audio ni vidéo.
- Deux canaux par liaison :
  - `ctrl` : fiable et ordonné (HELLO, PEERS, SIGNAL, MSG, SOS, BYE) ;
  - `pos` : non ordonné, `maxRetransmits = 0` (POS, PING) ; une position perdue est remplacée par la suivante.
- Lecture des QR : CameraX + ZXing (`zxing-core`), sans Google Play Services. Génération : ZXing.
- Interfaces `MeshLink` (une liaison ouverte) et `PairingTransport` (liaisons du maillage + première invitation), dans `:core`. Implémentations : `WebRtcLinks` (mode direct) et `FirebaseRelay` / `RelayTransport` (serveur : une liaison par membre présent, marquée `MeshLink.isBroadcastMedium` pour ne pas réinonder, voir `firebase-relay.md` §3), toutes deux dans `:p2p`. Futur (P8.9) : `NearbyTransport` (Bluetooth/Wi-Fi), `MeshtasticTransport` (LoRa).

## 3. Appairage (signalisation hors bande)

### 3.1 Déroulé
1. **A** crée un groupe : `groupId` (16 octets aléatoires) + `groupKey` (32 octets aléatoires).
2. A crée une PeerConnection et une offre, puis attend la **fin de la collecte ICE** (non progressif, délai max 5 s). Il affiche le **QR « invitation »**.
3. **B** scanne le QR, applique l'offre, crée la réponse, attend la collecte ICE, puis affiche le **QR « réponse »**.
4. A scanne la réponse et la connexion s'établit. B reçoit `HELLO` puis `PEERS` (liste des membres).
5. Les deux QR sont aussi disponibles en **lien texte** `toolmap://pair?d=<base64 URL-safe>` (le Base45 contient des espaces et des `%` que les messageries abîment), à envoyer par SMS ou messagerie. Cela permet un appairage à distance, ou un ré-appairage par SMS si le groupe est coupé du réseau de données.

### 3.2 Payload binaire (version 1)
Les entiers sont en big-endian. `str` = longueur sur 1 octet + UTF-8.

| Champ | Taille | Note |
|---|---|---|
| magic | 2 | `0x544D` (« TM ») |
| version | 1 | `1` |
| kind | 1 | `1` = OFFER, `2` = ANSWER, `3` = MESH_OFFER (offre relayée par le maillage, sans clé de groupe) |
| createdAt | 4 | secondes epoch ; un QR de plus de 10 min est refusé |
| groupId | 16 | |
| groupKey | 32 | **OFFER uniquement** |
| memberId | 8 | aléatoire, stable par appareil et par groupe |
| name | str | ≤ 24 octets |
| color | 1 | index de palette |
| iceUfrag | str | |
| icePwd | str | |
| dtlsFingerprint | 32 | SHA-256 |
| dtlsSetup | 1 | 0 = actpass, 1 = active, 2 = passive |
| candidateCount | 1 | |
| candidate[] | 8 ou 20 | type (host/srflx/prflx), famille (v4/v6) sur 1 octet, IP 4 ou 16 octets, port 2, priorité relative 1 |

Encodage : binaire → deflate (seulement si le résultat est plus court ; drapeau dans `version`) → **Base45** → QR en mode alphanumérique, correction d'erreur M. Taille attendue : 170 à 300 octets, soit un QR de version 10 à 13.

La SDP complète est **reconstruite** de chaque côté à partir d'un gabarit fixe : `m=application 9 UDP/DTLS/SCTP webrtc-datachannel`, `a=mid:0`, `a=sctp-port:5000`, plus les champs ci-dessus. Le codec (encodage, décodage, reconstruction) vit dans `:core` et est testé en JVM.

### 3.3 Sécurité
- Le QR d'invitation contient la **clé de groupe** : quiconque le photographie peut rejoindre le groupe. L'UI doit le rappeler (« Ne montrez ce code qu'aux participants »). Le QR expire au bout de 10 min.
- Retrait d'un membre : nouvelle `groupKey` générée et diffusée aux membres restants (message `REKEY` chiffré avec l'ancienne clé, sauf pour le membre exclu, dont on ferme les liaisons).
- Les empreintes DTLS des QR authentifient les deux extrémités. Les messages relayés sont chiffrés avec la clé de groupe : un membre intermédiaire ne peut ni forger ni lire un message s'il n'appartient pas au groupe.

## 4. Groupe maillé
- Un nouvel arrivant s'appaire avec **n'importe quel** membre, une seule fois.
- Ce membre diffuse `PEERS`. Pour chaque autre membre C, l'appareil au plus petit `memberId` initie une offre transmise en `SIGNAL{from, to, payload}`, routée par les liaisons existantes. Aucun QR supplémentaire n'est nécessaire.
- Si la liaison directe B↔C échoue (NAT), B et C restent joignables par **relais** : chaque nœud réémet vers ses autres voisins tout `POS` encore inconnu. Déduplication par `(memberId, seq)`, TTL de 4 sauts.
- Taille cible : jusqu'à 10 membres.

## 5. Messages applicatifs
Enveloppe : `type (1) | nonce (12) | AES-256-GCM(payload) | tag (16)`. La clé de chiffrement est dérivée par `HKDF-SHA256(groupKey, info = "toolmap-p2p-v1")`.

| Type | Canal | Contenu |
|---|---|---|
| `HELLO` | ctrl | memberId, nom, apparence (icône + couleur RGB sur 4 octets, `MemberLook`, ADR-048), version du protocole (2), capacités |
| `PEERS` | ctrl | liste (memberId, nom, apparence sur 4 octets, dernier vu) |
| `AVATAR` | ctrl | memberId, seq, image de profil JPEG 64 × 64 (6 Ko au plus ; vide = retirée) ; propagée comme un texte (ADR-049) |
| `SIGNAL` | ctrl | from, to, payload d'appairage (même codec qu'en §3.2), redémarrage ICE |
| `POS` | pos | memberId (8), seq (u32), t (u32), lat et lon (i32, 1e-7 °), alt (i16, m), précision (u16, m), vitesse (u16, cm/s), cap (u16, 0,1 °), batterie (u8, %), drapeaux (u8) : ≈ 40 octets |
| `TRAIL` | ctrl | (option) positions accumulées pendant une coupure, envoyées à la reconnexion |
| `PING` | pos | toutes les 15 s ; un membre est déclaré perdu après 45 s sans nouvelles ; par le serveur, il passe avec les messages de contrôle (`c`), pas dans la case de la position (P21.5 b) |
| `MSG` | ctrl | texte court (≤ 140 caractères) |
| `SOS` | ctrl | position + texte ; diffusion immédiate, alerte sonore et vibrante chez les autres |
| `REKEY` | ctrl | nouvelle clé de groupe |
| `BYE` | ctrl | départ volontaire |
| `KICK` | ctrl | (ajouté en P8) envoyé à l'exclu sur sa propre liaison, chiffré avec l'**ancienne** clé : sinon il ignorerait qu'il est dehors |
| `DIAG` | ctrl | (ajouté en P21, ADR-070) memberId, seq, charge utile des outils de test du build de debug (journal d'événements compressé, accusés du collecteur ; 4096 octets au plus) ; propagée comme un texte, jamais affichée ; un APK de release la relaie et l'ignore (`docs/specs/event-log.md` §4) |

## 6. Cadence et énergie
- `LocationManager.requestLocationUpdates(GPS_PROVIDER, minTime = x s, minDistance = 0)`. Envoi à chaque mise à jour, sauf si le déplacement depuis le dernier envoi est inférieur au seuil d (option, ex. 10 m) ; un envoi est forcé au moins toutes les 5·x s pour signaler qu'on est toujours là.
- Service de premier plan `foregroundServiceType="location"` avec une notification permanente (« Partage actif — 3 membres »).
- Réglage par défaut : x = 30 s.

## 7. Reconnexion
- Échec ICE sur une liaison : si le membre est encore joignable via le maillage, une **nouvelle offre** (genre MESH_OFFER) est négociée par `SIGNAL` relayé (le redémarrage ICE prévu à l'origine n'est pas réalisé : voir ADR-021).
- Une coupure brève sans changement d'IP se rétablit d'elle-même : l'état ICE passe de `disconnected` à `connected` sans nouvelle signalisation.
- Membre totalement isolé (état ICE `failed`, aucun chemin dans le maillage) : ses derniers points restent affichés avec la mention « déconnecté depuis HH:MM ». Il faut ré-appairer par QR ou par **lien SMS**.

## 8. Limites du mode direct (à afficher dans l'aide de l'application)
> Le mode serveur (P13) lève la limite du CGNAT : ces points ne valent que pour les groupes en mode direct.
- **CGNAT symétrique** (fréquent chez les opérateurs mobiles) : deux téléphones en 4G derrière ce type de NAT ne peuvent pas établir de liaison directe sans serveur TURN, exclu par choix. Le relais par un troisième membre bien connecté peut compenser.
- Si tout le groupe perd le réseau assez longtemps pour que les IP changent : ré-appairage (QR ou SMS).
- **Astuce terrain** : un membre active son point d'accès Wi-Fi et les autres s'y connectent. Le réseau local ainsi formé fonctionne **sans aucune couverture mobile** (portée de quelques dizaines de mètres) et sans STUN.
- Hors de portée de tout réseau : seuls les transports futurs (Nearby, LoRa/Meshtastic) pourront aider.

## 9. Interface
- Écran « Partage » : créer ou rejoindre un groupe, afficher ou scanner un QR, partager un lien, liste des membres (état, distance, cap, âge de la position, batterie), intervalle x, seuil d, STUN activé ou non.
- Carte : marqueur par membre (jeton : silhouette de jeu ou initiales de la couleur choisie au cercle chromatique, sur un disque contrasté cerclé de cette couleur, ou image de profil ; ADR-048 à ADR-050), cercle de précision, traînée des N dernières positions (réglage « Tracé des membres » : sinon position actuelle seule), étiquette d'âge (« il y a 2 min »).
- Actions : centrer sur un membre, le définir comme cible (cap et distance), exporter sa traînée en GPX, envoyer un message ou un SOS.

## 10. Tests
- JVM (`:core`) : codec QR (aller-retour, taille maximale, refus si expiré ou corrompu), reconstruction SDP, codec des messages, chiffrement, déduplication et TTL du relais, algorithme d'initiateur du maillage.
- Sur appareils : 2 téléphones sur le même Wi-Fi, puis en 4G (opérateurs différents), puis 3 téléphones en maillage avec un nœud relais. Résultats consignés dans `docs/JOURNAL.md`.

## 11. Réalisation (état après P8)
| Sujet | Réalisé | Où |
|---|---|---|
| Codecs | Base45 (vecteurs RFC 9285), charge d'appairage binaire + deflate, lien base64 URL-safe, péremption à 10 min, élagage des candidats, reconstruction SDP | `Base45`, `Pairing`, `Sdp` |
| Messages et chiffrement | 11 types dont `KICK`, enveloppe AES-256-GCM, clé HKDF-SHA256, TTL 4, relais sans rechiffrer | `Wire`, `GroupCrypto` |
| Maillage | liaisons manquantes négociées par `SIGNAL` relayé (le plus petit id offre), inondation + déduplication, pings 15 s, perdu après 45 s, réessais exponentiels, rejeu de la traînée manquée à la reconnexion, retrait avec nouvelle clé | `MeshNode` |
| Cadence | intervalle ≥ 5 s, déplacement minimal, envoi forcé après 5 intervalles | `ShareCadence`, `ShareController` |
| WebRTC | deux canaux prénégociés, ICE non progressif (5 s), STUN activable | `WebRtcLinks` (`:p2p`) |
| Interface | écran « Partage » (profil, groupe, invitation / réponse en QR et en lien, scan caméra, coller un lien, cadence, STUN, membres, messages, SOS, quitter), disques et traînées sur la carte, fiche de membre (voir, aller vers, enregistrer la traînée, retirer), bandeau SOS et notification, lien `toolmap://pair` ouvert depuis une messagerie | `fr.toolmap.share`, `ShareLayers` |
| Service | premier plan `location` pendant le partage, verrou de réveil partiel, relancé par le système après une mort du processus si le partage était actif, reconnexion au serveur à chaque changement de réseau, demande d'exemption de batterie (ADR-051) | `ShareService` |

**Écarts à la spec d'origine** : lien en base64 URL-safe ; genre MESH_OFFER ; message KICK ; reconnexion par nouvelle offre relayée et non par redémarrage ICE ; pas d'administrateur (tout membre peut en retirer un autre) ; pas d'étiquette d'âge sur la carte (pas de polices) : l'âge est dans la fiche ; pas de cercle de précision sur la carte ; **distance et cap vers chaque membre** : pas dans la liste, seulement via « Aller vers » (guidage de P6) ; le partage reprend après une mort du processus (service collant, ADR-051) ; un membre qui ne partage pas sa position n'a pas de service de premier plan.

**Vérifié en JVM** : codecs (aller-retour, vecteurs, tailles de QR, codes corrompus ou périmés), messages et chiffrement, maillage à plusieurs membres, perte et reconnexion, retrait avec nouvelle clé et `KICK`, cadence, SOS, textes, persistance, `ShareController` avec un faux transport et un vrai nœud pair, GeoJSON de la carte. **Non vérifié** : toute liaison WebRTC réelle (SDP reconstruite acceptée par libwebrtc, ICE sur un vrai réseau, canaux ouverts), lecture d'un vrai QR à la caméra, écrans, service écran éteint, notification, consommation, taille de l'APK.
