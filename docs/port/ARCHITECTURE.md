# BitTool Signer for Android: architecture and work plan

Binding for every implementation task after Wave 0. Order of authority: `DECISIONS.md` > this file > the area specs
(`app-shell.md`, `design-system.md`, `engine-contract.md`, `models-state.md`, `scan-sign.md`, `setup-backup.md`,
`vault-crypto.md`, `wallets-pairing.md`, `activity-settings.md`, `ownbit-*.md`) > `gaps.md`. Where a spec names a
module, class or file that this document places elsewhere, this document wins (see §6 for the deviations).

**Wave 0 (done, in the tree):** `:core:common`, `:core:model`, `:core:navigation` complete with tests;
`:core:engine` `EngineCalls` facade with fake-engine tests; the **public APIs** of `:core:crypto`, `:core:data` and
`:core:security` (interfaces, value types, sealed errors with exact-behaviour KDoc, no implementations);
`:core:l10n` text helpers; compiling **entry-point stubs** in every feature module except onboarding.
The KDoc in those source files is normative: it states the iOS behaviour each implementation must reproduce. The code
blocks below are an index. When this file and a KDoc disagree, the KDoc wins and the discrepancy is a bug to report.

**Frozen contracts.** Wave 1 and Wave 2 tasks may *add* members to an interface they implement (report each one), but
never rename, remove or change the meaning of an existing member, route or type without the orchestrator's approval.

---

## 1. Modules and public APIs

### 1.1 Dependency graph (as in the build files)

```
:app ──► every module below
:feature:* ──► :core:{common, model, l10n, designsystem, ui, navigation, data, security}   (convention plugin)
:core:security ──► common, model, l10n, designsystem, data(api crypto, engine)
:core:ui ──► common, l10n, api(model), api(designsystem), api(scanner)
:core:scanner ──► common, model, l10n, designsystem
:core:data ──► common, l10n, api(model), api(engine), api(crypto)
:core:crypto ──► common, l10n, api(model)
:core:engine ──► common, api(model)
:core:navigation ──► api(model)
:core:l10n ──► api(model)
:core:designsystem ──► common
:core:common      (Android, Hilt)        :core:model (pure JVM, kotlinx.serialization)
```

Rules beyond DECISIONS §3:

- Features never inject `SignerEngine`, `EngineCalls`, `WalletVault`, `DeviceKeyStore` or any repository. They talk
  to `SignerStore`, `SignerPreferences`, `ReferenceDataRepository`, `LegalDocumentRepository`, `TokenIconSource`,
  `AppAccessController`, `AppLifecycleMonitor` (phase / capture / screenshot signals), `RecoverySessionFactory`,
  `AppRouter`, `Bip39` and `SecureRandomSource` (setup only: phrase analysis and `BackupVerification.create(words,
  random.asKotlinRandom())`) and `BuildInfo`.
- `:core:designsystem` holds no domain types and no `UiText`: it takes resolved `String`s. `:core:ui` may take
  `UiText` and model types.
- Strings resolve at draw time (`UiText`), never in ViewModels, because the per-app locale can change while a
  ViewModel lives (activity recreation keeps `ViewModel`s).

### 1.2 `:core:common` (complete)

```kotlin
// coroutines/Dispatchers.kt: DECISIONS §7 (vault on Default, file I/O on IO, engine on its own thread)
@Qualifier annotation class IoDispatcher; @Qualifier annotation class DefaultDispatcher
@Qualifier annotation class MainDispatcher            // Dispatchers.Main.immediate
@Qualifier annotation class ApplicationScope          // SupervisorJob + Default; never cancelled
suspend inline fun <T> runCatchingCancellable(block: () -> T): Result<T>   // models-state §5: never swallows CancellationException
fun Throwable.rethrowIfCancellation()
// time/Clocks.kt: app-shell §15.5 (grace on elapsedRealtime), vault-crypto §17.5 (attempts on wall clock)
fun interface MonotonicClock { fun elapsedRealtimeMillis(): Long }
fun interface WallClock { fun currentTimeMillis(): Long }
// testing/FakeClocks.kt: test helpers usable from any module's tests
class FakeMonotonicClock(var nowMillis: Long = 100_000) : MonotonicClock { fun advanceBy(millis: Long) }
class FakeWallClock(var nowMillis: Long = 1_760_000_000_000L) : WallClock { fun advanceBy(millis: Long) }
// BuildInfo.kt: activity-settings §3.4, §14.8; DECISIONS §1, §9.1 (:app provides it from BuildConfig)
enum class Distribution(val flavor: String) { DirectApk("apk"), GooglePlay("play") }
data class BuildInfo(val applicationId: String, val versionName: String, val versionCode: Long,
                     val distribution: Distribution, val isDebug: Boolean, val repositoryUrl: String,
                     val signingCertificateSha256: String? = null) {   // activity-settings §14.8 "Signing certificate" row
    val buildNumber: Long; val displayVersion: String }       // "1.0.0 (1)" on iOS; Android default build number 0 → see KDoc
// log/Log.kt: DECISIONS §6 (no secrets in logs); disabled in release, lambdas never evaluated
object Log { @Volatile var enabled: Boolean; fun d(tag: String, message: () -> String) /* i, w, e */ }
// storage/Storage.kt: vault-crypto §2.2–2.3, §17.8
@Qualifier annotation class SignerDataDir      // noBackupFilesDir/BitToolSigner, or UiTestRun.root
class InvalidFileException(message: String) : IOException
object AtomicFiles { const val PENDING_PREFIX = ".pending-"
    fun write(target: File, bytes: ByteArray); fun readBounded(target: File, maxBytes: Int): ByteArray?
    fun delete(target: File); fun deleteStalePending(dir: File) }
// harness/UiTestEnvironment.kt: gaps.md G3 (debug UI-test harness hooks; release binds nothing)
data class UiTestRun(id, root: File, preferencesName, fixtures: Int, appLock: Boolean?, appearance: String?,
                     localeTag: String, allowCapture: Boolean)
interface UiTestEnvironment { val run: UiTestRun? }   // injected as Optional<UiTestEnvironment>
@Qualifier annotation class UiTestOverride            // Optional<@UiTestOverride T> replacement pattern
```

Hilt: `DispatchersModule`, `ClockModule`, `StorageModule`, `UiTestEnvironmentModule` (`@BindsOptionalOf`).

### 1.3 `:core:model` (complete, pure JVM)

Package map (one line each; the KDoc in each file cites its spec section):

| Package / file | Types | Spec |
|---|---|---|
| `util/Graphemes.kt`, `util/Text.kt` | `Graphemes.count/prefix/suffix/first/split/exceeds/hasPrefix`, `utf8Size`, Swift whitespace sets, `trimWhitespacesAndNewlines` | models-state §1.1; setup-backup §15.10 |
| `util/Hex.kt`, `util/Wipe.kt` | `Hex.encode/encodeToChars/decode/isHex/isHash64`; `wipe`, `useAndWipe`, `constantTimeEquals`, `utf8Bytes(CharArray)` | vault-crypto §11; DECISIONS §6 |
| `json/EngineJson.kt`, `json/CanonicalJson.kt` | strict typed readers (no coercion), `SignerJson`, `parseJsonObject`, `canonicalJson` (sorted keys), `prettyJson` (Apple style) | models-state §2, §3.4 |
| `UiText.kt` | `sealed UiText { Ios(english, args) / Resource(id, args) / Verbatim }`, `SignerException(text)`, `AppIssue(english, args)`, `Throwable.toUiText()` | models-state §4 |
| `SecretText.kt` | `SecretText` (CharArray, redacted, caller owns and wipes) | DECISIONS §6 |
| `WalletId.kt`, `WalletModels.kt` | `WalletId` (lowercase), `VaultMetadata`, `VaultSecret`, `PublicMetadataStatus`, `PublicWalletSnapshot`, `WalletPublicMetadata.parse`, `PublicAccount` | models-state §1.2–1.5; vault-crypto §10 |
| `Chain.kt`, `AccountProfiles.kt`, `AddressFormatter.kt` | `Chain`, `NetworkBrand`, `AccountProfile(s)`, `ConnectOptions`, `PairingLevel`, `ExportModeChoice`, `WalletLevelPairing`, `AddressFormatter` | wallets-pairing §1.1–1.3, §3.3 |
| `review/*` | `TransactionReview`, `ReviewDisplay`, `ReviewField`, `ReviewAsset`, `ReviewNetwork`, `ReviewContract`, `ReviewPresentation` helpers, `ActivityEssential`, `ReviewText` + `EngineTextLookup` | scan-sign §10–§12, §17 |
| `signing/*` | `SigningTask`, `SigningPlan`, `PlanCandidate`, `MatchingIssue`, `SigningReceipt`, `ReceiptJournal` | scan-sign §10.8, §16; models-state §6 |
| `pairing/Pairing.kt` | `QrPayloadView`, `PairingPayload`, `WalletPairingPayload` | scan-sign §10.7; wallets-pairing §1.4 |
| `scanning/QrScanFrameGate.kt` | `QrScanPurpose`, `QrScanFrameGate` (16,384-byte limit, 1 s throttle on monotonic ms) | scan-sign §6.8 |
| `Backup.kt` | `Bip39SupportedLengths`, `RecoveryCards`, `BackupVerification` (immutable, injected `Random`) | setup-backup §8.6, §8.8 |
| `CompatibleWallets.kt` | `CompatibleWallet`, `CompatibleWallets.wallets/directory/all` | wallets-pairing §1.5; activity-settings §6 |
| `Preferences.kt` | `AppLanguage` (22 entries, `es-ES`, `ar-u-nu-latn`), `Appearance`, `ONBOARDING_VERSION` | activity-settings §3.2, §3.5 |
| `PasswordPolicy.kt` | `PasswordPolicy.evaluate` (graphemes, categories, 1,024-byte cap), strength 0–4, constant-time `matches` | setup-backup §9.2 |
| `Resources.kt` | `QrErrorCorrection`, `QrMatrix`, `fun interface QrCodeEncoder`, `fun interface TokenIconSource`, `LegalDocument`, `LegalBlock`, `LegalDocumentParser` | design-system §5.12; activity-settings §5, §10 |
| `registry/Registry.kt` | `TokenRegistryEntry/Network`, `KnownContractEntry`, `ContractCategory`, `ReferenceData` | activity-settings §4.1; scan-sign §10.5 |

### 1.4 `:core:l10n` (helpers added)

```kotlin
fun UiText.resolve(resources: Resources): String          // DECISIONS §4: IosStrings by English key, else English with args
class ResourcesEngineTextLookup(resources: Resources) : EngineTextLookup   // scan-sign §17
fun Resources.reviewText(): ReviewText
object AppLocales { fun current(): AppLanguage; fun apply(language: AppLanguage); fun ensureLatinDigits() } // app-shell §15.2
```

Each work package adds its own `core/l10n/src/main/res/values/strings_<area>.xml` (English only; names prefixed
`<area>_`). Existing: `strings_crypto.xml`.

### 1.5 `:core:navigation` (complete)

```kotlin
sealed interface SignerRoute                                   // app-shell §15.10; ids only, never secrets
@Serializable data object WalletsGraph / ScanGraph / ActivityGraph / SettingsGraph : SignerRoute
sealed interface WalletRoute : SignerRoute                     // iOS WalletRoute
@Serializable data object WalletsRoute
@Serializable data class WalletDetailRoute(val walletId: String) : WalletRoute
@Serializable data class ConnectRoute(val walletId: String, val level: String = "wallet", val chain: String? = null) : WalletRoute
    // .walletLevel(id), .coinLevel(id, chain), .pairingLevel, .initialChain
@Serializable data class AddressesRoute(val walletId: String, val chain: String = "bitcoin") : WalletRoute
@Serializable data class ChangePasswordRoute(walletId) / DeleteWalletRoute(walletId) / RepairWalletRoute(walletId) / RecoveryPhraseRoute(walletId)
@Serializable data object ScanRoute; @Serializable data object ScannerRoute
@Serializable data object ActivityRoute; data class ActivityDetailRoute(receiptId); data class SignedRequestRoute(receiptId)
@Serializable data object SettingsRoute / LanguageRoute / NetworksRoute / CompatibilityRoute / ContactRoute / OpenSourceRoute / ThirdPartyNoticesRoute
@Serializable data class NetworkReferenceRoute(val chain: String); data class LegalDocumentRoute(val document: String)
@Serializable data object SubmitWalletRoute                    // root level (reached from Wallets and Settings)
val ModalRouteClasses: Set<KClass<out SignerRoute>>            // no bottom bar: Scanner, ChangePassword, Delete, Repair, RecoveryPhrase

enum class Tab(val graph: SignerRoute, val root: SignerRoute) { Wallets, Scan, Activity, Settings }
enum class SetupIntent { Create, Restore }
data class SetupPresentation(val id: Long, val intent: SetupIntent, val showsIntroduction: Boolean)
sealed interface WalletStackRequest { data class Replace(val route: WalletRoute); data object Reset }
@ActivityRetainedScoped class AppRouter @Inject constructor() {   // app-shell §9
    val tab: StateFlow<Tab>; val reselections: SharedFlow<Tab>; val scanRequest: StateFlow<Int>
    val setup: StateFlow<SetupPresentation?>; val walletStackRequests: Flow<WalletStackRequest>
    fun selectTab(tab: Tab); fun onTabClicked(tab: Tab); fun onTabShown(tab: Tab)
    fun startSetup(intent: SetupIntent, firstWallet: Boolean, introductionCompleted: Boolean)
    fun finishSetup(opening: WalletRoute?); fun cancelSetup(); fun setupDidDismiss()
    fun startScanning(); fun consumeScanRequest(): Boolean
    fun open(route: WalletRoute); fun resetNavigation() }
```

