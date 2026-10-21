# Integration backlog (from the Wave 1 / Wave 2 review reports)

Items the package reviewers could not fix inside their own paths. Owner = the module that must change.
QA round 1 adds device findings in `docs/qa/round1-*.md`. ✅ = fixed and reviewed (branches fix/backlog-*-reviewed, merged).
What QA rounds 2–3 and the final audit closed, and what they left open, is at the end of this file.

| # | Owner | Item | Source |
|---|---|---|---|
| B1 ✅ | core:ui | `PasswordField`: the typed password is exposed to accessibility (`uiautomator dump` shows it in plain text). Mark password nodes as password semantics / sensitive (gaps G5) without breaking the e2e flows that type into them. | W2-shell review |
| B2 ✅ | core:ui | Secret fields that clear with `clearText()` keep an undo history (Ctrl+Z restores a wiped password/phrase). Apply the setup editor's fix centrally in `PasswordField` (no undo, filtered text menu: no Copy/Cut/Share/Autofill on secrets), then delete feature-local copies. | W2-setup review |
| B3 ✅ | core:designsystem | `SignerTopAppBar` has no `nav.back` test tag for its navigation icon (gaps G1.0). | W2-wallets, W2-activity-settings |
| B4 ✅ | core:designsystem | `SignerBottomSheet` / `SignerAlertDialog` windows do not expose test tags as resource ids (`testTagsAsResourceId` must be set inside each dialog/sheet window). | W2-sign, W2-shell |
| B5 ✅ | core:ui | `ProfileMenu` detail line ("BIP84 · bc1q…") puts its ellipsis on the wrong side in RTL. | W2-wallets |
| B6 ✅ | core:designsystem / core:ui | `ValueRow` and `NavRow(value = …)` are not usable as specified, so settings carries local copies (`ValueLine`, `ChoiceRow`). Fix the components, then switch settings to them. | W2-activity-settings |
| B7 ✅ | core:l10n (crypto strings) | `crypto_device_lock_required` says "…before creating a wallet" but is also shown for unlock, sign and delete after the screen lock is removed. Reword (and update `TextsTest`). | W1-crypto |
| B8 ✅ | core:crypto | Expose a public `testing` factory for the test-policy Keystore constructors (`KeystoreJournalCipher`, `AndroidKeystoreDeviceKeyStore`) so `:core:data` androidTest stops using reflection. | W2-data-integration |
| B9 ✅ | core:data + core:crypto + feature:wallets | Lost-Keystore-key dead end: a wallet whose device key is gone can be neither reset, deleted nor re-imported (duplicate walletKeyId in its sidecar). iOS has the same dead end. Decide: allow "Remove This Wallet" without unlocking when the device key is provably unavailable (strong confirmation), which also frees the re-import. | W2-data-integration |
| B10 ✅ | core:data | Stale `StoreFixture` KDoc in `src/testShared` ("Integration swap point"); crypto doubles still compiled into the androidTest APK. | W2-data-integration |
| B11 ✅ | core:scanner | Scanner icons/colours use Material Icons Rounded and a private `ScannerPalette`; switch to `:core:designsystem` symbols and tokens. A failed CameraX start is not retried on resume (iOS restarts the camera on every activation). | W1-scanner |
| B12 ✅ | repo | `.gitignore` `build/` also matches a source package named `build`; anchor it. | W2-shell |
| B13 ✅ | core:common | Stale `BuildInfo.displayVersion` KDoc. | W2-shell |
| B14 ✅ | feature:onboarding / settings | Welcome's English "missing document" paragraph: its string belongs in `strings_settings.xml` and must be translated. | W2-shell |
| B15 ✅ | feature:sign, feature:activity | Engine error text must use `Callout(verbatim = true)`; verify every call site. | W1-design |
| B16 ✅ | feature:sign | Authorize sheet insets with the keyboard open: verify on device; the sheet opens at full content height. Verified on a device in QA round 3 (activity-keyboard tour, sign-11): content-sized above the keyboard (538–1403 px with it, 1223–2088 without), signing with IME Enter. | W2-sign |
| B17 | feature:settings | Owner questions: which tag "check out the tag for version 1.0.0 (1)" names; the in-app build steps omit `--ignore-scripts`; "Deleted wallet" row subtitle; the Language page staying open after a change. | W2-activity-settings |
| B18 | app | Android Terms / Privacy texts (apk, play) are drafts awaiting legal review. | W2-shell |
| B19 | release | Refresh `THIRD_PARTY_LICENSES_ANDROID.txt` from the final dependency set (include vendored QuickJS, MIT). | W1-data |
| B20 ✅ | core:l10n | Every `strings_<area>.xml` is English only: translation pass into the 20 other languages. Done (fd447d2; `tools/l10n/android/<lang>.json`); QA round 3 saw the Android-only texts translated (regression-11, the L1 notice in 21 languages). | all |

