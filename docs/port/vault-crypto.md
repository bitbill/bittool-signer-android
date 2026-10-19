# Port spec: secret storage, crypto and wallet persistence

Status: specification for the Android port of BitTool Signer (iOS Revision 11, 18 Oct 2026).
Audience: Android implementers. Read this next to the Swift files it cites.

Source roots (read-only):

- `BitToolSigner/` = `/Users/tanguopeng/Documents/bitsigner-ios/BitSigner/BitToolSigner/`
- `BitToolSignerTests/` = `/Users/tanguopeng/Documents/bitsigner-ios/BitSigner/BitToolSignerTests/`
- `docs/` = `/Users/tanguopeng/Documents/bitsigner-ios/BitSigner/docs/`
- `engine/` = `/Users/tanguopeng/Documents/bitsigner-ios/BitSigner/engine/` (JS sources of `signer.js`)

Files read in full: `Core/BIP39Seed.swift` (53 lines), `Core/PasswordPolicy.swift` (46), `Core/VaultCrypto.swift` (216), `Core/WalletVault.swift` (279), `Services/PublicWalletRepository.swift` (132), `Models/BIP39Wordlist.swift` (390), `Models/AppStore.swift` (602), `Models/Models.swift` (702), `Core/SigningEngine.swift` (215), `Design/PasswordViews.swift` (239), `Features/Wallets/WalletSecuritySheets.swift` (345), `Features/Wallets/WalletDetailView.swift` (214), `Features/Wallets/WalletsTab.swift` (153), `Features/Setup/WalletSetupModel.swift` (230), `Features/Setup/WalletSetupFlow.swift` (452), `Features/Setup/RecoveryPhraseViews.swift` (380), `Services/AppAccessController.swift` (213), `Features/Main/RootView.swift` (113), `docs/Security.md` (155), the relevant parts of `IMPLEMENTATION_STATUS.md`, and the tests `SecurityTests`, `HardeningTests`, `BIP39LanguageTests`, `SessionTests`, `BitToolSignerTests`.

Convention: every user-visible English string is quoted verbatim between straight double quotes, exactly as in Swift, including typographic apostrophes (`’`, U+2019) and curly quotes where the source has them; Swift interpolations are shown as their localization-key specifiers (`%@`, `%lld`). These strings are the localization keys (`translations/*.json` → `strings` map, English = key). Strings marked **[untranslated on iOS]** are not present in `translations/*.json` (iOS shows them in English in every language); Android should still make them string resources (English in all locales until translated).

---

## 1. Security model in one page

| Layer | What | Where (iOS) | Key | Notes |
|---|---|---|---|---|
| Wallet secret | `{"mnemonic","passphrase"}` JSON | inside vault | — | mnemonic = canonical NFC words joined by single spaces |
| Password layer | AES-256-GCM | inner ciphertext | PBKDF2-HMAC-SHA256(password, 32-byte salt, 600,000 it.) → 32 bytes | AAD = `com.bittool.signer.vault.v2/password\0` + header bytes |
| Device layer | AES-256-GCM over the inner *combined* box | `Vaults/<uuid>.bsvault` | random 32-byte per-wallet device key in Keychain | AAD = `com.bittool.signer.vault.v2/device\0` + header bytes |
| Retry counter | AES-GCM `{count, retryAfter}` | `Vaults/.<uuid>.attempts` | same per-wallet device key | AAD = `com.bittool.signer.auth.v1/<UPPERCASE-UUID>` |
| Public index | `{schemaVersion:2, body:<b64>, mac:<hex>}` | `PublicWallets/<uuid>.json` | one app-wide random 32-byte HMAC-SHA256 key in Keychain | MAC over domain + lowercase UUID + exact body bytes |
| Activity | JSON array of receipts | `receipts.json` | none (public data) | bounded journal |

There is **no** global "active wallet", no unlock session and no cached password: every secret operation (show phrase, change password, delete, repair, App-Lock password fallback, each signing task) authenticates that one wallet again (`docs/Security.md:42`, `Models/AppStore.swift:14-16`).

The encrypted vault is deliberately **not portable**: the device key never leaves the device (`docs/Security.md:22`). Consequently Android does not need byte compatibility with iOS vault files; it needs the same *design, parameters, bounds, error behaviour and UX*. Where this spec says "normative for Android" it defines the Android byte format.

---

## 2. Storage layout

### 2.1 iOS paths

Base: `FileManager.urls(for: .applicationSupportDirectory)` → `Library/Application Support/BitToolSigner/`.

| Path | Writer | Source |
|---|---|---|
| `BitToolSigner/Vaults/<lowercase-uuid>.bsvault` | `WalletVault.save` | `Core/WalletVault.swift:74, 253-255, 276-278` |
| `BitToolSigner/Vaults/.<lowercase-uuid>.attempts` (hidden file) | `WalletVault.saveAuthenticationState` | `Core/WalletVault.swift:222-224, 244-251` |
| `BitToolSigner/Vaults/.pending-<UUID>` (transient staging) | `writeProtected` | `Core/WalletVault.swift:265` |
| `BitToolSigner/PublicWallets/<lowercase-uuid>.json` | `PublicWalletRepository.write` | `Services/PublicWalletRepository.swift:24, 69, 100-117`; `Models/AppStore.swift:74` |
| `BitToolSigner/PublicWallets/.pending-<UUID>` (transient) | same | `Services/PublicWalletRepository.swift:111` |
| `BitToolSigner/receipts.json` | `AppStore.saveReceipts` | `Models/AppStore.swift:73, 592-600` |
| UserDefaults keys `appearance`, `language`, `appLockEnabled.v1`, `onboardingVersionCompleted` | various | `BitToolSignerApp.swift:9-10`, `Services/AppAccessController.swift:98`, `Features/Wallets/WalletsTab.swift:6` |

Note the `.pending-…` names: the vault one is `".pending-\(UUID().uuidString)"` (uppercase), the public one `".pending-\(UUID())"` (also uppercase via description). `list()` only considers `pathExtension == "bsvault"`, so `.attempts` and `.pending-*` files are ignored (`Core/WalletVault.swift:82`).

### 2.2 Protection applied to every write (`writeProtected`, `Core/WalletVault.swift:257-274`; identical code in `Services/PublicWalletRepository.swift:106-116`)

1. `createDirectory(withIntermediateDirectories: true, attributes: [posixPermissions 0o700, protectionKey .complete])`.
2. Directory `isExcludedFromBackup = true`.
3. Stage to `<dir>/.pending-<UUID>` with `Data.write(options: [.atomic, .completeFileProtection])`.
4. Set staged file `posixPermissions 0o600`, `protectionKey .complete`, `isExcludedFromBackup = true`.
5. `rename(2)` staged → final (atomic replace). Comment: "All protection attributes exist before atomic replacement. A failed preparation leaves the previous vault/password intact."
6. `defer` removes the staged file if anything failed.
7. Any error → `VaultError.storageFailure` (vault) / thrown error (public repository).

`receipts.json` is written with `.atomic, .completeFileProtection` directly (no explicit staging), then backup exclusion and `0o600` (`Models/AppStore.swift:592-600`).

### 2.3 Read guards

- Vault: file must exist (else `walletNotFound`), be a regular file (`attributesOfItem` does **not** follow a final symlink → a symlink is rejected) and `size <= 65_536` (`Core/WalletVault.swift:206-220`).
- Attempts: regular file, `size <= 1024` (`Core/WalletVault.swift:232-234`).
- Public file: regular file, `size <= 600_000` (`Services/PublicWalletRepository.swift:81-83`).
- Receipts: regular file, `size <= 2_097_152` (`Models/AppStore.swift:543-545, 574`).

---

## 3. Randomness

`SecureRandom` (`Core/VaultCrypto.swift:47-63`):

- `bytes(count:)`: `count` must be in `1...65_536`, else `VaultError.randomFailure`. Uses `SecRandomCopyBytes`; non-success → `randomFailure`.
- `entropyHex(wordCount:)`: `wordCount ∈ {12,15,18,21,24}` else `VaultError.invalidSecret`; returns `bytes(count: wordCount / 3 * 4)` as lowercase hex (12→16 bytes/32 hex chars, 15→20, 18→24, 21→28, 24→32/64 hex chars).
- The JS engine's `crypto.getRandomValues` and `Math.random` are backed by the same `SecureRandom.bytes` (`Core/SigningEngine.swift:25-60`; `getRandomValues` accepts only integer typed arrays, `byteLength <= 65536`, returns the array untouched when `byteLength == 0`; `Math.random` = `((u32[0] >>> 5) * 67108864 + (u32[1] >>> 6)) / 9007199254740992`). The engine spec owns this; it is listed here because mnemonic generation, salts, nonces and device keys must all come from the same CSPRNG.

Error string: `randomFailure` → "Secure randomness is unavailable. Try again after unlocking your device." (`Core/VaultCrypto.swift:29`).

Test (`BitToolSignerTests/SecurityTests.swift:22-31`): two 32-byte draws differ; `entropyHex(12).count == 32`; `entropyHex(24).count == 64`; `bytes(65_537)` throws `randomFailure`; `entropyHex(13)` throws `invalidSecret`.

---

## 4. Password policy

Source: `Core/PasswordPolicy.swift:1-46`; doc `docs/Security.md:32`.

"Passwords are used exactly as entered, without trimming or normalization." — no trim, no NFC/NFKD, no case folding. The KDF input is the UTF-8 bytes of the string as typed.

Constants: `minimumLength = 8`, `maximumLength = 64`.

`evaluate(password)` computes, over Unicode **scalars** (code points):

| Field | Rule (iOS `CharacterSet`) | Unicode general categories |
|---|---|---|
| `hasUppercase` | any scalar in `uppercaseLetters` | Lu, Lt |
| `hasLowercase` | any scalar in `lowercaseLetters` | Ll |
| `hasNumber` | any scalar in `decimalDigits` | Nd |
| `hasSymbol` | any scalar in `symbols` **or** `punctuationCharacters` | Sm, Sc, Sk, So, Pc, Pd, Ps, Pe, Pi, Pf, Po |
| `categories` | count of the four booleans above | 0–4 |
| `hasValidLength` | `8 <= password.count <= 64` **and** `password.utf8.count <= 1024` | `count` = **grapheme clusters** (Swift `Character`s) |
| `containsControl` | any scalar in `controlCharacters` | Cc, Cf (Cf includes ZWJ U+200D, soft hyphen U+00AD, BOM) |
| `isValid` | `hasValidLength && categories >= 3 && !containsControl` | |

Notes that matter for parity:

- Space (Zs) counts as nothing; it is allowed ("Spaces are Allowed1!" is valid).
- Letters without case (Lo/Lm: Chinese, Japanese kana, Thai, Arabic…) count as **no category**. A password of only CJK characters plus a digit and a symbol has 2 categories → invalid.
- Emoji are So → count as "symbol". A ZWJ emoji sequence contains U+200D (Cf) → **rejected** as a control character.
- Length is in grapheme clusters: "👍🏽" is 1, not 4 (Kotlin `String.length` would say 4).

Message (first matching rule wins, `Core/PasswordPolicy.swift:33-41`):

1. `!hasValidLength` → "Use 8–64 characters."
2. `containsControl` → "Control characters are not allowed."
3. `categories < 3` → "Include at least 3 of: uppercase, lowercase, numbers and symbols."
4. otherwise → "Password meets the requirements."

The message is what `VaultError.invalidPassword(message)` carries when the vault rejects a password (`Core/VaultCrypto.swift:152-153`, `Core/WalletVault.swift:155-156, 177-178`).

Test vectors (`BitToolSignerTests/SecurityTests.swift:10-20`):

| Input | Valid |
|---|---|
| `Abcdefg1` | yes |
| `abcdef1!` | yes |
| `Abcde1!` (7) | no |
| `alllowercase` | no |
| `ABCDEFG1` | no (2 categories) |
| `Abcdefg\n1` | no (control) |
| `"Aa1" + 61×"x"` (64) | yes |
| `"Aa1" + 62×"x"` (65) | no |
| `Spaces are Allowed1!` | yes |

---

## 5. Password entry UI and strength meter

### 5.1 `PasswordField` (`Design/PasswordViews.swift:9-98`)

Kinds (raw value = English placeholder = accessibility identifier, `Design/PasswordViews.swift:37-61`):

| Kind | Placeholder / title |
|---|---|
| `walletPassword` | "Wallet password" |
| `confirmPassword` | "Confirm password" |
| `currentWalletPassword` | "Current wallet password" |
| `newPassword` | "New password" |
| `confirmNewPassword` | "Confirm new password" |
| `passphrase` | "BIP39 passphrase" |
| `confirmPassphrase` | "Confirm passphrase" |

Behaviour:

- Non-revealable fields: `SecureField`, `textContentType = .password` unless `isNew` (then nil), no autocapitalization, autocorrection off, `.privacySensitive()`.
- `isNew = true` (a secret being set for the first time: new wallet password, new password in Change Password, passphrase): AutoFill and the strong-password generator are disabled (empty content type, empty `passwordRules`) because a generated password "for a BIP39 passphrase silently produces a different wallet" (`Design/PasswordViews.swift:17-21, 122-127`).
- `revealable = true` (only the passphrase fields): eye button, SF symbols `eye` / `eye.slash`, 32×32, accessibility labels "Show secret" / "Hide secret", identifier `<kind>.reveal`. Revealed state resets to concealed whenever `scenePhase != .active` (`Design/PasswordViews.swift:96`). The field keeps its text across reveal toggles. It carries a keyboard "Done" accessory.

### 5.2 `PasswordRequirementsView` (`Design/PasswordViews.swift:169-217`)

Inputs: `password`, `confirmation`. Layout: VStack(spacing 8), footnote font.

1. Strength bar: 4 capsules, height 4 pt, spacing 4, `accessibilityHidden(true)`, animated `.smooth(duration: 0.2)` on strength change.
   - `strength = 0` if password empty; `1` if not `isValid`; else `4` if `password.count >= 16 && categories == 4`; else `3` if `password.count >= 12`; else `2` (`Design/PasswordViews.swift:192-196`; counts are grapheme counts).
   - Filled capsules `index < strength`. Colour: strength 1 → `Palette.warning` (systemOrange); 2 → systemYellow; 3 and 4 → `Palette.success` (systemGreen). Unfilled → systemFill.
