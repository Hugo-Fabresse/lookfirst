# LookFirst UX design

Status: accepted (LF-7). Open questions settled on the cautious side, see section 10 (decisions D14 to D18).
Scope: flows, screens, states, texts (FR/EN), accessibility, exclusions.
Sources: `architecture.md` (flow, verdict model, opening content, immutability, network), `threat-model.md` (T-CNT, T-SCH-11, T-OTP, T-UI), `DECISIONS.md` (D3, D9, D12).

## 1. Principles

1. The user decides. Nothing opens, connects, dials or sends after a scan. Only an explicit tap does.
2. One decision at a time: Open / Copy / Cancel. The three actions are always in the same place.
3. The verdict explains in one sentence, then details the signals.
4. The real address is shown before any action (normalised form, host emphasised, punycode if IDN).
5. A false sense of security is worse than a useless warning. We never write "sûr" / "safe". The best level reads "Aucun signal de risque détecté" / "No risk signal detected" and always carries the "guarantees nothing" line.
6. Friction proportional to risk, with no dark pattern: no countdown, no hidden Cancel, no guilt wording. Buttons never move between levels.
7. Meaning is never carried by colour alone: colour + icon + label + position.
8. No jargon without a one-line explanation (punycode, RDAP, IDN, redirect).

## 2. Navigation map

```
First launch --> Scan (camera) <--> Settings --> About & privacy
                    |   ^
        pick image  |   | Cancel / Copy done / action done
                    v   |
               Result / Verdict  --(optional)--> Warning sheet (redirects | domain age)
                    |
                    +--(Open)--> [Confirm dialog, DANGEROUS only] --> external app
```

Single activity, Navigation Compose. Scan is the home screen. Back from Result = Cancel (never an action).

## 3. Flows

### 3.1 First launch (once)
One screen, no carousel, no account. Three facts, one button.
- Nothing opens by itself; you always choose.
- Everything is analysed on your phone; scanned addresses are not sent anywhere.
- Reputation lists are downloaded in the background (toggle, on by default, can be changed later in Settings). The download server only sees your IP.
Button: "Commencer" / "Get started". The camera permission is NOT requested here; it is requested when the user first opens the camera view, with a one-line rationale shown before the system dialog.

### 3.2 Camera scan
Scan opens with the viewfinder. A decoded QR (first stable read) pauses the camera and goes to Result/Verdict. Haptic tick only, no sound. The camera stays paused while a result is displayed (verdict immutability); on Cancel or after an action the camera resumes.

### 3.3 Image scan
"Image" button opens the system Photo Picker (no storage permission). Decoding runs on the picked image. Success goes to Result/Verdict. Failures: "no QR found" or "unreadable" (section 5.4). Several QR codes in one image: the first decoded one is used and the line "Plusieurs QR codes dans l'image : seul le premier est affiché." is shown (D14).

### 3.4 Result (epic "Scanner et décoder": content + Copy/Cancel)
Same layout as the Verdict screen with the verdict slot showing a neutral "not analysed" banner (grey, icon "?", text "Contenu décodé, pas encore analysé"). It must never look like NO_RISK_SIGNAL. Actions: Copy, Cancel. Replaced by the real banner when the verdict epic lands; no layout change.

### 3.5 Verdict (epic "Analyser et expliquer")
Banner (level) + one-sentence reason + emphasised host + 3 actions, all visible without scrolling. Details (all signals, exact content, list age, optional checks) below or expandable.

### 3.6 Actions
- Copy: copies the exact decoded text (not the normalised URL, so nothing is altered). Snackbar "Copié" / "Copied". For secrets (otpauth, Wi-Fi password): confirmation dialog first, then the sensitive flag; see 4.6.
- Open: depends on type (table 4.5). DANGEROUS adds one confirmation dialog (D17).
- Cancel: closes, resumes the camera. No confirmation.
- Optional checks (http/https only): see 3.7.

### 3.7 Optional network checks
Two text buttons in a "Vérifications facultatives" section: "Suivre les redirections" and "Vérifier l'âge du domaine". Each opens a bottom sheet with a warning and two buttons (Continuer / Annuler). The result is appended as new reasons and the level may go up, never down, and the user still has to choose Open/Copy/Cancel. Offline: both buttons disabled with the note "Hors connexion". Loading: spinner in the sheet, 5 s cap, Cancel always available. Failure: "Vérification impossible (délai dépassé ou réseau indisponible). Le verdict reste celui de l'analyse locale."

### 3.8 Settings
Reached from the Scan top bar (gear icon).
- Reputation lists: version, date and age ("mises à jour il y a 3 jours"), "Mettre à jour maintenant", toggle "Mise à jour automatique" (on by default, unmetered network only), result of the last attempt.
- About & privacy: what is local, what uses the network and when, who sees what (table 6), licences, app version, the "no guarantee" statement.
- Language follows the system (no in-app switch at MVP).
No history setting at MVP (section 9).

## 4. Verdict presentation

### 4.1 Levels

