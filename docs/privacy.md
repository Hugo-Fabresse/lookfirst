# LookFirst privacy policy / Politique de confidentialité

Same text as the in-app screen (`app/src/main/res/values/privacy.xml` and `values-fr/privacy.xml`). `PrivacyPolicyScreenTest` fails if this file and the app strings diverge: update both together, and the date line.

Même texte que l'écran de l'application ; le test `PrivacyPolicyScreenTest` échoue si ce fichier et les chaînes de l'appli divergent.

# Français

## Politique de confidentialité

Version du 18 octobre 2026

LookFirst analyse les QR codes sur votre téléphone et n'ouvre rien à votre place. Cette page dit ce que l'application fait de vos données, et ce qu'elle ne fait pas.

### Ce que LookFirst ne fait pas

Pas de compte. Pas de collecte de données. Pas d'historique de vos scans. Pas de publicité. Pas d'outil de mesure d'audience ni de service tiers intégré à l'application. Les données de l'application ne sont pas sauvegardées dans le cloud : la sauvegarde automatique d'Android est désactivée pour LookFirst.

### Où se fait l'analyse

Sur votre téléphone. Le contenu d'un QR code n'est jamais envoyé, sauf si vous lancez vous-même l'une des deux vérifications facultatives décrites ci-dessous, après un avertissement.

### Qui voit quoi

**Mise à jour des listes.** Chaque jour si la mise à jour automatique est activée, et quand vous touchez « Mettre à jour maintenant », l'application télécharge une liste de sites signalés publiée par le CERT Polska (Pologne). Ce service voit l'adresse IP de votre téléphone et un nom d'application constant, le même pour tous les utilisateurs. Il ne reçoit rien sur vos scans.

**Suivi des redirections (facultatif).** Seulement si vous le lancez, après un avertissement. L'application suit le lien de redirection en redirection. Chaque site de la chaîne voit l'adresse IP de votre téléphone et une requête minimale (HEAD, ou GET d'un seul octet) pour l'adresse demandée, sans cookie ni Referer. Il sait donc que son QR code a été scanné.

**Âge du domaine (facultatif).** Seulement si vous le lancez, après un avertissement. Le registre officiel du domaine (le serveur RDAP désigné par la liste de l'IANA embarquée dans l'application) voit l'adresse IP de votre téléphone et le seul nom de domaine, sans chemin ni paramètres.

### Presse-papiers

Copier est toujours votre choix. Pour un secret (mot de passe Wi-Fi, clé d'authentification otpauth), l'application demande une confirmation, marque la copie comme sensible et l'efface quand vous revenez dans LookFirst. Limite connue : avant Android 13, le marquage dépend du clavier, qui peut afficher ou garder la copie ; avant Android 9, la copie est remplacée par un contenu vide au lieu d'être effacée. Une application ou un clavier au premier plan peut lire le presse-papiers.

### Permissions

Caméra : scanner un QR code, demandée au premier affichage de la caméra (l'import d'une image fonctionne sans). Internet : télécharger les listes et faire les deux vérifications facultatives. État du réseau : savoir si le téléphone est hors connexion. Éviter la superposition d'écrans : empêcher une autre application de recouvrir les boutons. Empêcher la mise en veille : exigée par la bibliothèque WorkManager pendant la mise à jour des listes. Aucune autre permission : ni contacts, ni position, ni micro, ni SMS.

### Données gardées sur le téléphone

Les listes téléchargées et vos réglages sont dans le stockage privé de l'application, inaccessible aux autres applications. Ils sont supprimés quand vous désinstallez LookFirst.

### Contact

Pour toute question, passez par la page de l'application, là où vous l'avez obtenue.

---

# English

## Privacy policy

Version of 18 October 2026

LookFirst analyses QR codes on your phone and opens nothing for you. This page says what the app does with your data, and what it does not do.

### What LookFirst does not do

No account. No data collection. No history of your scans. No advertising. No analytics and no third-party service built into the app. The app's data is not backed up to the cloud: Android's automatic backup is turned off for LookFirst.

### Where the analysis happens

On your phone. The content of a QR code is never sent, unless you yourself start one of the two optional checks described below, after a warning.

### Who sees what

**List update.** Every day if automatic update is on, and when you tap "Update now", the app downloads a list of reported sites published by CERT Polska (Poland). That service sees your phone's IP address and a constant app name, the same for every user. It receives nothing about your scans.

**Redirect tracing (optional).** Only if you start it, after a warning. The app follows the link from redirect to redirect. Each site in the chain sees your phone's IP address and a minimal request (HEAD, or a one-byte GET) for the requested address, with no cookie and no Referer. It therefore knows that its QR code was scanned.

**Domain age (optional).** Only if you start it, after a warning. The domain's official registry (the RDAP server named by the IANA list embedded in the app) sees your phone's IP address and the domain name only, with no path and no parameters.

### Clipboard

Copying is always your choice. For a secret (Wi-Fi password, otpauth authentication key), the app asks for confirmation, marks the copy as sensitive and clears it when you come back to LookFirst. Known limit: before Android 13, the marking depends on the keyboard, which may show or keep the copy; before Android 9, the copy is replaced by an empty one instead of being cleared. An app or keyboard in the foreground can read the clipboard.

### Permissions

Camera: scan a QR code, asked when the camera is first shown (importing an image works without it). Internet: download the lists and run the two optional checks. Network state: know whether the phone is offline. Hide overlay windows: stop another app from covering the buttons. Keep awake: required by the WorkManager library during a list update. No other permission: no contacts, no location, no microphone, no SMS.

### Data kept on the phone

The downloaded lists and your settings are in the app's private storage, which other apps cannot reach. They are deleted when you uninstall LookFirst.

### Contact

For any question, use the app's page, where you got it.
