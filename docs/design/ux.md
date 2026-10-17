# LookFirst UX design

Status: accepted (LF-7). Open questions settled on the cautious side, see section 10 (decisions D14 to D18). Section 7 is the source of truth for every string; mock-ups only illustrate.
Scope: flows, screens, states, texts (FR/EN), accessibility, exclusions.
Sources: `architecture.md` (flow, verdict model, opening content, immutability, network), `threat-model.md` (T-CNT, T-SCH-11, T-OTP, T-UI), `DECISIONS.md` (D3, D9, D12).

## 1. Principles

1. The user decides. Nothing opens, connects, dials or sends after a scan. Only an explicit tap does.
2. One decision at a time: Open / Copy / Cancel. The three actions are always in the same place.
3. The verdict explains in one sentence, then details the signals.
4. The real address is shown before any action (normalised form, host emphasised, punycode if IDN).
5. A false sense of security is worse than a useless warning. We never write "sûr" / "safe". The best level reads "Aucun signal de risque détecté" / "No risk signal detected" and always carries the "guarantees nothing" line.
6. Friction proportional to risk, with no dark pattern: no countdown, no hidden Cancel, no guilt wording. One single action layout in every level: a fixed vertical stack of three full-width buttons (48 dp each, 8 dp gap), always in the order Cancel, Copy, Open. Only the emphasis changes between levels (filled vs outlined); a missing button leaves a shorter stack, the others never move or swap.
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
                    +--(Open)--> [Confirm dialog, SUSPICIOUS and DANGEROUS] --> external app
