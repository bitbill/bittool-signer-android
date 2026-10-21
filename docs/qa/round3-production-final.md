# QA round 3: tour "production-final" (production Keystore policy, after the lost-key fixes)

Device: emulator-5556 (`adb emu avd name` = **SignerQA3**), API 31, 1080×2220, 3-button navigation. Build: the installed
`com.bittool.signer.debug` 1.0.0-debug, updated in place at 21:18:12 (data kept). Per the brief it is main with every
fix (0bdb276), including the in-app BIP39 keyboard, the lost-key recovery (L1-L4) and the translations. Tour window:
21:20 to 22:00. Host load average 1–3.

How the tour was run:
- The app was launched normally with `am start -n com.bittool.signer.debug/com.bittool.signer.MainActivity` and no
  harness extras. So the real `AndroidKeystoreDeviceKeyStore`, `KeystoreJournalCipher` and
  `AndroidBiometricAuthenticator` were in use.
- The window is `FLAG_SECURE`: `screencap` returns 0 bytes. All production evidence is therefore accessibility dumps in
  `build/qa/round3/production-final/`. `fNN-*.xml` files are full `uiautomator dump --windows`; `fNN-*.txt` files are
  `ui.py dump` output.
- Screenshots exist only for the two short **harness** checks at the end (`hNN-*`, a separate
  `cacheDir/ui-tests/<run>` store, labelled as such). The device was returned to a production launch afterwards
  (`f70-final-state.xml`).

OS state, unchanged by this tour:
- PIN set; device unlocked throughout (`isKeyguardShowing=false`). No fingerprint enrolled (`dumpsys fingerprint`
  count 0).
- No PIN entry, no Settings › Security, no `locksettings`, no fingerprint touches. Only emulator-5556 was used.

Test passwords:
- They were generated for this tour and are kept only in the session scratchpad
  (`/private/tmp/claude-501/-Users-tanguopeng-Documents-bitool-signer-android/1bc3e92b-171d-46e2-bf4f-c5850df58c91/scratchpad/creds.txt`).
  They appear nowhere in this report or in the evidence: password fields dump as `•••`.
- The recovery phrase used is the public BIP39 test vector ("abandon … about", 73C5DA0A). `f32-recovery-revealed.xml`
  contains those public words.

End state:
- One wallet, "My Wallet" (73C5DA0A, id `c7f03329…`). It was re-imported in this tour, and its password is P1 in the
  scratchpad file.
- App Lock is off. Activity is empty (cleared in this tour). `/sdcard/Download/bittool-signer-signature.txt` (the
  export) is left in place.

## Summary

**L1 is fixed on the device it was found on.**
- On the first launch after the update, the journal whose key the round-2 screen-lock removal had deleted was proven
  lost and removed. `receipts.bin` was gone and the `receipts.restarted` marker written at 21:20 (`f01-files-after-launch.txt`).
- No "Unable to Continue" appeared, at launch or later. That held across 7 cold launches, a background/foreground
  cycle, a password reset by re-import, two signatures, a wallet delete, a re-import from Welcome and Activity Clear.
  No dump contains "could not be read" or "could not be saved".
- Activity showed **"Activity Started Again"** with the full Android-only explanation and an OK button. The notice
  survived a cold restart until OK was tapped, then never came back (cold restart after OK). The marker file is
  removed on OK.
- A new signature (the EIP-1559 vector) wrote a new `BTSR 01` journal and is listed after a cold restart. So is a second
  signature made after deleting and re-importing the wallet.

**Every password-only flow that round 2 could not finish works with the production key policy:**
- reveal the phrase with the password;
- the Activity detail Output section, with Show QR and Export as File through the system picker. The file is
  byte-identical to the vector's response UR;
- delete with the password;
- re-import, both as an in-place password reset and from Welcome after a delete;
- rename;
- change the password, where the old password is refused and the new one accepted afterwards;
- Clear Activity with records, and Import File.

**App Lock cannot be enabled without a biometric, and the app does not offer it.** The switch is shown off and
disabled, with the iOS-equivalent footer "Set up fingerprint or face unlock in Android Settings…". Tapping it does
nothing (iOS `Features/Settings/SettingsTab.swift:24-31`). The App Lock password form itself could only be reached through the harness
(fixture biometrics). There, the form behaves as on iOS.

