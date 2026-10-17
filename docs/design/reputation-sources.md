# Reputation sources

Status: revised by LF-28 (decision D20, ADR-0008). Checked on 2026-10-17 by reading the sources' own pages and by downloading the files (headers and sizes below were measured that day). This is a technical review, not legal advice. Where a page does not say something, this is written as "not found".

Constraint (ADR-0002, D3, D9): lists are downloaded in full to the phone and queried locally. No scanned URL is ever sent to anyone. That excludes lookup APIs. Since D20 there is no bundle published by LookFirst: **the phone downloads each list from its publisher** (ADR-0008) and builds its own index (`reputation-lists.md`).

## Criteria (all mandatory)

1. Downloadable without account, API key or identifier.
2. The publisher's terms allow automated use by a free app (text and link quoted below).
3. `https`.
4. Plain text: domains, hosts or URLs, one per line.
5. Reasonable on a phone: less than 10 MiB per update in total.
6. Updated at least daily.

Preference: hosted outside the United States.

## Comparison

| Source | Hosting | Key | Terms found | Format and size (measured) | Update | Verdict |
|---|---|---|---|---|---|---|
| **CERT Polska Warning List** (`https://hole.cert.pl/domains/v2/domains.txt`) | CERT Polska (NASK), Poland; nginx | **No** | "The Warning List may be used by any user for any purpose, including commercial purposes." "Attribution of the data source is not required." (https://cert.pl/en/warning-list/). Automation: the page tells operators to integrate the list and update it "every 5 minutes"; it has no sentence about apps specifically | `text/plain`, active domains only, one per line, entries of the last 6 months. 2,560,521 bytes and 125,418 lines. `ETag` and `Last-Modified` are sent | continuous (the publisher recommends 5-minute refresh) | **Chosen** |
| **abuse.ch URLhaus** | Switzerland | **Yes**: "you must include your Auth-Key in the URL"; host file and text list marked "Login required" | "available free of charge under the fair use principles"; commercial use "may require" the paid API. No CC0 statement found | CSV, text, host file after login | every 5 minutes | Rejected (criterion 1). A key cannot be embedded in a public app |
| **Phishing Army** (`phishing.army`) | Behind Cloudflare (United States CDN); origin country not stated | No | Feeds: "does not impose an additional restriction on access to or use of its generated feeds"; but upstream providers "may apply different conditions to commercial use, redistribution, automation or inclusion in a paid product or service", the user is "solely responsible for reviewing the terms of every upstream source", and it "does not grant, transfer or sublicense any rights belonging to the independent upstream data providers" (https://phishing.army/license/) | text, `phishing_army_blocklist_extended.txt`: 3,429,715 bytes, 157,065 entries. Header lists the upstreams: PhishTank, OpenPhish, CERT.PL, Phishunt, urlscan.io, PhishStats, StalkPhish, URLAbuse, AntiPhish.org | hourly | Rejected (criterion 2): it aggregates OpenPhish (personal use only) and PhishTank, and says it cannot grant their rights. Its CERT.PL part is available directly |
| **Phishing.Database** (GitHub, `phishing-domains-ACTIVE.txt`) | GitHub (United States) | No | MIT licence for the repository. Upstream feeds not documented; no sentence about automated use | 11,023,128 bytes, 392,178 domains (ACTIVE domains only) | "regularly" | Not retained (criteria 2 and 5 for now): alone it exceeds the size budget, provenance is undocumented, GitHub is a US host. Kept as the candidate second source, see below |
| **OpenPhish community** | United States | No | "solely for your personal use"; commercial use, product development and redistribution need written consent | text, 12 h | | Rejected (terms) |
| **PhishTank** | United States | Application key for regular downloads | Licence not found | XML, CSV, JSON, hourly | | Rejected (key) |
| **Google Safe Browsing** | United States | Yes | no commercial use without agreement; 30-minute freshness rule; full-hash confirmation sends prefixes to Google | hash prefixes | | Rejected (D3) |
| malware-filter (GitLab pages) | United States | No | page unreachable on the day of the check (HTTP 404) | | | Not evaluated |

## Decision (cautious side)

One source at the start: **CERT Polska Warning List**. It is the only candidate that meets all six criteria and is hosted in the EU. One source is thin coverage, and it is better to say so than to add a source whose terms we cannot defend. The code takes a list of sources (`ListSources.ALL`, one fixed URL each, independent of each other), so a second one is a code addition, not a redesign.

Points to know:
- **Automation is implicit, not explicit.** CERT Polska's text allows use "for any purpose" and instructs operators to feed the list into their systems automatically (5-minute refresh). It does not name mobile apps. The app fetches once a day at most (`If-Modified-Since`), far below that. If Hugo wants a written confirmation, it must come from CERT Polska (contact on cert.pl). Flagged as the one open point of LF-28.
- **Coverage is mostly Polish.** The list is run by a national CSIRT and is built from what is reported to it: it is strongest on phishing and fraud aimed at Poland and on large campaigns; sites aimed at other countries, notably France, can be missing, and a fresh site is often missing. The About screen says so ("a new or unreported site can be malicious").
- **Candidate second source, if coverage proves too thin: Phishing.Database ACTIVE domains** (MIT, 11 MB). With CERT Polska (2.5 MB) a full update would be about 13.6 MB, above the 10 MiB target: Hugo would have to relax the budget or accept a reduced file. Its provenance is undocumented and GitHub is a US host. Not added by LF-28.

## Data that is not a reputation list

| Data | Where it comes from | Note |
|---|---|---|
| Public Suffix List | embedded in the APK (ADR-0006, refreshed by a script and a PR, provenance checked by LF-20) | Used to find the registrable domain when the index is built and when a host is looked up |
| IANA RDAP bootstrap (`data.iana.org/rdap/dns.json`, RFC 9224) | embedded in the APK (LF-27) | Optional domain-age action only |
| Official domains ("allow-list") | embedded: the official domains of the brand list (`BrandList`) | Consulted first; a list can never mark one of them |

## Findings and risks

- Without our own signature the only protection of a downloaded list is `https`, the publisher's reputation and the checks on the phone (size, entry bounds, variation, format, official-domain filter). A compromised publisher can add false warnings; it cannot remove a built-in rule or lower a verdict (`threat-model.md`, T-LST-2 and T-LST-4).
- The publisher sees the phone's IP address at each update. The About screen says so.
- A list that is wrong about a domain gives a false "reported by public phishing lists". The verdict wording says "reported", with the list name.

## Updating the RDAP bootstrap (LF-27)

`app/src/main/resources/lookfirst/host/rdap-dns.json` is the IANA file copied as is and shipped in the APK, pinned by
SHA-256 in `scripts/gen-host-data.py` (`RDAP_SHA256`) and listed in `SHA256SUMS`. `data-provenance` checks the committed copy
against the pin offline (a red build means someone edited it) and, in a separate step that never fails the build, downloads
IANA's file again: a `::warning::` annotation means IANA published a new one. Then, by hand:

1. Download `https://data.iana.org/rdap/dns.json` and compare it with the committed file (`diff` of the `services`: added,
   removed and changed TLDs and servers; the `publication` date).
2. Check that no `https` URL points to an unexpected host (a registry operator you do not recognise, a host that is not
   under the TLD operator's or its known back-end provider's domain). Servers that are `http://` are ignored by the app
   and need no action; a TLD whose only server is `http://` simply has no domain-age button.
3. Replace the file, set `RDAP_SHA256` in the script, run `python3 scripts/gen-host-data.py <SOURCE_DIR> app/src/main/resources/lookfirst/host`
   (SOURCE_DIR from `--download`) to refresh `SHA256SUMS`, then `--verify`. Update the publication date quoted in DECISIONS.md.
4. Ship it with the next app version (there is no other way to update it: no download, no signed bundle). The test
   `EmbeddedRdapPlannerTest` reads the real file at build time.

## Sources consulted

- CERT Polska Warning List: https://cert.pl/en/warning-list/ and https://hole.cert.pl/domains/v2/domains.txt
- URLhaus API page: https://urlhaus.abuse.ch/api/
- Phishing Army: https://phishing.army/ and https://phishing.army/license/
- Phishing.Database: https://github.com/Phishing-Database/Phishing.Database
- OpenPhish terms: https://openphish.com/terms.html
- PhishTank developer info: https://phishtank.org/developer_info.php
- Safe Browsing terms: https://developers.google.com/safe-browsing/terms
- RDAP bootstrap: https://www.rfc-editor.org/rfc/rfc9224.html
