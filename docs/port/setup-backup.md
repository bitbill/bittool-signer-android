# Port spec: wallet creation, import and recovery-phrase backup

Status: authoritative port spec for the Android implementers of BitTool Signer.
Scope: everything between the "Create New Wallet" / "Import Existing Wallet" (or "Add wallet") entry points and the moment the setup flow closes, plus the recovery-phrase backup components (words, QR code, recovery cards, security tips and backup check). The backup components are also used by a saved wallet's "Recovery Phrase" sheet (`Features/Wallets/WalletSecuritySheets.swift:36-124`), so they must be built once and reused.

All Swift paths below are relative to `/Users/tanguopeng/Documents/bitsigner-ios/BitSigner/BitToolSigner/` unless stated otherwise. `path:line` refers to that tree as of 2026-10-20.

**Source of truth:** the Swift code. The PNG mocks in `design/` predate the current flow (they still say "vault", show a reveal confirmation alert and a three-choice verification grid that no longer exist). Section 13 says what to take from them and what to ignore. `docs/Security.md:113-133` describes the security intent of this flow. Where it disagrees with the code, section 16 lists the difference.

---

## 0. Glossary and conventions

| Term | Meaning |
|---|---|
| **phrase / mnemonic** | BIP39 recovery phrase. Generated phrases are always English. Imported phrases may be in any of the 10 BIP39 languages and are stored as the canonical NFC words. |
| **passphrase** | Optional BIP39 passphrase (the "25th word"). This is *not* the wallet password. |
| **wallet password** | The password that encrypts the local vault (PBKDF2-SHA256 600 000 iterations, AES-GCM, see the vault spec). It authorizes every signature. |
| **securityEpoch** | A token the store changes on every `suspend()`: app backgrounded, App Lock engaged, or screen capture started (`Models/AppStore.swift:86-94`, `BitToolSignerApp.swift:41`, `Features/Main/RootView.swift:60-61`). Views and models compare against it to drop late results and to reset sensitive UI. |
| **sensitiveEpoch** | A token the store changes on `beginSensitiveSession()` / `endSensitiveSession()` (`Models/AppStore.swift:84-85`). A generated phrase that arrives after the setup flow closed or re-opened is discarded. |
| **String keys** | Every user-visible English string below is quoted **verbatim**. It is also the localization key in `translations/*.json` (20 files plus English as the key, 773 strings each). Swift `%lld` maps to Android `%d`, `%@` to `%s`, and the translations' positional `%1$@` to `%1$s`. Typographic characters are part of the key: ’ (U+2019), “ ” (U+201C/D), – (U+2013), · (U+00B7), … (U+2026). A plain ASCII `'` must be escaped as `\'` in `strings.xml`. |
| **Not localized on iOS** | A small number of strings are built as plain Swift `String`s and are shown in English in every language. They are flagged ⚠︎L and listed in §14.3. |

---

## 1. Flow host (`Features/Setup/WalletSetupFlow.swift:3-82`)

### 1.1 Presentation

- `AppRouter.startSetup(intent, firstWallet:, introductionCompleted:)` creates a `SetupPresentation { id, intent: .create | .restore, showsIntroduction = firstWallet && !introductionCompleted }` (`Features/Main/AppRouter.swift:23-45`).
- `RootView` presents it as a **sheet** (`Features/Main/RootView.swift:41-46`). `existingWallets: store.wallets.count` is captured when the sheet is created.
- Entry points: Welcome's "Create New Wallet" / "Import Existing Wallet" (`Features/Onboarding/WelcomeView.swift:41-46`). Other entry points from the Wallets tab are in the wallets spec. All of them call `startSetup`.

### 1.2 Introduction gate

- `introductionDone` starts as `!presentation.showsIntroduction` (`WalletSetupFlow.swift:16`).
- If the introduction is shown, `OnboardingView(intent:)` appears first (`WalletSetupFlow.swift:36-42`; content in `Features/Onboarding/WelcomeView.swift:118-245`, summarized in Appendix A).
  - "Get Started" on the last page sets `@AppStorage("onboardingVersionCompleted") = 1`, then switches to the flow with `withAnimation(.smooth)`. The flow enters with `.move(edge: .trailing)` combined with `.opacity`, and the intro leaves with `.opacity`.
  - Cancel on the intro dismisses the whole sheet.
- Android: the intro is a destination at the start of the setup graph, shown only when the same condition holds. Persist `onboardingVersionCompleted` in DataStore (it is not secret).

### 1.3 Navigation stack

- `NavigationStack(path: $model.path)`. The root is `CreateWalletStep` for `.create` or `ImportWalletStep` for `.restore`. Pushed steps are `WalletSetupModel.Step`: `.backup`, `.verify`, `.password`, `.done` (`WalletSetupModel.swift:9`, `WalletSetupFlow.swift:71-81`).
- **The model owns the path**, and transitions replace it wholesale (`path = [.backup]`, `path = [.password]`) or append (`path.append(.verify)`). Android must keep this: the ViewModel owns `List<Step>` (see §15.4).

| Intent | Sequence (step numbers as shown on screen) |
|---|---|
| create | Root "Step 1 of 4" → `.backup` "Step 2 of 4" → `.verify` "Step 3 of 4" → `.password` "Step 4 of 4" → `.done` |
| restore | Root "Step 1 of 2" → `.password` "Step 2 of 2" → `.done` |

### 1.4 Toolbar, Cancel and discard

- The root step shows **"Cancel"** in the leading (`.cancellationAction`) slot. Pushed `.backup`, `.verify` and `.password` steps keep the system back button on the leading side and show **"Cancel"** on the trailing side (`WalletSetupFlow.swift:60-69, 73-75`). The `.done` step has no Cancel, and its back button is hidden (`WalletSetupFlow.swift:448`).
- Cancel is disabled while `model.busy`.
- Cancel with `model.hasDraftSecrets == true` opens a confirmation dialog. Otherwise it dismisses straight away.
- Confirmation dialog (`WalletSetupFlow.swift:46-53`), title always visible:
  - Title: "Discard this wallet?"
  - Destructive button: "Discard". It calls `model.clearSecrets()`, then dismisses.
  - Cancel-role button: "Keep Editing"
  - Message for create: "The recovery phrase shown here will be erased. If you already wrote it down, it will not be used."
  - Message for restore: "The words you entered will be erased from this screen."
- Swipe-to-dismiss of the sheet is disabled while `hasDraftSecrets || busy || created != nil` (`WalletSetupFlow.swift:45`). Once a wallet exists, the only way out is the Done step's buttons.

### 1.5 Host lifecycle hooks (`WalletSetupFlow.swift:54-57`)

| Event | Action |
|---|---|
| appear | `store.beginSensitiveSession()` |
| disappear (sheet closed for any reason) | `model.clearSecrets()`, then `store.endSensitiveSession()` |
| `store.securityEpoch` changed (background / App Lock / capture) | `model.suspend()` |
| `store.navigationResetEpoch` changed (a wallet was deleted elsewhere) | `model.clearSecrets()` if `created == nil` |

### 1.6 Finishing

`SetupCompleteStep` calls `router.finishSetup(opening: route)`. That stores `route` and sets `setup = nil`, which closes the sheet. When the dismissal animation completes, `setupDidDismiss()` opens the route: Wallets tab, `walletPath = [route]` (`AppRouter.swift:48-57`). "Pair a Wallet" passes `.connectWallet(walletID)`, which is Connect on the wallet-level QR. "Done" passes `nil`. Android: pop the setup graph first, then navigate to Connect (avoids the stuck-transition problem the iOS code works around).

---

## 2. `WalletSetupModel` — state and state machine (`Features/Setup/WalletSetupModel.swift`)

One instance per presentation. It is the only owner of secrets during setup, and it holds them in memory only.

### 2.1 Fields

| Field | Type / initial | Secret | Notes |
|---|---|---|---|
| `intent` | `.create` / `.restore` | – | |
| `path` | `[Step]` = `[]` | – | |
| `name` | `"My Wallet"` if `existingWallets == 0`, else `"Wallet %lld"` with `existingWallets + 1` (`:40-44`) | – | Localized when the model is created. |
| `wordCount` | 24 | – | Create only: 24 or 12. |
| `usePassphrase` | false | – | |
| `passphrase`, `passphraseConfirmation` | "" | **yes** | Used exactly as typed (no trim). Ignored while `usePassphrase == false` but kept in memory (`usePassphrase ? passphrase : ""`). |
| `phraseInput` | "" | **yes** | Import editor text. |
| `mnemonic` (private set) | "" | **yes** | The canonical phrase: generated, or accepted on import. |
| `importedLanguage` | nil | – | Language of an accepted import. |
| `languageChoices` | `[]` | **yes** (shows 3 words) | Several valid readings → language picker. |
| `scannedPhrase` (private) | nil | **yes** | Exact text from a QR code, used while the editor is unedited. |
| `hasRevealedBackup` | false | – | Set once the words have been shown (grid or cards; **not** the QR). Not reset by re-concealing. |
| `verification` | nil | **yes** (chips are real words) | `BackupVerification`. |
| `password`, `confirmation` | "" | **yes** | Wallet password. |
| `busy` (private set) | false | – | |
| `error` | nil | – | Shown as a danger Callout in the footer. |
| `created` (private set) | nil | – | `VaultMetadata` of the created or reset wallet. Marks success. |
| `duplicateOf` (private set) | nil | – | UUID of an existing wallet this phrase and passphrase derive. Switches the password step into "Reset Password" mode. |
| `didResetPassword` (private set) | false | – | |
| `operation` (private) | UUID | – | Token. A late async result is applied only if the token is unchanged. |

Derived values (`:46-52`):
- `words = mnemonic.split(" ")`
- `hasDraftSecrets = created == nil && (!mnemonic.isEmpty || !phraseInput.isEmpty || !passphrase.isEmpty || !password.isEmpty)`. Note that `confirmation` and `passphraseConfirmation` do not count.
- `trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)`
- `passwordValid = PasswordPolicy.evaluate(password).isValid && password == confirmation`
- `passphraseValid = !usePassphrase || (!passphrase.isEmpty && passphrase == passphraseConfirmation)`

### 2.2 Transitions

| Action | Guard | Effect |
|---|---|---|
| `startCreate(store)` (`:56-67`) | `validateOptions()` | Runs through `perform`. A phrase is generated **only if** `mnemonic.isEmpty \|\| words.count != wordCount`: `store.generateMnemonic(wordCount:)`. If the token still matches, it sets `mnemonic`, `hasRevealedBackup = false` and `verification = nil`. In every case it then sets `path = [.backup]`. So going back to step 1 and pressing Create again keeps the **same** phrase unless the word count changed. |
| `startImport(store)` (`:69-88`) | `validateOptions()` | Synchronous, not run through `perform`. (1) If `scannedPhrase == phraseInput` and `BIP39Mnemonic.validatedCanonical(scannedPhrase)` succeeds (exact spellings only), it calls `acceptImport(that)` and returns. (2) Otherwise it calls `BIP39Mnemonic.interpretations(phraseInput)`: one reading → `acceptImport`; more than one → `languageChoices = readings` (opens the picker). A thrown error sets `error = localizedDescription`. |
| `acceptImport(reading)` (`:90-98`) | – | Sets `languageChoices = []`, `mnemonic = reading.phrase`, `importedLanguage = reading.language`, `error = nil`, `duplicateOf = nil`, then `path = [.password]`. |
| `applyScanned(phrase)` (`:101-105`) | – | Sets `scannedPhrase = phrase`, `phraseInput = phrase` and `error = nil`. |
| `confirmBackup()` (`:108-117`) | `hasRevealedBackup && !words.isEmpty` | Sets `verification = try BackupVerification(words:)`, `error = nil`, then `path.append(.verify)`. A thrown error sets `error` ("A backup check requires a supported BIP39 phrase length."). |
| `place(chipID)` / `clear(position)` (`:119-127`) | – | Mutates `verification` and sets `error = nil`. |
| `select(position)` (`:129-131`) | – | Mutates `verification`. Does not clear the error. |
| `finishVerification()` (`:133-141`) | – | If `verification?.isCorrect != true`: `error = "Some words don't match. Check your written backup and try again."` and `verification.resetPlacements()`. The same blanks and chip order are kept. Otherwise `error = nil` and `path.append(.password)`. |
| `reviewPhraseAgain()` (`:143-146`) | – | Sets `verification = nil`, `error = nil`, `duplicateOf = nil`, then `path = [.backup]`. The phrase stays the same and remains concealed. `hasRevealedBackup` stays true. |
| `checkForExistingWallet(store)` (`:150-161`) | `!mnemonic.isEmpty && created == nil && duplicateOf == nil && !busy` | Runs when the password step appears (`.task`, `WalletSetupFlow.swift:398`). It calls `store.existingWallet(mnemonic:passphrase:)`. If `operation`, `securityEpoch` and `created == nil` are all unchanged, it sets `duplicateOf = existing`. Failures are ignored, because `createWallet` refuses duplicates on its own. |
| `resetPassword(store)` (`:163-174`) | `duplicateOf != nil && passwordValid && !mnemonic.isEmpty` | Through `perform`: `store.resetPassword(walletID:mnemonic:passphrase:newPassword:)`, then `created = store.wallet(walletID)`, `didResetPassword = true`, `clearSecrets()`, `path.append(.done)`. |
| `createWallet(store)` (`:176-188`) | `passwordValid && !mnemonic.isEmpty` | Through `perform`: `store.createWallet(name: trimmedName, mnemonic:, passphrase: usePassphrase ? passphrase : "", password:)`, then `created = wallet`, `clearSecrets()`, `path.append(.done)`. There is **no token check after the commit**: "A committed wallet is the success boundary, even if the app was backgrounded while saving" (`:181-182`). |
| `suspend()` (`:192-196`) | – | New `operation` token, `busy = false`, `password = ""`, `confirmation = ""`, `error = nil`, `verification?.resetPlacements()`. **Kept:** mnemonic, phrase input, name, passphrase, `hasRevealedBackup`, path. |
| `clearSecrets()` (`:198-204`) | – | New token, `busy = false`. Clears mnemonic, importedLanguage, languageChoices, scannedPhrase, phraseInput, passphrase, passphraseConfirmation, password, confirmation and verification. Sets `hasRevealedBackup = false` and `duplicateOf = nil`. It leaves `path`, `name`, `created` and `didResetPassword` unchanged. |

