# QA round 2: tour "activity" (BLOCKED, device tour not run)

Device: emulator-5558 (`adb emu avd name` reports **SignerQA2**, not SignerQA3 as briefed), API 31, 1080×2220 @ 440 dpi.
Debug build `com.bittool.signer.debug` 1.0.0-debug, installed 2026-10-21 14:23 (main at 8b03cb2).
Screenshots: `build/qa/round2/activity/` (only `00-initial.png` and `01-after-dismiss.png`, both of the system lock screen).

## Summary

**The device tour could not start.** emulator-5558 shows the Android keyguard (`isKeyguardShowing=true`, PIN pad after
`wm dismiss-keyguard`; `00-initial-s.png`, `01-after-dismiss-s.png`). The emulator has a PIN set, and nothing in the app
can be reached until someone enters it. Entering a device PIN at the OS lock screen, or clearing or re-setting it, is an
operating-system credential action I may not perform. A relayed agent message offered PIN 1234, and I declined it on the
same grounds. I asked the coordinator to have the user unlock the emulator by hand and polled the keyguard for about
45 minutes (14:29 to 15:14 AEDT). It stayed locked.

So nothing below was verified on the device in this round: no wallet created, no request signed, no Activity screen
seen, no round-1 sign fix re-checked, no Settings page re-checked. Counts: **P0 0 · P1 0 · P2 0 · P3 0** (none confirmed).
The only concrete finding is an environment one (env-01).

To unblock: unlock emulator-5558 by hand once. The screen-off timeout is already 2147483647 ms and
`stay_on_while_plugged_in=1`, so it stays unlocked. Then re-run this tour unchanged. Alternatively, run it on an emulator
with no lock screen: the harness no longer needs one for Activity, because `FixtureJournalCipher` covers it
(round-1 sign-01 fix).

## Issues

| ID | Sev | Screen | Steps | Expected | Actual (screenshot) | Suspected owner |
|---|---|---|---|---|---|---|
| env-01 | — (env) | System lock screen | `adb -s emulator-5558 shell dumpsys window \| grep isKeyguardShowing`; `ui.py shot` | QA emulator unlocked and on the launcher (or no lock screen) | Keyguard with PIN pad; `mCurrentFocus=NotificationShade` for the whole session (`00-initial-s.png`, `01-after-dismiss-s.png`). The AVD name is SignerQA2, not SignerQA3 | QA environment / orchestrator: unlock by hand, or provision the QA AVDs without a screen lock |

## Static pre-check (code read against iOS, not device-verified; not counted)

Done while waiting, so that the device run can go straight to the behaviour.
- `feature/activity`: list grouping (`ActivityModels.kt:60-75`, device zone, days and rows newest first), row content
  ("summary · wallet", time, 2-line title; `ActivityScreen.kt:200-240`), detail section order and rows
  (`ActivityDetailScreen.kt:131-226`), essentials (`core/model/.../ActivityEssential` = `ActivityTab.swift:216-238`),
  fresh re-review with case-insensitive fingerprint check and non-empty fields
  (`DefaultSignerStore.kt:840-850` = `AppStore.swift:581-591`), Output (Show QR only with frames, Export and Signature
  Payload only with text; `ActivityDetailScreen.kt:316-372`), retention note with `%d` = 25, export name
  `bittool-signer-signature.txt` / `text/plain`, "Clear" only while records exist, confirmation dropped on a
  `securityEpoch` change (`ActivityViewModel.kt:62-106`), unreadable state with no Clear (DECISIONS §10).
- Retention rules (`SigningReceipt.kt:173-183`): `take(100)`, payloads dropped from index 25, then oldest-with-payload
  shed while the encoded file is over 2 MiB. Same as `AppStore.swift:554-569`. Records survive a wallet deletion and show
  "Deleted wallet" in both the row and the detail (`ActivityModels.kt:18-31`; the row deliberately differs from iOS's
  bare "Wallet", per spec §12.2).
- No discrepancy with `ActivityTab.swift` found in this read. One design-system-wide difference: day headers are
  accent-coloured (`ActivityUi.kt:167-179`, `GroupedSection` §9.5), where the iOS reference has grey headers
  (`build/ios-reference/light-30-activity.png`). It is a deliberate design-system choice, so it is not filed.
- Reaching the unreadable state on device: `--lose-keys` deletes only the wallet keys and keeps
  `FixtureKeys/journal.key` (`FixtureDeviceKeyStore.kt:150-160`), so it does **not** make the journal unreadable. Use
  `run-as com.bittool.signer.debug` to delete `cache/ui-tests/<run>/FixtureKeys/journal.key` or to corrupt the
  `receipts.bin` of the run instead.

## Verified working

Nothing on the device this round.

## Not tested, and why

All of it, because emulator-5558 stayed behind its PIN keyguard (env-01):
- Wallet creation from the BIP39 reference phrase. Signing EVM personal_sign, EIP-712, EVM tx (EIP-1559 / legacy),
  BTC PSBT, TRON, Solana (the vectors are ready in `/sdcard/Download/qa/` on the device).
- The Activity tour: grouping, rows, detail, re-review and fingerprint match, Signed Request, response QR sheet, export
  through the picker, raw payloads, retention past 25, "Deleted wallet" after deleting the wallet, Clear Activity,
  the unreadable-journal state.
- Re-verification of round-1 sign-02 … sign-18.
- Settings: all pages, zh-Hans / ja / ar, font scale 2.0.
