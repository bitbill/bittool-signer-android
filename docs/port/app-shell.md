# Port spec: app shell, lifecycle, routing, onboarding, privacy and App Lock

Source of truth: the iOS app at `/Users/tanguopeng/Documents/bitsigner-ios/BitSigner`. All Swift citations below are
relative to that directory (`BitToolSigner/...`). Android prior art: Ownbit 5.0 at
`/Users/tanguopeng/AndroidStudioProjects/ownbitandroidnew` (cited as `ownbit:<path>`).

Files read in full for this spec: `BitToolSigner/BitToolSignerApp.swift`, `Features/Main/{RootView,MainTabView,AppRouter}.swift`,
`Features/Onboarding/WelcomeView.swift`, `Features/Lock/AppLockView.swift`, `Services/{AppAccessController,PrivacyShield,
RecoveryPrivacySession,RecoveryScreenshotSession,SimulatorUITestContext}.swift`, `Resources/LaunchScreen.storyboard`,
`docs/Security.md`, the app target's build settings in `BitToolSigner.xcodeproj/project.pbxproj`, plus the consumers needed
to describe behaviour exactly: `Models/AppStore.swift`, `Models/AppLanguage.swift`, `Features/Setup/{WalletSetupFlow,
WalletSetupModel}.swift`, `Features/Wallets/WalletsTab.swift`, `Features/Sign/ScanTab.swift`, `Features/Settings/{SettingsTab,
LegalDocumentView}.swift`, `Design/{DesignSystem,Logos,PasswordViews}.swift`, `Core/WalletVault.swift`, the privacy modifiers in
`Features/Setup/RecoveryPhraseViews.swift:315-380`, and the tests `BitToolSignerTests/{AppAccessTests,PrivacyShieldTests,
ScanRequestRouterTests,RecoveryPrivacyTests,RecoveryScreenshotTests}.swift`, `BitToolSignerUITests/BitToolSignerUITests.swift`.

Other port specs own the screens this shell hosts (setup steps, wallets/connect, scan/review/sign, activity, settings
pages, backup panel, vault, engine). This spec owns: process start, launch, the root stage machine, welcome + onboarding,
the tab container and all cross-screen routing, scene-phase handling, the App Lock state machine and lock screen, the
privacy cover, screen-capture/screenshot reactions, and the two recovery privacy state machines that the backup/setup
screens consume.

---

## 1. Object graph and layering (iOS)

```
BitToolSignerApp (@main)                                  BitToolSignerApp.swift:3-46
 ├─ AppDelegate ── PrivacyShield (native window, level .alert+2)   :48-53, PrivacyShield.swift:18-62
 ├─ WalletVault (one instance shared by both objects below)        :25
 ├─ AppStore(vault:)            @StateObject                       :26
 ├─ AppAccessController(vault:) @StateObject  (App Lock)           :27
 └─ WindowGroup → RootView                                          :30-44
       ├─ AppRouter @StateObject (owned by RootView, not the App)   RootView.swift:8
       ├─ stage: LaunchView | LoadFailureView | WelcomeView | MainTabView   :13-30
       ├─ in-tree PrivacyShieldView when captured or not active      :31-33
       ├─ .sheet(item: router.setup) → WalletSetupFlow               :41-46
       ├─ .alert("Unable to Continue") for store.error               :66-68
       └─ AppLockWindowPresenter (separate UIWindow, level .alert+1) :40, AppLockView.swift:133-186
```

