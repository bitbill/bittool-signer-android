# QA round 2: tour "production" (production Keystore policy, real biometrics)

Device: emulator-5556, API 31, 1080×2220 @ 440 dpi, 3-button navigation. `adb emu avd name` reports **SignerQA3**. The
brief said SignerQA2; that AVD is emulator-5558, see `round2-activity.md`. Build: the installed `com.bittool.signer.debug`
1.0.0-debug, installed 14:23 (main ≈ 8b03cb2). It predates 78b6d59, so it has **no in-app BIP39 keyboard**.

Every run was a normal launch with no UI-test harness: `pm clear` first, then `am start -n …/com.bittool.signer.MainActivity`.
So the real `AndroidKeystoreDeviceKeyStore`, `KeystoreJournalCipher` and `AndroidBiometricAuthenticator` were in use,
with `setUnlockedDeviceRequired(true)` keys. The window is `SECURE` and `HIDE_NON_SYSTEM_OVERLAY_WINDOWS`, and
`screencap` returns no image at all. All evidence is therefore accessibility dumps (`ui.py dump` and `uiautomator dump
--windows`) saved as text: `build/qa/round2/production/pNN-*.txt`. There are no screenshots. BiometricPrompt is a system
window, so its title, description and buttons are in the dumps.

OS-level steps on emulator-5556, all within the brief (test PIN 1234, test fingerprint):
- 14:27: keyguard unlocked with PIN 1234.
- 14:28–14:30: Settings › Security › Pixel Imprint, fingerprint 1 enrolled.
- 15:43: `locksettings clear --old 1234`. Android also deleted the fingerprint.
- 15:45: `locksettings set-pin 1234`.
- 15:47: fingerprint 1 enrolled again.
- 16:14: `locksettings clear` again, with App Lock off, to see the no-PIN state.
- 16:19: `set-pin 1234`.

End state: PIN 1234 set, **no fingerprint enrolled** (as at the start), `screen_off_timeout` unchanged (max), font 1.0.
The app holds one wallet, "My Wallet" (73C5DA0A), App Lock is off, and the Activity journal is unreadable (production-01).

## Summary

**The production key policy works for the normal life of a wallet.**
- Create, import, addresses, pairing, signing, Activity, App Lock, reveal and delete all work end to end with
  `setUnlockedDeviceRequired` keys.
- Create gave master fingerprint 1279E952, recomputed offline from the words shown. Importing the reference phrase gave
  73C5DA0A.
- No Keystore error appeared in logcat at any point.
- The EIP-1559 vector signed from a **pasted** request gives exactly the published signature `8bfb3928…300400`.
- The Activity record is written to `receipts.bin` (`BTSR` v1 header, no plaintext) and reads back after a cold restart.
  This was not testable in round 1 (sign-01).
- App Lock behaves like iOS:
  - enabling it needs a fingerprint, and cancelling the prompt leaves it off;
  - a cold launch locks and prompts at once;
  - 46 s in the background stays unlocked and keeps the page; 125 s locks;
  - a fingerprint unlocks;
  - "Use a Wallet Password" opens the password form, where any wallet's password unlocks;
  - wrong passwords back off.
- Deleting a wallet asks for the fingerprint first: Cancel aborts, a fingerprint leads to the password dialog, and a wrong
  password leaves the wallet intact.

**The lost-key path is where the production policy breaks.** When the screen lock is removed, Android deletes every
`setUnlockedDeviceRequired` key: the wallet keys, the public-data HMAC key and the journal key. It also deletes the
enrolled fingerprints. What the app does then:
1. **The Activity journal stays broken for good** (production-01, P1).
   - "Unable to Continue — The local activity record could not be read." appears on every launch, every unlock, every
     delete or import, and on top of setup's Done step.
   - Every new signature ends with "…could not be saved."
   - Activity shows "Activity Unavailable" and offers no way to reset.
   - This survives removing every wallet and starting again. Only clearing the app's data fixes it.
2. **With App Lock on, the app locks the user out** (production-02, P1).
   - Without a PIN, any wallet password gives "Set a screen lock…". That part is fine.
   - With the PIN set again, every wallet password gives "This wallet's device key is unavailable. Restore your recovery
     phrase on this device.", which is impossible from behind the lock. No fingerprint exists any more.
   - The only way in, re-enrolling a fingerprint in Android Settings, is mentioned nowhere.