### 1.6 `:core:engine` `EngineCalls` (complete; the native host is unchanged)

```kotlin
@Singleton class EngineCalls @Inject constructor(engine: SignerEngine) {   // engine-contract §12.5, §5 (exact params)
    suspend fun mnemonic(entropyHex: CharArray): JsonObject                 // secret sent alone under its key
    suspend fun derivePublicIndex(seedHex: CharArray): JsonObject
    suspend fun derivePublicAddresses(index: WalletPublicMetadata, chain: Chain, profile: String, start: Long, count: Int): JsonObject
    suspend fun createPublicPairing(index: WalletPublicMetadata, chain: Chain, profile: String, outputMode: String, addressIndex: Long): JsonObject
    suspend fun createWalletPairing(index: WalletPublicMetadata): JsonObject            // mode "full"
    suspend fun decodeQr(frames: List<String>): JsonObject; suspend fun decodePayload(text: String): JsonObject
    suspend fun review(request: JsonObject): JsonObject
    suspend fun resolveSigningPlan(request: JsonObject, wallets: List<PlanCandidate>): JsonObject
    suspend fun signPlanTask(request: JsonObject, plan: JsonObject, taskId: String, seedHex: CharArray,
                             riskAcknowledged: Boolean, previousResult: JsonObject?): JsonObject
    suspend fun encodeResponse(request: JsonObject, result: JsonObject): JsonObject
    suspend fun beginScanSession(); suspend fun scan(frame: String): JsonObject; suspend fun endScanSession()
    suspend fun releaseWarmContext()
    companion object { fun candidates(wallets: List<PlanCandidate>): JsonArray } }
```

The authenticated `publicIndex`, the `request`, the `plan` and `previousResult` pass through as the exact
`JsonObject`s received, never re-encoded from typed models.

### 1.7 `:core:crypto` (API frozen; W1-crypto implements)

```kotlin
sealed class VaultError(text: UiText) : SignerException   // vault-crypto §6.7: InvalidPassword(policyMessage), InvalidSecret,
    // InvalidName, RandomFailure, KeyDerivationFailure, EncryptionFailure, AuthenticationFailed, DeviceKeyUnavailable,
    // DevicePasscodeRequired (Android-only string), StorageFailure, MalformedVault, UnsupportedVersion, WalletNotFound,
    // TemporarilyLocked(seconds)
interface WalletVault {                                   // vault-crypto §6–§9, §17.2 (one Mutex)
    suspend fun list(): List<VaultMetadata>
    suspend fun create(name: String, mnemonic: SecretText, passphrase: SecretText, password: SecretText, id: WalletId): VaultMetadata
    suspend fun unlock(id: WalletId, password: SecretText): VaultSecret
    suspend fun verifyPassword(id: WalletId, password: SecretText)
    suspend fun changePassword(id: WalletId, currentPassword: SecretText, newPassword: SecretText)
    suspend fun resetPassword(id: WalletId, mnemonic: SecretText, passphrase: SecretText, newPassword: SecretText)
    suspend fun delete(id: WalletId, password: SecretText)
    suspend fun publicDataKey(createIfMissing: Boolean): PublicDataKey? }
interface PublicDataKey { fun mac(message: ByteArray): ByteArray; fun verify(message: ByteArray, tag: ByteArray): Boolean } // §10.3, §17.4
interface DeviceKeyStore { fun isDeviceSecure(): Boolean; fun create(id: WalletId)                                         // §7, §17.3
    fun seal(id: WalletId, plaintext: ByteArray, aad: ByteArray): ByteArray; fun open(id: WalletId, combined: ByteArray, aad: ByteArray): ByteArray
    fun delete(id: WalletId); fun publicDataKey(createIfMissing: Boolean): PublicDataKey? }
interface JournalCipher { fun seal(plaintext: ByteArray): ByteArray; fun open(sealed: ByteArray): ByteArray }              // DECISIONS §9.3
object SignerFiles { VAULTS_DIR = "Vaults"; VAULT_EXTENSION = "bsvault"; PUBLIC_WALLETS_DIR = "PublicWallets";
    RECEIPTS_FILE = "receipts.bin"; RECEIPTS_KEY_ALIAS = "com.bittool.signer.activity.v1" }
interface SecureRandomSource { fun bytes(count: Int): ByteArray; fun entropyHex(wordCount: Int): CharArray; fun asKotlinRandom(): Random } // §11
interface Bip39Seed { fun hex(mnemonic: SecretText, passphrase: SecretText): CharArray }                                      // setup-backup §11.6
enum class Bip39Language(fileName, displayName, sha256, matchesWithoutAccents) { English … Portuguese; val assetPath; val isChinese }
data class Bip39Canonical(val phrase: String, val language: Bip39Language)   // redacted toString
sealed class Bip39Error : SignerException { Empty, UnsupportedLength, UnknownWords, Checksum, Ambiguous }
data class RecoveryPhraseDraft(…)                                             // setup-backup §11.8
interface Bip39Wordlist { val language; val isAvailable: Boolean; val words: List<String>; fun index(token, exact = false): Int?; fun suggestions(prefix, limit = 6) }
interface Bip39 { fun wordlist(language): Bip39Wordlist; fun tokens(text): List<String>; fun interpretations(text): List<Bip39Canonical>
    fun canonicalize(text): Bip39Canonical; fun validatedCanonical(phrase): Bip39Canonical; fun draft(text): RecoveryPhraseDraft
    fun wordsFromSeedQr(text: String): List<String>? }                        // setup-backup §11.2–§11.8
```

### 1.8 `:core:data` (API frozen; W1-data implements)

```kotlin
data class SignerState(wallets, isLoading, walletLoadSucceeded, publicWallets, metadataStatuses, duplicateWalletIds,
    review: TransactionReview?, plan: SigningPlan?, matchingIssue: MatchingIssue?, completedTaskIds, signatureFrames,
    signatureText, signaturePayload: QrPayloadView?, receipts, activityUnreadable: Boolean, busy, securityEpoch: Long,
    navigationResetEpoch: Long, error: UiText?)             // models-state §5.1; derived: hasSignedResponse, pendingTasks, showsScanBadge, …
data class PreparedConnect(val payload: WalletPairingPayload, val matrices: List<QrMatrix>)   // wallets-pairing §2.5
interface SignerStore {                                     // models-state §5 (= iOS AppStore); main-thread, epoch-checked
    val state: StateFlow<SignerState>
    suspend fun reload(); fun suspendSession(); fun beginSensitiveSession(); fun endSensitiveSession()
    fun setConnectPrefetchAllowed(allowed: Boolean); fun preparedConnect(walletId: WalletId): PreparedConnect?; fun setError(text: UiText?)
    suspend fun generateMnemonic(wordCount: Int): SecretText
    suspend fun createWallet(name: String, mnemonic: SecretText, passphrase: SecretText, password: SecretText): VaultMetadata
    suspend fun existingWallet(mnemonic: SecretText, passphrase: SecretText): WalletId?
    suspend fun resetPassword(walletId: WalletId, mnemonic: SecretText, passphrase: SecretText, newPassword: SecretText)
    suspend fun completeSetup(walletId: WalletId, password: SecretText); suspend fun renameWallet(walletId: WalletId, name: String)
    suspend fun changePassword(walletId: WalletId, current: SecretText, new: SecretText); suspend fun deleteWallet(walletId: WalletId, password: SecretText)
    suspend fun reveal(walletId: WalletId, password: SecretText): VaultSecret
    suspend fun addresses(walletId: WalletId, chain: Chain, profile: String, start: Long, count: Int): List<PublicAccount>
    suspend fun pairing(walletId: WalletId, chain: Chain, profile: String, outputMode: String, index: Long): PairingPayload
    suspend fun walletPairing(walletId: WalletId): WalletPairingPayload
    suspend fun beginScan(); suspend fun endScan(); suspend fun receive(frame: String): Double; suspend fun receive(frames: List<String>): Double
    suspend fun importPayload(text: String); suspend fun prepare(request: JsonObject); suspend fun retryMatching()
    suspend fun sign(taskId: String, password: SecretText, riskAcknowledged: Boolean = false); fun clearRequest()
    suspend fun clearHistory(); suspend fun reviewStoredRequest(json: String, expectedRequestHash: String): TransactionReview }
interface SignerPreferences { val values: StateFlow<PreferenceValues?>; suspend fun load(): PreferenceValues        // app-shell §2.2
    suspend fun setAppearance(appearance: Appearance); suspend fun setAppLockEnabled(enabled: Boolean); suspend fun setOnboardingVersionCompleted(version: Int) }
data class PreferenceValues(appearance, appLockEnabled, onboardingVersionCompleted) { val introductionCompleted: Boolean }
interface PublicWalletRepository { snapshot(walletId, key); save(metadata, walletId, displayName, key); rename(walletId, name, key); remove(walletId) } // vault-crypto §10
interface ReceiptRepository { suspend fun read(): List<SigningReceipt>; suspend fun write(records: List<SigningReceipt>) }   // DECISIONS §9.3
interface ReferenceDataRepository { suspend fun load(): ReferenceData; suspend fun registryVersion(): String?; suspend fun engineDigest(): BundleDigest? } // activity-settings §4, §9
data class NoticeSection(val title: String, val paragraphs: List<String>)
interface LegalDocumentRepository { suspend fun blocks(document: LegalDocument): List<LegalBlock>; suspend fun thirdPartyNotices(): List<NoticeSection> } // §10
```

Store contract points that are easy to miss (normative KDoc in `SignerStore.kt`):

- **Commits are enforced by the store** (`withContext(NonCancellable)` after the persistent write): `createWallet`,
  `resetPassword`, `deleteWallet`, `completeSetup` and the receipt append of `sign`. Callers may cancel freely.
- **Error translation**: engine-authored `EngineException`s stay verbatim; the nine Kotlin-host engine messages (exact
  code + English, listed in the KDoc) and the store's own non-catalogue messages are rethrown/published as
  `UiText.Resource` from `strings_data.xml` (DECISIONS §9.2), so features never special-case engine errors.
- `existingWallet` returns the first match in vault order; `activityUnreadable` reports a journal that could not be
  read (default UI keeps iOS behaviour; it enables an explicit "Clear Activity" recovery if product wants it).
- Debug UI-test overrides of preferences (`UiTestRun.appLock/appearance/fixtures`) are applied by `SignerPreferences`
  inside its first load, before `values` is published (no App Lock race).

### 1.9 `:core:security` (API frozen; W1-security implements)

```kotlin
data class AppAccessState(resolving, isEnabled, isLocked, isAuthenticating, biometricsAvailable, error: UiText?) { val showsLock }  // app-shell §11.2
interface AppAccessController { val state: StateFlow<AppAccessState>                                                           // §11
    fun updateWalletCount(count: Int); suspend fun setEnabled(enabled: Boolean); suspend fun authenticateBiometrics()
    suspend fun authenticate(walletId: WalletId, password: SecretText); suspend fun confirmSensitiveAction(reason: UiText)
    fun didEnterBackground(); fun willEnterForeground(); fun clearError()
    companion object { BACKGROUND_GRACE_MILLIS = 120_000L; PREFERENCE_KEY = "appLockEnabled.v1" } }
data class BiometricPromptSpec(title: UiText, description: UiText?, negativeButton: UiText)
class BiometricException(kind: Kind /* Unavailable, Lockout, Failed */, text: UiText) : SignerException                    // §15.6
interface BiometricAuthenticator { fun isAvailable(): Boolean; suspend fun authenticate(prompt: BiometricPromptSpec); fun cancel() }
enum class ScenePhase { Active, Inactive, Background }; enum class LifecycleEvent { WillEnterForeground, DidBecomeActive, WillResignActive, DidEnterBackground }
interface AppLifecycleMonitor { val phase; val events; val isCaptured; val screenshots; val foregroundActivity; fun attach(activity: FragmentActivity) } // §15.4
interface RecoveryPrivacySession { isRevealed; prompt; start(); stop(); reveal(): Boolean; hide(); acknowledgeWarning() }       // §13.1
interface RecoveryScreenshotSession { warning; start(onScreenshot); stop(); acknowledge(warning) }                               // §13.2
interface RecoverySessionFactory { fun privacySession(): RecoveryPrivacySession; fun screenshotSession(): RecoveryScreenshotSession }
interface LockWindowController { val isShowing; fun show(activity: Activity, content: @Composable () -> Unit); fun hide() }      // §15.7, hides overlays (G4.1)
interface WindowGate { val isOpen: StateFlow<Boolean>; fun setOpen(open: Boolean); suspend fun awaitOpen() }  // closed with the lock, reopened ~450 ms after unlock;
    // :app mirrors it into :core:designsystem's LocalWindowGateOpen so every dialog/sheet requested while locked waits (§1.10)
interface SecureWindowPolicy { fun apply(activity: Activity, allowCapture: Boolean) }                                           // §15.8, DECISIONS §6
```

