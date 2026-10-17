# LookFirst — Décisions

Format : `DECISION [domaine] : choix — raison`. 2 lignes max par décision ; ce qui est structurant pour le code devient aussi un ADR dans `docs/adr/`. Notion reste la source de vérité du statut.

DECISION [stack] : D1 — Android natif Kotlin / Jetpack Compose — stack Android éprouvée
DECISION [architecture] : D2 — serveur d'analyse sur VPS — remplacée par D9
DECISION [vie-privée] : D3 — aucune URL scannée envoyée à un tiers ; listes de réputation publiques téléchargées sur le téléphone — vie privée
DECISION [ci] : D4 — dépôt GitHub, CI sur les runners GitHub gratuits (pas de runner sur le PC de Hugo) — pas de machine à garder allumée
DECISION [process] : D5 — nom LookFirst, tickets LF-n, commits `type(LF-n): summary` — traçabilité ticket / commit
DECISION [suivi] : D6 — suivi dans Notion (page LookFirst de l'espace « App's Projects »), source de vérité du statut — un seul endroit pour l'avancement
DECISION [dépôt] : D7 — pas de licence ; dépôt public pendant le développement puis privé — choix du propriétaire
DECISION [compatibilité] : D8 — minSdk 23 — maximum d'appareils
DECISION [architecture] : D9 — app 100 % locale, sans serveur ; redirections et RDAP optionnels sur action explicite ; pas de bac à sable — vie privée et simplicité (remplace D2)
DECISION [réputation] : D10 — listes de réputation publiées sur Codeberg — hébergeur européen, il voit seulement l'IP
DECISION [scan] : D11 — décodeur ZXing — sans Google
DECISION [deep-links] : D12 — deep links : copie seule — sécurité
DECISION [réputation] : D13 — bundle de listes signé Ed25519, anti-rollback — intégrité
DECISION [ci] : règles semgrep du registre non figées — acceptable en mode rapport (à figer avant de rendre semgrep bloquant)
DECISION [ci] : vérification Gradle sha256 seule, pas de PGP — beaucoup d'artefacts Google non signés
DECISION [tests] : Robolectric hors ligne, sdk 34 seulement — un autre sdk = nouvelle coordonnée android-all + métadonnées à cache froid
DECISION [deps] : Renovate désactivé pour android-all-instrumented — suit la version de Robolectric
DECISION [ux] : D14 — plusieurs QR dans une image : le premier seulement, signalé à l'écran — un choix dans une liste expose au leurre
DECISION [ux] : D15 — SEPA / paiement et crypto : Copier seulement — pas d'Intent générique sûr, les apps de paiement sont la cible
DECISION [ux] : D16 — listes > 14 jours = raison CAUTION ; bundle signé initial dans l'APK (ADR-0005) — jamais de listes absentes au 1er lancement
DECISION [ux] : D17 — Suspect et Danger : Ouvrir derrière une confirmation (Annuler par défaut) — l'utilisateur décide, aligné sur T-UI-2
DECISION [ux] : D18 — « Contrôles effectués : … » au lieu de « Vérifié : … » — ne pas ressembler à un label de confiance
DECISION [decoder] : contenu borné à 4096 caractères avant tout parsing, NFC, caractères cachés signalés jamais supprimés — un QR tient ~2,9 Ko
DECISION [decoder] : champs répétés Wi-Fi (S/T/P/H), schéma hors liste sans espace = AppLink, avec espace = Text — ambiguïté = rejet (Unknown), jamais de choix silencieux
DECISION [decoder] : HostKind.UNRESOLVED pour tout hôte non ASCII ou avec « % » — IPv4/domaine ne se décide qu'après le normaliseur UTS #46 (rawHost = hôte tel que saisi)
DECISION [decoder] : espaces et caractères Default_Ignorable en tête ignorés pour classer le schéma — « \u200Bjavascript: » reste DangerousScheme
DECISION [decoder] : longueur d'IBAN par pays non contrôlée (format + clé mod 97 seulement) — table des longueurs reportée à l'analyse
DECISION [sécurité] : alerte semgrep exported_activity sur l'activité de lancement acceptée — composant MAIN/LAUNCHER obligatoirement exporté, seul composant exporté
DECISION [analyse] : LONG_URL au-delà de 250 caractères — un QR marketing légitime dépasse rarement 200, au-delà le nom du site est noyé
DECISION [analyse] : liste de raccourcisseurs embarquée (~45 domaines, hôte égal ou sous-domaine) — local, sans réseau ; mise à jour avec le bundle de listes plus tard
DECISION [analyse] : UNUSUAL_PORT = tout port hors 80 et 443 — simple et prudent, y compris 80 sur https
DECISION [analyse] : numéros surtaxés détectés seulement en format international (+33 89…, +44 9…, +1 900…, +882…) — un numéro national sans pays est ambigu (089 = Munich, 09 = Freebox)
DECISION [analyse] : `*` ou `#` dans un numéro tel/sms = USSD_CODE (SUSPICIOUS) ; SMS vers 3 à 6 chiffres = SMS_SHORT_CODE, corps prérempli = SMS_PREFILLED_BODY (CAUTION)
DECISION [analyse] : nouveaux codes hors ux.md 7.3 — URL_ODD_FORMAT (C), URL_HOST_ENCODED (S), URL_MALFORMED (S), WIFI_WEP (C), WIFI_UNKNOWN_SECURITY (C), OTPAUTH_ODD_PARAMS (C), USSD_CODE (S), PREMIUM_NUMBER (S), POSSIBLE_PREMIUM_NUMBER (C), SHORT_NUMBER (C), SMS_SHORT_CODE (C), SMS_PREFILLED_BODY (C), MAIL_PREFILLED (C), NON_ASCII_DIGITS (S), BIDI_IN_URL (D), ANALYSIS_INCOMPLETE (C), CRYPTO_BAD_CHECKSUM (S) — textes à ajouter par le ticket UI
DECISION [analyse] : tout bidi dans une URL = DANGEROUS (BIDI_IN_HOST dans l'hôte ou le userinfo, BIDI_IN_URL ailleurs), après décodage « % » — aligné sur T-CNT-3
DECISION [analyse] : otpauth « inhabituel » = hotp, algorithme hors SHA1/256/512, digits hors 6/8, period hors 30/60, issuer ≠ préfixe du label — l'enrôlement reste SUSPICIOUS dans tous les cas
DECISION [analyse] : PAYMENT_MISMATCH EPC = IBAN invalide, BIC, devise, montant ou nom absent ; crypto = destinataire d'un `transfer` absent, invalide, mal casé (EIP-55) ou égal au contrat (destinataire réel en évidence), appel de contrat, montant > 1 000 000 unités / 10^21 wei / 10^27 unités de token ; `1e18` EIP-681 accepté ; le `transfer` vers un destinataire distinct est normal
DECISION [analyse] : Text et Unknown → UNPARSABLE (CAUTION) ; ParseResult.TooLarge / Empty aussi — jamais un résultat propre sans contenu analysé
DECISION [analyse] : checksums crypto en Kotlin pur — Base58Check (bitcoin, litecoin, dogecoin, dash, tron), Bech32/Bech32m (bitcoin, litecoin), EIP-55 avec Keccak-256 maison (pas SHA3 du JDK) ; adresse Ethereum tout en minuscules ou majuscules = pas de checksum, pas de signal ; autres familles non vérifiées
DECISION [analyse] : `tel:` de 3 à 6 chiffres = SHORT_NUMBER sauf 112, 114, 115, 119, 116xxx ; AnalysisContext.regionHint (ISO) fourni plus tard par data/ ; FR : 08[1-9] et 118xxx = PREMIUM_NUMBER ; sans région : 089 + 7 chiffres = POSSIBLE_PREMIUM_NUMBER (CAUTION)
DECISION [analyse] : USSD cherché (`*`, `#`) sur le numéro complet décodé avant le `;` ; chiffres convertis par Character.digit, tout chiffre non ASCII = NON_ASCII_DIGITS
DECISION [analyse] : hôte HostKind.UNRESOLVED (non ASCII ou `%`) = ANALYSIS_INCOMPLETE (CAUTION) en attendant le normaliseur de LF-16, qui le retirera ou le raffinera
DECISION [analyse] : USERINFO_AT = DANGEROUS si le userinfo contient un « . » ou un nom numérique, sinon SUSPICIOUS ; mailto avec bcc ou corps prérempli = MAIL_PREFILLED (CAUTION) ; Wi-Fi de type inconnu = WIFI_UNKNOWN_SECURITY (CAUTION)
DECISION [analyse] : détails de signal typés (PaymentProblem, OtpParam) — Untrusted reste réservé au texte scanné
DECISION [scan] : permission HIDE_OVERLAY_WINDOWS (normale, sans invite) ajoutée — setHideOverlayWindows(true) n'a d'effet qu'avec elle (T-UI-3)
DECISION [scan] : tout refus de CAMERA mène à l'écran « refusée » (réglages Android) — pas de seconde invite, ni culpabilisation (ux 5.3)
DECISION [scan] : après lecture ScanViewModel ne garde pas la chaîne — elle passe à ResultViewModel (jamais dans une route ni dans l'état sauvegardé), conservée tant que l'écran Résultat est ouvert
DECISION [result] : navigation Scan -> Résultat sans animation, ViewModels portés par l'activité — pas de hilt-navigation-compose, pas d'argument de route contenant le QR
DECISION [result] : contenu exact masqué tant que l'utilisateur ne touche pas « Afficher » quand il contient un secret (otpauth, mot de passe Wi-Fi) — sinon le secret fuit dans le bloc de contenu
DECISION [result] : Copier copie la chaîne lue par la caméra telle quelle (pas la forme NFC affichée) — « texte exact » ; presse-papiers vidé au retour dans l'app seulement s'il contient encore ce secret (API 28+)
DECISION [result] : textes ajoutés hors ux.md §7 (type_url_no_scheme, field_*, userinfo_note, camera_unavailable, camera_retry, a11y_invisible_other) — à reporter dans ux.md par le designer
DECISION [scan] : caméra absente ou occupée = état « caméra indisponible » avec Réessayer, image conservée — pas de viseur vide muet
DECISION [result] : hôte non ASCII = avertissement « caractères non latins » sous l'hôte — la forme punycode viendra avec LF-16/LF-18
DECISION [result] : presse-papiers vidé sur le focus de la fenêtre après avoir quitté l'app, label aléatoire comparé sans jamais lire le texte — API 29+ ne donne la lecture qu'avec le focus
DECISION [result] : TouchGuard protège Copier seulement (écran et dialogue), message si bloqué — Annuler et Retour restent toujours utilisables
DECISION [result] : FLAG_SECURE et capture des récents désactivée tant qu'un contenu avec secret est affiché — l'utilisateur peut le révéler
DECISION [hôtes] : UTS #46 non transitoire + Punycode embarqués en Kotlin pur, générés depuis les fichiers Unicode 18.0.0 (ADR-0006) — ICU4J trop lourd, android.icu exige l'API 24, java.net.IDN est IDNA2003 ; testés sur IdnaTestV2 (6 396 cas) et les vecteurs RFC 3492
DECISION [hôtes] : paramètres WHATWG (pas de STD3, pas de CheckHyphens) + longueurs DNS + rejet des caractères d'hôte interdits après mapping (un ＠ pleine chasse devient « @ ») — évite le contournement du userinfo ; hôte invalide = URL_MALFORMED + ANALYSIS_INCOMPLETE
DECISION [hôtes] : un seul point final (y compris « 。 ») est retiré de la forme normalisée et mémorisé — `paypal.com.` et `paypal.com` se comparent égaux ; deux points ou un label vide = invalide
DECISION [hôtes] : propriétés Unicode (Bidi, Joining, virama, marques, scripts, confusables) embarquées, pas lues dans java.lang.Character — le résultat ne dépend pas de la version Unicode du téléphone ; NFC/NFD restent ceux du JDK/Android
DECISION [hôtes] : Public Suffix List embarquée datée du 2026-10-16 (ICANN + privée) dans app/src/main/resources/lookfirst/host/, régénérée par scripts/gen-host-data.py — la version signée viendra avec le bundle de listes (D13) ; section privée connue pour blogspot.com, github.io…
DECISION [analyse] : MIXED_SCRIPT par label, scripts hors Common/Inherited, admis seulement un script, Latin+Han+Hiragana+Katakana, Latin+Han+Bopomofo, Latin+Han+Hangul (UTS #39 « highly restrictive ») ; Script_Extensions ignoré
DECISION [analyse] : BRAND_LOOKALIKE contre ~270 marques (BrandList : mondiales, banques, transporteurs, opérateurs, services publics FR/UE) — squelette UTS #39 identique = HOMOGLYPH ; distance d'édition (Damerau, transposition = 1) ≤ 1 si le nom compte ≥ 6 lettres, ≤ 2 si ≥ 10, jamais pour un mot courant (« ~ ») = TYPO (voir la règle d'indice plus bas) ; séparateur « - » retiré = TYPO
DECISION [analyse] : COMBINED = domaine officiel écrit dans des sous-domaines (`paypal.com.secure-login.example`), ou marque distinctive (≥ 6 lettres, non courante) comme sous-domaine entier, ou toute marque avec un mot d'appât (login, secure, verify, compte, remboursement…) dans le nom ou les sous-domaines ; `apple-pie.com` et `apple.stackexchange.com` ne sont pas signalés
DECISION [analyse] : un nom de marque exact sur un autre TLD n'est pas signalé (`google.de`, `amazon.co.uk`) — trop de faux positifs ; il l'est sur une plateforme ouvrable par tous (section privée de la PSL : `paypal.blogspot.com`) ; un domaine officiel n'est jamais signalé
DECISION [analyse] : ANALYSIS_INCOMPLETE retiré quand la normalisation réussit, gardé (avec URL_MALFORMED) si elle échoue, seul si les données embarquées manquent ; evidence de USERINFO_AT, IP_HOST et SHORTENER = hôte normalisé ASCII
DECISION [analyse] : détails typés LookalikeKind (HOMOGLYPH, TYPO, COMBINED) et MixedScripts (noms de scripts issus des données Unicode) ; evidence de BRAND_LOOKALIKE = domaine officiel de la marque — textes à écrire par le ticket UI
DECISION [analyse] : TYPO affiné après relecture — jamais pour les marques à une édition d'un mot courant (marqueur `^` : twitch, pinterest, binance, cdiscount, generali, revolut, stripe, telenet, citroen, amazon) ; pour un nom de moins de 8 lettres seulement avec un indice (mot d'appât ou TLD à risque), sauf paypal et google (`+`) ; séparateur retiré = TYPO sans indice
DECISION [analyse] : exemption du nom officiel avant les mots-clés — nom saisi égal à un mot-clé ou au nom d'un domaine officiel = rien (COMBINED sur plateforme ouverte) ; marque stricte (`!` : services publics FR, banques FR, La Poste/Colissimo/Chronopost/Mondial Relay) = signalée sur tout TLD non officiel (`ameli.info`)
DECISION [analyse] : domaine officiel suivi de labels (`paypal.com.cn`, `impots.gouv.fr.ma`, `ameli.fr.ru`) = COMBINED ; les déclinaisons légitimes connues sont listées comme domaines officiels (google.com.br, amazon.com.br…). Risque résiduel : une déclinaison légitime non listée (`apple.com.xx`) est signalée à tort, un nom de marque exact sur un TLD inconnu (`paypal.xyz`) ne l'est pas pour une marque non stricte
DECISION [analyse] : marque distinctive (6 lettres ou plus, non courante) en partie entière du nom enregistrable = COMBINED sans mot d'appât ; mot d'appât cherché aussi sur les squelettes (IDN) ; appâts étendus au vocabulaire FR (colis, livraison, amende, rdv, assure, mon, fr, id…)
DECISION [analyse] : WHOLE_SCRIPT_CONFUSABLE (SUSPICIOUS) — label d'un seul script non latin dont le squelette est du Latin pur, TLD d'un autre script (règle du vérificateur IDN de Chrome) ; `пример.рф` reste propre
DECISION [hôtes] : dernier label numérique ou `0x…` qui n'est pas une IPv4 valide (`foo.1`, `256.1.1.1`) = hôte invalide (WHATWG « ends in a number ») ; toString de NormalizedHost / résultats masqué (longueurs)
DECISION [analyse] : corpus de non-régression de 208 domaines légitimes (Tranco, services publics FR, banques FR) = 0 signal de marque ; liste de mots-clés × domaines officiels = 0 signal
DECISION [image-scan] : bornes — 10 Mio lus au plus, 25 Mpx déclarés max, décodage à ~4 Mpx, octets magiques jpeg/png/webp/heif avant le décodeur, URI content:// seulement — image piégée (T-IMG-1/2)
DECISION [image-scan] : pas d'aperçu de l'image sur l'écran d'échec — l'image n'est pas conservée après la lecture (T-IMG-4)
DECISION [image-scan] : textes err_image_too_big et err_image_corrupt ajoutés (absents d'ux.md §7) — « trop volumineuse » et « format illisible » ne se confondent pas avec « aucun QR »
DECISION [image-scan] : lecture multi-QR seulement si ≤ 30 motifs de repérage candidats, un seul thread dédié, nouvelle lecture refusée (BUSY) tant qu'il est occupé — QRCodeMultiReader est cubique et non annulable
DECISION [verdict] : sans listes de réputation (NoReputationLists), toute URL http(s) est au moins CAUTION (LISTS_MISSING) — voulu tant que l'epic Listes n'existe pas
DECISION [verdict] : le CAUTION systématique sans listes (NoReputationLists) est provisoire — à revoir par l'epic Listes
DECISION [verdict-ui] : niveaux = conteneurs fixes (rouge, orange, ambre, bleu-gris) hors couleurs dynamiques, jamais vert — silhouette d'icône + libellé portent le sens, contraste AA testé clair et sombre
DECISION [verdict-ui] : la ligne « Contrôles effectués » d'un contenu qui n'est pas un lien web cite type, caractères cachés et cohérence des champs — la version « adresse, alphabets… » serait fausse pour un Wi-Fi
DECISION [verdict-ui] : Ouvrir est affiché mais désactivé (description d'accessibilité) tant que LF-19 n'existe pas — la pile garde sa forme finale, rien ne s'ouvre  (levé par LF-19)
DECISION [verdict-ui] : lettres non ASCII de l'hôte soulignées (pas de pointillé : Compose n'en a pas dans un texte) ; l'imitation de marque a une phrase par type (le caractère exact n'est pas connu de l'UI)
DECISION [open] : seul ActionExecutor lance un Intent, test d'architecture avec liste blanche explicite (OpenIntents, AppSettingsOpener, ScanRoute pour les launchers) — T-SCH-8
DECISION [open] : URL canonique reconstruite depuis les champs analysés, userinfo retiré, sans schéma = https ; refus (« ne peut pas être ouvert de façon sûre ») si hôte/port inutilisable ou si Uri.parse relit un autre hôte/port
DECISION [open] : pas de Custom Tabs (androidx.browser = nouvelle dépendance) ; navigateur par défaut via setPackage, pas de navigateur par défaut = rien ne s'ouvre
DECISION [open] : mailto sans cc ni bcc (seul « À » est affiché, un destinataire caché serait une fuite) ; pas de createChooser (l'app par défaut de l'utilisateur reste respectée)
DECISION [open] : Wi-Fi sans dialogue système (API < 30, WEP, mot de passe invalide) = réglages Wi-Fi ouverts mais on reste sur l'écran pour garder les identifiants visibles
DECISION [open] : NON_BROWSER_HANDLER calculé à l'affichage du verdict (avant tout clic) ; Open désactivé tant que le niveau est inconnu
