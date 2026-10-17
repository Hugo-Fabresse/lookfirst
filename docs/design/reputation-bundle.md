# Reputation bundle: format, signature, update

Status: proposed (LF-22), revised after the security review. Design only, no code. Implementation tickets: the lists pipeline (LF-25) and the three app tickets listed in section 11.
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
| `allowlist.idx` | `d:` hashes of popular registrable domains, same format | 0.1 to 0.5 MB | anti-poisoning (T-LST-4) |
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
  "keyEpoch": 0,
  "bundleVersion": 413291,
  "generatedAt": "2026-10-17T03:12:00Z",
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
- `keyEpoch`: integer, the key epoch the manifest belongs to (section 4). Must equal the epoch of the key set the app accepts.
- `generatedAt`: UTC, `YYYY-MM-DDThh:mm:00Z` (whole minutes) only. It is the only date used for list age (ADR-0005). **Rejected if later than the device clock + 48 h** (reason `FUTURE_DATE`; a device clock more than 48 h behind therefore blocks updates, fail-safe).
- `bundleVersion`: integer **derived from `generatedAt`**: the number of whole minutes since 2026-01-01T00:00:00Z. The app recomputes it and rejects any other value. The pipeline never publishes twice in the same minute. Consequence: a signing key thief cannot jump the version counter beyond the date he writes, and the date is capped at the clock + 48 h, so the damage of a stolen S is at most a 48 h window of the floor (section 6, step 4), repaired by a new key epoch (section 4).
- `sources`: provenance for humans and for the pipeline's reproducibility check. The app uses only `id` (name table) and trusts nothing else in it.
- `files`: `path` must match `[a-zA-Z0-9._-]{1,64}`, no slash, no `..`. The app downloads only the fixed names of section 2 and fails if one is missing; an unknown extra path is ignored (never fetched). The base URL is compiled in, never read from the manifest. Duplicate paths or duplicate JSON keys: reject (strict parser). `entries` is checked against the index header. Format and threshold checks that apply to each file: section 6, steps 6 and 7.
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
- The APK pins two public keys for epoch 0: **S**, the signing key held as an environment secret of the lists workflow, and **R**, an offline recovery key (never on a runner). A manifest signed by S or R is accepted. **R cannot be removed** by any keyset.
- A pinned or keyset key is accepted only if it is an X.509 SubjectPublicKeyInfo of type `id-ecPublicKey` with the `namedCurve` OID of secp256r1 (1.2.840.10045.3.1.7); explicit curve parameters are refused (CurveBall class). `keyId` only selects a candidate; the candidate's **full SPKI bytes are compared** with the pinned or keyset entry before verifying.
- Routine rotation: ship the new key S' in an app release while the pipeline still signs with S; when the minimum app version we care about contains S', the pipeline switches to S'. Two keys are accepted during the transition because the app pins both until S is dropped by a later release.
- Rotation without an app release (optional, see section 13): `keyset.json` + `keyset.sig` (same line format, prefix `"LookFirst keyset v1\n"`) list the accepted manifest-signing keys with a monotonic `epoch`. **Keys of a keyset sign manifests only, never keysets**: only R signs keysets, so no key delegated by a keyset can extend or replace the key set. **It is signed by R only, not by the outgoing key.** This deviates from the earlier idea "key list signed by the old key": if S leaks, the attacker holds the old key, so a list signed by it lets him add his own key and lock us out for good; with R offline, a leaked S is repaired by publishing a keyset that drops S, no app update needed. The cost is a rare manual signing with R. The app stores the highest `epoch` seen. **A keyset that revokes S opens a new epoch.** The version floor is the pair `(epoch, bundleVersion)` compared lexicographically, so a bundle of a higher epoch is accepted whatever the version counter of the lower epoch had reached; this is how a thief of S who pushed the floor 48 h into the future is cleaned up. Manifests of an older epoch are rejected.
- If S and R both leak, the only recovery is an app release with new keys. Accepted.
- The embedded bundle is verified with the keys pinned in the APK (epoch 0), at the release build and at load; it never depends on a stored keyset.
- Pipeline side: before publishing, job B verifies the DER signature it just produced against the public key (`openssl dgst -verify`), so a malformed signature is never published.

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

