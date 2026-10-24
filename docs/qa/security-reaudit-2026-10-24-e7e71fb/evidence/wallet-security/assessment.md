# Wallet Secret Cancellation Re-audit

Reviewed commit: `e7e71fbc4b5614b68e993e1eec5dc986b4536a86`.
Previous vulnerable baseline: `bb1e6567fb2a8cc30dd6e8e9447e69b0f330fa1b`.
Tracked working tree was clean. No production files were modified by this reviewer.

## Conclusion

**BTS-03: Closed for all three reported application-owned secret-buffer paths.** Static review and deterministic tests against the current compiled production class confirm that cancellation at the dispatcher return boundary wipes the produced seed or canonical mnemonic buffer. No new race, authentication bypass, or signing regression was identified in these changes.

| Path | Current production location | Deterministic result |
| --- | --- | --- |
| Signing seed | `core/data/src/main/kotlin/com/bittool/signer/core/data/DefaultSignerStore.kt:947-956` | Seed produced, caller cancelled, all 128 seed characters zeroed; no signPlanTask call; unlocked mnemonic closed; busy false; no completed tasks. |
| Public-index seed | `core/data/src/main/kotlin/com/bittool/signer/core/data/DefaultSignerStore.kt:1086-1093` | Seed produced, caller cancelled, all 128 seed characters zeroed; no derivePublicIndex call. |
| Canonical mnemonic SecretText | `core/data/src/main/kotlin/com/bittool/signer/core/data/DefaultSignerStore.kt:1068-1074` | Actual coroutine holder captured after production and before return to main; cancellation closes SecretText and zeroes the original char array; no derivePublicIndex call. |

The fix establishes ownership outside `withContext` before the dispatched block returns. The seed holders are covered by an outer finally, while canonical's holder is wiped on exceptional return and transferred to the caller on success. Existing callers of canonical immediately enter useAndWipe. Dispatcher completion orders holder publication before cleanup, so the nullable holders do not introduce concurrent use/wipe. Existing security/request epoch checks still run before any engine call.

## Evidence

- `SecretCancellationReaudit.kt`: three-path harness using the actual production `DefaultSignerStore` and production `Pbkdf2Bip39Seed`, with separate default/main dispatchers. Only public BIP39 test-vector material is used; secret contents are never printed.
- `run-poc.py`: standalone compiler/runtime runner using the repository's already-built classes and cached dependencies; no Gradle invocation.
- `cancellation-result.txt`: **3/3 PASS**.
- `evidence-metadata.json`: exact HEAD, clean tracked diff, production source/jar hashes and harness hashes.

For canonical, the harness inspects the actual coroutine lambda's holder in the test process, captures both SecretText and its original backing char array after production, then cancels the caller while main-thread resumption is queued. This checks actual erasure, not only absence of an engine call.

## Regression-test discrimination

The newly added repository test `aSeedDerivedForACancelledCallerIsStillWiped` at `core/data/src/test/kotlin/com/bittool/signer/core/data/DefaultSignerStoreTest.kt:510-526` **correctly distinguishes the old and fixed implementation**:

- Temporarily compiling the unmodified `bb1e656` DefaultSignerStore and running the current repository regression test against it fails at line 525 with `AssertionError: the seed is wiped`.
- Running the same test against the current `e7e71fb` compiled production class passes.

Artifacts: `BaselineDefaultSignerStore.kt`, `RegressionDiscrimination.kt`, `check-regression-discrimination.py`, and `regression-discrimination-result.txt`. The baseline is loaded only in a separate temporary test JVM and never replaces repository files.

An initial static suspicion that the test might pass on the old implementation was disproved by this experiment. It is **not a finding**. The additional separate-dispatcher signing and canonical tests strengthen closure evidence beyond the repository's new single-path test.

## Retained limitations

- This closes the specific fixable mutable-buffer cancellation issue. It does not claim complete memory zeroization across JVM/Compose immutable strings, crypto-provider key copies, or the JS engine.
- No real-device heap extraction, Android Keystore/StrongBox execution, or ARMv8 native crypto validation was performed in this targeted re-audit. These components were not changed by the reviewed patch.
- The original issue required process-memory access or a later memory disclosure for attacker impact; no direct remote wallet theft was demonstrated before or after the fix.
- Scope here is the wallet secret-lifecycle delta in DefaultSignerStore and the accompanying regression test; other agents cover the remaining changed application code.
