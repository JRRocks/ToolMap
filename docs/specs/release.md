# Publication — signature, formats, taille (P10.3)

> Décision : ADR-027, précisée par ADR-045 (téléversement des artefacts non bloquant). Workflow : `.github/workflows/release.yml`. Configuration : `app/build.gradle.kts`.

## 1. Ce qui sort

| Fichier | Taille mesurée (suite 16) | Usage |
|---|---|---|
| APK de debug de la CI (`toolmap-debug-apk`) | 114,9 MB avant le filtre d'architectures (4 ABI) | installation par l'utilisateur, signé par la clé de publication quand son secret existe, sinon par la clé de debug publique (ADR-047) |
| APK de release non réduit (`assembleRelease`) | 82,3 MB (3 ABI : `arm64-v8a`, `armeabi-v7a`, `x86_64`) | installation directe une fois signé |
| Bundle de release (`bundleRelease`, `.aab`) | 42,5 MB | dépôt sur Google Play, qui livre à chaque téléphone sa seule architecture |
| APK de release réduit par R8 (`-Ptoolmap.minify=true`) | 70,7 MB | **jamais lancé sur un téléphone** : à essayer avant de s'y fier |

Les bibliothèques natives de MapLibre (≈ 13 MB par architecture) et de WebRTC (≈ 12 MB par architecture) font l'essentiel du poids. Le filtre `abiFilters` retire le x86 32 bits ; l'AAB supprime en plus les architectures inutiles pour chaque téléphone.

### Nom des fichiers APK

`ToolMap-<phase>-<version>.apk`, où `<phase>` vaut `debug`, `pre-release` ou `release`, et `<version>` est le `versionName` (`version.properties`, lu par `app/build.gradle.kts`) : par exemple `ToolMap-debug-0.2.0.apk`, pour la version 0.2.0 (règle : « Numéro de version » ci-dessous). Le nom est fixé dans `app/build.gradle.kts` (bloc `androidComponents`), donc identique en local et sur la CI. Le `versionCode` (numéro d'exécution du workflow) n'y figure pas : le run de l'artefact le donne. `pre-release` n'est pas encore un type de build de l'application : la phase arrivera avec le premier build de pré-publication. Le type de build `benchmark` (P15.1, ADR-057 : release avec R8, non debuggable, signée avec la clé de debug fixe, donc installable par-dessus l'APK de debug) sort sous le même schéma : `ToolMap-benchmark-<version>.apk`, en local seulement (la CI ne le compile pas). Les artefacts GitHub gardent leur nom (`toolmap-debug-apk`, `toolmap-release-apk`) ; le zip contient le fichier ainsi nommé.

### Numéro de version

Tant que l'application n'est pas publiée, le `versionName` a la forme `0.MINEUR.CORRECTIF` (ADR-040) :

- une livraison qui ajoute une fonction ou change l'interface passe au **MINEUR** suivant, et le CORRECTIF repart à 0 (`0.1.0` → `0.2.0`) ;
- une livraison qui ne corrige que des défauts passe au **CORRECTIF** suivant (`0.2.0` → `0.2.1`).

**Le CORRECTIF augmente tout seul** (ADR-059, qui remplace le point 2 d'ADR-040). Le numéro est dans `version.properties` (racine), avec l'empreinte `sourceHash` des sources dont il a été compilé. À chaque build local qui produit un APK ou un bundle (tâches `assemble*`, `bundle*`, `install*`, `package*`), `app/build.gradle.kts` recalcule l'empreinte (SHA-256 des fichiers Gradle, des jeux de sources hors tests de `:app`, `:core`, `:data`, `:p2p` et des titres et objectifs du journal, que « Nouveautés » copie dans l'APK ; retours chariot ignorés) ; si elle a changé, le CORRECTIF prend +1 et le fichier est réécrit. Deux APK différents n'ont donc jamais le même numéro, quel que soit le changement ; un APK recompilé à l'identique garde le sien. Les synchronisations de l'IDE, les tests et le style ne consomment pas de numéro ; un build qui échoue après l'incrémentation laisse un trou dans la suite, sans importance.

