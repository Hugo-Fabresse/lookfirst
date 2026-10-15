# 0002 - On-device reputation lists

Status: accepted (decision D3)

## Context
Reputation lookup APIs (Safe Browsing lookups, VirusTotal, etc.) require sending the scanned URL or a derived value to a third party. Public lists (Phishing.Database, URLhaus, OpenPhish, PhishTank) can be downloaded in full. Their licences vary (see `docs/design/reputation-sources.md`). A downloaded list is an input an attacker may try to tamper with.

## Decision
No scanned URL is sent to any third party. Public lists are downloaded whole, converted to a compact local index and queried on the device. The bundle is built by a separate, minimal repository and workflow (not the app's build CI): pinned upstream fetch, validation, reduction, Ed25519 signature, publication on Codeberg (decisions D10 and D13). The signing key is an environment secret of that workflow only. A signed manifest carries a monotonic version number and a generation date; the app verifies it with an embedded public key (two keys for rotation), refuses a version lower than or equal to the installed one (anti-rollback), enforces size caps and format validation, swaps atomically, keeps the previous index on failure, and shows the lists' age. The bundle also carries the Public Suffix List, the IANA RDAP bootstrap and the top-domains allow-list. Updates are fetched over HTTPS. A seed index ships in the APK. Only sources whose licence allows this use are bundled; MVP source is Phishing.Database (MIT for the repository; its upstream feeds are undocumented and must be confirmed or filtered before publication). The list date is shown in every verdict.

## Consequences
- Privacy promise holds: the update request carries no scan data.
- Storage and update cost (few MB, daily on unmetered networks).
- Coverage is limited to reported threats; staleness must be visible to the user.
- We own a signing key (kept out of the public repo and out of the app's CI) and a lists pipeline. Compromise of that workflow is an accepted risk, mitigated by SHA-pinned actions, minimal permissions and a maximum variation between two bundles.
- Codeberg sees the phone's IP at update time; to state in the privacy notice.
- Upstream licence terms must be monitored.
- Poisoning defences (top-domain allow-list, size-change check) are required.
