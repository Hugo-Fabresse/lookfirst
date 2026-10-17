# Reputation bundle: format, signature, update

Status: proposed (LF-22). Design only, no code. Implementation tickets: the lists pipeline (LF-25) and the three app tickets listed in section 11.
Sources: ADR-0002, ADR-0005, ADR-0006, ADR-0007, `architecture.md` (reputation bundle pipeline, network), `threat-model.md` (T-LST-*, T-CI-*), `reputation-sources.md`, decisions D3, D10, D13 (algorithm: D19), D16.

## 1. What this document fixes

One signed, self-describing **bundle** is produced by a separate pipeline, published on Codeberg, downloaded daily by the app and also embedded in the APK. Everything the phone needs to say "listed / not listed / lists old" comes from it. The phone never sends anything about a scan (D3).

Goals, in order: a forged or replayed bundle is never installed; a damaged update never degrades what is installed; a false "Listed" (which becomes DANGEROUS) is as close to impossible as we can make it; the verifier and the reader are small enough to read in one sitting.

## 2. Contents

| File (fixed name) | Content | Size target | Why in the bundle |
|---|---|---|---|
| `manifest.json` | the manifest (section 3) | < 4 KB | what is signed |
| `manifest.sig` | detached signature (section 4) | < 256 B | |
| `reputation-phishing-database.idx` | reputation index of one source (section 5) | 4 to 8 MB | the lookup itself |
| `allowlist.idx` | popular registrable domains, same format | 0.1 to 0.5 MB | anti-poisoning (T-LST-4) |
| `public-suffix-list.txt` | PSL in the ASCII format `PublicSuffixList.parse` already reads (ADR-0006) | ~0.25 MB | registrable domain for index keys and RDAP; fresher than the APK snapshot |
| `rdap-dns.json` | IANA RDAP bootstrap (RFC 9224) | ~30 KB | optional domain-age action (ADR-0004) |
| `brands.txt` | brand list for imitation checks (`BrandList`) | ~20 KB | refreshed without an app release (ADR-0006) |
| `NOTICE.txt` | source names, licences, MIT notice | < 16 KB | licences screen (reputation-sources.md) |

One index file per source: adding URLhaus later is a new file, not a new format. The source id is in the file header; the human name shown in a verdict comes from a table in the app keyed by source id, never from the bundle (a bundle string is never displayed).

Stays in the APK only (ADR-0005, ADR-0006): the Unicode tables and the UTS #46 implementation, the code, the public keys, the embedded copy of the **initial bundle** (same files, same signature, verified like a download) and the fallback PSL and brand list used by `domain.host` when no bundle file can be read. The embedded bundle is the version floor.

## 3. Manifest

UTF-8 JSON, one object, produced by the pipeline with sorted keys and no insignificant whitespace.

```json
{
  "formatVersion": 1,
  "bundleVersion": 1234,
  "generatedAt": "2026-10-17T03:12:09Z",
  "sources": [
    {"id": "phishing-database",
     "url": "https://raw.githubusercontent.com/Phishing-Database/Phishing.Database/<commit>/<file>",
     "sha256": "<hex of the upstream file>", "date": "2026-10-17T02:50:00Z"}
  ],
  "files": [
    {"path": "reputation-phishing-database.idx", "size": 6401024, "sha256": "<hex>", "entries": 800000},
    {"path": "allowlist.idx", "size": 400064, "sha256": "<hex>", "entries": 50000},
    {"path": "public-suffix-list.txt", "size": 250000, "sha256": "<hex>"}
  ]
}
```

Rules the verifier enforces (after the signature, section 6 step 2):
- `formatVersion`: integer. Greater than the app supports: reject with reason `APP_TOO_OLD` (settings can suggest updating the app). Within a version, new optional fields are ignored; removing or re-typing a field is a new `formatVersion`.
- `bundleVersion`: integer, monotonic, 1 to 2^31-1, incremented by the pipeline at each publication.
- `generatedAt`: UTC, `YYYY-MM-DDThh:mm:ssZ` only. It is the only date used for list age (ADR-0005).
- `sources`: provenance for humans and for the pipeline's reproducibility check. The app uses only `id` (name table) and trusts nothing else in it.
- `files`: `path` must match `[a-z0-9._-]{1,64}`, no slash, no `..`. The app downloads only the fixed names of section 2 and fails if one is missing; an unknown extra path is ignored (never fetched). The base URL is compiled in, never read from the manifest. Duplicate paths or duplicate JSON keys: reject (strict parser). `entries` is checked against the index header.
- Caps, checked against `size` before any download: index 16 MiB, allow-list 2 MiB, PSL 1 MiB, RDAP 512 KiB, brands 256 KiB, notice 16 KiB, manifest 64 KiB; sum of files 24 MiB. These replace the byte numbers of T-LST-3.