Pour un nouveau MINEUR ou MAJEUR (règle ci-dessus), écrire le numéro à la main dans `version.properties` (`version=0.5.0`) et vider `sourceHash` : le build suivant le garde tel quel. `version.properties` se committe et se pousse avec le changement. **La CI n'écrit jamais** : si les sources poussées ne correspondent pas à l'empreinte (poussées sans build local), le build échoue avec un message qui dit de compiler l'APK en local. Limite : deux copies de travail qui partent du même numéro (poste Windows et session cloud) peuvent produire chacune le même numéro suivant ; ne travailler que dans une copie à la fois, ou, en fusionnant, prendre le plus grand des deux +1.

Le numéro apparaît dans le nom de l'APK, dans le titre de la pré-version de debug, dans l'écran « Aide et à propos » et dans le nom du miroir Drive (`ToolMap.<version>.apk`) ; le journal (`docs/JOURNAL.md`) indique le numéro livré dans chaque entrée. `1.0.0` est une décision de l'utilisateur, prise quand l'application aura été essayée sur un téléphone et qu'une publication est prévue. Le `versionCode` ne suit pas ce numéro (ADR-027, point 4).

### Place occupée par les artefacts

Le compte GitHub gratuit a **0,5 Go de stockage d'artefacts par mois, partagé entre tous ses dépôts** (et avec GitHub Packages). L'usage se compte en Go × heures : un APK de 50 Mo gardé tout le mois prend 10 % du forfait, et effacer un artefact arrête de consommer sans rendre ce qui l'a déjà été. Quand le forfait est épuisé, la CI ne peut plus téléverser (« Artifact storage quota has been hit ») jusqu'au recalcul, toutes les 6 à 12 h, ou jusqu'au mois suivant.

