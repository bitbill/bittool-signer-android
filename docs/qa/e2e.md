# End-to-end suite (`:e2e`)

The black-box acceptance suite of BitTool Signer for Android: the port of the iOS `BitToolSignerUITests` (18 flows),
`BitToolSignerUITestsLaunchTests` and `ScreenshotTour` (gaps.md G1–G2), driving the `apkDebug` app from outside through
UiAutomator and the debug UI-test harness (gaps.md G3, `app/src/debug/kotlin/com/bittool/signer/harness/DebugUiTestHarness.kt`).

## 1. Running it

The suite runs on **one emulator only: `emulator-5554`** (API 31, no screen lock). Other emulators on the machine
(`emulator-5556`, `emulator-5558`) must never be touched.

```bash
# the whole suite (18 flows + launch smoke + light and dark tour), about 21 minutes
ANDROID_SERIAL=emulator-5554 tools/gradle-slot.sh ./gradlew :e2e:connectedDebugAndroidTest

# one class
ANDROID_SERIAL=emulator-5554 tools/gradle-slot.sh ./gradlew :e2e:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.bittool.signer.e2e.ScreenshotTour

# some tests (AGP passes only the first item of a comma-separated `class` list: use tests_regex)
ANDROID_SERIAL=emulator-5554 tools/gradle-slot.sh ./gradlew :e2e:connectedDebugAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.tests_regex=.*(testRejectClears|tourDark).*"
```

- **Device selection.** AGP runs connected tests on every connected device unless `ANDROID_SERIAL` names one. Its
  `--serial` option is broken in AGP 9.4.1 (`DeviceProviderInstrumentTestTask.getFilteredDevices` removes from the
  immutable list of the Gradle property, `UnsupportedOperationException`). The `:e2e` connected tasks therefore refuse to
  start unless `ANDROID_SERIAL` equals `-Pe2e.serial` (default `emulator-5554`); nothing is installed before that check.
- **Device lock.** `tools/gradle-slot.sh` takes the shared emulator lock for any command containing `connected`.
- **Device setup.** `DeviceSetupRule` (a class rule) sets the three animation scales to 0 for the run and restores the
  previous values afterwards (gaps.md G1.0). The app's language is the harness default `en-US`.
- **Results.** `e2e/build/outputs/androidTest-results/connected/debug/` (JUnit XML, and one logcat per test) and
  `e2e/build/reports/androidTests/connected/debug/index.html`. A failure message carries a description of the screen
  (every window, one line per node: tag, text, description, state, flags, bounds) with recovery words, word chips, card
  words, the phrase editor and the recovery QR **redacted**; the same description goes to the test's logcat.
