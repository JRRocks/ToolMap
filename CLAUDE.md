# CLAUDE.md — ToolMap

ToolMap est une **recréation d'AlpineQuest** (application Android de cartographie et de randonnée hors ligne) en **Kotlin natif**, enrichie de fonctionnalités maison.

## ⚠️ Modifications UI/UX : aperçu validé AVANT le code (demande de l'utilisateur, 2026-10-18)

Toute modification de l'interface ou de l'expérience utilisateur (écran, menu, bouton, dialogue, icône, couleur, texte visible, disposition, geste, enchaînement d'écrans) suit **obligatoirement** cet ordre :

1. **Rendu visuel d'abord** : montrer à l'utilisateur un ou plusieurs aperçus (maquette HTML/SVG rendue dans la conversation, ou capture retouchée) avec l'état **actuel** et l'état **proposé** ; plusieurs variantes quand un choix se pose.
2. **Attendre la validation explicite** de l'utilisateur sur la ou les previews. Sans « oui » clair, **ne pas toucher au code** : ni Kotlin/Compose, ni `strings.xml`, ni ressources (`res/`), ni icônes.
3. **Coder fidèlement à la preview validée.** Tout écart rendu nécessaire par une contrainte technique repasse par une nouvelle preview avant d'être codé.
4. **Après le code**, montrer une capture de l'émulateur à côté de la preview validée pour comparaison.
5. **Consigner** dans l'entrée du journal la preview validée (fichier ou description) et la date de validation.

Ne sont pas concernées : les modifications sans effet visible (logique, performance, tests, documentation), ni ce qui ne sert qu'au debug (voir ci-dessous).

## ⚠️ Aucune version publiée sans passage sur un émulateur (demande de l'utilisateur, 2026-11-09)

Aucune version n'est mise à disposition (artefact `toolmap-debug-apk`, pré-release `debug-<sha7>`, release `android-<version>` ou `v*`, lien donné à l'utilisateur) sans avoir tourné sur un émulateur Android, **en session cloud comme en session locale** (ADR-117) :