D'où, dans `ci.yml`, l'étape « Delete old artifacts » à chaque push, avant le téléversement : elle **supprime les `toolmap-debug-apk` précédents avant de téléverser celui du run** (il n'en reste qu'un ; l'ancien texte, « le plus récent », était faux : le code en gardait deux), efface les rapports d'échec (`reports`, de toute façon gardés 7 jours au plus) et ne touche pas aux artefacts de release. Un lien d'APK donné à l'utilisateur reste donc valable jusqu'au push suivant qui réussit son téléversement. L'étape ne fait jamais échouer la CI (`continue-on-error`) et ne tourne pas sur les pull requests (jeton en lecture seule).

Le téléversement de l'APK (`Upload debug APK`) et celui des rapports (`Upload reports`) sont **non bloquants** depuis ADR-045 : quand le quota refuse l'envoi, la CI reste verte, le journal de l'étape donne la cause (« Artifact storage quota has been hit »), et l'APK reste livré par la pré-version du paragraphe suivant, qui ne compte pas dans le quota. Le run d'un tel push n'a alors pas d'artefact : c'est le lien de la pré-version qu'il faut donner.

**Garde de livraison** (ADR-046) : la dernière étape, « Check that the APK was delivered », ne tourne que sur un push et seulement si les étapes bloquantes précédentes ont réussi. Elle passe si l'artefact a été téléversé, ou si la pré-version `debug-<sha7>` porte un APK (y compris sur un re-run du même commit, où la pré-version existe déjà). Elle **échoue** si aucun des deux n'existe : un run vert n'a donc jamais zéro APK livré sur un push. Une pull request n'a pas de pré-version : la garde ne s'y applique pas.

### Release de debug

Les fichiers d'une release GitHub ne comptent pas dans le quota d'artefacts. Donc, à chaque push **sur `main`**, l'étape « Publish the debug APK » crée une **pré-release** `debug-<sha7>` (commit exact, titre « ToolMap debug <version> (<sha7>) ») qui porte l'APK sous son nom `ToolMap-debug-<version>.apk`. Seule la plus récente est gardée : les anciennes pré-releases `debug-*` et leurs étiquettes sont supprimées. Le lien de téléchargement direct est :

`https://github.com/JRRocks/ToolMap/releases/download/debug-<sha7>/ToolMap-debug-<version>.apk`

Le dépôt étant public (depuis le 2026-10-27), ce lien se télécharge sans compte GitHub ; l'artefact d'un run, lui, demande d'être connecté. **Seule `main` publie** (P31, ADR-094) : l'application propose la plus récente de ces pré-versions comme mise à jour (§7), une branche de travail ne doit donc pas en créer ; son APK n'est livré que par l'artefact du run, et la garde de livraison ne regarde alors que lui.

## 2. Signature

La clé de publication **n'est jamais dans le dépôt** (`*.jks` et `*.keystore` sont ignorés, sauf la clé de debug publique). `app/build.gradle.kts` crée la configuration `release` si `TOOLMAP_KEYSTORE_FILE` existe dans l'environnement, avec `TOOLMAP_KEYSTORE_PASSWORD`, `TOOLMAP_KEY_ALIAS` et `TOOLMAP_KEY_PASSWORD`. Sans clé, `assembleRelease` et `bundleRelease` réussissent et produisent des fichiers **non signés** (un APK non signé ne s'installe pas ; Google Play signe lui-même un bundle déposé).

**Elle signe aussi l'APK de debug de la CI** (ADR-047) : quand les secrets existent, `ci.yml` décode la même clé et la configuration `debug` la prend. Sans secret, la CI signe avec la clé de debug publique (`app/debug.keystore`, mot de passe `android`, donc connu de tous) et le dit dans le journal et dans les notes de la pré-version. Une installation signée par une autre clé doit être **désinstallée une fois** avant l'APK signée par la clé de publication (les données locales partent, sauf sauvegarde P9.6). L'étape « Show the signer of the debug APK » affiche l'empreinte SHA-256 du certificat ; celle de la clé de debug publique est `E4:E1:CF:D2:06:03:A5:C2:2E:8E:C2:0A:3C:17:D7:85:36:44:01:AA:51:B9:24:05:91:99:FA:6D:72:C3:EE:A9`.

Créer la clé (une seule fois, **sur ton ordinateur**, jamais dans le dépôt, ni dans une session ou un chat). **À conserver hors du dépôt, avec ses mots de passe, dans un gestionnaire de mots de passe : perdue, elle interdit toute mise à jour de l'application déjà installée.**

```
keytool -genkeypair -v -keystore toolmap-release.jks -alias toolmap -keyalg RSA -keysize 4096 -validity 10000
```

Encoder le fichier sur une seule ligne, pour le secret :

```
base64 -w0 toolmap-release.jks > toolmap-release.b64
```

(macOS : `base64 -i toolmap-release.jks | tr -d '\n' > toolmap-release.b64` ; Windows, PowerShell : `[Convert]::ToBase64String([IO.File]::ReadAllBytes("toolmap-release.jks")) | Set-Content toolmap-release.b64`.)

Pour que le workflow signe : dans les paramètres du dépôt GitHub (Settings ▸ Secrets and variables ▸ Actions ▸ New repository secret), créer quatre secrets :

| Nom du secret | Valeur |
|---|---|
| `TOOLMAP_KEYSTORE_BASE64` | le contenu de `toolmap-release.b64` |
| `TOOLMAP_KEYSTORE_PASSWORD` | le mot de passe du magasin, choisi avec `keytool` |
| `TOOLMAP_KEY_ALIAS` | `toolmap` |
| `TOOLMAP_KEY_PASSWORD` | le mot de passe de la clé (souvent le même) |

Puis supprimer `toolmap-release.b64` : le secret suffit.

**État (2026-10-29, P31.1)** : la clé existe, créée sur le poste Windows de l'utilisateur dans un dossier hors du dépôt (`C:\Users\<utilisateur>\.toolmap-signing\`), avec un fichier de mot de passe et un script `set-github-secrets.ps1` qui pose les quatre secrets par `gh secret set`. Empreinte SHA-256 du certificat : `85:33:4C:0A:85:37:07:F5:7F:B2:BE:DF:6E:65:49:9A:5E:B4:51:EE:DB:93:87:32:C9:2F:4F:CD:A6:A0:D3:A4` (ADR-094). À ranger, avec son mot de passe, dans un gestionnaire de mots de passe. Tant que `TOOLMAP_KEYSTORE_BASE64` manque, la CI signe avec la clé de debug publique.

## 3. Lancer une release

Actions ▸ Release ▸ Run workflow (ou pousser une étiquette `v<version>`, par exemple `v0.2.0`). Le numéro de version interne (`versionCode`) est le numéro d'exécution du workflow ; le nom (`versionName`) est dans `version.properties`, à jour après un build local, avant l'étiquette. Les artefacts : `toolmap-release-apk`, `toolmap-release-aab` (et `toolmap-r8-mapping` si R8 était demandé : sans ce fichier, les traces de plantage ne se lisent pas).

En local : `ANDROID_HOME=… TOOLMAP_KEYSTORE_FILE=… … ./gradlew assembleRelease bundleRelease`.

## 4. Pourquoi R8 est désactivé par défaut

R8 renomme et retire du code : toute bibliothèque qui passe par la réflexion ou le JNI (MapLibre, WebRTC) peut planter **au démarrage ou à l'usage** d'une manière que les tests JVM ne voient pas. Les bibliothèques fournissent leurs règles, mais sans téléphone pour lancer la carte, un appel WebRTC et un scan de QR sur un build réduit, activer R8 par défaut serait parier. La compilation du build réduit est vérifiée (voir le journal), pas son exécution. Quand l'utilisateur aura essayé `-Ptoolmap.minify=true` sur son téléphone (carte, enregistrement, partage avec QR, impression PDF, sauvegarde), il suffira de changer la valeur par défaut dans `app/build.gradle.kts`.

## 5. Licence du code

**Non choisie.** Sans fichier `LICENSE`, le code reste « tous droits réservés » à son auteur : c'est à lui de choisir (GPL-3.0 pour que les dérivés restent libres, MIT ou Apache-2.0 pour une réutilisation sans condition). Toutes les bibliothèques embarquées sont sous licences permissives (BSD, Apache 2.0) et compatibles avec n'importe lequel de ces choix ; l'écran « Aide et à propos » les liste (`ThirdParty.kt`, gardé à jour par un test).

## 6. Avant la première publication

- Essayer l'APK sur un téléphone (cartes, traces, partage, impression, sauvegarde) : toutes les lignes 🔄 de `PLAN.md`.
- Créer la clé de publication et les secrets (§2).
- Choisir la licence (§5) et le nom du paquet définitif (`fr.toolmap` pour l'instant : le changer plus tard coûte une désinstallation).
- Dépôt public (2026-10-27, journal sessions 65 et 66) : `JRRocks/ToolMap` est reparti d'un seul commit, sans l'historique, qui portait l'adresse e-mail de l'utilisateur ; l'historique complet reste dans le dépôt privé `JRRocks/ToolMap-Archive`. Un dépôt public montre tout : historique, `refs/pull/*`, journaux des runs, artefacts et pré-version `debug-<sha7>` (son APK contient l'adresse et la clé Firebase, comme tout APK distribué). Les commits se font avec l'adresse `noreply` de GitHub ; les 4 secrets de §2 et les 2 du serveur (`TOOLMAP_FIREBASE_DB_URL`, `TOOLMAP_FIREBASE_API_KEY`) sont à créer dans chaque nouveau dépôt.
- Fiche Google Play : politique de confidentialité (le texte de l'écran « Aide et à propos » est le point de départ), déclaration de l'usage de la localisation en arrière-plan (services de premier plan de type `location`) et de la caméra (scan de QR).

## 7. Mise à jour depuis GitHub (P31, ADR-094)

L'application cherche elle-même une version plus récente et l'installe, à partir de la pré-version de debug de `main` (§1, « Release de debug »). Écrans validés le 2026-10-29 : `docs/previews/p31-mise-a-jour.html`, variante D pour le message, B pour la progression.

| Étape | Ce qui se passe | Code |
|---|---|---|
| Recherche | Au lancement (nouvelle activité, pas une rotation), sauf mode hors ligne ou « Rechercher au lancement » coupé ; ou « Rechercher » du dialogue « Mises à jour ». Requête anonyme `GET https://api.github.com/repos/JRRocks/ToolMap/releases?per_page=10` (limite de GitHub : 60 par heure et par adresse) | `UpdateController.check`, `GitHubReleases` (`:core`) |
| Choix | Pré-versions non brouillons dont l'étiquette commence par `debug-` ; version lue dans le nom `ToolMap-debug-<version>.apk` de leur fichier ; la plus haute, proposée si elle dépasse strictement la version installée (nombre par nombre) et servie en https | `GitHubReleases.latestApk`, `UpdateDecision.offer`, `AppVersion` |
| Message | Bandeau sur la carte 15 s (✕ pour le fermer) ; point rouge sur « Menu » tant qu'une version attend ; ligne « Mises à jour » sous « Plus » (« 0.4.131 disponible », « 0.4.126 · à jour, vérifiée à 10:42 ») ; dialogue « Mises à jour » (versions, interrupteur, « Mettre à jour » ou « Rechercher ») | `UpdateViews.kt`, `MainMenu.kt`, `MapScreen.kt` |
| Téléchargement | Dans l'application, écrit directement dans une session `PackageInstaller` ouverte avec la taille de l'APK (Android réserve la place d'abord : un téléphone plein échoue tout de suite, pas après le téléchargement ; aucune copie dans le cache, donc la place d'un seul APK) ; dialogue « Téléchargement de la X » (barre, Mo, « Annuler ») et notification du canal « Mises à jour de ToolMap » ; le dialogue fermé, le téléchargement continue ; une coupure : « Téléchargement interrompu », « Réessayer » | `UpdateController.startDownload`, `UpdateNotifications` |
| Installation | La session est remise à Android (permission `REQUEST_INSTALL_PACKAGES`) : Android demande la confirmation, et la première fois l'autorisation « Installer des applications inconnues » pour ToolMap ; sa réponse revient à `UpdateInstallReceiver`. | `UpdateController.install`, `UpdateInstallReceiver` |
| Refus | APK signé par une autre clé que l'installation (`STATUS_FAILURE_CONFLICT` ou `INCOMPATIBLE`) : dialogue « Installation refusée », qui dit de faire une sauvegarde, désinstaller, installer la nouvelle version, restaurer. C'est le cas, une fois, au passage à la clé de publication (§2) | `UpdateRefusedDialog` |

Un APK de debug de la CI garde le `versionCode` 1 (§3 : seul le workflow de release passe le numéro du run) ; Android remplace une installation de même `versionCode`, c'est donc le `versionName` qui décide. Les APK compilés en local ont aussi le `versionCode` 1 : une version locale plus récente que `main` ne se voit rien proposer.

Pour essayer sur l'émulateur sans vieille version : un APK de debug lit la version installée dans la préférence `installed_override` du fichier `updates` (`run-as fr.toolmap`, puis relancer l'application). Le journal d'événements note `update.check`, `update.downloaded`, `update.failed`, `update.install` et `update.unreachable`.