Truncation: the first 8 bytes of `SHA-256(kind byte || ASCII name)`, kind byte `0x68` ('h') or `0x64` ('d'). With n = 1,000,000, a name that is not in the list collides with some entry with probability n / 2^64 = 5e-14 per probe, at most 16 probes: below 1e-12 per scan. Over 10^9 scans in the app's lifetime that is an expected 10^-3 false "Listed" in total. It is not literally zero and we say so; it is orders of magnitude below the chance that the source itself is wrong about a domain, which the verdict wording already handles ("reported by public phishing lists"). **This is the accidental rate, not a security bound.** An attacker who can get names of his choice into the source (he registers domains or subdomains, and phishes from them) can search for a name whose truncated hash equals the hash of a chosen victim host. One target costs about 2^64 SHA-256 evaluations, roughly 50 GPU-years at 10^10 hashes per second; for T targets at once the expected work is 2^64 / T, so a collision with **some** host among a million small sites costs a few GPU-hours. The result is a false "Listed" on a victim's host: denial of reputation, not a bypass of any protection. Counter-measures: the app consults the allow-list first and ignores `d:` hits on allow-listed registrable domains, so popular sites are out of reach; the attacker's name must also pass the source's own validation (a live phishing site). Residual risk accepted for `formatVersion` 1 and written down in section 12; the lever, if it ever matters, is a 12-byte entry (+50 % size, infeasible search), i.e. a new `formatVersion`. The pipeline fails if two distinct names share a truncated hash (it makes any such attempt visible).

No per-entry payload: listed or not.

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