- **CI (toutes les sessions)** : le job « CI » installe l'APK qu'il vient de compiler sur un émulateur (API 34, `tools/emulator/smoke.sh`) et le lance ; un plantage Java ou natif, un ANR, l'écran de rapport de plantage ou un écran de carte absent font échouer le job **avant** toute publication (capture, vue de la fenêtre et journal : artefact `emulator-check`). Même contrôle dans « Release ». Le script exige aussi que le moteur natif (ADR-119) soit aux commandes dans un APK qui le contient (release comprise depuis P38.6), puis demande à l'application de mesurer le moteur contre le code Kotlin (`dumpsys activity`, lignes « Native engine » du journal du job).
- **Session cloud** (pas de KVM dans le conteneur, donc pas d'émulateur) : le lien n'est donné qu'après le run CI vert, contrôle sur émulateur compris ; quand la session a touché à l'écran, regarder la capture de `emulator-check` et le dire (le stockage des artefacts n'est pas joignable depuis le conteneur cloud : lire alors les « Texts on the screen » que `smoke.sh` écrit dans le journal du job, `get_job_logs`).
- **Session locale (poste Windows)** : avant le push, `bash tools/emulator/smoke.sh app/build/outputs/apk/debug` sur l'émulateur, puis parcourir à la main les écrans touchés par la session (captures) ; le consigner au journal.
- Un contrôle sur émulateur rouge ne se contourne pas : on corrige, on revérifie, puis on repousse.

## ⚠️ Rappels à faire à l'utilisateur à chaque session (demande de l'utilisateur, 2026-11-09)

Tant qu'une ligne ci-dessous n'est pas faite, la rappeler à l'utilisateur **à chaque session, dans le premier et dans le dernier message**, même quand la session porte sur autre chose. Le jour où c'est fait : le consigner au journal et retirer la ligne d'ici.

- (aucun rappel en cours ; le test du PDF géoréférencé dans QGIS, P37.3, et l'essai des performances, P38.5, sont faits depuis le 2026-11-10, journal, session 95. La release complète, P38.6, a été demandée et lancée le 2026-11-10, session 96.)

## ⚠️ Tâches de debug : pas d'aperçu, pas de question (demande de l'utilisateur, 2026-10-24)

- Ce qui ne sert qu'au debug (module `:debugkit`, sorti du build depuis la clôture de P21 le 2026-10-24 (ADR-072), scripts `tools/livetest/`, mode test du journal d'événements, APK de debug) se décide et se fait **sans rendu visuel et sans questionner l'utilisateur**. Pas d'affichage de debug pour ce que l'utilisateur voit déjà (ex. : écran gardé allumé, sans notification).
- Pendant les tests de debug (tests en direct du partage de position), **toujours travailler sur un plan écrit** (`docs/plan-partage.md`) et **noter chaque problème au moment où il arrive** (§ 5 de ce plan), puis le reporter dans `docs/specs/p21-corrections.md`.

## ⚠️ Idées pour plus tard : hors des plans (demande de l'utilisateur, 2026-10-24)

Les plans (`docs/PLAN.md`, `docs/plan-*.md`) sont relus à chaque session : n'y écrire **aucune** idée « pour plus tard » (ni ligne 💤, ni « Reporté », ni « Bonus »). Les écrire dans `docs/idees-futur.md`, à **ne lire que sur demande de l'utilisateur**. Travailler en petits incréments : un build par tâche.

## ⚠️ Copie la plus récente d'abord : GitHub, Drive ou local (demande de l'utilisateur, 2026-10-28)

Le projet vit à trois endroits qui peuvent prendre de l'avance l'un sur l'autre (deux postes Windows, sessions cloud) : le dépôt GitHub `JRRocks/ToolMap` (**toutes ses branches**, pas seulement `main`), le miroir Google Drive `2 - Jean/Claude Code/ToolMap/` et le dossier local. **En début de session, avant de lire le plan ou de toucher un fichier, et de nouveau avant chaque miroir Drive :**

1. **Relever la version de chaque copie** (`version=` de `version.properties` ; à version égale, le titre de l'entrée en haut de `docs/JOURNAL.md`) :
   - GitHub : `git fetch origin`, puis pour chaque branche de `git branch -r` (hors `dependabot/…`) `git show origin/<branche>:version.properties` ; sans dossier git : `git ls-remote https://github.com/JRRocks/ToolMap` et le fichier brut `https://raw.githubusercontent.com/JRRocks/ToolMap/<branche>/version.properties` ;
   - Drive : `<lecteur>:\<Mon Drive|My Drive>\2 - Jean\Claude Code\ToolMap\version.properties` (Google Drive pour ordinateur) ;
   - local : `version.properties` du dossier de travail, et `git status` (des changements non poussés font du local la copie la plus récente).
2. **La copie à la version la plus haute fait référence** : mettre les autres à jour à partir d'elle avant tout travail. Local en retard → copie de sauvegarde des fichiers modifiés, puis `git fetch` et `git checkout -B <branche> origin/<branche>` (fichiers supprimés en amont : `git clean` après vérification). Drive en retard → `scripts\drive-mirror.ps1` une fois le local à jour. GitHub en retard → pousser (§ protocole, point 7).
3. **Ne jamais écraser une copie plus récente par une plus ancienne.** `scripts\drive-mirror.ps1` refuse de lancer `/MIR` si la version du Drive dépasse celle du local.
4. **Copies divergentes** (même version et contenus différents, ou changements de chaque côté) : ne rien écraser, le dire à l'utilisateur et lui demander laquelle garder.
5. Noter dans l'entrée du journal la version de chaque copie et celle qui faisait référence.

## ⚠️ Protocole de suivi OBLIGATOIRE (journal de bord)

Chaque session (humaine ou IA) **doit** suivre ce protocole. C'est ce qui permet à plusieurs modèles successifs de faire avancer le projet sans perdre le fil.

1. **En début de session**, après avoir mis les copies à jour depuis la plus récente (§ « Copie la plus récente d'abord »), lire dans cet ordre :
   - `docs/PLAN.md` : feuille de route, tâches à ID (`P3.2`) et leur statut ;
   - les **3 entrées les plus récentes** de `docs/JOURNAL.md` (elles sont en haut du fichier) ;
   - `docs/DECISIONS.md` avant toute décision d'architecture ; ne jamais contredire un ADR sans en écrire un nouveau qui le remplace.
2. **Choisir la tâche** : si l'utilisateur ne précise rien, prendre la première tâche ⬜ non bloquée dans l'ordre de PLAN.md (suivre aussi le champ « Prochaine étape recommandée » du dernier journal). Passer son statut à 🔄 dans PLAN.md.
3. **Pendant le travail** : préfixer chaque message de commit par l'ID de la tâche, ex. `[P2.3] Téléchargement de zone : estimation du nombre de tuiles`.
4. **Avant chaque push / en fin de session** (même si la tâche n'est pas terminée) :
   - ajouter une entrée **en haut** de `docs/JOURNAL.md` en respectant le gabarit (voir ce fichier) ;
   - mettre à jour les statuts dans `docs/PLAN.md` (✅ terminé, 🔄 en cours, ⛔ bloqué + raison) ;
   - si une décision d'architecture a été prise ou modifiée : ajouter un ADR dans `docs/DECISIONS.md` ;
   - si une spécification a évolué : mettre à jour le fichier concerné dans `docs/specs/`.
5. **Ne jamais réécrire ni supprimer** une entrée passée du journal : une erreur se corrige par une nouvelle entrée.
6. Une tâche n'est ✅ que si ses **critères d'acceptation** (dans PLAN.md) sont vérifiés et que la vérification est consignée dans le journal (commandes lancées + résultat).
7. **Toujours compiler l'APK et envoyer le lien de téléchargement à l'utilisateur** (demande de l'utilisateur, 2026-10-03). Toute session se termine ainsi, même si la tâche n'est pas finie :
   - compiler en local : `./gradlew check assembleDebug` doit être vert avant le push ;
   - **le push sur GitHub est géré par Claude, sans demander** (demande de l'utilisateur, 2026-10-28 ; remplace la règle du 2026-10-24 qui réservait le push à une demande explicite, par Android Studio) : le dépôt `JRRocks/ToolMap` est public et sans restriction. La session pousse elle-même ses commits quand c'est nécessaire : à la fin de chaque tâche terminée et vérifiée, et toujours en fin de session, même si la tâche n'est pas finie. Jamais un build rouge ; sur la branche indiquée par la session (pas de PR sans demande) ; en respectant les règles du dépôt public (§ Conventions : rien de privé, identité `noreply`, aucun commit de `ToolMap-Archive`). Si le dossier de travail n'est relié à aucun dépôt GitHub (poste Windows, § Deux environnements), le dire et donner le chemin de l'APK compilé. Les points CI ci-dessous s'appliquent à chaque push ;
   - pousser, puis **attendre la fin du run « CI » GitHub Actions du commit poussé** (compilation, tests, puis contrôle sur émulateur avant toute publication : § « Aucune version publiée sans passage sur un émulateur ») (outils GitHub MCP `actions_list` → `list_workflow_runs` filtré sur la branche et l'événement `push`, puis `actions_get` → `get_workflow_run` ; sur le poste local sans ces outils : `gh run list --branch <branche> --event push`, puis `gh run view <run_id>` ; pas de boucle `sleep`) ;
   - **si la CI est rouge** : lire le journal du job en échec (`get_job_logs`, ou `gh run view <run_id> --log-failed`), corriger, revérifier en local, repousser, jusqu'au vert. Un test instable se corrige (cause trouvée), il ne se relance pas et ne se désactive pas. Un run qui échoue **sans démarrer** pour une raison de facturation (« spending limit », cas du 2026-10-16) n'est pas un échec de code : le dire, et passer au dernier point ;
   - récupérer l'artefact `toolmap-debug-apk` du run vert (`actions_list` → `list_workflow_run_artifacts`, ou `gh api repos/JRRocks/ToolMap/actions/runs/<run_id>/artifacts`) et **donner le lien dans le dernier message à l'utilisateur**, avec le commit et ce qui a changé :
     `https://github.com/JRRocks/ToolMap/actions/runs/<run_id>/artifacts/<artifact_id>`
     (téléchargement connecté à GitHub ; c'est un zip qui contient `ToolMap-debug-<version>.apk` (version de `version.properties`) ; l'artefact expire au bout de 90 jours, et la CI efface les plus anciens à chaque push : seul le dernier reste, voir `docs/specs/release.md` §1) ;
   - la CI publie aussi l'APK en pré-release GitHub `debug-<sha7>` (hors quota d'artefacts, `docs/specs/release.md` §1) : si le téléversement de l'artefact est bloqué, donner ce lien : `https://github.com/JRRocks/ToolMap/releases/download/debug-<sha7>/ToolMap-debug-<version>.apk` ;
   - **releases GitHub des deux dernières versions** (demande de l'utilisateur, 2026-10-29, ADR-099) : à chaque push (toute branche sauf Dependabot), la CI publie l'APK signé en release `android-<version>` et ne garde que les deux versions les plus récentes ; le lien public sans compte, à donner aussi : `https://github.com/JRRocks/ToolMap/releases/download/android-<version>/ToolMap-debug-<version>.apk`. iOS : même règle (`ios-<version>`) dès que la CI iOS produit une application ;
   - une session sans changement de code donne quand même le lien de l'APK du dernier run vert de la branche ;
   - si le lien ne peut pas être obtenu (CI bloquée, quota…), le dire explicitement avec la raison, et donner le chemin de l'APK compilé en local (`app/build/outputs/apk/debug/ToolMap-debug-<version>.apk`).
8. **Miroir Google Drive** (demande de l'utilisateur, 2026-10-17) : en fin de session sur le poste Windows, **après le run CI vert du dernier push**, lancer `powershell -ExecutionPolicy Bypass -File scripts\drive-mirror.ps1` (voir § « Miroir Google Drive » ci-dessous) et le signaler dans le dernier message. **Chaque APK compilé par GitHub est archivé sur le Drive** (demande de l'utilisateur, 2026-10-29) : le script copie celui du dernier push et ceux des releases `android-<version>` qui manquent ; jamais un APK compilé en local (signé avec la clé de debug publique, pas avec la clé de publication).

## Miroir Google Drive (2e sauvegarde)

Google Drive est le **miroir des fichiers locaux** du poste Windows (2e sauvegarde, en plus de GitHub) :

| Drive | Contenu |
|---|---|
| `2 - Jean/Claude Code/ToolMap/` | copie exacte du dossier local du poste qui fait le miroir : `C:\Bureau\Desktop\Jean\ToolMap` ou `C:\Users\<utilisateur>\Desktop\Claude Code\ToolMap` (fichiers sources ; sans `.git/`, `build/`, `.gradle/`, `.kotlin/`, `.idea/`, `local.properties`, APK) |
| `2 - Jean/Claude Code/APK release/` | APK **compilés par GitHub** (CI, signés avec la clé de publication), **nommés `AppName.version.apk`**, ex. `ToolMap.0.4.1.apk` (version de `version.properties`) ; ceux d'avant le 2026-10-29 (sauf 0.4.142 et 0.4.143) sont des builds locaux signés avec la clé de debug |

- `scripts/drive-mirror.ps1` fait les deux : `robocopy /MIR` vers le dossier ToolMap (ce qui est supprimé en local l'est aussi sur le Drive), puis archive dans `APK release/ToolMap.<version>.apk` les APK de GitHub (avec `gh`) : chaque release `android-<version>` absente du Drive (ou de taille différente), et l'artefact du run CI vert du commit courant quand sa version n'a pas de release. Les APK compilés par une session cloud ne restent sur GitHub que tant que leur release fait partie des deux dernières : ils sont archivés à la session suivante sur le poste Windows s'ils y sont encore.
- Il passe par **Google Drive pour ordinateur** (Drive monté en lecteur local, `<lecteur>:\Mon Drive\…`, détecté automatiquement). Le connecteur Google Drive de Claude ne sert qu'à vérifier ou créer des dossiers : il ne peut pas téléverser un projet entier ni un APK de plusieurs dizaines de Mo.
- Le miroir va toujours du local vers le Drive, jamais dans l'autre sens : ne pas modifier les fichiers directement sur le Drive.

## Documents de référence

| Fichier | Contenu |
|---|---|
| `docs/PLAN.md` | Vision, architecture, feuille de route par phases, critères d'acceptation |
| `docs/JOURNAL.md` | Journal de bord chronologique (plus récent en haut) |
| `docs/DECISIONS.md` | Décisions d'architecture (ADR) |
| `docs/specs/aqx-format.md` | Format AQX d'AlpineQuest (import/export des sources de cartes) |
| `docs/specs/p2p-sharing.md` | Partage de position : protocole commun (trames chiffrées, maillage, cadence) et mode direct WebRTC (appairage QR) |
| `docs/specs/print-pdf.md` | Export PDF à l'échelle (maths, mise en page) |
| `docs/specs/firebase-relay.md` | Serveur de coordination Firebase du partage de position, états des pairs, mise en place de la console (P13) |
| `docs/specs/backup-format.md` | Format de l'archive de sauvegarde / restauration (P9.6) |
| `docs/specs/performance.md` | Performance, batterie, tuiles (affichage, téléchargement de zone, règles d'usage des serveurs), restructuration légère du code et protocole de mesure sur l'émulateur (P15 ; remplace l'audit P10.1) |
| `docs/specs/problems.md` | **Aucune erreur silencieuse** : `Problems` (prévu / bogue), vérification `SilentFailureTest`, rapports nettoyés, envoi par le serveur de coordination, tickets GitHub par le workflow `problems.yml`, mise en place (P36, ADR-116) |
| `docs/revue-2026-11.md` | Revue performance et sécurité du 2026-11-08 : constats, corrigés, à montrer en aperçu, à décider, à vérifier (P36.1, P36.6 à P36.8) |
| `docs/specs/native-engine.md` | **Langage par domaine et moteur de calcul natif en Rust** (`:engine`) : analyse, ce que le moteur remplace, parité au bit près avec le code Kotlin, mesures, debug avant release, compilation (Rust, NDK), essai sur l'émulateur (P38, ADR-119) |
| `docs/specs/release.md` | Signature, APK / AAB de release, taille, licence (P10.3) |
| `docs/specs/ui-index.md` | **Index de l'interface** : chaque bouton, écran et réglage, sa logique et son statut (référence unique des menus, à tenir à jour ; remplace la spec P11) |
| `docs/specs/ui-reorganization.md` | Réorganisation légère des menus et fonctions utiles proposées, fidèles à AlpineQuest (P14) |
| `docs/specs/routing.md` | Itinéraires calculés avec BRouter (AIDL) : étapes, tronçons, opérations d'édition, comportements sans données, GPX (P19, ADR-064) |
| `docs/specs/elevation-maps.md` | Cartes selon l'altitude : bande d'altitude par calque, masque dans l'intercepteur, ET / OU avec la plage de zoom, panneau « Conditions d'affichage », altitudes hors ligne, coût mesuré (P20, ADR-068) |
| `docs/specs/blend-modes.md` | Couleurs et modes de fusion des calques : 16 modes W3C et teinte dans `:core`, recette dans l'adresse des tuiles, tuile composée dans l'intercepteur, coût mesuré (P26, ADR-111) |
| `docs/specs/event-log.md` | Journal d'événements (tous les builds), module `:debugkit` (sorti du build le 2026-10-24, ADR-072) : collecteur, mode test, envoi du journal par le groupe chiffré, commandes `adb` (P21, ADR-070) |
| `docs/specs/live-test.md` | Test en direct du partage de position : préparation, rôles, scénarios T0 à T19, analyse (`tools/livetest/`) |
| `docs/plan-fonctions-2026-10.md` | **Plan détaillé de P22 à P28** (alerte et écoute du groupe, saisie des valeurs, grilles MGRS / UTM, partage des favoris, couleurs et fusion, waypoints du groupe, dessin) : décisions de l'utilisateur, tâches, critères |
| `docs/plan-design-2026-10.md` | **Refonte de l'interface (P29)** : constats de l'audit, règles communes, étapes onglet par onglet ; 5 variantes à chaque changement et notification pour demander le choix (demande du 2026-10-26) |
| `docs/idees-futur.md` | Idées pour plus tard : **à ne lire que sur demande de l'utilisateur** |
| `docs/plan-partage.md` | Plan de travail des tests en direct du partage de position (P21, **clos le 2026-10-24**) : règles, état, ordre des tests, problèmes notés pendant le test ; à relire seulement pour un nouveau test en direct |
| `docs/specs/p21-corrections.md` | Défauts trouvés au premier test en direct (2026-10-24) : preuves, causes dans le code, corrections proposées, vérifications, ordre conseillé (P21.5, P21.6) |
| `docs/specs/multi-zoom.md` | Tuiles partagées entre cartes, cartes multi-zoom et zoom forcé, zooms manquants complétés (P12) |
| `docs/comparaison-alpinequest-toolmap.md` | Comparaison AlpineQuest / ToolMap faite sur émulateur, écarts corrigés (source de P16) |
| `docs/aq-icons-inventaire.md` | Relevé des icônes d'AlpineQuest : waypoints, activités, menus (P16.6, P16.7) |
| `tools/icons/` | Catalogue et générateur des icônes de waypoint et d'activité (ADR-056) |
| `fixtures/` | Fichiers de test (ex. `fixtures/aqx/IGN_Topo_zoom_test.aqx` fourni par l'utilisateur) |

## Stack technique (voir ADR)

- Kotlin, Jetpack Compose (Material 3), coroutines/Flow, Hilt, Room, WorkManager, OkHttp.
- Carte : MapLibre Native Android (build OpenGL ES `android-sdk-opengl`, ADR-028), avec un intercepteur OkHttp qui construit les URL des tuiles à partir des définitions AQX et les sert depuis le stockage local MBTiles.
- Modules Gradle : `:core` (Kotlin JVM pur, testable sans Android), `:engine`, `:data`, `:p2p`, `:app`.
- **Un langage par domaine** (ADR-119, `docs/specs/native-engine.md`) : Kotlin partout, sauf le moteur de calcul par pixel des tuiles dessinées par l'application (fusion, teinte, matrice de couleur, masque d'altitude, PNG rapide, Douglas-Peucker), en **Rust** dans `:engine` (`engine/src/main/rust`, `libtoolmap_engine.so`), installé à la porte `Compute.accelerator` de `:core`. Le code Kotlin (`…Reference`) reste la référence et le repli. Rust 1.97.0 (`rust-toolchain.toml`) et NDK 28.2.13676358, facultatifs sur un poste, obligatoires en CI.
- AGP 9.4 + Gradle 9.6 + Kotlin 2.4 (Kotlin intégré à AGP : ne pas appliquer `kotlin-android`), compileSdk 37, minSdk 26 (ADR-007). Versions figées dans `gradle/libs.versions.toml`.
- Types de build : `debug` (l'APK distribué par la CI ; **non debuggable** depuis le 2026-11-09, ADR-120 ; ses outils d'essai passent par `BuildConfig.DEV_TOOLS`, jamais par `BuildConfig.DEBUG`), `benchmark` (R8, mesures scriptées), `release` (pas avant la fin du test QGIS, P38.6).

## Numéro de version

Le numéro est dans `version.properties` et **son CORRECTIF augmente tout seul** à chaque build local d'APK dont les sources ont changé (ADR-059, `docs/specs/release.md` §1) : ne pas le changer à la main, sauf pour un nouveau MINEUR (`version=0.5.0`, `sourceHash` vidé). Le titre et l'objectif d'une entrée du journal entrent dans l'APK (« Nouveautés ») : **écrire l'entrée du journal avant le dernier build**. Toujours committer et pousser `version.properties` avec le code : la CI échoue si les sources poussées n'y correspondent pas. **Lancer `./gradlew spotlessApply` seul, avant `./gradlew check assembleDebug`** : dans une même commande, la remise en forme passe après le calcul de l'empreinte et le numéro ne correspond plus aux sources (vu les 2026-11-09, sessions 92 et 93) ; vérifier avant de pousser par `CI=true ./gradlew help`.

## Commandes

```bash
./gradlew check assembleDebug     # CI complète : ktlint (Spotless) + lint + tests unitaires + APK debug
./gradlew assembleDebug -Ptoolmap.debuggable=true   # APK de debug avec débogueur et run-as : il n'est plus debuggable sans cela (P36.7, ADR-120)
./gradlew :core:test testDebugUnitTest   # tests seuls (":core" est un module JVM pur : pas de testDebugUnitTest)
./gradlew spotlessApply           # corrige le style (ktlint) avant de committer
./gradlew lint                    # Android Lint seul
bash tools/livetest/setup.sh app/build/outputs/apk/debug/ToolMap-debug-<version>.apk   # émulateur collecteur d'un test en direct (docs/specs/live-test.md ; :debugkit à rebrancher d'abord, ADR-072)
bash tools/emulator/brouter-setup.sh [E5_N45 …]   # BRouter et ses cases .rd5 sur l'émulateur, depuis le cache ~/.cache/toolmap/brouter (hors dépôt : rien n'est retéléchargé), puis : adb emu avd snapshot save default_boot
bash tools/emulator/smoke.sh app/build/outputs/apk/debug   # contrôle sur émulateur avant toute publication (installe, lance, cherche plantage / ANR, moteur natif aux commandes puis mesuré ; capture et journal dans emulator-check/)
./gradlew :engine:cargoTest :engine:testDebugUnitTest   # moteur natif : tests du crate Rust, puis parité au bit près avec le code Kotlin (sautée sans Rust)
./gradlew :data:testDebugUnitTest --tests '*TilePipelineBenchmark*' -Ptoolmap.engine.bench=true   # Kotlin contre Rust sur la machine de build (data/build/engine-bench.txt)
bash tools/perf/E-engine.sh       # Kotlin contre Rust sur l'émulateur (APK de la CI ou benchmark installé ; demande faite par dumpsys)
./gradlew :app:generateBaselineProfile   # régénère app/src/main/generated/baselineProfiles/baseline-prof.txt (émulateur branché, ≈ 6 min ; la CI ne le fait pas)
# APK : app/build/outputs/apk/debug/ToolMap-debug-<version>.apk (nom : docs/specs/release.md §1)
./gradlew :app:assembleBenchmark  # APK benchmark (R8, non debuggable, clé de debug ; ADR-057) : celui des mesures P15
bash tools/perf/run-all.sh app/build/outputs/apk/benchmark/ToolMap-benchmark-<version>.apk   # mesures sur l'émulateur (tools/perf/README.md)
```

Les commandes longues (> 2 min) doivent tourner en arrière-plan avec une sortie vers un fichier de log.

### Deux environnements de travail

- **Cloud** (claude.ai/code) : le **hook SessionStart** (`.claude/hooks/session-start.sh`) installe le SDK Android dans `$HOME/android-sdk`, écrit `local.properties` et configure le miroir Maven Central du bac à sable (ADR-009). Si une session démarre sans lui, lancer à la main : `CLAUDE_CODE_REMOTE=true CLAUDE_PROJECT_DIR=$PWD ./.claude/hooks/session-start.sh` puis `export ANDROID_HOME=$HOME/android-sdk`. Un build à froid prend ≈ 2 min. En cas de `429 Too Many Requests` de Maven Central, vérifier que `~/.gradle/init.d/cloud-mirror.init.gradle` existe. Pas d'émulateur (pas de KVM) : c'est la CI qui fait le contrôle sur émulateur. Le hook installe aussi le NDK (`ndk;28.2.13676358`) et prépare le Rust du moteur natif (`rustup` et `cargo` sont dans l'image) ; s'il n'y arrive pas, le build local laisse le moteur de côté et le dit (« moteur natif absent de ce build »), le code Kotlin fait tout. GDAL s'installe (`apt-get install -y gdal-bin`) pour vérifier un PDF géoréférencé (`PrintGeoTest`).
- **Poste Windows de l'utilisateur** (depuis le 2026-10-15, sessions 27 et suivantes) : SDK d'Android Studio (`local.properties` → `C:/Users/<utilisateur>/AppData/Local/Android/Sdk`), `./gradlew` lancé depuis Git Bash avec le JDK d'Android Studio ; un build complet prend ≈ 4 à 5 min. **Un émulateur est disponible** (« Medium_Phone », Android 37) : `adb install -r -g app/build/outputs/apk/debug/ToolMap-debug-<version>.apk`, gestes par `adb shell input`, écran lu par `uiautomator dump` et captures. Le hook cloud ne fait rien ici.
  - **APK de debug non debuggable** (P36.7, ADR-120, 2026-11-09) : ses performances sur l'émulateur valent pour un téléphone, mais `run-as` et le débogueur demandent un build `-Ptoolmap.debuggable=true`. Pour mesurer, installer l'APK de la CI (il a le moteur natif).
  - **Moteur natif** : le build marche sans Rust ni NDK (APK sans la bibliothèque, le code Kotlin fait tout, tests de parité sautés). Pour le compiler aussi en local : Rust par [rustup](https://rustup.rs) (version et cibles Android suivent `engine/src/main/rust/rust-toolchain.toml`) et le NDK par Android Studio (SDK Manager ▸ SDK Tools ▸ « NDK (Side by side) » 28.2.13676358). L'APK de la CI a toujours le moteur.
  - **Attention git** : sur le poste `C:\Bureau\Desktop\Jean`, le dossier `ToolMap/` n'est **pas suivi** par le dépôt qui le contient (branche `sauvegarde/…`, sans remote) : rien ne s'y annule par git. Faire une copie des fichiers avant une réécriture importante. Sur le poste `C:\Users\<utilisateur>\Desktop\Claude Code\ToolMap`, le dossier est un **clone git** de `JRRocks/ToolMap` (depuis le 2026-10-28 ; `core.autocrlf=false`, identité `noreply` réglée dans le dépôt) : la mise à jour se fait par `git fetch` (§ « Copie la plus récente d'abord »).
  - **Dépôts GitHub** (depuis le 2026-10-27) : le code est poussé dans le dépôt **public** `JRRocks/ToolMap`, branche `main`, repartie d'un seul commit sans historique (0.4.81). L'historique complet jusqu'à 0.4.80 est dans le dépôt **privé** `JRRocks/ToolMap-Archive` : ne jamais en pousser un commit ni une branche dans `ToolMap` (ils portent l'adresse e-mail de l'utilisateur). Identité des commits : `JRRocks <314636139+JRRocks@users.noreply.github.com>`, jamais l'adresse personnelle.

Ce que l'émulateur ne remplace pas : le GPS réel, les capteurs (baromètre), la caméra, le partage entre deux téléphones, les services écran éteint et la consommation se vérifient par l'utilisateur sur son téléphone, avec l'APK de la CI (artefact `toolmap-debug-apk`, signé avec la clé de debug fixe `app/debug.keystore`, donc réinstallable par-dessus la version précédente) ou l'APK compilé en local.

## Conventions

- **Français** : documentation, journal, textes de l'interface (`strings.xml`), messages de commit.
- **Anglais** : identifiants, noms de fichiers source et commentaires de code.
- Logique métier pure (géo, formats, maths d'échelle, codec P2P) dans `:core`, couverte par des tests unitaires JVM.
- **Aucune erreur silencieuse** (demande de l'utilisateur, 2026-11-08, ADR-116, `docs/specs/problems.md`) : pour l'utilisateur une erreur peut rester silencieuse (même repli qu'avant, aucun texte nouveau), pour qui débogue **jamais**. Tout échec attrapé passe par `fr.toolmap.core.problem` : `Problems.expected("zone.action", e)` / `catchExpected("zone.action", IOException::class) { }` pour un échec prévu (réseau, fichier ou donnée venus de l'extérieur, refus du système : journal et logcat), `Problems.report` / `Problems.unexpected` / `catchReported("zone.action") { }` pour un bogue (journal, logcat **et ticket GitHub** automatique, étiquette `rapport-auto`). Jamais `runCatching`, jamais un `catch` qui ne relance pas, ne transmet pas l'exception et n'appelle pas `Problems` ; un attrape-tout (`Exception`, `Throwable`) se signale comme bogue ; le lieu est un littéral. `SilentFailureTest` le vérifie dans `./gradlew check`. Les plantages, plantages natifs et ANR deviennent aussi des tickets (rien à écrire). Un ticket ne contient rien de privé (`ProblemScrubber`) : ne jamais y ajouter de message, nom, chemin ou coordonnée bruts.
- **Moteur natif** (ADR-119) : le code Rust de `engine/src/main/rust` donne **les mêmes résultats au bit près** que le code Kotlin qu'il remplace (`Blend`, `MaskRaster`, `PathSimplifier`, fonctions `…Reference`). Toute formule changée d'un côté se change de l'autre dans le même commit ; `EngineParityTest` (dans `./gradlew check`) le vérifie. Pas de code `unsafe` hors de `lib.rs` (les appels JNI), pas de nouvelle dépendance Rust sans ADR. Un appel que le moteur refuse retombe sur le code Kotlin et se signale (`Problems`).
- Expressions régulières : échapper **toutes** les accolades littérales (`\{`, `\}`), même dans une classe. Le moteur d'Android (ICU) refuse une `}` nue que la JVM des tests accepte (plantage du 2026-10-03) ; `RegexPortabilityTest` le vérifie.
- Ne jamais committer de clé API personnelle de l'utilisateur. La clé publique IGN `ign_scan_ws` présente dans la fixture est tolérée.
- **Dépôt public** (2026-10-27) : rien de privé dans un fichier, un message de commit ou une image : adresse e-mail, prénom d'un tiers, nom d'utilisateur ou chemin du poste (écrire `<utilisateur>`), position réelle, lien d'invitation `toolmap://join?d=…`, journaux de test (`tools/livetest/out/`), métadonnées des captures du téléphone (EXIF, bloc Samsung de fin de fichier : à retirer). Vérification du 2026-10-27 : journal, sessions 65 et 66.
- Développer sur la branche indiquée par la session. Pas de PR sans demande explicite de l'utilisateur.
