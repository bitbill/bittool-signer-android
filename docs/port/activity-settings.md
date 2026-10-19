# Port spec: Activity tab and the whole Settings area

Scope: everything reachable from the **Activity** tab and the **Settings** tab of BitTool Signer iOS, plus
the data/services they own (signing-receipt journal, bundled token registry + known-contract browser,
token icon pack, compatible-wallet catalogue, interface language, appearance, legal documents, open-source
page). Paths below are relative to `/Users/tanguopeng/Documents/bitsigner-ios/BitSigner/` unless absolute.
`BitToolSigner/` is abbreviated `BTS/`.

Sources read in full: `BTS/Features/Activity/ActivityTab.swift` (330 lines),
`BTS/Features/Settings/SettingsTab.swift` (363), `TokenDirectoryView.swift` (358),
`WalletDirectoryView.swift` (113), `LegalDocumentView.swift` (120), `SubmitWalletView.swift` (68),
`BTS/Services/TokenIconStore.swift` (45), `BTS/Models/AppLanguage.swift` (127),
`BTS/Models/CompatibleWallets.swift` (176), `BTS/Models/AppStore.swift` (602), the `SigningReceipt` /
`TransactionReview` parts of `BTS/Models/Models.swift`, `BTS/Services/AppAccessController.swift`,
`BTS/Core/SigningEngine.swift`, the shared design components (`BTS/Design/*.swift`),
`BTS/Features/Onboarding/WelcomeView.swift`, `BTS/BitToolSignerApp.swift`, `BTS/Features/Main/*.swift`,
`BTS/Resources/TERMS_OF_USE.txt`, `PRIVACY_POLICY.txt`, `THIRD_PARTY_LICENSES.txt` (structure),
`docs/TokenRegistrySources.md`, `docs/KnownContracts.md`, `docs/Reproducible-Build.md`, the relevant
unit tests (`BitToolSignerTests/ActivityRecordTests.swift`, `LegalDocumentTests.swift`,
`CompatibleWalletsTests.swift`, `TokenRegistryBundleTests.swift`, `KnownContractTests.swift`) and the
21 `translations/*.json` files (773 strings each, keyed by the English source string).

Conventions in this spec

- Strings in `"double quotes"` are **verbatim English source strings = localization keys** (they exist in
  `translations/*.json` unless marked **[not localized on iOS]** or **[new Android key]**). Typography
  matters: `’` (U+2019), `…` (U+2026), `—` (U+2014), `·` (U+00B7) and, in one onboarding string,
  `Wi‑Fi` with U+2011 NON-BREAKING HYPHEN. Straight `'` is used in "BitTool Signer's" and
  "signature's"; curly `’` in "wallet’s" and "Don’t".
- Format specifiers are quoted as in Swift (`%@`, `%lld`). Android: `%@` → `%1$s`, `%lld` → `%1$d`
  (positional when more than one argument).
- iOS font names map to Material 3 roles in §14.3.

---

## 0. Key facts / risks (read first)

1. **Activity re-reviews the stored request with a fresh engine `review` call** and refuses to show any
   decoded field unless the recomputed `requestHash` equals the stored one (case-insensitive) and
   `fields` is non-empty (`BTS/Models/AppStore.swift:581-591`). This is a *fresh* QuickJS context, never
   the warm public one (`BTS/Core/SigningEngine.swift:118-124,156-161`).
2. **Retention**: ≤ 100 records; only the **25 most recent** keep payloads (request JSON, response
   frames, response text); each payload ≤ **32,768 UTF-8 bytes**; file ≤ **2,097,152 bytes**; oldest
   payloads are shed until the encoded file fits (`AppStore.swift:554-574`, `Models.swift:433-458`).
3. **Storage on iOS is plain JSON under OS Data Protection `complete`**, dir 0700 / file 0600, excluded
   from backup (`AppStore.swift:592-600`). Android has no per-file "complete" class → use an
   **AES-256-GCM file keyed in Android Keystore** (`setUnlockedDeviceRequired(true)` on API 28+) in
   `noBackupFilesDir`, and exclude everything from cloud backup *and* device-to-device transfer.
4. **The iOS Settings tab has no "air-gapped device guide"**: it was deliberately removed from Settings
   (`IMPLEMENTATION_STATUS.md:428-429`, "Offline Setup Guide was removed from Reference"); the guide is
   onboarding page 3 only (`BTS/Features/Onboarding/WelcomeView.swift:151-156`). Content and the Android
   adaptation are in §11; do not add a Settings row unless the product owner asks.
