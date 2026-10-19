# Port spec — Wallets tab, wallet detail, addresses, pairing (Connect) and wallet security sheets

Scope: everything a user reaches from the **Wallets** tab except wallet creation/import (setup flow) and the
recovery-phrase backup panel internals (both specced elsewhere; their touch points are listed here).

Source of truth: the Swift code. All Swift paths below are relative to
`/Users/tanguopeng/Documents/bitsigner-ios/BitSigner/BitToolSigner/`; engine paths are relative to
`/Users/tanguopeng/Documents/bitsigner-ios/BitSigner/` (`engine/...`). Where the docs disagree with the code, the code
wins and the disagreement is flagged (§15).

Conventions used in this document

- **"…"** in a `L` column / marked *(L)* = a localization key: the English text is the key in
  `translations/*.json` (`strings` map). Copy it byte-for-byte, including curly apostrophes `’` vs straight `'`,
  middle dots `·`, en dashes `–`, ellipses `…` and the `#`.
- *(V)* = **verbatim on iOS** (shown through `Text(String)` / `Text(verbatim:)` / `DetailLine(value:)`): NOT looked up
  in the translations, shown in English in every language. Keep it untranslated on Android
  (`translatable="false"` or a Kotlin constant) for parity.
- Format specifiers are kept as in Swift (`%@` string, `%lld` integer). Android mapping: `%@`→`%s`, `%lld`→`%d`,
  multiple args → positional `%1$s`, `%2$d` (the zh-Hans file already uses `%1$@`/`%2$lld` positional forms).
- iOS points (pt) map 1:1 to dp; iOS text styles map as in the typography spec (body 17, subheadline 15, footnote 13,
  caption 12, caption2 11, headline 17 semibold, title2 22 bold).

---

## 1. Shared models and constants

### 1.1 `Chain` — `Models/Models.swift:3-32`

Order (= `allCases`, used for every list/picker): `bitcoin`, `evm`, `tron`, `solana`. Raw value is the wire value sent
to the engine.

| case | `name` (V) | `longName` (V) | `ticker` | primary network logo |
|---|---|---|---|---|
| bitcoin | `Bitcoin` | `Bitcoin` | BTC | ChainBitcoin |
| evm | `EVM` | `Ethereum & EVM` | EVM | ChainEthereum |
| tron | `TRON` | `TRON` | TRX | ChainTron |
| solana | `Solana` | `Solana` | SOL | ChainSolana |

`Chain.primaryNetwork` — `Design/Logos.swift:72-81`.

### 1.2 `NetworkBrand` — `Design/Logos.swift:6-70`

Order: bitcoin, ethereum, bnb, arbitrum, base, tron, solana.

| case | name (V) | shortName (V) | asset (SVG in `Assets.xcassets/<asset>.imageset/*.svg`) |
|---|---|---|---|
| bitcoin | Bitcoin | Bitcoin | ChainBitcoin (`bitcoin.svg`) |
| ethereum | Ethereum | Ethereum | ChainEthereum (`ethereum.svg`) |
| bnb | BNB Smart Chain | BNB Chain | ChainBNB (`bnb.svg`) |
| arbitrum | Arbitrum One | Arbitrum | ChainArbitrum (`arbitrum.svg`) |
| base | Base | Base | ChainBase (`base.svg`) |
| tron | TRON | TRON | ChainTron (`tron.svg`) |
| solana | Solana | Solana | ChainSolana (`solana.svg`) |

Convert the SVGs to Android VectorDrawables (they are original vector drawings, `preserves-vector-representation`).
Logos never load from the network.

### 1.3 `AccountProfile` presets — `Features/Wallets/ConnectView.swift:3-22`

All `title`/`detail` are (V).

| list | id (wire `profile`) | title | detail |
|---|---|---|---|
| bitcoinSingle | `native` | Native SegWit | `BIP84 · bc1q…` |
| bitcoinSingle | `taproot` | Taproot | `BIP86 · bc1p…` |
| bitcoinSingle | `nested` | Nested SegWit | `BIP49 · 3…` |
| bitcoinSingle | `legacy` | Legacy | `BIP44 · 1…` |
| bitcoinMulti | `multisig-native` | Native SegWit | `BIP48 · P2WSH` |
| bitcoinMulti | `multisig-nested` | Nested SegWit | `BIP48 · P2SH-P2WSH` |
| solana | `standard` | Standard | `m/44'/501'/0'/0'` |
| solana | `trust` | Trust | `m/44'/501'/0'` |
| solana | `legacy` | Legacy | `m/44'/501'` |

Registered origins (engine `engine/src/public-index.js:10-14`): native `m/84'/0'/0'`, taproot `m/86'/0'/0'`, nested
`m/49'/0'/0'`, legacy `m/44'/0'/0'`, multisig-native `m/48'/0'/0'/2'`, multisig-nested `m/48'/0'/0'/1'`, EVM
`m/44'/60'/0'`, TRON `m/44'/195'/0'`.

### 1.4 Engine-result models — `Models/Models.swift`

- `PublicAccount` (`:34-49`): `chain` (fallback bitcoin), `address`, `path`, `publicKey`, `index` (Int, default 0);
  identity `chain + ":" + path` (use as LazyColumn key).
- `PublicMetadataStatus` (`:466`): `ready | needsCompletion | invalid`. `AppStore.metadataStatus(for:)` defaults to
  `needsCompletion` (`Models/AppStore.swift:150`). "ready" ⇔ an authenticated public index exists.
- `WalletPublicMetadata` (`:470-531`): validated public index. Validation (throws, messages *(L)*):
  version == 1, `walletKeyId` 64 hex, `masterFingerprint` 8 hex, exactly 6 BTC nodes with origins = the 6 above,
  EVM/TRON origins, 100 TRON addresses with index 0..99 and path `m/44'/195'/0'/0/<i>`, 3 Solana keys with the three
  paths, every node `publicKey` 33-byte 02/03 hex, `chainCode` 32 bytes, `parentFingerprint` 4 bytes, depth 3…4,
  `xpub` prefix and < 120 chars; recursive "public only" check (forbidden keys `mnemonic, seed, privatekey,
  privateextendedkey, password, passphrase, xprv, secret`, depth ≤ 12, arrays ≤ 256, strings ≤ 2048 bytes and not
  `xprv…`), serialized sorted JSON ≤ 262,144 bytes. Stores `walletKeyId` and `masterFingerprint` **lowercased**.
  Errors: "Invalid public wallet data. Repair this wallet." / "Public wallet data is incomplete or inconsistent." /
  "Public wallet data is too large."
- `PairingPayload` (`:571-589`): `frames` (non-empty, else throws "Unable to encode public wallet information."),
  `text` (fallback `frames.joined("\n")`), `address`, `path`, `outputMode`, `note` (parsed but **never displayed**),
  `payloadView: QRPayloadView?`.
