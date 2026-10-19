# BitTool Signer Android port: domain models and app state

Source of truth: the iOS app at `/Users/tanguopeng/Documents/bitsigner-ios/BitSigner`. In this document every
Swift path is relative to that root, written `path:line`. Engine sources are in `engine/` (the bundled
`BitToolSigner/Resources/signer.js` is built from them).

This spec covers:

- every model type in `BitToolSigner/Models/*.swift`;
- the types they depend on and that the implementers need in the same layer: `NetworkBrand` and `AddressFormatter`
  from `Design/`, `AccountProfile` and `PairingLevel` from `Features/Wallets/ConnectView.swift`, and `VaultMetadata`,
  `VaultSecret`, `SecureRandom` and `PublicWalletSnapshot` from `Core/` and `Services/`;
- the state machine and every public method of `AppStore`;
- `TokenIconStore`.

Vault cryptography, QR rendering and the screens are only described as far as the model layer touches them. Other port
specs cover them in full.

---

## 0. Cross-cutting conventions (read first)

1. **English is the localisation key.** Every user-visible string below is quoted exactly as written. iOS looks it up
   in `translations/<code>.json` → `strings` (equivalently `<code>.lproj/Localizable.strings`). If a key has no
   entry, the English text is shown. Swift interpolations become format specifiers in the key: `\(String)` → `%@`,
   `\(Int)` → `%lld`. Translated values may use positional forms such as `%1$@` and `%2$lld`.
   - Strings created with `String(localized:…, bundle: AppText.bundle)` are **localised**.
   - Strings that are plain Swift `String`s and are shown verbatim are **English only on iOS**. Examples are
     `matchingError` values, `QRPayloadView.summary`, the "+N more" line and the weak-card warning. Each one is
     flagged as *(not localised on iOS)*. Android may keep them English for parity, or add resources; translations
     for most of them do not exist in the 21 tables.
2. **Lenient-but-typed JSON reading.** iOS parses engine results with `JSONSerialization` and reads each field with
   `raw["k"] as? T`. A value of the wrong JSON type reads as *absent*. A string is never a number, and a number is
   never a string. `as? [String]` / `as? [[String: Any]]` fail **as a whole** when any element has the wrong type.
   Android must use strict typed readers that return `null` on a type mismatch. Ownbit already has them:
   `core/publicsigner/src/main/kotlin/io/ownbit/core/publicsigner/model/EngineJson.kt` (`str`, `int`, `bool`, `obj`,
   `arr`, `strings`, `ints`). Copy that file.
3. **Swift `String.count` counts grapheme clusters, not UTF-16 units.** Every length limit below that uses `.count`
   counts user-perceived characters: 300 / 280 / 200 / 160 / 40 / 12 / 120 / 128. Android must count graphemes with
   `android.icu.text.BreakIterator.getCharacterInstance()`, and `prefix`/`suffix` must cut on grapheme boundaries.
   Limits written with `.utf8.count` are **UTF-8 byte** counts (`s.encodeToByteArray().size`).
4. **Pass-through JSON stays raw.** Several engine objects go back to the engine unchanged later: `publicIndex`, the
   request, the plan, the previous `signPlanTask` result. They must be kept as `kotlinx.serialization.json.JsonObject`
   exactly as received. Never round-trip them through data classes. The engine re-hashes them (`planHash ===
   requestHash(body)`, `engine/src/resolver.js:225+`), so dropping or adding one key breaks signing.
5. **Canonical JSON.** iOS freezes documents with `JSONSerialization.data(..., [.sortedKeys])`. Compact output, keys
   sorted recursively. Android needs a `canonicalJson(JsonElement): String` helper that sorts keys recursively. Size
   limits are measured on this output. (iOS also escapes `/` as `\/` in this mode. That is cosmetic: hashes are
   computed by the engine on the parsed value.)
6. **Pretty JSON.** Display payloads use `[.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]`. Apple prints a
   2-space indent and `"key" : value`, with a space before the colon. Kotlin's `prettyPrint` with
   `prettyPrintIndent = "  "` prints `"key": value`. That difference is cosmetic and acceptable. Keys must still be
   sorted recursively.
7. **UUIDs.** Swift `UUID.uuidString` is UPPERCASE. iOS sends it as `localWalletId` to the engine and uses it as the
   avatar fallback seed. Parsing is case-insensitive in both directions. Android can use lowercase everywhere except
   in the avatar fallback (§2.7), which should use `toString().uppercase()` to get the same colours.

---

## 1. Chain families and networks

### 1.1 `Chain` (`BitToolSigner/Models/Models.swift:3-32`)

`enum Chain: String, CaseIterable, Codable`. **Case order matters**: it drives the `ChainPicker` order (Bitcoin,
EVM, TRON, Solana).

| case | raw (`id`, JSON) | `name` | `longName` | `ticker` | `primaryNetwork` (`Design/Logos.swift:72-81`) |
|---|---|---|---|---|---|
| `bitcoin` | `"bitcoin"` | "Bitcoin" | "Bitcoin" | "BTC" | `.bitcoin` |
| `evm` | `"evm"` | "EVM" | "Ethereum & EVM" | "EVM" | `.ethereum` |
| `tron` | `"tron"` | "TRON" | "TRON" | "TRX" | `.tron` |
| `solana` | `"solana"` | "Solana" | "Solana" | "SOL" | `.solana` |

- `longName` is used "where a chain family is listed on its own": the accessibility label of the chain picker, the
  Compatibility headers, and wallet-level verification rows.
- Only "Bitcoin" has a translation entry. The other names are shown untranslated.
- When the engine sends an unknown chain string, `PublicAccount` and `TransactionReview` fall back to `.bitcoin`
  (`Models.swift:43,382`). `SigningTask` instead **rejects** an unknown chain (`Models.swift:672`).

### 1.2 `NetworkBrand` (`BitToolSigner/Design/Logos.swift:6-70`)

These are the networks with bundled vector logos. Logos never load from the network.

| case | `name` | `shortName` | `nativeSymbol` | `asset` (logo) | `evmChainID` |
|---|---|---|---|---|---|
| `bitcoin` | "Bitcoin" | "Bitcoin" | "BTC" | `ChainBitcoin` (bitcoin.svg) | – |
| `ethereum` | "Ethereum" | "Ethereum" | "ETH" | `ChainEthereum` (ethereum.svg) | "1" |
| `bnb` | "BNB Smart Chain" | "BNB Chain" | "BNB" | `ChainBNB` (bnb.svg) | "56" |
| `arbitrum` | "Arbitrum One" | "Arbitrum" | "ETH" | `ChainArbitrum` (arbitrum.svg) | "42161" |
| `base` | "Base" | "Base" | "ETH" | `ChainBase` (base.svg) | "8453" |
| `tron` | "TRON" | "TRON" | "TRX" | `ChainTron` (tron.svg) | – |
| `solana` | "Solana" | "Solana" | "SOL" | `ChainSolana` (solana.svg) | – |

- `static func evm(chainID: String?) -> NetworkBrand?` returns the first case whose `evmChainID == chainID`, or `nil`
  (`Logos.swift:63-66`).
- `static let evmNetworks = [.ethereum, .bnb, .arbitrum, .base]` lists the networks that share one EVM account
  (`Logos.swift:69`).
- `ChainGlyph` (`Logos.swift:105-123`) draws EVM as a 2×2 grid: Ethereum and BNB on top, Arbitrum and Base below.
  Each logo is `size*0.5-1` with 2 pt spacing. Its accessibility label is "EVM networks". Other chains draw their
  `ChainLogo`.
- `NetworkLogo` (`Logos.swift:83-96`) draws the logo with a circle stroke of `primary @ 14%` opacity and width
  `max(0.5, size/56)`. Its accessibility label is `network.name`.
- `GenericNetworkLogo` (`Logos.swift:126-138`) is used for reviewed EVM networks that have no logo. It is a
  systemGray5 circle with the SF Symbol `point.3.connected.trianglepath.dotted` at 46% of the size, semibold,
  secondary colour.
- `NetworkLogoStack` (`Logos.swift:141-157`) draws overlapping logos with spacing `-size*0.28`. Each logo has padding
  `max(1.5, size*0.08)` and a ring in the surrounding card colour. Its accessibility label is the `shortName`s joined
  by ", ".

### 1.3 EVM networks the engine supports (`engine/src/token-registry.js:4`)

The engine reports `review.network = {chainId, name, symbol}`. The supported networks are:

| chainId | name | symbol | bundled logo? |
|---|---|---|---|
| 1 | Ethereum | ETH | yes |
| 56 | BNB Smart Chain | BNB | yes |
| 42161 | Arbitrum One | ETH | yes |
| 8453 | Base | ETH | yes |
| 10 | OP Mainnet | ETH | no → `GenericNetworkLogo` |
| 137 | Polygon PoS | POL | no |
| 100 | Gnosis | xDAI | no |
| 43114 | Avalanche C-Chain | AVAX | no |

`ReviewNetwork.brand` (`Models.swift:116-121`) resolves the logo:

- EVM: `NetworkBrand.evm(chainID:)`, which is `nil` for the last four networks.
- Other chains: `family.primaryNetwork`.

### 1.4 Account profiles: BTC script types and Solana presets (`Features/Wallets/ConnectView.swift:3-22`)

`struct AccountProfile: Identifiable, Hashable { id, title, detail }`. `title` is a picker label and `detail` is the
caption.

`bitcoinSingle` (this order):

| id | title | detail | engine origin (`engine/src/public-index.js:10-13`) | first receive address |
|---|---|---|---|---|
| `native` | "Native SegWit" | "BIP84 · bc1q…" | `m/84'/0'/0'` | `…/0/0` P2WPKH |
| `taproot` | "Taproot" | "BIP86 · bc1p…" | `m/86'/0'/0'` | P2TR (x-only key) |
| `nested` | "Nested SegWit" | "BIP49 · 3…" | `m/49'/0'/0'` | P2SH-P2WPKH |
| `legacy` | "Legacy" | "BIP44 · 1…" | `m/44'/0'/0'` | P2PKH |

`bitcoinMulti` exports one cosigner key; a cosigner alone has no receive address:

| id | title | detail | origin |
|---|---|---|---|
| `multisig-native` | "Native SegWit" | "BIP48 · P2WSH" | `m/48'/0'/0'/2'` |
| `multisig-nested` | "Nested SegWit" | "BIP48 · P2SH-P2WSH" | `m/48'/0'/0'/1'` |

`solana` (engine `SOLANA_PROFILES`, `engine/src/public-index.js:14`):

| id | title | detail (= path) |
|---|---|---|
| `standard` | "Standard" | `m/44'/501'/0'/0'` |
| `trust` | "Trust" | `m/44'/501'/0'` |
| `legacy` | "Legacy" | `m/44'/501'` |

EVM and TRON have one account each:

- EVM is `m/44'/60'/0'`. The default address is `m/44'/60'/0'/0/0`.
- TRON is `m/44'/195'/0'`. Receive indexes are `m/44'/195'/0'/0/i`, with `0 ≤ i ≤ 2,147,483,647`
  (`AddressIndexControl.maximum`, `ConnectView.swift:485`).

The profile titles and details have **no translation entries**: they are shown in English in every language.

`WalletLevelPairingView.accountName` (`ConnectView.swift:428-438`) builds a label such as `"Bitcoin · Taproot"` or
`"Solana · Trust"` from `chain.longName + " · " + preset.title`. It works for the codes `btc` and `sol` only; every
other code returns `nil` and the row falls back to `chain.longName`. The wallet-level chain codes are
`[("btc", .bitcoin), ("evm", .evm), ("tron", .tron), ("sol", .solana)]` (`ConnectView.swift:366`).

### 1.5 Pairing levels and export modes

- `enum PairingLevel { coin, wallet }` (`ConnectView.swift:350`). Connect opens on `.wallet` by default.
- Defaults: `AppStore.defaultConnectProfile = "native"` and `AppStore.defaultConnectMode = "accountXpub"`
  (`AppStore.swift:256-257`).
- Coin Level, profile per chain (`ConnectView.swift:116-122`):
  - bitcoin: the multisig profile when multisig is on, otherwise the single-sig profile;
  - solana: the Solana profile;
  - EVM and TRON: `"standard"`.
- Output mode per chain (`ConnectView.swift:123-130`):
  - bitcoin: `"accountXpub"` when multisig is on, otherwise the chosen mode;
  - evm: `"wallet"` | `"address"`;
  - solana: `"wallet"` | `"address"`;
  - tron: always `"address"`.
- Export-mode segmented choices (`ConnectView.swift:251-257`):
  - Bitcoin single-sig: `("accountXpub","Account XPUB"), ("address","Address")`;
  - EVM and Solana: `("wallet","Wallet"), ("address","Address")`;
  - Bitcoin multisig and TRON: none.
- Address index: only TRON exports a chosen index; every other chain exports 0 (`ConnectView.swift:132`).

### 1.6 `AddressFormatter` (`BitToolSigner/Design/AddressText.swift:6-39`)

Grouping only changes presentation. Copying always uses the original string.

- `groups(value, size = 4) -> [String]`
  - If `size <= 0` or the value is empty, returns `[value]`.
  - If the value starts with `0x` or `0X`, the first group is the prefix plus the next `size` characters
    (`"0x" + 4`).
  - The remainder is cut into chunks of `size`. The last chunk may be shorter.
- `short(value, leading = 6, trailing = 4) -> String`
  - `hexPrefix = 2` only when the value starts with **lowercase** `"0x"`.
  - If `value.count <= leading + trailing + hexPrefix + 1`, returns the value unchanged.
  - Otherwise returns `prefix(leading + hexPrefix) + "…" + suffix(trailing)`, with U+2026 as the ellipsis.
- `looksLikeAddress(value) -> Bool`. Trim whitespace and newlines first. Reject a value that contains `" "` or `"\n"`
  or whose length is outside `25...90`. Then the value is an address if it matches any of:
  1. `^0x[0-9a-fA-F]{40}$` (EVM)
  2. `^T[1-9A-HJ-NP-Za-km-z]{33}$` (TRON)
  3. `^(bc1|[13])[0-9A-Za-z]{20,87}$` (Bitcoin)
  4. `^[1-9A-HJ-NP-Za-km-z]{32,44}$` (Solana / base58)
