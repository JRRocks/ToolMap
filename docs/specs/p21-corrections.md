# Corrections issues du test en direct du partage (P21.5, P21.6)

> **P21 clos le 2026-10-24** (décision de l'utilisateur, ADR-072) : ce qui n'est pas marqué corrigé ici n'est pas fait ;
> les restes sont listés dans `docs/idees-futur.md` (Partage de position).

Dossier de travail : tout ce qu'il faut pour corriger plus tard les défauts trouvés au premier test en direct
(2026-10-24, journal de bord session 53, protocole `live-test.md`). Chaque défaut donne le symptôme, la preuve dans
le journal d'événements, la cause dans le code, la correction proposée et la vérification attendue.

- **Heures** : celles du téléphone (UTC+2) ; les lignes de l'émulateur sont en UTC (07:37 émulateur = 09:37 téléphone).
- **Journaux** : `tools/livetest/out/20261024-094258/` (`remote.log` = journal du téléphone, `current.log` = celui de
  l'émulateur) et `tools/livetest/out/live-2026-10-24-*.log`. **Non versionnés** (positions, adresse du serveur) :
  présents seulement sur le poste Windows. Les numéros de ligne cités ci-dessous datent de la version 0.4.61.
- **Appareils** : téléphone SM-S931U, Android 15, membre `2b3a4cd07df4f754` ; émulateur collecteur
  `f1ec7236c25feaa5` ; réglages du test : 30 s et 10 m, puis 5 s (09:20) et 0 m (09:58).
- **Second test** (2026-10-24, 14:56 → 16:42, 0.4.66, session 54) : `tools/livetest/out/20261024-164215/`
  (`analyse.txt` = `analyze.mjs --from 14:56`) et `tools/livetest/out/live-2026-10-24-1[3-4]-*.log` ; déroulé, statuts
  et notes prises sur le moment : `docs/plan-partage.md` § 3 et § 5. Défauts nouveaux : l à r ; lignes de code citées
  pour eux : version 0.4.66. Corrections de l à r et alertes (o, c) : 0.4.69, session 54.

## Récapitulatif et ordre conseillé

| Ordre | Id | Défaut | Gravité | Visible ? | Fichiers |
|---|---|---|---|---|---|
| 1 | d | ✅ 0.4.62 — Changer l'intervalle arrête le service de premier plan | **Grave** | non | `ShareController.kt`, `ShareService.kt` |
| 2 | a | ✅ 0.4.62 — Après un arrêt forcé (ou une mise à jour), le partage ne reprend pas | Moyenne | non | `ShareService.kt`, démarrage de l'appli |
| 3 | b | ✅ 0.4.64 — Un `PING` remplace la dernière position dans `p` | Moyenne | non | `FirebaseRelay.kt` |
| 4 | e | ✅ 0.4.64 — Trame `p` retenue écrite au battement suivant, pas à la réouverture | Faible | non | `FirebaseRelay.kt` |
| 5 | f | ✅ 0.4.64 — Nouveau réseau par défaut : le flux reste sur l'ancien | Faible | non | `NetworkWatch.kt`, `FirebaseRelay.kt` |
| 6 | g | ✅ 0.4.64 — Flux coupé toutes les heures à l'expiration du jeton | Faible | non | `FirebaseRelay.kt`, `FirebaseRest.kt` |
| 7 | h | ✅ 0.4.64 — Tentatives toutes les 8 s pendant que le réseau est bloqué par Android | Faible | non | `NetworkWatch.kt`, `ShareController.kt`, `FirebaseRelay.kt` |
| 8 | c | 🔄 0.4.69 localisation coupée — Aucun avertissement quand le GPS ne donne plus de fix | Moyenne | **oui** | `ShareController.kt`, `ShareScreen.kt` |
| 9 | t | Message « … est connecté(e) » à chaque reconnexion | Faible | **oui** | `ShareController.kt`, `ShareScreen.kt` |
| 10 | l | ✅ 0.4.69 — Position de départ envoyée seulement en traînée ; la traînée ne met pas à jour la position affichée | Moyenne | non | `MeshNode.kt` |
| 11 | m | ✅ 0.4.69 — Numéros de message en collision après un redémarrage | Faible (forte en mode test) | non | `MeshNode.kt`, `ShareController.kt` |
| 12 | n | ✅ 0.4.69 — Recul de l'horloge : écart au serveur faussé, plus d'envoi de position ni de `PING` | Faible (rare, effets lourds) | non | `FirebaseRelay.kt`, `ShareCadence.kt`, `MeshNode.kt` |
| 13 | q | ✅ 0.4.69 — Battement espacé écran éteint sans partage : liens fermés puis rouverts | Faible | non | `FirebaseRelay.kt` |
| 14 | p | ✅ 0.4.69 — Tentatives toutes les 8 s sans aucun réseau | Faible | non | `FirebaseRelay.kt`, `ShareController.kt` |
| 15 | r | ✅ 0.4.69 — Vieilles trames jamais effacées du serveur | Faible | non | `FirebaseRelay.kt` |
| 16 | o | ✅ 0.4.69 (1, 2) — Économiseur de batterie : GPS coupé écran éteint, sans avertissement | **Grave** (silencieux) | **oui** | `LocationSource.kt`, `SystemEvents.kt`, `ShareController.kt`, `ShareScreen.kt` |
| — | j | 7 Mio téléchargés en 2 s au retour au premier plan | À analyser | non | tuiles (`TileModule.kt`, intercepteur) |
| — | k | Repli sur la localisation réseau quand le GPS cale | À décider | oui | `LocationSource.kt` (contredit ADR-019) |
| — | P21.6 | ✅ 0.4.66 — Garder l'écran du téléphone allumé pendant un test (sans notification) ; pas tenu en arrière-plan sur le téléphone (second test) | Demande | non | `:debugkit`, `tools/livetest/cmd.sh` |

Les lignes « Visible : oui » passent d'abord par un aperçu validé (`CLAUDE.md`). Les autres se corrigent directement,
avec un test unitaire quand c'est possible.

**Ce que l'appli ne peut pas corriger seule** (demande de l'utilisateur, 2026-10-24 : l'alerter et lui dire quoi régler) :
économiseur de batterie qui coupe le GPS écran éteint (o), batterie de l'appli « optimisée » ou « restreinte » (Android peut
arrêter le partage en arrière-plan), localisation du téléphone coupée (c, n° 11), plus de fix GPS (c), économiseur de
données qui bloque le réseau en arrière-plan (h, « À noter »). Alertes visibles : aperçu `docs/previews/p21-alertes.html`
à valider avant de coder.

**Fait en 0.4.69** (session 54, variante B de l'aperçu validée le 2026-10-24, ADR-071) : localisation coupée (rouge),
économiseur de batterie et batterie de ToolMap optimisée (orange). Une carte par réglage sous l'interrupteur « Partager
ma position », avec un bouton vers le bon écran des réglages ; un dialogue « Le partage risque de s'arrêter » quand on
allume le partage (« Partager quand même », « Régler ») ; la pastille de la carte passe en orange avec ⚠ ; une 2e ligne
dans la notification (le réglage le plus grave). Relu à chaque retour sur l'écran et toutes les 5 s dans un groupe
(`share.alerts`). Comparaison avec l'émulateur : `docs/previews/p21-alertes-emulateur.html`. **Pas encore validés**
(aperçu complémentaire) : plus de fix GPS depuis 2 min (c), économiseur de données (h), batterie « restreinte » ; et le
message t.

---

## d — Changer l'intervalle d'envoi arrête le service de premier plan (grave)

> **Corrigé en 0.4.62** (session 53) : `ShareController.updateSettings` ne fait plus que relancer la demande GPS
> (`startFixes`, événement `share.gps.restart`), le partage ne passe jamais par « coupé » ; l'observateur de
> `ShareService` tourne sur `Dispatchers.Main` (plus `immediate`). Test `a new interval asks the GPS again without the
> sharing going off` (`ShareControllerTest`, en échec avant la correction). Vérifié sur l'émulateur : 30 s → 5 s → 30 s,
> service toujours au premier plan, aucun `share.service.finish`. À revérifier sur le téléphone (T12).

**Symptôme.** Après un changement d'intervalle dans Réglages d'envoi, l'interface dit toujours « partage actif », mais
dès que l'appli passe en arrière-plan plus aucune position ne part. Elles ne reviennent que quand ToolMap est à
l'écran (vu de 09:45 à 09:58). Couper puis relancer « Partager ma position » répare.

**Preuve** (`remote.log`, 09:20:20, intervalle 30 s → 10 s puis 5 s) :

```
09:20:20.193 share.settings intervalS=10 minMoveM=10 …
09:20:20.198 share.sharing on=false was=true
09:20:20.198 gps.stop client=2e5f44e
09:20:20.198 share.service.finish            ← même milliseconde
09:20:20.199 share.sharing on=true was=false
09:20:20.200 gps.request client=7fcf883 intervalMs=10000
09:20:20.210 share.service.destroy
```

Dernier fix en arrière-plan à 09:22:35 ; à 09:41:07, `snap.share sharing=true gps=true gpsUnavailable=false
lastFixAgoS=1111`. Changer la distance minimale ne le déclenche pas (09:58:24, seul l'intervalle compte).

**Cause.**
- `ShareController.updateSettings` (≈ l. 649-652) relance le GPS en passant par l'état « arrêté » :
  `if (rhythmChanged && gps != null) { setSharing(false); setSharing(true) }`.
- `setSharing(false)` publie `sharing = false` ; `ShareService.observe` (≈ l. 162-171) collecte l'état sur
  `Dispatchers.Main.immediate`, donc **sur-le-champ**, au milieu de `updateSettings` : il voit `sharing = false` et
  appelle `finish()`.
- `finish()` (l. 118-123) écrit `active = false` et `stopSelf()` : plus de service de premier plan ni de verrou de
  réveil, et pas de relance par le système (`START_NOT_STICKY`, `active` faux).
- Le flux GPS relancé tourne dans le processus (client `841e119`), mais sans service de premier plan Android ne le
  sert qu'appli visible.

**Correction proposée.**
1. Dans `ShareController`, relancer seulement le flux GPS, sans repasser par `sharing = false` : extraire de
   `setSharing(true)` une fonction privée `startFixes()` (lancement du job `gps`) et écrire dans `updateSettings` :
   `gps?.cancel(); gps = null; startFixes()`, sous le verrou, sans toucher à `mutableState`.
2. Défense en profondeur dans `ShareService.observe` : ne pas finir sur un `sharing = false` qui n'est pas un arrêt
   voulu. Par exemple un champ `ShareUi.stopReason` (`USER`, `GPS_ERROR`) posé par `setSharing(false)` et par le
   `.catch` du GPS, et `finish()` seulement dans ces cas ; ou collecter sur `Dispatchers.Main` (pas `immediate`) pour
   que les états transitoires soient fusionnés. La solution 1 suffit à corriger le défaut ; la 2 évite qu'il revienne.

**Vérification.**
- Test unitaire (`ShareControllerTest`, `FakeGps` existe déjà) : partage allumé, `updateSettings` avec un autre
  intervalle ; tous les états émis gardent `sharing = true` (collecteur sur `UnconfinedTestDispatcher`) et le
  `FakeGps` reçoit une nouvelle demande avec le nouvel intervalle.
- Test en direct (T12 à refaire) : changer l'intervalle, appli en arrière-plan et écran éteint ; positions continues,
  aucun `share.service.finish`.

## a — Le partage ne reprend pas après un arrêt forcé ou une mise à jour

> **Corrigé en 0.4.62** (session 53) : à l'ouverture de l'appli, `ShareViewModel` appelle
> `ShareService.resumeIfInterrupted` : si le service a laissé « actif = vrai » (pas d'arrêt par l'utilisateur), que
> l'appareil est dans un groupe, ne partage pas et a la permission de localisation, le service repart (`share.resume`).
> Tests `ShareServiceResumeTest`. Vérifié sur l'émulateur après `adb install -r` (0.4.60 → 0.4.62, `app.exit
> reason=PACKAGE_UPDATED`) et après `am force-stop` : `share.resume permitted=true`, `share.service.start`,
> `share.sharing on=true` 1 s après le lancement. Vu aussi en direct sur le téléphone de l'utilisateur avant la
> correction (mise à jour 0.4.61 à 11:24:42, partage resté coupé). Limite : sans ouverture de l'appli après un arrêt
> forcé, rien ne repart (Android ne relance pas une appli arrêtée de force).

**Symptôme.** Après Paramètres › Applis › ToolMap › Forcer l'arrêt (ou l'installation d'une nouvelle version), à la
relance de l'appli le partage reste coupé alors que l'utilisateur ne l'a pas arrêté. Vu à la répétition à deux
émulateurs (2026-10-23) et en T15 sur l'émulateur.

**Cause.** `ShareService` n'est relancé que par le système (`START_STICKY`, `onStartCommand` l. 92 avec
`intent == null`), ce qu'un arrêt forcé ou une mise à jour empêche. La préférence `share-service/active` reste vraie
(seul `finish()` l'efface), mais personne ne la lit au démarrage de l'appli.

**Correction proposée.**
- Exposer `ShareService.wasActive(context)` (lecture de `KEY_ACTIVE`).
- Au démarrage visible de l'appli (création de `MainActivity` ou `ShareViewModel`) : si `wasActive`, qu'un groupe
  existe et que la permission de localisation est accordée, appeler `ShareService.start(context)` (démarrage d'un
  service de premier plan permis depuis une activité visible).
- À étudier : un récepteur `ACTION_MY_PACKAGE_REPLACED` (après une mise à jour) et `BOOT_COMPLETED` (T16). Ces deux
  diffusions font partie des exceptions au démarrage en arrière-plan d'un service de premier plan, mais le type
  `location` demande en plus que l'appli ait la localisation « pendant l'utilisation » accordée et soit dans un état
  admis : à vérifier sur Android 14 et 15 avant de compter dessus.

**Vérification.** Sur l'émulateur : `adb shell am force-stop fr.toolmap`, puis lancer l'appli ; dans le journal,
`share.service.start action=start` et `share.sharing on=true` sans rien toucher. Idem après `adb install -r`.

## b — Un PING remplace la dernière position dans `p`

> **Corrigé en 0.4.64** (session 53) : `FirebaseRelay.send` n'écrit dans `p` que les `POS` ; les `PING` passent par
> `c` (`db.push`, nettoyés au bout de 120 s). `relay.p.replaced` n'est plus émis pour une trame dont l'écriture a
> commencé (`writingPosition`). Test `a PING goes with the control frames and leaves the latest position in p`
> (`FirebaseRelayFixesTest`, contre un faux Firebase). Compatible avec les versions précédentes : un `PING` est traité
> pareil quel que soit le canal. Vérifié sur l'émulateur (0.4.64) : `relay.write what=c type=PING` toutes les 15 s,
> `what=p` seulement pour les `POS`.

**Symptôme.** Un membre qui arrive (ou un collecteur relancé) voit l'autre « sans position » jusqu'à la position
suivante, ce qui peut prendre plusieurs minutes quand le téléphone ne bouge pas (distance minimale). Vu en T15 :
l'émulateur relancé a affiché le téléphone `nofix`.

**Preuve.** `relay.p.replaced old=POS new=PING` (téléphone 09:11:38 `queued=1`, 10:02:31 `queued=0`) ; rapport
`analyze.mjs` : 1 remplacement POS→PING côté téléphone, 6 côté émulateur.

**Cause.**
- `Msg.Ping` est sur le canal des positions (`Wire.kt` ≈ l. 144, `onPositionChannel = true`) ; `MeshNode.tick`
  (≈ l. 628-631) en diffuse un toutes les 15 s (`PING_EVERY_S`). Sur WebRTC c'est un canal non ordonné ; sur le
  serveur, c'est **une seule case** `rooms/<salon>/b/<membre>/p`.
- `FirebaseRelay.send` (≈ l. 670-685) met toute trame du canal des positions dans `pendingPosition`, puis dans `p` :
  le `PING` écrase le `POS`.
- L'événement est trompeur : le commentaire « Not written yet and never will be » est faux quand l'écriture de
  l'ancienne trame a déjà commencé (`queuePosition` l. 510-519 ne vide `pendingPosition` qu'après l'écriture) ; c'est
  le cas `queued=0`, où la position est bien arrivée.

**Correction proposée** (sans changer les règles Firebase, dont `$x` refuse toute nouvelle case) :
- Dans `FirebaseRelay.send`, envoyer les `PING` par le canal de contrôle `c` (`db.push`) au lieu de `p` : `p` ne
  garde que des `POS`. Les `PING` servent à la vivacité des liaisons dans `MeshNode` (membre perdu après 45 s sans
  trame) : ils restent donc nécessaires ; sur `c`, ils sont nettoyés par `cleanUp` (`OWN_FRAME_TTL_MS` = 120 s) et
  écartés à la réception au-delà de 150 s (`CONTROL_MAX_AGE_MS`), ce qui convient.
- Garder la trame en cours d'écriture (`writingPosition`) et n'émettre `relay.p.replaced` que si la trame remplacée
  n'avait pas commencé ; corriger le commentaire.
- Variante écartée : une case `q` pour les `PING` (demande de changer `firebase/database.rules.json` et de redéployer
  les règles dans la console, `firebase-relay.md`).

**Vérification.** Test dans `FirebaseRelayEmulatorTest` ou `RoomStateTest` : après `POS` puis `PING`, la case `p` lue
par un nouveau membre contient le `POS`. En direct : T15 (collecteur relancé), le téléphone apparaît avec sa position
dès l'ouverture du flux.

## e — Une trame `p` retenue n'est écrite qu'au battement suivant

> **Corrigé en 0.4.64** (session 53) : `openStream` met la position retenue en écriture dès que le flux est ouvert ;
> de même au déblocage du réseau (h) si le flux est resté ouvert. Test `a position held during an outage is written as
> soon as the stream reopens` (`FirebaseRelayFixesTest`).

**Symptôme.** Après une coupure, la dernière position attend jusqu'à 15 s après la reconnexion (T18, coupure de 30 s).

**Cause.** `FirebaseRelay.heartbeat` (≈ l. 497-507) est le seul endroit qui relance `queuePosition` quand
`pendingPosition` n'est pas vide ; `openStream` (l. 380-393) passe `streamUp` à vrai sans rien écrire.

**Correction proposée.** Dans `openStream`, juste après `streamUp = true; publishState()` : si `pendingPosition != null`,
`queuePosition(<nœud du membre>)`.

**Vérification.** En direct (T18) : `relay.write what=p` moins de 1 s après `relay.stream.open`. Test unitaire possible
avec une fausse base qui échoue puis réussit (`FirebaseRelayReconnectTest`).

## f — Nouveau réseau par défaut : le flux reste sur l'ancien

> **Corrigé en 0.4.64** (session 53) : le contrôleur dit au relais chaque réseau par défaut (`RelayTransport.onNetwork`,
> nom du `Network`) ; le relais note celui du flux à son ouverture, et `reconnectNow` annule un flux ouvert sur un autre
> réseau même s'il a moins de 5 s (`relay.reconnect.now cancel=true moved=true`). Test `a stream opened on the previous
> network is dropped at once, even a recent one` (`FirebaseRelayFixesTest`) ; les deux tests de
> `FirebaseRelayReconnectTest` passent toujours.
>
> **Complété en 0.4.65** : sur l'émulateur, en 0.4.64, le flux quittait bien le réseau mobile au retour du Wi-Fi
> (`moved=true`, rouvert en 0,25 s) mais retombait 30 s plus tard (`Software caused connection abort`) : il avait été
> rouvert sur une connexion de l'ancien réseau gardée par le pool d'OkHttp : une connexion libre laissée par les
> écritures faites en réseau mobile (protocole `http/1.1`). Le relais a maintenant son propre pool, remplacé à
> chaque nouveau réseau par défaut (`relay.network pool=new`) ; l'ancien est vidé (connexions libres aussitôt, les
> autres à la réouverture du flux, toutes à la fermeture du relais). Le même test vérifie que le premier appel après le
> changement ouvre une connexion neuve : il échoue sans ce changement. `relay.stream.open` donne aussi le protocole
> (`proto`). Vérifié sur l'émulateur (0.4.65) : Wi-Fi coupé puis rendu, `relay.network pool=new` et `moved=true` à
> chaque changement, flux rouvert en 0,3 s (mobile) et 1,7 s (Wi-Fi, nouvelle connexion TLS), aucune coupure dans les
> 50 s qui suivent (0.4.64 : `Software caused connection abort` 30 s après le retour du Wi-Fi).

**Symptôme.** Quand un second réseau arrive peu après une reconnexion (mobile puis Wi-Fi), le flux reste sur le
premier et tombe quand Android le coupe, ≈ 30 s plus tard (coupure ≈ 1,3 s).

**Preuve** (`current.log` de l'émulateur, heures UTC) :

```
07:37:27.481 relay.stream.open ms=60                 (réseau 106, mobile)
07:37:31.171 share.net.available net=107 previous=106 retry=true   (Wi-Fi)
07:37:31.172 relay.reconnect.now cancel=false        ← flux jugé « frais » : pas annulé
07:38:01.155 relay.stream.end upS=34 why="Software caused connection abort"
07:38:02.437 relay.stream.open ms=45
```

Même chose à 07:25:15 → 07:25:45. Sur le téléphone (Wi-Fi → 4G en T9), l'ancien réseau a disparu d'abord : pas de
défaut.

**Cause.** `FirebaseRelay.reconnectNow` (l. 354-367) n'annule pas un flux ouvert depuis moins de 5 s
(`FRESH_STREAM_MS`), garde prévue pour l'appel `onAvailable` immédiat à l'inscription et pour `validated`. Elle ne
sait pas sur quel réseau le flux a été ouvert.

**Correction proposée.** `ShareController.reconnectServer(network)` passe une étiquette du réseau (son `toString()`
ou `networkHandle`) à `relay.reconnectNow(tag)` ; le relais note l'étiquette du réseau par défaut au moment
d'`openStream` et annule l'appel en cours dès qu'on lui donne une étiquette différente, frais ou pas. La garde
« frais » ne vaut plus que pour le même réseau.

**Vérification.** Sur l'émulateur : `svc wifi disable`, attendre la reconnexion en mobile, puis `svc wifi enable` ;
`relay.reconnect.now cancel=true` et aucun `Software caused connection abort` 30 s plus tard.

## g — Le flux se coupe toutes les heures à l'expiration du jeton

> **Corrigé en 0.4.64** (session 53), la correction « mieux » et le minimum : le flux retient son jeton ; après chaque
> écriture réussie, si `FirebaseAuth.current` a changé (renouvelé 2 min avant l'expiration), le flux est rouvert
> aussitôt avec le nouveau (`relay.stream.renew`, sans pause, l'état reste « en ligne »). Sur `auth_revoked`, seul le
> jeton du flux est oublié (`FirebaseAuth.invalidate(token)`) et la reconnexion se fait sans pause. Le 401 d'une
> écriture n'oublie aussi que le jeton de cette écriture. Test `a renewed token reopens the stream at once, with no gap
> in the state` (`FirebaseRelayFixesTest` : jeton de 121 s, renouvelé une seule fois, second flux avec le nouveau
> jeton, jamais « injoignable »). À vérifier en direct : une heure sans `why="token expired"`.
>
> **Second test** (0.4.66) : deux renouvellements sans coupure : téléphone sans partage (`fb.auth via=refresh` à
> 16:34:25.851, `relay.stream.renew streamAgeS=1747`, flux rouvert 0,18 s après, aucun changement d'état) ; émulateur en
> train de partager (`fb.auth via=refresh` à 14:42:11.940 UTC, `relay.stream.renew streamAgeS=3396`, flux rouvert 0,15 s
> après, toujours `ONLINE`). Aucun `why="token expired"` en 1 h 46 (les deux du journal datent du matin, avant 0.4.64).
> L'heure entière de partage du téléphone (n° 18) a été annulée (trop longue) : reste à la voir sur le téléphone.

**Symptôme.** Une coupure d'environ 1,3 s par heure et par appareil.

**Preuve.** Téléphone : jeton renouvelé à 09:37:58 (`fb.auth via=refresh`), flux coupé à 09:39:57
(`relay.stream.end upS=962 error=IOException why="token expired"`, événement SSE `auth_revoked`), rouvert à
09:39:59.215. Émulateur : même coupure à 08:01:52 (UTC), une heure après qu'il a rejoint le groupe.

**Cause.** `FirebaseAuth.token()` (`FirebaseRest.kt` ≈ l. 57-61) renouvelle le jeton `RENEW_MARGIN_MS` = 2 min avant
l'expiration, quand une écriture le demande ; le flux ouvert garde l'ancien jusqu'à ce que le serveur le révoque
(`FirebaseRelay.onEvent` l. 417-420 lève « token expired ») ; `connectLoop` attend ensuite 1 s avant de recommencer.
De plus, `invalidate()` jette le jeton neuf obtenu 2 min plus tôt : un second renouvellement suit chaque coupure
(émulateur, 2e heure : `fb.auth via=refresh` à 10:59:56, coupure à 11:01:53, nouveau `fb.auth via=refresh` 0,65 s
après, flux rouvert en 1,2 s).

**Correction proposée.**
- Minimum : sur « token expired », recommencer sans la pause (comme un `kicked`), et n'appeler `invalidate()` que si
  le jeton du flux est encore le jeton courant (sinon le jeton neuf sert tel quel).
- Mieux : rouvrir le flux juste après un renouvellement (rappel de `FirebaseAuth` vers le relais, ou comparaison du
  jeton du flux avec `auth.token()` au battement) : nouvelle connexion ≈ 0,3 s, sans perte, puisque l'état complet du
  salon arrive à l'ouverture.

**Vérification.** Test d'une heure : aucun `why="token expired"`, ou une reconnexion de moins de 0,5 s.

## h — Tentatives toutes les 8 s pendant que le réseau est bloqué par Android

> **Corrigé en 0.4.64** (session 53) : le suivi du réseau quitte `ShareService` pour le contrôleur
> (`NetworkWatch`, `AndroidNetworkWatch`), actif tant qu'un groupe sur serveur existe ; il transmet aussi
> `onBlockedStatusChanged` (`share.net.blocked`) au relais (`RelayTransport.onBlocked`). Bloqué : la boucle de
> connexion attend (`relay.blocked.wait`), pas de battement ; débloqué : tentative immédiate. Tests `no attempt while
> Android blocks the network, one at once when it lets it back` (`FirebaseRelayFixesTest`) et `the server connection
> follows the default network as long as the group exists` (`ShareControllerRelayTest`).
>
> Vérifié sur l'émulateur (0.4.65). Sans partage, appli en arrière-plan 95 s : l'Android de l'émulateur ne bloque pas
> le réseau (aucun `net.blocked`). Blocage forcé (`adb shell cmd connectivity set-chain3-enabled true` puis
> `set-package-networking-enabled false fr.toolmap`) : `share.net.blocked blocked=true` puis `relay.blocked` ; le flux
> tombe à l'expiration de sa lecture (45 s), puis `relay.blocked.wait` ; aucune tentative en 75 s. Débloqué :
> `relay.connect` dans la même milliseconde, en ligne 0,83 s après. Reste, sans gravité : pendant le blocage, les `PING`
> du maillage et les trames `DIAG` tentent encore une écriture (échec DNS en 3 ms, sans trafic), et le flux mort reste
> ouvert jusqu'à 45 s ; l'état passe à « injoignable » dès la première écriture échouée (13 s). Piste : couper le flux
> et retenir les trames de contrôle dès le blocage.
>
> **Second test** (n° 17) : non observable sur le téléphone : Android 15 (One UI) n'a pas bloqué le réseau de l'appli en
> 5 min d'arrière-plan sans partage (écran éteint 3 min) ; flux resté ouvert, aucune tentative en boucle. Le blocage
> forcé sur l'émulateur reste la seule vérification. Défaut voisin trouvé pendant ce test : q.

**Symptôme.** Groupe créé, partage pas encore lancé, appli en arrière-plan : Android 15 coupe le réseau de l'appli ;
le relais retente toutes les 8 s pour rien (réveils, journal).

**Preuve** (`remote.log`) : `08:40:03 app.background`, `08:40:08 net.blocked net=1396 blocked=true`, puis 24 tentatives
`UnknownHostException` jusqu'à 08:45:20 ; débloqué à 08:40:55 quand l'appli revient, mais tentative suivante seulement
à 08:41:03 (attente de 8 s en cours), puis bloqué de nouveau 0,2 s après le retour en arrière-plan.

**Cause.** `SystemEvents` (≈ l. 374) ne fait que noter `net.blocked` ; le rappel réseau de `ShareService` n'existe que
pendant le partage ; `connectLoop` (l. 296-343) retente sans fin (attente plafonnée à 8 s, `MAX_BACKOFF_S`).

**Correction proposée.** Un rappel réseau tenu par `ShareController` tant qu'un groupe existe (pas seulement par le
service) ; `onBlockedStatusChanged(blocked)` → `relay.setBlocked(blocked)` : pas de tentative tant que c'est bloqué,
`reconnectNow()` au déblocage.

**À noter.** Un membre du groupe qui ne partage pas sa position et met l'appli en arrière-plan ne reçoit plus rien :
c'est voulu par Android (pas de service de premier plan). Le dire à l'utilisateur serait un changement visible
(aperçu d'abord).

**Vérification.** Groupe sans partage, appli en arrière-plan 2 min : un seul `relay.stream.end`, pas de série de
`relay.connect` ; retour au premier plan : reconnexion en moins de 1 s.

## c — Aucun avertissement quand le GPS ne donne plus de fix (visible : aperçu d'abord)

> **En partie en 0.4.69** (session 54) : la **localisation coupée** est signalée (carte rouge « Localisation coupée »,
> ligne de la notification, dialogue au lancement du partage ; alertes de o, ADR-071), relue en moins de 5 s et
> effacée dès qu'elle revient (`share.alerts alerts=LOCATION_OFF` puis `none`, vérifié sur l'émulateur). **Reste** :
> le GPS qui se tait sans être coupé (« Pas de position GPS depuis N min », `gpsStale`) : son texte est dans l'aperçu
> complémentaire, pas encore validé.

**Symptôme.** 18 min sans aucun fix et l'écran du partage n'en dit rien (`gpsUnavailable=false`). Ça cache aussi le
défaut (d).

**Cause.** Dans `ShareController.setSharing` (l. 537-546), `gpsUnavailable` n'est posé que par le `.catch` du flux (une
erreur) ; un GPS qui se tait n'est pas détecté. `lastFixAtMs` existe déjà (l. 560).

**Correction proposée.** Une vérification périodique dans le contrôleur (au `refresh` ou par un minuteur) : si le
partage est allumé et que le dernier fix date de plus de `max(3 × intervalle, 2 min)`, état `gpsStale = true` et
événement `share.gps.stale` (une fois par épisode) ; l'écran du partage et la notification le disent (« Pas de position
GPS depuis N min »). **Texte et emplacement à valider sur un aperçu avant de coder.**

**Vérification.** Test unitaire avec `FakeGps` et une horloge de test ; en direct, téléphone à l'intérieur loin des
fenêtres.

**Second test.** Confirmé deux fois : localisation coupée (n° 11 : `gps.provider enabled=false` à 15:55:52, vu en
0,06 s, mais aucun `share.problem` et `gpsUnavailable=false` jusqu'au retour) ; économiseur de batterie (n° 14, défaut o).
En plus : avertir **dès** `gps.provider enabled=false` (`LocationSource.kt` l. 65), sans attendre de délai, et effacer
l'avertissement à `enabled=true`. Alertes proposées : aperçu `docs/previews/p21-alertes.html`.

## t — « … est connecté(e) » à chaque reconnexion (visible : aperçu d'abord)

**Symptôme.** Le message apparaît à chaque ouverture de liaison, y compris après une simple coupure ou un redémarrage
de l'autre appareil ; vu sur l'émulateur relancé (T15) alors que le dernier battement du téléphone était ancien (68 s
relevés pendant la séance).

**Cause.** `ShareController`, rappel `onLinkOpen` (≈ l. 201-218) : `justJoined = name` à chaque liaison ouverte ;
`ShareScreen.kt` l. 138 affiche `R.string.share_joined`.

**Correction proposée.** Ne montrer le message que pour un membre jamais vu dans ce groupe (premier lien après une
invitation), ou après une coupure de plus de N minutes ; ne pas ouvrir de liaison sur un battement lu à l'ouverture du
flux s'il est plus vieux que `OPEN_WITHIN_MS` (à vérifier dans `evaluate`). **Comportement à valider sur un aperçu.**

**Second test.** Le code le confirme (`justJoined` remis à chaque `onLinkOpen`, `ShareController.kt` l. 221 en 0.4.66,
montré à la prochaine ouverture de l'écran Partage, même en retard) ; les fermetures et réouvertures de q le déclenchent
aussi. Le message n'est pas un événement du journal : à journaliser (`share.joined.shown`) pour le vérifier.

## j — Téléchargements en rafale au retour au premier plan (à analyser)

**Constat.** Données de l'appli (`TrafficStats` par uid, `snap.sys rxB`) : 32 Mio/h reçues, dont ≈ 2 Mio seulement
pour le flux du serveur. Rafales :
- 09:06:52, écran rallumé, appli au premier plan **2 s** sans déplacement (09:06:53 → 09:06:55) : **+7,06 Mio** en
  5 s, fils 73 → 111, tas natif +88 Mio ;
- 09:22:23 → 09:22:33, départ en voiture, carte à l'écran : +14 Mio en 15 s.

**Pourquoi c'est étonnant.** Chaque tuile affichée est gardée dans le stockage MBTiles et servie de là d'abord
(ADR-003, `TileModule.kt`, `TileInterceptor`) ; le cache ambiant de MapLibre est désactivé (`ToolMapApp.kt`
l. 101-113). Une zone déjà vue ne devrait rien retélécharger.

**Pistes.** Une couche non stockée (altitudes Terrarium des cartes selon l'altitude, P20 ; superpositions), des tuiles
périmées retéléchargées, le style, les icônes ou les polices rechargés à chaque création de la carte.

**Diagnostic proposé.** En debug, un événement de synthèse `tile.net` (par source : requêtes, octets, servies par le
stockage ou par le réseau, toutes les 10 s) ; puis ouvrir et fermer l'appli 5 fois sans bouger et comparer `rxB`.

## k — Repli sur la localisation réseau quand le GPS cale (à décider)

**Constat.** Immobile à l'intérieur (T1), fixes espacés de 40 à 204 s pour 30 s demandées (1 à 5 satellites,
C/N0 ≈ 21 dB-Hz). `AndroidLocationSource.fixes` (`record/LocationSource.kt` ≈ l. 75) ne demande que `GPS_PROVIDER`.

**À décider.** ADR-019 a choisi « GPS seul » : un repli sur `NETWORK_PROVIDER` (précision 20 à 100 m, sans Google Play)
demande un nouvel ADR. Il faudrait marquer ces positions (précision, origine) dans `PeerFix`, et le montrer (visible :
aperçu).

## l — Position de départ envoyée seulement en traînée ; la traînée ne touche pas la position affichée

> **Corrigé en 0.4.69** (session 54) : à la réception d'une `Msg.Trail`, son fix le plus récent, s'il est plus récent
> que la position connue du membre, devient sa position (`recordFix` + `onPosition`). Tests `MeshNodeTest` :
> `positions taken without any link are sent as a trail to the first neighbour` (l'autre a le dernier fix pour
> position) et `a trail older than the known position does not move it`. À revérifier sur le téléphone (relance de
> l'émulateur immobile, mode avion).

**Symptôme.** Au lancement (ou à une reprise), la 1re position est prise avant l'ouverture du lien : elle n'arrive aux
autres que dans la traînée, et ils gardent l'ancienne position affichée (vieille de 20 min sur le téléphone le
2026-10-24) jusqu'à l'envoi suivant : immobile, 5 intervalles plus tard (`KEEP_ALIVE_FACTOR`), soit 25 min avec un
intervalle de 5 min. Même effet après une coupure : la trame `p` retenue est la dernière d'avant la perte du lien, les
suivantes partent en traînée (mode avion 3 min : position vieille de 2 min 27 montrée à l'arrivée).

**Preuve.** Émulateur relancé : `share.fix send=true` 12:45:38.821 UTC, `mesh.pos.held held=1`, lien ouvert à
12:45:39.813, `TRAIL` écrit, aucun `relay.write what=p` ; téléphone `mesh.trail.in fixes=1` à 14:45:41, position de
l'émulateur `fix1217s` ; rattrapé seul à 14:48:08 (`sinceSentS=150`). Mode avion (n° 12) : trame `p` du fix de
15:59:21 reçue `ageS=147`, traînée de 18 positions à 16:01:49.

**Cause.** `MeshNode.publish` (l. 422-431) : sans lien, le fix va dans `missed` ; à l'ouverture d'un lien, `missed`
part en `Msg.Trail` (l. 213). À la réception, `Msg.Trail` (l. 307-312) n'ajoute qu'à `m.trail`, sans `recordFix` ni
`onPosition` : la position affichée ne bouge pas. Et `ShareCadence` compte depuis ce fix, « envoyé » en traînée.

**Correction proposée.** À la réception d'une traînée, si son fix le plus récent est plus récent que la position connue
du membre, le traiter aussi comme une position (`recordFix` + `onPosition`). Test `MeshNodeTest` : publication sans
lien, puis lien ouvert : l'autre nœud a pour position le dernier fix.

**Vérification.** Relancer l'appli de l'émulateur immobile : le téléphone affiche sa position du moment dès l'ouverture
du lien ; mode avion 3 min : position affichée = la plus récente de la traînée.

## m — Numéros de message en collision après un redémarrage

> **Corrigé en 0.4.69** (session 54) : le contrôleur retient le dernier numéro (`ShareStore.writeSeq`, au plus toutes
> les 10 s, `SEQ_KEEP_MS`) ; le nœud suivant part de `max(heure, retenu + 1 000)` (`seqFloor`, `SEQ_MARGIN`). Test
> `MeshNodeTest` : `a node started again above its last message number is heard at once`. À revérifier en mode test
> (Forcer l'arrêt puis relancer : aucun `debug.payload.refused`).

**Symptôme.** Après un arrêt forcé (n° 5) ou une réinstallation (n° 6), le téléphone reprend le partage, mais
l'émulateur jette tout ce qu'il envoie pendant 204 s (puis 28 s) : positions et journal (`debug.payload.refused`).

**Preuve.** Ancien processus `seq=1792848848` à 15:30:45 (203 s d'avance sur l'heure) ; nouveau `seq=1792848665` à
15:31:05 ; `relay.frame.in type=POS` reçus, mais aucun `mesh.pos.in` jusqu'à 15:34:09 (`seq=1792848861`).
Réinstallation : 20 s d'avance, 28 s perdues, 15 `debug.payload.refused`. 23 positions perdues en tout.

**Cause.** `MeshNode.seq` part de `clock() / MS_PER_S` (l. 155) et prend +1 par message (`nextSeq`, l. 710) ; en mode
test (`DIAG` ≈ 1/s, plus `POS` et `PING`), il prend de l'avance sur l'heure. Le nouveau processus repart de l'heure,
sur des numéros déjà vus : `firstTime(member, seq)` (l. 743) les écarte comme doublons. Hors mode test, moins d'un
message par seconde : pas d'avance, pas de collision (sauf rafale de messages).

**Correction proposée.** Le contrôleur retient le dernier numéro utilisé (préférence, écrite au plus toutes les 10 s et
au passage en arrière-plan) ; le nœud repart de `max(heure, dernier retenu + marge)`, la marge (1 000) couvrant les
messages partis après la dernière écriture. Test : nœud recréé avec le numéro retenu, son message suivant est accepté.

**Vérification.** En mode test, Forcer l'arrêt puis relancer : `mesh.pos.in` sur l'émulateur dès le 1er fix ; aucun
`debug.payload.refused`.

## n — Recul de l'horloge : écart au serveur faussé, plus d'envoi de position ni de `PING`

> **Corrigé en 0.4.69** (session 54), points (1) à (3) : `syncClock` refait la mesure (3 essais) quand l'aller-retour
> est négatif ou de plus de 10 s (`relay.clock.bad`) ; un changement d'heure (`ACTION_TIME_CHANGED`, `NetworkWatch`
> → `onClockChanged`) remesure l'écart (`relay.clock.changed`) ; un temps écoulé négatif compte comme écoulé dans la
> cadence, le `PING`, la vérification des liens et le SOS. Tests : `a clock measure spoilt by a clock change is made
> again` (`FirebaseRelayFixesTest`), `pings go on when the clock goes back` (`MeshNodeTest`), `a fix older than the
> last one sent, after the clock went back, is sent` (`WireTest`). Les minuteries sur `elapsedRealtime` restent pour
> plus tard.

Reprend le petit point « Saut de l'horloge d'un appareil ».

**Symptôme.** Quand l'horloge d'un appareil recule (émulateur : 815 s au retour du réseau mobile émulé), il n'envoie
plus sa position, ne fait plus de `PING` (l'autre le perd toutes les ≈ 60 s) ; si le saut tombe pendant la mesure de
l'écart au serveur, cet écart est faux de la moitié du saut. Une horloge qui avance (14:44) sans nouvelle mesure fait
jeter les trames de l'autre.

**Preuve** (n° 8, 15:41) : `sys.time_set`, `relay.clock offsetMs=408740 rttMs=-815350`, corrigé 6,7 s plus tard
(`offsetMs=817194`) ; `share.fix send=false sinceSentS=-695` ; plus de `mesh.ping.out` ; téléphone
`mesh.link.lost silentS=45` à 15:41:56 et 15:42:52. Préparation (14:44) : horloge avancée de 816 s appli lancée,
écart pas remesuré, trames du téléphone jetées (`relay.frame.drop why=nolink`).

**Cause.** `FirebaseRelay.syncClock` (l. 472) : `offsetMs = server - (t0 + t1) / 2`, sans contrôle du temps
d'aller-retour ; l'écart n'est mesuré qu'à la connexion. `ShareCadence.shouldSend` (l. 25-32) compare
`fix.timeSec - last.timeSec` (heure murale) : négatif après un recul, ni l'intervalle ni le maintien ne sont atteints.
`MeshNode.tick` (l. 625-630) compare `t - lastPingMs` (heure murale) : négatif, plus de `PING`.

**Correction proposée.** (1) `syncClock` rejette une mesure dont l'aller-retour est négatif ou de plus de 10 s, et la
refait ; (2) un changement d'heure (`Intent.ACTION_TIME_CHANGED`, déjà relevé en `sys.time_set`) relance la connexion
au serveur, donc la mesure de l'écart ; (3) un écart négatif depuis le dernier envoi (cadence) ou le dernier `PING`
compte comme « intervalle écoulé ». Plus tard, peut-être : minuteries sur une horloge qui ne saute pas
(`elapsedRealtime`), l'heure murale gardée pour les horodatages. Tests `ShareCadenceTest`, `MeshNodeTest`,
`FirebaseRelayFixesTest`.

**Vérification.** Émulateur : heure reculée de 15 min pendant le partage (`cmd alarm set-time`) : positions et `PING`
continuent, `relay.clock` remesuré, aucun lien perdu.

## o — Économiseur de batterie : GPS coupé écran éteint, sans avertissement (visible : aperçu d'abord)

> **Corrigé en 0.4.69** (session 54), points (1) et (2) : `sys.power` relève `locMode=` (mode de localisation de
> l'économiseur) ; alertes de la variante B de `docs/previews/p21-alertes.html` (validée le 2026-10-24, ADR-071) :
> carte « Économiseur de batterie activé » (bouton « Ouvrir l'économiseur ») et « Batterie de ToolMap optimisée »
> (« Mettre sans restriction », demande d'exemption), dialogue au lancement du partage, pastille orange ⚠ sur la carte,
> 2e ligne de la notification. `ShareAlerts.kt`, `ShareController.recheckPhone` (toutes les 5 s dans un groupe et à
> chaque retour sur l'écran), test `the phone settings that cut the sharing are read on demand and every 5 s in a
> group` (`ShareControllerTest`). Vérifié sur l'émulateur (0.4.68 : économiseur, optimisation, localisation coupée ;
> la demande d'exemption acceptée efface la carte). **Reste** : (3) dire aux autres membres que la position est
> suspendue ; vérification sur le téléphone (One UI).

**Symptôme.** Économiseur de batterie activé (One UI, Android 15) : à chaque extinction de l'écran, le système coupe le
GPS, même pour le service de premier plan du partage. Plus aucune position tant que l'écran est éteint (3 min 26, puis
8 min 30 au n° 14), sans rien dire à l'utilisateur ni aux autres ; le réseau, le journal et le lien continuent, donc
les autres voient le membre en ligne, avec une position de plus en plus vieille.

**Preuve** (n° 14) : `gnss.stop` à 16:07:50.763, 17 ms avant `sys.screen_off` ; dernier fix 16:07:45 ; émulateur :
« aucune position reçue depuis 61 s » à 16:08:46, téléphone toujours `DIRECT` ; état `gps=true gpsUnavailable=false` ;
écran rallumé à 16:10:59.895, `gnss.start` 21 ms après, 1er fix 12 s après (`sinceSentS=206`). Économiseur coupé
(16:20:04, `sys.power why=power_save_mode_changed saver=false`) : écran éteint sans `gnss.stop`, fix reçu écran éteint.

**Cause.** Mode de localisation de l'économiseur (`PowerManager.getLocationPowerSaveMode()`, par exemple « GPS coupé
écran éteint ») : décision du système, que l'appli ne peut pas contourner. `sys.power` relève `saver=` mais pas ce
mode ; rien dans `ShareController` ne réagit à l'économiseur.

**Correction proposée.** (1) Relever le mode dans `sys.power` (`locMode=`, sans effet visible) ; (2) alerter : écran
Partage et notification, au lancement du partage et dès que l'économiseur s'allume pendant le partage, avec un bouton
vers les réglages de la batterie ; (3) plus tard : dire aux autres membres que la position est suspendue (champ dans
`PING`). **Textes, emplacement et comportement : aperçu `docs/previews/p21-alertes.html` à valider avant de coder.**
Repli sur la localisation réseau : voir k.

**Vérification.** Téléphone : économiseur activé pendant le partage : alerte visible, `sys.power locMode=…` dans le
journal ; économiseur coupé : l'alerte disparaît.

## p — Tentatives toutes les 8 s sans aucun réseau

> **Corrigé en 0.4.69** (session 54) : `NetworkWatch` dit au contrôleur qu'il n'y a plus de réseau par défaut
> (`onOffline`), qui le dit au relais (`relay.offline offline=true`) ; la boucle attend (`relay.offline.wait`) le
> retour d'un réseau, 5 min au plus (`OFFLINE_MAX_WAIT_MS`), et repart aussitôt au retour (`onNetwork`). Test
> `FirebaseRelayFixesTest` : `no attempt while there is no network, one at once when a network comes`.

**Symptôme.** En mode avion (n° 12, 3 min), le relais retente toutes les 8 s alors qu'il n'y a aucun réseau : 27
tentatives pour rien (réveils, journal).

**Preuve.** `net.lost`, puis `relay.retry waitedS=8` et `relay.stream.end error=UnknownHostException` de 15:59:25 à
16:01:45 ; au retour du réseau, la reconnexion part déjà tout de suite (`share.net.available retry=true`).

**Cause.** `connectLoop` (`FirebaseRelay.kt` l. 342-398) retente avec une attente plafonnée à `MAX_BACKOFF_S` = 8 s,
qu'il y ait un réseau ou non ; seul un blocage d'Android (`onBlocked`, h) l'arrête.

**Correction proposée.** Comme pour h : le contrôleur dit au relais qu'il n'y a plus de réseau par défaut
(`RelayTransport.onOffline`) ; la boucle attend alors son retour (`relay.offline.wait`), avec un plafond long
(5 min) au cas où le rappel manquerait ; au retour, tentative immédiate (déjà le cas). Test dans
`FirebaseRelayFixesTest`, comme celui de h.

**Vérification.** Mode avion 2 min : aucune série de `relay.retry waitedS=8` ; reconnexion en moins de 1 s au retour.

## q — Battement espacé écran éteint quand on ne partage pas : liens fermés puis rouverts

> **Corrigé en 0.4.69** (session 54) : le minuteur passe chaque seconde (`beatIfDue`) et écrit le battement quand
> 15 s d'heure murale sont passées depuis le précédent, ou que l'heure a reculé. Test `FirebaseRelayFixesTest` :
> `the heartbeat follows the wall clock, not the timer's own delay`. À revérifier sur le téléphone (groupe sans
> partage, écran éteint 5 min).

**Symptôme.** Membre du groupe qui ne partage pas, appli en arrière-plan, écran éteint : son battement s'espace (90 s au
lieu de 15 s) ; les autres le croient parti (lien fermé), puis le retrouvent, avec chaque fois le message « … est
connecté(e) » (t).

**Preuve** (n° 17) : `relay.write what=h` toutes les 15 s jusqu'à 16:31:35, puis 16:33:05, 16:33:24, 16:34:03,
16:34:44 ; les `PING` du maillage partent toutes les 15 à 23 s ; émulateur `relay.peer.close beatAgeMs=76038` à
16:32:50, `relay.peer.open` à 16:33:06.

**Cause.** Le battement part du minuteur `toolmap-relay-timer` (`scheduleWithFixedDelay(::heartbeat, HEARTBEAT_S)`,
l. 275) : `ScheduledThreadPoolExecutor` compte avec `System.nanoTime`, horloge qui ne compte pas la veille profonde ;
sans service de premier plan, le processeur dort la plupart du temps et l'attente de 15 s s'étire. Le `tick()` du
maillage, lui, tourne chaque seconde et compare l'heure murale : il rattrape dès que le processeur se réveille (paquets
du flux, toutes les quelques secondes).

