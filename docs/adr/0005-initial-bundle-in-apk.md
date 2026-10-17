# 0005 - Initial signed bundle embedded in the APK

Status: REPLACED by ADR-0008 (LF-28, decision D20): no bundle is published or embedded any more. The 14-day staleness rule of D16 stands. Text below kept for history.

## Context
Reputation lists are downloaded in the background (ADR-0002). On first launch, offline or before the first download, the app would have no list and every verdict would carry LISTS_MISSING, which weakens the product exactly when a user is most likely to try it.

## Decision
The APK embeds an initial reputation bundle, signed with the same Ed25519 key and in the same format as downloaded ones (index, Public Suffix List, IANA RDAP bootstrap, top-domains allow-list, signed manifest). It is verified like any other bundle before use. The release CI regenerates the embedded bundle from the latest published one at each release build.

## Consequences
- The embedded version is the floor: the app never accepts a bundle whose version is lower than or equal to the installed one, and the installed one starts as the embedded one. Anti-rollback is unchanged.
- The age shown and used for the CAUTION threshold (14 days) is computed from the manifest `generatedAt`, not from the install date. An old APK installed and launched offline therefore shows LISTS_STALE on first launch. This is intended and honest: the lists really are that old.
- The release workflow gains a step (fetch, verify the signature, copy into the app assets); a release cannot ship an unsigned or unverified bundle.
- APK size grows by the bundle size (kept compact by the reduction step).
