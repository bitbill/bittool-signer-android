# QA round 2: tour "activity" (Activity, sign-xx re-verification, Settings)

Device: emulator-5558 (API 31, 1080×2220 @ 440 dpi). Its emulator console reports the AVD name **SignerQA2**
(`adb -s emulator-5558 emu avd name`), but the task brief calls it SignerQA3. Installed build: `com.bittool.signer.debug`
1.0.0-debug, installed 14:23:32 from the 14:20 apk-debug build (after the QA round 1 merges at 14:19). Its dex contains
the round 1 fix code (`FixtureJournalCipher` for sign-01, `RevealedErrorCallout` for sign-06/-07). It does **not**
contain the later main commits `fd447d2` (B20: Android-only strings translated, 14:42) or `88a214a`. Screenshots:
`build/qa/round2/activity/`. iOS ground truth: `BitSigner/BitToolSigner/Features/Activity/ActivityTab.swift`. Spec:
`docs/port/activity-settings.md` §2.

## Summary

**The device tour was not run.** For the whole session, from 14:28 to 16:07, emulator-5558 sat at the Android PIN
lock screen (`dumpsys window`: `isKeyguardShowing=true`; `00-initial-s.png`, `02-still-locked-s.png`). `wm dismiss-keyguard`
only brings up the PIN bouncer. I am not permitted to enter, clear or reset a device PIN. No fingerprint is enrolled, so
there is no other way to unlock. I asked the coordinator to have the user unlock it by hand, and the coordinator agreed
and passed the request on. I then polled the keyguard every 15 s for about 95 minutes, but the device was never
unlocked. The screen-off timeout is already at its maximum (`screen_off_timeout=2147483647`, `stay_on_while_plugged_in=1`),
so one unlock by hand will last for a full re-run.

What was done instead:
- A static review of `:feature:activity`, the journal path in `:core:data` / `:core:model` and the debug harness,
  against `ActivityTab.swift`, activity-settings.md §2 and the iOS reference shots (`light-30`, `-31`, `-31b`, `-31c`,
  `-31d`).
- A code-level check that each round 1 sign-xx fix is present in the installed APK.

Neither replaces the device tour. Every behaviour the brief asks for is listed under "Not tested".

Counts (static findings only): **P0 0 · P1 0 · P2 0 · P3 1**. Device-level counts: not available.

Test conditions: host load was low (load average 0.1–0.4 on the emulator), but that does not matter, because nothing ran
on screen.

## Issues

| ID | Sev | Screen | Steps | Expected (iOS / spec) | Actual (evidence) | Suspected owner · file:line |
|---|---|---|---|---|---|---|
| activity-01 | P3 | QA harness (Activity unreadable state) | Brief: "unreadable-journal state if reachable (--lose-keys)". Launch with `tools/qa/ui.py launch --lose-keys` | A harness switch reaches `SignerState.activityUnreadable`, so the "Activity Unavailable" state, the hidden Clear and the "could not be saved" alert on the next signature (activity-settings.md §2.2, DECISIONS §10) can be checked on a device and in e2e | Static: `--lose-keys` (`bts.uitest.loseDeviceKeys`) deletes only `<wallet id>.key` and keeps "the public-data key **and the journal key**", so the journal stays readable. No other extra corrupts or drops `FixtureKeys/journal.key` or `receipts.bin`. The unreadable state can only be reached by editing the run's files by hand (`run-as`) | :app (debug harness) · `app/src/debug/kotlin/com/bittool/signer/harness/FixtureDeviceKeyStore.kt:152-156` (`loseWalletKeys`), `DebugUiTestHarness.kt:67,107`; `tools/qa/ui.py:4,21`. Add e.g. `bts.uitest.loseJournalKey` (move `journal.key` aside) and a `ui.py launch --lose-journal` flag |

Not counted (decided or already tracked, confirmed in code):
- An unreadable journal is a dead end. While `activityUnreadable` is set, Clear is hidden
  (`ActivityViewModel.kt:41`, `canClear = days.isNotEmpty()`), so the store's recovery path
  (`SignerStore.clearHistory` overwrites an unreadable journal, `SignerStore.kt:262-266`) cannot be reached from the UI.
  Every later signature then raises "Unable to Continue — The local activity record could not be saved…"
  (`DefaultSignerStore.kt:869-884`). This is iOS parity and was decided in DECISIONS §10. The product owner may still
  want a recovery path (activity-settings.md §2.2 flags this).
