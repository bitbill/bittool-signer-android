# Port spec: Ownbit 5.0 Android UI and platform patterns to reuse in BitTool Signer

Scope: what the Android implementers of BitTool Signer should take from Ownbit 5.0 (Compose + Hilt, the same
build-logic layout and `apk` / `play` flavors) for the eleven cross-cutting topics below, what to change for the
signer, and what Ownbit got wrong first (the pitfalls are recorded in its KDoc and `docs/impl/*`). It complements the
screen specs in this folder (`scan-sign.md`, `wallets-pairing.md`, `setup-backup.md`, `app-shell.md`,
`design-system.md`, `activity-settings.md`); where `DECISIONS.md` says something else, `DECISIONS.md` wins.

Citation conventions

- `ob:<module>/<file>:<line>` = `/Users/tanguopeng/AndroidStudioProjects/ownbitandroidnew/<module>/src/main/kotlin/io/ownbit/<module with . instead of />/<file>`,
  e.g. `ob:core/scanner/ScannerScreen.kt:88` = `…/core/scanner/src/main/kotlin/io/ownbit/core/scanner/ScannerScreen.kt` line 88.
  `ob-test:` is the same under `src/test/kotlin`; `ob-res:<module>/<path>` is relative to the module's `src/main`
  (e.g. `ob-res:core/scanner/AndroidManifest.xml`); `ob-doc:` is relative to the Ownbit repo root (`docs/impl/...`,
  `app/build.gradle.kts`, …).
- `BTS/<path>:<line>` = `/Users/tanguopeng/Documents/bitsigner-ios/BitSigner/BitToolSigner/<path>`.
- User-visible English strings are quoted verbatim from the iOS app (they are the localisation keys). Ownbit's own
  strings are **never** reused as text (they say "Ownbit", "Public protocol", PIN, …); where Ownbit has a translation
  for an identical English sentence it is called out explicitly.

---

## 0. Key facts and risks (read first)

1. **Ownbit's scanner uses bundled ML Kit, which silently adds `INTERNET` and `ACCESS_NETWORK_STATE`.**
   `com.google.mlkit:barcode-scanning` → `com.google.mlkit:vision-common` → `com.google.android.datatransport:transport-backend-cct`,
   whose AAR manifest declares both permissions (checked in `~/.gradle/caches/.../transport-backend-cct-3.1.9.aar`).
   The signer scaffold already uses ZXing (`core/scanner/build.gradle.kts`) and removes both permissions with
   `tools:node="remove"` (`app/src/main/AndroidManifest.xml`). Keep it that way and add a merged-manifest test (§11.5).
   ML Kit also reports binary QR codes with `rawValue == null`; Ownbit drops them (`ob:core/scanner/QrImageDecoder.kt:66`),
   which would lose the iOS "nonText" message (`BTS/Features/Sign/QRScannerView.swift:214-217`).
2. **Ownbit's public-protocol code is a BitSigner port with many deliberate deviations. Port its components, not its
   behaviour.** `ChainPicker`, `AddressIndexStepper`, `DetailLine`, `PayloadDisclosure`, `WalletsWorksWith` /
   `WalletLogo`, `AnimatedQrCard` are 1:1 ports of BitSigner views and are good starting points. Do **not** copy
   `PublicFrameGate`'s extra rules and 150 s timeout, TronLink / TokenPocket handling, Ownbit's `CompatibleWallets`
   data, the extra consent rules, the Kotlin verifier, the D43 "signature insight" card, `EngineVocabulary`, "Prepare",
   "Same as in Ownbit", or the 23-logo set without `wallet_bittool`. The divergence list is
   `ob-doc:docs/ios-diff/public-protocol.md` (rows 1–43); §3.8 below condenses it.
3. **The signer accent is exactly Ownbit's `openProtocol` token**: emerald `#0F7B55` (light) / mint `#6BE3A4` (dark)
   (`BTS/Assets.xcassets/AccentColor.colorset/Contents.json`), on-accent white / ink `#0B1F16`
   (`BTS/Design/DesignSystem.swift:6-11`). Ownbit verified WCAG AA for it and added `openProtocolOnSoft` = `#0B6E4B`
   for small text on the 12 % soft fill, where plain emerald reaches only 4.46 : 1 (`ob-doc:docs/impl/modules/core-designsystem.md` §2).
4. **Windows, not composables, decide what covers what.** Compose `Dialog`, `ModalBottomSheet` and `Popup` are separate
   windows; a custom `WindowManager` window (toast, HUD, lock) does not inherit `FLAG_SECURE` unless it copies it
   (`ob:core/designsystem/component/OverlayWindow.kt:55-71`); a lock implemented as a Compose `Dialog` is shrunk to
   `screenWidthDp × screenHeightDp` and moves 99 px on the first frame (`ob:feature/security/lock/LockWindow.kt:40-51`);
   **toggling** `FLAG_SECURE` on a visible window re-creates its surface and blinks on some devices
   (`ob:feature/security/lock/RecentsGuard.kt:28-31`). DECISIONS §6 (FLAG_SECURE once in `onCreate`, never toggled) is
   consistent with this.
5. **Haptics: Ownbit is OFF by default behind an app setting** (`ob:core/designsystem/haptics/OwnbitHaptics.kt:67-70`).
   BitTool Signer has no haptics setting; iOS plays 8 `sensoryFeedback` events (§9.1). Port the SDK-aware constant
   mapping, not the switch.
6. **Transitions: Ownbit measured that Material fade-through / shared-axis fades show an empty frame** (old page faded
   out before the new one fades in → the window background flashes) on every push and every tab switch
   (`ob:app/navigation/OwnbitNavHost.kt:137-149`, `ob:app/main/MainScreen.kt:167-174`). DECISIONS §7 asks for
   shared-axis / fade-through: implement it with overlapping (not sequential) fades over opaque pages and a window
   background equal to the page colour, or use Ownbit's `NavMotion` (§6.3). Tabs switch instantly.
7. **Secrets never leave memory**: no `rememberSaveable`, `SavedStateHandle`, route argument, `rememberTextFieldState()`
   or Bundle. Ownbit's patterns: scanner for secret payloads hosted **in place** (§1.7), `@Singleton` in-memory holders
   with ids in routes, `CharArray` hand-over wiped by the receiver, redacted `toString()`, a release no-op logger.
8. **Android cannot reject third-party keyboards** (iOS does app-wide, `BTS/Features/Setup/RecoveryPhraseViews.swift:177-181`).
   Ownbit's answer is its own BIP39 keyboard with no `InputConnection` at all (§4.1). Whether the signer adopts it is a
   product decision; at minimum every secret / phrase field gets `IME_FLAG_NO_PERSONALIZED_LEARNING`, no autocorrect and
   autofill exclusion (§5.2).
9. **Some iOS strings on these surfaces are not localised on iOS**: `"Reading request…"`,
   `"Keep the animated code in view · %lld parts"` (`BTS/Features/Sign/ScanTab.swift:20-24`), the six `QRScanFrameGate`
   messages (`BTS/Features/Sign/QRScannerView.swift:212-220`) and `"Screenshot Detected"`
   (`BTS/Features/Setup/RecoveryPhraseViews.swift:347-349`) are absent from `translations/*.json`. Ownbit ships 21-language
   translations for the first two with byte-identical English (`publicprotocol_scan_reading`,
   `publicprotocol_scan_parts`); reusing them is an l10n decision (iOS shows English).
10. **Robolectric/Roborazzi conventions that work**: record-only screenshots behind a Gradle property and excluded
    otherwise, `@Config(sdk = [34])`, `@GraphicsMode(NATIVE)`, locale qualifiers, `ar-u-nu-latn`, `FLAG_SUPPORTS_RTL` set
    by hand for dialog windows, a paused main clock plus a settle loop for off-main-thread QR encoding (§11).

---

## 1. Camera QR scanning (`:core:scanner` in Ownbit)

Ownbit files (`ob:core/scanner/…`): `ScannerScreen.kt` (host composable), `ScannerViewModel.kt` (delivery logic),
`camera/ScannerCamera.kt` (CameraX binding), `camera/BarcodeAnalyzer.kt` (ML Kit analyzer),
`camera/CameraPermissionFlow.kt` (pure permission state machine), `camera/ScanDeduplicator.kt`, `ui/ScannerContent.kt`
(stateless page: viewfinder, panels, progress pill), `QrImageDecoder.kt` (still images), `MnemonicDetector.kt`,
`publicqr/PublicFrameGate.kt` (public-protocol gate). Module doc: `ob-doc:docs/impl/modules/core-scanner.md`.
iOS counterpart: `BTS/Features/Sign/QRScannerView.swift` (UI, overlay, `QRScanFrameGate`, camera coordinator) and
`BTS/Features/Sign/ScanTab.swift:8-89` (`ScanSession`). Full iOS behaviour: `scan-sign.md` §6–§7, §23.3–§23.5.

### 1.1 Public API shape (worth copying, renamed)

```kotlin
// ob:core/scanner/ScannerScreen.kt:88-101
@Composable
fun ScannerScreen(
    onResult: (String) -> Unit,              // raw text, main thread, never logged
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,                 // false = pause analysis (host shows a dialog / is processing)
    keepScanningAfterResult: Boolean = false,
    allowsMnemonicPayload: Boolean = false,
    title: String = stringResource(L10nR.string.scan),
    hint: String = stringResource(L10nR.string.align_within_frame_to_scan),
    progress: ScanProgressUi? = null,        // animated-code reading progress
    badge: (@Composable () -> Unit)? = null,
    viewModel: ScannerViewModel = hiltViewModel(key = SCANNER_VIEW_MODEL_KEY),
)
```

For the signer the equivalent signature follows iOS `QRScannerView(title, hint, purpose, progress, status, error,
onReset, onScan)` (`BTS/Features/Sign/QRScannerView.swift:9-17`): defaults `"Scan Request"` and
`"Point the camera at the QR code in your other wallet."`; the recovery variant is opened with
`"Scan Recovery QR"` / `"Scan a QR code containing the words (such as BitTool Signer’s recovery phrase QR code) or a Standard SeedQR."`
(`BTS/Features/Setup/WalletSetupFlow.swift:218`). The keyed ViewModel (`hiltViewModel(key = …)`,
`ob:core/scanner/ScannerScreen.kt:230-231`) lets a host destination own other ViewModels too — keep that.

### 1.2 CameraX binding (copy almost verbatim)

`ob:core/scanner/camera/ScannerCamera.kt:102-275`:

- `PreviewView(context)` with `implementationMode = COMPATIBLE` (TextureView: behaves inside Compose layouts and
  navigation transitions) and `scaleType = FILL_CENTER` (:114-120), shown with `AndroidView`.
- One `CameraBinding` per composition inside `DisposableEffect(lifecycleOwner, previewView)` (:124-130): the binding
  uses `LocalLifecycleOwner` = the back-stack entry, so the camera stops with the page and resumes with it.
- `ProcessCameraProvider.getInstance(context).addListener(…, mainExecutor)` with a `released` flag checked first
  (:153-169) — a page left before CameraX finished initialising must not bind.
- Back camera, else front, else "unavailable" (:174-181).
- One `ResolutionSelector` for both use cases: 16:9 fallback auto, `ResolutionStrategy(Size(1920, 1080),
  FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)` (:264-273) — "enough pixels for a version-40 QR filling the viewfinder
  (~3.5 px per module) while staying inside the guaranteed PRIV + YUV stream combination". Matches iOS
  `hd1920x1080` (`BTS/Features/Sign/QRScannerView.swift:292`).
- `ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST` (:185-188) and a **single-thread analysis executor** shut down in
  `release()` (:143, :244-254).
- `cameraInfo.torchState` and `cameraInfo.cameraState` observed with the lifecycle owner (:150-151, :203-204):
  `CameraState.error != null` → "camera unavailable" (in use, disabled by policy, fatal).
- Tap to focus: `previewView.meteringPointFactory.createPoint(x, y)` + `FocusMeteringAction.Builder(point)
  .setAutoCancelDuration(3, SECONDS)` (:229-242, :262).
- `release()` clears the analyzer, removes both observers, unbinds **only its own use cases** (not `unbindAll()`),
  closes the decoder, shuts the executor (:244-254).
- ML Kit-only parts to drop for the signer: `ZoomSuggestionOptions` auto-zoom up to `MAX_AUTO_ZOOM = 5f` (:213-227,
  :260). ZXing has no equivalent; an optional manual pinch-to-zoom via `cameraControl.setZoomRatio` is allowed polish.

