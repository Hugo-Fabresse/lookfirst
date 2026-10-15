# Agents LookFirst

Un agent par thème. La session principale discute du périmètre avec Hugo, crée la branche,
délègue aux agents, puis ouvre la PR.

## Les 13 agents

Android et produit :
`android-dev`, `android-platform`, `ui-dev`, `product-designer`, `architect`,
`performance-engineer`, `debugger`.

Qualité, sécurité, build, suivi :
`test-engineer`, `code-reviewer`, `security-dev`, `security-reviewer`, `build-ci-engineer`,
`project-manager`.

## Qui fait quoi, par chantier

| Chantier | Conception | Implémentation | Tests | Relecture |
|---|---|---|---|---|
| Décodage des types de QR (URL, Wi-Fi, SMS, tel, e-mail, otpauth, SEPA, crypto, deep links) | `architect` | `android-dev` | `test-engineer` (fixtures) | `code-reviewer` + `security-reviewer` |
| Parsing de contenu hostile, normalisation d'URL, homographes | — | `security-dev` | `test-engineer` | `security-reviewer` |
| Analyse locale et verdict (signaux, explications) | `architect` | `android-dev` | `test-engineer` | `code-reviewer` |
| Listes de réputation (téléchargement, intégrité, consultation) | `architect` | `security-dev` + `android-platform` (WorkManager) | `test-engineer` | `security-reviewer` |
| Redirections et RDAP optionnels | `architect` | `security-dev` | `test-engineer` | `security-reviewer` |
| Écrans Compose (scan, résultat, réglages) | `product-designer` | `ui-dev` | `test-engineer` | `product-designer` + `code-reviewer` |
| Textes de verdict et vocabulaire | `product-designer` | `ui-dev` | `test-engineer` | `product-designer` |
| Caméra et scan (CameraX, permissions) | — | `android-platform` | `test-engineer` | `code-reviewer` + `security-reviewer` |
| Intents (ouvrir, copier, Wi-Fi, SMS, appel), manifeste | — | `android-platform` | `test-engineer` | `security-reviewer` |
| Feature Android générale (glue, ViewModel, use case) | — | `android-dev` | `test-engineer` | `code-reviewer` |
| Build Gradle, CI GitHub, hooks, outillage | — | `build-ci-engineer` | — | `security-reviewer` |
| Refactoring Android | `architect` | `architect` | `test-engineer` | `code-reviewer` |
| Performance (latence du scan, démarrage, mémoire, batterie) | — | `performance-engineer` | — | `code-reviewer` |
| Bug ou crash signalé | — | `debugger` | `test-engineer` | `code-reviewer` |
| Tests sur téléphone | — | Hugo | `debugger` pour chaque problème trouvé | — |
| Suivi (Notion, PROGRESS / DECISIONS / BUGS) | — | `project-manager` | — | `code-reviewer` |

## Règles de travail

- **Un chantier à la fois**, discuté avec Hugo ; une branche, un ticket et une PR par étape.
- Les agents de conception (`product-designer`, `architect` pour une nouvelle architecture) livrent un document ou une spécification, **validé par Hugo avant tout code**.
- Les agents de relecture (`code-reviewer`, `security-reviewer`, `product-designer`) n'écrivent pas de code : Bash en lecture seule, par consigne.
- Toute PR qui touche des données non fiables (contenu de QR), le manifeste, les Intents, le réseau, les listes de réputation ou la CI passe par `security-reviewer`.
- Règles produit pour tous : aucune action automatique après un scan ; contenu de QR = entrée non fiable ; aucune URL envoyée à un tiers ; jamais « sûr », plutôt « aucun signal de risque détecté ».
- Une suggestion d'agent n'est pas une décision de Hugo : en cas de doute, « à valider ».
- Chaque ticket : `type(LF-n): summary` (entier, anglais, minuscule, sans point, <= 72 caractères ; voir CLAUDE.md, « Commits and PRs »), vérifié en CI. Une PR par ticket, jamais de commit sur `main`. Pas d'adb ni d'`installDebug` dans les agents : les installations sur le téléphone se font à la main, aux jalons. Personne ne touche `.github/workflows/security.yml`.
- À la fin d'un chantier, un agent explique à Hugo, en français simple, ce qui a été fait et pourquoi.

## CI et Gradle : règles actuelles

- **Le gate de PR tourne une fois, en CI** (runners GitHub `ubuntu-latest`) : `ktlintCheck detekt cpdCheck testDebugUnitTest assembleDebug`. En local : compilation, ktlint, detekt et les tests du ticket seulement.
- **Le gate complet n'est pas sur chaque PR.** Il se lance à la main (`workflow_dispatch` sur `main`) : quand aucune PR n'attend, avant toute installation sur le téléphone et à la fin de chaque jalon. Il ajoute `lintDebug` et `assembleRelease`.
- Les agents lancent Gradle uniquement ainsi, sur le verrou partagé avec les autres projets du PC, pour passer l'un après l'autre :

  ```
  flock /tmp/memlearn-gradle-test.lock ./gradlew … --max-workers=4
  ```

  `./gradlew --stop` se lance aussi sous ce verrou.
- Sécurité en CI : semgrep, gitleaks et osv-scanner en mode rapport ; leurs alertes deviennent des issues pour Hugo, qu'on ne corrige pas dans un autre ticket.

## Modèles et coût

- **Sonnet** pour l'implémentation, la conception et la relecture de code.
- **Opus** seulement pour `security-reviewer` (failles : là où une erreur coûte cher).
- **Une seule relecture par ticket** (`code-reviewer`, ou `security-reviewer` quand le ticket touche des données externes, le manifeste, la CI ou le réseau ; les deux seulement si le ticket relève des deux). Les correctifs demandés sont appliqués puis vérifiés par les tests et la CI, **sans seconde relecture**, sauf si la relecture a trouvé un problème **bloquant** : dans ce cas, une relecture ciblée de ce seul point.
- Au plus **2 agents en parallèle**, autres projets du PC compris, sur des tickets indépendants (dossiers de travail isolés).

Enchaînement type : périmètre validé → agent de conception (document validé par Hugo) → agent(s) de code → `test-engineer` → relecteur → vérifications locales légères → PR → **gate de PR en CI** → fusion → `project-manager`.

## Consigne type (à chaque lancement d'agent)

Objectif et critères d'acceptation ; où agir (fichiers) ; où ne pas agir et quoi ne pas explorer ; interdits connus ; approche et outils privilégiés (l'agent ajoute son expertise) ; décisions déjà prises (l'agent peut les contester en le signalant, sans dévier seul) ; fichier existant à imiter ; checks locales ; clause d'arrêt si le ticket grossit ; format du compte rendu. CLAUDE.md est chargé automatiquement : les agents ne relisent que les documents utiles au ticket.
