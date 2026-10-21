# BitTool Signer Android — Visual design system and shared UI components

Port spec, area: **design system** (tokens, brand, shared components, motion, haptics, icons, launch).
Source of truth: the iOS app at `/Users/tanguopeng/Documents/bitsigner-ios/BitSigner`. Unless stated otherwise,
Swift citations are relative to `BitSigner/BitToolSigner/` (e.g. `Design/DesignSystem.swift:42`).
Android idioms are taken from Ownbit 5.0 (`/Users/tanguopeng/AndroidStudioProjects/ownbitandroidnew`,
`core/designsystem`, `core/ui/qr`, `core/publicsigner/ui`).

Conventions used below
- "pt" (iOS) maps 1:1 to "dp", text "pt" maps 1:1 to "sp".
- Quoted strings are the exact English source strings (= localisation keys in
  `translations/*.json` → `strings`). `%@` / `%lld` are kept as Swift writes them; translations use positional
  forms (`%1$@`, `%2$lld`) — on Android these become `%1$s` / `%1$d`.
- "verbatim" = the text is not localised on iOS (a `String`, not a `LocalizedStringKey`).
- No component in this area calls the signing engine. The only engine-derived content they display is passed in
  verbatim by callers (`Callout(verbatim:)`, `PayloadDisclosure(verbatim:)`, `StatusPill(verbatim:)`).

---

## 0. Status of the design references (read first)

| Reference | What it is | Use it for |
|---|---|---|
| `Design/*.swift`, `Assets.xcassets`, `Resources/LaunchScreen.storyboard` | **Current, shipping design.** | Source of truth. |
| `design/Brand.md`, `design/Brand/*.svg`, `scripts/make-brand-assets.py` | Current brand construction. | Mark / tile / icon geometry. |
| `design/BitSigner-*.png`, `design/Recovery-*.png`, `design/Backup-Verification-Dark.png`, `design/Restore-Input-Light.png`, `design/Background-Resume-Step2.png` | **Pre-rename screenshots ("BitSigner")**, kept "for reference only" (`design/Brand.md` last line). | Mood only. |
| `design/Revision-2026-09-21/*.png` | Acceptance screenshots of 21 Sep 2026 — **also pre-rename** (title "BitSigner", old "B" mark, iOS 18.6 sim). | Mood only. |

What the old screenshots show (do **not** copy these specifics; the code changed since):
- Old mark: a mint lightning "B" (`design/BitSigner-Icon-Original.png`, glowing raster) — replaced by the ring + node mark.
- Light page warm off-white, white cards with very large radii and a faint hairline, dark page near-black with
  `#1A1D1C`-ish cards and hairlines.
- Primary CTA was a **pale mint `#B3F0C7` capsule with ink label** in light mode ("Create Wallet +",
  "Validate recovery phrase →", "Check my backup →"); disabled = same mint at ~40%. Current code: solid
  accent (emerald `#0F7B55` light / mint `#6BE3A4` dark) with white / ink label (§5.1).
- Uppercase, letter-spaced monospaced eyebrows ("YOUR KEYS. YOUR CONTROL.", "STEP 2 / 4", "OFFLINE BY DESIGN"),
  big sentence-style headlines ("Your wallets.", "Sign with confidence."), "01 Scan — 02 Review — 03 Return"
  steppers, BTC/EVM/TRX/SOL text chips, 3-tab bar (Wallets/Scan/Activity), "Connect" wording. **None of this is in
  the current code** (current: 4 tabs incl. Settings, "Pair", native large titles, network logos).
