# 0001 - Local-only analysis

Status: accepted (decision D9)

## Context
A QR scanner sees what the user does in physical life. Sending scanned URLs to a server would create a profile of the user, require us to operate and secure a backend, and make the tool a target. The attacker's site also learns of any request made to it.

## Decision
All analysis (parsing, homoglyph/IDN checks, typosquatting, reputation lookup, per-type rules) runs on the device. There is no LookFirst server. The two actions that need the network about the scanned content, redirect tracing and domain age (RDAP), are optional, started by a button, and preceded by a warning: the attacker's server sees the IP and learns the QR was scanned; RDAP receives only the domain. Page capture in a sandbox is dropped. No analytics, advertising or third-party crash reporting.

## Consequences
- Works offline; nothing to leak server-side; nothing to host or pay for.
- Detection is limited to what is knowable locally: new malicious domains are missed, so the verdict never says "safe" and shows the age of the lists.
- Heuristics and lists must be shipped and kept fresh by the app (ADR-0002).
- Optional actions must stay clearly separated in code (`data.network`) and UI.