2. Requirement rows (label text exactly):
   - "8 to 64 characters" — met = `hasValidLength`
   - "3 of: uppercase, lowercase, number, symbol" — met = `categories >= 3`
   - "Passwords match" — met = `!confirmation.isEmpty && password == confirmation`
   - Icon `checkmark.circle.fill` tinted success when met, `circle` secondary otherwise, with symbol replace transition; text primary when met, secondary otherwise; row is one accessibility element with value "Met" / "Not met".
   - The control-character rule has no row; such a password shows strength 1 (orange) even if all three rows are met.

Light/dark: only system semantic colours are used, so both modes follow the platform palette.

### 5.3 Wallet identity header (`Design/PasswordViews.swift:221-239`)

Shown wherever a wallet must be recognised before its password is typed: avatar (default size 56) + name (headline) + "Fingerprint %@" (caption monospaced, secondary) where `%@` = `fingerprint(for:)` (§11.9). Combined into one accessibility element.

---

## 6. Vault file format v2

Sources: `Core/VaultCrypto.swift:65-216`, `docs/Security.md:11-22`, review fix F9 (`docs/Security.md:104`, `IMPLEMENTATION_STATUS.md:919`).

### 6.1 Data model

```
VaultMetadata { id: UUID, name: String, createdAt: Date }          // VaultCrypto.swift:65-69
VaultSecret   { mnemonic: String, passphrase: String }             // :73-76
VaultHeader   { version: Int, metadata: VaultMetadata,
                kdf: String, iterations: UInt32, salt: Data }      // :78-84
EncryptedVault{ header, headerBytes: Data, ciphertext: Data }      // :89-118
```

### 6.2 Outer container (file contents)

JSON object, encoded by `VaultCrypto.encoder()` = `JSONEncoder` with `.sortedKeys` (`:205-209`):

```json
{"ciphertext":"<base64>","format":2,"header":"<base64 of headerBytes>"}
```

| Key | Type | Meaning |
|---|---|---|
| `format` | integer | must equal `VaultCrypto.version` = 2, else `unsupportedVersion` (`:105`) |
| `header` | base64 string | the exact UTF-8 JSON bytes of `VaultHeader`; decoded length must be `<= 4096` else `malformedVault` (`:107`) |
| `ciphertext` | base64 string | outer AES-GCM combined box (§6.5) |

Decoding: `header` bytes are parsed with a plain `JSONDecoder` only to read the fields; **the stored bytes, never a re-encoding, are what both GCM layers authenticate** (`:86-88`). Test `vaultAuthenticatesTheExactStoredHeaderBytes` (`BitToolSignerTests/HardeningTests.swift:102-118`) re-encodes the same header pretty-printed and expects `authenticationFailed`; setting `format` to 1 must fail decoding.

### 6.3 Header JSON (`headerBytes`)

Produced once at encryption with sorted keys (`:157-159`). iOS byte form:

```json
{"iterations":600000,"kdf":"PBKDF2-HMAC-SHA256","metadata":{"createdAt":<Double>,"id":"<UPPERCASE-UUID>","name":"<name>"},"salt":"<base64 32 bytes>","version":2}
```

| Field | iOS encoding | Constraint on read (`validate`, `:195-203`) |
|---|---|---|
| `version` | integer 2 | `== 2` else `unsupportedVersion` |
| `kdf` | `"PBKDF2-HMAC-SHA256"` | exact string else `unsupportedVersion` |
| `iterations` | UInt32, written as 600000 | `600_000...2_000_000` else `malformedVault` |
| `salt` | base64 (Foundation default `Data` strategy), 32 bytes | exactly 32 bytes else `malformedVault` |
| `metadata.id` | UUID string, **uppercase** (Swift `uuidString`) | must equal the id in the file name (`Core/WalletVault.swift:216`) else `malformedVault` |
| `metadata.name` | string; JSONEncoder escapes `/` as `\/` | non-empty and `count <= 40` (graphemes) else `malformedVault` |
| `metadata.createdAt` | JSONEncoder default `.deferredToDate` = **Double seconds since 2001-01-01T00:00:00Z** | any number |

`createdAt` value at creation: `Date(timeIntervalSince1970: floor(now_ms) / 1000)` — millisecond precision "keeps creation order stable for wallets added in quick succession" (`Core/WalletVault.swift:100-101`).

The header (including `name`) is **immutable for the life of the wallet**. Password change and reset re-encrypt with the *same* metadata and a *new* salt (`Core/WalletVault.swift:158-161, 180-184`). Rename never touches the vault (§10.5).

Normative for Android (vaults are never portable, so pick one encoding and keep it forever):

- Same container keys and header keys, keys sorted lexicographically, compact JSON, UTF-8.
- `salt`, `header`, `ciphertext`: standard base64 with padding (`java.util.Base64.getEncoder()`).
- `metadata.id`: uppercase canonical UUID (parity with iOS; any case is accepted on read, compare as UUID).
- `metadata.createdAt`: JSON number = seconds since 2001-01-01T00:00:00Z with millisecond precision, i.e. `(epochMillis - 978_307_200_000) / 1000.0`. (Alternatively an integer epoch-ms field — but then document it; do not mix.) Store the parsed value as epoch milliseconds in memory.
- Never re-serialize the header after creation; keep `headerBytes` as read and reuse them verbatim.

### 6.4 Password key derivation (`deriveKey`, `Core/VaultCrypto.swift:125-149`)

| Parameter | Value |
|---|---|
| PRF | HMAC-SHA256 |
| Iterations | 600,000 on write (`:121`); any value in 600,000–2,000,000 accepted on read |
| Salt | 32 random bytes per encryption (fresh on create, change and reset) |
| Output | 32 bytes → AES-256 key |
| Password bytes | `Array(password.utf8)` — no normalization |
| Guards | password non-empty and `utf8.count <= 1024`; salt exactly 32; iterations in range — else `malformedVault` (bounds are checked *before* the costly derivation, `:179`) |
| Failure | CommonCrypto status ≠ success → `keyDerivationFailure` ("The password key could not be created.") |
| Wiping | password byte array and derived array are `memset_s`-wiped in `defer` (`:132-135`); the `SymmetricKey` copy is not |

Test vector (independently re-verified with Python `hashlib.pbkdf2_hmac`, and with JVM `PBKDF2WithHmacSHA256`): password `Test-Password1`, salt bytes `00 01 02 … 1f`, 600,000 iterations → `05b079f15d09a3589c02ca352a3dd38ad23daf4663a59d6072c2333ddb4901d9` (`BitToolSignerTests/SecurityTests.swift:33-37`).

### 6.5 The two AES-GCM layers

CryptoKit `AES.GCM.seal` with a fresh random 96-bit nonce and 128-bit tag; `.combined` = `nonce(12) || ciphertext || tag(16)` (`docs/Security.md:15-16`).

AAD construction (`Core/VaultCrypto.swift:211-215`):

```
aad(layer) = UTF8("com.bittool.signer.vault.v2/" + layer) || 0x00 || headerBytes
layer ∈ {"password", "device"}
```

Hex of the fixed prefixes:

- password: `636f6d2e626974746f6f6c2e7369676e65722e7661756c742e76322f70617373776f726400`
- device: `636f6d2e626974746f6f6c2e7369676e65722e7661756c742e76322f64657669636500`

Construction (`encrypt`, `:151-174`):

```
cleartext   = JSON(VaultSecret) with sorted keys   // {"mnemonic":"…","passphrase":"…"}
inner       = AES-GCM(passwordKey, nonce1, cleartext, aad("password")).combined   // 12+n+16
ciphertext  = AES-GCM(deviceKey,   nonce2, inner,     aad("device")).combined     // 12+(28+n)+16 = n+56
```

So `ciphertext.count = len(cleartext) + 56`; the read bound is `56...32_768` (`:199`).

Decryption (`decrypt`, `:176-193`), in this order:

1. `validate(vault)` (§6.6).
2. `deviceKey.count == 32` else `deviceKeyUnavailable`.
3. Derive the password key with the header's salt and iterations (cost happens even if the device key is wrong).
4. Open the outer box with the device key and `aad("device")`.
5. Open the inner box with the password key and `aad("password")`.
6. Decode `VaultSecret` JSON; wipe the cleartext buffer.
7. **Any** failure in 4–6 → `authenticationFailed` → "The password is incorrect." (deliberately the same wording for tampering; Revision 10 item 4, `IMPLEMENTATION_STATUS.md` "Simplified the wrong-password message"; comment `Core/VaultCrypto.swift:32-34`).

Encryption preconditions (`:151-156`): password must pass `PasswordPolicy` (else `invalidPassword(message)`); mnemonic non-empty and `utf8.count <= 8192`, passphrase `utf8.count <= 1024` (else `invalidSecret`); device key 32 bytes (else `deviceKeyUnavailable`). Any CryptoKit failure → `encryptionFailure` ("The encrypted wallet could not be created.").

### 6.6 Validation bounds (all must hold, `validate`, `:195-203` + `load`, `Core/WalletVault.swift:206-220`)

| Check | Error |
|---|---|
| file exists | `walletNotFound` |
| regular file, size ≤ 65,536 | `malformedVault` |
| JSON decodes, `format == 2` | `unsupportedVersion` for format ≠ 2, otherwise `malformedVault` |
| header bytes ≤ 4,096 | `malformedVault` |
| `version == 2`, `kdf == "PBKDF2-HMAC-SHA256"` | `unsupportedVersion` |
| salt = 32 bytes, iterations 600,000–2,000,000, ciphertext 56–32,768 bytes, name non-empty and ≤ 40 | `malformedVault` |
| header id == file-name id | `malformedVault` |
| any other non-`VaultError` error while loading | `malformedVault` |

Test `authenticationRejectsWrongPasswordDeviceAndTampering` (`BitToolSignerTests/SecurityTests.swift:57-84`): wrong password → `authenticationFailed`; all-zero device key → `authenticationFailed`; last ciphertext byte flipped → `authenticationFailed`; header renamed "Attacker renamed" → `authenticationFailed`; `iterations = UInt32.max` → `malformedVault`. Round trip and fresh salt/nonce per encryption, and the serialized vault contains neither phrase, password nor passphrase (`:39-55`).

### 6.7 Error catalogue (`VaultError`, `Core/VaultCrypto.swift:8-45`)

Errors "deliberately exclude passwords, phrases, key material and JavaScript input" (`:7`).

| Case | Message (verbatim) |
|---|---|
| `invalidPassword(m)` | `m` (a PasswordPolicy message, or "The BIP39 passphrase exceeds 1,024 UTF-8 bytes.") |
| `invalidSecret` | "The recovery phrase is empty or too large." |
| `invalidName` | "Choose a wallet name of 1–40 characters." |
| `randomFailure` | "Secure randomness is unavailable. Try again after unlocking your device." |
| `keyDerivationFailure` | "The password key could not be created." |
| `encryptionFailure` | "The encrypted wallet could not be created." |
| `authenticationFailed` | "The password is incorrect." |
| `deviceKeyUnavailable` | "This wallet’s device key is unavailable. Restore your recovery phrase on this device." |
| `devicePasscodeRequired` | "Set an iPhone passcode before creating a wallet. The device key requires passcode protection." (iOS wording — Android needs a new string, §17.12) |
| `storageFailure` | "The encrypted wallet could not be read or saved." |
| `malformedVault` | "The wallet file is damaged or has an invalid format." |
| `unsupportedVersion` | "This wallet format is not supported by this version of BitTool Signer." |
| `walletNotFound` | "This wallet could not be found." |
| `temporarilyLocked(s)` | "Too many unsuccessful attempts. Try again in %lld seconds." (`s` = Int seconds; the translations key uses `%lld`) |

`AppIssue.message(String)` (`Models/Models.swift:461-464`) carries all other user-facing errors.

---

## 7. Device key layer (outer layer)

### 7.1 iOS Keychain item (`KeychainDeviceKeyStore`, `Core/WalletVault.swift:7-59`)

| Attribute | Value |
|---|---|
| `kSecClass` | `kSecClassGenericPassword` |
| `kSecAttrService` | `com.bittool.signer.vault.device-key.v1` |
| `kSecAttrAccount` | wallet UUID, **lowercase** |
| `kSecValueData` | 32 random bytes |
| `kSecAttrAccessible` | `kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly` |
| `kSecAttrSynchronizable` | `false` |

"Device keys never synchronize or migrate to another device. Removing the device passcode invalidates the item. No weaker accessibility fallback is permitted." (`:13-14`).

- `create(id:key:)`: first `LAContext.canEvaluatePolicy(.deviceOwnerAuthentication)` — false → `devicePasscodeRequired`. `SecItemAdd`; on `errSecAuthFailed`, `errSecNotAvailable`, `errSecInteractionNotAllowed` → `devicePasscodeRequired`; any other failure (including duplicate item) → `deviceKeyUnavailable`. It never overwrites.
- `read(id:)`: `SecItemCopyMatching`, must succeed and be exactly 32 bytes, else `deviceKeyUnavailable`.
- `delete(id:)`: success or `errSecItemNotFound` → ok; otherwise `deviceKeyUnavailable`.
- Callers wipe their copies with `resetBytes` after use (`Core/WalletVault.swift:103-104, 137-138, 159-160, 181-182, 125, 130`).

### 7.2 App-wide public-data HMAC key (`Core/WalletVault.swift:117-133`)

- Stored through the same `DeviceKeyStore` under the fixed id `0B175160-9A7E-4C1D-8000-00000000C0DE` (account `0b175160-9a7e-4c1d-8000-00000000c0de`), i.e. same service, same accessibility. "It never collides with random wallet UUIDs."
- `publicDataKey(createIfMissing:)`: read → return. If missing and `createIfMissing == false` → `nil`. Otherwise generate 32 random bytes and `create` (needs a passcode, same errors as above).
- Callers: `reload` (`createIfMissing: false`, error swallowed → `nil`), `renameWallet` (`false`), `createWallet` and `completeSetup` (`true`).
- It is **never deleted**, not even when the last wallet is deleted.

