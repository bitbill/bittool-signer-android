# Port gaps: completeness review of `docs/port/*` (critic pass, 2026-10-20)

Inputs reviewed: the 11 specs in this folder plus `DECISIONS.md` (working-tree version of 2026-10-20 22:55, which
already supersedes parts of `engine-contract.md`, see G7.1), every Swift file and resource of
`/Users/tanguopeng/Documents/bitsigner-ios/BitSigner/BitToolSigner`, the iOS test targets, `README.md`,
`IMPLEMENTATION_STATUS.md` (Revisions 11 down to 2.1, test results, "Still requiring external acceptance"),
`docs/Security.md`, `translations/*.json`, and the Android scaffold's `tools/l10n/strings-index.tsv`, asset folders
and `app/src/main/AndroidManifest.xml`.

Abbreviations: `IOS:` = `/Users/tanguopeng/Documents/bitsigner-ios/BitSigner/`, `BTS/` = `IOS:BitToolSigner/`,
`UIT` = `IOS:BitToolSignerUITests/BitToolSignerUITests.swift`, `TOUR` = `IOS:BitToolSignerUITests/ScreenshotTour.swift`,
`IS` = `IOS:IMPLEMENTATION_STATUS.md`, `SEC` = `IOS:docs/Security.md`.

**Result in one paragraph.** The 11 specs cover the iOS app very thoroughly. Every Swift file and every resource is
cited. Every user-visible host string literal appears verbatim (line-wrap-insensitive) in at least one spec, and so do
all 104 accessibility identifiers and every numeric constant. All 773 catalogue keys are in the scaffold's string
index. The gaps are cross-cutting rather than per-screen: (G1–G3) no spec ports the 18 end-to-end UI tests, the
screenshot tour or a test-fixture harness; (G4–G5) two Android-only attack surfaces with no iOS equivalent (overlays
or tapjacking, and accessibility-service reading of secrets) are not addressed; (G6) about 60 user-visible iOS strings
are **not** in the 773-key catalogue, although `DECISIONS.md` §4 says every iOS string already exists as a resource;
(G7) several specs contradict each other or `DECISIONS.md` (engine host, Activity journal format, module and store
names, Android-only biometric wording, asset paths, the permission allowlist, the engine-text lookup); (G8) the canonical
asset layout and permission allowlist were never written down in one place.

---

## 1. Coverage table

### 1.1 How coverage was checked

| Check | Method | Result |
|---|---|---|
| File citations | Every Swift basename and resource name was grepped in all specs | 51/51 Swift files cited; 22/22 resources cited (the `bip39-*.txt` files are cited by the pattern `bip39-<raw>.txt`, vault-crypto §13.1, setup-backup §11.1) |
| User-visible literals | Every Swift string literal, minus identifiers, regexes, paths and `\(…)` fragments, was matched against the spec text with whitespace and curly quotes normalized | 100 % of the user-visible literals were found. The only misses are doc-comment examples, for example `"ETH · 1,234 tokens · 329 contracts"` at `TokenDirectoryView.swift:224` |
| Numeric constants | Every numeric literal outside comments and strings, normalized for `_` and thousands separators | All found, except six written in other units in the specs: the `Task.sleep` ns values 1.5 s, 1.6 s, 0.35 s and 1 s, `CLOCK_MONOTONIC_RAW / 1e9`, and the 0.45 opacity |
| Accessibility ids | All 104 `accessibilityIdentifier(...)` values (interpolated ids checked by prefix) | 104/104 found |
| Catalogue | `translations/zh-Hans.json` keys against the spec text and `tools/l10n/strings-index.tsv` | 773 keys + 2 `infoPlist`; 773/773 in the scaffold index (774 rows including the camera rationale); see §1.6 |
| Bundled data | SHA-256 of each scaffold asset against `BTS/Resources` | 18/18 byte-identical (see §1.3) |

### 1.2 Swift sources (`BTS/`, 51 files, 11,911 lines)

| File | Lines | Specified in (primary sections) | Verdict |
|---|---|---|---|
| `BitToolSignerApp.swift` | 53 | app-shell §2.1, §2.4 | full |
| `Core/BIP39Seed.swift` | 53 | vault-crypto §13.7; engine-contract §4.1; setup-backup §11.6 | full |
| `Core/PasswordPolicy.swift` | 46 | vault-crypto §4; setup-backup §9.2 | full |
| `Core/SigningEngine.swift` | 215 | engine-contract §3, §8.1 | full (the Android host is superseded, G7.1) |
| `Core/VaultCrypto.swift` | 216 | vault-crypto §6 | full |
| `Core/WalletVault.swift` | 279 | vault-crypto §2, §7–§9 | full |
| `Design/AddressText.swift` | 109 | design-system §5.11; models-state §1.6 | full |
| `Design/DesignSystem.swift` | 338 | design-system §2–§5 | full |
| `Design/Logos.swift` | 233 | design-system §5.17; models-state §1.2, §1.7 | full |
| `Design/PasswordViews.swift` | 239 | vault-crypto §5; setup-backup §9; design-system §5.14–§5.16 | full |
| `Design/PayloadDisclosure.swift` | 100 | design-system §5.13; scan-sign §15.2 | full |
| `Design/QRCodeView.swift` | 115 | design-system §5.12; scan-sign §15.1; wallets-pairing §3.4 | full |
| `Features/Activity/ActivityTab.swift` | 330 | activity-settings §2 | full |
| `Features/Backup/RecoveryBackupViews.swift` | 564 | setup-backup §8 | full |
| `Features/Lock/AppLockView.swift` | 186 | app-shell §11.9–§11.10, §15.7 | full |
| `Features/Main/AppRouter.swift` | 86 | app-shell §9 | full |
| `Features/Main/MainTabView.swift` | 31 | app-shell §8 | full |
| `Features/Main/RootView.swift` | 113 | app-shell §3–§4 | full |
| `Features/Onboarding/WelcomeView.swift` | 245 | app-shell §5–§6; setup-backup App. A | full |
| `Features/Settings/LegalDocumentView.swift` | 120 | activity-settings §10 | full |
| `Features/Settings/SettingsTab.swift` | 363 | activity-settings §3, §6, §8, §9 | full |
| `Features/Settings/SubmitWalletView.swift` | 68 | activity-settings §7 | full |
| `Features/Settings/TokenDirectoryView.swift` | 358 | activity-settings §4 | full |
| `Features/Settings/WalletDirectoryView.swift` | 113 | activity-settings §6.2 | full |
| `Features/Setup/RecoveryPhraseViews.swift` | 380 | setup-backup §7.2–§7.4, §8.4 | full |
| `Features/Setup/WalletSetupFlow.swift` | 452 | setup-backup §1, §5–§7 | full |
| `Features/Setup/WalletSetupModel.swift` | 230 | setup-backup §2 | full |
| `Features/Sign/QRScannerView.swift` | 378 | scan-sign §6 | full |
| `Features/Sign/RequestReviewView.swift` | 907 | scan-sign §11–§13 | full |
| `Features/Sign/ScanTab.swift` | 325 | scan-sign §3–§5, §7 | full |
| `Features/Sign/SignatureResultView.swift` | 110 | scan-sign §15 | full |
| `Features/Wallets/AddressesView.swift` | 201 | wallets-pairing §6 (checked line by line) | full |
| `Features/Wallets/ConnectView.swift` | 699 | wallets-pairing §3.6–§3.10, §7 | full |
| `Features/Wallets/WalletDetailView.swift` | 214 | wallets-pairing §5; vault-crypto §12.5–§12.6 | full |
| `Features/Wallets/WalletSecuritySheets.swift` | 345 | wallets-pairing §8; vault-crypto §12.7; setup-backup §10 | full |
| `Features/Wallets/WalletsTab.swift` | 153 | wallets-pairing §4 | full |
| `Models/AppLanguage.swift` | 127 | models-state §9; activity-settings §3.5; ownbit-build-release §8.7 | full |
| `Models/AppStore.swift` | 602 | models-state §5; vault-crypto §11; scan-sign §8, §14, §16 | full |
| `Models/BIP39Wordlist.swift` | 390 | vault-crypto §13; setup-backup §11 | full |
| `Models/BackupVerification.swift` | 93 | models-state §8; setup-backup §8.8 | full |
| `Models/CompatibleWallets.swift` | 176 | models-state §10; activity-settings §6 | full |
| `Models/Models.swift` | 702 | models-state §1–§3, §6; scan-sign §10 | full |
| `Models/RecoveryCards.swift` | 44 | models-state §7; setup-backup §8.6 | full |
| `Models/ReviewText.swift` | 107 | models-state §4; scan-sign §17 | full |
| `Services/AppAccessController.swift` | 213 | app-shell §11 | full |
| `Services/PrivacyShield.swift` | 84 | app-shell §12 | full |
| `Services/PublicWalletRepository.swift` | 132 | vault-crypto §10 | full |
| `Services/RecoveryPrivacySession.swift` | 83 | app-shell §13.1; setup-backup §8.1 | full |
| `Services/RecoveryScreenshotSession.swift` | 83 | app-shell §13.2; setup-backup §8.2 | full |
| `Services/SimulatorUITestContext.swift` | 63 | mentioned only (iOS-only) | out of scope as such; the Android test harness it implies is **G3** |
| `Services/TokenIconStore.swift` | 45 | models-state §11; activity-settings §5; design-system §9.5 | full |

### 1.3 Resources (`BTS/Resources`, 22 files) and their scaffold copies

| Resource | Specified in | Android scaffold path | Byte-identical |
|---|---|---|---|
| `signer.js` (1,181,026 B, sha256 `6933d0d8…1077`) | engine-contract §1, §12.6; ownbit-build-release §7.5 | `core/engine/src/main/assets/engine/signer.js` | yes |
| `token-registry.json` | activity-settings §4.1; scan-sign §10 | `core/data/src/main/assets/registry/` | yes |
| `known-contracts.json` | activity-settings §4.1; scan-sign §10.5 | `core/data/src/main/assets/registry/` | yes |
| `TokenIcons.pack`, `TokenIcons.index.json` | models-state §11; activity-settings §5; design-system §9.5 | `core/data/src/main/assets/registry/` | yes / yes |
| `bip39-{english,chinese_simplified,chinese_traditional,japanese,korean,spanish,french,italian,czech,portuguese}.txt` | vault-crypto §13.1; setup-backup §11.1 | `core/crypto/src/main/assets/bip39/` | 10/10 yes |
| `TERMS_OF_USE.txt`, `PRIVACY_POLICY.txt` | activity-settings §10 (also lists the Android rewrites they need) | `core/data/src/main/assets/legal/` | yes / yes |
| `THIRD_PARTY_LICENSES.txt` | activity-settings §9 | `core/data/src/main/assets/legal/` | yes |
| `LaunchScreen.storyboard` | app-shell §3.1; design-system §9.9 | splash theme (no file) | n/a |
| `PrivacyInfo.xcprivacy` | out of scope (DECISIONS §1) | — | n/a |

