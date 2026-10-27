# Plan de travail : partage de position (P21)

> **Clos le 2026-10-24** (session 56, décision de l'utilisateur) : P21 terminé, `:debugkit` sorti du build (ADR-072).
> Ce plan n'est plus à lire en début de session ; il garde l'histoire des deux tests en direct. Les restes sont dans
> `docs/idees-futur.md` (Partage de position).
>
> Document de reprise. À lire en début de session avant tout travail sur le partage de position, avec les 3 dernières
> entrées de `docs/JOURNAL.md`. À tenir à jour pendant le travail (statuts du § 3, problèmes du § 5).
> État au 2026-10-24 (fin de la session 54) : version **0.4.69** (second test fait, défauts l à r corrigés, alertes
> o et c), branche `sauvegarde/p16-2026-10-16` de `JRRocks/ToolMap` (aujourd'hui `JRRocks/ToolMap-Archive`, privé). APK :
> `G:\Mon Drive\2 - Jean\Claude Code\APK release\ToolMap.0.4.69.apk` (CI bloquée par la facturation GitHub : run du commit `fac3dfe` non démarré).
>
> Références : protocole et scénarios `docs/specs/live-test.md` ; défauts, causes et corrections
> `docs/specs/p21-corrections.md` ; journal d'événements et commandes `docs/specs/event-log.md` ; serveur
> `docs/specs/firebase-relay.md`.

## Règles de travail (demande de l'utilisateur, 2026-10-24)

- **Debug : pas d'aperçu, pas de question.** Tout ce qui ne sert qu'au debug (module `:debugkit`, scripts
  `tools/livetest/`, mode test du journal, APK de debug) se décide et se fait sans rendu visuel et sans demander à
  l'utilisateur. Pas d'affichage de debug pour ce que l'utilisateur voit déjà (exemple : l'écran gardé allumé n'a pas
  de notification).
- **Un plan, et les problèmes notés sur le moment.** Pendant un test, suivre l'ordre du § 3 ; noter chaque problème
  **dès qu'il arrive** au § 5 (heure, scénario, symptôme, preuve). Après le test, le reporter dans
  `docs/specs/p21-corrections.md`.
- L'interface de l'appli elle-même (ce que voit tout utilisateur, en release aussi) garde la règle des aperçus de
  `CLAUDE.md` : (c), (t), langue du temps relatif.
- **Secrets** : adresse du serveur et clé API seulement dans les variables `TOOLMAP_FIREBASE_DB_URL` et
  `TOOLMAP_FIREBASE_API_KEY` de la commande gradle (l'utilisateur les redonne). Le lien d'invitation
  `toolmap://join?d=…`, les coordonnées et `tools/livetest/out/` ne vont jamais dans le dépôt, la doc, un commit ou un
  message. Avant un commit : chercher `AIza|firebasedatabase\.app|toolmap://join|[0-9]{2}\.[0-9]{4,}` dans les lignes
  ajoutées.
- Pendant le test, l'utilisateur est prévenu par une notification de l'app Claude (Remote Control connecté) à chaque
  manipulation à faire.

## 1. État

| Id | Sujet | État |
|---|---|---|
| P21.1 à P21.3 | Journal d'événements, module `:debugkit`, scripts `tools/livetest/` | ✅ |
| P21.4 | Test en direct | ✅ clos le 2026-10-24 ; 1re séance le 2026-10-24 : T1, T3, T9, T12, T14, T15, T18, T19 faits ; 2e séance le 2026-10-24 (14:56 → 16:42, 0.4.66) : § 3, n° 18 annulé, n° 19 retiré, bilan dans `p21-corrections.md` § Second test |
| d | Changer l'intervalle arrêtait le service de premier plan | ✅ 0.4.62, émulateur ; téléphone OK au second test (§ 3 n° 4) |
| a | Pas de reprise après un arrêt forcé ou une mise à jour | ✅ 0.4.62, émulateur ; téléphone OK après un arrêt forcé et une réinstallation (§ 3 n° 5, 6) |
| b | Un `PING` remplaçait la dernière position dans `p` | ✅ 0.4.64, émulateur ; à revoir sur le téléphone |
| e | Position retenue écrite au battement suivant | ✅ 0.4.64, tests unitaires seulement |
| f | Flux resté sur l'ancien réseau | ✅ 0.4.65 (pool de connexions du relais), émulateur |
| g | Flux coupé toutes les heures (jeton) | ✅ 0.4.64, tests unitaires seulement (une heure de test) |
| h | Tentatives toutes les 8 s quand Android bloque le réseau | ✅ 0.4.64, émulateur (blocage forcé) |
| P21.6 | Écran du téléphone gardé allumé (`cmd.sh awake`) | ✅ code 0.4.66, émulateur ; téléphone OK au premier plan (§ 3 n° 2, allumage à distance aussi), **pas en arrière-plan** (§ 3 n° 3, à corriger) ; pas de notification |
| P21.7 | Notification « ToolMap est actif en arrière-plan » | ✅ clos le 2026-10-24 ; émulateur OK ; téléphone OK au second test (§ 3 n° 1 : icône, ligne, bouton) ; reste la clôture |
| c | Aucun avertissement quand le GPS ne donne plus de fix | clos le 2026-10-24, en partie fait : 0.4.69, localisation coupée signalée (alertes de o, émulateur) ; « Pas de position GPS depuis N min » attend l'aperçu complémentaire |
| t | « … est connecté(e) » à chaque reconnexion | clos le 2026-10-24 sans être fait (`docs/idees-futur.md`) |
| j | 7 Mio téléchargés en 2 s au retour au premier plan | clos le 2026-10-24 sans être analysé (`docs/idees-futur.md`) |
| k | Repli sur la localisation réseau quand le GPS cale | clos le 2026-10-24 sans décision (contredit ADR-019, `docs/idees-futur.md`) |
| l | Position de départ seulement en traînée, traînée sans effet sur la position affichée | ✅ 0.4.69, tests unitaires ; à revoir sur le téléphone |
| m | Numéros de message en collision après un redémarrage | ✅ 0.4.69, tests unitaires ; à revoir en mode test (arrêt forcé) |
| n | Recul de l'horloge : écart faussé, plus de position ni de `PING` | ✅ 0.4.69, tests unitaires ; à revoir sur l'émulateur (heure reculée) |
| o | Économiseur de batterie : GPS coupé écran éteint, sans avertissement | ✅ 0.4.69 (`locMode`, alertes variante B validée le 2026-10-24), émulateur ; reste : le dire aux autres membres, téléphone |
| p | Tentatives toutes les 8 s sans réseau | ✅ 0.4.69, tests unitaires ; à revoir en mode avion |
| q | Battement espacé écran éteint sans partage | ✅ 0.4.69, tests unitaires ; à revoir (n° 17 du § 3) |
| r | Vieilles trames jamais effacées du serveur | ✅ 0.4.69, tests unitaires ; à revoir (`relay.c.purge`) |

## 2. Préparation du second test

**PC (Claude)** :

1. `./gradlew check assembleDebug` avec les deux variables du serveur passées dans la commande ; noter la version.
2. Émulateur `Medium_Phone` démarré en arrière-plan ; heure automatique coupée (`adb shell settings put global
   auto_time 0` : sinon le retour du réseau mobile la fait reculer, § 5 15:41) ; heure comparée à celle du PC
   (`adb shell date`), remise au besoin par `adb shell cmd alarm set-time <ms>`, **appli arrêtée**
   (`am force-stop fr.toolmap`, puis relancée) : sinon elle garde l'ancien écart au serveur et jette les trames du
   téléphone (§ 5, 14:44).
3. `bash tools/livetest/setup.sh app/build/outputs/apk/debug/ToolMap-debug-<version>.apk` (installation,
   permissions, rôle collecteur).
4. Suivi en direct : `node tools/livetest/live.mjs` dans le Monitor.

**Téléphone (utilisateur)** :

1. Installer l'APK de la même version (Drive `APK release/ToolMap.<version>.apk`), par-dessus l'ancienne.
2. App Claude : notifications autorisées, Remote Control connecté à la session du PC.
3. ToolMap : Partage de position → groupe de test sur le serveur → Inviter → coller le lien dans la conversation ;
   Claude le passe à l'émulateur (`bash tools/livetest/join.sh "<lien>"`).
4. Partage activé sur les deux appareils ; à la question « Montrer ToolMap dans la barre d'état ? » : Autoriser.
5. Contrôle : `live.mjs` affiche `# log epoch=…` du téléphone et ses positions ; `cmd.sh status` donne `in>0`.

## 3. Ordre du second test

Scénarios décrits dans `live-test.md` § 4. Statut à mettre à jour pendant le test (⬜ à faire, ✅ conforme,
❌ problème noté au § 5).

| # | Scénario | Vérifie | Attendu (événements) | Statut |
|---|---|---|---|---|
| 1 | Début, volet du téléphone tiré | P21.7 | icône des sommets dans la barre d'état, ligne « Position partagée », bouton « Arrêter le partage » | ✅ 14:56, vu par l'utilisateur |
| 2 | `cmd.sh awake 30`, téléphone posé, écran allumé sans le toucher (7 min) ; T0 en marchant retiré (demande de l'utilisateur, 2026-10-24 : l'écran qui tient immobile tiendra en mouvement) | P21.6, cadence immobile | `debug.awake on=true by=collector` sur le téléphone ; écran allumé au-delà de sa mise en veille ; une position au moins tous les 5 intervalles, même `fid` des deux côtés | ✅ écran allumé sans toucher de 14:58:57 à 15:09 (> 10 min) ; 47 positions envoyées, 47 reçues, une toutes les 9 à 24 s (fixes GPS irréguliers à l'intérieur) |
| 3 | T2 : touche Accueil, écran allumé (3 min) | P21.6, arrière-plan | écran toujours allumé, partage continu | ❌ écran éteint à la mise en veille (2 min, § 5 15:12) ; partage ✅ (20 envoyées, 20 reçues) |
| 4 | T12 : intervalle le plus court (5 min), puis remis | d | `share.gps.restart`, aucun `share.service.finish`, envoi continu en arrière-plan | ✅ 15:23:52 `share.gps.restart intervalS=5`, aucun `share.service.finish` ; 5 min en arrière-plan, écran éteint : 55 envoyées, 55 reçues, écarts 4 à 5 s (9 s quand un fix manque, 21 s au départ à l'intérieur) ; 150 mA en moyenne |
| 5 | T4 : Forcer l'arrêt, puis relancer | a | `share.resume` ≈ 1 s après le lancement | ✅ (a) : `share.resume` 0,23 s après `app.start` (15:30:59) ; ❌ émulateur sourd 204 s (numéros de message, § 5 15:31) |
| 6 | APK réinstallé par-dessus | a | `share.resume` au premier lancement | ✅ (a) : `share.resume` 0,22 s après `app.start` (15:36:19) ; ❌ 28 s sans position sur l'émulateur (même collision, § 5 15:36) |
| 7 | T15 : Claude redémarre l'appli de l'émulateur | b, t | position du téléphone affichée aussitôt (pas « sans position ») ; noter le toast « … est connecté(e) » | ✅ (b) : lien ouvert 3,4 s après le lancement, position du téléphone reçue 0,05 s après (`ageS=2`) ; le téléphone reçoit celle de l'émulateur 2 s après (pas de collision : l'émulateur n'avait pas d'avance) ; (t) message non relevé (§ 5 15:37) |
| 8 | T18 : réseau de l'émulateur coupé 30 s | e | aucun membre perdu ; trame `p` retenue écrite à la réouverture du flux | 1er essai (15:41) : ✅ reconnexion 0,5 s après le retour du réseau ; ❌ téléphone perdu côté téléphone (45 s) et plus d'envoi de position, à cause d'un recul de l'horloge de l'émulateur (§ 5 15:41) ; (e) pas éprouvé (aucune position retenue pendant la coupure). 2e essai (15:44:48 à 15:45:29, émulateur déplacé pendant la coupure) : ✅ (e) position prise pendant la coupure gardée (`posPending=true`), `relay.write what=p` 0,1 s après la réouverture du flux (0,2 s après le retour du réseau), reçue par le téléphone (`ageS=30`) ; téléphone jamais perdu (`links=1` tout du long) ; heure de l'émulateur stable (heure automatique coupée) |
| 9 | T9 : Wi-Fi → données mobiles (quitter la maison) | f | `relay.network pool=new`, `relay.reconnect.now moved=true`, pas de chute 30 s plus tard | ✅ (f), Wi-Fi coupé à la main (pas de marche, même bascule) : à 15:46:44 réseau mobile, `relay.network pool=new`, `moved=true`, flux rouvert 0,8 s après la perte du Wi-Fi ; retour du Wi-Fi à 15:49:16, flux rouvert en 0,4 s ; aucune chute ensuite, émulateur jamais perdu, positions toutes reçues (16 envoyées) |
| 10 | T5 : Vider le cache | — | groupe et réglages gardés | ✅ cache vidé entre 15:54:37 et 15:54:56 (confirmé par l'utilisateur) : processus pas relancé (aucun `app.start`), `group=true`, `intervalS=10` gardé, partage et lien direct continus |
| 11 | T6 : GPS coupé 2 min, puis rallumé | c (constat) | `gps.provider`, `share.problem` ; reprise dans un intervalle | (c) constat ✅ : coupure vue en 0,06 s (`gps.provider enabled=false` 15:55:52), mais aucun `share.problem` et `gpsUnavailable=false` tout du long (défaut c confirmé) ; reprise au 1er fix, 13,6 s après le retour (`gnss.firstfix ttffMs=13530`, plus d'un intervalle à cause du GPS, pas de l'appli) ; coupure de 18 s d'après le journal (§ 5 15:56) |
| 12 | T7 : mode avion 2 min | — | positions gardées puis envoyées (traînée), reconnexion en quelques secondes | ✅ mode avion de 15:58:38 à 16:01:45 (3 min 7 s) : flux rouvert 2,0 s après la fin (0,4 s après le retour du réseau), trame `p` retenue écrite 1,8 s après, traînée de 18 positions reçue par l'émulateur à 16:01:49, lien direct rouvert des deux côtés ; défauts mineurs § 5 16:01 |
| 13 | T8 : données mobiles et Wi-Fi coupés 2 min | — | comme T7 | ✅ (1er essai à 16:03 sans coupure dans le journal) ; 2e essai : sans réseau de 16:05:05.7 à 16:05:16.5 (11 s d'après le journal, l'utilisateur dit 2 min), flux rouvert 0,5 s après le retour du réseau mobile puis 0,4 s après celui du Wi-Fi ; position prise pendant la coupure écrite à la réouverture et reçue (`ageS=4`) ; émulateur jamais perdu ; la coupure longue est couverte par le n° 12 |
| 14 | T10 : économiseur de batterie 5 min | — | partage continu (au pire plus espacé) | ❌ économiseur de 16:07:45 à 16:20:04 : GPS coupé par le système à chaque extinction de l'écran (`gnss.stop`), repris à chaque allumage ; aucune position écran éteint (3 min 26, puis 8 min 30), sans avertissement ; réseau, journal et lien direct continus (§ 5 16:08) ; économiseur coupé : écran éteint à 16:20:07 sans `gnss.stop`, fix reçu écran éteint à 16:20:19 |
| 15 | T11 : couper puis relancer le partage | — | arrêt net, puis reprise | ✅ arrêt à 16:24:23.971 (action `STOP` de la notification) : GPS et service arrêtés en 21 ms, plus aucun envoi ; relance à 16:24:48.981 : 1er fix 4,9 s après, envoyé aussitôt et reçu par l'émulateur 0,36 s après, puis toutes les 9 à 11 s |
| 16 | T13 : message texte au groupe | — | `mesh.text.in` sur l'émulateur | ✅ envoyé à 16:26:20.670 (`share.text.out chars=4`), reçu par l'émulateur 0,3 s après (`mesh.text.in`, `share.text.in`, par le serveur) |
| 17 | Partage coupé, groupe gardé, appli en arrière-plan 5 min | h | `share.net.blocked`, `relay.blocked.wait`, plus de tentative toutes les 8 s | (h) non observable : partage coupé à 16:29:24, arrière-plan de 16:29:28 à 16:34:38 (écran éteint 16:31:28 → 16:34:36) ; Android n'a **pas** bloqué le réseau (aucun `net.blocked`), flux resté ouvert, aucune tentative en boucle ; nouveau défaut : battement espacé écran éteint (§ 5 16:32) ; jeton renouvelé en passant (§ 5 16:34) |
| 18 | Partage continu pendant au moins une heure (en marchant, T1 compris) | g | `relay.stream.renew` à l'heure du jeton, sans passer par « injoignable » | annulé à 16:39 (délai trop long, demande de l'utilisateur) ; (g) vu une fois sans partage : jeton renouvelé et flux rouvert en 0,18 s sans changement d'état (§ 5 16:34) ; reste à voir pendant un partage |
| 19 | T16 : redémarrer le téléphone | — | noter si le partage reprend seul | retiré (demande de l'utilisateur, 2026-10-24 : sans intérêt réel, qui redémarre son téléphone relance lui-même ses services) |
| 20 | T17 (facultatif, en dernier) : Effacer les données | — | groupe perdu (normal) ; `# log epoch=` nouveau | ✅ données effacées vers 16:40:45 (dernière ligne reçue du téléphone : battement à 16:40:44.882, juste après `app.background` ; les lignes d'après sont perdues avec le processus) ; l'émulateur perd le téléphone 45 s après (`mesh.link.lost silentS=45`, `share.peer DIRECT → LOST` à 16:41:41) ; groupe perdu sur le téléphone (appli rouverte par l'utilisateur) ; pas de nouveau `# log epoch=` : sans groupe, le journal du téléphone n'arrive plus (normal) |

Fin : `cmd.sh awake 0` ; quitter le groupe de test sur les deux appareils (le lien contient la clé du groupe).

## 4. Après le test

1. `bash tools/livetest/pull.sh`, puis `node tools/livetest/analyze.mjs tools/livetest/out/<dossier> --from HH:MM`.
2. Chaque problème du § 5 passe dans `docs/specs/p21-corrections.md` (nouvelle lettre : symptôme, preuve, cause dans
   le code, correction, vérification) et dans le récapitulatif de ce fichier.
3. `docs/PLAN.md` (P21.4 à P21.7), entrée du journal, § 1 et § 3 de ce fichier à jour.

## 5. Problèmes relevés pendant le test

Une ligne par problème, au moment où il arrive :
`- HH:MM (heure du téléphone) · n° du § 3 · symptôme · preuve (événement, membre) · piste`

- 14:44 · préparation (§ 2, point 2) · l'émulateur remis à l'heure (+816 s) avec l'appli lancée : il voit le
  téléphone « perdu » (`members=…:LOST:881s`) et jette toutes ses trames ; le téléphone le perd aussi 45 s · `snap.relay
  offsetMs=820006`, `relay.frame.drop why=nolink ageMs=819101` ; côté téléphone `mesh.link.lost silentS=45` à 14:44:53 ·
  écart au serveur pas remesuré après un saut d'horloge (§ 6, point 3) ; contourné en relançant l'appli de l'émulateur
  (`offsetMs=3705`, téléphone `DIRECT` 6 s après). À faire désormais : heure remise **avant** de lancer l'appli.
- 14:45 · préparation (relance de l'émulateur) · la 1re position après le démarrage (prise avant l'ouverture du lien)
  ne part que comme trace : le téléphone garde l'ancienne position de l'émulateur (`fix1217s`, 20 min) ; immobile, la
  suivante ne part qu'au bout de 5 intervalles (`KEEP_ALIVE_FACTOR`) · émulateur `share.fix send=true` 12:45:38.821 UTC,
  `mesh.pos.held held=1`, lien ouvert à 12:45:39.813, `TRAIL` écrit, aucun `relay.write what=p` ; téléphone
  `mesh.trail.in fixes=1` à 14:45:41 · `MeshNode.publish` sans lien → `missed` seulement ; `Msg.Trail` ajoute à
  `m.trail` sans toucher `m.fix` ni `onPosition` ; `p` jamais réécrit (même cas à la reprise `share.resume`, défaut a).
  Rattrapé seul à 14:48:08 (`share.fix send=true sinceSentS=150`, `relay.write what=p`, téléphone `fix16s`) ; avec un
  intervalle de 5 min, l'attente serait de 25 min.
- 14:56 · n° 2 (`cmd.sh awake 30`) · demande reçue (`debug.awake on=true by=collector` à 14:56:39), mais écran éteint
  3 s après (14:56:42) ; relancée (`awake 0`, puis `awake 30`) : écran allumé à distance en 0,65 s (14:57:41, donc
  `ACQUIRE_CAUSES_WAKEUP` marche sous Android 15), puis éteint 6,5 s après (14:57:47) · `sys.screen_off` sans cause
  dans le journal · à confirmer : bouton marche de l'utilisateur (voulu : l'écran reste éteint) ou verrou d'écran pas
  tenu sur l'écran de verrouillage ; le journal ne dit pas si le verrou `toolmap:debug-awake` est tenu (à ajouter).
  **Pas un défaut** : l'utilisateur confirme avoir appuyé sur le bouton marche à 14:56:42 et 14:57:47 (voulu : l'écran
  éteint par le bouton reste éteint). Écran ensuite resté allumé dès 14:58:57.
- 15:12 · n° 3 (T2, Accueil) · écran éteint 119,8 s après le passage en arrière-plan (`app.background` 15:10:15.342,
  `sys.screen_off` 15:12:15.120), demande `awake` toujours en cours (jusqu'à ≈ 15:27) · délai = mise en veille de
  2 min du téléphone : le verrou `SCREEN_BRIGHT_WAKE_LOCK` ne semble pas tenu en arrière-plan sur le téléphone (Android 15,
  One UI) ; le n° 2 ne le prouvait pas si le réglage « Garder l'écran allumé sur la carte » est coché (fenêtre
  `keepScreenOn` sur la carte) · à confirmer : bouton marche ? réglage coché ?
  **Confirmé, défaut de P21.6** : l'utilisateur n'a pas appuyé sur le bouton et le réglage n'est pas coché. Donc au
  premier plan le verrou tient (n° 2), en arrière-plan non, alors que l'allumage à distance (`ACQUIRE_CAUSES_WAKEUP`,
  14:57:41) marchait l'appli en arrière-plan. Rien dans `DebugKit` ne lâche le verrou au passage en arrière-plan (pas
  de `debug.awake on=false`) : verrou d'écran d'une appli en arrière-plan ignoré par le système du téléphone (One UI ?),
  alors que l'émulateur (Android 37) le tient. Pistes après le test : fenêtre invisible par-dessus les autres applis
  avec `FLAG_KEEP_SCREEN_ON` quand l'appli passe en arrière-plan (permission « Afficher par-dessus » à accorder une
  fois) ; journaliser l'état réel (`isHeld`, `PowerManager.isInteractive`) à chaque annonce.
- 15:31 · n° 5 (T4, arrêt forcé à 15:30:55, relance à 15:30:59) · le téléphone reprend le partage seul
  (`share.resume permitted=true` 0,23 s après `app.start`, `POS` envoyés dès 15:31:05), mais l'émulateur **jette
  tout** ce que le téléphone envoie pendant 204 s (positions, journal) : `relay.frame.in type=POS` reçus, aucun
  `mesh.pos.in`, `in=` du collecteur figé ; reprise à 15:34:09 avec `seq=1792848861` · numéros de message en
  collision : `MeshNode.seq` part de l'heure en secondes (`clock() / MS_PER_S`) et prend +1 par message ; en mode
  test (`DIAG` ≈ 1/s, plus `POS`, `PING`) il avait pris 203 s d'avance sur l'horloge (`seq=1792848848` à 15:30:45) ;
  le nouveau processus repart de l'heure (`seq=1792848665` à 15:31:05), donc sur des numéros déjà vus :
  `firstTime(member, seq)` les écarte comme doublons jusqu'à dépasser l'ancien maximum. Pistes : départ à
  `max(heure, dernier seq enregistré + marge)` ; ou oublier les numéros vus d'un membre à son `HELLO` direct.
- 15:36 · n° 6 (réinstallation, `app.exit reason=PACKAGE_UPDATED` 15:36:18, relance 15:36:19) · même collision, plus
  courte : ancien processus `seq=1792848994` à 15:36:13 (20 s d'avance), nouveau `seq=1792848985` à 15:36:26 jeté,
  `seq=1792849001` à 15:36:41 reçu : 28 s sans position sur l'émulateur ; `DIAG` jetés aussi, d'où 15
  `debug.payload.refused` (trou dans le journal) jusqu'au retour en arrière de l'envoi, journal complet ensuite.
- 15:37 · n° 7 (T15) · `share.problem problem=BAD_CODE` sur l'émulateur à 13:37:45 UTC : lien factice ouvert par
  erreur par Claude (`join.sh`), **pas un défaut**, à écarter de l'analyse. (t) : le message « … est connecté(e) »
  n'est pas un événement du journal, donc pas relevé ; le code confirme le défaut : `justJoined` est remis à chaque
  ouverture de lien direct (`ShareController.kt:221`, donc à chaque reconnexion) et affiché à la prochaine ouverture
  de l'écran Partage, même en retard (lien ouvert sur la carte à 13:37:39, écran Partage ouvert à 13:37:45).
- 15:41 · n° 8 (T18, réseau de l'émulateur coupé de 15:41:12 à 15:41:46) · reconnexion aussitôt (`relay.reconnect.now
  moved=true`, flux rouvert 0,5 s après), journal du téléphone rattrapé ; mais au retour du réseau mobile l'horloge de
  l'émulateur **recule de 815 s** (`sys.time_set`, heure du modem émulé) **pendant** la mesure de l'écart au serveur :
  `relay.clock offsetMs=408740 rttMs=-815350` (moitié du saut), corrigé 6,7 s plus tard par la 2e reconnexion
  (Wi-Fi, `offsetMs=817194`). Ensuite, deux effets de l'heure murale : (1) `share.fix send=false sinceSentS=-695`,
  plus aucune position envoyée tant que l'horloge n'a pas rattrapé l'heure du dernier envoi (+ 5 intervalles) ;
  (2) plus de `PING` : le téléphone perd l'émulateur toutes les ≈ 60 s (`mesh.link.lost silentS=45` à 15:41:56 et
  15:42:52), relien seulement par le battement (`relay.peer.open` 11 s et 14 s après). Sur un téléphone, un recul
  d'heure (heure réglée à la main, correction réseau) ferait pareil. Pistes : mesure rejetée et refaite si
  `rttMs < 0` ou trop grand ; cadence d'envoi et minuteries du maillage sur l'horloge monotone
  (`elapsedRealtime`), l'heure murale gardée pour les horodatages seulement ; écart remesuré sur
  `ACTION_TIME_CHANGED` (déjà au § 6, point 3). Contourné pour la suite : heure automatique coupée sur l'émulateur
  (`settings put global auto_time 0`), heure remise, appli relancée à 15:43:53 (§ 2, point 2).
- 15:56 · n° 11 (T6) · localisation coupée de 15:55:52.766 à 15:56:10.258 d'après le journal du téléphone (18 s ;
  l'utilisateur dit 2 min : un seul couple `sys.location on=false` / `on=true` dans le journal) · (c) : l'appli sait
  tout de suite que le fournisseur est coupé (`gps.provider client=… enabled=false`), mais l'état du partage n'en dit
  rien (`gps=true gpsUnavailable=false`, aucun `share.problem`) : seul le `.catch` du flux de fixes le pose. Piste en
  plus de celle de `p21-corrections.md` § c : avertir **dès** `gps.provider enabled=false` (pas besoin d'attendre
  2 min), et l'effacer à `enabled=true`.
- 16:01 · n° 12 (T7, mode avion 3 min) · (1) 27 tentatives de connexion (`relay.retry waitedS=8`) sans aucun réseau
  (`net.lost`, aucun `net.available` jusqu'à 16:01:46) : inutile, le retour du réseau relance déjà tout de suite
  (`share.net.available retry=true`) ; piste : sans réseau, attendre `net.available` (avec un plafond long) au lieu de
  réessayer toutes les 8 s. (2) La trame `p` retenue est la **dernière d'avant la perte du lien** (fix de 15:59:21,
  `ageS=147` à l'arrivée ; 3 `relay.p.replaced` avant), les suivantes partent dans la traînée ; comme la traînée ne
  touche pas la position affichée (§ 5 14:45), l'émulateur montre une position vieille de 2 min 27 jusqu'à la
  suivante (5 s ici, jusqu'à 5 intervalles si immobile) ; piste : à l'envoi de la traînée, la plus récente devient
  la trame `p` (ou la traînée met à jour la position affichée).
- 16:08 · n° 14 (T10, économiseur de batterie activé à 16:07:45) · **plus aucune position** dès l'extinction de
  l'écran : `gnss.stop` à 16:07:50.763, 17 ms avant `sys.screen_off` ; dernier fix 16:07:45 ; le réseau, le journal
  et les `PING` continuent (`relay.write what=c` chaque seconde), l'émulateur garde le téléphone en `DIRECT` mais sans
  position (alerte « aucune position reçue depuis 61 s » à 16:08:46) ; l'état du partage dit toujours `gps=true
  gpsUnavailable=false` (défaut c, encore). Cause probable : mode de localisation de l'économiseur (One UI, Android
  15) qui coupe le GPS écran éteint, même pour un service de premier plan. Pistes : journaliser
  `PowerManager.getLocationPowerSaveMode()` dans `sys.power` ; avertir l'utilisateur (« L'économiseur de batterie
  coupe le GPS écran éteint ») et le dire aux autres membres ; essayer le fournisseur réseau (moins précis) quand le
  GPS est coupé. **Confirmé** : écran rallumé à 16:10:59.895, `gnss.start` 21 ms après, 1er fix 12 s après
  (16:11:11), envoi aussitôt (`sinceSentS=206`) : 3 min 26 sans position, écran éteint, sans que personne le sache.
- 16:32 · n° 17 (sans partage, arrière-plan, écran éteint à 16:31:28) · **battement espacé** : `relay.write what=h`
  toutes les 15 s jusqu'à 16:31:35, puis 16:33:05 (90 s), 16:33:24, 16:34:03, 16:34:44 (18 à 41 s), alors que les
  `PING` du maillage partent toutes les 15 à 23 s ; l'émulateur ferme le lien (`relay.peer.close beatAgeMs=76038`,
  `share.peer DIRECT → RELAYED` à 16:32:50) et le rouvre 16 s après (`relay.peer.open`, donc un nouveau « … est
  connecté(e) », défaut t). Cause probable : le battement part du minuteur `toolmap-relay-timer`
  (`scheduleWithFixedDelay`, horloge monotone qui ne compte pas la veille profonde) ; le `tick()` du maillage
  compare l'heure murale chaque seconde et rattrape dès que le processeur se réveille. Piste : décider du battement
  sur l'heure murale à chaque `tick` (ou le faire partir avec le `PING`) ; sans service de premier plan, l'écart
  peut rester, donc tolérer plus longtemps un membre qui ne partage pas.
- 16:34 · n° 17 · (1) **Jeton renouvelé sans coupure** : `fb.auth via=refresh` à 16:34:25.851, `relay.stream.renew
  streamAgeS=1747`, flux rouvert 0,18 s après (`relay.stream.open` 16:34:26.077), aucun changement d'état : la
  correction g fonctionne une fois, sans partage (le n° 18 reste à faire). (2) À la réouverture, le serveur renvoie
  **333 vieilles trames** de l'émulateur (`relay.frame.drop why=old`, la plus vieille de 7 h 35) : le nettoyage
  (`cleanUp`) n'efface que les clés écrites depuis le démarrage de l'appli (`ownKeys` en mémoire), celles d'avant un
  arrêt ou une réinstallation restent ; piste : nettoyer au démarrage par une requête (`orderBy="t"&endAt=…`) sur son
  propre nœud, ou ne lire que les trames récentes (`startAt`).

## 6. Travail restant

Aucun : P21 clos le 2026-10-24. Ce qui restait (alertes et option t visibles, (o) dit aux autres membres, P21.6 en
arrière-plan, petits points du debug, analyses j et k, prochain test sur le téléphone) est dans `docs/idees-futur.md`
(Partage de position).

## 7. Commandes utiles (poste Windows, Git Bash)

Valables seulement avec `:debugkit` rebranché (ADR-072, point 3).

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
export ANDROID_SERIAL=emulator-5554 MSYS_NO_PATHCONV=1
adb="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
```

- Commandes de test : `bash tools/livetest/cmd.sh status | snapshot | mark "texte" | collect on|off | test <min>|0 | awake <min>|0 [here]`
  (`awake` sur le collecteur : l'écran des téléphones ; `here` : celui de l'appareil lui-même).
- Écran : `$adb shell dumpsys power | grep -E "mWakefulness=|toolmap:debug"`.
- Réseau de l'émulateur : `$adb shell svc wifi disable|enable` (son horloge recule alors de 816 s : la remettre par
  `$adb shell cmd alarm set-time <ms>`).
- Blocage du réseau par Android (h) : `$adb shell cmd connectivity set-chain3-enabled true` puis
  `set-package-networking-enabled false fr.toolmap` ; l'inverse pour débloquer.
- Fin de session : `CLAUDE.md` points 7 et 8 (build vert, push, CI, lien ou chemin de l'APK, miroir Drive).