- Presentation rules used everywhere:
  - `AddressText` (`AddressText.swift:43-62`): monospaced groups of 4 joined by single spaces. The **first and last
    groups** are semibold in primary colour; the middle groups are regular at `primary @ 78%`. The accessibility label
    is the groups joined by ", ".
  - `HashText` (`AddressText.swift:65-76`): **uppercased**, groups of 8 joined by " ", caption monospaced, secondary,
    selectable.
  - `CopyableAddress` (`AddressText.swift:79-109`):
    - A trailing copy button with SF Symbol `doc.on.doc`. After copying it shows `checkmark` for 1.5 s with a success
      haptic.
    - Its accessibility label is "Copied" or "Copy address".
    - A context menu offers "Copy Address".

### 1.7 Wallet identity colour (`WalletAvatar`, `BitToolSigner/Design/Logos.swift:190-220`)

The seed is `store.avatarIdentity(for:)`: the wallet's `walletKeyId`, or, before setup, the vault UUID string in
uppercase (`AppStore.swift:153`). The colour is a visual aid only.

- `bytes = SHA-256(UTF-8(identity))`
- `hue = (bytes[0] << 8 | bytes[1]) / 65535`
- `shift = 0.08 + bytes[2] / 255 * 0.14`
- gradient colours: `HSB(hue, 0.62, 0.78)` → `HSB((hue + shift) mod 1, 0.70, 0.62)`, running top-leading to
  bottom-trailing
- shape: a continuous rounded rectangle with radius `size*0.3`
- initial: the first character of the trimmed name, uppercased, or "W" when the name is empty. It is white, rounded,
  semibold, at `size*0.44`, with a black 15% shadow (radius 1, y 1).
- The avatar is hidden from accessibility.

---

## 2. Wallet identity and public data

### 2.1 `VaultMetadata` and `VaultSecret` (`BitToolSigner/Core/VaultCrypto.swift:65-76`)

- `VaultMetadata { id: UUID, name: String, createdAt: Date }` is Codable and Hashable. It is what `vault.list()`
  returns. The vault requires the name to be non-empty and at most 40 characters (`VaultCrypto.swift:200`).
- `VaultSecret { mnemonic: String, passphrase: String }` is returned only after authentication. The caller must drop
  it immediately and never cache it.

### 2.2 `PublicMetadataStatus` (`Models.swift:466`)

`enum PublicMetadataStatus: String { ready, needsCompletion, invalid }`. It is sent to the engine verbatim as
`publicMetadataStatus` for a wallet that has no index. `AppStore.metadataStatus(for:)` defaults to
`.needsCompletion`.

`PublicWalletRepository.snapshot` decides the status (`Services/PublicWalletRepository.swift:27-41`):

- no file → `.needsCompletion`, name nil;
- a file exists but there is no public-data key → `.invalid`;
- the file reads and authenticates but has no `publicIndex` → `.needsCompletion`, with the stored `displayName`;
- the index parses as `WalletPublicMetadata` → `.ready`;
- any other failure (bad MAC, a name over 40 characters, a parse error) → `.invalid`, name nil.

`PublicWalletSnapshot { displayName: String?, metadata: WalletPublicMetadata?, status }`
(`PublicWalletRepository.swift:5-9`).

The sidecar file stores the public index plus the display name. Its format is
`{"schemaVersion":2,"body":<base64 of sorted-keys JSON>,"mac":<64 hex>}`, where the body is
`{"localWalletId", "publicIndex", "displayName"?}`.

- The MAC is `HMAC-SHA256(key, "com.bittool.signer.public-wallet.v2\0" + lowercase uuid + "\0" + body)`.
- The tag is compared in constant time.
- The file may be at most 600,000 bytes; the body at most 300,000 bytes.
- The path is `PublicWallets/<lowercase uuid>.json`.
- Writes go to a staged `.pending-<UUID>` file followed by an atomic rename.
- The name is valid when trimmed-non-empty and at most 40 characters (`PublicWalletRepository.swift:66-68`).

The full storage design is covered by the vault/storage spec.

### 2.3 `WalletPublicMetadata` (`Models.swift:470-531`): the frozen, public-only wallet index

Fields:

- `jsonData`: the raw index as sorted-keys JSON;
- `walletKeyId`: lowercased;
- `masterFingerprint`: lowercased;
- computed `raw` (re-parsed `jsonData`);
- `version` returns the constant `2`. **It is unused, and it is not the JSON `version`, which must be 1.**
- `evmAddress = raw.evmDefaultAddress.address` (`Models.swift:505`). It is the first EVM receive address, used for
  summaries.

The engine produces the index with `derivePublicIndex` (`engine/src/public-index.js:26-35`):

```
{ version:1, walletKeyId:<64 hex>, masterFingerprint:<8 hex>,
  bitcoinPublicNodes:[6 × node], evmAccountNode:node, tronAccountNode:node,
  evmDefaultAddress:{chain,profile,path,index,branch,publicKey,address,masterFingerprint},
  tronAddressIndex:[100 × {chain,profile,path,index,branch,publicKey,address,masterFingerprint}],
  solanaPublicKeys:[3 × {chain:"solana",profile,path,index:0,publicKey(32B hex),address(base58),masterFingerprint}] }
node = {chain, originPath, accountPath, path, xpub, publicKey, compressedPublicKey, chainCode,
        parentFingerprint, depth, scriptProfile, children, masterFingerprint}
```

`init(raw)` validates in this exact order. Every failure throws `AppIssue.message`.

1. Structure (`Models.swift:478-486`). Any miss throws **"Invalid public wallet data. Repair this wallet."**
   - `version` is an Int equal to `1`.
   - `walletKeyId` is hex of 32 bytes (64 characters).
   - `masterFingerprint` is hex of 4 bytes.
   - `bitcoinPublicNodes` is an array of dicts with count 6.
   - `evmAccountNode` and `tronAccountNode` are dicts.
   - `tronAddressIndex` is an array of dicts with count 100.
   - `solanaPublicKeys` is an array of dicts with count 3.
   - `publicOnly(raw)` holds:
     - recursion depth ≤ 12;
     - no dict key whose lowercase form is in {`mnemonic`, `seed`, `privatekey`, `privateextendedkey`, `password`,
       `passphrase`, `xprv`, `secret`};
     - arrays have ≤ 256 items;
     - strings are ≤ 2048 UTF-8 bytes and do not start with `xprv`;
     - every other value is a number, boolean or null.
2. Consistency (`Models.swift:487-498`). Any miss throws **"Public wallet data is incomplete or inconsistent."**
   - The set of `bitcoinPublicNodes[].originPath` is exactly {`m/84'/0'/0'`, `m/86'/0'/0'`, `m/49'/0'/0'`,
     `m/44'/0'/0'`, `m/48'/0'/0'/2'`, `m/48'/0'/0'/1'`}.
   - `evmAccountNode.originPath == "m/44'/60'/0'"` and `tronAccountNode.originPath == "m/44'/195'/0'"`.
   - Every BTC, EVM and TRON node passes `validNode`:
     - `publicKey` is 33-byte hex starting `02` or `03`;
     - `chainCode` is 32-byte hex;
     - `parentFingerprint` is 4-byte hex;
     - `depth` is an Int in `3...4`;
     - `xpub` has the prefix `"xpub"` and length < 120.
   - The set of Solana `path`s is exactly {`m/44'/501'/0'/0'`, `m/44'/501'/0'`, `m/44'/501'`}. Each entry passes
     `validAddress(keyBytes: 32)`: `publicKey` is hex of exactly 32 bytes, and `address` is non-empty and at most 128
     characters.
   - The set of TRON `index`es is exactly `0..<100`. Each item has `path == "m/44'/195'/0'/0/<index>"` and passes
     `validAddress(keyBytes: 33)`.
3. Size. The sorted JSON must be ≤ 262,144 bytes, otherwise **"Public wallet data is too large."**
   (`Models.swift:499-500`).

`isHex(v, bytes)` means `v.count == bytes*2 && v ~ ^[0-9a-fA-F]+$` (`Models.swift:507-509`).

### 2.4 `PublicAccount` (`Models.swift:34-49`)

This is one derived receive address.

- Fields: `chain`, `address`, `path`, `publicKey`, `index`.
- `id = chain.rawValue + ":" + path`.
- Parsing from a `derivePublicAddresses` item: `chain` (unknown → `.bitcoin`); `address`, `path` and `publicKey`
  (missing → `""`); `index` (Int, missing → 0).
- The engine also sends `profile`, `branch` and `masterFingerprint`; they are ignored.

---

## 3. Engine call contract and result models

### 3.0 Engine envelope (`BitToolSigner/Core/SigningEngine.swift`) as `AppStore` sees it

`protocol OfflineSigningEngine { run(operation, parameters) async throws -> [String: Any]; releaseWarmContext() }`
(`AppStore.swift:5-12`). It is satisfied by `SigningEngine`.

- Request JSON = parameters plus `"operation": <op>`, serialised with sorted keys. It may be at most 1,048,576 bytes,
  otherwise code `REQUEST_TOO_LARGE`, "This request exceeds the 1 MB offline signing limit."
  (`SigningEngine.swift:184-195`).
- Allowed operations in production (`SigningEngine.swift:118-122`): `mnemonic`, `capabilities`, `derivePublicIndex`,
  `derivePublicAddresses`, `createPublicPairing`, `createWalletPairing`, `decodeQR`, `decodePayload`, `review`,
  `resolveSigningPlan`, `signPlanTask`, `encodeResponse`. Any other operation fails with `UNSUPPORTED_OPERATION`,
  "This signing operation is not supported."
  - DEBUG builds also allow `deriveAccounts`, `deriveBitcoinCosigner`, `encodeQR`, `sign`, `createPairing`,
    `validateMnemonic` and `wordlist`.
- Contexts:
  - "Warm" operations reuse one context: `derivePublicAddresses`, `createPublicPairing`, `createWalletPairing`.
  - Every other operation gets a fresh context.
  - A scan session has its own context: `scanBegin` / `scanAdd` (`SigningEngine.swift:156-178`).
  - `releaseWarmContext()` drops the warm context.
- Response handling (`SigningEngine.swift:74-94`):
  - A response over 2,097,152 bytes or with an unparsable envelope fails with `ENGINE_FAILURE`, "The offline engine
    could not process this request."
  - `ok:false` → `SigningEngineError(code, message)`. The code is cut to 80 characters (default `SIGNING_FAILED`). The
    message is cut to 400 characters (default "This request could not be signed.").
  - `ok:true` returns `result` when it is an object, otherwise `{"value": result}`, otherwise `{}`.
- `IncrementalQREngine` (`SigningEngine.swift:98-102`) has `beginScanSession()`, `scan(frame:)` and
  `endScanSession()`.

### 3.1 Operation table (what AppStore sends, and what it reads back)

| AppStore method | operation | parameters sent | result fields consumed |
|---|---|---|---|
| `generateMnemonic` | `mnemonic` | `{entropy: hex}`. The entropy is `SecureRandom.entropyHex(wordCount)`: `wordCount/3*4` random bytes (12→16, 15→20, 18→24, 21→28, 24→32), lowercase hex (`VaultCrypto.swift:59-62`). | `mnemonic` (String), else `value` (String). `wordCount` is ignored. |
| `createWallet`, `completeSetup`, `existingWallet`, `resetPassword` | `derivePublicIndex` | `{seed: <128-hex BIP39 seed>}` (`BIP39Seed.hex`: PBKDF2-HMAC-SHA512, 2048 rounds, NFKD) | The whole object becomes `WalletPublicMetadata(raw)`. |
| `addresses` | `derivePublicAddresses` | `{publicIndex: <raw index>, chain, profile, start, count}` | `addresses: [[String:Any]]` → `PublicAccount`. `hasMore` and `nextIndex` are ignored; the UI pages itself. |
| `pairing` | `createPublicPairing` | `{publicIndex, chain, profile, outputMode, index}` | → `PairingPayload` (§3.4) |
| `walletPairing` | `createWalletPairing` | `{publicIndex, mode: "full"}` | → `WalletPairingPayload` (§3.4) |
| `beginScan` | `scanBegin` (scan context) | `{}` | – |
| `receive(frame:)` | `scanAdd` (scan context) | `{frame}` | `complete` (Bool), `progress` (Double), `request` (object) |
| `receive(frames:)` | `decodeQR` | `{frames: [String]}` | `complete`, `progress`, `request` |
| `importPayload` | `decodePayload` | `{payload: String}` | `complete`, `request` |
| `prepare`, `review(storedRequest:)` | `review` | `{request}` | → `TransactionReview`, plus `canSign` (Bool) and `chain` |
| `prepare` | `resolveSigningPlan` | `{request, wallets: [{localWalletId: UUID-uppercase, publicIndex} \| {localWalletId, publicMetadataStatus}]}` | `status`, `message`, the whole object → `SigningPlan`, and `review` (refined) |
| `sign` | `signPlanTask` | `{request, plan: <whole resolved object>, taskId, seed, riskAcknowledged: Bool, previousResult?: <previous signPlanTask result>}` | `requestHash`, `planHash`, `taskId`. The whole object is kept as `previousResultData`. |
| `sign` (final task) | `encodeResponse` | `{request, result: <signPlanTask result>}` | `frames`, `text`. The engine never sends `text`, so it falls back to frames joined by `"\n"`. Also `payloadFormat`, `payload`, `payloadBytes`, `type` → `QRPayloadView`. |

Engine plan-resolution failure messages (`engine/src/resolver.js:17-18`) arrive as `resolved.message` with
`status != "ready"`:

| message | when |
|---|---|
| "No matching wallet" | no wallet matched |
| "Missing signer information" | the request does not identify a signer |
| "Ambiguous wallet match" | more than one wallet matched |
| "Wallet setup required" | no match, and at least one wallet has no valid index |
| an engine-specific message, or "Invalid or unsupported signing request" | the request is invalid |

The review screen switches on the first four to pick an explanation (`Features/Sign/RequestReviewView.swift:138-153`,
a separate spec).

Scan progress values (`protocols.js:297-300`):

- `1` when complete;
- BBQr: `fragments/count`;
- UR: `min(0.99, estimatedPercentComplete)`;
- otherwise `0`.

### 3.2 `TransactionReview` (`Models.swift:357-402`)

