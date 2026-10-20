# QA round 1: Wallets tour

Device: emulator-5556 (sdk_gphone64_arm64, API 31, 1080×2220, 3-button nav). Build: the installed `com.bittool.signer.debug`
(main at a0797c5; other agents were committing backlog fixes during the tour, so line numbers below are from HEAD and
may drift by a few lines). Screenshots: `build/qa/round1/wallets/` (`<name>.png`, with a downscaled `<name>-s.png`).
iOS reference: `build/ios-reference/` (light and dark). It filled in partway through the tour, and I compared against it.

## Summary

- Wallets tab, wallet detail, Addresses (paging to #81+, copy, long-press menu, per-address QR sheet), Coin Level for
  every chain/profile/mode, Wallet Level (4-frame animated QR, pause, counter, brightness, payload JSON, compatible
  wallets), rename, change password, reset password from the phrase, reveal and re-conceal of the recovery phrase,
  delete with confirmations, and the Repair path all work and match iOS for content, copy, order and engine output
  (addresses, paths, byte counts and Format lines agree with wallets-pairing.md §2.2 / §7.3).
- App Lock works in the harness (cold-launch lock, password fallback, back-off, 120 s grace, lock over sheet/dialog with state
  kept, Back on lock, any wallet's password unlocks, Settings toggle, delete pre-check footer, signing still asks for the
  password). Under FLAG_SECURE the Recents thumbnail is black.
- **App Lock was not verified with the production Keystore policy or with a real fingerprint.** I did not set a device
  screen-lock PIN or enroll a fingerprint. Both are OS security-settings changes that I may not make on my own. At the
  start I set PIN 1234 with `locksettings set-pin`, then removed it again with `locksettings clear --old 1234`. The device
  is back to its original state ("lockscreen disabled"). Without a screen lock, the production build refuses to create
  a wallet (wallets-04), so the production App Lock path is still not verified.
- One security item is still open: B1, typed passwords exposed in plain text to accessibility. There are 4 visible
  defects: the dark-mode address sheet card, the TRON label clipped at 1.3× and in CJK, the late screen-lock failure,
  and recovery-phrase focus loss. The rest are polish items.
- Environment: the host was overloaded the whole time (load average 15–73 on 10 cores). The guest showed ANRs in
  system_server, System UI, GMS and this app, and system_server restarted once (Watchdog, about 08:16). Latency and jank
  numbers are therefore meaningless: QR loads took up to 14 s, and Repair took about 19 min before it completed. I report
  no performance findings. The ANR trace of this app (06:45) shows an idle main thread, which points to CPU starvation
  and not an app hang.

## Issues

| ID | Sev | Screen | Steps | Expected (iOS / spec) | Actual (screenshot) | Suspected owner, file:line |
|---|---|---|---|---|---|---|
| wallets-01 | P0 | Every password field (Change Password, Recovery Phrase, Delete dialog, Repair, lock screen) | Type a password, then run `uiautomator dump` | The password is not readable by accessibility services (gaps G5) | The EditText exposes the typed text in plain text (e.g. `'Wrong-Password1!'`, `'Public-Test-Password1!'`). This is backlog **B1**, still open in this build. Dumps taken during w52 and L05. | core:ui `PasswordField` (backlog B1) |
| wallets-02 | P2 | Address detail sheet, dark | Dark theme → Addresses → tap a row | The address + derivation-path card is visible on the sheet (`AddressesView.swift:187` `.cardBackground()`, ref `dark-18-address-detail.png`) | The card is invisible: card and sheet are both `#1C1C1E` (sampled pixels identical). `d07-address-sheet-dark.png` | feature:wallets `addresses/AddressDetailSheet.kt:52`: `SignerTheme.colors` is read outside the `SignerBottomSheet` content (base palette, not the elevated one) and used at `:89`. Read the colors inside the sheet lambda. |
| wallets-03 | P2 | Pair TRON (Coin Level) | Font scale 1.3 (en), or the `ja` locale at 1.0 → Pair → TRON | "Address index" shown in full (`ConnectView.swift:493-497`: `lineLimit(1)` with `layoutPriority(1)`, so the label wins) | Clipped without an ellipsis: "Address" (1.3×) and "アドレスインデック" (ja 1.0×). Long locales (de, ru, …) are likely affected too. `f22-tron-1.3.png`, `j03-tron-ja.png` | core:ui `pairing/AddressIndexControl.kt:195` (`maxLines = 1` + `weight(1f)` beside a field of `76/116 dp × fontScale` at `:131` plus the stepper). Let the label take 2 lines or stack below a width threshold, not only at `isAccessibilityTextSize`. |
| wallets-04 | P2 | Setup on a device without a screen lock (production, non-harness) | Fresh install, no screen lock → Import → 12 words → password ×2 → Import Wallet | Tell the user before any secret is typed that a screen lock is required, with a way out (DECISIONS §4 lists "screen-lock requirement" as Android-only text) | Only after phrase entry, password entry and the KDF does it fail with "Set a screen lock (PIN, pattern or password) before creating a wallet…". No screenshot (FLAG_SECURE); text read from the dump. | feature:setup has no `isDeviceSecure` precheck. The only gate is core:crypto `vault/DefaultWalletVault.kt:108`. Expose `DeviceKeyStore.isDeviceSecure()` to the setup and Welcome entry points. Related: B7 wording. |
| wallets-05 | P2 | Recovery Phrase (locked state) | Detail → Show Recovery Phrase → wrong password → Continue | The field keeps focus and the keyboard stays up for a retry (`WalletSecuritySheets.swift:99-115`: focus is cleared only on success) | Error shown, but focus jumps to the close button (`recovery.close` focused) and the IME closes, so the user has to tap the field again. `w57-recovery-wrong.png` | feature:setup `reveal/RecoveryPhraseScreen.kt:175` (`enabled = !state.busy` drops focus while unlocking). Keep the field enabled but read-only, or call `focus.requestFocus()` when the error arrives. The lock screen does this correctly (L05). |
| wallets-06 | P3 | Coin/Wallet Level QR, address sheet | Open any pairing QR | Android-only control (wallets-pairing §13.5); it should sit cleanly on the plate | The "Normal" capsule sticks out 8 dp past the plate's end edge. On Wallet Level (full-width plate) it lands in the 20 dp gutter about 12 dp from the screen edge. In dark mode it shows as a white blob on black. `w33-wallet-level-top.png`, `d05-wallet-level-dark.png`, `r07-wallet-level-ar.png` | feature:wallets `common/WalletComponents.kt:274` (`offset(x = 8.dp, …)`). Inset it inside the plate's corner margin, or place it under the plate next to the controls. |
| wallets-07 | P3 | Pairing QR (ar, ja) | `--locale ar` / `ja` → Pair | Translated text | "Normal" / "Bright" / "Screen brightness" stay English. `r04-connect-btc-ar.png`, `j03-tron-ja.png` | core:l10n `values/strings_wallets.xml:11-15` (backlog B20) |
| wallets-08 | P3 | Wallet detail header | Open a wallet | Name / fingerprint / "Added" tightly stacked (`WalletDetailView.swift:137` `VStack(spacing: 4)`, ref `light-11-wallet-detail.png`) | A 48 dp-tall fingerprint row roughly triples the gaps, so the header looks loose. `w03-detail-top.png` | feature:wallets `detail/WalletDetailScreen.kt:269` (`heightIn(min = minTouchTarget)`). Enlarge the touch target without adding layout height. |
| wallets-09 | P3 | ProfileMenu (Pair BTC, Addresses), RTL | `--locale ar` → Pair Bitcoin | "BIP84 · bc1q…" with the ellipsis at the end | "…BIP84 · bc1q". `r04-connect-btc-ar.png` | core:ui `pairing/ProfileMenu.kt` (backlog B5). HEAD now has `contentDirection()` at `:91`, so re-verify on the next build. |
| wallets-10 | P3 | Change Password / Delete / Repair / Recovery Phrase | Look at the nav icon and its TalkBack label | iOS: a text button "Cancel" on every sheet, and "Done" once the phrase is revealed (`WalletSecuritySheets.swift:70,169,248,327`) | The X is labelled "Close" on three pages and "Cancel" on the fourth. After reveal the X stays but is labelled "Done". `w51-change-password.png`, `w58-recovery-revealed.png` | feature:wallets `security/SecurityPage.kt:74` (`NavIcon.Close` default label). Use the iOS strings and the same treatment on all four. |
| wallets-11 | P3 | TRON index field | Tap the index field | Digits-only pad (`ConnectView.swift:499` `.numberPad`) | Gboard number keyboard with `-`, `,`, `.` and space. The input is filtered, but the keys are misleading. `w27b-tron-ime.png` | core:ui `pairing/AddressIndexControl.kt:144`: use `KeyboardType.NumberPassword` (digits pad, no learning), which does not mask the input on a `BasicTextField`. |
| wallets-12 | P3 | Wallets tab after unlock; detail after rename | Unlock App Lock (or save Rename) with the IME/hardware Enter key | Focus is not left on an unrelated control | Focus ring on "Scan" with its tooltip pinned until the next interaction (`L06-unlocked.png`); focus ring on Back after Rename (`w50-rename-done.png`). Reproduced with adb key events (non-touch mode); affects physical-keyboard users. | core:security `window/DefaultLockWindowController.kt` `hide()` (`:149`) and the dialog dismissals: clear focus in the activity window after the overlay is removed. Tooltip: feature:wallets `common/IconAction.kt:31`. |
| wallets-13 | P3 | Addresses › Solana | Addresses → Solana | Rows aligned | Address columns start at 3 different x positions (labels Standard/Trust/Legacy). iOS has the same problem (`minWidth 34`), so this is polish, not parity. `w45-addresses-sol.png` | feature:wallets `addresses/AddressesScreen.kt:281`: use a fixed label column for Solana. |
| wallets-14 | P3 | Wallet detail › Security / Rename | Scroll to the bottom | iOS renders these as accent-tinted action buttons without chevrons (ref `light-12-wallet-detail-bottom.png`) | Label-coloured rows; two have chevrons and Rename has none, so the styling within one card is inconsistent. `w05-detail-bottom.png` | feature:wallets `detail/WalletDetailScreen.kt:347-365` (`NavRow`). Optional; product call. |
| wallets-15 | P3 | Security pages header | Open Change Password | The fingerprint string matches the list and detail ("Fingerprint 73C5DA0A") | `WalletIdentityHeader` always wraps the value in FSI/PDI, even in LTR (`'Fingerprint ⁨73C5DA0A⁩'`). `WalletsScreen.fingerprintText` isolates only in RTL. Invisible on screen, but inconsistent text for e2e matching. | core:ui `wallet/Wallets.kt:141`: isolate only in RTL, as `feature/wallets/list/WalletsScreen.kt:357-360` does. |

Counts: P0 1, P1 0, P2 4, P3 10.

## Verified working (no need to re-test)

- **Wallets tab**: cards in vault order with avatar, name, "Fingerprint XXXXXXXX", the 7-logo stack and Pair/Addresses.
  "Add Wallet" menu (Create / Import). Scan icon. Footer. Large title. A broken wallet shows "Fingerprint Setup required"
  and "Repair wallet data" and keeps its vault name (R01). Reference shots: light, dark (d01), 2.0× (icon-only actions,
  f01), ar (mirrored chevrons, Latin digits, r01).
- **Detail**: every section and footer string in iOS order. Fingerprint copy shows the "Copied" toast (API 31, so no system
  toast). Disabled when the wallet is not ready. "Added Oct 21, 2026" (ja/ar localized dates). The Repair-needed
  callout and Repair page (wrong password error; correct password rebuilt the sidecar, wallet ready again). Pops after
  delete.
- **Rename**: prefilled; Save disabled for empty/whitespace; 41 chars gives "Unable to Rename / Choose a wallet name of
  1–40 characters."; trims; IME action saves; avatar initial updates; works on a wallet without a sidecar (same as iOS).
- **Change Password**: wrong current password shows "The password is incorrect." with fields kept; strength bar (3
  segments at 15 chars, 4 at 22 with 4 classes); checklist; busy state disables the fields and shows a spinner; success
  pops and shows the "Password Changed" toast; Back = cancel.
- **Reset password from the phrase**: importing an existing phrase gives "Reset Password" (iOS copy, identity header,
  "Only the password changes…"), then "Password Reset" done, and the new password works.
- **Recovery Phrase**: auto-focus; wrong password error; correct password gives the concealed panel; Tap to Reveal;
  backgrounding discards the phrase and returns to the password form; Cancel/Done.
- **Delete**: toggle gates the button; dialog copy; Delete with an empty field gives "Enter this wallet's password.";
  wrong password errors with the wallet intact; Back closes the dialog and the field is empty on reopen; correct
  password deletes and the list updates. With App Lock on, the footer reads "Deleting asks for biometric confirmation,
  then …" and a failed biometric falls through to the password dialog. The dialog window is FLAG_SECURE (black in w63).
- **Addresses**: BTC native #0–#2 match the BIP84 test vectors; paging works past #81 with no duplicates; profile menu;
  EVM/TRON/Solana headers and footers; Solana's three presets; row tap opens the sheet ("Bitcoin address #80", "Trust
  account"); Done and Back close it; long press offers Copy Address (no toast) / Show QR Code.
- **Coin Level**: BTC single native/taproot/nested/legacy in Account XPUB and Address modes; multisig native/nested
  (cosigner path, no address, coordinator copy, no Export picker); EVM wallet/address; Solana standard/trust/legacy ×
  wallet/address; TRON. All addresses, paths and Format lines (`ur:crypto-account · CBOR · 170/172 bytes`,
  `crypto-hdkey 170`, `crypto-multi-accounts 156/154/152`, `Plain text · 42/62/44/34 bytes`) match §2.2. Fingerprint note
  in the JSON payload. Payload disclosure, Copy Payload, "Copied", Copy QR Data toast, copy-address check icon, Works-with
  lists per selection (BTC address gives BlueWallet + Electrum watch-only; multisig gives the coordinator list), Submit a
  Wallet page and back (scroll kept). Per-chain options persist across chain and level switches.
- **TRON index**: stepper (− disabled at 0, + disabled at max, long-press auto-repeat 7→29); typing 99999999999 clamps
  to 2147483647 and gives a valid QR/address (`TVapZk5c…`); "007" becomes 7; empty then Done resets; IME Done hides the
  keyboard; the field widens past 5 digits.
- **Wallet Level**: opens from the card's "Pair" and from the detail row; prefetched (instant for wallet 2); 4 frames
  with counter, progress, pause/play (stops at 1/4); 9 verify lines in engine order with Solana/BTC account names;
  "bittool-signer-wallet v1 · ur:bytes (canonical CBOR) · 1561 bytes"; Payload (JSON) with ZWSP wrapping; Works with
  BitTool Wallet (iOS); closing info callout. Brightness: Bright sets `sbrt=1.0`, which resets on leaving.
  KEEP_SCREEN_ON is set while a QR shows.
- **2.0× / 1.3×**: chain picker hides names, ProfileMenu and TRON stack, segmented controls fit (content-weighted
  widths), sheets and security pages scroll. **RTL (ar)**: mirrored layout, LTR addresses/paths/counters, isolated
  fingerprints. **CJK (ja)**: everything fits except wallets-03.
- **App Lock (harness run, fixture keys, failing fixture biometric)**: cold launch is locked with the auto-prompt; error
  callout; "Use a Wallet Password" gives a picker with names only and a password field; 8 wrong passwords then "Too many
  unsuccessful attempts. Try again in 11 seconds." (2^(n−4) back-off); field cleared and refocused after each failure;
  a 40 s background stays unlocked and keeps the page; 128 s locks; the lock covers an open bottom sheet and an open
  Rename dialog, and after unlock both are intact (Rename text kept); Back on the lock screen sends the task to the back
  and the app stays locked on return; the second wallet's password unlocks; Settings: turning off needs no biometric,
  turning on with a failing biometric shows the error and stays off; after unlocking, the Authorize sheet still asks
  for the wallet password (EVM eip1559 vector). With App Lock on and a fresh `reset` run, App Lock is disabled because
  the wallet count is 0 (iOS parity).
- **Recents / FLAG_SECURE**: production and non-capture runs set `fl=SECURE`, screenshots are black, and the Recents
  thumbnail is black, including with the address sheet open (p02, L09). In capture-allowed debug runs the thumbnail
  shows content (by design, §15.8).

## Not tested, and why

- **App Lock with the production Keystore policy (`setUnlockedDeviceRequired`) and a real fingerprint**: this needs a
  device screen lock and an enrolled fingerprint. Both are OS security-settings changes I may not make on my own (see
  Summary). Without a screen lock no real wallet can be created (wallets-04), so this whole path is unverified. To
  finish it, someone with authority should run on emulator-5556:
  `adb -s emulator-5556 shell locksettings set-pin 1234`, enroll a fingerprint under Settings › Security › Fingerprint
  (`adb -s emulator-5556 emu finger touch 1`), create a wallet through the UI in a non-harness launch, then repeat the
  App Lock checks above, including the real BiometricPrompt "Use a Wallet Password" negative button and
  cancel-on-background.
- **Biometric before delete with a real prompt (cancel aborts)**: same reason. Only the fixture failure path was tested.
- **Privacy cover while paused (not stopped), and capture/recording cover**: with FLAG_SECURE on, screenshots are black.
  The recording callback is API 35 and this device runs 31.
- **Predictive-back animation**: 3-button navigation. Back keyevents were tested.
- **Connect/Addresses engine error states, busy-state back consumption**: not reachable without a fault injector, and the
  timing could not be controlled on this host.
- **Performance and jank**: not measurable here (see Summary). One unexplained event, probably from input starvation: a
  fast `input swipe` on the wallet detail was delivered as a tap on the Solana row (w04).
- **Tooling defects found (not app bugs)**: `tools/qa/ui.py logcat` crashes (`adb()` returns bytes, the regex expects
  str). `ui.py dump` silently re-reads the previous `/sdcard/qa-ui.xml` when `uiautomator dump` fails, and it does not
  see AlertDialog windows; use `uiautomator dump --windows`. Bottom sheets and dialogs expose no test tags (B4), so
  `address.done` and `delete.password` cannot be tapped by tag.
- **Device state**: font scale is back to 1.0. adbd was rooted to read ANR traces and is unrooted again. No screen lock
  is set, as at the start. One harness sidecar file was moved and rebuilt by Repair inside the run directory of the
  `11111111-…` run only.
