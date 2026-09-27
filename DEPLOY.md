# Déployer le serveur relais sur Render (gratuit)

Ce guide déploie `server/` sur [Render](https://render.com) avec une adresse Internet fixe
(`https://....onrender.com`), pour que l'application Android fonctionne en 4G/5G, pas seulement
sur le Wi-Fi local. Le dépôt contient déjà tout ce qu'il faut (`Dockerfile`, `render.yaml`).

## Étapes

1. Va sur https://render.com et crée un compte (le plus simple : "Sign up with GitHub").
2. Une fois connecté, clique sur **"New +"** en haut à droite, puis **"Blueprint"**.
3. Choisis le dépôt **`beep-pouk/sites-clients`** (autorise Render à y accéder si demandé).
4. Render détecte automatiquement le fichier `render.yaml` à la racine du dépôt et propose de
   créer un service nommé `securechat-relay`. **Vérifie bien que la branche sélectionnée est
   `claude/secure-messaging-android-app-9vk4bc`** (pas `main`, qui ne contient pas encore le code).
5. Clique sur **"Apply"** / **"Create"**. Render construit l'image Docker et démarre le service —
   ça prend quelques minutes la première fois.
6. Une fois le déploiement terminé (statut "Live"), Render affiche une URL en haut de la page du
   service, du type :
   ```
   https://securechat-relay.onrender.com
   ```
   **C'est cette adresse qu'il faut donner à l'application** (voir plus bas).

## Configurer l'application avec cette adresse

Dans l'app SecureChat installée sur le téléphone : **Onglet Réglages → "Server address"**, colle
l'URL Render (sans `/` à la fin), clique **Save**, puis **ferme complètement l'application et
rouvre-la** (le changement ne prend effet qu'au redémarrage).

## À savoir (offre gratuite Render)

- Le service gratuit se met en veille après ~15 minutes sans trafic ; la première requête après
  une veille peut prendre 30 à 60 secondes le temps qu'il se réveille (les suivantes sont rapides).
- Le stockage est éphémère sur l'offre gratuite : un redémarrage/redéploiement du service efface
  la base de données du serveur (comptes enregistrés, clés publiques, messages en attente). Les
  conversations déjà reçues restent sur les téléphones (base locale chiffrée), mais il faudra que
  chacun relance l'app pour se réenregistrer si le serveur a redémarré entre-temps.
- Chaque nouveau `git push` sur la branche configurée redéploie automatiquement le service.