3. **B21 does not work in the real case** (production-03, P1). Re-importing the phrase of a lost-key wallet silently
   creates a **second wallet with the same keys**. The duplicate check reads the public index, which is unreadable
   because the public-data key went too.
4. **Lost-key wallets are presented as "Repair wallet data"** on the list and the detail page (production-04, P2).
   Repair then fails ("device key is unavailable"). The working "This Wallet Can't Be Unlocked › Remove This Wallet" flow
   (B9) is only found under Delete Wallet. There it works, with biometric confirmation when App Lock is on and without it
   otherwise. Re-importing the reference phrase afterwards and signing again works: same signature.

Counts: **P0 0 · P1 3 · P2 1 · P3 3.**

Test conditions:
- Host load average 9–220. Timing is not judged.
- **Two instances of this tour drove emulator-5556 at the same time**, 14:44–14:52 and again 15:20:30–15:26.
  - Both instances write to the same transcript file (`subagents/agent-a4aee7e15b6dd77f3.jsonl`). The copy reported
    separately: `round2-production-part1.md`, and `-part2.md` (part 2 = the twin's hand-back).
  - From 15:30:11 the device was claimed with `scratchpad/PRODUCTION-TOUR-DEVICE-CLAIM.txt`, and the twin handed back at
    15:30:23. Everything from 15:30 on is this instance's input only.
  - Results inside the two overlap windows are either independently verified (addresses equal the standard vectors) or
    were repeated later in the clean window (password unlock, signing).
  - The twin caused a foreign force-stop at 15:23:37 (`am_kill … from pid 19390`) and a wrong lock-screen password at
    about 15:25. Neither is counted.

## Issues

| ID | Sev | Screen | Steps | Expected (iOS / spec) | Actual (evidence) | Suspected owner · file:line |
|---|---|---|---|---|---|---|
| production-01 | P1 | Activity tab, root alert, every flow that reloads the store | Wallet with ≥1 signature (journal written) → `locksettings clear --old 1234` → `set-pin 1234` → launch, unlock → use the app (delete, import, sign, relaunch) | The journal key can be lost on Android: a screen-lock removal deletes it. The app must recover: start a new journal, with a one-time notice, or offer Clear. iOS never meets this state, because its journal has no key that a passcode change destroys (`Models/AppStore.swift:536-540`, `:554-568`). One "could not be read" per real event, not per reload. | "Unable to Continue — The local activity record could not be read." after **every** launch, unlock, wallet removal, import and setup Done (p41, p48, p51, p53, p56, p58, p63). Every signature then shows "The local activity record could not be saved. Any completed signature remains available." (p55). Activity shows "Activity Unavailable" with no Clear (p49). Removing all wallets and importing a first wallet from Welcome still shows it (p63). Only `pm clear` recovers. | core:data `core/data/src/main/kotlin/com/bittool/signer/core/data/DefaultSignerStore.kt:852-863` (sets `error` on every load), `:869-880` (append refuses because the read fails); core:crypto `vault/KeystoreJournalCipher.kt:53-80` (a missing key is reported as `AuthenticationFailed`, the same as a corrupt file); feature:activity `ActivityViewModel.kt:41` (`canClear` only with records; DECISIONS §10). Prove the journal key gone with the probe method of `AndroidKeystoreDeviceKeyStore.isKeyLost`, then replace `receipts.bin` with an empty journal under a new key, raising the alert once. Otherwise offer "Clear Activity" in the unreadable state. |
| production-02 | P1 | App Lock screen | App Lock on, 2 wallets → `locksettings clear --old 1234` (Android also deletes the fingerprint) → cold launch → "Use a Wallet Password" → any wallet's password; then `set-pin 1234`, retry | App Lock only guards public pages and never decides what a wallet can do (`Services/AppAccessController.swift:81-82`). It must not leave the person with no way in. When no unlock method can succeed, the lock screen should say what to do, or App Lock should stand aside (as it does for 0 wallets, `:120-126`), so that the lost-key removal (B9) and re-import can be reached. | No PIN: password form only ("Use Biometrics Instead" correctly hidden); the password gives "Set a screen lock (PIN, pattern or password) to create or use a wallet…" (p36, p37). That is fine. After `set-pin`: the password gives "This wallet's device key is unavailable. Restore your recovery phrase on this device." for every wallet (p38, p39), which cannot be done from behind the lock. No biometric is enrolled, so the app is unusable. Nothing says that enrolling a fingerprint helps. After I re-enrolled one, "Use Biometrics Instead" appeared on return and the fingerprint unlocked (p40, p41). | core:security `access/DefaultAppAccessController.kt:211` (password unlock = `vault.verifyPassword`), `:238` (biometrics re-checked on foreground); :app `lock/AppLockViewModel.kt` (no lost-key state). Suggest: on `DeviceKeyUnavailable` with `isDeviceKeyLost` true for every wallet, unlock App Lock (or turn it off) and show the lost-key explanation. At least add a line pointing to fingerprint enrolment. |
| production-03 | P1 | Import (setup) after a key loss | After production-02, unlock; Wallets › + › Import Recovery Phrase › the phrase of an existing lost-key wallet ("legal winner …", B8688DF1) › Continue › password › Import Wallet | Backlog B21: detect the lost key and point to "Remove This Wallet" (`setup_lost_key_message` + "Open Wallet"). Never create a second wallet for the same phrase and passphrase (iOS `Models/AppStore.swift:192` "already belong to an existing wallet"). | The password step stays in normal "Set a Password" mode (no Reset mode, no lost-key message; p50). "Import Wallet" creates **a second wallet**, `d33efc66…`, next to the dead `4020572a…`: two "Wallet 2" cards, one "Setup required", one B8688DF1 (p51, p52; `Vaults/` holds both `.bsvault`). The harness `--lose-keys` keeps the public-data key, so this case is invisible to e2e. | core:data `DefaultSignerStore.kt:433-441` (`existingWallet` matches only `publicWallets[…].walletKeyId`) and `:399-400` (the `createWallet` duplicate guard, same source); feature:setup `flow/WalletSetupViewModel.kt:489-503`. When a wallet has no readable public index, compare against the walletKeyId kept in its vault sidecar/header, or treat it as a possible duplicate and ask `isDeviceKeyLost`. Add an e2e/harness switch that also drops the public-data key. |
| production-04 | P2 | Wallets list card, wallet detail | Either state: (a) screen lock removed, App Lock off, cold launch; (b) after a new PIN, any lost-key wallet | A wallet whose device key is gone says so where the person looks first ("This Wallet Can't Be Unlocked", Remove; backlog B9). With no screen lock, say that a screen lock is required. Repair is only for damaged public data (iOS `Features/Wallets/WalletDetailView.swift:40-41`). | Both states show "Fingerprint Setup required" + "Repair wallet data" on the card and "Repair needed — This wallet's public data is missing or damaged… Enter the wallet password once to rebuild it." on the detail (p42, p43, p58). Repair with the right password fails: "This wallet's device key is unavailable…" (p45). Delete in state (a) asks for the password, then fails with "Set a screen lock…" (p59, p60). The lost-key page appears only under Delete Wallet (p46). | feature:wallets `list/WalletsScreen.kt:344`, `detail/WalletDetailScreen.kt:304-311` (Repair chosen from "no public index" alone). Query `isDeviceKeyLost` (and `isDeviceSecure`) for wallets without an index and show the lost-key or screen-lock state instead of Repair. |
| production-05 | P3 | App Lock screen | Trigger the back-off (9 wrong passwords for one wallet) → "Use Biometrics Instead" | A message that is still true (iOS shows `error ?? access.error`, `Features/Lock/AppLockView.swift:29`, so this is iOS-shared) | "Too many unsuccessful attempts. Try again in 8 seconds." stays, frozen, on the biometric actions and is never updated or cleared, even after the delay has passed (p28) | :app `lock/AppLockViewModel.kt:104-107` (`local.error ?: accessState.error`); core:security keeps `error`. Clear the access error when the form changes, or count down. |
| production-06 | P3 | Setup name; App Lock wallet menu | 1 wallet "My Wallet" + "Wallet 2" → delete "My Wallet" → Import: the name proposed is "Wallet 2" again; later "Wallet 3" twice | Names that tell wallets apart. iOS has the same count-based default (`Features/Setup/WalletSetupModel.swift:42-43`), and its lock menu lists names only (`AppLockView.swift:72-75`) | Two wallets named "Wallet 2", then two named "Wallet 3". The App Lock menu shows "Wallet 2 / Wallet 2" with nothing else (p37), and the person cannot tell which password to type | feature:setup `flow/WalletSetupViewModel.kt:137` (`existingWallets + 1`). Pick the first unused "Wallet n". :app `lock/AppLockScreen.kt:331-395` could add a short disambiguator for duplicate names (the fingerprint stays hidden by design). |
| production-07 | P3 | Review › Signing Wallet | Review any request (English) | Same text as the list and the Authorize sheet ("Fingerprint 73C5DA0A") | `'Fingerprint ⁨73C5DA0A⁩'`: FSI/PDI isolates in an LTR interface (p54). This is the rest of wallets-15; the list and the Authorize sheet are fixed | feature:sign `review/SigningWallets.kt:170` (`isolate(...)`). Use `isolateInRightToLeft` (`core/ui/.../wallet/Wallets.kt:167`). |

Seen again, not counted: the `BasicSecureTextField: Failed to fetch show password setting` stack trace on every password
field (production-r02 in part 1). Environment, not a product finding: duplicate tour instance on one emulator
(production-r01); AVD name SignerQA3 instead of SignerQA2.

## Verified working (production policy, no harness)

- **Window security**: `fl=SECURE`, `pfl=HIDE_NON_SYSTEM_OVERLAY_WINDOWS`. `screencap` yields nothing. Password fields
  show `•••` in the accessibility tree (B1 fixed). On API 31 the revealed recovery words are in the tree (by design:
  `setAccessibilityDataSensitive` needs API 34).
- **Create** (12 words): onboarding pages 1–3; the phrase revealed; verify with 4 chips; password checklist and
  "Passwords match"; "Wallet Created, Fingerprint 1279E952", equal to the offline BIP32 computation from the words shown.
  Per-wallet AES and public-data HMAC keys were generated with `setUnlockedDeviceRequired`, no Keystore error.
- **Import** of the reference phrase: "12 words · English", fingerprint **73C5DA0A**. Second wallet "legal winner…":
  B8688DF1, equal to the offline value.
- **Pairing**: Wallet Level animated QR "part 1 of 4", advancing (1/4 → 2/4), brightness control, "BTC · EVM · TRON ·
  SOL". The verify list gives the standard first addresses for BIP84/86/49/44, EVM and TRON of the reference phrase
  (`bc1qcr8t…306fyu`, `bc1p5cyx…`, `37VucYSa…`, `1LqBGSKu…`, `0x9858…Eda94`, `TUEZSdKs…`).
- **Addresses**: BIP84 receive #0–#5 equal the BIP84 test vectors.
- **Signing from a pasted request**:
  - EIP-1559 vector `evm-eip1559` from `core/data/src/androidTest/resources/vectors/signing-roundtrips.json`, typed into
    Paste Request.
  - Review: Ethereum, "Decoded on device", Send 0.000000000000000001 ETH, To `0x…dEaD`, "Network fee up to 0.00063 ETH",
    signing wallet.
  - Authorize sheet: sized to its content (538–1403 px; sign-11 fixed), password masked.
  - Result: "Signature Created", "ur:eth-signature · CBOR · 89 bytes". The Payload JSON signature equals the vector's
    `signatureHex` exactly, both before the key loss (Wallet 2) and after the re-import (Wallet 3).
- **Activity (KeystoreJournalCipher, production key)**:
  - Row "Native transfer · 0.000000000000000001 ETH · Wallet 2" under "Wednesday, Oct 21, 2026".
  - Detail: Date, Network, Wallet, Fingerprint 73C5DA0A, Request fingerprint, no-broadcast note.
  - Still listed after force-stop + cold launch.
  - `no_backup/BitToolSigner/receipts.bin` starts with `BTSR 01`.
- **App Lock**:
  - The Settings footer reads "Require biometric unlock…" while a fingerprint exists and "Set up fingerprint or face
    unlock…" when none does. The switch stays operable while on (iOS `SettingsTab.swift:24-40`).
  - The enable prompt reads "Lock BitTool Signer / Signing still requires the wallet password. / Cancel". Cancel leaves it
    off; a fingerprint turns it on.
  - Cold launch locks and auto-prompts "Unlock BitTool Signer" with the negative button "Use a Wallet Password". A
    fingerprint unlocks, and an unenrolled finger does not.
  - 46 s in the background stays unlocked on the same page (Activity); 125 s locks and prompts.
  - "Use a Wallet Password":
    - "Enter the password of any wallet on this device." and a wallet menu with names only, nothing preselected with 2
      wallets;
    - Unlock is disabled until a wallet and a password are given;
    - "Use Biometrics Instead" switches back, and "Unlock with Biometrics" unlocks.
  - The other wallet's password unlocks (clean-window repeat at 16:0x with Wallet 3).
  - A wrong password gives "The password is incorrect." with the field cleared and refocused. After repeated failures:
    "Too many unsuccessful attempts. Try again in 8 seconds."
  - With no biometric available, the lock screen opens straight on the password form, with no "Use Biometrics Instead"
    (iOS `AppLockView.swift:90`, `:101-105`).
  - Enrolling a fingerprint while the app is in the background makes "Use Biometrics Instead" appear on return.
- **Reveal recovery phrase** (Wallet 2):
  - Password, then the concealed panel, then "Tap to Reveal" shows the 12 words.
  - The nav label reads "Cancel" before the reveal and "Done" after it (wallets-10 fixed).
- **Delete with biometrics** (My Wallet):
  - Footer: "Deleting asks for biometric confirmation, then for this wallet's password."
  - The prompt "Confirm before deleting this wallet." (Cancel) leaves the page unchanged.
  - A fingerprint opens the "Wallet Password" dialog. A wrong password gives "The password is incorrect." on the page with
    the wallet kept.
  - The right password deletes it: the vault, the public file and the attempts file are gone, and the list updates.
- **Lost-key removal (B9)**:
  - "This Wallet Can't Be Unlocked" appears already on the first frame (B22 holds), with the toggle "I understand that
    only the recovery phrase can restore this wallet".
  - The footer names the biometric when App Lock is on.
  - Remove: fingerprint, then "Remove This Wallet?", then Remove deletes the files. Without App Lock there is no prompt.
  - Removing all wallets returns to Welcome.
- **Screen-lock precheck (wallets-04 fixed)**:
  - With no screen lock, Import shows "Screen Lock Required" with "Open Settings", which opens "Choose a screen lock", and
    Continue is disabled.
  - After a PIN is set, returning removes the callout.
- **Re-import after the loss**: the reference phrase is imported again (73C5DA0A) and signs with the identical signature.
- No crash or ANR of the app in logcat.

## Not tested, and why

- **Welcome "Screen Lock Required" dialog**: it needs 0 wallets and no screen lock at the same time. I tested only the
  Import-step callout.
- **Biometric lockout** (5 failed prints, then `security_biometric_lockout`) and a biometric failure falling through to
  the password on Delete: not run, to keep the enrolled-print state simple. Only Cancel and success were tested.
- **Signature of other request types, Activity detail Output section, Show QR sheet, Export as File, Clear Activity with
  records**: the journal was unreadable from 15:47 (production-01). Other request types were covered in round 1.
- **In-app BIP39 keyboard (DECISIONS §11)**: not in the installed build; the system keyboard (incognito) was used.
- **Visual layout of any screen**: FLAG_SECURE blocks capture in a production launch. Layout and copy are checked only
  through accessibility text and bounds.
- **Show Recovery Phrase, Change Password and Sign on a lost-key wallet**: not exercised. The vault error should be the
  same "device key is unavailable" (code read).
- **Predictive back, TalkBack speech, dark mode, other languages**: outside this tour.
- **Results inside 14:44–14:52 and 15:20:30–15:26** (the duplicate instance's windows): kept only where independently
  verified or repeated later in the clean window. The first pasted request in the 14:49 window lost characters to
  concurrent input and was redone with verified typing.
