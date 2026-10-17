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
- [✅ DONE] LF-21 — lint Android dans le gate de PR
- [✅ DONE] LF-17 — moteur de verdict (niveau + raisons ordonnées), port de réputation, AnalyzeScan — PR #22
- [✅ DONE] LF-18 — écran de verdict : bannière par niveau, raisons expliquées, hôte Unicode + forme technique, textes FR/EN de tous les codes — PR #24
- [✅ DONE] LF-22 — format du bundle de listes conçu (manifeste signé ECDSA P-256, index de hachages triés) — PR #26, docs/design/reputation-bundle.md, ADR-0007
- [✅ DONE] LF-23 — listes de réputation locales : vérification ECDSA, installation atomique (fsync des dossiers), consultation, garde-fous des clés de test — PR #28 — tests: 110 ajoutés ; reste : keyset en usage réel, RDAP/brands (formats), job de mise à jour (LF-24), clés réelles (LF-25)
- [✅ DONE] LF-19 — ouvrir en sûreté : ActionExecutor (navigateur par défaut, composeur, SMS, e-mail, cartes, Wi-Fi), confirmation Suspect/Danger, test d'architecture
- [✅ DONE] LF-24 — mise à jour automatique des listes (HTTPS Codeberg, WorkManager quotidien, « Mettre à jour maintenant »), écrans Réglages / À propos / premier lancement — PR #29, relecture sécu propre après correctif ; reste : dépôt et clés réels (LF-25)
- [✅ DONE] LF-26 — suivre les redirections sur demande : traceur (IP vérifiée, 5 sauts, 5 s), nouveau verdict sans baisse de niveau, section « Vérifications facultatives » + feuille d'avertissement, port 443 seul — PR #30, relecture sécu propre après correctif ; reste : test SNI/API 23 au jalon téléphone
- [🔄 WIP]  LF-28 — listes téléchargées chez leurs éditeurs (D20, ADR-0008) : CERT Polska, index construit sur le téléphone, plus de bundle ni de clés — branche feat/LF-28-direct-list-sources
- [✅ DONE] LF-27 — âge du domaine via RDAP sur demande : bootstrap embarqué validé, client RDAP durci (même transport que le traceur), DOMAIN_RECENT 30/90 jours, bouton + feuille d'avertissement — PR #31, relecture sécu propre après correctif
- [✅ DONE] LF-29 — gate complet rouge : règle R8 retirant Throwable.printStackTrace() en release (WorkManager fusionné dans une classe app) — branche fix/LF-29-release-stacktrace
- [✅ DONE] LF-43 — budget et indice TYPO sur les lettres du mot-clé et non sur son squelette ; corpus légitime +12 hôtes, 0 faux positif, fraude inchangée — branche fix/LF-43-typo-length
- [🔄 WIP]  LF-35 — garde anti-grille de motifs de repérage sur les deux lecteurs (caméra et import), seuil 30 — branche fix/LF-35-camera-finder-guard
- [✅ DONE] LF-44 — TYPO sur les morceaux à tirets d'un label, indice obligatoire ; corpus légitime +9 hôtes à tirets, 0 faux positif, fraude +4 — branche fix/LF-44-typo-in-label-parts
- [🔄 WIP]  LF-32 — limite de variation des listes sur la différence d'ensembles + ancrage de l'horloge par l'en-tête Date — branche fix/LF-32-list-set-variation
- [🔄 WIP]  LF-34 — signal LOCAL_HOST : hôtes locaux, réservés et TLD inexistants (audit S-6) — branche fix/LF-34-local-host-signal
- [✅ DONE] LF-42 — hôte probable d'un Text en forme d'adresse analysé (listes, marque, homographe, IP, local), sans Ouvrir (audit I-2) — branche fix/LF-42-address-like-text
