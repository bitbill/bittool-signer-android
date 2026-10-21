# End-to-end suite (`:e2e`)

The black-box acceptance suite of BitTool Signer for Android: the port of the iOS `BitToolSignerUITests` (18 flows),
`BitToolSignerUITestsLaunchTests` and `ScreenshotTour` (gaps.md G1–G2), driving the `apkDebug` app from outside through
UiAutomator and the debug UI-test harness (gaps.md G3, `app/src/debug/kotlin/com/bittool/signer/harness/DebugUiTestHarness.kt`).

## 1. Running it

The suite runs on **one emulator only: `emulator-5554`** (API 31, no screen lock). Other emulators on the machine
(`emulator-5556`, `emulator-5558`) must never be touched.

```bash
# the whole suite (18 flows + launch smoke + light and dark tour), about 22 minutes
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
| `support/Ui.kt` | queries (tag, tag prefix, text/description containing, exact label, heading title, segment of a segmented control), condition waits (150 ms polling, never fixed sleeps), scrolling and taps inside the **visible band** (see §4), whole-page searches for absences (`anywhereOnPage`, §4), typing (`setText` with a check that it arrived whole; password fields report one bullet per character) |
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
| `BitToolSignerUiTests.testOnboardingKeepsImportIntentAndIsShownOnce` | G1.1 | UIT:23-56 | pass (13 s) |
| `BitToolSignerUiTests.testImportSealsWalletAndOpensWallets` | G1.2 | UIT:59-85 | pass (15 s) |
| `BitToolSignerUiTests.testCreateRequiresRevealBackupCheckAndPassword` | G1.3 | UIT:88-184 | pass (87 s) |
| `BitToolSignerUiTests.testShowPhraseAgainReturnsToTheSameConcealedDraft` | G1.4 | UIT:187-207 | pass (12 s) |
| `BitToolSignerUiTests.testBackupOffersRecoveryCardsTipsAndAConcealedQR` | G1.5 | UIT:210-264 | pass (36 s) |
| `BitToolSignerUiTests.testImportAcceptsOtherLanguagesAndAsksWhenAmbiguous` | G1.6 | UIT:267-298 | pass (21 s) |
| `BitToolSignerUiTests.testImportDraftSurvivesBackground` | G1.7 | UIT:301-328 | pass (15 s) |
| `BitToolSignerUiTests.testPastedRequestIsMatchedReviewedAndSignedWithPassword` | G1.8 | UIT:333-431 | pass (44 s) |
| `BitToolSignerUiTests.testRejectClearsTheReviewedRequest` | G1.9 | UIT:434-446 | pass (7 s) |
| `BitToolSignerUiTests.testApprovalNamesAKnownSpenderAndFlagsAnUnlistedOne` | G1.10 | UIT:451-485 | pass (76 s) |
| `BitToolSignerUiTests.testWalletsScanButtonOpensTheCameraDirectly` | G1.11 | UIT:490-510 | pass (8 s) |
| `BitToolSignerUiTests.testScanCardOpensCameraWithoutPassword` | G1.12 | UIT:513-534 | pass (7 s) |
| `BitToolSignerUiTests.testConnectAndAddressesUsePublicDataOnly` | G1.13 | UIT:539-625 | pass (180 s) |
| `BitToolSignerUiTests.testPasswordChangeProtectsRecoveryPhrase` | G1.14 | UIT:628-663 | pass (37 s) |
| `BitToolSignerUiTests.testImportingAnExistingPhraseResetsItsPasswordInPlace` | G1.15 | UIT:669-717 | pass (36 s) |
| `BitToolSignerUiTests.testRenameThenDeleteLastWalletReturnsToWelcome` | G1.16 | UIT:720-769 | pass (30 s) |
| `BitToolSignerUiTests.testAppLockColdLaunchPasswordFallbackAndShortBackground` | G1.17 | UIT:774-803 | pass (14 s) |
| `BitToolSignerUiTests.testSettingsReferencePagesAndGuide` | G1.18 | UIT:806-916 | pass (220 s) |
| `BitToolSignerLaunchTests.testLaunchScreenshot` | G1.19 | LaunchTests:4-12 | pass (4 s) |
| `ScreenshotTour.tourLight` | G2 | TOUR `testTourLight` | pass (217 s), 71 of 71 shots |
| `ScreenshotTour.tourDark` | G2 | TOUR `testTourDark` | pass (222 s), 71 of 71 shots |

The two slowest flows spend their time on the Connect pages (G1.13: the animated QR code is never idle, so every query
waits out the 1 s idle timeout) and on scroll searches (G1.18: long lists, the whole-page link and absence searches of §4,
and a final negative search for "Offline Setup Guide" over the whole Settings page). The page searches of the review
(§7.1) cost G1.10 about 40 s, G1.13 about 25 s and G1.18 about 70 s.

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
- **Absences.** For the same reason an iOS "is not there" check (`XCTAssertFalse(… .exists)`) on a page that scrolls
  searches the whole page (`Ui.anywhereOnPage`: up to the top, down to the end, then back to where the page was, so the
  next steps scroll as before; logcat records how far each search went): "Save or Print" (G1.5), "not in the bundled list" (G1.10), "Watch and sign by QR" and "Ownbit" on
  TRON (G1.13), "If you forget this password" (G1.15), "open in your browser" and the link checks (G1.18). It is never
  used on a bottom sheet (a drag at the top would pull the sheet down): after backgrounding, the scrolled Recovery Cards
  sheet is checked for every `cards.card.*` node and "Hide Cards" instead of card 1 alone.
- **Engine digest.** The Open Source page also shows the signing certificate's SHA-256 (64 hex digits as well), so the
  digest is looked for in the engine row (`openSource.digest`) only.
- **Sheet-scoped Cancel.** Where iOS taps the Cancel of a named bar (`navigationBars["Paste Request"]`,
  `navigationBars["Recovery Phrase"]`), the suite first asserts that title, then taps Cancel.
- **Emptied password fields.** `assertEmptySecureField` passes on an empty text or the placeholder only (gaps.md G1.0);
  a field the app failed to clear reports its bullets and fails.
- **Labels.** Compose keeps a merged node's texts on its children; a label is the texts and descriptions of a node and
  its descendants (each refreshed from the app, never from the accessibility cache). Selected/checked: `Role.Tab`
  (segments, chain buttons) reports *selected*, other selectables and toggles *checked*.
- **Alerts.** "No alert appears" is "no new app window appears" (dialogs and sheets are windows of their own).
- **Links.** "No link" is "no `ClickableSpan` in any text of the app's windows", over the whole page.
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

2026-10-21, `main` at `d9f37ab` plus this module with the review changes (§7.1), `emulator-5554` (Pixel 3a AVD, API 31,
arm64, 1080 × 2220, 440 dpi): **21 of 21 passed** in 21 min 53 s (`BUILD SUCCESSFUL`; the JUnit XML records
`emulator-5554` for every suite): 18 flows, the launch smoke, and both tours with all 71 iOS shot names in light and in
dark (142 PNGs plus `launch.png` in `build/e2e-shots/`). The shots were looked at: they show the screens (FLAG_SECURE off
through the harness), e.g. `light-22-review`, `dark-15b-connect-tron-index`, `dark-06-password-confirm-focus` (the
keyboard's action key reads Done: the confirmation field has the focus), `light-46a-open-source`, `dark-51-app-lock-password`.
(The module's first run, before the review, also passed 21 of 21, in 20 min 58 s.)
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

### 7.1 Review (branch `test/e2e-reviewed`)

The port was checked test by test against `BitToolSignerUITests.swift`, `BitToolSignerUITestsLaunchTests.swift` and
`ScreenshotTour.swift`: same names, steps and assertions, condition waits only (polling, `Until`, `waitForIdle`), a
fresh harness run per test (`bts.uitest.run` = new UUID + reset; G1.17 and the tour's lock phase reuse their own run on
purpose), `emulator-5554` only (the guard refused `ANDROID_SERIAL=emulator-9999` before installing anything), the 71
iOS shot names in each theme. The constants are byte-identical to UIT:9-16, :273, :287. What the review changed:

| Finding | Effect before | Change |
|---|---|---|
| Absences were checked on the visible screen only, where XCUITest also sees what a scroll view holds off screen | "Save or Print", "not in the bundled list", TRON's "Watch and sign by QR" / "Ownbit", "If you forget this password", "open in your browser" and both link checks could pass with the text further down the page | whole-page search (§4 "Absences"); every search reached both ends of its page |
| The Recovery Cards sheet is scrolled to card 2 before backgrounding; only `cards.card.0` was checked afterwards | cards still revealed after backgrounding could pass with card 1 above the screen | no `cards.card.*` node and no `cards.hide` |
| The engine digest was any 64-hex text on the Open Source page | the signing certificate's SHA-256 satisfied it while the engine row read "Reading…" or "Unavailable" | looked for in `openSource.digest` only |
| `assertEmptySecureField` also passed on any text without bullets | wider than gaps.md G1.0 | empty or the placeholder only |
| "Paste Request" (G1.8) and "Recovery Phrase" (G1.14) Cancel tapped any "Cancel" | the sheet's title was not checked | the title is asserted first |
| `06-password-confirm-focus` was taken right after the IME action, without checking where the focus went | the shot could show the first field still focused | the tour waits until the confirmation field is focused (a probe showed the action does move it; the keyboard redraws its action key a moment later) |

The first full run of the review (page searches returning to the top) failed G1.18 once: after the link search of the
24-wallet directory, the iOS `scrollToExistence` (6 swipes up first) no longer reached `directory.submit` at the end of
the list. The search now returns to where the page was; the final run passed 21 of 21 (§6).

App defects: still none. Not changed: "exactly one Export as File" (G1.8) counts the screen after scrolling to the
export, which includes the pinned bar; the password-field absences on the Pair pages stay on-screen checks (the pages
have no field; a scan of the animated pages costs about a minute each).
