# Port spec: Scan tab, QR scanner, request review & signing, signature result

Status: implementation spec for the Android port of BitTool Signer (iOS SwiftUI → Kotlin / Jetpack Compose / Material 3).

## 0. Conventions and source map

The iOS source root is `/Users/tanguopeng/Documents/bitsigner-ios/BitSigner/`.

- A path written as `Features/Sign/ScanTab.swift:20` is relative to `BitToolSigner/` under that root.
- A path written as `engine/...` is relative to the root itself.
- Line numbers refer to the files as read on 2026-10-20.

| File | Lines | Covers |
|---|---|---|
| `Features/Sign/ScanTab.swift` | 325 | `ScanSession` (frame queue → engine scan session), `ScanTab` container, `ScanHomeView`, file import, `PressableCardStyle`, `PasteRequestSheet` |
| `Features/Sign/QRScannerView.swift` | 378 | Full-screen camera, viewfinder overlay, `QRScanFrameGate`, AVFoundation camera |
| `Features/Sign/RequestReviewView.swift` | 907 | Review page, `ReviewSummaryCard`, `ContractNotice`, `NFTGlyph`, `ReviewDetailsCard`, `TaskCard`, `AuthorizeSheet` |
| `Features/Sign/SignatureResultView.swift` | 110 | Result page, export document |
| `Design/PayloadDisclosure.swift` | 100 | Collapsed raw-payload viewer with copy |
| `Models/ReviewText.swift` | 107 | Translating engine English at draw time; `shortDecimal` |
| `Models/Models.swift` | 702 | `ReviewField`, `ReviewAsset`, `ReviewNetwork`, `ReviewContract`, `ReviewAssetKind`, `ReviewFlowSection`, `ReviewDisplay`, `TransactionReview`, `SigningReceipt`, `QRPayloadView`, `SigningTask`, `SigningPlan` |
| `Models/AppStore.swift` | 602 | Scan / import / prepare / match / sign / encode / receipt pipeline, epochs |
| `Core/SigningEngine.swift` | 215 | JS host: allowlist, fresh contexts, scan context, entropy, limits |
| `Design/DesignSystem.swift`, `Design/QRCodeView.swift`, `Design/AddressText.swift`, `Design/PasswordViews.swift`, `Design/Logos.swift` | — | Shared components used by these screens |
| `Features/Main/AppRouter.swift`, `RootView.swift`, `MainTabView.swift`, `BitToolSignerApp.swift` | — | Scan request token, tab switching, badge, suspend triggers |
| `engine/src/index.js`, `engine/protocols.js`, `engine/src/resolver.js`, `engine/src/util.js`, `engine/src/evm.js`, `engine/src/bitcoin.js`, `engine/src/tron.js`, `engine/src/solana.js`, `engine/src/nft-orders.js`, `engine/src/known-contracts.js`, `engine/src/token-registry.js` | — | Engine operations and the review JSON shape |
| `engine/README.md`, `docs/KnownContracts.md` | — | Engine contract docs |

Tests worth porting one-to-one: `BitToolSignerTests/ReviewTextTests.swift`, `QRScannerTests.swift`, `NFTReviewDisplayTests.swift` (it contains real engine `display` fixtures), `QRPayloadViewTests.swift`, `ScanRequestRouterTests.swift`, and `SessionTests.swift:218-378`, which covers background cancellation, late results, retry matching, collection changes and multi-wallet tasks.

### String conventions

- Every user-visible English string is quoted verbatim. The English text is the localization key in `translations/*.json` (`strings` object).
- Swift interpolations become format specifiers in the key: `\(String)` becomes `%@` and `\(Int)` becomes `%lld`. Translations use positional forms such as `%1$@` and `%2$lld`.
  - On Android, map `%@` to `%s` and `%lld` to `%d`, both with positions (`%1$s`, `%1$d`).
  - `%%` stays `%%`.
- Some strings are **not localized on iOS**, because they are passed as a plain Swift `String` to `Text(verbatim:)`, `Label(String,…)` or `Callout(verbatim:)`. These strings have no key in `translations/*.json`, so iOS shows them in English. Each one is marked **[EN-only on iOS]** below.
  - Android can display them through the same English-keyed lookup with English fallback (§17). That keeps today's output identical and lets translations be added later.
  - Ownbit already ships translations of two of them, in `core/l10n/src/main/res/values*/strings_public_protocol.xml`: `publicprotocol_scan_reading` and `publicprotocol_scan_parts`.
- Engine-authored text is always English and is never changed by the host. This covers field labels, actions, headlines, warnings and error messages. Only the UI translates labels, actions and headlines, at draw time, through `ReviewText` (§17).
  - Warnings, the summary, the title on the result page, and every engine error message are shown verbatim.

---

## 1. State model (port of `AppStore` signing state)

All of this state lives in one app-wide store (`Models/AppStore.swift:17-36`). It is in memory only, apart from receipts.

| Property | Type | Meaning |
|---|---|---|
| `review` | `TransactionReview?` | The decoded request under review. Non-nil means the Scan tab shows the review page. |
| `plan` | `SigningPlan?` | Fixed signing tasks after matching. |
| `matchingError` | `String?` | Why the request cannot be signed. English and shown verbatim (§11.5). |
| `completedTaskIDs` | `Set<String>` | Tasks already signed. |
| `signatureFrames` | `[String]` | Response QR frames. |
| `signatureText` | `String` | Export text: `encoded.text ?? frames.joined("\n")`. |
| `signaturePayload` | `QRPayloadView?` | Developer view of the response. |
| `busy` | `Bool` | A signature is in progress. |
| `securityEpoch` | `UUID` | Changes on every `suspend()`; late results are discarded against it. |
| private `requestEpoch` | `UUID` | Changes on every `clearRequest()`. A late result for a replaced request is dropped. |
| private `pendingRequestData` | `Data?` | Frozen canonical request JSON (sorted keys). |
| private `previousResultData` | `Data?` | Frozen JSON of the last `signPlanTask` result. Fed to the next task. |
| private `planWalletSnapshot` | `{walletIDs, indexes}` | The wallet collection at matching time. |

Derived values:

- `hasSignedResponse = !signatureFrames.isEmpty || !signatureText.isEmpty` (`AppStore.swift:63`).
- `pendingTasks = plan?.tasks.filter { !completedTaskIDs.contains($0.id) } ?? []` (`:64`).
- `walletLabel(for:)` returns the wallet name, or `"Wallet"` [EN-only on iOS] (`:151`).
- `avatarIdentity(for:)` returns `walletKeyId`, or the UUID string (`:153`).
- `fingerprint(for:)` returns the master fingerprint in **uppercase**, or `"Setup required"` [EN-only on iOS] (`:157-159`).

### Operations

- **`clearRequest()`** (`AppStore.swift:95-100`) sets a new `requestEpoch` and clears `signingOperation`, `busy`, `review`, `plan`, `matchingError`, `completedTaskIDs`, `planWalletSnapshot`, `pendingRequestData`, `previousResultData`, `signatureFrames`, `signatureText` and `signaturePayload`.
- **`suspend()`** (`:86-94`) runs on background (`BitToolSignerApp.swift:41`), App Lock (`RootView.swift:60`) and screen capture (`RootView.swift:61`). It:
  1. creates new `securityEpoch`, `sensitiveEpoch` and `requestEpoch` values;
  2. sets `signingOperation = nil` and `busy = false`;
  3. drops the warm engine context;
  4. **if `review != nil && plan == nil && matchingError == nil`, sets `matchingError = "Matching interrupted. Try again."`** [EN-only on iOS].
- **`invalidateMatching()`** (`:101-108`) calls `suspend()`, then sets `plan = nil`, `completedTaskIDs = []` and `previousResultData = nil`, clears the signature fields, and sets `matchingError = "Wallet information changed. Match this request again."`.
  - It is called by `reload()` when `plan != nil`, `!hasSignedResponse`, and the wallet collection snapshot changed (`:142-144`).
  - It is also called by `sign()` (§14).
- **`requireSession(epoch, requestID)`** (`:109-112`) throws a cancellation if `securityEpoch` or `requestEpoch` changed. Every await point in the pipeline re-checks it. A cancellation is never shown as an error.

### Scan tab state machine

| State | Condition | Screen |
|---|---|---|
| Home | `review == nil && !hasSignedResponse` | `ScanHomeView` |
| Scanner (modal over Home) | `scanning == true` | `QRScannerView` |
| Review: matching | `review != nil && plan == nil && matchingError == nil` | Review page, spinner in the wallet section, empty bottom bar |
| Review: cannot sign | `matchingError != nil` | Review page, red wallet box, disabled "Cannot Sign" |
| Review: ready / partial | `plan != nil && pendingTasks non-empty` | Review page, "Sign with …" |
| Result | `hasSignedResponse` | `SignatureResultView` |

Transitions:

| From → To | Trigger |
|---|---|
| Home → Scanner | Tap on the scan card, or `router.startScanning()` |
| Scanner → Home | Close, or system escape |
| Scanner → Review | The scan completes and `prepare` succeeds |
| Home → Review | Paste or import succeeds |
| Review → Home | Reject |
| Review → Result | The final task is signed |
| Result → Home | Done |

`ScanTab` (`Features/Sign/ScanTab.swift:88-106`) picks the screen in this order: `hasSignedResponse` first, then `review != nil`, then Home. The content cross-fades with `.smooth(duration: 0.3)` on changes to `review?.requestHash` and `hasSignedResponse`.

Global hooks:

- The Scan tab shows a badge `1` while `review != nil && !hasSignedResponse` (`Features/Main/MainTabView.swift:15`).
- When `review?.requestHash` becomes non-nil, the selected tab switches to Scan (`RootView.swift:62`).
- The root alert has title `"Unable to Continue"` and button `"OK"`. It shows `store.error`, which is used for the receipt write failure (`RootView.swift:66-68`).

---

## 2. Opening the scanner from elsewhere (scan request token)

`AppRouter` (`Features/Main/AppRouter.swift:34-37, 61-76`) holds a published counter `scanRequest` and a non-published `handledScanRequest`.

- `startScanning()` sets `tab = .scan` and increments `scanRequest`. It is called by the Wallets toolbar button `"Scan"` with icon `qrcode.viewfinder` and id `wallets.scan` (`Features/Wallets/WalletsTab.swift:30`).
- `consumeScanRequest()` returns true **once** per increment.
- `ScanHomeView.openCameraIfAsked()` (`ScanTab.swift:166-170`) runs `guard router.consumeScanRequest(), store.review == nil, !scanning`, then sets `error = nil` and `scanning = true`. It runs both on appear and on change of `router.scanRequest` (`:160-161`).
  - The token is consumed even when a review is open, so the camera never pops open later.
- The handled marker must live in the router or singleton, **not** in Home's view state. Home is destroyed on review and rebuilt after Done. iOS regression #5 is described in `IMPLEMENTATION_STATUS.md:125-131`, and `ScanRequestRouterTests.swift` is the regression test.
  - Android: keep the counter in a `@Singleton` or activity-scoped `AppRouter` state holder, never in `rememberSaveable` of the Home composable.
- Selecting the Scan tab by hand never opens the camera.

---

## 3. Scan home (`ScanHomeView`, `ScanTab.swift:108-251`)

The navigation title is `"Scan"` (large). The content is a scroll view with a vertical stack, spacing 20, horizontal padding 20, bottom padding 24, and a readable max width of 640. The background is `Palette.background`.

Order of elements:

1. **Scan card**. The whole card is one button (`:172-208`).
   - Container: padding 20, full width, background `Palette.card`, corner radius 26 (continuous). The content shape is the whole rounded rect, so the entire card is tappable.
   - Accessibility label `"Scan signing request"`, id `signing.scan`.
   - Press style `PressableCardStyle` (`:254-261`): scale 0.98 and opacity 0.9 while pressed, animated `.snappy(duration: 0.18)`.
   - Content (VStack, spacing 18):
     - Icon tile: 128×128 rounded rect, radius 30, accent at 12% opacity, top padding 8. Inside it, SF `qrcode.viewfinder` at 62pt regular weight in the accent tint, with a repeating `.pulse` symbol effect. **The pulse is disabled when Reduce Motion is on.**
     - Title `"Scan a Signing Request"`: title3, semibold, primary.
     - Subtitle `"Show the unsigned transaction or message as a QR code in your other wallet."`: subheadline, secondary, centered, wraps.
     - A pill that looks like a button but is part of the card: label `"Open Camera"` with icon `camera.fill`, body semibold, colour `Brand.onAccent`, full width, vertical padding 14, accent `Capsule` background.
   - Action: `error = nil; scanning = true`. The card is **not** disabled while busy.
2. **Option tiles** (HStack, spacing 12, two equal tiles) (`:210-223`).
   - `"Paste Request"` with icon `doc.on.clipboard`, id `signing.paste`. It opens the paste sheet.
   - `"Import File"` with icon `doc.badge.plus`, id `signing.file`. It opens the file importer.
   - Tile layout: icon in title2 size and accent tint, then the title in subheadline semibold primary, spacing 8. Full width, vertical padding 18, background `Palette.card`, radius 20, `PressableCardStyle`.
   - Both tiles are **disabled while `busy`** (an import is in flight).
3. **Error**: `Callout(verbatim: error, tone: .danger)` when an import fails. It stays until the next scan-card tap or submission clears it.
4. **"How signing works"** card. Leading-aligned VStack, spacing 16, `cardBackground(padding: 18)`, radius 22. Header text `"How signing works"` in headline. Then four `StepRow`s:

| # | Title | Detail | Icon |
|---|---|---|---|
| 1 | `"Scan"` | `"Your watch-only wallet shows the unsigned request as a QR code."` | `qrcode.viewfinder` |
| 2 | `"Review"` | `"BitTool Signer decodes it and finds the matching wallet on this device."` | `text.magnifyingglass` |
| 3 | `"Authorize"` | `"Enter that wallet’s password to sign."` | `lock.fill` |
| 4 | `"Return"` | `"Scan the signature back. Your other wallet broadcasts it."` | `arrow.uturn.left` |

`StepRow` (`Design/DesignSystem.swift:237-260`):

- A 36×36 circle filled with accent at 14%, holding the icon at 15pt semibold in the accent tint. The circle is hidden from accessibility.
- The title is subheadline semibold and the detail is subheadline secondary, spacing 2. HStack spacing 14, top-aligned.
- The `number` parameter is not drawn; the icon is the visual.
- Accessibility combines the row into one element.

Other behaviour:

- `.onChange(of: store.securityEpoch) { busy = false }` (`:155`).
- The scanner is presented full screen (`:144-150`):
  - `onAppear` calls `session.start(store:)`.
  - `onDismiss` calls `session.stop(store:)`.
  - The scanner's `onScan(frame)` calls `session.receive(frame, store:) { scanning = false }`.
  - Its inputs are `progress: session.progress`, `status: session.status`, `error: session.error`, and `onReset: session.start(store:)`.

---

## 4. Paste request sheet (`PasteRequestSheet`, `ScanTab.swift:263-325`)

- Presentation: a sheet with a navigation bar. Title `"Paste Request"` (inline). The leading cancellation item is `"Cancel"`, which sets `payload = ""` and dismisses.
- Body: scroll view, padding 20, VStack spacing 14, leading-aligned.
  - Instruction: `"Paste a PSBT (Base64 or hex) or a UR signing request. Multi-part UR requests can be pasted one part per line."` in subheadline secondary.
  - Text editor:
    - Monospaced footnote font, autocorrection off, autocapitalization none.
    - Minimum height 220 and padding 10. The background is `Palette.card` with radius 16, and the system scroll background is hidden.
    - Id `signing.payload`.
  - Button `"Paste from Clipboard"` with icon `doc.on.clipboard`, subheadline semibold. If the clipboard has a string, it **replaces** `payload`. If not, nothing happens.
  - Error: `Callout(verbatim: error, tone: .danger)`.
- Bottom action bar:
  - Prominent button `ActionLabel("Review Request", busy: busy)`, id `signing.decode`.
  - Disabled when `busy` or when `payload` trimmed of whitespace and newlines is empty.
- Submit (`:311-324`):
  1. `guard !busy`, then set `busy = true` and `error = nil`.
  2. Capture the text and `securityEpoch`, then call `store.importPayload(text)`.
  3. On success: `payload = ""` and dismiss.
  4. On failure: show the error only if the epoch is unchanged and the error is not a cancellation.
  5. Always set `busy = false` at the end.
- `.onChange(of: securityEpoch) { busy = false }`.

---

## 5. Import from file (`ScanTab.swift:152-154, 225-250`)