Adapt for the signer:
- Analyzer → ZXing on the Y plane (`PlanarYUVLuminanceSource` over a reused buffer, `QRCodeMultiReader`,
  `POSSIBLE_FORMATS = [QR_CODE]`, retry `invert()`), exactly as `scan-sign.md` §23.3. iOS reads **QR only**
  (`metadataObjectTypes = [.qr]`, `BTS/Features/Sign/QRScannerView.swift:302`); Ownbit also reads Data Matrix, Aztec,
  PDF417 (`ob:core/scanner/QrImageDecoder.kt:57-63`) — do not copy that list.
- Keep Ownbit's analyzer contract (`ob:core/scanner/camera/BarcodeAnalyzer.kt:20-47`): skip and `close()` the
  `ImageProxy` immediately while disabled; close it in `finally` after decoding (with `KEEP_ONLY_LATEST` CameraX hands
  over the next frame only after the previous `ImageProxy` is closed — "never more than one frame in flight"); post
  results to the main thread only `if (!released)`.
- Torch: iOS button top-right, hidden with `opacity(0)` but keeping its slot when unavailable
  (`BTS/Features/Sign/QRScannerView.swift:73-80`), torch forced off when the scene leaves `.active` (:58). Ownbit's torch
  is a 56 dp disc at the bottom (`ob:core/scanner/ui/ScannerContent.kt:153-167`) — use the iOS position. Turn the torch
  off on `ON_PAUSE` and on dispose (CameraX keeps it on otherwise until unbind).
- iOS runs the camera only while `scenePhase == .active` (`BTS/Features/Sign/QRScannerView.swift:29`); Ownbit binds to
  the entry lifecycle (STARTED). For iOS parity unbind on `ON_PAUSE` (a dialog or a split-screen focus loss pauses).

### 1.3 Permission flow (copy the state machine)

`ob:core/scanner/camera/CameraPermissionFlow.kt:11-58` is pure and unit-tested (`ob-test:core/scanner/camera/CameraPermissionFlowTest.kt`):

```kotlin
enum class CameraPermissionStatus { PENDING, GRANTED, RATIONALE, BLOCKED }
object CameraPermissionFlow {
    fun onEnter(granted: Boolean, alreadyRequested: Boolean, shouldShowRationale: Boolean) = when {
        granted -> GRANTED to false
        !alreadyRequested -> PENDING to true          // launch the system request now
        shouldShowRationale -> RATIONALE to false
        else -> BLOCKED to false
    }
    fun onRequestResult(granted: Boolean, shouldShowRationale: Boolean) = …
    fun onResume(current: CameraPermissionStatus, granted: Boolean) = if (granted) GRANTED else current
}
```

Pitfall recorded at :28-32: on first entry the order is "request → onResume → system dialog", so an unconditional
re-check in `onResume` stacks dialogs or binds twice — `onResume` may only **upgrade** to `GRANTED`, never prompt.
`requestedOnce` is `rememberSaveable` (`ob:core/scanner/ScannerScreen.kt:143`) so a rotation does not re-ask.
Settings intent with fallback: `ob:core/scanner/ScannerScreen.kt:247-256` (`ACTION_APPLICATION_DETAILS_SETTINGS` with
`Uri.fromParts("package", …)`, catch `ActivityNotFoundException` → `ACTION_SETTINGS`, `FLAG_ACTIVITY_NEW_TASK`).
"Never a black screen": every state renders an actionable panel (`ob:core/scanner/ui/ScannerContent.kt:114-133`).

Signer texts (iOS has one denied state, `BTS/Features/Sign/QRScannerView.swift:87-95`): icon `camera.fill` →
`"Camera Access Needed"`, `"Allow camera access in Settings to scan QR codes, or import the request as a file or pasted text."`,
button `"Open Settings"`. Android's extra RATIONALE state (may ask again) can reuse the same texts with the button
re-launching the request; a distinct "Allow" label would be a new Android string (DECISIONS §4). Camera errors map to
`"No camera is available. Import the request as a file instead."`, `"The camera is unavailable."`,
`"QR scanning is unavailable."`, `"This camera cannot read QR codes. Import a request file instead."`
(`BTS/Features/Sign/QRScannerView.swift:288-300`).

### 1.4 Delivery: de-duplication vs the iOS frame gate

Ownbit `ScanDeduplicator` (`ob:core/scanner/camera/ScanDeduplicator.kt:17-57`): SHA-256 keys only (no text kept), a code
is delivered when it appears, suppressed while in view, re-delivered after `DEFAULT_ABSENCE_MILLIS = 1_500L` out of view;
`refreshAll(now)` after any pause so a code that stayed in view during a dialog is not re-delivered ("no alert loops"),
`reset()` when the page is shown again. `ScannerViewModel` (`ob:core/scanner/ScannerViewModel.kt:59-190`) adds the
single-delivery guard (`finished`), a `screening` re-entrancy flag, Album decoding, and per-showing sessions (§1.6).

**Do not use the de-duplicator for the signer.** iOS semantics (`BTS/Features/Sign/QRScannerView.swift:207-239`):
`QRScanFrameGate.receive(value?, at: systemUptime)` → `.rejected(.tooLarge)` above `maximumBytes = 16_384` UTF-8 bytes,
`.rejected(.empty)` for whitespace-only, `.rejected(.nonText)` for `nil`, otherwise `.accepted(text)`; an outcome equal
to the previous one within 1 s is `.throttled`; identical animated frames are de-duplicated later by `ScanSession.seen`
(`BTS/Features/Sign/ScanTab.swift:56-63`). Keep Ownbit's good habits around it: monotonic clock injected for tests,
never log scanned text, pause analysis instead of tearing down the camera.

### 1.5 Overlay UI (geometry to adapt, constants to replace)

Ownbit `Viewfinder` (`ob:core/scanner/ui/ScannerContent.kt:181-293`): one `BoxWithConstraints` computes the frame;
a `Canvas` draws an even-odd mask (`PathFillType.EvenOdd`, full rect + rounded rect, 50 % black, :212-218), four
corner brackets as quadratic paths with round caps (:220-234), and an optional scan line; the hint / pill / badge are
laid out from the same geometry with `offset(y = frameTop + frame + …)`. Reduce motion = no animation
(`OwnbitTheme.reduceMotion`, read from `Settings.Global.ANIMATOR_DURATION_SCALE == 0f`,
`ob:core/designsystem/theme/OwnbitTheme.kt:327-335`). The page is always dark: `Color.Black` page, white texts, light
system-bar icons (`SystemBarIconsEffect(darkIcons = false, includeNavigationBar = true)`,
`ob:core/scanner/ScannerScreen.kt:169`), a nested `CompositionLocalProvider(LocalOwnbitColors provides OwnbitColors.Dark)`
for the badge (:263).

Replace Ownbit's constants (`FRAME_MAX = 300.dp`, `FRAME_WIDTH_FRACTION = 0.72f`, `FRAME_RAISE = 40.dp`,
`CORNER_RADIUS = 16.dp`, `CORNER_STROKE = 4.dp`, `CORNER_ARM = 32.dp`, scan line `3_000` ms, `:401-414`) with iOS's
(`BTS/Features/Sign/QRScannerView.swift:140-203`): side = `min(width − 64, height × 0.5, 340)`, centred 20 pt above
the middle, corner radius 28 (continuous), dim 0.55 when authorised else 0.8, brackets mint stroke 5 with arm
`min(w, h) × 0.16` and inner radius 26, **pulse** scale 0.985 ↔ 1.015 over 1.2 s ease-in-out autoreverse (no scan
line), progress = the rounded rect trimmed `0 … progress` inset 6, stroke 3, mint 0.9. The iOS status panel is a
material card at the bottom (radius 22, padding 18, max width 420) with a `ProgressView` (≤ 220 wide) plus
`"\(Int(progress * 100))%"` while `0 < progress < 1`, the status / hint text, a warning row for `error` with
`"Scan Again"` (when `onReset`), and the camera error in orange (:85-126). Top bar: `xmark` circle (`"Close"`), title,
torch circle (`"Turn on flashlight"` / `"Turn off flashlight"`) (:63-83). Status bar hidden (:49) → on Android hide the
status bar with `WindowInsetsControllerCompat` while shown and restore it on exit.

Ownbit's animated-code pill is still useful prior art for accessibility: one merged semantics node with
`ProgressBarRangeInfo` (indeterminate when unknown), nothing announced per frame
(`ob:core/scanner/ui/ScannerContent.kt:301-335`), and "no success haptic per part while `progress` is set"
(`ob:core/scanner/ScannerScreen.kt:120-123`; the host plays one success haptic when an animated request completes,
`ob:feature/tools/scan/ScanDestinations.kt:102-103`). iOS plays only `.error` on a new scanner error
(`BTS/Features/Sign/QRScannerView.swift:50`), nothing on success.

### 1.6 Sessions per showing (pitfall worth copying)

`ob:core/scanner/ScannerScreen.kt:112-140` + `ScannerViewModel.onScreenEntered` (`ob:core/scanner/ScannerViewModel.kt:105-121`):
a `rememberSaveable { UUID }` token per showing; a new token (the host hid and re-showed the scanner) drops pending
work, undelivered events (`while (_events.tryReceive().isSuccess)`), de-dup memory and the finished flag; the same token
coming back (page pushed on top then popped, rotation, app back from background via `ON_STOP` → `ON_START`) scans
afresh. The signer's equivalent is iOS `ScanSession.start/stop/reset` with its `token` (`BTS/Features/Sign/ScanTab.swift:26-53`);
model the Android `ScanSessionController` the same way and call `stop()` on dismiss (iOS `onDismiss:` of the full-screen
cover, `BTS/Features/Sign/ScanTab.swift:144`).

### 1.7 Scanning secrets: host the scanner in place (copy the pattern)

`ob-doc:docs/impl/modules/core-scanner.md` §1 "Secret scans in place": a scanned recovery phrase must never travel
through a navigation result (`SavedStateHandle`). The host keeps only a Boolean:

```kotlin
var scanning by rememberSaveable { mutableStateOf(false) }   // only a Boolean is saved
if (scanning) {
    ScannerScreen(onResult = { text -> viewModel.onScanned(text.trim()); scanning = false },
                  onBack = { scanning = false })
} else {
    ImportPhraseScreen(onScanClick = { scanning = true }, …)
}
```

Use this for the recovery-phrase scanner (iOS `QRScanPurpose.recoveryPhrase`). The signing scanner may be a route or an
in-place full-screen layer (iOS presents both as `fullScreenCover`). Ownbit's central mnemonic interception
(`allowsMnemonicPayload`, `ob:core/scanner/MnemonicDetector.kt:23-42`) is **Ownbit-only** — the iOS signing scanner
passes every accepted frame to the engine (`BTS/Models/AppStore.swift:330-350`); do not add an Ownbit-style tip.

### 1.8 Still images ("Album") and files

Ownbit's Album (`ob:core/scanner/QrImageDecoder.kt:87-173`, Photo Picker `PickVisualMedia.ImageOnly`,
`ob:core/scanner/ScannerScreen.kt:165-167`, back-port service in `ob-res:core/scanner/AndroidManifest.xml`) is **not an
iOS signer feature** (iOS offers "Paste Request" and "Import File", `BTS/Features/Sign/ScanTab.swift:123-126`). If ever
added, keep its safeguards: ≤ 24 MP decoded directly, larger photos down-sampled to ≤ 4096 px with the EXIF rotation
(`:167-171`), IO dispatcher, `SecurityException` caught. For "Import File" use `ActivityResultContracts.OpenDocument`
(no storage permission), size limit 512 000 bytes, PSBT magic `70 73 62 74 ff` → Base64, else strict UTF-8, else
`"Choose a PSBT file or a text file containing a UR request."` (`BTS/Features/Sign/ScanTab.swift:221-237`).

### 1.9 Animated-code reading and the engine scan session (prior art only)

Ownbit `PublicScanSession` (`ob:core/publicsigner/scan/PublicScanSession.kt:88-323`): one engine call in flight, frames
arriving meanwhile queued (`PublicFrameQueue`, capacity 32), an `epoch` counter so a result of a closed session is
dropped (:215-265), `CancellationException` puts the frame back so the code's next loop delivers it again (:222-231),
every engine refusal ends the session. The home-scanner host (`ob:feature/tools/scan/ScanDispatchViewModel.kt:195-236`)
shows indeterminate progress for the first part, then the session's latest progress (parts may finish out of order),
and pauses the camera (`scannerEnabled = false`) while classifying / alerting.

The signer must reproduce iOS `ScanSession` (`BTS/Features/Sign/ScanTab.swift:8-89`) instead: `seen` set of raw frames,
FIFO `queue`, `processing` flag, `token` per session, `securityEpoch` check after every engine call (a backgrounded call
re-queues its frame at the front), `progress = max(progress, value)`, first error stops the session and clears the
queue, `status` = `"Reading request…"` while the first part is processed, then
`"Keep the animated code in view · \(received) parts"` (English on iOS, see §0.9). Not from Ownbit: the gate's
`TIMED_OUT` after `NO_PROGRESS_TIMEOUT_MILLIS = 150_000L` (`ob:core/scanner/publicqr/PublicFrameGate.kt:305-310`),
`OTHER_REQUEST`, stray-code filtering, the 4300-char / 2048-frame / 1 MiB pre-checks — the engine decides on iOS.

