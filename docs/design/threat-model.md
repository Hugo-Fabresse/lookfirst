# LookFirst threat model

Status: proposed (LF-1). Method: STRIDE over the data flow in `architecture.md`.

## Assets and attackers

Assets: the user's device and accounts (credentials, OTP seeds, money), the user's IP and location, the integrity of the verdict (the user's trust), the local history.

Attackers:
- **A1, printer:** sticker on a parking meter, flyer, poster, or a QR in a phishing e-mail or PDF. Controls the QR content and the destination site. Cheap, mass scale. Main adversary.
- **A2, targeted:** crafts a payload against the analyser itself (evasion, crash, confusing the verdict screen).
- **A3, network:** on-path attacker (public Wi-Fi) when lists are downloaded.
- **A4, supply chain:** compromises a list source or the hosting of the bundle.
- **A5, other app on the device:** intercepts Intents or tries to read app storage.

Out of scope: rooted or compromised device, malicious OS, physical coercion. A legitimate QR physically overlaid by a fake one is A1: the app can only judge content.

Trust boundaries: (1) QR pixels -> decoder; (2) decoded string -> parser; (3) app -> system (Intents, clipboard, Wi-Fi); (4) app -> network; (5) app -> local storage.

Status legend: **M** mitigated by design; **A** accepted explicitly (reason given). Letters in the text refer to STRIDE: Spoofing, Tampering, Repudiation, Information disclosure, Denial of service, Elevation of privilege.

## QR content (boundaries 1 and 2)

| ID | Threat | Counter-measure | Status |
|---|---|---|---|
| T-CNT-1 | Huge payload (QR holds up to ~3 KB) exhausting memory or UI (D) | Hard cap on raw length (4096 chars, above = rejected with a message); UI truncates the middle, full text on demand; analysers have bounded cost (edit distance only on hostname labels, host max 253 chars) | M |
| T-CNT-2 | Control characters (NUL, CR/LF, ESC, U+2028) for UI/log injection or field splitting (T, S) | Normalise NFC; C0/C1 and format characters are escaped visibly and count as a signal; raw content never logged | M |
| T-CNT-3 | Bidi override/isolate (U+202E, U+2066-2069) to display a spoofed or reversed URL (S) | Detected and shown as `<U+202E>`; inside a host or URL it raises `DANGEROUS`; decoded content rendered in an isolated LTR container | M |
| T-CNT-4 | Homoglyph hosts (Cyrillic `а` for Latin `a`), mixed scripts, fullwidth forms (S) | Convert host with an embedded UTS #46 implementation (ICU4J or equivalent; `java.net.IDN` is IDNA2003 and `android.icu.text.IDNA` needs API 24+, minSdk is 23) to ASCII and Unicode forms; mixed-script and confusable check (UTS #39 subset); always display both forms | M (heuristic, see T-CNT-6) |
| T-CNT-5 | Punycode `xn--` hosts hiding the real name (S) | Decode, show decoded form with a reason; invalid punycode = `SUSPICIOUS` | M |
| T-CNT-6 | Look-alikes and brand-new malicious domains that no list knows (S) | Edit-distance and keyword heuristics vs the shipped brand list. New domains can slip through | A: a local-only tool cannot know unreported domains; the UI says "no risk signal detected", never "safe" |
| T-CNT-7 | Parsing differentials: `https://paypal.com@evil.tld`, backslashes, `//`, userinfo, decimal/octal/hex IPs, trailing dots, odd ports (S) | One strict parser (RFC 3986 plus WHATWG-style normalisation) for analysis and for opening; the normalised URL that was analysed is the one opened, passed to the browser in canonical form (punycode host, percent-encoded URL) and `Uri.getHost()` is re-checked equal to the analysed host just before the Intent; userinfo and numeric hosts are signals | M |
| T-CNT-8 | Shorteners hide the destination (S) | Embedded shortener list, flagged `CAUTION`; optional redirect tracing | M (resolution is optional by D9) |
| T-CNT-9 | Payloads built to crash or stall the analyser: nested encodings, giant labels, regex DoS (D) | No backtracking-prone regex on untrusted input; analysis on a background dispatcher with a timeout; timeout = `CAUTION` "could not fully analyse" | M |
| T-CNT-10 | Plain-text QR with social engineering ("call this number", fake support) (S) | Text with phone numbers, scheme-less URLs or crypto addresses gets `CAUTION`; text is never auto-linkified | M |

## Dangerous schemes and opening (boundary 3)

| ID | Threat | Counter-measure | Status |
|---|---|---|---|
| T-SCH-1 | `intent://...#Intent;component=...;end` launching arbitrary activities or injecting extras (E) | Never call `Intent.parseUri` on scan data. `intent:` is `DANGEROUS`, Copy only | M |
| T-SCH-2 | `javascript:`, `data:`, `vbscript:` (E) | `DANGEROUS`, not openable | M |
| T-SCH-3 | `file:`, `content:`, `android-app:` (local file or provider access) (I, E) | Blocked, Copy only | M |
| T-SCH-4 | App deep links (`myapp://pay?...`, `market://`, wallet or messaging apps) triggering in-app actions, confused deputy (E) | Allow-list of openable schemes: `http`, `https`, `tel`, `sms`/`smsto`, `mailto`, `geo`, `WIFI` (internal). `otpauth` is Copy only (T-OTP). Any other scheme (deep links, D12): `CAUTION`, scheme shown, **Copy only**, no Open button and no setting to enable one | M |
| T-SCH-5 | `tel:` with premium-rate numbers or USSD codes (`*#06#`, `*21*...#`) (E) | `ACTION_DIAL` only, never `ACTION_CALL`; `*`/`#` sequences and short codes flagged `SUSPICIOUS`; premium-rate detection is best effort | M (premium detection partial) |
| T-SCH-6 | `sms:` with prefilled body subscribing to paid services or leaking data (E) | `ACTION_SENDTO` with `smsto:`, user must press send, body shown in the verdict, short codes flagged | M |
| T-SCH-7 | `mailto:` with bcc, attach or body tricks (I) | Parse into fields, show recipient and subject, ignore attachments, `ACTION_SENDTO` without attachment extras | M |
| T-SCH-8 | Action triggered without confirmation (E) | ADR-0003: only `ActionExecutor` fires Intents, called solely from a button handler; an architecture test (Konsist or ArchUnit) forbids `startActivity` outside `data.launcher`; no auto-open setting exists | M |
| T-SCH-9 | Badly built or interceptable Intent: implicit Intent, wrong category, another app claiming the scheme (A5) | http(s): `ACTION_VIEW` + `CATEGORY_BROWSABLE` with a scheme-checked Uri; non-browser types go through `Intent.createChooser` so the target is visible; no secret (Wi-Fi password, OTP seed) in any implicit Intent; no exported components except the launcher activity; no `PendingIntent` | M; a malicious app registered as handler is A: the OS and the user choose the default |
| T-SCH-12 | A verified App Link or a registered handler captures an `https://` `ACTION_VIEW` so a non-browser app opens the URL (E, A5) | Resolve the default browser and `setPackage` on it; if a non-browser handler exists for the URL, show it in the verdict | M |
| T-SCH-10 | A WebView inside LookFirst would run attacker content in our process (E) | No WebView. Custom Tab or external browser only, so the browser's own protections apply. `<queries>` declares `CustomTabsService`; `warmup()`, `mayLaunchUrl()` and any prefetch with scanned data are forbidden (they would contact the site before the user decides) | M |
| T-SCH-11 | Clipboard exposure of copied secrets (I) | Copy is an explicit tap; for secrets (otpauth, Wi-Fi password): confirmation first, `ClipDescription.EXTRA_IS_SENSITIVE` (API 33+, hides the preview), and `clearPrimaryClip()` (API 28+) when the user returns to LookFirst | M; since API 29 a background app cannot read the clipboard, but the foreground app and keyboard/IME can (A, OS limitation) |

## Type-specific payloads

| ID | Threat | Counter-measure | Status |
|---|---|---|---|
| T-OTP | `otpauth://` QR that enrols an attacker-controlled TOTP, or a lure "re-enrol your 2FA"; seed leaking to clipboard or history (S, I) | Parse issuer, account, algorithm, digits, period; warn that a legitimate service shows a setup QR only while the user configures 2FA in their own session; flag issuer/label mismatch and unusual parameters; secret masked in UI, never stored, never logged; Copy only at MVP, with confirmation and the sensitive flag; never an implicit Intent (the URI contains the seed) | M; social-engineering residual A (the app can only warn) |
| T-WIFI | Evil twin or open network in `WIFI:S:..;T:..;P:..;;`, hidden SSID, `nopass`, WEP, EAP configs (S, I) | Parse the standard format including `\` escapes; open/WEP networks `CAUTION`; SSID shown with control chars made visible; API 30+: `ACTION_WIFI_ADD_NETWORKS` (system confirmation, Intent restricted to system packages); below 30: credentials displayed and the Wi-Fi settings opened, nothing configured silently; no Wi-Fi suggestions; EAP data shown, not auto-applied | M |
| T-PAY | Modified payment QR: EPC/SEPA (IBAN, BIC, name, amount, reference), `bitcoin:`/`ethereum:` (T, S) | Show IBAN (grouped), beneficiary name, amount, currency and reference prominently; validate IBAN checksum and EPC structure; flag missing name, invalid crypto address checksum (Base58Check, Bech32, EIP-55), `ethereum:` with contract-call data; always "check recipient and amount with the issuer" | A for a valid but attacker-owned destination: unknowable locally |
| T-APP | QR login or device-pairing lures (QRLJacking style) (S) | URL patterns for pairing/login flows get a specific `CAUTION` reason | M (heuristic) |

## Reputation lists (boundary 4)

| ID | Threat | Counter-measure | Status |
|---|---|---|---|
| T-LST-1 | MITM on the download (A3) | HTTPS only, system TLS validation | M |
| T-LST-2 | Compromised host (Codeberg), CDN or mirror replacing the bundle (A4) | Bundle signed with Ed25519 from the MVP (D13), public key embedded (two keys for rotation), verified before activation. The signing key is never in the app repo and never in the app's build CI (see CI / supply chain) | M |
| T-LST-8 | Rollback: an attacker or stale mirror serves an old, validly signed bundle (T) | Signed manifest with monotonic version and generation date; the app refuses a version lower than or equal to the installed one; list age always displayed | M |
| T-LST-3 | Oversized or malformed download, decompression bomb (D) | Streamed byte cap (e.g. 20 MB compressed, 60 MB expanded), strict format (one valid ASCII/punycode hostname per line, line and total count caps), download to temp file, validate, atomic swap; previous version kept on any failure | M |
| T-LST-4 | Poisoned list: popular domains inserted so the app cries wolf, or real entries removed (T) | Shipped allow-list of top domains is never `DANGEROUS` from a list hit alone; update rejected if it changes size by more than ~50 % or hits more than N top domains | M |
| T-LST-5 | Stale lists give false reassurance (R) | List date shown in every verdict; older than 7 days adds a `CAUTION` reason; a seed list ships in the APK | M |
| T-LST-6 | The update request reveals the user's IP to the list host (I) | No identifier, no cookies, no scan data, generic UA | A: inherent to any download; documented in the privacy notice |
| T-LST-7 | Licence breach when redistributing a source (legal) | Phishing.Database is MIT but does not document its upstream feeds, and the MIT licence of the repository cannot cover upstream data it does not own. Upstream sources are to be identified and any with restrictive terms (e.g. OpenPhish) excluded or filtered out before publication (see `reputation-sources.md`) | To confirm |

## Optional network actions (boundary 4)

| ID | Threat | Counter-measure | Status |
|---|---|---|---|
| T-NET-1 | Redirect tracing tells the attacker that the QR was scanned, from which IP; the server can fingerprint bots and serve a benign page (I) | Optional per-use button, warning before use; no cookies, no body, generic UA, 5 hops and 5 s | A: inherent to the feature, consent explicit (D9). Note that a benign answer to the tracer proves nothing |
| T-NET-2 | Redirect to non-http schemes or to the user's LAN (router admin, SSRF-like, DNS rebinding), or huge responses (E, D) | Filtering `Dns` in OkHttp so the checked IP is the connected IP; blocked ranges: IPv4 private, 0.0.0.0/8, 100.64/10, loopback, link-local, multicast, IPv6 ULA/link-local/loopback, IPv4-mapped; `followRedirects(false)` with manual hops, `Proxy.NO_PROXY`; https only; HEAD or `Range` request; body never read. Same rules for RDAP | M |
| T-NET-3 | RDAP exposes the domain to the registry; untrusted JSON response (I, T) | Only the registrable domain is sent (Public Suffix List and IANA bootstrap shipped in the signed bundle); RDAP URL must be `https`; same filtering client as T-NET-2; response size cap, strict model, only the registration event date is read; failure = no signal, never "safe" | M; leak of the domain to the registry is A, disclosed in the dialog |
| T-NET-4 | Following `http://` hops would need cleartext, and the Network Security Config is global (T) | No cleartext exception anywhere. A hop to `http://` is not followed and is shown as a risk signal | M |
| T-NET-5 | Domain age is an unreliable signal (new is not malicious, old can be compromised) (S) | Weak signal only, never lowers a verdict | M |

## Storage and permissions (boundary 5)

| ID | Threat | Counter-measure | Status |
|---|---|---|---|
| T-STO-1 | History reveals what the user scanned (I) | Off by default; host + level + time only; never otpauth, Wi-Fi secrets or payment data; excluded from backup and device transfer; one-tap delete; auto-expiry | M; protection is the device lock only, A |
| T-STO-2 | Other apps read app storage (A5) | App-private storage, no world-readable modes, `allowBackup="false"` | M (rooted devices out of scope) |
| T-STO-3 | Logs leak scanned content (I) | No scan content in release logs: R8 needs `-assumenosideeffects class android.util.Log { ... }` rules to strip `Log.*` (plain shrinking does not); no crash-reporting SDK | M |
| T-PRM-1 | Broad permissions enlarge blast radius (E) | Only `CAMERA` (runtime) and `INTERNET`; Photo Picker for images; no `QUERY_ALL_PACKAGES` | M |
| T-PRM-2 | Camera kept open or frames kept (I) | CameraX lifecycle-bound, released on stop; frames never saved | M |

## Image scanning (boundary 1)

| ID | Threat | Counter-measure | Status |
|---|---|---|---|
| T-IMG-1 | Booby-trapped image exploiting a native image decoder (E) | Platform decoders only (patched by OS updates), on a background thread; allowed types jpeg, png, webp, heif; no SVG or PDF | M; platform zero-day residual A |
| T-IMG-2 | Decompression bomb: small file, huge dimensions (D) | Read bounds first, refuse above ~25 MP or 20 MB, downsample to ~2048 px; catch `OutOfMemoryError` | M |
| T-IMG-3 | Image with several QRs so the user acts on the wrong one (S) | List every code found; the user selects; each is analysed separately | M |
| T-IMG-4 | Metadata leakage (EXIF) (I) | Image read once in memory, never copied, saved or uploaded | M |

## Verdict and UI

| ID | Threat | Counter-measure | Status |
|---|---|---|---|
| T-UI-1 | Display tricks (very long URL pushing the host off screen, bidi, lookalike emoji) (S) | Host on its own emphasised line first; rest collapsed; escaped rendering | M |
| T-UI-2 | User over-trusts the verdict (R) | Wording rules, permanent caveat line; Cancel is the default action for `SUSPICIOUS` and `DANGEROUS`; Open then needs a second confirmation | M; human factor residual A |
| T-UI-3 | Overlay or tapjacking on Open or Copy (E) | `filterTouchesWhenObscured` on the root view (Compose host view) and on each dialog window; a click that arrives under `FLAG_WINDOW_IS_PARTIALLY_OBSCURED` (API 29+, reported by the activity and dialog windows to `TouchGuard`) is ignored by the action buttons and dialog buttons; `setHideOverlayWindows(true)` on API 31+ (activity and dialog windows) | M; residual A on API 23 to 30: no system call hides overlays, API 23 to 28 never sets the partial flag, and a fully transparent overlay that lets touches through cannot be detected. The actions are Cancel and Copy only (no Open yet), so a hijacked tap copies text the user already sees |
| T-UI-4 | A second QR shown to the camera swaps the content under the user's finger, so the verdict no longer matches the action (S, T) | Verdict immutable: scanning paused while a verdict is displayed; a new code never replaces the displayed content; actions bind to the displayed payload id | M |

## CI / supply chain

| ID | Threat | Counter-measure | Status |
|---|---|---|---|
| T-CI-1 | Malicious or compromised GitHub Action or build step exfiltrates secrets or alters the APK (A4) | Third-party actions pinned by full commit SHA; workflow `permissions:` minimal (read by default); no secrets in the app build CI; signing of releases done outside untrusted PR workflows | M |
| T-CI-2 | Malicious dependency version (Gradle) (A4) | Gradle dependency verification (`verification-metadata.xml` with checksums), lockfiles, Dependabot/Renovate reviewed by hand; few dependencies (ZXing-core, OkHttp, ICU4J-class library) | M |
| T-CI-3 | The lists workflow is compromised and publishes a signed malicious bundle (A4) | Separate, minimal repository and workflow with the Ed25519 key as an environment secret of that workflow only; pinned upstream fetch (fixed URLs, checksums where offered); validation and reduction before signing; actions pinned by SHA; minimal permissions; maximum variation threshold between two bundles (size and entry count), failing the job above it; top-domains allow-list limits the impact of a poisoned bundle (T-LST-4) | A: a compromise of this workflow yields a validly signed bundle; impact bounded by the mitigations above (a list can only add warnings, never open anything) |
| T-CI-4 | Public repo leaks secrets or personal data (I) | No secrets or personal data in the repo; secret scanning enabled; signing key only in the lists workflow's environment | M |

## Residual risk (accepted)

Unknown and new malicious sites; valid payment destinations owned by the attacker; ignored warnings; platform zero-days; other apps registered as handlers; clipboard visible to the foreground app and keyboard; a compromise of the lists workflow (T-CI-3); the attacker learning of the scan when the user chooses redirect tracing.