- The Android-only Activity strings ("Deleted wallet", "High", "Warnings shown", "Partial signature created",
  "Activity Unavailable", "The file could not be saved.", `core/l10n/src/main/res/values/strings_activity.xml`) are
  English in every language **in the installed build**. This is backlog B20, fixed on main in `fd447d2` after this build.
- Deliberate deviations from iOS that match the spec's recommendations:
  - The row says "Deleted wallet" where iOS says "Wallet" (`ActivityModels.kt:13-17`; owner question in B17).
  - Receipt titles and the Action essential are translated through `ReviewText` where iOS shows engine English
    (`ActivityUi.kt:192-193`, `ActivityDetailScreen.kt:281-282`).
  - Day headers are accent-coloured `GroupedSection` headers (design-system decision, `GroupedSection.kt:71`), where
    iOS draws a grey header (`light-30-activity.png`).

## Verified working

Statically only, from the code. None of this was observed on the device.

- **Journal retention** (`ReceiptJournal.appended`, `core/model/.../signing/SigningReceipt.kt`): matches
  `AppStore.swift:554-569`.
  - At most 100 records, newest first. Records at index 25 and above lose their payloads.
  - While the encoding is over 2,097,152 bytes, the oldest record that still has payloads is stripped.
  - A payload over 32,768 UTF-8 bytes or over 2,048 frames is left out, never truncated.
  - The summary is cut to 160 graphemes. The title is clamped to 200, which is safer than iOS: iOS writes it unclamped
    but rejects more than 200 on read.
  - Only the final task carries response payloads, and a partial task is recorded as "Partial signature created".
- **Re-review** (`DefaultSignerStore.reviewStoredRequest`, `:840-850`): matches `AppStore.swift:581-591`.
  - It rejects a request over 32 KiB ("The stored request could not be read.").
  - It runs a fresh engine `review`, with no password, matching or epoch.
  - The `requestHash` is compared case-insensitively, and a mismatch or empty fields gives "The stored request does
    not match this signature's fingerprint."
  - The detail decodes once per visit, a failure is retried on reappearance, and "Signed Request" shares the detail
    ViewModel, so the engine does not run a second time (`ActivityNavigation.kt:30-40`).
- **List** (`ActivityScreen.kt`, `ActivityModels.kt:60-75`, `ActivityUi.kt`):
  - Receipts are grouped by device-zone day, days descending, receipts descending within a day.
  - Each day has a sticky header (`EEEEMMMdy` best pattern in the app locale) and a footnote ends the list.
  - A row shows the 36 dp logo (EVM by chain ID, generic logo for other chain IDs), a 2-line title, a 1-line
    "summary · wallet" subtitle, the time (device 24-hour setting) and a chevron. At accessibility text sizes the time
    is stacked under the subtitle.
  - The empty state is "No Signatures Yet". Clear shows only while there are records.
  - The confirmation ("Clear all activity?", "Clear Activity", "Cancel") is dropped when `securityEpoch` changes, and
    an obscured touch on the confirm button is refused.
  - Reselecting the tab scrolls to the top.
