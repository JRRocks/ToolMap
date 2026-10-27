# Test en direct du partage de position (P21)

> Outils : `docs/specs/event-log.md` (journal, `:debugkit`), scripts `tools/livetest/`. Décision : ADR-070.
>
> **P21 clos le 2026-10-24 (ADR-072)** : `:debugkit` n'est plus dans le build, l'APK de debug ne répond plus aux
> scripts. Pour un nouveau test, rebrancher le module d'abord (ADR-072, point 3).

## 1. Principe et rôles

- **Claude** pilote depuis le PC : l'émulateur « Medium_Phone » est membre du groupe **et collecteur** ; il reçoit en
  continu le journal complet du téléphone (chiffré, par le serveur du groupe) et le sien. Claude suit le tout en
  direct (`live.mjs` sous l'outil Monitor) et dit à l'utilisateur quoi faire par une **notification de l'application
  Claude** sur son téléphone.
- **L'utilisateur** a le téléphone avec l'APK de **debug** (le mode test s'y allume tout seul en entendant le
  collecteur), part marcher et fait les manipulations demandées, une à la fois. Il répond « fait » (ou décrit ce
  qu'il voit) dans l'application Claude ; à défaut, Claude voit l'effet dans le journal.
- Chaque étape est marquée dans le journal de l'émulateur (`cmd.sh mark "T5 GPS coupé"`) : le rapport range les
  événements des deux appareils sur la même ligne de temps.

## 2. Préparation (à la maison, ≈ 10 min)

**PC** (Claude) :

1. APK de debug compilé avec le serveur de l'utilisateur, passé **seulement par l'environnement**, jamais écrit dans le
   dépôt : `TOOLMAP_FIREBASE_DB_URL=… TOOLMAP_FIREBASE_API_KEY=… ./gradlew assembleDebug`.
2. Émulateur démarré, puis `bash tools/livetest/setup.sh app/build/outputs/apk/debug/ToolMap-debug-<version>.apk`
   (installation, permissions, émulateur posé à Chambéry, rôle collecteur). Si plusieurs appareils sont branchés, les
   scripts visent celui de `ANDROID_SERIAL` (ex. `export ANDROID_SERIAL=emulator-5554`).
3. Suivi : `node tools/livetest/live.mjs` (dans le Monitor de Claude Code ; journal brut dans `tools/livetest/out/`).

**Téléphone** (utilisateur) :

1. Installer le même APK de debug (lien donné en fin de session ; il s'installe par-dessus, même clé de debug).
2. Application Claude installée, notifications autorisées, **Remote Control connecté** à la session du PC (sans lui,
   les notifications n'arrivent pas).
3. Dans ToolMap : Partage de position → créer un **groupe de test sur le serveur** (pas le groupe habituel) → Inviter →
   « Partager le lien » → coller le lien `toolmap://join?d=…` dans la conversation Claude. Claude l'ouvre sur
   l'émulateur (`bash tools/livetest/join.sh "<lien>"`) et accepte.
4. Activer le partage sur les deux appareils. Noter l'intervalle d'envoi réglé (celui de tous les jours ; T12 essaie
   un intervalle court).
5. Vérification avant de sortir : `live.mjs` affiche `# log epoch=…` pour le téléphone (son journal arrive) et des
   positions reçues ; `cmd.sh status` montre `in>0`.

Après le test : quitter le groupe de test sur les deux appareils (le lien contient la clé du groupe).

## 3. Ce que Claude surveille en direct

- Une alerte par position qui n'arrive plus depuis 60 s (`GAP_S`), et à sa reprise ; par journal du téléphone qui
  n'arrive plus depuis 90 s.
- Les événements notables des deux appareils : démarrages et arrêts du processus, service, état des membres
  (`share.peer`), GPS, réseau, flux du serveur, écritures en échec, `relay.p.replaced`, liaisons, marques.
- Un résumé toutes les 2 min : âge de la dernière position, positions par minute, précision, batterie, écran, doze,
  satellites, flux du serveur, retard du journal.

## 4. Scénarios

Durée totale ≈ 1 h 30. Chaque étape : notification de Claude → manipulation → attente indiquée → notification
suivante. « Attendu » est ce que l'application doit faire ; tout écart est noté pour correction.

| ID | Manipulation (téléphone) | Attente | Attendu | Vérifié dans le journal |
|---|---|---|---|---|
| T0 | Marcher, appli au premier plan, écran allumé | 5 min | une position par intervalle, aucun trou | `mesh.pos.out` / `mesh.pos.in` (même `fid`), délai bout à bout, `relay.write` du canal `p` |
| T1 | Écran éteint, téléphone en poche | 15 min | idem, sans trou en doze | `sys.power` (doze, seau d'usage), `snap.sys` (écran, doze), `gnss.status` |
| T2 | Touche Accueil (appli en arrière-plan), écran allumé | 3 min | partage continu | `app.background`, `share.service.*` |
| T3 | Fermer l'appli depuis les applis récentes (balayer) | 3 min, puis relancer | le service continue ; au retour, rien à refaire | `app.exit`, `share.service.*`, `app.start` |
| T4 | Paramètres › Applis › ToolMap › **Forcer l'arrêt** | 2 min, puis relancer | partage arrêté (normal) ; à la relance, reprise **sans** rien refaire ? (répétition du 2026-10-23 : non, voir §7) | `app.exit` (`USER_REQUESTED`), `share.load`, `share.sharing` |
| T5 | Paramètres › Applis › ToolMap › Stockage › **Vider le cache** | 1 min | rien de perdu (groupe, réglages) | `share.load`, reprise des positions |
| T6 | **GPS coupé** (volet rapide « Localisation ») | 2 min, puis rallumer | problème signalé, reprise dans un intervalle après rallumage | `gps.provider`, `share.gps.error`, `share.problem`, `gnss.*` |
| T7 | **Mode avion** | 2 min, puis couper | positions gardées hors ligne puis envoyées (traînée) ; reconnexion en quelques secondes | `net.lost`, `relay.stream.end`, `relay.retry`, `mesh.pos.held`, `relay.stream.open` |
| T8 | **Données mobiles coupées** (Wi-Fi coupé aussi) | 2 min, puis remettre | comme T7, sans mode avion | `net.*`, `relay.reconnect.now` |
| T9 | Passer du **Wi-Fi aux données mobiles** (quitter la maison, ou couper le Wi-Fi) | 1 min | reconnexion immédiate sur le nouveau réseau | `net.available`, `relay.reconnect.now`, `relay.stream.open` |
| T10 | **Économiseur de batterie** activé | 5 min, puis désactiver | partage continu (au pire plus espacé) | `sys.power` (`saver`), cadence |
| T11 | Couper puis relancer le partage dans l'appli | 1 min | arrêt net puis reprise | `share.sharing` |
| T12 | Intervalle d'envoi le plus court | 5 min, puis remettre | cadence suivie, coût mesuré | `share.settings`, `snap.sys` (courant, CPU, données) |
| T13 | Envoyer un message texte au groupe | — | reçu par l'émulateur | `share.text.out` / `mesh.text.in` |
| T14 | (côté PC) Claude coupe le réseau de l'émulateur 2 min, puis le relance | 2 min | le téléphone voit le membre perdu puis retrouvé ; l'émulateur rattrape le journal sans trou | `share.peer`, `relay.peer.*`, marques `# gap` absentes |
| T15 | (côté PC) Claude redémarre l'appli de l'émulateur | 1 min | le téléphone reprend l'envoi du journal là où il en était | `debug.start`, `# log` absent (même époque) |
| T16 | **Redémarrer le téléphone** | 3 min | noter si le partage reprend seul après le démarrage | `app.start`, `app.start.reason`, `share.sharing` |
| T17 | *Facultatif, en dernier* : Paramètres › Applis › ToolMap › Stockage › **Effacer les données** | — | groupe et réglages perdus (normal) : à refaire via un nouveau lien | `# log epoch=… previous=…` |
| T18 | (côté PC) Claude coupe le réseau de l'émulateur 30 s | 1 min | aucun membre perdu (< 45 s) ; reconnexion dès le retour du réseau ; trame `p` retenue écrite aussitôt | `relay.state`, `relay.stream.open`, `relay.write what=p` |
| T19 | (côté PC) Claude coupe le réseau de l'émulateur 9 min | 10 min | membre perdu puis retrouvé ; journal de 9 min rattrapé sans trou | `share.peer`, `# gap` absent, numéros continus dans `remote.log` |

T2 : vérifier dans le journal que l'appli est bien passée en arrière-plan (`app.background` sans `sys.screen_off`).

**Écran du téléphone gardé allumé** (P21.6, T0 et T2) : `bash tools/livetest/cmd.sh awake 10` sur le collecteur ; le
téléphone allume son écran et le garde allumé 10 min, appli en arrière-plan aussi (`debug.awake on=true`), puis le
laisse s'éteindre (`on=false why=time`). `cmd.sh awake 0` rend la main avant. Éteint par l'utilisateur (bouton
marche), l'écran reste éteint. Téléphone sur USB : `ANDROID_SERIAL=<série> bash tools/livetest/cmd.sh awake 10`
garde son propre écran allumé.

Pendant la marche, les situations réelles comptent aussi (tunnel, bâtiment, zone sans réseau) : l'utilisateur les
signale dans la conversation si possible.

## 5. Après le test

1. `bash tools/livetest/pull.sh` → copie des journaux de l'émulateur dans `tools/livetest/out/<date>/` (non versionné :
   il contient des positions).
2. `node tools/livetest/analyze.mjs tools/livetest/out/<date>/ [--from HH:MM --to HH:MM]` → rapport : chronologie,
   cadence et trous de chaque côté avec ce qui s'est passé pendant le trou, délai bout à bout et trames perdues,
   écritures au serveur et coupures de flux, GPS, batterie (mAh/h, courant, CPU, données).
3. Résultats et défauts trouvés consignés au journal de bord ; corrections dans des tâches P21 suivantes.

## 6. Limites

- L'émulateur reste fixe (ou déplacé par `adb emu geo fix`) : seul le téléphone teste le GPS réel.
- Le mode test lui-même consomme (≈ 1 trame par seconde, instantanés, GNSS suivi) : les chiffres de batterie sont
  ceux du build de debug en test, pas ceux d'un usage normal ; T12 et un tronçon de T1 peuvent être refaits plus tard
  sans collecteur, journal relevé à la main (`adb`) pour comparer.
- Les notifications de Claude passent par Remote Control : sans réseau sur le téléphone (T7, T8), elles attendent.

## 7. Répétition sans téléphone (deux émulateurs)

Pour vérifier les outils avant de sortir, un second émulateur sans fenêtre joue le téléphone :

1. AVD `Phone2` : copie de `~/.android/avd/Medium_Phone.avd/config.ini` (sans les disques) dans `Phone2.avd/`, avec
   `AvdId=Phone2`, et un `Phone2.ini` qui pointe dessus ; démarrage :
   `emulator -avd Phone2 -port 5556 -no-window -no-audio -no-boot-anim -no-snapshot` (deux instances du même AVD
   demandent `-read-only` sur les deux).
2. Collecteur : `ANDROID_SERIAL=emulator-5554` pour tous les scripts. Groupe créé sur le collecteur (Positionnement ›
   Partage de position › Créer un groupe et inviter › Envoyer le lien par message : le lien se lit dans l'aperçu de la
   feuille de partage par `uiautomator dump`), ouvert sur `emulator-5556` avec `am start -a VIEW -d "<lien>"`.
3. Déplacements du « téléphone » : `adb -s emulator-5556 emu geo fix <lon> <lat>` ; manipulations par `adb -s
   emulator-5556 shell am force-stop fr.toolmap`, etc.

Répétition du 2026-10-23 (≈ 8 min, APK 0.4.59) :

- Le journal du « téléphone » arrive en continu (≈ 290 lignes/min, ≈ 1 trame/s, ≈ 280 octets par trame) ; mode test
  allumé seul ; arrêt forcé et relance : même époque, numéros continus, mode test gardé ; membre perdu après 45 s puis
  retrouvé ; tout est passé par le serveur (aucune liaison WebRTC).
- Redémarrage de l'appli du collecteur : le téléphone a renvoyé 371 lignes déjà reçues → corrigé (le collecteur garde
  où en est chaque membre, et les scripts écartent les doublons).
- **Défauts de l'application** (pour P21.5) : après un arrêt forcé, le partage **ne reprend pas** à la relance (le
  service n'est relancé que par le système, `START_STICKY`, ce qu'un arrêt forcé empêche ; l'état « actif » est pourtant
  gardé) ; au premier fix, une trame `PING` a pris la place de la position dans `p` 0,2 s après elle
  (`relay.p.replaced old=POS new=PING`) : la position était en cours d'écriture et elle est bien arrivée, mais
  l'événement la dit « jamais écrite », et un membre qui se connecte ensuite lit le `PING` au lieu de la dernière
  position.

## 8. Premier test en direct (2026-10-24)

Résultats et défauts : journal de bord, session 53 ; corrections en P21.5.

- Le canal du journal a tenu : 12 493 lignes du téléphone sans trou ni doublon dans `remote.log`, à travers un
  redémarrage du collecteur (T15) et trois coupures de son réseau (2 min, 30 s, 9 min).
- Le rapport se fait sur les fichiers de `pull.sh` : les journaux bruts de `live.mjs` perdent les lignes reçues pendant
  un redémarrage du suivi (`logcat -T 1`).
- Le téléphone met l'écran en veille tout seul pendant un test : outil pour le garder allumé en P21.6 (`cmd.sh awake`,
  §4, depuis 0.4.66).
- Refaire T12 après la correction du défaut (d) : changer l'intervalle arrêtait le service de premier plan.
