# LookFirst — Scanner de QR codes anti-quishing (Android)

## Vision
Application Android qui scanne les QR codes **sans rien exécuter automatiquement**. Elle décode tous
les types de contenu (URL, Wi-Fi, SMS, téléphone, e-mail, otpauth, SEPA, crypto, deep links), les
analyse **100 % en local**, affiche un verdict expliqué, puis l'utilisateur choisit Ouvrir / Copier / Annuler.

Règles produit (non négociables) :
- **Aucune action automatique** : ni ouverture d'URL, ni connexion Wi-Fi, ni SMS, ni appel, ni Intent après un scan. Seule une action explicite de l'utilisateur déclenche quelque chose.
- **Tout contenu de QR est une entrée non fiable** : taille bornée, parsing défensif, jamais d'évaluation, jamais de confiance dans un schéma ou un domaine affiché.
- **Aucune URL scannée n'est envoyée à un tiers** (D3, D9). Les listes de réputation publiques sont téléchargées sur le téléphone. Seules exceptions : suivi de redirections et RDAP, optionnels, sur action explicite de l'utilisateur.
- **Ne jamais afficher « sûr »** : écrire « aucun signal de risque détecté ». Chaque verdict est expliqué (quels signaux, pourquoi).

## Stack technique
- **Langage** : Kotlin
- **UI** : Jetpack Compose
- **Architecture** : MVVM + Clean Architecture (UseCase layer)
- **DI** : Hilt
- **Navigation** : Navigation Compose
- **Caméra / scan** : CameraX + ZXing (D11, sans Google)
- **Tests** : JUnit4 + Mockk + Turbine + Compose Testing
- **Build** : Gradle (Kotlin DSL)
- **Min SDK** : 23 (Android 6.0, D8)
- **CI** : GitHub Actions sur runners GitHub (`ubuntu-latest`, D4)

---

## Working docs (read these first)

Des sous-agents spécialisés vivent dans `.claude/agents/` (voir `.claude/agents/README.md` pour savoir
lequel utiliser quand). Pas de `.claude/commands/`. Chaque feature passe par une branche + une PR
GitHub ; rien n'est commité directement sur `main`.

| File | What |
|---|---|
| `docs/design/architecture.md` | couches, modules, flux scan → décodage → analyse → verdict, « où met-on X » |
| `docs/design/threat-model.md` | modèle de menace : attaques par QR, entrées hostiles, surfaces Android |
| `docs/design/reputation-sources.md` | listes de réputation publiques, format, mise à jour, licences |
| `docs/adr/` | Architecture Decision Records — le *pourquoi* des choix non évidents |
| `DECISIONS.md` | décisions D1 à D9 et suivantes, une ligne chacune |
| `PROGRESS.md` / `BUGS.md` | checklist courte / bugs récents — Notion est la source de vérité du statut (D6) |

(Les documents `docs/design/` et `docs/adr/000N-*.md` sont créés par LF-1 ; s'ils n'existent pas encore, ne les invente pas.)

---

## Workflow per feature

1. Écrire d'abord les tests qui échouent là où c'est peu coûteux (décodeurs, analyseurs, use cases).
2. `domain/` — modèles + interfaces de repository (si nouveau).
3. `data/` — décodage, listes de réputation, mappers, accès plateforme derrière des interfaces.
4. `di/` — bindings Hilt (si nouveaux injectables).
5. `ui/screens/<name>/` — `UiState`, `ViewModel`, `Route`, `Screen`, composables de section.
6. En local, sous le verrou : `flock /tmp/memlearn-gradle-test.lock ./gradlew compileDebugUnitTestKotlin ktlintCheck detekt --max-workers=4` + les tests touchés. Le gate de PR
   `./gradlew ktlintCheck detekt cpdCheck testDebugUnitTest assembleDebug` tourne **une fois, en CI** sur la PR et doit être vert avant fusion. `lintDebug` et `assembleRelease` ne sont pas dans une PR ordinaire : ils font partie du gate complet, lancé à la main sur `main`.
7. Installation sur téléphone et test à la main, aux jalons (pas d'adb, pas d'`installDebug` dans les agents).
8. Mettre à jour `PROGRESS.md` (+ `DECISIONS.md` / `BUGS.md` au besoin).

Les alertes de sécurité de la CI (semgrep, gitleaks, osv-scanner, en mode rapport) deviennent des issues pour Hugo : on ne les corrige ni ne les ferme dans un autre ticket. Ne jamais toucher `.github/workflows/security.yml`.

### Gradle
Toujours sous le verrou partagé avec les autres projets du PC : `flock /tmp/memlearn-gradle-test.lock ./gradlew … --max-workers=4`. `./gradlew --stop` aussi sous ce verrou.

### Refactoring
Seulement quand une relecture signale un problème structurel. Jamais mélangé à un commit de feature.

---

## Commits and PRs

Un seul format, pour chaque commit et chaque titre de PR : `type(LF-n): summary`.

