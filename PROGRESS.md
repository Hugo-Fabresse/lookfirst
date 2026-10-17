# LookFirst — Progress

Checklist courte, une ligne par fonctionnalité ou ticket, du plus ancien au plus récent. Le statut à jour vit dans Notion (page LookFirst, espace « App's Projects »).

- [✅ DONE] LF-1 — conception : architecture, modèle de menace, sources de réputation, ADR
- [✅ DONE] LF-2 — dépôt prêt pour les agents (règles, agents, fichiers de suivi)
- [✅ DONE] LF-3 — squelette Android (Gradle, Hilt, Compose, ktlint, detekt, cpd)
- [✅ DONE] LF-4 — CI GitHub (gate de PR, gate complet, sécurité en mode rapport)
- [✅ DONE] LF-12 — chaîne d'approvisionnement de la CI durcie (Renovate scanners, SHA d'actions vérifiés, vérification des dépendances Gradle)
- [✅ DONE] LF-13 — Robolectric android-all résolu et vérifié par Gradle (tests hors ligne)
- [✅ DONE] LF-7 — parcours et écrans (docs/design/ux.md)
- [✅ DONE] LF-8 — décodage sûr de tous les types de contenu QR (fusionné, PR #13) — tests: 98 passed
- [✅ DONE] LF-14 — alertes semgrep ouvertes triées (#5 faux positif accepté, #6/#10/#11 minimumReleaseAge par règle Renovate)
- [✅ DONE] LF-15 — signaux de risque par type de contenu (analyseurs purs, fusionné, PR #16) — tests: 58 ajoutés
- [✅ DONE] LF-9 — scan caméra (CameraX + ZXing, permission, écran Scan) — fusionné, PR #15
- [✅ DONE] LF-11 — écran Résultat : contenu exact + Copier / Annuler, navigation Scan → Résultat (fusionné, PR #17)
- [✅ DONE] LF-10 — scan d'un QR depuis une image (Photo Picker, lecture bornée, écran d'échec)
- [✅ DONE] LF-16 — normalisation des hôtes (UTS #46 embarqué, PSL), homographes, imitations de marques — PR #18 — tests: 252 de domaine
- [✅ DONE] LF-20 — provenance vérifiable des données Unicode / PSL embarquées (URL + SHA-256 figés, --verify, workflow hebdomadaire)
