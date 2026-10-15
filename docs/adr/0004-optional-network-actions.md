# 0004 - Optional, consented network actions

Status: accepted (follows decision D9)

## Context
Redirect tracing and domain age (RDAP) improve verdicts but contact outside parties: the first contacts the attacker's server, the second a registry.

## Decision
Both are separate buttons on the verdict screen, off by default, with a warning each time a scan uses them (first use: full explanation plus an "I understand"). They live in `data.network` behind their own use cases. Limits: 5 hops, 5 s, HEAD or `Range: bytes=0-0`, no cookies, generic User-Agent, http/https only, refuse private/loopback/link-local targets, size-capped responses, only the registrable domain sent to RDAP. The cleartext allowance needed for tracing applies to this client only. Results add signals; they never lower a verdict (a benign answer proves nothing, since servers can fingerprint scanners).

## Consequences
- The attacker may learn the QR was scanned when the user opts in; the warning states it.
- Extra code surface (SSRF-style protections) to test.
- Users who never press the buttons never make a request about their scans.