---

## 2. QR rendering and animated QR display

Ownbit files: `ob:core/ui/qr/QrEncoder.kt` (ZXing encoder → module matrix → bitmap), `QrCodeImage.kt` (static code),
`AnimatedQrCard.kt` (static or multi-frame, **a port of BitSigner `QRCodeView`**), `QrBrightness.kt`,
`ob:core/ui/window/WindowEffects.kt` (keep-screen-on, max brightness). iOS: `BTS/Design/QRCodeView.swift:6-111`.

### 2.1 Encoder (copy)

`ob:core/ui/qr/QrEncoder.kt:57-122` uses `com.google.zxing.qrcode.encoder.Encoder.encode(content, ecLevel, hints)`
directly (not `QRCodeWriter`, which scales and pads) to get the raw module matrix, adds its own quiet zone, and
renders **one pixel per module**:

```kotlin
val code = Encoder.encode(content, options.errorCorrection.zxing, hints)   // hints: CHARACTER_SET only when wanted
val inner = code.matrix                                                     // ByteMatrix, 1 = dark
```

Drawing (`ob:core/ui/qr/QrCodeImage.kt:101-117`, `AnimatedQrCard.kt:204-223`): a `Canvas` with
`drawImage(image, srcSize = IntSize(n, n), dstOffset, dstSize = IntSize(cell * n, cell * n), filterQuality =
FilterQuality.None)` where `cell = side / modules` (integer) and the drawn square is centred — crisp edges at any size,
no large bitmaps. This is exactly what iOS does (`interpolation(.none)` on a 1-px-per-module `CGImage`,
`BTS/Design/QRCodeView.swift:4-19, 60-63`).

Per-frame options for the signer = iOS `QRRenderer` (`BTS/Design/QRCodeView.swift:11-18`): error correction **M up to
600 UTF-8 bytes, L above**; iOS always feeds `Data(text.utf8)` (byte mode). Ownbit's `animatedQrOptions`
(`ob:core/ui/qr/AnimatedQrCard.kt:180-192`) keeps the same M/L rule but sets `CHARACTER_SET = UTF-8` only for non-ASCII
text (so upper-case UR can use ZXing's denser alphanumeric mode) and `margin = 0` (the plate's padding is the quiet
zone). Both decode to the same text; prefer Ownbit's variant (fewer modules, easier to scan) unless byte-identical QR
images are required. Pitfall: with `CHARACTER_SET` set, ZXing writes an ECI segment for byte mode; very old scanners
choke on ECI — another reason to set it only for non-ASCII.

### 2.2 Animated card (port, then align to iOS)

`ob:core/ui/qr/AnimatedQrCard.kt:89-175` behaviour worth keeping:
- All frames are encoded **once, off the main thread, before the first one is drawn** — no white flash between parts;
  state is written back on `Dispatchers.Main.immediate` (:112-121; see §11.3 for why).
- The frame loop is a `LaunchedEffect(frames, ready, paused)` with `delay(frameMillis)` — no `Animatable`, no fade:
  "the motion carries data, so frames switch without a fade", also with reduce motion (:72-74, :122-128).
- Controls row: 36 dp play / pause disc inside a 48 dp touch target (`minimumInteractiveComponentSize()`), a 140 dp
  `LinearProgressIndicator` of `(index + 1) / count` without stop indicator, an `"i/n"` counter whose width is measured
  once at its widest value (`"$count/$count"`) so the row never shifts (:240-292).
- Accessibility: the plate is one node whose label is the part text, exposed as a polite live region **only while
  paused** (`describedPart` updates on pause), so TalkBack is not spammed four times a second (:107-108, :141-149).

Align with iOS (`BTS/Design/QRCodeView.swift:28-111`): `interval = 250 ms` (Ownbit `ANIMATED_QR_FRAME_MILLIS = 250L`,
:178); white plate radius 24 with shadow black 8 % radius 16 y 6 (Ownbit uses a 1 dp border in light mode instead) and
18 pt inner padding (Ownbit 14 dp, :200); `maxSide` per call site (320 default; 300 Connect coin level, 360 wallet level,
340 signature result, 250 recovery QR — `BTS/Features/Wallets/ConnectView.swift:225, 393`,
`BTS/Features/Sign/SignatureResultView.swift:27`, `BTS/Features/Backup/RecoveryBackupViews.swift:99`); a grey spinner
until ready (Ownbit shows an empty white plate); a11y label `"Animated QR code, part %lld of %lld"` for several frames,
`"QR code"` for one; buttons `"Play QR animation"` / `"Pause QR animation"`; plate forced light
(`.environment(\.colorScheme, .light)`, :72). iOS can be handed pre-rendered `initialImages` "so the view never changes
during a navigation push" (:41-51) — on Android pre-encode the matrices in the ViewModel (Default dispatcher) before
navigating and pass them in.

Ownbit additions **not** in iOS — product decision, default: drop.
- `QrDisplayEffects()` = `FLAG_KEEP_SCREEN_ON` while a code is shown (`ob:core/ui/window/WindowEffects.kt:20-50`,
  reference-counted per window by `WindowRegistry`, :92-141). Harmless polish for animated codes if wanted.
- The "Normal / Bright" brightness capsule (`ob:core/ui/qr/QrBrightness.kt:53-126`) — extra UI with Ownbit strings; do
  not port.
- The in-memory LRU of encoded public codes (`ob:core/ui/qr/QrCodeImage.kt:147-159`, max 16) — "Only for public data";
  never for recovery-phrase QRs.
- The encode-error text (`qr_code_format_or_content_is_incorrect`, `AnimatedQrCard.kt:225-238`) has no iOS string.

---

## 3. Cold-wallet / public-signer UI (closest prior art to BitTool Signer screens)

Ownbit split: services + shared pairing panel + identity components in `ob:core/publicsigner/…`; entry, review and
result pages in `ob:feature/coldwallet/publicsign/…`; module docs `ob-doc:docs/impl/modules/core-publicsigner.md` and
`ob-doc:docs/impl/modules/feature-coldwallet.md` §3c. The UI is "BitSigner `ConnectView` / `RequestReviewView` in
Ownbit's grouped style".

### 3.1 Pairing panel ↔ iOS `ConnectView`

`ob:core/publicsigner/ui/PublicPairingPanel.kt:78-185` is a stateless-content + Route split:
`PublicPairingPanel(walletId, …, viewModel = hiltViewModel(key = "publicPairing:$walletId"))` collects state and builds a
`PublicPairingActions` lambda bag once (`remember(viewModel, …)`, :89-105), then calls `PublicPairingContent(state, …)`
which previews / screenshots render directly. Top to bottom (`ReadyBody`, :188-248): chain picker → chain options →
QR (`AnimatedContent(targetState = qrKey, fadeIn togetherWith fadeOut)`, :208-230) → "Export as" segmented row →
verification card → notes → works-with → connection flow. QR placeholder: nothing for the first 200 ms, then a skeleton
plate of the final size — no jump (`rememberDelayedLoading`, :390-397).

**Request-key guard (copy — it is BitSigner's `requestKey`)**: the UI state keeps the selection and the last QR state
separately; `currentQr` is non-null only when the QR's own selection + index equals the current one
(`ob:core/publicsigner/ui/PublicPairingViewModel.kt:129-162`), and `load()` cancels the previous job and applies a
result only `if (it.selection == sel && it.requestTronIndex == tron)` (:286-315). iOS:
`BTS/Features/Wallets/ConnectView.swift:133-137, 185, 318-332`.

**Typed-number debounce (copy)**: stepper taps apply at once, typed digits after `TYPED_INDEX_DEBOUNCE_MILLIS = 300`
(`PublicPairingViewModel.kt:350-369, 514-515`) — "typing "57" never makes the QR of #5 first". Selection survives
process death in `SavedStateHandle` under namespaced keys (`publicPairing.chain`, …, :487-512) — public data only.

Do not copy: TRON index range 0 … max(99, Ownbit TRX index) and Ownbit's TRON scheme (iOS: 0 … 2³¹−1, standard path,
`BTS/Features/Wallets/ConnectView.swift:476`), "Prepare" (iOS repairs public data differently), the security-log hooks
(`ScHooks.xpubExported`, :399-407), "Same as in Ownbit", "How signing works" inside the panel (iOS has it on the Scan
tab), the connection-flow card.

### 3.2 Components that are direct BitSigner ports (port, re-skin, use iOS data/strings)

| Ownbit component | iOS source | Notes for the port |
|---|---|---|
| `ChainPicker` `ob:core/publicsigner/ui/ChainPicker.kt:61-176` | `BTS/Features/Wallets/ConnectView.swift:27-76` | Track = segmented track, radius 18, 4 dp padding, `selectableGroup()`; tile radius 14, selected fill accent 14 % + 1.5 dp accent outline at 85 % (`animateColorAsState`), logo scale 1.06 unless reduce motion; unselected logo 35 % saturation at 60 % alpha via a `ColorMatrix` save-layer (:166-176); names hidden from `fontScale ≥ 1.6` (`HIDE_NAMES_FONT_SCALE`), semantics `Role.Tab` + `selected` + brand name; selection haptic (iOS `.sensoryFeedback(.selection, trigger: selection)` :76). Use BitTool chain drawables, not Ownbit coin logos. |
| `AddressIndexStepper` `ob:core/publicsigner/ui/AddressIndexStepper.kt:69-222` | `BTS/Features/Wallets/ConnectView.swift:476` (`AddressIndexControl`) | Capsule `BasicTextField` (digits only, Arabic-Indic → Latin via `asciiDigits`, `MAX_TYPED_DIGITS = 10`, clamp to max, empty field restored on blur, IME Done clears focus, leaving the field commits at once, :115-171); −/+ capsule with 44 × 36 dp targets, ends disabled at 0 / max, selection haptic (:173-219); path under it in monospace LTR. Max = the iOS range. |
| `DetailLine` `ob:core/publicsigner/ui/PairingVerificationCard.kt:144-159` | iOS `DetailLine` (`BTS/Design/DesignSystem.swift:263-289`) | Caption label above a 13 sp monospace LTR value, merged semantics. iOS value is `.textSelection(.enabled)` → wrap the value in `SelectionContainer`. |
| `PayloadDisclosure` `ob:core/publicsigner/ui/PayloadDisclosure.kt:63-181` | `BTS/Design/PayloadDisclosure.swift` | Collapsed row in accent, chevron rotates 90° (mirrored in RTL), `AnimatedVisibility(expandVertically + fadeIn)`; monospace box with horizontal scroll; `DISPLAY_LIMIT = 30_000` chars laid out, Copy copies everything; `summary()` formats `"ur:<type> · CBOR · 162 B"`, `"BBQr · PSBT (Base32) · 3,120 B"`, `"UTF-8 · 42 B"` with `Locale.US` grouping (:151-181). Drop the TronLink branch. |
| `WalletsWorksWith`, `WalletLogo`, `WalletLogos` `ob:core/publicsigner/ui/WalletsWorksWith.kt:61-245` | `BTS/Features/Wallets/ConnectView.swift:602-654` (`IntendedWalletsView`, `WalletGroup`) | Adaptive grid: columns = `(maxWidth + 10) / (140 + 10)`, 260 dp min from `fontScale ≥ 1.3`, rows padded with weighted spacers (:102-136); logo clipped to the app-icon corner 22.5 % with a 0.5 dp hairline at 8 % (:164-180); explicit id → drawable table checked by a test, never looked up by name (:207-245); platforms joined with " · ", each wrapped in FSI…PDI (`⁨…⁩`) so Arabic keeps the order (:182-199). iOS `platforms` are fixed English strings (`"iOS · Android"`, `"Desktop"`, `"Extension · iOS · Android"`, `BTS/Models/CompatibleWallets.swift:69-132`). |
| `ProtocolStepper` `ob:core/publicsigner/ui/identity/ProtocolStepper.kt:38-90` | none (Ownbit identity marker) | Pattern only: one merged node "Step n of 3, …", Latin zero-padded numbers `"%02d".format(Locale.ROOT, n)`, hairline connectors, mirrors in RTL. |