**Correction proposée.** Le minuteur passe chaque seconde et envoie le battement quand 15 s d'heure murale se sont
écoulées depuis le précédent (ou que l'heure a reculé), comme le `tick()` du maillage.

**Vérification.** Groupe sans partage, appli en arrière-plan, écran éteint 5 min : battements espacés de 15 à 25 s,
aucun `relay.peer.close` chez l'autre.

## r — Vieilles trames jamais effacées du serveur

> **Corrigé en 0.4.69** (session 54) : à la connexion, `purgeOldFrames` liste ses clés `c` (`shallow=true`) et
> efface d'un `PATCH` de `null` celles qui ne sont pas à ce processus et ont plus de 120 s (heure lue dans la clé,
> `PushIds.timeMs`) ; événement `relay.c.purge keys=… old=…`. Test `FirebaseRelayFixesTest` : `frames left on the
> server by a previous process are deleted at the start`.

**Symptôme.** À chaque ouverture du flux, le serveur renvoie des centaines de vieilles trames, jetées aussitôt
(`relay.frame.drop why=old`) : trafic et travail pour rien, qui grossissent avec le temps.

**Preuve.** n° 17, 16:34:26 : 333 trames de l'émulateur renvoyées au téléphone à la réouverture du flux, la plus vieille
de 7 h 35 (`ageMs=27293761`), surtout `HELLO`, `PEERS` et `PING` ; émulateur, 14:42:12 UTC : 1 180 trames jetées
d'un coup à la réouverture.

