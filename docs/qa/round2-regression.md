# QA round 2: tour "regression" (round-1 setup and wallets fixes, then an end-to-end smoke)

Device: emulator-5554 (sdk_gphone64_arm64, API 31, 1080×2220 at 440 dpi, about 393×807 dp, 3-button nav, Gboard).
Build: the installed `com.bittool.signer.debug` (lastUpdateTime 14:23, main with the QA round-1 and backlog fixes).
Driven with `tools/qa/ui.py` (harness runs) and a package-filtered dump/tap helper (see "Environment"). Screenshots:
`build/qa/round2/regression/NNN-name.png` (each one has a downscaled `NNN-name-s.png`). iOS references:
`build/ios-reference/`. iOS paths are relative to `BitSigner/BitToolSigner/`. Font scale is back to 1.0.

## Summary

Of the 30 round-1 findings, **24 are fixed on the device**. They held in light, dark, font 1.3 and 2.0, Arabic and
Japanese. Two are partly fixed, with new instances left over (wallets-12, wallets-15). Four are still open. Two of
them are expected: setup-06 and wallets-07 are the untranslated Android-only strings (B20). setup-15 cannot be fixed on
this device: the fix uses `LineBreak.Heading`, which only works on API 33 and later. wallets-14 was optional and is
unchanged. I found no regression of a round-1 fix.

All the important fixes are confirmed on the device:

- **setup-01 / wallets-01:** passwords are bullets with `password=true` in the accessibility tree.
- **setup-02:** with the keyboard up, the focused field and the caret line stay above the bar. This holds on the
  editor, the passphrase pair, the password checklist, at 1.3 and at 2.0.
- **wallets-04:** "Screen Lock Required" appears before anything secret is typed (production path, no harness).
- **wallets-05:** focus is kept after a wrong password.
- **setup-08:** the action is held during the duplicate check.

The end-to-end smoke passed with no crash and no ANR for `com.bittool.signer.debug` in about 70 minutes of logcat.
The flow was first run, onboarding, create (12 words and a passphrase), full backup and verify, password, Done,
wallets, pair (each chain and Wallet Level), addresses, rename, change password, reveal phrase, App Lock unlock, Scan
(paste EIP-1559 UR and a BIP84 PSBT, sign), Activity, Settings, and delete wallet. The EIP-1559 signature in the
response payload equals the published vector (`8bfb3928…a300400`). The three created or imported wallets' fingerprints
match my offline BIP32 computation (AB029ACB with passphrase "TREZOR", F7FB19CD, 73C5DA0A). sign-01 (Activity) is
fixed: receipts are written and listed.

What still keeps it below flagship quality:

- **IME suggestions on the phrase editor:** Gboard shows its suggestion strip and composing underline on the
  recovery-phrase editor, one tap from non-BIP39 words such as "abandoned" (regression-01, P2).
- **Focus rings in keyboard mode:** a ring is still left on unrelated controls in two places (B30 class).
- **Detail page jump:** the wallet detail page jumps after the Rename dialog.
- **Polish:** the rest are polish items.

Counts (new and carried-over open issues together): **P0 0 · P1 0 · P2 2 · P3 13**. New this round: P2 1, P3 9.
Carried over unfixed from round 1: P2 1 (setup-06), P3 3 (wallets-07, setup-15, wallets-14).

## Round-1 fix verification

