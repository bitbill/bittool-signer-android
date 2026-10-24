# BitTool Signer for Android
# Code Security Audit Report

**Assessment date:** 24 October 2026 (Australia/Sydney)  
**Baseline:** `bb1e6567fb2a8cc30dd6e8e9447e69b0f330fa1b` (`main`)  
**Version:** 1.0.0, default version code 1000001  
**Assessment:** AI-assisted source review, targeted reproductions, automated tests and dependency triage  
**Status:** Review complete; findings are open. No production fixes were applied.

## 1. Executive assessment

The wallet has substantial security controls: password and device encryption layers, authenticated wallet metadata, per-operation secret access, immutable signing plans, strict transaction parsing and application-level privacy protections. This assessment did not confirm a critical/high-severity vulnerability, password bypass, unauthorized signing path or direct private-key extraction path in the reviewed code.

Six actionable findings remain: two Medium security findings, two Low security findings, one functional reliability finding and one test-validation finding. The principal wallet risks are transaction consent on older Android versions and incomplete prioritization of destructive Solana instructions. A separate cancellation bug defeats explicit cleanup of an application-owned seed buffer. Bitcoin burn accounting is correct on the main review card but incorrect in its textual receipt/history summary.

**Recommendation:** resolve the Medium findings and seed cleanup gap, correct the burn summaries, and complete the device qualification matrix before treating this build as release-qualified for valuable wallets. This is an engineering assessment of a specific code snapshot, not independent third-party certification or proof that the wallet is vulnerability-free.

| ID | Priority | Security severity | Confirmed issue |
| --- | --- | --- | --- |
| BTS-01 | P2 | Medium | Partial overlays remain actionable on Android 10-11 consent screens |
| BTS-02 | P2 | Medium | Metaplex burns are omitted from mixed-transaction headlines and summaries |
| BTS-03 | P2 | Low | Cancellation can discard a derived seed before its cleanup guard starts |
| BTS-04 | P2 | Low | Bitcoin burn plus change produces a false all-funds-return summary |
| BTS-05 | P2 | Not security-rated | An interrupted static QR can stall without an error or retry |
| BTS-06 | P3 | Not security-rated | Registry integrity test still addresses missing iOS resource paths |

Priority describes implementation urgency; security severity describes the demonstrated consequence and prerequisites. No CVSS score is assigned where platform reproduction or a complete exploit chain is absent. All findings are open at the baseline above.

## 2. Scope, baseline and method

The initial working tree was clean. The checkout had no configured Git remote, so "latest" means the current local HEAD; no claim is made about an upstream branch. The HEAD commit timestamp is 24 October 2026, 10:15:30 +11:00. Review-generated documents and evidence are the only intended tracked-tree additions.

The first-party production inventory comprises 351 Kotlin/C/header/engine JavaScript files and approximately 58,166 lines, excluding vendored QuickJS, tests and assets. These figures describe the inventory, not a claim of uniform line-by-line coverage. Review effort followed security-sensitive data flows and their callers, with parallel examination of wallet storage, chain signing and Android authorization. Findings were checked against current implementations rather than accepted from historical audit notes.

| Area | Work performed |
| --- | --- |
| Wallet and cryptography | Entropy, BIP39, native PBKDF2, vault format and AAD, passwords, public metadata, retry counters, Keystore loss and recovery |
| Transaction engine | BTC/PSBT, EVM/EIP-712 and Safe, Solana, TRON, NFT operations, key paths, signer matching, transport binding and response encoding |
| Android authorization | App Lock, wallet operations, signing review and consent, setup/import, recovery display, lifecycle cancellation and session epochs |
| Input and output | QR scanner, JSON/CBOR/UR bounds, file import/export, clipboard, sensitive fields, signatures and history |
| Native host and platform | QuickJS/JNI boundary, engine pinning, entropy bridge, no-eval contexts, manifests, backup exclusions and release guard tasks |
| Verification and supply chain | Unit tests, targeted reproductions, native primitive cross-check, reproducible engine bundle, npm advisories and production reachability |

