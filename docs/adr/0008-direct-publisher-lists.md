# 0008 - Reputation lists downloaded directly from their publishers

Status: accepted (LF-28, decision D20 of 2026-10-17). Replaces ADR-0005 (initial bundle embedded in the APK) and ADR-0007 (ECDSA P-256 bundle signature) entirely, and the parts of ADR-0002 that speak of our own signing key, our lists pipeline and Codeberg. Replaces decisions D10, D13 and D19.

## Context
LF-22 to LF-24 designed a signed bundle: a separate repository and workflow fetched upstream lists, reduced them, signed a manifest with ECDSA P-256 and published it on Codeberg; the app embedded a first bundle and pinned two public keys. That design needs keys to generate and protect, a publication pipeline to run, a host to depend on and a recovery procedure, for a free app maintained by one person. Hugo decided to drop all of it.

## Decision
- No bundle published by LookFirst: no lists repository, no publication CI, no Codeberg, no signing key, no pinned keys, no manifest, no version floor, no key epoch.
- The phone downloads the public lists straight from their publishers over `https` (fixed URL per source, compiled in), validates them, and builds its own index (LFIX, truncated SHA-256 keys) in app-private storage. Nothing about a scan is ever sent; the publisher sees the phone's IP at update time (disclosed in About).
- Sources must be downloadable without account or key, allow automated use by a free app, be plain text, small (target 10 MiB per update) and updated daily; non-US hosting is preferred. First source: CERT Polska Warning List (`reputation-sources.md`).
- The Public Suffix List is embedded in the APK (ADR-0006). The RDAP bootstrap is embedded too (LF-27). No list file is trusted to change them.
- No list ships in the APK. D16 stands for age: lists older than 14 days give a CAUTION reason; with no list installed the verdict carries "lists missing" until the first download, which starts at once when the user ends the first-launch screen with automatic update on.

## Replacing the signature: what protects the user
We lose authenticity (anyone who controls the publisher or the connection after TLS can serve a list). The compensations, all on the phone (`reputation-lists.md`):
1. **A list can only add a warning.** It never lowers a verdict, never removes a built-in rule, never changes the PSL, the brand list or the RDAP server.
2. **The embedded official-domain list is consulted first**: a hit on an official registrable domain or on any host under it (`accounts.google.com`) is ignored and not indexed; more than 100 such entries make the list refused as poisoned.
3. Size cap (streamed), entry minimum and maximum, 2 % invalid-line limit, strict parser, index validated before the swap.
4. **Variation limit** against the installed index: entries removed and entries added (set difference), each at most 10 % plus 2 points per day of age of the installed count, none from 45 days (LF-32; a count alone let a full swap through). The publisher's `Date` header is never a reason to lift this limit.
5. A refusal keeps the previous index and is remembered; sources are independent.
6. `https` only, same-host redirects, no cookies, no identifier. `If-Modified-Since` carries only a date the app formats itself from the publisher's strictly parsed `Last-Modified` (never echoed), and only while the last full download is under 7 days old.

## Consequences
- Much less to run and to lose: no key theft scenario, no pipeline compromise (T-CI-3 disappears), no rollback logic. A compromised publisher can inject false warnings within the variation limit; accepted and documented (T-LST-2, T-LST-4).
- A false warning on a non-official domain is possible and cannot be undone by the app within a run. The wording stays "reported by public lists", with the list name.
- No offline coverage on first launch before the first download.
- Coverage depends on one publisher at the start. Adding a source is a code change (`ListSources`) guided by `reputation-sources.md`.
- LF-33 (audit S-4): that publisher is Polish and covers few French scams (measured: allegro 9,260 entries against antai 11, ameli 10, colissimo 3). The app says so in About and Settings and names the list in the verdict's "checks run" line; the other checks stay active. A second source was studied (phishunt.io, CIRCL, CERT-FR, CERT.at/EE, SWITCH, Phishing Initiative, Spamhaus, URLhaus): none passes the criteria with certainty, so none was added (`reputation-sources.md`).
- The implementation removed: `PinnedKeys`, `SignatureVerifier`, `Keyset`, manifest and bundle verifier, `ListsEndpoint`, embedded bundle source, old store and downloader, the Gradle publication guards.
- Thresholds are recorded in `DECISIONS.md` (D20).