Logos: Ownbit ships 23 WebP files `ob-res:core/publicsigner/res/drawable-nodpi/ic_wallet_<id>.webp`, converted from
BitSigner's 180 × 180 `wallet_<id>.imageset/icon.png` with Lanczos-3 to 120 × 120, WebP lossless, pixel-checked
(`ob-doc:docs/impl/assets/wallet-logos.md`). Reuse them, and convert the missing `BTS/Assets.xcassets/wallet_bittool.imageset/icon.png`
the same way (Ownbit omitted it). The data must be iOS's (`BTS/Models/CompatibleWallets.swift`): TronLink is
**watch-only** there (:122-127), BitTool Wallet is the wallet-level signer (:131-133), each wallet has a `site`
(:33-58), and there is a `directory` (:153-162). Ownbit's `CompatibleWallets.kt` (`ConnectMethod`, `tronSign`) is wrong
for the signer.

### 3.3 Review page ↔ iOS `RequestReviewView`

`ob:feature/coldwallet/publicsign/PublicReviewScreen.kt` + `PublicReviewViewModel.kt` + `PublicAckBar.kt` +
`PublicSummaryCard.kt` + `PublicSigningWalletCard.kt`; pure presenter `publicsign/domain/PublicReviewPresenter.kt`
(coordinator state → page model). Patterns to copy:

- **Route / Screen / Actions split** (`PublicReviewScreen.kt:89-148`): `PublicReviewPage` collects state, runs a
  `LaunchedEffect(viewModel) { viewModel.events.collect { … } }` for one-shot navigation, and builds a
  `PublicReviewActions` class of lambdas once; `PublicReviewScreen(state, actions)` is stateless.
- **Consent reset on the page's own `ON_STOP`**: `LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onStop() }`
  (:106-108) withdraws the acknowledgement and closes an idle password dialog (`PublicReviewViewModel.kt:343-356`).
  Also reset when the request hash changes and after every signed task (:123-136, :200-204). iOS resets on
  `securityEpoch`, on `requestHash` change and after each authorisation (`BTS/Features/Sign/RequestReviewView.swift:57-65`)
  — the signer's background edge comes from the app lifecycle monitor (`app-shell.md` §15.4), not the entry's `ON_STOP`
  (which also fires when another page is pushed; decide which one iOS parity needs).
- **Acknowledgement row** (`PublicAckBar.kt:101-…`): whole row `toggleable(role = Role.Checkbox)`, warning tint 12 % /
  16 % dark, selection haptic — iOS `acknowledgement` (`BTS/Features/Sign/RequestReviewView.swift:156-189`:
  `.snappy(0.15)` toggle, `.sensoryFeedback(.selection)`, value "Checked" / "Not checked"). `riskAcknowledged = true` is
  passed only if the bar asked for consent and it is ticked (`PublicReviewViewModel.kt:178-180`) — same rule as iOS
  `blocked = review.requiresAcknowledgement && !riskAccepted` (:188).
- **Password per task in a dialog whose own loading shows progress** (no HUD over the dialog), `CharArray` wiped in
  `finally` (`PublicReviewViewModel.kt:166-196`), outcomes mapped to field errors with an `errorCount` that drives the
  shake (`:198-225`). iOS uses a sheet (`AuthorizeSheet`, `BTS/Features/Sign/RequestReviewView.swift:821-907`) with
  `PasswordField(.walletPassword)` — keep the sheet form; reuse the error-count / wipe logic.
- **Severity banners** (`PublicReviewScreen.kt:276-302`): danger = red on `dangerSoft`, warning = orange on
  `warningTipBg`, info = brand on `infoSoft`, icon 16 dp top-aligned, radius 14, padding 14 × 12. iOS `Callout`
  tones; prefixes `"HIGH RISK"` / `"UNLIMITED"` / `"HIGH FEE"` are red in both apps.
- **Collapsible long values** (`:376-403`): first 280 chars + "…", toggle in accent, `rememberSaveable(row.index, row.value)`
  for the expanded flag only — the same 280-character rule as iOS (`BTS/Features/Sign/RequestReviewView.swift:717-742`, `scan-sign.md` §11.9).
- **Gone request** (`:183-191`): process death or a discarded request shows an empty state and closes; the request
  itself lives only in a `@Singleton` memory store addressed by a random id in the route
  (`ob-doc:docs/impl/modules/core-publicsigner.md` §8.2). For the signer, `SignerStore` holds the single review (iOS
  `AppStore.review`); do not put request JSON in the route.
- **Reject with partial signatures** asks first (destructive alert, `PublicReviewViewModel.kt:310-332`).

Untrusted text (`ob:core/ui/text/UntrustedText.kt:36-140`): escapes control, bidi, format, default-ignorable and
stacked-mark characters as visible `⟨U+XXXX⟩` with a warning-orange monospace span, caps at 256 code points. iOS has
**no** equivalent (it renders engine text as is). Optional hardening; adopting it changes what is displayed — product
decision, default: not in v1.

### 3.4 Result page ↔ iOS `SignatureResultView`

Ownbit result (`ob:feature/coldwallet/publicsign/PublicSignResultScreen.kt`): replaces the review, no back arrow, system
back = Done, Done discards the request while the answer stays on screen during the exit transition
(`ob-doc:docs/impl/modules/feature-coldwallet.md` §3c "Result page"). iOS: `QRCodeView(frames: store.signatureFrames,
maxSide: 340)`, one prominent `"Done"` (`checkmark`) that calls `store.clearRequest()` inside `withAnimation`, success
haptic when it appears (`BTS/Features/Sign/SignatureResultView.swift:27, 60, 68`). Ownbit's `PagedQrCard` (TronLink) is
not needed.

### 3.5 Scan-tab entry ↔ iOS `ScanHomeView`

Ownbit's entry page (`ob:feature/coldwallet/publicsign/PublicSignHomeScreen.kt`) has a scan card + paste sheet; the
paste is processed under a clear HUD and a refusal stays in the sheet with its text. iOS `ScanHomeView`
(`BTS/Features/Sign/ScanTab.swift:107-217`): whole-card button (`PressableCardStyle`: scale 0.98, opacity 0.9,
`.snappy(0.18)`), two tiles `"Paste Request"` / `"Import File"`, the four `StepRow`s, and the camera opened from
elsewhere through a token (`AppRouter.scanRequest` / `consumeScanRequest`, `BTS/Features/Main/AppRouter.swift:31-76`).

### 3.6 Engine bridge prior art (for the QuickJS host)

`ob-doc:docs/impl/modules/core-jsengine.md` §11: one entry point `BitSignerEngine.dispatch(json)` with an operation
allowlist bound to page kinds (PAIRING warm / SECRET / UNTRUSTED fresh per call / SCAN per session), request built with
`buildJsonObject` (never string concatenation), `{"ok": false, "error": {code, message}}` → typed exception with the code
reduced to `[A-Z0-9_]{1,80}` (the only part logged) and the message cut at 400 chars, 1 000 000-byte request guard
before any page loads, 2 MiB response cap, a guard test that the bundle global and `"operation"` appear in exactly one
file. The signer's `:core:engine` already implements the QuickJS equivalent (DECISIONS §5); reuse the guard-test idea.

### 3.7 Identity markers

Ownbit marks public-protocol surfaces with a badge, a stepper and a solid accent button
(`ob:core/publicsigner/ui/identity/*.kt`) to separate them from Ownbit's own pages. The signer has one protocol: no
badge, no stepper. The solid accent capsule (`OpenProtocolButton` = `OwnbitSecondaryButton` with
`containerColor = openProtocol`, `contentColor = onOpenProtocol`, flat grey when disabled, spinner keeps the label as the
a11y name, `ob:core/publicsigner/ui/identity/OpenProtocolButton.kt:22-46`) is the right model for iOS `prominentAction()`
+ `ActionLabel(busy:)` (`BTS/Design/DesignSystem.swift:46-127`).

### 3.8 Do-not-copy list (Ownbit behaviour that diverges from BitSigner)

From `ob-doc:docs/ios-diff/public-protocol.md` (row numbers there): Ownbit-edition engine and device name "Ownbit" (1);
WebView host and floor 91 (2, 4); 11-op allowlist without `createWalletPairing` / wallet level (3); seed passed instead of
deriving from the phrase (5); eligibility rules (6); no-online-rule notes (7); "Prepare" and sealed Room index (8, 9);
cross-check against Ownbit's stored addresses (10); two tabs on Ownbit pages (11); Ownbit BTC profile order and notes
(12); Ownbit TRON scheme 0…99 (13); EVM address #N (14); Solana "More paths" (15); 23 wallets minus BitTool / Safe /
Ownbit, guide sheets, connection flow (16, 40); security-log hooks (17); built-in H5 guide (18); one home scanner, no file
import, deep links (19); 150 s scan timeout (20); EN-tag vocabulary (21); D43 card (22); extra consent reasons (23);
Ownbit password dialog without delay (24); TRON owner refusal (25); change index ≤ 999 (26); Kotlin verifier (27);
"signed before" notice (28); offline-signing records instead of Activity (29, 43); witness-only SegWit + outpoint memory
(33, 34); fee rounding text (35); "Not signed yet" pill (36); TronLink / TokenPocket (38, 39).

---

## 4. Mnemonic entry, BIP39 suggestions, backup and verify

### 4.1 Ownbit's in-app BIP39 keyboard (optional for the signer)

`ob:core/ui/keyboard/Bip39Keyboard.kt:99-207` + `InAppKeyboardInput.kt:38-79` + `Bip39KeyboardEdits.kt` +
`SwitchToSystemKeyboardDialog.kt:15-28`; host: `ob:feature/onboarding/importmnemonic/ImportMnemonicScreen.kt`.

- `InAppKeyboardInput(inAppKeyboard = true) { field }` wraps the field in `InterceptPlatformTextInput(BlockSystemInput)`
  whose interceptor never forwards (`awaitCancellation()`), and provides a `SoftwareKeyboardController` whose `show()` is
  a no-op: **no `InputConnection` is ever created**, so no IME, suggestion strip or autofill can attach; the field keeps
  focus, caret, selection handles and the copy / paste toolbar (:21-79). Switching the flag re-keys the content; request
  focus again afterwards. With `false` the field goes to the system IME inside `NoPersonalizedLearning`.
- Keyboard layout: suggestion row (46 dp, chips 34 dp, horizontally scrolling, reset to the start for a new prefix, same
  height when empty so keys never jump, max `MAX_SUGGESTIONS = 12`), rows `qwertyuiop` / `asdfghjkl` /
  paste · `zxcvbnm` · backspace / system-keyboard globe · space · done; keys 42 dp, 8 dp row gap, 5 dp key gap, 1 dp
  darker key edge; forced LTR (`ForceLtr`) so an Arabic UI keeps QWERTY order; it pads for the navigation bar itself
  (:126-207, :229-280).