- Still valid: monospaced full addresses wrapping inside cards (Addresses-Dark / Solana-Addresses), white QR plate
  with rounded corners on both themes, tinted callouts (orange on orange-tint, `Message-Review-Light.png`), hidden
  recovery phrase card with eye-slash + "Tap to show …" (not in this spec's scope), segmented controls.

---

## 1. Brand

### 1.1 Identity and strings
- Mark: "a ring broken by a solid node — a closed loop with the point where a key is applied". BitTool Wallet ships
  the light version (ink on white), **BitTool Signer ships the dark one: white mark on ink `#0A0A0C`**
  (`design/Brand.md`). Nothing in it names a coin.
- App name `"BitTool Signer"` (localisation key exists; translates to itself). Brand line
  `"Air-gapped signing. For everyone."` (zh-Hans: 离线签名，人人可用。). Publisher `BITTOOL PTE. LTD.`
  (`Features/Settings/SettingsTab.swift:119`).

### 1.2 Geometry (1024 grid) — `design/Brand.md`, `scripts/make-brand-assets.py:18-27`
| Element | Value |
|---|---|
| Ring centre | (512, 511.5) — note the 0.5 offset in y |
| Ring outer / counter (inner) radius | 372 / 288 → wall 84 (equivalently: a stroke of width 84 on radius 330) |
| Node | circle r = 94.8 at (828.5, 511.5); straddles the wall (39 past the outer edge, 66 into the counter); **united** with the ring |
| Mark extent | x 140 … 923.3, y 139.5 … 883.5 (ring is centred, node protrudes right) |
| Colours | mark `#FFFFFF`, ground `#0A0A0C` |
| Tile corner | superellipse \|u\|^5 + \|v\|^5 ≤ 1 (exponent 5, "iOS continuous corner") — used for `BrandTile.png` |

SVG sources (`design/Brand/`):
- `BitToolSigner-Mark.svg` — mark only (white), for documents. Ring = one `fill-rule="evenodd"` path made of two
  full-circle arc pairs; node = `<circle>`.
- `BitToolSigner-Icon.svg` — 1024 full-bleed `#0A0A0C` square + mark (source of `AppIcon.png`, opaque RGB, no corners).
- `BitToolSigner-Tile.svg` — same with `rx=ry=229.1` (0.2237×1024) **plain rounded rect**. ⚠ Discrepancy: the
  rasterised `BrandTile.png` (512×512 RGBA) uses the **superellipse n=5**, not this rx. Android must match the PNG.

Raster assets: `Assets.xcassets/AppIcon.appiconset/AppIcon.png` (1024×1024, RGB, single universal size),
`Assets.xcassets/BrandTile.imageset/BrandTile.png` (512×512, RGBA, transparent outside the squircle, 4×4
super-sampled coverage, `make-brand-assets.py:40-75`).

### 1.3 `BrandTile` component — `Design/Logos.swift:224-233`
`Image("BrandTile")` resizable, high interpolation, scaled to fit, frame `size`×`size` (default 88), shadow
`black @ 16%`, radius `size × 0.1`, y `size × 0.04` when `shadow` (default true), accessibility-hidden.
"Identical artwork is used by the launch screen so the first SwiftUI frame continues the system launch image
without a jump."

### 1.4 Where the brand appears
| Place | Size | Shadow | Surroundings | Source |
|---|---|---|---|---|
| System launch screen | 96 | no | `LaunchBackground` colour, vertical stack spacing 18 centred on the **full screen** (not safe area), side margins 30; title "BitTool Signer" system 22 semibold `labelColor`; slogan system 16 regular `secondaryLabelColor`, multi-line centred | `Resources/LaunchScreen.storyboard` |
| `LaunchView` (store loading) | 96 | no | exact replica of the storyboard: `Color("LaunchBackground")`, `VStack(spacing: 18)`, `.font(.system(size: 22, weight: .semibold))` / `.system(size: 16)` secondary, `.padding(.horizontal, 30)`, `.ignoresSafeArea()`, accessibility combined | `Features/Main/RootView.swift:73-90` |
| Privacy cover (app inactive / switcher snapshot / screen captured) | 96 | no | `LaunchView()`; when captured adds bottom label `Label("Hidden while your screen is recorded or mirrored", systemImage: "eye.slash")` footnote secondary, centred, padding h30 bottom 24 | `Services/PrivacyShield.swift:68-84`, `RootView.swift:31-33` |
| Welcome hero | 96 | yes | appears with `scale 0.9→1` + `opacity 0→1`, `.spring(duration: 0.8)` (none with Reduce Motion); title "BitTool Signer" `.largeTitle.bold`; slogan `.title3` secondary; hero stack spacing 14; page has a top `RadialGradient(accent @16% → clear, center .top, startRadius 10, endRadius 420)` behind everything | `Features/Onboarding/WelcomeView.swift:31-35,59,62-74` |
| App Lock | 84 | yes | title "BitTool Signer Is Locked" `.title2.bold`, subtitle "Unlock to view your wallets and activity." subheadline secondary; stack spacing 14; page spacing 28, top spacer ≥ 60, padding h28 b24, readable width 440 | `Features/Lock/AppLockView.swift:18-41` |
| Settings › About footer | 44 | no | `VStack(spacing: 10) { BrandTile; Text("Air-gapped signing. For everyone.") }` centred, top 24, bottom 28 (footer style text) | `Features/Settings/SettingsTab.swift:89-97` |
| Wallet directory | — | — | BitTool Wallet's own logo `wallet_bittool` (ring mark, **black on white**) | `Models/CompatibleWallets.swift:132` |

---

## 2. Colour tokens

### 2.1 Accent — `Assets.xcassets/AccentColor.colorset` (global tint, `ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME`)
| Token | Light | Dark | Contrast |
|---|---|---|---|
| `accent` (`.accentColor`, `.tint`) | **emerald `#0F7B55`** | **mint `#6BE3A4`** | emerald on white 5.27:1, on `#F2F2F7` 4.72:1; mint on black 13.2:1, on `#1C1C1E` 10.7:1 |
| `onAccent` (`Brand.onAccent`, `Design/DesignSystem.swift:10-11`) | `#FFFFFF` | **ink `#0B1F16`** | white on emerald 5.27:1; ink on mint 10.8:1 |

The accent is the tint of: links, toolbar items, back buttons, toggles, `Label` icons in lists, `.bordered`
buttons, progress views, selection outlines, step/hero glyphs.

### 2.2 Brand constants — `Design/DesignSystem.swift:6-12`  ⚠ three similar "mint/ink" values
| Constant | Value | Used for |
|---|---|---|
| `Brand.mint` = `Color(red: 0.702, green: 0.941, blue: 0.780)` | **`#B3F0C7`** (pale "scanner mint", the old CTA colour) | QR scanner only: viewfinder brackets, progress ring (90%), linear progress, "Open Settings" / "Scan Again" buttons (`Features/Sign/QRScannerView.swift:94,102,107,157,164`) |
| `Brand.ink` = `Color(red: 0.043, green: 0.122, blue: 0.086)` | **`#0B1F16`** | label on `Brand.mint` (scanner buttons) and `onAccent` in dark |
| Tile/icon ink (`make-brand-assets.py:24`) | **`#0A0A0C`** | app icon / brand tile ground only — never a UI colour |
| AccentColor dark | **`#6BE3A4`** | the dark accent (≠ `Brand.mint`) |

### 2.3 Surfaces — `Palette` (`Design/DesignSystem.swift:14-22`) = iOS grouped system colours
iOS "dark elevated" values apply automatically inside sheets/popovers (elevated interface level). Android must
reproduce that (see §9.2).

| Token (Swift) | iOS colour | Light | Dark (base) | Dark (elevated: sheets) |
|---|---|---|---|---|
| `Palette.background` | systemGroupedBackground | `#F2F2F7` | `#000000` | `#1C1C1E` |
| `Palette.card` | secondarySystemGroupedBackground | `#FFFFFF` | `#1C1C1E` | `#2C2C2E` |
| `Palette.raised` | tertiarySystemGroupedBackground | `#F2F2F7` | `#2C2C2E` | `#3A3A3C` |
| `Palette.separator` (unused; `Divider()` uses it implicitly) | separator | `#3C3C43` @29% (≈`#C6C6C8` on white) | `#545458` @60% (≈`#3E3E41` on `#1C1C1E`) | same |
| `LaunchBackground.colorset` | custom | `#F2F2F7` | `#000000` | — |
| `systemGray5` (logo fallbacks) | systemGray5 | `#E5E5EA` | `#2C2C2E` | `#2C2C2E` |
| `.bar` material (pre-iOS-26 bottom bar) | chrome material | translucent light | translucent dark | — |
| `.regularMaterial` (toast), `.ultraThinMaterial` (scanner panels) | materials | translucent | translucent | — |

Labels / fills used implicitly through `.primary/.secondary/.tertiary/.quaternary` and `systemFill`:
| Token | Light | Dark |
|---|---|---|
| label (`.primary`) | `#000000` | `#FFFFFF` |
| secondaryLabel (`.secondary`) | `#3C3C43` @60% (≈`#8A8A8E` on white, 3.4:1) | `#EBEBF5` @60% (≈`#98989F` on `#1C1C1E`) |
| tertiaryLabel (`.tertiary`) | `#3C3C43` @30% | `#EBEBF5` @30% |
| quaternaryLabel (`.quaternary`) | `#3C3C43` @18% | `#EBEBF5` @16% |
| systemFill (strength meter track, onboarding dots) | `#787880` @20% | `#787880` @36% |
| tertiarySystemFill (ChainPicker track, index field) | `#767680` @12% | `#767680` @24% |
| address middle groups | label @78% (`#383838` on white) | label @78% |

### 2.4 Semantic tones — `Palette.success / warning / danger` + `systemYellow`
| Token | iOS | Light | Dark |
|---|---|---|---|
| `success` | systemGreen | `#34C759` | `#30D158` |
| `warning` | systemOrange | `#FF9500` | `#FF9F0A` |
| `danger` | systemRed | `#FF3B30` | `#FF453A` |
| strength level 2 | systemYellow | `#FFCC00` | `#FFD60A` |
(iOS 17/18 values; iOS 26 shifted red/orange slightly — Android pins these.)

Opacity conventions (always over the surface behind):
| Use | Alpha |
|---|---|
| `Callout`, `ContractNotice` card, acknowledgement box, `SymbolHero` disc, scan-card icon tile | **12%** |
| `StatusPill`, `StepRow` disc, `NFTGlyph` fill, ChainPicker selected tile fill | **14%** |
| Welcome radial gradient | 16% |
| Matching-error card (`Palette.danger.opacity(0.1)`) | 10% |
| `SymbolHero` outer ring stroke | 18% |
| ChainPicker selected outline | 85% |
| Logo rings: network logo `primary` 14%, token/wallet icon `primary` 8% | |

⚠ Contrast: iOS draws pill/badge **text** in the tone colour on its 14% tint — light-mode warning text is ≈1.8:1,
success ≈2:1 (fails WCAG AA). Android adds "on-tint" content colours (§9.2) — this mirrors what iOS itself does
under *Increase Contrast* and Ownbit's `openProtocolOnSoft` (`#0B6E4B`).

### 2.5 Risk / tone semantics (where each tone is used)
| Situation | Tone / colour | Glyph | Source |
|---|---|---|---|
| Engine warning starting (case-insensitive, after `uppercased()`) with `HIGH RISK`, `UNLIMITED`, `HIGH FEE` | `Callout(.danger)` | `exclamationmark.octagon.fill` | `Features/Sign/RequestReviewView.swift:71-82` |
| Any other engine warning | `Callout(.warning)` | `exclamationmark.triangle.fill` | same |
| `riskLevel == .high` | `StatusPill("High risk", "exclamationmark.shield.fill", danger)` | | `RequestReviewView.swift:341-343` |
| Decode badge: fully / partially / not decoded | success `checkmark.seal.fill` "Decoded on device" / warning `exclamationmark.triangle.fill` "Partially decoded" / danger `questionmark.diamond.fill` "Not decoded" | | `RequestReviewView.swift:451-483` |
| Known contract (library hit) | **warning (amber, never green)** `checkmark.seal.fill` | | `RequestReviewView.swift:542-577` |
| Recognized token contract | accent `info.circle.fill` | | same |
| Unlisted spender/operator | danger `exclamationmark.triangle.fill` | | same |
| Unlisted plain contract | secondary `questionmark.circle.fill` | | same |
| Marketplace flow "You give" / "You receive" / "Also paid out" | warning / accent / secondary | | `RequestReviewView.swift:463-471` |
| Risk acknowledgement box | warning 12% fill, checkbox `checkmark.square.fill` warning / `square` secondary | | `RequestReviewView.swift:156-184` |
| Engine note line | success `checkmark.shield.fill`, footnote semibold | | `RequestReviewView.swift:326-330` |
| Matching error | danger text `xmark.octagon.fill`, card danger 10% radius 22 | | `RequestReviewView.swift:88-111` |
| Task signed / awaiting | `StatusPill("Signed", "checkmark", success)` / `lock.fill` secondary, a11y "Awaiting password" | | `RequestReviewView.swift:787-791` |
| Signature result hero / receipts | success `checkmark.seal.fill` | | `Features/Sign/SignatureResultView.swift:14-18` |
| Compatible wallet badges | "Watch and sign by QR" success `qrcode.viewfinder`; "Watch only" secondary `eye` (caption2 semibold) | | `Features/Settings/WalletDirectoryView.swift:64-85` |
| Destructive actions (Delete Wallet, Reject, Clear) | `role: .destructive` / `Palette.danger` | | `Features/Wallets/WalletDetailView.swift:95-99` |
| Scanner errors | `.orange` text on dark | | `QRScannerView.swift:96-117` |

### 2.6 Fixed colours (identical in both themes)
- QR plate `#FFFFFF`, modules `#000000`; the QR view forces `.colorScheme(.light)` inside (`Design/QRCodeView.swift:72`).
- Scanner: `Color.black` page, dim `black @55%` (camera authorised) / `@80%` (not), forced dark scheme, status bar hidden.
- Wallet avatar gradient + white initial (§5.17), network logo artwork colours (§8.2).

### 2.7 Appearance
`@AppStorage("appearance")` = `"system" | "light" | "dark"` (`BitToolSignerApp.swift:9,36`); Settings ›
Appearance › "Theme" picker with "System" / "Light" / "Dark" (`SettingsTab.swift:45-52`, icon
`circle.lefthalf.filled`). Separate windows (App Lock, privacy shield) get the same override
(`PrivacyShield.swift:26-28`, `AppLockView.swift:176`).

---

## 3. Typography

All text uses the **system font with Dynamic Type text styles**; only the launch replica uses fixed sizes.
Default ("Large") sizes, which are the Android sp values to use:

| iOS style | Size / leading | Default weight | Where (examples) |
|---|---|---|---|
| `.largeTitle` | 34 / 41 | regular (used `.bold`) | Welcome title, onboarding page titles, tab-root navigation large titles (system, bold) |
| `.title` | 28 / 34 | — | not used |
| `.title2` | 22 / 28 | used `.bold` | "Signature Created", wallet name (detail), lock title, Welcome feature icons |
| `.title3` | 20 / 25 | regular / `.semibold` | Welcome slogan; review text headline (semibold); "Scan a Signing Request"; activity detail title (bold); acknowledgement checkbox glyph |
| `.headline` | 17 / 22 | semibold | card titles ("How signing works", "Details", "Works with"), wallet names, scanner title |
| `.body` | 17 / 22 | regular; buttons `.semibold` | `ActionLabel` (body semibold), row titles, default text |
| `.callout` | 16 / 21 | regular | full addresses in summary/verification, flow lines, token IDs (mono) |
| `.subheadline` | 15 / 20 | regular / `.semibold` | callout text, step rows, list subtitles, compact action labels |
| `.footnote` | 13 / 18 | regular / `.semibold` | detail values (mono), address rows (mono), footers, password requirements, legal links |
| `.caption` | 12 / 16 | regular / `.semibold` | detail labels, pills, field captions, fingerprints (mono), counters (monospaced digits) |
| `.caption2` | 11 / 13 | regular / `.semibold` | payload text (mono), support badges, contract caption |

Special faces
- **Monospaced** (`.monospaced()` = SF Mono): every address (`AddressText`), hash (`HashText`, caption), derivation
  path, fingerprint ("Fingerprint %@" caption mono; detail header footnote mono), `DetailLine` values (footnote mono),
  payload (caption2 mono), licence text (`.system(size: 11, design: .monospaced)`, `SettingsTab.swift:341`),
  `CopyableText` (footnote mono by default), token IDs (callout mono), Solana path captions.
- **Monospaced digits** (`.monospacedDigit()`): QR frame counter, scanner percentage, address-row index labels, time in activity rows, address-index field.
- **Rounded** (`design: .rounded`): review amount `.largeTitle` bold with `minimumScaleFactor(0.5)`, `lineLimit(2)` (none for NFTs) (`RequestReviewView.swift:285-290`); `TokenIcon` fallback letters (bold, size×0.3); `WalletAvatar` initial (semibold, size×0.44).
- Fixed-size glyphs: `SymbolHero` (size×0.62 medium), `StepRow` 15 semibold, scan card glyph 62 regular, signature
  hero 44, `GenericNetworkLogo` size×0.46 semibold, `NFTGlyph` size×0.44 semibold.

Dynamic Type behaviours to keep
- `dynamicTypeSize.isAccessibilitySize` (AX1+, ≈ body ×1.65): `ActionLabel` allows 2 lines (else 1, min scale 0.8);
  `ChainPicker` hides chain names; `CompactActionLabelStyle` shows icon only; `AddressIndexControl` and
  `ProfileMenu` stack vertically; recovery grid drops to 2 columns (other spec).
- `dynamicTypeSize >= .xxLarge` (≈ ×1.24): `WalletGroup` grid min column 260 instead of 140 (`ConnectView.swift:665`).
- `@ScaledMetric(relativeTo: .body)` widths 76 / 116 for the index field (`ConnectView.swift:482-483`).
- Truncation is avoided everywhere: `.fixedSize(horizontal: false, vertical: true)` on almost every multi-line text;
  `ViewThatFits` stacks the review badges at large sizes (`RequestReviewView.swift:264-275`).

---

## 4. Layout metrics

### 4.1 Global — `Metrics` (`Design/DesignSystem.swift:24-40`)
| Token | Value |
|---|---|
| `Metrics.pagePadding` | **20** (horizontal page gutter, bottom-bar gutter, sheet padding) |
| `Metrics.cardRadius` | **22** |
| `Metrics.readableWidth` | **640** (`.readableWidth()` = `frame(maxWidth: 640)` centred); variants 520 (Welcome, onboarding, address sheet), 560 (signature result), 440 (lock) |
| `listBottomInset()` | **30** bottom content margin for scrolling lists |
| `cardBackground(padding: 16, radius: 22)` | default card |

### 4.2 Radius inventory (all `.continuous`)
| Radius | Where |
|---|---|
| 26 | review summary card (padding 18), scan home card (padding 20) |
| 24 | QR plate, QR loading placeholder |
| 22 | default card, wallet card, matching-error card, scanner status panel |
| 20 | scan option tiles ("Paste Request", "Import File", padding v18) |
| 18 | ChainPicker track |
| 16 | `Callout`, contract notice card, acknowledgement box, summary inner boxes (counterparty / asset / flow, padding 12) |
| 14 | password field box (padding 14), `ProfileMenu` row, `AddressIndexControl`, ChainPicker tiles |
| 30 | scan-card glyph tile 128×128 |
| 28 | scanner viewfinder window; bracket corner r 26 |
| size×0.3 / ×0.26 / ×0.225 | wallet avatar / NFT glyph / wallet app icon |
| capsule | buttons, pills, toast, network badge, index field |

### 4.3 Recurrent spacing
Card stacks 16 (wallet list, review), 20 (scan home, connect), 22 (signature result, address sheet), 14 (verification
blocks); inner vertical spacing 10–14; label→value 3–6; bottom bar row spacing 10. Touch targets: 44 pt minimum (Brand
principle); copy buttons 30×30 visual, eye button 32×32, scanner circle buttons 44×44.

### 4.4 Shadows (only on floating things; cards are flat on the grouped page)
| Element | Colour / radius / y |
|---|---|
| QR plate | black 8% / 16 / 6 |
| Brand tile | black 16% / size×0.1 / size×0.04 |
| Toast capsule | black 12% / 12 / 4 |
| Wallet-avatar initial (text shadow) | black 15% / 1 / 1 |

---

## 5. Shared components

### 5.1 Buttons — `ActionLabel`, `prominentAction()`, `secondaryAction()` (`Design/DesignSystem.swift:47-64,94-133`)
- **Primary**: `.borderedProminent`, capsule, `.controlSize(.large)` (iOS 26: `.glassProminent`) — solid accent fill,
  label `onAccent`. Visual height ≈ 50–52.
- **Secondary**: `.bordered`, capsule, large (iOS 26 `.glass`) — translucent tinted fill, accent label
  (`ActionLabel(…, prominent: false)`).
- `ActionLabel(title, systemImage:, busy:, prominent:)`: `ZStack { if busy ProgressView; HStack(spacing 8){ icon; title } }`;
  title `lineLimit(isAccessibilitySize ? 2 : 1)`, `minimumScaleFactor(0.8)`, centred; font `.body.semibold`; full width;
  vertical padding 4. While `busy` the label is kept at opacity 0 (width stable) and the spinner is tinted
  `secondary` (disabled) / `onAccent` (prominent) / accent (secondary).
  Label colour: disabled → `.secondary` (iOS comment: white on a pale disabled fill would vanish), prominent →
  `onAccent`, secondary → tint.
- Destructive: never a filled CTA. Uses `role: .destructive` in toolbars ("Reject", "Clear"), dialogs
  ("Reject Request", "Clear Activity") and list rows ("Delete Wallet", red label + `trash`).
- Disabled secondary "Cannot Sign" (`xmark.circle`) replaces Sign when matching failed (`RequestReviewView.swift:200-205`).
- Wallet card actions ("Pair" `qrcode`, "Addresses" `list.bullet.rectangle`): `.bordered` capsule `.regular`,
  `CompactActionLabelStyle` (icon + subheadline semibold title, spacing 6, min scale 0.85, icon only at AX sizes) (`WalletsTab.swift:108-127,142-153`).
- Large tappable cards use `PressableCardStyle`: scale 0.98 + opacity 0.9 while pressed, `.snappy(0.18)` (`ScanTab.swift:254-261`).
- Plain text actions: `.buttonStyle(.plain)` + `.foregroundStyle(.tint)` (e.g. "Export as File", "Submit a Wallet", legal links).

### 5.2 `bottomActionBar` (`Design/DesignSystem.swift:66-92`)
Pinned above the home indicator. `VStack(spacing: 10)` of the bar content; padding horizontal 20, top 8 (iOS 26 safe-area
bar with scroll-edge effect) / 10 (older, with `.bar` background), bottom `bottomPadding` (default **8**; Welcome passes 0
because it ends in the legal-links line), constrained to `maxWidth` (default 640; 520 Welcome/onboarding, 560 signature).
Users: Welcome (Create / Import / legal links), onboarding (dots + Continue), review (acknowledgement + "Signature %lld of %lld"
+ Sign), signature result (Done), Authorize sheet (Sign).

### 5.3 Cards
- `cardBackground(padding: 16, radius: 22)` = padding then `Palette.card` continuous rounded rect (`DesignSystem.swift:42-45`). Flat, no border, no shadow.
- Card variants: review summary (`Palette.card`, radius 26, padding 18, `RequestReviewView.swift:345-347`), summary inner
  boxes (`Palette.raised`, radius 16, padding 12), scan card (radius 26, padding 20), option tiles (radius 20), wallet card
  (radius 22, sections divided by `Divider().padding(.leading, 16)`, `WalletsTab.swift:69-139`).
- Network badge capsule: logo 18 + network name caption semibold, padding h10 v5, `Palette.raised` capsule (`RequestReviewView.swift:436-449`).

### 5.4 `Callout` (`Design/DesignSystem.swift:135-184`)
Tones `info | success | warning | danger` → colour accent / success / warning / danger, symbol `info.circle.fill` /
`checkmark.circle.fill` / `exclamationmark.triangle.fill` / `exclamationmark.octagon.fill`.
Layout: `HStack(alignment: .firstTextBaseline, spacing: 10)`: symbol (tone colour, `imageScale(.medium)`, a11y hidden),
`VStack(leading, spacing 3)`: optional title subheadline semibold; text subheadline — `.primary` when no title, `.secondary`
under a title; wraps fully; `Spacer(minLength: 0)`. Padding 14; background tone @12% radius 16. A11y: children combined.
Two initialisers: localised key, or `verbatim:` for engine/runtime text. In `List`s it is placed with
`.listRowInsets(EdgeInsets()).listRowBackground(Color.clear)` (floats on the page, not in a cell).

### 5.5 `StatusPill` (`Design/DesignSystem.swift:186-210`)
`Label { text } icon: { Image(systemName:) }`, caption semibold, foreground = colour (default accent), padding h10 v5,
background colour @14% capsule, `lineLimit(1)`. Localised or verbatim (stored receipt type).

### 5.6 `SymbolHero` (`Design/DesignSystem.swift:212-234`)
Defaults `size 76`, colour accent. Disc colour @12% Ø size×1.7; ring stroke colour @18% 1pt Ø size×2.2; glyph
`.system(size: size×0.62, weight: .medium)` monochrome; frame size×2.2; a11y hidden; `.symbolEffect(.bounce)` once on
appear unless Reduce Motion. Onboarding uses size 56 (36 when height < 560) (`WelcomeView.swift:207,217`).

### 5.7 `StepRow` (`Design/DesignSystem.swift:236-260`)
`HStack(top, spacing 14)`: 36×36 circle accent @14% with glyph 15 semibold tint; `VStack(spacing 2)` title subheadline
semibold, detail subheadline secondary. a11y combined. ⚠ `number` is accepted but **never drawn**. Used in "How signing
works": "Scan"/"Your watch-only wallet shows the unsigned request as a QR code." (`qrcode.viewfinder`), "Review"/"BitTool
Signer decodes it and finds the matching wallet on this device." (`text.magnifyingglass`), "Authorize"/"Enter that wallet’s
password to sign." (`lock.fill`), "Return"/"Scan the signature back. Your other wallet broadcasts it." (`arrow.uturn.left`)
(`ScanTab.swift:128-136`, card padding 18).

### 5.8 `DetailLine` (`Design/DesignSystem.swift:262-290`)
`VStack(leading, spacing 3)`: label caption secondary; value footnote **monospaced** (default) or footnote; text
selection enabled; wraps; full width; a11y combined. `init(label:value:monospaced:)` for values the app did not write;
`init(label:prose:)` for localised prose.

### 5.9 `Clipboard` (`Design/DesignSystem.swift:292-298`)
`UIPasteboard.general.setItems([[typeAutomatic: text]], options: [.localOnly: true, .expirationDate: now + 120 s])`.
Default expiry **120 s**; no cross-device Handoff. Used for public data only (addresses, payloads, fingerprints, sites).

### 5.10 `StatusToast` / `copiedToast` / `statusToast` (`Design/DesignSystem.swift:300-338`)
Overlay at the bottom of the modified view: `Label(message, systemImage: "checkmark.circle.fill")`, subheadline semibold,
padding h16 v10, `.regularMaterial` capsule, shadow black 12% r12 y4, 24 above the bottom; transition
`.move(edge: .bottom) + .opacity`; animation `.spring(duration: 0.3)`; posts a VoiceOver announcement, stays **1.6 s**,
then hides; `.sensoryFeedback(.success)` when it appears.
- `copiedToast($flag)` → message "Copied", announcement "Copied" (used by Connect toolbar "Copy QR Data", wallet header fingerprint copy).
- `statusToast("Password Changed", announcement: "Password changed", …)` (`WalletDetailView.swift:131`).
- ⚠ iOS announcement strings are plain `String`s (not localised; "Password changed" has no translation). Android: announce the localised message.

### 5.11 Addresses — `Design/AddressText.swift`
**`AddressFormatter.groups(value, size = 4)`** (lines 7-22): empty or size ≤ 0 → `[value]`. If it starts with `0x` or `0X`,
the first group is the prefix **plus** the next `size` characters (`"0x1234"`); the rest is chunked by `size` (last group
may be shorter). Grouping is presentation only — copy always uses the original string.

**`AddressFormatter.short(value, leading = 6, trailing = 4)`** (lines 25-29) — middle ellipsis for summaries only:
`hexPrefix = 2` only when it starts with lowercase `"0x"`; if `count <= leading + trailing + hexPrefix + 1` return
unchanged; else `prefix(leading + hexPrefix) + "…" + suffix(trailing)` (U+2026). Call sites: token/contract directory
`(8, 6)` (`TokenDirectoryView.swift:318,348`), activity counterparty `(6, 6)` (`ActivityTab.swift:226`). Full addresses
are **never** ellipsized by the text system.

**`AddressFormatter.looksLikeAddress`** (lines 31-38): trimmed; reject if it contains `" "` or `"\n"` or length ∉ 25…90;
accept `^0x[0-9a-fA-F]{40}$` | `^T[1-9A-HJ-NP-Za-km-z]{33}$` | `^(bc1|[13])[0-9A-Za-z]{20,87}$` | `^[1-9A-HJ-NP-Za-km-z]{32,44}$`.
(Decides `ReviewField.rendersAsAddress`, `Models/Models.swift:93`, and summary counterparty fallback.)

**`AddressText(address, font = .body, groupSize = 4)`** (lines 43-62): groups joined by single spaces into one `Text`;
**first and last groups semibold + `.primary`, middle groups regular + `.primary @78%`** ("swapped addresses usually differ
there first"); whole text `font.monospaced()`; wraps vertically (breaks between groups); accessibility label = groups
joined with `", "`. Fonts used: `.callout` (summary, verification), `.footnote` (rows, task card, contract card), `.body` (address sheet).

**`HashText(value)`** (lines 65-76): `value.uppercased()` grouped by **8**, joined by spaces; caption monospaced
secondary; selectable. Used for "Request fingerprint" and receipts.

**`CopyableAddress(address, font)`** (lines 79-109): `HStack(firstTextBaseline, spacing 10)`: `AddressText` (fills width) +
borderless 30×30 button `doc.on.doc` → `checkmark` (subheadline medium, symbol replace transition); a11y label "Copy address"
→ "Copied"; context menu "Copy Address" (`doc.on.doc`); state resets after **1.5 s**; `.sensoryFeedback(.success)` on copy.
No toast.

**`CopyableText(text, label = "Copied", font = .footnote)`** (`Features/Settings/WalletDirectoryView.swift:88-113`): for
non-address text (sites, e-mail): one monospaced selectable `Text` + the same copy button; a11y label `label` / "Copied".

### 5.12 QR — `Design/QRCodeView.swift`
`QRRenderer.image(for:)` (6-26): `CIFilter.qrCodeGenerator`, message = UTF-8 bytes, correction **"M" if
`utf8.count <= 600`, else "L"**; CPU context; one pixel per module. Measured: CoreImage output includes a **1-module
quiet zone** (version 1 → 23×23 px) and picks the densest mode (700 upper-case chars at L → 79×79 = version 15, i.e.
alphanumeric). Frames are rendered off the main thread (`Task.detached(priority: .userInitiated)`).

`QRCodeView(frames, maxSide = 320, interval = 250 ms, initialImages)` (29-111):
- Plate: `RoundedRectangle(24).fill(.white)`, shadow black 8% r16 y6; image `.interpolation(.none)` scaled to fit with
  **padding 18** (plus the 1-module CI margin); aspect 1; `maxWidth: maxSide`; `.environment(\.colorScheme, .light)`.
  Not ready → gray `ProgressView` on the white plate.
- a11y: "QR code" (single) / "Animated QR code, part %lld of %lld" (multi).
- Multi-frame (count > 1): row `HStack(spacing 12)`: bordered circle button 32×32 `pause.fill`/`play.fill` (a11y
  "Pause QR animation"/"Play QR animation", symbol replace), linear `ProgressView(value: index+1, total: count)` max width
  140 (a11y hidden), text "%lld/%lld" caption monospaced-digit secondary. Column spacing 14.
- Timing: loop `index = (index + 1) % images.count` every `interval` while `!paused && ready`; new frames → re-render and
  `index = 0`. `initialImages` (rendered ahead for exactly these frames) are shown immediately so nothing changes during a
  navigation push (`ConnectView.swift:99-113`).
- maxSide by caller: 300 (coin-level pairing), 360 (wallet-level animated), 340 (signature), 280 (address sheet).
- Loading placeholder used by callers: `Palette.card` rounded 24 square, max 300, `ProgressView` (`ConnectView.swift:233-238`).

### 5.13 `PayloadDisclosure` (`Design/PayloadDisclosure.swift`)
Collapsed developer view of what a QR carries. `title` localised or verbatim (engine titles pass through), `text`,
`notes: [String]`, `identifier`.
- Toggle row (plain button, tint colour): title subheadline semibold, `Spacer(min 8)`, `chevron.right` footnote semibold
  rotated 90° when expanded; `withAnimation(.snappy(duration: 0.25))`; a11y value "Shown"/"Hidden"; id `identifier+"Toggle"`.
- Expanded: each note caption secondary; payload `caption2.monospaced()` full width (not selectable, id `identifier`);
  if longer than **`displayLimit = 30_000` characters**: shows the first 30 000 + `"\n…"` and
  "Showing the first %@ of %@ characters. Copy includes everything." (both numbers `formatted()` with locale grouping);
  copy button "Copy Payload" → "Copied" (`doc.on.doc` → `checkmark`, footnote semibold, tint, reset after 1.5 s), copies the
  **full** text via `Clipboard`.
- `displayText` (30-44): if the shown text is > 2 000 UTF-8 bytes, every line longer than 120 characters gets a U+200B
  after each run of **64** non-space characters (run resets at a space) so wrapping stays cheap.
- Callers: Connect coin-level (`verbatim: view.title`, notes e.g. "Fingerprints are stored as 32-bit numbers: %lld is
  fingerprint %@."), wallet level "Payload (JSON)", signature result (note "psbt_base64 is the exact PSBT this QR code carries;
  decoded is read from it for reference.").

### 5.14 `PasswordField` (`Design/PasswordViews.swift:4-98`)
`PasswordField(kind, text:, isNew = false, revealable = false)`. Kinds (raw value = English text = placeholder = a11y
identifier): "Wallet password", "Confirm password", "Current wallet password", "New password", "Confirm new password",
"BIP39 passphrase", "Confirm passphrase".
- Non-revealable: `SecureField` with placeholder only (no floating label), `textContentType` `.password` (or **none when
  `isNew`**, so iOS never suggests a generated strong password), no autocapitalisation/autocorrection, `privacySensitive()`.
- Revealable (passphrase only): one `UITextField` kept alive across reveals (secure entry toggled on the same field, text
  re-applied because iOS clears a focused field on toggle; never written to while first responder), body font scaled,
  autocap/autocorrect/spellcheck/smart punctuation off, no clear button, `textContentType` empty + `passwordRules("")` when
  `isNew`; input accessory toolbar with a Done button. Trailing eye button 32×32 `eye`/`eye.slash` body secondary, a11y
  "Show secret"/"Hide secret", id `identifier + ".reveal"`; **re-hidden whenever the scene leaves `.active`**.
- Containers supplied by callers: padding 14 on `Palette.raised` radius 14 (lock card) or `Palette.card` radius 14
  (authorize sheet).
- App-wide: third-party keyboards are refused (`BitToolSignerApp.swift:52`).

### 5.15 Password strength meter — `PasswordRequirementsView(password, confirmation)` (`PasswordViews.swift:168-217`)
Uses `PasswordPolicy.evaluate` (`Core/PasswordPolicy.swift:5-45`): length 8…64 **Characters** and ≤ 1 024 UTF-8 bytes;
categories = uppercase letters, lowercase letters, decimal digits, symbols ∪ punctuation (Unicode classes); control
characters make it invalid; valid = length ∧ categories ≥ 3 ∧ no control chars.
- Bar: 4 capsules, height 4, spacing 4, equal widths. `strength`: empty → 0; invalid → 1; `count ≥ 16 && categories == 4`
  → 4; `count ≥ 12` → 3; else 2. Filled segments (`index < strength`) coloured 1 = warning, 2 = systemYellow, 3–4 = success;
  unfilled `systemFill`. `.animation(.smooth(duration: 0.2))` on strength; bar a11y hidden.
- Checklist (footnote, `VStack(spacing 8)` with the bar): "8 to 64 characters" (`hasValidLength`), "3 of: uppercase,
  lowercase, number, symbol" (`categories >= 3`), "Passwords match" (`!confirmation.isEmpty && password == confirmation`).
  Icon `checkmark.circle.fill` success / `circle` secondary with symbol replace; text primary when met else secondary;
  a11y combined with value "Met"/"Not met".

### 5.16 `WalletIdentityHeader(walletID, size = 56)` (`PasswordViews.swift:219-239`)
`HStack(spacing 14)`: `WalletAvatar(identity: store.avatarIdentity, name: store.walletLabel, size)`, `VStack(spacing 3)`:
name headline, "Fingerprint %@" caption monospaced secondary (fingerprint upper-case, or verbatim "Setup required");
a11y combined. Authorize sheet uses size 48.

### 5.17 Logos — `Design/Logos.swift`
`NetworkBrand` (6-70), order = `allCases`:
| case | `name` (verbatim, never translated) | `shortName` | `nativeSymbol` | asset | EVM chain id |
|---|---|---|---|---|---|
| bitcoin | Bitcoin | Bitcoin | BTC | ChainBitcoin | — |
| ethereum | Ethereum | Ethereum | ETH | ChainEthereum | 1 |
| bnb | BNB Smart Chain | BNB Chain | BNB | ChainBNB | 56 |
| arbitrum | Arbitrum One | Arbitrum | ETH | ChainArbitrum | 42161 |
| base | Base | Base | ETH | ChainBase | 8453 |
| tron | TRON | TRON | TRX | ChainTron | — |
| solana | Solana | Solana | SOL | ChainSolana | — |
`evmNetworks = [ethereum, bnb, arbitrum, base]`; `Chain.primaryNetwork`: bitcoin→bitcoin, evm→ethereum, tron→tron,
solana→solana. `Chain.name`: "Bitcoin", "EVM", "TRON", "Solana"; `longName` EVM = "Ethereum & EVM" (`Models/Models.swift:3-32`).

| Component | Spec |
|---|---|
| `NetworkLogo(network, size = 28)` | bundled vector, high interpolation, fit; overlay circle strokeBorder `primary @14%` width `max(0.5, size/56)`; a11y label = `name`. Sizes used 15–60. |
| `ChainLogo(chain, size = 28)` | `NetworkLogo(chain.primaryNetwork)` |
| `ChainGlyph(chain, size = 32)` | EVM: 2×2 grid (spacing 2) of ETH, BNB / ARB, BASE at `size×0.5 − 1`, a11y "EVM networks"; others = `ChainLogo` |
| `GenericNetworkLogo(size = 28)` | circle `systemGray5` + `point.3.connected.trianglepath.dotted` size×0.46 semibold secondary; a11y hidden (reviewed EVM networks without a bundled logo) |
| `NetworkLogoStack(networks = all, size = 22, ring = Palette.card)` | `HStack(spacing: −size×0.28)`; each logo padded `max(1.5, size×0.08)` on a circle filled with `ring` (later logos overlap earlier ones); a11y = shortNames joined ", ". Wallet card size 16; Welcome size 28 with `ring: Palette.background` + caption "Bitcoin, Ethereum, BNB Chain, Arbitrum, Base, TRON and Solana". |
| `WalletLevelRow` mini grid | 2×2 `ChainLogo` 15 (BTC, EVM / TRON, SOL), spacing 2, frame 32 (`WalletDetailView.swift:174-190`) |
| `TokenIcon(icon, symbol, size = 40)` | bundled PNG from `TokenIconStore` (scaled to fill, clipped circle) or fallback circle `systemGray5` + first 3 chars of `symbol` upper-cased, rounded bold size×0.3 secondary, min scale 0.5; overlay stroke `primary @8%` 0.5; a11y = symbol. Sizes 32, 60. |
| `TokenIconStore` (`Services/TokenIconStore.swift`) | `TokenIcons.index.json` = `{ "<16 hex of SHA-256>": [offset, length] }` (1 985 entries, 67 KB) into `TokenIcons.pack` (11.2 MB of concatenated PNGs, ≤ 128 px); pack memory-mapped; `NSCache` count limit 200; unknown id → no icon. |
| `WalletAvatar(identity, name, size = 44)` | `bytes = SHA256(identity.utf8)`; `hue = (b0<<8 \| b1)/65535`; `shift = 0.08 + b2/255 × 0.14`; gradient top-leading → bottom-trailing from HSB(hue, 0.62, 0.78) to HSB((hue+shift) mod 1, 0.70, 0.62); rounded rect radius size×0.3; initial = first Character of the trimmed name upper-cased, else "W"; rounded semibold size×0.44 white with shadow black 15% r1 y1; a11y hidden. Identity = engine `walletKeyId` or the wallet UUID (`Models/AppStore.swift:153`). Sizes 42 (task), 44, 48, 50 (wallet card), 56, 76 (detail header). "A visual aid only: signing always binds to the full key identity." |
| `WalletAppIcon(asset, size = 26)` (`Features/Wallets/ConnectView.swift:683-699`) | bundled PNG fit, clipped rounded rect radius size×0.225, stroke `primary @8%` 0.5; missing → `app.fill` quaternary; a11y hidden. Sizes 30 (groups), 34 (directory). |
| `NFTGlyph(kind, size = 60)` (`RequestReviewView.swift:662-690`) | rounded rect radius size×0.26 accent @14%, glyph size×0.44 semibold tint (`photo.artframe` nft / nftOrToken, `square.stack.3d.up.fill` multiToken, `tag.fill` nftOrder); `nftOrToken` adds bottom-trailing badge: `questionmark` heavy size×0.2 white in a warning circle size×0.38, ringed `max(1.5, size×0.04)` with `Palette.card`, offset (+0.12 size, +0.12 size); a11y "NFT" / "NFT or token" / "ERC-1155 token" / "NFT order". |

### 5.18 Selection controls
- **Segmented pickers** (`.pickerStyle(.segmented)`): "Wallet Level"/"Coin Level" (`ConnectView.swift:147-152`),
  "Single-sig"/"Multisig", Solana "Standard"/"Trust"/"Legacy" (+ path caption mono: m/44'/501'/0'/0', m/44'/501'/0',
  m/44'/501'), export "Account XPUB"/"Address" (BTC) or "Wallet"/"Address" (EVM, Solana), Compatibility
  "Formats"/"Wallets" pinned under the nav bar on `.bar` with padding h20 v8 (`SettingsTab.swift:154-163`), plus setup/backup
  pickers (`WalletSetupFlow.swift:149`, `RecoveryBackupViews.swift:41`). Changes are animated `.snappy(0.2–0.25)`.
- **`ChainPicker`** (`ConnectView.swift:24-78`): track `tertiarySystemFill` radius 18, padding 4, spacing 4; four tiles
  (Bitcoin, EVM, TRON, Solana): logo 26 (unselected saturation 0.35, opacity 0.6; selected ×1.06), name caption
  (selected bold tint, else medium secondary, 1 line, min scale 0.8, **hidden at AX sizes**), padding v9; selected tile:
  accent @14% fill + 1.5 stroke accent @85% radius 14, `matchedGeometryEffect` slide, `.snappy(0.28)`; a11y label =
  `longName`, `.isSelected`; `.sensoryFeedback(.selection)`.
- **`ProfileMenu`** (`ConnectView.swift:547-597`): full-width row on `Palette.card` radius 14 padding h14 v10: title
  subheadline + current detail caption secondary, trailing current title + `chevron.up.chevron.down` (tint); opens a Menu
  picker. Bitcoin presets: "Native SegWit" "BIP84 · bc1q…", "Taproot" "BIP86 · bc1p…", "Nested SegWit" "BIP49 · 3…",
  "Legacy" "BIP44 · 1…"; multisig "Native SegWit" "BIP48 · P2WSH", "Nested SegWit" "BIP48 · P2SH-P2WSH" (`ConnectView.swift:3-22`).
- **`AddressIndexControl`** (`ConnectView.swift:474-545`): "Address index" label, centred number field (body
  monospaced-digit semibold, capsule `tertiarySystemFill`, width 76 / 116 scaled when > 5 digits, number pad) + `Stepper`
  0…2 147 483 647; digits only, ≤ 10 digits, clamps; path caption mono "m/44'/195'/0'/0/<i>"; card radius 14; keyboard Done.
- Menu picker (App Lock wallet choice, "Choose a wallet") and `Toggle`s (accent tint) are standard.

### 5.19 Lists and rows (`List` + `.listStyle(.insetGrouped)`, `Form`)
- Sections with header/footer text (system styles), rows with `Label(title, systemImage:)` (icon tinted accent),
  `LabeledContent(label, value)` (value right, secondary), `NavigationLink` (chevron), destructive buttons, `Toggle`.
- Row components: `ChainRow` (ChainGlyph 32 + longName body + subtitle caption secondary: "Single-sig and multisig accounts",
  "Ethereum, BNB Chain, Arbitrum, Base and more", "Any address index", "Standard, Trust and Legacy paths"),
  `WalletLevelRow` ("Wallet Level" / "One QR code for all four chains"), `AddressRow` (index "#n" caption semibold
  monospaced-digit secondary min width 34, `AddressText` footnote, trailing `qrcode` tint; context menu "Copy Address" /
  "Show QR Code"), `ActivityRow` (logo 36, title subheadline semibold 2 lines, subtitle caption 1 line, time caption
  monospaced-digit).
- Full-bleed content inside lists uses `.listRowBackground(Color.clear)` with zero insets (detail headers, callouts, pickers).
- Lists end with `listBottomInset()` (30).

### 5.20 Presentations (inventory for Android mapping)
| iOS | Where | Details |
|---|---|---|
| `.sheet` detents `[.medium, .large]`, drag indicator visible, `interactiveDismissDisabled(busy)`; autofocus after 350 ms | `AuthorizeSheet` "Authorize Signature" | `RequestReviewView.swift:821-879`; Cancel (disabled while busy); Sign in bottom bar |
| `.sheet` `[.medium, .large]` | security tips | `RecoveryBackupViews.swift:413` |
| `.sheet` `[.large]` | `AddressDetailSheet` (QR 280 + address card + callout, Done) | `AddressesView.swift:71-74,170-201` |
| `.sheet` (page) with `interactiveDismissDisabled` | wallet setup flow, recovery phrase, change password, delete, repair, paste request, legal | `WalletSetupFlow.swift:45`, `WalletSecuritySheets.swift` |
| `.fullScreenCover` | QR scanner | `ScanTab.swift:144`, `WalletSetupFlow.swift:217` |
| `.confirmationDialog` (title visible, destructive) | "Discard partial signatures?", "Clear all activity?", "Discard this wallet?", "Which language is your backup in?" | |
| `.alert` | "Unable to Continue" + OK, "Rename Wallet" (text field), "Wallet Password", recovery prompts | |
| Separate `UIWindow`s | App Lock (level alert+1), privacy shield (alert+2) | `AppLockView.swift:156-185`, `PrivacyShield.swift:49-57` |

### 5.21 Navigation chrome
- `TabView`: "Wallets" (`wallet.bifold`, iOS < 18 `wallet.pass`), "Scan" (`qrcode.viewfinder`, badge 1 while a review is
  pending and unsigned), "Activity" (`clock.arrow.circlepath`), "Settings" (`gearshape`); never minimises
  (`Features/Main/MainTabView.swift:8-29`).
- Tab roots use large titles ("Wallets", "Scan", "Activity", "Settings"); pushed pages and sheets use inline titles.
  Toolbar items: Wallets has Scan (`qrcode.viewfinder`) and Add menu (`plus`: "Create New Wallet", "Import Recovery Phrase").
- Stage switch (loading / failed / welcome / main) cross-fades `.smooth(duration: 0.35)` (`RootView.swift:25-35`).

### 5.22 Empty and failure states (`ContentUnavailableView`)
"No Signatures Yet" (`signature`) + "Signatures you create on this device are listed here. BitTool Signer never broadcasts
transactions." (`ActivityTab.swift:17-23`); "Wallets Unavailable" (`externaldrive.badge.exclamationmark`) + "Your encrypted
wallet files are unchanged. Unlock this device, then try again." + primary "Try Again" (`arrow.clockwise`, busy, max width 280)
(`RootView.swift:92-112`).

### 5.23 Scanner chrome (visual tokens only; behaviour belongs to the scanner spec) — `QRScannerView.swift:26-203`
Black page, forced dark, status bar hidden; viewfinder side `min(width − 64, height × 0.5, 340)`, centred 20 above the
middle, cut-out radius 28; dim 55% / 80%; brackets `Brand.mint` width 5 round caps, arm length 16% of side, corner r 26,
pulsing scale 0.985 ↔ 1.015 `.easeInOut(1.2)` repeat-autoreverse (off with Reduce Motion); progress = rounded-rect trim
inset 12, `Brand.mint @90%` width 3; top bar 44 glass circles (`xmark` "Close", flashlight "Turn on flashlight"/"Turn off
flashlight", hidden when unavailable), title headline white; bottom status panel `.ultraThinMaterial` radius 22, padding 18,
max width 420, error text `.orange`, `.sensoryFeedback(.error)` on new error.

---

## 6. Motion and haptics

### 6.1 Animation vocabulary (SwiftUI → Compose spring; `dampingRatio = 1 − bounce`, `stiffness = (2π/duration)²`)
| iOS | bounce | Compose | Uses |
|---|---|---|---|
| `.smooth(0.35)` | 0 | damping 1.0, stiffness 322 | root stage cross-fade |
| `.smooth(0.3)` | 0 | 1.0 / 439 | scan tab content swap (home → review → result) |
| `.smooth(0.25)` / `.smooth` | 0 | 1.0 / 632 (default 0.5 s → 158) | secret reveal (`RecoveryPhraseViews.swift:35`, `RecoveryBackupViews.swift:108,236`), onboarding paging (`WelcomeView.swift:167`) |
| `.smooth(0.2)` | 0 | 1.0 / 987 | strength bar, QR section appear, scanner panel |
| `.snappy(0.25)` | 0.15 | 0.85 / 632 | payload disclosure, pairing level, multisig picker |
| `.snappy(0.28)` | 0.15 | 0.85 / 504 | chain picker |
| `.snappy(0.2)` | 0.15 | 0.85 / 987 | compatibility tabs |
| `.snappy(0.18)` | 0.15 | 0.85 / 1 218 | pressable cards |
| `.snappy(0.15)` | 0.15 | 0.85 / 1 755 | risk checkbox |
| `.spring(duration: 0.3)` | 0 | 1.0 / 439 | toast in/out |
| `.spring(duration: 0.8)` | 0 | 1.0 / 62 | Welcome hero |
| `.easeInOut(1.2)` repeat | — | `infiniteRepeatable(tween(1200, FastOutSlowIn), Reverse)` | viewfinder pulse |
| symbol `.bounce` | — | one-shot scale 1→1.12→1 (~350 ms) | `SymbolHero`, signature seal |
| symbol `.pulse` repeating | — | alpha 1↔0.5 (~1 s) | scan card glyph (off with Reduce Motion) |
| `contentTransition(.symbolEffect(.replace))` | — | `AnimatedContent` scale+fade (~150 ms) | copy ✓, eye, play/pause, checkboxes |
Reduce Motion (`accessibilityReduceMotion`) disables: `SymbolHero` bounce, Welcome hero spring, scan-card pulse, viewfinder pulse.
First data landing during a push is applied without animation (`ConnectView.swift:326-330`).

### 6.2 Haptics (`.sensoryFeedback`)
| Kind | Trigger |
|---|---|
| `.success` | copy in `CopyableAddress`; any `StatusToast` appearing; wallet created (`WalletSetupFlow.swift:450`); signature result appears (`SignatureResultView.swift:68`) |
| `.selection` | chain picker change; risk acknowledgement toggle; backup verification chip placed (`RecoveryBackupViews.swift:512`) |
| `.error` | scanner shows a new error |
No in-app haptics setting; the system setting governs.

---

## 7. Accessibility conventions
- `accessibilityElement(children: .combine)` on composite rows (Callout, StepRow, DetailLine, identity header, checklist rows, address rows).
- Decorative glyphs/logos hidden; logos labelled with the network name (verbatim) or symbol.
- `accessibilityValue` for state ("Shown"/"Hidden", "Met"/"Not met", "Checked"/"Not checked"); `.isSelected` traits for pickers.
- Accessibility identifiers are stable English (`"wallet.card.<uuid>"`, `identifier + "Toggle"`, kind raw values,
  `".reveal"`) — keep them as Android `testTag`s.
- Announcements: toast (§5.10). QR part label changes every 250 ms on iOS (noisy).
- 44 pt minimum touch targets; Dynamic Type everywhere except the launch replica.

---

## 8. Asset inventory

### 8.1 Colour sets
`AccentColor` (§2.1), `LaunchBackground` (`#F2F2F7` / `#000000`).

### 8.2 Chain logos — `Assets.xcassets/Chain*.imageset/*.svg` (64×64 viewBox, `preserves-vector-representation`)
| File | Content | Conversion notes (VectorDrawable) |
|---|---|---|
| `bitcoin.svg` (1 075 B) | circle `#F7931A`; white "₿" path | no transforms; `<circle>` → arc path |
| `ethereum.svg` (576 B) | circle `#627EEA`; 6 white paths with `fill-opacity` 0.602 / 1 / 0.602 / 1 / 0.2 / 0.602 inside `translate(32 32) scale(2.05) translate(-16.5 -16)` | group `pivotX=16.5 pivotY=16 scale 2.05 translateX=15.5 translateY=16`; `fill-opacity` → `android:fillAlpha`; set `fillColor` on every path (no inheritance from `<g fill>`) |
| `bnb.svg` (557 B) | circle `#F0B90B`; white path with `transform="translate(32 32) scale(2.05) translate(-16 -16)"` | wrap path in group pivot 16,16 scale 2.05 translate 16,16 |
| `arbitrum.svg` (1 299 B) | circle `#213147`; group `translate(32 32) scale(0.0222) translate(-1250 -1250)` with paths `#213147`, `#12AAFF` ×2, `#9DCCED`, polygon `#213147`, white ×2 | group pivot 1250,1250 scale 0.0222 translate −1218,−1218; polygon → `M642,2179 L727,1947 L897,2088 L738,2234 Z` |
| `base.svg` (193 B) | circle `#0000FF`; white rect x/y 18.5, 27×27, rx 1.9 | rect → rounded-rect path |
| `tron.svg` (462 B) | circle `#EB0029`; white path `translate(32 32) scale(2.05) translate(-16.6 -16.8)` | group pivot 16.6,16.8 scale 2.05 translate 15.4,15.2 |
| `solana.svg` (912 B) | circle `#000000`; 3 bars with `linearGradient` userSpaceOnUse (360.88,−37.46)→(141.21,383.29) `#14F195`→`#9945FF`, group `translate(32 32) scale(0.094) translate(-198.85 -155.85)` | group pivot 198.85,155.85 scale 0.094 translate −166.85,−123.85; merge the 3 bars into one `pathData` with one `<aapt:attr name="android:fillColor"><gradient type="linear" startX=360.88 startY=-37.46 endX=141.21 endY=383.29>` (gradient coords stay in the group's local space — verify in preview) |
General VectorDrawable rule for `translate(tx ty) scale(s) translate(−a −b)`: `pivotX=a pivotY=b scaleX=scaleY=s translateX=tx−a translateY=ty−b`.

### 8.3 Wallet app logos — `Assets.xcassets/wallet_*.imageset/icon.png` (all 180×180 PNG; RGB or RGBA)
`wallet_ambire, wallet_backpack, wallet_bitcoinsafe, wallet_bittool, wallet_bluewallet, wallet_bull, wallet_electrum,
wallet_helium, wallet_imtoken, wallet_jupiter, wallet_keeper, wallet_metamask, wallet_nunchuk, wallet_okx, wallet_onekey,
wallet_phantom, wallet_rabby, wallet_solflare, wallet_sparrow, wallet_specter, wallet_tokenpocket, wallet_tronlink,
wallet_trust, wallet_zeus` (24 files) — referenced by `Models/CompatibleWallets.swift`. Names are already valid Android
resource names. Platforms strings shown under each ("iOS · Android", "Desktop", "Extension · iOS · Android", …) stay verbatim.

### 8.4 Other bundled data used by components
`TokenIcons.pack` + `TokenIcons.index.json` (§5.17).

---

## 9. Android mapping

### 9.1 Module placement (Ownbit-style convention plugins)
| Module | Contents |
|---|---|
| `core:designsystem` (no domain deps; `ownbit.android.compose`-like plugin) | `SignerTheme`, colour/typography/shape/spacing/motion tokens, haptics, `PrimaryButton`/`SecondaryButton`/`ActionLabel`, `BottomActionBar`, `SignerCard`, `Callout`, `StatusPill`, `SymbolHero`, `StepRow`, `DetailLine`, `StatusToast*`, `SegmentedControl`, `GroupedSection`/rows, `EmptyState`, `Modifier.pressScale`, `BrandMark`/`BrandTile`/`LaunchView`, Material Symbols vectors, brand vectors, `SuperellipseShape` |
| `core:ui` (depends on `core:designsystem`, `core:model`, ZXing) | `AddressFormatter` (pure, unit-tested) + `AddressText`/`HashText`/`CopyableAddress`/`CopyableText`, `SignerClipboard`, `QrRenderer` + `QrCodeView`, `PayloadDisclosure`, `PasswordField` + `PasswordRequirements` (+ `PasswordPolicy` from `core:common`), `NetworkBrand` visuals (`NetworkLogo`, `ChainLogo`, `ChainGlyph`, `GenericNetworkLogo`, `NetworkLogoStack`), `TokenIcon` + `TokenIconStore`, `WalletAvatar`, `WalletAppIcon`, `NftGlyph`, `ChainPicker`, `ProfileMenu`, `AddressIndexControl`, chain + wallet drawables |
| feature modules | screen-specific composites (`WalletCard`, `ReviewSummaryCard`, `ContractNotice`, `TaskCard`, `WalletIdentityHeader` bound to the store — keep the stateless part `WalletIdentity(name, fingerprint, avatarIdentity, size)` in `core:ui`) |
| `app` | launcher icon, splash theme, window setup, `MainActivity` |
Both flavors (`apk`, `play`) share every design resource; no flavor-specific UI is needed (no updater: the app has no INTERNET permission). Optional: a debug-only launcher background tint to tell builds apart.

### 9.2 Theme
```kotlin
enum class Appearance(val stored: String) { System("system"), Light("light"), Dark("dark") }   // same values as iOS "appearance"
@Composable fun SignerTheme(appearance: Appearance = Appearance.System, content: @Composable () -> Unit)
object SignerTheme {
    val colors: SignerColors @Composable get        // LocalSignerColors (resolves base vs elevated)
    val typography: SignerTypography @Composable get
    val reduceMotion: Boolean @Composable get       // ANIMATOR_DURATION_SCALE == 0
    val isAccessibilityTextSize: Boolean @Composable get   // LocalDensity.current.fontScale >= 1.6f (iOS AX1 ≈ ×1.65)
    val isExtraLargeText: Boolean @Composable get   // fontScale >= 1.25f (iOS .xxLarge)
}
@Composable fun ElevatedSurfaceLevel(content: @Composable () -> Unit) // wrap sheet/dialog content: dark uses elevated palette
```
- **No dynamic colour** (brand palette is fixed). `MaterialTheme(colorScheme, typography, shapes)` + `CompositionLocal`
  extended tokens. Follow Ownbit's `OwnbitTheme` pattern for system-bar icon appearance (dark icons in light) and
  reduce-motion detection; read the system dark setting from the application configuration (Ownbit D18 fix) when
  `Appearance.System` and AppCompat night mode is in use.
- Appearance persistence: DataStore key `"appearance"`. Apply with `AppCompatDelegate.setDefaultNightMode` and, on API 31+,
  `UiModeManager.setApplicationNightMode` so the **system splash and recents snapshot** follow the in-app choice (iOS
  updates its extra windows the same way). Update the window background on change (Ownbit `applyWindowBackground`).

Material 3 `ColorScheme`:
| Role | Light | Dark |
|---|---|---|
| primary / onPrimary | `#0F7B55` / `#FFFFFF` | `#6BE3A4` / `#0B1F16` |
| primaryContainer / onPrimaryContainer | `#DDEDE7` (accent 14% on white) / `#0B6E4B` | `#273831` (mint 14% on `#1C1C1E`) / `#6BE3A4` |
| inversePrimary | `#6BE3A4` | `#0F7B55` |
| secondary / onSecondary | `#0F7B55` / `#FFFFFF` | `#6BE3A4` / `#0B1F16` |
| secondaryContainer / onSecondaryContainer | `#DDEDE7` / `#0B6E4B` | `#273831` / `#6BE3A4` |
| tertiary / onTertiary (warning role) | `#B33000` / `#FFFFFF` | `#FF9F0A` / `#000000` |
| tertiaryContainer / onTertiaryContainer | `#FFF0DB` / `#B33000` | `#3C2E1B` / `#FF9F0A` |
| error / onError | `#FF3B30` / `#FFFFFF` | `#FF453A` / `#FFFFFF` |
| errorContainer / onErrorContainer | `#FFE4E2` / `#C2000F` | `#3C2222` / `#FF6961` |
| background = surface | `#F2F2F7` | `#000000` |
| onBackground = onSurface | `#000000` | `#FFFFFF` |
| surfaceVariant / onSurfaceVariant | `#E5E5EA` / `#8A8A8E` (= secondaryLabel on white) | `#2C2C2E` / `#98989F` |
| surfaceTint | = surface (no tonal tint) | = surface |
| outline / outlineVariant | `#8E8E93` / `#C6C6C8` | `#8E8E93` / `#38383A` |
| surfaceContainerLowest / Low / (default) / High / Highest | `#FFFFFF` / `#F7F7FA` / `#F2F2F7` / `#EBEBF0` / `#E5E5EA` | `#000000` / `#141416` / `#1C1C1E` / `#2C2C2E` / `#3A3A3C` |
| surfaceBright / surfaceDim | `#FFFFFF` / `#E5E5EA` | `#3A3A3C` / `#000000` |
| inverseSurface / inverseOnSurface | `#1C1C1E` / `#FFFFFF` | `#F2F2F7` / `#000000` |
| scrim | `#000000` | `#000000` |

Extended `SignerColors` (CompositionLocal), base / dark-elevated where different:
| Token | Light | Dark | Dark elevated |
|---|---|---|---|
| `page` (background) | `#F2F2F7` | `#000000` | `#1C1C1E` |
| `card` | `#FFFFFF` | `#1C1C1E` | `#2C2C2E` |
| `raised` | `#F2F2F7` | `#2C2C2E` | `#3A3A3C` |
| `label`, `secondaryLabel`, `tertiaryLabel`, `quaternaryLabel` | `#000`, `#3C3C43@60%`, `@30%`, `@18%` | `#FFF`, `#EBEBF5@60%`, `@30%`, `@16%` | same |
| `separator` (translucent) / `opaqueSeparator` | `#3C3C43@29%` / `#C6C6C8` | `#545458@60%` / `#38383A` | |
| `fill`, `secondaryFill`, `tertiaryFill`, `quaternaryFill` | `#787880@20/16%`, `#767680@12%`, `#747480@8%` | `#787880@36/32%`, `#767680@24%`, `#767680@18%` | |
| `gray5` | `#E5E5EA` | `#2C2C2E` | |
| `accent`, `onAccent` | `#0F7B55`, `#FFFFFF` | `#6BE3A4`, `#0B1F16` | |
| `accentOnTint` (text on accent 12–14%) | `#0B6E4B` | `#6BE3A4` | |
| `success` / `successOnTint` | `#34C759` / `#1B7333` | `#30D158` / `#30D158` | |
| `warning` / `warningOnTint` | `#FF9500` / `#B33000` | `#FF9F0A` / `#FF9F0A` | |
| `danger` / `dangerOnTint` | `#FF3B30` / `#C2000F` | `#FF453A` / `#FF6961` | |
| `strengthMid` | `#FFCC00` | `#FFD60A` | |
| `scannerMint`, `ink`, `brandInk` | `#B3F0C7`, `#0B1F16`, `#0A0A0C` (fixed) | | |
| `qrPlate`, `qrModule`, `launchBackground` | `#FFFFFF`, `#000000`, `#F2F2F7` | —, —, `#000000` | |
Rules: icons and fills keep the iOS tone colour; **small text on a tint uses `*OnTint`** (measured ≥ 4.6:1 on the
14% tint over `#F2F2F7`). Optional: on API 34+, when `UiModeManager.getContrast() >= 0.5` (or "high contrast text"), raise
`secondaryLabel` to 76% (iOS Increase Contrast).

Stock component colour overrides (set once in wrappers): `NavigationBar` container `card`-ish (`surfaceContainer`),
selected icon + label `primary`, indicator `primaryContainer`, unselected `secondaryLabel`; `TopAppBar` container = `page`,
scrolled = `surfaceContainerHigh` (light) / `card` (dark); `Switch` checked track `primary`, thumb `onPrimary`;
`ModalBottomSheet` container = elevated `page`; `AlertDialog` container = elevated `card`; `DropdownMenu` = `card`;
text-selection handles `primary`, background `primary @40%`.

### 9.3 Typography
`SignerTypography` with the iOS style names and exact sizes/line heights of §3, `FontFamily.Default`, letter spacing 0,
`fontFeatureSettings = "tnum"` variants (`monospacedDigit()` extension), `monospaced()` extension, weight helpers.
M3 mapping: `displaySmall` = review amount 34 bold · `headlineLarge` = largeTitle 34/41 **Bold** (also used for
`LargeTopAppBar` expanded titles) · `headlineMedium` 28/34 · `headlineSmall` = title2 22/28 · `titleLarge` = title3 20/25 ·
`titleMedium` = headline 17/22 SemiBold · `titleSmall` = subheadline 15/20 SemiBold · `bodyLarge` = body 17/22 ·
`bodyMedium` = subheadline 15/20 · `bodySmall` = footnote 13/18 · `labelLarge` = callout 16/21 SemiBold (dialog/text
buttons) · `labelMedium` = caption 12/16 SemiBold (navigation labels) · `labelSmall` = caption2 11/13 SemiBold (badges).
`callout` (16/21) and `caption2` are extra fields on `SignerTypography`.
- Monospace: `FontFamily.Monospace` resolves to the OEM's mono font (DroidSansMono/Noto Mono variants differ in 0/O and
  1/l/I). **Recommendation:** bundle one mono font with unambiguous glyphs (e.g. JetBrains Mono, OFL‑1.1, Regular +
  SemiBold, ~300 KB) in `core:designsystem/res/font` and list it in an Android-only notices file shown after
  `THIRD_PARTY_LICENSES.txt` (do not edit the shared file). If the owner prefers zero added assets, use
  `FontFamily.Monospace` and accept OEM variance.
- Rounded design: no system equivalent; use `FontFamily.Default` (bold/semibold). Do not download fonts (no network).
- Dynamic Type → `sp` + `fontScale`; Android 14 non-linear scaling applies automatically. Use
  `SignerTheme.isAccessibilityTextSize` / `isExtraLargeText` for the §3 layout switches; `@ScaledMetric` →
  `with(LocalDensity.current) { 76.dp * fontScale }` or measure text.
- ⚠ Do not combine fixed `lineHeight` with `includeFontPadding = false` + `LineHeightStyle.Trim` for Thai/Arabic/Vietnamese
  (tall marks clip). Use `Trim.None` and `lineHeight ≥ 1.3 × fontSize`, or leave `lineHeight` unspecified for those locales.
- RTL (Arabic is one of the 21 languages): addresses, hashes, paths, payloads, fingerprints, counters ("%lld/%lld") must be
  laid out `TextDirection.Ltr` (Ownbit `ltrText()`); chevrons use AutoMirrored icons. Every "Fingerprint %@" line is
  `fingerprintLine` (core:ui): the value isolated in RTL only and in the monospaced family as a run of its own. In
  Arabic the value is drawn in the system's monospaced face (measured on the API 31 QA emulator:
  `FingerprintFaceDeviceTest`); its capitals and digits simply look like an ordinary sans without the typewriter-like
  lower case of the English line (what QA regression-10 read as a proportional face).
- Japanese and Korean titles and running text break between phrases on every API level through `PhraseText`
  (`headingLineBreaks` / `phraseLineBreaks` styles; word joiners in the drawn copy below API 33, Korean below 35; read
  as written by TalkBack).

### 9.4 Shapes, spacing, elevation
```kotlin
object SignerSpacing { val page = 20.dp; val card = 16.dp; val cardLarge = 18.dp; val stack = 16.dp; val barGap = 10.dp }
object SignerRadius { val card = 22.dp; val summary = 26.dp; val qr = 24.dp; val tile = 20.dp; val track = 18.dp; val callout = 16.dp; val control = 14.dp }
object SignerWidths { val readable = 640.dp; val narrow = 520.dp; val result = 560.dp; val lock = 440.dp }
val ListBottomInset = 30.dp
```
- Use `RoundedCornerShape` for cards/callouts (Android idiom; iOS continuous corners are optional polish). Use
  `SuperellipseShape(n = 5)` for the brand tile only. `WalletAvatar` = `RoundedCornerShape(30%)`, `WalletAppIcon` = 22.5%, `NftGlyph` = 26%.
- Shadows: Compose UI 1.9+ `Modifier.dropShadow(shape, Shadow(radius, color, offset))` with the §4.4 values; fallback =
  Ownbit `softShadow` (`OwnbitElevation.kt`). No tonal elevation; cards stay flat (`tonalElevation = 0`, `shadowElevation = 0`).
- Readable width: `Modifier.widthIn(max = 640.dp).fillMaxWidth()` inside a centred `Box`; on medium/expanded window size
  classes keep the same max widths.
- Touch targets: 48 dp minimum (`minimumInteractiveComponentSize`), visual sizes as iOS (copy glyph area 30, eye 32).

```kotlin
class SuperellipseShape(private val n: Float = 5f, private val samples: Int = 256) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val a = size.width / 2f; val b = size.height / 2f; val e = 2.0 / n
        val path = Path()
        for (i in 0..samples) {
            val t = 2 * PI * i / samples; val c = cos(t); val s = sin(t)
            val x = a + a * sign(c) * abs(c).pow(e); val y = b + b * sign(s) * abs(s).pow(e)
            if (i == 0) path.moveTo(x.toFloat(), y.toFloat()) else path.lineTo(x.toFloat(), y.toFloat())
        }
        path.close(); return Outline.Generic(path)
    }
}
```

### 9.5 Component APIs for `core:designsystem` / `core:ui`
```kotlin
// Buttons (capsule, 52.dp high, full width by default; label = SignerTypography.body.semibold, icon 20.dp + 8.dp gap)
@Composable fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, busy: Boolean = false, enabled: Boolean = true)
@Composable fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, busy: Boolean = false, enabled: Boolean = true)
@Composable fun TonalPillButton(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) // wallet-card Pair/Addresses
@Composable fun BottomActionBar(modifier: Modifier = Modifier, maxWidth: Dp = 640.dp, bottomPadding: Dp = 8.dp, content: @Composable ColumnScope.() -> Unit)
@Composable fun SignerCard(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(16.dp), radius: Dp = 22.dp, color: Color = SignerTheme.colors.card, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit)
fun Modifier.pressScale(interactionSource: MutableInteractionSource, scale: Float = 0.98f, alpha: Float = 0.9f): Modifier
enum class Tone { Info, Success, Warning, Danger }
@Composable fun Callout(text: String, modifier: Modifier = Modifier, title: String? = null, tone: Tone = Tone.Info)
@Composable fun StatusPill(text: String, icon: ImageVector, color: Color = SignerTheme.colors.accent, modifier: Modifier = Modifier)
@Composable fun SymbolHero(icon: ImageVector, modifier: Modifier = Modifier, color: Color = SignerTheme.colors.accent, size: Dp = 76.dp)
@Composable fun StepRow(title: String, detail: String, icon: ImageVector, modifier: Modifier = Modifier)
@Composable fun DetailLine(label: String, value: String, modifier: Modifier = Modifier, monospaced: Boolean = true)
@Stable class StatusToastState { fun show(message: String, icon: ImageVector = SignerIcons.CheckCircleFilled) }
@Composable fun StatusToastHost(state: StatusToastState, modifier: Modifier = Modifier)   // 1.6 s, liveRegion Polite, success haptic
@Composable fun SegmentedControl(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier)
@Composable fun GroupedSection(modifier: Modifier = Modifier, header: String? = null, footer: String? = null, content: GroupedSectionScope.() -> Unit)
@Composable fun ValueRow(label: String, value: String, modifier: Modifier = Modifier)
@Composable fun NavRow(title: String, icon: ImageVector?, onClick: () -> Unit, subtitle: String? = null, destructive: Boolean = false)
@Composable fun EmptyState(icon: ImageVector, title: String, description: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null)
@Composable fun BrandMark(modifier: Modifier = Modifier, color: Color = Color.White)
@Composable fun BrandTile(size: Dp = 88.dp, modifier: Modifier = Modifier, shadow: Boolean = true)
@Composable fun LaunchView(modifier: Modifier = Modifier, captured: Boolean = false)
// core:ui
object AddressFormatter { fun groups(value: String, size: Int = 4): List<String>; fun short(value: String, leading: Int = 6, trailing: Int = 4): String; fun looksLikeAddress(value: String): Boolean }
@Composable fun AddressText(address: String, modifier: Modifier = Modifier, style: TextStyle = SignerTheme.typography.body, groupSize: Int = 4)
@Composable fun HashText(value: String, modifier: Modifier = Modifier)
@Composable fun CopyableAddress(address: String, modifier: Modifier = Modifier, style: TextStyle = SignerTheme.typography.body)
@Composable fun CopyableText(text: String, copyLabel: String, modifier: Modifier = Modifier, style: TextStyle = SignerTheme.typography.footnote)
object SignerClipboard { fun copy(context: Context, text: String, expiresAfter: Duration = 120.seconds) }
object QrRenderer { suspend fun render(frames: List<String>): List<ImageBitmap> }   // Dispatchers.Default
@Composable fun QrCodeView(frames: List<String>, modifier: Modifier = Modifier, maxSide: Dp = 320.dp, interval: Duration = 250.milliseconds, initialImages: List<ImageBitmap>? = null)
@Composable fun PayloadDisclosure(title: String, text: String, modifier: Modifier = Modifier, notes: List<String> = emptyList(), tag: String = "payload")
enum class PasswordKind(val english: String) { WalletPassword("Wallet password"), ConfirmPassword("Confirm password"), CurrentWalletPassword("Current wallet password"), NewPassword("New password"), ConfirmNewPassword("Confirm new password"), Passphrase("BIP39 passphrase"), ConfirmPassphrase("Confirm passphrase") }
@Composable fun PasswordField(kind: PasswordKind, state: TextFieldState, modifier: Modifier = Modifier, isNew: Boolean = false, revealable: Boolean = false, imeAction: ImeAction = ImeAction.Done, onSubmit: () -> Unit = {})
@Composable fun PasswordRequirements(password: CharSequence, confirmation: CharSequence, modifier: Modifier = Modifier)
@Composable fun WalletIdentity(name: String, fingerprint: String, avatarIdentity: String, modifier: Modifier = Modifier, size: Dp = 56.dp)
@Composable fun NetworkLogo(network: NetworkBrand, modifier: Modifier = Modifier, size: Dp = 28.dp)
@Composable fun ChainLogo(chain: Chain, modifier: Modifier = Modifier, size: Dp = 28.dp)
@Composable fun ChainGlyph(chain: Chain, modifier: Modifier = Modifier, size: Dp = 32.dp)
@Composable fun GenericNetworkLogo(modifier: Modifier = Modifier, size: Dp = 28.dp)
@Composable fun NetworkLogoStack(modifier: Modifier = Modifier, networks: List<NetworkBrand> = NetworkBrand.entries, size: Dp = 22.dp, ring: Color = SignerTheme.colors.card)
@Composable fun TokenIcon(icon: String?, symbol: String, modifier: Modifier = Modifier, size: Dp = 40.dp)
@Composable fun WalletAvatar(identity: String, name: String, modifier: Modifier = Modifier, size: Dp = 44.dp)
@Composable fun WalletAppIcon(@DrawableRes res: Int?, modifier: Modifier = Modifier, size: Dp = 26.dp)
@Composable fun NftGlyph(kind: ReviewAssetKind, modifier: Modifier = Modifier, size: Dp = 60.dp)
@Composable fun ChainPicker(selection: Chain, onSelect: (Chain) -> Unit, modifier: Modifier = Modifier)
@Composable fun <T> ProfileMenu(title: String, options: List<T>, selection: T, label: (T) -> String, detail: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier)
@Composable fun AddressIndexControl(index: Int, onIndexChange: (Int) -> Unit, pathPrefix: String, modifier: Modifier = Modifier)
```
Implementation notes per component
- **Buttons**: Compose `Button`/`FilledTonalButton` restyled, or Ownbit `ButtonFrame` pattern (press scale 0.96, ripple,
  `FitText` shrink-then-wrap, loading keeps the invisible label for width and a11y). Primary: `primary` fill,
  `onPrimary` label; disabled: `onSurface @12%` fill, `secondaryLabel` label (iOS rule). Secondary: `primaryContainer`
  fill, `accentOnTint` label (Material tonal = Android rendition of `.bordered`). Busy → `CircularProgressIndicator`
  20 dp, stroke 2 dp, colours as §5.1; semantics `progressBarRangeInfo = Indeterminate`.
- **BottomActionBar**: `Column(verticalArrangement = spacedBy(10.dp))` with `windowInsetsPadding(navigationBars ∪ ime)`,
  padding h20 / top 8 / bottom `bottomPadding`, background `page`; draw a 12 dp top gradient fade (page 0 → 100%) and a
  hairline only while the content can scroll beneath (`scrollState.canScrollForward`) — Android rendition of the iOS 26
  scroll-edge effect.
- **StatusToastHost**: an in-window overlay (`Box(Alignment.BottomCenter)`), capsule on `card` (light: dropShadow 12% r12 y4;
  dark: elevated card `#2C2C2E` + hairline), padding h16 v10, `subheadline.semibold`, 24 dp above the bottom bar/nav bar
  and above the IME; enter `slideInVertically + fadeIn`, exit reverse (spring damping 1, stiffness 439); semantics
  `liveRegion = Polite` (do not use `View.announceForAccessibility`, deprecated on API 36); success haptic. A newer toast
  replaces the current one. Ownbit's separate `OverlayWindow` is unnecessary here (no PIN-lock window above dialogs).
- **SegmentedControl**: port Ownbit `SegmentedControl` (track = `tertiaryFill`, thumb = `card` + dropShadow in light /
  `#636366` in dark, sliding thumb with spring, `Role.Tab`, `selectableGroup()`, text measured so long translations never
  truncate). Prefer it over M3 `SingleChoiceSegmentedButtonRow` (its check icon steals width from labels such as
  "Account XPUB"). Selection haptic.
- **ChainPicker**: port Ownbit `core/publicsigner/ui/ChainPicker.kt` (already a BitSigner port): accent = `colors.accent`,
  muted logos via `ColorMatrix.setToSaturation(0.35f)` + alpha 0.6, selected logo scale 1.06, names hidden when
  `isAccessibilityTextSize`, `clearAndSetSemantics { role = Tab; selected; contentDescription = longName }`, min height 48.
  Use the BitTool chain drawables (not Ownbit coin logos) and keep the sliding selection (`animateDpAsState` of the tile offset or
  `SharedTransitionLayout`).
- **ProfileMenu**: row + `DropdownMenu` anchored to the row (items: title + detail as supporting text, check on the selected one).
- **AddressIndexControl**: `BasicTextField` with `KeyboardType.Number`, filter digits, ≤ 10, clamp to
  2 147 483 647; replace `Stepper` with a tonal pill of two 40 dp icon buttons (`remove` / `add`, repeat while held).
- **GroupedSection** (insetGrouped `List` rendition): Android 16-style "segmented" list — rows on `card`, 2 dp gaps,
  outer corners 22 dp, inner corners 4 dp (or one card with `opaqueSeparator` hairlines inset 16 dp, closer to iOS);
  header `titleSmall` in `accentOnTint`/`primary`, sentence case, start 16 dp, top 24 dp; footer `bodySmall`
  `secondaryLabel`, start/end 16 dp, top 8 dp. Row min height 56 dp; leading icon 24 dp tinted `primary`; trailing value
  `secondaryLabel`; chevron only where iOS shows `NavigationLink` (Android may omit chevrons — choose one rule app-wide).
  Float callouts/headers outside the card (iOS `listRowBackground(.clear)`).
- **Lists**: `LazyColumn` with `contentPadding(bottom = 30.dp + nav bar)`; `Modifier.combinedClickable(onLongClick)` +
  `DropdownMenu` for iOS context menus ("Copy Address", "Show QR Code").
- **AddressText**: one `Text(buildAnnotatedString)` — edge groups `SpanStyle(fontWeight = SemiBold, color = label)`,
  middle `SpanStyle(color = label.copy(alpha = 0.78f))`, separators `" "`; style `.monospaced()`, `TextDirection.Ltr`,
  `softWrap = true`, `LineBreak.Simple`, no hyphenation; `semantics { contentDescription = groups.joinToString(", ") }`.
  Graphemes: groups are by Swift `Character`; addresses are ASCII so `chunked()` on code points is equivalent — keep a unit
  test with the iOS examples (`0x` + 4).
- **CopyableAddress / CopyableText**: 48 dp `IconButton` showing a 30 dp area (`content_copy` → `check` via
  `AnimatedContent`, revert after 1.5 s), long-press menu "Copy Address", success haptic, `SelectionContainer` around the text for
  `CopyableText`/`DetailLine`/`HashText` (iOS `textSelection(.enabled)`), **not** for `PayloadDisclosure`.
- **SignerClipboard**: `ClipboardManager.setPrimaryClip(ClipData.newPlainText("BitTool Signer", text))`; remember the
  `ClipDescription.timestamp`; after 120 s (Handler in-process, re-checked on next `ON_RESUME`) clear with
  `clearPrimaryClip()` (API 28+) only if the current description timestamp/label is still ours (readable only while the app has
  focus — otherwise defer to the next resume). Android cannot force "local only" (OEM cross-device clipboards); public data only.
  Android 13+ shows its own system copy confirmation; default: keep the in-app icon swap / toast too (iOS parity) — the owner may
  drop the in-app toast on API 33+ to avoid a double confirmation.
- **QrRenderer / QrCodeView**: ZXing `QRCodeWriter` (Ownbit `core/ui/qr/QrEncoder.kt`), hints: `ERROR_CORRECTION` = M if
  UTF-8 bytes ≤ 600 else L; **`MARGIN = 1`** (match CoreImage's 1-module quiet zone); `CHARACTER_SET = UTF-8` **only** when
  the text contains non-ASCII (otherwise ZXing auto-selects numeric/alphanumeric/byte like CoreImage; forcing UTF-8 would add
  an ECI and change versions). Encode all frames once on `Dispatchers.Default` before showing (no flashes), keep `ImageBitmap`
  at one pixel per module and draw with `FilterQuality.None`, snapping the cell to an integer pixel size (centred; leftover
  becomes extra white). Plate: white `RoundedCornerShape(24)`, padding 18 dp, dropShadow 8% r16 y6, max side `maxSide`
  (callers 280/300/320/340/360), always light content (`CircularProgressIndicator` gray while encoding). Controls: 40 dp tonal
  circle (`pause`/`play_arrow`) in a 48 dp target, `LinearProgressIndicator` 140 dp, counter caption tabular LTR with fixed
  width of "n/n"; 14 dp gap. Timer: `LaunchedEffect(frames, ready, paused) { while (true) { delay(250); index = (index+1) % n } }`.
  TalkBack: announce "Animated QR code, part %lld of %lld" only while paused (Ownbit improvement; iOS changes it 4×/s).
  Optional Android polish: `FLAG_KEEP_SCREEN_ON` while an animated code is visible (no brightness boost — iOS has none).
- **PayloadDisclosure**: `Column(spacedBy(10.dp))`, `Row` toggle with `chevron_right` (auto-mirrored) rotating via
  `animateFloatAsState(if (expanded) 90f else 0f, spring(dampingRatio = 0.85f, stiffness = 632f))`, `AnimatedVisibility(expandVertically)`;
  `stateDescription` "Shown"/"Hidden"; numbers formatted with `NumberFormat.getIntegerInstance(locale)`; insert U+200B exactly as §5.13;
  count limit by grapheme/`String.length` (payloads are ASCII — equivalent).
- **PasswordField**: `BasicSecureTextField(state = remember { TextFieldState() } /* NOT rememberTextFieldState(): it is saveable */,
  textObfuscationMode = if (revealed) Visible else RevealLastTyped, keyboardOptions = KeyboardOptions(capitalization = None,
  autoCorrectEnabled = false, keyboardType = Password, imeAction))` inside a decorator box (radius 14, padding 14, placeholder =
  localised kind, `bodyLarge`). Wrap in Ownbit `NoPersonalizedLearning` (IME_FLAG_NO_PERSONALIZED_LEARNING). Autofill:
  existing passwords → `semantics { contentType = ContentType.Password }`; **`isNew` → no content type and exclude the field
  from Autofill** (no password-generation offers; verify with Google Password Manager and a third-party manager; fallback:
  `LocalView.current.importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS` while the creation screen is shown).
  Revealable: trailing 48 dp `IconButton` (`visibility`/`visibility_off`, `secondaryLabel`), contentDescription
  "Show secret"/"Hide secret", testTag `kind.english + ".reveal"`; reset `revealed = false` on `Lifecycle.Event.ON_PAUSE`.
  Clear the state right after submit (iOS clears before the async sign). Android cannot block third-party keyboards (iOS does):
  rely on the no-learning flag.
- **PasswordRequirements**: `Row(spacedBy(4.dp))` of 4 `Box(Modifier.weight(1f).height(4.dp).clip(CircleShape))` with
  `animateColorAsState` (spring damping 1, stiffness 987); checklist rows icon 16–18 dp (`check_circle` filled success /
  `radio_button_unchecked` secondary, `AnimatedContent`), `mergeDescendants` + `stateDescription` "Met"/"Not met".
  `PasswordPolicy` in Kotlin: count graphemes with `android.icu.text.BreakIterator.getCharacterInstance()`; categories by
  `Character.getType(codePoint)`: uppercase = `UPPERCASE_LETTER`+`TITLECASE_LETTER`, lowercase = `LOWERCASE_LETTER`,
  digit = `DECIMAL_DIGIT_NUMBER`, symbol = `MATH/CURRENCY/MODIFIER/OTHER_SYMBOL` ∪ all `*_PUNCTUATION` types,
  control = `CONTROL` + `FORMAT`; UTF-8 byte cap 1 024.
- **WalletAvatar**: `MessageDigest.getInstance("SHA-256")` over UTF-8; colours `Color.hsv(hue*360f, s, v)` (SwiftUI HSB = HSV);
  `Brush.linearGradient(start = Offset.Zero, end = Offset.Infinite)`; initial = first grapheme
  (`BreakIterator`) of `name.trim()` `.uppercase()` (locale-invariant, same as Swift) or "W"; text shadow via
  `TextStyle(shadow = Shadow(Color.Black.copy(.15f), Offset(0f, 1dp), 1dp))`; `clearAndSetSemantics {}`.
- **TokenIconStore**: ship `TokenIcons.pack` in `assets/` **uncompressed** (`androidResources { noCompress += "pack" }`),
  `assets.openFd("TokenIcons.pack")` → `FileInputStream(fd.fileDescriptor).channel.map(READ_ONLY, fd.startOffset, fd.length)`;
  decode slices with `BitmapFactory.decodeByteArray`/`ImageDecoder` off-main; `LruCache` of 200; index parsed once (kotlinx.serialization).
  Fallback circle `gray5` + 3 letters `bold` size×0.3 `secondaryLabel`, `autoSize`/shrink to 50%.
- **NetworkLogo**: `Image(painterResource(R.drawable.ic_chain_x))` + `Modifier.border(max(0.5.dp, size/56), label @14%, CircleShape)`;
  `NetworkLogoStack` with `Arrangement.spacedBy(-size*0.28f)` (negative spacing is fine) and z-order = list order; mirror in RTL like iOS.
- **BrandMark** (Canvas, exact geometry): ring = `drawCircle(color, radius = 330u, center = (512u, 511.5u), style = Stroke(84u))`,
  node = `drawCircle(color, 94.8u, (828.5u, 511.5u))`, where `u = size / 1024`. **BrandTile** = `SuperellipseShape(5)` filled
  `#0A0A0C` + `BrandMark` at the same 1:1 scale + optional dropShadow 16% r = size×0.1 y = size×0.04; or the 512 px PNG
  (`drawable-nodpi`, sufficient up to 128 dp at xxxhdpi). Optional dark-mode polish: a 1 dp `#FFFFFF @10%` outline so the ink
  tile does not dissolve into the black page (iOS has none — owner decision).
- **LaunchView**: `Box(fillMaxSize, background(launchBackground))`; tile 96 dp **exactly at the window centre** (so the system
  splash icon continues without a jump — Android's splash cannot show text) with title (22 sp SemiBold, label) 18 dp below and
  slogan (16 sp, secondaryLabel, centred, 30 dp side margins) 18 dp below that, drawn edge-to-edge (ignores insets);
  `captured` adds the eye-slash footnote line 24 dp above the bottom inset. This differs from iOS (whole stack centred) on purpose.

### 9.6 Icon strategy
- Do **not** depend on `material-icons-extended` (huge, no longer updated). Import only the needed **Material Symbols
  Rounded** (weight 400, grade 0, optical size 24; filled variants where iOS uses `.fill`) as vector XML / `ImageVector`s in
  `core:designsystem` (`SignerIcons`), mirrored where directional.

SF Symbol → Material Symbol (used by shared components and chrome):
| SF Symbol | Material Symbol | SF Symbol | Material Symbol |
|---|---|---|---|
| info.circle.fill | info (filled) | checkmark.circle.fill | check_circle (filled) |
| exclamationmark.triangle.fill | warning (filled) | exclamationmark.octagon.fill | report (filled) |
| xmark.octagon.fill | dangerous (filled) | checkmark.seal.fill | verified (filled) |
| questionmark.diamond.fill | help (filled) | questionmark.circle.fill | help (filled) |
| exclamationmark.shield.fill | gpp_maybe (filled) | checkmark.shield(.fill) | verified_user |
| lock.shield | shield_lock | eye / eye.slash(.fill) | visibility / visibility_off |
| doc.on.doc | content_copy | checkmark | check |
| play.fill / pause.fill | play_arrow / pause | chevron.right | chevron_right (mirrored) |
| chevron.up.chevron.down | unfold_more | circle | radio_button_unchecked |
| checkmark.square.fill / square | check_box / check_box_outline_blank | point.3.connected.trianglepath.dotted | hub |
| photo.artframe | photo_frame | square.stack.3d.up.fill | stacks |
| tag.fill | sell | questionmark | question_mark |
| qrcode.viewfinder | qr_code_scanner | qrcode | qr_code_2 |
| wallet.bifold | wallet | clock.arrow.circlepath | history |
| gearshape | settings | signature | signature |
| lock.fill / lock.open / lock.rotation | lock / lock_open / lock_reset | key(.fill) / key.viewfinder | key |
| camera.fill | photo_camera | flashlight.on.fill / .off.fill | flashlight_on / flashlight_off |
| xmark / plus | close / add | square.and.arrow.down / square.and.arrow.up | download / file_save ("Export as File") |
| arrow.clockwise | refresh | doc.on.clipboard / doc.badge.plus | content_paste / note_add |
| text.magnifyingglass | manage_search | arrow.uturn.left | undo |
| list.bullet.rectangle | list_alt | wrench.and.screwdriver | build |
| pencil / trash | edit / delete | circle.lefthalf.filled / globe | contrast / language |
| circle.hexagongrid | token | envelope | mail |
| square.grid.2x2.fill | grid_view | lock.iphone | phonelink_lock |
| chevron.left.forwardslash.chevron.right | code | airplane / wifi.slash | flight / wifi_off |
| hand.raised.fill | back_hand | arrow.left.arrow.right | swap_horiz |
| antenna.radiowaves.left.and.right.slash | portable_wifi_off | externaldrive.badge.exclamationmark | sd_card_alert |
| app.fill (missing wallet logo) | apps (filled, quaternary) | key.fill | key (filled) |

- **Chain logos**: hand-convert the 7 SVGs to `core:ui/res/drawable/ic_chain_{bitcoin,ethereum,bnb,arbitrum,base,tron,solana}.xml`
  (64 dp, viewport 64) following §8.2 (group pivots, `fillAlpha`, gradient via `aapt:attr`, circles/rects/polygons as paths).
  Verify each against the SVG at 16/28/60 dp in Studio preview and a Roborazzi test. Never load logos remotely.
- **Wallet logos**: copy the 24 PNGs to `core:ui/res/drawable-nodpi/wallet_*.png` (180 px covers 34 dp at xxxhdpi = 136 px).
- **Brand vectors** (`app` or `core:designsystem`):
  - `brand_mark.xml`: viewport 1024; path A (ring) `fillType="evenOdd"`
    `M140,511.5 a372,372 0 1,0 744,0 a372,372 0 1,0 -744,0 Z M224,511.5 a288,288 0 1,0 576,0 a288,288 0 1,0 -576,0 Z`;
    path B (node) `M733.7,511.5 a94.8,94.8 0 1,0 189.6,0 a94.8,94.8 0 1,0 -189.6,0 Z`; both `#FFFFFFFF`.
  - Superellipse tile path: generate once (do not hand-write) and commit:
    ```python
    import math; n=5; N=192; cx=cy=a=512   # for the splash use cx=cy=144, a=48 in a 288 viewport
    pts=[(cx+a*math.copysign(abs(math.cos(t))**(2/n),math.cos(t)), cy+a*math.copysign(abs(math.sin(t))**(2/n),math.sin(t))) for t in (2*math.pi*i/N for i in range(N))]
    print("M"+" L".join(f"{x:.2f},{y:.2f}" for x,y in pts)+" Z")
    ```
- **Adaptive launcher icon** (`app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml`):
  `<background android:drawable="@color/brand_ink"/>` (`#0A0A0C`), `<foreground android:drawable="@drawable/ic_launcher_foreground"/>`,
  `<monochrome android:drawable="@drawable/ic_launcher_monochrome"/>`. Foreground/monochrome = 108×108 dp vector with
  `<group android:scaleX="0.0703125" android:scaleY="0.0703125" android:translateX="18" android:translateY="18">` containing the
  two brand-mark paths (scale 72/1024 maps the iOS 1024 icon onto the 72 dp visible area: ring outer r = 26.2 dp, node far edge
  28.9 dp from the centre — inside the 33 dp safe circle; ring centre lands on (54, 53.96)). Monochrome uses the same paths
  (launcher tints by alpha). If `minSdk < 26`, add legacy `mipmap-*/ic_launcher.png` rendered from `AppIcon.png` with Android's
  circle/squircle masks (not needed for minSdk 26 like Ownbit).
- **Play Store listing icon** (play flavor only): 512×512 PNG downscaled from `AppIcon.png` (full-bleed, opaque; Play applies
  the mask).

### 9.7 Edge-to-edge
- `enableEdgeToEdge(SystemBarStyle.auto(TRANSPARENT, TRANSPARENT))` and `window.isNavigationBarContrastEnforced = false`
  (API 29+), like Ownbit `MainActivity`; theme items `windowLightStatusBar`/`windowLightNavigationBar` per night mode so the
  splash hand-over does not flash wrong icon colours (Ownbit `themes.xml`).
- Every screen draws `page` behind the bars; `Scaffold` passes insets; lists add `navigationBars` bottom padding + 30 dp;
  `BottomActionBar` consumes `navigationBars ∪ ime`; sheets use `WindowInsets.ime` padding.
- Scanner: hide the status bar (`WindowInsetsControllerCompat.hide(statusBars())`, behaviour transient-by-swipe), light icons,
  restore on exit. QR/signature screens keep normal bars.
- Large screens / foldables: readable max widths (§4.1), centred.

### 9.8 Predictive back
- `android:enableOnBackInvokedCallback="true"` (default for targetSdk 36); Navigation-Compose predictive animations for pushed
  pages; M3 `ModalBottomSheet` predictive back for sheets.
- Back is **blocked** (swallowed with `BackHandler(enabled = true) {}`) wherever iOS sets `interactiveDismissDisabled`: Authorize
  sheet while signing, security sheets while busy, wallet setup while busy/created.
- Setup with draft secrets: `BackHandler` shows the "Discard this wallet?" confirmation instead of dismissing (iOS blocks the swipe and
  offers Cancel → confirmation).
- Onboarding pager: back = previous page when `page > 0` (iOS "Back" toolbar button), otherwise Cancel.
- Use `PredictiveBackHandler` only where a custom preview animation is wanted (e.g. pager); otherwise plain `BackHandler`.

### 9.9 Splash screen continuing the brand tile
- `androidx.core:core-splashscreen`; `Theme.Signer.Splash` (parent `Theme.SplashScreen`):
  `windowSplashScreenBackground = @color/launch_background` (`#F2F2F7`, `values-night` `#000000`),
  `windowSplashScreenAnimatedIcon = @drawable/splash_tile`, no icon background, `postSplashScreenTheme = @style/Theme.Signer`;
  `android:windowBackground`/`colorBackground` = `launch_background` (also the FLAG_SECURE/recents placeholder colour).
- `splash_tile.xml`: 288×288 dp vector (Android 12+ shows a background-less icon in a 192 dp circle; core-splashscreen uses
  the same 288 dp box below API 31): superellipse tile 96 dp centred (box 96…192, path from the generator with cx=cy=144,
  a=48) filled `#0A0A0C`, plus the brand-mark paths in `<group scaleX="0.09375" scaleY="0.09375" translateX="96" translateY="96">`.
  Wrap it in an `animated-vector` with a no-op 1 ms animator so Android 12+ keeps it vector-sharp (Ownbit `splash_icon.xml` trick).
  The 96 dp tile sits fully inside the 192 dp mask circle (corner reach ≈ 59 dp).
- `installSplashScreen()` before `super.onCreate`; keep it on screen only until the first Compose frame
  (`setKeepOnScreenCondition { !firstFrameDrawn }`); exit with **no** animation (`setOnExitAnimationListener { it.remove() }`)
  so the Compose `LaunchView` (tile at the identical centre position) appears to simply gain its title + slogan; `LaunchView`
  stays while the wallet store loads (iOS `stage == .loading`), then cross-fades (`tween`/spring damping 1 stiffness 322, 350 ms).
- With an in-app theme override, set `UiModeManager.setApplicationNightMode` (API 31+) so the next splash matches.

### 9.10 Privacy cover / capture (visual side only; mechanism decided by the security spec)
- Cover content = `LaunchView(captured)`. The recents thumbnail cannot be reliably replaced by drawing in `onPause`; use
  `FLAG_SECURE` and/or `Activity.setRecentsScreenshotEnabled(false)` (API 33) — the placeholder then shows `launch_background`.
- Captured state: Android 15 `WindowManager.addScreenRecordingCallback` (API 35) → show the "Hidden while your screen is recorded
  or mirrored" line; `FLAG_SECURE` already blanks casting/recording on secure-surface-aware paths.

### 9.11 Motion and haptics implementation
- `SignerMotion` object: `fun <T> smooth(seconds: Double)`, `fun <T> snappy(seconds: Double)` built with Ownbit's
  `springParams(duration, bounce)` (`OwnbitMotion.kt`): smooth = bounce 0, snappy = bounce 0.15; `reduceMotion` → bounce 0 and skip
  repeating pulses/bounces (`SymbolHero`, scan glyph, viewfinder, Welcome hero = appear without scale).
- Haptics via Compose `LocalHapticFeedback` (Compose 1.8+ types) or `View.performHapticFeedback`:
  success → `HapticFeedbackConstants.CONFIRM` (API 30+, fallback `VIRTUAL_KEY`); selection → `SEGMENT_TICK` (API 34+, fallback
  `CLOCK_TICK`); error → `REJECT` (API 30+, fallback `LONG_PRESS`). Map exactly the triggers of §6.2 — no extra haptics on every
  button (unlike Ownbit's Light tap haptic) and **no in-app haptics switch** (Ownbit's default-off switch must not be copied).

### 9.12 Accessibility mapping
`combine` → `Modifier.semantics(mergeDescendants = true)`; hidden → `clearAndSetSemantics {}`; `accessibilityValue` →
`stateDescription`; `.isSelected` → `selected`; identifiers → `Modifier.testTag(...)` with `testTagsAsResourceId = true` on the
root; headers → `semantics { heading() }`; QR part text as polite live region only when paused; contrast per §9.2.

### 9.13 Pitfalls checklist
1. Three greens/inks: accent dark `#6BE3A4` ≠ scanner `Brand.mint #B3F0C7`; label ink `#0B1F16` ≠ tile ink `#0A0A0C`.
2. `BrandTile.png` is a superellipse; `BitToolSigner-Tile.svg` is a plain rx-229 rounded rect — match the PNG.
3. Dark sheets/dialogs must use the **elevated** palette (`#1C1C1E` page, `#2C2C2E` card); otherwise black-on-black.
4. ZXing defaults differ from CoreImage: margin 4 → set 1; ISO-8859-1 default → UTF-8 only for non-ASCII; keep M/L at 600 bytes.
5. `rememberTextFieldState()`/`rememberSaveable` would persist secrets — use `remember { TextFieldState() }` and clear after use.
6. New-password fields must not trigger Autofill generators (BIP39 passphrase safety).
7. Tone-coloured small text fails contrast in light mode — use `*OnTint`.
8. Network names, tickers, wallet names and platform strings are verbatim (never translated); localise everything else, including
   toast announcements (iOS bug) and "Setup required"/"Wallet" fallbacks (iOS shows English) — flag in the l10n spec.
9. Swift `Character` counts (password length, payload limit, avatar initial) → graphemes on Android.
10. `includeFontPadding=false` + fixed line heights clip Thai/Arabic marks.
11. Force LTR for all identifiers in RTL locales.
12. TokenIcons.pack must be stored uncompressed to be memory-mappable.
13. `material-icons-extended` is deprecated and large — vendor the needed Material Symbols.
14. Copying Ownbit code into this MIT-licensed open-source repo publishes it — confirm with the owner (same organisation) before porting files verbatim.

### 9.14 Verification
Roborazzi screenshot tests (as Ownbit `DesignCatalogScreenshotTest`) for every component in light, dark, dark-elevated, RTL (ar),
font scale 2.0, and a long-translation locale (de/ru); unit tests for `AddressFormatter`, strength levels, `WalletAvatar` colours
(golden values computed from the Swift formula), QR options (M/L threshold, margin, charset), U+200B insertion, superellipse path.

---

## 10. Out of scope (iOS-only)
- Liquid Glass (`.glassProminent`, `.glass`, `glassEffect`, `safeAreaBar` scroll-edge effects) and every `#available(iOS 26)`
  / iOS 17–18 fallback branch (`DesignSystem.swift:47-91`, `QRScannerView.swift:129-137`); the `wallet.bifold` vs `wallet.pass`
  OS switch (`MainTabView.swift:27-30`).
- UIKit specifics: `UIPasteboard` options (`localOnly`), `UITextField`/`UIToolbar` accessory Done bar and `passwordRules`,
  `UIViewRepresentable` probes, separate `UIWindow` levels for lock/privacy, `UIScreen.isCaptured`, `UIHostingController`,
  CoreImage/Metal notes, `NSCache`.
- Face ID / Touch ID wording and symbols on the lock screen and settings ("Unlock with %@", "%@ Lock", "Use %@ Instead",
  `NSFaceIDUsageDescription`) — Android uses its own biometric spec.
- `LaunchScreen.storyboard`, asset-catalog mechanics (`preserves-vector-representation`, appiconset single-size), the iOS
  continuous-corner shape for generic cards, SF Pro/SF Mono/SF Rounded fonts, SF Symbols effects as such.
- `shouldAllowExtensionPointIdentifier(.keyboard)` (third-party keyboard block) — not possible on Android.
- Simulator UI-test context (`--dark-appearance`, `--light-appearance`, `Services/SimulatorUITestContext.swift`),
  App Store/TestFlight assets, `PrivacyInfo.xcprivacy`.
- The pre-rename screenshot styling (mint CTA, eyebrows, old "B" mark) — historical only.