### 7.3 Lifecycle facts (from `IMPLEMENTATION_STATUS.md` Revision 3 follow-ups and `PRIVACY_POLICY.txt:40`)

- Deleting a wallet deletes its device key.
- On iOS the Keychain item can survive app deletion; it "holds no wallet data and is useless without the encrypted file". (On Android, Keystore entries are removed with the app — see §17.)

---

## 8. Failed-attempt throttling

Source: `Core/WalletVault.swift:66-69, 135-152, 222-251`; `docs/Security.md:54-56`.

State: `AuthenticationState { count: Int, retryAfter: Date }`, JSON via a default `JSONEncoder` (not sorted; `Date` as seconds since 2001), sealed with **the wallet's device key** using AES-GCM, `combined` stored as the whole file. AAD = UTF-8 of `"com.bittool.signer.auth.v1/" + id.uuidString` — **uppercase UUID, no NUL separator** (while the file name uses the lowercase UUID).

Read (`authenticationState`): missing file → `{0, distantPast}`. Present: must be a regular file ≤ 1024 bytes, open with the device key, decode, `count ∈ 0...16`; **any** failure → `malformedVault` — i.e. a damaged or foreign attempts file makes the wallet fail closed with "The wallet file is damaged or has an invalid format." until the file is removed by a reset/delete.

Write: any failure → `storageFailure`.

`unlock(id:password:)` sequence (exact order):

1. `load(id)` (all §6.6 checks; `walletNotFound` / `malformedVault` / `unsupportedVersion`).
2. `deviceKeys.read(id)` (`deviceKeyUnavailable`).
3. `previous = authenticationState(...)`.
4. If `previous.retryAfter > now` → throw `temporarilyLocked(max(1, ceil(retryAfter - now)))`. The attempt is **not** counted.
5. `count = min(previous.count + 1, 16)`; `delay = count >= 5 ? min(300, 2^(count - 4)) : 0` seconds.
6. **Persist** `{count, now + delay}` before any password work ("Killing and relaunching the process cannot reset the counter or interrupt its persistence."). Failure → `storageFailure` and the unlock aborts.
7. Empty password or `utf8.count > 1024` → `authenticationFailed` (counted).
8. `VaultCrypto.decrypt` (§6.5).
9. Success → persist `{0, distantPast}` and return `VaultSecret`.

Delay table (count after the attempt → delay before the next one):

| count | 1–4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 | 13–16 |
|---|---|---|---|---|---|---|---|---|---|---|
| delay (s) | 0 | 2 | 4 | 8 | 16 | 32 | 64 | 128 | 256 | 300 |

Consequences: a correct password always works when not inside a delay window (the counter counts the successful attempt and then resets). The clock is the wall clock (`Date()`); `docs/Security.md:56` documents that clock control or filesystem rollback can interfere ("an application control, not a secure hardware counter"). The app never destroys a wallet after failed attempts.

Every secret operation shares this counter: show phrase, change password (its `unlock`), delete, repair (`completeSetup`), App Lock password fallback (`verifyPassword`), signing.

Tests: five wrong attempts, then a *new* `WalletVault` instance on the same directory refuses the correct password with `temporarilyLocked(seconds > 0)` (`BitToolSignerTests/SecurityTests.swift:86-104`); a reset clears the delay (`:208-218`).

---

## 9. `WalletVault` operations

`actor WalletVault` (`Core/WalletVault.swift:61-279`): "Serializes filesystem mutations and performs password work away from the UI. No password, clear recovery phrase, or derived key is retained between calls." All methods are synchronous inside the actor, so operations are fully serialized (a 600k-iteration unlock blocks a concurrent `list()` until it finishes).

### 9.1 `list() -> [VaultMetadata]` (`:78-94`)

- Directory missing → `[]`.
- For each `*.bsvault`: the base name must parse as a UUID (else `malformedVault`), then `load(id)` (full validation). **One bad file fails the whole list** (error propagates).
- Sort: `createdAt` ascending; ties → `id.uuidString` ascending ("Directory enumeration must not reorder wallets with equal timestamps.").
- Non-`VaultError` errors → `storageFailure`.

This order is the wallet order everywhere in the UI (oldest first).

### 9.2 `create(name:mnemonic:passphrase:password:id:)` (`:96-115`)

1. `name` trimmed of whitespace and newlines; must be non-empty and `count <= 40` else `invalidName`.
2. File for `id` must not exist else `storageFailure`.
3. Metadata with millisecond-floored `createdAt`.
4. Generate a 32-byte device key.
5. `VaultCrypto.encrypt` (policy, bounds, KDF, both GCM layers).
6. `deviceKeys.create(id, key)` (passcode check here).
7. `save` (atomic protected write). If saving fails → `try? deviceKeys.delete(id)` and rethrow ("The key is new and not shared with any previously saved wallet.").
8. Wipe the key copy; return metadata.

### 9.3 `unlock` — §8.

### 9.4 `changePassword(id:currentPassword:newPassword:)` (`:154-163`)

1. `PasswordPolicy.evaluate(newPassword)` invalid → `invalidPassword(message)` (before any attempt is counted).
2. `secret = unlock(id, currentPassword)` (counted, throttled).
3. Reload metadata; read device key; `encrypt(secret, metadata, newPassword, deviceKey)` (new salt, new nonces, same device key, same header metadata); `save` (atomic replace — a failure leaves the old password valid).

Two full KDF runs (unlock + encrypt). Public data, pairings, reviewed requests and navigation stay valid (`Models/AppStore.swift:514-519`).

### 9.5 `resetPassword(id:mnemonic:passphrase:newPassword:)` (`:165-187`)

"Re-encrypts a stored wallet under a new password, without the old one." Proof is the recovery phrase (+ passphrase): "whoever holds the phrase and any BIP39 passphrase already controls the funds".

1. Policy check on `newPassword` → `invalidPassword(message)`.
2. `passphrase.utf8.count <= 1024` else `invalidPassword("The BIP39 passphrase exceeds 1,024 UTF-8 bytes.")`.
3. `load(id)` metadata (`walletNotFound` if absent); read device key.
4. Encrypt the *supplied* `{mnemonic, passphrase}` under the new password with the existing device key and metadata; save.
5. `try?` reset the attempts state to `{0, distantPast}`.

The vault cannot check that the phrase belongs to this wallet — `AppStore.resetPassword` must do so first (§11.6). Tests: old password stops working, phrase/passphrase come back unchanged, name and creation date survive, still exactly one wallet (`BitToolSignerTests/SecurityTests.swift:170-187`); weak password and unknown wallet refused without changing anything (`:189-203`).

### 9.6 `verifyPassword(id:password:)` (`:191-193`)

`_ = try unlock(...)` and discard the secret. Used by App Lock's "use a wallet password" path (`Services/AppAccessController.swift:162-167`). "App access authentication has no secret-returning API."

### 9.7 `delete(id:password:)` (`:195-204`)

1. `unlock` (authentication required "even if a UI session is already unlocked").
2. Remove the vault file (failure → `storageFailure`).
3. `try?` remove the attempts file.
4. `deviceKeys.delete(id)` — a failure is reported (`deviceKeyUnavailable`) although the file is already gone ("A remaining random key alone contains no wallet secret.").

Test `multipleWalletsPasswordRotationAndReauthenticatedDelete` (`BitToolSignerTests/SecurityTests.swift:148-165`): two wallets (second with passphrase "different account"), rotate, wrong-password delete keeps both, correct delete leaves only the second.

---

## 10. Public wallet repository (the public index file)

Source: `Services/PublicWalletRepository.swift:1-132`; `docs/Security.md:44-48`; review fix F17.

Purpose: public keys, addresses and display name of each wallet. "They cannot spend funds but reveal account relationships and drive the receive addresses people share, so each file is device-protected, excluded from backup and authenticated with an HMAC key kept in the Keychain. A missing, damaged or modified file never triggers seed decryption; the wallet shows 'Repair'." (`:11-15`).

### 10.1 Envelope (file contents)

```json
{"body":"<base64 of bodyBytes>","mac":"<64 lowercase hex>","schemaVersion":2}
```

Written with `JSONSerialization` `.sortedKeys` (`:103-105`).

### 10.2 Body

`JSONSerialization` with `.sortedKeys` of:

```json
{"displayName":"<name>","localWalletId":"<UPPERCASE-UUID>","publicIndex":{ …engine derivePublicIndex result… }}
```

- `localWalletId` = `walletID.uuidString` (uppercase) (`:44, 56`).
- `displayName` optional; when present it must be valid (trimmed non-empty, `count <= 40`) (`:66-68`).
- `publicIndex` optional (absent after a rename of a wallet whose file was unreadable, §10.5).
- `bodyData.count <= 300_000` else "Public wallet data is too large." (`:101-102`).

### 10.3 MAC (`:71-77`)

```
mac = HMAC-SHA256(publicDataKey,
        UTF8("com.bittool.signer.public-wallet.v2") || 0x00 ||
        UTF8(lowercase UUID) || 0x00 || bodyBytes)
```

Stored as lowercase hex (`%02x`). Verified in constant time (`timingsafe_bcmp`, `:89-92`).

Test vector (computed for this spec with Python `hmac`): key = bytes `00 01 … 1f`; id `00112233-4455-6677-8899-aabbccddeeff`; body = `{"displayName":"Public","localWalletId":"00112233-4455-6677-8899-AABBCCDDEEFF"}` → mac `5d52224242f35a071ea0fe4e9d091939e104f3cad6a2b557c9dbf18ac376f03b`.

### 10.4 Read (`read`, `:79-98`) — every condition, else `AppIssue("Invalid public wallet data.")`

1. Regular file, size ≤ 600,000.
2. Envelope is a JSON object; `schemaVersion` is the integer 2.
3. `body` is a string that base64-decodes.
4. `mac` is a string of exactly 64 characters that hex-decodes (either case) to 32 bytes and equals the recomputed MAC (constant time).
5. Body is a JSON object; `UUID(raw.localWalletId) == id` (case-insensitive UUID parse).

### 10.5 Operations

- `snapshot(walletID:key:) -> {displayName?, metadata?, status}` (`:27-41`):

| Condition | status | displayName |
|---|---|---|
| file missing | `needsCompletion` | nil |
| key is nil (HMAC key missing/unreadable) | `invalid` | nil |
| `read` fails | `invalid` | nil |
| `displayName` present but invalid | `invalid` | nil |
| body has no `publicIndex` object | `needsCompletion` | displayName |
| `WalletPublicMetadata(publicIndex)` throws | `invalid` | nil |
| otherwise | `ready` | displayName |

- `save(metadata, walletID, displayName?, key)` (`:43-51`): body = `{localWalletId, publicIndex: metadata.raw}`; name = given `displayName` ?? the name currently in a *valid* existing file; validate; write.
- `rename(walletID, name, key)` (`:53-59`): trim (whitespace+newlines); `validName` else `VaultError.invalidName`; body = existing valid body ?? `{localWalletId}`; set `displayName`; write. (If the existing file is invalid, its index is dropped and the wallet becomes `needsCompletion`.)
- `remove(walletID)` (`:61-64`): delete the file if present (no error if missing).
- `write` (`:100-117`): size check, envelope, protected atomic write (§2.2); rename failure → `VaultError.storageFailure`.

Tests (`BitToolSignerTests/HardeningTests.swift:79-100`): good file → `ready` with name "Public"; another random key → `invalid`; nil key → `invalid`; body tampered ("Public"→"Pwned") → `invalid`; unknown wallet → `needsCompletion`. `SessionTests.swift:76-88`: a corrupt sidecar → `invalid`, the vault file is untouched, Repair restores `ready`.

### 10.6 `WalletPublicMetadata` validation (`Models/Models.swift:466-531`)

`PublicMetadataStatus` raw values: `ready`, `needsCompletion`, `invalid` (`:466`). These raw values are sent to the engine (§11.10).

`init(raw)` throws `AppIssue` unless all of the following hold. First group → "Invalid public wallet data. Repair this wallet.":