- `WalletPairingPayload` (`:535-569`): `frames` (non-empty), `payload` must be a JSON object (else "Unable to encode
  the wallet pairing."), `text`, `mode`, `payloadBytes` (Int), `firstAddresses` = `verify[]` mapped to
  `Line(chain, profile (default "standard"), address, path)` with id `chain + "/" + profile` (must include profile —
  a chain contributes several lines), `json` = pretty-printed, key-sorted, slashes **not** escaped.
- `QRPayloadView` (`:595-650`): from `payloadFormat` ∈ `json|hex|text`; nil (→ disclosure hidden) when format missing
  or payload empty/invalid.
  - `title`: json → "Payload (JSON)" *(L)*, hex → `Payload (Hex)`, text → `Payload (Text)` (both looked up as keys
    but **not present** in translations → English everywhere).
  - `text`: json → pretty JSON (same options as above); hex/text → the string.
  - `byteCount` = `payloadBytes` ?? UTF-8 length. `qrType` = result `type`.
  - `summary` (V, number formatted with the locale's grouping, `byteCount.formatted()`):
    - `bbqr-psbt` → `BBQr · PSBT (hex|Base32) · N bytes` (hex when the first frame is `B$H…`)
    - `psbt-base64` → `Plain text · PSBT Base64 · N bytes`; `psbt-hex` → `Plain text · PSBT hex · N bytes`
    - nil or `address` → `Plain text · N bytes`
    - any other type → `ur:<type> · CBOR · N bytes` (e.g. `ur:crypto-account · CBOR · 170 bytes`)

Pretty JSON format (to match iOS byte-for-byte, recommended): `JSONSerialization` `.prettyPrinted, .sortedKeys,
.withoutEscapingSlashes` = 2-space indent, `"key" : value` (space **before and after** the colon), one array element
per line, keys sorted by code point, UTF-8 unescaped, `/` not escaped. kotlinx `prettyPrint` uses `": "` — write a
tiny custom printer.

### 1.5 Compatible wallets — `Models/CompatibleWallets.swift`

`CompatibleWallet(name, asset, platforms, support: signing|watchOnly)`; `id = name`; `site` from `sites` map
(`:33-58`, shown elsewhere for copying — the app never opens a browser). Names and platforms are (V).
Icons: `Assets.xcassets/wallet_*.imageset/icon.png`, 180×180 PNG each → `drawable-nodpi` (or xxhdpi) WebP.

| list (`:68-133`) | entries (support) — `name / asset / platforms` |
|---|---|
| `bitcoinAccount` | Sparrow / wallet_sparrow / `Desktop`; BlueWallet / wallet_bluewallet / `iOS · Android`; Nunchuk / wallet_nunchuk / `iOS · Android`; Bitcoin Keeper / wallet_keeper / `iOS · Android`; BULL Bitcoin / wallet_bull / `iOS · Android`; Specter / wallet_specter / `Desktop`; Bitcoin Safe / wallet_bitcoinsafe / `Desktop` (all signing) |
| `zeus` | ZEUS / wallet_zeus / `iOS · Android` (signing) |
| `bitcoinCosigner` | Sparrow, BlueWallet, Nunchuk, Bitcoin Keeper, Specter, Bitcoin Safe (same assets/platforms; signing) |
| `bitcoinAddress` | BlueWallet / `iOS · Android`; Electrum / wallet_electrum / `Desktop · Android` (watchOnly) |
| `evmAccount` | MetaMask / wallet_metamask / `Extension · iOS · Android`; Rabby / wallet_rabby / `Extension · iOS · Android`; imToken / wallet_imtoken / `iOS · Android`; TokenPocket / wallet_tokenpocket / `iOS · Android`; Ambire / wallet_ambire / `Extension` (signing) |
| `evmAddress` | Rabby / `Extension · iOS · Android`; TokenPocket / `iOS · Android`; OKX Wallet / wallet_okx / `iOS · Android · Extension`; Trust Wallet / wallet_trust / `iOS · Android` (watchOnly) |
| `solanaAccount` | Solflare / wallet_solflare / `Extension · iOS · Android`; Helium Wallet / wallet_helium / `iOS · Android` (signing) |
| `solanaAddress` | Phantom / wallet_phantom / `Extension · iOS · Android`; Backpack / wallet_backpack / `Extension`; Jupiter / wallet_jupiter / `Extension · iOS · Android`; OKX Wallet / `iOS · Android · Extension`; TokenPocket / `iOS · Android` (watchOnly) |
| `tronAddress` | TokenPocket / `iOS · Android · Extension`; TronLink / wallet_tronlink / `iOS · Android`; OKX Wallet / `iOS · Android`; OneKey / wallet_onekey / `iOS · Android` (watchOnly) — note the per-list platform strings differ from the other lists; copy per list |
| `walletLevel` | BitTool Wallet / wallet_bittool / `iOS` (signing) |

Selector `wallets(chain, profile, mode)` (`:136-149`):

- bitcoin: profile starts with `multisig-` → `bitcoinCosigner`; else mode `address` → `bitcoinAddress`; else profile
  `legacy` → `bitcoinAccount`; otherwise `bitcoinAccount + [zeus]` (ZEUS has no legacy addresses).
- evm: `address` → `evmAddress`, else `evmAccount`. solana: `address` → `solanaAddress`, else `solanaAccount`.
- tron: `tronAddress`.

`directory` (`:153-162`) and `all(for:)` (`:165-175`) are used by Settings (alphabetical union, signing wins; per-chain
union, signing first, de-duplicated) — port them in the same model file.

Sites (`:33-58`): Sparrow sparrowwallet.com · BlueWallet bluewallet.io · Nunchuk nunchuk.io · Bitcoin Keeper
bitcoinkeeper.app · BULL Bitcoin bullbitcoin.com · Specter specter.solutions · Bitcoin Safe bitcoin-safe.org · ZEUS
zeusln.com · Electrum electrum.org · MetaMask metamask.io · Rabby rabby.io · imToken token.im · TokenPocket
tokenpocket.pro · Ambire ambire.com · OKX Wallet web3.okx.com · Trust Wallet trustwallet.com · Solflare solflare.com ·
Helium Wallet wallet.helium.com · Phantom phantom.com · Backpack backpack.exchange · Jupiter jup.ag · TronLink
tronlink.org · OneKey onekey.so · BitTool Wallet bittool.com/wallet.

### 1.6 Routes — `Features/Main/AppRouter.swift:9-15`

```
WalletRoute.detail(walletID)
WalletRoute.connect(walletID, chain)   // opens Connect on Coin Level for that chain
WalletRoute.connectWallet(walletID)    // opens Connect on Wallet Level
WalletRoute.addresses(walletID, chain) // every caller passes .bitcoin
```

Destinations (`Features/Wallets/WalletsTab.swift:45-58`): `.connect` → `ConnectView(initialChain: chain,
initialLevel: .coin, prefetched: store.preparedConnect(walletID:))`; `.connectWallet` → `ConnectView(initialLevel:
.wallet, prefetched: …)`; the prefetched wallet-level QR is passed in both cases so switching to Wallet Level is instant.

Entry points: wallet card header → detail; card "Pair" → connectWallet; card "Addresses" → addresses(.bitcoin);
detail chain rows → connect(chain); detail "Wallet Level" row → connectWallet; detail "Receive Addresses" →
addresses(.bitcoin); setup completion "Pair a Wallet" → `router.finishSetup(opening: .connectWallet(id))`
(`Features/Setup/WalletSetupFlow.swift:432`) which, after the setup sheet finishes dismissing, sets
`walletPath = [route]` (Connect becomes the only pushed screen; Back returns to the list) (`AppRouter.swift:48-57,78-81`).
`router.resetNavigation()` empties the path (after delete / when the wallet count reaches 0, `RootView.swift:53-58`).

### 1.7 Store facts used by these screens — `Models/AppStore.swift`

- `wallets` order = vault order: `createdAt` ascending, ties by UUID string ascending
  (`Core/WalletVault.swift:87-91`). Display name = public sidecar `displayName` ?? vault header name (`:133`).
- `fingerprint(for:)` (`:157-159`) = `masterFingerprint.uppercased()` or the literal `Setup required` (V, not in
  translations) — so a broken wallet shows "Fingerprint Setup required".
- `avatarIdentity(for:)` (`:153`) = `walletKeyId` (lowercase hex) or the wallet UUID string (uppercase on iOS).
- `duplicateWalletIDs` (`:140-141`): every wallet whose `walletKeyId` is shared by ≥ 2 wallets.
- `securityEpoch`: replaced by `suspend()` (`:86-94`) — called on background (`BitToolSignerApp.swift:41`), App Lock
  engaging and screen capture (`Features/Main/RootView.swift:60-61`), after delete (`:526`) and password reset
  (`:510`). `suspend()` also clears the Connect prefetch and releases the warm engine context.

---

## 2. Engine calls (exact)

Host transport (`Core/SigningEngine.swift`): request = parameters + `"operation"`, serialized with sorted keys, must be
≤ 1,048,576 bytes (else "This request exceeds the 1 MB offline signing limit."); response envelope
`{ok:true,result}` / `{ok:false,error:{code,message}}`; response ≤ 2,097,152 chars; error message truncated to 400
chars, code to 80; non-JSON/exception → "The offline engine could not process this request." (`:74-94,184-195`).
Engine error messages are shown **verbatim** in danger Callouts (they are English; not localized).

Warm context (`:124,155-160`): `derivePublicAddresses`, `createPublicPairing`, `createWalletPairing` reuse **one**
context (never sees secrets); every other operation gets a fresh context. `releaseWarmContext()` drops it (`:182`) on
`suspend()`. All calls are serialized (Swift `actor`).

All `publicIndex` values below are the wallet's stored public index object exactly as returned by
`derivePublicIndex` (`WalletPublicMetadata.raw`). If the wallet has no public metadata the store throws
"Wallet setup required." *(L)* before calling the engine.

### 2.1 `derivePublicAddresses` — `AppStore.swift:243-248`, used by Addresses (§6)

Sent:
```json
{"operation":"derivePublicAddresses","publicIndex":{…},"chain":"bitcoin|evm|tron|solana",
 "profile":"<btc profile> | \"standard\" (evm,tron) | \"\" (solana)","start":<int>,"count":<int>}
```
Consumed: `result.addresses` (array; missing → "Unable to derive public addresses." *(L)*), each →
`PublicAccount(chain, address, path, publicKey, index)`. `hasMore` / `nextIndex` are **ignored** (the UI computes
its own `hasMore`).
Engine rules (`engine/src/public-index.js:94-108`): `start ≥ 0`, `1 ≤ count ≤ 100`, `start + count ≤ 2^31`
(else `INDEX` "Invalid address page"); solana with empty profile returns the three presets (standard, trust, legacy),
each `index: 0`; bitcoin multisig profiles refused ("A cosigner key cannot define a multisig receive address");
addresses are `origin/0/i` receive branch only.

### 2.2 `createPublicPairing` — `AppStore.swift:249-253`, used by Connect › Coin Level (§7)

Sent (no `fragmentLength` → engine default **180**-byte UR fragments, `engine/protocols.js:367`):
```json
{"operation":"createPublicPairing","publicIndex":{…},"chain":"<chain>","profile":"<profile>",
 "outputMode":"<mode>","index":<addressIndex>}
```
Consumed: `frames`, `text`, `address`, `path`, `outputMode`, `note` (unused), and for the payload view `payloadFormat`,
`payload`, `payloadBytes`, `type`, `frames[0]`.

Combinations the UI can produce, with real output for the public test phrase
`abandon ×11 about` (probe of the bundled `signer.js`, SHA-256 `6933d0d8…1077`):

| chain / profile / mode | `type` | frames × chars | `path` | `address` | payloadBytes |
|---|---|---|---|---|---|
| bitcoin native accountXpub | crypto-account | 1 × 366 | m/84'/0'/0' | bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu | 170 |
| bitcoin taproot accountXpub | crypto-account | 1 × 366 | m/86'/0'/0' | bc1p5cyx…kedrcr | 170 |
| bitcoin nested accountXpub | crypto-account | 1 × 372 | m/49'/0'/0' | 37VucYSaXLCAsxYyAPfbSi9eh4iEcbShgf | 173 |
| bitcoin legacy accountXpub | crypto-account | 1 × 366 | m/44'/0'/0' | 1LqBGSKuX5yYUonjxT5qGfpUsXKYYWeabA | 170 |
| bitcoin <single> address | address | 1 × 34–62 | `<origin>/0/0` | same as above | = text bytes |
| bitcoin multisig-native (always accountXpub) | crypto-account | 1 × 370 | m/48'/0'/0'/2' | **""** (cosigner has no address) | 172 |
| bitcoin multisig-nested | crypto-account | 1 × 376 | m/48'/0'/0'/1' | "" | 175 |
| evm standard wallet | crypto-hdkey | 1 × 364 | m/44'/60'/0' | 0x9858EfFD232B4033E47d90003D41EC34EcaEda94 | 170 |
| evm standard address | address | 1 × 42 | m/44'/60'/0'/0/0 | same | 42 |
| solana standard / trust / legacy wallet | crypto-multi-accounts | 1 × 345 / 341 / 337 | preset path | HAgk14…KpqK / GjJyeC…7AhL / D2PPQS…zEi5 | 156 / 154 / 152 |
| solana <preset> address | address | 1 × 44 | preset path | same | 44 |
| tron standard address, index i | address | 1 × 34 | m/44'/195'/0'/0/i | e.g. i=0 TUEZSdKsoDHQMeZwihtdoBiN46zxhGWYdH | 34 |

UR frames are **upper-cased** (`UR:CRYPTO-ACCOUNT/OEADCY…`) so ZXing/CoreImage can use alphanumeric mode
(`engine/protocols.js:371`). Payload view of BTC native (JSON, sorted, CBOR registry names):
`{"master-fingerprint":1942346250,"output-descriptors":[{"witness-public-key-hash":{"crypto-hdkey":{…,"origin":
{"crypto-keypath":{"components":[84,true,0,true,0,true],"source-fingerprint":1942346250,"depth":3}},…,"name":
"BitTool Signer","note":"BitTool Signer public account"}}}]}`.

Engine refusals reachable only by bugs (UI never sends them): bitcoin multisig + `address` → "Unsupported Bitcoin
export mode"; non-TRON `index ≠ 0` or TRON `index ≥ 2^31` → "Invalid pairing address index"
(`engine/protocols.js:561-599`).

### 2.3 `createWalletPairing` — `AppStore.swift:305-309`, used by Connect › Wallet Level and the prefetch

Sent: `{"operation":"createWalletPairing","publicIndex":{…},"mode":"full"}` (only form; `simple` → "Unsupported
wallet pairing mode").
Engine (`engine/protocols.js:635-700`): logical object (`protocol "bittool-signer-wallet"`, `version 1`,
`mode "full"`, `network "mainnet"`, `masterFingerprint` lowercase, `accounts` = 4 BTC (`p2wpkh`, `p2tr`,
`p2sh-p2wpkh`, `p2pkh`, each `accountPath`, `script`, `xpub`, `derive {receive "0/*", change "1/*"}`), EVM, TRON
(`derive "0/*"`), `sol {profile "bip44-solana", accounts[3] {index, profile, path, address}}`) → canonical CBOR
(must be ≤ 4096 bytes) → `ur:bytes` with fragment = `min(400, ceil(len/2))` → must be ≥ 2 frames.
Consumed: `frames`, `text` (`frames.join("\n")`), `mode`, `payloadBytes`, `verify[]`, `payload` (object → `json`).
`type` (`"bytes"`) unused.
Test vector: 1561 bytes → **4 frames × 831 chars**, first frame starts `UR:BYTES/1-4/LPADAACFAMC…`.
`verify` order (9 lines): btc native `m/84'/0'/0'/0/0`, btc taproot `m/86'/0'/0'/0/0`, btc nested
`m/49'/0'/0'/0/0`, btc legacy `m/44'/0'/0'/0/0`, evm standard `m/44'/60'/0'/0/0`, tron standard
`m/44'/195'/0'/0/0`, sol standard `m/44'/501'/0'/0'`, sol trust `m/44'/501'/0'`, sol legacy `m/44'/501'`.
Full payload vector: `docs/Wallet-Pairing-v1.md` (in the iOS repo).

### 2.4 `derivePublicIndex` — Repair (§8.5), via `AppStore.completeSetup` (`AppStore.swift:213-233`)

Fresh context. Sent `{"operation":"derivePublicIndex","seed":"<128 lowercase hex>"}` where seed =
PBKDF2-HMAC-SHA512(password = NFKD(lowercased phrase, whitespace-collapsed), salt = NFKD("mnemonic"+passphrase),
2048 rounds, 64 bytes) computed natively (`Core/BIP39Seed.swift:21-48`; phrase ≤ 1024 UTF-8 bytes, passphrase ≤ 1024).
Result validated as `WalletPublicMetadata` and written to the public sidecar with the current display name, then
`reload()`.

### 2.5 Warm-context prefetch of the Wallet Level QR — `AppStore.swift:54-59,260-301`; `RootView.swift:20,59`

- Allowed only while `scenePhase == active && !appLocked && !screenCaptured && stage == .main` (wallets exist).
  `setConnectPrefetch(allowed:)` schedules or cancels.
- `scheduleConnectPrefetch()` (when allowed and the wallet load succeeded): cancel previous task; new task at
  **utility** priority; **sleep 600 ms**; then for each wallet in list order with no prefetch entry and status
  `ready`: remember `securityEpoch`; `createWalletPairing`; render all QR images at utility priority; **discard and
  stop** if cancelled, epoch changed, that wallet's index bytes changed, or image count ≠ frame count; an engine error
  just skips that wallet (`continue`).
- Stored in memory only: `[walletID: PreparedConnect(payload, images)]`. Cleared by `suspend()` and on every
  `reload()` (then re-scheduled, `:138`).
- Connect reads it once at navigation time; when present the Wallet Level page is complete on its first frame.
  Connect still issues its own `createWalletPairing` on appear (§7.6) — the result is `Equatable`-identical so
  nothing visibly changes.

---

## 3. Shared components

### 3.1 `WalletAvatar` — `Design/Logos.swift:190-220`

Deterministic identity tile (visual aid only; never used for signing decisions).
- `bytes = SHA-256(UTF-8(identity))`; `hue = (b0 << 8 | b1) / 65535`; `shift = 0.08 + b2/255 × 0.14`.
- Gradient top-leading → bottom-trailing from HSB(`hue`, 0.62, 0.78) to HSB(`(hue + shift) mod 1`, 0.70, 0.62).
  Android: `Color.hsv(hue*360f, s, v)` (HSB == HSV).
- Shape: rounded rect, corner = `size × 0.3`, continuous (squircle; Android `RoundedCornerShape(30%)` is close; a
  superellipse shape is nicer).
- Initial: first grapheme of the trimmed name, uppercased (locale-independent), else `W`. Font: rounded, semibold,
  `size × 0.44`, white, shadow black 15 % radius 1 y 1. Accessibility hidden.
- Sizes: 50 (card), 76 (detail header), 56 (identity header default), 44 (recovery sheet header).

### 3.2 Logos — `Design/Logos.swift:83-157`

- `NetworkLogo(network, size=28)`: the asset, `.interpolation(.high)`, scaled to fit, plus a circular hairline
  `primary @14%`, width `max(0.5, size/56)`; a11y label = `network.name`.
- `ChainLogo(chain, size)` = NetworkLogo of `primaryNetwork` (EVM → Ethereum logo).
- `ChainGlyph(chain, size=32)`: EVM → 2×2 grid (Ethereum, BNB / Arbitrum, Base), each `size×0.5 − 1`, spacing 2;
  a11y one element "EVM networks" *(L)*. Others → ChainLogo.
- `NetworkLogoStack(networks = all 7, size = 22, ring = Palette.card)`: overlapping row, spacing `−size × 0.28`,
  each logo padded `max(1.5, size × 0.08)` on a filled circle of the ring colour; a11y one element labelled with
  short names joined `", "` → `Bitcoin, Ethereum, BNB Chain, Arbitrum, Base, TRON, Solana` (V).

### 3.3 Address text — `Design/AddressText.swift`

- `AddressFormatter.groups(value, size=4)` (`:7-22`): a `0x`/`0X` prefix is glued to the first 4 characters
  (`0x9858 EfFD 232B …`); the rest in groups of 4.
- `AddressText` (`:43-62`): groups joined by single spaces; first and last group **semibold, primary**; middle groups
  regular, `primary @78%`; monospaced font; wraps; a11y label = groups joined `", "`. Copy always uses the raw string.
- `CopyableAddress` (`:79-109`): AddressText (leading, fills width) + borderless 30×30 button, icon `doc.on.doc` →
  `checkmark` (symbol replace) for **1.5 s**; a11y label "Copy address" *(L)* / "Copied" *(L)*; context menu
  "Copy Address" *(L)*; **success haptic** when copied. Baseline-aligned, spacing 10.
- `CopyableText` (`Features/Settings/WalletDirectoryView.swift:88-113`): same for non-address text, monospaced,
  selectable; label param default "Copied".

### 3.4 `QRCodeView` / `QRRenderer` — `Design/QRCodeView.swift`

Renderer (`:6-26`): CoreImage `qrCodeGenerator`, message = UTF-8 bytes, **correction "M" if UTF-8 length ≤ 600, else
"L"** (`:15`); 1 pixel per module; drawn with `.interpolation(.none)` (nearest neighbour, crisp). Rendering of all
frames happens off the main thread (`Task.detached`, `.userInitiated`; prefetch uses `.utility`).
Consequences: every Coin Level QR (≤ 376 chars) and every address QR is **M**; the Wallet Level frames (831 chars) are
**L**.

View (`:29-111`), parameters `frames`, `maxSide` (default 320), `interval` (default **250 ms** = 4 fps),
`initialImages` (used only if count matches frames):
- Plate: rounded rect radius **24** continuous, **white in both themes** (`.environment(\.colorScheme, .light)`),
  shadow black 8 % radius 16 y 6; QR image inset **18**; square (`aspectRatio 1`), width ≤ `maxSide`, centred.
  Gray spinner while not ready.
- `ready` ⇔ rendered frames == frames and images non-empty. On a new `frames` value: index → 0, re-render (spinner
  until done). `paused` is **not** reset by a frames change.
- Multi-frame only (`frames.count > 1`), row below the plate, spacing 12 (VStack spacing 14):
  - play/pause: bordered **circle** button, 32×32 icon frame, `play.fill` / `pause.fill` with symbol replace;
    a11y "Play QR animation" / "Pause QR animation" *(L)*.
  - linear progress `value = index+1`, `total = count`, max width 140, a11y hidden.
  - counter "%lld/%lld" *(L)* (`index+1`, `count`), caption, monospaced digits, secondary.
- Ticker: a loop while the view exists: sleep `interval`; if `!paused && ready` → `index = (index+1) % images.count`.
  Frames switch instantly (no cross-fade). Keyed on `frames.count`.
- A11y label of the image: multi → "Animated QR code, part %lld of %lld" *(L)*; single → "QR code" *(L)*.
- No brightness change and no idle-timer change on iOS (Android additions in §13.5).
- Sizes used: 300 (Coin Level), 360 (Wallet Level), 280 (address sheet), 320 (Activity), 250 (backup phrase).

### 3.5 `PayloadDisclosure` — `Design/PayloadDisclosure.swift`

Collapsed "developer view" of what the QR carries.
- Header button: title (subheadline semibold, tint) + trailing `chevron.right` rotating 0°→90° when expanded
  (`.snappy(0.25)`); a11y value "Shown" / "Hidden" *(L)*; id `<identifier>Toggle`.
- Expanded: each note (caption, secondary); payload text (caption2 monospaced, fills width, wraps, **not
  selectable**; id `<identifier>`).
- Display limit **30,000 characters**: beyond it shows the first 30,000 + `"\n…"` and the caption
  "Showing the first %@ of %@ characters. Copy includes everything." *(L)* (both numbers locale-grouped).
- When the displayed text is > 2,000 UTF-8 bytes, lines longer than 120 chars get a zero-width space (U+200B) after
  every 64 non-space characters (cheap wrapping). Copy always uses the original text.
- "Copy Payload" *(L)* (`doc.on.doc`) → "Copied" *(L)* (`checkmark`) for 1.5 s; footnote semibold tint; copies via
  the app clipboard (§3.9); id `<identifier>Copy`.

### 3.6 `ChainPicker` — `ConnectView.swift:27-78`

Custom segmented control (Connect › Coin Level and Addresses).
- Track: padding 4, background `tertiarySystemFill`, radius 18; tiles in an HStack spacing 4, equal width.
- Tile: VStack spacing 5: ChainLogo **26** (selected: saturation 1, opacity 1, scale **1.06**; unselected:
  saturation **0.35**, opacity **0.6**, scale 1) + `chain.name` (V) caption, **bold + accent** when selected,
  **medium + secondary** otherwise, 1 line, min scale 0.8; vertical padding 9.
- Selected background: accent @14 % fill + 1.5 pt accent @85 % border, radius 14, slides between tiles
  (`matchedGeometryEffect`), animation `.snappy(duration: 0.28)`.
- Names hidden at accessibility text sizes (logo + outline carry the choice). A11y label = `longName`, selected trait;
  ids `chain.bitcoin|evm|tron|solana`. **Selection haptic** on every change.

### 3.7 `ProfileMenu` — `ConnectView.swift:549-597`

Full-width row that opens a menu (picker inside).
- Row: left VStack(spacing 2): title *(L)* subheadline primary; current `detail` (V) caption secondary. Right:
  current `title` (V) + `chevron.up.chevron.down` (footnote semibold, small), accent colour, spacing 5. Padding h14 v10,
  card background radius 14; whole row tappable. At accessibility sizes the two halves stack vertically (spacing 6).
- A11y label = title; value = `"<title>, <detail>"`; id `profile.menu`.

### 3.8 `AddressIndexControl` — `ConnectView.swift:476-545` (TRON only)

- Card: padding leading 14, trailing 10, vertical 10, card colour, radius 14. VStack spacing 6.
- Row (HStack spacing 10; at accessibility sizes VStack spacing 8 with the label above): "Address index" *(L)*
  subheadline (1 line, 2 at AX sizes) · spacer · number field · stepper.
- Field: placeholder "0" *(L)*, number pad, centred, body monospaced-digit semibold, width **76** while ≤ 5 chars,
  **116** when > 5 (scaled with Dynamic Type), vertical padding 6, capsule `tertiarySystemFill`; a11y "Address index";
  id `connect.tronIndex.field`. Keyboard accessory "Done" *(L)* while focused.
- Stepper: range **0…2,147,483,647**, label hidden (a11y "Address index"), id `connect.tronIndex`; system stepper
  auto-repeats on long press.
- Path line: `m/44'/195'/0'/0/<index>` caption monospaced secondary; a11y "Derivation path %@" *(L)*.
- Text ⇄ index rules: field shows the exported index (stepper updates it, also while editing); on each edit keep only
  ASCII digits, first **10**; if that changed the text, rewrite and stop; parse; clamp to max (rewrite the text if
  clamped); apply if different. Empty text leaves the index unchanged. On focus loss the text resets to the index
  (e.g. `007` → `7`, empty → current index). Every applied change immediately becomes a new pairing request (§7.6).

### 3.9 Design primitives — `Design/DesignSystem.swift`

- Metrics (`:24-28`): card radius **22**, readable width **640**, page padding **20**. `cardBackground(padding 16,
  radius 22)`. `listBottomInset` 30.
- Palette (`:14-22`): background `systemGroupedBackground`, card `secondarySystemGroupedBackground`, raised
  `tertiarySystemGroupedBackground`, success/warning/danger = system green/orange/red. Accent (asset
  `AccentColor`): light **#0F7B55**, dark **#6BE3A4**; label on accent: white (light) / **#0B1F16** (dark)
  (`:6-12`, `design/Brand.md`).
- `Callout(text, title?, tone)` (`:136-184`): HStack(firstTextBaseline, spacing 10): icon (info `info.circle.fill` /
  success `checkmark.circle.fill` / warning `exclamationmark.triangle.fill` / danger `exclamationmark.octagon.fill`,
  tone colour; info = accent) + VStack(spacing 3): optional title subheadline semibold; text subheadline (primary
  without title, secondary with title). Padding 14, background tone @12 %, radius 16. One a11y element.
- `DetailLine(label, value, monospaced = true)` (`:263-290`): label caption secondary; value footnote (monospaced or
  not), **text-selectable**; spacing 3; one a11y element.
- `ActionLabel` (`:95-133`): full-width label for primary buttons; `busy` swaps the title for a spinner (title kept
  for layout, opacity 0); disabled → secondary label colour.
- `Clipboard.copy(text, expiresAfter: 120)` (`:293-298`): **local-only** (no Handoff/Universal Clipboard) and
  **expires after 120 s**. Every copy in this area uses it.
- `StatusToast` (`:302-329`): bottom capsule (`regularMaterial`, shadow 12 % r12 y4, padding h16 v10, 24 above the
  bottom), icon `checkmark.circle.fill` + message subheadline semibold; slides up + fades (spring 0.3); posts an
  accessibility announcement (`announcement` string, **not localized**); auto-hides after **1.6 s**; **success
  haptic**. `copiedToast` = message "Copied" *(L)*, announcement `Copied`.

### 3.10 Compatible-wallet blocks — `ConnectView.swift:599-699`

- `WalletGroup(title, systemImage, wallets)`: Label(title *(L)*, icon) subheadline semibold secondary; adaptive grid,
  min column width **140** (**260** at Dynamic Type ≥ xxLarge), column spacing 10, row spacing 12, leading. Cell:
  HStack spacing 9: `WalletAppIcon(size 30)` + VStack(spacing 1): name (V) subheadline, ≤ 2 lines, min scale 0.85;
  platforms (V) caption2 secondary, ≤ 2 lines, min scale 0.85. One a11y element per cell.
- `WalletAppIcon(asset, size = 26)`: image or fallback `app.fill` in quaternary; corner radius `size × 0.225`
  (continuous); hairline border primary @8 % 0.5 pt; a11y hidden.
- `SubmitWalletLink`: "Submit a Wallet" *(L)*, `envelope` icon, footnote semibold, accent, plain → pushes
  `SubmitWalletView`; id `connect.submitWallet`.
- `IntendedWalletsView(chain, profile, mode)` (`:602-638`): hidden when the list is empty (never, today). Card
  (VStack spacing 16):
  1. "Works with" *(L)* headline.
  2. signing wallets → WalletGroup title "Multisig coordinators · sign by QR" *(L)* when profile starts with
     `multisig-`, else "Watch and sign by QR" *(L)*; icon `qrcode.viewfinder`.
  3. watch-only wallets → WalletGroup "Watch only" *(L)* icon `eye`, followed by caption (secondary):
     TRON → "These wallets can show this address’s balance. No mainstream wallet currently sends TRON transactions to a QR signer in BitTool Signer’s format." *(L)*;
     others → "These wallets can show this address’s balance but cannot send its transactions to BitTool Signer for signing." *(L)*
  4. caption "Based on each wallet’s documentation or source code, or its official QR hardware-wallet guide (September 2026). Versions change: always compare the first address in both apps before receiving funds." *(L)*
  5. SubmitWalletLink. Id `connect.wallets`.
- `SubmitWalletView` (`Features/Settings/SubmitWalletView.swift`), title "Submit a Wallet" (inline), inset-grouped:
  - intro: "Does your wallet read BitTool Signer's QR codes? Send us the details and we will add it to the list. Anyone can submit a wallet, including its own developers." *(L)* (straight `'`).
  - Section "Email" *(L)*: "To" *(L)* → `support@bittool.com` (CopyableText, a11y "Copy address"); "Subject" *(L)* →
    `Add supported wallet` (V) (also a11y "Copy address" — iOS quirk, keep).
  - Section header "Include at least" *(L)*, items (title semibold / detail footnote secondary, all *(L)*):
    "Wallet name" — "The name as it is published."; "Official website" — "The address the wallet itself publishes.";
    "App icon" — "A link to the image, or attach it. A square icon of at least 512 px."; "Source code" — "A link to the repository, if the wallet is open source.";
    "QR formats supported" — "Which of these the wallet reads: Wallet Level, and Coin Level for Bitcoin, EVM, TRON or Solana."
    Footer "The app never opens a browser. Copy the address and open it on another device." *(L)*.
  - info Callout "Being listed is not a review or an endorsement. We do not audit the wallets people submit, and BitTool is not responsible for what happens through them." *(L)*.

### 3.11 `WalletIdentityHeader(walletID, size = 56)` — `Design/PasswordViews.swift:221-239`

HStack spacing 14: WalletAvatar + VStack(spacing 3): wallet name headline; "Fingerprint %@" *(L)* caption monospaced
secondary. One a11y element. Shown at the top of every password sheet so the user recognises the wallet before typing.

---

## 4. Wallets tab — `Features/Wallets/WalletsTab.swift`

Never empty: with zero wallets the root shows Welcome instead of the tabs (`RootView.swift:13-18`).

Layout: navigation title "Wallets" *(L)* (large title, collapses on scroll); scrolling lazy column, spacing **16**,
horizontal padding 20, vertical 8, max width 640 centred; background Palette.background.
1. One `WalletCard` per wallet, vault order.
2. Footer: Label "Keys and signing stay on this device." *(L)* icon `lock.shield`, footnote, secondary, top padding 8.

Toolbar (trailing, in this order):
- "Scan" *(L)*, `qrcode.viewfinder`, id `wallets.scan` → `router.startScanning()` (switch to the Scan tab and open the
  camera once; `AppRouter.swift:61-76`).
- Menu "Add Wallet" *(L)*, `plus`, id `wallets.add`, items "Create New Wallet" *(L)* (`plus`) and
  "Import Recovery Phrase" *(L)* (`square.and.arrow.down`) → `router.startSetup(.create|.restore, firstWallet:
  wallets.isEmpty, introductionCompleted: onboardingVersionCompleted >= 1)` (`:62-64`).

### 4.1 `WalletCard` (`:69-141`)

Card background Palette.card, radius 22 (continuous). VStack spacing 0:
1. **Header** (navigation link → detail, plain style, whole row tappable, padding 16; id
   `wallet.card.<UUID>`; a11y hint "Shows wallet details" *(L)*): HStack spacing 14:
   - WalletAvatar size 50.
   - VStack(leading, spacing 5): name (V) headline, primary, ≤ 2 lines; "Fingerprint %@" *(L)* caption monospaced
     secondary (uppercase fingerprint or `Setup required`); **if ready**: NetworkLogoStack(size **16**), top padding 2.
   - Spacer(min 8); `chevron.right` footnote semibold tertiary.
2. If duplicate: warning Callout "Another wallet has the same key identity. Requests it matches cannot be signed." *(L)*,
   padding h16, bottom 12.
3. Divider, leading inset 16.
4. **If ready** — action row, padding h14 v12, HStack spacing 10, two equal-width bordered **capsule** buttons
   (regular size):
   - "Pair" *(L)* icon `qrcode` → `.connectWallet` (Wallet Level); id `wallet.connect.<UUID>`.
   - "Addresses" *(L)* icon `list.bullet.rectangle` → `.addresses(id, .bitcoin)`; id `wallet.addresses.<UUID>`.
   - `CompactActionLabelStyle` (`:144-153`): icon + title spacing 6, subheadline semibold, title 1 line min scale
     0.85; **title hidden at accessibility sizes** (icon only).
   **Else** — link "Repair wallet data" *(L)* icon `wrench.and.screwdriver`, subheadline semibold, leading, padding 16
   → detail (where the Repair sheet lives).

There is no "current/default wallet"; all cards are equal.

---

## 5. Wallet detail — `Features/Wallets/WalletDetailView.swift`

Title: wallet name (V) or "Wallet" *(L)*, inline. Inset-grouped list, bottom inset 30. Sections in order:

1. **Header** (clear row background, insets top/bottom 8, centred; `:134-160`), VStack spacing 12:
   - WalletAvatar **76**.
   - VStack spacing 4: name title2 bold centred (fallback "Wallet"); button "Fingerprint %@" *(L)* footnote monospaced
     secondary → copies `masterFingerprint.uppercased()` and shows the "Copied" toast; disabled when there is no
     public metadata; a11y hint "Copies the master fingerprint" *(L)*; "Added %@" *(L)* with `createdAt` formatted
     date-only, abbreviated (e.g. `Oct 20, 2026`), footnote tertiary.
2. **Duplicate** (if duplicate): warning Callout title "Duplicate wallet" *(L)*, text "Another saved wallet uses the same recovery phrase and passphrase. Both are kept. Requests that match both cannot be signed until you delete one copy." *(L)* (no row insets, clear background).
3. If **not ready**: section with warning Callout title "Repair needed" *(L)*, text "This wallet's public data is missing or damaged. Your encrypted recovery phrase is safe. Enter the wallet password once to rebuild it." *(L)* (straight `'`), then button "Repair Wallet Data" *(L)* `wrench.and.screwdriver` → Repair sheet.
   If **ready**:
   - Section header "Pair" *(L)*: four `ChainRow`s (bitcoin, evm, tron, solana) → `.connect(id, chain)` (ids
     `detail.connect.<chain>`), then `WalletLevelRow` → `.connectWallet` (id `detail.connect.wallet`). Footer
     "Export public keys to a watch-only wallet such as Sparrow or MetaMask. Private keys never leave this device." *(L)*
   - Section: "Receive Addresses" *(L)* `list.bullet.rectangle` → `.addresses(id, .bitcoin)` (id `detail.addresses`).
     Footer "Derived on this device from public keys. Balances are never checked." *(L)*
4. Section "Security" *(L)* (shown even when not ready): "Show Recovery Phrase" *(L)* `key.viewfinder`
   (id `detail.recoveryPhrase`) → sheet; "Change Password" *(L)* `lock.rotation` (id `detail.changePassword`) → sheet.
5. Section: "Rename Wallet" *(L)* `pencil` (id `detail.rename`) → rename dialog (§8.6), pre-filled with the name.
6. Section: destructive "Delete Wallet" *(L)* `trash` in danger colour (id `detail.delete`) → sheet. Footer
   "Deleting removes this wallet from BitTool Signer only. Funds stay on the blockchain and can be restored with the recovery phrase." *(L)*

Behaviour: sheets are one-at-a-time (`WalletSheet = recoveryPhrase | changePassword | delete | repair`, `:3-6`);
when the wallet disappears from `store.wallets` the page pops itself (`:129`); "Password Changed" *(L)* status toast
(announcement `Password changed`, not localized) after a successful change (`:112,131`); copied toast (`:130`).

`ChainRow` (`:192-214`): HStack spacing 12: ChainGlyph 32 + VStack(spacing 2): `longName` (V) body; subtitle caption
secondary *(L)*: bitcoin "Single-sig and multisig accounts", evm "Ethereum, BNB Chain, Arbitrum, Base and more",
tron "Any address index", solana "Standard, Trust and Legacy paths". Vertical padding 2.

`WalletLevelRow` (`:174-190`): 2×2 grid of ChainLogo **15** (bitcoin, evm / tron, solana), spacing 2, in a 32×32
frame, a11y hidden; VStack(spacing 2): "Wallet Level" *(L)* body; "One QR code for all four chains" *(L)* caption
secondary.

---

## 6. Addresses — `Features/Wallets/AddressesView.swift`

Receive addresses derived locally from cached public keys; no balances/usage lookups; browsing never selects a signer.

Title "Addresses" *(L)* inline; inset-grouped list, bottom inset 30. Initial chain from the route (always bitcoin);
`btcProfile` starts `native`; nothing is remembered between visits.

Sections:
1. Controls (clear background, insets top 4 / bottom 8), VStack spacing 12: `ChainPicker`; if bitcoin:
   `ProfileMenu("Address type", bitcoinSingle, btcProfile)` (single-sig only — no multisig receive addresses).
2. Address list section.
   - Header: bitcoin "Receive · %@" *(L)* with the profile title (V) (e.g. `Receive · Native SegWit`); evm
     "Receive · All EVM networks" *(L)*; tron "Receive · TRX and TRC-20 tokens" *(L)*; solana "Accounts by derivation path" *(L)*.
   - Footer *(L)*: bitcoin "Showing receive addresses. Multisig addresses need every cosigner key and are created by your coordinator."; evm "One address works on Ethereum, BNB Smart Chain, Arbitrum, Base and other EVM networks."; tron "Every TRON receive index is available. To export a specific index, use Pair."; solana "Solana wallets use different derivation paths. Pick the one your other wallet uses."
   - Body: if the loaded key matches the current key → rows (+ a centred spinner row while loading more); else if an
     error → danger Callout with the engine/store message (verbatim); else a centred spinner, vertical padding 20.

Row (`AddressRow`, `:143-168`), whole row is a button → address sheet; id `address.row.<index>`; a11y: one element,
hint "Shows QR code and derivation path" *(L)*. HStack spacing 12, vertical padding 4:
- label: `#<index>` (V) — Solana: preset title (Standard/Trust/Legacy, V) matched by path — caption semibold
  monospaced digits secondary, min width 34, leading.
- `AddressText` footnote, fills width.
- `qrcode` icon in accent, a11y hidden.
Context menu (long press): "Copy Address" *(L)* `doc.on.doc` → clipboard (**no toast**); "Show QR Code" *(L)*
`qrcode` → sheet.

### 6.1 Paging state machine (`:9-17,24-25,106-140`)

- `pageSize = 20`; `listKey = chain + "|" + btcProfile`; `limit = solana ? 3 : 2,147,483,648` (2^31 — **needs a
  64-bit Long on Android**).
- `reload()` on every `listKey` change (previous task cancelled): `error = nil; hasMore = true`; fetch `start 0`; if
  cancelled or key changed → drop; else `accounts = page; loadedKey = key; hasMore = page.count == 20 && page.count <
  limit`; on error (not cancelled, key current) → `error = message`.
- `loadMore()` triggered when the **last** row appears: guard `hasMore && !loadingMore && loadedKey == listKey &&
  chain != solana`; `start = accounts.count`; `start ≥ limit` → `hasMore = false`; `loadingMore = true` (spinner
  row); fetch; drop if key changed or `accounts.count != start`; append; `hasMore = page.count == 20 &&
  accounts.count < limit`; on error → `hasMore = false` silently (no message).
- `fetch(start)`: `count = solana ? 3 : min(20, limit − start)`; `profile = bitcoin ? btcProfile : solana ? "" :
  "standard"` → §2.1.
- While a new key loads, the old rows are hidden (spinner), not cross-faded.

### 6.2 `AddressDetailSheet` (`:170-201`)

Full-height sheet with its own navigation bar; title: Solana "%@ account" *(L)* with the preset title
(`Standard account`); others "%@ address #%lld" *(L)* with `chain.name` (V) and index (`Bitcoin address #0`,
`EVM address #3`, `TRON address #12`). Trailing "Done" *(L)*.
Content (scroll, padding 20, max width 520, VStack spacing 22): QRCodeView(frames `[address]`, maxSide **280**), top
padding 8; card (VStack leading spacing 14): `CopyableAddress(address, body)`, Divider, `DetailLine("Derivation path",
path)`; info Callout "Address only. No public key or derivation path is included in this QR code." *(L)*.
The QR is the bare address text (no URI scheme), EC level M.

---

## 7. Connect / Pair — `Features/Wallets/ConnectView.swift:80-472`

### 7.1 State and defaults (`:83-114`)

| state | default | notes |
|---|---|---|
| `level` | from route (`.wallet` for connectWallet, `.coin` for connect) | segmented |
| `chain` | from route, default bitcoin | |
| `multisig` | false | BTC "Account type" |
| `btcSingle` | `native` (`AppStore.defaultConnectProfile`) | |
| `btcMulti` | `multisig-native` | |
| `btcMode` | `accountXpub` (`AppStore.defaultConnectMode`) | |
| `evmMode` | `wallet` | |
| `solanaProfile` | `standard` | |
| `solanaMode` | `wallet` | |
| `tronIndex` | 0 | |
| `walletPayload`, `walletLoadedKey`, `preparedImages` | from prefetch if any (`walletLoadedKey = "wallet"`) | |
| `payload`, `loadedKey`, `error`, `copied` | nil/false | one `error` shared by both levels |

Every chain keeps its own options for the life of the screen (switching chains or levels never resets them);
nothing is persisted.

Derived (`:116-142`):
- `profile` = bitcoin → `multisig ? btcMulti : btcSingle`; solana → `solanaProfile`; evm/tron → `standard`.
- `outputMode` = bitcoin → `multisig ? "accountXpub" : btcMode`; evm → `evmMode`; solana → `solanaMode`; tron →
  `address`.
- `addressIndex` = tron ? `tronIndex` : 0.
- `requestKey` = `chain|profile|outputMode|index` (e.g. `bitcoin|native|accountXpub|0`).
- `current` = `payload` only if `loadedKey == requestKey` (a stale payload is never shown).
- `currentWallet` = `walletPayload` if `walletLoadedKey == "wallet"`.
- `copyText` = Coin → `current.text`; Wallet → `currentWallet.text` (exactly the QR's text; animated = frames joined
  by `\n`).

### 7.2 Page chrome

- Title (inline): Coin → "Pair %@" *(L)* with `chain.name` (V) (`Pair Bitcoin`, `Pair EVM`); Wallet → "Pair Wallet" *(L)*.
- Toolbar trailing: "Copy QR Data" *(L)* `doc.on.doc` (id `connect.copy`), disabled while `copyText == nil`; copies
  and shows the "Copied" toast.
- Body: scroll, VStack spacing **20**, horizontal padding 20, bottom 24, max width 640; background Palette.background.
- First control: segmented "Pairing level" *(L)* with **"Wallet Level"** then **"Coin Level"** *(L)* (id
  `connect.level`), change animated `.snappy(0.25)`.

### 7.3 Coin Level layout (in order)

1. `ChainPicker`.
2. Options (`:189-220`):
   - **Bitcoin** (VStack spacing 12): segmented "Account type" *(L)*: "Single-sig" | "Multisig" *(L)* (id
     `connect.btcKind`, animated); `ProfileMenu("Address type", multisig ? bitcoinMulti : bitcoinSingle, …)`.
   - **Solana** (VStack leading spacing 8): segmented "Derivation path" *(L)* with Standard | Trust | Legacy (V) (id
     `connect.solanaProfile`); below it the preset path (V) caption monospaced secondary, horizontal padding 4.
   - **TRON**: `AddressIndexControl(pathPrefix "m/44'/195'/0'/0/")`.
   - **EVM**: nothing.
3. QR section (`:222-249`, VStack spacing 12, animated `.smooth(0.2)` on `current`):
   - loaded → `QRCodeView(current.frames, maxSide 300)` (fade in) + footnote secondary: BTC multisig
     "Scan with your multisig coordinator" *(L)*, otherwise "Scan with your watch-only wallet" *(L)*;
   - error → danger Callout (verbatim message);
   - loading → placeholder: card-coloured rounded rect radius 24, square, max 300, centred spinner.
   - Below (top padding 4) segmented "Export as" *(L)* (id `pairing.outputMode`), only when modes exist:
     BTC single-sig → `Account XPUB` (`accountXpub`) | `Address` (`address`); EVM and Solana → `Wallet` (`wallet`) |
     `Address` (`address`); BTC multisig and TRON → no picker. Segment titles are (V).
4. Verification card (only when `current` exists; `:268-298`; card padding 16 radius 22; VStack leading spacing 14):
   - if `address` non-empty: VStack(spacing 6): bitcoin "Verify the first receive address" *(L)*, others "Verify the address" *(L)* (subheadline semibold) + `CopyableAddress(address, callout)`; then Divider.
   - if `outputMode == accountXpub` or (EVM and `wallet`): DetailLine "Cosigner account path" *(L)* (BTC multisig) or
     "Account path" *(L)* = `path`; and, if address non-empty, DetailLine "First address path" *(L)* = `path + "/0/0"`.
     Otherwise DetailLine "Derivation path" *(L)* = `path`.
   - DetailLine "Format" *(L)* = `payloadView.summary` (not monospaced) — if a payload view exists.
   - DetailLine "Master fingerprint" *(L)* = uppercase fingerprint.
   - `PayloadDisclosure(verbatim: view.title, text: view.text, notes:, identifier "connect.payload")`.
     Notes (`:302-306`): only for JSON payloads whose text contains the decimal value of the fingerprint →
     `Fingerprints are stored as 32-bit numbers: 1942346250 is fingerprint 73C5DA0A.` (V — built as a plain Swift
     string, not localized). Applies to BTC account/cosigner, EVM wallet and Solana wallet exports.
   Order note in code: "Format, then fingerprint, then payload — the same order as wallet level."
5. Notes (`:308-314`): `outputMode == address` → info Callout "Address only. No public key or derivation path is included." *(L)* (always for TRON); else BTC multisig → "This exports one cosigner key. Your multisig coordinator combines all cosigner keys and the threshold to create the wallet and its addresses." *(L)*
6. `IntendedWalletsView(chain, profile, outputMode)` (§3.10) — reflects the selection even while loading.

Resulting matrix of what the verification card shows (test phrase):

| selection | verify header | path lines | Format |
|---|---|---|---|
| BTC single accountXpub | Verify the first receive address | Account path `m/84'/0'/0'`; First address path `m/84'/0'/0'/0/0` | `ur:crypto-account · CBOR · 170 bytes` |
| BTC single address | Verify the first receive address | Derivation path `m/84'/0'/0'/0/0` | `Plain text · 42 bytes` |
| BTC multisig (any) | — (no address) | Cosigner account path `m/48'/0'/0'/2'` | `ur:crypto-account · CBOR · 172 bytes` |
| EVM wallet | Verify the address | Account path `m/44'/60'/0'`; First address path `m/44'/60'/0'/0/0` | `ur:crypto-hdkey · CBOR · 170 bytes` |
| EVM address | Verify the address | Derivation path `m/44'/60'/0'/0/0` | `Plain text · 42 bytes` |
| SOL wallet | Verify the address | Derivation path (preset) | `ur:crypto-multi-accounts · CBOR · 156 bytes` |
| SOL address | Verify the address | Derivation path (preset) | `Plain text · 44 bytes` |
| TRON #i | Verify the address | Derivation path `m/44'/195'/0'/0/i` | `Plain text · 34 bytes` |

### 7.4 Wallet Level layout — `WalletLevelPairingView` (`:359-472`)

VStack spacing **18**, animated `.smooth(0.2)` on the payload:
1. Intro (always, leading, VStack spacing 10): "One animated QR code for all four chains" *(L)* headline; footnote
   secondary: "Includes an account xpub for each Bitcoin address type (bc1q, bc1p, 3, 1), for EVM and for TRON, plus all three Solana addresses. Your wallet can then use every Bitcoin, EVM and TRON address without scanning again. Hold the camera steady until every frame is read." *(L)*; row (spacing 6): four ChainLogo **18** (btc, evm, tron, sol; spacing 4; each a11y `longName`) + "BTC · EVM · TRON · SOL" *(L)* footnote secondary.
2. Loaded:
   - `QRCodeView(frames, maxSide 360, initialImages: prefetched)` (id `connect.walletQR`) — 4 frames, 250 ms each
     (one full cycle per second), with pause/progress/counter controls.
   - centred footnote secondary "Scan with BitTool Wallet or another wallet that supports BitTool Signer wallet pairing." *(L)*
   - Verification card (`:440-471`, VStack leading spacing 14): "Verify the first address of each account" *(L)*
     subheadline semibold; for each `verify` line (in engine order): VStack(spacing 6): HStack(spacing 8) ChainLogo 20 +
     account name (V, footnote semibold): BTC → `Bitcoin · Native SegWit|Taproot|Nested SegWit|Legacy`, SOL →
     `Solana · Standard|Trust|Legacy`, EVM → `Ethereum & EVM`, TRON → `TRON`; path (V) caption monospaced secondary;
     `CopyableAddress(address, callout)`; then Divider. Unknown chain codes are skipped. Then DetailLine "Format" *(L)*
     = `bittool-signer-wallet v1 · ur:bytes (canonical CBOR) · 1561 bytes` (V; the byte count is a raw integer, **no**
     grouping); DetailLine "Master fingerprint"; `PayloadDisclosure(title "Payload (JSON)", text: json, identifier
     "connect.walletJSON")` (no notes); caption secondary "Public addresses and public keys only. No private key, recovery phrase or passphrase is included." *(L)*
   - "Works with" card (VStack leading spacing 12, id `connect.walletLevelWallets`): "Works with" *(L)* headline;
     WalletGroup "One QR for every chain" *(L)* `qrcode.viewfinder` with BitTool Wallet (iOS); caption "Always compare the first addresses in both apps before receiving funds." *(L)*; SubmitWalletLink.
3. Error → danger Callout (verbatim). Loading → placeholder (as §7.3, max 300).
4. Always last: info Callout "Chain-specific QR codes (Coin Level) remain the choice for Sparrow, MetaMask, Solflare and the other wallets listed there." *(L)*

### 7.5 Coin Level vs Wallet Level (product semantics)

- **Coin Level**: one chain, one account (or one address), in that chain's standard format for third-party wallets
  (Sparrow/BlueWallet `crypto-account`, MetaMask/Rabby `crypto-hdkey` EIP-4527, Solflare/Helium
  `crypto-multi-accounts`, plain addresses for watch-only). Static single-frame QR in practice (≤ 376 chars).
- **Wallet Level**: BitTool Signer's own `bittool-signer-wallet` v1 (`ur:bytes`, canonical CBOR): 4 BTC xpubs +
  EVM xpub + TRON xpub + 3 Solana addresses, always **animated** (≥ 2 frames, 400-byte fragments, ≤ 4096 bytes).
  No multisig profiles (cosigner export stays Coin Level). No password; reads only the authenticated public index.
  Supported by BitTool Wallet.

### 7.6 Loading state machine (`:185,316-347`)

- A single task keyed on `level == coin ? requestKey : "wallet"`; any key change cancels the previous task and starts
  `load()`. Typing a TRON index or flipping any option therefore issues a new request immediately (no debounce);
  stale results are dropped by key comparison.
- Coin: if `loadedKey == key && payload != nil` → return; `first = (loadedKey == nil)`; `error = nil`; call §2.2;
  drop if cancelled or key changed; apply `payload`, `loadedKey` — **without animation if it is the first result**
  (it may land during the push transition), otherwise with the 0.2 s fade; on error (not cancelled, key current) →
  `error`.
- Wallet: `error = nil`; call §2.3 (always, even with a prefetched payload); drop if cancelled; set payload and
  `walletLoadedKey = "wallet"`; on error → `error`.
- Note the shared `error`: leaving an errored level and coming back reruns the load and clears it first.

---

## 8. Wallet security sheets — `Features/Wallets/WalletSecuritySheets.swift`

All sheets: own navigation bar, inline title, leading "Cancel" *(L)*; swipe-to-dismiss disabled while busy; typed
secrets cleared on dismiss and when the app is suspended (`securityEpoch` change); each opens with
`WalletIdentityHeader`.

### 8.1 `WalletOperation` (`:6-29`)

One password-authorized operation at a time: ignored while `busy`; each run gets a token; `busy = true; error = nil`;
on failure the error is shown only if the token is still current, the security epoch is unchanged and it is not a
cancellation; `busy` reset only by the current token. `cancel()` invalidates the token, cancels the task, clears
`busy` and `error`.

Vault errors that can surface (`Core/VaultCrypto.swift:24-44`, all *(L)*): "The password is incorrect.";
"Too many unsuccessful attempts. Try again in %lld seconds." (after 5 failures the vault enforces
`min(300, 2^(n−4))` s back-off, persisted before decryption; `Core/WalletVault.swift:140-147`); "This wallet could not be found.";
"This wallet’s device key is unavailable. Restore your recovery phrase on this device."; "The encrypted wallet could not be read or saved.";
"The wallet file is damaged or has an invalid format."; "Choose a wallet name of 1–40 characters."; password-policy
messages ("Use 8–64 characters.", "Control characters are not allowed.", "Include at least 3 of: uppercase, lowercase, numbers and symbols.").

### 8.2 Recovery Phrase sheet (`:37-122`) — title "Recovery Phrase" *(L)*

- Scroll content, padding 20, max width 640, VStack leading spacing 18; keyboard dismisses interactively on scroll.
  Single container for both states (iOS crash workaround; irrelevant on Android but keep one screen).
- Header: `WalletIdentityHeader(size 44)`.
- **Locked state**: `PasswordField(.walletPassword)` (placeholder "Wallet password" *(L)*), submit = continue, inside a
  card (padding 14, radius 14); footnote secondary "The recovery phrase stays hidden until you choose to show it." *(L)*
  (h-padding 4); error Callout (danger, verbatim); prominent button "Continue" *(L)* (busy spinner), disabled when the
  field is empty or busy, top padding 6, id `vaultManagement.submit`. Field auto-focuses **350 ms** after appearing.
- Submit: copy the password and **clear the field immediately**, then `store.reveal(walletID:password:)` (vault
  decrypt; no engine call); on success (same epoch) unfocus and show the secret.
- **Revealed state**: `RecoveryBackupPanel(words, displayedPassphrase: passphrase, hasPassphrase)` (Words / QR Code /
  Recovery Cards; each concealed until "Tap to Reveal"; screenshot handling — see the backup spec,
  `Features/Backup/RecoveryBackupViews.swift`) + warning Callout "Anyone with these words can take your funds. Write them on paper and store them offline. Never type them into a website or share a photo." *(L)*
- Toolbar leading: "Cancel" (locked) / "Done" (revealed) *(L)*, both clear and dismiss.
- Appear → `beginSensitiveSession()`; disappear → clear + `endSensitiveSession()` (a reveal that finishes after the
  sheet closed is discarded, `AppStore.swift:474-480`). Suspension → clear: the decrypted phrase is discarded and the
  password must be entered again.

### 8.3 Change Password sheet (`:124-191`) — title "Change Password" *(L)*

Form sections:
1. `WalletIdentityHeader` (56).
2. `PasswordField(.currentWalletPassword)` ("Current wallet password" *(L)*), next → new.
3. `PasswordField(.newPassword, isNew)` ("New password"), next → confirmation; `PasswordField(.confirmNewPassword,
   isNew)` ("Confirm new password"), done. Footer: `PasswordRequirementsView(new, confirmation)`
   (`Design/PasswordViews.swift:169-217`): 4-segment strength bar (height 4, spacing 4; strength 0 empty, 1 invalid,
   4 if ≥ 16 chars and 4 categories, 3 if ≥ 12 chars, else 2; colours 1 orange, 2 system yellow, 3–4 green, unfilled
   `systemFill`, animated 0.2 s, a11y hidden) and three checklist rows "8 to 64 characters", "3 of: uppercase, lowercase, number, symbol", "Passwords match" *(L)* with `checkmark.circle.fill` (green) / `circle` (secondary), a11y value "Met"/"Not met".
4. Error Callout (danger) if any.
5. Prominent "Change Password" *(L)* (busy spinner), id `vaultManagement.submit`; enabled only when
   `current` non-empty ∧ policy-valid(new) ∧ new == confirmation. Footer "Your recovery phrase, fingerprint and addresses stay the same." *(L)*

Submit: unfocus; `store.changePassword(current, new)` (vault re-encrypt; no engine call); success → clear, dismiss,
detail shows "Password Changed" toast. On failure the typed values **stay** (unlike the other sheets). Suspension
clears all three fields unless busy. Policy: `Core/PasswordPolicy.swift` (8–64 chars and ≤ 1024 UTF-8 bytes, ≥ 3 of
upper/lower/digit/symbol-or-punctuation, no control chars; used exactly as typed).

### 8.4 Delete Wallet sheet (`:193-297`) — title "Delete Wallet" *(L)*

Form:
1. `WalletIdentityHeader`.
2. Danger Callout title "Delete this wallet?" *(L)*, text "This removes the encrypted wallet and its public data from this device. Funds are not affected, but you can only recover them with the recovery phrase and any BIP39 passphrase." *(L)*
3. Toggle "I confirm I want to delete this wallet" *(L)* (id `delete.backupConfirmed`). Footer: "Deleting asks for this wallet’s password." *(L)* (or, only when App Lock is on with biometrics available, "Deleting asks for %@, then for this wallet’s password." *(L)* — see §14).
4. Error Callout (danger) if any.
5. Destructive prominent capsule button (large, tint danger, white label, semibold): "Delete Wallet" *(L)* `trash`;
   spinner (white) while busy/confirming; disabled until the toggle is on (and while busy). Id `vaultManagement.submit`.

Flow:
1. Tap → `confirm()`: clear error; `confirming = true`; (iOS: biometric check only when App Lock is on; cancel →
   stop; failure → ignored, the password decides); clear the password; show the password dialog.
2. Password dialog (alert): title "Wallet Password" *(L)*; secure field placeholder "Wallet password" *(L)* (id
   `delete.password`); message "Enter this wallet’s password to delete it. Without the recovery phrase this cannot be undone." *(L)*; buttons "Cancel" *(L)* (clears the field) and destructive "Delete" *(L)* (always enabled).
3. `submit()`: take and clear the password; empty → error "Enter this wallet’s password." *(L)*; else
   `store.deleteWallet(walletID, password)`: vault verifies the password **before** removing anything (wrong password →
   error in the sheet, every session intact, wallets reloaded); then `suspend()`, remove the public sidecar (failure →
   global alert "The encrypted wallet was deleted, but its public cache could not be removed." (V)), clear any pending
   request, reset navigation, reload (`AppStore.swift:520-531`). Sheet clears and dismisses; detail pops (wallet gone).
- Suspension: if not busy, clear the password and close the password dialog.

### 8.5 Repair Wallet sheet (`:299-345`) — title "Repair Wallet" *(L)*

Form: `WalletIdentityHeader`; `PasswordField(.walletPassword)` (submit → repair) with footer "BitTool Signer decrypts the wallet once to rebuild its public keys and addresses. Your recovery phrase and password do not change." *(L)*; error Callout; prominent "Repair Wallet" *(L)* (busy), disabled when empty/busy. Leading "Cancel".
Submit: take and clear the password; `store.completeSetup(walletID, password)` → vault unlock → native seed →
`derivePublicIndex` (§2.4) → save sidecar (keeps the display name) → reload; success → dismiss (the detail page then
shows the ready sections). Suspension clears the field (unless busy); disappear cancels the operation.

### 8.6 Rename (dialog) — `WalletDetailView.swift:117-128,162-168`

Alert "Rename Wallet" *(L)* with a text field (placeholder "Wallet name" *(L)*, word capitalization, pre-filled),
message "The fingerprint and addresses stay the same." *(L)*, buttons "Cancel" *(L)* and "Save" *(L)* (disabled while
the trimmed text is empty). Save → `store.renameWallet` (requires the public-data key, else "Repair this wallet before renaming it." *(L)*;
repository trims and requires 1–40 characters (grapheme count) else "Choose a wallet name of 1–40 characters." *(L)*;
only the public sidecar's `displayName` changes; `Services/PublicWalletRepository.swift:53-68`) → reload. Failure →
alert "Unable to Rename" *(L)* with the message and "OK" *(L)*. No password is asked.

---

## 9. Lifecycle, security epoch and privacy

- Background (`scenePhase == .background`), App Lock engaging, or screen capture/mirroring → `store.suspend()`:
  new `securityEpoch` (all sheets above drop typed passwords / revealed phrase / late results), Connect prefetch
  cleared, warm engine context released (§2.5).
- The prefetch only runs while the app is active, unlocked, not captured and showing the tabs.
- Public QR codes (pairing, addresses) are not hidden on capture; only secrets are (backup spec).
- Clipboard: local-only, 120 s expiry for every copy in this area (fingerprint, addresses, QR data, payload JSON).

## 10. Animations and haptics (summary)

| where | iOS | value |
|---|---|---|
| Pairing level switch | `.snappy` | 0.25 s |
| BTC Single-sig/Multisig | `.snappy` | default |
| ChainPicker selection slide | `.snappy` + matched geometry | 0.28 s |
| QR appear / change | opacity transition, `.smooth` | 0.2 s; first Coin result unanimated |
| Animated QR frames | instant switch | 250 ms per frame |
| Payload disclosure | chevron rotate + expand, `.snappy` | 0.25 s |
| Copy icon → checkmark | symbol replace | reverts after 1.5 s |
| Status toast | move from bottom + fade, spring | 0.3 s, visible 1.6 s |
| Strength bar | `.smooth` | 0.2 s |
| Haptic: selection | ChainPicker change | |
| Haptic: success | CopyableAddress copy, every StatusToast | |

## 11. Accessibility

- Identifiers (use as Compose `testTag`s): `wallets.scan`, `wallets.add`, `wallet.card.<UUID>`,
  `wallet.connect.<UUID>`, `wallet.addresses.<UUID>`, `detail.connect.<chain>`, `detail.connect.wallet`,
  `detail.addresses`, `detail.recoveryPhrase`, `detail.changePassword`, `detail.rename`, `detail.delete`,
  `address.row.<index>`, `chain.<chain>`, `profile.menu`, `connect.level`, `connect.copy`, `connect.btcKind`,
  `connect.solanaProfile`, `pairing.outputMode`, `connect.tronIndex.field`, `connect.tronIndex`, `connect.walletQR`,
  `connect.walletLevelWallets`, `connect.wallets`, `connect.submitWallet`, `connect.payload{,Toggle,Copy}`,
  `connect.walletJSON{,Toggle,Copy}`, `vaultManagement.submit`, `delete.backupConfirmed`, `delete.password`; password
  fields use their English placeholder as identifier (`Wallet password`, `Current wallet password`, `New password`,
  `Confirm new password`).
- Labels/hints/values listed per component above ("Shows wallet details", "Copies the master fingerprint",
  "Shows QR code and derivation path", "Animated QR code, part %lld of %lld", "Play/Pause QR animation",
  "Copy address"/"Copied", "Shown"/"Hidden", "Derivation path %@", "Met"/"Not met", "EVM networks").
- Dynamic Type: action titles collapse to icons, ChainPicker hides names, ProfileMenu and AddressIndexControl stack
  vertically at accessibility sizes; WalletGroup columns widen to 260 at ≥ xxLarge.

## 12. Light / dark

- Everything uses semantic colours; the only fixed colours are the QR plate (always white, light scheme inside) and
  the avatar gradient/white initial.
- Accent #0F7B55 (light) / #6BE3A4 (dark); text on accent white / #0B1F16.
- `design/BitSigner-Dark.png` / `-Light.png` (and `design/Revision-2026-09-21/*`) are **pre-rename concept
  screenshots** ("BitSigner", "Vault" tab, "ID ·" fingerprints, "Connect" button, horizontal profile chips, network
  banner) — `design/Brand.md` says they are kept for reference only. Use them for mood (deep-green ink, mint accent,
  monospaced uppercase micro-labels, large rounded cards); follow the code for structure and wording.

## 13. Android mapping

### 13.1 Modules (Ownbit 5.0 layout, `/Users/tanguopeng/AndroidStudioProjects/ownbitandroidnew`)

| module | contents |
|---|---|
| `core:model` | `Chain`, `NetworkBrand`, `AccountProfile`, `PublicAccount`, `PairingPayload`, `WalletPairingPayload`, `QrPayloadView`, `CompatibleWallet(s)` (pure Kotlin, unit-tested against the engine vectors in §2) |
| `core:engine` | QuickJS host (`SigningEngine`): allowlist, fresh vs warm context, 1 MiB request cap, error envelope mapping |
| `core:data` | `WalletStore` (AppStore equivalent): `addresses()`, `pairing()`, `walletPairing()`, prefetch cache, `reveal/changePassword/deleteWallet/renameWallet/completeSetup`, `securityEpoch: StateFlow` |
| `core:designsystem` | colours/typography, `Callout`, `DetailLine`, `ActionLabel`, `StatusToast`, `WalletAvatar`, `NetworkLogo`/`ChainLogo`/`ChainGlyph`/`NetworkLogoStack`, `WalletAppIcon`, segmented control |
| `core:ui` | `QrEncoder`, `AnimatedQrCode` (QRCodeView), `AddressText`, `CopyableAddress`, `CopyableText`, `PayloadDisclosure`, `ChainPicker`, `ProfileMenu`, `AddressIndexControl`, `AppClipboard`, window effects (keep-on / brightness / secure) |
| `feature:wallets` | `WalletsScreen`, `WalletDetailScreen`, `AddressesScreen` + `AddressDetailSheet`, `ConnectScreen` (+ `WalletLevelPairing`), `RecoveryPhraseSheet`, `ChangePasswordSheet`, `DeleteWalletSheet`, `RepairWalletSheet`, rename dialog, `SubmitWalletScreen` (or in `feature:settings`), one `@HiltViewModel` per screen |

Reusable Ownbit prior art (adapt, do not copy Ownbit-specific logic such as "Same as in Ownbit" cross-checks or the
WebView engine): `core/ui/src/main/kotlin/io/ownbit/core/ui/qr/{QrEncoder,AnimatedQrCard,QrBrightness,QrCodeImage}.kt`,
`core/ui/.../window/WindowEffects.kt`, `core/ui/.../security/ClipboardHelper.kt`,
`core/publicsigner/src/main/kotlin/io/ownbit/core/publicsigner/ui/{ChainPicker,AddressIndexStepper,ProfileRow,PayloadDisclosure,WalletsWorksWith,PairingVerificationCard}.kt`,
`core/publicsigner/.../pairing/CompatibleWallets.kt`. Ownbit's `AnimatedQrCard` already mirrors this `QRCodeView`
(250 ms, M ≤ 600 bytes else L, pause/progress/"i/n", polite live region only while paused).

No apk/play flavor differences in this area (both are offline, no INTERNET permission).

### 13.2 Navigation

- Navigation Compose with type-safe `@Serializable` routes: `WalletDetail(id)`, `Connect(id, chain: String?, level:
  Level)`, `Addresses(id, chain = "bitcoin")`, `SubmitWallet`. `Connect(id, null, WALLET)` ≙ `.connectWallet`.
- Setup completion → pop to the Wallets start destination, then navigate to `Connect(id, null, WALLET)` **after** the
  setup sheet/flow has finished closing (iOS waits for `onDismiss`).
- Delete → `popBackStack(WalletsRoute, inclusive = false)`; also observe the wallet list in Detail/Connect/Addresses
  and pop when the wallet disappears.
- Support predictive back (`enableOnBackInvokedCallback="true"`); block back while a password operation is busy.

### 13.3 Screens and Compose components

- Wallets: `Scaffold` + `LargeTopAppBar` (`exitUntilCollapsedScrollBehavior`) titled "Wallets"; actions: `IconButton`
  QrCodeScanner ("Scan", tooltip) and `IconButton` Add → `DropdownMenu` with two `DropdownMenuItem`s (leading icons
  Add / Download). `LazyColumn(contentPadding = 20.dp h / 8.dp v, verticalArrangement = spacedBy(16.dp))`,
  `widthIn(max = 640.dp)`, keys = wallet id; footer item. Cards: `Surface(shape = RoundedCornerShape(22.dp),
  color = card)`; header row `Modifier.clickable` with ripple clipped to the card; actions `OutlinedButton`/
  `FilledTonalButton` with `CircleShape`/full-pill shape, `Modifier.weight(1f)`. Optional polish: shared-element
  transition of the avatar + name into the detail header (`SharedTransitionLayout`).
- Detail: `LazyColumn` of grouped `Surface` sections (inset-grouped look) or `ListItem`s inside rounded containers,
  section headers/footers as `labelLarge`/`bodySmall` text; destructive row in `error` colour. `TopAppBar`
  (small/center) with the wallet name.
- Segmented pickers ("Pairing level", "Account type", "Derivation path", "Export as"): Material 3
  `SingleChoiceSegmentedButtonRow` + `SegmentedButton` (or Ownbit `SegmentedControl` for an iOS-like look).
  ChainPicker: custom `Row` of tiles with `animateColorAsState`/`animateFloatAsState` and a sliding indicator
  (`Modifier.offset` animated from tile bounds) — `Role.Tab`, `selectableGroup()`.
- ProfileMenu: whole-row `Surface(onClick)` anchoring a `DropdownMenu` (or `ExposedDropdownMenuBox`) with radio-style
  items; trailing `UnfoldMore` icon.
- Context menu on address rows: `Modifier.combinedClickable(onClick = open sheet, onLongClick = show DropdownMenu)`
  with `HapticFeedbackType.LongPress`.
- Sheets: `ModalBottomSheet(skipPartiallyExpanded = true)` for the address detail and the four security sheets (or
  full-screen dialogs for password flows); `sheetState` with `confirmValueChange = { !busy }` and a `BackHandler` while
  busy (≙ `interactiveDismissDisabled`). Rename and the delete password prompt: `AlertDialog` with
  `OutlinedTextField` / `OutlinedSecureTextField`.
- Toasts: a custom bottom capsule (or `SnackbarHost`) with the success haptic; announce with
  `View.announceForAccessibility` or a polite live region. On API 33+ the system already shows a clipboard
  confirmation — suppress the in-app "Copied" toast there (Ownbit `ClipboardHelper.systemShowsCopyConfirmation`), but
  keep the inline icon swap of `CopyableAddress` and Payload "Copied".
- PayloadDisclosure: `AnimatedVisibility(expandVertically + fadeIn)`, chevron `animateFloatAsState(90f)`; payload in a
  non-selectable `Text` (`FontFamily.Monospace`, 11 sp); `DetailLine` values in `SelectionContainer`.
- Icons (Material Symbols): lock.shield→Shield, qrcode.viewfinder→QrCodeScanner, plus→Add,
  square.and.arrow.down→Download, qrcode→QrCode2, list.bullet.rectangle→ListAlt, chevron.right→ChevronRight
  (auto-mirrored), wrench.and.screwdriver→Build, key.viewfinder→Key, lock.rotation→LockReset, pencil→Edit,
  trash→Delete, doc.on.doc→ContentCopy, checkmark→Check, play.fill/pause.fill→PlayArrow/Pause, eye→Visibility,
  envelope→Mail, chevron.up.chevron.down→UnfoldMore, callout tones→Info/CheckCircle/Warning/Report, app.fill→Apps.
- Haptics: selection → `View.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)` (API 34; fallback
  `CLOCK_TICK`); success → `CONFIRM` (API 30+; fallback `CONTEXT_CLICK`).
- Dynamic Type mapping: "accessibility size" ≙ `LocalDensity.current.fontScale ≥ 1.6` (Ownbit constant);
  "≥ xxLarge" ≙ `fontScale ≥ 1.3`.

### 13.4 State, ViewModels and engine use

- `ConnectViewModel`: holds the §7.1 state in a `StateFlow<ConnectUiState>`; `requestKey` derived;
  `snapshotFlow`/`combine` → `distinctUntilChanged()` → `collectLatest { load(it) }` reproduces `.task(id:)`
  (cancel + key check). Optional 150–250 ms debounce for typed TRON indexes only (Ownbit does this); stepper taps stay
  immediate. Keep the "first result without animation" rule (skip `AnimatedContent` for the first payload).
- `AddressesViewModel`: `listKey` flow → `collectLatest` reload; `loadMore()` guarded exactly as §6.1. Trigger it
  with `LaunchedEffect(listState) { snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.key }
  .filter { it == accounts.last().id } … }` (≙ last-row `onAppear`). **Use `Long` for `limit`, `start + count`
  and `limit - start`** (2^31 overflows `Int`). Item keys = `PublicAccount.id`.
- Engine: QuickJS (`wang.harlon.quickjs:wrapper-android`) contexts are not thread-safe — run every call on one
  dedicated single-thread dispatcher (≙ the Swift actor; also serializes prefetch with user calls), keep one warm
  context for the three public operations, create/destroy a fresh context for everything else, destroy the warm
  context on `suspend()`. Pass JSON strings only (never interpolate into script). Use kotlinx.serialization
  `JsonElement` end-to-end so integers stay integers (`version`, `index`, `depth`).
- Prefetch: in `WalletStore` (singleton), a `Job` started 600 ms after the gate opens
  (`ProcessLifecycleOwner` ≥ STARTED ∧ !appLocked ∧ !screenRecording ∧ wallets visible), `Dispatchers.Default`
  low priority for QR encoding; cache `Map<walletId, PreparedConnect(payload, matrices)>` in memory only; drop on
  epoch change / reload / ON_STOP. Pass the matrices to the QR composable so the first frame is complete.
- Security epoch: app-scoped `StateFlow<Long>` bumped on ON_STOP (background), App Lock, screen recording
  (`WindowManager.addScreenRecordingCallback`, API 35) — sheets observe it to clear secrets and to drop late results
  (port `WalletOperation` as a small class owning a `Job` + token + epoch check).

### 13.5 QR codes

- Encoder: ZXing core `com.google.zxing.qrcode.encoder.Encoder.encode(text, level, hints)`: **level M when the frame is
  ≤ 600 UTF-8 bytes, else L** (→ Coin Level/addresses M, Wallet Level L); set `CHARACTER_SET=UTF-8` only when the text
  has non-ASCII (avoids an ECI header; UR frames are upper-case ASCII so ZXing picks alphanumeric mode); margin 0 —
  the plate's white inset is the quiet zone. Ownbit `animatedQrOptions()` does exactly this.
- Rendering: 1 px per module `ImageBitmap`, drawn with `FilterQuality.None` at an integer module size, centred, on a
  white `RoundedCornerShape(24.dp)` plate, inset 18 dp, `widthIn(max = maxSide)` + `aspectRatio(1f)`, soft shadow
  (dark mode: optional 1 dp hairline in light mode as Ownbit). Encode all frames on `Dispatchers.Default` before
  showing the first (spinner meanwhile).
- Animation: `LaunchedEffect(frames, ready, paused) { if (frames.size > 1 && ready && !paused) while (isActive) {
  delay(250); index = (index + 1) % frames.size } }`; reset `index` to 0 when `frames` changes; no cross-fade (the
  motion carries data; also under "remove animations"). Stop ticking when the lifecycle is below RESUMED
  (`LifecycleResumeEffect`) — saves battery, invisible to the user. Controls: 36 dp circular tonal `IconButton` in a
  48 dp target, `LinearProgressIndicator(progress = (i+1)/n, width 140 dp)`, counter `"%1$d/%2$d"` with tabular
  figures and fixed width. Content description "Animated QR code, part %1$d of %2$d" updated (polite live region)
  only while paused; "QR code" for static codes.
- Keep screen on: `FLAG_KEEP_SCREEN_ON` while any pairing/address QR is composed (Ownbit `KeepScreenOn()`;
  reference-counted, applied to the **dialog window** when inside a `ModalBottomSheet`). iOS relies on the system idle
  timer; this is an Android addition.
- Brightness: `WindowManager.LayoutParams.screenBrightness = BRIGHTNESS_OVERRIDE_FULL`, restored on dispose
  (Ownbit `MaxBrightness()`). Recommended UX: Ownbit's opt-in "Normal / Bright" corner toggle on the plate
  (`QrBrightnessCorner`, product decision 2026-09-27) rather than forcing it; if forced, do it only while the
  Wallet Level animated QR is on screen. iOS has neither — any new label (e.g. Normal/Bright) is a **new string not in
  the 21-language set** and needs translation.

### 13.6 Clipboard

`AppClipboard.copy(context, text)`: `ClipData.newPlainText(label, text)` with
`description.extras = PersistableBundle { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }` (API 33 constant;
harmless earlier — keeps xpub/addresses out of the clipboard preview and keyboard history, closest to iOS
`localOnly`), plus an in-process clear after **120 s** if the clip is still ours (Ownbit `copySensitive` with
`clearAfterMillis = 120_000`). Use it for every copy in this area (fingerprint, address, QR data, payload).
Process death leaves the clip until Android's own expiry — document, as Ownbit does.

### 13.7 Text, numbers, dates, RTL

- Monospace: bundle a mono font with clear 0/O 1/l (e.g. JetBrains Mono or Roboto Mono) for addresses, paths,
  fingerprints and payloads; `fontFeatureSettings = "tnum"` for counters/index field.
- AddressText: `buildAnnotatedString` with `SpanStyle(fontWeight = SemiBold, color = onSurface)` for edge groups and
  `onSurface.copy(alpha = 0.78f)` for the middle; groups separated by plain spaces so lines break between groups.
- Arabic is one of the 21 languages: force **LTR** for addresses, paths, fingerprints, UR text and `i/n` counters
  (`TextDirection.Ltr` / U+2066…U+2069 isolates inside translated sentences such as "Pair %@", "Fingerprint %@").
- Numbers: `NumberFormat.getIntegerInstance(appLocale)` for `byteCount.formatted()` and the 30,000-char note; the
  wallet-level Format line uses the raw integer. Dates: `DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)` in the
  app locale (per-app language via `AppCompatDelegate.setApplicationLocales`).
- Name length: count graphemes (`BreakIterator.getCharacterInstance()`) for the 1–40 rule and for the avatar initial;
  uppercase with `Locale.ROOT`.
- strings.xml: escape `'` (`\'`) in "This wallet's public data…" / "BitTool Signer's QR codes"; keep curly `’`
  as-is; `%` literals none here; `translatable="false"` for the (V) strings (chain/network names, preset titles and
  details, export-mode titles, wallet names/platforms, `Setup required`, format summaries, the 32-bit fingerprint
  note, `Payload (Hex)`/`Payload (Text)` unless translations are added).

### 13.8 Password fields

`OutlinedSecureTextField` (Material3 1.4) / `BasicSecureTextField` with `KeyboardType.Password`, no autocorrect, IME
actions Next/Done/Go mirroring `submitLabel`; exclude from Autofill save (`ContentType` none / `importantForAutofill
= NO`) — wallet passwords must not go to a synced password manager (iOS `isNew` rationale). Auto-focus the Recovery
Phrase password after 350 ms (`LaunchedEffect { delay(350); focusRequester.requestFocus() }`).
`FLAG_SECURE` on the **sheet's own window** while the Recovery Phrase sheet is showing (a `ModalBottomSheet`/`Dialog`
is a separate window — apply via `DialogWindowProvider`, as Ownbit `hostWindow()`).

### 13.9 Pitfalls checklist

1. 2^31 limits overflow `Int` (Addresses `limit`, `start + count`); TRON index max 2,147,483,647 fits `Int`.
2. Do not show a stale QR: compare the payload's key with the current `requestKey` before rendering, and keep the
   placeholder while a new selection loads.
3. Wallet Level is always multi-frame (≥ 2); never fall back to a static QR for it; EC must be L for 831-char frames
   (M would push the QR version up and hurt scanning).
4. `WalletPairingPayload.Line` identity must include the profile (BTC ×4, SOL ×3 share a chain).
5. Upper-case UR frames must be encoded as given (don't lowercase; don't add a URI scheme); the copied "QR data" is
   frames joined by `\n`.
6. Engine messages are shown verbatim and are English — do not try to localize them.
7. QuickJS single-thread confinement; release the warm context on background.
8. ModalBottomSheet windows need their own keep-screen-on / brightness / FLAG_SECURE handling.
9. Chain names in "Pair %@" and "%@ address #%lld" are the untranslated `Chain.name`.
10. The delete password dialog's "Delete" is always enabled; empty password → "Enter this wallet’s password."

## 14. Behaviour that depends on other specs

- App Lock biometrics before delete (`AppAccessController.confirmSensitiveAction`, `Services/AppAccessController.swift:157-160`):
  only when App Lock is enabled and biometrics are available; the footer then reads "Deleting asks for %@, then for this wallet’s password."
  with the biometry name, and the prompt reason is "Confirm before deleting this wallet." *(L)*. If the Android App Lock
  spec adopts `BiometricPrompt`, mirror this (cancel → abort, error → fall through to the password); otherwise always
  use "Deleting asks for this wallet’s password."
- Recovery phrase panel (`RecoveryBackupPanel`, `ProtectedRecoveryPhrase`, `RecoveryPrivacySession`) — backup spec.
- Setup flow (create/import, "Pair a Wallet" completion) — setup spec.
- Scan tab (`router.startScanning`) — scan spec. Settings › Compatibility uses `CompatibleWallets.all(for:)`,
  `directory`, `WalletGroup` — settings spec.
- Vault encryption, password back-off, public sidecar HMAC — storage spec.

## 15. Doc/code discrepancies (code wins)

- `docs/Compatibility.md:49` says the Pair page "still opens on [Coin Level] by default"; `docs/Wallet-Pairing-v1.md`
  says it opens on Wallet Level. Code: it depends on the route — wallet card "Pair", detail "Wallet Level" row and
  setup completion open **Wallet Level**; detail chain rows open **Coin Level** for that chain; the segmented control
  always lists Wallet Level first.
- `engine/README.md:43` describes `createWalletPairing` as "one static single-part `ur:bytes` QR"; the bundled engine
  produces a multi-part QR with 400-byte fragments and refuses < 2 frames (`engine/protocols.js:675-688`).
- `Compatibility.md:87` cites `ur:crypto-account · CBOR · 162 bytes`; the current bundle gives 170 bytes for the test
  wallet (the name/note strings were added) — use live engine output in tests, not the doc number.
- The engine's `note` in `createPublicPairing` results is not displayed anywhere on iOS.

## 16. Out of scope (iOS-only — drop)

- Face ID / Touch ID naming in the delete footer and prompt (`access.biometry.name`); replace only via the Android App
  Lock decision (§14).
- Liquid Glass styles and iOS 26 / iOS 17–18 fallbacks (`prominentAction`, `secondaryAction`, `safeAreaBar`,
  `wallet.bifold` vs `wallet.pass` symbol).
- `RecoveryPhraseSheet`'s single-container workaround for the UIKit navigation-bar crash while snapshotting.
- `MaskedTextField` UIKit bridge, `passwordRules`, keyboard accessory toolbars, `UIPasteboard` Handoff options
  (replaced by §13.6 / §13.8), CoreImage software context, `matchedGeometryEffect`/SF Symbol effects (replaced by
  Compose equivalents).
- Simulator UI-test context (`Services/SimulatorUITestContext.swift`), App Store / TestFlight specifics,
  `PrivacyInfo.xcprivacy`.