| Level | Icon (shape differs) | Label FR | Label EN | Colour role | Open button style |
|---|---|---|---|---|---|
| DANGEROUS | octagon with X | Danger | Dangerous | red container, dark red text | outlined, label "Ouvrir quand même"; confirmation dialog |
| SUSPICIOUS | triangle with ! | Suspect | Suspicious | orange container | outlined, label "Ouvrir quand même" |
| CAUTION | diamond with ! | À vérifier | Check carefully | amber container | filled secondary, label "Ouvrir" |
| NO_RISK_SIGNAL | eye (not a check, not a shield, not a padlock) | Aucun signal de risque détecté | No risk signal detected | neutral blue-grey container, never green | filled, label "Ouvrir" |

Rules:
- Banner = icon (>= 24 dp) + label as text + container colour. Text on the banner never relies on colour alone, and the icons differ by silhouette so they can be told apart in greyscale.
- No green, no check mark, no padlock anywhere in the app.
- For DANGEROUS and SUSPICIOUS, Cancel is the filled primary button and Open is the outlined secondary one (same position). For the others Open is primary. Order is always, left to right: Cancel, Copy, Open (see mock-ups).
- NO_RISK_SIGNAL always shows under the banner: "Cela ne garantit rien : les listes ne connaissent que les menaces déjà signalées." / "This guarantees nothing: lists only know threats that were already reported."
- Unknown or unparsable content is CAUTION, never NO_RISK_SIGNAL.

### 4.2 Information order (top to bottom)
1. Level banner (icon + label).
2. One sentence: the most severe reason, as a full sentence. For NO_RISK_SIGNAL: the "guarantees nothing" sentence.
3. "Ce lien ouvrira" / "This link will open": the host, large, bold, alone on its line. If IDN: second line "Forme technique : xn--pypal-4ve.com" with a "?" explaining sentence (4.4). Under it, smaller: scheme and port only if not default (`http`, `:8443`).
4. Reasons: the 3 most severe as sentences with the evidence fragment highlighted (bold plus underline, not colour only). If more: "Voir les N autres signaux".
5. Exact content: collapsed to 2 lines, "Afficher tout le contenu" expander, escaped (4.3).
6. Lists age line: "Listes de réputation : mises à jour il y a 3 jours." If older than 14 days or absent, it also becomes a CAUTION reason (D16).
7. Optional checks (http/https only).
8. Actions, pinned at the bottom (sticky bar), outside the scrollable area.

Fit rule: items 1, 2, 3 and 8 are fixed and measure about 330 dp on a 360x640 dp screen at font scale 1.0; items 4 to 7 scroll in the middle only if they do not fit. At font scale >= 150% the middle part scrolls and the action bar stays pinned; the three actions then stack vertically (full-width, 48 dp each) if labels do not fit on one line.

### 4.3 Exact content and invisible characters
- Rendered in a monospace, LTR-isolated container (so a bidi override cannot reorder the display), selectable text disabled for the visual line (copy goes through Copy).
- Control, format and bidi characters are replaced by a visible token with a distinct background and the token is spoken by TalkBack as text: `<U+202E>`, `<U+200B>`, `<U+0000>`, `<LF>`.
- Lookalike (non-ASCII) letters in a host are marked with a dotted underline and listed in the reason ("le « а » est cyrillique (U+0430)").
- Long content (> 120 characters): collapsed to 2 lines plus "Afficher tout le contenu"; expanded view scrolls inside the container, cap displayed at 2000 characters then "… (N caractères de plus non affichés)". The bound on analysed size is handled by the decoder (see 5.5).
- Secrets (otpauth secret, Wi-Fi password): masked "••••••••" with an "Afficher" toggle, never expanded by default.

### 4.4 Domain, IDN and punycode
- The host is the only large text. Everything else (path, query) lives in the collapsed content.
- If the host contains non-ASCII characters: show the Unicode form as typed in the QR, then "Forme technique : xn--…" and the one-line explanation: "Les navigateurs utiliseront cette forme technique. Si elle ne ressemble pas au nom attendu, c'est un signal de risque."
- If the URL has a userinfo ("https://paypal.com@evil.example"): the host shown is the REAL host (`evil.example`), with the reason explaining the `@`.
- Registrable domain emphasis: the registrable part (from the Public Suffix List) in bold, subdomains in regular weight, so `paypal.com.evil.example` shows `evil.example` in bold.

### 4.5 Buttons by content type

