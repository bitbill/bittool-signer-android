# Contracts review of Wave 0 (adversarial pass, 2026-10-21)

Reviewer brief: find everything in `ARCHITECTURE.md` and the Wave-0 contracts (`:core:common`, `:core:model`,
`:core:navigation`, `EngineCalls`, the API-only `:core:crypto` / `:core:data` / `:core:security`, the feature stubs)
that would make the ten implementation agents guess, block or diverge from iOS, and fix it in place without changing
the architect's structure.

Inputs read: `DECISIONS.md`, `ARCHITECTURE.md`, every spec in `docs/port/` (models-state, setup-backup, scan-sign,
wallets-pairing, activity-settings, app-shell, vault-crypto, engine-contract §9/§8, gaps.md in full, design-system and
ownbit-ui-patterns where they define contracts), all Wave-0 Kotlin sources and tests, and the iOS sources they port
(`Models/Models.swift`, `AppStore.swift`, `BIP39Wordlist.swift`, `BackupVerification.swift`, `RecoveryCards.swift`,
`ReviewText.swift`, `PasswordPolicy.swift`, `AppAccessController.swift`, `RequestReviewView.swift` (summary, notice,
warnings), `ActivityTab.swift`, `LegalDocumentView.swift`, `TokenDirectoryView.swift`, `QRScannerView.swift` gate) and
every file of `BitToolSignerTests/`.

Build: `tools/gradle-slot.sh ./gradlew :core:common:testDebugUnitTest :core:model:test
:core:navigation:testDebugUnitTest :core:engine:testDebugUnitTest :core:crypto:compileDebugKotlin
:core:data:compileDebugKotlin :core:security:compileDebugKotlin` — green before and after the changes (model 109,
common 7, navigation 10, engine 7 tests; 0 failures).

---

## 1. Model completeness (models-state.md and the Swift it cites)

Checked type by type against the Swift source, not only the spec: `Chain`, `NetworkBrand`, `AccountProfile(s)`,
`ConnectOptions`, `AddressFormatter`, `VaultMetadata`/`VaultSecret`, `PublicMetadataStatus`, `WalletPublicMetadata`
(validation order, error texts, `isHex`, `publicOnly` depth/array/string rules, 256 KiB on canonical JSON, lowercase
identities), `PublicAccount`, `TransactionReview` (every default incl. the risk fallback and `networkName`),
`ReviewField` (`group`, `shortLabel`, `isLong` with CR LF as one Character, `baseLabel` = after the **last** ": ",
token/number/address rules), `ReviewAsset` (strict decode: wrong type on an optional key fails the whole asset),
`ReviewNetwork.brand`, `ReviewContract` (`protocol` key, "known without a name is no hit", token branch),
`ReviewDisplay` (lenient generic keys, fail-closed NFT keys, `boundedValue` 300 graphemes no truncation,
`boundedLines` all-or-nothing + "+N more", middle ellipsis 149/150, `#id` digit boundary, spoken ID),
`QrPayloadView` (formats, byte count fallback, BBQr char, summaries, PSBT set), `PairingPayload`,
`WalletPairingPayload` (verify rows, `chain/profile` ids, pretty JSON), `SigningPlan`/`SigningTask` (exact,
case-sensitive request hash, 1…64 tasks, grapheme length 64 of `walletKeyId`, distinct display values),
`SigningReceipt` + `ReceiptJournal` (stored() rules, retention 25/100, shed-oldest loop, read validation),
`ReviewText` (phrase/headline/label, `shortDecimal` string carry), `RecoveryCards`, `BackupVerification`,
`PasswordPolicy` (categories per scalar, Cc+Cf, grapheme length, 1,024 bytes, strength), `QrScanFrameGate`,
`AppLanguage`, `CompatibleWallets` (directory merge rule), registry types, `LegalDocumentParser` (Swift `isNewline`
set), `MatchingIssue`, `UiText` fallback formatting. Also ran a script over every `UiText.Ios(...)`/`AppIssue(...)`
literal in `core/**/src/main`: all are catalogue keys in `tools/l10n/strings-index.tsv`.

Result: the model layer is faithful. Findings:

| # | Finding | Severity | Change |
|---|---|---|---|
| M1 | `ReceiptJournal.isValid` checked receipt ids with `java.util.UUID.fromString`, which accepts `"1-1-1-1-1"`; iOS decodes `UUID` strictly. | low | Uses `WalletId.parse` (strict 36-char) for `id`; test cases added to `SigningTest.anyInvalidRecordRejectsTheWholeFile`. |
| M2 | Activity's "Signed Request" page uses a **different** warning-severity rule on iOS (`ActivityTab.swift:292`: any "HIGH" prefix) than the review page (HIGH RISK / UNLIMITED / HIGH FEE). The shared `:core:ui` `ReviewWarnings(review)` would have applied the review rule in Activity. | medium (visible divergence) | `ReviewPresentation.isSevereStoredWarning` added + test; `ReviewWarnings` signature in ARCHITECTURE §1.11 now takes the rule. |
| M3 | Strictness differences that are deliberate and fail closed (documented, not changed): Swift `as? Bool` accepts NSNumber 0/1 and `as? Int` accepts `1.0`, Kotlin readers do not; ICU `$` in iOS `isHex` tolerates a trailing newline, Kotlin does not; Swift `==` uses canonical equivalence in `password == confirmation`, Kotlin compares code units (stricter, matches the KDF bytes). The engine never emits the lenient forms. | info | none |
| M4 | `Bip39Canonical.phrase` is a `String` (unwipeable). Accepted: iOS parity, and the words must become display strings anyway; the store/vault APIs take `SecretText`. | info | none |

Ported tests vs iOS: compared every model test with the iOS case it claims to port (assertions, boundaries, vectors).
They assert the iOS behaviour. The coverage table in ARCHITECTURE §5 was wrong in places (see §5 below).

## 2. API sufficiency (walked every user action and displayed datum of the five feature specs)

Setup (create/import/backup/verify/password/done, reset mode, intro gate, scanner), Wallets (cards, detail, rename,
change/delete/repair, recovery phrase, addresses paging, Connect both levels with prefetch), Scan/Sign (home, paste,
file, scanner session queue, review states A/B/C, reject, authorize per task, result, export), Activity (list, clear,
detail re-review, signed request, QR sheet, export), Settings (App Lock row, theme, language, networks/search,
compatibility, contact, open source/digest, notices, legal), shell (stages, lock window, cover, alert, prefetch gate).
Everything displayed or acted on is reachable through `SignerState`/`SignerStore`, `SignerPreferences`, the
repositories, `AppAccessController`, `RecoverySessionFactory`, `AppLifecycleMonitor`, `AppRouter` and `BuildInfo`.
Gaps and ambiguities found:

| # | Finding | Severity | Change |
|---|---|---|---|
| A1 | **Commit boundaries were left to callers** ("the caller must not cancel it"). On Android a ViewModel scope or a sheet dismissal cancels the coroutine; a cancellation after `vault.create` would skip the reload and invite a duplicate import (the exact iOS concern). Same for reset, delete, repair and the receipt append after a signature. | high | `SignerStore` KDoc: the store runs everything after the persistent write in `withContext(NonCancellable)` without epoch checks (create, reset, delete, completeSetup, sign's receipt append); `sign` returns after the journal write. |
| A2 | **Kotlin-host engine messages could not be translated** (DECISIONS §9.2 says translate the ~60 non-catalogue strings; the nine host `EngineException` messages are not in `IosStrings`, and `toUiText()` shows them verbatim). No module was responsible. | medium | `SignerStore` KDoc: one mapping in the store rethrows those nine exact (code + English) as `SignerException(UiText.Resource(strings_data…))`; engine-authored messages stay verbatim. Store-authored non-catalogue messages are published as `UiText.Resource`. |
| A3 | **No owner for the ~60 G6 strings** used across packages ("Setup required" is drawn by `:core:ui`, wallets, sign and setup) → duplicate keys or missing ones. | medium | ARCHITECTURE §4.3: one-owner table per G6 string and the rule "lowest module that shows it"; `UiText.Ios` vs `UiText.Resource` rule spelled out. |
| A4 | **Dialogs and sheets would open above the App Lock window.** `WindowGate` existed only in `:core:security`; `SignerAlertDialog`/`SignerBottomSheet` (designsystem) cannot see it, so a late error dialog would draw over the lock screen (privacy leak; iOS presents beneath its lock window). | high | ARCHITECTURE §1.10: `LocalWindowGateOpen` + `rememberWindowGateReleased()` in `:core:designsystem` (Ownbit semantics), honoured by every dialog/sheet and feature-owned `Dialog`; §2.1 `:app` wiring; `WindowGate` / `LockWindowController` KDoc (close with the lock, reopen ~450 ms after unlock, lock window outside the gate). |
| A5 | Overlay hardening (G4.1) required `setHideOverlayWindows(true)` on **every** own window, but the dialog/sheet contracts did not say so. | medium | §1.10 dialog/sheet rows and `LockWindowController` KDoc. |
| A6 | `existingWallet` with duplicates was unordered on iOS (dictionary); Android would have picked arbitrarily. | low | KDoc: first match in vault order; canonicalises with `validatedCanonical`. |
| A7 | A corrupt/undecryptable journal blocks new receipts and hides "Clear" (activity-settings §2.2 flags this for product); the state could not express it. | low | `SignerState.activityUnreadable` (Android-only, default UI keeps iOS) + `reload`/`clearHistory` KDoc. |
| A8 | App Lock contract details missing vs `AppAccessController.swift`: `setEnabled` is a no-op when unchanged, re-checks the wallet count after the prompt, goes through the guarded auth path and also stores the error; `authenticate`/`authenticateBiometrics` return at once unless enabled **and** locked and are refused in the background. `BiometricAuthenticator` did not say what happens without a resumed activity. | medium | `AppAccess.kt` KDoc for all four. |
| A9 | Debug UI-test overrides (App Lock on, appearance, onboarding done when fixtures > 0) had no defined place; applying them after App Lock read the preference would race. | medium | `SignerPreferences` KDoc: applied inside the first load before `values` is published. |
| A10 | Open Source › This build "Signing certificate" row (activity-settings §14.8, also the app build file's own comment) had no data source. | low | `BuildInfo.signingCertificateSha256` (default null; `:app` reads `PackageManager`). `displayVersion` KDoc flags "1.0.0 (0)" vs iOS "1.0.0 (1)". |
| A11 | Setup needs a CSPRNG for `BackupVerification.create` and lifecycle signals, but `SecureRandomSource` / `AppLifecycleMonitor` were not in the list of injectables features may use. | low | ARCHITECTURE §1.1 list. |
| A12 | `SignerStore.sign`: behaviour under `suspendSession()` mid-sign (busy reset, late result discarded, review kept) was implicit. `beginScan` can throw. | low | KDoc. |
| A13 | Receipt ciphertext bound before decrypt was "a cap" without a value; notices file for the Android dependencies had no path; digest page could hash the asset a second way (G7.5). | low | `ReceiptRepository` (FILE_LIMIT + 33), `NoticeSection` (asset path and order), `engineDigest` (= `SignerEngine.bundleDigest()`). |
| A14 | `SetupFlowHost` did not say how its state holder lives/ends (secrets must be cleared and the sensitive session ended exactly when the presentation ends) or that its windows honour the gate. | medium | `SetupNavigation.kt` KDoc. |
| A15 | Stub KDoc invited inventions: "wallet-switcher" (Wallets) and "plan details" / "signature QR sheet" (Sign) do not exist on iOS; Recovery Phrase cited the wrong section. | low | `WalletsNavigation.kt`, `SignNavigation.kt`, `SetupNavigation.kt` KDoc. |

Checked and found sufficient (no change): epoch rules and their consumers (app-shell §4.6 table), scan-session queue
semantics (`beginScan`/`receive`/`endScan` + `securityEpoch` re-queue), `previousResult` chaining (kept as raw
`JsonObject`, `EngineCalls.signPlanTask`), plan snapshot invalidation on reload, refined review, warm-context prefetch
(600 ms, drop conditions, matrices for the first frame), `retryMatching`, `reviewStoredRequest` (case-insensitive,
no epoch), receipt retention, busy states, `MatchingIssue` explanations/retry, `AppRouter` scan token and
setup-dismiss ordering (channel buffers `Replace` across the welcome → main stage switch).

## 3. Crypto / security API vs vault-crypto.md and app-shell.md

| # | Finding | Severity | Change |
|---|---|---|---|
| C1 | Byte formats that must be chosen once (Android vaults are never portable) were left to "suggested" spec text: header JSON keys, `createdAt` encoding, id case, base64 variant, attempts plaintext and AAD. Two implementers could not have produced compatible files across a later rewrite. | medium | `WalletVault` KDoc now fixes them (vault-crypto §6.3, §17.5). |
| C2 | `DeviceKeyStore` KDoc says every secret operation refuses without a lock screen, but `unlock` did not list `DevicePasscodeRequired`, nor where in the order it happens, nor that an empty/over-1,024-byte password is a counted `AuthenticationFailed`, nor where a missing device key is detected (Android has no key-read step; it surfaces when sealing the attempt). | medium | `unlock` KDoc gives the exact order and error per step. |
| C3 | `resetPassword` did not state the policy check and the passphrase cap as `InvalidPassword("The BIP39 passphrase exceeds 1,024 UTF-8 bytes.")` before anything is read; `delete` did not state the error per step. | low | KDoc. |
| C4 | BIP39 (10 lists, NFKD keys, accent folding, grapheme-aware prefix, detection order, `interpretations` with typed-accent rule, ambiguity, `validatedCanonical`, draft, SeedQR), HMAC public index, repair flow, password policy, App Lock state machine (grace on `elapsedRealtime`, fail-closed resolving), privacy sessions (mount/stop/prompt/acknowledge, stale-warning ids): all expressible through the declared API. | — | none |

## 4. Screen inventory and navigation

Compared the inventory (75 rows) with every screen, sheet, alert, menu, picker and cover in the specs. Added row 38b
(address row context menu "Copy Address" / "Show QR Code"). Every route type needed exists; modal routes and the
root-level Submit route are consistent with the tab glue. Two decisions were implicit and are now written down:

- Back on the Scan tab returns to Wallets and never rejects/finishes (ARCHITECTURE §2.3 + §6 item 12) — scan-sign
  §23.7 had proposed Back = Reject / Done; the architecture's choice now reads as a deliberate deviation.
- A failed first load shows the failure page **and** the "Unable to Continue" alert, as iOS (app-shell §15.14 asked to
  decide) — §2.1.

## 5. Work packages

| # | Finding | Severity | Change |
|---|---|---|---|
| W1 | **Wave-1 packages were told to test with each other's code** ("`InMemoryDeviceKeyStore` … used by W1-data tests"; W1-data acceptance `connectedDebugAndroidTest` with the real vault) although Wave 1 runs in parallel and compiles only against Wave-0 APIs. W1-data would have blocked. | high | §3 preamble: each Wave-1 package writes its own test doubles; published fakes are for Wave 2; Integration reruns W1-data's instrumented tests with real crypto. |
| W2 | **SessionTests (15) cannot run as JVM tests**: iOS wraps the real `SigningEngine` (`ControlledSigningEngine`), and `derivePublicIndex`/pairing outputs are real engine data. The plan said "fake engine through EngineCalls". | high | W1-data: instrumented port with a gated/counting decorator over the real `QuickJsSignerEngine`; JVM tests only for pure pipeline rules. |
| W3 | W1-data did not list `Bip39` among its dependencies although create/existing/reset call `validatedCanonical`. | medium | Added. |
| W4 | Spec sections without an owner: the Android legal texts in `app/src/{apk,play}/assets/legal` (DECISIONS §9.5; W2-activity-settings could not write `app/**`), the `THIRD_PARTY_LICENSES_ANDROID.txt` asset, gaps G5.3/G5.4, the `values-en` locale pitfall (activity-settings §14.5(a): `[en-GB, de]` resolves German), theme application, `LocalTokenIconSource`, the window-gate wiring, fixture creation errors. | medium | Assigned (W2-shell incl. `core/l10n/build.gradle.kts` in Wave 2; W1-data for the notices asset); §2.1 lists the `:app` duties. |
| W5 | iOS tests not assigned or mis-assigned: RecoveryCardsTests `wordGridsDropColumnsBeforeCuttingWordsOff` (setup grid), NFTReviewDisplayTests' 4 view cases, LegalDocumentTests' bundled/wrapped/privacy-claims cases, SecurityTests' 3 reset cases (vault tests, listed under data) and 2 engine cases, KnownContractTests counts (10/3/1, not 9/4/1), ActivityRecordTests split (4 pure / 6 store). | medium | §5 table rewritten; package iOS-test lists updated (W1-crypto, W1-data, W1-design, W2-setup, W2-shell, W2-activity-settings). |
| W6 | Fakes Wave 2 needs were missing: `FakeAppLifecycleMonitor`, `FakeBiometricAuthenticator`, `FakeReferenceDataRepository`, `FakeLegalDocumentRepository`. | low | Added to W1-security / W1-data "Provides". |

Owned paths re-checked: disjoint (the only shared tree, `core/l10n/src/main/res/values/`, is split by
`strings_<area>.xml` file; `core/l10n/build.gradle.kts` now belongs to W2-shell in Wave 2 only). Acceptance tasks
re-checked for runnability: all are module-local Gradle tasks; instrumented ones need emulator-5554.

## 6. Files changed

- `core/data/src/main/kotlin/com/bittool/signer/core/data/SignerStore.kt` — commit boundaries, error translation,
  `existingWallet`, `beginScan`, `sign`, `reload`, `clearHistory` KDoc.
- `core/data/src/main/kotlin/com/bittool/signer/core/data/SignerState.kt` — `activityUnreadable`.
- `core/data/src/main/kotlin/com/bittool/signer/core/data/Repositories.kt` — UI-test preference overrides, journal
  bounds, notices files, digest source.
- `core/crypto/src/main/kotlin/com/bittool/signer/core/crypto/vault/WalletVault.kt` — byte formats, lock-screen rule,
  `unlock` order, `resetPassword`, `delete`.
- `core/security/src/main/kotlin/com/bittool/signer/core/security/AppAccess.kt` — `setEnabled`, `authenticate*`,
  `BiometricAuthenticator.authenticate`.
- `core/security/src/main/kotlin/com/bittool/signer/core/security/Privacy.kt` — `LockWindowController`, `WindowGate`.
- `core/common/src/main/kotlin/com/bittool/signer/core/common/BuildInfo.kt` — `signingCertificateSha256`,
  `displayVersion` note.
- `core/model/src/main/kotlin/com/bittool/signer/core/model/signing/SigningReceipt.kt` — strict receipt ids.
- `core/model/src/main/kotlin/com/bittool/signer/core/model/review/ReviewPresentation.kt` — `isSevereStoredWarning`.
- `core/model/src/test/.../signing/SigningTest.kt`, `.../review/ReviewModelTest.kt` — assertions for the two above.
- `feature/{wallets,sign,setup}/src/main/kotlin/.../*Navigation.kt` — KDoc only.
- `docs/port/ARCHITECTURE.md` — §1.1, §1.2, §1.8 (+ store contract points), §1.9, §1.10 (window gate, overlays,
  sensitive marking), §1.11 (`ReviewWarnings`), §2.1 (`:app` duties), §2.3, §2.5 (row 38b), §3 (Wave-1 testing rule
  and every package), §4.3 (G6 ownership table), §5 (test table), §6 (items 12–17, open questions).

## 7. Not resolved here (owner decisions; none blocks Wave 1)

1. Default build number: Android shows "1.0.0 (0)" unless `-Psigner.buildNumber=1` becomes the default (iOS "1.0.0 (1)");
   W2-shell decides in `app/build.gradle.kts`.
2. Whether Activity offers "Clear Activity" while the journal is unreadable (`activityUnreadable`; iOS hides it).
3. "BitTool Wallet" platforms "iOS" vs "iOS · Android" (models-state §10.3) — data unchanged.
4. Product names in the G6 set (Taproot, Account XPUB …): translate or keep `translatable="false"`.
5. The "Allow camera" button wording (scan-sign §23.5) if product wants one.
6. Legal texts for both flavors still need the legal owner's review (DECISIONS §9.5).