- **Detail** (`ActivityDetailScreen.kt`):
  - The sections are in iOS order: header, metadata, Request fingerprint, Input, Output, retention note.
  - Metadata shows Date, Network (`networkName ?: chain`), Wallet (or "Deleted wallet"), Fingerprint (stored value,
    else the wallet's current one, upper case), and Risk only when the level is not normal.
  - Input shows "Decoding again…", the failure (warning tone), or the essentials (Action / Amount / counterparty label /
    Network fee or Energy budget with `shortDecimal`) plus "All Decoded Fields", followed by the "Request Payload"
    disclosure.
  - Output shows "Show QR Code" only when frames exist and "Export as File" only when text exists (CreateDocument
    `text/plain`, `bittool-signer-signature.txt`; the text is taken after the store reloads, so an export that returns
    after a process restart still writes; failures are shown inline in red), followed by the "Signature Payload"
    disclosure.
  - The retention note shows only when nothing is kept. A record that disappears pops its pages.
- **Signature QR sheet** (`SignatureQrSheet.kt`): "Signature" with "Done", the QR at up to 320 dp, the info Callout,
  opened full height (`SignerBottomSheet` `skipPartiallyExpanded = true`).
- **Date patterns**: ICU best patterns for the three Activity skeletons (`EEEEMMMdy`, `yMMMdhms`, `yMMMdHms`) in all 21
  app languages contain only pattern letters that `java.time` on API 26+ accepts. There is no `B` day-period letter, so
  `DateTimeFormatter.ofPattern` cannot throw (checked with ICU4J 78.3; the device ICU is older).
- **Round 1 sign fixes**: the installed APK contains them, as shown by dex strings and the build time against the merge
  time. The claimed fix commits are:

  | Finding | Fix commit(s) |
  |---|---|
  | sign-01 | `0625ba4` |
  | sign-02 | `bb02f01` |
  | sign-03 | `45ed419` |
  | sign-04, -06, -07, -08, -11, -13, -17 | `e366943` |
  | sign-05 | `fe5634c` |
  | sign-09 | `943c149` |
  | sign-10 | `2ea9c74` |
  | sign-12, -15, -16 | `bc29c05`, `b3df907` |
  | sign-14 | `5d8e8c5` |
  | sign-18 | `9099984`, `c32d841` |

  None of them was re-verified on the device.

## Not tested, and why

Everything below needs the device, and the device stayed at the PIN keyguard for the whole session (see Summary). It
all remains open for a re-run once emulator-5558 is unlocked by hand:

- Creating "Reference Wallet" from the BIP39 reference phrase (fingerprint 73C5DA0A), and the second wallet for the 2-of-2
  partial receipts.
- Signing the round 1 vectors and recording their receipts:
  - EVM: `rt-evm-personal`, `rt-evm-eip712`, `rt-evm-eip1559`, `rt-evm-legacy-multibyte-v`, the latter with chain ID
    43114, which should show the generic logo.
  - `rt-bitcoin-bip84-psbt` and `btc-multi-input.psbt`.
  - `rt-tron-trx-transfer` and `rt-tron-message`.
  - `rt-solana-legacy-transfer`, `rt-solana-message` and `solana-opaque`.
  - `evm-approve-unlimited` / `evm-usdt-transfer` for the Amount, counterparty and Risk rows.
  - `btc-multisig-2of2` for "Partial signature created".

  The request files are staged on the device in `/sdcard/Download/qa/` and in
  `/private/tmp/claude-501/-Users-tanguopeng-Documents-bitool-signer-android/1bc3e92b-171d-46e2-bf4f-c5850df58c91/scratchpad/act2/req/`.
  The sign-cycle helper is
  `/private/tmp/claude-501/-Users-tanguopeng-Documents-bitool-signer-android/1bc3e92b-171d-46e2-bf4f-c5850df58c91/scratchpad/act2/qa.py`.
- The Activity list on the device: grouping by date (including a day boundary), row content, logos, time format, the
  sticky headers, the footer, light/dark, and comparison with `light-30-activity.png`.
- The detail on the device:
  - The fresh re-review with a matching request fingerprint, and the essentials per chain.
  - "All Decoded Fields" against `light-31c`.
  - The response QR sheet again (static and animated multi-frame) against `light-31d`.
  - Export through the system picker, with a byte comparison of the exported file against the vectors' `responseFrames`.
  - The Request and Signature payload disclosures and their copy buttons.
- Retention: signing more than 25 requests so that a record drops its payloads and shows the retention note, the
  100-record cap, and "Signed Request" while a record ages out.
- Records surviving a wallet deletion ("Deleted wallet" in the row and the detail, the fingerprint kept), and Clear
  Activity (the confirmation, its dismissal on background, the empty state afterwards).
- The unreadable-journal state: not reachable through `--lose-keys` in any case (activity-01). The only route is moving
  the run's `FixtureKeys/journal.key` aside with `run-as`.
- Device re-verification of every round 1 fix, sign-01 to sign-18 (`docs/qa/round1-sign.md`).
- Settings: every page, the languages zh-Hans, ja and ar, and font scale 2.0. The font scale was never changed, so it is
  still 1.0.