| Type | Cancel | Copy | Open (label) | Notes |
|---|---|---|---|---|
| http / https URL | yes | yes | yes ("Ouvrir dans le navigateur") | via default browser; if a non-browser app would capture the link, a reason says so |
| URL with DANGEROUS level | yes | yes | yes ("Ouvrir quand même"), confirmation dialog | Cancel primary |
| `javascript:` `intent:` `file:` `content:` `data:` | yes | yes | no | text: "Ce type de contenu ne peut pas être ouvert depuis LookFirst." |
| App deep link / unknown scheme (D12) | yes | yes | no | same text, plus "Copier seulement" |
| `tel:` | yes | yes | yes ("Ouvrir le composeur") | dial only, never call |
| `sms:` / `smsto:` | yes | yes | yes ("Écrire le SMS") | message is prefilled, not sent |
| `mailto:` | yes | yes | yes ("Écrire l'e-mail") | not sent |
| Wi-Fi | yes | yes (password: confirmation) | API 30+: "Ajouter ce réseau" (system confirmation); API < 30: "Ouvrir les réglages Wi-Fi" | credentials shown (password masked + Afficher); never connects |
| `otpauth://` | yes | yes (confirmation + sensitive flag) | no | issuer and account shown, secret masked |
| SEPA / payment (EPC) | yes | yes (IBAN, amount, beneficiary as separate fields) | no (D15) | consistency reasons |
| Crypto address | yes | yes | no (D15) | address shown in groups of 4 |
| Plain text / unknown | yes | yes | no | CAUTION by default |
| Unreadable / too big | yes | no | no | error state, see 5.5 |

### 4.6 Confirmation dialogs
- Open on DANGEROUS: title "Ouvrir quand même ?", body "LookFirst a trouvé des signaux de danger sérieux. Si vous continuez, c'est à vos risques.", host repeated in bold, buttons "Annuler" (default focus, filled) and "Ouvrir quand même" (text). No delay, no checkbox.
- Copy of a secret: title "Copier le secret ?", body "Les autres applications ouvertes et le clavier peuvent lire le presse-papiers. LookFirst l'efface quand vous revenez, si votre version d'Android le permet." Buttons "Annuler" / "Copier".

## 5. Screens and mock-ups

Width 38 characters, about a 360 dp phone. `[ ]` = button, `(i)` = info icon. Mock-ups show structure, not final pixels.

### 5.1 First launch

```
+--------------------------------------+
|                                      |
|              LookFirst               |
|     Regardez avant d'ouvrir.         |
|                                      |
|  [>|] Rien ne s'ouvre tout seul.     |
|       Vous choisissez toujours.      |
|                                      |
|  [#]  Tout est analysé sur votre     |
|       téléphone. Les adresses        |
|       scannées ne sont envoyées      |
|       nulle part.                    |
|                                      |
|  [~]  Les listes de sites signalés   |
|       sont téléchargées en tâche     |
|       de fond.                       |
|       Mise à jour auto     [ON  o]   |
|       (modifiable dans Réglages)     |
|                                      |
|  Aucun scan ne garantit rien : un    |
|  site nouveau peut ne figurer dans   |
|  aucune liste.                       |
|                                      |
|  +--------------------------------+  |
|  |           Commencer            |  |
|  +--------------------------------+  |
+--------------------------------------+
```

### 5.2 Scan (camera ready)

```
+--------------------------------------+
|  LookFirst                  [torch][⚙]|
|                                      |
|  +--------------------------------+  |
|  |                                |  |
|  |      +-  -  -  -  -  -+        |  |
|  |      |                 |       |  |
|  |      |   camera view   |       |  |
|  |      |                 |       |  |
|  |      +-  -  -  -  -  -+        |  |
|  |                                |  |
|  +--------------------------------+  |
|                                      |
|  Pointez un QR code. Rien ne         |
|  s'ouvrira sans votre accord.        |
|                                      |
|  +--------------------------------+  |
|  |  [img]  Choisir une image      |  |
|  +--------------------------------+  |
|  Listes : mises à jour il y a 3 j.   |
+--------------------------------------+
```

Variants (same frame):
- Lists stale/absent: the last line becomes a tappable warning chip "Listes anciennes (21 j) : touchez pour mettre à jour" / "Aucune liste : la réputation ne sera pas vérifiée".
- Airplane mode: the last line adds "Hors connexion : l'analyse locale fonctionne, les listes peuvent être anciennes." Nothing blocks.
- Camera paused (a result is shown): the viewfinder is not visible; the Result screen is on top.

### 5.3 Camera permission

First request (before the system dialog):

```
+--------------------------------------+
|  LookFirst                        [⚙]|
|                                      |
|            [ camera icon ]           |
|                                      |
|   LookFirst a besoin de la caméra    |
|   pour lire les QR codes.            |
|   Les images ne sont ni enregistrées |
|   ni envoyées.                       |
|                                      |
|  +--------------------------------+  |
|  |       Autoriser la caméra      |  |
|  +--------------------------------+  |
|  +--------------------------------+  |
|  |  [img]  Choisir une image      |  |
|  +--------------------------------+  |
+--------------------------------------+
```

Denied (and "don't ask again"):

```
+--------------------------------------+
|  LookFirst                        [⚙]|
|                                      |
|            [ camera barred ]         |
|                                      |
|   La caméra est désactivée.          |
|   Vous pouvez autoriser l'accès dans |
|   les réglages Android, ou scanner   |
|   une image à la place.              |
|                                      |
|  +--------------------------------+  |
|  |   Ouvrir les réglages Android  |  |
|  +--------------------------------+  |
|  +--------------------------------+  |
|  |  [img]  Choisir une image      |  |
|  +--------------------------------+  |
+--------------------------------------+
```

Image scan stays fully usable. No guilt wording, no repeated prompts.

### 5.4 Image scan failures

