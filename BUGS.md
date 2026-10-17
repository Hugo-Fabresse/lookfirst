# LookFirst — Bugs

Entrées résolues depuis plus de 2 semaines : à déplacer dans `docs/archive/`.

Format :
```
BUG [domaine] : ❌ description courte — statut: OPEN
BUG [domaine] : ✅ description courte — corrigé (LF-n)
```

BUG [url-analysis] : ✅ `hôte:port/chemin` sans schéma échappait aux listes de réputation alors qu'il s'ouvrait en https — corrigé (LF-30)
BUG [build] : ✅ le gate complet échouait : printStackTrace de WorkManager (WorkTypeConverters) fusionné par R8 dans une classe app — corrigé (LF-29)
BUG [scan] : ✅ une grille de motifs de repérage gelait l’analyse caméra (aucune garde) et le repli TRY_HARDER de l’import — corrigé (LF-35)
BUG [lists] : ✅ sous-domaines de wordpress.com / free.fr / ovh.net ignorés (parseur, lookup, BrandMatcher) : les entrées CERT Polska *.wordpress.com perdues, "aucun signal" affiché — corrigé (LF-31)
BUG [brand] : ✅ mobile.free.fr (service Free réel) recevait un signal TYPO (proche de t-mobile) — corrigé (LF-31, libellés réservés par plateforme)
BUG [lists] : ✅ la limite de variation ne comptait que le nombre d'entrées : un remplacement total à nombre égal passait — corrigé (LF-32, différence d'ensembles)
BUG [lists] : ✅ un téléchargement avec une horloge très en avance figeait âge et LISTS_STALE jusqu'à cette date — corrigé (LF-32, en-tête Date du serveur)