Z-order from bottom to top: main window (stages, tab stacks, every sheet/alert/full-screen cover) → App Lock window
(`.alert + 1`, AppLockView.swift:167) → privacy shield window (`.alert + 2`, PrivacyShield.swift:52). The lock window
covers every presented sheet and keeps their state; the shield covers the lock window too
(Security.md:111 "A native privacy window covers every presented sheet while inactive, backgrounded or captured. A separate
lower authentication window preserves existing navigation and modal routes behind App Lock.").

Design rule stated in code and docs, to keep verbatim in the Android architecture:
- AppRouter is "UI navigation only. Nothing here selects a signer or grants authorization: routes carry an explicit wallet
  ID solely to show that wallet's public data." (AppRouter.swift:17-18)
- AppAccessController "authenticates access to public app pages only. It never derives accounts, returns a secret,
  chooses a signer, or authorizes signing." (AppAccessController.swift:81-82)
- AppStore "has no active wallet, unlocked wallet, reusable signing authorization, or retained clear secret."
  (AppStore.swift:14-15). "There is deliberately no foreground idle timer or wallet-unlock lifetime." (AppStore.swift:83)

---

## 2. Process start, persisted preferences, build settings

### 2.1 Start sequence
1. `AppDelegate.didFinishLaunching` → `privacyShield.start()` (BitToolSignerApp.swift:51). Shield observers exist before any UI.
2. `BitToolSignerApp.init` (non-test path, :24-27): `defaults = .standard`; one `WalletVault()`; `AppStore(vault:)`;
   `AppAccessController(vault:)`.
3. `AppStore.init` schedules `reload()` in a Task unless an explicit reload already started (AppStore.swift:75-79).
   `isLoading` starts `true`, `walletLoadSucceeded` `false` (:19-20).
4. `AppAccessController.init` reads `appLockEnabled.v1`; `isLocked = isEnabled` — "No authenticated state is persisted:
   enabled cold launches start locked." (AppAccessController.swift:113-116). Biometry kind is read once here (:112).
5. The scene shows `RootView` with the language environment, the env objects and `preferredColorScheme`
   (BitToolSignerApp.swift:31-36). `privacyShield.style` follows Settings › Appearance with `initial: true` (:37-39).

### 2.2 Persisted keys (UserDefaults; none of them secret)

| Key | Type / values | Default | Written by | Read by |
|---|---|---|---|---|
| `appearance` | `"system"`, `"light"`, `"dark"` | `"system"` | Settings › Theme (SettingsTab.swift:45-51) | App scene `preferredColorScheme` (BitToolSignerApp.swift:36), shield window style (PrivacyShield.swift:26), lock window style (AppLockView.swift:176) |
| `language` | `AppLanguage.rawValue`: `system`, `en`, `zh-Hans`, `zh-Hant`, `ja`, `ko`, `ru`, `uk`, `es`, `es-419`, `de`, `fr`, `it`, `pt-BR`, `pt-PT`, `pl`, `id`, `tr`, `th`, `vi`, `fil`, `ar` (AppLanguage.swift:9-31) | `system` | Settings › Language | `.appLanguage(...)` (BitToolSignerApp.swift:33), `AppText.bundle` for model-layer strings (AppLanguage.swift:121-126) |
| `onboardingVersionCompleted` | Int | `0` | set to `1` when the introduction's last page is confirmed (WalletSetupFlow.swift:37) | Welcome / Wallets tab / setup flow (`>= 1` means done) (WelcomeView.swift:8,112; WalletsTab.swift:6,63) |
| `appLockEnabled.v1` | Bool | `false` | `AppAccessController.setEnabled` and `updateWalletCount(0)` (AppAccessController.swift:98,125,139) | `AppAccessController.init` (:113) |

There is no persisted "unlocked" state, no last-active timestamp, no persisted tab or navigation path.

### 2.3 Build / Info.plist settings that affect behaviour (app target, project.pbxproj:431-492)
- Display name `INFOPLIST_KEY_CFBundleDisplayName = "BitTool Signer"`; bundle id `com.bittool.signer`; `MARKETING_VERSION = 1.0.0`,
  `CURRENT_PROJECT_VERSION = 1` (Settings shows `"\(short) (\(build))"` → "1.0.0 (1)").
- `INFOPLIST_KEY_NSCameraUsageDescription = "BitTool Signer scans signing requests from your other wallet and recovery-phrase QR codes. Camera images are processed on this device and never leave it."`
  (translated per language in `translations/<lang>.json` → `infoPlist.NSCameraUsageDescription`).
- `INFOPLIST_KEY_NSFaceIDUsageDescription = "Use Face ID to open BitTool Signer. Signing requires your wallet password."` (iOS-only, drop).
- Orientations: iPhone `Portrait, LandscapeLeft, LandscapeRight` (no upside down); iPad all four. Device family `1,2`
  (iPhone + iPad). → Android: do NOT force portrait (unlike Ownbit, whose iOS app is portrait-only); support tablets.
- `UILaunchStoryboardName = LaunchScreen`; global accent color asset `AccentColor`; `ENABLE_USER_SELECTED_FILES = readonly`
  (file importer only); deployment target iOS 17.0.
- No URL schemes / associated domains / background modes: the app has no deep links and no background execution.

### 2.4 Keyboard extensions (app-wide)
`AppDelegate.application(_:shouldAllowExtensionPointIdentifier:)` returns `extensionPointIdentifier != .keyboard`
(BitToolSignerApp.swift:52): every third-party keyboard is refused app-wide (Security.md:125 explains why: the recovery
phrase editor is an ordinary visible text view). Android has no equivalent; see §15.9 for the mitigations.

---

## 3. Launch screen and LaunchView

### 3.1 LaunchScreen.storyboard (Resources/LaunchScreen.storyboard:2-18)
- Background: named color `LaunchBackground` = `#F2F2F7` (light), `#000000` (dark) (Assets.xcassets/LaunchBackground.colorset).
  This equals `systemGroupedBackground`, i.e. `Palette.background`, so the first SwiftUI frame continues the image.
- A vertical stack centered on the **full view** (not the safe area), `spacing 18`, leading/trailing ≥ 30 from the safe area:
  1. image `BrandTile` 96 × 96 (scaleAspectFit) — the dark tile (white mark on ink `#0A0A0C`, continuous corners);
  2. label `"BitTool Signer"`, system 22 pt semibold, `labelColor`, centered;
  3. label `"Air-gapped signing. For everyone."`, system 16 pt regular, `secondaryLabelColor`, centered, unlimited lines.
- The storyboard is English-only and follows the **system** appearance (it cannot follow the in-app setting;
  IMPLEMENTATION_STATUS.md revision notes confirm).

### 3.2 LaunchView (RootView.swift:72-90)
"Matches LaunchScreen.storyboard exactly, so loading never flashes a new layout." `ZStack { Color("LaunchBackground");
VStack(spacing: 18) { BrandTile(size: 96, shadow: false); Text("BitTool Signer").font(.system(size: 22, weight: .semibold));
Text("Air-gapped signing. For everyone.").font(.system(size: 16)).foregroundStyle(.secondary).multilineTextAlignment(.center) }
.padding(.horizontal, 30) }.ignoresSafeArea()` — centered on the full screen; `.accessibilityElement(children: .combine)`
(read as one element). Unlike the storyboard, these texts are localized (zh: "离线签名，人人可用。").

`BrandTile` (Design/Logos.swift:224-233): image `BrandTile`, `.interpolation(.high)`, square `size`, shadow
`black 16% opacity, radius size*0.1, y size*0.04` when `shadow == true` (default), accessibility hidden.

LaunchView is reused by the privacy cover (§12.2).

---

## 4. Root stage machine (RootView)

### 4.1 Stages (RootView.swift:13-18)
```
stage = .loading  if store.isLoading
        .failed   else if !store.walletLoadSucceeded
        .welcome  else if store.wallets.isEmpty
        .main     otherwise
```
Rendered over `Palette.background.ignoresSafeArea()` (:24): `.loading → LaunchView()`, `.failed → LoadFailureView()`,
`.welcome → WelcomeView().transition(.opacity)`, `.main → MainTabView().transition(.opacity)` (:25-30). Stage changes
animate with `.animation(.smooth(duration: 0.35), value: stage)` (:35) — a 0.35 s cross-fade.

Important: "A failed wallet-list load is not treated as an empty list" (Security.md:52). `.failed` never shows Welcome,
never disables App Lock (`updateWalletCount` is not called on failure).

The setup sheet is attached to RootView itself (:41), so it survives stage changes: creating the first wallet flips the
stage welcome → main underneath the still-open setup sheet.

### 4.2 LoadFailureView (RootView.swift:92-113)
`ContentUnavailableView`:
- title `Label("Wallets Unavailable", systemImage: "externaldrive.badge.exclamationmark")`
- description `"Your encrypted wallet files are unchanged. Unlock this device, then try again."`
- action: prominent button `ActionLabel("Try Again", systemImage: "arrow.clockwise", busy: retrying)`, `.frame(maxWidth: 280)`,
  disabled while retrying; tap → `retrying = true; await store.reload(); retrying = false`.
`AppStore.reload()` failure also sets `store.error = error.localizedDescription` (AppStore.swift:146), so the global alert
(§4.4) is shown on top of this view as well (double report; replicate or keep only the page — see §15.14).

### 4.3 RootView reactions (exact list, RootView.swift:47-65)

| Trigger | Action | Cite |
|---|---|---|
| `store.isLoading` → false and load succeeded | `access.updateWalletCount(store.wallets.count)` | :47-49 |
| `store.walletLoadSucceeded` → true | `access.updateWalletCount(store.wallets.count)` | :50-52 |
| `store.wallets.count` changes (only if not loading and load succeeded) | `access.updateWalletCount(count)`; if `count == 0`: `router.tab = .wallets; router.resetNavigation()` | :53-57 |
| `store.navigationResetEpoch` changes (set by `deleteWallet`, AppStore.swift:529) | `router.resetNavigation()` | :58 |
| `canPrefetch` changes, **initial: true**; `canPrefetch = scenePhase == .active && !access.isLocked && !isSceneCaptured && stage == .main` (:20) | `store.setConnectPrefetch(allowed:)` | :59 |
| `access.isLocked` → true | `store.suspend()` | :60 |
| `isSceneCaptured` → true | `store.suspend()` | :61 |
| `store.review?.requestHash` changes to non-nil | `router.tab = .scan` | :62 |
| `UIApplication.willEnterForegroundNotification` | `access.willEnterForeground()` | :63-65 |

And at the App level (BitToolSignerApp.swift:40-43): `scenePhase == .background` → `store.suspend()` **then**
`access.didEnterBackground()`; `scenePhase == .active` → `access.willEnterForeground()` (idempotent, see §11.5).

While locked (RootView.swift:37-39): the whole root content is `.opacity(0)`, `.allowsHitTesting(false)`,
`.accessibilityHidden(true)`. No animation is attached to these (lock/unlock is instant on iOS).

### 4.4 Global error alert (RootView.swift:66-68)
`.alert("Unable to Continue", isPresented: store.error != nil)` with one button `"OK"` (role cancel) that clears
`store.error`; message `Text(store.error ?? "")` (verbatim). Sources of `store.error`: wallet-list load failure (localized
vault/storage error), `"The local activity record could not be read."` (AppStore.swift:539), `"The local activity record
could not be saved. Any completed signature remains available."` (:601), `"The encrypted wallet was deleted, but its public
cache could not be removed."` (:528). The last three are raw English literals on iOS (not in `translations/*.json`) — localize
them on Android.

### 4.5 Connect prefetch (the only engine work the shell itself schedules)
`store.setConnectPrefetch(allowed:)` (AppStore.swift:270-273): when allowed, after **600 ms** (`Task.sleep(for: .milliseconds(600))`,
:284-285, "Let launch, unlock or the foreground transition finish first") it runs, for each wallet with metadata status
`.ready` and no cached result, engine op **`createWalletPairing`** with parameters `{"publicIndex": <wallet public index
object>, "mode": "full"}` (:305-309), parses `WalletPairingPayload(result)` and renders its `frames` to QR images; a result
is discarded if the security epoch changed, the wallet's public index changed, or the task was cancelled (:297-298).
Results live in memory only and are cleared by `suspend()` and on every reload. Disallowed → task cancelled. (Payload
fields and QR rendering are specified in the wallets/connect spec.) `createWalletPairing`, `createPublicPairing` and
`derivePublicAddresses` are the only "warm VM" operations (Core/SigningEngine.swift:124).

### 4.6 `AppStore.suspend()` — what "suspend" does (AppStore.swift:86-94)
```
securityEpoch = new; sensitiveEpoch = new; requestEpoch = new
signingOperation = nil; busy = false
clearConnectPrefetch()                       // cancel task + drop cached QRs
engine.releaseWarmContext()                  // drop warm JS VM (SigningEngine.swift:180-182)
if review != nil && plan == nil && matchingError == nil:
    matchingError = "Matching interrupted. Try again."   // raw English literal on iOS; localize
```
Called on: background (BitToolSignerApp.swift:41), lock engaged (RootView.swift:60), capture started (:61), and internally
after a password reset (AppStore.swift:510) / wallet deletion (:526) / matching invalidation (:104). The public request,
review and any completed signature response survive (Security.md:115).

`securityEpoch` is the broadcast that clears view-held secrets. Every consumer (port all of them):

| Consumer | Effect of a new `securityEpoch` | Cite |
|---|---|---|
| Lock screen | invalidate in-flight password op, clear password, `busy = false` | AppLockView.swift:43 |
| Setup flow | `model.suspend()`: invalidate op, `busy = false`, clear `password`, `confirmation`, `error`, reset verification placements (mnemonic, phrase input, passphrase draft **kept**) | WalletSetupFlow.swift:56, WalletSetupModel.swift:192-196 |
| Setup backup step | `.id(store.securityEpoch)` rebuilds the backup panel → phrase/QR concealed, Recovery Cards and tips sheets closed | WalletSetupFlow.swift:294 |
| Review page | `riskAccepted = false`; close the authorize sheet | Features/Sign/RequestReviewView.swift:59 |
| Authorize sheet | `cancel()`: invalidate op, clear password, dismiss | RequestReviewView.swift:877 |
| Saved-wallet recovery phrase sheet | discard the decrypted phrase and the password (back to the password form) | Features/Wallets/WalletSecuritySheets.swift:77-80 |
| Change-password sheet | clears its fields | WalletSecuritySheets.swift:173 |
| Delete sheet | if not busy: clear password, close the password prompt | WalletSecuritySheets.swift:261 |
| Repair sheet | if not busy: clear password | WalletSecuritySheets.swift:332 |
| Activity tab | dismiss the "Clear all activity?" dialog | Features/Activity/ActivityTab.swift:58 |
| Scan home / Paste sheet | `busy = false` | ScanTab.swift:155, :308 |
| Scan session | a frame decode that finished under an old epoch is re-queued, not applied | ScanTab.swift:64-74 |

Not epoch-based but lifecycle-based (same intent): `PasswordField(revealable:)` hides revealed text whenever
`scenePhase != .active` (Design/PasswordViews.swift:96); the QR scanner shows the camera only while `scenePhase == .active`,
turns the torch off when not active, and re-reads camera authorization on return (Features/Sign/QRScannerView.swift:29,56-59);
recovery displays conceal on inactive/background/capture/screenshot (§13).

---

## 5. Welcome screen (Features/Onboarding/WelcomeView.swift)

Shown only at stage `.welcome` (no wallets after a successful load). It is the root: no back, no tab bar.

### 5.1 Layout (:13-60)
- `GeometryReader` → `ScrollView` → `VStack(spacing: 0)`: `Spacer(minLength: 32)`, hero, `Spacer(minLength: 28)`, features,
  `Spacer(minLength: 28)`, networks, `Spacer(minLength: 24)`; `.padding(.horizontal, 28)`; `.frame(minHeight: viewport height)`
  (content vertically distributed on tall screens, scrolls on short ones); `.readableWidth(520)` (max 520 pt, centered);
  `.scrollBounceBehavior(.basedOnSize)` (no bounce when it fits).
- Background (top-aligned): `RadialGradient([accent @ 16% opacity, clear], center: .top, startRadius: 10, endRadius: 420)`
  ignoring safe area, accessibility hidden; under it `Palette.background`.
- Bottom action bar (`bottomActionBar(maxWidth: 520, bottomPadding: 0)`, DesignSystem.swift:72-91 — a pinned bar above the
  home indicator, `VStack(spacing: 10)`, horizontal padding 20, top padding 10 (8 on iOS 26), bar material background on
  iOS < 26), containing in order:
  1. Prominent capsule button `ActionLabel("Create New Wallet", systemImage: "plus")`, id `welcome.create` → `start(.create)`.
  2. Secondary capsule button `ActionLabel("Import Existing Wallet", systemImage: "square.and.arrow.down", prominent: false)`,
     id `welcome.import` → `start(.restore)`.
  3. `HStack(spacing: 6)`: plain text button `"Terms of Use"` (id `welcome.terms`), `Text("·")` tertiary color, plain text
     button `"Privacy Policy"` (id `welcome.privacy`); footnote font, accent tint, `.padding(.top, 12)` on top of the bar's 10 pt
     spacing ("the legal links are a separate thought from the two actions above them"). Bottom padding 0 under this row.
- Legal links open `LegalDocumentSheet` (Settings/LegalDocumentView.swift:105-120): a sheet with its own navigation bar,
  `LegalDocumentView(document:)` and a trailing `"Done"` button (id `legal.done`). Content is the bundled
  `TERMS_OF_USE.txt` / `PRIVACY_POLICY.txt` parsed as blocks (title line dropped, `Version …` line, `## ` headings, `- ` bullets,
  blank-line paragraphs; text selectable; missing file → `"This document is missing from the app bundle."`). Navigation
  title is `"Terms of Use"` / `"Privacy Policy"` (English String on iOS; documents are English by decision). No browser.

### 5.2 Hero (:62-74), `VStack(spacing: 14)`
- `BrandTile(size: 96)` (with shadow); on appear animates `scale 0.9 → 1` and `opacity 0 → 1` with `.spring(duration: 0.8)`,
  or no animation when Reduce Motion is on (:59).
- `"BitTool Signer"` — `.largeTitle.weight(.bold)` (34 pt bold).
- `"Air-gapped signing. For everyone."` — `.title3` (20 pt), secondary, centered.

### 5.3 Features (:76-84, row builder :96-109), `VStack(alignment: .leading, spacing: 18)`, full width, leading
Each row: `HStack(alignment: .top, spacing: 14)`: SF Symbol `.title2`, accent tint, width 34, accessibility hidden;
`VStack(alignment: .leading, spacing: 2)`: title `.headline`, detail `.subheadline` secondary, wraps; row is one
accessibility element (combined).

| Symbol | Title | Detail |
|---|---|---|
| `lock.iphone` | `"Keys stay on this device"` | `"Built to work entirely offline. No accounts, no cloud."` |
| `chevron.left.forwardslash.chevron.right` | `"Fully open source"` | `"Every line that touches your keys is published. Read it, or rebuild it and compare."` |
| `checkmark.shield` | `"Know what you sign"` | `"Scan a request, see it decoded and matched to your wallet, then sign it here."` |

### 5.4 Networks (:86-94), `VStack(spacing: 10)`
- `NetworkLogoStack(size: 28, ring: Palette.background)` (Logos.swift:141-157): the 7 bundled network logos in order
  Bitcoin, Ethereum, BNB Smart Chain, Arbitrum One, Base, TRON, Solana; overlapping (`HStack(spacing: -size*0.28)`), each
  padded by `max(1.5, size*0.08)` inside a circle of the ring color; each logo has a hairline circle stroke
  `primary @ 14%, width max(0.5, size/56)`. Accessibility: one element, label = short names joined with ", " →
  "Bitcoin, Ethereum, BNB Chain, Arbitrum, Base, TRON, Solana" (not localized on iOS).
- `"Bitcoin, Ethereum, BNB Chain, Arbitrum, Base, TRON and Solana"` — footnote, secondary, centered.

### 5.5 Start (:111-113)
`router.startSetup(intent, firstWallet: store.wallets.isEmpty, introductionCompleted: onboardingVersion >= 1)` — see §7.

---

## 6. Onboarding (OnboardingView, WelcomeView.swift:116-245)

"Three-page introduction shown before the first wallet is created or imported. Safety guidance is a required step; there
is intentionally no "Skip"." (:116-117). UI test asserts no `Skip` button (BitToolSignerUITests.swift:34).

### 6.1 When it is shown (shown once, intent preserved)
- `showsIntroduction = firstWallet && !introductionCompleted` (AppRouter.swift:44), where `firstWallet = store.wallets.isEmpty`
  and `introductionCompleted = onboardingVersionCompleted >= 1`. So it appears only when adding the first wallet and only
  until completed once. Adding a wallet from the Wallets tab when wallets exist never shows it.
- Completion (`"Get Started"` on page 3) sets `onboardingVersionCompleted = 1` and transitions with `.smooth` animation to
  the setup steps for the **same intent** (WalletSetupFlow.swift:36-39). Cancel dismisses the whole setup sheet **without**
  setting the flag (:40), so the next Create/Import shows it again.
- The flag survives deleting all wallets: after the last wallet is deleted the app returns to Welcome and Create/Import go
  straight to step 1 (UI test BitToolSignerUITests.swift:51-55: "The introduction is shown once.").
- Intent survives: Import → 3 pages → "Step 1 of 2" with title "Import Wallet" (UI test :37-40). Create → "Step 1 of 4".
- `readOnly` mode (`"Done"` instead of `"Get Started"`, no Cancel) exists (:120,184,191) but has **no caller** in the current
  app (the Settings "Offline Setup Guide" was removed; UI test :915). Not needed on Android.

### 6.2 Pages (:140-157) — symbol, title, body, points (point id = SF Symbol)

Page 1 — hero `lock.iphone`, title `"Offline by Design"`, body `"Your keys are created, encrypted and used only on this device. BitTool Signer works without a network connection."`
- `key.fill` — `"Recovery phrases never leave the app"`
- `wifi.slash` — `"No accounts, servers or cloud sync"`
- `hand.raised.fill` — `"Every signature needs your wallet password"`

Page 2 — hero `qrcode.viewfinder`, title `"Built for Compatibility"`, body `"Pair BitTool Signer with a watch-only wallet on another device. It prepares transactions; BitTool Signer reviews and signs them."`
- `arrow.left.arrow.right` — `"Open BC-UR and PSBT QR standards"`
- `square.stack.3d.up.fill` — `"Bitcoin, EVM networks, TRON and Solana"`
- `text.magnifyingglass` — `"Requests are decoded before you sign"`

Page 3 — hero `airplane`, title `"Prepare Your Device"`, body `"For the strongest isolation, use a dedicated iPhone and keep its connections off."`
- `airplane` — `"Turn on Airplane Mode"`
- `wifi.slash` — `"Confirm Wi‑Fi, cellular data and Bluetooth are off"` (note U+2011 non-breaking hyphen in "Wi‑Fi")
- `antenna.radiowaves.left.and.right.slash` — `"If iOS asks to allow wireless data, choose Don’t Allow: the app never connects"` (U+2019)
- `eye.slash.fill` — `"Keep your recovery phrase private: anyone with it can take your funds"`

Android wording changes needed (iOS-specific text; new keys + 20 translations): page 3 body "iPhone" → "phone"; the
"If iOS asks to allow wireless data…" point refers to the iOS (China-region) wireless-data prompt and has no Android
meaning — replace it (recommended new point: "BitTool Signer has no internet permission, so Android blocks every
connection") or drop it.

### 6.3 Controls (:159-201)
- Own `NavigationStack` with inline (empty) title; page container `TabView(selection: $page)` in `.page(indexDisplayMode: .never)`
  style → horizontal swipe between pages; `.animation(.smooth, value: page)`; background `Palette.background`.
- Bottom bar (`bottomActionBar(maxWidth: 520)`):
  - page indicator `HStack(spacing: 8)`: one capsule per page, height 8; current page width 22 filled with accent; others
    width 8 filled `systemFill`; `.animation(.snappy, value: page)`; accessibility: single element (children ignored), label
    `"Page \(page + 1) of \(pages.count)"` (key `"Page %lld of %lld"`); `.padding(.bottom, 6)`.
  - prominent button, id `onboarding.next`: label `"Continue"` on pages 1–2, `"Get Started"` on page 3; action: next page or
    `onComplete()`.
- Toolbar: leading (cancellation) `"Cancel"` → `onCancel` (dismisses setup sheet); trailing (primary action) `"Back"` shown
  only when `page > 0` → `page -= 1`.
- Sheet swipe-to-dismiss is allowed during onboarding (no draft secrets yet: WalletSetupFlow.swift:45).

### 6.4 Page layout (:203-244)
- `compact = viewport height < 560` ("Short screens (iPhone SE, landscape)").
- `ScrollView` (bounce only if needed) → `VStack(spacing: compact ? 16 : 24)`:
  - `SymbolHero(systemImage: page.symbol, size: compact ? 36 : 56)`, `.padding(.top, compact ? 4 : 16)`. SymbolHero
    (DesignSystem.swift:213-234): filled circle `accent @ 12%` diameter `size*1.7`; ring `accent @ 18%`, 1 pt, diameter
    `size*2.2`; symbol at `size*0.62` pt, medium weight, accent, monochrome; bounce effect once on appear unless Reduce
    Motion; frame `size*2.2`; accessibility hidden.
  - `VStack(spacing: 10)`: title `.largeTitle.weight(.bold)` centered; body `.body` secondary centered, wraps.
  - points card: `VStack(alignment: .leading, spacing: 14)` of `Label { Text(point).font(.subheadline) } icon: { Image(symbol)
    .foregroundStyle(.tint).frame(width: 24) }`, full width leading, `.cardBackground()` (padding 16, radius 22, `Palette.card`).
- `.padding(.horizontal, 24)`, `.padding(.bottom, 24)`, `.readableWidth(520)`.

---

## 7. Setup presentation (container-level behaviour; steps are in the setup spec)

`AppRouter.startSetup(intent, firstWallet:, introductionCompleted:)` creates a **new** `SetupPresentation(id: UUID(), intent,
showsIntroduction)` each call (AppRouter.swift:23-27,43-45) → a new sheet with a fresh `WalletSetupModel` (WalletSetupFlow.swift:13-17).

Callers: Welcome buttons (WelcomeView.swift:40,43); Wallets tab toolbar menu `"Add Wallet"` (plus) → `"Create New Wallet"`
(plus) / `"Import Recovery Phrase"` (square.and.arrow.down) (WalletsTab.swift:35-43,62-64).

WalletSetupFlow container rules relevant to the shell (WalletSetupFlow.swift):
- `introductionDone = !presentation.showsIntroduction`; if false, OnboardingView first (`.transition(.opacity)`), then the
  steps' NavigationStack (`.transition(.move(edge: .trailing).combined(with: .opacity))`) (:20-44).
- Swipe-to-dismiss disabled when `model.hasDraftSecrets || model.busy || model.created != nil` (:45);
  `hasDraftSecrets = created == nil && (mnemonic or phraseInput or passphrase or password non-empty)` (WalletSetupModel.swift:47-49).
- `"Cancel"` (leading on the first step, trailing on pushed steps; disabled while busy, :62-69): with draft secrets →
  confirmation dialog title `"Discard this wallet?"`, destructive `"Discard"` (clears secrets, dismisses), cancel
  `"Keep Editing"`; message `"The recovery phrase shown here will be erased. If you already wrote it down, it will not be used."`
  (create) or `"The words you entered will be erased from this screen."` (import) (:46-53). Without drafts: dismiss.
- Lifecycle: `onAppear → store.beginSensitiveSession()`; `onDisappear → model.clearSecrets(); store.endSensitiveSession()`;
  `securityEpoch → model.suspend()`; `navigationResetEpoch → clearSecrets()` unless a wallet was created (:54-57).
- Finish: the completion step calls `router.finishSetup(opening: route)` (:76-79) with `.connectWallet(walletID)` for
  `"Pair a Wallet"` (only after create/import, not after a password reset) or `nil` for `"Done"` (:431-446).

`finishSetup(opening:)` stores `routeAfterSetup` and sets `setup = nil` (AppRouter.swift:47-51). The sheet's `onDismiss`
(RootView.swift:41) calls `setupDidDismiss()`, which opens the stored route only **after the dismissal completes**
("Pushing while the sheet is still animating away can leave the push transition stuck mid-way.", AppRouter.swift:39-41,53-57).
A swipe dismissal leaves `routeAfterSetup == nil` → nothing opens.

Closing setup or terminating the process discards the draft (Security.md:113).

---

## 8. Main tabs (Features/Main/MainTabView.swift)

`TabView(selection: $router.tab)`, four tabs, fixed order (:8-22):

| Tag | Label | SF Symbol | Root | Badge |
|---|---|---|---|---|
| `.wallets` | `"Wallets"` | `wallet.bifold` (iOS 18+) / `wallet.pass` (:27-30) | `WalletsTab` — its `NavigationStack(path: $router.walletPath)` (WalletsTab.swift:9) | — |
| `.scan` | `"Scan"` | `qrcode.viewfinder` | `ScanTab` — own NavigationStack; shows `SignatureResultView` if a signed response exists, else `RequestReviewView` if a review exists, else `ScanHomeView` (ScanTab.swift:92-105) | `1` when `store.review != nil && !store.hasSignedResponse`, else none (:15) |
| `.activity` | `"Activity"` | `clock.arrow.circlepath` | `ActivityTab` (own stack) | — |
| `.settings` | `"Settings"` | `gearshape` | `SettingsTab` (own stack) | — |

- "The tab bar stays expanded: a signing app should keep Activity and Settings one tap away after scrolling a long review."
  (:23-24) — no hide-on-scroll / minimize behaviour.
- Default selected tab: `.wallets` (AppRouter.swift:29). Tab selection is not persisted.
- Each tab keeps its own navigation stack while another tab is shown. Only the Wallets stack is router-controlled.
- Standard iOS reselect: tapping the selected tab pops its stack to root (and scrolls to top).
- Tabs exist only at stage `.main`; when the last wallet is removed the app returns to Welcome and the router is reset
  to `.wallets` with an empty wallet path (RootView.swift:56).

---

## 9. AppRouter (Features/Main/AppRouter.swift)

### 9.1 Types
```swift
enum SetupIntent: String { case create, restore }                       // :4-7
enum WalletRoute: Hashable {                                             // :9-15
  case detail(UUID)                 // wallet page
  case connect(UUID, Chain)         // Pair page at Coin Level for that chain
  case connectWallet(UUID)          // Pair page at Wallet Level ("every chain in one code")
  case addresses(UUID, Chain)       // receive addresses for that chain
}
enum Tab { case wallets, scan, activity, settings }                      // :21
struct SetupPresentation: Identifiable, Equatable { id = UUID(); intent; showsIntroduction }  // :23-27
```
`Chain` raw values: `bitcoin`, `evm`, `tron`, `solana` (Models/Models.swift:3-4).

### 9.2 State
- `@Published tab: Tab = .wallets` (:29)
- `@Published walletPath: [WalletRoute] = []` (:30) — the Wallets tab's navigation stack.
- `@Published private(set) scanRequest = 0` (:34) and **unpublished** `handledScanRequest = 0` (:37): "A token rather than a
  flag, so asking twice in a row still works and nothing has to be reset afterwards." Changing `handledScanRequest` "must
  not redraw anything".
- `@Published setup: SetupPresentation?` (:38)
- `private routeAfterSetup: WalletRoute?` (:41)

### 9.3 API and exact semantics
| Function | Behaviour | Cite |
|---|---|---|
| `startSetup(_:firstWallet:introductionCompleted:)` | `setup = SetupPresentation(intent, showsIntroduction: firstWallet && !introductionCompleted)` | :43-45 |
| `finishSetup(opening:)` | `routeAfterSetup = route; setup = nil` | :48-51 |
| `setupDidDismiss()` | if `routeAfterSetup != nil`: clear it, then `open(route)` | :53-57 |
| `startScanning()` | `tab = .scan; scanRequest += 1` | :61-64 |
| `consumeScanRequest() -> Bool` | true once per `startScanning()` (`scanRequest != handledScanRequest` → mark handled, return true), else false | :72-76 |
| `open(_ route:)` | `tab = .wallets; walletPath = [route]` (replaces the whole stack) | :78-81 |
| `resetNavigation()` | `walletPath = []` (Wallets stack only; other tabs' stacks untouched) | :83-85 |

Why the scan token lives in the router: "that view is torn down the moment a request arrives and built again once the
signature is done, so view state resets to zero and the camera would reopen on top of the person every time they finished
signing" (:66-71).

### 9.4 Call sites (complete)
- `startScanning()`: Wallets tab toolbar trailing button `Label("Scan", systemImage: "qrcode.viewfinder")`, id `wallets.scan`
  ("Scanning is the thing this app is opened for, so it is one tap from the wallet list as well as from the tab bar.",
  WalletsTab.swift:27-34).
- `consumeScanRequest()`: `ScanHomeView.openCameraIfAsked()` on appear and on `scanRequest` change:
  `guard router.consumeScanRequest(), store.review == nil, !scanning else { return }; error = nil; scanning = true`
  (ScanTab.swift:156-170). Note the token is consumed first, so an ask made while a review is showing is dropped. Selecting
  the Scan tab by hand never opens the camera.
- `open(_:)`: only via `setupDidDismiss()` (route `.connectWallet(id)` after "Pair a Wallet").
- `resetNavigation()`: wallet count → 0 (with `tab = .wallets`), `navigationResetEpoch` (after wallet deletion) (RootView.swift:56,58).
- `tab = .scan`: when a new review appears (`store.review?.requestHash` becomes non-nil — from camera scan, paste or file
  import) (RootView.swift:62).
- Wallets stack pushes (value-based links): wallet card → `.detail(id)`; card "Pair" → `.connectWallet(id)`; card "Addresses"
  → `.addresses(id, .bitcoin)`; card "Repair wallet data" (not ready) → `.detail(id)` (WalletsTab.swift:77,114,119,131);
  wallet page → `.connect(id, chain)` for every `Chain.allCases`, `.connectWallet(id)`, `.addresses(id, .bitcoin)`
  (Features/Wallets/WalletDetailView.swift:50,55,66). Destinations: `.connect` opens Coin Level for that chain with the
  prefetched wallet-level QR handed over; `.connectWallet` opens Wallet Level with the prefetched QR (WalletsTab.swift:45-58).
- Activity stack: `NavigationLink(value: receipt)` → `ActivityDetailView` (ActivityTab.swift:29,45). Not router-controlled.

### 9.5 Router tests to port (BitToolSignerTests/ScanRequestRouterTests.swift)
1. New router: `consumeScanRequest() == false`. After `startScanning()`: `tab == .scan`, first consume true, second false.
2. After one consume, four further consumes (request arrives, review, signature result, Done) all false.
3. Asking again after consumption → exactly one more true.
4. `router.tab = .scan` set by hand → consume false.

---

## 10. Scene phase, lifecycle and capture: event mapping

iOS phases and what each one triggers (all sources cited above):

| iOS event | Shell effect |
|---|---|
| `willResignActive` (inactive: app switcher, Control/Notification Center, system alerts, Face ID prompt) | privacy shield window shown (PrivacyShield.swift:35); in-tree cover shown (`scenePhase != .active`, RootView.swift:31); recovery displays conceal (RecoveryPrivacySession.swift:40); screenshot-warning session deactivates and dismisses its alert (RecoveryScreenshotSession.swift:34); revealable passphrase field hides (PasswordViews.swift:96); camera preview removed, torch off (QRScannerView.swift:29,56-59); Connect prefetch disallowed (`canPrefetch` false) |
| `didEnterBackground` / `scenePhase == .background` | `store.suspend()` then `access.didEnterBackground()` (BitToolSignerApp.swift:41): new security epoch (clears passwords, risk acknowledgement, authorize sheet, decrypted backup, setup passwords/placements, concealment rebuild), warm VM released, prefetch cleared, monotonic background timestamp recorded, any biometric prompt cancelled. "The app revokes authorization immediately on entering the background, regardless of App Lock's grace period." (Security.md:111) |
| `willEnterForeground` (before the first frame) | `access.willEnterForeground()` → lock decision (§11.5) (RootView.swift:63-65) |
| `scenePhase == .active` / `didBecomeActive` | `access.willEnterForeground()` again (no-op if already handled) (BitToolSignerApp.swift:42); shield hidden unless captured (PrivacyShield.swift:36,48); screenshot session re-activates (RecoveryScreenshotSession.swift:36-38); prefetch allowed again if unlocked/uncaptured/main |
| `UIScreen.capturedDidChange` / `isSceneCaptured` (recording, AirPlay mirroring) | shield window and in-tree cover shown with the "recorded or mirrored" caption (PrivacyShield.swift:37,47-48; RootView.swift:31-32); `store.suspend()` when capture starts (RootView.swift:61); recovery displays conceal and refuse to reveal while captured (RecoveryPrivacySession.swift:27,42); prefetch disallowed |
| `userDidTakeScreenshot` (after the fact) | recovery displays conceal + require acknowledgement; directly visible recovery screens warn (§13) |

There is no foreground idle timer and no lock on inactive alone (Security.md:52).

---

## 11. App Lock (Services/AppAccessController.swift, Features/Lock/AppLockView.swift)

### 11.1 What it is
Optional, **off by default**. Protects access to the app's pages only. "It protects app access only, never signing.
Enabled cold launches start locked. A background interval of 120 seconds or longer requires new authentication; shorter
intervals preserve app access. … There is no foreground idle timer. Face ID's temporary inactive phase does not count as a
background transition. Retry and any saved wallet's password are available; password fallback returns no secret or signing
authorization. Removing the last wallet disables App Lock. A failed wallet-list load is not treated as an empty list."
(Security.md:52)

What it protects (hidden behind the lock window): the whole UI — wallet list (names, fingerprints, avatars), wallet pages,
addresses and pairing QRs, Activity records, Settings, any request under review or signature result, any open setup flow
or sheet (state preserved underneath). Before unlocking only wallet **names** are shown (the password picker), never
fingerprints: "Name only: the fingerprint also appears in exports, so it stays behind the lock." (AppLockView.swift:73).

What it never authorizes: signing (every signing task still needs that wallet's password), showing a recovery phrase,
password change, deletion (password; plus an optional biometric pre-check only while App Lock is on, §11.8), repair.
Unlocking does not create any session, cached password or "active wallet". Footer on the lock screen says so:
`"Unlocking the app never authorizes a signature."`

### 11.2 State (AppAccessController.swift:84-99)
`@Published private(set) isEnabled: Bool`, `isLocked: Bool`, `isAuthenticating = false`, `biometry: BiometryKind`;
`@Published var error: String?`. Private: `walletCount: Int?` (nil until the repository reports), `backgroundTime: TimeInterval?`,
`isInBackground = false`, `authenticationEpoch = UUID()`. Constants: `preferenceKey = "appLockEnabled.v1"`,
`backgroundGrace: TimeInterval = 120`. Clock: `monotonicSeconds()` = `clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW) / 1e9`
— "Monotonic time that keeps counting while the device sleeps. `systemUptime` pauses during sleep, which would let a phone
asleep for hours reopen unlocked." (:101-105; review fix F8, Security.md:103). The clock is injectable for tests (:107-109).

### 11.3 State machine

```
            cold launch: isLocked = isEnabled (no persisted auth)
                         │
   ┌──────────── UNLOCKED ◀──────────────────────────────┐
   │   background ─ record t0, cancel auth                │ authenticateBiometrics() ok
   │   foreground ─ if enabled and !(0 ≤ now−t0 < 120 s)  │ authenticate(walletID,password) ok
   │                → LOCKED                               │ updateWalletCount(0) (also disables)
   ▼                                                       │
 LOCKED ── background/foreground: stays LOCKED ───────────┘
```
- `isLocked` only becomes true at init (enabled) or in `willEnterForeground` (enabled and grace exceeded).
- `isLocked` becomes false only by a successful authentication, by `setEnabled` (sets `isLocked = false`), or by
  `updateWalletCount(0)`.

### 11.4 `updateWalletCount(_:)` (:120-127)
"Call after the repository has finished loading, not for a loading placeholder." Sets `walletCount = max(0, count)`. If
`count == 0`: cancel authentication, `isEnabled = false`, `isLocked = false`, `backgroundTime = nil`, persist `false`. (Prevents
a lock that no wallet password can open — test "deletingTheLastWalletClearsThePreferenceAndCannotDeadlockWelcome".)

### 11.5 Background / foreground (:169-189)
- `didEnterBackground()`: `guard !isInBackground`; `isInBackground = true; backgroundTime = uptime()`; `cancelAuthentication()`
  (`authenticationEpoch = new; biometric.cancel(); isAuthenticating = false`, :191-193). "A true background transition counts;
  Face ID's temporary inactive phase does not call this method and cannot cause an authentication loop."
- `willEnterForeground()`: `guard isInBackground` (idempotent; it is called twice per return); `isInBackground = false`;
  refresh `biometry = BiometryKind.current()` (production only); `defer { backgroundTime = nil }`; `guard isEnabled`;
  `now = uptime()`; stay unlocked only if `since != nil && now.isFinite && since.isFinite && now >= since && now - since < 120`;
  otherwise `isLocked = true`. "Preserve an already locked state even during a short background visit." (never unlocks here).
- Exact boundary (AppAccessTests.swift:29-49): background at 100, foreground at 219 (119 s) → unlocked; background at 219,
  foreground at 339 (120 s) → **locked**; background at 400, foreground at 399 (clock went backwards) → **locked** (fail closed).
- Repeated foreground notifications without a background never lock or prompt (AppAccessTests.swift:51-68).

### 11.6 Authentication paths
`performAuthentication(op)` (:200-212): `guard !isInBackground, !isAuthenticating else throw "App authentication is unavailable right now."`;
`epoch = new; authenticationEpoch = epoch; isAuthenticating = true; error = nil`; run `op`; `requireAuthentication(epoch)`;
on a non-cancellation error with the epoch still current → `self.error = error.localizedDescription` (shown on the lock screen);
rethrow. `isAuthenticating = false` on exit if the epoch is still current.
`requireAuthentication(epoch)` (:195-198): task not cancelled, `epoch == authenticationEpoch`, `!isInBackground` — else
`CancellationError`. Effect: a biometric success that completes after the app went to the background is discarded and the
app stays locked (test "backgroundRejectsAnInFlightBiometricCompletion", AppAccessTests.swift:70-83).

- `authenticateBiometrics()` (:142-147): only if `isEnabled && isLocked`; biometric reason
  `"Unlock BitTool Signer. Signing still requires the wallet password."` (raw String on iOS — not localized); success →
  `isLocked = false`.
- `authenticate(walletID:password:)` (:162-167): only if `isEnabled && isLocked`; `vault.verifyPassword(id:password:)`
  (Core/WalletVault.swift:191-193: a full `unlock` whose secret "dies here"); success → `isLocked = false`. **Any** wallet's
  password opens the app; the wrong wallet's password fails (AppAccessTests.swift:85-107). It goes through the vault's
  persistent per-wallet retry counter (WalletVault.swift:135-152): the attempt is counted before decryption; from the 5th
  consecutive failure a delay of `min(300, 2^(count−4))` seconds is imposed (2, 4, 8 … 300 s; count capped at 16); during the
  delay the error is `"Too many unsuccessful attempts. Try again in %lld seconds."`; success resets the counter. Wrong
  password error: `"The password is incorrect."` (Core/VaultCrypto.swift:35). Other possible vault errors are shown verbatim.
- `setEnabled(_ enabled:)` (:129-140): no-op if unchanged; if `isLocked || isInBackground` → throw
  `"Authenticate app access before changing App Lock."`; enabling requires `walletCount > 0` else throw
  `"Create a wallet before enabling App Lock."`, then a biometric prompt with reason `"Lock BitTool Signer with \(biometry.name)."`
  (raw String, not localized), `requireAuthentication`, and a re-check `walletCount > 0` (else `CancellationError`).
  **Disabling needs no biometric** (only the unlocked, foreground state). Then `isEnabled = enabled; isLocked = false`;
  persist. A failed biometric leaves it off and unpersisted (AppAccessTests.swift:7-27).

### 11.7 Biometric adapter (iOS, :54-79) and its error mapping
- Policy `.deviceOwnerAuthenticationWithBiometrics`, `localizedFallbackTitle = ""`: "No device-passcode fallback: the only
  alternative is a wallet password." (:60-61)
- Unavailable (policy cannot be evaluated or type not Face/Touch/Optic ID) → `"Biometric authentication is unavailable. Check Face ID or Touch ID in iOS Settings, or use a wallet password."`
- `userCancel`, `systemCancel`, `appCancel` → `CancellationError` (silent: no message anywhere).
- `biometryLockout` → `"Biometrics are locked after too many attempts. Use a wallet password, or unlock your iPhone with its passcode first."`
- any other failure / `success == false` → `"Authentication failed. Try again or use a wallet password."`
- `cancel()` invalidates the current context (used on background).
- `BiometryKind` (:13-51) is "used only for App Lock wording": `.faceID/.touchID/.opticID/.none`; `isAvailable = self != .none`;
  names "Face ID"/"Touch ID"/"Optic ID" (`.none` → "Face ID"); symbols `faceid`/`touchid`/`opticid`. Not enrolled → `.none`.
  Refreshed on every foreground.

### 11.8 `confirmSensitiveAction(reason:)` (:149-160)
"A one-off check before a destructive action, for someone who asked to be checked: it runs only while App Lock is on.
Turning App Lock off is a decision that this app does not use biometrics, and a delete must not reintroduce a Face ID prompt
that the person declined everywhere else. The wallet password still authorizes the action either way." `guard isEnabled,
biometry.isAvailable else return` (no prompt); otherwise a biometric prompt. Throws `CancellationError` when dismissed (the
caller aborts); any other error means "biometrics could not run, and the password alone decides" (the caller continues).
Used by Delete Wallet: reason `"Confirm before deleting this wallet."` (localized) (WalletSecuritySheets.swift:271-283);
its footer is `"Deleting asks for \(name), then for this wallet’s password."` when `isEnabled && biometry.isAvailable`, else
`"Deleting asks for this wallet’s password."` (:218-220, :267). Tests AppAccessTests.swift:125-151.

### 11.9 Lock screen UI (AppLockView.swift:6-128)
Hosted in its own window (§11.10). `ScrollView` (bounce only if needed) → `VStack(spacing: 28)`, `.padding(.horizontal, 28)`,
`.padding(.bottom, 24)`, `.readableWidth(440)`, background `Palette.background`:
1. `Spacer(minLength: 60)`.
2. `VStack(spacing: 14)`: `BrandTile(size: 84)` (with shadow); `"BitTool Signer Is Locked"` `.title2.weight(.bold)`;
   `"Unlock to view your wallets and activity."` `.subheadline` secondary centered.
3. Either **biometric actions** or the **password form** (`usePassword`).
4. Error callout (warning tone) with `error ?? access.error`, verbatim (:29-31).
5. `"Unlocking the app never authorizes a signature."` footnote, secondary, centered.

Biometric actions (:47-64), `VStack(spacing: 12)`:
- prominent `ActionLabel(verbatim: "Unlock with \(access.biometry.name)", systemImage: biometry.symbol, busy: access.isAuthenticating)`,
  disabled while authenticating, id `appLock.biometric` → `authenticateBiometrics()`. (Verbatim: not localized on iOS.)
- secondary `ActionLabel("Use a Wallet Password", systemImage: "key", prominent: false)`, id `appLock.usePassword` →
  `withAnimation(.snappy) { usePassword = true; error = nil }`; if no wallet chosen and exactly one wallet exists, preselect it.

Password form (:66-99), `VStack(alignment: .leading, spacing: 14)` in `.cardBackground(padding: 18)`:
- `"Enter the password of any wallet on this device."` subheadline secondary.
- menu `Picker("Wallet", selection: $walletID)`: first item `"Choose a wallet"` (nil), then each wallet's **name** only;
  full width leading; id `appLock.wallet`.
- `PasswordField(.walletPassword)` (placeholder/id `"Wallet password"`, secure, `textContentType(.password)`, no
  autocapitalization/autocorrection), submit label Go → `authenticatePassword`, padding 14, `Palette.raised` rounded 14.
- prominent `ActionLabel("Unlock", systemImage: "lock.open", busy: busy)`, disabled if no wallet, empty password or busy;
  id `appLock.unlock`.
- if biometrics available: text button `"Use \(name) Instead"` (key `"Use %@ Instead"`), subheadline semibold, full width →
  `withAnimation(.snappy) { password = ""; usePassword = false; error = nil }`.

Behaviour:
- `.task { authenticateBiometrics() }` (:42): **auto-prompts once** when the lock window appears (each lock engagement
  creates a new window → one auto-prompt per lock). It does not re-prompt after returning from a short background while
  still locked.
- `authenticateBiometrics()` (:101-111): if biometrics unavailable → `usePassword = true` (password form directly; note the
  single-wallet preselect is NOT applied on this path); if already authenticating → return; else clear error and call
  `access.authenticateBiometrics()`; non-cancellation errors → `error = message`.
- `authenticatePassword()` (:113-127): requires `!busy`, a wallet and a non-empty password; captures the password, **clears
  the field immediately**, `busy = true`, `error = nil`; on failure (same operation token, not cancellation) shows the error
  and refocuses the field; `busy = false` if the token still matches. A wrong password therefore requires retyping
  (UI test :789-795).
- `securityEpoch` change → new operation token, clear password, `busy = false` (:43). `onDisappear` → new token, clear password (:44).
- Initial focus: none (the field is focused only after an error).

### 11.10 Lock window semantics (AppLockWindowPresenter, AppLockView.swift:133-186)
- A zero-size probe view in the root's background finds the main window; on every update: if not locked → hide; else if no
  lock window: `source.endEditing(true)` (dismiss keyboard), remember the previous key window, create a `UIWindow` on the same
  scene at level `.alert + 1`, `accessibilityViewIsModal = true`, root = hosting controller with `AppLockView` + `store` +
  `access` (background `systemGroupedBackground`), `makeKeyAndVisible()` — no animation. Each update applies the in-app
  appearance (`overrideUserInterfaceStyle`).
- Hide: `endEditing`, hidden, root nil, previous window made key again — no animation.
- Note (iOS defect, do not port): the lock window's root does not receive `.appLanguage(...)`, so on iOS the lock screen is
  rendered in the device language rather than the in-app language. Android must use the app language everywhere.

### 11.11 Settings › Security section (SettingsTab.swift:23-42, 103-111)
- Toggle bound to `access.isEnabled`, label `Text("\(biometry.name) Lock")` (key `"%@ Lock"`) with icon `biometry.symbol`,
  id `settings.appLock`.
- Disabled when `changingLock || store.wallets.isEmpty || (!biometry.isAvailable && !access.isEnabled)` (an enabled lock
  can always be turned off, even if biometrics disappeared).
- On change: `changingLock = true; lockError = nil`; `try await access.setEnabled(new)`; non-cancellation error → `lockError`
  shown below the toggle (footnote, `Palette.danger`). Toggle reflects `access.isEnabled` (snaps back on failure).
- Section header `"Security"`; footer: available → `"Require \(name) to open BitTool Signer at launch and after 2 minutes in the background. Every signature still needs the wallet’s password."`
  (key `"Require %@ to open BitTool Signer at launch and after 2 minutes in the background. Every signature still needs the wallet’s password."`);
  unavailable → `"Set up Face ID or Touch ID in iOS Settings to lock BitTool Signer. Every signature always needs the wallet’s password."`

### 11.12 Sequences
- Cold launch, lock on: `isLocked = true` at init → lock window over LaunchView → auto biometric prompt; meanwhile the
  wallet list loads (the password picker fills in when loading finishes); success → window removed, root revealed at its
  stage (cross-fade only if the stage changed).
- Lock on, short visit (< 120 s): background → `suspend()` (passwords etc. cleared) → foreground → stays unlocked; the
  current page is kept (UI test :797-802: "A short visit elsewhere keeps the page.").
- Lock on, long visit (≥ 120 s, including device sleep): foreground → `isLocked = true` before the first frame → lock window;
  RootView also calls `store.suspend()` (RootView.swift:60).
- Last wallet deleted while lock on → `updateWalletCount(0)` → unlocked and disabled → Welcome.

---

## 12. Privacy cover ("shield")

### 12.1 Native shield window (PrivacyShield.swift:18-62)
"A separate native window covers presented sheets as well as the root view. This protects app-switcher snapshots/recording,
not an already-taken screenshot." Started at launch. Observes `willResignActive` (inactive = true), `didBecomeActive`
(inactive = false), `UIScreen.capturedDidChangeNotification`. `update()`: if neither inactive nor captured → hide and drop
all windows; else create one window per connected window scene at level `.alert + 2` (above the lock window) hosting
`PrivacyShieldView(captured:)`, or update existing ones. Its appearance `style` mirrors Settings › Appearance and updates live,
even while visible ("otherwise the app-switcher card can show the other appearance than the app", PrivacyShieldTests.swift:6-39).

### 12.2 PrivacyShieldView (PrivacyShield.swift:68-84)
"Identical to the launch screen so app-switcher snapshots never show wallet content. While the screen is recorded or
mirrored it says why, so it is not taken for a launch that never finished." = `LaunchView()` plus, only when `captured`, a
bottom overlay `Label("Hidden while your screen is recorded or mirrored", systemImage: "eye.slash")`, footnote, secondary,
centered, horizontal padding 30, bottom padding 24.

### 12.3 In-tree cover (RootView.swift:31-33)
The same view is also drawn inside the main window's root (`isSceneCaptured || scenePhase != .active`, identity transition —
instant) as a second layer under sheets.

### 12.4 Capture consequences
While captured: everything is covered (the app is unusable until recording/mirroring stops), `store.suspend()` ran once when
capture began, recovery content cannot be revealed, prefetch is off. Security.md:133: "Screen recording or mirroring can
trigger a lock and privacy cover. A background cover reduces exposure in the application switcher. These controls do not
prevent every screenshot, external camera, accessibility capture, or observation before the screen is covered."

---

## 13. Recovery privacy state machines (consumed by backup/setup specs)

### 13.1 RecoveryPrivacySession — "Display permission only: this object never owns a mnemonic or passphrase." (RecoveryPrivacySession.swift:4-83)
Used by every concealed full-phrase display: backup words grid, recovery-phrase QR, recovery cards (RecoveryBackupViews.swift:30-31,180;
RecoveryPhraseViews.swift:10).
- State: `isRevealed = false`, `prompt: Prompt? (.screenshot)`, `mounted = false`; `canReveal()` default =
  `applicationState == .active && !UIScreen.main.isCaptured`.
- `start()`: `stop()` first (a re-mount resets any reveal and observers), `mounted = true`, observe: screenshot → `hide(); prompt = .screenshot`;
  `willResignActive` → `hide()`; `didEnterBackground` → `hide()`; `capturedDidChange` → `hide()`. Events are ignored when not mounted.
- `stop()`: `mounted = false`, remove observers, `hide()`, `prompt = nil`.
- `reveal() -> Bool`: only if `mounted && canReveal() && prompt == nil` → `isRevealed = true`. "Tapping "Tap to Reveal" is the
  consent: the content shows at once, without a second confirmation."
- `hide()`; `acknowledgeWarning()` (the alert's OK): `hide(); prompt = nil` — never reveals by itself.
- View modifier (RecoveryPhraseViews.swift:316-345): `start()` on appear, `stop()` on disappear, `onReveal` callback when
  revealed, alert title `"Screenshot Detected"` (a Swift `String` constant → not localized on iOS, RecoveryPhraseViews.swift:348),
  message `"The phrase is hidden again, but the screenshot may already contain it. Delete it from Photos, Recently Deleted and any synced copies, and keep a written backup offline."`,
  button `"OK"` → `acknowledgeWarning()`.
- Concealed content is absent from the view tree and accessibility tree, not blurred (Security.md:119).
- A panel covered by its own sheet stops its session while covered and restarts it after (RecoveryBackupViews.swift:72-79);
  only the visible sheet raises the warning; one acknowledgement suffices.
- Tests (RecoveryPrivacyTests.swift): starts hidden; a tap reveals without prompt; screenshot conceals and blocks reveal until
  acknowledged (hiding alone does not acknowledge); screenshot while hidden still warns; resign-active/background/capture
  conceal without a prompt and a new tap reveals again; unsafe environment refuses; paused-under-sheet; unmounted sessions
  cannot reveal or warn; re-mount resets and does not accumulate observers.

### 13.2 RecoveryScreenshotSession — "Screenshot notification and warning state only." (RecoveryScreenshotSession.swift:4-83)
Used on directly visible recovery material: the import phrase editor (RecoveryPhraseViews.swift:208) and the backup
verification step (WalletSetupFlow.swift:342, callback resets placements and the error).
- State: `warning: Warning?` (each with a unique id), `mounted`, `active`, `onScreenshot` callback; `isApplicationActive()` default
  = `applicationState == .active`.
- `start(onScreenshot:)`: `stop()`; `mounted = true`; `active = isApplicationActive()`; store callback; observe screenshot →
  `receiveScreenshot()`, resign-active/background → `deactivate()` (`active = false`, dismiss warning), became-active →
  `active = isApplicationActive()`.
- `receiveScreenshot()`: call `onScreenshot()` **always first**; then, only if still `mounted && active && isApplicationActive()`,
  `warning = Warning()` (a new id, replacing any current one). "The callback may itself unmount the view. Never show a late
  warning after that, or re-present one while the app is inactive/backgrounded."
- `acknowledge(presented)`: only if `warning?.id == presented.id` ("A button from a replaced alert must not acknowledge a later screenshot.").
- `stop()`: unmount, inactive, remove observers, drop callback, dismiss warning.
- Modifier alert (RecoveryPhraseViews.swift:354-380): title `"Screenshot Detected"`, message `"The screenshot may contain your recovery phrase or some of its words. Delete it from Photos, Recently Deleted and any synced copies, and keep a written backup offline."`,
  `"OK"` → `acknowledge(warning)`. Content visibility is not changed by this session.
- Tests (RecoveryScreenshotTests.swift): repeated screenshots call back each time and need the current acknowledgement;
  lifecycle dismisses and defers alerts until active; current app state prevents background presentation; stop/remount
  keeps no warnings/callbacks/observers; unmounting inside the callback publishes no late warning.

Android wording: "Photos, Recently Deleted" are iOS app names — new Android strings needed (e.g. "Delete it from your
gallery, its trash or bin, and any synced copies such as Google Photos, and keep a written backup offline.").

---

## 14. Strings in scope (verbatim English keys)

Status: **L** = present in `translations/*.json` (localized on iOS); **V** = shown verbatim/raw English on iOS (missing from
the translations — add Android translations); **R** = iOS-specific wording to be replaced on Android (new key).

| String | Where | Status |
|---|---|---|
| `Unable to Continue`, `OK` | global alert | L |
| `BitTool Signer`, `Air-gapped signing. For everyone.` | LaunchView, Welcome, shield | L |
| `Wallets Unavailable`, `Your encrypted wallet files are unchanged. Unlock this device, then try again.`, `Try Again` | LoadFailureView | L |
| `Wallets`, `Scan`, `Activity`, `Settings` | tabs | L |
| `Create New Wallet`, `Import Existing Wallet`, `Terms of Use`, `·`, `Privacy Policy`, `Done` | Welcome / legal sheet | L |
| `Keys stay on this device`, `Built to work entirely offline. No accounts, no cloud.`, `Fully open source`, `Every line that touches your keys is published. Read it, or rebuild it and compare.`, `Know what you sign`, `Scan a request, see it decoded and matched to your wallet, then sign it here.`, `Bitcoin, Ethereum, BNB Chain, Arbitrum, Base, TRON and Solana` | Welcome | L |
| `This document is missing from the app bundle.` | legal view | V |
| Onboarding titles, bodies and points (§6.2) | onboarding | L (page 3 body and the "If iOS asks…" point: R) |
| `Page %lld of %lld` (zh `第 %1$lld / %2$lld 页`), `Continue`, `Get Started`, `Cancel`, `Back` | onboarding | L |
| `Discard this wallet?`, `Discard`, `Keep Editing`, `The recovery phrase shown here will be erased. If you already wrote it down, it will not be used.`, `The words you entered will be erased from this screen.` | setup container | L |
| `BitTool Signer Is Locked`, `Unlock to view your wallets and activity.`, `Unlocking the app never authorizes a signature.`, `Use a Wallet Password`, `Enter the password of any wallet on this device.`, `Wallet`, `Choose a wallet`, `Wallet password`, `Unlock` | lock screen | L |
| `Unlock with %@` | lock screen primary button | V + R |
| `Use %@ Instead` | lock screen | L (R: modality name) |
| `Authentication failed. Try again or use a wallet password.` | biometric failure | L |
| `Biometric authentication is unavailable. Check Face ID or Touch ID in iOS Settings, or use a wallet password.` | biometric unavailable | L + R |
| `Biometrics are locked after too many attempts. Use a wallet password, or unlock your iPhone with its passcode first.` | lockout | L + R |
| `Authenticate app access before changing App Lock.`, `Create a wallet before enabling App Lock.`, `App authentication is unavailable right now.` | AppAccessController | L |
| `Unlock BitTool Signer. Signing still requires the wallet password.` | biometric reason (unlock) | V |
| `Lock BitTool Signer with %@.` | biometric reason (enable) | V + R |
| `Confirm before deleting this wallet.` | biometric reason (delete) | L |
| `The password is incorrect.`, `Too many unsuccessful attempts. Try again in %lld seconds.` | vault errors shown on lock | L |
| `Hidden while your screen is recorded or mirrored` | shield | L |
| `Screenshot Detected` | recovery alerts | V |
| the two screenshot alert messages (§13) | recovery alerts | L + R ("Photos, Recently Deleted") |
| `%@ Lock`, `Security`, the two Security footers (§11.11) | Settings | L + R |
| `Matching interrupted. Try again.` | store.suspend | V |
| `The local activity record could not be read.`, `The local activity record could not be saved. Any completed signature remains available.`, `The encrypted wallet was deleted, but its public cache could not be removed.` | store.error | V |
| `Add Wallet`, `Import Recovery Phrase` (and `Scan`, `Create New Wallet`) | Wallets toolbar → router | L |
| NSCameraUsageDescription text (§2.3) | camera rationale | L (in `infoPlist` section) |

Format specifiers: keep `%@` for string interpolation and `%lld` for integers as the keys; Android resources use
`%1$s` / `%1$d` (positional when more than one, as in `Page %lld of %lld`).

Proposed Android-only replacements (English source; need the 20 translations through the same translations pipeline):
`App Lock` (toggle title, replacing `%@ Lock`); `Unlock with Biometrics` (or the system label, §15.6);
`Use Biometrics Instead`; `Require biometric unlock to open BitTool Signer at launch and after 2 minutes in the background. Every signature still needs the wallet’s password.`;
`Set up fingerprint or face unlock in Android Settings to lock BitTool Signer. Every signature always needs the wallet’s password.`;
`Biometric authentication is unavailable. Check fingerprint or face unlock in Android Settings, or use a wallet password.`;
`Biometrics are locked after too many attempts. Use a wallet password, or unlock your phone with its screen lock first.`;
prompt titles `Unlock BitTool Signer` / `Lock BitTool Signer` with description `Signing still requires the wallet password.`;
`For the strongest isolation, use a dedicated phone and keep its connections off.`; the replacement for the "If iOS asks…"
point; Android versions of the two screenshot messages; delete footer `Deleting asks for biometric confirmation, then for this wallet’s password.`

---

## 15. Android mapping

Target: minSdk 26, compile/target 36 (scaffold `build-logic/.../ProjectExtensions.kt`), single `MainActivity : AppCompatActivity`
(a `FragmentActivity`, required by `BiometricPrompt`), Compose + Material 3, Hilt. Flavors `apk` / `play`
(`app/build.gradle.kts`): **nothing in this spec differs between flavors** — both have no INTERNET permission, the same lock,
privacy and routing; only distribution-specific About content differs (settings spec).

### 15.1 Module placement
| Piece | Module | Notes |
|---|---|---|
| `MainActivity`, `SignerApplication`, root composable (stage machine, setup host, global alert, lock/cover layers), splash gate, DI wiring | `:app` | |
| `AppAccessController` (App Lock state machine), `BiometricAuthenticator` interface + `BiometricPrompt` impl, `MonotonicClock`, `AppLifecycleMonitor` (resign/background/foreground/active events), `CaptureMonitor`, `ScreenshotEvents`, `RecoveryPrivacySession`, `RecoveryScreenshotSession`, `LockWindow` + `WindowGate`, secure-window helpers | `:core:security` | Pure Kotlin state machines with injected clock/events so the iOS tests port 1:1 to JVM tests |
| `AppRouter`, `Tab`, `@Serializable` `WalletRoute` (UUID as String), `SetupIntent`, `SetupPresentation` | `:core:navigation` | |
| `AppStore` equivalents (`suspend()`, `securityEpoch`, `navigationResetEpoch`, connect prefetch), preferences | `:core:data` | |
| `LaunchView`, `BrandTile`, `PrivacyCover`, `ActionLabel`, bottom action bar, `Callout`, `SymbolHero`, `NetworkLogoStack` | `:core:designsystem` | LaunchView is used by `:app` (cover) and the lock window |
| `WelcomeScreen`, `OnboardingPager` | `:feature:onboarding` | Legal document view lives in `:core:ui` (shared with `:feature:settings`) |
| Tab roots | `:feature:wallets`, `:feature:sign`, `:feature:activity`, `:feature:settings` | |

### 15.2 Preferences
- `appearance`, `onboardingVersionCompleted`, `appLockEnabled.v1`: DataStore Preferences with the same key names and values.
  The first read is asynchronous, so model the App Lock state as **RESOLVING = locked** until the preference is read (Ownbit
  `ownbit:feature/security/src/main/kotlin/io/ownbit/feature/security/lock/AppLockController.kt` `LockTiming.status`, fail closed)
  and keep the splash on screen until resolved. Never persist an "unlocked" flag.
- `language`: do not duplicate — the source of truth is `AppCompatDelegate.getApplicationLocales()` (per-app locales; the
  scaffold manifest already declares `AppLocalesMetadataHolderService` with `autoStoreLocales`). Map `AppLanguage` raw values to
  BCP-47 tags (`zh-Hans`, `zh-Hant`, `es-419`, `pt-BR`, `pt-PT`, `fil`, `id`…); `system` = empty locale list.
- Appearance: apply with `AppCompatDelegate.setDefaultNightMode(...)` in `Application.onCreate` (before any activity) and on
  change; on API 31+ also `UiModeManager.setApplicationNightMode(...)` so the **system splash** follows the in-app setting
  (iOS cannot; this is an allowed improvement). Pass the same mode to the Compose theme; the lock window and cover use it too.

### 15.3 Launch / splash
- iOS launch background is `#F2F2F7` light / `#000000` dark (= page background). The scaffold's `app/src/main/res/values/colors.xml`
  sets `launch_background = #0A0A0C` for both — that is the **tile** color, not the launch background; with it the light-mode
  welcome would flash dark → light. Use `values/colors.xml #F2F2F7` and `values-night/colors.xml #000000`.
- Recommended: `installSplashScreen()` with the brand tile as icon on `launch_background`, `setKeepOnScreenCondition { store
  is loading || lock state resolving }` capped (~1.5 s, Ownbit `LaunchScreenGate.MAX_FIRST_SCREEN_WAIT_MILLIS`), then the first
  frame is directly Welcome / tabs / lock. If the cap is hit, show `LaunchView` (exact copy of §3.2) until loading ends, then
  cross-fade 350 ms (iOS `.smooth(duration: 0.35)`; Compose `tween(350, easing = FastOutSlowInEasing)` or a spring with no bounce).
- Stage changes welcome ↔ main: `AnimatedContent`/`Crossfade` 350 ms.

### 15.4 Lifecycle → iOS phase mapping (one `AppLifecycleMonitor` fed by `MainActivity`)
| iOS | Android source | Notes |
|---|---|---|
| `willEnterForeground` | `Activity.onStart` | Run `access.willEnterForeground()` here, synchronously, so the relock is in the first frame (collect the lock flow with `collectAsState()`, not lifecycle-gated — Ownbit `ownbit:app/src/main/kotlin/io/ownbit/app/ui/OwnbitAppRoot.kt` comment) |
| `didBecomeActive` | `onResume` | |
| `willResignActive` | `onPause` | conceal recovery displays, hide revealed passphrase, show in-app cover, stop camera preview/torch |
| `didEnterBackground` | `onStop` **when `!isChangingConfigurations`** | `store.suspend()` then `access.didEnterBackground()`, immediately |

- Do not use `ProcessLifecycleOwner` for the background edge: its `ON_STOP` is delayed ~700 ms, while iOS revokes immediately.
  (It is fine for `ON_START`, which is not delayed; if more activities are ever added, use PLO for both and accept the delay.)
- `isChangingConfigurations` (e.g. per-app locale change recreates the activity) must not count as background — otherwise a
  language switch would clear passwords and start the lock timer. The scaffold manifest already handles
  `orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|uiMode|density` without recreation.
- Known difference: launching the system file picker (`ACTION_OPEN_DOCUMENT`, Scan › Import File) stops our activity, so it
  counts as background (suspend + grace timer starts); on iOS the document picker keeps the app active. Keep it fail-safe
  (count it); 120 s is long enough for a normal pick. Same for the camera-permission "Open Settings" exit (also background on iOS).
- Back at the Wallets root (Android 12+) moves the task to the back → `onStop` → background, like Home. Below 12 the activity
  finishes but singletons (lock controller with its timestamp) survive in the process — same result. Process death = cold
  launch = locked (if enabled), exactly as iOS.

### 15.5 Monotonic clock and grace rule
`SystemClock.elapsedRealtime()` (counts deep sleep, immune to wall-clock changes) — never `uptimeMillis()` (pauses in deep
sleep: the iOS F8 bug) or `currentTimeMillis()`. Rule: stay unlocked iff `since != null && now >= since && now - since < 120_000`;
otherwise lock. Ownbit's `LockTiming` uses 60 s and `>`; do **not** copy those values.

### 15.6 Biometrics (`androidx.biometric.BiometricPrompt`)
- Authenticators: `BIOMETRIC_STRONG` (Class 3), **without** `DEVICE_CREDENTIAL`. Rationale: iOS deliberately has no passcode
  fallback ("the only alternative is a wallet password"); `DEVICE_CREDENTIAL` forbids a negative button (needed for "Use a
  Wallet Password"), and on API 29 and below it launches the separate ConfirmDeviceCredential activity, which stops our
  activity → counts as background → cancels authentication → the exact loop iOS guards against. (The orchestrator's hint
  "BiometricPrompt with device credential" contradicts iOS; adopting it would be a product decision, not a port.) No
  `CryptoObject` is needed: App Lock gates UI only. `BIOMETRIC_WEAK` would widen face-unlock coverage; decide explicitly.
- Availability = `BiometricManager.canAuthenticate(BIOMETRIC_STRONG) == BIOMETRIC_SUCCESS`; re-check on every `onStart`
  (iOS refreshes `biometry` on foreground). Modality wording: Android does not reliably report face vs fingerprint. Use the
  generic strings in §14, or on API 31+ the system-localized `BiometricManager.getStrings(...).getButtonLabel()/getSettingName()`
  (androidx.biometric 1.2.0-alpha05 or later; the scaffold pins 1.1.0, which lacks it). Icon: Material `Fingerprint`.
- Prompts: unlock — title `Unlock BitTool Signer`, description `Signing still requires the wallet password.`, negative
  button `Use a Wallet Password` (→ switch the lock screen to the password form); enable — title `Lock BitTool Signer`,
  negative `Cancel`; delete — title `Confirm before deleting this wallet.`, negative `Cancel`. `setConfirmationRequired(false)`.
- Error mapping (callback `onAuthenticationError(code)`): `ERROR_USER_CANCELED`, `ERROR_NEGATIVE_BUTTON`, `ERROR_CANCELED`
  (system cancel, e.g. app backgrounded), `ERROR_TIMEOUT` → cancellation (silent); `ERROR_LOCKOUT`, `ERROR_LOCKOUT_PERMANENT` →
  lockout message; `ERROR_HW_UNAVAILABLE`, `ERROR_NO_BIOMETRICS`, `ERROR_HW_NOT_PRESENT`, `ERROR_SECURITY_UPDATE_REQUIRED` →
  unavailable message; anything else → `Authentication failed. Try again or use a wallet password.`
  `onAuthenticationFailed()` (one non-matching attempt) is **not** terminal — the prompt stays; ignore it.
- Wrap it as a suspend function (`suspendCancellableCoroutine`, `cancelAuthentication()` on cancellation) behind an interface
  so `AppAccessController` keeps the iOS epoch logic and the tests' blocking fake.
- Show the prompt only when the activity is RESUMED (`LifecycleResumeEffect` keyed by the lock instance); auto-prompt once per
  lock engagement; on `onStop` call `cancelAuthentication()` (iOS `biometric.cancel()`).

### 15.7 Lock layer
- Requirement: the lock must cover every open dialog/bottom sheet while preserving it (iOS lock window above all sheets).
  Compose `Dialog`, `ModalBottomSheet`, `AlertDialog` and navigation `dialog` destinations are separate windows **above** the
  activity window, so an overlay drawn in the activity's composition is not enough.
- Recommended (prior art): port Ownbit's `LockWindow` (`ownbit:feature/security/src/main/kotlin/io/ownbit/feature/security/lock/LockWindow.kt`):
  a `TYPE_APPLICATION` window of the activity, MATCH_PARENT, laid out in the whole screen incl. cutout, `FLAG_SECURE`, no window
  animation, keyboard hidden on show, added after any open dialog so it is on top; Back on it moves the task to the back and
  never unlocks. Pair it with Ownbit's `WindowGate` (`ownbit:app/src/main/kotlin/io/ownbit/app/ui/LockWindowGate.kt`): every
  dialog/sheet/toast requested while locked waits and appears after unlock (on iOS such presentations happen beneath the lock
  window; on Android they would otherwise open above it — e.g. the "Unable to Continue" alert, a late async error).
  Alternative: if the design system renders all sheets/dialogs inside the root composition (not as windows), a top-of-tree
  overlay suffices.
- When locking: clear focus in the app window (`LocalFocusManager.clearFocus()`, hides the IME), `store.suspend()`, hide the
  app content from accessibility (`Modifier.clearAndSetSemantics {}` on the NavHost/tab container), keep its state.
- Appearance: lock appears instantly (security). Unlock may fade the copy out over ~300 ms (Ownbit `UNLOCK_SETTLE_MILLIS = 450`
  incl. settle) — an allowed Android polish since content is being revealed to an authenticated user.
- Lock screen in Compose: `verticalScroll` column, max width 440 dp centered, horizontal padding 28 dp, spacing 28 dp, 60 dp top
  spacer; Material 3 `Button` (filled, full width, pill) for primary, `FilledTonalButton`/`OutlinedButton` for secondary; wallet
  picker = `ExposedDropdownMenuBox` (names only); password = M3 `SecureTextField` (`TextFieldState` created with `remember`, not
  `rememberTextFieldState`, §15.11) with `ImeAction.Go`; error = warning `Callout`; preselect the single wallet in both the
  button path and the "biometrics unavailable" path (harmless improvement over the iOS quirk in §11.9). Use the app language.
- Settings toggle: M3 `Switch` with `checked = isEnabled`, disabled per §11.11, inline error text in `error` color below it.

### 15.8 Privacy: recents, capture, screenshots
- **FLAG_SECURE on the activity window, set once in `onCreate` before `setContent`, never toggled** (toggling re-creates the
  surface and blinks on some devices — Ownbit `RecentsGuard.kt` notes). It gives, OS-enforced and on every API level ≥ 26:
  blank Recents thumbnail (the iOS app-switcher cover), black screenshots, black screen recordings and casts (the iOS
  "recorded or mirrored" cover), no display on non-secure external/virtual displays. Compose dialogs/sheets/popups inherit it
  with the default `SecureFlagPolicy.Inherit`; never use platform `Toast`/`PopupWindow` for anything sensitive. Provide a
  **debug-build-only** switch to disable it for screenshot tours. This is stricter than iOS (iOS allows screenshots and only
  reacts); Security.md:133 states iOS lacks such an API, so this matches the design intent — confirm with product.
- API 33+: also `setRecentsScreenshotEnabled(false)` always (iOS covers the app switcher whether or not App Lock is on). The
  Recents card then shows a plain page in the theme's background color (not the branded LaunchView — not achievable on Android).
- In-app cover (`PrivacyCover` = LaunchView + optional caption) drawn at the top of the root composition and in the lock
  window when `lifecycle < RESUMED` (iOS inactive) or `captured`. Snapshot protection relies on FLAG_SECURE, not on this cover
  (drawing in `onPause` often lands after the thumbnail is taken — Ownbit measurement). Caveat: on API 26–28 split-screen, an
  unfocused app is paused, so the cover shows there (iPad Split View keeps both apps active).
- `CaptureMonitor.isCaptured`: API 35+ `WindowManager.addScreenRecordingCallback(executor, consumer)` (permission
  `android.permission.DETECT_SCREEN_RECORDING`, normal), `SCREEN_RECORDING_STATE_VISIBLE` → captured; register in `onStart`,
  remove in `onStop`. Below 35 there is no API: `isCaptured = false` and FLAG_SECURE carries the protection. When it turns true:
  `store.suspend()`, conceal recovery displays, refuse reveal, show the cover with `Hidden while your screen is recorded or mirrored`,
  close the window gate, disable prefetch.
- `ScreenshotEvents` (iOS `userDidTakeScreenshotNotification`): API 34+ `Activity.registerScreenCaptureCallback(mainExecutor, cb)`
  in `onStart`, `unregisterScreenCaptureCallback` in `onStop` (permission `android.permission.DETECT_SCREEN_CAPTURE`, normal).
  It fires after the fact, only for user-initiated captures. With FLAG_SECURE the capture is blocked anyway; do not rely on the
  callback firing under FLAG_SECURE — keep the session state machines wired to it as a fallback (debug builds without
  FLAG_SECURE, OEM behaviour).
- Mapping for the recovery sessions (§13): screenshot → `ScreenshotEvents`; `willResignActive` → `ON_PAUSE`; `didEnterBackground`
  → background edge; `capturedDidChange` → `CaptureMonitor` change; `didBecomeActive` → `ON_RESUME`; `canReveal()` =
  `lifecycle.isAtLeast(RESUMED) && !captured`; `isApplicationActive()` = RESUMED. Represent them as plain classes fed by a
  `SharedFlow<PrivacyEvent>`; `start()`/`stop()` from `DisposableEffect` (enter/leave composition) — keep the "re-start resets
  reveal" and "ignore events when unmounted" rules; concealed words must not be composed at all.

### 15.9 IME / autofill (the iOS keyboard-extension block has no Android equivalent)
- Cannot block third-party IMEs. On every secret or phrase field set `EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING`
  (Compose: `InterceptPlatformTextInput` wrapping the request's `createInputConnection` to OR the flag into `imeOptions`),
  `autoCorrectEnabled = false`, `KeyboardCapitalization.None`, password key types for passwords. For the visible phrase
  editor keep `KeyboardType.Text` (CJK IMEs must work) plus the no-learning flag and no suggestions.
- Autofill: exclude the app (`window.decorView.importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS`) so password
  managers never capture wallet passwords or passphrases (iOS keeps AutoFill away from new secrets; an app-wide exclusion is
  the conservative Android equivalent — note iOS allows AutoFill on the existing-password field).

### 15.10 Tabs and routing
- Tab container: M3 `NavigationBar` (compact) / `NavigationRail` (medium/expanded width; iPad parity) — `NavigationSuiteScaffold`
  fits. Items in fixed order with icons `AccountBalanceWallet`, `QrCodeScanner`, `History`, `Settings`; Scan item in `BadgedBox`
  with `Badge { Text("1") }` when a review is pending and unsigned (content description should say a request is waiting).
  No hide-on-scroll. Tab switch instant (no fade-through; Ownbit found it flashes an empty page). Reselect: pop that tab's stack
  to root, then scroll to top. System Back on a non-Wallets tab root → Wallets tab; on Wallets root → leave app.
- `AppRouter` as an activity-retained object (Hilt `@ActivityRetainedScoped` or a ViewModel): `tab: StateFlow<Tab>`,
  `walletBackStack`, `scanRequest` token + non-observable handled counter, `setup: StateFlow<SetupPresentation?>`,
  `routeAfterSetup`. With **Navigation 3** the Wallets back stack is a `SnapshotStateList<WalletRoute>` owned by the router —
  a 1:1 port of `walletPath` (`open(route)` = clear + add; `resetNavigation()` = clear). With navigation-compose 2.x (scaffold
  pins 2.10.2): one `NavHost` per tab, `open(route)` = select Wallets + `navigate(route) { popUpTo(WalletsRoot) }`,
  `resetNavigation()` = `popBackStack(WalletsRoot, inclusive = false)`.
- Selected tab may be saveable (public); restoring wallet routes after process death is harmless, but each destination must
  handle a wallet ID that no longer exists (iOS never restores).
- Review arrival: collect `review?.requestHash` distinct; non-null → select Scan. Wallet count 0 → Wallets + reset. Wallet
  deletion epoch → reset.
- Setup host: render the setup flow as a root-level full-screen layer **above** the stage switch (not a destination inside the
  tab graph), keyed by `SetupPresentation.id` (new state holder per presentation), slide-up enter / slide-down exit.
  `finishSetup(route)` → hide → open the route **after** the exit animation completes (e.g. `AnimatedVisibility` +
  `LaunchedEffect(transition.isRunning)`), mirroring `setupDidDismiss`. It must not survive process death (iOS: termination
  discards the draft) — do not put it in the saved back stack. Back handling: onboarding page > 0 → previous page; page 0 →
  Cancel; setup steps → pop; first step → Cancel (discard confirmation when drafts exist); disabled while busy; on the done
  step Back = Done.
- Onboarding pager: `HorizontalPager` (3 pages, user swipe enabled), custom indicator (active 22×8 dp pill in accent, inactive
  8×8 dp in a `systemFill`-like color, 8 dp gap, animated width), single semantics node "Page %1$d of %2$d"; top app bar with
  `Cancel` (start) and `Back` (end, only page > 0); bottom bar with Continue/Get Started; compact layout when height < 560 dp.
  Respect `Settings.Global.ANIMATOR_DURATION_SCALE == 0` / reduce-motion for the welcome tile spring and hero bounce.
- Icon map (SF → Material Symbols): `lock.iphone`→`PhonelinkLock`, `chevron.left.forwardslash.chevron.right`→`Code`,
  `checkmark.shield`→`VerifiedUser`, `plus`→`Add`, `square.and.arrow.down`→`Download`, `key.fill`→`Key`, `wifi.slash`→`WifiOff`,
  `hand.raised.fill`→`BackHand`, `arrow.left.arrow.right`→`SwapHoriz`, `square.stack.3d.up.fill`→`Layers`,
  `text.magnifyingglass`→`ManageSearch`, `airplane`→`AirplanemodeActive`, `antenna.radiowaves.left.and.right.slash`→`PublicOff`,
  `eye.slash(.fill)`→`VisibilityOff`, `faceid/touchid`→`Fingerprint`, `key`→`Key`, `lock.open`→`LockOpen`,
  `externaldrive.badge.exclamationmark`→`FolderOff`, `arrow.clockwise`→`Refresh`.

### 15.11 Secrets and saved state (pitfall)
Nothing secret may go into `rememberSaveable`, `SavedStateHandle`, navigation arguments, or `rememberTextFieldState()`
(which is saveable): the Bundle leaves the process (held by system_server) and survives process death, whereas iOS keeps
drafts in memory only and discards them on termination. Use `remember { TextFieldState() }` / ViewModel memory, and clear on
`securityEpoch`. Every `securityEpoch` consumer in §4.6 must be ported (e.g. as a `StateFlow<Long>` epoch counter collected
with `LaunchedEffect(epoch)`).

### 15.12 Colors and type for shell surfaces (iOS system values)
Page background `#F2F2F7` / `#000000`; card `#FFFFFF` / `#1C1C1E`; raised `#F2F2F7` / `#2C2C2E`; systemFill `#787880` @20% /
@36%; secondary label `#3C3C43` @60% / `#EBEBF5` @60%; tertiary label @30%; accent `#0F7B55` / `#6BE3A4`; on-accent label
white / `#0B1F16`; warning `#FF9500` / `#FF9F0A`; danger `#FF3B30` / `#FF453A`; success `#34C759` / `#30D158`. iOS sizes used
here: largeTitle 34, title2 22, title3 20, headline 17 semibold, body 17, subheadline 15, footnote 13 (map to the design
system's type scale; honour font scale). Edge-to-edge (mandatory at target 35+): bottom bars pad for navigation-bar insets.

### 15.13 Accessibility and test tags
Map identifiers to `Modifier.testTag(...)` with `semantics { testTagsAsResourceId = true }` at the root: `welcome.create`,
`welcome.import`, `welcome.terms`, `welcome.privacy`, `legal.done`, `onboarding.next`, `appLock.biometric`, `appLock.usePassword`,
`appLock.wallet`, `appLock.unlock`, `settings.appLock`, `wallets.scan`, `wallets.add`. Merge feature rows and LaunchView into
single TalkBack nodes; hide decorative tiles/heroes/gradients; network stack = one node with the short-name list; hide app
content while locked; the lock window is the only traversable surface while locked.

### 15.14 Behaviour checklist / tests to port
- `AppAccessTests` (all 8, AppAccessTests.swift:7-151) with a fake clock (start 100) and a fake biometric that can fail, block
  until completed, and count calls; `ScanRequestRouterTests` (4); `RecoveryPrivacyTests` (8); `RecoveryScreenshotTests` (5);
  `PrivacyShieldTests` analogue: cover/lock follow the in-app appearance and update live.
- UI flows (BitToolSignerUITests.swift): onboarding once + import intent preserved (:23-57); lock cold launch → password
  fallback → wrong password error → correct password → short background keeps page (:774-803); deleting the last wallet
  returns to Welcome (:720-772).
- Decide whether to show both the LoadFailure page and the "Unable to Continue" alert on a failed load (iOS does both).

---

## 16. Engine calls in this scope
- `createWalletPairing` — `{"publicIndex": <object>, "mode": "full"}` — via Connect prefetch only (§4.5); consumed as
  `WalletPairingPayload` (frames rendered to QR images, kept in memory).
- `releaseWarmContext` — not an engine operation: the host drops the reused public VM on every `suspend()`; the next warm
  operation creates a fresh one.
- App Lock password unlock does **not** call the engine (vault decrypt only; the secret is discarded).
No other engine call is made by the shell, welcome, onboarding, lock or privacy code.

---

## 17. Out of scope (iOS-only) — drop
- Face ID / Touch ID / Optic ID naming and SF symbols (`BiometryKind` names, `faceid`/`touchid`/`opticid`), `LAContext`
  `localizedFallbackTitle`, `NSFaceIDUsageDescription`, the iOS reason-string wording.
- `SimulatorUITestContext` entirely (DEBUG + simulator only): launch arguments `--bittool-signer-ui-tests`,
  `--reset-ui-fixtures`, `--app-lock-enabled`, `--dark-appearance`, `--light-appearance`; env `BITTOOL_SIGNER_UI_TEST_RUN`,
  `BITTOOL_SIGNER_UI_FIXTURE_COUNT` (≤ 8 "Public fixture N" wallets, password `Public-Test-Password1!`); fixture device keys;
  the fixture biometric that always throws `"Face ID was cancelled. Use a wallet password to continue."`
  (Services/SimulatorUITestContext.swift:1-63). Android uses Hilt test modules instead.
- Liquid Glass (`.glassProminent`, `.glass`, `safeAreaBar`) and iOS 17/18 fallbacks (`wallet.bifold` vs `wallet.pass`,
  `writingToolsBehavior`), `UIUserInterfaceStyle` mapping, `overrideUserInterfaceStyle` per window.
- UIKit window machinery: window levels `.alert+1/+2`, per-`UIWindowScene` shield windows (iPad multi-window), the window
  probe, `accessibilityViewIsModal`, `makeKey` restoration, the alert-binding-in-State workaround, `privacySensitive()`.
- `UIApplication.shouldAllowExtensionPointIdentifier` keyboard block (replaced by §15.9 mitigations).
- `CLOCK_MONOTONIC_RAW`, `UIScreen.isCaptured` / `capturedDidChangeNotification`, `userDidTakeScreenshotNotification`
  (replaced by §15.5 / §15.8 APIs).
- LaunchScreen.storyboard specifics, `UIApplicationSceneManifest`, `UIApplicationSupportsIndirectInputEvents`,
  `UIStatusBarStyle`, `ITSAppUsesNonExemptEncryption`, App Store / TestFlight, `PrivacyInfo.xcprivacy`.
- The `.id(language)` view-tree rebuild that sends the user back to the Wallets tab after a language change — on Android the
  per-app-locale recreation restores the current tab (allowed Android behaviour).
- Onboarding `readOnly` mode (no caller). The "If iOS asks to allow wireless data…" onboarding point. iOS wording "iPhone",
  "iOS Settings", "Photos, Recently Deleted" (replace, §14).