### 1.4 Test sources (beyond the brief, checked because the specs promise test parity)

| Target | Specified in | Verdict |
|---|---|---|
| `BitToolSignerTests/*.swift` (25 files) + `BIP39LanguageVectors.json`, `PublicPlanVectors.json` | engine-contract §11.3 maps every file to its Android home; vault-crypto §16, setup-backup §15.11 and others add detail | covered |
| `BitToolSignerUITests/BitToolSignerUITests.swift` (18 end-to-end flows, 1,154 lines) | app-shell §15.14 names 3 of the flows (onboarding, App Lock, delete); setup-backup §15.11 lists component-level Compose tests for setup; no spec ports the suite or its fixtures | **gap, G1** |
| `BitToolSignerUITests/ScreenshotTour.swift` (60 shots per theme) | not cited; setup-backup §15.11 asks for setup screenshot tests across font scales, widths and RTL | **gap, G2** |
| `BitToolSignerUITests/BitToolSignerUITestsLaunchTests.swift` | not cited | **gap, G1.19** |

### 1.5 Feature and security claims (README, IMPLEMENTATION_STATUS Rev 11→2.1, Security.md)

| # | Claim (source) | Specified in | Status |
|---|---|---|---|
| 1 | Opens no network connection; engine, registry and icons are bundled (README:5; SEC:135) | DECISIONS §6; activity-settings §14.7; ownbit-build-release §7.3 | covered; the allowlist is incomplete, **G8** |
| 2 | 21 languages; app name never translated; only permission prompts localized (README:7; IS:135, :481) | DECISIONS §1, §4; activity-settings §3.5; app-shell §2.3 | covered |
| 3 | Installed build verifiable through the `signer.js` digest and a release manifest (README:17-26) | activity-settings §9 (APK pull); ownbit-build-release §7.2, §7.5 | covered; path conflict, **G7.5** |
| 4 | Wallets tab: no "current wallet"; card = identity colour, name, master fingerprint, networks, Pair and Addresses links; detail page: 4 chains + Wallet Level, addresses, reveal, change password, rename, delete; a chain row opens Coin Level, Wallet Level and the card's Pair open Wallet Level (README:50; IS:257) | wallets-pairing §4–§5; app-shell §9 | covered |
| 5 | Pair: Coin Level / Wallet Level; animated `ur:bytes` multi-frame QR with 4 BTC xpubs, EVM, TRON and 3 Solana addresses; BTC Account XPUB by default; TRON index 0…2³¹−1 (README:52; IS:183, :525, :532) | wallets-pairing §7; engine-contract §6.5–§6.6 | covered |
| 6 | Scan: the whole card is tappable; paste; file import; review order summary → warnings → wallet → fields → fingerprint; acknowledgement and Sign pinned; one password per signature (README:54) | scan-sign §3–§5, §11, §13 | covered |
| 7 | 1,720 known contracts: a hit names the deployment, a miss says so, an unlisted spender or operator raises the risk (README:56; IS:314-366) | scan-sign §10.5, §11.5; activity-settings §4 | covered |
| 8 | NFT review cards (Seaport, LooksRare, Blur, Metaplex, Bubblegum, Core) (README:58; IS:599) | scan-sign §11.3–§11.4; models-state §3.2.5 (decoding is in the engine) | covered |
| 9 | Activity: by date; no broadcast; input re-reviewed and fingerprint must match; output QR, file and raw text; only the last 25 keep payloads; public data, protected, excluded from backups (README:60; IS:266-286) | activity-settings §2; models-state §6 | covered; journal format conflict, **G7.2** |
| 10 | Settings: App Lock, appearance, language (Arabic RTL), Networks, Tokens & Contracts, compatibility, Open Source, version, entity (README:62) | activity-settings §3–§10 | covered. README's "air-gapped device guide" in Settings is stale: it moved to onboarding (IS:428; activity-settings §11) |
| 11 | First run: welcome → 3 onboarding pages shown once, intent kept → 4 create steps or 2 import steps (README:64) | app-shell §5–§7; setup-backup §1 | covered |
| 12 | Backup: Words/QR switch, concealed by default, one-tap reveal, re-conceal on screenshot, background or recording; plain QR never saved or shared; 2-of-3 cards with a 12/15-word warning; Security Tips; 4–5 blanks to fill (README:66; SEC:119-127; IS:616-626) | setup-backup §6.2–§6.3, §8 | covered |
| 13 | Import in 10 BIP39 languages, detection, checksum, no accents needed, CJK without spaces, ambiguity question, suggestions, phrase QR and SeedQR (README:68; SEC:125) | setup-backup §7, §11; vault-crypto §13 | covered |
| 14 | Vault: PBKDF2-HMAC-SHA256 600,000 → AES-256-GCM → outer device-key layer; v2 authenticates the stored header bytes (README:74; SEC:13-20) | vault-crypto §6–§7 | covered |
| 15 | Terms and Privacy v1.1 bundled; read from Settings › About and the welcome page; no browser (README:75; IS:743) | activity-settings §10; app-shell §5 | covered; the Android texts need legal rewriting (open question 5) |
| 16 | Public index HMAC under a device key; tampered or missing → Repair with one password (README:76; SEC:44-48) | vault-crypto §10; wallets-pairing §8.5 | covered |
| 17 | Match by public key → fixed task → password per task; no manual wallet choice, no global unlock, no password-free signing (README:77; SEC:42, :50) | scan-sign §8, §11.7, §13–§14; models-state §5.4 | covered |
| 18 | BIP39 seed computed natively; fresh context per secret or untrusted operation; release allowlist; `getRandomValues` and `Math.random` both native (README:78; SEC:62-69) | engine-contract §3–§5 | covered; the host implementation is superseded, **G7.1** |
| 19 | App Lock: optional, off by default, never authorizes a signature; cold launch locked; 120 s background grace on a sleep-counting clock; no idle timer; removing the last wallet disables it; a failed wallet load is not "empty" (README:79; SEC:52) | app-shell §11, §15.4–§15.6; DECISIONS §6 | covered |
| 20 | Backgrounding immediately clears passwords, acknowledgements and revealed phrases (README:79; SEC:111-115) | app-shell §4.6, §15.4; setup-backup §2.3 | covered |
| 21 | Five failed attempts → 2 s doubling to 300 s; persisted before derivation; never destroys a wallet (SEC:54) | vault-crypto §8 | covered |
| 22 | Engine findings R2-01/02/03 and F-01…F-04, the BTC fee rule, NFT and known-contract logic (IS:17-28, :97-115) | inside the byte-identical `signer.js`; engine-contract §11 vectors | covered by engine identity |
| 23 | I-01 TRON energy shown as "Energy budget", not a fee, in history (IS:41) | activity-settings §2.5 (`feeKind == "energyBudget"`) | covered |
| 24 | I-02 Repair sheet clears its password on suspend (IS:42) | wallets-pairing §8.5 | covered |
| 25 | Password reset by re-importing the same phrase: reset mode, hint hidden, "Password Reset", keeps name, date, addresses and history, clears attempts, `walletKeyId` checked first (IS:48-66) | setup-backup §2.2, §6.4; vault-crypto §9.5, §11.6 | covered |
| 26 | Delete asks for biometrics only when App Lock is on; toggle "I confirm I want to delete this wallet" (IS:68-78) | wallets-pairing §8.4; app-shell §11.8 | covered |
| 27 | "Contact Us": entity and copyable `support@bittool.com`, no `mailto:` (IS:116) | activity-settings §8 | covered |
| 28 | Wrong-password message "The password is incorrect." (IS:120) | vault-crypto §6.7; scan-sign §21 | covered |
| 29 | The camera does not reopen after Done (router-held marker) (IS:125) | app-shell §9; scan-sign §2 | covered |
| 30 | Engine text translated at draw time (`phrase`/`label`/`headline`); fee ceilings at 8 decimals rounded up (IS:143-161) | scan-sign §17; models-state §4 | covered; lookup-mechanism conflict, **G7.7** |
| 31 | Scanner dark only in its own subtree (IS:162) | scan-sign §6.2; design-system §5.23 | covered |
| 32 | Pairing verification lists 9 rows with id `chain/profile` (IS:217) | wallets-pairing §7.4; models-state §3.4 | covered |
| 33 | Request fingerprint shown in upper case everywhere (IS:288) | design-system §5.11 (`HashText`) | covered |
| 34 | Networks, Tokens & Contracts: segmented switch sharing one search, hidden when one side is empty, "All EVM networks" row (IS:361) | activity-settings §4.3–§4.4 | covered |
| 35 | Language switch applies at once, rebuilds the tree, returns to the first tab; only translated languages listed (IS:385) | activity-settings §3.5, §14.5; models-state §12.4 | covered |
| 36 | Passphrase eye button re-conceals on background; AutoFill off for new secrets (IS:488) | setup-backup §6.1.1; app-shell §15.9 | covered |
| 37 | Fingerprint as wallet identity; App Lock picker shows names only (IS:723) | wallets-pairing §4.1; app-shell §11.9 | covered |
| 38 | Coin Level Payload section, Format → Master fingerprint → Payload order, "Copy Payload" to a local expiring clipboard (IS:730-742) | wallets-pairing §3.5, §7.3; design-system §5.13 | covered |
| 39 | Result page: single Done; Format, Payload (JSON) and a secondary Export as File (IS:778) | scan-sign §15 | covered |
| 40 | Backup check: blanks scroll into view, chips pinned to the bar, wrong answer keeps blanks and order, background or screenshot clears placements (IS:797-803; SEC:127) | setup-backup §8.8 | covered |
| 41 | Concealed content absent from the view and accessibility tree (SEC:119) | setup-backup §8.4; app-shell §15.8 ("concealed words must not be composed at all") | covered. On Android, accessibility services can still read *revealed* words: **G5** |
| 42 | No third-party keyboards (SEC:125) | app-shell §2.4, §15.9; setup-backup §15.8 (cannot be ported; mitigations listed) | covered |
| 43 | The only exit to another app is the camera-permission Settings button (SEC:135) | app-shell §15.4 (SAF picker and Settings exits counted as background) | covered |
| 44 | Real-device acceptance list (IS:1006-1021) | ownbit-build-release §7.2 step 12 (device self-test) | partly: third-party wallet round trips are not in the Android checklist (open question 9) |
| 45 | End-to-end flows listed as tested (IS:841-870) | — | **gap, G1** |

