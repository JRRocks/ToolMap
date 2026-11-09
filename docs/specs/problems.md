# Aucune erreur silencieuse : journal et tickets GitHub (P36)

> Décision : ADR-116. Code : `core/.../problem/` (`Problems`, `catchReported`, `catchExpected`, `ProblemReport`,
> `ProblemScrubber`, `ProblemFingerprint`), `app/.../problem/` (`AppProblems`, `ProblemQueue`, `ProblemUpload`),
> `p2p/.../FirebaseReports.kt`, `app/.../crash/CrashReporter.kt` ; règles `firebase/database.rules.json` (nœud
> `reports`) ; workflow `.github/workflows/problems.yml` et script `tools/problems/`. Vérification :
> `SilentFailureTest` (`./gradlew :core:test`), `node --test tools/problems/reports.test.mjs`.

## 1. Règle (demande de l'utilisateur, 2026-11-08)

- **Pour l'utilisateur**, une erreur peut rester silencieuse : l'application garde la solution de repli qu'elle avait
  (valeur par défaut, message déjà prévu, écran de rapport de plantage existant). Aucun écran ni texte nouveau.
- **Pour la personne qui débogue**, aucune erreur attrapée n'est silencieuse : chacune est écrite dans logcat (avec sa
  pile, étiquette `ToolMap`) et dans le journal d'événements (`problem`, `problem.expected`, `docs/specs/event-log.md`).
- **Tout bogue, plantage ou événement inattendu devient un ticket GitHub** dans `JRRocks/ToolMap`, étiquette
  `rapport-auto`, un ticket par bogue (même empreinte), les nouveaux cas ajoutés en commentaire.

## 2. Dans le code

| Situation | Écrire | Effet |
|---|---|---|
| Échec prévu et traité : pas de réseau, serveur qui refuse, fichier choisi par l'utilisateur invalide, donnée d'un pair mal formée, permission refusée, appli externe absente, nettoyage qui échoue | `Problems.expected("zone.action", e)` ou `catchExpected("zone.action", IOException::class, …) { … }` | logcat (avertissement) et journal `problem.expected`, pas de ticket |
| Échec qui ne devrait pas arriver : bogue, fichier d'état écrit par l'appli illisible, appel de MapLibre, Room ou Compose qui échoue, attrape-tout autour de notre propre logique | `Problems.report("zone.action", e)` ou `catchReported("zone.action") { … }` | logcat (erreur), journal `problem`, **ticket** |
| État jugé impossible, sans exception | `Problems.unexpected("zone.action", "message")` | idem, la pile dit où |
| Exception non attrapée (plantage Java ou Kotlin) | rien : `CrashReporter` | rapport à l'écran (existant) et **ticket** |
| Plantage natif, blocage (ANR) | rien : lus au lancement suivant (Android 11+) | **ticket** |

- `catchReported` et `catchExpected` remplacent `runCatching` : même `Result`, mêmes `.getOrNull()` /
  `.getOrElse { }`. `catchExpected` traite comme un bogue (ticket) toute exception d'un autre type que ceux listés. Les
  deux **relancent l'annulation d'une coroutine** (`CancellationException`, dont `TimeoutCancellationException`) au
  lieu de l'avaler, ce que faisait `runCatching` : un délai de `withTimeout` se traite par `withTimeoutOrNull` ou par un
  `catch (e: TimeoutCancellationException)` explicite.
- Le **lieu** (`"tiles.decode"`) est un littéral en minuscules et points, jamais construit à partir d'une donnée : il
  nomme le problème dans les journaux et entre dans l'empreinte du ticket.
- Les paires de **contexte** (`"layer" to id`) vont aux journaux du téléphone ; le ticket ne garde que les nombres, les
  booléens et les valeurs d'énumération. Jamais de clé d'API, de jeton ni d'adresse avec clé dans le contexte.