### 1.10 `:core:designsystem` components (W1-design; generic, `String` parameters, no domain types)

Theme and tokens (design-system §2–§4, §9.2–§9.4, §9.11): `@Composable fun SignerTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit)`;
`object SignerTheme { val colors: SignerColors; val typography: SignerTypography; val shapes; val metrics }`
(`SignerColors`: page, card, elevatedCard, accent, accentOnTint, onAccent, label, secondaryLabel, tertiaryLabel,
separator, fill, success, warning, danger, mint, ink, scannerScrim); `SignerMotion` (named springs and tweens);
`SignerIcons` (Material Symbols Rounded vectors used by the app, §9.6); chain glyph drawables `R.drawable.ic_chain_bitcoin|ethereum|tron|solana|bnb|arbitrum|base`.

| Component (signature) | Behaviour | Replaces iOS | Used by |
|---|---|---|---|
| `PrimaryButton(text, onClick, modifier, icon: ImageVector? = null, busy = false, enabled = true)` | capsule 52 dp, busy keeps label width, haptic none | `ActionLabel` + `.prominentAction()` (DesignSystem.swift:95) | every primary action |
| `SecondaryButton(…same…)` | tonal capsule | `.secondaryAction()` | Welcome, Connect, backup |
| `TonalPillButton(text, icon, onClick, modifier)` | small tonal pill | wallet-card actions (WalletsTab.swift:114) | WalletsScreen |
| `TextAction(text, onClick, enabled = true, destructive = false)` | top-bar text action | toolbar `Button("Cancel"/"Done"/"Reject")` | setup, sheets, review |
| `SignerTopAppBar(title, navigation: NavIcon = Back, onNavigate, actions: @Composable RowScope.() -> Unit = {}, large = false, scrollBehavior: TopAppBarScrollBehavior? = null)`; `enum NavIcon { None, Back, Close }` | Material 3 top bar, large for tab roots | `.navigationTitle` + toolbar | all screens |
| `BottomActionBar(modifier, maxWidth = 640.dp, bottomPadding = 8.dp, canScrollUnder: Boolean, content: @Composable ColumnScope.() -> Unit)` | IME + nav-bar insets, fade and hairline when content scrolls under | `.bottomActionBar` (DesignSystem.swift:66) | setup steps, review, signature, Connect |
| `SignerCard(modifier, padding, radius = 22.dp, color, onClick: (() -> Unit)? = null, content)` | flat card, press scale when clickable | `.cardBackground` | everywhere |
| `Modifier.pressScale(interactionSource, scale = 0.98f, alpha = 0.9f)` | press feedback | button styles | cards, tiles |
| `Callout(text, modifier, title: String? = null, tone: Tone = Info)`; `enum Tone { Info, Success, Warning, Danger }` | tinted box, icon per tone | `Callout` (DesignSystem.swift:136) | review, setup, scan home, signature |
| `StatusPill(text, icon, color, modifier)` | small capsule | `StatusPill` (:187) | wallet detail, review |
| `SymbolHero(icon, modifier, color, size = 76.dp, bounce = false)` | hero icon in circles | `SymbolHero` (:213) | setup done, Welcome, empty states |
| `StepRow(number: Int?, title, detail, icon, modifier)` | numbered explanation row | `StepRow` (:237) | Scan home, onboarding |
| `DetailLine(label, value, modifier, monospaced = true)` | label + selectable value | `DetailLine` (:263) | signature, activity detail, address detail |
| `StatusToastState.show(message, icon)`, `StatusToastHost(state, modifier)` | 1.6 s, polite live region, success haptic | `StatusToast` (:302) | copy actions, password changed |
| `SegmentedControl(options: List<String>, selectedIndex, onSelect, modifier)` | sliding thumb, `Role.Tab` | `Picker(.segmented)` | Connect, backup Words/QR, word count, Compatibility, NetworkReference |
| `GroupedSection(modifier, header: String? = null, footer: String? = null, content: GroupedSectionScope.() -> Unit)`; `ValueRow(label, value)`; `NavRow(title, icon, onClick, subtitle = null, value = null, destructive = false)`; `SwitchRow(title, icon, checked, onCheckedChange, enabled = true)` | inset-grouped list rendition | `List(.insetGrouped)`, `Form`, `LabeledContent`, `Toggle` | detail, Settings, Activity detail, Addresses |
| `EmptyState(icon, title, description, modifier, action: (@Composable () -> Unit)? = null)` | centred empty view | `ContentUnavailableView` | Activity, search results |
| `SignerAlertDialog(title, text, confirm: DialogAction, dismiss: DialogAction? = null, secure = false, onDismissRequest)`; `data class DialogAction(text, destructive = false, enabled = true, onClick)` | `AlertDialog`, `SecureFlagPolicy.SecureOn` when [secure]; honours `LocalWindowGateOpen` (not shown until released); `setHideOverlayWindows(true)` on its own window (API 31+, G4.1) | `.alert`, `.confirmationDialog` | all confirmations |
| `SignerBottomSheet(onDismissRequest, skipPartiallyExpanded = true, secure = false, dismissible = true, content)` | `ModalBottomSheet` with secure policy, blocks dismiss while busy; same gate and overlay rules as the dialog | `.sheet` | paste, authorize, tips, cards, address detail, signature QR, legal |
| `val LocalWindowGateOpen: ProvidableCompositionLocal<Boolean>` (default true; a Boolean, so it does not clash with `:core:security`'s `WindowGate`), `@Composable fun rememberWindowGateReleased(): Boolean` | Ownbit `core/designsystem/.../WindowGate.kt` semantics: a window that has not appeared yet waits while closed; a window already shown stays. Every feature-owned `Dialog`/`Popup` window (e.g. setup's recovery-scanner `Dialog`) checks `rememberWindowGateReleased()` too | iOS presents beneath the lock window | :app provides it; every window |
| `val LocalSensitiveDataMarking: Boolean` (default true) | read by `AccessibilitySensitive`; `:app` may provide false in a debug UI-test run if UiAutomator cannot read sensitive nodes (gaps G5.3) | — | :app (debug harness) |
| `BrandMark(modifier, color)`, `BrandTile(size = 88.dp, modifier, shadow = true)` | exact geometry (§1.2) | `BrandTile` (Logos.swift:224) | Welcome, LaunchView, lock screen, Open Source |
| `LaunchView(modifier, captured = false)` | continues the system splash; also the privacy cover visual | `LaunchView` (RootView.swift:73), `PrivacyShieldView` | :app stages, cover |
| `Modifier.readableWidth(max = 640.dp)` | centred max width | `.readableWidth()` | every scroll page |
| `Modifier.obscuredTouchGuard(onBlocked: () -> Unit)` | consumes `ACTION_DOWN` with `FLAG_WINDOW_IS_(PARTIALLY_)OBSCURED` (gaps G4, DECISIONS §9.7) | — (Android-only) | decisive buttons (§4.8) |
| `AccessibilitySensitive(active: Boolean)` (effect) | `View.setAccessibilityDataSensitive(YES)` on API 34+ while active, restores on dispose (gaps G5) | — | revealed secrets (§4.8) |
| `Modifier.noPersonalizedLearning()`, `ExcludeFromAutofill()` (effect) | `IME_FLAG_NO_PERSONALIZED_LEARNING`; `IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS` | iOS keyboard traits | password, passphrase, phrase editor |
| `rememberSignerHaptics(): SignerHaptics` (`success()`, `selection()`, `warning()`, `reject()`) | maps to `HapticFeedbackConstants` with API fallbacks (design-system §9.11) | `.sensoryFeedback` | §4.5 |

### 1.11 `:core:ui` components (W1-design; domain-aware)

W1-design must not reference `:core:scanner` symbols (parallel compile); QR-bearing composites are assembled in the features.

| Component (signature) | Behaviour | Replaces iOS | Used by |
|---|---|---|---|
| `@Composable fun UiText.asString(): String`; `rememberReviewText(): ReviewText` | resolve with `LocalContext.current.resources` | `String(localized:)`, `ReviewText` | every screen |
| `AddressText(address, modifier, style, groupSize = 4)`; `HashText(value, modifier)` | grouped monospace, LTR, a11y groups | `AddressText`, `HashText` (AddressText.swift:43, :65) | Connect, Addresses, review, activity, directory |
| `CopyableAddress(address, modifier, style, onCopied: () -> Unit)`; `CopyableText(text, copyLabel, modifier, style, onCopied)` | copy icon swap, long-press menu | `CopyableAddress` (AddressText.swift:79), `CopyableText` (WalletDirectoryView.swift:88) | Addresses, Contact, Submit, Open Source |
| `object SignerClipboard { fun copy(context, text, label = "BitTool Signer") }` | `EXTRA_IS_SENSITIVE`, clears after 120 s if still ours | `Clipboard` (DesignSystem.swift:292) | all copy actions |
| `PayloadDisclosure(title, text, modifier, notes: List<String> = emptyList(), tag = "payload")` | expandable raw payload, ZWSP breaks | `PayloadDisclosure` (PayloadDisclosure.swift:6) | Connect, signature, activity |
| `enum PasswordKind`; `PasswordField(kind, state: TextFieldState, modifier, isNew = false, revealable = false, imeAction = Done, onSubmit = {})` | `BasicSecureTextField`, no learning, no autofill for new, eye resets on pause | `PasswordField` (PasswordViews.swift:9) | setup, authorize, wallet security, lock |
| `PasswordRequirements(password: CharSequence, confirmation: CharSequence, modifier)` | 4-bar meter + checklist from `PasswordPolicy` | `PasswordRequirementsView` (:169) | setup password, change/reset password |
| `WalletIdentityHeader(name, fingerprint: String?, avatarIdentity, modifier, size = 56.dp)` | avatar + name + fingerprint | `WalletIdentityHeader` (:221) | security pages, authorize, recovery phrase |
| `WalletAvatar(identity, name, modifier, size = 44.dp)` | SHA-256 hue gradient, first grapheme | `WalletAvatar` (Logos.swift:190) | cards, detail, review signer |
| `NetworkLogo(network: NetworkBrand, modifier, size)`, `ChainLogo(chain, …)`, `ChainGlyph(chain, …)`, `GenericNetworkLogo(…)`, `NetworkLogoStack(modifier, networks, size, ring)` | chain artwork | Logos.swift:83-157 | Welcome, cards, Connect, review, directory, activity |
| `TokenIcon(iconId: String?, symbol: String, modifier, size = 40.dp)` + `LocalTokenIconSource` | decodes `TokenIconSource.png` off-main, LruCache 200, letter fallback | `TokenIcon` (:160) | review, directory, activity |
| `WalletAppIcon(asset: String?, modifier, size = 26.dp)` | bundled wallet icons by `CompatibleWallet.asset` | `WalletAppIcon` (ConnectView.swift:683) | Connect, Compatibility |
| `NftGlyph(kind: ReviewAssetKind, modifier, size = 60.dp)`; `ContractCategoryGlyph(category)` | NFT and contract glyphs | `NFTGlyph` (RequestReviewView.swift:662) | review, activity, directory |
| `ChainPicker(selection: Chain, onSelect, modifier)` | sliding selection, muted logos | `ChainPicker` (ConnectView.swift:27) | Connect, Addresses |
| `<T> ProfileMenu(title, options, selection, label: (T) -> String, detail: (T) -> String, onSelect, modifier)` | row + `DropdownMenu` | `ProfileMenu` (:549) | Connect, Addresses |
| `AddressIndexControl(index: Long, onIndexChange, pathPrefix, modifier)` | digits only, clamp 2,147,483,647, repeat buttons | `AddressIndexControl` (:476) | Connect (TRON) |
| `IntendedWallets(wallets: List<CompatibleWallet>, modifier, onSubmitWallet: () -> Unit)`; `WalletGroup(title, wallets)` | compatible-wallet blocks | `IntendedWalletsView`, `WalletGroup`, `SubmitWalletLink` (:602-699) | Connect, Compatibility |
| `ReviewSummaryCard(review, reviewText, modifier)` | headline, amount, counterparty, network, NFT box | `ReviewSummaryCard` (RequestReviewView.swift:221) | review, activity detail |
| `ContractNotice(contract: ReviewContract, modifier)` | known-contract card | `ContractNotice` (:547) | review, activity detail |
| `ReviewWarnings(warnings: List<String>, modifier, isSevere: (String) -> Boolean = ReviewPresentation::isSevereWarning)` | one verbatim `Callout` per warning, danger when `isSevere`, else warning. Review passes the default (HIGH RISK / UNLIMITED / HIGH FEE); Activity's Signed Request passes `ReviewPresentation::isSevereStoredWarning` (any "HIGH" prefix, `ActivityTab.swift:292`) | RequestReviewView.swift:71-82 | review, Signed Request |
| `ReviewDetailsCard(display: ReviewDisplay, reviewText, modifier, showsTokenId)` | field rows by `rowStyle`, flow sections | `ReviewDetailsCard` (:694) | review, Signed Request |
| `LegalDocumentView(blocks: List<LegalBlock>, modifier, contentPadding)` | headings and paragraphs, no links | `LegalDocumentView` (LegalDocumentView.swift:13) | Welcome sheet, Settings legal pages |