- iOS `fileImporter` allows `[.data, .plainText, .json]` with single selection. `.data` matches any regular file, so in practice **any file** can be chosen.
- `importFile` steps:
  1. Read the file size from resource values. If the size is unknown, it is treated as 0.
  2. If **size > 512,000 bytes**, throw `"This file is larger than 512 KB."`. The limit is decimal 512,000, not 524,288.
  3. Read all the bytes.
  4. If the data starts with the PSBT magic bytes `70 73 62 74 ff` (`psbt\xff`), convert it to **Base64** (standard alphabet, padded, no line breaks).
  5. Otherwise, if the data decodes as UTF-8, use the text unchanged.
  6. Otherwise throw `"Choose a PSBT file or a text file containing a UR request."`.
  7. Call `submit(text)`.
- Any thrown error goes into the Home error callout (`error.localizedDescription`). That includes system read errors, which show the OS wording.
- `submit` (`:241-250`) follows the same busy, epoch and cancellation rules as the paste sheet, and calls `store.importPayload(text)`. While `busy`, the option tiles are disabled. There is no other spinner on Home.
- `store.importPayload(text)` (`AppStore.swift:352-360`):
  1. Call `clearRequest()` and capture the epochs.
  2. If `text.utf8.count > 262_144`, throw `"Text payload exceeds 256 KB."`.
  3. Call engine **`decodePayload`** with `{"payload": text}` (§9).
  4. Call `requireSession`.
  5. If `decoded.complete != true` or `decoded.request` is not an object, throw `"The request is incomplete. Import all QR frames."`.
  6. Call `prepare(request)` (§8).
- Engine `decodePayload` (`engine/protocols.js:261-271`):
  - It trims the input and rejects empty input with `'Empty signing payload'`.
  - Text that starts with `cHNidP` or `70736274ff` (case-insensitive) and contains only Base64 characters, hex characters and whitespace has its whitespace removed. It is then decoded as a PSBT of at most 131,072 bytes, magic checked.
  - Any other text is split on `\r?\n`, each line is trimmed, blank lines are dropped, and the lines go to `decodeQR({frames})`. This is how multi-part UR text pasted one part per line works.
  - Engine input cap: `Buffer.byteLength(payload) <= 262,144`.

---

## 6. Camera scanner (`QRScannerView`, `Features/Sign/QRScannerView.swift`)

### 6.1 API and purposes

`QRScannerView(title, hint, purpose, progress, status, error, onReset, onScan)` (`:9-17`).

- The defaults are the signing request values: title `"Scan Request"`, hint `"Point the camera at the QR code in your other wallet."`, purpose `.signingRequest`.
- The same view is reused by wallet setup (`Features/Setup/WalletSetupFlow.swift:218`) with title `"Scan Recovery QR"`, hint `"Scan a QR code containing the words (such as BitTool Signer’s recovery phrase QR code) or a Standard SeedQR."`, purpose `.recoveryPhrase`, no progress, and its own error handling.
- Android: build **one generic scanner composable** in `:core:scanner` with a `purpose` parameter.

### 6.2 Layout (ZStack, `:26-61`)

1. `Color.black` ignoring safe areas.
2. The camera preview, present **only while** `authorization == .authorized && scenePhase == .active`. It is hidden from accessibility. When the app is inactive or in the background, the camera session is torn down.
3. `ViewfinderOverlay(progress, active: authorization == .authorized)`, hidden from accessibility.
4. A VStack containing the top bar, a spacer, then the status panel. Padding: horizontal 20, vertical 12.

Whole-view modifiers:

- `.environment(\.colorScheme, .dark)` forces dark **for the scanner subtree only**. It must not be window-level; iOS fixed exactly this in `IMPLEMENTATION_STATUS.md:162-167`.
- The status bar is hidden.
- `.sensoryFeedback(.error, trigger: error)` gives error haptics when `error` becomes non-nil.
- The accessibility escape action dismisses the scanner.

### 6.3 Top bar (`:63-83`)

- Close: SF `xmark` (headline) in a 44×44 circle. The circle is glass on iOS 26 and `ultraThinMaterial` before that. Accessibility label `"Close"`. It dismisses the scanner.
- Centre: the title, headline, white.
- Torch:
  - Icon `flashlight.on.fill` when on and `flashlight.off.fill` when off, 44×44 circle.
  - Accessibility label `"Turn off flashlight"` / `"Turn on flashlight"`.
  - When the torch is unavailable it is `opacity 0` and disabled, so the space is kept and the title stays centred.
  - The torch is forced off whenever `scenePhase != .active` (`:58`).
- Foreground white.

### 6.4 Status panel (`:85-126`)

- Container: VStack spacing 12, white foreground, padding 18, max width 420. Background `.ultraThinMaterial` with radius 22.
- Animations: `.smooth(duration: 0.2)` on `error` and `progress`.
- The content is the first state that applies:

| State | Content |
|---|---|
| `authorization == .denied` or `.restricted` | Icon `camera.fill` (title size, secondary). `"Camera Access Needed"` in headline. `"Allow camera access in Settings to scan QR codes, or import the request as a file or pasted text."` in subheadline secondary, centred. Button `"Open Settings"`: bordered prominent, tint `Brand.mint`, foreground `Brand.ink`. It opens the app's settings page. |
| `error != nil` (session or protocol error) | `Label(error, systemImage: "exclamationmark.triangle.fill")` in subheadline, **orange**, centred, id `scanner.protocolError`. The text is engine or host English. If `onReset` exists, a button `"Scan Again"` (mint tint, ink text, id `scanner.reset`) calls `onReset()`. |
| Otherwise | If `0 < progress < 1`: a linear progress bar (tint mint, max width 220), then `"%lld%%"` with `Int(progress * 100)` in caption with monospaced digits, secondary, id `scanner.progress`. Next, `status` (verbatim) if present, otherwise `hint`, in subheadline centred, id `scanner.status`. Then, if `cameraError` is set: footnote, orange, centred, id `scanner.cameraError`. |

### 6.5 Viewfinder overlay (`:140-203`)

- Geometry inside the full-screen container:
  - `side = min(width - 64, height * 0.5, 340)` (points; use dp on Android).
  - `rect = (x: (width - side)/2, y: (height - side)/2 - 20, side, side)`, so the window is centred and **lifted 20**.
- Dim layer: a full rect minus the rounded rect (radius 28, continuous), filled with even-odd rule. Colour is black at **0.55** when `active` (camera authorized) and **0.8** otherwise.
- Brackets: four corner strokes in `Brand.mint`, line width 5, round cap and join.
  - Arm length = `min(w, h) * 0.16`. Each corner is drawn as a quadratic curve with radius 26.
  - Path per corner: from `(minX, minY + length)` to `(minX, minY + r)`, quad to `(minX + r, minY)` with control `(minX, minY)`, then a line to `(minX + length, minY)`. The other three corners are mirrored.
  - Pulse: scale from 0.985 to 1.015, `easeInOut(duration: 1.2)`, repeat forever, autoreverse. It starts on appear. **There is no pulse with Reduce Motion.**
- Progress ring: drawn when `progress > 0`.
  - A rounded rect of radius 28 with size `(side - 12) × (side - 12)`, trimmed from 0 to `progress`.
  - Stroke `Brand.mint` at 90% opacity, line width 3, round cap.
  - The trim starts where SwiftUI starts a rounded-rect path, near the top-left corner going clockwise. Android can use `PathMeasure.getSegment(0, length * progress)`.

### 6.6 Permission (`:21, :51-59`)

- Initial state: `AVCaptureDevice.authorizationStatus(for: .video)`.
- On `.task`: if the status is `.notDetermined`, request access. The result sets the status to `.authorized` or `.denied`. While the system prompt shows, the overlay is dimmed to 0.8 and the hint panel shows.
- On return to `scenePhase == .active`, the status is re-read, so coming back from Settings picks up a grant.
- The iOS system prompt text is `INFOPLIST_KEY_NSCameraUsageDescription` (`BitToolSigner.xcodeproj/project.pbxproj:423`): `"BitTool Signer scans signing requests from your other wallet and recovery-phrase QR codes. Camera images are processed on this device and never leave it."`. It is translated in `translations/*.json` → `infoPlist.NSCameraUsageDescription`. Android can show it in its own rationale UI (§23.5).

### 6.7 Camera configuration (`CameraPreview.Coordinator.start`, `:281-321`)

Everything runs on a serial queue `com.bittool.signer.camera` with QoS userInitiated.

1. Camera selection: the back built-in wide-angle camera, otherwise any video device. If none exists, the error is `"No camera is available. Import the request as a file instead."`.
2. Session preset `hd1920x1080` if supported.
3. If the input cannot be added, the error is `"The camera is unavailable."`.
4. If the metadata output cannot be added, the error is `"QR scanning is unavailable."`.
5. If the output does not offer `.qr`, the error is `"This camera cannot read QR codes. Import a request file instead."`.
6. Metadata types are `[.qr]` only, with the delegate on the main queue.
7. Focus is continuous autofocus with the autofocus range restricted to `.near`, where supported.
8. Start running. Torch availability is `hasTorch && isTorchAvailable`. The preview rotation follows the device through `RotationCoordinator`. The preview uses `videoGravity = .resizeAspectFill`.
9. Any thrown error is reported as `cameraError` with its `localizedDescription`.

Torch (`:337-344`): switched on the camera queue, only if the device has a torch and its mode differs.

Stop (`:346-355`):

1. Clear the callbacks.
2. Invalidate the rotation observation.
3. On the queue, turn the torch off if it is on, then `stopRunning()`.

This runs when the preview view is removed: on dismiss, on background, or when the app becomes inactive.

### 6.8 Frame handling and `QRScanFrameGate` (`:207-239, :357-371`)

For each metadata callback:

1. Keep only QR codes. If there are none, return.
2. **Prefer readable codes**: `readable = codes.compactMap(stringValue)`.
   - If `readable` is empty, the list to process is `[nil]`, meaning one "non-text" observation.
   - Otherwise it is every readable value. A binary code must never hide a readable request elsewhere in the same frame.
3. Feed each value through the gate with the monotonic uptime in seconds.

The gate's outcomes:

- `accepted(text)` calls `onScan(text)`. The scanner's `onScan` wrapper first clears `cameraError = nil`.
- `rejected(issue)` sets `cameraError` to the issue message for the current purpose.
- `throttled` does nothing.

`QRScanFrameGate`:

- `maximumBytes = 16_384` UTF-8 bytes.
- For a nil value the outcome is `rejected(.nonText)`.
- If `value.utf8.count > 16_384`, the outcome is `rejected(.tooLarge)`. A value of exactly 16,384 bytes is accepted.
- If the value trimmed of whitespace and newlines is empty, the outcome is `rejected(.empty)`.
- Otherwise the outcome is `accepted(value)` with the value **unchanged**. There is no trimming or case change; the engine validates the raw text.
- Throttle: if `next == last` (same outcome, including the same accepted text) and `time >= lastTime && time - lastTime < 1.0 s`, return `throttled`. A throttled call does **not** update `lastTime`, so a code held in view is offered again every 1 s. A clock value earlier than `lastTime` is never throttled.
- Different outcomes, such as the next animated frame, pass immediately.

Issue messages, all [EN-only on iOS]:

| Issue | `.signingRequest` | `.recoveryPhrase` |
|---|---|---|
| nonText | `"A QR code was found, but it doesn’t contain readable text. Export the request as a file instead."` | `"This QR code doesn’t contain readable words. Compact SeedQR isn’t supported; use a QR code with the words or a Standard SeedQR, or type the words."` |
| empty | `"This QR code is empty. Scan the signing request from your other wallet."` | `"This QR code is empty. Scan a QR code that contains your recovery phrase."` |
| tooLarge | `"This QR code is too large. Use an animated QR code or export a request file."` | `"This QR code is too large to be a recovery phrase."` |

Camera-setup strings are localized on iOS: `"No camera is available. Import the request as a file instead."`, `"The camera is unavailable."`, `"QR scanning is unavailable."`, `"This camera cannot read QR codes. Import a request file instead."`.

---

## 7. Scan session (`ScanSession`, `ScanTab.swift:9-86`)

This is a main-thread state holder for one scanner presentation.

Published state:

- `progress: Double = 0`
- `received: Int = 0`, the number of distinct frames handed in
- `processing: Bool`
- `error: String?`

Private state:

- `seen: Set<String>`, exact-string de-duplication for this session
- `queue: [String]`
- `token: UUID`
- `ready: Bool`
- `onComplete`

`status` (`:20-24`), both values [EN-only on iOS]:

- If `processing && received == 1`: `"Reading request…"`.
- Else if `received > 1 && progress < 1`: `"Keep the animated code in view · \(received) parts"`, i.e. `"Keep the animated code in view · %d parts"` in Android positional form.
- Otherwise nil, and the scanner shows the hint.

Algorithm:

- **`start(store)`** (`:26-40`):
  1. `reset()`, capture `id = token`.
  2. `await store.beginScan()`, which creates a fresh engine scan context and calls `scanBegin` (§9).
  3. If `token == id`, set `ready = true`. If frames were queued while starting (`onComplete` set), run `drain`.
  4. On error, if `token == id`, set `error = localizedDescription`. A typical message is an engine start failure.
- **`stop(store)`** (`:42-45`): `reset()`, then `await store.endScan()`, which drops the engine scan context.
- **`reset()`** (`:47-50`): new token; clear `seen`, `queue`, `progress`, `received`, `processing`, `error`, `ready` and `onComplete`.
- **`receive(frame, completed)`** (`:52-58`):
  - Ignore the frame if `error != nil`, if `store.review != nil`, or if the frame is already in `seen`.
  - Otherwise set `onComplete = completed`, increment `received`, append to `queue`, and call `drain`.
- **`drain`** (`:60-85`):
  1. Guard `ready && !processing && error == nil && !queue.isEmpty`.
  2. Pop the first frame, set `processing = true`, and capture `token` and `store.securityEpoch`.
  3. Run `outcome = await store.receive(frame:)`.
  4. If the token changed, return and discard the result.
  5. Set `processing = false`.
  6. **If `securityEpoch` changed** (the app was backgrounded mid-call), put the frame back at the front of the queue and return. It is retried when the next new frame arrives; the engine collector already de-duplicates it, and if it had already completed the request, re-adding it returns `complete` again.
  7. On success, set `progress = max(progress, value)`. Then if `store.review != nil`, call `completed()`, which sets `scanning = false` and so dismisses the scanner, which calls `stop()`. Otherwise call `drain` again.
  8. On failure: if it is a cancellation, return silently. Otherwise set `error = localizedDescription` and `queue = []`. The scanner shows the error and "Scan Again", and `onReset` calls `start()`, which opens a brand-new engine scan session.

`store.beginScan()` and `endScan()` are at `AppStore.swift:316-327`.

`store.receive(frame:)` (`AppStore.swift:330-341`):

1. `clearRequest()`, capture the epochs.
2. Call `scanAdd {frame}` through the scan context.
3. `requireSession`.
4. If `decoded.complete == true` and `request` is an object, `await prepare(request)`.
5. Return `decoded.progress` as a Double, or `review == nil ? 0 : 1` when it is absent.

The batch fallback `receive(frames:)` (`:343-351`) exists for test engines only. It caps frames at ≤ 2048 and total UTF-8 at ≤ 1,048,576, otherwise `"QR transfer is too large."`, and calls `decodeQR {frames}`. Android may keep it for tests.

Engine progress semantics (`engine/protocols.js:296-301`):

- UR fountain: `min(0.99, decoder.estimatedPercentComplete())`.
- BBQr: `fragments received / count`.
- Static PSBT text, or a single-part UR: 0, then complete with progress 1.
- A frame that is already in the collector returns the current status unchanged.

User-visible behaviour while `prepare` runs on the last frame: the scanner still shows the last status and progress, because progress is only updated after `receive` returns. Then it closes, and the review page is already rendered underneath.

Engine `QRCollector.add` errors end the session. They are shown verbatim in the scanner [EN-only on iOS]. They include:

- `'QR frame size exceeds limits'` (more than 4,300 characters)
- `'Empty QR frame'`
- `'Invalid QR frame count'`
- `'QR scan memory limit exceeded'`
- `'Multiple unrelated QR payloads'`
- `'Unsupported signing QR format'`, for a stray non-PSBT or non-UR code, for example an address QR
- `'Mixed UR types'`
- `'Unsupported signing UR type'`, for example scanning a pairing `crypto-account` or a signature UR
- `'Invalid UR frame'`
- `'Multiple static QR payloads'`
- `'Invalid fountain sequence'`
- `'Fountain sequence exceeds limits'`
- `'Invalid fountain fragment'`
- `'Invalid fountain fragment metadata'`
- `'Inconsistent fountain fragment size'`
- `'Mixed UR fountain sessions'`
- `'UR checksum failure'`
- `'Unsupported BBQR header, file type, or alphabet'`
- `'Invalid BBQR fragment index'`
- `'Mixed BBQR encodings or counts'`
- `'Invalid BBQR encoded data'`
- `'Nonfinal BBQR base32 fragment must end on a 5-byte boundary'`
- `'Unequal BBQR fragment lengths'`
- `'Conflicting BBQR fragment'`
- `'Oversized final BBQR fragment'`
- `'BBQR PSBT magic mismatch'`
- `'Invalid PSBT magic'`
- `'QR payload exceeds supported size'`
- CBOR errors such as `'Truncated CBOR'` and `'Indefinite or reserved CBOR encoding is unsupported'`

Unlike Ownbit's gate, a stray QR code is **not ignored**: it ends the session with an error. Keep this iOS behaviour.

The host error for a missing scan context is `"The scan session ended. Scan again."` (code `SCAN`) [EN-only on iOS] (`Core/SigningEngine.swift:173`).

---

## 8. Request preparation (`prepare`, `AppStore.swift:361-403`)

1. `clearRequest()`, capture the epochs.
2. **Freeze** the request (`:117-122`):
   - If it is not a valid JSON object, throw `"Invalid request format."`.
   - Serialize it with **sorted keys**. If the result is larger than 1,048,576 bytes, throw `"Request exceeds the supported size."`.
   - Parse it back into `immutable`. This parse can fail with `"Invalid local document."` (`:113-116`).
3. Engine **`review`** with `{"request": immutable}` (fresh context). Then `requireSession`.
4. Validate. The request is rejected unless all of these hold:
   - `requestHash` matches `^[0-9a-fA-F]{64}$`;
   - `result.chain == immutable.chain`;
   - the chain is one of `bitcoin`, `evm`, `tron` or `solana`;
   - `fields` is non-empty.

   On failure, throw `"Missing or invalid transaction commitment."`.
5. Set `pendingRequestData = data` and `review = reviewed`. **The review page appears now**, and the wallet section shows "Finding the matching wallet…".
6. If `result.canSign != true`, set `matchingError = "Unsupported signing protocol"` [EN-only on iOS] and return. Note that `reviewResult` always sets `canSign: true` (`engine/src/util.js:32-35`), so this is defensive.
7. Build the candidates, one per local wallet in display order:
   - `{"localWalletId": <UUID string>, "publicIndex": <wallet public index object>}` when the public metadata exists;
   - otherwise `{"localWalletId": <UUID>, "publicMetadataStatus": "ready"|"needsCompletion"|"invalid"}`.

   iOS uses `UUID.uuidString`, which is **uppercase**.
8. Engine **`resolveSigningPlan`** with `{"request": immutable, "wallets": candidates}` (fresh context). Then `requireSession`.
9. If the wallet collection changed during matching, set `matchingError = "Wallet information changed. Match this request again."` and return.
10. If `resolved.status != "ready"`, set `matchingError = resolved.message ?? "No matching wallet"` and return.
11. Parse `SigningPlan(resolved, expectedRequestHash: reviewed.requestHash)` (§10.8). It throws `"Invalid signing plan."` or `"Duplicate signing task."`.
12. Every task's `walletKeyId` must equal the local `publicWallets[walletID].walletKeyId`. Otherwise throw `"Invalid signing wallet identity."`.
13. Set `plan = fixed`, `planWalletSnapshot = collection` and `matchingError = nil`.
14. **Refined review**: if `resolved.review` is an object whose `requestHash` and `chain` equal the current review's and whose `fields` are non-empty, replace `review` with it. This is how Bitcoin change outputs verified from the wallet's own keys appear: the extra field `Output N ownership`, `display.note = "Change returns to your wallet"`, and the recomputed summary and fee warning (`engine/src/resolver.js:141-164`).
15. Any error thrown in steps 8–12, after `requireSession`, becomes `matchingError = error.localizedDescription`. A cancellation propagates instead.

Errors thrown by steps 1–4 propagate to the caller: the scanner error, the paste-sheet error or the Home error.

**Retry** (`retryMatching`, `AppStore.swift:405-410`) requires `plan == nil`, `!hasSignedResponse`, `review != nil` and `pendingRequestData` present. Otherwise it throws `"No interrupted or unmatched request to retry."`. It runs `prepare(object(data))`, so the review and matching run again from the frozen copy. No password is involved.

---

## 9. Engine calls (exact parameters and consumed results)

Host rules (`Core/SigningEngine.swift`):

- **Allowlist** (`:118-122`): `mnemonic`, `capabilities`, `derivePublicIndex`, `derivePublicAddresses`, `createPublicPairing`, `createWalletPairing`, `decodeQR`, `decodePayload`, `review`, `resolveSigningPlan`, `signPlanTask`, `encodeResponse`. `scanBegin` and `scanAdd` are reachable only through the scan session.
  - Any other operation fails with code `UNSUPPORTED_OPERATION` and message `"This signing operation is not supported."`.
- **Request JSON** (`:184-195`): `parameters + {"operation": name}`, serialized with sorted keys and **never interpolated into script**; it is passed as the argument of `BitToolSignerEngine.dispatch(json)`.
  - Invalid JSON fails with `INVALID_REQUEST` and `"The signing request has an invalid format."`.
  - More than **1,048,576 UTF-8 bytes** fails with `REQUEST_TOO_LARGE` and `"This request exceeds the 1 MB offline signing limit."`.
- **Contexts**: every operation in this spec gets a **fresh context**. Only `derivePublicAddresses`, `createPublicPairing` and `createWalletPairing` reuse the warm one. The scan has its own context, created by `beginScanSession` and dropped by `endScanSession`.
- **Response** (`:74-94`):
  - The response must be a string of at most **2,097,152 UTF-8 bytes** and parse as `{ok: Bool, …}`. Otherwise the error is `ENGINE_FAILURE` with `"The offline engine could not process this request."`.
  - When `ok == false`: `code = error.code` (default `SIGNING_FAILED`, truncated to 80 characters) and `message = error.message` (default `"This request could not be signed."`, truncated to 400 characters).
  - When `ok == true`: if `result` is an object, it is returned as is. A non-object result is returned as `{"value": result}`.
- **Engine start errors**:
  - `ENGINE_UNAVAILABLE`: `"The offline signing engine could not be started."`
  - `ENTROPY_UNAVAILABLE`: `"The engine’s secure entropy source could not be installed."`
  - `ENGINE_INVALID`: `"The bundled signing engine could not be loaded."`
  - `ENGINE_MISSING`: `"The bundled signing engine is unavailable. Reinstall BitTool Signer."`
- **Dispatch-level engine errors** (`engine/src/index.js:36`):
  - `LIMIT`: `'Request exceeds one megabyte.'`
  - Any uncontrolled exception: `INVALID_REQUEST` with `'The payload is malformed, unsupported or inconsistent. No signature was produced.'`
- **Input JSON rules** enforced by the engine parser (`engine/protocols.js:114-156`):
  - No duplicate keys.
  - Nesting depth ≤ 32 and ≤ 20,000 values in total.
  - Numbers must be finite. Integers must be safe; large values must be strings.
  - No trailing data.
- **Entropy**: native CSPRNG bytes are installed as frozen `crypto.getRandomValues` and `Math.random` **before** the bundle is evaluated (`:25-60`). This is covered in the engine spec.

### Operations used by this feature

| Operation | Context | Exact parameters | Result fields consumed |
|---|---|---|---|
| `scanBegin` | new scan context | `{}`, i.e. JSON `{"operation":"scanBegin"}` | Ignored; it returns `{started:true}` |
| `scanAdd` | scan context | `{"frame": <raw scanned text, unchanged>}` | `complete` (Bool), `progress` (number 0…1; may be integer 0 or 1), `request` (object, when complete) |
| `decodePayload` | fresh | `{"payload": <pasted or file text>}` | `complete` (must be true), `request` |
| `decodeQR` (fallback/tests) | fresh | `{"frames": [String]}` | `complete`, `progress`, `request` |
| `review` | fresh | `{"request": <frozen request object>}` | The whole object → `TransactionReview` (§10). The host checks `requestHash`, `chain`, `fields` and `canSign`. |
| `resolveSigningPlan` | fresh | `{"request": <frozen>, "wallets": [{"localWalletId", "publicIndex"} or {"localWalletId", "publicMetadataStatus"}]}` | `status`, `message`; when ready: `requestHash`, `planHash`, `tasks[]`, `review` (refined), and the **whole object** kept as `plan.raw` |
| `signPlanTask` | fresh | `{"request": <frozen>, "plan": <plan.raw>, "taskId": <task.id>, "seed": <128 lowercase hex>, "riskAcknowledged": Bool}`, plus `"previousResult": <previous result object>` from the second task on | `requestHash` (must equal `plan.requestHash`), `planHash` (must equal `plan.planHash`), `taskId` (must equal the task). The **whole result** is frozen and kept as `previousResultData` and passed to `encodeResponse`. |
| `encodeResponse` | fresh | `{"request": <frozen>, "result": <final signPlanTask result>}`; there is no `fragmentLength`, so the engine default of 180 applies | `frames` ([String]), `text` (optional; it is not emitted today, so the host falls back to `frames.joined("\n")`), `type`, `payloadFormat`, `payload`, `payloadBytes` for `QRPayloadView` |

Further notes:

- **`seed`** is derived natively: PBKDF2-HMAC-SHA512 with 2,048 rounds. The password is the NFKD-normalized, lowercased phrase with whitespace collapsed to single spaces. The salt is `"mnemonic" + passphrase`, NFKD-normalized. The phrase must be ≤ 1,024 UTF-8 bytes and the passphrase ≤ 1,024 (`Core/BIP39Seed.swift:20-50`).
  - On Android, implement PBKDF2 over raw UTF-8 bytes using `Mac("HmacSHA512")`. Do **not** use `SecretKeyFactory("PBKDF2WithHmacSHA512")` with a `char[]`, because its encoding is provider-defined.
- **`resolveSigningPlan` results** (`engine/src/resolver.js:17-18, 166-224`):
  - On failure: `{version: 1, status: "noMatch"|"missingSigner"|"ambiguous"|"setupRequired"|"invalid", message, tasks: [], review?}`.
  - Messages: `noMatch` → `'No matching wallet'`, `missingSigner` → `'Missing signer information'`, `ambiguous` → `'Ambiguous wallet match'`, `setupRequired` → `'Wallet setup required'`.
  - For `invalid`, the message is the controlled engine message, or `'Invalid or unsupported signing request'`.
  - On success: `{version: 1, status: "ready", requestHash, payloadDigest, protocolProfile, originalPayloadBytes, signingBytesDigest, chain, review, tasks: [{id, localWalletId, walletKeyId, chain, targets: [{path, publicKey, address, inputIndex?, leafHashes?, signerSlot?, …}], paths, publicKeys, addresses, inputs, signatureAlgorithm, signingBytesDigest}], planHash}`. Tasks are sorted by `localWalletId` as a string.
- **`signPlanTask`** requires `riskAcknowledged === true` when the parsed `decodeStatus` is `partiallyDecoded` or `opaque`. Otherwise the error is `RISK` with `'Confirm this request’s decoding risks before signing'` (`engine/src/resolver.js:233`). The engine does **not** require acknowledgement for `riskLevel == high`; the host UI does (§11.7).
- **Response transport** (`engine/protocols.js:398-416`): the response uses the request's own transport.
  - `crypto-psbt` → `crypto-psbt`; `psbt` → `psbt`.
  - BBQr → BBQr in the same encoding (`H` or `2`).
  - `psbt-base64` / `psbt-hex` → a static text QR if the text is ≤ 1,800 characters, otherwise an animated `crypto-psbt`.
  - `eth-` / `sol-` / `tron-sign-request` → `*-signature` echoing `requestId`.
  - **UR frames are uppercase** (`:367-372`), which suits QR alphanumeric mode.
  - The payload view is decoded back from the produced frames (`describeQR`, `:482-525`). For a PSBT it is `{psbt_base64, decoded?}`.

---

## 10. Review result JSON and model parsing

### 10.1 Engine review object

The review object is produced by `reviewResult(request, title, summary, fields, warnings, details)` (`engine/src/util.js:32-35`).

```jsonc
{
  "chain": "bitcoin" | "evm" | "tron" | "solana",
  "title": "Sign Bitcoin transaction" | "Sign Ethereum message" | "Sign typed data" | "Approve a Safe transaction" | "Authorize token spending" | "TRX transfer" | "Native transfer" | … (English),
  "summary": "Send 0.001 BTC" | "Personal signature (EIP-191)" | "<title>: <amount> <symbol>" | …,
  "fields": [{"label": "Network", "value": "Ethereum"}, …],   // value always a string
  "warnings": ["HIGH FEE: …", "UNLIMITED spending approval.", "…"],
  "requestHash": "<64 lowercase hex>",   // SHA-256 of the sorted-key canonical JSON of the request
  "canSign": true,
  "decodeStatus": "fullyDecoded" | "partiallyDecoded" | "opaque",
  "riskLevel": "normal" | "warning" | "high",
  "network": {"chainId": "1", "name": "Ethereum", "symbol": "ETH"},  // optional; chainId EVM only
  "display": { … },   // optional, see 10.4
  "contract": { … },  // optional, see 10.5
  "asset": { … }      // optional, see 10.6
}
```

Default risk: `riskLevel` is `warning` if there are warnings, otherwise `normal`. Chain parsers override it:

- **Bitcoin**: always at least `warning`, and `high` if any warning starts with `HIGH` (`engine/src/bitcoin.js:161`).
- **EVM**: `high` on `HIGH FEE` and on opaque or delegatecall calls. An unlisted spender or operator raises the level to at least `warning` (`engine/src/evm.js:693-700`).
- **TRON**: `high` for type 46, or for warnings matching `/approval|authoriz|HIGH RISK|HIGH FEE/i` (`engine/src/tron.js:97`).
- **Messages**: always `high` on EVM and TRON.

Network objects:

- Bitcoin: `{name: 'Bitcoin', symbol: 'BTC'}`
- TRON: `{name: 'TRON', symbol: 'TRX'}`
- Solana: `{name: 'Solana', symbol: 'SOL'}`
- EVM: from the chain ID, one of `1` Ethereum ETH, `56` BNB Smart Chain BNB, `42161` Arbitrum One ETH, `8453` Base ETH, `10` OP Mainnet ETH, `137` Polygon PoS POL, `100` Gnosis xDAI, `43114` Avalanche C-Chain AVAX (`engine/src/token-registry.js:4`).

### 10.2 `TransactionReview` parsing (`Models/Models.swift:357-402`)

- `chain`: an unknown value parses as `.bitcoin`. `prepare` has already validated it.
- `title` defaults to `"Review request"`. `summary` defaults to `""`.
- `fields`: `[{label: String ?? "", value: String(describing: value ?? "")}]`, with `id` equal to the array index.
- `warnings`: an array of strings.
- `requestHash`: a string, default `""`.
- `decodeStatus`: unknown parses as `.opaque`.
- `riskLevel`: unknown parses as `warnings.isEmpty ? .normal : .warning`.
- `asset`: decoded from `asset`. On any failure it is nil. The required keys are `chainFamily`, `assetAddress`, `name`, `symbol`, `decimals` (Int) and `icon` (String). The optional keys are `chainId`, `tokenProgram`, `rawAmount` and `displayAmount`.
- `network`: present only if `network.name` is a string. Its family is the review chain, plus `chainId` and `symbol`.
- `display`: `ReviewDisplay(raw["display"])`. `contract`: `ReviewContract(raw["contract"])`.
- `requiresAcknowledgement = decodeStatus != .fullyDecoded || riskLevel == .high`.
- `networkName`: `network.name`, otherwise the value of the field labelled `"Network"`, otherwise the chain name (`"Bitcoin"`, `"EVM"`, `"TRON"` or `"Solana"`). Shown verbatim.

### 10.3 `ReviewField` helpers (`Models.swift:51-94`)