5. **iOS-specific wording sits in localized keys** (App Lock label/footers, biometric errors, onboarding
   "dedicated iPhone", Terms §15 "The App Store", Privacy "iOS file protection / iCloud / Secure
   Enclave / Handoff / mainland-China wireless-data prompt"). Android needs **new keys + 20 translations**
   and **flavor-specific legal texts** (§10, §12.3).
6. **Language switching**: 21 languages + "System", Arabic RTL. On Android use
   `AppCompatDelegate.setApplicationLocales`; pitfalls: English lives in `values/` only → a device list
   like `[en-GB, de]` resolves **German** unless a `values-en` copy exists or the followed language is
   pinned; and **`playRelease` App Bundles must disable language splits** or in-app switching shows
   missing strings (§14.5).
7. **No INTERNET permission in either flavor**; library manifests can re-add it through manifest merger →
   `tools:node="remove"` + CI check on both `apkRelease` and `playRelease` merged manifests. "Network
   connections: None" / "Networking code: None" in Settings must stay true.
8. **apk vs play** differ only in About/Open-Source build info (distribution, signing-certificate
   SHA-256) and in the store clauses of the legal texts. No in-app update in `apk` (no network). Both
   flavors should share one applicationId **and one signing certificate** (upload the release key to
   Play App Signing), otherwise a user moving between distributions must uninstall — which deletes the
   wallets (§14.8).
9. **Engine digest** shown on the Open Source page must be computed from the bundled asset at runtime:
   current `signer.js` = `6933d0d8a6f60faa8222506ac0bd7eef80eb884c9f73e31e9f354b4740f21077`,
   **1,181,026 bytes**, token registry `2026-09-22.1`. Store `signer.js` byte-identical in `assets/`.
10. Several values iOS shows are **not localized** (stored English `resultType`, "Deleted wallet", "High",
    "Warnings shown", activity I/O errors, engine-produced titles/summaries/action). Decide per item
    (§12.2); defaults recommended below.

---

## 1. Shared building blocks used by these screens

| iOS component | Source | Behaviour to reproduce |
| --- | --- | --- |
| `Palette` | `BTS/Design/DesignSystem.swift:14-22` | background = systemGroupedBackground, card = secondarySystemGroupedBackground, success = systemGreen, warning = systemOrange, danger = systemRed. |
| Accent colour | `BTS/Assets.xcassets/AccentColor.colorset` | light `#0F7B55`, dark `#6BE3A4`. `Brand.onAccent` = white in light, ink `#0B1F16` in dark (`DesignSystem.swift:6-12`). Launch background light `#F2F2F7`, dark `#000000`. |
| `Metrics` | `DesignSystem.swift:24-28` | cardRadius 22, readableWidth 640, pagePadding 20. |
| `listBottomInset()` | `DesignSystem.swift:38-40` | 30 pt extra bottom content margin on scrolling lists (Activity list, Activity detail, Networks list). |
| `Callout` | `DesignSystem.swift:136-184` | tones info (accent, `info.circle.fill`), success, warning (orange, `exclamationmark.triangle.fill`), danger (red, `exclamationmark.octagon.fill`); 14 pt padding, fill = tone colour @ 12 % opacity, corner 16, icon + subheadline text; a11y combined; icon hidden from a11y. |
| `StatusPill` | `DesignSystem.swift:187-210` | capsule, icon + caption semibold, colour text, fill colour @ 14 %, h-padding 10 / v-padding 5, 1 line. |
| `DetailLine` | `DesignSystem.swift:263-290` | two lines: label (caption, secondary) above value (footnote; monospaced unless `monospaced:false` or prose); value selectable; a11y combined. `value:` init = verbatim, `prose:` init = localized. |
| `CopyableText` | `BTS/Features/Settings/WalletDirectoryView.swift:88-113` | monospaced selectable text, full width, + 30×30 borderless button `doc.on.doc` → `checkmark` (symbol replace transition). Once copied it **stays** a checkmark (no reset). A11y label = copied ? "Copied" : its `label` (default "Copied"). No haptic. |
| `HashText` | `BTS/Design/AddressText.swift:65-76` | value **uppercased**, groups of 8 joined by spaces, caption monospaced secondary, selectable. |
| `AddressFormatter.short` | `AddressText.swift:25-29` | if value has `0x` prefix, keep `leading+2` chars; returns value unchanged unless `count > leading+trailing+prefix+1`; else `prefix + "…" + suffix(trailing)`. |
| `PayloadDisclosure` | `BTS/Design/PayloadDisclosure.swift` | collapsed by default; header button = title (subheadline semibold, tint) + chevron rotating 0°→90° (`snappy 0.25 s`); a11y value "Shown"/"Hidden". Expanded: optional notes (caption secondary), payload text (caption2 monospaced) capped at **30,000 characters** with `"\n…"` appended and the note "Showing the first %@ of %@ characters. Copy includes everything." (numbers grouped); when > 2,000 UTF-8 bytes, a zero-width space U+200B is inserted every 64 non-space chars in lines > 120 chars (wrap performance only; copy uses original). Copy button "Copy Payload" ↔ "Copied" (`doc.on.doc`/`checkmark`), resets after **1.5 s**. |
| `QRCodeView` | `BTS/Design/QRCodeView.swift` | white rounded card (corner 24, shadow black 8 % r16 y6), QR at 1 px/module scaled with nearest-neighbour, padding 18, square, `maxSide` cap; error correction **M if payload ≤ 600 UTF-8 bytes else L**; always light scheme. Multi-frame: auto-advance every **250 ms**, play/pause circle button (a11y "Pause QR animation"/"Play QR animation"), linear progress (max width 140, a11y hidden) and "%lld/%lld" (caption monospaced digits). A11y label "Animated QR code, part %lld of %lld" or "QR code". Spinner until rendered. |
| `NetworkLogo` | `BTS/Design/Logos.swift:83-96` | bundled vector logo (ChainBitcoin/Ethereum/BNB/Arbitrum/Base/Tron/Solana `.svg`), circle stroke primary @ 14 %, width `max(0.5, size/56)`; a11y label = network name. |
| `GenericNetworkLogo` | `Logos.swift:126-138` | systemGray5 circle + `point.3.connected.trianglepath.dotted` at 46 % size, secondary; a11y hidden. |
| `ChainGlyph` | `Logos.swift:105-123` | EVM = 2×2 grid of Ethereum/BNB/Arbitrum/Base logos (each `size*0.5-1`, spacing 2), a11y "EVM networks"; others = chain's primary logo. |
| `TokenIcon` | `Logos.swift:160-185` | image from `TokenIconStore`, scaledToFill, circle clip, stroke primary @ 8 % 0.5 pt; fallback systemGray5 circle with first 3 chars of symbol uppercased (rounded bold, `size*0.3`, min scale 0.5); a11y label = symbol. |
| `WalletAppIcon` | `BTS/Features/Wallets/ConnectView.swift:683-698` | bundled PNG `wallet_*`, rounded-rect corner `size*0.225`, stroke primary @ 8 %; fallback `app.fill` quaternary; a11y hidden. |
| `WalletGroup` | `ConnectView.swift:654-681` | title label (icon + subheadline semibold secondary), adaptive grid min column 140 pt (260 at Dynamic Type ≥ xxLarge), spacing 10 / row spacing 12; item = icon 30 + name (subheadline, ≤2 lines, min scale .85) + platforms (caption2 secondary, ≤2 lines); a11y combined per item. |
| `BrandTile` | `Logos.swift:224-233` | app icon tile image; shadow optional; a11y hidden. |
| `Clipboard.copy` | `DesignSystem.swift:293-298` | local-only (no Handoff/Universal Clipboard), **expires after 120 s**. |
| `NetworkBrand` | `Logos.swift:6-70` | bitcoin, ethereum ("1"), bnb ("56", short "BNB Chain", name "BNB Smart Chain"), arbitrum ("42161", short "Arbitrum", name "Arbitrum One"), base ("8453"), tron, solana. `evm(chainID:)` maps only those four chain IDs. `Chain.primaryNetwork`: bitcoin→bitcoin, evm→ethereum, tron→tron, solana→solana. |
| `Chain` | `Models.swift:3-32` | `bitcoin`/`evm`/`tron`/`solana`; `name` "Bitcoin"/"EVM"/"TRON"/"Solana"; `longName` = name except EVM "Ethereum & EVM" [not localized on iOS — shown via `Text(String)`]. |

---

## 2. Activity

### 2.1 Record model — `SigningReceipt` (`BTS/Models/Models.swift:404-459`)

| Field | Type | Meaning / source at creation (`AppStore.swift:449-457`) |
| --- | --- | --- |
| `id` | UUID | new per record |
| `date` | Date | `Date()` at signing |
| `chain` | Chain | `task.chain` |
| `title` | String | `approved.title` (engine review title, English) |
| `requestHash` | String | `plan.requestHash` (64 hex) |
| `walletID` | UUID? | `task.walletID` |
| `walletKeyID` | String? | `task.walletKeyId` |
| `masterFingerprint` | String? | `publicWallets[walletID]?.masterFingerprint` (8 hex, lowercase as stored) |
| `resultType` | String? | `"Signature created"` when this was the plan's final task, else `"Partial signature created"` (stored English) |
| `summary` | String? | `approved.summary` **truncated to 160 characters** (`prefix(160)`) |
| `networkName` | String? | `approved.networkName` (= `review.network.name`, else the "Network" field, else `chain.name`; `Models.swift:376-379`) |
| `chainID` | String? | `approved.network?.chainID` |
| `riskLevel` | String? | `approved.riskLevel.rawValue`: `"normal"`, `"warning"`, `"high"` |
| `requestJSON` | String? | canonical JSON of the frozen request that was reviewed and signed (`JSONSerialization` `.sortedKeys`, `AppStore.swift:117-122,455`), passed through `stored()` |
| `responseFrames` | [String]? | final task only: the signature QR frames, through `stored()` |
| `responseText` | String? | final task only: the export text (`encoded.text` or frames joined by `"\n"`), through `stored()` |

- `hasPayloads` = `requestJSON != nil || responseText != nil || responseFrames non-empty` (`:433`).
- `payloadLimit = 32_768` (UTF-8 bytes, per text; for frames: sum of UTF-8 bytes of all frames), and frames
  count ≤ **2,048** (`:437,454-458`). `stored()` returns nil for nil/empty/oversized → **the payload is left
  out, never truncated** (test `anOversizedPayloadIsLeftOutInsteadOfTruncated`).
- `payloadRetention = 25` (`:439`). `withoutPayloads` nils the three payload fields only (`:442-446`).
- One record is created **per signed task**: a multi-wallet (multisig) plan produces several records; only
  the final one carries response payloads (partial ones carry `requestJSON` only).
- The record never contains secrets (test `noSecretReachesTheActivityFile`: no mnemonic word, password,
  `xprv`, or `seed`).

### 2.2 Persistence (`BTS/Models/AppStore.swift`)

- Location: `Application Support/BitToolSigner/receipts.json` (`:70-73`). Loaded by `loadReceipts()` at the
  end of every successful `reload()` (`:145`) — i.e. after wallets load; on load failure `receipts = []`
  and `store.error = "The local activity record could not be read."` [not localized on iOS] (`:536-540`),
  shown by the root alert titled "Unable to Continue" with button "OK" (`BTS/Features/Main/RootView.swift:66-68`).
- **Read validation** (`:541-553`) — any violation rejects the whole file (`"Invalid activity record."`):
  file must be a regular file of ≤ `receiptFileLimit` = **2,097,152 bytes** (`:574`); JSON array decodes;
  count ≤ **100**; every record: `title.count ≤ 200`, `summary.count ≤ 200`, `requestHash` matches
  `^[0-9a-fA-F]{64}$`, and each payload is already in `stored()` form (non-empty, within limits).
- **Append** (`:554-569`): `([new] + readReceipts()).prefix(100)`; records at index ≥ 25 lose payloads;
  then *while* `JSONEncoder().encode(updated).count > 2,097,152` drop payloads from the **oldest record that
  still has them**; save; publish. If anything throws (including an unreadable existing file) →
  `error = "The local activity record could not be saved. Any completed signature remains available."`
  [not localized on iOS] (`:601`). The signature itself is unaffected. By design an unreadable journal is
  **never overwritten** by a one-entry history (`docs/Security.md:117`).
  - iOS gap to be aware of: if the file is unreadable, `receipts` is empty, so the Clear button is hidden
    and new signatures are never recorded until reinstall. Android: keep "never silently overwrite", but
    the product owner may want a recovery path (e.g. Clear available when a read error is pending). Flag.
- **Clear** (`:532-535`): `saveReceipts([])` then `receipts = []`; failure → same "could not be saved" error.
- **Write** (`:592-600`): create directory with POSIX 0700 + `FileProtectionType.complete`; set
  `isExcludedFromBackup` on the directory and the file; atomic write with `.completeFileProtection`; file
  POSIX 0600. Encoding: default `JSONEncoder` (dates = seconds since 2001-01-01). Android does not need
  format compatibility with iOS (no migration path); use epoch millis.
- Sizes are designed so a written file can always be read back: 25 × 2 × 32 KiB + 100 × 1 KiB < 2 MiB
  (test `theLimitsCannotProduceAFileTheNextLaunchWouldReject`).
- Records survive wallet deletion and show "Deleted wallet" (Privacy Policy §2; `ActivityTab.swift:139`).
- No per-record delete exists anywhere on iOS (no swipe-to-delete, no detail delete). Only "Clear".

### 2.3 Activity tab list (`BTS/Features/Activity/ActivityTab.swift:4-104`)

Tab item: label "Activity", SF `clock.arrow.circlepath`, third of four tabs (Wallets · Scan · Activity ·
Settings; `BTS/Features/Main/MainTabView.swift:15-17`). The tab exists only while ≥ 1 wallet exists
(root stage `.main`, `RootView.swift:17`).

Navigation title "Activity" (large title, may wrap to two lines).

**Empty state** (`receipts.isEmpty`, `:17-23`): `ContentUnavailableView` with label "No Signatures Yet"
(SF `signature`) and description "Signatures you create on this device are listed here. BitTool Signer
never broadcasts transactions." on the grouped background. No toolbar button.

**List** (`:25-41`, inset-grouped):
- Grouping (`:8-12`): by `Calendar.current.startOfDay(for: date)` (device time zone); sections sorted by
  day **descending**; receipts within a day by `date` descending.
- Section header: the day formatted `.dateTime.weekday(.wide).month().day().year()` in the environment
  (app) locale, e.g. en "Tuesday, Oct 20, 2026" (month abbreviated).
- Row (`ActivityRow`, `:63-88`), HStack spacing 12, vertical padding 2:
  - `ReceiptLogo` 36 pt (`:90-104`): if `chain == .evm` → `NetworkBrand.evm(chainID)` (Ethereum/BNB/
    Arbitrum/Base) else `GenericNetworkLogo`; non-EVM → chain's primary network logo.
  - Title `receipt.title` (subheadline semibold, ≤ 2 lines) [engine English, shown verbatim].
  - Subtitle (caption, secondary, 1 line): `[summary if non-empty, walletLabel(walletID) if walletID]`
    joined by `" · "`. `walletLabel` returns the wallet's current name, or the literal `"Wallet"` when the
    wallet was deleted (`AppStore.swift:151`) [verbatim, not localized in this path].
  - Spacer (min 8), then time `.dateTime.hour().minute()` (caption, monospaced digits, secondary).
  - Tap → push `ActivityDetailView(receipt)`. Test id `activity.row`.
- After the last day, an empty section whose footer reads "A local record of signatures created on this
  device. Check broadcast and confirmation in your other wallet." (`:34-37`).
- Bottom inset 30 pt.

**Toolbar** (`:45-52`): only when non-empty, trailing destructive (red) text button "Clear"
(`activity.clear`) → confirmation dialog (`:53-57`): title "Clear all activity?" (visible), destructive
action "Clear Activity" → `store.clearHistory()`, message "This only removes the local records. It does
not affect any transaction." (+ system Cancel). The dialog is dismissed whenever `store.securityEpoch`
changes (`:58`) — i.e. on background, App Lock, screen capture (`AppStore.suspend()`).

### 2.4 Activity detail (`ActivityTab.swift:111-276`)

Navigation title "Signature" (inline). Inset-grouped list, bottom inset 30. Sections **in this order**:

1. **Header** (`:122-134`, clear row background, centered, VStack spacing 10): `ReceiptLogo` 56; title
   (title3 bold, centered); summary if non-empty (subheadline secondary centered); `StatusPill` with SF
   `checkmark.seal.fill` in success green: text = stored `resultType` **verbatim** if present, otherwise
   localized "Signature created".
2. **Metadata** (`:135-147`), `LabeledContent` rows (label leading, value trailing secondary):
   - "Date" → `date.formatted(date: .abbreviated, time: .standard)` (note: iOS uses the *device* locale
     here, unlike the header/row which use the app locale; Android should use the app locale everywhere).
   - "Network" → `networkName ?? chain.name`.
   - "Wallet" (only if `walletID`) → `"Deleted wallet"` [not localized] if the wallet no longer exists, else
     `walletLabel(id)`.
   - "Fingerprint" (only if available) → `(receipt.masterFingerprint ?? publicMetadata(walletID).masterFingerprint).uppercased()`.
   - "Risk" (only if `riskLevel` present and ≠ `"normal"`) → `"High"` if `"high"`, else `"Warnings shown"` [both not localized].
3. **Request fingerprint** (`:148-154`): header "Request fingerprint"; row `HashText(requestHash)` (upper,
   8-char groups); footer "BitTool Signer does not broadcast. Whether this transaction was sent or
   confirmed is only known to your other wallet and the network."
4. **Input** (`:180-206`, only if `requestJSON != nil`): header "Input"; footer "The exact request that was
   signed. It is decoded again on this device, and its fingerprint must still match the one above."
   Rows:
   - while decoding: spinner + "Decoding again…" (secondary);
   - on error: `Label(decodeError)` with `exclamationmark.triangle.fill`, footnote, orange;
   - on success: the **essentials** (§2.5) as `LabeledContent(label) { Text(verbatim: value) }`
     (value trailing-aligned, multi-line), then a navigation row "All Decoded Fields"
     (SF `list.bullet.rectangle.portrait`, id `activity.decoded`) → §2.6;
   - always: `PayloadDisclosure(title: "Request Payload", text: requestJSON, identifier: "activity.request")`.
5. **Output** (`:242-267`, only if `responseText != nil` or `responseFrames` non-empty): header "Output";
   footer "The signature exactly as it was produced. Showing it again creates nothing new: the same code
   can be scanned by the wallet that made the request." Rows:
   - "Show QR Code" (SF `qrcode`, id `activity.showQR`) if frames non-empty → sheet §2.7;
   - if `responseText` non-empty: "Export as File" (SF `square.and.arrow.up`, id `activity.export`) →
     system file exporter: plain text, default file name **`bittool-signer-signature`** (→ `.txt`), UTF-8
     content = `responseText` (`SignedResponseDocument`, `BTS/Features/Sign/SignatureResultView.swift:100-111`);
     then `PayloadDisclosure(title: "Signature Payload", text: responseText, identifier: "activity.signature")`;
   - export failure → error's `localizedDescription` (footnote, red) below.
6. **Retention note** (`:157-162`, only if `!hasPayloads`): empty section with footer "The request and
   signature are kept for the %lld most recent signatures. This one is older, so only its summary
   remains." (`%lld` = 25). (Also appears for a record whose payloads were too large to store.)

Decoding is started by `.task(id: receipt.id)` once per detail appearance (`:168,269-275`): skipped if no
`requestJSON` or already decoded; `CancellationError` is swallowed (navigating away cancels).

### 2.5 Engine call: `review` of a stored request (`AppStore.swift:581-591`)

```
guard requestJSON as UTF-8 data, byteCount ≤ 32,768
      else error "The stored request could not be read."
request = JSON object parsed from it            // non-object → "Invalid local document."; parse error → system message
engine.run("review", ["request": request])      // FRESH context (not a warm op)
```
Exact wire JSON passed to `BitToolSignerEngine.dispatch` (`SigningEngine.swift:184-195`): keys sorted,
≤ 1,048,576 bytes (else "This request exceeds the 1 MB offline signing limit."):
```json
{"operation":"review","request":{ …the stored canonical request object… }}
```
Envelope handling (`SigningEngine.swift:74-94`): response string ≤ 2,097,152 UTF-8 bytes and must parse
to `{"ok": Bool, …}`, else "The offline engine could not process this request."; `ok:false` →
`error.code` (≤ 80 chars, default `SIGNING_FAILED`) and `error.message` (≤ 400 chars, default "This request
could not be signed.") are surfaced as the error text; `ok:true` → `result` object.

Validation of the result: `TransactionReview(result).requestHash` equals the receipt's `requestHash`
**case-insensitively** and `fields` is non-empty; otherwise "The stored request does not match this
signature's fingerprint." Nothing else is checked (unlike the live `prepare`, there is no chain-equality,
`canSign` or plan step), and **no session/epoch guard**: it works without a password and never touches the
pending request (test `theStoredRequestReviewsAgainToTheSameCommitment`). Note: the live review screen may
have shown the *plan-refined* review from `resolveSigningPlan` (e.g. verified Bitcoin change,
`AppStore.swift:395-398`); Activity shows only the plain `review` result. Keep this identical.

Result fields consumed (`Models.swift:381-401`): `chain`, `title` (default "Review request"), `summary`,
`fields[{label,value}]`, `warnings[String]`, `requestHash`, `decodeStatus`, `riskLevel` (default normal, or
warning if warnings exist), `asset{…}`, `network{name, chainId, symbol}`, `display{action, amount, symbol,
headline, counterpartyLabel, counterparty, fee, feeKind, note, assetKind, assetContract,
assetContractLabel, tokenId, give, receive, others}`, `contract{address, role, known, name, protocol,
category, everyChain, token}`. Parsing rules are shared with the Review screen spec.

**Essentials** (`ActivityTab.swift:216-238`, test `theDecodedEssentialsNameTheActionAmountAndCounterparty`),
in order:
1. id `action`, label "Action", value `display?.action ?? review.title` (verbatim English on iOS — the
   live review screen translates it with `ReviewText.phrase`; recommend Android does the same here).
2. if `display.amount`: id `amount`, label "Amount", value `[amount, symbol].compactMap.joined(" ")`
   (e.g. "Unlimited USDT").
3. if `display.counterparty`: id `counterparty`, label = localized lookup of `display.counterpartyLabel ??
   "To"` (engine's own caption: "Spender", "Operator", "To", …; falls back to the English text when no
   translation), value `AddressFormatter.short(counterparty, leading: 6, trailing: 6)`.
4. if `display.fee`: id `fee`; if `display.feeKind == "energyBudget"` → label "Energy budget", value
   `ReviewText.shortDecimal(fee)` (≤ 8 decimals, **rounded up** away from zero; `BTS/Models/ReviewText.swift`);
   else label "Network fee", value `fee` unchanged.
No display → only the Action line (value = title).

### 2.6 "Signed Request" page (`ActivityTab.swift:280-304`)

Title "Signed Request" (inline). Scrolling VStack, spacing 16, horizontal padding 20, bottom 20, readable
width 640, grouped background. Content = the Review screen's own components with the re-reviewed
`TransactionReview` (nothing here can sign):
1. `ReviewSummaryCard(review)` (`BTS/Features/Sign/RequestReviewView.swift:221`).
2. `ContractNotice(contract)` if `review.contract != nil` and `!ReviewSummaryCard.showsNotice(for:)` (i.e.
   the summary does not already show that address as counterparty; `RequestReviewView.swift:241-244,547`).
3. One `Callout(verbatim: warning)` per warning: tone danger if `warning.uppercased()` starts with "HIGH",
   else warning.
4. `ReviewDetailsCard(fields)` (`RequestReviewView.swift:694`).
Reuse the Android Review components from the review spec unchanged. Pass the already-decoded review (do
not re-run the engine) — scope the decoded result to the detail screen's ViewModel.

### 2.7 Signature QR sheet (`ActivityTab.swift:307-330`)

Modal sheet with its own nav bar: title "Signature" (inline), confirmation toolbar button "Done". Scroll
content (padding 20, readable width 520, spacing 20): `QRCodeView(frames: responseFrames, maxSide: 320)`
(top padding 8), then info `Callout("This is the signature that was already created. Scanning it again signs
nothing new.")`. Not auto-dismissed on background (privacy cover hides it).

### 2.8 Accessibility / ids

`activity.row`, `activity.clear`, `activity.decoded`, `activity.request`(+`Toggle`/`Copy`),
`activity.showQR`, `activity.export`, `activity.signature`(+`Toggle`/`Copy`). Map to Compose `testTag`s.

---

## 3. Settings root (`BTS/Features/Settings/SettingsTab.swift:4-112`)

Title "Settings" (large). Grouped `Form`. Sections **in this order**:

### 3.1 Security — App Lock (`:23-42`)

- Header "Security".
- Toggle, label "%@ Lock" with `%@` = biometry name ("Face ID" | "Touch ID" | "Optic ID"; "Face ID" when
  none) and SF icon `faceid`/`touchid`/`opticid` (`AppAccessController.swift:13-51`). Id `settings.appLock`.
  - `isOn` reflects `access.isEnabled`; flipping calls `changeAppLock` (`:103-111`): ignored while a change is
    in flight; clears the error; `access.setEnabled(new)`; non-cancellation errors appear below as footnote
    red text.
  - Disabled when: a change is in flight **or** there are no wallets **or** (biometrics unavailable **and**
    lock currently off) — so an enabled lock can always be turned off.
- Footer: biometrics available → "Require %@ to open BitTool Signer at launch and after 2 minutes in the
  background. Every signature still needs the wallet’s password." (`%@` = biometry name); unavailable →
  "Set up Face ID or Touch ID in iOS Settings to lock BitTool Signer. Every signature always needs the
  wallet’s password."
- Semantics (`AppAccessController.swift:98-189`): preference key `appLockEnabled.v1` (bool); enabling requires
  a wallet ("Create a wallet before enabling App Lock.") and a successful biometric check (reason "Lock
  BitTool Signer with %@." [not localized]); disabling needs no check; both refused while locked/background
  ("Authenticate app access before changing App Lock."); concurrent auth → "App authentication is
  unavailable right now."; **background grace 120 s** measured with a monotonic clock that keeps running in
  sleep; no device-passcode fallback; turning off when wallets reach 0 is automatic. Full lock-screen
  behaviour belongs to the App Lock spec.
- Android: biometric product names are not exposed → **new keys** (see §12.3): label "Biometric Lock",
  footers "Require biometrics to open BitTool Signer at launch and after 2 minutes in the background. Every
  signature still needs the wallet’s password." / "Set up a fingerprint or face unlock in Android Settings to
  lock BitTool Signer. Every signature always needs the wallet’s password." Availability =
  `BiometricManager.canAuthenticate(BIOMETRIC_STRONG) == BIOMETRIC_SUCCESS`; prompt with `BIOMETRIC_STRONG`
  only (no `DEVICE_CREDENTIAL`), negative button "Cancel". Grace timer: `SystemClock.elapsedRealtime()`.

### 3.2 Appearance (`:44-65`)

- Header "Appearance"; footer "The interface language. What a signing request itself says is decided by the
  request and stays as it is."
- **Theme** picker (menu style; label "Theme", SF `circle.lefthalf.filled`; id `settings.appearance`):
  options "System" / "Light" / "Dark" → stored string `"system"` | `"light"` | `"dark"` under key
  `appearance` (default `"system"`). Applied app-wide with `preferredColorScheme(nil|.light|.dark)`
  (`BTS/BitToolSignerApp.swift:36`) and also to the privacy-shield window (`:37-39`,
  `BTS/Services/PrivacyShield.swift:6-27`), so the cover/lock screens follow the chosen theme.
- **Language** picker (label "Language", SF `globe`; id `settings.language`): first option "System"
  (localized), then each language's **endonym** (never translated), stored as the raw value under key
  `language` (default `"system"`). See §3.5.
- Effect of a language change: the whole tree is rebuilt via `.id(language.rawValue)` and **returns to the
  first tab** (`AppLanguage.swift:95-106`). Android recreates the activity; keeping the user on Settings is
  the Android norm and acceptable.

### 3.3 Reference (`:67-74`)

Header "Reference":
- "Networks, Tokens & Contracts" (SF `circle.hexagongrid`) → §4.
- "Compatibility" (SF `checkmark.shield`) → §6.

### 3.4 About (`:76-97`)

Header "About":
- `LabeledContent` "Version" → `"\(CFBundleShortVersionString) (\(CFBundleVersion))"`, defaults "1.0" / "1";
  current iOS value "1.0.0 (1)" (`:13-18`).
- "Network connections" → "None".
- Navigation row "Fully Open Source" → §9 (`settings.openSource`).
- "Contact Us" → §8 (`settings.contact`).
- "Terms of Use" → §10 (`settings.terms`).
- "Privacy Policy" → §10 (`settings.privacy`).
Row text uses the normal body colour (not tinted; `IMPLEMENTATION_STATUS.md:983-984`).
Footer (centered, top padding 24, bottom 28, spacing 10): `BrandTile(size: 44, shadow: false)` and "Air-gapped
signing. For everyone."

### 3.5 Interface languages (`BTS/Models/AppLanguage.swift`)

Picker order and endonyms (`:9-60`):

| raw value | endonym | | raw value | endonym |
| --- | --- | --- | --- | --- |
| `system` | "System" (localized) | | `fr` | Français |
| `en` | English | | `it` | Italiano |
| `zh-Hans` | 简体中文 | | `pt-BR` | Português (Brasil) |
| `zh-Hant` | 繁體中文 | | `pt-PT` | Português (Portugal) |
| `ja` | 日本語 | | `pl` | Polski |
| `ko` | 한국어 | | `id` | Bahasa Indonesia |
| `ru` | Русский | | `tr` | Türkçe |
| `uk` | Українська | | `th` | ภาษาไทย |
| `es` | Español (España) | | `vi` | Tiếng Việt |
| `es-419` | Español (Latinoamérica) | | `fil` | Filipino |
| `de` | Deutsch | | `ar` | العربية |

- Only languages with a bundled localization are offered (`selectable`, `:65-67`); all 21 are bundled.
  (Do **not** copy Ownbit's endonyms "繁体中文"/"русский" — BitTool Signer uses "繁體中文"/"Русский".)
- `locale` = `Locale(identifier: raw)`; system → device locale.
- Layout direction: Arabic → RTL (everything mirrors, back buttons, row leading edges); other explicit
  languages → LTR; system → direction of the device language (`:73-79,108-110`).
- `AppText.bundle` (`:121-127`): model-layer strings (errors) are resolved in the **chosen** language bundle.
- Engine output (review text) is English by design and translated at draw time (`ReviewText`); the Terms and
  Privacy Policy are English by decision.

---

## 4. Networks, Tokens & Contracts (`BTS/Features/Settings/TokenDirectoryView.swift`)

### 4.1 Data

`BTS/Resources/token-registry.json` (889,414 bytes; schemaVersion 2, `registryVersion` `"2026-09-22.1"`):
decoded fields `registryVersion`, `networks[]`, `assets[]` (`:84-88`); other top-level keys (`sources`,
`selection`, `coinmarketcap`, `counts`, …) and asset keys (`metadataSource`, `cmcId`, …) are ignored.
- `TokenRegistryEntry` (`:6-16`): `chainFamily`, `chainId?`, `assetAddress`, `name`, `symbol`, `decimals`,
  `icon` (16-hex id), `rank?`; `id = "chainFamily:chainId:assetAddress"` (empty string for nil chainId).
- `TokenRegistryNetwork` (`:20-40`): `chainFamily`, `chainId?`, `key`, `name`, `nativeSymbol`, `tokens`;
  `chain = chainFamily + ":" + (chainId ?? "")`; `brand` = evm→`NetworkBrand.evm(chainId)`, tron, solana,
  else nil; `shortName = brand?.shortName ?? name`.

`BTS/Resources/known-contracts.json` (283,866 bytes; schemaVersion 1, `updated` `"2026-10-09"`): decoded
`updated`, `contracts[]` (`:90-93`). `KnownContractEntry` (`:45-82`): `chainFamily`, `chainId?` (`"*"` =
every EVM chain), `address`, `name`, `protocol` (Swift property `project`), `category`;
`id = "chainFamily:chainId:address"`; `everyChain = chainId == "*"`.

Shipped content (verified against the files):

| Network (file order) | key | native | tokens | contracts | logo |
| --- | --- | --- | ---: | ---: | --- |
| Ethereum (evm 1) | `ethereum` | ETH | 1,008 | 329 | Ethereum |
| BNB Smart Chain (evm 56) | `smartchain` | BNB | 876 | 194 | BNB |
| Arbitrum One (evm 42161) | `arbitrum` | ETH | 225 | 186 | Arbitrum |
| Base (evm 8453) | `base` | ETH | 333 | 217 | Base |
| TRON | `tron` | TRX | 26 | 139 | TRON |
| Solana | `solana` | SOL | 585 | 262 | Solana |
| OP Mainnet (evm 10) | `optimism` | ETH | 53 | 169 | generic |
| Polygon PoS (evm 137) | `polygon` | POL | 137 | 191 | generic |
| Gnosis (evm 100) | `gnosis` | xDAI | 32 | 0 | generic |
| Avalanche C-Chain (evm 43114) | `avalanche` | AVAX | 75 | 6 | generic |
| (every EVM network, `evm:*`) | — | — | — | 27 | generic |

Totals: **3,350 tokens over 10 networks**, **1,720 known contracts** (307 projects). Categories (11):
token 329, dex 316, bridge 308, lending 215, aggregator 186, staking 110, infra 83, nft 67, other 42, perp
35, wallet 29. Provenance & meaning: `docs/TokenRegistrySources.md`, `docs/KnownContracts.md` (a listing
identifies a deployment, **not** an endorsement or safety check; EVM addresses lowercased; TRON/Solana keep
case). The engine embeds its own compact copies inside `signer.js`; this screen reads the app-bundle copies.

### 4.2 Loading (`TokenDirectory`, `:97-147`)

A fresh `TokenDirectory` per opening of the page (`@StateObject`), `load()` once: both files decoded off the
main thread; tokens grouped by `chain` keeping file order (network, then rank); contracts grouped by `chain`
and each group sorted by `(project, name)` (plain string `<` ordering); `contractCount` = total;
`everyEVMChain = "evm:*"`. Missing/undecodable file → that half stays empty (no error UI). Until loaded the
page shows only the Bitcoin row and footers with empty interpolations ("Registry ."). Android: load once into
a process-wide repository (Dispatchers.Default) and show a short skeleton/progress instead of empty footers.

### 4.3 Directory page (`:151-236`)

Title "Networks, Tokens & Contracts" (inline). Inset-grouped, bottom inset 30.

Section 1, header "Networks":
- Static (non-navigable) Bitcoin row first: `NetworkLogo(.bitcoin, 32)`, "Bitcoin" (body), "BTC · Single-sig
  and multisig" (caption secondary).
- One navigation row per registry network in file order (id `network.<key>`): logo 32 (brand or generic),
  `network.name` (body), summary (caption secondary, one line) = `nativeSymbol` + (`" · "` + "%lld tokens"
  if > 0) + (`" · "` + "%lld contracts" if > 0), e.g. "ETH · 1008 tokens · 329 contracts" (iOS `%lld` is
  ungrouped; keep `%d`), Gnosis "xDAI · 32 tokens". Tap → network page (§4.4) titled `shortName`
  (Ethereum, BNB Chain, Arbitrum, Base, TRON, Solana, OP Mainnet, Polygon PoS, Gnosis, Avalanche C-Chain).
- Footer: "Recognized tokens are shown with their name, icon and decimals when you review a request, entirely
  offline. Unknown tokens are shown by contract address with raw amounts. A listed token is not an
  endorsement. Registry %@." (`%@` = `registryVersion`).

Section 2 (only if every-chain contracts exist), header "Known Public Contracts":
- Row (id `contracts.everyChain`): generic logo 32, "All EVM networks", caption "%lld contracts at the same
  address on every network" (27). Tap → network page titled "All EVM networks" with tokens = [] and those
  contracts.
- Footer: "%lld contract addresses published by the projects themselves. When a request authorizes one of
  them, the review names it; when it authorizes an address that is not listed, the review says so. A
  listing identifies a deployment. It is not a judgement about the request. Updated %@." (`%lld` = 1720,
  `%@` = `updated` raw ISO date "2026-10-09").

### 4.4 Network page (`NetworkReferenceView`, `:241-305`)

- Shows **tokens** initially, or **contracts** when the network has no tokens (`:250`).
- Segmented control (only when both lists are non-empty; id `reference.picker`; label hidden), padding
  h 20 / v 8: "%lld Tokens" | "%lld Contracts" — counts are the **unfiltered** totals.
- Search field always visible under the title (`.searchable(... .navigationBarDrawer(displayMode: .always))`);
  placeholder "Symbol, name or address" on tokens, "Name, project or address" on contracts. The query is
  shared across the two lists. Normalisation: trim spaces/tabs (not newlines) and lowercase.
  - tokens match if `symbol` **contains** q, or `name` **contains** q, or `assetAddress` **has prefix** q;
  - contracts match if `name`, `project`, `category` or `address` **contains** q.
  iOS filters synchronously on every keystroke (no debounce).
- Plain list (`listStyle(.plain)`); empty result → system "no results" view for the query
  (`ContentUnavailableView.search(text:)`: "No Results for “q”" / "Check the spelling or try a new search."
  — system strings, not in the app catalogue → **new Android keys**).
- **TokenRow** (`:307-329`): `TokenIcon` 36; HStack spacing 6: symbol (body semibold) + name (subheadline
  secondary, 1 line); below it `AddressFormatter.short(assetAddress, leading: 8, trailing: 6)` (caption
  monospaced secondary); trailing "%lld dp" (caption2, tertiary) with a11y label "%lld decimals". Long-press
  context menu "Copy Contract Address" (SF `doc.on.doc`) copies the full address.
- **ContractRow** (`:333-358`): category glyph (body, tint) in a 36 pt circle filled accent @ 12 % (a11y
  hidden); name (body semibold, ≤ 2 lines); `"\(project) · \(category)"` (caption secondary, verbatim);
  short address (8/6, caption monospaced secondary); a11y combined; context menu "Copy Contract Address".
- Category glyphs (`:65-81`, must stay distinct per category — test `everyCategoryHasItsOwnGlyph`):
  aggregator `arrow.triangle.merge`, bridge `arrow.left.arrow.right`, dex `arrow.2.squarepath`, infra
  `gearshape.2.fill`, lending `building.columns.fill`, nft `photo.artframe`, perp
  `chart.line.uptrend.xyaxis`, staking `lock.shield.fill`, token `circle.hexagongrid.fill`, wallet
  `wallet.pass.fill`, anything else (incl. `other` and future categories) `square.grid.2x2.fill`.

---

## 5. Token icon store (`BTS/Services/TokenIconStore.swift`)

- `TokenIcons.pack` (11,205,605 bytes) = concatenated PNGs; `TokenIcons.index.json` (67,531 bytes) =
  `{ "<16-hex id>": [offset, length] }`, **1,985** images for 3,350 tokens (id = first 16 hex of the image's
  SHA-256; identical artwork on several chains stored once). Images ≤ 128 px.
- Pack is memory-mapped (only touched pages load); index parsed once; `NSCache` count limit **200**.
- `image(id)`: nil for nil/empty id, unknown id, `range.count != 2`, `offset < 0`, `length ≤ 0`, or
  `offset+length > pack size`; decode failure → nil (then the lettered placeholder). Never any network.
- Tests to port: every registry icon id resolves and the pack holds exactly the ids used; USDT on Ethereum
  and TRON share one id; the bytes hash to the id; `"0000000000000000"` and `"../../etc/passwd"` → nil.

---

## 6. Compatibility (`SettingsTab.swift:143-219`, `WalletDirectoryView.swift`, `BTS/Models/CompatibleWallets.swift`)

Title "Compatibility" (inline) for both tabs (WalletDirectoryView also declares "Wallets" — on Android keep
one top bar titled "Compatibility"). A segmented control pinned at the top (bar material background, padding
h 20 / v 8, id `compatibility.tab`, a11y label "Compatibility"): "Formats" | "Wallets", switching animated
(`snappy 0.2 s`). Default tab: Formats.

### 6.1 Formats tab (`:169-218`)

Inset-grouped list:
1. Info callout (no row background): "BitTool Signer uses open QR standards. The wallets below support these
   formats according to their documentation, source code or official QR hardware-wallet guides (September
   2026). Versions change, so always compare addresses in both apps before receiving funds."
2. One section per chain, in order Bitcoin, EVM, TRON, Solana (`item(_:formats:signing:)`, `:199-218`):
   `ChainGlyph` 36 + `chain.longName` (headline; "Bitcoin", "Ethereum & EVM", "TRON", "Solana");
   `DetailLine` "QR formats" = formats (**verbatim**, not localized); `DetailLine` "Signing" = prose
   (localized); then `WalletGroup` "Watch and sign by QR" (SF `qrcode.viewfinder`) with the signing wallets,
   and `WalletGroup` "Watch only" (SF `eye`) with the watch-only wallets (each group only if non-empty;
   vertical padding 4).

| Chain | QR formats (verbatim) | Signing (localized key) |
| --- | --- | --- |
| Bitcoin | `crypto-account (account xpub), crypto-psbt, BBQR, Base64 PSBT` | "PSBT v0/v2: single-sig (Legacy, Nested, Native SegWit, Taproot) and multisig (P2SH, P2SH-P2WSH, P2WSH, Taproot script path). MuSig2 is not supported." |
| EVM | `crypto-hdkey (EIP-4527), eth-sign-request / eth-signature` | "Legacy, EIP-2930 and EIP-1559 transactions, personal messages and EIP-712 typed data, including Safe, Permit2 and NFT marketplace orders." |
| TRON | `Address QR (any index), tron-sign-request / tron-signature` | "TRX and TRC-20 transfers, contract calls, permission updates and Stake 2.0 resources. No mainstream wallet sends TRON transactions to a QR signer in this format yet." |
| Solana | `crypto-multi-accounts, sol-sign-request / sol-signature` | "Legacy and v0 transactions with static accounts, and messages. Address lookup tables are not supported offline." |

Per-chain wallets = `CompatibleWallets.all(for:)` (`CompatibleWallets.swift:165-175`: lists joined in order,
de-duplicated by name, first occurrence wins; signing lists come first):

| Chain | Watch and sign by QR (name — platforms) | Watch only |
| --- | --- | --- |
| Bitcoin | Sparrow — Desktop; BlueWallet — iOS · Android; Nunchuk — iOS · Android; Bitcoin Keeper — iOS · Android; BULL Bitcoin — iOS · Android; Specter — Desktop; Bitcoin Safe — Desktop; ZEUS — iOS · Android | Electrum — Desktop · Android |
| EVM | MetaMask — Extension · iOS · Android; Rabby — Extension · iOS · Android; imToken — iOS · Android; TokenPocket — iOS · Android; Ambire — Extension | OKX Wallet — iOS · Android · Extension; Trust Wallet — iOS · Android |
| TRON | — | TokenPocket — iOS · Android · Extension; TronLink — iOS · Android; OKX Wallet — iOS · Android; OneKey — iOS · Android |
| Solana | Solflare — Extension · iOS · Android; Helium Wallet — iOS · Android | Phantom — Extension · iOS · Android; Backpack — Extension; Jupiter — Extension · iOS · Android; OKX Wallet — iOS · Android · Extension; TokenPocket — iOS · Android |

3. Wallet Level section (`:183-194`): SF `square.grid.2x2.fill` (title3, tint, 36×36, a11y hidden) + "Wallet
   Level · all chains" (headline); `DetailLine` "QR format" = `bittool-signer-wallet v1 (ur:bytes, canonical
   CBOR): an account xpub for each Bitcoin address type, for EVM and for TRON, and all three Solana addresses,
   in one animated QR` (verbatim, not localized); `WalletGroup` "Works with" (SF `qrcode.viewfinder`) =
   [BitTool Wallet — iOS].

Platform strings ("Desktop", "Extension", "iOS · Android", …) and wallet names are verbatim data. (If
BitTool Wallet ships on Android, its platforms string is a data update for the owner, not a port change.)
The per-export lists used by the Connect page (`CompatibleWallets.wallets(chain:profile:mode:)`,
`:136-149`) belong to the Connect spec but live in the same catalogue — port the catalogue once in a core
module.

### 6.2 Wallets tab — directory (`WalletDirectoryView.swift:9-60`)

Inset-grouped list:
1. Two callouts (no row backgrounds): warning "Every wallet here supports, or says it supports, BitTool
   Signer's QR codes. Anyone can submit a wallet and claim that. We have not fully verified these claims and
   we do not audit, endorse or vouch for any of these wallets." then info "This page is information only.
   Check any wallet yourself before trusting it with money. BitTool is not responsible for anything that
   happens through another wallet — bugs, hacks, scams or losses of any kind."
2. Section header "%lld wallets" (24); one row per wallet of `CompatibleWallets.directory` (`:153-162`: union
   of all lists, a wallet that signs anywhere is listed once **as a signer**; for watch-only duplicates the
   *last* occurrence wins; sorted `localizedCaseInsensitiveCompare`). Row (`:41-59`, v-padding 4, id
   `directory.wallet.<name>`): `WalletAppIcon` 34 + name (subheadline semibold) and platforms (caption
   secondary), trailing `SupportBadge` (`:64-84`; caption2 semibold, title+icon, trailing-aligned,
   wraps): signing → "Watch and sign by QR" `qrcode.viewfinder` green; watch-only → "Watch only" `eye`
   secondary. Below: `CopyableText(site, label: "Copy address")`. Footer "The app never opens a browser. Copy
   the address and open it on another device."
3. Section: navigation row "Submit a Wallet" (SF `envelope`, id `directory.submit`) → §7.

Directory content (exact order; S = signing badge, W = watch only; site):
Ambire S ambire.com · Backpack W backpack.exchange · Bitcoin Keeper S bitcoinkeeper.app · Bitcoin Safe S
bitcoin-safe.org · BitTool Wallet S bittool.com/wallet · BlueWallet S bluewallet.io · BULL Bitcoin S
bullbitcoin.com · Electrum W electrum.org · Helium Wallet S wallet.helium.com · imToken S token.im · Jupiter W
jup.ag · MetaMask S metamask.io · Nunchuk S nunchuk.io · OKX Wallet W web3.okx.com (platforms "iOS ·
Android") · OneKey W onekey.so · Phantom W phantom.com · Rabby S rabby.io · Solflare S solflare.com · Sparrow S
sparrowwallet.com · Specter S specter.solutions · TokenPocket S tokenpocket.pro (platforms "iOS · Android") ·
Trust Wallet W trustwallet.com · TronLink W tronlink.org · ZEUS S zeusln.com.
Asset names: `wallet_ambire`, `wallet_backpack`, `wallet_keeper`, `wallet_bitcoinsafe`, `wallet_bittool`,
`wallet_bluewallet`, `wallet_bull`, `wallet_electrum`, `wallet_helium`, `wallet_imtoken`, `wallet_jupiter`,
`wallet_metamask`, `wallet_nunchuk`, `wallet_okx`, `wallet_onekey`, `wallet_phantom`, `wallet_rabby`,
`wallet_solflare`, `wallet_sparrow`, `wallet_specter`, `wallet_tokenpocket`, `wallet_tronlink`,
`wallet_trust`, `wallet_zeus` (PNG, `BTS/Assets.xcassets/wallet_*.imageset/icon.png`). Tests to port
(`CompatibleWalletsTests.swift`): every wallet has an icon and non-empty platforms; no "Ownbit", no "Safe";
walletLevel == ["BitTool Wallet"]; per-chain lists unique with signing first.

---

## 7. Submit a Wallet (`BTS/Features/Settings/SubmitWalletView.swift`)

Title "Submit a Wallet" (inline). Also reachable from the Connect page's `SubmitWalletLink`
(`ConnectView.swift:641-652`). Inset-grouped:
1. Text (subheadline): "Does your wallet read BitTool Signer's QR codes? Send us the details and we will add
   it to the list. Anyone can submit a wallet, including its own developers."
2. Section "Email": two labelled blocks (title caption semibold secondary over `CopyableText` callout font):
   "To" → `support@bittool.com`; "Subject" → `Add supported wallet` (verbatim English, deliberately not
   localized: it is the email subject). Both copy buttons use a11y label "Copy address" (iOS quirk for the
   subject; fine to keep).
3. Section header "Include at least", rows (title subheadline semibold / detail footnote secondary, a11y
   combined):
   - "Wallet name" — "The name as it is published."
   - "Official website" — "The address the wallet itself publishes."
   - "App icon" — "A link to the image, or attach it. A square icon of at least 512 px."
   - "Source code" — "A link to the repository, if the wallet is open source."
   - "QR formats supported" — "Which of these the wallet reads: Wallet Level, and Coin Level for Bitcoin,
     EVM, TRON or Solana."
   Footer "The app never opens a browser. Copy the address and open it on another device."
4. Info callout: "Being listed is not a review or an endorsement. We do not audit the wallets people submit,
   and BitTool is not responsible for what happens through them."
No mail intent, no browser (the app opens nothing).

## 8. Contact Us — the operating entity (`SettingsTab.swift:118-141`)

Title "Contact Us" (inline). Section: `LabeledContent` "Publisher" → `BITTOOL PTE. LTD.` (id
`contact.publisher`). Section header "Contact email": `CopyableText(support@bittool.com, label: "Copy
address", font: callout)` (id `contact.email`); footer "The app never opens a mail app. Copy the address and
write to us from another device."

---

## 9. Open Source (`SettingsTab.swift:223-363`)

Title "Open Source" (inline; the About row says "Fully Open Source"). Inset-grouped, sections in order:

1. Info callout: "BitTool Signer is open source, all of it. The app, the offline signing engine and the token
   data are published under one repository, so anyone can read what the signer does with a recovery phrase —
   and rebuild it and compare the result with the copy installed here."
2. Header "Repository": the address `https://github.com/bitbill/bittool-signer-ios` (callout monospaced,
   selectable, id `openSource.repository`) and a bordered button "Copy Address" (SF `doc.on.doc`) → after
   copying "Copied" (SF `checkmark`), never resets. Footer "The app never opens a browser. Copy the address and
   open it on another device." **Android: the repository address is an owner decision** (the iOS repo, a new
   `…-android` repo, or a monorepo) — make it a `BuildConfig` field; do not invent a URL.
3. Header "This build" (`DetailLine`s, monospaced values):
   - "App version" → version string as in About;
   - "Signing engine" → lowercase hex SHA-256 of the bundled `signer.js` (current
     `6933d0d8a6f60faa8222506ac0bd7eef80eb884c9f73e31e9f354b4740f21077`);
   - "Engine size" → `"\(bytes.formatted()) bytes"` e.g. "1,181,026 bytes" (number grouped in the device
     locale; the word "bytes" is not localized on iOS);
   - "Token registry" → `registryVersion` read from the **first 512 bytes** of `token-registry.json` with the
     regex `"registryVersion":"([^"]+)"` (current "2026-09-22.1");
   - "Networking code" → "None".
   While computing: "Reading…"; failure: "Unavailable" (digest / registry). iOS quirk: engine size stays
   "Reading…" forever if the file cannot be read — Android should show "Unavailable".
   Footer: "The engine digest is the SHA-256 of signer.js as it is bundled in this app, computed on this device
   each time you open this page." Computation off the main thread, once per page instance (`:310-330`).
4. Header "Rebuild it yourself": three steps, each "%lld." (footnote monospaced secondary) + text (footnote):
   1. "Clone the repository and check out the tag for version %@." (`%@` = version string "1.0.0 (1)")
   2. "Run: cd engine && npm ci && npm run build" (note: `docs/Reproducible-Build.md` uses `npm ci
      --ignore-scripts`; the in-app text omits the flag — keep the key as is, raise with owner)
   3. "Compare the SHA-256 it prints, and engine/bundle-manifest.json, with the digest above."
   Footer: "The engine bundle is deterministic: the same sources produce the same bytes, so a digest that
   differs means the installed engine is not the published one."
5. Navigation row "Third-Party Notices" (id `openSource.notices`) → licences page; footer "The engine bundles
   open-source libraries for cryptography and encoding. Their licences and copyright notices are reproduced in
   full."

**Third-Party Notices** (`LicensesView`, `:333-363`): title "Third-Party Notices" (inline);
`THIRD_PARTY_LICENSES.txt` (92,925 bytes; "BitTool Signer JavaScript engine — third-party notices", 67
packages, entries separated by a line of `=`) read off-main, split on `"\n\n"`, each paragraph rendered
verbatim in an 11 pt monospaced, selectable text, lazily, spacing 10, padding 20. Missing file → "Third-party
notices are included with the source distribution." **Android must append the Android app's own
dependencies' notices** (Compose/AndroidX/Material, Hilt/Dagger, Kotlin stdlib/coroutines/serialization,
QuickJS wrapper + QuickJS itself, ZXing/CameraX or whatever the scanner uses, Biometric) — generate at build
time into a second asset and render both (heading per file).

