# Idées pour plus tard

> **À ne lire que sur demande de l'utilisateur** (règle du 2026-10-24, `CLAUDE.md`). Rien ici n'est planifié : une idée
> retenue devient une tâche de `docs/PLAN.md` (ou d'un `docs/plan-*.md`) et quitte ce fichier.

## Partage de position

- **Outil SOS** distinct de l'Alerte (2026-10-24) : sonnerie et vibration forcées même en silencieux et en « Ne pas
  déranger » (accès demandé une fois à Android), plein écran sur l'écran verrouillé.
- **Prévenir la permanence d'un membre perdu** (2026-10-24) : notification quand un membre ne donne plus signe de vie
  depuis N minutes. Limite physique : en montagne, le téléphone ne capte pas toujours ; on travaille d'abord sur le
  contexte d'une station de ski.
- **Permanence d'écoute sur PC** (2026-10-24) : la fonction de la permanence (refuge) pourrait tourner sur un PC ; pour
  l'instant, application mobile seulement.
- **Transports alternatifs** (ancien P8.9) : derrière une interface `PeerTransport`, Nearby Connections (Bluetooth /
  Wi-Fi, sans internet), Meshtastic (LoRa).
- **Serveur pour la signalisation seulement** (ancien P13.6) : le serveur ne sert qu'à la signalisation, puis liaison
  WebRTC directe quand elle réussit.
- **Synchronisation des traces** entre membres d'un groupe (ancien « Bonus »).
- **Restes de P21** (phase close le 2026-10-24, détail dans `docs/specs/p21-corrections.md` et
  `docs/plan-partage.md` § 6) :
  - (c) « Pas de position GPS depuis N min », alertes de l'économiseur de données et de la batterie « restreinte »
    (aperçu complémentaire jamais validé) ;
  - (o) dire aux autres membres que la position est suspendue (champ dans `PING`, visible chez eux) ;
  - (t) « … est connecté(e) » affiché à chaque reconnexion ; temps relatif « il y a … » dans la langue du système ;
  - (j) 7 Mio téléchargés en 2 s au retour au premier plan, à analyser ; (k) repli sur la localisation réseau quand
    le GPS cale (contredit ADR-019), à décider ;
  - sans effet visible : âge des trames jugé à l'heure du serveur, minuteries sur `elapsedRealtime` ;
  - revoir sur le téléphone les corrections l à r et les alertes de 0.4.69, et (g) pendant une heure de partage ;
  - nouveau test en direct : rebrancher `:debugkit` (ADR-072) ; écran gardé allumé en arrière-plan (P21.6, fenêtre
    `FLAG_KEEP_SCREEN_ON`) et petits points du debug (`p21-corrections.md` § Petits points).

## Cartes et calques

- **Gomme de couleur et comparaison de deux cartes** (reste de l'ancien P11.5 ; le mode de fusion est devenu P26).
- **Emprise dessinée sur la carte** dans l'assistant d'ajout de carte (reporté de P4.4).
- **Reprojection** des sources en CRS spécifique (Lambert 93, Suisse LV95…) (ancien « Bonus »).
- **Import** GeoTIFF, KMZ calibrés, images à calibrer (ancien « Bonus »).
- **Impression dans un CRS projeté** (Lambert 93 / UTM) avec grille droite (ancien « Bonus »).

## Performance

- **Mémoire des tuiles multi-zoom composées** et lecture parallèle de leurs sources (ancien P15.9, M5) : seulement si
  les scénarios T1 / T2 montrent que les tuiles composées coûtent cher.
- **GPS de la boîte d'altitude, alertes adaptatives** (PF5, PF6 : non mesurables sur émulateur), APK `arm64` de debug
  (PF7), Réglages découpés (PF8) (ancien P15.10) : après les mesures de batterie sur téléphone (`performance.md` §7).

## Divers

- **Choix de la langue** et traduction anglaise de l'interface (reporté de P9.7, ancien « Bonus ») : interface en
  français seulement pour l'instant.
- **Detekt** (reporté de P0.4, ADR-008) : à rouvrir quand detekt 2.0 sera stable.