```
+--------------------------------------+
|  [<]  Image                          |
|                                      |
|   [ image preview, small ]           |
|                                      |
|   (i) Aucun QR code trouvé dans      |
|       cette image.                   |
|       Essayez une image plus nette,  |
|       bien cadrée, sans reflet.      |
|                                      |
|  +--------------------------------+  |
|  |     Choisir une autre image    |  |
|  +--------------------------------+  |
|  [ Retour à la caméra ]              |
+--------------------------------------+
```

Variant "QR trouvé mais illisible": "Un QR code a été repéré, mais il est trop abîmé pour être lu."

### 5.5 Unreadable or too large content

```
+--------------------------------------+
|  [<]  Contenu non analysable         |
|                                      |
|  [!]  Ce QR code ne peut pas être    |
|       analysé de façon fiable.       |
|       Son contenu est trop volumineux|
|       (ou illisible).                |
|       Par prudence, LookFirst ne     |
|       l'affiche pas.                 |
|                                      |
|  Ce n'est pas un feu vert :          |
|  n'ouvrez rien qui vienne de lui.    |
|                                      |
|  +--------------------------------+  |
|  |             Annuler            |  |
|  +--------------------------------+  |
+--------------------------------------+
```

Level: CAUTION (diamond icon). No Open, no Copy (nothing trustworthy to copy). Empty content: "Ce QR code est vide." with the same layout.

### 5.6 Result (epic "Scanner et décoder": contents plus Copy/Cancel)

```
+--------------------------------------+
|  [<]  Résultat du scan               |
|  +--------------------------------+  |
|  | [?]  Pas encore analysé        |  |
|  | Contenu décodé tel quel. Aucune|  |
|  | vérification n'a été faite.    |  |
|  +--------------------------------+  |
|  Type : lien web (https)             |
|  Ce lien ouvrira                     |
|  example.com                         |
|                                      |
|  Contenu exact                       |
|  +--------------------------------+  |
|  | https://example.com/a?b=1      |  |
|  +--------------------------------+  |
|                                      |
|  [ Annuler ]  [ Copier ]             |
+--------------------------------------+
```

### 5.7 Verdict, DANGEROUS (fits without scrolling)

```
+--------------------------------------+
|  [<]  Résultat                       |
|  +--------------------------------+  |
|  | [X]  DANGER                    |  |
|  +--------------------------------+  |
|  Cette adresse figure dans une liste |
|  publique de sites signalés.         |
|                                      |
|  Ce lien ouvrira                     |
|  secure-login.example                |
|  (xn--... si IDN)                    |
|                                      |
|  [X] Adresse signalée (liste A)      |
|  [!] Connexion non chiffrée (http)   |
|  Voir les 1 autre signal  v          |
|  > Afficher le contenu exact         |
|  Listes : mises à jour il y a 3 j.   |
|  ------------------------------------|
|  +--------------------------------+  |
|  |           ANNULER              |  |
|  +--------------------------------+  |
|  [ Copier ]       [ Ouvrir quand   ] |
|                   [ même           ] |
+--------------------------------------+
```

Cancel is filled and full-width on top, Copy and Open below as outlined buttons. For levels CAUTION and NO_RISK_SIGNAL, the row is `[Annuler] [Copier] [Ouvrir]` with Open filled. (Position of each button is the same in every level; only emphasis changes.)

### 5.8 Verdict, SUSPICIOUS with IDN, expanded details

```
+--------------------------------------+
|  [<]  Résultat                       |
|  +--------------------------------+  |
|  | [/!\] SUSPECT                  |  |
|  +--------------------------------+  |
|  L'adresse ressemble à paypal.com    |
|  mais utilise un « а » cyrillique.   |
|                                      |
|  Ce lien ouvrira                     |
|  раураl.com                          |
|  Forme technique : xn--l1aaa6c.com   |
|  (i) Les navigateurs utiliseront     |
|      cette forme.                    |
|                                      |
|  [!] Imite un site connu             |
|  [!] Mélange latin et cyrillique     |
|  [i] Domaine : voir « Vérifications  |
|      facultatives »                  |
|  ------------------------------------|
|  [ Annuler ] [ Copier ] [ Ouvrir ]   |
+--------------------------------------+
```

The domain text above is illustrative; real lookalike rendering uses the actual characters with dotted underline under non-ASCII letters.

### 5.9 Verdict, CAUTION (list stale, short URL, offline)

```
+--------------------------------------+
|  [<]  Résultat                       |
|  +--------------------------------+  |
|  | [<>!] À VÉRIFIER               |  |
|  +--------------------------------+  |
|  Ce lien raccourci cache sa vraie    |
|  destination.                        |
|                                      |
|  Ce lien ouvrira                     |
|  bit.example                         |
|                                      |
|  [!] Lien raccourci                  |
|  [!] Listes anciennes (21 jours)     |
|  > Afficher le contenu exact         |
|                                      |
|  Vérifications facultatives          |
|  [ Suivre les redirections ]         |
|  [ Âge du domaine ]                  |
|  (i) Hors connexion : indisponible   |
|  ------------------------------------|
|  [ Annuler ] [ Copier ] [ Ouvrir ]   |
+--------------------------------------+
```