What the repository publishes and how to verify (from `docs/Reproducible-Build.md`; for docs, not UI):
engine sources `engine/src/*`, locked dependencies, committed deterministic `signer.js` +
`engine/bundle-manifest.json` (sha256 `6933d0d8…1077`, bytes 1,181,026), `scripts/release-manifest.sh`
producing `SHA256SUMS.txt`, `release-manifest.json`, `clean-install.log`, `clean-build.log`. On iOS only
`signer.js` can be compared (FairPlay encrypts the binary). **On Android the APK is not encrypted**: a user
can `adb shell pm path <applicationId>`, `adb pull` the `base.apk` (no root) and run
`unzip -p base.apk assets/signer.js | shasum -a 256` — for both flavors (Play's `base.apk` still contains the
asset). Android docs should say this; the in-app steps remain the three above.

---

## 10. Terms of Use / Privacy Policy (`BTS/Features/Settings/LegalDocumentView.swift`)

- `LegalDocument.terms` → resource `TERMS_OF_USE.txt`, title "Terms of Use"; `.privacy` →
  `PRIVACY_POLICY.txt`, "Privacy Policy" (`:5-11`). The nav title is the **English** title (String, not
  localized) — the documents are English-only by decision. Pushed from Settings › About; presented as a sheet
  with a "Done" button (`legal.done`) from the Welcome page (`:106-120`).
