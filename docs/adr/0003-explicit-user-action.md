# 0003 - Explicit user action, nothing automatic

Status: accepted

## Context
Quishing works because scanners open the link at once. Android also makes automatic actions dangerous: `intent:` URIs, deep links, `tel:` with USSD, Wi-Fi configuration, otpauth enrolment, premium SMS.

## Decision
A scan never triggers an action. The result is decoded, analysed and shown with a verdict; the user chooses Open, Copy or Cancel. Only `data.launcher.ActionExecutor` may start an Intent, and it is invoked only from a button handler. There is no "auto-open" setting. Dangerous schemes (`intent:`, `javascript:`, `file:`, `content:`, `data:`) are never openable; unknown app schemes are Copy-only by default. For `SUSPICIOUS` and `DANGEROUS` verdicts Cancel is the default action and Open needs a second confirmation. Intents are built from parsed fields, never with `Intent.parseUri`. Dial uses `ACTION_DIAL`, not `ACTION_CALL`. No WebView in the app.

## Consequences
- One extra tap for every scan; this is the product's point.
- Architecture test enforces that `startActivity` is only called from `data.launcher`.
- Some conveniences are lost (no auto-join Wi-Fi, no one-tap login pairing).
- Verdict screen must display the decoded content verbatim and escaped, since it is the user's only review step.
