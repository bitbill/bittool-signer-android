package com.bittool.signer.core.data

import com.bittool.signer.core.crypto.vault.PublicDataKey
import com.bittool.signer.core.engine.BundleDigest
import com.bittool.signer.core.model.Appearance
import com.bittool.signer.core.model.LegalBlock
import com.bittool.signer.core.model.LegalDocument
import com.bittool.signer.core.model.PublicWalletSnapshot
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.WalletPublicMetadata
import com.bittool.signer.core.model.registry.ReferenceData
import com.bittool.signer.core.model.signing.SigningReceipt
import kotlinx.coroutines.flow.StateFlow

/**
 * Non-secret preferences (app-shell.md §2.2, §15.2) in one DataStore file, same key names as iOS: `appearance`
 * (`system|light|dark`), `appLockEnabled.v1`, `onboardingVersionCompleted`. The interface language is not stored here:
 * AppCompat's per-app locale list is the source of truth. Never an "unlocked" flag.
 *
 * Debug UI-test runs (gaps.md G3, `UiTestRun`): the DataStore file is `UiTestRun.preferencesName` instead of the
 * production file, and the implementation applies the run's overrides **inside the first load, before [values] is
 * published** — `appLock` → `appLockEnabled.v1`, `appearance` → `appearance`, `fixtures > 0` →
 * `onboardingVersionCompleted = 1` — so App Lock (which stays resolving/locked until [values] is non-null) can never
 * observe the pre-override value. Without a run nothing differs from production.
 */
interface SignerPreferences {
    /** The current values, or null until the first read completed (App Lock treats null as locked: fail closed). */
    val values: StateFlow<PreferenceValues?>

    /** Awaits and returns the first (or current) values. */
    suspend fun load(): PreferenceValues

    suspend fun setAppearance(appearance: Appearance)
    suspend fun setAppLockEnabled(enabled: Boolean)
    suspend fun setOnboardingVersionCompleted(version: Int)
}

data class PreferenceValues(
    val appearance: Appearance = Appearance.System,
    val appLockEnabled: Boolean = false,
    val onboardingVersionCompleted: Int = 0,
) {
    /** iOS `onboardingVersionCompleted >= 1`. */
    val introductionCompleted: Boolean get() = onboardingVersionCompleted >= com.bittool.signer.core.model.ONBOARDING_VERSION
}

/**
 * The authenticated public sidecars (vault-crypto.md §10, iOS `PublicWalletRepository`):
 * `@SignerDataDir/PublicWallets/<lowercase-uuid>.json` = `{"body":<b64 sorted JSON>,"mac":<64 hex>,"schemaVersion":2}`,
 * body `{"displayName"?, "localWalletId", "publicIndex"?}`, file ≤ 600,000 bytes, body ≤ 300,000. Its own `Mutex`.
 * Used only by `DefaultSignerStore` (public for its tests).
 */
interface PublicWalletRepository {
    /** The status table of vault-crypto.md §10.5 (missing → needs completion; nil key / bad MAC / invalid → invalid). */
    suspend fun snapshot(walletId: WalletId, key: PublicDataKey?): PublicWalletSnapshot

    /** Writes the index with [displayName] (or the name of a valid existing file). */
    suspend fun save(metadata: WalletPublicMetadata, walletId: WalletId, displayName: String?, key: PublicDataKey)

    /** Sets the display name (trimmed, 1–40 graphemes, else `VaultError.InvalidName`), keeping a valid existing index. */
    suspend fun rename(walletId: WalletId, name: String, key: PublicDataKey)

    /** Deletes the sidecar if present. */
    suspend fun remove(walletId: WalletId)
}

/**
 * The Activity journal `@SignerDataDir/receipts.bin` (DECISIONS.md §9.3): magic + version + AES-GCM (Keystore key
 * `SignerFiles.RECEIPTS_KEY_ALIAS`, `setUnlockedDeviceRequired` where supported) over the iOS JSON array (newest
 * first; rules in `ReceiptJournal`, limits on the plaintext), written with `AtomicFiles`. A missing file reads as
 * empty; a bad tag, lost key or invalid content throws "Invalid activity record." and the file is never overwritten
 * silently (the store then shows "The local activity record could not be read.").
 *
 * Bounds: the sealed file is read with `AtomicFiles.readBounded(file, ReceiptJournal.FILE_LIMIT + 33)` (magic 4 +
 * version 1 + IV 12 + tag 16) **before** decrypting; the plaintext then goes through `ReceiptJournal.decode` (all iOS
 * limits). [write] encodes with `ReceiptJournal.encode`, which the store only calls with `ReceiptJournal.appended`
 * output (≤ 2 MiB by construction) or an empty list.
 */
interface ReceiptRepository {
    suspend fun read(): List<SigningReceipt>
    suspend fun write(records: List<SigningReceipt>)
}

/**
 * Bundled reference data for Settings (activity-settings.md §4, §9): decoded once per process on
 * `Dispatchers.Default` and cached.
 */
interface ReferenceDataRepository {
    /** Registry networks/tokens and known contracts (`ReferenceData.Empty` halves when a file is unreadable). */
    suspend fun load(): ReferenceData

    /** `registryVersion` read from the first 512 bytes of `token-registry.json` (regex), or null ("Unavailable"). */
    suspend fun registryVersion(): String?

    /**
     * SHA-256 and size of the bundled `assets/engine/signer.js`, computed on this device each time the Open Source page
     * asks (`SignerEngine.bundleDigest()`, gaps.md G7.5 — never a second hashing path); null on failure ("Unavailable").
     */
    suspend fun engineDigest(): BundleDigest?
}

/**
 * One notices file shown on Third-Party Notices, in this order: the engine's `legal/THIRD_PARTY_LICENSES.txt` (iOS
 * byte-identical), then the Android app's own dependencies `legal/THIRD_PARTY_LICENSES_ANDROID.txt` (gaps.md G8;
 * written by W1-data from the version catalogue: AndroidX/Compose/Material, Hilt/Dagger, Kotlin stdlib/coroutines/
 * serialization, QuickJS, ZXing, CameraX, androidx.biometric). [title] is the file's first line; [paragraphs] are the
 * rest split on blank lines (`"\n\n"`), verbatim.
 */
data class NoticeSection(val title: String, val paragraphs: List<String>)

/**
 * Bundled legal texts (activity-settings.md §10). Asset paths are flavor-overridable: `app/src/{apk,play}/assets/legal/…`
 * replace `:core:data`'s copies at merge time. English only by decision.
 */
interface LegalDocumentRepository {
    /** Parsed blocks (`LegalDocumentParser`), or one paragraph `LegalDocumentParser.MISSING` when the asset is absent. */
    suspend fun blocks(document: LegalDocument): List<LegalBlock>

    /** Notices split on blank lines, per file; "Third-party notices are included with the source distribution." when none. */
    suspend fun thirdPartyNotices(): List<NoticeSection>
}