## Found by the backlog reviewers (open)

| # | Owner | Item |
|---|---|---|
| B21 ✅ | feature:setup | Re-importing the phrase of a wallet whose device key is lost switches setup into Reset Password, which then fails with "device key unavailable…". Detect the lost key and point the user to "Remove This Wallet" (B9) instead. |
| B22 ✅ | feature:wallets | Delete Wallet first renders the normal page, then switches to "This Wallet Can’t Be Unlocked" after the key check (~1 s on a device): show a neutral loading state until the check answers. |
| B23 ✅ | core:crypto | `TestKeystore` is a public production-source object building weaker test-policy keys; guard it (`@VisibleForTesting` + lint, or move it behind a test-only artifact). |
| B24 ✅ | app | `FixtureDeviceKeyStore` keeps `isKeyLost = false`, so e2e can never reach the lost-key removal; add a harness switch. |
| B25 ✅ | app | `UnableToContinueDialog` shows store/engine error text in the interface direction; iOS shows it as given (`contentDirection()`). |
| B26 ✅ | core:designsystem / core:ui | `DropdownMenu` popups (ProfileMenu, App Lock wallet menu…) do not publish test tags as resource ids. |
| B27 ✅ | core:ui | Verify that PasswordField's semantics for autofill (`InputText`, `FillableData`) never carry the plain password (relies on the app-wide autofill exclusion today). |
| B28 ✅ | app | Pre-existing lint error: RestrictedApi in `SignerNavigation.kt:84`. |

## Left by the QA round 1 fix reviewers (open)

| # | Owner | Item |
|---|---|---|
| B29 ✅ | app, feature:settings, feature:wallets | Raw `DropdownMenu` calls (AppLockScreen.kt:380, NetworkReferenceScreen.kt:332, AddressesScreen.kt:289, WalletsScreen.kt:218) → `SignerDropdownMenu`, so their tags are resource ids (rest of B26). |
| B30 ✅ | core:designsystem / core:ui | Put `WindowFocusReturnEffect` inside `SignerAlertDialog`, `SignerBottomSheet`, `SignerDropdownMenu` and the core:ui menus, so every dialog, sheet and popup returns or clears focus like the Wallets ones (rest of wallets-12). Done as `WindowFocusHoldEffect` (QA round 2); fix/polish removed the feature-local duplicates and moved every feature `clearFocus()` to `rememberClearFocus()`. |
| B31 ✅ | core:designsystem | `SwitchRow` subtitle: `LineBreak.Heading` / phrase breaking on API 33+ (rest of setup-15). Done (QA round 2); fix/polish shares it as `PhraseText` (setup titles and running text, onboarding titles, read as written by TalkBack). |
| B32 ✅ | feature:sign | When the acknowledgement scrolls into the list (font ≥ ~1.6), the disabled Sign gives no hint; and first frame shows the acknowledgement in the bar before it moves. |
| B33 ✅ | app / feature:sign | Whether the tab bar should hide on the review page (iOS parity check of the pushed review). |

