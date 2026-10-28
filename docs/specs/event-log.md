# Journal d'événements et outils de test du build de debug (P21)

> Décision : ADR-070. Code : `core/.../eventlog/` (`EventLog`, `EventStore`, `EventLine`), `app/.../eventlog/`
> (`AppEventLog`, `SystemEvents`), module `:debugkit` (debug seulement). Tests en direct : `docs/specs/live-test.md`,
> scripts `tools/livetest/`.
>
> **Depuis le 2026-10-24 (P21 clos, ADR-072), `:debugkit` est sorti du build** : aucun APK ne l'a, ni le debug ni la
> release. Ce qui suit sur `:debugkit` décrit le module débranché (sources gardées dans `debugkit/`) ; le journal
> d'événements, lui, reste dans tous les builds.

## 1. But

Savoir après coup, et en direct pendant un test, **tout ce que l'application a fait et subi** : actions de
l'utilisateur, écritures au serveur, liaisons, positions envoyées et reçues, GPS, réseau, économie d'énergie, arrêts
du processus. Le journal existe dans **tous** les builds (léger : quelques lignes par minute) ; le build de **debug**
ajoute le module `:debugkit`, qui le rend bavard et l'envoie au PC pendant un test.

## 2. Format et fichiers

- Une ligne par événement : `<n°> <époque ms> <HH:mm:ss.SSS> <nom> clé=valeur clé="valeur avec espaces"`.
  Le numéro croît sans fin (il reprend après un redémarrage) ; les valeurs avec espace, guillemet, `=`, saut de ligne
  ou vides sont entre guillemets, `\"`, `\\` et `\n` échappés ; les décimaux ont au plus 7 décimales.
- Fichiers : `files/eventlog/current.log`, `previous.log` (rotation à 1 Mio, 4 Mio en debug) et `epoch` (8 chiffres
  hexadécimaux tirés au hasard à la création du dossier : un journal nouveau après « Effacer les données » ou une
  réinstallation). Les données de l'application seulement : rien sur la carte SD, rien ne sort du téléphone en
  dehors d'un test (§4).
- Écriture par un fil à part (`toolmap-eventlog`) : journaliser ne bloque jamais l'appelant. Les dernières lignes
  restent aussi en mémoire (256, 4096 en debug). Avant un plantage, `CrashReporter` attend au plus 500 ms que tout
  soit écrit.
- En debug, chaque ligne est aussi copiée dans logcat, étiquette `TMEvent`.

## 3. Événements