### 5.10 Verdict, NO_RISK_SIGNAL

```
+--------------------------------------+
|  [<]  Résultat                       |
|  +--------------------------------+  |
|  | (o)  AUCUN SIGNAL DE RISQUE    |  |
|  |      DÉTECTÉ                   |  |
|  +--------------------------------+  |
|  Cela ne garantit rien : les listes  |
|  ne connaissent que les menaces déjà |
|  signalées.                          |
|                                      |
|  Ce lien ouvrira                     |
|  example.com                         |
|                                      |
|  Contrôles effectués : adresse,      |
|  alphabets, schéma, listes           |
|  > Afficher le contenu exact         |
|  Listes : mises à jour il y a 3 j.   |
|  ------------------------------------|
|  [ Annuler ] [ Copier ] [ Ouvrir ]   |
+--------------------------------------+
```

"(o)" is the eye icon. The line "Contrôles effectués : …" lists what was checked (not the result) so the user knows what absence of signal means (D18).

### 5.11 Content type without Open (deep link, otpauth)

```
+--------------------------------------+
|  [<]  Résultat                       |
|  +--------------------------------+  |
|  | [<>!] À VÉRIFIER               |  |
|  +--------------------------------+  |
|  Ce code ouvre une application, pas  |
|  un site. LookFirst ne l'ouvre pas.  |
|                                      |
|  Type : lien d'application           |
|  monappli://pay?to=...               |
|  > Afficher le contenu exact         |
|  ------------------------------------|
|  [ Annuler ]        [ Copier ]       |
+--------------------------------------+
```

otpauth variant: reason "Ce code ajoute un compte de double authentification (service : X, compte : Y). Un service légitime ne l'affiche que pendant que VOUS configurez la double authentification." Secret masked.

### 5.12 Warning sheet: optional checks

```
+--------------------------------------+
|  (dimmed verdict)                    |
|  +--------------------------------+  |
|  | Suivre les redirections ?      |  |
|  |                                |  |
|  | LookFirst va contacter ce lien |  |
|  | pour voir où il mène.          |  |
|  | Le site verra votre adresse IP |  |
|  | et saura que son QR code a été |  |
|  | scanné.                        |  |
|  | Rien n'est ouvert ni installé. |  |
|  |                                |  |
|  | [ Annuler ]   [ Continuer ]    |  |
|  +--------------------------------+  |
+--------------------------------------+
```

Domain age sheet: "LookFirst demande à un registre public (RDAP) depuis quand ce nom de domaine existe. Seul le nom de domaine (example.com) est envoyé, pas l'adresse complète. Le registre voit votre adresse IP. Le site lui-même n'est pas contacté." Cancel is the default focus in both sheets.

### 5.13 Settings

```
+--------------------------------------+
|  [<]  Réglages                       |
|                                      |
|  Listes de réputation                |
|  Version 128 · il y a 3 jours        |
|  Dernière tentative : réussie        |
|  [ Mettre à jour maintenant ]        |
|  Mise à jour automatique    [ON  o]  |
|  Réseau non limité uniquement        |
|                                      |
|  À propos et confidentialité    >    |
|  Version de l'application      1.0.0 |
+--------------------------------------+
```

States: updating (spinner, "Mise à jour en cours"), failed ("Échec : la liste précédente est conservée"), rejected ("Liste refusée : vérification de signature échouée. La liste précédente est conservée." No technical detail), offline ("Hors connexion"), up to date ("Déjà à jour").

### 5.14 About and privacy

```
+--------------------------------------+
|  [<]  À propos et confidentialité    |
|                                      |
|  Ce qui reste sur votre téléphone    |
|  Le contenu des QR codes, l'analyse  |
|  et les résultats. Rien n'est        |
|  enregistré.                         |
|                                      |
|  Ce qui utilise le réseau            |
|  +-----------------+----------------+|
|  | Mise à jour des | Hébergeur des  ||
|  | listes (auto)   | listes : votre ||
|  |                 | IP seulement   ||
|  | Redirections    | Le site scanné ||
|  | (sur demande)   | voit votre IP  ||
|  | Âge du domaine  | Registre RDAP: ||
|  | (sur demande)   | nom de domaine ||
|  +-----------------+----------------+|
|                                      |
|  Ce que LookFirst ne garantit pas    |
|  Un site neuf ou non signalé peut    |
|  être malveillant. Restez vigilant.  |
|                                      |
|  Licences                       >    |
+--------------------------------------+
```

## 6. Network disclosure table (About screen content)

| Use | When | What leaves the phone | Who sees it |
|---|---|---|---|
| List update | automatic (if on) or manual | a plain download request | the list host: your IP |
| Redirect tracing | only on your tap | requests to the scanned address | the scanned site: your IP, the time, that its QR was scanned |
| Domain age | only on your tap | the registrable domain only | the RDAP registry: your IP and the domain |

## 7. Texts (FR / EN)

Strings live in `strings.xml` (fr default, en). Placeholders use `%1$s`.

### 7.1 Generic