## Lost-key recovery (QA round 2 production tour; Android-only — removing the screen lock deletes every UNLOCKED_DEVICE_REQUIRED key and enrolled fingerprints)

| # | Sev | Item |
|---|---|---|
| L1 ✅ | P1 | production-01: a provably lost Activity journal key leaves the journal unreadable forever ("local activity record could not be read" on every reload, every new signature "could not be saved", no reset). The records are public data: when the key is provably gone, start a fresh journal automatically and tell the person once. **Confirmed on the device it was found on** (QA round 3 production-final: journal removed, "Activity Started Again" until OK, new journal reads back); a damaged journal whose key is present stays unreadable. The instrumented `SignerStoreRealCryptoTest` asserts both on the real Keystore. |
| L2 ✅ | P1 | production-02: App Lock locks the person out when no wallet password can verify (all device keys lost) and no biometric is enrolled. App Lock guards public pages only; when no unlock path can succeed, the lock screen must explain why and let the person continue (turning App Lock off). |
| L3 ✅ | P1 | production-03: re-importing the phrase of a lost-key wallet creates a second wallet with the same keys (the duplicate check needs the lost public-index key). Detect it from the unauthenticated sidecar identity and route to "Remove This Wallet" (B21 intent). |
| L4 ✅ | P2 | production-04: lost-key wallets (and wallets on a device without a screen lock) show "Repair needed", which cannot succeed; show "This Wallet Can’t Be Unlocked" with Remove where the person looks first (list card, detail). |
| L5 | P3 | (production-06 ✅ unique default names; production-07 ✅ fingerprintLine, confirmed on a device in QA round 3: no FSI/PDI isolates in the Review dump) Open, iOS-shared: production-05: the lock screen back-off message stays frozen (also iOS). production-06: default wallet names can repeat (also iOS; fixed on fix/lost-key). production-07: Signing Wallet fingerprint keeps FSI/PDI isolates in LTR (rest of wallets-15; also regression-06; fixed in QA round 2, and fix/polish draws every "Fingerprint …" line through `fingerprintLine`). |

## Still open after the lost-key / polish round

| # | Item |
|---|---|
| O1 | Device pass of the real screen-lock removal (production-01..04 fixes) — needs a phone where the owner removes and re-sets the screen lock; agents may not. L1 is done: QA round 3 production-final confirmed it on the round-2 device whose lock had been removed. L2–L4 positive cases (a lost-key wallet card and detail, "Continue Without App Lock", duplicate detection of a lost-key wallet) remain. |
| O2 | Debug harness: `bts.uitest.loseDeviceKeys` keeps the public-data and journal keys, so e2e cannot reach L1/L3/L4 as production does. **L1 part done** (QA round 3 activity-keyboard-07): `bts.uitest.loseJournalKey` / `tools/qa/ui.py launch --lose-journal-key` loses the run's journal key for good, so "Activity Started Again" is reachable. L3/L4 still need a lost public-data key in the harness. |
| O3 | Public-data HMAC key permanently *invalidated* (not deleted) is not recognised by the loss proof (AES-only); if a device invalidates instead of deleting, imports fail until data is cleared. |
| O4 | A wallet whose sidecar cannot be authenticated but whose key is fine blocks re-import and points to Repair; if its password is also forgotten there is no way forward (iOS would create a duplicate). |
| O5 | Below API 33 the CJK word joiners in `PhraseText` layouts can shift TalkBack character navigation (spoken text is correct). |

## Closed by QA rounds 2–3 and the final audit

