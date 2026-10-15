# Reputation sources

Status: proposed (LF-1). Checked on 2026-10-15 by reading the sources' own pages. Where a page did not state a licence or a term, this is written as "not found". This is a technical review, not legal advice: confirm with the source owner before shipping anything marked "unclear".

Constraint (ADR-0002): lists are downloaded in full to the phone and queried locally. No scanned URL is ever sent to a third party. That excludes lookup APIs.

## Comparison

| Source | Licence / terms found | Use inside an app? Redistribution? | Format | Size | Update frequency | API key |
|---|---|---|---|---|---|---|
| **Phishing.Database** (github.com/Phishing-Database/Phishing.Database) | MIT licence (README/LICENSE page) | Yes. MIT allows use and redistribution if the copyright notice is kept. Upstream sources it aggregates have their own terms (not stated by the project) | Plain text: domains, links, IPs; ACTIVE / INACTIVE / NEW variants | `ALL-phishing-domains.tar.gz` 4.4 MB; `ALL-phishing-links.tar.gz` 14 MB (README). Other files: not listed | "Updated regularly", retested regularly; fixed schedule not found | No |
| **URLhaus (abuse.ch)** | Free "under fair use principles"; page says for-profit use may require the paid commercial API. A CC0 statement was **not found** on the pages I could read; Terms of Use and Fair Use pages were not readable | Unclear. Redistribution not addressed on the pages read | CSV/JSON dumps, text URL list, host file, RPZ, etc. | Not found | Dumps refresh every 5 min, asked not to fetch more often than every 5 min | **Yes**, free Auth-Key from the abuse.ch portal, sent in the request URL. A key cannot be embedded in a public app or repo |
| **OpenPhish Community feed** (openphish.com) | Terms of Use: use "solely for your personal use"; commercial use (including threat intelligence, detection, product development) needs prior written consent; no redistribution, no derivative works without written permission | **No** for a distributed app without a written agreement | Text file (CSV/JSON only on paid tiers) | Not found ("limited" coverage) | Every 12 h (community), 5 min (paid) | No for community feed |
| **PhishTank** (phishtank.org) | Licence not stated on the developer page (links to an EULA I could not read); the terms page redirected to the home page | Unclear. Redistribution not addressed | XML, CSV, PHP, JSON, each plain or gz/bz2 | Not found | Hourly | Application key needed for regular downloads (without it only a few per day) |
| **Google Safe Browsing** (Update API) | Terms: "Unless you have a separate agreement with Google, you may not use the Safe Browsing API for commercial purposes"; a warning may only be shown on data refreshed in the last 30 minutes | Conflicts with offline operation and the 30-minute freshness rule. The Update API is hash-prefix based, but full-hash confirmation sends prefixes to Google (not verified on the page fetched) | Hash prefixes (protobuf) | Not found | Continuous | Yes (API key) |

## Complementary non-list data (no reputation, but useful)

| Data | Source | Terms | Note |
|---|---|---|---|
| RDAP bootstrap (which server answers for a TLD) | IANA `https://data.iana.org/rdap/dns.json` (RFC 9224) | IANA registry; licence not checked, public registry data | Needed for the optional domain-age action. Small JSON |
| Brand/typosquat list, shortener list | Own curated asset | Own | No external dependency; maintained in the repo |

## Findings and risks

- Only Phishing.Database has an explicit, permissive licence. Its limits: it is itself an aggregate, so its provenance is out of our hands, and its false positives are handled by GitHub issues rather than an SLA.
- URLhaus is high quality for malware distribution URLs, but the mandatory Auth-Key and the unclear redistribution make it a poor fit for a public repo and a serverless app. If the maintainers confirm in writing that a CI job may fetch it with a project key and republish a derived list, it becomes usable. Not found: explicit statement either way.
- OpenPhish community is excluded by its terms for this use.
- PhishTank: unclear terms, key required, and registration for new users has historically been unreliable. Not recommended without clarification.
- Safe Browsing is excluded by D3 and by its non-commercial clause. (LookFirst is free and open, which may make it "non-commercial", but the 30-minute freshness rule and Google-side hash-prefix lookups still conflict with the local-only promise.)
- Size on a phone: 14 MB of full phishing links is heavy to keep and to scan. Host-level matching (domains list, 4.4 MB gzipped) is enough for MVP; the build step should convert it to a compact sorted hash set or Bloom filter (a few MB, `O(log n)` lookup).

## Recommendation

1. **MVP: Phishing.Database domain list only** (MIT, no key, no registration). Use `ALL-phishing-domains` (or ACTIVE domains only, which is smaller and fresher).
2. **Do not have each phone download from GitHub raw directly.** Build a CI job in the LookFirst repository (a build job, not a server) that downloads the list, validates it, reduces it to a compact index, signs it (Ed25519) and publishes it as a GitHub Release asset. Phones fetch that asset. This gives integrity (ADR-0002, T-LST-2/3/4), a stable format and fewer upstream hits. Keep the MIT notice in the bundle and in the app's licences screen.
3. **Ship a seed index in the APK** so a verdict works offline on first launch.
4. **Evaluate URLhaus second:** ask abuse.ch whether a CI-republished, derived domain list is acceptable for a free, open-source app. Do not add it before a written answer.
5. Excluded: OpenPhish (terms), Safe Browsing (D3), PhishTank (unclear, key).
6. Always present list hits as "reported by public phishing lists" with the list name, and keep the allow-list of top domains to dampen poisoning or false positives.

## Sources consulted

- Phishing.Database: https://github.com/Phishing-Database/Phishing.Database
- URLhaus API page: https://urlhaus.abuse.ch/api/
- OpenPhish terms: https://openphish.com/terms.html and feeds page https://openphish.com/phishing_feeds.html
- PhishTank developer info: https://phishtank.org/developer_info.php
- Safe Browsing terms: https://developers.google.com/safe-browsing/terms and Update API: https://developers.google.com/safe-browsing/v4/local-databases
- RDAP bootstrap: https://www.rfc-editor.org/rfc/rfc9224.html and https://www.iana.org/assignments/rdap-dns/rdap-dns.xhtml
