# Integration backlog (from the Wave 1 / Wave 2 review reports)

Items the package reviewers could not fix inside their own paths. Owner = the module that must change.
QA round 1 adds device findings in `docs/qa/round1-*.md`. ✅ = fixed and reviewed (branches fix/backlog-*-reviewed, merged).

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
| B16 | feature:sign | Authorize sheet insets with the keyboard open: verify on device; the sheet opens at full content height. | W2-sign |
| B17 | feature:settings | Owner questions: which tag "check out the tag for version 1.0.0 (1)" names; the in-app build steps omit `--ignore-scripts`; "Deleted wallet" row subtitle; the Language page staying open after a change. | W2-activity-settings |
| B18 | app | Android Terms / Privacy texts (apk, play) are drafts awaiting legal review. | W2-shell |
| B19 | release | Refresh `THIRD_PARTY_LICENSES_ANDROID.txt` from the final dependency set (include vendored QuickJS, MIT). | W1-data |
| B20 | core:l10n | Every `strings_<area>.xml` is English only: translation pass into the 20 other languages. | all |

## Found by the backlog reviewers (open)

| # | Owner | Item |
|---|---|---|
| B21 | feature:setup | Re-importing the phrase of a wallet whose device key is lost switches setup into Reset Password, which then fails with "device key unavailable…". Detect the lost key and point the user to "Remove This Wallet" (B9) instead. |
| B22 | feature:wallets | Delete Wallet first renders the normal page, then switches to "This Wallet Can’t Be Unlocked" after the key check (~1 s on a device): show a neutral loading state until the check answers. |
| B23 | core:crypto | `TestKeystore` is a public production-source object building weaker test-policy keys; guard it (`@VisibleForTesting` + lint, or move it behind a test-only artifact). |
| B24 | app | `FixtureDeviceKeyStore` keeps `isKeyLost = false`, so e2e can never reach the lost-key removal; add a harness switch. |
| B25 | app | `UnableToContinueDialog` shows store/engine error text in the interface direction; iOS shows it as given (`contentDirection()`). |
| B26 | core:designsystem / core:ui | `DropdownMenu` popups (ProfileMenu, App Lock wallet menu…) do not publish test tags as resource ids. |
| B27 | core:ui | Verify that PasswordField's semantics for autofill (`InputText`, `FillableData`) never carry the plain password (relies on the app-wide autofill exclusion today). |
| B28 | app | Pre-existing lint error: RestrictedApi in `SignerNavigation.kt:84`. |