```

Single activity, Navigation Compose. Scan is the home screen. Back from Result = Cancel (never an action).

## 3. Flows

### 3.1 First launch (once)
One screen, no carousel, no account. Three facts, one button.
- Nothing opens by itself; you always choose.
- Everything is analysed on your phone; scanned addresses are not sent anywhere, unless you start an optional check.
- Reputation lists are downloaded in the background (toggle, on by default, can be changed later in Settings). The download server only sees your IP.
Button: "Commencer" / "Get started". The camera permission is NOT requested here; it is requested when the user first opens the camera view, with a one-line rationale shown before the system dialog.

### 3.2 Camera scan
Scan opens with the viewfinder. A decoded QR (first stable read) pauses the camera and goes to Result/Verdict. Haptic tick only, no sound. The camera stays paused while a result is displayed (verdict immutability); on Cancel or after an action the camera resumes.

### 3.3 Image scan
"Image" button opens the system Photo Picker (no storage permission). Decoding runs on the picked image. Success goes to Result/Verdict. Failures: "no QR found" or "unreadable" (section 5.4). Several QR codes in one image: the first decoded one is used and the line "Plusieurs QR codes dans l'image : seul le premier est affiché." is shown (D14).

### 3.4 Result (epic "Scanner et décoder": content + Copy/Cancel)
Same layout as the Verdict screen with the verdict slot showing a neutral "not analysed" banner (grey, icon "?", text "Pas encore analysé"). It must never look like NO_RISK_SIGNAL. Actions: Cancel (filled), Copy (outlined); no Open. Replaced by the real banner when the verdict epic lands; no layout change.

### 3.5 Verdict (epic "Analyser et expliquer")
Banner (level) + one-sentence reason + emphasised host + the 3-button stack, all visible without scrolling (guarantee and limits in 4.2). Details (all signals, exact content, list age, optional checks) in the middle zone.

### 3.6 Actions
- Copy: copies the exact decoded text (not the normalised URL, so nothing is altered). Snackbar "Copié" / "Copied". For secrets (otpauth, Wi-Fi password): confirmation dialog first, then the sensitive flag; see 4.6.
- Open: depends on type (table 4.5). SUSPICIOUS and DANGEROUS add one confirmation dialog (D17, threat model T-UI-2).
- Cancel: closes, resumes the camera. No confirmation.
- Optional checks (http/https only): see 3.7.

### 3.7 Optional network checks
Two text buttons in a "Vérifications facultatives" section: "Suivre les redirections" and "Vérifier l'âge du domaine". Each opens a bottom sheet with a warning and two buttons (Continuer / Annuler). Offline: both buttons disabled with the note "Hors connexion". Loading: spinner in the sheet, 5 s cap, Cancel always available. Failure: "Vérification impossible (délai dépassé ou réseau indisponible). Le verdict reste celui de l'analyse locale."

Result of a check (verdict immutability preserved):
- A check produces a NEW `Verdict` bound to the same `payloadId`. The displayed content does not change; only the reasons and possibly the level do. The level may go up, never down (info reasons do not count, see 7.3).
- "Ce lien ouvrira" and the Open button stay on the scanned address, never on the final destination found by tracing. The final destination appears only as an info reason (REDIRECT_FINAL).
- If the level changes, TalkBack announces it once (`a11y_level_changed`, polite live region); nothing is announced if the level is unchanged.
- The user still has to choose Cancel / Copy / Open.

### 3.8 Settings
Reached from the Scan top bar (gear icon).
- Reputation lists: version, date and age ("mises à jour il y a 3 jours"), "Mettre à jour maintenant", toggle "Mise à jour automatique" (on by default, unmetered network only), result of the last attempt.
- About & privacy: what is local, what uses the network and when, who sees what (table 6), licences, app version, the "no guarantee" statement.
- Language follows the system (no in-app switch at MVP).
No history setting at MVP (section 9).

## 4. Verdict presentation

### 4.1 Levels

| Level | Icon (shape differs) | Label FR | Label EN | Colour role | Emphasis (Cancel / Copy / Open) |
|---|---|---|---|---|---|
| DANGEROUS | octagon with X | Danger | Dangerous | red container, dark red text | Cancel filled; Copy outlined; Open outlined, label "Ouvrir quand même", confirmation dialog |
| SUSPICIOUS | triangle with ! | Suspect | Suspicious | orange container | Cancel filled; Copy outlined; Open outlined, label "Ouvrir quand même", confirmation dialog |
| CAUTION | diamond with ! | À vérifier | Check carefully | amber container | Cancel outlined; Copy outlined; Open filled, label of the type (4.5) |
| NO_RISK_SIGNAL | eye (not a check, not a shield, not a padlock) | Aucun signal de risque détecté | No risk signal detected | neutral blue-grey container, never green | Cancel outlined; Copy outlined; Open filled, label of the type (4.5) |

Rules:
- Banner = icon (>= 24 dp) + label as text + container colour. Text on the banner never relies on colour alone, and the icons differ by silhouette so they can be told apart in greyscale.
- No green, no check mark, no padlock anywhere in the app.
- Layout is identical in every level: vertical stack, top to bottom Cancel, Copy, Open (48 dp each, 8 dp gap, full width). Only emphasis changes. If a button does not exist for the type (4.5), the stack is shorter and the others keep their order.
- When no Open exists (deep link, otpauth, payment...), Cancel is filled whatever the level.
- NO_RISK_SIGNAL always shows under the banner: "Cela ne garantit rien : les listes ne connaissent que les menaces déjà signalées." / "This guarantees nothing: lists only know threats that were already reported."
- Unknown or unparsable content is CAUTION, never NO_RISK_SIGNAL.
- The level is the maximum severity of the reasons, `info` reasons excluded (see 7.3).

### 4.2 Information order (top to bottom)
1. Level banner (icon + label).
2. One sentence: the most severe reason, as a full sentence. For NO_RISK_SIGNAL: the "guarantees nothing" sentence.
3. "Ce lien ouvrira" / "This link will open": the host, large, bold, alone on its line. If IDN: second line "Forme technique : xn--pypal-4ve.com" with a "?" explaining sentence (4.4). Under it, smaller: scheme and port only if not default (`http`, `:8443`).
4. Reasons: one row per reason with its severity icon and its `shortLabel` (7.3); the first 3 are shown, the others behind "Voir N autres signaux". A row expands (tap, 48 dp) to the full sentence with the evidence fragment highlighted (bold plus underline, not colour only). The most severe sentence is already in item 2.
5. Exact content: collapsed to 2 lines, "Afficher le contenu exact" expander, escaped (4.3).
6. Lists age line: "Listes de réputation : mises à jour il y a 3 jours." If older than 14 days or absent, it also becomes a CAUTION reason (D16).
7. Optional checks (http/https only).
8. Actions: the 3-button stack, pinned at the bottom, outside the scrollable area.

Fit rule (honest budget, 360x640 dp, font scale 100%, non-IDN host). Usable height about 568 dp after status and navigation bars.

| Fixed item | Height |
|---|---|
| Top bar | 56 dp |
| Banner (72 dp for the two-line NO_RISK label) | 56 dp |
| Sentence, up to 3 lines | 72 dp |
| Host block (label + host line) | 64 dp |
| Gaps between the above | 24 dp |
| Action zone: 3 x 48 + 2 x 8 + 16 padding | 176 dp |
| Total fixed zone | about 450 dp (466 for NO_RISK) |

That leaves about 120 dp (104 for NO_RISK) for the middle zone: two reason rows or the content expander. Guarantee: banner, sentence, host and the three buttons are visible without scrolling at font scale 100% and a non-IDN host. Beyond that (larger font, IDN second line, longer sentence, shorter screen) the middle zone scrolls and the action stack stays pinned; if even the fixed items do not fit, the zone from banner to host scrolls with the middle zone and only the stack stays pinned. Nothing is ever hidden behind the stack.

### 4.3 Exact content and invisible characters
- Rendered in a monospace, LTR-isolated container (so a bidi override cannot reorder the display), selectable text disabled for the visual line (copy goes through Copy).
- Control, format and bidi characters are replaced by a visible token with a distinct background and the token is spoken by TalkBack as text: `<U+202E>`, `<U+200B>`, `<U+0000>`, `<LF>`.
- Lookalike (non-ASCII) letters in a host are marked with a dotted underline and listed in the reason ("le « а » est cyrillique (U+0430)").
- Long content (> 120 characters): collapsed to 2 lines plus "Afficher le contenu exact"; expanded view scrolls inside the container, cap displayed at 2000 characters then "… (N caractères de plus non affichés)". The bound on analysed size is handled by the decoder (see 5.5).
- Secrets (otpauth secret, Wi-Fi password): masked "••••••••" with an "Afficher" toggle, never expanded by default.

### 4.4 Domain, IDN and punycode
- The host is the only large text. Everything else (path, query) lives in the collapsed content.
- If the host contains non-ASCII characters: show the Unicode form as typed in the QR, then "Forme technique : xn--…" and the one-line explanation: "Les navigateurs utiliseront cette forme technique. Si elle ne ressemble pas au nom attendu, c'est un signal de risque."
- If the URL has a userinfo ("https://paypal.com@evil.example"): the host shown is the REAL host (`evil.example`), with the reason explaining the `@`.
- Registrable domain emphasis: the registrable part (from the Public Suffix List) in bold, subdomains in regular weight, so `paypal.com.evil.example` shows `evil.example` in bold.

### 4.5 Buttons by content type
Cancel and Copy exist for every type except where noted; the Open label is the key in 7.5.

| Type | Cancel | Copy | Open (label) | Notes |
|---|---|---|---|---|
| http / https URL | yes | yes | yes ("Ouvrir dans le navigateur") | via default browser; if a non-browser app would capture the link, a reason says so |
| URL at SUSPICIOUS or DANGEROUS | yes | yes | yes ("Ouvrir quand même"), confirmation dialog | Cancel filled |
| `javascript:` `intent:` `file:` `content:` `data:` | yes | yes | no | text: "Ce type de contenu ne peut pas être ouvert depuis LookFirst." |
| App deep link / unknown scheme (D12) | yes | yes | no | same text, Copy labelled "Copier seulement" |
| `tel:` | yes | yes | yes ("Ouvrir le composeur") | dial only, never call |
| `sms:` / `smsto:` | yes | yes | yes ("Écrire le SMS") | message is prefilled, not sent |
| `mailto:` | yes | yes | yes ("Écrire l'e-mail") | not sent |
| `geo:` | yes | yes | yes ("Ouvrir dans Plans") | `ACTION_VIEW` geo to the default maps app; nothing is fetched by LookFirst |
| Wi-Fi | yes | yes (password: confirmation) | API 30+: "Ajouter ce réseau" (system confirmation); API < 30: "Ouvrir les réglages Wi-Fi" | credentials shown (password masked + Afficher); never connects |
| `otpauth://` | yes | yes (confirmation + sensitive flag) | no | issuer and account shown, secret masked |
| SEPA / payment (EPC) | yes | yes (IBAN, amount, beneficiary as separate fields) | no (D15) | consistency reasons |
| Crypto address | yes | yes | no (D15) | address shown in groups of 4 |
| Plain text / unknown | yes | yes | no | CAUTION by default |
| Unreadable / too big | yes | no | no | error state, see 5.5 |

### 4.6 Confirmation dialogs
- Open at SUSPICIOUS or DANGEROUS: title "Ouvrir quand même ?", body (DANGEROUS) "LookFirst a trouvé des signaux de danger sérieux. Si vous continuez, c'est à vos risques." or (SUSPICIOUS) "LookFirst a trouvé des signaux de risque. Si vous continuez, c'est à vos risques.", host repeated in bold, buttons "Annuler" (initial focus, filled) and "Ouvrir quand même" (text). No delay, no checkbox.
- Copy of a secret: title "Copier le secret ?", body "Les autres applications ouvertes et le clavier peuvent lire le presse-papiers. LookFirst l'efface quand vous revenez, si votre version d'Android le permet." Buttons "Annuler" / "Copier".

## 5. Screens and mock-ups

