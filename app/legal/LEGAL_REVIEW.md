# Android legal texts — pending legal-owner review

**Status: DRAFT for legal review (DECISIONS.md §9.5). Do not ship a release before the legal owner has approved both
flavors' texts and the release checklist records the approval.**

The app shows the Terms of Use and the Privacy Policy from its assets (`legal/TERMS_OF_USE.txt`,
`legal/PRIVACY_POLICY.txt`; English in every language by decision). Each distribution has its own copy, which
overrides `:core:data`'s iOS-identical default at merge time:

| Flavor | Files | Distribution clause |
|---|---|---|
| `apk` (bittool.com/signer) | `app/src/apk/assets/legal/` | Terms §15 "Direct download" (obtain only from bittool.com/signer, verify the APK with `apksigner verify --print-certs`, compare with the published fingerprint and the in-app "Signing certificate"); Privacy §10 "Direct download" |
| `play` (Google Play) | `app/src/play/assets/legal/` | Terms §15 "Google Play"; Privacy §10 "Google Play" |

Everything else is identical between the flavors (`LegalTextsTest` fails on any other difference).

## What changed from the iOS texts (activity-settings.md §10)

Terms of Use:
- §1 "the BitTool Signer app for Android".
- §3 "not Apple" → "not Google, not the maker of your device".
- §4 "passcode" → "screen lock"; "jailbroken device" → "a rooted device, on one running modified system software".
- §7 "Settings › Open Source" → "Settings › Fully Open Source" (the Android row title).
- §12 "deleting the app" → "uninstalling the app".
- §13 "the address in section 15" → "section 16" (the iOS text points at its App Store section; the contact section is 16).
- §15 "The App Store" (Apple's mandatory EULA terms, the embargo clause) → the flavor's distribution clause above.

Privacy Policy:
- §1 the iOS-only subsection "Why iOS may ask about wireless data" removed; replaced by a paragraph on the missing
  Internet permission (Android itself refuses every connection).
- §2 "Keychain" → "Android Keystore"; the Activity record is encrypted with a Keystore key (DECISIONS.md §9.3); the iOS
  storage paragraph ("iOS file protection", "excluded from iCloud and computer backups", "Preferences are ordinary iOS
  settings and are included in a device backup") → private app storage, excluded from cloud backups and device-to-device
  transfers, nothing backed up (the manifest's `allowBackup="false"` and data-extraction rules).
- §3 "iOS Settings" → "Android Settings".
- §4 "Face ID and Touch ID … Secure Enclave" → "Fingerprint and face unlock … the device's secure hardware".
- §5 "never through Handoff or Universal Clipboard — and expires after two minutes" → marked sensitive, the app clears
  its own copy after two minutes (best effort on Android: keyboards and clipboard apps may keep a history).
- §6 app-switcher snapshots → `FLAG_SECURE` (screenshots, recordings, casting and Recents), the recording cover, foreign
  overlays hidden on Android 12+.
- §7 "the Files app" → "Android's file picker".
- §8 "A random device key may stay in the iOS Keychain until iOS clears it…" → uninstalling removes the Keystore keys.
- §10 "The App Store" → the flavor's distribution clause.

## Open points for the legal owner

1. The effective date and version line (kept "Version 1.1 · Effective 16 October 2026" from iOS; the UI tests look for
   "Version 1.1").
2. Privacy §10 (apk): whether the website download needs its own notice (server logs of bittool.com are outside the
   app and outside this policy).
3. Terms §15 (play): whether Google Play requires any further clause for a self-custody crypto app (re-read the
   "Cryptocurrency exchanges and software wallets" policy before each release, ownbit-build-release.md §7.2 step 11).
4. The only URL either text may contain is `https://bittool.com/signer` (the app opens no browser; tested).