### 1.12 `:core:scanner` components (W1-scanner; no `:core:designsystem` symbols in Wave 1, `MaterialTheme` only)

| Component (signature) | Behaviour | Replaces iOS | Used by |
|---|---|---|---|
| `class ZxingQrCodeEncoder @Inject constructor() : QrCodeEncoder` (Hilt `@Binds`) | ZXing, EC from `QrErrorCorrection`, margin 1, UTF-8 hint only for non-ASCII (design-system §9.5) | `QRRenderer` (QRCodeView.swift) | `:core:data` (prefetched matrices), `QrCodeView` |
| `QrCodeView(frames: List<String>, modifier, maxSide = 320.dp, intervalMillis = 250, initial: List<QrMatrix>? = null)` | encodes all frames off-main first, nearest-neighbour draw, animated player with pause and counter, keep-screen-on while animated | `QRCodeView` (QRCodeView.swift:29) | Connect, signature, activity QR sheet, recovery QR |
| `QrScannerScreen(title: String, hint: String, purpose: QrScanPurpose, progress: Float?, status: String?, error: String?, onReset: () -> Unit, onClose: () -> Unit, onScan: (String) -> Unit, modifier)` | full-screen dark camera, viewfinder with progress ring, torch, status panel, Back closes, `QrScanFrameGate` inside | `QRScannerView` (QRScannerView.swift:9) | `:feature:sign` ScannerRoute, `:feature:setup` import |
| `rememberCameraPermissionState(): CameraPermissionState` (`status: Granted/NotAsked/Denied/PermanentlyDenied`, `request()`, `openSettings()`) | runtime CAMERA flow, re-check on resume (scan-sign §23.5) | AVCaptureDevice authorization | QrScannerScreen |
| `internal class QrFrameAnalyzer(onValues: (List<String?>) -> Unit) : ImageAnalysis.Analyzer` | Y plane, `QRCodeMultiReader`, inverted retry, strict UTF-8 (§23.3) | `metadataOutput` (QRScannerView.swift:357) | QrScannerScreen |

Gate rejections are shown with `strings_scanner.xml` keys holding the exact English of `QrScanFrameGate.Issue.english(purpose)`.

### 1.13 Feature entry points (stubs in the tree; owners replace the bodies)

```kotlin
// :feature:wallets   WalletsNavigation.kt
fun NavGraphBuilder.walletsDestinations(navController: NavController)        // inside navigation<WalletsGraph>
// :feature:setup     SetupNavigation.kt
fun NavGraphBuilder.recoveryPhraseDestination(navController: NavController)  // :app registers it inside WalletsGraph
@Composable fun SetupFlowHost(presentation: SetupPresentation, onFinished: (WalletRoute?) -> Unit, onCancelled: () -> Unit, modifier: Modifier = Modifier)
// :feature:sign      SignNavigation.kt
fun NavGraphBuilder.scanDestinations(navController: NavController)           // inside navigation<ScanGraph>
// :feature:activity  ActivityNavigation.kt
fun NavGraphBuilder.activityDestinations(navController: NavController)       // inside navigation<ActivityGraph>
// :feature:settings  SettingsNavigation.kt
fun NavGraphBuilder.settingsDestinations(navController: NavController)       // inside navigation<SettingsGraph>
fun NavGraphBuilder.settingsSharedDestinations(navController: NavController) // root level: SubmitWalletRoute
// :feature:onboarding (W2-shell creates)
@Composable fun WelcomeScreen(onStart: (SetupIntent) -> Unit, modifier: Modifier = Modifier)
@Composable fun OnboardingPager(intent: SetupIntent, onComplete: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier)
```

---

## 2. Screens and navigation

### 2.1 Root composition (`:app`, app-shell §4, §7, §11–§12, §15)

```
MainActivity (AppCompatActivity, single task)
  onCreate: DebugUiTestHarness.configure (debug) → installSplashScreen (keep while AppAccessState.resolving)
            → SecureWindowPolicy.apply → setHideOverlayWindows(true) (API 31+) → AppLifecycleMonitor.attach
            → AppLocales.ensureLatinDigits → super.onCreate → setContent { SignerTheme(appearance) { Root() } }
Root()
  layer 1  RootStages: Loading → LaunchView | Failed → LoadFailureScreen | Welcome → WelcomeScreen | Main → MainScaffold
  layer 2  SetupOverlay (when AppRouter.setup != null): OnboardingPager (showsIntroduction) → SetupFlowHost
  layer 3  PrivacyCover (LaunchView(captured)) while phase != Active or isCaptured
  layer 4  UnableToContinueDialog (SignerState.error; deferred by WindowGate)
  window   AppLockScreen in LockWindowController (above every dialog and sheet) while AppAccessState.showsLock
```

`RootViewModel` reproduces every RootView reaction (app-shell §4.3): wallet count → `AppAccessController.updateWalletCount`;
count 0 → `selectTab(Wallets)` + `resetNavigation()`; `navigationResetEpoch` → `resetNavigation()`;
`review.requestHash` becomes non-null → `selectTab(Scan)`; lock or capture → `suspendSession()`;
`canPrefetch` (resumed, unlocked, not captured, stage Main) → `setConnectPrefetchAllowed`; `onStart` →
`willEnterForeground()`; `onStop` (not changing configurations) → `suspendSession()` then `didEnterBackground()`.

Further `:app` duties fixed here so nobody guesses:
- `WindowGate.setOpen(!showsLock && !captured)` (closed in the same frame the lock engages, reopened ~450 ms after
  unlock) and `CompositionLocalProvider(LocalWindowGateOpen provides …)` around layers 1, 2 and 4 — **not** around the
  lock window's content. `LocalTokenIconSource` (Hilt `TokenIconSource`) at the root.
- Appearance: `AppCompatDelegate.setDefaultNightMode` (+ `UiModeManager.setApplicationNightMode` on API 31+) from
  `SignerPreferences.values.appearance` in `SignerApplication` and on every change; Settings only writes the
  preference.
- A failed first load shows `LoadFailureScreen` **and** the "Unable to Continue" alert with the error, as iOS does
  (app-shell §4.2, §15.14 decided: parity).

### 2.2 Navigation graph

```
NavHost(startDestination = WalletsGraph)                         :app MainScaffold, stage Main only
├─ navigation<WalletsGraph>(startDestination = WalletsRoute)     walletsDestinations + recoveryPhraseDestination
│   ├─ WalletsRoute                      tab root
│   ├─ WalletDetailRoute(walletId)
│   ├─ ConnectRoute(walletId, level, chain?)
│   ├─ AddressesRoute(walletId, chain)
│   ├─ ChangePasswordRoute(walletId)     modal (no bar)
│   ├─ DeleteWalletRoute(walletId)       modal
│   ├─ RepairWalletRoute(walletId)       modal
│   └─ RecoveryPhraseRoute(walletId)     modal, :feature:setup
├─ navigation<ScanGraph>(startDestination = ScanRoute)           scanDestinations
│   ├─ ScanRoute                         Home | Review | Signature chosen from SignerState (AnimatedContent)
│   └─ ScannerRoute                      modal, full screen, dark subtree
├─ navigation<ActivityGraph>(startDestination = ActivityRoute)   activityDestinations
│   ├─ ActivityRoute
│   ├─ ActivityDetailRoute(receiptId)
│   └─ SignedRequestRoute(receiptId)     shares the detail entry's ViewModel
├─ navigation<SettingsGraph>(startDestination = SettingsRoute)   settingsDestinations
│   ├─ SettingsRoute, LanguageRoute, NetworksRoute, NetworkReferenceRoute(chain),
│   └─ CompatibilityRoute, ContactRoute, OpenSourceRoute, ThirdPartyNoticesRoute, LegalDocumentRoute(document)
└─ SubmitWalletRoute                     root level, settingsSharedDestinations (keeps the current tab selected)
```

Not destinations: the setup flow (overlay with a ViewModel-owned step path, setup-backup §15.4), Welcome and
onboarding (stages), the lock screen (window), every sheet and dialog (hosted by their screen).

Tab bar (`NavigationBar`, app-shell §8, §15.10): Wallets, Scan (badge `showsScanBadge`), Activity, Settings.
Tapping a tab: `AppRouter.onTabClicked` → glue navigates `navigate(tab.graph) { popUpTo(graph.findStartDestination()) { saveState = true }; launchSingleTop = true; restoreState = true }`.
Reselecting: glue pops to `tab.root`; the root screen also scrolls to top (it collects `AppRouter.reselections`).
The bar hides when the current destination's class is in `ModalRouteClasses`, and slides (no fade) with the IME.

### 2.3 Back behaviour

| Where | System Back / predictive back |
|---|---|
| Wallets root | default (task moves to back; `onStop` → suspend) |
| Scan, Activity, Settings roots | back to the Wallets tab (start destination); never mutates the request state. A review or a signature stays on Scan (badge). **Deliberate deviation** from scan-sign §23.7 (which proposed Back = Reject on the review and Back = Done on the result): Back never discards a reviewed request or partial signatures; Reject and Done stay explicit buttons (§6 item 12) |
| Pushed pages | pop one page |
| Modal routes (Change Password, Delete, Repair, Recovery Phrase) | same as Cancel: wipe fields, pop; consumed while busy |
| ScannerRoute | close the camera (`session.stop`), pop |
| Setup overlay | setup-backup §15.4 table (intro page > 0 → previous page; first step with draft secrets → "Discard this wallet?"; busy → consumed; pushed steps → previous step; Done → Done) |
| Sheets | dismiss unless busy (Authorize while signing, delete in progress) |
| Dialogs | dismiss = the cancel action |
| Lock window | `moveTaskToBack(true)`; never unlocks |
| Stage Welcome | default |

### 2.4 AppRouter deep routing (glue in `:app`)

- `tab` → navigate to the tab graph as above. `OnDestinationChangedListener` maps the destination's parent graph to a
  `Tab` and calls `onTabShown` (destinations outside the tab graphs, such as `SubmitWalletRoute`, leave it unchanged).
- `walletStackRequests`, collected in order: `Replace(route)` → ensure the Wallets graph is current (tab navigation),
  then `navigate(route) { popUpTo<WalletsRoute>(); launchSingleTop = true }`. `Reset` → when Wallets is current,
  `popBackStack<WalletsRoute>(inclusive = false)`, otherwise `clearBackStack<WalletsGraph>()` (drops its saved stack).
- `setup` → `SetupOverlay` enters with a vertical slide; on exit animation end `setupDidDismiss()` opens the pending
  route. Setup's `onFinished(route)` → `finishSetup(route)`; `onCancelled` → `cancelSetup()`.
- `scanRequest` → `ScanRoute`'s ViewModel calls `consumeScanRequest()` on first composition and on each change and
  navigates to `ScannerRoute` when true.
- Destinations whose id no longer exists (deleted wallet or receipt) pop themselves; restored back stacks may outlive them.

### 2.5 Screen inventory (every iOS screen, sheet, dialog, alert, confirmation and cover)

iOS paths are relative to `BitSigner/BitToolSigner/`. VM = `@HiltViewModel`.

