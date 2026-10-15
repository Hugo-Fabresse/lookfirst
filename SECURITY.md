# Security Policy

LookFirst analyse des contenus non fiables (QR codes) : la sécurité est au coeur du produit.

## Signaler une faille

Merci de **ne pas ouvrir d'issue publique**. Utilisez le signalement privé de GitHub : onglet *Security* du dépôt, puis *Report a vulnerability* (GitHub Security Advisories).

Indiquez la version ou le commit, les étapes de reproduction et l'impact estimé.

## Périmètre

L'app est 100 % locale : aucune URL scannée n'est envoyée à un tiers (voir `docs/adr/`). Les failles de parsing de contenu hostile, d'Intents, de permissions ou de chargement des listes de réputation sont dans le périmètre.