Endpoint (Codeberg release assets, decision of the review): a base URL compiled into the app and two kinds of location, both built by the app, never taken from server data.
- `<base>/latest/manifest.json` and `<base>/latest/manifest.sig`: a release with the fixed tag `latest` that holds only these two small files, replaced at each publication.
- `<base>/<tag>/<path>` for each bundle file, where `<tag>` is the decimal `bundleVersion` of the verified manifest, checked against `^[0-9]{1,10}$` and rebuilt by the app. `<path>` is one of the fixed names of section 2.
Publication order (pipeline, section 10): create the release `<bundleVersion>` as a **draft**, upload every asset including a copy of the manifest and signature, make it public, only then replace `latest`; keep the last N = 5 releases. The window while the two `latest` files are replaced can show a mismatched pair: the app then sees `BAD_SIGNATURE`, retries once after 60 s, and otherwise waits for the next run. If the Forgejo API turns out to be indispensable (to be checked in LF-25), the app may use a bounded parser (response cap 64 KiB) that extracts the tag only and validates it as above.
Transport: only redirects that stay on the same host over `https` (at most 3) are followed; any other redirect fails the update (to check against Codeberg's asset redirects in LF-25; if assets are redirected to another host, that host is pinned in the base configuration, not discovered). System TLS validation, no certificate pinning (the signature protects integrity; pinning a certificate that rotates would only create outages). OkHttp in `data.network` (`ListDownloader`): **no `Cache`, no `If-None-Match` / `If-Modified-Since`** (a validator is a per-installation identifier), no cookies, no auth, a **constant User-Agent without app version**, `Accept-Encoding: identity`, cleartext forbidden by the network security config. The URL is not configurable at runtime.

Steps; every one must pass, the first failure stops and keeps the installed bundle:
1. Download `manifest.sig` and `manifest.json` to a temporary file, counting bytes while streaming (caps in section 3; Content-Length is never trusted).
2. **Verify the signature** over prefix + bytes with the pinned keys. Nothing from the manifest is read before this.
3. Parse the manifest strictly; check `formatVersion`, field shapes, path rules, caps; `generatedAt` not later than clock + 48 h; `bundleVersion` equal to the minutes since the epoch of `generatedAt`; `keyEpoch` equal to the accepted epoch.
4. **Anti-rollback:** `(keyEpoch, bundleVersion)` must be strictly greater, compared lexicographically, than the floor, where floor = max(embedded bundle, installed bundle, highest pair ever accepted). The last term is stored separately so that removing a broken install cannot lower the floor. Equal to the installed pair means "up to date": stop with success, no file download. A new epoch (R-signed keyset) resets the version part of the floor.
5. Download each file whose `sha256` differs from the installed file of the same name (PSL, RDAP, brands rarely change), streaming into a new temporary directory with a byte cap and hashing as it goes. Size and SHA-256 must match the manifest exactly.
6. Validate each file's format (index per 5.3; PSL parse; RDAP JSON size and shape; brand lines). The index's PSL hash must match the PSL file. RDAP: every service URL is `https`, with no IP-literal host, no explicit port, no userinfo.
7. **Variation checks**, each against the installed counterpart (the pipeline applies the same ones, tighter, section 10):
   - index: entry count within a tolerance that grows with the age of the installed bundle, `10 % + 2 % per day of age` (a daily update stays near 10 %; an app that was offline for 20 days accepts 50 %, beyond about 45 days the limit no longer applies, because lists that old are already CAUTION and a permanent refusal would freeze the user on them); no registrable domain of `allowlist.idx` present as a `d:` entry in the new index;
   - PSL: no rule of the ICANN section of the installed list disappears, and no allow-listed registrable domain becomes covered by a public suffix;
   - brands: the number of entries does not fall by more than 20 %.
8. **Atomic install:** fsync the files, rename the temporary directory to `bundles/<bundleVersion>`, write the pointer file `current` (version + manifest SHA-256) under a temporary name, fsync, rename over the old pointer. Only then delete older bundle directories. A crash at any point leaves the old or the new bundle, never a mix.
9. Persist `highestAcceptedVersion` and the last success time; clear the failure record.

At load (cold start, off the main thread): read the pointer, re-verify `manifest.sig` and the file sizes, map the indices. The SHA-256 of big files is not recomputed at each start (app-private storage is outside the threat model; an attacker able to write there already owns the app). Any failure at load: fall back to the embedded bundle and mark the installed one bad. If the embedded bundle has a higher version than the installed one (after an app update), the embedded one is used and the installed one deleted.

Failure handling:
- A failure never touches the installed bundle. The app records one enum reason (`NETWORK`, `HTTP`, `TOO_LARGE`, `BAD_SIGNATURE`, `BAD_MANIFEST`, `ROLLBACK`, `HASH_MISMATCH`, `BAD_FORMAT`, `VARIATION`, `APP_TOO_OLD`, `STORAGE`) and a time, shown in settings. No telemetry.
- Loop guards: network, HTTP 5xx and storage errors return `Result.retry()` with exponential backoff (start 1 h, cap 8 h) and at most 4 attempts per 24 h. Security or format rejections return `Result.success()`: retrying the same bytes cannot help; the next periodic run tries again. For a rejected `bundleVersion` the app remembers (version, manifest SHA-256) and does not re-download its files until the manifest changes, so a bad 6 MB bundle is not fetched every few hours.

Age and clock: `age = max(0, referenceNow - generatedAt)` with `referenceNow = max(device clock, generatedAt of the installed and embedded bundles)`, **using only `generatedAt` values that passed the checks of step 3** (the embedded one verified with the APK keys); an unvalidated date never feeds the clock. A device clock set back cannot make the lists look younger than dates the app itself has seen; a validated `generatedAt` slightly in the future (up to 48 h) is clamped to age 0. A device clock far in the future makes lists look old, the cautious direction. A user who sets the clock back by days to hide staleness controls the device: accepted. Age is shown in whole days; above 14 days it becomes the CAUTION reason (D16), everywhere (T-LST-5 is aligned).

First launch and offline: the embedded bundle is the installed one until a download succeeds (ADR-0005). An old APK started offline shows stale lists honestly, because the age comes from `generatedAt`.

Download volume: with a 6 MB index that changes daily, about 6 to 8 MB per day on unmetered networks. Acceptable for the MVP. A delta update (sorted hash arrays make a merge diff against a known `bundleVersion` cheap, likely a few hundred KB) is a later optimisation, not part of `formatVersion` 1.

Order, in one line: signature -> manifest shape -> anti-rollback -> per-file size and SHA-256 -> per-file format -> variation and allow-list -> atomic swap. Never reorder: parsing before verifying is how formats get exploited.

## 7. Mapping to the existing port
`ReputationLookup.lookup(host: NormalizedHost): ReputationResult` stays synchronous and offline. The implementation (in `data/reputation`) holds the mapped indices and PSL of the current bundle behind an atomically swapped reference; `domain/` sees no file, no URL, no Android type. `ListsMissing` happens only if neither the installed nor the embedded bundle can be read. `ListsStale(ageDays)` comes from `generatedAt` as above. `Listed(source)` carries a name from the app's own table.

## 8. Allow-list behaviour
`allowlist.idx` holds popular registrable domains (a few tens of thousands; source open, section 13). In the app, the host's registrable domain is looked up in the allow-list first; if it is there, `d:` hits are ignored (defence in depth: the pipeline already removes these entries, and this closes the hash-collision route of 5.2 for popular sites). The file contains `d:` hashes only. An `h:` hit for a specific subdomain of an allow-listed domain (a hosted-content subdomain of a large provider, say) stays valid: that is real abuse of a shared platform and the entry names only that host. The pipeline drops `h:` entries equal to an allow-listed domain or to `www.` plus it. The bundle only answers `Listed`, `NotListed`, `ListsMissing` or `ListsStale`; the verdict engine (LF-17) decides the level.

## 9. Not in this format
Delta updates, IP-address entries, per-entry metadata (category, date), hash widths other than 8 bytes. Each would be a new `formatVersion` or a new file, not a patch of this one.

## 10. Production pipeline (summary; detail in LF-25)

Repository `lookfirst-lists` (separate, minimal), run daily and on demand:
Workflow: GitHub Actions in the separate `lookfirst-lists` repository, triggers `schedule` and `workflow_dispatch` only (no pull request, no push trigger), signing environment protected and restricted to `main`. Three jobs, three trust levels:

1. **Job A, build (no secrets, `contents: read`):** upstream at a commit SHA resolved at the start of the run and recorded in the manifest; HTTPS; SHA-256 and size caps. The IANA RDAP bootstrap and the PSL are fetched the same way (the PSL pinning conventions of LF-20 apply).
   - **Validate:** strict canonical entries only. An entry is kept only if it is already ASCII, lower case, labels in `[a-z0-9_-]`, `xn--` labels that decode and re-encode to themselves, no trailing dot, no IP literal, within DNS length limits. The pipeline does **not** try to normalise: a Python UTS #46 that disagrees with `HostNormalizer` would silently miss entries. Rejected entries are counted and reported; a reject rate above a set threshold fails the job. A small conformance corpus of (input, expected ASCII) pairs is shared between the pipeline's tests and the app's tests so both sides provably agree on canonical forms.
   - **Reduce:** deduplicate; classify with the pinned PSL (registrable domain -> `d:`, deeper host -> `h:`; drop `h:` covered by a `d:` of the same list); remove allow-listed domains; hash, sort, write. Byte-for-byte reproducible: same inputs, same bytes; only `generatedAt` and `bundleVersion` move.
   - **Duplicate check:** no two distinct names may share a truncated hash (fail and report both names).
   - **Variation check:** compare with the previously published manifest, fetched from Codeberg and verified with the public key. Fail above +-20 % index entries, on any allow-list collision, on a file size jump beyond a set bound, and per file: PSL (no ICANN-section rule lost, no allow-listed domain covered by a public suffix), brands (no fall of more than 20 %), RDAP (every URL `https`, no IP literal, port or userinfo). A human override is a separate, logged manual run.
2. **Job B, sign (the only job with the key, environment secret, no third-party action):** takes the artefact by digest and **replays the pure checks with checked-in code that is not derived from the data**: LFIX header, strictly increasing order, exact size, entry counts, allow-list intersection, +-20 % variation against the previous verified manifest, PSL and RDAP rules. Only then it writes the manifest (`generatedAt` from its own clock, `bundleVersion` derived from it), signs with S using `openssl`, and verifies the DER signature it produced. R is never on a runner.
3. **Job C, publish:** holds the Codeberg token, scoped to releases only, and nothing else. Draft release, upload, make public, then replace `latest` (section 6). Keeps the last 5 releases.

Actions pinned by SHA in A and C, minimal permissions, secret scanning (T-CI-1, T-CI-3, T-CI-4). The app's release build fetches the latest bundle, **verifies it with the pinned keys** and embeds it (ADR-0005).

## 11. Implementation tickets this design implies
- LF-25: pipeline, with the conformance corpus.
- App 1: bundle store and verifier (manifest parser, ECDSA verification with pinned keys, version floor, atomic install, load, embedded bundle) in `data/reputation`.
- App 2: index reader (mmap, binary search, probe set) implementing `ReputationLookup`.
- App 3: update job (WorkManager, `ListDownloader` in `data.network`, retry rules, settings entry: age, last failure reason, update now, disable).
- Hand tests for them: ECDSA verification on a real API 23 to 25 phone (unit tests run on the JDK provider, not the platform's); airplane-mode first launch; kill the app during an update; corrupt a file on a test server.

## 11b. Implementation notes (LF-23: App 1 and App 2)

Code: `app/src/main/java/com/lookfirst/data/reputation/`. Where it differs from or fills in the text above:
- The manifest is read by a small strict JSON reader (`StrictJson`: duplicate keys, floats, leading zeros, depth > 6, more than 2000 values refused), not `org.json`, and only after the signature. File `path` allows capitals (`NOTICE.txt`).
- `keyset.json`: `{"formatVersion":1,"epoch":N,"keys":[{"spki":"<base64 SPKI>"}]}` with `keyset.sig` (same line format, prefix `LookFirst keyset v1\n`), signed by R only, epoch strictly above the stored one, 1 to 8 distinct strict P-256 keys. The accepted ring for epoch N is the keyset keys plus R. An epoch-0 bundle already installed stays usable after a keyset (verified with the pinned keys); new bundles need the new epoch.
- Layout under `filesDir/reputation` (excluded from backup and device transfer by `data_extraction_rules.xml`, `allowBackup=false`): `bundles/<epoch>-<version>/`, `tmp/` (candidates), `current` (pointer: `epoch version manifest-sha256`), `state` (highest accepted pair, latest validated `generatedAt`, keyset, last failure and rejected manifest hash). The downloader (LF-24) gets a directory from `ReputationStore.newCandidateDirectory()`, puts `manifest.json`, `manifest.sig` and the files in it (copying unchanged files from the installed bundle), and calls `install`. Files not listed in the manifest are deleted before the swap.
- Durability: files are fsynced before the rename; after the directory rename `bundles/` is fsynced (`Os.open(dir, O_RDONLY)` + `Os.fsync`, API 21+), and the store root after the pointer rename. The rename order gives old-or-new.
- Variation of `allowlist.idx` uses the same tolerance as the index. The "allow-listed domain becomes a public suffix" check stays in the pipeline (hashes only in the app). RDAP and brands formats/thresholds are not checked yet (only size and SHA-256).
- The embedded bundle is read from `assets/reputation/<fixed names>` (none yet), verified with the epoch-0 pinned keys, installed when newer than the installed bundle or when none loads, and never lowers the stored floor.

### Production keys (for LF-25)
`PinnedKeys.kt` currently pins **test keys** whose private halves are in `app/src/test/.../TestKeys.kt`; `PinnedKeys.KEYS_ARE_PRODUCTION` is `false`, and three guards apply until it is `true`: (a) at run time `ReputationStore` refuses every bundle (installed, embedded, downloaded) in a non-debug build, so lookups say `ListsMissing`; (b) the version name ends in `-testkeys`; (c) the Gradle task `verifyPinnedKeysAreProduction` fails a release build meant for publication, i.e. one run with `-Plookfirst.publish=true` or with a `signingConfig` on the release build type (an unsigned `assembleRelease`, as in the full gate, stays possible). The task reads `PinnedKeys.kt` with comments removed. To go to production:
1. Generate S (CI secret) and R (offline) outside the repository: `openssl ecparam -name prime256v1 -genkey -noout`, then `openssl ec -in key.pem -pubout -outform DER | base64 -w0` gives the SPKI to paste. Private keys never enter any repository.
2. Replace `SIGNING_KEY_S` and `RECOVERY_KEY_R` in `PinnedKeys.kt`, set `KEYS_ARE_PRODUCTION = true` in the same commit.
3. Delete the test private keys from `TestKeys.kt` if production keys are used elsewhere in tests; keep the test signers for the unit tests, which build their own bundles and take the ring as a parameter (`PinnedKeysTest` fails if the flag is true while a test key is still pinned).
4. Sign with the DER signature line format of section 4 and check with `openssl dgst -sha256 -verify` that the message is the prefix followed by the exact manifest bytes.
5. Publish with `-Plookfirst.publish=true`.
6. Add the signed bundle under `app/src/main/assets/reputation/` (manifest.json, manifest.sig and the files); `AssetEmbeddedBundle` already reads it.

## 12. Threats and risks

| Threat | Counter-measure here |
|---|---|
| T-LST-1 MITM | HTTPS, and the signature: TLS is not what protects integrity |
| T-LST-2 hosting compromise | signature over the manifest, per-file SHA-256, pinned keys, R offline |
| T-LST-8 rollback / replay / version lock by a stolen S | floor `(keyEpoch, bundleVersion)`; version derived from `generatedAt`, date capped at clock + 48 h; new epoch by an R-signed keyset resets the floor; a freeze attack shows as age, CAUTION after 14 days |
| T-LST-3 oversized / malformed | caps before and during download, identity encoding, no archive or compression, fixed binary layout validated in one pass |
| T-LST-4 poisoning | allow-list dropped in the pipeline and consulted first in the app; +-20 % pipeline and age-proportional app variation thresholds; per-file thresholds for PSL, brands, RDAP; a hit only adds a warning |
| T-LST-5 stale lists | age from `generatedAt`, CAUTION above 14 days, embedded bundle |
| T-LST-6 IP seen by the host | generic request, no identifier; privacy notice |
| T-LST-7 licences | only confirmed sources; `NOTICE.txt` |
| T-CI-3 pipeline compromise | split jobs (untrusted data never next to the key), validation and variation checks, pinned actions |

Accepted risks:
- **A keyset does not survive "Clear data" or a reinstall** (it lives in app-private storage): the app falls back to the keys pinned in the APK, S included. Every rotation by keyset must therefore be followed by an app release that removes the revoked key from the pinned keys; until users update, a leaked S still signs bundles for them after a data wipe.
- A compromise of the lists workflow yields a validly signed bundle (T-CI-3). It is **not** limited to extra warnings: within the thresholds it can also (a) remove or add PSL rules (a phishing host made to look like a different registrable domain), (b) shrink the brand list by up to 20 % per release and weaken imitation checks, (c) point the RDAP bootstrap at an `https` server of its choice, which then receives the registrable domain when the user taps "Check domain age" (explicit action, ADR-0004, but a privacy leak of that one domain). Bounds: separate jobs (the data parser never sees the key), job B replaying the checks, thresholds per file, R offline able to open a new epoch. A bundle lowering the user's guard is possible; the user is still never shown "safe". S and R both leaked: app release needed.
- A stolen S can freeze the version floor for at most 48 h of clock before the next epoch repairs it, and can still publish bundles inside the thresholds until revoked.
- 64-bit truncation: about 1e-12 per scan of an accidental false "Listed"; a deliberate one against a small, non-allow-listed host costs hours of GPU time (5.2).
- The app's age-proportional variation limit no longer protects after about 45 days offline.
- Non-canonical entries (IDN-only entries of a source) are dropped until a converter proven against `HostNormalizer` exists.
- The device clock is not trusted against a user who controls the device.
- "Not listed" is never "safe": "no risk signal found" (product rule).

## 13. Open points
- **Phishing.Database upstream feeds** (reputation-sources.md, T-LST-7): which feeds it merges and under what terms. Needed before the first public bundle; blocks LF-25's publication step, not this format.
- Real entry count and size of the ACTIVE domains list (measure in LF-25).
- Decided in the review: publication as Codeberg release assets (last 5 kept); pipeline on GitHub Actions in `lookfirst-lists` with a protected environment; allow-list = Tranco top ~10k, list ID frozen, only domains present over several months (LF-25 fixes the ID and the rule); stale threshold 14 days everywhere; keyset signed by R only.
- Codeberg: terms for daily downloads by many phones, whether asset downloads redirect to another host, availability of the `latest` tag pattern (LF-25).
- Whether `keyset.json` ships in the first implementation or rotation goes through app releases only at first (section 4).