- Robustness: a `KeyboardEditor` keeps the last emitted `TextFieldValue` until the caller's state catches up, so two
  taps in one frame never start from a stale value (:517-535); backspace fires on touch-down then repeats after 400 ms
  every 70 ms accelerating to 35 ms (`RepeatKey`, :276-280, :405-455); keys are not focusable (tapping never steals the
  field's focus, :398); `KEYBOARD_TAP` haptic per key (:124) — note this one bypasses the app haptic switch.
- The globe key asks first (`SwitchToSystemKeyboardDialog`): keep = primary, switch = secondary, dismiss = keep.

Adapt / decide: iOS uses a plain multiline editor on the system keyboard (CJK / Korean phrases need Apple's IMEs) and
rejects third-party keyboards app-wide (`BTS/Features/Setup/RecoveryPhraseViews.swift:177-218`). On Android the closest
equivalent is Ownbit's keyboard for Latin-script lists plus an explicit switch to the system IME for zh / ja / ko. It is
extra UI not in iOS → product decision. If adopted, the keyboard's own labels (paste / delete / system keyboard / space /
done and the switch dialog) are new Android strings.

### 4.2 Live analysis and suggestions

Ownbit `MnemonicInput.analyze/suggestionsAt/applySuggestion` (`ob:core/crypto/bip39/MnemonicInput.kt:76-213`): caret-aware
token ranges (per character for CJK), status precedence invalid word > count > checksum > valid, invalid words echoed at
most `ECHO_LIMIT` chars with "…", redacted `toString()` on every type that can hold words (:29, :63, :73). The word lists
are NFKD (`ob:core/crypto/bip39/Bip39Wordlists.kt:22-36, 75-76`), 8 languages, suggestions limit 12
(`Bip39Validator.kt:271`).

Signer rules differ (`BTS/Features/Setup/RecoveryPhraseViews.swift:220-312`): 10 languages, language detected from the
draft, **5** suggestions (`list.suggestions(for:limit: 5)`), Chinese shows `"Each Chinese word is one character"`
instead of chips, empty states `"Type a word to see suggestions"` / `"No matching word"`, chips are monospaced semibold
capsules with a11y `"Insert %@"`, completion replaces the trailing `[^\s,，、]+$` run with the word + one space. Status
line: `"Recovery phrase"` with `"Unknown: %@"` (first two unknown words in “”), `"12–24 words · any BIP39 language"`,
`"1 word"`, `"%lld words"` + `" · "` + language name, icon `checkmark.circle.fill` / `text.word.spacing` /
`exclamationmark.circle.fill`. Placeholder `"Enter your words separated by spaces"`, min height 132, monospaced body.
Port the analysis structure (pure Kotlin, caret aware, redacted) with iOS's numbers and strings.

### 4.3 Showing a phrase: grid, cover, reveal

- Ownbit `MnemonicGrid` (`ob:core/designsystem/component/MnemonicGrid.kt:59-150`): box height depends only on the word
  count ("never on the words themselves"), one shared font size for every cell from a reference label
  (`FONT_REFERENCE_TEXT = "24. tomorrow"`, 16 → 12 sp in 0.5 steps, `MnemonicGridMetrics.fitFontSize`), covered state
  renders **no words at all** (fixed placeholder bars), RTL mirrors the grid while each "N. word" stays LTR.
- Ownbit `CoverSwitch` (`ob:feature/backup/ui/CoverSwitch.kt:19-50`): **covering is instant** ("the secret leaves
  composition in the same frame — nothing lingers in a fade-out"); revealing fades the secret in over the cover in
  250 ms, then drops the cover so TalkBack never finds it.
- Re-cover on `Lifecycle.Event.ON_PAUSE` (`ob:feature/backup/mnemonic/BackupMnemonicScreen.kt:126`), tab switch, new
  phrase; the decrypted phrase lives in the page ViewModel and an in-memory `@Singleton` holder for the next pages,
  wiped in `onCleared` (`ob:feature/backup/data/BackupPhraseHolder.kt:13-39`).

Signer rules: iOS `WordGridColumns` picks 3 / 2 / 1 columns and one word size (≥ 80 % of the style size, rounded down
to 0.5 pt, safety margin 3) (`BTS/Features/Setup/RecoveryPhraseViews.swift:56-99`); concealed placeholder uses a
reference 8-letter word, blur 2, "Tap to Reveal" / "Make sure no one can see your screen." over a material card
(:103-139); reveal is the consent, no second confirmation; hide on screenshot, resign-active, background, capture
(`BTS/Services/RecoveryPrivacySession.swift:4-83`). Use Ownbit's instant cover (iOS hides inside `withAnimation`, which
keeps the words visible during the fade — Android should not), and `app-shell.md` §15.8 for the event mapping.

### 4.4 Verify step

Ownbit verify page (`ob-doc:docs/impl/modules/feature-backup.md` §3): known cells show 6 fixed dots (TalkBack reads only
the number), current blank 2 dp brand border, candidates tracked by index (duplicates safe), used = 30 % + disabled,
wrong → shake + error haptic + alert, same positions reshuffled; tests include "150 random tap rounds where the verdict
always equals what the grid shows". The signer's model is iOS `BackupVerification` (`BTS/Models/BackupVerification.swift:7-93`:
4 blanks for 12–15 words, 5 for ≥ 18; chips never in answer order unless all equal; `activePosition`, `select`,
`clear`, `resetPlacements`) with the chip `.sensoryFeedback(.selection, trigger: placed)`
(`BTS/Features/Backup/RecoveryBackupViews.swift:512`). Port the model as pure Kotlin with an injectable RNG; copy
Ownbit's random-tap property test idea.

### 4.5 Recovery QR

Ownbit's backup QR contract (`ob:core/crypto/qr/MnemonicQrPayload.kt:1-60`): words joined by one ASCII space, UTF-8,
level M, margin 2, "must only exist while revealed, never be cached, copied, logged or passed through Intents/Bundles",
with a `CharArray` builder. iOS renders `QRCodeView(frames: [words.joined(separator: " ")], maxSide: 250)` with the
warning `"This QR code is your recovery phrase. …"` (`BTS/Features/Backup/RecoveryBackupViews.swift:99-116`).

---

## 5. Password entry, secure input, strength meter

### 5.1 Field

