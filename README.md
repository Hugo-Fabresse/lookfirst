# LookFirst

Scanner de QR codes anti-quishing pour Android (Kotlin, Jetpack Compose).

Principe : on scanne, **rien ne s'exécute automatiquement**. L'app décode le contenu (URL, Wi-Fi, SMS, téléphone, e-mail, otpauth, SEPA, crypto, deep links), l'analyse **entièrement sur le téléphone**, explique le verdict, puis l'utilisateur choisit Ouvrir, Copier ou Annuler.

Statut : en construction. Règles du projet : [`CLAUDE.md`](CLAUDE.md). Décisions : [`DECISIONS.md`](DECISIONS.md). Sécurité : [`SECURITY.md`](SECURITY.md).