**Cause.** `cleanUp` (l. 654) n'efface que les clés de `ownKeys`, gardées en mémoire (l. 827) : les trames écrites
avant un arrêt de l'appli, une réinstallation ou un « Effacer les données » restent sur le serveur.

**Correction proposée.** Au démarrage du relais, lister ses propres clés (`shallow=true`) et effacer d'une écriture
groupée (`PATCH` avec des `null`) celles qui ne sont pas dans `ownKeys` et datent de plus de `OWN_FRAME_TTL_MS` (heure
du serveur lue dans la clé, ou trame lue). Le nœud d'un membre parti sans revenir reste : hors de portée sans fonction
côté serveur.

**Vérification.** Après un redémarrage de l'appli : `relay.c.purge` efface les anciennes clés ; à la réouverture
suivante du flux, quasiment plus de `relay.frame.drop why=old` venant de cet appareil.

## P21.6 — Garder l'écran du téléphone allumé pendant un test (demande du 2026-10-24)

> **Fait en 0.4.66** (session 53), sans la notification (aperçu à valider d'abord) : `cmd.sh awake <minutes>` →
> `DebugCommands.AWAKE` → `DebugKit.setAwake` ; le collecteur met les secondes restantes dans ses annonces (champ `w`
> de la charge `col` plutôt qu'une heure de fin : l'horloge des appareils saute, voir Petits points) ; le téléphone
> (`ScreenAwake`, `PowerScreenLock`) allume l'écran au début de la demande seulement, puis le garde allumé
> (`SCREEN_BRIGHT_WAKE_LOCK`) : éteint au bouton marche, il reste éteint. `cmd.sh awake <minutes> here` (ou sur un
> appareil qui n'est pas le collecteur, téléphone sur USB) : l'écran de l'appareil lui-même, sans que les annonces du
> collecteur l'annulent. Au plus 240 min par commande. Tests `ScreenAwakeTest` (4). `ACQUIRE_CAUSES_WAKEUP` demandera
> un jour la permission `TURN_SCREEN_ON` (documentation d'Android 37) : sans elle, l'écran serait seulement gardé
> allumé une fois allumé.
>
> **Vérifié sur l'émulateur** (0.4.66, mise en veille réglée à 15 s, appli en arrière-plan) : `awake 1 here` garde
> l'écran allumé 60 s (verrou `toolmap:debug-awake` dans `dumpsys power`) puis `on=false why=time` et veille ;
> écran éteint, `awake 5 here` l'allume ; bouton marche : écran éteint et le reste, verrou toujours tenu ;
> `awake 0 here` : `why=asked`, veille 15 s plus tard. Côté collecteur, `cmd.sh awake 5` : `awakeAskS=298` deux
> secondes plus tard, aucun verrou sur l'émulateur ; `awake 0` : 0, préférence remise à 0. Reste le téléphone, qui
> reçoit le champ `w` (second test en direct). La réponse de `cmd.sh` donne l'état d'avant la commande (exécutée
> sur le fil du module, comme `test`) : `cmd.sh status` donne l'état d'après.
>
> Notification « Écran gardé allumé » : **abandonnée** (décision de l'utilisateur, 2026-10-24 : l'écran allumé se
> voit ; pas d'aperçu ni de question pour ce qui ne sert qu'au debug, `CLAUDE.md`). L'aperçu
> `docs/previews/p21-ecran-allume.html` reste pour mémoire.
>
> **Second test, téléphone** (One UI, Android 15) : au premier plan, l'écran reste allumé (n° 2, plus de 10 min sans
> toucher) et l'allumage à distance marche (`ACQUIRE_CAUSES_WAKEUP`, 0,65 s) ; **en arrière-plan, non** : écran éteint
> 119,8 s après le passage en arrière-plan (mise en veille de 2 min), sans `debug.awake on=false` (n° 3). Le système du
> téléphone ignore le verrou d'écran d'une appli en arrière-plan (l'émulateur, Android 37, le tient). Pistes : fenêtre
> invisible par-dessus les autres applis avec `FLAG_KEEP_SCREEN_ON` (permission « Afficher par-dessus les autres
> applis », une fois) ; journaliser `isHeld` et `PowerManager.isInteractive` à chaque annonce.

**Besoin.** Pendant T0 et T2, l'écran du téléphone s'est éteint tout seul ; l'utilisateur a demandé « un outil qui le
garde éveillé si besoin », commandé depuis le PC, même appli en arrière-plan (T2).

**Proposition.**
- Commande PC : `bash tools/livetest/cmd.sh awake <minutes>` (0 = rendre la main) → action `fr.toolmap.debug.AWAKE` de
  `DebugCommands` sur le collecteur.
- Transport : un champ de plus dans la charge `DiagPayload.Acks` (`DiagCodec`, `{"k":"col",…}`), par exemple
  `"w": <heure serveur de fin>`, que le collecteur envoie déjà toutes les 5 s : pas de nouveau type de message, et la
  commande se répète d'elle-même.
- Téléphone (`DebugKit`, à la réception des accusés) : verrou de réveil `SCREEN_BRIGHT_WAKE_LOCK |
  ACQUIRE_CAUSES_WAKEUP` (déprécié mais toujours actif, acceptable dans le seul build de debug) tenu jusqu'à l'heure de
  fin ; il marche appli en arrière-plan, contrairement à `FLAG_KEEP_SCREEN_ON` qui demande une fenêtre visible.
  Événements `debug.awake on=true untilS=…` et `debug.awake on=false`.
- Visible : l'écran reste allumé, et une notification de debug « Écran gardé allumé jusqu'à HH:MM » est souhaitable ;
  **aperçu à valider avant de coder**.
- En attendant : sur le téléphone, durée de mise en veille de l'écran à 10 min, ou Options pour les développeurs ›
  « Rester activé » (seulement en charge).

**Vérification.** `cmd.sh awake 5` : l'écran reste allumé 5 min, appli en arrière-plan ; `cmd.sh awake 0` le rend.

## Petits points relevés

- Flux muet détecté seulement après 45 s (émulateur, 10:28 téléphone) : le flux SSE ne reçoit plus rien à partir de
  ≈ 10:27:56 ; une écriture `c/DIAG` échoue à 10:28:08 (`SocketTimeoutException` après 10 s), mais le flux n'est
  coupé qu'à l'expiration de la lecture (`relay.stream.end upS=1608 why=timeout` à 10:28:41, `STREAM_READ_TIMEOUT_S`
  = 45 s), et la liaison avec le téléphone est perdue au même moment (`mesh.link.lost silentS=45`) ; reprise 1,2 s plus
  tard. Piste : quand une écriture expire alors que le flux est ouvert, vérifier le flux tout de suite (rouvrir si rien
  n'est arrivé depuis plus de 10 s) au lieu d'attendre les 45 s. Ping de l'émulateur à ce moment-là : ≈ 320 ms contre
  47 ms pour le PC (le réseau de l'émulateur passe par celui de Windows) : cause probablement côté émulateur.
- Mode test : à chaque réouverture du flux, l'état complet du salon arrive d'un bloc (79 à 110 Kio), surtout des
  trames de journal (`DIAG`) gardées jusqu'à 120 s dans `c`. Debug seulement ; on peut raccourcir la durée de vie des
  trames `DIAG`.
- `live.mjs` repart de la dernière ligne du logcat (`-T 1`) : les lignes reçues pendant qu'il était arrêté manquent
  à son journal brut (pas dans `remote.log`). Possible : repartir de l'heure de la dernière ligne vue.
- **Saut de l'horloge d'un appareil** (vu sur l'émulateur, 2026-10-24, après la mise en veille du PC) : l'horloge de
  l'émulateur est restée 13,6 min en retard. L'écart au serveur (`relay.clock`) n'est mesuré qu'à la connexion : ses
  trames ont paru vieilles au téléphone, qui a jeté les accusés du collecteur (plus de `CONTROL_MAX_AGE_MS` = 150 s) ;
  l'envoi du journal s'est bloqué (le téléphone renvoyait sans fin, le collecteur refusait, `debug.payload.refused`).
  Remis par `adb shell cmd alarm set-time <ms>` (ni `auto_time` ni `network_time_update_service force_refresh` n'ont
  suffi). Un téléphone dont l'heure change (réglage manuel, fuseau, correction réseau) ferait de même. Piste : mesurer
  de nouveau l'écart au serveur à chaque réouverture du flux et quand l'horloge saute (`Intent.ACTION_TIME_CHANGED`),
  et juger l'âge d'une trame avec l'heure du serveur.
  **Cause du saut sur l'émulateur** (session 53, suite) : c'est son passage au réseau mobile (`svc wifi disable`) qui
  recule son horloge, de 816 s exactement (heure du réseau mobile émulé), et elle n'est pas rattrapée au retour du
  Wi-Fi. Après un essai de (f), remettre l'heure : `adb shell cmd alarm set-time <ms>`.
  **Devenu le défaut n** au second test (recul pendant la mesure de l'écart, envois et `PING` arrêtés).
- Le temps relatif de la liste des membres (« Hors ligne · il y a … ») suit la langue du système
  (`DateUtils.getRelativeTimeSpanString`, `ShareComponents.kt` l. 156) : « 11 seconds ago » sur l'émulateur en anglais,
  dans une interface en français. Changement visible : aperçu d'abord.
- `analyze.mjs` : quand l'horloge d'un appareil recule (n), ses lignes se rangent avant les précédentes et le rapport
  voit un faux trou de réception (« 15:41:07 → 15:43:58 » au second test, alors que 31 positions sont arrivées pendant
  ce temps) ; signaler les reculs (`sys.time_set`) ou ranger par numéro de ligne.
- Le nœud d'un membre qui a effacé ses données (n° 20) ou quitté sans le dire reste sur le serveur : les autres ne
  peuvent pas l'effacer (règles) ; voir r.

## Second test en direct (après P21.5)

Ordre détaillé, attendus et suivi pendant le test : `docs/plan-partage.md` § 3 (les problèmes y sont notés au moment
où ils arrivent, § 5).

À refaire en priorité : T12 (d), T4 et une mise à jour de l'APK (a), T15 (b et le message t), T18 (e), Wi-Fi qui
revient pendant le mobile sur l'émulateur (f), une heure entière (g), groupe sans partage en arrière-plan (h). Puis ce
qui n'a pas été fait le 2026-10-24 : T0 en marchant, T2 avec P21.6, T5 à T8, T10, T11, T13, T16, T17. Au début :
vérifier la notification unique de P21.7 (icône des sommets dans la barre d'état du téléphone, ligne « Position
partagée », bouton « Arrêter le partage » ; question « Montrer ToolMap dans la barre d'état ? » si les notifications
sont encore refusées).

**Bilan du second test** (2026-10-24, 14:56 → 16:42, 0.4.66 ; détail : `docs/plan-partage.md` § 3 et § 5) :

- **Conformes sur le téléphone** : P21.7 (notification unique), d (intervalle changé, service gardé : 55 positions
  sur 55 en 5 min, écran éteint), a (reprise 0,22 s après un arrêt forcé et après une réinstallation), b (position
  du téléphone reçue 0,05 s après la relance de l'émulateur), e (position retenue écrite 0,1 s après la réouverture),
  f (Wi-Fi → mobile : flux rouvert 0,8 s après, et 0,4 s au retour du Wi-Fi), cache vidé, mode avion 3 min (traînée
  de 18 positions), coupure du réseau, arrêt et relance du partage (21 ms, 1er fix 4,9 s), message texte (0,3 s).
- **Partiellement** : g (deux renouvellements sans coupure, mais pas pendant une heure de partage du téléphone) ;
  P21.6 (écran gardé allumé au premier plan, pas en arrière-plan).
- **Non observable** : h (Android n'a pas bloqué le réseau).
- **Confirmés** : c (localisation coupée, puis économiseur), t (code).
- **Nouveaux** : l, m, n, o, p, q, r ci-dessus. Le plus grave pour l'utilisateur : o (plus aucune position écran
  éteint avec l'économiseur de batterie, sans que personne le sache).
- **Chiffres** (`analyse.txt`) : 426 positions émises par le téléphone, 403 reçues par l'émulateur (les 23 manquantes :
  collisions de numéros de m) ; médiane de 10 s entre deux positions pour 10 s demandées ; écriture d'une position sur
  le serveur 55 ms (médiane) ; batterie du téléphone 55 % → 46 % en 105 min (mode test compris : journal envoyé en
  continu), écran éteint 49 % du temps.
- **Retirés à la demande de l'utilisateur** : T0 en marchant (l'écran qui tient immobile tient en mouvement), n° 18 (une
  heure, trop long), n° 19 (redémarrage du téléphone : l'utilisateur revoit lui-même ses services).