| Key | FR | EN |
|---|---|---|
| action_cancel | Annuler | Cancel |
| action_copy | Copier | Copy |
| action_open | Ouvrir | Open |
| action_open_anyway | Ouvrir quand même | Open anyway |
| copied | Copié | Copied |
| scan_hint | Pointez un QR code. Rien ne s'ouvrira sans votre accord. | Point at a QR code. Nothing opens without your approval. |
| pick_image | Choisir une image | Choose an image |
| link_will_open | Ce lien ouvrira | This link will open |
| technical_form | Forme technique : %1$s | Technical form: %1$s |
| technical_form_help | Les navigateurs utiliseront cette forme technique. Si elle ne ressemble pas au nom attendu, c'est un signal de risque. | Browsers will use this technical form. If it does not look like the expected name, that is a risk signal. |
| show_content | Afficher le contenu exact | Show exact content |
| hide_content | Masquer le contenu | Hide content |
| more_signals | Voir les %1$d autres signaux | See %1$d more signals |
| lists_age | Listes de réputation : mises à jour %1$s. | Reputation lists: updated %1$s. |
| checked_line | Contrôles effectués : adresse, alphabets, schéma, listes de réputation. | Checks run: address, alphabets, scheme, reputation lists. |
| optional_checks | Vérifications facultatives | Optional checks |
| trace_redirects | Suivre les redirections | Trace redirects |
| check_domain_age | Vérifier l'âge du domaine | Check domain age |
| offline | Hors connexion | Offline |
| offline_note | Hors connexion : l'analyse locale fonctionne, les listes peuvent être anciennes. | Offline: local analysis works, the lists may be out of date. |
| not_openable | Ce type de contenu ne peut pas être ouvert depuis LookFirst. Vous pouvez seulement le copier. | This type of content cannot be opened from LookFirst. You can only copy it. |
| not_analysed | Contenu décodé, pas encore analysé | Decoded, not analysed yet |

### 7.2 Levels and disclaimer

| Level | FR | EN |
|---|---|---|
| DANGEROUS | Danger | Dangerous |
| SUSPICIOUS | Suspect | Suspicious |
| CAUTION | À vérifier | Check carefully |
| NO_RISK_SIGNAL | Aucun signal de risque détecté | No risk signal detected |
| no_risk_disclaimer | Cela ne garantit rien : les listes ne connaissent que les menaces déjà signalées. | This guarantees nothing: lists only know threats that were already reported. |

### 7.3 Reasons (stable code, sentence)

Severity: D = DANGEROUS, S = SUSPICIOUS, C = CAUTION.

| # | Code | Sev | FR | EN |
|---|---|---|---|---|
| 1 | REPUTATION_LISTED | D | Cette adresse figure dans une liste publique de sites signalés (%1$s). | This address is on a public list of reported sites (%1$s). |
| 2 | DANGEROUS_SCHEME | D | Ce contenu est un programme ou une commande (%1$s:), pas une page web. Il pourrait exécuter du code. | This content is a program or a command (%1$s:), not a web page. It could run code. |
| 3 | BIDI_IN_HOST | D | L'adresse contient un caractère invisible qui inverse le sens de lecture : elle peut s'afficher autrement qu'elle n'est réellement. | The address contains an invisible character that reverses reading direction, so it may display differently from what it really is. |
| 4 | BRAND_LOOKALIKE | S | L'adresse ressemble à %1$s mais utilise un « %2$s » %3$s. | The address looks like %1$s but uses a %3$s "%2$s". |
| 5 | MIXED_SCRIPT | S | Le nom mélange plusieurs alphabets (%1$s). C'est une technique courante pour imiter un autre site. | The name mixes several alphabets (%1$s). This is a common way to imitate another site. |
| 6 | USERINFO_AT | S | L'adresse contient un « @ » : ce qui précède n'est pas le site. Le vrai site est %1$s. | The address contains an "@": what comes before it is not the site. The real site is %1$s. |
| 7 | IP_HOST | S | L'adresse est une suite de chiffres (%1$s) et non un nom de site. Les services légitimes l'utilisent rarement. | The address is a string of numbers (%1$s) rather than a site name. Legitimate services rarely do this. |
| 8 | HIDDEN_CHARS | S | Le contenu cache %1$d caractère(s) invisible(s). Ils sont rendus visibles ci-dessous. | The content hides %1$d invisible character(s). They are made visible below. |
| 9 | PAYMENT_MISMATCH | S | Les informations de paiement sont incohérentes (%1$s). Vérifiez le bénéficiaire avant tout virement. | The payment details are inconsistent (%1$s). Check the beneficiary before any transfer. |
| 10 | OTPAUTH_ENROL | S | Ce code ajoute un compte de double authentification (%1$s). Un service légitime ne l'affiche que pendant que vous le configurez vous-même. | This code adds a two-factor account (%1$s). A legitimate service only shows it while you are setting it up yourself. |
| 11 | SHORTENER | C | Ce lien raccourci cache sa vraie destination. | This shortened link hides its real destination. |
| 12 | PLAIN_HTTP | C | La connexion n'est pas chiffrée (http) : ce que vous saisissez peut être lu par d'autres. | The connection is not encrypted (http): what you type can be read by others. |
| 13 | LONG_URL | C | L'adresse est très longue : le nom du site est noyé dans le reste. | The address is very long: the site name is buried in the rest. |
| 14 | UNUSUAL_PORT | C | L'adresse utilise un port inhabituel (%1$s). | The address uses an unusual port (%1$s). |
| 15 | OPEN_WIFI | C | Ce réseau Wi-Fi n'a pas de mot de passe : tout le monde peut observer ce que vous y faites. | This Wi-Fi network has no password: anyone can observe what you do on it. |
| 16 | UNKNOWN_SCHEME | C | Ce code ouvre une application (%1$s:), pas un site. LookFirst ne l'ouvre pas. | This code opens an app (%1$s:), not a site. LookFirst does not open it. |
| 17 | NON_BROWSER_HANDLER | C | Une application autre que le navigateur peut ouvrir ce lien. | An app other than the browser may open this link. |
| 18 | LISTS_STALE | C | Les listes de réputation ont %1$d jours : des menaces récentes peuvent manquer. | The reputation lists are %1$d days old: recent threats may be missing. |
| 19 | LISTS_MISSING | C | Aucune liste de réputation n'est installée : l'adresse n'a pas été comparée aux sites signalés. | No reputation list is installed: the address was not compared with reported sites. |
| 20 | UNPARSABLE | C | Ce contenu n'a pas pu être compris. Il est affiché tel quel. | This content could not be understood. It is shown as is. |
| 21 | REDIRECT_HTTP (after tracing) | S | Ce lien redirige vers une adresse non chiffrée (%1$s). | This link redirects to an unencrypted address (%1$s). |
| 22 | REDIRECT_FINAL (after tracing) | info | Après %1$d redirection(s), la destination finale est %2$s. | After %1$d redirect(s), the final destination is %2$s. |
| 23 | DOMAIN_RECENT (after RDAP) | S | Ce nom de domaine a été enregistré il y a %1$d jours : les sites frauduleux sont souvent récents. | This domain name was registered %1$d days ago: fraudulent sites are often recent. |