Reproductions used public test mnemonics, synthetic unsigned transactions and temporary test files. They did not access real wallet secrets, broadcast transactions or alter user wallet state. The engine findings were reproduced against current source and independently against the shipped Android JavaScript asset. Synthetic account/UTXO fixtures establish parser, UI-model and signature behavior; they do not establish successful execution on a live chain.

The report separates (a) reproduced defects, (b) source/test-supported platform exposures, and (c) residual limitations. Device-specific behavior was not inferred from JVM tests.

## 3. Wallet security controls observed

**Secrets at rest.** The vault uses PBKDF2-HMAC-SHA256 with 600,000 iterations for its password layer, AES-256-GCM authenticated encryption, and a separate per-wallet Android Keystore AES-GCM device layer. Domain-separated associated data binds header metadata. Salts and nonces use platform randomness. File/header/ciphertext/KDF bounds are enforced before expensive work. Password rotation reauthenticates and refreshes encryption material.

**Wallet identity and authorization.** Public wallet sidecars are authenticated. A short master fingerprint is a candidate filter rather than full authorization. Signing rederives the wallet identity and public index from the unlocked secret and reconstructs the approved task. Reset and repair paths verify identity; lost-key deletion rechecks proof under the vault lock. Secret-bearing operations require wallet passwords; App Lock biometrics are not treated as transaction authorization.