**L4 holds for healthy wallets.** Nowhere did a healthy wallet show "Repair", "Setup required" or "This Wallet Can't Be
Unlocked":
- the list card;
- the detail page, including after background/foreground;
- the Delete page, already on its first frame (B22);
- duplicate import, which offered Reset Password, not the lost-key route.

Counts: **P0 0 · P1 0 · P2 0 · P3 1.**

## Issues

| ID | Sev | Screen | Steps | Expected (iOS / spec) | Actual (evidence) | Suspected owner · file:line |
|---|---|---|---|---|---|---|
| production-final-01 | P3 | Wallet › Change Password (keyboard open) | Change Password › type the current password, the new password and its confirmation (keyboard stays up, focus in "Confirm new password") › look for "Change Password" | The action the person is about to take is visible, or within reach, while typing. Setup's Reset Password (the other "set a password" flow) keeps its action in a bar above the keyboard. iOS draws the action inline in the Form (`Features/Wallets/WalletSecuritySheets.swift:156-163`), so this is a polish and consistency point, not a parity break | The primary button stays at `[55,1400][1025,1543]` while the keyboard's touchable region starts at y=1403 (`f41a-cp-button-under-ime.xml`, `f41a-ime-insets.txt`). The button is entirely behind the IME. A tap at its reported centre landed on a key and typed a character into the confirmation, which turned "Passwords match" false and disabled the button (`f41-cp-wrong-current.xml`). The page does scroll (`f42-cp-scrolled-with-ime.xml`), and IME Done on the confirmation hides the keyboard (parity with iOS `.submitLabel(.done)`). So the action is never lost, only hidden | feature:wallets `security/SecurityPage.kt:89-99` (`imePadding()` + `verticalScroll`: only the focused field is brought into view), `security/ChangePasswordScreen.kt:98-103` (inline `PrimaryButton`). Bring the submit button into view with the last field (`BringIntoViewRequester` on IME show), or use the setup's bottom-bar pattern |

Seen, not counted:
- **Stale error on Change Password (iOS-shared).** After a wrong current password, "The password is incorrect." stays
  on the page after the person corrects the field, until the next submit (`f43`, then the `cp6` state before `f44`).
  iOS keeps `operation.error` the same way (`Features/Wallets/WalletSecuritySheets.swift:154-156`).
- **"Deleted wallet" on a re-imported wallet's record (owner question B17).** A record signed before a delete and
  re-import of the same phrase reads "Deleted wallet": the wallet id changed, the fingerprint did not
  (`f59-activity-after-reimport.xml`). This is iOS logic (`Features/Activity/ActivityTab.swift:139`).
- **Slow file import on the emulator.** Import File took about 10 s from the picker closing to the review. The Paste
  and Import tiles were disabled and a progress indicator showed meanwhile (`f69-import-file-review.xml`), so feedback
  was present. Timing is not judged on the emulator.
- **Log noise.** The `BasicSecureTextField … show_password` stack trace appears on every password field (production-r02,
  round 2). Choreographer reported 31–51 skipped frames during some page transitions. Software-rendered emulator, not
  judged.

## Verified working (production launch, no harness)

**L1: journal key loss recovered**
- First launch (`f01-launch.txt`): wallet list, no alert. On disk, `receipts.bin` (the old file, sha256 `a197b475…`,
  1837 B from 14:58) is removed and `receipts.restarted` (0 B) is written.
- Activity shows "Activity Started Again" with the message "The key that protected the earlier activity record is no
  longer on this device (Android deletes it when the screen lock is removed)…" and OK (`f02b`). The 21 translations
  use each language's own term for Activity (de "Verlauf neu begonnen", fr "Historique recommencé", ja "履歴…").
- After a cold restart before OK, the notice is still shown (`f03b`).
- After OK: "No Signatures Yet", and the marker is gone. After a cold restart it stays gone (`f04`, `f05`).
- Signing the EIP-1559 vector shows "Signature Created" with no save alert. `receipts.bin` is new (`BTSR 01…`, sha256
  `42eb788c…`). The row "Native transfer · 0.000000000000000001 ETH · My Wallet" is listed, also after a cold restart
  (`f15`–`f18b`).
- Damaged journal whose key is present (harness `--unreadable-journal`): "Unable to Continue — could not be read" once
  at launch, then "Activity Unavailable". It is not repeated on foreground. The journal is not discarded, so L1 stays
  limited to a proven-lost key (`h05-unreadable-launch.png`, `h06-unreadable-activity.png`, `h07`).