## 4. Signature

Algorithm: ECDSA P-256, SHA-256 (ADR-0007: Ed25519 is native only from API 33, minSdk is 23, no home-made crypto, no dependency). Encodings are the ones `java.security` reads natively: public key as X.509 SubjectPublicKeyInfo (91 bytes), signature as ASN.1 DER (at most 72 bytes).

`manifest.sig` is a single ASCII line, at most 256 bytes, no JSON (nothing to parse before the signature is checked):

```
lookfirst-sig-v1 <keyId> <base64 DER signature>
```

`keyId` is the first 8 hex characters of the SHA-256 of the public key; it only selects which pinned key to try. The signed message is:

```
"LookFirst bundle manifest v1\n" || exact bytes of manifest.json
```

The prefix separates this use of the key from any other (key list, future files). The signature covers the bytes as downloaded; nobody re-serialises JSON, so there is no canonicalisation to get wrong. Each file is then authenticated by the SHA-256 the signed manifest gives for it.

Keys:
- The APK pins two public keys: **S**, the signing key held as an environment secret of the lists workflow, and **R**, an offline recovery key (never on a runner). A manifest signed by S or R is accepted.
- Routine rotation: ship the new key S' in an app release while the pipeline still signs with S; when the minimum app version we care about contains S', the pipeline switches to S'. Two keys are accepted during the transition because the app pins both until S is dropped by a later release.
- Rotation without an app release (optional, see section 13): `keyset.json` + `keyset.sig` (same line format, prefix `"LookFirst keyset v1\n"`) list the accepted keys with a monotonic `keysetVersion`. **It is signed by R only, not by the outgoing key.** This deviates from the earlier idea "key list signed by the old key": if S leaks, the attacker holds the old key, so a list signed by it lets him add his own key and lock us out for good; with R offline, a leaked S is repaired by publishing a keyset that drops S, no app update needed. The cost is a rare manual signing with R. The app stores the highest `keysetVersion` seen (same anti-rollback rule).
- If S and R both leak, the only recovery is an app release with new keys. Accepted.

## 5. Index format