- `version` is the integer **1** (public index version 1; the struct's `version` property returning 2 is unused).
- `walletKeyId` = 64 hex chars; `masterFingerprint` = 8 hex chars.
- `bitcoinPublicNodes` = array of exactly 6 objects; `evmAccountNode`, `tronAccountNode` objects; `tronAddressIndex` = array of exactly 100; `solanaPublicKeys` = array of exactly 3.
- `publicOnly(raw)`: recursive, depth ≤ 12; no object key (case-insensitive) in {`mnemonic`, `seed`, `privatekey`, `privateextendedkey`, `password`, `passphrase`, `xprv`, `secret`}; arrays ≤ 256 elements; strings ≤ 2,048 UTF-8 bytes and not starting with `xprv`; other leaves must be numbers/booleans/null.

Second group → "Public wallet data is incomplete or inconsistent.":

- BTC `originPath` set == {`m/84'/0'/0'`, `m/86'/0'/0'`, `m/49'/0'/0'`, `m/44'/0'/0'`, `m/48'/0'/0'/2'`, `m/48'/0'/0'/1'`}; EVM `m/44'/60'/0'`; TRON `m/44'/195'/0'`.
- Every node: `publicKey` 33-byte hex starting `02`/`03`; `chainCode` 32-byte hex; `parentFingerprint` 4-byte hex; `depth` integer 3…4; `xpub` starts with `xpub` and is < 120 chars.
- Solana `path` set == {`m/44'/501'/0'/0'`, `m/44'/501'/0'`, `m/44'/501'`}; each `publicKey` 32-byte hex, `address` non-empty ≤ 128 chars.
- TRON `index` set == 0..<100; each `path == "m/44'/195'/0'/0/<index>"`, `publicKey` 33-byte hex, `address` non-empty ≤ 128.

Then the sorted-key JSON must be ≤ 262,144 bytes ("Public wallet data is too large."). Stored: `jsonData` (sorted JSON), `walletKeyId` and `masterFingerprint` **lowercased**; `raw` is re-parsed from `jsonData` on every access; `evmAddress` = `evmDefaultAddress.address`.

`walletKeyId` = `sha256(UTF8("BitTool-Signer/WalletKeyId/v1") || masterCompressedPubKey(33) || masterChainCode(32))` (`engine/src/public-index.js:17`). For the public test phrase `abandon … about` (no passphrase): `walletKeyId = 99d5d3536665b81e0e99ccbd2219e090c6efe01f9966e9e9a7419c23d6d80f96`, master fingerprint `73c5da0a` (asserted in `engine/test/public-index.test.js:33`; recomputed independently for this spec). **Pitfall:** `engine/test/fixtures/evm-public-ui.json` still carries the pre-rename value `95f7c3fc…9a34` (domain `BitSigner/WalletKeyId/v1`); that field is unused — do not copy it as a vector.

---

## 11. `AppStore` — the central store

Source: `Models/AppStore.swift:1-602`. `@MainActor final class AppStore: ObservableObject`. "Wallet identity and page routes are explicit. This store has no active wallet, unlocked wallet, reusable signing authorization, or retained clear secret." (`:14-15`).

### 11.1 Published state (`:18-36`)

`wallets: [VaultMetadata]` (display names already merged), `isLoading` (initially true), `walletLoadSucceeded`, `publicWallets: [UUID: WalletPublicMetadata]`, `metadataStatuses: [UUID: PublicMetadataStatus]`, `duplicateWalletIDs: Set<UUID>`, `review`, `plan`, `matchingError`, `completedTaskIDs`, `signatureFrames`, `signatureText`, `signaturePayload`, `receipts`, `busy`, `securityEpoch`, `navigationResetEpoch`, `error` (shown by RootView as alert "Unable to Continue" with "OK", `Features/Main/RootView.swift:66-68`).

Derived: `hasSignedResponse`, `pendingTasks`, `hasIncompleteWallets = wallets.contains { publicWallets[$0.id] == nil }` (`:63-65`).

Init (`:67-80`): directory `Application Support/BitToolSigner`, receipts URL, `PublicWalletRepository(directory: …/PublicWallets)`; schedules `reload()` unless an explicit reload already started.

### 11.2 Epochs and suspension (secret lifetime)

- `securityEpoch` — rotated by `suspend()`. Every async secret path captures it and checks it after each await (`requireSession`, `:109-112`); a mismatch throws `CancellationError` (silently dropped by UI).
- `sensitiveEpoch` — rotated by `beginSensitiveSession()` / `endSensitiveSession()` (recovery views call these on appear/disappear) and by `suspend()`; `generateMnemonic`, `completeSetup` and `reveal` also require it unchanged (`:84-85, 160-168, 213-218, 474-480`).
- `requestEpoch` — request pipeline (other spec).
- `suspend()` (`:86-94`): new security/sensitive/request epochs; `signingOperation = nil`; `busy = false`; clear Pair prefetch; release the warm JS context; if a review exists with no plan and no matching error → `matchingError = "Matching interrupted. Try again."` **[untranslated on iOS]**.
- Triggers: scene enters background (`BitToolSignerApp.swift:41`), App Lock becomes locked (`Features/Main/RootView.swift:60`), screen capture starts (`RootView.swift:61`), and internally after `resetPassword` and `deleteWallet`.
- "There is deliberately no foreground idle timer or wallet-unlock lifetime." (`:83`).

### 11.3 `reload()` (`:123-148`)

1. `hasStartedReload = true`; `reloadEpoch = new` (later overlapping reloads win; stale ones return without publishing).
2. `originals = vault.list()` — failure: `walletLoadSucceeded = false`, `error = error.localizedDescription`, `isLoading = false`. RootView then shows the failure page: title "Wallets Unavailable", body "Your encrypted wallet files are unchanged. Unlock this device, then try again.", button "Try Again" (re-runs reload) (`Features/Main/RootView.swift:92-113`). "A failed wallet-list load is not treated as an empty list." (`docs/Security.md:52`).
3. `key = originals.isEmpty ? nil : try? vault.publicDataKey(createIfMissing: false)`.
4. For each vault (in list order): `snapshot = publicRepository.snapshot(id, key)`; displayed metadata = `{id, snapshot.displayName ?? vault header name, createdAt}`; index and status per id.
5. Publish `wallets`, `publicWallets`, `metadataStatuses`; reset and reschedule Pair prefetch; `walletLoadSucceeded = true`.
6. `duplicateWalletIDs` = every wallet whose ready index shares `walletKeyId` with another ready index.
7. If a plan exists, no signed response yet, and the wallet collection snapshot (ids + exact index JSON bytes) changed → `invalidateMatching()` ("Wallet information changed. Match this request again." **[translated]**).
8. `loadReceipts()`; `isLoading = false`.

### 11.4 `createWallet(name:mnemonic:passphrase:password:)` (`:177-211`)

1. Re-entrancy guard → "A wallet is already being saved."
2. Capture `securityEpoch`.
3. `passphrase.utf8.count <= 1024` else "The BIP39 passphrase exceeds 1,024 UTF-8 bytes."
4. `mnemonic = BIP39Mnemonic.validatedCanonical(mnemonic).phrase` — "The vault always stores the canonical words the seed is derived from." (exact spellings only; §13.7).
5. Engine `derivePublicIndex` with `{"seed": BIP39Seed.hex(mnemonic, passphrase)}` (fresh JS context); `requireSession`; `WalletPublicMetadata(raw)`.
6. `reload()`; `requireSession`; if `!walletLoadSucceeded` → "The existing wallet collection could not be read. Retry before adding a wallet."
7. If any ready index has the same `walletKeyId` → "This recovery phrase and passphrase already belong to an existing wallet." (wallets needing repair are not compared — duplicates can still arise and are flagged later).
8. `id = UUID()`; `key = vault.publicDataKey(createIfMissing: true)` (nil → `deviceKeyUnavailable`; no passcode → `devicePasscodeRequired`).
9. Write the public sidecar first: `publicRepository.save(index, id, displayName: trimmed name, key)` — "It is invisible without a committed vault. A crash cannot leave a visible new wallet with missing setup."
10. `requireSession`; `vault.create(name, mnemonic, passphrase, password, id)`; on any failure `try? publicRepository.remove(id)` and rethrow.
11. "Persistence is the success boundary": `reload()` and return the metadata, even if the app was backgrounded during the commit.

Test `interruptedCreationDoesNotLeaveAnEncryptedOrPublicWallet` (`BitToolSignerTests/SessionTests.swift:379-394`): suspend while `derivePublicIndex` runs → `CancellationError`, no vault, no wallet.

### 11.5 Repair: `completeSetup(walletID:password:)` (`:213-233`)

1. Wallet must be listed else `walletNotFound`.
2. Capture both epochs; `secret = vault.unlock(id, password)` (counted); `requireSession`; engine `derivePublicIndex {"seed": …}`; `requireSession`; sensitive epoch unchanged.
3. Validate the index. "Password authorization may repair an inconsistent public cache. The encrypted wallet, never the old cache, is the identity authority."
4. `publicDataKey(createIfMissing: true)`; `publicRepository.save(index, id, displayName: walletLabel(for: id), key)` — the name used is whatever is displayed *now* (for an invalid file that is the immutable vault header name, so a rename made before the file became invalid is lost; replicate).
5. `reload()`. "Existing duplicate encrypted records are preserved and reported; the resolver rejects an ambiguous identity instead of deleting user data."

Never decrypts other wallets, never regenerates keys (`docs/Security.md:48`). If the HMAC key was lost, every wallet becomes `invalid` and each needs its own repair.

### 11.6 `resetPassword(walletID:mnemonic:passphrase:newPassword:)` (`:493-512`)

1. `publicWallets[walletID]` must exist else "This wallet needs repair before its password can be reset."
2. `canonical = validatedCanonical(mnemonic).phrase`; engine `derivePublicIndex {"seed": BIP39Seed.hex(canonical, passphrase)}`; `requireSession`.
3. `derived.walletKeyId == existing.walletKeyId` else "This recovery phrase belongs to a different wallet." ("without that check, one wallet's phrase could overwrite another's stored secret").
4. `vault.resetPassword(...)` (§9.5); then `suspend()` ("The password that was authorizing this session is gone.") and `reload()`.

### 11.7 `changePassword` / `deleteWallet` / `renameWallet` / `reveal` / `existingWallet`

- `changePassword(walletID:current:new:)` (`:516-519`): `Task.checkCancellation()`; `vault.changePassword`. No suspend.
- `deleteWallet(walletID:password:)` (`:520-531`): `vault.delete` first — on error `reload()` and rethrow (sessions untouched, the sheet shows the error). On success: `suspend()`; `publicRepository.remove` (failure → `error = "The encrypted wallet was deleted, but its public cache could not be removed."` **[untranslated on iOS]**); `clearRequest()`; `navigationResetEpoch = UUID()`; `reload()`. Activity records are kept and show "Deleted wallet" **[untranslated on iOS]** (`Features/Activity/ActivityTab.swift:139`). When the wallet count reaches 0, App Lock is turned off (`Services/AppAccessController.swift:120-127`) and navigation returns to the Wallets tab/welcome.
- `renameWallet(walletID:name:)` (`:235-242`): wallet must exist (`walletNotFound`); `publicDataKey(createIfMissing: false)` nil → "Repair this wallet before renaming it."; `publicRepository.rename`; `reload()`. A rename never changes signer identity or invalidates a plan (`docs/Security.md:78`; test `SessionTests.swift:353-355`).
- `reveal(walletID:password:)` (`:474-480`): `vault.unlock`; `requireSession`; sensitive epoch unchanged; returns `VaultSecret` to the Show Recovery Phrase sheet only.
- `existingWallet(mnemonic:passphrase:) -> UUID?` (`:481-491`): validatedCanonical → derivePublicIndex(seed) → id of the ready wallet with that `walletKeyId`, else nil. "Public derivation only: nothing here reads a vault, asks for a password or returns a secret."

### 11.8 `generateMnemonic(wordCount:)` and `validateMnemonic` (`:160-175`)

- Capture both epochs; engine `mnemonic` with `{"entropy": SecureRandom.entropyHex(wordCount)}`; `requireSession`; sensitive epoch unchanged; read `result["mnemonic"]` (fallback `result["value"]`) as String else "Unable to generate recovery phrase."
- Engine side (`engine/src/keys.js:16`): `hexToBytes(entropy, 32)`, length must be 16/20/24/28/32 bytes (else engine error `ENTROPY` "Entropy must contain 128–256 bits in 32-bit steps."), returns `{mnemonic: entropyToMnemonic(bytes, englishWordlist), wordCount}`. **Generated phrases are always English.**
- UI offers only 24 (default) and 12 words (`Features/Setup/WalletSetupFlow.swift:145-148`, `WalletSetupModel.swift:14`); the API accepts 12/15/18/21/24.
- `validateMnemonic(phrase)` = `BIP39Mnemonic.validatedCanonical(phrase)` natively (no engine call).
- Vectors: entropy `"00"×16` (or 32 `"0"`s) → `abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about` (`BitToolSignerTests/SecurityTests.swift:137-140`, `BitToolSignerTests.swift:11-12`).

### 11.9 Identity helpers (`:149-159`)

- `walletLabel(for:)` = displayed name, or the literal `"Wallet"` for an unknown id.
- `avatarIdentity(for:)` = `walletKeyId` if the index is ready, else `id.uuidString` (uppercase). "never used for signing decisions". The colour therefore changes when a wallet goes from Repair to ready.
- `fingerprint(for:)` = `masterFingerprint.uppercased()` (e.g. `73C5DA0A`) or `"Setup required"` **[untranslated on iOS]**. Display only; signing never relies on it.

### 11.10 Public-index consumers (other specs own them; listed for the data contract)

| Operation | Parameters | Consumed |
|---|---|---|
| `derivePublicAddresses` (warm ctx) | `{publicIndex: metadata.raw, chain, profile ("native" default), start (0), count (20)}` | `addresses[]` → `PublicAccount` (`:243-248`) |
| `createPublicPairing` (warm) | `{publicIndex, chain, profile, outputMode ("accountXpub"), index (0)}` | `PairingPayload` (`:249-253`) |
| `createWalletPairing` (warm) | `{publicIndex, mode: "full"}` | `WalletPairingPayload` (`:305-309`) |
| `resolveSigningPlan` (fresh) | `wallets: [{localWalletId: UPPERCASE uuid, publicIndex}]` or `[{localWalletId, publicMetadataStatus: "needsCompletion"|"invalid"}]` (`:375-380`) | plan (other spec) |
| `signPlanTask` (fresh) | `{request, plan, taskId, seed: BIP39Seed.hex(...), riskAcknowledged, previousResult?}` after `vault.unlock` (`:463-472`) | signature result |

### 11.11 Activity journal persistence (`:532-601`; detail in the Activity spec)

- File `BitToolSigner/receipts.json`: `JSONEncoder` default encoding of `[SigningReceipt]` newest first (`Models/Models.swift:408-459`).
- Read limits: regular file ≤ 2,097,152 bytes; ≤ 100 records; `title` ≤ 200 chars; `summary` ≤ 200; `requestHash` 64 hex; every payload already normalized by `SigningReceipt.stored` (text ≤ 32,768 UTF-8 bytes non-empty; frames 1–2,048 with total ≤ 32,768 bytes). Violations → "Invalid activity record."; read failure sets `error = "The local activity record could not be read."` **[untranslated on iOS]** and shows an empty list.
- Append: prepend, keep 100, payloads only on the 25 most recent (`payloadRetention`), then strip payloads from the oldest until the encoding is ≤ the limit. "An unreadable journal is not overwritten with a new one-entry history" (append re-reads and fails). Write failure → `error = "The local activity record could not be saved. Any completed signature remains available."` **[untranslated on iOS]**.
- `clearHistory()` writes `[]`.

---

## 12. Wallet metadata and wallet-management UI

### 12.1 Name

- Rules: trimmed (whitespace and newlines), 1–40 grapheme clusters (`Core/WalletVault.swift:97-98`, `Services/PublicWalletRepository.swift:66-68`).
- Default in setup: "My Wallet" when there are no wallets, else "Wallet %lld" with `existingWallets + 1` (`Features/Setup/WalletSetupModel.swift:42-43`).
- Setup text fields truncate to 40 characters as you type (`String(value.prefix(40))`, `WalletSetupFlow.swift:140, 188`), autocapitalize words, label "Wallet name".
- Setup validation (`WalletSetupModel.swift:206-212`): empty → "Enter a wallet name."; > 40 → "Use a wallet name of 40 characters or fewer."; passphrase mismatch/empty → "The BIP39 passphrases must match and cannot be empty."
- Two names exist: the immutable vault header name (creation) and the public-file `displayName` (renames). Displayed = public `displayName` ?? header name (§11.3).

### 12.2 Identity colour (`Design/Logos.swift:190-220`)

```
b      = SHA256(UTF8(identity))           // identity = walletKeyId (lowercase hex) or UPPERCASE uuid
hue    = ((b[0] << 8) | b[1]) / 65535
shift  = 0.08 + b[2] / 255 * 0.14
c1     = HSB(hue, 0.62, 0.78)
c2     = HSB((hue + shift) mod 1, 0.70, 0.62)
fill   = linear gradient c1 (top-leading) → c2 (bottom-trailing)
shape  = continuous rounded rect, corner radius = size * 0.3
glyph  = first grapheme of the trimmed name, uppercased; "W" if empty
font   = size * 0.44, semibold, rounded design, white, shadow black 15 % radius 1 y 1
a11y   = hidden
```

Same in light and dark mode. Sizes: 50 (wallet card), 76 (detail header), 56 (identity header default), 44 (Show Recovery Phrase sheet).

Vectors (computed for this spec): identity `99d5d353…0f96` → `b[0..2] = 5c 8a 02`, hue 0.361486, shift 0.081098, second hue 0.442584. Identity `00112233-4455-6677-8899-AABBCCDDEEFF` → `c4 cf af`, hue 0.768795, shift 0.176078, second hue 0.944874. Android: `Color.hsv(hue * 360f, s, v)`.

### 12.3 Ordering and dates

- Wallet list order = `vault.list()` order: `createdAt` ascending, ties by UUID string (§9.1). No manual reordering, no "default" wallet ("every card is equal and self-contained", `Features/Wallets/WalletsTab.swift:67-68`).
- Detail header: "Added %@" with `createdAt.formatted(date: .abbreviated, time: .omitted)` (footnote, tertiary) (`Features/Wallets/WalletDetailView.swift:152-156`).

### 12.4 Wallet card (`Features/Wallets/WalletsTab.swift:69-141`)

- Row: avatar 50, name (headline, ≤ 2 lines), "Fingerprint %@" (caption monospaced), network logos only when `ready`; chevron; whole row navigates to detail (hint "Shows wallet details").
- Duplicate: Callout warning "Another wallet has the same key identity. Requests it matches cannot be signed."
- Ready: buttons "Pair" (qrcode) → wallet-level pairing, "Addresses" (list.bullet.rectangle) → addresses (bordered capsules; titles collapse to icons at accessibility sizes).
- Not ready: link "Repair wallet data" (wrench.and.screwdriver) → detail.
- Footer below cards: "Keys and signing stay on this device." (lock.shield).

### 12.5 Wallet detail (`Features/Wallets/WalletDetailView.swift:8-169`)

Section order:

1. Header: avatar 76, name (title2 bold, centered), tappable "Fingerprint %@" (copies the uppercase fingerprint to the clipboard — local-only, expires after 120 s, `Design/DesignSystem.swift:293-298` — and shows the copied toast; disabled without index; hint "Copies the master fingerprint"), "Added %@".
2. Duplicate (if flagged): Callout title "Duplicate wallet", body "Another saved wallet uses the same recovery phrase and passphrase. Both are kept. Requests that match both cannot be signed until you delete one copy."
3. If not ready: Callout title "Repair needed", body "This wallet's public data is missing or damaged. Your encrypted recovery phrase is safe. Enter the wallet password once to rebuild it." + button "Repair Wallet Data" (wrench.and.screwdriver). Else: Pair section and Receive Addresses section (other spec).
4. "Security": "Show Recovery Phrase" (key.viewfinder), "Change Password" (lock.rotation).
5. "Rename Wallet" (pencil).
6. "Delete Wallet" (trash, danger colour); footer "Deleting removes this wallet from BitTool Signer only. Funds stay on the blockchain and can be restored with the recovery phrase."

Navigation title = name (fallback "Wallet"). The page pops itself when its wallet disappears. After a password change: status toast "Password Changed" (VoiceOver announcement "Password changed" **[untranslated on iOS]**).

### 12.6 Rename (`WalletDetailView.swift:117-128, 162-168`)

Alert "Rename Wallet" with a text field "Wallet name" (prefilled, word capitalization), buttons "Cancel" / "Save" (Save disabled when trimmed empty), message "The fingerprint and addresses stay the same." Errors → alert "Unable to Rename" with the error message and "OK". No 40-character truncation in this field — over-long names fail with "Choose a wallet name of 1–40 characters." Android: a Material 3 dialog with an `OutlinedTextField` is fine; enforce the same validation.

### 12.7 Password-authorized sheets (`Features/Wallets/WalletSecuritySheets.swift`)

Common runner `WalletOperation` (`:7-29`): one operation at a time; errors are shown only if the attempt is current, the security epoch is unchanged and the error is not cancellation; `cancel()` resets busy and error.

**Show Recovery Phrase** (`:37-122`): title "Recovery Phrase"; identity header (44); password field "Wallet password" auto-focused after 350 ms, submit "Continue"; helper "The recovery phrase stays hidden until you choose to show it."; error Callout; button "Continue" (disabled when empty/busy). The typed password is cleared the moment it is submitted. On success shows the backup panel (words split on spaces, passphrase shown separately; concealment rules belong to the backup spec) and the warning "Anyone with these words can take your funds. Write them on paper and store them offline. Never type them into a website or share a photo." Toolbar "Cancel" (before) / "Done" (after) clears everything. Clears on security-epoch change (backgrounding) and on disappear; begins/ends a sensitive session on appear/disappear.

**Change Password** (`:124-191`): title "Change Password"; identity header; "Current wallet password"; "New password" and "Confirm new password" (`isNew`); footer = `PasswordRequirementsView(new, confirmation)`; error Callout; button "Change Password" enabled only when current non-empty, new valid, new == confirmation; footer "Your recovery phrase, fingerprint and addresses stay the same."; toolbar "Cancel". Success → clear, dismiss, toast on the detail page. On security-epoch change, fields clear unless busy.

**Delete Wallet** (`:193-297`): title "Delete Wallet"; identity header; danger Callout title "Delete this wallet?" body "This removes the encrypted wallet and its public data from this device. Funds are not affected, but you can only recover them with the recovery phrase and any BIP39 passphrase."; toggle "I confirm I want to delete this wallet"; footer "Deleting asks for %@, then for this wallet’s password." (when App Lock is on and biometrics are available; `%@` = biometry name) else "Deleting asks for this wallet’s password."; red capsule button "Delete Wallet" with white trash icon, enabled only when the toggle is on. Flow: optional biometric confirmation with reason "Confirm before deleting this wallet." (cancel aborts, any other failure continues to the password), then an alert "Wallet Password" with a secure field "Wallet password", buttons "Cancel" and "Delete" (destructive), message "Enter this wallet’s password to delete it. Without the recovery phrase this cannot be undone."; empty password → "Enter this wallet’s password." Success → dismiss (the detail page pops because the wallet is gone). On security-epoch change the password and alert clear unless busy.

**Repair Wallet** (`:299-345`): title "Repair Wallet"; identity header; "Wallet password" (submit on return); footer "BitTool Signer decrypts the wallet once to rebuild its public keys and addresses. Your recovery phrase and password do not change."; error Callout; button "Repair Wallet"; "Cancel". The password clears on suspend (unless busy) and on disappear (Revision 11 I-02).

### 12.8 Password step in setup, including reset mode (`Features/Setup/WalletSetupFlow.swift:346-400`, `WalletSetupModel.swift:148-188`)

- On appear: `checkForExistingWallet` (public derivation only; failures ignored). If the phrase + passphrase match a ready wallet, `duplicateOf` is set and the step switches to reset mode.
- Normal mode: title "Set a Password", message "The password encrypts this wallet on this device and authorizes every signature. It is not part of your recovery phrase.", fields "Wallet password" + "Confirm password" (`isNew`), requirements view, Callout "If you forget this password, import this recovery phrase again to set a new one.", button "Create Wallet" (create flow) / "Import Wallet" (import flow), navigation title "Password", step "Step %lld of %lld" = 4 of 4 (create) or 2 of 2 (import).
- Reset mode: title and navigation title "Reset Password", message "This recovery phrase already belongs to a wallet on this device. Set a new password for it: the phrase you just entered is the proof, so the old password is not needed.", identity header of the existing wallet, Callout "Only the password changes. The wallet keeps its name, addresses, paired wallets and history.", button "Reset Password".
- Button enabled when the password is valid and confirmed and not busy; back button hidden while busy.
- Completion: "Wallet Created" / "Wallet Imported" / "Password Reset"; text "Your keys are encrypted on this device. Next, pair a watch-only wallet to receive funds and prepare transactions." or (reset) "This wallet is encrypted with your new password. Use it to authorize signatures from now on."; buttons "Pair a Wallet" (not after reset) and "Done"; success haptic.

---

## 13. BIP39

Sources: `Models/BIP39Wordlist.swift:1-390`, `Core/BIP39Seed.swift:1-53`, `docs/Security.md:125`, `IMPLEMENTATION_STATUS.md` Revision 2.1.

### 13.1 Languages, files and hashes (`Models/BIP39Wordlist.swift:7-59`)

| Enum | File (`bip39-<raw>.txt`) | Display name | SHA-256 | Accent-insensitive |
|---|---|---|---|---|
| english | english | English | `2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda` | yes |
| chineseSimplified | chinese_simplified | 简体中文 | `5c5942792bd8340cb8b27cd592f1015edf56a8c5b26276ee18a482428e7c5726` | no |
| chineseTraditional | chinese_traditional | 繁體中文 | `417b26b3d8500a4ae3d59717d7011952db6fc2fb84b807f3f94ac734e89c1b5f` | no |
| japanese | japanese | 日本語 | `2eed0aef492291e061633d7ad8117f1a2b03eb80a29d0e4e3117ac2528d05ffd` | no |
| korean | korean | 한국어 | `9e95f86c167de88f450f0aaf89e87f6624a57f973c67b516e338e8e8b8897f60` | no |
| spanish | spanish | Español | `46846a5a0139d1e3cb77293e521c2865f7bcdb82c44e8d0a06a2cd0ecba48c0b` | yes |
| french | french | Français | `ebc3959ab7801a1df6bac4fa7d970652f1df76b683cd2f4003c941c63d517e59` | yes |
| italian | italian | Italiano | `d392c49fdb700a24cd1fceb237c1f65dcc128f6b34a8aacb58b59384b5c648c2` | yes |
| czech | czech | Čeština | `7e80e161c3e93d9554c2efb78d4e3cebf8fc727e9c52e03b83b94406bdcc95fc` | yes |
| portuguese | portuguese | Português | `2685e9c194c82ae67e10ba59d9ea5345a23dc093e92276fc5361f6667d79cd3f` | yes |

Facts measured on the bundled files: all are LF-terminated, no CR, 2,048 words each, already NFKD. Longest NFKD word in UTF-8 bytes: zh 3, cs/en/pt 8, it 9, es 10, fr 12, ja 27, ko 33 (24 × 33 + 23 = 815 bytes worst case).

Display names are language autonyms and are **not** localized.

### 13.2 Wordlist object (`:62-148`)

Loading (`:77-96`): read file → SHA-256 must equal the pinned hash → UTF-8 decode → `split("\n")` (empty pieces dropped) → per word compute `key` and `exactKey` → require 2,048 words, 2,048 distinct `key`s and 2,048 distinct `exactKey`s (this is the "Latin lists are unique without accents" invariant). Any failure → an *unavailable* list (`words` empty, `isAvailable == false`), which detection silently skips. Lists are loaded lazily and cached thread-safely (`BIP39` cache, `:151-168`); English is checked first "so the usual case never loads the other nine lists".

- `words`: index order, **NFC** (display and storage form).
- `exactKey(t) = lowercase(NFKD(t))`.
- `key(t, lang) = exactKey(t)` with every scalar of general category **Mn** removed, only for accent-insensitive languages (`:103-109`).
- `index(of:exact:)` → lookup by `exactKey` or by `key`; `contains` = index ≠ nil.
- `suggestions(for prefix, limit = 6)`: `k = key(prefix)`; empty or list unavailable → `[]`; else canonical NFC words whose key **hasPrefix** `k`, in list order, up to `limit` (`:119-128`). `isPrefix(t) = !suggestions(t, limit: 1).isEmpty`.
- `words(fromSeedQR:)` (`:134-147`): English list only; text trimmed (whitespace+newlines); length ∈ {48, 60, 72, 84, 96}; all ASCII decimal digits; each 4-digit group < 2048 → words. No checksum check here (it happens at import).

Swift semantic to preserve: `String.hasPrefix` compares by grapheme cluster. With NFKD keys, a typed Japanese "か" is not a prefix of a key starting "が" (が), and Korean "가" is not a prefix of "각" (L V T in one cluster). Android must reproduce this (§17.10) or accept documented divergence.

Tests: `list.words.first == "abandon"`, `last == "zoo"`; engine `wordlist` (DEBUG op) equals the native English list; `suggestions("aba") == ["abandon"]`; `suggestions("zz") == []`; `suggestions("ab", limit: 3) == ["abandon", "ability", "able"]` (`BitToolSignerTests/HardeningTests.swift:38-47`). First words: ja `あいこくしん`, ko `가격`, es `ábaco`, zh-Hans `的`; zh-Hant last `歇` (`BIP39LanguageTests.swift:23-34`). SeedQR: `"0000"×11 + "0003"` → the abandon/about phrase; 47 digits → nil; `"2048"×12` → nil; `"abandon " + digits` → nil (`HardeningTests.swift:49-57`).

### 13.3 Tokenizing typed or pasted text (`BIP39Mnemonic.tokens`, `:214-230`)

1. Split on: any `Character.isWhitespace` (Unicode White_Space, includes U+3000 ideographic space and U+00A0), `,` U+002C, `，` U+FF0C, `、` U+3001. Empty pieces are dropped.
2. Drop numbering: a piece whose NFKD form consists only of ASCII digits, `.` and `)` (e.g. "1.", "１)", "12") is ignored. "Only ASCII digits (after width folding): Chinese numerals such as 一 and 十 are real BIP39 words." (Revision 2.1 fixed a bug that dropped them.)
3. A piece of more than one character whose scalars are **all Han** (U+3400–4DBF, U+4E00–9FFF, U+F900–FAFF, U+20000–2FA1F, `:232-237`) is split into single-character tokens ("Chinese typed without spaces").
4. Otherwise the piece is one token (original spelling, not normalized).

Supported lengths: {12, 15, 18, 21, 24} (`:167`).

### 13.4 Detection and readings (`:239-302`)

`detectionOrder = [english, chineseSimplified, chineseTraditional, japanese, korean, spanish, french, italian, czech, portuguese]`.

`readings(tokens, exact)`: for each language in order (when `exact`, all languages; otherwise only accent-insensitive ones), skipping unavailable lists: map every token with `index(of:exact:)`; if any token fails, skip the language; otherwise `anyMapped = true`; if the BIP39 checksum of the indices is valid, add `Canonical(phrase: NFC words joined by " ", language)` unless a reading with the **same phrase** is already in this result (first language in detection order wins).

`interpretations(text)` (`:261-278`):

1. `tokens` empty → `.empty`; count not supported → `.unsupportedLength(count)`.
2. `results = readings(exact: true).valid`.
3. If `results` is empty **or** no token has typed accents → append every non-exact reading that is not already in `results` (equality here is phrase **and** language).
4. Non-empty → return (exact readings first).
5. If any language mapped every token (exact or not) → `.checksum`.
6. Else `best` = the language (detection order, first maximum wins on ties) containing the most tokens (accent-insensitive); throw `.unknownWords(tokens not in best)`.

`hasTypedAccents(tokens)` (`:304-309`): some token contains an alphabetic scalar below U+0250 **and** its NFD (canonical) decomposition contains an Mn scalar. "Typed accents are taken as deliberate and never matched away."

`canonicalize(text)` (`:246-250`): exactly one interpretation → it; otherwise `.ambiguous(options)`.

`validatedCanonical(phrase)` (`:282-287`): for phrases BitTool Signer produced or stored — tokens, length check, **exact** readings only, first valid in detection order, else `.checksum`. Used by `createWallet`, `resetPassword`, `existingWallet`, `validateMnemonic` and scanned phrases.

Checksum (`:312-329`): standard BIP39 — 11-bit indices → bits; `checksumBits = count / 3`; entropy = first `count*11 - count/3` bits; compare the first `checksumBits` bits of SHA-256(entropy). Entropy buffer and bit array are wiped after use.

Errors (`:179-208`):

| Error | Message |
|---|---|
| `.empty` | "Enter your recovery phrase." |
| `.unsupportedLength(1)` | "Invalid recovery phrase: it has 1 word. Recovery phrases have 12, 15, 18, 21 or 24 words." |
| `.unsupportedLength(n)` | "Invalid recovery phrase: it has %lld words. Recovery phrases have 12, 15, 18, 21 or 24 words." |
| `.unknownWords([w])` | "Invalid recovery phrase: %@ is not in the BIP39 word list." |
| `.unknownWords(ws)` | "Invalid recovery phrase: %@ are not in the BIP39 word list." |
| `.checksum` | "Invalid recovery phrase. Check the words and their order." |
| `.ambiguous` | "These words are a valid recovery phrase in more than one language. Choose the language of your written backup." |

`%@` for unknown words = the first 3 unknown tokens, each wrapped in `“` `”` (U+201C/U+201D), joined by `", "`. Singular is chosen by the total count of unknown words, not by how many are listed.

Ambiguity test vector (`BitToolSignerTests/BIP39LanguageTests.swift:97-112`): `nomina solido comodo nucleo pagina armonia nucleo liquido pagina logica nucleo nucleo` → readings `[italian, spanish]`, `canonicalize` throws `.ambiguous`; Italian seed `ff24afd428aa5040062bdaa2f41b8397b21ac04fef76fff7bc27e6ecf0ba349243005a33f5e1486d9e0ea54fafbba9ae4dc1c54779ab2f3ed1963f872b0e4a78`; Spanish seed `dc1bb7e8db68b6484ac3fe00d0f356a10f4e33deb6efed27cbff7df2daedded3e8d05053605876bdb87bdd8373d35e4cf6ada4a2a114d08d69b3597a5e2b01d0`. The accented Spanish `nómina sólido cómodo núcleo página armonía núcleo líquido página lógica núcleo núcleo` canonicalizes to Spanish only. `validatedCanonical` of each reading keeps its own language.

Other tests (`BIP39LanguageTests.swift:38-95`): fixture `BitToolSignerTests/BIP39LanguageVectors.json` (44 vectors: 4 per language + 4 extra Japanese `bip32JP`; 10 invalid-checksum phrases, one per language; source @scure/bip39 2.4.0) — each canonicalizes to its language (Chinese: either Chinese variant is accepted) and derives the expected seed; NFC input with U+3000 replaced by spaces gives the same canonical; for Latin languages the unaccented uppercase form and a numbered `"1. word, 2. word, …"` form give the same canonical; Chinese without spaces gives the same canonical; the canonical phrase is NFC; word count preserved. Invalid fixtures throw `.checksum`; `"  \n "` → `.empty`; 3 words → `.unsupportedLength(3)`; 11×abandon + `xyzzy` → `.unknownWords(["xyzzy"])`; 11×abandon + `ábaco` (mixed languages) throws.

### 13.5 Live draft analysis (`RecoveryPhraseDraft`, `:333-390`)

Inputs: the raw editor text. Fields: `words` (tokens), `language`, `unknownWords`, `partialWord`, `trailingWordIncomplete`.

1. `endsInWord` = last character exists and is not whitespace, `,`, `，`, `、`. `partialWord = endsInWord ? tokens.last : nil`.
2. No tokens → language nil, no unknown words, not incomplete.
3. A token is acceptable in a list if contained, or if it is the last token, `endsInWord`, and `list.isPrefix(token)`.
4. Pass 1: first language (detection order, available) where every token is acceptable using **exact** containment (prefix test is accent-insensitive) → chosen with no missing words.
5. Pass 2 (if none): per language, missing = tokens not acceptable with accent-insensitive containment; zero missing → choose and stop; else keep the language with strictly fewer missing (earliest wins ties).
6. No available list at all → language nil, all tokens unknown.
7. `language = missing.count < tokens.count ? chosen : nil`; `unknownWords = missing`; `trailingWordIncomplete = partialWord != nil && !chosen.contains(partialWord)`.
8. `hasSupportedLength`, `looksComplete = hasSupportedLength && unknownWords.isEmpty && !trailingWordIncomplete` ("The checksum is verified when the phrase is submitted.").

Tests: `"abandon abandon ab"` → partial "ab", no unknown; `"abandon xyzzy abandon "` → no partial, unknown `["xyzzy"]`; 11 words + `" ab"` → supported length but not complete; the full phrase (with or without trailing space) complete (`HardeningTests.swift:59-75`). `"abaco abdomen abe"` → Spanish, partial "abe", `suggestions("abaco") == ["ábaco"]`; `"あいこくしん　あいこ"` → Japanese, 2 words, trailing incomplete; `"的一是在"` → Chinese, 4 words (`BIP39LanguageTests.swift:181-203`).

### 13.6 Import UI pieces tied to BIP39 (`Features/Setup/RecoveryPhraseViews.swift`, `WalletSetupFlow.swift:167-243`)

Status line (`RecoveryPhraseStatus`, `:222-270`): left "Recovery phrase" (subheadline semibold); right: icon + text (footnote, 1 line, min scale 0.8; moves below at large text via `ViewThatFits`).

- Unknown words present: icon `exclamationmark.circle.fill` (danger), text "Unknown: %@" in danger colour, `%@` = first 2 unknown tokens quoted `“…”` joined by `", "`.
- Else icon `checkmark.circle.fill` (success) if `looksComplete`, otherwise `text.word.spacing` (secondary); text: 0 words → "12–24 words · any BIP39 language"; 1 → "1 word"; n → "%lld words"; then, if a language is detected and (`language != english` or `looksComplete`), append `" · " + displayName` (English is named only once complete).

Candidate words (`RecoveryPhraseAssistant`, `:273-312`), shown in the keyboard toolbar while the editor is focused, with a "Done" button that dismisses the keyboard:

- List = detected language or English; suggestions = `suggestions(partialWord, limit: 5)`; `completed = list.contains(partialWord)`.
- Chinese detected → text "Each Chinese word is one character".
- Else if no suggestions or (completed and exactly one suggestion): if not completed show "Type a word to see suggestions" (no partial word) or "No matching word"; if completed show nothing.
- Else horizontally scrolling chips (subheadline monospaced semibold, capsule, raised background), accessibility "Insert %@". Tap replaces the trailing run matching regex `[^\s,，、]+$` with `word + " "` (canonical accented form).

Editor (`RecoveryPhraseInput`, `:182-218`): placeholder "Enter your words separated by spaces", accessibility label "Recovery phrase", monospaced body, min height 132, no autocorrection/autocapitalization/content type, Writing Tools off, screenshot warning (backup spec).

Continue (`WalletSetupModel.startImport`, `:69-88`): if the text equals an unedited scanned phrase and `validatedCanonical` succeeds → accept with its exact language, no question. Else `interpretations`: one → accept; several → confirmation dialog "Which language is your backup in?", message "These words are valid in more than one language, and each language gives a different wallet. Choose the language your backup is written in.", one button per reading "%@: %@…" (`displayName`, first 3 words joined by spaces); errors shown as the step's error. Accept (`:90-98`): store canonical phrase and language, clear `duplicateOf`, go to the password step. Continue is disabled while the trimmed text is empty.

Scanning a recovery QR (`WalletSetupFlow.swift:217-229`): toolbar "Scan Recovery QR"; scanner title "Scan Recovery QR", hint "Scan a QR code containing the words (such as BitTool Signer’s recovery phrase QR code) or a Standard SeedQR."

1. `BIP39Wordlist.shared.words(fromSeedQR: text)` (English) → fill the editor with the words joined by spaces and mark as scanned.
2. Else if `interpretations(text)` returns at least one reading (ambiguous counts) → fill with the trimmed text, mark as scanned.
3. Else scanner error "This QR code does not contain a recovery phrase." **[untranslated on iOS]**.

Scanner-level rejections for this purpose (`Features/Sign/QRScannerView.swift:212-222`): non-text "This QR code doesn’t contain readable words. Compact SeedQR isn’t supported; use a QR code with the words or a Standard SeedQR, or type the words."; empty "This QR code is empty. Scan a QR code that contains your recovery phrase."; > 16,384 UTF-8 bytes "This QR code is too large to be a recovery phrase." (these are plain Swift `String`s, check the scanner spec for their localization state).

BitTool Signer's own phrase QR is the plain text of the canonical words joined by single spaces (`Features/Backup/RecoveryBackupViews.swift:99`), so it round-trips through path 2 with its exact spelling. Test `scannedPhrasesKeepTheirExactLanguageWithoutAsking` (`BIP39LanguageTests.swift:114-146`): scanned Italian → no question, language Italian, path `[password]`; editing the scanned text (appending a space) re-enables the question; typing it by hand asks.

### 13.7 Seed derivation (`BIP39Seed.hex`, `Core/BIP39Seed.swift:9-53`)

"BIP39 seed stretching (PBKDF2-HMAC-SHA512, 2048 rounds) in native code." The engine receives the 64-byte seed, never the phrase, for public-index derivation and signing.

```
phrase = join(" ", split_on_whitespace(lowercase(NFKD(mnemonic))))
guard phrase non-empty && utf8(phrase) <= 1024 && utf8(passphrase) <= 1024   // else invalidSecret
password = utf8(phrase)
salt     = utf8(NFKD("mnemonic" + passphrase))      // passphrase NOT lowercased/trimmed
seed     = PBKDF2-HMAC-SHA512(password, salt, 2048, 64)
return lowercase hex (128 chars); wipe password, salt, seed buffers (memset_s)
```

`maximumPhraseBytes = 1024` (`:17`): bounded so that real 24-word Japanese/Korean backups fit (audit BTS-04; test `everyValidPhraseLengthDerivesASeedInEveryWordlist`, `BIP39LanguageTests.swift:152-168`, derives a seed from 12–24 repetitions of the longest word of every list). `> 1024` bytes, empty, or passphrase > 1024 bytes → `invalidSecret` (`:172-179`). CommonCrypto failure → `keyDerivationFailure`.

Vectors (`HardeningTests.swift:13-25`, re-verified with Python): `abandon×11 about` + `TREZOR` → `c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04`; `legal winner thank year wave sausage worth useful legal winner thank yellow` + `TREZOR` → `2e8905819b8723fe2c1d161860e5ee1830318dbf49a83bd451cfb8440c28bd6fa457fe1296106559a3c80937a1c1069be3a3a5bd381ee6260e8d9739fce1f607`; `abandon×11 about` + `""` → `5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4`. Whitespace/case-mangled input gives the same seed; passphrase `"é"` (U+00E9) equals `"é"`; `"   "` throws. Engine parity: `derivePublicIndex {mnemonic, passphrase}` and `{seed}` give identical index JSON for passphrases `""` and `"Public passphrase ✓"` (`HardeningTests.swift:27-34`).

Engine contract (`engine/src/keys.js:26-31`): `seed` must match `/^[0-9a-f]{128}$/i`, and `mnemonic`/`passphrase` must be absent when `seed` is given ("Provide either a seed or a recovery phrase, not both.").

---

## 14. Secret lifetime and zeroization

What iOS wipes (`memset_s` / `resetBytes`):

- `BIP39Seed`: phrase bytes, salt bytes, seed bytes (`Core/BIP39Seed.swift:34, 50-52`).
- `deriveKey`: password bytes and derived bytes (`Core/VaultCrypto.swift:132-135`).
- `encrypt` / `decrypt`: cleartext JSON buffer (`:162, 188`).
- Device keys and the public-data key copies after each use (`Core/WalletVault.swift:103-104, 125, 130, 138, 160, 182`).
- BIP39 checksum entropy and bit arrays (`Models/BIP39Wordlist.swift:319-322`).

What is **not** wiped, and the docs say so (`docs/Security.md:72`, `Core/VaultCrypto.swift:71-72`, `Models/AppStore.swift:460-462`): Swift `String`s (mnemonic, passphrase, password, seed hex), `VaultSecret`, CryptoKit `SymmetricKey` copies, JS strings and engine internals. "A fresh context reduces the lifetime of secrets but is not a proof of memory erasure."

Clearing rules (UI side):

- Every password field's text is copied into a local and cleared the moment its action starts (`WalletSecuritySheets.swift:109-110, 287-288, 338-339`).
- Security-epoch change (background, App Lock, capture) clears: Show Recovery Phrase password and decrypted secret; Change Password fields (unless busy); Delete password and alert (unless busy); Repair password (unless busy); setup passwords, confirmation, error and backup-check placements (`WalletSetupModel.suspend`, `:192-196`). The setup phrase draft, passphrase and wallet name are kept so the person can continue (`docs/Security.md:113`).
- Closing setup clears all draft secrets (`WalletSetupModel.clearSecrets`, `:198-204`; on disappear, `WalletSetupFlow.swift:55`). A discard confirmation "Discard this wallet?" with "Discard" / "Keep Editing" and message "The recovery phrase shown here will be erased. If you already wrote it down, it will not be used." (create) or "The words you entered will be erased from this screen." (import) protects drafts (`WalletSetupFlow.swift:46-53`).
- Each signing task needs its own password; nothing is cached for another operation.

---

## 15. Engine calls in this area

| Operation | Context | Parameters sent | Result fields consumed |
|---|---|---|---|
| `mnemonic` | fresh | `{"entropy": "<32–64 lowercase hex>"}` | `mnemonic` (String), fallback `value`; `wordCount` ignored |
| `derivePublicIndex` | fresh | `{"seed": "<128 lowercase hex>"}` | whole object → `WalletPublicMetadata` (`version`, `walletKeyId`, `masterFingerprint`, `bitcoinPublicNodes`, `evmAccountNode`, `tronAccountNode`, `tronAddressIndex`, `solanaPublicKeys`, `evmDefaultAddress`, …) stored verbatim (sorted) as `body.publicIndex` |
| `signPlanTask` | fresh | includes `"seed"` from `BIP39Seed.hex` after `vault.unlock` | other spec |

Every request object gets `"operation": <name>` and is serialized with sorted keys, ≤ 1,048,576 bytes (`Core/SigningEngine.swift:184-195`); production allowlist `Core/SigningEngine.swift:118-122` (`mnemonic` is in it; `validateMnemonic`, `wordlist`, `deriveAccounts` are DEBUG-only and must not be used by production code).

---

## 16. Invariants checklist (port these as tests)

1. PBKDF2-SHA256 vector (§6.4) and BIP39 seed vectors (§13.7).
2. Vault round trip; fresh salt and ciphertext per encryption; serialized vault contains no phrase/password/passphrase.
3. Wrong password, wrong device key, flipped ciphertext byte, edited header, re-encoded header → `authenticationFailed`; `iterations` out of range → `malformedVault`; `format != 2` → refused.
4. Throttle survives a new vault instance; reset clears it; delay table §8.
5. Two wallets, password rotation, wrong-password delete keeps the wallet, correct delete removes vault + attempts + key.
6. Reset keeps id/name/createdAt, still one wallet, old password fails; weak password / unknown wallet refused without change.
7. Public file: tampered body, other key, nil key → `invalid`; unknown id → `needsCompletion`; MAC vector §10.3.
8. Corrupt public file never alters the vault file; Repair restores `ready`; the vault file is byte-identical after Repair (`SessionTests.swift:14-24`).
9. Rename changes the label but not the vault bytes, fingerprint, `walletKeyId` or plan hash; duplicate import refused with "already belong"; a different passphrase gives a different fingerprint (`SessionTests.swift:90-118`).
10. Incomplete wallet + import of the same phrase → after repair both are flagged duplicate, plan invalidated, signing refused (`SessionTests.swift:120-140`).
11. Suspension during `derivePublicIndex` leaves no vault and no public file.
12. Password policy vectors §4; strength levels §5.2.
13. All 10 wordlists load with pinned hashes; BIP39LanguageVectors.json (44 + 10); ambiguity vector; scanned-phrase shortcut; draft vectors; SeedQR vectors; worst-case phrase lengths.
14. `mnemonic` engine op with zero entropy → abandon…about.

---

## 17. Android mapping

### 17.1 Modules (follow Ownbit 5.0's build-logic convention plugins and `core/*` + `feature/*` layout)

| Module | Contents |
|---|---|
| `:core:crypto` (pure Kotlin/JVM where possible) | `SecureRandoms`, `Pbkdf2` (SHA-256 and SHA-512), software `AesGcm` (combined format), `Secrets` (wipe helpers, `constantTimeEquals` — reuse Ownbit's `io.ownbit.core.crypto.util.Secrets` / `SecureRandoms` patterns), `PasswordPolicy`, `Graphemes`, BIP39 (`Bip39Language`, `Bip39Wordlist`, `Bip39Mnemonic`, `RecoveryPhraseDraft`, `Bip39Seed`, `SeedQr`). Word lists as Java resources (`src/main/resources/bip39/bip39-<lang>.txt`, byte-identical copies) so JVM unit tests can load them. |
| `:core:vault` (new; Android library) | `DeviceKeyStore` interface + `AndroidKeystoreDeviceKeyStore` + `InMemoryDeviceKeyStore` (tests), `VaultCodec` (container/header JSON), `VaultCrypto`, `WalletVault`, `AttemptsStore`, `PublicWalletRepository`, `AtomicFiles`, Hilt qualifiers `@VaultDir`, `@PublicWalletsDir`, `@SignerDataDir`, injectable `Clock`. |
| `:core:model` | `VaultMetadata`, `VaultSecret` (with `wipe()` where possible), `VaultError` (sealed class with the §6.7 cases), `PublicMetadataStatus` (`ready`, `needsCompletion`, `invalid` — keep raw values for the engine), `WalletPublicMetadata`, `AppIssue`. |
| `:core:data` | `WalletStore` = port of `AppStore` (singleton, `StateFlow`s, epochs), `ReceiptStore`. |
| `:core:designsystem` | `WalletAvatar`, `PasswordField`, `PasswordRequirements`, `WalletIdentityHeader`. |
| `:feature:wallets`, `:feature:setup` | screens in §12. |

Both product flavors (`apk`, `play` → `apkRelease`, `playRelease`) share this code unchanged; nothing in vault/crypto is flavor-specific. If both flavors keep the same `applicationId` (as in Ownbit), switching distribution requires a reinstall, which deletes vault files **and** Keystore keys: users restore from their phrase. Say so in release notes.

### 17.2 Concurrency

- `WalletVault`: one instance (`@Singleton`), every public method `suspend` and wrapped in a single `Mutex` (`kotlinx.coroutines.sync.Mutex`) on `Dispatchers.Default` (KDF) / `Dispatchers.IO` (files). This reproduces actor serialization (an unlock blocks a concurrent list; acceptable, iOS does the same).
- `PublicWalletRepository`: its own `Mutex`.
- `WalletStore`: main-safe; publishes `StateFlow`s; epochs as `UUID` fields compared after each suspension point (`requireSession`), throwing `CancellationException` → UI ignores it, exactly like `WalletOperation`. Do not rely on coroutine cancellation alone: the epoch check is what discards late results.
- Suspension triggers on Android: `ProcessLifecycleOwner` `ON_STOP` (background), App Lock locking, screen recording start (`Activity.registerScreenCaptureCallback` is screenshot-only; for recording use `WindowManager.addScreenRecordingCallback` on API 35+). The lifecycle spec owns details; the store just exposes `suspend()`.

### 17.3 Per-wallet device key → Android Keystore AES-256-GCM

iOS keeps raw key bytes in the Keychain and does AES in CryptoKit; Android must keep the key **non-exportable inside Keystore** and run the outer layer through it.

- Alias: `com.bittool.signer.vault.device-key.v1/<lowercase-uuid>` (mirrors service/account).
- `KeyGenParameterSpec.Builder(alias, PURPOSE_ENCRYPT or PURPOSE_DECRYPT)` with `setKeySize(256)`, `setBlockModes(BLOCK_MODE_GCM)`, `setEncryptionPaddings(ENCRYPTION_PADDING_NONE)`, `setRandomizedEncryptionRequired(true)` (default — Keystore generates the 12-byte IV; read it with `cipher.iv`), `setUnlockedDeviceRequired(true)` (API 28+), `setIsStrongBoxBacked(true)` when `PackageManager.FEATURE_STRONGBOX_KEYSTORE` is present, retrying without StrongBox on `StrongBoxUnavailableException` (same pattern as Ownbit `AndroidKeystoreDeviceKeyManager`). **No** `setUserAuthenticationRequired` (iOS needs no prompt per use).
- Precondition replacing `LAContext.canEvaluatePolicy(.deviceOwnerAuthentication)`: `KeyguardManager.isDeviceSecure()`; false → `devicePasscodeRequired`.
- Combined layout parity: write `iv(12) || doFinal output (ciphertext || tag(16))`; read with `GCMParameterSpec(128, iv)` and `updateAAD(aad("device"))`.
- Error mapping: alias absent → `deviceKeyUnavailable`; `KeyPermanentlyInvalidatedException`, `UnrecoverableKeyException`, `KeyStoreException`, `ProviderException` while loading → `deviceKeyUnavailable`; `AEADBadTagException` → `authenticationFailed`.
- Creation order must change: iOS encrypts with raw bytes *then* adds the Keychain item; on Android the key must exist before the outer encryption. Do: policy/bounds check → PBKDF2 + inner layer (software) → generate the Keystore key (fail if the alias already exists) → outer layer → atomic write → on any failure after generation, delete the alias. Never leave a key without a file or a file without a key.
- **Pitfall: `KeyGenerator.generateKey()` silently replaces an existing alias.** Always check `containsAlias` first under the vault mutex. On API 26–30 a failed binder call can make `getKey`/`containsAlias` report a live key as missing (documented in Ownbit's `RecordCipher.kt`); treat "missing" as fatal (`deviceKeyUnavailable`) for existing wallets, never as a reason to create.
- Deletion: `KeyStore.deleteEntry(alias)`; absent alias = success; other failure → `deviceKeyUnavailable` (after the file is removed, like iOS).

Decision for the lead — passcode removal. iOS's `WhenPasscodeSetThisDeviceOnly` item is destroyed when the passcode is removed (wallets become "device key unavailable", restore from phrase). Android options:
1. *Recommended:* the configuration above plus a runtime gate: refuse every secret operation and wallet creation while `!isDeviceSecure()` with the passcode-required error. Keys survive if the user later re-enables a lock screen. Simple, no OEM-specific auth-bound key bugs.
2. *Strict parity:* add `setUserAuthenticationRequired(true)` + `setUserAuthenticationParameters(timeout, AUTH_DEVICE_CREDENTIAL or AUTH_BIOMETRIC_STRONG)` so keys are permanently invalidated when the lock screen is removed. Cost: `UserNotAuthenticatedException` after the timeout since the last device unlock (needs a device-credential prompt iOS never shows), Smart Lock/trust-agent edge cases, more OEM variance. Not recommended for v1.

### 17.4 Public-data HMAC key → Keystore HMAC-SHA256

- Alias `com.bittool.signer.vault.device-key.v1/0b175160-9a7e-4c1d-8000-00000000c0de`; `KeyGenerator.getInstance(KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")`, `PURPOSE_SIGN or PURPOSE_VERIFY`, 256-bit, `setUnlockedDeviceRequired(true)` (API 28+). Prefer TEE over StrongBox: this key MACs every wallet's body (tens of KB, up to 300 KB) on every reload, and StrongBox throughput is low. Measure; use StrongBox only if reload stays well under 100 ms per wallet.
- `publicDataKey(createIfMissing = false)` returns null only when the alias is definitely absent; on Keystore errors return null as iOS does (`try?`), which makes all wallets show Repair — and Repair (`createIfMissing = true`) must then **not** regenerate over an existing alias (see pitfall above), otherwise every other wallet's MAC breaks.
- Verification: compute the MAC with `Mac.getInstance("HmacSHA256")` initialised with the Keystore key and compare with `MessageDigest.isEqual` (constant time). Accept the stored hex in either case; require exactly 64 chars.

### 17.5 Attempts file

Same semantics and AAD (`com.bittool.signer.auth.v1/<UPPERCASE-UUID>`), sealed with the wallet's Keystore key. Suggested plaintext: `{"count":<0..16>,"retryAfter":<epoch ms>}` (`retryAfter = 0` for "distant past"). Inject a `Clock` (wall clock, `System.currentTimeMillis()`) for tests. Keep "persist before PBKDF2" and "damaged file = `malformedVault`".

### 17.6 PBKDF2-HMAC-SHA256 at 600,000 iterations — performance risk

- Must stay 600,000 on write and accept 600,000–2,000,000 on read.
- Cost per operation: create 1×, unlock/sign/show/delete/repair/App-Lock fallback 1×, **change password 2×**, reset 1×.
- Reference timings measured for this spec on an Apple-silicon Mac: Python/OpenSSL (native) 92 ms; JVM 11 `SecretKeyFactory("PBKDF2WithHmacSHA256")` ≈ 670 ms; JVM `Mac` loop ≈ 665–760 ms. ART on a mid/low-end phone will be slower than desktop HotSpot; expect seconds for a pure-Java implementation (estimate — benchmark it).
- `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")` exists from API 26 and is served by the platform Bouncy Castle provider (Java code), not Conscrypt — check `provider.name` on target devices. It takes a `char[]` and encodes it itself; avoid it so the exact UTF-8 bytes iOS uses are what you feed in.
- Candidates, in order of preference after benchmarking on the slowest supported device:
  1. NDK implementation (C, SHA-256 with ARMv8 crypto extensions and a portable fallback, HMAC with precomputed ipad/opad states = 2 compressions per iteration), exposed through a tiny JNI function taking `ByteArray`s and wiping native buffers. Expected ~0.1 s.
  2. Kotlin loop over Conscrypt's native `Mac("HmacSHA256")`, reusing one `Mac` and output buffers (`doFinal(out, 0)`); one JNI hop per iteration.
  3. Bouncy Castle `PKCS5S2ParametersGenerator(SHA256Digest())` (pure Java) as a reference implementation in tests.
- Gate on the §6.4 vector in unit and instrumented tests; add a Macrobenchmark/instrumented timing test that logs the duration on CI devices. Target: ≤ 1 s on the minimum-spec device.
- Always off the main thread; the UI shows its busy state (button spinner) and blocks double submission (the `WalletOperation` rules). A cooperative `ensureActive()` every ~10,000 iterations is fine — an aborted unlock has already counted its attempt, matching iOS.
- BIP39 seed (PBKDF2-HMAC-SHA512, 2,048 iterations) is cheap; implement it with `Mac("HmacSHA512")` over the exact UTF-8 bytes, wiping buffers.

### 17.7 Software AES-GCM (password layer and attempts file if not Keystore)

`Cipher.getInstance("AES/GCM/NoPadding")` (Conscrypt), `SecretKeySpec(derived32, "AES")`, fresh 12-byte nonce from `SecureRandom`, `GCMParameterSpec(128, nonce)`, `updateAAD(aad("password"))`, combined = `nonce || ct || tag`. New `Cipher` per operation. `SecretKeySpec` clones the key and cannot be destroyed (same limitation as iOS's `SymmetricKey`); wipe your own `ByteArray`.

### 17.8 Files, backup and protection

- Base directory: `context.noBackupFilesDir/BitToolSigner/` with `Vaults/`, `PublicWallets/`, `receipts.json`, same file names as iOS (`<lowercase-uuid>.bsvault`, `.<lowercase-uuid>.attempts`, `<lowercase-uuid>.json`).
- Manifest: `android:allowBackup="false"`, `android:fullBackupContent` excluding every domain (API ≤ 30), `android:dataExtractionRules` excluding every domain for both `<cloud-backup>` and `<device-transfer>` (API 31+: `allowBackup=false` alone does **not** stop device-to-device transfer). Copy Ownbit's `data_extraction_rules.xml` / `full_backup_content.xml`. Leave `android:hasFragileUserData` false. Also exclude preferences (DataStore) — a difference from iOS, where preferences enter device backups; harmless.
- No `INTERNET` permission (project decision).
- iOS `FileProtectionType.complete` has no per-file Android equivalent; files live in credential-encrypted storage (available after first unlock). The real at-rest protection is the Keystore layer: with `setUnlockedDeviceRequired(true)` neither the vault nor the public files can be opened while the device is locked. Do not make the app `directBootAware`.
- Permissions: app-private dirs are already per-UID; optionally `Os.chmod(dir, 0700)` / `Os.chmod(file, 0600)` for parity.
- Atomic write helper (replaces `writeProtected`): `mkdirs`; temp = `File(dir, ".pending-" + UUID)`; write with `FileOutputStream`, `fd.sync()`, close; `Os.rename(temp, target)` (atomic `rename(2)`, same filesystem) or `Files.move(…, ATOMIC_MOVE, REPLACE_EXISTING)`; best-effort `fsync` of the directory via `Os.open(dir, O_RDONLY)` + `Os.fsync`; `finally` delete the temp if it still exists. On startup delete stale `.pending-*` files (iOS leaves them after a crash; they are harmless but tidy). Avoid `androidx.core.util.AtomicFile` here: its sibling backup files need special read paths.
- Read guards: `Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)` → `isRegularFile` and `size()` bounds **before** reading (§2.3).

### 17.9 Zeroization on Android

- Use `ByteArray`/`CharArray` for passwords, phrase bytes, salts, derived keys, seeds and cleartext; wipe in `finally` (Ownbit `Secrets.wipe`, `useAndWipe`).
- Compose text fields hold `String`s/`TextFieldState` that cannot be wiped; clear the state on submit and on suspend, never log them, keep them out of `SavedStateHandle`/`rememberSaveable` (process-death restoration would persist secrets to disk — iOS discards the draft when the process ends, `docs/Security.md:113`).
- The engine needs the seed as a hex `String` inside JSON; same limitation as iOS. Keep the documentation's honest disclaimer in the Android security doc.
- Turn off autofill for these fields (`importantForAutofill="no"` / Compose semantics `contentType` none) and, for the phrase editor, request `IME_FLAG_NO_PERSONALIZED_LEARNING` (needs a platform `EditText` or `PlatformImeOptions`); Android cannot refuse third-party keyboards the way `AppDelegate` does (`BitToolSignerApp.swift:52`) — the input spec owns this.

### 17.10 Unicode parity pitfalls (Swift vs Kotlin)

1. `String.count` (Swift) is grapheme clusters; use `android.icu.text.BreakIterator.getCharacterInstance()` (API 24+, current emoji rules) for password length 8–64, strength thresholds 12/16, name ≤ 40, the 40-char truncation, the avatar initial, Chinese per-character splitting and `.count > 1`.
2. Iterate **code points** (`codePoints()`), not `Char`s, for category checks, Han ranges (supplementary plane) and Mn removal.
3. Categories: Lu/Lt → `Character.UPPERCASE_LETTER`/`TITLECASE_LETTER`; Ll → `LOWERCASE_LETTER`; Nd → `DECIMAL_DIGIT_NUMBER`; symbols → `MATH_SYMBOL`, `CURRENCY_SYMBOL`, `MODIFIER_SYMBOL`, `OTHER_SYMBOL`; punctuation → `CONNECTOR_PUNCTUATION`, `DASH_PUNCTUATION`, `START_PUNCTUATION`, `END_PUNCTUATION`, `INITIAL_QUOTE_PUNCTUATION`, `FINAL_QUOTE_PUNCTUATION`, `OTHER_PUNCTUATION`; control → `CONTROL`, `FORMAT`; Mn → `NON_SPACING_MARK`; alphabetic → `Character.isAlphabetic(cp)`.
4. Whitespace: Swift `Character.isWhitespace` = Unicode White_Space (U+0009–000D, 0020, 0085, 00A0, 1680, 2000–200A, 2028, 2029, 202F, 205F, 3000). Kotlin `Char.isWhitespace()` differs (adds U+001C–001F, misses U+0085). Use an explicit set. `trimmingCharacters(in: .whitespacesAndNewlines)` = Z* categories + U+000A–000D + U+0085.
5. Lowercasing: always `lowercase()` (Locale.ROOT) — never `toLowerCase()` with the default locale (Turkish is one of the 21 UI languages: "I" → "ı" would break word lookup).
6. Normalization: `java.text.Normalizer.normalize(s, Normalizer.Form.NFKD | NFD | NFC)`.
7. Swift `==` and dictionary keys use canonical equivalence; Kotlin compares code units. Normalize before comparing (all lookup keys are already NFKD, which is enough).
8. `hasPrefix` is grapheme-aware in Swift: implement `startsWith(key) && (candidate.length == key.length || isGraphemeBoundary(candidate, key.length))` to reproduce the Japanese/Korean prefix behaviour of §13.2 (add a test for "か" vs "が…" and "가" vs "각…").
9. Regex `\s` in the chip-insertion pattern `[^\s,，、]+$` is Unicode-aware on iOS (ICU); in Java add `(?U)` / `Pattern.UNICODE_CHARACTER_CLASS` so U+3000 separates words.
10. UTF-8 byte limits: `toByteArray(Charsets.UTF_8).size`. Reject strings with unpaired surrogates for secrets (Swift strings cannot contain them).
11. Number formatting in messages (`%lld`) → Android `%d` in resources; keep the English key text identical for the translation import, map `%@` → `%s`, `%lld` → `%d`, keep positional forms from the translations (`%1$lld`, `%2$@` …).

### 17.11 JSON

- Use `kotlinx.serialization.json` `JsonObject` trees; build objects with **sorted keys** where iOS sorts (vault container/header, secret, public envelope and body, `WalletPublicMetadata.jsonData`, engine requests). Store and MAC the exact bytes produced; never re-encode bytes that are authenticated.
- Integer checks (`version == 1`, `depth`, `index`, `schemaVersion == 2`, `format == 2`) must reject non-integers.

### 17.12 Strings that need Android-specific wording (new resources, not in `translations/*.json`)

- `devicePasscodeRequired`: iOS "Set an iPhone passcode before creating a wallet. The device key requires passcode protection." → e.g. "Set a screen lock (PIN, pattern or password) before creating a wallet. The device key requires screen lock protection." Needs translation into the 20 other languages.
- `PRIVACY_POLICY.txt` mentions the iOS Keychain, iOS file protection and iCloud (`PRIVACY_POLICY.txt:14, 20, 40`); Android needs an adapted text (Android Keystore; keys deleted with the app; excluded from Android backup and device transfer). Legal owns the wording.
- Biometric wording in the delete footer uses the device's biometric name; on Android use a generic "biometrics"/"fingerprint or face" string (App Lock spec).
- Also create resources for the iOS-untranslated literals listed in this spec ("Matching interrupted. Try again.", "Setup required", "Wallet", "Deleted wallet", "Password changed", "This QR code does not contain a recovery phrase.", "The encrypted wallet was deleted, but its public cache could not be removed.", "The local activity record could not be read.", "The local activity record could not be saved. Any completed signature remains available.").

### 17.13 Compose UI notes

- Strength meter: four `Box`es with `RoundedCornerShape(50)`, height 4.dp, gap 4.dp, `animateColorAsState(tween(200))`; colours from the design system's success/warning/yellow tokens with light/dark variants; requirement rows with `Icons.Rounded.CheckCircle` / `RadioButtonUnchecked` and `Modifier.semantics(mergeDescendants = true) { stateDescription = "Met"/"Not met" }`.
- Sheets → `ModalBottomSheet` (or full-screen dialogs for Delete/Change Password on small screens) with `sheetState` non-dismissable while busy (`confirmValueChange = { !busy }`), matching `interactiveDismissDisabled`.
- Delete password prompt → `AlertDialog` with a password `OutlinedTextField` (`KeyboardType.Password`, `PasswordVisualTransformation`).
- Haptics: success haptic on setup completion (`HapticFeedbackType.Confirm` on API 30+, or `LongPress` fallback).
- Avatar: `Brush.linearGradient(listOf(c1, c2), start = Offset.Zero, end = Offset.Infinite)` in a `RoundedCornerShape(size * 0.3f)`; `clearAndSetSemantics {}`.
- Status/copy toasts → `SnackbarHost` with `LiveRegionMode.Polite` announcements.
- Secret screens use `FLAG_SECURE` (privacy spec).

### 17.14 Testing on Android

- JVM unit tests: port `SecurityTests`, the storage parts of `HardeningTests`, `BIP39LanguageTests` (copy `BIP39LanguageVectors.json` into `src/test/resources`), the store parts of `SessionTests` with an in-memory `DeviceKeyStore` (the iOS tests use `MemoryDeviceKeys`/`SessionDeviceKeys`, `SecurityTests.swift:221-243`).
- Instrumented tests: Keystore key generation with and without StrongBox, alias-collision guard, `unlockedDeviceRequired`, `isDeviceSecure` gating, PBKDF2 timing, atomic replacement under process kill (write → kill → relaunch → old or new vault, never neither), backup exclusion (`adb shell bmgr backupnow` produces no app data), device transfer rules.
- Real-device matrix (iOS "Still requiring external acceptance" equivalents): lock-screen removal and re-enable, reinstall, low-end device KDF timing, API 28–30 Keystore flakiness, Samsung/Xiaomi/Pixel StrongBox.

---

## 18. Out of scope (iOS-only, do not port)

- `LAContext` / Face ID / Touch ID / Optic ID wording and `NSFaceIDUsageDescription`; the passcode check through `LAContext.canEvaluatePolicy` (replaced by `KeyguardManager.isDeviceSecure`).
- Keychain specifics: `kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly`, `kSecAttrSynchronizable`, Keychain survival after app deletion.
- `FileProtectionType.complete`, `isExcludedFromBackup`, iCloud/iTunes backup behaviour (replaced by §17.8).
- `memset_s`, CommonCrypto, CryptoKit, `SecRandomCopyBytes` (replaced by Kotlin/JCA/NDK equivalents with the same parameters).
- `UITextField` `passwordRules` / `textContentType` AutoFill tricks, `UIToolbar` keyboard accessory, Writing Tools (`writingToolsBehavior`), `AppDelegate` third-party-keyboard refusal.
- `UIPasteboard` `localOnly` (Handoff) — use `ClipData` with `EXTRA_IS_SENSITIVE` where relevant and a 120 s clear (clipboard spec).
- `SimulatorUITestContext` and the DEBUG + Simulator UI-test harness; DEBUG-only engine operations (`validateMnemonic`, `wordlist`, `deriveAccounts`, …) in production code.
- Liquid Glass, iOS 17/18 fallbacks, iPad readable-width specifics, `PrivacyInfo.xcprivacy`, App Store / TestFlight items.
- iOS `UserDefaults` keys as such (Android uses DataStore; names may be kept for clarity).