**Signing (password-only path)**
- The EIP-1559 vector (`core/data/src/androidTest/resources/vectors/signing-roundtrips.json`) was typed into Paste
  Request; the text was verified identical (360 chars).
- Review: Ethereum, Decoded on device, Send 0.000000000000000001 ETH, To `0x0000 … dEaD`, "Network fee up to 0.00063
  ETH", Signing Wallet "Fingerprint 73C5DA0A". There are **no FSI/PDI isolates** in the dump, so production-07 is fixed
  (`f13-review.xml`).
- Authorize sheet: "The password decrypts this wallet for this signature only."
- Payload JSON signature `8bfb3928…a300400` equals the vector exactly. It was identical again after the delete and
  re-import (`f16`, `f60c`).

**Activity detail**
- Header ("Signature created" pill). Date, Network, Wallet, Fingerprint 73C5DA0A, Request fingerprint, no-broadcast
  footer.
- Input: Action Send, Amount, To `0x000000…00dEaD`, Network fee 0.00063 ETH, All Decoded Fields, Request Payload, and
  the iOS footer (`f20b`, `f21`). The layout matches `build/ios-reference/light-31b-activity-input-output.png`.
- All Decoded Fields: "Signed Request" with the summary card, derivation path `m/44'/60'/0'/0/0` and Requested signer
  `0x9858…da94` (`f27`).