### 1.6 Translations (`translations/zh-Hans.json`)

- `strings`: **773 keys**; `infoPlist`: 2 (`NSCameraUsageDescription`, which becomes the Android camera rationale, and
  `NSFaceIDUsageDescription`, dropped). All 773 English keys are present in `tools/l10n/strings-index.tsv`.
- **628 keys** are quoted verbatim in at least one spec, ignoring line wraps. The other **145** occur only inside
  `signer.js`: Details field labels, display actions, review titles and the like (for example "Access list", "Safe
  nonce", "Transfer ERC-1155 token"). By design they are not specified screen by screen. They reach the screen through
  the `ReviewText` runtime lookup (scan-sign §17; DECISIONS §4 `IosStrings.lookup`). No host-side key is missing from the
  specs.
- 541 keys appear as complete literals in Swift and 246 in `signer.js`. The remaining 52 are interpolated keys
  (`%@`/`%lld`), and all 52 are quoted in the specs.
- **Sample of 60 keys** (seeded random positions; the index is the key's position in the file). The spec column names
  the section that describes the screen where the key is used:

| # | Key (verbatim prefix) | Used at | Screen specified in |
|---|---|---|---|
| 32 | Access list | engine | scan-sign §11.9 Details + §17 |
| 39 | Accounts by derivation path | AddressesView.swift:82 | wallets-pairing §6 |
| 47 | Address only. No public key or derivation path is included. | ConnectView.swift:310 | wallets-pairing §7.3 |
| 50 | Advanced. Adds a “25th word”. | WalletSetupFlow.swift:256 | setup-backup §6.1.1 |
| 51 | Air-gapped signing. For everyone. | SettingsTab.swift:92; RootView.swift:80; WelcomeView.swift:69 | activity-settings §3.4; app-shell §3.2 |
| 63 | Amount (TRX) | engine | scan-sign §11.9 + §17 (`label`) |
| 67 | Another wallet has the same key identity. Requests it matches cannot b… | WalletsTab.swift:106 | wallets-pairing §4.1 |
| 83 | Attached TRX | engine | scan-sign §11.9 + §17 |
| 92 | BIP39 passphrase | PasswordViews.swift:6 | design-system §5.14; setup-backup §9.1 |
| 95 | BTC · Single-sig and multisig | TokenDirectoryView.swift:161 | activity-settings §4.3 |
| 97 | Back Up Your Phrase | WalletSetupFlow.swift:285 | setup-backup §6.2 |
| 116 | BitTool Signer uses open QR standards. The wallets below support these… | SettingsTab.swift:172 | activity-settings §6.1 |
| 141 | Clear NFT approval | engine | scan-sign §11.3 + §17 |
| 151 | Confirm | PasswordViews.swift:39 | setup-backup §9.1 |
| 157 | Contact Us | SettingsTab.swift:81, :138 | activity-settings §8 |
| 162 | Contract is not in the known list | RequestReviewView.swift:528 | scan-sign §11.5 |
| 174 | Create Recovery Phrase | WalletSetupFlow.swift:157 | setup-backup §6.1 |
| 196 | Deploy contract | engine | scan-sign §11.3 + §17 |
| 208 | Distinct token IDs | engine | scan-sign §11.9 + §17 |
| 209 | Does your wallet read BitTool Signer's QR codes? Send us the details a… | SubmitWalletView.swift:12 | activity-settings §7 |
| 211 | Done | PasswordViews.swift:133; LegalDocumentView.swift:115; ActivityTab.swift:326 | activity-settings §2.7, §10 |
| 217 | Each passphrase opens a different wallet. Back it up separately: the r… | WalletSetupFlow.swift:271 | setup-backup §6.1.1 |
| 218 | Each wallet signs with its own password. The final signature appears a… | RequestReviewView.swift:120 | scan-sign §11.7 |
| 220 | Email | SubmitWalletView.swift:16 | activity-settings §7 |
| 227 | Enter the password of any wallet on this device. | AppLockView.swift:68 | app-shell §11.9 |
| 252 | Fingerprint | PasswordViews.swift:231; ActivityTab.swift:141 | activity-settings §2.4; setup-backup §9.3 |
| 275 | I understand the risks of this request | RequestReviewView.swift:166 | scan-sign §11.7 |
| 281 | Import Existing Wallet | WelcomeView.swift:43 | app-shell §5.1 |
| 319 | Known Public Contracts | TokenDirectoryView.swift:207 | activity-settings §4.3 |
| 321 | Known public contract | RequestReviewView.swift:517 | scan-sign §11.5 |
| 326 | Legacy and v0 transactions with static accounts, and messages. Address… | SettingsTab.swift:182 | activity-settings §6.1 |
| 328 | Light | SettingsTab.swift:47; RecoveryBackupViews.swift:141 | activity-settings §3.2; setup-backup §8.5 |
| 330 | Lock period (blocks) | engine | scan-sign §11.9 + §17 |
| 375 | Never take a screenshot or photo of the words, and never store them in… | RecoveryBackupViews.swift:376 | setup-backup §8.7 |
| 416 | Owner / authority | engine | scan-sign §11.9 + §17 |
| 437 | Permission ID | engine | scan-sign §11.9 + §17 |
| 455 | QR format | SettingsTab.swift:191, :207; WalletDirectoryView.swift:7 | activity-settings §6.1, §6.2 |
| 468 | Receive · TRX and TRC-20 tokens | AddressesView.swift:81 | wallets-pairing §6 |
| 470 | Receiver callback bytes | engine | scan-sign §11.9 + §17 |
| 476 | Recognized tokens are shown with their name, icon and decimals… Registry %@. | TokenDirectoryView.swift:187 | activity-settings §4.3 |
| 492 | Rename Wallet | WalletDetailView.swift:90, :117 | wallets-pairing §8.6 |
| 507 | Require %@ to open BitTool Signer at launch and after 2 minutes in the… | SettingsTab.swift:40 | app-shell §11.11; activity-settings §3.1 |
| 518 | Run: cd engine && npm ci && npm run build | SettingsTab.swift:279 | activity-settings §9 |
| 520 | Safe address | engine | scan-sign §11.9 + §17 |
| 540 | Scan with your watch-only wallet | ConnectView.swift:227 | wallets-pairing §7.3 |
| 569 | Sign Ethereum message | engine | scan-sign §10.1, §11.3 |
| 582 | Signatures you create on this device are listed here. BitTool Signer n… | ActivityTab.swift:21 | activity-settings §2.3 |
| 594 | Single-sig and multisig accounts | WalletDetailView.swift:197 | wallets-pairing §5 |
| 597 | Some words don't match. Check your written backup and try again. | WalletSetupModel.swift:135 | setup-backup §2.2 |
| 619 | The BIP39 passphrase exceeds 1,024 UTF-8 bytes. | WalletVault.swift:179; AppStore.swift:182 | vault-crypto §6.7, §9.5 |
| 644 | The recovery phrase stays hidden until you choose to show it. | WalletSecuritySheets.swift:91 | wallets-pairing §8.2 |
| 666 | This exports one cosigner key. Your multisig coordinator combines all… | ConnectView.swift:312 | wallets-pairing §7.3 |
| 667 | This file is larger than 512 KB. | ScanTab.swift:231 | scan-sign §5 |
| 686 | Token ID (hex) | engine | scan-sign §11.3 + §17 |
| 702 | Transfer ERC-1155 token | engine | scan-sign §11.3 + §17 |
| 731 | Using your written copy, tap the missing words in order. Tap a blank t… | WalletSetupFlow.swift:319 | setup-backup §6.3 |
| 745 | Wallet information changed. Match this request again. | AppStore.swift:107, :385, :416 | engine-contract §9; models-state §5.6 |
| 760 | Works with | SettingsTab.swift:192; ConnectView.swift:402, :613 | activity-settings §6.1; wallets-pairing §3.10 |
| 763 | You give | RequestReviewView.swift:488; Models.swift:201 | scan-sign §11.3 |
| 767 | Your keys are encrypted on this device. Next, pair a watch-only wallet… | WalletSetupFlow.swift:418 | setup-backup §6.5 |

Result: 45 of the 60 sampled keys are host strings, and every one has a specified screen. The other 15 are engine
strings and reach the screen through `ReviewText` (§17 of scan-sign).

---

## 2. Gaps filled

### G1. End-to-end acceptance suite: port of `BitToolSignerUITests`

The iOS suite (UIT, 18 flows, about 17 minutes) is the only executable statement of several cross-screen behaviours:
the import intent survives onboarding, backgrounding preserves the draft but clears passwords, the risk acknowledgement
is per visit, Activity re-reviews, a reset keeps the wallet, the 120 s grace keeps the page, and others. The Android port
must reproduce it as black-box tests against the debug app.

#### G1.0 Harness conventions

**Module.** Add `:e2e`, a `com.android.test` module with `targetProjectPath = ":app"`, the `apk` flavor
(`missingDimensionStrategy("distribution", "apk")`) and the debug build type. It is self-instrumenting
(`experimentalProperties["android.experimental.self-instrumenting"] = true`), so the test process is not the app process.
That matches XCUITest, which drives the app from outside: the test can `am force-stop` and cold-launch the app, which
App Lock's cold-launch rule and the fixture reset require. The test drives the UI with UiAutomator (`androidx.test.uiautomator`), and the
app root sets `Modifier.semantics { testTagsAsResourceId = true }` in debug builds, so that every test tag below is a
view resource name (`By.res("signing.scan")`). Shared files (`settings.gradle.kts`, the version catalogue) are owned by
the orchestrator (DECISIONS §8).

**Device setup** (once per run): API 34 or 35 emulator, `en-US` system locale, the three animation scales set to 0
(`settings put global window_animation_scale 0`, and the same for `transition_animation_scale` and
`animator_duration_scale`), the emulator camera enabled (virtual scene).

**Constants.** Copy these byte for byte from UIT and never log them:

| Name | Value / source |
|---|---|
| `publicRecoveryPhrase` | `abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about` (UIT:9); master fingerprint `73C5DA0A` = 1942346250 |
| `fixturePassword` | `Public-Test-Password1!` (UIT:10) |
| `publicSigningRequest` | the `UR:ETH-SIGN-REQUEST/OSADTPDA…MKCH` string at UIT:11 |
| `approveKnownSpenderRequest` | UIT:14 (unlimited USDT approval to Permit2, fixture 1) |
| `approveUnlistedSpenderRequest` | UIT:16 (the same approval to an unlisted address) |
| other passwords | `Mismatch-Password1!`, `UiTest-Backup8!`, `Wrong-Password1!`, `Updated-Test-Password2!`, `Recovered-Test-Password3!` |
| Spanish phrase | `obra diadema gorila farmacia colgar gorra pausa talar cocina duda dragon optar` (UIT:273; "dragon" deliberately without its accent) |
| Italian/Spanish ambiguous phrase | `nomina solido comodo nucleo pagina armonia nucleo liquido pagina logica nucleo nucleo` (UIT:287) |

**Helpers** (iOS helper → Android equivalent):

| iOS (UIT:918-1154) | Android |
|---|---|
| `launch(fixtures:dark:)` (:923) | `am force-stop <pkg>`, then `am start -W -n <pkg>/com.bittool.signer.MainActivity --es bts.uitest.run <uuid> --ez bts.uitest.reset true --ei bts.uitest.fixtures N [--es bts.uitest.appearance dark]` (G3). If N > 0, wait up to 150 s until N objects with resource prefix `wallet.card.` exist, then 1 s more. Otherwise wait 20 s for `welcome.create` |
| `element(containing:)` (:969) | the first object whose text **or** content description contains the string, case-insensitive |
| `assertStep(n, of: t)` (:974) | wait 15 s for text starting with `Step n of t` (key `Step %lld of %lld`) |
| `scrollToExistence` (:981) | up to 6 swipes up, then 10 down, on the main scrollable, until the object exists. Lazy lists only compose visible rows, as on iOS |
| `tap` (:998) | scroll to the object; if the IME is shown (`dumpsys input_method` → `mInputShown=true`), tap the top-app-bar title to clear focus (never `pressBack()`, which would navigate); then require enabled and visible, and click |
| `typePayload` (:1024) | `obj.text = payload`, then assert `obj.text == payload`. UiAutomator `setText` does not drop characters, so the retry is a safety net only |
| `type(_, into:)` / `clear` | click, then `obj.text = value` (replaces, where iOS appends) / `obj.clear()` |
| `setSwitch` (:1055) | click until `isChecked == target` |
| `goBack` (:1066) | click the top-app-bar navigation icon. **New Android-only test tag `nav.back` on every back icon** (never `pressBack()` in tests that check discard dialogs) |
| `backgroundAndReactivate` (:1079) | `pressHome()`, wait ≤ 5 s until `currentPackageName != pkg`, then wait 1 s, then relaunch with the launcher intent and no extras. `singleTask` resumes the task and does not cold-start it. Assert the app is in the foreground within 5 s. Always keep this under 120 s so App Lock grace applies |
| `assertEmptySecureField` (:1088) | text is empty or equals the placeholder (a non-empty Compose password field reports masked characters) |
| `waitUntilEnabled` (:1095) | `obj.wait(Until.enabled(true), 20_000)` |
| `revealWords` (:1102) | tap `recovery.reveal`; read the 12 objects `recovery.word.0` … `recovery.word.11`; each holds exactly one ASCII-letter token; keep them in memory only; never log them or attach them to reports |
| `blankPositions` / `chipLabels` / `fill` / `assertBlanksEmpty` (:1118-1153) | resource names `verification.slot.<pos>` (sorted) and `verification.chip.0…7`. State is read from `AccessibilityNodeInfo.stateDescription` through `uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByViewId(tag)` (API 30+). Slot: starts with `"Empty"` (`"Empty, next to fill"` for the active one). Chip: `"Available"` / `"Placed"` (models-state §8) |
| camera permission (:494-504) | the permission-controller button by resource id `permission_allow_foreground_only_button` (or `…_one_time_button`), in `com.android.permissioncontroller` or `com.google.android.permissioncontroller` |

#### G1.1 Onboarding keeps the import intent and is shown once (UIT:23-56)

0 fixtures.
1. No bottom navigation exists before the first wallet.
2. Tap `welcome.privacy` → a sheet titled "Privacy Policy" whose text contains "collects nothing" (PRIVACY_POLICY.txt:5) → tap `legal.done`.
3. Tap `welcome.import` → the text "Offline by Design" is shown; no "Skip" button exists. For pages 1–3, the page indicator's content description is `Page n of 3`; tap `onboarding.next`.
4. Assert "Step 1 of 2" and the title "Import Wallet".
5. Type `abandon abandon abandon` into `recovery.input.visible` → `recovery.status` contains "3 words"; tap `setup.import` → "Invalid recovery phrase" appears within 10 s.
6. Tap the top bar's "Cancel" → a "Discard" button appears (the typed words are not dropped silently) → tap it → `welcome.create` exists.
7. Tap `welcome.create` → "Step 1 of 4"; "Offline by Design" does **not** appear (onboarding is shown once).

#### G1.2 Import seals the wallet and opens Wallets (UIT:59-85)

0 fixtures. Open import (step 3 of G1.1, or skip onboarding when already seen). Type `publicRecoveryPhrase` → status
contains "12 words" → `setup.import` → "Step 2 of 2". `setup.finish` is disabled. Enter `fixturePassword` in "Wallet
password" and `Mismatch-Password1!` in "Confirm password": still disabled. Clear the confirmation and type
`fixturePassword`, then tap `setup.finish`. "Wallet Imported" appears within 60 s. Tap `setup.done` → the first
`wallet.card.*` appears within 30 s and its text contains "My Wallet"; the "Scan" tab exists; `welcome.create` is gone.

#### G1.3 Create requires reveal, backup check and password (UIT:88-184)

0 fixtures. Create → "Step 1 of 4"; choose "12 words"; tap `setup.create` → "Step 2 of 4".
- `recovery.reveal` exists, `recovery.word.0` does **not**, and `backup.continue` is disabled.
- Reveal: no dialog appears (single tap); `backup.continue` becomes enabled. Tap `recovery.hide` → concealed again.
- Background and reactivate → still step 2, words concealed; revealing again shows **the same 12 words** (the draft is concealed, not replaced).
- `backup.continue` → "Step 3 of 4"; no `recovery.word.0`; `verification.continue` is disabled.
- Exactly **4** blanks; the chip labels sorted equal the blank words sorted; the first blank's state is `Empty, next to fill`.
- Fill with the first two different words swapped → `verification.continue` is enabled → tap it → text containing "Some words don" appears; the blanks and chip order are unchanged, every blank is empty again, and continue is disabled.
- Fill one blank, then tap that slot → it empties.
- Fill all, background and reactivate → step 3, the same blanks and chip order, all blanks empty. Fill correctly → continue → "Step 4 of 4".
- Type `UiTest-Backup8!` in both fields → `setup.finish` is enabled. Background and reactivate → both fields are empty and finish is disabled. Retype and finish → "Wallet Created" within 60 s.
- `setup.connect` → title "Pair Wallet" within 10 s (Wallet Level); no password field; `connect.copy` becomes enabled within 20 s (the push completed and the QR loaded); `connect.level` first segment "Wallet Level". Select "Coin Level" → "Pair Bitcoin"; `pairing.outputMode` first segment "Account XPUB" and selected. Tap `chain.evm` → "Pair EVM"; `chain.evm` selected, `chain.bitcoin` not.

Android notes: on API 30+ the selected state is `isSelected` or stateDescription. FLAG_SECURE does not block
UiAutomator, which reads the accessibility tree (but see G5 about `accessibilityDataSensitive`).

#### G1.4 "Show the phrase again" returns to the same concealed draft (UIT:187-207)

Create with 12 words, reveal and remember the words, `backup.continue` → step 3 → `verification.showPhrase` → step 2,
concealed; revealing shows the same words → `recovery.hide` → Cancel → "Discard" → `welcome.create`.

#### G1.5 Backup offers recovery cards, tips and a concealed QR (UIT:210-264)

Create with 12 words → step 2.
- `backup.cards` → title "Recovery Cards"; `cards.reveal` exists, `cards.card.0` does not; text containing "12-word phrase" (the short-phrase warning) exists. Reveal → no dialog; `cards.card.0` exists; `cards.card.0.word.0` reads `Word 1, not on this card`; `cards.card.1.word.0` does not.
- Background and reactivate → `cards.card.0` concealed. Close the sheet ("Done") if it is still open → `recovery.reveal` exists and `backup.continue` is **enabled** (showing the words on the cards counts as revealing).
- `backup.tips` → title "Security Tips" → "Done".
- `backup.format` → "QR Code": no `recovery.word.0`; `backup.qr.reveal` exists, `backup.qr` does not; text containing "plain text" exists. Reveal → no dialog; `backup.qr` exists; no "Save or Print" action exists. `backup.qr.hide` → concealed.
- Cancel → "Discard".

#### G1.6 Import accepts other languages and asks when ambiguous (UIT:267-298)

Import the Spanish phrase → status contains "12 words" **and** "Español" → `setup.import` → "Step 2 of 2" → Cancel →
Discard. Import the ambiguous phrase → `setup.import` → buttons whose text begins "Español" and "Italiano" appear → choose
Español → "Step 2 of 2" → set `fixturePassword` twice → finish → "Wallet Imported" within 60 s.

#### G1.7 The import draft survives backgrounding (UIT:301-328)

Import: replace `setup.name` ("My Wallet") with "Travel Signer", then type `publicRecoveryPhrase`. Background and
reactivate → still step 1 of 2; the name and phrase are unchanged; no dialog. `setup.import` → step 2; type the password;
background and reactivate → the password field is empty. `nav.back` → step 1, with the phrase and name intact.

#### G1.8 A pasted request is matched, reviewed and signed with the password (UIT:333-431)

1 fixture. "Scan" tab → `signing.scan` exists.
1. `signing.paste` → `signing.payload` exists and `signing.decode` is disabled. Type `unsupported-qr-test-fixture` → decode → text containing "Unsupported" within 10 s → "Cancel" on the "Paste Request" sheet.
2. Paste again with `publicSigningRequest` → decode → title "Review Request" within 20 s; `signing.authorize` appears within 20 s and its text contains "Public fixture 1" (matched by keys; button "Sign with %@").
3. `signing.risk` exists, Sign is disabled; tick → checked; Sign enabled.
4. Sign → "Wallet password" field → type `fixturePassword` → background and reactivate → the "Authorize Signature" sheet is gone; `signing.risk` is **unchecked** (consent is per visit); Sign is disabled.
5. Tick, Sign → the password field is empty; type `Wrong-Password1!` → `authorize.sign` → "password is incorrect" within 30 s. Type `fixturePassword` → `authorize.sign` → `signature.title` within 30 s.
6. Exactly one "Export as File" exists. Scroll `signature.payloadToggle` clear of `signature.done`, tap → `signature.payload` contains "request-id" and "signature"; `signature.export` exists; `signature.done` → `signing.scan` (the camera does **not** open, IS:125).
7. "Activity" tab → the first `activity.row` contains "Public fixture 1" → open → title "Signature"; "Request fingerprint" exists. Scroll to `activity.decoded` → tap → title "Signed Request"; "Details" exists and "Derivation path" can be scrolled to. Back → "Signature".
8. `activity.showQR` → "Scanning it again signs nothing new" → "Done" → "Signature". `activity.export` exists. `activity.signatureToggle` → text containing "ur:eth-signature".
9. Back → `activity.clear` → confirm "Clear Activity" → "No Signatures Yet".

#### G1.9 Reject clears the reviewed request (UIT:434-446)

1 fixture. Paste `publicSigningRequest` → `signing.authorize` appears → `signing.reject` → `signing.scan` exists and
"Review Request" is gone.

#### G1.10 An approval names a known spender and flags an unlisted one (UIT:451-485)

1 fixture. Paste `approveKnownSpenderRequest` → "Review Request" → `review.contractNotice` appears within 20 s and its
text (merged semantics) contains "Permit2", "Uniswap" and "Known public contract"; the Details contain "Spender (known
contract)"; no "not in the bundled list" text exists. Reject. Paste `approveUnlistedSpenderRequest` → the notice contains
"Spender is not a known contract" and not "Permit2"; scrolling finds the warning beginning "The spender is not in the
bundled list of known public contracts." (engine text).

Android note: `review.contractNotice` must merge its descendants' text (`Modifier.semantics(mergeDescendants = true)`),
so that one node carries the label the way iOS's combined accessibility element does.

#### G1.11 The Wallets scan button opens the camera directly (UIT:490-510)

1 fixture; camera permission revoked first (`pm revoke <pkg> android.permission.CAMERA`). Tap `wallets.scan` → answer
the system dialog (Allow while using the app) → within 20 s a "Close" control and the title "Scan Request" exist → Close
→ `signing.scan` exists (the person is left on Scan).

#### G1.12 The scan card opens the camera without a password (UIT:513-534)

1 fixture; permission granted (`pm grant`). "Scan" tab → tap `signing.scan` → "Close" and "Scan Request" exist; no
password field → Close → `signing.scan`.

#### G1.13 Pair and Addresses use public data only (UIT:539-625)

3 fixtures. 3 wallet cards; text containing "Fingerprint 73C5DA0A".
- The first `wallet.connect.*` → "Pair Wallet"; no `connect.walletMode`; within 10 s text containing "bc1q, bc1p, 3, 1" and "all three Solana addresses"; `connect.walletQR` within 20 s; `connect.copy` enabled; scrolling finds "bittool-signer-wallet v1" and "BitTool Wallet".
- Scroll up, select "Coin Level" → "Pair Bitcoin"; `connect.payloadToggle` → `connect.payload` contains "master-fingerprint"; text containing "1942346250 is fingerprint 73C5DA0A".
- `chain.evm` / `chain.tron` / `chain.solana` / `chain.bitcoin` → titles "Pair EVM" / "Pair TRON" / "Pair Solana" / "Pair Bitcoin".
- `chain.tron` → set `connect.tronIndex.field` to `123456` (select all, type, close the IME) → "m/44'/195'/0'/0/123456" within 10 s; `connect.copy` enabled. "Watch only" exists, "Watch and sign by QR" does not, "TokenPocket" exists, "Ownbit" does not.
- `chain.bitcoin` → "Watch and sign by QR", "Sparrow" and "BlueWallet" exist; `pairing.outputMode` has no "Public Key" segment.
- Wallet Level → "Pair Wallet" → Coin Level → "Pair Bitcoin" (each level keeps its state); no password field. Background and reactivate → "Pair Bitcoin" is still shown. Back.
- The first `wallet.addresses.*` → "Addresses"; `address.row.0` within 20 s and its text without spaces contains "bc1q". `chain.evm` → a row containing "0x". `chain.tron` → "TRX and TRC-20" and `address.row.0`; no text containing "195'". `chain.solana` → rows beginning "Standard", "Trust" and "Legacy"; tap "Standard" → "Derivation path" → "Done"; no password field.

#### G1.14 Changing the password protects the recovery phrase (UIT:628-663)

1 fixture. The first card → `detail.changePassword` → "Current wallet password" = `Wrong-Password1!`, "New password" =
"Confirm new password" = `Updated-Test-Password2!` → `vaultManagement.submit` → "password is incorrect" within 30 s
(reported, not swallowed). Replace the current password with `fixturePassword` → submit → the "Change Password" sheet
closes within 60 s, and `detail.recoveryPhrase` still exists (the page stays). `detail.recoveryPhrase` → "Wallet
password" = `fixturePassword` → submit → "password is incorrect". Type the new password → submit → `recovery.reveal`
exists and `recovery.word.0` does not. Background and reactivate → the password field is back and `recovery.reveal` is
gone (the decrypted phrase is discarded) → "Cancel" on "Recovery Phrase" → `detail.recoveryPhrase`.

#### G1.15 Re-importing an existing phrase resets its password in place (UIT:669-717)

1 fixture; remember the first card's text. `wallets.add` → "Import Recovery Phrase" → skip onboarding if it is shown →
type `publicRecoveryPhrase` → `setup.import` → `setup.finish` appears; title "Reset Password" within 30 s; no text
containing "If you forget this password". Set `Recovered-Test-Password3!` twice → finish → "Password Reset" within 60 s →
`setup.done`. Exactly 1 card, with the same text. Recovery Phrase with `fixturePassword` → "password is incorrect"; with
the new password → `recovery.reveal`.

#### G1.16 Rename, then deleting the last wallet returns to Welcome (UIT:720-769)

1 fixture. Card → `detail.connect.wallet` → "Pair Wallet", `connect.walletQR` within 20 s → back → title "Public fixture
1". For `detail.connect.bitcoin` and `detail.connect.tron` → "Pair Bitcoin" / "Pair TRON", with "Coin Level" selected →
back. `detail.rename` → dialog "Rename Wallet" → replace the text with "Travel Signer" → "Save" → title "Travel Signer".
`detail.delete` → `vaultManagement.submit` is disabled → switch `delete.backupConfirmed` on → submit → (App Lock is off,
so no biometric prompt, IS:68) dialog "Wallet Password" → `Wrong-Password1!` → "Delete" → "password is incorrect"; the
"Delete Wallet" sheet is still open → submit → `fixturePassword` → "Delete" → `welcome.create` within 30 s.

#### G1.17 App Lock: cold launch, password fallback, short background (UIT:774-803)

Launch with 1 fixture and dark appearance; remember the run id. Force-stop, then cold-launch with the **same run id**,
`--ez bts.uitest.appLock true`, dark, and no reset → "BitTool Signer Is Locked" within 15 s. The fake biometric fails
with its fixed message (G3), which the lock screen shows. The "Scan" tab is not reachable (absent from the
accessibility tree, app-shell §15.7). `appLock.usePassword` → "Wallet password" = `Wrong-Password1!` → `appLock.unlock`
→ "password is incorrect" within 30 s → `fixturePassword` → unlock → the lock is gone within 30 s. The first
`wallet.addresses.*` → "Addresses". Background and reactivate (well under 120 s) → still "Addresses" and not locked.

#### G1.18 Settings reference pages and guide (UIT:806-916)

1 fixture. "Settings" tab → `settings.appLock` is off. Tap it → the fake biometric's failure text appears and the switch
stays **off** (it turns on only after a successful biometric check).
- "Networks, Tokens & Contracts" → `network.solana` → title "Solana", at least one row → back → `network.ethereum` → "Ethereum"; search "tether" → "USDT" exists and "USD Coin" does not. Clear the search; the second segment (contracts) exists → tap → a row exists; search "permit2" → "Permit2" exists and "Seaport" does not. Back to the directory → `contracts.everyChain` → "All EVM networks", a row, **no** segmented control → back → back.
- "Compatibility" → background and reactivate → still "Compatibility". `compatibility.tab` → "Wallets": "says it supports", "Anyone can submit a wallet" and "BitTool is not responsible" exist; `directory.wallet.Sparrow` can be scrolled to; "sparrowwallet.com" is shown as text; **no link nodes** exist (on Android: no `ClickableSpan`/`LinkAnnotation` and nothing launching `ACTION_VIEW`).
- `directory.submit` → "Submit a Wallet": "support@bittool.com", "Add supported wallet", "Wallet name", "Official website", "App icon", "Source code" and "QR formats supported" → back → back.
- "Contact Us" → "BITTOOL PTE. LTD.", "support@bittool.com", no link → back.
- `settings.openSource` → "Open Source": the repository address (iOS asserts `https://github.com/bitbill/bittool-signer-ios`; Android: open question 1); "Signing engine"; a 64-hex-digit digest appears within 10 s (computed on the device); `openSource.notices` → "Third-Party Notices" → back → back.
- "Terms of Use" → "Version 1.1"; "Privacy Policy" → "collects nothing"; no text containing "open in your browser".
- No "Offline Setup Guide" button exists.

#### G1.19 Launch smoke (`BitToolSignerUITestsLaunchTests.swift:4-12`)

Cold launch with reset and 0 fixtures → the text "BitTool Signer" appears within 10 s; save one screenshot (FLAG_SECURE
off through the harness).

#### G1.20 Expected iOS-to-Android differences in this suite

- "Face ID was cancelled" (UIT:784, :813) → the Android fake's message (G3).
- The springboard permission alert (UIT:494) → the permission-controller dialog.
- The UIT:4 test-tooling problem (`typeText` dropping characters) does not exist on Android.
- Run time: Android derivation is now ≈ 0.65 s per wallet (DECISIONS §5), so fixture creation is far below the 150 s budget. Keep the budget anyway; CI emulators are slow.

### G2. Screenshot tour: port of `ScreenshotTour.swift`

Purpose (TOUR:3-5): capture every main screen for visual review, light and dark, with public fixtures only. A revealed
recovery phrase is never attached to test reports.

**Harness.** Same `:e2e` module, a class `ScreenshotTour` with `tourLight()` and `tourDark()`. The run needs the debug
harness flag `bts.uitest.allowCapture=true`, which clears FLAG_SECURE in debug builds only (DECISIONS §6); without it,
every PNG is black. Shots are written with `UiDevice.takeScreenshot(File)` to
`<test storage>/shots/<light|dark>-<name>.png` and pulled with `adb pull`. The directory is chosen by the instrumentation
argument `shotDir`; iOS uses `BITTOOL_SIGNER_SHOT_DIR`, TOUR:354. Extra arguments are passed through `extraArgs`, the
equivalent of `BITTOOL_SIGNER_EXTRA_ARGS` (TOUR:287); use it for `fontScale=2.0` (iOS AccessibilityL) or `locale=ar`.
"Sensitive" shots go to the shot directory only, never into test reports or CI artefacts.

**Sequence and names** (keep the names, so that iOS and Android shots pair up):

| Phase | Shots |
|---|---|
| Setup, 0 fixtures (TOUR:29-121) | `01-welcome`; `02-onboarding-1`, `03-onboarding-2`, `04-onboarding-3` (wait for "Page n of 3" and 1 s before each); `05-import-phrase` (the phrase minus its last 3 letters typed, then "out" appended); `06-password-confirm-focus` (password typed + IME action Next: the confirm field must scroll above the IME); `06-password`; `07-imported`; `08-wallets-first`; then `wallets.add` → "Create New Wallet": `09-create`; 12 words → `09b-backup-concealed`; QR tab `09c-backup-qr-tab`; `09d-backup-qr-revealed` (**sensitive**) → hide; Words → cards `09e-recovery-cards`, `09e-recovery-cards-revealed` (**sensitive**) → Done; tips `09f-security-tips` → Done; reveal → hide → continue → tap the first chip → `09g-verify-board` (**sensitive**); Cancel → Discard; `wallets.add` → "Import Recovery Phrase" → the Spanish phrase → `05b-import-spanish` → Cancel → Discard |
| Wallets, signing, activity, settings, 2 fixtures (TOUR:125-267) | `10-wallets`; card → `11-wallet-detail`, swipe → `12-wallet-detail-bottom`; `19a-change-password`, `19b-recovery-password`, `19c-delete` (each opened from detail, then Cancel); back; `wallet.connect.*` → `15c-connect-wallet-level`, swipe → `15d-connect-wallet-level-verify`; Coin Level → `13-connect-btc`, `14-connect-btc-bottom`; `15-connect-evm`; `15-connect-tron`, then 2 × increment of the `connect.tronIndex` stepper → `15b-connect-tron-index`; `15-connect-solana`; back; addresses `16-addresses-btc`, `17-addresses-evm`, `17c-addresses-tron`, `17b-addresses-solana`; EVM `address.row.1` → `18-address-detail` → Done; Scan `20-scan`; paste `21-paste` → decode → `22-review`, `23-review-middle`, `24-review-bottom`, ticked → `24b-review-acknowledged`; Sign → `25-authorize` → sign → `26-signature`, `27-signature-bottom` → Done; paste the known-spender request → `28-review-known-contract`, `29-review-known-contract-warnings` → Reject; Activity `30-activity`; row → `31-activity-detail`, `31b-activity-input-output`; decoded → `31c-activity-signed-request` → back; show QR → `31d-activity-signature-qr` → Done; Settings `40-settings`, `41-settings-bottom`; `42-networks`; `43-tokens-ethereum`; `44-tokens-solana`; `44b-tokens-tron`; Ethereum contracts segment → `44c-contracts-ethereum`; directory swipe → `44d-contracts-section`; `contracts.everyChain` → `44e-contracts-every-chain`; `45-compatibility`, `46-compatibility-bottom`; `46a-open-source`; `46b-terms-of-use`, scrolled until `https://bittool.com/signer` is visible → `46b2-terms-of-use-bottom`; `46c-privacy-policy`, `46c2-privacy-policy-bottom` (TOUR:339-344: the last block must clear the bottom navigation) |
| App Lock (TOUR:271-281) | cold launch with the same run id + `appLock` (+ dark) → `50-app-lock`; `appLock.usePassword` → `51-app-lock-password` |

**Device matrix** (iOS ran iPhone 17 Pro light and dark, iPhone SE light, iPad, and AccessibilityL, IS:331, :993): a
compact phone (≈ 360 × 640 dp), a large phone, a tablet (≥ 840 dp wide, so the readable-width rules apply), font scale
2.0, and one Arabic run (RTL is a product claim; this is an Android addition). Pair it with Roborazzi component
screenshots (ownbit-ui-patterns §11.2) for regressions; the tour is for human review.

### G3. Debug-only UI-test harness (Android replacement for `SimulatorUITestContext`)

iOS (`BTS/Services/SimulatorUITestContext.swift:1-63`) compiles the harness only under `DEBUG && simulator`. The Android
equivalent has the same contract.

| Behaviour | iOS | Android |
|---|---|---|
| Opt-in | launch argument `--bittool-signer-ui-tests` plus env `BITTOOL_SIGNER_UI_TEST_RUN=<uuid>` (:14-15) | launch-intent extras `bts.uitest.run=<uuid>`, read **before `super.onCreate()`** of `MainActivity`, so before Hilt injects anything, by `DebugUiTestHarness.configure(intent)` from `app/src/debug`. Release builds have no such class (a release check: R8 output contains no `DebugUiTestHarness`). Also require an emulator (`ro.kernel.qemu == "1"` or `ro.boot.qemu == "1"`), mirroring "simulator only" |
| Isolation | temp dir `BitToolSignerUITests/<uuid>`; UserDefaults suite `com.bittool.signer.ui-tests.<uuid>` (:16-17) | the store root is `cacheDir/ui-tests/<uuid>/` (Vaults/, PublicWallets/, receipts); a DataStore file named `ui-tests-<uuid>`. The production `noBackupFilesDir/BitToolSigner` and DataStore are never touched |
| Reset | `--reset-ui-fixtures` deletes the dir and the defaults domain (:18-21) | `bts.uitest.reset=true` deletes both |
| Device keys | file-backed fake `SimulatorFixtureDeviceKeys` under `FixtureKeys/` (:48-57) | a file-backed fake `DeviceKeyStore` plus a fake public-data HMAC key under `<root>/FixtureKeys/`, bound by a debug-only Hilt module. Keystore behaviour is covered by `:core:crypto` instrumented tests instead |
| Biometrics | always throws "Face ID was cancelled. Use a wallet password to continue." (:59-62) | always fails with `AppIssue("Biometric authentication was cancelled. Use a wallet password to continue.")`, debug-only English, not a string resource. App Lock's enable path and the lock screen show it |
| Preferences | `--app-lock-enabled` → lock on; `--dark-appearance` / `--light-appearance` (:25-27) | `bts.uitest.appLock=true`; `bts.uitest.appearance=dark` or `=light` |
| Fixtures | `BITTOOL_SIGNER_UI_FIXTURE_COUNT`, clamped 0…8; when > 0, sets `onboardingVersionCompleted = 1`; after the first load, only if no wallets exist, creates `Public fixture 1…N` from `publicRecoveryPhrase`, passphrase `""` for #1 and `"Public fixture <n>"` for the others, password `Public-Test-Password1!`; on failure, `store.error = "UI fixture setup failed: " + message` (:29-43) | `bts.uitest.fixtures=N` with identical semantics, created through the real `SignerStore.createWallet`, so the whole create path is exercised |
| Locale | `-AppleLanguages (en) -AppleLocale en_US` | `bts.uitest.locale` (default `en-US`), applied through `AppCompatDelegate.setApplicationLocales` before the first composition |
| Capture | n/a (the simulator captures freely) | `bts.uitest.allowCapture=true` leaves FLAG_SECURE off for this process (tour, G1.19) |
| Process model | each `XCUIApplication.launch()` is a new process | `am force-stop` before every launch. Extras delivered later by `onNewIntent` (a `singleTask` relaunch) are **ignored** |

Constraint for implementers: in harness mode, nothing that opens storage may run in `SignerApplication.onCreate`. Engine
`prewarm()` is fine because it touches no storage.

### G4. Overlay and tapjacking protection (Android-only threat)

No iOS counterpart exists: iOS has no third-party overlays. On Android, an app with `SYSTEM_ALERT_WINDOW` (or an
accessibility overlay) can draw over BitTool Signer to hide the amount or recipient on the review page, or to steer a tap
onto "I understand the risks of this request", "Sign", the delete confirmation or the App Lock switch. The review page
is the security boundary of the product (README:54; SEC:74-80), so the Android port must keep it intact.

1. **API 31+ (most devices): hide foreign overlays.** In `MainActivity.onCreate`, call `window.setHideOverlayWindows(true)`,
   and declare `<uses-permission android:name="android.permission.HIDE_OVERLAY_WINDOWS"/>` (normal protection level,
   granted at install, no prompt). While our window is visible, the system hides every `TYPE_APPLICATION_OVERLAY` window of
   other apps. System UI, the IME, permission dialogs and system toasts are unaffected. Apply it to every window we own:
   dialogs, sheets and the lock window (app-shell §15.7) get the same call on their `Window`. Android 12+ also blocks
   untrusted *pass-through* touches by itself; this call removes the remaining visual-deception case of a low-opacity
   overlay that lets touches through.
2. **API 26–30: refuse obscured touches on decisive controls.** A `Modifier.obscuredTouchGuard(onBlocked)` in
   `:core:designsystem`, built on `pointerInteropFilter`, inspects `MotionEvent.flags` for `FLAG_WINDOW_IS_OBSCURED` and,
   on API 29+, `FLAG_WINDOW_IS_PARTIALLY_OBSCURED`. If either is set, it consumes the `ACTION_DOWN` and calls `onBlocked`.
   Apply it to: `signing.risk`, `signing.authorize`, `authorize.sign`, `delete.backupConfirmed`, `vaultManagement.submit` (change, reset and delete), the delete dialog's "Delete", `setup.finish`, `settings.appLock`, `recovery.reveal`, `cards.reveal`, `backup.qr.reveal`, the "Clear Activity" confirmation, and `appLock.unlock`.
   Do **not** set `filterTouchesWhenObscured` on the root view: screen-dimmer and blue-light apps would make the whole app
   unresponsive with no explanation.
3. **Feedback.** On a blocked touch, show the status toast (design-system §5.10) with a new Android-only key in
   `strings_security.xml` (`security_overlay_blocked`): "Another app is drawing over BitTool Signer. Close it, then try
   again." It goes through the same translation pass as other Android-only keys.
4. **Allowlist.** Add `android.permission.HIDE_OVERLAY_WINDOWS` to the merged-manifest allowlist (G8).
5. **Tests.** Robolectric: the guard consumes a synthetic `MotionEvent` with `FLAG_WINDOW_IS_OBSCURED` and passes a clean
   one. Instrumented (manual, release checklist): a helper app showing a `TYPE_APPLICATION_OVERLAY` over the review page →
   the overlay disappears on API 31+, and taps are refused on API 30.

### G5. Accessibility services reading revealed secrets (Android-only threat)

SEC:131-133 lists "accessibility capture" as a residual risk. On Android it is concrete. Any accessibility service the
user enabled, including malware that tricked them into it, can read every on-screen text node and act on it. FLAG_SECURE
does not stop this. The specs make concealed words "not composed at all" (app-shell §15.8), but *revealed* words, the
phrase editor's text, a revealed passphrase and the verification chips are all readable.

1. **API 34+.** While a secret is on screen, mark the hosting view `accessibilityDataSensitive =
   View.ACCESSIBILITY_DATA_SENSITIVE_YES`. Then only services that declare `isAccessibilityTool` (TalkBack, Switch Access,
   Voice Access and similar) can query or act on it. Compose has no semantics property for this to our knowledge (verify
   against the pinned Compose BOM). Set it on `LocalView.current` from a `DisposableEffect(keyed on revealed)` and restore
   the previous value in `onDispose`. Dialogs and sheets are separate windows, so use their own `LocalView`. Where:
   - the revealed word grid (backup step 2 and the saved-wallet Recovery Phrase sheet);
   - revealed recovery cards;
   - the revealed recovery QR; also make sure its content description never contains words;
   - the verification board (its chips are real words, SEC:127);
   - the import phrase editor while it is non-empty;
   - a passphrase field while its eye toggle shows the text;
   - the authorize sheet.

   Password fields are already masked to accessibility.
2. **Below API 34** nothing equivalent exists; FLAG_SECURE, the immediate conceal on `onPause` and the
   reveal-only-while-resumed rule (app-shell §15.8) remain the protection. Document this in the Android Security.md.
3. **Verify** that UiAutomator, which the e2e suite uses (G1), can still read nodes marked sensitive. If it cannot, the
   debug harness (G3) must skip the marking when `bts.uitest.run` is set, in debug builds only.
4. Also verify on API 30+ that content capture (`View.setImportantForContentCapture(IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS)`
   on the root) and the assist structure do not expose text under FLAG_SECURE. If they do, set the content-capture exclusion
   on the root view app-wide; the app has no reason to take part in content capture.

### G6. User-visible strings that are not in the 773-key catalogue

`DECISIONS.md` §4 says "Every iOS string already exists as a resource in `:core:l10n`". It does not hold for the strings
below. iOS draws them in English in every language: plain `String`, `Text(verbatim:)`, engine-bridge errors or protocol
tokens. None of them is in `tools/l10n/strings-index.tsv`. The specs describe each one, usually marked *(V)* or "not
localized", but an implementer following DECISIONS §4 will not find a resource. Add them to the area's
`strings_<area>.xml` in English. Brand names, protocol tokens and identifiers get `translatable="false"`. The rest join
the Android-only translation pass, unless the owner decides they stay English (open question 2).

| String (verbatim English; `%@`/`%lld` = interpolation) | iOS source | Described in | Kind |
|---|---|---|---|
| The offline signing engine could not be started. / The engine’s secure entropy source could not be installed. / The bundled signing engine could not be loaded. / The offline engine could not process this request. / This request could not be signed. / This signing operation is not supported. / The scan session ended. Scan again. / The signing request has an invalid format. / This request exceeds the 1 MB offline signing limit. / The bundled signing engine is unavailable. Reinstall BitTool Signer. | `Core/SigningEngine.swift:20,62,67,82,88,152,173,188,192,210` | engine-contract §8.1; scan-sign §21 | errors → translate |
| Matching interrupted. Try again. · Setup required · Partial signature created · The encrypted wallet was deleted, but its public cache could not be removed. · The local activity record could not be read. · The local activity record could not be saved. Any completed signature remains available. | `Models/AppStore.swift:92,158,452,528,539,601` | models-state §5; activity-settings §2.2 | messages → translate |
| No matching wallet · Missing signer information · Ambiguous wallet match · Wallet setup required (no period; the key "Wallet setup required." with a period does exist) · Unsupported signing protocol · HIGH RISK · HIGH FEE | `Features/Sign/RequestReviewView.swift:81,140-148`; `AppStore.swift:373,388` | scan-sign §11.6–§11.7 | labels → translate. Since iOS ff2756f the four resolver messages (and "Invalid signing request") are catalogue keys, drawn through `ReviewText.phrase` |
| A QR code was found, but it doesn’t contain readable text. Export the request as a file instead. / This QR code is empty. Scan the signing request from your other wallet. / This QR code is too large. Use an animated QR code or export a request file. / This QR code doesn’t contain readable words. Compact SeedQR isn’t supported; use a QR code with the words or a Standard SeedQR, or type the words. / This QR code is empty. Scan a QR code that contains your recovery phrase. / This QR code is too large to be a recovery phrase. | `Features/Sign/QRScannerView.swift:214-219` | scan-sign §6.8 | scanner errors → translate |
| Reading request… · Keep the animated code in view · %lld parts · `<n>%` | `Features/Sign/ScanTab.swift:21-22`; `QRScannerView.swift:108` | scan-sign §6.4, §7 | progress → translate (the percentage via `NumberFormat`). Both texts are catalogue keys since iOS ff2756f |
| psbt_base64 is the exact PSBT this QR code carries; decoded is read from it for reference. | `Features/Sign/SignatureResultView.swift:81` | scan-sign §15 | note → translate |
| This QR code does not contain a recovery phrase. · Screenshot Detected · `%@: %@ …` (language reading row) | `Features/Setup/WalletSetupFlow.swift:226,235`; `RecoveryPhraseViews.swift:348` | setup-backup §7.1, §8.2 | translate (keep the reading row's format) |
| With a %lld-word phrase each card shows %lld of the %lld words, and the other %lld could be worked out with enough computing power. Guard every card as carefully as the full phrase, or use a 24-word phrase for split backups. · Empty, next to fill · Empty | `Features/Backup/RecoveryBackupViews.swift:214,483` | setup-backup §8.6, §8.8 | translate (a11y states too). The warning is a catalogue key since iOS ff2756f |
| Deleted wallet · Warnings shown · High | `Features/Activity/ActivityTab.swift:139,145` | activity-settings §2.4 | translate |
| Password changed (toast; differs from the key "Password Changed") | `Features/Wallets/WalletDetailView.swift:131` | wallets-pairing §5 | translate (or reuse "Password Changed") |
| Native SegWit · Taproot · Nested SegWit · Legacy · Standard · Trust · Account XPUB · Address · Ethereum & EVM · NFT contract (not verified) · Review request · +%lld more (see Details) · Payload (Hex) · Payload (Text) · Fingerprints are stored as 32-bit numbers: %@ is fingerprint %@. | `ConnectView.swift:8-20,253-254,305`; `Models.swift:19,244,287,383,606-607` | wallets-pairing §1.3, §7.3; models-state §1.4, §3.2.5 | product names (Taproot, Account XPUB) → owner's call; the rest → translate |
| This document is missing from the app bundle. · `Version ` (legal parser prefix, not displayed as such) | `Features/Settings/LegalDocumentView.swift:67,86` | activity-settings §10 | translate / parser token |
| Unlock with %@ · Lock BitTool Signer with %@. · Unlock BitTool Signer. Signing still requires the wallet password. · Face ID / Touch ID / Optic ID | `AppLockView.swift:50`; `AppAccessController.swift:39-41,134,144` | app-shell §14 | replaced by the Android-only biometric set (G7.4); "Unlock with %@" is a catalogue key since iOS f9092a7 and the Android button uses it |
| Protocol and format tokens: `crypto-account (account xpub), crypto-psbt, BBQR, Base64 PSBT`, `crypto-hdkey (EIP-4527), eth-sign-request / eth-signature`, `Address QR (any index), tron-sign-request / tron-signature`, `crypto-multi-accounts, sol-sign-request / sol-signature`, `bittool-signer-wallet v1 (ur:bytes, canonical CBOR): …` (the rest of the sentence is English), `bittool-signer-wallet v1 · ur:bytes (canonical CBOR) · %@ bytes`, `BBQr · PSBT (hex/Base32) · %@`, `Plain text · PSBT Base64 · %@`, `Plain text · PSBT hex · %@`, `Plain text · %@`, `ur:%@ · CBOR · %@`, `%@ bytes`, `BIP84 · bc1q…` etc. | `SettingsTab.swift:175-191,268`; `ConnectView.swift:8-15,463`; `Models.swift:613-619` | activity-settings §6.1; models-state §3.4 | tokens → `translatable="false"`; the "bytes" unit and the English tail of the `SettingsTab.swift:191` sentence → translate |
| Brand and entity names: BITTOOL PTE. LTD., support@bittool.com, Ethereum, BNB Smart Chain, BNB Chain, Arbitrum One, Arbitrum, Base, Solana, wallet names and platform labels (`iOS · Android`, `Desktop`, `Extension`…), language endonyms, BIP39 language names | `SettingsTab.swift:119-120`; `Logos.swift:13-25`; `CompatibleWallets.swift`; `AppLanguage.swift`; `BIP39Wordlist.swift:18-27` | design-system §5.17; models-state §9–§10; activity-settings §6 | `translatable="false"`; the platform labels (`Desktop`, `Extension`) are the owner's call |

Engine field labels and actions (the 145 engine-only keys of §1.6) are a different case: they **are** in the
catalogue and are reached through `ReviewText`.

### G7. Cross-spec conflicts and superseded statements: resolutions

DECISIONS wins wherever it speaks. These are the places where specs disagree with it or with each other.

**G7.1 Engine host (superseded by the working-tree DECISIONS §2/§5).** DECISIONS now specifies vendored QuickJS
2026-06-04 with an own JNI library (`bittoolsigner_engine`, `core/engine/src/main/cpp/signer_jni.c`). Strings pass in
and out as UTF-8 byte arrays. Engine runtimes are created without the Eval intrinsic (`JS_NewContextRaw` + explicit
intrinsics); only the compiler runtime parses. Entropy comes from bionic `arc4random_buf` (1…65,536 bytes per call).
Bytecode is compiled once in memory, `prewarm()` and `bundleDigest()` exist, and the measured timings are: verify +
compile ≈ 0.3 s, fresh runtime ≈ 40 ms, `derivePublicIndex` ≈ 0.65 s. Therefore these statements are stale:
- engine-contract §0 items 5 (the "BigNum 2024-02-14" wrapper, ≈ 7 s / ≈ 0.2 s) and 6 (modified UTF-8);
- the engine-contract §5.1 timing column;
- engine-contract §12.1 (`jniSafeSource`);
- engine-contract §12.3 (the `wang.harlon.quickjs:wrapper-android:3.2.3` API);
- engine-contract §12.4 (the "ASCII-only transport required" rationale: no longer required; keep it only if cheap);
- engine-contract §12.6 (`libquickjs-android-wrapper.so`);
- the engine-contract §12.7 numbers;
- ownbit-build-release §0 item 9, the §2.5 table row, §3.4 `keepDebugSymbols` and §7.2 step 11 ("QuickJS ships stripped libraries"; Play's missing-symbol warning now depends on the own `.so`, so ship native debug symbols);
- models-state §13 ("replaced by QuickJS (`wang.harlon…`)");
- wallets-pairing §13.4.

Still valid and required: the V-UNICODE golden test (engine-contract §11.2), the 16 KB alignment check (now on
`libbittoolsigner_engine.so`), every protocol, allowlist, limit and lifecycle rule, and "never call the engine from the
main thread". The UX consequence: create, import, repair, reset and sign stay below about 1–2 s plus PBKDF2 (vault-crypto
§17.6), so the busy-spinner design of the specs is enough. No progress copy is needed.

**G7.2 Activity journal format.** vault-crypto §17.8 and models-state §12.3 specify
`noBackupFilesDir/BitToolSigner/receipts.json` as plain JSON (iOS parity), written by the shared `.pending-<uuid>` +
`rename` helper, with `AtomicFile` explicitly avoided. activity-settings §14.2 specifies `receipts.bin`, sealed with
AES-GCM under a Keystore key (`setUnlockedDeviceRequired`), and written with `AtomicFile`. **Resolution
(recommended):** use the activity-settings design for confidentiality. It is the Android equivalent of iOS
`completeFileProtection`, which makes the file unreadable while the device is locked; Android credential-encrypted
storage alone is readable after first unlock. Use the vault-crypto §17.8 atomic-write helper and read guards, so that one
write discipline exists. File name `receipts.bin`, magic and version as in activity-settings §14.2, all iOS limits applied
to the plaintext. Key alias `com.bittool.signer.activity.v1`. Key loss → the "The local activity record could not be read."
path, and the file is never overwritten silently (SEC:117). The Privacy-text adaptation (activity-settings §10) must say
the record is encrypted on the device. If the owner prefers strict parity, keep `receipts.json`. Decide once and update
the other two specs.

**G7.3 Module and store names.** Map to DECISIONS §3:

| Name used in a spec | Use instead |
|---|---|
| `:core:bip39` (setup-backup §15.1) | `:core:crypto` (BIP39 is listed there; wordlists are already at `core/crypto/src/main/assets/bip39/`, not Java resources, so JVM unit tests read them from the source path or use Robolectric) |
| `:core:jsengine` (setup-backup §15.1) | `:core:engine` |
| `:core:vault` (setup-backup §15.1, vault-crypto §17.1, models-state §12.1) | `:core:crypto` for `VaultCrypto`, `DeviceKeyStore` and attempts sealing; `:core:data` for `WalletVault` file orchestration and `PublicWalletRepository` ("wallet repositories") |
| `:feature:backup` (setup-backup §15.1) | `:feature:setup`, which DECISIONS gives "backup … reveal-phrase flow". The saved-wallet Recovery Phrase sheet is that reveal flow, reached from `:feature:wallets` through a `:core:navigation` route. Composables used by both (word grid, `ProtectedRecoveryPhrase`, `RecoveryBackupPanel`) go to `:core:ui` |
| `:core:signing` (scan-sign §23.1), `SigningStore`; `WalletStore` (setup-backup §15.1) | the single `SignerStore` in `:core:data` (DECISIONS §7) |
| `:core:datastore` (activity-settings §14.1) | preferences in `:core:data` (DataStore) |
| `:core:publicsigner` (ownbit-ui-patterns) | Ownbit-only; no signer module |

**G7.4 Android-only App Lock wording.** app-shell §14/§15.6 and activity-settings §3.1/§12.3 propose different
replacements for the `%@`-with-Face-ID keys. Use **one** set; this one is recommended because "App Lock" is already the
catalogue's term ("Create a wallet before enabling App Lock.", "Authenticate app access before changing App Lock."):
- toggle title "App Lock";
- footer when available: "Require biometric unlock to open BitTool Signer at launch and after 2 minutes in the background. Every signature still needs the wallet’s password.";
- footer when unavailable: "Set up fingerprint or face unlock in Android Settings to lock BitTool Signer. Every signature always needs the wallet’s password.";
- lock-screen button "Unlock with Biometrics" (since iOS f9092a7: the catalogue's "Unlock with %@" filled with "Biometrics");
- "Use Biometrics Instead";
- prompt titles "Unlock BitTool Signer" and "Lock BitTool Signer", with the description "Signing still requires the wallet password.";
- errors: "Biometric authentication is unavailable. Check fingerprint or face unlock in Android Settings, or use a wallet password." and "Biometrics are locked after too many attempts. Use a wallet password, or unlock your phone with its screen lock first.";
- delete footer: "Deleting asks for biometric confirmation, then for this wallet’s password.";
- enable prompt negative button "Cancel"; unlock prompt negative button "Use a Wallet Password" (an existing key).

Drop activity-settings' "Biometric Lock", "Require biometrics…" and "Set up a fingerprint…".

**G7.5 Asset paths.** The scaffold layout is canonical (G8). activity-settings §9 (`unzip -p base.apk assets/signer.js`)
and §14.6 (`assets.open("signer.js")`) are wrong. Use `assets/engine/signer.js`, and on the Open Source page call
`SignerEngine.bundleDigest()` (DECISIONS §5) instead of hashing the asset again. models-state §12.5
`assets.openFd("TokenIcons.pack")` → `"registry/TokenIcons.pack"`.

**G7.6 Merged-manifest permission allowlist.** ownbit-build-release §7.3 allows only `CAMERA` (+ androidx's
`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`). app-shell §15.8 requires `DETECT_SCREEN_CAPTURE` (API 34) and
`DETECT_SCREEN_RECORDING` (API 35). DECISIONS §6 uses `androidx.biometric`, which brings `USE_BIOMETRIC` (and
`USE_FINGERPRINT` for API < 28). G4 adds `HIDE_OVERLAY_WINDOWS`. The canonical list is in G8; update the task.

**G7.7 Engine-text lookup.** models-state §12.4 proposes bundling `assets/translations/<code>.json` as a runtime map.
DECISIONS §4 specifies `IosStrings.lookup(english)` → resource id over the generated `strings_ios.xml`. Follow DECISIONS:
no translation JSON in assets, one source of truth, and the per-app locale applies automatically. A missing entry
returns the English unchanged in both designs.

### G8. Canonical asset layout and permission allowlist

**Assets** (verified byte-identical with `BTS/Resources` on 2026-10-20; keep the engine digest test and add a digest
test for the data files):

| Module | Asset path | Notes |
|---|---|---|
| `:core:engine` | `assets/engine/signer.js` | `noCompress += "js"` |
| `:core:data` | `assets/registry/token-registry.json`, `assets/registry/known-contracts.json`, `assets/registry/TokenIcons.pack`, `assets/registry/TokenIcons.index.json` | `noCompress += "pack"` (mmap) |
| `:core:data` | `assets/legal/TERMS_OF_USE.txt`, `assets/legal/PRIVACY_POLICY.txt`, `assets/legal/THIRD_PARTY_LICENSES.txt` | the per-flavor Android texts (activity-settings §10) override these from `app/src/{apk,play}/assets/legal/` (app assets take priority over library assets); add the Android dependency notices as a second file, e.g. `assets/legal/THIRD_PARTY_LICENSES_ANDROID.txt` |
| `:core:crypto` | `assets/bip39/bip39-<raw>.txt` (10 files) | SHA-256 pinned (vault-crypto §13.1) |

**Permissions** (merged manifest of every variant must equal this set):

| Permission | Why | Source |
|---|---|---|
| `android.permission.CAMERA` | scanning | scan-sign §23.5 |
| `android.permission.USE_BIOMETRIC` (+ `USE_FINGERPRINT`, merged by androidx.biometric) | App Lock | DECISIONS §6 |
| `android.permission.DETECT_SCREEN_CAPTURE` | screenshot callback, API 34 | app-shell §15.8 |
| `android.permission.DETECT_SCREEN_RECORDING` | recording callback, API 35 | app-shell §15.8 |
| `android.permission.HIDE_OVERLAY_WINDOWS` | G4 | this file |
| `<applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | added by androidx.core | ownbit-build-release §7.3 |

`INTERNET` and `ACCESS_NETWORK_STATE` stay `tools:node="remove"`, as in the scaffold manifest. The scaffold manifest
does not yet declare the four new permissions.

---

## 3. Remaining open questions

1. **Open Source page repository address.** iOS shows `https://github.com/bitbill/bittool-signer-ios` (SettingsTab.swift:229) and the UI test asserts it. Will the Android source be published (repository URL), and does the page show both repositories? The README's claim that the installed build can be checked against the source needs an Android repository.
2. **The ~60 non-catalogue strings (G6):** translate them on Android (polish) or keep them English for strict parity? This affects the size of the translation pass.
3. **Activity journal (G7.2):** encrypted `receipts.bin` (recommended) or plain `receipts.json`?
4. **FLAG_SECURE makes the screenshot-warning flows mostly unreachable on Android** (app-shell §15.8 asked product to confirm). Keep the warning texts in English with "Photos" wording, or reword for Android ("…delete it from your gallery and any synced copies…")? This needs new keys if reworded.
5. **Android legal texts** (activity-settings §10): the legal owner must write `apk` and `play` versions; the parser, structure and the legal-document tests stay.
6. **Entropy source.** The original brief said "SecureRandom exactly like iOS"; the working-tree DECISIONS chose `arc4random_buf`. Both are kernel-CSPRNG backed. Confirm, and keep the Taproot "two signatures differ" vector (engine-contract §0 item 4).
7. **Overlay and accessibility hardening (G4/G5)** go beyond iOS behaviour. Confirm that both are wanted, and confirm the new permission and the `strings_security.xml` key.
8. **`:e2e` module (G1–G3)** needs `settings.gradle.kts` and catalogue changes (shared files): assign the task to the orchestrator.
9. **Third-party wallet round trips** (IS:1011-1015: Sparrow, BlueWallet, Nunchuk, Keeper, BULL, Specter, Bitcoin Safe, ZEUS, MetaMask, Rabby, imToken, TokenPocket, Ambire, Solflare, Helium Wallet, plus the watch-only imports) are not in the Android release checklist (ownbit-build-release §7.2). Add them as a pre-1.0 acceptance item, run against the Android signer.
10. **README staleness.** README:62 still lists "the air-gapped device guide" under Settings; IS:428 removed it, and the Android port follows the code (onboarding only).
