# LookFirst — Bugs

Entrées résolues depuis plus de 2 semaines : à déplacer dans `docs/archive/`.

Format :
```
BUG [domaine] : ❌ description courte — statut: OPEN
BUG [domaine] : ✅ description courte — corrigé (LF-n)
```

BUG [url-analysis] : ✅ `hôte:port/chemin` sans schéma échappait aux listes de réputation alors qu'il s'ouvrait en https — corrigé (LF-30)
BUG [build] : ✅ le gate complet échouait : printStackTrace de WorkManager (WorkTypeConverters) fusionné par R8 dans une classe app — corrigé (LF-29)
BUG [lists] : ✅ sous-domaines de wordpress.com / free.fr / ovh.net ignorés (parseur, lookup, BrandMatcher) : les entrées CERT Polska *.wordpress.com perdues, "aucun signal" affiché — corrigé (LF-31)
BUG [brand] : ✅ mobile.free.fr (service Free réel) recevait un signal TYPO (proche de t-mobile) — corrigé (LF-31, libellés réservés par plateforme)
BUG [brand] : ✅ budget et indice TYPO calculés sur le squelette (tmobile = 8 car.) : rnobile.* (mobile.de) signalé sans indice ; telekom, comcast, cetelem, abnamro, amendes perdaient l'exigence d'indice, macif / monzo / ameli gagnaient une édition — corrigé (LF-43)