- **Output**: Show QR Code, Export as File, and Signature Payload, which expands to
  `UR:ETH-SIGNATURE/OEADTPDAGD…KPLUZE` (equal to the vector's `responseFrames[0]`) with Copy Payload, plus the iOS
  footer (`f22`).
- Show QR Code: a "Signature" sheet with Done, the QR plate and "This is the signature that was already created…"
  (`f23`). The QR image itself could not be captured (FLAG_SECURE).
- **Export as File**: the system picker (DocumentsUI) proposes `bittool-signer-signature.txt` in Downloads (iOS default
  name `bittool-signer-signature`, plain text). SAVE writes a 203 B file.
  - Pulled to `f25-exported-bittool-signer-signature.txt`, it is **byte-identical** to the vector's response UR, with
    no trailing newline: the same bytes as iOS `SignedResponseDocument` writes (`Features/Sign/SignatureResultView.swift:107-108`).
  - Cancelling the picker leaves the page unchanged, with no error (`f26`).
- Clear: "Clear all activity?" with Cancel / Clear Activity. Cancel keeps both records. Clear Activity gives "No
  Signatures Yet", a 35 B empty sealed journal, and stays empty after a cold restart (`f65`–`f67`).

**Re-import onto an existing wallet = password reset (G1.15)**
- Wallets › + › Import Recovery Phrase. The 12 words were entered with the **in-app BIP39 keyboard**:
  - letter keys typed into the field;
  - "ab" offered abandon / ability / able / about / above, and tapping a suggestion inserted "abandon ";
  - the status went "1 word" … "12 words · English";
  - Done closed the keyboard.
- Continue gave Reset Password, already on the first dump (no flip, setup-08): "This recovery phrase already belongs to
  a wallet on this device…", with "My Wallet / Fingerprint 73C5DA0A" and "Only the password changes…" (`f09`).
- "Password Reset" (`f11`). The new password then signed.

**Reveal recovery phrase**
- The password form shows "The recovery phrase stays hidden until you choose to show it."
- A wrong password gives "The password is incorrect.", with the field cleared and kept focused (`f30`).
- The right password shows the concealed panel, and the nav label turns "Done" (iOS `WalletSecuritySheets.swift:70`,
  `secret == nil ? "Cancel" : "Done"`) (`f31`).
- Tap to reveal: 12 numbered words, equal to the phrase, and "Hide Recovery Phrase" (`f32`).
- Home, then return: the phrase is gone and the password form is back (DECISIONS §6) (`f33`).
- After a password change, the old password is refused and the new one reveals (`f45`, `f46`).

**Rename**
- Dialog "Rename Wallet / The fingerprint and addresses stay the same." with the name prefilled.
- Save is disabled when the name is empty or whitespace only (iOS `WalletDetailView.swift:122`).
- "   Savings Vault  " is saved trimmed, as "Savings Vault", on the detail and the list (`f35`–`f37b`).
- A 44-character name gives "Unable to Rename — Choose a wallet name of 1–40 characters." (iOS `Core/WalletVault.swift:98`, `Core/VaultCrypto.swift:28`,
  `WalletDetailView.swift:126`) (`f63`, `f64`).

**Change password**
- Fields "Current wallet password / New password / Confirm new password", the requirements checklist and the footer.
- A mismatched confirmation disables the button.
- A wrong current password gives "The password is incorrect.", and the wallet is unchanged (`f43`).
- Success returns to the detail page with the **"Password Changed"** toast (iOS `WalletDetailView.swift:131`)
  (`f47-password-changed-toast.xml`).
- IME Next moves current → new → confirm. IME Done on the confirmation hides the keyboard.

**Delete with the password (App Lock off, so no biometric)**
- The page shows "Delete this wallet?" and the callout, the toggle "I confirm I want to delete this wallet" and the
  footer "Deleting asks for this wallet's password.". Delete is disabled until the toggle is on (`f50a`, `f50`).
- The dialog reads "Wallet Password / Enter this wallet's password to delete it…" with Cancel / Delete (`f51`).
- An empty password gives "Enter this wallet's password." (iOS `WalletSecuritySheets.swift:289`) (`f52`).
- A wrong password gives "The password is incorrect." and the vault is kept (`f53`).
- The right password deletes the wallet: `Vaults/` and `PublicWallets/` are empty, and the app returns to Welcome
  (`f54`).

**Re-import from Welcome**
- "Import Existing Wallet" goes straight to Step 1 (onboarding already done), with the name proposed as "My Wallet".
- "Set a Password" mode (not Reset), then "Wallet Imported", with 73C5DA0A and "Pair a Wallet" (`f55`–`f57`).
- The wallet signs the vector again with the identical signature.

**Import File** (system picker, OpenDocument)
- The pushed `qa-eip1559-request.txt` opens the same review. The tiles are disabled and progress shows while it is read
  (`f68`, `f69`). Reject returns to the Scan home.

**App Lock without a biometric**
- The `settings.appLock` switch is `checkable`, `checked=false` and `enabled=false`.
- The footer reads "Set up fingerprint or face unlock in Android Settings to lock BitTool Signer. Every signature always
  needs the wallet's password."
- Tapping the switch changes nothing. The preference stays `appLockEnabled=false`, no prompt or dialog opens (`f48`,
  `f49`).

**L4 negative case (healthy wallets)**
- Covered on the card, the detail page (also after background/foreground), the Delete page first frame, and the
  duplicate-import route (`f01`, `f28`, `f28b`, `f50a`, `f09`, `f62`).

**Window security**
- `screencap` gives 0 bytes in a production launch. Password fields dump as bullets.
- No app crash or ANR in logcat from 21:20, and no Keystore error from the app.

**Harness-only (debug store, fixture biometrics that always fail; `h01`–`h04`)**
- The cold lock screen reads "BitTool Signer Is Locked" with "Unlock with Biometrics", "Use a Wallet Password", the
  biometric error callout and "Unlocking the app never authorizes a signature."
- The password form reads "Enter the password of any wallet on this device." and shows the wallet menu preselected
  with one wallet (iOS `Features/Lock/AppLockView.swift:57`). Unlock is disabled while the field is empty, and "Use Biometrics
  Instead" is offered. It matches `build/ios-reference/light-51-app-lock-password.png`.
- A wrong password gives "The password is incorrect." with the field cleared and the keyboard up.
- The fixture password unlocks to the wallet list.

## Not tested, and why

- **The App Lock password form and unlock with the production key policy.** Enabling App Lock needs an enrolled
  biometric, and the brief forbids enrolling or simulating fingerprints. The form was exercised with the harness only.
- **The positive lost-key cases L2, L3 and L4** (lost-key wallet card or detail, "Continue Without App Lock", duplicate
  detection of a lost-key wallet), and backlog O1. They need a new screen-lock removal on a device, which agents may
  not do. No lost-key wallet exists on this device.
- **The restart notice shown above existing records** (`feature/activity/.../ActivityScreen.kt:197-199`). The journal could only restart
  once, and I acknowledged the notice before signing. This was checked in code only.
- **The Show QR image content** (FLAG_SECURE in production). The payload text was verified instead.
- **Visual layout of the production screens** (FLAG_SECURE). Checked through accessibility text and bounds only.
- **Biometric flows** (delete confirmation, lockout), dark mode, other languages, TalkBack speech and predictive back.
  Outside this tour's brief, or they need biometrics.
- **Deleting the journal's old Keystore alias.** It cannot be inspected without root. The new journal sealing and
  reading back proves a working key.