| Round-1 ID | Status | Evidence (light / dark / 1.3 / 2.0 / ar / ja as applicable) |
|---|---|---|
| setup-01 | **fixed** | Passphrase and wallet password dump as `'••••••'` with `password=true` (`005-passphrase-typed`, `017-pw-typing`); also the lock screen, Change Password, Recovery Phrase and the Delete dialog |
| setup-02 | **fixed** | Confirm passphrase above the bar (`006-passphrase-next`). The checklist stays visible while typing (`017-pw-typing`, `018-pw-both`, at 2.0 `070-f2-password-typing`). The import editor keeps all lines visible in dark (`050-dark-import-ab`), with the error Callout (`061-light-import-error`) and at 1.3 (`121-f13-import-typing`) |
| setup-03 | **fixed** | Light chips are white capsules on the grey bar (`060-light-chips`) |
| setup-04 | **fixed** | IME Next on "BIP39 passphrase" focuses "Confirm passphrase" and the keyboard stays up. The focused node is `Confirm passphrase` and the typed text lands there (`006-passphrase-next`) |
| setup-05 | **fixed** | At 2.0 the status is stacked under the label in two lines, whole: "12–24 words · any BIP39 language" (`063-f2-import-empty`) |
| setup-06 | **not fixed** (B20) | ja onboarding page 3: body and the "no internet permission" point are still English (`079-ja-onboarding-3`) |
| setup-07 | **fixed** | ar: the words and caret start at the left; the placeholder and chips are mirrored (`076-ar-import-typing`) |
| setup-08 | **fixed** (functional) | The Reset page appears about 0.5 s after the step (`056-dup-1` → `056-dup-2`). The first frame still shows "Set a Password / Import Wallet" (disabled), which is iOS parity (`.task`). The hold itself could not be timed (the check answered too fast) |
| setup-09 | **fixed** | While saving, the back arrow is shown disabled and the title stays in place (`019-creating-busy`) |
| setup-10 | **fixed** | The last chip fades out, with a gap before "Done" (`050-dark-import-ab`, `060-light-chips`, ar `076`) |
| setup-11 | **fixed** | The seal glyph fills the disc like iOS (`020-done` vs `light-07-imported.png`) |
| setup-12 | **fixed** | Button glyphs (+, download, QR) scale at 2.0 (`064-f2-welcome`, `071-f2-done`). The Welcome feature-row glyphs do not (regression-07) |
| setup-13 | **fixed** | The whole Welcome page fits above the bar in light and dark (`001-welcome-light`, `048-dark-welcome`) |
| setup-14 | **fixed** | At 2.0 the board is centred on the first blank when it appears (`069-f2-verify`) |
| setup-15 | **not fixed on this device** | API 31: "追加しま / す。" and "ネット / ワーク" still break mid-word (`080-ja-create`). The fix needs API 33+. The SwitchRow subtitle is backlog B31 (open) |
| wallets-01 | **fixed** | Same as setup-01, on every password field I reached |
| wallets-02 | **fixed** | The dark address sheet card is visible on the sheet (`047-dark-address-sheet`) |
| wallets-03 | **fixed** | "Address index" stacks above the field instead of clipping: en 1.3 (`037-tron-f13`), ja 1.0 (`077-ja-pair-tron`). ar fits on one line (`074-ar-pair-tron`) |
| wallets-04 | **fixed** | Production path (no harness, no device screen lock). Create/Import opens "Screen Lock Required": "Set a screen lock (PIN, pattern or password) to create or use a wallet. The device key requires screen lock protection." "Open Settings" opens `ChooseLockGeneric`, Back returns (dump only, FLAG_SECURE) |
| wallets-05 | **fixed** | After a wrong password the field stays focused, is cleared and the keyboard stays up (`030-recovery-wrong`) |
| wallets-06 | **fixed** | The capsule sits inside the plate's end corner: Coin Level (`033-pair-tron`), Wallet Level (`041-pair-wallet-level`), dark (`105-dark-wallet-level`, `047`), ar (`073-ar-pair-btc`) |
| wallets-07 | **not fixed** (B20) | "Normal" / "Screen brightness" are still English in ar and ja (`073-ar-pair-btc`, `077-ja-pair-tron`) |
| wallets-08 | **fixed** | The header is tight like `light-11-wallet-detail.png` (`022-detail-top`). Side effect: regression-09 |
| wallets-09 | **fixed** | ar: "BIP84 · bc1q…", with the ellipsis at the end (`073-ar-pair-btc`) |
| wallets-10 | **fixed** | The cross reads "Cancel" on Change Password, Delete and Recovery Phrase, and "Done" after the reveal. Repair was not reachable (see "Not tested") |
| wallets-11 | **fixed** | The digits-only pad (`034-tron-ime`) |
| wallets-12 | **partly fixed** | Unlock with Enter: no ring on Scan (`083-unlocked-enter`). Rename saved with Enter: no ring (`025-rename-done`). New instances remain in keyboard mode: regression-02 and regression-03 (rest is B30) |
| wallets-13 | **fixed** | The Solana addresses start at one x position (x = 267 px for Standard/Trust/Legacy, `044-addresses-sol`) |
| wallets-14 | **unchanged** (optional, product call) | Security rows are still label-coloured, with chevrons on two of them (`023-detail-bottom` vs `light-12-wallet-detail-bottom.png`) |
| wallets-15 | **partly fixed** | WalletIdentityHeader is now `Fingerprint AB029ACB` with no FSI (Change Password dump). The review's Signing Wallet row still isolates in LTR: regression-06 |