- `group`: the text before `" · "` (for example `"Instruction 2 · Recipient"`). `shortLabel`: the text after it, or the whole label.
- `isLong = value.count > 280 || newline count > 6`.
- `baseLabel`: take `shortLabel`, drop everything up to the **last** `": "`, trim, then lowercase.
- `isTokenIdentifier`: `baseLabel` contains `"token id"`, or contains `"quantity"`, or equals `"items"`, or starts with `"items "`.
- `holdsNumber`: `isTokenIdentifier`, or `baseLabel` contains `"amount"`, `"allowance"`, `"raw units"` or `"(wei)"`.
- `rendersAsAddress = !holdsNumber && AddressFormatter.looksLikeAddress(value)`.
- `AddressFormatter.looksLikeAddress` (`Design/AddressText.swift:31-38`):
  - Trim the value. It must contain no spaces or newlines and be 25–90 characters long.
  - It must then match one of `^0x[0-9a-fA-F]{40}$`, `^T[1-9A-HJ-NP-Za-km-z]{33}$`, `^(bc1|[13])[0-9A-Za-z]{20,87}$` or `^[1-9A-HJ-NP-Za-km-z]{32,44}$`.

### 10.4 `ReviewDisplay` (`Models.swift:218-343`)

Engine keys:

- `action` is required. Without it the display is nil.
- Optional strings: `amount`, `symbol`, `headline`, `counterpartyLabel`, `counterparty`, `fee`, `feeKind` (`"energyBudget"` for TRON's `fee_limit`), `note`.
- NFT-only: `assetKind`, `assetContract`, `assetContractLabel`, `tokenId`, `give[]`, `receive[]`, `others[]`.

Parsing:

- The original keys are read leniently: each is taken as a string or treated as absent.
- `assetKind` must be one of `nft`, `nftOrToken`, `multiToken` or `nftOrder`. **If it is absent or unknown, every NFT key is ignored** (fail closed).
- `boundedValue`: the value must be a string that is non-blank and at most **300** characters. Otherwise it is nil. It is never truncated.
- `boundedLines`:
  - The value must be an array made up **only** of strings. Otherwise the result is empty.
  - Blank lines are dropped.
  - A line longer than 300 characters is cut in the middle: the first 149 characters, then `"…"`, then the last 150.
  - If there are more than **32** lines, the result is the first 31 followed by `"+\(n) more (see Details)"` [EN-only on iOS], where `n = total - 31`.
- `assetContractCaption = assetContractLabel ?? "NFT contract (not verified)"`. It is shown verbatim, so it is [EN-only on iOS].
- `tokenIDIsAmbiguous = assetKind == .nftOrToken`.
- `summaryTokenID`: for an NFT with a `tokenId`, it is nil when `headline` or `amount` already contains `"#" + id` not followed by an ASCII digit. Otherwise it is the `tokenId`.
- `showsAssetBox = isNFT && (assetContract != nil || summaryTokenID != nil)`.
- `flowSections` (NFT only): `give`, `receive`, `others` in that order, skipping empty sections.
- `spokenTokenID`: an ID longer than 12 characters is read in groups of 4 joined by `", "`.

Real display fixtures are in `BitToolSignerTests/NFTReviewDisplayTests.swift:17-38`. Examples: `{"action":"Send NFT","headline":"NFT #79233663…80642508 (77 digits)","counterpartyLabel":"To",…,"assetKind":"nft","assetContract":…}` and Seaport `{"action":"List NFT for Sale (Seaport 1.6)","amount":"0.95","symbol":"ETH","headline":"NFT #1234 · 0xBC4C…f13D","counterpartyLabel":"Marketplace",…}`.

Bitcoin display (`engine/src/bitcoin.js:59-75`):

- `{action: "Send", fee: "<x> BTC", amount, symbol: "BTC", counterpartyLabel: "To", counterparty}` for one payment.
- For several payments the `headline` is `"N recipients · X BTC"`.
- With no payment: `action: "Consolidate"`.
- With a burn: `action: "Burn"`.
- When change is verified: `note: "Change returns to your wallet"`.

EVM display: `fee: "<maxFee> <symbol>"` (`engine/src/evm.js:706`).

### 10.5 `ReviewContract` (`Models.swift:129-176`) and the known-contract library

Engine shape: `{address, role: "spender"|"operator"|"contract", known: Bool, name?, protocol?, category?, everyChain?, token?}`.

- It is set by `counterpartyNotice` (`engine/src/evm.js:485-499`), with an equivalent in `tron.js`. It is skipped for `SafeTx`, and Solana never sets it.
- The address looked up is the one the request authorizes (`docs/KnownContracts.md:183-201`):

| Request | Address looked up | Role |
|---|---|---|
| approve / increaseAllowance | the spender | `spender` |
| setApprovalForAll | the operator | `operator` |
| EIP-712 naming a `spender` or `operator` | that address | `spender` |
| any other call | the called contract | `contract` |
| typed data without one | `domain.verifyingContract` | `contract` |

- On a hit, the engine also appends a field with label `"Spender (known contract)"`, `"Operator (known contract)"`, `"Contract (known)"` or `"Verifying contract (known)"` and value `"<name> — <protocol> · <category>"`, with `" (same address on every EVM network)"` added for CREATE2 entries.
- On a miss where the address is a token in the registry: `token: "<name> (<symbol>)"`.
- On a miss otherwise, a warning is pushed, and for a spender or operator the risk is raised to `warning`.

Host parsing:

- `address` is a `boundedValue`. Without it there is no contract.
- An unknown `role` parses as `contract`.
- `known = raw.known == true && boundedValue(name) != nil`. "Known" without a name is not treated as a hit.
- If not known: `name = nil`, `token = boundedValue(raw.token)`, and nothing else is read.
- If known: `project = boundedValue(protocol)`, `category = boundedValue(category)`, `everyChain = Bool`.
- `subtitle`: the non-empty parts of `[project, category]` joined with `" · "`, or nil.

### 10.6 Asset, network and brand

`ReviewNetwork.brand` (`Models.swift:116-121`, `Design/Logos.swift:63-81`):

- EVM: `NetworkBrand.evm(chainID)`, which maps chain IDs `1`, `56`, `42161` and `8453` to ethereum, bnb, arbitrum and base. Any other chain ID has no brand.
- Other families: the family's primary network (bitcoin, tron, solana).

Token icons come from `TokenIcons.pack` plus `TokenIcons.index.json`, keyed by the `asset.icon` id (`Services/TokenIconStore.swift`). When there is no icon, a `systemGray5` circle shows the first 3 letters of the symbol, uppercased, in rounded bold at 30% of the size.

### 10.7 `QRPayloadView` (`Models.swift:595-650`)

- `payloadFormat` must be `json`, `hex` or `text`. Otherwise the view is nil and the disclosure is hidden.
- For `json`, `payload` must be an object. It is formatted as **pretty, key-sorted JSON without escaped slashes**; Apple's style is 2-space indent and `"key" : value`.
- For `hex` or `text`, `payload` must be a non-empty string.
- `byteCount = payloadBytes ?? text.utf8.count`. `qrType = type`.
- The BBQr encoding character is `frames[0][2]` when the frame starts with `B$`.
- `title`: `"Payload (JSON)"`, `"Payload (Hex)"` or `"Payload (Text)"`. Only the JSON title has a translation key.
- `summary`, verbatim [EN-only on iOS]. Here `bytes` is `"<n formatted with locale grouping> bytes"`.
  - `bbqr-psbt`: `"BBQr · PSBT (hex|Base32) · <bytes>"`, with `hex` when the encoding is `H`.
  - `psbt-base64`: `"Plain text · PSBT Base64 · <bytes>"`.
  - `psbt-hex`: `"Plain text · PSBT hex · <bytes>"`.
  - nil or `address`: `"Plain text · <bytes>"`.
  - any other type: `"ur:<type> · CBOR · <bytes>"`.
- `isPSBT`: `type` is one of `crypto-psbt`, `psbt`, `bbqr-psbt`, `psbt-base64` or `psbt-hex`.

### 10.8 `SigningPlan` and `SigningTask` (`Models.swift:652-702`)

Plan checks (otherwise `"Invalid signing plan."`):

- `status == "ready"`;
- `requestHash == expected`;
- `planHash` matches 64 hex characters;
- `tasks` is non-empty with **≤ 64** tasks.

Each task is checked (otherwise `"Invalid signing plan."`):

- `id` is non-empty;
- `localWalletId` parses as a UUID;
- `walletKeyId.count == 64`;
- `chain` is valid;
- `paths` is a non-empty `[String]`;
- `publicKeys.count == paths.count`.

Then:

- Optional task fields: `addresses` defaults to `[]`, `inputs` (`[Int]`) defaults to `[]`, and `signerSlot = targets[0].signerSlot`.
- Duplicate task IDs fail with `"Duplicate signing task."`.
- `plan.raw` is the whole resolved object, kept as sorted-keys JSON and sent back **unchanged** to `signPlanTask`. The engine recomputes `planHash` over it.
- `displayAddresses` and `displayPaths` are the non-empty distinct values, in order.

---

## 11. Review page (`RequestReviewView`, `RequestReviewView.swift:5-217`)

### 11.1 Frame

- Navigation title `"Review Request"` (inline). Background `Palette.background`.
- Content: a scroll view with a VStack, spacing 16, horizontal padding 20, bottom padding 20, readable width 640.
- Toolbar, cancellation placement: `"Reject"` with the destructive role, id `signing.reject`.
  - If `completedTaskIDs` is empty, `reject()` runs immediately.
  - Otherwise a confirmation dialog appears with title `"Discard partial signatures?"` (title visible), the destructive button `"Reject Request"`, and the message `"Signatures already created for this request will be discarded."`.
- `reject()` sets `riskAccepted = false`, then calls `store.clearRequest()` inside `withAnimation`.
- `riskAccepted` is reset on `securityEpoch` change (which also sets `authorizing = nil`, so the sheet closes), on `requestHash` change, on reject, and **after every successful signature** (the `AuthorizeSheet` `onSigned` callback). For multi-wallet requests, the acknowledgement must be ticked again for each wallet.

### 11.2 Section order (`:16-34`)

1. `ReviewSummaryCard`.
2. `ContractNotice` card, only if `review.contract != nil && !ReviewSummaryCard.showsNotice(for: review)`. This is the case where the authorized address is not the counterparty shown in the summary, for example a token transfer that authorizes the token contract.
3. Warnings, if any.
4. Signing wallet(s).
5. `ReviewDetailsCard(fields)`.
6. Request fingerprint: `"Request fingerprint"` in caption semibold secondary, then `HashText(requestHash)`, leading-aligned, horizontal padding 4.
   - `HashText` (`Design/AddressText.swift:65-76`) draws the hash **uppercased** in groups of 8 joined by spaces, in caption monospaced secondary, wrapping and selectable.

Below the content is the pinned bottom bar with the acknowledgement and the Sign button (§11.7).

### 11.3 Summary card (`ReviewSummaryCard`, `:221-461`)

Card: padding 18, full width, background `Palette.card`, radius 26. Accessibility `.contain`. VStack spacing 14, in this order:

1. **Badges row**. Side by side if they fit, stacked and leading-aligned otherwise (`ViewThatFits`). Use stacking at large font scales rather than truncating.
   - Network badge (`:436-449`):
     - Logo at 18: the brand logo; else, for non-EVM chains, the chain logo; else `GenericNetworkLogo`.
     - Then `networkName` (caption semibold, one line, verbatim).
     - Padding h10 v5 on a `Palette.raised` capsule.
   - Decode badge (`StatusPill`, `:451-460`):

| decodeStatus | Text | Icon | Colour |
|---|---|---|---|
| fullyDecoded | `"Decoded on device"` | `checkmark.seal.fill` | success (green) |
| partiallyDecoded | `"Partially decoded"` | `exclamationmark.triangle.fill` | warning (orange) |
| opaque | `"Not decoded"` | `questionmark.diamond.fill` | danger (red) |

2. **Icon** at 60, top padding 4 (`:424-434`). The first that applies:
   - `review.asset` → `TokenIcon`;
   - `display.assetKind` → `NFTGlyph` (§11.4);
   - `network.brand`, or for non-EVM chains `chain.primaryNetwork` → `NetworkLogo`;
   - EVM → `GenericNetworkLogo` (a `systemGray5` circle with `point.3.connected.trianglepath.dotted` at 46% size).
3. **Text block** (VStack spacing 6):
   - Action line: `ReviewText.phrase(display?.action ?? review.title)`. Subheadline semibold, secondary, centred.
   - **Amount**: present if `display.amount` exists, as `amount + " " + symbol` (symbol omitted when nil).
     - Large title in the rounded design, bold, centred, minimum scale 0.5, selectable, id `review.headline`.
     - Line limit 2. **NFT: no line limit, and it wraps vertically.** NFT order prices can have up to 78 raw digits.
   - Secondary headline, when `display.amount != nil` and `display.headline` exists: `ReviewText.headline(headline)` in subheadline secondary, centred.
   - Text headline: present when a display exists and `display.amount == nil`, showing `display.headline`. Without a display, it shows `review.summary` if non-empty.
     - It renders as `ReviewText.headline(…)` in title3 semibold, centred, selectable, id `review.headline`.
     - Line limit 8. **NFT: unlimited.**
4. **Asset box** if `display.showsAssetBox` (`:353-390`). Padding 12, `Palette.raised`, radius 16, VStack spacing 10.
   - When `assetContract` exists:
     - The caption is `assetContractCaption`, verbatim, in caption semibold secondary, centred.
     - Below it, `AddressText(contract)` in callout, centred.
     - The block is combined for accessibility, id `review.assetContract`.
   - When `summaryTokenID` exists:
     - A divider if there is a contract above.
     - The caption is `"Token ID or raw amount"` when ambiguous, otherwise `"Token ID"`.
     - The ID is shown in callout monospaced, centred, wrapping and selectable. The accessibility label is `spokenTokenID`, id `review.tokenId`.
5. **Flow box** (marketplace orders) if `flowSections` is non-empty (`:394-422`). Padding 12, raised, radius 16, leading-aligned, spacing 12, with a divider between sections.
   - Each section has a label in caption semibold secondary:
     - give: `"You give"`, icon `arrow.up.right`, tinted warning;
     - receive: `"You receive"`, icon `arrow.down.left`, tinted accent;
     - others: `"Also paid out"`, icon `arrow.triangle.branch`, tinted secondary.
   - Then each line in callout, verbatim, wrapping and selectable.
   - Accessibility combines each section, id `review.flow.give|receive|others`.
6. **Counterparty box**, when present (`:227-237, :312-325`):
   - The counterparty comes from `display` when a display exists: `display.counterparty` (none if nil), labelled `display.counterpartyLabel ?? "To"`.
   - Without a display, take the fields whose `shortLabel` is `Recipient`, `Spender` or `Operator` **and** whose value looks like an address. Use it only if exactly one matches; the label is `"To"` for Recipient, otherwise the `shortLabel`.
   - Rendering:
     - Label: `ReviewText.phrase(label)` in caption semibold secondary.
     - `AddressText(value)` in callout, centred.
     - When `showsNotice` holds, the compact `ContractNotice` follows. `showsNotice` means a contract exists, a counterparty exists, and the counterparty value equals `contract.address` case-insensitively.
     - Padding 12, raised, radius 16, combined for accessibility.
7. **Note**: `Label(display.note, "checkmark.shield.fill")` in footnote semibold, success colour. Verbatim [EN-only on iOS], for example `"Change returns to your wallet"`.
8. **Fee**: `shown = ReviewText.shortDecimal(display.fee)` (§17). Footnote secondary.
   - If `feeKind == "energyBudget"`: `"Energy budget up to %@ · total fee not known offline"`.
   - Otherwise: `"Network fee up to %@"`.
9. **High risk pill** if `riskLevel == .high`: `StatusPill("High risk", "exclamationmark.shield.fill", danger)`.

`AddressText` (`Design/AddressText.swift:43-62`):

- Monospaced, in groups of 4. A `0x` prefix stays attached to the first group.
- The first and last groups are semibold and primary. The middle groups are regular, primary at 78% opacity.
- The accessibility label reads the groups joined by `", "`.
- It is not selectable.

### 11.4 NFT glyph (`NFTGlyph`, `:662-690`)

This is an offline mark; artwork is never fetched.

- A rounded square with radius `size × 0.26`, filled with accent at 14%.
- The icon is drawn at `size × 0.44`, semibold, accent:
  - `nft` and `nftOrToken`: `photo.artframe`
  - `multiToken`: `square.stack.3d.up.fill`
  - `nftOrder`: `tag.fill`
- For `nftOrToken` only, a bottom-trailing badge:
  - a `questionmark` at `size × 0.2`, heavy, white;
  - on a warning-coloured circle of `size × 0.38`;
  - with a ring in the card colour of `max(1.5, size × 0.04)`;
  - offset by `(size × 0.12, size × 0.12)`.
- Accessibility ignores the children. Labels:
  - `nft`: `"NFT"`
  - `nftOrToken`: `"NFT or token"`
  - `multiToken`: `"ERC-1155 token"`
  - `nftOrder`: `"NFT order"`

### 11.5 Known-contract notice (`ContractNotice`, `:547-657`; `docs/KnownContracts.md:203-218`)

A listing identifies a deployment. **It is never green.**

| Kind | Condition | Colour | Icon | Title | Detail |
|---|---|---|---|---|---|
| known | `contract.known` | warning (amber/orange) | `checkmark.seal.fill` | by role: `"Known public contract (spender)"`, `"Known public contract (operator)"`, `"Known public contract"` | `[name, subtitle]` joined with `" — "`, for example `Permit2 — Uniswap · infra` |
| token | not known, `token != nil` | accent | `info.circle.fill` | `"Recognized token contract"` | the token text, for example `Tether USD (USDT)` |
| unlisted spender/operator | not known, no token, role ≠ contract | danger | `exclamationmark.triangle.fill` | `"Spender is not a known contract"` / `"Operator is not a known contract"` | none (the engine warning explains) |
| unlisted contract | not known, no token, role = contract | secondary | `questionmark.circle.fill` | `"Contract is not in the known list"` | none |

- **Compact form**, inside the counterparty box:
  - The title as a label with its icon, in caption semibold, in the kind colour.
  - The detail in caption secondary, centred.
  - If `everyChain`, the note `"Same address on every EVM network."` in caption2 secondary.
  - Top padding 2, combined, id `review.contractNotice`.
- **Card form**:
  - An HStack aligned on the first text baseline, spacing 10: the icon (accessibility hidden), then a VStack spacing 4.
  - The VStack holds the title (subheadline semibold), the detail (subheadline secondary), the role caption (`"Spender"`, `"Operator"` or `"Contract"`, caption2 semibold secondary), `AddressText(address)` in footnote, and the every-chain note.
  - Padding 14, background the kind colour at 12%, radius 16, combined, id `review.contractNotice`.

### 11.6 Warnings (`:71-82`)

- A VStack with spacing 10. Each warning is a `Callout(verbatim: warning)`, so warnings are **never translated**.
- Tone is **danger** when `warning.uppercased()` starts with `HIGH RISK`, `UNLIMITED` or `HIGH FEE`. Otherwise it is **warning**.
- The engine keeps the severity prefix in front of indexed warnings (`engine/src/nft-orders.js:50-55`).

`Callout` (`Design/DesignSystem.swift:136-184`):

- An HStack aligned on the first text baseline, spacing 10.
- The tone icon (accessibility hidden): info `info.circle.fill` in accent, success `checkmark.circle.fill`, warning `exclamationmark.triangle.fill`, danger `exclamationmark.octagon.fill`.
- The text in subheadline primary, wrapping.
- Padding 14, background the tone colour at 12%, radius 16, combined for accessibility.

### 11.7 Signing wallet section (`:84-132`) and matching statuses

- Header (headline): `"Signing Wallets"` when `plan.tasks.count > 1`, otherwise `"Signing Wallet"`. VStack spacing 12.

**State A: `matchingError != nil`.**

- A box with padding 16, background danger at 10%, radius 22.
- `Label(message, "xmark.octagon.fill")` in subheadline semibold, danger, verbatim [EN-only on iOS], id `signing.matchError`.
- The explanation in footnote secondary, wrapping. It is selected by the exact English message:

| Engine status / source | Message (verbatim) | Explanation (localized) | Retry? |
|---|---|---|---|
| `noMatch` | `"No matching wallet"` | `"This request does not belong to any wallet on this device. Check that you scanned the request for this wallet."` | no |
| `missingSigner` | `"Missing signer information"` | `"The request does not say which key should sign. Export it again from your other wallet with its account details."` | no |
| `ambiguous` | `"Ambiguous wallet match"` | `"More than one saved wallet matches. Delete the duplicate wallet, then scan again."` | no |
| `setupRequired` | `"Wallet setup required"` | `"A wallet needs repair before it can be matched. Open it in Wallets and repair its data."` | no |
| host (`canSign == false`) | `"Unsupported signing protocol"` | `"BitTool Signer can show this request but cannot sign this kind of request."` | no |
| `invalid` (the engine passes its controlled error message, see `engine/src/resolver.js:199`) or a thrown error | for example `'Unsupported signing request'`, `'Invalid master fingerprint'`, `'Invalid signer public key'`, `'Invalid or unsupported signing request'`, `"Invalid signing plan."`, `"Invalid signing wallet identity."`, or engine host errors | default `"This request cannot be signed. Nothing has been signed."` | no |
| host `suspend()` during matching | `"Matching interrupted. Try again."` | default (as above) | **yes** |
| host collection change | `"Wallet information changed. Match this request again."` | default | **yes** |

- `canRetry(message)` is true when the message contains `"interrupted"` or `"changed"` (`:134-136`).
- The retry button shows only when `plan == nil && !hasSignedResponse && canRetry`:
  - Label `"Match Again"` with icon `arrow.clockwise`, or `"Matching…"` while retrying. Subheadline semibold, id `signing.retryMatch`, disabled while retrying.
  - Action: `store.retryMatching()`. An error, unless the epoch changed or it is a cancellation, shows as `retryError` in footnote danger.

**State B: `plan != nil`.**

- One `TaskCard` per task (§11.8).
- If there is more than one task, a footnote in secondary: `"Each wallet signs with its own password. The final signature appears after every wallet has signed."`.

**State C: matching in progress.**

- An HStack with spacing 10: an indeterminate spinner and `"Finding the matching wallet…"` in subheadline secondary, inside `cardBackground()`.

**Bottom bar** (`signBar`, `:186-206`) inside `bottomActionBar`: VStack spacing 10, horizontal padding 20, top padding 8–10, bottom padding 8, pinned above the home indicator.

- If `pendingTasks.first` exists and `matchingError == nil`:
  - Compute `blocked = review.requiresAcknowledgement && !riskAccepted`.
  - If `requiresAcknowledgement`, show the **acknowledgement row** (`:156-184`):
    - It is a whole-row plain button that toggles `riskAccepted` with `.snappy(0.15)`.
    - Icon (title3): `square` in secondary when unticked, `checkmark.square.fill` in warning colour when ticked, with a replace transition.
    - Title (subheadline semibold primary): `"I understand the risks of this request"` when `fullyDecoded`, otherwise `"I understand this request can’t be fully verified"`.
    - Subtitle (caption secondary): `"Read every warning above before signing."` when `fullyDecoded`, otherwise `"Some effects couldn’t be decoded on this device."`.
    - Padding h14 v10, background warning at 12%, radius 16.
    - Selection haptic on toggle. Accessibility value `"Checked"` or `"Not checked"`, the selected trait when ticked, id `signing.risk`.
  - If `plan.tasks.count > 1`: `"Signature %lld of %lld"` with `(completedTaskIDs.count + 1, total)`, in footnote semibold secondary.
  - The prominent button `"Sign with %@"` with `walletLabel(task.walletID)`, icon `signature` and `busy: store.busy`.
    - It is disabled when `blocked || store.busy`, id `signing.authorize`.
    - It opens `AuthorizeSheet(task: pendingTasks.first, riskAcknowledged: riskAccepted)`.
- Else if `matchingError != nil`: a secondary disabled button `"Cannot Sign"` with icon `xmark.circle`, non-prominent, id `signing.authorize`.
- Otherwise, while matching, the bar is empty.

### 11.8 Task card (`TaskCard`, `:768-817`)

- `cardBackground()`, VStack spacing 12, leading-aligned.
- Header row (HStack spacing 12):
  - `WalletAvatar` at 42 (`Design/Logos.swift:190-220`): a rounded rect with radius `0.3 × size`.
    - It is filled with a top-leading to bottom-trailing linear gradient derived from `SHA-256(identity UTF-8)`.
    - `hue = (b0<<8 | b1) / 65535`. The first colour is HSB(hue, 0.62, 0.78). The second is HSB((hue + 0.08 + b2/255 × 0.14) mod 1, 0.7, 0.62).
    - The initial is the first character of the trimmed name, uppercased, or `"W"`. It is drawn at 0.44 × size, semibold rounded, white, with a shadow of black 15% radius 1 y 1.
  - The name in headline, then `"Fingerprint %@"` (uppercase master fingerprint) in caption monospaced secondary.
  - Trailing: when done, `StatusPill("Signed", "checkmark", success)`. Otherwise `lock.fill` in secondary with accessibility label `"Awaiting password"`.
- When `showsAddress` (no Details field labelled exactly `"Requested signer"`): for each distinct address, `"Signing address"` in caption secondary, then `AddressText` in footnote.
- When `showsPath` (no field labelled `"Derivation path"` whose value starts with `"m/"`):
  - `"Derivation paths"` when there are several distinct paths, otherwise `"Derivation path"`, in caption secondary.
  - The paths joined with `"\n"` in footnote monospaced.
- When `inputs` is non-empty: `DetailLine("Inputs signed", "#1, #2, …")`. Inputs are 1-based and the value is not monospaced.
- When `signerSlot` exists: `DetailLine("Signer position", "\(slot + 1)")`.

`DetailLine` (`Design/DesignSystem.swift:263-290`) draws the label in caption secondary and the value in footnote (monospaced when requested), selectable and combined for accessibility.

### 11.9 Details card (`ReviewDetailsCard`, `:694-764`)

- The title `"Details"` in headline, bottom padding 10.
- Then one card (`cardBackground()`) with the rows separated by dividers with vertical padding 10. Each row is a VStack with spacing 5.
- Row label: `ReviewText.label(field.label)` in caption semibold secondary.
- Row style (`rowStyle`), decided by label first, then by value:

| Style | Condition | Rendering |
|---|---|---|
| address | `rendersAsAddress` | `CopyableAddress` in footnote (below) |
| identifier | `isTokenIdentifier && value.count <= 4096` | The full value, monospaced footnote, selectable, never collapsed |
| collapsible | `isLong` | Shows `prefix(280) + "…"` until expanded, then the full value; monospaced footnote, selectable. Button `"Show All"` / `"Show Less"` (footnote semibold) toggles it with `.snappy`. Expansion state is per field index. |
| text | otherwise | Monospaced footnote if the value looks technical (starts with `0x` or `m/`, matches `^[0-9a-fA-F]{16,}$`, or contains `\n`), otherwise subheadline; selectable |

`CopyableAddress` (`Design/AddressText.swift:79-109`):

- `AddressText` with a trailing 30×30 borderless button showing `doc.on.doc`, or `checkmark` for 1.5 s after copying.
- Accessibility label `"Copy address"` or `"Copied"`. Success haptic.
- Context menu `"Copy Address"` with icon `doc.on.doc`.
- Copying uses `Clipboard.copy`: local-only, expiring after **120 s** (`Design/DesignSystem.swift:293-298`).

---

## 12. Risk / decode presentation summary

| Signal | Where | Colour | Icon | String |
|---|---|---|---|---|
| decode fullyDecoded | badge | success | `checkmark.seal.fill` | `"Decoded on device"` |
| decode partiallyDecoded | badge | warning | `exclamationmark.triangle.fill` | `"Partially decoded"` |
| decode opaque | badge | danger | `questionmark.diamond.fill` | `"Not decoded"` |
| riskLevel high | summary pill | danger | `exclamationmark.shield.fill` | `"High risk"` |
| riskLevel warning / normal | — | — | — | No pill; only the warning callouts appear |
| warning callout (severe) | list | danger | `exclamationmark.octagon.fill` | Engine text starting with `HIGH RISK` / `UNLIMITED` / `HIGH FEE` |
| warning callout | list | warning | `exclamationmark.triangle.fill` | Engine text |
| acknowledgement required | bottom bar | warning bg 12% | `square` / `checkmark.square.fill` | §11.7 |
| verified note | summary | success | `checkmark.shield.fill` | `display.note` |

`requiresAcknowledgement` is true when the request is **not fully decoded**, or when **risk is high**. It never overrides structural or identity checks.

---

## 13. Authorize sheet: password per task (`AuthorizeSheet`, `:821-907`)

- Presented as a sheet keyed by the task. There is one sheet per pending task, always `pendingTasks.first`. A request that matches several local wallets (multisig PSBTs) shows several `TaskCard`s, and each wallet is authorized **separately** with its own password.
- Detents: medium and large, with a visible drag indicator. Interactive dismiss is disabled while busy.
- Navigation title `"Authorize Signature"` (inline). The cancellation item `"Cancel"` is disabled while busy and calls `cancel()`.
- Body: a scroll view (bounce only when the content is larger), padding 20, VStack spacing 18:
  - `WalletIdentityHeader(walletID, size: 48)` (`Design/PasswordViews.swift:221-239`): the avatar, the name in headline, and `"Fingerprint %@"` in caption monospaced secondary, combined.
  - `PasswordField(.walletPassword)`:
    - A secure field with placeholder `"Wallet password"`, which is also the identifier.
    - Content type password, so an existing password may be autofilled. No autocapitalization or autocorrection. Privacy sensitive.
    - Padding 14, background `Palette.card`, radius 14. Submit label "done", and `onSubmit` calls `sign()`.
  - If there is an error: a danger `Callout(verbatim: error)`. Otherwise `"The password decrypts this wallet for this signature only."` in footnote secondary.
- Bottom bar: the prominent button `ActionLabel("Sign", systemImage: "signature", busy: busy)`, id `authorize.sign`, disabled when the password is empty or busy.
- **Auto-focus** on the password field 350 ms after presentation.
- `.onChange(of: securityEpoch)` calls `cancel()`.
- `.onDisappear` sets a new operation ID and clears the password.
- **`sign()`** (`:881-901`):
  1. Guard that the password is non-empty and not busy.
  2. Capture `entered`, a new `operation` ID and the epoch. **Clear the field immediately** (`password = ""`), set `error = nil` and `busy = true`.
  3. `await store.sign(taskID:, password: entered, riskAcknowledged:)`.
  4. On success, and only if the operation is still current: `busy = false`, `onSigned()` (which resets the review's `riskAccepted`), then dismiss.
  5. On failure, and only if the operation is still current: `busy = false`. If the epoch is unchanged and the error is not a cancellation, show the error and refocus the field. The user retypes the password.
- **`cancel()`**: a new operation ID, clear the password, `busy = false`, dismiss. An in-flight result is ignored by the sheet, but the store still applies it if its epochs are valid.
- Typical errors shown in the sheet:
  - Vault: `"The password is incorrect."`, `"Too many unsuccessful attempts. Try again in %lld seconds."`. Throttling starts at the fifth attempt with a delay of `2^(n-4)` s, capped at 300 s and persisted (`Core/WalletVault.swift:135-152`). Also `"This wallet’s device key is unavailable. Restore your recovery phrase on this device."`, `"The wallet file is damaged or has an invalid format."`, `"This wallet format is not supported by this version of BitTool Signer."` and `"This wallet could not be found."`.
  - Store errors (§14).
  - Engine `signPlanTask` errors, verbatim. Examples: `'The request or signing plan changed after review'`, `'Signing bytes changed after review'`, `'Confirm this request’s decoding risks before signing'`, `'The decrypted wallet does not match the signing plan'`, `'The signing targets do not match the request and decrypted wallet'`, `'Decrypted public key differs from the approved key'`, `'Previous signature does not belong to this signing plan'`.

---

## 14. Signing pipeline (`AppStore.sign`, `AppStore.swift:412-472`)

1. Guard `!busy && !hasSignedResponse`. Otherwise throw `"This signing request is already being processed."`.
2. If a plan exists **and** either the wallet collection snapshot differs or any task wallet is in `duplicateWalletIDs`: call `invalidateMatching()`, then throw `"Wallet information changed. Match this request again."`.
3. Guard all of the following, otherwise throw `"No matching signing task. Review the request again."`:
   - `matchingError == nil`;
   - `plan` exists;
   - `task = pendingTasks.first` and `task.id == taskID` (tasks are signed **in plan order**);
   - `pendingRequestData` exists;
   - `review.requestHash == plan.requestHash`;
   - the local `walletKeyId` of `task.walletID` equals `task.walletKeyId`.
4. Set a new `signingOperation` and `busy = true`. A defer resets `busy` if this operation is still current. `busy` drives the spinner on the review page's Sign button as well as the sheet's.
5. `requireSession`. Re-parse the request from `pendingRequestData`.
6. `vault.unlock(id, password)`, then `requireSession`.
7. Engine `signPlanTask` with:
   - `request`, `plan: plan.raw`, `taskId`, `seed`, `riskAcknowledged`;
   - plus `previousResult` when `previousResultData` exists.
8. `requireSession`. The result's `requestHash == plan.requestHash`, `planHash == plan.planHash` and `taskId == taskID` must all hold. Otherwise throw `"Request changed. Review the transaction again."`.
9. `resultData = frozen(result)`, with the same 1 MiB limit and errors as §8.
10. `finalTask = pendingTasks.count == 1`. If final:
    - Engine `encodeResponse {request, result}`, then `requireSession`.
    - `frames = encoded.frames ?? []`, `text = encoded.text ?? frames.joined("\n")`.
    - If both are empty, throw `"Unable to encode signature response."`.
    - Set `signatureFrames`, `signatureText` and `signaturePayload = QRPayloadView(encoded)`. The Scan tab switches to the result.
11. `previousResultData = resultData`. Insert the task into `completedTaskIDs`, so the `TaskCard` shows "Signed" and the bar shows the next "Sign with …".
12. `appendReceipt(…)` (§16).

The secret's lifetime is the `unlock` → `signPlanTask` scope only. Nothing is cached between tasks, and every task needs its own password entry.

There is no progress percentage. Progress is shown by the button spinners, the per-task "Signed" pills, and "Signature N of M".

---

## 15. Signature result page (`SignatureResultView.swift`)

- Navigation title `"Signature"` (inline). Background `Palette.background`.
- Content: a scroll view with a VStack, spacing 22, horizontal padding 20, bottom padding 20, readable width **560**.
- Elements in order:
  1. **Header** (VStack spacing 10, top padding 8):
     - `checkmark.seal.fill` at 44pt in success colour, with a bounce effect on appear, hidden from accessibility.
     - `"Signature Created"` in title2 bold, id `signature.title`.
     - `"Scan this code with the wallet that created the request."` in subheadline secondary, centred.
  2. **Response QR**: `QRCodeView(frames: signatureFrames, maxSide: 340)` when there are frames (§15.1).
  3. **Request summary card** when `review` exists:
     - An HStack with spacing 12. The icon at 32 is the token icon, otherwise the brand network logo, otherwise the chain logo.
     - `review.title` in subheadline semibold, **verbatim**.
     - `review.summary` in footnote secondary, at most 2 lines, if non-empty.
     - `cardBackground(padding: 14)`.
  4. **Result details card**, shown when `signaturePayload != nil || !signatureText.isEmpty` (`:76-97`). VStack spacing 14, `cardBackground()`:
     - When there is a payload view:
       - `DetailLine("Format", value: view.summary)`, not monospaced.
       - `PayloadDisclosure(view.title, text: view.text, notes:)` (§15.2), id `signature.payload`. For a PSBT the notes are `["psbt_base64 is the exact PSBT this QR code carries; decoded is read from it for reference."]` [EN-only on iOS]; otherwise there are none.
       - A divider.
     - Button `"Export as File"` with icon `square.and.arrow.up`, subheadline semibold, full-width leading, accent tint, plain style, id `signature.export`. It is disabled when `signatureText` is empty.
  5. **Info callout**: `"Signing doesn’t send anything. Your other wallet broadcasts the transaction, and it is only final once confirmed on the network."`.
  6. **Export error**: a danger `Callout(verbatim:)`.
- **Bottom bar** (max width 560): the prominent button `"Done"` with icon `checkmark`, id `signature.done`. It calls `store.clearRequest()` inside `withAnimation`, returning to Home without reopening the camera.
- **Export** uses iOS `fileExporter` with `SignedResponseDocument(text: signatureText)`, content type `.plainText` and default file name `"bittool-signer-signature"`, so the file is `bittool-signer-signature.txt`. The content is the UTF-8 `signatureText`, i.e. the frames joined by `\n`. A failure sets `exportError` to the error's `localizedDescription`.
- Haptics: `.sensoryFeedback(.success, trigger: appeared)` once on appear.

### 15.1 `QRCodeView` (`Design/QRCodeView.swift`)

- Rendering:
  - One pixel per module, scaled with nearest-neighbour sampling.
  - Error correction **M** when the frame is ≤ 600 UTF-8 bytes, otherwise **L**.
  - Rendered off the main thread. A spinner shows until all frames are rendered.
- Container:
  - A white rounded rect, radius 24, with a shadow of black 8%, radius 16, y 6. The QR has 18 of padding inside.
  - Aspect ratio 1, max side 340. **Always light**, even in dark mode.
- Animation, when there are several frames:
  - Every **250 ms**, `index = (index + 1) % count`, unless paused or not yet rendered. The index resets to 0 when the frames change.
  - Controls (HStack spacing 12):
    - A bordered circle button 32×32 showing `play.fill` when paused and `pause.fill` when playing. Accessibility label `"Play QR animation"` or `"Pause QR animation"`.
    - A linear progress bar showing `(index + 1) / count`, max width 140, hidden from accessibility.
    - `"%lld/%lld"` in caption with monospaced digits, secondary.
  - The QR image's accessibility label is `"Animated QR code, part %lld of %lld"` when there are several frames, otherwise `"QR code"`.

### 15.2 `PayloadDisclosure` (`Design/PayloadDisclosure.swift`)

- Header: a plain button tinted with the accent. The title is subheadline semibold, followed by `chevron.right` (footnote semibold), rotated 90° when expanded and hidden from accessibility.
  - It toggles with `.snappy(duration: 0.25)`.
  - Accessibility value `"Shown"` or `"Hidden"`, id `<identifier>Toggle`.
- When expanded:
  - The notes, each in caption secondary, wrapping.
  - The text, in caption2 monospaced, full width, wrapping, id `<identifier>`.
    - Show at most **30,000** characters. A longer text is cut and `"\n…"` is appended.
    - If the shown text is longer than 2,000 UTF-8 bytes, insert a zero-width space (U+200B) after every 64 consecutive non-space characters, in lines longer than 120 characters, so that wrapping stays cheap.
    - **It is deliberately not selectable.** Copying must go through the expiring clipboard.
  - When truncated: `"Showing the first %@ of %@ characters. Copy includes everything."` with both numbers locale-formatted (`30,000` and the total), in caption secondary.
  - Button `"Copy Payload"` with `doc.on.doc`, which becomes `"Copied"` with `checkmark` for **1.5 s**. Footnote semibold, accent, id `<identifier>Copy`. It copies the **full** text through `Clipboard.copy`, which expires after 120 s and stays local only.

---

## 16. Recording to Activity (`appendReceipt`, `AppStore.swift:449-457, 554-569`)

A receipt is recorded **after every successful task**, partial ones included.

```text
SigningReceipt {
  id: new UUID, date: now, chain: task.chain, title: approved.title (engine English),
  requestHash: plan.requestHash, walletID: task.walletID, walletKeyID: task.walletKeyId,
  masterFingerprint: publicWallets[walletID].masterFingerprint (lowercase hex),
  resultType: finalTask ? "Signature created" : "Partial signature created",
  summary: String(approved.summary.prefix(160)), networkName: approved.networkName,
  chainID: approved.network?.chainID, riskLevel: approved.riskLevel.rawValue,
  requestJSON: stored(<frozen canonical request JSON text>),
  responseFrames: finalTask ? stored(signatureFrames) : nil,
  responseText:   finalTask ? stored(signatureText)   : nil
}
```

`Models/Models.swift:408-459` defines the receipt.

- `stored(text)` keeps the text only if it is non-empty and ≤ **32,768** UTF-8 bytes.
- `stored(frames)` keeps the frames only if non-empty, ≤ 2,048 frames, and ≤ 32,768 total UTF-8 bytes.
- Nothing is ever truncated: an oversized payload is simply not stored.

`resultType` is displayed verbatim in Activity. `"Signature created"` is a key; `"Partial signature created"` is [EN-only on iOS].

Write rules:

1. Prepend the new receipt to the existing list and keep **100** records.
2. Only the **25** newest keep their payloads. Older records are kept without `requestJSON`, `responseFrames` and `responseText`.
3. While the encoded file is larger than **2,097,152** bytes, drop the payloads of the oldest record that still has them.
4. Save atomically to `Application Support/BitToolSigner/receipts.json`: directory mode 0700, file mode 0600, complete file protection, excluded from backup.
5. On failure, set `store.error = "The local activity record could not be saved. Any completed signature remains available."` [EN-only on iOS], which is shown in the root "Unable to Continue" alert. The signature stays on screen.

The validation used on read is in the Activity spec. A receipt never contains a secret.

---

## 17. `ReviewText`: translating engine English at draw time (`Models/ReviewText.swift`)

The engine writes English only, because that text is covered by the request fingerprint and must be reproducible byte for byte. The UI translates at the last moment and **falls back to the engine's English** whenever a phrase has no entry.

- **`phrase(english)`**: an empty string returns `""`. Otherwise it returns the translation, or `english` when there is none. It is used for `display.action` (or the title), counterparty labels and the counterparty caption.
- **`headline(english)`**:
  1. Use the full translation if one exists.
  2. Otherwise, for each prefix in `["Method ", "Safe: ", "Batch: ", "New "]`: if the text starts with the prefix and the remainder is non-empty, return `phrase(prefix.dropLast()) + " " + remainder`.
     - `dropLast()` gives the keys `"Method"`, `"Safe:"`, `"Batch:"` and `"New"`.
     - An empty remainder stops the search.
  3. Otherwise return the English unchanged.
- **`label(english)`**:
  1. Use the full translation if one exists.
  2. Otherwise, if the label ends with `")"` and contains `"("`, take the stem before the **last** `"("`, trimmed. If the stem has a translation, return `translated stem + " " + "(unit…)"`. For example `"Amount (USDC)"` becomes `"<Amount> (USDC)"`.
  3. Otherwise return the English unchanged.
- **`translation(of:)`** returns nil when there is no entry. iOS uses a sentinel default value for this.
- **`shortDecimal(text, places = 8)`** is for fee ceilings and **rounds up**:
  1. Split once on a space into `number` and an optional `unit`.
  2. `number` must contain `.`, and every character after the first must be a digit or `.`. Otherwise return the text unchanged.
  3. Split on the first `.` into `whole` and `fraction`. The fraction must have **more than 8** digits, all of them digits. Otherwise return the text unchanged.
  4. Keep the first 8 fraction digits. If every dropped digit is `0`, return `whole.kept + unit`.
  5. Otherwise add one in the last kept place, carrying through the digit strings (never parsed as integers), into `whole` if needed, and prefixing `1` if the carry passes the front.
  6. A non-digit anywhere in `whole` during the carry returns the original text.
- Test vectors (`BitToolSignerTests/ReviewTextTests.swift`):

| Input | Output |
|---|---|
| `"0.000396834604360128 ETH"` | `"0.00039684 ETH"` |
| `"0.999999999 ETH"` | `"1.00000000 ETH"` |
| `"1.123456789 ETH"` | `"1.12345679 ETH"` |
| `"0.500000000000 ETH"` | `"0.50000000 ETH"` |
| `"9223372036854775807.99999999999998 ETH"` | `"9223372036854775808.00000000 ETH"` |
| `"99999999999999999999999999.999999999 ETH"` | `"100000000000000000000000000.00000000 ETH"` |
| `"9.999999999"` | `"10.00000000"` |
| `"0.0001 ETH"`, `"12 TRX"`, `"0.00039683"`, `"about 0.1 ETH"`, `""` | unchanged |

- Fallback tests: `phrase("Dilithium Vault Rebalance")` returns the input unchanged, and `headline("Method ")` returns `"Method "`.
- The list of all engine phrases is in `engine/data/interface-strings.json` (742 entries). Use it to make sure every engine phrase has an Android resource.

---

## 18. Epochs, cancellation and late results (behaviour to reproduce)

- Every async step captures `(securityEpoch, requestEpoch)` and re-checks them after each await. A stale result is **silently dropped**.
- UI handlers ignore errors when the epoch changed or the error is a cancellation. This applies to Home import, the paste sheet, retry, the `AuthorizeSheet` and `ScanSession`.
- `suspend()` while matching leaves `review` in place and sets `"Matching interrupted. Try again."` with the Match Again button.
- `suspend()` while signing:
  - `busy` resets;
  - the `AuthorizeSheet` closes through `cancel()` on the epoch change, and `riskAccepted` resets;
  - the store discards the late signature;
  - the review stays, so the user can sign again with a password.
- `suspend()` during a scan: the in-flight frame is re-queued (§7) and the camera stops (scene inactive). Scanning resumes on return.
- A wallet reload that changes the wallet set or the public indexes while a plan exists and no response is out calls `invalidateMatching()`. All partial signatures are cleared, and the user must match again and re-enter passwords.
- Replacing or rejecting a request changes `requestEpoch`, so late review or plan results for the old request are dropped.

---

## 19. Design tokens used here (light / dark)

| Token | Light | Dark | iOS source |
|---|---|---|---|
| accent | `#0F7B55` | `#6BE3A4` | `Assets.xcassets/AccentColor.colorset` |
| onAccent (text on accent) | `#FFFFFF` | `#0B1F16` | `Brand.onAccent` (`DesignSystem.swift:10-11`) |
| mint (scanner accents) | `#B3F0C7` | same | `Brand.mint` |
| ink (text on mint) | `#0B1F16` | same | `Brand.ink` |
| background | `#F2F2F7` | `#000000` | systemGroupedBackground |
| card | `#FFFFFF` | `#1C1C1E` | secondarySystemGroupedBackground |
| raised | `#F2F2F7` | `#2C2C2E` | tertiarySystemGroupedBackground |
| success | `#34C759` | `#30D158` | systemGreen |
| warning | `#FF9500` | `#FF9F0A` | systemOrange |
| danger | `#FF3B30` | `#FF453A` | systemRed |
| neutral fill (logo fallback) | `#E5E5EA` | `#2C2C2E` | systemGray5 |
| secondary text | `#3C3C43` @ 60% | `#EBEBF5` @ 60% | secondaryLabel |

The scanner is always dark. The QR container is always white.

Metrics:

| Metric | Value |
|---|---|
| Page padding | 20 |
| Card radius | 22 |
| Readable width | 640 (560 on the result page) |
| Callout / box radius | 16 |
| Summary and scan card radius | 26 |
| Tile radius | 20 |
| Status panel radius | 22 |
| Viewfinder radius | 28 |

Type mapping at default size:

| iOS style | Size |
|---|---|
| largeTitle | 34 |
| title2 | 22 |
| title3 | 20 |
| headline | 17 semibold |
| body | 17 |
| callout | 16 |
| subheadline | 15 |
| footnote | 13 |
| caption | 12 |
| caption2 | 11 |

`ActionLabel` (`DesignSystem.swift:95-133`):

- Full width, body semibold, vertical padding 4.
- When busy, a spinner replaces the content; the content is hidden but keeps its layout.
- One line, or two at accessibility text sizes, with minimum scale 0.8.
- When disabled, the label colour is secondary.

`StatusPill`: caption semibold in the tint colour, padding h10 v5, the tint at 14% on a capsule, one line.

---

## 20. Accessibility, haptics and motion

- Test IDs to keep as Compose `testTag`s:
  - `signing.scan`, `signing.paste`, `signing.file`, `signing.payload`, `signing.decode`
  - `scanner.protocolError`, `scanner.reset`, `scanner.progress`, `scanner.status`, `scanner.cameraError`
  - `signing.reject`, `signing.matchError`, `signing.retryMatch`, `signing.risk`, `signing.authorize`
  - `review.headline`, `review.assetContract`, `review.tokenId`, `review.flow.give|receive|others`, `review.contractNotice`
  - `authorize.sign`, `Wallet password`
  - `signature.title`, `signature.payloadToggle`, `signature.payload`, `signature.payloadCopy`, `signature.export`, `signature.done`
  - `wallets.scan`
- Semantics:
  - The scan card is one button labelled "Scan signing request".
  - Decorative icons are hidden.
  - Callouts, detail lines, step rows, the counterparty box, the asset box and flow sections are each merged into one node.
  - The acknowledgement is a checkbox with state descriptions "Checked" and "Not checked".
  - The NFT glyph has a content description.
  - Long token IDs are spoken in groups of 4.
  - Addresses are spoken in groups.
- Haptics:

| Event | iOS feedback |
|---|---|
| Scanner error appears | `.error` |
| Acknowledgement toggled | `.selection` |
| Result page appears | `.success` |
| Address copied | `.success` |

- Reduce Motion disables the scan-card icon pulse and the viewfinder bracket pulse. The QR animation keeps running, because it carries data.
- Large text:
  - The badges row stacks.
  - Amounts scale down to 50% (except NFT amounts, which wrap).
  - The authorize sheet scrolls rather than compressing, with Sign pinned.

---

## 21. Errors and strings catalogue (host strings in this flow)

Localized on iOS. Each has a key in `translations/*.json`:

- Home and paste: `"Scan"`, `"Scan a Signing Request"`, `"Show the unsigned transaction or message as a QR code in your other wallet."`, `"Open Camera"`, `"Scan signing request"`, `"Paste Request"`, `"Import File"`, `"How signing works"`, `"Review"`, `"Authorize"`, `"Return"`, the four step details (§3), `"Paste a PSBT (Base64 or hex) or a UR signing request. Multi-part UR requests can be pasted one part per line."`, `"Paste from Clipboard"`, `"Review Request"`, `"Cancel"`.
- Scanner: `"Scan Request"`, `"Point the camera at the QR code in your other wallet."`, `"Close"`, `"Turn on flashlight"`, `"Turn off flashlight"`, `"Camera Access Needed"`, `"Allow camera access in Settings to scan QR codes, or import the request as a file or pasted text."`, `"Open Settings"`, `"Scan Again"`, `"%lld%%"`, `"No camera is available. Import the request as a file instead."`, `"The camera is unavailable."`, `"QR scanning is unavailable."`, `"This camera cannot read QR codes. Import a request file instead."`.
- Import: `"This file is larger than 512 KB."`, `"Choose a PSBT file or a text file containing a UR request."`, `"Text payload exceeds 256 KB."`, `"The request is incomplete. Import all QR frames."`, `"QR transfer is too large."`, `"Invalid request format."`, `"Request exceeds the supported size."`, `"Invalid local document."`, `"Missing or invalid transaction commitment."`, `"Invalid signing plan."`, `"Duplicate signing task."`, `"Invalid signing wallet identity."`, `"No interrupted or unmatched request to retry."`.
- Review:
  - Frame and badges: `"Reject"`, `"Discard partial signatures?"`, `"Reject Request"`, `"Signatures already created for this request will be discarded."`, `"Decoded on device"`, `"Partially decoded"`, `"Not decoded"`, `"High risk"`, `"Network fee up to %@"`, `"Energy budget up to %@ · total fee not known offline"`.
  - NFT and flows: `"You give"`, `"You receive"`, `"Also paid out"`, `"NFT"`, `"NFT or token"`, `"ERC-1155 token"`, `"NFT order"`, `"Token ID"`, `"Token ID or raw amount"`.
  - Contract notice: `"Known public contract (spender)"`, `"Known public contract (operator)"`, `"Known public contract"`, `"Spender is not a known contract"`, `"Operator is not a known contract"`, `"Contract is not in the known list"`, `"Recognized token contract"`, `"Spender"`, `"Operator"`, `"Contract"`, `"Same address on every EVM network."`.
  - Wallet section: `"Signing Wallet"`, `"Signing Wallets"`, `"Finding the matching wallet…"`, `"Match Again"`, `"Matching…"`, the six explanations (§11.7), `"Each wallet signs with its own password. The final signature appears after every wallet has signed."`.
  - Bottom bar: `"I understand the risks of this request"`, `"I understand this request can’t be fully verified"`, `"Read every warning above before signing."`, `"Some effects couldn’t be decoded on this device."`, `"Signature %lld of %lld"`, `"Sign with %@"`, `"Cannot Sign"`.
  - Task card and details: `"Fingerprint %@"`, `"Signed"`, `"Awaiting password"`, `"Signing address"`, `"Derivation path"`, `"Derivation paths"`, `"Inputs signed"`, `"Signer position"`, `"Details"`, `"Show All"`, `"Show Less"`, `"Request fingerprint"`, `"Copy address"`, `"Copy Address"`, `"Copied"`.
- Authorize: `"Authorize Signature"`, `"Wallet password"`, `"The password decrypts this wallet for this signature only."`, `"Sign"`, `"This signing request is already being processed."`, `"Wallet information changed. Match this request again."`, `"No matching signing task. Review the request again."`, `"Request changed. Review the transaction again."`, `"Unable to encode signature response."`, `"The password is incorrect."`, `"Too many unsuccessful attempts. Try again in %lld seconds."`.
- Result: `"Signature"`, `"Signature Created"`, `"Scan this code with the wallet that created the request."`, `"Format"`, `"Payload (JSON)"`, `"Copy Payload"`, `"Showing the first %@ of %@ characters. Copy includes everything."`, `"Shown"`, `"Hidden"`, `"Export as File"`, `"Signing doesn’t send anything. Your other wallet broadcasts the transaction, and it is only final once confirmed on the network."`, `"Done"`, `"Animated QR code, part %lld of %lld"`, `"QR code"`, `"Play QR animation"`, `"Pause QR animation"`, `"%lld/%lld"`.
- Root: `"Unable to Continue"`, `"OK"`, `"Signature created"`.

[EN-only on iOS]. No key exists, so iOS shows English:

- Scanner and session: `"Reading request…"`, `"Keep the animated code in view · %d parts"`, the 6 frame-gate messages (§6.8), `"The scan session ended. Scan again."`.
- Matching messages: `"Matching interrupted. Try again."`, `"Unsupported signing protocol"`, and the engine matching messages. `"Wallet information changed. Match this request again."` is shown verbatim even though a key exists.
- Values drawn verbatim: `"Setup required"`, `"Wallet"`, `"Payload (Hex)"`, `"Payload (Text)"`, the `QRPayloadView.summary` strings, `"psbt_base64 is the exact PSBT this QR code carries; decoded is read from it for reference."`, `"NFT contract (not verified)"`, `"+%d more (see Details)"`, `"Partial signature created"`.
- Records and results: `"The local activity record could not be saved. Any completed signature remains available."`, all engine warnings, notes, titles and summaries on the result page, and all engine error messages.
- Engine host errors: `"The offline signing engine could not be started."`, `"The engine’s secure entropy source could not be installed."`, `"The bundled signing engine could not be loaded."`, `"The offline engine could not process this request."`, `"This request could not be signed."`, `"This signing operation is not supported."`, `"The signing request has an invalid format."`, `"This request exceeds the 1 MB offline signing limit."`, `"The bundled signing engine is unavailable. Reinstall BitTool Signer."`.

---

## 22. Tests to port (Kotlin unit and Robolectric)

- `QrScanFrameGate`: all six cases of `QRScannerTests.swift`:
  - unreadable and empty codes are rejected explicitly;
  - the exact 16,384-byte boundary is accepted, with `é` counted as 2 bytes;
  - a repeated frame is throttled at 0.4 s and 0.9 s and accepted at 1.0 s;
  - distinct frames pass immediately and unchanged, including a value with leading newlines;
  - repeated failures do not block a readable frame;
  - an earlier clock value is not throttled.
- `ReviewText`: §17 vectors.
- `ReviewDisplay` / `ReviewContract` / `ReviewField.rowStyle`: `NFTReviewDisplayTests.swift`, which has 19 tests including the fixtures, the 32-line cap, middle-ellipsis and the `#id` digit boundary.
- `QRPayloadView`: `QRPayloadViewTests.swift`.
- Router: `ScanRequestRouterTests.swift`. The flow request → review → result → Done must never reopen the camera.
- Store: `SessionTests.swift:218-378`, using a fake engine and a fake vault.

---

## 23. Android mapping

### 23.1 Module placement (following Ownbit 5.0's build-logic conventions)

| Module | Contents |
|---|---|
| `:core:engine` | QuickJS host. A `SignerEngine` with the same allowlist, a fresh context per operation, the scan-session context, entropy installation and size limits. A `@Singleton` running on its own dispatcher (§23.2). |
| `:core:model` (pure Kotlin) | `TransactionReview`, `ReviewField`, `ReviewDisplay`, `ReviewContract`, `ReviewAsset`, `ReviewNetwork`, `SigningPlan`, `SigningTask`, `QrPayloadView`, `SigningReceipt`, `AddressFormatter`, `ReviewText` (with an injected lookup), `QrScanFrameGate`. All the §22 tests live here. |
| `:core:signing` (or `:core:data`) | `SigningStore`, the port of the `AppStore` signing part: `StateFlow` state, epochs, `beginScan`/`endScan`/`receive(frame)`/`importPayload`/`prepare`/`retryMatching`/`sign`/`clearRequest`/`suspend`, and the receipt repository. A `@Singleton`, shared with Wallets (reload invalidation) and Activity. |
| `:core:scanner` | The camera composable (CameraX + ZXing), permission handling, the viewfinder overlay, the status panel, and the generic `QrScannerScreen(title, hint, purpose, progress, status, error, onReset, onScan)` reused by recovery-phrase import. |
| `:core:designsystem` | `Callout`, `StatusPill`, `ActionLabel`/`ActionButton`, `DetailLine`, `StepRow`, `AddressText`, `HashText`, `CopyableAddress`, `QrCodeView`, `PayloadDisclosure`, `TokenIcon`, `NetworkLogo`, `GenericNetworkLogo`, `WalletAvatar`, `NftGlyph`, colour tokens. |
| `:feature:sign` | `ScanTabRoute` (switching Home, Review and Result with `AnimatedContent`), `ScanHome`, `PasteRequestSheet`, `RequestReviewScreen`, `ReviewSummaryCard`, `ContractNotice`, `ReviewDetailsCard`, `TaskCard`, `AuthorizeSheet`, `SignatureResultScreen`, `ScanSessionController` and its ViewModels. |

Ownbit prior art (read for patterns, **do not copy its behaviour**):

- `core/scanner/src/main/kotlin/io/ownbit/core/scanner/camera/ScannerCamera.kt` covers CameraX binding, the 1080p `ResolutionSelector`, torch state, the camera-unavailable state from `CameraState.error`, and tap-to-focus. Note that it uses ML Kit, which this port replaces.
- `core/scanner/src/main/kotlin/io/ownbit/core/scanner/ScannerScreen.kt:141-161, 245-256` covers the permission flow and `ACTION_APPLICATION_DETAILS_SETTINGS`.
- `core/ui/src/main/kotlin/io/ownbit/core/ui/security/ClipboardHelper.kt` covers the sensitive clip flag and delayed clear.
- `core/publicsigner/src/main/kotlin/io/ownbit/core/publicsigner/scan/PublicScanSession.kt` covers a single in-flight engine call with a queue.
- Ownbit's `PublicFrameGate` and `PublicScanSession` **add** behaviour BitTool Signer iOS does not have: ignoring stray codes, a 150 s idle timeout, TronLink and TokenPocket handling, and a different de-duplication. Port the iOS `QRScanFrameGate` and `ScanSession` semantics exactly, as in §6.8 and §7.
- Ownbit's `EngineVocabulary` is a different translation approach. Use the iOS `ReviewText` English-keyed lookup instead.

### 23.2 Engine thread (QuickJS)

- Use one dedicated thread for every QuickJS runtime and context. QuickJS is not thread-safe, and the wrapper binds contexts to their creating thread.

  ```kotlin
  private val engineExecutor = Executors.newSingleThreadExecutor { r -> Thread(null, r, "bittool-signer-engine", 16L shl 20) }
  val engineDispatcher = engineExecutor.asCoroutineDispatcher()
  suspend fun run(op: String, params: JsonObject): JsonObject = withContext(engineDispatcher) { … }
  ```

  The 16 MB stack suits the QuickJS max stack size. Run this on a real device with the largest fixtures.
- `run` must:
  - check the allowlist;
  - serialize `params + operation` with **sorted keys**;
  - enforce ≤ 1,048,576 **UTF-8 bytes**;
  - create a fresh context, install `crypto.getRandomValues` and `Math.random` from `SecureRandom` exactly as in `Core/SigningEngine.swift:32-60`, evaluate `signer.js`, and call `BitToolSignerEngine.dispatch(json)`;
  - parse the envelope, applying the 2,097,152-byte cap, the 80-character code prefix and the 400-character message prefix;
  - **close and destroy the context in `finally`**.
- Scan session: `beginScanSession()` closes any old scan context, then creates a new one and calls `scanBegin`. `scan(frame)` calls `scanAdd` on it, or throws `SCAN` / `"The scan session ended. Scan again."` when there is none. `endScanSession()` closes it. All of these run on the same engine thread.
- Never call the engine from the main thread. Signing can take seconds without a JIT.
- Engine calls are **not interrupted** on cancellation. Results are discarded by epoch, as on iOS. Use `NonCancellable` around the native call or let it finish; never kill the thread.
- Performance: evaluating the 854 KB bundle per fresh context is the main cost. Measure it. If it is too slow, compile `signer.js` to QuickJS bytecode once per process and evaluate that bytecode in each fresh context. The bytecode comes from the byte-identical bundled source, and the entropy shim must still be installed first.
- JSON: use `kotlinx.serialization` `JsonElement`, which keeps integer literals as integers and preserves `plan.raw` exactly. Never route through `Double`. Never emit duplicate keys. Respect depth ≤ 32 and ≤ 20,000 values.

### 23.3 Camera: CameraX `ImageAnalysis` + ZXing

- Dependencies: `androidx.camera:camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`, and `com.google.zxing:core:3.5.3`. ZXing is open source and needs neither Google Play services nor model downloads, so it fits both the **apk** and **play** flavors and the NO-INTERNET manifest.
- Use cases:
  - `Preview` on a `PreviewView` with `ImplementationMode.COMPATIBLE` (TextureView, which works well inside Compose transitions) and `ScaleType.FILL_CENTER` (the equivalent of `resizeAspectFill`).
  - `ImageAnalysis` with `STRATEGY_KEEP_ONLY_LATEST`, output format `YUV_420_888`, and a `ResolutionSelector` targeting 1920×1080 at 16:9 with fallback "closest lower then higher" (as Ownbit's `ScannerCamera.resolutionSelector()`). This mirrors iOS's `hd1920x1080`.
  - Camera: back, falling back to front.
  - Continuous autofocus is the default. Tap-to-focus with `FocusMeteringAction` is acceptable polish.
- Analyzer, on its own single-thread executor, separate from the engine thread:
  1. Take the **Y plane only**: `planes[0]` (pixelStride is 1) and its `rowStride`. Copy it into a **reused** `ByteArray(rowStride * height)` and build `PlanarYUVLuminanceSource(bytes, rowStride, height, 0, 0, width, height, false)`.
     - Rotation does not matter for QR decoding, so the buffer is not rotated.
     - The crop is the full frame, as on iOS. An optional optimisation maps the viewfinder rect through `PreviewView.outputTransform` and `CoordinateTransform`.
  2. Decode with `QRCodeMultiReader().decodeMultiple(BinaryBitmap(HybridBinarizer(source)), hints)` so that several codes in one frame are all seen.
     - Hints: `POSSIBLE_FORMATS = [QR_CODE]` (QR only) and `CHARACTER_SET = "UTF-8"`.
     - On `NotFoundException`, retry once on `source.invert()` for **inverted (light-on-dark) codes**.
     - Alternatively use `MultiFormatReader` restricted to QR with `ALSO_INVERTED = true`, which handles a single code only.
  3. For each `Result`, find the text or nil:
     - If `resultMetadata[BYTE_SEGMENTS]` exists and any segment fails **strict** UTF-8 decoding (`CodingErrorAction.REPORT`), the value is **nil** (the iOS `nonText` case, for example a Compact SeedQR or arbitrary binary).
     - Otherwise the value is `result.text`.
     - Strict decoding matters because ZXing alone replaces bad bytes with U+FFFD or guesses Shift_JIS.
  4. Apply the "prefer readable" rule: `values = readable.ifEmpty { listOf(null) }`.
  5. Post the values to the main thread and feed each one, in order, through `QrScanFrameGate.receive(value, SystemClock.elapsedRealtime())` (monotonic). Handle accepted, rejected and throttled exactly as §6.8.
  6. Throttling:
     - `KEEP_ONLY_LATEST` naturally throttles analysis to decoder speed.
     - The gate throttles an identical outcome for 1 s.
     - Distinct animated frames pass immediately.
     - Stop analysis (`clearAnalyzer` or an `enabled` flag) once `store.review != nil` or a session error is showing. iOS ignores frames in that case; Android can skip the decoding work too.
  7. Do not log scanned text.
- Torch:
  - The button shows when `cameraInfo.hasFlashUnit()`; otherwise it is invisible but keeps its space.
  - Toggle with `cameraControl.enableTorch()` and observe `torchState`.
  - Force it off on `ON_PAUSE` and on dispose.
- Lifecycle:
  - iOS runs the camera only while the scene is **active**. Bind to a lifecycle that is RESUMED only while the host Activity is resumed, or unbind on `ON_PAUSE` and rebind on `ON_RESUME`.
  - Release everything on dispose: unbind the use cases, shut down the executor, turn the torch off.
- Unavailable states map to `cameraError` text:

| Android condition | String |
|---|---|
| No camera (`!hasCamera(back) && !hasCamera(front)`, or no `FEATURE_CAMERA_ANY`) | `"No camera is available. Import the request as a file instead."` |
| `bindToLifecycle` failure, or `CameraState.error` (in use, max cameras, disabled by policy, fatal, Do Not Disturb) | `"The camera is unavailable."` |
| Analysis use case cannot bind | `"QR scanning is unavailable."` |
| — | `"This camera cannot read QR codes. Import a request file instead."` is unreachable with ZXing; keep the string |

- Manifest:
  - `<uses-permission android:name="android.permission.CAMERA"/>`.
  - `<uses-feature android:name="android.hardware.camera.any" android:required="false"/>`, so devices without a camera can still install and use paste or import.
  - **No INTERNET permission.** Check the merged manifest of every library so that none adds it.

### 23.4 Scanner UI in Compose

- Present the scanner as a **full-screen destination**: a navigation route or a `Dialog(properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false))`.
  - Edge-to-edge, black background.
  - Hide the status bar with `WindowInsetsControllerCompat.hide(statusBars())` while it is visible and restore it on exit.
  - Use light system-bar icons.
  - Force a dark `MaterialTheme` on **this subtree only**. Never call `AppCompatDelegate.setDefaultNightMode` (the iOS Revision 9 regression).
- Overlay `Canvas`:
  - `Path` with `fillType = PathFillType.EvenOdd` for the dim layer: the full rect plus a rounded rect of radius 28dp.
  - The bracket path from §6.5 with stroke 5dp, round cap and join, in mint.
  - The progress ring: rounded-rect path inset by 6dp, `PathMeasure.getSegment(0f, length * progress, dst)`, stroke 3dp at 90% mint.
  - The pulse uses `rememberInfiniteTransition` with scale 0.985↔1.015 and a 1,200 ms tween, `RepeatMode.Reverse`. It is disabled when `Settings.Global.ANIMATOR_DURATION_SCALE == 0`; also respect the system's "Remove animations" setting.
- Top bar:
  - Circular translucent `IconButton`s, 48dp to meet the minimum touch target (iOS uses 44).
  - Material Symbols `close` and `flashlight_on` / `flashlight_off`.
  - Content descriptions as in §6.3.
- Status panel:
  - A `Surface` at 60–70% black, radius 22dp, max width 420dp, padding 18dp, bottom-centred above the navigation bar inset.
  - `LinearProgressIndicator(progress = { p }, color = mint, width ≤ 220dp)`.
  - Panel content animates with `animateContentSize` and a crossfade of about 200 ms.
  - An optional blur with `Modifier.blur` on API 31+ applies to the panel's own background only. It is not required.
- System back closes the scanner, like iOS close and escape. Closing calls `session.stop()`.
- Haptic on a new error: `view.performHapticFeedback(HapticFeedbackConstants.REJECT)` on API 30+, otherwise `LONG_PRESS`.

### 23.5 Runtime CAMERA permission UX

1. On scanner entry, check `checkSelfPermission(CAMERA)`.
   - If it is not granted and the app has never asked, or `shouldShowRequestPermissionRationale` is true, launch `ActivityResultContracts.RequestPermission()` straight away. iOS asks on first appear.
   - While the system dialog is up, dim at 0.8 and show the hint panel. This is the iOS `notDetermined` state.
2. If the permission is denied, show the "Camera Access Needed" panel (§6.4).
   - "Open Settings" launches `Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))`, falling back to `Settings.ACTION_SETTINGS`.
   - When `shouldShowRequestPermissionRationale` is still true, the button may re-launch the permission request instead. If a separate "Allow" label is wanted, it is an **Android-only string that needs 20 translations**. Flag it to l10n.
   - The localized `infoPlist.NSCameraUsageDescription` text (§6.6) can be shown as the rationale line.
3. Re-check on `ON_RESUME`, the iOS equivalent of re-reading on `scenePhase == .active`. A grant made in Settings starts the camera without reopening the scanner.
4. Persist "asked once" in `rememberSaveable` or a small preference, so the permanently-denied case can be told apart from "never asked".

### 23.6 Scan home, paste and import in Compose

- **Scan card**: a `Surface(onClick = …, shape = RoundedCornerShape(26.dp), color = card)` around the **whole** card.
  - Press feedback: `interactionSource.collectIsPressedAsState()` drives `animateFloatAsState` for scale 0.98 and alpha 0.9 over about 180 ms.
  - `Modifier.semantics { contentDescription = "Scan signing request"; role = Role.Button }`, with the inner content merged.
  - The "Open Camera" pill is a non-clickable `Box` drawn as a filled capsule, not a separate `Button`, so the entire card stays a single target.
  - The icon pulse is an infinite alpha or scale transition, disabled when animations are off.
- **Tiles**: two `Surface(onClick)` in a `Row` with `Modifier.weight(1f)`, `enabled = !busy`.
- **Paste sheet**:
  - A `ModalBottomSheet(skipPartiallyExpanded = true)`, or a full-screen dialog with a `TopAppBar` titled "Paste Request" and a `"Cancel"` `TextButton`.
  - An `OutlinedTextField` with `minLines` around 9 (min height 220dp) and a monospace `TextStyle`.
  - `KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Ascii)`.
  - The bottom button with `imePadding()`.
  - "Paste from Clipboard": `clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()`. Replace the field when it is non-null, otherwise do nothing. Android 12+ shows its own "pasted" toast, which is fine.
- **Import**:
  - `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument())` launched with `arrayOf("*/*")`. iOS `.data` means any file; PSBT files usually arrive as `application/octet-stream`.
  - Read on `Dispatchers.IO`:
    1. Query `OpenableColumns.SIZE`. A known size **> 512,000** gives `"This file is larger than 512 KB."`.
    2. If the size is unknown, read through a bounded stream of at most 512,001 bytes and apply the same check. iOS treats an unknown size as 0 and reads everything; do not.
    3. If the data has the PSBT magic, use `Base64.encodeToString(bytes, Base64.NO_WRAP)`.
    4. Otherwise decode as strict UTF-8, or fail with `"Choose a PSBT file or a text file containing a UR request."`.
    5. Pass the result to `store.importPayload`.
  - A user cancel (null URI) does nothing.
  - An `IOException` or `SecurityException` shows `e.localizedMessage` in the danger callout. iOS shows the OS text. A localized fallback is a new Android-only string; flag it.
- **Export**:
  - `rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain"))` launched with `"bittool-signer-signature.txt"`.
  - Write `signatureText.toByteArray(UTF_8)` with `contentResolver.openOutputStream(uri, "wt")`. Use `"wt"` to truncate, because `"w"` does not truncate on some providers.
  - On failure, show the danger callout with the message.
  - A user may choose a cloud provider. That provider uploads the file, not the app; this matches the iOS privacy text. Do **not** add an `ACTION_SEND` share sheet without updating the privacy policy.

### 23.7 Review, authorize and result in Compose

- **Scan tab container**:
  - `AnimatedContent(targetState = when { hasSignedResponse -> Result; review != null -> Review; else -> Home })` with a 300 ms fade-through.
  - Scan tab badge: `NavigationBarItem` with `BadgedBox(badge = { if (review != null && !hasSignedResponse) Badge { Text("1") } })`.
  - Switch to the Scan tab when a new `requestHash` appears.
- **Review**:
  - A `Scaffold` with a `TopAppBar` titled "Review Request". The action `TextButton("Reject")` uses the error colour.
  - The content is a `LazyColumn`, or a scrolling `Column`, in the §11.2 order.
  - The `bottomBar` is a `Surface` (tonal elevation, `navigationBarsPadding()`) holding the acknowledgement row and the Sign button.
  - The acknowledgement is a `Row` with `Modifier.toggleable(value, role = Role.Checkbox)`, a `Checkbox` coloured with the warning colour when checked, a `stateDescription` of "Checked" or "Not checked", and haptic `HapticFeedbackConstants.CLOCK_TICK`, or `SEGMENT_TICK` on API 34+.
  - The reject confirmation is an `AlertDialog` titled `"Discard partial signatures?"` with text `"Signatures already created for this request will be discarded."`, a confirm `TextButton("Reject Request")` in the error colour, and a dismiss `"Cancel"`.
  - **Back** (an Android-only decision): on the review page, back behaves like Reject, including the confirmation when there are partial signatures. On the result page, back behaves like Done. Never silently keep a request alive behind another screen; iOS has no back on these pages.
  - Selectable values (amount, headline, token ID, flow lines, details values, hash) go in `SelectionContainer`.
  - `AddressText` and `PayloadDisclosure` text are **not** selectable.
  - The `CopyableAddress` context menu is a long-press `DropdownMenu` with `"Copy Address"`.
  - The badges row uses `FlowRow` so it wraps at large font scales.
  - A large amount uses `autoSize` text from Compose 1.8 `BasicText(autoSize = TextAutoSize.StepBased(minFontSize = …))` down to 50%, except NFT amounts, which wrap.
- **AuthorizeSheet**:
  - A `ModalBottomSheet` with `sheetState` `skipPartiallyExpanded = false`, matching iOS's medium and large detents.
  - `confirmValueChange = { !busy }` and gestures disabled while busy.
  - `properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn)` sets `FLAG_SECURE` on the password sheet.
  - The password field is an `OutlinedSecureTextField`, or a `TextField` with `PasswordVisualTransformation`: `KeyboardType.Password`, `ImeAction.Done` calls `sign()`, and the semantics content type is `Password` so existing-password autofill works.
  - Focus with a `FocusRequester` after `delay(350)`, then show the keyboard.
  - The Sign button sits in the sheet's bottom area with `imePadding()`.
  - Clear the field on submit and on dismiss.
  - Close the sheet when `securityEpoch` changes.
- **Result**:
  - `QrCodeView`: ZXing `QRCodeWriter` with `ErrorCorrectionLevel.M` when the frame is ≤ 600 UTF-8 bytes, otherwise L, and `MARGIN = 1`.
    - Render each frame to a 1-pixel-per-module `ImageBitmap` off the main thread (`Dispatchers.Default`).
    - Draw with `FilterQuality.None` inside a white rounded `Surface` with radius 24dp and 18dp padding.
    - Uppercase UR frames encode in alphanumeric mode automatically.
  - Animation: `LaunchedEffect(frames) { while (isActive) { delay(250); if (!paused) index = (index + 1) % n } }`.
  - Controls: a `FilledTonalIconButton` for play and pause, a `LinearProgressIndicator`, and the `"%d/%d"` text.
  - `contentDescription` as in §15.1.
  - Optional Android polish: raise window brightness while the QR is visible, with `WindowManager.LayoutParams.screenBrightness`, restored on dispose. iOS does not do this, so keep it subtle or omit it.
  - Success haptic on first composition: `HapticFeedbackConstants.CONFIRM` on API 30+.

### 23.8 Clipboard

Implement `Clipboard.copy(text)` as follows:

1. Call `ClipData.newPlainText("", text)`.
2. Set `ClipDescription.EXTRA_IS_SENSITIVE` on API 33+, which hides the clip from previews and keyboards. It is optional for public data but matches iOS's "local only" intent.
3. Schedule an in-process clear after **120 s**, but only if the clip is still ours: use a token in the clip extras (Ownbit's `ClipboardHelper.copySensitive` pattern).

Android has no clipboard expiration API, and Handoff has no Android equivalent.

Keep the inline "Copied" state change (1.5 s) even on Android 13+, which shows its own system confirmation. The inline state is not a toast.

### 23.9 Lifecycle, suspend and state

- Map `suspend()` triggers:
  - `ProcessLifecycleOwner` `ON_STOP` (background);
  - App Lock engaging;
  - screen capture, where the app uses `FLAG_SECURE`, or the Android 15+ `WindowManager.addScreenRecordingCallback` if it is used (see the privacy spec).
- Keep the signing state in the `@Singleton` store, so it survives configuration changes and tab switches. Do not persist the review, plan or partial signatures across process death. iOS keeps them in memory only; losing them on process death equals iOS termination.
- The `ScanSessionController` lives in the scanner's ViewModel. Its coroutine work runs on `Dispatchers.Main.immediate`, and the engine call suspends onto the engine dispatcher. Mirror `token` and the epoch checks exactly as in §7.
- `localWalletId` uses `UUID.toString()`, which is lowercase. Only the device-local task ordering differs from iOS, which is harmless. Keep it stable for receipts.

### 23.10 apkRelease vs playRelease

This feature has no flavor differences:

- no Play services;
- ZXing and CameraX are bundled;
- no network;
- the camera is optional hardware.

Use the same `applicationId` and the same strings. Keep any flavor-specific code (update checks and similar) out of `:feature:sign`.

### 23.11 Pitfalls checklist

- Port the frame gate's exact semantics, and don't trim or uppercase scanned text before the engine. iOS's `wrapped` test sends `" \nUR:BYTES/TESTFRAME\n"` and requires it to be delivered unchanged.
- The 512 KB file limit is **512,000 bytes**. The text limit is **262,144 UTF-8 bytes**. The QR frame gate limit is **16,384 UTF-8 bytes**. The engine frame limit is 4,300 characters. The request freeze limit is 1,048,576 bytes. The engine response limit is 2,097,152 bytes.
- Check every byte limit on **UTF-8 bytes**, not `String.length` (UTF-16 units).
- `progress` from the engine may be the integer `0` or `1`. Read it as `JsonPrimitive.double`.
- Keep `plan.raw` byte-faithful. Reorder keys only by sorting, never drop or retype a value, and never send a Kotlin data-class re-encoding of the plan.
- Risk acknowledgement resets after each signed task. Match that.
- The camera must stop when the app is not resumed, and the torch must turn off.
- Dispose QuickJS contexts promptly. A fresh context per call without disposal leaks native memory fast.
- Never show engine warnings through translation, and never colour a known contract green.
- `ReviewText.headline` uses the keys `"Safe:"` and `"Batch:"`, with the colon.
- Pretty JSON in the payload view: sorted keys, 2-space indent, unescaped slashes. Apple writes `"key" : value`; matching that is cosmetic.
- Do not make the payload text selectable.

---

## 24. Out of scope (iOS-only, drop or replace)

- Liquid Glass and iOS 26 APIs: `glassEffect`, `.glassProminent`, `.glass` button styles, `safeAreaBar`. Also the iOS 17/18 `#available` fallbacks (`GlassCircle`, `prominentAction`, `bottomActionBar`, `wallet.bifold` vs `wallet.pass`). Replace them with M3 components.
- SF Symbols and `symbolEffect` (pulse, bounce, replace content transitions). Replace them with Material Symbols plus Compose animations.
- `sensoryFeedback`. Replace it with `HapticFeedbackConstants` (§20, §23).
- AVFoundation specifics: `AVCaptureMetadataOutput`, `RotationCoordinator`, session presets, `autoFocusRangeRestriction`, `isTorchAvailable`, `videoRotationAngle`. Replace them with CameraX + ZXing.
- `UIPasteboard` `.localOnly` / Handoff and `.expirationDate`. Replace them with ClipboardManager plus a scheduled clear.
- `fileImporter`/`fileExporter`, `FileDocument`, `UTType`, security-scoped resources. Replace them with SAF.
- `presentationDetents`, `interactiveDismissDisabled`, `fullScreenCover`, `confirmationDialog`, `ViewThatFits`, `readableWidth` for iPad. Replace them with Compose equivalents.
- `isSceneCaptured`, `scenePhase`, `UIApplication.openSettingsURLString`. Replace them with lifecycle callbacks, `FLAG_SECURE` and the app details settings intent.
- Face ID wording and the `NSFaceIDUsageDescription` permission prompt.
- The `NSCameraUsageDescription` system prompt has no Android equivalent; at most, reuse its text as the in-app rationale.
- `SimulatorUITestContext`, UI-test simulator behaviour, App Store and TestFlight specifics, `PrivacyInfo.xcprivacy`.
- JavaScriptCore notes: `autoreleasepool`, JSC exception handler, no-JIT timing. CommonCrypto `CCKeyDerivationPBKDF`. CoreImage `CIQRCodeGenerator` with the software `CIContext`.
- `IncrementalQREngine` / batch `decodeQR` fallback for non-incremental test engines. This is optional on Android and used only for tests.
