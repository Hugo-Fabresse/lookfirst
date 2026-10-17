# Reputation lists: download, checks, index

Status: LF-28 (decision D20, ADR-0008). Replaces `reputation-bundle.md` (LF-22: signed bundle, Codeberg, ECDSA), which no longer applies.
Sources: ADR-0002, ADR-0006, ADR-0008, `reputation-sources.md`, `threat-model.md` (T-LST-*), decisions D3, D9, D16, D20.

## 1. What this document fixes

The phone downloads each public list from its publisher, checks it, turns it into a local index and looks hosts up in that index. Nothing about a scan is ever sent (D3). There is no bundle of ours, no signature and no key: the checks below replace the signature as the guard against a bad list, and the design rule is that **a list can only add a warning, never lower a verdict**.

Code: `app/src/main/java/com/lookfirst/data/reputation/`.

| Class | Role |
|---|---|
| `ListSources` / `ListSource` | the sources: id, name, fixed https URL, download cap, entry bounds |
| `PublisherDownloader` | one GET per source, redirects, streamed cap, conditional request, independent outcomes |
| `DomainListParser` | bounded linear parser: file to sorted index keys |
| `IndexWriter` | writes the LFIX file |
| `ListStore` | installed indexes, checks, atomic install, age, failures |
| `LocalReputationLookup` | the lookup used by the verdict engine |
| `ListUpdateRunner`, `ListUpdateWorker` | one run at a time, daily job, "Update now" |

## 2. Download

- One fixed `https` URL per source, compiled in. The only address taken from the network is a redirect `Location`, followed by hand (3 hops at most) and only if it is `https`, on the same host, default port, no user information. Anything else ends the attempt.
- The size is counted while streaming. `Content-Length` is never trusted. No archive and no compression (`Accept-Encoding: identity`). Per-source cap: 8 MiB for CERT Polska (real size about 2.5 MB), 5-minute deadline per file.
- Request headers: `User-Agent: LookFirst` (no version), `Accept-Encoding: identity`, and `If-Modified-Since` when an index is installed. The publisher's `Last-Modified` is parsed strictly as an IMF-fixdate (RFC 9110, weekday and calendar checked), stored as an instant, refused if it is later than the clock, and the date sent back is formatted by the app from that instant: nothing the server sent is ever echoed. An invalid value drops the conditional request. `304` means "current": the list is kept and its age is reset. A `304` never refreshes the time of the last full download: 7 days after it, the next request is unconditional, so a server that answers `304` forever cannot freeze the list. No `ETag`, no cookie, no other validator, no identifier. The request is the same for every user and carries nothing about a scan; a test checks that every request is exactly the compiled URL.
- System TLS validation, no pinning. Timeouts on connect (15 s) and on every read (30 s).
- Sources are independent: one failing or refused source never blocks another. Order of `ListSources.ALL`.
- Work: WorkManager daily (unmetered, battery not low), backoff 1 h, 4 attempts per period; "Update now" and the first-launch choice run the same `ListUpdateRunner` (a mutex allows one download at a time). No list ships in the APK: with automatic update on, the first launch downloads at once.

## 3. File format and parser

Format: text, one host per line, UTF-8, LF or CRLF; blank lines ignored; `#` starts a comment; a leading byte order mark is ignored.

`DomainListParser` streams the file in 64 KiB chunks and never builds a line longer than 300 bytes (a longer one counts as invalid and is dropped without being stored). Linear time, no regular expression, memory bounded by the entry cap. Per line:

1. trim spaces, tabs, CR; skip empty and comment lines;
2. a control character (below U+0020 except tab, or DEL) makes the line invalid;
3. `HostNormalizer.normalize` (UTS #46, punycode, the same code as for a scanned host). Any error makes the line invalid. URLs, `user@host`, paths and IPv6 are invalid by construction;
4. a host whose registrable domain cannot be found (a bare public suffix such as `com` or `co.uk`) is invalid: a list must not flag a whole registry;
5. if the registrable domain is official (the embedded `BrandList.officialDomains`), the entry is dropped and counted, whether it is the domain itself or a host under it (`accounts.google.com`). Exception: on a user-content platform (`UserContentPlatforms`: wordpress.com, free.fr, ovh.net, slack.com; anyone gets `name.platform` and the suffix is not in the private PSL) only the platform host, `www.` and the platform's reserved service labels (`mobile.free.fr`, `api.slack.com`) are official, and `vps.ovh.net` is a sub-suffix (`vps-xxx.vps.ovh.net` is the tenant); `name.platform` is a registrable name of its own (key `d:name.platform`, deeper hosts `h:`), is not counted as poison and is indexed;
6. the key: `d:` + name if the host is a registrable domain, otherwise `h:` + name (also for IPv4).

Without the Public Suffix List nothing is classified: the file is refused (`BAD_FORMAT`). Refusals of the whole file: no valid line (`TOO_FEW`); more than 2 % invalid lines, which covers binary files, HTML error pages and wrong formats (`BAD_FORMAT`); more entries than the source's maximum (`TOO_MANY`, checked while reading); more than 100 entries on or under official domains (`POISONED`; the CERT Polska file of 2026-10-17 has 2).

## 4. Checks before an index replaces another

Applied in `ListStore.install`; any refusal keeps the installed index byte for byte, is remembered per source (reason and time) and is not retried in the same run.

| Check | Value | Why |
|---|---|---|
| Download size | 8 MiB per source (total of the compiled sources stays under 10 MiB) | T-LST-3 |
| Entries (CERT Polska) | at least 40 000 (about a third of today's size; revise if the list grows) and at most 600 000 (today about 125 000) | an emptied or exploded list is refused even on the first install |
| Variation against the installed index | entries removed AND entries added (set difference of the two sorted key arrays, one linear merge over the mapped file, about 7 ms for 125 000 entries) must each be at most 10 % + 2 points per day of age of the installed index, of its entry count; no limit from 45 days | a poisoned or truncated update cannot swap most of the list in one step, even at equal size (LF-32); a phone that was off for weeks must be able to catch up. An installed index that is not strictly increasing (damaged) is no baseline: it is replaced like a first install, the entry bounds still apply |
| Format | 2 % invalid lines at most; index written and validated (`Lfix.validate`: magic, size, strictly increasing keys) before the swap | |
| Official domains | more than 100 entries on or under official domains: refused; fewer are dropped | an attempt to make the app cry wolf on popular sites (T-LST-4) |

Age of the installed index: time since the last successful download or `304`, against `max(clock, latest time ever seen)`. A download or `304` is recorded at that same reference time (never at a clock that is behind it), so a clock that was set ahead and then corrected neither makes a list look old nor lifts the variation limit. The publisher's `Date` header (strict IMF-fixdate, read from the response; nothing is added to the request) is untrusted: the publisher chooses it, so it can only make the app more careful. It never lifts the variation limit and never makes a list younger: the tolerance depends on the local age alone, and a time is never recorded below the device clock. `Date` may only bring a latest time seen that lies more than a day after it down to `max(clock, Date + 1 day)`. An installed index whose recorded time is after both the clock and `Date + 1 day` (recorded with a clock set far ahead) has an unknown age: lookups answer `LISTS_STALE` (CAUTION) with the limit kept strict until the next successful contact records a sound time (kept in memory). Residual: a frozen age can keep updates refused as `VARIATION`, a denial of service the publisher can already cause by serving a list that fails the checks. Network, HTTP, size and format failures are remembered per source like refusals.

## 5. Storage and installation

`filesDir/reputation/` (outside backups: `allowBackup="false"` and the extraction rules):

- `indexes/<source id>.lfix`: one file per source;
- `tmp/`: downloads and index candidates; leftovers are removed at load;
- `state`: per source the time of the last check, the validator and the last failure; plus the latest time ever seen. Damaged or oversized: treated as empty.

Install: write `tmp/index-N`, `fsync` the file, validate it, `rename` over `indexes/<id>.lfix`, `fsync` the directory, then write the state (temporary file, `fsync`, rename) and map the new file. A crash leaves either the old or the new index, never a mix. At load an index whose size and header disagree, or whose source id is not the file's, is deleted.

## 6. Index format (LFIX)

Unchanged from LF-22/LF-23, except that bytes 32 to 63 (once a Public Suffix List hash) are written as zero and not read: the PSL is embedded in the APK. They stay in the layout so that `size == 64 + 8n` holds.

Matching is exact on the normalized host (`NormalizedHost.ascii`). Two kinds, hashed separately:

| Kind | Meaning | Matches |
|---|---|---|
| `h:` host | exactly this host | only that host |
| `d:` domain | a registrable domain | that domain and every subdomain |

Key: the first 8 bytes of `SHA-256(kind byte || ASCII name)`, kind byte `h` or `d`. Sorted array of unsigned 64-bit keys, binary search in a read-only mapped file: no heap copy, no parsing at lookup.

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | magic `LFIX` |
| 4 | 2 | format = 1 |
| 6 | 2 | source id (the app's table: CERT Polska = 2; 0 and 1 belonged to the removed bundle) |
| 8 | 4 | entry count n |
| 12 | 20 | reserved, zero |
| 32 | 32 | reserved, zero (was the PSL hash) |
| 64 | 8n | entries, strictly increasing, unsigned |

`size == 64 + 8n` exactly. Truncated hashes: a name that is not listed collides with some entry with probability n / 2^64 per probe (5e-14 for a million entries, at most 16 probes). That is the accidental rate, not a security bound: an attacker who can put names of his choice in a source could search for a collision with a chosen victim host (about 2^64 / T work for T targets). The result is a false "reported by public lists", never a bypass; popular domains are out of reach because the embedded official-domain list is consulted first. Accepted for format 1; a 12-byte entry (new format version) is the lever if it ever matters.

## 7. Lookup

`LocalReputationLookup`, synchronous and offline, never waits for an installation (while one holds the lock the answer is "lists missing"):

1. registrable domain R from the embedded PSL (private section included: `tenant.blogspot.com` is registrable);
2. **embedded official domains first**: if R is official, no entry applies to R or to anything under it (the parser does not index such entries either). A phishing page on a service subdomain of a popular official domain is therefore never flagged by a list (residual risk, T-LST-4), with one exception: on a user-content platform (`UserContentPlatforms`) R is the tenant name (`label.wordpress.com`, like `label.blogspot.com`), so only the platform host and `www.` are official and every tenant is looked up and indexed; the brand matcher judges the tenant label with `onPlatform` and the other analyses apply normally. Without the Public Suffix List the answer is `ListsMissing`, never a hit or a clean result;
3. otherwise, for each source: `h:` for the host, then `d:` for the host and each parent down to R included, never the public suffix; at most 16 probes per source;
4. a hit gives `Listed(<publisher name from the compiled table>)`; no hit gives `NotListed`, or `ListsStale(age)` when the oldest installed index is older than 14 days (D16); no index gives `ListsMissing`. A hit is never lowered by staleness.

The verdict wording stays "reported by public phishing lists" with the list name, and "no risk signal detected" is never "safe".

## 8. Not in this design

- RDAP bootstrap and its use: LF-27 (embedded in the APK).
- A second source: `reputation-sources.md`.
- URL-level matching (only hosts and domains are indexed).
- Any server operated by LookFirst.

## 9. Threats

`threat-model.md`: T-LST-1 to T-LST-8, T-CI-3 (no longer applicable: no lists workflow, no key).

## 10. Hand tests (phone)

See the PR of LF-28: first-launch download, "Update now" online and offline, airplane mode mid-download, an update after a day (304), Settings and About texts in French and English.