- Parser (`:64-102`, tests `LegalDocumentTests.swift`): split on any newline keeping empty lines; trim spaces;
  a line equal to the title while nothing has been emitted is dropped; a line starting with `"Version "` while
  nothing has been emitted is a **version** block; blank line ends a paragraph; `"## "` → **heading** (prefix
  removed); `"- "` → **bullet**; anything else joins the current paragraph with a single space. Missing file →
  one paragraph "This document is missing from the app bundle."
- Rendering (`:25-53`): lazy VStack spacing 12, padding 20, readable width 640, whole page text-selectable;
  version = footnote secondary; heading = headline + 8 top padding; bullet = `•` (secondary) + callout text;
  paragraph = callout. Id `legal.terms` / `legal.privacy`.
- iOS rendering quirks: `**bold**` markers are shown literally (the block text is a plain `String`), and the
  Privacy Policy's `### Why iOS may ask about wireless data` line is not a heading (`"### "` ≠ `"## "`) and is
  merged into the next paragraph. Android: render `**…**` as bold spans and either support `### ` as a
  sub-heading or (better) remove that iOS-only subsection from the Android text.
- Structure: Terms "Version 1.1 · Effective 16 October 2026", headings `## 1. This agreement` … `## 16.
  Contact`; Privacy "Version 1.1 · Effective 16 October 2026", `## In short`, `## 1. What we collect` … `## 12.
  Contact`. Both end with `BITTOOL PTE. LTD.` / `support@bittool.com` / `https://bittool.com/signer` as three
  separate blocks after the Contact heading (test). Only URL allowed: `https://bittool.com/signer`.