- Les échecs du journal d'événements lui-même ont des lieux `eventlog.*` : ils vont à logcat et en ticket, pas dans le
  journal (pas de boucle). Une défaillance du récepteur de problèmes (`Problems.sink`) finit sur la sortie d'erreur
  (logcat « System.err »).
- **Vérification automatique** (`SilentFailureTest`, dans `./gradlew check`) : aucun `runCatching` ; chaque
  `catch (e: T)` relance, transmet `e` comme valeur (`Result.failure(e)`, `onError(e)` ; un appel à `Log` ne compte
  pas) ou appelle `Problems` ; un attrape-tout (`Exception`, `Throwable`, `RuntimeException`, `Error`) relance,
  transmet ou appelle `Problems.report` / `unexpected` ; chaque `.catch { }` d'un Flow appelle `Problems` ou relance ;
  le lieu est un littéral.

## 3. Sur le téléphone

- **Récepteur** (`AppProblems`, installé dans `ToolMapApp.attachBaseContext`, processus principal) : un même problème
  (lieu + type) est écrit au plus une fois par 10 s dans logcat et le journal ; la ligne suivante dit combien ont été
  sautés (`skipped`).
- **File d'attente** (`ProblemQueue`) : `files/problems/queue/<empreinte>.json`, un fichier par bogue ; un bogue
  rencontré mille fois est un seul rapport avec son compte (ajouté au fichier au plus une fois par minute). 40
  fichiers au plus, rien de plus vieux que 30 jours. Un plantage Java est écrit à l'instant, avant l'écran de rapport.
- **Envoi** (`ProblemUpload`, travail WorkManager unique, réseau requis, 20 s après le premier rapport, nouvel essai
  espacé en cas d'échec) : chaque rapport est écrit sous `reports/<empreinte>-<10 caractères au hasard>` du **serveur de
  coordination intégré à l'APK** (secrets de la CI, `docs/specs/firebase-relay.md` § 8), avec une identité anonyme
  propre (`files/problems/firebase.token`), puis effacé de la file. Rien ne part sans serveur intégré (build local), ni
  pendant les tests JVM (Robolectric).

## 4. Ce que contient un rapport (dépôt public)

`ProblemReport` (format 1, JSON de 16 000 caractères au plus) : empreinte, genre (`ERROR`, `CRASH`, `NATIVE_CRASH`,
`ANR`), lieu, classe de la cause première, message **nettoyé**, pile d'appels (40 appels par exception, 6 causes,
9 000 caractères, messages nettoyés), nom du fil nettoyé, version et commit de l'APK, version d'Android, modèle du
téléphone, première ABI, compte, premier et dernier instant.

- **Nettoyage** (`ProblemScrubber`) : adresses (URL, URI, e-mail) → `<url>`, `<e-mail>` ; chemins de fichiers jusqu'au
  prochain deux-points ou guillemet → `<chemin>` ; texte entre guillemets → `"…"` ; nombres décimaux (coordonnées) et
  longs nombres → `<n>` ; identifiants hexadécimaux de 12 caractères et plus, jetons (lettres et chiffres mêlés, 20
  caractères et plus) → `<id>` ; 240 caractères au plus.
- **Plantage natif** : du tombstone ne restent que les chemins des bibliothèques (`.so`, répertoire d'installation
  aléatoire remplacé par `…`) et les noms de fonctions ; jamais le message d'abandon ni les chaînes de la mémoire.
- **ANR** : les appels du fil principal (`at …`, `- waiting to lock …`) de la trace d'Android.
- Jamais : journal d'événements, logcat, coordonnées, noms de traces ou de cartes, invitation, contenu des fichiers.
- **Empreinte** (`ProblemFingerprint`, 16 hexadécimaux) : SHA-256 du genre (les plantages Java et les erreurs
  attrapées partagent le leur), du lieu, de la classe de la cause et des 6 premiers appels du code de l'appli
  (`fr.toolmap.`) sans numéro de ligne : le même bogue garde son ticket d'une version à l'autre.

## 5. Côté serveur et GitHub

