# Architecture Decision Records

Le *pourquoi* des choix structurants. Un fichier par décision : `NNNN-titre-court.md` (contexte, décision, conséquences). Les décisions courtes vivent dans `DECISIONS.md` ; ce qui structure le code reçoit aussi un ADR.

| N° | Titre |
|---|---|
| 0001 | Local-only analysis |
| 0002 | On-device reputation lists |
| 0003 | Explicit user action |
| 0004 | Optional network actions (redirections et RDAP) |
| 0005 | Initial signed bundle embedded in the APK (replaced by 0008) |
| 0006 | Embedded UTS #46 host normalization (no ICU) |
| 0007 | ECDSA P-256 for the reputation bundle signature (replaced by 0008) |
| 0008 | Reputation lists downloaded directly from their publishers |