| field | source key | type / default |
|---|---|---|
| `chain` | `chain` | `Chain`, unknown → `.bitcoin` |
| `title` | `title` | String, default "Review request" *(not translated; engine titles are drawn through `ReviewText.phrase`)* |
| `summary` | `summary` | String, default `""` |
| `fields` | `fields` | `[[String:Any]]` (otherwise `[]`). Each item becomes `ReviewField(id: offset, label: field.label as? String ?? "", value: String(describing: field.value ?? ""))`. The engine always sends string values (`engine/src/util.js:31`). For non-strings Android should use the JSON text, as Ownbit's `displayText()` does. |
| `warnings` | `warnings` | `[String]` (otherwise `[]`) |
| `requestHash` | `requestHash` | String, default `""` |
| `asset` | `asset` | `ReviewAsset?`, strict-decoded (§3.2.2) |
| `decodeStatus` | `decodeStatus` | `DecodeStatus`, unknown → `.opaque` |
| `riskLevel` | `riskLevel` | `RiskLevel`, unknown → `.normal` when there are no warnings, otherwise `.warning` |
| `network` | `network` | Present only if `network.name` is a String. Then `ReviewNetwork(family: chain, chainID: network.chainId as? String, name, symbol: network.symbol as? String)`. |
| `display` | `display` | `ReviewDisplay(raw)` (§3.2.5) |
| `contract` | `contract` | `ReviewContract(raw)` (§3.2.4) |

Computed properties:

- `requiresAcknowledgement = decodeStatus != .fullyDecoded || riskLevel == .high`. Explicit per-request consent is
  required. The app is stricter than the engine here: the engine requires `riskAcknowledged` only for
  partially decoded or opaque requests (`engine/src/resolver.js:233`).
- `networkName`:
  1. `network.name`;
  2. else the value of the first field whose label is exactly `"Network"`;
  3. else `chain.name`.

Enums:

- `DecodeStatus: fullyDecoded | partiallyDecoded | opaque` (`Models.swift:349-351`). UI labels are "Decoded on
  device", "Partially decoded" and "Not decoded" (`RequestReviewView.swift:476-482`).
- `RiskLevel: normal | warning | high` (`Models.swift:353-355`).

#### 3.2.1 `ReviewField` (`Models.swift:51-94`)

`{id: Int (index), label, value}`.

- `group`: the text before the first `" · "` in `label`, or nil. For example "Instruction 2 · Recipient" gives
  "Instruction 2". It is currently not used by any view.
- `shortLabel`: the text after the first `" · "`, or the whole label.
- `isLong = value.count > 280 || (number of "\n" in value) > 6`.
- private `baseLabel`: take `shortLabel`; if it contains `": "`, keep the part after the **last** occurrence; then
  trim spaces and lowercase. For example "Call 2: Amount" gives "amount".
- `isTokenIdentifier`: `baseLabel` contains "token id", or contains "quantity", or equals "items", or starts with
  "items ".
- `holdsNumber`: `isTokenIdentifier`, or `baseLabel` contains one of "amount", "allowance", "raw units", "(wei)".
  These fields are matched by label, not value, because long digit strings would otherwise match the Bitcoin address
  pattern, while the Solana System Program ID (32 ones) must stay an address.
- `rendersAsAddress = !holdsNumber && AddressFormatter.looksLikeAddress(value)`.

How the Details card draws a row (`RequestReviewView.swift:720-726`):

- `rendersAsAddress` → copyable address;
- `isTokenIdentifier && value.count ≤ 4096` → identifier: plain monospaced text, whole, wrapping;
- `isLong` → collapsible: the first 280 characters plus "…", with "Show All" / "Show Less";
- otherwise plain text.

The label is always drawn through `ReviewText.label`.

#### 3.2.2 `ReviewAsset` (`Models.swift:96-108`)

This is a Codable struct decoded strictly with `JSONDecoder`. Any missing required key or wrong type makes the whole
asset `nil`.

- Required: `chainFamily: String`, `assetAddress: String`, `name: String`, `symbol: String`, `decimals: Int`,
  `icon: String`. `icon` is the 16-hex content hash used by `TokenIconStore`.
- Optional: `chainId: String?`, `tokenProgram: String?`, `rawAmount: String?`, `displayAmount: String?`.

The engine only produces assets from the bundled registry (`engine/src/token-registry.js:12-23`), so `icon` is always
present in practice.

#### 3.2.3 `ReviewNetwork` (`Models.swift:111-122`)

`{family: Chain, chainID: String?, name: String, symbol: String?}`. `brand` is described in §1.3.

#### 3.2.4 `ReviewContract` (`Models.swift:129-176`)

This is what the bundled known-contract library says about the address the request hands assets or authority to. A
hit never means the request is safe.

- `enum Role: String { spender, operator_ = "operator", contract }`. An unknown role becomes `.contract`.
- Fields: `address`, `role`, `known: Bool`, `name?`, `project?`, `category?`, `everyChain = false`, `token?`.
- `init?(raw)`:
  1. `raw` must be a dict, and `address = boundedValue(raw.address)` must be non-nil, otherwise the contract is nil.
  2. `role` from `raw.role`.
  3. `name = boundedValue(raw.name)`.
  4. `known = (raw.known as? Bool ?? false) && name != nil`. "Known" without a name is **not** a hit.
  5. If not known: `name = nil`, `token = boundedValue(raw.token)` (for example "Tether USD (USDT)", meaning the
     registry knows it), then return. `project` and `category` stay nil and `everyChain` stays false.
  6. If known: `project = boundedValue(raw["protocol"])` (**the JSON key is `protocol`**),
     `category = boundedValue(raw.category)`, `everyChain = raw.everyChain as? Bool ?? false`. `everyChain` means a
     CREATE2 deployment with the same address on every EVM chain.
- `subtitle` = the non-empty values of `[project, category]` joined by `" · "`, or nil. For example
  "Uniswap · infra".

UI captions (`RequestReviewView.swift:510-560`):

| role | known | not known |
|---|---|---|
| spender | "Known public contract (spender)" | "Spender is not a known contract" |
| operator | "Known public contract (operator)" | "Operator is not a known contract" |
| contract | "Known public contract" | "Contract is not in the known list" |

A token hit is captioned "Recognized token contract". A known detail is shown as `name — subtitle`.

#### 3.2.5 `ReviewDisplay` (`Models.swift:218-343`)

This is the structured headline the engine builds from the parsed signing bytes.

Constants:

- `maxListLines = 32`
- `maxStringLength = 300`
- `defaultAssetContractLabel = "NFT contract (not verified)"` *(no translation entry)*

`init?(raw)`:

1. `raw` must be a dict, and `action` must be a String, otherwise the display is nil.
2. These keys are read leniently (`as? String`, no bounds): `amount`, `symbol`, `headline`, `counterpartyLabel`,
   `counterparty`, `fee`, `note`, `feeKind`.
   - `feeKind == "energyBudget"` (TRON `fee_limit`) means the fee is a cap on energy, not on the whole fee.
   - An absent `feeKind` means the fee is a ceiling on the whole network fee.
3. `assetKind = ReviewAssetKind(rawValue: raw.assetKind)`. If it is nil (absent or unknown), stop: **no NFT key is
   read**. This fails closed.
4. Otherwise read, size-bounded:
   - `assetContract = boundedValue(raw.assetContract)`
   - `assetContractLabel = boundedValue(raw.assetContractLabel)`
   - `tokenId = boundedValue(raw.tokenId)`
   - `give`, `receive` and `others` via `boundedLines(raw.give)` and so on

Helpers:

- `boundedValue(v)`: returns the value if it is a String that is non-blank after trimming whitespace and newlines and
  has at most 300 characters, otherwise nil. It never truncates.
- `boundedLines(v)`:
  - `v` must be an array whose **every** element is a String, otherwise the result is `[]`.
  - Blank lines are dropped and each line goes through `boundedLine`.
  - If there are more than 32 lines, the result is the first 31 lines plus `"+\(count-31) more (see Details)"`
    *(not localised on iOS)*.
- `boundedLine(s)`: if `s.count > 300`, returns the first 149 characters + "…" + the last 150 characters, keeping
  the amount at the start and the recipient tag at the end.

Computed properties:

- `isNFT = assetKind != nil`
- `assetContractCaption = assetContractLabel ?? "NFT contract (not verified)"`
- `tokenIDIsAmbiguous = assetKind == .nftOrToken`. The UI caption is then "Token ID or raw amount", otherwise
  "Token ID".
- `summaryTokenID`: nil unless `isNFT` and `tokenId` is set. It is nil when `headline` or `amount` already shows the
  ID, using `text(_, showsTokenID:)`. Otherwise it is `tokenId`.
- `showsAssetBox = isNFT && (assetContract != nil || summaryTokenID != nil)`
- `flowSections`: `[]` unless `isNFT`. Otherwise give, receive and others in that order, dropping empty ones.
- `static text(t, showsTokenID: id)`: false when `id` is empty. Otherwise search for `"#"+id`; it counts only when the
  next character is the end of the string or a non-ASCII-digit. So "#12345" does not show "1234".
- `static spokenTokenID(id)`: for an ID longer than 12 characters, `AddressFormatter.groups(id, 4)` joined by ", ";
  otherwise the ID. This is used as the TalkBack text.

`ReviewAssetKind: String` (`Models.swift:181-199`):

| case | meaning | icon (SF Symbol) | a11y label (`RequestReviewView.swift:495-504`) |
|---|---|---|---|
| `nft` | NFT-only method or proven NFT | `photo.artframe` | "NFT" |
| `nftOrToken` | ambiguous selector, or a 0-decimal SPL transfer of 1 | `photo.artframe` | "NFT or token" |
| `multiToken` | ERC-1155 (never presented as an NFT) | `square.stack.3d.up.fill` | "ERC-1155 token" |
| `nftOrder` | Seaport / LooksRare / Blur order | `tag.fill` | "NFT order" |

`ReviewFlowSection` (`Models.swift:202-215`) is `{kind: give | receive | others, lines}`, with `id = kind.rawValue`:

| kind | icon | title (`RequestReviewView.swift:484-492`) | tint |
|---|---|---|---|
| give | `arrow.up.right` | "You give" | warning |
| receive | `arrow.down.left` | "You receive" | accent |
| others | `arrow.triangle.branch` | "Also paid out" | secondary |

### 3.3 `SigningPlan` and `SigningTask` (`Models.swift:652-702`)

`SigningPlan(raw, expectedRequestHash)` fails with **"Invalid signing plan."** unless all of these hold:

- `status == "ready"`;
- `requestHash == expectedRequestHash`, an exact, case-sensitive comparison;
- `planHash` matches `^[0-9a-fA-F]{64}$`;
- `tasks` is an array of dicts with `1 ≤ count ≤ 64`. (The engine itself allows ≤ 100 tasks when signing.)

After that:

- Each task goes through `SigningTask(raw)`, which may throw.
- Duplicate task IDs throw **"Duplicate signing task."**
- The plan keeps `requestHash`, `planHash`, `tasks` and `jsonData` (the **entire resolved object** as sorted-keys
  JSON, including `review`, `version` and `planHash`). `raw` re-parses it, and it is what goes back to
  `signPlanTask` as `plan`.

Engine plan shape (`engine/src/resolver.js:166-215`):

```
{version:1, status:"ready", requestHash, payloadDigest, protocolProfile, originalPayloadBytes,
 signingBytesDigest, chain, review, tasks:[task], planHash}
task = {id, localWalletId, walletKeyId, chain, targets:[{...publicKeyAt, inputIndex?, leafHashes?, signerSlot?}],
        paths, publicKeys, addresses, inputs, signatureAlgorithm, signingBytesDigest}
```

`SigningTask(raw)` fails with **"Invalid signing plan."** unless:

- `id` is a non-empty String;
- `localWalletId` parses as a UUID;
- `walletKeyId` is a String of length 64 (the hex content is not checked);
- `chain` is a valid `Chain`;
- `paths` is a non-empty `[String]`;
- `publicKeys` is a `[String]` with the same count as `paths`.

The parsed fields are:

- `addresses = [String]`, default `[]`;
- `inputs = [Int]`, default `[]`;
- `signerSlot = targets[0].signerSlot as? Int` (Solana's required-signer index);
- `displayAddresses` and `displayPaths`: values de-duplicated in first-seen order, with empty strings dropped.

### 3.4 Pairing payloads (`Models.swift:533-650`)

`PairingPayload` (`Models.swift:571-589`) is the Coin Level export, from `createPublicPairing`.

- `frames` must be a non-empty `[String]`, otherwise **"Unable to encode public wallet information."**
- `text`: from the result, otherwise frames joined by "\n".
- `address`: default `""`. It is the first receive address. For multisig it is still the leaf `/0/0` address; the UI
  hides the address when it is empty.
- `path`: default `""`.
  - It is the account origin for `accountXpub`.
  - It is the leaf path for address mode and Solana.
  - It is the account origin for EVM wallet mode.
  - It is the leaf `m/44'/195'/0'/0/i` for TRON.
- `outputMode`: default `""`.
- `note`: optional. Bitcoin xpub exports carry "Public account key. Receive and change addresses can be derived." or
  "Cosigner account key only. No threshold or complete wallet policy is included." (`protocols.js:583`). It is
  engine English and not shown by `ConnectView`.
- `payloadView = QRPayloadView(raw)`.

`WalletPairingPayload` (`Models.swift:535-569`) is the wallet-level export, `bittool-signer-wallet` v1, from
`createWalletPairing`.

- `frames` must be non-empty and `payload` must be a valid JSON dict, otherwise **"Unable to encode the wallet
  pairing."** The engine guarantees at least 2 frames: `ur:bytes` with a fragment size of `min(400, ceil(len/2))`
  (`protocols.js:677-698`).
- `text`: from the result, otherwise frames joined by "\n".
- `mode`: default `""`; it is always "full".
- `payloadBytes`: Int, default 0. It is the canonical CBOR length.
- `firstAddresses`: from `verify: [[String:Any]]`. Items missing `chain`, `address` or `path` are skipped;
  `profile` defaults to "standard". `Line.id = chain + "/" + profile`.
  - The engine sends 9 lines in this order:
    - `btc` with the profiles native, taproot, nested and legacy, each at `<origin>/0/0`;
    - `evm`/standard at `m/44'/60'/0'/0/0`;
    - `tron`/standard at `m/44'/195'/0'/0/0`;
    - `sol` with the profiles standard, trust and legacy, at their preset paths.
  - The chain codes are `btc`, `evm`, `tron` and `sol`, **not** the `Chain` raw values.
- `json`: `payload` pretty-printed with sorted keys and without escaping slashes. It is the developer view of the
  logical object `{protocol:"bittool-signer-wallet", version:1, mode:"full", network:"mainnet",
  masterFingerprint, accounts:[...]}` (`protocols.js:644-668`).

`QRPayloadView` (`Models.swift:595-650`) is a developer view of exactly what a QR carries. The engine re-decodes it
from the frames it produced (`describeQR`, `protocols.js:482-518`).

- `init?(raw)` returns nil when `payloadFormat` is not `json`, `hex` or `text`. When it is nil, the disclosure is
  hidden.
  - `json`: `payload` must be a **dict**; `text = prettyJSON(payload)` (pretty, sorted keys, unescaped slashes).
  - `hex` / `text`: `payload` must be a non-empty String; `text = payload`.
  - `byteCount = payloadBytes as? Int ?? text.utf8.count`
  - `qrType = raw.type as? String`
  - private `bbqrEncoding`: the character at index 2 of the first frame, if that frame starts with `"B$"` and has
    more than 2 characters. It is `H`, `2` or `Z`.
- `title` *(localised by key)*: "Payload (JSON)", "Payload (Hex)" or "Payload (Text)". Only the JSON one has
  translations.
- `summary` *(not localised on iOS)*. `bytes = "<byteCount formatted with grouping> bytes"`.
  - `"bbqr-psbt"` → `"BBQr · PSBT (hex) · <bytes>"` when the encoding is `H`, otherwise
    `"BBQr · PSBT (Base32) · <bytes>"`.
  - `"psbt-base64"` → `"Plain text · PSBT Base64 · <bytes>"`.
  - `"psbt-hex"` → `"Plain text · PSBT hex · <bytes>"`.
  - nil or `"address"` → `"Plain text · <bytes>"`.
  - any other type → `"ur:<type> · CBOR · <bytes>"`. Example: "ur:crypto-account · CBOR · 162 bytes".
- `isPSBT`: `qrType` is in {`crypto-psbt`, `psbt`, `bbqr-psbt`, `psbt-base64`, `psbt-hex`}.

Ownbit prior art for this type: `core/publicsigner/.../model/PublicPayloadView.kt`.

### 3.5 Errors

- `AppIssue` (`Models.swift:461-464`) is `enum AppIssue: LocalizedError { case message(String) }`. The message is the
  already-localised text. On Android it should be `class AppIssue(message: String) : Exception(message)`, where the
  message is resolved through the string table at throw time.
- `VaultError` (`Core/VaultCrypto.swift:8-45`) and `SigningEngineError` (`SigningEngine.swift:4-8`) surface through
  `error.localizedDescription`. The vault spec covers their texts. The ones AppStore throws directly are
  `.walletNotFound` ("This wallet could not be found.") and `.deviceKeyUnavailable`.

---

## 4. `ReviewText`: draw-time translation of engine English (`BitToolSigner/Models/ReviewText.swift`)

The engine writes labels, actions and headlines in English. That text is covered by the request fingerprint, so it is
never translated inside the engine. The UI translates at draw time and falls back to the engine's English.

- `phrase(english)` (`ReviewText.swift:15-18`): `""` stays `""`. Otherwise the translation, or the English.
- `headline(english)` (`ReviewText.swift:22-34`):
  1. If the whole string has a translation, use it.
  2. Otherwise, for each prefix in `["Method ", "Safe: ", "Batch: ", "New "]` (in order): if the string starts with
     the prefix and the remaining value is non-empty, return `phrase(prefix minus its trailing space) + " " + value`.
     The keys are `"Method"`, `"Safe:"`, `"Batch:"` and `"New"`, all of which exist in the tables. Example:
     "Method 0x3593564c" → "方法 0x3593564c". An empty remainder stops the loop.
  3. Otherwise return the English.
- `label(english)` (`ReviewText.swift:39-46`): the full translation, if any. Otherwise, if the string ends with `")"`
  and contains `"("`: `stem` = the text before the **last** `"("`, trimmed of spaces; `unit` = from that `"("` to the
  end. If `stem` has a translation, return `translated + " " + unit`, so "Amount (USDC)" → "金额 (USDC)". Otherwise
  return the English.
- private `translation(of:)` returns nil when there is no entry. iOS detects a missing entry with the sentinel default
  `"\u{0}none\u{0}"`.
- `shortDecimal(text, places = 8)` (`ReviewText.swift:60-79`) is pure and needs unit tests. It shortens the fee shown
  in the summary; the exact value stays in Details.
  1. Split at the first `" "`: `number` and `unit` (`" " + rest`, or `""`).
  2. Bail out (return `text`) unless `number` contains `"."` and every character **after the first** is a digit or
     `"."`. The first character is not checked.
  3. `whole` = before the first dot; `fraction` = after it. Bail out unless `fraction.count > places` and the
     fraction is all digits.
  4. `kept = fraction.prefix(places)`.
  5. If every dropped digit is `"0"`, return `whole + "." + kept + unit` (truncation is exact).
  6. Otherwise round **away from zero**: add 1 at the last kept place, carrying through `kept`. If the carry leaves
     the fraction, carry through `whole` as a string; if it overflows, prepend `"1"`. Return
     `whole' + "." + kept' + unit`.
  7. If any character met during the carry is not a digit (for example a `"-"` in `whole`), return the original
     text. The carry works on strings so that a huge fee can never overflow an integer.
  - Examples: "0.123456789 ETH" → "0.12345679 ETH"; "0.999999999 BTC" → "1.00000000 BTC";
    "1.000000000 SOL" → "1.00000000 SOL".
  - Swift `isNumber` accepts non-ASCII digits too. The engine output is ASCII, so Android can use `'0'..'9'`.
  - Consumers: the review fee line, "Network fee up to %@" or "Energy budget up to %@ · total fee not known offline"
    (`RequestReviewView.swift:331-339`), and the Activity "Energy budget" essential line.

Android needs a runtime English→translation map for the active language for these paths, because the engine labels
are dynamic. `R.string` IDs cannot serve them (§10.4).

---

## 5. `AppStore`: the app state (`BitToolSigner/Models/AppStore.swift`)

`@MainActor final class AppStore: ObservableObject`. There is one instance per process. It is created in
`BitToolSignerApp.init` with a shared `WalletVault` (`BitToolSignerApp.swift:24-27`).

Design rule (`AppStore.swift:14-15`): **there is no active wallet, no unlocked wallet, no reusable signing
authorisation and no retained secret.** Every secret operation takes a password, uses the secret once and drops it.
Wallet identity and routes are explicit IDs.

### 5.1 Published state (`AppStore.swift:18-36`)

| property | type | initial | meaning |
|---|---|---|---|
| `wallets` | `[VaultMetadata]` | `[]` | vault order. `name` is replaced by the sidecar `displayName` when present. |
| `isLoading` | Bool | **true** | true until the first `reload` finishes |
| `walletLoadSucceeded` | Bool | false | whether the last reload succeeded. RootView stages: loading → failed ("Wallets Unavailable") → welcome (no wallets) → main. |
| `publicWallets` | `[UUID: WalletPublicMetadata]` | `[:]` | only wallets with a valid index |
| `metadataStatuses` | `[UUID: PublicMetadataStatus]` | `[:]` | |
| `duplicateWalletIDs` | `Set<UUID>` | `[]` | wallets whose `walletKeyId` is shared with another wallet. They show a duplicate warning and cannot sign. |
| `review` | `TransactionReview?` | nil | the request currently under review |
| `plan` | `SigningPlan?` | nil | the fixed signing plan for `review` |
| `matchingError` | `String?` | nil | why no plan exists *(English identifiers, see §5.6)* |
| `completedTaskIDs` | `Set<String>` | `[]` | |
| `signatureFrames` | `[String]` | `[]` | the final response QR frames |
| `signatureText` | String | `""` | the final response text (file export) |
| `signaturePayload` | `QRPayloadView?` | nil | |
| `receipts` | `[SigningReceipt]` | `[]` | Activity, newest first |
| `busy` | Bool | false | a signing operation is in flight |
| `securityEpoch` | UUID | random | renewed by `suspend()`. Observed by views to drop dialogs and late results. |
| `navigationResetEpoch` | UUID | random | renewed after a wallet is deleted. RootView then pops the wallet navigation. |
| `error` | `String?` | nil | **publicly settable.** It is a global alert, "Unable to Continue", with an "OK" button that sets it to nil (`RootView.swift:66-68`). |

Derived properties (`AppStore.swift:63-65`):

- `hasSignedResponse = !signatureFrames.isEmpty || !signatureText.isEmpty`
- `pendingTasks = plan?.tasks.filter { !completedTaskIDs.contains($0.id) } ?? []`, in plan order
- `hasIncompleteWallets = wallets.contains { publicWallets[$0.id] == nil }`

Private state (`AppStore.swift:37-62`):

- `vault`, `engine` and `publicRepository` (its directory is `<support>/BitToolSigner/PublicWallets`).
- `receiptURL = <Application Support>/BitToolSigner/receipts.json`.
- The epochs `sensitiveEpoch`, `requestEpoch` and `reloadEpoch`, plus `hasStartedReload`.
- `signingOperation: UUID?`.
- `pendingRequestData: Data?`: the frozen request JSON.
- `previousResultData: Data?`: the frozen last `signPlanTask` result.
- `creatingWallet: Bool`.
- `planWalletSnapshot: WalletCollectionSnapshot?`. `WalletCollectionSnapshot = {walletIDs: Set<UUID>, indexes:
  [UUID: Data]}`, where `indexes` holds each wallet's `jsonData`.
- `connectPrefetch: [UUID: PreparedConnect]`, `prefetchTask` and `prefetchAllowed`.
- `scanFrames: [String]` and `incrementalScan: Bool`.

### 5.2 Epoch and session rules

- `requireSession(epoch, requestID?)` (`AppStore.swift:109-112`) is called after **every** `await`. It throws
  `CancellationError` if the task was cancelled, if `epoch != securityEpoch`, or if `requestID` is given and differs
  from `requestEpoch`. The UI swallows `CancellationError` silently.
- `sensitiveEpoch` guards results that return secrets or recovery material: `generateMnemonic`, `completeSetup` and
  `reveal`. Views call `beginSensitiveSession()` on appear and `endSensitiveSession()` on disappear
  (`WalletSetupFlow.swift:54-55`, `WalletSecuritySheets.swift:75-76`). Both renew `sensitiveEpoch`, so a result that
  arrives after its sheet closed is discarded.
- There is deliberately **no idle timer and no unlock lifetime** (`AppStore.swift:83`).

`suspend()` (`AppStore.swift:86-94`) runs on background, App Lock and screen capture (`BitToolSignerApp.swift:41`,
`RootView.swift:60-61`). It also runs inside `resetPassword` and `deleteWallet`. It:

1. renews `securityEpoch`, `sensitiveEpoch` and `requestEpoch`;
2. sets `signingOperation = nil` and `busy = false`;
3. calls `clearConnectPrefetch()`;
4. calls `engine.releaseWarmContext()` asynchronously;
5. if `review != nil && plan == nil && matchingError == nil`, sets `matchingError = "Matching interrupted. Try
   again."` *(not localised)*.

It keeps the review, the plan and the signature, so a finished signature survives a background trip.

`clearRequest()` (`AppStore.swift:95-100`) renews `requestEpoch`, sets `signingOperation = nil` and `busy = false`,
and nils or empties: `review`, `plan`, `matchingError`, `completedTaskIDs`, `planWalletSnapshot`,
`pendingRequestData`, `previousResultData`, `signatureFrames`, `signatureText` and `signaturePayload`.

`invalidateMatching()` (private, `AppStore.swift:101-108`) calls `suspend()`, then clears `plan`,
`planWalletSnapshot`, `completedTaskIDs`, `previousResultData`, `signatureFrames`, `signatureText` and
`signaturePayload`. Finally it sets `matchingError = "Wallet information changed. Match this request again."`. The
review and `pendingRequestData` are kept, so "Match Again" works. Retry is offered when the message contains
"interrupted" or "changed" (`RequestReviewView.swift:134-136`).

`frozen(raw)` (`AppStore.swift:117-122`):

- The value must be a valid JSON object, otherwise **"Invalid request format."**
- It is serialised as sorted-keys JSON.
- The result must be ≤ 1,048,576 bytes, otherwise **"Request exceeds the supported size."**

`object(data)` parses JSON into a dict, otherwise **"Invalid local document."**

### 5.3 Lifecycle and wallets

**`init(vault, engine, publicRepository?, storageDirectory?)`** (`AppStore.swift:67-80`) schedules
`Task { if !hasStartedReload { await reload() } }`. An explicit early `reload()` therefore wins over the bootstrap
reload.

**`reload()`** (`AppStore.swift:123-148`):

1. Set `hasStartedReload = true` and a fresh `reloadEpoch`.
2. `originals = vault.list()`. A throw lands in step 9.
3. `key` = nil when there are no wallets, otherwise `try? vault.publicDataKey(createIfMissing: false)`.
4. For each wallet, read `snapshot(walletID:key:)`. The displayed name is `snapshot.displayName ?? wallet.name`. Store
   `metadata` (only non-nil values are stored) and `status`.
5. If `reloadEpoch` changed meanwhile, return. Only the latest reload publishes.
6. Publish `wallets`, `publicWallets` and `metadataStatuses`. Call `clearConnectPrefetch()` and
   `scheduleConnectPrefetch()`. Set `walletLoadSucceeded = true`.
7. `duplicateWalletIDs` = every ID whose `walletKeyId` group has more than one member.
8. If `plan != nil && !hasSignedResponse && planWalletSnapshot != current snapshot`, call `invalidateMatching()`.
   Then call `loadReceipts()`.
9. On error, if this is still the current reload: `walletLoadSucceeded = false` and
   `error = error.localizedDescription`.
10. If this is still the current reload: `isLoading = false`.

Simple accessors (`AppStore.swift:149-159`):

- `publicMetadata(for:)`
- `metadataStatus(for:)`: default `.needsCompletion`
- `walletLabel(for:)`: the name, or the English "Wallet"
- `avatarIdentity(for:)`: `walletKeyId`, or `id.uuidString`
- `wallet(_:)`
- `fingerprint(for:)`: `masterFingerprint.uppercased()`, or the English **"Setup required"** (no translation entry)

**`generateMnemonic(wordCount:) async throws -> String`** (`AppStore.swift:160-168`):

1. Capture `securityEpoch` and `sensitiveEpoch`.
2. Run the engine `mnemonic` operation with fresh entropy.
3. Call `requireSession`, and check that `sensitiveEpoch` is unchanged.
4. Return `result.mnemonic ?? result.value`, otherwise **"Unable to generate recovery phrase."**

`SecureRandom.entropyHex` throws `.invalidSecret` unless the word count is in {12, 15, 18, 21, 24}.

**`validateMnemonic(_:) -> BIP39Mnemonic.Canonical`** (`AppStore.swift:171-175`) is local. It calls
`BIP39Mnemonic.validatedCanonical`: exact spellings only, checksum verified (`BIP39Wordlist.swift:282-287`).
`Canonical` is `{phrase (NFC words joined by single spaces), language: BIP39Language}`.

**`createWallet(name:mnemonic:passphrase:password:) -> VaultMetadata`** (`AppStore.swift:177-211`):

1. Re-entrancy guard: if a creation is running, throw **"A wallet is already being saved."**
2. Capture `epoch`.
3. If `passphrase.utf8.count > 1024`, throw **"The BIP39 passphrase exceeds 1,024 UTF-8 bytes."**
4. `mnemonic = validateMnemonic(mnemonic).phrase`. The vault always stores the canonical words.
5. Run `derivePublicIndex` with the seed, call `requireSession`, and build `WalletPublicMetadata`.
6. `await reload()` and `requireSession`. If `!walletLoadSucceeded`, throw **"The existing wallet collection could
   not be read. Retry before adding a wallet."**
7. If any existing index has the same `walletKeyId`, throw **"This recovery phrase and passphrase already belong to
   an existing wallet."**
8. Generate `id = UUID()`. `key = vault.publicDataKey(createIfMissing: true)`; if it is nil, throw
   `VaultError.deviceKeyUnavailable`.
9. **Write the public sidecar first**, with the trimmed name. It is invisible until a vault file exists.
10. `requireSession`, then `vault.create(name:mnemonic:passphrase:password:id:)`. On any error, remove the sidecar
    and rethrow.
11. `await reload()` and return the metadata. Persistence is the success boundary: no epoch check happens after the
    commit.

**`completeSetup(walletID:password:)`** (`AppStore.swift:213-227`), the "Repair" action:

1. The wallet must exist, otherwise `VaultError.walletNotFound`.
2. Capture both epochs.
3. `vault.unlock` → `derivePublicIndex(seed)`, with `requireSession` in between (`authenticatedPublicIndex`,
   `AppStore.swift:229-233`).
4. Check both epochs → `WalletPublicMetadata`.
5. Get the key (create if missing, otherwise `.deviceKeyUnavailable`). Save the sidecar with
   `displayName = walletLabel(for:)`. Reload.

The encrypted wallet, never the old cache, is the identity authority. Existing duplicates are kept and reported, not
deleted.

**`renameWallet(walletID:name:)`** (`AppStore.swift:235-242`):

1. The wallet must exist (`.walletNotFound`).
2. The key must exist without being created, otherwise **"Repair this wallet before renaming it."**
3. `publicRepository.rename`: trimmed; an empty name or one over 40 characters is `VaultError.invalidName`, "Choose a
   wallet name of 1–40 characters."
4. Reload.

Only the sidecar `displayName` changes; the vault header keeps the original name.

**`addresses(walletID:chain:profile = "native":start = 0:count = 20) -> [PublicAccount]`** (`AppStore.swift:243-248`):

- No index → **"Wallet setup required."**
- The engine `derivePublicAddresses` result must contain `addresses`, otherwise **"Unable to derive public
  addresses."**
- Callers (`AddressesView.swift:136-140`):
  - Bitcoin passes the single-sig profile.
  - Solana passes `""`, which returns all 3 preset accounts at once with no paging.
  - EVM and TRON pass `"standard"`.
  - The page size is 20. The limit is 2,147,483,648 for non-Solana chains.

**`pairing(walletID:chain:profile = "native":outputMode = "accountXpub":index = 0) -> PairingPayload`**
(`AppStore.swift:249-253`): no index → **"Wallet setup required."**

**`walletPairing(walletID:) -> WalletPairingPayload`** (`AppStore.swift:305-309`): no index → **"Wallet setup
required."** It always uses `mode: "full"`.

**Connect prefetch** (`AppStore.swift:256-301`):

- `PreparedConnect { payload: WalletPairingPayload, images: [UIImage] }`. `preparedConnect(walletID:)` returns the
  cached entry.
- `setConnectPrefetch(allowed:)` is driven by RootView. It is true only while the scene is active, the App Lock is not
  locked, the screen is not being captured and the main tabs are showing (`RootView.swift:20,59`).
  - false → cancel the prefetch task.
  - true → schedule a prefetch.
- `scheduleConnectPrefetch()`:
  - It requires `prefetchAllowed && walletLoadSucceeded`.
  - It cancels the previous task and starts a utility-priority task that **sleeps 600 ms** and then calls
    `prefetchConnectPairings()`.
- `prefetchConnectPairings()` loops over the wallets that have no cache entry and status `.ready`:
  1. Capture the epoch and that wallet's `jsonData`.
  2. Call `walletPairing`. A failure skips to the next wallet.
  3. Render QR images at utility priority.
  4. If the task was cancelled, the epoch changed, the wallet's index changed, or the image count ≠ frame count,
     **return** (stop the whole loop).
  5. Otherwise store the entry.
- `clearConnectPrefetch()` cancels the task and empties the cache. It is called by `suspend` and by every successful
  reload.

**`reveal(walletID:password:) -> VaultSecret`** (`AppStore.swift:474-480`) calls `vault.unlock` and checks both
epochs. The caller must clear the secret.

**`existingWallet(mnemonic:passphrase:) -> UUID?`** (`AppStore.swift:484-491`) is a public derivation only. It
canonicalises the phrase, runs `derivePublicIndex`, calls `requireSession`, and returns the first wallet whose
`walletKeyId` matches. No vault is read.

**`resetPassword(walletID:mnemonic:passphrase:newPassword:)`** (`AppStore.swift:498-512`):

1. The wallet must have an index, otherwise **"This wallet needs repair before its password can be reset."**
2. Canonicalise the phrase → `derivePublicIndex` → `requireSession`.
3. If the derived `walletKeyId` ≠ the existing one, throw **"This recovery phrase belongs to a different wallet."**
4. `vault.resetPassword(...)`, then `suspend()`, then `reload()`.

**`changePassword(walletID:current:new:)`** (`AppStore.swift:516-519`): a cancellation check, then
`vault.changePassword`. Nothing else changes: keys, ID, public data, the review and navigation all stay valid.

**`deleteWallet(walletID:password:)`** (`AppStore.swift:520-531`):

1. Cancellation check.
2. `vault.delete(id:password:)`. The vault verifies the password first. On error: `reload()` and rethrow, so the sheet
   shows the error and nothing else changes.
3. `suspend()`.
4. Remove the sidecar. If that fails, set `error = "The encrypted wallet was deleted, but its public cache could not
   be removed."` *(not localised)*.
5. `clearRequest()`, a new `navigationResetEpoch`, `reload()`.

When the wallet count reaches 0, RootView switches to the Wallets tab and resets navigation (`RootView.swift:53-57`).

### 5.4 The request pipeline (scan → review → match → sign)

State machine. Any step may end in `matchingError` while keeping `review` as a read-only view:

```
idle ──scan/import──▶ decoding ──complete──▶ reviewed(review, pendingRequestData)
reviewed ──canSign=false──▶ review + matchingError "Unsupported signing protocol"
reviewed ──resolveSigningPlan──▶ planned(plan, planWalletSnapshot) | review + matchingError
planned ──sign(task 1..n-1)──▶ planned(completedTaskIDs+, previousResultData)      [receipt "Partial signature created"]
planned ──sign(last task)──▶ signed(signatureFrames/Text/Payload)                   [receipt "Signature created"]
any ──suspend──▶ (unchanged) + "Matching interrupted. Try again." if reviewed without plan/error
planned (unsigned) ──wallet collection changed──▶ review + "Wallet information changed. Match this request again."
any ──clearRequest──▶ idle
```

**`beginScan()`** (`AppStore.swift:316-321`) empties `scanFrames`. If the engine supports incremental scanning, it
calls `beginScanSession()` (a fresh context) and sets `incrementalScan = true`; otherwise it sets it to false.
**`endScan()`** (`AppStore.swift:323-327`) empties the frames, ends the session if incremental, and sets
`incrementalScan = false`.

**`receive(frame:) -> Double`** (`AppStore.swift:330-341`):

- Fallback path (not incremental): append the frame if it is new, then call `receive(frames: scanFrames)`.
- Incremental path:
  1. `clearRequest()`. **This runs on every frame.**
  2. Capture the epochs.
  3. `scan(frame:)`, then `requireSession`.
  4. If `complete == true` and `request` is an object, `await prepare(request)`.
  5. Return `progress as? Double`, or `review == nil ? 0 : 1`.

**`receive(frames:) -> Double`** (`AppStore.swift:343-351`):

1. `clearRequest()`.
2. Check `frames.count ≤ 2048` and total UTF-8 ≤ 1,048,576, otherwise **"QR transfer is too large."**
3. Engine `decodeQR`, then the same tail as the incremental path.

**`importPayload(text)`** (`AppStore.swift:352-360`), for paste or a file:

1. `clearRequest()`.
2. Check `text.utf8.count ≤ 262,144`, otherwise **"Text payload exceeds 256 KB."**
3. Engine `decodePayload`.
4. If not `complete == true` with a `request` object, throw **"The request is incomplete. Import all QR frames."**
5. `prepare(request)`.

**`prepare(request)`** (`AppStore.swift:361-403`):

1. `clearRequest()`. Capture `epoch` and `preparing = requestEpoch`.
2. `data = frozen(request)` and `immutable = object(data)`. The engine only ever sees the frozen copy.
3. Engine `review {request: immutable}`, then `requireSession(epoch, preparing)`.
4. `reviewed = TransactionReview(result)`. Validate all of the following, otherwise throw **"Missing or invalid
   transaction commitment."**:
   - `requestHash` matches `^[0-9a-fA-F]{64}$`;
   - `result.chain == immutable.chain`, as strings;
   - the chain is valid;
   - `fields` is non-empty.
5. Set `pendingRequestData = data` and `review = reviewed`. This moves RootView to the Scan tab
   (`RootView.swift:62`).
6. If `result.canSign != true`: `matchingError = "Unsupported signing protocol"` and return.
7. `collection = walletCollectionSnapshot`. The candidates follow vault order: each is `{localWalletId: uuidString,
   publicIndex: raw}` when an index exists, otherwise `{localWalletId, publicMetadataStatus: <status raw>}`.
8. Engine `resolveSigningPlan`, then `requireSession`.
9. If the collection changed: `matchingError = "Wallet information changed. Match this request again."` and return.
10. If `status != "ready"`: `matchingError = resolved.message ?? "No matching wallet"` and return.
11. `fixed = SigningPlan(resolved, expectedRequestHash: reviewed.requestHash)`. Every task's `walletKeyId` must equal
    `publicWallets[task.walletID].walletKeyId`, otherwise throw **"Invalid signing wallet identity."**
12. Set `plan = fixed`, `planWalletSnapshot = collection` and `matchingError = nil`.
13. If `resolved.review` is a dict and its `TransactionReview` has the same `requestHash`, the same chain and non-empty
    fields, **replace** `review` with it. The refined review contains facts proven with the matched public keys, such
    as verified Bitcoin change ownership and the recomputed summary and fee warnings.
14. Errors in steps 8–13 that are not cancellations: call `requireSession` (so a cancellation still propagates) and
    set `matchingError = error.localizedDescription`. Errors in steps 2–4 **throw** to the caller.

**`retryMatching()`** (`AppStore.swift:405-410`) requires `plan == nil && !hasSignedResponse && review != nil &&
pendingRequestData != nil`, otherwise **"No interrupted or unmatched request to retry."** It then calls
`prepare(object(pendingRequestData))`.

**`sign(taskID:password:riskAcknowledged = false)`** (`AppStore.swift:412-458`):

1. If `busy || hasSignedResponse`, throw **"This signing request is already being processed."**
2. If a plan exists and either the wallet collection snapshot changed or any task's wallet is in
   `duplicateWalletIDs`: call `invalidateMatching()` and throw **"Wallet information changed. Match this request
   again."**
3. Throw **"No matching signing task. Review the request again."** unless all of these hold:
   - `matchingError == nil`;
   - the plan exists;
   - `pendingTasks.first?.id == taskID`, so tasks are signed **strictly in plan order**;
   - `pendingRequestData` exists;
   - `review.requestHash == plan.requestHash`;
   - the task wallet's `walletKeyId` still matches.
4. Set a new `signingOperation`, `busy = true`, and a deferred reset (only if the operation is still current).
   Call `requireSession`.
5. `authenticatedSignature` (`AppStore.swift:463-472`):
   1. `vault.unlock(task.walletID, password)`, then `requireSession`.
   2. Parameters: `{request, plan: plan.raw, taskId, seed: BIP39Seed.hex(...), riskAcknowledged}`, plus
      `previousResult` when there is one.
   3. Engine `signPlanTask`.
6. `requireSession`. If `result.requestHash != plan.requestHash`, `result.planHash != plan.planHash` or
   `result.taskId != taskID`, throw **"Request changed. Review the transaction again."**
7. `resultData = frozen(result)`. `finalTask = pendingTasks.count == 1`.
8. If this is the final task:
   1. Engine `encodeResponse {request, result}`, then `requireSession`.
   2. `frames = encoded.frames ?? []` and `text = encoded.text ?? frames.joined("\n")`.
   3. If both are empty, throw **"Unable to encode signature response."**
   4. Publish `signatureFrames`, `signatureText` and `signaturePayload = QRPayloadView(encoded)`.
9. Set `previousResultData = resultData` and add `taskID` to `completedTaskIDs`.
10. Append a receipt (§6):
    - `id`: new UUID; `date`: now;
    - `chain = task.chain`; `title = approved.title`;
    - `requestHash = plan.requestHash`;
    - `walletID`, `walletKeyID` and `masterFingerprint` (lowercase) from the task's wallet;
    - `resultType`: "Signature created" for the final task, otherwise "Partial signature created";
    - `summary = String(approved.summary.prefix(160))`;
    - `networkName = approved.networkName`; `chainID = approved.network?.chainID`;
      `riskLevel = approved.riskLevel.rawValue`;
    - `requestJSON = stored(pendingRequestData as UTF-8)`;
    - `responseFrames` and `responseText`: stored for the final task only, otherwise nil.

`previousResult` exists so that several wallets can each add their signature to one PSBT. Only Bitcoin can have more
than one task.

### 5.5 Activity receipts in the store

These methods implement the rules in §6:

- `loadReceipts()` (`AppStore.swift:536-540`)
- `readReceipts()` (`AppStore.swift:541-553`)
- `appendReceipt()` (`AppStore.swift:554-569`)
- `clearHistory()` (`AppStore.swift:532-535`)
- `saveReceipts()` (`AppStore.swift:592-600`)

**`review(storedRequest json:, expecting requestHash:) -> TransactionReview`** (`AppStore.swift:581-591`) re-reviews
a stored request offline:

- The JSON must be at most 32,768 UTF-8 bytes and parse, otherwise **"The stored request could not be read."**
- It runs the engine `review` operation.
- The result's `requestHash` must equal the stored hash **case-insensitively**, and `fields` must be non-empty.
  Otherwise **"The stored request does not match this signature's fingerprint."**
- It never touches the pending request, matches no wallet, needs no password and does no epoch checks.

### 5.6 `matchingError` vocabulary

`matchingError` holds English identifiers that the UI switches on. iOS shows them verbatim via `Label(String)`.

| value | set by |
|---|---|
| "Unsupported signing protocol" | `prepare` when `canSign` is false |
| "Matching interrupted. Try again." | `suspend` while matching |
| "Wallet information changed. Match this request again." | `invalidateMatching`, `prepare` |
| "No matching wallet" / "Missing signer information" / "Ambiguous wallet match" / "Wallet setup required" / an engine message | the `resolveSigningPlan` failure message |
| a localised error description | `prepare` catch: plan validation, or engine/vault errors |

Android should model this as a `sealed interface MatchingIssue`, keeping `raw: String` for the engine messages, and
map it to resources in the UI.

---

## 6. `SigningReceipt`: the Activity record and its retention (`Models.swift:404-459`, `AppStore.swift:532-601`)

`struct SigningReceipt: Codable, Identifiable, Hashable` is a local record of a signature made on this device. It
never stores secrets and never claims broadcast or confirmation.

| field (JSON key) | type | notes |
|---|---|---|
| `id` | UUID | |
| `date` | Date | signing time. Swift's default encoding is a Double, seconds since 2001-01-01. **Android need not read iOS files**, so it can use epoch milliseconds. |
| `chain` | Chain | raw string |
| `title` | String | the review title (engine English) |
| `requestHash` | String | 64 hex characters |
| `walletID` | UUID? | |
| `walletKeyID` | String? | |
| `masterFingerprint` | String? | lowercase |
| `resultType` | String? | "Signature created" \| "Partial signature created". Shown verbatim (`ActivityTab.swift:129-130`); when it is nil, the UI shows the localised "Signature created". |
| `summary` | String? | ≤ 160 characters at write time |
| `networkName` | String? | |
| `chainID` | String? | the EVM chain ID, used for the logo (`ActivityTab.swift:93-95`) |
| `riskLevel` | String? | raw `RiskLevel`. Activity shows "Risk": "High" for `high`, otherwise "Warnings shown" for any value ≠ `normal`. |
| `requestJSON` | String? | the canonical frozen request JSON |
| `responseFrames` | [String]? | the signature QR frames, for "Show QR Code" |
| `responseText` | String? | the signature text, for "Export as File" (`bittool-signer-signature`, plain text) |

All optionals are omitted from the JSON when nil.

Constants and helpers:

- `payloadLimit = 32_768` UTF-8 bytes for one payload.
- `payloadRetention = 25`: the most recent signatures keep their payloads.
- `hasPayloads = requestJSON != nil || responseText != nil || responseFrames?.isEmpty == false`
- `withoutPayloads`: a copy with `requestJSON`, `responseFrames` and `responseText` set to nil.
- `stored(_ text: String?) -> String?`: the text, if non-nil, non-empty and ≤ 32,768 UTF-8 bytes; otherwise nil. An
  oversized payload is **not stored**; the record still exists with its summary.
- `stored(_ frames: [String]?) -> [String]?`: the frames, if non-nil, non-empty, at most 2,048 items and with a total
  UTF-8 size ≤ 32,768; otherwise nil.

Store-level rules:

- **File:** `<Application Support>/BitToolSigner/receipts.json`, a JSON array, newest first.
  - The directory has mode 0700, complete file protection and is excluded from backup.
  - The file is written atomically with complete protection, excluded from backup, mode 0600.
- **`receiptFileLimit = 2_097_152` bytes** (`AppStore.swift:574`). That is room for 100 records, 25 of them with
  payloads of up to 32 KiB, plus metadata.
- **Read validation** (`readReceipts`). Any failure throws "Invalid activity record.":
  - a missing file means `[]`;
  - the file must be a regular file of at most 2 MiB;
  - it must decode;
  - `count ≤ 100`;
  - every record has `title.count ≤ 200`, `(summary?.count ?? 0) ≤ 200` and `requestHash ~ ^[0-9a-fA-F]{64}$`;
  - every payload satisfies `stored(x) == x`: when present it must be non-empty and within the limits.
- **`loadReceipts()`** runs after every successful reload. It sets `receipts = []`, then reads. A failure sets
  `error = "The local activity record could not be read."` *(not localised)* and leaves the list empty.
- **`appendReceipt(r)`**:
  1. `updated = ([r] + readReceipts()).prefix(100)`. The newest record is first, and records beyond 100 are dropped.
  2. Indexes ≥ 25 lose their payloads.
  3. While the JSON encoding of `updated` is over 2,097,152 bytes and some record still has payloads, strip the
     payloads of the **last** (oldest) record that has any.
  4. Save, then publish.
  5. Any failure, including a corrupt existing file, sets `error = "The local activity record could not be saved.
     Any completed signature remains available."` *(not localised)*. So a corrupt file blocks new records until
     "Clear Activity" is used.
- **`clearHistory()`** saves `[]` and publishes `[]`. On failure it reports the same save error.
- **Activity UI facts** that depend on the model (`Features/Activity/ActivityTab.swift`):
  - Sections are grouped by start of day, days descending, records descending by date within a day.
  - The row subtitle is `summary` and the wallet label joined by " · ".
  - The detail footer for records without payloads reads "The request and signature are kept for the %lld most
    recent signatures. This one is older, so only its summary remains." with `%lld` = 25.
  - "Decoding again…" is shown while `review(storedRequest:)` runs.
  - A wallet that no longer exists is labelled "Deleted wallet".
  - The essentials list (`ActivityTab.swift:216-238`):
    - "Action" = `display.action ?? title`;
    - "Amount" = `amount` and `symbol` joined by a space;
    - the counterparty, labelled with the `counterpartyLabel ?? "To"` key, with the value
      `AddressFormatter.short(_, 6, 6)`;
    - "Network fee", or "Energy budget" with `shortDecimal` applied when `feeKind == "energyBudget"`.
- One edge case: `title` is not truncated at write time, but the read check rejects titles over 200 characters. Engine
  titles are short, but Android should clamp the title to 200 graphemes when writing so the history cannot become
  unreadable.

---

## 7. Recovery cards: 2-of-3 split (`BitToolSigner/Models/RecoveryCards.swift`)

The phrase is split across three cards so that any two cards restore it. The layout depends **only on the word
count**, so it is deterministic, not secret, and the same on every device and every time the cards are opened.

- `count = 3`
- `cardOmitting(p) = (p / 3 + p % 3) % 3`, with a zero-based position `p` and integer division. This is the card
  (0, 1 or 2) that leaves out word `p`. Each row of three positions has each card omit exactly one word, and the
  omitted slot shifts by one every row.
- `shows(p, on: card) = cardOmitting(p) != card`
- `omittedPositions(on: card, wordCount:)` = the ascending positions `p < wordCount` with `cardOmitting(p) == card`.
  Each card omits exactly `wordCount/3` words, and each missing word appears on the other two cards.
- `remainingEntropyBits(wordCount) = (wordCount/3) * 11 - wordCount/3`. That is 11 bits per missing word, minus one
  checksum bit per three words.
- `singleCardIsWeak(wordCount) = remainingEntropyBits < 60`, which is true for **12 words (40 bits) and 15 words
  (50 bits)**.

Omitted positions, shown 1-based in "Leaves out words %@" (joined with ", "):

| words | card 1 | card 2 | card 3 | bits left with one card |
|---|---|---|---|---|
| 12 | 1, 6, 8, 10 | 2, 4, 9, 11 | 3, 5, 7, 12 | 40 (weak) |
| 15 | 1, 6, 8, 10, 15 | 2, 4, 9, 11, 13 | 3, 5, 7, 12, 14 | 50 (weak) |
| 18 | 1, 6, 8, 10, 15, 17 | 2, 4, 9, 11, 13, 18 | 3, 5, 7, 12, 14, 16 | 60 |
| 21 | 1, 6, 8, 10, 15, 17, 19 | 2, 4, 9, 11, 13, 18, 20 | 3, 5, 7, 12, 14, 16, 21 | 70 |
| 24 | 1, 6, 8, 10, 15, 17, 19, 24 | 2, 4, 9, 11, 13, 18, 20, 22 | 3, 5, 7, 12, 14, 16, 21, 23 | 80 |

UI that depends on it (`Features/Backup/RecoveryBackupViews.swift:175-340`):

- Sheet title: "Recovery Cards".
- Intro text, by strength:
  - weak: "Keep these three cards in three different places. Any two of them restore the complete recovery phrase,
    so if one card is lost, the other two can still restore your wallet."
  - strong: "Keep these three cards in three different places. Any two of them restore the complete recovery phrase,
    so if one card is lost or stolen, the other two keep your wallet safe and recoverable."
- Weak warning callout, warning tone, *(not localised on iOS, no key)*: `"With a \(n)-word phrase each card shows
  \(n - missing) of the \(n) words, and the other \(missing) could be worked out with enough computing power. Guard
  every card as carefully as the full phrase, or use a 24-word phrase for split backups."`, where
  `missing = n / 3`.
- If the wallet has a passphrase: "The cards contain the words only. Back up your BIP39 passphrase separately."
- Each card:
  - "Card %lld of %lld" and "Leaves out words %@";
  - every position is drawn, either as `"N."` plus the word, or as `"N."` plus `xxxx` at tertiary colour on a 45%
    raised background;
  - accessibility: "Word %lld, %@" or "Word %lld, not on this card".
- The cards are concealed until tapped: "Tap to Reveal", "Make sure no one can see your screen."
  - accessibility label "Show recovery cards", hint "Make sure no one can see your screen";
  - after reveal, "Hide Cards".
- The security tip "Recovery cards: any two restore" has its own text (`RecoveryBackupViews.swift:379-380`).

---

## 8. Backup verification: fill-in check (`BitToolSigner/Models/BackupVerification.swift`)

The check is held in memory only, inside the setup draft.

- `Chip { id: Int, word: String }`. The id is the **display order** and says nothing about where the word belongs.
- `GenerationError.invalidPhraseLength`, "A backup check requires a supported BIP39 phrase length."
- State:
  - `wordCount`;
  - `positions`: the blank positions, zero-based and ascending;
  - `chips`: the blank words, shuffled;
  - private `placements: [position: chipID]`;
  - private `selectedPosition: Int?`;
  - private `correctWords: [position: word]`.
- `blankCount(for n) = n >= 18 ? 5 : 4`: four blanks for 12 and 15 words, five for 18, 21 and 24.
- `init(words, using rng)` (`BackupVerification.swift:40-53`):
  1. The word count must be in `BIP39.supportedLengths = {12, 15, 18, 21, 24}`, otherwise the error above.
  2. `positions = shuffle(0..<n).prefix(blankCount).sorted()`.
  3. `answer = words at positions`, in ascending position order.
  4. `order = indices(answer)`. If the answer has more than one **distinct** word, reshuffle `order` until
     `order.map(answer) != answer`, so the chips never appear in answer order. If every blank holds the same word,
     no reshuffle happens.
  5. `chips[i] = Chip(id: i, word: answer[order[i]])`.
  6. The default RNG is `SystemRandomNumberGenerator`, which is cryptographic. Android should use `SecureRandom` and
     inject a seeded `Random` in tests.
- `activePosition`: `selectedPosition` if it is still empty, otherwise the first empty position in ascending order,
  otherwise nil.
- `isComplete = placements.count == positions.count`
- `isCorrect = isComplete && every position's placed **word** == the correct word`. The comparison is by word, not
  chip ID, so duplicate words in either order are correct.
- `isBlank(p) = correctWords[p] != nil`; `word(at: p)` = the word of the placed chip; `isPlaced(chip)`.
- `place(chipID) -> Bool`: fails (returns false) when there is no active position, the chip ID is unknown, or the chip
  is already placed. Otherwise it places the chip at `activePosition` and clears the selection.
- `select(position)`: only for a blank, empty position; it sets `selectedPosition`.
- `clear(position)`: only for a blank; it removes the placement and **selects** the position.
- `resetPlacements()`: removes every placement and the selection.

Setup-flow use (`Features/Setup/WalletSetupModel.swift:108-146`, `WalletSetupFlow.swift:313-343`):

- `confirmBackup()` builds a new check each time the person confirms they wrote the phrase down. The order of blanks
  and chips is random every time.
- "Confirm" is enabled only when `isComplete`.
- `finishVerification()`:
  - if `!isCorrect`: set the error "Some words don't match. Check your written backup and try again." and call
    `resetPlacements()`;
  - otherwise go to the password step.
- "Show the Phrase Again" clears the check and returns to the backup step.
- Backgrounding (`suspend`) and the screenshot warning both reset placements.
- Step header: "Check Your Backup" (step 3 of 4). Message: "Using your written copy, tap the missing words in order.
  Tap a blank to choose it, or a filled blank to change it."
- Grid accessibility: "Word %lld, hidden" for masked cells; for blank cells the label "Word %lld", the value = the
  word, else "Empty, next to fill" / "Empty", and the hint "Fills this blank next" / "Removes this word".
- Chip accessibility: the value is "Placed" or "Available". A chip is disabled when it is placed or there is no active
  position. A selection haptic plays on placement.

---

## 9. App languages (`BitToolSigner/Models/AppLanguage.swift`)

`enum AppLanguage: String, CaseIterable` has `system` plus 21 languages, in this picker order. The names are
endonyms and are never translated. `system` is shown as the localised "System".

| # | case | code (raw = lproj = translations file) | endonym | RTL |
|---|---|---|---|---|
| 0 | system | `""`/`"system"` (follows the device) | "System" (localised) | device |
| 1 | english | `en` | English | |
| 2 | simplifiedChinese | `zh-Hans` | 简体中文 | |
| 3 | traditionalChinese | `zh-Hant` | 繁體中文 | |
| 4 | japanese | `ja` | 日本語 | |
| 5 | korean | `ko` | 한국어 | |
| 6 | russian | `ru` | Русский | |
| 7 | ukrainian | `uk` | Українська | |
| 8 | spanish | `es` | Español (España) | |
| 9 | latinAmericanSpanish | `es-419` | Español (Latinoamérica) | |
| 10 | german | `de` | Deutsch | |
| 11 | french | `fr` | Français | |
| 12 | italian | `it` | Italiano | |
| 13 | brazilianPortuguese | `pt-BR` | Português (Brasil) | |
| 14 | europeanPortuguese | `pt-PT` | Português (Portugal) | |
| 15 | polish | `pl` | Polski | |
| 16 | indonesian | `id` | Bahasa Indonesia | |
| 17 | turkish | `tr` | Türkçe | |
| 18 | thai | `th` | ภาษาไทย | |
| 19 | vietnamese | `vi` | Tiếng Việt | |
| 20 | filipino | `fil` | Filipino | |
| 21 | arabic | `ar` | العربية | **yes** |

- The stored preference key is `"language"` in UserDefaults (`@AppStorage("language")`). Its default is
  `AppLanguage.system.rawValue`, which is `"system"`. `current(stored)` maps an unknown value to `.system`
  (`AppLanguage.swift:90`).
- `selectable` (`AppLanguage.swift:65-67`) contains `system` plus each case whose code is a bundled localisation. All
  21 are bundled.
- `locale` is nil for `system`, otherwise `Locale(identifier: code)`.
- `layoutDirection`: nil for `system` (use the device language's direction), right-to-left for `arabic`, otherwise
  left-to-right. Arabic mirrors everything, including back buttons and row leading edges.
- `LanguageEnvironment` (`AppLanguage.swift:95-111`):
  - It applies the locale and direction without restarting.
  - It forces a full view-tree rebuild with `.id(language.rawValue)`, which **returns to the first tab**. That is
    the expected behaviour when switching language.
- `AppText.bundle` (`AppLanguage.swift:121-127`) is the chosen language's bundle, or the main bundle for `system`.
  Model-layer strings (`String(localized:bundle:)`) use it, because they have no SwiftUI environment.
- Settings: Appearance section, "Language" picker (SF Symbol `globe`). Footer: "The interface language. What a signing
  request itself says is decided by the request and stays as it is." (`SettingsTab.swift:53-65`).
- Translation data:
  - `translations/<code>.json` = `{language, name (English name), infoPlist: {NSCameraUsageDescription,
    NSFaceIDUsageDescription}, strings: {<English key>: <translation>}}`.
  - There are 773 strings per language. English has no file, because the key is the text.
  - Values use `%@`, `%lld` and positional `%1$@` / `%2$lld`, and `%%` for a literal percent sign.

---

## 10. Compatible wallets (`BitToolSigner/Models/CompatibleWallets.swift`)

This is a list of wallets known to work with BitTool Signer exports. The data comes only from primary sources
(September 2026); the sites were checked in October 2026. Listing a wallet is **not** a certification.

`CompatibleWallet { name, asset (logo image name), platforms, support: .signing | .watchOnly }`:

- `id = name`;
- `site = CompatibleWallets.sites[name] ?? ""`. The site is shown as copyable text. **The app never opens a
  browser.**

Support means:

- `signing`: the wallet imports the export by QR, sends unsigned requests by QR, and takes the signature back. Its UI
  badge is "Watch and sign by QR" (SF Symbol `qrcode.viewfinder`, success colour). Multisig coordinators are titled
  "Multisig coordinators · sign by QR".
- `watchOnly`: the wallet can only watch the account or address. Its badge is "Watch only" (SF Symbol `eye`,
  secondary colour).

Logos are `Assets.xcassets/<asset>.imageset/icon.png`, 180×180 PNG. They are drawn with a corner radius of
`size*0.225` and a `primary @ 8%` 0.5 pt stroke. A missing asset falls back to the SF Symbol `app.fill` in quaternary
colour.

Platform strings are **not translated** (no keys): "Desktop", "iOS · Android", "Desktop · Android",
"Extension · iOS · Android", "Extension", "iOS · Android · Extension", "iOS".

### 10.1 Lists (`CompatibleWallets.swift:67-133`)

| list | wallets (name / asset / platforms / support) |
|---|---|
| `bitcoinAccount` (crypto-account xpub, then crypto-psbt) | Sparrow / wallet_sparrow / Desktop / S · BlueWallet / wallet_bluewallet / iOS · Android / S · Nunchuk / wallet_nunchuk / iOS · Android / S · Bitcoin Keeper / wallet_keeper / iOS · Android / S · BULL Bitcoin / wallet_bull / iOS · Android / S · Specter / wallet_specter / Desktop / S · Bitcoin Safe / wallet_bitcoinsafe / Desktop / S |
| `zeus` (reads single-sig via LND; no legacy) | ZEUS / wallet_zeus / iOS · Android / S |
| `bitcoinCosigner` | Sparrow / Desktop / S · BlueWallet / iOS · Android / S · Nunchuk / iOS · Android / S · Bitcoin Keeper / iOS · Android / S · Specter / Desktop / S · Bitcoin Safe / Desktop / S |
| `bitcoinAddress` | BlueWallet / iOS · Android / W · Electrum / wallet_electrum / Desktop · Android / W |
| `evmAccount` (crypto-hdkey EIP-4527, eth-sign-request) | MetaMask / wallet_metamask / Extension · iOS · Android / S · Rabby / wallet_rabby / Extension · iOS · Android / S · imToken / wallet_imtoken / iOS · Android / S · TokenPocket / wallet_tokenpocket / iOS · Android / S · Ambire / wallet_ambire / Extension / S |
| `evmAddress` | Rabby / Extension · iOS · Android / W · TokenPocket / iOS · Android / W · OKX Wallet / wallet_okx / iOS · Android · Extension / W · Trust Wallet / wallet_trust / iOS · Android / W |
| `solanaAccount` (crypto-multi-accounts, sol-sign-request) | Solflare / wallet_solflare / Extension · iOS · Android / S · Helium Wallet / wallet_helium / iOS · Android / S |
| `solanaAddress` | Phantom / wallet_phantom / Extension · iOS · Android / W · Backpack / wallet_backpack / Extension / W · Jupiter / wallet_jupiter / Extension · iOS · Android / W · OKX Wallet / iOS · Android · Extension / W · TokenPocket / iOS · Android / W |
| `tronAddress` (address only; nobody sends tron-sign-request) | TokenPocket / iOS · Android · Extension / W · TronLink / wallet_tronlink / iOS · Android / W · OKX Wallet / iOS · Android / W · OneKey / wallet_onekey / iOS · Android / W |
| `walletLevel` (bittool-signer-wallet v1) | BitTool Wallet / wallet_bittool / iOS / S |

S = signing, W = watchOnly. The same wallet appears with different platform strings in different lists. Keep each
list's strings as written.

Sites (`CompatibleWallets.swift:33-58`):

| wallet | site |
|---|---|
| Sparrow | sparrowwallet.com |
| BlueWallet | bluewallet.io |
| Nunchuk | nunchuk.io |
| Bitcoin Keeper | bitcoinkeeper.app |
| BULL Bitcoin | bullbitcoin.com |
| Specter | specter.solutions |
| Bitcoin Safe | bitcoin-safe.org |
| ZEUS | zeusln.com |
| Electrum | electrum.org |
| MetaMask | metamask.io |
| Rabby | rabby.io |
| imToken | token.im |
| TokenPocket | tokenpocket.pro |
| Ambire | ambire.com |
| OKX Wallet | web3.okx.com |
| Trust Wallet | trustwallet.com |
| Solflare | solflare.com |
| Helium Wallet | wallet.helium.com |
| Phantom | phantom.com |
| Backpack | backpack.exchange |
| Jupiter | jup.ag |
| TronLink | tronlink.org |
| OneKey | onekey.so |
| BitTool Wallet | bittool.com/wallet |

### 10.2 Selection functions

- `wallets(chain, profile, mode)` (`CompatibleWallets.swift:136-149`) lists the wallets for one Coin Level export:
  - bitcoin:
    - a profile starting with `multisig-` → `bitcoinCosigner`;
    - mode `"address"` → `bitcoinAddress`;
    - profile `"legacy"` → `bitcoinAccount`;
    - otherwise `bitcoinAccount + [zeus]`.
  - evm: `"address"` → `evmAddress`, otherwise `evmAccount`.
  - solana: `"address"` → `solanaAddress`, otherwise `solanaAccount`.
  - tron: `tronAddress`.
- `directory` (`CompatibleWallets.swift:153-162`) merges every list into one alphabetical directory:
  - It concatenates walletLevel, bitcoinAccount, [zeus], bitcoinCosigner, bitcoinAddress, evmAccount, evmAddress,
    solanaAccount, solanaAddress and tronAddress.
  - Per name it keeps the first `signing` record. If the name never signs, it keeps the **last** watch-only record.
  - It sorts with `localizedCaseInsensitiveCompare`.
  - The result has **24 wallets**: Ambire (S), Backpack (W), Bitcoin Keeper (S), Bitcoin Safe (S), BitTool Wallet
    (S, "iOS"), BlueWallet (S), BULL Bitcoin (S), Electrum (W), Helium Wallet (S), imToken (S), Jupiter (W),
    MetaMask (S), Nunchuk (S), OKX Wallet (W, "iOS · Android", from the tron list), OneKey (W), Phantom (W),
    Rabby (S), Solflare (S), Sparrow (S), Specter (S), TokenPocket (S, "iOS · Android"), TronLink (W),
    Trust Wallet (W), ZEUS (S).
- `all(for chain)` (`CompatibleWallets.swift:165-175`) lists every wallet for a chain, de-duplicated by name with the
  first occurrence kept:
  - bitcoin: [bitcoinAccount, zeus, bitcoinCosigner, bitcoinAddress]. Signers: 7 + ZEUS. Watchers: Electrum.
  - evm: [evmAccount, evmAddress]. Signers: 5. Watchers: OKX Wallet, Trust Wallet.
  - solana: [solanaAccount, solanaAddress]. Signers: Solflare, Helium Wallet. Watchers: Phantom, Backpack, Jupiter,
    OKX Wallet, TokenPocket.
  - tron: tronAddress (4 watchers).

### 10.3 Consumers and strings

- **Connect › Coin Level** (`ConnectView.swift:602-638`):
  - Title "Works with".
  - Signing group titled "Watch and sign by QR" (or the multisig title).
  - Watch group "Watch only", followed by a note:
    - TRON: "These wallets can show this address’s balance. No mainstream wallet currently sends TRON transactions
      to a QR signer in BitTool Signer’s format."
    - other chains: "These wallets can show this address’s balance but cannot send its transactions to BitTool
      Signer for signing."
  - Then: "Based on each wallet’s documentation or source code, or its official QR hardware-wallet guide (September
    2026). Versions change: always compare the first address in both apps before receiving funds."
  - Then "Submit a Wallet".
  - The grid is adaptive: minimum column width 140 pt, or 260 pt at xxLarge Dynamic Type and above. Icons are 30 pt.
- **Connect › Wallet Level**: the "One QR for every chain" group. Settings › Compatibility › Formats shows `all(for:)`
  per chain plus the wallet-level group.
- **Settings › Compatibility › Wallets** (`Features/Settings/WalletDirectoryView.swift`):
  - Two callouts:
    - warning tone: "Every wallet here supports, or says it supports, BitTool Signer's QR codes. Anyone can submit a
      wallet and claim that. We have not fully verified these claims and we do not audit, endorse or vouch for any of
      these wallets."
    - info tone: "This page is information only. Check any wallet yourself before trusting it with money. BitTool is
      not responsible for anything that happens through another wallet — bugs, hacks, scams or losses of any kind."
  - The section header is "%lld wallets".
  - Each row: icon 34, name, platforms, support badge, and the site with a copy button ("Copy address").
  - Footer: "The app never opens a browser. Copy the address and open it on another device."
  - Then "Submit a Wallet".
- **Product decision for Android:** "BitTool Wallet" is listed with platforms "iOS". Confirm with product whether the
  Android listing should read "iOS · Android". Until then, keep the data identical.

---

## 11. Token icon pack (`BitToolSigner/Services/TokenIconStore.swift`)

The token artwork is bundled in a single file, because 1,971+ loose PNGs would each round up to a filesystem block.
**The store never touches the network**; an unknown ID simply has no icon.

- `Resources/TokenIcons.index.json` is a JSON object `{ "<16 lowercase hex>": [offset, length], … }`.
  - The key is the first 16 hex digits of the image's SHA-256. Tokens that share artwork share one key.
  - The shipped file has **1,985 entries**, exactly the set of `icon` values in `token-registry.json` (none missing,
    none unused).
  - The entries are contiguous and in key order, and they end exactly at the pack's EOF.
- `Resources/TokenIcons.pack` is 11,205,605 bytes of concatenated **PNG** files.
  - Most icons are 64×64 (1,414) or 128×128 (564); a handful are 116–128 pixels.
  - The largest is 41,864 bytes and the smallest 256 bytes.
- `image(icon)` (`TokenIconStore.swift:32-44`):
  1. A nil or empty icon returns nil.
  2. Check the cache, an `NSCache` with `countLimit = 200`.
  3. Look the ID up in the index. The entry must have 2 elements with `offset ≥ 0`, `length > 0` and
     `offset + length ≤ pack.count`.
  4. Slice the bytes and decode them as an image. Failure returns nil.
  5. Cache the image and return it.
  - The pack is memory-mapped (`.mappedIfSafe`) and the index is parsed lazily once.
  - `count` = the number of index entries.
- `TokenIcon` view (`Logos.swift:160-185`):
  - It fills a circle of size `size` (default 40), with `scaledToFill` and high-quality interpolation.
  - Fallback: a systemGray5 circle with `symbol.prefix(3).uppercased()` in a bold rounded font at `size*0.3`,
    secondary colour, minimum scale 0.5.
  - Overlay: a `primary @ 8%` 0.5 pt circle stroke.
  - The accessibility label is the symbol.
- The icon ID comes from `ReviewAsset.icon` (review) and from registry rows (Settings › Networks, Tokens & Contracts).

---

## 12. Android mapping

### 12.1 Modules (following Ownbit 5.0 `settings.gradle.kts` and its build-logic conventions)

| module | contents from this spec |
|---|---|
| `:core:model` (pure Kotlin/JVM, kotlinx.serialization, no Android) | `Chain`, `NetworkBrand` (minus the drawable IDs), `AccountProfile`, `PairingLevel`, `ExportMode`, `AddressFormatter`, all review models (`TransactionReview`, `ReviewField`, `ReviewAsset`, `ReviewNetwork`, `ReviewContract`, `ReviewDisplay`, `ReviewAssetKind`, `ReviewFlowSection`, `DecodeStatus`, `RiskLevel`), `SigningPlan`/`SigningTask`, `PairingPayload`, `WalletPairingPayload`, `QrPayloadView`, `PublicAccount`, `WalletPublicMetadata`, `PublicMetadataStatus`, `VaultMetadata`, `SigningReceipt`, `RecoveryCards`, `BackupVerification`, `CompatibleWallets` data, `AppLanguage` enum (codes and endonyms only), `ReviewText.shortDecimal`, and `EngineJson` readers (copy Ownbit's `EngineJson.kt`). Every item gets JVM unit tests ported from `BitToolSignerTests/*`. |
| `:core:engine` | the QuickJS `SignerEngine` (`suspend fun run(op: String, params: JsonObject): JsonObject`), `IncrementalQrEngine`, `SigningEngineException(code, message)` |
| `:core:vault` | `WalletVault`, `PasswordPolicy`, `BIP39Seed`, `SecureRandomSource` |
| `:core:data` | `SignerStore` (= AppStore, `@Singleton`), `PublicWalletRepository`, `ReceiptStore`, `TokenIconStore`, `UserPreferences` (DataStore: `appearance`, `language`, `onboardingVersionCompleted`) |
| `:core:l10n` | adapt Ownbit's `core/l10n` (`AppLanguage.kt`, `LanguageManager`, `LocalePlatform`) plus a `ReviewText` translator backed by the runtime map |
| `:core:designsystem` / `:core:ui` | `NetworkLogo`, `ChainGlyph`, `GenericNetworkLogo`, `NetworkLogoStack`, `TokenIcon`, `WalletAvatar`, `WalletAppIcon`, `AddressText`, `HashText`, `CopyableAddress` |

### 12.2 Kotlin model shapes (sketch)

```kotlin
@Serializable enum class Chain(val wire: String) {
    @SerialName("bitcoin") Bitcoin("bitcoin"), @SerialName("evm") Evm("evm"),
    @SerialName("tron") Tron("tron"), @SerialName("solana") Solana("solana");
    val displayName get() = when (this) { Bitcoin -> "Bitcoin"; Evm -> "EVM"; Tron -> "TRON"; Solana -> "Solana" }
    val longName get() = if (this == Evm) "Ethereum & EVM" else displayName
    companion object { fun fromWire(s: String?) = entries.firstOrNull { it.wire == s } }
}

data class ReviewField(val id: Int, val label: String, val value: String) {
    val shortLabel: String get() = label.substringAfter(" · ", label)
    // baseLabel/isTokenIdentifier/holdsNumber/isLong exactly as §3.2.1 (grapheme counts!)
}

data class TransactionReview(/* §3.2 */) {
    companion object { fun parse(raw: JsonObject): TransactionReview }  // never throws; defaults per table
}

class SigningPlan private constructor(val requestHash: String, val planHash: String,
                                      val tasks: List<SigningTask>, val raw: JsonObject /* untouched */) {
    companion object { fun parse(raw: JsonObject, expectedRequestHash: String): SigningPlan /* throws AppIssue */ }
}

@Serializable data class SigningReceipt(
    val id: String, val date: Long /* epoch ms */, val chain: Chain, val title: String, val requestHash: String,
    @SerialName("walletID") val walletId: String? = null, @SerialName("walletKeyID") val walletKeyId: String? = null,
    val masterFingerprint: String? = null, val resultType: String? = null, val summary: String? = null,
    val networkName: String? = null, @SerialName("chainID") val chainId: String? = null, val riskLevel: String? = null,
    @SerialName("requestJSON") val requestJson: String? = null, val responseFrames: List<String>? = null,
    val responseText: String? = null,
)
// Json { explicitNulls = false; ignoreUnknownKeys = true; encodeDefaults = false }

sealed interface MatchingIssue {
    data object Unsupported : MatchingIssue      // "Unsupported signing protocol"
    data object Interrupted : MatchingIssue      // "Matching interrupted. Try again."
    data object WalletsChanged : MatchingIssue   // "Wallet information changed. Match this request again."
    data class Engine(val message: String) : MatchingIssue  // resolver messages, incl. the 4 known ones
    data class Failure(val message: String) : MatchingIssue // localised error
    val canRetry get() = this is Interrupted || this is WalletsChanged
}
```

- `ReviewAssetKind` and `ReviewContract.Role` should be enums with `fromWire()` that returns null or the default; do
  not use kotlinx's enum decoding, which throws on an unknown value.
- Decode `ReviewAsset` with a strict `Json.decodeFromJsonElement` inside `runCatching { }.getOrNull()`. That
  reproduces "any mismatch → nil".
- `WalletPublicMetadata` keeps `raw: JsonObject` plus `canonicalBytes: ByteArray`, which is used for the
  snapshot/equality checks and the 256 KiB limit. Compare snapshots by `Map<WalletId, JsonObject>` structural
  equality.

### 12.3 `SignerStore` (AppStore) on Android

- Expose `val state: StateFlow<SignerState>`, an immutable data class holding every published property in §5.1. Add
  derived extension properties: `hasSignedResponse`, `pendingTasks`, `hasIncompleteWallets`.
- **Confine all mutation to the main thread** (`withContext(Dispatchers.Main.immediate)`, or a single-thread
  `CoroutineScope`) to mirror `@MainActor`. Use `_state.update {}` for atomic copies.
- Engine work runs on a **dedicated single-thread dispatcher**: `Executors.newSingleThreadExecutor()
  .asCoroutineDispatcher()`. QuickJS contexts must be created and used on the same thread;
  `Dispatchers.Default.limitedParallelism(1)` does **not** pin a thread. Vault work (PBKDF2 at 600,000 iterations,
  Keystore) runs on `Dispatchers.Default`.
- Epochs: `securityEpoch` and `navigationResetEpoch` are counters in `state` (`Long`, incremented). `sensitiveEpoch`,
  `requestEpoch` and `reloadEpoch` are private `AtomicLong`s.
- Port `requireSession(epoch, requestId)` as `currentCoroutineContext().ensureActive()` followed by `if (epoch !=
  securityEpoch || …) throw CancellationException()`. Call it after **every** suspension point listed in §5. UI
  layers catch and ignore `CancellationException`. Never wrap it in `runCatching`.
- `busy` and the operation token replace `signingOperation`. Use `try { } finally { if (op == current) busy = false }`.
- Triggers for `suspend()`:
  - `ProcessLifecycleOwner` `ON_STOP` (background);
  - App Lock becoming locked;
  - screen capture: `Activity.registerScreenCaptureCallback` on API 34+, and `WindowManager.addScreenRecordingCallback`
    on API 35+;
  - inside `resetPassword` and `deleteWallet`.
  Also set `FLAG_SECURE` on secret screens, which prevents most capture outright.
- Connect prefetch: `setConnectPrefetch(allowed)` is driven by a combined flow of lifecycle `RESUMED`, not locked, not
  capturing, and stage == main. Delay 600 ms, then render `ImageBitmap`s with ZXing on `Dispatchers.Default`, and keep
  the same discard conditions.
- `error: String?` becomes a `MutableStateFlow` that the root composable shows as an `AlertDialog` titled "Unable to
  Continue" with "OK". The ViewModels can also set it.
- Storage:
  - `receipts.json` and `PublicWallets/` go under `context.noBackupFilesDir/BitToolSigner/`. App-private storage is
    0600 by default, and credential-encrypted storage plays the role of complete file protection.
  - Write with `androidx.core.util.AtomicFile`.
  - Declare `android:allowBackup="false"` and `dataExtractionRules` that exclude everything.
- Size checks use UTF-8 byte counts. Character limits use grapheme counts (§0.3).

### 12.4 Localisation and `ReviewText`

- Static UI uses `R.string` resources generated from `translations/*.json`. Ownbit's `tools/strings-import` and
  `core/l10n` show the conversions:
  - `%@` → `%s`, `%lld` → `%d`, positional `%1$@` → `%1$s`;
  - `%%` stays;
  - escape `'`, `"`, `@` and `?` at the start of a value.
- The resource folders follow Ownbit: `values`, `values-b+zh+Hans`, `values-b+zh+Hant`, `values-ja`, `values-ko`,
  `values-ru`, `values-uk`, `values-es`, `values-b+es+419`, `values-de`, `values-fr`, `values-it`, `values-pt-rBR`,
  `values-pt-rPT`, `values-pl`, `values-in` + `values-b+id`, `values-tr`, `values-th`, `values-vi`, `values-b+fil`,
  `values-ar`. Add `res/xml/locales_config.xml` and `android:supportsRtl="true"`.
- Apply the per-app language with `LocaleManager` on API 33+ or `AppCompatDelegate.setApplicationLocales`
  (Ownbit `LocalePlatform.kt`). That recreates the activity, the equivalent of `.id(language)`. Navigation then
  returns to the first tab.
- Ownbit applies Arabic as `ar-u-nu-latn` so digits stay Latin. Adopt the same.
- **Engine text needs a runtime map.** Bundle `assets/translations/<code>.json` (the `strings` object) and load
  `Map<String, String>` for the active language. `ReviewText.phrase`, `headline` and `label` and the matching-error
  explanations look up English keys in it. A missing entry returns the English unchanged. Never route engine strings
  through `getIdentifier`.
- Drop the `infoPlist` section. On Android the camera rationale is a normal string resource; it can reuse the
  `NSCameraUsageDescription` translations.

### 12.5 Resources and assets

- Chain logos: convert `Assets.xcassets/Chain*.imageset/*.svg` to VectorDrawables (`ic_chain_bitcoin` and so on).
  Wallet logos: `wallet_*.imageset/icon.png` (180×180) go to `drawable-nodpi/wallet_<name>.png`, using the same names.
  Map `CompatibleWallet.asset` to a drawable with a `when`, not `getIdentifier`, so R8 keeps the resources.
- Token icons:
  - Place `TokenIcons.pack` in `assets/` and add `androidResources { noCompress += listOf("pack") }`. Then
    `assets.openFd("TokenIcons.pack")` yields a mappable `FileChannel`. `FileInputStream(fd.fileDescriptor).channel
    .map(READ_ONLY, fd.startOffset, fd.length)` reproduces `.mappedIfSafe`.
  - Parse the index once, lazily, with kotlinx (`Map<String, List<Int>>`).
  - Cache in `LruCache<String, ImageBitmap>(200)` and decode with `BitmapFactory.decodeByteArray` on
    `Dispatchers.IO`.
  - The `TokenIcon` composable loads with `produceState`; until the bitmap arrives it shows the 3-letter fallback.
- `WalletAvatar`: use `MessageDigest.getInstance("SHA-256")` and `Color.hsv(hue*360f, s, v)` with a
  `Brush.linearGradient` from top-start to bottom-end. The Compose gradient follows the layout direction, which
  matches iOS's `.topLeading`.

### 12.6 Pitfalls checklist

1. Keep the `plan`, `request`, `publicIndex` and `previousResult` JSON raw; their hashes depend on it (§0.4).
2. Port the strict typed readers; never coerce types (§0.2).
3. Count graphemes for `.count` limits and UTF-8 bytes for `.utf8.count` limits (§0.3).
4. `requestHash` comparisons are case-sensitive except in `review(storedRequest:)`, which is case-insensitive.
5. Run the epoch check after every `await`. A late engine result must never repopulate state after
   suspend/lock/background.
6. The receipt list is newest first. Payload retention applies to indexes 0..24. Strip payloads from the oldest record
   until the encoding is ≤ 2 MiB. A corrupt file blocks appends until cleared.
7. `encodeResponse` returns no `text`, so the frames joined with `"\n"` become the exported file text.
8. `BackupVerification` must use `SecureRandom` and reshuffle until the chip order differs from the answer order.
9. QuickJS thread affinity: use one dedicated thread for the engine. The scan session context lives from
   `beginScan` to `endScan`.
10. `CompatibleWallets.directory` merge semantics: the first signing record wins, otherwise the last watch-only record.
    OKX shows "iOS · Android".

---

## 13. Out of scope (iOS-only), drop or replace

- `UIImage`, `NSCache`, `Bundle.main.localizations`, `Bundle(path:)` / `AppText.bundle` mechanics: replaced by
  Android resources and the runtime map.
- `LanguageEnvironment` `.environment(\.locale/\.layoutDirection)` and `.id()`: replaced by per-app locales and
  activity recreation.
- iOS file attributes: `FileProtectionType.complete`, `isExcludedFromBackup` and POSIX modes. Replaced by
  app-private `noBackupFilesDir`, backup rules and FBE.
- `UIApplication.willEnterForegroundNotification`, `scenePhase` and `isSceneCaptured`: replaced by
  ProcessLifecycleOwner and the screen-capture callbacks.
- `autoreleasepool` and `JSVirtualMachine`/`JSContext`: replaced by QuickJS (`wang.harlon.quickjs:wrapper-android`).
- `SimulatorUITestContext`, and the `AppStore(storageDirectory:)` test seam: use Hilt test modules instead.
- The DEBUG-only engine operations are allowed in debug builds only, if at all.
- `infoPlist.NSFaceIDUsageDescription` and every Face ID / Touch ID wording.
- SF Symbols: map each one to Material Symbols. These are icon choices, not data.
- `localizedCaseInsensitiveCompare`: use `java.text.Collator` with the app locale, or `String.CASE_INSENSITIVE_ORDER`
  (the names are ASCII).
- `VaultError.devicePasscodeRequired`'s iPhone wording belongs to the vault spec. Android needs a "screen lock"
  equivalent.