- **Règles** (`firebase/database.rules.json`, nœud `reports`) : un utilisateur connecté (anonyme compris) peut
  **créer** un rapport (identifiant `<16 hex>-<10 [0-9a-z]>`, champs `v` = 1, `j` texte ≤ 16 000, `t` = heure du
  serveur), jamais le lire, le modifier ni l'effacer ; seul le compte lecteur `reports@toolmap.invalid` (connexion par
  mot de passe) lit et efface.
- **Workflow `problems.yml`** (toutes les heures à la minute 23, et à la demande) : se connecte en lecteur, lit
  `reports`, refuse ce qui ne ressemble pas exactement à un rapport de l'appli, regroupe par empreinte puis, pour
  chaque bogue :
  - pas de ticket → **ouvre** un ticket (au plus 10 par passage, le reste attend le suivant), étiquettes `rapport-auto`
    et `plantage` / `plantage-natif` / `anr` / `erreur-inattendue` ;
  - ticket ouvert → **commentaire** « Vu de nouveau » (compte, versions, appareils ; au plus 20 par passage) ;
  - ticket fermé et version plus récente que toutes celles vues → **rouvert** avec « Réapparu en … » ; sinon le
    rapport (version ancienne, déjà corrigée) est simplement retiré. Seule compte une version que la CI a publiée
    (au plus la plus haute release `android-<version>`) : un faux rapport « 999.0.0 » ne rouvre rien et ne relève
    pas la version notée dans le ticket (revue du 2026-11-09) ; un APK compilé en local et pas encore poussé ne
    rouvre donc pas un ticket fermé ;
  - puis les rapports traités sont effacés du serveur.
  Tout le texte venu du téléphone est rendu inerte (blocs de code, pas de mention `@`, pas de lien).
- **Limites assumées** : n'importe qui ayant la clé web du projet (publique par nature) peut déposer de faux rapports ;
  le script n'accepte que la forme exacte d'un rapport et plafonne les tickets créés par passage. La réouverture
  automatique peut se tromper si un téléphone garde une version antérieure au correctif mais jamais vue avant : le
  commentaire le montre, refermer suffit.

### Mise en place (à faire une fois par le propriétaire du serveur)

1. Console Firebase › **Authentication › Méthode de connexion** : activer **Adresse e-mail/Mot de passe** (l'accès
   anonyme reste activé).
2. **Authentication › Utilisateurs › Ajouter un utilisateur** : `reports@toolmap.invalid` et un mot de passe long tiré
   au hasard (l'adresse n'existe pas : aucun e-mail n'est envoyé).
3. **Realtime Database › Règles** : coller `firebase/database.rules.json` et **Publier** (ajoute le nœud `reports`).
4. GitHub › dépôt `JRRocks/ToolMap` › **Settings › Secrets and variables › Actions** : secret
   `TOOLMAP_REPORTS_PASSWORD` = le mot de passe de l'étape 2 (`TOOLMAP_FIREBASE_DB_URL` et `TOOLMAP_FIREBASE_API_KEY`
   existent déjà).
5. Le workflow planifié ne tourne que depuis la branche par défaut (`main`) : il démarre quand ce travail y arrive.
   Lancement à la main : onglet **Actions › Problems › Run workflow**.

Tant que l'étape 3 n'est pas faite, l'envoi est refusé (`problems.upload.refused`, journal) et les rapports attendent
sur le téléphone (30 jours au plus).

## 6. Vérification

- JVM : `ProblemsTest` (classement, annulation relancée, récepteur défaillant sans boucle), `ProblemReportTest`
  (nettoyage, empreinte, natif, ANR, aller-retour JSON), `SilentFailureTest` (le vérificateur et tout le code).
- Node : `tools/problems/reports.test.mjs` (validation, regroupement, texte inerte, décisions), lancé par la CI.
- **Non vérifié dans la session du 2026-11-08** : l'envoi réel vers Firebase et l'ouverture d'un ticket (règles à
  publier, compte lecteur et secret à créer, workflow sur `main`).