- `type` : `feat`, `fix`, `refactor`, `test`, `docs`, `ci`, `build`, `perf`, `style`, `chore`.
- `LF-n` : l'ID du ticket, entier simple.
- `summary` : anglais, minuscule au début, présent, sans point final ; sujet entier <= 72 caractères.
- Exemples : `fix(LF-12): handle empty qr content`, `ci(LF-4): add pull request gate`.
- Pas de commit sans ticket. Un commit = une intention ; jamais refactoring et feature mélangés.
- Chaque commit d'agent se termine par la ligne `Co-Authored-By: …` fournie par la session.
- Exemptés : commits de fusion et `Revert "..."` générés par GitHub.
- Vérifié en CI par `scripts/check-commit-format.sh` ; en local : `scripts/check-commit-format.sh --range origin/main HEAD` ou `--message "fix(LF-12): ..."` (`--self-test` pour les exemples).
- Hooks locaux optionnels : `git config core.hooksPath scripts/git-hooks` (pre-commit : ktlint ; commit-msg : `scripts/check-commit-format.sh --commit-msg` ; pre-push : compilation + ktlint + detekt, ignoré pour un push sans code Android).

---

## Standards de code

### Architecture
- Zéro import Android dans `domain/` (pas de Context, pas de Compose, pas de CameraX).
- Le décodage et l'analyse sont des fonctions pures et déterministes dans `domain/` : même entrée, même verdict, testables sans appareil.
- Les APIs Android (caméra, presse-papiers, Intents, réseau) restent derrière des interfaces dans `data/platform/`.
- Les composables reçoivent uniquement UiState + lambdas, jamais un ViewModel.
- Chaque ViewModel expose `StateFlow<UiState>` en lecture seule (`asStateFlow()`).
- Les événements one-shot passent par `Channel` + `receiveAsFlow()`, jamais `SharedFlow`.
- Erreurs typées (sealed result), jamais `e.message` affiché à l'utilisateur.

### Sécurité du contenu scanné
- Borner la taille de tout contenu avant parsing ; pas de regex à backtracking sur du contenu non borné (ReDoS) ; pas de réflexion ni d'évaluation.
- Afficher à l'utilisateur la forme **normalisée** de ce qui sera ouvert (IDN / punycode, userinfo `@`, ports, schémas inattendus) avant toute action.
- Aucun Intent implicite construit depuis du contenu scanné sans confirmation ; composants non exportés par défaut ; pas de `allowBackup`.
- Pas de journalisation du contenu scanné. Pas de SDK d'analytics ni de publicité.
- Réseau : seulement le téléchargement des listes de réputation publiques (HTTPS, intégrité vérifiée) et les actions optionnelles explicites (redirections, RDAP).

### Textes et verdicts
- Jamais « sûr » ni « safe » : « aucun signal de risque détecté ». Un verdict liste ses signaux.
- Pas de chaîne en dur dans les composables : ressources `strings`, FR et EN.

### Nommage
- Anglais partout dans le code.
- Fonctions : verbes explicites (`decodePayload`, `analyzeUrl`).
- UiState : sealed interface par écran (`ScanUiState`, `VerdictUiState`).

### Quand une spec est incomplète
- Prendre la décision la plus utile pour l'utilisateur, en restant du côté de la prudence.
- La documenter immédiatement dans `DECISIONS.md` : `DECISION [domaine] : choix — raison`.
- Ne jamais bloquer le build pour une décision mineure.

---

## Structure de fichiers

Layer-first, un seul module Gradle au départ (détail : `docs/design/architecture.md`).
`app/src/main/java/com/lookfirst/` (package `com.lookfirst`, confirmé par LF-3) :

```
  core/        # utilitaires purs : result, text, normalisation d'URL
  data/        # platform (caméra, presse-papiers, Intents), reputation (listes locales),
               # repository, éventuel réseau optionnel
  di/          # modules Hilt
  domain/      # Kotlin pur : model, decoder (types de QR), analysis (signaux, verdict), usecase
  ui/          # screens (scan, résultat, réglages), components, theme, navigation
  MainActivity.kt, LookFirstApp.kt
```

---

## Fichiers de suivi à maintenir

Notion (page LookFirst, espace « App's Projects ») est la source de vérité du statut ; ces fichiers restent courts : 1 ligne par feature dans `PROGRESS.md`, 2 lignes max par décision (structurant → ADR), entrées de `BUGS.md` résolues depuis plus de 2 semaines déplacées dans `docs/archive/`.

**`PROGRESS.md`** :
```
- [✅ DONE] Feature — tests: N passed, reviews: clean
- [🔄 WIP]  Feature — en cours
- [⬜ TODO] Feature
```

**`DECISIONS.md`** :
```
DECISION [url-analysis] : punycode affiché en clair — l'homographe doit être visible
```

**`BUGS.md`** :
```
BUG [decoder] : ❌ vCard tronquée plante le décodeur — statut: OPEN
BUG [decoder] : ✅ Wi-Fi sans mot de passe rejeté — corrigé (LF-12)
```

---

## Critère de "done" (par ticket)

- Gate de PR vert en CI : `./gradlew ktlintCheck detekt cpdCheck testDebugUnitTest assembleDebug` ; gate complet (à la main sur `main`) : + `lintDebug assembleRelease`.
- Aucune violation ❌ ouverte dans `BUGS.md` pour la feature.
- Critères d'acceptation du ticket cochés, PR relue (une seule relecture) puis fusionnée.
