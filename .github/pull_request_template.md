<!-- Titre de la PR : type(LF-n): summary (ex. fix(LF-12): handle empty content). Types : feat fix refactor test docs ci build perf style chore. Voir CLAUDE.md, « Commits and PRs ». -->

## Ticket

LF-<n> (lien Notion facultatif)

## Résumé

<!-- Ce qui change, en 2 à 4 puces. -->

## Captures

<!-- Avant / après pour tout changement visible (ou « sans changement visuel »). -->

## Vérifications

- [ ] Checks locaux : `./gradlew compileDebugUnitTestKotlin ktlintCheck detekt` + tests touchés (sous le verrou flock)
- [ ] Contenu de QR traité comme non fiable : bornes, aucune action automatique, aucune URL envoyée à un tiers
- [ ] Textes de verdict : jamais « sûr », plutôt « aucun signal de risque détecté »
- [ ] Relecture faite (code-reviewer ou security-reviewer)
- [ ] `PROGRESS.md` / `DECISIONS.md` / `BUGS.md` à jour si besoin
- [ ] CI verte (ktlintCheck detekt cpdCheck testDebugUnitTest assembleDebug)

## À tester sur téléphone

<!-- Étapes courtes, reprises dans le guide de test de fin d'epic. -->
