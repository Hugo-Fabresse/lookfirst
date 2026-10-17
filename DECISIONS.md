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
DECISION [scan] : permission HIDE_OVERLAY_WINDOWS (normale, sans invite) ajoutée — setHideOverlayWindows(true) n'a d'effet qu'avec elle (T-UI-3)
DECISION [scan] : tout refus de CAMERA mène à l'écran « refusée » (réglages Android) — pas de seconde invite, ni culpabilisation (ux 5.3)
DECISION [scan] : après lecture ScanViewModel ne garde pas la chaîne — elle passe à ResultViewModel (jamais dans une route ni dans l'état sauvegardé), conservée tant que l'écran Résultat est ouvert
DECISION [result] : navigation Scan -> Résultat sans animation, ViewModels portés par l'activité — pas de hilt-navigation-compose, pas d'argument de route contenant le QR
DECISION [result] : contenu exact masqué tant que l'utilisateur ne touche pas « Afficher » quand il contient un secret (otpauth, mot de passe Wi-Fi) — sinon le secret fuit dans le bloc de contenu
DECISION [result] : Copier copie la chaîne lue par la caméra telle quelle (pas la forme NFC affichée) — « texte exact » ; presse-papiers vidé au retour dans l'app seulement s'il contient encore ce secret (API 28+)
DECISION [result] : textes ajoutés hors ux.md §7 (type_url_no_scheme, field_*, userinfo_note, camera_unavailable, camera_retry, a11y_invisible_other) — à reporter dans ux.md par le designer
DECISION [scan] : caméra absente ou occupée = état « caméra indisponible » avec Réessayer, image conservée — pas de viseur vide muet
