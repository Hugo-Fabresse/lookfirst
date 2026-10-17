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