| # | iOS view | iOS file:line | Android composable | Module | Host | ViewModel | Reached from |
|---|---|---|---|---|---|---|---|
| 1 | Launch screen + `LaunchView` | Resources/LaunchScreen.storyboard; Features/Main/RootView.swift:73 | system splash + `LaunchView` | :app (:core:designsystem) | stage Loading | `RootViewModel` | cold start |
| 2 | `LoadFailureView` | Features/Main/RootView.swift:92 | `LoadFailureScreen` | :app | stage Failed | `RootViewModel` | `reload()` failed |
| 3 | `WelcomeView` | Features/Onboarding/WelcomeView.swift:5 | `WelcomeScreen` | :feature:onboarding | stage Welcome | `WelcomeViewModel` | zero wallets |
| 4 | Legal sheet from Welcome | WelcomeView.swift:54; Features/Settings/LegalDocumentView.swift:106 | `LegalDocumentSheet` (`LegalDocumentView`) | :feature:onboarding | `SignerBottomSheet` | `WelcomeViewModel` | Welcome "Terms of Use" / "Privacy Policy" |
| 5 | `OnboardingView` (3 pages) | WelcomeView.swift:118; shown by Features/Setup/WalletSetupFlow.swift:36 | `OnboardingPager` | :feature:onboarding | inside `SetupOverlay` | none (stateless; `:app` persists completion) | first setup only |
| 6 | Setup sheet container | RootView.swift:41 | `SetupOverlay` | :app | full-screen overlay | `RootViewModel` | `AppRouter.startSetup` |
| 7 | `MainTabView` | Features/Main/MainTabView.swift:3 | `MainScaffold` (NavigationBar + NavHost) | :app | stage Main | `MainViewModel` | wallets exist |
| 8 | "Unable to Continue" alert | RootView.swift:66 | `UnableToContinueDialog` | :app | `SignerAlertDialog` | `RootViewModel` | `SignerState.error` |
| 9 | `AppLockView` (biometric + password form) | Features/Lock/AppLockView.swift:6, :91 | `AppLockScreen` | :app | `LockWindowController` window | `AppLockViewModel` | `AppAccessState.showsLock` |
| 10 | Privacy shield window / `PrivacyShieldView` | Services/PrivacyShield.swift:18, :68; RootView.swift:31 | `PrivacyCover` (`LaunchView(captured)`) | :app | in-tree layer 3 | `RootViewModel` | not resumed, or captured |
| 11 | Create step | WalletSetupFlow.swift:126 | `CreateWalletStep` | :feature:setup | setup path `[]` | `WalletSetupViewModel` | Add Wallet › Create, Welcome |
| 12 | Import step | WalletSetupFlow.swift:167 | `ImportWalletStep` | :feature:setup | setup path `[]` | `WalletSetupViewModel` | Add Wallet › Import, Welcome |
| 13 | Recovery QR scanner cover | WalletSetupFlow.swift:217 | `QrScannerScreen(purpose = RecoveryPhrase)` | :core:scanner | full-screen `Dialog` over setup | `WalletSetupViewModel` | Import "Scan Recovery QR" |
| 14 | "Which language is your backup in?" | WalletSetupFlow.swift:230 | `BackupLanguageDialog` | :feature:setup | `SignerAlertDialog(secure)` list | `WalletSetupViewModel` | ambiguous import |
| 15 | Passphrase options | WalletSetupFlow.swift:245 | `PassphraseOptions` | :feature:setup | inline | `WalletSetupViewModel` | create / import steps |
| 16 | Backup step (words, QR, cards entry) | WalletSetupFlow.swift:279; Features/Backup/RecoveryBackupViews.swift:15 | `BackupPhraseStep` + `RecoveryBackupPanel` | :feature:setup | setup path `[Backup]` | `WalletSetupViewModel` | Create › Continue |
| 17 | Security Tips sheet | WalletSetupFlow.swift:310; RecoveryBackupViews.swift:68, :357 | `SecurityTipsSheet` | :feature:setup | `SignerBottomSheet(skipPartiallyExpanded = false)` | none | backup panel "Security Tips" |
| 18 | Recovery Cards sheet | RecoveryBackupViews.swift:69, :175 | `RecoveryCardsSheet` | :feature:setup | `SignerBottomSheet(secure)` | none (session from factory) | backup panel |
| 19 | Verify step | WalletSetupFlow.swift:314; RecoveryBackupViews.swift:422, :491 | `VerifyBackupStep` | :feature:setup | setup path `[Backup, Verify]` | `WalletSetupViewModel` | Backup › Continue |
| 20 | Password step | WalletSetupFlow.swift:346 | `SetPasswordStep` | :feature:setup | setup path `[…, Password]` | `WalletSetupViewModel` | Verify or Import › Continue |
| 21 | Done step | WalletSetupFlow.swift:402 | `SetupCompleteStep` | :feature:setup | setup path `[…, Done]` | `WalletSetupViewModel` | wallet saved |
| 22 | "Discard this wallet?" | WalletSetupFlow.swift:46 | `DiscardWalletDialog` | :feature:setup | `SignerAlertDialog` | `WalletSetupViewModel` | Cancel / Back with draft secrets |
| 23 | "Screenshot Detected" (privacy prompt) | Features/Setup/RecoveryPhraseViews.swift:331 | `ScreenshotDetectedDialog` | :feature:setup | `SignerAlertDialog(secure)` | session state | screenshot over concealed material |
| 24 | Screenshot warning (direct material) | RecoveryPhraseViews.swift:365 | `ScreenshotWarningDialog` | :feature:setup | `SignerAlertDialog(secure)` | session state | screenshot over editor / verify board |
| 25 | Saved-wallet Recovery Phrase sheet | Features/Wallets/WalletSecuritySheets.swift:37 (presented WalletDetailView.swift:109) | `RecoveryPhraseScreen` | :feature:setup | `RecoveryPhraseRoute` | `RecoveryPhraseViewModel` | Wallet detail "Recovery Phrase" |
| 26 | `WalletsTab` | Features/Wallets/WalletsTab.swift:3 | `WalletsScreen` | :feature:wallets | `WalletsRoute` | `WalletsViewModel` | tab |
| 27 | "Add Wallet" menu | WalletsTab.swift:36 | `AddWalletMenu` | :feature:wallets | `DropdownMenu` | `WalletsViewModel` | top bar "+" |
| 28 | `WalletDetailView` | Features/Wallets/WalletDetailView.swift:8 | `WalletDetailScreen` | :feature:wallets | `WalletDetailRoute` | `WalletDetailViewModel` | wallet card |
| 29 | "Rename Wallet" alert | WalletDetailView.swift:117 | `RenameWalletDialog` | :feature:wallets | `SignerAlertDialog` + text field | `WalletDetailViewModel` | detail "Rename" |
| 30 | "Unable to Rename" alert | WalletDetailView.swift:126 | `UnableToRenameDialog` | :feature:wallets | `SignerAlertDialog` | `WalletDetailViewModel` | rename failure |
| 31 | "Password changed" toast | WalletDetailView.swift:131 | `StatusToastHost` | :feature:wallets | toast | `WalletDetailViewModel` | Change Password success |
| 32 | `ChangePasswordSheet` | WalletSecuritySheets.swift:124 | `ChangePasswordScreen` | :feature:wallets | `ChangePasswordRoute` | `ChangePasswordViewModel` | detail |
| 33 | `DeleteWalletSheet` | WalletSecuritySheets.swift:193 | `DeleteWalletScreen` | :feature:wallets | `DeleteWalletRoute` | `DeleteWalletViewModel` | detail |
| 34 | Delete "Wallet Password" alert | WalletSecuritySheets.swift:249 | `DeletePasswordDialog` | :feature:wallets | `SignerAlertDialog(secure)` + `PasswordField` | `DeleteWalletViewModel` | Delete › confirm |
| 35 | Biometric check before delete | Services/AppAccessController.swift:149 | system `BiometricPrompt` | :core:security | system | `DeleteWalletViewModel` | Delete with App Lock on |
| 36 | `RepairWalletSheet` | WalletSecuritySheets.swift:299 | `RepairWalletScreen` | :feature:wallets | `RepairWalletRoute` | `RepairWalletViewModel` | detail "Repair" |
| 37 | `AddressesView` | Features/Wallets/AddressesView.swift:5 | `AddressesScreen` | :feature:wallets | `AddressesRoute` | `AddressesViewModel` | card "Addresses", detail |
| 38 | `AddressDetailSheet` | AddressesView.swift:71, :170 | `AddressDetailSheet` | :feature:wallets | `SignerBottomSheet` | `AddressesViewModel` | address row |
| 38b | Address row context menu ("Copy Address", "Show QR Code") | AddressesView.swift:143-168 | long-press `DropdownMenu` | :feature:wallets | menu | `AddressesViewModel` | address row long-press (copy shows no toast) |
| 39 | `ConnectView` (Coin Level) | Features/Wallets/ConnectView.swift:80 | `ConnectScreen` | :feature:wallets | `ConnectRoute(level = coin)` | `ConnectViewModel` | detail chain rows |
| 40 | `WalletLevelPairingView` | ConnectView.swift:359 | `WalletLevelPairing` (section of ConnectScreen) | :feature:wallets | `ConnectRoute(level = wallet)` | `ConnectViewModel` | card "Pair", detail, setup "Pair a Wallet" |
| 41 | Address type / mode menus | ConnectView.swift:199, :549 | `ProfileMenu` | :core:ui | `DropdownMenu` | `ConnectViewModel` | Connect rows |
| 42 | Connect copy action | ConnectView.swift:174 | top-bar action + toast | :feature:wallets | toast | `ConnectViewModel` | Connect |
| 43 | Index keyboard "Done" | ConnectView.swift:533 | IME action Done | :core:ui | IME | `ConnectViewModel` | TRON index field |
| 44 | `SubmitWalletView` | Features/Settings/SubmitWalletView.swift:5 (links ConnectView.swift:643, WalletDirectoryView.swift:30) | `SubmitWalletScreen` | :feature:settings | `SubmitWalletRoute` (root) | none | Connect, Compatibility |
| 45 | `ScanTab` switcher | Features/Sign/ScanTab.swift:88 | `ScanTabScreen` | :feature:sign | `ScanRoute` | `ScanTabViewModel` | tab, review arrival |
| 46 | `ScanHomeView` | ScanTab.swift:108 | `ScanHome` | :feature:sign | `ScanRoute` (no request) | `ScanHomeViewModel` | tab |
| 47 | `QRScannerView` cover | ScanTab.swift:144; Features/Sign/QRScannerView.swift:9 | `SigningScannerScreen` (wraps `QrScannerScreen`) | :feature:sign | `ScannerRoute` | `ScannerViewModel` (iOS `ScanSession`) | Scan card, Wallets "Scan", `startScanning` |
| 48 | Camera permission prompt and "Camera Access Needed" | QRScannerView.swift:21, :51 | permission dialog + scanner panel | :core:scanner | system dialog | `CameraPermissionState` | first scan |
| 49 | `PasteRequestSheet` | ScanTab.swift:151, :263 | `PasteRequestSheet` | :feature:sign | `SignerBottomSheet` | `ScanHomeViewModel` | "Paste Request" |
| 50 | File importer | ScanTab.swift:152 | `OpenDocument` launcher | :feature:sign | system picker | `ScanHomeViewModel` | "Import File" |
| 51 | `RequestReviewView` | Features/Sign/RequestReviewView.swift:5 | `RequestReviewScreen` | :feature:sign | `ScanRoute` (review) | `RequestReviewViewModel` | decoded request |
| 52 | "Discard partial signatures?" | RequestReviewView.swift:51 | `RejectRequestDialog` | :feature:sign | `SignerAlertDialog` | `RequestReviewViewModel` | Reject after a partial signature |
| 53 | `AuthorizeSheet` | RequestReviewView.swift:56, :821 | `AuthorizeSheet` | :feature:sign | `SignerBottomSheet(secure)` | `RequestReviewViewModel` | Sign / task card |
| 54 | `SignatureResultView` | Features/Sign/SignatureResultView.swift:4 | `SignatureResultScreen` | :feature:sign | `ScanRoute` (signed) | `SignatureResultViewModel` | last task signed |
| 55 | Signature file exporter | SignatureResultView.swift:64 | `CreateDocument("text/plain")` launcher | :feature:sign | system picker | `SignatureResultViewModel` | "Export" |
| 56 | `ActivityTab` | Features/Activity/ActivityTab.swift:4 | `ActivityScreen` | :feature:activity | `ActivityRoute` | `ActivityViewModel` | tab |
| 57 | "Clear all activity?" | ActivityTab.swift:53 | `ClearActivityDialog` | :feature:activity | `SignerAlertDialog` | `ActivityViewModel` | top bar "Clear" |
| 58 | `ActivityDetailView` | ActivityTab.swift:44, :111 | `ActivityDetailScreen` | :feature:activity | `ActivityDetailRoute` | `ActivityDetailViewModel` | activity row |
| 59 | `SignedRequestView` | ActivityTab.swift:187, :280 | `SignedRequestScreen` | :feature:activity | `SignedRequestRoute` | parent `ActivityDetailViewModel` | detail "All Decoded Fields" |
| 60 | `SignatureQRSheet` | ActivityTab.swift:169, :307 | `SignatureQrSheet` | :feature:activity | `SignerBottomSheet` | `ActivityDetailViewModel` | detail "Show QR" |
| 61 | Activity file exporter | ActivityTab.swift:172 | `CreateDocument("text/plain")` launcher | :feature:activity | system picker | `ActivityDetailViewModel` | detail "Export" |
| 62 | `SettingsTab` | Features/Settings/SettingsTab.swift:4 | `SettingsScreen` | :feature:settings | `SettingsRoute` | `SettingsViewModel` | tab |
| 63 | App Lock toggle prompt | SettingsTab.swift:24 | system `BiometricPrompt` | :core:security | system | `SettingsViewModel` | App Lock switch |
| 64 | Theme picker | SettingsTab.swift:45 | `ThemeDialog` | :feature:settings | `SignerAlertDialog` single choice | `SettingsViewModel` | "Theme" |
| 65 | Language picker | SettingsTab.swift:53 | `LanguageScreen` | :feature:settings | `LanguageRoute` | `LanguageViewModel` | "Language" |
| 66 | `TokenDirectoryView` | Features/Settings/TokenDirectoryView.swift:151 (link SettingsTab.swift:68) | `NetworksScreen` | :feature:settings | `NetworksRoute` | `NetworksViewModel` | "Networks, Tokens & Contracts" |
| 67 | `NetworkReferenceView` | TokenDirectoryView.swift:241 (links :165, :192, :42) | `NetworkReferenceScreen` | :feature:settings | `NetworkReferenceRoute(chain)` | `NetworkReferenceViewModel` | network rows, "All EVM networks" |
| 68 | Token / contract context menu | TokenDirectoryView.swift:325, :354 | long-press `DropdownMenu` | :feature:settings | menu | `NetworkReferenceViewModel` | long-press |
| 69 | `CompatibilityView` | SettingsTab.swift:143 (link :71) | `CompatibilityScreen` | :feature:settings | `CompatibilityRoute` | `CompatibilityViewModel` | "Compatibility" |
| 70 | `WalletDirectoryView` | Features/Settings/WalletDirectoryView.swift:9 | `WalletDirectory` (segment) | :feature:settings | segment of CompatibilityScreen | `CompatibilityViewModel` | "Wallets" segment |
| 71 | `ContactView` | SettingsTab.swift:118 (link :81) | `ContactScreen` | :feature:settings | `ContactRoute` | none | "Contact Us" |
| 72 | `OpenSourceView` | SettingsTab.swift:223 (link :79) | `OpenSourceScreen` | :feature:settings | `OpenSourceRoute` | `OpenSourceViewModel` | "Fully Open Source" |
| 73 | `LicensesView` | SettingsTab.swift:333 (link :289) | `ThirdPartyNoticesScreen` | :feature:settings | `ThirdPartyNoticesRoute` | `ThirdPartyNoticesViewModel` | Open Source › notices |
| 74 | `LegalDocumentView` | Features/Settings/LegalDocumentView.swift:13 (links SettingsTab.swift:83, :85) | `LegalDocumentScreen` | :feature:settings | `LegalDocumentRoute(document)` | `LegalDocumentViewModel` | "Terms of Use", "Privacy Policy" |
| 75 | Copied / status toasts | Design/DesignSystem.swift:302 | `StatusToastHost` | :core:designsystem | toast | owner screen | copy actions |