**Abuse and error handling.** Wrong-password attempts are recorded before decryption and protected with authenticated storage. Corrupt counters fail closed. The latest Keystore handling distinguishes transient unavailability from proved key loss, reducing accidental repair/data-loss decisions after non-strong device unlock. The applicable Android platform caveat is documented by [Android's Keystore API](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder); real OEM verification remains necessary.

**Signing integrity.** The resolver binds the canonical request, original payload/digest and exact task targets. Signing checks that plan against the effective seed. Raw transport is revalidated. Accumulated signatures are checked and retained only for the original transaction. Partial/opaque requests require explicit acknowledgement; that acknowledgement cannot override invalid structure or missing signer evidence.

**Chain-specific checks.** BTC restricts sighash modes to ALL/default and validates scripts, derivations and prevouts; verified change comes from the wallet's own keys. EVM enforces supported types, chain context and strict typed-data schemas. Solana enforces required signer slots, checks prior signatures, rejects unresolved address lookup tables, and separates message signing from transaction parsing. TRON checks protobuf bytes against accompanying representations and keeps operated accounts distinct from signer evidence.

**Platform and host protections.** The production manifest excludes network permissions and backups/device-transfer domains. Only the launcher activity is intended to be externally reachable. Release build guard tasks check permission/component allowlists. Secure windows, overlay hiding where supported, background concealment, clipboard restrictions and autofill/content-capture exclusion reduce disclosure. QuickJS receives JSON as an argument, has no network/file/module bridge or runtime eval intrinsic, and uses fresh contexts for secret/untrusted operations. The shipped engine is length/hash pinned.

These controls were observed in source and the checks described below. Their presence is not a claim that every supported OS/device enforces them identically.

## 4. Detailed findings

### BTS-01 - Partial-overlay consent remains possible on Android 10-11

**Priority / severity:** P2 / Medium. **Confidence:** High for code behavior; malicious-overlay device exploit not executed.

**Primary location:** `core/designsystem/src/main/kotlin/com/bittool/signer/core/designsystem/security/Hardening.kt:53-54`.

**Supporting locations:** `core/security/.../SecureWindows.kt:28-31`; `feature/sign/.../review/SignBar.kt:140-146,219-225`; `feature/sign/.../review/AuthorizeSheet.kt` (wallet/password confirmation); `core/designsystem/.../security/HardeningTest.kt:65-67`. Paths abbreviated after the module here are resolved by their filenames in the repository.

The touch predicate rejects `FLAG_WINDOW_IS_OBSCURED` but deliberately permits `FLAG_WINDOW_IS_PARTIALLY_OBSCURED` for input-method compatibility. That exception applies to keyboard-free transaction review controls as well as password entry. Foreign overlays are hidden only on API 31+, while the app supports API 26+. API 29-30 expose the partial-occlusion flag without the newer overlay-hiding protection.

An attacker needs a separate installed application with overlay permission, an attacker-controlled signing request and a user who completes wallet password authorization. An overlay can replace the visible recipient/amount while leaving the actual Sign/risk controls uncovered. Those taps are accepted. The later password sheet confirms wallet identity without independently repeating the economic transaction details. This weakens informed consent; it does not bypass the password or change signed bytes.

**Evidence:** the existing Compose test explicitly sends a partially obscured touch and expects the guarded control to click. Source tracing confirms the same guard on signing controls. [Android's tapjacking guidance](https://developer.android.com/privacy-and-security/risks/tapjacking) describes this partial-occlusion attack and mitigation. No on-device malicious-overlay demonstration was performed, and no recovery-phrase reading via an overlay is claimed.

**Remediation:** scope the IME exception to actual entry interactions. Require an unobscured review/confirmation step with the keyboard dismissed, reject partial occlusion there on supported APIs, and retain overlay hiding on API 31+. Define an explicit support policy for older APIs where equivalent detection is unavailable.

**Regression acceptance:** on API 29 and 30, cover the amount/recipient while leaving the Sign button visible; neither signing consent nor the final confirmation may advance. Verify ordinary password entry and legitimate keyboards still work. Repeat on a production build with API 31+ overlay hiding enabled.

### BTS-02 - Mixed Solana transactions understate Metaplex destruction

**Priority / severity:** P2 / Medium. **Confidence:** Reproduced in source and shipped engine.

**Primary locations:** `engine/src/solana.js:153-156,164-167`; aggregation at `:394-425`.

`tmBurn` and `tmBurnNft` create instruction fields and high-risk warnings but do not emit a destruction entry into the shared `movements` collection. The headline aggregator therefore treats a transaction with one such burn and a single SOL transfer as one economic transfer.

**Reproduction:** construct a required-signer legacy Solana message containing a one-lamport System transfer plus a Metaplex `BurnV1` or `BurnNft`, resolve its signing plan, and authorize it with the public test wallet. Both variants return:

```text
summary: 0.000000001 SOL
display.action: Sign Transaction
display.amount: 0.000000001
display.symbol: SOL
```

The prominent economic summary omits the destroyed NFT/token and its mint, and that summary also flows into history. A hostile coordinator can use a dust movement to divert attention from destruction. Engine signatures were generated and verified for the synthetic messages.

**Mitigating controls:** the request remains high risk and partially decoded; irreversible-burn warnings and instruction details are present and risk acknowledgement is required. This is a presentation/consent defect, not silent execution, an acknowledgement bypass or proof of an on-chain burn.

**Remediation:** emit uniform destruction records for all supported burn decoders, including Metaplex. Prefer destructive effects in the main card and summary, retain mint identity and avoid collapsing different assets into one amount. Unknown token standards should remain explicit rather than receiving invented decimals or NFT classification.

**Regression acceptance:** cover each Metaplex burn alone, with dust SOL, with another transfer, with multiple burns of one mint and with multiple mints. The main display and stored summary must disclose destruction in every case.

### BTS-03 - Prompt cancellation can bypass explicit seed cleanup

**Priority / severity:** P2 / Low. **Confidence:** Reproduced against the compiled store and production seed derivation.

**Primary location:** `core/data/src/main/kotlin/com/bittool/signer/core/data/DefaultSignerStore.kt:1072-1077`. The signing path has the same pattern at `:945-952`. `canonical()` at `:1063` has a related ownership gap that was identified statically, not independently reproduced.

The seed is produced inside a dispatcher-changing `withContext`, then assigned to a local variable before entering its wiping `try/finally`. If the caller is cancelled after production but before return dispatch, Kotlin discards the result and throws cancellation. The cleanup block is never entered. This matches the documented [prompt cancellation behavior of withContext](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-context.html).

**Reproduction:** inject a test spy around production `Pbkdf2Bip39Seed`, retain the returned array, cancel the parent immediately before returning it, and invoke the actual `existingWallet`/public-index path. The result is:

```text
operationCancelled=true
seedProduced=true
seedLength=128
seedWiped=false
derivePublicIndexCalls=0
```

The test retains the array only to inspect cleanup and wipes it before exit; it never prints its contents. The engine correctly receives no seed after cancellation, but the independent seed buffer remains uncleared. In normal execution its allocation becomes garbage and may remain until reclaimed/reused.

**Impact and prerequisites:** the BIP39 seed controls the wallet's derived keys. An attacker still needs process-memory inspection or a later memory disclosure; no external extraction channel, password bypass or unauthorized signature was demonstrated. This is a fixable application-owned buffer gap in addition to the unavoidable limits of immutable JVM/JavaScript copies.

**Remediation:** establish ownership in an outer holder before crossing the return-dispatch boundary and wipe from an outer `finally`, as already done by `unlockInto`/vault secret work. Alternatively keep production, use and wiping inside one scope. Audit other cancellable operations returning `SecretText` or secret arrays.

**Regression acceptance:** deterministically cancel after derivation and before main-thread resumption; verify the produced array is zeroed and no signing/public-index call occurs. A security-epoch change alone is not equivalent to cancelling the coroutine.

### BTS-04 - Bitcoin burn is recorded as all funds returning

**Priority / severity:** P2 / Low. **Confidence:** Reproduced in source and shipped engine.

**Primary location:** `engine/src/resolver.js:161-163`. Consumers include `core/model/.../signing/SigningReceipt.kt:111`, `feature/sign/.../result/SignatureResultScreen.kt:276-278` and `feature/activity/.../ActivityDetailScreen.kt:199-200`.

`withVerifiedChange` excludes OP_RETURN outputs from the `sent` total, then equates a zero total with every output returning to the wallet. It does not check whether nonzero value is irreversibly burned.

**Reproduction:** a synthetic 1 BTC input pays 0.49999 BTC to verified wallet change, 0.5 BTC to OP_RETURN and 0.00001 BTC in fees. Resolution and signing succeed. The textual summary is `Every output returns to your wallet`, while the main display correctly says `Burn` and `Destroys 0.5 BTC in an unspendable output`.

**Impact:** the signature-result summary and persisted activity/history misstate a destructive operation as a consolidation. The main pre-sign card and burn warning are accurate, materially reducing immediate signing risk. This is not a hidden-OP_RETURN or invalid-signature finding.

**Remediation:** derive both the display and textual summary from one accounting model that separates payment, verified change, burns and fees. A burn must prevent the all-outputs-return summary.

**Regression acceptance:** exercise change plus burn, change plus burn plus payment, burn without change, and zero-value data outputs. Verify the review, signature-result summary and reconstructed activity record agree.

### BTS-05 - Static QR retry stalls after background interruption

**Priority:** P2 functional reliability; not classified as wallet theft. **Confidence:** Reproduced against compiled production view-model code.

**Primary location:** `feature/sign/src/main/kotlin/com/bittool/signer/feature/sign/scanner/ScannerViewModel.kt:194-197`, with the duplicate filter at `:162`.

If backgrounding changes the security epoch while a QR is being processed, before review publication, the frame is returned to the front of the retry queue. It remains in `seen`. Subsequent callbacks containing the same static QR return at deduplication before `drain()` can execute. No error is recorded, and the error-only Scan Again action is unavailable.

**Reproduction:** suspend processing of `STATIC-QR`, advance the security epoch, finish the interrupted call, then submit that same frame five more times. The store has received it only once; progress remains zero, processing is false and error is null. A different frame drains the queue, confirming the cause. The existing regression test uses a different second frame and misses this case.

**Impact:** a valid static signing request appears unreadable until the camera is closed/reopened or a different frame is presented. No unauthorized signing or key exposure is demonstrated.

**Remediation and acceptance:** make retry queue ownership independent of first-seen deduplication, or explicitly resume draining when foreground processing is allowed. Test identical-frame retry, animated duplicates, repeated backgrounding and stale-session cancellation without introducing a retry loop while suspended.

### BTS-06 - The default registry integrity test uses iOS-only paths

**Priority:** P3 validation reliability. **Confidence:** Reproduced by the unmodified test suite.

**Primary locations:** `engine/test/chain-expansion.test.mjs:117,125-126`.

The test reads `../../BitToolSigner/Resources/token-registry.json`, `TokenIcons.index.json` and `TokenIcons.pack`. Those paths are absent in this Android repository; actual assets are under `core/data/src/main/assets/registry/`. Consequently, `npm test` reports 209 passed and 1 failed, and this test never reaches the Android asset checks.

Only the three paths were adapted in a temporary copy; the exact registry test then passed (one executed test, 18 name-filter skips). This validates the current Android registry projection/icon hashes without hiding the failure of the standard command. No asset tampering or integrity mismatch was found.

**Remediation and acceptance:** resolve Android asset paths explicitly or parameterize platform resource roots. The documented unmodified `npm test` command must pass in a clean Android checkout and must fail when a bundled token decimal or icon byte is deliberately changed in an isolated test fixture.

## 5. Verification results and dependency assessment

The machine-readable verification summary and raw logs are retained in the adjacent `evidence/` directory. Both Gradle invocations succeeded (7m 58s and 10m 12s). Some tasks reused valid incremental/cached results; this report does not call those fresh device executions.

| Check | Result |
| --- | --- |
| Unmodified JavaScript engine suite | 210 tests: 209 passed, 1 failed (BTS-06) |
| Registry test with only Android path adaptation | 1 passed, 18 filtered skips, 0 failures |
| BTC/Solana adversarial engine cases | Confirmed against current source and independently against shipped bundle |
| Seed cancellation | Confirmed; engine call suppressed, seed array not wiped |
| Static QR interruption | Confirmed; repeated identical frame stalls, different-frame control drains |
| Portable native PBKDF2 | 46/46 cross-checks against hashlib/OpenSSL passed, including 600,000 iterations and boundary lengths |
| Engine rebuild | Byte-identical: 1,181,026 bytes; digest below |
| Android/JVM test results | 1613 tests across 19 tasks: 0 failed, 0 skipped; incremental/cached results included |
| Release variants | apkRelease and playRelease: lint 0 errors and 70 warnings per variant (the reports largely repeat the same issues); permission/component checks passed |
| Physical-device instrumentation | Not executed |

Shipped asset and independently rebuilt engine SHA-256:

```text
6933d0d8a6f60faa8222506ac0bd7eef80eb884c9f73e31e9f354b4740f21077
```

Dependencies were installed from the committed lockfile with lifecycle scripts disabled. Current `npm audit` reported six affected package nodes (three low, three moderate), derived from two underlying advisories: [elliptic / GHSA-848j-6mx2-7j84](https://github.com/advisories/GHSA-848j-6mx2-7j84) and [uuid / GHSA-w5hq-g745-h8pq](https://github.com/advisories/GHSA-w5hq-g745-h8pq). These are development/interoperability dependency chains involving the Keystone reference packages, not six independent application vulnerabilities.

`npm audit --omit=dev` returned zero reported vulnerabilities. Rebuilding with an esbuild input manifest confirmed that `elliptic`, `secp256k1`, `hdkey` and `uuid` were absent from the shipped engine dependency graph; the rebuilt bytes exactly matched the checked-in asset. This supports a test-tooling exposure classification for these specific advisories, not an assertion that all runtime dependencies are safe. Update or replace the affected development dependencies without blindly applying the suggested major-version downgrade.

The vendored QuickJS identifies itself as 2026-06-04, also listed by the [upstream project](https://bellard.org/quickjs/). The host wrapper was reviewed; the full JavaScript VM, Android platform, cryptographic dependencies and native side channels were not exhaustively audited. No complete Maven/Android/native CVE database scan is claimed.

## 6. Residual risk and release qualification

**Compromised device and memory.** This is a software wallet/signing device, without an independent trusted display. A rooted OS, injected process code, malicious trusted accessibility service or compromised input method can exceed the assumptions of ordinary app isolation. Mutable-array wiping reduces exposure but cannot promise erasure of every JVM/Compose/JavaScript copy. Software retry counters are not hardware anti-rollback.

**Offline execution limits.** Review cannot establish live contract code, token behavior, balances, current authorities/permissions, nonce freshness or final execution outcome. Known selectors and bundled metadata are descriptions, not proof of safe execution. Risk acknowledgement remains meaningful and must not be used as a substitute for accurate destructive-operation summaries.

**Resource exhaustion.** Input/node/depth/heap/stack limits are present. The QuickJS wrapper has no observed per-operation interrupt deadline. No exploitable unbounded-input case was demonstrated in this pass; adversarial timing/fuzz tests on low-memory supported phones remain useful hardening work, not an additional confirmed finding.

**Lint coverage.** The 70 warnings per variant concern API constants, version/update suggestions, Compose parameter conventions, plural strings, resources and Gradle conventions. They are retained in the evidence rather than described as a clean lint run. Android lint reported that the plain JVM `core:model` module was treated as an external dependency and its source was not analyzed. That module was covered by source review and 109 JVM tests; passing Android lint must not be represented as complete source coverage.

**Devices and distribution.** JVM/Robolectric tests do not validate StrongBox fallback, ARMv8 native acceleration, biometric/OEM quirks, overlays, capture/mirroring, backup behavior or physical attacks. No production APK was installed or exercised on a device, and existing APK/AAB artifacts were not treated as verified products of this exact commit. No release-signing key, published package, Play distribution or external wallet camera round trip was certified.

Before a release decision, run production-build device checks on representative API 26-28, 29-30, 31-34 and 35+ devices: lock-screen removal/recreation, face/Smart Lock versus strong unlock, Keystore failures, overlay consent, screenshot/recording/external displays, backgrounding during derivation/signing/scanning, process death and offline response export. Use public test wallets only.

## 7. Remediation sequence and evidence

1. Correct BTS-01 and BTS-02 so consent cannot rely on partially covered or economically incomplete review screens.
2. Close BTS-03 with deterministic cancellation-cleanup tests; apply the ownership rule to similar secret-bearing return values.
3. Unify BTC burn summaries (BTS-04), repair identical-frame retry (BTS-05), and restore the standard Android registry test (BTS-06).
4. Triage development advisories, rerun the unmodified suites and release checks, then perform the device matrix above.

Evidence beside this report includes source/asset bundle comparison, dependency audit JSON, engine test logs, burn reproduction scripts and outputs, the path-only registry adaptation, Kotlin cancellation/scanner reproduction sources and outputs, and the final Gradle/lint verification summary. Temporary diagnostic paths and compiler caches are not prerequisites for understanding the findings; JVM reproduction runners additionally require the repository to be built and the matching JDK/Gradle dependency cache.

The audit made no production fixes, commits, pushes, broadcasts or releases. A remediation review should identify its own new baseline and explicitly close each finding with both regression evidence and the applicable platform validation.