`validateOptions()` (`:206-212`) checks in this order:
1. `trimmedName.isEmpty` → "Enter a wallet name."
2. `trimmedName.count > 40` → "Use a wallet name of 40 characters or fewer." (`count` counts grapheme clusters, see §15.11)
3. `!passphraseValid` → "The BIP39 passphrases must match and cannot be empty."
4. Otherwise `error = nil`.

`perform(store, work)` (`:214-229`):
- Returns at once if `busy`.
- Otherwise: new `id`, `epoch = store.securityEpoch`, `operation = id`, `busy = true`, `error = nil`.
- Inside the task: if the epoch already changed it throws Cancellation, otherwise it awaits `work(id)`.
- On error, `self.error = error.localizedDescription` only if `operation == id`, the epoch is unchanged and the error is not a cancellation.
- At the end, `busy = false` only if `operation == id`.

### 2.3 Where secrets go and when they are cleared

| Trigger | password/confirmation | verification placements | phrase / input / passphrase | concealed displays |
|---|---|---|---|---|
| App resigns active (Control Center, notification shade, app switcher) | kept | kept | kept | **re-concealed** (privacy session) |
| App enters background → `store.suspend()` | **cleared** | **cleared** | kept | re-concealed. The backup panel is rebuilt (format resets to Words, cards and tips sheets close) |
| Screen capture starts → `store.suspend()` | cleared | cleared | kept | re-concealed, and no reveal is allowed while capture is on |
| App Lock engages → `store.suspend()` | cleared | cleared | kept | re-concealed |
| Screenshot | – | **cleared** on the verify step | kept | re-concealed and warning shown |
| Discard / sheet closed / process death | all cleared | all cleared | all cleared | – |
| Wallet committed | all cleared (`clearSecrets`) | – | – | – |

---

## 3. Store and engine calls (`Models/AppStore.swift`)

Engine envelope (`Core/SigningEngine.swift:146-199`): the request is `parameters` plus `"operation": <name>`, serialized with sorted keys and limited to 1 MB (`maximumRequestBytes`). The response is `{"ok": true, "result": {...}}` or `{"ok": false, "error": {"code", "message"}}`. The message is truncated to 400 characters and shown verbatim in the Callout. A non-object `result` is wrapped as `{"value": result}`. Both operations used here run in a **fresh context** (they are not in `warmOperations`).

| Store function | Engine operation | Exact JSON sent | Result fields consumed |
|---|---|---|---|
| `generateMnemonic(wordCount:)` (`:160-168`) | `mnemonic` | `{"entropy": "<hex>", "operation": "mnemonic"}`. `entropy` is `SecureRandom.entropyHex(wordCount)`: `wordCount/3*4` random bytes, lowercase hex. 12 words → 16 bytes (32 hex chars), 24 words → 32 bytes (64 hex chars) (`Core/VaultCrypto.swift:59-62`). Supported counts are 12, 15, 18, 21 and 24; anything else is `VaultError.invalidSecret`. | `result["mnemonic"]` as String, falling back to `result["value"]`. Missing → "Unable to generate recovery phrase.". `wordCount` in the result is ignored. The engine always uses the English list (`engine/src/keys.js:16`). After the call: `requireSession(epoch)` and `sensitive == sensitiveEpoch`, otherwise Cancellation. |
| `createWallet(name:mnemonic:passphrase:password:)` (`:178-211`) | `derivePublicIndex` | `{"operation": "derivePublicIndex", "seed": "<128 lowercase hex>"}`. The seed comes from `BIP39Seed.hex(mnemonic: canonical, passphrase:)`, computed **natively** (§11.6). | The entire object is validated by `WalletPublicMetadata(raw)` (`Models/Models.swift:477-502`): `version == 1`, `walletKeyId` (32-byte hex), `masterFingerprint` (4-byte hex), `bitcoinPublicNodes` (6, fixed origin paths), `evmAccountNode` (`m/44'/60'/0'`), `tronAccountNode` (`m/44'/195'/0'`), `tronAddressIndex` (100), `solanaPublicKeys` (3), public-only. `walletKeyId` is used for the duplicate check. The object is persisted as the wallet's public sidecar. |
| `existingWallet(mnemonic:passphrase:)` (`:484-491`) | `derivePublicIndex` | Same as above. | `walletKeyId` is compared with every loaded `publicWallets[*].walletKeyId`. Returns that wallet's UUID or nil. Nothing is written. |
| `resetPassword(walletID:mnemonic:passphrase:newPassword:)` (`:498-512`) | `derivePublicIndex` | Same as above. | `derived.walletKeyId` must equal `publicWallets[walletID].walletKeyId`. |

**Never send `mnemonic` to the engine.** The engine's own `withSeed` only accepts the English wordlist (`engine/src/keys.js:17-22`), so Japanese, Chinese and other imports would fail. The host always derives the seed natively and sends `seed`, as iOS does.

`createWallet` order (`:178-211`). Every step can throw, and the message reaches the footer Callout:
1. A re-entrancy guard (`creatingWallet`) → "A wallet is already being saved."
2. `passphrase.utf8.count <= 1024` → "The BIP39 passphrase exceeds 1,024 UTF-8 bytes."
3. `validateMnemonic` = `BIP39Mnemonic.validatedCanonical(mnemonic).phrase`. Exact spellings only; the stored form is never reinterpreted.
4. `derivePublicIndex(seed)`, then `requireSession(epoch)`, then `WalletPublicMetadata(raw)`.
5. `reload()`, then `requireSession(epoch)`. If `!walletLoadSucceeded` → "The existing wallet collection could not be read. Retry before adding a wallet."
6. Duplicate `walletKeyId` → "This recovery phrase and passphrase already belong to an existing wallet."
7. `vault.publicDataKey(createIfMissing: true)` (device key; nil → `VaultError.deviceKeyUnavailable`). Then `publicRepository.save(index, walletID: newUUID, displayName: trimmedName)`. The sidecar is written first and stays invisible until a vault exists.
8. `requireSession(epoch)`, then `vault.create(name:mnemonic:passphrase:password:id:)` (`Core/WalletVault.swift:96-115`). On failure the sidecar is removed and the error rethrown.
9. `reload()` and return the metadata. **This is the success boundary.**

`resetPassword` (`:498-512`):
- No public index for the wallet → "This wallet needs repair before its password can be reset."
- The derived key differs → "This recovery phrase belongs to a different wallet."
- Then `vault.resetPassword` (`Core/WalletVault.swift:176-186`; validates the policy again and passphrase ≤ 1024 bytes, re-encrypts under a new salt, clears the failed-attempt counter).
- Then `store.suspend()`. This changes `securityEpoch`, so the flow's `model.suspend()` also runs. The model's success path has no token check, so it still sets `created` and pushes `.done`.
- Then `reload()`.

Errors that can come from the vault during create or reset (`Core/VaultCrypto.swift:24-43`) and appear verbatim in the footer:
- "Choose a wallet name of 1–40 characters."
- "The recovery phrase is empty or too large."
- "Secure randomness is unavailable. Try again after unlocking your device."
- "The password key could not be created."
- "The encrypted wallet could not be created."
- "This wallet’s device key is unavailable. Restore your recovery phrase on this device."
- "Set an iPhone passcode before creating a wallet. The device key requires passcode protection." (Android needs its own wording; see the vault spec.)
- "The encrypted wallet could not be read or saved."
- `invalidPassword(message)`: the PasswordPolicy message.

Plus "Invalid public wallet data. Repair this wallet." and "Public wallet data is incomplete or inconsistent." (`Models/Models.swift:486, 498`).

---

## 4. Shared visual building blocks (`Design/DesignSystem.swift`)