| Source | Items | Closed by | On a device |
|---|---|---|---|
| QA round 1 (`round1-setup.md`, `round1-wallets.md`) | setup-01..15, wallets-01..15 | fix/qa1-* | Round 2 regression tour: 24 of 30 confirmed; the rest carried on as regression-11 (setup-06), regression-12 (wallets-07) and regression-13 (setup-15), confirmed in round 3 (regression-12 in Japanese only), B30 (wallets-12, done) and production-07 (wallets-15, confirmed in round 3); wallets-14 stays a product call |
| QA round 1 (`round1-sign.md`) | sign-01..18 | fix/qa1-*, fix/polish | Round 3: 16 confirmed; sign-08 (Import File spinner) code present, not observed (the decode finished within a frame); sign-09 (camera denied) not tested |
| QA round 2 regression tour (`round2-regression.md`) | regression-01..14 | fix/qa2-*, fix/polish | Round 3: 13 confirmed; regression-01 fixed on the default path (the in-app keyboard has no IME); with the system keyboard Gboard still suggests (activity-keyboard-02, open) |
| QA round 2 activity tour (`round2-activity.md`) | activity-01 (no harness path to the unreadable journal) | `bts.uitest.unreadableJournal` / `ui.py --unreadable-journal` (081264f) | Round 3: alert at launch, "Activity Unavailable" without Clear, "could not be saved" after a signature |
| QA round 2 production tour (`round2-production.md`) | production-01..07 = L1–L5 above | fix/lost-key, fix/polish | Round 3 production-final: L1 and production-07 confirmed, L4's healthy-wallet side confirmed; L2–L4 positive cases need O1; production-05 open (iOS-shared) |
| Final audit (`audit-final.md`) | A01–A14, all 14 confirmed findings | fix/audit-setup (A01), fix/audit-qr-capture (A02 screen kept on only while an animated code plays with the window gate open, A03 mirroring counts as captured on every API level, A13 animated frames kept per screen), fix/audit-crypto-engine (A04–A08, A12), fix/audit-sign (A09, A10, A14), fix/audit-app (A11 `ensureLatinDigits` on the side of `super.onCreate` where AppCompat sees the list) | Not device-specific; A01's accessibility Copy/Cut could not be fired through adb (round 3), covered by unit tests |
| QA round 3 activity-keyboard tour (`round3-activity-keyboard.md`) | activity-keyboard-06 (the harness `--locale` lost to AppCompat's stored language on API 31), activity-keyboard-07 (no harness path to "Activity Started Again") | fix/final-app-tools-docs: below API 33 the harness recreates an activity attached in another language once, so AppCompat stores the run's language; `bts.uitest.loseJournalKey` / `ui.py --lose-journal-key` | Robolectric (API 31) and JVM tests; not yet re-toured |

## Open after QA round 3

| # | Sev | Owner | Item |
|---|---|---|---|
| activity-keyboard-01 | P2 | core:ui, feature:setup | The in-app keyboard keeps its portrait rows after a rotation (and the landscape rows after rotating back): the editor is off screen. `LocalWindowInfo.containerDpSize` is not updated while `MainActivity` handles the rotation itself (`Bip39Keyboard.kt` `rememberKeyboardMetrics`, `FormSteps.kt` `shortWindow`, `PushedSteps.kt`). |
| activity-keyboard-02 | P3 | feature:setup | With the system keyboard chosen, Gboard ignores `TYPE_TEXT_FLAG_NO_SUGGESTIONS` and suggests on the phrase (rest of regression-01). |
| activity-keyboard-03 | P3 | feature:setup | Font 2.0 with an error: the callout, Continue and the in-app keyboard leave the status line under the top bar. |
| activity-keyboard-04 | P3 | feature:activity, core:designsystem | Activity › Output "Show QR Code" / "Export as File" are label-coloured navigation rows; iOS draws accent list buttons (as the wallet page since regression-14). |
| activity-keyboard-05 | P3 | core:ui | The in-app keyboard's Paste key looks enabled with nothing pasteable and does nothing. |
| production-final-01 | P3 | feature:wallets | Change Password: the submit button sits behind the keyboard while typing the confirmation. |
| sign-08, sign-09 | — | QA | Not yet observed on a device (spinner too fast; camera denial not toured). |
