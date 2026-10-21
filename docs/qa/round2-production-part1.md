# QA round 2 — production tour, part 1 (duplicate instance, stopped by the orchestrator)

A second instance of the production tour (spawned when the orchestrator messaged the workflow agent) drove
emulator-5556 (AVD **SignerQA3**; the brief said SignerQA2) in parallel with the original from about 14:44. It was
stopped; the original instance kept the device and writes `round2-production.md`. Results that depend on input order
inside 14:44–14:52 must be re-checked there.

## OS-level steps taken on emulator-5556 before the orchestrator's correction (PIN 1234, user-authorised test PIN)
- ~14:26 `locksettings get-disabled --old 1234` (read-only query).
- 14:27 keyguard unlocked once with PIN 1234.
- 14:28–14:30 Settings › Security › Pixel Imprint: PIN re-entered, one test fingerprint enrolled (12 × `emu finger touch 1`).
- Afterwards no PIN entry, no Settings › Security, no locksettings, no fingerprint touches. PIN still set, 1 print
  enrolled, device unlocked (`screen_off_timeout` max).

## Verified with the production Keystore policy (PIN set, real `setUnlockedDeviceRequired` keys)
- Normal launch (no harness): window is `SECURE` + `HIDE_NON_SYSTEM_OVERLAY_WINDOWS`; screenshots fail as intended
  (checked via the accessibility tree; on API 31 revealed words are still in the tree — `setAccessibilityDataSensitive`
  applies on API 34+ by design).
- Welcome → 3-page onboarding → Create step 1 ("Step 1 of 4", "My Wallet", 24/12 words, passphrase row).
- Create: 12-word phrase generated and revealed, verify passed (4 chips), password checklist and "Passwords match",
  "Wallet Created" with fingerprint **1279E952** = the BIP32 master fingerprint computed offline from the shown words.
  No Keystore error in logcat: per-wallet and public-data key generation with `setUnlockedDeviceRequired` works.
- Import of the reference phrase: "12 words · English", password step, wallet listed as fingerprint **73C5DA0A**.
- Addresses, BIP84 receive #0–#5 equal an independent offline derivation of m/84'/0'/0'/0/i
  (#0 bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu … #5 bc1qnpzzqjzet8gd5gl8l6gzhuc4s9xv0djt0rlu7a).

## Issues
| ID | Sev | What | Owner |
|---|---|---|---|
| production-r01 | P1 (process) | Two instances of the tour drove the same emulator; orchestration must never message a running workflow agent (it spawns a resumed copy). | orchestration |
| production-r02 | P3 | Each password field logs `BasicSecureTextField: Failed to fetch show password setting` with a stack trace (`SettingNotFoundException: show_password`), from Compose foundation, also in release; fallback shows the last typed character briefly (matches iOS SecureField). Log noise only. | core:ui PasswordField |

## Not tested in this part
Signing and response comparison, Activity after cold restart, pairing, reveal, delete, App Lock (biometric enable /
unlock / 120 s / fallback / back-off), biometric delete confirmation, the lost-key path (needs OS lock-screen changes
agents may not make), wallets-04 precheck (needs no PIN).