---

## 3. Work packages

Every package: work in your own worktree branch, commit there, touch only the owned paths (DECISIONS §8), add strings
only to your `strings_<area>.xml`, add every testTag the e2e suite needs (gaps.md G1) on your screens, and report
anything stubbed. "Acceptance" lists the Gradle tasks that must pass (run through `tools/gradle-slot.sh`).

### Wave 1 (parallel; compile only against Wave-0 APIs)

Wave-1 packages cannot see each other's implementations or fakes until the orchestrator merges them. Each package
therefore tests against **its own test doubles** of the other Wave-1 interfaces (in its `src/test` / `src/androidTest`,
not published), plus the real `:core:engine` (done) in instrumented tests. The published `testing` fakes listed below
are for Wave 2. After the Wave-1 merge the orchestrator reruns `:core:data:connectedDebugAndroidTest` with the real
crypto bindings (Integration).

**W1-crypto** — owns `core/crypto/**`, `core/l10n/src/main/res/values/strings_crypto.xml`.
- Specs: vault-crypto (all, esp. §2–§11, §13, §17), setup-backup §11, DECISIONS §6, §9.6, gaps G7.3, G8.
- Provides (implementations + `CryptoModule`): `DefaultWalletVault` (one `Mutex`, format 2, attempts file, header bytes
  verbatim), `AndroidKeystoreDeviceKeyStore`, `InMemoryDeviceKeyStore` (main source set, `testing` package; for the
  Integration rerun of W1-data's tests and for Wave 2), `KeystoreJournalCipher`, `DefaultBip39` + asset `Bip39Wordlist` (SHA-256 checked, NFKD, accent folding),
  `Pbkdf2Bip39Seed`, `SystemSecureRandomSource` (`SecureRandom`). Binds `DeviceKeyStore` as
  `Optional<@UiTestOverride DeviceKeyStore>.orElse(keystore)` and declares that optional.
- Consumes: `:core:model` (`SecretText`, `PasswordPolicy`, `VaultMetadata`, `WalletId`), `AtomicFiles`, `@SignerDataDir`, clocks.
- iOS tests: SecurityTests (9 of 12: entropy, PBKDF2 vector, round trip, tampering, throttling across instances,
  rotation + re-authenticated delete, and the three password-reset cases — they are vault tests; `passwordRules…` is
  done in model, the two JS-bridge/engine cases belong to `:core:engine`), BIP39LanguageTests (all 9, vectors JSON copied to `src/test/resources`), HardeningTests (native seed vectors,
  wordlist canonical, SeedQR, draft, vault header bytes). `nativeSeedDerivesTheSameWalletAsTheEngine` needs the engine:
  instrumented (`:core:crypto` androidTest may depend on `:core:engine`).
- Acceptance: `:core:crypto:testDebugUnitTest`, `:core:crypto:connectedDebugAndroidTest` (Keystore, StrongBox fallback, alias never replaced).

**W1-data** — owns `core/data/**`, `strings_data.xml`.
- Specs: models-state §5–§7, §11, §12; scan-sign §1, §7–§9, §14, §16, §18; wallets-pairing §1.7, §2; setup-backup §3;
  activity-settings §2.2, §2.5, §4–§5, §9–§10, §14.2; vault-crypto §10, §11; engine-contract §9; DECISIONS §9.3;
  gaps G6 (store and engine-host messages, §4.3 table), G7.2, G8.
- Provides: `DefaultSignerStore` (`@Singleton`, epochs, prefetch, scan pipeline, signing, receipts), DataStore
  `SignerPreferences` (file name from `UiTestRun.preferencesName` when present), `FilePublicWalletRepository`,
  `EncryptedReceiptRepository` (`JournalCipher` + `AtomicFiles` + `ReceiptJournal`, ciphertext cap before decrypt),
  `AssetReferenceDataRepository`, `AssetLegalDocumentRepository`, `PackTokenIconSource` (mmap, `noCompress += "pack"`),
  `DataModule`, the asset `legal/THIRD_PARTY_LICENSES_ANDROID.txt` (notices of the Android dependencies in the version
  catalogue; the orchestrator refreshes it at release); reference fakes for Wave 2 in
  `com.bittool.signer.core.data.testing` (main source set): `FakeSignerStore` (scriptable state + recorded calls),
  `FakeSignerPreferences`, `FakeReferenceDataRepository`, `FakeLegalDocumentRepository`.
- Consumes: `EngineCalls`, `WalletVault`, `DeviceKeyStore` (via vault only), `JournalCipher`, `Bip39`
  (`validatedCanonical` in create/existing/reset), `Bip39Seed`, `SecureRandomSource`, `QrCodeEncoder` (bound by
  W1-scanner). In Wave 1 all of these except `EngineCalls` are test doubles written by W1-data (an in-memory vault
  that counts unlocks, an English-only `Bip39` and a `Mac("HmacSHA512")` `Bip39Seed` in test code, a fake encoder).
- iOS tests: SessionTests (15) and BitToolSignerTests (4) as **instrumented** tests — they need the real engine
  (`ControlledSigningEngine` wraps `SigningEngine()`): port it as a `SignerEngine` decorator with operation gates,
  invocation counts and scripted orchestration tasks over the real `QuickJsSignerEngine`; ActivityRecordTests (6 store
  cases: capture, re-review, mismatch, retention across relaunch, no secrets, many signatures readable), HardeningTests
  (public files authenticated, incremental scan session, warm context — instrumented), KnownContractTests (3 library
  cases), TokenRegistryBundleTests (4), LegalDocumentTests (`documentIsBundledAndRendersWithoutLinks` and
  `aWrappedParagraphStaysOneBlock` on the bundled default texts). Pure pipeline rules may additionally get JVM tests
  with a scripted fake engine.
- Acceptance: `:core:data:testDebugUnitTest`, `:core:data:connectedDebugAndroidTest` (real engine, Wave-1 doubles).

**W1-design** — owns `core/designsystem/**`, `core/ui/**`, `strings_design.xml`, `strings_ui.xml`.
- Specs: design-system (all), ownbit-ui-patterns, wallets-pairing §3, scan-sign §11.3–§11.9, §19–§20,
  setup-backup §4, §9, activity-settings §1, gaps G4.2 (guard), G5.1 (sensitive effect).
- Provides: §1.10 and §1.11 exactly (no `:core:scanner` symbols), including `LocalWindowGateOpen` /
  `rememberWindowGateReleased()` honoured by `SignerAlertDialog` and `SignerBottomSheet`; drawables for chains and
  wallet icons; Roborazzi component screenshots (light, dark, font 2.0, RTL).
- iOS tests: PassphraseFieldTests (2 field cases), KnownContractTests (`everyCategoryHasItsOwnGlyph`),
  NFTReviewDisplayTests (the 4 view cases: glyph labels of `glyphAndSpokenLabels`, `summaryCardRendersEngineDisplays`,
  `rawUnitOrderPriceWrapsInsteadOfTruncating`, `detailsCardRendersTokenIDsAsPlainText` — Robolectric Compose), plus a
  gate test (a dialog requested while closed appears only after release; a shown dialog stays when it closes).
- Acceptance: `:core:designsystem:testDebugUnitTest`, `:core:ui:testDebugUnitTest`, `:core:ui:compileDebugKotlin`.

**W1-scanner** — owns `core/scanner/**`, `strings_scanner.xml`.
- Specs: scan-sign §6, §15.1, §23.3–§23.5; design-system §5.12, §5.23, §9.5 (QR); gaps G6 (scanner strings).
- Provides: §1.12 exactly, Hilt plugin in its build file and `ScannerModule` binding `QrCodeEncoder`.
- Consumes: `QrScanFrameGate`, `QrErrorCorrection`, `QrMatrix` from `:core:model`.
- iOS tests: QRScannerTests (already in model); add analyzer tests (strict UTF-8, inverted codes, multi-code frames) and
  encoder parity tests (EC level, margin, ECI only for non-ASCII).
- Acceptance: `:core:scanner:testDebugUnitTest`, `:core:scanner:compileDebugKotlin`.

**W1-security** — owns `core/security/**`, `strings_security.xml` (incl. `security_overlay_blocked`, the G7.4 App Lock set).
- Specs: app-shell §10–§13, §15.4–§15.9; DECISIONS §6, §9.4, §9.7; gaps G4, G5, G7.4.
- Provides: `DefaultAppAccessController`, `AndroidBiometricAuthenticator` (bound as `Optional<@UiTestOverride>.orElse`),
  `DefaultAppLifecycleMonitor`, `DefaultRecoverySessionFactory`, `DefaultLockWindowController`, `DefaultWindowGate`,
  `DefaultSecureWindowPolicy`, `SecurityModule`; fakes `FakeAppAccessController`, `FakeRecoverySessionFactory`,
  `FakeAppLifecycleMonitor` (drivable phase / capture / screenshot events) and `FakeBiometricAuthenticator` (fail,
  block until completed, count calls — the AppAccessTests double) in a `testing` package for Wave 2.
- Consumes: `SignerPreferences`, `WalletVault.verifyPassword`, `MonotonicClock` (Wave 1: own test doubles).
- iOS tests: AppAccessTests (8), RecoveryPrivacyTests (8), RecoveryScreenshotTests (5), HardeningTests (App Lock clock).
- Acceptance: `:core:security:testDebugUnitTest`.

### Wave 2 (parallel after Wave 1 merged)

**W2-shell** — owns `app/**` (incl. `src/debug`, `src/apk`, `src/play`), `feature/onboarding/**`, `strings_shell.xml`,
`strings_onboarding.xml`, `core/l10n/src/test/**/AppNameTest.kt`.
Also owns, in Wave 2 only, `core/l10n/build.gradle.kts` (a generated `values-en` copy of every `values/strings_*.xml`,
activity-settings §14.5 pitfall (a): with "System" and a device list `[en-GB, de]` Android would otherwise pick German).
- Specs: app-shell (all), ownbit-build-release, design-system §9.7–§9.10, activity-settings §10 (the Android legal
  texts), §11, §14.4–§14.5, §14.7–§14.8, gaps G3, G4.1, G5.3–G5.4 (UiAutomator vs sensitive nodes; content-capture
  exclusion on the root), G7.6, G8, DECISIONS §9.5.
- Provides: §2.1–§2.4 (stages, overlay, cover, lock window content, global alert, NavHost glue, tab bar, the `:app`
  duties listed under §2.1), Welcome and onboarding (Android wording of app-shell §6.2), manifest permission set of
  G8, `BuildInfo` (incl. `signingCertificateSha256`) and `@Named(ENGINE_TEST_OPERATIONS)` providers,
  `SignerApplication` (engine `prewarm()`, nothing that opens storage), `DebugUiTestHarness` + debug Hilt module
  (`UiTestEnvironment`, fixture `DeviceKeyStore` + public-data key, failing `BiometricAuthenticator`, fixtures created
  through `SignerStore.createWallet`, failure → `setError("UI fixture setup failed: …")`), flavor About content, the
  flavor legal texts `app/src/{apk,play}/assets/legal/TERMS_OF_USE.txt` and `PRIVACY_POLICY.txt` (derived from the iOS
  texts per activity-settings §10, marked for legal review), the default build number decision (`BuildInfo` KDoc).
- iOS tests: PrivacyShieldTests (2), AppNameTests (3), LegalDocumentTests `privacyPolicyDescribesWhatTheAppActuallyDoes`
  adapted to the Android claims per flavor (`LegalTextsTest` in `:app`); Robolectric root-stage and glue tests
  (Replace/Reset ordering, count 0, review → Scan, gate closed while locked), the `[en-GB, de]` resolution test.
- Acceptance: `:app:assembleApkDebug`, `:app:assemblePlayDebug`, `:app:testApkDebugUnitTest`, `:feature:onboarding:testDebugUnitTest`,
  `:app:assembleApkRelease` (R8 output has no `DebugUiTestHarness`; merged manifest equals the G8 set).

**W2-setup** — owns `feature/setup/**`, `strings_setup.xml`.
- Specs: setup-backup (all), app-shell §7, §13, gaps G5 (word grid, cards, QR, verify board, editor, passphrase), G6 (setup strings), DECISIONS §9.4.
- Provides: `SetupFlowHost`, `recoveryPhraseDestination`, `WalletSetupViewModel` (port of `WalletSetupModel` incl.
  the non-cancellable commit), every component of inventory rows 11–25 (`RecoveryBackupPanel`, `ProtectedRecoveryPhrase`,
  `RecoveryWordGrid`, `RecoveryQrSection`, `RecoveryCardsSheet`, `SecurityTipsSheet`, `BackupVerificationGrid/Chips`,
  phrase editor, status line, suggestion bar).
- Consumes: `SignerStore`, `SignerPreferences` (intro flag is written by `:app`), `Bip39`, `SecureRandomSource`,
  `RecoverySessionFactory`, `AppLifecycleMonitor`, `QrScannerScreen`, `QrCodeView`, `:core:ui`.
- iOS tests: RecoveryInputViewTests (1), RecoveryPrivacyViewTests (2), PassphraseFieldTests (setup case),
  RecoveryCardsTests `wordGridsDropColumnsBeforeCuttingWordsOff` (the §8.4.1 column/size rule, with `TextMeasurer`);
  ViewModel transition tests for setup-backup §2.2, the suggestion insertion (`(?U)[^\s,，、]+$`) and the
  "abandon 1" quirk of §11.8.
- Acceptance: `:feature:setup:testDebugUnitTest`.

**W2-wallets** — owns `feature/wallets/**`, `strings_wallets.xml`.
- Specs: wallets-pairing (all), app-shell §9.4, gaps G6 (wallet strings).
- Provides: `walletsDestinations` and inventory rows 26–43 (incl. 38b).
- Consumes: `SignerStore` (`addresses`, `pairing`, `walletPairing`, `preparedConnect`, security operations),
  `AppAccessController.confirmSensitiveAction`, `AppRouter`, `QrCodeView`, `:core:ui`.
- iOS tests: none left at unit level beyond model; add ViewModel tests (Addresses paging §6.1, Connect loading §7.6,
  delete order biometric → password).
- Acceptance: `:feature:wallets:testDebugUnitTest`.

**W2-sign** — owns `feature/sign/**`, `strings_sign.xml`.
- Specs: scan-sign (all), gaps G4.2 (sign, risk, authorize), G5.1 (authorize sheet), G6 (engine and progress strings).
- Provides: `scanDestinations`, inventory rows 45–55, `ScannerViewModel` (exact iOS `ScanSession` queue semantics).
- Consumes: `SignerStore`, `AppRouter.consumeScanRequest`, `QrScannerScreen`, `QrCodeView`, review components of `:core:ui`.
- iOS tests: ScanRequestRouterTests flow test at ViewModel level (request → review → result → Done never reopens the camera).
- Acceptance: `:feature:sign:testDebugUnitTest`.

**W2-activity-settings** — owns `feature/activity/**`, `feature/settings/**`, `strings_activity.xml`, `strings_settings.xml`.
- Specs: activity-settings (all), app-shell §11.11, gaps G6, G7.4, G7.5, DECISIONS §9.1, §9.5.
- Provides: `activityDestinations`, `settingsDestinations`, `settingsSharedDestinations`, inventory rows 44, 56–74.
- Consumes: `SignerStore` (`receipts`, `activityUnreadable`, `clearHistory`, `reviewStoredRequest`), `SignerPreferences`,
  `AppLocales`, `AppAccessController`, `ReferenceDataRepository`, `LegalDocumentRepository`, `BuildInfo`, `QrCodeView`,
  `:core:ui`.
- iOS tests: LegalDocumentTests (rendering without links: no `LinkAnnotation`/`ClickableSpan`), ActivityRecordTests
  `theDecodedEssentialsNameTheActionAmountAndCounterparty` at screen level, ViewModel tests for the directory search
  (normalisation, prefix vs contains) and segments.
- Acceptance: `:feature:activity:testDebugUnitTest`, `:feature:settings:testDebugUnitTest`.

### Integration (orchestrator)

`:e2e` module and G1–G3 suites (shared Gradle files), the rerun of `:core:data:connectedDebugAndroidTest` with the
real crypto bindings after the Wave-1 merge, the full `connectedApkDebugAndroidTest` run, the screenshot tour, the
translation pass of every `strings_<area>.xml`, and the release checklist (ownbit-build-release §7, plus the
third-party wallet round trips of DECISIONS §9.9).

---

## 4. Conventions

### 4.1 ViewModels, state and events
- `@HiltViewModel class XxxViewModel @Inject constructor(…) : ViewModel()` exposing `val uiState: StateFlow<XxxUiState>`
  (an immutable `data class`, `@Immutable` when it holds lists) built with `stateIn(viewModelScope, WhileSubscribed(5_000), initial)`.
- One-off events (navigate, toast, open a launcher) go through `Channel<XxxEvent>(Channel.BUFFERED).receiveAsFlow()`,
  collected in a `LaunchedEffect` with `repeatOnLifecycle(STARTED)` semantics; never a "consumed" flag in state.
- Screen = stateless `XxxContent(uiState, onAction)` + a thin `XxxScreen(viewModel = hiltViewModel())`. Previews and
  Roborazzi tests target `XxxContent`.
- Testability: business logic in the ViewModel takes interfaces only (`SignerStore`, `AppRouter`…); tests use the
  fakes from §3, `kotlinx-coroutines-test` and Turbine.
- `securityEpoch` change → the ViewModel cancels in-flight work, clears secrets, closes sensitive sheets;
  `navigationResetEpoch` → setup clears secrets if nothing was created.

### 4.2 Errors
- Store and repository failures surface as `SignerException` (text `UiText`) or `CancellationException`
  (silent, never shown). ViewModels catch with `runCatchingCancellable` and map to `UiText`.
- Screen-local errors (password wrong, scanner, export, rename) show inline (`Callout(tone = Danger)` or the field's
  error line) or the screen's own dialog, exactly where iOS shows them. App-wide failures set `SignerStore.setError`
  and appear in the single "Unable to Continue" dialog (title "Unable to Continue", one "OK").
- Never put exception messages from the platform into the UI, except the file-export failure text iOS also shows.

### 4.3 Strings
- iOS catalogue text: `stringResource(R.string.<name>)` (find by English in `tools/l10n/strings-index.tsv`), or
  `UiText.Ios(english)` when the text crosses a module boundary. Engine text: `ReviewText` / `IosStrings.lookup`.
- Non-catalogue iOS strings (gaps G6) and Android-only strings: add to your `strings_<area>.xml` with the exact iOS
  English (DECISIONS §9.2); brand names and protocol tokens `translatable="false"`.
- Numbers through `NumberFormat` of the current locale (Arabic uses Latin digits); dates through `DateTimeFormatter`
  with the app locale.
- `IosStrings.lookup` only knows the 773 catalogue keys. A `UiText.Ios(english)` whose English is **not** in the
  catalogue falls back to English, so code in a lower module that needs a translatable non-catalogue text uses
  `UiText.Resource(R.string.<area>_…)` from its own file; a feature that receives model English (e.g.
  `MatchingIssue.english`, `SigningReceipt.resultType`, `QrPayloadView.titleEnglish`) maps the known values to its
  resources and shows anything else verbatim.

**Non-catalogue iOS strings (gaps G6): one owner each.** All of them are translated (DECISIONS §9.2) except the tokens
marked `translatable="false"`. A string shown by several packages lives in the lowest module that shows it; the
others reference that resource (all `strings_*.xml` compile into the one `com.bittool.signer.core.l10n.R`).

| Strings (G6 rows) | File / owner | Notes |
|---|---|---|
| The 9 Kotlin-host engine messages; "The local activity record could not be read." / "…could not be saved. Any completed signature remains available."; "The encrypted wallet was deleted, but its public cache could not be removed." | `strings_data.xml` (W1-data) | produced by `SignerStore` as `UiText.Resource` |
| The 6 frame-gate messages (both purposes) | `strings_scanner.xml` (W1-scanner) | |
| "Setup required" (null fingerprint), "Payload (Hex)", "Payload (Text)", the `QrPayloadView.summary` unit "bytes" sentence parts, "NFT contract (not verified)", "+%d more (see Details)", "Review request", profile titles (Native SegWit, Taproot, Nested SegWit, Legacy, Standard, Trust), "Account XPUB", "Address", "Ethereum & EVM", "Fingerprints are stored as 32-bit numbers: %1$s is fingerprint %2$s." | `strings_ui.xml` (W1-design) | shown by `:core:ui` components (`WalletIdentityHeader`, `PayloadDisclosure` callers, `ReviewSummaryCard`, `ProfileMenu`, `ChainPicker`); brand/protocol tokens inside them stay `translatable="false"`; product names (Taproot, Account XPUB) translatable only if the owner says so. An unknown wallet name uses the catalogue key "Wallet" |
| "Biometric authentication was cancelled. Use a wallet password to continue." | none (debug harness English, G3) | W2-shell, not a resource |
| "Matching interrupted. Try again.", "No matching wallet", "Missing signer information", "Ambiguous wallet match", "Wallet setup required" (no period), "Unsupported signing protocol", "Reading request…", "Keep the animated code in view · %1$d parts", "psbt_base64 is the exact PSBT…" | `strings_sign.xml` (W2-sign) | `MatchingIssue` mapping; other engine messages stay verbatim |
| "This QR code does not contain a recovery phrase.", "Screenshot Detected", the two Android screenshot messages (DECISIONS §9.4), "%1$s: %2$s…", the weak-cards warning (positional, 4 args), "Empty", "Empty, next to fill" (verification-board values) | `strings_setup.xml` (W2-setup) | |
| "Password changed" (toast announcement) | `strings_wallets.xml` (W2-wallets) | or reuse the key "Password Changed" |
| "Deleted wallet", "Warnings shown", "High", "Partial signature created", "No Results for “%1$s”", "Check the spelling or try a new search.", the Distribution / Signing certificate rows | `strings_activity.xml` / `strings_settings.xml` (W2-activity-settings) | `resultType` values mapped, anything else verbatim |
| "This document is missing from the app bundle." (`LegalDocumentParser.MISSING`) | `strings_settings.xml` (W2-activity-settings) | the repository returns the English block; the legal views map it |
| The G7.4 App Lock set, `security_overlay_blocked` | `strings_security.xml` (W1-security) | lock-screen texts used by `:app` reference them |
| Onboarding Android wording (page 3 body, the replaced point) | `strings_onboarding.xml` (W2-shell) | |

### 4.4 Secrets
- Passwords, phrases, passphrases, seeds: `CharArray`/`ByteArray`/`SecretText`, owned and wiped by whoever created
  them; `TextFieldState` created with `remember {}` (never `rememberTextFieldState`/`rememberSaveable`); cleared right
  after submit and on `ON_STOP`.
- Never in routes, `SavedStateHandle`, `Bundle`, logs, `toString`, crash reports, analytics, clipboard or DataStore.
- Concealed recovery words are not composed at all (no blur over real text). Dialogs and sheets that can show secrets
  use `secure = true` (separate windows are not covered by the activity's `FLAG_SECURE`).

### 4.5 Haptics (design-system §6.2, §9.11)
Success: signature created, wallet created, copy, password changed. Selection: segmented controls, chain picker,
verification chips. Warning/reject: scanner error, wrong password, rejected verification. Nothing on plain navigation.

### 4.6 Motion
Shared-axis X for pushes, fade-through for tab switches, vertical slide for the setup overlay and modal routes,
`AnimatedContent` fade for the Scan Home/Review/Signature switch; springs without bounce (`dampingRatio = 1`) for
layout; honour "Remove animations" (`ANIMATOR_DURATION_SCALE == 0`).

### 4.7 Accessibility and test tags
48 dp targets, `contentDescription` from the iOS accessibility labels, `stateDescription` for toggles and reveal
states, headings via `semantics { heading() }`, live regions for toasts and scan progress, RTL-correct (mirrored
chevrons, LTR for addresses and hex). Test tags = the iOS `accessibilityIdentifier` strings, with
`Modifier.semantics { testTagsAsResourceId = true }` at the root so UiAutomator sees them.

### 4.8 Android-only hardening (DECISIONS §9.7, gaps G4–G5)
- `Modifier.obscuredTouchGuard { toast(security_overlay_blocked) }` on: `signing.risk`, `signing.authorize`,
  `authorize.sign`, `delete.backupConfirmed`, `vaultManagement.submit`, the delete dialog's "Delete", `setup.finish`,
  `settings.appLock`, `recovery.reveal`, `cards.reveal`, `backup.qr.reveal`, the "Clear Activity" confirmation, `appLock.unlock`.
- `AccessibilitySensitive(active = revealed)` on: revealed word grids, revealed cards, revealed recovery QR, the
  verification board, the non-empty phrase editor, a visible passphrase, the authorize sheet.

### 4.9 Tests
- Pure logic in `:core:model` (`:core:model:test`). Android-typed logic: Robolectric (`testDebugUnitTest`, SDK 36,
  JDK 21 flags already in build-logic). Compose UI: Robolectric + `createComposeRule`. Keystore and engine:
  instrumented (`connectedDebugAndroidTest`, emulator-5554).
- Name tests after the iOS case (`theCameraDoesNotReopenAfterASignatureIsFinished`) and say which iOS file they port.
- No real recovery phrase other than the published BIP39 vectors and the public fixture phrase; never assert on or
  print secrets in failure messages.

### 4.10 DI bindings per module

| Module | Hilt module | Bindings |
|---|---|---|
| :core:common | `DispatchersModule`, `ClockModule`, `StorageModule`, `UiTestEnvironmentModule` | dispatchers, `@ApplicationScope`, `MonotonicClock`, `WallClock`, `@SignerDataDir File`, `Optional<UiTestEnvironment>` |
| :core:engine | `EngineModule` (exists) | `SignerEngine` (needs `@Named(ENGINE_TEST_OPERATIONS) Boolean` from :app); `EngineCalls` by `@Inject` |
| :core:crypto | `CryptoModule` | `WalletVault`, `DeviceKeyStore`, `Optional<@UiTestOverride DeviceKeyStore>`, `JournalCipher`, `Bip39`, `Bip39Seed`, `SecureRandomSource` |
| :core:data | `DataModule` | `SignerStore`, `SignerPreferences`, `PublicWalletRepository`, `ReceiptRepository`, `ReferenceDataRepository`, `LegalDocumentRepository`, `TokenIconSource` |
| :core:security | `SecurityModule` | `AppAccessController`, `BiometricAuthenticator`, `Optional<@UiTestOverride BiometricAuthenticator>`, `AppLifecycleMonitor`, `RecoverySessionFactory`, `LockWindowController`, `WindowGate`, `SecureWindowPolicy` |
| :core:scanner | `ScannerModule` | `QrCodeEncoder` |
| :core:navigation | none | `AppRouter` (`@ActivityRetainedScoped @Inject`) |
| :app | `AppModule` (+ `DebugUiTestModule` in `src/debug`) | `BuildInfo`, `@Named(ENGINE_TEST_OPERATIONS)`; debug: `UiTestEnvironment`, `@UiTestOverride DeviceKeyStore`, `@UiTestOverride BiometricAuthenticator` |

---

## 5. iOS test → Android location

| iOS file (cases) | Android location | Status |
|---|---|---|
| ActivityRecordTests (10) | `:core:model` `signing/SigningTest`, `review/ReviewModelTest` (4 pure: oversized payload, no payloads, limit arithmetic, essentials); `:core:data` androidTest `SignerStoreActivityTest` (6 store cases, real engine) | pure done; store W1-data |
| AppAccessTests (8) | `:core:security` `AppAccessControllerTest` (fake clock starting at 100, `FakeBiometricAuthenticator`) | W1-security |
| AppNameTests (3) | `:core:l10n` `AppNameTest` (Robolectric, 21 locales) | W2-shell |
| BIP39LanguageTests (9) | `:core:crypto` `Bip39LanguageTest` | W1-crypto |
| BackupVerificationTests (7) | `:core:model` `BackupTest` | done |
| RecoveryCardsTests (4) | `:core:model` `BackupTest` (3 algorithm cases); `:feature:setup` `WordGridColumnsTest` (`wordGridsDropColumnsBeforeCuttingWordsOff`) | 3 done; 1 W2-setup |
| BitToolSignerTests / EngineIntegrationTests (4) | `:core:data` androidTest `SignerStoreEngineTest` (real engine, fixed seeds) | W1-data |
| CompatibleWalletsTests (4) | `:core:model` `CatalogueTest` | done |
| HardeningTests (12) | crypto (native seed vectors, seed = engine wallet [instrumented], wordlist canonical [instrumented, engine `wordlist` test op], SeedQR, draft, header bytes); data (public files authenticated, incremental scan session, warm context — instrumented); security (App Lock clock); model `ReviewModelTest` (address grouping, review decode) | model done; rest W1 |
| KnownContractTests (14) | `:core:model` `ReviewModelTest` (10 presentation cases); `:core:data` `ReferenceDataTest` (3 library cases); `:core:ui` (`everyCategoryHasItsOwnGlyph`) | model done; rest W1 |
| LegalDocumentTests (4) | `:core:model` `CatalogueTest` (`aBlankLineStartsANewBlock`); `:core:data` `LegalDocumentRepositoryTest` (`documentIsBundledAndRendersWithoutLinks` ×2, `aWrappedParagraphStaysOneBlock` on the default texts); `:app` `LegalTextsTest` (`privacyPolicyDescribes…`, adapted Android claims per flavor); `:feature:settings` (rendered without links) | parser done; rest W1-data / W2 |
| NFTReviewDisplayTests (22) | `:core:model` `review/NftReviewDisplayTest` (18 model cases + the spoken-ID half of `glyphAndSpokenLabels`); `:core:ui` (glyph labels, `summaryCardRendersEngineDisplays`, `rawUnitOrderPriceWrapsInsteadOfTruncating`, `detailsCardRendersTokenIDsAsPlainText`) | model done; 4 W1-design |
| PassphraseFieldTests (3) | `:core:ui` `PasswordFieldTest` (2); `:feature:setup` (`setupShowsTheEyeOnBothPassphraseFields`) | W1-design, W2-setup |
| PrivacyShieldTests (2) | `:app` `PrivacyCoverTest` | W2-shell |
| QRPayloadViewTests (3) | `:core:model` `pairing/PairingTest` | done |
| QRScannerTests (6) | `:core:model` `scanning/QrScanFrameGateTest` | done |
| RecoveryInputViewTests (1) | `:feature:setup` `PhraseEditorTest` (input traits: no autofill content type, no autocorrect, no capitalization, text keyboard, no-personalized-learning flag) | W2-setup |
| RecoveryPrivacyTests (8) | `:core:security` `RecoveryPrivacySessionTest` | W1-security |
| RecoveryPrivacyViewTests (2) | `:feature:setup` `ProtectedRecoveryPhraseTest` | W2-setup |
| RecoveryScreenshotTests (5) | `:core:security` `RecoveryScreenshotSessionTest` | W1-security |
| ReviewTextTests (5) | `:core:model` `review/ReviewTextTest`; `:core:l10n` `TextsTest` | done |
| ScanRequestRouterTests (4) | `:core:navigation` `AppRouterTest` | done |
| SecurityTests (12) | `:core:model` `CatalogueTest` (password rules); `:core:crypto` `WalletVaultTest`, `SecureRandomTest`, `Pbkdf2Test` (9 cases incl. the 3 reset cases); `:core:engine` androidTest (JS bridge fresh scope / no network: `runtimesHaveNoEvalAndNoNetwork`; the zero-entropy `mnemonic` and `deriveAccounts` vectors: W1-data `SignerStoreEngineTest`) | rules done; rest W1 |
| SessionTests (15) | `:core:data` androidTest `SignerStoreSessionTest` (real engine behind a `ControlledSigningEngine`-style decorator; Wave-1 vault/BIP39 doubles, rerun with real crypto at Integration) | W1-data |
| TokenRegistryBundleTests (4) | `:core:data` `ReferenceDataTest`, `TokenIconPackTest` | W1-data |
| BitToolSignerUITests (18 flows), LaunchTests, ScreenshotTour | `:e2e` (gaps G1–G3) | orchestrator |

Wave 0 counts: `:core:model` 109 tests, `:core:common` 7, `:core:navigation` 10, `:core:engine` 7 (5 new), `:core:l10n` 2.

---

## 6. Decisions taken here (deviations from the specs)

1. Text, hex and wipe helpers live in `:core:model.util`, not `:core:common` (DECISIONS §3 lists them under common),
   because the pure-JVM model needs them and cannot depend on an Android module. `:core:common` re-exports nothing.
2. `WalletVault` stays in `:core:crypto` (DECISIONS §3 "vault format v2") although gaps G7.3 suggests `:core:data`.
   On Android the device key is a Keystore operation, so `DeviceKeyStore` performs `seal`/`open` instead of returning key bytes.
3. The Activity journal is `receipts.bin` sealed by `JournalCipher` (DECISIONS §9.3), not the plain `receipts.json`
   of models-state §12.3.
4. BIP39 wordlists are assets (`core/crypto/src/main/assets/bip39`, gaps G8), not Java resources as vault-crypto §17.1 says.
5. The recovery-backup composables stay in `:feature:setup` (both consumers, the setup flow and `RecoveryPhraseRoute`,
   live there), not in `:core:ui` or a `:feature:backup` module.
6. QR display and encoding are in `:core:scanner` (`QrCodeView`, `ZxingQrCodeEncoder`) behind the model interface
   `QrCodeEncoder`, so `:core:data` can prefetch matrices without a UI dependency.
7. iOS sheets for Change Password, Delete and Repair, and the Recovery Phrase sheet, become modal routes (full pages
   without the bar): they hold password fields and long content, which full pages handle better with the IME on Android.
8. Setup is a root overlay with a ViewModel-owned step path (setup-backup §15.4), not a NavHost destination; it is
   composed by `:app` together with the onboarding pager, because features cannot depend on each other.
9. `UiText` carries iOS English keys across modules; ViewModels never resolve strings.
10. Debug UI-test replacements use `Optional<@UiTestOverride T>` bindings instead of replacing Hilt modules, which Hilt
    cannot do outside tests.
11. Shared changes made in Wave 0 (needed by the contracts): build-logic `configureUnitTestJvm` adds
    `--add-exports java.base/jdk.internal.access` and `--add-opens java.base/java.io` (Robolectric SDK 36 on JDK 21);
    Hilt plugin on `:core:common` and `:core:navigation`; `:core:l10n` `api(:core:model)`; `:core:crypto` depends on
    `:core:l10n`; `:core:scanner` depends on `:core:model`; feature entry-point stubs.
12. System Back on the Scan tab never rejects a request or finishes a signature (scan-sign §23.7 proposed Back = Reject
    / Done): it returns to the Wallets tab like every other tab root and the request stays behind the badge. Reject and
    Done remain the only ways to discard, as on iOS (which has no Back there).
13. Commit boundaries are enforced inside `SignerStore` (`NonCancellable`), not left to callers, and the store
    translates Kotlin-host engine messages (DECISIONS §9.2). Engine-authored messages stay verbatim.
14. `existingWallet` picks the first matching wallet in vault order (iOS: an unordered dictionary).
15. `SignerState.activityUnreadable` is Android-only state (no iOS counterpart) so the Activity screen can offer the
    recovery path activity-settings §2.2 flags; the default UI keeps iOS behaviour.
16. The window gate lives in two places: `:core:security` `WindowGate` (driven by `:app`) and its mirror
    `LocalWindowGateOpen` in `:core:designsystem`, because the dialogs/sheets that must honour it cannot depend on
    `:core:security`.
17. Activity's Signed Request keeps iOS's older warning-severity rule (`ReviewPresentation.isSevereStoredWarning`):
    parity, not a deviation, but easy to miss because the review components are shared.

Open questions for the owner: none blocking. `SignerStore.receive` returns progress as `Double` 0…1 (iOS); the
scanner UI converts it. The Android "Allow camera" button text (scan-sign §23.5) needs a new key if product wants it.
Also: the default build number (iOS "1.0.0 (1)" vs Android "1.0.0 (0)", `BuildInfo` KDoc); whether "BitTool Wallet"
lists "iOS · Android" (models-state §10.3; data unchanged until decided); whether Activity offers "Clear Activity" when
the journal is unreadable (`activityUnreadable`).
