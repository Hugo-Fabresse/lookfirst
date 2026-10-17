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
DECISION [analyse] : nouveaux codes hors ux.md 7.3 — URL_ODD_FORMAT (C), URL_HOST_ENCODED (S), URL_MALFORMED (S), WIFI_WEP (C), OTPAUTH_ODD_PARAMS (C), USSD_CODE (S), PREMIUM_NUMBER (S), SMS_SHORT_CODE (C), SMS_PREFILLED_BODY (C) — textes à ajouter par le ticket UI
DECISION [analyse] : bidi cherché dans l'hôte et le userinfo après décodage « % » ; ailleurs dans l'URL il reste HIDDEN_CHARS (SUSPICIOUS) — le spoof d'affichage vise l'autorité
DECISION [analyse] : otpauth « inhabituel » = hotp, algorithme hors SHA1/256/512, digits hors 6/8, period hors 30/60, issuer ≠ préfixe du label — l'enrôlement reste SUSPICIOUS dans tous les cas
DECISION [analyse] : PAYMENT_MISMATCH EPC = IBAN invalide, BIC, devise, montant ou nom absent ; crypto = transfert de token, destinataire ≠ adresse affichée, appel de contrat, montant > 1 000 000 unités / 10^21 wei / 10^27 unités de token ; les écritures `1e18` EIP-681 sont acceptées
DECISION [analyse] : Text et Unknown → UNPARSABLE (CAUTION) ; ParseResult.TooLarge / Empty aussi — jamais un résultat propre sans contenu analysé
