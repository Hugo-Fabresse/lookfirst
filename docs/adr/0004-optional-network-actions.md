# 0004 - Optional, consented network actions

Status: accepted (follows decision D9)

## Context
Redirect tracing and domain age (RDAP) improve verdicts but contact outside parties: the first contacts the attacker's server, the second a registry.

## Decision
Both are separate buttons on the verdict screen, off by default, with a warning each time a scan uses them (first use: full explanation plus an "I understand"). They live in `data.network` behind their own use cases. Limits: 5 hops, 5 s, HEAD or `Range: bytes=0-0`, no cookies, generic User-Agent, `Proxy.NO_PROXY`, size-capped responses, only the registrable domain sent to RDAP (https URLs only). Redirects are followed manually (`followRedirects(false)`). A custom OkHttp `Dns` checks every resolved address and returns only the checked ones, so the checked IP is the connected IP; blocked ranges are IPv4 private, 0.0.0.0/8, 100.64/10, loopback, link-local, multicast, IPv6 ULA/link-local/loopback and IPv4-mapped. The Network Security Config forbids cleartext globally, with no exception: hops to `http://` are not followed and are shown as a risk signal. Results add signals; they never lower a verdict (a benign answer proves nothing, since servers can fingerprint scanners).

## Consequences
- The attacker may learn the QR was scanned when the user opts in; the warning states it.
- Extra code surface (SSRF-style protections) to test.
- Users who never press the buttons never make a request about their scans.

## Update (LF-26)
The redirect tracer does not use OkHttp: it resolves and checks the addresses itself and speaks a hand-written HTTP/1.1 `HEAD` over a TLS socket to a checked IP (no new dependency, the connected IP is the checked IP by construction). The limits and blocked ranges above are unchanged. RDAP (LF-27) uses the same building blocks (`BoundedWork`, `CheckedConnector`, `TlsHopTransport`) plus a capped body read. Its server is the one the signed bundle's bootstrap names (https, host name, no port, no userinfo, validated at install), its redirects stay on the same host, and a 404 or an old date never changes a verdict. Details: DECISIONS.md `[rdap]`, threat model T-NET-3.