Incidentally confirmed fixed: sign-01 (receipts written, `094-activity`), sign-06 (paste error visible above the
keyboard, `115-paste-garbage`), sign-10 (sheets and dialogs capturable: `045`, `088`, `085`, `103`). B4 and B26
(dialog tags `delete.password`, `rename.save`, `address.done`). B16 (Authorize sheet: Sign stays above the keyboard,
`088-authorize`). B21 (lost key on re-import: "This Wallet Can't Be Unlocked" with "Open Wallet", `109-lostkey-import`).
B9 and B22 (Delete shows the lost-key page, "Remove This Wallet?" removes it, `112`, `113`, `114`). B24 (`--lose-keys`).

## Issues

| ID | Sev | Screen | Steps | Expected (iOS / spec) | Actual (screenshot) | Suspected owner (file:line) |
|---|---|---|---|---|---|---|
| regression-01 | P2 | Import step 1, recovery-phrase editor (all languages) | Import → type "…abandon ab", or a complete word | No keyboard suggestions or autocorrect on phrase fields (DECISIONS §6 "no autocorrect/suggestions"). iOS `.autocorrectionDisabled()` hides QuickType (`Features/Setup/RecoveryPhraseViews.swift:196-198`) | Gboard shows its suggestion strip and underlines the word being composed. It offers "abandoned" and "abandonment" (not BIP39 words) one tap away, and a clipboard chip ("73C5DA0A"). Incognito mode stops learning, but the suggestions still show. `050-dark-import-ab`, `060-light-chips`, `061-light-import-error`, `121-f13-import-typing`. Already visible in round 1 (`round1/setup/82-dark-import-typing.png`), not flagged then | feature:setup `phrase/RecoveryPhraseInput.kt:124-129`: `KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Text)` does not set `TYPE_TEXT_FLAG_NO_SUGGESTIONS`. Use a no-suggestions input type (e.g. `KeyboardType.Password` without masking, as wallets-11 did with `NumberPassword`, or add the flag through the platform text-input interceptor) |
| regression-02 | P3 | Recovery Phrase (wallet detail › Show Recovery Phrase) | Type the correct password, press Enter on a hardware keyboard or adb `keyevent 66` | Nothing focused after the unlock (`Features/Wallets/WalletSecuritySheets.swift:114` `passwordFocused = false`) | A focus ring is left on the Cancel/Done cross (`031-recovery-concealed`). Only in keyboard (non-touch) mode | feature:setup `reveal/RecoveryPhraseScreen.kt:164-180`: the focused password form leaves composition and the focus moves to the next control. Hold or clear the window focus as `WindowFocusReturn` does (B30) |
| regression-03 | P3 | Pair TRON, address index | Type an index, press Enter on a hardware keyboard | Focus leaves the field and nothing else is focused (`Features/Wallets/ConnectView.swift:498-532`) | A focus ring lands on the Back arrow (`035-tron-12`). The soft-keyboard ✓ leaves no ring (`118-tron-softdone`) | core:ui `pairing/AddressIndexControl.kt:161`: `focusManager.clearFocus()` gives the focus to the first control in keyboard mode (see commit 0ff434e). Same class as wallets-12 / B30 |
| regression-04 | P3 | Wallet detail | Scroll to the bottom → Rename Wallet → Cancel or Save | The page stays where it was | The list jumps about 128 dp: Rename moves from y=1249 to 1602 px and Delete Wallet goes under the tab bar (`023-detail-bottom` → `025-rename-done`, `116-detail-after-rename-cancel`). Reproduced twice | feature:wallets `detail/WalletDetailScreen.kt:125` (RenameWalletDialog over the LazyColumn). Likely the dialog's IME resizes the activity viewport (`app/src/main/AndroidManifest.xml:49` `adjustResize`) and the list does not restore its end position |
| regression-05 | P3 | Scan › Paste Request | Type "garbage-request" → Review Request (keyboard up) | Error under the editor, with the typed text still visible (`Features/Sign/ScanTab.swift:276-290`) | The error is brought into view (sign-06 fixed), but the editor's first line, with the text and caret, scrolls under the sheet header. The visible part of the editor is empty (`115-paste-garbage`) | feature:sign `home/PasteRequestSheet.kt:141` (`RevealedErrorCallout` bring-into-view). Keep the caret line in view too, or hide the IME when the error appears |
| regression-06 | P3 | Review Request › Signing Wallet | Paste any request, open the review, read the accessibility tree | `Fingerprint 73C5DA0A`, like the list and the security pages (`Features/Sign/RequestReviewView.swift:783`) | `'Fingerprint ⁨73C5DA0A⁩'`, isolated in LTR (dump of `087-review`). This is the rest of wallets-15 | feature:sign `review/SigningWallets.kt:170`: `isolate(fingerprint)` → `isolateInRightToLeft(fingerprint)` |
| regression-07 | P3 | Welcome, onboarding pages | Font scale 2.0 → Welcome; Create → page 1 | Feature symbols scale with Dynamic Type (`Features/Onboarding/WelcomeView.swift:98-99` `.font(.title2)`) | Feature-row glyphs stay about 26 dp beside text at double size. Onboarding point glyphs are capped at 24 dp wide (`064-f2-welcome`, `065-f2-onb1`) | feature:onboarding `WelcomeScreen.kt:356` (`size(26.dp)`), `OnboardingPager.kt:215-220` (`Modifier.width(24.dp)` caps `FirstLineIcon`'s scaled size) |
| regression-08 | P3 | Create › Back Up Your Phrase | Font 2.0 → Create → reveal | Words readable without scrolling them through a slot | The pinned footer ("Security Tips" plus a two-line primary button) takes about 45 % of the height. The word grid shows one row (about 40 dp) at a time (`067-f2-backup`). iOS has the same footer structure (`Features/Setup/WalletSetupFlow.swift:295-307`), so this is polish, not parity. Same class as setup-14 and sign-04 | feature:setup `flow/PushedSteps.kt:105-125` (footer). At accessibility sizes, move "Security Tips" into the scroll content or make the footer scroll |
| regression-09 | P3 | Wallet detail header | Tap the "Added Oct 21, 2026" line | Only the fingerprint copies (`Features/Wallets/WalletDetailView.swift:141-153`) | Tapping the date copies the fingerprint and shows "Copied" (`120-tap-added`). The fingerprint's 48 dp touch bounds (575–707 px) overlap the date (677–727 px). Side effect of the wallets-08 fix | feature:wallets `detail/WalletDetailScreen.kt:275-285`. Keep the touch expansion off the next line (expand upwards, or give the date its own pointer region) |
| regression-10 | P3 | Every "Fingerprint …" line, Arabic | `--locale ar` → Wallets | The value keeps its monospaced face (iOS `.caption.monospaced()`) | The Latin value renders in the proportional fallback font after the Arabic word (`072-ar-wallets`). English is monospaced (`046-dark-wallets`) | core:designsystem `theme/SignerTypography.kt:121` (`monospaced()` applied to the whole sentence). The font fallback keeps the Arabic font for the run after the FSI isolate. Apply the mono family as a span on the value (feature:wallets `list/WalletsScreen.kt:291-292`, core:ui `wallet/Wallets.kt:146`) |
| regression-11 | P2 | Onboarding page 3 (20 non-English languages) | `--locale ja` → Create → page 3 | Fully translated | Still English (round-1 **setup-06**, `079-ja-onboarding-3`) | core:l10n `values/strings_onboarding.xml` (B20; `tools/l10n/android_strings.py` landed but no translations yet) |
| regression-12 | P3 | Pairing QR brightness capsule (ar, ja) | `--locale ar` / `ja` → Pair | Translated | "Normal" / "Screen brightness" are still English (round-1 **wallets-07**, `073-ar-pair-btc`, `077-ja-pair-tron`) | core:l10n `values/strings_wallets.xml` (B20) |
| regression-13 | P3 | Large titles and switch subtitles (ja, API < 33) | `--locale ja` → Create | Phrase-boundary breaks | "追加しま / す。" and "ネット / ワーク" (round-1 **setup-15**, `080-ja-create`). The fix only acts on API 33+, and the SwitchRow subtitle is B31 | core:designsystem `SwitchRow` (B31); feature:setup `SetupUi.kt` / `PushedSteps.kt` large titles (no pre-33 fallback) |
| regression-14 | P3 | Wallet detail › Security / Rename | Scroll to the bottom | iOS accent-tinted action rows without chevrons (`light-12-wallet-detail-bottom.png`) | Unchanged, as noted in round 1 (**wallets-14**, `023-detail-bottom`). Product call still open | feature:wallets `detail/WalletDetailScreen.kt` (NavRow security section) |

## Verified working (end-to-end smoke, this build)

- **First run → onboarding → create:** Welcome (light, dark, 2.0), 3 onboarding pages with Continue / Get Started,
  and Create step 1 (12 words, passphrase on/off, eye buttons). Backup: concealed, reveal, the grid adapts 2 or 3
  columns to word length like iOS `WordGridColumns`. Verify: 4 blanks, chips fill in order, Confirm. Password:
  strength meter, checklist, IME Next/Done. Create Wallet (busy state). Done with the identity card. Pair a Wallet / Done.
- **Import (dark run):** reference phrase via chips → "12 words · English" → 73C5DA0A. Unknown-word error
  ("“xyzzy” is not in the BIP39 word list"). Discard dialog. Re-import of an existing phrase → Reset Password page with
  the identity block.
- **Wallets:** card (name, fingerprint, 7 logos, Pair/Addresses), Add menu, reselecting the tab pops to the root.
  **Detail:** sections, fingerprint copy toast. **Pair:** Coin Level BTC (single/multi, ProfileMenu), EVM, TRON
  (stepper, typed index 12 → `m/44'/195'/0'/0/12`, "Plain text · 34 bytes"), Solana (3 presets), Wallet Level
  (animated QR, part 1 of 4). **Addresses:** BTC #0–#5, Solana rows, address sheet light/dark, Done.
- **Rename** with IME Enter; **Change Password** (IME Next chain; Done on the last field only hides the keyboard,
  like iOS `onSubmit { focus = nil }`; success pops). The new password then opens **Recovery Phrase** (wrong → error
  and focus kept; correct → concealed → Tap to Reveal shows the same 12 words and "TREZOR" under "BIP39 passphrase ·
  back up separately", as iOS `RecoveryPhraseViews.swift:20`).
- **App Lock (harness):** cold launch locked; "Use a Wallet Password"; unlock with Enter; no focus ring.
- **Scan:** Paste Request with the EIP-1559 UR typed in → review (Send 0.000000000000000001 ETH to 0x…dEaD, fee up to
  0.00063 ETH, signing wallet) → Authorize (sheet above the keyboard) → Signature Created. The `ur:eth-signature · CBOR
  · 89 bytes` payload signature equals the vector. BIP84 PSBT (base64) → review (0.0009 BTC to bc1qcr8t…, fee 0.0001
  BTC) → wrong password ("The password is incorrect.", field cleared, focus kept) → correct password with IME Enter →
  signed. Garbage text → "Unsupported signing QR format" visible with the keyboard up.
- **Activity:** both receipts listed by day; detail (date, network, wallet, fingerprint, request fingerprint).
- **Settings:** App Lock, Theme chooser (System/Light/Dark, switching live), Language, Networks, Compatibility, Fully
  Open Source, Contact Us, Terms of Use (v1.1), Version "1.0.0 (1)", "Network connections: None".
- **Delete wallet:** confirmation switch → password dialog (tags exposed) → Enter → the last wallet is gone and the
  app returns to Welcome. **Lost device key** (`--lose-keys`): re-import offers "Open Wallet", Delete shows the
  lost-key page, Remove works.
- **Locales and scales:** ar (mirrored bars, chips, stepper, menus, Latin digits) and ja (no clipping apart from
  regression-13). Font 1.3 (wallets, detail, import, TRON stacked) and 2.0 (welcome, onboarding, create, verify,
  password with checklist, done, import).
- **logcat:** no `FATAL EXCEPTION`, `ANR in com.bittool.signer…` or "Input dispatching timed out" for the app during
  the tour (14:27–15:38). The only app warnings are Compose `BasicSecureTextField: SettingNotFoundException:
  show_password`, which is framework noise on this image.

## Not tested, and why

- **setup-08 hold timing:** the duplicate check answered within about 0.5 s, so I could not catch "Import Wallet"
  while it was held.
- **setup-15 on API 33+:** this emulator is API 31 (the fix is API 33+).
- **wallets-10 Repair page:** reaching it needs a wallet with broken public data. The harness has no switch for that,
  and I may not edit files.
- **wallets-12 Add Wallet and address-row menus in keyboard mode:** not driven with D-pad/arrow keys. The fixer's
  Robolectric tests cover them, and the menus still have no resource ids (B29), which makes driving them by tag hard.
- **App Lock with the production Keystore policy and a real BiometricPrompt:** this needs a device screen lock and an
  enrolled fingerprint (OS security settings I may not change). Only the harness path and the wallets-04 notice on the
  production path were tested.
- **Recovery Cards sheet, Security Tips, recovery QR scanner, real camera scan, Import File:** not reopened this
  round (round 1 verified them). The virtual camera shows no QR code.
- **Predictive back** (3-button navigation), **TalkBack speech** (labels checked in the tree only), **process death**,
  **API 34+/35 screenshot and recording callbacks:** not available or not set up on this device.
- **Timing and jank:** not judged (shared host, load average 45–100).

## Environment notes

- **Another session used emulator-5554 during the tour.** `com.bittool.wallet.debug` was installed (14:35:33),
  launched from adb (14:35:57) and dumped with uiautomator (14:36–14:37). A "BitTool Wallet isn't responding" dialog of
  `com.bittool.wallet` was on screen at the start. I tapped only "Wait" on it (`000-initial`), brought the signer back
  with its launcher intent, and from then on checked `mFocusedApp` before every input.
- **Tooling defect (not an app bug):** `tools/qa/ui.py` `dump` and `tap` do not filter nodes by package. When the
  signer's window comes back with no hierarchy, or another app's window is listed, the dump shows the other app's
  nodes. That app uses the same tags (`onboarding.next`, `tab.scan`, `connect.walletVerify`). At 14:34–14:45 this
  produced two dumps of screens that were not on the display. `ui.py tap <tag>` would tap into the other app. Suggest
  restricting `find` to `package == PKG` unless asked otherwise.
- **Tooling defect:** `dumpsys window` lists the LAST ANR section first, so `grep -m1 mCurrentFocus` reports a stale
  window. Use `dumpsys window displays`.
- **Device state at the end:** font scale 1.0, no screen lock (unchanged), the signer on its Wallets tab
  (harness run 0422716f…).