### 5.1 What is looked up
Matching is exact on the normalised host. Keys are the **ASCII form produced by `HostNormalizer`** (`NormalizedHost.ascii`: UTS #46 non-transitional, lower case, punycode, no trailing dot, no percent-encoding). Two entry kinds, hashed separately:

| Kind | Meaning | Matches |
|---|---|---|
| `h:` host | exactly this host | only that host |
| `d:` domain | a registrable domain | that domain and every subdomain |

Lookup for a host H with registrable domain R (from the bundle's PSL, private section included: `tenant.blogspot.com` is registrable, `blogspot.com` is not): probe `h:H`, then `d:` for H and each parent down to R **included**, never the public suffix. At most 16 probes (a hostile 100-label host does not cost 100 searches; beyond the cap only H and R are probed). A host with no registrable domain (IP literal, public suffix, unresolved host) probes `h:H` only.

### 5.2 Why exact, and why hashes
Options for roughly 0.5 to 1 million entries:

| Structure | Size (800k entries) | Lookup | False positives | Verdict |
|---|---|---|---|---|
| Bloom filter | ~1 MB at 1e-3; ~2 MB at 1e-5 | O(k) | yes, and a false positive is a false DANGEROUS | no |
| Xor / ribbon filter, 32-bit fingerprints | ~3.5 MB | O(1) | ~2e-10 per probe | no: still approximate, specialised builder and reader |
| Sorted strings, reversed labels, front-coded in blocks | ~6 to 9 MB (estimate) | binary search on block heads, then a scan | none | no: exact, but a more complex reader for no size gain |
| **Sorted array of 64-bit truncated SHA-256** | **8 B x n = 6.4 MB** | **O(log n), ~20 comparisons** | **~5e-14 per probe** | **chosen** |

Truncation: the first 8 bytes of `SHA-256(kind byte || ASCII name)`, kind byte `0x68` ('h') or `0x64` ('d'). With n = 1,000,000, a name that is not in the list collides with some entry with probability n / 2^64 = 5e-14 per probe, at most 16 probes: below 1e-12 per scan. Over 10^9 scans in the app's lifetime that is an expected 10^-3 false "Listed" in total. It is not literally zero and we say so; it is orders of magnitude below the chance that the source itself is wrong about a domain, which the verdict wording already handles ("reported by public phishing lists"). The attacker cannot engineer a collision: he does not control the index content, and if he could poison the list he could insert the real name directly (that case is the allow-list's job, section 8). No per-entry payload: listed or not.

Entry width is fixed at 8 bytes in `formatVersion` 1 (6 bytes would save 25 % at 3.5e-9 per probe: not worth it now, possible in a later `formatVersion`).

### 5.3 File layout
Big-endian integers. No compression, no archive, no nested container (no decompression bomb, T-LST-3).

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | magic `LFIX` |
| 4 | 2 | index format = 1 |
| 6 | 2 | source id (0 = allow-list, 1 = Phishing.Database, ...) |
| 8 | 4 | entry count n |
| 12 | 20 | reserved, zero |
| 32 | 32 | SHA-256 of the PSL file the pipeline used to classify `h:` / `d:` |
| 64 | 8n | entries, unsigned 64-bit, strictly increasing |

`size == 64 + 8n` exactly. The PSL hash must equal the hash of `public-suffix-list.txt` in the same manifest: the keys and the phone's lookup must agree on what "registrable" means.

Install-time validation (once, off the main thread): magic, versions, size, `n` within caps, strictly increasing order (one linear pass), no trailing bytes. Lookup-time: bounds only. The file is opened with `FileChannel.map(READ_ONLY)` and searched in place with `MappedByteBuffer.getLong`: no heap copy, no parsing, the OS pages in what the ~20 probes touch. A 6.4 MB mapping is harmless even on 32-bit devices.

### 5.4 Size on the phone
Phishing.Database publishes only compressed sizes (README: 4.4 MB for the domains archive, 14 MB for the links), so the entry count is **not known**; the order is 0.5 to 1 million domains. That gives an index of 4 to 8 MB on disk and for the download (hashes do not compress), plus about 0.3 MB of other files. The embedded copy adds the same to the APK. The pipeline's reduction (ACTIVE domains only, duplicates and covered subdomains removed, section 10) keeps it at the low end; LF-25 must report the measured figure. If it exceeds 8 MB, the options are the 6-byte width or dropping the `h:` long tail: a format decision, to take then.

## 6. Update

Trigger: `PeriodicWorkRequest` every 24 h (flex 6 h), unique name, `ExistingPeriodicWorkPolicy.KEEP`, constraints `NetworkType.UNMETERED` and battery not low; a user setting disables it (ADR-0002, architecture "Where the network is used"). "Update now" is a one-time request on any connected network, started by the user, with a 15-minute cooldown. The worker never enqueues itself.

Fixed endpoint: an `https://` URL on Codeberg compiled into the app, one directory holding `manifest.json`, `manifest.sig` and the files by their fixed names. No redirect to another host is followed. System TLS validation, no certificate pinning (the signature protects integrity; pinning a certificate that rotates would only create outages). OkHttp in `data.network` (`ListDownloader`): no cookies, no auth, generic UA, `Accept-Encoding: identity`, cleartext forbidden by the network security config. The URL is not configurable at runtime.

Steps; every one must pass, the first failure stops and keeps the installed bundle:
1. Download `manifest.sig` and `manifest.json` to a temporary file, counting bytes while streaming (caps in section 3; Content-Length is never trusted).
2. **Verify the signature** over prefix + bytes with the pinned keys. Nothing from the manifest is read before this.
3. Parse the manifest strictly; check `formatVersion`, field shapes, path rules, caps.
4. **Anti-rollback:** `bundleVersion` must be strictly greater than the floor, where floor = max(embedded bundle version, installed bundle version, highest version ever accepted). The last term is stored separately so that removing a broken install cannot lower the floor. Equal to the installed version means "up to date": stop with success, no file download.
5. Download each file whose `sha256` differs from the installed file of the same name (PSL, RDAP, brands rarely change), streaming into a new temporary directory with a byte cap and hashing as it goes. Size and SHA-256 must match the manifest exactly.
6. Validate each file's format (index per 5.3; PSL parse; RDAP JSON size and shape; brand lines). The index's PSL hash must match the PSL file.
7. **Variation check** against the installed index: entry count within +-50 % (T-LST-4; the pipeline's own threshold is tighter, section 10), and no registrable domain of `allowlist.idx` present as a `d:` entry in the new index (the pipeline removes them; one left means a bug or an attack).
8. **Atomic install:** fsync the files, rename the temporary directory to `bundles/<bundleVersion>`, write the pointer file `current` (version + manifest SHA-256) under a temporary name, fsync, rename over the old pointer. Only then delete older bundle directories. A crash at any point leaves the old or the new bundle, never a mix.
9. Persist `highestAcceptedVersion` and the last success time; clear the failure record.

At load (cold start, off the main thread): read the pointer, re-verify `manifest.sig` and the file sizes, map the indices. The SHA-256 of big files is not recomputed at each start (app-private storage is outside the threat model; an attacker able to write there already owns the app). Any failure at load: fall back to the embedded bundle and mark the installed one bad. If the embedded bundle has a higher version than the installed one (after an app update), the embedded one is used and the installed one deleted.

Failure handling:
- A failure never touches the installed bundle. The app records one enum reason (`NETWORK`, `HTTP`, `TOO_LARGE`, `BAD_SIGNATURE`, `BAD_MANIFEST`, `ROLLBACK`, `HASH_MISMATCH`, `BAD_FORMAT`, `VARIATION`, `APP_TOO_OLD`, `STORAGE`) and a time, shown in settings. No telemetry.
- Loop guards: network, HTTP 5xx and storage errors return `Result.retry()` with exponential backoff (start 1 h, cap 8 h) and at most 4 attempts per 24 h. Security or format rejections return `Result.success()`: retrying the same bytes cannot help; the next periodic run tries again. For a rejected `bundleVersion` the app remembers (version, manifest SHA-256) and does not re-download its files until the manifest changes, so a bad 6 MB bundle is not fetched every few hours.

Age and clock: `age = max(0, referenceNow - generatedAt)` with `referenceNow = max(device clock, generatedAt of the installed and embedded bundles)`. A device clock set back cannot make the lists look younger than dates the app itself has seen; a `generatedAt` slightly in the future (our own clock error, signed) is clamped to age 0, not rejected. A device clock far in the future makes lists look old, the cautious direction. A user who sets the clock back by days to hide staleness controls the device: accepted. Age is shown in whole days; above 14 days it becomes the CAUTION reason (D16). To fix in the UX tickets: T-LST-5 still says 7 days while D16 and ux.md say 14.

First launch and offline: the embedded bundle is the installed one until a download succeeds (ADR-0005). An old APK started offline shows stale lists honestly, because the age comes from `generatedAt`.

Download volume: with a 6 MB index that changes daily, about 6 to 8 MB per day on unmetered networks. Acceptable for the MVP. A delta update (sorted hash arrays make a merge diff against a known `bundleVersion` cheap, likely a few hundred KB) is a later optimisation, not part of `formatVersion` 1.

Order, in one line: signature -> manifest shape -> anti-rollback -> per-file size and SHA-256 -> per-file format -> variation and allow-list -> atomic swap. Never reorder: parsing before verifying is how formats get exploited.

## 7. Mapping to the existing port
`ReputationLookup.lookup(host: NormalizedHost): ReputationResult` stays synchronous and offline. The implementation (in `data/reputation`) holds the mapped indices and PSL of the current bundle behind an atomically swapped reference; `domain/` sees no file, no URL, no Android type. `ListsMissing` happens only if neither the installed nor the embedded bundle can be read. `ListsStale(ageDays)` comes from `generatedAt` as above. `Listed(source)` carries a name from the app's own table.

## 8. Allow-list behaviour
`allowlist.idx` holds popular registrable domains (a few tens of thousands; source open, section 13). In the app, a `d:` hit on a registrable domain that is in the allow-list is ignored (defence in depth: the pipeline already removes these entries). An `h:` hit for a specific subdomain of an allow-listed domain (a hosted-content subdomain of a large provider, say) stays valid: that is real abuse of a shared platform and the entry names only that host. The pipeline drops `h:` entries equal to an allow-listed domain or to `www.` plus it. The bundle only answers `Listed`, `NotListed`, `ListsMissing` or `ListsStale`; the verdict engine (LF-17) decides the level.

## 9. Not in this format
Delta updates, IP-address entries, per-entry metadata (category, date), hash widths other than 8 bytes. Each would be a new `formatVersion` or a new file, not a patch of this one.

## 10. Production pipeline (summary; detail in LF-25)

Repository `lookfirst-lists` (separate, minimal), run daily and on demand:
1. **Fetch (job A, no secrets):** upstream at a commit SHA resolved at the start of the run and recorded in the manifest; HTTPS; SHA-256 and size caps. The IANA RDAP bootstrap and the PSL are fetched the same way (the PSL pinning conventions of LF-20 apply).
2. **Validate:** strict canonical entries only. An entry is kept only if it is already ASCII, lower case, labels in `[a-z0-9_-]`, `xn--` labels that decode and re-encode to themselves, no trailing dot, no IP literal, within DNS length limits. The pipeline does **not** try to normalise: a Python UTS #46 that disagrees with `HostNormalizer` would silently miss entries. Rejected entries are counted and reported; a reject rate above a set threshold fails the job. A small conformance corpus of (input, expected ASCII) pairs is shared between the pipeline's tests and the app's tests so both sides provably agree on canonical forms.
3. **Reduce:** deduplicate; classify with the pinned PSL (registrable domain -> `d:`, deeper host -> `h:`; drop `h:` covered by a `d:` of the same list); remove allow-listed domains; hash, sort, write. Byte-for-byte reproducible: same inputs, same bytes; only `generatedAt` and `bundleVersion` move.
4. **Variation check:** compare with the previously published manifest, fetched from Codeberg and verified with the public key. Fail above +-20 % entries, on any allow-list collision above a set count, or on a file size jump beyond a set bound. A human override is a separate, logged manual run.
5. **Sign (job B, the only job with the key):** receives the artefact by digest, recomputes the hashes, writes the manifest, signs with S (protected environment secret). It never executes code derived from the untrusted data. R is never on a runner.
6. **Publish:** to the fixed Codeberg location, files first, `manifest.sig` and `manifest.json` last, so a phone never sees a manifest whose files are missing. The repository must not keep a daily 6 MB binary in git history (about 2 GB per year): publish to a force-replaced branch or to release assets; settle in LF-25 with Codeberg's usage policy.
7. Actions pinned by SHA, minimal permissions, secret scanning (T-CI-1, T-CI-3, T-CI-4). The app's release build fetches the latest bundle, **verifies it with the pinned keys** and embeds it (ADR-0005).

## 11. Implementation tickets this design implies
- LF-25: pipeline, with the conformance corpus.
- App 1: bundle store and verifier (manifest parser, ECDSA verification with pinned keys, version floor, atomic install, load, embedded bundle) in `data/reputation`.
- App 2: index reader (mmap, binary search, probe set) implementing `ReputationLookup`.
- App 3: update job (WorkManager, `ListDownloader` in `data.network`, retry rules, settings entry: age, last failure reason, update now, disable).
- Hand tests for them: ECDSA verification on a real API 23 to 25 phone (unit tests run on the JDK provider, not the platform's); airplane-mode first launch; kill the app during an update; corrupt a file on a test server.

## 12. Threats and risks

| Threat | Counter-measure here |
|---|---|
| T-LST-1 MITM | HTTPS, and the signature: TLS is not what protects integrity |
| T-LST-2 hosting compromise | signature over the manifest, per-file SHA-256, pinned keys, R offline |
| T-LST-8 rollback / replay | version floor (embedded, installed, highest accepted); a freeze attack shows as age, CAUTION after 14 days |
| T-LST-3 oversized / malformed | caps before and during download, identity encoding, no archive or compression, fixed binary layout validated in one pass |
| T-LST-4 poisoning | allow-list dropped in the pipeline and again in the app, +-20 % pipeline and +-50 % app variation thresholds; a hit only adds a warning |
| T-LST-5 stale lists | age from `generatedAt`, CAUTION above 14 days, embedded bundle |
| T-LST-6 IP seen by the host | generic request, no identifier; privacy notice |
| T-LST-7 licences | only confirmed sources; `NOTICE.txt` |
| T-CI-3 pipeline compromise | split jobs (untrusted data never next to the key), validation and variation checks, pinned actions |

Accepted risks:
- A compromise of the lists workflow yields a validly signed bundle (T-CI-3), bounded by "a list only adds warnings, never opens anything" and by the allow-list and variation checks. S and R both leaked: app release needed.
- 64-bit truncation: about 1e-12 per scan of a false "Listed" (5.2).
- Non-canonical entries (IDN-only entries of a source) are dropped until a converter proven against `HostNormalizer` exists.
- The device clock is not trusted against a user who controls the device.
- "Not listed" is never "safe": "no risk signal found" (product rule).

## 13. Open points
- **Phishing.Database upstream feeds** (reputation-sources.md, T-LST-7): which feeds it merges and under what terms. Needed before the first public bundle; blocks LF-25's publication step, not this format.
- Real entry count and size of the ACTIVE domains list (measure in LF-25).
- Source of the allow-list: a published top-sites ranking with an acceptable licence and a stable download; none selected.
- Codeberg: terms for daily binary downloads by many phones, where exactly to publish (Pages, release assets, force-replaced branch), and whether a second pinned mirror is wanted (the signature makes it safe, but it adds a host that sees phone IPs).
- Runner for the pipeline: GitHub Actions with a protected environment versus Codeberg's own CI; the constraints of section 10 hold either way.
- Whether `keyset.json` ships in the first implementation or rotation goes through app releases only at first (section 4).
