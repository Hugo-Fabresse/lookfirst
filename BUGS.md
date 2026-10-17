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
BUG [lists] : ✅ un téléchargement avec une horloge très en avance figeait âge et LISTS_STALE jusqu'à cette date — corrigé (LF-32, âge inconnu signalé LISTS_STALE, limite gardée ; Date jamais utilisée pour la lever)
BUG [brand] : ✅ budget et indice TYPO calculés sur le squelette (tmobile = 8 car.) : rnobile.* (mobile.de) signalé sans indice ; telekom, comcast, cetelem, abnamro, amendes perdaient l'exigence d'indice, macif / monzo / ameli gagnaient une édition — corrigé (LF-43)
BUG [brand] : ✅ LF-43, effets de bord assumés (moins de faux positifs) : budget 2 → 1 sur 9 lettres (microsoft, instagram, colissimo, metamask, cryptocom, moneygram, royalmail, mcdonalds, epicgames) ; indice exigé pour walmart, matmut, cetelem, amendes ; venmo sans faute de frappe (venmoo.xyz n'est plus signalé) ; risque résiduel : faute sans indice (amelli.fr, tmobil.example) non signalée — fautes dans un morceau à tirets : LF-44
BUG [analyse] : ✅ S-6 : localhost, hôte à un label, *.local / *.internal / *.lan / *.home.arpa, fritz.box ou TLD inexistant ne produisaient aucun signal (https://localhost/x = NO_RISK_SIGNAL) — corrigé (LF-34, LOCAL_HOST)