`PasswordTextField` (`ob:core/designsystem/component/TextFields.kt:279-339`): `NoPersonalizedLearning { OwnbitTextField(…) }`,
`SecureKeyboard.options(keyboardType = Password, imeAction = Done)` (no autocorrect, no capitalisation,
`ob:core/designsystem/component/SecureInput.kt:22-36`), `PasswordVisualTransformation` toggled by an eye button whose
state is plain `remember` (never saveable), **`ltrInput = true`** ("a revealed password such as "!@#123" must not be
reordered by an Arabic (RTL) paragraph", :318-320), auto-focus via `Modifier.autoFocusOnShow(autoFocus)`.

Signer mapping (`BTS/Design/PasswordViews.swift:9-166`): kinds `"Wallet password"`, `"Confirm password"`,
`"Current wallet password"`, `"New password"`, `"Confirm new password"`, `"BIP39 passphrase"`, `"Confirm passphrase"`;
only the passphrase field is revealable (`"Show secret"` / `"Hide secret"`), revealed text is hidden again when the app
leaves the foreground (`scenePhase != .active`, :95-96); new secrets keep AutoFill away (`isNew`). With the scaffold's Compose BOM 2026.09.00 /
Material 3 1.4.0 (DECISIONS §2) prefer `SecureTextField` / `OutlinedSecureTextField` on a `remember { TextFieldState() }`
(never `rememberTextFieldState()`, which is saveable — `app-shell.md` §15.11) and keep Ownbit's LTR + no-learning
wrappers around it.

### 5.2 IME and autofill guards (copy)

- `NoPersonalizedLearning` (`ob:core/designsystem/component/SecureInput.kt:48-73`): `InterceptPlatformTextInput` wraps
  the request's `createInputConnection` and ORs `EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING` into `imeOptions` — works
  for any `BasicTextField` / `TextFieldState` field.
- `NoAutofill` (`ob:core/ui/security/NoAutofill.kt:24-63`): sets the host view's `importantForAutofill =
  IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS`, reference-counted per view (two pages during a transition never undo
  each other), restored when the last user leaves. Compose ≥ 1.8 reports fields to autofill through semantics
  (`contentType`), so "never set a `contentType` on a secret field". DECISIONS §6 wants autofill excluded app-wide: set
  it on the activity's decor view in `onCreate` and keep the composable for dialog windows (separate views).

### 5.3 Password dialog mechanics (copy)

`PublicPasswordDialog` (`ob:core/publicsigner/ui/PublicPasswordDialog.kt:32-79`): text held in `remember` and cleared in
`DisposableEffect.onDispose`; converted to `CharArray` only on confirm and **owned by the receiver** (`onSubmit` wipes
it, `Arrays.fill(password, '\u0000')` in `finally`, `PublicPairingViewModel.kt:428-451`); `LaunchedEffect(prompt.errorCount)
{ if (errorCount > 0) dialogState.shakeError() }`; `secure = true`; back disabled while busy. Shake = keyframes
`0, -8, 8, -6, 6, -3, 3, 0` dp over 400 ms + error haptic (`ob:core/designsystem/modifier/Shake.kt:19-70`), skipped with
reduce motion.

### 5.4 Keyboard focus (pitfall worth copying)

`Modifier.autoFocusOnShow()` / `rememberKeyboardFocus()` (`ob:core/designsystem/modifier/KeyboardFocus.kt:178-264`): "the
send amount page sometimes had a caret but no keyboard" — Android drops a keyboard show while the field's window has no
input focus (a HUD window, an alert that just closed, the first frames of a dialog / sheet window). The helper waits for
lifecycle ≥ STARTED and `LocalWindowInfo.isWindowFocused`, requests focus (retrying until attached), shows the IME, then
once RESUMED checks the IME inset and re-issues the show every `RETRY_STEP_MILLIS = 120` for `VERIFY_WINDOW_MILLIS = 600`
(`ob-doc:docs/impl/modules/core-designsystem.md` §3). Every single-field dialog / sheet auto-focuses its field (guarded by
`ob-test:core/designsystem/modifier/SingleInputAutoFocusAuditTest.kt`). iOS AppLockView refocuses the password after an
error (`BTS/Features/Lock/AppLockView.swift:113-127`).

Dialog placement with a text field (`ob:core/designsystem/component/Dialog.kt:282-320`): card in the upper part (centre
at `H/4 + h/4`, bottom ≤ `MODAL_UPPER_MAX_BOTTOM_FRACTION = 0.55f`), because some vendor keyboards (Huawei secure
keyboard) report **no IME insets** and covered a centred dialog.

### 5.5 Strength meter: do not copy Ownbit's algorithm

Ownbit's 5-segment meter is a port of Ownbit iOS `BILPasswordStrengthView` scoring
(`ob:core/model/wallet/PasswordStrength.kt:14-80`; UI `ob:feature/onboarding/password/CreatePasswordScreen.kt:209-230`:
red 1 / yellow `#FFC400` 2–3 / green 4–5, `animateColorAsState` per segment). The signer uses iOS
`PasswordRequirementsView` (`BTS/Design/PasswordViews.swift:168-217`): **4** capsules 4 high, strength 0 empty, 1 if
invalid, 2 / 3 / 4 by length ≥ 12 and length ≥ 16 with all 4 categories; colours warning / systemYellow / success,
`.smooth(0.2)`; checklist `"8 to 64 characters"`, `"3 of: uppercase, lowercase, number, symbol"`, `"Passwords match"`
with `checkmark.circle.fill` / `circle` and value "Met" / "Not met". Policy (`BTS/Core/PasswordPolicy.swift:5-46`): 8–64
characters (grapheme count) and ≤ 1024 UTF-8 bytes, ≥ 3 categories, no control characters; messages
`"Use 8–64 characters."`, `"Control characters are not allowed."`,
`"Include at least 3 of: uppercase, lowercase, numbers and symbols."`, `"Password meets the requirements."`. Keep from
Ownbit: score on a `CharSequence` without making a `String` copy, wipe the temporary code-point arrays (:25-35).

---

## 6. Navigation and the tab bar

### 6.1 Routes (copy the rules)

`ob-doc:docs/impl/modules/core-navigation.md` §1–§10:
- Every destination is a `@Serializable data object / data class` implementing a sealed marker, grouped per owner
  feature; KDoc names the iOS controller and the owner module.
- Arguments are **primitives only** (`String`, `Int`, `Long`, `Boolean`, `String?`) — no `typeMap`, no custom `NavType`;
  `RouteRoundTripTest` pushes all routes through the real Navigation machinery (Robolectric) with URI-hostile strings
  (`/ ? & # % +`, spaces, CJK, empty) and fails for a route without a case.
- Never secrets in routes or `SavedStateHandle`; multi-step flows keep state in a `@Singleton` holder and pass an id.
- Results: `NavResultKeys` + `popWithResult` / `NavResultEffect`; the key is cleared with `null` (never `remove`, which
  detaches the flow); main thread only.
- Helpers (`NavigationExt.kt`): `navigateSingleTop(route)` compares type **and arguments** — Navigation's
  `launchSingleTop = true` re-uses the top entry of the same type with new arguments and keeps the old ViewModel
  (gotcha §10); `navigateReplacingCurrent`, `navigateClearingBackStack`, `isOnBackStack<T>()`.
- Stale taps: run navigation callbacks through an `ifCurrent(entry)` guard — a page animating out is still tappable,
  and a double tap on ✕ popped the page below (`ob-doc:docs/impl/modules/feature-backup.md` §1).

Signer: `WalletRoute.detail(UUID)`, `.connect(UUID, Chain)`, `.connectWallet(UUID)`, `.addresses(UUID, Chain)`
(`BTS/Features/Main/AppRouter.swift:9-15`) → `@Serializable` classes with `walletId: String` + chain wire string;
`AppRouter` semantics (tab, `scanRequest` token, setup presentation, `routeAfterSetup` opened after the sheet dismissed)
per `app-shell.md` §15.10.

### 6.2 NavHost wiring

`ob:app/navigation/OwnbitNavHost.kt:62-125`: one NavHost in `:app`, each feature contributes
`fun NavGraphBuilder.<feature>Graph(navController)`; placeholders for the start destinations are registered **first** so
a missing feature registration never crashes during parallel development (:201-222). Root swaps (welcome ↔ main) dissolve
over an old page that stays drawn (`NavMotion.stay`, because `ExitTransition.KeepUntilTransitionsFinished` is internal).

### 6.3 Transitions (pitfall)

`NavMotion` (`ob:app/navigation/OwnbitNavHost.kt:150-199`): iOS push = new page slides in from the trailing edge **over**
the current one, which moves 30 % underneath; pop = reverse; `PUSH_MILLIS = 400`, `CubicBezierEasing(0.32f, 0.72f, 0f, 1f)`,
mirrored for RTL, predictive back follows the gesture; reduce motion = dissolve over a page that stays, pops fade the
leaving page out over the one underneath. Reason (:137-149): "Material's shared X axis faded the old page out in 90 ms
and the new one in after that — for a few frames neither page was drawn and the screen showed the window background".
Every destination root must paint an opaque full-size background (`NavTransitionFramesTest` checks every frame for
half-transparent pixels, `ob-test:app/navigation/NavTransitionFramesTest.kt`).
For DECISIONS §7 ("shared-axis / fade-through"): use enter and exit **simultaneously** (`fadeIn(tween(…)) togetherWith
fadeOut(tween(…))` with overlapping windows, or a slide + fade) and give the window the page background
(`window.setBackgroundDrawable(ColorDrawable(launch_background))`, re-applied on every theme switch —
`ob:app/MainActivity.kt:123-131`); verify with a frame test like Ownbit's.

### 6.4 Tab container and bar

`ob:app/main/MainScreen.kt:86-316`:
- `rememberSaveable` selected index; each tab inside `SaveableStateHolder.SaveableStateProvider(tab.name)` so scroll
  positions survive switching; tab ViewModels live in the container's entry and the other tabs' ViewModels are created
  eagerly with the container so a first switch shows loaded content (:87-95).
- `AnimatedContent` with `EnterTransition.None togetherWith ExitTransition.None` — instant switch (:167-174).
- Back on a non-start tab returns to the start tab (`BackHandler`, :101-103); Back on the start tab leaves the app.
- Reselect is a one-shot `MutableSharedFlow(extraBufferCapacity = 1)` filtered per tab (:105-110), used to scroll to top /
  pop the tab stack.
- Bar (`OwnbitNavigationBar`, :185-229): compact iOS-style 50 dp row (`MainTabBarHeight`) above the navigation-bar inset,
  hairline divider, `height(IntrinsicSize.Min)` so very large font scales grow the bar instead of clipping, 24 dp icons
  over 10 sp medium labels, selected = accent tint + filled icon, **no indicator pill**, unbounded ripple radius 28 + press
  scale 0.92, selection haptic only on a change (none on reselect), badge = 16 dp red capsule with 11 sp LTR digits at the
  icon's top-right (:289-310).

Signer: four tabs `"Wallets"`, `"Scan"`, `"Activity"`, `"Settings"` (`BTS/Features/Main/MainTabView.swift:8-22`), Scan
badge `1` while `store.review != nil && !store.hasSignedResponse` (:15), bar never hides ("a signing app should keep
Activity and Settings one tap away", :23-24). Either Ownbit's compact bar (iOS look) or M3 `NavigationBar` /
`NavigationSuiteScaffold` (`app-shell.md` §15.10) — whichever, keep the instant switch and the toast offset above it
(`OwnbitOverlayHost(toastBottomPadding = MainTabBarHeight + 16dp)`).

### 6.5 Activity shell details worth copying

`ob:app/MainActivity.kt:47-137`: `installSplashScreen()` before `super.onCreate`, keep-on-screen gate until the first
screen is composed (capped), `enableEdgeToEdge(navigationBarStyle = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT))` plus
`window.isNavigationBarContrastEnforced = false` on API 29+ (the contrast scrim painted a strip over grey pages with
3-button navigation, :110-121), AppCompat night mode from the persisted appearance and the window background re-applied
after every switch (:91-100, :123-131), `singleTask` + `onNewIntent`. Ownbit forces portrait on phones because its iOS
app is portrait-only (:56-59) — **do not** copy (the signer supports rotation and tablets, `app-shell.md` §2.3).

---

## 7. Secure window, screenshot protection, recents

### 7.1 What Ownbit has

- `SecureScreen(enabled)` (`ob:core/ui/security/SecureScreen.kt:20-23`) → `WindowFlagEffect(FLAG_SECURE)`
  (`ob:core/ui/window/WindowEffects.kt:52-65`): reference-counted per window **and** flag in a `WeakHashMap`
  (`WindowRegistry`, :92-117), so two secure pages overlapping in a transition never clear each other; applied to the
  dialog / sheet window when called inside one (`View.hostWindow()` walks parents for `DialogWindowProvider`, :71-79).
- Dialog / sheet windows: `DialogProperties(securePolicy = if (secure) SecureOn else Inherit)`
  (`ob:core/designsystem/component/Dialog.kt:343-349`), `ModalBottomSheetProperties(securePolicy = …)`
  (`ob:core/designsystem/component/BottomSheet.kt:203-206`). Custom overlay windows copy the host's flag
  (`overlayWindowFlags(secure = …)`, `ob:core/designsystem/component/OverlayWindow.kt:55-71`), the lock window always sets
  it (`ob:feature/security/lock/LockWindow.kt:81-102`).
- Recents (`ob:feature/security/lock/RecentsGuard.kt:33-103`): API 33+ `Activity.setRecentsScreenshotEnabled(false)`;
  API 26–32 an invisible 1 × 1 px `TYPE_APPLICATION` window with `FLAG_SECURE | NOT_FOCUSABLE | NOT_TOUCHABLE`, its only
  view `INVISIBLE` (no surface) — the window manager replaces the task snapshot of an activity with any secure window by a
  plain page in `android:colorBackground` (AOSP `ActivityRecord.shouldUseAppThemeSnapshot`), without blanking
  screenshots. Ownbit's reasons: toggling the activity window's `FLAG_SECURE` "re-creates its surface on some devices
  (the owner's Huawei: a visible blink on every return), and the flag set in `onPause` often landed after the thumbnail was
  taken (emulator: 3 of 6 trials showed the wallet page)".
- Clipboard (`ob:core/ui/security/ClipboardHelper.kt:30-162`): `copy()` returns whether the caller should show its own
  "Copied" (not on API 33+, where the system confirms); `copySensitive()` sets `EXTRA_IS_SENSITIVE` + an app token in the
  clip extras, clears after `SENSITIVE_CLEAR_DELAY_MS = 60_000` only if the clip is still ours, cancels when the user
  copies something else (primary-clip listener), clears to be safe when unreadable in the background.

### 7.2 Signer mapping

- DECISIONS §6 sets `FLAG_SECURE` on the activity window in `onCreate` and never toggles it → per-page `SecureScreen` is
  redundant for activity-window pages; keep the `Inherit` policy on every Compose dialog / sheet (they inherit at
  creation) and copy the flag into any custom window (lock window, toast if implemented as a window). Never use a
  platform `Toast` or `PopupWindow` for secret content (not covered by inheritance).
- `setRecentsScreenshotEnabled(false)` on API 33+; the guard window is unnecessary while the activity is always secure.
- iOS privacy cover (`BTS/Services/PrivacyShield.swift:15-84`) and recovery privacy / screenshot sessions
  (`BTS/Services/RecoveryPrivacySession.swift`, `RecoveryScreenshotSession.swift`) → `app-shell.md` §15.8 (API 34
  `registerScreenCaptureCallback`, API 35 `addScreenRecordingCallback`, `ON_PAUSE` as resign-active). Strings:
  `"Hidden while your screen is recorded or mirrored"`, `"Screenshot Detected"` (not localised on iOS),
  `"The phrase is hidden again, but the screenshot may already contain it. Delete it from Photos, Recently Deleted and any synced copies, and keep a written backup offline."`,
  `"The screenshot may contain your recovery phrase or some of its words. Delete it from Photos, Recently Deleted and any synced copies, and keep a written backup offline."`.
- Clipboard: iOS copies public text `localOnly` with `expirationDate` 120 s (`BTS/Design/DesignSystem.swift:292-297`).
  Use Ownbit's `copySensitive` mechanics with `clearAfterMillis = 120_000` and the sensitive flag (DECISIONS §6); follow
  Ownbit's API 33 rule for the in-app "Copied" toast, but keep the inline icon swap (copy → checkmark for 1.5 s,
  `BTS/Design/AddressText.swift:90-107`) on every API level.

---

## 8. App lock and biometrics

### 8.1 Ownbit architecture (PIN only, no biometrics)

`ob-doc:docs/impl/modules/feature-security.md` §1–§8; code `ob:feature/security/…`:
- `AppLockController` (`lock/AppLockController.kt`): process-wide state machine `RESOLVING → LOCKED / UNLOCKED`
  (RESOLVING counts as locked until the store is read — fail closed), background time from `elapsedRealtime`,
  `lockGeneration` bumped on every lock request **also while already locked**, so the lock screen drops a password
  verified before the app went to the background, an open dialog and typed text (:99-159). Ownbit's
  `BACKGROUND_TIMEOUT_MILLIS = 60_000L` with `>` (:29-40) — the signer uses iOS `backgroundGrace = 120` s with
  `now - since < 120` (`BTS/Services/AppAccessController.swift:99, 177-189`).
- Relock in `Activity.onStart` (before the first frame) and the overlay reads the status with `collectAsState` (not
  lifecycle-gated), so the returning first frame is already covered. `ProcessLifecycleOwner` reports `ON_STOP` 700 ms
  late (effective limit 60.7 s) — `app-shell.md` §15.4 uses the activity's `onStop` instead.
- Layers (`AppLockOverlay.kt:90-318`, `lock/LockLayers.kt`, pure + `LockLayersTest`): an opaque cover in the activity
  window that swallows touches and hides the app from accessibility (`IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS`),
  focus cleared + keyboard hidden on lock; a non-interactive copy of the lock screen in the activity window; the real lock
  screen in its own window (`LockWindow`). Unlock: two unchanged frames, then the secure lock window is removed over its
  identical copy and the copy fades out in the activity window (alpha 1 → 0, scale 1.04, app spring ≈ 0.35 s); touches
  wait until the fade ends. This fixed a blink caused by fading a translucent `FLAG_SECURE` window (§4a of the doc).
- `LockWindow` (`lock/LockWindow.kt:52-117`): `TYPE_APPLICATION` window of the activity added through
  `activity.windowManager` (token filled in), MATCH_PARENT, `FLAG_LAYOUT_IN_SCREEN | LAYOUT_NO_LIMITS |
  HARDWARE_ACCELERATED | DRAWS_SYSTEM_BAR_BACKGROUNDS | SECURE`, `SOFT_INPUT_STATE_ALWAYS_HIDDEN | ADJUST_NOTHING`,
  `windowAnimations = 0`, cutout `ALWAYS` (R+) / `SHORT_EDGES`, `setFitInsetsTypes(0)`, format `TRANSLUCENT` (so the
  activity below keeps drawing); content is an `AbstractComposeView` with the caller's `CompositionContext` and borrowed
  lifecycle / ViewModel / saved-state owners; back → `moveTaskToBack(true)` (key + API 33 `OnBackInvokedCallback`);
  first-frame callback via `ViewTreeObserver.OnDrawListener`. **Not a Compose `Dialog`**: its window measures content at
  most `screenWidthDp × screenHeightDp` (without bars below Android 15) and shrinks, centred — "1078 × 2021 at y = 99" on a
  1080 × 2220 phone, the "PIN page collapses once".
- `WindowGate` (`ob:core/designsystem/component/WindowGate.kt:34-125`) + `rememberLockWindowGate`
  (`ob:app/ui/LockWindowGate.kt:26-41`): closed in the same composition that sees the lock, re-opened
  `UNLOCK_SETTLE_MILLIS = 450` after the unlock; while closed no new dialog / sheet / HUD / toast window is added (the
  caller keeps its state; it appears after the unlock), a window already on screen stays (the lock window covers it);
  navigation `dialog<…>` destinations are kept invisible / untouchable / unfocusable (`GatedDialogDestination`). The lock
  screen's own windows are outside the gate.
- Launch: the system splash stays until the lock screen is composed, so the app content is never seen under it
  (`ob:app/LaunchScreenGate.kt`); while the splash is up the lock draws only in the activity window with `FLAG_SECURE`.

### 8.2 Signer mapping

iOS App Lock = biometrics with a wallet-password fallback, its own window above every sheet
(`BTS/Features/Lock/AppLockView.swift:130-185`, window level `.alert + 1`), the privacy shield above it (`.alert + 2`).
Port Ownbit's `LockWindow` + `WindowGate` + layer model (as `app-shell.md` §15.7 recommends) with these changes: no PIN
pad; the lock screen is iOS `AppLockView` (`"BitTool Signer Is Locked"`, `"Unlock to view your wallets and activity."`,
biometric button, `"Use a Wallet Password"`, password form with `"Enter the password of any wallet on this device."`,
wallet picker `"Choose a wallet"` (names only), `"Unlock"`, `"Use %@ Instead"`, footer
`"Unlocking the app never authorizes a signature."`); `BiometricPrompt` per `app-shell.md` §15.6 (needs a
`FragmentActivity` — `AppCompatActivity` is one; prompt only while RESUMED; `cancelAuthentication()` on `onStop`).
Ownbit's pitfalls that still apply: draw the lock in the very first frame after `onStart`; make the copy in the activity
window identical in frame and insets (edge-to-edge) so the take-over does not jump; never fade a secure window;
hide app content from accessibility while locked; clear focus / hide the IME on lock.

---

## 9. Haptics, toasts, HUD, bottom sheets, dialogs

### 9.1 Haptics

Copy the vocabulary and SDK mapping (`ob:core/designsystem/haptics/OwnbitHaptics.kt:31-59`, pure `hapticConstantFor`,
unit-tested): Light `KEYBOARD_TAP`, Medium `VIRTUAL_KEY`, Heavy `LONG_PRESS`, Selection `SEGMENT_TICK` (34+) /
`CLOCK_TICK`, Success `CONFIRM` (30+) / `VIRTUAL_KEY`, Warning `LONG_PRESS`, Error `REJECT` (30+) / `LONG_PRESS`,
Toggle `TOGGLE_ON/OFF` (34+) / `CLOCK_TICK`, Drag `GESTURE_START/END` (30+). Always through
`View.performHapticFeedback` (honours the system touch-feedback setting). Ownbit rule worth keeping: haptics only on
explicit user actions — none on screen appearance, automatic refreshes or async status changes
(`ob-doc:docs/impl/modules/core-designsystem.md` §2 "Haptics").

**Drop** `OwnbitHapticsSettings.enabled` (default `false`, :61-70) and the `LocalHapticFeedback` no-op replacement
(`ob:core/designsystem/theme/OwnbitTheme.kt:150`). There is a second, older `Haptics` class in
`ob:core/ui/haptics/Haptics.kt:17-55` with a different mapping — keep one implementation only.

iOS haptic sites to reproduce (all automatic `sensoryFeedback`): success when the "Copied" toast appears
(`BTS/Design/DesignSystem.swift:327`), when an address copy flips to the checkmark (`BTS/Design/AddressText.swift:107`),
when a wallet is created (`BTS/Features/Setup/WalletSetupFlow.swift:450`), when the signature result appears
(`BTS/Features/Sign/SignatureResultView.swift:68`); selection on the risk checkbox
(`BTS/Features/Sign/RequestReviewView.swift:180`), on a backup chip placed (`BTS/Features/Backup/RecoveryBackupViews.swift:512`),
on a Connect selection change (`BTS/Features/Wallets/ConnectView.swift:76`); error on a new scanner error
(`BTS/Features/Sign/QRScannerView.swift:50`). Two of these fire on appearance (result page, wallet created) — they are
iOS behaviour, keep them despite Ownbit's rule.

### 9.2 Toasts

Ownbit (`ob:core/designsystem/component/Toast.kt:66-272`): a `ToastController` in a `staticCompositionLocalOf`, one current
toast (a new one replaces it), `durationMillis = OwnbitMotion.TOAST_MILLIS` (1.5 s) counted from appearance, rendered in a
pass-through overlay window (`FLAG_NOT_FOCUSABLE | NOT_TOUCHABLE`, never steals the keyboard), one window per toast so a
replacement is added above dialogs opened meanwhile, positioned `bottomPadding` above the navigation bar or 16 dp above an
open keyboard (`toastBottomOffsetPx`, :206-207), `liveRegion = LiveRegionMode.Polite` on the card (:220), light haptic.

Signer `StatusToast` (`BTS/Design/DesignSystem.swift:300-338`): label + `checkmark.circle.fill`, subheadline semibold,
capsule on regular material, padding 16 × 10, shadow 12 % radius 12 y 4, 24 above the bottom, move-from-bottom + opacity
with spring 0.3, **1.6 s**, VoiceOver announcement, success haptic; used as `copiedToast` (`"Copied"`) and `statusToast`.
`design-system.md` §9.5 (`StatusToastHost`) renders it inside the root composition (no overlay window) — fine as long as no dialog / sheet is
open when it shows; if a toast must appear above a sheet, Ownbit's overlay window (with `FLAG_SECURE` copied) is the
proven way.

### 9.3 HUD

Ownbit `HudController` (`ob:core/designsystem/component/Hud.kt:58-300`): loading / success / failure, blocking loading in
a focusable overlay window (it closes the keyboard), success / failure auto-dismiss 2.2 s, `Clear` vs `Frosted` backdrop
(`HUD_BLUR_DELAY_MILLIS = 400`, `HUD_FROSTED_FAILURE_GRACE_MILLIS = 100`), `LoadingHudEffect(active, message)` driven by
a ViewModel flag that removes only its own presentation. The iOS signer has **no HUD**: busy states are spinners inside the
action button (`ActionLabel(busy:)`, `BTS/Design/DesignSystem.swift:100-127`) and `ProgressView`s in place. Prefer button
`loading` states (Ownbit's buttons keep the label as the a11y name and block taps, `OpenProtocolButton`); do not port the
HUD.

### 9.4 Bottom sheets

`OwnbitBottomSheet` (`ob:core/designsystem/component/BottomSheet.kt:186-280`): M3 `ModalBottomSheet` with
`ModalBottomSheetProperties(securePolicy, shouldDismissOnBackPress)`, 36 × 5 grabber, radius 28,
`contentWindowInsets = { WindowInsets.safeDrawing.only(Bottom + Horizontal) }`, tonal elevation 0, gate check first
(`if (!rememberWindowGateReleased()) return`), `rememberSheetDismiss(state, onDismiss)` = animate hide, then dismiss
(:145-155); detents `Content / Medium (0.5) / Large (0.92)`. iOS sheets used by the signer: paste request, authorize,
recovery cards, security tips, wallet security sheets — each a `NavigationStack` with a title and Cancel. Map to M3
`ModalBottomSheet` (skipPartiallyExpanded for forms) or full-screen dialogs; keep `Inherit` secure policy and the gate.

### 9.5 Dialogs

`OwnbitDialog` / `OwnbitAlertDialog` (`ob:core/designsystem/component/Dialog.kt:185-490`): modal — a full-size
`blockPointerInput` scrim swallows touches beside the card (`dismissOnClickOutside = false` by default), back = the cancel
action (disabled while verifying), card `min(325, w − 50)` radius 24, title / buttons fixed and the body scrolls between
them, ≤ 2 actions side by side while both labels fit else stacked, **actions don't auto-dismiss**, **one primary per
dialog** (cancel / reject are secondary), `rememberOwnbitDialogState().shakeError()`, upper placement with a text field
(§5.4). iOS uses system alerts (`"Unable to Continue"` + `"OK"`, `BTS/Features/Main/RootView.swift:66-68`); M3
`AlertDialog` is the natural Android equivalent — apply the same rules (modal, explicit dismiss, one primary).

---

## 10. Hilt modules and ViewModel / UiState conventions

- **Module layout** (`ob-doc:docs/impl/ARCHITECTURE.md` §3–§4, build-logic `ob-doc:build-logic/convention/src/main/kotlin/AndroidFeatureConventionPlugin.kt`):
  a feature plugin applies compose + hilt + serialization and adds the core modules every screen needs; features never
  depend on features; core modules that host UI + ViewModels (`:core:scanner`, `:core:publicsigner`) are allowed when two
  features need the same screen. New third-party dependencies go in the module's own build file with a full coordinate
  during parallel work (same rule as DECISIONS §8).
- **DI**: one `di/` package per module with an `@Module @InstallIn(SingletonComponent::class) abstract class XxxModule`
  of `@Binds` (`ob:core/scanner/di/ScannerModule.kt:17-28`); everything else constructor-injected. Dispatchers by
  qualifier (`@IoDispatcher`, `@DefaultDispatcher`, `@MainDispatcher`) and an `@ApplicationScope CoroutineScope(SupervisorJob()
  + Default)` (`ob:core/common/coroutines/Dispatchers.kt:14-31`). Logging through one `Log` object that is a no-op in
  release (`ob:core/common/log/Log.kt:11-25`), lambdas so messages are not even built.
- **ViewModel testability seam**: an `internal` primary constructor taking plain lambdas / fakes plus an `@Inject`
  secondary constructor that adapts the real collaborators (`ob:core/publicsigner/ui/PublicPairingViewModel.kt:186-201`).
- **State**: `@Immutable data class XxxUiState` with derived getters (`currentQr`, `canSign`, `acceptsInput`) instead of
  duplicated booleans (`PublicPairingViewModel.kt:129-162`, `ob:core/scanner/ScannerViewModel.kt:24-34`,
  `ob:feature/coldwallet/publicsign/PublicReviewViewModel.kt:51-68`); a private `MutableStateFlow` exposed with
  `asStateFlow()`; updates with `update { it.copy(…) }`; UI state never holds secret text ("Holds no scanned text").
- **One-shot events**: `Channel<Event>(Channel.BUFFERED)` + `receiveAsFlow()`, collected once in a
  `LaunchedEffect(viewModel)` (`ScannerViewModel.kt:73-76`, `PublicReviewScreen.kt:97-105`); events with payloads get a
  redacted `toString()` (`ScannerEvent.Result.toString() = "Result(<n chars>)"`, :39-41).
- **Screens**: `XxxRoute(viewModel = hiltViewModel())` collects with `collectAsStateWithLifecycle()` and builds a stable
  `XxxActions` (class of lambdas, `remember(viewModel)`), the stateless `XxxScreen(state, actions)` is what previews,
  Robolectric tests and screenshots use. Route arguments read with `SavedStateHandle.toRoute<T>()`
  (`PublicReviewViewModel.kt:105`).
- **Async correctness**: cancel the previous job before starting a new one, and apply a result only if its key is still
  current (request key, epoch, session token); catch `CancellationException` and rethrow; a last-resort catch maps any
  other exception to a UI error so dialogs never stay busy (`PublicReviewViewModel.kt:182-196`).
- **Secrets**: `CharArray` parameters are owned by the callee and wiped in `finally`; parsers return redacted types;
  "only engine codes and exception class names are logged" (`ob-doc:docs/impl/modules/core-publicsigner.md` §9) with a
  `LogHygieneTest`.
- Previews: one multi-preview annotation (light + dark) and a preview wrapper that never touches the system bars
  (`ob:core/designsystem/preview/OwnbitPreviews.kt`).

---

## 11. Tests (unit, Robolectric, Roborazzi)

### 11.1 Layers

- Pure JVM for state machines and parsers: `CameraPermissionFlowTest`, `ScanDeduplicatorTest`, `PublicFrameGateTest`,
  `PasswordStrength`, haptic mapping, dialog width / placement math, `upperModalCardTop`, toast offset — every UI rule
  that can be a pure function is one (`ob:core/designsystem/component/Dialog.kt:123-139, 299-317`,
  `Toast.kt:196-207`).
- ViewModels with `UnconfinedTestDispatcher` + `Dispatchers.setMain` / `resetMain`, events collected in
  `backgroundScope` (`ob-test:core/scanner/ScannerViewModelTest.kt:26-55`), an injected monotonic clock (`{ now }`),
  `CompletableDeferred` fakes to hold async work mid-flight; Turbine for flows; mockk sparingly.
- Robolectric UI tests (`@Config(sdk = [34])`, Compose `createComposeRule()` / `createAndroidComposeRule<ComponentActivity>()`)
  for page states (`ScannerContentTest`: every permission / camera state renders an actionable page),
  `ScannerScreenSessionTest` (in-place hosting delivers on every showing).
- Instrumented only where a device is needed (camera decode of generated codes incl. a version-40 QR,
  `ob-doc:core/scanner/src/androidTest/kotlin/io/ownbit/core/scanner/MlKitQrImageDecoderTest.kt`; Keystore; engine). The live camera path is manual QA (list in
  `ob-doc:docs/impl/modules/core-scanner.md` §6).

### 11.2 Roborazzi screenshots (copy the setup)

Module build file (`ob-doc:core/designsystem/build.gradle.kts`, `core/publicsigner/build.gradle.kts`):

```kotlin
testOptions { unitTests {
    isIncludeAndroidResources = true
    all { test ->
        val record = project.findProperty("ownbit.screenshots")?.toString() == "true"
        test.systemProperty("roborazzi.test.record", record.toString())
        test.maxHeapSize = "1536m"
        if (!record) test.exclude("**/screenshot/**")
    }
} }
testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
```

Record-only, no goldens; PNGs to `build/outputs/roborazzi/<area>/`. Test shape
(`ob-test:core/publicsigner/screenshot/PublicPairingScreenshotTest.kt:179-322`): `@RunWith(ParameterizedRobolectricTestRunner)`,
`@GraphicsMode(GraphicsMode.Mode.NATIVE)`, `@Config(sdk = [34], qualifiers = "w393dp-h3200dp-xhdpi")` (tall for full
pages; `w393dp-h852dp` for phone windows), case names `scene_locale_theme[_width]`, theme passed explicitly with system
bars untouched, `onNodeWithTag("page").captureRoboImage(...)` for content and `captureScreenRoboImage(...)` for dialog /
sheet windows. Locale switching (:162-176): `RuntimeEnvironment.setQualifiers("+b+zh+Hans")`; Arabic with
`Locale.forLanguageTag("ar-u-nu-latn")` set as default **and** in the resources configuration so `%d` stays Latin.
Long-translation set at 360 dp (de, ru, fr, fil, uk, pl, pt-PT).

### 11.3 Robolectric pitfalls Ownbit hit

- Off-main-thread work (QR encoding, image decoding) under a paused clock: write state back on
  `Dispatchers.Main.immediate` (`ob:core/ui/qr/QrCodeImage.kt:82-92`) and settle with
  `mainClock.autoAdvance = false; repeat(12) { advanceTimeBy(100); Thread.sleep(60); shadowOf(getMainLooper()).idle() }`
  (`PublicPairingScreenshotTest.kt:150-160`).
- Dialog / sheet windows resolve RTL only if the application supports it: set `ApplicationInfo.FLAG_SUPPORTS_RTL` on the
  test application and activity (`PublicPairingScreenshotTest.kt:292-298`).
- Robolectric draws no window dim / blur; secure windows are not special; its library test manifest targets SDK 26 and
  focuses the first field of every new dialog by itself — assert a semantics marker, not focus
  (`ob-doc:docs/impl/modules/core-designsystem.md` §6).
- Windows are not captured by `captureRoboImage` of a composable — expose an internal card composable for screenshots.
- A frame test (`NavTransitionFramesTest`) can check every frame of a transition for half-transparent pixels and the host
  background, with the old animation as a negative control.

### 11.4 Guard (audit) tests worth recreating

- Boundary test that scans `src/main` and the build file for forbidden imports / dependencies (network, WebView), with a
  self-check of its own regexes (`ob-test:core/publicsigner/PublicSignerBoundaryTest.kt`); declare the sources as test
  inputs so a new import cannot leave it UP-TO-DATE (`ob-doc:core/publicsigner/build.gradle.kts`, last block).
- Log hygiene, route round trip, single-input auto-focus audit (scans every module's sources for one-field dialogs
  without auto-focus), call-site guard for the engine entry point.

### 11.5 Signer-specific additions

- Merged-manifest test: parse `build/intermediates/merged_manifests/*/AndroidManifest.xml` (or the `processManifest`
  output) of `apkRelease` and `playRelease` and fail on `android.permission.INTERNET` / `ACCESS_NETWORK_STATE` (§0.1).
- Port the iOS tests that cover these patterns: `QRScanFrameGate`, `ScanSession`, `BackupVerification`, `WordGridColumns`,
  `PasswordPolicy`, `AppAccessController`, `RecoveryPrivacySession`, `CompatibleWalletsTests` (every wallet has an icon,
  `activity-settings.md` §6.2).

---

## 12. Flavors (apk / play) — what Ownbit does

`ob-doc:app/build.gradle.kts:137-200`: `flavorDimensions += "distribution"`, `apk` (`isDefault = true`) and `play`, each
with `buildConfigField("String", "DISTRIBUTION", "\"apk\" / \"play\"")`; flavor code in `src/apk/…` / `src/play/…`
(Ownbit: `di/DistributionModule.kt` binding update services; `src/apk/AndroidManifest.xml` adds
`REQUEST_INSTALL_PACKAGES`). Release: R8 + resource shrinking, signing from local properties / env, optional separate Play
**upload** key used only for the `play` App Bundle, `ndk.abiFilters` for phone ABIs. Locale-related:
`resourceConfigurations` listing exactly the app languages (also drops the `en-rXC` / `ar-rXB` pseudo-locales that
otherwise wrapped library strings in U+200E / U+200F), `androidResources.generateLocaleConfig = false`, and
`bundle { language { enableSplit = false } }` — **in-app language switching needs every language in the base APK**
(lint `AppBundleLocaleChanges`). The signer differs (DECISIONS §1): no in-app update, no install permission, no
Play-rating link; both flavors stay offline and functionally identical.

---

## 13. Android mapping (concrete recommendations)

### 13.1 Placement in the signer modules (DECISIONS §3)

| Pattern | Signer module | Start from |
|---|---|---|
| CameraX binding, permission state machine, scanner page (overlay, status panel, top bar), ZXing analyzer, `QrScanFrameGate` (pure, could live in `:core:model`) | `:core:scanner` | `ob:core/scanner/camera/ScannerCamera.kt`, `CameraPermissionFlow.kt`, `ui/ScannerContent.kt` (structure), iOS constants (§1.5) |
| QR encoder (module matrix, integer cells, `FilterQuality.None`), `QrCodeView` (static / animated, controls, a11y) | `:core:scanner` (DECISIONS) or `:core:ui` | `ob:core/ui/qr/QrEncoder.kt`, `AnimatedQrCard.kt` |
| `ChainPicker`, `AddressIndexStepper`, `DetailLine`, `PayloadDisclosure`, wallet logos / works-with grid, password field + requirements view, address text with copy | `:core:ui` / `:core:designsystem` | §3.2, §5 |
| Haptics mapping, shake modifier, press scale, dialog / sheet wrappers with secure policy + gate | `:core:designsystem` | `OwnbitHaptics.kt` (minus the switch), `Shake.kt`, `PressScale.kt`, `Dialog.kt`, `BottomSheet.kt` |
| `NoPersonalizedLearning`, `NoAutofill`, `ClipboardHelper`, `WindowFlagEffect`, lock window, window gate, recents switch | `:core:security` (window helpers) and `:core:ui` (input wrappers) | `ob:core/designsystem/component/SecureInput.kt`, `ob:core/ui/security/*`, `ob:feature/security/lock/LockWindow.kt`, `WindowGate.kt` |
| Route rules, round-trip test, nav helpers, `NavMotion` | `:core:navigation`, `:app` | §6 |
| Scan session controller, review / authorize / result screens | `:feature:sign` (+ `SignerStore` in `:core:data`) | iOS semantics; Ownbit §3.3 for Compose structure |
| Phrase input, suggestions, grid, cover switch, verify | `:feature:setup` (+ pure logic in `:core:crypto` / `:core:model`) | §4 |

### 13.2 Compose / Android API choices

- Camera: `androidx.camera` 1.6 `Preview` + `ImageAnalysis` in a `PreviewView(COMPATIBLE)` via `AndroidView`
  (`camera-compose`'s `CameraXViewfinder` is an acceptable alternative but keep TextureView-like behaviour inside
  transitions). Permissions: `rememberLauncherForActivityResult(RequestPermission())` + `ActivityCompat.shouldShowRequestPermissionRationale`.
  File import: `ActivityResultContracts.OpenDocument(arrayOf("*/*"))` (the launch stops the activity → counts as background,
  `app-shell.md` §15.4).
- Overlay drawing: `Canvas` + `Path(fillType = EvenOdd)`, `PathMeasure.getSegment` for the progress ring,
  `rememberInfiniteTransition` for the pulse (skip with `ANIMATOR_DURATION_SCALE == 0`).
- QR: `com.google.zxing.qrcode.encoder.Encoder.encode` + `Bitmap.createBitmap(pixels, n, n, ARGB_8888).asImageBitmap()` +
  `drawImage(…, filterQuality = FilterQuality.None)`; encode on `Dispatchers.Default`, pre-encode before navigating.
- Text input: `BasicTextField(TextFieldState)` / M3 `SecureTextField` with `remember { TextFieldState() }`,
  `KeyboardOptions(autoCorrectEnabled = false, capitalization = None, keyboardType = Password | Text)`,
  `InterceptPlatformTextInput` for the no-learning flag, `TextDirection.Ltr` for passwords / addresses / hex.
- Secure windows: activity `FLAG_SECURE` once; `DialogProperties(securePolicy = Inherit)` /
  `ModalBottomSheetProperties(securePolicy = Inherit)`; custom windows copy the flag.
- Lock: `WindowManager.addView` with Ownbit's `lockWindowLayoutParams()`; `BiometricPrompt` (`BIOMETRIC_STRONG`, negative
  button `"Use a Wallet Password"`).
- Clipboard: `ClipboardManager.setPrimaryClip` with `ClipDescription.EXTRA_IS_SENSITIVE` + token, delayed clear 120 s.
- Haptics: `LocalView.current.performHapticFeedback(constant)` with the SDK-aware mapping.
- Accessibility: merged nodes for composite rows, `Role.Tab` / `Role.Checkbox` / `Role.Switch`, `stateDescription`,
  `ProgressBarRangeInfo`, polite live regions only for user-paced changes, `heading()` on panel titles, 48 dp touch targets
  (`minimumInteractiveComponentSize()`), FSI / PDI isolates when mixing Latin names into RTL lists.

### 13.3 Pitfall checklist

1. Merged manifest contains no `INTERNET` / `ACCESS_NETWORK_STATE` in either flavor (ML Kit would add them).
2. ZXing: detect binary QR (byte segments that are not strict UTF-8) → iOS `nonText`; QR only; try inverted.
3. Close every `ImageProxy`; single analysis executor shut down on dispose; release only your own use cases; torch off.
4. Permission: request only on entry and on the user's tap; `onResume` only upgrades; never a black screen.
5. Secret scanners hosted in place; nothing secret in saved state, routes, `rememberTextFieldState()`, logs or `toString()`.
6. Never toggle `FLAG_SECURE`; every extra window copies it; the lock is a real application window, not a Compose
   `Dialog`; windows requested while locked wait behind a gate.
7. Transitions never show the window background (opaque pages, simultaneous fades, window background = page colour);
   tabs switch instantly.
8. Haptics on by default (system setting only); one implementation; the 8 iOS sites.
9. Keyboard focus in dialogs needs window focus + retries; dialogs with fields sit in the upper half.
10. Revealed passwords and addresses are LTR; Arabic uses Latin digits (`ar-u-nu-latn`); RTL mirrors chevrons
    (`AutoMirrored` icons, rotate the opposite way).
11. Animated QR: no fade between frames, controls row of constant width, TalkBack announces parts only when paused.
12. Pre-encode QR frames before a push (iOS `initialImages`) so the page never changes mid-transition.
13. App bundle language split disabled; `resourceConfigurations` = the 21 app languages.
14. Ownbit data (wallet list, TRON scheme, strings, "Ownbit" device name) never leaks into the signer.

---

## 14. Out of scope (iOS-only) — drop or replace

- Face ID / Touch ID / Optic ID naming (`BiometryKind`, `"Unlock with %@"`-style wording,
  `NSFaceIDUsageDescription`), `LAContext` fallback title tricks → generic Android biometric strings (`app-shell.md` §15.6).
- Liquid Glass and iOS 26 / 17 / 18 branches: `GlassCircle` / `glassEffect` (`BTS/Features/Sign/QRScannerView.swift:129-137`),
  `.glassProminent` / `.glass` button styles and `safeAreaBar` (`BTS/Design/DesignSystem.swift:46-91`), `wallet.bifold`
  vs `wallet.pass` (`BTS/Features/Main/MainTabView.swift:27-30`), `writingToolsBehavior(.disabled)` (iOS 18,
  `BTS/Features/Setup/RecoveryPhraseViews.swift:211-217`).
- `AVCaptureDevice.RotationCoordinator` preview rotation (CameraX handles rotation), `autoFocusRangeRestriction = .near`
  (no CameraX equivalent; continuous AF + tap-to-focus instead).
- `UIPasteboard` `localOnly` / Handoff option (no Android equivalent; use the sensitive flag + delayed clear).
- App-delegate rejection of third-party keyboard extensions (Android: IME flags, optional in-app keyboard, §4.1).
- `.privacySensitive()` and `UIScreen.isCaptured` / `capturedDidChangeNotification` (Android: `FLAG_SECURE`; API 35
  screen-recording callback only), `userDidTakeScreenshotNotification` (API 34 callback; moot under `FLAG_SECURE`).
- `UIWindow` levels `.alert + 1` / `.alert + 2` (Android: `TYPE_APPLICATION` windows in add order).
- `CIQRCodeGenerator` / software `CIContext` (Android: ZXing).
- Dynamic Type categories (Android: `fontScale`; Ownbit's thresholds 1.3 / 1.6 for layout changes).
- Simulator UI-test context, App Store / TestFlight specifics, `PrivacyInfo.xcprivacy`.

Ownbit-only, not to port: PIN lock pad and its recovery flow, `OwnbitHapticsSettings`, frosted / clear HUD rules, QR
brightness toggle, in-app APK update, FCM push, WebView JS host, security center hooks and D43 card, public-protocol
identity markers and Ownbit-edition engine behaviour (§3.8), Album photo-picker decoding (unless product adds it), the
mnemonic-interception tip, ML Kit.