Deux niveaux : `event` (toujours gardé) et `detail` (gardé seulement en mode test, `EventLog.verbose` : chaque trame,
chaque ping). Les coordonnées (`lat`, `lon`) ne sont écrites qu'en mode test. L'adresse du serveur et le jeton ne
sont jamais écrits (ils sont dans l'URL des requêtes).

| Famille | Événements | Champs principaux |
|---|---|---|
| Processus | `app.start`, `app.start.reason` (API 35), `app.exit` (raisons d'arrêt du système, API 30+), `app.crash`, `app.foreground` / `app.background`, `app.trim`, `app.lowmem`, `app.perm`, `app.intent` | `reason`, `importance`, `pss`, `level` |
| Interface | `ui.activity`, `ui.screen` ; `notify.ask` (question « Montrer ToolMap dans la barre d'état ? » posée, P21.7), `notify.ask.answer` (Autoriser ou Plus tard), `notify.ask.result` (réponse d'Android, réglages ouverts si refus définitif) | `screen`, `depth`, `allow`, `granted`, `settings` |
| Système | `sys.screen_on` / `sys.screen_off`, `sys.power_connected`…, `sys.airplane`, `sys.battery`, `sys.location`, `sys.power` (économie, doze, optimisation de batterie, mise en veille de l'appli, seau d'usage ; `locMode` : mode de localisation de l'économiseur, Android 9+, P21.5 o) | `on`, `percent`, `why` |
| Réseau | `net.available`, `net.lost`, `net.caps` (type, validé, débit), `net.blocked` | `net`, `type` |
| GPS | `gps.request`, `gps.provider` (activé / désactivé), `gps.refused`, `gps.stop`, `gps.fix` (détail) ; `gnss.*` en mode test (§5) | `acc`, `ageMs`, `mock` |
| Partage | `share.load`, `share.group.*`, `share.invite`, `share.code`, `share.sharing`, `share.fix` (chaque position GPS, envoyée ou non), `share.peer` (changement d'état d'un membre), `share.problem`, `share.service.*`, `share.net.*`, `share.text.*`, `share.sos.*`, `share.rekey`, `share.excluded`, `share.settings`, `share.reconnect`, `share.node.start`, `share.gps.restart` (nouvel intervalle : seule la demande GPS change), `share.resume` (partage interrompu repris au lancement), `share.alerts` (réglages du téléphone qui coupent le partage, à chaque changement : `LOCATION_OFF`, `POWER_SAVER`, `BATTERY_OPTIMIZED` ou `none`, P21.5) | `send`, `acc`, `from`/`to`, `heardAgoS`, `fixAgoS` |
| Maillage | `mesh.link.open` / `closed` / `lost`, `mesh.pos.out`, `mesh.pos.in`, `mesh.pos.held`, `mesh.trail.in`, `mesh.text.in`, `mesh.sos.in` / `out`, `mesh.sos.end.in` / `out` (levée de l'alerte, P22.2 : `lifted`, `stale`, `left`), `mesh.kick.in`, `mesh.bye.in`, `mesh.frame.bad`, `mesh.send.fail` ; détails `mesh.frame.in`, `mesh.ping.out` | `member`, `via`, `seq`, `fid` (identifiant de trame : 4 premiers octets du nonce, le même chez l'émetteur et le récepteur), `fixT`, `ageS` |
| Serveur | `relay.start`, `relay.connect`, `relay.stream.open` / `end`, `relay.stream.renew` (flux rouvert avec le jeton renouvelé), `relay.retry`, `relay.reconnect.now` (`moved` : flux ouvert sur un autre réseau), `relay.network` (nouveau réseau par défaut : nouvelles connexions), `relay.blocked` / `relay.blocked.wait` (réseau bloqué par Android : pas de tentative), `relay.offline` / `relay.offline.wait` (aucun réseau : pas de tentative, 5 min au plus, P21.5 p), `relay.clock` (écart au serveur), `relay.clock.bad` (mesure refaite : aller-retour négatif ou de plus de 10 s), `relay.clock.changed` (heure du téléphone changée : écart remesuré), `relay.c.purge` (au démarrage : trames `c` d'un processus précédent effacées, P21.5 r), `relay.state`, `relay.peer.open` / `close` / `gone`, `relay.write` (canal de position : toujours ; autres : détail), `relay.write.fail`, `relay.p.replaced`, `relay.frame.drop`, `relay.ban`, `relay.close` ; `fb.auth*`, `fb.http` ; détails `relay.sse`, `relay.hb.in`, `relay.frame.in` | `what`, `type`, `fid`, `ms`, `bytes`, `upS`, `why`, `cancel`, `moved`, `blocked`, `proto` (protocole du flux) |
| Direct (WebRTC) | `rtc.ice`, `rtc.link.open`, `rtc.link.close` | `peer`, `state` |
| Outils de test | `test.mark`, `debug.*`, `snap.*`, `gnss.*` | §4, §5 |

`relay.p.replaced` signale une position remplacée avant que son écriture ait commencé : seule la plus récente part dans
`p`. Depuis 0.4.64, `p` ne reçoit que des `POS` (les `PING` passent par `c`, P21.5 b).

**Jauges** : des valeurs lues à la demande (`EventLog.gauge`), écrites par les instantanés du mode test : `relay`
(état, flux, écritures, échecs, octets, file d'attente, trames reçues et rejetées, pairs) et `share` (groupe, partage,
membres et leur état, dernier fix, dernier envoi).

## 4. Envoi du journal au PC pendant un test (`:debugkit`)

- **Collecteur** : un appareil du groupe (l'émulateur sur le PC) déclaré par `tools/livetest/cmd.sh collect on`. Il
  annonce sa présence toutes les 5 s (message `DIAG` de type `col` avec ce qu'il a déjà de chaque membre), écrit ce
  qu'il reçoit dans `files/eventlog/remote.log` (`<membre> <ligne>`, rotation à 32 Mio) et dans logcat (`TMRemote`).
- **Membres** : un téléphone qui entend un collecteur passe en **mode test pour 30 min** (prolongé à chaque annonce,
  gardé si l'application redémarre) et envoie son journal : à la première annonce, les 2000 dernières lignes (ou la
  suite de ce que le collecteur a déjà), puis tout au fil de l'eau.
- **Transport** : message `DIAG` (type 13) chiffré avec la clé du groupe, sur le canal fiable (`c`) comme un texte,
  relayé par le maillage ; charge utile JSON compressée (Deflate) d'au plus 4096 octets, soit une trame de 5,5 kio en
  Base64 (règle Firebase : 6000). Au plus une trame par seconde, et **jamais quand une écriture au serveur attend** :
  les positions passent d'abord.
- **Sans trou ni doublon** : le collecteur n'accepte une trame que si elle suit ce qu'il a ; sinon il la refuse, et
  l'émetteur, qui ne voit plus l'accusé avancer pendant 15 s, renvoie depuis la dernière ligne accusée. Au plus 4000
  lignes en vol. Lignes que le téléphone n'avait plus : marque `# gap from=… to=…` ; nouveau journal : `# log epoch=…`.
  Le collecteur garde où en est chaque membre (préférences `debugkit`, clé `members`, écrite avec `remote.log`) :
  redémarré, il ne redemande rien (essai du 2026-10-23 : sans cela, 371 lignes reçues deux fois). `live.mjs` et
  `analyze.mjs` écartent de toute façon une ligne déjà vue (même membre, même numéro).
- Ce que le collecteur reçoit ne repart pas : il n'envoie que ses annonces.

## 5. Mode test (debug seulement)

Actif sur le collecteur, et sur un téléphone pendant 30 min après la dernière annonce entendue (ou `cmd.sh test 30`) :

- `EventLog.verbose` : les détails sont gardés (chaque trame, ping, écriture, fix GPS, avec coordonnées) ;
- **instantané toutes les 5 s** : `snap.sys` (batterie %, courant µA, charge µAh, branché, température, temps CPU
  du processus, tas Java, tas natif, fils, octets reçus / envoyés par l'appli, écran, doze, économie d'énergie) puis
  `snap.relay`, `snap.share` ;
- **GNSS** : `gnss.start`, `gnss.stop`, `gnss.firstfix` (temps du premier fix), `gnss.status` toutes les 5 s
  (satellites vus, utilisés, C/N0 moyen des utilisés, meilleur, constellations) ;
- `debug.status` toutes les minutes (rôle, émission : envoyé, accusé, retard, trames, octets, renvois ; `awakeAskS`,
  `awake` : écran gardé allumé, P21.6).

**Écran gardé allumé** (P21.6) : le collecteur ajoute à ses annonces le nombre de secondes pendant lesquelles les
téléphones gardent leur écran allumé (champ `w` de la charge `col`, absent : non ; une version plus ancienne
l'ignore). Le téléphone tient un verrou d'écran (`SCREEN_BRIGHT_WAKE_LOCK`, déprécié mais respecté, appli en
arrière-plan aussi) jusqu'à la fin donnée, compté sur le temps depuis le démarrage (un saut d'horloge ne change rien) ;
il allume l'écran au début de la demande seulement : éteint au bouton marche, l'écran reste éteint. Événements
`debug.awake.set` (commande reçue), `debug.awake on=true leftS= by=collector|here`, `debug.awake on=false why=asked|time`.
Un téléphone qui n'entend plus le collecteur garde l'écran allumé jusqu'à la fin donnée, au plus 240 min.

Coût assumé (demande de l'utilisateur) : quelques dizaines de lignes par seconde, ≈ 1 trame de 5,5 kio par seconde
vers le serveur, réveil toutes les secondes ; le mode s'arrête seul 30 min après le départ du collecteur.

## 6. Commandes (`adb`, debug seulement)

Récepteur `fr.toolmap.debugkit.DebugCommands`, protégé par la permission `DUMP` (seul le shell `adb` l'a) ; la
réponse (`data=`) est la ligne d'état. Actions `fr.toolmap.debug.` + `COLLECT` (`--ez on`), `TEST` (`--ei minutes`,
0 : arrêt), `AWAKE` (`--ei minutes`, 0 : arrêt ; sur le collecteur : l'écran des téléphones, §5 ; `--ez here true` ou
sur un autre appareil : son propre écran), `MARK` (`--es text` : ligne `test.mark`), `SNAPSHOT`, `STATUS`. Raccourci :
`tools/livetest/cmd.sh` (`cmd.sh awake 10`, `cmd.sh awake 10 here`).

## 7. Absent d'un build de release

`:debugkit` n'entre que par `debugImplementation` : un APK release ou benchmark n'a ni le collecteur, ni le mode test,
ni le récepteur de commandes, ni le message `DIAG` émis (il sait seulement le relayer et l'ignorer). Il garde le
journal léger sur le téléphone (§2, §3 sans les détails).