- **Android needs its own texts** (owner/legal to write; keep structure + tests):
  - Terms §3 "not Apple", §4 "jailbroken device" → rooted; **§15 "The App Store"** → `play` flavor: a Google
    Play clause; `apk` flavor: drop §15 (renumber §16 → §15 or keep numbering with a direct-download clause);
    §13 references "section 15" for the address — update.
  - Privacy: "iOS file protection", "excluded from iCloud and computer backups", "Preferences are ordinary iOS
    settings and are included in a device backup" (Android: nothing is backed up if all backup is excluded),
    "### Why iOS may ask about wireless data" (drop; Android: "The app requests no Internet permission, so
    Android itself prevents any connection"), §3 "iOS Settings", §4 "Face ID and Touch ID … Secure Enclave"
    (Android biometrics/TEE), §5 "Handoff or Universal Clipboard … expires after two minutes" (Android: app
    clears its own copy after two minutes, best effort), §6 app switcher (FLAG_SECURE), §7 "Files app",
    §8 "iOS Keychain" (Android Keystore keys are removed with the app), §10 "The App Store" (play: Google
    Play; apk: direct download from bittool.com).
  - Ship per flavor: `app/src/play/assets/legal/…` and `app/src/apk/assets/legal/…`; the test claims
    ("no networking code", "Clear Activity", "two minutes", platform-security and backup claims) adapted.

---

## 11. Air-gapped device guide (onboarding only on iOS)

Not part of iOS Settings (removed in Revision 5, `IMPLEMENTATION_STATUS.md:428-429`). It is page 3 of the
introduction (`WelcomeView.swift:151-156`), shown before the first wallet is created/imported, with no Skip;
the bottom button reads "Continue" on pages 1-2 and "Get Started" (or "Done" in the unused `readOnly` mode) on
page 3; page dots a11y "Page %lld of %lld". Verbatim content:

- Hero SF `airplane`; title "Prepare Your Device"; body "For the strongest isolation, use a dedicated iPhone
  and keep its connections off."
- Points: (`airplane`) "Turn on Airplane Mode"; (`wifi.slash`) "Confirm Wi‑Fi, cellular data and Bluetooth
  are off" (U+2011 in "Wi‑Fi"); (`antenna.radiowaves.left.and.right.slash`) "If iOS asks to allow wireless
  data, choose Don’t Allow: the app never connects"; (`eye.slash.fill`) "Keep your recovery phrase private:
  anyone with it can take your funds".

Android adaptation (owned by the onboarding spec; listed here because it was requested):

| iOS key | Android |
| --- | --- |
| "For the strongest isolation, use a dedicated iPhone and keep its connections off." | **iOS-specific** → new key "For the strongest isolation, use a dedicated Android phone and keep its connections off." |
| "Turn on Airplane Mode" | reuse |
| "Confirm Wi‑Fi, cellular data and Bluetooth are off" | reuse (optionally a new key adding NFC; owner choice) |
| "If iOS asks to allow wireless data, choose Don’t Allow: the app never connects" | **iOS-specific** (mainland-China WLAN prompt; Android has none) → new key "BitTool Signer has no Internet permission, so Android itself keeps it offline" or drop the point |
| "Keep your recovery phrase private: anyone with it can take your funds" | reuse |

---

## 12. String catalogue for this area

### 12.1 Localized keys (present in all 21 translation files)

Activity: "Activity", "No Signatures Yet", "Signatures you create on this device are listed here. BitTool
Signer never broadcasts transactions.", "A local record of signatures created on this device. Check broadcast
and confirmation in your other wallet.", "Clear", "Clear all activity?", "Clear Activity", "This only removes
the local records. It does not affect any transaction.", "Signature", "Signature created", "Date", "Network",
"Wallet", "Fingerprint", "Risk", "Request fingerprint", "BitTool Signer does not broadcast. Whether this
transaction was sent or confirmed is only known to your other wallet and the network.", "Input", "Decoding
again…", "All Decoded Fields", "Request Payload", "The exact request that was signed. It is decoded again on
this device, and its fingerprint must still match the one above.", "Action", "Amount", "To", "Energy budget",
"Network fee", "Output", "Show QR Code", "Export as File", "Signature Payload", "The signature exactly as it
was produced. Showing it again creates nothing new: the same code can be scanned by the wallet that made the
request.", "The request and signature are kept for the %lld most recent signatures. This one is older, so
only its summary remains.", "Signed Request", "This is the signature that was already created. Scanning it
again signs nothing new.", "Done", "The stored request could not be read.", "The stored request does not
match this signature's fingerprint.", "Invalid activity record.", "Invalid local document.", "Unable to
Continue", "OK", "Cancel".

Shared components: "Copy Payload", "Copied", "Shown", "Hidden", "Showing the first %@ of %@ characters. Copy
includes everything.", "QR code", "Animated QR code, part %lld of %lld", "Play QR animation", "Pause QR
animation", "%lld/%lld", "EVM networks", "Copy address".

Settings: "Settings", "Security", "%@ Lock", "Require %@ to open BitTool Signer at launch and after 2 minutes
in the background. Every signature still needs the wallet’s password.", "Set up Face ID or Touch ID in iOS
Settings to lock BitTool Signer. Every signature always needs the wallet’s password.", "Appearance", "Theme",
"System", "Light", "Dark", "Language", "The interface language. What a signing request itself says is decided
by the request and stays as it is.", "Reference", "Networks, Tokens & Contracts", "Compatibility", "About",
"Version", "Network connections", "None", "Fully Open Source", "Contact Us", "Terms of Use", "Privacy
Policy", "Air-gapped signing. For everyone.", "Authenticate app access before changing App Lock.", "Create a
wallet before enabling App Lock.", "App authentication is unavailable right now.", "Authentication failed.
Try again or use a wallet password.".

Contact / Submit: "Publisher", "Contact email", "The app never opens a mail app. Copy the address and write to
us from another device.", "Submit a Wallet", "Does your wallet read BitTool Signer's QR codes? Send us the
details and we will add it to the list. Anyone can submit a wallet, including its own developers.", "Email",
"Subject", "Include at least", "Wallet name", "The name as it is published.", "Official website", "The
address the wallet itself publishes.", "App icon", "A link to the image, or attach it. A square icon of at
least 512 px.", "Source code", "A link to the repository, if the wallet is open source.", "QR formats
supported", "Which of these the wallet reads: Wallet Level, and Coin Level for Bitcoin, EVM, TRON or Solana.",
"The app never opens a browser. Copy the address and open it on another device.", "Being listed is not a
review or an endorsement. We do not audit the wallets people submit, and BitTool is not responsible for what
happens through them.".

Compatibility: "Formats", "Wallets", the four "Signing" prose keys and the info callout quoted in §6.1, "Wallet
Level · all chains", "QR format", "QR formats", "Signing", "Works with", "Watch and sign by QR", "Watch only",
the two directory callouts quoted in §6.2, "%lld wallets".

Networks: "Bitcoin", "BTC · Single-sig and multisig", "Networks", the registry footer (§4.3), "All EVM
networks", "%lld contracts at the same address on every network", "Known Public Contracts", the contracts
footer (§4.3), "%lld tokens", "%lld contracts", "%lld Tokens", "%lld Contracts", "Symbol, name or address",
"Name, project or address", "%lld dp", "%lld decimals", "Copy Contract Address".

Open Source: "Open Source", the intro callout (§9), "Repository", "Copy Address", "This build", "App version",
"Signing engine", "Engine size", "Token registry", "Networking code", "Reading…", "Unavailable", "The engine
digest is the SHA-256 of signer.js as it is bundled in this app, computed on this device each time you open
this page.", "Rebuild it yourself", "%lld.", "Clone the repository and check out the tag for version %@.",
"Run: cd engine && npm ci && npm run build", "Compare the SHA-256 it prints, and engine/bundle-manifest.json,
with the digest above.", "The engine bundle is deterministic: the same sources produce the same bytes, so a
digest that differs means the installed engine is not the published one.", "Third-Party Notices", "The engine
bundles open-source libraries for cryptography and encoding. Their licences and copyright notices are
reproduced in full.", "Third-party notices are included with the source distribution.".

### 12.2 Shown but NOT localized on iOS (decide; recommendation in brackets)

- Stored `resultType` "Signature created" / "Partial signature created" shown verbatim (only legacy records
  without it show the localized "Signature created"). ["Partial signature created" is not a key → store an
  enum code and localize at display; add the key.]
- "Deleted wallet", "High", "Warnings shown" (`ActivityTab.swift:139,145`). [Add keys + translations.]
- Row subtitle fallback `"Wallet"` for deleted wallets (`AppStore.swift:151`). [Use the localized "Wallet",
  or better "Deleted wallet" for consistency with the detail.]
- "The local activity record could not be read." / "The local activity record could not be saved. Any
  completed signature remains available." [Add keys.]
- Engine-produced `title`, `summary`, `display.action`, review warnings (English by design; translate at draw
  time via the `ReviewText` mechanism where a phrase exists).
- QR-format strings, wallet names/platforms/sites, publisher, email, subject, repository URL, network names,
  project/category in contract rows, engine digest, version strings — data, keep verbatim.
- "%@ bytes" in Engine size ("bytes" is literal). [Optional new key.]
- Legal documents and their titles: English only by decision.
- System-provided iOS strings that need Android keys: search empty state "No Results for “%@”" / "Check the
  spelling or try a new search."; search hint/clear content descriptions.

### 12.3 New Android keys (need all 20 translations)

"Biometric Lock"; "Require biometrics to open BitTool Signer at launch and after 2 minutes in the background.
Every signature still needs the wallet’s password."; "Set up a fingerprint or face unlock in Android Settings
to lock BitTool Signer. Every signature always needs the wallet’s password."; Android variants of the two
iOS-worded biometric errors ("Biometric authentication is unavailable. Check Face ID or Touch ID in iOS
Settings, or use a wallet password." and "Biometrics are locked after too many attempts. Use a wallet
password, or unlock your iPhone with its passcode first."); the onboarding replacements in §11; the
§12.2 additions; and the flavor/build rows proposed in §14.8 ("Distribution", "Direct download (APK)",
"Google Play", "Signing certificate").

---

## 13. Persistence keys (preferences)

| iOS key (UserDefaults) | Values | Android |
| --- | --- | --- |
| `appearance` | `system` / `light` / `dark` | DataStore/SharedPreferences, same values |
| `language` | `system` or a raw value of §3.5 | **source of truth = AppCompat application locales** (empty list = System); optionally mirror the raw value |
| `appLockEnabled.v1` | Bool | same key |
| `onboardingVersionCompleted` | Int | same key (onboarding spec) |

---

## 14. Android mapping

### 14.1 Module placement (Ownbit 5.0 layout)

- `:core:model` — `SigningReceipt` (+ `ResultType` enum), `Chain`, `NetworkBrand`, `TransactionReview`
  family (shared with the Review spec), `CompatibleWallet`.
- `:core:engine` (QuickJS) — `SigningEngine.run("review", …)` uses a **fresh** context; same 1 MiB request /
  2 MiB response bounds and error envelope handling.
- `:core:data` — `ActivityRepository` (encrypted journal, `Mutex`, `StateFlow<List<SigningReceipt>>`,
  `append`, `clear`, `reviewStored(json, expectedHash)`), `ReferenceDataRepository` (registry + known
  contracts, lazy, cached for the process), `TokenIconStore`, `CompatibleWallets` catalogue, `BuildInfo`
  (engine digest, registry version, distribution, signing cert).
- `:core:datastore` — appearance, app-lock flag, onboarding version.
- `:core:l10n` — `AppLanguage` (endonyms of §3.5), `LanguageController` (AppCompat locales), generated
  `strings.xml` for 21 locales, and a generated **English-text → resId map** for engine phrases
  (`ReviewText.phrase/label/headline` port), since Android resources are not keyed by English.
- `:core:designsystem` — Callout, StatusPill, DetailLine, CopyableText, HashText, PayloadDisclosure, QrCode
  (ZXing core `QRCodeWriter`, EC M/L rule, `FilterQuality.None`), NetworkLogo/GenericNetworkLogo/ChainGlyph,
  TokenIcon, WalletAppIcon, WalletGroup (`FlowRow`/adaptive grid), BrandTile, grouped-section list items.
- `:feature:activity` — ActivityScreen, ActivityDetailScreen (+ViewModel holding the re-review result),
  SignedRequestScreen (reuses `:feature:sign` review components or moves them to `:core:ui`),
  SignatureQrSheet.
- `:feature:settings` — Settings, Compatibility (Formats/Wallets), SubmitWallet, Contact, OpenSource,
  Licenses, LegalDocument, NetworksTokensContracts, NetworkReference.
- `app/src/apk`, `app/src/play` — Hilt `DistributionModule` providing `DistributionInfo` and flavor legal
  assets (as Ownbit's `app/src/{apk,play}/kotlin/.../di/DistributionModule.kt`).

### 14.2 Activity on Android

- List: `LazyColumn` with **`stickyHeader`** per day (key = epoch day; header on an opaque
  `surfaceContainer` background so rows don't show through), items keyed by receipt id, footer item at the
  end, 30 dp bottom content padding plus nav-bar insets. Root top bar: `LargeTopAppBar` (or `MediumTopAppBar`)
  with `exitUntilCollapsed` scroll behaviour; action `TextButton("Clear")` in `colorScheme.error`, only when
  non-empty.
- Confirm: Material 3 `AlertDialog` (title "Clear all activity?", text = message, confirm "Clear Activity"
  in error colour, dismiss "Cancel"); dismiss it on `Lifecycle.Event.ON_STOP`, on App Lock and on capture
  (mirror `securityEpoch`).
- Empty state: centred icon (`Icons.Rounded.Draw`), title, body; no actions.
- Row: Material `ListItem` — leading 36 dp logo, headline `titleSmall` semibold max 2 lines, supporting
  `bodySmall` 1 line ellipsis, trailing time `labelMedium` with `fontFeatureSettings = "tnum"`.
- Dates: group with `ZoneId.systemDefault()`/`LocalDate`; header pattern
  `DateFormat.getBestDateTimePattern(appLocale, "EEEEMMMdy")`; time via
  `android.text.format.DateFormat.getTimeFormat(context)` (honours the 24-hour setting, uses the app-locale
  context); detail "Date" `DateTimeFormatter.ofLocalizedDateTime(MEDIUM, MEDIUM).withLocale(appLocale)`.
- Detail: grouped cards in a `LazyColumn`; small `TopAppBar` "Signature"; label/value rows with value
  end-aligned and wrapping; spinner row (`CircularProgressIndicator` 20 dp); warning row in
  `warning` colour; "All Decoded Fields" row with trailing auto-mirrored chevron. Navigation arg =
  receipt id; if the id disappears (cleared), pop back.
- Re-review in the detail ViewModel: `viewModelScope.launch(Dispatchers.Default)`; cancel on leave; pass the
  decoded review to SignedRequest via the shared ViewModel (nav-graph scoped) — no second engine call.
- Stored JSON round-trip: keep the request as a `JsonElement`/raw string (kotlinx.serialization) so large
  integers are never coerced to Double; serialize with sorted keys (iOS canonical form).
- QR sheet: `ModalBottomSheet(skipPartiallyExpanded = true)` or a full-screen dialog with top bar
  "Signature" + "Done"; keep the screen on while a QR is shown (`FLAG_KEEP_SCREEN_ON` via
  `LocalView.keepScreenOn`) — small Android polish.
- Export: `rememberLauncherForActivityResult(CreateDocument("text/plain"))`, suggested name
  `bittool-signer-signature.txt`, write UTF-8 via `ContentResolver.openOutputStream`; show failures inline in
  red. (If the user picks a cloud provider, that provider uploads — say so in the Privacy text.)
- Journal file: `noBackupFilesDir/BitToolSigner/receipts.bin`; `AtomicFile` writes; format
  `magic | version | 12-byte IV | AES-256-GCM(ciphertext+tag)` with a Keystore key
  (`KeyGenParameterSpec`, `PURPOSE_ENCRYPT|DECRYPT`, GCM, NoPadding, `setUnlockedDeviceRequired(true)` on
  API ≥ 28, `setIsStrongBoxBacked` optional). Apply all iOS limits to the **plaintext** JSON (≤ 2,097,152
  bytes, ≤ 100 records, field checks) and a ciphertext size cap before decrypting. Key invalidated / decrypt
  failure → "could not be read" path (never crash, never overwrite silently). Manifest:
  `android:allowBackup="false"`, `android:fullBackupContent="false"` (≤ API 30) and
  `android:dataExtractionRules` excluding all domains under both `<cloud-backup>` and `<device-transfer>`
  (Android 12+ ignores `allowBackup` for D2D).

### 14.3 Settings UI on Android

- Root: `LazyColumn` of grouped sections (Ownbit `SettingsSection`/`SettingsRow` style): section header
  (`titleSmall`, primary/onSurfaceVariant), rounded `surfaceContainerLow` card with `ListItem`s and
  dividers, footer (`bodySmall`, onSurfaceVariant). `LargeTopAppBar("Settings")`.
- App Lock: `ListItem` with leading `Icons.Rounded.Fingerprint`, trailing `Switch` (checked = enabled,
  disabled per §3.1; while authenticating show it disabled); error line in `error` colour.
- Theme & Language: rows with trailing current value (theme label / endonym, the language value forced LTR
  only if needed) → single-choice `AlertDialog` with `RadioButton`s for theme; a `ModalBottomSheet`
  (or full screen list) with check marks for the 22 language options. Apply immediately.
- Navigation rows: trailing `Icons.AutoMirrored.Rounded.KeyboardArrowRight`; value rows: trailing
  `bodyMedium` onSurfaceVariant. About footer: 44 dp brand tile + slogan, centred.
- Font role mapping: largeTitle→`headlineLarge`, title3→`titleLarge`, headline→`titleMedium` (semibold),
  body→`bodyLarge`, callout→`bodyMedium`, subheadline→`bodyMedium`, footnote→`bodySmall`,
  caption→`labelMedium`, caption2→`labelSmall`; monospaced → `FontFamily.Monospace` (or bundled JetBrains
  Mono).
- SF Symbol → Material Symbols (Rounded): `signature`→Draw, `clock.arrow.circlepath`→History,
  `checkmark.seal.fill`→Verified, `list.bullet.rectangle.portrait`→ListAlt, `qrcode`→QrCode2,
  `square.and.arrow.up`→SaveAlt (Android "save to file"), `exclamationmark.triangle.fill`→Warning,
  `circle.lefthalf.filled`→Contrast, `globe`→Language, `circle.hexagongrid`→Hexagon/Hub,
  `checkmark.shield`→VerifiedUser, `doc.on.doc`→ContentCopy, `checkmark`→Check, `envelope`→MailOutline,
  `eye`→Visibility, `qrcode.viewfinder`→QrCodeScanner, `square.grid.2x2.fill`→GridView,
  `point.3.connected.trianglepath.dotted`→Hub, `info.circle.fill`→Info, `exclamationmark.octagon.fill`→Report;
  categories: aggregator→CallMerge, bridge→SwapHoriz, dex→Sync, infra→SettingsSuggest,
  lending→AccountBalance, nft→Image, perp→ShowChart, staking→Security(Shield), token→Toll,
  wallet→AccountBalanceWallet, default→GridView (keep them pairwise distinct).
- Compatibility tabs: `SingleChoiceSegmentedButtonRow` pinned under the top bar (or `PrimaryTabRow`),
  `AnimatedContent` crossfade ~200 ms. Wallet grids: `FlowRow` / `LazyVerticalGrid(Adaptive(140.dp))`
  (260 dp at font scale ≥ 1.3).
- Network page: `SingleChoiceSegmentedButtonRow` (only if both lists) + a search `TextField` (or
  `DockedSearchBar`) always visible, leading search icon, trailing clear button, IME action Search;
  placeholder per tab; `LazyColumn` keyed by entry id; long-press → `DropdownMenu` "Copy Contract Address"
  with `HapticFeedbackType.LongPress`; tap does nothing (as iOS).
- **Search debouncing**: `snapshotFlow { query }.map(normalize).distinctUntilChanged().debounce(120–150 ms)
  .mapLatest { filter(it) }.flowOn(Dispatchers.Default)` (first emission immediate). Lists ≤ 1,008 rows, so
  this is about jank-free typing, not cost. Normalize: trim `' '`/`'\t'` only, `lowercase(Locale.ROOT)`
  (matches Swift's locale-independent `lowercased()`; avoids Turkish-i surprises).
- Token icons: put `TokenIcons.pack` in `assets/` **uncompressed** (`androidResources { noCompress += "pack" }`)
  so `assets.openFd()` works, then `FileInputStream(fd.fileDescriptor).channel.map(READ_ONLY,
  fd.startOffset, fd.length)`; parse the index once; decode with `BitmapFactory.decodeByteArray`
  (`inSampleSize` to the target px); `LruCache<String, ImageBitmap>(200)`; load via `produceState` on
  `Dispatchers.IO`; same bounds checks; placeholder letters as §1.
- Legal viewer: `LazyColumn` of blocks inside `SelectionContainer` (or per-block selection), max width 640 dp,
  bold-span support; title in English.
- Licences: `LazyColumn` over paragraphs, 11 sp monospace, `SelectionContainer`.
- Clipboard: `ClipboardManager.setPrimaryClip(ClipData.newPlainText(label, text))`; Android 13+ shows its own
  confirmation, below 13 show a Snackbar "Copied"; schedule an in-process clear after **120 s** if the clip is
  still ours (Ownbit `ClipboardHelper.copySensitive` pattern, token in `ClipDescription` extras). Don't mark
  public data `EXTRA_IS_SENSITIVE` (optional). Copy buttons switch to a check icon (CopyableText: stays;
  PayloadDisclosure: resets after 1.5 s) with `AnimatedContent`.
- Copy-to-clipboard and "Clear" are the only side effects; the app opens **no** browser, mail client, Play
  Store page or share sheet from these screens (the only external exit app-wide is the camera-permission
  settings intent, `docs/Security.md:135`).
- Accessibility: `Modifier.semantics(mergeDescendants = true)` where iOS combines; content descriptions per
  §1/§2.8; `testTag`s = iOS accessibility identifiers.

### 14.4 Theme override

Persist `system|light|dark`; apply with `AppCompatDelegate.setDefaultNightMode(MODE_NIGHT_FOLLOW_SYSTEM |
MODE_NIGHT_NO | MODE_NIGHT_YES)` at `Application.onCreate` and on change (requires `AppCompatActivity`; it also
themes the privacy cover/lock window and system dialogs consistently, as iOS does for its shield window);
Compose reads `isSystemInDarkTheme()`. Update edge-to-edge system-bar icon contrast
(`enableEdgeToEdge(SystemBarStyle.auto(...))`) after the change. Light/dark tokens: accent #0F7B55 / #6BE3A4,
on-accent white / #0B1F16, grouped background ≈ #F2F2F7 / #000000, QR always on white in both.

### 14.5 Per-app language

- `AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))`; "System" =
  `LocaleListCompat.getEmptyLocaleList()`. Manifest: `android:localeConfig="@xml/locales_config"` (the 21
  tags, as Ownbit: en, zh-Hans, zh-Hant, ja, ko, ru, uk, es-ES, es-419, de, fr, it, pt-BR, pt-PT, pl, id, tr,
  th, vi, fil, ar) and, for API < 33, `AppLocalesMetadataHolderService` with `autoStoreLocales=true`. The
  current picker selection = `getApplicationLocales()` (so a choice made in Android 13+ system "App
  languages" shows up in-app).
- Resource dirs: `values` (English), `values-b+zh+Hans`, `values-b+zh+Hant`, `values-es`, `values-b+es+419`,
  `values-pt-rBR`, `values-pt-rPT`, `values-in` + `values-b+id`, `values-b+fil` (+`values-tl`), others
  `values-<lang>`.
- Pitfalls (all hit by Ownbit): (a) English only in `values/` → with "System" and a device list like
  `[en-GB, de]` Android resolves **German**; ship an identical `values-en` (or pin the followed language like
  Ownbit's `LanguageManager`). (b) Per-app `en` can pick AndroidX's `en-rXC` pseudo-locale → apply `en-US`,
  and restrict `localeFilters`/`resourceConfigurations` to the 21 languages. (c) **Play App Bundles must set
  `bundle { language { enableSplit = false } }`** or `playRelease` users lack other languages' resources
  after switching. (d) Arabic digits: consider `ar-u-nu-latn` (Latin digits) like Ownbit, so counts/dates
  match the engine's Latin-digit amounts — a deliberate Android decision.
- RTL: `android:supportsRtl="true"`; Compose mirrors automatically. Force **LTR** for addresses, hashes,
  fingerprints, payloads, version/digest strings, URLs, the email, "%lld/%lld" counters
  (`CompositionLocalProvider(LocalLayoutDirection provides Ltr)` + `TextDirection.Ltr`, or Unicode isolates).
  Use `Icons.AutoMirrored.*` for chevrons/back.
- Strings outside Compose (ViewModel/repository errors) must resolve in the app locale: emit `UiText(resId,
  args)` and resolve in UI, or use the activity context (on API < 33 the Application context keeps the system
  locale).
- String generation: produce `strings.xml` from `translations/*.json` (keyed by English) with a deterministic
  resource name per key and a checked-in map; convert `%@`→`%n$s`, `%lld`→`%n$d`, keep `%%`; escape `'`, `"`,
  leading `@`/`?`; keep Unicode punctuation as is; fail the build on a missing entry or a changed
  specifier (as `scripts/write-localizations.py` does).

### 14.6 Open Source page on Android

Digest: `assets.open("signer.js")` streamed through `MessageDigest("SHA-256")` on `Dispatchers.IO`, byte
count from the stream; must equal the iOS digest because the file is byte-identical (add a unit test pinning
it to `engine/bundle-manifest.json`). Registry version: read 512 bytes of `token-registry.json` + the same
regex. Engine size grouped with `NumberFormat.getIntegerInstance(appLocale)`.

### 14.7 Manifest / offline guarantees

No `INTERNET`, no `ACCESS_NETWORK_STATE`; add `<uses-permission android:name="android.permission.INTERNET"
tools:node="remove"/>` and a Gradle/CI task that fails if any release variant's merged manifest requests a
network permission. Keep "Network connections: None" / "Networking code: None" static (optionally assert in
debug that `requestedPermissions` lacks INTERNET).

### 14.8 apkRelease vs playRelease in Settings/About

Same screens; differences:

| Item | `apk` | `play` |
| --- | --- | --- |
| Distribution row (new, Open Source › This build, or About) | "Direct download (APK)" | "Google Play" |
| Signing certificate (new row in This build) | SHA-256 of the signing cert (`PackageManager.getPackageInfo(…, GET_SIGNING_CERTIFICATES).signingInfo.apkContentsSigners`, API 28+; `GET_SIGNATURES` below), uppercase colon-separated, monospaced, copyable — compare with the fingerprint published at bittool.com/signer or `apksigner verify --print-certs` | Same row; value is the Play App Signing certificate |
| In-app update | **none** (no network; unlike Ownbit's apk flavor) | none (Play updates) |
| "Rate on Google Play" (Ownbit) | n/a | **do not add** (the app opens nothing) |
| Legal texts | no store clause / direct-download clause | Google Play clause (Terms §15, Privacy §10) |
| Verification instructions (docs) | full `base.apk` extraction | `base.apk` from the Play install still contains `assets/signer.js` |

- Version string: iOS shows "1.0.0 (1)". With an Ownbit-style `versionCode` (1.0.0 → 1000000) prefer
  `versionName` + a separate build number `BuildConfig` field so About and the "check out the tag for version
  %@" step stay meaningful.
- Use the **same `applicationId` and the same signing certificate** for both flavors (supply the release key
  to Play App Signing). Different certificates force uninstall to switch distributions, which destroys the
  encrypted wallets and the Activity journal. If they must differ, say so in the Distribution row and docs.
- `PackageManager.getInstallSourceInfo` (API 30+) can show the actual installer, but the flavor is known at
  build time; optional.

### 14.9 Tests to port

`ActivityRecordTests` (payload capture, canonical JSON, re-review equality, mismatch refusal, 25-record
retention across relaunch, no secrets in the journal, oversized payload omitted, limit arithmetic, 25 fat
records stay readable, essentials), `LegalDocumentTests` (per-flavor text), `CompatibleWalletsTests`,
`TokenRegistryBundleTests`, `KnownContractTests` (counts per chain = file `counts`, every chain reachable from
a row, sorted by project/name, distinct category glyphs), plus Android-only: merged-manifest network check,
engine digest pinned, locale resolution with `[en-GB, de]`, RTL snapshot of Activity/Settings in Arabic.

---

## 15. Out of scope (iOS-only) — drop

- Face ID / Touch ID / Optic ID naming and `LAContext` specifics (`BiometryKind` names/symbols, Info.plist
  `NSFaceIDUsageDescription`, the "Lock BitTool Signer with %@." / "Unlock BitTool Signer…" reason strings).
- "iOS Settings" / "iPhone" wording in keys (replaced by new Android keys, §12.3), the mainland-China
  wireless-data prompt guidance.
- `UIPasteboard` `.localOnly` / `.expirationDate` options (emulated in-process), Handoff/Universal Clipboard.
- `FileProtectionType.complete`, `isExcludedFromBackup` (replaced by Keystore encryption + backup rules).
- Liquid Glass / `glassProminent`, iOS 17/18 fallbacks (`wallet.bifold`/`wallet.pass`, disabled-button
  colour workaround), `listBottomInset` `contentMargins`, `.searchable` drawer placement,
  `ContentUnavailableView`, `confirmationDialog` styling, `fileExporter`/`FileDocument`, `NSCache`,
  `Data(mappedIfSafe)` (each has an Android equivalent above).
- `.id(language.rawValue)` tree rebuild and "return to first tab" on language change (Android recreation).
- `SimulatorUITestContext`, `#if DEBUG && targetEnvironment(simulator)` test defaults injection.
- App Store / TestFlight / Apple Configurator `.ipa` extraction steps, FairPlay discussion, Terms §15 and
  Privacy §10 Apple clauses, `PrivacyInfo.xcprivacy`, `InfoPlist.strings` (`infoPlist` sections of the
  translation files).
- `AppDelegate` keyboard-extension refusal and `PrivacyShield` window mechanics (covered by the
  privacy/lock spec on Android via `FLAG_SECURE` and a cover overlay).
