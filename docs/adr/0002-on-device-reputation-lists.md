# 0002 - On-device reputation lists

Status: accepted (decision D3)

## Context
Reputation lookup APIs (Safe Browsing lookups, VirusTotal, etc.) require sending the scanned URL or a derived value to a third party. Public lists (Phishing.Database, URLhaus, OpenPhish, PhishTank) can be downloaded in full. Their licences vary (see `docs/design/reputation-sources.md`). A downloaded list is an input an attacker may try to tamper with.

## Decision
No scanned URL is sent to any third party. Public lists are downloaded whole, converted to a compact local index and queried on the device. Updates are fetched over HTTPS from a bundle built by project CI, verified with an embedded Ed25519 public key, size-capped and format-validated, then swapped atomically; a failed update keeps the previous index. A seed index ships in the APK. Only sources whose licence allows this use are bundled; MVP source is Phishing.Database (MIT). The list date is shown in every verdict.

## Consequences
- Privacy promise holds: the update request carries no scan data.
- Storage and update cost (few MB, daily on unmetered networks).
- Coverage is limited to reported threats; staleness must be visible to the user.
- We own a signing key (kept out of the public repo) and a CI pipeline, and must monitor upstream licence changes.
- Poisoning defences (top-domain allow-list, size-change check) are required.
