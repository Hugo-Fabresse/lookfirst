# 0007 - ECDSA P-256 for the reputation bundle signature

Status: accepted (LF-22). Replaces the "Ed25519" part of D13, ADR-0002 and ADR-0005 (their other content stands).

## Context
D13 chose Ed25519 for the signed bundle (manifest). The app verifies, it never signs. The platform provides Ed25519 natively only from API 33 (`Signature.getInstance("Ed25519")` and `KeyFactory`), and minSdk is 23 (D8): on most supported phones there is no native Ed25519. We do not write our own cryptography (a verifier written by us, even a short one, is the kind of code that fails silently).

## Options compared
| Option | Verifier cost in the APK | Maintenance | Licence | Verdict |
|---|---|---|---|---|
| (a) ECDSA P-256, `SHA256withECDSA` from `java.security` | 0 bytes, 0 dependency, no `build.gradle` / `verification-metadata.xml` change | the platform's own provider (Conscrypt / BoringSSL), patched with the OS | platform | **chosen** |
| (b) Ed25519 library (BouncyCastle `bcprov`, `net.i2p.crypto:eddsa`, libsodium bindings) | BouncyCastle: several MB before R8, and a history of clashes with the platform's repackaged copy; `eddsa`: small but, as far as I recall, last released in 2018 and unmaintained; libsodium: native `.so` per ABI (JNI) | one more artefact to pin and watch for each of T-CI-2's checks | MIT / CC0 / ISC | rejected: a dependency (or native code) added only to get a nicer algorithm |
| (c) Tink (`tink-android`, Ed25519 verify) | order of 1 MB before R8 plus protobuf-lite (to measure, not measured here) | maintained by Google, frequent releases | Apache-2.0 | acceptable fallback, not needed |

(b) and (c) figures are from memory and were not re-checked: they do not change the outcome, because (a) has no cost at all.

## Decision
- The manifest is signed with **ECDSA over P-256 with SHA-256**, verified on the phone with `Signature.getInstance("SHA256withECDSA")` and a public key loaded from an X.509 SubjectPublicKeyInfo (`KeyFactory.getInstance("EC")`). Available on every API level we support.
- The signature is detached (`manifest.sig`) and covers a fixed domain-separation prefix followed by the exact bytes of `manifest.json`. Format and key handling: `docs/design/reputation-bundle.md`.
- Signing happens only in the lists pipeline (LF-25), on a runner, with a key held as an environment secret. Nonce quality matters for ECDSA: the signing step uses a deterministic-nonce implementation (RFC 6979) when the tool offers it, otherwise the OS CSPRNG; it never uses a custom RNG.
- Public keys are pinned in the APK. A second, offline recovery key is pinned too (rotation, see the design document).
- D13's other parts stand: signed manifest, anti-rollback, atomic swap.

## Consequences
- Zero dependency and zero APK growth for the verifier.
- ECDSA is less pleasant than Ed25519 on the signing side (nonce handling, DER encoding, malleable signatures). None of that touches the phone: the app only verifies, strictly (DER, signature length cap), and does not use the signature as an identifier.
- `Signature` / `KeyFactory` behaviour on API 23 is the platform's. Robolectric and JVM unit tests run on the JDK provider, so the implementation ticket needs one check on a real API 23-25 device (listed in its hand-test list).
- If a future minSdk reaches 33, switching is a `formatVersion` bump and a new ADR; nothing in the design depends on ECDSA specifics.
- ADR-0002 and ADR-0005, D13, `architecture.md`, `threat-model.md` and `reputation-sources.md` say "Ed25519": read "ECDSA P-256" (the design documents are updated; the two accepted ADRs keep their text and point here).