| Token | iOS value | Android |
|---|---|---|
| `Palette.background` | `systemGroupedBackground` (light #F2F2F7, dark #000000) | M3 `surfaceContainerLow` / `background`. Keep the grouped look: a light gray page with white cards. |
| `Palette.card` | `secondarySystemGroupedBackground` (light #FFFFFF, dark #1C1C1E) | `surfaceContainerLowest` (light) / `surfaceContainer` (dark) |
| `Palette.raised` | `tertiarySystemGroupedBackground` (light #F2F2F7, dark #2C2C2E) | `surfaceContainerHigh` |
| success / warning / danger | systemGreen #34C759/#30D158, systemOrange #FF9500/#FF9F0A, systemRed #FF3B30/#FF453A | Custom semantic colors (M3 has only `error`). |
| `systemYellow` (strength 2) | #FFCC00 / #FFD60A | Custom |
| `systemFill` (empty bars, concealed placeholders) | rgba(120,120,128,0.20) light / 0.36 dark | `onSurface` at 12–16% |
| Accent | emerald **#0F7B55** (light), mint **#6BE3A4** (dark) (`Assets.xcassets/AccentColor.colorset`, `design/Brand.md`) | `primary` |
| On-accent label | white (light), ink **#0B1F16** (dark) (`DesignSystem.swift:10-11`) | `onPrimary` |
| `Metrics.cardRadius` | 22 | 22.dp |
| `Metrics.pagePadding` | 20 | 20.dp horizontal (16.dp is acceptable on compact widths if applied consistently) |
| `Metrics.readableWidth` | 640 (Done page 520) | Constrain the content column with `widthIn(max = 640.dp)`, centered. |

- `cardBackground(padding = 16, radius = 22)`: padding plus a `Palette.card` rounded rectangle.
- `Callout(text, title?, tone)` (`:136-184`): a row aligned on the first baseline, spacing 10. The icon depends on tone: info `info.circle.fill` in accent, success `checkmark.circle.fill`, warning `exclamationmark.triangle.fill`, danger `exclamationmark.octagon.fill`, each in its tone color. Text is subheadline, primary color. Padding 14, background tone color at 12% opacity, radius 16, one combined accessibility element. `Callout(verbatim:)` is used for runtime error text.
- `ActionLabel(title, systemImage?, busy, prominent)` (`:95-133`): full-width, body semibold, vertical padding 4. While busy, a spinner replaces the title; the title is kept at opacity 0 so the width does not change. Label color: disabled → secondary; prominent → on-accent; otherwise tint. One line, or two at accessibility text sizes, minimum scale 0.8.
- `prominentAction()`: a capsule, large control size, filled with the accent. `secondaryAction()`: a bordered capsule. (Liquid Glass on iOS 26 is out of scope.)
- `bottomActionBar { … }` (`:72-91`): pinned to the bottom safe area. VStack spacing 10, horizontal padding 20, top 8 (10 before iOS 26), bottom 8, readable width. Before iOS 26 it has a `.bar` material background. It rises above the keyboard.
- `SymbolHero(systemImage, color, size)` (`:213-234`): a filled circle at 12% opacity with diameter `size*1.7`, a 1-pt ring at 18% opacity with diameter `size*2.2`, and a symbol at `size*0.62` medium weight. It bounces once on appear unless Reduce Motion is on.

Type mapping. SwiftUI sizes at the default Dynamic Type size, with the closest M3 role:
- largeTitle 34 bold → `headlineLarge` bold, 32–34sp
- title2 22 → `titleLarge`
- headline 17 semibold → `titleMedium`
- body 17 → `bodyLarge`
- subheadline 15 → `bodyMedium`, 15sp
- footnote 13 → `bodySmall`
- caption 12 → `labelMedium`
- caption2 11 → `labelSmall`
- Monospaced word text → a monospace family (e.g. JetBrains Mono / system `FontFamily.Monospace`), semibold.

---

## 5. `SetupScaffold` — common layout of steps 1–4 (`WalletSetupFlow.swift:87-124`)

- A vertical scroll with a VStack, leading-aligned, spacing 20, padding 20, readable width.
- Header: VStack spacing 8, read by accessibility as **one combined element**:
  1. `"Step %lld of %lld"` — footnote semibold, accent color.
  2. Title — largeTitle bold, wraps.
  3. Message — body, secondary color, wraps.
- Content slot, then the footer slot in the bottom action bar.
- `focusID` (verify step only): whenever it changes, the scroll view scrolls that element to the **center** with `.snappy`.
- Dragging the scroll view dismisses the keyboard interactively. Page background `Palette.background`. Inline (small) navigation title.
- Android polish (optional, from the mocks): above the "Step x of y" line, a segmented progress indicator (4 or 2 equal segments, completed ones in the accent, the rest `systemFill`, 4.dp high, gap 8.dp, animated). Keep the "Step %lld of %lld" text for accessibility and for parity.

---

## 6. Create flow

### 6.1 Step 1 — Create a Wallet (`WalletSetupFlow.swift:126-165`)

Navigation title "New Wallet". Scaffold `step 1, total 4`.
- Title: "Create a Wallet"
- Message: "BitTool Signer creates a new recovery phrase on this device. It works for Bitcoin, EVM networks, TRON and Solana."

Content, in order:
1. **Wallet name** (VStack spacing 8)
   - Label "Wallet name" (subheadline semibold).
   - Text field with placeholder "Wallet name". Autocapitalization: words. Return key: "done". Padding 14, `Palette.card` background, radius 14. Test id `setup.name`.
   - Any change longer than 40 characters is cut to the first 40 (`String.prefix(40)`, grapheme clusters).
2. **Recovery phrase length** (VStack spacing 8)
   - Label "Recovery phrase length" (subheadline semibold).
   - Segmented picker with accessibility label "Recovery phrase length". Options in this order: "24 words" (24, default), "12 words" (12). Creation offers only 24 and 12; import accepts 12/15/18/21/24.
   - Footnote, secondary color: "Both are secure. 24 words is common for long-term storage."
3. **PassphraseOptions** (§6.1.1).

Footer, in order:
- If `error` is set, a danger Callout with the verbatim error.
- Prominent button "Create Recovery Phrase", with a spinner while `busy`. Disabled while `busy || trimmedName.isEmpty || !passphraseValid`. Test id `setup.create`. Action: `startCreate`.

#### 6.1.1 PassphraseOptions (`WalletSetupFlow.swift:245-277`). Shared by create and import.

It is a card (`cardBackground()`), VStack spacing 12:
- A toggle bound to `usePassphrase`; the change animates with `.snappy`. Test id `setup.usePassphrase`. Label:
  - "BIP39 passphrase" (subheadline semibold)
  - "Advanced. Adds a “25th word”." (footnote, secondary)
- When on, two fields in a `Palette.raised` container with radius 14. Each field has padding 14, and a divider indented 14 sits between them:
  - `PasswordField(.passphrase)`: placeholder "BIP39 passphrase", `isNew: true`, `revealable: true`. Return key "next" moves focus to the confirmation field.
  - `PasswordField(.confirmPassphrase)`: placeholder "Confirm passphrase", `isNew: true`, `revealable: true`. Return key "done" clears focus.
  - A warning Callout: "Each passphrase opens a different wallet. Back it up separately: the recovery phrase alone cannot restore this wallet. It is not your wallet password."
- Turning the toggle off hides the fields but keeps their text. If the text is non-empty it still counts toward `hasDraftSecrets`.

### 6.2 Step 2 — Back Up Your Phrase (`WalletSetupFlow.swift:279-312`)

Navigation title "Recovery Phrase". Scaffold `step 2, total 4`.
- Title: "Back Up Your Phrase"
- Message: "Write these %lld words on paper, in order, and keep them safe. You’ll confirm them on the next screen." (`%lld` = `words.count`)

Content: `RecoveryBackupPanel` (§8) with these parameters:
- `words = model.words`
- `displayedPassphrase = ""`. Setup does not show the passphrase; the person just typed it.
- `hasPassphrase = usePassphrase && !passphrase.isEmpty`. This drives the "not included" warnings on the QR and cards.
- `showsTipsLink = false`. The tips button is in the footer instead.
- `isCovered = showingTips`.
- `onReveal { model.hasRevealedBackup = true }`.
- The panel is **keyed by `store.securityEpoch`** (`.id(...)`, `:294`). Every suspend rebuilds it: the format returns to Words, the cards and tips sheets owned by the panel close, and every privacy session starts concealed.

Footer, in order:
1. A danger Callout if `error` is set.
2. `SecurityTipsButton` (§8.7) → `showingTips = true` → the `SecurityTipsSheet` sheet.
3. Prominent button "I’ve Written Them Down in Order". Disabled until `hasRevealedBackup`. Test id `backup.continue`. Action: `confirmBackup()`.
4. While `!hasRevealedBackup`, a footnote in secondary color: "Reveal the words first."

**Revealing only the QR code does not set `hasRevealedBackup`.** Showing the words grid or the recovery cards does (`docs/Security.md:119`).

### 6.3 Step 3 — Check Your Backup (`WalletSetupFlow.swift:314-344`, model `Models/BackupVerification.swift`)

Navigation title "Verify". Scaffold `step 3, total 4`, with `focusID = scrollID(activePosition)` so the active blank is always scrolled to the center.
- Title: "Check Your Backup"
- Message: "Using your written copy, tap the missing words in order. Tap a blank to choose it, or a filled blank to change it."

Content: `BackupVerificationGrid` (§8.8). Footer, in order:
1. A danger Callout if `error` is set.
2. `BackupVerificationChips` (§8.8), with 4 bottom padding. They stay in the bottom bar so they remain reachable while the grid scrolls.
3. Prominent button "Confirm". Disabled until `verification.isComplete`. Test id `verification.continue`. Action: `finishVerification()`.
4. Plain button "Show the Phrase Again" (subheadline semibold, min height 36). Test id `verification.showPhrase`. Action: `reviewPhraseAgain()`.

Screen-level `recoveryScreenshotWarning`: a screenshot calls `verification.resetPlacements()` and `error = nil`, then shows the screenshot alert with the "may contain" message (§8.2).

### 6.4 Step 4 — Set a Password (`WalletSetupFlow.swift:346-400`)

Shared by create (step 4 of 4) and import (step 2 of 2). On appear (`.task`): `checkForExistingWallet`. The back button is hidden while busy.

| | Normal (`duplicateOf == nil`) | Reset mode (`duplicateOf != nil`) |
|---|---|---|
| Navigation title | "Password" | "Reset Password" |
| Title | "Set a Password" | "Reset Password" |
| Message | "The password encrypts this wallet on this device and authorizes every signature. It is not part of your recovery phrase." | "This recovery phrase already belongs to a wallet on this device. Set a new password for it: the phrase you just entered is the proof, so the old password is not needed." |
| Below the requirements | info Callout "If you forget this password, import this recovery phrase again to set a new one." | VStack spacing 12: `WalletIdentityHeader(walletID)`, then info Callout "Only the password changes. The wallet keeps its name, addresses, paired wallets and history." |
| Button | "Create Wallet" (create) / "Import Wallet" (restore) | "Reset Password" |
| Action | `createWallet` | `resetPassword` |

Content:
1. A `Palette.card` container with radius 16 holding two fields, each with padding 14, separated by a divider indented 14:
   - `PasswordField(.walletPassword)`: placeholder "Wallet password", `isNew: true`, not revealable. Return key "next".
   - `PasswordField(.confirmPassword)`: placeholder "Confirm password", `isNew: true`. Return key "done".
2. `PasswordRequirementsView(password, confirmation)` with horizontal padding 4 (§9.2).
3. The Callout or identity block from the table.

Footer: a danger Callout if `error`; then a prominent button with a spinner while busy. Disabled while `!passwordValid || busy`. Test id `setup.finish`. It clears focus before running the action.

### 6.5 Done (`WalletSetupFlow.swift:402-452`)

The back button is hidden and the navigation title is empty. Content is a scroll view, VStack spacing 24, padding 20, readable width 520:
1. `SymbolHero("checkmark.seal.fill", success color, size 64)`, top padding 24.
2. Title (largeTitle bold) and message (body, secondary, centered), VStack spacing 8:
   - Reset: "Password Reset" / "This wallet is encrypted with your new password. Use it to authorize signatures from now on."
   - Create: "Wallet Created" / "Your keys are encrypted on this device. Next, pair a watch-only wallet to receive funds and prepare transactions."
   - Import: "Wallet Imported" / the same message as Create.
3. If `created`, `WalletIdentityHeader(walletID: created.id)` inside a card.

Bottom bar (max width 520):
- If `created != nil && !didResetPassword`: prominent "Pair a Wallet" with a `qrcode` icon → `finish(.connectWallet(id))`. Test id `setup.connect`.
- Reset: a prominent "Done" → `finish(nil)`. Otherwise a **secondary** "Done" → `finish(nil)`. Test id `setup.done`.

Success haptic (`.sensoryFeedback(.success)`) fires when `created` becomes non-nil.

---

## 7. Import flow

### 7.1 Step 1 — Import a Wallet (`WalletSetupFlow.swift:167-243`)

Navigation title "Import Wallet". Scaffold `step 1, total 2`.
- Title: "Import a Wallet"
- Message: "Enter the 12, 15, 18, 21 or 24 words of an existing BIP39 recovery phrase, in any of the ten BIP39 languages."

Content, in order (the phrase comes **before** the name, unlike the old mock):
1. VStack spacing 10:
   - `RecoveryPhraseStatus(text: phraseInput)` (§7.2)
   - `RecoveryPhraseInput(text: $phraseInput)` (§7.3), with focus bound to `phraseFocused`.
2. Wallet name: label "Wallet name", a field with placeholder "Wallet name", autocapitalization words, padding 14, card background radius 14, 40-character cut, test id `setup.name`. There is no return-key label on this step.
3. `PassphraseOptions` (§6.1.1).

Footer:
- A danger Callout if `error` is set.
- Prominent "Continue". Disabled while `busy` or while the trimmed `phraseInput` is empty. Test id `setup.import`. Action: `startImport`.

Toolbar:
- Primary action: a `Label("Scan Recovery QR", systemImage: "qrcode.viewfinder")` button. It sets `scanError = nil` and opens the scanner as a full-screen cover.
- Keyboard toolbar, shown only while the phrase editor is focused: `RecoveryPhraseAssistant(text: $phraseInput)` (§7.4), a spacer, then a "Done" button (semibold) that clears focus.

Language picker (`:230-241`). It is presented while `languageChoices` is non-empty. Dismissing it sets `languageChoices = []`.
- Title (visible): "Which language is your backup in?"
- One button per reading, in `interpretations` order (exact spellings first, then detection order): `"%@: %@…"`. The first argument is `reading.language.displayName`; the second is the first 3 canonical words joined by a space; the key ends with U+2026. Tapping a button calls `acceptImport(reading)`.
- Message: "These words are valid in more than one language, and each language gives a different wallet. Choose the language your backup is written in."
- On iPhone the system adds a Cancel automatically; Android must add "Cancel".
- The buttons show recovery words, so this surface is sensitive (FLAG_SECURE, §15.6).

Scanner (`:217-229`). It reuses the app's `QRScannerView` (`Features/Sign/QRScannerView.swift`; full spec in the scanner doc) with:
- `title: "Scan Recovery QR"`
- `hint: "Scan a QR code containing the words (such as BitTool Signer’s recovery phrase QR code) or a Standard SeedQR."`
- `purpose: .recoveryPhrase`
- `error: scanError`
- `onReset: { scanError = nil }`. The "Scan Again" button appears whenever an error is shown.

`onScan(text)`:
1. If `BIP39Wordlist.shared.words(fromSeedQR: text)` returns words (English Standard SeedQR, §11.7): `applyScanned(words.joined(" "))` and close the scanner. The SeedQR checksum is **not** checked here; Continue checks it.
2. Else, if `BIP39Mnemonic.interpretations(text)` succeeds with at least one reading (ambiguous readings are accepted): `applyScanned(text.trimmed)` and close the scanner.
3. Else: `scanError = "This QR code does not contain a recovery phrase."` (⚠︎L)

Frame gate for the recovery purpose (`QRScannerView.swift:207-239`), also ⚠︎L:
- Payload larger than 16 384 UTF-8 bytes → "This QR code is too large to be a recovery phrase."
- Empty or whitespace-only → "This QR code is empty. Scan a QR code that contains your recovery phrase."
- A QR code with no text, such as a binary Compact SeedQR → "This QR code doesn’t contain readable words. Compact SeedQR isn’t supported; use a QR code with the words or a Standard SeedQR, or type the words."
- The same outcome within 1 s is throttled (monotonic clock).

The camera runs only while the app is active. The torch turns off when the app becomes inactive.

Exact-spelling rule: after a scan, as long as the editor text is still exactly `scannedPhrase`, Continue takes the exact reading without asking for a language. One edit (even an added space) switches to the forgiving `interpretations` path, which may ask.

### 7.2 Status line (`Features/Setup/RecoveryPhraseViews.swift:222-270`)

It sits **above** the editor so it stays visible while the keyboard is up. Layout: a single row when it fits ("Recovery phrase" subheadline semibold, a spacer of at least 8, then the indicator). Otherwise it stacks, with the indicator below (`ViewThatFits`). One combined accessibility element, test id `recovery.status`.

Indicator: an icon (hidden from accessibility) plus text (footnote, one line, minimum scale 0.8). `draft = RecoveryPhraseDraft(text)`, §11.5.

| Condition | Icon | Color | Text |
|---|---|---|---|
| `unknownWords` non-empty | `exclamationmark.circle.fill` | danger (icon and text) | `"Unknown: %@"`. `%@` is the first **2** unknown tokens, each wrapped in “ ”, joined by ", ". |
| 0 tokens | `text.word.spacing` | secondary | "12–24 words · any BIP39 language" |
| 1 token | `text.word.spacing`, or `checkmark.circle.fill` if `looksComplete` | secondary / success icon | "1 word" |
| n tokens | same | same | `"%lld words"` |

Language suffix: when `draft.language != nil` and (`language != .english` **or** `looksComplete`), the text becomes `count + " · " + language.displayName`. The joiner " · " is not localized. Display names are endonyms and never localized: "English", "简体中文", "繁體中文", "日本語", "한국어", "Español", "Français", "Italiano", "Čeština", "Português" (`Models/BIP39Wordlist.swift:16-29`). The checksum is **not** shown live; it is checked on Continue.

### 7.3 Phrase editor (`RecoveryPhraseViews.swift:182-218`)

- A multiline editor, minimum height 132, body monospaced, padding 10, `Palette.card` background, radius 16, transparent inner background.
- Placeholder when empty: "Enter your words separated by spaces" (tertiary, top 8, leading 5, ignores touches, hidden from accessibility).
- Accessibility label "Recovery phrase". Test id `recovery.input.visible`.
- No content type (no AutoFill), default keyboard (CJK input methods allowed), autocapitalization never, autocorrection off, Writing Tools off.
- `.privacySensitive()` and `.recoveryScreenshotWarning()`: a screenshot shows the "may contain" alert; the text is not cleared.
- Paste works through the system edit menu; there is no dedicated paste button. Text is always visible (never masked).
- App-wide, iOS rejects third-party keyboard extensions (`BitToolSignerApp.swift:52`). Android cannot do this; see §15.8.

### 7.4 Suggestion bar above the keyboard (`RecoveryPhraseViews.swift:273-312`)

- `draft = RecoveryPhraseDraft(text)`; `list = wordlist(draft.language ?? .english)`.
- `suggestions = draft.partialWord.map { list.suggestions(for: $0, limit: 5) } ?? []`
- `completed = list.contains(partialWord)` (accent-insensitive for Latin lists).

The bar scrolls horizontally with no scroll indicator, HStack spacing 6:

| Condition | Shows |
|---|---|
| detected language is Chinese (either script) | footnote, secondary: "Each Chinese word is one character" |
| `suggestions.isEmpty \|\| (completed && suggestions.count == 1)`, and `!completed` | footnote, secondary: "Type a word to see suggestions" if there is no partial word, else "No matching word" |
| same condition, and `completed` | nothing (the only suggestion is the word already typed) |
| otherwise | up to 5 chips |

Each chip shows the word verbatim (subheadline monospaced semibold), padding 10 × 6, `Palette.raised` capsule. Accessibility label `"Insert %@"`, test id `suggestion.<word>`.

Tapping a chip replaces the trailing run that matches `[^\s,，、]+$` with `word + " "`. The canonical word is inserted **with its accents**. If nothing matches, nothing happens.

### 7.5 Import step 2 and Done

Identical to §6.4 and §6.5 with `intent == .restore`: "Step 2 of 2", button "Import Wallet", title "Wallet Imported". Reset mode applies when the phrase and passphrase already belong to a wallet on the device.

---

## 8. Recovery backup components (`Features/Backup/RecoveryBackupViews.swift`, `Features/Setup/RecoveryPhraseViews.swift`, `Services/Recovery*Session.swift`)

### 8.1 RecoveryPrivacySession — the display-permission state machine (`Services/RecoveryPrivacySession.swift:12-83`)

It never owns the secret, only whether it may be drawn.

State: `isRevealed` (Bool), `prompt` (nil | `.screenshot`), `mounted`.

- `start()`: calls `stop()`, sets `mounted = true`, and observes:
  - screenshot → `hide()` and `prompt = .screenshot`
  - will-resign-active → `hide()`
  - did-enter-background → `hide()`
  - screen-captured-did-change (**any** change) → `hide()`
- `stop()`: `mounted = false`, removes observers, `hide()`, `prompt = nil`.
- `reveal() -> Bool`: succeeds only if `mounted && canReveal() && prompt == nil`. `canReveal` = app active && screen not captured. On success, `isRevealed = true`.
- `hide()`: `isRevealed = false`.
- `acknowledgeWarning()` (alert OK): `hide()`, `prompt = nil`.

Modifier `recoveryPrivacy(session, onReveal)` (`RecoveryPhraseViews.swift:316-345`):
- Appear → `session.start()`. Disappear → `session.stop()`. So **leaving the view, or switching Words ↔ QR, re-conceals it**.
- `isRevealed` becoming true → `onReveal()`.
- `prompt` set → an alert:
  - Title: "Screenshot Detected" (⚠︎L, `RecoveryCopy.screenshotTitle`)
  - Message: "The phrase is hidden again, but the screenshot may already contain it. Delete it from Photos, Recently Deleted and any synced copies, and keep a written backup offline."
  - Button: "OK" → `acknowledgeWarning()`
- A reveal is refused until the alert is acknowledged.

**Tap-to-reveal is the consent.** There is no second confirmation (the mock's "Show recovery phrase?" alert was removed, `docs/Security.md:119`). While concealed, the words are **not composed at all**: they are absent from the view tree and the accessibility tree, not merely blurred.

### 8.2 RecoveryScreenshotSession — warning only (`Services/RecoveryScreenshotSession.swift:7-83`)

Used on directly visible material: the import editor and the verify step.
- `start(onScreenshot)`: `mounted = true`, `active = isApplicationActive()`. It observes screenshot → `receiveScreenshot()`, resign-active / background → `deactivate()`, and become-active → `active = isApplicationActive()`.
- `receiveScreenshot()`: calls `onScreenshot()` first. Then, only if still mounted, active and the app is active, it sets `warning = Warning(id: UUID)`.
- `deactivate()`: `active = false` and dismisses the warning.
- `acknowledge(w)`: dismisses only if `w.id` is the current warning's id, so a stale alert's OK cannot acknowledge a newer screenshot.

Alert:
- Title: "Screenshot Detected" (⚠︎L)
- Message: "The screenshot may contain your recovery phrase or some of its words. Delete it from Photos, Recently Deleted and any synced copies, and keep a written backup offline."
- Button: "OK"

The visibility of the underlying content does not change.

### 8.3 RecoveryBackupPanel (`RecoveryBackupViews.swift:5-82`)

Parameters: `words`, `displayedPassphrase = ""`, `hasPassphrase = false`, `showsTipsLink = true`, `isCovered = false`, `onReveal`.

State: separate privacy sessions for the words and for the QR ("seeing the words is no reason to put a scannable copy on screen too"), `format = .words`, `showingCards`, `showingTips`.

Layout, VStack spacing 16:
1. Segmented picker with accessibility label "Backup format". Segments "Words" | "QR Code". The change animates with `.snappy(duration: 0.25)`. Test id `backup.format`.
2. Words tab:
   - `ProtectedRecoveryPhrase(words, passphrase: displayedPassphrase, privacy: wordsPrivacy, onReveal)` (§8.4).
   - A trailing-aligned button: "Recovery Cards" plus a small `arrow.right` (subheadline semibold). Accessibility hint "Splits the phrase across three cards. Any two restore it." Test id `backup.cards`. It opens `RecoveryCardsSheet` (§8.6).
3. QR tab: `RecoveryQRSection(words, hasPassphrase, privacy: qrPrivacy)` (§8.5).
4. If `showsTipsLink`: `SecurityTipsButton` (top padding 4) → `SecurityTipsSheet`.

Covering: `covered = isCovered || showingCards || showingTips`. When it changes, the visible format's session is stopped (which hides the content) or started again. A sheet does not make the view underneath disappear on iOS, so the code does this explicitly. After closing the cards or tips sheet, the words are concealed again.

### 8.4 ProtectedRecoveryPhrase and the word grid (`RecoveryPhraseViews.swift:7-175`)

**Concealed** (`ConcealedPhrase`, `:103-139`). It is a button with plain style.
- Accessibility label "Show recovery phrase", hint "Make sure no one can see your screen", test id `recovery.reveal`.
- Tapping it runs `withAnimation(.smooth(duration: 0.25)) { privacy.reveal() }`.
- Body: a ZStack over a card (padding 12, `Palette.card`, radius 22):
  - A grid of `count` placeholder cells. The column count is computed from the reference word **"wwwwwwww"**, never from the real words, so the placeholder leaks nothing but the word count.
  - Each cell: the 1-based index (caption2 monospaced digits, tertiary, min width 18, trailing-aligned) and a 10-pt-high bar (radius 3, `systemFill`). Padding 10 horizontal × 13 vertical, `Palette.raised`, radius 12. Grid spacing 8 horizontal and vertical.
  - The whole grid is blurred with radius 2 and hidden from accessibility.
  - Overlaid in the center, `RevealPrompt` (`RecoveryBackupViews.swift:157-169`): VStack spacing 8 with `eye.slash.fill` (title2), "Tap to Reveal" (headline), and "Make sure no one can see your screen." (footnote, secondary, centered). Padding 22 × 16, on a `.regularMaterial` rounded rectangle with radius 18.

**Revealed** (VStack spacing 14):
1. `RecoveryWordGrid(words)` with an opacity transition (`:141-175`):
   - Cell: HStack spacing 6. The index (caption2 monospaced digits, secondary, min width 18, trailing-aligned), then the word verbatim in the shared font (§8.4.1), one line, safety minimum scale 0.9.
   - The cell fills its width (no trailing spacer). Padding 10 × 11, `Palette.raised`, radius 12. Each cell is a combined accessibility element. Test id `recovery.word.<index>`.
   - Outer padding 12, `Palette.card`, radius 22.
2. If `passphrase` is non-empty (the saved-wallet sheet only):
   - A `key` label "BIP39 passphrase · back up separately" (caption semibold, secondary).
   - The passphrase verbatim, body monospaced. Test id `recovery.passphrase`.
   - Card with padding 14, radius 16.
3. Button "Hide Recovery Phrase" with `eye.slash` (subheadline semibold), animated hide. Test id `recovery.hide`.

If the words or the passphrase change, the content is hidden.

#### 8.4.1 WordGridColumns — one font size for every cell, at most 3 columns (`RecoveryPhraseViews.swift:56-99`)

Constants: `gridSpacing = 8`, `minimumScale = 0.8`, `safetyMargin = 3`, `spacing` (index–word) = 6.

Inputs: `cellPadding`, `indexSample` (widest index label), `indexStyle` (default caption2), `indexMinWidth`.

1. `base` = the subheadline point size at the current Dynamic Type size (15 at the default size).
2. If `width <= 0` (before layout), return 2 columns at accessibility text sizes, otherwise 3, with `wordSize = base`. On Android, measure first (`BoxWithConstraints`) so this case does not occur.
3. `widest` = the maximum `ceil(width)` of each word in monospaced semibold at `base`. `index` = `max(indexMinWidth, ceil(width of indexSample))` in monospaced-digit regular at `indexStyle`.
4. Try `columns` in [3, 2, 1]:
   - `cell = (width − 8·(columns−1)) / columns`
   - `available = cell − 2·cellPadding − index − 6 − 3`
   - `scale = min(1, available / widest)`
   - Accept if `scale >= 0.8` or `columns == 1`.
   - `wordSize = max(1, floor(base·scale·2)/2)`, rounded down to half a point.

Instances:

| View | cellPadding | indexSample | indexStyle | indexMinWidth | Words measured |
|---|---|---|---|---|---|
| ConcealedPhrase | 10 | "24" | caption2 | 18 | `["wwwwwwww"]` only |
| RecoveryWordGrid | 10 | "24" | caption2 | 18 | the real words |
| RecoveryCardView | 8 | "24." | caption2 | 20 | words + `"xxxx"` |
| BackupVerificationGrid | 8 | "24." | caption1 | 0 | **chip words only** + `"••••••"`. The masked words never affect the layout. |

Test expectations (`BitToolSignerTests/RecoveryCardsTests.swift:52-73`):
- 402-pt-wide phone (grid width 338): `["abandon","abstract","zoo"]` → 3 columns.
- 375-pt phone (grid width 311): the same words → 2 columns.
- Short words → 3 columns.
- xxxLarge text → 2 columns. Accessibility5 → 1 column.
- At the default size the word size is 15 for short words, and ≥ 12 (15 × 0.8) for long words.

### 8.5 RecoveryQRSection — plaintext phrase QR (`RecoveryBackupViews.swift:90-154`)

VStack spacing 16:
1. Info Callout: "This QR code contains your complete recovery phrase in plain text. Use it only in private, to move this wallet to another device of your own. Writing the words down by hand is still the safest backup."
2. Revealed:
   - `QRCodeView(frames: [words.joined(separator: " ")], maxSide: 250)` with an opacity transition. Test id `backup.qr`; accessibility label "QR code".
   - Button "Hide QR Code" with `eye.slash`. Test id `backup.qr.hide`.
3. Concealed: `ConcealedQRCode`, a button with accessibility label "Show recovery phrase QR code", hint "Make sure no one can see your screen", test id `backup.qr.reveal`. It looks like this:
   - A white rounded rectangle, radius 24, shadow black 8% (radius 16, y 6).
   - The SF Symbol `qrcode` scaled to fit, ultraLight weight, black at 55%, padding 26, blur 10, hidden from accessibility. This is only a picture, not the phrase.
   - `RevealPrompt` on top.
   - Aspect ratio 1, max width 250, centered, always in **light** appearance.
4. Footnote, medium weight, danger color, centered: "This QR code is your recovery phrase. Anyone who scans or photographs it can take all your funds, and no support team will ever ask to see it. Never take a screenshot of it, send it or show it to anyone."
5. If `hasPassphrase`, a warning Callout: "It contains the %lld words only. Your BIP39 passphrase is not included; restoring this wallet also needs the passphrase."

The QR is attached with `recoveryPrivacy(qrPrivacy)` **without** `onReveal`.

Payload: the words joined by single spaces, UTF-8, plain text, nothing else. It must be readable by BitTool Signer's own import (§7.1) in every language.

Rendering (`Design/QRCodeView.swift:6-26`, `:29-72`):
- One pixel per module, scaled with nearest-neighbour sampling.
- Error correction **M** when the payload is ≤ 600 UTF-8 bytes, otherwise **L**.
- White plate, radius 24, padding 18, the same shadow. A spinner shows until the image is ready. Always light appearance.
- **It is never offered for saving, sharing, printing or copying.** No long-press menu, no share button.

### 8.6 Recovery cards (2-of-3) (`Models/RecoveryCards.swift`, `RecoveryBackupViews.swift:175-340`)

Algorithm. The layout depends only on the word count, never on the words:
- `count = 3`
- `cardOmitting(p) = (p / 3 + p % 3) % 3` (zero-based position p)
- `shows(p, card) = cardOmitting(p) != card`
- `omittedPositions(card, n) = [p in 0..<n where cardOmitting(p) == card]`
- `remainingEntropyBits(n) = (n / 3) · 11 − n / 3`
- `singleCardIsWeak(n) = remainingEntropyBits(n) < 60`: true for 12 (40 bits) and 15 (50 bits); false for 18 (60), 21 (70) and 24 (80).

Omitted word numbers (1-based) as shown on each card:

| Words | Card 1 | Card 2 | Card 3 |
|---|---|---|---|
| 12 | 1, 6, 8, 10 | 2, 4, 9, 11 | 3, 5, 7, 12 |
| 15 | 1, 6, 8, 10, 15 | 2, 4, 9, 11, 13 | 3, 5, 7, 12, 14 |
| 18 | 1, 6, 8, 10, 15, 17 | 2, 4, 9, 11, 13, 18 | 3, 5, 7, 12, 14, 16 |
| 21 | 1, 6, 8, 10, 15, 17, 19 | 2, 4, 9, 11, 13, 18, 20 | 3, 5, 7, 12, 14, 16, 21 |
| 24 | 1, 6, 8, 10, 15, 17, 19, 24 | 2, 4, 9, 11, 13, 18, 20, 22 | 3, 5, 7, 12, 14, 16, 21, 23 |

Invariants to unit-test (`BitToolSignerTests/RecoveryCardsTests.swift`):
- Each card omits exactly n/3 positions, in ascending order.
- Any two cards together cover every position.
- Every position is omitted by exactly one card.
- Each row of three positions is omitted once by each card.

**Sheet** (`RecoveryCardsSheet`, `:175-216`): its own navigation stack, title "Recovery Cards" (inline), a "Done" confirmation action. The scroll content has padding 20, readable width, VStack leading-aligned, spacing 16:
1. Intro, subheadline, secondary:
   - weak (12/15 words): "Keep these three cards in three different places. Any two of them restore the complete recovery phrase, so if one card is lost, the other two can still restore your wallet."
   - otherwise: "Keep these three cards in three different places. Any two of them restore the complete recovery phrase, so if one card is lost or stolen, the other two keep your wallet safe and recoverable."
2. If weak, a warning Callout (verbatim, ⚠︎L, not in the translations). With n = word count and m = n / 3:
   "With a {n}-word phrase each card shows {n − m} of the {n} words, and the other {m} could be worked out with enough computing power. Guard every card as carefully as the full phrase, or use a 24-word phrase for split backups."
   - 12 words: "… each card shows 8 of the 12 words, and the other 4 …"
   - 15 words: "… 10 of the 15 words, and the other 5 …"
3. If `hasPassphrase`, a warning Callout: "The cards contain the words only. Back up your BIP39 passphrase separately."
4. `RecoveryCardsContent` (`:218-247`) with its **own** privacy session and `onReveal` (revealing the cards counts as revealing the backup in setup). VStack spacing 16:
   - Revealed: three `RecoveryCardView`s (opacity transition), then "Hide Cards" with `eye.slash`. Test id `cards.hide`.
   - Concealed: `ConcealedCards`, a button with label "Show recovery cards", hint "Make sure no one can see your screen", test id `cards.reveal`. It shows three card placeholders (VStack spacing 10). Each placeholder has:
     - "Card %lld of %lld" (subheadline semibold, secondary)
     - a 3-column grid (spacing 6) of `min(count, 6)` rectangles, each `Palette.raised`, radius 8, height 26
     - padding 12, `Palette.card`, radius 18
     - The group is blurred with radius 2.5 and hidden from accessibility; `RevealPrompt` sits on top.

**RecoveryCardView** (`:250-311`): VStack spacing 12. Padding 12, `Palette.card`, radius 22, plus a 1-pt accent border at 35% opacity. Test id `cards.card.<n>`.
- Header, one combined accessibility element, VStack spacing 2:
  - "Card %lld of %lld" (headline)
  - "Leaves out words %@" (caption, secondary). `%@` is the 1-based omitted positions joined by ", ".
- Grid of **every** position, using the shared font from §8.4.1.
- Each cell: the index `"%lld."` (caption2 monospaced digits, secondary, min width 20, trailing-aligned), then:
  - a shown word: verbatim, one line, minimum scale 0.9
  - an omitted word: the literal `xxxx` in regular weight, tertiary color
- Cell padding 8 × 10, radius 11. Background `Palette.raised` when shown, `Palette.raised` at 45% when omitted.
- Accessibility: the cell ignores its children. Label `"Word %lld, %@"` (shown) or `"Word %lld, not on this card"`. Test id `cards.card.<c>.word.<p>`.

### 8.7 Security Tips (`RecoveryBackupViews.swift:344-415`)

Button: `Label("Security Tips", systemImage: "info.circle")`, subheadline, secondary color, full width. Test id `backup.tips`.

Sheet:
- Navigation title "Security Tips" (inline), "Done" confirmation button.
- An inset grouped list with a 30-pt bottom content margin.
- Detents medium and large.
- Each row: an SF Symbol in the accent color, a title (headline), and detail text (subheadline, secondary). The VStack has spacing 3 and vertical padding 4.

The tips, in this order. The SF Symbol is the row's stable id.

| SF Symbol | Material Symbols suggestion | Title | Detail |
|---|---|---|---|
| `pencil.and.list.clipboard` | `edit_note` | "Write it on paper" | "Copy every word by hand, in order. Keep the paper somewhere safe from fire and water, apart from this device." |
| `eye.slash` | `visibility_off` | "Keep it offline" | "Never take a screenshot or photo of the words, and never store them in notes, email, messages or cloud storage." |
| `qrcode` | `qr_code_2` | "The QR code is the phrase" | "Anyone who scans or photographs it can take your funds. Show it only in private, to move this wallet to another device of your own." |
| `rectangle.split.3x1` | `view_week` | "Recovery cards: any two restore" | "Keep the three cards in three different places. One card alone does not hold the full phrase, but it shows two thirds of the words; with a 12- or 15-word phrase the rest can be guessed with enough computing power, so hide every card." |
| `person.2.slash` | `group_off` | "Never share it" | "Anyone with these words can take your funds from any device. No one from BitTool Signer will ever ask for them." |
| `exclamationmark.shield` | `gpp_maybe` | "Never type it into a website" | "Only enter your recovery phrase into a wallet you trust, on a device you control, when you restore it." |
| `key` | `key` | "Back up any passphrase separately" | "If this wallet uses a BIP39 passphrase, the words alone cannot restore it." |
| `lock` | `lock` | "Your password is not a backup" | "The wallet password only unlocks the copy on this device. The words restore the wallet anywhere." |

### 8.8 Backup check (`Models/BackupVerification.swift`, `RecoveryBackupViews.swift:422-564`)

**Model** (pure, unit-testable with an injected RNG):
- `blankCount(n) = n >= 18 ? 5 : 4`.
- `init(words)`:
  - Throws `invalidPhraseLength` ("A backup check requires a supported BIP39 phrase length.") unless `n ∈ {12, 15, 18, 21, 24}`.
  - `positions` = `blankCount` distinct indices taken from a shuffle of all indices, then **sorted ascending**.
  - `correctWords[p] = words[p]`; `answer = positions.map { words[$0] }`.
  - Chip order: if `Set(answer).count > 1`, reshuffle until the order differs from `answer`, so the chips are never in answer order. If all blank words are the same, there is no reshuffle.
  - `chips[i] = Chip(id: i, word: answer[order[i]])`. Chip ids are **display order** and say nothing about where a word belongs.
- RNG: `SystemRandomNumberGenerator`. Android: `SecureRandom` via `kotlin.random.asKotlinRandom()`, with a seedable RNG injectable for tests.
- `activePosition`: the selected position if it is still empty, else the first empty position in ascending order, else nil.
- `isComplete = placements.count == positions.count`.
- `isCorrect = isComplete && every position's placed word == correctWords[p]`. It compares **words**, not chip ids, so repeated words are interchangeable (test `repeatedWordsAreInterchangeable`).
- `place(chipID)`: fails if there is no active position, the id is unknown, or the chip is already placed. Otherwise `placements[active] = chipID` and `selectedPosition = nil`.
- `select(p)`: only on an empty blank. Sets `selectedPosition = p`.
- `clear(p)`: only on a blank. Removes the placement and sets `selectedPosition = p`.
- `resetPlacements()`: clears all placements and the selection.

**Grid** (`BackupVerificationGrid`). It shows every position, using the same grid, spacing and card container as the word grid.
- Masked (non-blank) cell:
  - `"%lld."` (caption monospaced digits, secondary), then the literal `••••••` (6 × U+2022) in the shared font, tertiary.
  - Horizontal padding 8, min height 42, `Palette.raised`, radius 12.
  - Accessibility: children ignored, label `"Word %lld, hidden"`.
- Blank cell: a button. Tapping an empty blank calls `select`; tapping a filled one calls `clear`.
  - Content: the index, then the placed word or nothing, in primary color, one line, minimum scale 0.9.
  - Background: accent at 10% when active, otherwise `Palette.background`.
  - Border: 2 pt accent when active; otherwise 1 pt, secondary at 45% when empty or accent at 40% when filled.
  - Animation `.snappy(0.18)` when the active blank changes.
  - Accessibility: label `"Word %lld"`; value = the word, or "Empty, next to fill" (active) / "Empty" (⚠︎L); hint "Fills this blank next" (empty) / "Removes this word" (filled). Test id `verification.slot.<p>`.
  - Scroll id: `"verification.cell.<p>"`.

**Chips** (`BackupVerificationChips`), in the footer: a **centered flow layout**, spacing 8, rows centered horizontally and items centered vertically in each row (`CenteredFlowLayout`, `:523-564`).
- Each chip shows the word verbatim (body monospaced semibold, primary, one line), horizontal padding 16, min height 44, `Palette.card`, radius 13.
- Border 1 pt in the accent: 15% opacity when placed, 60% otherwise. A placed chip is drawn at opacity 0.3.
- Disabled when placed, or when no blank is active.
- Selection haptic each time a chip's placed state changes.
- Accessibility: label = word, value "Placed" / "Available". Test id `verification.chip.<id>`.
- The chips are real phrase words; the whole row is privacy-sensitive.

Behaviour summary:
- The first blank in ascending order is active at the start.
- Tapping a chip fills the active blank, and the next empty blank becomes active.
- Tapping a filled blank removes its word and makes that blank active.
- Tapping an empty blank makes it the next one to fill.
- "Confirm" is enabled once every blank is filled. A wrong answer clears the placements and keeps the same blanks and chip order.
- Backgrounding (`suspend`) and screenshots also clear the placements.

---

## 9. Password components (`Design/PasswordViews.swift`, `Core/PasswordPolicy.swift`)

### 9.1 PasswordField (`PasswordViews.swift:9-98`)

Kinds. The raw value is both the English placeholder and the accessibility identifier:
- "Wallet password"
- "Confirm password"
- "Current wallet password"
- "New password"
- "Confirm new password"
- "BIP39 passphrase"
- "Confirm passphrase"

Non-revealable fields (wallet password): a secure field.
- `isNew == true` (every setup field): no text content type, so there is no AutoFill or strong-password suggestion. Otherwise the content type is `.password`.
- Autocapitalization never, autocorrection off, privacy-sensitive.

Revealable fields (the BIP39 passphrase). A typo would silently open another wallet, so the person must be able to read the text back.
- One text field kept alive across reveals; toggling the secure mode never loses what was typed.
- No autocapitalization, autocorrection, spell check, smart dashes, quotes or insert/delete.
- Text content type empty plus empty password rules, so no generated password is ever offered or inserted.
- A "Done" key toolbar.
- An eye button, 32 × 32: `eye` while hidden, `eye.slash` while shown. Accessibility label "Show secret" / "Hide secret", test id `<identifier>.reveal`.
- **The text is hidden again whenever the scene is no longer active.**

### 9.2 PasswordPolicy and the requirements view

`evaluate(password)` (`Core/PasswordPolicy.swift:20-45`) works on Unicode scalars. Passwords are used exactly as typed: no trimming, no normalization.
- uppercase: any scalar in `CharacterSet.uppercaseLetters` (Unicode categories Lu, Lt)
- lowercase: `lowercaseLetters` (Ll)
- number: `decimalDigits` (Nd)
- symbol: `symbols` (Sm, Sc, Sk, So) or `punctuationCharacters` (Pc, Pd, Ps, Pe, Pi, Pf, Po)
- `categories` = how many of the four are present
- `hasValidLength` = 8 ≤ `password.count` (grapheme clusters) ≤ 64 **and** `utf8.count ≤ 1024`
- `containsControl` = any scalar in `controlCharacters` (**Cc and Cf**). Cf includes ZWJ U+200D, so emoji ZWJ sequences are rejected.
- `isValid = hasValidLength && categories >= 3 && !containsControl`
- `message`, first match wins:
  1. not `hasValidLength` → "Use 8–64 characters."
  2. control → "Control characters are not allowed."
  3. `categories < 3` → "Include at least 3 of: uppercase, lowercase, numbers and symbols."
  4. otherwise "Password meets the requirements."

  The message reaches the UI only through vault errors.

`PasswordRequirementsView` (`PasswordViews.swift:169-217`), footnote, VStack spacing 8:
- **Strength meter**: 4 capsules, height 4, spacing 4, hidden from accessibility, animated with `.smooth(0.2)`.
  - `strength`: 0 if the password is empty; 1 if not valid; 4 if `count >= 16 && categories == 4`; 3 if `count >= 12`; otherwise 2.
  - Filled capsules: strength 1 → warning (orange); 2 → systemYellow; 3 or 4 → success (green). Unfilled → `systemFill`.
- **Checklist** rows: icon `checkmark.circle.fill` (success) when met, `circle` (secondary) when not, with a symbol-replace transition. Text is primary when met, secondary when not. Each row is a combined accessibility element with value "Met" / "Not met".
  1. "8 to 64 characters" — `hasValidLength`
  2. "3 of: uppercase, lowercase, number, symbol" — `categories >= 3`
  3. "Passwords match" — `!confirmation.isEmpty && password == confirmation`
- Edge case: a password with a control or format character can show all three items as met while the button stays disabled (`passwordValid` is false). This is iOS behaviour. On Android, consider showing the policy message as a 4th, error-only line in that case.

### 9.3 WalletIdentityHeader (`PasswordViews.swift:221-239`)

HStack spacing 14, one combined accessibility element:
- `WalletAvatar(identity: walletKeyId or UUID, name, size 56)` (see the wallets spec)
- VStack spacing 3:
  - the name (headline). Unknown wallets show "Wallet".
  - `"Fingerprint %@"` (caption monospaced, secondary). The value is `masterFingerprint` in uppercase, or "Setup required" (⚠︎L) when there is no public index.

---

## 10. Saved-wallet "Recovery Phrase" sheet (reuse; `Features/Wallets/WalletSecuritySheets.swift:36-124`)

Not part of setup, but it uses the same panel and must behave the same.
- Navigation title "Recovery Phrase". A "Cancel" button, which becomes "Done" once the secret is shown.
- First a password form:
  - `PasswordField(.walletPassword)`, return key "continue"
  - footnote "The recovery phrase stays hidden until you choose to show it."
  - an error Callout if needed
  - "Continue" with a spinner
  - focus is set automatically after 350 ms
- After unlock (`store.reveal`): `WalletIdentityHeader(size 44)`, then `RecoveryBackupPanel(words: secret.mnemonic.split(" "), displayedPassphrase: secret.passphrase, hasPassphrase: !passphrase.isEmpty)` with `showsTipsLink` left at its default (true), then a warning Callout: "Anyone with these words can take your funds. Write them on paper and store them offline. Never type them into a website or share a photo."
- Any `securityEpoch` change discards the decrypted secret; the password must be entered again.
- Dismissal is disabled while busy.

---

## 11. BIP39 input logic (native Kotlin port of `Models/BIP39Wordlist.swift`)

This runs natively on iOS, not in the engine, and must run natively on Android too: it is called on every keystroke. Port it to pure Kotlin in a JVM-testable module.

### 11.1 Wordlists

- Bundled files `bip39-<raw>.txt`. Raw names: `english`, `chinese_simplified`, `chinese_traditional`, `japanese`, `korean`, `spanish`, `french`, `italian`, `czech`, `portuguese`. They are byte-identical to bitcoin/bips.
- Each file is verified with SHA-256 when loaded (`:32-45`):

| List | SHA-256 |
|---|---|
| english | `2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda` |
| chinese_simplified | `5c5942792bd8340cb8b27cd592f1015edf56a8c5b26276ee18a482428e7c5726` |
| chinese_traditional | `417b26b3d8500a4ae3d59717d7011952db6fc2fb84b807f3f94ac734e89c1b5f` |
| japanese | `2eed0aef492291e061633d7ad8117f1a2b03eb80a29d0e4e3117ac2528d05ffd` |
| korean | `9e95f86c167de88f450f0aaf89e87f6624a57f973c67b516e338e8e8b8897f60` |
| spanish | `46846a5a0139d1e3cb77293e521c2865f7bcdb82c44e8d0a06a2cd0ecba48c0b` |
| french | `ebc3959ab7801a1df6bac4fa7d970652f1df76b683cd2f4003c941c63d517e59` |
| italian | `d392c49fdb700a24cd1fceb237c1f65dcc128f6b34a8aacb58b59384b5c648c2` |
| czech | `7e80e161c3e93d9554c2efb78d4e3cebf8fc727e9c52e03b83b94406bdcc95fc` |
| portuguese | `2685e9c194c82ae67e10ba59d9ea5345a23dc093e92276fc5361f6667d79cd3f` |

- A failed check, a count other than 2048, or a lookup collision makes the list **unavailable** (empty). Unavailable lists are skipped.
- Split the file on `\n` and **drop empty pieces**. Swift's `split` omits the empty piece after the trailing newline; Kotlin `split("\n")` keeps it.
- `words` are stored in NFC for display.
- `exactKey(t) = NFKD(t).lowercase()`. `key(t, lang)` = `exactKey`, and for Latin-script lists (english, spanish, french, italian, czech, portuguese, i.e. `matchesWithoutAccents`) it also removes every Mn (nonspacing mark) scalar.
- Lookup tables keep the first index on collision.
- `index(of: token, exact)` looks in `exactLookup` or in `lookup`. `contains` is the same check.
- `suggestions(prefix, limit = 6)`: `key(prefix)`; empty or unavailable → `[]`. Walk the keys in index order and collect `words[i]` while `keys[i].startsWith(key)`, stopping at `limit`. The assistant passes 5.
- `isPrefix(t) = suggestions(t, 1).isNotEmpty()`.
- Lists are loaded lazily and cached with a lock. English loads first, so the common case never loads the other nine.

### 11.2 Tokenization `tokens(text)` (`:214-230`)

1. Split on any character where `isWhitespace` (the Unicode White_Space property, which includes U+3000 IDEOGRAPHIC SPACE and U+00A0), or `,`, `，` (U+FF0C) or `、` (U+3001). Empty pieces are dropped.
2. Skip any piece whose NFKD form consists only of ASCII digits, `.` and `)`. This drops numbering such as "1.", "12)" and "１." (fullwidth digits fold under NFKD). Chinese numerals such as 一 are real words and are kept.
3. If a piece has more than one character and **every scalar is Han** (U+3400–4DBF, U+4E00–9FFF, U+F900–FAFF, U+20000–2FA1F), split it into single characters. This handles Chinese typed without spaces. Split by **code point**, not by UTF-16 `Char`, because U+20000+ are surrogate pairs.

### 11.3 `interpretations(text)` — the forgiving import parser (`:261-278`)

1. `tokens` is empty → `.empty` "Enter your recovery phrase."
2. The count is not in {12, 15, 18, 21, 24} → `.unsupportedLength(n)`:
   - n == 1: "Invalid recovery phrase: it has 1 word. Recovery phrases have 12, 15, 18, 21 or 24 words."
   - otherwise: "Invalid recovery phrase: it has %lld words. Recovery phrases have 12, 15, 18, 21 or 24 words."
3. `exact = readings(tokens, exact: true)`; `results = exact.valid`.
4. If `results` is empty **or** no token has typed accents, append the accent-insensitive readings (`readings(tokens, exact: false)`, Latin lists only) that are not already present.
5. If `results` is non-empty, return it (exact readings first).
6. If any list mapped every token (exact or loose) but no checksum passed → `.checksum` "Invalid recovery phrase. Check the words and their order."
7. Otherwise pick the `best` language: the one containing the most tokens (loose lookup), scanning in detection order. Swift's `max(by:)` replaces the running result only when the next one is strictly greater, so the **first** maximum (English first) wins ties. Throw `.unknownWords(tokens not in best)`:
   - one word: "Invalid recovery phrase: %@ is not in the BIP39 word list."
   - several: "Invalid recovery phrase: %@ are not in the BIP39 word list."

   `%@` is the first **3** unknown tokens, each in “ ”, joined by ", ".

Other details:
- `readings(tokens, exact)`: for each language in `detectionOrder` (english, chinese_simplified, chinese_traditional, japanese, korean, spanish, french, italian, czech, portuguese) that is either `exact` or Latin, and is available: resolve every token's index. If all resolve, set `anyMapped = true`. If the checksum is valid, append `Canonical(phrase: list.words[i] joined by " ", language)`, removing duplicate phrases.
- `hasTypedAccents(tokens)`: some token contains a scalar that is alphabetic and below U+0250, **and** its NFD form contains an Mn scalar.
- `.ambiguous` ("These words are a valid recovery phrase in more than one language. Choose the language of your written backup.") is thrown only by `canonicalize`, which setup does not use. Setup shows the picker instead. Keep the string.

### 11.4 `validatedCanonical(phrase)` — exact only (`:282-287`)

Used for stored, generated and scanned-unedited phrases.
1. Tokenize.
2. A count outside the supported set → `unsupportedLength`.
3. Take the first valid exact reading, or throw `checksum`.

### 11.5 Checksum (`:312-329`)

- `n` 11-bit indices form `n·11` bits.
- `checksumBits = n/3`; `entropyBits = n·11 − n/3`.
- Pack the entropy bits MSB first into `entropyBits/8` bytes and compute the SHA-256.
- Compare the first `checksumBits` bits of the hash with the trailing bits.
- Wipe the buffers afterwards (`memset_s`; on Android `fill(0)`).

### 11.6 Seed (`Core/BIP39Seed.swift:9-55`), computed natively, never in the engine

1. `normalizedPhrase = NFKD(mnemonic).lowercase()`, split on whitespace and re-joined with single spaces.
2. Require non-empty, at most **1024** UTF-8 bytes; passphrase at most **1024** UTF-8 bytes. Otherwise `VaultError.invalidSecret` ("The recovery phrase is empty or too large.").
3. Seed = **PBKDF2-HMAC-SHA512**, 2048 iterations, 64 bytes. Password = UTF-8 bytes of `normalizedPhrase`. Salt = UTF-8 bytes of `NFKD("mnemonic" + passphrase)`. The passphrase is **not** lowercased or trimmed.
4. The result is lowercase hex (128 characters).
5. Wipe the password, salt and seed byte arrays.

### 11.7 Standard SeedQR (`:134-147`)

- English list only.
- After trimming whitespace and newlines, the text must be 48, 60, 72, 84 or 96 ASCII digits (12, 15, 18, 21 or 24 words).
- Each group of 4 digits is an index below 2048, mapped to `words[index]`.
- Anything else → nil. Compact (binary) SeedQR is unsupported.

### 11.8 RecoveryPhraseDraft — live analysis (`:333-390`)

- `words = tokens(text)`.
- `endsInWord` = the last character is not whitespace, `,`, `，` or `、`. Then `partialWord = endsInWord ? tokens.last : nil`.
- No tokens → `language = nil`, no unknown words, `trailingWordIncomplete = false`.
- A token is acceptable if the list contains it, or if it is the last token, `endsInWord`, and a valid prefix in that list.
- Pass 1 (exact): the first available language in detection order where every token is acceptable using **exact** lookup (plus the prefix rule) is chosen, with no missing words.
- Pass 2 (loose): for each available language, compute `missing` with the loose lookup. The first with no missing words wins; otherwise keep the language with the fewest missing (the earlier one on ties).
- `language = chosen.missing.count < tokens.count ? chosen.language : nil`.
- `unknownWords = chosen.missing`.
- `trailingWordIncomplete = partial != nil && !chosen.list.contains(partial)`.
- `hasSupportedLength = words.count ∈ {12, 15, 18, 21, 24}`.
- `looksComplete = hasSupportedLength && unknownWords.isEmpty && !trailingWordIncomplete`. The checksum is checked only on Continue.

Known quirk: numbering tokens are dropped, so with text "abandon 1" `partialWord` is "abandon" while the regex in `complete(with:)` replaces the trailing "1". Keep this for parity, or fix it consciously.

Performance: on iOS the draft is computed twice per render (status and assistant). On Android, compute it once per text change in the ViewModel on `Dispatchers.Default` and expose the status and suggestions as state. Do not compute it inside composition.

---

## 12. Accessibility, animation and haptics

### 12.1 Accessibility (beyond the per-component lists above)

- The step header is one combined element.
- Concealed placeholders are buttons with label and hint. The blurred shapes underneath are hidden from accessibility.
- Revealed word cells combine index and word. Card cells give an explicit label. Verification blanks have label, value and hint. Chips have label and value. Requirement rows have value "Met" / "Not met".
- `RevealPrompt` and the strength meter are hidden from accessibility.
- Test ids:
  - `setup.name`, `setup.create`, `setup.import`, `setup.usePassphrase`, `setup.finish`, `setup.connect`, `setup.done`
  - `backup.format`, `backup.cards`, `backup.qr`, `backup.qr.reveal`, `backup.qr.hide`, `backup.tips`, `backup.continue`
  - `recovery.reveal`, `recovery.hide`, `recovery.word.<i>`, `recovery.passphrase`, `recovery.input.visible`, `recovery.status`
  - `suggestion.<word>`
  - `cards.reveal`, `cards.hide`, `cards.card.<c>`, `cards.card.<c>.word.<p>`
  - `verification.slot.<p>`, `verification.chip.<id>`, `verification.continue`, `verification.showPhrase`
  - Password-field ids = the English kind names, plus `.reveal`.

  On Android these become `Modifier.testTag`, with `testTagsAsResourceId` enabled for UI tests.
- Dynamic Type: the word grids reduce columns before shrinking text (§8.4.1). The action label allows two lines at accessibility text sizes. The status line stacks when it does not fit.

### 12.2 Animations

| What | iOS |
|---|---|
| Intro → flow | `.smooth`; flow enters with move-from-trailing + opacity |
| Reveal | `.smooth(duration: 0.25)`, content opacity transition |
| Hide | default `withAnimation` |
| Words ↔ QR | `.snappy(duration: 0.25)` |
| Passphrase toggle | `.snappy` |
| Active blank | `.snappy(duration: 0.18)` |
| Scroll to active blank | `.snappy`, centered |
| Strength meter | `.smooth(duration: 0.2)` |
| Requirement icon | symbol replace |
| Done hero | one bounce on appear; none with Reduce Motion |
| QR scanner | viewfinder pulse 1.2 s ease-in-out, repeating (none with Reduce Motion) |

Compose: `.smooth` ≈ `spring(dampingRatio = 1f)`; `.snappy` ≈ `spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)`. Or use M3 Expressive `MotionScheme` fast effects / spatial specs. Respect the system "remove animations" setting (`ANIMATOR_DURATION_SCALE == 0`).

### 12.3 Haptics

| Event | iOS | Android |
|---|---|---|
| Chip placed or removed | selection | `HapticFeedbackType.SegmentTick` (or `TextHandleMove`) |
| Wallet created / reset | success | `HapticFeedbackConstants.CONFIRM` (API 30+) |
| Scanner error | error | `HapticFeedbackConstants.REJECT` |

---

## 13. Design PNGs: what they show and how to use them

All in `/Users/tanguopeng/Documents/bitsigner-ios/BitSigner/design/`. They come from **before** the vault → wallet rename and the flow redesign (`design/Brand.md` last line). The newer `design/Revision-2026-09-21/` set is also older than the current code.

- **Recovery-Hidden-Light.png / Recovery-Hidden-Dark.png** (also `Revision-2026-09-21/Recovery-Hidden-Light.png`)
  - A page sheet: "Cancel" in the accent on the leading side, a centered bold title ("Create vault", obsolete).
  - A 4-segment progress bar with 2 segments in the accent (emerald in light, mint in dark) and the rest light gray / dark gray.
  - A letter-spaced "STEP 2 / 4" caption, a very large bold title, gray body text.
  - A tall rounded card (white in light; near-black #1A1D1C with a hairline border in dark) holding a centered accent eye-slash icon, accent semibold text and a gray hint.
  - Below: a camera icon with a footnote, a switch row, and a disabled pale-mint capsule button with a trailing arrow.
  - Obsolete: the toggle, "Check my backup", the inline camera footnote and the wording.
  - Keep: the large-title hierarchy, generous whitespace, a single concealed card with the accent eye-slash, and the capsule CTA.
- **Recovery-Reveal-Confirmation.png**: an alert "Show recovery phrase?" with "I understand, show it" / "Cancel". **Removed** in the current code; do not implement it.
- **Backup-Verification-Dark.png**: the old check, three options per position ("Word #3: abandon / limb / must") with a mint selected border, "Hide verification choices" and a mint "Continue →" button. **Superseded** by the blanks-and-chips check in §8.8. Keep the dark palette: near-black page, dark gray cards, mint accent with ink label.
- **Restore-Input-Light.png** / `Revision-2026-09-21/Invalid-Recovery-Light.png`: the old import, with the name field first, "Recovery phrase" plus an inline accent "Scan" link, a large white monospaced editor, an "English only" helper and a mint "Validate recovery phrase →" button. The invalid variant shows an orange-tinted Callout "Invalid recovery phrase. Check the words and their order." below the button. Current code: phrase first, a live status line, Scan in the toolbar, ten languages, and the error as a **danger** Callout **above** the button.
- **Background-Resume-Step2.png**: after returning from the background, the phrase card is concealed again while the "written down" toggle is still on. This matches the code: `hasRevealedBackup` survives re-concealment.
- Brand: the accent is emerald #0F7B55 in light and mint #6BE3A4 in dark. The labels on the accent are white in light and ink #0B1F16 in dark (`design/Brand.md`). The mocks' pale-mint light-mode CTA is obsolete.

Android polish this spec permits:
- the segmented step indicator
- M3 segmented buttons
- an M3 top app bar with a close icon
- edge-to-edge with a scrim behind the bottom bar
- a shared-axis transition between steps
- predictive back

None of these may change copy, ordering or security behaviour.

---

## 14. Localization notes

### 14.1 Format specifiers used by this flow

| Key | Arguments |
|---|---|
| "Step %lld of %lld" | step, total |
| "Wallet %lld" | existingWallets + 1 |
| "Write these %lld words on paper, in order, and keep them safe. You’ll confirm them on the next screen." | word count |
| "Unknown: %@" | quoted list |
| "%lld words" | count |
| "Insert %@" | word |
| "%@: %@…" | language name, first 3 words |
| "Card %lld of %lld" | card, 3 |
| "Leaves out words %@" | list |
| "%lld." | index |
| "%lld" | index |
| "Word %lld, %@" | position, word |
| "Word %lld, not on this card" | position |
| "Word %lld, hidden" | position |
| "Word %lld" | position |
| "It contains the %lld words only. Your BIP39 passphrase is not included; restoring this wallet also needs the passphrase." | count |
| "Invalid recovery phrase: it has %lld words. Recovery phrases have 12, 15, 18, 21 or 24 words." | count |
| "Invalid recovery phrase: %@ is not in the BIP39 word list." | list |
| "Invalid recovery phrase: %@ are not in the BIP39 word list." | list |
| "Fingerprint %@" | fingerprint |
| "Too many unsuccessful attempts. Try again in %lld seconds." | seconds |

Several translations reorder arguments with `%1$@` / `%2$lld`. Always generate Android strings with positional arguments.

### 14.2 Singular/plural splits

iOS chooses between two **separate keys** in code: "1 word" vs "%lld words"; unsupported length with 1 vs n; "is" vs "are" for unknown words. Keep the same split in Kotlin, so the existing translations can be used one-to-one. Do not convert these to `<plurals>`, because the translations contain only these two forms.

### 14.3 ⚠︎L — strings shown in English in every language on iOS (not in `translations/*.json`)

- "Screenshot Detected" (`RecoveryPhraseViews.swift:348`)
- "This QR code does not contain a recovery phrase." (`WalletSetupFlow.swift:226`)
- The weak-cards warning "With a … could be worked out with enough computing power. …" (`RecoveryBackupViews.swift:212-215`)
- "Empty, next to fill", "Empty" (`RecoveryBackupViews.swift:483`)
- The three recovery-purpose scanner gate messages (`QRScannerView.swift:217-219`)
- "Setup required" (`AppStore.swift:158`)

Recommendation: add them to Android resources with the English text and flag them for translation. This is better than parity-by-omission. Note the weak-cards warning has 4 numeric arguments; use a positional format: `With a %1$d-word phrase each card shows %2$d of the %1$d words, and the other %3$d could be worked out …`.

### 14.4 Untranslated tokens

Never localize these:
- the BIP39 language display names (endonyms)
- the literals `xxxx`, `••••••` and " · "
- the curly quotes around unknown words

The BIP39 words are always shown verbatim and read left to right, even in Arabic (set `TextDirection.Ltr` / `textDirection = Ltr` on word text and on the editor).

---

## 15. Android mapping

### 15.1 Modules (following Ownbit 5.0's `build-logic` convention plugins and `core/*` + `feature/*` layout)

| Module | Contents |
|---|---|
| `:core:bip39` (pure JVM/Kotlin, `ownbit.jvm.library`-style plugin) | wordlists (as resources, SHA-256 pinned), `Bip39Language`, `Bip39Wordlist`, `Bip39Mnemonic` (tokens, interpretations, validatedCanonical, checksum), `RecoveryPhraseDraft`, `SeedQr`, `Bip39Seed` (PBKDF2), `RecoveryCards`, `BackupVerification`, `PasswordPolicy`. 100% unit-tested on the JVM; port the iOS tests one-to-one, including the seeded SplitMix64 generator. |
| `:core:jsengine` | QuickJS `SigningEngine` (fresh context for `mnemonic` / `derivePublicIndex`), envelope handling as in §3. |
| `:core:vault` / `:core:data` | `WalletVault`, `PublicWalletRepository`, and the `WalletStore` (port of `AppStore`) exposing `securityEpoch: StateFlow<Long>`, `sensitiveEpoch`, `generateMnemonic`, `createWallet`, `existingWallet`, `resetPassword`. |
| `:core:designsystem` | `Callout`, `ActionButton` (with the busy state), `BottomActionBar`, `SymbolHero`, `StepHeader`, palette tokens (§4). |
| `:core:ui` | `SecureScreen` (FLAG_SECURE, reference-counted, as in Ownbit `core/ui/security/SecureScreen.kt`), `NoAutofill` (Ownbit `core/ui/security/NoAutofill.kt`), `NoPersonalizedLearning` (Ownbit `core/designsystem/component/SecureInput.kt`), the optional in-app keyboard (Ownbit `core/ui/keyboard/Bip39Keyboard.kt`, `InAppKeyboardInput.kt`), `RecoveryPrivacySession`, `ScreenshotWarningSession`, a lifecycle/scene-phase bridge. |
| `:feature:backup` | `RecoveryBackupPanel`, `ProtectedRecoveryPhrase`, `RecoveryWordGrid` and grid layout, `RecoveryQrSection`, `RecoveryCardsSheet`, `SecurityTipsSheet`, `BackupVerificationGrid` / `Chips`, `CenteredFlowRow`. Used by setup and by the wallet's Recovery Phrase sheet. |
| `:feature:setup` | the setup graph: intro gate, Create / Import / Backup / Verify / Password / Done screens, `WalletSetupViewModel`. |
| `:core:scanner` | CameraX + a **bundled** decoder (ML Kit `barcode-scanning` bundled model, or zxing-cpp). The unbundled Play-services model cannot be used: the app has no INTERNET permission, and the `apk` flavor may run without GMS. |

Flavors: `apk` and `play` behave **identically** in this flow. Do not let Play-services-only APIs (unbundled ML Kit, Play Integrity, Credential Manager password saving) into these modules.

### 15.2 ViewModel and state machine

- `@HiltViewModel class WalletSetupViewModel(savedStateHandle, store: WalletStore, …)`. The intent and `existingWallets` come from the route. Expose `StateFlow<SetupUiState>` and port every transition in §2.2 one-to-one, including the guards.
- **Secrets never go into `SavedStateHandle`, route arguments, `rememberSaveable`, `rememberTextFieldState()` (it saves its text!), DataStore or logs.** Keep them in ViewModel memory only. Wrap them in a `Secret` value class whose `toString()` is redacted; a data-class `toString()` would otherwise put the phrase in logs and crash reports.
- Non-secret state may survive process death (intent, name, wordCount, usePassphrase). The simplest correct behaviour is to restart the flow at step 1 with empty secrets, as iOS does.
- Operation token: use a `Long` epoch counter and the coroutine `Job`.
  - `suspend()` and `clearSecrets()` bump the token and cancel **generation** and **duplicate-check** jobs.
  - **Do not cancel `createWallet` or `resetPassword` after the vault commit.** Run the store call so that once `vault.create` returns, `created` / `.done` is always applied (e.g. `withContext(NonCancellable)` around commit + reload, or never cancel that job and only gate *error display* on the token, as iOS does). Otherwise a background transition during saving could make the user import a duplicate.
- Map `store.securityEpoch` changes → `suspend()`; `navigationResetEpoch` → `clearSecrets()` if not created. Call `onCleared()` → `clearSecrets()` and `store.endSensitiveSession()`; call `beginSensitiveSession()` when the ViewModel is created.

### 15.3 Lifecycle mapping (single-Activity app)

| iOS | Android |
|---|---|
| `willResignActive` (privacy sessions hide; passphrase eye resets; screenshot warning dismissed) | Activity `Lifecycle.Event.ON_PAUSE`. **Do not** use window-focus loss: our own dialogs and bottom sheets take window focus and would re-conceal the phrase. |
| `didEnterBackground` → `store.suspend()` | Activity `ON_STOP` with `!isChangingConfigurations`. Rotation, theme or locale recreation must not suspend. Avoid `ProcessLifecycleOwner` for this; its ON_STOP is delayed by about 700 ms. |
| `didBecomeActive` | `ON_RESUME` |
| `UIScreen.isCaptured` / `capturedDidChange` (no reveal while captured; capture start → `store.suspend()`) | API 35+: `WindowManager.addScreenRecordingCallback` (normal permission `DETECT_SCREEN_RECORDING`). On `SCREEN_RECORDING_STATE_VISIBLE` → `store.suspend()`, and `canReveal` returns false. Below 35, rely on FLAG_SECURE (recordings and casts show black). |
| `userDidTakeScreenshotNotification` | API 34+: `Activity.registerScreenCaptureCallback` (normal permission `DETECT_SCREEN_CAPTURE`) → the same handlers as iOS (conceal + prompt; verify step resets placements). With FLAG_SECURE, screenshots are blocked and the callback may never fire; keep it wired as defense in depth and verify on a device. |
| `scenePhase != .active` in PasswordField | Collect the lifecycle state. Any state below RESUMED resets `revealed = false`. |

The QR scanner must be a composable inside the same Activity. A separate Activity would stop the main one and trigger `suspend()`. The camera permission dialog only pauses the Activity, which matches iOS resign-active.

### 15.4 Navigation graph and predictive back

Recommended: a Navigation 3 `NavDisplay` driven by the ViewModel-owned back stack. It is a direct match for SwiftUI `NavigationStack(path:)`, because the model replaces or appends steps itself, and predictive back is built in. If the project standardizes on Navigation Compose 2.9 type-safe routes (as Ownbit uses 2.8), use a nested `SetupGraph(intent)` route that hosts a **single** destination. Inside it, render `viewModel.path` with `AnimatedContent` / `SeekableTransitionState` driven by `PredictiveBackHandler`. Do not split the steps into separate Navigation Compose destinations: the model's wholesale path replacement (`path = [.password]`, `reviewPhraseAgain` → `[.backup]`) does not map well to those destinations.

```
Root (Welcome | Main)
 └─ SetupGraph(intent, existingWallets)        ← presented full-screen, vertical slide-in (Android idiom for the iOS sheet)
     ├─ Intro            (only if first wallet && onboardingVersionCompleted < 1)
     ├─ Form             (Create | Import)                       path = []
     ├─ Backup           path = [Backup]                         (create)
     ├─ Verify           path = [Backup, Verify]                 (create)
     ├─ Password         path = [Backup, Verify, Password] | [Password]
     └─ Done             path = [..., Done]
```

Back behaviour (system back, predictive back gesture, and the top-bar navigation icon):

| Where | Back does |
|---|---|
| Intro page > 0 | previous intro page ("Back") |
| Intro page 0 | close setup |
| Form, `busy` | nothing (consume) |
| Form, `hasDraftSecrets` | show "Discard this wallet?". Use a plain `BackHandler`, so the system does **not** preview back-to-home. |
| Form, otherwise | close setup (predictive back-to-previous animation) |
| Backup / Verify | pop one step with the predictive animation. The phrase is kept, no dialog (as iOS's back button). |
| Password, `busy` | nothing (the iOS back button is hidden) |
| Password | pop one step (create → Verify, import → Form). See §16 item 2 on clearing the passwords here. |
| Done | the same as tapping "Done" (`finish(null)`). iOS offers no back here; mapping back to the allowed exit is the Android equivalent. |

The "Cancel" action on pushed steps (top app bar trailing action, or a close icon on step 1) runs the same discard logic. Disable it while busy.

Finishing: pop `SetupGraph`, then navigate to `ConnectWallet(walletId)` if requested, as described in §1.6.

### 15.5 Compose components

- Step scaffold: `Scaffold`, with a `TopAppBar` (title = navigation title, `navigationIcon` = Close on the first step or Back on pushed steps, actions = "Cancel" / "Scan Recovery QR").
  - Content: `LazyColumn` or `Column(verticalScroll)`, `widthIn(max = 640.dp)`.
  - `bottomBar`: a `BottomActionBar` column with spacing 10.dp, `navigationBarsPadding()` + `imePadding()`, and a scrim or surface when content scrolls under it.
  - Use `LazyListState.animateScrollToItem` (or `BringIntoViewRequester`) to center the active blank.
- Word count and Words/QR switches: M3 `SingleChoiceSegmentedButtonRow` (24 first).
- Passphrase toggle: `ListItem` with a trailing `Switch`, inside a card. Expand and collapse with `AnimatedVisibility`.
- Password and passphrase fields: M3 `SecureTextField` / `OutlinedSecureTextField` (`TextFieldState`, `textObfuscationMode = Hidden/Visible` for the eye toggle, so the text survives toggling). Options: `KeyboardOptions(keyboardType = Password, autoCorrectEnabled = false, capitalization = None, imeAction = Next/Done)`. **No `contentType`** / autofill semantics; wrap the flow in `NoAutofill()`. The `TextFieldState` must be created in the ViewModel or with `remember`, never with `rememberTextFieldState`.
- Phrase editor: `BasicTextField` / `OutlinedTextField` multiline, `minLines = 5`, monospace, `KeyboardOptions(capitalization = None, autoCorrectEnabled = false, keyboardType = Text, imeAction = Default)`, wrapped in `NoPersonalizedLearning` (adds `IME_FLAG_NO_PERSONALIZED_LEARNING`). Do not add `TYPE_TEXT_FLAG_NO_SUGGESTIONS`: some CJK input methods need their candidate strip to compose characters. Test Gboard Pinyin, Zhuyin, Japanese and Korean.
- **Suggestion row above the keyboard (IME-attached):** render it as the last item of the `bottomBar` column, visible only when `phraseFocused && WindowInsets.isImeVisible`. The bar has `imePadding()`, so the row sits flush on top of the IME, under the footer (Continue). Contents: a `LazyRow` of `SuggestionChip`s, or the hint text, plus a trailing "Done" `TextButton` that clears focus and hides the keyboard. Tapping a chip edits the text through the ViewModel and moves the cursor to the end. Animate with `AnimatedVisibility(expandVertically + fadeIn)`. With the in-app keyboard (§15.8) the same row sits above that keyboard.
- Concealed and revealed displays: `if (revealed) { Grid() } else { ConcealedPlaceholder() }`. Never use `alpha` or `blur` over real words. Use `Modifier.blur` (API 31+) only on the placeholder shapes, with a fallback of a solid `systemFill` look on API < 31. For the frosted `RevealPrompt`, use `surfaceContainerHigh` at 92% opacity.
- Word grids: compute the layout of §8.4.1 with `TextMeasurer` inside `BoxWithConstraints`. `base = 15.sp` × `fontScale` (Android's non-linear font scaling, API 34+, already applies). Use `LazyVerticalGrid` only if not nested in a vertical scroll; otherwise a manual `Row` chunking (≤ 24 cells).
- Chips: `FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = spacedBy(8.dp))`.
- Language picker, Security Tips and Recovery Cards: `ModalBottomSheet`. Tips: allow partial expansion (medium/large detents); cards: `skipPartiallyExpanded = true`. Every sheet or dialog that can show words gets `ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn)` / `DialogProperties(securePolicy = SecureOn)`, because dialogs are separate windows and the Activity's FLAG_SECURE does not cover them. The language picker can also be an `AlertDialog` with a list.
- Discard dialog: `AlertDialog`, confirm "Discard" in the error color, dismiss "Keep Editing".
- Screenshot alerts: `AlertDialog` with the single "OK"; acknowledging works as in §8.1 / §8.2.
- QR: render with ZXing `QRCodeWriter` (byte mode, UTF-8, EC M if ≤ 600 bytes else L, margin 0, quiet zone from the 18.dp plate padding) into an `ImageBitmap` drawn with `FilterQuality.None`. No long-press, no share, no save, no `SelectionContainer`. Test that BitTool Signer's import, and at least one other wallet, read Japanese and Chinese phrase QR codes with the charset you choose.
- Done: `SymbolHero` = a `Box` with two circles and the `verified` icon; a scale bounce on first composition unless animations are disabled.

### 15.6 FLAG_SECURE coverage

Apply `SecureScreen()` to the **whole setup graph**: words, QR, cards, chips, the language picker (it shows words), the editor and the passwords. That also blanks the Recents thumbnail. Apply it as well to the saved-wallet Recovery Phrase sheet, and set the secure policy on every dialog or sheet (§15.5). This is stronger than iOS, which cannot block screenshots. Keep the iOS screenshot-warning code paths anyway (§15.3).

### 15.7 Autofill and clipboard

- `NoAutofill()` on the setup graph (sets `IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS`) so no "Save password?" or suggested password reaches the passphrase or password fields. This is the Android counterpart of iOS `textContentType(nil)` / empty `passwordRules`.
- The app never copies phrases. Do not add copy actions or a `SelectionContainer` around words. Pasting into the editor is allowed, as on iOS. Android 13+ shows the system paste notice; that is fine.

### 15.8 Keyboard trust (iOS rejects third-party keyboards; Android cannot)

Android has no API to refuse a third-party IME. Decided (DECISIONS §11) and implemented: the recovery phrase is typed
on BitTool's own keyboard by default, a port of Ownbit 5.0's `Bip39Keyboard`, so the typed words never reach any input
method. There is no iOS counterpart; iOS behaviour elsewhere is unchanged.

**Where.** `ImportWalletStep` (`:feature:setup` `flow/FormSteps.kt`) is the only place a phrase is typed: import,
resetting a forgotten password, a phrase that is already here, and re-importing after a lost device key. The components
live in `:core:ui` `keyboard/`; the strings in `strings_keyboard.xml` (7 Android-only strings, translated into the 20
other languages; the keys otherwise reuse the iOS keys "Done", "Delete", "Paste from Clipboard", "Insert %@").

**No input-method connection.** `RecoveryPhraseEditor` is hosted by `InAppKeyboardInput(inAppKeyboard = true)`, which
swallows the platform text-input request of every Compose field inside it (`InterceptPlatformTextInput` that never
forwards) and makes `SoftwareKeyboardController.show()` a no-op. No `InputConnection` is ever created, the view is not
a text editor for the `InputMethodManager`, and no keyboard app, suggestion strip, clipboard tool or Autofill can attach.
The field keeps its focus, caret, selection handles and the secret text menu (Paste and Select All only). Physical
keyboards type into it directly; Copy and Cut keys and shortcuts are refused (`isCopyOrCutShortcut`), Paste works. When
the editor gains the focus from a field typed with the system keyboard (the name, a passphrase), the system keyboard
is put away (`hideSystemKeyboard`).

**The keyboard** (`Bip39Keyboard`), in `SetupScaffold`'s bottom bar under the footer, full width, sliding up from the
bottom edge (none without animations); `keepInViewAboveBar` keeps the status line and the editor, or the caret line,
above it:
- Top row: the editor's own completions as "Insert %@" chips (`suggestion.<word>`, the editor's Insert action, iOS
  `RecoveryPhraseAssistant.complete(with:)`), or its hints ("Type a word to see suggestions", "No matching word", "Each
  Chinese word is one character"). Fixed height, so the keys never jump.
- `qwertyuiop` / `asdfghjkl` / paste · `zxcvbnm` · backspace / system keyboard (globe) · space · Done, lower case, left
  to right in every language (RTL interfaces keep QWERTY; hints keep the interface direction). a–z covers English,
  Spanish, French, Italian, Czech and Portuguese, which the editor matches without accents.
- Paste inserts the clipboard text exactly as the editor's Paste menu does (`Bip39KeyboardEdits.paste`); backspace
  deletes a grapheme on touch-down and repeats, faster, while held; Done hides the keyboard (clears the focus).
- Edits go straight into the editor's `TextFieldState` (`KeyboardEdit`, redacted `toString`), nowhere else.
- Keys never take the focus; the whole cell is the touch target; pressed colour and a letter preview drawn in this
  window (no popup, so it stays under `FLAG_SECURE`); keyboard-tap haptic through the view (the system setting
  decides; no sounds); TalkBack names every key; the window is accessibility-sensitive while shown (API 34+). Labels
  grow with the font size up to 1.3×; keys at most 720 dp wide; shorter rows on short windows; light and dark from the
  design-system palette.

**Back.** Back and the predictive back gesture close the keyboard first (it sinks a little with the gesture; a cancelled
gesture leaves it up); only the next Back asks to discard the draft.

**Short windows.** Below 420 dp of window height (phones in landscape) "Continue" steps aside while the keyboard is up,
so the editor keeps room; Done brings it back. An error is not hidden with it: it stays above the keyboard while the
phrase is fixed (typing does not clear it, iOS).

**System keyboard (Chinese, Japanese, Korean).** The globe key opens `SwitchToSystemKeyboardDialog` ("Switch to the
system keyboard?", the system or a third-party keyboard can see and learn the words; "Keep BitTool keyboard" is the
default, Back keeps it). "Switch anyway" sets `WalletSetupViewModel.phraseKeyboard = PhraseKeyboard.System` (snapshot
state, not a secret, for the presentation; in-app again for the next one), recreates the editor and focuses it once
the window has the focus, raising the system keyboard. The editor then asks the input method for:
`IME_FLAG_NO_PERSONALIZED_LEARNING` (`NoPersonalizedLearning`, incognito), no autocorrection, no capitalisation, and
`TYPE_TEXT_FLAG_NO_SUGGESTIONS` on the plain text class (`NoSuggestions`, QA regression-01: no suggestion strip, no
composing underline, no clipboard chip on Latin keyboards; CJK input methods keep their conversion candidates). No
password or visible-password variation, which would give Latin-only layouts. Above the system keyboard the suggestion
bar (`RecoverySuggestionBar`) starts with a "BitTool keyboard" chip (`keyboard.inApp`) that switches back and puts the
system keyboard away.

**Tests.** `:core:ui` `Bip39KeyboardEditsTest` (Ownbit's edit cases), `Bip39KeyboardTest` and Roborazzi component
shots; `:feature:setup` `PhraseKeyboardTest` (no input connection, keys, suggestions, hardware keys, Copy / Cut refused,
Done and Back, predictive back, switching both ways with the warning and the system keyboard's traits, short window
with and without an error), `PhraseKeyboardInsertTest` (Ownbit's suggestion cases on the Insert semantics),
`PhraseEditorTest` (input traits), `KeyboardKeepInViewTest`, `PhraseKeyboardScreenshotTest`.

### 15.9 Engine and crypto pitfalls

- Generate entropy with `java.security.SecureRandom` (§3). Run `mnemonic` in a fresh QuickJS context whose `crypto.getRandomValues` comes from SecureRandom.
- Implement the BIP39 seed in Kotlin with `javax.crypto.Mac("HmacSHA512")` in an explicit PBKDF2 loop over **UTF-8 bytes**, or BouncyCastle `PKCS5S2ParametersGenerator(SHA512Digest())`. `SecretKeyFactory("PBKDF2WithHmacSHA512")` takes a `char[]` whose encoding is provider-defined. Verify with the BIP39 test vectors (passphrase "TREZOR"), the Japanese NFKD vectors, and a CJK passphrase.
- Interpreted JS PBKDF2 (2048 rounds of SHA-512) in QuickJS would be slow. The native seed is what iOS uses as well.
- Vault PBKDF2 (600 000 iterations) on create takes seconds on low-end devices. Run it on `Dispatchers.Default` and keep the busy spinner.

### 15.10 Text handling pitfalls (Swift ↔ Kotlin)

- `lowercased()` → `lowercase(Locale.ROOT)`. **Never** the default locale: with Turkish, I → ı breaks English words, and the app ships `tr`.
- NFKD, NFD and NFC → `java.text.Normalizer`.
- Mn → `Character.getType(cp) == Character.NON_SPACING_MARK`.
- `isAlphabetic` → `Character.isAlphabetic(cp)`.
- Swift `Character.isWhitespace` (Unicode White_Space) differs from Kotlin `Char.isWhitespace()`: Kotlin includes U+001C–001F and excludes U+0085 NEL. Use an explicit White_Space set for exact parity.
- Grapheme-cluster counts (`name.count`, `password.count`, `prefix(40)`) → `BreakIterator.getCharacterInstance()` (or ICU `android.icu.text.BreakIterator`). `String.length` counts UTF-16 units: emoji and combining sequences would allow different lengths than iOS, and the vault on another platform might reject the name.
- PasswordPolicy categories → `Character.getType`: Lu/Lt, Ll, Nd, Sm/Sc/Sk/So/Pc/Pd/Ps/Pe/Pi/Pf/Po; control = `CONTROL` or `FORMAT`. Iterate code points.
- Han split and every per-character loop → iterate **code points**.
- Wordlist file split → drop the empty trailing piece (§11.1).
- `trimmingCharacters(.whitespacesAndNewlines)` → trim with the same White_Space predicate.

### 15.11 Testing checklist

- JVM tests for everything in `:core:bip39`, ported from `BackupVerificationTests` and `RecoveryCardsTests`, plus interpretations cases:
  - an unaccented Italian/Spanish ambiguity (e.g. "nomina solido comodo …")
  - Japanese with U+3000
  - Chinese without spaces
  - numbering "1." / "１)"
  - mixed case
  - typed accents treated as deliberate
  - checksum vs unknown-word errors
  - the singular/plural messages
- Compose UI tests:
  - the reveal gate (no word text in the semantics tree while concealed)
  - the QR reveal does not enable "I’ve Written Them Down in Order"
  - the cards reveal does
  - the wrong verification resets placements
  - `suspend()` clears the passwords and placements but keeps the phrase
  - predictive back at each step
  - FLAG_SECURE set on every setup window, including sheets and dialogs
- Screenshot tests: light/dark, fontScale 1.0 / 1.3 / 2.0, a 360dp-wide phone, a foldable / tablet (readable width), RTL (Arabic): words still LTR.

---

## 16. Discrepancies and open decisions

1. **Design PNGs vs code**: the code wins in every case (§13).
2. **Passwords on back from the password step**: `docs/Security.md:125` says returning to edit the phrase clears wallet passwords. The iOS code keeps `password` / `confirmation` until `suspend()` / `clearSecrets()`. Recommendation for Android: clear both when the Password step is popped. This matches the documented intent and costs nothing. Flag it to iOS.
3. **`duplicateOf` and passphrase edits**: `acceptImport` and `reviewPhraseAgain` reset `duplicateOf`, but going back from Password to the Create form and changing the passphrase does not, because `startCreate` keeps `duplicateOf`. This is practically unreachable for a freshly generated phrase. Android may reset `duplicateOf` whenever the phrase or passphrase changes.
4. **⚠︎L strings** (§14.3) should be localized on Android.
5. **Screenshot alert wording** mentions "Photos, Recently Deleted" (iOS app names). With FLAG_SECURE it is mostly unreachable. Keep the existing keys (translated), or write Android wording (e.g. Google Photos "Trash") as new keys. Product decision.
6. **Keyboard trust** (§15.8): a product decision between the in-app keyboard default and system IME only.
7. **Device-passcode error** "Set an iPhone passcode before creating a wallet. …": Android needs its own wording (lock-screen requirement for the Keystore key). See the vault spec.

---

## 17. Out of scope (iOS-only)

- Face ID / App Lock biometric wording. The setup flow never uses biometrics.
- Liquid Glass styles (`.glassProminent`, `.glass`, `safeAreaBar`, `GlassCircle`) and every `#available(iOS 26 / 18 / 17)` fallback, including the `.bar` material bottom bar.
- Writing Tools (`writingToolsBehavior(.disabled)`, iOS 18).
- `UITextInputPasswordRules`, strong-password suggestion and the `MaskedTextField` UIKit workaround (iOS clears a secure field when it toggles). The Compose `SecureTextField` obfuscation toggle has no such issue. Keep only the *intent*: no autofill or generated password on new secrets.
- `UIApplicationDelegate.shouldAllowExtensionPointIdentifier` (third-party keyboard rejection). Replaced by §15.8.
- `.privacySensitive()` redaction (widgets, Lock Screen). Replaced by FLAG_SECURE.
- The `PrivacyShield` UIWindow over the app switcher. Android equivalent: FLAG_SECURE / `setRecentsScreenshotEnabled(false)`, in the app-shell spec.
- `UIScreen.isCaptured`, `UIScreen.capturedDidChangeNotification`, `UIApplication.userDidTakeScreenshotNotification` as APIs. Their behaviour is mapped in §15.3.
- `interactiveDismissDisabled` / sheet swipe-to-dismiss. Replaced by the back rules in §15.4.
- The keyboard `inputAccessoryView` "Done" toolbar and `ToolbarItemGroup(placement: .keyboard)` as such. Replaced by IME actions and the IME-attached row.
- SF Symbols (use Material Symbols), Dynamic Type `UIFont` metrics (use `fontScale`), `CIQRCodeGenerator`, AVFoundation (CameraX), `memset_s` (use `fill(0)`).
- `SimulatorUITestContext`, App Store / TestFlight specifics, `PrivacyInfo.xcprivacy`, the iPhone-specific onboarding copy ("use a dedicated iPhone", "If iOS asks to allow wireless data…"). The onboarding spec must supply Android wording.

---

## Appendix A — Introduction pages shown before the first wallet (`Features/Onboarding/WelcomeView.swift:118-245`)

Three pages, swipeable. There is intentionally no Skip.
- Toolbar: "Cancel" (dismisses the setup) and, from page 2, "Back".
- Page dots: the current dot is 22 × 8 in the accent; the others are 8 × 8 in `systemFill`. Accessibility: `"Page %lld of %lld"`.
- Button: "Continue", or "Get Started" on the last page ("Done" when read-only).

Page layout: `SymbolHero` (size 56; 36 when the height is below 560), a centered largeTitle title, a body paragraph, then a card of icon + text points.

1. "Offline by Design" — "Your keys are created, encrypted and used only on this device. BitTool Signer works without a network connection."
   - "Recovery phrases never leave the app"
   - "No accounts, servers or cloud sync"
   - "Every signature needs your wallet password"
2. "Built for Compatibility" — "Pair BitTool Signer with a watch-only wallet on another device. It prepares transactions; BitTool Signer reviews and signs them."
   - "Open BC-UR and PSBT QR standards"
   - "Bitcoin, EVM networks, TRON and Solana"
   - "Requests are decoded before you sign"
3. "Prepare Your Device" — "For the strongest isolation, use a dedicated iPhone and keep its connections off." (iPhone wording: needs an Android variant)
   - "Turn on Airplane Mode"
   - "Confirm Wi‑Fi, cellular data and Bluetooth are off" (contains U+2011 in "Wi‑Fi")
   - "If iOS asks to allow wireless data, choose Don’t Allow: the app never connects" (iOS-only; on Android the app has no INTERNET permission, so drop this point or reword it)
   - "Keep your recovery phrase private: anyone with it can take your funds"

The details belong to the onboarding spec. The setup flow only needs the gate logic in §1.2.