- **Screenshots.** Written on the device by the shell (`screencap`) to the instrumentation argument `shotDir` (default
  `/data/local/tmp/bittool-e2e-shots`, a shell-owned folder that survives AGP's uninstall after the run) and pulled to
  **`build/e2e-shots/`** by `:e2e:pullE2eShots`, which finalizes every connected run (also a failed one).

## 2. Module

`e2e/` is a `com.android.test` module (`targetProjectPath = ":app"`, `missingDimensionStrategy("distribution", "apk")`,
debug only), **self-instrumenting** (`android.experimental.self-instrumenting`), so the test process is not the app's:
the suite force-stops and cold-launches the app like XCUITest does. Dependencies: androidx.test runner/core/ext-junit and
UiAutomator 2.4.0 (`libs.androidx.test.uiautomator`). The app's test tags are resource ids in debug builds
(`testTagsAsResourceId` at the root, and per window for dialogs, sheets and the lock window).

| File | Role |
|---|---|
| `support/Fixtures.kt` | the iOS constants, byte for byte (UIT:9-16, :273, :287, the passwords) |
| `support/SignerApp.kt` | `launch` (force-stop, `am start -W` with the `bts.uitest.*` extras, wait for the fixture cards or Welcome), `backgroundAndReactivate` (Home, wait until the activity is `STOPPED` in `dumpsys activity activities`, relaunch with the launcher intent), camera grant/revoke |
| `support/Ui.kt` | queries (tag, tag prefix, text/description containing, exact label, heading title, segment of a segmented control), condition waits (150 ms polling, never fixed sleeps), scrolling and taps inside the **visible band** (see §4), typing (`setText` with a check that it arrived whole; password fields report one bullet per character) |
| `support/SignerTestCase.kt` | the iOS helpers: `openSetup`, `assertStep`, `revealWords`, `blankPositions`, `chipLabels`, `fill`, `assertBlanksEmpty`, `assertEmptySecureField`, link detection, window counting |
| `support/Hierarchy.kt` | the redacted screen description of failure messages |
| `support/Device.kt` | shell, instrumentation arguments (`shotDir`, `extraArgs`), `DeviceSetupRule`, `Shots` |
| `BitToolSignerUiTests.kt` | G1.1–G1.18, one test per iOS test, same names and order of assertions |
| `BitToolSignerLaunchTests.kt` | G1.19 |
| `ScreenshotTour.kt` | G2, `tourLight` / `tourDark` |

## 3. Test map

Every iOS test has its Android test with the same name, the same steps and the same assertions (adaptations: §4). The
last column is the final run of 2026-10-21 (§6).

| Android test | gaps.md | iOS | Last run |
|---|---|---|---|
| `BitToolSignerUiTests.testOnboardingKeepsImportIntentAndIsShownOnce` | G1.1 | UIT:23-56 | pass (12 s) |
| `BitToolSignerUiTests.testImportSealsWalletAndOpensWallets` | G1.2 | UIT:59-85 | pass (17 s) |
| `BitToolSignerUiTests.testCreateRequiresRevealBackupCheckAndPassword` | G1.3 | UIT:88-184 | pass (92 s) |
| `BitToolSignerUiTests.testShowPhraseAgainReturnsToTheSameConcealedDraft` | G1.4 | UIT:187-207 | pass (18 s) |
| `BitToolSignerUiTests.testBackupOffersRecoveryCardsTipsAndAConcealedQR` | G1.5 | UIT:210-264 | pass (31 s) |
| `BitToolSignerUiTests.testImportAcceptsOtherLanguagesAndAsksWhenAmbiguous` | G1.6 | UIT:267-298 | pass (23 s) |
| `BitToolSignerUiTests.testImportDraftSurvivesBackground` | G1.7 | UIT:301-328 | pass (18 s) |
| `BitToolSignerUiTests.testPastedRequestIsMatchedReviewedAndSignedWithPassword` | G1.8 | UIT:333-431 | pass (49 s) |
| `BitToolSignerUiTests.testRejectClearsTheReviewedRequest` | G1.9 | UIT:434-446 | pass (9 s) |
| `BitToolSignerUiTests.testApprovalNamesAKnownSpenderAndFlagsAnUnlistedOne` | G1.10 | UIT:451-485 | pass (33 s) |
| `BitToolSignerUiTests.testWalletsScanButtonOpensTheCameraDirectly` | G1.11 | UIT:490-510 | pass (13 s) |
| `BitToolSignerUiTests.testScanCardOpensCameraWithoutPassword` | G1.12 | UIT:513-534 | pass (11 s) |
| `BitToolSignerUiTests.testConnectAndAddressesUsePublicDataOnly` | G1.13 | UIT:539-625 | pass (158 s) |
| `BitToolSignerUiTests.testPasswordChangeProtectsRecoveryPhrase` | G1.14 | UIT:628-663 | pass (41 s) |
| `BitToolSignerUiTests.testImportingAnExistingPhraseResetsItsPasswordInPlace` | G1.15 | UIT:669-717 | pass (29 s) |
| `BitToolSignerUiTests.testRenameThenDeleteLastWalletReturnsToWelcome` | G1.16 | UIT:720-769 | pass (30 s) |
| `BitToolSignerUiTests.testAppLockColdLaunchPasswordFallbackAndShortBackground` | G1.17 | UIT:774-803 | pass (24 s) |
| `BitToolSignerUiTests.testSettingsReferencePagesAndGuide` | G1.18 | UIT:806-916 | pass (150 s) |
| `BitToolSignerLaunchTests.testLaunchScreenshot` | G1.19 | LaunchTests:4-12 | pass (4 s) |
| `ScreenshotTour.tourLight` | G2 | TOUR `testTourLight` | pass (260 s), 71 of 71 shots |
| `ScreenshotTour.tourDark` | G2 | TOUR `testTourDark` | pass (221 s), 71 of 71 shots |

The two slowest flows spend their time on the Connect pages (G1.13: the animated QR code is never idle, so every query
waits out the 1 s idle timeout) and on scroll searches (G1.18: long lists, and a final negative search for "Offline Setup
Guide" over the whole Settings page).

## 4. Android adaptations (beyond gaps.md G1.20)

- **Typing.** UiAutomator sets text through accessibility (`ACTION_SET_TEXT`) and replaces where XCUITest appends. The
  recovery-phrase editor is set without a tap, so BitTool's own BIP39 keyboard (DECISIONS §11) does not come up; password
  fields are tapped first (the system keyboard comes up, as on iOS) and checked by their bullet count, because they never
  report the password itself (backlog B1).
- **Visible band.** Compose content scrolls under the top app bar, pinned bottom bars (the Scan review's sign bar, the
  setup buttons) and the tab bar, where accessibility still reports it on screen. A node counts as hittable only when its
  centre lies between those bars (and outside the status bar, navigation bar and IME); drags start inside the band (a bar
  owns its touches) and hold still before release, so a scroll never flings and always moves by a known distance.
- **Off-screen nodes.** Unlike XCUITest, UiAutomator only sees what is on screen: assertions on rows, the setup step
  header, the risk acknowledgement (the last list row while it is not pinned) and the signature's export scroll to them
  first.
- **Labels.** Compose keeps a merged node's texts on its children; a label is the texts and descriptions of a node and
  its descendants (each refreshed from the app, never from the accessibility cache). Selected/checked: `Role.Tab`
  (segments, chain buttons) reports *selected*, other selectables and toggles *checked*.
- **Alerts.** "No alert appears" is "no new app window appears" (dialogs and sheets are windows of their own).
- **Links.** "No link" is "no `ClickableSpan` in any text of the app's windows".
- **Open Source.** Android shows its own repository (`https://github.com/bitbill/bittool-signer-android`) as the app's
  source and the iOS repository as the engine's (DECISIONS §9.1); both are asserted.
- **Screenshots in the flows.** iOS `attach(...)` captures are not taken: the flows run with the production window
  policy (FLAG_SECURE), where captures are black; the tour runs with `bts.uitest.allowCapture=true`.
- **Biometrics.** The harness' fixed failure "Biometric authentication was cancelled. Use a wallet password to
  continue." replaces "Face ID was cancelled".

## 5. Screenshot tour

`tourLight` and `tourDark` write `<light|dark>-<name>.png` with the iOS names (`01-welcome` … `51-app-lock-password`,
gaps.md G2 table), so iOS and Android shots pair up. Shots holding throwaway words (`09d-backup-qr-revealed`,
`09e-recovery-cards-revealed`, `09g-verify-board`) stay in the shot folder only, never in a report. Like iOS
(`continueAfterFailure = true`), a screen that does not appear is recorded and the tour goes on; the test fails at the end
with every problem met. Variants: `-Pandroid.testInstrumentationRunnerArguments.extraArgs="fontScale=2.0"` (system font
scale for the run, restored afterwards) or `extraArgs="locale=ar"` (the harness' app language); use another `shotDir`
per variant.

## 6. Last run

2026-10-21, `main` at `d9f37ab` plus this module, `emulator-5554` (Pixel 3a AVD, API 31, arm64, 1080 × 2220, 440 dpi):
**21 of 21 passed** in 20 min 58 s (`BUILD SUCCESSFUL`): 18 flows, the launch smoke, and both tours with all 71 iOS shot
names in light and in dark (142 PNGs plus `launch.png` in `build/e2e-shots/`). The shots were looked at: they show the
screens (FLAG_SECURE off through the harness), e.g. `dark-22-review`, `dark-50-app-lock`, `light-31-activity-detail`.
Not run in this pass: the `extraArgs` variants (`fontScale=2.0`, `locale=ar`) and the device matrix of gaps.md G2 (compact
phone, tablet), which need other emulators.

## 7. Findings

**App defects: none.** Every flow of the iOS suite holds on Android as specified in gaps.md G1 (with DECISIONS §9.1 for
the Open Source page). Every test tag the suite needs exists; none had to be added.

What the runs did turn up (all fixed in the suite or outside the app):

| Finding | Evidence | Status |
|---|---|---|
| AGP 9.4.1's `--serial` option of connected tests crashes | `UnsupportedOperationException` at `DeviceProviderInstrumentTestTask.getFilteredDevices:547` (removes from the Gradle property's immutable list) | worked around: `ANDROID_SERIAL` plus the module's guard (§1) |
| AGP runs only the first test of a comma-separated `class` argument | `class=A,B` and `class=A#x,A#y` ran one test each | use `tests_regex` (§1) |
| Another process drives `emulator-5554` outside the device lock | logcat of the failing runs: `com.android.commands.monkey.Monkey` and `uiautomator dump` started by the shell user at 18:06 and 18:57, BitTool Wallet (`com.bittool.wallet.debug`) brought to the front over the app; 2 flows and the dark tour failed in those runs and passed on rerun; the final run had no such activity | outside the suite; a failure message now notes when another app is in the foreground |
| The import status updates asynchronously (the first BIP39 wordlist load) | "12 words" appeared after the check in the first run | the suite waits for labels (condition) |
| The address rows are replaced for a while after a chain switch | four consecutive clicks on `address.row.1` hit stale nodes right after `chain.evm` | the suite clicks a node only once it has stayed put; not a visible defect |