Phrasing rules: one sentence, says what was seen, then what it means; no "malware", "hack", "virus" without being the evidence; never "sûr/safe"; never an order in capitals.

### 7.4 Optional actions and errors

| Key | FR | EN |
|---|---|---|
| warn_trace_title | Suivre les redirections ? | Trace redirects? |
| warn_trace_body | LookFirst va contacter ce lien pour voir où il mène. Le site verra votre adresse IP et saura que son QR code a été scanné. Rien n'est ouvert ni installé. | LookFirst will contact this link to see where it leads. The site will see your IP address and learn that its QR code was scanned. Nothing is opened or installed. |
| warn_rdap_title | Vérifier l'âge du domaine ? | Check domain age? |
| warn_rdap_body | LookFirst demande à un registre public (RDAP) depuis quand ce nom de domaine existe. Seul le nom de domaine (%1$s) est envoyé, pas l'adresse complète. Le registre voit votre adresse IP. Le site lui-même n'est pas contacté. | LookFirst asks a public registry (RDAP) how long this domain name has existed. Only the domain name (%1$s) is sent, not the full address. The registry sees your IP address. The site itself is not contacted. |
| warn_continue | Continuer | Continue |
| check_failed | Vérification impossible (délai dépassé ou réseau indisponible). Le verdict reste celui de l'analyse locale. | Check not possible (timed out or network unavailable). The verdict remains the local analysis. |
| dialog_open_danger_title | Ouvrir quand même ? | Open anyway? |
| dialog_open_danger_body | LookFirst a trouvé des signaux de danger sérieux. Si vous continuez, c'est à vos risques. | LookFirst found serious danger signals. If you continue, you do so at your own risk. |
| dialog_copy_secret_title | Copier le secret ? | Copy the secret? |
| dialog_copy_secret_body | Les autres applications ouvertes et le clavier peuvent lire le presse-papiers. LookFirst l'efface quand vous revenez, si votre version d'Android le permet. | Other open apps and the keyboard can read the clipboard. LookFirst clears it when you come back, if your Android version allows it. |
| err_no_qr | Aucun QR code trouvé dans cette image. Essayez une image plus nette, bien cadrée, sans reflet. | No QR code found in this image. Try a sharper, well-framed image without glare. |
| err_unreadable | Un QR code a été repéré, mais il est trop abîmé pour être lu. | A QR code was spotted, but it is too damaged to be read. |
| err_too_big | Ce QR code est trop volumineux pour être analysé de façon fiable. Par prudence, LookFirst ne l'affiche pas. | This QR code is too large to be analysed reliably. As a precaution, LookFirst does not display it. |
| err_empty | Ce QR code est vide. | This QR code is empty. |
| err_not_a_green_light | Ce n'est pas un feu vert : n'ouvrez rien qui vienne de lui. | This is not a green light: do not open anything coming from it. |
| err_multi_qr | Plusieurs QR codes dans l'image : seul le premier est affiché. | Several QR codes in the image: only the first is shown. |
| camera_rationale | LookFirst a besoin de la caméra pour lire les QR codes. Les images ne sont ni enregistrées ni envoyées. | LookFirst needs the camera to read QR codes. Images are neither saved nor sent. |
| camera_denied | La caméra est désactivée. Vous pouvez autoriser l'accès dans les réglages Android, ou scanner une image à la place. | The camera is off. You can allow access in Android settings, or scan an image instead. |
| lists_update_failed | Échec de la mise à jour : la liste précédente est conservée. | Update failed: the previous list is kept. |
| lists_rejected | Liste refusée : la vérification a échoué. La liste précédente est conservée. | List rejected: verification failed. The previous list is kept. |
| lists_up_to_date | Déjà à jour. | Already up to date. |