Width 38 characters, about a 360 dp phone. `[ ]` outlined button, `[####]` filled button, `(i)` info icon. Mock-ups show structure, not final pixels; strings come from section 7.

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
|       nulle part, sauf si vous       |
|       lancez une vérification        |
|       facultative.                   |
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
|  [########## Commencer ###########]  |
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
|  [    [img]  Choisir une image    ]  |
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
|  [##### Autoriser la caméra ######]  |
|  [    [img]  Choisir une image    ]  |
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
|  [# Ouvrir les réglages Android ##]  |
|  [    [img]  Choisir une image    ]  |
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
|  [### Choisir une autre image ####]  |
|  [       Retour à la caméra       ]  |
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
|  [########### Annuler ############]  |
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
|  [########### Annuler ############]  |
|  [             Copier             ]  |
+--------------------------------------+
```

### 5.7 Verdict, DANGEROUS (fixed zone fits without scrolling)

```
+--------------------------------------+
|  [<]  Résultat                       |
|  +--------------------------------+  |
|  | [X]  Danger                    |  |
|  +--------------------------------+  |
|  Cette adresse figure dans une liste |
|  publique de sites signalés (A).     |
|                                      |
|  Ce lien ouvrira                     |
|  secure-login.example                |
|                                      |
|  [X] Adresse signalée                |
|  [!] Connexion non chiffrée          |
|  > Voir 1 autre signal               |
|  > Afficher le contenu exact         |
|  Listes : mises à jour il y a 3 j.   |
|  ------------------------------------|
|  [########### Annuler ############]  |
|  [             Copier             ]  |
|  [       Ouvrir quand même        ]  |
+--------------------------------------+
```

Same stack in every level: Cancel, Copy, Open from top to bottom. Here Cancel is filled and Open is outlined (and goes through the confirmation dialog). For CAUTION and NO_RISK_SIGNAL, Open is filled and Cancel outlined. On a screen too short for the rows in the middle, they scroll above the pinned stack.

### 5.8 Verdict, SUSPICIOUS with IDN

```
+--------------------------------------+
|  [<]  Résultat                       |
|  +--------------------------------+  |
|  | [/!\] Suspect                  |  |
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
|  [!] Alphabets mélangés              |
|  ------------------------------------|
|  [########### Annuler ############]  |
|  [             Copier             ]  |
|  [       Ouvrir quand même        ]  |
+--------------------------------------+
```

The domain text above is illustrative; real lookalike rendering uses the actual characters with dotted underline under non-ASCII letters. With the IDN second line the fixed zone exceeds the 568 dp budget on a 640 dp screen: the zone above the stack scrolls and the stack stays pinned (4.2).

### 5.9 Verdict, CAUTION (list stale, short URL, offline)

```
+--------------------------------------+
|  [<]  Résultat                       |
|  +--------------------------------+  |
|  | [<>!] À vérifier               |  |
|  +--------------------------------+  |
|  Ce lien raccourci cache sa vraie    |
|  destination.                        |
|                                      |
|  Ce lien ouvrira                     |
|  bit.example                         |
|                                      |
|  [!] Lien raccourci                  |
|  [!] Listes anciennes                |
|  > Afficher le contenu exact         |
|                                      |
|  Vérifications facultatives          |
|  [ Suivre les redirections ]         |
|  [ Vérifier l'âge du domaine ]       |
|  (i) Hors connexion                  |
|  ------------------------------------|
|  [            Annuler             ]  |
|  [             Copier             ]  |
|  [## Ouvrir dans le navigateur ###]  |
+--------------------------------------+
```

Fixed zone first, the rest of the middle (reasons, optional checks) scrolls above the pinned stack.

### 5.10 Verdict, NO_RISK_SIGNAL

```
+--------------------------------------+
|  [<]  Résultat                       |
|  +--------------------------------+  |
|  | (o)  Aucun signal de risque    |  |
|  |      détecté                   |  |
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
|  [            Annuler             ]  |
|  [             Copier             ]  |
|  [## Ouvrir dans le navigateur ###]  |
+--------------------------------------+
```

"(o)" is the eye icon. The line "Contrôles effectués : …" lists what was checked (not the result) so the user knows what absence of signal means (D18).

### 5.11 Content type without Open (deep link, otpauth)

```
+--------------------------------------+
|  [<]  Résultat                       |
|  +--------------------------------+  |
|  | [<>!] À vérifier               |  |
|  +--------------------------------+  |
|  Ce code ouvre une application, pas  |
|  un site. LookFirst ne l'ouvre pas.  |
|                                      |
|  Type : lien d'application           |
|  monappli://pay?to=...               |
|  > Afficher le contenu exact         |
|  ------------------------------------|
|  [########### Annuler ############]  |
|  [        Copier seulement        ]  |
+--------------------------------------+
```

otpauth variant: reason "Ce code ajoute un compte de double authentification (service : X, compte : Y). Un service légitime ne l'affiche que pendant que vous le configurez vous-même." Secret masked; Copy is labelled "Copier" and goes through the secret dialog.

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

Domain age sheet: "LookFirst demande à un registre public (RDAP, rdap.verisign.com) depuis quand ce nom de domaine existe. Seul le nom de domaine (example.com) est envoyé, pas l'adresse complète. Le registre voit votre adresse IP. Le site lui-même n'est pas contacté." It names the registry server and the domain exactly as they will be sent (punycode for an IDN). Cancel is the initial focus in both sheets. The result stays under "Vérifications facultatives" as a line ("Domaine enregistré le 10 oct. 2026 (il y a 12 jours)."); the date is the registry's, shown in UTC. The button needs no reputation list. It is disabled with a note when there is no registry to ask (an IP address, a TLD the embedded bootstrap does not list) and offline.

### 5.13 Settings

```
+--------------------------------------+
|  [<]  Réglages                       |
|                                      |
|  Listes de réputation                |
|  Version 128 · il y a 3 jours        |
|  Dernière tentative : réussie        |
|  [    Mettre à jour maintenant    ]  |
|  Mise à jour automatique    [ON  o]  |
|  Réseau non limité uniquement        |
|                                      |
|  À propos et confidentialité    >    |
|  Version de l'application      1.0.0 |
+--------------------------------------+
```

States: updating (spinner, "Mise à jour en cours"), failed ("Échec de la mise à jour : la liste précédente est conservée."), rejected ("Liste refusée : la vérification de signature a échoué. La liste précédente est conservée." No technical detail), offline ("Hors connexion"), up to date ("Déjà à jour.").

### 5.14 About and privacy

```
+--------------------------------------+
|  [<]  À propos et confidentialité    |
|                                      |
|  Ce qui reste sur votre téléphone    |
|  Le contenu des QR codes, l'analyse  |
|  et les résultats. LookFirst         |
|  n'enregistre pas vos scans.         |
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

The IANA RDAP bootstrap file (which tells which registry to ask) and the Public Suffix List (which computes the registrable domain) are embedded in the app (`scripts/gen-host-data.py`) and refreshed with each app version. Neither is fetched on demand, so no third party besides the list host and the two optional targets above ever sees a request.

## 7. Texts (FR / EN)

Strings live in `strings.xml` (fr default, en). This section is exhaustive and is the source of truth; mock-ups illustrate. Placeholders use `%1$s` / `%1$d`. Counted strings are `plurals` (7.10), never `%d` + "(s)". FR `one` covers 0 and 1; EN `one` covers 1.

### 7.1 Generic

| Key | FR | EN |
|---|---|---|
| action_cancel | Annuler | Cancel |
| action_copy | Copier | Copy |
| action_open | Ouvrir | Open |
| action_open_anyway | Ouvrir quand même | Open anyway |
| copy_only | Copier seulement | Copy only |
| copied | Copié | Copied |
| back_to_camera | Retour à la caméra | Back to camera |
| scan_hint | Pointez un QR code. Rien ne s'ouvrira sans votre accord. | Point at a QR code. Nothing opens without your approval. |
| pick_image | Choisir une image | Choose an image |
| pick_other_image | Choisir une autre image | Choose another image |
| link_will_open | Ce lien ouvrira | This link will open |
| technical_form | Forme technique : %1$s | Technical form: %1$s |
| technical_form_help | Les navigateurs utiliseront cette forme technique. Si elle ne ressemble pas au nom attendu, c'est un signal de risque. | Browsers will use this technical form. If it does not look like the expected name, that is a risk signal. |
| show_content | Afficher le contenu exact | Show exact content |
| hide_content | Masquer le contenu | Hide content |
| secret_show | Afficher | Show |
| secret_hide | Masquer | Hide |
| secret_masked | •••••••• | •••••••• |
| lists_age | Listes de réputation : mises à jour %1$s. | Reputation lists: updated %1$s. |
| lists_age_short | Listes : mises à jour %1$s. | Lists: updated %1$s. |
| checked_line | Contrôles effectués : adresse, alphabets, schéma, listes de réputation. | Checks run: address, alphabets, scheme, reputation lists. |
| checked_line_content | Contrôles effectués : type de contenu, caractères cachés, cohérence des champs. | Checks run: type of content, hidden characters, consistency of the fields. |
| lists_none | Aucune liste de réputation installée. | No reputation list installed. |
| analysing_banner | Analyse en cours… | Analysing… |
| optional_checks | Vérifications facultatives | Optional checks |
| trace_redirects | Suivre les redirections | Trace redirects |
| check_domain_age | Vérifier l'âge du domaine | Check domain age |
| offline | Hors connexion | Offline |
| offline_note | Hors connexion : l'analyse locale fonctionne, les listes peuvent être anciennes. | Offline: local analysis works, the lists may be out of date. |
| not_openable | Ce type de contenu ne peut pas être ouvert depuis LookFirst. Vous pouvez seulement le copier. | This type of content cannot be opened from LookFirst. You can only copy it. |
| not_analysed_banner | Pas encore analysé | Not analysed yet |
| not_analysed_body | Contenu décodé tel quel. Aucune vérification n'a été faite. | Decoded as is. No check was made. |
| result_title | Résultat | Result |
| result_scan_title | Résultat du scan | Scan result |
| unreadable_title | Contenu non analysable | Content cannot be analysed |
| image_title | Image | Image |
| exact_content_title | Contenu exact | Exact content |
| scheme_port_note | %1$s · port %2$s | %1$s · port %2$s |
| ok | OK | OK |

### 7.2 Levels and disclaimer

| Key | FR | EN |
|---|---|---|
| level_dangerous | Danger | Dangerous |
| level_suspicious | Suspect | Suspicious |
| level_caution | À vérifier | Check carefully |
| level_no_risk_signal | Aucun signal de risque détecté | No risk signal detected |
| no_risk_disclaimer | Cela ne garantit rien : les listes ne connaissent que les menaces déjà signalées. | This guarantees nothing: lists only know threats that were already reported. |

### 7.3 Reasons (stable code, short label, sentence)

Severity: D = DANGEROUS, S = SUSPICIOUS, C = CAUTION, I = info. The level is the maximum severity of the reasons; `info` reasons (REDIRECT_FINAL) are displayed but never enter the level calculation. `shortLabel` is the compact row text (4.2 item 4, mock-ups); `sentence` is the full text (4.2 item 2 for the most severe, and the expanded row). Counted sentences (marked P) are plurals, see 7.10. Key names: `reason_<code>_short` and `reason_<code>`.

| # | Code | Sev | shortLabel FR | shortLabel EN | Sentence FR | Sentence EN |
|---|---|---|---|---|---|---|
| 1 | REPUTATION_LISTED | D | Adresse signalée | Reported address | Cette adresse figure dans une liste publique de sites signalés (%1$s). | This address is on a public list of reported sites (%1$s). |
| 2 | DANGEROUS_SCHEME | D | Commande, pas une page | Command, not a page | Ce contenu est un programme ou une commande (%1$s:), pas une page web. Il pourrait exécuter du code. | This content is a program or a command (%1$s:), not a web page. It could run code. |
| 3 | BIDI_IN_HOST | D | Sens de lecture inversé | Reversed reading direction | L'adresse contient un caractère invisible qui inverse le sens de lecture : elle peut s'afficher autrement qu'elle n'est réellement. | The address contains an invisible character that reverses reading direction, so it may display differently from what it really is. |
| 4 | BRAND_LOOKALIKE | S | Imite un site connu | Imitates a known site | L'adresse ressemble à %1$s mais utilise un « %2$s » %3$s. | The address looks like %1$s but uses a %3$s "%2$s". |
| 5 | MIXED_SCRIPT | S | Alphabets mélangés | Mixed alphabets | Le nom mélange plusieurs alphabets (%1$s). C'est une technique courante pour imiter un autre site. | The name mixes several alphabets (%1$s). This is a common way to imitate another site. |
| 6 | USERINFO_AT | S | « @ » trompeur | Misleading "@" | L'adresse contient un « @ » : ce qui précède n'est pas le site. Le vrai site est %1$s. | The address contains an "@": what comes before it is not the site. The real site is %1$s. |
| 7 | IP_HOST | S | Adresse en chiffres | Numeric address | L'adresse est une suite de chiffres (%1$s) et non un nom de site. Les services légitimes l'utilisent rarement. | The address is a string of numbers (%1$s) rather than a site name. Legitimate services rarely do this. |
| 8 | HIDDEN_CHARS (P) | S | Caractères invisibles | Hidden characters | Plurals, 7.10 | Plurals, 7.10 |
| 9 | PAYMENT_MISMATCH | S | Paiement incohérent | Inconsistent payment | Les informations de paiement sont incohérentes (%1$s). Vérifiez le bénéficiaire avant tout virement. | The payment details are inconsistent (%1$s). Check the beneficiary before any transfer. |
| 10 | OTPAUTH_ENROL | S | Ajout de double authentification | Adds a two-factor account | Ce code ajoute un compte de double authentification (%1$s). Un service légitime ne l'affiche que pendant que vous le configurez vous-même. | This code adds a two-factor account (%1$s). A legitimate service only shows it while you are setting it up yourself. |
| 11 | SHORTENER | C | Lien raccourci | Shortened link | Ce lien raccourci cache sa vraie destination. | This shortened link hides its real destination. |
| 12 | PLAIN_HTTP | C | Connexion non chiffrée | Unencrypted connection | La connexion n'est pas chiffrée (http) : ce que vous saisissez peut être lu par d'autres. | The connection is not encrypted (http): what you type can be read by others. |
| 13 | LONG_URL | C | Adresse très longue | Very long address | L'adresse est très longue : le nom du site est noyé dans le reste. | The address is very long: the site name is buried in the rest. |
| 14 | UNUSUAL_PORT | C | Port inhabituel | Unusual port | L'adresse utilise un port inhabituel (%1$s). | The address uses an unusual port (%1$s). |
| 15 | OPEN_WIFI | C | Wi-Fi sans mot de passe | Wi-Fi without password | Ce réseau Wi-Fi n'a pas de mot de passe : tout le monde peut observer ce que vous y faites. | This Wi-Fi network has no password: anyone can observe what you do on it. |
| 16 | UNKNOWN_SCHEME | C | Ouvre une application | Opens an app | Ce code ouvre une application (%1$s:), pas un site. LookFirst ne l'ouvre pas. | This code opens an app (%1$s:), not a site. LookFirst does not open it. |
| 17 | NON_BROWSER_HANDLER | C | Ouvert par une autre appli | Opened by another app | Une application autre que le navigateur peut ouvrir ce lien. | An app other than the browser may open this link. |
| 18 | LISTS_STALE (P) | C | Listes anciennes | Old lists | Plurals, 7.10 | Plurals, 7.10 |
| 19 | LISTS_MISSING | C | Aucune liste | No list | Aucune liste de réputation n'est installée : l'adresse n'a pas été comparée aux sites signalés. | No reputation list is installed: the address was not compared with reported sites. |
| 20 | UNPARSABLE | C | Contenu incompris | Content not understood | Ce contenu n'a pas pu être compris. Il est affiché tel quel. | This content could not be understood. It is shown as is. |
| 21 | REDIRECT_HTTP (after tracing) | S | Redirection non chiffrée | Unencrypted redirect | Ce lien redirige vers une adresse non chiffrée (%1$s). | This link redirects to an unencrypted address (%1$s). |
| 22 | REDIRECT_FINAL (after tracing, P) | I | Destination finale | Final destination | Plurals, 7.10 | Plurals, 7.10 |
| 23 | DOMAIN_RECENT (after RDAP, P) | S under 30 days, C from 30 to 89 days | Domaine récent | Recent domain | Plurals, 7.10 | Plurals, 7.10 |
| 24 | BIDI_IN_URL | D | Sens de lecture inversé | Reversed reading direction | Le lien contient un caractère invisible qui inverse le sens de lecture : une partie peut s'afficher autrement qu'elle n'est réellement. | The link contains an invisible character that reverses reading direction, so part of it may display differently from what it really is. |
| 25 | WHOLE_SCRIPT_CONFUSABLE | S | Alphabet trompeur | Look-alike alphabet | Le nom %2$s est écrit entièrement dans l'alphabet %1$s mais se lit comme des lettres latines ordinaires. C'est une technique courante pour imiter un autre site. | The name %2$s is written entirely in the %1$s alphabet but reads as ordinary Latin letters. This is a common way to imitate another site. |
| 26 | CRYPTO_BAD_CHECKSUM | S | Adresse invalide | Invalid address | Cette adresse crypto échoue à son contrôle intégré : elle est mal saisie ou falsifiée. Les fonds envoyés pourraient être perdus. | This crypto address fails its built-in check: it is mistyped or forged. Funds sent to it may be lost. |
| 27 | OTPAUTH_ODD_PARAMS | C | Réglages inhabituels | Unusual two-factor settings | Ce code de double authentification utilise des réglages inhabituels (%1$s). Les services légitimes le font rarement. | This two-factor code uses unusual settings (%1$s). Legitimate services rarely do this. |
| 28 | USSD_CODE | S | Code de commande | Phone command | Ce numéro contient « * » ou « # » : c'est un code de commande (%1$s) que le composeur exécuterait, pas un numéro de téléphone. | This number contains "*" or "#": it is a command code (%1$s) that the dialer would run, not a phone number. |
| 29 | PREMIUM_NUMBER | S | Numéro surtaxé | Premium-rate number | %1$s ressemble à un numéro surtaxé : l'appeler ou lui écrire peut coûter bien plus cher que d'habitude. | %1$s looks like a premium-rate number: calling or texting it can cost much more than usual. |
| 30 | POSSIBLE_PREMIUM_NUMBER | C | Numéro peut-être surtaxé | Possible premium rate | %1$s est un numéro surtaxé dans certains pays. Vérifiez le coût avant d'appeler ou d'écrire. | %1$s is a premium-rate number in some countries. Check the cost before calling or texting. |
| 31 | SHORT_NUMBER | C | Numéro court | Short number | %1$s est un numéro très court, souvent un service payant. Vérifiez le coût avant d'appeler. | %1$s is a very short number, often a paid service. Check the cost before calling. |
| 32 | SMS_SHORT_CODE | C | SMS vers un numéro court | SMS to a short number | Ce SMS part vers un numéro court (%1$s), souvent un service payant. | This SMS goes to a short number (%1$s), often a paid service. |
| 33 | SMS_PREFILLED_BODY | C | Message prérempli | Prefilled message | Le texte du SMS est déjà écrit. Lisez-le avant de l'envoyer : LookFirst n'envoie rien. | The text of the SMS is already written. Read it before sending it: LookFirst sends nothing. |
| 34 | MAIL_PREFILLED | C | E-mail prérempli | Prefilled email | L'e-mail contient un texte tout prêt ou un destinataire caché (cci). Lisez-le avant de l'envoyer : LookFirst n'envoie rien. | The email has a ready-made text or a hidden recipient (bcc). Read it before sending it: LookFirst sends nothing. |
| 35 | NON_ASCII_DIGITS | S | Chiffres inhabituels | Unusual digits | Le numéro utilise des chiffres d'un autre système d'écriture, ce qui peut cacher le vrai numéro. | The number uses digits from another writing system, which can hide the real number. |
| 36 | URL_ODD_FORMAT | C | Adresse mal écrite | Oddly written address | L'adresse n'est pas écrite comme un navigateur l'attend (schéma absent, barres inhabituelles ou antislash). Elle peut être lue autrement que ce qui est affiché. | The address is not written the way a browser expects (missing scheme, unusual slashes or backslashes). It may be read differently from what is shown. |
| 37 | URL_HOST_ENCODED | S | Nom de site codé | Encoded site name | Le nom du site est écrit avec des codes « % », dont un nom de site n'a jamais besoin. Cela cache le nom au lecteur. | The site name is written with "%" codes, which a site name never needs. This hides the name from a reader. |
| 38 | URL_MALFORMED | S | Adresse invalide | Invalid address | L'adresse est mal formée (nom vide ou trop long, caractères interdits, port invalide ou espaces). Des applications différentes peuvent la lire différemment. | The address is malformed (empty or too long name, forbidden characters, invalid port or spaces). Different apps may read it differently. |
| 39 | WIFI_WEP | C | Chiffrement Wi-Fi faible | Weak Wi-Fi encryption | Ce réseau utilise WEP, un chiffrement cassé : les personnes proches peuvent lire ce que vous y faites. | This network uses WEP, an encryption that is broken: others nearby can read what you do on it. |
| 40 | WIFI_UNKNOWN_SECURITY | C | Sécurité Wi-Fi inconnue | Unknown Wi-Fi security | Le type de sécurité de ce réseau n'a pas été reconnu. Vérifiez-le avant de vous connecter. | The security type of this network was not recognised. Check it before you connect. |
| 41 | ANALYSIS_INCOMPLETE | C | Analyse incomplète | Analysis incomplete | Le contenu n'a pas pu être analysé en entier : certains contrôles n'ont pas été faits. | The content could not be fully analysed: some checks were not made. |
| 42 | LISTS_UNAVAILABLE | C | Listes illisibles | Lists unreadable | Les listes de réputation n'ont pas pu être lues : l'adresse n'a pas été comparée aux sites signalés. | The reputation lists could not be read: the address was not compared with reported sites. |
| 43 | REDIRECT_PRIVATE (after tracing) | S | Redirection vers réseau local | Redirect to local network | Ce lien redirige vers une adresse d'un réseau local ou réservé (%1$s). LookFirst ne l'a pas contactée. | This link redirects to an address on a local or reserved network (%1$s). LookFirst did not contact it. |
| 43b | REDIRECT_PRIVATE, scanned address itself (no hop) | S | Redirection vers réseau local | Redirect to local network | Ce lien pointe vers une adresse d'un réseau local ou réservé (%1$s). LookFirst ne l'a pas contactée. | This link points to an address on a local or reserved network (%1$s). LookFirst did not contact it. |
| 44 | REDIRECT_EXCESSIVE (after tracing) | C | Trop de redirections | Too many redirects | Ce lien enchaîne trop de redirections ou tourne en boucle : LookFirst a cessé de le suivre. | This link chains too many redirects or loops: LookFirst stopped following it. |
| 45 | DOMAIN_UNKNOWN (after RDAP, 404) | I | Domaine inconnu | Unknown domain | Le registre ne connaît pas ce nom de domaine : rien ne peut être dit de son âge. | The registry does not know this domain name: nothing can be said about its age. |

Added by LF-18 (codes created after this table, rows 24 to 42): severities are those of `SignalCode`. Typed details are turned into words by the UI (`payment_problem_*`, `otp_param_*`, `script_*`), never printed as code names. BRAND_LOOKALIKE has one sentence per kind (`reason_brand_lookalike_homoglyph`, `_typo`, `_combined`; the character is not known to the UI, so the sentence names the brand only) and MIXED_SCRIPT / WHOLE_SCRIPT_CONFUSABLE name the label (evidence, `%2$s`) besides the alphabets (`%1$s`). The evidence of a sentence is bold and underlined, cut at 80 characters, its invisible characters shown as tokens. Strings: `res/values/reasons.xml` and `res/values-fr/reasons.xml`; a test checks that every `SignalCode` has its texts in both languages.

Phrasing rules: one sentence, says what was seen, then what it means; no "malware", "hack", "virus" without being the evidence; never "sûr/safe"; never an order in capitals. A shortLabel is 4 words at most, never a sentence, never an order.

### 7.4 Optional actions, dialogs and errors

| Key | FR | EN |
|---|---|---|
| warn_trace_title | Suivre les redirections ? | Trace redirects? |
| warn_trace_body | LookFirst va contacter ce lien pour voir où il mène. Le site verra votre adresse IP et saura que son QR code a été scanné. Rien n'est ouvert ni installé. | LookFirst will contact this link to see where it leads. The site will see your IP address and learn that its QR code was scanned. Nothing is opened or installed. |
| warn_rdap_title | Vérifier l'âge du domaine ? | Check domain age? |
| warn_rdap_body | LookFirst demande à un registre public (RDAP, %2$s) depuis quand ce nom de domaine existe. Seul le nom de domaine (%1$s) est envoyé, pas l'adresse complète. Le registre voit votre adresse IP. Le site lui-même n'est pas contacté. | LookFirst asks a public registry (RDAP, %2$s) how long this domain name has existed. Only the domain name (%1$s) is sent, not the full address. The registry sees your IP address. The site itself is not contacted. |
| age_check | Vérifier l'âge du domaine | Check domain age |
| age_note_no_registry_list | Indisponible : la liste des registres est illisible. | Unavailable: the list of registries could not be read. |
| age_note_no_domain | Disponible pour les liens avec un nom de domaine uniquement. | Only available for links with a domain name. |
| age_note_no_server | Aucun registre connu pour ce type de domaine. | No known registry for this kind of domain. |
| age_result_unknown | Ce domaine est inconnu du registre. | The registry does not know this domain. |
| age_result_registered (plurals) | Domaine enregistré le %1$s (il y a %2$d jour(s)). | Domain registered on %1$s (%2$d day(s) ago). |
| warn_continue | Continuer | Continue |
| check_loading | Vérification en cours… | Checking… |
| check_failed | Vérification impossible (délai dépassé ou réseau indisponible). Le verdict reste celui de l'analyse locale. | Check not possible (timed out or network unavailable). The verdict remains the local analysis. |
| trace_https_only | Disponible pour les liens https uniquement. | Only available for https links. |
| trace_port_only | Disponible pour les liens sur le port https standard uniquement. | Only available for links on the standard https port. |
| check_offline_note | Hors connexion | Offline |
| dialog_open_title | Ouvrir quand même ? | Open anyway? |
| dialog_open_danger_body | LookFirst a trouvé des signaux de danger sérieux. Si vous continuez, c'est à vos risques. | LookFirst found serious danger signals. If you continue, you do so at your own risk. |
| dialog_open_suspicious_body | LookFirst a trouvé des signaux de risque. Si vous continuez, c'est à vos risques. | LookFirst found risk signals. If you continue, you do so at your own risk. |
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
| lists_chip_stale | Listes anciennes (%1$s) : touchez pour mettre à jour | Old lists (%1$s): tap to update |
| lists_chip_missing | Aucune liste : la réputation ne sera pas vérifiée | No list: reputation will not be checked |

Error handling rule: typed errors map to these strings; `Throwable.message` is never displayed.

### 7.5 Action labels by content type

| Key | FR | EN | Used for |
|---|---|---|---|
| open_in_browser | Ouvrir dans le navigateur | Open in browser | http / https at CAUTION or NO_RISK_SIGNAL |
| open_dialer | Ouvrir le composeur | Open the dialer | `tel:` |
| write_sms | Écrire le SMS | Write the SMS | `sms:` / `smsto:` |
| write_email | Écrire l'e-mail | Write the email | `mailto:` |
| open_in_maps | Ouvrir dans Plans | Open in Maps | `geo:` |
| add_wifi_network | Ajouter ce réseau | Add this network | Wi-Fi, API 30+ |
| open_wifi_settings | Ouvrir les réglages Wi-Fi | Open Wi-Fi settings | Wi-Fi, API < 30 |
| open_android_settings | Ouvrir les réglages Android | Open Android settings | camera denied |
| allow_camera | Autoriser la caméra | Allow camera | camera rationale |
| copy_only | Copier seulement | Copy only | deep link / unknown scheme (7.1) |
| get_started | Commencer | Get started | first launch |

### 7.6 Type labels

| Key | FR | EN |
|---|---|---|
| type_url_https | Type : lien web (https) | Type: web link (https) |
| type_url_http | Type : lien web (http, non chiffré) | Type: web link (http, unencrypted) |
| type_tel | Type : numéro de téléphone | Type: phone number |
| type_sms | Type : SMS | Type: SMS |
| type_mail | Type : e-mail | Type: email |
| type_geo | Type : position géographique | Type: geographic location |
| type_wifi | Type : réseau Wi-Fi | Type: Wi-Fi network |
| type_otpauth | Type : double authentification (otpauth) | Type: two-factor setup (otpauth) |
| type_payment | Type : paiement (SEPA) | Type: payment (SEPA) |
| type_crypto | Type : adresse crypto | Type: crypto address |
| type_applink | Type : lien d'application | Type: app link |
| type_dangerous_scheme | Type : commande ou programme | Type: command or program |
| type_text | Type : texte | Type: text |
| type_unknown | Type : inconnu | Type: unknown |

### 7.7 First launch

| Key | FR | EN |
|---|---|---|
| onboarding_tagline | Regardez avant d'ouvrir. | Look before you open. |
| onboarding_nothing_opens | Rien ne s'ouvre tout seul. Vous choisissez toujours. | Nothing opens by itself. You always choose. |
| onboarding_privacy | Tout est analysé sur votre téléphone. Les adresses scannées ne sont envoyées nulle part, sauf si vous lancez une vérification facultative. | Everything is analysed on your phone. Scanned addresses are not sent anywhere, unless you start an optional check. |
| onboarding_lists | Les listes de sites signalés sont téléchargées en tâche de fond. | Lists of reported sites are downloaded in the background. |
| onboarding_auto_update | Mise à jour automatique | Automatic update |
| onboarding_changeable | Modifiable dans Réglages. | You can change this in Settings. |
| onboarding_no_guarantee | Aucun scan ne garantit rien : un site nouveau peut ne figurer dans aucune liste. | No scan guarantees anything: a new site may be on no list. |

### 7.8 Settings and About

| Key | FR | EN |
|---|---|---|
| settings_title | Réglages | Settings |
| settings_lists_header | Listes de réputation | Reputation lists |
| settings_lists_version | Version %1$d · %2$s | Version %1$d · %2$s |
| settings_last_attempt_ok | Dernière tentative : réussie | Last attempt: succeeded |
| settings_last_attempt_failed | Dernière tentative : échouée | Last attempt: failed |
| settings_last_attempt_none | Aucune tentative pour l'instant | No attempt yet |
| settings_update_now | Mettre à jour maintenant | Update now |
| settings_auto_update | Mise à jour automatique | Automatic update |
| settings_unmetered_only | Réseau non limité uniquement | Unmetered network only |
| lists_updating | Mise à jour en cours | Updating |
| lists_update_failed | Échec de la mise à jour : la liste précédente est conservée. | Update failed: the previous list is kept. |
| lists_rejected | Liste refusée : la vérification de signature a échoué. La liste précédente est conservée. | List rejected: the signature check failed. The previous list is kept. |
| lists_offline | Hors connexion | Offline |
| lists_up_to_date | Déjà à jour. | Already up to date. |
| settings_about_link | À propos et confidentialité | About and privacy |
| settings_app_version | Version de l'application | App version |
| about_title | À propos et confidentialité | About and privacy |
| about_local_header | Ce qui reste sur votre téléphone | What stays on your phone |
| about_local_body | Le contenu des QR codes, l'analyse et les résultats. LookFirst n'enregistre pas vos scans. | QR code content, the analysis and the results. LookFirst does not record your scans. |
| about_network_header | Ce qui utilise le réseau | What uses the network |
| about_net_lists | Mise à jour des listes (automatique) | List update (automatic) |
| about_net_lists_who | Hébergeur des listes : votre adresse IP seulement. | List host: your IP address only. |
| about_net_redirects | Redirections (sur demande) | Redirects (on request) |
| about_net_redirects_who | Le site scanné voit votre adresse IP et sait que son QR code a été scanné. | The scanned site sees your IP address and learns that its QR code was scanned. |
| about_net_rdap | Âge du domaine (sur demande) | Domain age (on request) |
| about_net_rdap_who | Registre RDAP : votre adresse IP et le nom de domaine. | RDAP registry: your IP address and the domain name. |
| about_no_guarantee_header | Ce que LookFirst ne garantit pas | What LookFirst does not guarantee |
| about_no_guarantee_body | Un site neuf ou non signalé peut être malveillant. Restez vigilant. | A new or unreported site can be malicious. Stay alert. |
| about_licences | Licences | Licences |

### 7.9 Accessibility descriptions (section 8)

| Key | FR | EN |
|---|---|---|
| a11y_camera_preview | Aperçu de la caméra. Pointez un QR code. | Camera preview. Point at a QR code. |
| a11y_scan_paused | Scan en pause | Scan paused |
| a11y_back | Retour | Back |
| a11y_settings | Réglages | Settings |
| a11y_torch | Lampe torche | Torch |
| a11y_banner | %1$s. %2$s | %1$s. %2$s |
| a11y_level_changed | Niveau passé à %1$s | Level changed to %1$s |
| a11y_risk_signal_n | Signal de risque %1$d sur %2$d : %3$s | Risk signal %1$d of %2$d: %3$s |
| a11y_technical_form | Forme technique : %1$s | Technical form: %1$s |
| a11y_content_collapsed | Contenu exact, réduit, bouton, double-toucher pour afficher | Exact content, collapsed, button, double-tap to expand |
| a11y_content_expanded | Contenu exact, affiché, bouton, double-toucher pour réduire | Exact content, expanded, button, double-tap to collapse |
| a11y_invisible_char | Caractère %1$s, %2$s | Character %1$s, %2$s |
| a11y_bidi_override | inversion du sens de lecture | reading direction reversal |
| a11y_zero_width | espace de largeur nulle | zero-width space |
| a11y_null_char | caractère nul | null character |
| a11y_line_break | saut de ligne | line break |
| a11y_masked | masqué | hidden |
| a11y_open_browser | Ouvrir dans le navigateur le lien vers %1$s | Open the link to %1$s in the browser |
| a11y_open_anyway | Ouvrir quand même le lien vers %1$s, après confirmation | Open the link to %1$s anyway, after confirmation |
| a11y_open_dialer | Ouvrir le composeur avec ce numéro, sans appeler | Open the dialer with this number, without calling |
| a11y_copy_exact | Copier le contenu exact | Copy the exact content |
| a11y_cancel_resume | Annuler et reprendre le scan | Cancel and resume scanning |
| a11y_lookalike_letter | lettre %1$s, %2$s | letter %1$s, %2$s |

### 7.10 Plurals

| Key | Quantity | FR | EN |
|---|---|---|---|
| more_signals | one | Voir %1$d autre signal | See %1$d more signal |
| more_signals | other | Voir %1$d autres signaux | See %1$d more signals |
| age_days | one | il y a %1$d jour | %1$d day ago |
| age_days | other | il y a %1$d jours | %1$d days ago |
| age_today | (plain string) | aujourd'hui | today |
| chars_more | one | … (%1$d caractère de plus non affiché) | … (%1$d more character not shown) |
| chars_more | other | … (%1$d caractères de plus non affichés) | … (%1$d more characters not shown) |
| reason_hidden_chars | one | Le contenu cache %1$d caractère invisible. Il est rendu visible ci-dessous. | The content hides %1$d invisible character. It is made visible below. |
| reason_hidden_chars | other | Le contenu cache %1$d caractères invisibles. Ils sont rendus visibles ci-dessous. | The content hides %1$d invisible characters. They are made visible below. |
| reason_lists_stale | one | Les listes de réputation ont %1$d jour : des menaces récentes peuvent manquer. | The reputation lists are %1$d day old: recent threats may be missing. |
| reason_lists_stale | other | Les listes de réputation ont %1$d jours : des menaces récentes peuvent manquer. | The reputation lists are %1$d days old: recent threats may be missing. |
| reason_redirect_final | one | Après %1$d redirection, la destination finale est %2$s. | After %1$d redirect, the final destination is %2$s. |
| reason_redirect_final | other | Après %1$d redirections, la destination finale est %2$s. | After %1$d redirects, the final destination is %2$s. |
| reason_domain_recent | one | Ce nom de domaine a été enregistré il y a %1$d jour : les sites frauduleux sont souvent récents. | This domain name was registered %1$d day ago: fraudulent sites are often recent. |
| reason_domain_recent | other | Ce nom de domaine a été enregistré il y a %1$d jours : les sites frauduleux sont souvent récents. | This domain name was registered %1$d days ago: fraudulent sites are often recent. |

The "%1$s" in `lists_age`, `lists_age_short`, `lists_chip_stale` and `settings_lists_version` is the output of `age_days` or `age_today`.

## 8. Accessibility (WCAG 2.2 AA)

### Contrast
- Text and icons on the banner: >= 4.5:1 for text, >= 3:1 for icons and component borders (SC 1.4.3, 1.4.11). Use a dark text on a light tinted container (or white on a dark one), never coloured text on a coloured container. Concrete colour tokens are checked with a contrast tool when the theme is written; the spec requires verification in both light and dark themes.
- Highlighted evidence uses bold + underline, not colour alone (SC 1.4.1).
- The four levels must be distinguishable in greyscale: icon silhouette + label differ.

### Touch targets
- All buttons, expanders and icon buttons >= 48 x 48 dp. The three action buttons are a fixed vertical stack, 48 dp each, 8 dp apart, full width, in the order Cancel, Copy, Open at every level.
- Cancel and Open are never adjacent: Copy always sits between them.

### Text size
- All text in `sp`; layout tested at font scale 1.0, 1.5 and 2.0. The action stack is pinned outside the scrollable area; the zone above it scrolls when the fixed items do not fit (4.2). No truncation of the host line (it wraps on several lines, breaking at dots, never ellipsized).
- "Without scrolling" is only guaranteed at font scale 100% and a non-IDN host; beyond, the central zone scrolls and the stack stays pinned.
- Host: >= 24 sp bold; body 16 sp; secondary 14 sp minimum.

### TalkBack
Reading order = visual order:
1. Banner: "Suspect. L'adresse ressemble à paypal.com mais utilise un a cyrillique." (heading, announced with `liveRegion = polite` once when the screen appears, not repeated).
2. "Ce lien ouvrira" then the host, read letter by letter on demand; for an IDN: the Unicode form then "Forme technique : x n dash dash …".
3. Reasons, one list item each, "signal de risque 1 sur 3 : …".
4. Content expander: "Contenu exact, réduit, bouton, double-toucher pour afficher".
5. List age.
6. Optional checks, then the actions as the last focus group, in stack order (Cancel, Copy, Open).
Rules: the icon is decorative (`contentDescription = null`) because the label carries the meaning; invisible characters are exposed as text ("caractère U+202E, inversion du sens de lecture"); initial focus on the banner heading; actions have explicit descriptions (7.9); after Copy the snackbar is announced; masked secrets are announced as "masqué", never read. A level change after an optional check is announced once (3.7).
- The camera view has the description "Aperçu de la caméra. Pointez un QR code." plus an announcement "Scan en pause" when a result is shown.
- Dialogs trap focus; initial focus is on Cancel.

### Compose implementation constraints
- Initial focus: a `FocusRequester` attached to the banner heading, requested from a `LaunchedEffect(verdict.payloadId)` so it fires once per displayed payload, not on every recomposition.
- Confirmation dialogs and warning sheets: initial focus on Cancel (`FocusRequester` + `LaunchedEffect` on the dialog's first composition); focus is trapped inside; closing returns focus to the button that opened it.
- Invisible characters: the visible token (for example `<U+202E>`) is rendered as text with a distinct background, and carries `contentDescription = "caractère U+202E, inversion du sens de lecture"` (`a11y_invisible_char` + the matching description key); the raw character itself is never rendered.
- The `liveRegion = polite` announcing the banner is keyed on the `Verdict` (payload id + level): announced once per verdict, and once more only if an optional check changes the level; recomposition or a configuration change never re-announces.
- The action stack lives in a pinned container outside the scrolling `Column`/`LazyColumn`; nothing in the scrolling zone can overlap it (reserve its height with insets/padding, not with a magic number).

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
| D16 | Stale lists | CAUTION reason above 14 days; an initial signed bundle ships in the APK so lists are never absent on first launch (ADR-0005) | Phishing domains live hours to days; the APK bundle covers the first run and airplane mode |
| D17 | SUSPICIOUS and DANGEROUS | Open kept, behind one confirmation dialog (Cancel default) | Removing it contradicts "the user decides" and would push users to copy-paste around the app; aligned with threat model T-UI-2 |
| D18 | "Vérifié : …" line | Reworded "Contrôles effectués : …" / "Checks run: …" | Lists what was checked without sounding like a seal of approval |
