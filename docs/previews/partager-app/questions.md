# Partager l'app : à la place de « Quitter » dans le menu

**Aperçu validé le 2026-11-19** (« Validation du 2026-11-19 », en bas) : peut être codé.

Demande de l'utilisateur du 2026-11-19, mot pour mot :

> Retire le bouton quitter et remplace le par un bouton "Partager l'app" Qui ouvre un onglet avec le liens vers le téléchargement direct de la dernière version En lien comme, https://github.com/JRRocks/ToolMap/releases/download/android-0.4.235/ToolMap-release-0.4.235.apk Et en QR code (avec le logo de toolmap au centre du qrcode) Le lien en text sous le QR code, avec un petit bouton partage pour envoyer via d'autre app de messagerie

Aperçus : `index.html` (à ouvrir dans un navigateur ; `#actuel`, `#menu`, `#a`, `#b` ou `#c` à la fin de l'adresse n'en montrent qu'un) et `img/`. Le fond de carte (`carte.svg`) et les coordonnées sont inventés. Le QR code des images est un vrai QR code (zxing, niveau de correction H, logo au centre) : il se lit en pleine taille et à moitié (décodé par zxing le 2026-11-19).

Pour chaque question, la réponse recommandée est la première (a).

## Aujourd'hui (image 01-actuel)

En haut du menu, « Quitter » ferme ToolMap (ADR-053 : l'écran se ferme, rien ne s'arrête). Le bouton Retour de la carte fait de même, avec la fenêtre « Quitter ToolMap ? » quand le réglage la demande.

## Sans question

- « Quitter » disparaît du menu. Pour fermer l'app, le bouton Retour de la carte marche toujours.
- Le lien mène à la dernière version publiée (APK de release), pas à la version de debug du téléphone qui le montre.
- Le logo de ToolMap est au centre du QR code ; le niveau de correction H le garde lisible sous le logo.
- Calendrier : codé après P39.15, essayé avec l'APK de debug ; il n'arrive dans une version publiée qu'au passage dans `main`.

## Les questions

**1. Ce qui s'ouvre quand on touche « Partager l'app »**
- **a (recommandé) : une page entière, comme « Réglages… »** : c'est là que le QR code est le plus grand (image 03-A-page).
- b : un panneau sur la carte, à la place du menu (image 04-B-panneau).
- c : une fenêtre au milieu de l'écran (image 05-C-fenetre).

**2. Le lien**
- **a (recommandé) : un lien fixe qui mène toujours à la dernière version publiée**, `https://github.com/JRRocks/ToolMap/releases/latest/download/ToolMap.apk`. Le QR code marche alors sans réseau et ne se périme jamais. Pour qu'il marche tout de suite, une copie du fichier est ajoutée à la version 0.4.235 déjà publiée sur GitHub ; rien ne change pour les téléphones.
- b : le lien de la version, comme dans l'exemple de la demande. Il faut du réseau, et le lien ne marche plus dès que deux versions plus récentes sont publiées.

**3. Le message envoyé par le bouton de partage**
- **a (recommandé) : « ToolMap pour Android : » suivi du lien.**
- b : le lien seul.

## Ce que montre chaque image

| Image | Ce qu'elle montre |
|---|---|
| 01-actuel | Le menu d'aujourd'hui (version 0.4.253), « Quitter » en haut. |
| 02-menu | Le menu proposé : « Partager l'app », icône de partage et flèche, à la place de « Quitter » (le cadre orange en pointillés ne sert qu'à l'aperçu : il montre ce qui change). |
| 03-A-page | 1 a : la page « Partager l'app » (← revient au menu) : « À scanner avec l'appareil photo de l'autre téléphone », le QR code avec le logo, le lien dans un cadre et le petit bouton rond de partage. |
| 04-B-panneau | 1 b : le même contenu dans un panneau sur la carte, à la place du menu. |
| 05-C-fenetre | 1 c : le même contenu dans une fenêtre, avec « Fermer » ; la ligne du menu n'a pas de flèche. |

## Validation du 2026-11-19

Réponse de l'utilisateur relayée par la session principale, mot pour mot :

> Tout en a

- **1 a** : une page entière (image 03-A-page), ouverte par la ligne du menu de l'image 02-menu.
- **2 a** : le lien fixe `https://github.com/JRRocks/ToolMap/releases/latest/download/ToolMap.apk`. La CI joint à chaque release `android-<version>` une copie de l'APK de release nommée `ToolMap.apk`.
- **3 a** : le bouton de partage envoie « ToolMap pour Android : » suivi du lien.
- Aperçu validé : 02-menu et 03-A-page.

Fait le 2026-11-19 par la session principale, avec l'accord donné par « 2 a » : une copie `ToolMap.apk` de `ToolMap-release-0.4.235.apk` (82 378 366 octets, mêmes octets) est jointe à la release `android-0.4.235`, la plus récente. Le lien fixe répond (HTTP 200 après deux redirections) et donne ce même fichier. Le contrôle des mises à jour (`GitHubReleases.latestApk` ne lit que `ToolMap-(release|debug)-<version>.apk`) et `scripts/drive-mirror.ps1` (motif `ToolMap-*-*.apk`) ne lisent pas cette copie.