Error handling rule: typed errors map to these strings; `Throwable.message` is never displayed.

## 8. Accessibility (WCAG 2.2 AA)

### Contrast
- Text and icons on the banner: >= 4.5:1 for text, >= 3:1 for icons and component borders (SC 1.4.3, 1.4.11). Use a dark text on a light tinted container (or white on a dark one), never coloured text on a coloured container. Concrete colour tokens are checked with a contrast tool when the theme is written; the spec requires verification in both light and dark themes.
- Highlighted evidence uses bold + underline, not colour alone (SC 1.4.1).
- The four levels must be distinguishable in greyscale: icon silhouette + label differ.

### Touch targets
- All buttons, expanders and icon buttons >= 48 x 48 dp. Spacing >= 8 dp between the three action buttons.
- Cancel and Open are never adjacent without a gap; in the DANGEROUS verdict Cancel is on top, full-width.

### Text size
- All text in `sp`; layout tested at font scale 1.0, 1.5 and 2.0. The sticky action bar stacks vertically if needed; the middle content scrolls. No truncation of the host line (it wraps on several lines, breaking at dots, never ellipsized).
- Host: >= 24 sp bold; body 16 sp; secondary 14 sp minimum.

### TalkBack
Reading order = visual order:
1. Banner: "Suspect. L'adresse ressemble à paypal.com mais utilise un a cyrillique." (heading, announced with `liveRegion = polite` once when the screen appears, not repeated).
2. "Ce lien ouvrira" then the host, read letter by letter on demand; for an IDN: the Unicode form then "Forme technique : x n dash dash …".
3. Reasons, one list item each, "signal de risque 1 sur 3 : …".
4. Content expander: "Contenu exact, réduit, bouton, double-toucher pour afficher".
5. List age.
6. Optional checks, then the actions as the last focus group.
Rules: the icon is decorative (`contentDescription = null`) because the label carries the meaning; invisible characters are exposed as text ("caractère U+202E, inversion du sens de lecture"); initial focus on the banner heading; actions have explicit descriptions ("Ouvrir dans le navigateur le lien vers example.com", "Copier le contenu exact", "Annuler et reprendre le scan"); after Copy the snackbar is announced; masked secrets are announced as "masqué", never read.
- The camera view has the description "Aperçu de la caméra. Pointez un QR code." plus an announcement "Scan en pause" when a result is shown.
- Dialogs trap focus; initial focus is on Cancel.

### Other
- No timeouts on decisions; no flashing; haptic feedback is optional and never the only signal of a successful scan (the screen changes).
- `filterTouchesWhenObscured` on the root view (T-UI-3); if the touch is filtered, no message is needed.
- Respect system dark theme and reduced motion.

## 9. Deliberately excluded (MVP)

- No scan history (architecture keeps it as an optional, off-by-default later feature).
- No sharing of results or content, no "send to a friend", no export.
- No ads, no analytics, no accounts, no crash reporter that sends data.
- No auto-open, no auto-copy, no auto-connect, no "open next time without asking".
- No in-app browser or WebView, no link preview that fetches the page.
- No "safe", "secure", "verified", green check or padlock anywhere.
- No score or percentage of risk (false precision); levels and reasons only.
- No allow-list or "trust this site" button at MVP.
- No batch scan, no continuous scan.
- No in-app language switch (follows system).
- No advanced setting that re-enables Open for deep links or dangerous schemes (D12).

## 10. Decisions taken on the open questions

| Code | Question | Decision | Reason |
|---|---|---|---|
| D14 | Several QR codes in one image | First decoded only, with a visible line saying so | Simpler; a list to choose from adds a step where a decoy can be picked |
| D15 | SEPA / payment and crypto | Copy only, no Open | No safe generic Intent; payment apps are the target of the attack |
| D16 | Stale lists | CAUTION reason above 14 days; an initial signed bundle ships in the APK so lists are never absent on first launch | Phishing domains live hours to days; the APK bundle covers the first run and airplane mode |
| D17 | DANGEROUS | Open kept, behind one confirmation dialog (Cancel default) | Removing it contradicts "the user decides" and would push users to copy-paste around the app |
| D18 | "Vérifié : …" line | Reworded "Contrôles effectués : …" / "Checks run: …" | Lists what was checked without sounding like a seal of approval |
